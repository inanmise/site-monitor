import { describe, it, expect, vi, beforeEach } from 'vitest'
import { useState } from 'react'
import { render, screen, fireEvent, waitFor, within } from './test-utils'

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
    fireEvent.click(screen.getByRole('button', { name: 'Send report' }))
    expect(await screen.findByText('Description is required')).toBeInTheDocument()
    expect(api.sendIssueReport).not.toHaveBeenCalled()
  })

  it('"profilime kaydet" gerçek bir onay kutusu (shadcn Checkbox, etiketle bağlı); kaldırılınca false gider', async () => {
    api.getMe.mockResolvedValue({ success: true, username: 'N1', email: null })
    render(<IssueReportModal open onClose={() => {}} />)
    const box = await screen.findByRole('checkbox', { name: 'Save this address to my profile' })
    expect(box).toBeChecked()
    fireEvent.click(screen.getByText('Save this address to my profile'))   // etikete basmak kutuyu çevirir
    expect(box).not.toBeChecked()
    fireEvent.change(screen.getByLabelText(/Your email address/i), { target: { value: 'ben@example.com' } })
    fireEvent.change(screen.getAllByRole('textbox')[0], { target: { value: 'bir şey' } })
    fireEvent.click(screen.getByRole('button', { name: 'Send report' }))
    await waitFor(() => expect(api.sendIssueReport).toHaveBeenCalledWith(
      expect.objectContaining({ email: 'ben@example.com', saveEmailToProfile: false })))
  })

  it('kural 1: otomatik bağlam readonly özettedir — kullanıcıdan URL/sürüm/tema İSTEYEN input yoktur', async () => {
    api.getMe.mockResolvedValue({ success: true, username: 'N1', email: 'ben@example.com' })
    render(<IssueReportModal open onClose={() => {}} />)
    await waitFor(() => expect(api.getMe).toHaveBeenCalled())
    // Readonly özet katlanır "eklenecek teknik ayrıntılar" bölümünde — varsayılan kapalı, açınca görünür
    const tech = screen.getByRole('button', { name: /Technical details we’ll attach/i })
    expect(tech).toHaveAttribute('aria-expanded', 'false')
    expect(screen.queryByText(/response-series/)).toBeNull()
    fireEvent.click(tech)
    // Son başarısız istekler özette görünür (halka tamponundan)
    expect(await screen.findByText(/response-series/)).toBeInTheDocument()
    // Gizlilik notu neyin ASLA eklenmediğini söyler
    expect(screen.getByText(/never attach passwords, cookies/i)).toBeInTheDocument()
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
    fireEvent.click(screen.getByRole('button', { name: 'Send report' }))
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
    fireEvent.click(screen.getByRole('button', { name: 'Send report' }))
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
    fireEvent.click(screen.getByRole('button', { name: 'Send report' }))
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

  it('önem = seçim kartları (RadioGroup, açıklamalı); "Seçimi temizle" belirtilmemiş hâle döndürür', async () => {
    api.getMe.mockResolvedValue({ success: true, username: 'N1', email: 'ben@example.com' })
    render(<IssueReportModal open onClose={() => {}} />)
    await waitFor(() => expect(api.getMe).toHaveBeenCalled())

    const group = screen.getByRole('radiogroup', { name: /Severity/i })
    expect(group).toHaveAttribute('data-slot', 'issue-category-cards')
    const blocker = screen.getByRole('radio', { name: /Blocking me/ })
    expect(screen.queryByRole('button', { name: 'Clear selection' })).toBeNull()   // seçim yokken temizle yok
    fireEvent.click(blocker)
    expect(blocker).toBeChecked()
    // Kart açıklaması görünür metin (ipucuna saklanmaz)
    expect(screen.getByText('I can’t get on with my work')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Clear selection' }))
    expect(blocker).not.toBeChecked()

    fireEvent.change(screen.getByRole('textbox'), { target: { value: 'x' } })
    fireEvent.click(screen.getByRole('button', { name: 'Send report' }))
    await waitFor(() => expect(api.sendIssueReport).toHaveBeenCalled())
    expect(api.sendIssueReport.mock.calls[0][0].category).toBeUndefined()
  })

  it('geçersiz e-posta formatı gönderimi engeller ve alanı işaretler', async () => {
    api.getMe.mockResolvedValue({ success: true, username: 'N1', email: null })
    render(<IssueReportModal open onClose={() => {}} />)
    await waitFor(() => expect(api.getMe).toHaveBeenCalled())
    fireEvent.change(await screen.findByLabelText(/What happened/i), { target: { value: 'bir şey' } })
    fireEvent.change(screen.getByLabelText(/Your email address/i), { target: { value: 'bozuk-adres' } })
    fireEvent.click(screen.getByRole('button', { name: 'Send report' }))

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
    fireEvent.click(screen.getByRole('button', { name: 'Send report' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Sunucu reddetti')
    expect(screen.getAllByRole('alert')).toHaveLength(1)
  })

  // ── Ekran görüntüleri ───────────────────────────────────────────────────────

  it('sürükle-bırak görsel ekler ve silinebilir', async () => {
    api.getMe.mockResolvedValue({ success: true, username: 'N1', email: 'ben@example.com' })
    const { container } = render(<IssueReportModal open onClose={() => {}} />)
    await waitFor(() => expect(api.getMe).toHaveBeenCalled())

    const zone = container.ownerDocument.querySelector('[data-slot="issue-dropzone"]')
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
    fireEvent.drop(container.ownerDocument.querySelector('[data-slot="issue-dropzone"]'),
      { dataTransfer: { files: [pdf], types: ['Files'] } })

    expect(await screen.findByText(/only PNG and JPEG are supported/i)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Remove screenshot/i })).toBeNull()
  })

  it('5 görsel sınırı aşılınca uyarı çıkar (fazlası sessizce kırpılmaz)', async () => {
    api.getMe.mockResolvedValue({ success: true, username: 'N1', email: 'ben@example.com' })
    const { container } = render(<IssueReportModal open onClose={() => {}} />)
    await waitFor(() => expect(api.getMe).toHaveBeenCalled())

    const six = Array.from({ length: 6 }, (_, i) => png(`s${i}.png`))
    fireEvent.drop(container.ownerDocument.querySelector('[data-slot="issue-dropzone"]'),
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

    fireEvent.drop(container.ownerDocument.querySelector('[data-slot="issue-dropzone"]'),
      { dataTransfer: { files: [png()], types: ['Files'] } })
    fireEvent.click(await screen.findByRole('button', { name: /Enlarge screenshot 1/i }))

    await waitFor(() => expect(screen.getAllByRole('dialog')).toHaveLength(2))
    fireEvent.keyDown(document, { key: 'Escape' })
    await waitFor(() => expect(screen.getAllByRole('dialog')).toHaveLength(1))
    expect(onClose).not.toHaveBeenCalled()   // dıştaki modal AÇIK kalır
  })

  // ── 2026-09-27 rehberli form: bölümler, sayaç, yapıştırma, Ctrl+Enter, hata/başarı ekranları ─────────

  it('numaralı bölümler + canlı karakter sayacı (5000)', async () => {
    api.getMe.mockResolvedValue({ success: true, username: 'N1', email: 'ben@example.com' })
    render(<IssueReportModal open onClose={() => {}} />)
    await waitFor(() => expect(api.getMe).toHaveBeenCalled())
    const dlg = screen.getByRole('dialog')
    expect(dlg.querySelectorAll('[data-slot="report-section"]')).toHaveLength(4)
    expect(screen.getByRole('group', { name: /Describe the problem/ })).toBeInTheDocument()
    fireEvent.change(screen.getByRole('textbox'), { target: { value: 'abcde' } })
    expect(dlg.querySelector('[data-slot="char-counter"]').textContent).toBe('5 / 5000')
    expect(screen.getByRole('textbox')).toHaveAttribute('maxlength', '5000')
  })

  it('boş gönderim: satır içi hata + alan aria-invalid; API çağrılmaz', async () => {
    api.getMe.mockResolvedValue({ success: true, username: 'N1', email: 'ben@example.com' })
    render(<IssueReportModal open onClose={() => {}} />)
    await waitFor(() => expect(api.getMe).toHaveBeenCalled())
    fireEvent.click(screen.getByRole('button', { name: 'Send report' }))
    expect(await screen.findByText('Description is required')).toBeInTheDocument()
    expect(screen.getByRole('textbox')).toHaveAttribute('aria-invalid', 'true')
    expect(api.sendIssueReport).not.toHaveBeenCalled()
  })

  it('Ctrl+Enter gönderir', async () => {
    api.getMe.mockResolvedValue({ success: true, username: 'N1', email: 'ben@example.com' })
    render(<IssueReportModal open onClose={() => {}} />)
    await waitFor(() => expect(api.getMe).toHaveBeenCalled())
    const ta = screen.getByRole('textbox')
    fireEvent.change(ta, { target: { value: 'kısayol ile' } })
    fireEvent.keyDown(ta, { key: 'Enter', ctrlKey: true })
    await waitFor(() => expect(api.sendIssueReport).toHaveBeenCalledWith(expect.objectContaining({ message: 'kısayol ile' })))
  })

  it('panodan yapıştırılan görsel ekran görüntüsü olarak eklenir (metin yapıştırmaya dokunulmaz)', async () => {
    api.getMe.mockResolvedValue({ success: true, username: 'N1', email: 'ben@example.com' })
    render(<IssueReportModal open onClose={() => {}} />)
    await waitFor(() => expect(api.getMe).toHaveBeenCalled())
    const ta = screen.getByRole('textbox')
    fireEvent.paste(ta, { clipboardData: { files: [png('pano.png')], items: [] } })
    expect(await screen.findByRole('button', { name: /Remove screenshot 1/i })).toBeInTheDocument()
    // Metin yapıştırma (dosya yok) — görsel eklenmez
    fireEvent.paste(ta, { clipboardData: { files: [], items: [] } })
    expect(screen.getAllByRole('button', { name: /Remove screenshot/i })).toHaveLength(1)
  })

  it('gönderim hatası: yazılanlar KORUNUR; "Try again" aynı içerikle yeniden gönderir', async () => {
    api.getMe.mockResolvedValue({ success: true, username: 'N1', email: 'ben@example.com' })
    api.sendIssueReport.mockResolvedValueOnce({ success: false, error: 'Geçici hata' })
    render(<IssueReportModal open onClose={() => {}} />)
    await waitFor(() => expect(api.getMe).toHaveBeenCalled())
    fireEvent.change(screen.getByRole('textbox'), { target: { value: 'kaybolmasın' } })
    fireEvent.click(screen.getByRole('button', { name: 'Send report' }))
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Geçici hata')
    expect(alert).toHaveTextContent(/has been kept/i)
    expect(screen.getByRole('textbox').value).toBe('kaybolmasın')
    fireEvent.click(within(alert).getByRole('button', { name: 'Try again' }))
    await waitFor(() => expect(api.sendIssueReport).toHaveBeenCalledTimes(2))
    expect(api.sendIssueReport.mock.calls[1][0].message).toBe('kaybolmasın')
    expect(await screen.findByText('LIR-2026-000099')).toBeInTheDocument()
  })

  it('başarı ekranı: referans + kopyala + sırada ne var; "View my report" derin bağlantıyla sekmeye gider ve pencereyi kapatır', async () => {
    api.getMe.mockResolvedValue({ success: true, username: 'N1', email: 'ben@example.com' })
    const onClose = vi.fn()
    const nav = vi.fn()
    window.addEventListener('sm:navigate', nav)
    try {
      render(<IssueReportModal open onClose={onClose} />)
      await waitFor(() => expect(api.getMe).toHaveBeenCalled())
      fireEvent.change(screen.getByRole('textbox'), { target: { value: 'x' } })
      fireEvent.click(screen.getByRole('button', { name: 'Send report' }))
      const success = await waitFor(() => { const el = document.querySelector('[data-slot="report-success"]'); expect(el).not.toBeNull(); return el })
      expect(success).toHaveAttribute('role', 'status')
      expect(within(success).getByText('LIR-2026-000099')).toBeInTheDocument()
      expect(within(success).getByRole('button', { name: 'Copy reference' })).toBeInTheDocument()
      expect(within(success).getByText(/confirmation has been sent to ben@example\.com/i)).toBeInTheDocument()
      const link = screen.getByRole('link', { name: /View my report/ })
      expect(link).toHaveAttribute('href', '/?tab=login-issues&ir_id=99')
      fireEvent.click(link)
      expect(onClose).toHaveBeenCalled()
      expect(nav).toHaveBeenCalledTimes(1)
      expect(nav.mock.calls[0][0].detail).toEqual({ tab: 'login-issues', params: { ir_id: 99 } })
    } finally {
      window.removeEventListener('sm:navigate', nav)
    }
  })

  it('çökme bağlamında (ErrorBoundary) "View my report" tam sayfa yüklemesine bırakılır (uygulama içi olay yok)', async () => {
    api.getMe.mockResolvedValue({ success: true, username: 'N1', email: 'ben@example.com' })
    const nav = vi.fn()
    window.addEventListener('sm:navigate', nav)
    try {
      render(<IssueReportModal open onClose={() => {}} errorText="TypeError: boom" linkedReference="LIR-2026-000077" />)
      await waitFor(() => expect(api.getMe).toHaveBeenCalled())
      expect(screen.getByText('Captured error (will be attached)')).toBeInTheDocument()
      fireEvent.change(screen.getByRole('textbox'), { target: { value: 'x' } })
      fireEvent.click(screen.getByRole('button', { name: 'Send report' }))
      const link = await screen.findByRole('link', { name: /View my report/ })
      const ev = new MouseEvent('click', { bubbles: true, cancelable: true })
      link.dispatchEvent(ev)
      expect(nav).not.toHaveBeenCalled()
    } finally {
      window.removeEventListener('sm:navigate', nav)
    }
  })

  it('"Report something else" formu sıfırlar', async () => {
    api.getMe.mockResolvedValue({ success: true, username: 'N1', email: 'ben@example.com' })
    render(<IssueReportModal open onClose={() => {}} />)
    await waitFor(() => expect(api.getMe).toHaveBeenCalled())
    fireEvent.change(screen.getByRole('textbox'), { target: { value: 'ilk' } })
    fireEvent.click(screen.getByRole('button', { name: 'Send report' }))
    fireEvent.click(await screen.findByRole('button', { name: /Report something else/ }))
    expect(screen.getByRole('textbox').value).toBe('')
    expect(screen.getByRole('button', { name: 'Send report' })).toBeInTheDocument()
  })
})

/**
 * "Sizi nasıl etkiliyor?" zenginleştirmesi (2026-09-28): ÇOKLU "Ne yaşıyorsunuz?" çipleri (ToggleGroup, aria-pressed)
 * + tekli önem ("Ne kadar etkiliyor?"). Gövdeye kanonik sırada `impacts` gider; "Diğer" seçilince kısa metin alanı açılır
 * ve yalnız OTHER seçiliyken `impactOther` gönderilir. Hiçbir şey seçilmezse alanlar gövdede YOK (eski sözleşme).
 */
describe('IssueReportModal — çoklu etki', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.getMe.mockResolvedValue({ success: true, username: 'N1', email: 'ben@example.com' })
    api.sendIssueReport.mockResolvedValue({ success: true, reference: 'LIR-2026-000123' })
  })
  const chip = (name) => within(screen.getByRole('group', { name: 'What are you experiencing?' })).getByRole('button', { name })

  it('12 çip; birden çok seçilir / bırakılır; önemden bağımsız; gövdeye KANONİK sırada gider', async () => {
    render(<IssueReportModal open onClose={() => {}} />)
    await waitFor(() => expect(api.getMe).toHaveBeenCalled())
    const group = screen.getByRole('group', { name: 'What are you experiencing?' })
    expect(within(group).getAllByRole('button')).toHaveLength(12)
    fireEvent.click(chip('The app is slow'))
    fireEvent.click(chip("I can't sign in, or I keep getting signed out"))
    fireEvent.click(chip("It doesn't display properly on my phone or tablet"))
    fireEvent.click(chip("It doesn't display properly on my phone or tablet"))   // bırak
    expect(chip('The app is slow')).toHaveAttribute('aria-pressed', 'true')
    expect(chip("It doesn't display properly on my phone or tablet")).toHaveAttribute('aria-pressed', 'false')
    fireEvent.click(screen.getByRole('radio', { name: /Blocking me/ }))
    fireEvent.change(screen.getAllByRole('textbox')[0], { target: { value: 'Pano açılmıyor' } })
    fireEvent.click(screen.getByRole('button', { name: 'Send report' }))
    await waitFor(() => expect(api.sendIssueReport).toHaveBeenCalled())
    const dto = api.sendIssueReport.mock.calls[0][0]
    expect(dto.impacts).toEqual(['LOGIN', 'SLOW'])   // tıklama sırası değil, kanonik sıra
    expect(dto.impactOther).toBeUndefined()
    expect(dto.category).toBe('BLOCKER')
  })

  it('"Diğer" seçilince kısa metin alanı açılır (≤200); metin yalnız OTHER seçiliyken gider', async () => {
    render(<IssueReportModal open onClose={() => {}} />)
    await waitFor(() => expect(api.getMe).toHaveBeenCalled())
    expect(screen.queryByRole('textbox', { name: /Something else — briefly describe it/ })).toBeNull()
    fireEvent.click(chip('Something else'))
    const other = screen.getByRole('textbox', { name: /Something else — briefly describe it/ })
    expect(other).toHaveAttribute('maxLength', '200')
    fireEvent.change(other, { target: { value: '  Filtreler sıfırlanıyor  ' } })
    fireEvent.change(screen.getAllByRole('textbox')[0], { target: { value: 'x' } })
    fireEvent.click(screen.getByRole('button', { name: 'Send report' }))
    await waitFor(() => expect(api.sendIssueReport).toHaveBeenCalledTimes(1))
    expect(api.sendIssueReport.mock.calls[0][0]).toMatchObject({ impacts: ['OTHER'], impactOther: 'Filtreler sıfırlanıyor' })
  })

  it('"Diğer" bırakılırsa yazılan metin GÖNDERİLMEZ; hiçbir etki yoksa alanlar gövdede yok', async () => {
    render(<IssueReportModal open onClose={() => {}} />)
    await waitFor(() => expect(api.getMe).toHaveBeenCalled())
    fireEvent.click(chip('Something else'))
    fireEvent.change(screen.getByRole('textbox', { name: /Something else — briefly describe it/ }), { target: { value: 'gizli' } })
    fireEvent.click(chip('Something else'))
    fireEvent.change(screen.getAllByRole('textbox')[0], { target: { value: 'x' } })
    fireEvent.click(screen.getByRole('button', { name: 'Send report' }))
    await waitFor(() => expect(api.sendIssueReport).toHaveBeenCalledTimes(1))
    const dto = api.sendIssueReport.mock.calls[0][0]
    expect(dto.impacts).toBeUndefined()
    expect(dto.impactOther).toBeUndefined()
  })

  it('önem KOMPAKT: seçilenin açıklaması görünür metin; etki çipleri telefonda tek sütun, sm+ iki sütun', async () => {
    render(<IssueReportModal open onClose={() => {}} />)
    await waitFor(() => expect(api.getMe).toHaveBeenCalled())
    expect(screen.queryByText('I can’t get on with my work')).toBeNull()
    fireEvent.click(screen.getByRole('radio', { name: /Blocking me/ }))
    expect(screen.getByText('I can’t get on with my work')).toBeInTheDocument()
    const picker = document.querySelector('[data-slot="issue-impact-picker"]')
    expect(picker.className).toMatch(/grid-cols-1/)
    expect(picker.className).toMatch(/sm:grid-cols-2/)
  })
})
