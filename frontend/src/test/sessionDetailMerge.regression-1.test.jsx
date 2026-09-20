import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, within } from './test-utils.jsx'
import UserActivityPanel from '../components/admin/useractivity/UserActivityPanel.jsx'

// Regression: ISSUE-002 — çevrimiçi kullanıcının oturum detayı yalnız active_users kaydını alıyordu; dizin alanları
// (oluşturulma, tur durumu, kilit, ek takımlar, user_id) kayboluyor, "Tur: hiç görmedi" (dizin: tamamladı) görünüyor,
// "Tam kullanıcı kartı" / "Turu sıfırla" düğmeleri çizilmiyordu.
// Found by /qa on 2026-09-20
// Report: .gstack/qa-reports/qa-report-localhost-2026-09-20.md
const { apiMock } = vi.hoisted(() => ({
  apiMock: { admin: { getLoginSeries: vi.fn(), getUserTimeline: vi.fn(), ackAnomaly: vi.fn(), terminateUserSession: vi.fn(), resetUserTour: vi.fn(), unlockUser: vi.fn() } },
}))
vi.mock('../api/client', () => ({ api: apiMock, formatDate: (s) => s ?? '', formatDateSec: (s) => s ?? '', formatDateOnly: (s) => (s ?? '').slice(0, 10) }))
vi.mock('../components/admin/LoginActivityChart.jsx', () => ({ default: () => <div data-testid="login-chart" /> }))
import { api } from '../api/client'

const ago = (sec) => new Date(Date.now() - sec * 1000).toISOString().slice(0, 19)
const DATA = {
  generated_at: '2026-09-20T00:30:00', office_hours: { start: 8, end: 20 },
  summary: { active_count: 1, logins_24h: 1, failed_24h: 0, anomalies_24h: 0, unique_users_24h: 1, total_users: 1, dormant_30d: 0, never_logged_in: 0, tour: { completed: 1, dismissed: 0, none: 0 } },
  // Oturum kaydı: dizin alanları YOK (backend buildActiveUsers böyle)
  active_users: [{ username: 'admin', display_name: 'Yonetici', system_role: 'ADMIN', team_id: 5, team_name: 'Takim A', login_at: ago(3600), last_seen: ago(20), idle_sec: 20, expires_in_sec: 3580, ip: '10.0.0.1', last_login_at: ago(3600) }],
  // Dizin kaydı: oluşturulma + tur + user_id + ek takımlar
  login_status: [{ username: 'admin', user_id: 1, display_name: 'Yonetici', system_role: 'ADMIN', team_id: 5, team_name: 'Takim A', team_ids: [5, 9], auth_source: 'LOCAL', active: true, created_at: '2025-12-01T10:00:00', last_login_at: ago(3600), tour_status: 'completed', tour_at: '2026-02-01T09:00:00' }],
  series: { day: [] }, top_sources: [], anomalies: { total: 0, unacked_recent: 0, counts: {}, recent: [] },
  role_team: { by_role: [], by_team: [{ team_id: 5, team_name: 'Takim A', count: 1, users: [] }] },
  heatmaps: [], usage: { days: 7, pages: [], users: [], teams: [] },
  details: { logins: [], failed: [], anomalies: [], unique_users: [], dormant: [] },
}

describe('ISSUE-002 — oturum detayı dizin + oturum kaydını birleştirir', () => {
  beforeEach(() => {
    vi.clearAllMocks(); window.history.replaceState({}, '', '/?tab=health')
    api.admin.getLoginSeries.mockResolvedValue({ success: true, data: { buckets: [], granularity: 'day' } })
    api.admin.getUserTimeline.mockResolvedValue({ success: true, data: { logins: 1, failed: 0, distinct_ips: 1, events: [] } })
  })

  it('çevrimiçi kullanıcının Detay'ı: oluşturulma, tur "tamamladı", oturum alanları (boşta), Tam kullanıcı kartı + Turu sıfırla düğmeleri', async () => {
    render(<UserActivityPanel data={DATA} error={false} refreshing={false} onRefresh={vi.fn()} isAdmin globalAdmin username="other" />)
    // 04 Aktif kullanıcılar tablosundaki Detay (yalnız active_users kaydından açılır)
    const row = document.querySelector('.uact-table--sessions tbody tr')
    fireEvent.click(within(row).getByRole('button', { name: /Detay|Detail/ }))
    const dlg = await screen.findByRole('dialog')
    expect(within(dlg).getByText('2025-12-01T10:00:00')).toBeInTheDocument()           // dizinden
    expect(within(dlg).getByText(/tamamladı|completed/)).toBeInTheDocument()            // dizinden (eskiden "hiç görmedi")
    expect(within(dlg).getByText(/^Oturum$|^Session$/)).toBeInTheDocument()             // oturum bölümü (canlı kayıt)
    expect(within(dlg).getByRole('button', { name: /Tam kullanıcı kartı|Full user card/ })).toBeInTheDocument()
    expect(within(dlg).getByRole('button', { name: /Turu sıfırla|Reset the tour/ })).toBeInTheDocument()
    expect(within(dlg).getByRole('button', { name: /^Sonlandır$|^Terminate$/ })).toBeInTheDocument()   // canlı oturum → sonlandırma
  })
})
