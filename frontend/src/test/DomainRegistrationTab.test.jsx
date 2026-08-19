import { render, screen, waitFor } from './test-utils.jsx'
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
        domain: 'kartfree.com', registrar: 'GoDaddy.com, LLC', registrar_iana_id: '146',
        dnssec: 'signed', source: 'RDAP', days_remaining: 25, expiry_date: '2026-08-06T12:37:46Z',
        registration_date: '2010-01-01', last_changed: '2025-01-01',
        nameservers: ['ns1.example.com'], resolved_ips: ['1.2.3.4'], hostnames: ['host.example.com'],
        status_codes: ['client transfer prohibited'], checked_at: '2026-07-12T06:00:00Z',
      },
    })
    api.monitoring.getDomainHistory.mockResolvedValue({ success: true, data: { checks: [] } })
  })

  it('fetches live registration on mount and renders registrar + IANA ID + IP + EPP', async () => {
    render(<DomainRegistrationTab monitor={{ id: 7, domain: 'kartfree.com' }} />)
    await waitFor(() =>
      expect(api.monitoring.getDomainRegistration).toHaveBeenCalledWith(7, { live: true }))
    expect(await screen.findByText('GoDaddy.com, LLC')).toBeInTheDocument()
    expect(screen.getByText('146')).toBeInTheDocument()
    expect(screen.getByText('1.2.3.4')).toBeInTheDocument()
    expect(screen.getByText('client transfer prohibited')).toBeInTheDocument()
  })

  it('falls back to stored data when the live query fails', async () => {
    api.monitoring.getDomainRegistration
      .mockResolvedValueOnce({ success: false, error: 'boom' })                 // live
      .mockResolvedValueOnce({ success: true, data: { domain: 'kartfree.com', registrar: 'GoDaddy.com, LLC', source: 'RDAP', status_codes: [], nameservers: [], resolved_ips: [], hostnames: [], checked_at: '2026-07-12T06:00:00Z' } }) // fallback
    render(<DomainRegistrationTab monitor={{ id: 7, domain: 'kartfree.com' }} />)
    await waitFor(() => expect(api.monitoring.getDomainRegistration).toHaveBeenCalledTimes(2))
    expect(await screen.findByText('GoDaddy.com, LLC')).toBeInTheDocument()
  })
})
