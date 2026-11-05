import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, within, act } from './test-utils.jsx'
import { EN } from '../i18n/en.js'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDateSec: (s) => s ?? '',
  api: withApiFallback({ noc: { coverage: vi.fn() } }),
}))
// "Yönet" düğmesi Alarm iletimi sekmesinin izniyle (`alerts.forwards` görme) çizilir — izin testte değiştirilir
const perm = vi.hoisted(() => ({ forwardsView: true }))
vi.mock('../contexts/PermissionsProvider.jsx', () => ({
  usePermissions: () => ({
    perms: {}, scope: null, loaded: true, failed: false, refresh: () => {},
    canView: (r) => (r === 'alerts.forwards' ? perm.forwardsView : false),
    canEdit: () => false, canExecute: () => false,
  }),
  PermissionsProvider: ({ children }) => children,
}))
import SimResult from '../components/admin/whonotified/SimResult.jsx'

/**
 * "Kim bilgilendirilir?" — İLETİM ALICILARI (2026-11-05). Simülasyon yanıtının `forwards` listesi kendi kartında: alıcı
 * takım, "Tüm takım" / üye sayısı, kapsam ("bu türün tüm izlemeleri" / "yalnız seçilen izlemeler" + not), adresler
 * (kesilmez), adressiz alıcı "yalnız push". Alan yoksa ya da liste boşsa kart yok.
 */
const LONG_TEAM = 'Dijital Kanallar Operasyon ve Uygulama Destek Ekibi (Ankara / Bölge Müdürlüğü)'
const BASE = {
  team_id: 7, team_name: 'Takım A', level: 'HIGH', managers_included: true, team_contacts_missing: false,
  team_emails: [{ email: 'takim-a@example.com', team: 'Takım A', source: 'Takım maili', kind: 'TEAM' }],
  contacts: [], webhooks: [], push: [], push_access: 'NONE', email_total: 1,
}
const FORWARDS = [
  { recipient_team_id: 9, recipient_team_name: LONG_TEAM, recipient_kind: 'TEAM', applies: 'all', scope_kind: 'ALL',
    emails: ['ops@example.com', 'ops-yedek@example.com'], member_count: 0 },
  { recipient_team_id: 11, recipient_team_name: 'Ağ Ekibi', recipient_kind: 'MEMBERS', applies: 'monitors_only', scope_kind: 'MONITORS',
    emails: ['ayse.yilmaz@example.com', 'OPS@example.com'], member_count: 2 },
  { recipient_team_id: 12, recipient_team_name: 'Güvenlik Ekibi', recipient_kind: 'TEAM', applies: 'all', scope_kind: 'TYPES',
    emails: [], member_count: 0 },
]
const card = () => document.querySelector('[data-slot="wn-channel"][data-channel="forward"]')
/** SimResult + 7/24 notunun isteği (NocNote) yerleşene dek — act uyarısı olmasın. */
async function draw(ui) {
  const r = render(ui)
  await act(async () => {})
  return r
}

describe('SimResult — iletim alıcıları kartı', () => {
  beforeEach(() => { vi.clearAllMocks(); perm.forwardsView = true })

  it('her iletim satırı: takım, alıcı türü, kapsam, adresler; özet tekil adres sayısı', async () => {
    await draw(<SimResult data={{ ...BASE, forwards: FORWARDS }} level="HIGH" kind="MONITOR" />)
    const c = card()
    expect(c).toBeTruthy()
    expect(within(c).getByRole('heading', { name: new RegExp(EN['fwdx.sim.title']) })).toBeInTheDocument()
    const rows = [...c.querySelectorAll('[data-slot="wn-recipient"][data-source="forward"]')]
    expect(rows).toHaveLength(3)
    expect(rows[0]).toHaveTextContent(LONG_TEAM)
    expect(rows[0]).toHaveTextContent(EN['fwdx.recipient.team'])
    expect(rows[0]).toHaveTextContent(EN['fwdx.scope.all'])
    expect(rows[0]).toHaveTextContent('ops@example.com, ops-yedek@example.com')
    expect(rows[1]).toHaveAttribute('data-applies', 'monitors_only')
    expect(rows[1]).toHaveTextContent('2 members')
    expect(rows[1]).toHaveTextContent(EN['fwdx.sim.appliesMonitors'])
    expect(rows[1].querySelector('[data-slot="wn-forward-partly"]')).toHaveTextContent(EN['fwdx.sim.monitorsOnlyNote'])
    expect(rows[2]).toHaveTextContent(EN['fwdx.sim.appliesType'])
    expect(rows[2]).toHaveTextContent(EN['fwdx.sim.noTeamEmail'])
    // ops@ ve OPS@ aynı adres (büyük / küçük harf) → 3 tekil adres
    expect(c.querySelector('[data-slot="wn-forward-summary"]')).toHaveTextContent(EN['fwdx.sim.summary'].replace('{0}', '3'))
    expect(c.querySelector('.truncate')).toBeNull()
  })

  it('yönet düğmesi Alarm iletimi sekmesine takım süzgeciyle', async () => {
    const onNavigate = vi.fn()
    await draw(<SimResult data={{ ...BASE, forwards: FORWARDS }} level="HIGH" kind="MONITOR" onNavigate={onNavigate} />)
    fireEvent.click(screen.getByRole('button', { name: EN['fwdx.sim.manage'] }))
    expect(onNavigate).toHaveBeenCalledWith('forwards', { g_fteam: '7' })
  })

  it('Alarm iletimi sekmesini görme izni (alerts.forwards) yoksa "Yönet" düğmesi çizilmez — kart yine görünür', async () => {
    perm.forwardsView = false
    await draw(<SimResult data={{ ...BASE, forwards: FORWARDS }} level="HIGH" kind="MONITOR" onNavigate={vi.fn()} />)
    expect(card()).toBeTruthy()
    expect(card().querySelector('[data-action="wn-open-forwards"]')).toBeNull()
    expect(screen.queryByRole('button', { name: EN['fwdx.sim.manage'] })).toBeNull()
  })

  it('adresi olmayan üye iletimi "yalnız push"; tüm iletimler adressizse özet push der', async () => {
    await draw(<SimResult data={{ ...BASE, forwards: [{ ...FORWARDS[1], emails: [], applies: 'all', scope_kind: 'ALL' }] }} level="HIGH" kind="CERT" />)
    expect(card()).toHaveTextContent(EN['fwdx.sim.noMemberEmail'])
    expect(card().querySelector('[data-slot="wn-forward-summary"]')).toHaveTextContent(EN['fwdx.sim.summaryNone'])
    expect(card().querySelector('[data-slot="wn-forward-partly"]')).toBeNull()
  })

  it('alan yoksa (eski sunucu) ya da liste boşsa kart yok; yönetim düğmesi yalnız onNavigate ile', async () => {
    const { unmount } = await draw(<SimResult data={BASE} level="HIGH" kind="CERT" />)
    expect(card()).toBeNull()
    unmount()
    await draw(<SimResult data={{ ...BASE, forwards: [] }} level="HIGH" kind="CERT" />)
    expect(card()).toBeNull()
  })

  it('types_only: yalnız seçilen türler, tür adlarıyla (ham anahtar yok) + not; forward_id satır anahtarı', async () => {
    await draw(<SimResult data={{ ...BASE, forwards: [{ forward_id: 41, recipient_team_id: 9, recipient_team_name: LONG_TEAM,
      recipient_kind: 'TEAM', applies: 'types_only', scope_kind: 'TYPES', scope_types: ['http', 'cert', 'dbquery'],
      emails: ['ops@example.com'], member_count: 0 }] }} level="HIGH" kind="MONITOR" />)
    const row = card().querySelector('[data-slot="wn-recipient"][data-source="forward"]')
    expect(row).toHaveAttribute('data-applies', 'types_only')
    const applies = row.querySelector('[data-wn="applies"]')
    expect(applies).toHaveTextContent(EN['fwdx.sim.appliesTypes'].replace('{0}',
      `${EN['userpush.type.http']}, ${EN['fwd.type.cert']}, ${EN['userpush.type.dbquery']}`))
    expect(applies).toHaveAttribute('data-tone', 'warning')
    expect(row.querySelector('[data-slot="wn-forward-partly"]')).toHaveTextContent(EN['fwdx.sim.typesOnlyNote'])
    expect(row).not.toHaveTextContent('dbquery')
  })

  it('tek adres → tekil özet; onNavigate yoksa yönet düğmesi yok', async () => {
    await draw(<SimResult data={{ ...BASE, forwards: [{ ...FORWARDS[0], emails: ['ops@example.com'] }] }} level="HIGH" kind="CERT" />)
    expect(card().querySelector('[data-slot="wn-forward-summary"]')).toHaveTextContent(EN['fwdx.sim.summary.one'])
    expect(card().querySelector('[data-action="wn-open-forwards"]')).toBeNull()
  })
})
