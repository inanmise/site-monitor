import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react'
import { LangProvider } from '../i18n/index.jsx'
import { pressMenuTrigger } from './helpers/dropdownMenu.js'

/**
 * KULLANICI YÖNETİMİ — kimin hangi takımı gördüğünü belirleyen ekran; kendi testi yoktu
 * (AdminPanel.test.jsx bileşeni vi.mock'luyor → dolaylı kapsam sahte).
 *
 * En pahalı sessiz hata YETKİ GENİŞLEMESİ: kaydetme payload'ı takım yöneticisinin kendi takımını
 * DEĞİL formdaki takımı gönderirse, takım yöneticisi kendi kullanıcısını başka takıma ekleyip o
 * takımın tüm sertifika/alarm verisini okutabilir. Bu kural yalnız `save()` içindeki tek satırda
 * yaşıyor ve arayüzde hiçbir belirtisi yok — bu yüzden testle kilitleniyor.
 *
 * İkinci sınıf: "TEK ADMIN" ve "kendini silme" korumaları. Kaybolurlarsa sistem admin'siz kalır
 * (kimse yetki veremez) ve bu ancak bir sonraki yönetim işinde fark edilir.
 */
const confirmMock = vi.fn(() => Promise.resolve(true))
const toastMock = { success: vi.fn(), error: vi.fn(), info: vi.fn() }

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  formatDate: (s) => String(s ?? ''),
  api: withApiFallback({ admin: {
    searchUsers: vi.fn(),
    createUser: vi.fn(),
    updateUser: vi.fn(),
    deleteUser: vi.fn(),
    unlockUser: vi.fn(),
    unlockUserRole: vi.fn(),
    unlockUserOrgRole: vi.fn(),
    unlockUserTeams: vi.fn(),
    getUserAvatar: vi.fn(),
  } }),
}))
vi.mock('../components/ui/Dialog.jsx', () => ({
  useDialog: () => ({ showConfirm: confirmMock }),
  DialogProvider: ({ children }) => children,
}))
vi.mock('../components/ui/Toast.jsx', () => ({
  useToast: () => toastMock,
  ToastProvider: ({ children }) => children,
}))

import { api } from '../api/client'
import UserManager from '../components/admin/UserManager.jsx'

const TEAMS = [{ id: 5, name: 'SY-A' }, { id: 9, name: 'SY-B' }]

const USERS = [
  { id: 1, username: 'ali', display_name: 'Ali V', email: 'ali@example.com',
    system_role: 'USER', team_ids: [5], active: true },
  { id: 2, username: 'admin2', display_name: 'Admin İki', email: 'a2@example.com',
    system_role: 'ADMIN', team_ids: [5], active: true },
  { id: 3, username: 'kilitli', display_name: 'Kilitli K', email: 'k@example.com',
    system_role: 'USER', team_ids: [9], active: true, permanent_lock: true },
  // Takım kilidi (2026-09-18): admin üyeliği elle değiştirdi → LDAP girişi ezmez; rozet + "AD'ye geri ver"
  { id: 4, username: 'cokTakim', display_name: 'Çok Takım', email: 'ct@example.com',
    system_role: 'USER', team_ids: [5, 9], active: true, team_locked: true },
]

const renderUm = (props = {}) => render(
  <LangProvider>
    <UserManager systemRole="ADMIN" currentUsername="admin1" teams={TEAMS} {...props} />
  </LangProvider>
)

async function openRowMenu(username) {
  const row = (await screen.findByText(username)).closest('tr')
  pressMenuTrigger(within(row).getByRole('button', { name: /işlem|actions/i }))
}

describe('UserManager', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    confirmMock.mockResolvedValue(true)
    api.admin.searchUsers.mockResolvedValue({
      success: true, data: USERS, total: USERS.length, page: 0, active_admin_count: 2,
    })
    api.admin.updateUser.mockResolvedValue({ success: true })
    api.admin.createUser.mockResolvedValue({ success: true })
    api.admin.deleteUser.mockResolvedValue({ success: true })
    api.admin.unlockUser.mockResolvedValue({ success: true })
  })

  it('kullanıcıları ve takım adlarını basar', async () => {
    renderUm()
    expect(await screen.findByText('ali')).toBeInTheDocument()
    expect(screen.getAllByText('SY-A').length).toBeGreaterThan(0)
  })

  // ── Yetki genişlemesi ────────────────────────────────────────────────────────

  it('TEAM_ADMIN kaydında takım KENDİ takımına sabitlenir (formdaki değer dikkate alınmaz)', async () => {
    renderUm({ systemRole: 'TEAM_ADMIN', ownTeamId: 9 })
    await openRowMenu('ali')                       // team_ids: [5]
    fireEvent.click(await screen.findByText(/^Düzenle$|^Edit$/))
    fireEvent.click(await screen.findByRole('button', { name: /^Kaydet$|^Save$/ }))

    await waitFor(() => expect(api.admin.updateUser).toHaveBeenCalled())
    const [id, payload] = api.admin.updateUser.mock.calls[0]
    expect(id).toBe(1)
    expect(payload.team_ids).toEqual([9])               // kendi takımı — formdaki 5 DEĞİL
    expect(payload.team_id).toBe(9)
  })

  it('ADMIN kaydında kullanıcının takım seçimi KORUNUR', async () => {
    renderUm()
    await openRowMenu('ali')
    fireEvent.click(await screen.findByText(/^Düzenle$|^Edit$/))
    fireEvent.click(await screen.findByRole('button', { name: /^Kaydet$|^Save$/ }))

    await waitFor(() => expect(api.admin.updateUser).toHaveBeenCalled())
    expect(api.admin.updateUser.mock.calls[0][1].team_ids).toEqual([5])
  })

  it('düzenleme DOĞRU kullanıcıyı hedefler (satır→id eşlemesi kaymaz)', async () => {
    renderUm()
    await openRowMenu('kilitli')                        // üçüncü satır → id=3
    fireEvent.click(await screen.findByText(/^Düzenle$|^Edit$/))
    fireEvent.click(await screen.findByRole('button', { name: /^Kaydet$|^Save$/ }))

    await waitFor(() => expect(api.admin.updateUser).toHaveBeenCalled())
    expect(api.admin.updateUser.mock.calls[0][0]).toBe(3)
    expect(api.admin.createUser).not.toHaveBeenCalled()   // düzenleme create'e düşmemeli
  })

  // ── Silme korumaları ────────────────────────────────────────────────────────

  it('SİLME onay ister; iptalde kullanıcı silinmez', async () => {
    confirmMock.mockResolvedValue(false)
    renderUm()
    await openRowMenu('ali')
    fireEvent.click(await screen.findByText(/^Sil$|^Delete$/))

    await waitFor(() => expect(confirmMock).toHaveBeenCalled())
    expect(api.admin.deleteUser).not.toHaveBeenCalled()
  })

  it('onaylanınca doğru id ile silinir ve liste yenilenir', async () => {
    renderUm()
    const before = api.admin.searchUsers.mock.calls.length
    await openRowMenu('ali')
    fireEvent.click(await screen.findByText(/^Sil$|^Delete$/))

    await waitFor(() => expect(api.admin.deleteUser).toHaveBeenCalledWith(1))
    await waitFor(() => expect(api.admin.searchUsers.mock.calls.length).toBeGreaterThan(before))
  })

  it('KENDİNİ silme seçeneği menüde YOK (kullanıcı kendi erişimini kesemez)', async () => {
    renderUm({ currentUsername: 'ali' })
    await openRowMenu('ali')

    expect(await screen.findByText(/^Düzenle$|^Edit$/)).toBeInTheDocument()   // menü açık
    expect(screen.queryByText(/^Sil$|^Delete$/)).toBeNull()
  })

  it('SON aktif admin silinemez — rozet gösterilir ve menüde silme yok', async () => {
    api.admin.searchUsers.mockResolvedValue({
      success: true, data: USERS, total: 3, page: 0, active_admin_count: 1,
    })
    renderUm()
    await openRowMenu('admin2')

    expect(screen.queryByText(/^Sil$|^Delete$/)).toBeNull()
    expect(screen.getByText(/TEK ADMIN|LAST ADMIN/)).toBeInTheDocument()
  })

  it('silme sunucuda reddedilirse hata bildirilir, başarı denmez', async () => {
    api.admin.deleteUser.mockResolvedValue({ success: false, error: 'son admin' })
    renderUm()
    await openRowMenu('ali')
    fireEvent.click(await screen.findByText(/^Sil$|^Delete$/))

    await waitFor(() => expect(toastMock.error).toHaveBeenCalled())
    expect(toastMock.success).not.toHaveBeenCalled()
  })

  // ── Doğrulama / kilit açma ──────────────────────────────────────────────────

  it('geçersiz e-posta kaydetmeyi ENGELLER (istek hiç gitmez)', async () => {
    renderUm()
    await openRowMenu('ali')
    fireEvent.click(await screen.findByText(/^Düzenle$|^Edit$/))

    const emailInput = document.querySelector('input[value="ali@example.com"]')
    fireEvent.change(emailInput, { target: { value: 'bozuk-adres' } })
    fireEvent.click(screen.getByRole('button', { name: /^Kaydet$|^Save$/ }))

    await waitFor(() => expect(screen.getByText(/Geçerli bir e-posta|valid email/i)).toBeInTheDocument())
    expect(api.admin.updateUser).not.toHaveBeenCalled()
  })

  it('kilit açma yalnız KİLİTLİ kullanıcıda görünür ve doğru id ile çağrılır', async () => {
    renderUm()
    await openRowMenu('ali')                       // permanent_lock yok
    expect(screen.queryByText(/Kilidi Aç|Unlock/i)).toBeNull()
    fireEvent.keyDown(document, { key: 'Escape' })

    await openRowMenu('kilitli')
    fireEvent.click(await screen.findByText(/Kilidi Aç|Unlock/i))

    await waitFor(() => expect(api.admin.unlockUser).toHaveBeenCalledWith(3))
  })

  it('takım kilidi: rozet yalnız team_locked kullanıcıda; menüden "AD-ye geri ver" doğru id ile çağrılır', async () => {
    api.admin.unlockUserTeams.mockResolvedValue({ success: true })
    renderUm()
    const lockedRow = (await screen.findByText('cokTakim')).closest('tr')
    expect(within(lockedRow).getByTitle(/Takımlar kilitli|Teams locked/i)).toBeInTheDocument()
    const plainRow = screen.getByText('ali').closest('tr')
    expect(within(plainRow).queryByTitle(/Takımlar kilitli|Teams locked/i)).toBeNull()

    await openRowMenu('ali')
    expect(screen.queryByText(/Takımları AD'ye geri ver|Return teams to AD/i)).toBeNull()
    fireEvent.keyDown(document, { key: 'Escape' })

    await openRowMenu('cokTakim')
    fireEvent.click(await screen.findByText(/Takımları AD'ye geri ver|Return teams to AD/i))
    await waitFor(() => expect(api.admin.unlockUserTeams).toHaveBeenCalledWith(4))
  })

  it('kaydetme reddedilirse modal AÇIK kalır ve hata gösterilir', async () => {
    api.admin.updateUser.mockResolvedValue({ success: false, error: 'çakışma' })
    renderUm()
    await openRowMenu('ali')
    fireEvent.click(await screen.findByText(/^Düzenle$|^Edit$/))
    fireEvent.click(await screen.findByRole('button', { name: /^Kaydet$|^Save$/ }))

    await waitFor(() => expect(screen.getByText('çakışma')).toBeInTheDocument())
    expect(screen.getByRole('button', { name: /^Kaydet$|^Save$/ })).toBeInTheDocument()
    expect(toastMock.success).not.toHaveBeenCalled()
  })
})
