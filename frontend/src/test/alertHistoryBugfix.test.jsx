import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'
import AlertHistory from '../components/admin/AlertHistory.jsx'

/**
 * Alarm Geçmişi — 2026-10-22 hata düzeltmeleri:
 *  - UIM-8 / K13: yalnız push (tüm e-postalar hariç, push alıcısı kaldı) yeniden bildirim → "0 alıcıya" denmez; ağ hatası
 *    işlenmemiş ret değil okunur ileti.
 *  - UIM-31: takım dağılımı / gürültü hücresinden açılan alarm KISMİ satırdı — tam satır tekil uçtan okunur.
 */
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../hooks/use-mobile.js', () => ({ useIsMobile: () => false }))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: withApiFallback({
    admin: {
      getAlerts: vi.fn(), getAlert: vi.fn(), reNotifyAlert: vi.fn(), previewReNotify: vi.fn(),
      getAlertNotifications: vi.fn(), getAlertPushDeliveries: vi.fn(), getTeams: vi.fn(),
      getAlertTeamStats: vi.fn(), getAlertsCsvUrl: vi.fn(() => '/api/admin/alerts/export'),
    },
  }),
}))
import { api } from '../api/client'

const OPEN = { id: 301, domain: 'foo.example.com', alert_type: 'HTTP_DOWN', alert_level: 'HIGH', acknowledged: false, resolved: false,
  created_at: '2026-10-22T08:00:00', team_id: 1, team_name: 'Takım A', email_sent_count: 1 }

beforeEach(() => {
  vi.clearAllMocks()
  window.history.replaceState({}, '', '/?tab=alerthistory')
  try { sessionStorage.clear(); localStorage.clear() } catch { /* yok */ }
  api.admin.getTeams.mockResolvedValue({ success: true, data: [{ id: 1, name: 'Takım A' }] })
  api.admin.getAlertNotifications.mockResolvedValue({ success: true, data: [] })
  api.admin.getAlertPushDeliveries.mockResolvedValue({ success: true, data: [] })
  api.admin.getAlerts.mockResolvedValue({ success: true, total: 1, page: 0, size: 20, data: [OPEN] })
})

describe('UIM-8 — yalnız push yeniden bildirim', () => {
  async function openModal() {
    api.admin.previewReNotify.mockResolvedValue({ success: true, data: {
      alert_id: 301, recipients: [{ email: 'takim-a@example.com', name: 'SY-Takım A', role: null, kind: 'TEAM' }],
      webhook: { channel_enabled: true, block_reason: null, recipients: [
        { username: 'N00001', display_name: 'Kisi Bir', status: 'PENDING' },
        { username: 'N00002', display_name: 'Kisi Iki', status: 'PENDING' }] } } })
    render(<AlertHistory />)
    await waitFor(() => expect(screen.getByText('foo.example.com')).toBeDefined())
    fireEvent.click(screen.getByRole('button', { name: /foo\.example\.com.*Re-Notify/ }))
    await screen.findByText(/confirm recipients/i)
    const modal = screen.getByRole('dialog')
    const row = Array.from(modal.querySelectorAll('label')).find((l) => l.textContent.includes('takim-a@example.com'))
    fireEvent.click(row.parentElement.querySelector('[role="checkbox"]'))
    return modal
  }

  it('tüm e-postalar hariç + 2 push kişisi → sunucu kabul eder; bildirim "2 kişiye push" der, "0 alıcı" demez', async () => {
    api.admin.reNotifyAlert.mockResolvedValue({ success: true, data: { recipients_queued: 0, contacts_queued: 0 } })
    const modal = await openModal()
    fireEvent.click(within(modal).getByRole('button', { name: /^send$/i }))
    await waitFor(() => expect(api.admin.reNotifyAlert).toHaveBeenCalledWith(301, { excludeEmails: ['takim-a@example.com'] }))
    expect(await screen.findByText(/push notification sent to 2 people|push bildirimi 2 kişiye/i)).toBeInTheDocument()
    expect(screen.queryByText(/sent to 0 recipients|0 alıcıya/i)).toBeNull()
  })

  it('gönderimde ağ hatası → okunur ileti (işlenmemiş ret değil), pencere açık kalır', async () => {
    api.admin.reNotifyAlert.mockRejectedValue(new Error('Bağlantı koptu — yeniden deneyin.'))
    const modal = await openModal()
    fireEvent.click(within(modal).getByRole('button', { name: /^send$/i }))
    expect(await screen.findByText(/Bağlantı koptu/)).toBeInTheDocument()
    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })
})

describe('UIM-31 — hücreden açılan alarm tam satırla açılır', () => {
  it('takım dağılımı hücresi → getAlert(id) çağrılır, detay TAM satırla açılır', async () => {
    sessionStorage.setItem('alh-teamstats-open', 'true')
    api.admin.getAlertTeamStats.mockResolvedValue({ success: true, data: { window_days: 30, total_open: 1, total_last7: 1,
      teams: [{ team_id: 1, team_name: 'Takım A', open: 1, closed: 0, last7: 1, last30: 1 }] } })
    const partial = { id: 777, domain: 'slot.example.com', alert_type: 'HTTP_DOWN', alert_level: 'HIGH', created_at: '2026-10-22T07:00:00', resolved: false }
    api.admin.getAlerts.mockImplementation((p) => Promise.resolve(p?.teamId === 1
      ? { success: true, data: [partial], total: 1 }
      : { success: true, total: 1, page: 0, size: 20, data: [OPEN] }))
    api.admin.getAlert.mockResolvedValue({ success: true, data: { ...partial, email_sent_count: 3, act_scope: false, team_id: 1, team_name: 'Takım A' } })
    render(<AlertHistory urlSync />)
    await waitFor(() => expect(document.querySelector('[data-cell-num]')).not.toBeNull())
    fireEvent.click(document.querySelector('[data-cell-num]'))
    fireEvent.click(await screen.findByRole('button', { name: 'slot.example.com' }))
    await waitFor(() => expect(api.admin.getAlert).toHaveBeenCalledWith(777))
    // Tam satırdaki act_scope:false → Sahiplen / Çöz düğmeleri yok (kısmi satır onları gösteriyordu)
    await waitFor(() => expect(document.querySelector('[data-slot="alert-detail-body"]')).not.toBeNull())
    const body = document.querySelector('[data-slot="alert-detail-body"]')
    expect(within(body).queryByRole('button', { name: /^(acknowledge|sahiplen)$/i })).toBeNull()
  })
})
