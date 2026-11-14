// Görsel motoru — yapısal benzerlik (SSIM) ölçümü (saf). Kalite aramasının ve "en küçük biçim" kararının ölçütü.
//  • Parlaklık (luma) düzleminde, 8×8 pencere / 4 piksel adım (4×4 hücre toplamlarından — tek geçiş, O(N), ek bellek N/16).
//  • Üç ölçek: tam, 1/2, 1/4 (kutu ortalaması) — ağırlıklı ortalama. Tam ölçek blok / halka bozulmasını, küçük
//    ölçekler normal izleme mesafesindeki yapıyı yakalar; yalnız tam ölçek gürültüye aşırı duyarlı olurdu.
//  • Renk (Cb / Cr) farkı 1/4 ölçekte küçük bir çarpanla cezalandırılır (parlaklığı koruyup rengi kaydıran palet /
//    düşük kalite kodlama "aynı" sayılmasın).
//  • Saydamlık varsa görsel hem SİYAH hem BEYAZ zemine bindirilip ölçülür; küçük olan alınır.
// Büyük görselde tam görsel yerine sabit bir örneklem (döşeme mozaiği) ölçülür — maliyet sınırlı kalır.

// Parlaklık terimi sabiti: standart (0,01 × 255)² yerine (0,1 × 255)². Luma zaten gama kodludur (algısal olarak
// yaklaşık düzgün); standart sabitle parlaklık ORANI terimi koyu bölgede aşırı duyarlıdır — neredeyse siyah bir
// alanda 3 düzeylik (görünmez) ortalama kayması pencereyi 0,87'ye düşürür. Kalibrasyonda (gerçek, koyu alanlı
// fotoğraf) bu, benzerliğin kaliteyle TEK YÖNLÜ artmasını bozuyordu (kalite 70 < kalite 60) — ikili arama tek
// yönlülüğe dayanır. Orta tonlarda etkisi yoktur (128'e karşı 131 → 0,9997).
const C1 = 650.25          // (0,1 × 255)²
const C2 = 58.5225         // (0,03 × 255)²
const SCALE_WEIGHTS = [0.5, 0.3, 0.2]
/** Renk cezası: 1/4 ölçekte Cb / Cr ortalama kare farkı bu değere ulaşınca benzerlik 0,1 düşer. */
const CHROMA_NORM = 400

/** Bu piksel sayısının üstündeki görsel örneklemle (mozaik) ölçülür. */
export const SAMPLE_MAX_PIXELS = 3_000_000
export const TILE = 512
const TILES_PER_AXIS = 3

/** RGBA → luma düzlemi; `backdrop` (0..255) saydam pikselin bindirileceği zemin parlaklığı. */
export function lumaPlane(rgba, w, h, backdrop = 255) {
  const n = w * h
  const out = new Float32Array(n)
  for (let p = 0, i = 0; p < n; p++, i += 4) {
    const y = 0.299 * rgba[i] + 0.587 * rgba[i + 1] + 0.114 * rgba[i + 2]
    const a = rgba[i + 3]
    out[p] = a === 255 ? y : (y * a + backdrop * (255 - a)) / 255
  }
  return out
}

/** 2×2 kutu ortalamasıyla yarıya indirir (tek sayıda kalan son satır / sütun atılır). */
export function half(plane, w, h) {
  const nw = w >> 1
  const nh = h >> 1
  const out = new Float32Array(nw * nh)
  for (let y = 0; y < nh; y++) {
    const r0 = y * 2 * w
    const r1 = r0 + w
    for (let x = 0; x < nw; x++) {
      const c = x * 2
      out[y * nw + x] = (plane[r0 + c] + plane[r0 + c + 1] + plane[r1 + c] + plane[r1 + c + 1]) * 0.25
    }
  }
  return { plane: out, w: nw, h: nh }
}

function ssimWindow(sa, sb, saa, sbb, sab, count) {
  const ma = sa / count
  const mb = sb / count
  const va = Math.max(0, saa / count - ma * ma)
  const vb = Math.max(0, sbb / count - mb * mb)
  const cov = sab / count - ma * mb
  return ((2 * ma * mb + C1) * (2 * cov + C2)) / ((ma * ma + mb * mb + C1) * (va + vb + C2))
}

/**
 * İki düzlemin ortalama SSIM'i: 8×8 pencere, 4 piksel adım. 8 pikselden dar / kısa görselde tek pencere.
 * @returns {number} −1..1 (özdeş düzlemler için tam 1)
 */
export function ssimPlane(a, b, w, h) {
  if (w < 8 || h < 8) {
    let sa = 0; let sb = 0; let saa = 0; let sbb = 0; let sab = 0
    const n = w * h
    for (let i = 0; i < n; i++) { const x = a[i]; const y = b[i]; sa += x; sb += y; saa += x * x; sbb += y * y; sab += x * y }
    return n > 0 ? ssimWindow(sa, sb, saa, sbb, sab, n) : 1
  }
  const cw = w >> 2
  const ch = h >> 2
  const cells = cw * ch
  const A = new Float64Array(cells)
  const B = new Float64Array(cells)
  const AA = new Float64Array(cells)
  const BB = new Float64Array(cells)
  const AB = new Float64Array(cells)
  for (let cy = 0; cy < ch; cy++) {
    for (let dy = 0; dy < 4; dy++) {
      const row = (cy * 4 + dy) * w
      for (let cx = 0; cx < cw; cx++) {
        const c = cy * cw + cx
        const o = row + cx * 4
        for (let dx = 0; dx < 4; dx++) {
          const x = a[o + dx]
          const y = b[o + dx]
          A[c] += x; B[c] += y; AA[c] += x * x; BB[c] += y * y; AB[c] += x * y
        }
      }
    }
  }
  let sum = 0
  let count = 0
  for (let cy = 0; cy + 1 < ch; cy++) {
    for (let cx = 0; cx + 1 < cw; cx++) {
      const c0 = cy * cw + cx
      const c1 = c0 + 1
      const c2 = c0 + cw
      const c3 = c2 + 1
      sum += ssimWindow(A[c0] + A[c1] + A[c2] + A[c3], B[c0] + B[c1] + B[c2] + B[c3], AA[c0] + AA[c1] + AA[c2] + AA[c3],
        BB[c0] + BB[c1] + BB[c2] + BB[c3], AB[c0] + AB[c1] + AB[c2] + AB[c3], 64)
      count += 1
    }
  }
  return count > 0 ? sum / count : 1
}

/** Sınama kâhini: her pencereyi baştan toplayan yalın SSIM (üretimde kullanılmaz). */
export function ssimPlaneSimple(a, b, w, h) {
  if (w < 8 || h < 8) return ssimPlane(a, b, w, h)
  let sum = 0
  let count = 0
  for (let y = 0; y + 8 <= (h >> 2) * 4; y += 4) {
    for (let x = 0; x + 8 <= (w >> 2) * 4; x += 4) {
      let sa = 0; let sb = 0; let saa = 0; let sbb = 0; let sab = 0
      for (let dy = 0; dy < 8; dy++) {
        for (let dx = 0; dx < 8; dx++) {
          const p = (y + dy) * w + x + dx
          sa += a[p]; sb += b[p]; saa += a[p] * a[p]; sbb += b[p] * b[p]; sab += a[p] * b[p]
        }
      }
      sum += ssimWindow(sa, sb, saa, sbb, sab, 64)
      count += 1
    }
  }
  return count > 0 ? sum / count : 1
}

function multiScale(pa, pb, w, h) {
  let a = pa
  let b = pb
  let cw = w
  let ch = h
  let total = 0
  let weight = 0
  for (let s = 0; s < SCALE_WEIGHTS.length; s++) {
    total += ssimPlane(a, b, cw, ch) * SCALE_WEIGHTS[s]
    weight += SCALE_WEIGHTS[s]
    if (s === SCALE_WEIGHTS.length - 1 || cw < 32 || ch < 32) break
    const ha = half(a, cw, ch)
    const hb = half(b, cw, ch)
    a = ha.plane; b = hb.plane; cw = ha.w; ch = ha.h
  }
  return total / weight
}

/** 1/4 ölçekte (4×4 kutu) Cb / Cr ortalama kare farkı. */
function chromaMse(x, y, w, h) {
  const cw = w >> 2
  const ch = h >> 2
  if (cw < 1 || ch < 1) return 0
  let sum = 0
  for (let cy = 0; cy < ch; cy++) {
    for (let cx = 0; cx < cw; cx++) {
      let db = 0
      let dr = 0
      for (let dy = 0; dy < 4; dy++) {
        let i = ((cy * 4 + dy) * w + cx * 4) * 4
        for (let dx = 0; dx < 4; dx++, i += 4) {
          const ax = x[i + 3] / 255
          const ay = y[i + 3] / 255
          // Cb ∝ B − Y, Cr ∝ R − Y (saydamlıkla önçarpımlı; saydam pikselin rengi sayılmaz)
          const yx = 0.299 * x[i] + 0.587 * x[i + 1] + 0.114 * x[i + 2]
          const yy = 0.299 * y[i] + 0.587 * y[i + 1] + 0.114 * y[i + 2]
          db += (x[i + 2] - yx) * ax - (y[i + 2] - yy) * ay
          dr += (x[i] - yx) * ax - (y[i] - yy) * ay
        }
      }
      db /= 16
      dr /= 16
      sum += db * db * 0.3186 + dr * dr * 0.5084          // (0,564)², (0,713)²: Cb / Cr ölçekleri
    }
  }
  return sum / (cw * ch)
}

/**
 * İki eş ölçülü RGBA görselin benzerliği (0..1; özdeş = 1).
 * @param {Uint8Array|Uint8ClampedArray} x kaynak @param {Uint8Array|Uint8ClampedArray} y aday
 */
export function similarity(x, y, w, h) {
  let alpha = false
  const n = w * h
  for (let i = 3; i < n * 4; i += 4) if (x[i] !== 255 || y[i] !== 255) { alpha = true; break }
  let s = multiScale(lumaPlane(x, w, h, 255), lumaPlane(y, w, h, 255), w, h)
  if (alpha) s = Math.min(s, multiScale(lumaPlane(x, w, h, 0), lumaPlane(y, w, h, 0), w, h))
  const penalty = Math.min(1, chromaMse(x, y, w, h) / CHROMA_NORM) * 0.1
  const out = s * (1 - penalty)
  return out < 0 ? 0 : out > 1 ? 1 : out
}

/**
 * Benzerlik örneklemi: küçük görselde tamamı; büyükte 3×3 (en çok) döşeme — 512 px, 16'nın katına hizalı (JPEG / WebP
 * blok ızgarasıyla aynı hizada kalsın). Döşemeler görsele eşit aralıkla yayılır; belirlenimci.
 * @returns {{ whole: boolean, tiles: {x:number,y:number,w:number,h:number}[], cols: number, rows: number,
 *   width: number, height: number }} `width × height` mozaik ölçüsü
 */
export function samplePlan(w, h) {
  if (w * h <= SAMPLE_MAX_PIXELS) return { whole: true, tiles: [{ x: 0, y: 0, w, h }], cols: 1, rows: 1, width: w, height: h }
  const axis = (len) => {
    const t = Math.min(TILE, len)
    const count = Math.max(1, Math.min(TILES_PER_AXIS, Math.floor(len / t)))
    const pos = []
    for (let i = 0; i < count; i++) {
      const p = count === 1 ? (len - t) / 2 : ((len - t) * i) / (count - 1)
      pos.push(Math.max(0, Math.min(len - t, Math.floor(p / 16) * 16)))
    }
    return { t, pos }
  }
  const ax = axis(w)
  const ay = axis(h)
  const tiles = []
  for (const y of ay.pos) for (const x of ax.pos) tiles.push({ x, y, w: ax.t, h: ay.t })
  return { whole: false, tiles, cols: ax.pos.length, rows: ay.pos.length, width: ax.t * ax.pos.length, height: ay.t * ay.pos.length }
}

/** Görselden örneklem mozaiğini çıkarır (tam görselse aynı tampon döner — kopya yok). */
export function extractSample(rgba, w, h, plan) {
  if (plan.whole) return rgba
  const out = new Uint8ClampedArray(plan.width * plan.height * 4)
  plan.tiles.forEach((t, k) => {
    const ox = (k % plan.cols) * t.w
    const oy = Math.floor(k / plan.cols) * t.h
    for (let y = 0; y < t.h; y++) {
      const s = ((t.y + y) * w + t.x) * 4
      out.set(rgba.subarray(s, s + t.w * 4), ((oy + y) * plan.width + ox) * 4)
    }
  })
  return out
}

/** Ayrı ayrı okunmuş döşemeleri (her biri t.w × t.h RGBA) mozaiğe dizer. */
export function assembleSample(tiles, plan) {
  if (plan.whole) return tiles[0]
  const out = new Uint8ClampedArray(plan.width * plan.height * 4)
  plan.tiles.forEach((t, k) => {
    const ox = (k % plan.cols) * t.w
    const oy = Math.floor(k / plan.cols) * t.h
    const src = tiles[k]
    for (let y = 0; y < t.h; y++) out.set(src.subarray(y * t.w * 4, (y + 1) * t.w * 4), ((oy + y) * plan.width + ox) * 4)
  })
  return out
}
