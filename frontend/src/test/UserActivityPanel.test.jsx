import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'
import UserActivityPanel from '../components/admin/useractivity/UserActivityPanel.jsx'

/**
 * Kullanıcı / Oturum paneli (2026-09-13 zenginleştirme): bölümler, süzgeç ↔ URL, boşta bandı + kendi oturum koruması,
 * sayfa kullanımı + hiç açılmayanlar, giriş durumu pili, takım "hiç girmedi", kaynak ilk görülme, anomali onayı,
 * gerekçeli sonlandırma, detay modalı (zaman çizelgesi + sicil maskesi), CSV/bağlantı.
 */
const { apiMock } = vi.hoisted(() => ({
  apiMock: {
    admin: {
      getLoginSeries: vi.fn(), getUserTimeline: vi.fn(), ackAnomaly: vi.fn(), terminateUserSession: vi.fn(),
    },
  },
}))
vi.mock('../api/client', () => ({ api: apiMock, formatDate: (s) => s ?? '', formatDateSec: (s) => s ?? '', formatDateOnly: (s) => (s ?? '').slice(0, 10) }))
vi.mock('../components/admin/LoginActivityChart.jsx', () => ({ default: () => <div data-testid="login-chart" /> }))
import { api } from '../api/client'

const nowIso = (offsetSec) => new Date(Date.now() - offsetSec * 1000).toISOString().slice(0, 19)
const DATA = {
  generated_at: '2026-09-13T00:30:00', window_days: 7, office_hours: { start: 8, end: 20 },
  summary: { active_count: 2, logins_24h: 10, failed_24h: 3, anomalies_24h: 1, unique_users_24h: 4, logins_7d: 60, failed_7d: 5, anomalies_7d: 2, unique_users_7d: 9, total_users: 6, dormant_30d: 2, dormant_90d: 1, never_logged_in: 1 },
  active_users: [
    { username: 'admin', user_id: 1, display_name: 'Yönetici', system_role: 'ADMIN', team_id: 5, team_name: 'Takım A', login_at: nowIso(3600), last_seen: nowIso(20), duration_min: 60, idle_sec: 20, expires_in_sec: 3580, last_tab: 'forecast', last_tab_at: nowIso(20), ip: '10.0.0.1', city: 'Istanbul', country: 'TR', user_agent: 'Chrome/1 Windows', last_login_method: 'remember-me', last_login_at: nowIso(3600), employee_id: 'E-1' },
    { username: 'bob', user_id: 2, display_name: 'Bob', system_role: 'USER', team_id: 9, team_name: 'Takım B', login_at: nowIso(7200), last_seen: nowIso(2400), duration_min: 120, idle_sec: 2400, expires_in_sec: 1200, last_tab: 'domains', ip: '10.0.0.2', user_agent: 'Firefox/1', last_login_at: nowIso(7200), employee_id: 'E-2' },
  ],
  login_status: [
    { username: 'admin', user_id: 1, system_role: 'ADMIN', team_name: 'Takım A', last_login_at: nowIso(3600), failed_since_login: 0, last_login_method: 'remember-me' },
    { username: 'bob', user_id: 2, system_role: 'USER', team_name: 'Takım B', last_login_at: nowIso(7200), failed_since_login: 2 },
    { username: 'carol', user_id: 3, system_role: 'USER', team_name: 'Takım A', last_login_at: nowIso(45 * 86400), failed_since_login: 0 },
    { username: 'dave', user_id: 4, system_role: 'USER', team_name: 'Takım B', last_login_at: null, failed_since_login: 0 },
  ],
  series: { day: [4, 5, 6, 5, 5, 5, 10].map((v, i) => ({ ts: `2026-09-0${i + 1}T00:00:00`, success: v, failed: i === 6 ? 3 : 0 })) },
  top_users: [], top_sources: [
    { ip: '10.0.0.1', total: 8, success: 8, failed: 0, country: 'TR', city: 'Istanbul', org: 'Example ISP', user_count: 2, users: ['admin', 'bob'], first_seen: nowIso(3 * 86400), last_seen: nowIso(60), new_this_week: true },
  ],
  anomalies: { total: 2, unacked_recent: 1, counts: { OFF_HOURS: 2, UNUSUAL_IP: 0, GEO_VELOCITY: 0, BRUTE_FORCE: 0, RATE_LIMITED: 0 }, recent: [
    { id: 7, time: '2026-09-12T20:00:00', actor: 'bob', ip: '10.0.0.2', outcome: 'FAILURE', flags: 'OFF_HOURS', reason: 'bad password', ack: null },
    { id: 8, time: '2026-09-11T21:00:00', actor: 'bob', ip: '10.0.0.2', outcome: 'SUCCESS', flags: 'OFF_HOURS', ack: { by: 'admin', at: nowIso(600), note: 'seen' } },
  ] },
  role_team: { by_role: [{ role: 'ADMIN', count: 6, users: [{ username: 'admin', count: 6 }] }, { role: 'USER', count: 4, users: [] }],
    by_team: [{ team_id: 5, team_name: 'Takım A', count: 6, users: [{ username: 'admin', count: 6 }], member_count: 2, never_logged: [{ username: 'carol', user_id: 3 }] }, { team_id: 9, team_name: 'Takım B', count: 4, users: [], member_count: 2, never_logged: [] }] },
  heatmaps: [{ from: '2026-09-07T00:00:00', to: '2026-09-14T00:00:00', matrix: Array.from({ length: 7 }, () => Array(24).fill(0)), failed: Array.from({ length: 7 }, () => Array(24).fill(0)), max: 1, cells: { '0-9': [{ time: '2026-09-07T09:00:00', actor: 'admin', outcome: 'SUCCESS' }] }, row_totals: [1, 0, 0, 0, 0, 0, 0], col_totals: [], total: 1, today_dow: 6 }],
  details: { logins: [], failed: [], anomalies: [], unique_users: [], dormant: [{ username: 'carol', user_id: 3, last_login_at: nowIso(45 * 86400), team_name: 'Takım A' }, { username: 'dave', user_id: 4, last_login_at: null }] },
  usage: { days: 7, ping_seconds: 15, total_minutes: 100, pages: [{ tab: 'forecast', minutes: 90, users: 2, share: 90, last_seen: nowIso(120) }, { tab: 'domains', minutes: 10, users: 1, share: 10, last_seen: nowIso(60) }],
    users: [{ username: 'admin', user_id: 1, minutes: 95, pages: 2, top_tab: 'forecast', team_name: 'Takım A' }], teams: [{ team_id: 5, team_name: 'Takım A', tabs: { forecast: 1, domains: 1 } }], trend: [] },
}

function renderPanel(props = {}) {
  const onRefresh = vi.fn()
  const utils = render(<UserActivityPanel data={DATA} error={false} refreshing={false} onRefresh={onRefresh} isAdmin globalAdmin={false} username="admin" {...props} />)
  return { ...utils, onRefresh }
}

describe('UserActivityPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks(); window.history.replaceState({}, '', '/?tab=health')
    api.admin.getLoginSeries.mockResolvedValue({ success: true, data: { buckets: [], granularity: 'day' } })
    api.admin.getUserTimeline.mockResolvedValue({ success: true, data: { username: 'bob', logins: 2, failed: 1, distinct_ips: 2, events: [
      { id: 7, time: '2026-09-12T20:00:00', outcome: 'FAILURE', flags: 'OFF_HOURS', ip: '10.0.0.2', user_agent: 'Chrome/1 Windows' },
      { id: 6, time: '2026-09-11T09:00:00', outcome: 'SUCCESS', ip: '10.0.0.1', user_agent: 'Firefox/1' },
    ] } })
    api.admin.ackAnomaly.mockResolvedValue({ success: true, data: { audit_id: 7, acknowledged: true } })
    api.admin.terminateUserSession.mockResolvedValue({ success: true, data: { username: 'bob' } })
  })

  it('bölümler, tazelik damgası, KPI kartları (atıl hesap + başarısız oranı) ve pencere seçimi (u_range URL\'de)', async () => {
    renderPanel()
    expect(screen.getByText(/veri · 2026-09-13T00:30:00|data · 2026-09-13T00:30:00/)).toBeInTheDocument()
    for (const n of ['02', '03', '04', '05', '06', '07', '08', '09']) expect(screen.getByText(n)).toBeInTheDocument()
    expect(screen.getByText(/Atıl hesap|Dormant accounts/)).toBeInTheDocument()
    expect(screen.getByText(/%23 başarısız oranı|23% failure rate/)).toBeInTheDocument()   // 3 / (3+10) = %23
    fireEvent.click(screen.getByRole('button', { name: /^7 gün$|^7 days$/ }))
    await waitFor(() => expect(window.location.search).toContain('u_range=7d'))
    expect(screen.getByText('60')).toBeInTheDocument()   // 7 günlük login sayısı
    await waitFor(() => expect(api.admin.getLoginSeries).toHaveBeenCalled())
  })

  it('oturum tablosu: boşta bandı, son sayfa etiketi, kendi oturumu sonlandırılamaz, sıralama düğmesi aria-sort taşır', () => {
    renderPanel()
    const table = document.querySelector('.uact-table--sessions')
    expect(within(table).getByText(/Vade Takvimi|Expiry Forecast/)).toBeInTheDocument()        // last_tab → nav etiketi
    expect(table.querySelector('.uact-idle--live')).not.toBeNull()
    expect(table.querySelector('.uact-idle--away')).not.toBeNull()
    const selfRow = table.querySelector('tr.is-self')
    expect(selfRow).not.toBeNull()
    expect(within(selfRow).queryByRole('button', { name: /Sonlandır|Terminate/ })).toBeNull()
    const bobRow = [...table.querySelectorAll('tbody tr')].find((tr) => tr.textContent.includes('bob'))
    expect(within(bobRow).getByRole('button', { name: /Sonlandır|Terminate/ })).toBeInTheDocument()
    const th = table.querySelector('th[aria-sort]')
    expect(th).not.toBeNull()
    fireEvent.click(within(th).getByRole('button'))
    expect(th.getAttribute('aria-sort')).toMatch(/ascending|descending/)
  })

  it('gerekçeli sonlandırma: modal → gerekçe → API gerekçeyle çağrılır, tazeleme tetiklenir', async () => {
    const { onRefresh } = renderPanel()
    const table = document.querySelector('.uact-table--sessions')
    const bobRow = [...table.querySelectorAll('tbody tr')].find((tr) => tr.textContent.includes('bob'))
    fireEvent.click(within(bobRow).getByRole('button', { name: /Sonlandır|Terminate/ }))
    const dlg = await screen.findByRole('dialog')
    fireEvent.change(within(dlg).getByLabelText(/Gerekçe|Reason/), { target: { value: 'stale VPN' } })
    fireEvent.click(within(dlg).getByRole('button', { name: /^Sonlandır$|^Terminate$/ }))
    await waitFor(() => expect(api.admin.terminateUserSession).toHaveBeenCalledWith('bob', 'stale VPN'))
    await waitFor(() => expect(onRefresh).toHaveBeenCalled())
  })

  it('sayfa kullanımı: sayfalar, pay çubuğu, hiç açılmayan sekme çipleri; takım süzgeci listeleri daraltır ve URL u_team taşır', async () => {
    renderPanel()
    expect(screen.getByText('90%')).toBeInTheDocument()
    expect(document.querySelectorAll('.uact-chip').length).toBeGreaterThan(10)   // 35 sekme − 2 kullanılan
    // takım süzgeci: SearchableSelect (mousedown ile açılır)
    const trig = document.querySelectorAll('.uact-filters .ss-trigger')[0]
    fireEvent.mouseDown(trig)
    await waitFor(() => expect(document.querySelector('.ss-option')).not.toBeNull())
    fireEvent.mouseDown([...document.querySelectorAll('.ss-option')].find((el) => el.textContent === 'Takım B'))
    await waitFor(() => expect(window.location.search).toContain('u_team=9'))
    const table = document.querySelector('.uact-table--sessions')
    expect(within(table).queryByText('Yönetici')).toBeNull()
    expect(table.textContent).toContain('bob')
    fireEvent.click(screen.getByRole('button', { name: /Süzgeçleri temizle|Clear filters/ }))
    await waitFor(() => expect(window.location.search).not.toContain('u_team'))
  })

  it('giriş durumu pilleri (aktif/30+ gün/hiç girmemiş), takım "hiç girmedi" rozeti, kaynak "yeni" + ilk görülme', () => {
    renderPanel()
    expect(document.querySelector('.uact-st--active')).not.toBeNull()
    expect(document.querySelector('.uact-st--dormant')).not.toBeNull()
    expect(document.querySelector('.uact-st--never')).not.toBeNull()
    expect(screen.getByText(/1 hiç girmedi|1 never signed in/)).toBeInTheDocument()
    expect(screen.getByText(/^yeni$|^new$/)).toBeInTheDocument()
    expect(screen.getByText(/3 gün önce|3 d ago/)).toBeInTheDocument()
  })

  it('anomali: sözlük açılır, onaysız satır onaylanır (not ile), onaylı satır onayı kaldırılabilir', async () => {
    const { onRefresh } = renderPanel()
    fireEvent.click(screen.getByRole('button', { name: /Bayrak sözlüğü|Flag glossary/ }))
    expect(document.querySelector('.uact-glossary')).not.toBeNull()
    fireEvent.click(screen.getByRole('button', { name: /^Onayla$|^Acknowledge$/ }))
    const dlg = await screen.findByRole('dialog')
    fireEvent.change(within(dlg).getByLabelText(/Not|Note/), { target: { value: 'inceledim' } })
    fireEvent.click(within(dlg).getByRole('button', { name: /Onayla|Acknowledge/ }))
    await waitFor(() => expect(api.admin.ackAnomaly).toHaveBeenCalledWith(7, true, 'inceledim'))
    await waitFor(() => expect(onRefresh).toHaveBeenCalled())
    fireEvent.click(screen.getByRole('button', { name: /Onayı kaldır|^Clear$/ }))
    await waitFor(() => expect(api.admin.ackAnomaly).toHaveBeenCalledWith(8, false, undefined))
  })

  it('detay modalı: zaman çizelgesi API\'den gelir, sicil global admin değilse maskelenir, UA açılınca görünür', async () => {
    renderPanel()
    const table = document.querySelector('.uact-table--sessions')
    const bobRow = [...table.querySelectorAll('tbody tr')].find((tr) => tr.textContent.includes('bob'))
    fireEvent.click(within(bobRow).getByRole('button', { name: /Detay|Details/ }))
    const dlg = await screen.findByRole('dialog')
    await waitFor(() => expect(api.admin.getUserTimeline).toHaveBeenCalledWith('bob', 20))
    expect(await within(dlg).findByText(/2 giriş · 1 başarısız · 2 farklı IP|2 sign-ins · 1 failed · 2 distinct IPs/)).toBeInTheDocument()
    expect(within(dlg).getByText(/gizli|hidden/)).toBeInTheDocument()          // employee_id maskeli
    expect(within(dlg).queryByText('Firefox/1')).toBeNull()
    fireEvent.click(within(dlg).getByRole('button', { name: /User-Agent/ }))
    expect(within(dlg).getByText('Firefox/1')).toBeInTheDocument()
    expect(document.querySelectorAll('.uact-tl').length).toBe(2)
  })

  it('global admin sicil numarasını görür; dışa aktarma menüsü ve bağlantı kopyalama çökmeden çalışır', async () => {
    Object.assign(navigator, { clipboard: { writeText: vi.fn().mockResolvedValue() } })
    renderPanel({ globalAdmin: true })
    fireEvent.click(screen.getAllByRole('button', { name: /Detay|Details/ })[0])
    const dlg = await screen.findByRole('dialog')
    expect(within(dlg).getByText('E-1')).toBeInTheDocument()
    // Altlıktaki kapat düğmesi: başlıktaki X de artık adlı (i18n "Kapat/Close") — altlığa daraltılır.
    fireEvent.click(within(dlg.querySelector('[data-slot="dialog-footer"]')).getByRole('button', { name: /Kapat|Close|Dismiss/i }))
    fireEvent.click(screen.getByRole('button', { name: /Dışa aktar|Export/ }))
    fireEvent.click(screen.getByRole('button', { name: /Oturumlar \(CSV\)|Sessions \(CSV\)/ }))
    fireEvent.click(screen.getByRole('button', { name: /Bağlantıyı kopyala|Copy link/ }))
    await waitFor(() => expect(navigator.clipboard.writeText).toHaveBeenCalled())
  })

  it('hata durumunda StatusBlock + tekrar dene; veri yokken yer tutucu', () => {
    const onRefresh = vi.fn()
    const { unmount } = render(<UserActivityPanel data={null} error onRefresh={onRefresh} />)
    fireEvent.click(screen.getByRole('button', { name: /Yenile|Refresh/ }))
    expect(onRefresh).toHaveBeenCalled()
    unmount()
    render(<UserActivityPanel data={null} error={false} />)
    expect(screen.getByText('…')).toBeInTheDocument()
  })
})
