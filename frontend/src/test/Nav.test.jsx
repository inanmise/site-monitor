import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import Nav from '../components/Nav.jsx'

const DEFAULT_PROPS = {
  activeTab: 'dashboard',
  onTabChange: vi.fn(),
  username: 'testuser',
  onLogout: vi.fn(),
}

describe('Nav', () => {
  it('renders all 5 tabs', () => {
    render(<Nav {...DEFAULT_PROPS} />)
    expect(screen.getByText('Dashboard')).toBeInTheDocument()
    expect(screen.getByText(/Uyarılar/)).toBeInTheDocument()
    expect(screen.getByText(/Tüm Sertifikalar/)).toBeInTheDocument()
    expect(screen.getByText(/Yenileme/)).toBeInTheDocument()
    expect(screen.getByText(/Yönetim/)).toBeInTheDocument()
  })

  it('marks the active tab with the active class', () => {
    render(<Nav {...DEFAULT_PROPS} activeTab="warnings" />)
    const warningsBtn = screen.getByText(/Uyarılar/)
    expect(warningsBtn).toHaveClass('active')
    const dashboardBtn = screen.getByText('Dashboard')
    expect(dashboardBtn).not.toHaveClass('active')
  })

  it('calls onTabChange with tab id when a tab is clicked', () => {
    const onTabChange = vi.fn()
    render(<Nav {...DEFAULT_PROPS} onTabChange={onTabChange} />)
    fireEvent.click(screen.getByText('Dashboard'))
    expect(onTabChange).toHaveBeenCalledWith('dashboard')
  })

  it('calls onTabChange with "admin" when Yönetim is clicked', () => {
    const onTabChange = vi.fn()
    render(<Nav {...DEFAULT_PROPS} onTabChange={onTabChange} />)
    fireEvent.click(screen.getByText(/Yönetim/))
    expect(onTabChange).toHaveBeenCalledWith('admin')
  })

  it('displays the username', () => {
    render(<Nav {...DEFAULT_PROPS} username="alice" />)
    expect(screen.getByText(/alice/)).toBeInTheDocument()
  })

  it('calls onLogout when logout button is clicked', () => {
    const onLogout = vi.fn()
    render(<Nav {...DEFAULT_PROPS} onLogout={onLogout} />)
    fireEvent.click(screen.getByText(/Çıkış/))
    expect(onLogout).toHaveBeenCalledTimes(1)
  })
})
