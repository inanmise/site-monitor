import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'
import { channel, overview } from './helpers/techErrorFixtures.js'
import { pressMenuTrigger } from './helpers/dropdownMenu.js'

// Kanal listesi yeniden tasarımı (2026-10-17, 8. sürüm): tıklanabilir dönem özeti kartları (süzgeç), arama (Türkçe
// katlama), sıralama, "süzgeçleri temizle", sayfalama (URL te_page / te_ps), süzgeçler URL'de (te_q / te_st / te_sort).
// jsdom'da kap genişliği 0 → TABLO düzeni (satırlar `te-channel-row`); kart düzeni ayrı dosyada (genişlik kancası mock'lu).
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: withApiFallback({
    techErrors: {
      overview: vi.fn(), createChannel: vi.fn(), updateChannel: vi.fn(), deleteChannel: vi.fn(), template: vi.fn(),
      saveTemplate: vi.fn(), defaultTemplate: vi.fn(), reports: vi.fn(), createReport: vi.fn(), report: vi.fn(),
      saveReport: vi.fn(), refreshTemplate: vi.fn(), deleteReport: vi.fn(), uploadImage: vi.fn(), deleteImage: vi.fn(),
      imageUrl: (id) => `/api/tech-error-reports/images/${id}`, send: vi.fn(), downloadPdf: vi.fn(),
    },
  }),
}))
import { api } from '../api/client'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import TechErrorReportsPage from '../components/techerror/TechErrorReportsPage.jsx'

const te = () => api.techErrors

/** 60 kanal: 1–20 gönderildi, 21–40 açık (taslak), 41–55 başlanmamış, 56–60 pasif; 50 + 10 sayfalanır (page ön ayarı 50). */
function manyChannels() {
  const out = []
  for (let i = 1; i <= 60; i++) {
    const name = `Kanal ${String(i).padStart(2, '0')}`
    const extra = i <= 20 ? { period_report: { id: 1000 + i, status: 'sent' } }
      : i <= 40 ? { period_report: { id: 1000 + i, status: 'draft' } }
        : i <= 55 ? {}
          : { active: false }
    if (i === 7) extra.description = 'İnternet Şubesi müşterileri'
    if (i === 60) extra.team_id = 2
    if (i === 60) extra.team_name = 'Kurumsal Kanallar'
    out.push(channel(i, name, extra))
  }
  return out
}

const rows = () => document.querySelectorAll('[data-slot="te-channel-row"]')

beforeEach(() => {
  vi.clearAllMocks()
  window.history.replaceState({}, '', '/?tab=channelreports')
  try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
  te().overview.mockResolvedValue(overview(manyChannels()))
})

describe('kanal listesi — tablo, süzgeçler, sayfalama', () => {
  it('60 kanal: tablo düzeninde ilk sayfa 50 satır, çubukla ikinci sayfa 10; sayım ve URL te_page=2', async () => {
    render(<TechErrorReportsPage />)
    await screen.findByText('60 / 60 kanal gösteriliyor')
    expect(document.querySelector('[data-slot="te-channel-list"]')).toHaveAttribute('data-layout', 'table')
    expect(rows()).toHaveLength(50)
    expect(screen.getByText('1–50 / 60 kayıt')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Sonraki' }))
    await waitFor(() => expect(rows()).toHaveLength(10))
    await waitFor(() => expect(window.location.search).toContain('te_page=2'))
    // Pasifler listenin sonunda (ad sırası, aktifler önce): son sayfada pasif satırlar
    expect(document.querySelectorAll('[data-slot="te-channel-row"][data-active="false"]')).toHaveLength(5)
  })

  it('dönem özeti kartı süzgeçtir: "Raporu başlanmamış" → yalnız 15 kanal, aria-pressed, URL te_st=missing; yeniden tıklayınca kalkar', async () => {
    render(<TechErrorReportsPage />)
    await screen.findByText('60 / 60 kanal gösteriliyor')
    const missing = screen.getByRole('button', { name: /Raporu başlanmamış/ })
    expect(within(missing).getByText('15')).toBeInTheDocument()
    fireEvent.click(missing)
    await screen.findByText('15 / 60 kanal gösteriliyor')
    expect(missing).toHaveAttribute('aria-pressed', 'true')
    expect(rows()).toHaveLength(15)
    await waitFor(() => expect(window.location.search).toContain('te_st=missing'))
    expect(screen.getByRole('button', { name: 'Süzgeçleri temizle' })).toBeInTheDocument()
    fireEvent.click(missing)
    await screen.findByText('60 / 60 kanal gösteriliyor')
    expect(missing).toHaveAttribute('aria-pressed', 'false')
    // Süzgeç yokken "Aktif kanal" kartı etkin (diğer sayfalarla aynı: tıklanması süzgeci kaldırır)
    expect(document.querySelector('[data-slot="stat-item"][data-key="all"]')).toHaveAttribute('aria-pressed', 'true')
    await waitFor(() => expect(window.location.search).not.toContain('te_st='))
    // Pasif kartı: pasif kanallar
    fireEvent.click(screen.getByRole('button', { name: /Pasif kanal/ }))
    await screen.findByText('5 / 60 kanal gösteriliyor')
    expect(document.querySelectorAll('[data-slot="te-channel-row"][data-active="false"]')).toHaveLength(5)
  })

  it('arama Türkçe katlar ("internet" açıklamadaki İnternet Şubesi); eşleşme yoksa boş durum + temizle; URL te_q', async () => {
    render(<TechErrorReportsPage />)
    await screen.findByText('60 / 60 kanal gösteriliyor')
    const search = screen.getByRole('searchbox', { name: 'Kanal, takım ya da açıklama ara' })
    expect(search).toHaveAttribute('data-page-search')
    fireEvent.change(search, { target: { value: 'internet' } })
    await screen.findByText('1 / 60 kanal gösteriliyor')
    expect(rows()).toHaveLength(1)
    expect(within(rows()[0]).getByRole('button', { name: 'Kanal 07' })).toBeInTheDocument()
    await waitFor(() => expect(window.location.search).toContain('te_q=internet'))
    fireEvent.change(search, { target: { value: 'yok böyle kanal' } })
    expect(await screen.findByText('Süzgeçlerle eşleşen kanal yok')).toBeInTheDocument()
    expect(rows()).toHaveLength(0)
    fireEvent.click(screen.getAllByRole('button', { name: 'Süzgeçleri temizle' })[0])
    await screen.findByText('60 / 60 kanal gösteriliyor')
    expect(search).toHaveValue('')
  })

  it('sıralama: "duruma göre" eksik raporlar önce, pasifler sonda; URL te_sort=status; "takıma göre" diğer takım adı sırasına girer', async () => {
    render(<TechErrorReportsPage />)
    await screen.findByText('60 / 60 kanal gösteriliyor')
    const sort = screen.getByLabelText('Sıralama')
    fireEvent.change(sort, { target: { value: 'status' } })
    await waitFor(() => expect(within(rows()[0]).getByRole('button', { name: 'Kanal 41' })).toBeInTheDocument())
    expect(rows()[15].getAttribute('data-channel')).toBe('21')        // 15 eksikten sonra açıklar
    await waitFor(() => expect(window.location.search).toContain('te_sort=status'))
    fireEvent.change(sort, { target: { value: 'team' } })
    // "Dijital Kanallar …" < "Kurumsal Kanallar": 60 numaralı (Kurumsal) en sonda, pasif olduğu için zaten sonda — ad sırası korunur
    await waitFor(() => expect(window.location.search).toContain('te_sort=team'))
    fireEvent.click(screen.getByRole('button', { name: 'Sonraki' }))
    await waitFor(() => expect(rows()).toHaveLength(10))
    expect(rows()[9].getAttribute('data-channel')).toBe('60')
  })

  it('bağlantıyla açılır: te_st=sent&te_sort=status&te_page=1 ilk yüklemede uygulanır; tablo satırında "Şablonu aç" ve etiketli işlem menüsü', async () => {
    window.history.replaceState({}, '', '/?tab=channelreports&te_st=sent&te_sort=status')
    render(<TechErrorReportsPage />)
    await screen.findByText('20 / 60 kanal gösteriliyor')
    // Etkin kartın erişilebilir adı "süzgeci kaldır" ipucudur (MonitorStatsBar) — kart anahtarıyla bulunur
    expect(document.querySelector('[data-slot="stat-item"][data-key="sent"]')).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByLabelText('Sıralama')).toHaveValue('status')
    const first = rows()[0]
    expect(within(first).getByRole('button', { name: 'Şablonu aç' })).toBeInTheDocument()
    expect(within(first).getByRole('button', { name: 'Eylül 2026 raporunu aç' })).toBeInTheDocument()
    // Kullanıcı bildirimi (2026-10-17): takım bilgisinin yanında İKİ ikon vardı ve takım tıklanmıyordu — rozet tek ikonlu
    // ve diğer sayfalardaki gibi takımın üye penceresini açan düğme
    const teamBtn = within(first).getByRole('button', { name: /takımının üyelerini göster$/ })
    expect(teamBtn).toHaveAttribute('data-slot', 'team-badge')
    expect(teamBtn.parentElement.querySelectorAll('svg.lucide-users')).toHaveLength(1)
    pressMenuTrigger(within(first).getByRole('button', { name: 'Kanal 01 — İşlemler' }))
    expect(await screen.findByRole('menuitem', { name: 'Tüm raporlar' })).toBeInTheDocument()
    expect(screen.getByRole('menuitem', { name: 'Kanalı düzenle' })).toBeInTheDocument()
    expect(screen.getByRole('menuitem', { name: 'Pasife al' })).toBeInTheDocument()
    expect(screen.queryByRole('menuitem', { name: 'Kanalı sil' })).toBeNull()        // can_delete yok
  })

  it('"Eylül 2026 raporunu hazırla" rapor açar ve rapora geçer; süzgeçler kanaldan dönünce korunur', async () => {
    te().createReport.mockResolvedValue({ success: true, data: { id: 777 } })
    te().report.mockResolvedValue({ success: false, status: 404, error: 'yok' })
    window.history.replaceState({}, '', '/?tab=channelreports&te_st=missing')
    render(<TechErrorReportsPage />)
    await screen.findByText('15 / 60 kanal gösteriliyor')
    fireEvent.click(within(rows()[0]).getByRole('button', { name: 'Eylül 2026 raporunu hazırla' }))
    await waitFor(() => expect(te().createReport).toHaveBeenCalledWith(41, 2026, 9))
    await waitFor(() => expect(window.location.search).toContain('te_rep=777'))
    expect(window.location.search).toContain('te_st=missing')      // liste süzgeci URL'de kalır → dönüşte aynı liste
  })
})

// Kanal sırası (R14, 2026-10-17): "Sıra" sıralamasında satır menüsü "Yukarı / Aşağı taşı" — yalnız düzenleyebilen
// (paylaşımla gören değil), takımın ucunda devre dışı; taşıma aynı takımın komşusuyla sort_order değiştirir (sırasız takım
// önce 1…n numaralanır), PUT'lar yalnız sort_order taşır, liste iyimser güncellenir ve yeniden yüklenir; hata → bildirim + eski sıra.
const order = () => [...rows()].map((r) => r.getAttribute('data-channel'))
const menuOf = (name) => pressMenuTrigger(screen.getByRole('button', { name: `${name} — İşlemler` }))
/**
 * Menüyü Escape ile kapatır ve bir tur bekler: Radix odağı eski tetiğe setTimeout(0) ile geri verir; hemen açılan bir sonraki
 * menü o geç `focusin`i "dışarıya odak" sayıp kendini kapatıyordu (jsdom'da art arda açılan satır menüleri).
 */
async function closeMenu() {
  fireEvent.keyDown(document.activeElement, { key: 'Escape' })
  await new Promise((r) => setTimeout(r, 0))
}
function orderFixture() {
  const t2 = { team_id: 2, team_name: 'Ağ Operasyon' }
  return [
    channel(1, 'A Kanalı'), channel(2, 'B Kanalı'), channel(3, 'C Kanalı'),                       // takım 1, sırasız (null)
    channel(9, 'Z Kanalı', { ...t2, sort_order: 1 }),
    channel(7, 'Paylaşılan Kanal', { ...t2, sort_order: 2, can_edit: true, shared_access: true }), // paylaşımla: taşınmaz
    channel(8, 'Salt Okunur Kanal', { ...t2, sort_order: 3, can_edit: false }),
  ]
}
/** Sunucu gibi davranan mock: PUT sort_order'u listede günceller, overview güncel listeyi döner. */
function liveMocks(list) {
  te().overview.mockImplementation(async () => overview(list.map((c) => ({ ...c }))))
  te().updateChannel.mockImplementation(async (id, body) => {
    const c = list.find((x) => x.id === id)
    if (c && 'sort_order' in body) c.sort_order = body.sort_order
    return { success: true, data: { ...c, ...body } }
  })
}

describe('kanal sırası (R14)', () => {
  it('taşıma öğeleri yalnız "Sıra" sıralamasında ve yalnız düzenleyebilen satırda; takımın ilkinde yukarı devre dışı; ipucu ve URL te_sort=order', async () => {
    liveMocks(orderFixture())
    render(<TechErrorReportsPage />)
    await screen.findByText('6 / 6 kanal gösteriliyor')
    menuOf('A Kanalı')
    expect(await screen.findByRole('menuitem', { name: 'Kanalı düzenle' })).toBeInTheDocument()
    expect(screen.queryByRole('menuitem', { name: /taşı/ })).toBeNull()          // ad sıralamasında yok
    await closeMenu()
    expect(document.querySelector('[data-slot="te-sort-hint"]')).toBeNull()
    fireEvent.change(screen.getByLabelText('Sıralama'), { target: { value: 'order' } })
    await waitFor(() => expect(window.location.search).toContain('te_sort=order'))
    expect(screen.getByText('Sıra bu takım içindeki kanalların listelenme sırasıdır; menüden taşıyın.')).toHaveAttribute('id', 'te-sort-hint')
    expect(screen.getByLabelText('Sıralama')).toHaveAttribute('aria-describedby', 'te-sort-hint')
    // "Ağ Operasyon" < "Dijital …": takım 2 (sıra 1, 2, 3) önce; takım 1 sırasız → ad sırası
    expect(order()).toEqual(['9', '7', '8', '1', '2', '3'])
    menuOf('A Kanalı')
    const up = await screen.findByRole('menuitem', { name: 'Yukarı taşı: A Kanalı' })
    expect(up).toHaveAttribute('aria-disabled', 'true')                           // takımının ilki (önündeki 8 başka takım)
    expect(screen.getByRole('menuitem', { name: 'Aşağı taşı: A Kanalı' })).not.toHaveAttribute('aria-disabled')
    await closeMenu()
    menuOf('C Kanalı')
    expect(await screen.findByRole('menuitem', { name: 'Aşağı taşı: C Kanalı' })).toHaveAttribute('aria-disabled', 'true')   // takımının sonu
    await closeMenu()
    menuOf('Paylaşılan Kanal')
    expect(await screen.findByRole('menuitem', { name: 'Kanalı düzenle' })).toBeInTheDocument()
    expect(screen.queryByRole('menuitem', { name: /taşı/ })).toBeNull()          // paylaşımla görülen: düzenlese de taşımaz
    await closeMenu()
    menuOf('Salt Okunur Kanal')
    expect(await screen.findByRole('menuitem', { name: 'Tüm raporlar' })).toBeInTheDocument()
    expect(screen.queryByRole('menuitem', { name: /taşı/ })).toBeNull()          // can_edit yok
  })

  it('sırasız takımda "Aşağı taşı": takım 1…n numaralanır (3 PUT, yalnız sort_order, sırayla), liste yeniden yüklenir; sonraki taşıma iki PUT ile yer değiştirir', async () => {
    const list = orderFixture()
    liveMocks(list)
    window.history.replaceState({}, '', '/?tab=channelreports&te_sort=order')
    render(<TechErrorReportsPage />)
    await screen.findByText('6 / 6 kanal gösteriliyor')
    expect(order()).toEqual(['9', '7', '8', '1', '2', '3'])
    menuOf('A Kanalı')
    fireEvent.click(await screen.findByRole('menuitem', { name: 'Aşağı taşı: A Kanalı' }))
    await waitFor(() => expect(te().updateChannel).toHaveBeenCalledTimes(3))
    expect(te().updateChannel.mock.calls).toEqual([[2, { sort_order: 1 }], [1, { sort_order: 2 }], [3, { sort_order: 3 }]])
    await waitFor(() => expect(te().overview).toHaveBeenCalledTimes(2))
    await waitFor(() => expect(order()).toEqual(['9', '7', '8', '2', '1', '3']))
    // Artık ikisinin de sırası var: A yukarı ↔ B yalnız iki PUT (başka takıma dokunulmaz)
    menuOf('A Kanalı')
    fireEvent.click(await screen.findByRole('menuitem', { name: 'Yukarı taşı: A Kanalı' }))
    await waitFor(() => expect(te().updateChannel).toHaveBeenCalledTimes(5))
    expect(te().updateChannel.mock.calls.slice(3)).toEqual([[1, { sort_order: 1 }], [2, { sort_order: 2 }]])
    await waitFor(() => expect(order()).toEqual(['9', '7', '8', '1', '2', '3']))
    expect(te().updateChannel.mock.calls.every(([id]) => [1, 2, 3].includes(id))).toBe(true)
  })

  it('iyimser sıra: PUT henüz dönmeden liste yeni sırayı gösterir; taşıma sürerken öğeler devre dışı', async () => {
    liveMocks(orderFixture())
    te().updateChannel.mockReturnValue(new Promise(() => {}))        // hiç dönmez
    window.history.replaceState({}, '', '/?tab=channelreports&te_sort=order')
    render(<TechErrorReportsPage />)
    await screen.findByText('6 / 6 kanal gösteriliyor')
    menuOf('B Kanalı')
    fireEvent.click(await screen.findByRole('menuitem', { name: 'Aşağı taşı: B Kanalı' }))
    await waitFor(() => expect(order()).toEqual(['9', '7', '8', '1', '3', '2']))
    expect(te().updateChannel).toHaveBeenCalledTimes(1)               // sıralı: ilk PUT bekleniyor
    expect(te().overview).toHaveBeenCalledTimes(1)                    // yeniden yükleme yalnız başarıda
    menuOf('A Kanalı')
    expect(await screen.findByRole('menuitem', { name: 'Aşağı taşı: A Kanalı' })).toHaveAttribute('aria-disabled', 'true')
  })

  it('PUT düşerse: bildirim (sunucu metni), eski sıra geri gelir, kalan PUT gönderilmez, sunucu gerçeği yeniden okunur', async () => {
    liveMocks(orderFixture())
    te().updateChannel.mockResolvedValue({ success: false, status: 400, error: 'Sıra numarası 0 ile 10 000 arasında olmalı.' })
    window.history.replaceState({}, '', '/?tab=channelreports&te_sort=order')
    render(<TechErrorReportsPage />)
    await screen.findByText('6 / 6 kanal gösteriliyor')
    menuOf('A Kanalı')
    fireEvent.click(await screen.findByRole('menuitem', { name: 'Aşağı taşı: A Kanalı' }))
    expect((await screen.findAllByText('Sıra numarası 0 ile 10 000 arasında olmalı.')).length).toBeGreaterThan(0)
    await waitFor(() => expect(te().overview).toHaveBeenCalledTimes(2))
    expect(te().updateChannel).toHaveBeenCalledTimes(1)               // ilk PUT düştü, ikincisi / üçüncüsü gitmedi
    await waitFor(() => expect(order()).toEqual(['9', '7', '8', '1', '2', '3']))
  })
})
