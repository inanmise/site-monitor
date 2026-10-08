import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  api: withApiFallback({ getSharedCertificate: vi.fn() }),
  formatDate: (s) => 'D(' + s + ')',
  formatDateSec: (s) => 'T(' + s + ')',
}))
const exp = vi.hoisted(() => ({ exportSharedPdf: vi.fn(), exportSharedXlsx: vi.fn() }))
vi.mock('../components/sharedcert/sharedCertExport.js', () => exp)

import { api } from '../api/client'
import SharedCertificateModal from '../components/SharedCertificateModal.jsx'

/**
 * Paylaşılan sertifika penceresi — takıma / gruba göre görünüm + PDF / Excel (2026-10-08, kullanıcı: "kartta 'x alan
 * adında ortak SAN' yazıyor; tıklayınca açılan pencerede takım bazlı, grup bazlı görebilmeliyim; PDF ve Excel olarak
 * dışa alabilmeliyim").
 */
const data = {
  domain: 'a.example.com', fingerprint: 'AA:BB', subject: 'CN=*.example.com', issuer: 'Example CA', not_after: '2027-01-01T00:00:00',
  days_remaining: 100, san: ['a.example.com', 'b.example.com', 'c.example.com'], hidden: 0,
  peers: [
    { domain: 'a.example.com', self: true, days_remaining: 100, team_id: 2, team_name: 'Takım B', group_name: 'Ödeme', in_inventory: true },
    { domain: 'b.example.com', days_remaining: 40, team_id: 1, team_name: 'Takım A', group_name: null, in_inventory: true },
    { domain: 'c.example.com', days_remaining: 40, team_name: null, group_name: 'Ödeme', in_inventory: false },
  ],
}
const sectionLabels = () => [...document.querySelectorAll('[data-slot="shc-section"]')].map((r) => r.textContent)
const rowsText = () => [...screen.getByTestId('shc-table').querySelectorAll('tbody tr')].map((r) => (r.getAttribute('data-slot') === 'shc-section' ? `# ${r.getAttribute('data-section')}` : r.querySelector('[data-shc-domain]')?.textContent.trim()))

describe('SharedCertificateModal — takım / grup görünümü ve dışa aktarma', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.getSharedCertificate.mockResolvedValue({ success: true, data })
  })

  it('varsayılan liste: bölüm başlığı yok, Grup sütunu var', async () => {
    render(<SharedCertificateModal domain="a.example.com" onClose={() => {}} />)
    await screen.findByTestId('shc-table')
    expect(screen.getByRole('button', { name: 'List' })).toHaveAttribute('aria-pressed', 'true')
    expect(sectionLabels()).toEqual([])
    expect(screen.getByRole('columnheader', { name: 'Group' })).toBeInTheDocument()
    expect(document.querySelectorAll('[data-slot="shc-group"]')[0]).toHaveTextContent('Ödeme')
  })

  it('takıma göre: bölümler A→Z, takımsız SONDA, her başlıkta alan sayısı', async () => {
    render(<SharedCertificateModal domain="a.example.com" onClose={() => {}} />)
    await screen.findByTestId('shc-table')
    fireEvent.click(screen.getByRole('button', { name: 'By team' }))
    expect(rowsText()).toEqual(['# Takım A', 'b.example.com', '# Takım B', 'a.example.com', '# none', 'c.example.com'])
    expect(sectionLabels()[2]).toMatch(/No team.*Domains: 1/)
  })

  it('gruba göre: Ödeme (2 alan) önce, grupsuz sonda', async () => {
    render(<SharedCertificateModal domain="a.example.com" onClose={() => {}} />)
    await screen.findByTestId('shc-table')
    fireEvent.click(screen.getByRole('button', { name: 'By group' }))
    expect(rowsText()).toEqual(['# Ödeme', 'a.example.com', 'c.example.com', '# none', 'b.example.com'])
    expect(sectionLabels()[0]).toMatch(/Ödeme.*Domains: 2/)
  })

  it('Excel / PDF: seçili görünümle dışa aktarır; hata olursa açıklayıcı uyarı', async () => {
    render(<SharedCertificateModal domain="a.example.com" onClose={() => {}} />)
    await screen.findByTestId('shc-table')
    fireEvent.click(screen.getByRole('button', { name: 'By group' }))
    fireEvent.click(screen.getByRole('button', { name: /Download Excel/ }))
    await waitFor(() => expect(exp.exportSharedXlsx).toHaveBeenCalledWith(data, 'group', expect.any(Function)))
    exp.exportSharedPdf.mockRejectedValueOnce(new Error('font'))
    fireEvent.click(screen.getByRole('button', { name: /Download PDF/ }))
    await waitFor(() => expect(exp.exportSharedPdf).toHaveBeenCalledWith(data, 'group', expect.any(Function)))
    expect(await screen.findByText(/The export file could not be created/)).toBeInTheDocument()
  })

  it('eş yoksa dışa aktarma düğmeleri kapalı', async () => {
    api.getSharedCertificate.mockResolvedValue({ success: true, data: { ...data, peers: [] } })
    render(<SharedCertificateModal domain="a.example.com" onClose={() => {}} />)
    await screen.findByText(/no other domain|başka alan yok/i)
    expect(screen.getByRole('button', { name: /Download PDF/ })).toBeDisabled()
    expect(screen.getByRole('button', { name: /Download Excel/ })).toBeDisabled()
  })
})
