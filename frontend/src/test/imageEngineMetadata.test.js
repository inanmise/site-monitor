import { describe, it, expect } from 'vitest'
import {
  buildExif, findJpegExif, findWebpExif, insertJpegExif, insertWebpExif, parseExif, planMetadata, readMetadata, readPngMeta,
  toExifDate, toIsoDate,
} from '../utils/imageEngine/metadata.js'
import { encodePng } from '../utils/imageEngine/pngWriter.js'
import { probeBytes } from '../utils/imageEngine/probe.js'
import { rng, solid } from './imageEngineKit.test.helpers.js'

const enc = (s) => new TextEncoder().encode(s)
const rational = (...pairs) => { const b = new Uint8Array(pairs.length * 8); const dv = new DataView(b.buffer); pairs.forEach(([n, d], i) => { dv.setUint32(i * 8, n, true); dv.setUint32(i * 8 + 4, d, true) }); return b }
/** İstanbul civarı örnek GPS girdileri (küçük-sonlu değerler). */
const GPS = [
  { tag: 0, type: 1, count: 4, value: Uint8Array.from([2, 3, 0, 0]) },
  { tag: 1, type: 2, count: 2, value: enc('N\u0000') },
  { tag: 2, type: 5, count: 3, value: rational([41, 1], [0, 1], [4932, 100]) },
  { tag: 3, type: 2, count: 2, value: enc('E\u0000') },
  { tag: 4, type: 5, count: 3, value: rational([28, 1], [58, 1], [4212, 100]) },
]
const jpegWith = (tiff) => {
  const base = Uint8Array.from([0xff, 0xd8, 0xff, 0xe0, 0, 16, 0x4a, 0x46, 0x49, 0x46, 0, 1, 1, 0, 0, 1, 0, 1, 0, 0,
    0xff, 0xc0, 0, 11, 8, 0, 4, 0, 6, 1, 1, 0x11, 0, 0xff, 0xd9])
  return tiff ? insertJpegExif(base, tiff) : base
}

/** Büyük-sonlu (Motorola) EXIF bloğu: fotoğraf makineleri çoğu kez böyle yazar. */
function bigEndianExif() {
  const out = new Uint8Array(210)
  const dv = new DataView(out.buffer)
  out.set([0x4d, 0x4d, 0, 42])
  dv.setUint32(4, 8)
  dv.setUint16(8, 4)                                                // 4 girdi
  const entry = (k, tag, type, count, value) => { const o = 10 + k * 12; dv.setUint16(o, tag); dv.setUint16(o + 2, type); dv.setUint32(o + 4, count); dv.setUint32(o + 8, value) }
  entry(0, 0x0112, 3, 1, 6 << 16)                                    // yön = 6 (SHORT, sola dayalı)
  entry(1, 0x8298, 2, 12, 100)                                       // telif → ofset 100
  entry(2, 0x8769, 4, 1, 120)                                        // EXIF IFD
  entry(3, 0x8825, 4, 1, 150)                                        // GPS IFD
  out.set(enc('(c) Deneme\u0000'), 100)
  dv.setUint16(120, 1)
  dv.setUint16(122, 0x9003); dv.setUint16(124, 2); dv.setUint32(126, 20); dv.setUint32(130, 180)
  out.set(enc('2026:11:14 09:30:00\u0000'), 180)
  dv.setUint16(150, 2)
  dv.setUint16(152, 2); dv.setUint16(154, 3); dv.setUint32(156, 1); dv.setUint16(160, 41)          // enlem (SHORT, deneme)
  dv.setUint16(164, 4); dv.setUint16(166, 3); dv.setUint32(168, 1); dv.setUint16(172, 29)          // boylam
  return out.slice(0, 200)
}

describe('görsel motoru — EXIF okuma / yazma', () => {
  it('gidiş-dönüş: telif + oluşturma zamanı + konum yazılır ve aynen okunur', () => {
    const tiff = buildExif({ copyrightBytes: enc('© 2026 Türkiye İş Bankası'), creation: '2026:11:14 09:30:00', gps: GPS })
    expect(tiff[0]).toBe(0x49)                                       // küçük-sonlu
    expect(tiff.length % 2).toBe(0)
    const back = parseExif(tiff)
    expect(new TextDecoder().decode(back.copyrightBytes)).toBe('© 2026 Türkiye İş Bankası')
    expect(back.creation).toBe('2026:11:14 09:30:00')
    expect(back.gps.map((e) => [e.tag, e.type, e.count])).toEqual(GPS.map((e) => [e.tag, e.type, e.count]))
    back.gps.forEach((e, i) => expect(Array.from(e.value)).toEqual(Array.from(GPS[i].value)))
  })

  it('yalnız istenen alanlar yazılır; hiçbiri yoksa blok yok', () => {
    expect(buildExif({})).toBeNull()
    expect(buildExif({ copyrightBytes: new Uint8Array(0), creation: 'geçersiz', gps: [] })).toBeNull()
    const onlyDate = parseExif(buildExif({ creation: '2026-11-14T09:30:00+03:00' }))
    expect(onlyDate).toEqual({ copyrightBytes: null, creation: '2026:11:14 09:30:00', gps: null })
    const onlyGps = parseExif(buildExif({ gps: GPS }))
    expect(onlyGps.copyrightBytes).toBeNull()
    expect(onlyGps.creation).toBeNull()
    expect(onlyGps.gps.length).toBe(5)
    const onlyCopy = buildExif({ copyrightBytes: enc('abc') })
    const parsed = parseExif(onlyCopy)
    expect({ ...parsed, copyrightBytes: Array.from(parsed.copyrightBytes) }).toEqual({ copyrightBytes: [97, 98, 99], creation: null, gps: null })
    expect(onlyCopy.length).toBeLessThan(40)                         // en küçük blok
  })

  it('büyük-sonlu kaynak okunur; yön etiketi hiçbir zaman taşınmaz (pikseller zaten döndürüldü)', () => {
    const src = bigEndianExif()
    const meta = parseExif(src)
    expect(new TextDecoder().decode(meta.copyrightBytes)).toBe('(c) Deneme')
    expect(meta.creation).toBe('2026:11:14 09:30:00')
    expect(meta.gps.map((e) => e.tag)).toEqual([2, 4])
    expect(Array.from(meta.gps[0].value)).toEqual([41, 0])           // SHORT 41 küçük-sonluya çevrildi
    const rebuilt = buildExif(meta)
    const jpeg = jpegWith(rebuilt)
    expect(probeBytes(jpeg).orientation).toBe(1)                     // yeniden yazılan blokta yön yok
    expect(probeBytes(jpegWith(src)).orientation).toBe(6)
    expect(parseExif(rebuilt).gps.map((e) => Array.from(e.value))).toEqual([[41, 0], [29, 0]])
  })

  it('DÜŞMANCA ofset / uzunluklar: döngüye girmez, atmaz, sınır dışını okumaz', () => {
    const good = buildExif({ copyrightBytes: enc('telif'), creation: '2026:11:14 09:30:00', gps: GPS })
    const rnd = rng(99)
    const t0 = Date.now()
    for (let round = 0; round < 3000; round++) {
      const b = Uint8Array.from(good)
      const hits = 1 + ((rnd() * 4) | 0)
      for (let k = 0; k < hits; k++) b[(rnd() * b.length) | 0] = (rnd() * 256) | 0
      const r = parseExif(b)                                         // atmamalı
      expect(r).toHaveProperty('gps')
      if (r.gps) expect(r.gps.length).toBeLessThanOrEqual(64)
    }
    expect(Date.now() - t0).toBeLessThan(4000)
    // kendine dönen / dışarı taşan IFD işaretçileri
    const loop = Uint8Array.from(good)
    new DataView(loop.buffer).setUint32(4, 8, true)
    const dv = new DataView(loop.buffer)
    for (let o = 10; o + 12 <= loop.length; o += 12) if (dv.getUint16(o, true) === 0x8769 || dv.getUint16(o, true) === 0x8825) dv.setUint32(o + 8, 8, true)
    expect(() => parseExif(loop)).not.toThrow()
    const outside = Uint8Array.from(good)
    new DataView(outside.buffer).setUint32(4, 0xfffffff0, true)
    expect(parseExif(outside)).toEqual({ copyrightBytes: null, creation: null, gps: null })
    // dev girdi sayısı ve dev uzunluk
    const huge = new Uint8Array(64)
    huge.set([0x49, 0x49, 42, 0, 8, 0, 0, 0, 0xff, 0xff])
    huge.set([0x98, 0x82, 2, 0, 0xff, 0xff, 0xff, 0x7f, 0, 0, 0, 0], 10)
    expect(parseExif(huge)).toEqual({ copyrightBytes: null, creation: null, gps: null })
    expect(parseExif(new Uint8Array(0))).toEqual({ copyrightBytes: null, creation: null, gps: null })
    expect(parseExif(enc('XXnot tiff at all'))).toEqual({ copyrightBytes: null, creation: null, gps: null })
    expect(parseExif(good, good.length - 4)).toEqual({ copyrightBytes: null, creation: null, gps: null })
  })

  it('tarih biçimleri', () => {
    expect(toExifDate('2026:11:14 09:30:00')).toBe('2026:11:14 09:30:00')
    expect(toExifDate('2026-11-14T09:30:00Z')).toBe('2026:11:14 09:30:00')
    expect(toExifDate('2026-11-14 09:30:00')).toBe('2026:11:14 09:30:00')
    expect(toExifDate('14.11.2026 09:30')).toBeNull()
    expect(toExifDate('')).toBeNull()
    expect(toExifDate(null)).toBeNull()
    expect(toIsoDate('2026:11:14 09:30:00')).toBe('2026-11-14T09:30:00')
    expect(toIsoDate('Sat, 14 Nov 2026')).toBe('Sat, 14 Nov 2026')
  })
})

describe('görsel motoru — dosyadan üstveri', () => {
  it('JPEG: EXIF APP1 bulunur, üç tür ve varlıkları okunur; EXIF yoksa hepsi yok', () => {
    const tiff = buildExif({ copyrightBytes: enc('© Biz'), creation: '2026:11:14 09:30:00', gps: GPS })
    const jpeg = jpegWith(tiff)
    expect(jpeg[2]).toBe(0xff)
    expect(jpeg[3]).toBe(0xe0)                                        // JFIF önce
    expect(jpeg[20]).toBe(0xff)
    expect(jpeg[21]).toBe(0xe1)                                       // EXIF hemen ardından
    expect(probeBytes(jpeg)).toMatchObject({ type: 'jpeg', width: 6, height: 4 })   // SOF hâlâ okunuyor
    const m = readMetadata(jpeg, 'jpeg')
    expect(m.present).toEqual({ copyright: true, creation: true, location: true })
    expect(m.copyright).toBe('© Biz')
    expect(m.creation).toBe('2026:11:14 09:30:00')
    expect(readMetadata(jpegWith(null), 'jpeg').present).toEqual({ copyright: false, creation: false, location: false })
    expect(findJpegExif(new Uint8Array([1, 2, 3]))).toBeNull()
    // JFIF'siz JPEG: EXIF SOI'den hemen sonra
    const bare = insertJpegExif(Uint8Array.from([0xff, 0xd8, 0xff, 0xdb, 0, 2, 0xff, 0xd9]), tiff)
    expect(bare[3]).toBe(0xe1)
    expect(readMetadata(bare, 'jpeg').present.location).toBe(true)
    // çok büyük blok / JPEG olmayan girdi: dosya olduğu gibi döner
    const notJpeg = Uint8Array.from([1, 2, 3, 4])
    expect(insertJpegExif(notJpeg, tiff)).toBe(notJpeg)
    expect(insertJpegExif(jpeg, new Uint8Array(70000))).toBe(jpeg)
  })

  it('PNG: tEXt / iTXt telif ve oluşturma zamanı; eXIf parçasından konum', async () => {
    const { bytes } = await encodePng({ width: 4, height: 4, rgba: solid(4, 4, [1, 2, 3, 255]) },
      { text: [{ keyword: 'Copyright', text: '© Örnek A.Ş.' }, { keyword: 'Creation Time', text: '2026-11-14T09:30:00' }, { keyword: 'Software', text: 'x' }] })
    const m = readMetadata(bytes, 'png')
    expect(m.copyright).toBe('© Örnek A.Ş.')
    expect(m.creation).toBe('2026-11-14T09:30:00')
    expect(m.present).toEqual({ copyright: true, creation: true, location: false })
    const utf = await encodePng({ width: 4, height: 4, rgba: solid(4, 4, [1, 2, 3, 255]) }, { text: [{ keyword: 'Copyright', text: 'İş ₺ 版權' }] })
    expect(readPngMeta(utf.bytes).copyright).toBe('İş ₺ 版權')
    expect(readMetadata(new Uint8Array(20), 'png').present).toEqual({ copyright: false, creation: false, location: false })
    // kesik dosya: parça uzunluğu dosyayı aşıyor → sessizce durur
    expect(() => readPngMeta(bytes.subarray(0, 60))).not.toThrow()
  })

  it('WebP: yalın dosya VP8X e sarılır, EXIF parçası eklenir ve geri okunur; başlık okuyucu ölçüyü aynı görür', () => {
    // VP8L (kayıpsız) yalın başlık: 5 × 3, saydamlık bayrağı açık
    const vp8l = new Uint8Array(12 + 8 + 6)
    vp8l.set(enc('RIFF'), 0); vp8l.set(enc('WEBP'), 8); vp8l.set(enc('VP8L'), 12)
    new DataView(vp8l.buffer).setUint32(16, 6, true)
    vp8l[20] = 0x2f
    const bits = (5 - 1) | ((3 - 1) << 14) | (1 << 28)
    new DataView(vp8l.buffer).setUint32(21, bits, true)
    new DataView(vp8l.buffer).setUint32(4, vp8l.length - 8, true)
    const pad = new Uint8Array(30); pad.set(vp8l)                      // başlık okuyucu ≥ 30 bayt ister
    new DataView(pad.buffer).setUint32(4, 22, true); new DataView(pad.buffer).setUint32(16, 10, true)
    expect(probeBytes(pad)).toMatchObject({ type: 'webp', width: 5, height: 3, hasAlpha: true })
    const tiff = buildExif({ copyrightBytes: enc('telif'), gps: GPS })
    const out = insertWebpExif(pad, tiff)
    expect(new TextDecoder().decode(out.subarray(12, 16))).toBe('VP8X')
    expect(out[20] & 0x08).toBe(0x08)                                  // EXIF bayrağı
    expect(out[20] & 0x10).toBe(0x10)                                  // saydamlık bayrağı korundu
    expect(new DataView(out.buffer).getUint32(4, true)).toBe(out.length - 8)
    expect(out.length % 2).toBe(0)
    expect(probeBytes(out)).toMatchObject({ type: 'webp', width: 5, height: 3, hasAlpha: true, animated: false })
    const range = findWebpExif(out)
    expect(parseExif(out, range.t, range.end).gps.length).toBe(5)
    expect(readMetadata(out, 'webp').present).toEqual({ copyright: true, creation: false, location: true })
    // zaten VP8X olan dosyada yalnız bayrak açılır ve parça eklenir
    const again = insertWebpExif(out.slice(0, out.length - (8 + tiff.length + (tiff.length & 1))), tiff)
    expect(readMetadata(again, 'webp').present.location).toBe(true)
    expect(insertWebpExif(new Uint8Array(40), tiff)).toBeNull()        // WebP değil
    expect(insertWebpExif(pad, null)).toBeNull()
    expect(findWebpExif(new Uint8Array(8))).toBeNull()
  })

  it('bozuk üstveri görseli engellemez: okuma hata vermez, "yok" sayılır', () => {
    const rnd = rng(5)
    for (let i = 0; i < 300; i++) {
      const junk = new Uint8Array(200)
      for (let k = 0; k < junk.length; k++) junk[k] = (rnd() * 256) | 0
      junk.set([0xff, 0xd8, 0xff, 0xe1, 0, 190, 0x45, 0x78, 0x69, 0x66, 0, 0, 0x49, 0x49, 42, 0, 8, 0, 0, 0])
      for (const type of ['jpeg', 'png', 'webp', 'gif', 'bmp', 'avif']) expect(() => readMetadata(junk, type)).not.toThrow()
    }
  })
})

describe('görsel motoru — üstveri planı (hangi tür hangi çıktıya yazılır)', () => {
  const meta = readMetadata(jpegWith(buildExif({ copyrightBytes: enc('© Biz'), creation: '2026:11:14 09:30:00', gps: GPS })), 'jpeg')
  const all = { copyright: true, creation: true, location: true }

  it('varsayılan: hiçbir şey taşınmaz', () => {
    for (const type of ['jpeg', 'png', 'webp']) {
      expect(planMetadata(meta, { copyright: false, creation: false, location: false }, type)).toEqual({ exif: null, text: [], kept: [], dropped: [] })
    }
    expect(planMetadata(meta, undefined, 'jpeg').kept).toEqual([])
  })

  it('JPEG / WebP: en küçük EXIF; PNG: metin parçaları, konum taşınmaz', () => {
    const j = planMetadata(meta, all, 'jpeg')
    expect(j.kept).toEqual(['copyright', 'creation', 'location'])
    expect(j.dropped).toEqual([])
    expect(parseExif(j.exif)).toMatchObject({ creation: '2026:11:14 09:30:00' })
    expect(planMetadata(meta, all, 'webp').kept).toEqual(['copyright', 'creation', 'location'])
    const p = planMetadata(meta, all, 'png')
    expect(p.exif).toBeNull()
    expect(p.text).toEqual([{ keyword: 'Copyright', text: '© Biz' }, { keyword: 'Creation Time', text: '2026-11-14T09:30:00' }])
    expect(p.kept).toEqual(['copyright', 'creation'])
    expect(p.dropped).toEqual(['location'])
    // yalnız istenen VE kaynakta bulunan türler
    expect(planMetadata(meta, { copyright: false, creation: true, location: false }, 'jpeg').kept).toEqual(['creation'])
    const noGps = readMetadata(jpegWith(buildExif({ copyrightBytes: enc('x') })), 'jpeg')
    expect(planMetadata(noGps, all, 'jpeg').kept).toEqual(['copyright'])
  })

  it('PNG kaynağın serbest biçimli tarihi EXIF e çevrilemezse JPEG çıktıda taşınamaz olarak bildirilir', async () => {
    const { bytes } = await encodePng({ width: 4, height: 4, rgba: solid(4, 4, [1, 2, 3, 255]) },
      { text: [{ keyword: 'Copyright', text: 'Biz' }, { keyword: 'Creation Time', text: 'Sat, 14 Nov 2026 09:30:00 GMT' }] })
    const m = readMetadata(bytes, 'png')
    const j = planMetadata(m, all, 'jpeg')
    expect(j.kept).toEqual(['copyright'])
    expect(j.dropped).toEqual(['creation'])
    expect(new TextDecoder().decode(parseExif(j.exif).copyrightBytes)).toBe('Biz')
    expect(planMetadata(m, all, 'png').kept).toEqual(['copyright', 'creation'])
  })
})
