import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from './test-utils.jsx'
import AdminPanel from '../components/admin/AdminPanel.jsx'

vi.mock('../api/client', () => ({
  api: {
    admin: {
      getTeams: vi.fn().mockResolvedValue({ success: true, data: [] }),
    },
  },
}))

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
vi.mock('../components/admin/TeamManager.jsx', () => ({
  default: () => <div data-testid="team-manager">TeamManager</div>,
}))
vi.mock('../components/admin/UserManager.jsx', () => ({
  default: () => <div data-testid="user-manager">UserManager</div>,
}))
describe('AdminPanel', () => {
  it('renders only non-admin tabs without admin role', () => {
    render(<AdminPanel />)
    expect(screen.getByText('Domain Inventory')).toBeInTheDocument()
    expect(screen.getByText('Escalation Contacts')).toBeInTheDocument()
    expect(screen.queryByText('Alert History')).not.toBeInTheDocument()
    expect(screen.queryByText('Thresholds')).not.toBeInTheDocument()
  })

  it('renders all 6 tabs with admin role', () => {
    render(<AdminPanel systemRole="ADMIN" />)
    expect(screen.getByText('Domain Inventory')).toBeInTheDocument()
    expect(screen.getByText('Escalation Contacts')).toBeInTheDocument()
    expect(screen.getByText('Alert History')).toBeInTheDocument()
    expect(screen.getByText('Thresholds')).toBeInTheDocument()
    expect(screen.getByText('Teams')).toBeInTheDocument()
    expect(screen.getByText('Users')).toBeInTheDocument()
  })

  it('shows InventoryManager by default (first tab)', () => {
    render(<AdminPanel />)
    expect(screen.getByTestId('inventory-manager')).toBeInTheDocument()
    expect(screen.queryByTestId('escalation-contacts')).not.toBeInTheDocument()
  })

  it('inventory tab button has active class by default', () => {
    render(<AdminPanel />)
    const inventoryBtn = screen.getByRole('button', { name: 'Domain Inventory' })
    expect(inventoryBtn).toHaveClass('active')
  })

  it('switches to EscalationContacts when contacts tab is clicked', () => {
    render(<AdminPanel />)
    fireEvent.click(screen.getByRole('button', { name: 'Escalation Contacts' }))
    expect(screen.getByTestId('escalation-contacts')).toBeInTheDocument()
    expect(screen.queryByTestId('inventory-manager')).not.toBeInTheDocument()
  })

  it('switches to AlertHistory when alert tab is clicked (admin)', () => {
    render(<AdminPanel systemRole="ADMIN" />)
    fireEvent.click(screen.getByRole('button', { name: 'Alert History' }))
    expect(screen.getByTestId('alert-history')).toBeInTheDocument()
    expect(screen.queryByTestId('inventory-manager')).not.toBeInTheDocument()
  })

  it('switches to AlertThresholds when thresholds tab is clicked (admin)', () => {
    render(<AdminPanel systemRole="ADMIN" />)
    fireEvent.click(screen.getByRole('button', { name: 'Thresholds' }))
    expect(screen.getByTestId('alert-thresholds')).toBeInTheDocument()
  })

  it('active tab button updates when switching tabs', () => {
    render(<AdminPanel />)
    const contactsBtn = screen.getByRole('button', { name: 'Escalation Contacts' })
    fireEvent.click(contactsBtn)
    expect(contactsBtn).toHaveClass('active')
    const inventoryBtn = screen.getByRole('button', { name: 'Domain Inventory' })
    expect(inventoryBtn).not.toHaveClass('active')
  })
})
