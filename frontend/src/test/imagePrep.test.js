import { describe, it, expect, vi } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import {
  ImagePrepError, MAX_DECODE, MAX_INPUT_BYTES, PDF_LAYOUT, PRINT_SCALE, SERVER_IMAGE, browserDeps, headerSize, jpegOrientation,
  prepareImage, slotBox, sniffKind, targetSize,
} from '../components/techerror/imagePrep.js'

// Görsel hazırlama (2026-10-15, Faz C): PDF yerleşimi ve sunucu sınırları Java kaynağından okunur (iki taraf ayrışırsa
// kırmızı); tür ilk baytlardan; akış sahte tarayıcı API'leriyle (jsdom'da canvas yok).
const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../..')
const read = (rel) => fs.readFileSync(path.join(ROOT, rel), 'utf8')
const WRITER = read('backend/src/main/java/com/sitemonitor/service/report/TechErrorReportPdfWriter.java')
const CANVAS = read('backend/src/main/java/com/sitemonitor/service/report/PdfCanvas.java')
const VALIDATOR = read('backend/src/main/java/com/sitemonitor/service/techerror/TechErrorImageValidator.java')
const num = (src, name) => Number(new RegExp(`${name}\\s*=\\s*([0-9_.]+)L?f?;`).exec(src)[1].replace(/_/g, ''))

describe('Java ile aynı ölçüler', () => {
  it('PDF yuvası (kenar boşluğu, aralık, yarım / tam en yüksek) ve sunucu sınırları', () => {
    expect(PDF_LAYOUT.margin).toBe(num(CANVAS, 'MARGIN'))
    expect(CANVAS).toContain('PAGE = PDRectangle.A4')
    expect(PDF_LAYOUT.gap).toBe(num(WRITER, 'GAP'))
    expect(PDF_LAYOUT.halfMaxH).toBe(num(WRITER, 'HALF_MAX_H'))
    expect(PDF_LAYOUT.fullMaxH).toBe(num(WRITER, 'FULL_MAX_H'))
    expect(SERVER_IMAGE.maxSide).toBe(num(VALIDATOR, 'MAX_SIDE'))
    expect(SERVER_IMAGE.maxPixels).toBe(num(VALIDATOR, 'MAX_PIXELS'))
    expect(SERVER_IMAGE.minSide).toBe(num(VALIDATOR, 'MIN_SIDE'))
    expect(VALIDATOR).toContain('MAX_BYTES = 5L * 1024 * 1024')
    expect(SERVER_IMAGE.maxBytes).toBe(5 * 1024 * 1024)
    // Writer'daki yerleşim formülü: yarım hücre (CONTENT_W - GAP * 2) / 2
    expect(WRITER).toContain('(CONTENT_W - GAP * 2) / 2')
  })
})

describe('targetSize', () => {
  it('geniş ekran görüntüsü tam yuvada genişliğe sığar, ölçeğin üstü küçülür; oran korunur', () => {
    const box = slotBox('full')
    const t = targetSize(3840, 1080, 'full')
    expect(t.resize).toBe(true)
    expect(t.width).toBe(Math.floor(3840 * Math.min(1, (box.w * PRINT_SCALE) / 3840)))
    expect(Math.abs(t.width / t.height - 3840 / 1080)).toBeLessThan(0.01)
  })

  it('uzun görsel yükseklik sınırına göre küçülür; yarım yuva daha küçük hedef', () => {
    const half = targetSize(1000, 4000, 'half')
    expect(half.height).toBeLessThanOrEqual(Math.ceil(PDF_LAYOUT.halfMaxH * PRINT_SCALE))
    expect(half.resize).toBe(true)
    expect(targetSize(1600, 900, 'half').width).toBeLessThan(targetSize(1600, 900, 'full').width + 1)
  })

  it('zaten küçük görsel küçültülmez (büyütülmez de); sınıra yakın olan da (%10 pay)', () => {
    expect(targetSize(400, 300, 'full')).toEqual({ width: 400, height: 300, resize: false })
    const box = slotBox('full')
    const w = Math.round(box.w * PRINT_SCALE * 1.05)
    expect(targetSize(w, Math.round(w / 3), 'full').resize).toBe(false)
  })

  it('sunucu sınırı (6000 px kenar, 12 MP) her zaman uygulanır', () => {
    const t = targetSize(8000, 100, 'full', 1000)                // ölçek büyük olsa bile
    expect(t.width).toBeLessThanOrEqual(SERVER_IMAGE.maxSide)
    expect(t.resize).toBe(true)
    const p = targetSize(5000, 5000, 'full', 1000)
    expect(p.width * p.height).toBeLessThanOrEqual(SERVER_IMAGE.maxPixels)
  })

  it('kısa kenar sunucu alt sınırının (16 px) altına inmez — uzun ince şerit reddedilmesin (inceleme 2026-10-15)', () => {
    const strip = targetSize(1920, 40, 'half')
    expect(strip.height).toBe(SERVER_IMAGE.minSide)
    expect(strip.resize).toBe(true)
    expect(strip.width).toBe(Math.floor(1920 * (16 / 40) + 1e-9))
    const tall = targetSize(400, 20000, 'half')
    expect(tall.width).toBeGreaterThanOrEqual(SERVER_IMAGE.minSide)
    expect(Math.max(tall.width, tall.height)).toBeLessThanOrEqual(SERVER_IMAGE.maxSide)
    // Kayan nokta 16/37 × 37 = 15,99… yuvarlaması kısa kenarı 15'e düşürmez
    expect(targetSize(4000, 37, 'half').height).toBe(16)
    // Taban ölçeği paya geri ittiyse yeniden örneklenmez (2000 × 17: taban 16/17 ≈ 0,94 > 1/1,1)
    expect(targetSize(2000, 17, 'half')).toEqual({ width: 2000, height: 17, resize: false })
    // Alt sınır sunucu üst sınırıyla çelişirse (aşırı ince) kısa kenar küçük kalır — çağıran söyler
    expect(Math.min(...Object.values(targetSize(30000, 20, 'full')).slice(0, 2))).toBeLessThan(SERVER_IMAGE.minSide)
  })
})

describe('sniffKind', () => {
  const bytes = (...xs) => new Uint8Array(xs)
  const text = (s) => new TextEncoder().encode(s)
  it('türü uzantıdan değil ilk baytlardan bilir', () => {
    expect(sniffKind(bytes(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a))).toBe('png')
    expect(sniffKind(bytes(0xff, 0xd8, 0xff, 0xe0))).toBe('jpeg')
    expect(sniffKind(text('GIF89a'))).toBe('gif')
    expect(sniffKind(text('RIFF\u0000\u0000\u0000\u0000WEBPVP8 '))).toBe('webp')
    expect(sniffKind(bmpHead(800, 600))).toBe('bmp')
    expect(sniffKind(text('\u0000\u0000\u0000\u0018ftypheic'))).toBe('heic')
    expect(sniffKind(text('\u0000\u0000\u0000\u0018ftypmif1'))).toBe('heic')
    expect(sniffKind(text('\u0000\u0000\u0000\u001cftypavif'))).toBe('avif')
    // AVIF'in ana markası mif1 olabilir: uyumlu markalarda avif varsa AVIF'tir (HEIC mesajı verilmez)
    expect(sniffKind(text('\u0000\u0000\u0000\u0020ftypmif1\u0000\u0000\u0000\u0000mif1miafavif'))).toBe('avif')
    expect(sniffKind(text('\u0000\u0000\u0000\u0018ftypmif1\u0000\u0000\u0000\u0000heic'))).toBe('heic')
    // Yalnız "BM" ile başlayan metin BMP değildir
    expect(sniffKind(text('BM raporu notları ve açıklamalar'))).toBe('unknown')
    // UTF-8 BOM'lu ya da yorumla başlayan SVG de SVG'dir
    expect(sniffKind(new Uint8Array([0xef, 0xbb, 0xbf, ...text('<svg xmlns="x">')]))).toBe('svg')
    expect(sniffKind(text('<!-- Illustrator ile üretildi --><svg xmlns="x">'))).toBe('svg')
    expect(sniffKind(text('<!doctype html><html><body><svg>'))).toBe('unknown')
    expect(sniffKind(text('%PDF-1.7'))).toBe('pdf')
    expect(sniffKind(bytes(0x49, 0x49, 0x2a, 0x00))).toBe('tiff')
    expect(sniffKind(text('  <?xml version="1.0"?><svg xmlns="x">'))).toBe('svg')
    expect(sniffKind(text('merhaba'))).toBe('unknown')
    expect(sniffKind(new Uint8Array())).toBe('unknown')
  })
})

/** Sahte tarayıcı: aç → { width, height }, ölçekle → { w, h }, kodla → istenen boyutta Blob; `calls` = kodlamalar. */
function fakeDeps({ width, height, pngBytes = 200_000, jpegBytes = 150_000, decodeFails = false, scaleFails = null } = {}) {
  const calls = []
  const scales = []
  const released = []
  return {
    calls,
    scales,
    released,
    decoded: 0,
    async decode() {
      this.decoded += 1
      if (decodeFails) throw new Error('decode')
      return { width, height, close: vi.fn() }
    },
    async scale(_bm, w, h) {
      if (scaleFails) throw scaleFails
      const s = { w, h }
      scales.push(s)
      return s
    },
    async encode(s, type, q) {
      calls.push({ w: s.w, h: s.h, type, q })
      const size = type === 'image/png' ? pngBytes : jpegBytes
      return new Blob([new Uint8Array(size)], { type })
    },
    release(s) { released.push(s) },
  }
}
function bmpHead(w, h) {
  const b = new Uint8Array(54)
  b[0] = 0x42; b[1] = 0x4d; b[14] = 40
  b[18] = w & 0xff; b[19] = (w >> 8) & 0xff
  b[22] = h & 0xff; b[23] = (h >> 8) & 0xff
  return b
}
const PNG_HEAD = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]
const file = (head, size, name = 'grafik.png', type = 'image/png') => {
  const buf = new Uint8Array(size)
  buf.set(head.slice(0, size))
  return new File([buf], name, { type })
}

describe('prepareImage', () => {
  it('küçük PNG olduğu gibi gider (yeniden kodlanmaz)', async () => {
    const f = file(PNG_HEAD, 50_000)
    const deps = fakeDeps({ width: 800, height: 400 })
    const out = await prepareImage(f, 'full', deps)
    expect(out.file).toBe(f)
    expect(out.info).toMatchObject({ resized: false, converted: false })
    expect(deps.calls).toHaveLength(0)
  })

  it('büyük ekran görüntüsü yuvaya göre küçültülür, PNG kalır; özet eski / yeni ölçüyü söyler', async () => {
    const f = file(PNG_HEAD, 3_000_000, 'ekran.png')
    const deps = fakeDeps({ width: 3840, height: 2160 })
    const out = await prepareImage(f, 'half', deps)
    expect(deps.calls[0].type).toBe('image/png')
    expect(out.file.type).toBe('image/png')
    expect(out.file.name).toBe('ekran.png')
    expect(out.info).toMatchObject({ resized: true, converted: false, fromW: 3840, fromH: 2160, fromBytes: 3_000_000, toBytes: 200_000 })
    expect(out.info.toW).toBeLessThan(3840)
  })

  it('WebP / GIF / BMP PNG\'ye çevrilir; fotoğraf benzeri büyük PNG JPEG\'e döner (belirgin küçükse)', async () => {
    const webp = new File([new TextEncoder().encode('RIFF\u0000\u0000\u0000\u0000WEBPVP8 ' + ' '.repeat(100))], 'a.webp', { type: 'image/webp' })
    const out = await prepareImage(webp, 'full', fakeDeps({ width: 600, height: 400 }))
    expect(out.file.type).toBe('image/png')
    expect(out.file.name).toBe('a.png')
    expect(out.info).toMatchObject({ converted: true, fromKind: 'WebP', toKind: 'PNG' })
    const photo = await prepareImage(file(PNG_HEAD, 4_000_000), 'full',
      fakeDeps({ width: 4000, height: 3000, pngBytes: 2_500_000, jpegBytes: 600_000 }))
    expect(photo.file.type).toBe('image/jpeg')
    expect(photo.file.name).toBe('grafik.jpg')
  })

  it('net hatalar: HEIC / PDF / SVG / TIFF / tanınmayan / boş / çok büyük / açılamayan / çok küçük / küçültmeye rağmen büyük', async () => {
    const err = async (f, deps = fakeDeps({ width: 100, height: 100 })) => {
      try { await prepareImage(f, 'full', deps) } catch (e) { return e instanceof ImagePrepError ? e.key : 'other' }
      return null
    }
    const enc = (s) => new TextEncoder().encode(s)
    expect(await err(new File([enc('\u0000\u0000\u0000\u0018ftypheic....')], 'IMG_1.HEIC'))).toBe('te.img.errHeic')
    expect(await err(new File([enc('%PDF-1.7 ....')], 'rapor.pdf'))).toBe('te.img.errPdf')
    expect(await err(new File([enc('<svg xmlns="x"></svg>')], 'g.svg'))).toBe('te.img.errSvg')
    expect(await err(new File([new Uint8Array([0x4d, 0x4d, 0x00, 0x2a, 1, 2])], 'g.tif'))).toBe('te.img.errTiff')
    expect(await err(new File([enc('düz metin dosyası')], 'not.png', { type: 'image/png' }))).toBe('te.img.errUnknownType')
    expect(await err(new File([], 'bos.png'))).toBe('te.img.errEmpty')
    const big = { size: MAX_INPUT_BYTES + 1, name: 'dev.png', slice: () => new Blob([new Uint8Array(PNG_HEAD)]) }
    expect(await err(big)).toBe('te.img.errTooBig')
    expect(await err(file(PNG_HEAD, 1000), fakeDeps({ decodeFails: true }))).toBe('te.img.errDecode')
    expect(await err(file(PNG_HEAD, 1000), fakeDeps({ width: 10, height: 10 }))).toBe('te.img.errTooSmall')
    expect(await err(file(PNG_HEAD, 9_000_000), fakeDeps({ width: 5000, height: 5000, pngBytes: 9_000_000, jpegBytes: 7_000_000 })))
      .toBe('te.img.errStillLarge')
  })

  it('ölçekleme bir kez yapılır, birkaç kodlamada paylaşılır ve sonunda bırakılır', async () => {
    const deps = fakeDeps({ width: 4000, height: 3000, pngBytes: 6_000_000, jpegBytes: 6_500_000 })
    await expect(prepareImage(file(PNG_HEAD, 4_000_000), 'full', deps)).rejects.toMatchObject({ key: 'te.img.errStillLarge' })
    expect(deps.scales).toHaveLength(1)
    expect(deps.calls.length).toBeGreaterThan(1)
    expect(deps.released).toEqual(deps.scales)
  })

  it('başlıktaki dev ölçü AÇMADAN reddedilir; çizim / bellek hatası "bu tarayıcıda işlenemedi" der', async () => {
    const ihdr = (w, h) => {
      const b = new Uint8Array(64)
      b.set(PNG_HEAD)
      new DataView(b.buffer).setUint32(16, w)
      new DataView(b.buffer).setUint32(20, h)
      return new File([b], 'dev.png', { type: 'image/png' })
    }
    const deps = fakeDeps({ width: 100, height: 100 })
    await expect(prepareImage(ihdr(20_000, 20_000), 'full', deps)).rejects.toMatchObject({ key: 'te.img.errHuge', params: ['20000 × 20000'] })
    expect(deps.decoded).toBe(0)
    await expect(prepareImage(ihdr(MAX_DECODE.side + 1, 20), 'full', deps)).rejects.toMatchObject({ key: 'te.img.errHuge' })
    // 48 MP telefon fotoğrafı açılır
    const ok = fakeDeps({ width: 8000, height: 6000 })
    await expect(prepareImage(ihdr(8000, 6000), 'full', ok)).resolves.toBeTruthy()
    const broken = fakeDeps({ width: 4000, height: 3000, scaleFails: new ImagePrepError('te.img.errBrowser') })
    await expect(prepareImage(file(PNG_HEAD, 4_000_000), 'full', broken)).rejects.toMatchObject({ key: 'te.img.errBrowser' })
  })

  it('aşırı ince görsel (sunucu sınırına inince kısa kenarı 16 px altı) açık mesajla reddedilir', async () => {
    await expect(prepareImage(file(PNG_HEAD, 2000), 'full', fakeDeps({ width: 30000, height: 20 })))
      .rejects.toMatchObject({ key: 'te.img.errThin', params: ['30000 × 20'] })
  })

  it('EXIF yönü yan olan JPEG küçültme gerekmese de düz kaydedilir; yönü normal JPEG olduğu gibi gider', async () => {
    const exif = (orientation, le = true) => {
      // FFD8 | FFE1 len "Exif\0\0" | TIFF (II*\0 ya da MM\0*) IFD0 @8: 1 giriş 0x0112 SHORT = orientation
      const tiff = le
        ? [0x49, 0x49, 0x2a, 0x00, 8, 0, 0, 0, 1, 0, 0x12, 0x01, 3, 0, 1, 0, 0, 0, orientation, 0, 0, 0, 0, 0, 0, 0]
        : [0x4d, 0x4d, 0x00, 0x2a, 0, 0, 0, 8, 0, 1, 0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, orientation, 0, 0, 0, 0, 0, 0]
      const app1 = [...new TextEncoder().encode('Exif'), 0, 0, ...tiff]
      const len = app1.length + 2
      // SOF0: yükseklik 300, genişlik 400 (dosyada yatay; yön 6 ile dik görünür)
      return new Uint8Array([0xff, 0xd8, 0xff, 0xe1, len >> 8, len & 0xff, ...app1,
        0xff, 0xc0, 0, 11, 8, 0x01, 0x2c, 0x01, 0x90, 3, 0, 0, 0, 0xff, 0xda])
    }
    expect(jpegOrientation(exif(6))).toBe(6)
    expect(jpegOrientation(exif(8, false))).toBe(8)
    expect(jpegOrientation(exif(1))).toBe(1)
    expect(jpegOrientation(new Uint8Array([0xff, 0xd8, 0xff, 0xdb, 0, 4, 0, 0]))).toBe(1)
    expect(jpegOrientation(PNG_HEAD)).toBe(1)
    expect(headerSize(exif(6), 'jpeg')).toEqual({ width: 400, height: 300 })
    const pad = (head) => { const b = new Uint8Array(50_000); b.set(head); return b }
    // Tarayıcı yönü uygulayarak açar (300 × 400); yuvaya sığar, yine de düz kaydedilir
    const rotated = fakeDeps({ width: 300, height: 400 })
    const out = await prepareImage(new File([pad(exif(6))], 'foto.jpg', { type: 'image/jpeg' }), 'full', rotated)
    expect(out.file.type).toBe('image/jpeg')
    expect(out.info).toMatchObject({ rotated: true, resized: false, converted: false, toW: 300, toH: 400 })
    expect(rotated.calls).toEqual([{ w: 300, h: 400, type: 'image/jpeg', q: 0.9 }])
    const upright = fakeDeps({ width: 400, height: 300 })
    const f = new File([pad(exif(1))], 'duz.jpg', { type: 'image/jpeg' })
    expect((await prepareImage(f, 'full', upright)).file).toBe(f)
    expect(upright.calls).toHaveLength(0)
  })

  it('başlıktan bilinen ve sığan PNG yine AÇILARAK denetlenir (bozuk dosya yakalanır); açma API\'si yoksa olduğu gibi gider', async () => {
    const ihdr = (w, h, size = 64, name = 'grafik.png', type = 'image/png') => {
      const b = new Uint8Array(size)
      b.set(PNG_HEAD)
      new DataView(b.buffer).setUint32(16, w)
      new DataView(b.buffer).setUint32(20, h)
      return new File([b], name, { type })
    }
    const deps = fakeDeps({ width: 800, height: 400 })
    const f = ihdr(800, 400)
    const out = await prepareImage(f, 'full', deps)
    expect(out.file).toBe(f)
    expect(out.info).toMatchObject({ resized: false, fromW: 800, fromH: 400 })
    expect(deps.decoded).toBe(1)
    // Yarım inmiş PNG: başlık sağlam, tarayıcı açamıyor → "bozuk" (sunucu yalnız başlığı okur, yakalamazdı)
    const broken = { ...fakeDeps(), decode: async () => { throw new DOMException('kesik', 'InvalidStateError') } }
    await expect(prepareImage(ihdr(800, 400), 'full', broken)).rejects.toMatchObject({ key: 'te.img.errDecode' })
    // Açma API'si yok (ReferenceError) ya da seçeneği tanımıyor (TypeError): sığan PNG olduğu gibi gider
    for (const err of [new ReferenceError('createImageBitmap'), new TypeError('x')]) {
      const old = { ...fakeDeps(), decode: async () => { throw err } }
      const g = ihdr(800, 400)
      expect((await prepareImage(g, 'full', old)).file).toBe(g)
    }
    // ... ama küçültme / çevirme gerekiyorsa API'siz yapılamaz → açılamadı
    const old = { ...fakeDeps(), decode: async () => { throw new ReferenceError('createImageBitmap') } }
    await expect(prepareImage(ihdr(3840, 2160), 'half', old)).rejects.toMatchObject({ key: 'te.img.errDecode' })
    await expect(prepareImage(ihdr(800, 400, 6 * 1024 * 1024), 'full', old)).rejects.toMatchObject({ key: 'te.img.errDecode' })
    // Başlıktaki küçük ölçü açmadan reddedilir
    const d2 = fakeDeps({ width: 10, height: 400 })
    await expect(prepareImage(ihdr(10, 400), 'full', d2)).rejects.toMatchObject({ key: 'te.img.errTooSmall', params: ['10 × 400'] })
    expect(d2.decoded).toBe(0)
  })

  it('geçerli başlıklı 6 MB PNG yeniden kodlanır; geçerli başlıklı GIF / BMP PNG\'ye çevrilir (olduğu gibi gitmez)', async () => {
    const big = new Uint8Array(6 * 1024 * 1024)
    big.set(PNG_HEAD)
    new DataView(big.buffer).setUint32(16, 900)
    new DataView(big.buffer).setUint32(20, 600)
    const d1 = fakeDeps({ width: 900, height: 600, pngBytes: 400_000 })
    const o1 = await prepareImage(new File([big], 'buyuk.png', { type: 'image/png' }), 'full', d1)
    expect(o1.file.size).toBe(400_000)
    expect(d1.calls).toHaveLength(1)
    const gif = new Uint8Array(64)
    gif.set([0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 0x20, 0x03, 0x58, 0x02])
    const o2 = await prepareImage(new File([gif], 'a.gif', { type: 'image/gif' }), 'full', fakeDeps({ width: 800, height: 600 }))
    expect(o2.file.type).toBe('image/png')
    expect(o2.info).toMatchObject({ converted: true, fromKind: 'GIF' })
    const bmp = new Uint8Array(64)
    bmp.set(bmpHead(800, 600))
    const o3 = await prepareImage(new File([bmp], 'a.bmp', { type: 'image/bmp' }), 'full', fakeDeps({ width: 800, height: 600 }))
    expect(o3.info).toMatchObject({ converted: true, fromKind: 'BMP', toKind: 'PNG' })
  })

  it('eski tarayıcı imageOrientation seçeneğini tanımıyorsa seçeneksiz açar; dosya okunamazsa "yeniden seçin" der', async () => {
    const had = 'createImageBitmap' in globalThis
    const prev = globalThis.createImageBitmap
    const seen = []
    globalThis.createImageBitmap = vi.fn(async (_f, opts) => {
      seen.push(opts)
      if (opts) throw new TypeError('imageOrientation')
      return { width: 10, height: 10 }
    })
    try {
      await expect(browserDeps.decode(new Blob(['x']))).resolves.toEqual({ width: 10, height: 10 })
      expect(seen).toEqual([{ imageOrientation: 'from-image' }, undefined])
      globalThis.createImageBitmap = vi.fn(async () => { throw new DOMException('bozuk', 'InvalidStateError') })
      await expect(browserDeps.decode(new Blob(['x']))).rejects.toBeInstanceOf(DOMException)
    } finally {
      if (had) globalThis.createImageBitmap = prev
      else delete globalThis.createImageBitmap
    }
    const unreadable = { size: 100, name: 'a.png', slice: () => ({ arrayBuffer: () => Promise.reject(new DOMException('x', 'NotReadableError')) }) }
    await expect(prepareImage(unreadable, 'full', fakeDeps({ width: 10, height: 10 }))).rejects.toMatchObject({ key: 'te.img.errRead' })
  })

  it('başlık ölçüsü: PNG / GIF / BMP / WebP; okunamazsa null', () => {
    // OS/2 çekirdek BMP başlığı (DIB 12 bayt): ölçü 16 bit
    const core = new Uint8Array(26)
    core[0] = 0x42; core[1] = 0x4d; core[14] = 12
    core[18] = 0x20; core[19] = 0x03; core[20] = 0x58; core[21] = 0x02
    expect(sniffKind(core)).toBe('bmp')
    expect(headerSize(core, 'bmp')).toEqual({ width: 800, height: 600 })
    const png = new Uint8Array(24)
    png.set(PNG_HEAD)
    new DataView(png.buffer).setUint32(16, 1920)
    new DataView(png.buffer).setUint32(20, 1080)
    expect(headerSize(png, 'png')).toEqual({ width: 1920, height: 1080 })
    expect(headerSize(new Uint8Array([0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 0x20, 0x03, 0x58, 0x02]), 'gif')).toEqual({ width: 800, height: 600 })
    expect(headerSize(bmpHead(800, 600), 'bmp')).toEqual({ width: 800, height: 600 })
    const vp8x = new Uint8Array(30)
    vp8x.set(new TextEncoder().encode('RIFF\u0000\u0000\u0000\u0000WEBPVP8X'))
    vp8x[24] = 0x7f; vp8x[25] = 0x07; vp8x[27] = 0x37; vp8x[28] = 0x04        // 1920 × 1080 (değer − 1)
    expect(headerSize(vp8x, 'webp')).toEqual({ width: 1920, height: 1080 })
    expect(headerSize(new Uint8Array(10), 'avif')).toBeNull()
    // İmzasız VP8 çerçevesi (bozuk / kesik dosya): ölçü okunmaz, açılıp bakılır
    const junk = new Uint8Array(30).fill(0x20)
    junk.set(new TextEncoder().encode('RIFF    WEBPVP8 '))
    expect(headerSize(junk, 'webp')).toBeNull()
    expect(headerSize(new Uint8Array([0xff, 0xd8, 0xff, 0xda]), 'jpeg')).toBeNull()
  })

  it('6 MB PNG küçültme gerekmese de sunucu sınırı için yeniden kodlanır', async () => {
    const deps = fakeDeps({ width: 900, height: 600, pngBytes: 400_000 })
    const out = await prepareImage(file(PNG_HEAD, 6 * 1024 * 1024), 'full', deps)
    expect(deps.calls).toHaveLength(1)
    expect(out.file.size).toBe(400_000)
    expect(out.info.resized).toBe(false)
  })
})
