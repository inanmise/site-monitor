// Görsel aracı derin karşılaştırma (2026-11-15, kullanıcı isteği: "önce → sonra değerler net olsun: boyut, en-boy, toplam
// piksel, DPI / PPI, sıkıştırma oranı, renk / bit derinliği, JPEG tekrar kaydetme kaybı"). SAF modül (DOM / tuval yok):
//  - readImageFacts(bytes, type): dosya başlığından bit derinliği, renk modeli, piksel yoğunluğu (PPI), JPEG kalite tahmini,
//    renk alt örneklemesi, aşamalı mı, palet boyu, kayıpsız mı. Her döngü bayt sayısı ve sabit tavanla sınırlı; tanınmayan
//    alan null ("bilinmiyor") — asla uydurulmuş değer.
//  - countColors(rgba, w, h): eşit aralıklı örnekte farklı renk sayısı (en çok COLOR_COUNT_CAP; aşarsa `atLeast`).
//  - svgCandidate / bandingRisk: öneri kuralları.

/** Başlıkta en çok bu kadar bayt okunur (JPEG işaretleri, PNG parçaları). */
export const FACTS_SCAN_BYTES = 512 * 1024
/** JPEG işaret / PNG parça sayısı tavanı. */
const MAX_SEGMENTS = 400
/** Renk sayımında en çok bu kadar piksel örneklenir. */
export const COLOR_SAMPLE_PIXELS = 262_144
/** Renk sayımı bu sayıya ulaşınca durur ("en az N"). */
export const COLOR_COUNT_CAP = 4096
/** SVG önerisi: en çok bu kadar renk ve en uzun kenar. */
export const SVG_MAX_COLORS = 24
export const SVG_MAX_SIDE = 1024
/** Baskın renk: görünür örneklerin en az bu payını kaplayan renk. */
export const DOMINANT_MIN_SHARE = 0.01
/** Kenar yumuşatmalı logo: en çok bu kadar baskın renk … */
export const SVG_MAX_DOMINANT = 8
/** … görünür alanın en az bu payını kaplamalı (kalan = kenar ara renkleri). */
export const SVG_MIN_DOMINANT_SHARE = 0.85

/** IJG standart parlaklık niceleme tablosu (zig-zag değil, doğal sıra — toplam karşılaştırılır, sıra önemsiz). */
const STD_LUMA = [
  16, 11, 10, 16, 24, 40, 51, 61, 12, 12, 14, 19, 26, 58, 60, 55, 14, 13, 16, 24, 40, 57, 69, 56, 14, 17, 22, 29, 51, 87, 80, 62,
  18, 22, 37, 56, 68, 109, 103, 77, 24, 35, 55, 64, 81, 104, 113, 92, 49, 64, 78, 87, 103, 121, 120, 101, 72, 92, 95, 98, 112, 100, 103, 99,
]
const STD_LUMA_SUM = STD_LUMA.reduce((a, b) => a + b, 0)

const u16be = (b, i) => (b[i] << 8) | b[i + 1]
const u16le = (b, i) => b[i] | (b[i + 1] << 8)
const u32be = (b, i) => ((b[i] << 24) >>> 0) + ((b[i + 1] << 16) | (b[i + 2] << 8) | b[i + 3])
const u32le = (b, i) => (b[i] | (b[i + 1] << 8) | (b[i + 2] << 16)) + ((b[i + 3] << 24) >>> 0)
const i32le = (b, i) => b[i] | (b[i + 1] << 8) | (b[i + 2] << 16) | (b[i + 3] << 24)
const round1 = (v) => Math.round(v * 10) / 10

function empty() {
  return { bitDepth: null, colorModel: null, ppi: null, jpeg: null, paletteSize: null, lossless: null }
}

/**
 * Niceleme tablosu toplamından IJG kalite tahmini (1–100). Ölçek = toplam / standart × 100; ölçek ≤ 100 ise
 * kalite = (200 − ölçek) / 2, değilse 5000 / ölçek. Standart dışı tablolarda yaklaşık değerdir.
 */
export function estimateJpegQuality(table) {
  if (!Array.isArray(table) && !(table instanceof Uint16Array) || table.length !== 64) return null
  let sum = 0
  let max = 0
  for (let i = 0; i < 64; i++) { sum += table[i]; if (table[i] > max) max = table[i] }
  if (!(sum > 0)) return null
  if (max === 1) return 100                                      // kalite 100: bütün katsayılar 1
  const scale = (sum * 100) / STD_LUMA_SUM
  const q = scale <= 100 ? (200 - scale) / 2 : 5000 / scale
  return Math.max(1, Math.min(100, Math.round(q)))
}

/** JPEG: JFIF yoğunluğu, DQT (kalite), SOF (bit derinliği, bileşen, örnekleme, aşamalı). */
function jpegFacts(b, n) {
  const out = empty()
  out.lossless = false
  let i = 2
  let seg = 0
  let lumaTable = null
  while (i + 4 <= n && seg < MAX_SEGMENTS) {
    if (b[i] !== 0xff) { i += 1; continue }
    const marker = b[i + 1]
    if (marker === 0xff) { i += 1; continue }
    if (marker === 0xd8 || (marker >= 0xd0 && marker <= 0xd7) || marker === 0x01) { i += 2; continue }
    if (marker === 0xd9 || marker === 0xda) break               // EOI / SOS: başlık bitti
    seg += 1
    const len = u16be(b, i + 2)
    if (len < 2 || i + 2 + len > n) break
    const s = i + 4
    const end = i + 2 + len
    if (marker === 0xe0 && len >= 16 && b[s] === 0x4a && b[s + 1] === 0x46 && b[s + 2] === 0x49 && b[s + 3] === 0x46 && b[s + 4] === 0) {
      const units = b[s + 7]
      const x = u16be(b, s + 8)
      const y = u16be(b, s + 10)
      if (x > 0 && y > 0 && (units === 1 || units === 2)) {
        out.ppi = units === 1 ? { x, y } : { x: round1(x * 2.54), y: round1(y * 2.54) }
      }
    } else if (marker === 0xdb) {
      let p = s
      let guard = 0
      while (p < end && guard < 4) {
        guard += 1
        const pq = b[p] >> 4
        const tq = b[p] & 0x0f
        const size = pq ? 128 : 64
        if (p + 1 + size > end) break
        if (tq === 0 && lumaTable == null) {
          lumaTable = new Uint16Array(64)
          for (let k = 0; k < 64; k++) lumaTable[k] = pq ? u16be(b, p + 1 + k * 2) : b[p + 1 + k]
        }
        p += 1 + size
      }
    } else if ((marker >= 0xc0 && marker <= 0xc3) || (marker >= 0xc5 && marker <= 0xc7) || (marker >= 0xc9 && marker <= 0xcb)
      || (marker >= 0xcd && marker <= 0xcf)) {
      const precision = b[s]
      const comps = b[s + 5]
      out.bitDepth = precision > 0 ? precision : null
      out.colorModel = comps === 1 ? 'gray' : comps === 3 ? 'rgb' : comps === 4 ? 'cmyk' : null
      const progressive = marker === 0xc2 || marker === 0xc6 || marker === 0xca || marker === 0xce
      let subsampling = null
      if (comps === 3 && s + 6 + 9 <= end) {
        const f = (k) => b[s + 6 + k * 3 + 1]
        const yh = f(0) >> 4
        const yv = f(0) & 0x0f
        const ch = f(1) >> 4
        const cv = f(1) & 0x0f
        if (ch > 0 && cv > 0) {
          const h = yh / ch
          const v = yv / cv
          subsampling = h === 2 && v === 2 ? '4:2:0' : h === 2 && v === 1 ? '4:2:2' : h === 1 && v === 1 ? '4:4:4' : `${yh}x${yv}`
        }
      }
      out.jpeg = { quality: null, subsampling, progressive, components: comps }
    }
    i = end
  }
  if (out.jpeg && lumaTable) out.jpeg.quality = estimateJpegQuality(lumaTable)
  else if (lumaTable) out.jpeg = { quality: estimateJpegQuality(lumaTable), subsampling: null, progressive: null, components: null }
  return out
}

const PNG_MODEL = { 0: 'gray', 2: 'rgb', 3: 'palette', 4: 'gray-alpha', 6: 'rgba' }

/** PNG: IHDR (bit derinliği, renk türü), pHYs (yoğunluk), PLTE (palet boyu). */
function pngFacts(b, n) {
  const out = empty()
  out.lossless = true
  let i = 8
  let seg = 0
  while (i + 12 <= n && seg < MAX_SEGMENTS) {
    seg += 1
    const len = u32be(b, i)
    const type = String.fromCharCode(b[i + 4], b[i + 5], b[i + 6], b[i + 7])
    const d = i + 8
    if (len > n || d + len > n) break
    if (type === 'IHDR' && len >= 13) {
      out.bitDepth = b[d + 8]
      out.colorModel = PNG_MODEL[b[d + 9]] ?? null
    } else if (type === 'pHYs' && len >= 9 && b[d + 8] === 1) {
      const x = u32be(b, d)
      const y = u32be(b, d + 4)
      if (x > 0 && y > 0) out.ppi = { x: round1(x * 0.0254), y: round1(y * 0.0254) }
    } else if (type === 'PLTE') {
      out.paletteSize = Math.floor(len / 3)
    } else if (type === 'IDAT' || type === 'IEND') {
      break
    }
    i = d + len + 4
  }
  return out
}

/** GIF: mantıksal ekran tanımındaki genel renk tablosu. */
function gifFacts(b, n) {
  const out = empty()
  out.lossless = true
  out.colorModel = 'palette'
  out.bitDepth = 8
  if (n >= 13 && (b[10] & 0x80)) out.paletteSize = 2 ** ((b[10] & 0x07) + 1)
  return out
}

/** WebP: VP8 (kayıplı) / VP8L (kayıpsız) / VP8X (+ALPH). */
function webpFacts(b, n) {
  const out = empty()
  out.bitDepth = 8
  let i = 12
  let seg = 0
  let alpha = false
  while (i + 8 <= n && seg < MAX_SEGMENTS) {
    seg += 1
    const fourcc = String.fromCharCode(b[i], b[i + 1], b[i + 2], b[i + 3])
    const len = u32le(b, i + 4)
    if (fourcc === 'VP8X' && n > i + 8) alpha = alpha || (b[i + 8] & 0x10) !== 0
    else if (fourcc === 'ALPH') alpha = true
    else if (fourcc === 'VP8L') { out.lossless = true; alpha = alpha || ((n > i + 12) && ((b[i + 8 + 4] >> 4) & 1) === 1); break }
    else if (fourcc === 'VP8 ') { out.lossless = false; break }
    if (len > n) break
    i += 8 + len + (len & 1)
  }
  out.colorModel = alpha ? 'rgba' : 'rgb'
  return out
}

/** BMP: bit / piksel, yoğunluk (piksel / metre). */
function bmpFacts(b, n) {
  const out = empty()
  out.lossless = true
  if (n < 46) return out
  const bpp = u16le(b, 28)
  out.bitDepth = bpp >= 24 ? 8 : bpp || null
  out.colorModel = bpp <= 8 ? 'palette' : bpp === 32 ? 'rgba' : 'rgb'
  const x = i32le(b, 38)
  const y = i32le(b, 42)
  if (x > 0 && y > 0) out.ppi = { x: round1(x * 0.0254), y: round1(y * 0.0254) }
  return out
}

/**
 * Dosya başlığından görsel bilgileri. `bytes` Uint8Array; yalnız ilk {@link FACTS_SCAN_BYTES} okunur. Tanınmayan tür / bozuk
 * başlık → tüm alanlar null. Hiçbir zaman fırlatmaz.
 */
export function readImageFacts(bytes, type) {
  try {
    if (!bytes || typeof bytes.length !== 'number' || bytes.length < 8) return empty()
    const n = Math.min(bytes.length, FACTS_SCAN_BYTES)
    if (type === 'jpeg') return jpegFacts(bytes, n)
    if (type === 'png') return pngFacts(bytes, n)
    if (type === 'gif') return gifFacts(bytes, n)
    if (type === 'webp') return webpFacts(bytes, n)
    if (type === 'bmp') return bmpFacts(bytes, n)
    return empty()
  } catch {
    return empty()
  }
}

/**
 * Eşit aralıklı örnekte farklı (opak sayılan) renk sayısı. Saydam pikseller (alfa 0) tek renk sayılır. Örnek en çok
 * {@link COLOR_SAMPLE_PIXELS} piksel; sayım {@link COLOR_COUNT_CAP}'e ulaşınca durur → `{ count, atLeast: true }`.
 */
export function countColors(rgba, width, height) {
  const total = Math.max(0, Math.floor(width) * Math.floor(height))
  if (!rgba || total === 0 || rgba.length < total * 4) return { count: null, atLeast: false }
  const step = Math.max(1, Math.ceil(total / COLOR_SAMPLE_PIXELS))
  const seen = new Map()
  let visible = 0
  for (let p = 0; p < total; p += step) {
    const o = p * 4
    const a = rgba[o + 3]
    const key = a === 0 ? -1 : ((rgba[o] << 16) | (rgba[o + 1] << 8) | rgba[o + 2]) * 4 + (a === 255 ? 0 : a < 128 ? 1 : 2)
    if (a !== 0) visible += 1
    seen.set(key, (seen.get(key) || 0) + 1)
    if (seen.size >= COLOR_COUNT_CAP) return { count: COLOR_COUNT_CAP, atLeast: true, dominant: null, dominantShare: null }
  }
  // Baskın renkler: görünür örneklerin en az %1'ini kaplayan renkler (kenar yumuşatmasının yüzlerce ara rengi sayılmaz)
  let dominant = 0
  let covered = 0
  for (const [key, n] of seen) {
    if (key === -1 || visible === 0) continue
    if (n / visible >= DOMINANT_MIN_SHARE) { dominant += 1; covered += n }
  }
  return { count: seen.size, atLeast: false, dominant, dominantShare: visible === 0 ? null : covered / visible }
}

/**
 * Logo / simge gibi mi (küçük ölçü + az renk) — SVG önerisi. Az renk: toplam ≤ {@link SVG_MAX_COLORS} ya da kenar
 * yumuşatmalı logo — en çok {@link SVG_MAX_DOMINANT} baskın renk görünür alanın ≥ %{@link SVG_MIN_DOMINANT_SHARE}'ini kaplar.
 * Renk sayısı bilinmiyorsa false.
 */
export function svgCandidate(colors, width, height) {
  if (!colors || colors.count == null || colors.atLeast) return false
  const side = Math.max(width, height)
  if (!(side >= 16 && side <= SVG_MAX_SIDE)) return false
  if (colors.count <= SVG_MAX_COLORS) return true
  return colors.dominant != null && colors.dominant >= 1 && colors.dominant <= SVG_MAX_DOMINANT
    && colors.dominantShare != null && colors.dominantShare >= SVG_MIN_DOMINANT_SHARE
}

/**
 * Bantlaşma riski: 16 bit kaynak 8 bite indi, ya da paletli PNG'ye az renkle (≤ 64) inildi ve benzerlik hedefin altında.
 */
export function bandingRisk({ inputBitDepth, outputType, outputColors, similarity }) {
  if (inputBitDepth != null && inputBitDepth > 8) return 'bit_depth'
  if (outputType === 'png' && outputColors != null && outputColors > 0 && outputColors <= 64
    && similarity != null && similarity < 0.985) return 'palette'
  return null
}
