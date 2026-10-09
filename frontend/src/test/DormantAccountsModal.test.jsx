import { StrictMode } from 'react'
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within, act } from './test-utils.jsx'
import DormantAccountsModal from '../components/admin/useractivity/dormant/DormantAccountsModal.jsx'
import UserActivityPanel from '../components/admin/useractivity/UserActivityPanel.jsx'

/**
 * Atıl hesaplar görünümü (2026-10-09 yeniden tasarım): istatistik kutucukları + dağılım kartları (süzgeç düğmeleri),
 * arama / fasetler / sıralama, tablo (lg+) ↔ kart (dar), satır → kullanıcı detayı, CSV, sonraki adım (yalnız yönetici;
 * toplu pasife alma yalnız GLOBAL yönetici, ön doldurulmuş derin bağlantı — hesap DEĞİŞTİRMEZ), boş / kırpılmış / hata
 * durumları, kimlik izi yok. TAM liste pencere açılınca `/user-activity/dormant`'tan BİR KEZ gelir (yoklanan özet ilk
 * 500'ü taşır): yüklenirken özet satırları, hata + Yeniden dene, üst üste istek yok, bayat yanıt ezmez.
 * Sorgular rol / erişilebilir ad / data-slot'a bağlı.
 */
const { apiMock, csvSpy } = vi.hoisted(() => ({
  apiMock: { admin: { getLoginSeries: vi.fn(), getUserTimeline: vi.fn(), ackAnomaly: vi.fn(), terminateUserSession: vi.fn(), getDormantAccounts: vi.fn() } },
  csvSpy: vi.fn(),
}))
vi.mock('../api/client', () => ({ api: apiMock, formatDate: (s) => s ?? '', formatDateSec: (s) => s ?? '', formatDateOnly: (s) => (s ?? '').slice(0, 10) }))
vi.mock('../components/admin/LoginActivityChart.jsx', () => ({ default: () => <div data-testid="login-chart" /> }))
vi.mock('../utils/csvExport.js', async (importOriginal) => ({ ...(await importOriginal()), downloadCsv: csvSpy }))
import { api } from '../api/client'

const DAY = 86_400_000
const ago = (d) => new Date(Date.now() - d * DAY).toISOString().slice(0, 19)
const DORMANT = [
  { username: 'zeynep', user_id: 'u-1', display_name: 'Zeynep Kaya (Bölüm X)', system_role: 'USER', team_id: 5, team_name: 'Takım A', auth_source: 'LDAP', last_login_at: ago(400), inactive_days: 400, created_at: ago(900), account_age_days: 900, has_email: true, has_photo: false },
  { username: 'ali', user_id: 'u-2', display_name: 'Ali Veli', system_role: 'AUDIT', team_id: 9, team_name: 'Takım B', auth_source: 'LOCAL', last_login_at: ago(200), inactive_days: 200, has_email: false, permanent_lock: true, has_photo: false },
  { username: 'cagla', user_id: 'u-3', display_name: 'Çağla Demir', system_role: 'USER', team_id: 5, team_name: 'Takım A', auth_source: 'LDAP', last_login_at: ago(100), inactive_days: 100, has_email: true, has_photo: false },
  { username: 'omer', user_id: 'u-4', display_name: 'Ömer Şahin', system_role: 'TEAM_ADMIN', team_id: null, team_name: null, auth_source: 'LOCAL', last_login_at: ago(45), inactive_days: 45, has_email: true, has_photo: false },
  { username: 'bora', user_id: 'u-5', display_name: 'Bora Ak', system_role: 'USER', team_id: 9, team_name: 'Takım B', auth_source: 'LDAP', last_login_at: null, inactive_days: null, account_age_days: 300, has_email: true, has_photo: false },
  { username: 'ece', user_id: 'u-6', display_name: 'Ece Su', system_role: 'USER', team_id: 5, team_name: 'Takım A', auth_source: 'LOCAL', last_login_at: null, inactive_days: null, account_age_days: 3, has_email: true, has_photo: false },
]
const META = { total: 6, cap: 5000, truncated: false, threshold_days: 30 }
const DATA = {
  generated_at: '2026-10-09T09:00:00', office_hours: { start: 8, end: 20 }, identity_masked: true,
  summary: { active_count: 1, logins_24h: 1, failed_24h: 0, anomalies_24h: 0, unique_users_24h: 1, logins_7d: 3, failed_7d: 0, anomalies_7d: 0, unique_users_7d: 1, total_users: 24, dormant_30d: 6, dormant_90d: 5, never_logged_in: 2 },
  active_users: [], login_status: [], series: { day: [] }, top_sources: undefined,
  anomalies: { total: 0, unacked_recent: 0, counts: {}, recent: [] }, role_team: { by_role: [], by_team: [] }, heatmaps: [],
  usage: { days: 7, pages: [], users: [], teams: [] },
  details: { logins: [], failed: [], anomalies: [], unique_users: [], dormant: DORMANT, dormant_meta: { ...META, cap: 500 } },
}
/** `/user-activity/dormant` yanıtı (tam liste). */
const fullRes = (rows = DORMANT, meta = META) => ({ success: true, data: { rows, meta, generated_at: '2026-10-09T09:00:10' } })
function deferred() {
  let resolve, reject
  const promise = new Promise((res, rej) => { resolve = res; reject = rej })
  return { promise, resolve, reject }
}

function setWidth(w) {
  Object.defineProperty(window, 'innerWidth', { configurable: true, writable: true, value: w })
  act(() => { window.dispatchEvent(new Event('resize')) })
}
const dialog = () => document.querySelector('[data-slot="dormant-title"]').closest('[role="dialog"]')
const title = () => document.querySelector('[data-slot="dormant-title"]').textContent
const stats = () => within(dialog()).getByRole('region', { name: /Atıl hesap istatistikleri|Dormant account statistics/ })
const tile = (id) => dialog().querySelector(`[data-stat="${id}"]`)
const row = (id) => dialog().querySelector(`[data-row="${id}"]`)
const loadingLine = () => document.querySelector('[data-slot="dormant-loading"]')
const refreshBtn = () => document.querySelector('[data-slot="dormant-refresh"]')
const tableUsers = () => [...within(dialog()).getByTestId('dormant-table').querySelectorAll('tbody tr')].map((r) => r.getAttribute('data-user'))
const cardUsers = () => [...dialog().querySelectorAll('[data-slot="dormant-card"]')].map((c) => c.getAttribute('data-user'))

function mount(props = {}) {
  const cb = { onUser: vi.fn(), onRefresh: vi.fn(), onClose: vi.fn() }
  const utils = render(<DormantAccountsModal data={DATA} isAdmin globalAdmin {...cb} {...props} />)
  return { ...cb, ...utils }
}
/** Pencereyi aç ve tam listenin gelmesini bekle (varsayılan yanıt = özetle aynı satırlar). */
async function open(props = {}) {
  const r = mount(props)
  await waitFor(() => expect(loadingLine()).toBeNull())
  return r
}

let navEvents = []
const onNav = (e) => navEvents.push(e.detail)
beforeEach(() => {
  vi.clearAllMocks(); navEvents = []
  try { localStorage.clear() } catch { /* yok */ }
  window.addEventListener('sm:navigate', onNav)
  api.admin.getDormantAccounts.mockResolvedValue(fullRes())
  setWidth(1280)
})
afterEach(() => { window.removeEventListener('sm:navigate', onNav); setWidth(1024) })

describe('DormantAccountsModal — istatistikler', () => {
  it('kutucuklar: toplam + aktif hesaplar içindeki pay, hiç girmemiş (+ yeni açılan), 180+ gün, ortanca / en uzun', async () => {
    await open()
    expect(within(dialog()).getByText(/Atıl hesap · 6|Dormant accounts · 6/)).toBeInTheDocument()
    stats()
    expect(tile('total').textContent).toMatch(/6/)
    expect(tile('total').textContent).toMatch(/%25|25%/)                          // 6 / 24
    expect(tile('total').textContent).toMatch(/24/)
    expect(tile('never').textContent).toMatch(/2/)
    expect(tile('never').textContent).toMatch(/1 tanesi son 30 günde açıldı|1 opened in the last 30 days/)
    expect(tile('long').textContent).toMatch(/2/)
    expect(tile('long').textContent).toMatch(/1 tanesi bir yılı aştı|1 of them for over a year/)
    expect(tile('median').textContent).toMatch(/150 gün|150 days/)             // [45,100,200,400]
    expect(tile('median').textContent).toMatch(/400 gün|400 days/)
    expect(dialog().querySelector('[data-fact="no-email"]').textContent).toMatch(/1/)
    expect(dialog().querySelector('[data-fact="locked"]').textContent).toMatch(/1/)
  })

  it('dağılım kartları: kovalar sabit sırada, kaynak, rol, takımlar (takımsız ayrı) — sayı + pay', async () => {
    await open()
    const buckets = dialog().querySelector('[data-breakdown="bucket"]')
    expect([...buckets.querySelectorAll('[data-row]')].map((b) => b.getAttribute('data-row')))
      .toEqual(['bucket-d30', 'bucket-d90', 'bucket-d180', 'bucket-d365', 'bucket-never'])
    expect(row('bucket-never').textContent).toMatch(/Hiç girmemiş|Never signed in/)
    expect(row('bucket-never').textContent).toMatch(/2.*(%33|33%)/)
    expect(row('source-LDAP').textContent).toMatch(/LDAP\s*3/)
    expect(row('source-LOCAL').textContent).toMatch(/(Yerel|Local)\s*3/)
    expect(row('role-USER').textContent).toMatch(/USER\s*4/)
    expect(row('team-5').textContent).toMatch(/Takım A\s*3/)
    expect(row('team-none').textContent).toMatch(/(Takımsız|No team)\s*1/)
  })

  it('kutucuk ve dağılım satırları SÜZGEÇ düğmesidir (aria-pressed); Toplam hepsini temizler', async () => {
    await open()
    expect(tile('total')).toHaveAttribute('aria-pressed', 'true')
    fireEvent.click(tile('never'))
    expect(tile('never')).toHaveAttribute('aria-pressed', 'true')
    expect(tile('total')).toHaveAttribute('aria-pressed', 'false')
    expect(row('bucket-never')).toHaveAttribute('aria-pressed', 'true')   // aynı süzgeç → dağılım satırı da basılı
    expect(tableUsers()).toEqual(['bora', 'ece'])
    fireEvent.click(tile('never'))
    expect(tableUsers()).toHaveLength(6)
    fireEvent.click(row('source-LOCAL'))
    expect(tableUsers()).toEqual(['ali', 'omer', 'ece'])
    fireEvent.click(row('team-5'))                                        // fasetler arası VE
    expect(tableUsers()).toEqual(['ece'])
    fireEvent.click(tile('total'))
    expect(tableUsers()).toHaveLength(6)
    fireEvent.click(tile('long'))
    expect(tableUsers()).toEqual(['zeynep', 'ali'])
    // istatistikler bütün listeden: süzgeçten bağımsız
    expect(tile('total').textContent).toMatch(/6/)
  })
})

describe('DormantAccountsModal — liste', () => {
  it('varsayılan sıra en uzun süredir girmeyen önce; satır hücreleri: gün + dilim, hiç girmemiş + yeni hesap, takım, rol/kaynak', async () => {
    await open()
    expect(tableUsers()).toEqual(['zeynep', 'bora', 'ali', 'cagla', 'omer', 'ece'])
    const table = within(dialog()).getByTestId('dormant-table')
    const z = table.querySelector('tr[data-user="zeynep"]')
    expect(z.querySelector('[data-part="name"]').textContent).toBe('Zeynep Kaya')
    expect(z.querySelector('[data-part="meta"]').textContent).toBe('zeynep · Bölüm X')
    expect(z.textContent).toMatch(/400 gün|400 days/)
    expect(z.querySelector('[data-bucket-badge="d365"]')).not.toBeNull()
    expect(z.textContent).toContain('Takım A')
    expect(z.textContent).toMatch(/LDAP/)
    const ece = table.querySelector('tr[data-user="ece"]')
    expect(ece.querySelector('[data-status="never"]')).not.toBeNull()
    expect(ece.querySelector('[data-new-account]')).not.toBeNull()
    const bora = table.querySelector('tr[data-user="bora"]')
    expect(bora.querySelector('[data-new-account]')).toBeNull()
    expect(bora.textContent).toMatch(/Hesap yaşı: 300 gün|Account age: 300 days/)
    expect(within(dialog()).getByText(/Gösterilen: 6 \/ 6|Showing 6 of 6/)).toBeInTheDocument()
  })

  it('arama + faset (Popover + Command) + etkin süzgeç çipi; sıralama başlığı ve seçici', async () => {
    await open()
    fireEvent.change(within(dialog()).getByRole('searchbox', { name: /^Ara$|^Search$/ }), { target: { value: 'takım b' } })
    expect(tableUsers()).toEqual(['bora', 'ali'])
    expect(within(dialog()).getByText(/Atıl hesap · 2 \/ 6|Dormant accounts · 2 \/ 6/)).toBeInTheDocument()
    const chips = within(dialog()).getByRole('group', { name: /Etkin süzgeçler|Active filters/ })
    fireEvent.click(within(chips).getByRole('button', { name: /(Süzgeci kaldır|Remove filter): (Ara|Search)/ }))
    expect(tableUsers()).toHaveLength(6)
    const bar = within(dialog()).getByRole('group', { name: /^Süzgeçler$|^Filters$/ })
    fireEvent.click(within(bar).getByRole('button', { name: /^Hareketsizlik|^Inactivity/ }))
    fireEvent.click(screen.getByRole('option', { name: /365\+/ }))
    fireEvent.click(screen.getByRole('option', { name: /90–179/ }))
    expect(tableUsers()).toEqual(['zeynep', 'cagla'])
    const chips2 = within(dialog()).getByRole('group', { name: /Etkin süzgeçler|Active filters/ })
    expect(within(chips2).getAllByRole('button')).toHaveLength(3)              // iki çip + temizle
    fireEvent.click(within(chips2).getByRole('button', { name: /Süzgeçleri temizle|Clear filters/ }))
    expect(tableUsers()).toHaveLength(6)
    // sıralama: başlık (Kişi → ad A→Z) ve seçici
    const th = within(dialog()).getByTestId('dormant-table').querySelectorAll('th[aria-sort]')
    fireEvent.click(within(th[0]).getByRole('button'))
    expect(tableUsers()).toEqual(['ali', 'bora', 'cagla', 'ece', 'omer', 'zeynep'])
    fireEvent.change(within(dialog()).getByRole('combobox', { name: /^Sırala$|^Sort by$/ }), { target: { value: 'team_asc' } })
    expect(tableUsers()).toEqual(['zeynep', 'cagla', 'ece', 'bora', 'ali', 'omer'])
  })

  it('satır / ad düğmesi kullanıcı detayını açar (onUser satırla); CSV görünen listeyi indirir', async () => {
    const { onUser } = await open()
    const table = within(dialog()).getByTestId('dormant-table')
    fireEvent.click(within(table.querySelector('tr[data-user="ali"]')).getByRole('button', { name: /Ali Veli/ }))
    expect(onUser).toHaveBeenCalledWith(expect.objectContaining({ username: 'ali', user_id: 'u-2' }))
    fireEvent.click(table.querySelector('tr[data-user="omer"] td'))
    expect(onUser).toHaveBeenLastCalledWith(expect.objectContaining({ username: 'omer' }))
    fireEvent.click(row('bucket-never'))
    fireEvent.click(within(dialog()).getByRole('button', { name: /^(CSV indir|Export CSV)$/ }))
    expect(csvSpy).toHaveBeenCalledTimes(1)
    const [name, csv] = csvSpy.mock.calls[0]
    expect(name).toMatch(/^dormant-accounts-\d{8}-\d{4}\.csv$/)
    expect(csv.trim().split('\r\n')).toHaveLength(3)                            // başlık + 2 hiç girmemiş
  })

  it('dar ekranda kart listesi (stretched ad düğmesi), süzgeçler Sheet\'te; kart tıklaması detayı açar', async () => {
    setWidth(390)
    const { onUser } = await open()
    expect(within(dialog()).queryByTestId('dormant-table')).toBeNull()
    // telefonda dağılım kartları KAPALI başlar (kutucuklar görünür); tetikle açılır
    expect(dialog().querySelector('[data-breakdown]')).toBeNull()
    expect(tile('total')).not.toBeNull()
    fireEvent.click(within(dialog()).getByRole('button', { name: /^(Dağılımlar|Breakdown)/ }))
    expect(dialog().querySelector('[data-breakdown="bucket"]')).not.toBeNull()
    expect(cardUsers()).toEqual(['zeynep', 'bora', 'ali', 'cagla', 'omer', 'ece'])
    fireEvent.click(within(dialog().querySelector('[data-slot="dormant-card"][data-user="bora"]')).getByRole('button', { name: /Bora Ak/ }))
    expect(onUser).toHaveBeenCalledWith(expect.objectContaining({ username: 'bora' }))
    fireEvent.click(within(dialog()).getByRole('button', { name: /^Süzgeçler$|^Filters$/ }))
    const sheet = await waitFor(() => { const el = document.querySelector('[data-slot="dormant-filter-sheet"]'); expect(el).not.toBeNull(); return el })
    fireEvent.click(within(sheet).getByRole('button', { name: /(Yerel|Local) \(3\)/ }))
    expect(cardUsers()).toEqual(['ali', 'omer', 'ece'])
  })
})

describe('DormantAccountsModal — tam liste (açılışta bir kez, yoklama yok)', () => {
  it('açılışta BİR istek; gelene kadar özetin ilk satırları + "yükleniyor"; gelince istatistik / sayı / CSV tam listeden', async () => {
    const d = deferred()
    api.admin.getDormantAccounts.mockReturnValueOnce(d.promise)
    const preview = { ...DATA, details: { ...DATA.details, dormant: DORMANT.slice(0, 3), dormant_meta: { total: 6, cap: 3, truncated: true, threshold_days: 30 } } }
    mount({ data: preview })
    expect(api.admin.getDormantAccounts).toHaveBeenCalledTimes(1)
    expect(loadingLine().textContent).toMatch(/Tam liste yükleniyor|Loading the full list/)
    expect(loadingLine().textContent).toMatch(/3 of 6|6 hesabın ilk 3/)            // ilk 3 / 6
    expect(title()).toMatch(/· 3$/)
    expect(dialog().querySelector('[data-slot="dormant-truncated"]')).toBeNull()   // yüklenirken bant yerine satır
    expect(refreshBtn()).toBeDisabled()
    fireEvent.click(refreshBtn())                                                  // kapalı düğme: yeni istek yok
    expect(api.admin.getDormantAccounts).toHaveBeenCalledTimes(1)

    await act(async () => { d.resolve(fullRes()) })
    await waitFor(() => expect(loadingLine()).toBeNull())
    expect(title()).toMatch(/· 6$/)
    expect(tile('total').textContent).toMatch(/6/)
    expect(dialog().querySelector('[data-slot="dormant-truncated"]')).toBeNull()
    fireEvent.click(within(dialog()).getByRole('button', { name: /^(CSV indir|Export CSV)$/ }))
    expect(csvSpy.mock.calls[0][1].trim().split('\r\n')).toHaveLength(7)          // başlık + 6
    // açık pencere yoklamaz: bekleme süresince yeni istek yok
    await new Promise((r) => setTimeout(r, 30))
    expect(api.admin.getDormantAccounts).toHaveBeenCalledTimes(1)
  })

  it('hata: bant + Yeniden dene; özet satırları kalır; yeniden deneme başarılı olunca bant kalkar', async () => {
    api.admin.getDormantAccounts
      .mockRejectedValueOnce(new Error('Sunucuya ulaşılamadı. Ağ bağlantınızı kontrol edip yeniden deneyin.'))
      .mockResolvedValueOnce({ success: false, error: 'Atıl hesap listesi şu an hazırlanamıyor; biraz sonra yeniden deneyin.' })
      .mockResolvedValueOnce(fullRes())
    mount()
    const banner = await waitFor(() => { const el = document.querySelector('[data-slot="dormant-load-error"]'); expect(el).not.toBeNull(); return el })
    expect(banner.textContent).toMatch(/tam listesi yüklenemedi|full dormant account list could not be loaded/)
    expect(banner.textContent).toContain('Sunucuya ulaşılamadı')
    expect(tableUsers()).toHaveLength(6)                                          // özet satırları görünür kalır
    fireEvent.click(within(banner).getByRole('button', { name: /Yeniden dene|Try again/ }))
    await waitFor(() => expect(document.querySelector('[data-slot="dormant-load-error"]')?.textContent || '').toContain('hazırlanamıyor'))
    fireEvent.click(within(document.querySelector('[data-slot="dormant-load-error"]')).getByRole('button', { name: /Yeniden dene|Try again/ }))
    await waitFor(() => expect(document.querySelector('[data-slot="dormant-load-error"]')).toBeNull())
    expect(api.admin.getDormantAccounts).toHaveBeenCalledTimes(3)
    expect(loadingLine()).toBeNull()
  })

  it('pencerenin Yenile düğmesi tam listeyi (ve panel özetini) yeniden ister; kırpılmış tam liste bandı gösterir', async () => {
    const { onRefresh } = await open()
    api.admin.getDormantAccounts.mockResolvedValueOnce(fullRes(DORMANT.slice(0, 2), { total: 5200, cap: 5000, truncated: true, threshold_days: 30 }))
    fireEvent.click(refreshBtn())
    expect(onRefresh).toHaveBeenCalledTimes(1)
    await waitFor(() => expect(title()).toMatch(/· 2$/))
    expect(api.admin.getDormantAccounts).toHaveBeenCalledTimes(2)
    expect(dialog().querySelector('[data-slot="dormant-truncated"]').textContent).toMatch(/5000.*5200/)
  })

  it('bayat yanıt ezmez: AYNI bileşenin eski isteği (StrictMode bağla-çöz-bağla) yeni yanıtın üstüne yazmaz', async () => {
    // StrictMode etkiyi bağla → temizle → yeniden bağla diye koşar; ref'ler korunur → iki istek aynı durumun sahibi.
    // Sıra koruması olmasa geç gelen A (1 satır) yeni B'yi (4 satır) ezerdi.
    const a = deferred(), b = deferred()
    api.admin.getDormantAccounts.mockReturnValueOnce(a.promise).mockReturnValueOnce(b.promise)
    render(<StrictMode><DormantAccountsModal data={DATA} isAdmin globalAdmin onClose={vi.fn()} /></StrictMode>)
    expect(api.admin.getDormantAccounts).toHaveBeenCalledTimes(2)
    await act(async () => { b.resolve(fullRes(DORMANT.slice(0, 4))) })
    await waitFor(() => expect(title()).toMatch(/· 4$/))
    await act(async () => { a.resolve(fullRes(DORMANT.slice(0, 1))) })           // geç gelen ESKİ yanıt
    await new Promise((r) => setTimeout(r, 10))
    expect(title()).toMatch(/· 4$/)
    expect(tableUsers()).toHaveLength(4)
  })

  it('bayat yanıt ezmez: kapanmış pencerenin geç gelen yanıtı yeni açılan pencereye sızmaz', async () => {
    const a = deferred(), b = deferred()
    api.admin.getDormantAccounts.mockReturnValueOnce(a.promise).mockReturnValueOnce(b.promise)
    const first = mount()
    first.unmount()                                                              // A yoldayken pencere kapandı
    mount()
    expect(api.admin.getDormantAccounts).toHaveBeenCalledTimes(2)
    await act(async () => { b.resolve(fullRes(DORMANT.slice(0, 4))) })
    await waitFor(() => expect(title()).toMatch(/· 4$/))
    await act(async () => { a.resolve(fullRes(DORMANT.slice(0, 1))) })
    await new Promise((r) => setTimeout(r, 10))
    expect(title()).toMatch(/· 4$/)
  })
})

describe('DormantAccountsModal — sonraki adım ve yetki', () => {
  it('global yönetici: toplu pasife alma ön doldurulmuş derin bağlantıyla açılır (hesap DEĞİŞTİRMEZ) + Kullanıcılar listesi', async () => {
    const { onClose } = await open()
    const next = dialog().querySelector('[data-slot="dormant-next"]')
    expect(next.textContent).toMatch(/30\+ gündür giriş yok|no sign-in for 30\+ days/)
    fireEvent.click(tile('long'))
    fireEvent.click(row('source-LDAP'))
    expect(dialog().querySelector('[data-slot="dormant-next-bulk-desc"]').textContent).toMatch(/180\+/)
    fireEvent.click(within(next).getByRole('button', { name: /Toplu pasife almada incele|Review in bulk deactivation/ }))
    expect(onClose).toHaveBeenCalled()
    expect(navEvents.at(-1)).toEqual({ tab: 'admin', params: { g_tab: 'users', g_bd: '1', g_bd_days: '180', g_bd_never: '0', g_bd_src: 'LDAP' } })
    fireEvent.click(within(next).getByRole('button', { name: /Kullanıcılar listesinde aç|Open in the Users list/ }))
    expect(navEvents.at(-1)).toEqual({ tab: 'admin', params: { g_tab: 'users', g_dormant: '180' } })
    expect(api.admin.terminateUserSession).not.toHaveBeenCalled()
  })

  it('kapsamlı yönetici: yalnız Kullanıcılar listesi; AUDIT / kullanıcı: sonraki adım kartı yok', async () => {
    const { unmount } = await open({ globalAdmin: false })
    const next = dialog().querySelector('[data-slot="dormant-next"]')
    expect(within(next).queryByRole('button', { name: /Toplu pasife almada incele|Review in bulk deactivation/ })).toBeNull()
    expect(within(next).getByRole('button', { name: /Kullanıcılar listesinde aç|Open in the Users list/ })).toBeInTheDocument()
    unmount()
    await open({ isAdmin: false, globalAdmin: false })
    expect(dialog().querySelector('[data-slot="dormant-next"]')).toBeNull()
  })
})

describe('DormantAccountsModal — durumlar', () => {
  it('boş liste: başarı durumu (istatistik / süzgeç yok); kırpılmış tam liste: uyarı bandı', async () => {
    api.admin.getDormantAccounts.mockResolvedValueOnce(fullRes([], { total: 0, cap: 5000, truncated: false, threshold_days: 30 }))
    const { unmount } = await open({ data: { ...DATA, details: { ...DATA.details, dormant: [], dormant_meta: { total: 0, cap: 500, truncated: false } } } })
    expect(dialog().querySelector('[data-slot="empty"][data-tone="success"]')).not.toBeNull()
    expect(dialog().querySelector('[data-slot="dormant-stats"]')).toBeNull()
    expect(within(dialog()).getByRole('button', { name: /^(CSV indir|Export CSV)$/ })).toBeDisabled()
    unmount()
    api.admin.getDormantAccounts.mockResolvedValueOnce(fullRes(DORMANT, { total: 7200, cap: 5000, truncated: true, threshold_days: 30 }))
    await open()
    expect(dialog().querySelector('[data-slot="dormant-truncated"]').textContent).toMatch(/5000.*7200/)
  })

  it('özet boşken tam liste gelene kadar "yükleniyor" (yanlış "atıl hesap yok" göstermez)', async () => {
    const d = deferred()
    api.admin.getDormantAccounts.mockReturnValueOnce(d.promise)
    mount({ data: { ...DATA, details: { ...DATA.details, dormant: [], dormant_meta: { total: 0, cap: 500, truncated: false } } } })
    expect(dialog().querySelector('[data-slot="empty"][data-tone="success"]')).toBeNull()
    expect(dialog().querySelector('[data-slot="loading-block"]')).not.toBeNull()
    await act(async () => { d.resolve(fullRes()) })
    await waitFor(() => expect(title()).toMatch(/· 6$/))
  })

  it('süzgeçle eşleşme yoksa "temizle" eylemli boş durum; veri yokken yükleniyor, ikisi de gelmezse tekrar dene', async () => {
    const { unmount, onRefresh } = await open()
    fireEvent.change(within(dialog()).getByRole('searchbox', { name: /^Ara$|^Search$/ }), { target: { value: 'yok-böyle-biri' } })
    const empty = dialog().querySelector('[data-slot="empty"][data-tone="neutral"]')
    fireEvent.click(within(empty).getByRole('button', { name: /Süzgeçleri temizle|Clear filters/ }))
    expect(tableUsers()).toHaveLength(6)
    unmount()
    api.admin.getDormantAccounts.mockReturnValueOnce(new Promise(() => {}))      // hiç dönmeyen istek
    const r1 = render(<DormantAccountsModal data={null} onClose={vi.fn()} />)
    expect(document.querySelector('[data-slot="loading-block"]')).not.toBeNull()
    r1.unmount()
    api.admin.getDormantAccounts.mockRejectedValueOnce(new Error('Sunucuya ulaşılamadı. Ağ bağlantınızı kontrol edin.'))
    render(<DormantAccountsModal data={null} error onRefresh={onRefresh} onClose={vi.fn()} />)
    const failed = await waitFor(() => { const el = document.querySelector('[data-slot="empty"][data-tone="danger"]'); expect(el).not.toBeNull(); return el })
    fireEvent.click(within(failed).getByRole('button', { name: /Yenile|Refresh/ }))
    expect(onRefresh).toHaveBeenCalled()
    await waitFor(() => expect(api.admin.getDormantAccounts).toHaveBeenCalledTimes(4))
  })

  it('kimlik izi yok: satırda IP olsa bile gösterilmez; yenilemede süzgeç korunur', async () => {
    const leakyRows = DORMANT.map((r) => ({ ...r, ip: '10.9.9.9', last_login_ip: '10.9.9.8' }))
    api.admin.getDormantAccounts.mockResolvedValue(fullRes(leakyRows))
    const leaky = { ...DATA, details: { ...DATA.details, dormant: leakyRows } }
    const { rerender } = render(<DormantAccountsModal data={leaky} isAdmin globalAdmin onClose={vi.fn()} />)
    await waitFor(() => expect(loadingLine()).toBeNull())
    expect(dialog().textContent).not.toContain('10.9.9.')
    fireEvent.click(tile('never'))
    rerender(<DormantAccountsModal data={{ ...leaky, generated_at: '2026-10-09T09:00:30' }} isAdmin globalAdmin onClose={vi.fn()} />)
    expect(tableUsers()).toEqual(['bora', 'ece'])
    expect(api.admin.getDormantAccounts).toHaveBeenCalledTimes(1)               // özet yenilemesi tam listeyi yeniden İSTEMEZ
  })
})

describe('UserActivityPanel — Atıl hesap KPI kartı', () => {
  beforeEach(() => {
    api.admin.getLoginSeries.mockResolvedValue({ success: true, data: { buckets: [], granularity: 'day' } })
    api.admin.getUserTimeline.mockResolvedValue({ success: true, data: { username: 'ali', logins: 0, failed: 0, distinct_ips: 0, events: [] } })
  })

  it('kart yeni görünümü açar (tam listeyi o an ister); satır mevcut oturum / kullanıcı detayını açar; diğer KPI ayrıntıları eski pencerede', async () => {
    render(<UserActivityPanel data={{ ...DATA, details: { ...DATA.details, unique_users: [{ username: 'ali', logins: 2, last_login: ago(1) }] } }}
      error={false} refreshing={false} onRefresh={vi.fn()} isAdmin globalAdmin={false} username="admin" />)
    expect(api.admin.getDormantAccounts).not.toHaveBeenCalled()                 // panel tam listeyi YOKLAMAZ
    const card = document.querySelector('[data-kpi="dormant"]')
    expect(card.textContent).toMatch(/6/)
    fireEvent.click(card)
    expect(dialog()).not.toBeNull()
    expect(api.admin.getDormantAccounts).toHaveBeenCalledTimes(1)
    await waitFor(() => expect(loadingLine()).toBeNull())
    expect(dialog().querySelector('[data-slot="dormant-stats"]')).not.toBeNull()
    fireEvent.click(within(within(dialog()).getByTestId('dormant-table').querySelector('tr[data-user="ali"]')).getByRole('button', { name: /Ali Veli/ }))
    await waitFor(() => expect(api.admin.getUserTimeline).toHaveBeenCalledWith('ali', 20))
    // tekil kullanıcı KPI'ı hâlâ genel ayrıntı penceresinde
    fireEvent.click(document.querySelector('[data-kpi="uniq"]'))
    expect(await screen.findByText(/Tekil kullanıcı · 1|Unique users · 1/)).toBeInTheDocument()
  })
})
