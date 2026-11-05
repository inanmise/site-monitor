import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, act, waitFor, within } from './test-utils.jsx'
import { EN } from '../i18n/en.js'
import { TR } from '../i18n/tr.js'
import { eventLabel } from '../components/admin/audit/auditFormat.js'

/**
 * Alarm iletimi bağlantıları (2026-11-05): Yönetim sekmesi `alerts.forwards` görüntüleme izniyle görünür (adminOnly değil),
 * izin yoksa ne şeritte ne URL'den açılır; takım silme etki penceresinde iletim bilgi satırları (silmeyi engellemez);
 * denetim etiketleri "Alarm oluşturuldu" kuralına düşmez.
 */
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
const perm = vi.hoisted(() => ({ view: true, loaded: true }))
vi.mock('../api/client', () => ({
  api: withApiFallback({
    admin: { getTeams: vi.fn().mockResolvedValue({ success: true, data: [] }), teamImpact: vi.fn(), teamMove: vi.fn(), deleteTeam: vi.fn() },
    alertForwards: { list: vi.fn() },
  }),
  formatDate: (s) => String(s ?? ''),
  formatDateOnly: (s) => String(s ?? ''),
  formatDateSec: (s) => String(s ?? ''),
}))
vi.mock('../contexts/PermissionsProvider.jsx', () => ({
  usePermissions: () => ({
    perms: {}, scope: null, loaded: perm.loaded, failed: false, refresh: () => {},
    canView: (r) => (r === 'alerts.forwards' ? perm.view : false),
    canEdit: () => false, canExecute: () => false,
  }),
  PermissionsProvider: ({ children }) => children,
}))
vi.mock('../components/admin/EscalationContacts.jsx', () => ({ default: () => <div data-testid="escalation-contacts" /> }))
vi.mock('../components/admin/RecipientSimulator.jsx', () => ({ default: () => <div /> }))
vi.mock('../components/admin/AlertThresholds.jsx', () => ({ default: () => <div /> }))
vi.mock('../components/admin/TeamManager.jsx', () => ({ default: () => <div /> }))
vi.mock('../components/admin/UserManager.jsx', () => ({ default: () => <div /> }))
vi.mock('../components/admin/forwards/AlertForwardManager.jsx', () => ({ default: () => <div data-testid="forward-manager" /> }))
import { api } from '../api/client'
import AdminPanel from '../components/admin/AdminPanel.jsx'
import TeamDeleteImpactModal, { movedSummary } from '../components/admin/TeamDeleteImpactModal.jsx'

beforeEach(() => {
  vi.clearAllMocks()
  perm.view = true
  perm.loaded = true
  window.history.replaceState({}, '', '/?tab=admin')
})

describe('Yönetim → Alarm iletimi sekmesi', () => {
  it('izin varken "Bildirim & Alarmlar" grubunda Bildirim Grupları\'ndan sonra; USER da görür', async () => {
    await act(async () => { render(<AdminPanel systemRole="USER" />) })
    const names = within(screen.getByRole('tablist')).getAllByRole('tab').map((x) => x.textContent)
    expect(names.indexOf(EN['fwd.tab'])).toBe(names.indexOf(EN['admin.tabNotifyGroups']) + 1)
  })

  it('URL g_tab=forwards açılır; izin yoksa sekme yok ve URL varsayılana iner', async () => {
    window.history.replaceState({}, '', '/?tab=admin&g_tab=forwards')
    const { unmount } = render(<AdminPanel systemRole="ADMIN" />)
    expect(await screen.findByTestId('forward-manager')).toBeInTheDocument()
    unmount()
    perm.view = false
    await act(async () => { render(<AdminPanel systemRole="ADMIN" />) })
    expect(screen.queryByRole('tab', { name: EN['fwd.tab'] })).toBeNull()
    expect(screen.queryByTestId('forward-manager')).toBeNull()
  })

  it('izin snapshot\'ı henüz gelmediyse URL\'deki sekme reddedilmez; gelince izin yoksa varsayılana iner', async () => {
    window.history.replaceState({}, '', '/?tab=admin&g_tab=forwards')
    perm.view = false
    perm.loaded = false
    const { rerender } = render(<AdminPanel systemRole="USER" />)
    expect(await screen.findByTestId('forward-manager')).toBeInTheDocument()
    perm.loaded = true
    await act(async () => { rerender(<AdminPanel systemRole="USER" />) })
    await waitFor(() => expect(screen.queryByTestId('forward-manager')).toBeNull())
    expect(screen.getByTestId('escalation-contacts')).toBeInTheDocument()
  })
})

describe('Takım silme etkisi — alarm iletimi', () => {
  const base = { open_alerts: 0, domains: { count: 0, items: [] }, monitors: { count: 0, items: [] }, users: { count: 0, items: [] },
    contacts: { count: 0, items: [] }, groups: { count: 0, items: [] } }
  const TEAM = { id: 7, name: 'Takım A' }

  it('boş takım: kendi iletimleri takımla silinir; gelenler kaldırılır — silmeyi engellemez', async () => {
    api.admin.teamImpact.mockResolvedValue({ success: true, data: { ...base, empty: true, alert_forwards: { owned: 3, incoming: 1 } } })
    render(<TeamDeleteImpactModal team={TEAM} teams={[TEAM]} onClose={() => {}} onDeleted={() => {}} />)
    const owned = await waitFor(() => { const e = document.querySelector('[data-slot="team-impact-fwd-owned"]'); expect(e).not.toBeNull(); return e })
    expect(owned).toHaveTextContent(EN['fwd.impact.ownedDeleted'].replace('{0}', '3'))
    expect(document.querySelector('[data-slot="team-impact-fwd-incoming"]')).toHaveTextContent(EN['fwd.impact.incoming.one'].replace('{0}', '1'))
    expect(screen.getByRole('button', { name: /^Sil$|^Delete$/ })).not.toBeDisabled()
  })

  it('dolu takım: kendi iletimleri "Taşı" ile hedefe geçer; eski sunucu (alan yok) → satır yok', async () => {
    api.admin.teamImpact.mockResolvedValueOnce({ success: true, data: { ...base, empty: false, monitors: { count: 1, items: ['x'] },
      alert_forwards: { owned: 1, incoming: 0 } } })
    const { unmount } = render(<TeamDeleteImpactModal team={TEAM} teams={[TEAM, { id: 9, name: 'B' }]} onClose={() => {}} onDeleted={() => {}} />)
    const owned = await waitFor(() => { const e = document.querySelector('[data-slot="team-impact-fwd-owned"]'); expect(e).not.toBeNull(); return e })
    expect(owned).toHaveTextContent(EN['fwd.impact.ownedMoved.one'].replace('{0}', '1'))
    expect(document.querySelector('[data-slot="team-impact-fwd-incoming"]')).toBeNull()
    unmount()
    api.admin.teamImpact.mockResolvedValueOnce({ success: true, data: { ...base, empty: true } })
    render(<TeamDeleteImpactModal team={TEAM} teams={[TEAM]} onClose={() => {}} onDeleted={() => {}} />)
    await screen.findByRole('button', { name: /^Sil$|^Delete$/ })
    expect(document.querySelector('[data-slot="team-impact-fwd-owned"]')).toBeNull()
    expect(document.querySelector('[data-slot="team-impact-fwd-paused"]')).toBeNull()
  })

  it('dolu takım: "tüm izlemeler / seçilen türler" kapsamlı iletimler taşınınca duraklatılır — uyarı satırı sayıyla', async () => {
    api.admin.teamImpact.mockResolvedValueOnce({ success: true, data: { ...base, empty: false, monitors: { count: 1, items: ['x'] },
      alert_forwards: { owned: 3, incoming: 0, paused_on_move: 2 } } })
    const { unmount } = render(<TeamDeleteImpactModal team={TEAM} teams={[TEAM, { id: 9, name: 'B' }]} onClose={() => {}} onDeleted={() => {}} />)
    const paused = await waitFor(() => { const e = document.querySelector('[data-slot="team-impact-fwd-paused"]'); expect(e).not.toBeNull(); return e })
    expect(paused).toHaveTextContent(EN['fwd.impact.pausedOnMove'].replace('{0}', '2'))
    unmount()
    // Tek iletim → tekil metin
    api.admin.teamImpact.mockResolvedValueOnce({ success: true, data: { ...base, empty: false, monitors: { count: 1, items: ['x'] },
      alert_forwards: { owned: 1, incoming: 0, paused_on_move: 1 } } })
    const second = render(<TeamDeleteImpactModal team={TEAM} teams={[TEAM, { id: 9, name: 'B' }]} onClose={() => {}} onDeleted={() => {}} />)
    await waitFor(() => expect(document.querySelector('[data-slot="team-impact-fwd-paused"]'))
      .toHaveTextContent(EN['fwd.impact.pausedOnMove.one'].replace('{0}', '1')))
    second.unmount()
    // Sıfır / eski sunucu (alan yok) / boş takım (taşıma yok — iletimler takımla silinir) → satır yok
    for (const data of [{ empty: false, alert_forwards: { owned: 1, incoming: 0, paused_on_move: 0 } },
      { empty: false, alert_forwards: { owned: 1, incoming: 0 } },
      { empty: true, alert_forwards: { owned: 2, incoming: 0, paused_on_move: 2 } }]) {
      api.admin.teamImpact.mockResolvedValueOnce({ success: true, data: { ...base, monitors: { count: data.empty ? 0 : 1, items: ['x'] }, ...data } })
      const r = render(<TeamDeleteImpactModal team={TEAM} teams={[TEAM, { id: 9, name: 'B' }]} onClose={() => {}} onDeleted={() => {}} />)
      await waitFor(() => expect(document.querySelector('[data-slot="team-impact-fwd-owned"]')).not.toBeNull())
      expect(document.querySelector('[data-slot="team-impact-fwd-paused"]')).toBeNull()
      r.unmount()
    }
  })
})

describe('takım taşıma özeti — alarm iletimi', () => {
  it('taşınan ve hedef takıma yönelik olduğu için silinen iletimler sayılır; sıfır yazılmaz', () => {
    const tOf = (d) => (k, ...a) => String(d[k] ?? k).replace(/\{(\d)\}/g, (_, i) => String(a[Number(i)]))
    expect(movedSummary({ monitors: 2, alert_forwards: 3, alert_forwards_removed: 1 }, tOf(EN)))
      .toBe(`Moved: 2 monitors, ${EN['team.mv.alert_forwards'].replace('{0}', '3')}, ${EN['team.mv.alert_forwards_removed'].replace('{0}', '1')}`)
    // Taşınınca duraklatılanlar ayrıca sayılır ("kapsamı gözden geçirin")
    expect(movedSummary({ alert_forwards: 3, alert_forwards_paused: 2, alert_forwards_removed: 1 }, tOf(TR)))
      .toBe(`Taşındı: ${TR['team.mv.alert_forwards'].replace('{0}', '3')}, ${TR['team.mv.alert_forwards_paused'].replace('{0}', '2')}, `
        + `${TR['team.mv.alert_forwards_removed'].replace('{0}', '1')}`)
    expect(TR['team.mv.alert_forwards_removed']).toContain('hedef takıma giden')
    expect(movedSummary({ alert_forwards: 0, alert_forwards_removed: 0 }, tOf(TR))).toBe(TR['team.movedNothing'])
  })
})

describe('denetim etiketleri', () => {
  it('ALERT_FORWARD_* kendi etiketini taşır (iki dil); "alarm oluşturuldu" kuralına düşmez', () => {
    const tOf = (d) => (k) => d[k] ?? k
    for (const ty of ['ALERT_FORWARD_CREATE', 'ALERT_FORWARD_UPDATE', 'ALERT_FORWARD_DELETE']) {
      expect(eventLabel(ty, tOf(TR))).toBe(TR[`audit.ev.${ty}`])
      expect(eventLabel(ty, tOf(EN))).toBe(EN[`audit.ev.${ty}`])
    }
    expect(eventLabel('ALERT_FORWARD_CREATE', tOf(EN))).toMatch(/forwarding/i)
  })
})
