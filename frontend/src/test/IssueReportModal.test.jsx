import { describe, it, expect, vi, beforeEach } from 'vitest'
import { useState } from 'react'
import { render, screen, fireEvent, waitFor } from './test-utils'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  api: withApiFallback({
    getMe: vi.fn(),
    sendIssueReport: vi.fn(),
  }),
  getRecentFailures: () => [{ path: '/api/monitoring/scripted/7/response-series', status: 500, at: '2026-08-07T00:00:00' }],
}))

// jsdom'da canvas.toBlob / createImageBitmap YOK — gerçek downscaleImage çağrılırsa test asılır.
vi.mock('../utils/imageDownscale', () => ({ downscaleImage: vi.fn(async (f) => f) }))

function png(name = 'a.png') { return new File(['x'], name, { type: 'image/png' }) }

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
    api.getMe.mockResolvedValue({ success: true, username: 'N1', email: 'ben@example.com' })
    render(<IssueReportModal open onClose={() => {}} />)
    await waitFor(() => expect(api.getMe).toHaveBeenCalled())
    expect(await screen.findByText('ben@example.com')).toBeInTheDocument()
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
    api.getMe.mockResolvedValue({ success: true, username: 'N1', email: 'ben@example.com' })
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
    api.getMe.mockResolvedValue({ success: true, username: 'N1', email: 'ben@example.com' })
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

  // ── Modal kabuğu davranışı (eskiden hiçbiri yoktu) ──────────────────────────

  it('dialog semantiği: role + başlığa bağlı aria-labelledby', async () => {
    api.getMe.mockResolvedValue({ success: true, username: 'N1', email: 'ben@example.com' })
    render(<IssueReportModal open onClose={() => {}} />)
    const dlg = await screen.findByRole('dialog')
    expect(dlg.getAttribute('aria-modal')).toBe('true')
    expect(document.getElementById(dlg.getAttribute('aria-labelledby')).textContent)
      .toContain('Report a Problem')
  })

  it('Escape kapatır; gönderim sürerken kapatmaz', async () => {
    api.getMe.mockResolvedValue({ success: true, username: 'N1', email: 'ben@example.com' })
    const onClose = vi.fn()
    // Gönderim asla bitmesin → busy durumu kalıcı olsun
    api.sendIssueReport.mockImplementation(() => new Promise(() => {}))
    render(<IssueReportModal open onClose={onClose} />)
    await waitFor(() => expect(api.getMe).toHaveBeenCalled())

    fireEvent.keyDown(document, { key: 'Escape' })
    expect(onClose).toHaveBeenCalledOnce()

    onClose.mockClear()
    fireEvent.change(screen.getByRole('textbox'), { target: { value: 'bir şey' } })
    fireEvent.click(screen.getByRole('button', { name: 'Send' }))
    await waitFor(() => expect(screen.getByRole('button', { name: /Sending/i })).toBeInTheDocument())
    fireEvent.keyDown(document, { key: 'Escape' })
    expect(onClose).not.toHaveBeenCalled()
  })

  it('gönderim sırasında butonda aria-busy + spinner vardır', async () => {
    api.getMe.mockResolvedValue({ success: true, username: 'N1', email: 'ben@example.com' })
    api.sendIssueReport.mockImplementation(() => new Promise(() => {}))
    render(<IssueReportModal open onClose={() => {}} />)
    await waitFor(() => expect(api.getMe).toHaveBeenCalled())
    fireEvent.change(screen.getByRole('textbox'), { target: { value: 'bir şey' } })
    fireEvent.click(screen.getByRole('button', { name: 'Send' }))
    const btn = await screen.findByRole('button', { name: /Sending/i })
    expect(btn.getAttribute('aria-busy')).toBe('true')
    expect(btn.querySelector('[data-slot="spinner"]')).toBeTruthy()
  })

  it('odak açılışta modala girer, kapanışta tetikleyiciye döner', async () => {
    api.getMe.mockResolvedValue({ success: true, username: 'N1', email: 'ben@example.com' })
    function Host() {
      const [open, setOpen] = useState(false)
      return (
        <>
          <button type="button" onClick={() => setOpen(true)}>Aç</button>
          <IssueReportModal open={open} onClose={() => setOpen(false)} />
        </>
      )
    }
    render(<Host />)
    const trigger = screen.getByRole('button', { name: 'Aç' })
    trigger.focus()
    fireEvent.click(trigger)
    await waitFor(() => expect(screen.getByRole('dialog').contains(document.activeElement)).toBe(true))
    fireEvent.keyDown(document, { key: 'Escape' })
    await waitFor(() => expect(document.activeElement).toBe(trigger))
  })

  // ── Form semantiği ──────────────────────────────────────────────────────────

  it('etiketler kontrollere bağlıdır (getByLabelText)', async () => {
    api.getMe.mockResolvedValue({ success: true, username: 'N1', email: null })
    render(<IssueReportModal open onClose={() => {}} />)
    await waitFor(() => expect(api.getMe).toHaveBeenCalled())
    expect(await screen.findByLabelText(/What happened/i)).toBeInTheDocument()
    expect(screen.getByLabelText(/Your email address/i)).toBeInTheDocument()
  })

  it('önem seçimi aria-pressed taşır; "Not specified" seçimi temizler', async () => {
    api.getMe.mockResolvedValue({ success: true, username: 'N1', email: 'ben@example.com' })
    render(<IssueReportModal open onClose={() => {}} />)
    await waitFor(() => expect(api.getMe).toHaveBeenCalled())

    const blocker = screen.getByRole('button', { name: 'Blocking me' })
    fireEvent.click(blocker)
    expect(blocker.getAttribute('aria-pressed')).toBe('true')

    fireEvent.click(screen.getByRole('button', { name: 'Not specified' }))
    expect(blocker.getAttribute('aria-pressed')).toBe('false')

    fireEvent.change(screen.getByRole('textbox'), { target: { value: 'x' } })
    fireEvent.click(screen.getByRole('button', { name: 'Send' }))
    await waitFor(() => expect(api.sendIssueReport).toHaveBeenCalled())
    expect(api.sendIssueReport.mock.calls[0][0].category).toBeUndefined()
  })

  it('geçersiz e-posta formatı gönderimi engeller ve alanı işaretler', async () => {
    api.getMe.mockResolvedValue({ success: true, username: 'N1', email: null })
    render(<IssueReportModal open onClose={() => {}} />)
    await waitFor(() => expect(api.getMe).toHaveBeenCalled())
    fireEvent.change(await screen.findByLabelText(/What happened/i), { target: { value: 'bir şey' } })
    fireEvent.change(screen.getByLabelText(/Your email address/i), { target: { value: 'bozuk-adres' } })
    fireEvent.click(screen.getByRole('button', { name: 'Send' }))

    expect(await screen.findByText('Enter a valid email address')).toBeInTheDocument()
    expect(api.sendIssueReport).not.toHaveBeenCalled()
    expect(screen.getByLabelText(/Your email address/i).getAttribute('aria-invalid')).toBe('true')
  })

  it('sunucu hatası tek bir alert olarak gösterilir', async () => {
    api.getMe.mockResolvedValue({ success: true, username: 'N1', email: 'ben@example.com' })
    api.sendIssueReport.mockResolvedValue({ success: false, error: 'Sunucu reddetti' })
    render(<IssueReportModal open onClose={() => {}} />)
    await waitFor(() => expect(api.getMe).toHaveBeenCalled())
    fireEvent.change(screen.getByRole('textbox'), { target: { value: 'x' } })
    fireEvent.click(screen.getByRole('button', { name: 'Send' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Sunucu reddetti')
    expect(screen.getAllByRole('alert')).toHaveLength(1)
  })

  // ── Ekran görüntüleri ───────────────────────────────────────────────────────

  it('sürükle-bırak görsel ekler ve silinebilir', async () => {
    api.getMe.mockResolvedValue({ success: true, username: 'N1', email: 'ben@example.com' })
    const { container } = render(<IssueReportModal open onClose={() => {}} />)
    await waitFor(() => expect(api.getMe).toHaveBeenCalled())

    const zone = container.ownerDocument.querySelector('.issue-dropzone')
    fireEvent.drop(zone, { dataTransfer: { files: [png()], types: ['Files'] } })

    expect(await screen.findByRole('button', { name: /Remove screenshot 1/i })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /Remove screenshot 1/i }))
    await waitFor(() => expect(screen.queryByRole('button', { name: /Remove screenshot 1/i })).toBeNull())
  })

  it('desteklenmeyen dosya SESSİZCE atılmaz — görünür uyarı çıkar', async () => {
    api.getMe.mockResolvedValue({ success: true, username: 'N1', email: 'ben@example.com' })
    const { container } = render(<IssueReportModal open onClose={() => {}} />)
    await waitFor(() => expect(api.getMe).toHaveBeenCalled())

    const pdf = new File(['x'], 'rapor.pdf', { type: 'application/pdf' })
    fireEvent.drop(container.ownerDocument.querySelector('.issue-dropzone'),
      { dataTransfer: { files: [pdf], types: ['Files'] } })

    expect(await screen.findByText(/only PNG and JPEG are supported/i)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Remove screenshot/i })).toBeNull()
  })

  it('5 görsel sınırı aşılınca uyarı çıkar (fazlası sessizce kırpılmaz)', async () => {
    api.getMe.mockResolvedValue({ success: true, username: 'N1', email: 'ben@example.com' })
    const { container } = render(<IssueReportModal open onClose={() => {}} />)
    await waitFor(() => expect(api.getMe).toHaveBeenCalled())

    const six = Array.from({ length: 6 }, (_, i) => png(`s${i}.png`))
    fireEvent.drop(container.ownerDocument.querySelector('.issue-dropzone'),
      { dataTransfer: { files: six, types: ['Files'] } })

    expect(await screen.findByText(/At most 5 images/i)).toBeInTheDocument()
    await waitFor(() =>
      expect(screen.getAllByRole('button', { name: /Remove screenshot/i })).toHaveLength(5))
  })

  it('küçük resme tıklayınca iç içe büyütme penceresi açılır ve Escape yalnız onu kapatır', async () => {
    api.getMe.mockResolvedValue({ success: true, username: 'N1', email: 'ben@example.com' })
    const onClose = vi.fn()
    const { container } = render(<IssueReportModal open onClose={onClose} />)
    await waitFor(() => expect(api.getMe).toHaveBeenCalled())

    fireEvent.drop(container.ownerDocument.querySelector('.issue-dropzone'),
      { dataTransfer: { files: [png()], types: ['Files'] } })
    fireEvent.click(await screen.findByRole('button', { name: /Enlarge screenshot 1/i }))

    await waitFor(() => expect(screen.getAllByRole('dialog')).toHaveLength(2))
    fireEvent.keyDown(document, { key: 'Escape' })
    await waitFor(() => expect(screen.getAllByRole('dialog')).toHaveLength(1))
    expect(onClose).not.toHaveBeenCalled()   // dıştaki modal AÇIK kalır
  })
})
