// Görsel motoru — üstveri okuma / yazma (saf). Varsayılan: TÜM üstveri atılır. İstenirse yalnız üç tür korunur:
// telif (Copyright), oluşturma zamanı (DateTimeOriginal) ve konum (GPS). Kaynaktan yalnız bu alanlar ayrıştırılır ve
// çıktıya EN KÜÇÜK bir EXIF bloğu (JPEG APP1 / WebP EXIF parçası) ya da PNG metin parçaları olarak yeniden yazılır —
// yön etiketi, küçük resim, üretici notları, XMP vb. hiçbir zaman taşınmaz.
// Ayrıştırıcılar dosyadaki ofset / uzunluk alanlarına güvenmez: her okuma sınır denetimli, her döngü sayaçlıdır.

const TYPE_SIZE = [0, 1, 1, 2, 4, 8, 1, 1, 2, 4, 8, 4, 8]
const TAG_COPYRIGHT = 0x8298
const TAG_EXIF_IFD = 0x8769
const TAG_GPS_IFD = 0x8825
const TAG_DATETIME = 0x0132
const TAG_DATETIME_ORIGINAL = 0x9003
const TAG_DATETIME_DIGITIZED = 0x9004
const MAX_IFD_ENTRIES = 256
const MAX_GPS_ENTRIES = 64
const MAX_GPS_VALUE = 512
const MAX_TEXT = 1024

function ascii(b, from, len) {
  let s = ''
  const end = Math.min(b.length, from + len)
  for (let i = from; i < end; i++) s += String.fromCharCode(b[i])
  return s
}
const u16be = (b, o) => (b[o] << 8) | b[o + 1]
const u32be = (b, o) => b[o] * 16777216 + (b[o + 1] << 16) + (b[o + 2] << 8) + b[o + 3]
const u32le = (b, o) => b[o] + (b[o + 1] << 8) + (b[o + 2] << 16) + b[o + 3] * 16777216

function decodeUtf8(bytes) {
  try { return new TextDecoder('utf-8').decode(bytes) } catch { return ascii(bytes, 0, bytes.length) }
}

/** Bir IFD'nin girdileri (sınır denetimli; değerler `{ tag, type, count, at, size }` — `at` mutlak veri konumu). */
function readIfd(b, t, end, le, off) {
  const u16 = (o) => (le ? b[o] | (b[o + 1] << 8) : u16be(b, o))
  const u32 = (o) => (le ? u32le(b, o) : u32be(b, o))
  const ifd = t + off
  const out = []
  if (!(off >= 8) || ifd + 2 > end) return out
  const n = Math.min(u16(ifd), MAX_IFD_ENTRIES)
  for (let k = 0; k < n; k++) {
    const e = ifd + 2 + k * 12
    if (e + 12 > end) break
    const type = u16(e + 2)
    const count = u32(e + 4)
    if (type < 1 || type > 12 || count > 0x100000) continue
    const size = TYPE_SIZE[type] * count
    const at = size <= 4 ? e + 8 : t + u32(e + 8)
    if (at < t || at + size > end) continue
    out.push({ tag: u16(e), type, count, at, size })
  }
  return out
}

function asciiValue(b, entry, cap) {
  if (entry.type !== 2 && entry.type !== 7 && entry.type !== 1) return null
  let len = Math.min(entry.size, cap)
  for (let i = 0; i < len; i++) if (b[entry.at + i] === 0) { len = i; break }
  while (len > 0 && b[entry.at + len - 1] === 0x20) len -= 1
  return len > 0 ? b.slice(entry.at, entry.at + len) : null
}

/**
 * TIFF / EXIF bloğundan üç tür: telif (ham baytlar), oluşturma zamanı (metin), GPS girdileri (değerler küçük-sonlu).
 * @param {Uint8Array} b @param {number} t TIFF başlığının konumu @param {number} limit bloğun sonu (hariç)
 */
export function parseExif(b, t = 0, limit = b.length) {
  const out = { copyrightBytes: null, creation: null, gps: null }
  const end = Math.min(b.length, limit)
  if (t < 0 || t + 8 > end) return out
  const le = b[t] === 0x49 && b[t + 1] === 0x49
  if (!le && !(b[t] === 0x4d && b[t + 1] === 0x4d)) return out
  const u32 = (o) => (le ? u32le(b, o) : u32be(b, o))
  const u16 = (o) => (le ? b[o] | (b[o + 1] << 8) : u16be(b, o))
  if (u16(t + 2) !== 42) return out
  const ifd0 = readIfd(b, t, end, le, u32(t + 4))
  let exifOff = 0
  let gpsOff = 0
  let dateTime = null
  for (const e of ifd0) {
    if (e.tag === TAG_COPYRIGHT) out.copyrightBytes = asciiValue(b, e, MAX_TEXT)
    else if (e.tag === TAG_EXIF_IFD && e.type === 4 && e.count === 1) exifOff = u32(e.at)
    else if (e.tag === TAG_GPS_IFD && e.type === 4 && e.count === 1) gpsOff = u32(e.at)
    else if (e.tag === TAG_DATETIME) dateTime = asciiValue(b, e, 32)
  }
  if (exifOff) {
    let original = null
    let digitized = null
    for (const e of readIfd(b, t, end, le, exifOff)) {
      if (e.tag === TAG_DATETIME_ORIGINAL) original = asciiValue(b, e, 32)
      else if (e.tag === TAG_DATETIME_DIGITIZED) digitized = asciiValue(b, e, 32)
    }
    dateTime = original || digitized || dateTime
  }
  if (dateTime) out.creation = ascii(dateTime, 0, dateTime.length)
  if (gpsOff) {
    const entries = []
    for (const e of readIfd(b, t, end, le, gpsOff)) {
      if (entries.length >= MAX_GPS_ENTRIES || e.size > MAX_GPS_VALUE || e.size === 0) continue
      const value = b.slice(e.at, e.at + e.size)
      // Değeri küçük-sonluya çevir (eleman boyu: RATIONAL iki 4 baytlık sayıdır)
      const unit = e.type === 5 || e.type === 10 ? 4 : TYPE_SIZE[e.type]
      if (!le && unit > 1) {
        for (let o = 0; o + unit <= value.length; o += unit) value.subarray(o, o + unit).reverse()
      }
      entries.push({ tag: e.tag, type: e.type, count: e.count, value })
    }
    // Enlem (2) ve boylam (4) yoksa konum sayılmaz
    if (entries.some((e) => e.tag === 2) && entries.some((e) => e.tag === 4)) {
      out.gps = entries.sort((x, y) => x.tag - y.tag)
    }
  }
  return out
}

const pad2 = (n) => n + (n & 1)

/**
 * En küçük EXIF (TIFF, küçük-sonlu) bloğu: yalnız verilen alanlar. Hiçbiri yoksa null.
 * @param {{ copyrightBytes?: Uint8Array|null, creation?: string|null, gps?: object[]|null }} fields
 */
export function buildExif(fields) {
  const copyright = fields.copyrightBytes && fields.copyrightBytes.length ? fields.copyrightBytes : null
  const creation = fields.creation ? toExifDate(fields.creation) : null
  const gps = fields.gps && fields.gps.length ? fields.gps : null
  if (!copyright && !creation && !gps) return null
  const bytesOf = (s) => { const o = new Uint8Array(s.length + 1); for (let i = 0; i < s.length; i++) o[i] = s.charCodeAt(i) & 0x7f; return o }
  const withNul = (u) => { const o = new Uint8Array(u.length + 1); o.set(u); return o }
  const ifd0 = []
  if (copyright) ifd0.push({ tag: TAG_COPYRIGHT, type: 2, value: withNul(copyright) })
  if (creation) ifd0.push({ tag: TAG_EXIF_IFD, type: 4, pointer: 'exif' })
  if (gps) ifd0.push({ tag: TAG_GPS_IFD, type: 4, pointer: 'gps' })
  const exif = creation ? [{ tag: TAG_DATETIME_ORIGINAL, type: 2, value: bytesOf(creation) }] : []
  const gpsIfd = gps ? gps.map((e) => ({ tag: e.tag, type: e.type, count: e.count, value: e.value })) : []
  const sizeOf = (entries) => 6 + entries.length * 12 + entries.reduce((n, e) => n + (e.value && e.value.length > 4 ? pad2(e.value.length) : 0), 0)
  const offsets = { ifd0: 8 }
  offsets.exif = offsets.ifd0 + sizeOf(ifd0)
  offsets.gps = offsets.exif + (exif.length ? sizeOf(exif) : 0)
  const total = offsets.gps + (gpsIfd.length ? sizeOf(gpsIfd) : 0)
  const out = new Uint8Array(total)
  const w16 = (o, v) => { out[o] = v & 255; out[o + 1] = (v >> 8) & 255 }
  const w32 = (o, v) => { out[o] = v & 255; out[o + 1] = (v >>> 8) & 255; out[o + 2] = (v >>> 16) & 255; out[o + 3] = (v >>> 24) & 255 }
  out[0] = 0x49; out[1] = 0x49
  w16(2, 42)
  w32(4, 8)
  const write = (entries, base) => {
    w16(base, entries.length)
    let data = base + 2 + entries.length * 12 + 4
    entries.forEach((e, k) => {
      const o = base + 2 + k * 12
      w16(o, e.tag)
      w16(o + 2, e.type)
      if (e.pointer) { w32(o + 4, 1); w32(o + 8, offsets[e.pointer]); return }
      const unit = TYPE_SIZE[e.type]
      w32(o + 4, e.count ?? Math.floor(e.value.length / unit))
      if (e.value.length <= 4) out.set(e.value, o + 8)
      else { w32(o + 8, data); out.set(e.value, data); data += pad2(e.value.length) }
    })
    // sonraki IFD ofseti 0 (dizi zaten sıfır)
  }
  write(ifd0, offsets.ifd0)
  if (exif.length) write(exif, offsets.exif)
  if (gpsIfd.length) write(gpsIfd, offsets.gps)
  return out
}

const isDigit = (c) => c >= 48 && c <= 57

/** 'YYYY:MM:DD HH:MM:SS' ya da 'YYYY-MM-DDTHH:MM:SS…' → EXIF biçimi; tanınmazsa null. */
export function toExifDate(text) {
  const s = String(text || '')
  if (s.length < 19) return null
  for (const i of [0, 1, 2, 3, 5, 6, 8, 9, 11, 12, 14, 15, 17, 18]) if (!isDigit(s.charCodeAt(i))) return null
  const sep = (i, allowed) => allowed.includes(s[i])
  if (!sep(4, ':-') || !sep(7, ':-') || !sep(10, ' T') || !sep(13, ':') || !sep(16, ':')) return null
  return `${s.slice(0, 4)}:${s.slice(5, 7)}:${s.slice(8, 10)} ${s.slice(11, 13)}:${s.slice(14, 16)}:${s.slice(17, 19)}`
}

/** EXIF tarihi → ISO 8601 (yerel, bölgesiz); tanınmazsa metin olduğu gibi. */
export function toIsoDate(text) {
  const e = toExifDate(text)
  return e ? `${e.slice(0, 4)}-${e.slice(5, 7)}-${e.slice(8, 10)}T${e.slice(11)}` : String(text || '')
}

/** JPEG'de EXIF APP1 bloğunun TIFF aralığı ({ t, end }) ya da null; işaretçi yürüyüşü sayaçlı. */
export function findJpegExif(b) {
  if (b.length < 4 || b[0] !== 0xff || b[1] !== 0xd8) return null
  let i = 2
  for (let guard = 0; guard < 2000 && i + 4 <= b.length; guard++) {
    if (b[i] !== 0xff) return null
    const m = b[i + 1]
    if (m === 0xff) { i += 1; continue }
    if (m === 0xd8 || m === 0x01 || (m >= 0xd0 && m <= 0xd7)) { i += 2; continue }
    if (m === 0xda || m === 0xd9) return null
    const len = u16be(b, i + 2)
    if (len < 2) return null
    if (m === 0xe1 && ascii(b, i + 4, 6) === 'Exif\u0000\u0000') return { t: i + 10, end: Math.min(b.length, i + 2 + len) }
    i += 2 + len
  }
  return null
}

/** PNG: Copyright / Creation Time metin parçaları + eXIf parçası (TIFF aralığı). */
export function readPngMeta(b) {
  const out = { copyright: null, creation: null, exif: null }
  let o = 8
  for (let guard = 0; guard < 8192 && o + 12 <= b.length; guard++) {
    const len = u32be(b, o)
    const type = ascii(b, o + 4, 4)
    const data = o + 8
    if (len > b.length || data + len + 4 > b.length) break
    if (type === 'IEND') break
    if (type === 'eXIf') out.exif = { t: data, end: data + len }
    if (type === 'tEXt' || type === 'iTXt') {
      let k = 0
      while (k < len && k < 80 && b[data + k] !== 0) k += 1
      const keyword = ascii(b, data, k).toLowerCase()
      if (keyword === 'copyright' || keyword === 'creation time') {
        let from = data + k + 1
        let text = null
        if (type === 'tEXt') {
          text = ascii(b, from, Math.min(MAX_TEXT, data + len - from))
        } else if (from + 2 <= data + len && b[from] === 0) {
          // iTXt: sıkıştırma bayrağı 0 (sıkıştırılmış metin okunmaz), dil etiketi ve çevrilmiş anahtar atlanır
          from += 2
          for (let z = 0; z < 2; z++) {
            let guard2 = 0
            while (from < data + len && b[from] !== 0 && guard2++ < 256) from += 1
            from += 1
          }
          if (from <= data + len) text = decodeUtf8(b.subarray(from, Math.min(data + len, from + MAX_TEXT)))
        }
        if (text != null && text.trim()) {
          if (keyword === 'copyright') out.copyright = text.trim()
          else out.creation = text.trim()
        }
      }
    }
    o = data + len + 4
  }
  return out
}

/** WebP: EXIF parçasının TIFF aralığı ya da null. */
export function findWebpExif(b) {
  if (b.length < 20 || ascii(b, 0, 4) !== 'RIFF' || ascii(b, 8, 4) !== 'WEBP') return null
  let o = 12
  for (let guard = 0; guard < 4096 && o + 8 <= b.length; guard++) {
    const size = u32le(b, o + 4)
    const data = o + 8
    if (size > b.length || data + size > b.length) return null
    if (ascii(b, o, 4) === 'EXIF') {
      const skip = ascii(b, data, 6) === 'Exif\u0000\u0000' ? 6 : 0
      return { t: data + skip, end: data + size }
    }
    o = data + size + (size & 1)
  }
  return null
}

/**
 * Kaynağın korunabilir üstverisi. @returns {{ copyrightBytes: Uint8Array|null, copyright: string|null,
 *   creation: string|null, gps: object[]|null, present: { copyright: boolean, creation: boolean, location: boolean } }}
 */
export function readMetadata(bytes, type) {
  const out = { copyrightBytes: null, copyright: null, creation: null, gps: null }
  try {
    let range = null
    if (type === 'jpeg') range = findJpegExif(bytes)
    else if (type === 'webp') range = findWebpExif(bytes)
    else if (type === 'png') {
      const png = readPngMeta(bytes)
      range = png.exif
      out.copyright = png.copyright
      out.creation = png.creation
    }
    if (range) {
      const exif = parseExif(bytes, range.t, range.end)
      if (exif.copyrightBytes && !out.copyright) { out.copyrightBytes = exif.copyrightBytes; out.copyright = decodeUtf8(exif.copyrightBytes) }
      if (exif.creation && !out.creation) out.creation = exif.creation
      out.gps = exif.gps
    }
  } catch {
    // Bozuk üstveri: yokmuş gibi davranılır (görselin işlenmesini engellemez)
  }
  return { ...out, present: { copyright: !!out.copyright, creation: !!out.creation, location: !!out.gps } }
}

/** JPEG'e EXIF APP1 ekler (SOI'den, varsa JFIF APP0'dan hemen sonra). Blok çok büyükse dosya olduğu gibi döner. */
export function insertJpegExif(jpeg, tiff) {
  if (!tiff || tiff.length + 8 > 65535 || jpeg.length < 4 || jpeg[0] !== 0xff || jpeg[1] !== 0xd8) return jpeg
  let at = 2
  if (jpeg[2] === 0xff && jpeg[3] === 0xe0 && jpeg.length >= 6) {
    const len = u16be(jpeg, 4)
    if (len >= 2 && 4 + len <= jpeg.length) at = 4 + len
  }
  const seg = new Uint8Array(4 + 6 + tiff.length)
  seg[0] = 0xff; seg[1] = 0xe1
  const len = 2 + 6 + tiff.length
  seg[2] = (len >> 8) & 255; seg[3] = len & 255
  seg.set([0x45, 0x78, 0x69, 0x66, 0, 0], 4)
  seg.set(tiff, 10)
  const out = new Uint8Array(jpeg.length + seg.length)
  out.set(jpeg.subarray(0, at), 0)
  out.set(seg, at)
  out.set(jpeg.subarray(at), at + seg.length)
  return out
}

/**
 * WebP'ye EXIF parçası ekler. Yalın dosya (VP8 / VP8L) genişletilmiş biçime (VP8X) sarılır; VP8X dosyada bayrak
 * açılır. Tanınmayan dosya olduğu gibi döner (null).
 */
export function insertWebpExif(webp, tiff) {
  if (!tiff || webp.length < 30 || ascii(webp, 0, 4) !== 'RIFF' || ascii(webp, 8, 4) !== 'WEBP') return null
  const fmt = ascii(webp, 12, 4)
  const exifChunk = new Uint8Array(8 + pad2(tiff.length))
  exifChunk.set([0x45, 0x58, 0x49, 0x46], 0)
  exifChunk[4] = tiff.length & 255; exifChunk[5] = (tiff.length >>> 8) & 255
  exifChunk[6] = (tiff.length >>> 16) & 255; exifChunk[7] = (tiff.length >>> 24) & 255
  exifChunk.set(tiff, 8)
  let head
  let body = webp.slice(12)
  if (body.length & 1) {                                      // son parçanın dolgu baytı eksikse tamamla
    const padded = new Uint8Array(body.length + 1)
    padded.set(body)
    body = padded
  }
  if (fmt === 'VP8X') {
    head = new Uint8Array(0)
    body[8] |= 0x08                                           // VP8X bayrakları: EXIF
  } else {
    let width
    let height
    let alpha = false
    if (fmt === 'VP8 ' && webp[23] === 0x9d && webp[24] === 0x01 && webp[25] === 0x2a) {
      width = (webp[26] | (webp[27] << 8)) & 0x3fff
      height = (webp[28] | (webp[29] << 8)) & 0x3fff
    } else if (fmt === 'VP8L' && webp[20] === 0x2f) {
      width = 1 + (((webp[22] & 0x3f) << 8) | webp[21])
      height = 1 + (((webp[24] & 0x0f) << 10) | (webp[23] << 2) | ((webp[22] & 0xc0) >> 6))
      alpha = (webp[24] & 0x10) !== 0
    } else {
      return null
    }
    if (!(width >= 1 && height >= 1)) return null
    head = new Uint8Array(18)
    head.set([0x56, 0x50, 0x38, 0x58, 10, 0, 0, 0], 0)        // 'VP8X', boy 10
    head[8] = 0x08 | (alpha ? 0x10 : 0)
    const w = width - 1
    const h = height - 1
    head[12] = w & 255; head[13] = (w >> 8) & 255; head[14] = (w >> 16) & 255
    head[15] = h & 255; head[16] = (h >> 8) & 255; head[17] = (h >> 16) & 255
  }
  const total = 12 + head.length + body.length + exifChunk.length
  const out = new Uint8Array(total)
  out.set(webp.subarray(0, 12), 0)
  const riff = total - 8
  out[4] = riff & 255; out[5] = (riff >>> 8) & 255; out[6] = (riff >>> 16) & 255; out[7] = (riff >>> 24) & 255
  out.set(head, 12)
  out.set(body, 12 + head.length)
  out.set(exifChunk, 12 + head.length + body.length)
  return out
}

/**
 * Çıktıya yazılacak üstveri planı (saf karar). @param meta readMetadata çıktısı @param preserve seçenek
 * @param {'jpeg'|'png'|'webp'} outType
 * @returns {{ exif: Uint8Array|null, text: {keyword:string,text:string}[], kept: string[], dropped: string[] }}
 *   `kept` / `dropped`: istenen VE kaynakta bulunan türlerden yazılan / yazılamayan.
 */
export function planMetadata(meta, preserve, outType) {
  const want = ['copyright', 'creation', 'location'].filter((k) => preserve?.[k] && meta?.present?.[k])
  const plan = { exif: null, text: [], kept: [], dropped: [] }
  if (!want.length) return plan
  if (outType === 'png') {
    for (const k of want) {
      if (k === 'copyright') { plan.text.push({ keyword: 'Copyright', text: meta.copyright }); plan.kept.push(k) }
      else if (k === 'creation') { plan.text.push({ keyword: 'Creation Time', text: toIsoDate(meta.creation) }); plan.kept.push(k) }
      else plan.dropped.push(k)                              // PNG'de konum taşınmaz
    }
    return plan
  }
  const fields = {}
  for (const k of want) {
    if (k === 'copyright') {
      fields.copyrightBytes = meta.copyrightBytes || new TextEncoder().encode(meta.copyright).slice(0, MAX_TEXT)
      plan.kept.push(k)
    } else if (k === 'creation') {
      if (toExifDate(meta.creation)) { fields.creation = meta.creation; plan.kept.push(k) } else plan.dropped.push(k)
    } else {
      fields.gps = meta.gps
      plan.kept.push(k)
    }
  }
  plan.exif = buildExif(fields)
  if (!plan.exif) { plan.dropped.push(...plan.kept); plan.kept = [] }
  return plan
}
