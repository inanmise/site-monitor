import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent } from './test-utils.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({ api: withApiFallback({ weeklyReports: { completion: vi.fn() }, admin: { getTeams: vi.fn().mockResolvedValue({ success: true, data: [] }) } }) }))
import { api } from '../api/client'
import WeeklyCompletionBoard from '../components/WeeklyCompletionBoard.jsx'

/** Takım tamamlama panosu (2026-09-12, #21): takım × hafta ızgara, hücre tıklaması, boş veri → pano yok. */
describe('WeeklyCompletionBoard', () => {
  beforeEach(() => vi.clearAllMocks())

  it('takımlar × haftalar; eksik toplamı; hücre tıklaması report_id varsa onu, yoksa takım+hafta verir', async () => {
    api.weeklyReports.completion.mockResolvedValue({ success: true, data: {
      year: 2026, weeks: 3, current_week: 3, total_missing: 2,
      teams: [
        { team_id: 2, team_name: 'Takım B', reminder: true, approved: 1, missing: 2, cells: [{ week: 1, status: 'APPROVED', report_id: 11 }, { week: 2, status: 'MISSING', report_id: null }, { week: 3, status: 'DRAFT', report_id: 12 }] },
        { team_id: 1, team_name: 'Takım A', reminder: false, approved: 3, missing: 0, cells: [{ week: 1, status: 'APPROVED', report_id: 1 }, { week: 2, status: 'APPROVED', report_id: 2 }, { week: 3, status: 'PENDING_APPROVAL', report_id: 3 }] },
      ],
    } })
    const onPick = vi.fn()
    render(<WeeklyCompletionBoard year={2026} onPick={onPick} />)
    await screen.findByText(/2 eksik hafta|2 missing weeks/)
    // Varsayılan KAPALI (2026-09-13) — başlık tıklanınca açılır
    expect(document.querySelectorAll('[data-slot="wrc-cell"]').length).toBe(0)
    fireEvent.click(document.querySelector('[data-slot="collapsible-trigger"]'))
    expect(document.querySelectorAll('[data-slot="wrc-cell"]').length).toBe(6)
    expect(document.querySelectorAll('[data-slot="wrc-cell"][data-status="MISSING"]').length).toBe(1)
    expect(document.querySelector('[data-slot="wrc-noremind"]')).not.toBeNull()   // Takım A hatırlatması kapalı
    fireEvent.click(screen.getByRole('button', { name: /Takım B 2\. hafta girilmedi|Takım B week 2 not entered/ }))
    expect(onPick).toHaveBeenCalledWith(2, 2, null)
    fireEvent.click(screen.getByRole('button', { name: /Takım B 3\. hafta taslak|Takım B week 3 draft/ }))
    expect(onPick).toHaveBeenCalledWith(2, 3, 12)
  })

  it('telefonda (390 px) takım kartları: son haftalar 40 px kutular, hücre tıklaması aynı onPick; data prop verilirse kendisi çekmez', async () => {
    const data = {
      year: 2026, weeks: 52, current_week: 3, total_missing: 1,
      teams: [{ team_id: 2, team_name: 'Takım B', reminder: true, approved: 1, missing: 1, cells: [{ week: 1, status: 'APPROVED', report_id: 11 }, { week: 2, status: 'MISSING', report_id: null }, { week: 3, status: 'DRAFT', report_id: 12 }] }],
    }
    const w = window.innerWidth
    window.innerWidth = 390
    try {
      try { localStorage.setItem('wr-completion-open', 'true') } catch { /* yoksay */ }
      const onPick = vi.fn()
      render(<WeeklyCompletionBoard year={2026} data={data} onPick={onPick} />)
      expect(api.weeklyReports.completion).not.toHaveBeenCalled()
      const card = document.querySelector('[data-slot="wrc-team-card"]')
      expect(card).not.toBeNull()
      // Gelecek haftalar (4–52) kartta yok: yalnız güncel haftaya kadar son haftalar
      expect(card.querySelectorAll('[data-slot="wrc-cell"]').length).toBe(3)
      expect(document.querySelector('table')).toBeNull()
      fireEvent.click(screen.getByRole('button', { name: /Takım B 2\. hafta girilmedi|Takım B week 2 not entered/ }))
      expect(onPick).toHaveBeenCalledWith(2, 2, null)
    } finally {
      window.innerWidth = w
      try { localStorage.removeItem('wr-completion-open') } catch { /* yoksay */ }
    }
  })

  it('geniş kapta ısı haritası: gelecek haftalar tıklanmaz yer tutucu, güncel hafta işaretli', async () => {
    const data = { year: 2026, weeks: 5, current_week: 3, total_missing: 0,
      teams: [{ team_id: 1, team_name: 'Takım A', reminder: true, approved: 3, missing: 0, cells: [1, 2, 3].map((wk) => ({ week: wk, status: 'APPROVED', report_id: wk })) }] }
    try { localStorage.setItem('wr-completion-open', 'true') } catch { /* yoksay */ }
    render(<WeeklyCompletionBoard year={2026} data={data} />)
    expect(document.querySelectorAll('[data-slot="wrc-cell"]').length).toBe(3)
    expect(document.querySelectorAll('[data-slot="wrc-cell"][data-current]').length).toBe(1)
    expect(screen.getByText(/Her hafta girildi|Every week is in/)).toBeInTheDocument()
    try { localStorage.removeItem('wr-completion-open') } catch { /* yoksay */ }
  })

  it('takım listesi boş (takım kullanıcısı) → pano çizilmez', async () => {
    api.weeklyReports.completion.mockResolvedValue({ success: true, data: { year: 2026, weeks: 10, teams: [] } })
    const { container } = render(<WeeklyCompletionBoard year={2026} />)
    await new Promise((r) => setTimeout(r, 10))
    expect(container.querySelector('[data-slot="wrc"]')).toBeNull()
  })
})
