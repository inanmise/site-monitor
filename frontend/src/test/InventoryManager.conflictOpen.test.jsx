import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { LangProvider } from '../i18n/index.jsx'

/**
 * Mükerrer alan adı bandından aktarım / geri yükleme (2026-09-28): form `onSaved(res, domain, { open: true, record })`
 * ile kapanır → Envanter listeyi TAZELER ve taşınan kaydın çekmecesini açar (tazelenmiş satır tercih edilir).
 * Düz kayıtta (opts yok) çekmece açılmaz — eski davranış.
 */
const toastMock = { success: vi.fn(), error: vi.fn(), info: vi.fn() }
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => String(s ?? ''),
  formatDateOnly: (s) => String(s ?? '').slice(0, 10),
  api: withApiFallback({ admin: { getInventory: vi.fn(), getTeams: vi.fn(), getAlerts: vi.fn() } }),
}))
vi.mock('../contexts/PermissionsProvider.jsx', () => ({
  usePermissions: () => ({ perms: { 'inventory.crud': { edit: true } }, canView: () => true, canEdit: (r) => r === 'inventory.crud', canExecute: () => false, refresh: () => {} }),
  PermissionsProvider: ({ children }) => children,
}))
vi.mock('../components/ui/Dialog.jsx', () => ({ useDialog: () => ({ showConfirm: vi.fn(() => Promise.resolve(true)) }), DialogProvider: ({ children }) => children }))
vi.mock('../components/ui/Toast.jsx', () => ({ useToast: () => toastMock, ToastProvider: ({ children }) => children }))
vi.mock('../utils/exportInventory', () => ({ exportInventoryCsv: vi.fn(() => 0), exportInventoryPdf: vi.fn(async () => 0) }))
// Form: yalnız onSaved sözleşmesi — mükerrer bandının "aktar" sonucu ve düz kayıt
vi.mock('../components/inventory/InventoryFormModal.jsx', () => ({
  default: ({ onSaved }) => (
    <div>
      <button type="button" onClick={() => onSaved({ success: true, data: { id: 3 } }, 'moved.example.com',
        { open: true, record: { id: 3, domain: 'moved.example.com', team_id: 5 } })}>stub-transferred</button>
      <button type="button" onClick={() => onSaved({ success: true }, 'plain.example.com')}>stub-saved</button>
    </div>
  ),
}))

import { api } from '../api/client'
import InventoryManager from '../components/admin/InventoryManager.jsx'

const OWN = { id: 1, domain: 'own-a.example.com', port: 443, active: true, team_id: 5, team_name: 'Takım A', can_manage: true }
const MOVED = { id: 3, domain: 'moved.example.com', port: 443, active: true, team_id: 5, team_name: 'Takım A', can_manage: true, tier: 2 }

describe('Envanter — mükerrer bandından aktarım sonrası kaydı aç', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    window.history.replaceState(null, '', '/')
    api.admin.getTeams.mockResolvedValue({ success: true, data: [{ id: 5, name: 'Takım A' }] })
    api.admin.getAlerts.mockResolvedValue({ success: true, total: 0 })
    api.admin.getInventory.mockResolvedValueOnce({ success: true, data: [OWN], scope: 'mine' })
      .mockResolvedValue({ success: true, data: [OWN, MOVED], scope: 'mine' })
  })

  it('opts.open → form kapanır, liste tazelenir, taşınan kaydın çekmecesi (tazelenmiş satır) açılır', async () => {
    render(<LangProvider><InventoryManager systemRole="ADMIN" teams={[{ id: 5, name: 'Takım A' }]} openAddSignal onAddConsumed={() => {}} /></LangProvider>)
    await screen.findByText('own-a.example.com')
    fireEvent.click(await screen.findByRole('button', { name: 'stub-transferred' }))
    const drawer = await screen.findByRole('dialog', { name: 'moved.example.com' })
    expect(drawer).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'stub-transferred' })).toBeNull()   // form kapandı
    expect(api.admin.getInventory).toHaveBeenCalledTimes(2)
  })

  it('düz kayıt (opts yok) → çekmece AÇILMAZ; yalnız tazeleme', async () => {
    render(<LangProvider><InventoryManager systemRole="ADMIN" teams={[{ id: 5, name: 'Takım A' }]} openAddSignal onAddConsumed={() => {}} /></LangProvider>)
    await screen.findByText('own-a.example.com')
    fireEvent.click(await screen.findByRole('button', { name: 'stub-saved' }))
    await waitFor(() => expect(api.admin.getInventory).toHaveBeenCalledTimes(2))
    expect(screen.queryByRole('dialog', { name: 'moved.example.com' })).toBeNull()
    expect(screen.queryByRole('dialog', { name: 'plain.example.com' })).toBeNull()
  })
})
