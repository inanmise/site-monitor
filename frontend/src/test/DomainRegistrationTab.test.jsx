import { render, screen, waitFor, fireEvent } from './test-utils.jsx'
import DomainRegistrationTab from '../components/DomainRegistrationTab.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  formatDateSec: (s) => s ?? '',
  api: withApiFallback({
    monitoring: {
      getDomainRegistration: vi.fn(),
      getDomainHistory: vi.fn(),
    },
  }),
}))
import { api } from '../api/client'

describe('DomainRegistrationTab', () => {
  beforeEach(() => {
    api.monitoring.getDomainRegistration.mockResolvedValue({
      success: true,
      data: {
        domain: 'example.com', registrar: 'Example Registrar, LLC', registrar_iana_id: '9999',
        dnssec: 'signed', source: 'RDAP', days_remaining: 25, expiry_date: '2026-08-06T12:37:46Z',
        registration_date: '2010-01-01', last_changed: '2025-01-01',
        nameservers: ['ns1.example.com'], resolved_ips: ['1.2.3.4'], hostnames: ['host.example.com'],
        status_codes: ['client transfer prohibited'], checked_at: '2026-07-12T06:00:00Z',
      },
    })
    api.monitoring.getDomainHistory.mockResolvedValue({ success: true, data: { checks: [] } })
  })

  // K-1 (2026-09-29): sekmeyi AÇMAK canlı sorgu yapmaz — elle kayıt DOMAINMON_CHANGED tabanını tüketiyordu.
  it('reads the STORED registration on mount (no live query) and renders registrar + IANA ID + IP + EPP', async () => {
    render(<DomainRegistrationTab monitor={{ id: 7, domain: 'example.com' }} />)
    await waitFor(() =>
      expect(api.monitoring.getDomainRegistration).toHaveBeenCalledWith(7, { live: false }))
    expect(api.monitoring.getDomainRegistration).not.toHaveBeenCalledWith(7, { live: true })
    expect(await screen.findByText('Example Registrar, LLC')).toBeInTheDocument()
    expect(screen.getByText('9999')).toBeInTheDocument()
    expect(screen.getByText('1.2.3.4')).toBeInTheDocument()
    expect(screen.getByText('client transfer prohibited')).toBeInTheDocument()
  })

  it('runs the live query ONLY when the user presses Refresh', async () => {
    render(<DomainRegistrationTab monitor={{ id: 7, domain: 'example.com' }} />)
    fireEvent.click(await screen.findByRole('button', { name: /^(Yenile|Refresh)$/ }))
    await waitFor(() =>
      expect(api.monitoring.getDomainRegistration).toHaveBeenCalledWith(7, { live: true }))
  })

  it('falls back to stored data when the live query fails', async () => {
    const stored = { success: true, data: { domain: 'example.com', registrar: 'Example Registrar, LLC', source: 'RDAP', status_codes: [], nameservers: [], resolved_ips: [], hostnames: [], checked_at: '2026-07-12T06:00:00Z' } }
    // Çağrı SAYISINA değil argümana göre (StrictMode açılış efektini iki kez koşturabilir): canlı sorgu başarısız, kayıtlı bilgi var.
    api.monitoring.getDomainRegistration.mockImplementation((_id, opts) =>
      Promise.resolve(opts?.live ? { success: false, error: 'boom' } : stored))
    render(<DomainRegistrationTab monitor={{ id: 7, domain: 'example.com' }} />)
    fireEvent.click(await screen.findByRole('button', { name: /^(Yenile|Refresh)$/ }))
    expect(await screen.findByText(/Live query failed|Anlık sorgu başarısız/)).toBeInTheDocument()
    expect(api.monitoring.getDomainRegistration).toHaveBeenCalledWith(7, { live: true })
    expect(screen.getByText('Example Registrar, LLC')).toBeInTheDocument()
  })
})
