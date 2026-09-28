import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, within, waitFor } from './test-utils.jsx'
import RecipientSimulator from '../components/admin/RecipientSimulator.jsx'
import { buildView, filterPushRows } from '../components/admin/whonotified/whoNotifiedModel.js'

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

const TEAMS = [{ id: 7, name: 'Takım A' }]
const OPEN_CHANNEL = { enabled: true, configured: true, team_enabled: true, types: ['cert'], disabled_types: [],
  quiet_start: null, quiet_end: null, quiet_min_level: null, quiet_active: false, quiet_blocks_level: false, block_reason: null }
/** Sunucu tel biçimi — UserPushRecipientResolver.Explanation (snake_case); kişiler sahte (Kişi A…). */
const MEMBERS = [
  { username: 'kisia', display_name: 'Kişi A', title: 'Uzman', org_role: 'TECH', active: true, group: 'uzman', group_enabled: true, min_level: 'WARNING', opt_out: false, decision: 'RECIPIENT' },
  { username: 'kisib', display_name: 'Kişi B', title: null, org_role: null, active: true, group: null, group_enabled: null, min_level: null, opt_out: false, decision: 'NO_ORG_ROLE' },
  { username: 'kisic', display_name: 'Kişi C', title: 'Analist', org_role: 'DANISMAN', active: true, group: null, group_enabled: null, min_level: null, opt_out: false, decision: 'NO_GROUP' },
  { username: 'kisid', display_name: 'Kişi D', title: 'Müdür', org_role: 'MANAGER', active: true, group: 'yonetici', group_enabled: true, min_level: 'CRITICAL', opt_out: false, decision: 'BELOW_MIN_LEVEL' },
  { username: 'kisie', display_name: 'Kişi E', title: 'Uzman', org_role: 'TECH', active: true, group: 'uzman', group_enabled: true, min_level: 'WARNING', opt_out: true, decision: 'SKIPPED_USER_OPT_OUT' },
  { username: 'kisif', display_name: 'Kişi F', title: 'Uzman', org_role: 'TECH', active: true, group: null, group_enabled: null, min_level: null, opt_out: false, decision: 'MISSING_MEMBERSHIP' },
]
const base = (over = {}) => ({
  team_id: 7, team_name: 'Takım A', level: 'HIGH', standalone_monitor: false, managers_included: true,
  team_contacts_missing: false, team_contacts_defined: true, email_total: 1,
  team_emails: [{ email: 'takim-a@example.com', team: 'Takım A', source: 'Takım maili', kind: 'TEAM' }],
  contacts: [], webhooks: [],
  push: MEMBERS, push_access: 'FULL', push_access_reason: 'GLOBAL_ADMIN', push_settings: 'FULL', push_viewer: 'admin',
  push_channel: OPEN_CHANNEL,
  ...over,
})

const pushCard = () => document.querySelector('[data-slot="wn-channel"][data-channel="push"]')
const rowOf = (name) => within(pushCard()).getAllByRole('listitem').find((r) => within(r).queryByText(name))
const tileValue = (key) => document.querySelector(`[data-slot="stat-item"][data-key="${key}"] [data-slot="stat-value"]`)?.textContent
const live = () => document.querySelector('[data-slot="wn-live"]').textContent
const navEvents = []
const onNav = (e) => navEvents.push(e.detail)

async function renderWith(data, props = {}) {
  api.admin.simulateRecipients.mockResolvedValue({ success: true, data })
  render(<RecipientSimulator teams={TEAMS} isAdmin defaultTeamId={7} {...props} />)
  await screen.findByRole('heading', { name: 'Result' })
}

/**
 * "Kim bilgilendirilir?" push kartı (2026-09-28, kullanıcı: "kişi bazlı webhook bildirimlerini bu sekmede göremedim").
 * Sözleşme: HER üye "Gets it / Doesn’t get it" + sade gerekçe (Ayarlar "Kim alır?" ile aynı karar sözlüğü) + sonraki
 * adım; süzgeç + arama; görünürlük sunucudan (FULL / SELF / NONE) ve göremeyene NEDEN; kanal kapalıyken açık durum;
 * özet kutucukları ve ekran okuyucu özeti push sayılarıyla tutarlı; 7/24 notu.
 */
describe('Who gets notified — per-person push decisions', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    navEvents.length = 0
    window.addEventListener('sm:navigate', onNav)
    api.notificationGroups.list.mockResolvedValue({ success: true, data: [] })
    api.noc.coverage.mockResolvedValue({ success: true, data: { summary: { total: 4, covered: 1, not_covered: 3, paused: 0,
      by_type: { SSL: { total: 2, covered: 1 }, PING: { total: 2, covered: 0 } }, active_groups: 1, disabled_types: ['PING'], min_level: 'CRITICAL' }, items: [] } })
  })
  afterEach(() => {
    window.removeEventListener('sm:navigate', onNav)
    window.history.replaceState(null, '', '/')
  })

  it('lists everyone who gets it AND everyone who doesn’t, each with the plain reason', async () => {
    await renderWith(base())
    const rows = within(pushCard()).getAllByRole('listitem')
    expect(rows).toHaveLength(6)
    expect(rowOf('Kişi A').querySelector('[data-decision="RECIPIENT"]')).toHaveTextContent('Gets it')
    const reasons = {
      'Kişi B': 'No org role — set it in User management (derived automatically on AD sign-in)',
      'Kişi C': 'No group match (org role is not assigned to any group)',
      'Kişi D': 'Level below the group minimum',
      'Kişi E': 'Opted out (My Activity)',
      'Kişi F': 'No team membership row (this is their primary team, but the membership table has no row — data defect)',
    }
    for (const [name, text] of Object.entries(reasons)) {
      const row = rowOf(name)
      expect(row).toHaveAttribute('data-receives', 'false')
      expect(row.querySelector('[data-wn="push-decision"]')).toHaveTextContent('Doesn’t get it')
      expect(within(row).getByText(text)).toBeInTheDocument()
    }
    // Açıklamalar: grup/seviye bağlamıyla
    expect(within(rowOf('Kişi D')).getByText(/minimum level for their group \(Manager\) is CRITICAL, and this HIGH alert falls below it/)).toBeInTheDocument()
    expect(within(rowOf('Kişi C')).getByText(/No push group is linked to their org role \(DANISMAN\)/)).toBeInTheDocument()
    expect(pushCard().querySelector('[data-wn="count"]')).toHaveTextContent('1/6')   // sayı rozeti: alır/toplam
  })

  it('filters (All / Will get it / Won’t get it) and search narrow the list', async () => {
    await renderWith(base())
    const card = pushCard()
    fireEvent.click(within(card).getByRole('button', { name: 'Will get it (1)' }))
    expect(within(card).getAllByRole('listitem')).toHaveLength(1)
    expect(within(card).getByText('Kişi A')).toBeInTheDocument()

    fireEvent.click(within(card).getByRole('button', { name: 'Won’t get it (5)' }))
    expect(within(card).getAllByRole('listitem')).toHaveLength(5)
    expect(within(card).queryByText('Kişi A')).toBeNull()

    fireEvent.change(within(card).getByRole('searchbox', { name: 'Search by name or username' }), { target: { value: 'kisie' } })
    expect(within(card).getAllByRole('listitem')).toHaveLength(1)
    expect(within(card).getByText('Kişi E')).toBeInTheDocument()

    fireEvent.change(within(card).getByRole('searchbox', { name: 'Search by name or username' }), { target: { value: 'nobody-here' } })
    expect(within(card).queryAllByRole('listitem')).toHaveLength(0)
    expect(within(card).getByText('Nobody matches this filter.')).toBeInTheDocument()

    fireEvent.click(within(card).getByRole('button', { name: 'All (6)' }))
    fireEvent.change(within(card).getByRole('searchbox', { name: 'Search by name or username' }), { target: { value: '' } })
    expect(within(card).getAllByRole('listitem')).toHaveLength(6)
  })

  it('next steps: open the user, edit push groups (Settings → Webhook), opt-out is only theirs to change', async () => {
    const onNavigate = vi.fn()
    await renderWith(base(), { onNavigate })
    fireEvent.click(within(rowOf('Kişi B')).getByRole('button', { name: 'Open user Kişi B' }))
    expect(onNavigate).toHaveBeenLastCalledWith('users', { g_q: 'kisib' })
    fireEvent.click(within(rowOf('Kişi F')).getByRole('button', { name: 'Open user Kişi F' }))
    expect(onNavigate).toHaveBeenLastCalledWith('users', { g_q: 'kisif' })

    fireEvent.click(within(rowOf('Kişi D')).getByRole('button', { name: 'Edit push groups for Kişi D' }))
    expect(navEvents.at(-1)).toEqual({ tab: 'settings', params: { sec: 'userpush' } })
    expect(within(rowOf('Kişi C')).getByRole('button', { name: 'Edit push groups for Kişi C' })).toBeInTheDocument()

    const optOut = rowOf('Kişi E')
    expect(within(optOut).getByText(/only they can turn them back on/)).toBeInTheDocument()
    expect(within(optOut).queryByRole('button', { name: /Kişi E/ })).toBeNull()
    expect(within(rowOf('Kişi A')).queryByRole('button', { name: /Open user|Edit push groups/ })).toBeNull()
  })

  it('summary tiles and the screen-reader summary carry push counts; “No push” tile opens the list filtered', async () => {
    await renderWith(base())
    expect(tileValue('push')).toBe('1')
    expect(tileValue('pushNot')).toBe('5')
    expect(screen.getByText('of 6 team members')).toBeInTheDocument()
    expect(live()).toBe('1 email and 0 webhook recipients; 1 would get a push and 5 wouldn’t; 5 not notified.')
    fireEvent.click(screen.getByRole('button', { name: 'No push: 5 — go to the list' }))
    await waitFor(() => expect(within(pushCard()).getByRole('button', { name: 'Won’t get it (5)' })).toHaveAttribute('aria-pressed', 'true'))
    expect(within(pushCard()).getAllByRole('listitem')).toHaveLength(5)
  })

  it('team manager (scoped admin): full list with a note why; group fixes need a global admin only for the address', async () => {
    await renderWith(base({ push_access_reason: 'TEAM_MANAGER', push_settings: 'LIMITED', push_viewer: 'kisid' }))
    expect(within(pushCard()).getByText('You manage this team, so you can see every member’s decision.')).toBeInTheDocument()
    expect(within(rowOf('Kişi D')).getByText('You')).toBeInTheDocument()   // izleyenin kendi satırı işaretli
    expect(within(rowOf('Kişi C')).getByRole('button', { name: 'Edit push groups for Kişi C' })).toBeInTheDocument()
  })

  it('TEAM_ADMIN without Settings access: fix text says whom to ask instead of a dead link', async () => {
    await renderWith(base({ push_access_reason: 'TEAM_MANAGER', push_settings: 'NONE' }), { isAdmin: false })
    const c = rowOf('Kişi C')
    expect(within(c).queryByRole('button', { name: /Edit push groups/ })).toBeNull()
    expect(within(c).getByText(/ask your team’s manager or a global administrator/)).toBeInTheDocument()
  })

  it('member (SELF): only their own row, “You”, My Activity link for opt-out; tile and summary speak to them', async () => {
    await renderWith(base({ push: [MEMBERS[4]], push_access: 'SELF', push_access_reason: 'MEMBER_SELF', push_settings: 'NONE', push_viewer: 'kisie' }),
      { isAdmin: false })
    const card = pushCard()
    expect(within(card).getAllByRole('listitem')).toHaveLength(1)
    expect(within(card).getByText('You')).toBeInTheDocument()
    expect(within(card).getByText('Would you get a push notification in this scenario?')).toBeInTheDocument()
    expect(within(card).getByText(/visible to the people who manage it/)).toBeInTheDocument()
    expect(within(card).queryByRole('button', { name: 'All (1)' })).toBeNull()   // tek satırda süzgeç yok
    fireEvent.click(within(card).getByRole('button', { name: 'Open My Activity' }))
    expect(navEvents.at(-1)).toEqual({ tab: 'myactivity', params: undefined })
    expect(tileValue('push')).toBe('No')
    expect(document.querySelector('[data-slot="stat-item"][data-key="pushNot"]')).toBeNull()
    expect(live()).toBe('1 email and 0 webhook recipients; you wouldn’t get a push in this scenario; 1 not notified.')
  })

  it('member whose own row is missing is told membership may be missing (not shown someone else’s row)', async () => {
    await renderWith(base({ push: [], push_access: 'SELF', push_access_reason: 'MEMBER_SELF', push_settings: 'NONE', push_viewer: 'kisiz' }),
      { isAdmin: false })
    expect(pushCard().querySelector('[data-slot="wn-push-self-missing"]')).toHaveTextContent('your team membership may be missing')
    expect(tileValue('push')).toBe('—')
  })

  it('NONE (not a member): clear reason and whom to ask; no push rows or tiles', async () => {
    await renderWith(base({ push: undefined, push_access: 'NONE', push_access_reason: 'NOT_MEMBER', push_settings: 'NONE', push_viewer: 'kisix' }),
      { isAdmin: false })
    const hidden = pushCard().querySelector('[data-slot="wn-push-hidden"]')
    expect(hidden).toHaveAttribute('data-reason', 'NOT_MEMBER')
    expect(hidden).toHaveTextContent('You’re neither a member nor a manager of this team.')
    expect(hidden).toHaveTextContent('ask the team’s manager or a global administrator')
    expect(within(pushCard()).queryAllByRole('listitem')).toHaveLength(0)
    expect(document.querySelector('[data-slot="stat-item"][data-key="push"]')).toBeNull()
    expect(live()).toBe('1 email and 0 webhook recipients; 0 not notified.')
  })

  it('push switched off globally: explicit banner + settings link; “gets it” members count as not getting it', async () => {
    await renderWith(base({ push_channel: { ...OPEN_CHANNEL, enabled: false, block_reason: 'CHANNEL_DISABLED' } }))
    const banner = pushCard().querySelector('[data-slot="wn-push-channel"]')
    expect(banner).toHaveAttribute('data-block', 'CHANNEL_DISABLED')
    expect(banner).toHaveTextContent('Nobody gets a push in this scenario')
    expect(banner).toHaveTextContent('The push channel is switched off globally')
    fireEvent.click(within(banner).getByRole('button', { name: 'Open Webhook Notifications settings' }))
    expect(navEvents.at(-1)).toEqual({ tab: 'settings', params: { sec: 'userpush' } })
    expect(tileValue('push')).toBe('0')
    expect(tileValue('pushNot')).toBe('6')
    const a = rowOf('Kişi A')
    expect(a.querySelector('[data-wn="push-decision"]')).toHaveTextContent('Doesn’t get it')
    expect(within(a).getByText('Push channel is off')).toBeInTheDocument()
    expect(within(a).getByText(/they’ll get it once the channel block is lifted/)).toBeInTheDocument()
  })

  it('address missing: a scoped admin is sent to a global admin; a member is told to ask an administrator', async () => {
    const blocked = { ...OPEN_CHANNEL, configured: false, block_reason: 'NOT_CONFIGURED' }
    await renderWith(base({ push_settings: 'LIMITED', push_access_reason: 'TEAM_MANAGER', push_channel: blocked }))
    const banner = pushCard().querySelector('[data-slot="wn-push-channel"]')
    expect(banner).toHaveTextContent('Only a global administrator can change this')
    expect(within(banner).queryByRole('button')).toBeNull()
  })

  it('partial type scope and quiet-hours window are explained without blocking', async () => {
    await renderWith(base({ standalone_monitor: true, push_channel: { ...OPEN_CHANNEL, types: ['http', 'ping', 'dns'], disabled_types: ['ping', 'dns'],
      quiet_start: '22:00', quiet_end: '07:00', quiet_min_level: 'CRITICAL', quiet_blocks_level: true } }))
    const status = pushCard().querySelector('[data-slot="wn-push-channel"]')
    expect(status).toHaveTextContent('Push is switched off for these monitor types: Ping, DNS.')
    expect(status).toHaveTextContent('Quiet hours 22:00–07:00: alerts below CRITICAL aren’t sent')
    expect(tileValue('push')).toBe('1')
  })

  it('24/7 note: level below the 24/7 minimum, type status, team coverage and a link to 24/7 Coverage', async () => {
    await renderWith(base())
    const noc = await waitFor(() => {
      const el = document.querySelector('[data-slot="wn-noc-status"]')
      expect(el).not.toBeNull()
      return el
    })
    expect(api.noc.coverage).toHaveBeenCalledWith({ teamId: 7, type: 'SSL' })
    expect(noc.querySelector('[data-noc="level"]')).toHaveAttribute('data-ok', 'false')
    expect(noc).toHaveTextContent('This level is below the 24/7 minimum (CRITICAL or above)')
    expect(noc.querySelector('[data-noc="types"]')).toHaveTextContent('24/7 is on for this alert type')
    expect(noc.querySelector('[data-noc="coverage"]')).toHaveTextContent('1 of 2 monitors in this team currently notify the 24/7 team')
    fireEvent.click(screen.getByRole('button', { name: 'Open 24/7 Coverage' }))
    expect(navEvents.at(-1)).toEqual({ tab: 'noc', params: { n_team: '7', n_type: 'SSL' } })
  })

  it('24/7 note for monitor alerts lists types switched off for 24/7 and never invents a per-monitor answer', async () => {
    window.history.replaceState(null, '', '/?g_kind=MONITOR&g_level=CRITICAL')
    await renderWith(base({ level: 'CRITICAL', standalone_monitor: true }))
    await waitFor(() => expect(document.querySelector('[data-slot="wn-noc-status"]')).not.toBeNull())
    const noc = document.querySelector('[data-slot="wn-noc-status"]')
    expect(api.noc.coverage).toHaveBeenCalledWith({ teamId: 7, type: undefined })
    expect(noc.querySelector('[data-noc="level"]')).toHaveAttribute('data-ok', 'true')
    expect(noc.querySelector('[data-noc="types"]')).toHaveTextContent('Switched off for 24/7: Ping')
    expect(noc.querySelector('[data-noc="coverage"]')).toHaveTextContent('0 of 2 monitors')
    expect(screen.getByText(/decided monitor by monitor/)).toBeInTheDocument()
  })

  it('24/7 note degrades to text + link when the coverage summary is unavailable', async () => {
    api.noc.coverage.mockResolvedValue({ success: false, error: 'forbidden' })
    await renderWith(base())
    expect(await screen.findByText('The 24/7 status couldn’t be loaded.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Open 24/7 Coverage' })).toBeInTheDocument()
  })
})

describe('whoNotifiedModel — push view', () => {
  it('effective decision: a channel block turns RECIPIENT into the block code; personal reasons are kept', () => {
    const v = buildView(base({ push_channel: { ...OPEN_CHANNEL, block_reason: 'SKIPPED_QUIET_HOURS' } }))
    expect(v.push.rows.map((r) => r.effective)).toEqual(['SKIPPED_QUIET_HOURS', 'NO_ORG_ROLE', 'NO_GROUP', 'BELOW_MIN_LEVEL', 'SKIPPED_USER_OPT_OUT', 'MISSING_MEMBERSHIP'])
    expect(v.push.rows[0]).toMatchObject({ decision: 'RECIPIENT', receives: false, blockedByChannel: true })
    expect(v.counts).toMatchObject({ push: 0, pushNot: 6 })
    expect(v.excluded.find((x) => x.name === 'Kişi A').reason).toBe('SKIPPED_QUIET_HOURS')
  })

  it('access falls back sensibly for an older server and unknown block codes are ignored', () => {
    expect(buildView(base({ push_access: undefined })).push.access).toBe('FULL')
    expect(buildView(base({ push_access: undefined, push: undefined })).push.access).toBe('NONE')
    expect(buildView(base({ push_channel: { ...OPEN_CHANNEL, block_reason: 'WHATEVER' } })).push.channel.block).toBeNull()
    expect(buildView(base({ push_channel: undefined })).push.channel).toBeNull()
    expect(buildView(base({ push_viewer: 'KISIB' })).push.rows.find((r) => r.isSelf).name).toBe('Kişi B')
  })

  it('filterPushRows: yes/no/all + case-insensitive search on name and username', () => {
    const rows = buildView(base()).push.rows
    expect(filterPushRows(rows, 'yes', '').map((r) => r.username)).toEqual(['kisia'])
    expect(filterPushRows(rows, 'no', '')).toHaveLength(5)
    expect(filterPushRows(rows, 'all', 'kişi c').map((r) => r.username)).toEqual(['kisic'])
    expect(filterPushRows(rows, 'all', 'KISID').map((r) => r.username)).toEqual(['kisid'])
  })
})
