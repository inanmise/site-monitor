import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within, act } from './test-utils.jsx'
import RecipientSimulator from '../components/admin/RecipientSimulator.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  formatDateSec: (s) => s ?? '',
  api: withApiFallback({
    admin: { simulateRecipients: vi.fn() },
    notificationGroups: { list: vi.fn() },
  }),
}))
import { api } from '../api/client'

const TEAMS = [{ id: 7, name: 'Takım A' }, { id: 9, name: 'Takım B' }]
const GROUPS = [{ id: 3, name: 'Ops Grubu', team_id: 7, active: true, is_default: false }]
/** Sunucu tel biçimi (snake_case) — EscalationService.simulateRecipients + AdminController push ayağı. */
const RESULT = {
  team_id: 7, team_name: 'Takım A', level: 'HIGH', standalone_monitor: false, managers_included: true,
  contacts_fallback_global: false, email_total: 2,
  team_emails: [{ email: 'takim-a@example.com', team: 'Takım A', source: 'Grup: Ops Grubu', kind: 'TEAM' }],
  contacts: [
    { id: 1, name: 'Ali PO', email: 'po@example.com', role: 'PO', min_level: 'WARNING', team_id: 7, email_duplicate: false },
    { id: 2, name: 'Veli', email: 'takim-a@example.com', role: 'TECH', min_level: 'WARNING', team_id: 7, email_duplicate: true },
    { id: 4, name: 'Can', email: null, role: 'MANAGER', min_level: 'HIGH', team_id: 7, email_duplicate: false },
  ],
  webhooks: [{ id: 1, name: 'Ali PO', type: 'SLACK', target: 'hooks.example.com/…abc123' }],
  push: [
    { username: 'ali', display_name: 'Ali PO', org_role: 'PO', group: 'po', min_level: 'WARNING', decision: 'RECIPIENT' },
    { username: 'veli', display_name: 'Veli', org_role: null, group: null, min_level: null, decision: 'NO_GROUP' },
  ],
}
const EMPTY = { ...RESULT, team_id: 9, team_name: 'Takım B', team_emails: [], contacts: [], webhooks: [], push: [], email_total: 0 }

const url = () => new URLSearchParams(window.location.search)
const channel = (name) => document.querySelector(`[data-slot="wn-channel"][data-channel="${name}"]`)
const tileValue = (key) => document.querySelector(`[data-slot="stat-item"][data-key="${key}"] [data-slot="stat-value"]`)?.textContent
const pickTeam = async (name) => {
  fireEvent.mouseDown(screen.getByRole('combobox', { name: 'Team' }))
  fireEvent.mouseDown(await screen.findByRole('option', { name }))
}

/**
 * "Kim bilgilendirilir?" (2026-09-27: Yönetim Paneli'nde ayrı sekme + yeniden tasarım). Sözleşme: senaryo → sunucu
 * yükü (API değişmedi), senaryo URL'de (g_team/g_level/g_kind/g_group), sonuç özet kutucukları + kanal kartları +
 * "bilgilendirilmeyenler" gerekçeleriyle; boş/hata/yükleniyor durumları; push yalnız yöneticide.
 */
describe('RecipientSimulator — "Who gets notified?" tab', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.admin.simulateRecipients.mockResolvedValue({ success: true, data: RESULT })
    api.notificationGroups.list.mockResolvedValue({ success: true, data: GROUPS })
  })
  afterEach(() => { window.history.replaceState(null, '', '/') })

  it('starts with guidance and no request; picking a team simulates HIGH/CERT and writes g_team', async () => {
    render(<RecipientSimulator teams={TEAMS} isAdmin />)
    expect(screen.getByText('Choose a team to get started')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Simulate' })).toBeDisabled()
    await act(async () => { await new Promise((r) => setTimeout(r, 260)) })
    expect(api.admin.simulateRecipients).not.toHaveBeenCalled()

    await pickTeam('Takım A')
    await waitFor(() => expect(api.admin.simulateRecipients).toHaveBeenCalledWith({ teamId: 7, level: 'HIGH', kind: 'CERT', groupId: null }))
    await waitFor(() => expect(url().get('g_team')).toBe('7'))
    expect(url().get('g_level')).toBeNull()   // varsayılan değer yazılmaz
    expect(await screen.findByRole('heading', { name: 'Result' })).toBeInTheDocument()
  })

  it('deep link restores the whole scenario (g_team, g_level, g_kind, g_group)', async () => {
    window.history.replaceState(null, '', '/?tab=admin&g_tab=whoNotified&g_team=7&g_level=CRITICAL&g_kind=MONITOR&g_group=3')
    render(<RecipientSimulator teams={TEAMS} isAdmin />)
    await waitFor(() => expect(api.admin.simulateRecipients).toHaveBeenCalledWith({ teamId: 7, level: 'CRITICAL', kind: 'MONITOR', groupId: 3 }))
    expect(screen.getByRole('button', { name: 'CRITICAL' })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByRole('button', { name: 'Monitor' })).toHaveAttribute('aria-pressed', 'true')
    await waitFor(() => expect(screen.getByRole('combobox', { name: 'Notification group (optional)' })).toHaveTextContent('Ops Grubu'))
    expect(api.notificationGroups.list).toHaveBeenCalledWith(7)
  })

  it('hand-edited link: unknown level/kind fall back, a non-numeric or unknown team is not simulated', async () => {
    window.history.replaceState(null, '', '/?g_team=99&g_level=BOGUS&g_kind=X')
    render(<RecipientSimulator teams={TEAMS} isAdmin />)
    expect(screen.getByRole('button', { name: 'HIGH' })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByRole('button', { name: 'Certificate' })).toHaveAttribute('aria-pressed', 'true')
    await act(async () => { await new Promise((r) => setTimeout(r, 260)) })
    expect(api.admin.simulateRecipients).not.toHaveBeenCalled()   // 99 görünür takım değil → seçim temizlendi
    expect(screen.getByText('Choose a team to get started')).toBeInTheDocument()
  })

  it('level/kind changes re-run and only non-default values are written to the URL (shareable link)', async () => {
    render(<RecipientSimulator teams={TEAMS} isAdmin={false} defaultTeamId={7} />)
    await waitFor(() => expect(api.admin.simulateRecipients).toHaveBeenCalledTimes(1))
    fireEvent.click(screen.getByRole('button', { name: 'WARNING' }))
    await waitFor(() => expect(api.admin.simulateRecipients).toHaveBeenLastCalledWith({ teamId: 7, level: 'WARNING', kind: 'CERT', groupId: null }))
    await waitFor(() => expect(url().get('g_level')).toBe('WARNING'))
    fireEvent.click(screen.getByRole('button', { name: 'Monitor' }))
    await waitFor(() => expect(api.admin.simulateRecipients).toHaveBeenLastCalledWith({ teamId: 7, level: 'WARNING', kind: 'MONITOR', groupId: null }))
    await waitFor(() => expect(url().get('g_kind')).toBe('MONITOR'))
    fireEvent.click(screen.getByRole('button', { name: 'HIGH' }))
    await waitFor(() => expect(url().get('g_level')).toBeNull())   // varsayılana dönünce parametre silinir
    expect(url().get('g_team')).toBe('7')
  })

  it('late defaultTeamId (teams load after mount) preselects; switching team clears the group', async () => {
    const { rerender } = render(<RecipientSimulator teams={TEAMS} isAdmin={false} defaultTeamId="" />)
    expect(screen.getByText('Choose a team to get started')).toBeInTheDocument()
    rerender(<RecipientSimulator teams={TEAMS} isAdmin={false} defaultTeamId={7} />)
    await waitFor(() => expect(api.admin.simulateRecipients).toHaveBeenCalledWith({ teamId: 7, level: 'HIGH', kind: 'CERT', groupId: null }))

    fireEvent.mouseDown(await screen.findByRole('combobox', { name: 'Notification group (optional)' }))
    fireEvent.mouseDown(await screen.findByRole('option', { name: 'Ops Grubu' }))
    await waitFor(() => expect(api.admin.simulateRecipients).toHaveBeenLastCalledWith({ teamId: 7, level: 'HIGH', kind: 'CERT', groupId: 3 }))

    await pickTeam('Takım B')
    await waitFor(() => expect(api.admin.simulateRecipients).toHaveBeenLastCalledWith({ teamId: 9, level: 'HIGH', kind: 'CERT', groupId: null }))
  })

  it('first load shows a skeleton status instead of an empty result', async () => {
    api.admin.simulateRecipients.mockReturnValue(new Promise(() => {}))
    render(<RecipientSimulator teams={TEAMS} isAdmin defaultTeamId={7} />)
    const loading = document.querySelector('[data-slot="wn-loading"]')
    expect(loading).toHaveAttribute('role', 'status')
    expect(within(loading).getByText('Resolving recipients…')).toBeInTheDocument()
    expect(document.querySelector('[data-slot="wn-result"]')).toBeNull()
  })

  it('result: summary tiles, email grouped by source (duplicate merged), push recipients only, masked webhook', async () => {
    render(<RecipientSimulator teams={TEAMS} isAdmin defaultTeamId={7} />)
    await screen.findByRole('heading', { name: 'Result' })
    expect(tileValue('email')).toBe('2')
    expect(tileValue('push')).toBe('1')
    expect(tileValue('webhook')).toBe('1')
    expect(tileValue('excluded')).toBe('2')
    expect(screen.getByText('of 2 team members')).toBeInTheDocument()

    const email = channel('email')
    const rows = within(email).getAllByRole('listitem')
    expect(rows).toHaveLength(2)   // takım/grup adresi + Ali — Veli aynı adreste (tek e-posta), Can'ın adresi yok
    expect(within(rows[0]).getByText('takim-a@example.com')).toBeInTheDocument()
    expect(within(rows[0]).getByText('Notification group')).toBeInTheDocument()
    expect(within(rows[0]).getByText('Also an escalation contact: Veli — one email is sent')).toBeInTheDocument()
    expect(within(rows[1]).getByText('po@example.com')).toBeInTheDocument()
    expect(within(rows[1]).getByText('Escalation contact')).toBeInTheDocument()
    expect(within(rows[1]).getByRole('button', { name: 'Copy po@example.com' })).toBeInTheDocument()

    const push = channel('push')
    expect(within(push).getAllByRole('listitem')).toHaveLength(1)
    expect(within(push).getByText('Ali PO')).toBeInTheDocument()
    expect(within(push).queryByText('Veli')).toBeNull()
    expect(push.querySelector('[data-decision="RECIPIENT"]')).not.toBeNull()

    expect(within(channel('webhook')).getByText(/hooks\.example\.com/)).toBeInTheDocument()
    expect(within(channel('webhook')).getByText('Slack')).toBeInTheDocument()
  })

  it('"Not notified": the tile opens the list with each person’s reason', async () => {
    render(<RecipientSimulator teams={TEAMS} isAdmin defaultTeamId={7} />)
    await screen.findByRole('heading', { name: 'Result' })
    const toggle = screen.getByRole('button', { name: /Not notified \(2\)/ })
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    fireEvent.click(screen.getByRole('button', { name: 'Not notified: 2 — go to the list' }))
    expect(toggle).toHaveAttribute('aria-expanded', 'true')
    const list = document.querySelector('[data-slot="wn-excluded"]')
    const rows = within(list).getAllByRole('listitem')
    expect(rows).toHaveLength(2)
    const veli = rows.find((r) => within(r).queryByText('Veli'))
    expect(veli.querySelector('[data-decision="NO_GROUP"]')).toHaveTextContent('No group match (org role is not assigned to any group)')
    const can = rows.find((r) => within(r).queryByText('Can'))
    expect(within(can).getByText('No email address')).toBeInTheDocument()
  })

  it('why-badge explains the inclusion on tap (HintPopover, works without hover)', async () => {
    render(<RecipientSimulator teams={TEAMS} isAdmin defaultTeamId={7} />)
    await screen.findByRole('heading', { name: 'Result' })
    fireEvent.click(screen.getByRole('button', { name: 'Escalation contact — why is Ali PO included?' }))
    expect(await screen.findByRole('tooltip')).toHaveTextContent('Their minimum level is WARNING, and this HIGH alert meets it.')
    // Aynı kişinin webhook satırındaki rozet AYRI adla (iki düğmede aynı ad = belirsiz)
    expect(screen.getByRole('button', { name: 'Escalation contact — why does Ali PO’s webhook fire?' })).toBeInTheDocument()
  })

  it('non-admin: no push card or tile; monitor alerts at WARNING say only the team is notified', async () => {
    api.admin.simulateRecipients.mockResolvedValue({ success: true, data: { ...RESULT, level: 'WARNING', managers_included: false, contacts: [], webhooks: [], push: undefined, email_total: 1 } })
    render(<RecipientSimulator teams={TEAMS} isAdmin={false} defaultTeamId={7} />)
    await screen.findByRole('heading', { name: 'Result' })
    expect(channel('push')).toBeNull()
    expect(document.querySelector('[data-slot="stat-item"][data-key="push"]')).toBeNull()
    expect(screen.getByText(/For monitor alerts at WARNING only the team is notified/)).toBeInTheDocument()
  })

  it('scoped ADMIN (server omitted push): the push card says it is global-admin only, not "None"', async () => {
    api.admin.simulateRecipients.mockResolvedValue({ success: true, data: { ...RESULT, push: undefined } })
    render(<RecipientSimulator teams={TEAMS} isAdmin defaultTeamId={7} />)
    await screen.findByRole('heading', { name: 'Result' })
    expect(within(channel('push')).getByText('Only global administrators can see push decisions.')).toBeInTheDocument()
    expect(document.querySelector('[data-slot="stat-item"][data-key="push"]')).toBeNull()
  })

  it('nobody notified: warning with next steps that jump to contacts / notification groups for the team', async () => {
    api.admin.simulateRecipients.mockResolvedValue({ success: true, data: EMPTY })
    const onNavigate = vi.fn()
    render(<RecipientSimulator teams={TEAMS} isAdmin defaultTeamId={9} onNavigate={onNavigate} />)
    const banner = (await screen.findByText('Nobody would be notified')).closest('[data-slot="alert"]')
    expect(banner).toHaveAttribute('data-tone', 'warning')
    fireEvent.click(within(banner).getByRole('button', { name: 'Add an escalation contact' }))
    expect(onNavigate).toHaveBeenLastCalledWith('contacts', { g_team: '9' })
    fireEvent.click(within(banner).getByRole('button', { name: 'Set up a notification group' }))
    expect(onNavigate).toHaveBeenLastCalledWith('notifyGroups', { g_team: '9' })
    expect(tileValue('email')).toBe('0')
  })

  it('error: a clean failure and a network failure both show an alert; "Try again" re-runs', async () => {
    api.admin.simulateRecipients.mockResolvedValueOnce({ success: false, error: 'forbidden' })
    render(<RecipientSimulator teams={TEAMS} isAdmin defaultTeamId={7} />)
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('The simulation couldn’t run')
    expect(alert).toHaveTextContent('forbidden')
    fireEvent.click(within(alert).getByRole('button', { name: 'Try again' }))
    await screen.findByRole('heading', { name: 'Result' })
    expect(api.admin.simulateRecipients).toHaveBeenCalledTimes(2)

    api.admin.simulateRecipients.mockRejectedValueOnce(new Error('network down'))
    fireEvent.click(screen.getByRole('button', { name: 'Simulate' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('network down')
  })
})
