import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { LangProvider } from '../i18n/index.jsx'

/**
 * İZİN SİSTEMİNİN ARAYÜZÜ — 291 satır, iki yazma ucu, buraya kadar SIFIR test.
 * (AdminPanel.test.jsx bu bileşeni vi.mock ile değiştiriyor → "dolaylı kapsam" görüntüsü sahte.)
 *
 * Asıl risk hücre eşlemesi: bir off-by-one ya da yanlış sıralama, TIKLANAN hücreden BAŞKA bir
 * (rol, kaynak, aksiyon) üçlüsünü sunucuya gönderir; ekranda doğru görünür, yetki yanlış değişir.
 * Hassas (sensitive) yetkilerde onay diyaloğunun atlanması da sessiz bir yetki yükseltmesidir.
 */
const confirmMock = vi.fn(() => Promise.resolve(true))
const toastMock = { success: vi.fn(), error: vi.fn(), info: vi.fn() }

vi.mock('../api/client', () => ({
  formatDate: (s) => String(s ?? ''),
  api: { admin: {
    getPermissionMatrix: vi.fn(),
    updatePermissionGrant: vi.fn(),
    resetPermissionsToDefaults: vi.fn(),
  } },
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
  usePermissions: () => ({ refresh: vi.fn(), canView: () => true, canEdit: () => true, canExecute: () => true }),
  PermissionsProvider: ({ children }) => children,
}))

import { api } from '../api/client'
import PermissionMatrix from '../components/admin/PermissionMatrix.jsx'

// Tek kaynak, üç aksiyon; "execute" HASSAS. Sade tutuluyor ki hücre indeksleri elle sayılabilsin.
const CATALOG = [{
  group: 'certificates', resource_key: 'inventory.crud', labelKey: 'perm.res.inventory.crud',
  actions: ['view', 'edit', 'execute'], sensitive: ['execute'],
}]

const GRANTS = [
  { role: 'TEAM_ADMIN', resource_key: 'inventory.crud', action: 'view',    allowed: true },
  { role: 'TEAM_ADMIN', resource_key: 'inventory.crud', action: 'edit',    allowed: false },
  { role: 'USER',       resource_key: 'inventory.crud', action: 'view',    allowed: false },
]

const renderMatrix = () => render(<LangProvider><PermissionMatrix /></LangProvider>)

/** Satırdaki tıklanabilir hücreler: ADMIN kilitli olduğu için sıra TEAM_ADMIN, USER, AUDIT × view/edit/execute. */
const pills = () => [...document.querySelectorAll('.perm-toggle-cell button.perm-pill')]

describe('PermissionMatrix', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    confirmMock.mockResolvedValue(true)
    api.admin.getPermissionMatrix.mockResolvedValue({ success: true, catalog: CATALOG, grants: GRANTS })
    api.admin.updatePermissionGrant.mockImplementation((body) =>
      Promise.resolve({ success: true, data: { ...body } }))
    api.admin.resetPermissionsToDefaults.mockResolvedValue({ success: true })
  })

  it('ADMIN sütunu KİLİTLİ — tıklanabilir hücre üretmez (admin yetkisi arayüzden kısılamaz)', async () => {
    renderMatrix()
    await waitFor(() => expect(api.admin.getPermissionMatrix).toHaveBeenCalled())

    // Kapsam TABLO hücreleri: açıklama şeridinde de bir kilit rozeti var, sayıma girmemeli.
    expect(document.querySelectorAll('.perm-locked-cell .perm-locked-badge').length).toBe(3)   // ADMIN × 3 aksiyon
    // Kalan 3 rol × 3 aksiyon = 9 tıklanabilir hücre
    expect(pills()).toHaveLength(9)
  })

  it('hücre tıklaması TAM OLARAK kendi (rol, kaynak, aksiyon) üçlüsünü gönderir', async () => {
    renderMatrix()
    await waitFor(() => expect(api.admin.getPermissionMatrix).toHaveBeenCalled())

    // İlk hücre: TEAM_ADMIN / view (şu an allowed=true → geri alma)
    fireEvent.click(pills()[0])
    await waitFor(() => expect(api.admin.updatePermissionGrant).toHaveBeenCalled())
    expect(api.admin.updatePermissionGrant.mock.calls[0][0]).toEqual({
      role: 'TEAM_ADMIN', resource_key: 'inventory.crud', action: 'view', allowed: false,
    })

    // İkinci hücre: TEAM_ADMIN / edit (allowed=false → verme)
    fireEvent.click(pills()[1])
    await waitFor(() => expect(api.admin.updatePermissionGrant).toHaveBeenCalledTimes(2))
    expect(api.admin.updatePermissionGrant.mock.calls[1][0]).toEqual({
      role: 'TEAM_ADMIN', resource_key: 'inventory.crud', action: 'edit', allowed: true,
    })

    // Dördüncü hücre: sıradaki ROL (USER) / view — rol sınırı doğru
    fireEvent.click(pills()[3])
    await waitFor(() => expect(api.admin.updatePermissionGrant).toHaveBeenCalledTimes(3))
    expect(api.admin.updatePermissionGrant.mock.calls[2][0].role).toBe('USER')
    expect(api.admin.updatePermissionGrant.mock.calls[2][0].action).toBe('view')
  })

  it('mevcut yetki durumu aria-pressed ile doğru yansır', async () => {
    renderMatrix()
    await waitFor(() => expect(api.admin.getPermissionMatrix).toHaveBeenCalled())

    expect(pills()[0].getAttribute('aria-pressed')).toBe('true')    // TEAM_ADMIN/view verili
    expect(pills()[1].getAttribute('aria-pressed')).toBe('false')   // TEAM_ADMIN/edit yok
  })

  it('HASSAS yetki VERİLİRKEN onay ister; iptal edilirse istek GİTMEZ', async () => {
    confirmMock.mockResolvedValue(false)
    renderMatrix()
    await waitFor(() => expect(api.admin.getPermissionMatrix).toHaveBeenCalled())

    fireEvent.click(pills()[2])   // TEAM_ADMIN / execute → hassas, şu an kapalı

    await waitFor(() => expect(confirmMock).toHaveBeenCalled())
    expect(api.admin.updatePermissionGrant).not.toHaveBeenCalled()
  })

  it('hassas yetki GERİ ALINIRKEN onay istenmez (kısıtlama yönü serbest)', async () => {
    api.admin.getPermissionMatrix.mockResolvedValue({ success: true, catalog: CATALOG, grants: [
      ...GRANTS, { role: 'TEAM_ADMIN', resource_key: 'inventory.crud', action: 'execute', allowed: true },
    ] })
    renderMatrix()
    await waitFor(() => expect(api.admin.getPermissionMatrix).toHaveBeenCalled())

    fireEvent.click(pills()[2])   // execute açık → kapatma

    await waitFor(() => expect(api.admin.updatePermissionGrant).toHaveBeenCalled())
    expect(confirmMock).not.toHaveBeenCalled()
    expect(api.admin.updatePermissionGrant.mock.calls[0][0].allowed).toBe(false)
  })

  it('sunucu reddederse yerel durum DEĞİŞMEZ (ekran yalan söylemez)', async () => {
    api.admin.updatePermissionGrant.mockResolvedValue({ success: false, error: '403' })
    renderMatrix()
    await waitFor(() => expect(api.admin.getPermissionMatrix).toHaveBeenCalled())

    const before = pills()[1].getAttribute('aria-pressed')
    fireEvent.click(pills()[1])

    await waitFor(() => expect(toastMock.error).toHaveBeenCalled())
    expect(pills()[1].getAttribute('aria-pressed')).toBe(before)
  })

  it('varsayılanlara sıfırlama ONAY ister; iptalde istek gitmez', async () => {
    confirmMock.mockResolvedValue(false)
    renderMatrix()
    await waitFor(() => expect(api.admin.getPermissionMatrix).toHaveBeenCalled())

    const resetBtn = screen.getByRole('button', { name: /reset|varsayılan/i })
    fireEvent.click(resetBtn)

    await waitFor(() => expect(confirmMock).toHaveBeenCalled())
    expect(api.admin.resetPermissionsToDefaults).not.toHaveBeenCalled()
  })

  it('sıfırlama onaylanınca ucu çağırır ve matrisi YENİDEN yükler', async () => {
    renderMatrix()
    await waitFor(() => expect(api.admin.getPermissionMatrix).toHaveBeenCalledTimes(1))

    fireEvent.click(screen.getByRole('button', { name: /reset|varsayılan/i }))

    await waitFor(() => expect(api.admin.resetPermissionsToDefaults).toHaveBeenCalled())
    await waitFor(() => expect(api.admin.getPermissionMatrix).toHaveBeenCalledTimes(2))
  })
})
