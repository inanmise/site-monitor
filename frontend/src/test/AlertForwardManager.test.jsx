import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, within, fireEvent, waitFor } from './test-utils.jsx'
import { pressMenuTrigger } from './helpers/dropdownMenu.js'
import { EN } from '../i18n/en.js'

/**
 * Yönetim → Alarm iletimi (2026-11-05): liste (sahip → alıcı, üye adlarının TAMAMI, kapsam çipleri ≤ 4 + "+N", durum /
 * dikkat / adres 0, salt okunur satır), KPI süzgeci (URL g_fstate), Türkçe katlamalı arama, kaldırma onayı (adıyla),
 * duraklat / sürdür (sürümle), 403 erişim yok, okuma hatası + yeniden dene, boş durum (eylem çağrılı / yönetemeyen),
 * "Bize iletilenler" kartı, g_fnew derin bağı. Adlar gerçek uzunlukta.
 */
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
const { confirmMock } = vi.hoisted(() => ({ confirmMock: vi.fn() }))
vi.mock('../api/client', () => ({
  api: withApiFallback({
    alertForwards: { list: vi.fn(), create: vi.fn(), update: vi.fn(), remove: vi.fn(), monitors: vi.fn(), forMonitor: vi.fn() },
    teams: { directory: vi.fn(), members: vi.fn() },
  }),
  formatDate: (s) => String(s ?? ''),
  formatDateOnly: (s) => String(s ?? ''),
  formatDateSec: (s) => String(s ?? ''),
}))
vi.mock('../components/ui/Dialog.jsx', () => ({ useDialog: () => ({ showConfirm: confirmMock }), DialogProvider: ({ children }) => children }))
import { api } from '../api/client'
import AlertForwardManager from '../components/admin/forwards/AlertForwardManager.jsx'

const OWNER = 'Ödeme Sistemleri ve Kart Operasyonları Uygulama Geliştirme Ekibi (Teknoloji / Ödeme Platformları)'
const LONG_TEAM = 'Dijital Kanallar Mobil Bankacılık ve Uzun Adlı Uygulama Geliştirme Ekibi (Teknoloji / Kanal Yönetimi)'
const LONG_PERSON = 'Ayşegül Nur Karaosmanoğlu-Yıldırımtekin (Teknoloji Servis Yönetimi Bölüm Başkanı)'
const PERSON2 = 'Çağrı Işıkoğlu (Ağ Operasyon Merkezi / Kıdemli Uzman)'

const MONS = Array.from({ length: 6 }, (_, i) => ({ type: 'http', id: 100 + i, name: `Ödeme ağ geçidi uç noktası ${i + 1}`,
  target: `https://odeme-${i + 1}.example.com/api/saglik`, active: true, missing: false }))
const ROWS = [
  { id: 1, version: 2, owner_team_id: 10, owner_team_name: OWNER, owner_team_active: true, scope_kind: 'TYPES', scope_types: ['http', 'ping'],
    scope_monitors: [], recipient_kind: 'TEAM', recipient_team_id: 20, recipient_team_name: LONG_TEAM, recipient_team_active: true,
    recipient_members: [], recipient_address_count: 2, enabled: true, expires_on: null, state: 'active', note: 'Gece vardiyası desteği için',
    created_at: '2026-11-01T08:00:00', created_by: 'yonetici', updated_at: '2026-11-01T08:00:00', updated_by: 'yonetici', can_edit: true },
  { id: 2, version: 5, owner_team_id: 10, owner_team_name: OWNER, owner_team_active: true, scope_kind: 'MONITORS', scope_types: [],
    scope_monitors: MONS, recipient_kind: 'MEMBERS', recipient_team_id: 30, recipient_team_name: 'Ağ Operasyon',
    recipient_team_active: true, recipient_members: [
      { user_id: 'u-1', display_name: LONG_PERSON, active: true, in_team: true },
      { user_id: 'u-2', display_name: PERSON2, active: false, in_team: true },
      { user_id: 'u-3', display_name: 'Can Demir', active: true, in_team: false },
    ], recipient_address_count: 0, enabled: true, expires_on: null, state: 'active', note: null,
    created_at: '2026-11-02T08:00:00', created_by: 'yonetici', can_edit: true },
  { id: 3, version: 1, owner_team_id: 40, owner_team_name: 'Kart', owner_team_active: true, scope_kind: 'ALL', scope_types: [],
    scope_monitors: [], recipient_kind: 'TEAM', recipient_team_id: 20, recipient_team_name: LONG_TEAM, recipient_team_active: true,
    recipient_members: [], recipient_address_count: 1, enabled: false, expires_on: null, state: 'paused', note: null,
    created_at: '2026-10-01T08:00:00', created_by: 'baska', can_edit: false },
]
const INCOMING = [
  { id: 9, owner_team_id: 50, owner_team_name: 'Hazine ve Fon Yönetimi Platform Ekibi', scope_kind: 'TYPES', scope_types: ['cert'],
    scope_monitors: [], recipient_kind: 'TEAM', recipient_team_id: 10, recipient_team_name: OWNER, recipient_team_active: true,
    recipient_members: [], recipient_address_count: 1, enabled: true, state: 'active', can_edit: false },
]
const listResponse = (over = {}) => ({ success: true, data: { items: ROWS, incoming: INCOMING, manageable_teams: [{ id: 10, name: OWNER }],
  can_grant: true, limits: { per_team: 100, members: 50, monitors: 200, note: 500 }, ...over } })

const rows = () => [...document.querySelectorAll('[data-slot="fwd-list"] [data-slot="fwd-row"]')]
const rowOf = (id) => document.querySelector(`[data-slot="fwd-list"] [data-slot="fwd-row"][data-id="${id}"]`)
const tile = (k) => document.querySelector(`[data-slot="stat-item"][data-key="${k}"]`)

beforeEach(() => {
  vi.clearAllMocks()
  localStorage.clear()
  window.history.replaceState({}, '', '/?tab=admin&g_tab=forwards')
  confirmMock.mockResolvedValue(true)
  api.alertForwards.list.mockResolvedValue(listResponse())
  api.alertForwards.update.mockResolvedValue({ success: true, data: {} })
  api.alertForwards.remove.mockResolvedValue({ success: true, data: {} })
  api.alertForwards.monitors.mockResolvedValue({ success: true, data: { items: MONS.map((m) => ({ type: m.type, id: m.id, name: m.name, target: m.target, active: m.active })) } })
  api.teams.directory.mockResolvedValue({ success: true, data: [{ id: 10, name: OWNER, active: true, email: 'odeme@x' },
    { id: 20, name: LONG_TEAM, active: true, email: 'mobil@x' }, { id: 30, name: 'Ağ Operasyon', active: true, email: '' }] })
  api.teams.members.mockResolvedValue({ success: true, data: { members: [] } })
})

describe('AlertForwardManager', () => {
  it('liste: sahip → alıcı, üyelerin TAMAMI (pasif / ayrılmış rozetli), durum, adres 0 uyarısı, not, salt okunur satır; ad kırpılmaz', async () => {
    render(<AlertForwardManager />)
    await waitFor(() => expect(rows()).toHaveLength(3))
    expect(rowOf(1)).toHaveTextContent(OWNER)
    expect(rowOf(1)).toHaveTextContent(LONG_TEAM)
    expect(rowOf(1)).toHaveTextContent(EN['fwd.rcpt.wholeTeam'])
    expect(rowOf(1)).toHaveTextContent('Gece vardiyası desteği için')
    expect(rowOf(1).querySelector('[data-slot="fwd-state"]')).toHaveAttribute('data-state', 'active')
    // Üye adlarının hepsi; pasif ve takımdan ayrılmış üye rozetli
    const members = [...rowOf(2).querySelectorAll('[data-slot="fwd-member"]')]
    expect(members.map((m) => m.textContent)).toEqual([LONG_PERSON, `${PERSON2}· ${EN['fwd.memberPassive']}`, `Can Demir· ${EN['fwd.memberLeft']}`])
    // Etkin ama e-posta adresi yok → "yalnız push" + dikkat açıklaması
    expect(rowOf(2).querySelector('[data-slot="fwd-no-address"]')).toHaveTextContent(EN['fwd.noAddress'])
    expect(rowOf(2).querySelector('[data-slot="fwd-attention"]')).toHaveTextContent(EN['fwd.attention.noAddress'])
    // Salt okunur satır: menü yok
    expect(within(rowOf(3)).queryByRole('button', { name: new RegExp(EN['fwd.actions']) })).toBeNull()
    expect(rowOf(3)).toHaveTextContent(EN['fwd.readOnly'])
    expect(rowOf(3).querySelector('[data-slot="fwd-state"]')).toHaveAttribute('data-state', 'paused')
    for (const r of rows()) expect(r.innerHTML).not.toContain('truncate')
    expect(document.querySelector('[data-slot="fwd-new"]')).not.toBeNull()
  })

  it('kapsam: ilk 4 izleme görünür, "+N daha" açılınca HEPSİ; TYPES tür adları', async () => {
    render(<AlertForwardManager />)
    await waitFor(() => expect(rows()).toHaveLength(3))
    const chips = () => rowOf(2).querySelectorAll('[data-slot="fwd-scope-chip"]')
    expect(chips()).toHaveLength(4)
    const more = rowOf(2).querySelector('[data-slot="fwd-scope-more"]')
    expect(more).toHaveTextContent(EN['fwd.scope.more'].replace('{0}', '2'))
    fireEvent.click(more)
    expect(chips()).toHaveLength(6)
    expect(more).toHaveAttribute('aria-expanded', 'true')
    expect([...rowOf(1).querySelectorAll('[data-slot="fwd-scope-chip"]')].map((c) => c.textContent)).toEqual([EN['nav.http'], EN['nav.ping']])
    expect(rowOf(3).querySelector('[data-slot="fwd-scope-chip"]')).toHaveTextContent(EN['fwd.scope.allShort'])
  })

  it('KPI: Dikkat yalnız sorunlu / adressiz iletimleri süzer; URL g_fstate; Toplam temizler; sayılar doğru', async () => {
    render(<AlertForwardManager />)
    await waitFor(() => expect(rows()).toHaveLength(3))
    expect(tile('total')).toHaveTextContent('3')
    expect(tile('active')).toHaveTextContent('2')
    expect(tile('paused')).toHaveTextContent('1')
    expect(tile('attention')).toHaveTextContent('1')
    fireEvent.click(tile('attention'))
    await waitFor(() => expect(rows()).toHaveLength(1))
    expect(rows()[0]).toHaveAttribute('data-id', '2')
    await waitFor(() => expect(new URL(window.location.href).searchParams.get('g_fstate')).toBe('attention'))
    fireEvent.click(tile('total'))
    await waitFor(() => expect(rows()).toHaveLength(3))
  })

  it('arama Türkçe katlamalı (üye adı, izleme adı); kapsam süzgeci; süzgeçleri temizle', async () => {
    render(<AlertForwardManager />)
    await waitFor(() => expect(rows()).toHaveLength(3))
    const search = screen.getByRole('searchbox', { name: EN['fwd.search'] })
    fireEvent.change(search, { target: { value: 'cagri isikoglu' } })
    await waitFor(() => expect(rows()).toHaveLength(1))
    fireEvent.change(search, { target: { value: 'odeme ag gecidi uc noktasi 6' } })
    await waitFor(() => expect(rows().map((r) => r.dataset.id)).toEqual(['2']))
    fireEvent.change(search, { target: { value: '' } })
    fireEvent.change(screen.getByRole('combobox', { name: EN['fwd.f.scopeFilter'] }), { target: { value: 'all' } })
    await waitFor(() => expect(rows().map((r) => r.dataset.id)).toEqual(['3']))
    fireEvent.click(document.querySelector('[data-slot="fwd-clear"]'))
    await waitFor(() => expect(rows()).toHaveLength(3))
  })

  it('kaldırma onay ister ve neyin kaldırılacağını adıyla söyler; vazgeçilirse istek yok; onaylanınca silinir ve liste yenilenir', async () => {
    render(<AlertForwardManager />)
    await waitFor(() => expect(rows()).toHaveLength(3))
    confirmMock.mockResolvedValueOnce(false)
    pressMenuTrigger(within(rowOf(1)).getByRole('button', { name: new RegExp(EN['fwd.actions']) }))
    fireEvent.click(await screen.findByRole('menuitem', { name: EN['fwd.remove.ok'] }))
    await waitFor(() => expect(confirmMock).toHaveBeenCalled())
    const msg = confirmMock.mock.calls[0][0].message
    expect(msg).toContain(OWNER)
    expect(msg).toContain(LONG_TEAM)
    expect(msg).toContain(EN['nav.http'])
    expect(api.alertForwards.remove).not.toHaveBeenCalled()
    pressMenuTrigger(within(rowOf(1)).getByRole('button', { name: new RegExp(EN['fwd.actions']) }))
    fireEvent.click(await screen.findByRole('menuitem', { name: EN['fwd.remove.ok'] }))
    await waitFor(() => expect(api.alertForwards.remove).toHaveBeenCalledWith(1))
    await waitFor(() => expect(api.alertForwards.list).toHaveBeenCalledTimes(2))
  })

  it('duraklat: PUT {enabled:false, version}; 409 → hata + liste yeniden okunur', async () => {
    render(<AlertForwardManager />)
    await waitFor(() => expect(rows()).toHaveLength(3))
    pressMenuTrigger(within(rowOf(1)).getByRole('button', { name: new RegExp(EN['fwd.actions']) }))
    fireEvent.click(await screen.findByRole('menuitem', { name: EN['fwd.pause'] }))
    await waitFor(() => expect(api.alertForwards.update).toHaveBeenCalledWith(1, { enabled: false, version: 2 }))
    await waitFor(() => expect(api.alertForwards.list).toHaveBeenCalledTimes(2))
    api.alertForwards.update.mockResolvedValueOnce({ success: false, status: 409, code: 'CONCURRENT_MODIFICATION', error: 'Başkası değiştirdi (test).' })
    pressMenuTrigger(within(rowOf(2)).getByRole('button', { name: new RegExp(EN['fwd.actions']) }))
    fireEvent.click(await screen.findByRole('menuitem', { name: EN['fwd.pause'] }))
    await waitFor(() => expect(api.alertForwards.update).toHaveBeenCalledWith(2, { enabled: false, version: 5 }))
    await waitFor(() => expect(api.alertForwards.list).toHaveBeenCalledTimes(3))
  })

  it('duraklatılmış satır "Sürdür" gösterir → enabled:true', async () => {
    api.alertForwards.list.mockResolvedValue(listResponse({ items: [{ ...ROWS[0], enabled: false, state: 'paused' }] }))
    render(<AlertForwardManager />)
    await waitFor(() => expect(rows()).toHaveLength(1))
    pressMenuTrigger(within(rowOf(1)).getByRole('button', { name: new RegExp(EN['fwd.actions']) }))
    fireEvent.click(await screen.findByRole('menuitem', { name: EN['fwd.resume'] }))
    await waitFor(() => expect(api.alertForwards.update).toHaveBeenCalledWith(1, { enabled: true, version: 2 }))
  })

  it('403: "erişim yok" boş durumu — kırmızı hata değil, liste / yeni düğmesi yok', async () => {
    api.alertForwards.list.mockResolvedValue({ success: false, status: 403, error: 'İzin yok (test).' })
    render(<AlertForwardManager />)
    await screen.findByText(EN['fwd.forbidden.title'])
    expect(screen.queryByText(EN['fwd.loadFailed'])).toBeNull()
    expect(document.querySelector('[data-slot="fwd-new"]')).toBeNull()
    expect(document.querySelector('[data-slot="fwd-list"]')).toBeNull()
  })

  it('okuma hatası: danger + yeniden dene ("iletim yok" DEMEZ); yenileme hatasında son liste kalır ve uyarı çıkar', async () => {
    api.alertForwards.list.mockResolvedValueOnce({ success: false, status: 500, error: 'Sunucu yanıt vermedi (test).' })
    render(<AlertForwardManager />)
    await screen.findByText('Sunucu yanıt vermedi (test).')
    expect(screen.queryByText(EN['fwd.empty.title'])).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: EN['fwd.retry'] }))
    await waitFor(() => expect(rows()).toHaveLength(3))
    api.alertForwards.list.mockRejectedValueOnce(new Error('ağ'))
    fireEvent.click(document.querySelector('[data-slot="fwd-refresh"]'))
    await waitFor(() => expect(document.querySelector('[data-slot="fwd-stale"]')).not.toBeNull())
    expect(rows()).toHaveLength(3)
  })

  it('boş: yönetebiliyorsa üç adımlı açıklama + "İlk iletimi ekle" pencereyi açar; yönetemiyorsa açıklama, düğme yok', async () => {
    api.alertForwards.list.mockResolvedValue(listResponse({ items: [], incoming: [] }))
    const { unmount } = render(<AlertForwardManager />)
    await screen.findByText(EN['fwd.empty.title'])
    expect(document.querySelector('[data-slot="fwd-incoming"]')).toBeNull()
    fireEvent.click(document.querySelector('[data-slot="fwd-empty-add"]'))
    await waitFor(() => expect(document.querySelector('[data-slot="fwd-form"]')).not.toBeNull())
    unmount()
    api.alertForwards.list.mockResolvedValue(listResponse({ items: [], incoming: [], manageable_teams: [] }))
    render(<AlertForwardManager />)
    await screen.findByText(EN['fwd.empty.noManage'])
    expect(document.querySelector('[data-slot="fwd-empty-add"]')).toBeNull()
    expect(document.querySelector('[data-slot="fwd-new"]')).toBeNull()
  })

  it('"Bize iletilenler": salt okunur özet (sahip → biz, kapsam, durum), açıklama; menüsü yok', async () => {
    render(<AlertForwardManager />)
    const card = await waitFor(() => { const c = document.querySelector('[data-slot="fwd-incoming"]'); expect(c).not.toBeNull(); return c })
    expect(card).toHaveTextContent(EN['fwd.incoming.desc'])
    expect(card).toHaveTextContent('Hazine ve Fon Yönetimi Platform Ekibi')
    expect(card).toHaveTextContent(EN['fwd.type.cert'])
    expect(card.querySelector('[data-slot="fwd-state"]')).toHaveAttribute('data-state', 'active')
    expect(within(card).queryByRole('button', { name: new RegExp(EN['fwd.actions']) })).toBeNull()
  })

  it('g_fnew derin bağı: pencere "Seçilen izlemeler" kapsamıyla ve o izleme seçili açılır; g_fnew URL\'den silinir', async () => {
    window.history.replaceState({}, '', '/?tab=admin&g_tab=forwards&g_fnew=http:103&g_fteam=10')
    render(<AlertForwardManager />)
    const form = await waitFor(() => { const f = document.querySelector('[data-slot="fwd-form"]'); expect(f).not.toBeNull(); return f })
    expect(new URL(window.location.href).searchParams.get('g_fnew')).toBeNull()
    await waitFor(() => expect(api.alertForwards.monitors).toHaveBeenCalledWith(10))
    expect(within(form).getByRole('radio', { name: new RegExp(EN['fwd.scope.monitors']) })).toBeChecked()
    await waitFor(() => expect(form.querySelectorAll('[data-slot="fwd-monitor-chip"]')).toHaveLength(1))
    expect(form.querySelector('[data-slot="fwd-monitor-chip"]')).toHaveAttribute('data-key', 'http:103')
  })

  it('g_fnew yönetilmeyen takımın izlemesi için: pencere açılmaz, neden söylenir', async () => {
    window.history.replaceState({}, '', '/?tab=admin&g_tab=forwards&g_fnew=cert:ornek.example.com&g_fteam=77')
    render(<AlertForwardManager />)
    const banner = await waitFor(() => { const b = document.querySelector('[data-slot="fwd-deeplink-problem"]'); expect(b).not.toBeNull(); return b })
    // Görmediği takımla süzülü boş liste kalmaz — süzgeç bırakılır, kendi iletimleri görünür
    await waitFor(() => expect(rows()).toHaveLength(3))
    expect(banner).toHaveTextContent(EN['fwd.deepLink.notManaged'])
    expect(document.querySelector('[data-slot="fwd-form"]')).toBeNull()
  })

  it('kaydet sonrası özet bildirimi: bilgi sayıları + erişim, liste yenilenir', async () => {
    api.alertForwards.create.mockResolvedValue({ success: true, data: { ...ROWS[0], id: 99 }, grant: { requested: true, created: 1, existing: 0, failed: 0 },
      info: { emails: 2, push: 3 } })
    render(<AlertForwardManager />)
    await waitFor(() => expect(rows()).toHaveLength(3))
    fireEvent.click(document.querySelector('[data-slot="fwd-new"]'))
    const form = await waitFor(() => { const f = document.querySelector('[data-slot="fwd-form"]'); expect(f).not.toBeNull(); return f })
    const teamPicker = within(form).getByRole('combobox', { name: new RegExp(`^${EN['fwd.f.recipientTeam']}`) })
    await waitFor(() => expect(teamPicker).not.toBeDisabled())
    fireEvent.mouseDown(teamPicker)
    const opt = await screen.findByRole('option', { name: LONG_TEAM })
    fireEvent.mouseDown(opt); fireEvent.click(opt)
    fireEvent.click(document.querySelector('[data-slot="fwd-submit"]'))
    await waitFor(() => expect(api.alertForwards.create).toHaveBeenCalled())
    await screen.findByText(new RegExp(EN['fwd.toast.created'].replace('.', '\\.')))
    expect(screen.getByText(new RegExp('e-mail: 2, push: 3'))).toBeTruthy()
    await waitFor(() => expect(api.alertForwards.list).toHaveBeenCalledTimes(2))
    await waitFor(() => expect(document.querySelector('[data-slot="fwd-form"]')).toBeNull())
  })
})
