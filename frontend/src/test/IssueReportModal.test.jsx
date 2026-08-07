import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils'

vi.mock('../api/client', () => ({
  api: {
    getMe: vi.fn(),
    sendIssueReport: vi.fn(),
  },
  getRecentFailures: () => [{ path: '/api/monitoring/scripted/7/response-series', status: 500, at: '2026-08-07T00:00:00' }],
}))

import { api } from '../api/client'
import IssueReportModal from '../components/IssueReportModal.jsx'

/** Kural 1'in testi: otomatik toplanan bağlam (kim/ne zaman/nerede/sürüm/tema) kullanıcıya
 *  FORM ALANI olarak sorulmaz — yalnız readonly özet. Kullanıcıya sorulanlar: açıklama (zorunlu),
 *  önem (ops), görsel (ops), profilde yoksa e-posta. */
describe('IssueReportModal', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.sendIssueReport.mockResolvedValue({ success: true, reference: 'LIR-2026-000099' })
  })

  it('profil e-postası VARKEN: e-posta alanı SORULMAZ, readonly bilgi satırı görünür', async () => {
    api.getMe.mockResolvedValue({ success: true, username: 'N1', email: 'ben@akbank.com' })
    render(<IssueReportModal open onClose={() => {}} />)
    await waitFor(() => expect(api.getMe).toHaveBeenCalled())
    expect(await screen.findByText('ben@akbank.com')).toBeInTheDocument()
    // e-posta input'u yok (test EN locale: "Your email address")
    expect(screen.queryByPlaceholderText('you@company.com')).toBeNull()
  })

  it('profil e-postası YOKKEN: zorunlu e-posta alanı + "profilime kaydet" onay kutusu çıkar', async () => {
    api.getMe.mockResolvedValue({ success: true, username: 'N1', email: null })
    render(<IssueReportModal open onClose={() => {}} />)
    await waitFor(() => expect(api.getMe).toHaveBeenCalled())
    expect(await screen.findByPlaceholderText('you@company.com')).toBeInTheDocument()
    expect(screen.getByText('Save this address to my profile')).toBeInTheDocument()
    // Açıklama + e-posta girilmeden gönderim reddedilir (istemci tarafı)
    fireEvent.click(screen.getByRole('button', { name: 'Send' }))
    expect(await screen.findByText('Description is required')).toBeInTheDocument()
    expect(api.sendIssueReport).not.toHaveBeenCalled()
  })

  it('kural 1: otomatik bağlam readonly özettedir — kullanıcıdan URL/sürüm/tema İSTEYEN input yoktur', async () => {
    api.getMe.mockResolvedValue({ success: true, username: 'N1', email: 'ben@akbank.com' })
    render(<IssueReportModal open onClose={() => {}} />)
    await waitFor(() => expect(api.getMe).toHaveBeenCalled())
    // Readonly özet başlığı var
    expect(screen.getByText(/Automatically included info/i)).toBeInTheDocument()
    // Son başarısız istekler özette görünür (halka tamponundan)
    expect(screen.getByText(/response-series/)).toBeInTheDocument()
    // Form alanları: yalnız açıklama (textarea) — URL/sürüm/tema için input YOK
    const textboxes = screen.getAllByRole('textbox')
    expect(textboxes).toHaveLength(1)   // yalnız açıklama textarea'sı
  })

  it('gönderim: payload otomatik bağlamı taşır; başarıda referans no gösterilir', async () => {
    api.getMe.mockResolvedValue({ success: true, username: 'N1', email: 'ben@akbank.com' })
    render(<IssueReportModal open onClose={() => {}} errorText="TypeError: boom" linkedReference="LIR-2026-000077" />)
    await waitFor(() => expect(api.getMe).toHaveBeenCalled())
    fireEvent.change(screen.getByRole('textbox'), { target: { value: 'Grafik açılınca ekran çöktü' } })
    fireEvent.click(screen.getByText('Blocking me'))
    fireEvent.click(screen.getByRole('button', { name: 'Send' }))
    await waitFor(() => expect(api.sendIssueReport).toHaveBeenCalled())
    const dto = api.sendIssueReport.mock.calls[0][0]
    expect(dto.message).toBe('Grafik açılınca ekran çöktü')
    expect(dto.category).toBe('BLOCKER')
    expect(dto.errorText).toBe('TypeError: boom')
    expect(dto.linkedReference).toBe('LIR-2026-000077')
    expect(dto.email).toBeUndefined()          // profil e-postası varken payload'da e-posta YOK
    expect(typeof dto.url).toBe('string')
    expect(Array.isArray(dto.failedRequests)).toBe(true)
    expect(await screen.findByText('LIR-2026-000099')).toBeInTheDocument()
  })
})
