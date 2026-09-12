import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent } from './test-utils.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({ api: withApiFallback({ getExecutiveStats: vi.fn(), admin: { getTeams: vi.fn().mockResolvedValue({ success: true, data: [] }) } }) }))
import { api } from '../api/client'
import ExecutiveSummary from '../components/ExecutiveSummary.jsx'

/** Yönetici özeti (2026-09-12, #20): dört KPI, delta, takım çubukları en kötü üstte, tıklama süzer. */
describe('ExecutiveSummary', () => {
  beforeEach(() => vi.clearAllMocks())

  it('KPI değerleri + delta + takım karşılaştırması; takım satırı onOpenTeam; KPI kartı sm:navigate', async () => {
    api.getExecutiveStats.mockResolvedValue({ success: true, data: {
      certs: { total: 120, ok: 108, under30: 9, under7: 2, expired: 1, error: 2, health_pct: 90.0 },
      alerts: { open: 4, critical: 1, opened_last30: 12, opened_prev30: 20, delta: -8 },
      sla: { target_pct: 99.9, monitors: 40, breaches: 3, worst: [] },
      teams: [{ team_id: 2, team_name: 'Takım B', total: 20, ok: 15, under30: 3, expired: 1, error: 1, open_alerts: 2, health_pct: 75.0 },
              { team_id: 1, team_name: 'Takım A', total: 100, ok: 93, under30: 6, expired: 0, error: 1, open_alerts: 2, health_pct: 93.0 }],
      window_days: 30,
    } })
    const onTeam = vi.fn(); const nav = vi.fn()
    window.addEventListener('sm:navigate', nav)
    render(<ExecutiveSummary onOpenTeam={onTeam} />)
    await screen.findByText('%90')
    expect(screen.getByText(/-8 önceki 30 güne göre|-8 vs previous 30 days/)).toBeInTheDocument()
    expect(screen.getByText(/SLA ihlali \(hedef %99\.9\)|SLA breaches \(target 99\.9%\)/)).toBeInTheDocument()
    const rows = document.querySelectorAll('.exs-team-row')
    expect(rows.length).toBe(2)
    expect(rows[0].textContent).toContain('Takım B')   // en kötü üstte
    fireEvent.click(rows[0])
    expect(onTeam).toHaveBeenCalledWith(2)
    fireEvent.click(screen.getByRole('button', { name: /30 gün altı|Under 30 days/ }))
    expect(nav.mock.calls[0][0].detail.tab).toBe('renewal')
    window.removeEventListener('sm:navigate', nav)
  })

  it('tek takım → karşılaştırma bloğu çizilmez; uç başarısız → özet yok', async () => {
    api.getExecutiveStats.mockResolvedValue({ success: true, data: { certs: { total: 1, ok: 1, health_pct: 100 }, alerts: {}, sla: {}, teams: [{ team_id: 1, team_name: 'A', total: 1, health_pct: 100 }] } })
    const { container, unmount } = render(<ExecutiveSummary />)
    await screen.findByText('%100')
    expect(container.querySelector('.exs-teams')).toBeNull()
    unmount()
    api.getExecutiveStats.mockResolvedValue({ success: false })
    const { container: c2 } = render(<ExecutiveSummary />)
    await new Promise((r) => setTimeout(r, 10))
    expect(c2.querySelector('.exs')).toBeNull()
  })
})
