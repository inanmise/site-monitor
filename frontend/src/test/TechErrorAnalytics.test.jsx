import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'
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
    expect(screen.queryByText('İncelenecek aktif kanal yok')).toBeNull()
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
    expect(await screen.findByText('İncelenecek aktif kanal yok')).toBeInTheDocument()
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
