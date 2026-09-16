import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'

/**
 * Alarm Geçmişi zenginleştirmesi (2026-09-16, kullanıcı isteği):
 *  - kartta takım + imza geçmişi (kaçıncı alarm, önceki oluşum, ne zamandır sessiz) + "Geçmişini gör",
 *  - takım kırılımı paneli (açık / kapalı / son 7 / son 30, satıra tıklayınca süzme).
 */
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => String(s ?? ''),
  api: withApiFallback({
    admin: {
      getAlerts: vi.fn(),
      getAlertTeamStats: vi.fn(),
      getTeams: vi.fn().mockResolvedValue({ success: true, data: [{ id: 5, name: 'Takım A' }] }),
      getAlertsCsvUrl: vi.fn(() => '/api/admin/alerts/export'),
    },
  }),
}))
import { api } from '../api/client'
import AlertHistory from '../components/admin/AlertHistory.jsx'
import AlertTeamStatsPanel from '../components/admin/alerts/AlertTeamStatsPanel.jsx'
import AlertSignatureStrip, { daysSince } from '../components/admin/alerts/AlertSignatureStrip.jsx'
import { LangProvider } from '../i18n/index.jsx'

const DAY = 86_400_000
const iso = (ms) => new Date(ms).toISOString().slice(0, 19)

const openAlert = {
  id: 87, alert_type: 'PING_DOWN', alert_level: 'CRITICAL', domain: 'ping.example.com', resolved: false,
  created_at: iso(Date.now() - 2 * DAY), message: 'PING_DOWN · ping.example.com', team_id: 5,
  history_count: 4, history_prev_at: iso(Date.now() - 9 * DAY), history_last_at: iso(Date.now() - 2 * DAY),
}

describe('AlertSignatureStrip — imza geçmişi', () => {
  const wrap = (props) => render(<LangProvider><AlertSignatureStrip {...props} /></LangProvider>)

  it('açık alarm: takım, toplam sayı, önceki oluşum + aradaki gün; sessizlik çipi YOK (alarm sürüyor)', () => {
    wrap({ alert: openAlert, teamName: 'Takım A', onShowHistory: () => {} })
    expect(screen.getByText(/Takım A/)).toBeInTheDocument()
    expect(screen.getByText(/geçmişte 4 alarm|4 alerts in total/)).toBeInTheDocument()
    expect(screen.getByText(/arada 7 gün|7 days apart/)).toBeInTheDocument()
    expect(screen.queryByText(/gündür sessiz|quiet for/)).toBeNull()
    expect(screen.getByRole('button', { name: /Geçmişini gör|See its history/ })).toBeInTheDocument()
  })

  it('kapalı alarm: kapanışından beri geçen süre "sessiz" çipinde; aynı gün boşluğu "aynı gün" yazar', () => {
    wrap({ alert: { ...openAlert, resolved: true, resolved_at: iso(Date.now() - 3 * DAY), history_prev_at: iso(Date.now() - 2 * DAY) } })
    expect(screen.getByText(/3 gündür sessiz|quiet for 3 days/)).toBeInTheDocument()
    expect(screen.getByText(/aynı gün|same day/)).toBeInTheDocument()
  })

  it('tek seferlik alarm: "İlk kez" yazar, geçmiş düğmesi çıkmaz', () => {
    wrap({ alert: { ...openAlert, history_count: 1, history_prev_at: null } })
    expect(screen.getByText(/İlk kez|First time/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Geçmişini gör|See its history/ })).toBeNull()
  })

  it('daysSince: bozuk damga null, gelecek tarih 0', () => {
    expect(daysSince(null)).toBeNull()
    expect(daysSince('bozuk')).toBeNull()
    expect(daysSince(iso(Date.now() + DAY))).toBe(0)
    expect(daysSince(iso(Date.now() - 3 * DAY))).toBe(3)
  })
})

describe('AlertTeamStatsPanel — takım kırılımı', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    try { localStorage.removeItem('alh-teamstats-open') } catch { /* yoksay */ }
    api.admin.getAlertTeamStats.mockResolvedValue({ success: true, data: {
      window_days: 30, total_open: 4, total_closed: 37, total_last7: 3, total_last30: 39,
      teams: [
        { team_id: 5, team_name: 'Takım A', open: 4, closed: 37, last7: 3, last30: 39 },
        { team_id: null, team_name: null, open: 0, closed: 1, last7: 0, last30: 1 },
      ],
    } })
  })

  it('varsayılan KAPALI; açınca satırlar + özet gelir, takıma tıklamak onPickTeam çağırır', async () => {
    const onPick = vi.fn()
    render(<LangProvider><AlertTeamStatsPanel onPickTeam={onPick} /></LangProvider>)
    const head = screen.getByRole('button', { name: /Takım kırılımı|Breakdown by team/ })
    expect(head).toHaveAttribute('aria-expanded', 'false')
    expect(api.admin.getAlertTeamStats).not.toHaveBeenCalled()   // kapalıyken istek atılmaz

    fireEvent.click(head)
    await waitFor(() => expect(api.admin.getAlertTeamStats).toHaveBeenCalled())
    await screen.findByText('Takım A')
    expect(screen.getByText(/4 açık · son 7 günde 3|4 open · 3 in the last 7 days/)).toBeInTheDocument()
    expect(screen.getByText(/Takımı çözülemeyen|No team resolved/)).toBeInTheDocument()
    fireEvent.click(document.querySelector('.alh-ts-link'))
    expect(onPick).toHaveBeenCalledWith('5')
  })
})

describe('AlertHistory — "Geçmişini gör" listeyi o imzaya süzer', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.admin.getAlerts.mockResolvedValue({ success: true, data: [openAlert], total: 1, page: 0, size: 50 })
  })

  it('kapalı sekmeye geçer, alan adı + tip süzgecini sunucuya gönderir', async () => {
    render(<AlertHistory urlSync />)
    await waitFor(() => expect(api.admin.getAlerts).toHaveBeenCalled())
    const btn = await screen.findByRole('button', { name: /Geçmişini gör|See its history/ })
    fireEvent.click(btn)
    await waitFor(() => {
      const last = api.admin.getAlerts.mock.calls.at(-1)[0]
      expect(last).toMatchObject({ resolved: 'true', alertType: 'PING_DOWN', q: 'ping.example.com' })
    })
  })
})
