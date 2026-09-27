import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent } from './test-utils.jsx'
import AdminOverviewStrip from '../components/admin/AdminOverviewStrip.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  api: withApiFallback({ admin: { overview: vi.fn() } }),
}))
// Telefon kipi (jsdom medya sorgusu görmez → kanca mock'lanır)
const mobile = vi.hoisted(() => ({ on: false }))
vi.mock('../hooks/use-mobile.js', () => ({ useIsMobile: () => mobile.on }))
import { api } from '../api/client'

/** Özet şeridi (2026-09-20): sayaçlar + uyarılar; uyarı çipi sekmeye SÜZGEÇLİ atlar. */
describe('AdminOverviewStrip', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.admin.overview.mockResolvedValue({ success: true, data: {
      dormant_days: 90,
      counts: { teams: 3, teams_active: 2, users: 12, users_active: 10, admins: 1, contacts: 4, contacts_webhook: 1, groups: 2,
        threshold_tiers: 1, threshold_default: { warning: 30, high: 15, critical: 7 } },
      warnings: [{ code: 'USER_NEVER_LOGGED_IN', count: 3, tab: 'users' }, { code: 'SINGLE_ADMIN', count: 1, tab: 'users' }, { code: 'TEAM_NO_LEADER', count: 1, tab: 'teams' }],
    } })
  })

  it('sayaçlar ve uyarılar çizilir; uyarı tıklaması sekme + süzgeç verir', async () => {
    const jump = vi.fn()
    render(<AdminOverviewStrip isAdmin onJump={jump} />)
    expect(await screen.findByText('12')).toBeInTheDocument()
    expect(screen.getByText('30/15/7')).toBeInTheDocument()
    fireEvent.click(screen.getByText(/Tek aktif ADMIN|Only one active ADMIN/))
    expect(jump).toHaveBeenCalledWith('users', { g_role: 'ADMIN' })
    fireEvent.click(screen.getByText(/1 takımın lideri yok|1 teams have no leader/))
    expect(jump).toHaveBeenCalledWith('teams', {})
    // KPI tıklaması süzgeçsiz sekme
    fireEvent.click(screen.getByText('4').closest('button'))
    expect(jump).toHaveBeenCalledWith('contacts', {})
  })

  it('shadcn: sayaçlar ve uyarılar Button; ağır uyarı (tek ADMIN) danger, diğerleri warning tonunda', async () => {
    render(<AdminOverviewStrip isAdmin onJump={() => {}} />)
    await screen.findByText('12')
    const kpi = screen.getByText('12').closest('button')
    expect(kpi).toHaveAttribute('data-slot', 'button')
    expect(kpi).toHaveAttribute('data-kpi', 'users')
    const severe = screen.getByText(/Tek aktif ADMIN|Only one active ADMIN/).closest('button')
    expect(severe).toHaveAttribute('data-slot', 'button')
    expect(severe).toHaveAttribute('data-tone', 'danger')
    expect(screen.getByText(/1 takımın lideri yok|1 teams have no leader/).closest('button')).toHaveAttribute('data-tone', 'warning')
    // Legacy `.aov-*` sınıfları kalmadı (App.css katmansız — shadcn Button'u ezerdi)
    expect(document.querySelector('[class*="aov-"]')).toBeNull()
  })

  // 2026-09-27 kullanıcı isteği: "hiç giriş yapmamış" bilgisi Kullanıcılar sekmesinin kutucuklarında; şeritte YOK.
  it('kullanıcı etkinliği uyarıları (hiç girmemiş / uzun süredir girmemiş / kilitli) şeritte gösterilmez', async () => {
    api.admin.overview.mockResolvedValue({ success: true, data: { counts: { teams: 1, users: 2, contacts: 0, groups: 0 },
      warnings: [{ code: 'USER_NEVER_LOGGED_IN', count: 1 }, { code: 'USER_DORMANT', count: 2 }, { code: 'USER_LOCKED', count: 1 }] } })
    const { container } = render(<AdminOverviewStrip isAdmin onJump={() => {}} />)
    expect(await screen.findByText(/Sağlık uyarısı yok|No health warnings/)).toBeInTheDocument()
    expect(container.querySelector('[data-warning]')).toBeNull()
    expect(screen.queryByText(/hiç giriş|never signed in|never logged in/i)).toBeNull()
  })

  it('admin değilse eşik KPI\\u0027si yok; uyarı yoksa "temiz" satırı; hata → hiç çizilmez', async () => {
    api.admin.overview.mockResolvedValue({ success: true, data: { counts: { teams: 1, users: 2, contacts: 0, groups: 0, threshold_default: { warning: 30, high: 15, critical: 7 } }, warnings: [] } })
    render(<AdminOverviewStrip isAdmin={false} onJump={() => {}} />)
    expect(await screen.findByText(/Sağlık uyarısı yok|No health warnings/)).toBeInTheDocument()
    expect(screen.queryByText('30/15/7')).toBeNull()
    api.admin.overview.mockResolvedValue({ success: false })
    const { container } = render(<AdminOverviewStrip isAdmin onJump={() => {}} />)
    await new Promise(r => setTimeout(r, 20))
    expect(container.querySelector('[data-testid="admin-overview"]')).toBeNull()
  })
})

/** Telefon (2026-09-26, mweb): uyarılar ekranı yemesin — ağırlar önce, ilk 2 görünür, "+N" ile tümü açılır. */
describe('AdminOverviewStrip — telefon', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mobile.on = true
    api.admin.overview.mockResolvedValue({ success: true, data: {
      counts: { teams: 3, users: 12, contacts: 4, groups: 2 },
      warnings: [{ code: 'CONTACT_INACTIVE', count: 2 }, { code: 'TEAM_NO_LEADER', count: 1 }, { code: 'SINGLE_ADMIN', count: 1 }],
    } })
  })

  it('ağır uyarı öne alınır; yalnız 2 çip + "+1" düğmesi; basınca hepsi görünür ve tıklanabilir kalır', async () => {
    const jump = vi.fn()
    const { container } = render(<AdminOverviewStrip isAdmin onJump={jump} />)
    await screen.findByText('12')
    const chips = () => [...container.querySelectorAll('[data-warning]')].map((b) => b.getAttribute('data-warning'))
    expect(chips()).toEqual(['SINGLE_ADMIN', 'CONTACT_INACTIVE'])
    fireEvent.click(screen.getByRole('button', { name: /^\+1 (more|daha)$/ }))
    expect(chips()).toEqual(['SINGLE_ADMIN', 'CONTACT_INACTIVE', 'TEAM_NO_LEADER'])
    fireEvent.click(screen.getByText(/1 takımın lideri yok|1 teams have no leader/))
    expect(jump).toHaveBeenCalledWith('teams', {})
    mobile.on = false
  })
})
