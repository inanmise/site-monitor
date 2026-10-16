/**
 * Görsel hazırlama (2026-10-15, Faz C; 2026-10-16 kullanıcı isteği: "çözünürlüğe etki etmeyelim, rakamlar / yazılar
 * okunur kalsın"). Görsel TAM ÇÖZÜNÜRLÜKTE saklanır: sunucu sınırları (PNG / JPEG, ≤ 5 MB, ≤ 6000 px kenar, ≤ 12 MP)
 * içindeki PNG / JPEG hiç yeniden kodlanmaz (aynı dosya gider). Yalnız sınırı aşan görsel küçültülür — ALAN ORTALAMASIYLA
 * ({@link areaResample}): her yeni piksel kapladığı kaynak alanın tam ağırlıklı ortalaması; metinde titreme / Lanczos
 * halesi yok, tarayıcıdan bağımsız. Ekran görüntüsü ve grafik (PNG / GIF / BMP kaynak) HİÇBİR ZAMAN JPEG'e çevrilmez
 * (JPEG yazının çevresini bozar); bayt sınırı aşılırsa ölçü küçültülür. JPEG kaynak (fotoğraf) JPEG kalır (kalite 0,95);
 * EXIF yönü yan olan JPEG düz kaydedilir (sunucu ve PDF yönü bilmez). Eskiden görsel PDF yuvasının ölçüsüne (yarım
 * yuvada 760 px) indiriliyordu — ekranda yuvayı dolduran görsel büyütülüp bulanıklaşıyordu.
 *
 * Saf hesaplar ({@link slotBox}, {@link targetSize}, {@link sniffKind}, {@link headerSize}, {@link jpegOrientation})
 * jsdom'da sınanır; çizim / kodlama tarayıcı API'leri (createImageBitmap, canvas.toBlob) {@link prepareImage}'ın `deps`
 * parametresiyle değiştirilebilir.
 */

// PDF yerleşimi — TechErrorReportPdfWriter / PdfCanvas ile AYNI (imagePrep.test.js Java kaynağından okur)
export const PDF_LAYOUT = Object.freeze({ pageW: 595.27563, margin: 36, gap: 8, imageMaxH: 640 })
/** Yeniden kodlanan JPEG'in kalitesi (yalnız JPEG kaynak — fotoğraf; ekran görüntüsü PNG kalır). */
export const JPEG_QUALITY = 0.95
/** Bayt sınırı için ölçü küçültme denemesi en çok (her adım bir öncekinin sonucuna göre). */
const MAX_SHRINK_STEPS = 5
// Sunucu doğrulayıcısı — TechErrorImageValidator ile AYNI (test okur)
export const SERVER_IMAGE = Object.freeze({ maxBytes: 5 * 1024 * 1024, maxSide: 6000, maxPixels: 12_000_000, minSide: 16 })
/** Tarayıcıda açılmadan önceki dosya sınırı (çok büyük dosya belleği tüketmesin). */
export const MAX_INPUT_BYTES = 40 * 1024 * 1024
/**
 * Tarayıcıda açılabilecek en büyük görsel (başlıktan okunur, AÇMADAN önce): 48 MP telefon fotoğrafı geçer; küçük ama
 * dev ölçülü (ör. 20000 × 20000) sıkıştırılmış bir PNG sekmeyi çökertmez.
 */
export const MAX_DECODE = Object.freeze({ pixels: 60_000_000, side: 30_000 })
/** Tür + başlık ölçüsü + EXIF yönü için okunan baş (JPEG'de SOF büyük APP bölümlerinin ardında olabilir). */
const HEAD_BYTES = 256 * 1024

/**
 * Görselin yuvadaki genişliği (%) — PDF ile aynı kural (TechErrorReportPdfWriter.fit): yuvanın genişliği, oran korunur;
 * yalnız {@code imageMaxH}'yi aşacak kadar uzun (dikey) görsel orantılı daralır ve ortalanır.
 */
export function displayWidthPct(width, w, h) {
  if (!(w > 0) || !(h > 0)) return 100
  const box = slotBox(width)
  return Math.min(100, (box.h / box.w) * (w / h) * 100)
}

/**
 * Yuvanın PDF'teki kutusu (pt): yarım = bir kolon, tam = iki kolon. Görsel kutunun GENİŞLİĞİNİ doldurur (2026-10-16);
 * yükseklik yalnız sayfaya sığmayan uzun görseli sınırlar.
 */
export function slotBox(width) {
  const contentW = PDF_LAYOUT.pageW - PDF_LAYOUT.margin * 2
  if (width === 'full') return { w: contentW, h: PDF_LAYOUT.imageMaxH }
  return { w: (contentW - PDF_LAYOUT.gap * 2) / 2, h: PDF_LAYOUT.imageMaxH }
}

/**
 * Hedef piksel ölçüsü (2026-10-16): görsel OLDUĞU GİBİ kalır; yalnız sunucu sınırı (6000 px kenar, 12 MP) aşılıyorsa
 * en-boy oranı korunarak sınıra iner. Hiçbir zaman büyütülmez; yuvaya göre küçültme YOK (çözünürlük korunur). Sınıra
 * inince kısa kenarı alt sınırın ({@code minSide}) altında kalan aşırı ince görseli çağıran ayrıca söyler.
 * @returns {{ width: number, height: number, resize: boolean }}
 */
export function targetSize(natW, natH) {
  const w0 = Math.max(1, Math.round(natW))
  const h0 = Math.max(1, Math.round(natH))
  const f = Math.min(1, SERVER_IMAGE.maxSide / w0, SERVER_IMAGE.maxSide / h0, Math.sqrt(SERVER_IMAGE.maxPixels / (w0 * h0)))
  if (f >= 1) return { width: w0, height: h0, resize: false }
  // +1e-9: kayan nokta 6000 / 7200 × 7200'ü 5999,99… yapıp bir piksel kaybettirmesin (taban yine sınırın içinde)
  const side = (n) => Math.max(1, Math.floor(n * f + 1e-9))
  return { width: side(w0), height: side(h0), resize: true }
}

/**
 * ALAN ORTALAMASIYLA küçültme (kesirli kapsamalı kutu süzgeci): her hedef pikseli, kaynakta kapladığı dikdörtgendeki
 * piksellerin kapsama oranıyla ağırlıklı ortalamasıdır. Küçültmede metin / grafik için en temiz yöntem: örtüşme
 * (aliasing, titreyen ince çizgi) yok, Lanczos / bikübiğin harf çevresindeki açık-koyu halesi yok, tam katta (2:1, 3:1)
 * birebir ortalama; tarayıcının çizim kalitesine bağlı değil. Ağırlıklar TAMSAYI (kaynak satır / sütun hedef ölçüsü
 * biriminde): kaynak sütun c = [c·dstW, (c+1)·dstW), hedef sütun x = [x·srcW, (x+1)·srcW) — bir kaynak sütunu en çok iki
 * hedef sütunu kapsar (srcW ≥ dstW). Ayrılabilir (önce yatay, sonra dikey) ve SATIR SATIR akar: bellek O(dstW); kaynak
 * {@code readRow(y)} ile okunur (RGBA, srcW × 4; Promise de dönebilir — tarayıcı şerit okurken ana iş parçacığını
 * bırakır, 48 MP fotoğrafta sayfa donmaz), sonuç {@code writeRow(y, row)} ile yazılır. Saydamlık ÖNÇARPIMLI ortalanır
 * (saydam kenarda koyu saçak olmaz). Yalnız küçültür (dstW ≤ srcW, dstH ≤ srcH). Java eşi: {@code ImageAreaAverage}.
 */
export async function areaResample(srcW, srcH, dstW, dstH, readRow, writeRow) {
  if (!(dstW >= 1 && dstH >= 1 && dstW <= srcW && dstH <= srcH)) throw new RangeError('areaResample yalnız küçültür')
  // Yatay eşleme: kaynak sütunun hedefi ve ilk hedefe düşen ağırlığı (kalanı sonrakine)
  const hd = new Int32Array(srcW)
  const hw = new Float64Array(srcW)
  for (let c = 0; c < srcW; c++) {
    const start = c * dstW
    const d = Math.floor(start / srcW)
    hd[c] = d
    hw[c] = Math.min(start + dstW, (d + 1) * srcW) - start
  }
  const norm = srcW * srcH
  const rowH = new Float64Array(dstW * 4)
  let acc = new Float64Array(dstW * 4)
  let next = new Float64Array(dstW * 4)
  const out = new Uint8ClampedArray(dstW * 4)
  let dRow = 0
  for (let r = 0; r < srcH; r++) {
    let src = readRow(r)
    if (src && typeof src.then === 'function') src = await src
    rowH.fill(0)
    for (let c = 0; c < srcW; c++) {
      const o = c * 4
      const a = src[o + 3]
      const k = a / 255
      const pr = src[o] * k
      const pg = src[o + 1] * k
      const pb = src[o + 2] * k
      const w1 = hw[c]
      const w2 = dstW - w1
      const i = hd[c] * 4
      rowH[i] += pr * w1; rowH[i + 1] += pg * w1; rowH[i + 2] += pb * w1; rowH[i + 3] += a * w1
      if (w2 > 0) {
        rowH[i + 4] += pr * w2; rowH[i + 5] += pg * w2; rowH[i + 6] += pb * w2; rowH[i + 7] += a * w2
      }
    }
    const start = r * dstH
    const bound = (dRow + 1) * srcH
    const v1 = Math.min(start + dstH, bound) - start
    const v2 = dstH - v1
    for (let i = 0; i < rowH.length; i++) acc[i] += rowH[i] * v1
    if (v2 > 0) for (let i = 0; i < rowH.length; i++) next[i] += rowH[i] * v2
    if (start + dstH >= bound) {
      for (let x = 0; x < dstW; x++) {
        const i = x * 4
        const a = acc[i + 3] / norm
        if (a <= 0) {
          out[i] = 0; out[i + 1] = 0; out[i + 2] = 0; out[i + 3] = 0
        } else {
          const un = 255 / a
          out[i] = Math.round((acc[i] / norm) * un)
          out[i + 1] = Math.round((acc[i + 1] / norm) * un)
          out[i + 2] = Math.round((acc[i + 2] / norm) * un)
          out[i + 3] = Math.round(a)
        }
      }
      writeRow(dRow, out)
      const t = acc; acc = next; next = t
      next.fill(0)
      dRow += 1
    }
  }
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

/**
 * Ana iş parçacığını kısa bırakır (sayfa çizilsin, "Hazırlanıyor…" dönsün). setTimeout DEĞİL MessageChannel: gizli sekmede
 * tarayıcılar zamanlayıcıları saniyelere / dakikalara kısar, mesaj görevlerini kısmaz — kullanıcı bir sonraki ekran
 * görüntüsü için raporlama aracının sekmesine geçince hazırlama durmasın (inceleme 2026-10-16).
 */
function yieldToUi() {
  return new Promise((resolve) => {
    if (typeof MessageChannel === 'function') {
      const ch = new MessageChannel()
      ch.port1.onmessage = () => { ch.port1.close(); resolve() }
      ch.port2.postMessage(0)
    } else {
      setTimeout(resolve, 0)
    }
  })
}

/** Kullanılan canvas belleğini hemen bırak (iOS Safari'de toplam canvas belleği sınırlı). */
function release(canvas) {
  try { if (canvas) { canvas.width = 0; canvas.height = 0 } } catch { /* yok */ }
}

function context(canvas, options) {
  const cx = options ? canvas.getContext('2d', options) : canvas.getContext('2d')
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
    const sw = bitmap.width
    const sh = bitmap.height
    const canvas = document.createElement('canvas')
    canvas.width = width
    canvas.height = height
    const ctx = context(canvas)
    if (width === sw && height === sh) {
      // Ölçü aynı (biçim çevirme / yön düzeltme): piksel piksel kopya, yeniden örnekleme yok
      ctx.imageSmoothingEnabled = false
      ctx.drawImage(bitmap, 0, 0)
      return canvas
    }
    // Kaynak ŞERİT ŞERİT okunur (48 MP fotoğrafın tamamı ikinci kez belleğe alınmaz); her yeni şeritte ana iş parçacığı
    // bırakılır (sayfa donmaz, "Hazırlanıyor…" döner); alan ortalaması satır satır yazar
    const STRIP = 64
    const strip = document.createElement('canvas')
    strip.width = sw
    strip.height = Math.min(STRIP, sh)
    let cache = null
    let cacheY = 0
    let cacheN = 0
    try {
      const sx = context(strip, { willReadFrequently: true })
      sx.imageSmoothingEnabled = false
      const load = (r) => {
        cacheY = r
        cacheN = Math.min(STRIP, sh - r)
        sx.clearRect(0, 0, sw, strip.height)
        sx.drawImage(bitmap, 0, r, sw, cacheN, 0, 0, sw, cacheN)
        cache = sx.getImageData(0, 0, sw, cacheN).data
      }
      const row = (r) => {
        const o = (r - cacheY) * sw * 4
        return cache.subarray(o, o + sw * 4)
      }
      const readRow = (r) => {
        if (cache && r >= cacheY && r < cacheY + cacheN) return row(r)
        return yieldToUi().then(() => { load(r); return row(r) })
      }
      const out = ctx.createImageData(width, height)
      await areaResample(sw, sh, width, height, readRow, (y, r) => out.data.set(r, y * width * 4))
      ctx.putImageData(out, 0, 0)
    } catch (e) {
      release(canvas)
      throw e
    } finally {
      release(strip)
    }
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

/** Kayıpsız kaynak (ekran görüntüsü, grafik): hiçbir zaman JPEG'e çevrilmez. */
const LOSSLESS_KINDS = new Set(['png', 'gif', 'bmp'])

/**
 * Görseli yükleme için hazırlar (yuvadan bağımsız — çözünürlük korunur). Dönüş `{ file, info }`; `info` kullanıcıya
 * gösterilecek özet: `{ resized, converted, rotated, fromKind, toKind, fromW, fromH, toW, toH, fromBytes, toBytes }`.
 * Hata: {@link ImagePrepError} (i18n anahtarı + değerler).
 */
export async function prepareImage(file, deps = browserDeps) {
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
    && dims.width >= SERVER_IMAGE.minSide && dims.height >= SERVER_IMAGE.minSide && !targetSize(dims.width, dims.height).resize
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
    const target = targetSize(natW, natH)
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
    // Kodlama: JPEG kaynak (fotoğraf) JPEG 0,95; diğerleri KAYIPSIZ PNG. WebP / AVIF fotoğrafın PNG'si bayt sınırını
    // aşarsa JPEG denenir; ekran görüntüsü / grafik (PNG, GIF, BMP) hiçbir zaman JPEG olmaz — bayt sınırı aşılırsa ÖLÇÜ
    // alan ortalamasıyla küçültülür (bir sonraki adım bir öncekinin bayt oranına göre; en çok MAX_SHRINK_STEPS).
    let w = target.width
    let h = target.height
    let blob = null
    let type = kind === 'jpeg' ? 'image/jpeg' : 'image/png'
    for (let step = 0; ; step++) {
      scaled = await deps.scale(bitmap, w, h)
      type = kind === 'jpeg' ? 'image/jpeg' : 'image/png'
      blob = await deps.encode(scaled, type, kind === 'jpeg' ? JPEG_QUALITY : undefined)
      if (blob.size > SERVER_IMAGE.maxBytes && type === 'image/png' && !LOSSLESS_KINDS.has(kind)) {
        const jpeg = await deps.encode(scaled, 'image/jpeg', JPEG_QUALITY)
        if (jpeg.size < blob.size) { blob = jpeg; type = 'image/jpeg' }
      }
      if (blob.size <= SERVER_IMAGE.maxBytes) break
      const f = Math.sqrt(SERVER_IMAGE.maxBytes / blob.size) * 0.95
      const nw = Math.floor(w * f)
      const nh = Math.floor(h * f)
      if (step + 1 >= MAX_SHRINK_STEPS || Math.min(nw, nh) < SERVER_IMAGE.minSide) {
        throw new ImagePrepError('te.img.errStillLarge', [blob.size, SERVER_IMAGE.maxBytes])
      }
      deps.release?.(scaled)
      scaled = null
      w = nw
      h = nh
    }
    const ext = type === 'image/jpeg' ? 'jpg' : 'png'
    const out = new File([blob], renamed(file.name, ext), { type })
    const toKind = type === 'image/jpeg' ? 'JPEG' : 'PNG'
    return {
      file: out,
      info: { ...base, resized: w !== natW || h !== natH, converted: toKind !== KIND_LABEL[kind], toKind,
        toW: w, toH: h, toBytes: blob.size },
    }
  } finally {
    try { if (scaled) deps.release?.(scaled) } catch { /* yok */ }
    try { bitmap?.close?.() } catch { /* yok */ }
  }
}
