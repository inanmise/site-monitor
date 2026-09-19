import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => String(s ?? ''),
  api: withApiFallback({ me: { today: vi.fn() }, admin: { getTeams: vi.fn().mockResolvedValue({ success: true, data: [] }) } }),
}))
import { api } from '../api/client'
import TodayPanel from '../components/TodayPanel.jsx'

const EMPTY_MON = { flapping: { count: 0, items: [] }, slow: { count: 0, items: [] }, stale: { count: 0, paused: 0, items: [] }, domains: { count: 0, expired: 0, items: [] } }

/** "Sizin için — bugün" (2026-09-12, #3): kartlar, bağlantılar doğru sekmeye; hepsi sıfırsa yeşil tek satır.
 *  2026-09-19: istisna kartı kalktı; dört izleme kartı (kararsız / yavaşlayan / sessiz / alan adı kaydı) geldi. */
describe('TodayPanel', () => {
  beforeEach(() => { vi.clearAllMocks(); try { localStorage.clear() } catch { /* yok */ } })

  it('kartlar: sayılar, 30 gün altı alan tıklanınca onOpenDomain; "Tümünü gör" sm:navigate ile doğru sekmeye', async () => {
    api.me.today.mockResolvedValue({ success: true, data: {
      certs: { count: 2, expired: 1, items: [{ domain: 'exp.example.com', days: -3, team_id: 1, team_name: 'Takım A' }, { domain: 'soon.example.com', days: 12 }] },
      alerts: { count: 1, critical: 1, items: [{ id: 9, domain: 'down.example.com', type: 'HTTP_DOWN', level: 'CRITICAL', acknowledged: false }] },
      ...EMPTY_MON,
      weekly: { year: 2026, week: 37, count: 1, missing: 1, items: [{ team_id: 1, team_name: 'Takım A', status: 'DRAFT' }] },
    } })
    const onOpen = vi.fn(); const nav = vi.fn()
    window.addEventListener('sm:navigate', nav)
    render(<TodayPanel onOpenDomain={onOpen} />)
    await screen.findByText(/4 konu ilgi bekliyor|4 items need attention/)
    // Varsayılan KAPALI: özet satırı görünür, kartlar açılınca gelir
    expect(document.querySelector('.today-grid')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: /Sizin için|For you/ }))
    expect(screen.getByText(/1 tanesi DOLMUŞ|1 already EXPIRED/)).toBeInTheDocument()
    expect(screen.getByText(/1 tanesi KRİTİK|1 CRITICAL/)).toBeInTheDocument()
    expect(screen.getByText(/^taslak$|^draft$/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'exp.example.com' }))
    expect(onOpen).toHaveBeenCalledWith('exp.example.com')
    // "Tümünü gör" artık POP-UP açar (2026-09-18): tam liste full=true ile çekilir; "Sayfaya git" eski geçişi yapar
    const goButtons = screen.getAllByRole('button', { name: /Tümünü gör|See all/ })
    fireEvent.click(goButtons[0])
    const modal = await screen.findByRole('dialog')
    await waitFor(() => expect(api.me.today).toHaveBeenLastCalledWith({ full: true }))
    expect(modal.textContent).toMatch(/\(2\)/)                       // başlıkta sayı
    expect(modal.querySelectorAll('.today-modal-row')).toHaveLength(2)
    expect(nav).not.toHaveBeenCalled()
    fireEvent.click(within(modal).getByRole('button', { name: /Sayfaya git|Open the page/ }))
    expect(nav.mock.calls[0][0].detail.tab).toBe('renewal')
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    // Card satır içi bileşen → her render'da yeniden kurulur; eski düğme referansı kopuk, yeniden sorgula
    fireEvent.click(screen.getAllByRole('button', { name: /Tümünü gör|See all/ })[2])   // certs, alerts, weekly (izleme kartları 0 → düğme yok)
    const m2 = await screen.findByRole('dialog')
    fireEvent.click(within(m2).getByRole('button', { name: /Sayfaya git|Open the page/ }))
    expect(nav.mock.calls[1][0].detail.tab).toBe('weeklyreports')
    window.removeEventListener('sm:navigate', nav)
  })

  it('pop-up SAYFALI: 23 sertifika → 10 satır + sayfalama; arama kutusu daraltır; satıra tıklamak onOpenDomain', async () => {
    const many = Array.from({ length: 23 }, (_, i) => ({ domain: `c${String(i).padStart(2, '0')}.example.com`, days: i, team_id: 1, team_name: 'Takım A' }))
    api.me.today.mockImplementation((opts) => Promise.resolve({ success: true, data: {
      certs: { count: 23, expired: 0, items: opts?.full ? many : many.slice(0, 5) },
      alerts: { count: 0, items: [] }, ...EMPTY_MON, weekly: { count: 0, missing: 0, items: [] },
    } }))
    const onOpen = vi.fn()
    render(<TodayPanel onOpenDomain={onOpen} />)
    await screen.findByText(/23 konu ilgi bekliyor|23 items need attention/)
    fireEvent.click(screen.getByRole('button', { name: /Sizin için|For you/ }))
    expect(document.querySelectorAll('.today-card .today-list li')).toHaveLength(5)   // kartta yalnız 5
    fireEvent.click(screen.getByRole('button', { name: /Tümünü gör|See all/ }))
    const modal = await screen.findByRole('dialog')
    await waitFor(() => expect(modal.querySelectorAll('.today-modal-row')).toHaveLength(10))
    expect(modal.querySelector('.pg-nav')).not.toBeNull()
    expect(modal.textContent).toMatch(/1[–-]10 (\/|of) 23/)
    fireEvent.change(modal.querySelector('input[type=text]'), { target: { value: 'c2' } })
    await waitFor(() => expect(modal.querySelectorAll('.today-modal-row')).toHaveLength(3))   // c20, c21, c22
    fireEvent.click(within(modal).getByRole('button', { name: 'c21.example.com' }))
    expect(onOpen).toHaveBeenCalledWith('c21.example.com')
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
  })

  it('hepsi sıfır → yeşil "ilgilenilecek bir şey yok", kart yok; başlık katlanır ve tercih saklanır', async () => {
    api.me.today.mockResolvedValue({ success: true, data: {
      certs: { count: 0, items: [] }, alerts: { count: 0, items: [] }, ...EMPTY_MON, weekly: { count: 1, missing: 0, week: 37, items: [] },
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

  it('2026-09-19 izleme kartları: kararsız/yavaşlayan/sessiz/alan adı satırları; satır tıklaması izlemenin sekmesine ?monitor=; pop-up aynı satırı çizer; "Sayfaya git" bölüm sekmesine', async () => {
    const data = {
      certs: { count: 0, items: [] }, alerts: { count: 0, items: [] }, weekly: { count: 0, missing: 0, items: [] },
      flapping: { count: 1, items: [{ type: 'HTTP', monitor_id: 11, name: 'api', target: 'https://api.example.com/health', transitions: 4, last_status: 'DOWN', team_id: 1, team_name: 'Takım A' }] },
      slow: { count: 1, items: [{ type: 'PING', monitor_id: 12, name: 'gw', target: '10.0.0.1', today_ms: 300, baseline_ms: 100, ratio: 3 }] },
      stale: { count: 3, paused: 2, items: [
        { type: 'PORT', monitor_id: 13, name: 'smtp', target: 'mail.example.com:25', last_check: '2026-09-19T08:00:00', age_min: 90, expected_min: 10 },
        { type: 'SCRIPTED', monitor_id: 14, name: 'login-flow', target: null, last_check: null, never: true, age_min: 600, expected_min: 10 },
        { type: 'KEYWORD', monitor_id: 16, name: 'kw', target: 'https://kw.example.com', last_check: null, never: false, age_min: 10080, expected_min: 10 }] },
      domains: { count: 1, expired: 1, items: [{ type: 'DOMAIN', monitor_id: 15, name: 'd', domain: 'exp.example.com', days: -2, registrar: 'Registrar X' }] },
    }
    api.me.today.mockResolvedValue({ success: true, data })
    const nav = vi.fn(); window.addEventListener('sm:navigate', nav)
    try { localStorage.setItem('today-panel-open', 'true') } catch { /* yok */ }
    render(<TodayPanel />)
    await screen.findByText(/6 konu ilgi bekliyor|6 items need attention/)
    expect(screen.getByText(/4 geçiş · şu an DOWN|4 changes · currently DOWN/)).toBeInTheDocument()
    expect(screen.getByText(/300 ms · taban 100 ms · ×3|300 ms · baseline 100 ms · ×3/)).toBeInTheDocument()
    expect(screen.getByText(/90 dk önce · beklenen ≤10 dk|90 min ago · expected ≤10 min/)).toBeInTheDocument()
    expect(screen.getByText(/hiç kontrol edilmedi|never checked/)).toBeInTheDocument()
    expect(screen.getByText(/7\+ gündür kontrol yok|no check for 7\+ days/)).toBeInTheDocument()
    expect(screen.getByText(/2 izleme duraklatılmış|2 monitors paused/)).toBeInTheDocument()
    expect(screen.getByText(/1 tanesi DOLMUŞ|1 already EXPIRED/)).toBeInTheDocument()
    expect(screen.getByText('Registrar X')).toBeInTheDocument()
    // satır → izlemenin sekmesi + ?monitor=; Sentetik'te derin bağlantı yok
    fireEvent.click(screen.getByRole('button', { name: 'api' }))
    expect(nav.mock.calls.at(-1)[0].detail).toEqual({ tab: 'http', params: { monitor: 11 } })
    fireEvent.click(screen.getByRole('button', { name: 'login-flow' }))
    expect(nav.mock.calls.at(-1)[0].detail).toEqual({ tab: 'scripted', params: undefined })
    fireEvent.click(screen.getByRole('button', { name: 'exp.example.com' }))
    expect(nav.mock.calls.at(-1)[0].detail).toEqual({ tab: 'domain', params: { monitor: 15 } })
    // pop-up: sessiz kartı → aynı satır bileşeni, "Sayfaya git" → sistem sağlığı
    fireEvent.click(screen.getAllByRole('button', { name: /Tümünü gör|See all/ })[2])
    const modal = await screen.findByRole('dialog')
    await waitFor(() => expect(modal.querySelectorAll('.today-modal-row')).toHaveLength(3))
    expect(within(modal).getByText(/hiç kontrol edilmedi|never checked/)).toBeInTheDocument()
    fireEvent.click(within(modal).getByRole('button', { name: /Sayfaya git|Open the page/ }))
    expect(nav.mock.calls.at(-1)[0].detail.tab).toBe('health')
    window.removeEventListener('sm:navigate', nav)
  })

  it('uç başarısız → panel çizilmez', async () => {
    api.me.today.mockResolvedValue({ success: false })
    const { container } = render(<TodayPanel />)
    await new Promise((r) => setTimeout(r, 10))
    expect(container.querySelector('.today')).toBeNull()
  })
})
