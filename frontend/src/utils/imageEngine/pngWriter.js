// Görsel motoru — PNG yazıcı (saf; sıkıştırma fflate ile, en yüksek düzey). İki çıktı:
//  • paletli (renk türü 3): PLTE + tRNS, bit derinliği renk sayısına göre 1 / 2 / 4 / 8;
//  • gerçek renk: gri (0), RGB (2), gri + saydamlık (4), RGBA (6) — görselin içeriğine göre en dar olanı.
// Satır süzgeci: paletlide "yok"; gerçek renkte satır başına en küçük mutlak toplam (MSAD) sezgisi. Küçük görselde iki
// strateji de sıkıştırılır ve küçük olan alınır. Sıkıştırma akışlıdır (satır kümeleri) — dev görselde ham tamponun
// tamamı bellekte tutulmaz, ana iş parçacığı yedeğinde araya `tick` girer.
import { Zlib } from 'fflate'

const SIGNATURE = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]
/** Bu ham boyuta kadar iki süzgeç stratejisi de denenir (üstünde tek strateji — süre sınırlı kalsın). */
const TRY_BOTH_MAX_RAW = 8 * 1024 * 1024
/** Bu ham boyutun üstünde sıkıştırma düzeyi 9 → 6 (dosya ~%1–2 büyür, süre yarıya iner; dev görselde süre sınırlı kalsın). */
const LEVEL_DOWN_RAW = 32 * 1024 * 1024
const BATCH_BYTES = 1 << 18

const CRC_TABLE = new Uint32Array(256)
for (let n = 0; n < 256; n++) {
  let c = n
  for (let k = 0; k < 8; k++) c = (c & 1) ? (0xedb88320 ^ (c >>> 1)) : (c >>> 1)
  CRC_TABLE[n] = c >>> 0
}

/** CRC-32 (PNG / zlib çokterimlisi); `crc` ile parça parça sürdürülebilir (başlangıç ve bitişte ters çevrilir). */
export function crc32(bytes, start = 0, end = bytes.length, crc = 0) {
  let c = (crc ^ 0xffffffff) >>> 0
  for (let i = start; i < end; i++) c = CRC_TABLE[(c ^ bytes[i]) & 0xff] ^ (c >>> 8)
  return (c ^ 0xffffffff) >>> 0
}

function writeU32(out, o, v) {
  out[o] = (v >>> 24) & 255; out[o + 1] = (v >>> 16) & 255; out[o + 2] = (v >>> 8) & 255; out[o + 3] = v & 255
}

/** Bir PNG parçası: uzunluk + tür + veri + CRC. `parts` veri dilimleri (kopyasız birleştirilir). */
/** Tek PNG parçası: uzunluk + tür + veri + CRC. `parts` ardışık yazılan veri parçalarıdır. */
export function pngChunk(type, parts) {
  let len = 0
  for (const p of parts) len += p.length
  const out = new Uint8Array(12 + len)
  writeU32(out, 0, len)
  for (let i = 0; i < 4; i++) out[4 + i] = type.charCodeAt(i)
  let o = 8
  for (const p of parts) { out.set(p, o); o += p.length }
  writeU32(out, o, crc32(out, 4, o))
  return out
}

/** Metin parçası: Latin-1'e sığıyorsa tEXt, sığmıyorsa iTXt (UTF-8, sıkıştırmasız). Anahtar 1–79 Latin-1 karakter. */
export function textChunk(keyword, text) {
  const kw = String(keyword).slice(0, 79)
  const value = String(text)
  const kwBytes = new Uint8Array(kw.length)
  for (let i = 0; i < kw.length; i++) kwBytes[i] = kw.charCodeAt(i) & 0xff
  let latin = true
  for (let i = 0; i < value.length; i++) if (value.charCodeAt(i) > 255) { latin = false; break }
  if (latin) {
    const body = new Uint8Array(value.length)
    for (let i = 0; i < value.length; i++) body[i] = value.charCodeAt(i)
    return pngChunk('tEXt', [kwBytes, new Uint8Array([0]), body])
  }
  return pngChunk('iTXt', [kwBytes, new Uint8Array([0, 0, 0, 0, 0]), new TextEncoder().encode(value)])
}

/** Gerçek renk çıktısı için içerik: tümü opak mı, tümü gri mi. */
export function analyzeRgba(rgba, pixelCount) {
  let opaque = true
  let gray = true
  for (let p = 0, i = 0; p < pixelCount; p++, i += 4) {
    if (rgba[i + 3] !== 255) opaque = false
    if (rgba[i] !== rgba[i + 1] || rgba[i] !== rgba[i + 2]) gray = false
    if (!opaque && !gray) break
  }
  return { opaque, gray }
}

const absByte = (v) => (v < 128 ? v : 256 - v)

/** Satırı beş süzgeçle dener, mutlak toplamı en küçük olanı `out`a yazar (ilk bayt süzgeç türü). */
function filterAdaptive(cur, prev, bpp, out, scratch) {
  const n = cur.length
  let bestType = 0
  let bestSum = Infinity
  for (let type = 0; type < 5; type++) {
    let sum = 0
    for (let i = 0; i < n; i++) {
      const a = i >= bpp ? cur[i - bpp] : 0
      const b = prev[i]
      const c = i >= bpp ? prev[i - bpp] : 0
      let v
      if (type === 0) v = cur[i]
      else if (type === 1) v = cur[i] - a
      else if (type === 2) v = cur[i] - b
      else if (type === 3) v = cur[i] - ((a + b) >> 1)
      else {
        const p = a + b - c
        const pa = Math.abs(p - a)
        const pb = Math.abs(p - b)
        const pc = Math.abs(p - c)
        v = cur[i] - (pa <= pb && pa <= pc ? a : pb <= pc ? b : c)
      }
      v &= 0xff
      scratch[type * n + i] = v
      sum += absByte(v)
      if (sum >= bestSum) break
    }
    if (sum < bestSum) { bestSum = sum; bestType = type }
  }
  out[0] = bestType
  out.set(scratch.subarray(bestType * n, bestType * n + n), 1)
}

/**
 * Satırları süzüp akışlı sıkıştırır. `rowAt(y, hedef)` ham satırı (rowBytes) doldurur.
 * @returns {Promise<Uint8Array[]>} zlib çıktı dilimleri
 */
async function deflateRows(height, rowBytes, bpp, adaptive, rowAt, level, tick) {
  const chunks = []
  const z = new Zlib({ level, mem: 12 }, (data) => { if (data.length) chunks.push(data) })
  const rowsPerBatch = Math.max(1, Math.floor(BATCH_BYTES / (rowBytes + 1)))
  const batch = new Uint8Array(rowsPerBatch * (rowBytes + 1))
  let cur = new Uint8Array(rowBytes)
  let prev = new Uint8Array(rowBytes)
  const scratch = adaptive ? new Uint8Array(rowBytes * 5) : null
  let used = 0
  for (let y = 0; y < height; y++) {
    rowAt(y, cur)
    const out = batch.subarray(used, used + rowBytes + 1)
    if (adaptive) filterAdaptive(cur, prev, bpp, out, scratch)
    else { out[0] = 0; out.set(cur, 1) }
    used += rowBytes + 1
    const t = prev; prev = cur; cur = t
    const last = y === height - 1
    if (used === batch.length || last) {
      z.push(batch.subarray(0, used), last)
      used = 0
      if (tick && !last) await tick()
    }
  }
  return chunks
}

const totalLength = (chunks) => chunks.reduce((n, c) => n + c.length, 0)

/**
 * PNG üretir.
 * @param {{ width: number, height: number, indices?: Uint8Array, palette?: Uint8Array, colors?: number,
 *   rgba?: Uint8Array|Uint8ClampedArray }} image paletli (indices + palette) ya da gerçek renk (rgba)
 * @param {{ text?: {keyword: string, text: string}[], level?: number, tick?: () => Promise<void> }} [opts]
 * @returns {Promise<{ bytes: Uint8Array, colorType: number, bitDepth: number }>}
 */
export async function encodePng(image, opts = {}) {
  const { width, height } = image
  const tick = opts.tick
  if (!(width >= 1 && height >= 1)) throw new RangeError('png: ölçü')
  let colorType
  let bitDepth
  let rowBytes
  let bpp
  let rowAt
  const extra = []
  if (image.indices && image.palette) {
    const colors = image.colors ?? image.palette.length / 4
    if (!(colors >= 1 && colors <= 256)) throw new RangeError('png: palet')
    colorType = 3
    bitDepth = colors <= 2 ? 1 : colors <= 4 ? 2 : colors <= 16 ? 4 : 8
    rowBytes = Math.ceil((width * bitDepth) / 8)
    bpp = 1
    const idx = image.indices
    if (bitDepth === 8) {
      rowAt = (y, dst) => { dst.set(idx.subarray(y * width, (y + 1) * width)) }
    } else {
      const per = 8 / bitDepth
      rowAt = (y, dst) => {
        dst.fill(0)
        for (let x = 0, p = y * width; x < width; x++, p++) dst[(x / per) | 0] |= idx[p] << (8 - bitDepth * ((x % per) + 1))
      }
    }
    const plte = new Uint8Array(colors * 3)
    let alphaCount = 0
    for (let i = 0; i < colors; i++) {
      plte[i * 3] = image.palette[i * 4]; plte[i * 3 + 1] = image.palette[i * 4 + 1]; plte[i * 3 + 2] = image.palette[i * 4 + 2]
      if (image.palette[i * 4 + 3] !== 255) alphaCount = i + 1
    }
    extra.push(pngChunk('PLTE', [plte]))
    if (alphaCount > 0) {
      const trns = new Uint8Array(alphaCount)
      for (let i = 0; i < alphaCount; i++) trns[i] = image.palette[i * 4 + 3]
      extra.push(pngChunk('tRNS', [trns]))
    }
  } else if (image.rgba) {
    const src = image.rgba
    const { opaque, gray } = analyzeRgba(src, width * height)
    bitDepth = 8
    colorType = gray ? (opaque ? 0 : 4) : (opaque ? 2 : 6)
    const channels = (gray ? 1 : 3) + (opaque ? 0 : 1)
    rowBytes = width * channels
    bpp = channels
    rowAt = (y, dst) => {
      let i = y * width * 4
      let o = 0
      for (let x = 0; x < width; x++, i += 4) {
        dst[o++] = src[i]
        if (!gray) { dst[o++] = src[i + 1]; dst[o++] = src[i + 2] }
        if (!opaque) dst[o++] = src[i + 3]
      }
    }
  } else {
    throw new RangeError('png: görsel')
  }

  const raw = (rowBytes + 1) * height
  const useLevel = opts.level ?? (raw > LEVEL_DOWN_RAW ? 6 : 9)
  // Süzgeç stratejisi: paletli → yok (8 bitte küçük görselde uyarlamalı da denenir); gerçek renk → uyarlamalı (+ yok)
  const strategies = colorType === 3
    ? (bitDepth === 8 && raw <= TRY_BOTH_MAX_RAW ? [false, true] : [false])
    : (raw <= TRY_BOTH_MAX_RAW ? [true, false] : [true])
  let best = null
  for (const adaptive of strategies) {
    const chunks = await deflateRows(height, rowBytes, bpp, adaptive, rowAt, useLevel, tick)
    if (!best || totalLength(chunks) < totalLength(best)) best = chunks
    if (tick) await tick()
  }

  const ihdr = new Uint8Array(13)
  writeU32(ihdr, 0, width)
  writeU32(ihdr, 4, height)
  ihdr[8] = bitDepth
  ihdr[9] = colorType
  const parts = [Uint8Array.from(SIGNATURE), pngChunk('IHDR', [ihdr]), ...extra]
  for (const t of opts.text || []) parts.push(textChunk(t.keyword, t.text))
  parts.push(pngChunk('IDAT', best), pngChunk('IEND', []))
  const bytes = new Uint8Array(totalLength(parts))
  let o = 0
  for (const p of parts) { bytes.set(p, o); o += p.length }
  return { bytes, colorType, bitDepth }
}
