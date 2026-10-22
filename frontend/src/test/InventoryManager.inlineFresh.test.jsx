import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { LangProvider } from '../i18n/index.jsx'

/**
 * Envanter satır içi düzenleme (2026-10-22, ADMIN-7): tier / aktif anahtarı eskiden LİSTEDEKİ (bayat olabilir) satırı
 * yama ile birlikte PUT'luyordu; uç tüm alanları yazdığı için başka birinin bu arada yaptığı değişiklik (açıklama,
 * sorumlular, pasife alma) sessizce geri alınıyordu. Artık kayıt önce TAZE okunur (`/admin/inventory/by-domain`) ve yama
 * onun üstüne uygulanır; kayıt okunamaz / değişmişse hiçbir şey yazılmaz.
 */
const toastMock = { success: vi.fn(), error: vi.fn(), info: vi.fn() }
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  formatDate: (s) => String(s ?? ''),
  formatDateOnly: (s) => String(s ?? '').slice(0, 10),
  api: withApiFallback({
    admin: {
      getInventory: vi.fn(), getTeams: vi.fn(), getAlerts: vi.fn(), getInventoryHygiene: vi.fn(),
      updateInventory: vi.fn(), getInventoryByDomain: vi.fn(),
    },
  }),
}))
vi.mock('@/hooks/use-mobile', () => ({ useIsMobile: () => false }))
vi.mock('../contexts/PermissionsProvider.jsx', () => ({
  usePermissions: () => ({ perms: {}, canView: () => true, canEdit: (r) => r === 'inventory.crud', canExecute: () => false, refresh: () => {} }),
  PermissionsProvider: ({ children }) => children,
}))
vi.mock('../components/ui/Dialog.jsx', () => ({ useDialog: () => ({ showConfirm: vi.fn(() => Promise.resolve(true)) }), DialogProvider: ({ children }) => children }))
vi.mock('../components/ui/Toast.jsx', () => ({ useToast: () => toastMock, ToastProvider: ({ children }) => children }))
vi.mock('../components/history/ChangeHistoryTab.jsx', () => ({ default: () => null }))
vi.mock('../components/history/CheckHistoryTab.jsx', () => ({ default: () => null }))

import { api } from '../api/client'
import InventoryManager from '../components/admin/InventoryManager.jsx'

/** Listedeki (bayat) satır: açıklama ve sorumlu ESKİ. */
const STALE = { id: 1, domain: 'a.example.com', port: 443, active: true, team_id: 5, team_name: 'Takım A', tier: 2,
  description: 'eski açıklama', svc_mgmt_contact: 'eski@example.com', cert_status: 'valid', cert_days_remaining: 90, can_manage: true }
/** Sunucudaki güncel kayıt: başka biri açıklamayı ve sorumluyu değiştirdi. */
const FRESH = { ...STALE, description: 'başkasının yeni açıklaması', svc_mgmt_contact: 'yeni@example.com', updated_at: '2026-10-22T09:00:00' }
delete FRESH.cert_status
delete FRESH.cert_days_remaining

const renderIm = () => render(<LangProvider><InventoryManager systemRole="TEAM_ADMIN" teams={[{ id: 5, name: 'Takım A' }]} /></LangProvider>)

describe('Envanter satır içi düzenleme — taze kayıt (ADMIN-7)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    window.history.replaceState(null, '', '/?tab=domains')
    api.admin.getInventory.mockResolvedValue({ success: true, data: [STALE], scope: 'mine', visible_to_all: true })
    api.admin.getTeams.mockResolvedValue({ success: true, data: [{ id: 5, name: 'Takım A' }] })
    api.admin.getAlerts.mockResolvedValue({ success: true, total: 0 })
    api.admin.getInventoryHygiene.mockResolvedValue({ success: true, data: { total: 0, scanned: 1, groups: [] } })
    api.admin.updateInventory.mockResolvedValue({ success: true })
  })

  it('aktif anahtarı: kayıt önce taze okunur; gövde TAZE alanlar + yama — bayat açıklama / sorumlu GİTMEZ', async () => {
    api.admin.getInventoryByDomain.mockResolvedValue({ success: true, data: FRESH })
    renderIm()
    await screen.findByText('a.example.com')
    fireEvent.click(screen.getByRole('switch', { name: 'a.example.com — Active' }))
    await waitFor(() => expect(api.admin.updateInventory).toHaveBeenCalledTimes(1))
    expect(api.admin.getInventoryByDomain).toHaveBeenCalledWith('a.example.com')
    const [id, body] = api.admin.updateInventory.mock.calls[0]
    expect(id).toBe(1)
    expect(body.active).toBe(false)
    expect(body.description).toBe('başkasının yeni açıklaması')
    expect(body.svc_mgmt_contact).toBe('yeni@example.com')
    expect(Object.keys(body).some((k) => k.startsWith('cert_'))).toBe(false)
  })

  it('kayıt okunamaz ya da başka bir kayda dönüşmüşse (yeniden adlandırma) HİÇBİR ŞEY yazılmaz; liste yenilenir', async () => {
    api.admin.getInventoryByDomain.mockResolvedValue({ success: true, data: { ...FRESH, id: 99 } })
    renderIm()
    await screen.findByText('a.example.com')
    fireEvent.click(screen.getByRole('switch', { name: 'a.example.com — Active' }))
    await waitFor(() => expect(toastMock.error).toHaveBeenCalled())
    expect(api.admin.updateInventory).not.toHaveBeenCalled()
    await waitFor(() => expect(api.admin.getInventory.mock.calls.length).toBeGreaterThanOrEqual(2))
  })

  it('taze okuma ağ hatası → yazılmaz, hata söylenir', async () => {
    api.admin.getInventoryByDomain.mockRejectedValue(new TypeError('Failed to fetch'))
    renderIm()
    await screen.findByText('a.example.com')
    fireEvent.click(screen.getByRole('switch', { name: 'a.example.com — Active' }))
    await waitFor(() => expect(toastMock.error).toHaveBeenCalled())
    expect(api.admin.updateInventory).not.toHaveBeenCalled()
  })
})
