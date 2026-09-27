import { describe, it, expect, vi } from 'vitest'
import { render, screen, waitFor, fireEvent } from './test-utils'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  api: withApiFallback({
    monitoring: {
      getScriptedResponseSeries: vi.fn(),
      getKeywordResponseSeries: vi.fn(),
    },
  }),
  formatDate: (s) => String(s),
  formatDateSec: (s) => String(s),
}))

import { api } from '../api/client'
import ResponseTimeChart from '../components/ResponseTimeChart.jsx'

/** 2026-08 scripted regresyonu: endpoint ham Object[] (dizi-içinde-dizi) döndüğünde s.ts
 *  undefined kalıyor ve tickLabel'daki endsWith TÜM EKRANI ErrorBoundary'ye düşürüyordu.
 *  Bu suite hem normal render'ı hem de bozuk beslemenin artık yalnız "veri yok"a düşmesini pinler. */
describe('ResponseTimeChart', () => {
  const envelope = (series) => ({ success: true, data: { series, bucket: 'hour', unit: 'ms', from: 'a', to: 'b', total: series.length, down_total: 0, capped: false } })

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
  })

  // ── Yeniden tasarım (2026-09-27): özet kutucukları + seri anahtarları ─────────────────────

  it('özet kutucukları pencereden türer (min / ağırlıklı ort / p95 tepe / maks / kesinti / örnek)', async () => {
    api.monitoring.getScriptedResponseSeries.mockResolvedValue(envelope([
      { ts: '2026-08-06T10:00:00', avg: 120, min: 90, max: 150, p95: 145, count: 3, down: 0 },
      { ts: '2026-08-06T11:00:00', avg: 130, min: 100, max: 160, p95: 155, count: 1, down: 1 },
    ]))
    render(<ResponseTimeChart monitorId={7} kind="scripted" />)
    const tiles = await waitFor(() => {
      const els = [...document.querySelectorAll('[data-slot="chart-tile-value"]')]
      expect(els).toHaveLength(6)
      return els
    })
    // ort = (120·3 + 130·1) / 4 = 122.5 → 123; p95 tepe = 155; kesinti 1; örnek 4
    expect(tiles.map(x => x.textContent)).toEqual(['90ms', '123ms', '155ms', '160ms', '1', '4'])
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

  it('veri yokken boş durum ipucuyla; dönen spinner YOK', async () => {
    api.monitoring.getScriptedResponseSeries.mockResolvedValue(envelope([]))
    render(<ResponseTimeChart monitorId={7} kind="scripted" />)
    expect(await screen.findByText('No data in this range')).toBeInTheDocument()
    expect(screen.queryByRole('status')).toBeNull()
    expect(document.querySelector('[data-slot="chart-tile"]')).toBeNull()
  })
})
