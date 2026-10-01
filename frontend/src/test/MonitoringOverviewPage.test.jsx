import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'
import MonitoringOverviewPage, { filterRows, successPct, TYPE_META, STATUS_META, TYPE_ORDER } from '../components/MonitoringOverviewPage.jsx'
import {
  attentionRows, teamHealth, matchQuickView, quickViewCounts, rowUptime, uptimeTone, isSlow, verdict, distribution,
  alertLevelCounts, staleLimitMs, overviewCsv,
} from '../components/monitoring/overviewModel.js'
import { parseSort, sortRows, toggleSort } from '../components/monitoring/overviewFilters.js'

/**
 * İzleme Panosu (2026-09-30; 2026-10-01 shadcn yeniden tasarımı): KPI şeridi + özet pencereleri, filo sağlığı ve
 * "dikkat gerektirenler", takım sağlığı, tür kartları, izleme listesi (hızlı görünümler, süzgeçler, başarı ölçeri, yanıt
 * süresi, CSV); tıklamalar izleme sayfasına / Alarm Geçmişi'ne `sm:navigate` ile gider.
 */
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: withApiFallback({ monitoring: { getOverview: vi.fn() } }),
}))
vi.mock('../utils/csvExport.js', async (importOriginal) => ({ ...(await importOriginal()), downloadCsv: vi.fn() }))
import { api } from '../api/client'
import { downloadCsv } from '../utils/csvExport.js'

const TYPE = (type, extra = {}) => ({ type, total: 0, active: 0, paused: 0, deleted: 0, down: 0, stale: 0, unknown: 0,
  checks_window: 0, failed_window: 0, success_rate_window: null, open_alerts: 0, open_critical: 0, resolved_window: 0, last_checked_at: null, ...extra })

const DATA = {
  success: true,
  data: {
    generated_at: '2026-09-30T14:00:00', window_hours: 24,
    totals: { total: 4, active: 3, paused: 1, deleted: 0, down: 1, stale: 1, unknown: 0, checks_window: 120, failed_window: 6, open_alerts: 1, open_critical: 1, resolved_window: 2, last_checked_at: '2026-09-30T13:59:00', success_rate_window: 95.0 },
    types: [
      TYPE('http', { total: 2, active: 2, down: 1, checks_window: 100, failed_window: 6, success_rate_window: 94.0, open_alerts: 1, open_critical: 1, last_checked_at: '2026-09-30T13:59:00', resolved_window: 2, avg_response_ms_window: 180 }),
      TYPE('ping', { total: 1, active: 1, stale: 1, checks_window: 20, success_rate_window: 100.0, last_checked_at: '2026-09-30T10:00:00' }),
      TYPE('port'), TYPE('dns'), TYPE('domain'), TYPE('keyword'), TYPE('page'), TYPE('pagespeed'),
      TYPE('scripted', { total: 1, paused: 1 }),
    ],
    monitors: [
      { type: 'http', id: 1, name: 'API sağlık', target: 'https://api.example.com/health', team_id: 14, team_name: 'SY', active: true, deleted: false, status: 'down', last_checked_at: '2026-09-30T13:59:00', last_ok: false, response_ms: 800, avg_response_ms_window: 200, last_error: 'HTTP 503', interval_seconds: 300, open_alerts: 1, open_alert_level: 'CRITICAL', open_since: '2026-09-30T12:00:00', open_acknowledged: false, checks_window: 60, failed_window: 6, success_rate_window: 90.0 },
      { type: 'http', id: 2, name: 'Portal', target: 'https://portal.example.com', team_id: 14, team_name: 'SY', active: true, deleted: false, status: 'up', last_checked_at: '2026-09-30T13:58:00', last_ok: true, response_ms: 120, avg_response_ms_window: 110, interval_seconds: 300, open_alerts: 0, checks_window: 40, failed_window: 0, success_rate_window: 100.0 },
      { type: 'ping', id: 3, name: 'GW', target: '10.0.0.1', team_id: 7, team_name: 'Ağ', active: true, deleted: false, status: 'stale', last_checked_at: '2026-09-30T10:00:00', last_ok: true, response_ms: 3, interval_seconds: 60, open_alerts: 0, checks_window: 20, failed_window: 0 },
      { type: 'scripted', id: 4, name: 'Login akışı', target: 'Login akışı', team_id: 14, team_name: 'SY', active: false, deleted: false, status: 'paused', last_checked_at: null, open_alerts: 0, checks_window: 0, failed_window: 0 },
    ],
  },
}

const rowsOf = (c) => [...c.querySelectorAll('[data-slot="mo-row"]')]
const kpiButton = (c, key) => c.querySelector(`[data-slot="stat-item"][data-key="${key}"]`)
const dialog = (kind) => document.querySelector(`[data-slot="mo-kpi-dialog"][data-kind="${kind}"]`)
const filterInDialog = () => fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: /listede süz|filter the list/i }))

describe('İzleme Panosu', () => {
  beforeEach(() => { vi.clearAllMocks(); window.history.replaceState({}, '', '/?tab=monitoring'); try { localStorage.clear() } catch { /* yok */ } })

  it('KPI şeridi ve tür kartları sunucu özetini gösterir; sorunlu tür kartı kötü tonda', async () => {
    api.monitoring.getOverview.mockResolvedValue(DATA)
    const { container } = render(<MonitoringOverviewPage />)
    await waitFor(() => expect(api.monitoring.getOverview).toHaveBeenCalledWith(24))
    const kpi = (key) => container.querySelector(`[data-slot="stat-item"][data-key="${key}"] [data-slot="stat-value"]`).textContent
    expect(kpi('total')).toBe('4')
    expect(kpi('down')).toBe('1')
    expect(kpi('stale')).toBe('1')
    expect(kpi('paused')).toBe('1')
    expect(kpi('alerts')).toBe('1')
    expect(kpi('resolved')).toBe('2')
    const cards = container.querySelectorAll('[data-slot="mo-type-card"]')
    expect(cards).toHaveLength(9)
    expect(container.querySelector('[data-slot="mo-type-card"][data-type="http"]')).toHaveAttribute('data-tone', 'bad')
    expect(container.querySelector('[data-slot="mo-type-card"][data-type="ping"]')).toHaveAttribute('data-tone', 'warn')
    expect(container.querySelector('[data-slot="mo-type-card"][data-type="port"]')).toHaveAttribute('data-tone', 'ok')
    // Tür kartı: ağırlıklı ortalama yanıt + başarı oranı (EN yüzde sırası)
    const http = container.querySelector('[data-slot="mo-type-card"][data-type="http"]')
    expect(http.querySelector('[data-slot="mo-type-avg"]').textContent).toBe('180 ms')
    expect(http.querySelector('[data-slot="mo-type-success"]').textContent).toBe('94%')
    // Liste: sorunlu en üstte
    expect(rowsOf(container).map((r) => r.getAttribute('data-status'))).toEqual(['down', 'stale', 'up', 'paused'])
  })

  it('tür kartına tıklayınca liste türe süzülür; "Sorunlu" KPI özet penceresi açar, "Listede süz" durum süzgecini uygular; temizle hepsini kaldırır', async () => {
    api.monitoring.getOverview.mockResolvedValue(DATA)
    const { container } = render(<MonitoringOverviewPage />)
    await waitFor(() => expect(rowsOf(container).length).toBe(4))
    const httpCard = container.querySelector('[data-slot="mo-type-card"][data-type="http"]')
    fireEvent.click(within(httpCard).getAllByRole('button')[0])
    await waitFor(() => expect(rowsOf(container).length).toBe(2))
    expect(rowsOf(container).every((r) => r.getAttribute('data-type') === 'http')).toBe(true)
    fireEvent.click(kpiButton(container, 'down'))
    await waitFor(() => expect(dialog('down')).not.toBeNull())
    filterInDialog()
    await waitFor(() => expect(rowsOf(container).length).toBe(1))
    expect(container.querySelector('[data-slot="mo-status-filter"]').value).toBe('down')
    expect(screen.queryByRole('dialog')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: /süzgeçleri temizle|clear filters/i }))
    await waitFor(() => expect(rowsOf(container).length).toBe(4))
  })

  it('KPI penceresi / tür kartı tıklaması listeyi süzer VE liste başlığına kaydırır; envanteri pasif satır rozet taşır', async () => {
    const scrolled = []
    const orig = HTMLElement.prototype.scrollIntoView   // setup.js polyfill'i (HTMLElement) Element'i gölgeler
    HTMLElement.prototype.scrollIntoView = function (opts) { scrolled.push([this.getAttribute('data-slot'), opts]) }
    try {
      const withInv = { ...DATA, data: { ...DATA.data, monitors: DATA.data.monitors.map((m, i) => (i === 0 ? { ...m, inventory_inactive: true, status: 'paused' } : m)) } }
      api.monitoring.getOverview.mockResolvedValue(withInv)
      const { container } = render(<MonitoringOverviewPage />)
      await waitFor(() => expect(rowsOf(container).length).toBeGreaterThan(0))
      fireEvent.click(kpiButton(container, 'stale'))
      await waitFor(() => expect(dialog('stale')).not.toBeNull())
      filterInDialog()
      await waitFor(() => expect(scrolled.at(-1)?.[0]).toBe('mo-list-anchor'))
      expect(scrolled.at(-1)[1]).toMatchObject({ block: 'start' })
      const before = scrolled.length
      const card = container.querySelector('[data-slot="mo-type-card"][data-type="http"]')
      fireEvent.click(within(card).getAllByRole('button')[0])
      expect(scrolled.length).toBeGreaterThan(before)
      fireEvent.click(screen.getAllByRole('button', { name: /süzgeçleri temizle|clear filters/i })[0])   // araç çubuğu + boş durum bloğu
      await waitFor(() => expect(container.querySelector('[data-slot="mo-row"] [data-slot="mo-inv-inactive"]')).not.toBeNull())
    } finally {
      HTMLElement.prototype.scrollIntoView = orig
    }
  })

  it('satır eylemleri: "aç" izleme sayfasına arama ile, "alarmlar" Alarm Geçmişi\'ne türe süzülmüş gider; pencere seçici 7 güne geçince yeniden yükler', async () => {
    api.monitoring.getOverview.mockResolvedValue(DATA)
    const events = []
    const onNav = (e) => events.push(e.detail)
    window.addEventListener('sm:navigate', onNav)
    const { container } = render(<MonitoringOverviewPage />)
    await waitFor(() => expect(rowsOf(container).length).toBe(4))
    fireEvent.click(screen.getByRole('button', { name: /API sağlık — (izleme sayfasında aç|open on its monitor page)/i }))
    fireEvent.click(screen.getByRole('button', { name: /API sağlık — (açık alarmlarına git|go to its open alerts)/i }))
    expect(events).toEqual([
      { tab: 'http', params: { q: 'https://api.example.com/health' } },
      { tab: 'alerthistory', params: { view: 'open', src: 'http', q: 'https://api.example.com/health' } },
    ])
    window.removeEventListener('sm:navigate', onNav)
    fireEvent.click(screen.getByRole('radio', { name: /son 7 gün|last 7 days/i }))
    await waitFor(() => expect(api.monitoring.getOverview).toHaveBeenCalledWith(168))
  })

  it('filterRows: tür/durum/takım/arama süzer ve sorunlu → gecikmiş → bilinmiyor → sağlıklı → duraklatılmış → silinmiş sıralar; successPct 0–100 kırpar', () => {
    const rows = DATA.data.monitors
    expect(filterRows(rows, { q: 'portal' }).map((r) => r.id)).toEqual([2])
    expect(filterRows(rows, { team: '7' }).map((r) => r.id)).toEqual([3])
    expect(filterRows(rows, { status: 'paused' }).map((r) => r.id)).toEqual([4])
    expect(filterRows(rows).map((r) => r.status)).toEqual(['down', 'stale', 'up', 'paused'])
    expect(successPct({ success_rate_window: 101 })).toBe(100)
    expect(successPct({ success_rate_window: null })).toBeNull()
    // Yeniden dışa aktarılan tanımlar (diğer dosyalar/testler sayfadan içe aktarıyor)
    expect(TYPE_ORDER).toHaveLength(9)
    expect(TYPE_META.http.tab).toBe('http')
    expect(STATUS_META.down.rank).toBe(0)
  })

  it('yükleme hatası: hata bloğu + Yenile; veri gelince liste; Yenile sunucu belleğini atlar (fresh)', async () => {
    api.monitoring.getOverview.mockResolvedValueOnce({ success: false, error: 'boom' }).mockResolvedValue(DATA)
    const { container } = render(<MonitoringOverviewPage />)
    expect(await screen.findByText('boom')).toBeInTheDocument()
    fireEvent.click(screen.getAllByRole('button', { name: /yenile|refresh/i })[0])
    await waitFor(() => expect(rowsOf(container).length).toBe(4))
    expect(api.monitoring.getOverview).toHaveBeenLastCalledWith(24, true)
    expect(container.querySelector('[data-slot="mo-live"]').textContent).toMatch(/live/i)
  })

  it('veri varken yenileme başarısızsa eski veri kalır, uyarı şeridi + "Tekrar dene"', async () => {
    api.monitoring.getOverview.mockResolvedValueOnce(DATA).mockResolvedValueOnce({ success: false, error: 'kopuk' }).mockResolvedValue(DATA)
    const { container } = render(<MonitoringOverviewPage />)
    await waitFor(() => expect(rowsOf(container).length).toBe(4))
    fireEvent.click(screen.getByRole('button', { name: /^refresh$/i }))
    await waitFor(() => expect(container.querySelector('[data-slot="mo-stale-data"]')).not.toBeNull())
    expect(container.querySelector('[data-slot="mo-stale-data"]').textContent).toContain('kopuk')
    expect(rowsOf(container).length).toBe(4)
    expect(container.querySelector('[data-slot="mo-live"]')).toHaveAttribute('data-state', 'error')
    fireEvent.click(screen.getByRole('button', { name: /try again/i }))
    await waitFor(() => expect(container.querySelector('[data-slot="mo-stale-data"]')).toBeNull())
  })
})

describe('İzleme Panosu — sütun süzgeçleri ve sıralama (2026-10-01)', () => {
  beforeEach(() => { vi.clearAllMocks(); window.history.replaceState({}, '', '/?tab=monitoring'); try { localStorage.clear() } catch { /* yok */ } })

  const openFilter = (container, column) => fireEvent.click(container.querySelector(`[data-slot="mo-col-filter"][data-column="${column}"]`))

  it('Durum sütunu süzgeci çoklu seçim: sayılar faset; iki durum seçilince iki satır, çip ve URL; çip × kaldırır', async () => {
    api.monitoring.getOverview.mockResolvedValue(DATA)
    const { container } = render(<MonitoringOverviewPage />)
    await waitFor(() => expect(rowsOf(container).length).toBe(4))
    openFilter(container, 'status')
    const list = await screen.findByRole('group', { name: /^durum$|^status$/i })
    const opt = (v) => list.querySelector(`[data-option="${v}"]`)
    expect(opt('down').textContent).toMatch(/1$/)
    fireEvent.click(within(opt('down')).getByRole('checkbox'))
    fireEvent.click(within(opt('stale')).getByRole('checkbox'))
    await waitFor(() => expect(rowsOf(container).map((r) => r.getAttribute('data-status'))).toEqual(['down', 'stale']))
    await waitFor(() => expect(window.location.search).toContain('mo_status=down%2Cstale'))
    const chip = container.querySelector('[data-slot="mo-chip"][data-chip="status"]')
    expect(chip.textContent).toMatch(/(Sorunlu|Failing).*(gecikmiş|overdue)/i)
    // araç çubuğu seçicisi çoklu seçimde "2 seçili"
    expect(container.querySelector('[data-slot="mo-status-filter"]').value).toBe('__multi')
    fireEvent.click(within(chip).getByRole('button'))
    await waitFor(() => expect(rowsOf(container).length).toBe(4))
  })

  it('Takım / son kontrol / koşum / alarm süzgeçleri birlikte çalışır; tümünü temizle', async () => {
    api.monitoring.getOverview.mockResolvedValue(DATA)
    const { container } = render(<MonitoringOverviewPage />)
    await waitFor(() => expect(rowsOf(container).length).toBe(4))
    openFilter(container, 'team')
    const teams = await screen.findByRole('group', { name: /^takım$|^team$/i })
    fireEvent.click(within(teams.querySelector('[data-option="14"]')).getByRole('checkbox'))
    await waitFor(() => expect(rowsOf(container).length).toBe(3))
    openFilter(container, 'checks')
    const checks = await screen.findByRole('radiogroup', { name: /koşum|runs/i })
    fireEvent.click(within(checks.querySelector('[data-option="failed"]')).getByRole('radio'))
    await waitFor(() => expect(rowsOf(container).map((r) => r.textContent)).toEqual([expect.stringMatching(/API sağlık/)]))
    await waitFor(() => expect(window.location.search).toContain('mo_ck=failed'))
    openFilter(container, 'alert')
    const alerts = await screen.findByRole('radiogroup', { name: /alarm|alert/i })
    fireEvent.click(within(alerts.querySelector('[data-option="none"]')).getByRole('radio'))
    await waitFor(() => expect(rowsOf(container).length).toBe(0))
    fireEvent.click(screen.getAllByRole('button', { name: /tüm süzgeçleri temizle|clear all filters/i })[0])
    await waitFor(() => expect(rowsOf(container).length).toBe(4))
    expect(container.querySelector('[data-slot="mo-chips"]')).toBeNull()
  })

  it('Başlık sıralaması: ad A→Z, ikinci tık Z→A, üçüncü tık varsayılana döner; aria-sort ve URL; başarı sütunu en düşük önce', async () => {
    api.monitoring.getOverview.mockResolvedValue(DATA)
    const { container } = render(<MonitoringOverviewPage />)
    await waitFor(() => expect(rowsOf(container).length).toBe(4))
    const names = () => [...container.querySelectorAll('[data-slot="mo-row"] td:nth-child(2) .font-semibold')].map((e) => e.textContent)
    const btn = () => container.querySelector('[data-slot="mo-sort"][data-sort-key="name"]')
    fireEvent.click(btn())
    await waitFor(() => expect(names()).toEqual(['API sağlık', 'GW', 'Login akışı', 'Portal']))
    expect(btn().closest('th').getAttribute('aria-sort')).toBe('ascending')
    await waitFor(() => expect(window.location.search).toContain('mo_sort=name_asc'))
    fireEvent.click(btn())
    await waitFor(() => expect(names()).toEqual(['Portal', 'Login akışı', 'GW', 'API sağlık']))
    fireEvent.click(btn())
    await waitFor(() => expect(names()[0]).toBe('API sağlık'))   // varsayılan: sorunlu önce
    expect(btn().closest('th').getAttribute('aria-sort')).toBe('none')
    // son kontrol: en yeni önce, hiç kontrol edilmemiş sonda
    fireEvent.click(container.querySelector('[data-slot="mo-sort"][data-sort-key="last"]'))
    await waitFor(() => expect(names()).toEqual(['API sağlık', 'Portal', 'GW', 'Login akışı']))
    // başarı oranı: en düşük önce, koşumsuz sonda
    fireEvent.click(container.querySelector('[data-slot="mo-sort"][data-sort-key="uptime"]'))
    await waitFor(() => expect(names()).toEqual(['API sağlık', 'GW', 'Portal', 'Login akışı']))
    await waitFor(() => expect(window.location.search).toContain('mo_sort=uptime_asc'))
    // yanıt süresi: en yavaş önce
    fireEvent.click(container.querySelector('[data-slot="mo-sort"][data-sort-key="response"]'))
    await waitFor(() => expect(names()).toEqual(['API sağlık', 'Portal', 'GW', 'Login akışı']))
  })

  it('URL\'den açılış: mo_type=http,ping&mo_sort=name_desc → iki tür süzülü, Z→A', async () => {
    window.history.replaceState({}, '', '/?tab=monitoring&mo_type=http,ping&mo_sort=name_desc')
    api.monitoring.getOverview.mockResolvedValue(DATA)
    const { container } = render(<MonitoringOverviewPage />)
    await waitFor(() => expect(rowsOf(container).length).toBe(3))
    expect([...container.querySelectorAll('[data-slot="mo-row"] td:nth-child(2) .font-semibold')].map((e) => e.textContent)).toEqual(['Portal', 'GW', 'API sağlık'])
  })
})

// ── KPI özet pencereleri (2026-10-01, kullanıcı isteği) ────────────────────────────────────────────────────────────
const ORPHAN_DATA = {
  success: true,
  data: {
    ...DATA.data,
    totals: { ...DATA.data.totals, total: 6, paused: 2, inventory_inactive: 1 },
    types: DATA.data.types.map((ty) => (ty.type === 'dns' ? { ...ty, total: 2, active: 1, paused: 1, inventory_inactive: 1 } : ty)),
    monitors: [
      ...DATA.data.monitors,
      { type: 'dns', id: 7, name: 'ivr.example.com', target: 'ivr.example.com', team_id: 14, team_name: 'SY', active: false, deleted: false,
        standalone: false, inventory_inactive: true, status: 'paused', last_checked_at: '2026-08-12T16:13:42', open_alerts: 0, checks_window: 0, failed_window: 0,
        standalone_twin: { id: 9, name: 'IVR (bağımsız)', target: 'ivr.example.com', status: 'up' } },
      { type: 'dns', id: 9, name: 'IVR (bağımsız)', target: 'ivr.example.com', team_id: 14, team_name: 'SY', active: true, deleted: false,
        standalone: true, inventory_inactive: false, status: 'up', last_checked_at: '2026-09-30T13:58:00', open_alerts: 0, checks_window: 287, failed_window: 0 },
    ],
  },
}

describe('İzleme Panosu — KPI özet pencereleri', () => {
  beforeEach(() => { vi.clearAllMocks(); window.history.replaceState({}, '', '/?tab=monitoring'); try { localStorage.clear() } catch { /* yok */ } })

  it('Sorunlu: özet kutucukları, tür/takım dağılımı, sorunlu izleme satırı (son hata + açık kalma süresi) ve eylemleri; URL mo_dlg', async () => {
    api.monitoring.getOverview.mockResolvedValue(DATA)
    const events = []
    const onNav = (e) => events.push(e.detail)
    window.addEventListener('sm:navigate', onNav)
    const { container } = render(<MonitoringOverviewPage />)
    await waitFor(() => expect(rowsOf(container).length).toBe(4))
    expect(kpiButton(container, 'down')).toHaveAccessibleName(/failing — open the summary/i)
    fireEvent.click(kpiButton(container, 'down'))
    const dlg = await screen.findByRole('dialog')
    expect(within(dlg).getByText('Failing monitors', { selector: 'h2 *, h2' })).toBeInTheDocument()
    await waitFor(() => expect(window.location.search).toContain('mo_dlg=down'))
    const body = dialog('down')
    const tiles = [...body.querySelectorAll('[data-slot="mo-dlg-tile"]')].map((x) => x.textContent)
    expect(tiles[0]).toMatch(/^1Failing monitors/)
    expect(tiles[1]).toMatch(/^1With a critical alert/)
    expect(body.querySelector('[data-slot="mo-dlg-bytype"]').textContent).toMatch(/HTTP.*1/)
    expect(body.querySelector('[data-slot="mo-dlg-byteam"]').textContent).toMatch(/SY.*1/)
    const items = body.querySelectorAll('[data-slot="mo-dlg-item"]')
    expect(items).toHaveLength(1)
    expect(items[0].textContent).toMatch(/HTTP 503 · open for/)
    fireEvent.click(within(items[0]).getByRole('button', { name: /API sağlık — go to its monitor page/i }))
    expect(events.at(-1)).toEqual({ tab: 'http', params: { q: 'https://api.example.com/health' } })
    window.removeEventListener('sm:navigate', onNav)
  })

  it('Kontrolü gecikmiş: beklenen aralık + eşik cümlesi ve açıklama notu; "Listede süz" gecikmiş durumuna süzer', async () => {
    api.monitoring.getOverview.mockResolvedValue(DATA)
    const { container } = render(<MonitoringOverviewPage />)
    await waitFor(() => expect(rowsOf(container).length).toBe(4))
    fireEvent.click(kpiButton(container, 'stale'))
    await waitFor(() => expect(dialog('stale')).not.toBeNull())
    const body = dialog('stale')
    const item = body.querySelector('[data-slot="mo-dlg-item"]')
    expect(item.textContent).toMatch(/GW/)
    expect(item.textContent).toMatch(/expected every 1 min · overdue after 10 min/)
    expect(body.textContent).toMatch(/What does overdue mean\?/)
    expect(body.textContent).toMatch(/removed from the inventory/)
    filterInDialog()
    await waitFor(() => expect(rowsOf(container).map((r) => r.getAttribute('data-status'))).toEqual(['stale']))
  })

  it('Açık alarm: seviye kutucukları, sahiplenilmemiş sayısı, "Alarm Geçmişi\'nde aç" ve "Listede süz" (alarm süzgeci)', async () => {
    api.monitoring.getOverview.mockResolvedValue(DATA)
    const events = []
    const onNav = (e) => events.push(e.detail)
    window.addEventListener('sm:navigate', onNav)
    const { container } = render(<MonitoringOverviewPage />)
    await waitFor(() => expect(rowsOf(container).length).toBe(4))
    fireEvent.click(kpiButton(container, 'alerts'))
    await waitFor(() => expect(dialog('alerts')).not.toBeNull())
    const tiles = [...dialog('alerts').querySelectorAll('[data-slot="mo-dlg-tile"]')].map((x) => x.textContent)
    expect(tiles).toEqual([expect.stringMatching(/^1Open alerts/), expect.stringMatching(/^1Critical/), expect.stringMatching(/^0High/),
      expect.stringMatching(/^0Warning/), expect.stringMatching(/^1Unacknowledged/)])
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: /open in alert history/i }))
    expect(events.at(-1)).toEqual({ tab: 'alerthistory', params: { view: 'open' } })
    window.removeEventListener('sm:navigate', onNav)
    // closeOnNavigate: içeriden gezinme pencereyi kapatır; yeniden aç → Listede süz
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    fireEvent.click(kpiButton(container, 'alerts'))
    await waitFor(() => expect(dialog('alerts')).not.toBeNull())
    filterInDialog()
    await waitFor(() => expect(rowsOf(container).map((r) => r.textContent)).toEqual([expect.stringMatching(/API sağlık/)]))
    await waitFor(() => expect(window.location.search).toContain('mo_al=any'))
  })

  it('Duraklatılmış: gerçek duraklatılmışlar + envanterden çıkarılmış eski izleme ayrı alt grupta, açıklama ve ASIL (bağımsız) izlemeye bağlantı', async () => {
    api.monitoring.getOverview.mockResolvedValue(ORPHAN_DATA)
    const events = []
    const onNav = (e) => events.push(e.detail)
    window.addEventListener('sm:navigate', onNav)
    const { container } = render(<MonitoringOverviewPage />)
    await waitFor(() => expect(rowsOf(container).length).toBe(6))
    expect(kpiButton(container, 'paused').textContent).toMatch(/1 out of inventory/)
    // Tür kartı: sayfa ile eşleşen sayım (aktif 1 + duraklatılmış 0) + envanter dışı çipi
    const dns = container.querySelector('[data-slot="mo-type-card"][data-type="dns"]')
    expect(dns.textContent).toMatch(/1 active · 0 paused/)
    expect(dns.querySelector('[data-slot="mo-type-inv"]').textContent).toMatch(/1 out of inventory/)
    fireEvent.click(kpiButton(container, 'paused'))
    await waitFor(() => expect(dialog('paused')).not.toBeNull())
    const body = dialog('paused')
    expect(body.textContent).toMatch(/Paused \(1\)/)
    const orphans = body.querySelector('[data-slot="mo-dlg-orphans"]')
    expect(orphans.textContent).toMatch(/Old monitors of hosts removed from the inventory \(1\)/)
    expect(orphans.textContent).toMatch(/the sweep skips it and the monitor page does not list it/)
    expect(orphans.querySelector('[data-slot="mo-dlg-twin"]').textContent).toMatch(/Real check: IVR \(bağımsız\)/)
    fireEvent.click(within(orphans).getByRole('button', { name: /IVR \(bağımsız\) — open the standalone monitor/i }))
    expect(events.at(-1)).toEqual({ tab: 'dns', params: { q: 'ivr.example.com' } })
    window.removeEventListener('sm:navigate', onNav)
  })

  it('URL mo_dlg=stale ile açılış: pencere açık gelir; Escape kapatır ve URL\'den düşer', async () => {
    window.history.replaceState({}, '', '/?tab=monitoring&mo_dlg=stale')
    api.monitoring.getOverview.mockResolvedValue(DATA)
    render(<MonitoringOverviewPage />)
    await waitFor(() => expect(dialog('stale')).not.toBeNull())
    fireEvent.keyDown(screen.getByRole('dialog'), { key: 'Escape' })
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    await waitFor(() => expect(window.location.search).not.toContain('mo_dlg'))
  })
})

describe('İzleme Panosu — zenginleştirmeler', () => {
  beforeEach(() => { vi.clearAllMocks(); window.history.replaceState({}, '', '/?tab=monitoring'); try { localStorage.clear() } catch { /* yok */ } })

  it('Filo sağlığı: hüküm + dağılım lejantı (duruma süzer) + dikkat gerektirenler (sorunlu önce) ve "Tümünü listede göster"', async () => {
    api.monitoring.getOverview.mockResolvedValue(DATA)
    const { container } = render(<MonitoringOverviewPage />)
    await waitFor(() => expect(rowsOf(container).length).toBe(4))
    const health = container.querySelector('[data-slot="mo-health"]')
    expect(health).toHaveAttribute('data-tone', 'bad')
    expect(health.querySelector('[data-slot="mo-verdict"]').textContent).toBe('Monitors failing: 1')
    expect(health.textContent).toMatch(/Healthy: 1 of 3 active monitors/)
    const att = [...health.querySelectorAll('[data-slot="mo-attention-item"]')]
    expect(att.map((x) => x.getAttribute('data-status'))).toEqual(['down', 'stale'])
    // Eylem adları tablodakilerden farklı → tek "… open on its monitor page" kalır
    expect(within(att[0]).getByRole('button', { name: /API sağlık — review its alerts/i })).toBeInTheDocument()
    fireEvent.click(health.querySelector('[data-slot="mo-legend-item"][data-status="stale"]'))
    await waitFor(() => expect(rowsOf(container).map((r) => r.getAttribute('data-status'))).toEqual(['stale']))
    fireEvent.click(health.querySelector('[data-slot="mo-attention-all"]'))
    await waitFor(() => expect(rowsOf(container).map((r) => r.getAttribute('data-status'))).toEqual(['down', 'stale']))
    expect(container.querySelector('[data-slot="mo-views"] [data-view="problems"]')).toHaveAttribute('data-state', 'on')
  })

  it('Takım sağlığı: en sorunlu takım üstte; satır o takıma süzer (aria-pressed), yeniden tık kaldırır', async () => {
    api.monitoring.getOverview.mockResolvedValue(DATA)
    const { container } = render(<MonitoringOverviewPage />)
    await waitFor(() => expect(rowsOf(container).length).toBe(4))
    const teamRows = [...container.querySelectorAll('[data-slot="mo-team-row"]')]
    expect(teamRows.map((x) => x.getAttribute('data-team'))).toEqual(['14', '7'])
    expect(teamRows[0]).toHaveAccessibleName(/SY: monitors 3, failing 1, overdue 0, open alerts 1/)
    fireEvent.click(teamRows[1])
    await waitFor(() => expect(rowsOf(container).map((r) => r.textContent)).toEqual([expect.stringMatching(/GW/)]))
    expect(container.querySelector('[data-slot="mo-team-row"][data-team="7"]')).toHaveAttribute('aria-pressed', 'true')
    await waitFor(() => expect(window.location.search).toContain('mo_team=7'))
    fireEvent.click(container.querySelector('[data-slot="mo-team-row"][data-team="7"]'))
    await waitFor(() => expect(rowsOf(container).length).toBe(4))
  })

  it('Hızlı görünümler: sayılar, seçim süzgeçleri değiştirir, arama korunur; süzgeç önayara uyuyorsa seçili görünür', async () => {
    api.monitoring.getOverview.mockResolvedValue(DATA)
    const { container } = render(<MonitoringOverviewPage />)
    await waitFor(() => expect(rowsOf(container).length).toBe(4))
    const view = (k) => container.querySelector(`[data-slot="mo-views"] [data-view="${k}"]`)
    expect(['all', 'problems', 'alerts', 'failing', 'paused'].map((k) => view(k).textContent.replace(/\D/g, ''))).toEqual(['4', '2', '1', '1', '1'])
    expect(view('all')).toHaveAttribute('data-state', 'on')
    fireEvent.click(view('paused'))
    await waitFor(() => expect(rowsOf(container).map((r) => r.getAttribute('data-status'))).toEqual(['paused']))
    fireEvent.click(view('failing'))
    await waitFor(() => expect(rowsOf(container).map((r) => r.textContent)).toEqual([expect.stringMatching(/API sağlık/)]))
    await waitFor(() => expect(window.location.search).toContain('mo_ck=failed'))
    expect(window.location.search).not.toContain('mo_status')
    fireEvent.click(view('all'))
    await waitFor(() => expect(rowsOf(container).length).toBe(4))
  })

  it('Satır: başarı ölçeri tonu, son yanıt + ortalama ve "yavaş" işareti', async () => {
    api.monitoring.getOverview.mockResolvedValue(DATA)
    const { container } = render(<MonitoringOverviewPage />)
    await waitFor(() => expect(rowsOf(container).length).toBe(4))
    const [api503, gw, portal] = rowsOf(container)
    expect(api503.querySelector('[data-slot="mo-uptime"]')).toHaveAttribute('data-tone', 'warn')
    expect(api503.querySelector('[data-slot="mo-uptime"]').textContent).toMatch(/^90%60 \/ 6/)
    expect(portal.querySelector('[data-slot="mo-uptime"]')).toHaveAttribute('data-tone', 'ok')
    const slow = api503.querySelector('td:nth-child(7) [data-slot="mo-response"]')
    expect(slow).toHaveAttribute('data-slow', 'true')
    expect(slow.textContent).toMatch(/800 ms.*Slow.*avg 200 ms/)
    expect(portal.querySelector('td:nth-child(7) [data-slot="mo-response"]')).not.toHaveAttribute('data-slow')
    expect(gw.querySelector('td:nth-child(7)').textContent).toBe('3 ms')
  })

  it('CSV: süzülen satırların TAMAMI, başlıklar ve hücreler; dosya adı damgalı', async () => {
    api.monitoring.getOverview.mockResolvedValue(DATA)
    const { container } = render(<MonitoringOverviewPage />)
    await waitFor(() => expect(rowsOf(container).length).toBe(4))
    fireEvent.click(container.querySelector('[data-slot="mo-type-card"][data-type="http"] button'))
    await waitFor(() => expect(rowsOf(container).length).toBe(2))
    fireEvent.click(screen.getByRole('button', { name: /download the 2 filtered monitors as csv/i }))
    expect(downloadCsv).toHaveBeenCalledTimes(1)
    const [name, csv] = downloadCsv.mock.calls[0]
    expect(name).toMatch(/^monitoring-overview-\d{8}-\d{4}\.csv$/)
    expect(csv.charCodeAt(0)).toBe(0xfeff)   // BOM: Excel Türkçe karakterleri doğru açsın
    const lines = csv.slice(1).trim().split('\r\n')
    expect(lines[0]).toBe('Type,Monitor,Target,Team,Status,Last check,Success (%),Runs,Failed,Last response (ms),Avg response (ms),Open alerts,Alert level,Alert opened,Last error,Out of inventory')
    expect(lines).toHaveLength(3)
    expect(lines[1]).toMatch(/^HTTP \/ Website,API sağlık,https:\/\/api\.example\.com\/health,SY,Failing,.+,90,60,6,800,200,1,CRITICAL,.+,HTTP 503,$/)
  })
})

describe('İzleme Panosu — model yardımcıları', () => {
  const rows = DATA.data.monitors
  it('başarı oranı, ton eşikleri, yavaşlık kuralı, gecikme eşiği', () => {
    expect(rowUptime({ checks_window: 10, failed_window: 1 })).toBe(90)
    expect(rowUptime({ checks_window: 0 })).toBeNull()
    expect(rowUptime({ success_rate_window: 99.5 })).toBe(99.5)
    expect([uptimeTone(99), uptimeTone(95), uptimeTone(89.9), uptimeTone(null)]).toEqual(['ok', 'warn', 'crit', null])
    expect(isSlow({ response_ms: 800, avg_response_ms_window: 200 })).toBe(true)
    expect(isSlow({ response_ms: 7, avg_response_ms_window: 3 })).toBe(false)          // küçük sayılarda gürültü
    expect(isSlow({ response_ms: 800, avg_response_ms_window: null })).toBe(false)
    expect(staleLimitMs(60)).toBe(600_000)          // taban 10 dk
    expect(staleLimitMs(3600)).toBe(10_800_000)     // 3×
    expect(staleLimitMs(null)).toBe(10_800_000)     // aralık yok → 3 saat
  })

  it('dikkat gerektirenler, takım sağlığı, hüküm, dağılım, alarm seviyeleri', () => {
    expect(attentionRows(rows).map((r) => r.id)).toEqual([1, 3])
    const th = teamHealth(rows, 'Takımsız')
    expect(th.map((g) => [g.key, g.total, g.down, g.stale, g.openAlerts, g.uptime])).toEqual([['14', 3, 1, 0, 1, 94], ['7', 1, 0, 1, 0, 100]])
    expect(verdict(DATA.data.totals)).toEqual({ tone: 'bad', key: 'mo.health.down', n: 1 })
    expect(verdict({ total: 2, active: 2 })).toEqual({ tone: 'ok', key: 'mo.health.ok', n: 2 })
    expect(verdict({ total: 1, active: 0, paused: 1 })).toMatchObject({ key: 'mo.health.allPaused' })
    expect(distribution(DATA.data.totals).map((x) => x.count)).toEqual([1, 1, 1, 0, 1])
    expect(alertLevelCounts(rows)).toMatchObject({ CRITICAL: 1, HIGH: 0, WARNING: 0, alerts: 1, monitors: 1 })
  })

  it('hızlı görünüm eşleşmesi ve sayıları; sıralama anahtarları uptime/response', () => {
    expect(matchQuickView({ statuses: ['stale', 'down', 'unknown'], q: 'x' })).toBe('problems')
    expect(matchQuickView({ statuses: ['down'] })).toBe('')
    expect(matchQuickView({})).toBe('all')
    expect(quickViewCounts(rows)).toEqual({ all: 4, problems: 2, alerts: 1, failing: 1, paused: 1 })
    expect(parseSort('uptime')).toEqual({ key: 'uptime', dir: 'asc' })
    expect(parseSort('response')).toEqual({ key: 'response', dir: 'desc' })
    expect(toggleSort({ key: '', dir: 'asc' }, 'response')).toEqual({ key: 'response', dir: 'desc' })
    expect(sortRows(rows, { key: 'uptime', dir: 'desc' }).map((r) => r.id)).toEqual([3, 2, 1, 4])   // koşumsuz iki yönde de sonda
  })

  it('CSV formül nötrleme ortak yardımcıdan: "=" ile başlayan hata metni metin olarak kalır', () => {
    const t = (k, ...a) => `${k}${a.length ? ':' + a.join('|') : ''}`
    const csv = overviewCsv([{ ...rows[0], last_error: '=cmd|calc' }], { t, locale: 'en-GB', typeLabel: (k) => k, statusLabel: (k) => k })
    expect(csv).toContain("'=cmd|calc")
  })
})
