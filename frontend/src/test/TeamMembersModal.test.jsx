import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within, act } from './test-utils.jsx'
import TeamMembersModal from '../components/ui/TeamMembersModal.jsx'
import { EN } from '../i18n/en.js'
import {
  commonUnit, filterMembers, foldText, groupContactsByLevel, memberFacets, resolveModalManager, sortMembersBy,
} from '../components/ui/teamMembersModel.js'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  api: withApiFallback({
    teams: { members: vi.fn() },
    noc: { getCallList: vi.fn() },
  }),
}))

import { api } from '../api/client'

/**
 * Takım üyeleri penceresi — 2026-09-28 shadcn yeniden tasarımı. Adlar yer tutucu (proje kuralı).
 * Test dili EN (test ortamının varsayılanı): metinler sözlükten okunur, tahmin edilmez.
 */
const L = (key, ...args) => args.reduce((s, a, i) => s.split(`{${i}}`).join(String(a)), EN[key])

const TEAM = { id: 1, name: 'Takım A', email: 'takim-a@example.com', leader_id: 11, leader_display_name: 'Kişi B', active: true }
const mem = (id, display_name, extra = {}) => ({
  id, username: `U${id}`, display_name, email: `u${id}@example.com`, title: 'Uzman', department: 'Birim A',
  mudurluk_name: 'Müdürlük A', org_role: 'TECH', company_level: '5', manager_id: 90, manager_display_name: 'Kişi M', ...extra,
})
const MEMBERS = [
  mem(11, 'Kişi B', { org_role: 'PO', title: 'Ürün Sahibi', company_level: '8' }),
  mem(12, 'Kişi C', { title: 'Kıdemli Yazılım Uzmanı', company_level: '7' }),
  mem(13, 'Özgür D', { title: 'Test Uzmanı' }),
  mem(14, 'Kişi F', { org_role: 'MANAGER', title: 'Yönetici', company_level: '9', manager_id: 91, manager_display_name: 'Kişi N' }),
  mem(15, 'Ayşe E', { title: 'Analist' }),
]
const CONTACTS = [
  { id: 1, name: 'Kişi W', email: 'w@example.com', role: 'MANAGER', min_alert_level: 'CRITICAL' },
  { id: 2, name: 'Kişi X', email: 'x@example.com', role: 'TECH', min_alert_level: 'HIGH' },
  { id: 3, name: 'Kişi Y', email: 'y@example.com', role: 'PO', min_alert_level: 'WARNING' },
  { id: 4, name: 'Kişi Z', email: 'z@example.com', role: 'CLEVEL', min_alert_level: 'CRITICAL' },
]
const payload = (over = {}) => ({ success: true, data: { team: TEAM, members: MEMBERS, escalation_contacts: CONTACTS, ...over } })

const dialog = () => screen.getByRole('dialog')
const rowNames = () => [...dialog().querySelectorAll('[data-slot="team-member-name"]')].map((n) => n.textContent.trim())
const openTab = (name) => fireEvent.mouseDown(within(dialog()).getByRole('tab', { name }))

function show(props = {}) {
  return render(<TeamMembersModal open team={{ id: 1, name: 'Takım A' }} onClose={() => {}} {...props} />)
}

beforeEach(() => {
  vi.clearAllMocks()
  api.teams.members.mockResolvedValue(payload())
  api.noc.getCallList.mockResolvedValue({ success: true, data: [] })
})

describe('Başlık özeti — Takım Müdürü ve Takım Lideri AYRI, müdür üye listesine karışmaz', () => {
  it('türetilmiş müdür (üye değil) yalnız başlık çipinde; üye satırı sayısı = üye sayısı', async () => {
    show()
    await waitFor(() => expect(rowNames()).toHaveLength(5))
    const mgr = dialog().querySelector('[data-slot="team-manager"]')
    const lead = dialog().querySelector('[data-slot="team-leader"]')
    expect(within(mgr).getByText('Kişi M')).toBeInTheDocument()      // çoğunluğun bağlı olduğu ilk yönetici
    expect(within(mgr).getByText(L('team.colManager'))).toBeInTheDocument()
    expect(within(lead).getByText('Kişi B')).toBeInTheDocument()
    expect(within(lead).getByText('Ürün Sahibi')).toBeInTheDocument()   // lider üye → unvanı da
    expect(rowNames()).not.toContain('Kişi M')
    expect(dialog().querySelector('[data-slot="team-member-count"]').textContent).toContain(L('team.membersCount', 5))
    // Liderin satırında "Lider" rozeti; müdür rozeti hiçbir satırda yok (müdür üye değil)
    expect(dialog().querySelectorAll('[data-slot="team-member-leader"]')).toHaveLength(1)
    expect(dialog().querySelector('[data-slot="team-member-manager"]')).toBeNull()
  })

  it('elle atanmış müdür ÜYEYSE: çipte o, satırında rozet — satır İKİNCİ kez eklenmez', async () => {
    show({ team: { id: 1, name: 'Takım A', manager_id: 12 } })
    await waitFor(() => expect(rowNames()).toHaveLength(5))
    expect(within(dialog().querySelector('[data-slot="team-manager"]')).getByText('Kişi C')).toBeInTheDocument()
    expect(rowNames().filter((n) => n === 'Kişi C')).toHaveLength(1)
    const badge = dialog().querySelector('[data-slot="team-member-manager"]')
    expect(badge.closest('[data-slot="team-member-card"]').textContent).toContain('Kişi C')
  })

  it('yönetim ekranı teamManager verince O kullanılır; null = "Not set" (türetme yapılmaz — sütunla çelişmez)', async () => {
    const { unmount } = show({ teamManager: { label: 'Kişi Q', userId: 77, manual: true } })
    await waitFor(() => expect(rowNames()).toHaveLength(5))
    expect(within(dialog().querySelector('[data-slot="team-manager"]')).getByText('Kişi Q')).toBeInTheDocument()
    unmount()
    show({ teamManager: null })
    await waitFor(() => expect(rowNames()).toHaveLength(5))
    const mgr = dialog().querySelector('[data-slot="team-manager"]')
    expect(mgr).toHaveAttribute('data-empty', 'true')
    expect(within(mgr).getByText(L('team.notSet'))).toBeInTheDocument()
  })

  it('takım e-postası: mailto + adıyla kopyala; ortak müdürlük rozeti; açıklama', async () => {
    const writeText = vi.fn().mockResolvedValue()
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true })
    show({ team: { id: 1, name: 'Takım A', description: 'Ödeme servisleri ekibi' } })
    await waitFor(() => expect(rowNames()).toHaveLength(5))
    expect(dialog().querySelector('a[href="mailto:takim-a@example.com"]')).not.toBeNull()
    expect(within(dialog()).getByText('Ödeme servisleri ekibi')).toBeInTheDocument()
    expect(dialog().querySelector('[data-slot="team-unit"]').textContent).toContain('Müdürlük A')
    const copy = within(dialog()).getByRole('button', { name: L('a11y.rowAction', 'Takım A', L('team.copyMailbox')) })
    await act(async () => { fireEvent.click(copy) })
    expect(writeText).toHaveBeenCalledWith('takim-a@example.com')
    expect(copy).toHaveAccessibleName(L('a11y.rowAction', 'Takım A', L('team.emailCopied')))
  })
})

describe('Üyeler — arama, sıralama, rol çipleri, boş/sonuçsuz durum', () => {
  it('varsayılan sıra: MANAGER → PO → kademe → ad', async () => {
    show()
    await waitFor(() => expect(rowNames()).toHaveLength(5))
    expect(rowNames()).toEqual(['Kişi F', 'Kişi B', 'Kişi C', 'Ayşe E', 'Özgür D'])
  })

  it('arama aksan/harf duyarsız: ad, unvan ve e-posta; "N result" + Clear filters', async () => {
    show()
    await waitFor(() => expect(rowNames()).toHaveLength(5))
    const box = within(dialog()).getByRole('searchbox', { name: L('team.memberSearchLabel') })
    fireEvent.change(box, { target: { value: 'ozgur' } })
    expect(rowNames()).toEqual(['Özgür D'])
    expect(dialog().querySelector('[data-slot="team-member-results"]').textContent).toBe(L('team.results.one', 1))
    fireEvent.change(box, { target: { value: 'KIDEMLI' } })
    expect(rowNames()).toEqual(['Kişi C'])
    fireEvent.change(box, { target: { value: 'u15@example' } })
    expect(rowNames()).toEqual(['Ayşe E'])
    fireEvent.click(within(dialog()).getByRole('button', { name: L('team.clearFilters') }))
    expect(rowNames()).toHaveLength(5)
  })

  it('sonuçsuz arama: boş durum + temizle düğmesi listeyi geri getirir', async () => {
    show()
    await waitFor(() => expect(rowNames()).toHaveLength(5))
    fireEvent.change(within(dialog()).getByRole('searchbox'), { target: { value: 'zzz' } })
    expect(rowNames()).toHaveLength(0)
    expect(within(dialog()).getByText(L('team.noResultsTitle'))).toBeInTheDocument()
    expect(dialog().querySelector('[data-slot="team-member-results"]').textContent).toBe(L('team.results', 0))
    const clears = within(dialog()).getAllByRole('button', { name: L('team.clearFilters') })
    fireEvent.click(clears[clears.length - 1])
    expect(rowNames()).toHaveLength(5)
  })

  it('sıralama: ada göre ve unvana göre', async () => {
    show()
    await waitFor(() => expect(rowNames()).toHaveLength(5))
    const sort = within(dialog()).getByRole('combobox', { name: L('team.sortLabel') })
    fireEvent.change(sort, { target: { value: 'name' } })
    expect(rowNames()).toEqual(['Ayşe E', 'Kişi B', 'Kişi C', 'Kişi F', 'Özgür D'])
    fireEvent.change(sort, { target: { value: 'title' } })
    // Analist · Kıdemli Yazılım Uzmanı · Test Uzmanı · Ürün Sahibi · Yönetici (tr sırası)
    expect(rowNames()).toEqual(['Ayşe E', 'Kişi C', 'Özgür D', 'Kişi B', 'Kişi F'])
  })

  it('rol çipleri veriden türer (sayılarıyla); çip listeyi süzer, "Tümü" geri alır', async () => {
    show()
    await waitFor(() => expect(rowNames()).toHaveLength(5))
    const group = within(dialog()).getByRole('radiogroup', { name: L('team.filterLabel') })
    const chips = within(group).getAllByRole('radio').map((b) => b.getAttribute('aria-label'))
    expect(chips).toEqual([`${L('team.filterAll')} (5)`, `${EN['usr.orgRoleVal.MANAGER']} (1)`, `${EN['usr.orgRoleVal.PO']} (1)`, `${EN['usr.orgRoleVal.TECH']} (3)`])
    fireEvent.click(within(group).getByRole('radio', { name: `${EN['usr.orgRoleVal.PO']} (1)` }))
    expect(rowNames()).toEqual(['Kişi B'])
    fireEvent.click(within(group).getByRole('radio', { name: `${L('team.filterAll')} (5)` }))
    expect(rowNames()).toHaveLength(5)
  })

  it('tek rollü takımda çip çubuğu çizilmez (anlamsız süzgeç yok)', async () => {
    api.teams.members.mockResolvedValue(payload({ members: MEMBERS.map((m) => ({ ...m, org_role: 'TECH' })) }))
    show()
    await waitFor(() => expect(rowNames()).toHaveLength(5))
    expect(within(dialog()).queryByRole('radiogroup', { name: L('team.filterLabel') })).toBeNull()
  })

  it('üyesiz takım: boş durum', async () => {
    api.teams.members.mockResolvedValue(payload({ members: [] }))
    show()
    await waitFor(() => expect(within(dialog()).getByText(L('team.noMembers'))).toBeInTheDocument())
    expect(dialog().querySelector('[data-slot="team-member-cards"]')).toBeNull()
  })

  it('satır e-postası: mailto + kişiyi adıyla anan kopyala düğmesi', async () => {
    const writeText = vi.fn().mockResolvedValue()
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true })
    show()
    await waitFor(() => expect(rowNames()).toHaveLength(5))
    const row = within(dialog()).getByRole('listitem', { name: 'Kişi C' })
    expect(row.querySelector('a[href="mailto:u12@example.com"]')).not.toBeNull()
    const copy = within(row).getByRole('button', { name: L('a11y.rowAction', 'Kişi C', L('team.copyEmail')) })
    await act(async () => { fireEvent.click(copy) })
    expect(writeText).toHaveBeenCalledWith('u12@example.com')
    // Satırın kendi müdürü ALAN olarak
    expect(row.textContent).toContain(L('team.reportsTo', 'Kişi M'))
  })
})

describe('Yönetim kipi — düzenle eylemi yalnız canManage', () => {
  it('canManage yokken düzenle düğmesi yok', async () => {
    show()
    await waitFor(() => expect(rowNames()).toHaveLength(5))
    expect(dialog().querySelector('[data-slot="team-member-open"]')).toBeNull()
  })

  it('canManage: satır başına adıyla "Edit User"; tıklayınca üye nesnesiyle onEditUser', async () => {
    const onEditUser = vi.fn()
    show({ canManage: true, onEditUser })
    await waitFor(() => expect(rowNames()).toHaveLength(5))
    expect(dialog().querySelectorAll('[data-slot="team-member-open"]')).toHaveLength(5)
    fireEvent.click(within(dialog()).getByRole('button', { name: L('a11y.rowAction', 'Kişi C', EN['usr.editTitle']) }))
    expect(onEditUser).toHaveBeenCalledWith(expect.objectContaining({ id: 12 }))
  })

  it('ek üyelik (yönetim verisi team_id) rozet + çip', async () => {
    const admin = MEMBERS.map((m) => ({ ...m, team_id: m.id === 13 ? 5 : 1, team_ids: m.id === 13 ? [5, 1] : [1] }))
    const loadMembers = vi.fn().mockResolvedValue({ success: true, data: { team: null, members: admin, escalation_contacts: [] } })
    show({ loadMembers, team: TEAM })
    await waitFor(() => expect(rowNames()).toHaveLength(5))
    const sec = dialog().querySelectorAll('[data-slot="team-member-secondary"]')
    expect(sec).toHaveLength(1)
    expect(sec[0].closest('[data-slot="team-member-card"]').textContent).toContain('Özgür D')
    fireEvent.click(within(dialog()).getByRole('radio', { name: `${L('team.filterSecondary')} (1)` }))
    expect(rowNames()).toEqual(['Özgür D'])
  })
})

/**
 * jsdom görsel YÜKLEMEZ → Radix AvatarImage `<img>` çizmez. Yüklenmiş görseli taklit eden sahte Image: src atanınca
 * bir sonraki turda `load` tetiklenir (Radix hem onload hem addEventListener yolunu kullanabilir).
 */
class LoadedImage {
  constructor() { this.complete = false; this.naturalWidth = 0; this.listeners = {} }
  addEventListener(type, fn) { this.listeners[type] = fn }
  removeEventListener(type) { delete this.listeners[type] }
  set src(v) {
    this._src = v; this.complete = true; this.naturalWidth = 40
    // Radix olayın hedefinden okur (`event.currentTarget.complete`) — hedefsiz olay yakalanmamış TypeError üretiyordu
    const ev = { type: 'load', target: this, currentTarget: this }
    setTimeout(() => { this.onload?.(ev); this.listeners.load?.(ev) }, 0)
  }
  get src() { return this._src }
}

describe('Fotoğraf ve sistem rolü görünür (kullanıcı kararı 2026-09-28); telefon / sicil asla', () => {
  afterEach(() => { vi.unstubAllGlobals() })

  it('rehber verisi: has_photo olan üyenin fotoğrafı istenir, olmayanınki istenmez; sistem rolü rozeti', async () => {
    vi.stubGlobal('Image', LoadedImage)
    const withRoles = MEMBERS.map((m, i) => ({ ...m, system_role: i === 0 ? 'ADMIN' : 'USER', has_photo: m.id !== 13 }))
    api.teams.members.mockResolvedValue(payload({ members: withRoles }))
    show()
    await waitFor(() => expect(rowNames()).toHaveLength(5))
    await waitFor(() => expect(dialog().querySelector('img[src="/api/users/12/photo"]')).not.toBeNull())
    expect(dialog().querySelector('img[src="/api/users/13/photo"]')).toBeNull()   // has_photo:false → istek yok
    const roles = [...dialog().querySelectorAll('[data-slot="team-member-system-role"]')].map((b) => b.getAttribute('data-role'))
    expect(roles).toHaveLength(5)
    expect(roles.filter((r) => r === 'ADMIN')).toHaveLength(1)
  })

  it('yönetim yükleyicisi tam entity verse de telefon / sicil görünmez; fotoğraf ve sistem rolü görünür', async () => {
    vi.stubGlobal('Image', LoadedImage)
    const full = MEMBERS.map((m, i) => ({ ...m, phone: `+90 532 000 00 0${i}`, employee_id: `10020${i}`, system_role: 'ADMIN', photo_base64: 'QUJD' }))
    const loadMembers = vi.fn().mockResolvedValue({ success: true, data: { team: TEAM, members: full, escalation_contacts: CONTACTS } })
    api.noc.getCallList.mockResolvedValue({ success: true, data: [{ user_id: 12, display_name: 'Kişi C', title: 'Uzman', has_phone: true, phone: '+90 555 111 22 33' }] })
    show({ loadMembers, canManage: true, onEditUser: () => {} })
    await waitFor(() => expect(rowNames()).toHaveLength(5))
    // Her sekme AYRI denetlenir: Radix pasif sekmenin içeriğini DOM'dan çıkarır (bite-check 2026-09-28: yalnız son sekmeye
    // bakan sürüm üye satırına eklenen telefonu yakalamıyordu).
    const assertClean = () => {
      const text = document.body.textContent
      expect(text).not.toMatch(/\+90/)
      expect(text).not.toContain('100200')
    }
    assertClean()   // Üyeler sekmesi (satırlar DOM'da)
    // yönetim verisinde has_photo yok → fotoğraf istenir (yoksa sunucu 204 → baş harf); sistem rolü rozeti görünür
    await waitFor(() => expect(dialog().querySelector('img[src="/api/users/11/photo"]')).not.toBeNull())
    expect(dialog().querySelectorAll('[data-slot="team-member-system-role"][data-role="ADMIN"]')).toHaveLength(5)
    openTab(new RegExp(EN['team.tabEscalation']))
    await waitFor(() => expect(dialog().querySelector('[data-slot="team-escalation-group"]')).not.toBeNull())
    assertClean()
    openTab(/24\/7/)
    await waitFor(() => expect(dialog().querySelector('[data-slot="team-call-item"]')).not.toBeNull())
    assertClean()
  })
})

describe('Eskalasyon sekmesi', () => {
  it('açıklama + seviyeye göre gruplar (kritik önce), seviye rozetleri, adıyla kopyala', async () => {
    show()
    await waitFor(() => expect(rowNames()).toHaveLength(5))
    openTab(new RegExp(EN['team.tabEscalation']))
    await waitFor(() => expect(within(dialog()).getByText(L('team.escWhatTitle'))).toBeInTheDocument())
    const groups = [...dialog().querySelectorAll('[data-slot="team-escalation-group"]')]
    expect(groups.map((g) => g.getAttribute('data-level'))).toEqual(['CRITICAL', 'HIGH', 'WARNING'])
    expect(within(groups[0]).getByRole('heading', { name: new RegExp(L('team.escScope.CRITICAL')) })).toBeInTheDocument()
    expect([...groups[0].querySelectorAll('[data-slot="team-escalation-name"]')].map((n) => n.textContent)).toEqual(['Kişi W', 'Kişi Z'])
    expect(groups[1].querySelector('[data-slot="team-escalation-level"]').textContent).toBe(EN['ec.level.high'])
    expect(within(groups[2]).getByRole('button', { name: L('a11y.rowAction', 'Kişi Y', L('team.copyEmail')) })).toBeInTheDocument()
    expect(within(groups[2]).getByText('y@example.com').closest('a')).toHaveAttribute('href', 'mailto:y@example.com')
  })

  it('kişisiz takım: boş durum', async () => {
    api.teams.members.mockResolvedValue(payload({ escalation_contacts: [] }))
    show()
    await waitFor(() => expect(rowNames()).toHaveLength(5))
    openTab(new RegExp(EN['team.tabEscalation']))
    await waitFor(() => expect(within(dialog()).getByText(L('team.noEscalation'))).toBeInTheDocument())
  })
})

describe('7/24 arama listesi sekmesi', () => {
  const ROWS = [
    { user_id: 11, display_name: 'Kişi B', title: 'Ürün Sahibi', has_phone: true, is_member: true },
    { user_id: 12, display_name: 'Kişi C', title: 'Kıdemli Yazılım Uzmanı', has_phone: false, is_member: true },
    { user_id: 16, display_name: 'Kişi G', title: 'Uzman', has_phone: true, is_member: false },
  ]
  afterEach(() => { vi.restoreAllMocks() })

  it('sıralı liste; AD’de telefonu olmayan uyarı + rozet; üye olmayan rozet; 7/24 Kapsamı’na götürür', async () => {
    api.noc.getCallList.mockResolvedValue({ success: true, data: ROWS })
    const onClose = vi.fn()
    const nav = vi.fn()
    window.addEventListener('sm:navigate', nav)
    show({ onClose })
    await waitFor(() => expect(within(dialog()).getByRole('tab', { name: /24\/7/ })).toBeInTheDocument())
    openTab(/24\/7/)
    const list = await within(dialog()).findByRole('list', { name: L('noc.clListLabel', 'Takım A') })
    const items = within(list).getAllByRole('listitem')
    expect(items.map((li) => li.getAttribute('data-user'))).toEqual(['11', '12', '16'])
    expect(items[1].querySelector('[data-slot="team-call-nophone"]')).not.toBeNull()
    expect(items[0].querySelector('[data-slot="team-call-nophone"]')).toBeNull()
    expect(items[2].querySelector('[data-slot="team-call-notmember"]')).not.toBeNull()
    expect(within(dialog()).getByText(L('noc.clNoPhoneTitle', 'Kişi C'))).toBeInTheDocument()
    fireEvent.click(within(dialog()).getByRole('button', { name: new RegExp(L('team.clOpen')) }))
    expect(onClose).toHaveBeenCalled()
    expect(nav).toHaveBeenCalledTimes(1)
    expect(nav.mock.calls[0][0].detail).toEqual({ tab: 'noc', params: { n_ct: '1' } })
    window.removeEventListener('sm:navigate', nav)
  })

  it('boş liste: tanımlanmamış durumu anlatılır', async () => {
    show()
    await waitFor(() => expect(within(dialog()).getByRole('tab', { name: /24\/7/ })).toBeInTheDocument())
    openTab(/24\/7/)
    await waitFor(() => expect(within(dialog()).getByText(L('noc.clEmptyTitle', 'Takım A'))).toBeInTheDocument())
  })

  it('403 (görme yetkisi yok): sekme HİÇ çizilmez', async () => {
    api.noc.getCallList.mockResolvedValue({ success: false, status: 403, error: 'Yetki yok' })
    show()
    await waitFor(() => expect(rowNames()).toHaveLength(5))
    await waitFor(() => expect(api.noc.getCallList).toHaveBeenCalledWith(1))
    await act(async () => {})
    expect(within(dialog()).queryByRole('tab', { name: /24\/7/ })).toBeNull()
    expect(within(dialog()).getAllByRole('tab')).toHaveLength(2)
  })

  it('sunucu hatası (500): sekme görünür, hata + yeniden dene', async () => {
    api.noc.getCallList.mockResolvedValueOnce({ success: false, status: 500, error: 'Sunucu hatası' })
    show()
    await waitFor(() => expect(within(dialog()).getByRole('tab', { name: /24\/7/ })).toBeInTheDocument())
    openTab(/24\/7/)
    expect(await within(dialog()).findByText('Sunucu hatası')).toBeInTheDocument()
    api.noc.getCallList.mockResolvedValueOnce({ success: true, data: ROWS })
    fireEvent.click(within(dialog()).getByRole('button', { name: new RegExp(L('team.retry')) }))
    expect(await within(dialog()).findByRole('list', { name: L('noc.clListLabel', 'Takım A') })).toBeInTheDocument()
  })
})

describe('Büyük takım (300 üye) — standart sayfalama', () => {
  it('ilk çizim 50 satır; sayı her zaman görünür; "Sonraki" sonraki sayfayı açar; arama tüm listede ve 1. sayfaya döner', async () => {
    const big = Array.from({ length: 300 }, (_, i) => mem(1000 + i, `Kişi ${String(i).padStart(3, '0')}`))
    api.teams.members.mockResolvedValue(payload({ members: big }))
    const t0 = performance.now()
    show()
    await waitFor(() => expect(rowNames()).toHaveLength(50))
    const ms = performance.now() - t0
    expect(dialog().querySelector('[data-slot="team-member-count"]').textContent).toContain(L('team.membersCount', 300))
    // Açılım ("N kişi daha") değil standart çubuk (paginationBase kapısı): sonraki sayfa FARKLI 50 kişi
    const firstPage = rowNames()
    fireEvent.click(within(dialog()).getByRole('button', { name: L('pg.next') }))
    expect(rowNames()).toHaveLength(50)
    expect(rowNames().some((n) => firstPage.includes(n))).toBe(false)
    // Arama 300'ün tamamında (yalnız görünen sayfada değil); görünüm değişince 1. sayfaya dönülür
    fireEvent.change(within(dialog()).getByRole('searchbox'), { target: { value: 'Kişi 29' } })
    // "29" geçen: ad Kişi 029/129/229/290–299 (e-posta/kullanıcı adı aynı kümeyi verir) = 13
    expect(rowNames()).toHaveLength(13)
    expect(dialog().querySelector('[data-slot="team-member-results"]').textContent).toBe(L('team.results', 13))
    // Akıl sağlığı: 300 üyeli pencere jsdom'da makul sürede açılır (ölçü raporlanır, sıkı eşik değil)
    expect(ms).toBeLessThan(10000)
  })
})

describe('Yükleme yarışı — refreshKey art arda değişince eski yanıt yenisini ezmez', () => {
  it('ilk (yavaş) yanıt ikinciden SONRA gelse de ekranda ikinci veri kalır', async () => {
    let resolveFirst
    const first = new Promise((r) => { resolveFirst = r })
    const loadMembers = vi.fn()
      .mockImplementationOnce(() => first)
      .mockImplementationOnce(() => Promise.resolve(payload({ members: [mem(21, 'Yeni Kişi')] })))
    const { rerender } = show({ loadMembers, refreshKey: 0 })
    rerender(<TeamMembersModal open team={{ id: 1, name: 'Takım A' }} onClose={() => {}} loadMembers={loadMembers} refreshKey={1} />)
    await waitFor(() => expect(rowNames()).toEqual(['Yeni Kişi']))
    await act(async () => { resolveFirst(payload({ members: [mem(20, 'Eski Kişi')] })) })
    expect(rowNames()).toEqual(['Yeni Kişi'])
    expect(loadMembers).toHaveBeenCalledTimes(2)
  })

  it('aynı takımın yeniden yüklenmesinde eski liste ekranda kalır (iskelete dönmez), arama korunur', async () => {
    let resolveSecond
    const loadMembers = vi.fn()
      .mockImplementationOnce(() => Promise.resolve(payload()))
      .mockImplementationOnce(() => new Promise((r) => { resolveSecond = r }))
    const { rerender } = show({ loadMembers, refreshKey: 0 })
    await waitFor(() => expect(rowNames()).toHaveLength(5))
    fireEvent.change(within(dialog()).getByRole('searchbox'), { target: { value: 'ozgur' } })
    rerender(<TeamMembersModal open team={{ id: 1, name: 'Takım A' }} onClose={() => {}} loadMembers={loadMembers} refreshKey={1} />)
    await waitFor(() => expect(loadMembers).toHaveBeenCalledTimes(2))   // yükleyici mikro-görevde çağrılır
    expect(rowNames()).toEqual(['Özgür D'])
    expect(dialog().querySelector('[data-slot="team-loading"]')).toBeNull()
    await act(async () => { resolveSecond(payload({ members: [...MEMBERS, mem(22, 'Özgür H')] })) })
    expect(rowNames()).toEqual(['Özgür D', 'Özgür H'])
  })

  it('takım değişince önceki takımın geç yanıtı yeni takımın listesine yazılmaz', async () => {
    let resolveA
    const loadMembers = vi.fn((id) => (id === 1
      ? new Promise((r) => { resolveA = r })
      : Promise.resolve(payload({ team: { id: 2, name: 'Takım B' }, members: [mem(31, 'Kişi T')] }))))
    const { rerender } = show({ loadMembers, team: { id: 1, name: 'Takım A' } })
    rerender(<TeamMembersModal open team={{ id: 2, name: 'Takım B' }} onClose={() => {}} loadMembers={loadMembers} />)
    await waitFor(() => expect(rowNames()).toEqual(['Kişi T']))
    await act(async () => { resolveA(payload()) })
    expect(rowNames()).toEqual(['Kişi T'])
  })
})

describe('Saf model', () => {
  it('foldText: Türkçe + aksan', () => {
    expect(foldText('  ÖZGÜR  Işık ')).toBe('ozgur isik')
  })
  it('filterMembers çok sözcüklü arama VE bağlıdır', () => {
    expect(filterMembers(MEMBERS, { query: 'kişi uzman' }).map((m) => m.id)).toEqual([12])
  })
  it('sortMembersBy title: unvansızlar sonda', () => {
    const r = sortMembersBy([mem(1, 'B', { title: '' }), mem(2, 'A', { title: 'Z' })], 'title')
    expect(r.map((m) => m.id)).toEqual([2, 1])
  })
  it('memberFacets: rolsüz üyeler "none", ek üyelik yalnız team_id varsa', () => {
    const f = memberFacets([mem(1, 'A', { org_role: null }), mem(2, 'B', { team_id: 9 })], 1)
    expect(f.map((x) => x.value)).toEqual(['all', 'role:TECH', 'role:none', 'secondary'])
  })
  it('commonUnit: karışık müdürlükte null', () => {
    expect(commonUnit([mem(1, 'A'), mem(2, 'B', { mudurluk_name: 'Müdürlük B' })])).toBeNull()
    expect(commonUnit([mem(1, 'A'), mem(2, 'B', { mudurluk_name: '' })])).toBe('Müdürlük A')
  })
  it('groupContactsByLevel: bilinmeyen seviye WARNING, boş grup yok', () => {
    const g = groupContactsByLevel([{ name: 'A', min_alert_level: 'X' }, { name: 'B', min_alert_level: 'CRITICAL' }])
    expect(g.map((x) => [x.level, x.items.length])).toEqual([['CRITICAL', 1], ['WARNING', 1]])
  })
  it('resolveModalManager: elle atanmış üye olmayan müdürün adı bağlı üyeden okunur', () => {
    expect(resolveModalManager({ managerId: 90, members: MEMBERS })).toMatchObject({ label: 'Kişi M', manual: true, member: null })
  })
})
