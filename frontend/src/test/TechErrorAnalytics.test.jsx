import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within, act } from './test-utils.jsx'
import { channel, overview } from './helpers/techErrorFixtures.js'

// Kanal analizi (2026-10-18, R22): kanal listesindeki "Analiz" düğmesi `te_view=analytics` görünümünü açar; araç çubuğu
// (dönem 6 / 12 / 24, alan seçici en çok 4, takım) isteğe ve URL'ye gider; tablo boş ayı "—" yazar (0 değil), gönderilmemiş
// raporun değeri soluk + adında "gönderilmedi", hücre o raporu açar; dar kapta kanal kartları; okuma hatası tehlike bloğu +
// tekrar dene (asla "veri yok"); kesilmiş pencere bilgi şeridi; CSV görünen tablonun ham sayıları.
const width = vi.hoisted(() => ({ value: 0 }))
vi.mock('../hooks/useElementWidth.js', () => ({ useElementWidth: () => [() => {}, width.value], useElementWidthState: () => [width.value, () => {}] }))
const csv = vi.hoisted(() => ({ downloadCsv: vi.fn() }))
vi.mock('../utils/csvExport.js', async (importOriginal) => ({ ...(await importOriginal()), downloadCsv: csv.downloadCsv }))
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: withApiFallback({
    techErrors: {
      overview: vi.fn(), analytics: vi.fn(), reports: vi.fn(), template: vi.fn(), report: vi.fn(),
      imageUrl: (id) => `/api/tech-error-reports/images/${id}`,
    },
  }),
}))
import { api } from '../api/client'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import TechErrorReportsPage from '../components/techerror/TechErrorReportsPage.jsx'
import { ANALYTICS_KEYS } from '../components/techerror/analytics/analyticsModel.js'

const te = () => api.techErrors
const LONG = 'Bireysel İnternet Şubesi ve Mobil Bankacılık Ortak Kanalı (Dijital Kanallar / Bireysel Bankacılık)'

function months(n) {
  const out = []
  for (let i = n - 1; i >= 0; i--) {
    const d = new Date(Date.UTC(2026, 8 - i, 1))
    out.push({ year: d.getUTCFullYear(), month: d.getUTCMonth() + 1 })
  }
  return out
}

function analytics(extra = {}) {
  const n = 12
  const tx = (base) => { const a = Array(n).fill(null); a[11] = base; a[10] = base - 100; return a }
  const rate = () => { const a = Array(n).fill(null); a[11] = 0.42; return a }
  const reps = (id, sent) => { const a = Array(n).fill(null); a[11] = { id, sent }; a[10] = { id: id + 1, sent: true }; a[5] = { id: id + 2, sent: true }; return a }
  return {
    success: true,
    data: {
      team: 'all', months_requested: 12, months: months(n), end: { year: 2026, month: 9 },
      fields: [{ id: 'error-rate', label: 'Teknik hata oranı', type: 'percent' }, { id: 'total-tx', label: 'Toplam işlem adedi', type: 'number' }],
      available_fields: [
        { id: 'total-tx', label: 'Toplam işlem adedi', type: 'number', channels: 2 },
        { id: 'error-rate', label: 'Teknik hata oranı', type: 'percent', channels: 2 },
        { id: 'sla-rate', label: 'Kritik fonksiyonların SLA oranı', type: 'percent', channels: 1 },
      ],
      channels: [
        { id: 10, name: LONG, team_id: 1, team_name: 'Dijital Kanallar', points: { 'error-rate': rate(), 'total-tx': tx(1_250_000) }, reports: reps(100, false) },
        { id: 20, name: 'POS', team_id: 2, team_name: 'Kurumsal Kanallar', points: { 'error-rate': rate(), 'total-tx': tx(900_000) }, reports: reps(200, true) },
      ],
      channel_count: 2, truncated: false,
      teams: [{ id: 1, name: 'Dijital Kanallar' }, { id: 2, name: 'Kurumsal Kanallar' }],
      month_choices: [6, 12, 24], max_fields: 4,
      ...extra,
    },
  }
}

const lastParams = () => te().analytics.mock.calls.at(-1)?.[0]
const field = (id) => document.querySelector(`[data-slot="te-an-field"][data-field="${id}"]`)

beforeEach(() => {
  vi.clearAllMocks()
  width.value = 0
  window.history.replaceState({}, '', '/?tab=channelreports')
  try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
  te().overview.mockResolvedValue(overview([channel(10, LONG), channel(20, 'POS')]))
  te().analytics.mockResolvedValue(analytics())
  te().report.mockResolvedValue({ success: false, status: 404, error: 'yok' })
})

describe('kanal analizi', () => {
  it('kanal listesindeki "Analiz" düğmesi görünümü açar: varsayılan istek 12 ay, alan yok; URL te_view=analytics; geri dönüş', async () => {
    render(<TechErrorReportsPage />)
    const open = await screen.findByRole('button', { name: 'Analiz' })
    expect(open).toHaveAttribute('data-slot', 'te-analytics-open')
    fireEvent.click(open)
    expect(await screen.findByRole('heading', { name: 'Kanal analizi' })).toBeInTheDocument()
    await waitFor(() => expect(te().analytics).toHaveBeenCalledWith({ months: 12 }))
    await waitFor(() => expect(window.location.search).toContain('te_view=analytics'))
    // Varsayılan alanlar sunucudan: iki kart, seçicide 2/4
    await waitFor(() => expect(document.querySelectorAll('[data-slot="te-an-field"]')).toHaveLength(2))
    expect(within(field('error-rate')).getByRole('heading', { name: 'Teknik hata oranı' })).toBeInTheDocument()
    expect(screen.getByText('Ekim 2025 – Eylül 2026 · 2 kanal')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Kanallara dön' }))
    expect(await screen.findByRole('button', { name: 'Analiz' })).toBeInTheDocument()
    await waitFor(() => expect(window.location.search).not.toContain('te_view='))
  })

  it('derin bağlantı: takım / ay / alanlar isteğe gider; dönem seçimi yeni istek ve URL', async () => {
    window.history.replaceState({}, '', '/?tab=channelreports&te_view=analytics&te_an_team=1&te_an_months=24&te_an_fields=error-rate,total-tx')
    render(<TechErrorReportsPage />)
    await waitFor(() => expect(te().analytics).toHaveBeenCalledWith({ team: '1', months: 24, fields: 'error-rate,total-tx' }))
    const six = await screen.findByRole('button', { name: '6 ay' })
    expect(screen.getByRole('button', { name: '24 ay' })).toHaveAttribute('aria-pressed', 'true')
    fireEvent.click(six)
    await waitFor(() => expect(lastParams()).toEqual({ team: '1', months: 6, fields: 'error-rate,total-tx' }))
    await waitFor(() => expect(window.location.search).toContain('te_an_months=6'))
    // Varsayılana (12) dönünce URL'den silinir
    fireEvent.click(screen.getByRole('button', { name: '12 ay' }))
    await waitFor(() => expect(lastParams()).toEqual({ team: '1', months: 12, fields: 'error-rate,total-tx' }))
    await waitFor(() => expect(window.location.search).not.toContain('te_an_months'))
  })

  it('alan seçici: alan eklenir (istek + URL, adı sararak listelenir)', async () => {
    window.history.replaceState({}, '', '/?tab=channelreports&te_view=analytics')
    render(<TechErrorReportsPage />)
    const picker = await screen.findByRole('button', { name: /Alan seç/ })
    fireEvent.click(picker)
    const sla = await screen.findByRole('option', { name: /Kritik fonksiyonların SLA oranı/ })
    expect(sla).toHaveAttribute('aria-checked', 'false')
    fireEvent.click(sla)
    await waitFor(() => expect(lastParams()).toEqual({ months: 12, fields: 'error-rate,total-tx,sla-rate' }))
    await waitFor(() => expect(window.location.search).toContain('te_an_fields=error-rate%2Ctotal-tx%2Csla-rate'))
  })

  it('tek alan seçiliyken o alan seçicide kaldırılamaz', async () => {
    window.history.replaceState({}, '', '/?tab=channelreports&te_view=analytics&te_an_fields=total-tx')
    te().analytics.mockResolvedValue(analytics({ fields: [{ id: 'total-tx', label: 'Toplam işlem adedi', type: 'number' }] }))
    render(<TechErrorReportsPage />)
    fireEvent.click(await screen.findByRole('button', { name: /Alan seç/ }))
    const tx = await screen.findByRole('option', { name: /Toplam işlem adedi/ })
    expect(tx).toHaveAttribute('aria-checked', 'true')
    expect(tx).toHaveAttribute('aria-disabled', 'true')
    const calls = te().analytics.mock.calls.length
    fireEvent.click(tx)
    await new Promise((r) => setTimeout(r, 30))
    expect(te().analytics.mock.calls.length).toBe(calls)
  })

  it('tablo: en yeni ay önce; boş ay "—" (0 değil), gönderilmemiş değer soluk + adında "gönderilmedi"; hücre raporu açar', async () => {
    window.history.replaceState({}, '', '/?tab=channelreports&te_view=analytics')
    render(<TechErrorReportsPage />)
    await waitFor(() => expect(field('total-tx')).not.toBeNull())
    const card = field('total-tx')
    expect(card.querySelector('[data-slot="te-an-table-wrap"]')).toHaveAttribute('data-layout', 'table')
    const rows = card.querySelectorAll('[data-slot="te-an-row"]')
    expect(rows).toHaveLength(12)
    expect(rows[0]).toHaveAttribute('data-month', '2026-9')
    expect(within(rows[0]).getByText('Eyl 2026')).toBeInTheDocument()
    // Raporu olmayan ay: "—" + ekran okuyucuya "rapor yok"; hiçbir hücre sahte 0 yazmaz
    expect(within(rows[2]).getAllByText('rapor yok')).toHaveLength(2)
    expect(card.textContent).not.toMatch(/(^|\s)0(\s|$)/)
    // Raporu var ama değer yok (Mart): "—" bağlantısı, adında "değer yok"
    const aprilCell = within(rows[6]).getAllByRole('button')[0]
    expect(aprilCell).toHaveTextContent('—')
    expect(aprilCell).toHaveAccessibleName(/değer yok/)
    // Gönderilmemiş rapor: soluk + adında "gönderilmedi"; gönderilmiş: düz
    const unsent = within(rows[0]).getByRole('button', { name: /1\.250\.000 \(gönderilmedi\)/ })
    expect(unsent).toHaveAttribute('data-state', 'unsent')
    const sent = within(rows[0]).getByRole('button', { name: /^900\.000 — / })
    expect(sent).toHaveAttribute('data-state', 'sent')
    // Uzun kanal adı tablo başlığında ve grafik anahtarında tam (kırpılmaz)
    expect(within(card.querySelector('[data-slot="te-an-table"]')).getByText(LONG)).toBeInTheDocument()
    expect(within(card.querySelector('[data-slot="te-an-legend"]')).getByText(LONG)).toBeInTheDocument()
    fireEvent.click(unsent)
    await waitFor(() => expect(te().report).toHaveBeenCalledWith('100'))
    await waitFor(() => expect(window.location.search).toContain('te_view=report'))
    expect(window.location.search).toContain('te_rep=100')
  })

  it('dar kap (< 760 px): kanal kartları — ay listesi, aynı hücreler ve bağlantılar', async () => {
    width.value = 400
    window.history.replaceState({}, '', '/?tab=channelreports&te_view=analytics')
    render(<TechErrorReportsPage />)
    await waitFor(() => expect(field('error-rate')).not.toBeNull())
    const card = field('error-rate')
    expect(card.querySelector('[data-slot="te-an-table-wrap"]')).toHaveAttribute('data-layout', 'cards')
    expect(card.querySelector('[data-slot="te-an-table"]')).toBeNull()
    const cards = card.querySelectorAll('[data-slot="te-an-card"]')
    expect(cards).toHaveLength(2)
    expect(within(cards[0]).getByText(LONG)).toBeInTheDocument()
    expect(cards[0].querySelectorAll('[data-slot="te-an-card-row"]')).toHaveLength(12)
    expect(within(cards[0]).getByRole('button', { name: /%0,42 \(gönderilmedi\)/ })).toBeInTheDocument()
  })

  it('okuma hatası: tehlike bloğu + "Tekrar dene" (asla "veri yok"); yeniden okumada tablo gelir', async () => {
    te().analytics.mockResolvedValueOnce({ success: false, status: 500, error: 'Sunucu şu an yanıt vermiyor; birkaç dakika sonra tekrar deneyin.' })
    window.history.replaceState({}, '', '/?tab=channelreports&te_view=analytics')
    render(<TechErrorReportsPage />)
    await waitFor(() => expect(document.querySelector('[data-slot="empty"][data-tone="danger"]')).not.toBeNull())
    expect(screen.queryByText('İncelenecek kanal yok')).toBeNull()
    expect(screen.queryByText(/değer girilmemiş/)).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'Tekrar dene' }))
    await waitFor(() => expect(field('total-tx')).not.toBeNull())
  })

  it('kesilmiş pencere bilgi şeridi; kanal yoksa boş durum; değeri olmayan alan "değer girilmemiş"', async () => {
    te().analytics.mockResolvedValueOnce(analytics({ truncated: true, months: months(10), months_requested: 24, channel_count: 130 }))
    window.history.replaceState({}, '', '/?tab=channelreports&te_view=analytics')
    const { unmount } = render(<TechErrorReportsPage />)
    expect(await screen.findByText('Yalnız son 10 ay gösteriliyor')).toBeInTheDocument()
    expect(screen.getByText(/130 kanalın 24 ayı/)).toBeInTheDocument()
    unmount()
    te().analytics.mockResolvedValueOnce(analytics({ channels: [], channel_count: 0 }))
    const second = render(<TechErrorReportsPage />)
    expect(await screen.findByText('İncelenecek kanal yok')).toBeInTheDocument()
    second.unmount()
    const empty = analytics()
    empty.data.fields = [{ id: 'sla-rate', label: 'Kritik fonksiyonların SLA oranı', type: 'percent' }]
    te().analytics.mockResolvedValueOnce(empty)
    render(<TechErrorReportsPage />)
    expect(await screen.findByText('Bu alana seçilen dönemde hiçbir kanalda değer girilmemiş.')).toBeInTheDocument()
  })

  it('görünmeyen takım (404): süzgeç kalkar, kurum kapsamıyla yeniden okunur', async () => {
    te().analytics.mockResolvedValueOnce({ success: false, status: 404, error: 'Takım bulunamadı ya da bu takımın raporlarını göremezsiniz.' })
    window.history.replaceState({}, '', '/?tab=channelreports&te_view=analytics&te_an_team=99')
    render(<TechErrorReportsPage />)
    await waitFor(() => expect(te().analytics).toHaveBeenCalledWith({ team: '99', months: 12 }))
    await waitFor(() => expect(lastParams()).toEqual({ months: 12 }))
    await waitFor(() => expect(field('total-tx')).not.toBeNull())
    await waitFor(() => expect(window.location.search).not.toContain('te_an_team'))
  })

  it('CSV: görünen tablonun satırları, değer ham sayı, boş ay boş hücre', async () => {
    window.history.replaceState({}, '', '/?tab=channelreports&te_view=analytics')
    render(<TechErrorReportsPage />)
    await waitFor(() => expect(field('total-tx')).not.toBeNull())
    fireEvent.click(within(field('total-tx')).getByRole('button', { name: 'Toplam işlem adedi tablosunu CSV olarak indir' }))
    expect(csv.downloadCsv).toHaveBeenCalledTimes(1)
    const [name, body] = csv.downloadCsv.mock.calls[0]
    expect(name).toBe('te-analiz-total-tx-2025-10_2026-09.csv')
    const lines = body.slice(body.charCodeAt(0) === 0xfeff ? 1 : 0).split('\r\n')
    expect(lines[0]).toContain('Ay')
    expect(lines[0]).toContain('POS (Kurumsal Kanallar)')
    expect(lines[1]).toBe('Eylül 2026,1250000,900000')
    expect(lines[3]).toBe('Temmuz 2026,,')
  })
})

/** Elle çözülen yanıt — geç gelen / bekleyen istek senaryoları için. */
function deferred() {
  let resolve
  const promise = new Promise((r) => { resolve = r })
  return { promise, resolve }
}

const param = (k) => new URL(window.location.href).searchParams.get(k)

describe('kanal analizi — inceleme düzeltmeleri (2026-10-18)', () => {
  it('ilk okuma HATASINDA da araç çubuğu: adresteki süzgeç görünür; "Varsayılana dön" te_an_* anahtarlarını siler ve varsayılanla okur', async () => {
    te().analytics.mockResolvedValue({ success: false, status: 500, error: 'Sunucu şu an yanıt vermiyor; birkaç dakika sonra tekrar deneyin.' })
    window.history.replaceState({}, '', '/?tab=channelreports&te_view=analytics&te_an_team=1&te_an_months=24&te_an_fields=error-rate')
    render(<TechErrorReportsPage />)
    await waitFor(() => expect(document.querySelector('[data-slot="empty"][data-tone="danger"]')).not.toBeNull())
    const toolbar = document.querySelector('[data-slot="te-an-toolbar"]')
    expect(toolbar).not.toBeNull()
    expect(within(toolbar).getByRole('button', { name: '24 ay' })).toHaveAttribute('aria-pressed', 'true')
    // Seçili alan (adresten) seçicide sayılır; yanıt yokken de kaldırılabilir olarak listelenir
    expect(within(toolbar).getByRole('button', { name: /Alan seç/ })).toHaveTextContent('1/4')
    te().analytics.mockResolvedValue(analytics())
    fireEvent.click(within(toolbar).getByRole('button', { name: 'Varsayılana dön' }))
    await waitFor(() => expect(lastParams()).toEqual({ months: 12 }))
    await waitFor(() => { for (const k of ANALYTICS_KEYS) expect(param(k)).toBeNull() })
    await waitFor(() => expect(field('total-tx')).not.toBeNull())
    expect(screen.queryByRole('button', { name: 'Varsayılana dön' })).toBeNull()
  })

  it('süzgeç değişince eski veri HEMEN kalkar (iskelet); önceki süzgecin geç yanıtı düşer', async () => {
    window.history.replaceState({}, '', '/?tab=channelreports&te_view=analytics')
    render(<TechErrorReportsPage />)
    await waitFor(() => expect(field('total-tx')).not.toBeNull())
    const six = deferred()
    te().analytics.mockImplementationOnce(() => six.promise)
    fireEvent.click(screen.getByRole('button', { name: '6 ay' }))
    // Eski (12 aylık) tablo yeni süzgeçle GÖRÜNMEZ — yükleniyor iskeleti; araç çubuğu yerinde
    expect(field('total-tx')).toBeNull()
    expect(document.querySelector('[data-slot="te-an-loading"]')).not.toBeNull()
    expect(document.querySelector('[data-slot="te-an-toolbar"]')).not.toBeNull()
    const twentyFour = deferred()
    te().analytics.mockImplementationOnce(() => twentyFour.promise)
    fireEvent.click(screen.getByRole('button', { name: '24 ay' }))
    await waitFor(() => expect(lastParams()).toEqual({ months: 24 }))
    const fresh = analytics()
    fresh.data.channels = [{ ...fresh.data.channels[1], name: 'Yeni Süzgeç Kanalı' }]
    await act(async () => { twentyFour.resolve(fresh) })
    await waitFor(() => expect(screen.getAllByText('Yeni Süzgeç Kanalı').length).toBeGreaterThan(0))
    // 6 ayın yanıtı SONRA gelir: düşer, ekran 24 ayın verisinde kalır
    const stale = analytics()
    stale.data.channels = [{ ...stale.data.channels[1], name: 'Eski Süzgeç Kanalı' }]
    await act(async () => { six.resolve(stale) })
    await new Promise((r) => setTimeout(r, 20))
    expect(screen.queryByText('Eski Süzgeç Kanalı')).toBeNull()
    expect(screen.getAllByText('Yeni Süzgeç Kanalı').length).toBeGreaterThan(0)
  })

  it('Yenile: veri yerinde kalır (aria-busy), istek fresh=1 taşır; yenileme hatası eski veriyi silmez', async () => {
    window.history.replaceState({}, '', '/?tab=channelreports&te_view=analytics')
    render(<TechErrorReportsPage />)
    await waitFor(() => expect(field('total-tx')).not.toBeNull())
    const pending = deferred()
    te().analytics.mockImplementationOnce(() => pending.promise)
    fireEvent.click(screen.getByRole('button', { name: 'Yenile' }))
    await waitFor(() => expect(lastParams()).toEqual({ months: 12, fresh: 1 }))
    expect(field('total-tx')).not.toBeNull()
    expect(document.querySelector('[data-slot="te-an-fields-list"]')).toHaveAttribute('aria-busy', 'true')
    await act(async () => { pending.resolve({ success: false, status: 500, error: 'Sunucu şu an yanıt vermiyor; birkaç dakika sonra tekrar deneyin.' }) })
    expect(await screen.findByText('Analiz yenilenemedi; son okunan değerler gösteriliyor')).toBeInTheDocument()
    expect(field('total-tx')).not.toBeNull()
  })

  it('istenen alanlar kapsamda yok: sunucu varsayılanları döner — not gösterilir, adres İKİNCİ İSTEK OLMADAN düzeltilir', async () => {
    te().analytics.mockResolvedValue(analytics({ fields_fallback: true, dropped_fields: ['eski-alan'] }))
    window.history.replaceState({}, '', '/?tab=channelreports&te_view=analytics&te_an_fields=eski-alan')
    render(<TechErrorReportsPage />)
    expect(await screen.findByText('Seçili alanlar bu kapsamda yok; varsayılan alanlar gösteriliyor')).toBeInTheDocument()
    expect(screen.getByText(/Bulunamayan alanlar: eski-alan/)).toBeInTheDocument()
    expect(screen.queryByText('Karşılaştırılacak alan yok')).toBeNull()
    await waitFor(() => expect(field('total-tx')).not.toBeNull())
    await waitFor(() => expect(param('te_an_fields')).toBeNull())
    await new Promise((r) => setTimeout(r, 30))
    expect(te().analytics).toHaveBeenCalledTimes(1)
    expect(te().analytics).toHaveBeenCalledWith({ months: 12, fields: 'eski-alan' })
    // Seçici sunucunun çözdüğü alanları seçili gösterir
    expect(screen.getByRole('button', { name: /Alan seç/ })).toHaveTextContent('2/4')
  })

  it('bir kısmı kapsamda yok: kalanlar adrese yazılır (ikinci istek yok), kısa not', async () => {
    te().analytics.mockResolvedValue(analytics({ fields_fallback: false, dropped_fields: ['eski-alan'] }))
    window.history.replaceState({}, '', '/?tab=channelreports&te_view=analytics&te_an_fields=error-rate,eski-alan,total-tx')
    render(<TechErrorReportsPage />)
    expect(await screen.findByText(/Seçili alanlardan bazıları bu kapsamda yok \(eski-alan\)/)).toBeInTheDocument()
    await waitFor(() => expect(param('te_an_fields')).toBe('error-rate,total-tx'))
    await new Promise((r) => setTimeout(r, 30))
    expect(te().analytics).toHaveBeenCalledTimes(1)
  })

  it('alan türü farklı kanal: grafik anahtarında, tablo başlığında ve kartta not', async () => {
    const d = analytics()
    d.data.fields[1] = { ...d.data.fields[1], type_mismatch: [20] }
    te().analytics.mockResolvedValue(d)
    window.history.replaceState({}, '', '/?tab=channelreports&te_view=analytics')
    render(<TechErrorReportsPage />)
    await waitFor(() => expect(field('total-tx')).not.toBeNull())
    const card = field('total-tx')
    const legend = [...card.querySelectorAll('[data-slot="te-an-legend-item"]')]
    expect(legend[1]).toHaveAttribute('data-mismatch', 'true')
    expect(within(legend[1]).getByText('Bu kanalın güncel şablonunda alan türü farklı; yalnız aynı türdeki aylar gösterilir')).toBeInTheDocument()
    expect(legend[0]).not.toHaveAttribute('data-mismatch')
    const head = card.querySelector('[data-slot="te-an-table"] th[data-channel="20"]')
    expect(within(head).getByText('Bu kanalın güncel şablonunda alan türü farklı; yalnız aynı türdeki aylar gösterilir')).toBeInTheDocument()
    expect(field('error-rate').querySelector('[data-slot="te-an-mismatch"]')).toBeNull()
  })

  it('kuyruktaki rapor: "gönderim sırada" (gönderilmedi değil), soluk', async () => {
    const d = analytics()
    d.data.channels[0].reports[11] = { id: 100, sent: false, queued: true }
    te().analytics.mockResolvedValue(d)
    window.history.replaceState({}, '', '/?tab=channelreports&te_view=analytics')
    render(<TechErrorReportsPage />)
    await waitFor(() => expect(field('total-tx')).not.toBeNull())
    const cell = within(field('total-tx')).getByRole('button', { name: /1\.250\.000 \(gönderim sırada\)/ })
    expect(cell).toHaveAttribute('data-state', 'queued')
    expect(cell.className).toContain('text-muted-foreground')
    expect(cell.className).toContain('pointer-coarse:min-w-10')
  })

  it('Y ekseni veriye göre: %99,80–%99,95 aralığında eksen 0\'dan başlamaz', async () => {
    const d = analytics()
    d.data.fields = [{ id: 'sla-rate', label: 'Kritik fonksiyonların SLA oranı', type: 'percent', type_mismatch: [] }]
    d.data.channels.forEach((ch, c) => {
      const a = Array(12).fill(null)
      a[9] = 99.8 + c * 0.05
      a[10] = 99.9
      a[11] = 99.95
      ch.points = { 'sla-rate': a }
    })
    te().analytics.mockResolvedValue(d)
    window.history.replaceState({}, '', '/?tab=channelreports&te_view=analytics&te_an_fields=sla-rate')
    render(<TechErrorReportsPage />)
    await waitFor(() => expect(field('sla-rate')).not.toBeNull())
    const [lo, hi] = field('sla-rate').querySelector('[data-slot="te-an-chart"]').dataset.yDomain.split(',').map(Number)
    expect(lo).toBeGreaterThan(99)
    expect(hi).toBeLessThanOrEqual(100)
  })

  it('seçici yalnız varsayılan şablondaki seçili alanı da listeler ve kaldırtır', async () => {
    const d = analytics()
    d.data.fields = [...d.data.fields, { id: 'quota-rate', label: 'Kota hatalarının teknik hatalara oranı', type: 'percent', type_mismatch: [] }]
    te().analytics.mockResolvedValue(d)
    window.history.replaceState({}, '', '/?tab=channelreports&te_view=analytics&te_an_fields=error-rate,total-tx,quota-rate')
    render(<TechErrorReportsPage />)
    fireEvent.click(await screen.findByRole('button', { name: /Alan seç/ }))
    const quota = await screen.findByRole('option', { name: /Kota hatalarının teknik hatalara oranı/ })
    expect(quota).toHaveAttribute('aria-checked', 'true')
    expect(quota).not.toHaveAttribute('aria-disabled', 'true')
    expect(quota).toHaveTextContent('0 kanalda')
    fireEvent.click(quota)
    await waitFor(() => expect(lastParams()).toEqual({ months: 12, fields: 'error-rate,total-tx' }))
  })

  it('analizden açılan rapor: adres te_from=analytics; "Analize dön" analizi aynı süzgeçle açar', async () => {
    window.history.replaceState({}, '', '/?tab=channelreports&te_view=analytics&te_an_months=24')
    render(<TechErrorReportsPage />)
    await waitFor(() => expect(field('total-tx')).not.toBeNull())
    fireEvent.click(within(field('total-tx')).getByRole('button', { name: /1\.250\.000 \(gönderilmedi\)/ }))
    await waitFor(() => expect(param('te_view')).toBe('report'))
    await waitFor(() => expect(param('te_from')).toBe('analytics'))
    const back = await screen.findByRole('button', { name: 'Analize dön' })
    fireEvent.click(back)
    expect(await screen.findByRole('heading', { name: 'Kanal analizi' })).toBeInTheDocument()
    await waitFor(() => expect(param('te_view')).toBe('analytics'))
    expect(param('te_from')).toBeNull()
    expect(param('te_an_months')).toBe('24')
    // İnceleme 2026-10-18 (2. tur): dönüşte İLK okuma taze — düzeltilen değer / silinen rapor sunucu belleğinden eski gelmesin
    await waitFor(() => expect(lastParams()).toEqual({ months: 24, fresh: 1 }))
    // Sonraki süzgeç değişikliği yeniden taze değil (Yenile'ye kadar normal okuma)
    fireEvent.click(screen.getByRole('button', { name: '6 ay' }))
    await waitFor(() => expect(lastParams()).toEqual({ months: 6 }))
  })

  it('inceleme 2026-10-18 (2. tur): listeden açılan analizin ilk okuması taze DEĞİL; yalnız rapordan dönüşte taze', async () => {
    render(<TechErrorReportsPage />)
    fireEvent.click(await screen.findByRole('button', { name: 'Analiz' }))
    await waitFor(() => expect(field('total-tx')).not.toBeNull())
    for (const call of te().analytics.mock.calls) expect(call[0]).not.toHaveProperty('fresh')
  })

  it('inceleme 2026-10-18 (2. tur): erişim yok (403) — yalnız erişim yok durumu; araç çubuğu (Yenile, seçiciler) ve tekrar dene yok', async () => {
    te().analytics.mockResolvedValue({ success: false, status: 403, error: 'Bu sayfayı görme yetkiniz yok.' })
    window.history.replaceState({}, '', '/?tab=channelreports&te_view=analytics&te_an_months=24')
    render(<TechErrorReportsPage />)
    await waitFor(() => expect(document.querySelector('[data-slot="empty"]')).not.toBeNull())
    expect(document.querySelector('[data-slot="te-an-toolbar"]')).toBeNull()
    expect(screen.queryByRole('button', { name: 'Yenile' })).toBeNull()
    expect(document.querySelector('[data-slot="te-an-retry"]')).toBeNull()
    expect(screen.getByRole('button', { name: 'Kanallara dön' })).toBeInTheDocument()
  })

  it('inceleme 2026-10-18 (2. tur): hata durumundaki "Tekrar dene" ve "Varsayılana dön" dokunmatikte ≥ 40 px', async () => {
    te().analytics.mockResolvedValue({ success: false, status: 500, error: 'Sunucu şu an yanıt vermiyor; birkaç dakika sonra tekrar deneyin.' })
    window.history.replaceState({}, '', '/?tab=channelreports&te_view=analytics&te_an_months=24')
    render(<TechErrorReportsPage />)
    await waitFor(() => expect(document.querySelector('[data-slot="te-an-retry"]')).not.toBeNull())
    expect(document.querySelector('[data-slot="te-an-retry"]').className).toContain('pointer-coarse:min-h-10')
    expect(document.querySelector('[data-slot="te-an-reset-error"]').className).toContain('pointer-coarse:min-h-10')
  })

  it('inceleme 2026-10-18 (2. tur): aktif kanalı olmayan seçili takım adıyla (team_name) görünür; okunurken "yükleniyor", ad okunamazsa "adı okunamayan takım" — asla çıplak "#kimlik"', async () => {
    const pending = deferred()
    te().analytics.mockImplementationOnce(() => pending.promise)
    window.history.replaceState({}, '', '/?tab=channelreports&te_view=analytics&te_an_team=5')
    const { unmount } = render(<TechErrorReportsPage />)
    const toolbar = await waitFor(() => { const el = document.querySelector('[data-slot="te-an-toolbar"]'); expect(el).not.toBeNull(); return el })
    expect(within(toolbar).getByText('Takım yükleniyor…')).toBeInTheDocument()
    expect(within(toolbar).queryByText('#5')).toBeNull()
    await act(async () => { pending.resolve(analytics({ team: '5', team_name: 'Ödeme Sistemleri', channels: [], channel_count: 0 })) })
    await waitFor(() => expect(within(document.querySelector('[data-slot="te-an-toolbar"]')).getByText('Ödeme Sistemleri')).toBeInTheDocument())
    expect(screen.queryByText('#5')).toBeNull()
    unmount()
    // Okuma hatası, ad bilinmiyor
    te().analytics.mockResolvedValue({ success: false, status: 500, error: 'Sunucu şu an yanıt vermiyor; birkaç dakika sonra tekrar deneyin.' })
    render(<TechErrorReportsPage />)
    await waitFor(() => expect(document.querySelector('[data-slot="te-an-retry"]')).not.toBeNull())
    expect(within(document.querySelector('[data-slot="te-an-toolbar"]')).getByText('Adı okunamayan takım')).toBeInTheDocument()
    expect(screen.queryByText('#5')).toBeNull()
  })

  it('kanal kartları KAP genişliğine göre iki sütun (görünüm alanına göre değil)', async () => {
    width.value = 600
    window.history.replaceState({}, '', '/?tab=channelreports&te_view=analytics')
    render(<TechErrorReportsPage />)
    await waitFor(() => expect(field('total-tx')).not.toBeNull())
    const wrap = field('total-tx').querySelector('[data-slot="te-an-table-wrap"]')
    expect(wrap.className).toContain('@container')
    const grid = wrap.querySelector('[data-slot="te-an-cards"]')
    expect(grid.className).toContain('@xl:grid-cols-2')
    expect(grid.className).not.toMatch(/(^|\s)sm:grid-cols-2/)
  })
})

// Pasif kanallar (2026-10-18, kullanıcı kararı: "pasife alınmış kanalları geçmiş aylarıyla birlikte gösterelim"): sunucu
// penceresinde raporu olan pasif kanalı aktiflerin ARDINDAN `active: false` ile gönderir — grafik anahtarında, tablo
// başlığında ve kartta "Pasif" rozeti (data-inactive), CSV başlığında "(pasif)"; `active` alanı olmayan eski yanıt rozet almaz.
const LONG_INACTIVE = 'Eski Kurumsal İnternet Şubesi Ödeme ve Nakit Yönetimi Kanalı (Kurumsal Bankacılık / Kapatıldı 2026)'

function withInactive() {
  const d = analytics()
  const n = 12
  const tx = Array(n).fill(null)
  tx[2] = 400_000
  const reps = Array(n).fill(null)
  reps[2] = { id: 900, sent: true }
  d.data.channels[0].active = true
  d.data.channels[1].active = true
  d.data.channels.push({ id: 30, name: LONG_INACTIVE, team_id: 2, team_name: 'Kurumsal Kanallar', active: false,
    points: { 'error-rate': Array(n).fill(null), 'total-tx': tx }, reports: reps })
  d.data.channel_count = 3
  return d
}

describe('kanal analizi — pasif kanallar (2026-10-18)', () => {
  it('pasif kanal: grafik anahtarında, tablo başlığında "Pasif" rozeti; sıra sunucununki (aktifler önce); hücre raporu açar', async () => {
    te().analytics.mockResolvedValue(withInactive())
    window.history.replaceState({}, '', '/?tab=channelreports&te_view=analytics')
    render(<TechErrorReportsPage />)
    await waitFor(() => expect(field('total-tx')).not.toBeNull())
    const card = field('total-tx')
    const legend = [...card.querySelectorAll('[data-slot="te-an-legend-item"]')]
    expect(legend).toHaveLength(3)
    expect(legend[2]).toHaveAttribute('data-inactive', 'true')
    expect(within(legend[2]).getByText(LONG_INACTIVE)).toBeInTheDocument()
    expect(within(legend[2]).getByText('Pasif')).toHaveAttribute('data-slot', 'te-an-inactive')
    expect(legend[0]).not.toHaveAttribute('data-inactive')
    expect(legend[1]).not.toHaveAttribute('data-inactive')
    expect(legend[0].querySelector('[data-slot="te-an-inactive"]')).toBeNull()
    // Tablo başlığı: sütun sırası sunucununki, pasif sütunda rozet
    const heads = [...card.querySelectorAll('[data-slot="te-an-table"] th[data-channel]')]
    expect(heads.map((h) => h.dataset.channel)).toEqual(['10', '20', '30'])
    expect(heads[2]).toHaveAttribute('data-inactive', 'true')
    expect(within(heads[2]).getByText('Pasif')).toBeInTheDocument()
    expect(heads[0].querySelector('[data-slot="te-an-inactive"]')).toBeNull()
    // Rozet SARAR, kırpılmaz (truncate yok; rounded-md overflow-visible)
    const badge = within(heads[2]).getByText('Pasif')
    expect(badge.className).toContain('overflow-visible')
    expect(badge.className).toContain('rounded-md')
    expect(badge.className).toContain('whitespace-normal')
    expect(badge.className).not.toContain('truncate')
    // Pasif kanalın geçmiş ayı: değer + rapor bağlantısı
    const cell = card.querySelector('[data-slot="te-an-cell"][data-report="900"]')
    expect(cell).not.toBeNull()
    expect(cell).toHaveAccessibleName(/400\.000 — .*raporunu aç/)
  })

  it('pasif kanal: dar kapta kartta da rozet (data-inactive); "N kanal daha" listesinde de rozetli', async () => {
    width.value = 400
    const d = withInactive()
    // 9 aktif + 1 pasif: pasif grafikte çizilmez, "1 kanal daha" listesine düşer (sunucu sırası: aktifler önce)
    const base = d.data.channels[1]
    const extra = []
    for (let i = 0; i < 7; i++) extra.push({ ...base, id: 40 + i, name: `Kanal ${i}`, active: true })
    d.data.channels.splice(2, 0, ...extra)
    te().analytics.mockResolvedValue(d)
    window.history.replaceState({}, '', '/?tab=channelreports&te_view=analytics')
    render(<TechErrorReportsPage />)
    await waitFor(() => expect(field('total-tx')).not.toBeNull())
    const card = field('total-tx')
    const cards = [...card.querySelectorAll('[data-slot="te-an-card"]')]
    expect(cards).toHaveLength(10)
    expect(cards[9]).toHaveAttribute('data-inactive', 'true')
    expect(within(cards[9]).getByText('Pasif')).toBeInTheDocument()
    expect(cards.slice(0, 9).every((c) => !c.hasAttribute('data-inactive'))).toBe(true)
    expect(card.querySelectorAll('[data-slot="te-an-legend-item"][data-inactive]')).toHaveLength(0)
    fireEvent.click(within(card).getByRole('button', { name: '2 kanal daha' }))
    const hidden = [...card.querySelectorAll('[data-slot="te-an-hidden-list"] li')]
    expect(hidden).toHaveLength(2)
    expect(hidden[1]).toHaveAttribute('data-inactive', 'true')
    expect(within(hidden[1]).getByText('Pasif')).toBeInTheDocument()
    expect(hidden[0]).not.toHaveAttribute('data-inactive')
  })

  it('CSV: pasif kanal sütun başlığına arayüz dilinde "(pasif)" / "(inactive)"', async () => {
    te().analytics.mockResolvedValue(withInactive())
    window.history.replaceState({}, '', '/?tab=channelreports&te_view=analytics')
    const { unmount } = render(<TechErrorReportsPage />)
    await waitFor(() => expect(field('total-tx')).not.toBeNull())
    fireEvent.click(within(field('total-tx')).getByRole('button', { name: 'Toplam işlem adedi tablosunu CSV olarak indir' }))
    const head = (body) => body.slice(body.charCodeAt(0) === 0xfeff ? 1 : 0).split('\r\n')[0]
    const tr = head(csv.downloadCsv.mock.calls[0][1])
    expect(tr).toContain(`${LONG_INACTIVE} (Kurumsal Kanallar) (pasif)`)
    expect(tr).toContain('POS (Kurumsal Kanallar),')
    expect(tr).not.toContain('POS (Kurumsal Kanallar) (pasif)')
    unmount()
    localStorage.setItem(LANG_STORAGE_KEY, 'en')
    render(<TechErrorReportsPage />)
    await waitFor(() => expect(field('total-tx')).not.toBeNull())
    fireEvent.click(within(field('total-tx')).getByRole('button', { name: /CSV/ }))
    const en = head(csv.downloadCsv.mock.calls.at(-1)[1])
    expect(en).toContain(`${LONG_INACTIVE} (Kurumsal Kanallar) (inactive)`)
    expect(within(field('total-tx')).getAllByText('Inactive').length).toBeGreaterThan(0)
  })

  it('eski sunucu (active alanı yok): kanallar aktif sayılır — rozet ve data-inactive yok, CSV etiketsiz', async () => {
    window.history.replaceState({}, '', '/?tab=channelreports&te_view=analytics')
    render(<TechErrorReportsPage />)
    await waitFor(() => expect(field('total-tx')).not.toBeNull())
    expect(document.querySelector('[data-slot="te-an-inactive"]')).toBeNull()
    expect(document.querySelector('[data-inactive]')).toBeNull()
    fireEvent.click(within(field('total-tx')).getByRole('button', { name: 'Toplam işlem adedi tablosunu CSV olarak indir' }))
    expect(csv.downloadCsv.mock.calls[0][1]).not.toContain('(pasif)')
  })
})
