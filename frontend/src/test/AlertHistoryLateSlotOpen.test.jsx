import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within, act } from './test-utils.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../hooks/use-mobile.js', () => ({ useIsMobile: () => false }))
// Takım dağılımı hücresi = openFullAlert tetiği (tekil uçtan TAM satırı okur)
vi.mock('../components/admin/alerts/AlertTeamStatsPanel.jsx', () => ({
  default: ({ onOpenAlert }) => (
    <button type="button" data-testid="slot-open" onClick={() => onOpenAlert({ id: 202, domain: 'slot.example.com', resolved: false })}>slot</button>
  ),
}))
vi.mock('../components/admin/AlertNoisePanel.jsx', () => ({ default: () => null }))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: withApiFallback({
    admin: {
      getAlerts: vi.fn(), getAlert: vi.fn(), getAlertNotifications: vi.fn(), getAlertPushDeliveries: vi.fn(), getTeams: vi.fn(),
      getAlertsCsvUrl: vi.fn(() => '/api/admin/alerts/export'),
    },
  }),
}))
import { api } from '../api/client'
import AlertHistory from '../components/admin/AlertHistory.jsx'

/**
 * Alarm geçmişi — ısı haritası / takım dağılımı hücresinden açılan alarm (2026-10-22, son inceleme FE-8): tekil uçtan geç
 * gelen yanıt, kullanıcı arada başka bir alarmı açtıysa onun penceresini DEĞİŞTİRMEZ; pencere kapatıldıysa yeniden AÇMAZ.
 */
const LIST_ALERT = { id: 101, domain: 'liste.example.com', alert_type: 'EXPIRY', alert_level: 'HIGH', acknowledged: false, resolved: false,
  created_at: '2026-10-20T08:00:00' }
const SLOT_ALERT = { id: 202, domain: 'slot.example.com', alert_type: 'EXPIRY', alert_level: 'CRITICAL', acknowledged: false, resolved: false,
  created_at: '2026-10-20T09:00:00' }

function deferred() { let resolve; const p = new Promise((r) => { resolve = r }); return { p, resolve } }

describe('AlertHistory — geç gelen tekil alarm okuması', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    window.history.replaceState({}, '', '/?tab=alerthistory')
    api.admin.getAlerts.mockResolvedValue({ success: true, data: [LIST_ALERT], total: 1, page: 0, size: 20 })
    api.admin.getAlertNotifications.mockResolvedValue({ success: true, data: [] })
    api.admin.getAlertPushDeliveries.mockResolvedValue({ success: true, data: [] })
    api.admin.getTeams.mockResolvedValue({ success: true, data: [] })
  })
  afterEach(() => { window.history.replaceState({}, '', '/') })

  it('arada listeden başka alarm açıldıysa geç yanıt o pencereyi değiştirmez', async () => {
    const slow = deferred()
    api.admin.getAlert.mockImplementation(() => slow.p)
    render(<AlertHistory urlSync />)
    await waitFor(() => expect(document.querySelector('[data-alert-card] [data-alert-open]')).not.toBeNull())
    fireEvent.click(screen.getByTestId('slot-open'))
    await waitFor(() => expect(api.admin.getAlert).toHaveBeenCalledWith(202))
    fireEvent.click(document.querySelector('[data-alert-card] [data-alert-open]'))
    const detail = await screen.findByRole('dialog')
    expect(within(detail).getAllByText(/liste\.example\.com/).length).toBeGreaterThan(0)
    await act(async () => { slow.resolve({ success: true, data: SLOT_ALERT }) })
    await new Promise((r) => setTimeout(r, 20))
    const after = screen.getByRole('dialog')
    expect(within(after).queryAllByText(/slot\.example\.com/)).toHaveLength(0)
    expect(within(after).getAllByText(/liste\.example\.com/).length).toBeGreaterThan(0)
  })

  it('pencere kapatıldıysa (ya da hiç açılmadan başka işlem yapıldıysa) geç yanıt pencereyi yeniden açmaz', async () => {
    const slow = deferred()
    api.admin.getAlert.mockImplementation(() => slow.p)
    render(<AlertHistory urlSync />)
    await waitFor(() => expect(document.querySelector('[data-alert-card] [data-alert-open]')).not.toBeNull())
    fireEvent.click(screen.getByTestId('slot-open'))
    await waitFor(() => expect(api.admin.getAlert).toHaveBeenCalledWith(202))
    // Kullanıcı listeden bir alarm açıp kapatır
    fireEvent.click(document.querySelector('[data-alert-card] [data-alert-open]'))
    const detail = await screen.findByRole('dialog')
    fireEvent.keyDown(detail, { key: 'Escape' })
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    await act(async () => { slow.resolve({ success: true, data: SLOT_ALERT }) })
    await new Promise((r) => setTimeout(r, 20))
    expect(screen.queryByRole('dialog')).toBeNull()
  })

  it('arada hiçbir şey olmadıysa yanıt pencereyi açar (bugünkü davranış)', async () => {
    api.admin.getAlert.mockResolvedValue({ success: true, data: SLOT_ALERT })
    render(<AlertHistory urlSync />)
    await waitFor(() => expect(document.querySelector('[data-alert-card] [data-alert-open]')).not.toBeNull())
    fireEvent.click(screen.getByTestId('slot-open'))
    const detail = await screen.findByRole('dialog')
    expect(within(detail).getAllByText(/slot\.example\.com/).length).toBeGreaterThan(0)
  })
})
