import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within, act } from './test-utils.jsx'

const toastMock = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn(), info: vi.fn(), dismiss: vi.fn() }))
vi.mock('../components/ui/Toast.jsx', () => ({ useToast: () => toastMock, ToastProvider: ({ children }) => children }))
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  api: withApiFallback({ noc: {
    coverage: vi.fn(), setMonitor: vi.fn(), bulk: vi.fn(), getCallList: vi.fn(), saveCallList: vi.fn(), teamMembers: vi.fn(),
  } }),
  formatDate: (s) => String(s ?? ''),
}))

import { api } from '../api/client'
import NocCoveragePage from '../pages/NocCoveragePage.jsx'
import { TeamDirectoryProvider } from '../components/ui/TeamDirectory.jsx'

const ITEMS = [
  { type: 'PING', id: 1, name: 'ping-a', target: 'a.example.com', team_id: 1, team_name: 'Takım A', active: true, noc_notify: false, covered: false, reason: 'MONITOR_OFF', group_names: [] },
  { type: 'HTTP', id: 2, name: 'web', target: 'https://www.example.com', team_id: 1, team_name: 'Takım A', active: true, noc_notify: true, covered: true, reason: null, group_names: ['NOC Nöbet'] },
  { type: 'DNS', id: 3, name: 'dns-b', target: 'example.org', team_id: 2, team_name: 'Takım B', active: false, noc_notify: false, covered: false, reason: 'PAUSED', group_names: [] },
  { type: 'PORT', id: 4, name: 'db', target: 'db.example.com:5432', team_id: 2, team_name: 'Takım B', active: true, noc_notify: true, covered: false, reason: 'TYPE_DISABLED', group_names: [] },
  { type: 'SSL', id: 5, name: 'www.example.com', target: 'www.example.com', team_id: 1, team_name: 'Takım A', active: true, noc_notify: false, covered: false, reason: 'MONITOR_OFF', group_names: [] },
]
const SUMMARY = { total: 5, covered: 1, not_covered: 3, paused: 1, by_type: {}, active_groups: 2, disabled_types: ['PORT'] }
const ok = (items = ITEMS, summary = SUMMARY) => ({ success: true, data: { summary, items } })
const USER = { systemRole: 'USER', globalAdmin: false, myTeamIds: [1], myTeams: [{ id: 1, name: 'Takım A' }], userId: 7 }

function deferred() {
  let resolve
  const promise = new Promise((r) => { resolve = r })
  return { promise, resolve }
}
const rows = () => [...document.querySelectorAll('[data-slot="noc-row"]')]
const rowOf = (key) => document.querySelector(`[data-slot="noc-row"][data-key="${key}"]`)

async function renderPage(props = {}) {
  const utils = render(<NocCoveragePage {...USER} {...props} />)
  await waitFor(() => expect(document.querySelector('[data-slot="noc-skeleton"]')).toBeNull())
  return utils
}

beforeEach(() => {
  vi.clearAllMocks()
  window.history.replaceState(null, '', '/?tab=noc')
  api.noc.coverage.mockResolvedValue(ok())
  api.noc.getCallList.mockResolvedValue({ success: true, data: [] })
  api.noc.teamMembers.mockResolvedValue({ success: true, data: [] })
})

/** 7/24 Kapsamı (2026-09-27): özet, süzgeçler, neden rozetleri, tek tık iyimser + geri alma, toplu, arama listesi, durumlar. */
describe('NocCoveragePage — özet ve liste', () => {
  it('kutucuklar satırlardan türer; kapsam yüzdesi; tür çubukları (kapalı tür rozetli); yöneticinin kapattığı türler bandı', async () => {
    await renderPage()
    await waitFor(() => expect(rows()).toHaveLength(5))
    const values = [...document.querySelectorAll('[data-slot="stat-item"] [data-slot="stat-value"]')].map((v) => v.textContent)
    expect(values).toEqual(['5', '1', '3', '1'])   // toplam · kapsanan · kapsanmayan · duraklatılmış
    expect(document.querySelector('[data-slot="noc-meta-pct"]').textContent).toMatch(/25/)   // 1 / (5 − 1 duraklatılmış)
    const typeRows = [...document.querySelectorAll('[data-slot="noc-type-coverage"] li[data-type]')].map((li) => li.getAttribute('data-type'))
    expect(typeRows).toEqual(['SSL', 'PING', 'HTTP', 'DNS', 'PORT'])
    const port = document.querySelector('[data-slot="noc-type-coverage"] li[data-type="PORT"]')
    expect(within(port).getByText(/Type off|Tür kapalı/)).toBeInTheDocument()
    expect(within(port).getByRole('progressbar', { hidden: true })).toBeInTheDocument()
    expect(screen.getByText(/An administrator has switched off some types|Yönetici bazı türleri kapattı/)).toBeInTheDocument()
  })

  it('satır sırası: kapsanmayan (kullanıcının düzeltebileceği) önce; neden rozetleri düz sözcükle; takım rozeti', async () => {
    await renderPage()
    await waitFor(() => expect(rows()).toHaveLength(5))
    expect(rows().map((r) => r.getAttribute('data-key'))).toEqual(['SSL:5', 'PING:1', 'PORT:4', 'DNS:3', 'HTTP:2'])
    const reason = (key) => rowOf(key).querySelector('[data-slot="noc-reason"]')
    expect(reason('PING:1')).toHaveAttribute('data-reason', 'MONITOR_OFF')
    expect(reason('PING:1').textContent).toMatch(/Monitor not opted in|İzleme 7\/24 ekibine bildirmiyor/)
    expect(reason('PORT:4').textContent).toMatch(/Type disabled by admin|Tür yönetici tarafından kapatıldı/)
    expect(reason('DNS:3').textContent).toMatch(/^(Paused|Duraklatıldı)$/)
    expect(reason('HTTP:2').textContent).toMatch(/Covered 24\/7|7\/24 kapsamında/)
    expect(within(rowOf('PORT:4')).getByText('Takım B')).toBeInTheDocument()
    // USER Takım A üyesi: B takımının satırında eylem yok; kendi takımının kapalı satırında "7/24'e bildir"
    expect(within(rowOf('DNS:3')).queryByRole('button', { name: /Notify the 24\/7 team|7\/24’e bildir/ })).toBeNull()
    expect(within(rowOf('PING:1')).getByRole('button', { name: /^(Notify the 24\/7 team|7\/24’e bildir) — ping-a$/ })).toBeInTheDocument()
  })

  it('ad GERÇEK bağlantı: tür sekmesi + ?monitor=; SSL → Pano + sertifika penceresi (open=cert) — ayrıntı NocCoverageDeepLink.test', async () => {
    const nav = vi.fn()
    window.addEventListener('sm:navigate', nav)
    try {
      await renderPage()
      await waitFor(() => expect(rows()).toHaveLength(5))
      fireEvent.click(within(rowOf('PORT:4')).getByRole('link', { name: 'db' }))
      expect(nav.mock.calls.at(-1)[0].detail).toEqual({ tab: 'port', params: { monitor: 4 } })
      fireEvent.click(within(rowOf('SSL:5')).getByRole('link', { name: 'www.example.com' }))
      expect(nav.mock.calls.at(-1)[0].detail).toEqual({ tab: 'dashboard', params: { domain: 'www.example.com', open: 'cert' } })
    } finally { window.removeEventListener('sm:navigate', nav) }
  })
})

describe('NocCoveragePage — süzgeçler (URL n_*)', () => {
  it('derin bağlantı n_type süzer + çip; çipi kaldırmak temizler; arama n_q URL\'e yazılır; durum kutucuğu süzer', async () => {
    window.history.replaceState(null, '', '/?tab=noc&n_type=PING,DNS,BOGUS')
    await renderPage()
    await waitFor(() => expect(rows().map((r) => r.getAttribute('data-key')).sort()).toEqual(['DNS:3', 'PING:1']))
    const chip = document.querySelector('[data-action="noc-chip"][data-chip="type"]')
    expect(chip.textContent).toMatch(/(Type|Tür): Ping, DNS/)
    fireEvent.click(chip)
    await waitFor(() => expect(rows()).toHaveLength(5))

    fireEvent.change(screen.getByRole('searchbox'), { target: { value: 'db' } })
    await waitFor(() => expect(rows().map((r) => r.getAttribute('data-key'))).toEqual(['PORT:4']))
    await waitFor(() => expect(window.location.search).toContain('n_q=db'), { timeout: 2000 })
    expect(window.location.search).toContain('tab=noc')   // uygulamanın anahtarına dokunulmadı
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: '' } })

    const paused = [...document.querySelectorAll('[data-slot="stat-item"]')].find((el) => /Paused|Duraklatılmış/.test(el.textContent))
    fireEvent.click(paused)
    await waitFor(() => expect(rows().map((r) => r.getAttribute('data-key'))).toEqual(['DNS:3']))
    expect(paused).toHaveAttribute('aria-pressed', 'true')
    await waitFor(() => expect(window.location.search).toContain('n_status=paused'), { timeout: 2000 })
  })

  it('süzgeçle eşleşme yoksa boş durum + "Filtreleri temizle"', async () => {
    window.history.replaceState(null, '', '/?tab=noc&n_q=hic-yok')
    await renderPage()
    expect(await screen.findByText(/No monitors match these filters|Süzgeçlerle eşleşen izleme yok/)).toBeInTheDocument()
    fireEvent.click(screen.getAllByRole('button', { name: /Clear filters|Filtreleri temizle/ }).at(-1))
    await waitFor(() => expect(rows()).toHaveLength(5))
  })
})

describe('NocCoveragePage — tek tık ve toplu', () => {
  it('tek tık: sunucu beklerken satır İYİMSER "kapsanıyor"; sunucu satırı gelince o uygulanır', async () => {
    const d = deferred()
    api.noc.setMonitor.mockReturnValueOnce(d.promise)
    await renderPage()
    await waitFor(() => expect(rows()).toHaveLength(5))
    fireEvent.click(within(rowOf('PING:1')).getByRole('button', { name: /— ping-a$/ }))
    expect(api.noc.setMonitor).toHaveBeenCalledWith('PING', 1, { enabled: true })
    await waitFor(() => expect(rowOf('PING:1')).toHaveAttribute('data-status', 'covered'))
    expect([...document.querySelectorAll('[data-slot="stat-item"] [data-slot="stat-value"]')].map((v) => v.textContent)).toEqual(['5', '2', '2', '1'])
    await act(async () => { d.resolve({ success: true, data: { ...ITEMS[0], noc_notify: true, covered: true, reason: null, group_names: ['NOC Nöbet'] } }) })
    await waitFor(() => expect(within(rowOf('PING:1')).getByText('NOC Nöbet')).toBeInTheDocument())
    expect(toastMock.success).toHaveBeenCalledWith(expect.stringMatching(/ping-a/))
  })

  it('tek tık hata: satır ESKİ hâline döner, hata toast\'ı sunucu mesajıyla', async () => {
    api.noc.setMonitor.mockResolvedValueOnce({ success: false, error: 'Yetkisiz' })
    await renderPage()
    await waitFor(() => expect(rows()).toHaveLength(5))
    fireEvent.click(within(rowOf('SSL:5')).getByRole('button', { name: /— www\.example\.com$/ }))
    await waitFor(() => expect(toastMock.error).toHaveBeenCalledWith(expect.stringMatching(/www\.example\.com.*Yetkisiz/)))
    expect(rowOf('SSL:5')).toHaveAttribute('data-status', 'MONITOR_OFF')
    expect(within(rowOf('SSL:5')).getByRole('button', { name: /— www\.example\.com$/ })).not.toBeDisabled()
  })

  it('toplu: yalnız açılabilir satırlar seçilir; bulk(items, true); atlanan satır geri alınır, liste sunucuyla eşitlenir', async () => {
    const after = ITEMS.map((it) => (it.id === 1 ? { ...it, noc_notify: true, covered: true, reason: null } : it))
    api.noc.coverage.mockResolvedValueOnce(ok()).mockResolvedValue(ok(after))
    api.noc.bulk.mockResolvedValueOnce({ success: true, data: { updated: 1, skipped: [{ type: 'SSL', id: 5, reason: 'FORBIDDEN' }] } })
    await renderPage()
    await waitFor(() => expect(rows()).toHaveLength(5))
    // Takım B (üye değil) ve bildirimi açık satırlarda kutu yok
    expect(within(rowOf('DNS:3')).queryByRole('checkbox')).toBeNull()
    expect(within(rowOf('HTTP:2')).queryByRole('checkbox')).toBeNull()
    fireEvent.click(screen.getByRole('checkbox', { name: /Select every monitor on this page|Bu sayfada açılabilecek tüm izlemeleri seç/ }))
    const bar = await waitFor(() => { const b = document.querySelector('[data-slot="noc-bulk-bar"]'); expect(b).not.toBeNull(); return b })
    expect(bar.textContent).toMatch(/2 (selected|seçili)/)
    fireEvent.click(within(bar).getByRole('button', { name: /\(2\)/ }))
    await waitFor(() => expect(api.noc.bulk).toHaveBeenCalledTimes(1))
    const [sent, enabled] = api.noc.bulk.mock.calls[0]
    expect(enabled).toBe(true)
    expect(sent.map((x) => `${x.type}:${x.id}`).sort()).toEqual(['PING:1', 'SSL:5'])
    // Atlama nedeni düz sözcükle (FORBIDDEN → "yetkiniz yok")
    await waitFor(() => expect(toastMock.error).toHaveBeenCalledWith(expect.stringMatching(/: 1\. .*(no permission|yetkiniz yok): 1/)))
    await waitFor(() => expect(api.noc.coverage).toHaveBeenCalledTimes(2))
    await waitFor(() => expect(rowOf('PING:1')).toHaveAttribute('data-status', 'covered'))
    expect(rowOf('SSL:5')).toHaveAttribute('data-status', 'MONITOR_OFF')
    expect(document.querySelector('[data-slot="noc-bulk-bar"]')).toBeNull()
  })

  it('toplu: sunucu UNCHANGED (zaten açık) atlarsa satır GERİ ALINMAZ ve kısmi hata sayılmaz', async () => {
    const pending = deferred()   // eşitleme yüklemesi askıda: görülen durum yalnız iyimser + geri alma
    api.noc.coverage.mockResolvedValueOnce(ok()).mockReturnValueOnce(pending.promise)
    api.noc.bulk.mockResolvedValueOnce({ success: true, data: { updated: 1, skipped: [{ type: 'PING', id: 1, reason: 'UNCHANGED' }] } })
    await renderPage()
    await waitFor(() => expect(rows()).toHaveLength(5))
    fireEvent.click(screen.getByRole('checkbox', { name: /^(Select|Seç:) ?ping-a$/ }))
    fireEvent.click(screen.getByRole('checkbox', { name: /^(Select|Seç:) ?www\.example\.com$/ }))
    fireEvent.click(within(document.querySelector('[data-slot="noc-bulk-bar"]')).getByRole('button', { name: /\(2\)/ }))
    await waitFor(() => expect(toastMock.success).toHaveBeenCalledWith(expect.stringMatching(/(already switched on|zaten açıktı): 1/)))
    expect(toastMock.error).not.toHaveBeenCalled()
    expect(rowOf('PING:1')).toHaveAttribute('data-status', 'covered')
    expect(rowOf('SSL:5')).toHaveAttribute('data-status', 'covered')
  })

  it('tek satır seçimi: satır kutusu (satırı ayıran adla) seçer/bırakır', async () => {
    await renderPage()
    await waitFor(() => expect(rows()).toHaveLength(5))
    const cb = screen.getByRole('checkbox', { name: /^(Select|Seç:) ?ping-a$/ })
    fireEvent.click(cb)
    await waitFor(() => expect(document.querySelector('[data-slot="noc-bulk-bar"]').textContent).toMatch(/1 (selected|seçili)/))
    fireEvent.click(cb)
    await waitFor(() => expect(document.querySelector('[data-slot="noc-bulk-bar"]')).toBeNull())
  })
})

describe('NocCoveragePage — durumlar ve yarış', () => {
  it('hiç aktif grup yok: global yönetici Ayarlar bağlantısı görür; kullanıcı "yöneticinize sorun"', async () => {
    api.noc.coverage.mockResolvedValue(ok(ITEMS, { ...SUMMARY, active_groups: 0 }))
    const nav = vi.fn()
    window.addEventListener('sm:navigate', nav)
    try {
      const r1 = await renderPage({ systemRole: 'ADMIN', globalAdmin: true })
      expect(await screen.findByText(/Settings → 24\/7 Monitoring Team|Ayarlar → 7\/24 İzleme Ekibi/)).toBeInTheDocument()
      fireEvent.click(screen.getByRole('button', { name: /Set up groups|Grupları tanımla/ }))
      expect(nav.mock.calls.at(-1)[0].detail).toEqual({ tab: 'settings', params: { sec: 'noc' } })
      r1.unmount()
      await renderPage()
      expect(await screen.findByText(/Ask your administrator|Yöneticinizden tanımlamasını isteyin/)).toBeInTheDocument()
      expect(screen.queryByRole('button', { name: /Set up groups|Grupları tanımla/ })).toBeNull()
      expect(screen.queryByRole('button', { name: /24\/7 settings|7\/24 ayarları/ })).toBeNull()
    } finally { window.removeEventListener('sm:navigate', nav) }
  })

  it('tümü kapsanıyorsa başarı durumu; görüş kapsamında izleme yoksa boş durum', async () => {
    api.noc.coverage.mockResolvedValueOnce(ok([ITEMS[1]], { ...SUMMARY, disabled_types: [] }))
    const r1 = await renderPage()
    expect(await screen.findByText(/Every active monitor is covered 24\/7|Tüm aktif izlemeler 7\/24 kapsamında/)).toBeInTheDocument()
    r1.unmount()
    api.noc.coverage.mockResolvedValueOnce(ok([], { ...SUMMARY, disabled_types: [] }))
    await renderPage()
    expect(await screen.findByText(/No monitors to show|Görebildiğiniz izleme yok/)).toBeInTheDocument()
    expect(document.querySelector('[data-slot="stat-item"]')).toBeNull()
  })

  it('yükleme hatası: bant + yeniden dene; ikinci deneme satırları getirir', async () => {
    api.noc.coverage.mockResolvedValueOnce({ success: false, error: 'Sunucu hatası' })
    await renderPage()
    expect(await screen.findByText('Sunucu hatası')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /Try again|Yeniden dene/ }))
    await waitFor(() => expect(rows()).toHaveLength(5))
    expect(screen.queryByText('Sunucu hatası')).toBeNull()
  })

  it('yarış (loadSeq): yavaş ESKİ yanıt, sonra başlayan yeni yüklemenin sonucunu EZMEZ', async () => {
    const a = deferred()
    const b = deferred()
    api.noc.coverage.mockReturnValueOnce(a.promise).mockReturnValueOnce(b.promise)
    render(<NocCoveragePage {...USER} />)
    fireEvent.click(screen.getByRole('button', { name: /^(Refresh|Yenile)$/ }))
    await act(async () => { b.resolve(ok([ITEMS[1]])) })
    await waitFor(() => expect(rows().map((r) => r.getAttribute('data-key'))).toEqual(['HTTP:2']))
    await act(async () => { a.resolve(ok()) })
    expect(rows().map((r) => r.getAttribute('data-key'))).toEqual(['HTTP:2'])
  })

  it('Pano damgası (refreshKey) değişince sessizce tazelenir; ilk damga ek istek üretmez', async () => {
    const { rerender } = render(<NocCoveragePage {...USER} refreshKey="t1" />)
    await waitFor(() => expect(rows()).toHaveLength(5))
    expect(api.noc.coverage).toHaveBeenCalledTimes(1)
    rerender(<NocCoveragePage {...USER} refreshKey="t2" />)
    await waitFor(() => expect(api.noc.coverage).toHaveBeenCalledTimes(2))
  })
})

describe('NocCoveragePage — takım arama listesi', () => {
  // Sunucu biçimi: is_member da döner (takımdan ayrılmış kişi listede kalabilir)
  const CALL = [
    { user_id: 11, display_name: 'Kişi A', title: 'Uzman', has_phone: true, is_member: true },
    { user_id: 12, display_name: 'Kişi B', title: null, has_phone: false, is_member: true },
  ]
  afterEach(() => { vi.clearAllMocks() })

  it('TEAM_ADMIN: sıra yukarı/aşağı, telefonu olmayan uyarısı, kaydet userIds SIRASIYLA; Vazgeç geri alır', async () => {
    api.noc.getCallList.mockResolvedValue({ success: true, data: CALL })
    api.noc.teamMembers.mockResolvedValue({ success: true, data: [...CALL, { user_id: 13, display_name: 'Kişi C', title: 'Müdür', has_phone: true }] })
    api.noc.saveCallList.mockResolvedValue({ success: true })
    await renderPage({ systemRole: 'TEAM_ADMIN' })
    const card = await waitFor(() => { const c = document.querySelector('[data-slot="noc-call-list"]'); expect(c.querySelectorAll('[data-slot="noc-cl-item"]')).toHaveLength(2); return c })
    expect(api.noc.getCallList).toHaveBeenCalledWith('1')
    expect(api.noc.teamMembers).toHaveBeenCalledWith('1')
    expect(within(card).getByText(/(People with no phone number in AD|AD’de telefonu olmayan kişi): 1/)).toBeInTheDocument()
    expect(card.querySelector('[data-slot="noc-cl-item"][data-user="12"] [data-slot="noc-cl-nophone"]')).not.toBeNull()
    expect(within(card).getByRole('button', { name: /(Move Kişi A up|Yukarı taşı: Kişi A)/ })).toBeDisabled()

    fireEvent.click(within(card).getByRole('button', { name: /(Move Kişi B up|Yukarı taşı: Kişi B)/ }))
    expect([...card.querySelectorAll('[data-slot="noc-cl-item"]')].map((li) => li.getAttribute('data-user'))).toEqual(['12', '11'])
    fireEvent.click(within(card).getByRole('button', { name: /Discard|Vazgeç/ }))
    expect([...card.querySelectorAll('[data-slot="noc-cl-item"]')].map((li) => li.getAttribute('data-user'))).toEqual(['11', '12'])

    fireEvent.click(within(card).getByRole('button', { name: /(Move Kişi A down|Aşağı taşı: Kişi A)/ }))
    fireEvent.click(within(card).getByRole('button', { name: /Save list|Listeyi kaydet/ }))
    await waitFor(() => expect(api.noc.saveCallList).toHaveBeenCalledWith('1', [12, 11]))   // SIRA korunur
    await waitFor(() => expect(toastMock.success).toHaveBeenCalled())
    await waitFor(() => expect(card.querySelector('[data-slot="settings-save-bar"]')).not.toHaveAttribute('data-dirty'))

    fireEvent.click(within(card).getByRole('button', { name: /(Remove Kişi A from the list|Listeden çıkar: Kişi A)/ }))
    fireEvent.click(within(card).getByRole('button', { name: /Save list|Listeyi kaydet/ }))
    await waitFor(() => expect(api.noc.saveCallList).toHaveBeenLastCalledWith('1', [12]))
  })

  it('USER (müdür değil): liste salt okunur — taşı/çıkar/ekle yok, üye listesi istenmez; takımdan ayrılan işaretli', async () => {
    api.noc.getCallList.mockResolvedValue({ success: true, data: [CALL[0], { ...CALL[1], is_member: false }] })
    await renderPage()
    const card = await waitFor(() => { const c = document.querySelector('[data-slot="noc-call-list"]'); expect(c.querySelectorAll('[data-slot="noc-cl-item"]')).toHaveLength(2); return c })
    expect(card.querySelector('[data-slot="noc-cl-item"][data-user="12"] [data-slot="noc-cl-notmember"]')).not.toBeNull()
    expect(card.querySelector('[data-slot="noc-cl-item"][data-user="11"] [data-slot="noc-cl-notmember"]')).toBeNull()
    expect(card.querySelector('[data-slot="noc-cl-readonly"]')).not.toBeNull()
    expect(within(card).queryByRole('button', { name: /(Move|taşı)/ })).toBeNull()
    expect(api.noc.teamMembers).not.toHaveBeenCalled()
    expect(card.querySelector('[data-slot="settings-save-bar"]')).toBeNull()
  })

  it('arama listesi en fazla 25 kişi: dolunca ekleme seçicisi kapalı ve "liste dolu"', async () => {
    const many = Array.from({ length: 25 }, (_, i) => ({ user_id: 100 + i, display_name: `Kişi ${i + 1}`, title: null, has_phone: true, is_member: true }))
    api.noc.getCallList.mockResolvedValue({ success: true, data: many })
    api.noc.teamMembers.mockResolvedValue({ success: true, data: [...many, { user_id: 999, display_name: 'Kişi Z', title: null, has_phone: true }] })
    await renderPage({ systemRole: 'TEAM_ADMIN' })
    const card = await waitFor(() => { const c = document.querySelector('[data-slot="noc-call-list"]'); expect(c.querySelectorAll('[data-slot="noc-cl-item"]')).toHaveLength(25); return c })
    const add = within(card).getByRole('combobox', { name: /Add a person|Kişi ekle/ })
    expect(add).toBeDisabled()
    expect(add.textContent).toMatch(/(The list is full|Liste dolu).*25/)
  })

  it('başlıkta 7/24’e giden en düşük seviye (sunucu özeti min_level)', async () => {
    api.noc.coverage.mockResolvedValue(ok(ITEMS, { ...SUMMARY, min_level: 'HIGH' }))
    await renderPage()
    await waitFor(() => expect(document.querySelector('[data-slot="noc-meta-level"]')).not.toBeNull())
    expect(document.querySelector('[data-slot="noc-meta-level"]').textContent).toMatch(/High and critical|Yüksek ve kritik/)
  })

  it('boş arama listesi: e-postada Takım Müdürü gösterileceği notu', async () => {
    await renderPage({ systemRole: 'TEAM_ADMIN' })
    expect(await screen.findByText(/No call list set up for Takım A|Takım A için arama listesi tanımlanmamış/)).toBeInTheDocument()
  })

  /** Takım rehberiyle (lider/müdür bilgisi) çiz — rehber yanıtı testin elinde (geç gelebilir). */
  async function renderWithDirectory(props = {}) {
    const utils = render(<TeamDirectoryProvider><NocCoveragePage {...USER} {...props} /></TeamDirectoryProvider>)
    await waitFor(() => expect(document.querySelector('[data-slot="noc-skeleton"]')).toBeNull())
    return utils
  }
  const DIR = [
    { id: 1, name: 'Takım A', leader_id: null, manager_id: null },
    { id: 2, name: 'Takım B', leader_id: null, manager_id: null },
    { id: 3, name: 'Takım C', leader_id: null, manager_id: 7 },   // kullanıcı 7 bu takımın ELLE ATANMIŞ müdürü, üyesi DEĞİL
  ]

  it('üye OLMAYAN takım müdürü/lideri kendi takımının listesine ulaşır ve düzenler (rehberdeki manager_id/leader_id)', async () => {
    window.history.replaceState(null, '', '/?tab=noc&n_ct=3')
    api.teams.directory.mockResolvedValue({ success: true, data: DIR })
    await renderWithDirectory()
    await waitFor(() => expect(api.noc.getCallList).toHaveBeenCalledWith('3'))
    await waitFor(() => expect(api.noc.teamMembers).toHaveBeenCalledWith('3'))
    const card = document.querySelector('[data-slot="noc-call-list"]')
    expect(card.querySelector('[data-slot="noc-cl-readonly"]')).toBeNull()
  })

  it('satır takımı YALNIZ üyesiyse seçenek: envanter satırının başka (SY) takımı n_ct ile bile açılmaz (403 yok); global görüntüleyici açar', async () => {
    window.history.replaceState(null, '', '/?tab=noc&n_ct=2')
    const { unmount } = await renderPage()
    await waitFor(() => expect(api.noc.getCallList).toHaveBeenCalled())
    expect(api.noc.getCallList.mock.calls.map(([id]) => id)).not.toContain('2')
    expect(api.noc.getCallList).toHaveBeenLastCalledWith('1')
    unmount()
    vi.clearAllMocks()
    window.history.replaceState(null, '', '/?tab=noc&n_ct=2')
    await renderPage({ systemRole: 'AUDIT', myTeamIds: [], myTeams: [] })
    await waitFor(() => expect(api.noc.getCallList).toHaveBeenCalledWith('2'))
  })

  it('takım rehberi GEÇ gelir ve kullanıcı liderse: üye listesi o an istenir (ekleme seçicisi dolar), ikinci kez istenmez', async () => {
    const dir = deferred()
    api.teams.directory.mockReturnValue(dir.promise)
    api.noc.teamMembers.mockResolvedValue({ success: true, data: [{ user_id: 13, display_name: 'Kişi C', title: null, has_phone: true }] })
    await renderWithDirectory()
    await waitFor(() => expect(api.noc.getCallList).toHaveBeenCalledWith('1'))
    const card = document.querySelector('[data-slot="noc-call-list"]')
    await waitFor(() => expect(card.querySelector('[data-slot="noc-cl-readonly"]')).not.toBeNull())
    expect(api.noc.teamMembers).not.toHaveBeenCalled()
    await act(async () => { dir.resolve({ success: true, data: [{ id: 1, name: 'Takım A', leader_id: 7, manager_id: null }] }) })
    await waitFor(() => expect(api.noc.teamMembers).toHaveBeenCalledWith('1'))
    const add = await waitFor(() => within(card).getByRole('combobox', { name: /Add a person|Kişi ekle/ }))
    await waitFor(() => expect(add).toBeEnabled())
    expect(card.querySelector('[data-slot="noc-cl-readonly"]')).toBeNull()
    expect(api.noc.teamMembers).toHaveBeenCalledTimes(1)
  })

  it('kaydet 403: kart SALT OKUNURA döner, kaydedilmemiş sıra geri alınır, nedeni yazılır', async () => {
    api.noc.getCallList.mockResolvedValue({ success: true, data: CALL })
    api.noc.teamMembers.mockResolvedValue({ success: true, data: CALL })
    api.noc.saveCallList.mockResolvedValue({ success: false, error: 'Arama listesini yalnız takımın yöneticisi/müdürü düzenleyebilir', status: 403 })
    await renderPage({ systemRole: 'TEAM_ADMIN' })
    const card = await waitFor(() => { const c = document.querySelector('[data-slot="noc-call-list"]'); expect(c.querySelectorAll('[data-slot="noc-cl-item"]')).toHaveLength(2); return c })
    fireEvent.click(within(card).getByRole('button', { name: /(Move Kişi B up|Yukarı taşı: Kişi B)/ }))
    fireEvent.click(within(card).getByRole('button', { name: /Save list|Listeyi kaydet/ }))
    await waitFor(() => expect(card.querySelector('[data-slot="noc-cl-denied"]')).not.toBeNull())
    expect(card.querySelector('[data-slot="noc-cl-denied"]').textContent).toMatch(/can.t edit this team.s call list|düzenleme yetkiniz yok/)
    expect(within(card).queryByRole('button', { name: /(Move|taşı)/ })).toBeNull()
    expect(card.querySelector('[data-slot="settings-save-bar"]')).toBeNull()
    expect([...card.querySelectorAll('[data-slot="noc-cl-item"]')].map((li) => li.getAttribute('data-user'))).toEqual(['11', '12'])
    expect(toastMock.error).toHaveBeenCalled()
  })

  it('yükleme 403: anlaşılır, çevrilmiş neden', async () => {
    api.noc.getCallList.mockResolvedValue({ success: false, error: 'HTTP 403 raw', status: 403 })
    await renderPage()
    const card = await waitFor(() => { const c = document.querySelector('[data-slot="noc-call-list"]'); expect(c.querySelector('[data-slot="alert"]')).not.toBeNull(); return c })
    expect(card.textContent).toMatch(/You don.t have access to this team.s call list|arama listesini görme yetkiniz yok/)
    expect(card.textContent).not.toContain('HTTP 403 raw')
  })
})

describe('NocCoveragePage — toplu >500 (parçalı) kısmi hata', () => {
  it('1. parça yazıldı, 2. parça düştü → YALNIZ düşen parçanın satırı geri alınır; liste sunucuyla eşitlenir', async () => {
    window.history.replaceState(null, '', '/?tab=noc&n_ps=200')
    const many = Array.from({ length: 501 }, (_, i) => ({
      type: 'PING', id: 1000 + i, name: `m-${String(i).padStart(3, '0')}`, target: `h${i}.example.com`, team_id: 1, team_name: 'Takım A',
      active: true, noc_notify: false, covered: false, reason: 'MONITOR_OFF', group_names: [],
    }))
    const sync = deferred()   // eşitleme yüklemesi askıda: görülen durum yalnız iyimser + geri alma
    api.noc.coverage.mockResolvedValueOnce(ok(many, { ...SUMMARY, disabled_types: [] })).mockReturnValueOnce(sync.promise)
    api.noc.bulk.mockResolvedValueOnce({ success: true, data: { updated: 500, skipped: [] } }).mockRejectedValueOnce(new Error('ağ hatası'))
    await renderPage()
    await waitFor(() => expect(rows()).toHaveLength(200))
    const selectPage = () => fireEvent.click(screen.getByRole('checkbox', { name: /Select every monitor on this page|Bu sayfada açılabilecek tüm izlemeleri seç/ }))
    const next = () => fireEvent.click(screen.getByRole('button', { name: /^(Next|Sonraki)$/ }))
    selectPage(); next()
    await waitFor(() => expect(rowOf('PING:1200')).not.toBeNull())
    selectPage(); next()
    await waitFor(() => expect(rows()).toHaveLength(101))
    selectPage()
    const bar = await waitFor(() => { const b = document.querySelector('[data-slot="noc-bulk-bar"]'); expect(b.textContent).toMatch(/501 (selected|seçili)/); return b })
    fireEvent.click(within(bar).getByRole('button', { name: /[(]501[)]/ }))
    await waitFor(() => expect(api.noc.bulk).toHaveBeenCalledTimes(2))
    expect(api.noc.bulk.mock.calls.map(([items]) => items.length)).toEqual([500, 1])
    await waitFor(() => expect(toastMock.error).toHaveBeenCalled())
    await waitFor(() => expect(api.noc.coverage).toHaveBeenCalledTimes(2))   // eşitleme istendi
    // Sıra: kapsanmayan önce → geri alınan tek satır (2. parça) ilk sayfanın başında; 1. parçadakiler "kapsanıyor" KALIR
    fireEvent.click(screen.getByRole('button', { name: /^(First page|İlk sayfa)$/ }))
    await waitFor(() => expect(rows()[0]).toHaveAttribute('data-key', 'PING:1500'))
    expect(rows()[0]).toHaveAttribute('data-status', 'MONITOR_OFF')
    expect(rows().slice(1).every((r) => r.getAttribute('data-status') === 'covered')).toBe(true)
  }, 60_000)
})
