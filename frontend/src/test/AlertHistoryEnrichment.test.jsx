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
    try { sessionStorage.removeItem('alh-teamstats-open') } catch { /* yoksay */ }
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

  // Hücre pop-up'ı (2026-09-18): sayıya tıkla → o takım+kova alarmları SAYFALI (sunucu sayfalama)
  it('hücre sayısına tıklayınca takım+kova sorgusuyla sayfalı liste modali açılır; takımsız satır ve 0 tıklanmaz; sayfa 2 yeni istek atar', async () => {
    const mk = (i) => ({ id: i, alert_type: 'HTTP_DOWN', alert_level: 'CRITICAL', domain: `d${i}.example.com`, created_at: '2026-09-10T10:00:00', resolved: false, message: 'm' })
    api.admin.getAlerts.mockImplementation(({ page }) => Promise.resolve({ success: true, total: 39, data: Array.from({ length: Math.min(25, 39 - page * 25) }, (_, k) => mk(page * 25 + k + 1)) }))
    render(<LangProvider><AlertTeamStatsPanel /></LangProvider>)
    fireEvent.click(screen.getByRole('button', { name: /Takım kırılımı|Breakdown by team/ }))
    await screen.findByText('Takım A')
    const rows = document.querySelectorAll('.alh-ts-row')
    expect(rows[0].querySelectorAll('.alh-ts-num')).toHaveLength(4)   // açık/kapandı/7/30 hepsi > 0
    expect(rows[1].querySelectorAll('.alh-ts-num')).toHaveLength(0)   // takımsız → düz sayı

    fireEvent.click([...rows[0].querySelectorAll('.alh-ts-num')].find((b) => b.textContent.trim() === '39'))   // son 30 gün
    await waitFor(() => expect(api.admin.getAlerts).toHaveBeenCalled())
    const q = api.admin.getAlerts.mock.calls[0][0]
    expect(q).toMatchObject({ teamId: 5, page: 0, size: 25 })
    expect(q.since).toBeTruthy(); expect(q.resolved).toBeUndefined()
    const modal = await screen.findByRole('dialog')
    expect(modal.textContent).toMatch(/Takım A/)
    await waitFor(() => expect(modal.querySelectorAll('.alh-cell-row')).toHaveLength(25))
    expect(modal.querySelector('.pg-nav')).not.toBeNull()
    fireEvent.click([...modal.querySelectorAll('button')].find((b) => /Sonraki|Next/i.test(b.getAttribute('aria-label') || '')))
    await waitFor(() => expect(api.admin.getAlerts).toHaveBeenLastCalledWith(expect.objectContaining({ page: 1 })))
    await waitFor(() => expect(modal.querySelectorAll('.alh-cell-row')).toHaveLength(14))   // 39 - 25
  })

  // Regression: ISSUE-001 — TeamBadge varsayılan <button> çiziyor; satır düğmesinin İÇİNDE
  // kullanılınca React her satır için validateDOMNesting uyarısı basıyordu.
  // Found by /qa on 2026-09-16
  // Report: .gstack/qa-reports/qa-report-localhost-2026-09-16.md
  it('ISSUE-001: takım rozeti satır düğmesinin içinde <button> DEĞİL (iç içe düğüm uyarısı)', async () => {
    render(<LangProvider><AlertTeamStatsPanel onPickTeam={() => {}} /></LangProvider>)
    fireEvent.click(screen.getByRole('button', { name: /Takım kırılımı|Breakdown by team/ }))
    await screen.findByText('Takım A')
    const link = document.querySelector('.alh-ts-link')
    expect(link.tagName).toBe('BUTTON')
    expect(link.querySelector('button')).toBeNull()          // rozet span olmalı
    expect(link.querySelector('[role="button"]')).not.toBeNull()   // erişilebilirliği korur
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
