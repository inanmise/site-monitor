import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from './test-utils.jsx'
import MaintenanceWindowsPage from '../components/MaintenanceWindowsPage.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  formatDateSec: (s) => s ?? '',
  api: withApiFallback({
    monitoring: {
      maintenance: {
        list: vi.fn(), active: vi.fn(), create: vi.fn(), update: vi.fn(),
        remove: vi.fn(), pause: vi.fn(), resume: vi.fn(), quick: vi.fn(),
      },
      getHttpMonitors: vi.fn(), getPortMonitors: vi.fn(), getKeywordMonitors: vi.fn(),
      getPingMonitors: vi.fn(), getDnsMonitors: vi.fn(), getDomainMonitors: vi.fn(), getUptimeOverview: vi.fn(),
    },
  }),
}))
import { api } from '../api/client'

const win = {
  id: 1, name: 'DB bakımı', description: 'Planlı', all_monitors: false,
  targets: [{ type: 'port', target: 'db.local', name: 'DB' }], target_count: 1,
  timezone: 'Europe/Istanbul', start_at: '2026-01-01T23:00:00', duration_minutes: 120,
  recurrence: 'DAILY', days_of_week: null, day_of_month: null, active: true,
  status: 'upcoming', next_occurrence: '2026-01-02T23:00:00',
}

describe('MaintenanceWindowsPage', () => {
  beforeEach(() => { vi.clearAllMocks() })

  it('boş durumda "Create your first maintenance!" gösterir', async () => {
    api.monitoring.maintenance.list.mockResolvedValue({ success: true, data: [] })
    render(<MaintenanceWindowsPage systemRole="ADMIN" />)
    await waitFor(() => expect(api.monitoring.maintenance.list).toHaveBeenCalled())
    expect(await screen.findByText(/create your first maintenance|İlk bakımınızı oluşturun/i)).toBeInTheDocument()
  })

  it('pencereyi listeler (ad + status)', async () => {
    api.monitoring.maintenance.list.mockResolvedValue({ success: true, data: [win] })
    render(<MaintenanceWindowsPage systemRole="ADMIN" />)
    await waitFor(() => expect(api.monitoring.maintenance.list).toHaveBeenCalled())
    expect(await screen.findByText('DB bakımı')).toBeInTheDocument()
    expect(screen.getByText(/upcoming|Yaklaşan/i)).toBeInTheDocument()
  })

  /**
   * 2026-09-25 (R15): satır eylemleri yalnız ikon + title taşıyordu — her satırda aynı "Sil",
   * "Düzenle"; ekran okuyucu hangi pencerenin silineceğini duymuyordu. Ad = pencere adı + eylem.
   */
  it('satır eylem düğmelerinin ADI pencereyi ayırır (iki satır, özdeş ad yok)', async () => {
    api.monitoring.maintenance.list.mockResolvedValue({ success: true, data: [win, { ...win, id: 2, name: 'Ağ bakımı', status: 'paused' }] })
    render(<MaintenanceWindowsPage systemRole="ADMIN" />)
    await screen.findByText('Ağ bakımı')
    for (const name of ['DB bakımı', 'Ağ bakımı']) {
      expect(screen.getByRole('button', { name: new RegExp(`^${name} — (Sil|Delete)`, 'i') })).toBeInTheDocument()
      expect(screen.getByRole('button', { name: new RegExp(`^${name} — (Düzenle|Edit)`, 'i') })).toBeInTheDocument()
    }
    expect(screen.getByRole('button', { name: /^Ağ bakımı — (Sürdür|Devam|Resume)/i })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /^(Sil|Delete)$/i })).toBeNull()
  })
})
