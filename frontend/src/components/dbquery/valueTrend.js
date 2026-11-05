/**
 * Veritabanı Sorgu İzleme — kartın DEĞER EĞİLİMİ modeli (saf; 2026-11-05, dbq2 C1).
 *
 * <p>Sunucu liste satırına `value_trend` ekler: `{ points: [number|null] (son 24 sa zamanlanmış çalıştırmaların ölçülen
 * değeri, ≤ 24 kova, eskiden yeniye), delta_pct, direction: "up" | "down" | "flat" | null }`. Alan yoksa (eski sunucu,
 * değersiz izleme) kart bugünkü gibi çizilir. Renk YÖNÜN iyi / kötü olduğunu bilmez (artış bir kuralda iyi, ötekinde
 * kötü) — eğilim NÖTR çizilir; ihlal durumu zaten rozettedir.
 */

/** Sunucunun en çok gönderdiği kova sayısı — fazlası (bozuk / gelecekteki yanıt) kesilir, en yeniler kalır. */
export const TREND_MAX_POINTS = 24
export const TREND_DIRECTIONS = Object.freeze(['up', 'down', 'flat'])

const num = (v) => (typeof v === 'number' && Number.isFinite(v) ? v : null)

/**
 * Satırın eğilimi → `{ points, direction, delta, count }` ya da null (alan yok / dizi değil / hiç sayısal nokta yok).
 * Bilinmeyen yön ve sayı olmayan yüzde düşer (null) — uydurulmaz.
 */
export function valueTrendOf(m) {
  const vt = m?.value_trend
  if (!vt || typeof vt !== 'object' || !Array.isArray(vt.points)) return null
  const points = vt.points.slice(-TREND_MAX_POINTS).map(num)
  const count = points.filter((v) => v != null).length
  if (count === 0) return null
  return {
    points,
    direction: TREND_DIRECTIONS.includes(vt.direction) ? vt.direction : null,
    delta: num(vt.delta_pct),
    count,
  }
}

/**
 * Yüzde değişim metni: TR "+%12,5" / "−%3", EN "+12.5%" / "−3%". 100'ün üstünde ondalıksız; çok küçük değişim "%0".
 * Sayı değilse null.
 */
export function deltaText(delta, lang = 'tr') {
  if (typeof delta !== 'number' || !Number.isFinite(delta)) return null
  const abs = Math.abs(delta)
  const digits = abs >= 100 ? 0 : 1
  const shown = new Intl.NumberFormat(lang === 'en' ? 'en-GB' : 'tr-TR', { maximumFractionDigits: digits }).format(abs)
  const zero = Number(abs.toFixed(digits)) === 0
  const sign = zero ? '' : delta > 0 ? '+' : '−'
  return lang === 'en' ? `${sign}${shown}%` : `${sign}%${shown}`
}

/**
 * Mini grafiğin SVG yolları: veri olmayan kovada çizgi KOPAR (ayrı parçalar); iki yanı boş tek nokta çizgide görünmez —
 * nokta olarak döner. Değer ekseni noktaların en küçük–en büyük aralığına yayılır (düz seri ortada yatay çizgi).
 *
 * @returns {{ segments: string[], dots: {x:number,y:number}[] }}
 */
export function sparkGeometry(points, width = 100, height = 24, pad = 2) {
  const list = Array.isArray(points) ? points : []
  const real = list.filter((v) => typeof v === 'number' && Number.isFinite(v))
  if (!real.length) return { segments: [], dots: [] }
  const lo = Math.min(...real)
  const hi = Math.max(...real)
  const span = hi - lo
  const n = list.length
  const xOf = (i) => (n <= 1 ? width / 2 : pad + (i * (width - 2 * pad)) / (n - 1))
  const yOf = (v) => (span === 0 ? height / 2 : pad + ((hi - v) * (height - 2 * pad)) / span)
  const r = (x) => Math.round(x * 100) / 100
  const segments = []
  const dots = []
  let cur = []
  const flush = () => {
    if (cur.length === 1) dots.push({ x: r(cur[0][0]), y: r(cur[0][1]) })
    else if (cur.length > 1) segments.push(cur.map(([x, y], i) => `${i ? 'L' : 'M'}${r(x)} ${r(y)}`).join(' '))
    cur = []
  }
  list.forEach((v, i) => {
    if (typeof v === 'number' && Number.isFinite(v)) cur.push([xOf(i), yOf(v)])
    else flush()
  })
  flush()
  return { segments, dots }
}
