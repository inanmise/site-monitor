/**
 * Görsel hazırlama (2026-10-15, Faz C). Yüklenen görsel, PDF'te çizileceği yuva boyutuna göre TARAYICIDA küçültülür ve
 * gerekirse PNG / JPEG'e çevrilir — sunucu yalnız PNG / JPEG, ≤ 5 MB, ≤ 6000 px, ≤ 12 MP kabul eder ve rapor başına toplam
 * 40 MB'tır; e-posta eki 15 MB'ı aşarsa rapor gönderilemez. Büyük ekran görüntüleri bu yüzden PDF'te görünen ölçünün
 * {@link PRINT_SCALE} katına indirilir (baskıda keskin, dosyada küçük). Küçültme gerekmeyen PNG / JPEG hiç yeniden
 * kodlanmaz (kalite kaybı yok) — EXIF yönü yan olan JPEG hariç: sunucu ve PDF yönü bilmez, görsel düz kaydedilir.
 *
 * Saf hesaplar ({@link slotBox}, {@link targetSize}, {@link sniffKind}, {@link headerSize}, {@link jpegOrientation})
 * jsdom'da sınanır; çizim / kodlama tarayıcı API'leri (createImageBitmap, canvas.toBlob) {@link prepareImage}'ın `deps`
 * parametresiyle değiştirilebilir.
 */

// PDF yerleşimi — TechErrorReportPdfWriter / PdfCanvas ile AYNI (imagePrep.test.js Java kaynağından okur)
export const PDF_LAYOUT = Object.freeze({ pageW: 595.27563, margin: 36, gap: 8, halfMaxH: 250, fullMaxH: 330 })
/** PDF'te görünen ölçünün kaç katı piksel tutulur (≈ 216 dpi). */
export const PRINT_SCALE = 3
/** Bu kadar büyük olmadıkça küçültülmez (gereksiz yeniden kodlama yok). */
const RESIZE_SLACK = 1.1
// Sunucu doğrulayıcısı — TechErrorImageValidator ile AYNI (test okur)
export const SERVER_IMAGE = Object.freeze({ maxBytes: 5 * 1024 * 1024, maxSide: 6000, maxPixels: 12_000_000, minSide: 16 })
/** Tarayıcıda açılmadan önceki dosya sınırı (çok büyük dosya belleği tüketmesin). */
export const MAX_INPUT_BYTES = 40 * 1024 * 1024
/**
 * Tarayıcıda açılabilecek en büyük görsel (başlıktan okunur, AÇMADAN önce): 48 MP telefon fotoğrafı geçer; küçük ama
 * dev ölçülü (ör. 20000 × 20000) sıkıştırılmış bir PNG sekmeyi çökertmez.
 */
export const MAX_DECODE = Object.freeze({ pixels: 60_000_000, side: 30_000 })
/** PNG bu boyutu aşarsa (fotoğraf benzeri) JPEG denenir; daha küçükse JPEG alınır. */
const PNG_TO_JPEG_BYTES = 1.5 * 1024 * 1024
/** Tür + başlık ölçüsü + EXIF yönü için okunan baş (JPEG'de SOF büyük APP bölümlerinin ardında olabilir). */
const HEAD_BYTES = 256 * 1024

/** Yuvanın PDF'teki kutusu (pt): yarım = bir kolon, tam = iki kolon. */
export function slotBox(width) {
  const contentW = PDF_LAYOUT.pageW - PDF_LAYOUT.margin * 2
  if (width === 'full') return { w: contentW, h: PDF_LAYOUT.fullMaxH }
  return { w: (contentW - PDF_LAYOUT.gap * 2) / 2, h: PDF_LAYOUT.halfMaxH }
}

/**
 * Hedef piksel ölçüsü: görsel PDF'te kutuya en-boy oranı korunarak sığdırılır (imageRow ile aynı kural), görünen ölçünün
 * {@code scale} katı tutulur; sunucu sınırları (kenar / piksel) da aşılmaz. Hiçbir zaman büyütülmez. Kısa kenar sunucu
 * alt sınırının ({@code minSide}) altına inmez (inceleme 2026-10-15: 1920 × 40 şerit yarım yuvada 760 × 15 olup
 * reddediliyordu); bu sınır sunucu üst sınırıyla çelişirse (aşırı ince görsel) sonuç kısa kenarı küçük kalır — çağıran
 * bunu ayrıca söyler.
 * @returns {{ width: number, height: number, resize: boolean }}
 */
export function targetSize(natW, natH, width, scale = PRINT_SCALE) {
  const w0 = Math.max(1, Math.round(natW))
  const h0 = Math.max(1, Math.round(natH))
  const box = slotBox(width)
  let dw = box.w
  let dh = box.w * h0 / w0
  if (dh > box.h) { dh = box.h; dw = box.h * w0 / h0 }
  // Görünen ölçü × ölçek; sunucu sınırları
  let f = Math.min(1, (dw * scale) / w0, (dh * scale) / h0)
  const capped = Math.min(1, SERVER_IMAGE.maxSide / w0, SERVER_IMAGE.maxSide / h0, Math.sqrt(SERVER_IMAGE.maxPixels / (w0 * h0)))
  const mustShrink = capped < 1
  // Yalnız gerçekten büyükse (ya da sunucu sınırı aşılıyorsa) küçült
  if (!mustShrink && f > 1 / RESIZE_SLACK) return { width: w0, height: h0, resize: false }
  f = Math.min(f, capped)
  const minF = Math.min(1, SERVER_IMAGE.minSide / Math.min(w0, h0))
  if (f < minF) f = Math.min(minF, capped)
  // Taban ölçeği paya geri ittiyse (ör. 2000 × 17 şerit) yeniden örneklenmez — kaynak zaten sunucu sınırlarında
  if (f >= 1 || (!mustShrink && f > 1 / RESIZE_SLACK)) return { width: w0, height: h0, resize: false }
  // +1e-9: kayan nokta 16 / 37 × 37'yi 15,999… yapıp kısa kenarı alt sınırın bir piksel altına düşürmesin
  const side = (n) => Math.max(1, Math.floor(n * f + 1e-9))
  return { width: side(w0), height: side(h0), resize: true }
}

const HEIF_BRANDS = new Set(['heic', 'heix', 'hevc', 'hevx', 'heim', 'heis', 'mif1', 'msf1'])
const BMP_DIB_SIZES = new Set([12, 40, 52, 56, 64, 108, 124])

function asciiOf(b, from, len) {
  let s = ''
  for (let i = from; i < from + len && i < b.length; i++) s += String.fromCharCode(b[i])
  return s
}

/**
 * Dosya türü İLK baytlardan (uzantı / MIME yanıltabilir). Dönüş: png | jpeg | gif | webp | bmp | avif (tarayıcı açar,
 * PNG / JPEG'e çevrilir) ya da heic | pdf | svg | tiff (açılamaz — ne yapılacağını söyleyen hata) ya da unknown.
 */
export function sniffKind(bytes) {
  const b = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes || [])
  const at = (i) => (i < b.length ? b[i] : -1)
  const ascii = (from, len) => asciiOf(b, from, len)
  if (at(0) === 0x89 && at(1) === 0x50 && at(2) === 0x4e && at(3) === 0x47) return 'png'
  if (at(0) === 0xff && at(1) === 0xd8 && at(2) === 0xff) return 'jpeg'
  if (ascii(0, 4) === 'GIF8') return 'gif'
  if (ascii(0, 4) === 'RIFF' && ascii(8, 4) === 'WEBP') return 'webp'
  // BMP: "BM" + bilinen DIB başlık boyu (yalnız "BM" ile başlayan metin BMP sayılmasın)
  if (at(0) === 0x42 && at(1) === 0x4d && BMP_DIB_SIZES.has(at(14) + at(15) * 256 + at(16) * 65536 + at(17) * 16777216)) return 'bmp'
  if (ascii(0, 4) === '%PDF') return 'pdf'
  if ((at(0) === 0x49 && at(1) === 0x49 && at(2) === 0x2a && at(3) === 0x00)
    || (at(0) === 0x4d && at(1) === 0x4d && at(2) === 0x00 && at(3) === 0x2a)) return 'tiff'
  if (ascii(4, 4) === 'ftyp') {
    // Ana marka + uyumlu markalar (AVIF'in ana markası "mif1" olabilir: önce AVIF aranır)
    const boxLen = Math.min(Math.max(16, (at(0) << 24 >>> 0) + (at(1) << 16) + (at(2) << 8) + at(3)), 512, b.length)
    const brands = [ascii(8, 4)]
    for (let o = 16; o + 4 <= boxLen; o += 4) brands.push(ascii(o, 4))
    if (brands.includes('avif') || brands.includes('avis')) return 'avif'
    if (brands.some((x) => HEIF_BRANDS.has(x))) return 'heic'
  }
  // SVG: metin; UTF-8 BOM, boşluk, XML bildirimi, yorum ve DOCTYPE'tan sonra <svg (en çok ilk 512 bayt)
  const start = at(0) === 0xef && at(1) === 0xbb && at(2) === 0xbf ? 3 : 0
  const head = ascii(start, Math.min(b.length, 512)).trimStart().toLowerCase()
  if (head.startsWith('<') && head.includes('<svg') && !head.includes('<html')) return 'svg'
  return 'unknown'
}

/**
 * Başlıktan piksel ölçüsü — görsel AÇILMADAN (PNG IHDR, GIF, BMP, WebP VP8 / VP8L / VP8X, JPEG SOF). Okunamazsa null
 * (ör. AVIF ya da SOF baştaki baytların dışında): o zaman açıp bakılır.
 * @returns {{ width: number, height: number } | null}
 */
export function headerSize(bytes, kind) {
  const b = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes || [])
  const u16be = (o) => (b[o] << 8) | b[o + 1]
  const u16le = (o) => b[o] | (b[o + 1] << 8)
  const u32be = (o) => b[o] * 16777216 + (b[o + 1] << 16) + (b[o + 2] << 8) + b[o + 3]
  const u32le = (o) => b[o] + (b[o + 1] << 8) + (b[o + 2] << 16) + b[o + 3] * 16777216
  const ok = (w, h) => (w > 0 && h > 0 ? { width: w, height: h } : null)
  if (kind === 'png' && b.length >= 24) return ok(u32be(16), u32be(20))
  if (kind === 'gif' && b.length >= 10) return ok(u16le(6), u16le(8))
  if (kind === 'bmp' && b.length >= 22 && u32le(14) === 12) return ok(u16le(18), u16le(20))   // OS/2 çekirdek başlığı: 16 bit
  if (kind === 'bmp' && b.length >= 26) {
    const h = u32le(22)
    return ok(u32le(18), h > 0x7fffffff ? 0x100000000 - h : h)      // eksi yükseklik = yukarıdan aşağı
  }
  if (kind === 'webp' && b.length >= 30) {
    const fmt = asciiOf(b, 12, 4)
    // Çerçeve imzası doğrulanmadan ölçü okunmaz (bozuk dosyada çöp bayt "dev görsel" sanılmasın)
    if (fmt === 'VP8 ') return b[23] === 0x9d && b[24] === 0x01 && b[25] === 0x2a ? ok(u16le(26) & 0x3fff, u16le(28) & 0x3fff) : null
    if (fmt === 'VP8L') return b[20] !== 0x2f ? null : ok(1 + (((b[22] & 0x3f) << 8) | b[21]), 1 + (((b[24] & 0x0f) << 10) | (b[23] << 2) | ((b[22] & 0xc0) >> 6)))
    if (fmt === 'VP8X') return ok(1 + (b[24] | (b[25] << 8) | (b[26] << 16)), 1 + (b[27] | (b[28] << 8) | (b[29] << 16)))
    return null
  }
  if (kind === 'jpeg') {
    let i = 2
    for (let guard = 0; guard < 256 && i + 9 < b.length; guard++) {
      if (b[i] !== 0xff) return null
      const m = b[i + 1]
      if (m === 0xff) { i += 1; continue }                              // dolgu
      if (m === 0xd8 || m === 0x01 || (m >= 0xd0 && m <= 0xd7)) { i += 2; continue }
      if (m === 0xda || m === 0xd9) return null                         // görüntü verisi: başlık bitti
      const len = u16be(i + 2)
      if (len < 2) return null
      // SOF0–SOF15 (DHT C4, JPG C8, DAC CC hariç)
      if (m >= 0xc0 && m <= 0xcf && m !== 0xc4 && m !== 0xc8 && m !== 0xcc) return ok(u16be(i + 7), u16be(i + 5))
      i += 2 + len
    }
  }
  return null
}

/**
 * JPEG'in EXIF yönü (1–8; yoksa / okunamazsa 1). 1 dışındaki bir değerde görsel yeniden kodlanır: sunucu ve PDF yönü
 * bilmez, telefon fotoğrafı PDF'te yan basılırdı (inceleme 2026-10-15).
 */
export function jpegOrientation(bytes) {
  const b = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes || [])
  if (b[0] !== 0xff || b[1] !== 0xd8) return 1
  let i = 2
  for (let guard = 0; guard < 64 && i + 4 <= b.length; guard++) {
    if (b[i] !== 0xff) return 1
    const m = b[i + 1]
    if (m === 0xff) { i += 1; continue }
    if (m === 0xd8 || m === 0x01 || (m >= 0xd0 && m <= 0xd7)) { i += 2; continue }
    if (m === 0xda || m === 0xd9) return 1
    const len = (b[i + 2] << 8) | b[i + 3]
    if (len < 2) return 1
    if (m === 0xe1 && asciiOf(b, i + 4, 6) === 'Exif\u0000\u0000') {
      const t = i + 10                                                  // TIFF başlığı
      const le = b[t] === 0x49 && b[t + 1] === 0x49
      if (!le && !(b[t] === 0x4d && b[t + 1] === 0x4d)) return 1
      const u16 = (o) => (le ? b[o] | (b[o + 1] << 8) : (b[o] << 8) | b[o + 1])
      const u32 = (o) => (le ? b[o] + (b[o + 1] << 8) + (b[o + 2] << 16) + b[o + 3] * 16777216
        : b[o] * 16777216 + (b[o + 1] << 16) + (b[o + 2] << 8) + b[o + 3])
      const ifd = t + u32(t + 4)
      if (ifd + 2 > b.length) return 1
      const n = Math.min(u16(ifd), 256)
      for (let k = 0; k < n; k++) {
        const e = ifd + 2 + k * 12
        if (e + 12 > b.length) return 1
        if (u16(e) === 0x0112) {
          const v = u16(e + 8)
          return v >= 1 && v <= 8 ? v : 1
        }
      }
      return 1
    }
    i += 2 + len
  }
  return 1
}

const DECODABLE = new Set(['png', 'jpeg', 'gif', 'webp', 'bmp', 'avif'])
const KIND_ERROR = { heic: 'te.img.errHeic', pdf: 'te.img.errPdf', svg: 'te.img.errSvg', tiff: 'te.img.errTiff' }
const KIND_LABEL = { png: 'PNG', jpeg: 'JPEG', gif: 'GIF', webp: 'WebP', bmp: 'BMP', avif: 'AVIF' }

/** Hazırlama hatası: {@code key} i18n anahtarı, {@code params} yer tutucular. */
export class ImagePrepError extends Error {
  constructor(key, params = []) {
    super(key)
    this.key = key
    this.params = params
  }
}

/** Kullanılan canvas belleğini hemen bırak (iOS Safari'de toplam canvas belleği sınırlı). */
function release(canvas) {
  try { if (canvas) { canvas.width = 0; canvas.height = 0 } } catch { /* yok */ }
}

function context(canvas) {
  const cx = canvas.getContext('2d')
  // Bellek dolunca tarayıcı bağlam vermez: "dosya bozuk" değil, "bu tarayıcıda işlenemedi"
  if (!cx) throw new ImagePrepError('te.img.errBrowser')
  return cx
}

/** Varsayılan tarayıcı uygulaması: aç → ölçekle (bir kez) → kodla (gerekirse birkaç biçimde) → bırak. */
export const browserDeps = {
  async decode(file) {
    // EXIF yönü uygulanır (telefon fotoğrafı yan yatmasın). Seçeneği tanımayan eski tarayıcı (iOS 15, Chrome < 112)
    // TypeError atar: seçeneksiz açılır — bu tarayıcıların varsayılanı da yönü uygular.
    try {
      return await createImageBitmap(file, { imageOrientation: 'from-image' })
    } catch (e) {
      if (e instanceof TypeError) return createImageBitmap(file)
      throw e
    }
  },
  async scale(bitmap, width, height) {
    let src = bitmap
    let sw = bitmap.width
    let sh = bitmap.height
    // Çok büyük küçültmede yarıya yarıya inilir (tek adımda kaba örnekleme metni bulanıklaştırır)
    while (sw / 2 >= width && sh / 2 >= height) {
      const c = document.createElement('canvas')
      c.width = Math.max(1, Math.floor(sw / 2))
      c.height = Math.max(1, Math.floor(sh / 2))
      const cx = context(c)
      cx.imageSmoothingQuality = 'high'
      cx.drawImage(src, 0, 0, c.width, c.height)
      if (src !== bitmap) release(src)
      src = c
      sw = c.width
      sh = c.height
    }
    const canvas = document.createElement('canvas')
    canvas.width = width
    canvas.height = height
    const ctx = context(canvas)
    ctx.imageSmoothingQuality = 'high'
    ctx.drawImage(src, 0, 0, width, height)
    if (src !== bitmap) release(src)
    return canvas
  },
  async encode(canvas, type, quality) {
    let target = canvas
    if (type === 'image/jpeg') {
      // JPEG saydamlık taşımaz: saydam PNG siyah zemine düşmesin
      target = document.createElement('canvas')
      target.width = canvas.width
      target.height = canvas.height
      const cx = context(target)
      cx.fillStyle = '#ffffff'
      cx.fillRect(0, 0, target.width, target.height)
      cx.drawImage(canvas, 0, 0)
    }
    try {
      return await new Promise((resolve, reject) => {
        target.toBlob((blob) => (blob ? resolve(blob) : reject(new ImagePrepError('te.img.errBrowser'))), type, quality)
      })
    } finally {
      if (target !== canvas) release(target)
    }
  },
  release,
}

function renamed(name, ext) {
  const base = String(name || 'gorsel').replace(/\.[^./\\]{1,8}$/, '')
  return `${base || 'gorsel'}.${ext}`
}

/**
 * Görseli yuvası için hazırlar. Dönüş `{ file, info }`; `info` kullanıcıya gösterilecek özet:
 * `{ resized, converted, rotated, fromKind, toKind, fromW, fromH, toW, toH, fromBytes, toBytes }`.
 * Hata: {@link ImagePrepError} (i18n anahtarı + değerler).
 */
export async function prepareImage(file, slotWidth, deps = browserDeps) {
  if (!file || !file.size) throw new ImagePrepError('te.img.errEmpty')
  if (file.size > MAX_INPUT_BYTES) throw new ImagePrepError('te.img.errTooBig', [file.size, MAX_INPUT_BYTES])
  let head
  try {
    head = new Uint8Array(await file.slice(0, HEAD_BYTES).arrayBuffer())
  } catch {
    // Seçildikten sonra değişen / erişimi kalkan dosya (Android içerik adresi): bellek sorunu değil
    throw new ImagePrepError('te.img.errRead')
  }
  const kind = sniffKind(head)
  if (KIND_ERROR[kind]) throw new ImagePrepError(KIND_ERROR[kind])
  if (!DECODABLE.has(kind)) throw new ImagePrepError('te.img.errUnknownType', [file.name || ''])
  // Ölçü başlıktan, AÇMADAN: dev ölçülü görsel sekmeyi çökertmesin
  const dims = headerSize(head, kind)
  if (dims && (dims.width * dims.height > MAX_DECODE.pixels || Math.max(dims.width, dims.height) > MAX_DECODE.side)) {
    throw new ImagePrepError('te.img.errHuge', [`${dims.width} × ${dims.height}`])
  }
  const orientation = kind === 'jpeg' ? jpegOrientation(head) : 1
  // Ölçüsü başlıktan bilinen, yuvaya sığan, yönü düz PNG / JPEG: tarayıcı görseli AÇAMIYORSA (açma API'si yok —
  // ReferenceError / TypeError) olduğu gibi gider. Açabiliyorsa yine açılır: yarım inmiş / bozuk dosya burada yakalanır
  // (sunucu yalnız başlığı okur, piksel çözmez — inceleme 2026-10-15).
  const fitsAsIs = !!dims && (kind === 'png' || kind === 'jpeg') && orientation === 1 && file.size <= SERVER_IMAGE.maxBytes
    && dims.width >= SERVER_IMAGE.minSide && dims.height >= SERVER_IMAGE.minSide && !targetSize(dims.width, dims.height, slotWidth).resize
  if (dims && (dims.width < SERVER_IMAGE.minSide || dims.height < SERVER_IMAGE.minSide)) {
    throw new ImagePrepError('te.img.errTooSmall', [`${dims.width} × ${dims.height}`])
  }
  let bitmap
  try {
    bitmap = await deps.decode(file)
  } catch (e) {
    if (fitsAsIs && (e instanceof ReferenceError || e instanceof TypeError)) {
      return { file, info: { fromKind: KIND_LABEL[kind], fromW: dims.width, fromH: dims.height, fromBytes: file.size, rotated: false,
        resized: false, converted: false, toKind: KIND_LABEL[kind], toW: dims.width, toH: dims.height, toBytes: file.size } }
    }
    throw new ImagePrepError('te.img.errDecode')
  }
  let scaled = null
  try {
    const natW = bitmap.width
    const natH = bitmap.height
    if (!natW || !natH) throw new ImagePrepError('te.img.errDecode')
    if (natW < SERVER_IMAGE.minSide || natH < SERVER_IMAGE.minSide) throw new ImagePrepError('te.img.errTooSmall', [`${natW} × ${natH}`])
    const target = targetSize(natW, natH, slotWidth)
    // Aşırı ince görsel: sunucu üst sınırına inince kısa kenar alt sınırın altında kalıyor
    if (Math.min(target.width, target.height) < SERVER_IMAGE.minSide) {
      throw new ImagePrepError('te.img.errThin', [`${natW} × ${natH}`])
    }
    const rotated = orientation !== 1
    const keep = (kind === 'png' || kind === 'jpeg') && !target.resize && !rotated && file.size <= SERVER_IMAGE.maxBytes
    const base = { fromKind: KIND_LABEL[kind], fromW: natW, fromH: natH, fromBytes: file.size, rotated }
    if (keep) {
      return { file, info: { ...base, resized: false, converted: false, toKind: KIND_LABEL[kind], toW: natW, toH: natH, toBytes: file.size } }
    }
    // Ölçekleme BİR kez; kodlama: JPEG kaynak JPEG kalır, diğerleri (ekran görüntüsü, grafik — keskin metin) PNG. Büyük
    // PNG JPEG'e döner.
    scaled = await deps.scale(bitmap, target.width, target.height)
    let type = kind === 'jpeg' ? 'image/jpeg' : 'image/png'
    let blob = await deps.encode(scaled, type, 0.9)
    if (type === 'image/png' && blob.size > PNG_TO_JPEG_BYTES) {
      const jpeg = await deps.encode(scaled, 'image/jpeg', 0.9)
      if (jpeg.size < blob.size * 0.7) { blob = jpeg; type = 'image/jpeg' }
    }
    if (blob.size > SERVER_IMAGE.maxBytes) {
      const smaller = await deps.encode(scaled, 'image/jpeg', 0.8)
      if (smaller.size < blob.size) { blob = smaller; type = 'image/jpeg' }
    }
    if (blob.size > SERVER_IMAGE.maxBytes) throw new ImagePrepError('te.img.errStillLarge', [blob.size, SERVER_IMAGE.maxBytes])
    const ext = type === 'image/jpeg' ? 'jpg' : 'png'
    const out = new File([blob], renamed(file.name, ext), { type })
    const toKind = type === 'image/jpeg' ? 'JPEG' : 'PNG'
    return {
      file: out,
      info: { ...base, resized: target.resize, converted: toKind !== KIND_LABEL[kind], toKind,
        toW: target.width, toH: target.height, toBytes: blob.size },
    }
  } finally {
    try { if (scaled) deps.release?.(scaled) } catch { /* yok */ }
    try { bitmap?.close?.() } catch { /* yok */ }
  }
}
