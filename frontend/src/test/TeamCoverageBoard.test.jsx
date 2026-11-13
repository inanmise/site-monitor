import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'

/**
 * Takımlar → kapsam panosu + toplu düzeltme (2026-11-13, yönetim zenginleştirmesi (h)). Pano her boyutu ayrı okur;
 * okunamayan boyut "bilinmiyor"dur (eksik değil). Toplu düzeltme pencereleri: varsayılan grup (takım başına kendi grubu)
 * ve eskalasyon kişisi ekleme — önizleme + tek istek + satır satır sonuç.
 */
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => String(s ?? ''),
  formatDateSec: (s) => String(s ?? ''),
  api: withApiFallback({
    admin: { getTeams: vi.fn(), getUsers: vi.fn(), getTeamUsers: vi.fn(), teamStats: vi.fn(), bulkTeams: vi.fn(), getContacts: vi.fn(), bulkCreateContacts: vi.fn() },
    notificationGroups: { list: vi.fn(), bulk: vi.fn() },
    noc: { coverage: vi.fn() },
    oncall: { current: vi.fn() },
  }),
}))
vi.mock('../components/admin/AdminChangeHistory.jsx', () => ({ default: () => null }))
import { api } from '../api/client'
import TeamManager from '../components/admin/TeamManager.jsx'

const USERS = [
  { id: 7, username: 'lead', display_name: 'Lider Bir', team_id: 1, active: true, email: 'lead@example.com' },
  { id: 8, username: 'mgr', display_name: 'Mudur Elle', active: true, email: 'mgr@example.com' },
]
const TEAMS = [
  { id: 1, name: 'Ödeme Sistemleri', active: true, leader_id: 7, email: 'odeme@example.com', manager_id: 8 },
  { id: 2, name: 'Arşiv', active: true, leader_id: null, email: '', manager_id: null },
  { id: 3, name: 'Çağrı Merkezi', active: true, leader_id: 7, email: 'cagri@example.com', manager_id: 8 },
]
const STATS = { 1: { members: 2, monitors: 3, domains: 1 }, 2: { members: 1, monitors: 2 }, 3: { members: 1, monitors: 1 } }
const CONTACTS = [{ id: 1, team_id: 1, user_id: 8, min_alert_level: 'WARNING', active: true }]
const GROUPS = [
  { id: 10, team_id: 1, name: 'Ödeme Nöbet', emails: ['n@example.com'], is_default: true, active: true, can_write: true },
  { id: 30, team_id: 3, name: 'Çağrı Tek', emails: ['c@example.com'], is_default: false, active: true, can_write: true },
  { id: 20, team_id: 2, name: 'Arşiv A', emails: ['a@example.com'], is_default: false, active: true, can_write: true },
  { id: 21, team_id: 2, name: 'Arşiv B', emails: ['b@example.com'], is_default: false, active: true, can_write: true },
]

const boardRow = (id) => document.querySelector(`[data-slot="tcb-row"][data-team-id="${id}"]`)
const listRow = (id) => document.querySelector(`[data-slot="table-body"] > tr[data-team-id="${id}"]`)

beforeEach(() => {
  vi.clearAllMocks()
  window.history.replaceState({}, '', '/?tab=admin&g_tab=teams')
  try { localStorage.clear() } catch { /* yok */ }
  api.admin.getUsers.mockResolvedValue({ success: true, data: USERS })
  api.admin.getTeams.mockResolvedValue({ success: true, data: TEAMS })
  api.admin.teamStats.mockResolvedValue({ success: true, data: STATS })
  api.admin.getContacts.mockResolvedValue({ success: true, data: CONTACTS })
  api.notificationGroups.list.mockResolvedValue({ success: true, data: { groups: GROUPS } })
  api.noc.coverage.mockResolvedValue({ success: true, data: { summary: { by_team: { 1: { total: 4, covered: 4, paused: 0 }, 3: { total: 2, covered: 1, paused: 0 } } } } })
  api.oncall.current.mockResolvedValue({ success: true, data: [{ team_id: 1, name: 'Nöbetçi Kişi', until: '2026-11-14T06:00:00', active: true }] })
})

describe('Takımlar — kapsam panosu', () => {
  it('pano: ulaşım / eskalasyon / nöbet / 7/24 doğru; URL g_tview=board; by_team ile tek kapsam isteği', async () => {
    window.history.replaceState({}, '', '/?tab=admin&g_tab=teams&g_tview=board')
    render(<TeamManager systemRole="ADMIN" globalAdmin onTeamsChange={() => {}} />)
    await waitFor(() => expect(boardRow(1)).toBeTruthy())
    await waitFor(() => expect(boardRow(1).querySelector('[data-slot="tcb-esc"]').getAttribute('data-state')).toBe('ok'))
    expect(api.noc.coverage).toHaveBeenCalledWith({ summary: true, byTeam: true })
    expect(boardRow(2).querySelector('[data-slot="tcb-delivery"]').getAttribute('data-delivery')).toBe('none')
    expect(boardRow(3).querySelector('[data-slot="tcb-esc"]').getAttribute('data-state')).toBe('gap')
    expect(boardRow(1).querySelector('[data-slot="tcb-oncall"]').getAttribute('data-state')).toBe('on')
    expect(boardRow(2).querySelector('[data-slot="tcb-oncall"]').getAttribute('data-state')).toBe('none')
    expect(boardRow(1).querySelector('[data-slot="tcb-noc"]').getAttribute('data-state')).toBe('all')
    expect(boardRow(3).querySelector('[data-slot="tcb-noc"]').getAttribute('data-state')).toBe('partial')
    expect(boardRow(2).querySelector('[data-slot="tcb-noc"]').getAttribute('data-state')).toBe('noMonitors')
    expect(boardRow(1).textContent).toContain('Mudur Elle')
    expect(boardRow(2).textContent).toMatch(/Atanmamış|Not assigned/)
    // en sorunlu takım önce (Arşiv: ulaşım yok + varsayılansız + nöbetsiz)
    expect(document.querySelector('[data-slot="tcb-row"]').getAttribute('data-team-id')).toBe('2')
  })

  it('okunamayan boyut BİLİNMİYOR: kişiler düşerse eskalasyon "bilinmiyor", KPI "—", yeniden dene yalnız düşeni okur', async () => {
    window.history.replaceState({}, '', '/?tab=admin&g_tab=teams&g_tview=board')
    api.admin.getContacts.mockResolvedValueOnce({ success: false, error: 'x' })
    render(<TeamManager systemRole="ADMIN" globalAdmin onTeamsChange={() => {}} />)
    await waitFor(() => expect(boardRow(3)).toBeTruthy())
    await waitFor(() => expect(boardRow(3).querySelector('[data-slot="tcb-esc"]').getAttribute('data-state')).toBe('unknown'))
    const kpi = document.querySelector('[data-slot="tcb-board"] [data-key="critGap"]') || [...document.querySelectorAll('[data-slot="tcb-board"] [data-slot="stat-item"]')].find((b) => /Kritik|critical/i.test(b.textContent))
    expect(kpi.textContent).toContain('—')
    const parts = document.querySelector('[data-slot="tcb-parts"]')
    expect(parts.textContent).toMatch(/eskalasyon kişileri|escalation contacts/)
    expect(api.notificationGroups.list).toHaveBeenCalledTimes(1)
    fireEvent.click(within(parts).getByRole('button'))
    await waitFor(() => expect(boardRow(3).querySelector('[data-slot="tcb-esc"]').getAttribute('data-state')).toBe('gap'))
    expect(api.admin.getContacts).toHaveBeenCalledTimes(2)
    expect(api.notificationGroups.list).toHaveBeenCalledTimes(1)
  })

  it('pano satırı "Düzelt" menüsü takım için ilgili düzeltmeleri sunar', async () => {
    window.history.replaceState({}, '', '/?tab=admin&g_tab=teams&g_tview=board')
    render(<TeamManager systemRole="ADMIN" globalAdmin onTeamsChange={() => {}} />)
    await waitFor(() => expect(boardRow(2)).toBeTruthy())
    const trigger = within(boardRow(2)).getByRole('button', { name: /Düzelt|Fix/ })
    fireEvent.pointerDown(trigger, { button: 0, ctrlKey: false, pointerType: 'mouse' })
    const menu = await screen.findByRole('menu')
    expect(within(menu).getByRole('menuitem', { name: /Varsayılan grup seç|Choose default group/ })).toBeTruthy()
    expect(within(menu).getByRole('menuitem', { name: /Eskalasyon kişisi ekle|Add escalation contact/ })).toBeTruthy()
  })
})

describe('Takımlar — toplu düzeltme', () => {
  it('varsayılan grup: mevcut korunur, tek aday seçili gelir, çok adaylıda kullanıcı seçer; yalnız değişen gönderilir', async () => {
    api.notificationGroups.bulk.mockResolvedValue({ success: true, data: { action: 'make_default', done: 2, skipped: 0, failed: 0, results: [
      { id: 30, name: 'Çağrı Tek', team_id: 3, status: 'done', reason: 'done' }, { id: 21, name: 'Arşiv B', team_id: 2, status: 'done', reason: 'done' }] } })
    render(<TeamManager systemRole="ADMIN" globalAdmin onTeamsChange={() => {}} />)
    await waitFor(() => expect(listRow(3)).toBeTruthy())
    for (const id of [1, 2, 3]) fireEvent.click(within(listRow(id)).getByRole('checkbox'))
    fireEvent.click(await waitFor(() => document.querySelector('[data-testid="tm-bulk-bar"] [data-action="fix-default-group"]')))
    const dlg = await screen.findByRole('dialog')
    await waitFor(() => expect(dlg.querySelectorAll('[data-slot="bulk-preview-row"]')).toHaveLength(3))
    const state = (id) => dlg.querySelectorAll('[data-slot="bulk-preview-row"]')[[1, 2, 3].indexOf(id)]
    expect(state(1).getAttribute('data-reason')).toBe('unchanged')
    expect(state(2).getAttribute('data-reason')).toBe('no_choice')
    expect(state(3).getAttribute('data-state')).toBe('change')
    fireEvent.mouseDown(within(state(2)).getByRole('combobox'))
    fireEvent.mouseDown((await screen.findAllByRole('option')).find((o) => o.textContent.startsWith('Arşiv B')))
    await waitFor(() => expect(state(2).getAttribute('data-state')).toBe('change'))
    fireEvent.click(dlg.querySelector('[data-slot="bulk-confirm"]'))
    await waitFor(() => expect(api.notificationGroups.bulk).toHaveBeenCalledWith({ action: 'make_default', ids: [21, 30] }))
    await waitFor(() => expect(dlg.querySelectorAll('[data-slot="bulk-result-row"][data-status="done"]')).toHaveLength(2))
  })

  it('eskalasyon kişisi ekle: kullanıcı seçilmeden onay kapalı; mevcut kaydı olan takım atlanacak; tek bulk-create isteği', async () => {
    api.admin.bulkCreateContacts.mockResolvedValue({ success: true, data: { action: 'create', done: 2, skipped: 1, failed: 0, results: [] } })
    render(<TeamManager systemRole="ADMIN" globalAdmin onTeamsChange={() => {}} />)
    await waitFor(() => expect(listRow(3)).toBeTruthy())
    for (const id of [1, 2, 3]) fireEvent.click(within(listRow(id)).getByRole('checkbox'))
    fireEvent.click(await waitFor(() => document.querySelector('[data-testid="tm-bulk-bar"] [data-action="fix-contact"]')))
    const dlg = await screen.findByRole('dialog')
    expect(dlg.querySelector('[data-slot="bulk-confirm"]')).toBeDisabled()
    fireEvent.mouseDown(within(dlg.querySelector('[data-field="user_id"]')).getByRole('combobox'))
    fireEvent.mouseDown((await screen.findAllByRole('option')).find((o) => o.textContent.startsWith('Mudur Elle')))
    await waitFor(() => expect(dlg.querySelector('[data-slot="bulk-preview-row"][data-reason="already_exists"]')).toBeTruthy())
    expect(dlg.querySelectorAll('[data-slot="bulk-preview-row"][data-state="change"]')).toHaveLength(2)
    fireEvent.click(dlg.querySelector('[data-slot="bulk-confirm"]'))
    await waitFor(() => expect(api.admin.bulkCreateContacts).toHaveBeenCalledWith({ team_ids: [1, 2, 3], user_id: 8, role: 'TECH', min_alert_level: 'WARNING' }))
  })
})
