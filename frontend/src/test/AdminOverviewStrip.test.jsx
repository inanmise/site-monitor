import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent } from './test-utils.jsx'
import AdminOverviewStrip from '../components/admin/AdminOverviewStrip.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  api: withApiFallback({ admin: { overview: vi.fn() } }),
}))
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
    fireEvent.click(screen.getByText(/3 kullanıcı hiç giriş|3 users have never/))
    expect(jump).toHaveBeenCalledWith('users', { g_dormant: 'never' })
    fireEvent.click(screen.getByText(/Tek aktif ADMIN|Only one active ADMIN/))
    expect(jump).toHaveBeenCalledWith('users', { g_role: 'ADMIN' })
    fireEvent.click(screen.getByText(/1 takımın lideri yok|1 teams have no leader/))
    expect(jump).toHaveBeenCalledWith('teams', {})
    // KPI tıklaması süzgeçsiz sekme
    fireEvent.click(screen.getByText('4').closest('button'))
    expect(jump).toHaveBeenCalledWith('contacts', {})
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
