import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'

/**
 * Takım Yönetimi (2026-09-20, kullanıcı bildirimi): standart araç çubuğu (arama: ad / e-posta / lider / müdür),
 * süzgeçler (durum, müdür, açık alarm, lider yok, haftalık), toplu işlemler (aktif/pasif, haftalık, müdür ata),
 * PaginationBar.
 */
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
const { confirmMock } = vi.hoisted(() => ({ confirmMock: vi.fn() }))
vi.mock('../api/client', () => ({
  api: withApiFallback({ admin: { getTeams: vi.fn(), getUsers: vi.fn(), getTeamUsers: vi.fn(), teamStats: vi.fn(), bulkTeams: vi.fn(), updateTeam: vi.fn(), createTeam: vi.fn(), updateTeamWeeklyNotifications: vi.fn() } }),
}))
vi.mock('../components/ui/Dialog.jsx', () => ({ useDialog: () => ({ showConfirm: confirmMock }), DialogProvider: ({ children }) => children }))
vi.mock('../components/admin/AdminChangeHistory.jsx', () => ({ default: () => null }))
import { api } from '../api/client'
import TeamManager from '../components/admin/TeamManager.jsx'

const USERS = [
  { id: 7, username: 'lead', display_name: 'Lider Bir', org_role: 'TECH', team_id: 1, active: true },
  { id: 8, username: 'mgr', display_name: 'Mudur Elle', org_role: 'MANAGER', active: true },
  { id: 9, username: 'mgr2', display_name: 'Mudur Iki', org_role: 'MANAGER', active: true },
]
const TEAMS = [
  { id: 1, name: 'Payments', active: true, leader_id: 7, email: 'p@example.com', manager_id: 8, weekly_reminder_enabled: true },
  { id: 2, name: 'Ledger', active: true, leader_id: null, email: 'l@example.com', manager_id: 9 },
  { id: 3, name: 'Archive', active: false, leader_id: 7, email: 'a@example.com', manager_id: null },
]
const STATS = { 1: { members: 2, open_alerts: 3 }, 2: { members: 1, open_alerts: 0 }, 3: { members: 0, open_alerts: 0 } }

describe('TeamManager — süzgeç + toplu işlem (2026-09-20)', () => {
  beforeEach(() => {
    vi.clearAllMocks(); window.history.replaceState({}, '', '/?tab=admin&g_tab=teams')
    try { localStorage.clear() } catch { /* yok */ }
    api.admin.getUsers.mockResolvedValue({ success: true, data: USERS })
    api.admin.getTeams.mockResolvedValue({ success: true, data: TEAMS })
    api.admin.teamStats.mockResolvedValue({ success: true, data: STATS })
    api.admin.getTeamUsers.mockResolvedValue({ success: true, data: [] })
    api.admin.bulkTeams.mockResolvedValue({ success: true, data: { ok: 2, failed: 0, results: [] } })
    confirmMock.mockResolvedValue(true)
  })
  const rows = () => [...document.querySelectorAll('.admin-table tbody tr')].filter((r) => r.querySelector('td.um-col-check'))

  it('arama lider / müdür adıyla da bulur; süzgeçler: açık alarm, müdür, durum, lider yok', async () => {
    render(<TeamManager systemRole="ADMIN" onTeamsChange={() => {}} />)
    await screen.findByText('Payments')
    await waitFor(() => expect(rows()).toHaveLength(3))
    const search = screen.getByLabelText(/Takım ara|Search teams/)
    fireEvent.change(search, { target: { value: 'mudur iki' } })
    await waitFor(() => expect(rows()).toHaveLength(1))
    expect(rows()[0].textContent).toContain('Ledger')
    fireEvent.change(search, { target: { value: 'lider bir' } })
    await waitFor(() => expect(rows()).toHaveLength(2))
    fireEvent.change(search, { target: { value: '' } })
    fireEvent.click(screen.getByRole('button', { name: /Süzgeçler|Filters/ }))
    // açık alarmı olanlar
    fireEvent.mouseDown(screen.getByLabelText(/Açık alarm|Open alerts/))
    fireEvent.mouseDown(await screen.findByText(/Açık alarmı olanlar|With open alerts/))
    await waitFor(() => expect(rows()).toHaveLength(1))
    expect(rows()[0].textContent).toContain('Payments')
    await waitFor(() => expect(new URLSearchParams(window.location.search).get('g_alerts')).toBe('open'))
    fireEvent.click(screen.getByRole('button', { name: /Süzgeçleri temizle|Clear filters/ }))
    await waitFor(() => expect(rows()).toHaveLength(3))
    // müdür: Mudur Elle
    fireEvent.mouseDown(screen.getByLabelText(/Takım Müdürü|Team Manager/))
    fireEvent.mouseDown((await screen.findAllByText('Mudur Elle')).find((el) => el.closest('.ss-option')))
    await waitFor(() => expect(rows()).toHaveLength(1))
    expect(rows()[0].textContent).toContain('Payments')
    fireEvent.click(screen.getByRole('button', { name: /Süzgeçleri temizle|Clear filters/ }))
    // durum: pasif
    fireEvent.mouseDown(screen.getByLabelText(/^Aktif$|^Active$/))
    fireEvent.mouseDown((await screen.findAllByText(/^Pasif$|^Inactive$/)).find((el) => el.closest('.ss-option')))
    await waitFor(() => expect(rows()).toHaveLength(1))
    expect(rows()[0].textContent).toContain('Archive')
    fireEvent.click(screen.getByRole('button', { name: /Süzgeçleri temizle|Clear filters/ }))
    // lider yok
    fireEvent.mouseDown(screen.getByLabelText(/Takım Lideri|Team Leader/))
    fireEvent.mouseDown((await screen.findAllByText(/Lider atanmadı|No leader/)).find((el) => el.closest('.ss-option')))
    await waitFor(() => expect(rows()).toHaveLength(1))
    expect(rows()[0].textContent).toContain('Ledger')
  })

  it('toplu işlem: iki takım seç → Pasifleştir onayla → bulkTeams; haftalık ve müdür atama seçenekleri; sonuç toast + liste tazelenir', async () => {
    render(<TeamManager systemRole="ADMIN" onTeamsChange={() => {}} />)
    await screen.findByText('Payments')
    await waitFor(() => expect(rows()).toHaveLength(3))
    fireEvent.click(screen.getByLabelText(/Payments takımını seç|Select team Payments/))
    fireEvent.click(screen.getByLabelText(/Ledger takımını seç|Select team Ledger/))
    const bar = screen.getByTestId('tm-bulk-bar')
    expect(bar.textContent).toMatch(/2 takım seçili|2 teams selected/)
    fireEvent.click(within(bar).getByRole('button', { name: /Pasifleştir|Deactivate/ }))
    await waitFor(() => expect(api.admin.bulkTeams).toHaveBeenCalledWith({ action: 'deactivate', ids: [1, 2] }))
    expect(confirmMock).toHaveBeenCalled()
    await waitFor(() => expect(api.admin.getTeams).toHaveBeenCalledTimes(2))
    await waitFor(() => expect(screen.queryByTestId('tm-bulk-bar')).toBeNull())
    // haftalık: seçim → cuma hatırlatması aç
    fireEvent.click(screen.getByLabelText(/Sayfadaki takımları seç|Select the teams on this page/))
    fireEvent.mouseDown(within(screen.getByTestId('tm-bulk-bar')).getByLabelText(/Haftalık e-posta|Weekly emails/))
    fireEvent.mouseDown(await screen.findByText(/Cuma hatırlatması: aç|Friday reminder: on/))
    await waitFor(() => expect(api.admin.bulkTeams).toHaveBeenLastCalledWith({ action: 'weekly_reminder_on', ids: [1, 2, 3] }))
    // müdür ata
    await waitFor(() => expect(screen.queryByTestId('tm-bulk-bar')).toBeNull())
    fireEvent.click(screen.getByLabelText(/Payments takımını seç|Select team Payments/))
    fireEvent.mouseDown(within(screen.getByTestId('tm-bulk-bar')).getByLabelText(/Müdür ata|Assign manager/))
    fireEvent.mouseDown((await screen.findAllByText('Mudur Iki')).find((el) => el.closest('.ss-option')))
    await waitFor(() => expect(api.admin.bulkTeams).toHaveBeenLastCalledWith({ action: 'set_manager', ids: [1], manager_id: 9 }))
    // onay reddedilirse istek gitmez
    confirmMock.mockResolvedValueOnce(false)
    await waitFor(() => expect(screen.queryByTestId('tm-bulk-bar')).toBeNull())
    fireEvent.click(screen.getByLabelText(/Payments takımını seç|Select team Payments/))
    const calls = api.admin.bulkTeams.mock.calls.length
    fireEvent.click(within(screen.getByTestId('tm-bulk-bar')).getByRole('button', { name: /Aktifleştir|Activate/ }))
    await waitFor(() => expect(confirmMock).toHaveBeenCalledTimes(4))
    expect(api.admin.bulkTeams.mock.calls.length).toBe(calls)
  })

  it('TEAM_ADMIN: yalnız kendi takımının kutusu çizilir; standart PaginationBar var, eski sayfa seçici yok', async () => {
    render(<TeamManager systemRole="TEAM_ADMIN" ownTeamId={2} myTeamIds={[2]} onTeamsChange={() => {}} />)
    await screen.findByText('Payments')
    await waitFor(() => expect(rows()).toHaveLength(3))
    expect(screen.queryByLabelText(/Payments takımını seç|Select team Payments/)).toBeNull()
    expect(screen.getByLabelText(/Ledger takımını seç|Select team Ledger/)).toBeInTheDocument()
    expect(document.querySelector('.audit-pagination')).toBeNull()
    await waitFor(() => expect(document.querySelector('.pgn-bar')).toBeTruthy())
  })
})
