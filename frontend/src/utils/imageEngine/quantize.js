// Görsel motoru — kayıplı palet nicemleme (saf, tipli dizilerle). PNG çıktısı için görseli SAYDAMLIĞIYLA birlikte
// ≤ 256 renge indirir:
//  1) histogram (tam renkler; > 1 M farklı renkte kanal bitleri kademeli atılır),
//  2) ≤ `maxColors` farklı renk varsa palet BİREBİR (kayıpsız),
//  3) değilse algısal-ağırlıklı, saydamlıkla önçarpımlı uzayda varyans bölmeli "median cut" + k-ortalama iyileştirme,
//  4) pikseller en yakın palet rengine eşlenir; istenirse yılan (serpantin) Floyd–Steinberg titreşimi (hata kırpılır,
//     sönümlenir).
// Tam saydam (a = 0), tam opak (a = 255) ve yarı saydam renkler AYRI kümelenir: opak piksel hiçbir zaman yarı saydam
// bir palet rengine, tam saydam piksel de görünür bir renge düşmez (saydam kenarda pus / hale olmaz).
// Belirlenimci: aynı girdi → aynı palet ve aynı dizinler. Tüm döngüler girdi boyuyla ya da sabitlerle sınırlıdır.

import { drive, runSteps } from './steps.js'

const S_R = Math.sqrt(0.5)
const S_G = 1
const S_B = Math.sqrt(0.45)
const S_A = Math.sqrt(0.625)

const TABLE_BITS = 21
/** Tam izlenen farklı renk sayısı (aşılırsa kanal bitleri atılır). */
export const MAX_UNIQUE = 1 << 20
/** Palet tasarımına giren en çok histogram girdisi (aşılırsa girdiler kovalanır). */
export const MAX_DESIGN_ENTRIES = 65536
const KMEANS_PASSES = 3
const SPLIT_WEIGHT_POW = 0.75
const DITHER_DAMP = 0.92
const DITHER_ERR_MAX = 32
/** Bu hatanın altında titreşim uygulanmaz (palet zaten neredeyse birebir; gürültü eklemek dosyayı büyütür). */
export const DITHER_MIN_MSE = 2e-5

const LITTLE_ENDIAN = new Uint8Array(new Uint32Array([1]).buffer)[0] === 1

function hashKey(key, bits) {
  return Math.imul(key ^ (key >>> 15), 0x9e3779b1) >>> (32 - bits)
}

/** Pikselin histogram anahtarı: r | g<<8 | b<<16 | a<<24; tam saydam = 0; a = 255 ve a > 0 sınıfları korunur. */
function keyOf(r, g, b, a, bits) {
  if (a === 0) return 0
  if (bits === 0) return (r | (g << 8) | (b << 16) | (a << 24)) >>> 0
  const m = (0xff << bits) & 0xff
  let aq = a
  if (a !== 255) { aq = a & m; if (aq === 0) aq = 1 << bits }
  return ((r & m) | ((g & m) << 8) | ((b & m) << 16) | (aq << 24)) >>> 0
}

/** Histogram parçası: kaç piksel (ana iş parçacığında parçalar arasında sayfaya nefes aldırılır). */
const HIST_CHUNK = 1 << 18
/** Palet tasarımında k-ortalama atamasının parça boyu (girdi sayısı). */
const REFINE_CHUNK = 8192

function histStart(data, n, bits) {
  const size = 1 << TABLE_BITS
  const aligned = LITTLE_ENDIAN && data.byteOffset % 4 === 0 && data.buffer.byteLength >= data.byteOffset + n * 4
  return { bits, mask: size - 1, keys: new Uint32Array(size), counts: new Uint32Array(size), used: 0,
    px: aligned ? new Uint32Array(data.buffer, data.byteOffset, n) : null }
}

/** [from, to) piksellerini tabloya ekler (düz fonksiyon: sıcak döngü). Farklı renk sınırı aşılırsa false. */
function histChunk(st, data, from, to) {
  const { keys, counts, mask, px, bits } = st
  let used = st.used
  for (let p = from; p < to; p++) {
    let key
    if (px && bits === 0) {
      key = px[p]
      if (key >>> 24 === 0) key = 0
    } else {
      const i = p * 4
      key = keyOf(data[i], data[i + 1], data[i + 2], data[i + 3], bits)
    }
    let slot = hashKey(key, TABLE_BITS)
    for (;;) {
      if (counts[slot] === 0) {
        if (used >= MAX_UNIQUE) { st.used = used; return false }
        keys[slot] = key
        counts[slot] = 1
        used += 1
        break
      }
      if (keys[slot] === key) { counts[slot] += 1; break }
      slot = (slot + 1) & mask
    }
  }
  st.used = used
  return true
}

function histFinish(st) {
  const { keys, counts, used } = st
  const size = keys.length
  const outKeys = new Uint32Array(used)
  const outCounts = new Uint32Array(used)
  for (let s = 0, o = 0; s < size; s++) if (counts[s] !== 0) { outKeys[o] = keys[s]; outCounts[o] = counts[s]; o += 1 }
  return { keys: outKeys, counts: outCounts, unique: used, bits: st.bits }
}

function* histogramSteps(data, pixelCount) {
  for (let bits = 0; bits <= 4; bits++) {
    const st = histStart(data, pixelCount, bits)
    let ok = true
    for (let p = 0; ok && p < pixelCount; p += HIST_CHUNK) {
      ok = histChunk(st, data, p, Math.min(pixelCount, p + HIST_CHUNK))
      yield
    }
    if (ok) return histFinish(st)
  }
  // 4 bit atılınca en çok 16^4 = 65 536 anahtar kalır: buraya gelinmez
  throw new RangeError('histogram')
}

/** Renk histogramı. @returns {{ keys: Uint32Array, counts: Uint32Array, unique: number, bits: number }} */
export function buildHistogram(data, pixelCount) {
  return runSteps(histogramSteps(data, pixelCount))
}

/**
 * Histogramı palet tasarımı girdilerine çevirir (gerekirse ≤ MAX_DESIGN_ENTRIES'e kovalar; kova rengi ağırlıklı
 * ortalamadır). Girdiler sınıfa göre sıralıdır: önce opak (a = 255), sonra yarı saydam; tam saydam ayrı bildirilir.
 */
function* designSteps(hist) {
  let { keys, counts } = hist
  let n = keys.length
  let r = new Float64Array(n)
  let g = new Float64Array(n)
  let b = new Float64Array(n)
  let a = new Float64Array(n)
  let w = new Float64Array(n)
  const half = hist.bits > 0 ? (1 << hist.bits) / 2 - 0.5 : 0          // atılan bitlerin kova ortası
  for (let i = 0; i < n; i++) {
    const k = keys[i]
    const av = k >>> 24
    r[i] = Math.min(255, (k & 255) + half)
    g[i] = Math.min(255, ((k >>> 8) & 255) + half)
    b[i] = Math.min(255, ((k >>> 16) & 255) + half)
    a[i] = av === 255 || av === 0 ? av : Math.min(254, av + half)
    w[i] = counts[i]
  }
  for (let bits = Math.max(1, hist.bits + 1); n > MAX_DESIGN_ENTRIES + 1 && bits <= 6; bits++) {
    const tb = 18
    const size = 1 << tb
    const mask = size - 1
    const tk = new Uint32Array(size)
    const tu = new Uint8Array(size)
    const sr = new Float64Array(size)
    const sg = new Float64Array(size)
    const sb = new Float64Array(size)
    const sa = new Float64Array(size)
    const sw = new Float64Array(size)
    let used = 0
    let overflow = false
    for (let i = 0; i < n; i++) {
      const key = keyOf(r[i] | 0, g[i] | 0, b[i] | 0, a[i] | 0, bits)
      let slot = hashKey(key, tb)
      for (;;) {
        if (tu[slot] === 0) { tu[slot] = 1; tk[slot] = key; used += 1; break }
        if (tk[slot] === key) break
        slot = (slot + 1) & mask
      }
      // Tablo dolmadan bırak (dolu tabloda boş yuva araması bitmez): bir bit daha atılarak yeniden denenir
      if (used > size * 0.7) { overflow = true; break }
      sr[slot] += r[i] * w[i]; sg[slot] += g[i] * w[i]; sb[slot] += b[i] * w[i]; sa[slot] += a[i] * w[i]; sw[slot] += w[i]
    }
    yield
    if (overflow) continue
    const nr = new Float64Array(used)
    const ng = new Float64Array(used)
    const nb = new Float64Array(used)
    const na = new Float64Array(used)
    const nw = new Float64Array(used)
    for (let s = 0, o = 0; s < size; s++) {
      if (tu[s] === 0) continue
      nr[o] = sr[s] / sw[s]; ng[o] = sg[s] / sw[s]; nb[o] = sb[s] / sw[s]
      const am = sa[s] / sw[s]
      na[o] = (tk[s] >>> 24) === 255 ? 255 : tk[s] === 0 ? 0 : Math.max(1, Math.min(254, am))
      nw[o] = sw[s]
      o += 1
    }
    r = nr; g = ng; b = nb; a = na; w = nw
    n = used
  }
  // Sınıflara ayır: opak önce, yarı saydam sonra; tam saydam palete ayrı girer
  const opaque = []
  const semi = []
  let transparentWeight = 0
  for (let i = 0; i < n; i++) {
    if (a[i] === 0) transparentWeight += w[i]
    else if (a[i] === 255) opaque.push(i)
    else semi.push(i)
  }
  const m = opaque.length + semi.length
  const coords = new Float32Array(m * 4)
  const weights = new Float64Array(m)
  const put = (i, o) => {
    const af = a[i] / 255
    coords[o * 4] = (r[i] / 255) * af * S_R
    coords[o * 4 + 1] = (g[i] / 255) * af * S_G
    coords[o * 4 + 2] = (b[i] / 255) * af * S_B
    coords[o * 4 + 3] = af * S_A
    weights[o] = w[i]
  }
  let o = 0
  for (const i of opaque) put(i, o++)
  for (const i of semi) put(i, o++)
  return { coords, weights, opaqueCount: opaque.length, semiCount: semi.length, transparentWeight }
}

function boxStats(coords, weights, order, lo, hi) {
  let W = 0
  const s = [0, 0, 0, 0]
  const q = [0, 0, 0, 0]
  for (let j = lo; j < hi; j++) {
    const e = order[j]
    const w = weights[e]
    W += w
    for (let c = 0; c < 4; c++) {
      const v = coords[e * 4 + c]
      s[c] += v * w
      q[c] += v * v * w
    }
  }
  let axis = 0
  let best = -1
  let sse = 0
  for (let c = 0; c < 4; c++) {
    const v = W > 0 ? Math.max(0, q[c] - (s[c] * s[c]) / W) : 0
    sse += v
    if (v > best) { best = v; axis = c }
  }
  return { lo, hi, W, sse, axis, mean: W > 0 ? s.map((x) => x / W) : [0, 0, 0, 0] }
}

/** Kutuyu en büyük varyanslı ekseninde, toplam kare hatayı en çok düşüren noktadan böler. */
function splitBox(coords, weights, order, box) {
  const { lo, hi, axis } = box
  const m = hi - lo
  const keys = new Float64Array(m)
  for (let j = 0; j < m; j++) keys[j] = Math.round(coords[order[lo + j] * 4 + axis] * 1048576) * 131072 + j
  keys.sort()
  const seg = order.slice(lo, hi)
  for (let j = 0; j < m; j++) order[lo + j] = seg[keys[j] % 131072]
  // önek toplamları
  let W = 0
  const S = [0, 0, 0, 0]
  for (let j = lo; j < hi; j++) {
    const e = order[j]
    const w = weights[e]
    W += w
    for (let c = 0; c < 4; c++) S[c] += coords[e * 4 + c] * w
  }
  let wl = 0
  const sl = [0, 0, 0, 0]
  let bestGain = -1
  let bestAt = -1
  for (let j = lo; j < hi - 1; j++) {
    const e = order[j]
    const w = weights[e]
    wl += w
    for (let c = 0; c < 4; c++) sl[c] += coords[e * 4 + c] * w
    const wr = W - wl
    if (!(wl > 0 && wr > 0)) continue
    let gain = 0
    for (let c = 0; c < 4; c++) { const sr = S[c] - sl[c]; gain += (sl[c] * sl[c]) / wl + (sr * sr) / wr }
    if (gain > bestGain) { bestGain = gain; bestAt = j + 1 }
  }
  if (bestAt < 0) return null
  return [boxStats(coords, weights, order, lo, bestAt), boxStats(coords, weights, order, bestAt, hi)]
}

/** Merkezleri bir eksende sıralar: en yakın komşu araması o eksende dışa doğru açılır ve erken biter. */
function buildSearch(cent, from, to) {
  const k = to - from
  let axis = 0
  let best = -1
  for (let c = 0; c < 4; c++) {
    let s = 0
    let q = 0
    for (let i = from; i < to; i++) { const v = cent[i * 4 + c]; s += v; q += v * v }
    const v = k > 0 ? q - (s * s) / k : 0
    if (v > best) { best = v; axis = c }
  }
  const idx = []
  for (let i = from; i < to; i++) idx.push(i)
  idx.sort((x, y) => (cent[x * 4 + axis] - cent[y * 4 + axis]) || (x - y))
  const order = Int32Array.from(idx)
  const vals = new Float32Array(k)
  for (let i = 0; i < k; i++) vals[i] = cent[order[i] * 4 + axis]
  return { axis, order, vals, k }
}

/** En yakın merkez (tam sonuç — kaba kuvvetle aynı uzaklık). Boş kümede −1. */
export function nearestIn(search, cent, q0, q1, q2, q3) {
  const { order, vals, k } = search
  if (k === 0) return -1
  const qa = search.axis === 0 ? q0 : search.axis === 1 ? q1 : search.axis === 2 ? q2 : q3
  let lo = 0
  let hi = k
  while (lo < hi) { const mid = (lo + hi) >> 1; if (vals[mid] < qa) lo = mid + 1; else hi = mid }
  let left = lo - 1
  let right = lo
  let best = -1
  let bestD = Infinity
  while (left >= 0 || right < k) {
    if (right < k) {
      const dv = vals[right] - qa
      if (dv * dv >= bestD) right = k
      else {
        const c = order[right] * 4
        const d0 = cent[c] - q0
        const d1 = cent[c + 1] - q1
        const d2 = cent[c + 2] - q2
        const d3 = cent[c + 3] - q3
        const d = d0 * d0 + d1 * d1 + d2 * d2 + d3 * d3
        if (d < bestD) { bestD = d; best = order[right] }
        right += 1
      }
    }
    if (left >= 0) {
      const dv = qa - vals[left]
      if (dv * dv >= bestD) left = -1
      else {
        const c = order[left] * 4
        const d0 = cent[c] - q0
        const d1 = cent[c + 1] - q1
        const d2 = cent[c + 2] - q2
        const d3 = cent[c + 3] - q3
        const d = d0 * d0 + d1 * d1 + d2 * d2 + d3 * d3
        if (d < bestD) { bestD = d; best = order[left] }
        left -= 1
      }
    }
  }
  return best
}

/** Kaba kuvvet en yakın merkez (sınama kâhini; üretimde kullanılmaz). */
export function nearestBrute(cent, from, to, q0, q1, q2, q3) {
  let best = -1
  let bestD = Infinity
  for (let i = from; i < to; i++) {
    const c = i * 4
    const d0 = cent[c] - q0
    const d1 = cent[c + 1] - q1
    const d2 = cent[c + 2] - q2
    const d3 = cent[c + 3] - q3
    const d = d0 * d0 + d1 * d1 + d2 * d2 + d3 * d3
    if (d < bestD) { bestD = d; best = i }
  }
  return best
}

/** k-ortalama ataması: [from, to) girdileri en yakın merkeze; toplamlar `st`te (sıra değişmez → sonuç aynı). */
function assignRange(st, coords, weights, cent, from, to) {
  const { search, sums, ws, cFrom } = st
  let sse = st.sse
  for (let e = from; e < to; e++) {
    const o = e * 4
    const q0 = coords[o]
    const q1 = coords[o + 1]
    const q2 = coords[o + 2]
    const q3 = coords[o + 3]
    const ci = nearestIn(search, cent, q0, q1, q2, q3)
    const c = ci * 4
    const d0 = cent[c] - q0
    const d1 = cent[c + 1] - q1
    const d2 = cent[c + 2] - q2
    const d3 = cent[c + 3] - q3
    const w = weights[e]
    sse += (d0 * d0 + d1 * d1 + d2 * d2 + d3 * d3) * w
    const j = (ci - cFrom) * 4
    sums[j] += q0 * w; sums[j + 1] += q1 * w; sums[j + 2] += q2 * w; sums[j + 3] += q3 * w
    ws[ci - cFrom] += w
  }
  st.sse = sse
}

/** Bir sınıfın (girdi aralığı [eFrom, eTo), merkez aralığı [cFrom, cTo)) k-ortalama iyileştirmesi; kare hata döner. */
function* refineSteps(coords, weights, eFrom, eTo, cent, cFrom, cTo, passes) {
  const k = cTo - cFrom
  if (k === 0 || eTo === eFrom) return 0
  const st = { search: null, sums: new Float64Array(k * 4), ws: new Float64Array(k), cFrom, sse: 0 }
  for (let pass = 0; pass <= passes; pass++) {
    st.search = buildSearch(cent, cFrom, cTo)
    st.sums.fill(0)
    st.ws.fill(0)
    st.sse = 0
    for (let e = eFrom; e < eTo; e += REFINE_CHUNK) {
      assignRange(st, coords, weights, cent, e, Math.min(eTo, e + REFINE_CHUNK))
      yield
    }
    if (pass === passes) break                              // son tur yalnız hatayı ölçer
    const { sums, ws } = st
    for (let i = 0; i < k; i++) {
      if (!(ws[i] > 0)) continue                             // boş küme: merkez yerinde kalır
      const c = (cFrom + i) * 4
      cent[c] = sums[i * 4] / ws[i]; cent[c + 1] = sums[i * 4 + 1] / ws[i]
      cent[c + 2] = sums[i * 4 + 2] / ws[i]; cent[c + 3] = sums[i * 4 + 3] / ws[i]
    }
  }
  return st.sse
}

const clamp255 = (v) => (v < 0 ? 0 : v > 255 ? 255 : Math.round(v))

/** RGBA (0..255) → nicemleme uzayı koordinatı (dizinin `o` konumuna yazar). */
function toCoords(out, o, r, g, b, a) {
  const af = a / 255
  out[o] = (r / 255) * af * S_R
  out[o + 1] = (g / 255) * af * S_G
  out[o + 2] = (b / 255) * af * S_B
  out[o + 3] = af * S_A
}

/** Palet sırası: saydam olanlar önce (tRNS kısa kalsın), sonra parlaklık; eşitlikte renk anahtarı. */
function sortPalette(colors) {
  const lum = (c) => 0.299 * c[0] + 0.587 * c[1] + 0.114 * c[2]
  const key = (c) => (c[0] | (c[1] << 8) | (c[2] << 16) | (c[3] << 24)) >>> 0
  return colors.slice().sort((x, y) => (x[3] - y[3]) || (lum(x) - lum(y)) || (key(x) - key(y)))
}

/**
 * Palet tasarımı. @param {Uint8Array|Uint8ClampedArray} data RGBA @param {number} pixelCount
 * @param {number} maxColors 2..256
 * @returns {{ palette: Uint8Array, colors: number, exact: boolean, mse: number, hasTransparent: boolean,
 *   opaque: number, semi: number }} `palette` RGBA dörtlüleri (sıralı); `mse` nicemleme uzayında ortalama kare hata
 *   (piksel başına, 4 kanal toplamı; 0 = kayıpsız).
 */
export function buildPalette(data, pixelCount, maxColors = 256) {
  return runSteps(paletteSteps(data, pixelCount, maxColors))
}

/**
 * `buildPalette`in adım adım sürümü: `tick` verilirse (ana iş parçacığı yedeği) her parçadan sonra beklenir — büyük
 * görselde sayfa donmaz, iptal araya girer. `tick` yoksa eşzamanlı ile aynı. Sonuç birebir aynıdır.
 */
export function buildPaletteAsync(data, pixelCount, maxColors = 256, tick) {
  return drive(paletteSteps(data, pixelCount, maxColors), tick)
}

function* paletteSteps(data, pixelCount, maxColors) {
  const limit = Math.max(2, Math.min(256, maxColors | 0))
  const hist = yield* histogramSteps(data, pixelCount)
  if (hist.bits === 0 && hist.unique <= limit) {
    const colors = []
    for (let i = 0; i < hist.unique; i++) {
      const k = hist.keys[i]
      colors.push([k & 255, (k >>> 8) & 255, (k >>> 16) & 255, k >>> 24])
    }
    return finishPalette(sortPalette(colors), true, 0)
  }
  const d = yield* designSteps(hist)
  const total = d.opaqueCount + d.semiCount
  const hasT = d.transparentWeight > 0
  let budget = limit - (hasT ? 1 : 0)
  if (budget < 1) budget = 1
  const order = new Uint32Array(total)
  for (let i = 0; i < total; i++) order[i] = i
  // Bölme ağırlığı: sayım^0,75 — seyrek ama belirgin renkler (ince yazı, logo) kendi kutusunu bulabilsin
  const splitW = new Float64Array(total)
  for (let i = 0; i < total; i++) splitW[i] = Math.pow(d.weights[i], SPLIT_WEIGHT_POW)
  let boxes = []
  if (d.opaqueCount > 0 && d.semiCount > 0 && budget < 2) {
    boxes.push(boxStats(d.coords, splitW, order, 0, total))            // tek renk hakkı: sınıflar birleşir
  } else {
    if (d.opaqueCount > 0) boxes.push(boxStats(d.coords, splitW, order, 0, d.opaqueCount))
    if (d.semiCount > 0) boxes.push(boxStats(d.coords, splitW, order, d.opaqueCount, total))
  }
  const mixed = boxes.length === 1 && d.opaqueCount > 0 && d.semiCount > 0
  while (boxes.length < budget) {
    let pick = -1
    let worst = 1e-12
    for (let i = 0; i < boxes.length; i++) {
      if (boxes[i].hi - boxes[i].lo >= 2 && boxes[i].sse > worst) { worst = boxes[i].sse; pick = i }
    }
    if (pick < 0) break
    const parts = splitBox(d.coords, splitW, order, boxes[pick])
    if (!parts) { boxes[pick].sse = 0; continue }
    boxes.splice(pick, 1, parts[0], parts[1])
    if ((boxes.length & 7) === 0) yield
  }
  // Merkezler: opak kutular önce, yarı saydamlar sonra (girdi sırası da öyle)
  const isOpaqueBox = (bx) => !mixed && bx.hi <= d.opaqueCount
  const ob = boxes.filter(isOpaqueBox)
  const sb = boxes.filter((bx) => !isOpaqueBox(bx))
  const k = boxes.length
  const cent = new Float32Array(k * 4)
  const trueMean = (bx, o) => {
    let W = 0
    const s = [0, 0, 0, 0]
    for (let j = bx.lo; j < bx.hi; j++) {
      const e = order[j]
      const w = d.weights[e]
      W += w
      for (let c = 0; c < 4; c++) s[c] += d.coords[e * 4 + c] * w
    }
    for (let c = 0; c < 4; c++) cent[o * 4 + c] = W > 0 ? s[c] / W : bx.mean[c]
  }
  let ci = 0
  for (const bx of ob) trueMean(bx, ci++)
  for (const bx of sb) trueMean(bx, ci++)
  let sse
  if (mixed) sse = yield* refineSteps(d.coords, d.weights, 0, total, cent, 0, k, KMEANS_PASSES)
  else {
    sse = (yield* refineSteps(d.coords, d.weights, 0, d.opaqueCount, cent, 0, ob.length, KMEANS_PASSES))
      + (yield* refineSteps(d.coords, d.weights, d.opaqueCount, total, cent, ob.length, k, KMEANS_PASSES))
  }
  const totalWeight = d.transparentWeight + d.weights.reduce((x, y) => x + y, 0)
  // Merkez → 8 bit RGBA; yinelenenler birleşir
  const seen = new Set()
  const colors = []
  if (hasT) { colors.push([0, 0, 0, 0]); seen.add(0) }
  for (let i = 0; i < k; i++) {
    const opaque = !mixed && i < ob.length
    let af = opaque ? 1 : cent[i * 4 + 3] / S_A
    if (af > 1) af = 1
    let a8 = opaque ? 255 : Math.round(af * 255)
    if (!opaque && !mixed) a8 = Math.max(1, Math.min(254, a8))
    if (a8 <= 0) { if (!seen.has(0)) { seen.add(0); colors.push([0, 0, 0, 0]) } continue }
    const div = af > 1e-6 ? af : 1
    const c = [clamp255((cent[i * 4] / (S_R * div)) * 255), clamp255((cent[i * 4 + 1] / (S_G * div)) * 255),
      clamp255((cent[i * 4 + 2] / (S_B * div)) * 255), a8]
    const key = (c[0] | (c[1] << 8) | (c[2] << 16) | (c[3] << 24)) >>> 0
    if (seen.has(key)) continue
    seen.add(key)
    colors.push(c)
  }
  return finishPalette(sortPalette(colors), false, totalWeight > 0 ? sse / totalWeight : 0)
}

function finishPalette(colors, exact, mse) {
  const palette = new Uint8Array(colors.length * 4)
  let opaque = 0
  let semi = 0
  let hasTransparent = false
  colors.forEach((c, i) => {
    palette.set(c, i * 4)
    if (c[3] === 255) opaque += 1
    else if (c[3] === 0) hasTransparent = true
    else semi += 1
  })
  return { palette, colors: colors.length, exact, mse, hasTransparent, opaque, semi }
}

/**
 * Pikselleri palete eşler. @param {object} pal buildPalette çıktısı
 * @param {{ dither?: boolean, tick?: () => Promise<void> }} [opts] `tick` — uzun işte ara ara beklenir (iptal / akıcılık)
 * @returns {Promise<Uint8Array>} piksel başına palet dizini
 */
export async function remapPixels(data, width, height, pal, opts = {}) {
  const n = width * height
  const out = new Uint8Array(n)
  const P = pal.palette
  const k = pal.colors
  const tick = opts.tick
  if (pal.exact) {
    // Birebir palet: renk → dizin (açık adresli küçük tablo)
    const tb = 10
    const mask = (1 << tb) - 1
    const tk = new Uint32Array(1 << tb)
    const tv = new Int16Array(1 << tb).fill(-1)
    for (let i = 0; i < k; i++) {
      const key = P[i * 4 + 3] === 0 ? 0 : (P[i * 4] | (P[i * 4 + 1] << 8) | (P[i * 4 + 2] << 16) | (P[i * 4 + 3] << 24)) >>> 0
      let slot = hashKey(key, tb)
      while (tv[slot] !== -1) slot = (slot + 1) & mask
      tk[slot] = key
      tv[slot] = i
    }
    for (let y = 0; y < height; y++) {
      for (let x = 0, p = y * width, i = p * 4; x < width; x++, p++, i += 4) {
        const a = data[i + 3]
        const key = a === 0 ? 0 : (data[i] | (data[i + 1] << 8) | (data[i + 2] << 16) | (a << 24)) >>> 0
        let slot = hashKey(key, tb)
        let guard = 0
        while (tv[slot] !== -1 && tk[slot] !== key && guard++ <= mask) slot = (slot + 1) & mask
        out[p] = tv[slot] === -1 ? 0 : tv[slot]
      }
      if (tick && (y & 63) === 63) await tick()
    }
    return out
  }

  // Palet sırası: saydam (a = 0) → yarı saydam → opak (sortPalette); sınıf aralıkları
  let tIndex = -1
  let sFrom = 0
  if (k > 0 && P[3] === 0) { tIndex = 0; sFrom = 1 }
  let oFrom = sFrom
  while (oFrom < k && P[oFrom * 4 + 3] !== 255) oFrom += 1
  const cent = new Float32Array(k * 4)
  for (let i = 0; i < k; i++) toCoords(cent, i * 4, P[i * 4], P[i * 4 + 1], P[i * 4 + 2], P[i * 4 + 3])
  const searchO = buildSearch(cent, oFrom, k)
  const searchS = buildSearch(cent, sFrom, oFrom)
  const anyVisible = buildSearch(cent, sFrom, k)
  const q = new Float32Array(4)
  const CB = 18
  const cmask = (1 << CB) - 1
  const cacheKey = new Uint32Array(1 << CB)
  const cacheVal = new Uint8Array(1 << CB)
  const cacheUsed = new Uint8Array(1 << CB)
  const lookup = (r, g, b, a) => {
    const key = (r | (g << 8) | (b << 16) | (a << 24)) >>> 0
    const slot = hashKey(key, CB) & cmask
    if (cacheUsed[slot] === 1 && cacheKey[slot] === key) return cacheVal[slot]
    toCoords(q, 0, r, g, b, a)
    let idx = a === 255 ? nearestIn(searchO, cent, q[0], q[1], q[2], q[3]) : nearestIn(searchS, cent, q[0], q[1], q[2], q[3])
    if (idx < 0) idx = nearestIn(anyVisible, cent, q[0], q[1], q[2], q[3])
    if (idx < 0) idx = tIndex >= 0 ? tIndex : 0
    cacheUsed[slot] = 1
    cacheKey[slot] = key
    cacheVal[slot] = idx
    return idx
  }
  const transparentIdx = tIndex >= 0 ? tIndex : -1

  if (!opts.dither) {
    for (let y = 0; y < height; y++) {
      for (let x = 0, p = y * width, i = p * 4; x < width; x++, p++, i += 4) {
        const a = data[i + 3]
        out[p] = a === 0 && transparentIdx >= 0 ? transparentIdx : lookup(data[i], data[i + 1], data[i + 2], a === 0 ? 1 : a)
      }
      if (tick && (y & 63) === 63) await tick()
    }
    return out
  }

  // Titreşimde hedef renk (kaynak + hata) neredeyse her pikselde farklıdır: tam anahtarlı önbellek işe yaramaz
  // (4 MP'de ~4 sn). Hedef, hücrelere bölünmüş doğrudan tabloyla aranır — opak: kanal başına 6 bit (4 seviyelik
  // hücre), yarı saydam: 5 bit; hücrenin palet rengi hücre ORTASI için bir kez bulunur. Seçimdeki küçük sapma
  // (≤ hücre köşegeni) hata dağıtımıyla zaten telafi edilir.
  const cellO = new Int16Array(1 << 18).fill(-1)
  const cellS = new Int16Array(1 << 20).fill(-1)
  const lookupCell = (r, g, b, a) => {
    if (a === 255) {
      const key = (r >> 2) | ((g >> 2) << 6) | ((b >> 2) << 12)
      let idx = cellO[key]
      if (idx < 0) { idx = lookup((r & 0xfc) | 2, (g & 0xfc) | 2, (b & 0xfc) | 2, 255); cellO[key] = idx }
      return idx
    }
    const key = (r >> 3) | ((g >> 3) << 5) | ((b >> 3) << 10) | ((a >> 3) << 15)
    let idx = cellS[key]
    if (idx < 0) {
      const ac = Math.max(1, Math.min(254, (a & 0xf8) | 4))
      idx = lookup((r & 0xf8) | 4, (g & 0xf8) | 4, (b & 0xf8) | 4, ac)
      cellS[key] = idx
    }
    return idx
  }

  // Yılan Floyd–Steinberg: hata kırpılır (±DITHER_ERR_MAX) ve sönümlenir; tam saydam piksele hata taşınmaz
  let cur = new Float32Array((width + 2) * 4)
  let next = new Float32Array((width + 2) * 4)
  const E = DITHER_ERR_MAX
  const lim = (v) => (v > E ? E : v < -E ? -E : v)
  for (let y = 0; y < height; y++) {
    const dir = (y & 1) === 0 ? 1 : -1
    next.fill(0)
    for (let xi = 0; xi < width; xi++) {
      const x = dir === 1 ? xi : width - 1 - xi
      const p = y * width + x
      const i = p * 4
      const e = (x + 1) * 4
      const a = data[i + 3]
      if (a === 0 && transparentIdx >= 0) { out[p] = transparentIdx; continue }
      let r = data[i] + lim(cur[e])
      let g = data[i + 1] + lim(cur[e + 1])
      let b = data[i + 2] + lim(cur[e + 2])
      r = r < 0 ? 0 : r > 255 ? 255 : r
      g = g < 0 ? 0 : g > 255 ? 255 : g
      b = b < 0 ? 0 : b > 255 ? 255 : b
      let al = a
      if (a !== 255) {
        al = (a === 0 ? 1 : a) + lim(cur[e + 3])
        al = al < 1 ? 1 : al > 254 ? 254 : al
      }
      const idx = lookupCell((r + 0.5) | 0, (g + 0.5) | 0, (b + 0.5) | 0, a === 255 ? 255 : (al + 0.5) | 0)
      out[p] = idx
      const c = idx * 4
      const er = (r - P[c]) * DITHER_DAMP
      const eg = (g - P[c + 1]) * DITHER_DAMP
      const eb = (b - P[c + 2]) * DITHER_DAMP
      const ea = a === 255 ? 0 : (al - P[c + 3]) * DITHER_DAMP
      const f = e + dir * 4
      const bk = e - dir * 4
      cur[f] += er * 0.4375; cur[f + 1] += eg * 0.4375; cur[f + 2] += eb * 0.4375; cur[f + 3] += ea * 0.4375
      next[bk] += er * 0.1875; next[bk + 1] += eg * 0.1875; next[bk + 2] += eb * 0.1875; next[bk + 3] += ea * 0.1875
      next[e] += er * 0.3125; next[e + 1] += eg * 0.3125; next[e + 2] += eb * 0.3125; next[e + 3] += ea * 0.3125
      next[f] += er * 0.0625; next[f + 1] += eg * 0.0625; next[f + 2] += eb * 0.0625; next[f + 3] += ea * 0.0625
    }
    const t = cur; cur = next; next = t
    if (tick && (y & 31) === 31) await tick()
  }
  return out
}

/** Palet dizinlerini RGBA'ya açar (benzerlik ölçümü ve testler için). */
export function expandPalette(indices, palette, out) {
  const n = indices.length
  const dst = out || new Uint8ClampedArray(n * 4)
  for (let p = 0, o = 0; p < n; p++, o += 4) {
    const c = indices[p] * 4
    dst[o] = palette[c]; dst[o + 1] = palette[c + 1]; dst[o + 2] = palette[c + 2]; dst[o + 3] = palette[c + 3]
  }
  return dst
}
