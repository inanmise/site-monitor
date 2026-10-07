import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within, act } from '@testing-library/react'
import { LangProvider } from '../i18n/index.jsx'

/**
 * 2026-09-27 regresyon taraması (release-fixes.md FRONTEND A #3): Envanter `load(scope)` sıra korumasızdı —
 * "Tüm takımlar" → "Takımlarım" hızlı geçişinde GEÇ dönen "all" yanıtı "mine" listesini eziyordu (anahtar
 * "Takımlarım" derken başka takımın kayıtları listede). Denetimli promise'ler: eski istek YENİSİNDEN SONRA çözülür.
 */
const toastMock = { success: vi.fn(), error: vi.fn(), info: vi.fn() }
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => String(s ?? ''),
  formatDateOnly: (s) => String(s ?? '').slice(0, 10),
  api: withApiFallback({ admin: { getInventory: vi.fn(), getTeams: vi.fn(), getAlerts: vi.fn() } }),
}))
vi.mock('../contexts/PermissionsProvider.jsx', () => ({
  usePermissions: () => ({ perms: {}, canView: () => true, canEdit: (r) => r === 'inventory.crud', canExecute: () => false, refresh: () => {} }),
  PermissionsProvider: ({ children }) => children,
}))
vi.mock('../components/ui/Dialog.jsx', () => ({ useDialog: () => ({ showConfirm: vi.fn(() => Promise.resolve(true)) }), DialogProvider: ({ children }) => children }))
vi.mock('../components/ui/Toast.jsx', () => ({ useToast: () => toastMock, ToastProvider: ({ children }) => children }))
vi.mock('../utils/exportInventory', () => ({ exportInventoryCsv: vi.fn(() => 0), exportInventoryPdf: vi.fn(async () => 0) }))

import { api } from '../api/client'
import InventoryManager from '../components/admin/InventoryManager.jsx'

const OWN = [{ id: 1, domain: 'own-a.example.com', port: 443, active: true, team_id: 5, team_name: 'Takım A', tier: 1, cert_status: 'valid', can_manage: true }]
const FOREIGN = [{ id: 9, domain: 'foreign.example.com', port: 8443, active: true, team_id: 9, team_name: 'Takım B', tier: 3, cert_status: 'error', can_manage: false }]
const deferred = () => { let resolve; const p = new Promise((r) => { resolve = r }); return { p, resolve } }
const flush = () => act(() => new Promise((r) => setTimeout(r, 0)))
const scopeBtn = (re) => within(screen.getByRole('group', { name: /görünürlük kapsamı|visible teams/i })).getByRole('button', { name: re })

describe('Envanter — kapsam geçişi fetch yarışı', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    window.history.replaceState(null, '', '/')
    api.admin.getTeams.mockResolvedValue({ success: true, data: [{ id: 5, name: 'Takım A' }, { id: 9, name: 'Takım B' }] })
    api.admin.getAlerts.mockResolvedValue({ success: true, total: 0 })
  })

  it('"Tüm takımlar" yanıtı "Takımlarım"a dönüldükten SONRA gelirse listeyi ezmez', async () => {
    const all = deferred(), mine2 = deferred()
    let mineCalls = 0
    api.admin.getInventory.mockImplementation((scope) => {
      if (scope === 'all') return all.p
      mineCalls += 1
      return mineCalls === 1 ? Promise.resolve({ success: true, data: OWN, scope: 'mine', visible_to_all: true }) : mine2.p
    })
    render(<LangProvider><InventoryManager systemRole="TEAM_ADMIN" teams={[{ id: 5, name: 'Takım A' }]} /></LangProvider>)
    await screen.findByText('own-a.example.com')

    fireEvent.click(scopeBtn(/tüm takımlar|all teams/i))
    await waitFor(() => expect(api.admin.getInventory).toHaveBeenLastCalledWith('all'))
    fireEvent.click(scopeBtn(/takımlarım|my teams/i))
    await waitFor(() => expect(api.admin.getInventory).toHaveBeenLastCalledWith('mine'))

    // Yeni ("mine") yanıt önce, eski ("all") yanıt SONRA döner.
    await act(async () => { mine2.resolve({ success: true, data: OWN, scope: 'mine', visible_to_all: true }) })
    await flush()
    await act(async () => { all.resolve({ success: true, data: [...OWN, ...FOREIGN], scope: 'all', visible_to_all: true }) })
    await flush()

    expect(screen.getByText('own-a.example.com')).toBeInTheDocument()
    expect(screen.queryByText('foreign.example.com')).toBeNull()
    expect(scopeBtn(/takımlarım|my teams/i)).toHaveAttribute('aria-pressed', 'true')
  })
})
