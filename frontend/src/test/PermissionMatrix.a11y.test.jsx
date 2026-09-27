import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent, within } from '@testing-library/react'
import { LangProvider } from '../i18n/index.jsx'

/**
 * Yetki matrisi — erişilebilirlik sözleşmesi.
 *
 * <p>R16 (2026-09-25): ADMIN sütunu kilitli; hücrede yalnız dekoratif (aria-hidden) kilit ikonu vardı → tablo
 * gezinmesinde hücre BOŞ okunuyordu, kullanıcı ADMIN'in o yetkiye sahip olmadığını sanabilirdi. Hücre görsel olarak
 * gizli bir metin taşır.
 * <p>2026-09-27 yeniden tasarım: her anahtarın adı "<rol> — <kaynak> — <tür>" (satırı VE sütunu ayırt eder, benzersiz);
 * değişen hücre ekran okuyucuya da söylenir; matriste tek sekme durağı; rol başlığı açıklaması dokunmatikte de açılan
 * adlandırılmış düğme; gözden geçirme paneli başlıklı + açıklamalı diyalog; tablo adlandırılmış.
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

const CATALOG = [
  { group: 'certificates', resource_key: 'inventory.crud', actions: ['view', 'edit', 'execute'], sensitive: ['execute'] },
  { group: 'certificates', resource_key: 'notes.read', actions: ['view'], sensitive: [] },
]

const renderOpen = () => render(<LangProvider><PermissionMatrix initialOpenGroup="certificates" /></LangProvider>)
const switches = () => [...document.querySelectorAll('[data-cell="toggle"] [role="switch"]')]

describe('PermissionMatrix — erişilebilirlik', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.admin.getPermissionMatrix.mockResolvedValue({ success: true, catalog: CATALOG, grants: [], can_edit: true })
  })

  it('her kilitli ADMIN hücresi erişilebilir metin taşır; kilit ikonu dekoratif', async () => {
    renderOpen()
    await waitFor(() => expect(document.querySelectorAll('td[data-cell="locked"]')).toHaveLength(4))
    for (const cell of document.querySelectorAll('td[data-cell="locked"]')) {
      // Görünür içerik yalnız ikon; metin ağaçta (sr-only) — hücrenin okunan içeriği boş değil.
      expect(cell.textContent.trim()).toBe('ADMIN always has full access.')
      expect(cell.querySelector('svg')).toHaveAttribute('aria-hidden', 'true')
    }
  })

  it('geçersiz (—) hücreler de ekran okuyucuda boş değil', async () => {
    renderOpen()
    await waitFor(() => expect(document.querySelectorAll('td[data-cell="na"]').length).toBeGreaterThan(0))
    for (const cell of document.querySelectorAll('td[data-cell="na"]')) {
      expect(cell).toHaveTextContent('This action does not apply to this feature.')
    }
  })

  it('her anahtarın adı "<rol> — <kaynak> — <tür>" biçiminde ve BENZERSİZ', async () => {
    renderOpen()
    await waitFor(() => expect(switches()).toHaveLength(3 * 3 + 3 * 1))
    const names = switches().map((s) => s.getAttribute('aria-label'))
    expect(new Set(names).size).toBe(names.length)
    for (const n of names) expect(n).toMatch(/^(TEAM_ADMIN|USER|AUDIT) — (inventory\.crud|notes\.read) — (View|Edit|Execute)$/)
  })

  it('matris adlandırılmış bir tablo; tek sekme durağı (gezici tabindex)', async () => {
    renderOpen()
    await waitFor(() => expect(switches().length).toBeGreaterThan(0))
    expect(screen.getByRole('table', { name: 'Permission Management' })).toBeInTheDocument()
    expect(switches().filter((s) => s.tabIndex === 0)).toHaveLength(1)
  })

  it('değişen hücre ekran okuyucuya da söylenir (yalnız renkli nokta değil)', async () => {
    renderOpen()
    await waitFor(() => expect(switches().length).toBeGreaterThan(0))
    const cell = switches()[0].closest('td')
    expect(cell).not.toHaveTextContent('unsaved change')
    fireEvent.click(switches()[0])
    expect(cell).toHaveTextContent('unsaved change')
    expect(cell.querySelector('[data-slot="perm-change-dot"]')).toHaveAttribute('aria-hidden', 'true')
  })

  it('rol başlığı açıklaması adlandırılmış düğme (dokunmatikte de açılır, yalnız-hover değil)', async () => {
    renderOpen()
    await waitFor(() => expect(switches().length).toBeGreaterThan(0))
    const info = screen.getByRole('button', { name: 'About the AUDIT role' })
    fireEvent.click(info)
    expect(await screen.findByRole('tooltip')).toHaveTextContent('System-wide read-only (auditor).')
  })

  it('gözden geçirme paneli başlıklı ve açıklamalı bir diyalog; kapatma düğmesi adlandırılmış', async () => {
    renderOpen()
    await waitFor(() => expect(switches().length).toBeGreaterThan(0))
    fireEvent.click(switches()[0])
    fireEvent.click(within(document.querySelector('[data-slot="perm-changes-bar"]')).getByRole('button', { name: 'Review' }))
    const dlg = await screen.findByRole('dialog', { name: 'Review changes' })
    expect(dlg).toHaveAccessibleDescription(/Nothing changes until you save/)
    expect(within(dlg).getByRole('button', { name: 'Close' })).toBeInTheDocument()
    expect(within(dlg).getByRole('list', { name: 'Review changes' })).toBeInTheDocument()
  })

  it('bekleyen değişiklik çubuğu adlandırılmış bir bölge', async () => {
    renderOpen()
    await waitFor(() => expect(switches().length).toBeGreaterThan(0))
    expect(screen.queryByRole('region', { name: 'Unsaved changes' })).toBeNull()
    fireEvent.click(switches()[0])
    expect(screen.getByRole('region', { name: 'Unsaved changes' })).toHaveTextContent('1 unsaved change')
  })
})
