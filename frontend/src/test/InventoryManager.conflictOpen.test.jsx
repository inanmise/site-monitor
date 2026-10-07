import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, act } from '@testing-library/react'
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

/*
 * Ek 3/4 (2026-09-28): Envanter sekmesi ZATEN açıkken gelen derin bağlantı — App aynı sekmede paramları adrese yazıp
 * `sm:tab-params` yayar (sayfa yeniden bağlanmaz). `domain` yalnız mount'ta okunuyordu: mükerrer alan adı bandının
 * penceresindeki "Envanterde aç" yalnız pencereyi kapatıyor, kayıt açılmıyordu. Başka takımın salt okunur kaydı yalnız
 * "tüm takımlar"da listelenir → `i_scope=all` uygulanır ve karar o kapsamın listesi gelince verilir (eski liste kaydı
 * bulamayıp bağlantıyı DÜŞÜRMEZ).
 */
describe('Envanter — sekme açıkken derin bağlantı (sm:tab-params)', () => {
  const FOREIGN = { id: 8, domain: 'foreign.example.com', port: 443, active: true, team_id: 6, team_name: 'Takım B', can_manage: false }
  const tabParams = (detail) => act(() => { window.dispatchEvent(new CustomEvent('sm:tab-params', { detail })) })
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    window.history.replaceState(null, '', '/?tab=domains')
    api.admin.getTeams.mockResolvedValue({ success: true, data: [{ id: 5, name: 'Takım A' }, { id: 6, name: 'Takım B' }] })
    api.admin.getAlerts.mockResolvedValue({ success: true, total: 0 })
    api.admin.getInventory.mockImplementation(async (scope) => (scope === 'all'
      ? { success: true, data: [OWN, FOREIGN], scope: 'all', visible_to_all: true }
      : { success: true, data: [OWN], scope: 'mine', visible_to_all: true }))
  })

  it('kendi kaydı: `domain` gelince listedeki kaydın çekmecesi açılır (yeniden yükleme yok)', async () => {
    render(<LangProvider><InventoryManager systemRole="ADMIN" teams={[{ id: 5, name: 'Takım A' }]} /></LangProvider>)
    await screen.findByText('own-a.example.com')
    const calls = api.admin.getInventory.mock.calls.length
    tabParams({ domain: 'own-a.example.com' })
    expect(await screen.findByRole('dialog', { name: 'own-a.example.com' })).toBeInTheDocument()
    expect(api.admin.getInventory.mock.calls.length).toBe(calls)
  })

  it('başka takımın kaydı + i_scope=all: kapsam "tüm takımlar"a geçer, o liste gelince çekmece açılır (eski liste bağlantıyı düşürmez)', async () => {
    render(<LangProvider><InventoryManager systemRole="ADMIN" teams={[{ id: 5, name: 'Takım A' }]} /></LangProvider>)
    await screen.findByText('own-a.example.com')
    tabParams({ domain: 'foreign.example.com', i_scope: 'all' })
    await waitFor(() => expect(api.admin.getInventory).toHaveBeenLastCalledWith('all'))
    expect(await screen.findByRole('dialog', { name: 'foreign.example.com' })).toBeInTheDocument()
  })

  it('domain taşımayan olay (başka bir sayfa paramı) hiçbir şey açmaz', async () => {
    render(<LangProvider><InventoryManager systemRole="ADMIN" teams={[{ id: 5, name: 'Takım A' }]} /></LangProvider>)
    await screen.findByText('own-a.example.com')
    tabParams({ sec: 'releases' })
    await new Promise((r) => setTimeout(r, 30))
    expect(screen.queryByRole('dialog')).toBeNull()
  })
})
