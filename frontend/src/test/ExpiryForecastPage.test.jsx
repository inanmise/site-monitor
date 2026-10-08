import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, fireEvent, within, act } from './test-utils.jsx'
import { pressMenuTrigger } from './helpers/dropdownMenu.js'
import ExpiryForecastPage from '../pages/ExpiryForecastPage.jsx'
import { horizonBuckets, sortRows, matchesTile } from '../pages/forecast/forecastUi.jsx'

/**
 * Sertifika Takvimi (2026-09-27 shadcn + mobil web yeniden tasarımı): başlık + dışa aktar menüsü, KPI kutucukları
 * süzgeç (f_urg), süzgeç çubuğu (f_team… f_q, f_plan), vade ufku (dönem süzgeci), takvim (gün → Sheet, f_day),
 * liste (tablo / telefonda kart, sıralama, sayfalama), plan akışı (RenewalPlanModal), içgörüler, boş/hata durumları.
 * Miras (2026-09-12): #1 dolmuş + erişilemeyen görünür, #4 hata bandı, #7 takım tablosu, #8 kapsama, #10 zamanında.
 */
const { apiMock, icsMock } = vi.hoisted(() => {
  const target = { getForecast: vi.fn(), forecastPlan: vi.fn(), forecastUnplan: vi.fn(), refreshCertificateHealth: vi.fn() }
  return {
    apiMock: new Proxy(target, { get(t, prop) { if (prop in t || typeof prop === 'symbol') return t[prop]; t[prop] = vi.fn(() => Promise.resolve({ success: true, data: [] })); return t[prop] } }),
    icsMock: { buildIcs: vi.fn(() => 'ICS'), downloadIcs: vi.fn() },
  }
})
vi.mock('../api/client', async () => {
  const real = await vi.importActual('../api/client')
  return { api: apiMock, formatDate: (s) => s ?? '', formatDateSec: (s) => s ?? '', formatDateOnly: (s) => s ?? '', localDayKey: real.localDayKey }
})
vi.mock('../utils/ics.js', () => icsMock)
import { api } from '../api/client'

// Plan modalının tarih seçicisi proje geneli ui/DateTimeField (Calendar + Popover; jsdom'da ağır). Bu dosya plan AKIŞINI
// sınar, seçicinin kendisini değil → değer sözleşmesini (yyyy-MM-dd, onChange) koruyan ince taklit.
vi.mock('../components/ui/DateTimeField.jsx', () => ({
  default: ({ value, onChange }) => <input data-testid="dtf-stub" value={value || ''} onChange={(e) => onChange(e.target.value)} />,
}))

// Yerel gün (toISOString UTC günü verir: 00:00–03:00 İstanbul'da fikstür bir gün geri kayıp "80 gün" 79 oluyordu)
function inDays(n) { const d = new Date(); d.setDate(d.getDate() + n); return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}` }
const cert = (domain, days, extra = {}) => ({
  domain, days_remaining: days, status: days == null ? 'error' : 'valid', not_after: days == null ? null : `${inDays(days)}T12:00:00`,
  renew_by: days == null ? null : inDays(days - 14), lead_days: 14, tier: 1, team_id: 1, team_name: 'Takım A', issuer_cn: 'CA One',
  fingerprint: 'F' + domain, renewal_plan_state: 'none', checked_at: '2026-09-12T10:00:00', ...extra,
})
const DATA = {
  certs: [
    cert('crit.example.com', 3), cert('high.example.com', 10), cert('warn.example.com', 25, { team_id: 2, team_name: 'Takım B', tier: 2 }),
    cert('ok.example.com', 80), cert('gone.example.com', -4), cert('err.example.com', null, { error: 'connect timeout' }),
    cert('shared.example.com', 25, { fingerprint: 'Fwarn.example.com', team_id: 2, team_name: 'Takım B' }),
  ],
  thresholds: { warning: 30, high: 15, critical: 7 },
  lead_days: { default: 14, t1: 30, t2: 14, t3: 14, t4: 14 },
  data_as_of: '2026-09-12T10:00:00', environment: 'test',
  renewals: { window_days: 90, on_time: 3, late: 1, months: [{ month: '2026-08', on_time: 2, late: 1 }, { month: '2026-09', on_time: 1, late: 0 }],
    events: [{ domain: 'old.example.com', renewed_at: '2026-09-01T00:00:00', prev_not_after: '2026-10-01T00:00:00', renew_by: '2026-09-17', on_time: true }] },
  domains: [{ id: 9, domain: 'example.org', days_remaining: 12, expiry_date: `${inDays(12)}T00:00:00`, team_name: 'Takım A', registrar: 'R1' }],
}
const rowDomains = () => [...document.querySelectorAll('[data-slot="fc-row"]')].map((tr) => tr.getAttribute('data-domain'))
const listView = () => fireEvent.click(screen.getByRole('button', { name: /^List$|^Liste$/ }))
const tileByLabel = (re) => [...document.querySelectorAll('[data-slot="stat-item"]')].find((b) => re.test(b.textContent))
async function renderLoaded(props = {}) {
  const utils = render(<ExpiryForecastPage {...props} />)
  await screen.findByText(/TEST/)
  return utils
}

beforeEach(() => {
  vi.clearAllMocks()
  window.history.replaceState(null, '', '/')
  api.getForecast.mockResolvedValue({ success: true, data: DATA })
  api.refreshCertificateHealth.mockResolvedValue({ success: true })
})

describe('ExpiryForecastPage — başlık, kutucuklar, durumlar', () => {
  it('tek uç; başlık marka + ortam rozeti + veri damgası; kutucuklar sayar (gecikmiş = dolmuş + erişilemeyen); eşik notu; varsayılan görünüm takvim', async () => {
    await renderLoaded()
    expect(api.getForecast).toHaveBeenCalledTimes(1)
    const header = document.querySelector('[data-slot="fc-header"]')
    expect(header.textContent).toMatch(/Certificate Calendar|Sertifika Takvimi/)
    expect(within(header).getByText('TEST')).toBeInTheDocument()
    expect(within(header).getByText(/2026-09-12T10:00:00/)).toBeInTheDocument()
    const tiles = document.querySelectorAll('[data-slot="stat-item"]')
    expect(tiles).toHaveLength(6)
    expect(within(tileByLabel(/Overdue|Gecikmiş/)).getByText('2')).toBeInTheDocument()
    expect(tileByLabel(/Overdue|Gecikmiş/).textContent).toMatch(/1 expired · 1 unreachable|1 süresi dolmuş · 1 erişilemiyor/)
    expect(within(tileByLabel(/≤ 7 (days|gün)/)).getByText('1')).toBeInTheDocument()
    expect(screen.getByText(/Thresholds: critical ≤7 · high ≤15 · warning ≤30|Eşikler: kritik ≤7/)).toBeInTheDocument()
    expect(document.querySelector('[data-slot="month-calendar"]')).not.toBeNull()
    expect(document.querySelector('[data-slot="fc-list"]')).toBeNull()
  })

  it('#4 uç REDDEDERSE hata bandı (role=alert) + yeniden dene; sayfa çökmez', async () => {
    api.getForecast.mockRejectedValueOnce(new Error('boom'))
    render(<ExpiryForecastPage />)
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent(/boom/)
    fireEvent.click(within(alert).getByRole('button', { name: /Try again|Yeniden dene/ }))
    await waitFor(() => expect(api.getForecast).toHaveBeenCalledTimes(2))
    expect(await screen.findByText(/TEST/)).toBeInTheDocument()
  })

  it('#12 boş aralık: sıradaki bitiş ipucu ve "genişlet" düğmesi listeyi getirir', async () => {
    api.getForecast.mockResolvedValue({ success: true, data: { ...DATA, certs: [cert('far.example.com', 80)], renewals: { on_time: 0, late: 0, months: [], events: [] } } })
    await renderLoaded()
    listView()
    expect(await screen.findByText(/No certificates expire in the next 30 days|Önümüzdeki 30 günde/)).toBeInTheDocument()
    expect(screen.getByText(/next: far.example.com \(80 days\)|sıradaki: far.example.com \(80 gün\)/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /Widen to 90 days|90 güne genişlet/ }))
    await waitFor(() => expect(rowDomains()).toEqual(['far.example.com']))
  })

  it('dışa aktar menüsü: ICS öğesi ics yardımcılarını LİSTE satırlarıyla çağırır; yazdırma başlığı marka', async () => {
    await renderLoaded()
    pressMenuTrigger(screen.getByRole('button', { name: /^Export$|^Dışa aktar$/ }))
    fireEvent.click(await screen.findByRole('menuitem', { name: /\.ics/ }))
    await waitFor(() => expect(icsMock.downloadIcs).toHaveBeenCalledWith('renewal-plan.ics', 'ICS'))
    expect(icsMock.buildIcs).toHaveBeenCalled()
  })
})

describe('ExpiryForecastPage — süzgeçler ve URL', () => {
  it('kutucuk süzgeç: "≤ 7 gün" aria-pressed + liste yalnız kritik + URL f_urg; ikinci basış kaldırır', async () => {
    await renderLoaded()
    listView()
    await waitFor(() => expect(rowDomains().length).toBe(6))   // 30 gün + dolmuş + erişilemeyen (ok 80 gün dışarıda)
    const tile = tileByLabel(/≤ 7 (days|gün)/)
    fireEvent.click(tile)
    expect(tile).toHaveAttribute('aria-pressed', 'true')
    await waitFor(() => expect(rowDomains()).toEqual(['crit.example.com']))
    await waitFor(() => expect(window.location.search).toContain('f_urg=critical'))
    expect(document.querySelector('[data-slot="fc-chip"]')).not.toBeNull()
    fireEvent.click(tile)
    await waitFor(() => expect(rowDomains().length).toBe(6))
    await waitFor(() => expect(window.location.search).not.toContain('f_urg'))
  })

  it('#2 takım tablosu "Süz" listeyi daraltır ve URL f_team taşır; çip kaldırınca geri gelir; arama f_q yazar', async () => {
    await renderLoaded()
    listView()
    await waitFor(() => expect(rowDomains()).toContain('warn.example.com'))
    const filterBtns = screen.getAllByRole('button', { name: /^Filter$|^Süz$/ })
    fireEvent.click(filterBtns[1])   // Takım B satırı (ikinci)
    await waitFor(() => expect(rowDomains().sort()).toEqual(['shared.example.com', 'warn.example.com']))
    await waitFor(() => expect(window.location.search).toContain('f_team=2'))
    fireEvent.click(screen.getByRole('button', { name: /Remove filter: Team|Süzgeci kaldır: Takım/ }))
    await waitFor(() => expect(rowDomains().length).toBe(6))
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: 'high' } })
    await waitFor(() => expect(rowDomains()).toEqual(['high.example.com']))
    await waitFor(() => expect(window.location.search).toContain('f_q=high'))
  })

  it('derin bağlantı: ?f_view=list&f_q=warn liste görünümünü ve aramayı açar; f_plan=planned yalnız planlıları bırakır', async () => {
    window.history.replaceState(null, '', '/?tab=forecast&f_view=list&f_q=warn')
    api.getForecast.mockResolvedValue({ success: true, data: { ...DATA, certs: [...DATA.certs, cert('plan.example.com', 12, { renewal_plan_state: 'planned', renewal_planned_at: inDays(2), renewal_planned_note: 'CSR ready' })] } })
    await renderLoaded()
    await waitFor(() => expect(rowDomains()).toEqual(['warn.example.com']))
    expect(document.querySelector('[data-slot="month-calendar"]')).toBeNull()
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: '' } })
    fireEvent.click(screen.getByRole('button', { name: /^Planned$|^Planlı$/ }))
    await waitFor(() => expect(rowDomains()).toEqual(['plan.example.com']))
    expect(document.querySelector('[data-slot="fc-plan"][data-state="planned"]')).not.toBeNull()
    expect(screen.getByText('CSR ready')).toBeInTheDocument()
    await waitFor(() => expect(window.location.search).toContain('f_plan=planned'))
  })
})

describe('ExpiryForecastPage — vade ufku', () => {
  it('horizonBuckets: 90 gün → 13 haftalık kova, sınıf sayıları; 365 → 12 aylık kova; sortRows / matchesTile', () => {
    const today = inDays(0)
    const th = DATA.thresholds
    const weeks = horizonBuckets(DATA.certs, th, 90, today, 'en-GB', (k, d) => `Week of ${d}`)
    expect(weeks).toHaveLength(13)
    expect(weeks[0].from).toBe(today); expect(weeks[0].to).toBe(inDays(6))
    expect(weeks[0].critical).toBe(1); expect(weeks[1].high).toBe(1); expect(weeks[3].warning).toBe(2); expect(weeks[11].later).toBe(1)
    expect(weeks.reduce((n, b) => n + b.total, 0)).toBe(5)   // dolmuş + erişilemeyen kovaya girmez
    const months = horizonBuckets(DATA.certs, th, 365, today, 'en-GB')
    expect(months).toHaveLength(12); expect(months[0].from).toBe(today)
    expect(months.reduce((n, b) => n + b.total, 0)).toBe(5)
    const rows = [{ domain: 'b', days_remaining: 5 }, { domain: 'a', days_remaining: 9 }]
    expect(sortRows(rows, { key: 'domain', dir: 'asc' }).map((r) => r.domain)).toEqual(['a', 'b'])
    expect(sortRows(rows, { key: 'expiry', dir: 'desc' }).map((r) => r.domain)).toEqual(['a', 'b'])
    expect(matchesTile(DATA.certs[4], 'overdue', th, today)).toBe(true)
    expect(matchesTile(DATA.certs[0], 'overdue', th, today)).toBe(false)
  })

  it('"en yoğun dönem" düğmesi listeyi o haftaya daraltır (takvimden liste görünümüne geçer) + dönem çipi; çip kaldırınca tümü', async () => {
    await renderLoaded()
    const peaks = document.querySelectorAll('[data-slot="fc-horizon-peak"]')
    expect(peaks.length).toBeGreaterThan(0)
    expect(peaks[0].textContent).toMatch(/2/)   // en yoğun hafta: warn + shared (25. gün)
    fireEvent.click(peaks[0])
    await waitFor(() => expect(rowDomains().sort()).toEqual(['shared.example.com', 'warn.example.com']))
    const chip = [...document.querySelectorAll('[data-slot="fc-chip"]')].find((c) => /Period|Dönem/.test(c.textContent))
    expect(chip).toBeTruthy()
    fireEvent.click(chip)
    await waitFor(() => expect(rowDomains().length).toBe(6))
  })
})

describe('ExpiryForecastPage — takvim ve gün paneli', () => {
  it('olaylı gün düğmesi yan paneli açar (Sheet, role=dialog): o günün sertifikaları; Planla panel→pencere; kaydedince panel taze rozetle geri gelir', async () => {
    api.forecastPlan.mockResolvedValue({ success: true, data: { domain: 'crit.example.com', renewal_planned_at: '2026-09-20', renewal_planned_by: 'Admin', renewal_planned_note: 'x' } })
    await renderLoaded()
    const day = inDays(3)
    fireEvent.change(screen.getByRole('combobox', { name: /Go to month|Aya git/ }), { target: { value: day.slice(0, 7) } })
    const open = document.querySelector(`[data-slot="month-calendar-day"][data-day="${day}"] [data-slot="month-calendar-day-open"]`)
    expect(open).not.toBeNull()
    fireEvent.click(open)
    const sheet = await screen.findByRole('dialog')
    expect(sheet.textContent).toContain(day)
    expect(within(sheet).getByText('crit.example.com')).toBeInTheDocument()
    expect(within(sheet).queryByText('high.example.com')).toBeNull()
    await waitFor(() => expect(window.location.search).toContain(`f_day=${day}`))
    // Planla → panel kapanır, plan penceresi açılır
    fireEvent.click(within(sheet).getByRole('button', { name: /Renewal plan — crit\.example\.com|Yenileme planı — crit\.example\.com/ }))
    const dlg = await screen.findByRole('dialog')
    expect(dlg.textContent).toMatch(/Renewal plan|Yenileme planı/)
    expect(within(dlg).queryByText('crit.example.com')).toBeNull()
    fireEvent.change(within(dlg).getByLabelText(/Planned renewal date|Planlanan yenileme tarihi/), { target: { value: '2026-09-20' } })
    fireEvent.change(within(dlg).getByLabelText(/^Note$|^Not$/), { target: { value: 'x' } })
    fireEvent.click(within(dlg).getByRole('button', { name: /Save plan|Planı kaydet/ }))
    await waitFor(() => expect(api.forecastPlan).toHaveBeenCalledWith('crit.example.com', '2026-09-20', 'x'))
    // Panel geri geldi ve satır planlı
    const again = await screen.findByRole('dialog')
    expect(again.textContent).toContain(day)
    expect(within(again).getByText(/planned · 2026-09-20|planlı · 2026-09-20/)).toBeInTheDocument()
    // Düğmenin adı alan adını taşır (aria-label), metni artık "Planı düzenle"
    expect(within(again).getByRole('button', { name: /Renewal plan — crit\.example\.com|Yenileme planı — crit\.example\.com/ })).toHaveTextContent(/Edit plan|Planı düzenle/)
  })

  it('plan sonrası geri gelen gün paneli SÜZGEÇLERE uyar (2026-10-09): takım süzgecinin dışındaki aynı günlü sertifika eklenmez', async () => {
    api.getForecast.mockResolvedValue({ success: true, data: { ...DATA, certs: [...DATA.certs, cert('crit-b.example.com', 3, { team_id: 2, team_name: 'Takım B' })] } })
    api.forecastPlan.mockResolvedValue({ success: true, data: { domain: 'crit.example.com', renewal_planned_at: '2026-09-20', renewal_planned_by: 'Admin', renewal_planned_note: null } })
    window.history.replaceState(null, '', '/?f_team=1')
    await renderLoaded()
    const day = inDays(3)
    fireEvent.change(screen.getByRole('combobox', { name: /Go to month|Aya git/ }), { target: { value: day.slice(0, 7) } })
    fireEvent.click(document.querySelector(`[data-slot="month-calendar-day"][data-day="${day}"] [data-slot="month-calendar-day-open"]`))
    const sheet = await screen.findByRole('dialog')
    expect(within(sheet).getByText('crit.example.com')).toBeInTheDocument()
    expect(within(sheet).queryByText('crit-b.example.com')).toBeNull()
    fireEvent.click(within(sheet).getByRole('button', { name: /Renewal plan — crit\.example\.com|Yenileme planı — crit\.example\.com/ }))
    const dlg = await screen.findByRole('dialog')
    fireEvent.change(within(dlg).getByLabelText(/Planned renewal date|Planlanan yenileme tarihi/), { target: { value: '2026-09-20' } })
    fireEvent.click(within(dlg).getByRole('button', { name: /Save plan|Planı kaydet/ }))
    await waitFor(() => expect(api.forecastPlan).toHaveBeenCalled())
    const again = await screen.findByRole('dialog')
    await waitFor(() => expect(again.textContent).toContain(day))
    expect(within(again).getByText('crit.example.com')).toBeInTheDocument()
    expect(within(again).queryByText('crit-b.example.com')).toBeNull()
  })

  it('derin bağlantı ?f_day=… veri gelince paneli açar; kapatınca f_day silinir; takvim önceki/sonraki/bugün düğmeleri klavye erişilebilir gerçek düğmeler', async () => {
    const day = inDays(10)
    window.history.replaceState(null, '', `/?tab=forecast&f_day=${day}`)
    await renderLoaded()
    const sheet = await screen.findByRole('dialog')
    expect(within(sheet).getByText('high.example.com')).toBeInTheDocument()
    // İki kapat düğmesi: Sheet'in köşe X'i (sr-only) + altlıktaki "Kapat" — altlıktaki
    fireEvent.click(within(sheet).getAllByRole('button', { name: /^Close$|^Kapat$/ }).at(-1))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    await waitFor(() => expect(window.location.search).not.toContain('f_day'))
    for (const name of [/Previous month|Önceki ay/, /Next month|Sonraki ay/, /^Today$|^Bugün$/]) {
      expect(screen.getByRole('button', { name }).tagName).toBe('BUTTON')
    }
  })

  it('takım tablosu hücresi → o takım+kova listesi panelde; 12 kayıtlı panel SAYFALI (10 + sayfalama)', async () => {
    const many = Array.from({ length: 12 }, (_, i) => cert(`gun${i}.example.com`, 5, { team_id: 3, team_name: 'Takım C' }))
    api.getForecast.mockResolvedValue({ success: true, data: { ...DATA, certs: [...DATA.certs, ...many] } })
    await renderLoaded()
    const rowC = [...document.querySelectorAll('[data-slot="fc-team-table"] tbody tr')].find((tr) => tr.textContent.includes('Takım C'))
    const crit = [...rowC.querySelectorAll('[data-slot="fc-cell-btn"]')].find((b) => b.textContent.trim() === '12')
    expect(crit).toBeTruthy()
    fireEvent.click(crit)
    const sheet = await screen.findByRole('dialog')
    expect(sheet.textContent).toMatch(/Takım C/)
    expect(sheet.querySelectorAll('[data-slot="fc-day-row"]')).toHaveLength(10)
    expect(within(sheet).getByRole('navigation', { name: /Sayfalama|Pagination/ })).toBeInTheDocument()
    fireEvent.click(within(sheet).getByRole('button', { name: /^Next$|Sonraki sayfa|^Next page$|Sonraki$/ }))
    await waitFor(() => expect(sheet.querySelectorAll('[data-slot="fc-day-row"]')).toHaveLength(2))
    expect(rowC.querySelector('[data-slot="fc-zero"]')).not.toBeNull()
  })
})

describe('ExpiryForecastPage — liste', () => {
  it('#3 renew-by, #8 paylaşılan + veren, #10 zamanında oranı; satır: sağlık ucu (menü) ve plan düğmesi adı alan adını taşır; sıralama aria-sort', async () => {
    await renderLoaded()
    listView()
    await waitFor(() => expect(rowDomains()).toContain('crit.example.com'))
    const row = document.querySelector('[data-slot="fc-row"][data-domain="crit.example.com"]')
    expect(row.textContent).toMatch(new RegExp(inDays(3 - 14)))   // renew-by (lg sütunu DOM'da)
    const insights = within(document.querySelector('[data-slot="fc-insights"]'))
    expect(insights.getByText(/1 certificate → 2 domains|1 sertifika → 2 alan/)).toBeInTheDocument()
    expect(insights.getByText('CA One')).toBeInTheDocument()   // veren çipi (tablo sütununda da geçer → içgörü kartında ara)
    expect(insights.getByText(/3 on time · 1 late|3 zamanında · 1 geç/)).toBeInTheDocument()
    expect(document.querySelector('[data-slot="fc-ontime"]').textContent).toMatch(/75%/)
    pressMenuTrigger(within(row).getByRole('button', { name: /crit\.example\.com — (Row actions|Satır işlemleri)/ }))
    fireEvent.click(await screen.findByRole('menuitem', { name: /Check now|Şimdi kontrol et/ }))
    await waitFor(() => expect(api.refreshCertificateHealth).toHaveBeenCalledWith('crit.example.com'))
    expect(within(row).getByRole('button', { name: /Renewal plan — crit\.example\.com|Yenileme planı — crit\.example\.com/ })).toBeInTheDocument()
    // Sıralama: başlık düğmesi (araç çubuğundaki menü düğmesi de aynı adı alır → tablo içinde ara) alan adına göre artan → azalan
    const table = () => within(document.querySelector('[data-slot="fc-table"]'))
    fireEvent.click(table().getByRole('button', { name: /Sort by Domain|Sırala: Alan adı/ }))
    await waitFor(() => expect(rowDomains()[0]).toBe('crit.example.com'))
    expect(document.querySelector('th[aria-sort="ascending"]')).not.toBeNull()
    fireEvent.click(table().getByRole('button', { name: /Sort by Domain|Sırala: Alan adı/ }))
    await waitFor(() => expect(rowDomains()[0]).toBe('warn.example.com'))
    expect(document.querySelector('th[aria-sort="descending"]')).not.toBeNull()
  })

  it('30 satır → panel ön ayarı: 25 satır + sayfalama çubuğu; ikinci sayfada 5', async () => {
    const many = Array.from({ length: 24 }, (_, i) => cert(`m${String(i).padStart(2, '0')}.example.com`, 20))
    api.getForecast.mockResolvedValue({ success: true, data: { ...DATA, certs: [...DATA.certs, ...many] } })
    await renderLoaded()
    listView()
    await waitFor(() => expect(rowDomains()).toHaveLength(25))
    const bar = document.querySelector('[data-slot="fc-list"] [data-slot="pagination-bar"]')
    expect(bar).not.toBeNull()
    fireEvent.click(within(bar).getByRole('button', { name: /^Next$|Sonraki sayfa|^Next page$|Sonraki$/ }))
    await waitFor(() => expect(rowDomains()).toHaveLength(5))
  })
})

describe('ExpiryForecastPage — telefon (useIsMobile)', () => {
  const origWidth = window.innerWidth
  afterEach(() => { Object.defineProperty(window, 'innerWidth', { configurable: true, writable: true, value: origWidth }) })

  it('kart listesi (tablo yok), "Süzgeçler" düğmesi alttan Sheet açar; kart eylem düğmeleri alan adını taşır', async () => {
    Object.defineProperty(window, 'innerWidth', { configurable: true, writable: true, value: 390 })
    await renderLoaded()
    listView()
    await waitFor(() => expect(document.querySelectorAll('[data-slot="fc-card"]').length).toBe(6))
    expect(document.querySelector('[data-slot="fc-table"]')).toBeNull()
    const card = document.querySelector('[data-slot="fc-card"][data-domain="crit.example.com"]')
    expect(within(card).getByRole('button', { name: /Renewal plan — crit\.example\.com|Yenileme planı — crit\.example\.com/ })).toBeInTheDocument()
    fireEvent.click(document.querySelector('[data-slot="fc-filters-open"]'))
    const sheet = await screen.findByRole('dialog')
    expect(sheet.textContent).toMatch(/Filters|Süzgeçler/)
    expect(sheet.querySelector('[data-slot="fc-filter-sheet"]')).not.toBeNull()
    // Sayaç faset kapsamındaki kayıt sayısıdır (7: 80 günlük "ok" dâhil), 30 günlük liste satırı (6) değil
    fireEvent.click(within(sheet).getByRole('button', { name: /Show 7 results|7 sonucu göster/ }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
  })
})

describe('ExpiryForecastPage — dar kap (tablet + açık kenar çubuğu)', () => {
  it('görünüm alanı geniş olsa da liste kabı 640 px altındaysa kart görünümü (yatay kaydırmalı tablo değil)', async () => {
    const orig = HTMLElement.prototype.getBoundingClientRect
    HTMLElement.prototype.getBoundingClientRect = function () {
      return this.getAttribute('data-slot') === 'fc-list'
        ? { width: 500, height: 800, top: 0, left: 0, right: 500, bottom: 800, x: 0, y: 0, toJSON() {} }
        : orig.call(this)
    }
    try {
      await renderLoaded()
      listView()
      await waitFor(() => expect(document.querySelectorAll('[data-slot="fc-card"]').length).toBe(6))
      expect(document.querySelector('[data-slot="fc-table"]')).toBeNull()
      // Masaüstü süzgeç çubuğu kalır (Sheet düğmesi yalnız telefonda)
      expect(document.querySelector('[data-slot="fc-filters-open"]')).toBeNull()
    } finally { HTMLElement.prototype.getBoundingClientRect = orig }
  })
})

describe('ExpiryForecastPage — alan adı paneli', () => {
  it('katlanır bölüm açık başlar, alan adı tablosu satırı derin bağlantı için düğme; başlık kapatınca içerik DOM\'dan çıkar', async () => {
    await renderLoaded()
    expect(document.querySelector('[data-slot="fc-domain-row"]')).not.toBeNull()
    const toggles = [...document.querySelectorAll('[data-slot="stats-toggle"]')]
    const domainsToggle = toggles.find((b) => /Domain Expirations|Alan Adı Bitişleri/.test(b.textContent))
    expect(domainsToggle).toHaveAttribute('aria-expanded', 'true')
    await act(async () => { fireEvent.click(domainsToggle) })
    await waitFor(() => expect(document.querySelector('[data-slot="fc-domain-row"]')).toBeNull())
  })
})
