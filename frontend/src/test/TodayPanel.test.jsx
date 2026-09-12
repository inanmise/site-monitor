import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent } from './test-utils.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => String(s ?? ''),
  api: withApiFallback({ me: { today: vi.fn() }, admin: { getTeams: vi.fn().mockResolvedValue({ success: true, data: [] }) } }),
}))
import { api } from '../api/client'
import TodayPanel from '../components/TodayPanel.jsx'

/** "Sizin için — bugün" (2026-09-12, #3): dört kart, bağlantılar doğru sekmeye; hepsi sıfırsa yeşil tek satır. */
describe('TodayPanel', () => {
  beforeEach(() => { vi.clearAllMocks(); try { localStorage.clear() } catch { /* yok */ } })

  it('dört kart: sayılar, 30 gün altı alan tıklanınca onOpenDomain; "Tümünü gör" sm:navigate ile doğru sekmeye', async () => {
    api.me.today.mockResolvedValue({ success: true, data: {
      certs: { count: 2, expired: 1, items: [{ domain: 'exp.example.com', days: -3, team_id: 1, team_name: 'Takım A' }, { domain: 'soon.example.com', days: 12 }] },
      alerts: { count: 1, critical: 1, items: [{ id: 9, domain: 'down.example.com', type: 'HTTP_DOWN', level: 'CRITICAL', acknowledged: false }] },
      exceptions: { count: 1, expired: 0, items: [{ domain: 'old.example.com', until: '2026-09-20', days: 8 }] },
      weekly: { year: 2026, week: 37, count: 1, missing: 1, items: [{ team_id: 1, team_name: 'Takım A', status: 'DRAFT' }] },
    } })
    const onOpen = vi.fn(); const nav = vi.fn()
    window.addEventListener('sm:navigate', nav)
    render(<TodayPanel onOpenDomain={onOpen} />)
    await screen.findByText(/5 konu ilgi bekliyor|5 items need attention/)
    // Varsayılan KAPALI: özet satırı görünür, kartlar açılınca gelir
    expect(document.querySelector('.today-grid')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: /Sizin için|For you/ }))
    expect(screen.getByText(/1 tanesi DOLMUŞ|1 already EXPIRED/)).toBeInTheDocument()
    expect(screen.getByText(/1 tanesi KRİTİK|1 CRITICAL/)).toBeInTheDocument()
    expect(screen.getByText(/^taslak$|^draft$/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'exp.example.com' }))
    expect(onOpen).toHaveBeenCalledWith('exp.example.com')
    const goButtons = screen.getAllByRole('button', { name: /Tümünü gör|See all/ })
    fireEvent.click(goButtons[0])
    expect(nav.mock.calls[0][0].detail.tab).toBe('renewal')
    fireEvent.click(goButtons[3])
    expect(nav.mock.calls[1][0].detail.tab).toBe('weeklyreports')
    window.removeEventListener('sm:navigate', nav)
  })

  it('hepsi sıfır → yeşil "ilgilenilecek bir şey yok", kart yok; başlık katlanır ve tercih saklanır', async () => {
    api.me.today.mockResolvedValue({ success: true, data: {
      certs: { count: 0, items: [] }, alerts: { count: 0, items: [] }, exceptions: { count: 0, items: [] }, weekly: { count: 1, missing: 0, week: 37, items: [] },
    } })
    render(<TodayPanel />)
    await screen.findByText(/Bugün ilgilenilecek bir şey yok|Nothing needs attention today/)
    expect(document.querySelector('.today-grid')).toBeNull()
    const head = screen.getByRole('button', { name: /Sizin için|For you/ })
    expect(head).toHaveAttribute('aria-expanded', 'false')   // varsayılan kapalı
    fireEvent.click(head)
    expect(head).toHaveAttribute('aria-expanded', 'true')
    expect(localStorage.getItem('today-panel-open')).toBe('true')
  })

  it('uç başarısız → panel çizilmez', async () => {
    api.me.today.mockResolvedValue({ success: false })
    const { container } = render(<TodayPanel />)
    await new Promise((r) => setTimeout(r, 10))
    expect(container.querySelector('.today')).toBeNull()
  })
})
