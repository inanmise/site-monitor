// Görsel motoru — başlık okuma (saf). Görsel AÇILMADAN tür (sihirli baytlar), piksel ölçüsü, saydamlık ve animasyon
// bilgisi; ölçü sınırı çözmeden ÖNCE denetlenir (küçük dosyaya sıkıştırılmış dev görsel sekmeyi çökertmesin).
// Tüm döngüler sayaç ve dosya boyuyla sınırlıdır; dosyadaki uzunluk / ofset alanlarına güvenilmez; düzenli ifade yok.
import { ENGINE_LIMITS, EngineError } from './errors.js'

/** İlk okumada alınan baş (çoğu görselde yeter; yetmezse dosyanın tamamı okunur). */
export const HEAD_BYTES = 512 * 1024
export const DECODABLE = Object.freeze(['png', 'jpeg', 'gif', 'webp', 'bmp', 'avif'])

const HEIF_BRANDS = ['heic', 'heix', 'hevc', 'hevx', 'heim', 'heis', 'mif1', 'msf1']
const BMP_DIB_SIZES = [12, 40, 52, 56, 64, 108, 124]

function ascii(b, from, len) {
  let s = ''
  const end = Math.min(b.length, from + len)
  for (let i = from; i < end; i++) s += String.fromCharCode(b[i])
  return s
}
const u16be = (b, o) => (b[o] << 8) | b[o + 1]
const u16le = (b, o) => b[o] | (b[o + 1] << 8)
const u32be = (b, o) => b[o] * 16777216 + (b[o + 1] << 16) + (b[o + 2] << 8) + b[o + 3]
const u32le = (b, o) => b[o] + (b[o + 1] << 8) + (b[o + 2] << 16) + b[o + 3] * 16777216

/** Dosya türü ilk baytlardan: png | jpeg | gif | webp | bmp | avif | heic | pdf | svg | tiff | unknown. */
export function sniffType(bytes) {
  const b = bytes
  const at = (i) => (i < b.length ? b[i] : -1)
  if (at(0) === 0x89 && at(1) === 0x50 && at(2) === 0x4e && at(3) === 0x47 && at(4) === 0x0d && at(5) === 0x0a) return 'png'
  if (at(0) === 0xff && at(1) === 0xd8 && at(2) === 0xff) return 'jpeg'
  if (ascii(b, 0, 4) === 'GIF8') return 'gif'
  if (ascii(b, 0, 4) === 'RIFF' && ascii(b, 8, 4) === 'WEBP') return 'webp'
  if (at(0) === 0x42 && at(1) === 0x4d && b.length >= 18 && BMP_DIB_SIZES.includes(u32le(b, 14))) return 'bmp'
  if (ascii(b, 0, 4) === '%PDF') return 'pdf'
  if ((at(0) === 0x49 && at(1) === 0x49 && at(2) === 0x2a && at(3) === 0x00)
    || (at(0) === 0x4d && at(1) === 0x4d && at(2) === 0x00 && at(3) === 0x2a)) return 'tiff'
  if (ascii(b, 4, 4) === 'ftyp' && b.length >= 16) {
    const boxLen = Math.min(Math.max(16, u32be(b, 0)), 512, b.length)
    const brands = [ascii(b, 8, 4)]
    for (let o = 16; o + 4 <= boxLen; o += 4) brands.push(ascii(b, o, 4))
    if (brands.includes('avif') || brands.includes('avis')) return 'avif'
    if (brands.some((x) => HEIF_BRANDS.includes(x))) return 'heic'
  }
  const start = at(0) === 0xef && at(1) === 0xbb && at(2) === 0xbf ? 3 : 0
  const head = ascii(b, start, 512).trimStart().toLowerCase()
  if (head.startsWith('<') && head.includes('<svg') && !head.includes('<html')) return 'svg'
  return 'unknown'
}

/** Profil adı sRGB'yi mi anlatıyor: "sRGB" ile BAŞLAMALI ("Display P3 Gamut with sRGB Transfer" sRGB değildir). */
const srgbName = (text) => String(text).trim().toLowerCase().startsWith('srgb')

/**
 * Gömülü ICC profili sRGB mi? Profilin açıklaması ('desc' etiketi — v2 düz metin ya da v4 'mluc' UTF-16) "sRGB" ile
 * başlıyorsa evet ("sRGB IEC61966-2.1", "sRGB built-in", tarayıcı tuvalinin yazdığı "sRGB" …). sRGB profilli görsel
 * (kameraların, Photoshop'un ve tarayıcının yazdığı JPEG / WebP'lerin çoğu) için "renk profili dönüştürüldü" denmez —
 * dönüşüm yoktur. Okunamayan / tanınmayan profil sRGB SAYILMAZ. Sınırlı: ≤ 64 etiket, ≤ 256 karakter; profildeki
 * ofsetlere güvenilmez.
 */
export function iccLooksSrgb(b, from, to) {
  const end = Math.min(b.length, to)
  if (!(from >= 0) || from + 132 > end) return false
  const count = Math.min(u32be(b, from + 128), 64)
  for (let k = 0; k < count; k++) {
    const e = from + 132 + k * 12
    if (e + 12 > end) return false
    if (ascii(b, e, 4) !== 'desc') continue
    const off = from + u32be(b, e + 4)
    if (off < from || off + 12 > end) return false
    const kind = ascii(b, off, 4)
    let text = ''
    if (kind === 'desc') {
      text = ascii(b, off + 12, Math.min(u32be(b, off + 8), 256, Math.max(0, end - off - 12)))
    } else if (kind === 'mluc' && off + 28 <= end && u32be(b, off + 8) >= 1) {
      const start = off + u32be(b, off + 24)
      const stop = Math.min(end, start + Math.min(u32be(b, off + 20), 512))
      if (start >= off) for (let i = start; i + 1 < stop; i += 2) text += String.fromCharCode((b[i] << 8) | b[i + 1])
    }
    return srgbName(text)
  }
  return false
}

function probePng(b) {
  if (b.length < 33 || ascii(b, 12, 4) !== 'IHDR') return null
  const out = { width: u32be(b, 16), height: u32be(b, 20), hasAlpha: false, animated: false, icc: false, done: false }
  const colorType = b[25]
  if (colorType === 4 || colorType === 6) out.hasAlpha = true
  let o = 8
  for (let guard = 0; guard < 4096; guard++) {
    if (o + 8 > b.length) return out                               // baş yetmedi (done = false)
    const len = u32be(b, o)
    const type = ascii(b, o + 4, 4)
    if (type === 'IDAT' || type === 'IEND') { out.done = true; return out }
    if (type === 'acTL' && o + 16 <= b.length) out.animated = u32be(b, o + 8) > 1
    if (type === 'tRNS') out.hasAlpha = true
    // iCCP: profil sıkıştırılmıştır; yalnız ADI (anahtar sözcük, ≤ 79 bayt) okunur — "sRGB …" ise dönüşüm sayılmaz
    if (type === 'iCCP') out.icc = !srgbName(ascii(b, o + 8, Math.min(len, 79)))
    if (len > 0x7fffffff) { out.done = true; return out }           // bozuk uzunluk: daha ileri gidilmez
    o += 12 + len
  }
  out.done = true
  return out
}

function probeGif(b) {
  if (b.length < 13) return null
  const out = { width: u16le(b, 6), height: u16le(b, 8), hasAlpha: false, animated: false, icc: false, done: false }
  const flags = b[10]
  let o = 13 + ((flags & 0x80) ? 3 * (1 << ((flags & 7) + 1)) : 0)
  let frames = 0
  const skipSubBlocks = () => {
    for (let guard = 0; guard < 0x2000000; guard++) {
      if (o >= b.length) return false
      const size = b[o]
      o += 1 + size
      if (size === 0) return true
    }
    return false
  }
  for (let guard = 0; guard < 100000; guard++) {
    if (o >= b.length) return out
    const tag = b[o]
    if (tag === 0x3b) break
    if (tag === 0x21) {
      if (o + 2 >= b.length) return out
      // Grafik denetim uzantısı: ilk karenin saydamlık bayrağı
      if (b[o + 1] === 0xf9 && frames === 0 && o + 4 < b.length && (b[o + 3] & 1)) out.hasAlpha = true
      o += 2
      if (!skipSubBlocks()) return out
    } else if (tag === 0x2c) {
      frames += 1
      if (frames >= 2) { out.animated = true; break }
      if (o + 10 > b.length) return out
      const lf = b[o + 9]
      o += 10 + ((lf & 0x80) ? 3 * (1 << ((lf & 7) + 1)) : 0) + 1     // + LZW en küçük kod boyu
      if (!skipSubBlocks()) return out
    } else {
      break                                                          // tanınmayan blok: daha ileri gidilmez
    }
  }
  out.done = true
  return out
}

function probeBmp(b) {
  if (b.length < 30) return null
  const dib = u32le(b, 14)
  if (dib === 12) return { width: u16le(b, 18), height: u16le(b, 20), hasAlpha: false, animated: false, icc: false, done: true }
  const w = u32le(b, 18)
  const h = u32le(b, 22)
  if (w > 0x7fffffff) return null
  const bpp = u16le(b, 28)
  return { width: w, height: h > 0x7fffffff ? 0x100000000 - h : h, hasAlpha: bpp === 32 ? null : false,
    animated: false, icc: false, done: true }
}

function probeWebp(b) {
  if (b.length < 30) return null
  const fmt = ascii(b, 12, 4)
  const base = { hasAlpha: false, animated: false, icc: false, done: true }
  if (fmt === 'VP8 ') {
    if (!(b[23] === 0x9d && b[24] === 0x01 && b[25] === 0x2a)) return null
    return { ...base, width: u16le(b, 26) & 0x3fff, height: u16le(b, 28) & 0x3fff }
  }
  if (fmt === 'VP8L') {
    if (b[20] !== 0x2f) return null
    return { ...base, width: 1 + (((b[22] & 0x3f) << 8) | b[21]),
      height: 1 + (((b[24] & 0x0f) << 10) | (b[23] << 2) | ((b[22] & 0xc0) >> 6)), hasAlpha: (b[24] & 0x10) !== 0 }
  }
  if (fmt === 'VP8X') {
    const flags = b[20]
    // ICC bayrağı: profil VP8X'in hemen ardındaki 'ICCP' parçasıdır (ham); sRGB ise dönüşüm sayılmaz
    let icc = (flags & 0x20) !== 0
    if (icc && ascii(b, 30, 4) === 'ICCP' && b.length >= 38) icc = !iccLooksSrgb(b, 38, 38 + u32le(b, 34))
    return { ...base, width: 1 + (b[24] | (b[25] << 8) | (b[26] << 16)), height: 1 + (b[27] | (b[28] << 8) | (b[29] << 16)),
      hasAlpha: (flags & 0x10) !== 0, animated: (flags & 0x02) !== 0, icc }
  }
  return null
}

/** JPEG: SOF ölçüsü + EXIF yönü + ICC varlığı; işaretçi yürüyüşü sayaçla sınırlı. */
function probeJpeg(b) {
  const out = { width: 0, height: 0, hasAlpha: false, animated: false, icc: false, orientation: 1, done: false }
  let i = 2
  let iccSrgb = false
  for (let guard = 0; guard < 2000; guard++) {
    if (i + 4 > b.length) return out
    if (b[i] !== 0xff) { out.done = true; return out }
    const m = b[i + 1]
    if (m === 0xff) { i += 1; continue }
    if (m === 0xd8 || m === 0x01 || (m >= 0xd0 && m <= 0xd7)) { i += 2; continue }
    if (m === 0xda || m === 0xd9) { out.done = true; return out }
    const len = u16be(b, i + 2)
    if (len < 2) { out.done = true; return out }
    if (m >= 0xc0 && m <= 0xcf && m !== 0xc4 && m !== 0xc8 && m !== 0xcc) {
      if (i + 9 > b.length) return out
      out.height = u16be(b, i + 5)
      out.width = u16be(b, i + 7)
      out.done = true
      return out
    }
    if (m === 0xe1 && ascii(b, i + 4, 6) === 'Exif\u0000\u0000') out.orientation = exifOrientation(b, i + 10, i + 2 + len)
    if (m === 0xe2 && ascii(b, i + 4, 12) === 'ICC_PROFILE\u0000') {
      // Profilin ilk parçası (sıra 1) açıklamayı taşır: sRGB ise "dönüştürüldü" denmez; sonraki parçalar kararı değiştirmez
      if (b[i + 16] === 1) iccSrgb = iccLooksSrgb(b, i + 18, i + 2 + len)
      out.icc = !iccSrgb
    }
    i += 2 + len
  }
  out.done = true
  return out
}

/** TIFF başlığı `t`de olan EXIF bloğunun yönü (1–8); okunamazsa 1. */
export function exifOrientation(b, t, limit) {
  const end = Math.min(b.length, limit)
  if (t + 8 > end) return 1
  const le = b[t] === 0x49 && b[t + 1] === 0x49
  if (!le && !(b[t] === 0x4d && b[t + 1] === 0x4d)) return 1
  const u16 = (o) => (le ? u16le(b, o) : u16be(b, o))
  const u32 = (o) => (le ? u32le(b, o) : u32be(b, o))
  const ifd = t + u32(t + 4)
  if (ifd < t || ifd + 2 > end) return 1
  const n = Math.min(u16(ifd), 256)
  for (let k = 0; k < n; k++) {
    const e = ifd + 2 + k * 12
    if (e + 12 > end) return 1
    if (u16(e) === 0x0112) {
      const v = u16(e + 8)
      return v >= 1 && v <= 8 ? v : 1
    }
  }
  return 1
}

/** ISO BMFF kutularını yürür (en çok `max` kutu): `visit(type, bodyStart, bodyEnd)`. */
function walkBoxes(b, from, to, max, visit) {
  let o = from
  for (let guard = 0; guard < max && o + 8 <= to; guard++) {
    let size = u32be(b, o)
    let head = 8
    if (size === 1) {
      if (o + 16 > to) return
      if (u32be(b, o + 8) !== 0) return                              // > 4 GB kutu: bu motorun sınırının çok üstünde
      size = u32be(b, o + 12)
      head = 16
    } else if (size === 0) {
      size = to - o
    }
    if (size < head) return
    const end = Math.min(to, o + size)
    visit(ascii(b, o + 4, 4), o + head, end)
    o += size
  }
}

function probeAvif(b, total) {
  const out = { width: 0, height: 0, hasAlpha: false, animated: false, icc: false, done: false }
  const brandEnd = Math.min(b.length, Math.max(16, u32be(b, 0)), 512)
  for (let o = 8; o + 4 <= brandEnd; o += o === 8 ? 8 : 4) if (ascii(b, o, 4) === 'avis') out.animated = true
  let metaSeen = false
  let rotated = false
  walkBoxes(b, 0, b.length, 64, (type, s, e) => {
    if (type !== 'meta') return
    metaSeen = true
    // Kutunun tamamı başta değilse (dosya daha uzun) ölçü eksik kalabilir → çağıran tam dosyayla yeniden dener
    walkBoxes(b, s + 4, e, 256, (t1, s1, e1) => {
      if (t1 !== 'iprp') return
      walkBoxes(b, s1, e1, 64, (t2, s2, e2) => {
        if (t2 !== 'ipco') return
        walkBoxes(b, s2, e2, 1024, (t3, s3, e3) => {
          if (t3 === 'ispe' && s3 + 12 <= e3) {
            const w = u32be(b, s3 + 4)
            const h = u32be(b, s3 + 8)
            // Birincil görsel en büyük olandır (küçük resim / döşeme parçaları daha küçük): sınır için güvenli üst değer
            if (w * h > out.width * out.height) { out.width = w; out.height = h }
          } else if (t3 === 'auxC') {
            if (ascii(b, s3 + 4, 64).includes('alpha')) out.hasAlpha = true
          } else if (t3 === 'colr') {
            const kind = ascii(b, s3, 4)
            if (kind === 'prof' || kind === 'rICC') out.icc = true
          } else if (t3 === 'irot' && s3 < e3) {
            if (b[s3] & 1) rotated = true
          }
        })
      })
    })
  })
  if (rotated) { const t = out.width; out.width = out.height; out.height = t }
  out.done = (metaSeen && out.width > 0) || b.length >= total
  return out
}

/**
 * Baytlardan görsel bilgisi. `bytes` dosyanın başı (ya da tamamı), `total` dosyanın tam boyu.
 * @returns {{ type: string, width: number, height: number, hasAlpha: boolean|null, animated: boolean, icc: boolean,
 *   orientation: number, complete: boolean }} `complete = false` → baş yetmedi, tam dosyayla yeniden çağırın.
 * @throws EngineError UNSUPPORTED_FORMAT / DECODE_FAILED / TOO_MANY_PIXELS
 */
export function probeBytes(bytes, total = bytes.length) {
  const type = sniffType(bytes)
  if (!DECODABLE.includes(type)) throw new EngineError('UNSUPPORTED_FORMAT', { detected: bytes.length ? type : 'empty' })
  let p = null
  if (type === 'png') p = probePng(bytes)
  else if (type === 'jpeg') p = probeJpeg(bytes)
  else if (type === 'gif') p = probeGif(bytes)
  else if (type === 'webp') p = probeWebp(bytes)
  else if (type === 'bmp') p = probeBmp(bytes)
  else p = probeAvif(bytes, total)
  const whole = bytes.length >= total
  if (!p || (!(p.width > 0 && p.height > 0) && (p.done || whole))) {
    if (!p && !whole) return { type, width: 0, height: 0, hasAlpha: null, animated: false, icc: false, orientation: 1, complete: false }
    // Ölçüsü başlıktan okunamayan görsel AÇILMAZ (sınır denetlenemez)
    throw new EngineError('DECODE_FAILED', { reason: 'header' })
  }
  let { width, height } = p
  const orientation = p.orientation || 1
  if (orientation >= 5 && orientation <= 8) { const t = width; width = height; height = t }   // tarayıcı yönü uygular
  const complete = p.done || whole
  if (width > 0 && height > 0) checkLimits(width, height)
  return { type, width, height, hasAlpha: p.hasAlpha, animated: !!p.animated, icc: !!p.icc, orientation, complete }
}

/** Ölçü sınırı (çözmeden ÖNCE ve çözdükten sonra bir kez daha). */
export function checkLimits(width, height) {
  if (width * height > ENGINE_LIMITS.maxPixels || Math.max(width, height) > ENGINE_LIMITS.maxSide) {
    throw new EngineError('TOO_MANY_PIXELS', { width, height, maxPixels: ENGINE_LIMITS.maxPixels, maxSide: ENGINE_LIMITS.maxSide })
  }
}

/** Blob / File baytlarını okur (`arrayBuffer` yoksa FileReader). */
export function readBlob(blob) {
  if (typeof blob.arrayBuffer === 'function') return blob.arrayBuffer().then((b) => new Uint8Array(b))
  return new Promise((resolve, reject) => {
    const fr = new FileReader()
    fr.onload = () => resolve(new Uint8Array(fr.result))
    fr.onerror = () => reject(fr.error || new Error('read'))
    fr.readAsArrayBuffer(blob)
  })
}

/**
 * Blob'dan başlık bilgisi: önce baş (512 KB), yetmezse dosyanın tamamı. Dosya boyu sınırı okumadan ÖNCE denetlenir.
 * @returns {Promise<{ type, width, height, bytes, hasAlpha, animated, icc, orientation }>}
 */
export async function probeBlob(blob) {
  const size = Number(blob?.size)
  if (!blob || !Number.isFinite(size)) throw new EngineError('INVALID_OPTIONS', { field: 'blob', reason: 'type' })
  if (size > ENGINE_LIMITS.maxInputBytes) throw new EngineError('TOO_LARGE', { bytes: size, maxBytes: ENGINE_LIMITS.maxInputBytes })
  if (size === 0) throw new EngineError('UNSUPPORTED_FORMAT', { detected: 'empty' })
  let head
  try {
    head = await readBlob(size > HEAD_BYTES && typeof blob.slice === 'function' ? blob.slice(0, HEAD_BYTES) : blob)
  } catch {
    throw new EngineError('DECODE_FAILED', { reason: 'read' })
  }
  let info = probeBytes(head, size)
  if (!info.complete && head.length < size) {
    let all
    try { all = await readBlob(blob) } catch { throw new EngineError('DECODE_FAILED', { reason: 'read' }) }
    info = probeBytes(all, all.length)
  }
  if (!(info.width > 0 && info.height > 0)) throw new EngineError('DECODE_FAILED', { reason: 'header' })
  return { type: info.type, width: info.width, height: info.height, bytes: size, hasAlpha: info.hasAlpha,
    animated: info.animated, icc: info.icc, orientation: info.orientation }
}
