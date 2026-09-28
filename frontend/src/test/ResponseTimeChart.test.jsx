import { describe, it, expect, vi, beforeEach, beforeAll, afterAll } from 'vitest'
import { StrictMode } from 'react'
import { render, screen, waitFor, fireEvent, within, act } from './test-utils'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  api: withApiFallback({
    monitoring: {
      getScriptedResponseSeries: vi.fn(),
      getKeywordResponseSeries: vi.fn(),
      getPageSpeedSeries: vi.fn(),
      getPingResponseSeries: vi.fn(),
    },
  }),
  formatDate: (s) => String(s),
  formatDateSec: (s) => String(s),
}))

import { api } from '../api/client'
import ResponseTimeChart from '../components/ResponseTimeChart.jsx'

const HOUR = 3_600_000
// Sunucu biçimi (UTC, Z'siz) — fixture damgaları now'dan türetilir (sabit tarih kayan pencerede zaman bombasıdır).
const at = (hoursAgo) => new Date(Date.now() - hoursAgo * HOUR).toISOString().slice(0, 19)
const envelope = (series) => ({ success: true, data: { series, bucket: 'hour', unit: 'ms', from: at(24), to: at(0), total: series.length, down_total: 0, capped: false } })
const bucket = (hoursAgo, avg, extra = {}) => ({ ts: at(hoursAgo), avg, min: avg, max: avg, p95: avg, count: 2, down: 0, ...extra })
function deferred() {
  let resolve
  const promise = new Promise((res) => { resolve = res })
  return { promise, resolve }
}
const tile = (id) => document.querySelector(`[data-slot="chart-tile"][data-kpi="${id}"]`)
const tileValue = (id) => tile(id)?.querySelector('[data-slot="chart-tile-value"]')?.textContent

beforeEach(() => { vi.clearAllMocks() })

// jsdom yerleşim yapmaz: recharts'ın ResponsiveContainer'ı kabını 0×0 ölçer ve SVG'nin İÇİNİ hiç çizmez (eşik çizgisi,
// eksenler görünmez). YALNIZ o kabın ölçüsü verilir (dar sahte; başka hiçbir öğenin ölçüsü değişmez) — yerleşim
// iddiası DEĞİL, "şu işaret çizildi mi" iddiası için. Yerleşim Playwright'ta ölçülür.
let restoreRect
beforeAll(() => {
  const orig = HTMLElement.prototype.getBoundingClientRect
  HTMLElement.prototype.getBoundingClientRect = function () {
    if (this.classList?.contains('recharts-responsive-container')) {
      return { x: 0, y: 0, top: 0, left: 0, width: 640, height: 300, right: 640, bottom: 300, toJSON() {} }
    }
    return orig.call(this)
  }
  restoreRect = () => { HTMLElement.prototype.getBoundingClientRect = orig }
})
afterAll(() => restoreRect?.())

/** 2026-08 scripted regresyonu: endpoint ham Object[] (dizi-içinde-dizi) döndüğünde s.ts
 *  undefined kalıyor ve tickLabel'daki endsWith TÜM EKRANI ErrorBoundary'ye düşürüyordu.
 *  Bu suite hem normal render'ı hem de bozuk beslemenin artık yalnız "veri yok"a düşmesini pinler. */
describe('ResponseTimeChart', () => {
  it('normal seri: aralık butonları + grafik render olur, "veri yok" görünmez', async () => {
    api.monitoring.getScriptedResponseSeries.mockResolvedValue(envelope([
      { ts: '2026-08-06T10:00:00', avg: 120, min: 90, max: 150, p95: 145, count: 3, down: 0 },
      { ts: '2026-08-06T11:00:00', avg: 130, min: 100, max: 160, p95: 155, count: 3, down: 1 },
    ]))
    render(<ResponseTimeChart monitorId={7} kind="scripted" />)
    await waitFor(() => expect(api.monitoring.getScriptedResponseSeries).toHaveBeenCalled())
    expect(screen.queryByText('No data in this range')).toBeNull()
    expect(screen.getByText('Custom')).toBeInTheDocument()
  })

  /**
   * Saatlik pencereler: en kucuk aralik 24 saatti ve backend <=48 saatte 10 dakikalik kova
   * kullandigi icin olcumler ortalamaya karisiyordu. Gun cinsinden ifade edilemedikleri icin
   * bu pencereler ACIK from/to gonderir — days ile gondermek 1 gune yuvarlardi.
   */
  it('saatlik aralik acik from/to gonderir (days DEGIL)', async () => {
    api.monitoring.getScriptedResponseSeries.mockResolvedValue(envelope([]))
    render(<ResponseTimeChart monitorId={7} kind="scripted" />)
    await waitFor(() => expect(api.monitoring.getScriptedResponseSeries).toHaveBeenCalled())

    fireEvent.click(screen.getByRole('button', { name: '1 hr' }))

    await waitFor(() => {
      const last = api.monitoring.getScriptedResponseSeries.mock.calls.at(-1)[1]
      expect(last.days).toBeUndefined()
      expect(last.from).toBeTruthy()
      expect(last.to).toBeTruthy()
      // Pencere gercekten ~1 saat olmali; yanlis hesap sessizce baska bir araligi cizerdi.
      const span = new Date(last.to + 'Z') - new Date(last.from + 'Z')
      expect(span).toBeGreaterThan(55 * 60 * 1000)
      expect(span).toBeLessThan(65 * 60 * 1000)
    })
  })

  it('bozuk besleme (ham dizi elemanları, ts yok) → ÇÖKMEZ, "veri yok" gösterir', async () => {
    // Bug reprodüksiyonu: Jackson'ın Object[] serileştirmesi — her eleman obje değil DİZİ
    api.monitoring.getScriptedResponseSeries.mockResolvedValue(envelope([
      ['2026-08-06T21:43:00', 0, false],
      ['2026-08-06T21:44:00', 340, true],
    ]))
    render(<ResponseTimeChart monitorId={7} kind="scripted" />)
    await waitFor(() => expect(api.monitoring.getScriptedResponseSeries).toHaveBeenCalled())
    expect(await screen.findByText('No data in this range')).toBeInTheDocument()
  })

  it('ts alanı eksik obje karışığı → geçerli kayıtlar çizilir, bozuklar sessizce atlanır', async () => {
    api.monitoring.getScriptedResponseSeries.mockResolvedValue(envelope([
      { avg: 120, count: 1, down: 0 },                                        // ts yok — atlanır
      { ts: '2026-08-06T10:00:00', avg: 130, min: 100, max: 160, p95: 155, count: 2, down: 0 },
    ]))
    render(<ResponseTimeChart monitorId={7} kind="scripted" />)
    await waitFor(() => expect(api.monitoring.getScriptedResponseSeries).toHaveBeenCalled())
    await waitFor(() => expect(tileValue('avg')).toBe('130 ms'))
    expect(screen.queryByText('No data in this range')).toBeNull()
  })

  // ── Varsayılan aralık ───────────────────────────────────────────────────────
  //
  // 2026-08-16: varsayılan 30 GÜN'den 24 SAAT'e çekildi (kullanıcı isteği, tüm izleme türlerinde).
  // Bu değerin testi YOKTU: '30d' → '24h' değişikliği 823 testin hiçbirini kırmadı. Sessizce
  // eski değere dönerse kimse fark etmez, oysa grafik "şu an ne oluyor" sorusuna bakılan yer —
  // 30 günlük pencere son birkaç saatteki dalgalanmayı kova ortalamasında eritiyordu.

  it('VARSAYILAN aralık 24 saat: istek days=1 ile gider', async () => {
    api.monitoring.getScriptedResponseSeries.mockResolvedValue(envelope([]))
    render(<ResponseTimeChart monitorId={7} kind="scripted" />)
    await waitFor(() => expect(api.monitoring.getScriptedResponseSeries).toHaveBeenCalled())

    const [, params] = api.monitoring.getScriptedResponseSeries.mock.calls[0]
    expect(params).toEqual({ days: 1 })
  })

  it('24h düğmesi açılışta SEÇİLİ görünür (kullanıcı hangi aralığa baktığını görür)', async () => {
    api.monitoring.getScriptedResponseSeries.mockResolvedValue(envelope([]))
    render(<ResponseTimeChart monitorId={7} kind="scripted" />)
    await waitFor(() => expect(api.monitoring.getScriptedResponseSeries).toHaveBeenCalled())

    // Aralık seçici ui/SegmentedControl (shadcn ToggleGroup): seçili öğe aria-pressed, diğerleri değil
    const group = screen.getByRole('group', { name: /time range|zaman aralığı/i })
    const selected = [...group.querySelectorAll('button[aria-pressed="true"]')].map(b => b.textContent.trim())
    expect(selected).toEqual(['24h'])
    expect(group.querySelector('button[aria-pressed="false"]').textContent.trim()).toBe('1 hr')
    // Telefon karşılığı (NativeSelect) da aynı değeri taşır — iki kontrol ayrışmaz.
    expect(screen.getByRole('combobox', { name: /time range|zaman aralığı/i })).toHaveValue('24h')
  })

  // ── Yeniden tasarım (2026-09-28): özet kutucukları + seri anahtarları ─────────────────────

  it('özet kutucukları pencereden türer (ağırlıklı ort / p95 tepe / en yüksek + en düşük / erişilebilirlik + örnek / başarısız)', async () => {
    api.monitoring.getScriptedResponseSeries.mockResolvedValue(envelope([
      { ts: '2026-08-06T10:00:00', avg: 120, min: 90, max: 150, p95: 145, count: 3, down: 0 },
      { ts: '2026-08-06T11:00:00', avg: 130, min: 100, max: 160, p95: 155, count: 1, down: 1 },
    ]))
    render(<ResponseTimeChart monitorId={7} kind="scripted" />)
    await waitFor(() => expect(tile('avg')).not.toBeNull())
    // ort = (120·3 + 130·1) / 4 = 122.5 → 123; p95 tepe = 155 (kovalı pencerede TEPE diye etiketli); en düşük 90;
    // en yüksek 160; başarısız 1; örnek 4 → erişilebilirlik 3/4 = %75
    expect(tileValue('avg')).toBe('123 ms')
    expect(tileValue('p95')).toBe('155 ms')
    expect(tile('p95')).toHaveTextContent('p95 (peak)')
    expect(tileValue('max')).toBe('160 ms')
    expect(tile('max')).toHaveTextContent('lowest 90 ms')
    expect(tileValue('availability')).toBe('75%')
    expect(tile('availability')).toHaveTextContent('3/4 passed')
    expect(tileValue('failed')).toBe('1')
    expect(tile('failed')).toHaveAttribute('data-tone', 'crit')
    // Kutucuklar ekran okuyucuda okunur (düğme adı = etiket + değer) ve açıklaması dokunarak açılır (yalnız-hover yok).
    const summary = screen.getByRole('group', { name: 'Summary' })
    const avail = within(summary).getByRole('button', { name: /Availability\s*75%/ })
    fireEvent.click(avail)
    expect(await screen.findByRole('tooltip')).toHaveTextContent('The share of checks that passed.')
  })

  it('seri anahtarları shadcn ToggleGroup: basılı = görünür; basınca seri gizlenir (aria-pressed=false)', async () => {
    api.monitoring.getScriptedResponseSeries.mockResolvedValue(envelope([
      { ts: '2026-08-06T10:00:00', avg: 120, min: 90, max: 150, p95: 145, count: 3, down: 0 },
    ]))
    render(<ResponseTimeChart monitorId={7} kind="scripted" />)
    const avg = await waitFor(() => {
      const el = document.querySelector('[data-slot="chart-series"] [data-series="avg"]')
      expect(el).not.toBeNull()
      return el
    })
    expect(avg.tagName).toBe('BUTTON')
    expect(avg).toHaveAttribute('aria-pressed', 'true')
    fireEvent.click(avg)
    expect(avg).toHaveAttribute('aria-pressed', 'false')
    expect(document.querySelector('[data-slot="chart-series"] [data-series="p95"]')).toHaveAttribute('aria-pressed', 'true')
  })

  it('veri yokken boş durum ipucuyla; dönen spinner YOK; aralığı genişletme önerisi çalışır', async () => {
    api.monitoring.getScriptedResponseSeries.mockResolvedValue(envelope([]))
    render(<ResponseTimeChart monitorId={7} kind="scripted" />)
    expect(await screen.findByText('No data in this range')).toBeInTheDocument()
    expect(screen.queryByRole('status')).toBeNull()
    expect(document.querySelector('[data-slot="chart-tile"]')).toBeNull()

    fireEvent.click(screen.getByRole('button', { name: 'Widen to the last 7 days' }))
    await waitFor(() => expect(api.monitoring.getScriptedResponseSeries.mock.calls.at(-1)[1]).toEqual({ days: 7 }))
    const group = screen.getByRole('group', { name: /time range/i })
    expect(group.querySelector('button[aria-pressed="true"]').textContent.trim()).toBe('7d')
  })

  // ── Durumlar: eskiyi göster + yarış koruması, hedef değişimi, hata ─────────────────────────

  it('aralık değişince ESKİ grafik soluk kalır (iskelet yok); geç dönen eski yanıt yeniyi EZMEZ', async () => {
    const fn = api.monitoring.getScriptedResponseSeries
    const first = deferred()
    const week = deferred()
    const month = deferred()
    fn.mockReset()
    fn.mockReturnValueOnce(first.promise).mockReturnValueOnce(week.promise).mockReturnValueOnce(month.promise)
    render(<ResponseTimeChart monitorId={7} kind="scripted" />)
    await act(async () => { first.resolve(envelope([bucket(3, 100)])) })
    await waitFor(() => expect(tileValue('avg')).toBe('100 ms'))

    fireEvent.click(screen.getByRole('button', { name: '7d' }))
    await waitFor(() => expect(fn).toHaveBeenCalledTimes(2))
    expect(tileValue('avg')).toBe('100 ms')                                       // eski veri yerinde
    expect(document.querySelector('[data-slot="chart-skeleton"]')).toBeNull()     // titreme yok
    expect(document.querySelector('[data-stale="true"]')).not.toBeNull()          // soluk
    expect(screen.getByText('Updating chart')).toBeInTheDocument()               // küçük spinner duyurusu

    fireEvent.click(screen.getByRole('button', { name: '30d' }))
    await waitFor(() => expect(fn).toHaveBeenCalledTimes(3))
    await act(async () => { month.resolve(envelope([bucket(3, 300)])) })
    await waitFor(() => expect(tileValue('avg')).toBe('300 ms'))
    expect(document.querySelector('[data-stale="true"]')).toBeNull()

    // Yavaş dönen 7 günlük (ESKİ tur) yanıt gelir: yok sayılır, bayrak da yeniden kalkmaz.
    await act(async () => { week.resolve(envelope([bucket(3, 200)])) })
    expect(tileValue('avg')).toBe('300 ms')
    expect(screen.queryByText('Updating chart')).toBeNull()
    expect(document.querySelector('[data-stale="true"]')).toBeNull()
  })

  it('StrictMode (kur → temizle → kur) altında da veri yazılır — yaşam bayrağı kalıcı "ölü" kalmaz', async () => {
    api.monitoring.getScriptedResponseSeries.mockResolvedValue(envelope([bucket(3, 140)]))
    render(<StrictMode><ResponseTimeChart monitorId={7} kind="scripted" /></StrictMode>)
    await waitFor(() => expect(tileValue('avg')).toBe('140 ms'))
    expect(document.querySelector('[data-slot="chart-skeleton"]')).toBeNull()
  })

  it('izleme değişince ESKİ izlemenin verisi hiç gösterilmez (iskelet), yenisi gelince çizilir', async () => {
    const fn = api.monitoring.getScriptedResponseSeries
    const second = deferred()
    fn.mockReset()
    fn.mockResolvedValueOnce(envelope([bucket(3, 100)])).mockReturnValueOnce(second.promise)
    const { rerender } = render(<ResponseTimeChart monitorId={7} kind="scripted" />)
    await waitFor(() => expect(tileValue('avg')).toBe('100 ms'))

    rerender(<ResponseTimeChart monitorId={8} kind="scripted" />)
    await waitFor(() => expect(fn).toHaveBeenLastCalledWith(8, { days: 1 }))
    expect(tile('avg')).toBeNull()
    expect(document.querySelector('[data-slot="chart-skeleton"]')).not.toBeNull()

    await act(async () => { second.resolve(envelope([bucket(3, 250)])) })
    await waitFor(() => expect(tileValue('avg')).toBe('250 ms'))
  })

  it('hata (success:false): AlertBanner + "Try again" → yeniden istek; başarıda banner kalkar, grafik çizilir', async () => {
    const fn = api.monitoring.getScriptedResponseSeries
    fn.mockReset()
    fn.mockResolvedValueOnce({ success: false, error: 'boom' }).mockResolvedValueOnce(envelope([bucket(3, 120)]))
    render(<ResponseTimeChart monitorId={7} kind="scripted" />)
    const title = await screen.findByText("Couldn't load the chart data")
    const banner = title.closest('[data-slot="alert"]')
    expect(banner).toHaveAttribute('data-tone', 'danger')
    expect(banner).toHaveTextContent('boom')
    expect(tile('avg')).toBeNull()
    expect(document.querySelector('[data-slot="chart-skeleton"]')).toBeNull()

    fireEvent.click(within(banner).getByRole('button', { name: 'Try again' }))
    await waitFor(() => expect(tileValue('avg')).toBe('120 ms'))
    expect(screen.queryByText("Couldn't load the chart data")).toBeNull()
    expect(fn).toHaveBeenCalledTimes(2)
  })

  it('ağ hatası (istisna) da hata durumudur; yükleme bayrağı asılı kalmaz, yeniden dene boş aralığa düşer', async () => {
    const fn = api.monitoring.getScriptedResponseSeries
    fn.mockReset()
    fn.mockRejectedValueOnce(new Error('Network down')).mockResolvedValueOnce(envelope([]))
    render(<ResponseTimeChart monitorId={7} kind="scripted" />)
    await screen.findByText("Couldn't load the chart data")
    // Yükleniyor göstergeleri inmiş: iskelet/duyuru yok, yenile düğmesi kilitli değil.
    expect(document.querySelector('[data-slot="chart-skeleton"]')).toBeNull()
    expect(screen.queryByText('Loading chart')).toBeNull()
    expect(screen.getByRole('button', { name: 'Refresh chart' })).not.toBeDisabled()
    expect(screen.getByRole('button', { name: 'Refresh chart' })).not.toHaveAttribute('aria-busy')
    fireEvent.click(screen.getByRole('button', { name: 'Try again' }))
    expect(await screen.findByText('No data in this range')).toBeInTheDocument()
  })

  it('aralık değişimi başarısız olursa son veri SOLUK kalır ve bu açıkça söylenir', async () => {
    const fn = api.monitoring.getScriptedResponseSeries
    fn.mockReset()
    fn.mockResolvedValueOnce(envelope([bucket(3, 100)])).mockResolvedValueOnce({ success: false })
    render(<ResponseTimeChart monitorId={7} kind="scripted" />)
    await waitFor(() => expect(tileValue('avg')).toBe('100 ms'))
    fireEvent.click(screen.getByRole('button', { name: '7d' }))
    expect(await screen.findByText(/The selected range couldn't be loaded/)).toBeInTheDocument()
    expect(document.querySelector('[data-stale="true"]')).not.toBeNull()
    expect(tileValue('avg')).toBe('100 ms')
  })

  // ── Eşik çizgisi ────────────────────────────────────────────────────────────────────────

  it('yavaş yanıt eşiği verilince etiketli çip + grafikte eşik çizgisi; verilmeyince ikisi de yok', async () => {
    api.monitoring.getKeywordResponseSeries.mockResolvedValue(envelope([bucket(3, 120, { max: 160 })]))
    const { unmount } = render(<ResponseTimeChart monitorId={3} kind="keyword" slowThreshold={150} />)
    const chip = await waitFor(() => {
      const el = document.querySelector('[data-slot="chart-threshold"]')
      expect(el).not.toBeNull()
      return el
    })
    expect(chip).toHaveTextContent('Slow response threshold: 150 ms')
    expect(chip).toHaveAttribute('data-in-view', 'true')
    expect(document.querySelector('[data-slot="chart-main"] .recharts-reference-line')).not.toBeNull()
    unmount()

    render(<ResponseTimeChart monitorId={3} kind="keyword" />)
    await waitFor(() => expect(tile('avg')).not.toBeNull())
    expect(document.querySelector('[data-slot="chart-threshold"]')).toBeNull()
    expect(document.querySelector('[data-slot="chart-main"] .recharts-reference-line')).toBeNull()
  })

  it('sayfa hızı bütçesi kendi biriminde (bayt) çizilir; verinin çok üstündeki eşik ekseni EZMEZ, çipte söylenir', async () => {
    api.monitoring.getPageSpeedSeries.mockResolvedValue(envelope([bucket(3, 2_000_000, { max: 2_200_000 })]))
    const { unmount } = render(<ResponseTimeChart monitorId={4} kind="pagespeed" metric="size" unit="B"
      budget={2_500_000} budgetLabel="budget (threshold)" />)
    const chip = await waitFor(() => {
      const el = document.querySelector('[data-slot="chart-threshold"]')
      expect(el).not.toBeNull()
      return el
    })
    expect(chip).toHaveTextContent('Budget (threshold): 2.4 MB')
    expect(document.querySelector('[data-slot="chart-main"] .recharts-reference-line')).not.toBeNull()
    unmount()

    api.monitoring.getKeywordResponseSeries.mockResolvedValue(envelope([bucket(3, 120, { max: 160 })]))
    render(<ResponseTimeChart monitorId={3} kind="keyword" slowThreshold={5000} />)
    const far = await waitFor(() => {
      const el = document.querySelector('[data-slot="chart-threshold"]')
      expect(el).not.toBeNull()
      return el
    })
    expect(far).toHaveAttribute('data-in-view', 'false')
    expect(far).toHaveTextContent('above the chart')
    expect(document.querySelector('[data-slot="chart-main"] .recharts-reference-line')).toBeNull()
  })

  // ── Erişilebilirlik ─────────────────────────────────────────────────────────────────────

  it('erişilebilir özet: grafik figürünün adı başlık, açıklaması aralığın metin özeti', async () => {
    api.monitoring.getScriptedResponseSeries.mockResolvedValue(envelope([
      { ts: at(5), avg: 120, min: 90, max: 150, p95: 145, count: 3, down: 0 },
      { ts: at(4), avg: 130, min: 100, max: 160, p95: 155, count: 1, down: 1 },
    ]))
    render(<ResponseTimeChart monitorId={7} kind="scripted" />)
    const fig = await screen.findByRole('figure', { name: 'Script run time' })
    expect(fig).toHaveAccessibleDescription(
      'In the last 24 hours, the average was 123 ms and the highest 160 ms; availability 75%, 1 failed check.')
  })

  it('ping: paket kaybı ayrı küçük grafikte (ikinci y ekseni YOK) + kendi kutucuğu ve seri anahtarı', async () => {
    api.monitoring.getPingResponseSeries.mockResolvedValue(envelope([
      bucket(3, 12, { loss: 0 }), bucket(2, 14, { loss: 50 }),
    ]))
    render(<ResponseTimeChart monitorId={5} kind="ping" />)
    await waitFor(() => expect(tileValue('loss')).toBe('25%'))
    expect(document.querySelector('[data-slot="chart-aux"]')).toHaveTextContent('Packet loss')
    expect(document.querySelector('[data-slot="chart-series"] [data-series="aux"]')).toHaveAttribute('aria-pressed', 'true')
    // Ana grafik tek y eksenli: ikinci (sağ) eksen artık yok.
    expect(document.querySelectorAll('[data-slot="chart-main"] .recharts-yAxis')).toHaveLength(1)
  })
})
