// Görsel motoru — yeniden örnekleme (saf, tipli dizilerle). "Kusursuz küçültme"nin üç şartı burada:
//  1) DOĞRUSAL ışık: sRGB → doğrusal → süzgeç → sRGB (gama kodlu ortalama görseli karartır);
//  2) ÖNÇARPIMLI saydamlık: renk, saydamlığıyla ağırlıklı ortalanır (saydam kenarda koyu / açık hale olmaz);
//  3) küçültmede çekirdek ölçek kadar GENİŞLER (örtüşme / titreme olmaz).
// Ayrılabilir (önce yatay, sonra dikey) ve SATIR SATIR akar: bellek O(çekirdek yüksekliği × hedef genişlik) — 50 MP
// kaynak için tam boy ikinci bir kayan nokta tamponu AYRILMAZ. Kaynak satırları sırayla `pushRow` ile verilir; hazır
// olan hedef satırlar `onRow(y, satır)` ile döner (satır tamponu yeniden kullanılır — çağıran kopyalar).
import { SRGB_TO_LINEAR, linearToSrgb8 } from './color.js'

/** Catmull-Rom (Keys, a = −0,5): tek, küçük eksi lob → Lanczos-3'ten belirgin az hale; küçültmede net. */
function catrom(t) {
  const x = t < 0 ? -t : t
  if (x < 1) return (1.5 * x - 2.5) * x * x + 1
  if (x < 2) return ((-0.5 * x + 2.5) * x - 4) * x + 2
  return 0
}

function lanczos3(t) {
  const x = t < 0 ? -t : t
  if (x < 1e-8) return 1
  if (x >= 3) return 0
  const px = Math.PI * x
  return (3 * Math.sin(px) * Math.sin(px / 3)) / (px * px)
}

export const FILTERS = Object.freeze({
  catrom: Object.freeze({ support: 2, fn: catrom }),
  lanczos3: Object.freeze({ support: 3, fn: lanczos3 }),
})
export const DEFAULT_FILTER = 'catrom'

/**
 * Bir eksenin katkı tablosu: hedef piksel i → kaynak aralığı [start, start + count) ve toplamı 1 olan ağırlıklar.
 * Küçültmede çekirdek `srcN / dstN` kadar genişler; kenarda taşan kollar atılır ve ağırlıklar yeniden 1'e ölçeklenir.
 */
export function contributions(srcN, dstN, filterName = DEFAULT_FILTER) {
  const f = FILTERS[filterName] || FILTERS[DEFAULT_FILTER]
  const ratio = srcN / dstN
  const fscale = Math.max(1, ratio)
  const support = f.support * fscale
  const maxTaps = Math.min(srcN, Math.ceil(support * 2) + 3)
  const start = new Int32Array(dstN)
  const count = new Int32Array(dstN)
  const weights = new Float32Array(dstN * maxTaps)
  const tmp = new Float64Array(maxTaps)
  for (let i = 0; i < dstN; i++) {
    const center = (i + 0.5) * ratio
    let left = Math.floor(center - support)
    let right = Math.ceil(center + support)
    if (left < 0) left = 0
    if (right > srcN - 1) right = srcN - 1
    let n = right - left + 1
    if (n > maxTaps) n = maxTaps
    let sum = 0
    for (let k = 0; k < n; k++) {
      const w = f.fn((left + k + 0.5 - center) / fscale)
      tmp[k] = w
      sum += w
    }
    const base = i * maxTaps
    if (!(Math.abs(sum) > 1e-9)) {
      // Çekirdek tümüyle sıfıra düştü (olmamalı): en yakın kaynak pikseli
      const nearest = Math.max(0, Math.min(srcN - 1, Math.floor(center)))
      left = nearest
      n = 1
      weights[base] = 1
    } else {
      for (let k = 0; k < n; k++) weights[base + k] = tmp[k] / sum
    }
    start[i] = left
    count[i] = n
  }
  return { start, count, weights, maxTaps }
}

/**
 * Akışlı yeniden örnekleyici. @param {{ srcW, srcH, dstW, dstH, filter?, onRow: (y: number, row: Uint8ClampedArray) => void }} o
 * @returns {{ pushRow(src: ArrayLike<number>, offset?: number): void, rowsPushed(): number, rowsEmitted(): number }}
 */
export function createResampler({ srcW, srcH, dstW, dstH, filter = DEFAULT_FILTER, onRow }) {
  if (!(srcW >= 1 && srcH >= 1 && dstW >= 1 && dstH >= 1)) throw new RangeError('resample: ölçü')
  const hc = contributions(srcW, dstW, filter)
  const vc = contributions(srcH, dstH, filter)
  const ringN = vc.maxTaps
  const rowLen = dstW * 4
  const ring = new Array(ringN)
  for (let i = 0; i < ringN; i++) ring[i] = new Float32Array(rowLen)
  const lin = new Float32Array(srcW * 4)
  const acc = new Float32Array(rowLen)
  const out = new Uint8ClampedArray(rowLen)
  const hw = hc.weights
  const hstart = hc.start
  const hcount = hc.count
  const hTaps = hc.maxTaps
  const LUT = SRGB_TO_LINEAR
  let pushed = 0
  let emitted = 0

  function emit(y) {
    acc.fill(0)
    const n = vc.count[y]
    const s = vc.start[y]
    const wo = y * vc.maxTaps
    for (let k = 0; k < n; k++) {
      const w = vc.weights[wo + k]
      if (w === 0) continue
      const row = ring[(s + k) % ringN]
      for (let i = 0; i < rowLen; i++) acc[i] += row[i] * w
    }
    for (let i = 0; i < rowLen; i += 4) {
      const a = acc[i + 3]
      if (a < 0.00196) {                       // < 0,5 / 255: tam saydam
        out[i] = 0; out[i + 1] = 0; out[i + 2] = 0; out[i + 3] = 0
      } else {
        const inv = 1 / a
        out[i] = linearToSrgb8(acc[i] * inv)
        out[i + 1] = linearToSrgb8(acc[i + 1] * inv)
        out[i + 2] = linearToSrgb8(acc[i + 2] * inv)
        out[i + 3] = a >= 1 ? 255 : (a * 255 + 0.5) | 0
      }
    }
    onRow(y, out)
  }

  function pushRow(src, offset = 0) {
    if (pushed >= srcH) throw new RangeError('resample: fazla satır')
    // 1) sRGB → doğrusal, önçarpımlı
    for (let x = 0, o = offset, i = 0; x < srcW; x++, o += 4, i += 4) {
      const a = src[o + 3]
      if (a === 255) {
        lin[i] = LUT[src[o]]; lin[i + 1] = LUT[src[o + 1]]; lin[i + 2] = LUT[src[o + 2]]; lin[i + 3] = 1
      } else if (a === 0) {
        lin[i] = 0; lin[i + 1] = 0; lin[i + 2] = 0; lin[i + 3] = 0
      } else {
        const k = a / 255
        lin[i] = LUT[src[o]] * k; lin[i + 1] = LUT[src[o + 1]] * k; lin[i + 2] = LUT[src[o + 2]] * k; lin[i + 3] = k
      }
    }
    // 2) yatay süzgeç → halka tampon
    const dst = ring[pushed % ringN]
    for (let x = 0, di = 0; x < dstW; x++, di += 4) {
      const n = hcount[x]
      let si = hstart[x] * 4
      const wo = x * hTaps
      let c0 = 0
      let c1 = 0
      let c2 = 0
      let c3 = 0
      for (let k = 0; k < n; k++, si += 4) {
        const w = hw[wo + k]
        c0 += lin[si] * w; c1 += lin[si + 1] * w; c2 += lin[si + 2] * w; c3 += lin[si + 3] * w
      }
      dst[di] = c0; dst[di + 1] = c1; dst[di + 2] = c2; dst[di + 3] = c3
    }
    pushed += 1
    // 3) dikey süzgeç: gereken son kaynak satırı geldiyse hedef satır hazır
    while (emitted < dstH && vc.start[emitted] + vc.count[emitted] <= pushed) emit(emitted++)
  }

  return { pushRow, rowsPushed: () => pushed, rowsEmitted: () => emitted }
}

/** Tüm görseli tek çağrıda yeniden örnekler (küçük görseller ve testler için; büyük işte akışlı sürüm kullanılır). */
export function resampleRGBA(src, srcW, srcH, dstW, dstH, filter = DEFAULT_FILTER) {
  const out = new Uint8ClampedArray(dstW * dstH * 4)
  const rs = createResampler({ srcW, srcH, dstW, dstH, filter, onRow: (y, row) => out.set(row, y * dstW * 4) })
  for (let r = 0; r < srcH; r++) rs.pushRow(src, r * srcW * 4)
  return out
}
