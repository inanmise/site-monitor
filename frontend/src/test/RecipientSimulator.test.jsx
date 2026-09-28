import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within, act } from './test-utils.jsx'
import RecipientSimulator from '../components/admin/RecipientSimulator.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  formatDateSec: (s) => s ?? '',
  api: withApiFallback({
    admin: { simulateRecipients: vi.fn() },
    notificationGroups: { list: vi.fn() },
    noc: { coverage: vi.fn() },
  }),
}))
import { api } from '../api/client'

const TEAMS = [{ id: 7, name: 'Takım A' }, { id: 9, name: 'Takım B' }]
const GROUPS = [{ id: 3, name: 'Ops Grubu', team_id: 7, active: true, is_default: false }]
/** Sunucu tel biçimi (snake_case) — EscalationService.simulateRecipients + AdminController push ayağı. */
const RESULT = {
  team_id: 7, team_name: 'Takım A', level: 'HIGH', standalone_monitor: false, managers_included: true,
  team_contacts_missing: false, team_contacts_defined: true, email_total: 2,
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
  // Push ayağı görünürlüğü + kanal durumu (2026-09-28, PushDecisionAccess / UserPushService.scenarioChannel)
  push_access: 'FULL', push_access_reason: 'GLOBAL_ADMIN', push_settings: 'FULL', push_viewer: 'admin',
  push_channel: { enabled: true, configured: true, team_enabled: true, types: ['cert'], disabled_types: [],
    quiet_start: null, quiet_end: null, quiet_min_level: null, quiet_active: false, quiet_blocks_level: false, block_reason: null },
}
const EMPTY = { ...RESULT, team_id: 9, team_name: 'Takım B', team_emails: [], contacts: [], webhooks: [], push: [], email_total: 0,
  team_contacts_missing: true, team_contacts_defined: false }

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
    api.noc.coverage.mockResolvedValue({ success: true, data: { summary: { total: 3, covered: 1, not_covered: 2, paused: 0,
      by_type: { SSL: { total: 3, covered: 1 } }, active_groups: 1, disabled_types: [], min_level: 'CRITICAL' }, items: [] } })
  })
  afterEach(() => { window.history.replaceState(null, '', '/') })

  it('starts with guidance and no request; picking a team simulates HIGH/CERT and writes g_team', async () => {
    render(<RecipientSimulator teams={TEAMS} isAdmin />)
    expect(screen.getByText('Choose a team to get started')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Simulate' })).toBeDisabled()
    await act(async () => { await new Promise((r) => setTimeout(r, 260)) })
    expect(api.admin.simulateRecipients).not.toHaveBeenCalled()

    await pickTeam('Takım A')
    await waitFor(() => expect(api.admin.simulateRecipients).toHaveBeenCalledWith({ teamId: 7, level: 'HIGH', kind: 'CERT', groupId: null, ugTeamId: null }))
    await waitFor(() => expect(url().get('g_team')).toBe('7'))
    expect(url().get('g_level')).toBeNull()   // varsayılan değer yazılmaz
    expect(await screen.findByRole('heading', { name: 'Result' })).toBeInTheDocument()
  })

  it('deep link restores the whole scenario (g_team, g_level, g_kind, g_group)', async () => {
    window.history.replaceState(null, '', '/?tab=admin&g_tab=whoNotified&g_team=7&g_level=CRITICAL&g_kind=MONITOR&g_group=3')
    render(<RecipientSimulator teams={TEAMS} isAdmin />)
    await waitFor(() => expect(api.admin.simulateRecipients).toHaveBeenCalledWith({ teamId: 7, level: 'CRITICAL', kind: 'MONITOR', groupId: 3, ugTeamId: null }))
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
    await waitFor(() => expect(api.admin.simulateRecipients).toHaveBeenLastCalledWith({ teamId: 7, level: 'WARNING', kind: 'CERT', groupId: null, ugTeamId: null }))
    await waitFor(() => expect(url().get('g_level')).toBe('WARNING'))
    fireEvent.click(screen.getByRole('button', { name: 'Monitor' }))
    await waitFor(() => expect(api.admin.simulateRecipients).toHaveBeenLastCalledWith({ teamId: 7, level: 'WARNING', kind: 'MONITOR', groupId: null, ugTeamId: null }))
    await waitFor(() => expect(url().get('g_kind')).toBe('MONITOR'))
    fireEvent.click(screen.getByRole('button', { name: 'HIGH' }))
    await waitFor(() => expect(url().get('g_level')).toBeNull())   // varsayılana dönünce parametre silinir
    expect(url().get('g_team')).toBe('7')
  })

  it('late defaultTeamId (teams load after mount) preselects; switching team clears the group', async () => {
    const { rerender } = render(<RecipientSimulator teams={TEAMS} isAdmin={false} defaultTeamId="" />)
    expect(screen.getByText('Choose a team to get started')).toBeInTheDocument()
    rerender(<RecipientSimulator teams={TEAMS} isAdmin={false} defaultTeamId={7} />)
    await waitFor(() => expect(api.admin.simulateRecipients).toHaveBeenCalledWith({ teamId: 7, level: 'HIGH', kind: 'CERT', groupId: null, ugTeamId: null }))

    fireEvent.mouseDown(await screen.findByRole('combobox', { name: 'Notification group (optional)' }))
    fireEvent.mouseDown(await screen.findByRole('option', { name: 'Ops Grubu' }))
    await waitFor(() => expect(api.admin.simulateRecipients).toHaveBeenLastCalledWith({ teamId: 7, level: 'HIGH', kind: 'CERT', groupId: 3, ugTeamId: null }))

    await pickTeam('Takım B')
    await waitFor(() => expect(api.admin.simulateRecipients).toHaveBeenLastCalledWith({ teamId: 9, level: 'HIGH', kind: 'CERT', groupId: null, ugTeamId: null }))
  })

  it('first load shows a skeleton status instead of an empty result', async () => {
    api.admin.simulateRecipients.mockReturnValue(new Promise(() => {}))
    render(<RecipientSimulator teams={TEAMS} isAdmin defaultTeamId={7} />)
    const loading = document.querySelector('[data-slot="wn-loading"]')
    expect(loading).toHaveAttribute('role', 'status')
    expect(within(loading).getByText('Resolving recipients…')).toBeInTheDocument()
    expect(document.querySelector('[data-slot="wn-result"]')).toBeNull()
  })

  it('result: summary tiles, email grouped by source (duplicate merged), push gets / doesn’t get, masked webhook', async () => {
    render(<RecipientSimulator teams={TEAMS} isAdmin defaultTeamId={7} />)
    await screen.findByRole('heading', { name: 'Result' })
    expect(tileValue('email')).toBe('2')
    expect(tileValue('push')).toBe('1')
    expect(tileValue('pushNot')).toBe('1')
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

    // Push kartı artık HER üyeyi gösterir: alan (Ali) VE almayan (Veli) — nedeniyle (2026-09-28)
    const push = channel('push')
    expect(within(push).getAllByRole('listitem')).toHaveLength(2)
    expect(within(push).getByText('Ali PO')).toBeInTheDocument()
    expect(push.querySelector('[data-decision="RECIPIENT"]')).not.toBeNull()
    const veli = within(push).getAllByRole('listitem').find((r) => within(r).queryByText('Veli'))
    expect(veli.querySelector('[data-decision="NO_GROUP"]')).toHaveTextContent('Doesn’t get it')
    expect(within(veli).getByText('No group match (org role is not assigned to any group)')).toBeInTheDocument()

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

  it('older server without push data: the push card explains it is hidden (no tile); WARNING monitor note stays', async () => {
    api.admin.simulateRecipients.mockResolvedValue({ success: true, data: { ...RESULT, level: 'WARNING', managers_included: false, contacts: [], webhooks: [], push: undefined, push_access: undefined, push_access_reason: undefined, push_channel: undefined, email_total: 1 } })
    render(<RecipientSimulator teams={TEAMS} isAdmin={false} defaultTeamId={7} />)
    await screen.findByRole('heading', { name: 'Result' })
    const hidden = channel('push').querySelector('[data-slot="wn-push-hidden"]')
    expect(hidden).toHaveTextContent('Per-person push decisions aren’t available to you')
    expect(hidden).toHaveTextContent('You don’t have permission to see this team’s per-person push decisions.')
    expect(document.querySelector('[data-slot="stat-item"][data-key="push"]')).toBeNull()
    expect(screen.getByText(/For monitor alerts at WARNING only the team is notified/)).toBeInTheDocument()
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

  // 2026-09-28 prod hatası: kontaksız takımın KRİTİK alarmı başka takımların müdürlerine gidiyordu; ekran bunu
  // "global kişilere düşüldü" diye gösteriyordu. Artık takımın kendi kişisi yoksa yalnız takım alıcıları — ekran söyler.
  it('team without escalation contacts: says none are set up, only the team is notified, never another team', async () => {
    api.admin.simulateRecipients.mockResolvedValue({ success: true, data: { ...RESULT, level: 'CRITICAL',
      contacts: [], webhooks: [], email_total: 1, team_contacts_missing: true, team_contacts_defined: false } })
    const onNavigate = vi.fn()
    render(<RecipientSimulator teams={TEAMS} isAdmin defaultTeamId={7} onNavigate={onNavigate} />)
    const banner = (await screen.findByText('No escalation contacts set up')).closest('[data-slot="alert"]')
    expect(banner).toHaveAttribute('data-tone', 'warning')
    expect(banner).toHaveTextContent('the alert goes only to the team’s own recipients')
    expect(banner).toHaveTextContent('Another team’s manager or escalation contacts are never added.')
    fireEvent.click(within(banner).getByRole('button', { name: 'Add an escalation contact' }))
    expect(onNavigate).toHaveBeenLastCalledWith('contacts', { g_team: '7' })
    const rows = within(channel('email')).getAllByRole('listitem')
    expect(rows).toHaveLength(1)   // yalnız takım/grup adresi
    expect(screen.queryByText('Global escalation contact')).toBeNull()
    expect(screen.queryByText(/fell back to team-less/)).toBeNull()
    expect(screen.queryByText('Nobody would be notified')).toBeNull()
  })

  // 2026-09-28 "her sahip takım kendi kişisi": sertifika senaryosunda UG takımı seçilebilir; sunucu SY + UG'yi ayrı sayar.
  it('certificate scenario: picking a UG team sends ugTeamId + writes g_ug; a monitor scenario drops it', async () => {
    render(<RecipientSimulator teams={TEAMS} isAdmin defaultTeamId={7} />)
    await screen.findByRole('heading', { name: 'Result' })
    fireEvent.mouseDown(screen.getByRole('combobox', { name: /UG team/ }))
    expect(screen.queryByRole('option', { name: 'Takım A' })).toBeNull()   // SY kendisi UG olamaz
    fireEvent.mouseDown(await screen.findByRole('option', { name: 'Takım B' }))
    await waitFor(() => expect(api.admin.simulateRecipients).toHaveBeenLastCalledWith(
      { teamId: 7, level: 'HIGH', kind: 'CERT', groupId: null, ugTeamId: 9 }))
    await waitFor(() => expect(url().get('g_ug')).toBe('9'))

    fireEvent.click(screen.getByRole('button', { name: 'Monitor' }))
    await waitFor(() => expect(api.admin.simulateRecipients).toHaveBeenLastCalledWith(
      { teamId: 7, level: 'HIGH', kind: 'MONITOR', groupId: null, ugTeamId: null }))
    expect(screen.queryByRole('combobox', { name: /UG team/ })).toBeNull()
    await waitFor(() => expect(url().get('g_ug')).toBeNull())
  })

  it('SY + UG: a per-team banner names the team that has no escalation contacts (the other team is fine)', async () => {
    api.admin.simulateRecipients.mockResolvedValue({ success: true, data: { ...RESULT, level: 'CRITICAL',
      team_contacts_missing: true, team_contacts_defined: false,
      owners: [
        { team_id: 7, role: 'SY', team_name: 'Takım A', contacts_missing: true, contacts_defined: false },
        { team_id: 9, role: 'UG', team_name: 'Takım B', contacts_missing: false, contacts_defined: true },
      ] } })
    render(<RecipientSimulator teams={TEAMS} isAdmin defaultTeamId={7} />)
    const banner = (await screen.findByText(/No escalation contacts are set up for the SY team \(Takım A\)/)).closest('[data-slot="alert"]')
    expect(banner).toHaveAttribute('data-tone', 'warning')
    expect(banner).toHaveTextContent('Another team’s contacts are never added.')
    expect(screen.queryByText(/the UG team \(Takım B\)/)).toBeNull()
    expect(screen.getAllByText('No escalation contacts set up')).toHaveLength(1)   // tek takımlı şerit ikinci kez çizilmez
  })

  it('team contacts exist but none takes this level: an info note, no "not set up" warning', async () => {
    api.admin.simulateRecipients.mockResolvedValue({ success: true, data: { ...RESULT, contacts: [], webhooks: [],
      email_total: 1, team_contacts_missing: true, team_contacts_defined: true } })
    render(<RecipientSimulator teams={TEAMS} isAdmin defaultTeamId={7} />)
    const note = (await screen.findByText(/None of this team’s escalation contacts receives HIGH alerts/)).closest('[data-slot="alert"]')
    expect(note).toHaveAttribute('data-tone', 'info')
    expect(screen.queryByText('No escalation contacts set up')).toBeNull()
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
