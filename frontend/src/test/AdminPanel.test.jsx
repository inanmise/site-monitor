import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import AdminPanel from '../components/admin/AdminPanel.jsx'

// Mock all child components that make API calls
vi.mock('../components/admin/InventoryManager.jsx', () => ({
  default: () => <div data-testid="inventory-manager">InventoryManager</div>,
}))
vi.mock('../components/admin/AlertHistory.jsx', () => ({
  default: () => <div data-testid="alert-history">AlertHistory</div>,
}))
vi.mock('../components/admin/EscalationContacts.jsx', () => ({
  default: () => <div data-testid="escalation-contacts">EscalationContacts</div>,
}))
vi.mock('../components/admin/AlertThresholds.jsx', () => ({
  default: () => <div data-testid="alert-thresholds">AlertThresholds</div>,
}))

describe('AdminPanel', () => {
  it('renders all 4 sub-tab buttons', () => {
    render(<AdminPanel />)
    expect(screen.getByText(/Domain Envanteri/)).toBeInTheDocument()
    expect(screen.getByText(/Alarm Geçmişi/)).toBeInTheDocument()
    expect(screen.getByText(/Eskalasyon Kişileri/)).toBeInTheDocument()
    expect(screen.getByText(/Eşik Değerleri/)).toBeInTheDocument()
  })

  it('shows InventoryManager by default (first tab)', () => {
    render(<AdminPanel />)
    expect(screen.getByTestId('inventory-manager')).toBeInTheDocument()
    expect(screen.queryByTestId('alert-history')).not.toBeInTheDocument()
  })

  it('inventory tab button has active class by default', () => {
    render(<AdminPanel />)
    const inventoryBtn = screen.getByText(/Domain Envanteri/)
    expect(inventoryBtn).toHaveClass('active')
  })

  it('switches to AlertHistory when Alarm Geçmişi is clicked', () => {
    render(<AdminPanel />)
    fireEvent.click(screen.getByText(/Alarm Geçmişi/))
    expect(screen.getByTestId('alert-history')).toBeInTheDocument()
    expect(screen.queryByTestId('inventory-manager')).not.toBeInTheDocument()
  })

  it('switches to EscalationContacts when the contacts tab is clicked', () => {
    render(<AdminPanel />)
    fireEvent.click(screen.getByText(/Eskalasyon Kişileri/))
    expect(screen.getByTestId('escalation-contacts')).toBeInTheDocument()
  })

  it('switches to AlertThresholds when the thresholds tab is clicked', () => {
    render(<AdminPanel />)
    fireEvent.click(screen.getByText(/Eşik Değerleri/))
    expect(screen.getByTestId('alert-thresholds')).toBeInTheDocument()
  })

  it('active tab button updates when switching tabs', () => {
    render(<AdminPanel />)
    const alertsBtn = screen.getByText(/Alarm Geçmişi/)
    fireEvent.click(alertsBtn)
    expect(alertsBtn).toHaveClass('active')
    const inventoryBtn = screen.getByText(/Domain Envanteri/)
    expect(inventoryBtn).not.toHaveClass('active')
  })
})
