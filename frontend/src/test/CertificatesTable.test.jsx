import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent } from './test-utils.jsx'
import CertificatesTable from '../components/CertificatesTable.jsx'

// api istemcisini mock'la — component mount'ta getCertificatesPaginated çağırır.
// Durum sınıfları (status-valid/critical/error) dilden BAĞIMSIZ CSS sınıfı → i18n metnine bağlanmadan doğrulanır.
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  api: withApiFallback({ getCertificatesPaginated: vi.fn() }),
  formatDate: (s) => s || 'N/A',
}))

import { api } from '../api/client'

function paged(data) {
  return { success: true, data, pagination: { current_page: 1, total: data.length, total_pages: 1 } }
}

const cert = (over) => ({
  domain: 'x.com', issuer_cn: 'Test CA', subject: 'CN=x', not_after: '2027-01-01T00:00:00',
  days_remaining: 200, warning: false, status: 'valid', checked_at: '2026-07-01T00:00:00', ...over,
})

describe('CertificatesTable', () => {
  beforeEach(() => vi.clearAllMocks())

  it('sertifika satırlarını alan adı + duruma göre doğru CSS sınıfıyla gösterir', async () => {
    api.getCertificatesPaginated.mockResolvedValue(paged([
      cert({ domain: 'valid.com', days_remaining: 200, warning: false, status: 'valid' }),
      cert({ domain: 'crit.com', days_remaining: 10, warning: true, status: 'warning' }),   // 0..30 → KRİTİK (warning'i ezer)
      cert({ domain: 'err.com', days_remaining: null, warning: true, status: 'error' }),
    ]))

    const { container } = render(<CertificatesTable onRowClick={() => {}} />)

    await screen.findByText('valid.com')
    expect(screen.getByText('crit.com')).toBeInTheDocument()
    expect(screen.getByText('err.com')).toBeInTheDocument()

    // days_remaining 10 → KRİTİK sınıfı (valid/warning değil)
    expect(container.querySelector('tr[data-domain="crit.com"] .status-critical')).not.toBeNull()
    expect(container.querySelector('tr[data-domain="valid.com"] .status-valid')).not.toBeNull()
    expect(container.querySelector('tr[data-domain="err.com"] .status-error')).not.toBeNull()
  })

  it('satıra tıklayınca onRowClick alan adıyla çağrılır', async () => {
    const onRowClick = vi.fn()
    api.getCertificatesPaginated.mockResolvedValue(paged([cert({ domain: 'click.com' })]))

    const { container } = render(<CertificatesTable onRowClick={onRowClick} />)
    await screen.findByText('click.com')

    fireEvent.click(container.querySelector('tr[data-domain="click.com"]'))
    expect(onRowClick).toHaveBeenCalledWith('click.com')
  })

  it('boş veri → hiç sertifika satırı çizilmez (boş durum)', async () => {
    api.getCertificatesPaginated.mockResolvedValue(paged([]))

    const { container } = render(<CertificatesTable onRowClick={() => {}} />)

    await waitFor(() => expect(api.getCertificatesPaginated).toHaveBeenCalled())
    await waitFor(() => expect(container.querySelectorAll('tr[data-domain]')).toHaveLength(0))
  })
})
