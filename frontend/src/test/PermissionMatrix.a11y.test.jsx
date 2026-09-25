import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, waitFor } from '@testing-library/react'
import { LangProvider } from '../i18n/index.jsx'

/**
 * Yetki matrisi — ADMIN hücrelerinin ekran okuyucu metni (2026-09-25, R16).
 *
 * <p>ADMIN sütunu kilitli: hücrede yalnız `title` ve dekoratif (aria-hidden) kilit ikonu vardı.
 * `title` ekran okuyucuda güvenilir değil → tablo gezinmesinde hücre BOŞ okunuyordu; kullanıcı
 * ADMIN'in o yetkiye sahip olmadığını sanabilirdi. Hücre artık görsel olarak gizli bir metin taşır.
 * (Ayrı dosya: ana PermissionMatrix.test.jsx aynı anda başka bir düzeltmenin testlerini alıyor.)
 */
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
  useDialog: () => ({ showConfirm: vi.fn(() => Promise.resolve(true)) }),
  DialogProvider: ({ children }) => children,
}))
vi.mock('../components/ui/Toast.jsx', () => ({
  useToast: () => ({ success: vi.fn(), error: vi.fn(), info: vi.fn() }),
  ToastProvider: ({ children }) => children,
}))
vi.mock('../contexts/PermissionsProvider.jsx', () => ({
  usePermissions: () => ({ refresh: vi.fn(), canView: () => true, canEdit: () => true, canExecute: () => true }),
  PermissionsProvider: ({ children }) => children,
}))

import { api } from '../api/client'
import PermissionMatrix from '../components/admin/PermissionMatrix.jsx'

const CATALOG = [{
  group: 'certificates', resource_key: 'inventory.crud', labelKey: 'perm.res.inventory.crud',
  actions: ['view', 'edit', 'execute'], sensitive: ['execute'],
}]

describe('PermissionMatrix — ADMIN hücreleri ekran okuyucuda boş değil', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.admin.getPermissionMatrix.mockResolvedValue({ success: true, catalog: CATALOG, grants: [], can_edit: true })
  })

  it('her kilitli ADMIN hücresi erişilebilir metin taşır; kilit ikonu dekoratif', async () => {
    render(<LangProvider><PermissionMatrix /></LangProvider>)
    await waitFor(() => expect(document.querySelectorAll('td.perm-locked-cell')).toHaveLength(3))
    for (const cell of document.querySelectorAll('td.perm-locked-cell')) {
      // Görünür içerik yalnız ikon; metin ağaçta (sr-only) — hücrenin okunan içeriği boş değil.
      expect(cell.textContent.trim()).toBe('ADMIN always has full access.')
      expect(cell.querySelector('svg')).toHaveAttribute('aria-hidden', 'true')
    }
  })
})
