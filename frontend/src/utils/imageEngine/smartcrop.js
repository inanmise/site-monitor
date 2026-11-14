// Görsel motoru — akıllı kırpma ve "düz zeminde serbest duran nesne" tespiti (saf). Küçültülmüş bir kopya
// (uzun kenar ≤ ANALYSIS_SIDE) üstünde çalışır: maliyet görselin boyundan bağımsız ve sınırlıdır.
import { luma } from './color.js'

/** Çözümleme kopyasının uzun kenarı (piksel). */
export const ANALYSIS_SIDE = 192
const CENTER_BIAS = 0.2
const BORDER_PLAIN_MIN = 0.97
const OBJECT_MIN_AREA = 0.004
const OBJECT_MAX_BBOX = 0.9
const BG_TOLERANCE = 18

/**
 * Ayrıntı (enerji) haritası: önçarpımlı parlaklığın ve saydamlığın komşu farkı + doygunluk payı. Düz zemin ≈ 0,
 * kenar / doku / yüz gibi ayrıntılı bölgeler yüksek.
 */
export function energyMap(rgba, w, h) {
  const n = w * h
  const L = new Float32Array(n)
  const A = new Float32Array(n)
  const S = new Float32Array(n)
  for (let p = 0, i = 0; p < n; p++, i += 4) {
    const a = rgba[i + 3] / 255
    const r = rgba[i]
    const g = rgba[i + 1]
    const b = rgba[i + 2]
    L[p] = luma(r, g, b) * a
    A[p] = a * 255
    S[p] = (Math.max(r, g, b) - Math.min(r, g, b)) * a
  }
  const e = new Float32Array(n)
  for (let y = 0; y < h; y++) {
    const yu = y > 0 ? y - 1 : y
    const yd = y < h - 1 ? y + 1 : y
    for (let x = 0; x < w; x++) {
      const xl = x > 0 ? x - 1 : x
      const xr = x < w - 1 ? x + 1 : x
      const p = y * w + x
      e[p] = Math.abs(L[y * w + xr] - L[y * w + xl]) + Math.abs(L[yd * w + x] - L[yu * w + x])
        + 0.5 * (Math.abs(A[y * w + xr] - A[y * w + xl]) + Math.abs(A[yd * w + x] - A[yu * w + x]))
        + 0.1 * S[p]
    }
  }
  return e
}

/** Toplam alan tablosu: sat[(y)(w+1) + x] = [0,x) × [0,y) toplamı. */
export function integralImage(map, w, h) {
  const sat = new Float64Array((w + 1) * (h + 1))
  for (let y = 0; y < h; y++) {
    let row = 0
    for (let x = 0; x < w; x++) {
      row += map[y * w + x]
      sat[(y + 1) * (w + 1) + x + 1] = sat[y * (w + 1) + x + 1] + row
    }
  }
  return sat
}

const boxSum = (sat, w, x0, y0, x1, y1) => sat[y1 * (w + 1) + x1] - sat[y0 * (w + 1) + x1] - sat[y1 * (w + 1) + x0] + sat[y0 * (w + 1) + x0]

/**
 * `winW × winH` pencerenin `axis` boyunca en iyi konumu: içindeki enerji toplamı × hafif merkez payı. Düz görselde
 * (enerji yok) pencere ORTADA kalır. Dönüş: pencere başlangıcı (çözümleme kopyası pikseli).
 */
export function bestWindowOffset(sat, w, h, winW, winH, axis) {
  const span = axis === 'x' ? w - winW : h - winH
  if (span <= 0) return 0
  const mid = span / 2
  let best = Math.round(mid)
  let bestScore = -1
  let bestDist = Infinity
  for (let o = 0; o <= span; o++) {
    const sum = axis === 'x' ? boxSum(sat, w, o, 0, o + winW, h) : boxSum(sat, w, 0, o, w, o + winH)
    const d = (o - mid) / mid
    const score = sum * (1 - CENTER_BIAS * d * d)
    const dist = Math.abs(o - mid)
    if (score > bestScore + 1e-9 || (Math.abs(score - bestScore) <= 1e-9 && dist < bestDist)) {
      best = o
      bestScore = score
      bestDist = dist
    }
  }
  return best
}

/**
 * Kırpma penceresinin kaynak pikselindeki başlangıcı (akıllı kırpma). @param thumb { data, width, height } çözümleme
 * kopyası; @param plan planResize çıktısı (cropAxis dolu).
 */
export function smartCropOffset(thumb, srcW, srcH, plan) {
  const { data, width: tw, height: th } = thumb
  const axis = plan.cropAxis
  const winW = Math.max(1, Math.min(tw, Math.round(plan.window.w * tw / srcW)))
  const winH = Math.max(1, Math.min(th, Math.round(plan.window.h * th / srcH)))
  const sat = integralImage(energyMap(data, tw, th), tw, th)
  const o = bestWindowOffset(sat, tw, th, winW, winH, axis)
  return axis === 'x' ? Math.round(o * srcW / tw) : Math.round(o * srcH / th)
}

/**
 * Düz zemin üstünde serbest duran nesne var mı? Kenar halkasının ≥ %97'si tek bir renge (ya da saydamlığa) yakınsa
 * zemin "düz" sayılır; zeminden farklı piksellerin kutusu nesnedir. Nesne çok küçükse ya da görselin neredeyse
 * tamamını kaplıyorsa `null`.
 * @returns {null | { background: number[], bbox: {x0,y0,x1,y1} }} kutu çözümleme kopyası pikselinde (x1 / y1 hariç uç)
 */
export function detectObject(rgba, w, h) {
  if (w < 8 || h < 8) return null
  // 1) kenar halkasının baskın rengi (kanal başına 4 bit kovalar)
  const buckets = new Map()
  const ring = []
  const add = (x, y) => {
    const i = (y * w + x) * 4
    ring.push(i)
    const a = rgba[i + 3]
    const key = a < 16 ? -1 : ((rgba[i] >> 4) << 12) | ((rgba[i + 1] >> 4) << 8) | ((rgba[i + 2] >> 4) << 4) | (a >> 4)
    buckets.set(key, (buckets.get(key) || 0) + 1)
  }
  for (let x = 0; x < w; x++) { add(x, 0); add(x, h - 1) }
  for (let y = 1; y < h - 1; y++) { add(0, y); add(w - 1, y) }
  let topKey = 0
  let topCount = -1
  for (const [key, count] of buckets) if (count > topCount || (count === topCount && key < topKey)) { topKey = key; topCount = count }
  const bg = [0, 0, 0, 0]
  if (topKey !== -1) {
    let cnt = 0
    for (const i of ring) {
      const a = rgba[i + 3]
      if (a < 16) continue
      const key = ((rgba[i] >> 4) << 12) | ((rgba[i + 1] >> 4) << 8) | ((rgba[i + 2] >> 4) << 4) | (a >> 4)
      if (key !== topKey) continue
      bg[0] += rgba[i]; bg[1] += rgba[i + 1]; bg[2] += rgba[i + 2]; bg[3] += a
      cnt += 1
    }
    for (let c = 0; c < 4; c++) bg[c] = Math.round(bg[c] / cnt)
    if (bg[3] >= 250) bg[3] = 255
  }
  const transparentBg = bg[3] < 16
  const isBg = (i) => {
    const a = rgba[i + 3]
    if (transparentBg) return a < 24
    if (Math.abs(a - bg[3]) > BG_TOLERANCE) return false
    return Math.abs(rgba[i] - bg[0]) <= BG_TOLERANCE && Math.abs(rgba[i + 1] - bg[1]) <= BG_TOLERANCE
      && Math.abs(rgba[i + 2] - bg[2]) <= BG_TOLERANCE
  }
  let plain = 0
  for (const i of ring) if (isBg(i)) plain += 1
  if (plain / ring.length < BORDER_PLAIN_MIN) return null
  // 2) zeminden farklı piksellerin kutusu
  let x0 = w
  let y0 = h
  let x1 = -1
  let y1 = -1
  let count = 0
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      if (isBg((y * w + x) * 4)) continue
      count += 1
      if (x < x0) x0 = x
      if (x > x1) x1 = x
      if (y < y0) y0 = y
      if (y > y1) y1 = y
    }
  }
  if (count < OBJECT_MIN_AREA * w * h || x1 < 0) return null
  const bboxArea = (x1 - x0 + 1) * (y1 - y0 + 1)
  if (bboxArea > OBJECT_MAX_BBOX * w * h) return null
  return { background: transparentBg ? [0, 0, 0, 0] : bg, bbox: { x0, y0, x1: x1 + 1, y1: y1 + 1 } }
}
