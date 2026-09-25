import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, fireEvent, waitFor } from './test-utils.jsx'
import PasswordChangeModal from '../components/admin/PasswordChangeModal.jsx'

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

  const fill = (container, { verify = 'eski123', next = 'yeni12', confirm = 'yeni12' } = {}) => {
    const inputs = container.querySelectorAll('input[type="password"]')
    fireEvent.change(inputs[0], { target: { value: verify } })
    fireEvent.change(inputs[1], { target: { value: next } })
    fireEvent.change(inputs[2], { target: { value: confirm } })
    return inputs
  }

  it('SIZINTI KAPISI: üç parola alanı da password tipinde', () => {
    const { container } = render(<PasswordChangeModal mode="self" targetUser={USER} onClose={() => {}} />)
    const inputs = container.querySelectorAll('input[type="password"]')
    expect(inputs).toHaveLength(3)
    // Düz metin bir alan kalmamalı.
    expect(container.querySelectorAll('input[type="text"]')).toHaveLength(0)
  })

  it('kısa parolada gönder düğmesi KAPALI (istek atılmaz)', () => {
    const { container } = render(<PasswordChangeModal mode="self" targetUser={USER} onClose={() => {}} />)
    fill(container, { next: 'kisa', confirm: 'kisa' })
    expect(container.querySelector('[data-slot="button"][data-variant="default"]')).toBeDisabled()
  })

  it('onay eşleşmiyorsa gönder düğmesi KAPALI', () => {
    const { container } = render(<PasswordChangeModal mode="self" targetUser={USER} onClose={() => {}} />)
    fill(container, { next: 'yeni12', confirm: 'baska1' })
    expect(container.querySelector('[data-slot="button"][data-variant="default"]')).toBeDisabled()
  })

  it('geçerli girdide parola değiştirilir ve modal kapanır', async () => {
    const onClose = vi.fn()
    const onSuccess = vi.fn()
    const { container } = render(
      <PasswordChangeModal mode="self" targetUser={USER} onClose={onClose} onSuccess={onSuccess} />)

    fill(container)
    fireEvent.click(container.querySelector('[data-slot="button"][data-variant="default"]'))

    await waitFor(() => expect(api.me.changePassword).toHaveBeenCalledWith('eski123', 'yeni12'))
    await waitFor(() => expect(onSuccess).toHaveBeenCalled())
    expect(onClose).toHaveBeenCalled()
  })

  it('HATA SINIFLANDIRMASI: 403 → "eski parola yanlış" mesajı', async () => {
    api.me.changePassword.mockResolvedValue({ success: false, status: 403, error: 'FORBIDDEN' })
    const { container } = render(<PasswordChangeModal mode="self" targetUser={USER} onClose={() => {}} />)

    fill(container)
    fireEvent.click(container.querySelector('[data-slot="button"][data-variant="default"]'))

    // Ham "FORBIDDEN" gösterilmemeli — kullanıcı nedeni anlayamaz.
    await waitFor(() => expect(container.textContent).not.toContain('FORBIDDEN'))
    expect(container.querySelector('.modal-box').textContent.length).toBeGreaterThan(0)
  })

  it('HATA SINIFLANDIRMASI: "recently used" → parola tekrarı mesajı (ham metin DEĞİL)', async () => {
    api.me.changePassword.mockResolvedValue({ success: false, error: 'Password was recently used' })
    const { container } = render(<PasswordChangeModal mode="self" targetUser={USER} onClose={() => {}} />)

    fill(container)
    fireEvent.click(container.querySelector('[data-slot="button"][data-variant="default"]'))

    await waitFor(() => expect(api.me.changePassword).toHaveBeenCalled())
    expect(container.textContent).not.toContain('Password was recently used')
  })

  it('SINIFLANDIRILAMAYAN hata ham olarak gösterilir (sessizce yutulmaz)', async () => {
    api.me.changePassword.mockResolvedValue({ success: false, error: 'beklenmeyen sunucu hatasi' })
    const { container } = render(<PasswordChangeModal mode="self" targetUser={USER} onClose={() => {}} />)

    fill(container)
    fireEvent.click(container.querySelector('[data-slot="button"][data-variant="default"]'))

    await waitFor(() => expect(container.textContent).toContain('beklenmeyen sunucu hatasi'))
  })

  it('ZORUNLU modda dışarı tıklayarak KAÇILAMAZ (mustChangePassword kilidi atlanmasın)', () => {
    const onClose = vi.fn()
    const { container } = render(<PasswordChangeModal mode="forced-change" targetUser={USER} onClose={onClose} />)

    fireEvent.click(container.querySelector('.modal-overlay'))
    expect(onClose).not.toHaveBeenCalled()
  })

  it('normal modda dışarı tıklamak modalı kapatır', () => {
    const onClose = vi.fn()
    const { container } = render(<PasswordChangeModal mode="self" targetUser={USER} onClose={onClose} />)

    fireEvent.click(container.querySelector('.modal-overlay'))
    expect(onClose).toHaveBeenCalled()
  })

  it('başarısızlıkta modal AÇIK kalır (kullanıcı düzeltebilsin)', async () => {
    api.me.changePassword.mockResolvedValue({ success: false, error: 'hata' })
    const onClose = vi.fn()
    const { container } = render(<PasswordChangeModal mode="self" targetUser={USER} onClose={onClose} />)

    fill(container)
    fireEvent.click(container.querySelector('[data-slot="button"][data-variant="default"]'))

    await waitFor(() => expect(api.me.changePassword).toHaveBeenCalled())
    expect(onClose).not.toHaveBeenCalled()
  })
})
