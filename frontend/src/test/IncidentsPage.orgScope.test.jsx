import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, fireEvent, within } from './test-utils.jsx'
import { pressMenuTrigger } from './helpers/dropdownMenu.js'
import IncidentsPage from '../components/IncidentsPage.jsx'
import { PermissionsProvider } from '../contexts/PermissionsProvider.jsx'
import { PAGE_STATE_PARAMS, PAGE_STATE_PREFIXES } from '../hooks/useUrlQuerySync.js'

/**
 * Olaylar — org geneli SALT OKUNUR görünürlük (2026-09-28, kullanıcı kararı): "Takımımın olayları | Diğer ekiplerin
 * olayları | Tümü" süzgeci (varsayılan Takımımın = bugünkü görünüm; URL `scope`), başka ekibin olayında HİÇBİR eylem
 * denetimi yok (rozet + sahibi ekip + açıklama), kendi olayında eylemler yerinde; eylem izni olmayan (AUDIT) kendi
 * kapsamında da salt okunur. Satır bayrakları SUNUCUDAN (`can_manage` / `can_act` / `can_delete`).
 */
let MOBILE = false
vi.mock('../hooks/use-mobile.js', () => ({ useIsMobile: () => MOBILE }))

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  formatDateSec: (s) => s ?? '',
  api: withApiFallback({
    monitoring: { incidents: { list: vi.fn(), get: vi.fn(), comments: vi.fn(), addComment: vi.fn(), deleteComment: vi.fn(), remove: vi.fn() } },
    admin: { acknowledgeAlert: vi.fn(), resolveAlert: vi.fn(), getAlertNotifications: vi.fn() },
    nocCalls: { list: vi.fn() },
    me: { getPermissions: vi.fn() },
  }),
}))
import { api } from '../api/client'

const base = {
  status: 'ongoing', root_cause: { code: '503', category: 'server_error' }, comment_count: 1,
  alert_type: 'HTTP_DOWN', alert_level: 'CRITICAL', started_at: '2026-09-28T03:00:00', resolved_at: null, resolved_by: null,
  acknowledged: false, message: 'HTTP 503',
}
/** Kendi takımının olayı — USER: eylem var, silme yok. */
const own = {
  ...base, id: 1, domain: 'https://own.example.com', team_id: 5, team_name: 'Takım A',
  monitor: { name: 'Kendi sitesi', type: 'http', tab: 'http', monitor_id: 70 }, can_manage: true, can_act: true, can_delete: false,
}
/** Başka ekibin olayı — sunucu izleme kimliğini vermez. */
const foreign = {
  ...base, id: 2, domain: 'https://foreign.example.com', team_id: 9, team_name: 'Takım B',
  monitor: { name: 'Başka site', type: 'http', tab: 'http', monitor_id: null }, can_manage: false, can_act: false, can_delete: false,
}

const COUNTS = { mine: 4, others: 11, all: 15 }

/** Sayfa listesi + iki özet isteği; `scope` isteğe göre yanıt verir (sunucu sözleşmesi). */
function mockList({ rows = [own, foreign], counts = COUNTS, serverScope = null } = {}) {
  api.monitoring.incidents.list.mockImplementation((p = {}) => {
    const applied = serverScope ?? p.scope ?? 'mine'
    if (p.status === 'ongoing' && Number(p.size) === 200) return Promise.resolve({ success: true, data: rows.filter((r) => r.status === 'ongoing'), total: rows.length, scope: applied })
    if (p.status === 'resolved' && Number(p.size) === 1) return Promise.resolve({ success: true, data: [], total: 0, scope: applied })
    return Promise.resolve({ success: true, data: rows, total: rows.length, type_counts: { HTTP_DOWN: rows.length }, scope: applied,
      visible_to_all: counts != null, ...(counts ? { scope_counts: counts } : {}) })
  })
}
const pageCalls = () => api.monitoring.incidents.list.mock.calls.map((c) => c[0])
  .filter((p) => !(p?.status === 'ongoing' && Number(p?.size) === 200) && !(p?.status === 'resolved' && Number(p?.size) === 1))
const summaryCalls = () => api.monitoring.incidents.list.mock.calls.map((c) => c[0]).filter((p) => Number(p?.size) === 200 || Number(p?.size) === 1)
const scopeGroup = () => screen.findByRole('group', { name: /Incident scope|Olay kapsamı/ })
const card = (id) => document.querySelector(`[data-slot="incident-card"][data-incident-id="${id}"]`)
const openCard = async (name) => {
  fireEvent.click(await screen.findByRole('button', { name: new RegExp(`Open incident ${name}|${name} olayını aç`) }))
  return screen.findByRole('dialog', { name: /Incident #|Olay #/ })
}

beforeEach(() => {
  vi.clearAllMocks(); MOBILE = false; localStorage.clear()
  window.history.replaceState({}, '', '/?tab=incidents')
  api.admin.getAlertNotifications.mockResolvedValue({ success: true, data: [] })
  api.monitoring.incidents.comments.mockResolvedValue({ success: true, data: [
    { id: 31, author_name: 'Kişi B', author_username: 'kisib', body: 'veritabanı yeniden başlatıldı', created_at: '2026-09-28T03:05:00' },
  ] })
  api.nocCalls.list.mockResolvedValue({ success: true, data: [] })
})
afterEach(() => { window.history.replaceState({}, '', '/?tab=incidents') })

describe('kapsam süzgeci (Takımımın | Diğer ekiplerin | Tümü)', () => {
  it('varsayılan "Takımımın olayları": istekte scope YOK (bugünkü biçim), çiplerde sunucu sayıları, not yok', async () => {
    mockList()
    render(<IncidentsPage systemRole="USER" teamId={5} />)
    const group = await scopeGroup()
    const mine = within(group).getByRole('button', { name: /My teams' incidents|Takımımın olayları/ })
    expect(mine).toHaveAttribute('aria-pressed', 'true')
    expect(within(mine).getByText('4')).toBeInTheDocument()
    expect(within(group).getByRole('button', { name: /Other teams' incidents|Diğer ekiplerin olayları/ })).toHaveTextContent('11')
    expect(within(group).getByRole('button', { name: /^(All|Tümü)/ })).toHaveTextContent('15')
    expect(pageCalls().every((p) => !('scope' in p))).toBe(true)
    expect(document.querySelector('[data-slot="incident-scope-note"]')).toBeNull()
    expect(window.location.search).not.toContain('scope=')
  })

  it('"Diğer ekiplerin olayları" → sayfa + özet istekleri scope=others, sayfa 0; URL scope=others; salt okunur notu', async () => {
    mockList()
    render(<IncidentsPage systemRole="USER" teamId={5} />)
    fireEvent.click(within(await scopeGroup()).getByRole('button', { name: /Other teams' incidents|Diğer ekiplerin olayları/ }))
    await waitFor(() => expect(pageCalls().at(-1)).toEqual(expect.objectContaining({ scope: 'others', page: 0 })))
    await waitFor(() => expect(summaryCalls().filter((p) => p.scope === 'others')).toHaveLength(2))
    await waitFor(() => expect(new URLSearchParams(window.location.search).get('scope')).toBe('others'))
    await waitFor(() => expect(document.querySelector('[data-slot="incident-scope-note"]')?.textContent).toMatch(/read-only|salt okunur/))

    fireEvent.click(within(await scopeGroup()).getByRole('button', { name: /^(All|Tümü)/ }))
    await waitFor(() => expect(pageCalls().at(-1)).toEqual(expect.objectContaining({ scope: 'all' })))
    await waitFor(() => expect(new URLSearchParams(window.location.search).get('scope')).toBe('all'))

    fireEvent.click(within(await scopeGroup()).getByRole('button', { name: /My teams' incidents|Takımımın olayları/ }))
    await waitFor(() => expect(pageCalls().at(-1)).not.toHaveProperty('scope'))
    await waitFor(() => expect(window.location.search).not.toContain('scope='))
  })

  it('URL scope=others açılışta uygulanır; sunucu uygulamazsa (ayar kapalı) seçim mine\'a döner, süzgeç çizilmez', async () => {
    window.history.replaceState({}, '', '/?tab=incidents&scope=others')
    mockList({ counts: null, serverScope: 'mine' })
    render(<IncidentsPage systemRole="USER" teamId={5} />)
    await waitFor(() => expect(pageCalls()[0]).toEqual(expect.objectContaining({ scope: 'others' })))
    await waitFor(() => expect(pageCalls().at(-1)).not.toHaveProperty('scope'))
    await waitFor(() => expect(window.location.search).not.toContain('scope='))
    expect(screen.queryByRole('group', { name: /Incident scope|Olay kapsamı/ })).toBeNull()
  })

  it('sunucu scope_counts döndürmezse (global görüntüleyici / ayar kapalı) süzgeç YOK — liste bugünkü gibi', async () => {
    mockList({ rows: [own], counts: null })
    render(<IncidentsPage systemRole="AUDIT" />)
    await screen.findByText('Kendi sitesi')
    expect(screen.queryByRole('group', { name: /Incident scope|Olay kapsamı/ })).toBeNull()
  })

  it('URL ad alanı: sayfanın yazdığı her anahtar uygulamanın sekme-geçişi temizliğinde (PAGE_STATE_PARAMS) — scope dahil', () => {
    for (const k of ['scope', 'stat', 'type', 'q', 'from', 'to', 'level', 'team', 'view', 'sort', 'page', 'ps'])
      expect(PAGE_STATE_PARAMS.includes(k) || PAGE_STATE_PREFIXES.some((p) => k.startsWith(p))).toBe(true)
  })
})

describe('başka ekibin olayı — salt okunur, müdahale denetimi YOK', () => {
  it('pano kartında kilit rozeti; kendi kartında yok', async () => {
    mockList()
    render(<IncidentsPage systemRole="USER" teamId={5} />)
    await screen.findByText('Başka site')
    expect(card(2).querySelector('[data-slot="read-only-badge"]')).not.toBeNull()
    expect(card(2)).toHaveAttribute('data-foreign', 'true')
    expect(card(1).querySelector('[data-slot="read-only-badge"]')).toBeNull()
  })

  it('liste satırı: rozet + menüde yalnız "Detayı aç" (onayla/çöz/sil/izlemeyi aç YOK); kendi satırında onayla/çöz var', async () => {
    mockList()
    render(<IncidentsPage systemRole="ADMIN" teamId={5} />)   // rol ipucu ADMIN olsa da sunucu can_delete=false der
    await screen.findByText('Başka site')
    fireEvent.click(screen.getByRole('button', { name: /^(List|Liste)$/ }))
    const row = await waitFor(() => { const r = document.querySelector('tr[data-incident-id="2"]'); expect(r).not.toBeNull(); return r })
    expect(row.querySelector('[data-slot="read-only-badge"]')).not.toBeNull()
    pressMenuTrigger(within(row).getByRole('button', { name: /Başka site/ }))
    const menu = await screen.findByRole('menu')
    expect(within(menu).getByRole('menuitem', { name: /Open detail|Detaya git/ })).toBeInTheDocument()
    expect(within(menu).queryByRole('menuitem', { name: /^(Acknowledge|Onayla)$/ })).toBeNull()
    expect(within(menu).queryByRole('menuitem', { name: /^(Resolve|Çöz)$/ })).toBeNull()
    expect(within(menu).queryByRole('menuitem', { name: /^(Delete|Sil)$/ })).toBeNull()
    expect(within(menu).queryByRole('menuitem', { name: /Open monitor|İzlemeyi aç/ })).toBeNull()
    fireEvent.keyDown(menu, { key: 'Escape' })
    await waitFor(() => expect(screen.queryByRole('menu')).toBeNull())

    const mine = document.querySelector('tr[data-incident-id="1"]')
    expect(mine.querySelector('[data-slot="read-only-badge"]')).toBeNull()
    pressMenuTrigger(within(mine).getByRole('button', { name: /Kendi sitesi/ }))
    const menu2 = await screen.findByRole('menu')
    expect(within(menu2).getByRole('menuitem', { name: /^(Acknowledge|Onayla)$/ })).toBeInTheDocument()
    expect(within(menu2).getByRole('menuitem', { name: /^(Resolve|Çöz)$/ })).toBeInTheDocument()
    expect(within(menu2).getByRole('menuitem', { name: /Open monitor|İzlemeyi aç/ })).toBeInTheDocument()
    expect(within(menu2).queryByRole('menuitem', { name: /^(Delete|Sil)$/ })).toBeNull()   // can_delete=false (kapsamlı müdür tuzağı)
  })

  it('detay: rozet + sahibi ekip + açıklama; onayla/çöz/sil/izlemeyi aç, yorum yazıcı ve yorum silme YOK; alıcılar/arama kaydı İSTENMEZ', async () => {
    mockList()
    render(<IncidentsPage systemRole="USER" teamId={5} />)
    const dlg = await openCard('Başka site')
    const ro = dlg.querySelector('[data-slot="incident-read-only"]')
    expect(ro).not.toBeNull()
    expect(ro.querySelector('[data-slot="read-only-badge"] [data-slot="team-badge"]')).not.toBeNull()
    expect(within(ro).getByText(/belongs to another team|başka bir ekibe ait/)).toBeInTheDocument()
    // yorumlar OKUNUR
    expect(await within(dlg).findByText('veritabanı yeniden başlatıldı')).toBeInTheDocument()
    const actions = dlg.querySelector('[data-slot="incident-actions"]')
    expect(within(actions).queryByRole('button', { name: /^(Acknowledge|Onayla)$/ })).toBeNull()
    expect(within(actions).queryByRole('button', { name: /^(Resolve|Çöz)$/ })).toBeNull()
    expect(within(actions).queryByRole('button', { name: /(Delete|Sil)$/ })).toBeNull()
    expect(within(actions).queryByRole('link', { name: /Open monitor|İzlemeyi aç/ })).toBeNull()
    expect(within(dlg).queryByRole('link', { name: /Başka site/ })).toBeNull()             // izleme adı düz metin
    expect(within(dlg).queryByRole('textbox', { name: /Write a comment|Yorum yaz/ })).toBeNull()
    expect(dlg.querySelector('[data-slot="incident-composer-locked"]').textContent).toMatch(/another team|Başka ekibin/)
    expect(within(dlg).queryByRole('button', { name: / — (Sil|Delete)$/ })).toBeNull()      // yorum silme
    expect(api.admin.getAlertNotifications).not.toHaveBeenCalled()
    expect(api.nocCalls.list).not.toHaveBeenCalled()
    expect(dlg.querySelector('[data-slot="noc-call-summary"]')).toBeNull()
  })

  it('7/24 operatörü (noc_calls.write) başka ekibin olayında arama özetini salt okunur görür — yine eylem yok', async () => {
    mockList()
    api.me.getPermissions.mockResolvedValue({ success: true, data: { 'noc_calls.write': { edit: true }, 'alerts.read': { view: true } } })
    render(<PermissionsProvider user={{ username: 'noc1' }}><IncidentsPage systemRole="AUDIT" teamId={5} /></PermissionsProvider>)
    await waitFor(() => expect(api.me.getPermissions).toHaveBeenCalled())
    const dlg = await openCard('Başka site')
    await waitFor(() => expect(api.nocCalls.list).toHaveBeenCalledWith(2))
    expect(within(dlg).queryByRole('button', { name: /^(Acknowledge|Onayla|Resolve|Çöz)$/ })).toBeNull()
    expect(within(dlg).queryByRole('textbox', { name: /Write a comment|Yorum yaz/ })).toBeNull()
  })

  it('kendi olayı (USER): onayla/çöz/izlemeyi aç + yorum yazıcı + bildirim geçmişi yerinde; silme yok (can_delete=false)', async () => {
    mockList()
    render(<IncidentsPage systemRole="USER" teamId={5} />)
    const dlg = await openCard('Kendi sitesi')
    expect(dlg.querySelector('[data-slot="incident-read-only"]')).toBeNull()
    expect(within(dlg).getByRole('button', { name: /^(Acknowledge|Onayla)$/ })).toBeInTheDocument()
    expect(within(dlg).getByRole('button', { name: /^(Resolve|Çöz)$/ })).toBeInTheDocument()
    expect(within(dlg).getByRole('link', { name: /Open monitor|İzlemeyi aç/ })).toHaveAttribute('href', '?tab=http&monitor=70')
    expect(within(dlg).getByRole('textbox', { name: /Write a comment|Yorum yaz/ })).toBeInTheDocument()
    expect(await within(dlg).findByRole('button', { name: /^Kişi B · .+ — (Sil|Delete)$/ })).toBeInTheDocument()
    expect(within(dlg).queryByRole('button', { name: /^Kendi sitesi · .+ — (Sil|Delete)$/ })).toBeNull()
    await waitFor(() => expect(api.admin.getAlertNotifications).toHaveBeenCalledWith(1))
  })

  it('AUDIT (kendi kapsamı, can_act=false): rozet YOK ama onayla/çöz/yorum da YOK — yerinde "yetkiniz yok" notu', async () => {
    mockList({ rows: [{ ...own, can_act: false }], counts: null })
    render(<IncidentsPage systemRole="AUDIT" />)
    const dlg = await openCard('Kendi sitesi')
    expect(dlg.querySelector('[data-slot="incident-read-only"]')).toBeNull()
    expect(within(dlg).queryByRole('button', { name: /^(Acknowledge|Onayla)$/ })).toBeNull()
    expect(within(dlg).queryByRole('button', { name: /^(Resolve|Çöz)$/ })).toBeNull()
    expect(within(dlg).queryByRole('textbox', { name: /Write a comment|Yorum yaz/ })).toBeNull()
    expect(dlg.querySelector('[data-slot="incident-composer-locked"]').textContent).toMatch(/permission|yetkiniz yok/)
    expect(within(dlg).getByRole('link', { name: /Open monitor|İzlemeyi aç/ })).toBeInTheDocument()   // gezinme kalır
  })

  it('telefonda da: başka ekibin kartı rozetli, detayda eylem yok', async () => {
    MOBILE = true
    mockList()
    render(<IncidentsPage systemRole="USER" teamId={5} />)
    await screen.findByText('Başka site')
    expect(card(2).querySelector('[data-slot="read-only-badge"]')).not.toBeNull()
    const dlg = await openCard('Başka site')
    expect(within(dlg).queryByRole('button', { name: /^(Acknowledge|Onayla|Resolve|Çöz)$/ })).toBeNull()
  })
})
