import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent } from './test-utils.jsx'
import MyAuditLog from '../components/MyAuditLog.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  api: withApiFallback({ me: {
    getMyAudit: vi.fn(),
    // Cihaz Gecmisi artik VARSAYILAN gorunum; panel mount olunca bu uclari cagirir.
    getMyDevices: vi.fn(),
    getMyDeviceLogins: vi.fn(),
  } }),
  formatDate: (s) => s ?? '',
  formatDateSec: (s) => s ?? '',
}))
import { api } from '../api/client'

const ROW = {
  event_time: '2026-08-12T10:15:00',
  event_type: 'LOGIN',
  outcome: 'SUCCESS',
  resource_type: null,
  resource_id: null,
  ip_address: '10.0.0.1',
}

describe('MyAuditLog', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    sessionStorage.clear()
    api.me.getMyAudit.mockResolvedValue({ success: true, data: [ROW], total: 1, page: 0 })
    api.me.getMyDevices.mockResolvedValue({ success: true, data: { current: {}, remembered: [] } })
    api.me.getMyDeviceLogins.mockResolvedValue({ success: true, data: { rows: [], total: 0, page: 0 } })
  })

  /** Denetim gorunumune gec — sayfa artik ikiye ayrildi (varsayilan: Cihaz Gecmisi). */
  function openAuditView() {
    fireEvent.click(screen.getByText(/My audit log|Denetim Kayıtlarım/i))
  }

  it('kendi denetim kaydını listeler (denetim gorunumunde)', async () => {
    // Sayfa K1a ile ikiye ayrildi; TABLONUN DAVRANISI degismedi, yalniz bir sekme arkasinda.
    render(<MyAuditLog />)
    openAuditView()
    await waitFor(() => expect(screen.getByText('LOGIN')).toBeInTheDocument())
    expect(screen.getByText('10.0.0.1')).toBeInTheDocument()
  })

  it('VARSAYILAN gorunum Cihaz Gecmisi (guvenlik sorusu ham olay listesinden once)', async () => {
    render(<MyAuditLog />)
    await waitFor(() => expect(api.me.getMyDevices).toHaveBeenCalled())
    // Denetim tablosu henuz cizilmemis olmali.
    expect(screen.queryByText('10.0.0.1')).not.toBeInTheDocument()
  })

  it('loginInfo verilmediğinde sayfa çalışmaya devam eder (özet blok yok)', async () => {
    render(<MyAuditLog />)
    await waitFor(() => expect(api.me.getMyAudit).toHaveBeenCalled())
    expect(screen.queryByText(/My Sign-in Info/i)).not.toBeInTheDocument()
  })

  it('loginInfo varsa ham listeden ÖNCE giriş güvenliği özeti gösterilir', async () => {
    render(<MyAuditLog loginInfo={{
      prev_login_at: '2026-08-10T09:00:00',
      prev_login_ip: '10.0.0.9',
      failed_before_login: 2,
      last_failed_at: '2026-08-11T10:00:00',
      last_failed_ip: '10.0.0.8',
      last_failed_reason: 'BAD_PASSWORD',
      current_login_at: '2026-08-12T11:00:00',
      first_login: false,
    }} />)
    expect(screen.getByText(/My Sign-in Info/i)).toBeInTheDocument()
    expect(screen.getByText('2026-08-10T09:00:00')).toBeInTheDocument()
    expect(screen.getByText('2')).toBeInTheDocument()
    await waitFor(() => expect(api.me.getMyAudit).toHaveBeenCalled())
  })
})
