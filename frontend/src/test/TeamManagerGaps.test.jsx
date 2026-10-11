import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'
import { pressMenuTrigger } from './helpers/dropdownMenu.js'
import { EN } from '../i18n/en.js'

/**
 * Takımlar sayfası (2026-10-11, kullanıcı isteği: "e-posta adresi olmayan takım, takım lideri olmayan takım gibi takımda
 * eksik olanların filtrelenebileceği bir süzgeç yok … tablo araları çok açık, menüler net belli değil, sayfaya sığmıyor"):
 * eksik bilgi süzgeci (sayılar, any/all, URL), özet kartları, satır eksik rozeti + düzeltme kısayolları, görünür
 * "İşlemler" menüsü, kap genişliğine göre kart görünümü, sıralama, CSV eksik sütunu.
 */
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
const { confirmMock, width, csv } = vi.hoisted(() => ({ confirmMock: vi.fn(), width: { value: 0 }, csv: { rows: null } }))
vi.mock('../api/client', () => ({
  api: withApiFallback({ admin: { getTeams: vi.fn(), getUsers: vi.fn(), getTeamUsers: vi.fn(), teamStats: vi.fn(), bulkTeams: vi.fn(), updateTeam: vi.fn(), createTeam: vi.fn(), updateTeamWeeklyNotifications: vi.fn() } }),
}))
vi.mock('../components/ui/Dialog.jsx', () => ({ useDialog: () => ({ showConfirm: confirmMock }), DialogProvider: ({ children }) => children }))
vi.mock('../components/admin/AdminChangeHistory.jsx', () => ({ default: () => null }))
vi.mock('../hooks/useElementWidth.js', () => ({
  useElementWidthState: () => [width.value, () => {}],
  useElementWidth: () => [() => {}, width.value],
}))
vi.mock('../utils/csvExport.js', async (orig) => {
  const real = await orig()
  return { ...real, downloadCsv: (_name, text) => { csv.rows = text } }
})
import { api } from '../api/client'
import TeamManager from '../components/admin/TeamManager.jsx'

const USERS = [
  { id: 7, username: 'lead', display_name: 'Lider Bir', employee_id: '100007', org_role: 'TECH', team_id: 1, active: true },
  { id: 8, username: 'mgr', display_name: 'Müdür Bir', employee_id: '100008', org_role: 'MANAGER', active: true },
]
const TEAMS = [
  { id: 1, name: 'Payments', active: true, leader_id: 7, email: 'p@example.com', manager_id: 8, description: 'Ödemeler' },
  { id: 2, name: 'Ledger', active: true, leader_id: null, email: '', manager_id: null, description: '' },
  { id: 3, name: 'Archive', active: false, leader_id: 7, email: 'a@example.com', manager_id: 8, description: 'Arşiv' },
]
const STATS = {
  1: { members: 2, members_inactive: 0, domains: 1, monitors: 1, contacts: 1, groups: 1, open_alerts: 3 },
  2: { members: 1, members_inactive: 1, domains: 0, monitors: 0, contacts: 0, groups: 0, open_alerts: 0 },
  3: { members: 2, members_inactive: 0, domains: 0, monitors: 2, contacts: 1, groups: 0, open_alerts: 0 },
}

const rows = () => [...document.querySelectorAll('[data-slot="table-body"] > tr')].filter((r) => r.cells.length > 1)
const rowNames = () => rows().map((r) => r.querySelector('[data-slot="team-badge"]')?.textContent.trim())
const chip = (key) => document.querySelector(`[data-slot="team-gap-chip"][data-gap="${key}"]`)
const kpi = (key) => document.querySelector(`[data-slot="stat-item"][data-key="${key}"]`)
const rowOf = (name) => rows().find((r) => r.textContent.includes(name))
const url = () => new URL(window.location.href).searchParams

async function mount(props = {}) {
  render(<TeamManager systemRole="ADMIN" onTeamsChange={() => {}} {...props} />)
  await screen.findByText('Payments')
  await waitFor(() => expect(chip('groups')?.getAttribute('data-count')).toBe('2'))
}

describe('TeamManager — eksik bilgi süzgeci (2026-10-11)', () => {
  beforeEach(() => {
    vi.clearAllMocks(); width.value = 0; csv.rows = null
    window.history.replaceState({}, '', '/?tab=admin&g_tab=teams')
    try { localStorage.clear() } catch { /* yok */ }
    api.admin.getUsers.mockResolvedValue({ success: true, data: USERS })
    api.admin.getTeams.mockResolvedValue({ success: true, data: TEAMS })
    api.admin.teamStats.mockResolvedValue({ success: true, data: STATS })
    api.admin.getTeamUsers.mockResolvedValue({ success: true, data: [] })
    confirmMock.mockResolvedValue(true)
  })

  it('her eksik bir çip: sayısı o eksikle kaç takım olduğunu gösterir', async () => {
    await mount()
    const counts = Object.fromEntries([...document.querySelectorAll('[data-slot="team-gap-chip"]')]
      .map((c) => [c.getAttribute('data-gap'), Number(c.getAttribute('data-count'))]))
    expect(counts).toEqual({ email: 1, leader: 1, members: 1, contacts: 1, manager: 1, inactive_members: 1, groups: 2, description: 1, assets: 1 })
    expect(chip('email')).toHaveTextContent(EN['team.gap.email.label'])
    expect(chip('email').getAttribute('aria-pressed')).toBe('false')
  })

  it('çip seçilince yalnız o eksiği olan takımlar; ikinci çip "herhangi biri" ile birleşir, "hepsi" kesişimi alır; URL g_miss/g_mmode', async () => {
    await mount()
    fireEvent.click(chip('groups'))
    await waitFor(() => expect(rowNames()).toEqual(['Ledger', 'Archive']))
    expect(chip('groups').getAttribute('aria-pressed')).toBe('true')
    fireEvent.click(chip('email'))
    await waitFor(() => expect(rowNames()).toEqual(['Ledger', 'Archive']))   // any: e-posta (Ledger) ∪ grup (Ledger, Archive)
    fireEvent.click(screen.getByRole('radio', { name: EN['team.gap.modeAll'] }))
    await waitFor(() => expect(rowNames()).toEqual(['Ledger']))
    await waitFor(() => expect(url().get('g_miss')).toBe('email,groups'))
    expect(url().get('g_mmode')).toBe('all')
    // seçilen çipin sayısı kendi süzgecinden etkilenmez
    expect(chip('groups').getAttribute('data-count')).toBe('2')
    fireEvent.click(screen.getByRole('button', { name: EN['team.gap.clear'] }))
    await waitFor(() => expect(rows()).toHaveLength(3))
    await waitFor(() => expect(url().get('g_miss')).toBeNull())
  })

  it('URL\'deki g_miss sayfa açılışında uygulanır; "Süzgeçleri temizle" eksik seçimini de kaldırır', async () => {
    window.history.replaceState({}, '', '/?tab=admin&g_tab=teams&g_miss=leader,bogus')
    render(<TeamManager systemRole="ADMIN" onTeamsChange={() => {}} />)
    await screen.findByText('Ledger')
    await waitFor(() => expect(rowNames()).toEqual(['Ledger']))
    expect(chip('leader').getAttribute('aria-pressed')).toBe('true')
    fireEvent.click(screen.getByRole('button', { name: EN['inv.filterClear'] }))
    await waitFor(() => expect(rows()).toHaveLength(3))
    expect(chip('leader').getAttribute('aria-pressed')).toBe('false')
  })

  it('eşleşme yoksa açıklayıcı boş durum', async () => {
    window.history.replaceState({}, '', '/?tab=admin&g_tab=teams&g_miss=email,assets,leader&g_mmode=all')
    api.admin.getTeams.mockResolvedValue({ success: true, data: TEAMS.filter((tm) => tm.id !== 2) })
    render(<TeamManager systemRole="ADMIN" onTeamsChange={() => {}} />)
    await screen.findByText(EN['team.gap.empty'])
  })

  it('özet kartları: toplam / aktif / pasif / eksik bilgisi olan / alarmdaki — tıklanınca süzer, tekrar tıklayınca kaldırır', async () => {
    await mount()
    const val = (k) => kpi(k).querySelector('[data-slot="stat-value"]').textContent
    expect(val('total')).toBe('3')
    expect(val('active')).toBe('2')
    expect(val('inactive')).toBe('1')
    expect(val('gaps')).toBe('1')     // Archive yalnız düşük önemli (grup) eksik → sayılmaz
    expect(val('alerts')).toBe('1')
    fireEvent.click(kpi('gaps'))
    await waitFor(() => expect(rowNames()).toEqual(['Ledger']))
    expect(chip('email').getAttribute('aria-pressed')).toBe('true')
    expect(chip('groups').getAttribute('aria-pressed')).toBe('false')
    expect(kpi('gaps').getAttribute('aria-pressed')).toBe('true')
    fireEvent.click(kpi('gaps'))
    await waitFor(() => expect(rows()).toHaveLength(3))
    fireEvent.click(kpi('inactive'))
    await waitFor(() => expect(rowNames()).toEqual(['Archive']))
    fireEvent.click(kpi('alerts'))
    await waitFor(() => expect(rows()).toHaveLength(0))   // pasif + alarmlı: yok
    fireEvent.click(kpi('total'))
    await waitFor(() => expect(rows()).toHaveLength(3))
  })

  it('satır rozeti: eksiksiz takım "Complete"; eksikli takım "N missing" → pencere nedenleri ve düzeltme kısayollarını listeler', async () => {
    const onJump = vi.fn()
    await mount({ onJump })
    expect(within(rowOf('Payments')).getByText(EN['team.gap.none'])).toBeInTheDocument()
    const badge = rowOf('Ledger').querySelector('button[data-slot="team-gap-badge"]')
    expect(badge).toHaveTextContent('9 missing')
    expect(badge.getAttribute('data-severity')).toBe('high')
    expect(badge.getAttribute('aria-label')).toContain('Ledger')
    fireEvent.click(badge)
    const pop = await screen.findByRole('dialog')
    expect(pop.querySelectorAll('li[data-gap]')).toHaveLength(9)
    expect(within(pop).getByText(EN['team.gap.contacts.why'])).toBeInTheDocument()
    fireEvent.click(within(pop).getByRole('button', { name: new RegExp(EN['team.gap.contacts.fix']) }))
    expect(onJump).toHaveBeenCalledWith('contacts', { g_team: '2' })
    // düzenleme kısayolu takım penceresini açar
    fireEvent.click(rowOf('Ledger').querySelector('button[data-slot="team-gap-badge"]'))
    const pop2 = await screen.findByRole('dialog')
    fireEvent.click(within(pop2.querySelector('li[data-gap="email"]')).getByRole('button'))
    expect(await screen.findByText(EN['team.editTitle'])).toBeInTheDocument()
  })

  it('düzenleme yetkisi yoksa düzenleme kısayolu çıkmaz; görüntüleme / sekme kısayolları kalır', async () => {
    render(<TeamManager systemRole="USER" ownTeamId={1} myTeamIds={[1]} onTeamsChange={() => {}} onJump={() => {}} />)
    await screen.findByText('Ledger')
    await waitFor(() => expect(chip('groups')?.getAttribute('data-count')).toBe('2'))
    fireEvent.click(rowOf('Ledger').querySelector('button[data-slot="team-gap-badge"]'))
    const pop = await screen.findByRole('dialog')
    expect(pop.querySelector('li[data-gap="email"] [data-slot="team-gap-fix"]')).toBeNull()
    expect(pop.querySelector('li[data-gap="members"] [data-slot="team-gap-fix"]')).toBeNull()
    expect(pop.querySelector('li[data-gap="inactive_members"] [data-slot="team-gap-fix"]')).not.toBeNull()
    expect(pop.querySelector('li[data-gap="contacts"] [data-slot="team-gap-fix"]')).not.toBeNull()
  })

  it('sayaçlar gelmeden sayaca dayalı eksikler "…" ve rozet bunu söyler; e-posta / lider hemen bilinir', async () => {
    api.admin.teamStats.mockReturnValue(new Promise(() => {}))
    render(<TeamManager systemRole="ADMIN" onTeamsChange={() => {}} />)
    await screen.findByText('Ledger')
    await waitFor(() => expect(chip('leader').getAttribute('data-count')).toBe('1'))
    expect(chip('contacts').getAttribute('data-count')).toBeNull()
    expect(chip('contacts')).toHaveTextContent('…')
    expect(kpi('alerts').querySelector('[data-slot="stat-value"]').textContent).toBe('…')
    fireEvent.click(rowOf('Ledger').querySelector('button[data-slot="team-gap-badge"]'))
    const pop = await screen.findByRole('dialog')
    expect(pop.querySelector('li[data-gap="contacts"]')).toBeNull()
    expect(within(pop).getByText(EN['team.gap.statsPending'])).toBeInTheDocument()
  })

  it('İşlemler menüsü görünür etiketli; erişilebilir ad satırı söyler; yeni kısayollar sekmeye süzgeçle geçer', async () => {
    const onJump = vi.fn()
    await mount({ onJump })
    const trigger = screen.getByRole('button', { name: `Payments — ${EN['team.colActions']}` })
    expect(trigger).toHaveTextContent(EN['team.colActions'])
    expect(trigger.getAttribute('data-kebab')).toBe('labeled')
    pressMenuTrigger(trigger)
    fireEvent.click(await screen.findByRole('menuitem', { name: EN['team.menuGroups'] }))
    expect(onJump).toHaveBeenCalledWith('notifyGroups', { g_team: '1' })
  })

  it('sıralama: en çok eksiği olan önce → URL g_sort; varsayılan sunucu sırası', async () => {
    await mount()
    expect(rowNames()).toEqual(['Payments', 'Ledger', 'Archive'])
    fireEvent.mouseDown(screen.getByLabelText(EN['team.sortTeams']))
    fireEvent.mouseDown((await screen.findAllByText(EN['team.sortBy.gaps'])).find((el) => el.closest('[role="option"]')))
    await waitFor(() => expect(rowNames()).toEqual(['Ledger', 'Archive', 'Payments']))
    await waitFor(() => expect(url().get('g_sort')).toBe('gaps'))
  })

  it('CSV sonda eksik bilgiler sütununu taşır', async () => {
    await mount()
    fireEvent.click(screen.getByRole('button', { name: EN['team.exportCsv'] }))
    expect(csv.rows).toContain(EN['team.csvGaps'])
    expect(csv.rows).toContain(`${EN['team.gap.email.label']}; ${EN['team.gap.leader.label']}`)
  })

  it('sorumlular: lider ve müdür ad-soyadının yanında sicil; atanmamışta sicil yok', async () => {
    await mount()
    const sicils = (name) => [...rowOf(name).querySelectorAll('[data-slot="team-owners"] [data-slot="team-owner-sicil"]')].map((e) => e.textContent)
    expect(sicils('Payments')).toEqual([EN['team.sicil'].replace('{0}', '100007'), EN['team.sicil'].replace('{0}', '100008')])
    const owners = rowOf('Payments').querySelector('[data-slot="team-owners"]')
    expect(owners.textContent).toContain('Lider Bir')
    expect(owners.textContent).toContain('Müdür Bir')
    expect(sicils('Ledger')).toEqual([])
  })

  it('müdür yalnız sicilden türetildiyse (kullanıcı kaydı yok) sicil ikinci kez yazılmaz', async () => {
    api.admin.getUsers.mockResolvedValue({ success: true, data: [
      { id: 20, username: 'uye', display_name: 'Üye Bir', employee_id: '100020', org_role: 'TECH', team_id: 5, manager_sicil: 'X9999', active: true },
    ] })
    api.admin.getTeams.mockResolvedValue({ success: true, data: [{ id: 5, name: 'Zeta', active: true, leader_id: null, manager_id: null, email: 'z@example.com' }] })
    api.admin.teamStats.mockResolvedValue({ success: true, data: {} })
    render(<TeamManager systemRole="ADMIN" onTeamsChange={() => {}} />)
    await screen.findByText('Zeta')
    await waitFor(() => expect(rowOf('Zeta').querySelector('[data-slot="team-owners"]').textContent).toContain('X9999'))
    expect(rowOf('Zeta').querySelectorAll('[data-slot="team-owner-sicil"]')).toHaveLength(0)
  })

  it('dar kapta (< 900 px) kartlar: tablo yok, her takım bir kart, seçim ve menü kartta', async () => {
    width.value = 600
    render(<TeamManager systemRole="ADMIN" onTeamsChange={() => {}} />)
    await screen.findByText('Payments')
    const list = document.querySelector('[data-slot="tm-list"]')
    expect(list.getAttribute('data-view')).toBe('cards')
    expect(screen.queryByRole('table')).toBeNull()
    const cards = [...document.querySelectorAll('[data-slot="team-card"]')]
    expect(cards).toHaveLength(3)
    expect(within(cards[0]).getByRole('button', { name: `Payments — ${EN['team.colActions']}` })).toBeInTheDocument()
    expect(within(cards[0]).getAllByRole('switch')).toHaveLength(2)
    fireEvent.click(screen.getByLabelText(EN['team.selectAll']))
    expect(await screen.findByTestId('tm-bulk-bar')).toBeInTheDocument()
    expect(cards.every((c) => c.getAttribute('data-state') === 'selected')).toBe(true)
  })

  it('geniş kapta (≥ 900 px) sıkı sabit düzenli tablo; ad rozeti kesilmez (truncate yok)', async () => {
    width.value = 1100
    await mount()
    expect(document.querySelector('[data-slot="tm-list"]').getAttribute('data-view')).toBe('table')
    expect(screen.getByRole('table').className).toContain('table-fixed')
    const nameSpan = rowOf('Payments').querySelector('[data-slot="team-badge"] span')
    expect(nameSpan.className).not.toContain('truncate')
    expect(nameSpan.className).toContain('[overflow-wrap:anywhere]')
  })
})
