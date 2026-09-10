import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'
import { describe, it, expect, vi, beforeEach } from 'vitest'

const { apiMock } = vi.hoisted(() => {
  const deep = (obj) => new Proxy(obj, {
    get(t, prop) {
      if (prop === 'then' || typeof prop === 'symbol') return undefined
      if (prop in t) return typeof t[prop] === 'object' && t[prop] !== null ? deep(t[prop]) : t[prop]
      t[prop] = vi.fn(() => Promise.resolve({ success: true, data: {} }))
      return t[prop]
    },
  })
  return { apiMock: deep({ monitoring: {}, admin: {} }) }
})

vi.mock('../api/client', () => ({
  api: apiMock,
  formatDateSec: (s) => s ?? '',
  formatDateOnly: (s) => s ?? '',
  formatDate: (s) => s ?? '',
}))

import { api } from '../api/client'
import MonitorChangesConsole from '../components/admin/MonitorChangesConsole.jsx'

const ROW = {
  kind: 'SCRIPTED', resource_id: 12, resource_name: 'Ödeme akışı', seq: 3, event_type: 'UPDATE',
  team_id: 5, team_name: 'Kanal takımı', actor: 'N23456', actor_name: 'Ada Lovelace',
  ip_address: '10.20.30.40', user_agent: 'Mozilla/5.0 (Windows NT 10.0) Chrome/126.0.0.0',
  at: '2026-08-22T14:03:11', note: 'Zaman aşımı yetmiyordu',
  changes: JSON.stringify({
    intervalSeconds: { from: 300, to: 60 },
    timeoutMs: { from: 30000, to: 45000 },
    // DİKKAT: çip değerleri satır ADIYLA çakışmamalı — yoksa sorgular iki öğe bulur.
    name: { from: 'Eski ad', to: 'Yeni ad' },
    active: { from: false, to: true },
    url: { from: 'a', to: 'b' },
  }),
}
const DELETED = {
  ...ROW, kind: 'PORT', resource_id: 7, resource_name: 'Eski port', seq: 9,
  event_type: 'DELETE', changes: null, note: null, at: '2026-08-22T15:00:00',
}

const KIND_COUNTS = {
  SCRIPTED: { CREATE: 3, UPDATE: 8, DELETE: 1 },
  PORT: { CREATE: 1, UPDATE: 2 },
  DNS: { CREATE: 0, UPDATE: 0 },        // hic hareket yok -> kart CIZILMEZ
}

function reply(rows, extra = {}) {
  return {
    success: true,
    data: {
      changes: rows, total: rows.length,
      event_counts: { CREATE: 4, UPDATE: 11, DELETE: 2 },
      kind_counts: KIND_COUNTS,
      ...extra,
    },
  }
}

beforeEach(() => {
  vi.clearAllMocks()
  api.monitoring.getRecentChanges.mockResolvedValue(reply([ROW, DELETED]))
  // Varsayilan: takim listesi BOS -> secici cizilmez. Suzgeci sinayan testler kendi verisini kurar.
  api.admin.getTeams.mockResolvedValue({ success: true, data: [] })
})

/**
 * Ozet serit + tur kartlari VARSAYILAN KAPALI (izleme sayfalarindaki istatistik seridiyle ayni).
 * Onlari sinayan testler once basligi acmali; asagida ayrica varsayilanin kapali oldugu ve
 * basligin gercekten actigi da sinaniyor.
 */
function openStats(container) {
  fireEvent.click(container.querySelector('.stats-collapse-bar'))
}

describe('MonitorChangesConsole', () => {
  it('tüm türlerdeki değişiklikleri tek listede, künyesiyle gösterir', async () => {
    render(<MonitorChangesConsole />)
    await waitFor(() => expect(api.monitoring.getRecentChanges).toHaveBeenCalled())

    expect(await screen.findByText('Ödeme akışı')).toBeInTheDocument()
    expect(screen.getByText('Eski port')).toBeInTheDocument()
    // Tür + takım aynı satırda: "nerede" sorusunun cevabı.
    // Takım adı artık tıklanabilir rozet (ayrı element) → künye metni kapsayıcıdan okunur
    expect([...document.querySelectorAll('.chg-row-meta')].some(e => /Synthetic · Kanal takımı/.test(e.textContent))).toBe(true)
    // "Silindi" hem olay süzgecinde hem satır rozetinde geçer — satırdakini arıyoruz.
    const rows = screen.getByText('Eski port').closest('.chg-rows')
    expect(within(rows).getByText('Deleted')).toBeInTheDocument()
  })

  it('özet şeridi PENCERENİN tamamını özetler, sayfalanan listeyi değil', async () => {
    const { container } = render(<MonitorChangesConsole />)
    await screen.findByText('Ödeme akışı')
    openStats(container)

    // Toplam = olay sayaçlarının toplamı (4+11+2), sunucunun sayfa "total"i (2) DEĞİL.
    // Kırılım sayaçları tür/aktör/arama süzgeçlerinden etkilenmiyor; toplam sayfa sayısından
    // okunsaydı şerit kendi içinde çelişirdi (tür seçilince toplam düşer, kırılım kalırdı).
    const values = [...container.querySelectorAll('.audit-stat-value')].map(n => n.textContent)
    expect(values).toEqual(['17', '4', '11', '2'])
  })

  it('kapalı satırda ilk 3 alan görünür, kalanı "+N alan" olarak özetlenir', async () => {
    const { container } = render(<MonitorChangesConsole />)
    await screen.findByText('Ödeme akışı')

    // 5 değişiklik var; liste taranırken satır şişmesin diye 3 çip + özet.
    expect(container.querySelectorAll('.chg-row-chips .chg-chip')).toHaveLength(4)
    expect(screen.getByText('+2 more')).toBeInTheDocument()
  })

  it('satır açılınca tam diff, not, IP ve izlemeye derin bağlantı gelir', async () => {
    render(<MonitorChangesConsole />)
    const head = (await screen.findByText('Ödeme akışı')).closest('button')

    fireEvent.click(head)
    expect(head).toHaveAttribute('aria-expanded', 'true')

    expect(screen.getByText('Zaman aşımı yetmiyordu')).toBeInTheDocument()
    expect(screen.getByText('10.20.30.40')).toBeInTheDocument()
    // Konsol ile izlemenin kendi sekmesi birbirine bağlanır.
    expect(screen.getByRole('link', { name: 'Go to monitor' }))
      .toHaveAttribute('href', '?tab=scripted&monitor=12&mtab=changes')
  })

  it('olay süzgeci sunucuya eventType olarak gider ve sayfa başa döner', async () => {
    render(<MonitorChangesConsole />)
    await screen.findByText('Ödeme akışı')
    api.monitoring.getRecentChanges.mockClear()

    fireEvent.click(screen.getByRole('button', { name: 'Deletions only' }))

    await waitFor(() => expect(api.monitoring.getRecentChanges).toHaveBeenCalledWith(
      expect.objectContaining({ eventType: 'DELETE', page: 0 })))
  })

  it('serbest arama istek parametresine yansır', async () => {
    render(<MonitorChangesConsole />)
    await screen.findByText('Ödeme akışı')
    api.monitoring.getRecentChanges.mockClear()

    fireEvent.change(screen.getByLabelText('Search by monitor name…'), { target: { value: 'ödeme' } })

    await waitFor(() => expect(api.monitoring.getRecentChanges).toHaveBeenCalledWith(
      expect.objectContaining({ q: 'ödeme', page: 0 })))
  })

  it('sonuç yoksa süzgeçleri temizlemeyi öneren boş durum gösterilir', async () => {
    api.monitoring.getRecentChanges.mockResolvedValue(reply([]))
    render(<MonitorChangesConsole />)

    expect(await screen.findByText('No changes recorded yet')).toBeInTheDocument()
    expect(screen.getByText(/Clear them and try again/)).toBeInTheDocument()
  })

  it('sunucu hatası uyarı olarak gösterilir, konsol çökmez', async () => {
    api.monitoring.getRecentChanges.mockResolvedValue({ success: false, error: 'yetkiniz yok' })
    render(<MonitorChangesConsole />)

    expect(await screen.findByText('yetkiniz yok')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Clear filters' })).toBeInTheDocument()
  })

  it('BOZUK changes alanı satırı düşürmez — künye okunmaya devam eder', async () => {
    api.monitoring.getRecentChanges.mockResolvedValue(reply([{ ...ROW, changes: '{bozuk' }]))
    const { container } = render(<MonitorChangesConsole />)

    expect(await screen.findByText('Ödeme akışı')).toBeInTheDocument()
    expect(container.querySelector('.chg-chip')).toBeNull()
  })

  it('adı olmayan kaynak kimliğiyle gösterilir (silinmiş kayıt boş satır olmaz)', async () => {
    api.monitoring.getRecentChanges.mockResolvedValue(reply([{ ...DELETED, resource_name: null }]))
    const { container } = render(<MonitorChangesConsole />)

    const row = await screen.findByText('#7')
    expect(within(container.querySelector('.chg-row')).getByText('Deleted')).toBeInTheDocument()
    expect(row).toBeInTheDocument()
  })
})

/**
 * Tur kartlari — "hangi izlemede ne kadar olusturma / degisiklik / silme oldu" tek bakista.
 *
 * Kullanici bildirimi (2026-08-22): ozet seridi dort duz sayi kutusuydu ve "cok sade, kullanissiz"
 * geliyordu. Toplamlar "bugun 40 degisiklik olmus" der ama yoneticinin sordugu soruyu ("hangi tur
 * hareketli, nerede silme var") cevaplamaz. Kartlar ayni zamanda tur suzgeci.
 */
describe('MonitorChangesConsole — tür kartları', () => {
  it('her tür için toplam ve olay kırılımı gösterir, hareketsiz türü ÇİZMEZ', async () => {
    const { container } = render(<MonitorChangesConsole />)
    await screen.findByText('Ödeme akışı')
    openStats(container)

    const cards = [...container.querySelectorAll('.chg-kpi')]
    // "Tümü" + Sentetik + Port = 3; DNS'in hiç kaydı yok, kart üretmez (11 boş kutu gürültüdür).
    expect(cards).toHaveLength(3)
    expect(within(cards[0]).getByText('15')).toBeInTheDocument()      // genel toplam 12 + 3
    // En hareketli tür ÖNCE gelir: sıralama okunabilirliğin parçası.
    expect(within(cards[1]).getByText('Synthetic')).toBeInTheDocument()
    expect(within(cards[1]).getByText('12')).toBeInTheDocument()
    expect(within(cards[1]).getByText('3')).toBeInTheDocument()       // CREATE
    expect(within(cards[1]).getByText('8')).toBeInTheDocument()       // UPDATE
    expect(within(cards[1]).getByText('1')).toBeInTheDocument()       // DELETE
  })

  it('sıfır olan olay kırılımı çipi çizilmez', async () => {
    const { container } = render(<MonitorChangesConsole />)
    await screen.findByText('Ödeme akışı')
    openStats(container)

    // PORT kartında DELETE yok → 3 değil 2 çip.
    const portCard = [...container.querySelectorAll('.chg-kpi')]
      .find(c => c.textContent.includes('Port'))
    expect(portCard.querySelectorAll('.chg-kpi-chip')).toHaveLength(2)
    expect(portCard.querySelector('.chg-kpi-chip--danger')).toBeNull()
  })

  it('karta tıklamak listeyi o türe daraltır, tekrar tıklamak açar', async () => {
    const { container } = render(<MonitorChangesConsole />)
    await screen.findByText('Ödeme akışı')
    openStats(container)
    api.monitoring.getRecentChanges.mockClear()

    const portCard = [...container.querySelectorAll('.chg-kpi')]
      .find(c => c.textContent.includes('Port'))
    fireEvent.click(portCard)

    await waitFor(() => expect(api.monitoring.getRecentChanges).toHaveBeenCalledWith(
      expect.objectContaining({ kind: 'port', page: 0 })))
    expect(portCard).toHaveAttribute('aria-pressed', 'true')

    api.monitoring.getRecentChanges.mockClear()
    fireEvent.click(portCard)
    await waitFor(() => expect(api.monitoring.getRecentChanges).toHaveBeenCalledWith(
      expect.objectContaining({ kind: '' })))
  })

  it('"Tümü" kartı seçili türü temizler', async () => {
    const { container } = render(<MonitorChangesConsole />)
    await screen.findByText('Ödeme akışı')
    openStats(container)

    const cards = () => [...container.querySelectorAll('.chg-kpi')]
    fireEvent.click(cards().find(c => c.textContent.includes('Port')))
    await waitFor(() => expect(cards()[0]).toHaveAttribute('aria-pressed', 'false'))

    fireEvent.click(cards()[0])
    await waitFor(() => expect(api.monitoring.getRecentChanges).toHaveBeenCalledWith(
      expect.objectContaining({ kind: '' })))
  })

  it('hiç kayıt yoksa kart ızgarası çizilmez (boş kutular gösterilmez)', async () => {
    api.monitoring.getRecentChanges.mockResolvedValue(reply([], { kind_counts: {} }))
    const { container } = render(<MonitorChangesConsole />)
    await screen.findByText('No changes recorded yet')

    expect(container.querySelector('.chg-kpi-grid')).toBeNull()
  })

  it('özet şeridi STİLLİ kart sınıfını kullanır (.audit-stat diye bir kural yok)', async () => {
    const { container } = render(<MonitorChangesConsole />)
    await screen.findByText('Ödeme akışı')
    openStats(container)

    expect(container.querySelectorAll('.chg-stats-row .audit-stat-card')).toHaveLength(4)
  })
})

/**
 * Zaman aralığı — kullanıcı isteği (2026-08-22): "last 7 days kısmını artıralım, 15/30/45/60/90
 * olsun, özel aratabileceğimiz tarihler olsun."
 *
 * Buradaki iki hassas nokta: (1) aralık YEREL saatle kurulmalı — toISOString() UTC'ye kaydırır ve
 * Türkiye'de "bugün" sabah 03:00'te başlamış gibi görünür; (2) sayaçlar/kartlar da aralığı
 * uygulamalı, yoksa şerit 90 günü sayarken liste 30 günü gösterir.
 */
describe('MonitorChangesConsole — zaman aralığı', () => {
  const seg = (container) => container.querySelector('.chg-range-seg')
  const pick = (container, label) => fireEvent.click(within(seg(container)).getByText(label))
  const lastCall = () => api.monitoring.getRecentChanges.mock.calls.at(-1)[0]

  /**
   * Gönderilen sınırı ANLAMIYLA çözer: sunucu UTC sakladığı için dize UTC'dir, bu yüzden
   * 'Z' ekleyerek Date'e çevrilir. İlk sürüm bunu yerel saatle gönderiyordu ve test de onu
   * pinliyordu — Türkiye'de (UTC+3) "bugün" penceresi 3 saat geç başlıyor, günün ilk üç
   * saatindeki değişiklikler listeden sessizce düşüyordu.
   */
  const asInstant = (iso) => {
    expect(iso).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}$/)   // saniye hassasiyeti, Z'siz
    return new Date(iso + 'Z')
  }

  it('varsayılan TÜM zaman: tarih süzgeci gönderilmez', async () => {
    render(<MonitorChangesConsole />)
    await screen.findByText('Ödeme akışı')

    expect(lastCall()).toMatchObject({ from: '', to: '' })
  })

  it('gün pencereleri (7/15/30/45/60/90) doğru başlangıcı gönderir', async () => {
    const { container } = render(<MonitorChangesConsole />)
    await screen.findByText('Ödeme akışı')

    for (const days of [7, 15, 30, 45, 60, 90]) {
      api.monitoring.getRecentChanges.mockClear()
      pick(container, `${days}d`)
      await waitFor(() => expect(api.monitoring.getRecentChanges).toHaveBeenCalled())

      const { from, to } = lastCall()
      const start = asInstant(from)
      // "Son N gün" bugünü DÂHİL sayar → başlangıç (N-1) gün önceki günün 00:00'ı.
      const expected = new Date()
      expected.setDate(expected.getDate() - (days - 1))
      expected.setHours(0, 0, 0, 0)
      expect(start.getTime()).toBe(expected.getTime())
      expect(to).toBe('')                       // açık uçlu: "şimdiye kadar"
    }
  })

  it('Bugün seçimi günün 00:00\'ını gönderir (UTC kaymasi YOK)', async () => {
    const { container } = render(<MonitorChangesConsole />)
    await screen.findByText('Ödeme akışı')
    api.monitoring.getRecentChanges.mockClear()

    pick(container, 'Today')
    await waitFor(() => expect(api.monitoring.getRecentChanges).toHaveBeenCalled())

    const start = asInstant(lastCall().from)
    const midnight = new Date()
    midnight.setHours(0, 0, 0, 0)
    expect(start.getTime()).toBe(midnight.getTime())
  })

  it('Tümü seçimi tarih süzgecini temizler', async () => {
    const { container } = render(<MonitorChangesConsole />)
    await screen.findByText('Ödeme akışı')

    pick(container, '30d')
    await waitFor(() => expect(lastCall().from).not.toBe(''))

    pick(container, 'All')
    await waitFor(() => expect(lastCall()).toMatchObject({ from: '', to: '' }))
  })

  it('Özel seçilince tarih aralığı seçici açılır', async () => {
    const { container } = render(<MonitorChangesConsole />)
    await screen.findByText('Ödeme akışı')

    expect(container.querySelector('.dp-trigger')).toBeNull()
    pick(container, 'Custom')
    expect(container.querySelectorAll('.dp-trigger').length).toBeGreaterThan(0)
  })

  it('Tümü seçiliyken Özele geçince seçicinin varsayılanı HEMEN uygulanır', async () => {
    // Aksi halde düğme "Özel" derken liste hâlâ tüm zamanı gösterir: kontrol ekranla çelişir.
    const { container } = render(<MonitorChangesConsole />)
    await screen.findByText('Ödeme akışı')
    expect(lastCall()).toMatchObject({ from: '', to: '' })

    pick(container, 'Custom')

    await waitFor(() => expect(lastCall().from).not.toBe(''))
    const start = asInstant(lastCall().from)
    const expected = new Date()
    expected.setDate(expected.getDate() - 29)
    expected.setHours(0, 0, 0, 0)
    expect(start.getTime()).toBe(expected.getTime())
    expect(lastCall().to).not.toBe('')
  })

  it('AKTİF bir pencereden Özele geçince aralık DEĞİŞMEZ', async () => {
    const { container } = render(<MonitorChangesConsole />)
    await screen.findByText('Ödeme akışı')

    pick(container, '90d')
    await waitFor(() => expect(lastCall().from).not.toBe(''))
    const before = lastCall().from

    pick(container, 'Custom')
    // Kullanıcının seçtiği pencere korunur; seçici onu gösterir.
    expect(lastCall().from).toBe(before)
  })

  it('aralık değişince sayfa BAŞA döner (3. sayfada 90 gün seçip boş liste görmeyelim)', async () => {
    api.monitoring.getRecentChanges.mockResolvedValue(reply([ROW, DELETED], { total: 300 }))
    const { container } = render(<MonitorChangesConsole />)
    await screen.findByText('Ödeme akışı')

    fireEvent.click(screen.getByRole('button', { name: /next|sonraki|›/i }))
    await waitFor(() => expect(lastCall().page).toBe(1))

    pick(container, '15d')
    await waitFor(() => expect(lastCall().page).toBe(0))
  })
})

/**
 * Kullanici bildirimi (2026-08-27): kartlar sayfa acilir acilmaz ekrani dolduruyordu; asil is
 * olan "kim neyi degistirdi" listesi katlamanin altinda kaliyordu. Izleme sayfalarindaki
 * istatistik seridiyle ayni davranis istendi: VARSAYILAN KAPALI, baslikla acilir.
 */
describe('MonitorChangesConsole — istatistik katlamasi', () => {
  it('kartlar VARSAYILAN KAPALI; baslik yerinde durur', async () => {
    const { container } = render(<MonitorChangesConsole />)
    await screen.findByText('Ödeme akışı')

    expect(container.querySelector('.stats-collapse-bar')).toBeInTheDocument()
    expect(container.querySelectorAll('.chg-kpi')).toHaveLength(0)
    expect(container.querySelectorAll('.audit-stat-value')).toHaveLength(0)
    // Liste kapaliyken de gorunur: katlama asil isi gizlemez.
    expect(screen.getByText('Ödeme akışı')).toBeInTheDocument()
  })

  it('basliga tiklamak acar, tekrar tiklamak kapatir', async () => {
    const { container } = render(<MonitorChangesConsole />)
    await screen.findByText('Ödeme akışı')

    openStats(container)
    expect(container.querySelectorAll('.chg-kpi').length).toBeGreaterThan(0)
    expect(container.querySelectorAll('.audit-stat-value')).toHaveLength(4)

    openStats(container)
    expect(container.querySelectorAll('.chg-kpi')).toHaveLength(0)
  })

  it('tur suzgeci kartlar KAPALIYKEN de erisilebilir (katlama hicbir yolu kapatmaz)', async () => {
    const { container } = render(<MonitorChangesConsole />)
    await screen.findByText('Ödeme akışı')

    expect(container.querySelectorAll('.chg-kpi')).toHaveLength(0)
    // Tur suzgeci kartlarin DISINDA duruyor: kartlar kapaliyken de tur secilebilir.
    expect(container.querySelector('.chg-filters')).toBeInTheDocument()
  })
})

/**
 * Kullanici bildirimi (2026-08-27): kartta tur adi yerine ham anahtar goruluyordu —
 * "chg.kind.pagespeed". Backend'e PAGESPEED turu eklenmis, arayuzun uc listesi (KINDS, i18n,
 * ICONS) guncellenmemisti. `change-kinds-sync` bekcisi tekrarini engelliyor; bu test de
 * ekranda GERCEKTEN cevrilmis adin ciktigini kanitlar.
 */
describe('MonitorChangesConsole — PAGESPEED turu', () => {
  it('Sayfa Hizi karti cevrilmis adla cizilir, ham anahtarla degil', async () => {
    api.monitoring.getRecentChanges.mockResolvedValue(
      reply([ROW], { kind_counts: { PAGESPEED: { CREATE: 1, UPDATE: 2 } } }))
    const { container } = render(<MonitorChangesConsole />)
    await screen.findByText('Ödeme akışı')
    openStats(container)

    const card = [...container.querySelectorAll('.chg-kpi')]
      .find(c => c.textContent.includes('Page Speed'))
    expect(card, 'PAGESPEED karti cizilmedi').toBeTruthy()
    expect(container.textContent).not.toContain('chg.kind.')
  })
})

/**
 * Kullanici istegi (2026-08-27): ekran TUM takim kullanicilarina acildi; herkes yalniz kendi
 * kapsamindaki takimlarin degisikliklerini gorur, yonetici takim bazli suzer.
 *
 * Kapsamin KENDISI ucta uygulanir (viewTeamIds); buradaki testler SUNUM tarafini pinler.
 */
describe('MonitorChangesConsole — takim suzgeci', () => {
  const lastCall = () => api.monitoring.getRecentChanges.mock.calls.at(-1)[0]
  const twoTeams = () => api.admin.getTeams.mockResolvedValue(
    { success: true, data: [{ id: 5, name: 'Takım A' }, { id: 7, name: 'Takım B' }] })
  const oneTeam = () => api.admin.getTeams.mockResolvedValue(
    { success: true, data: [{ id: 5, name: 'Takım A' }] })

  it('TEK takim goren kullanicida secici HIC cizilmez', async () => {
    oneTeam()
    const { container } = render(<MonitorChangesConsole />)
    await screen.findByText('Ödeme akışı')

    // "Tüm takımlar" + tek takim = iki secenek; secici hicbir sey yapmaz, yalniz cubugu doldurur.
    await waitFor(() => expect(api.admin.getTeams).toHaveBeenCalled())
    expect(screen.queryByLabelText('Filter by team')).toBeNull()
  })

  it('COK takim gorulunce secici cizilir ve secim teamId olarak istege girer', async () => {
    twoTeams()
    render(<MonitorChangesConsole />)
    await screen.findByText('Ödeme akışı')

    // SearchableSelect yerli <select> degil: tetigi mouseDown ile ac, secenegi mouseDown ile sec.
    const trigger = await screen.findByLabelText('Filter by team')
    api.monitoring.getRecentChanges.mockClear()
    fireEvent.mouseDown(trigger)
    fireEvent.mouseDown([...document.querySelectorAll('.ss-option')]
      .find(el => el.textContent.includes('Takım B')))

    await waitFor(() => expect(lastCall().teamId).toBe('7'))
    // Sayfalama BASA doner: 3. sayfada takim degistirip bos liste gormeyelim.
    expect(lastCall().page).toBe(0)
  })

  it('takim secilmemisken teamId istege HIC konmaz', async () => {
    twoTeams()
    render(<MonitorChangesConsole />)
    await screen.findByText('Ödeme akışı')

    expect(lastCall()).not.toHaveProperty('teamId')
  })

  /**
   * Suzgec bir KOLAYLIK, ekranin calisma sarti degil: takim ucu patlarsa liste calismaya
   * devam etmeli. Aksi halde tek bir yardimci istek butun ekrani goturur.
   */
  it('takim ucu PATLARSA konsol cokmez, liste calisir', async () => {
    api.admin.getTeams.mockRejectedValue(new Error('403'))
    render(<MonitorChangesConsole />)

    expect(await screen.findByText('Ödeme akışı')).toBeInTheDocument()
    expect(screen.queryByLabelText('Filter by team')).toBeNull()
  })

})

/**
 * Kapsam notu: takim kullanicisi listenin kendi kapsamiyla sinirli oldugunu VE takimsiz
 * kayitlarin burada gorunmedigini bilmeli. Yazilmasaydi eksik gordugunu fark edemez,
 * "demek hic degismemis" diye okurdu.
 */
describe('MonitorChangesConsole — kapsam notu', () => {
  it('takim kullanicisina gosterilir', async () => {
    render(<MonitorChangesConsole globalViewer={false} />)
    await screen.findByText('Ödeme akışı')

    expect(screen.getByText(/Only changes for the teams you can see/)).toBeInTheDocument()
  })

  it('global yoneticide gosterilmez (onun icin dogru degil)', async () => {
    render(<MonitorChangesConsole globalViewer />)
    await screen.findByText('Ödeme akışı')

    expect(screen.queryByText(/Only changes for the teams you can see/)).toBeNull()
  })
})
