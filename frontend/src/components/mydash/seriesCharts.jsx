import { cn } from '@/lib/utils'

/**
 * Kişisel Panom zaman serisi kutularının HAFİF çizimleri (2026-11-14) — kütüphane yok (recharts pano parçasına girmez):
 * sayım çubukları (alarm eğilimi), boşluklu mini çizgi (yanıt süresi) ve yarım daire gösterge (erişilebilirlik).
 *
 * Kurallar: seri renkleri DURUM rengi değildir (mavi `--chart-1`, mor `--chart-5`; yeşil / kırmızı / kehribar yalnız gerçek
 * durum için); ölçümü olmayan kova BOŞLUKTUR (çizgi kesilir, 0 çizilmez); komşusu boş tek nokta NOKTA olarak çizilir; her
 * çizimin metin karşılığı çağıranda (görünmez liste / yanındaki sayılar) — çizimin kendisi tek bir `role="img"` adıdır.
 */

/** Seri renkleri — açıklama (legend) ve çubuklar aynı sınıfı kullanır. */
export const SERIES_CLASS = { opened: 'bg-chart-1', closed: 'bg-chart-5' }

/**
 * Kova başına iki çubuk (açılan / kapanan). Sayım kovasında 0 gerçek değerdir: çubuk çizilmez (boşluk değil — o saatte alarm
 * yok). Sıfırdan büyük en küçük değer de görünür kalsın diye çubuk en az 2 px. Yatay etiketler `ticks` ile (çarpışmasın diye
 * seyrek); ilk etiket sola, son etiket sağa yaslanır — kutunun dışına taşmaz. TV'de grafik hücrenin KALAN yüksekliğini doldurur
 * (en az 5 rem): sık sayfada kısa kalıp satırı uzatmaz, seyrek sayfada boşluğu kullanır.
 *
 * @param points  [{ t, opened, closed, label, title }] — eskiden yeniye
 * @param ticks   etiketi gösterilecek kova sıraları
 */
export function TrendBars({ points, max, ticks = [], label, tv = false }) {
  const top = Math.max(1, Number(max) || 0)
  const h = (v) => (v > 0 ? `max(2px, ${(v * 100) / top}%)` : '0%')
  const tickSet = new Set(ticks)
  const last = points.length - 1
  return (
    <div data-slot="mydash-trend-chart" role="img" aria-label={label} className={cn('min-w-0', tv && 'flex min-h-0 flex-1 flex-col')}>
      <div className={cn('flex items-end gap-px border-b border-border', tv ? 'min-h-20 flex-1' : 'h-24')} aria-hidden="true">
        {points.map((p) => (
          <div key={p.t} data-slot="mydash-trend-col" data-opened={p.opened} data-closed={p.closed} title={p.title}
            className="flex h-full min-w-0 flex-1 items-end justify-center gap-px">
            <span className={cn('w-1/2 max-w-4 rounded-t-sm', SERIES_CLASS.opened)} style={{ height: h(p.opened) }} />
            <span className={cn('w-1/2 max-w-4 rounded-t-sm opacity-80', SERIES_CLASS.closed)} style={{ height: h(p.closed) }} />
          </div>
        ))}
      </div>
      <div className={cn('flex gap-px text-muted-foreground', tv ? 'h-6 text-sm' : 'h-4 text-[10px]')} aria-hidden="true" data-slot="mydash-trend-ticks">
        {points.map((p, i) => (
          <span key={p.t} className="relative min-w-0 flex-1">
            {tickSet.has(i) && (
              <span data-slot="mydash-trend-tick"
                className={cn('absolute top-0.5 leading-none whitespace-nowrap tabular-nums',
                  i === 0 ? 'left-0' : i === last ? 'right-0' : 'left-1/2 -translate-x-1/2')}>
                {p.label}
              </span>
            )}
          </span>
        ))}
      </div>
    </div>
  )
}

/**
 * Boşluklu mini çizgi: `slots` eskiden yeniye, `ms: null` = ölçüm yok → çizgi orada KESİLİR. Ardışık ölçümler çizgi, komşusu
 * boş tek ölçüm nokta. Hata görülen saatler altta küçük kırmızı çentik (gerçek durum rengi). Hiç ölçüm yoksa kesikli taban
 * çizgisi. Sabit piksel boyutu (viewBox ölçeklenmez → nokta elips olmaz, çizgi kalınlığı sabit).
 */
export function GapSpark({ slots, width = 96, height = 28, label, className }) {
  const list = Array.isArray(slots) ? slots : []
  const vals = list.map((s) => (s && s.ms != null && Number.isFinite(Number(s.ms)) ? Number(s.ms) : null))
  const present = vals.filter((v) => v != null)
  const pad = 3
  const innerW = Math.max(1, width - 2 * pad), innerH = Math.max(1, height - 2 * pad - 3)
  const lo = present.length ? Math.min(...present) : 0
  const hi = present.length ? Math.max(...present) : 1
  const span = hi - lo || 1
  const x = (i) => pad + (list.length > 1 ? (i * innerW) / (list.length - 1) : innerW / 2)
  // Tüm değerler eşitse çizgi ortada dursun (tabana yapışıp "0" gibi okunmasın)
  const y = (v) => (hi === lo ? pad + innerH / 2 : pad + innerH - ((v - lo) / span) * innerH)
  const runs = []
  let cur = []
  vals.forEach((v, i) => {
    if (v == null) { if (cur.length) { runs.push(cur); cur = [] } return }
    cur.push([x(i), y(v)])
  })
  if (cur.length) runs.push(cur)
  const f = (n) => n.toFixed(1)
  return (
    // Boyut SATIR İÇİ stilde: shadcn Button içindeki `svg` kuralı (`size-4`) sınıfla verilen / öznitelikteki ölçüyü ezer —
    // tıklanabilir satırda (satırın tamamı düğme) grafik 16 px'e iniyordu (e2e ekran görüntüsünde bulundu)
    <svg width={width} height={height} viewBox={`0 0 ${width} ${height}`} role="img" aria-label={label} data-slot="mydash-spark"
      data-points={present.length} className={cn('shrink-0 overflow-visible', className)} style={{ width, height }}>
      {present.length === 0 && (
        <line x1={pad} x2={width - pad} y1={height / 2} y2={height / 2} strokeDasharray="3 3" strokeWidth="1"
          className="stroke-muted-foreground/50" data-slot="mydash-spark-empty" />
      )}
      {runs.map((run, i) => (run.length === 1
        ? <circle key={i} cx={f(run[0][0])} cy={f(run[0][1])} r="2" fill="var(--chart-1)" data-slot="mydash-spark-dot" />
        : <polyline key={i} points={run.map(([px, py]) => `${f(px)},${f(py)}`).join(' ')} fill="none" stroke="var(--chart-1)"
            strokeWidth="1.5" strokeLinejoin="round" strokeLinecap="round" data-slot="mydash-spark-line" />))}
      {list.map((s, i) => (s && Number(s.fail) > 0
        ? <rect key={`f${i}`} x={f(x(i) - 1)} y={height - 3} width="2" height="3" className="fill-destructive" data-slot="mydash-spark-fail" />
        : null))}
    </svg>
  )
}

const GAUGE_R = 50
const GAUGE_LEN = Math.PI * GAUGE_R
const GAUGE_STROKE = {
  success: 'stroke-success', warning: 'stroke-amber-500', danger: 'stroke-destructive', info: 'stroke-primary', muted: 'stroke-muted-foreground/40',
}

/**
 * Yarım daire gösterge: `fraction` (0 … 1) kadar dolu yay, `targetFraction` konumunda hedef çentiği. Değer yoksa (null) yay
 * boş ve çentik de çizilmez — bilinmeyen değer dolu / yeşil yay olarak görünmez. Ortadaki sayı çağıranın (HTML metin).
 */
export function Gauge({ fraction, targetFraction, tone = 'muted', label, className, children }) {
  const fr = fraction == null ? null : Math.min(1, Math.max(0, Number(fraction) || 0))
  const tf = targetFraction == null ? null : Math.min(1, Math.max(0, Number(targetFraction) || 0))
  const angle = tf == null ? 0 : Math.PI * (1 - tf)
  const at = (r) => [60 + r * Math.cos(angle), 60 - r * Math.sin(angle)]
  const [x1, y1] = at(GAUGE_R - 9)
  const [x2, y2] = at(GAUGE_R + 9)
  return (
    <div className={cn('relative mx-auto w-full max-w-[15rem]', className)} data-slot="mydash-gauge" data-tone={tone}
      data-fraction={fr == null ? '' : fr.toFixed(3)}>
      <svg viewBox="0 0 120 70" className="block h-auto w-full" role="img" aria-label={label}>
        <path d="M 10 60 A 50 50 0 0 1 110 60" fill="none" strokeWidth="10" strokeLinecap="round" className="stroke-muted" />
        {fr != null && fr > 0 && (
          <path d="M 10 60 A 50 50 0 0 1 110 60" fill="none" strokeWidth="10" strokeLinecap="round" data-slot="mydash-gauge-value"
            className={GAUGE_STROKE[tone] || GAUGE_STROKE.muted} strokeDasharray={`${(GAUGE_LEN * fr).toFixed(2)} ${GAUGE_LEN.toFixed(2)}`} />
        )}
        {tf != null && fr != null && (
          <line x1={x1.toFixed(2)} y1={y1.toFixed(2)} x2={x2.toFixed(2)} y2={y2.toFixed(2)} strokeWidth="2.5" strokeLinecap="round"
            className="stroke-foreground" data-slot="mydash-gauge-target" />
        )}
      </svg>
      <div className="absolute inset-x-0 bottom-0 flex flex-col items-center text-center">{children}</div>
    </div>
  )
}
