import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent, within, act } from './test-utils.jsx'
import { PAGE_STATE_PREFIXES } from '../hooks/useUrlQuerySync.js'

/**
 * Olay & Hata Geçmişi — shadcn yeniden tasarımı (2026-09-28). Pinlenen davranışlar:
 *  - süzgeç çipleri (çipin tamamı kaldırır, Temizle hepsini), `ih_*` URL eşlemesi (tab korunur),
 *  - titremesiz geçiş: iskelet yalnız ilk yüklemede; süzgeç değişince eski sonuç soluk yerinde, bayat yanıt ezmez,
 *  - hata (ilk yükleme: blok + Yeniden dene; sonrası: şerit + eski satırlar) ve iki boş durum,
 *  - özet kartı "SLA İhlali" tek başına süzer (eski memo bağımlılık hatası), KPI'lar (açık süzer, MTTR sunucudan),
 *  - ayrıntı Sheet'i (zaman çizelgesi, kodlar, `ih_id` / e-postanın `incident`'ı), yetkiye göre eylemler,
 *  - beş bölümlü form: satır içi doğrulama + ilk hatalı bölüm, meşgul bayrağı, satır içi sunucu hatası, vazgeç onayı,
 *    Ctrl+Enter.
 * Fikstürler GERÇEK tel biçiminde (snake_case, IncidentController.dto).
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
// Tarih seçici ağır (Calendar + Popover): değer sözleşmesini (ISO dize, onChange) koruyan ince taklit.
vi.mock('../components/ui/DateTimeField.jsx', () => ({
  default: ({ value, onChange, placeholder, invalid }) => (
    <input aria-label={placeholder || 'date'} aria-invalid={invalid || undefined} value={value || ''} onChange={(e) => onChange(e.target.value)} />
  ),
}))
// Markdown düzenleyici (@uiw/react-md-editor) jsdom'da ağır: düzenlenebilirken metin kutusu, salt okunurken işlenmiş kap.
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
  id: 1, title: 'Ödeme servisinde gecikme', occurred_at: '2026-09-20T09:00:00', detected_at: '2026-09-20T09:12:00',
  resolved_at: '2026-09-20T11:05:00', severity: 'CRITICAL', status: 'RESOLVED', category: 'APPLICATION',
  error_code: 'E-500', function_code: 'F-12', channel_code: '', service: 'www.example.com, api.example.com',
  channel: 'Mobil Şube, İnternet Şubesi, Çağrı Merkezi', team_id: 1, team_name: 'Takım A',
  rca_summary: 'Bağlantı havuzu tükendi.', description: '', resolution_steps: 'Havuz büyütüldü.', business_impact: '',
  affected_services: 'Ödeme', problem_types: 'Konfigürasyon', affected_app: '', affected_systems: '',
  affected_customers: 1200, affected_transactions: 340, sla_breached: true, error_budget_burn_pct: 12.5,
  duration_minutes: 125, runbook_url: null, tags: 'db, havuz', created_by: 'kisi.a', created_at: '2026-09-20T11:30:00',
  updated_by: null, updated_at: '2026-09-20T11:30:00', ...over,
})
const ROWS = [
  rec(),
  rec({ id: 2, title: 'DNS çözümleme hatası', severity: 'MEDIUM', status: 'OPEN', category: 'NETWORK', resolved_at: null,
    duration_minutes: null, sla_breached: false, team_id: 2, team_name: 'Takım B', channel: '', service: '', tags: '' }),
]
const page = (data, total = data.length) => ({ success: true, data, total, page: 0, size: 25 })
const deferred = () => { let resolve; const p = new Promise((r) => { resolve = r }); return { p, resolve } }
const flush = (ms = 0) => act(() => new Promise((r) => setTimeout(r, ms)))
const lastListArgs = () => api.incidents.list.mock.calls.at(-1)?.[0] ?? {}

beforeEach(() => {
  vi.clearAllMocks()
  window.history.replaceState({}, '', '/?tab=incident-history')
  perms.view = true; perms.manage = true; perms.del = true
  api.incidents.list.mockResolvedValue(page(ROWS))
  api.incidents.trends.mockResolvedValue({ success: true, data: { summary: { total: 2, open: 1, critical: 1, sla_breached: 1, mttr_minutes: 125, mttr_sample: 1 }, by_severity: { CRITICAL: 1, MEDIUM: 1 }, by_status: { OPEN: 1, RESOLVED: 1 }, daily: [] } })
  api.incidents.options.mockResolvedValue({ success: true, data: [] })
  api.admin.getTeams.mockResolvedValue({ success: true, data: [{ id: 1, name: 'Takım A' }, { id: 2, name: 'Takım B' }] })
})

async function ready() {
  render(<IncidentHistoryPage />)
  await screen.findByText('Ödeme servisinde gecikme')
}

describe('süzgeçler ve çipler', () => {
  it('önem seçilince çip çıkar; çipin tamamı süzgeci kaldırır; Temizle hepsini kaldırır', async () => {
    await ready()
    fireEvent.change(screen.getByRole('combobox', { name: /^(Önem|Severity)$/ }), { target: { value: 'CRITICAL' } })
    await waitFor(() => expect(lastListArgs()).toMatchObject({ severity: 'CRITICAL' }))
    const chip = screen.getByRole('button', { name: /(Önem: KRİTİK süzgecini kaldır|Remove filter: Severity: CRITICAL)/ })
    fireEvent.click(chip)
    await waitFor(() => expect(lastListArgs().severity).toBeUndefined())
    expect(screen.queryByRole('button', { name: /(Önem: KRİTİK süzgecini kaldır|Remove filter: Severity)/ })).toBeNull()

    fireEvent.change(screen.getByRole('combobox', { name: /^(Durum|Status)$/ }), { target: { value: 'OPEN' } })
    fireEvent.change(screen.getByRole('combobox', { name: /^(Kategori|Category)$/ }), { target: { value: 'NETWORK' } })
    await waitFor(() => expect(lastListArgs()).toMatchObject({ status: 'OPEN', category: 'NETWORK' }))
    fireEvent.click(within(screen.getByRole('group', { name: /Etkin süzgeçler|Active filters/ })).getByRole('button', { name: /^(Temizle|Clear all)$/ }))
    await waitFor(() => { expect(lastListArgs().status).toBeUndefined(); expect(lastListArgs().category).toBeUndefined() })
    expect(screen.queryByRole('group', { name: /Etkin süzgeçler|Active filters/ })).toBeNull()
  })

  it('arama yazarken gecikmeli uygulanır; Enter HEMEN uygular', async () => {
    await ready()
    const n = api.incidents.list.mock.calls.length
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: 'dns' } })
    await flush(40)
    expect(api.incidents.list.mock.calls.length).toBe(n)   // her tuşta istek yok
    fireEvent.keyDown(screen.getByRole('searchbox'), { key: 'Enter' })
    await waitFor(() => expect(lastListArgs()).toMatchObject({ q: 'dns' }), { timeout: 200 })
  })
})

describe('URL eşlemesi (ih_ öneki; tab uygulamanın)', () => {
  it('adresten başlar: ih_sev / ih_q / ih_team ilk isteğe gider, geçersiz değer yok sayılır, tab korunur', async () => {
    window.history.replaceState({}, '', '/?tab=incident-history&ih_sev=HIGH&ih_q=dns&ih_team=2&ih_st=BOGUS')
    render(<IncidentHistoryPage />)
    await waitFor(() => expect(api.incidents.list).toHaveBeenCalled())
    const first = api.incidents.list.mock.calls[0][0]
    expect(first).toMatchObject({ severity: 'HIGH', q: 'dns', team_id: '2' })
    expect(first.status).toBeUndefined()
    await flush(350)
    expect(new URLSearchParams(window.location.search).get('tab')).toBe('incident-history')
  })

  it('süzgeç adrese ih_ önekiyle yazılır (çıplak status/q yazılmaz); önek sekme değişiminde temizlenen listede', async () => {
    await ready()
    fireEvent.change(screen.getByRole('combobox', { name: /^(Durum|Status)$/ }), { target: { value: 'RESOLVED' } })
    await flush(400)
    const q = new URLSearchParams(window.location.search)
    expect(q.get('ih_st')).toBe('RESOLVED')
    expect(q.get('status')).toBeNull()
    expect(q.get('tab')).toBe('incident-history')
    expect(PAGE_STATE_PREFIXES.some((p) => 'ih_st'.startsWith(p) && 'ih_id'.startsWith(p))).toBe(true)
  })
})

describe('titremesiz geçiş (stale-while-revalidate)', () => {
  it('iskelet yalnız ilk yüklemede; süzgeç değişince eski sonuç SOLUK yerinde kalır; bayat yanıt yeniyi ezmez', async () => {
    const first = deferred()
    api.incidents.list.mockImplementationOnce(() => first.p)
    render(<IncidentHistoryPage />)
    expect(document.querySelector('[data-slot="ih-skeleton"]')).toBeTruthy()
    await act(async () => { first.resolve(page(ROWS)) })
    await screen.findByText('Ödeme servisinde gecikme')
    expect(document.querySelector('[data-slot="ih-skeleton"]')).toBeNull()

    const slow = deferred(), fast = deferred()
    api.incidents.list.mockImplementationOnce(() => slow.p).mockImplementationOnce(() => fast.p)
    fireEvent.change(screen.getByRole('combobox', { name: /^(Önem|Severity)$/ }), { target: { value: 'LOW' } })
    await waitFor(() => expect(lastListArgs()).toMatchObject({ severity: 'LOW' }))
    const box = document.querySelector('[data-slot="ih-results"]')
    expect(box).toHaveAttribute('data-stale', 'true')
    expect(screen.getByText('Ödeme servisinde gecikme')).toBeInTheDocument()      // eski sonuç yerinde
    expect(document.querySelector('[data-slot="ih-skeleton"]')).toBeNull()          // iskelet YOK
    await waitFor(() => expect(document.querySelector('[data-slot="ih-results"]').className).toMatch(/opacity-60/))

    fireEvent.change(screen.getByRole('combobox', { name: /^(Önem|Severity)$/ }), { target: { value: 'HIGH' } })
    await waitFor(() => expect(lastListArgs()).toMatchObject({ severity: 'HIGH' }))
    await act(async () => { fast.resolve(page([rec({ id: 30, title: 'Yeni yanıt', severity: 'HIGH' })])) })
    await screen.findByText('Yeni yanıt')
    await act(async () => { slow.resolve(page([rec({ id: 31, title: 'Bayat yanıt', severity: 'LOW' })])) })
    await flush()
    expect(screen.queryByText('Bayat yanıt')).toBeNull()
    expect(screen.getByText('Yeni yanıt')).toBeInTheDocument()
    expect(document.querySelector('[data-slot="ih-results"]')).not.toHaveAttribute('data-stale')
  })
})

describe('hata ve boş durumlar', () => {
  it('ilk yükleme hatası: hata bloğu + Yeniden dene; yeniden deneme başarılıysa liste çizilir', async () => {
    api.incidents.list.mockRejectedValueOnce(new Error('boom'))
    render(<IncidentHistoryPage />)
    const block = await waitFor(() => {
      const el = document.querySelector('[data-slot="empty"][data-tone="danger"]')
      expect(el).toBeTruthy()
      return el
    })
    fireEvent.click(within(block).getByRole('button', { name: /Yeniden dene|Try again/ }))
    await screen.findByText('Ödeme servisinde gecikme')
    expect(document.querySelector('[data-slot="empty"][data-tone="danger"]')).toBeNull()
  })

  it('başarıdan sonra hata: eski satırlar yerinde + hata şeridi (Yeniden dene)', async () => {
    await ready()
    api.incidents.list.mockResolvedValueOnce({ success: false, error: 'Sunucu meşgul' })
    fireEvent.change(screen.getByRole('combobox', { name: /^(Önem|Severity)$/ }), { target: { value: 'LOW' } })
    const banner = await screen.findByText('Sunucu meşgul')
    expect(banner.closest('[data-slot="alert"]')).toHaveAttribute('data-tone', 'danger')
    expect(screen.getByText('Ödeme servisinde gecikme')).toBeInTheDocument()
  })

  it('süzgeçsiz boş liste: "henüz kayıt yok" + (yetkiliye) Yeni olay', async () => {
    api.incidents.list.mockResolvedValue(page([]))
    render(<IncidentHistoryPage />)
    await screen.findByText(/Henüz olay kaydı yok|No incidents logged yet/)
    const block = document.querySelector('[data-slot="empty"]')
    expect(within(block).getByRole('button', { name: /Yeni Olay|New Incident/ })).toBeInTheDocument()
  })

  it('süzgeçli boş liste: "eşleşen yok" + Temizle süzgeçleri kaldırır', async () => {
    await ready()
    api.incidents.list.mockResolvedValue(page([]))
    fireEvent.change(screen.getByRole('combobox', { name: /^(Önem|Severity)$/ }), { target: { value: 'LOW' } })
    const title = await screen.findByText(/Bu süzgeçlerle eşleşen olay yok|No incidents match these filters/)
    fireEvent.click(within(title.closest('[data-slot="empty"]')).getByRole('button', { name: /Temizle|Clear all/ }))
    await waitFor(() => expect(lastListArgs().severity).toBeUndefined())
  })
})

describe('özet kartları ve KPI', () => {
  it('"SLA İhlali" kartı tek başına tıklanınca liste slaBreached=true ile süzülür', async () => {
    await ready()
    fireEvent.click(document.querySelector('[data-slot="stats-toggle"]'))
    const sla = await waitFor(() => {
      const el = document.querySelector('[data-slot="stat-item"][data-key="sla"]')
      expect(el).toBeTruthy()
      return el
    })
    fireEvent.click(sla)
    await waitFor(() => expect(lastListArgs()).toMatchObject({ slaBreached: true }))
    expect(document.querySelector('[data-slot="stat-item"][data-key="sla"]')).toHaveAttribute('aria-pressed', 'true')
  })

  it('KPI: "Açık" süzer (open=true, aria-pressed); MTTR sunucunun mttr_minutes değerinden biçimlenir', async () => {
    await ready()
    const mttr = await waitFor(() => {
      const el = document.querySelector('[data-slot="ih-kpi"][data-key="mttr"] [data-slot="ih-kpi-value"]')
      expect(el).toBeTruthy()
      return el
    })
    expect(mttr.textContent).toMatch(/2 (sa|h) 5 (dk|min)/)
    const open = document.querySelector('[data-slot="ih-kpi"][data-key="open"]')
    expect(open.tagName).toBe('BUTTON')
    fireEvent.click(open)
    await waitFor(() => expect(lastListArgs()).toMatchObject({ open: true }))
    expect(document.querySelector('[data-slot="ih-kpi"][data-key="open"]')).toHaveAttribute('aria-pressed', 'true')
    expect(document.querySelector('[data-slot="ih-kpi"][data-key="mttr"]').tagName).not.toBe('BUTTON')   // bilgi kutusu, süzgeç değil
  })
})

describe('ayrıntı (önce okuma)', () => {
  it('satır tıklaması Sheet açar: zaman çizelgesi, kodlar, işlenmiş markdown; adres ih_id taşır, kapatınca silinir', async () => {
    await ready()
    fireEvent.click(screen.getByRole('row', { name: /Ödeme servisinde gecikme/ }))
    const dlg = await screen.findByRole('dialog')
    expect(within(dlg).getByRole('heading', { name: 'Ödeme servisinde gecikme' })).toBeInTheDocument()
    const steps = [...dlg.querySelectorAll('[data-slot="ih-timeline-step"]')].map((s) => s.getAttribute('data-kind'))
    expect(steps).toEqual(['occurred', 'detected', 'resolved'])
    expect(dlg.textContent).toMatch(/12 (dk|min)/)            // tespit süresi (oluş → tespit)
    expect(within(dlg).getByText('E-500')).toBeInTheDocument()
    expect([...dlg.querySelectorAll('[data-slot="md-view"]')].map((e) => e.textContent)).toContain('Bağlantı havuzu tükendi.')
    await flush(350)
    expect(new URLSearchParams(window.location.search).get('ih_id')).toBe('1')
    fireEvent.click(within(dlg).getByRole('button', { name: /^(Kapat|Close)$/ }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    await flush(350)
    expect(new URLSearchParams(window.location.search).get('ih_id')).toBeNull()
  })

  it('açık olay: çizelgede "güncel durum" adımı, çözülme "henüz çözülmedi"', async () => {
    await ready()
    fireEvent.click(screen.getByRole('row', { name: /DNS çözümleme hatası/ }))
    const dlg = await screen.findByRole('dialog')
    const kinds = [...dlg.querySelectorAll('[data-slot="ih-timeline-step"]')].map((s) => [s.getAttribute('data-kind'), s.getAttribute('data-pending')])
    expect(kinds).toEqual([['occurred', null], ['detected', null], ['status', null], ['resolved', 'true']])
  })

  it('e-posta derin bağlantısı ?incident=7 → tekil kayıt çekilir, ayrıntı açılır, `incident` adresten silinir', async () => {
    window.history.replaceState({}, '', '/?tab=incident-history&incident=7')
    api.incidents.get.mockResolvedValue({ success: true, data: rec({ id: 7, title: 'Sayfada olmayan olay' }) })
    render(<IncidentHistoryPage />)
    const dlg = await screen.findByRole('dialog')
    expect(api.incidents.get).toHaveBeenCalledWith('7')
    expect(within(dlg).getByRole('heading', { name: 'Sayfada olmayan olay' })).toBeInTheDocument()
    await flush(350)
    const q = new URLSearchParams(window.location.search)
    expect(q.get('incident')).toBeNull()
    expect(q.get('ih_id')).toBe('7')
    expect(q.get('tab')).toBe('incident-history')
  })

  it('yetki: düzenle ve sil yalnız izinliye (silme ayrı izin)', async () => {
    perms.del = false
    await ready()
    fireEvent.click(screen.getByRole('row', { name: /Ödeme servisinde gecikme/ }))
    let dlg = await screen.findByRole('dialog')
    expect(within(dlg).getByRole('button', { name: /^(Düzenle|Edit)$/ })).toBeInTheDocument()
    expect(within(dlg).queryByRole('button', { name: /(Sil|Delete)$/ })).toBeNull()
    fireEvent.click(within(dlg).getByRole('button', { name: /^(Kapat|Close)$/ }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())

    perms.manage = false
    fireEvent.click(screen.getByRole('row', { name: /Ödeme servisinde gecikme/ }))
    dlg = await screen.findByRole('dialog')
    expect(within(dlg).queryByRole('button', { name: /^(Düzenle|Edit)$/ })).toBeNull()
  })
})

describe('yetki: görüntüleyici', () => {
  it('yazma izni yoksa Yeni olay, seçim kutuları ve satır düzenle düğmeleri çizilmez', async () => {
    perms.manage = false; perms.del = false
    await ready()
    expect(screen.queryByRole('button', { name: /^(Yeni Olay|New Incident)$/ })).toBeNull()
    expect(screen.queryAllByRole('checkbox')).toHaveLength(0)
    expect(screen.queryByRole('button', { name: /— (Düzenle|Edit)$/ })).toBeNull()
  })
})

describe('olay formu (beş bölüm)', () => {
  async function openCreate() {
    await ready()
    fireEvent.click(screen.getByRole('button', { name: /^(Yeni Olay|New Incident)$/ }))
    return screen.findByRole('dialog', { name: /Yeni Olay Kaydı|New Incident Record|New Incident/ })
  }
  const saveBtn = (dlg) => dlg.querySelector('[data-action="save"]')

  it('beş bölüm sekmesi; boş kaydetme API çağırmaz, satır içi hata + hatalı bölüm rozeti + ilk hatalı bölüme döner', async () => {
    const dlg = await openCreate()
    const tabs = within(dlg).getAllByRole('tab')
    expect(tabs.map((tb) => tb.getAttribute('data-section'))).toEqual(['summary', 'impact', 'codes', 'rca', 'notify'])
    fireEvent.mouseDown(tabs[1])   // Radix Tabs jsdom'da mousedown ile değişir
    await waitFor(() => expect(tabs[1]).toHaveAttribute('aria-selected', 'true'))
    fireEvent.click(saveBtn(dlg))
    await waitFor(() => expect(within(dlg).getAllByRole('tab')[0]).toHaveAttribute('aria-selected', 'true'))
    expect(within(dlg).getByText(/Başlık girin\.|Enter a title\./)).toBeInTheDocument()
    expect(within(dlg).getByText(/Sorumlu takımı seçin\.|Choose the team that owns it\./)).toBeInTheDocument()
    expect(within(dlg).getAllByRole('tab')[0]).toHaveAttribute('data-invalid', 'true')
    expect(within(dlg).getByRole('textbox', { name: /^(Başlık|Title)/ })).toHaveAttribute('aria-invalid', 'true')
    expect(api.incidents.create).not.toHaveBeenCalled()
  })

  it('geçerli form: kaydederken düğme meşgul (disabled + aria-busy), başarıda pencere kapanır; gövde snake_case', async () => {
    const dlg = await openCreate()
    fireEvent.change(within(dlg).getByRole('textbox', { name: /^(Başlık|Title)/ }), { target: { value: 'Yeni kesinti' } })
    fireEvent.change(within(dlg).getByRole('textbox', { name: /^(Oluş Zamanı|Occurred)/ }), { target: { value: '2026-09-27T08:00:00' } })
    fireEvent.change(within(dlg).getByRole('combobox', { name: /^(Takım|Team)/ }), { target: { value: '1' } })
    const d = deferred()
    api.incidents.create.mockImplementationOnce(() => d.p)
    fireEvent.click(saveBtn(dlg))
    await waitFor(() => expect(saveBtn(dlg)).toBeDisabled())
    expect(saveBtn(dlg)).toHaveAttribute('aria-busy', 'true')
    expect(api.incidents.create).toHaveBeenCalledWith(expect.objectContaining({
      title: 'Yeni kesinti', occurred_at: '2026-09-27T08:00:00', team_id: '1', team_name: 'Takım A', severity: 'HIGH', status: 'OPEN',
    }))
    expect(api.incidents.create.mock.calls[0][0]).not.toHaveProperty('duration_minutes')   // boş sayı gönderilmez
    await act(async () => { d.resolve({ success: true, data: rec({ id: 9, title: 'Yeni kesinti' }) }) })
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
  })

  it('sunucu hatası pencerede satır içi kalır; meşgul bayrağı söner, form kaybolmaz', async () => {
    const dlg = await openCreate()
    fireEvent.change(within(dlg).getByRole('textbox', { name: /^(Başlık|Title)/ }), { target: { value: 'X' } })
    fireEvent.change(within(dlg).getByRole('textbox', { name: /^(Oluş Zamanı|Occurred)/ }), { target: { value: '2026-09-27T08:00:00' } })
    fireEvent.change(within(dlg).getByRole('combobox', { name: /^(Takım|Team)/ }), { target: { value: '1' } })
    api.incidents.create.mockRejectedValueOnce(new Error('network'))
    fireEvent.click(saveBtn(dlg))
    await waitFor(() => expect(saveBtn(dlg)).not.toBeDisabled())
    expect(within(dlg).getByRole('alert').textContent).toMatch(/İşlem başarısız|Operation failed/)
    expect(within(dlg).getByRole('textbox', { name: /^(Başlık|Title)/ })).toHaveValue('X')
  })

  it('kaydedilmemiş değişiklikte Vazgeç onay sorar: "Düzenlemeye dön" korur, "Değişiklikleri at" kapatır', async () => {
    const dlg = await openCreate()
    fireEvent.change(within(dlg).getByRole('textbox', { name: /^(Başlık|Title)/ }), { target: { value: 'yarım' } })
    fireEvent.click(within(dlg).getByRole('button', { name: /^(Vazgeç|Cancel)$/ }))
    fireEvent.click(await screen.findByRole('button', { name: /Düzenlemeye dön|Keep editing/ }))
    await waitFor(() => expect(screen.queryByRole('button', { name: /Düzenlemeye dön|Keep editing/ })).toBeNull())
    expect(within(dlg).getByRole('textbox', { name: /^(Başlık|Title)/ })).toHaveValue('yarım')
    fireEvent.click(within(dlg).getByRole('button', { name: /^(Vazgeç|Cancel)$/ }))
    fireEvent.click(await screen.findByRole('button', { name: /Değişiklikleri at|Discard changes/ }))
    await waitFor(() => expect(screen.queryByText(/Yeni Olay Kaydı|New Incident Record/)).toBeNull())
  })

  it('değişiklik yoksa Vazgeç SORMADAN kapatır', async () => {
    const dlg = await openCreate()
    fireEvent.click(within(dlg).getByRole('button', { name: /^(Vazgeç|Cancel)$/ }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(screen.queryByRole('button', { name: /Değişiklikleri at|Discard changes/ })).toBeNull()
  })

  it('Ctrl+Enter kaydetmeyi tetikler (doğrulama çalışır)', async () => {
    const dlg = await openCreate()
    fireEvent.keyDown(within(dlg).getByRole('textbox', { name: /^(Başlık|Title)/ }), { key: 'Enter', ctrlKey: true })
    expect(await within(dlg).findByText(/Başlık girin\.|Enter a title\./)).toBeInTheDocument()
  })

  it('düzenle: ayrıntıdaki Düzenle formu mevcut değerlerle açar; açılışta kirli sayılmaz (türetilmiş süre); kaydedince update(id)', async () => {
    await ready()
    fireEvent.click(screen.getByRole('row', { name: /Ödeme servisinde gecikme/ }))
    const sheet = await screen.findByRole('dialog')
    fireEvent.click(within(sheet).getByRole('button', { name: /^(Düzenle|Edit)$/ }))
    const dlg = await screen.findByRole('dialog', { name: /Olayı Düzenle|Edit Incident/ })
    expect(within(dlg).getByRole('textbox', { name: /^(Başlık|Title)/ })).toHaveValue('Ödeme servisinde gecikme')
    expect(dlg.querySelector('[data-slot="ih-form-dirty"]')).toBeNull()
    api.incidents.update.mockResolvedValueOnce({ success: true, data: rec({ title: 'Başlık güncellendi' }) })
    api.incidents.list.mockResolvedValue(page([rec({ title: 'Başlık güncellendi' }), ROWS[1]]))   // sunucu kaydedileni döner
    fireEvent.change(within(dlg).getByRole('textbox', { name: /^(Başlık|Title)/ }), { target: { value: 'Başlık güncellendi' } })
    expect(dlg.querySelector('[data-slot="ih-form-dirty"]')).toBeTruthy()
    fireEvent.click(saveBtn(dlg))
    await waitFor(() => expect(api.incidents.update).toHaveBeenCalledWith(1, expect.objectContaining({ title: 'Başlık güncellendi' })))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: /Olayı Düzenle|Edit Incident/ })).toBeNull())
    expect(within(screen.getByRole('dialog')).getByRole('heading', { name: 'Başlık güncellendi' })).toBeInTheDocument()
  })
})
