// Görsel motoru — KAYIPSIZ üstveri ayıklama (saf). "Asla daha kötü" kuralı özgün dosyayı döndürdüğünde içindeki EXIF
// (GPS!), XMP, IPTC, yorum, metin parçası ve dosya sonu ekleri sızmasın diye: görüntü verisi (JPEG entropi baytları,
// PNG IDAT, WebP VP8 / VP8L, GIF kareleri) BAYT BAYT aynı kalır, yalnız üstveri kapları atılır; renk / çözme için
// gereken parçalar (JFIF, ICC profili, Adobe dönüşüm bayrağı, tRNS, gAMA, iCCP, animasyon parçaları …) korunur.
//
// Kurallar: dosyadaki uzunluk / ofset alanlarına güvenilmez (her okuma sınır denetimli, her döngü sayaçlı); düzenli
// ifade yok; yapı beklenmedik olduğunda (bilinmeyen kritik PNG parçası, EOI'siz JPEG, bozuk uzunluk …) `null` döner —
// çağıran o zaman özgün dosyayı DÖNDÜRMEZ, görseli normal işler (üstveri hiçbir koşulda sızmaz).
import { exifOrientation } from './probe.js'
import { pngChunk } from './pngWriter.js'

const u16be = (b, o) => (b[o] << 8) | b[o + 1]
const u32be = (b, o) => b[o] * 16777216 + (b[o + 1] << 16) + (b[o + 2] << 8) + b[o + 3]
const u32le = (b, o) => b[o] + (b[o + 1] << 8) + (b[o + 2] << 16) + b[o + 3] * 16777216
function ascii(b, from, len) {
  let s = ''
  const end = Math.min(b.length, from + len)
  for (let i = from; i < end; i++) s += String.fromCharCode(b[i])
  return s
}

/** Aralık listesinden yeni dizi (yalnız bir şey atıldıysa çağrılır). */
function assemble(b, ranges) {
  let n = 0
  for (const [s, e] of ranges) n += e - s
  const out = new Uint8Array(n)
  let o = 0
  for (const [s, e] of ranges) { out.set(b.subarray(s, e), o); o += e - s }
  return out
}

/** JPEG'de korunan APP bölümleri: JFIF (APP0), ICC profili (APP2), Adobe renk dönüşümü (APP14). Diğer APPn / COM atılır. */
function keepJpegApp(b, m, at) {
  if (m === 0xe0) return ascii(b, at + 4, 5) === 'JFIF\u0000'               // JFXX (küçük resim) atılır
  if (m === 0xe2) return ascii(b, at + 4, 12) === 'ICC_PROFILE\u0000'       // MPF / FPXR atılır
  if (m === 0xee) return ascii(b, at + 4, 5) === 'Adobe'                    // CMYK / YCCK çözümü buna bağlı
  return false
}

function stripJpeg(b) {
  const len = b.length
  if (len < 4 || b[0] !== 0xff || b[1] !== 0xd8) return null
  const ranges = [[0, 2]]
  let removed = false
  let orientation = 1
  let i = 2
  let inScan = false
  // Bölüm başına en az 2 bayt ilerlenir: döngü dosya boyuyla sınırlı
  for (let guard = 0; guard <= len; guard++) {
    if (inScan) {
      // Entropi verisi: 0xFF 00 (doldurulmuş bayt) ve 0xFF D0–D7 (RST) veridir; ilk gerçek işaretçide durulur
      let p = i
      let marker = -1
      for (let g2 = 0; g2 <= len; g2++) {
        p = b.indexOf(0xff, p)
        if (p < 0 || p + 1 >= len) return null                               // EOI yok: kesik dosya
        let q = p + 1
        while (q < len && b[q] === 0xff) q += 1                               // dolgu baytları
        if (q >= len) return null
        const m = b[q]
        if (m === 0x00 || (m >= 0xd0 && m <= 0xd7)) { p = q + 1; continue }
        marker = q - 1
        break
      }
      if (marker < 0) return null
      ranges.push([i, marker])
      i = marker
      inScan = false
    }
    if (i + 2 > len || b[i] !== 0xff) return null
    const m = b[i + 1]
    if (m === 0xff) { i += 1; continue }                                      // başlıktaki dolgu baytı
    if (m === 0xd9) {                                                         // EOI: sonrası (ekler, ikinci görsel) atılır
      ranges.push([i, i + 2])
      if (i + 2 < len) removed = true
      const out = removed ? assemble(b, ranges) : b
      return { bytes: out, removed, orientation }
    }
    if (m === 0xd8) return null                                               // ikinci SOI: beklenmeyen yapı
    if (m === 0x01 || (m >= 0xd0 && m <= 0xd7)) {                             // uzunluksuz işaretçi
      ranges.push([i, i + 2])
      i += 2
      continue
    }
    if (i + 4 > len) return null
    const segLen = u16be(b, i + 2)
    const end = i + 2 + segLen
    if (segLen < 2 || end > len) return null
    const isApp = m >= 0xe0 && m <= 0xef
    if (m === 0xe1 && ascii(b, i + 4, 6) === 'Exif\u0000\u0000' && orientation === 1) orientation = exifOrientation(b, i + 10, end)
    if ((isApp && !keepJpegApp(b, m, i)) || m === 0xfe) {
      removed = true                                                          // EXIF, XMP, IPTC, MPF, yorum …
    } else {
      ranges.push([i, end])
    }
    i = end
    if (m === 0xda) inScan = true                                             // SOS: ardından entropi verisi
  }
  return null
}

/** PNG'de korunan yardımcı parçalar (renk, saydamlık, çözünürlük, APNG). Diğer yardımcı parçalar (metin, eXIf, tIME …) atılır. */
const PNG_KEEP = new Set(['tRNS', 'gAMA', 'cHRM', 'sRGB', 'iCCP', 'cICP', 'mDCv', 'cLLi', 'sBIT', 'pHYs', 'bKGD',
  'acTL', 'fcTL', 'fdAT'])
const PNG_CRITICAL = new Set(['IHDR', 'PLTE', 'IDAT', 'IEND'])
const PNG_SIG = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]
const isLetter = (c) => (c >= 65 && c <= 90) || (c >= 97 && c <= 122)

function stripPng(b) {
  const len = b.length
  if (len < 8 + 25) return null
  for (let k = 0; k < 8; k++) if (b[k] !== PNG_SIG[k]) return null
  const ranges = [[0, 8]]
  let removed = false
  let orientation = 1
  let o = 8
  let afterIhdr = -1
  for (let guard = 0; guard < 1_000_000; guard++) {
    if (o + 12 > len) return null                                             // IEND yok
    const clen = u32be(b, o)
    if (clen > 0x7fffffff || o + 12 + clen > len) return null
    for (let k = 4; k < 8; k++) if (!isLetter(b[o + k])) return null
    const type = ascii(b, o + 4, 4)
    const end = o + 12 + clen
    if (guard === 0 && type !== 'IHDR') return null
    if (type === 'IEND') {
      ranges.push([o, end])
      if (end < len) removed = true                                           // IEND sonrası ekler
      const out = removed ? assemble(b, ranges) : b
      return { bytes: out, removed, orientation, insertAt: afterIhdr }
    }
    const critical = (b[o + 4] & 0x20) === 0
    if (critical) {
      if (!PNG_CRITICAL.has(type)) return null                                // bilinmeyen kritik parça: dokunulmaz
      ranges.push([o, end])
    } else if (PNG_KEEP.has(type)) {
      ranges.push([o, end])
    } else {
      if (type === 'eXIf' && orientation === 1) orientation = exifOrientation(b, o + 8, o + 8 + clen)
      removed = true
    }
    if (type === 'IHDR') afterIhdr = ranges.reduce((n, [s, e]) => n + e - s, 0)
    o = end
  }
  return null
}

const WEBP_KEEP = new Set(['VP8X', 'ICCP', 'ANIM', 'ANMF', 'ALPH', 'VP8 ', 'VP8L'])

function stripWebp(b) {
  const len = b.length
  if (len < 20 || ascii(b, 0, 4) !== 'RIFF' || ascii(b, 8, 4) !== 'WEBP') return null
  const riffEnd = 8 + u32le(b, 4)
  if (riffEnd > len || riffEnd < 20) return null
  const ranges = [[0, 12]]
  let removed = riffEnd < len                                                 // RIFF sonrası ekler
  let orientation = 1
  let o = 12
  let vp8xAt = -1
  for (let guard = 0; guard < 1_000_000 && o < riffEnd; guard++) {
    if (o + 8 > riffEnd) return null
    const type = ascii(b, o, 4)
    const size = u32le(b, o + 4)
    const end = o + 8 + size + (size & 1)
    if (end > riffEnd + (size & 1) || o + 8 + size > riffEnd) return null
    const stop = Math.min(end, riffEnd)
    if (WEBP_KEEP.has(type)) {
      if (type === 'VP8X') vp8xAt = ranges.reduce((n, [s, e]) => n + e - s, 0)
      ranges.push([o, stop])
    } else {
      if (type === 'EXIF' && orientation === 1) {
        const skip = ascii(b, o + 8, 6) === 'Exif\u0000\u0000' ? 6 : 0
        orientation = exifOrientation(b, o + 8 + skip, o + 8 + size)
      }
      removed = true                                                          // EXIF, XMP, bilinmeyen parçalar
    }
    o = end
  }
  if (!removed) return { bytes: b, removed: false, orientation }
  const out = assemble(b, ranges)
  if (out.length & 1) return null
  const riff = out.length - 8
  out[4] = riff & 255; out[5] = (riff >>> 8) & 255; out[6] = (riff >>> 16) & 255; out[7] = (riff >>> 24) & 255
  if (vp8xAt >= 0 && vp8xAt + 9 <= out.length) out[vp8xAt + 8] &= ~(0x08 | 0x04)  // VP8X: EXIF ve XMP bayrakları
  return { bytes: out, removed: true, orientation }
}

/** GIF'te korunan uygulama uzantıları: döngü sayısı (NETSCAPE / ANIMEXTS) ve ICC profili. Yorum ve XMP atılır. */
const GIF_KEEP_APP = new Set(['NETSCAPE2.0', 'ANIMEXTS1.0', 'ICCRGBG1012'])

function stripGif(b) {
  const len = b.length
  if (len < 14 || ascii(b, 0, 4) !== 'GIF8') return null
  const flags = b[10]
  let o = 13 + ((flags & 0x80) ? 3 * (1 << ((flags & 7) + 1)) : 0)
  if (o > len) return null
  const ranges = [[0, o]]
  let removed = false
  const skipSubBlocks = (from) => {
    let p = from
    for (let g = 0; g <= len; g++) {
      if (p >= len) return -1
      const size = b[p]
      p += 1 + size
      if (size === 0) return p <= len ? p : -1
    }
    return -1
  }
  for (let guard = 0; guard <= len; guard++) {
    if (o >= len) return null                                                 // sonlandırıcı yok
    const tag = b[o]
    if (tag === 0x3b) {
      ranges.push([o, o + 1])
      if (o + 1 < len) removed = true
      return { bytes: removed ? assemble(b, ranges) : b, removed, orientation: 1 }
    }
    if (tag === 0x21) {
      if (o + 2 > len) return null
      const label = b[o + 1]
      const end = skipSubBlocks(o + 2)
      if (end < 0) return null
      let keep = true
      if (label === 0xfe) keep = false                                         // yorum
      else if (label === 0xff) keep = b[o + 2] === 11 && GIF_KEEP_APP.has(ascii(b, o + 3, 11))
      if (keep) ranges.push([o, end])
      else removed = true
      o = end
    } else if (tag === 0x2c) {
      if (o + 10 > len) return null
      const lf = b[o + 9]
      const data = o + 10 + ((lf & 0x80) ? 3 * (1 << ((lf & 7) + 1)) : 0) + 1
      const end = skipSubBlocks(data)
      if (end < 0) return null
      ranges.push([o, end])
      o = end
    } else {
      return null
    }
  }
  return null
}

/** AVIF: Exif / XMP öğesi ya da uuid kutusu var mı (true = var ya da anlaşılamadı → ayıklanamaz). */
function avifHasMetadata(b) {
  const IMAGE_ITEMS = new Set(['av01', 'grid', 'iden', 'iovl', 'tmap'])
  let metadata = false
  let sawMeta = false
  const walk = (from, to, max, visit) => {
    let o = from
    for (let guard = 0; guard < max && o + 8 <= to; guard++) {
      let size = u32be(b, o)
      let head = 8
      if (size === 1) {
        if (o + 16 > to || u32be(b, o + 8) !== 0) { metadata = true; return }
        size = u32be(b, o + 12)
        head = 16
      } else if (size === 0) size = to - o
      if (size < head) { metadata = true; return }
      visit(ascii(b, o + 4, 4), o + head, Math.min(to, o + size))
      o += size
    }
  }
  walk(0, b.length, 256, (type, s, e) => {
    if (type === 'uuid') metadata = true
    if (type !== 'meta') return
    sawMeta = true
    walk(s + 4, e, 256, (t1, s1, e1) => {
      if (t1 !== 'iinf' || s1 + 6 > e1) return
      const version = b[s1]
      const first = s1 + 4 + (version === 0 ? 2 : 4)
      walk(first, e1, 4096, (t2, s2, e2) => {
        if (t2 !== 'infe') return
        const v = b[s2]
        if (v < 2) { metadata = true; return }
        const at = s2 + 4 + (v === 2 ? 2 : 4) + 2
        if (at + 4 > e2) { metadata = true; return }
        if (!IMAGE_ITEMS.has(ascii(b, at, 4))) metadata = true               // Exif, mime (XMP), uri … ya da bilinmeyen
      })
    })
  })
  return metadata || !sawMeta
}

/**
 * Üstveriyi kayıpsız ayıklar. @param {Uint8Array} bytes @param {string} type probe.js türü
 * @returns {{ bytes: Uint8Array, removed: boolean, orientation: number, insertAt?: number }|null}
 *   `removed` = bir şey atıldı (false ise `bytes` özgün dizinin KENDİSİDİR). `orientation` = atılan EXIF'teki yön
 *   (1 = yok); çağıran yönü korumak için en küçük bir EXIF yazar. `null` = güvenle ayıklanamaz → özgün dosya dönmez.
 */
export function stripMetadata(bytes, type) {
  try {
    if (type === 'jpeg') return stripJpeg(bytes)
    if (type === 'png') return stripPng(bytes)
    if (type === 'webp') return stripWebp(bytes)
    if (type === 'gif') return stripGif(bytes)
    if (type === 'bmp') return { bytes, removed: false, orientation: 1 }        // BMP'de üstveri kabı yok
    if (type === 'avif') return avifHasMetadata(bytes) ? null : { bytes, removed: false, orientation: 1 }
  } catch {
    return null
  }
  return null
}

/** PNG'ye parça ekler (IHDR'den hemen sonra; IDAT'tan önce olmalı: eXIf ve metin için uygun). */
export function insertPngChunks(png, insertAt, chunks) {
  if (!chunks.length) return png
  if (!(insertAt >= 8 + 25) || insertAt > png.length) return null
  let n = png.length
  for (const c of chunks) n += c.length
  const out = new Uint8Array(n)
  out.set(png.subarray(0, insertAt), 0)
  let o = insertAt
  for (const c of chunks) { out.set(c, o); o += c.length }
  out.set(png.subarray(insertAt), o)
  return out
}

/** PNG eXIf parçası (yön için). */
export function pngExifChunk(tiff) {
  return pngChunk('eXIf', [tiff])
}
