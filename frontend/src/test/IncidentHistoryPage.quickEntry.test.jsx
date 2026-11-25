import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, fireEvent, within, act } from './test-utils.jsx'
import { TR } from '../i18n/tr.js'
import { activeShortcutContexts, INCIDENT_HISTORY_CONTEXT } from '../utils/shortcutContexts.js'

/**
 * Olay ve Hata Geçmişi — "Hızlı Giriş" (2026-11-20) sayfa davranışları:
 *  - liste `fields=summary` ister; özet satırdan açılan ayrıntı tam kaydı okur (markdown "yükleniyor" → içerik; okunamazsa
 *    "okunamadı + yeniden dene"),
 *  - seçenekler: kanal açılışta, kalan dördü ilk form açılışında BİR kez; günlük trend yalnız döküm açılınca,
 *  - Hızlı kayıt düğmesi sade formu açar; satırdaki durum rozeti tek tıkla durumu kaydeder (liste yenilenir),
 *  - ayrıntıdan Çoğalt (özet satırda önce tam kayıt okunur),
 *  - kısayollar: N yeni olay, E açık ayrıntıyı düzenle (yazarken / "g" dizisinde çalışmaz); bağlam `?` listesinde.
 */

const { apiMock, perms } = vi.hoisted(() => {
  const deep = (obj) => new Proxy(obj, {
    get(t, prop) {
      if (prop === 'then' || typeof prop === 'symbol') return undefined
      if (prop in t) return typeof t[prop] === 'object' && t[prop] !== null ? deep(t[prop]) : t[prop]
      t[prop] = vi.fn(() => Promise.resolve({ success: true, data: [] }))
      return t[prop]
    },
  })
  return { apiMock: deep({ incidents: {}, admin: {} }), perms: { view: true, manage: true, del: true } }
})

vi.mock('../api/client', () => ({
  api: apiMock,
  formatDate: (s) => s ?? '',
  formatDateSec: (s) => s ?? '',
  formatDateOnly: (s) => s ?? '',
}))
vi.mock('../contexts/PermissionsProvider.jsx', () => ({
  usePermissions: () => ({
    perms: {},
    canView: () => perms.view,
    canEdit: () => perms.manage,
    canExecute: () => perms.del,
    refresh: () => {},
  }),
  PermissionsProvider: ({ children }) => children,
}))
vi.mock('../components/ui/DateTimeField.jsx', () => ({
  default: ({ value, onChange, placeholder }) => (
    <input aria-label={placeholder || 'date'} value={value || ''} onChange={(e) => onChange(e.target.value)} />
  ),
}))
vi.mock('../components/ui/SmartDateTimeField.jsx', () => ({
  default: ({ id, value, onChange }) => <input id={id} value={value || ''} onChange={(e) => onChange(e.target.value)} />,
}))
vi.mock('../components/ui/MarkdownEditor.jsx', () => ({
  default: ({ value, onChange, editable = true }) => (editable
    ? <textarea data-slot="md-edit" value={value || ''} onChange={(e) => onChange(e.target.value)} />
    : <div data-slot="md-view">{value}</div>),
}))
vi.mock('@/components/shadcn/chart', async (importOriginal) => {
  const real = await importOriginal()
  return { ...real, ChartContainer: ({ children }) => <div data-slot="chart">{children}</div>, BarChart: () => <div data-slot="bar-probe" /> }
})

import { api } from '../api/client'
import IncidentHistoryPage from '../components/IncidentHistoryPage.jsx'

const rec = (over = {}) => ({
  id: 1, version: 4, title: 'Ödeme servisinde gecikme', occurred_at: '2026-09-20T09:00:00', detected_at: '2026-09-20T09:12:00',
  resolved_at: null, severity: 'CRITICAL', status: 'OPEN', category: 'APPLICATION', error_code: 'E-500', function_code: '',
  channel_code: '', service: '', channel: '', team_id: 1, team_name: 'Takım A', affected_services: 'Ödeme',
  problem_types: '', affected_app: '', affected_systems: '', affected_customers: null, affected_transactions: null,
  sla_breached: false, error_budget_burn_pct: null, duration_minutes: null, runbook_url: null, tags: '', can_edit: true,
  created_by: 'kisi.a', created_at: '2026-09-20T11:30:00', updated_at: '2026-09-20T11:30:00', ...over,
})
/** Özet satır: dört markdown metni YOK, işaret var (sunucunun `fields=summary` yanıtı). */
const summaryRow = (over = {}) => ({ ...rec(over), markdown_omitted: true })
const FULL = rec({
  rca_summary: 'Bağlantı havuzu tükendi.', description: 'Açıklama ![g](/api/incidents/images/3)', resolution_steps: 'Havuz büyütüldü.',
  business_impact: 'Ödemeler gecikti.',
})
const page = (data, total = data.length) => ({ success: true, data, total, page: 0, size: 25 })
const deferred = () => { let resolve; const p = new Promise((r) => { resolve = r }); return { p, resolve } }
const flush = (ms = 0) => act(() => new Promise((r) => setTimeout(r, ms)))
const optionTypes = () => api.incidents.options.mock.calls.map((c) => c[0])
const dailyCalls = () => api.incidents.trends.mock.calls.filter(([since, until]) => since && until)

beforeEach(() => {
  vi.clearAllMocks()
  localStorage.setItem('site-monitor-lang', 'tr')
  window.history.replaceState({}, '', '/?tab=incident-history')
  perms.view = true; perms.manage = true; perms.del = true
  api.incidents.list.mockResolvedValue(page([summaryRow(), summaryRow({ id: 2, title: 'DNS çözümleme hatası', status: 'INVESTIGATING' })]))
  api.incidents.get.mockResolvedValue({ success: true, data: FULL })
  api.incidents.trends.mockResolvedValue({ success: true, data: { summary: { total: 2 }, by_severity: {}, by_status: {}, daily: [] } })
  api.incidents.options.mockResolvedValue({ success: true, data: [] })
  api.admin.getTeams.mockResolvedValue({ success: true, data: [{ id: 1, name: 'Takım A' }] })
})

afterEach(() => { localStorage.removeItem('site-monitor-lang') })

async function ready() {
  const view = render(<IncidentHistoryPage />)
  await screen.findByText('Ödeme servisinde gecikme')
  return view
}

describe('özet liste satırları', () => {
  it('liste fields=summary ister', async () => {
    await ready()
    expect(api.incidents.list.mock.calls[0][0]).toMatchObject({ fields: 'summary' })
  })

  it('özet satırdan açılan ayrıntı: markdown önce "yükleniyor", tam kayıt gelince içerik', async () => {
    const d = deferred()
    api.incidents.get.mockImplementation(() => d.p)
    await ready()
    fireEvent.click(screen.getByRole('row', { name: /Ödeme servisinde gecikme/ }))
    const sheet = await screen.findByRole('dialog')
    expect(sheet.querySelectorAll('[data-slot="ih-detail-md-loading"]').length).toBe(4)
    expect(sheet.textContent).not.toContain(TR['inc.notEntered'])
    await act(async () => { d.resolve({ success: true, data: FULL }) })
    await waitFor(() => expect(sheet.querySelectorAll('[data-slot="md-view"]').length).toBe(4))
    expect(sheet.textContent).toContain('Bağlantı havuzu tükendi.')
    // markdown'ın gelmesi "liste açıldıktan sonra değişti" sayılmaz
    expect(sheet.querySelector('[data-slot="ih-fresh-note"][data-kind="updated"]')).toBeNull()
  })

  it('tam kayıt okunamazsa "okunamadı + yeniden dene"; yeniden dene okur', async () => {
    api.incidents.get.mockResolvedValueOnce({ success: false, error: 'x' })
    await ready()
    fireEvent.click(screen.getByRole('row', { name: /Ödeme servisinde gecikme/ }))
    const sheet = await screen.findByRole('dialog')
    await waitFor(() => expect(sheet.querySelectorAll('[data-slot="ih-detail-md-failed"]').length).toBe(4))
    api.incidents.get.mockResolvedValue({ success: true, data: FULL })
    fireEvent.click(sheet.querySelector('[data-action="md-retry"]'))
    await waitFor(() => expect(sheet.querySelectorAll('[data-slot="md-view"]').length).toBe(4))
  })
})

describe('tembel yüklemeler', () => {
  it('açılışta yalnız kanal seçenekleri; kalan dördü ilk form açılışında bir kez', async () => {
    await ready()
    await flush()
    expect(optionTypes()).toEqual(['CHANNEL'])
    fireEvent.click(document.querySelector('[data-action="new"]'))
    await waitFor(() => expect(optionTypes()).toEqual(['CHANNEL', 'DOMAIN', 'ERROR_CODE', 'FUNCTION_CODE', 'CHANNEL_CODE']))
    const dlg = await screen.findByRole('dialog')
    fireEvent.click(within(dlg).getByRole('button', { name: /^(Vazgeç|İptal|Cancel)$/ }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    fireEvent.click(document.querySelector('[data-action="new"]'))
    await screen.findByRole('dialog')
    await flush()
    expect(optionTypes()).toHaveLength(5)
  })

  it('günlük trend yalnız döküm açılınca okunur (açılışta tek trends isteği)', async () => {
    await ready()
    await flush()
    expect(api.incidents.trends).toHaveBeenCalledTimes(1)
    expect(dailyCalls()).toHaveLength(0)
    fireEvent.click(document.querySelector('[data-slot="stats-toggle"]'))
    await waitFor(() => expect(dailyCalls()).toHaveLength(1))
  })
})

describe('hızlı kayıt ve durum', () => {
  it('"Hızlı kayıt" sade formu açar; tek takım seçili', async () => {
    await ready()
    fireEvent.click(document.querySelector('[data-action="quick"]'))
    const dlg = await screen.findByRole('dialog')
    expect(dlg.querySelector('[data-slot="incident-form"]')).toHaveAttribute('data-view', 'quick')
    expect(dlg.querySelector('[data-field="team_id"] select')).toHaveValue('1')
  })

  it('satırdaki durum rozeti tek tıkla kaydeder; ayrıntı açılmaz; liste yenilenir', async () => {
    api.incidents.update.mockResolvedValue({ success: true, data: { ...FULL, status: 'INVESTIGATING', version: 5 } })
    await ready()
    const row = screen.getByRole('row', { name: /Ödeme servisinde gecikme/ })
    const listCalls = api.incidents.list.mock.calls.length
    fireEvent.click(row.querySelector('[data-slot="ih-qs-trigger"]'))
    const pop = await waitFor(() => {
      const el = document.querySelector('[data-slot="ih-qs"]')
      expect(el).not.toBeNull()
      return el
    })
    fireEvent.click(pop.querySelector('[data-status="INVESTIGATING"]'))
    await waitFor(() => expect(api.incidents.update).toHaveBeenCalledWith(1, { status: 'INVESTIGATING', id: 1, version: 4 }))
    await waitFor(() => expect(api.incidents.list.mock.calls.length).toBeGreaterThan(listCalls))
    expect(document.querySelector('[data-slot="ih-detail"]')).toBeNull()
  })

  it('görüntüleyicide durum rozeti düğme değil', async () => {
    perms.manage = false
    await ready()
    expect(document.querySelector('[data-slot="ih-qs-trigger"]')).toBeNull()
  })
})

describe('çoğaltma', () => {
  it('özet satırdan çoğaltmada tam kayıt okunur; form kopya bandıyla, görseller çıkarılmış açılır', async () => {
    await ready()
    fireEvent.click(screen.getByRole('row', { name: /Ödeme servisinde gecikme/ }))
    const sheet = await screen.findByRole('dialog')
    await waitFor(() => expect(sheet.querySelectorAll('[data-slot="md-view"]').length).toBe(4))
    fireEvent.click(sheet.querySelector('[data-action="duplicate"]'))
    const form = await waitFor(() => {
      const el = document.querySelector('[data-slot="incident-form"]')
      expect(el).not.toBeNull()
      return el
    })
    expect(form).toHaveAttribute('data-mode', 'create')
    const note = form.querySelector('[data-slot="ih-copy-note"]')
    expect(note.textContent).toContain(TR['inc.copy.title'].replace('{0}', '1'))
    expect(note.querySelector('[data-slot="ih-copy-images"]').textContent).toBe(TR['inc.copy.images.one'])
    expect(within(form).getByRole('textbox', { name: /^Başlık/ })).toHaveValue('Ödeme servisinde gecikme')
  })

  it('ayrıntı tam kaydı aldıysa çoğaltma yeniden okumaz', async () => {
    await ready()
    fireEvent.click(screen.getByRole('row', { name: /Ödeme servisinde gecikme/ }))
    const sheet = await screen.findByRole('dialog')
    await waitFor(() => expect(sheet.querySelectorAll('[data-slot="md-view"]').length).toBe(4))
    // ayrıntı tam kaydı aldı → çoğaltma yeniden okumaz
    const gets = api.incidents.get.mock.calls.length
    fireEvent.click(sheet.querySelector('[data-action="duplicate"]'))
    await waitFor(() => expect(document.querySelector('[data-slot="ih-copy-note"]')).not.toBeNull())
    expect(api.incidents.get.mock.calls.length).toBe(gets)
  })

  async function duplicateFirst() {
    fireEvent.click(screen.getByRole('row', { name: /Ödeme servisinde gecikme/ }))
    const sheet = await screen.findByRole('dialog')
    await waitFor(() => expect(sheet.querySelectorAll('[data-slot="md-view"]').length).toBe(4))
    fireEvent.click(sheet.querySelector('[data-action="duplicate"]'))
    return waitFor(() => {
      const el = document.querySelector('[data-slot="incident-form"]')
      expect(el).not.toBeNull()
      return el
    })
  }

  it('takım listesi okunamadıysa (bilinmiyor) kaynak takım korunur, "takım düştü" denmez (inceleme L4)', async () => {
    api.admin.getTeams.mockResolvedValue({ success: false, error: 'x' })
    await ready()
    await flush()
    const form = await duplicateFirst()
    expect(form.querySelector('[data-field="team_id"] select')).toHaveValue('1')
    expect(form.querySelector('[data-slot="ih-copy-team"]')).toBeNull()
  })

  it('BİLİNEN takım listesi kaynak takımı içermiyorsa takım düşer ve söylenir', async () => {
    api.admin.getTeams.mockResolvedValue({ success: true, data: [{ id: 2, name: 'Takım B' }] })
    await ready()
    await flush()
    const form = await duplicateFirst()
    expect(form.querySelector('[data-field="team_id"] select')).toHaveValue('')
    expect(form.querySelector('[data-slot="ih-copy-team"]')).not.toBeNull()
  })

  it('tam kayıt okunamazsa form AÇILMAZ (eksik açıklamayla kopya yaratılmaz)', async () => {
    api.incidents.get.mockResolvedValue({ success: false, error: 'Sunucuya ulaşılamadı; biraz sonra deneyin.' })
    await ready()
    fireEvent.click(screen.getByRole('row', { name: /Ödeme servisinde gecikme/ }))
    const sheet = await screen.findByRole('dialog')
    await waitFor(() => expect(sheet.querySelectorAll('[data-slot="ih-detail-md-failed"]').length).toBe(4))
    const gets = api.incidents.get.mock.calls.length
    fireEvent.click(sheet.querySelector('[data-action="duplicate"]'))
    await waitFor(() => expect(api.incidents.get.mock.calls.length).toBe(gets + 1))
    await flush()
    expect(document.querySelector('[data-slot="incident-form"]')).toBeNull()
  })
})

describe('kısayollar', () => {
  const press = (key, target = document.body, extra = {}) => fireEvent.keyDown(target, { key, ...extra })

  it('bağlam sayfa açıkken kayıtlı; kapanınca kalkar', async () => {
    const view = await ready()
    expect(activeShortcutContexts().has(INCIDENT_HISTORY_CONTEXT)).toBe(true)
    view.unmount()
    expect(activeShortcutContexts().has(INCIDENT_HISTORY_CONTEXT)).toBe(false)
  })

  it('N yeni olay formunu açar; yazarken ya da "g" dizisinde açmaz', async () => {
    await ready()
    press('g'); press('n')
    await flush()
    expect(document.querySelector('[data-slot="incident-form"]')).toBeNull()
    const search = document.querySelector('[data-page-search]')
    expect(search).not.toBeNull()
    press('n', search)
    await flush()
    expect(document.querySelector('[data-slot="incident-form"]')).toBeNull()
    press('n')
    await waitFor(() => expect(document.querySelector('[data-slot="incident-form"]')).toHaveAttribute('data-mode', 'create'))
    expect(document.querySelector('[data-slot="incident-form"]')).toHaveAttribute('data-view', 'full')
  })

  it('E açık ayrıntıyı düzenler; ayrıntı yokken bir şey yapmaz', async () => {
    await ready()
    press('e')
    await flush()
    expect(document.querySelector('[data-slot="incident-form"]')).toBeNull()
    fireEvent.click(screen.getByRole('row', { name: /Ödeme servisinde gecikme/ }))
    await screen.findByRole('dialog')
    press('e')
    await waitFor(() => expect(document.querySelector('[data-slot="incident-form"]')).toHaveAttribute('data-mode', 'edit'))
  })

  it('yazma izni yoksa N / E çalışmaz', async () => {
    perms.manage = false
    await ready()
    press('n')
    await flush()
    expect(document.querySelector('[data-slot="incident-form"]')).toBeNull()
  })
})
