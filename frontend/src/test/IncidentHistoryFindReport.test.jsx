import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, fireEvent, within, act } from './test-utils.jsx'
import { TR } from '../i18n/tr.js'
import { EN } from '../i18n/en.js'
import { pressMenuTrigger } from './helpers/dropdownMenu.js'

/**
 * Olay ve Hata Geçmişi — "Bul ve Raporla" (2026-11-26, Paket 3) arayüz akışları: eşleşen alan ipucu, gelişmiş süzgeçler +
 * sıralama (eski sunucu uyarısı), eğilim isteğinin biçimi (süzgeçsiz = bugünkü iki tarihli çağrı), süzgece göre KPI notu,
 * dışa aktarım (CSV / Excel), seçenek yönetimi (yalnız yöneticiye), "Özeti indir" / "Yazdır" ve ayrıntılı döküm. Bilinmeyen
 * değer "—" kalır, uydurma "yok" / 0 yazılmaz.
 */

const { apiMock, perms, download } = vi.hoisted(() => {
  const deep = (obj) => new Proxy(obj, {
    get(t, prop) {
      if (prop === 'then' || typeof prop === 'symbol') return undefined
      if (prop in t) return typeof t[prop] === 'object' && t[prop] !== null ? deep(t[prop]) : t[prop]
      t[prop] = vi.fn(() => Promise.resolve({ success: true, data: [] }))
      return t[prop]
    },
  })
  return { apiMock: deep({ incidents: {}, admin: {} }), perms: { view: true, manage: true, del: true }, download: { ok: true, calls: [] } }
})

vi.mock('../api/client', () => ({
  api: apiMock,
  formatDate: (s) => s ?? '',
  formatDateSec: (s) => s ?? '',
  formatDateOnly: (s) => s ?? '',
}))
vi.mock('../contexts/PermissionsProvider.jsx', () => ({
  usePermissions: () => ({ perms: {}, canView: () => perms.view, canEdit: () => perms.manage, canExecute: () => perms.del, refresh: () => {} }),
  PermissionsProvider: ({ children }) => children,
}))
vi.mock('../components/ui/DateTimeField.jsx', () => ({
  default: ({ value, onChange, placeholder }) => <input aria-label={placeholder || 'date'} value={value || ''} onChange={(e) => onChange(e.target.value)} />,
}))
vi.mock('../components/ui/MarkdownEditor.jsx', () => ({
  // Markdown görseli (`![..](url)`) gerçek bileşen gibi <img> olur — yazdırmanın görsel beklemesi bunu sınar
  default: ({ value }) => {
    const m = /!\[([^\]]*)\]\(([^)\s]+)\)/.exec(String(value ?? ''))
    return <div data-slot="md-view">{m ? <img src={m[2]} alt={m[1]} loading="lazy" /> : value}</div>
  },
}))
vi.mock('@/components/shadcn/chart', async (importOriginal) => {
  const real = await importOriginal()
  return { ...real, ChartContainer: ({ children }) => <div data-slot="chart">{children}</div>, BarChart: () => <div data-slot="bar-probe" /> }
})
vi.mock('../utils/csvExport.js', async (importOriginal) => {
  const real = await importOriginal()
  return { ...real, downloadBlob: (name, blob) => { download.calls.push({ name, blob }); return download.ok } }
})

import { api } from '../api/client'
import IncidentHistoryPage from '../components/IncidentHistoryPage.jsx'
import IncidentReportActions, { PRINT_ATTR, waitForImages } from '../components/incidenthistory/IncidentReportActions.jsx'
import OptionsManagerDialog, { isMergeTarget } from '../components/incidenthistory/OptionsManagerDialog.jsx'
import HistoryBreakdown from '../components/incidenthistory/HistoryBreakdown.jsx'

const either = (key, ...args) => {
  const fill = (s) => args.reduce((acc, a, i) => acc.split(`{${i}}`).join(String(a)), s)
  return new RegExp(`^(${[TR[key], EN[key]].map((s) => fill(s).replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('|')})$`)
}
const has = (node, key, ...args) => {
  const txt = node?.textContent ?? ''
  const fill = (s) => args.reduce((acc, a, i) => acc.split(`{${i}}`).join(String(a)), s)
  return txt.includes(fill(TR[key])) || txt.includes(fill(EN[key]))
}

const rec = (over = {}) => ({
  id: 1, title: 'Ödeme servisinde gecikme', occurred_at: '2026-09-20T09:00:00', detected_at: '2026-09-20T09:12:00',
  resolved_at: '2026-09-20T11:05:00', severity: 'CRITICAL', status: 'RESOLVED', category: 'APPLICATION',
  error_code: 'E-500', function_code: '', channel_code: '', service: 'www.example.com', channel: 'Mobil Şube',
  team_id: 1, team_name: 'Takım A', rca_summary: 'Havuz', description: '', resolution_steps: 'Havuz büyütüldü',
  business_impact: '', affected_services: '', problem_types: 'Konfigürasyon', affected_app: '', affected_systems: '',
  affected_customers: 1200, affected_transactions: null, sla_breached: true, error_budget_burn_pct: null,
  duration_minutes: 125, runbook_url: null, tags: 'db, havuz', created_by: 'kisi.a', created_at: '2026-09-20T11:30:00',
  updated_by: null, updated_at: '2026-09-20T11:30:00', version: 0, ...over,
})
const page = (data, extra = {}) => ({ success: true, data, total: data.length, page: 0, size: 25, ...extra })
/** waitFor içinde: öğe yoksa fırlatır (yeniden dener), varsa döner. */
const flush = (ms = 0) => act(() => new Promise((r) => setTimeout(r, ms)))
const need = (sel) => { const n = document.querySelector(sel); if (!n) throw new Error('yok: ' + sel); return n }
const lastListArgs = () => api.incidents.list.mock.calls.at(-1)?.[0] ?? {}
const TRENDS = { summary: { total: 2, open: 1, critical: 1, sla_breached: 1, mttr_minutes: 125, mttr_sample: 1 },
  by_severity: { CRITICAL: 1, MEDIUM: 1 }, by_status: { OPEN: 1, RESOLVED: 1 }, daily: [] }

beforeEach(() => {
  vi.clearAllMocks()
  download.ok = true
  download.calls = []
  window.history.replaceState({}, '', '/?tab=incident-history')
  perms.view = true; perms.manage = true; perms.del = true
  api.incidents.list.mockResolvedValue(page([rec()]))
  api.incidents.trends.mockResolvedValue({ success: true, data: TRENDS })
  api.incidents.options.mockResolvedValue({ success: true, data: [] })
  api.incidents.teams.mockResolvedValue({ success: true, data: [{ id: 1, name: 'Takım A', active: true, can_manage_options: false }] })
  api.admin.getTeams.mockResolvedValue({ success: true, data: [{ id: 1, name: 'Takım A' }] })
})

afterEach(() => { document.body.removeAttribute(PRINT_ATTR) })

async function ready() {
  render(<IncidentHistoryPage />)
  await screen.findByText('Ödeme servisinde gecikme')
}

describe('arama ve süzgeçler', () => {
  it('arama başka bir alanda eşleştiyse satırda "Eşleşme: <alan>" ipucu; başlıkta eşleşince ipucu yok', async () => {
    window.history.replaceState({}, '', '/?tab=incident-history&ih_q=havuz')
    api.incidents.list.mockResolvedValue(page([rec({ match_field: 'resolution_steps' }), rec({ id: 2, title: 'Havuz', match_field: 'title' })]))
    await ready()
    await waitFor(() => expect(lastListArgs()).toMatchObject({ q: 'havuz' }))
    const hints = [...document.querySelectorAll('[data-slot="ih-match"]')]
    expect(hints.length).toBeGreaterThan(0)
    expect(new Set(hints.map((h) => h.getAttribute('data-field')))).toEqual(new Set(['resolution_steps']))
    expect(has(hints[0], 'inc.match.in', TR['inc.match.field.resolution_steps'])
      || has(hints[0], 'inc.match.in', EN['inc.match.field.resolution_steps'])).toBe(true)
  })

  it('gelişmiş süzgeçler: postmortem / etiket / benim açtıklarım sunucu adlarıyla gider; sayaç düğmede', async () => {
    await ready()
    fireEvent.click(document.querySelector('[data-slot="ih-more-filters"]'))
    await waitFor(() => expect(document.querySelector('[data-slot="ih-advanced-filters"]')).not.toBeNull())
    const adv = document.querySelector('[data-slot="ih-advanced-filters"]')
    fireEvent.change(within(adv).getByRole('combobox', { name: /^Postmortem$/ }), { target: { value: 'none' } })
    await waitFor(() => expect(lastListArgs()).toMatchObject({ postmortem: 'none' }))
    const tag = within(adv).getByRole('textbox', { name: either('inc.f.tag') })
    fireEvent.change(tag, { target: { value: ' prod ' } })
    fireEvent.keyDown(tag, { key: 'Enter' })
    await waitFor(() => expect(lastListArgs()).toMatchObject({ tag: 'prod' }))
    fireEvent.click(within(adv).getByRole('checkbox'))
    await waitFor(() => expect(lastListArgs()).toMatchObject({ created_by: '@me' }))
    expect(has(document.querySelector('[data-slot="ih-more-filters"]'), 'inc.f.moreN', 3)).toBe(true)
    // adres eşitlemesi gecikmeli (yazmadan ~300 ms sonra)
    await waitFor(() => expect(window.location.search).toContain('ih_pm=none'))
    await waitFor(() => expect(window.location.search).toContain('ih_mine=1'))
  })

  it('sıralama listeye gider (varsayılan gönderilmez); eski sunucu (ext_filters yok) → uyarı, yeni sunucu → uyarı yok', async () => {
    await ready()
    expect(lastListArgs().sort).toBeUndefined()
    expect(document.querySelector('[data-slot="ih-ext-unsupported"]')).toBeNull()
    fireEvent.change(screen.getByRole('combobox', { name: either('inc.sort.label') }), { target: { value: 'severity' } })
    await waitFor(() => expect(lastListArgs()).toMatchObject({ sort: 'severity' }))
    await waitFor(() => expect(document.querySelector('[data-slot="ih-ext-unsupported"]')).not.toBeNull())
    api.incidents.list.mockResolvedValue(page([rec()], { ext_filters: true }))
    fireEvent.change(screen.getByRole('combobox', { name: either('inc.sort.label') }), { target: { value: 'duration' } })
    await waitFor(() => expect(lastListArgs()).toMatchObject({ sort: 'duration' }))
    await waitFor(() => expect(document.querySelector('[data-slot="ih-ext-unsupported"]')).toBeNull())
  })

  it('eğilim: süzgeç yokken bugünkü iki tarihli çağrı (nesne değil)', async () => {
    await ready()
    await waitFor(() => expect(api.incidents.trends.mock.calls.length).toBeGreaterThanOrEqual(1))
    for (const call of api.incidents.trends.mock.calls) expect(typeof call[0]).not.toBe('object')
  })

  it('eğilim: gelişmiş süzgeçte nesne; kartların kendi boyutları (önem) gitmez', async () => {
    window.history.replaceState({}, '', '/?tab=incident-history&ih_tag=prod&ih_sev=HIGH')
    await ready()
    await waitFor(() => expect(api.incidents.trends.mock.calls.length).toBeGreaterThanOrEqual(1))
    for (const call of api.incidents.trends.mock.calls) {
      expect(call[0]).toMatchObject({ tag: 'prod' })
      expect(call[0]).not.toHaveProperty('severity')
    }
    expect(lastListArgs()).toMatchObject({ tag: 'prod', severity: 'HIGH' })
  })

  it('KPI: eğilim süzgece göreyse not yazar; değilse yazmaz', async () => {
    await ready()
    await waitFor(() => expect(document.querySelectorAll('[data-slot="ih-kpi-value"]').length).toBe(4))
    expect(document.querySelector('[data-slot="ih-kpi-filtered"]')).toBeNull()
    // yeni sunucu: eğilim süzgeçleri uyguladığını söyler (ext_filters) — eski sunucu notu yok
    api.incidents.trends.mockResolvedValue({ success: true, data: { ...TRENDS, filtered: true }, ext_filters: true })
    fireEvent.click(document.querySelector('[data-slot="ih-more-filters"]'))
    const adv = await waitFor(() => need('[data-slot="ih-advanced-filters"]'))
    fireEvent.change(within(adv).getByRole('combobox', { name: /^Postmortem$/ }), { target: { value: 'draft' } })
    await waitFor(() => expect(document.querySelector('[data-slot="ih-kpi-filtered"]')).not.toBeNull())
    expect(document.querySelector('[data-slot="ih-kpi-unfiltered"]')).toBeNull()
  })
})

describe('dışa aktarım', () => {
  it('CSV: listenin süzgeç + sıralamasıyla; başarıda bildirim; eski sunucu "bu sürümde yok"', async () => {
    window.history.replaceState({}, '', '/?tab=incident-history&ih_tag=prod&ih_sort=severity')
    api.incidents.exportCsv.mockResolvedValue({ success: true, name: 'olay-gecmisi.csv', truncated: false })
    await ready()
    pressMenuTrigger(document.querySelector('[data-action="export"]'))
    fireEvent.click(await waitFor(() => need('[data-slot="ih-export-csv"]')))
    await waitFor(() => expect(api.incidents.exportCsv).toHaveBeenCalled())
    expect(api.incidents.exportCsv.mock.calls[0][0]).toMatchObject({ tag: 'prod', sort: 'severity' })
    expect(api.incidents.exportCsv.mock.calls[0][0]).not.toHaveProperty('page')
    await screen.findByText(either('inc.export.done', 'olay-gecmisi.csv'))

    api.incidents.exportCsv.mockResolvedValue({ success: false, status: 404, code: 'NOT_FOUND' })
    pressMenuTrigger(document.querySelector('[data-action="export"]'))
    fireEvent.click(await waitFor(() => need('[data-slot="ih-export-csv"]')))
    await screen.findByText(either('inc.export.unsupported'))
  })

  it('Excel: JSON satırlarından tarayıcıda kurulur ve indirilir; tavan aşıldıysa uyarır', async () => {
    api.incidents.exportJson.mockResolvedValue({ success: true, data: {
      columns: ['id', 'title', 'severity'], rows: [{ id: 1, title: 'a', severity: 'HIGH' }], truncated: true, max_rows: 10000,
    } })
    await ready()
    pressMenuTrigger(document.querySelector('[data-action="export"]'))
    fireEvent.click(await waitFor(() => need('[data-slot="ih-export-xlsx"]')))
    await waitFor(() => expect(download.calls).toHaveLength(1))
    expect(download.calls[0].name).toMatch(/^olay-gecmisi-\d{8}-\d{4}\.xlsx$/)
    expect(download.calls[0].blob.size).toBeGreaterThan(0)
    await screen.findByText(either('inc.export.truncated', 10000))
  })

  it('Excel: indirme başlatılamazsa başarı yazmaz, hata bildirir', async () => {
    download.ok = false
    api.incidents.exportJson.mockResolvedValue({ success: true, data: { columns: ['id'], rows: [{ id: 1 }] } })
    await ready()
    pressMenuTrigger(document.querySelector('[data-action="export"]'))
    fireEvent.click(await waitFor(() => need('[data-slot="ih-export-xlsx"]')))
    await screen.findByText(either('inc.export.failed'))
  })
})

describe('seçenek yönetimi', () => {
  it('düğme yalnız seçenekleri yönetebildiği takımı olana; tıklayınca pencere o takımla açılır', async () => {
    await ready()
    await waitFor(() => expect(api.incidents.teams).toHaveBeenCalled())
    expect(document.querySelector('[data-action="manage-options"]')).toBeNull()
  })

  it('yönetilebilen takım varsa düğme görünür ve pencereyi açar (kayıt yetkisi yoksa görünmez)', async () => {
    api.incidents.teams.mockResolvedValue({ success: true, data: [
      { id: 1, name: 'Takım A', active: true, can_manage_options: true },
      { id: 2, name: 'Takım B', active: true, can_manage_options: false },
    ] })
    api.incidents.optionUsage.mockResolvedValue({ success: true, data: { values: [] } })
    await ready()
    const btn = await waitFor(() => need('[data-action="manage-options"]'))
    fireEvent.click(btn)
    const mgr = await waitFor(() => need('[data-slot="ih-options-manager"]'))
    const team = within(mgr).getByRole('combobox', { name: either('inc.colTeam') })
    expect([...team.options].map((o) => o.value)).toEqual(['', '1'])
    expect(team.value).toBe('1')
    await screen.findByText(either('inc.opt.none'))
  })

  it('pencere: tek takım seçili açılır, değer + yeni ad → onay → istek; sonuç bildirimi + yenileme', async () => {
    api.incidents.optionUsage.mockResolvedValue({ success: true, data: { type: 'CHANNEL', team_id: 1, values: [
      { value: 'IVR', records: 3, team_option: true, global_option: false },
      { value: 'Çağrı Merkezi', records: 2, team_option: false, global_option: true },
    ] } })
    api.incidents.renameOptions.mockResolvedValue({ success: true, data: { mode: 'merge', records: 4, to: 'Sesli', global_kept: ['Çağrı Merkezi'] } })
    const onChanged = vi.fn()
    render(<OptionsManagerDialog teams={[{ id: 1, name: 'Takım A', can_manage_options: true }]} onClose={() => {}} onChanged={onChanged} />)
    await waitFor(() => expect(document.querySelectorAll('[data-slot="ih-opt-value"]')).toHaveLength(2))
    expect(api.incidents.optionUsage.mock.calls[0].slice(0, 2)).toEqual(['CHANNEL', '1'])
    const submit = document.querySelector('[data-slot="ih-opt-submit"]')
    expect(submit).toBeDisabled()
    for (const li of document.querySelectorAll('[data-slot="ih-opt-value"]')) fireEvent.click(within(li).getByRole('checkbox'))
    fireEvent.change(document.querySelector('[data-slot="ih-opt-to"]'), { target: { value: 'Sesli' } })
    fireEvent.click(submit)
    const dialog = await screen.findByRole('dialog', { name: either('inc.opt.confirmMergeTitle') })
    expect(has(dialog, 'inc.opt.confirmBody', 'IVR, Çağrı Merkezi', 'Sesli', 5)).toBe(true)
    fireEvent.click(within(dialog).getByRole('button', { name: either('inc.opt.merge') }))
    await waitFor(() => expect(api.incidents.renameOptions).toHaveBeenCalledWith({ type: 'CHANNEL', team_id: 1, from: ['IVR', 'Çağrı Merkezi'], to: 'Sesli' }))
    await waitFor(() => expect(onChanged).toHaveBeenCalled())
  })

  it('istemci doğrulaması: kanal adında virgül / aynı ad alanın altında; istek gitmez', async () => {
    api.incidents.optionUsage.mockResolvedValue({ success: true, data: { values: [{ value: 'ATM', records: 1 }] } })
    render(<OptionsManagerDialog teams={[{ id: 1, name: 'Takım A', can_manage_options: true }]} onClose={() => {}} />)
    const li = await waitFor(() => need('[data-slot="ih-opt-value"]'))
    fireEvent.click(within(li).getByRole('checkbox'))
    fireEvent.change(document.querySelector('[data-slot="ih-opt-to"]'), { target: { value: 'A, B' } })
    fireEvent.click(document.querySelector('[data-slot="ih-opt-submit"]'))
    await waitFor(() => expect(document.querySelector('[data-field="to"]')).not.toBeNull())
    expect(has(document.querySelector('[data-field="to"]'), 'inc.opt.errComma')).toBe(true)
    fireEvent.change(document.querySelector('[data-slot="ih-opt-to"]'), { target: { value: 'ATM' } })
    fireEvent.click(document.querySelector('[data-slot="ih-opt-submit"]'))
    await waitFor(() => expect(has(document.querySelector('[data-field="to"]'), 'inc.opt.errSame')).toBe(true))
    expect(api.incidents.renameOptions).not.toHaveBeenCalled()
  })

  async function pickAndSubmit(to = 'Bankamatik') {
    const li = await waitFor(() => need('[data-slot="ih-opt-value"]'))
    fireEvent.click(within(li).getByRole('checkbox'))
    fireEvent.change(document.querySelector('[data-slot="ih-opt-to"]'), { target: { value: to } })
    fireEvent.click(document.querySelector('[data-slot="ih-opt-submit"]'))
    const dialog = await screen.findByRole('dialog', { name: either('inc.opt.confirmRenameTitle') })
    fireEvent.click(within(dialog).getByRole('button', { name: either('inc.opt.rename') }))
  }

  it('yanıt yok (zaman aşımı / ağ geçidi): sonuç bilinmiyor iletisi, liste YENİDEN okunur, liste sayfası tazelenir; körlemesine yeniden denenmez', async () => {
    api.incidents.optionUsage.mockResolvedValue({ success: true, data: { values: [{ value: 'ATM', records: 2 }] } })
    api.incidents.renameOptions.mockResolvedValue({ success: false, status: 0, code: 'REQUEST_TIMEOUT', error: 'zaman aşımı' })
    const onChanged = vi.fn()
    render(<OptionsManagerDialog teams={[{ id: 1, name: 'Takım A' }]} onClose={() => {}} onChanged={onChanged} />)
    await pickAndSubmit()
    await waitFor(() => expect(has(document.querySelector('[data-slot="ih-opt-failure"]'), 'inc.opt.unsure')).toBe(true))
    await waitFor(() => expect(api.incidents.optionUsage).toHaveBeenCalledTimes(2))
    expect(onChanged).toHaveBeenCalled()
    expect(api.incidents.renameOptions).toHaveBeenCalledTimes(1)
    // ileti yeniden okumadan sonra da yerinde; seçim sıfırlandı (yeni karar güncel sayılarla)
    await waitFor(() => expect(document.querySelector('[data-slot="ih-opt-failure"]')).not.toBeNull())
    expect(document.querySelector('[data-slot="ih-opt-submit"]')).toBeDisabled()
  })

  it('sunucunun alan hatası alanın altında; 409 / 403 / 503 iletisi pencerede; takım / tür değişince ileti gider', async () => {
    api.incidents.optionUsage.mockResolvedValue({ success: true, data: { values: [{ value: 'ATM', records: 2 }] } })
    api.incidents.renameOptions.mockResolvedValueOnce({ success: false, status: 400, code: 'VALIDATION_FAILED',
      field: 'to', fields: { to: 'Kayıt #7 için yeni değer alanın sınırını aşıyor.' }, error: 'Kayıt #7 için yeni değer alanın sınırını aşıyor.' })
    render(<OptionsManagerDialog teams={[{ id: 1, name: 'Takım A' }, { id: 2, name: 'Takım B' }]} onClose={() => {}} />)
    fireEvent.change(document.querySelector('[data-slot="ih-opt-team"]'), { target: { value: '1' } })
    await pickAndSubmit()
    await waitFor(() => expect(document.querySelector('[data-field="to"]')?.textContent).toContain('Kayıt #7'))
    api.incidents.renameOptions.mockResolvedValueOnce({ success: false, status: 403, code: 'FORBIDDEN', error: 'Bu takımın seçeneklerini yönetme yetkiniz yok' })
    fireEvent.change(document.querySelector('[data-slot="ih-opt-to"]'), { target: { value: 'Yeni' } })
    fireEvent.click(document.querySelector('[data-slot="ih-opt-submit"]'))
    fireEvent.click(within(await screen.findByRole('dialog', { name: either('inc.opt.confirmRenameTitle') }))
      .getByRole('button', { name: either('inc.opt.rename') }))
    await waitFor(() => expect(document.querySelector('[data-slot="ih-opt-failure"]')?.textContent).toContain('yetkiniz yok'))
    expect(api.incidents.optionUsage).toHaveBeenCalledTimes(1)   // kesin ret: yeniden okuma yok
    fireEvent.change(document.querySelector('[data-slot="ih-opt-type"]'), { target: { value: 'ERROR_CODE' } })
    await waitFor(() => expect(api.incidents.optionUsage).toHaveBeenCalledTimes(2))
    expect(document.querySelector('[data-slot="ih-opt-failure"]')).toBeNull()
  })

  it('istek sürerken takım ve tür seçicileri kilitli (sonuç başka listeye yazılmaz)', async () => {
    api.incidents.optionUsage.mockResolvedValue({ success: true, data: { values: [{ value: 'ATM', records: 2 }] } })
    let resolve
    api.incidents.renameOptions.mockImplementation(() => new Promise((r) => { resolve = r }))
    render(<OptionsManagerDialog teams={[{ id: 1, name: 'Takım A' }]} onClose={() => {}} />)
    await pickAndSubmit()
    await waitFor(() => expect(document.querySelector('[data-slot="ih-opt-team"]')).toBeDisabled())
    expect(document.querySelector('[data-slot="ih-opt-type"]')).toBeDisabled()
    await act(async () => { resolve({ success: true, data: { mode: 'rename', records: 2, to: 'Bankamatik' } }) })
    await waitFor(() => expect(document.querySelector('[data-slot="ih-opt-team"]')).not.toBeDisabled())
  })

  it('kullanım okunamazsa "okunamadı" + yeniden dene; eski sunucu "bu sürümde yok" (asla "değer yok")', async () => {
    api.incidents.optionUsage.mockResolvedValueOnce({ success: false, status: 500, error: 'x' })
    render(<OptionsManagerDialog teams={[{ id: 1, name: 'Takım A' }]} onClose={() => {}} />)
    await screen.findByText(either('inc.opt.loadFailed'))
    expect(document.querySelector('[data-slot="ih-opt-empty"]')).toBeNull()
    api.incidents.optionUsage.mockResolvedValueOnce({ success: false, status: 405 })
    fireEvent.click(screen.getByRole('button', { name: either('inc.retry') }))
    await screen.findByText(either('inc.opt.unsupported'))
  })
})

describe('özeti indir ve yazdır', () => {
  it('özet: kaydın TAZE hâli okunur, Markdown dosyası indirilir', async () => {
    api.incidents.get.mockResolvedValue({ success: true, data: rec({ id: 7, title: 'Ödeme Şube gecikme', business_impact: 'Etki metni' }) })
    render(<IncidentReportActions record={{ id: 7, title: 'eski başlık' }} />)
    fireEvent.click(document.querySelector('[data-slot="ih-summary-download"]'))
    await waitFor(() => expect(download.calls).toHaveLength(1))
    expect(api.incidents.get.mock.calls[0][0]).toBe(7)
    expect(download.calls[0].name).toBe('olay-7-odeme-sube-gecikme.md')
    const text = await download.calls[0].blob.text()
    expect(text.startsWith('# Ödeme Şube gecikme')).toBe(true)
    expect(text).toContain('Etki metni')
  })

  it('özet: kayıt okunamazsa hiçbir şey indirilmez, hata bildirilir', async () => {
    api.incidents.get.mockResolvedValue({ success: false, status: 503 })
    render(<IncidentReportActions record={{ id: 7, title: 'x' }} />)
    fireEvent.click(document.querySelector('[data-slot="ih-summary-download"]'))
    await screen.findByText(either('inc.sum.readFailed'))
    expect(download.calls).toHaveLength(0)
  })

  it('yazdır: taze kayıtla yazdırma görünümü body altında, print çağrılır; afterprint temizler', async () => {
    const printSpy = vi.fn()
    const orig = window.print
    window.print = printSpy
    try {
      api.incidents.get.mockResolvedValue({ success: true, data: rec({ id: 9, title: 'Yazdırılacak' }) })
      render(<IncidentReportActions record={{ id: 9, title: 'x' }} />)
      fireEvent.click(document.querySelector('[data-slot="ih-print"]'))
      await waitFor(() => expect(document.querySelector('[data-slot="ih-print-root"]')).not.toBeNull())
      const root = document.querySelector('[data-slot="ih-print-root"]')
      expect(root.parentElement).toBe(document.body)
      expect(root.textContent).toContain('Yazdırılacak')
      expect(document.body.getAttribute(PRINT_ATTR)).toBe('1')
      await waitFor(() => expect(printSpy).toHaveBeenCalledTimes(1))
      act(() => { window.dispatchEvent(new Event('afterprint')) })
      await waitFor(() => expect(document.querySelector('[data-slot="ih-print-root"]')).toBeNull())
      expect(document.body.hasAttribute(PRINT_ATTR)).toBe(false)
    } finally {
      window.print = orig
    }
  })
})

describe('ayrıntılı döküm', () => {
  it('MTTD, ortanca MTTR, müşteri toplamı ve takım kırılımı; satır tıklaması süzgeç', async () => {
    const onFilter = vi.fn()
    render(<HistoryBreakdown onFilter={onFilter} onCard={() => {}} trends={{
      ...TRENDS, filtered: true, capped: true, rows_read: 20000,
      summary: { ...TRENDS.summary, mttd_minutes: 12, mttd_median_minutes: 10, mttd_sample: 3, mttr_median_minutes: 60,
        customers_sum: 1500, customers_sample: 2 },
      by_team: [{ team_id: 2, team_name: 'Takım B', count: 2 }, { team_id: null, team_name: null, count: 1 }],
      by_channel: { ATM: 2 }, by_category: { NETWORK: 1 },
    }} />)
    expect(document.querySelector('[data-slot="ih-metric-customers"] [data-slot="ih-metric-value"]').textContent).toMatch(/1[.,]500/)
    expect(document.querySelector('[data-slot="ih-bd-capped"]')).not.toBeNull()
    const team = document.querySelector('[data-slot="ih-bd-team"]')
    expect(team.textContent).toContain('Takım B')
    expect(has(team, 'inc.bd.teamless')).toBe(true)
    fireEvent.click(within(team).getAllByRole('button')[0])
    expect(onFilter).toHaveBeenCalledWith({ team_id: '2' }, expect.objectContaining({ teamName: 'Takım B' }))
    // kanal satırı TAM öğe süzgeci (inceleme 2026-11-26) — döküm tam değerle sayar
    fireEvent.click(within(document.querySelector('[data-slot="ih-bd-channel"]')).getByRole('button'))
    expect(onFilter).toHaveBeenLastCalledWith({ channel_exact: 'ATM' }, expect.anything())
  })

  it('eski sunucu (yeni alanlar yok): MTTD / müşteri "—", takım kırılımı "—" — asla "kayıt yok"', () => {
    render(<HistoryBreakdown trends={TRENDS} onFilter={() => {}} onCard={() => {}} />)
    expect(document.querySelector('[data-slot="ih-metric-mttd"] [data-slot="ih-metric-value"]').textContent).toBe('—')
    expect(document.querySelector('[data-slot="ih-metric-customers"] [data-slot="ih-metric-value"]').textContent).toBe('—')
    expect(has(document.querySelector('[data-slot="ih-metric-mttd"]'), 'inc.bd.mttdNone')).toBe(false)
    expect(has(document.querySelector('[data-slot="ih-metric-customers"]'), 'inc.bd.customersNone')).toBe(false)
    const team = document.querySelector('[data-slot="ih-bd-team"]')
    expect(team.getAttribute('data-unknown')).toBe('true')
    expect(has(team, 'inc.bd.none')).toBe(false)
  })

  it('eğilim okunamadı: hepsi "—"', () => {
    render(<HistoryBreakdown trends={null} failed onFilter={() => {}} onCard={() => {}} />)
    for (const slot of ['ih-metric-mttd', 'ih-metric-mttr-median', 'ih-metric-customers', 'ih-metric-total']) {
      expect(document.querySelector(`[data-slot="${slot}"] [data-slot="ih-metric-value"]`).textContent).toBe('—')
    }
    expect(document.querySelector('[data-slot="ih-bd-team"]')).toBeNull()
  })
})

// ── Bağımsız inceleme düzeltmeleri (2026-11-26) ─────────────────────────────────────────────────────────────────────────

const isDaily = (c) => (c[0] && typeof c[0] === 'object' ? c[0].view === 'daily' : c[2]?.view === 'daily')
const summaryCalls = () => api.incidents.trends.mock.calls.filter((c) => !isDaily(c))
const dailyCalls = () => api.incidents.trends.mock.calls.filter(isDaily)
const kpiValue = (k) => document.querySelector(`[data-slot="ih-kpi"][data-key="${k}"] [data-slot="ih-kpi-value"]`)?.textContent

describe('inceleme 2026-11-26 — sayfa', () => {
  it('#1 KPI kartı aramayı KORUR: kutu, çip ve liste / eğilim isteğinin q değeri aynı kalır', async () => {
    await ready()
    const box = screen.getByRole('searchbox')
    fireEvent.change(box, { target: { value: 'dns' } })
    fireEvent.keyDown(box, { key: 'Enter' })
    await waitFor(() => expect(lastListArgs()).toMatchObject({ q: 'dns' }))
    fireEvent.click(document.querySelector('[data-slot="ih-kpi"][data-key="critical"]'))
    await waitFor(() => expect(lastListArgs()).toMatchObject({ q: 'dns', severity: 'CRITICAL' }))
    await flush(400)   // gecikmeli arama penceresi geçti — yine aynı
    expect(lastListArgs()).toMatchObject({ q: 'dns', severity: 'CRITICAL' })
    expect(box.value).toBe('dns')
    expect(document.querySelector('[data-slot="ih-active-filters"] [data-filter="q"]')).not.toBeNull()
    const lastSummary = summaryCalls().at(-1)
    expect(lastSummary[0]).toMatchObject({ q: 'dns' })
    expect(lastSummary[0]).not.toHaveProperty('severity')
    // döküm önem satırı (aynı onCard) da aramayı korur
    fireEvent.click(document.querySelector('[data-slot="stats-toggle"]'))
    const sev = await waitFor(() => need('[data-slot="ih-bd-severity"]'))
    fireEvent.click(within(sev).getAllByRole('button')[1])   // HIGH
    await waitFor(() => expect(lastListArgs()).toMatchObject({ q: 'dns', severity: 'HIGH' }))
    expect(box.value).toBe('dns')
  })

  it('#2 başlık eylemleri data-action taşır, data-slot="button" KALIR (PageHeader dokunmatik büyütmesi)', async () => {
    api.incidents.teams.mockResolvedValue({ success: true, data: [{ id: 1, name: 'Takım A', active: true, can_manage_options: true }] })
    await ready()
    await waitFor(() => need('[data-action="manage-options"]'))
    for (const a of ['export', 'manage-options', 'refresh', 'quick', 'new']) {
      const el = document.querySelector(`[data-action="${a}"]`)
      expect(el, a).not.toBeNull()
      expect(el.getAttribute('data-slot'), a).toBe('button')
    }
  })

  it('#5 döküm kanal satırı TAM öğe (channel_exact) süzer: istek + çip + adres; eski pod için alt dize yedeği', async () => {
    api.incidents.trends.mockResolvedValue({ success: true, data: { ...TRENDS, by_channel: { ATM: 2 }, by_team: [] }, ext_filters: true })
    api.incidents.list.mockResolvedValue(page([rec()], { ext_filters: true }))
    await ready()
    fireEvent.click(document.querySelector('[data-slot="stats-toggle"]'))
    const ch = await waitFor(() => need('[data-slot="ih-bd-channel"]'))
    fireEvent.click(within(ch).getByRole('button'))
    await waitFor(() => expect(lastListArgs()).toMatchObject({ channel_exact: 'ATM', channel: 'ATM' }))
    const chip = await waitFor(() => need('[data-slot="ih-active-filters"] [data-filter="channel_exact"]'))
    expect(has(chip, 'inc.chip.channelExact', 'ATM')).toBe(true)
    expect(document.querySelector('[data-slot="ih-active-filters"] [data-filter="channel"]')).toBeNull()
    await waitFor(() => expect(summaryCalls().at(-1)[0]).toMatchObject({ channel_exact: 'ATM' }))
    await waitFor(() => expect(new URLSearchParams(window.location.search).get('ih_chx')).toBe('ATM'))
    expect(document.querySelector('[data-slot="ih-ext-unsupported"]')).toBeNull()
    fireEvent.click(chip)
    await waitFor(() => expect(lastListArgs()).not.toHaveProperty('channel_exact'))
    expect(lastListArgs()).not.toHaveProperty('channel')
  })

  it('#5 channel_exact adresten okunur; eski pod (ext_filters yok) → uyarı', async () => {
    window.history.replaceState({}, '', '/?tab=incident-history&ih_chx=Mobil%20%C5%9Eube')
    await ready()
    expect(lastListArgs()).toMatchObject({ channel_exact: 'Mobil Şube', channel: 'Mobil Şube' })
    await waitFor(() => need('[data-slot="ih-ext-unsupported"]'))
  })

  it('#6 döküm takım satırı seçicide olmayan takımı ADIYLA ekler (seçici + çip)', async () => {
    api.incidents.trends.mockResolvedValue({ success: true, data: { ...TRENDS, by_team: [{ team_id: 9, team_name: 'Paylaşılan Takım', count: 1 }] }, ext_filters: true })
    await ready()
    fireEvent.click(document.querySelector('[data-slot="stats-toggle"]'))
    const tm = await waitFor(() => need('[data-slot="ih-bd-team"]'))
    fireEvent.click(within(tm).getByRole('button'))
    await waitFor(() => expect(lastListArgs()).toMatchObject({ team_id: '9' }))
    const sel = screen.getByRole('combobox', { name: either('inc.colTeam') })
    expect(sel.value).toBe('9')
    expect(sel.selectedOptions[0].textContent).toBe('Paylaşılan Takım')
    const chip = need('[data-slot="ih-active-filters"] [data-filter="team_id"]')
    expect(has(chip, 'inc.chip.team', 'Paylaşılan Takım')).toBe(true)
  })

  it('#6 adresteki, listede olmayan takım: seçici "tüm takımlar" demez, o kimliği "adı okunamayan takım" diye gösterir', async () => {
    window.history.replaceState({}, '', '/?tab=incident-history&ih_team=77')
    await ready()
    const sel = screen.getByRole('combobox', { name: either('inc.colTeam') })
    expect(sel.value).toBe('77')
    expect(sel.selectedOptions[0].textContent).toMatch(either('inc.teamUnknown'))
  })

  it('#7 günlük grafik view=daily ister (süzgeçsiz: iki tarih + opts); özet isteği view taşımaz', async () => {
    await ready()
    fireEvent.click(document.querySelector('[data-slot="stats-toggle"]'))
    await waitFor(() => expect(dailyCalls().length).toBeGreaterThanOrEqual(1))
    const d = dailyCalls().at(-1)
    expect(typeof d[0]).toBe('string')
    expect(typeof d[1]).toBe('string')
    expect(d[2]).toEqual({ view: 'daily' })
    expect(summaryCalls().length).toBeGreaterThanOrEqual(1)
    for (const c of summaryCalls()) expect(c[2]).toBeUndefined()
  })

  it('#7 süzgeçli günlük grafik: nesne çağrısı süzgeç + view=daily', async () => {
    window.history.replaceState({}, '', '/?tab=incident-history&ih_tag=prod')
    await ready()
    fireEvent.click(document.querySelector('[data-slot="stats-toggle"]'))
    await waitFor(() => expect(dailyCalls().length).toBeGreaterThanOrEqual(1))
    expect(dailyCalls().at(-1)[0]).toMatchObject({ tag: 'prod', view: 'daily' })
    expect(summaryCalls().every((c) => c[0]?.view === undefined)).toBe(true)
  })

  it('#13 eski sunucu: eğilim yanıtında ext_filters yok + süzgeç → "sayılar süzülmüyor" notu (süzgece göre notu yok)', async () => {
    window.history.replaceState({}, '', '/?tab=incident-history&ih_tag=prod')
    api.incidents.trends.mockResolvedValue({ success: true, data: { ...TRENDS, filtered: true } })
    await ready()
    const note = await waitFor(() => need('[data-slot="ih-kpi-unfiltered"]'))
    expect(has(note, 'inc.kpiUnfilteredOld')).toBe(true)
    expect(document.querySelector('[data-slot="ih-kpi-filtered"]')).toBeNull()
  })

  it('#13 süzgeç yokken (bugünkü iki tarihli çağrı) not çıkmaz', async () => {
    await ready()
    await waitFor(() => expect(document.querySelectorAll('[data-slot="ih-kpi-value"]').length).toBe(4))
    expect(document.querySelector('[data-slot="ih-kpi-unfiltered"]')).toBeNull()
  })

  it('#14 aynı süzgecin özet yenilemesi düşerse eski sayılar + "yenilenemedi" şeridi; yeniden dene başarılıysa kalkar', async () => {
    await ready()
    await waitFor(() => expect(kpiValue('total')).toBe('2'))
    expect(document.querySelector('[data-slot="ih-kpi-stale"]')).toBeNull()
    api.incidents.trends.mockResolvedValue({ success: false, status: 503, error: 'x' })
    fireEvent.click(document.querySelector('[data-action="refresh"]'))
    const strip = await waitFor(() => need('[data-slot="ih-kpi-stale"]'))
    expect(has(strip, 'inc.kpiStale')).toBe(true)
    expect(kpiValue('total')).toBe('2')
    api.incidents.trends.mockResolvedValue({ success: true, data: { ...TRENDS, summary: { ...TRENDS.summary, total: 3 } } })
    fireEvent.click(within(strip).getByRole('button', { name: either('inc.retry') }))
    await waitFor(() => expect(document.querySelector('[data-slot="ih-kpi-stale"]')).toBeNull())
    expect(kpiValue('total')).toBe('3')
  })

  it('#14 başka aralığın özeti düşerse eski sayı KALMAZ ("—"), şerit çıkmaz', async () => {
    await ready()
    await waitFor(() => expect(kpiValue('total')).toBe('2'))
    api.incidents.trends.mockResolvedValue({ success: false, status: 503 })
    fireEvent.change(screen.getByLabelText(either('inc.since')), { target: { value: '2026-08-01' } })
    await waitFor(() => expect(kpiValue('total')).toBe('—'))
    expect(document.querySelector('[data-slot="ih-kpi-stale"]')).toBeNull()
  })
})

describe('inceleme 2026-11-26 — seçenek yöneticisi', () => {
  it('#11 isMergeTarget: hedef KAYITLARDA geçiyorsa birleştirme; yalnız seçenek satırı (0 kayıt) değil', () => {
    const vals = [{ value: 'IVR', records: 3 }, { value: 'Sesli', records: 0, team_option: true }, { value: 'ATM', records: 2 }]
    expect(isMergeTarget(vals, new Set(['IVR']), 'sesli')).toBe(false)
    expect(isMergeTarget(vals, new Set(['IVR']), ' atm ')).toBe(true)
    expect(isMergeTarget(vals, new Set(['IVR', 'ATM']), 'ATM')).toBe(false)   // hedef seçili kaynaklardan biri
    expect(isMergeTarget(vals, new Set(['IVR']), '')).toBe(false)
    expect(isMergeTarget(null, new Set(), 'x')).toBe(false)
  })

  it('#11 hedef yalnız seçenek satırıysa düğme ve onay "yeniden adlandır" der (sunucu kuralı)', async () => {
    api.incidents.optionUsage.mockResolvedValue({ success: true, data: { values: [
      { value: 'IVR', records: 3, team_option: true }, { value: 'Sesli', records: 0, team_option: true },
    ] } })
    render(<OptionsManagerDialog teams={[{ id: 1, name: 'Takım A' }]} onClose={() => {}} />)
    const li = await waitFor(() => need('[data-slot="ih-opt-value"][data-value="IVR"]'))
    fireEvent.click(within(li).getByRole('checkbox'))
    fireEvent.change(document.querySelector('[data-slot="ih-opt-to"]'), { target: { value: 'Sesli' } })
    const submit = document.querySelector('[data-slot="ih-opt-submit"]')
    expect(has(submit, 'inc.opt.rename')).toBe(true)
    fireEvent.click(submit)
    await screen.findByRole('dialog', { name: either('inc.opt.confirmRenameTitle') })
  })

  it('#11 hedef kayıtlarda geçiyorsa birleştir', async () => {
    api.incidents.optionUsage.mockResolvedValue({ success: true, data: { values: [
      { value: 'IVR', records: 3 }, { value: 'Sesli', records: 2 },
    ] } })
    render(<OptionsManagerDialog teams={[{ id: 1, name: 'Takım A' }]} onClose={() => {}} />)
    const li = await waitFor(() => need('[data-slot="ih-opt-value"][data-value="IVR"]'))
    fireEvent.click(within(li).getByRole('checkbox'))
    fireEvent.change(document.querySelector('[data-slot="ih-opt-to"]'), { target: { value: 'sesli' } })
    expect(has(document.querySelector('[data-slot="ih-opt-submit"]'), 'inc.opt.merge')).toBe(true)
  })

  it('#8 kesik kullanım listesi: not + "en az N kayıt"; kesik değilse not yok', async () => {
    api.incidents.optionUsage.mockResolvedValue({ success: true, data: { truncated: true, rows_read: 20000, max_rows: 20000,
      values: [{ value: 'ATM', records: 19000 }] } })
    const { unmount } = render(<OptionsManagerDialog teams={[{ id: 1, name: 'Takım A' }]} onClose={() => {}} />)
    const note = await waitFor(() => need('[data-slot="ih-opt-truncated"]'))
    expect(has(note, 'inc.opt.truncated', 20000)).toBe(true)
    expect(has(need('[data-slot="ih-opt-value"]'), 'inc.opt.recordsAtLeast', 19000)).toBe(true)
    unmount()
    api.incidents.optionUsage.mockResolvedValue({ success: true, data: { truncated: false, rows_read: 3, values: [{ value: 'ATM', records: 3 }] } })
    render(<OptionsManagerDialog teams={[{ id: 1, name: 'Takım A' }]} onClose={() => {}} />)
    const li = await waitFor(() => need('[data-slot="ih-opt-value"]'))
    expect(has(li, 'inc.opt.records', 3)).toBe(true)
    expect(document.querySelector('[data-slot="ih-opt-truncated"]')).toBeNull()
  })
})

describe('inceleme 2026-11-26 — yazdırma görselleri bekler (#12)', () => {
  it('waitForImages: görsel yoksa hemen; tembel görsel eager olur; load / error ile çözülür; süre dolunca da; iptal çözmez', async () => {
    await expect(waitForImages(null).promise).resolves.toBeUndefined()
    const root = document.createElement('div')
    root.innerHTML = '<img src="/a.png" loading="lazy" alt="a"><img src="/b.png" alt="b">'
    const imgs = root.querySelectorAll('img')
    let done = false
    const w = waitForImages(root, 10_000)
    w.promise.then(() => { done = true })
    expect(imgs[0].getAttribute('loading')).toBe('eager')
    imgs[0].dispatchEvent(new Event('load'))
    await flush()
    expect(done).toBe(false)
    imgs[1].dispatchEvent(new Event('error'))
    await flush()
    expect(done).toBe(true)

    const r2 = document.createElement('div')
    r2.innerHTML = '<img src="/c.png" alt="c">'
    const t0 = Date.now()
    await waitForImages(r2, 60).promise
    expect(Date.now() - t0).toBeGreaterThanOrEqual(50)

    const r3 = document.createElement('div')
    r3.innerHTML = '<img src="/d.png" alt="d">'
    let resolved = false
    const w3 = waitForImages(r3, 30)
    w3.promise.then(() => { resolved = true })
    w3.cancel()
    await flush(80)
    expect(resolved).toBe(false)
  })

  it('yazdır: görsel inene dek print çağrılmaz, düğme meşgul; inince print', async () => {
    const printSpy = vi.fn()
    const orig = window.print
    window.print = printSpy
    try {
      api.incidents.get.mockResolvedValue({ success: true, data: rec({ id: 9, title: 'Görselli', description: '![ekran](/api/incidents/images/3)' }) })
      render(<IncidentReportActions record={{ id: 9, title: 'x' }} />)
      fireEvent.click(document.querySelector('[data-slot="ih-print"]'))
      const img = await waitFor(() => need('[data-slot="ih-print-root"] img'))
      await flush(200)
      expect(img.getAttribute('loading')).toBe('eager')
      expect(printSpy).not.toHaveBeenCalled()
      expect(document.querySelector('[data-slot="ih-print"]')).toBeDisabled()
      act(() => { img.dispatchEvent(new Event('load')) })
      await waitFor(() => expect(printSpy).toHaveBeenCalledTimes(1))
      await waitFor(() => expect(document.querySelector('[data-slot="ih-print"]')).not.toBeDisabled())
      act(() => { window.dispatchEvent(new Event('afterprint')) })
      await waitFor(() => expect(document.querySelector('[data-slot="ih-print-root"]')).toBeNull())
    } finally {
      window.print = orig
    }
  })

  it('yazdır: pencere kapanırsa (bileşen sökülür) görsel beklemesi iptal, print çağrılmaz, öznitelik kalkar', async () => {
    const printSpy = vi.fn()
    const orig = window.print
    window.print = printSpy
    try {
      api.incidents.get.mockResolvedValue({ success: true, data: rec({ id: 9, description: '![e](/api/incidents/images/4)' }) })
      const { unmount } = render(<IncidentReportActions record={{ id: 9, title: 'x' }} />)
      fireEvent.click(document.querySelector('[data-slot="ih-print"]'))
      const img = await waitFor(() => need('[data-slot="ih-print-root"] img'))
      await flush(100)
      unmount()
      img.dispatchEvent(new Event('load'))
      await flush(50)
      expect(printSpy).not.toHaveBeenCalled()
      expect(document.body.hasAttribute(PRINT_ATTR)).toBe(false)
    } finally {
      window.print = orig
    }
  })
})
