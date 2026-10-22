import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'
import PasswordChangeModal, { PWD_MAX_LENGTH, PWD_MIN_LENGTH, serverLengthMessage } from '../components/admin/PasswordChangeModal.jsx'
import { EN } from '../i18n/en.js'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: withApiFallback({
    me: { changePassword: vi.fn() },
  }),
}))
import { api } from '../api/client'

/**
 * Parola değiştirme modalı — %2.88 kapsamla duruyordu.
 *
 * Üç sözleşme test ediliyor, üçü de kozmetik değil:
 *
 * 1. **Üç alan da `type="password"`.** Biri düz metne dönerse yeni parola ekranda ve ekran
 *    görüntüsünde açıkta kalır — sessiz bir sızıntı.
 * 2. **Sunucu hatasının SINIFLANDIRILMASI.** Kod yanıtı okuyup "eski parola yanlış",
 *    "yakın zamanda kullanılmış" ve "uzunluk kuralı" ayrımını yapıyor. Yanlış eşleşme
 *    kullanıcıya YANLIŞ nedeni gösterir ve o parolayı neden kabul edilmediğini anlamaz.
 * 3. **ZORUNLU değişim modunda modal dışına tıklayarak KAÇILAMAZ.** `mustChangePassword`
 *    kilidi bu ekrana dayanıyor; kapatılabilseydi kullanıcı kilidi atlardı.
 */
const USER = { username: 'admin', id: 1 }

describe('PasswordChangeModal', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.me.changePassword.mockResolvedValue({ success: true })
  })

  // Kabuk ui/ModalShell (shadcn Dialog) — içerik document.body'ye portal'lanır; `container` onu görmez.
  const submitBtn = () => screen.getByRole('dialog').querySelector('[data-slot="button"][data-variant="default"]')
  const fill = (_c, { verify = 'eski123', next = 'yeni12', confirm = 'yeni12' } = {}) => {
    const inputs = document.querySelectorAll('input[type="password"]')
    fireEvent.change(inputs[0], { target: { value: verify } })
    fireEvent.change(inputs[1], { target: { value: next } })
    fireEvent.change(inputs[2], { target: { value: confirm } })
    return inputs
  }

  it('SIZINTI KAPISI: üç parola alanı da password tipinde', () => {
    render(<PasswordChangeModal mode="self" targetUser={USER} onClose={() => {}} />)
    const dlg = screen.getByRole('dialog')
    const inputs = dlg.querySelectorAll('input[type="password"]')
    expect(inputs).toHaveLength(3)
    // shadcn Input + ui/Field etiket bağı
    expect(inputs[0]).toHaveAttribute('data-slot', 'input')
    expect(inputs[1].id).toBeTruthy()
    expect(dlg.querySelector(`label[for="${inputs[1].id}"]`)).not.toBeNull()
    // Düz metin bir alan kalmamalı.
    expect(dlg.querySelectorAll('input[type="text"]')).toHaveLength(0)
  })

  it('kısa parolada gönder düğmesi KAPALI (istek atılmaz)', () => {
    const { container } = render(<PasswordChangeModal mode="self" targetUser={USER} onClose={() => {}} />)
    fill(container, { next: 'kisa', confirm: 'kisa' })
    expect(submitBtn()).toBeDisabled()
  })

  it('onay eşleşmiyorsa gönder düğmesi KAPALI', () => {
    const { container } = render(<PasswordChangeModal mode="self" targetUser={USER} onClose={() => {}} />)
    fill(container, { next: 'yeni12', confirm: 'baska1' })
    expect(submitBtn()).toBeDisabled()
  })

  it('geçerli girdide parola değiştirilir ve modal kapanır', async () => {
    const onClose = vi.fn()
    const onSuccess = vi.fn()
    const { container } = render(
      <PasswordChangeModal mode="self" targetUser={USER} onClose={onClose} onSuccess={onSuccess} />)

    fill(container)
    fireEvent.click(submitBtn())

    await waitFor(() => expect(api.me.changePassword).toHaveBeenCalledWith('eski123', 'yeni12'))
    await waitFor(() => expect(onSuccess).toHaveBeenCalled())
    expect(onClose).toHaveBeenCalled()
  })

  it('HATA SINIFLANDIRMASI: 403 → "eski parola yanlış" mesajı', async () => {
    api.me.changePassword.mockResolvedValue({ success: false, status: 403, error: 'FORBIDDEN' })
    const { container } = render(<PasswordChangeModal mode="self" targetUser={USER} onClose={() => {}} />)

    fill(container)
    fireEvent.click(submitBtn())

    // Ham "FORBIDDEN" gösterilmemeli — kullanıcı nedeni anlayamaz.
    await waitFor(() => expect(api.me.changePassword).toHaveBeenCalled())
    await waitFor(() => expect(screen.getByRole('alert')).toBeInTheDocument())
    expect(document.body.textContent).not.toContain('FORBIDDEN')
    expect(screen.getByRole('dialog').textContent.length).toBeGreaterThan(0)
  })

  it('HATA SINIFLANDIRMASI: "recently used" → parola tekrarı mesajı (ham metin DEĞİL)', async () => {
    api.me.changePassword.mockResolvedValue({ success: false, error: 'Password was recently used' })
    const { container } = render(<PasswordChangeModal mode="self" targetUser={USER} onClose={() => {}} />)

    fill(container)
    fireEvent.click(submitBtn())

    await waitFor(() => expect(api.me.changePassword).toHaveBeenCalled())
    expect(document.body.textContent).not.toContain('Password was recently used')
  })

  it('SINIFLANDIRILAMAYAN hata ham olarak gösterilir (sessizce yutulmaz)', async () => {
    api.me.changePassword.mockResolvedValue({ success: false, error: 'beklenmeyen sunucu hatasi' })
    const { container } = render(<PasswordChangeModal mode="self" targetUser={USER} onClose={() => {}} />)

    fill(container)
    fireEvent.click(submitBtn())

    await waitFor(() => expect(document.body.textContent).toContain('beklenmeyen sunucu hatasi'))
  })

  it('ZORUNLU modda dışarı tıklayarak KAÇILAMAZ (mustChangePassword kilidi atlanmasın)', () => {
    const onClose = vi.fn()
    render(<PasswordChangeModal mode="forced-change" targetUser={USER} onClose={onClose} />)

    fireEvent.click(document.querySelector('[data-slot="dialog-overlay"]'))
    fireEvent.keyDown(screen.getByRole('dialog'), { key: 'Escape' })
    expect(onClose).not.toHaveBeenCalled()
    // Kapatma düğmesi de yok (X ve Vazgeç çizilmez)
    expect(screen.queryByRole('button', { name: /close|kapat|cancel|vazgeç/i })).toBeNull()
  })

  it('normal modda dışarı tıklamak modalı kapatır', () => {
    const onClose = vi.fn()
    render(<PasswordChangeModal mode="self" targetUser={USER} onClose={onClose} />)

    fireEvent.click(document.querySelector('[data-slot="dialog-overlay"]'))
    expect(onClose).toHaveBeenCalled()
  })

  it('başarısızlıkta modal AÇIK kalır (kullanıcı düzeltebilsin)', async () => {
    api.me.changePassword.mockResolvedValue({ success: false, error: 'hata' })
    const onClose = vi.fn()
    const { container } = render(<PasswordChangeModal mode="self" targetUser={USER} onClose={onClose} />)

    fill(container)
    fireEvent.click(submitBtn())

    await waitFor(() => expect(api.me.changePassword).toHaveBeenCalled())
    expect(onClose).not.toHaveBeenCalled()
  })

  // ── UIA-H1 / H11 (2026-10-22): istemci kuralı sunucunun 6–64'ü; maxLength YOK (eskiden 10'da sessizce kesiyordu) ──
  it('UIA-H1: 30 karakterlik parola KESİLMEDEN gönderilir (eskiden maxLength=10 ilk 10 karakteri yazdırıyordu)', async () => {
    const pass = 'uzun-ve-guvenli-bir-parola-30c'
    expect(pass.length).toBe(30)
    const { container } = render(<PasswordChangeModal mode="self" targetUser={USER} onClose={() => {}} />)
    const inputs = fill(container, { next: pass, confirm: pass })
    expect(inputs[1]).not.toHaveAttribute('maxlength')
    expect(inputs[2]).not.toHaveAttribute('maxlength')
    expect(inputs[1].value).toHaveLength(30)
    expect(submitBtn()).not.toBeDisabled()
    fireEvent.click(submitBtn())
    await waitFor(() => expect(api.me.changePassword).toHaveBeenCalledWith('eski123', pass))
  })

  it('UIA-H1: 64 karakter kabul, 65 karakter → Kaydet kapalı ve alan altında "en fazla 64" uyarısı', () => {
    const ok = 'a'.repeat(PWD_MAX_LENGTH)
    const { container, rerender } = render(<PasswordChangeModal mode="self" targetUser={USER} onClose={() => {}} />)
    fill(container, { next: ok, confirm: ok })
    expect(submitBtn()).not.toBeDisabled()
    const tooLong = 'a'.repeat(PWD_MAX_LENGTH + 1)
    fill(container, { next: tooLong, confirm: tooLong })
    rerender(<PasswordChangeModal mode="self" targetUser={USER} onClose={() => {}} />)
    expect(submitBtn()).toBeDisabled()
    expect(screen.getByRole('dialog').textContent).toContain(EN['pwd.tooLong'].replace('{0}', String(PWD_MAX_LENGTH)))
  })

  it('UIA-H1: sunucunun "too short (min 12 chars)" iletisi yapılandırılmış sınırla yerelleştirilir', async () => {
    api.me.changePassword.mockResolvedValue({ success: false, status: 400, error: 'Password too short (min 12 chars)' })
    const { container } = render(<PasswordChangeModal mode="self" targetUser={USER} onClose={() => {}} />)
    fill(container, { next: 'yeni1234', confirm: 'yeni1234' })
    fireEvent.click(submitBtn())
    await waitFor(() => expect(screen.getByRole('alert').textContent).toBe(EN['pwd.tooShort'].replace('{0}', '12')))
  })

  it('AUTH-7: sunucunun yeni parola alan hatası (field: password) YENİ PAROLA alanının altında; yazınca kalkar', async () => {
    const text = 'The password can't start or end with a space: remove the spaces and try again.'
    api.me.changePassword.mockResolvedValue({ success: false, code: 'VALIDATION_FAILED', field: 'password', error: text })
    render(<PasswordChangeModal mode="self" targetUser={USER} onClose={() => {}} />)
    const inputs = fill(null, { next: 'yeni12 ', confirm: 'yeni12 ' })
    fireEvent.click(submitBtn())
    await waitFor(() => expect(inputs[1]).toHaveAttribute('aria-invalid', 'true'))
    const describedBy = inputs[1].getAttribute('aria-describedby').split(' ')
    expect(describedBy.some((id) => document.getElementById(id)?.textContent === text)).toBe(true)
    expect(screen.queryByRole('alert')).toBeNull()
    fireEvent.change(inputs[1], { target: { value: 'yeni12' } })
    expect(inputs[1]).not.toHaveAttribute('aria-invalid')
  })

  it('serverLengthMessage: kısa / uzun / ilgisiz ileti', () => {
    const t = (k, n) => `${k}:${n}`
    expect(serverLengthMessage('Password too long (max 40 chars)', t)).toBe('pwd.tooLong:40')
    expect(serverLengthMessage('Password too short', t)).toBe(`pwd.tooShort:${PWD_MIN_LENGTH}`)
    expect(serverLengthMessage('Current password wrong', t)).toBeNull()
  })

  it('UIA-H11: ağ hatasında ileti gösterilir (yakalanmamış söz reddi değil), modal açık kalır', async () => {
    api.me.changePassword.mockRejectedValue(Object.assign(new Error('Sunucuya ulaşılamadı. Bağlantınızı kontrol edin.'), { status: 0 }))
    const onClose = vi.fn()
    const { container } = render(<PasswordChangeModal mode="forced-change" targetUser={USER} onClose={onClose} />)
    fill(container)
    fireEvent.click(submitBtn())
    await waitFor(() => expect(screen.getByRole('alert').textContent).toContain('Sunucuya ulaşılamadı'))
    expect(onClose).not.toHaveBeenCalled()
  })
})
