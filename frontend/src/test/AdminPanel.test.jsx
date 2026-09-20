import { describe, it, expect, vi, afterEach } from 'vitest'
import { render, screen, fireEvent, act } from './test-utils.jsx'
import AdminPanel from '../components/admin/AdminPanel.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  api: withApiFallback({
    admin: {
      getTeams: vi.fn().mockResolvedValue({ success: true, data: [] }),
    },
  }),
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
  it('non-admin sees contacts, teams, users (read-only) but not thresholds', async () => {
    await act(async () => { render(<AdminPanel />) })
    expect(screen.getByText('Escalation Contacts')).toBeInTheDocument()
    expect(screen.getByText('Teams')).toBeInTheDocument()
    expect(screen.getByText('Users')).toBeInTheDocument()
    expect(screen.queryByText('Thresholds')).not.toBeInTheDocument()
  })

  it('renders all 4 tabs with admin role', async () => {
    await act(async () => { render(<AdminPanel systemRole="ADMIN" />) })
    expect(screen.getByText('Thresholds')).toBeInTheDocument()
    expect(screen.getByText('Escalation Contacts')).toBeInTheDocument()
    expect(screen.getByText('Teams')).toBeInTheDocument()
    expect(screen.getByText('Users')).toBeInTheDocument()
  })

  it('shows EscalationContacts by default for non-admin', async () => {
    await act(async () => { render(<AdminPanel />) })
    expect(screen.getByTestId('escalation-contacts')).toBeInTheDocument()
    expect(screen.queryByTestId('alert-thresholds')).not.toBeInTheDocument()
  })

  it('shows AlertThresholds by default for admin', async () => {
    await act(async () => { render(<AdminPanel systemRole="ADMIN" />) })
    expect(screen.getByTestId('alert-thresholds')).toBeInTheDocument()
    expect(screen.queryByTestId('escalation-contacts')).not.toBeInTheDocument()
  })

  it('contacts tab button has active class by default for non-admin', async () => {
    await act(async () => { render(<AdminPanel />) })
    const contactsBtn = screen.getByRole('button', { name: 'Escalation Contacts' })
    expect(contactsBtn).toHaveClass('active')
  })

  it('switches to EscalationContacts when contacts tab is clicked (admin)', async () => {
    await act(async () => { render(<AdminPanel systemRole="ADMIN" />) })
    fireEvent.click(screen.getByRole('button', { name: 'Escalation Contacts' }))
    expect(screen.getByTestId('escalation-contacts')).toBeInTheDocument()
    expect(screen.queryByTestId('alert-thresholds')).not.toBeInTheDocument()
  })

  it('switches to AlertThresholds when thresholds tab is clicked (admin)', async () => {
    await act(async () => { render(<AdminPanel systemRole="ADMIN" />) })
    fireEvent.click(screen.getByRole('button', { name: 'Escalation Contacts' }))
    fireEvent.click(screen.getByRole('button', { name: 'Thresholds' }))
    expect(screen.getByTestId('alert-thresholds')).toBeInTheDocument()
  })

  it('active tab button updates when switching tabs', async () => {
    await act(async () => { render(<AdminPanel systemRole="ADMIN" />) })
    const contactsBtn = screen.getByRole('button', { name: 'Escalation Contacts' })
    fireEvent.click(contactsBtn)
    expect(contactsBtn).toHaveClass('active')
    const thresholdsBtn = screen.getByRole('button', { name: 'Thresholds' })
    expect(thresholdsBtn).not.toHaveClass('active')
  })
})

/** Alt sekme + süzgeçler URL'de (g_*): derin bağlantı ve yenileme ilk sekmeye düşmez (2026-09-20). */
describe('AdminPanel — g_tab derin bağlantı', () => {
  afterEach(() => { window.history.replaceState(null, '', '/') })

  it('g_tab=users ile açılınca Kullanıcılar sekmesi aktif', async () => {
    window.history.replaceState(null, '', '/?tab=admin&g_tab=users')
    await act(async () => { render(<AdminPanel systemRole="ADMIN" />) })
    expect(screen.getByTestId('user-manager')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Users' })).toHaveClass('active')
  })

  it('yetkisiz sekme (TEAM_ADMIN + g_tab=thresholds) varsayılana iner', async () => {
    window.history.replaceState(null, '', '/?tab=admin&g_tab=thresholds')
    await act(async () => { render(<AdminPanel systemRole="TEAM_ADMIN" />) })
    expect(screen.getByTestId('escalation-contacts')).toBeInTheDocument()
    expect(screen.queryByTestId('alert-thresholds')).not.toBeInTheDocument()
  })

  it('sekme değişince önceki sekmenin g_* süzgeçleri URL parametrelerinden silinir, g_tab kalır', async () => {
    window.history.replaceState(null, '', '/?tab=admin&g_tab=users&g_role=ADMIN&g_q=ali')
    await act(async () => { render(<AdminPanel systemRole="ADMIN" />) })
    fireEvent.click(screen.getByRole('button', { name: 'Teams' }))
    const sp = new URLSearchParams(window.location.search)
    expect(sp.get('g_role')).toBeNull()
    expect(sp.get('g_q')).toBeNull()
    expect(sp.get('tab')).toBe('admin')
    expect(screen.getByTestId('team-manager')).toBeInTheDocument()
  })
})
