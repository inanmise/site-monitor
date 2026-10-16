import { describe, it, expect, vi } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import {
  ImagePrepError, JPEG_QUALITY, MAX_DECODE, MAX_INPUT_BYTES, PDF_LAYOUT, SERVER_IMAGE, areaResample, browserDeps, headerSize,
  jpegOrientation, displayWidthPct, prepareImage, slotBox, sniffKind, targetSize,
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
    expect(PDF_LAYOUT.imageMaxH).toBe(num(WRITER, 'IMAGE_MAX_H'))
    expect(SERVER_IMAGE.maxSide).toBe(num(VALIDATOR, 'MAX_SIDE'))
    expect(SERVER_IMAGE.maxPixels).toBe(num(VALIDATOR, 'MAX_PIXELS'))
    expect(SERVER_IMAGE.minSide).toBe(num(VALIDATOR, 'MIN_SIDE'))
    expect(VALIDATOR).toContain('MAX_BYTES = 5L * 1024 * 1024')
    expect(SERVER_IMAGE.maxBytes).toBe(5 * 1024 * 1024)
    // Writer'daki yerleşim formülü: yarım hücre (CONTENT_W - GAP * 2) / 2
    expect(WRITER).toContain('(CONTENT_W - GAP * 2) / 2')
  })
})

describe('displayWidthPct (2026-10-16: görsel yuvayı doldurur)', () => {
  it('geniş görsel yuvanın tamamı; yalnız sayfaya sığmayan uzun görsel PDF gibi orantılı daralır', () => {
    expect(displayWidthPct('half', 760, 351)).toBe(100)
    expect(displayWidthPct('full', 1569, 314)).toBe(100)
    // dikey telefon ekran görüntüsü: PDF'te 640 pt yükseklikte ortalanır → ekranda da aynı oran
    const box = slotBox('full')
    expect(displayWidthPct('full', 887, 1920)).toBeCloseTo((PDF_LAYOUT.imageMaxH * 887 / 1920) / box.w * 100, 6)
    expect(displayWidthPct('full', 887, 1920)).toBeLessThan(60)
    expect(displayWidthPct('half', 0, 0)).toBe(100)
    expect(displayWidthPct('half', undefined, 100)).toBe(100)
  })
})

describe('targetSize (2026-10-16: çözünürlük korunur)', () => {
  it('sunucu sınırı içindeki görsel OLDUĞU GİBİ kalır — yuvaya göre küçültme yok, büyütme yok', () => {
    expect(targetSize(3840, 2160)).toEqual({ width: 3840, height: 2160, resize: false })
    expect(targetSize(1920, 40)).toEqual({ width: 1920, height: 40, resize: false })
    expect(targetSize(400, 300)).toEqual({ width: 400, height: 300, resize: false })
    expect(targetSize(6000, 2000)).toEqual({ width: 6000, height: 2000, resize: false })
  })

  it('yalnız sunucu sınırı (6000 px kenar, 12 MP) aşılınca oran korunarak sınıra iner', () => {
    expect(targetSize(7200, 1800)).toEqual({ width: 6000, height: 1500, resize: true })
    const p = targetSize(5000, 5000)
    expect(p.resize).toBe(true)
    expect(p.width * p.height).toBeLessThanOrEqual(SERVER_IMAGE.maxPixels)
    expect(p.width).toBe(p.height)
    const tall = targetSize(400, 20000)
    expect(Math.max(tall.width, tall.height)).toBeLessThanOrEqual(SERVER_IMAGE.maxSide)
    // Aşırı ince görsel sınıra inince kısa kenarı alt sınırın altında kalır — çağıran söyler (errThin)
    expect(Math.min(...Object.values(targetSize(30000, 20)).slice(0, 2))).toBeLessThan(SERVER_IMAGE.minSide)
  })
})

/** Tek kanallı (gri) test görseli: v(x, y) → RGBA dizisi; areaResample'ı çalıştırıp hedefi döner. */
async function resample(srcW, srcH, dstW, dstH, v, alpha = () => 255) {
  const rows = []
  for (let y = 0; y < srcH; y++) {
    const r = new Uint8ClampedArray(srcW * 4)
    for (let x = 0; x < srcW; x++) { const g = v(x, y); r.set([g, g, g, alpha(x, y)], x * 4) }
    rows.push(r)
  }
  const out = []
  await areaResample(srcW, srcH, dstW, dstH, (y) => rows[y], (y, row) => { out[y] = Array.from(row) })
  return out
}

describe('areaResample — alan ortalaması (2026-10-16: metin / rakam net kalır)', async () => {
  it('2:1 tam katta her hedef piksel 2 × 2 bloğun birebir ortalaması', async () => {
    const out = await resample(4, 2, 2, 1, (x) => [0, 100, 200, 255][x])
    expect(out).toHaveLength(1)
    expect(out[0].filter((_, i) => i % 4 === 0)).toEqual([50, 228])          // (0+100)/2, (200+255)/2 → 227,5 → 228
  })

  it('3:2 kesirli kapsama: ortadaki kaynak piksel iki hedefe yarı yarıya düşer', async () => {
    // hedef 0 = kaynak 0 (tam) + kaynak 1 (yarım) → (0·1 + 90·0,5) / 1,5 = 30; hedef 1 = (90·0,5 + 180·1) / 1,5 = 150
    const out = await resample(3, 1, 2, 1, (x) => [0, 90, 180][x])
    expect(out[0].filter((_, i) => i % 4 === 0)).toEqual([30, 150])
    const v = await resample(1, 3, 1, 2, (_x, y) => [0, 90, 180][y])
    expect([v[0][0], v[1][0]]).toEqual([30, 150])
  })

  it('düz renk aynen kalır; enerji (ortalama parlaklık) korunur; sınırda değer taşmaz', async () => {
    const flat = await resample(7, 5, 3, 2, () => 77)
    for (const row of flat) for (let i = 0; i < row.length; i += 4) expect(row.slice(i, i + 4)).toEqual([77, 77, 77, 255])
    const src = (x, y) => (x * 37 + y * 11) % 256
    let sum = 0
    for (let y = 0; y < 30; y++) for (let x = 0; x < 40; x++) sum += src(x, y)
    const out = await resample(40, 30, 16, 12, src)
    let osum = 0
    for (const row of out) for (let i = 0; i < row.length; i += 4) { osum += row[i]; expect(row[i]).toBeGreaterThanOrEqual(0); expect(row[i]).toBeLessThanOrEqual(255) }
    expect(Math.abs(osum / (16 * 12) - sum / (40 * 30))).toBeLessThan(1)
  })

  it('siyah-beyaz ince çizgi kaybolmaz: 1 px çizgi 2:1 küçültmede gri iz bırakır (örtüşmeyle düşmez)', async () => {
    // her 4. sütun siyah (yazı çizgisi gibi) — nokta örnekleme bazen hepsini atlar; alan ortalaması hepsini taşır
    const out = await resample(16, 2, 8, 1, (x) => (x % 4 === 1 ? 0 : 255))
    const g = out[0].filter((_, i) => i % 4 === 0)
    expect(g.filter((v) => v < 255)).toHaveLength(4)
    expect(Math.min(...g)).toBe(128)
  })

  it('saydamlık önçarpımlı: saydam pikselin rengi kenara koyu saçak olarak sızmaz', async () => {
    // sol piksel kırmızı-opak, sağ piksel SİYAH ama tam saydam → sonuç yarı saydam KIRMIZI (koyu kırmızı değil)
    const rows = [new Uint8ClampedArray([255, 0, 0, 255, 0, 0, 0, 0])]
    let got
    await areaResample(2, 1, 1, 1, () => rows[0], (_y, row) => { got = Array.from(row) })
    expect(got).toEqual([255, 0, 0, 128])
    const clear = [new Uint8ClampedArray([9, 9, 9, 0, 9, 9, 9, 0])]
    await areaResample(2, 1, 1, 1, () => clear[0], (_y, row) => { got = Array.from(row) })
    expect(got).toEqual([0, 0, 0, 0])
  })

  it('büyütmez; her hedef satır bir kez ve sırayla yazılır', async () => {
    await expect(areaResample(10, 10, 11, 5, () => new Uint8ClampedArray(40), () => {})).rejects.toThrow(RangeError)
    const ys = []
    const rows = Array.from({ length: 13 }, () => new Uint8ClampedArray(9 * 4).fill(200))
    await areaResample(9, 13, 4, 6, (y) => rows[y], (y) => ys.push(y))
    expect(ys).toEqual([0, 1, 2, 3, 4, 5])
  })

  it('satır Promise ile de okunabilir (tarayıcı şerit okurken ana iş parçacığını bırakır) — sonuç aynı', async () => {
    const v = (x, y) => (x * 31 + y * 17) % 256
    const rows = []
    for (let y = 0; y < 9; y++) {
      const r = new Uint8ClampedArray(14 * 4)
      for (let x = 0; x < 14; x++) r.set([v(x, y), v(x, y), v(x, y), 255], x * 4)
      rows.push(r)
    }
    const sync = []
    await areaResample(14, 9, 5, 4, (y) => rows[y], (y, row) => { sync[y] = Array.from(row) })
    const viaPromise = []
    await areaResample(14, 9, 5, 4, (y) => new Promise((ok) => setTimeout(() => ok(rows[y]), 0)), (y, row) => { viaPromise[y] = Array.from(row) })
    expect(viaPromise).toEqual(sync)
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
    bitmaps: [],
    lastBitmap: null,
    async decode() {
      this.decoded += 1
      if (decodeFails) throw new Error('decode')
      this.lastBitmap = { width, height, close: vi.fn() }
      return this.lastBitmap
    },
    async scale(bm, w, h) {
      if (scaleFails) throw scaleFails
      this.bitmaps.push(bm)
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
    const out = await prepareImage(f, deps)
    expect(out.file).toBe(f)
    expect(out.info).toMatchObject({ resized: false, converted: false })
    expect(deps.calls).toHaveLength(0)
  })

  it('2026-10-16: sınır içindeki büyük ekran görüntüsü OLDUĞU GİBİ gider (çözünürlük korunur, yeniden kodlanmaz)', async () => {
    const f = file(PNG_HEAD, 3_000_000, 'ekran.png')
    const deps = fakeDeps({ width: 3840, height: 2160 })
    const out = await prepareImage(f, deps)
    expect(out.file).toBe(f)
    expect(out.info).toMatchObject({ resized: false, converted: false, toW: 3840, toH: 2160 })
    expect(deps.scales).toHaveLength(0)
    expect(deps.calls).toHaveLength(0)
  })

  it('sınırı aşan ekran görüntüsü sınıra iner, KAYIPSIZ PNG kalır; özet eski / yeni ölçüyü söyler', async () => {
    const f = file(PNG_HEAD, 3_000_000, 'ekran.png')
    const deps = fakeDeps({ width: 7200, height: 1800 })
    const out = await prepareImage(f, deps)
    expect(deps.scales).toEqual([{ w: 6000, h: 1500 }])
    expect(deps.calls).toEqual([{ w: 6000, h: 1500, type: 'image/png', q: undefined }])
    expect(out.file.type).toBe('image/png')
    expect(out.file.name).toBe('ekran.png')
    expect(out.info).toMatchObject({ resized: true, converted: false, fromW: 7200, fromH: 1800, toW: 6000, toH: 1500,
      fromBytes: 3_000_000, toBytes: 200_000 })
  })

  it('WebP PNG\'ye çevrilir (aynı ölçü); ekran görüntüsü PNG\'si HİÇBİR ZAMAN JPEG olmaz — bayt sınırında ölçü küçülür', async () => {
    const webp = new File([new TextEncoder().encode('RIFF\u0000\u0000\u0000\u0000WEBPVP8 ' + ' '.repeat(100))], 'a.webp', { type: 'image/webp' })
    const out = await prepareImage(webp, fakeDeps({ width: 600, height: 400 }))
    expect(out.file.type).toBe('image/png')
    expect(out.file.name).toBe('a.png')
    expect(out.info).toMatchObject({ converted: true, resized: false, fromKind: 'WebP', toKind: 'PNG', toW: 600, toH: 400 })
    // 6 MB PNG: JPEG denenmez; PNG ilk kodlamada 6 MB → ölçü bayt oranına göre küçülür, ikinci kodlama sığar
    let n = 0
    const shrink = fakeDeps({ width: 4000, height: 3000 })
    shrink.encode = async (sc, type, q) => {
      shrink.calls.push({ w: sc.w, h: sc.h, type, q })
      n += 1
      return new Blob([new Uint8Array(n === 1 ? 6 * 1024 * 1024 : 3 * 1024 * 1024)], { type })
    }
    const big = await prepareImage(file(PNG_HEAD, 6 * 1024 * 1024, 'tablo.png'), shrink)
    expect(shrink.calls.map((c) => c.type)).toEqual(['image/png', 'image/png'])
    expect(big.file.type).toBe('image/png')
    expect(big.info).toMatchObject({ resized: true, converted: false, toKind: 'PNG' })
    const f = Math.sqrt(5 / 6) * 0.95
    expect(big.info.toW).toBe(Math.floor(4000 * f))
    expect(shrink.released).toEqual(shrink.scales)                // ara tuval adımda, son tuval finally'de bırakıldı
    // WebP fotoğrafın PNG'si sınırı aşarsa JPEG 0,95 denenir
    const photo = await prepareImage(webp, fakeDeps({ width: 600, height: 400, pngBytes: 6_000_000, jpegBytes: 900_000 }))
    expect(photo.file.type).toBe('image/jpeg')
    expect(photo.file.name).toBe('a.jpg')
  })

  it('net hatalar: HEIC / PDF / SVG / TIFF / tanınmayan / boş / çok büyük / açılamayan / çok küçük / küçültmeye rağmen büyük', async () => {
    const err = async (f, deps = fakeDeps({ width: 100, height: 100 })) => {
      try { await prepareImage(f, deps) } catch (e) { return e instanceof ImagePrepError ? e.key : 'other' }
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

  it('bayt sınırı için küçültme adımları sınırlıdır (en çok 5) ve her tuval bırakılır; JPEG\'e kaçılmaz', async () => {
    const deps = fakeDeps({ width: 4000, height: 3000, pngBytes: 6_000_000, jpegBytes: 100_000 })
    await expect(prepareImage(file(PNG_HEAD, 6 * 1024 * 1024), deps)).rejects.toMatchObject({ key: 'te.img.errStillLarge' })
    expect(deps.scales).toHaveLength(5)
    expect(deps.calls.every((c) => c.type === 'image/png')).toBe(true)
    expect(deps.released).toEqual(deps.scales)
    // her adım ÖZGÜN görselden (birikmeli yeniden örnekleme yok) ve ölçü her seferinde KESİN küçülür
    expect(deps.bitmaps).toHaveLength(5)
    expect(deps.bitmaps.every((b) => b === deps.lastBitmap)).toBe(true)
    for (let i = 1; i < deps.scales.length; i++) expect(deps.scales[i].w).toBeLessThan(deps.scales[i - 1].w)
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
    await expect(prepareImage(ihdr(20_000, 20_000), deps)).rejects.toMatchObject({ key: 'te.img.errHuge', params: ['20000 × 20000'] })
    expect(deps.decoded).toBe(0)
    await expect(prepareImage(ihdr(MAX_DECODE.side + 1, 20), deps)).rejects.toMatchObject({ key: 'te.img.errHuge' })
    // 48 MP telefon fotoğrafı açılır
    const ok = fakeDeps({ width: 8000, height: 6000 })
    await expect(prepareImage(ihdr(8000, 6000), ok)).resolves.toBeTruthy()
    // sınırı aşan (küçültülecek) görselde çizim belleği yetmezse
    const broken = fakeDeps({ width: 7200, height: 1800, scaleFails: new ImagePrepError('te.img.errBrowser') })
    await expect(prepareImage(file(PNG_HEAD, 4_000_000), broken)).rejects.toMatchObject({ key: 'te.img.errBrowser' })
  })

  it('aşırı ince görsel (sunucu sınırına inince kısa kenarı 16 px altı) açık mesajla reddedilir', async () => {
    await expect(prepareImage(file(PNG_HEAD, 2000), fakeDeps({ width: 30000, height: 20 })))
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
    const out = await prepareImage(new File([pad(exif(6))], 'foto.jpg', { type: 'image/jpeg' }), rotated)
    expect(out.file.type).toBe('image/jpeg')
    expect(out.info).toMatchObject({ rotated: true, resized: false, converted: false, toW: 300, toH: 400 })
    expect(rotated.calls).toEqual([{ w: 300, h: 400, type: 'image/jpeg', q: JPEG_QUALITY }])
    expect(JPEG_QUALITY).toBe(0.95)
    const upright = fakeDeps({ width: 400, height: 300 })
    const f = new File([pad(exif(1))], 'duz.jpg', { type: 'image/jpeg' })
    expect((await prepareImage(f, upright)).file).toBe(f)
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
    const out = await prepareImage(f, deps)
    expect(out.file).toBe(f)
    expect(out.info).toMatchObject({ resized: false, fromW: 800, fromH: 400 })
    expect(deps.decoded).toBe(1)
    // Yarım inmiş PNG: başlık sağlam, tarayıcı açamıyor → "bozuk" (sunucu yalnız başlığı okur, yakalamazdı)
    const broken = { ...fakeDeps(), decode: async () => { throw new DOMException('kesik', 'InvalidStateError') } }
    await expect(prepareImage(ihdr(800, 400), broken)).rejects.toMatchObject({ key: 'te.img.errDecode' })
    // Açma API'si yok (ReferenceError) ya da seçeneği tanımıyor (TypeError): sığan PNG olduğu gibi gider
    for (const err of [new ReferenceError('createImageBitmap'), new TypeError('x')]) {
      const old = { ...fakeDeps(), decode: async () => { throw err } }
      const g = ihdr(800, 400)
      expect((await prepareImage(g, old)).file).toBe(g)
    }
    // ... ama küçültme / çevirme gerekiyorsa API'siz yapılamaz → açılamadı
    const old = { ...fakeDeps(), decode: async () => { throw new ReferenceError('createImageBitmap') } }
    await expect(prepareImage(ihdr(7200, 1800), old)).rejects.toMatchObject({ key: 'te.img.errDecode' })
    await expect(prepareImage(ihdr(800, 400, 6 * 1024 * 1024), old)).rejects.toMatchObject({ key: 'te.img.errDecode' })
    // Başlıktaki küçük ölçü açmadan reddedilir
    const d2 = fakeDeps({ width: 10, height: 400 })
    await expect(prepareImage(ihdr(10, 400), d2)).rejects.toMatchObject({ key: 'te.img.errTooSmall', params: ['10 × 400'] })
    expect(d2.decoded).toBe(0)
  })

  it('geçerli başlıklı 6 MB PNG yeniden kodlanır; geçerli başlıklı GIF / BMP PNG\'ye çevrilir (olduğu gibi gitmez)', async () => {
    const big = new Uint8Array(6 * 1024 * 1024)
    big.set(PNG_HEAD)
    new DataView(big.buffer).setUint32(16, 900)
    new DataView(big.buffer).setUint32(20, 600)
    const d1 = fakeDeps({ width: 900, height: 600, pngBytes: 400_000 })
    const o1 = await prepareImage(new File([big], 'buyuk.png', { type: 'image/png' }), d1)
    expect(o1.file.size).toBe(400_000)
    expect(d1.calls).toHaveLength(1)
    const gif = new Uint8Array(64)
    gif.set([0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 0x20, 0x03, 0x58, 0x02])
    const o2 = await prepareImage(new File([gif], 'a.gif', { type: 'image/gif' }), fakeDeps({ width: 800, height: 600 }))
    expect(o2.file.type).toBe('image/png')
    expect(o2.info).toMatchObject({ converted: true, fromKind: 'GIF' })
    const bmp = new Uint8Array(64)
    bmp.set(bmpHead(800, 600))
    const o3 = await prepareImage(new File([bmp], 'a.bmp', { type: 'image/bmp' }), fakeDeps({ width: 800, height: 600 }))
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
    await expect(prepareImage(unreadable, fakeDeps({ width: 10, height: 10 }))).rejects.toMatchObject({ key: 'te.img.errRead' })
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
    const out = await prepareImage(file(PNG_HEAD, 6 * 1024 * 1024), deps)
    expect(deps.calls).toHaveLength(1)
    expect(out.file.size).toBe(400_000)
    expect(out.info.resized).toBe(false)
  })
})
