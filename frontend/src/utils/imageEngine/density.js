// Görsel motoru — piksel yoğunluğu (PPI) okuma / yazma (2026-11-15, kullanıcı kararı: "kaynağın PPI değeri çıktıda AYNEN
// kalsın"). SAF modül: dosya baytlarından yoğunluğu okur (analyze.js ile ORTAK okuyucu) ve yeniden kodlanmış JPEG / PNG'ye
// aynı değeri yazar. Tarayıcının JPEG kodlayıcısı JFIF APP0'ı birimsiz (units 0, yalnız en-boy oranı) yazar; PNG
// yazıcımız pHYs yazmaz; WebP ve AVIF'te yoğunluk alanı yoktur.
//
// Kaynak yoğunluğu: JPEG JFIF (units 1 = inç başına, 2 = cm başına; x, y > 0), PNG pHYs (birim 1 = metre başına), BMP
// bilgi başlığı (metre başına piksel). EXIF XResolution okunmaz (EXIF okuyucumuz bu alanı çıkarmıyor).
// Yazma kuralları: JPEG'e aynı birimle (dpi → dpi, dpcm → dpcm; metre başına kaynak → dpi'ye yuvarlanır), PNG'ye metre
// başına (PNG / BMP kaynağının değeri BİREBİR; dpi → round(dpi / 0,0254), dpcm → × 100). Görsel yeniden boyutlansa da
// değer aynı kalır. Her döngü bayt sayısı ve sabit tavanla sınırlıdır; tanınmayan / bozuk dosyada hiçbir şey yazılmaz.
import { crc32 } from './pngWriter.js'

/** JPEG işaret / PNG parça sayısı tavanı (analyze.js ile aynı). */
const MAX_SEGMENTS = 400
/** JFIF APP0 bölütünün en kısa boyu (başlık dahil, uzunluk alanı: 16). */
const JFIF_LEN = 16
/** pHYs parçasının toplam boyu (uzunluk 4 + tür 4 + veri 9 + CRC 4). */
export const PHYS_CHUNK_BYTES = 21
/** Eklenen JFIF APP0'ın toplam boyu (işaret 2 + 16). */
export const JFIF_SEGMENT_BYTES = 18

const u16be = (b, i) => (b[i] << 8) | b[i + 1]
const u32be = (b, i) => ((b[i] << 24) >>> 0) + ((b[i + 1] << 16) | (b[i + 2] << 8) | b[i + 3])
const i32le = (b, i) => b[i] | (b[i + 1] << 8) | (b[i + 2] << 16) | (b[i + 3] << 24)
const round1 = (v) => Math.round(v * 10) / 10
const isJfif = (b, s) => b[s] === 0x4a && b[s + 1] === 0x46 && b[s + 2] === 0x49 && b[s + 3] === 0x46 && b[s + 4] === 0

/**
 * JFIF APP0 gövdesinden (s = "JFIF\0"ın başı) yoğunluk: { unit: 'in' | 'cm', x, y } ya da null (birimsiz / sıfır).
 * Çağıran gövdenin en az 12 bayt olduğunu denetlemiş olmalı.
 */
export function jfifDensityAt(b, s) {
  const units = b[s + 7]
  const x = u16be(b, s + 8)
  const y = u16be(b, s + 10)
  if (!(x > 0 && y > 0) || (units !== 1 && units !== 2)) return null
  return { unit: units === 1 ? 'in' : 'cm', x, y }
}

/** pHYs verisinden (d = verinin başı, len = veri boyu): { unit: 'm', x, y } ya da null (birim 0 / sıfır). */
export function physDensityAt(b, d, len) {
  if (len < 9 || b[d + 8] !== 1) return null
  const x = u32be(b, d)
  const y = u32be(b, d + 4)
  return x > 0 && y > 0 ? { unit: 'm', x, y } : null
}

/** BMP bilgi başlığından (≥ 40 bayt): { unit: 'm', x, y } ya da null. */
export function bmpDensityAt(b, n) {
  if (n < 46) return null
  const hdr = b[14] | (b[15] << 8) | (b[16] << 16) | (b[17] << 24)
  if (hdr < 40) return null
  const x = i32le(b, 38)
  const y = i32le(b, 42)
  return x > 0 && y > 0 ? { unit: 'm', x, y } : null
}

/** Yoğunluk → inç başına piksel (ekranda gösterilen; bir ondalık). */
export function ppiOf(d) {
  if (!d) return null
  if (d.unit === 'in') return { x: d.x, y: d.y }
  if (d.unit === 'cm') return { x: round1(d.x * 2.54), y: round1(d.y * 2.54) }
  return { x: round1(d.x * 0.0254), y: round1(d.y * 0.0254) }
}

/** JPEG'in JFIF APP0 bölütünü bulur (SOS / EOI'ye dek, sınırlı): { at (işaretin konumu), s (gövde), len } ya da null. */
function findJfif(b) {
  const n = b.length
  if (n < 4 || b[0] !== 0xff || b[1] !== 0xd8) return null
  let i = 2
  for (let seg = 0; seg < MAX_SEGMENTS && i + 4 <= n; seg++) {
    if (b[i] !== 0xff) return null
    const marker = b[i + 1]
    if (marker === 0xd9 || marker === 0xda) return null
    const len = u16be(b, i + 2)
    if (len < 2 || i + 2 + len > n) return null
    if (marker === 0xe0 && len >= JFIF_LEN && isJfif(b, i + 4)) return { at: i, s: i + 4, len }
    i += 2 + len
  }
  return null
}

/** Dosyanın yoğunluğu: { unit: 'in' | 'cm' | 'm', x, y } ya da null (yok / tanınmıyor). Hiçbir zaman fırlatmaz. */
export function readDensity(bytes, type) {
  try {
    if (!bytes || typeof bytes.length !== 'number') return null
    if (type === 'jpeg') {
      const j = findJfif(bytes)
      return j ? jfifDensityAt(bytes, j.s) : null
    }
    if (type === 'png') {
      const n = bytes.length
      let i = 8
      for (let seg = 0; seg < MAX_SEGMENTS && i + 12 <= n; seg++) {
        const len = u32be(bytes, i)
        const t = String.fromCharCode(bytes[i + 4], bytes[i + 5], bytes[i + 6], bytes[i + 7])
        if (len > n || i + 12 + len > n) return null
        if (t === 'pHYs') return physDensityAt(bytes, i + 8, len)
        if (t === 'IDAT' || t === 'IEND') return null
        i += 12 + len
      }
      return null
    }
    if (type === 'bmp') return bmpDensityAt(bytes, bytes.length)
    return null
  } catch {
    return null
  }
}

/** Yoğunluk → JFIF alanları { units: 1 | 2, x, y } (16 bit); sığmazsa null. Metre başına kaynak dpi'ye yuvarlanır. */
export function jfifFields(d) {
  if (!d) return null
  let units
  let x
  let y
  if (d.unit === 'in' || d.unit === 'cm') { units = d.unit === 'in' ? 1 : 2; x = d.x; y = d.y }
  else { units = 1; x = Math.round(d.x * 0.0254); y = Math.round(d.y * 0.0254) }
  if (!(x >= 1 && x <= 65535 && y >= 1 && y <= 65535)) return null
  return { units, x, y }
}

/** Yoğunluk → pHYs değerleri (metre başına, 32 bit); metre başına kaynak BİREBİR. Sığmazsa null. */
export function physFields(d) {
  if (!d) return null
  let x
  let y
  if (d.unit === 'm') { x = d.x; y = d.y }
  else if (d.unit === 'cm') { x = d.x * 100; y = d.y * 100 }
  else { x = Math.round(d.x / 0.0254); y = Math.round(d.y / 0.0254) }
  if (!(x >= 1 && x <= 0x7fffffff && y >= 1 && y <= 0x7fffffff)) return null
  return { x, y }
}

/**
 * JPEG'e yoğunluğu yazar: var olan JFIF APP0'ın birim + Xdensity + Ydensity alanları yamalanır (boy aynı); yoksa SOI'nin
 * hemen ardına JFIF APP0 eklenir (18 bayt). Başka bayt değişmez. Yazılamazsa (tanınmayan dosya, sığmayan değer) null.
 */
export function withJpegDensity(jpeg, d) {
  const f = jfifFields(d)
  if (!f || !jpeg || jpeg.length < 4 || jpeg[0] !== 0xff || jpeg[1] !== 0xd8) return null
  const j = findJfif(jpeg)
  if (j) {
    const out = jpeg.slice()
    out[j.s + 7] = f.units
    out[j.s + 8] = (f.x >> 8) & 255; out[j.s + 9] = f.x & 255
    out[j.s + 10] = (f.y >> 8) & 255; out[j.s + 11] = f.y & 255
    return out
  }
  const seg = new Uint8Array(JFIF_SEGMENT_BYTES)
  seg.set([0xff, 0xe0, 0, JFIF_LEN, 0x4a, 0x46, 0x49, 0x46, 0, 1, 1, f.units, (f.x >> 8) & 255, f.x & 255, (f.y >> 8) & 255, f.y & 255, 0, 0])
  const out = new Uint8Array(jpeg.length + seg.length)
  out.set(jpeg.subarray(0, 2), 0)
  out.set(seg, 2)
  out.set(jpeg.subarray(2), 2 + seg.length)
  return out
}

function physChunk(f) {
  const out = new Uint8Array(PHYS_CHUNK_BYTES)
  const w = (o, v) => { out[o] = (v >>> 24) & 255; out[o + 1] = (v >>> 16) & 255; out[o + 2] = (v >>> 8) & 255; out[o + 3] = v & 255 }
  w(0, 9)
  out.set([0x70, 0x48, 0x59, 0x73], 4)                     // "pHYs"
  w(8, f.x)
  w(12, f.y)
  out[16] = 1                                               // birim: metre
  w(17, crc32(out, 4, 17))
  return out
}

/**
 * PNG'ye yoğunluğu yazar: var olan pHYs parçası yeniden yazılır; yoksa İLK IDAT'tan hemen önce yeni pHYs eklenir (21 bayt,
 * CRC doğru). Başka parça değişmez. Yazılamazsa (tanınmayan / bozuk dosya, IDAT yok, sığmayan değer) null.
 */
export function withPngDensity(png, d) {
  const f = physFields(d)
  if (!f || !png || png.length < 8 + 25 || png[0] !== 0x89 || png[1] !== 0x50 || png[2] !== 0x4e || png[3] !== 0x47) return null
  const n = png.length
  let i = 8
  for (let seg = 0; seg < MAX_SEGMENTS && i + 12 <= n; seg++) {
    const len = u32be(png, i)
    const t = String.fromCharCode(png[i + 4], png[i + 5], png[i + 6], png[i + 7])
    if (len > n || i + 12 + len > n) return null
    if (t === 'pHYs') {
      const chunk = physChunk(f)
      const out = new Uint8Array(n - (12 + len) + chunk.length)
      out.set(png.subarray(0, i), 0)
      out.set(chunk, i)
      out.set(png.subarray(i + 12 + len), i + chunk.length)
      return out
    }
    if (t === 'IDAT') {
      if (i < 8 + 25) return null                           // IHDR'den önce IDAT: bozuk dosya
      const chunk = physChunk(f)
      const out = new Uint8Array(n + chunk.length)
      out.set(png.subarray(0, i), 0)
      out.set(chunk, i)
      out.set(png.subarray(i), i + chunk.length)
      return out
    }
    if (t === 'IEND') return null
    i += 12 + len
  }
  return null
}

/** Çıktıya yoğunluğu yazar (yalnız JPEG / PNG); yazılamazsa ya da yoğunluk yoksa null (dosya olduğu gibi kalır). */
export function withDensity(bytes, type, d) {
  if (!d) return null
  if (type === 'jpeg') return withJpegDensity(bytes, d)
  if (type === 'png') return withPngDensity(bytes, d)
  return null
}

/** Yazılacak yoğunluğun çıktıya ekleyebileceği en çok bayt (hedef boyut payı). */
export function densityOverhead(type, d) {
  if (!d) return 0
  if (type === 'jpeg') return jfifFields(d) ? JFIF_SEGMENT_BYTES : 0
  if (type === 'png') return physFields(d) ? PHYS_CHUNK_BYTES : 0
  return 0
}
