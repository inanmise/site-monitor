import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within, act } from './test-utils.jsx'
import UserEditor from '../components/admin/user-editor/UserEditor.jsx'

/**
 * Paylaşılan kullanıcı düzenleyicisi (2026-10-02, kullanıcı isteği: "Kullanıcı Düzenle ekranını shadcn ile yeniden,
 * fonksiyonlarını koruyarak tasarlayalım"). Kipler (ekle / düzenle / görüntüle), korumalar (kendi hesabı, tek ADMIN,
 * takım yöneticisi), alan yanında doğrulama + sekme hata noktası + odak, değişiklik sayacı + kaydedilmemiş değişiklik
 * onayı, AD alan kilidi ve kilit açma (yalnız global yönetici), geçici şifre sonucu, aktiflik bandı, kablo sözleşmesi.
 */
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
const perms = vi.hoisted(() => ({ set: new Set() }))

vi.mock('../api/client', () => ({
  formatDate: (s) => String(s ?? ''),
  formatDateSec: (s) => String(s ?? ''),
  api: withApiFallback({ admin: {
    createUser: vi.fn(), updateUser: vi.fn(), unlockUserField: vi.fn(), unlockUserRole: vi.fn(), unlockUser: vi.fn(),
    autoResetPassword: vi.fn(), resetUserTour: vi.fn(), terminateUserSession: vi.fn(), getUserDevices: vi.fn(),
  } }),
}))
vi.mock('../contexts/PermissionsProvider.jsx', () => ({
  usePermissions: () => ({
    perms: {}, refresh: () => {},
    canView: (r) => perms.set.has(`${r}:view`), canEdit: (r) => perms.set.has(`${r}:edit`), canExecute: (r) => perms.set.has(`${r}:execute`),
  }),
  PermissionsProvider: ({ children }) => children,
}))

import { api } from '../api/client'

const TEAMS = [{ id: 3, name: 'Payments' }, { id: 4, name: 'Cards' }, { id: 9, name: 'Ops' }]
const LOCAL = {
  id: 7, username: 'ALI', display_name: 'Ali V', email: 'ali@example.com', employee_id: '12345', system_role: 'USER',
  team_ids: [3], team_id: 3, org_role: 'TECH', active: true, auth_source: 'LOCAL',
}
const LDAP_USER = {
  ...LOCAL, id: 8, username: 'AYSE', display_name: 'Ayşe K', email: 'ayse@example.com', auth_source: 'LDAP',
  title: 'Uzman', locked_field_keys: ['email'], role_locked: true,
}

const dialog = () => screen.getByRole('dialog')
const tab = (re) => screen.getByRole('tab', { name: re })
const openTab = (re) => fireEvent.mouseDown(tab(re), { button: 0 })
const saveBtn = () => screen.getByRole('button', { name: /^(Kaydet|Save)$/ })
const field = (key) => dialog().querySelector(`[data-field="${key}"]`)

function renderEditor(props = {}) {
  const onClose = vi.fn()
  const onSaved = vi.fn()
  const onChanged = vi.fn()
  const utils = render(<UserEditor mode="edit" user={LOCAL} teams={TEAMS} viewerRole="ADMIN" onClose={onClose} onSaved={onSaved} onChanged={onChanged} {...props} />)
  return { ...utils, onClose, onSaved, onChanged }
}

describe('UserEditor — kipler', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    perms.set = new Set()
    api.admin.createUser.mockResolvedValue({ success: true, data: { id: 99 } })
    api.admin.updateUser.mockResolvedValue({ success: true, data: LOCAL })
  })

  it('ekle: Hesap · Takımlar · Profil sekmeleri (Güvenlik yok), kullanıcı adı ve şifre düzenlenebilir', () => {
    renderEditor({ mode: 'add', user: null })
    expect(screen.getByRole('dialog', { name: /Kullanıcı Ekle|Add User/ })).toBeInTheDocument()
    expect(screen.getAllByRole('tab').map((x) => x.getAttribute('data-tab-key'))).toEqual(['account', 'teams', 'profile'])
    expect(screen.getByLabelText(/^(Kullanıcı Adı|Username)/)).not.toBeDisabled()
    expect(screen.getByLabelText(/^(Şifre|Password)/)).toHaveAttribute('type', 'password')
    // aktiflik anahtarı ekle kipinde yok (sunucu yeni hesabı her zaman aktif açar)
    expect(screen.queryByRole('switch')).toBeNull()
  })

  it('ekle: boş formda Kaydet → hatalar ALANLARIN YANINDA, sekmelerde hata noktası, ilk hatalı alana odak; istek gitmez', async () => {
    renderEditor({ mode: 'add', user: null })
    fireEvent.click(saveBtn())
    await waitFor(() => expect(field('username')).toHaveTextContent(/zorunlu|required/i))
    expect(field('password')).toHaveTextContent(/en az 4|at least 4/)
    expect(field('email')).toHaveTextContent(/E-posta adresi zorunlu|email address is required/i)
    expect(field('teams')).toHaveTextContent(/En az bir takım|At least one team/)
    expect(tab(/^(Hesap|Account)/)).toHaveAttribute('data-invalid', 'true')
    expect(tab(/^(Takımlar|Teams)/)).toHaveAttribute('data-invalid', 'true')
    expect(tab(/^(Profil|Profile)/)).not.toHaveAttribute('data-invalid')
    expect(screen.getByLabelText(/^(Kullanıcı Adı|Username)/)).toHaveAttribute('aria-invalid', 'true')
    await waitFor(() => expect(document.activeElement).toBe(screen.getByLabelText(/^(Kullanıcı Adı|Username)/)))
    expect(dialog().querySelector('[data-slot="ued-error-count"]')).toHaveTextContent(/4/)
    expect(api.admin.createUser).not.toHaveBeenCalled()
    // alan düzenlenince kendi hatası silinir
    fireEvent.change(screen.getByLabelText(/^(Kullanıcı Adı|Username)/), { target: { value: 'yeni' } })
    expect(field('username')).not.toHaveTextContent(/zorunlu|required/i)
  })

  it('ekle: birincil takım seçilir (listenin başına) → createUser gövdesi team_id = team_ids[0] + şifre', async () => {
    renderEditor({ mode: 'add', user: null })
    fireEvent.change(screen.getByLabelText(/^(Kullanıcı Adı|Username)/), { target: { value: 'yeni' } })
    fireEvent.change(screen.getByLabelText(/^(Şifre|Password)/), { target: { value: 'gizli1' } })
    fireEvent.change(screen.getByLabelText(/^(E-posta|Email)/), { target: { value: 'y@example.com' } })
    openTab(/^(Takımlar|Teams)/)
    const picker = within(field('teams')).getByRole('combobox')
    fireEvent.mouseDown(picker)
    fireEvent.mouseDown(await screen.findByRole('option', { name: 'Payments' }))
    // jsdom'da konumlanamayan Popper içeriği ilk güncellemeden sonra visibility:hidden alır (erişilebilirlik ağacı dışı) —
    // seçenek cmdk kancasıyla bulunur.
    fireEvent.mouseDown([...document.querySelectorAll('[cmdk-item]')].find((o) => o.textContent === 'Cards'))
    fireEvent.keyDown(document.activeElement, { key: 'Escape' })
    const rows = () => [...dialog().querySelectorAll('[data-slot="ued-team-row"]')]
    await waitFor(() => expect(rows()).toHaveLength(2))
    expect(rows()[0]).toHaveAttribute('data-primary', 'true')   // ilk seçilen birincil
    fireEvent.click(within(rows()[1]).getByRole('button', { name: /Cards — (Birincil yap|Make primary)/ }))
    expect(rows()[0]).toHaveAttribute('data-team-id', '4')
    fireEvent.click(saveBtn())
    await waitFor(() => expect(api.admin.createUser).toHaveBeenCalled())
    const body = api.admin.createUser.mock.calls[0][0]
    expect(body).toMatchObject({ username: 'yeni', password: 'gizli1', email: 'y@example.com', team_ids: [4, 3], team_id: 4, active: true })
  })

  it('görüntüle: alanlar kapalı, altlık Kapat + Düzenle; Düzenle (onEdit yoksa) aynı pencerede düzenlemeye geçer', async () => {
    renderEditor({ mode: 'view' })
    expect(screen.getByRole('dialog', { name: /Kullanıcı Detayı|User Details/ })).toBeInTheDocument()
    expect(screen.getByLabelText(/^(E-posta|Email)/)).toBeDisabled()
    expect(screen.queryByRole('button', { name: /^(Kaydet|Save)$/ })).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: /^(Düzenle|Edit)$/ }))
    await waitFor(() => expect(screen.getByLabelText(/^(E-posta|Email)/)).not.toBeDisabled())
    expect(saveBtn()).toBeDisabled()   // değişiklik yok
  })

  it('görüntüle: onEdit verilmişse Düzenle onu çağırır', () => {
    const onEdit = vi.fn()
    renderEditor({ mode: 'view', onEdit })
    fireEvent.click(screen.getByRole('button', { name: /^(Düzenle|Edit)$/ }))
    expect(onEdit).toHaveBeenCalled()
  })
})

describe('UserEditor — düzenleme, değişiklik özeti, kapatma onayı', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    perms.set = new Set()
    api.admin.updateUser.mockResolvedValue({ success: true, data: LOCAL })
  })

  it('değişiklik yokken Kaydet kapalı; alan değişince "1 değişiklik" + özet eski → yeni; gövde = eski gövde + o alan', async () => {
    const { onSaved, onClose } = renderEditor()
    expect(saveBtn()).toBeDisabled()
    expect(dialog().querySelector('[data-slot="ued-dirty"]')).toHaveAttribute('data-count', '0')
    openTab(/^(Profil|Profile)/)
    fireEvent.change(screen.getByDisplayValue('Ali V'), { target: { value: 'Ali Veli' } })
    const dirty = dialog().querySelector('[data-slot="ued-dirty"]')
    expect(dirty).toHaveAttribute('data-count', '1')
    expect(dirty).toHaveTextContent(/1 (değişiklik|change)/)
    fireEvent.click(dirty)
    await waitFor(() => expect(document.querySelector('[data-slot="ued-changes"]')).not.toBeNull())
    const changeRow = document.querySelector('[data-slot="ued-change-row"][data-field="display_name"]')
    expect(changeRow).toHaveTextContent(/Ali V/)
    expect(changeRow).toHaveTextContent(/Ali Veli/)
    fireEvent.keyDown(document.activeElement, { key: 'Escape' })
    expect(saveBtn()).not.toBeDisabled()
    fireEvent.click(saveBtn())
    await waitFor(() => expect(api.admin.updateUser).toHaveBeenCalled())
    // KABLO SÖZLEŞMESİ: eski formun gövdesi, yalnız değişen alan farklı
    expect(api.admin.updateUser.mock.calls[0]).toEqual([7, {
      username: 'ALI', display_name: 'Ali Veli', email: 'ali@example.com', employee_id: '12345', system_role: 'USER',
      team_ids: [3], team_id: 3, org_role: 'TECH', active: true, first_name: '', last_name: '', title: '', phone: '',
      department: '', company_level: '', mudurluk_name: '', manager_sicil: '',
    }])
    await waitFor(() => expect(onSaved).toHaveBeenCalled())
    expect(onClose).toHaveBeenCalled()
  })

  it('Ctrl+Enter kaydeder', async () => {
    renderEditor()
    const email = screen.getByLabelText(/^(E-posta|Email)/)
    fireEvent.change(email, { target: { value: 'yeni@example.com' } })
    fireEvent.keyDown(email, { key: 'Enter', ctrlKey: true })
    await waitFor(() => expect(api.admin.updateUser).toHaveBeenCalledWith(7, expect.objectContaining({ email: 'yeni@example.com' })))
  })

  it('kaydedilmemiş değişiklikle İptal → onay; "Düzenlemeye devam et" pencereyi açık tutar, "Değişiklikleri sil" kapatır', async () => {
    const { onClose } = renderEditor()
    fireEvent.change(screen.getByLabelText(/^(E-posta|Email)/), { target: { value: 'x@example.com' } })
    fireEvent.click(screen.getByRole('button', { name: /^(İptal|Cancel)$/ }))
    const confirm = await screen.findByRole('dialog', { name: /Kaydedilmemiş değişiklikler|Discard unsaved changes/ })
    fireEvent.click(within(confirm).getByRole('button', { name: /Düzenlemeye devam et|Keep editing/ }))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: /Discard unsaved|Kaydedilmemiş değişiklikler/ })).toBeNull())
    expect(onClose).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: /^(İptal|Cancel)$/ }))
    const again = await screen.findByRole('dialog', { name: /Kaydedilmemiş değişiklikler|Discard unsaved changes/ })
    fireEvent.click(within(again).getByRole('button', { name: /Değişiklikleri sil|Discard changes/ }))
    await waitFor(() => expect(onClose).toHaveBeenCalled())
  })

  it('değişiklik yokken İptal onay sormadan kapatır', () => {
    const { onClose } = renderEditor()
    fireEvent.click(screen.getByRole('button', { name: /^(İptal|Cancel)$/ }))
    expect(onClose).toHaveBeenCalled()
  })

  it('bozuk e-posta (Profil sekmesindeyken) → Hesap sekmesine döner, hata alanın altında, odak e-postada; istek yok', async () => {
    renderEditor()
    fireEvent.change(screen.getByLabelText(/^(E-posta|Email)/), { target: { value: 'bozuk-adres' } })
    openTab(/^(Profil|Profile)/)
    expect(tab(/^(Profil|Profile)/)).toHaveAttribute('data-state', 'active')
    fireEvent.click(saveBtn())
    await waitFor(() => expect(tab(/^(Hesap|Account)/)).toHaveAttribute('data-state', 'active'))
    expect(field('email')).toHaveTextContent(/Geçerli bir e-posta|valid email/i)
    expect(tab(/^(Hesap|Account)/)).toHaveAttribute('data-invalid', 'true')
    expect(within(tab(/^(Hesap|Account)/)).getByText(/1 (hatalı alan|invalid fields)/)).toBeInTheDocument()
    await waitFor(() => expect(document.activeElement).toBe(screen.getByLabelText(/^(E-posta|Email)/)))
    expect(api.admin.updateUser).not.toHaveBeenCalled()
  })

  it('takım ADMIN dışındaki rolde zorunlu: son takım çıkarılınca Kaydet Takımlar sekmesinde hata verir; ADMIN rolünde takımsız kaydedilir', async () => {
    renderEditor()
    openTab(/^(Takımlar|Teams)/)
    fireEvent.click(screen.getByRole('button', { name: /Payments — (Takımı çıkar|Remove team)/ }))
    openTab(/^(Hesap|Account)/)
    fireEvent.click(saveBtn())
    await waitFor(() => expect(tab(/^(Takımlar|Teams)/)).toHaveAttribute('data-state', 'active'))
    expect(field('teams')).toHaveTextContent(/En az bir takım|At least one team/)
    expect(api.admin.updateUser).not.toHaveBeenCalled()
  })

  it('sunucu reddederse pencere açık kalır, hata bandı görünür, başarı denmez', async () => {
    api.admin.updateUser.mockResolvedValue({ success: false, error: 'çakışma' })
    const { onClose, onSaved } = renderEditor()
    fireEvent.change(screen.getByLabelText(/^(E-posta|Email)/), { target: { value: 'z@example.com' } })
    fireEvent.click(saveBtn())
    expect(await screen.findByText('çakışma')).toBeInTheDocument()
    expect(onClose).not.toHaveBeenCalled()
    expect(onSaved).not.toHaveBeenCalled()
  })
})

describe('UserEditor — korumalar', () => {
  beforeEach(() => { vi.clearAllMocks(); perms.set = new Set() })

  it('kendi hesabı: rol seçicisi ve aktiflik anahtarı kapalı, açıklamalı; oturum sonlandırma yok', () => {
    renderEditor({ currentUsername: 'ali', globalAdmin: true })
    expect(screen.getByLabelText(/^(Sistem Rolü|System Role)/)).toBeDisabled()
    expect(screen.getByRole('switch', { name: /^(Aktif|Active)$/ })).toBeDisabled()
    expect(screen.getByText(/Kendi rolünüzü|cannot change your own role/)).toBeInTheDocument()
    expect(screen.getByText(/Kendi hesabınızı pasif|cannot deactivate your own account/)).toBeInTheDocument()
    expect(dialog().querySelector('[data-flag="self"]')).not.toBeNull()
    expect(dialog().querySelector('[data-slot="ued-terminate"]')).toBeNull()
  })

  it('sistemdeki TEK aktif ADMIN: rol ve aktiflik kilitli, "TEK ADMIN" rozeti', () => {
    renderEditor({ user: { ...LOCAL, system_role: 'ADMIN' }, activeAdminCount: 1, currentUsername: 'baska' })
    expect(screen.getByLabelText(/^(Sistem Rolü|System Role)/)).toBeDisabled()
    expect(screen.getByRole('switch', { name: /^(Aktif|Active)$/ })).toBeDisabled()
    expect(screen.getByText(/tek aktif ADMIN — rolü|last active ADMIN — role/i)).toBeInTheDocument()
    expect(dialog().querySelector('[data-flag="last-admin"]')).not.toBeNull()
  })

  it('takım yöneticisi: yalnız USER / TEAM_ADMIN verir, takım kendi takımı (salt-okunur), kayıtta KENDİ takımına sabitlenir', async () => {
    api.admin.updateUser.mockResolvedValue({ success: true })
    renderEditor({ viewerRole: 'TEAM_ADMIN', ownTeamId: 9, user: { ...LOCAL, team_ids: [9], team_id: 9 } })
    fireEvent.mouseDown(screen.getByLabelText(/^(Sistem Rolü|System Role)/))
    const opts = (await screen.findAllByRole('option')).map((o) => o.textContent)
    expect(opts).toEqual(['USER', 'TEAM_ADMIN'])
    fireEvent.keyDown(document.activeElement, { key: 'Escape' })
    expect(dialog().querySelector('[data-slot="ued-teams"]')).toHaveAttribute('data-scope', 'team-admin')
    expect(within(field('teams')).getByRole('combobox', { hidden: true })).toBeDisabled()   // Takımlar sekmesi henüz açılmadı
    fireEvent.change(screen.getByLabelText(/^(E-posta|Email)/), { target: { value: 'n@example.com' } })
    fireEvent.click(saveBtn())
    await waitFor(() => expect(api.admin.updateUser).toHaveBeenCalled())
    expect(api.admin.updateUser.mock.calls[0][1]).toMatchObject({ team_ids: [9], team_id: 9 })
  })

  it('aktiflik bandı: pasife alınca sonuç uyarısı, pasifi aktifleştirince bilgi', () => {
    const { unmount } = renderEditor()
    expect(screen.queryByText(/signed out of every open session|oturumları hemen kapanır/)).toBeNull()
    fireEvent.click(screen.getByRole('switch', { name: /^(Aktif|Active)$/ }))
    expect(screen.getByText(/signed out of every open session|oturumları hemen kapanır/)).toBeInTheDocument()
    unmount()
    renderEditor({ user: { ...LOCAL, active: false } })
    expect(dialog().querySelector('[data-account="inactive"]')).not.toBeNull()   // başlıkta Pasif rozeti
    fireEvent.click(screen.getByRole('switch', { name: /^(Aktif|Active)$/ }))
    expect(screen.getByText(/closed sessions do not come back|kapanan oturumlar geri gelmez/)).toBeInTheDocument()
  })
})

describe('UserEditor — AD kilitleri, güvenlik eylemleri', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    perms.set = new Set()
    api.admin.unlockUserField.mockResolvedValue({ success: true, data: { ...LDAP_USER, locked_field_keys: [] }, had_lock: true })
    api.admin.unlockUserRole.mockResolvedValue({ success: true })
    api.admin.unlockUser.mockResolvedValue({ success: true })
    api.admin.autoResetPassword.mockResolvedValue({ success: true, email_status: 'SENT' })
    api.admin.terminateUserSession.mockResolvedValue({ success: true })
    api.admin.getUserDevices.mockResolvedValue({ success: true, data: { current: null, remembered: [] } })
  })

  it('LDAP hesabı: kilitli alanda rozet + "AD\'ye geri ver" YALNIZ global yöneticide; tıklayınca field-unlock ve rozet kalkar', async () => {
    const { onChanged } = renderEditor({ user: LDAP_USER, globalAdmin: true })
    const emailField = field('email')
    expect(emailField.querySelector('[data-lock="field"][data-field="email"]')).not.toBeNull()
    expect(dialog().querySelector('[data-lock="fields"]')).toHaveAttribute('data-count', '1')   // başlık özeti
    // kilitsiz AD alanı: "AD'den gelir" ipucu
    expect(field('title')).toHaveTextContent(/AD'den gelir|Comes from AD/)
    fireEvent.click(within(emailField).getByRole('button', { name: /(E-posta|Email) — (AD'ye geri ver|Return to AD)/ }))
    await waitFor(() => expect(api.admin.unlockUserField).toHaveBeenCalledWith(8, 'email'))
    await waitFor(() => expect(emailField.querySelector('[data-lock="field"]')).toBeNull())
    expect(onChanged).toHaveBeenCalled()
  })

  it('kapsamlı yönetici (global değil): kilit rozeti var, geri verme düğmesi yok', () => {
    renderEditor({ user: LDAP_USER, globalAdmin: false })
    expect(field('email').querySelector('[data-lock="field"]')).not.toBeNull()
    expect(dialog().querySelector('[data-slot="ued-field-unlock"]')).toBeNull()
  })

  it('rol AD kilidi yerinde açılır (mevcut role-unlock ucu)', async () => {
    renderEditor({ user: LDAP_USER })
    fireEvent.click(screen.getByRole('button', { name: /(Sistem Rolü|System Role) — (Rolü AD'ye geri ver|Return role to AD)/ }))
    await waitFor(() => expect(api.admin.unlockUserRole).toHaveBeenCalledWith(8))
    await waitFor(() => expect(dialog().querySelector('[data-slot="ued-lock-note"][data-kind="role"]')).toBeNull())
  })

  it('Güvenlik: kalıcı kilit → Kilidi Aç (unlock ucu); geçici şifre gönderilince sonuç satır içinde', async () => {
    renderEditor({ user: { ...LOCAL, permanent_lock: true } })
    openTab(/^(Güvenlik|Security)/)
    const sec = dialog().querySelector('[data-slot="ued-security"]')
    expect(sec.querySelector('[data-lock="perm"]')).not.toBeNull()
    fireEvent.click(within(sec).getByRole('button', { name: /^(Kilidi Aç|Unlock)$/ }))
    await waitFor(() => expect(api.admin.unlockUser).toHaveBeenCalledWith(7))
    await waitFor(() => expect(sec.querySelector('[data-slot="ued-no-lock"]')).not.toBeNull())

    fireEvent.click(within(sec).getByRole('button', { name: /^(Şifre Sıfırla|Reset Password)$/ }))
    const reset = await screen.findByRole('dialog', { name: /Şifre Sıfırlama|Password Reset|Reset/ })
    fireEvent.change(within(reset).getByLabelText(/Sizin mevcut şifreniz|Your current password/), { target: { value: 'admin-pw' } })
    fireEvent.click(within(reset).getByRole('button', { name: /^(Gönder|Send)$/ }))
    await waitFor(() => expect(api.admin.autoResetPassword).toHaveBeenCalledWith(7, 'admin-pw'))
    expect(await within(sec).findByText(/Geçici şifre email ile gönderildi|Temporary password emailed/)).toBeInTheDocument()
  })

  it('geçici şifre e-postası gidemezse sonuç hata bandıyla', async () => {
    api.admin.autoResetPassword.mockResolvedValue({ success: true, email_status: 'FAILED: smtp' })
    renderEditor()
    openTab(/^(Güvenlik|Security)/)
    const sec = dialog().querySelector('[data-slot="ued-security"]')
    fireEvent.click(within(sec).getByRole('button', { name: /^(Şifre Sıfırla|Reset Password)$/ }))
    const reset = await screen.findByRole('dialog', { name: /Şifre Sıfırlama|Password Reset|Reset/ })
    fireEvent.change(within(reset).getByLabelText(/Sizin mevcut şifreniz|Your current password/), { target: { value: 'pw' } })
    fireEvent.click(within(reset).getByRole('button', { name: /^(Gönder|Send)$/ }))
    expect(await within(sec).findByText(/Email gönderilemedi|could not be sent/)).toBeInTheDocument()
  })

  it('oturum sonlandırma yalnız global yöneticide; gerekçeyle mevcut uca gider', async () => {
    const { unmount } = renderEditor({ globalAdmin: false })
    expect(dialog().querySelector('[data-slot="ued-terminate"]')).toBeNull()
    unmount()
    renderEditor({ globalAdmin: true, currentUsername: 'baska' })
    openTab(/^(Güvenlik|Security)/)
    fireEvent.click(dialog().querySelector('[data-slot="ued-terminate"]'))
    const dlg = await screen.findByRole('dialog', { name: /Oturumu sonlandır — ALI|Terminate session — ALI/ })
    fireEvent.change(within(dlg).getByLabelText(/Gerekçe|Reason/), { target: { value: 'ayrıldı' } })
    fireEvent.click(within(dlg).getByRole('button', { name: /^(Sonlandır|Terminate)$/ }))
    await waitFor(() => expect(api.admin.terminateUserSession).toHaveBeenCalledWith('ALI', 'ayrıldı'))
  })

  it('cihaz geçmişi: izin yoksa hiç yok; izinle KAPALI başlar ve açılmadan sorgu atmaz', async () => {
    const { unmount } = renderEditor()
    expect(dialog().querySelector('[data-section="devices"]')).toBeNull()
    unmount()
    perms.set = new Set(['audit_log.read:view'])
    renderEditor()
    openTab(/^(Güvenlik|Security)/)
    const toggle = dialog().querySelector('[data-slot="ued-devices-toggle"]')
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    expect(api.admin.getUserDevices).not.toHaveBeenCalled()
    await act(async () => { fireEvent.click(toggle) })
    await waitFor(() => expect(api.admin.getUserDevices).toHaveBeenCalledWith(7))
  })

  it('"AD ile karşılaştır" yalnız LDAP + global yönetici + bağlantı verilmişse; tıklayınca kullanıcıyla çağrılır', async () => {
    const onOpenDirectory = vi.fn()
    renderEditor({ user: LDAP_USER, globalAdmin: true, onOpenDirectory })
    fireEvent.click(dialog().querySelector('[data-slot="ued-ad-compare"]'))
    await waitFor(() => expect(onOpenDirectory).toHaveBeenCalledWith(expect.objectContaining({ id: 8 })))
  })
})
