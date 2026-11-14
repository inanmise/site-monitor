import { describe, it, expect } from 'vitest'
import { insertPngChunks, pngExifChunk, stripMetadata } from '../utils/imageEngine/strip.js'
import { buildExif, findJpegExif, findWebpExif, parseExif, readMetadata, readPngMeta } from '../utils/imageEngine/metadata.js'
import { encodePng, pngChunk } from '../utils/imageEngine/pngWriter.js'
import { exifOrientation, probeBytes } from '../utils/imageEngine/probe.js'
import { decodePng, pngChunks, rng, solid } from './imageEngineKit.test.helpers.js'

/**
 * Kayıpsız üstveri ayıklama (strip.js): "asla daha kötü" özgün dosyayı döndürdüğünde EXIF (GPS!), XMP, IPTC, yorum,
 * metin parçası ve dosya sonu ekleri SIZMAZ; görüntü baytları birebir aynı kalır; bozuk / beklenmedik yapı → null.
 */
const enc = (s) => new TextEncoder().encode(s)
const asc = (s) => [...s].map((c) => c.charCodeAt(0))
const rational = (...pairs) => { const b = new Uint8Array(pairs.length * 8); const dv = new DataView(b.buffer); pairs.forEach(([n, d], i) => { dv.setUint32(i * 8, n, true); dv.setUint32(i * 8 + 4, d, true) }); return b }
const GPS = [
  { tag: 1, type: 2, count: 2, value: enc('N\u0000') },
  { tag: 2, type: 5, count: 3, value: rational([41, 1], [0, 1], [4932, 100]) },
  { tag: 3, type: 2, count: 2, value: enc('E\u0000') },
  { tag: 4, type: 5, count: 3, value: rational([28, 1], [58, 1], [4212, 100]) },
]
const seg = (m, payload) => [0xff, m, ((payload.length + 2) >> 8) & 255, (payload.length + 2) & 255, ...payload]
const has = (hay, needle) => {
  outer: for (let i = 0; i + needle.length <= hay.length; i++) {
    for (let k = 0; k < needle.length; k++) if (hay[i + k] !== needle[k]) continue outer
    return true
  }
  return false
}

/** Gerçek yapılı JPEG: JFIF, EXIF (GPS + yön), XMP, IPTC, yorum, ICC, Adobe, DQT, SOF0, DHT, SOS + entropi (FF00 ve
 * RST dahil), ikinci tarama, EOI ve dosya sonu eki. `entropy` iki taramanın baytlarıdır. */
function richJpeg({ orientation = 6, trailer = true } = {}) {
  const tiff = buildExif({ gps: GPS, creation: '2026:11:14 09:30:00', orientation })
  const entropy1 = [0x12, 0x34, 0xff, 0x00, 0x56, 0xff, 0xd0, 0x78, 0xff, 0xff, 0x00, 0x9a, 0xbc]  // FF00 doldurma, RST0, dolgu
  const entropy2 = [0x01, 0x02, 0xff, 0x00, 0x03]
  const parts = [
    [0xff, 0xd8],
    seg(0xe0, [...asc('JFIF'), 0, 1, 1, 0, 0, 1, 0, 1, 0, 0]),
    seg(0xe1, [...asc('Exif'), 0, 0, ...tiff]),
    seg(0xe1, [...asc('http://ns.adobe.com/xap/1.0/'), 0, ...asc('<x:xmpmeta>GPS 41,0</x:xmpmeta>')]),
    seg(0xed, [...asc('Photoshop 3.0'), 0, ...asc('8BIM IPTC sehir: Istanbul')]),
    seg(0xfe, asc('gizli yorum')),
    seg(0xe2, [...asc('ICC_PROFILE'), 0, 1, 1, 9, 9, 9]),
    seg(0xe2, [...asc('MPF'), 0, 1, 2, 3]),
    seg(0xee, [...asc('Adobe'), 0, 100, 0, 0, 0, 0, 1]),
    seg(0xdb, [0, ...new Array(64).fill(1)]),
    seg(0xc0, [8, 0, 4, 0, 6, 1, 1, 0x11, 0]),
    seg(0xc4, [0, ...new Array(16).fill(0)]),
    seg(0xda, [1, 1, 0, 0, 0x3f, 0]),
    entropy1,
    seg(0xc4, [0x10, ...new Array(16).fill(0)]),
    seg(0xda, [1, 1, 0, 0, 0x3f, 0]),
    entropy2,
    [0xff, 0xd9],
  ]
  if (trailer) parts.push([...asc('SEFT gizli ek: GPS'), 0xff, 0xd8, 0xff, 0xe1])
  return { bytes: Uint8Array.from(parts.flat()), entropy1, entropy2 }
}

describe('görsel motoru — üstveri ayıklama: JPEG', () => {
  it('EXIF / XMP / IPTC / yorum / MPF / dosya sonu eki atılır; JFIF, ICC, Adobe, tablolar ve entropi BAYT BAYT kalır', () => {
    const { bytes, entropy1, entropy2 } = richJpeg()
    expect(readMetadata(bytes, 'jpeg').present.location).toBe(true)
    const s = stripMetadata(bytes, 'jpeg')
    expect(s.removed).toBe(true)
    expect(s.orientation).toBe(6)
    const out = s.bytes
    expect(findJpegExif(out)).toBeNull()
    expect(readMetadata(out, 'jpeg').present).toEqual({ copyright: false, creation: false, location: false })
    for (const gone of ['Exif', 'http://ns.adobe.com', 'Photoshop', 'gizli yorum', 'MPF', 'SEFT']) expect(has(out, asc(gone))).toBe(false)
    for (const kept of ['JFIF', 'ICC_PROFILE', 'Adobe']) expect(has(out, asc(kept))).toBe(true)
    expect(has(out, entropy1)).toBe(true)
    expect(has(out, entropy2)).toBe(true)
    expect(out.subarray(out.length - 2)).toEqual(Uint8Array.from([0xff, 0xd9]))
    // başlık hâlâ okunur (ölçü, tür)
    expect(probeBytes(out)).toMatchObject({ type: 'jpeg', width: 6, height: 4 })
    // ikinci kez ayıklamak bir şey değiştirmez (aynı dizi döner)
    const again = stripMetadata(out, 'jpeg')
    expect(again.removed).toBe(false)
    expect(again.bytes).toBe(out)
  })

  it('üstverisiz JPEG: hiçbir şey atılmaz, özgün dizi döner', () => {
    const plain = Uint8Array.from([0xff, 0xd8, ...seg(0xe0, [...asc('JFIF'), 0, 1, 1, 0, 0, 1, 0, 1, 0, 0]), ...seg(0xc0, [8, 0, 4, 0, 6, 1, 1, 0x11, 0]),
      ...seg(0xda, [1, 1, 0, 0, 0x3f, 0]), 0x11, 0x22, 0xff, 0xd9])
    const s = stripMetadata(plain, 'jpeg')
    expect(s).toEqual({ bytes: plain, removed: false, orientation: 1 })
    expect(s.bytes).toBe(plain)
  })

  it('bozuk / beklenmedik yapı → null (özgün dosya o zaman DÖNMEZ): EOI yok, kesik bölüm, ikinci SOI, yanlış imza', () => {
    const { bytes } = richJpeg({ trailer: false })
    expect(stripMetadata(bytes.subarray(0, bytes.length - 2), 'jpeg')).toBeNull()        // EOI yok (kesik dosya)
    expect(stripMetadata(bytes.subarray(0, 30), 'jpeg')).toBeNull()                      // bölüm ortasında biter
    const twoSoi = Uint8Array.from([0xff, 0xd8, 0xff, 0xd8, 0xff, 0xd9])
    expect(stripMetadata(twoSoi, 'jpeg')).toBeNull()
    const badLen = Uint8Array.from([0xff, 0xd8, 0xff, 0xe1, 0xff, 0xff, 1, 2, 3, 0xff, 0xd9])
    expect(stripMetadata(badLen, 'jpeg')).toBeNull()                                      // uzunluk dosyadan büyük
    const zeroLen = Uint8Array.from([0xff, 0xd8, 0xff, 0xe1, 0, 1, 0xff, 0xd9])
    expect(stripMetadata(zeroLen, 'jpeg')).toBeNull()
    expect(stripMetadata(Uint8Array.from([1, 2, 3, 4]), 'jpeg')).toBeNull()
  })
})

describe('görsel motoru — üstveri ayıklama: PNG', () => {
  it('tEXt / zTXt / iTXt / eXIf / tIME / dosya sonu eki atılır; IHDR, gAMA, PLTE, tRNS, IDAT, IEND aynı kalır', async () => {
    const base = (await encodePng({ width: 5, height: 3, rgba: solid(5, 3, [10, 200, 30, 128]) })).bytes
    const chunks = pngChunks(base)
    const ihdrEnd = 8 + 25
    const extra = [
      pngChunk('gAMA', [Uint8Array.from([0, 0, 0xb1, 0x8f])]),
      pngChunk('tEXt', [enc('Location\u000041.0,28.9')]),
      pngChunk('zTXt', [enc('Comment\u0000\u0000xx')]),
      pngChunk('iTXt', [enc('XML:com.adobe.xmp\u0000\u0000\u0000\u0000\u0000<x:xmpmeta/>')]),
      pngChunk('eXIf', [buildExif({ gps: GPS, orientation: 3 })]),
      pngChunk('tIME', [Uint8Array.from([7, 234, 11, 14, 9, 30, 0])]),
    ]
    let n = base.length + 9
    for (const c of extra) n += c.length
    const src = new Uint8Array(n)
    src.set(base.subarray(0, ihdrEnd), 0)
    let o = ihdrEnd
    for (const c of extra) { src.set(c, o); o += c.length }
    src.set(base.subarray(ihdrEnd), o)
    src.set(asc('trailer!!'), o + base.length - ihdrEnd)
    expect(readPngMeta(src)).not.toBeNull()
    const s = stripMetadata(src, 'png')
    expect(s.removed).toBe(true)
    expect(s.orientation).toBe(3)
    expect(s.insertAt).toBe(ihdrEnd)
    expect(pngChunks(s.bytes).map((c) => c.type)).toEqual(['IHDR', 'gAMA', ...chunks.map((c) => c.type).filter((t) => t !== 'IHDR')])
    expect(decodePng(s.bytes).rgba).toEqual(decodePng(base).rgba)
    for (const gone of ['Location', 'Comment', 'xmpmeta', 'eXIf', 'tIME', 'trailer']) expect(has(s.bytes, asc(gone))).toBe(false)
    // yalnız istenen parça geri eklenebilir (IHDR'den hemen sonra)
    const back = insertPngChunks(s.bytes, s.insertAt, [pngExifChunk(buildExif({ orientation: 3 }))])
    expect(pngChunks(back).map((c) => c.type).slice(0, 3)).toEqual(['IHDR', 'eXIf', 'gAMA'])
    expect(decodePng(back).rgba).toEqual(decodePng(base).rgba)
    expect(insertPngChunks(s.bytes, 5, [pngExifChunk(buildExif({ orientation: 3 }))])).toBeNull()   // geçersiz konum
  })

  it('bilinmeyen KRİTİK parça, IEND yok, bozuk uzunluk, IHDR ilk değil → null', async () => {
    const base = (await encodePng({ width: 2, height: 2, rgba: solid(2, 2, [1, 2, 3, 255]) })).bytes
    const crit = new Uint8Array(base.length + 12)
    crit.set(base.subarray(0, 33))
    crit.set(pngChunk('ZZZZ', [new Uint8Array(0)]), 33)
    crit.set(base.subarray(33), 45)
    expect(stripMetadata(crit, 'png')).toBeNull()
    expect(stripMetadata(base.subarray(0, base.length - 12), 'png')).toBeNull()
    const badLen = base.slice()
    badLen[33] = 0x7f
    expect(stripMetadata(badLen, 'png')).toBeNull()
    const noIhdr = base.slice()
    noIhdr.set(asc('IHDX'), 12)
    expect(stripMetadata(noIhdr, 'png')).toBeNull()
    expect(stripMetadata(base, 'png')).toEqual({ bytes: base, removed: false, orientation: 1, insertAt: 33 })
  })
})

describe('görsel motoru — üstveri ayıklama: WebP, GIF, AVIF, BMP', () => {
  const riff = (chunks) => {
    const body = chunks.flatMap(([type, data]) => [...asc(type), data.length & 255, (data.length >> 8) & 255, 0, 0, ...data, ...(data.length & 1 ? [0] : [])])
    const size = 4 + body.length
    return Uint8Array.from([...asc('RIFF'), size & 255, (size >> 8) & 255, (size >> 16) & 255, 0, ...asc('WEBP'), ...body])
  }
  it('WebP: EXIF / XMP parçaları atılır, RIFF boyu ve VP8X bayrakları düzeltilir; ICC ve görüntü parçası kalır', () => {
    const vp8 = [0x30, 0x01, 0x00, 0x9d, 0x01, 0x2a, 4, 0, 3, 0, 1, 2, 3]       // 13 bayt (tek → dolgu)
    const exif = [...asc('Exif'), 0, 0, ...buildExif({ gps: GPS, orientation: 8 })]
    const src = riff([
      ['VP8X', [0x20 | 0x08 | 0x04, 0, 0, 0, 3, 0, 0, 2, 0, 0]],
      ['ICCP', [1, 2, 3]],
      ['VP8 ', vp8],
      ['EXIF', exif],
      ['XMP ', asc('<x:xmpmeta>konum</x:xmpmeta>')],
    ])
    expect(findWebpExif(src)).not.toBeNull()
    const s = stripMetadata(src, 'webp')
    expect(s.removed).toBe(true)
    expect(s.orientation).toBe(8)
    const out = s.bytes
    expect(out.length % 2).toBe(0)
    expect(out[4] + (out[5] << 8) + (out[6] << 16) + out[7] * 16777216).toBe(out.length - 8)
    expect(out[20]).toBe(0x20)                                                 // yalnız ICC bayrağı kaldı
    expect(findWebpExif(out)).toBeNull()
    expect(has(out, asc('xmpmeta'))).toBe(false)
    expect(has(out, vp8)).toBe(true)
    expect(has(out, asc('ICCP'))).toBe(true)
    expect(stripMetadata(out, 'webp').removed).toBe(false)
    // bozuk: RIFF boyu dosyadan büyük / parça boyu taşar
    const bad = src.slice()
    bad[4] = 0xff; bad[5] = 0xff
    expect(stripMetadata(bad, 'webp')).toBeNull()
    const over = riff([['VP8 ', vp8]])
    over[16] = 0xff
    expect(stripMetadata(over, 'webp')).toBeNull()
  })

  it('GIF: yorum ve XMP uzantısı, sonlandırıcıdan sonrası atılır; NETSCAPE döngüsü ve kareler kalır', () => {
    const frame = [0x21, 0xf9, 4, 0, 5, 0, 0, 0, 0x2c, 0, 0, 0, 0, 1, 0, 1, 0, 0, 2, 2, 0x44, 1, 0]
    const loop = [0x21, 0xff, 11, ...asc('NETSCAPE2.0'), 3, 1, 0, 0, 0]
    const comment = [0x21, 0xfe, 5, ...asc('gizli'), 0]
    const xmp = [0x21, 0xff, 11, ...asc('XMP DataXMP'), 6, ...asc('konum!'), 0]
    const head = [...asc('GIF89a'), 1, 0, 1, 0, 0x80, 0, 0, 255, 255, 255, 0, 0, 0]
    const src = Uint8Array.from([...head, ...loop, ...comment, ...frame, ...xmp, ...frame, 0x3b, ...asc('ek')])
    const s = stripMetadata(src, 'gif')
    expect(s.removed).toBe(true)
    expect(Array.from(s.bytes)).toEqual([...head, ...loop, ...frame, ...frame, 0x3b])
    expect(stripMetadata(s.bytes, 'gif').removed).toBe(false)
    expect(stripMetadata(Uint8Array.from([...head, ...frame]), 'gif')).toBeNull()           // sonlandırıcı yok
    expect(stripMetadata(Uint8Array.from([...head, 0x99, 0x3b]), 'gif')).toBeNull()          // bilinmeyen blok
  })

  it('AVIF: Exif / XMP öğesi ya da uuid kutusu varsa ayıklanamaz (null → normal işlenir); yalnız görüntü öğesi → olduğu gibi', () => {
    const box = (type, payload) => { const n = 8 + payload.length; return [(n >>> 24) & 255, (n >> 16) & 255, (n >> 8) & 255, n & 255, ...asc(type), ...payload] }
    const infe = (id, type) => box('infe', [2, 0, 0, 0, 0, id, 0, 0, ...asc(type), 0])
    const avif = (items) => Uint8Array.from([...box('ftyp', [...asc('avif'), 0, 0, 0, 0, ...asc('mif1')]),
      ...box('meta', [0, 0, 0, 0, ...box('iinf', [0, 0, 0, 0, 0, items.length, ...items.flat()])])])
    const plain = avif([infe(1, 'av01')])
    expect(stripMetadata(plain, 'avif')).toEqual({ bytes: plain, removed: false, orientation: 1 })
    expect(stripMetadata(avif([infe(1, 'av01'), infe(2, 'Exif')]), 'avif')).toBeNull()
    expect(stripMetadata(avif([infe(1, 'av01'), infe(2, 'mime')]), 'avif')).toBeNull()
    expect(stripMetadata(Uint8Array.from([...plain, ...box('uuid', new Array(16).fill(7))]), 'avif')).toBeNull()
    expect(stripMetadata(Uint8Array.from(box('ftyp', asc('avif'))), 'avif')).toBeNull()   // meta yok: anlaşılamadı
    const bmp = Uint8Array.from([0x42, 0x4d, 1, 2, 3])
    expect(stripMetadata(bmp, 'bmp')).toEqual({ bytes: bmp, removed: false, orientation: 1 })
    expect(stripMetadata(bmp, 'tiff')).toBeNull()
  })

  it('düşmanca girdi: rastgele bozulmuş dosyalarda hiç atmaz, null ya da geçerli sonuç döner, hızlı biter', async () => {
    const samples = [
      ['jpeg', richJpeg().bytes],
      ['png', (await encodePng({ width: 9, height: 7, rgba: solid(9, 7, [5, 6, 7, 99]) })).bytes],
      ['gif', Uint8Array.from([...asc('GIF89a'), 1, 0, 1, 0, 0, 0, 0, 0x21, 0xfe, 2, 1, 2, 0, 0x2c, 0, 0, 0, 0, 1, 0, 1, 0, 0, 2, 2, 0x44, 1, 0, 0x3b])],
    ]
    const rnd = rng(77)
    const t0 = performance.now()
    for (const [type, good] of samples) {
      for (let k = 0; k < 400; k++) {
        const b = good.slice(0, Math.max(1, Math.floor(rnd() * (good.length + 1))))
        for (let m = 0; m < 3; m++) b[Math.floor(rnd() * b.length)] = Math.floor(rnd() * 256)
        const r = stripMetadata(b, type)
        if (r) {
          expect(r.bytes).toBeInstanceOf(Uint8Array)
          expect(r.bytes.length).toBeLessThanOrEqual(b.length)
          expect(typeof r.removed).toBe('boolean')
        }
      }
    }
    // uzunluk alanlarına güvenilmez: 1 MB'lık "entropi" taraması doğrusal
    const big = new Uint8Array(1 << 20)
    big.set([0xff, 0xd8, ...seg(0xc0, [8, 0, 4, 0, 6, 1, 1, 0x11, 0]), ...seg(0xda, [1, 1, 0, 0, 0x3f, 0])])
    for (let i = 40; i < big.length - 2; i += 2) big[i] = 0xff                  // FF 00 FF 00 …
    big.set([0xff, 0xd9], big.length - 2)
    expect(stripMetadata(big, 'jpeg').removed).toBe(false)
    expect(performance.now() - t0).toBeLessThan(5000)
  })

  it('EXIF yön okuyucusu ayıklanan blokla uyumlu (yön geri yazılabilir)', () => {
    const tiff = buildExif({ orientation: 5 })
    expect(exifOrientation(tiff, 0, tiff.length)).toBe(5)
    expect(parseExif(tiff)).toEqual({ copyrightBytes: null, creation: null, gps: null })    // yalnız yön: başka üstveri yok
  })
})
