import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'
import StormStatusPage from '../components/StormStatusPage.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: withApiFallback({ monitoring: { storm: { status: vi.fn(), history: vi.fn(), analytics: vi.fn(), detail: vi.fn() } } }),
}))
import { api } from '../api/client'

const iso = (min) => new Date(Date.now() + min * 60_000).toISOString().slice(0, 19)

const STATUS = { success: true, data: {
  generated_at: iso(0), settings: { enabled: true, threshold_unit: 'COUNT', threshold_value: 5, window_minutes: 5, quiet_minutes: 5, per_group: false, re_alert_hours: 24, min_threshold: 2, percent_min_targets: 3 },
  totals: { teams: 3, storming: 1, near: 1, open_storms: 1 },
  teams: [
    { team_id: 3, team_name: 'Takım C', status: 'CALM', threshold: 5, active_monitors: 8, window_minutes: 5, window_targets: 0, window_alerts: 0, window_items: [], last_storm_at: null, storms_30d: 1, storms: [] },
    { team_id: 1, team_name: 'Takım A', status: 'STORM', threshold: 5, active_monitors: 40, window_minutes: 5, window_targets: 6, window_alerts: 7, window_items: [], last_storm_at: iso(-20), storms_30d: 3,
      storms: [{ id: 7, team_id: 1, team_name: 'Takım A', resolved: false, created_at: iso(-20), root_cause: 'HTTP_DOWN', member_count: 7, threshold_effective: 5, targets_at_open: 6, peak_targets: 7,
        quiet_minutes: 5, last_member_at: iso(-2), seal_at: iso(3), sealed: false, active_down: 6, active_members: 7, recovered_members: 1, resolve_floor: 3,
        trigger: { id: 300, domain: 'https://a.example.com', alert_type: 'HTTP_DOWN', alert_level: 'CRITICAL', created_at: iso(-20) } }] },
    { team_id: 2, team_name: 'Takım B', status: 'NEAR', threshold: 5, active_monitors: 12, window_minutes: 5, window_targets: 4, window_alerts: 4,
      window_items: [{ id: 401, domain: 'b1.example.com', alert_type: 'PING_DOWN', alert_level: 'HIGH', created_at: iso(-3) }], last_storm_at: null, storms_30d: 0, storms: [] },
  ], legacy_open: [] } }

const HISTORY = { success: true, data: { items: [
  { id: 6, team_id: 1, team_name: 'Takım A', resolved: true, created_at: iso(-600), resolved_at: iso(-500), resolve_reason: 'SEALED', duration_ms: 6_000_000, root_cause: 'HTTP_DOWN', member_count: 5, members_total: 5, members_recovered: 3, targets_at_open: 5, threshold_effective: 5 },
  { id: 5, team_id: 2, team_name: 'Takım B', resolved: true, created_at: iso(-3000), resolved_at: iso(-2900), resolve_reason: 'FLOOR', duration_ms: 5_400_000, root_cause: 'MIXED', member_count: 6, members_total: 6, members_recovered: 6 },
], total: 2, page: 0, size: 20, total_pages: 1 } }

const ANALYTICS = { success: true, data: { days: 30, from: '2026-09-01', to: '2026-09-30', total: 3, open: 1, sealed: 1, avg_duration_ms: 5_700_000, avg_members: 6,
  series: [{ day: '2026-09-01', total: 2, by_team: { 1: 2 } }, { day: '2026-09-02', total: 1, by_team: { 2: 1 } }],
  teams: [{ team_id: 1, team_name: 'Takım A', storms: 2, open: 1, sealed: 1, floor: 0, avg_duration_ms: 6_000_000, avg_members: 6, max_peak_targets: 7, last_storm_at: iso(-20) },
          { team_id: 2, team_name: 'Takım B', storms: 1, open: 0, sealed: 0, floor: 1, avg_duration_ms: 5_400_000, avg_members: 6, max_peak_targets: 6, last_storm_at: iso(-3000) }],
  reasons: { SEALED: 1, FLOOR: 1, OPEN: 1 }, root_causes: { HTTP_DOWN: 2, MIXED: 1 }, hours: Array.from({ length: 24 }, (_, h) => (h === 3 ? 2 : 0)), recent: [] } }

const DETAIL = { success: true, data: { id: 7, team_id: 1, team_name: 'Takım A', resolved: false, created_at: iso(-20), root_cause: 'HTTP_DOWN', member_count: 7,
  threshold_effective: 5, threshold_unit: 'COUNT', threshold_value: 5, window_minutes: 5, quiet_minutes: 5, targets_at_open: 6, peak_targets: 7,
  last_member_at: iso(-2), last_re_alert_at: iso(-20), seal_at: iso(3), sealed: false, duration_ms: 1_200_000,
  trigger: { id: 300, domain: 'https://a.example.com', alert_type: 'HTTP_DOWN', alert_level: 'CRITICAL', created_at: iso(-20) },
  members: [
    { event_id: 300, domain: 'https://a.example.com', alert_type: 'HTTP_DOWN', alert_level: 'CRITICAL', team_id: 1, created_at: iso(-20), resolved: false, join_kind: 'TRIGGER', joined_at: iso(-20), announced_at: iso(-20), trigger: true },
    { event_id: 301, domain: 'https://b.example.com', alert_type: 'HTTP_DOWN', alert_level: 'HIGH', team_id: 1, created_at: iso(-19), resolved: true, resolved_at: iso(-5), join_kind: 'PEER', joined_at: iso(-20), announced_at: iso(-20), left_at: iso(-5), leave_kind: 'RECOVERED', trigger: false },
  ], members_total: 2, members_recovered: 1, members_down: 1, notifications: { initial: 7, realert: 0, resolve: 0, suppressed: 2, push: 3, push_members: 12, last_mail_at: iso(-20) } } }

/** Radix Tabs tetikleyicisi onMouseDown ile etkinleşir; click tek başına yetmez. */
function pickTab(re) { const el = screen.getByRole('tab', { name: re }); fireEvent.mouseDown(el, { button: 0 }); fireEvent.click(el) }

function navEvents() {
  const events = []
  window.addEventListener('sm:navigate', (e) => events.push(e.detail))
  return events
}

describe('Alarm Fırtınası sayfası (2026-09-30)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    window.history.replaceState({}, '', '/?tab=storms')
    try { localStorage.clear() } catch { /* yok say */ }
    api.monitoring.storm.status.mockResolvedValue(STATUS)
    api.monitoring.storm.history.mockResolvedValue(HISTORY)
    api.monitoring.storm.analytics.mockResolvedValue(ANALYTICS)
    api.monitoring.storm.detail.mockResolvedValue(DETAIL)
  })

  it('Durum: KPI şeridi, ayar rozetleri; takım kartları fırtına → yakın → sakin sıralı; fırtına özeti mühür geri sayımı ve tetikleyeni gösterir', async () => {
    const { container } = render(<StormStatusPage />)
    await waitFor(() => expect(container.querySelectorAll('[data-slot="sf-team"]').length).toBe(3))
    const kpi = (k) => container.querySelector(`[data-slot="stat-item"][data-key="${k}"] [data-slot="stat-value"]`).textContent
    expect(kpi('storming')).toBe('1'); expect(kpi('near')).toBe('1'); expect(kpi('open')).toBe('1'); expect(kpi('total30')).toBe('4')
    expect(container.querySelector('[data-slot="sf-setting-threshold"]').textContent).toMatch(/5/)
    const cards = [...container.querySelectorAll('[data-slot="sf-team"]')]
    expect(cards.map((c) => c.getAttribute('data-status'))).toEqual(['STORM', 'NEAR', 'CALM'])
    expect(within(cards[0]).getByText(/6 \/ 5/)).toBeInTheDocument()
    const storm = cards[0].querySelector('[data-slot="sf-storm"][data-storm-id="7"]')
    expect(storm).not.toBeNull()
    expect(storm.querySelector('[data-slot="sf-seal-in"]')).not.toBeNull()
    expect(storm.textContent).toMatch(/a\.example\.com/)
    // eşiğe yakın takım: pencere alarmları listelenir
    expect(cards[1].querySelector('[data-slot="sf-window-items"]').textContent).toMatch(/b1\.example\.com/)
  })

  it('Açıklama ve kural kartı GERÇEK ayarlardan: pencere 7 dk + eşik 4 hedef; kapanış tabanı 2; sessiz pencere 15 dk; grup kapsamı', async () => {
    api.monitoring.storm.status.mockResolvedValue({ ...STATUS, data: { ...STATUS.data,
      settings: { enabled: true, threshold_unit: 'COUNT', threshold_value: 4, window_minutes: 7, quiet_minutes: 15, per_group: true, re_alert_hours: 24, min_threshold: 2, percent_min_targets: 3 } } })
    const { container } = render(<StormStatusPage />)
    await waitFor(() => expect(container.querySelector('[data-slot="sf-rules"]')).not.toBeNull())
    expect(screen.getByText(/7-minute window/)).toBeInTheDocument()
    expect(screen.getByText(/4 distinct targets go down/)).toBeInTheDocument()
    expect(document.body.textContent).not.toContain('{0}')
    const rule = (id) => container.querySelector(`[data-slot="sf-rule"][data-rule="${id}"]`).textContent
    expect(rule('threshold')).toMatch(/4 distinct targets/)
    expect(rule('window')).toMatch(/7 min/)
    expect(rule('quiet')).toMatch(/15 min/)
    expect(rule('floor')).toMatch(/below 2/)
    expect(rule('realert')).toMatch(/24 hours/)
    expect(rule('scope')).toMatch(/notification group/)
    // 2026-10-04: alan yoksa (eski sunucu) push kuralı varsayılanı gösterir — toplu fırtına push'u (kullanıcı kararı)
    expect(rule('push')).toMatch(/one summary storm push/i)
    expect(container.querySelector('[data-slot="sf-rules"]').getAttribute('data-enabled')).toBe('true')
  })

  it('Kural kartı push kipini GERÇEK ayardan gösterir (2026-10-03): push_individual=false → toplu fırtına push\'u; true → alarm başına', async () => {
    api.monitoring.storm.status.mockResolvedValue({ ...STATUS, data: { ...STATUS.data,
      settings: { ...STATUS.data.settings, push_individual: false } } })
    const { container, unmount } = render(<StormStatusPage />)
    await waitFor(() => expect(container.querySelector('[data-rule="push"]')).not.toBeNull())
    expect(container.querySelector('[data-rule="push"]').textContent).toMatch(/one summary storm push/i)
    unmount()
    api.monitoring.storm.status.mockResolvedValue({ ...STATUS, data: { ...STATUS.data,
      settings: { ...STATUS.data.settings, push_individual: true } } })
    const { container: c2 } = render(<StormStatusPage />)
    await waitFor(() => expect(c2.querySelector('[data-rule="push"]')).not.toBeNull())
    expect(c2.querySelector('[data-rule="push"]').textContent).toMatch(/one per alert/i)
    expect(c2.querySelector('[data-rule="push"]').textContent).toMatch(/hourly push cap/i)
  })

  it('Yüzde birimi ve kapalı koruma: eşik cümlesi yüzdeyle (en az 3), kart "Koruma kapalı" ve başlıkta uyarı rozeti', async () => {
    api.monitoring.storm.status.mockResolvedValue({ ...STATUS, data: { ...STATUS.data,
      settings: { enabled: false, threshold_unit: 'PERCENT', threshold_value: 10, window_minutes: 5, quiet_minutes: 5, per_group: false, re_alert_hours: 24, min_threshold: 2, percent_min_targets: 3 } } })
    const { container } = render(<StormStatusPage />)
    await waitFor(() => expect(container.querySelector('[data-slot="sf-rules"]')).not.toBeNull())
    expect(container.querySelector('[data-slot="sf-rule"][data-rule="threshold"]').textContent).toMatch(/10% of the active monitors \(at least 3\)/)
    expect(container.querySelector('[data-slot="sf-rules"]').getAttribute('data-enabled')).toBe('false')
    expect(container.querySelector('[data-slot="sf-rules-state"]').textContent).toMatch(/protection off/i)
    expect(screen.getAllByText(/protection off/i).length).toBeGreaterThanOrEqual(1)
  })

  it('"Fırtına ayarları" Ayarlar → Alarm Fırtınası\'na, "Takımın açık alarmları" Alarm Geçmişi\'ne takım süzgeciyle gider', async () => {
    const events = navEvents()
    const { container } = render(<StormStatusPage />)
    await waitFor(() => expect(container.querySelectorAll('[data-slot="sf-team"]').length).toBe(3))
    fireEvent.click(screen.getByRole('button', { name: /fırtına ayarları|storm settings/i }))
    expect(events.at(-1)).toEqual({ tab: 'settings', params: { sec: 'storm' } })
    fireEvent.click(screen.getByRole('button', { name: /takımın açık alarmları|team's open alerts/i }))
    expect(events.at(-1)).toEqual({ tab: 'alerthistory', params: { view: 'open', team: '1' } })
  })

  it('Ayrıntı: fırtına #7 penceresi üyeleri (tetikleyen rozeti, kurtulan), bildirim özetini ve zaman çizelgesini gösterir; üye satırı alarmı açar', async () => {
    const events = navEvents()
    const { container } = render(<StormStatusPage />)
    await waitFor(() => expect(container.querySelectorAll('[data-slot="sf-team"]').length).toBe(3))
    fireEvent.click(within(container.querySelector('[data-slot="sf-storm"][data-storm-id="7"]')).getByRole('button', { name: /ayrıntı|details/i }))
    await waitFor(() => expect(api.monitoring.storm.detail).toHaveBeenCalledWith(7))
    const dlg = await screen.findByRole('dialog')
    await waitFor(() => expect(dlg.querySelectorAll('[data-slot="sf-member"]').length).toBe(2))
    expect(dlg.querySelector('[data-slot="sf-trigger"]')).not.toBeNull()
    expect(dlg.querySelectorAll('[data-slot="sf-member-state"][data-resolved="true"]').length).toBe(1)
    expect(dlg.querySelector('[data-slot="sf-notifications"]').textContent).toMatch(/7/)
    // 2026-10-03: toplu push sayacı ile üye alarmlara giden bireysel push sayacı ayrı
    expect(dlg.querySelector('[data-slot="sf-notifications"]').textContent).toMatch(/3 summary pushes/)
    expect(dlg.querySelector('[data-slot="sf-push-members"]').textContent).toMatch(/12 individual pushes/)
    expect(dlg.querySelectorAll('[data-slot="sf-timeline"] li').length).toBeGreaterThanOrEqual(3)
    await waitFor(() => expect(window.location.search).toContain('sf_storm=7'))
    fireEvent.click(within(dlg).getByRole('button', { name: /https:\/\/b\.example\.com/ }))
    expect(events.at(-1)).toEqual({ tab: 'alerthistory', params: { alert: '301', view: 'closed', q: 'https://b.example.com' } })
  })

  it('Geçmiş: sekme yüklenince liste gelir; takım / yalnız kapanmış süzgeçleri isteğe geçer; boş sonuçta temizle', async () => {
    const { container } = render(<StormStatusPage />)
    await waitFor(() => expect(container.querySelectorAll('[data-slot="sf-team"]').length).toBe(3))
    pickTab(/geçmiş|history/i)
    await waitFor(() => expect(api.monitoring.storm.history).toHaveBeenCalledWith(expect.objectContaining({ page: 0, size: expect.any(Number), resolvedOnly: false })))
    await waitFor(() => expect(container.querySelectorAll('[data-slot="sf-history-row"]').length).toBe(2))
    expect(container.querySelector('[data-slot="sf-history-row"][data-storm-id="6"] [data-slot="sf-reason"]').getAttribute('data-reason')).toBe('SEALED')
    fireEvent.change(screen.getByRole('combobox', { name: /^takım$|^team$/i }), { target: { value: '2' } })
    await waitFor(() => expect(api.monitoring.storm.history).toHaveBeenLastCalledWith(expect.objectContaining({ teamId: '2' })))
    fireEvent.click(screen.getByRole('checkbox', { name: /yalnız kapanmış|closed only/i }))
    await waitFor(() => expect(api.monitoring.storm.history).toHaveBeenLastCalledWith(expect.objectContaining({ resolvedOnly: true })))
    await waitFor(() => expect(window.location.search).toContain('sf_team=2'))
    api.monitoring.storm.history.mockResolvedValue({ success: true, data: { items: [], total: 0, page: 0, size: 20, total_pages: 0 } })
    fireEvent.change(screen.getByLabelText(/başlangıç|from/i), { target: { value: '2026-09-01' } })
    await waitFor(() => expect(screen.getByText(/bu aralıkta fırtına yok|no storms in this range/i)).toBeInTheDocument())
    fireEvent.click(screen.getAllByRole('button', { name: /süzgeçleri temizle|clear filters/i })[0])
    await waitFor(() => expect(api.monitoring.storm.history).toHaveBeenLastCalledWith(expect.objectContaining({ teamId: undefined, from: undefined, resolvedOnly: false })))
  })

  it('Analiz: 30 gün varsayılan; 7 gün seçilince yeniden yükler; takım özeti tablosu, kapanış nedenleri ve saat dağılımı çizilir', async () => {
    const { container } = render(<StormStatusPage />)
    await waitFor(() => expect(container.querySelectorAll('[data-slot="sf-team"]').length).toBe(3))
    pickTab(/analiz|analysis/i)
    await waitFor(() => expect(api.monitoring.storm.analytics).toHaveBeenCalledWith({ teamId: undefined, days: 30 }))
    await waitFor(() => expect(container.querySelector('[data-slot="sf-team-stats"]')).not.toBeNull())
    expect(container.querySelectorAll('[data-slot="sf-team-stats"] tbody tr').length).toBe(2)
    expect(container.querySelectorAll('[data-slot="sf-reasons"] [data-slot="sf-reason"]').length).toBe(3)
    expect(container.querySelectorAll('[data-slot="sf-hours"] span').length).toBe(24)
    fireEvent.click(screen.getByRole('radio', { name: /son 7 gün|last 7 days/i }))
    await waitFor(() => expect(api.monitoring.storm.analytics).toHaveBeenLastCalledWith({ teamId: undefined, days: 7 }))
    await waitFor(() => expect(window.location.search).toContain('sf_days=7'))
  })

  it('URL\'den açılış: sf_tab=history&sf_team=2 → geçmiş sekmesi o takımla yüklenir', async () => {
    window.history.replaceState({}, '', '/?tab=storms&sf_tab=history&sf_team=2&sf_res=1')
    render(<StormStatusPage />)
    await waitFor(() => expect(api.monitoring.storm.history).toHaveBeenCalledWith(expect.objectContaining({ teamId: '2', resolvedOnly: true })))
    expect(screen.getByRole('tab', { name: /geçmiş|history/i })).toHaveAttribute('aria-selected', 'true')
  })

  it('yükleme hatası: hata bloğu + Yenile; başarıda takım kartları; boş kapsamda boş durum', async () => {
    api.monitoring.storm.status.mockResolvedValueOnce({ success: false, error: 'kapalı' })
    const { container } = render(<StormStatusPage />)
    await waitFor(() => expect(screen.getByText(/yüklenemedi|could not be loaded/i)).toBeInTheDocument())
    api.monitoring.storm.status.mockResolvedValueOnce({ success: true, data: { ...STATUS.data, teams: [], totals: { teams: 0, storming: 0, near: 0, open_storms: 0 } } })
    fireEvent.click(screen.getAllByRole('button', { name: /yenile|refresh/i })[0])
    await waitFor(() => expect(screen.getByText(/görüş kapsamında takım yok|no teams in your view scope/i)).toBeInTheDocument())
    expect(container.querySelectorAll('[data-slot="sf-team"]').length).toBe(0)
  })
})
