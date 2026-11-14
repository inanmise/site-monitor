import { describe, it, expect } from 'vitest'
import { HEAD_BYTES, checkLimits, exifOrientation, iccLooksSrgb, probeBlob, probeBytes, sniffType } from '../utils/imageEngine/probe.js'
import { ENGINE_LIMITS, EngineError } from '../utils/imageEngine/errors.js'
import { encodePng } from '../utils/imageEngine/pngWriter.js'
import { rng, solid } from './imageEngineKit.test.helpers.js'

const enc = (s) => Array.from(s, (c) => c.charCodeAt(0))
const u32be = (v) => [(v >>> 24) & 255, (v >>> 16) & 255, (v >>> 8) & 255, v & 255]
const u32le = (v) => [v & 255, (v >>> 8) & 255, (v >>> 16) & 255, (v >>> 24) & 255]
const u16le = (v) => [v & 255, (v >>> 8) & 255]
const u16be = (v) => [(v >>> 8) & 255, v & 255]

function pngHeader(w, h, colorType = 6, extraChunks = []) {
  const chunk = (type, data) => [...u32be(data.length), ...enc(type), ...data, 0, 0, 0, 0]
  return Uint8Array.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, ...chunk('IHDR', [...u32be(w), ...u32be(h), 8, colorType, 0, 0, 0]),
    ...extraChunks.flatMap(([t, d]) => chunk(t, d)), ...chunk('IDAT', [1, 2, 3]), ...chunk('IEND', [])])
}
function jpeg(w, h, { before = [], sof = 0xc0 } = {}) {
  return Uint8Array.from([0xff, 0xd8, ...before, 0xff, sof, 0, 11, 8, ...u16be(h), ...u16be(w), 1, 1, 0x11, 0, 0xff, 0xda, 0, 2, 0xff, 0xd9])
}
const exifApp1 = (orientation) => {
  const tiff = [0x49, 0x49, 42, 0, 8, 0, 0, 0, 1, 0, 0x12, 0x01, 3, 0, 1, 0, 0, 0, orientation, 0, 0, 0, 0, 0, 0, 0]
  return [0xff, 0xe1, ...u16be(2 + 6 + tiff.length), ...enc('Exif'), 0, 0, ...tiff]
}
function gif(w, h, { frames = 1, transparent = false, frameBytes = 20 } = {}) {
  const out = [...enc('GIF89a'), ...u16le(w), ...u16le(h), 0x80, 0, 0, 1, 2, 3, 4, 5, 6]        // 2 renkli genel palet
  for (let f = 0; f < frames; f++) {
    out.push(0x21, 0xf9, 4, transparent ? 1 : 0, 0, 0, 0, 0)
    out.push(0x2c, 0, 0, 0, 0, ...u16le(w), ...u16le(h), 0, 2)
    for (let left = frameBytes; left > 0; left -= 255) { const n = Math.min(255, left); out.push(n, ...new Array(n).fill(7)) }
    out.push(0)
  }
  out.push(0x3b)
  return Uint8Array.from(out)
}
function webp(kind, w, h, flags = 0) {
  const riff = (body) => Uint8Array.from([...enc('RIFF'), ...u32le(body.length + 4), ...enc('WEBP'), ...body])
  if (kind === 'VP8X') return riff([...enc('VP8X'), ...u32le(10), flags, 0, 0, 0, (w - 1) & 255, ((w - 1) >> 8) & 255, ((w - 1) >> 16) & 255, (h - 1) & 255, ((h - 1) >> 8) & 255, ((h - 1) >> 16) & 255])
  if (kind === 'VP8 ') return riff([...enc('VP8 '), ...u32le(10), 0, 0, 0, 0x9d, 0x01, 0x2a, ...u16le(w), ...u16le(h)])
  const bits = (w - 1) | ((h - 1) << 14) | (flags ? 1 << 28 : 0)
  return riff([...enc('VP8L'), ...u32le(10), 0x2f, ...u32le(bits >>> 0), 0, 0, 0, 0, 0])
}
function bmp(w, h, bpp = 24) {
  const b = new Uint8Array(54)
  b.set([0x42, 0x4d]); b.set(u32le(40), 14); b.set(u32le(w), 18); b.set(u32le(h >>> 0), 22); b.set(u16le(bpp), 28)
  return b
}
function avif(w, h, { alpha = false, brand = 'avif', rotate = false, icc = false } = {}) {
  const box = (type, body) => [...u32be(body.length + 8), ...enc(type), ...body]
  const ipco = [...box('ispe', [0, 0, 0, 0, ...u32be(64), ...u32be(64)]), ...box('ispe', [0, 0, 0, 0, ...u32be(w), ...u32be(h)])]
  if (alpha) ipco.push(...box('auxC', [0, 0, 0, 0, ...enc('urn:mpeg:mpegB:cicp:systems:auxiliary:alpha'), 0]))
  if (rotate) ipco.push(...box('irot', [1]))
  if (icc) ipco.push(...box('colr', [...enc('prof'), 1, 2, 3]))
  const meta = box('meta', [0, 0, 0, 0, ...box('hdlr', [0, 0, 0, 0]), ...box('iprp', box('ipco', ipco))])
  // ana marka `brand`; uyumlu markalar: mif1 + miaf (+ ana marka mif1 ise avif — gerçek dosyalardaki gibi)
  const compatible = brand === 'mif1' ? [...enc('avif'), ...enc('mif1'), ...enc('miaf')] : [...enc('mif1'), ...enc('miaf')]
  return Uint8Array.from([...box('ftyp', [...enc(brand), 0, 0, 0, 0, ...compatible]), ...meta, ...box('mdat', [1, 2, 3, 4])])
}
const code = (fn) => { try { fn() } catch (e) { expect(e).toBeInstanceOf(EngineError); return [e.code, e.params] } return null }

describe('görsel motoru — tür (sihirli baytlar)', () => {
  it('desteklenen türleri uzantıya / MIME a bakmadan tanır', () => {
    expect(sniffType(pngHeader(1, 1))).toBe('png')
    expect(sniffType(jpeg(1, 1))).toBe('jpeg')
    expect(sniffType(gif(1, 1))).toBe('gif')
    expect(sniffType(webp('VP8X', 1, 1))).toBe('webp')
    expect(sniffType(bmp(1, 1))).toBe('bmp')
    expect(sniffType(avif(1, 1))).toBe('avif')
    expect(sniffType(avif(1, 1, { brand: 'mif1' }))).toBe('avif')         // ana marka mif1, uyumlu markada avif
  })

  it('açılamayan türler adıyla UNSUPPORTED_FORMAT olur', () => {
    const cases = { svg: '<?xml version="1.0"?><svg xmlns="http://www.w3.org/2000/svg"/>', pdf: '%PDF-1.7', unknown: 'merhaba dünya', html: '<html><svg></svg></html>' }
    expect(sniffType(Uint8Array.from(enc(cases.svg)))).toBe('svg')
    expect(sniffType(Uint8Array.from(enc(cases.pdf)))).toBe('pdf')
    expect(sniffType(Uint8Array.from(enc(cases.html)))).toBe('unknown')
    expect(sniffType(Uint8Array.from([0x49, 0x49, 0x2a, 0, 8, 0, 0, 0]))).toBe('tiff')
    expect(sniffType(Uint8Array.from([0, 0, 0, 24, ...enc('ftypheic'), 0, 0, 0, 0, ...enc('mif1heic')]))).toBe('heic')
    expect(sniffType(Uint8Array.from([0x42, 0x4d, ...enc(' ile başlayan metin BMP değildir')]))).toBe('unknown')
    expect(code(() => probeBytes(Uint8Array.from(enc(cases.svg))))).toEqual(['UNSUPPORTED_FORMAT', { detected: 'svg' }])
    expect(code(() => probeBytes(Uint8Array.from(enc(cases.unknown))))).toEqual(['UNSUPPORTED_FORMAT', { detected: 'unknown' }])
    expect(code(() => probeBytes(new Uint8Array(0)))).toEqual(['UNSUPPORTED_FORMAT', { detected: 'empty' }])
  })
})

describe('görsel motoru — başlıktan ölçü / saydamlık / animasyon', () => {
  it('PNG: ölçü, renk türü / tRNS ten saydamlık, acTL den animasyon, iCCP', () => {
    expect(probeBytes(pngHeader(640, 480, 6))).toMatchObject({ type: 'png', width: 640, height: 480, hasAlpha: true, animated: false, complete: true })
    expect(probeBytes(pngHeader(640, 480, 2)).hasAlpha).toBe(false)
    expect(probeBytes(pngHeader(640, 480, 3, [['PLTE', [1, 2, 3]], ['tRNS', [0]]])).hasAlpha).toBe(true)
    expect(probeBytes(pngHeader(10, 10, 6, [['acTL', [...u32be(5), ...u32be(0)]]])).animated).toBe(true)
    expect(probeBytes(pngHeader(10, 10, 6, [['acTL', [...u32be(1), ...u32be(0)]]])).animated).toBe(false)
    expect(probeBytes(pngHeader(10, 10, 2, [['iCCP', [1, 2, 3]]])).icc).toBe(true)
  })

  it('JPEG: SOF ölçüsü (aşamalı dahil), EXIF yönü 5–8 de ölçü yer değiştirir, ICC', () => {
    expect(probeBytes(jpeg(4000, 3000))).toMatchObject({ type: 'jpeg', width: 4000, height: 3000, hasAlpha: false, orientation: 1 })
    expect(probeBytes(jpeg(4000, 3000, { sof: 0xc2 }))).toMatchObject({ width: 4000, height: 3000 })
    expect(probeBytes(jpeg(4000, 3000, { before: exifApp1(6) }))).toMatchObject({ width: 3000, height: 4000, orientation: 6 })
    expect(probeBytes(jpeg(4000, 3000, { before: exifApp1(3) }))).toMatchObject({ width: 4000, height: 3000, orientation: 3 })
    expect(probeBytes(jpeg(4000, 3000, { before: exifApp1(77) })).orientation).toBe(1)
    expect(probeBytes(jpeg(10, 10, { before: [0xff, 0xe2, 0, 16, ...enc('ICC_PROFILE'), 0, 1, 1] })).icc).toBe(true)
    // DHT (C4) SOF değildir
    expect(probeBytes(jpeg(10, 20, { before: [0xff, 0xc4, 0, 4, 9, 9] }))).toMatchObject({ width: 10, height: 20 })
    expect(exifOrientation(Uint8Array.from([1, 2, 3]), 0, 3)).toBe(1)
  })

  it('GIF: mantıksal ekran ölçüsü, ilk karenin saydamlık bayrağı, ikinci kare = animasyon', () => {
    expect(probeBytes(gif(320, 200))).toMatchObject({ type: 'gif', width: 320, height: 200, hasAlpha: false, animated: false, complete: true })
    expect(probeBytes(gif(320, 200, { transparent: true })).hasAlpha).toBe(true)
    expect(probeBytes(gif(320, 200, { frames: 3 })).animated).toBe(true)
    expect(probeBytes(gif(320, 200, { frames: 2, frameBytes: 5000 })).animated).toBe(true)
  })

  it('WebP (VP8 / VP8L / VP8X), BMP ve AVIF', () => {
    expect(probeBytes(webp('VP8 ', 800, 600))).toMatchObject({ type: 'webp', width: 800, height: 600, hasAlpha: false })
    expect(probeBytes(webp('VP8L', 801, 601, 1))).toMatchObject({ width: 801, height: 601, hasAlpha: true })
    expect(probeBytes(webp('VP8L', 801, 601, 0)).hasAlpha).toBe(false)
    expect(probeBytes(webp('VP8X', 5000, 4000, 0x10 | 0x02 | 0x20))).toMatchObject({ width: 5000, height: 4000, hasAlpha: true, animated: true, icc: true })
    expect(probeBytes(bmp(300, 200))).toMatchObject({ type: 'bmp', width: 300, height: 200, hasAlpha: false })
    expect(probeBytes(bmp(300, -200))).toMatchObject({ width: 300, height: 200 })          // yukarıdan aşağı
    expect(probeBytes(bmp(300, 200, 32)).hasAlpha).toBeNull()                              // bilinemez
    expect(probeBytes(avif(1920, 1080))).toMatchObject({ type: 'avif', width: 1920, height: 1080, hasAlpha: false, animated: false })
    expect(probeBytes(avif(1920, 1080, { alpha: true, icc: true }))).toMatchObject({ hasAlpha: true, icc: true })
    expect(probeBytes(avif(1920, 1080, { brand: 'avis' })).animated).toBe(true)
    expect(probeBytes(avif(1920, 1080, { rotate: true }))).toMatchObject({ width: 1080, height: 1920 })
  })

  it('SINIRLAR ÇÖZMEDEN ÖNCE: dev ölçü başlıktan reddedilir (sıkıştırma bombası açılmaz)', () => {
    expect(code(() => probeBytes(pngHeader(20000, 20000)))).toEqual(['TOO_MANY_PIXELS', { width: 20000, height: 20000, maxPixels: 50_000_000, maxSide: 30_000 }])
    expect(code(() => probeBytes(pngHeader(30001, 10)))[0]).toBe('TOO_MANY_PIXELS')
    expect(code(() => probeBytes(jpeg(65535, 65535)))[0]).toBe('TOO_MANY_PIXELS')
    expect(code(() => probeBytes(gif(60000, 60000)))[0]).toBe('TOO_MANY_PIXELS')
    expect(code(() => probeBytes(webp('VP8X', 16000000, 4)))[0]).toBe('TOO_MANY_PIXELS')
    expect(code(() => probeBytes(bmp(100000, 100000)))[0]).toBe('TOO_MANY_PIXELS')
    expect(code(() => probeBytes(avif(40000, 40000)))[0]).toBe('TOO_MANY_PIXELS')
    expect(probeBytes(pngHeader(10000, 5000)).width).toBe(10000)                           // tam 50 MP: geçer
    expect(probeBytes(pngHeader(30000, 1000)).width).toBe(30000)
    expect(() => checkLimits(7071, 7071)).not.toThrow()
    expect(() => checkLimits(7072, 7072)).toThrow(EngineError)
  })

  it('ölçüsü okunamayan görsel AÇILMAZ: DECODE_FAILED (header)', () => {
    expect(code(() => probeBytes(pngHeader(0, 10)))).toEqual(['DECODE_FAILED', { reason: 'header' }])
    expect(code(() => probeBytes(Uint8Array.from([0xff, 0xd8, 0xff, 0xd9])))).toEqual(['DECODE_FAILED', { reason: 'header' }])
    expect(code(() => probeBytes(Uint8Array.from([...enc('RIFF'), 0, 0, 0, 0, ...enc('WEBPXXXX'), ...new Array(20).fill(0)])))).toEqual(['DECODE_FAILED', { reason: 'header' }])
    expect(code(() => probeBytes(Uint8Array.from([0, 0, 0, 20, ...enc('ftypavif'), 0, 0, 0, 0, ...enc('avif')])))).toEqual(['DECODE_FAILED', { reason: 'header' }])
    // baş yetmediyse (dosya daha uzun) hata değil "eksik": çağıran tam dosyayla yeniden dener
    const longJpeg = Uint8Array.from([0xff, 0xd8, 0xff, 0xe1, 0xff, 0xff, ...new Array(100).fill(0)])
    expect(probeBytes(longJpeg, 100000)).toMatchObject({ type: 'jpeg', width: 0, complete: false })
  })

  it('DÜŞMANCA baytlar: rastgele bozulmuş başlıklar döngüye girmez; yalnız EngineError atılır', () => {
    const rnd = rng(123)
    const seeds = [pngHeader(100, 100, 3, [['PLTE', [1, 2, 3]], ['tRNS', [0]], ['acTL', [...u32be(3), ...u32be(0)]]]), jpeg(100, 100, { before: exifApp1(6) }),
      gif(100, 100, { frames: 2, transparent: true }), webp('VP8X', 100, 100, 0x12), bmp(100, 100, 32), avif(100, 100, { alpha: true, rotate: true, icc: true })]
    const t0 = Date.now()
    for (let round = 0; round < 4000; round++) {
      const b = Uint8Array.from(seeds[round % seeds.length])
      const hits = 1 + ((rnd() * 6) | 0)
      for (let k = 0; k < hits; k++) b[8 + ((rnd() * (b.length - 8)) | 0)] = (rnd() * 256) | 0
      try {
        const info = probeBytes(b)
        expect(info.width * info.height).toBeLessThanOrEqual(ENGINE_LIMITS.maxPixels)
      } catch (e) {
        expect(e, `tur ${round}: ${e?.message}`).toBeInstanceOf(EngineError)
      }
    }
    expect(Date.now() - t0).toBeLessThan(5000)
    // sonsuz döngü adayları: sıfır uzunluklu kutular / parçalar, dev alt blok zincirleri
    expect(() => probeBytes(Uint8Array.from([0, 0, 0, 16, ...enc('ftypavif'), 0, 0, 0, 0, 0, 0, 0, 0, ...enc('meta'), ...new Array(64).fill(0)]))).toThrow(EngineError)
    const gifLoop = Uint8Array.from([...enc('GIF89a'), 1, 0, 1, 0, 0, 0, 0, 0x21, 0xff, ...new Array(5000).fill(1)])
    expect(probeBytes(gifLoop)).toMatchObject({ type: 'gif', width: 1, height: 1 })
  })
})

describe('görsel motoru — Blob tan başlık', () => {
  it('boyut sınırı OKUMADAN önce; boş dosya; Blob olmayan girdi', async () => {
    const big = { size: ENGINE_LIMITS.maxInputBytes + 1, slice() { throw new Error('okunmamalı') }, arrayBuffer() { throw new Error('okunmamalı') } }
    await expect(probeBlob(big)).rejects.toMatchObject({ code: 'TOO_LARGE', params: { bytes: ENGINE_LIMITS.maxInputBytes + 1, maxBytes: ENGINE_LIMITS.maxInputBytes } })
    await expect(probeBlob(new Blob([]))).rejects.toMatchObject({ code: 'UNSUPPORTED_FORMAT', params: { detected: 'empty' } })
    await expect(probeBlob(null)).rejects.toMatchObject({ code: 'INVALID_OPTIONS' })
    await expect(probeBlob({ size: 10, arrayBuffer: () => Promise.reject(new Error('x')) })).rejects.toMatchObject({ code: 'DECODE_FAILED', params: { reason: 'read' } })
  })

  it('motorun kendi PNG çıktısı ve baş (512 KB) yetmeyen dosya', async () => {
    const { bytes } = await encodePng({ width: 33, height: 21, rgba: solid(33, 21, [1, 2, 3, 200]) })
    expect(await probeBlob(new Blob([bytes]))).toEqual({ type: 'png', width: 33, height: 21, bytes: bytes.length, hasAlpha: true, animated: false, icc: false, orientation: 1 })
    // SOF u 600 KB lık APP bölümlerinin ardında olan JPEG: ilk okuma yetmez, tamamı okunur
    const filler = []
    for (let i = 0; i < 10; i++) filler.push(0xff, 0xe5, 0xff, 0xff, ...new Array(65533).fill(0))
    const far = jpeg(1234, 567, { before: filler })
    expect(far.length).toBeGreaterThan(HEAD_BYTES)
    let reads = 0
    const blob = new Blob([far])
    const counted = { size: blob.size, slice: (a, b) => { reads += 1; return blob.slice(a, b) }, arrayBuffer: () => { reads += 1; return blob.arrayBuffer() } }
    expect(await probeBlob(counted)).toMatchObject({ type: 'jpeg', width: 1234, height: 567 })
    expect(reads).toBe(2)
    // animasyonu ilk karenin ardında (512 KB ötesinde) olan GIF
    const bigGif = gif(50, 50, { frames: 2, frameBytes: 600000 })
    expect(await probeBlob(new Blob([bigGif]))).toMatchObject({ type: 'gif', animated: true })
  })
})

describe('görsel motoru — gömülü renk profili (ICC): sRGB dönüşüm sayılmaz', () => {
  /** En küçük ICC profili: 128 baytlık başlık + etiket tablosu + 'desc' etiketi (v2 düz metin ya da v4 mluc). */
  function iccProfile(description, { v4 = false, extraTags = 0 } = {}) {
    const tags = 1 + extraTags
    const tableEnd = 128 + 4 + tags * 12
    let data
    if (v4) {
      const text = Array.from(description).flatMap((ch) => [0, ch.charCodeAt(0)])
      data = [...enc('mluc'), 0, 0, 0, 0, ...u32be(1), ...u32be(12), ...enc('enUS'), ...u32be(text.length), ...u32be(28), ...text]
    } else {
      data = [...enc('desc'), 0, 0, 0, 0, ...u32be(description.length + 1), ...enc(description), 0]
    }
    const out = new Array(128).fill(0)
    out.push(...u32be(tags))
    for (let k = 0; k < extraTags; k++) out.push(...enc('wtpt'), ...u32be(tableEnd), ...u32be(4))
    out.push(...enc('desc'), ...u32be(tableEnd), ...u32be(data.length))
    out.push(...data)
    return out
  }
  const app2 = (profile, seq = 1, count = 1) => [0xff, 0xe2, ...u16be(2 + 14 + profile.length), ...enc('ICC_PROFILE'), 0, seq, count, ...profile]

  it('JPEG: açıklaması "sRGB" olan profil (v2 ve v4) dönüşüm sayılmaz; başka / okunamayan profil sayılır', () => {
    expect(iccLooksSrgb(Uint8Array.from(iccProfile('sRGB IEC61966-2.1')), 0, 1e6)).toBe(true)
    expect(iccLooksSrgb(Uint8Array.from(iccProfile('sRGB', { v4: true })), 0, 1e6)).toBe(true)                // tarayıcı tuvalinin yazdığı
    expect(iccLooksSrgb(Uint8Array.from(iccProfile('Display P3', { v4: true })), 0, 1e6)).toBe(false)
    // Chrome'un Display P3 tuvalinden yazdığı ad: içinde "sRGB" GEÇER ama sRGB değildir (ad sRGB ile başlamalı)
    expect(iccLooksSrgb(Uint8Array.from(iccProfile('Display P3 Gamut with sRGB Transfer', { v4: true })), 0, 1e6)).toBe(false)
    expect(iccLooksSrgb(Uint8Array.from(iccProfile('  sRGB2014')), 0, 1e6)).toBe(true)
    expect(iccLooksSrgb(Uint8Array.from(iccProfile('Adobe RGB (1998)', { extraTags: 3 })), 0, 1e6)).toBe(false)
    expect(iccLooksSrgb(Uint8Array.from(iccProfile('sRGB built-in', { extraTags: 5 })), 0, 1e6)).toBe(true)
    for (const [name, opts, icc] of [['sRGB IEC61966-2.1', {}, false], ['sRGB', { v4: true }, false], ['Display P3', { v4: true }, true], ['ProPhoto RGB', {}, true]]) {
      expect(probeBytes(jpeg(10, 10, { before: app2(iccProfile(name, opts)) })).icc, name).toBe(icc)
    }
    // çok parçalı profil: karar ilk parçadan; sonraki parça onu değiştirmez
    expect(probeBytes(jpeg(10, 10, { before: [...app2(iccProfile('sRGB IEC61966-2.1'), 1, 2), ...app2([1, 2, 3, 4], 2, 2)] })).icc).toBe(false)
    expect(probeBytes(jpeg(10, 10, { before: [...app2(iccProfile('Display P3'), 1, 2), ...app2([1, 2, 3, 4], 2, 2)] })).icc).toBe(true)
    // ilk parçası olmayan / boş profil: bilinmiyor → dönüştürüldü sayılır
    expect(probeBytes(jpeg(10, 10, { before: app2([1, 2, 3, 4], 2, 2) })).icc).toBe(true)
    expect(probeBytes(jpeg(10, 10)).icc).toBe(false)
  })

  it('bozuk profil: ofset / uzunluk alanlarına güvenilmez (taşma yok, sonuç "sRGB değil")', () => {
    const good = iccProfile('sRGB IEC61966-2.1')
    const cut = Uint8Array.from(good.slice(0, 140))
    expect(iccLooksSrgb(cut, 0, 1e6)).toBe(false)
    const huge = good.slice()
    huge.splice(128, 4, 0xff, 0xff, 0xff, 0xff)                         // 4 milyar etiket
    expect(iccLooksSrgb(Uint8Array.from(huge), 0, 1e6)).toBe(true)      // ilk 64 etikete bakılır; 'desc' ilk sırada
    const far = good.slice()
    far.splice(128 + 4 + 4, 4, 0x7f, 0xff, 0xff, 0xff)                  // ofset dosyanın dışında
    expect(iccLooksSrgb(Uint8Array.from(far), 0, 1e6)).toBe(false)
    const long = good.slice()
    long.splice(128 + 4 + 12 + 8, 4, 0xff, 0xff, 0xff, 0xff)            // metin uzunluğu 4 milyar
    expect(iccLooksSrgb(Uint8Array.from(long), 0, 1e6)).toBe(true)      // eldeki baytlarla sınırlı okunur
    expect(iccLooksSrgb(new Uint8Array(0), 0, 10)).toBe(false)
    expect(iccLooksSrgb(Uint8Array.from(good), -5, 1e6)).toBe(false)
    // aralık sonu profilin ortasında: taşmadan "değil"
    expect(iccLooksSrgb(Uint8Array.from(good), 0, 150)).toBe(false)
  })

  it('PNG: iCCP parçasının ADI "sRGB …" ise dönüşüm sayılmaz; genel ad ("ICC Profile") sayılır', () => {
    expect(probeBytes(pngHeader(10, 10, 2, [['iCCP', [...enc('sRGB IEC61966-2.1'), 0, 0, 1, 2, 3]]])).icc).toBe(false)
    expect(probeBytes(pngHeader(10, 10, 2, [['iCCP', [...enc('ICC Profile'), 0, 0, 1, 2, 3]]])).icc).toBe(true)
    expect(probeBytes(pngHeader(10, 10, 2, [['iCCP', [...enc('Display P3'), 0, 0, 1, 2, 3]]])).icc).toBe(true)
    expect(probeBytes(pngHeader(10, 10, 2, [['sRGB', [0]]])).icc).toBe(false)
  })

  it('WebP: VP8X in ardındaki ICCP parçası sRGB ise dönüşüm sayılmaz; profil okunamıyorsa sayılır', () => {
    const vp8x = (flags, chunks) => {
      const body = [...enc('VP8X'), ...u32le(10), flags, 0, 0, 0, 99, 0, 0, 49, 0, 0, ...chunks]
      return Uint8Array.from([...enc('RIFF'), ...u32le(body.length + 4), ...enc('WEBP'), ...body])
    }
    const iccp = (profile) => [...enc('ICCP'), ...u32le(profile.length), ...profile, ...(profile.length & 1 ? [0] : [])]
    expect(probeBytes(vp8x(0x20, iccp(iccProfile('sRGB', { v4: true }))))).toMatchObject({ type: 'webp', width: 100, height: 50, icc: false })
    expect(probeBytes(vp8x(0x20, iccp(iccProfile('Display P3 Gamut with sRGB Transfer', { v4: true })))).icc).toBe(true)
    expect(probeBytes(vp8x(0x20, [...enc('VP8 '), ...u32le(4), 1, 2, 3, 4])).icc).toBe(true)        // bayrak var, parça yok
    expect(probeBytes(vp8x(0x20, [...enc('ICCP'), 0xff, 0xff, 0xff, 0x7f, 1, 2, 3])).icc).toBe(true) // yalan uzunluk: taşma yok
    expect(probeBytes(vp8x(0x00, iccp(iccProfile('Display P3')))).icc).toBe(false)                   // bayrak yok
  })
})
