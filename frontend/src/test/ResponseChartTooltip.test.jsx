import { describe, it, expect, vi } from 'vitest'
import { render, screen } from './test-utils'

vi.mock('../api/client', () => ({
  api: {},
  formatDate: (s) => `D(${s})`,
  formatDateSec: (s) => String(s),
}))

import { useT } from '../i18n/index.jsx'
import { TooltipCard } from '../components/responsechart/ChartTooltipCard.jsx'
import { BUCKET_MS, makeFormat, shapeSeries } from '../components/responsechart/responseChartModel.js'

/**
 * Grafik ipucu içeriği — türe göre (ping paket kaybı, sertifika kalan gün, sayfa hızı bayt biçimi) ve durumlara göre
 * (başarısız kova, değersiz kova, boşluk noktası, eşik üstü). Recharts ipucu jsdom'da fareyle açılamadığı için
 * içerik bileşeni doğrudan, gerçek tel biçiminden (`shapeSeries`) üretilmiş noktayla sınanır.
 */
const H = 3_600_000
const at = (hoursAgo) => new Date(Date.now() - hoursAgo * H).toISOString().slice(0, 19)
/** Tel biçimi satırı → modelin çizdiği nokta. */
const point = (row, { bucket = 'hour', aux = null } = {}) =>
  shapeSeries({ series: [{ ts: at(2), count: 1, down: 0, ...row }], bucket }, { aux }).points[0]

function Tip({ p, unit = 'ms', bucketMs = BUCKET_MS.hour, title = 'Response time', aux = null, threshold = null }) {
  const t = useT()
  const fmt = makeFormat(unit, { ms: t('rtc.unit.ms'), sec: t('rtc.unit.s') })
  return <TooltipCard active payload={[{ payload: p }]} t={t} fmt={fmt} bucketMs={bucketMs} title={title} aux={aux} threshold={threshold} />
}
const rows = () => [...document.querySelectorAll('[data-slot="chart-tip-row"]')].map((r) => r.textContent)

describe('TooltipCard', () => {
  it('ping: kova özeti + paket kaybı + başarısız kontrol ve ayrıntının yeri (hata metni UYDURULMAZ)', () => {
    const p = point({ avg: 12, min: 8, max: 30, p95: 25, count: 4, down: 1, loss: 25 }, { aux: 'loss' })
    render(<Tip p={p} aux="loss" title="Round-trip time (RTT)" />)
    expect(rows()).toEqual(['Average12 ms', 'p9525 ms', 'min–max8 ms – 30 ms', 'Packet loss25%', 'Checks4'])
    expect(screen.getByText('1 of 4 checks failed')).toBeInTheDocument()
    expect(screen.getByText('Error details: Check History tab')).toBeInTheDocument()
    // Başlık: kurum saatiyle kova başı (formatDate) + kova sonu
    expect(document.querySelector('[data-slot="chart-tooltip"]').textContent).toContain(`D(${p.ts})`)
  })

  it('sertifika: tek kontrollük dakika kovası tek değer satırı + kalan gün; aralık sonu yazılmaz', () => {
    const p = point({ avg: 340, min: 340, max: 340, p95: 340, days: 64 }, { bucket: 'minute', aux: 'days' })
    render(<Tip p={p} aux="days" title="TLS check time" bucketMs={BUCKET_MS.minute} />)
    expect(rows()).toEqual(['TLS check time340 ms', 'Days Remaining64 d', 'Checks1'])
    expect(screen.getByText('All checks passed')).toBeInTheDocument()
    expect(document.querySelector('[data-slot="chart-tooltip"] > div').textContent).toBe(`D(${p.ts})`)
  })

  it('sayfa hızı boyutu bayt biçiminde (formatBytes) — "ms" eki YOK', () => {
    const p = point({ avg: 2_500_000, min: 1_900_000, max: 3_300_000, p95: 3_200_000, count: 3 })
    render(<Tip p={p} unit="B" title="Size" />)
    expect(rows()).toEqual(['Average2.4 MB', 'p953.1 MB', 'min–max1.8 MB – 3.1 MB', 'Checks3'])
    expect(document.querySelector('[data-slot="chart-tooltip"]').textContent).not.toMatch(/\d\s?ms/)
  })

  it('tüm kontrolleri başarısız kova: "Ölçüm yok" + başarısız sayısı', () => {
    const p = point({ avg: null, min: null, max: null, p95: null, count: 3, down: 3 })
    render(<Tip p={p} />)
    expect(rows()[0]).toBe('Response timeNo measurement')
    expect(screen.getByText('3 of 3 checks failed')).toBeInTheDocument()
  })

  it('eşik üstü kova rozetle işaretlenir; eşik altında rozet yok', () => {
    const over = point({ avg: 400, count: 2, min: 350, max: 450, p95: 440 })
    const { unmount } = render(<Tip p={over} threshold={300} />)
    expect(screen.getByText('Above the threshold')).toHaveAttribute('data-slot', 'badge')
    unmount()
    render(<Tip p={point({ avg: 200, count: 2, min: 150, max: 250, p95: 240 })} threshold={300} />)
    expect(screen.queryByText('Above the threshold')).toBeNull()
  })

  it('boşluk noktası ve etkin olmayan ipucu hiçbir şey çizmez', () => {
    const { container } = render(<Tip p={{ t: Date.now(), gap: true }} />)
    expect(container.querySelector('[data-slot="chart-tooltip"]')).toBeNull()
    const t = (k) => k
    const { container: c2 } = render(<TooltipCard active={false} payload={[]} t={t} fmt={makeFormat('ms')} bucketMs={H} title="x" />)
    expect(c2.querySelector('[data-slot="chart-tooltip"]')).toBeNull()
  })
})
