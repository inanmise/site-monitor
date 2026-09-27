import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react'
import { LangProvider } from '../i18n/index.jsx'

/**
 * YETKİ YÖNETİMİ — izin sisteminin arayüzü (2026-09-27 shadcn yeniden tasarımı).
 *
 * Asıl risk hücre eşlemesi: bir off-by-one ya da yanlış sıralama, TIKLANAN hücreden BAŞKA bir (rol, kaynak, aksiyon)
 * üçlüsünü sunucuya gönderir; ekranda doğru görünür, yetki yanlış değişir. Hassas yetkilerde onayın atlanması sessiz bir
 * yetki yükseltmesidir. Yeniden tasarımla değişiklikler ANINDA gitmiyor: çevirmek "bekleyen" kümeye yazar, Kaydet aynı
 * hücre-başına PUT sözleşmesiyle SIRALI gönderir (sunucu değişmedi) — kısmi hatada başarılılar uygulanır.
 */
const confirmMock = vi.fn(() => Promise.resolve(true))
const toastMock = { success: vi.fn(), error: vi.fn(), info: vi.fn() }
const refreshMock = vi.fn()
const mobile = vi.hoisted(() => ({ on: false }))

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  formatDate: (s) => String(s ?? ''),
  api: withApiFallback({ admin: {
    getPermissionMatrix: vi.fn(),
    updatePermissionGrant: vi.fn(),
    resetPermissionsToDefaults: vi.fn(),
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
vi.mock('../contexts/PermissionsProvider.jsx', () => ({
  usePermissions: () => ({ refresh: refreshMock, canView: () => true, canEdit: () => true, canExecute: () => true }),
  PermissionsProvider: ({ children }) => children,
}))
vi.mock('../hooks/use-mobile.js', () => ({ useIsMobile: () => mobile.on }))

import { api } from '../api/client'
import PermissionMatrix from '../components/admin/PermissionMatrix.jsx'

// Sertifika grubu: tek kaynak, üç aksiyon, "execute" HASSAS (hücre indeksleri elle sayılabilsin).
// İletişim grubu: backend aynı anahtarı eylem başına İKİ satır döndürür (notification.groups) — tek satırda birleşmeli.
const CATALOG = [
  { group: 'certificates', resource_key: 'inventory.crud', actions: ['view', 'edit', 'execute'], sensitive: ['execute'] },
  { group: 'communication', resource_key: 'notification.groups', actions: ['view'], sensitive: [] },
  { group: 'communication', resource_key: 'notification.groups', actions: ['edit'], sensitive: [] },
]

const GRANTS = [
  { role: 'TEAM_ADMIN', resource_key: 'inventory.crud', action: 'view',    allowed: true, updated_by: 'demo', updated_at: '2026-09-20T10:00:00' },
  { role: 'TEAM_ADMIN', resource_key: 'inventory.crud', action: 'edit',    allowed: false, updated_by: 'ops', updated_at: '2026-09-25T09:30:00' },
  { role: 'USER',       resource_key: 'inventory.crud', action: 'view',    allowed: false },
  { role: 'USER',       resource_key: 'notification.groups', action: 'view', allowed: true },
]

// Grup varsayılan KAPALI (2026-09-26); satır iddiaları için Sertifika Yönetimi açık başlatılır.
const renderMatrix = (props = { initialOpenGroup: 'certificates' }) =>
  render(<LangProvider><PermissionMatrix {...props} /></LangProvider>)

/** Tablodaki tıklanabilir hücreler: ADMIN kilitli → sıra TEAM_ADMIN, USER, AUDIT × view/edit/execute. */
const pills = () => [...document.querySelectorAll('[data-cell="toggle"] [data-slot="switch"]')]
const header = () => within(document.querySelector('[data-slot="page-actions"]'))
const bar = () => document.querySelector('[data-slot="perm-changes-bar"]')
const ready = () => waitFor(() => expect(pills().length).toBeGreaterThan(0))

describe('PermissionMatrix', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mobile.on = false
    confirmMock.mockResolvedValue(true)
    api.admin.getPermissionMatrix.mockResolvedValue({ success: true, catalog: CATALOG, grants: GRANTS, can_edit: true })
    api.admin.updatePermissionGrant.mockImplementation((body) =>
      Promise.resolve({ success: true, data: { ...body, updated_by: 'demo', updated_at: '2026-09-27T08:00:00' } }))
    api.admin.resetPermissionsToDefaults.mockResolvedValue({ success: true })
  })

  it('ADMIN sütunu KİLİTLİ — tıklanabilir hücre üretmez (admin yetkisi arayüzden kısılamaz)', async () => {
    renderMatrix()
    await ready()
    // Kapsam TABLO hücreleri: açıklama satırında da bir kilit rozeti var, sayıma girmemeli.
    expect(document.querySelectorAll('[data-cell="locked"] [data-slot="perm-locked-badge"]').length).toBe(3)   // ADMIN × 3 aksiyon
    expect(pills()).toHaveLength(9)   // kalan 3 rol × 3 aksiyon
  })

  it('başlık: sayfa adı, açıklama, sayaç çipleri ve son değişiklik (kim · ne zaman)', async () => {
    renderMatrix()
    await ready()
    expect(screen.getByRole('heading', { level: 2, name: 'Permission Management' })).toBeInTheDocument()
    expect(screen.getByText('Which role can see, edit or act on each resource.')).toBeInTheDocument()
    const meta = within(document.querySelector('[data-slot="page-meta"]'))
    expect(meta.getByText('2 resources')).toBeInTheDocument()   // notification.groups iki satır → TEK kaynak
    expect(meta.getByText('4 roles')).toBeInTheDocument()
    // En yeni updated_at kazanır (ops, 09-25) — sıralama değil tarih
    expect(document.querySelector('[data-slot="perm-last-change"]')).toHaveTextContent('Last changed by ops · 2026-09-25T09:30:00')
  })

  it('çevirmek İSTEK ATMAZ: hücre bekleyen olur; Kaydet TAM OLARAK tıklanan (rol, kaynak, aksiyon) üçlülerini gönderir', async () => {
    renderMatrix()
    await ready()
    expect(header().getByRole('button', { name: 'Save changes' })).toBeDisabled()
    expect(header().getByRole('button', { name: 'Discard' })).toBeDisabled()

    fireEvent.click(pills()[0])   // TEAM_ADMIN / view: verili → geri alma
    fireEvent.click(pills()[1])   // TEAM_ADMIN / edit: yok → verme
    fireEvent.click(pills()[3])   // sıradaki ROL (USER) / view — rol sınırı doğru
    expect(api.admin.updatePermissionGrant).not.toHaveBeenCalled()
    expect(bar()).toHaveTextContent('3 unsaved changes')
    expect(pills()[1]).toHaveAttribute('aria-checked', 'true')
    expect(pills()[1].closest('td')).toHaveAttribute('data-changed', 'true')

    fireEvent.click(header().getByRole('button', { name: 'Save changes' }))
    await waitFor(() => expect(api.admin.updatePermissionGrant).toHaveBeenCalledTimes(3))
    expect(api.admin.updatePermissionGrant.mock.calls.map((c) => c[0])).toEqual([
      { role: 'TEAM_ADMIN', resource_key: 'inventory.crud', action: 'view', allowed: false },
      { role: 'TEAM_ADMIN', resource_key: 'inventory.crud', action: 'edit', allowed: true },
      { role: 'USER', resource_key: 'inventory.crud', action: 'view', allowed: true },
    ])
    await waitFor(() => expect(toastMock.success).toHaveBeenCalledWith('3 permission changes saved.'))
    expect(bar()).toBeNull()
    expect(refreshMock).toHaveBeenCalledTimes(1)   // kendi yetki anlık görüntüsü bir kez tazelenir
    expect(pills()[1]).toHaveAttribute('aria-checked', 'true')   // sunucu yanıtı uygulandı
    expect(pills()[1].closest('td')).not.toHaveAttribute('data-changed')
    expect(document.querySelector('[data-slot="perm-last-change"]')).toHaveTextContent('Last changed by demo · 2026-09-27T08:00:00')
  })

  it('sunucudaki değere geri çevrilen hücre bekleyenden ÇIKAR (değişiklik sayılmaz)', async () => {
    renderMatrix()
    await ready()
    fireEvent.click(pills()[1])
    expect(bar()).toHaveTextContent('1 unsaved change')
    fireEvent.click(pills()[1])
    expect(bar()).toBeNull()
    expect(header().getByRole('button', { name: 'Save changes' })).toBeDisabled()
  })

  it('mevcut yetki durumu shadcn Switch aria-checked ile doğru yansır', async () => {
    renderMatrix()
    await ready()
    expect(pills()[0].getAttribute('aria-checked')).toBe('true')    // TEAM_ADMIN/view verili
    expect(pills()[1].getAttribute('aria-checked')).toBe('false')   // TEAM_ADMIN/edit yok
  })

  it('HASSAS yetki VERİLİRKEN onay ister; iptal edilirse bekleyen değişiklik OLUŞMAZ', async () => {
    confirmMock.mockResolvedValue(false)
    renderMatrix()
    await ready()
    fireEvent.click(pills()[2])   // TEAM_ADMIN / execute → hassas, şu an kapalı
    await waitFor(() => expect(confirmMock).toHaveBeenCalled())
    expect(confirmMock.mock.calls[0][0].variant).toBe('danger')
    expect(pills()[2]).toHaveAttribute('aria-checked', 'false')
    expect(bar()).toBeNull()
  })

  it('HASSAS yetki onaylanınca bekleyen olur ve kaydedilir', async () => {
    renderMatrix()
    await ready()
    fireEvent.click(pills()[2])
    await waitFor(() => expect(pills()[2]).toHaveAttribute('aria-checked', 'true'))
    fireEvent.click(header().getByRole('button', { name: 'Save changes' }))
    await waitFor(() => expect(api.admin.updatePermissionGrant).toHaveBeenCalledWith({
      role: 'TEAM_ADMIN', resource_key: 'inventory.crud', action: 'execute', allowed: true }))
  })

  it('hassas yetki GERİ ALINIRKEN onay istenmez (kısıtlama yönü serbest)', async () => {
    api.admin.getPermissionMatrix.mockResolvedValue({ success: true, catalog: CATALOG, grants: [
      ...GRANTS, { role: 'TEAM_ADMIN', resource_key: 'inventory.crud', action: 'execute', allowed: true },
    ], can_edit: true })
    renderMatrix()
    await ready()
    fireEvent.click(pills()[2])   // execute açık → kapatma
    expect(confirmMock).not.toHaveBeenCalled()
    expect(pills()[2]).toHaveAttribute('aria-checked', 'false')
  })

  it('sunucu reddederse: hata bildirimi, hücre BEKLEYEN kalır (kaydedilemedi), sunucu durumu DEĞİŞMEZ; panel hatayı listeler', async () => {
    api.admin.updatePermissionGrant.mockResolvedValue({ success: false, error: '403' })
    renderMatrix()
    await ready()
    fireEvent.click(pills()[1])
    fireEvent.click(header().getByRole('button', { name: 'Save changes' }))

    await waitFor(() => expect(toastMock.error).toHaveBeenCalledWith('1 of 1 changes could not be saved'))
    expect(pills()[1].closest('td')).toHaveAttribute('data-failed', 'true')
    const sheet = await screen.findByRole('dialog', { name: 'Review changes' })   // hata paneli kendiliğinden açılır
    expect(within(sheet).getByText('Not saved: 403')).toBeInTheDocument()
    expect(sheet.querySelector('[data-slot="alert"][data-tone="danger"]')).not.toBeNull()
    expect(bar()).toBeNull()   // panel açıkken yüzen çubuk gizli (panelin kendi Kaydet'i var)
    expect(refreshMock).not.toHaveBeenCalled()

    fireEvent.click(within(sheet).getByRole('button', { name: 'Close' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(bar()).toHaveTextContent('1 not saved')

    // Geri al → sunucudaki değer (yok) geri gelir: ekran yalan söylemez
    fireEvent.click(within(bar()).getByRole('button', { name: 'Review' }))
    const again = await screen.findByRole('dialog', { name: 'Review changes' })
    fireEvent.click(within(again).getByRole('button', { name: 'Undo: TEAM_ADMIN — inventory.crud — Edit' }))
    await waitFor(() => expect(pills()[1]).toHaveAttribute('aria-checked', 'false'))
  })

  it('KISMİ hata: başarılı olan uygulanır ve listeden düşer, başarısız olan kalır', async () => {
    api.admin.updatePermissionGrant
      .mockImplementationOnce((body) => Promise.resolve({ success: true, data: body }))
      .mockImplementationOnce(() => Promise.reject(new Error('network down')))
    renderMatrix()
    await ready()
    fireEvent.click(pills()[1])   // TEAM_ADMIN edit → başarılı
    fireEvent.click(pills()[3])   // USER view → ağ hatası
    fireEvent.click(header().getByRole('button', { name: 'Save changes' }))

    await waitFor(() => expect(toastMock.error).toHaveBeenCalledWith('1 of 2 changes could not be saved'))
    expect(pills()[1].closest('td')).not.toHaveAttribute('data-changed')
    expect(pills()[3].closest('td')).toHaveAttribute('data-failed', 'true')
    expect(refreshMock).toHaveBeenCalledTimes(1)
    const sheet = await screen.findByRole('dialog', { name: 'Review changes' })
    expect(within(sheet).getAllByRole('listitem')).toHaveLength(1)
    expect(within(sheet).getByText('Not saved: network down')).toBeInTheDocument()
    fireEvent.click(within(sheet).getByRole('button', { name: 'Keep editing' }))
    await waitFor(() => expect(bar()).toHaveTextContent('1 unsaved change'))
    expect(bar()).toHaveTextContent('1 not saved')
  })

  it('kaydederken ilerleme N/M görünür, anahtarlar ve düğmeler kilitli', async () => {
    let release
    api.admin.updatePermissionGrant.mockImplementationOnce((body) => new Promise((r) => { release = () => r({ success: true, data: body }) }))
    renderMatrix()
    await ready()
    fireEvent.click(pills()[1])
    fireEvent.click(pills()[4])
    fireEvent.click(header().getByRole('button', { name: 'Save changes' }))
    await waitFor(() => expect(within(bar()).getByText('Saving… 0 of 2')).toBeInTheDocument())
    expect(pills().every((p) => p.disabled)).toBe(true)
    expect(header().getByRole('button', { name: 'Save changes' })).toHaveAttribute('aria-busy', 'true')
    release()
    await waitFor(() => expect(bar()).toBeNull())
    expect(pills().some((p) => p.disabled)).toBe(false)
  })

  it('gözden geçirme paneli: rol · kaynak · tür · eski → yeni; tek tek geri alma sayacı düşürür', async () => {
    renderMatrix()
    await ready()
    fireEvent.click(pills()[0])   // TEAM_ADMIN view: Granted → Not granted
    fireEvent.click(pills()[4])   // USER edit: Not granted → Granted
    fireEvent.click(within(bar()).getByRole('button', { name: 'Review' }))

    const sheet = await screen.findByRole('dialog', { name: 'Review changes' })
    const items = within(sheet).getAllByRole('listitem')
    expect(items).toHaveLength(2)
    expect(items[0]).toHaveTextContent('TEAM_ADMIN')
    expect(items[0]).toHaveTextContent('inventory.crud')
    expect(items[0]).toHaveTextContent(/View.*Granted.*becomes.*Not granted/)
    expect(items[1]).toHaveTextContent(/USER.*Edit.*Not granted.*becomes.*Granted/)

    fireEvent.click(within(sheet).getByRole('button', { name: 'Undo: TEAM_ADMIN — inventory.crud — View' }))
    await waitFor(() => expect(within(sheet).getAllByRole('listitem')).toHaveLength(1))
    expect(pills()[0]).toHaveAttribute('aria-checked', 'true')
    expect(bar()).toBeNull()   // panel açıkken yüzen çubuk gizli

    // Panelden kaydet → yalnız kalan tek değişiklik gider, panel kapanır
    fireEvent.click(within(sheet).getByRole('button', { name: 'Save changes' }))
    await waitFor(() => expect(api.admin.updatePermissionGrant).toHaveBeenCalledTimes(1))
    expect(api.admin.updatePermissionGrant.mock.calls[0][0]).toEqual({ role: 'USER', resource_key: 'inventory.crud', action: 'edit', allowed: true })
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
  })

  it('Vazgeç ONAY ister; iptalde değişiklikler kalır, onayda hepsi geri alınır (istek yok)', async () => {
    renderMatrix()
    await ready()
    fireEvent.click(pills()[1])
    fireEvent.click(pills()[3])
    confirmMock.mockResolvedValueOnce(false)
    fireEvent.click(header().getByRole('button', { name: 'Discard' }))
    await waitFor(() => expect(confirmMock).toHaveBeenCalledTimes(1))
    expect(confirmMock.mock.calls[0][0].message).toMatch(/2 unsaved changes will be undone/)
    expect(bar()).toHaveTextContent('2 unsaved changes')

    fireEvent.click(within(bar()).getByRole('button', { name: 'Discard' }))
    await waitFor(() => expect(bar()).toBeNull())
    expect(pills()[1]).toHaveAttribute('aria-checked', 'false')
    expect(api.admin.updatePermissionGrant).not.toHaveBeenCalled()
  })

  it('kaydedilmemiş değişiklik varken sayfadan çıkış tarayıcı uyarısı alır; temizken almaz', async () => {
    renderMatrix()
    await ready()
    const fire = () => { const ev = new Event('beforeunload', { cancelable: true }); window.dispatchEvent(ev); return ev.defaultPrevented }
    expect(fire()).toBe(false)
    fireEvent.click(pills()[1])
    expect(fire()).toBe(true)
  })

  it('varsayılanlara sıfırlama ONAY ister; iptalde istek gitmez', async () => {
    confirmMock.mockResolvedValue(false)
    renderMatrix()
    await ready()
    fireEvent.click(screen.getByRole('button', { name: /reset|varsayılan/i }))
    await waitFor(() => expect(confirmMock).toHaveBeenCalled())
    expect(api.admin.resetPermissionsToDefaults).not.toHaveBeenCalled()
  })

  it('sıfırlama onaylanınca ucu çağırır, bekleyenleri siler ve matrisi YENİDEN yükler; onay metni bekleyenleri anar', async () => {
    renderMatrix()
    await waitFor(() => expect(api.admin.getPermissionMatrix).toHaveBeenCalledTimes(1))
    await ready()
    fireEvent.click(pills()[1])
    fireEvent.click(screen.getByRole('button', { name: /reset|varsayılan/i }))
    await waitFor(() => expect(api.admin.resetPermissionsToDefaults).toHaveBeenCalled())
    expect(confirmMock.mock.calls[0][0].message).toMatch(/Your 1 unsaved changes will be discarded as well/)
    await waitFor(() => expect(api.admin.getPermissionMatrix).toHaveBeenCalledTimes(2))
    await waitFor(() => expect(bar()).toBeNull())
    expect(refreshMock).toHaveBeenCalled()
  })

  // 2026-09-25 kullanıcı kararı: admin dışındakiler matrisi OKUR ama değiştiremez (sunucu can_edit=false).
  it('SALT OKUNUR (can_edit=false): bilgi şeridi + rozet, anahtarlar KAPALI ama durumu gösterir, eylem düğmesi YOK, tıklama değişiklik üretmez', async () => {
    api.admin.getPermissionMatrix.mockResolvedValue({ success: true, catalog: CATALOG, grants: GRANTS, can_edit: false })
    renderMatrix()
    expect(await screen.findByText(/Salt okunur görünüm|Read-only view/)).toBeInTheDocument()
    await ready()
    expect(within(document.querySelector('[data-slot="page-meta"]')).getByText('Read-only')).toBeInTheDocument()
    expect(pills().every((p) => p.disabled)).toBe(true)
    expect(pills()[0].getAttribute('aria-checked')).toBe('true')      // TEAM_ADMIN/view verili — okunabilir
    expect(screen.queryByRole('button', { name: /reset|varsayılan/i })).toBeNull()
    expect(document.querySelector('[data-slot="page-actions"]')).toBeNull()
    expect(screen.queryByRole('button', { name: /Unsaved only/ })).toBeNull()
    fireEvent.click(pills()[1])
    expect(bar()).toBeNull()
    expect(api.admin.updatePermissionGrant).not.toHaveBeenCalled()
  })

  it('düzenlenebilir kipte salt-okunur şeridi YOK; can_edit alanı hiç gelmezse güvenli taraf: salt okunur', async () => {
    renderMatrix()
    await ready()
    expect(screen.queryByText(/Salt okunur görünüm|Read-only view/)).toBeNull()
    expect(pills().some((p) => p.disabled)).toBe(false)

    api.admin.getPermissionMatrix.mockResolvedValue({ success: true, catalog: CATALOG, grants: GRANTS })
    renderMatrix()
    expect(await screen.findByText(/Salt okunur görünüm|Read-only view/)).toBeInTheDocument()
  })

  // R10 (2026-09-25): "bilinmiyor" ile "yetkin yok" aynı ekrana düşmemeli.
  const errorBanner = () => document.querySelector('[data-slot="alert"][data-tone="danger"]')

  it('R10: yükleme success:false → HATA bandı + yeniden dene; salt-okunur bandı ve anahtarlar YOK; yeniden deneyince matris gelir', async () => {
    api.admin.getPermissionMatrix.mockResolvedValueOnce({ success: false, error: '500' })
    renderMatrix()
    await waitFor(() => expect(errorBanner()).not.toBeNull())
    expect(errorBanner()).toHaveTextContent(/Yetki matrisi yüklenemedi|Could not load the permission matrix/)
    expect(screen.queryByText(/Salt okunur görünüm|Read-only view/)).toBeNull()
    expect(pills()).toHaveLength(0)
    expect(screen.queryByRole('button', { name: /reset|varsayılan/i })).toBeNull()

    fireEvent.click(screen.getByRole('button', { name: /^Yeniden dene$|^Try again$/ }))
    await waitFor(() => expect(pills()).toHaveLength(9))
    expect(api.admin.getPermissionMatrix).toHaveBeenCalledTimes(2)
    expect(errorBanner()).toBeNull()
  })

  it('R10: istek FIRLATIRSA ret yakalanır ve hata bandı çıkar (işlenmeyen ret yok); yükleme iskeleti kalkar', async () => {
    api.admin.getPermissionMatrix.mockRejectedValueOnce(new Error('ağ yok'))
    renderMatrix()
    await waitFor(() => expect(errorBanner()).not.toBeNull())
    expect(screen.queryByText(/Salt okunur görünüm|Read-only view/)).toBeNull()
    expect(screen.queryByText(/Yetkiler yükleniyor|Loading permissions/)).toBeNull()
    expect(document.querySelector('[data-slot="skeleton"]')).toBeNull()
  })

  it('yüklenirken iskelet + ekran okuyucu durumu', async () => {
    api.admin.getPermissionMatrix.mockReturnValue(new Promise(() => {}))
    renderMatrix()
    expect(screen.getByRole('status')).toHaveTextContent('Loading permissions…')
    expect(document.querySelectorAll('[data-slot="skeleton"]').length).toBeGreaterThan(0)
  })

  it('boş katalog → boş durum bloğu', async () => {
    api.admin.getPermissionMatrix.mockResolvedValue({ success: true, catalog: [], grants: [], can_edit: true })
    renderMatrix()
    expect(await screen.findByText('No permissions to show')).toBeInTheDocument()
  })

  // 2026-09-26 kullanıcı isteği: her grup (Sertifika Yönetimi dahil) varsayılan KAPALI.
  it('varsayılan: TÜM gruplar kapalı, hiç yetki satırı çizilmez; başlığa tıklayınca açılır', async () => {
    renderMatrix({})
    const cert = await screen.findByRole('button', { name: /Sertifika Yönetimi|Certificate Management/ })
    expect(screen.getAllByRole('button', { expanded: false }).length).toBeGreaterThan(0)
    expect(screen.queryAllByRole('button', { expanded: true })).toHaveLength(0)
    expect(pills()).toHaveLength(0)
    fireEvent.click(cert)
    expect(cert).toHaveAttribute('aria-expanded', 'true')
    expect(pills().length).toBeGreaterThan(0)
  })

  it('Tümünü aç / Tümünü kapat: bütün grupları birlikte açar ve kapatır', async () => {
    renderMatrix({})
    await screen.findByRole('button', { name: /^Certificate Management/ })
    fireEvent.click(screen.getByRole('button', { name: 'Expand all' }))
    expect(screen.getByRole('button', { name: /^Certificate Management/ })).toHaveAttribute('aria-expanded', 'true')
    expect(screen.getByRole('button', { name: /Communication/ })).toHaveAttribute('aria-expanded', 'true')
    expect(pills()).toHaveLength(9 + 6)
    fireEvent.click(screen.getByRole('button', { name: 'Collapse all' }))
    expect(pills()).toHaveLength(0)
    expect(screen.getByRole('button', { name: 'Expand all' })).toBeInTheDocument()
  })

  it('aynı kaynağın eylem başına ayrı katalog satırları TEK satırda birleşir', async () => {
    renderMatrix({ initialOpenGroup: 'communication' })
    await ready()
    expect(document.querySelectorAll('[data-resource="notification.groups"]')).toHaveLength(1)
    const row = document.querySelector('[data-resource="notification.groups"]')
    expect(within(row).getAllByRole('switch')).toHaveLength(6)   // 3 rol × (view + edit)
    expect(within(row).getByRole('switch', { name: 'USER — notification.groups — View' })).toBeChecked()
  })

  it('arama: eşleşen kaynağın grubunu açar, diğerlerini gizler; sonuç yoksa boş durum + süzgeçleri temizle', async () => {
    renderMatrix({})
    await screen.findByRole('button', { name: /^Certificate Management/ })
    const search = screen.getByRole('searchbox', { name: 'Search resources, descriptions or groups' })
    fireEvent.change(search, { target: { value: 'NOTIFICATION' } })
    expect(document.querySelector('[data-resource="notification.groups"]')).not.toBeNull()
    expect(document.querySelector('[data-resource="inventory.crud"]')).toBeNull()
    expect(screen.queryByRole('button', { name: /^Certificate Management/ })).toBeNull()

    // açıklama metninde de arar (i18n etiketi)
    fireEvent.change(search, { target: { value: 'certificate records' } })
    expect(document.querySelector('[data-resource="inventory.crud"]')).not.toBeNull()

    fireEvent.change(search, { target: { value: 'zzz-yok' } })
    expect(screen.getByText('No features match your search.')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Clear filters' }))
    expect(search).toHaveValue('')
    expect(screen.getByRole('button', { name: /^Certificate Management/ })).toBeInTheDocument()
  })

  it('tür süzgeci: yalnız seçilen türlerin sütunları; son tür kapatılamaz', async () => {
    renderMatrix()
    await ready()
    const kinds = within(screen.getByRole('group', { name: 'Permission types shown' }))
    fireEvent.click(kinds.getByRole('button', { name: /Edit/ }))
    fireEvent.click(kinds.getByRole('button', { name: /Execute/ }))
    expect(kinds.getByRole('button', { name: /View/ })).toHaveAttribute('aria-pressed', 'true')
    expect(pills()).toHaveLength(3)   // 3 rol × view
    expect(pills().map((p) => p.getAttribute('aria-label'))).toEqual([
      'TEAM_ADMIN — inventory.crud — View', 'USER — inventory.crud — View', 'AUDIT — inventory.crud — View'])
    fireEvent.click(kinds.getByRole('button', { name: /View/ }))   // son tür → yok sayılır
    expect(kinds.getByRole('button', { name: /View/ })).toHaveAttribute('aria-pressed', 'true')
    expect(pills()).toHaveLength(3)
  })

  it('"Yalnız hassas" ve "Kaydedilmemişler" süzgeçleri', async () => {
    renderMatrix({})
    await screen.findByRole('button', { name: /^Certificate Management/ })
    expect(screen.getByRole('button', { name: /Unsaved only/ })).toBeDisabled()   // bekleyen yokken anlamsız
    fireEvent.click(screen.getByRole('button', { name: 'Sensitive only' }))
    expect(document.querySelector('[data-resource="inventory.crud"]')).not.toBeNull()
    expect(screen.queryByRole('button', { name: /Communication/ })).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'Sensitive only' }))

    fireEvent.click(screen.getByRole('button', { name: 'Expand all' }))
    fireEvent.click(screen.getByRole('switch', { name: 'AUDIT — notification.groups — Edit' }))
    fireEvent.click(screen.getByRole('button', { name: /Unsaved only/ }))
    expect(document.querySelector('[data-resource="inventory.crud"]')).toBeNull()
    expect(document.querySelector('[data-resource="notification.groups"]')).not.toBeNull()
    expect(screen.getByRole('button', { name: /Communication.*1 changed/ })).toBeInTheDocument()
  })

  it('rol özet kartları: N / M açık (bekleyen dahil), tıklayınca sütun vurgulanır', async () => {
    renderMatrix()
    await ready()
    const cards = document.querySelectorAll('[data-slot="perm-role-card"]')
    expect(cards).toHaveLength(4)
    const teamAdmin = screen.getByRole('button', { name: 'TEAM_ADMIN: 1 of 5 permissions granted' })
    expect(screen.getByRole('button', { name: 'ADMIN: 5 of 5 permissions granted' })).toHaveTextContent('Full access — cannot be changed')
    fireEvent.click(pills()[1])   // TEAM_ADMIN edit → açık (bekleyen)
    expect(screen.getByRole('button', { name: 'TEAM_ADMIN: 2 of 5 permissions granted' })).toBe(teamAdmin)

    expect(teamAdmin).toHaveAttribute('aria-pressed', 'false')
    fireEvent.click(teamAdmin)
    expect(teamAdmin).toHaveAttribute('aria-pressed', 'true')
    expect(document.querySelector('th[data-role="TEAM_ADMIN"]')).toHaveAttribute('data-highlight', 'true')
    fireEvent.click(teamAdmin)
    expect(document.querySelector('th[data-role="TEAM_ADMIN"]')).not.toHaveAttribute('data-highlight')
  })

  it('klavye: matriste TEK sekme durağı; oklar hücreler arasında gezer (kilitli/geçersiz hücreleri atlar), Home/End', async () => {
    renderMatrix({})
    await screen.findByRole('button', { name: /^Certificate Management/ })
    fireEvent.click(screen.getByRole('button', { name: 'Expand all' }))
    const sw = (name) => screen.getByRole('switch', { name })
    expect(pills().filter((p) => p.tabIndex === 0)).toHaveLength(1)
    expect(sw('TEAM_ADMIN — inventory.crud — View').tabIndex).toBe(0)

    sw('TEAM_ADMIN — inventory.crud — View').focus()
    fireEvent.keyDown(document.activeElement, { key: 'ArrowRight' })
    expect(document.activeElement).toBe(sw('TEAM_ADMIN — inventory.crud — Edit'))
    expect(sw('TEAM_ADMIN — inventory.crud — Edit').tabIndex).toBe(0)   // durak odakla birlikte taşınır
    expect(sw('TEAM_ADMIN — inventory.crud — View').tabIndex).toBe(-1)

    fireEvent.keyDown(document.activeElement, { key: 'ArrowDown' })
    expect(document.activeElement).toBe(sw('TEAM_ADMIN — notification.groups — Edit'))
    fireEvent.keyDown(document.activeElement, { key: 'ArrowLeft' })
    expect(document.activeElement).toBe(sw('TEAM_ADMIN — notification.groups — View'))
    fireEvent.keyDown(document.activeElement, { key: 'ArrowLeft' })   // solda yalnız kilitli ADMIN → yerinde kalır
    expect(document.activeElement).toBe(sw('TEAM_ADMIN — notification.groups — View'))
    fireEvent.keyDown(document.activeElement, { key: 'End' })
    expect(document.activeElement).toBe(sw('AUDIT — notification.groups — Edit'))
    fireEvent.keyDown(document.activeElement, { key: 'ArrowUp' })
    expect(document.activeElement).toBe(sw('AUDIT — inventory.crud — Edit'))
    fireEvent.keyDown(document.activeElement, { key: 'Home' })
    expect(document.activeElement).toBe(sw('TEAM_ADMIN — inventory.crud — View'))
    fireEvent.keyDown(document.activeElement, { key: 'End', ctrlKey: true })
    expect(document.activeElement).toBe(sw('AUDIT — notification.groups — Edit'))
  })

  describe('telefon (<768 px)', () => {
    beforeEach(() => { mobile.on = true })

    it('rol seçici + gruplar kapalı kartlar; anahtar satırları etiketli ve seçili role bağlı', async () => {
      renderMatrix({})
      const picker = await screen.findByRole('radiogroup', { name: 'Role' })
      expect(within(picker).getAllByRole('radio')).toHaveLength(4)
      expect(within(picker).getByRole('radio', { name: 'TEAM_ADMIN' })).toHaveAttribute('aria-checked', 'true')
      expect(document.querySelector('[data-testid="perm-matrix"]')).toBeNull()   // tablo YOK
      expect(screen.queryAllByRole('switch')).toHaveLength(0)                  // gruplar kapalı

      fireEvent.click(screen.getByRole('button', { name: /^Certificate Management/ }))
      const card = document.querySelector('[data-slot="perm-mobile-card"][data-resource="inventory.crud"]')
      expect(within(card).getAllByRole('switch').map((s) => s.getAttribute('aria-label'))).toEqual([
        'TEAM_ADMIN — inventory.crud — View', 'TEAM_ADMIN — inventory.crud — Edit', 'TEAM_ADMIN — inventory.crud — Execute'])
      expect(within(card).getByText('Add, edit, delete certificate records.')).toBeInTheDocument()   // açıklama GÖRÜNÜR

      fireEvent.click(within(picker).getByRole('radio', { name: 'USER' }))
      expect(within(card).getByRole('switch', { name: 'USER — inventory.crud — View' })).not.toBeChecked()
      fireEvent.click(within(card).getByRole('switch', { name: 'USER — inventory.crud — Edit' }))
      expect(bar()).toHaveTextContent('1 unsaved change')

      fireEvent.click(within(picker).getByRole('radio', { name: 'ADMIN' }))
      expect(within(card).queryAllByRole('switch')).toHaveLength(0)
      expect(within(card).getAllByText('Always granted')).toHaveLength(3)
    })

    it('özet kartına dokunmak rol seçicisini o role çevirir', async () => {
      renderMatrix({})
      const picker = await screen.findByRole('radiogroup', { name: 'Role' })
      fireEvent.click(screen.getByRole('button', { name: /^AUDIT: / }))
      expect(within(picker).getByRole('radio', { name: 'AUDIT' })).toHaveAttribute('aria-checked', 'true')
      expect(screen.getByRole('button', { name: /^AUDIT: / })).toHaveAttribute('aria-pressed', 'true')
    })
  })
})
