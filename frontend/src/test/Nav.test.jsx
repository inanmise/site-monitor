import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from './test-utils.jsx'
import Nav from '../components/Nav.jsx'

const DEFAULT_PROPS = {
  activeTab: 'dashboard',
  onTabChange: vi.fn(),
  username: 'testuser',
  onLogout: vi.fn(),
}

describe('Nav', () => {
  it('renders all 6 tabs', () => {
    render(<Nav {...DEFAULT_PROPS} />)
    expect(screen.getByText('Dashboard')).toBeInTheDocument()
    expect(screen.getByText('Warnings')).toBeInTheDocument()
    expect(screen.getByText('All Certificates')).toBeInTheDocument()
    expect(screen.getByText('Renewal Advice')).toBeInTheDocument()
    expect(screen.getByText('Activity Log')).toBeInTheDocument()
    expect(screen.getByText('Admin Panel')).toBeInTheDocument()
  })

  /**
   * Kullanici istegi (2026-08-27): Izleme Degisiklikleri ekrani TUM takim kullanicilarina acildi.
   * Onceden `isGlobalAdmin || isAudit` kapisindaydi. Uc zaten viewTeamIds ile sinirliyor; menuyu
   * gizlemek kullaniciyi yalniz KENDI takiminin verisinden mahrum birakiyordu.
   *
   * DEFAULT_PROPS'ta systemRole/globalAdmin YOK -> siradan kullanici. Kapi geri kapatilirsa
   * bu test kirilir.
   */
  it('shows Monitor Changes to a plain team user (no admin props)', () => {
    render(<Nav {...DEFAULT_PROPS} />)
    expect(screen.getByText('Monitor Changes')).toBeInTheDocument()
  })

  /** Denetim Logu AYNI kapida DEGIL: sistem-geneli guvenlik kaydi, admin/AUDIT'te kalir. */
  it('still hides the system audit log from a plain team user', () => {
    render(<Nav {...DEFAULT_PROPS} />)
    expect(screen.queryByText('Audit Log')).toBeNull()
  })

  it('marks the active tab with the sb-active class', () => {
    render(<Nav {...DEFAULT_PROPS} activeTab="warnings" />)
    const warningsBtn = screen.getByRole('button', { name: /Warnings/ })
    expect(warningsBtn).toHaveClass('sb-active')
    const dashboardBtn = screen.getByRole('button', { name: /Dashboard/ })
    expect(dashboardBtn).not.toHaveClass('sb-active')
  })

  it('calls onTabChange with tab id when a tab is clicked', () => {
    const onTabChange = vi.fn()
    render(<Nav {...DEFAULT_PROPS} onTabChange={onTabChange} />)
    fireEvent.click(screen.getByRole('button', { name: /Dashboard/ }))
    expect(onTabChange).toHaveBeenCalledWith('dashboard')
  })

  it('calls onTabChange with "admin" when Admin Panel is clicked', () => {
    const onTabChange = vi.fn()
    render(<Nav {...DEFAULT_PROPS} onTabChange={onTabChange} />)
    fireEvent.click(screen.getByRole('button', { name: /Admin Panel/ }))
    expect(onTabChange).toHaveBeenCalledWith('admin')
  })

  it('displays the username', () => {
    render(<Nav {...DEFAULT_PROPS} username="alice" />)
    expect(screen.getByText(/alice/)).toBeInTheDocument()
  })

  it('calls onLogout when logout button is clicked', () => {
    const onLogout = vi.fn()
    render(<Nav {...DEFAULT_PROPS} onLogout={onLogout} />)
    fireEvent.click(screen.getByRole('button', { name: /Logout/ }))
    expect(onLogout).toHaveBeenCalledTimes(1)
  })
})
