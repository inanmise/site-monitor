import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  api: withApiFallback({ getSharedCertificate: vi.fn() }),
  formatDate: (s) => 'D(' + s + ')',
  formatDateSec: (s) => 'T(' + s + ')',
}))

import { api } from '../api/client'
import SharedCertificateModal from '../components/SharedCertificateModal.jsx'

/** Paylaşılan sertifika penceresi (2026-09-22): eş listesi bağlamıyla, bu kart işaretli, kapsam dışı sayısı, alan seçimi. */
describe('SharedCertificateModal', () => {
  const data = {
    domain: 'a.example.com', fingerprint: 'AA:BB', subject: 'CN=*.example.com', issuer: 'DigiCert', not_after: '2027-01-01T00:00:00',
    days_remaining: 100, san: ['a.example.com', 'b.example.com'], hidden: 2,
    peers: [
      { domain: 'a.example.com', self: true, status: 'valid', days_remaining: 100, not_after: '2027-01-01T00:00:00', checked_at: '2026-09-22T10:00:00', port: 443, tier: 1, team_id: 5, team_name: 'Takım A', platform: 'IIS', in_inventory: true },
      { domain: 'b.example.com', self: false, status: 'valid', days_remaining: 5, not_after: '2026-09-27T00:00:00', checked_at: '2026-09-22T09:00:00', port: 8443, tier: null, team_name: null, platform: null, in_inventory: false },
    ],
  }
  beforeEach(() => { vi.clearAllMocks(); api.getSharedCertificate.mockResolvedValue({ success: true, data }) })

  it('sertifika künyesi + eş tablosu: bu kart işaretli, envanterde olmayan rozetli, kritik kalan gün vurgulu, kapsam dışı sayısı', async () => {
    render(<SharedCertificateModal domain="a.example.com" onClose={() => {}} />)
    await waitFor(() => expect(screen.getByTestId('shc-table')).toBeInTheDocument())
    expect(api.getSharedCertificate).toHaveBeenCalledWith('a.example.com')
    expect(screen.getByText('CN=*.example.com')).toBeInTheDocument()
    expect(screen.getByText('DigiCert')).toBeInTheDocument()
    const rows = screen.getByTestId('shc-table').querySelectorAll('tbody tr')
    expect(rows).toHaveLength(2)
    expect(rows[0].classList.contains('shc-self')).toBe(true)
    expect(rows[0].textContent).toMatch(/bu kart|this card/)
    expect(rows[1].textContent).toMatch(/envanterde değil|not in inventory/)
    expect(rows[1].querySelector('.shc-crit')).not.toBeNull()   // 5 gün
    expect(screen.getByText(/2 alan görüş kapsamınız dışında|2 domains are outside/)).toBeInTheDocument()
    expect(screen.getByText(':8443', { exact: false })).toBeInTheDocument()
  })

  it('alan adına tıklayınca o alan seçilir ve pencere kapanır; hata durumunda uyarı bandı', async () => {
    const onClose = vi.fn(); const onSelectDomain = vi.fn()
    const { unmount } = render(<SharedCertificateModal domain="a.example.com" onClose={onClose} onSelectDomain={onSelectDomain} />)
    await waitFor(() => expect(screen.getByTestId('shc-table')).toBeInTheDocument())
    fireEvent.click(screen.getByTestId('shc-table').querySelectorAll('tbody tr')[1].querySelector('.inv-domain'))
    expect(onSelectDomain).toHaveBeenCalledWith('b.example.com')
    expect(onClose).toHaveBeenCalled()
    unmount()
    api.getSharedCertificate.mockResolvedValue({ success: false, error: 'boom' })
    render(<SharedCertificateModal domain="x.example.com" onClose={() => {}} />)
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('boom'))
  })
})
