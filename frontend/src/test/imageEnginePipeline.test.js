import { describe, it, expect, vi } from 'vitest'
import { assertDecodedWithinProbe, estimatePalettePng, makeCtx, processCore, runPng, supportCore } from '../utils/imageEngine/pipeline.js'
import { buildPalette } from '../utils/imageEngine/quantize.js'
import { encodePng } from '../utils/imageEngine/pngWriter.js'
import { buildExif, parseExif, findJpegExif, readMetadata } from '../utils/imageEngine/metadata.js'
import { exifOrientation } from '../utils/imageEngine/probe.js'
import { ENGINE_LIMITS, NOTE_CODES } from '../utils/imageEngine/errors.js'
import { MAX_SEARCH_ENCODES, SIMILARITY_TARGET, TARGET_QUALITY_FLOOR } from '../utils/imageEngine/search.js'
import {
  decodePng, fakeJpegBlob, fakePlatform, logoWithAlpha, photoLike, pngChunks, readFakeLossy, registerBlob, rng, solid,
} from './imageEngineKit.test.helpers.js'

// Piksel döngüleri kapsam (v8) ölçümüyle birkaç kat yavaşlar (CI: 2 vCPU): varsayılan 15 sn yerine geniş süre sınırı.
vi.setConfig({ testTimeout: 60_000 })

/**
 * İş akışı (pipeline.js) SAHTE platformla uçtan uca: çözme / kayıplı kodlama taklit edilir (imageEngineKit), geri kalan
 * her şey — başlık, ölçü planı, doğrusal ışıkta küçültme, nicemleme, PNG yazımı, benzerlik, kalite / hedef araması,
 * üstveri, "asla daha kötü" — gerçek koddur. Gerçek tarayıcı kodlayıcılarıyla aynı akış e2e/image-engine.spec.js'te.
 */
const pngBlob = async (rgba, w, h, opts) => new Blob([(await encodePng({ width: w, height: h, rgba }, opts)).bytes], { type: 'image/png' })
const run = (blob, options, platform = fakePlatform(), extra = {}) => processCore(blob, options, { platform, ...extra })
const bytesOf = async (blob) => new Uint8Array(await blob.arrayBuffer())
const enc = (s) => new TextEncoder().encode(s)

describe('görsel motoru — iş akışı: sonuç biçimi ve temel akış', () => {
  it('düz renkli saydam logo (PNG, gerçek renk kaynak) → çok daha küçük PALETLİ PNG; saydamlık korunur', async () => {
    const w = 160
    const h = 120
    const logo = logoWithAlpha(w, h)
    // kaynağı bilerek şişir: süzgeçsiz, hızlı sıkıştırma yerine gürültüsüz ama gerçek renk (RGBA) yaz
    const src = await pngBlob(logo, w, h, { level: 0 })
    const r = await run(src, { format: 'keep' })
    expect(r.type).toBe('png')
    expect(r.mime).toBe('image/png')
    expect(r.kept).toBe(false)
    expect([r.width, r.height]).toEqual([w, h])
    expect(r.bytes).toBe(r.blob.size)
    expect(r.bytes).toBeLessThan(src.size / 10)
    expect(r.colors).toBeGreaterThan(1)
    expect(r.colors).toBeLessThanOrEqual(256)
    expect(r.quality).toBeNull()
    // ≤ 256 renk: palet çözülen piksellerle birebir — ama kenardaki YARI SAYDAM piksellerin rengi tarayıcının
    // önçarpımlı çözmesinde yuvarlanmış olabilir: "birebir" denmez (benzerlik bilinmiyor + not)
    expect(r.similarity).toBeNull()
    expect(r.resized).toBe(false)
    expect(r.cropped).toBe(false)
    expect(r.notes).toEqual(['ALPHA_COLORS_ROUNDED'])
    expect(r.savingPct).toBeGreaterThan(90)
    expect(r.original).toEqual({ type: 'png', mime: 'image/png', width: w, height: h, bytes: src.size, hasAlpha: true, animated: false,
      metadata: { copyright: false, creation: false, location: false } })
    const out = decodePng(await bytesOf(r.blob))
    expect(out.colorType).toBe(3)
    for (let i = 0; i < logo.length; i += 4) {
      expect(out.rgba[i + 3]).toBe(logo[i + 3])
      if (logo[i + 3] > 0) expect([out.rgba[i], out.rgba[i + 1], out.rgba[i + 2]]).toEqual([logo[i], logo[i + 1], logo[i + 2]])
    }
  })

  it('fotoğraf (JPEG) otomatik kalite: daha küçük, benzerlik eşiğin üstünde, kodlama ≤ 8', async () => {
    const w = 240
    const h = 180
    const platform = fakePlatform()
    const src = fakeJpegBlob({ data: photoLike(w, h, 3), width: w, height: h }, { padBytes: 400000 })
    const r = await run(src, { format: 'keep', quality: 'auto' }, platform)
    expect(r.type).toBe('jpeg')
    expect(r.kept).toBe(false)
    expect(r.bytes).toBeLessThan(src.size)
    expect(r.similarity).toBeGreaterThanOrEqual(SIMILARITY_TARGET)
    expect(r.quality).toBeGreaterThanOrEqual(40)
    expect(r.quality).toBeLessThanOrEqual(95)
    expect(r.colors).toBeNull()
    expect(platform.stats.encodes).toBeLessThanOrEqual(MAX_SEARCH_ENCODES)
    expect(platform.stats.closed).toBe(1)                               // kaynak kapatıldı
    expect(platform.stats.sessionsClosed).toBe(platform.stats.prepared) // her kodlama oturumu kapatıldı
    expect((await readFakeLossy(r.blob)).quality).toBe(r.quality)
    // bir alt kalite eşiği geçmezdi (en düşük geçen kalite seçildi) — sahte kodlayıcıda benzerlik tek yönlü
    expect(r.notes).toEqual([])
  })

  it('sayısal kalite: tek kodlama, verilen kalite; benzerlik yine ölçülür', async () => {
    const platform = fakePlatform()
    const src = fakeJpegBlob({ data: photoLike(120, 90, 5), width: 120, height: 90 }, { padBytes: 100000 })
    const r = await run(src, { format: 'jpeg', quality: 60 }, platform)
    expect(r.quality).toBe(60)
    expect(platform.stats.encodes).toBe(1)
    expect(r.similarity).toBeGreaterThan(0.5)
    expect(r.similarity).toBeLessThanOrEqual(1)
  })

  it('ilerleme: aşamalar sözleşmedeki adlarla, 0..1 aralığında ve aşama içinde azalmadan', async () => {
    const calls = []
    const src = fakeJpegBlob({ data: photoLike(400, 300, 1), width: 400, height: 300 }, { padBytes: 300000 })
    await run(src, { resize: { method: 'scale', width: 100 }, format: 'jpeg' }, fakePlatform(), { onProgress: (p, f) => calls.push([p, f]) })
    const phases = [...new Set(calls.map((c) => c[0]))]
    expect(phases).toEqual(['decode', 'resize', 'search'])
    for (const [p, f] of calls) { expect(['decode', 'resize', 'encode', 'search']).toContain(p); expect(f).toBeGreaterThanOrEqual(0); expect(f).toBeLessThanOrEqual(1) }
    for (const phase of phases) {
      const fs = calls.filter((c) => c[0] === phase).map((c) => c[1])
      for (let i = 1; i < fs.length; i++) expect(fs[i]).toBeGreaterThanOrEqual(fs[i - 1])
      expect(fs[fs.length - 1]).toBe(1)
    }
    // dinleyici hatası işi durdurmaz
    await expect(run(src, {}, fakePlatform(), { onProgress: () => { throw new Error('x') } })).resolves.toBeTruthy()
  })
})

describe('görsel motoru — iş akışı: yeniden boyutlandırma', () => {
  const big = { data: photoLike(600, 400, 2), width: 600, height: 400 }
  const src = () => fakeJpegBlob(big, { padBytes: 500000 })

  it('scale / fit / cover: ölçüler; hiçbir zaman büyütme', async () => {
    const dims = async (resize) => { const r = await run(src(), { resize, format: 'jpeg', quality: 80 }); return [r.width, r.height, r.resized, r.cropped, r.notes] }
    expect(await dims({ method: 'scale', width: 300 })).toEqual([300, 200, true, false, []])
    expect(await dims({ method: 'scale', height: 100 })).toEqual([150, 100, true, false, []])
    expect(await dims({ method: 'fit', width: 200, height: 200 })).toEqual([200, 133, true, false, []])
    expect(await dims({ method: 'cover', width: 200, height: 200 })).toEqual([200, 200, true, true, ['SMART_CROP']])
    expect(await dims({ method: 'scale', width: 5000 })).toEqual([600, 400, false, false, ['NOT_UPSCALED']])
    expect(await dims({ method: 'fit', width: 5000, height: 5000 })).toEqual([600, 400, false, false, ['NOT_UPSCALED']])
    expect(await dims({ method: 'cover', width: 900, height: 900 })).toEqual([400, 400, true, true, ['NOT_UPSCALED', 'SMART_CROP']])
    const out = await readFakeLossy((await run(src(), { resize: { method: 'cover', width: 200, height: 100 }, format: 'jpeg', quality: 90 })).blob)
    expect([out.width, out.height]).toEqual([200, 100])
  })

  it('küçültme DOĞRUSAL ışıkta: 1 piksellik dama PNG 2× küçülünce ≈ 188', async () => {
    const w = 64
    const d = new Uint8ClampedArray(w * w * 4)
    for (let y = 0; y < w; y++) for (let x = 0; x < w; x++) { const v = ((x + y) & 1) ? 255 : 0; d.set([v, v, v, 255], (y * w + x) * 4) }
    const r = await run(await pngBlob(d, w, w), { resize: { method: 'scale', width: 32 }, format: 'png', quality: 100 })
    expect([r.width, r.height]).toEqual([32, 32])
    const out = decodePng(await bytesOf(r.blob))
    for (let y = 4; y < 28; y++) for (let x = 4; x < 28; x++) expect(out.rgba[(y * 32 + x) * 4]).toBe(188)
  })

  it('cover akıllı kırpma: ayrıntılı taraf pencerede kalır', async () => {
    const w = 400
    const h = 100
    const d = solid(w, h, [220, 220, 220, 255])
    const rnd = rng(9)
    for (let y = 0; y < h; y++) for (let x = 300; x < 400; x++) d.set([rnd() * 255, rnd() * 255, rnd() * 255, 255], (y * w + x) * 4)
    const r = await run(await pngBlob(d, w, h), { resize: { method: 'cover', width: 100, height: 100 }, format: 'png', quality: 100 })
    expect([r.width, r.height, r.cropped]).toEqual([100, 100, true])
    expect(r.notes).toContain('SMART_CROP')
    const out = decodePng(await bytesOf(r.blob))
    let colourful = 0
    for (let i = 0; i < out.rgba.length; i += 4) if (out.rgba[i] !== 220 || out.rgba[i + 1] !== 220) colourful += 1
    expect(colourful).toBeGreaterThan(9000)                               // pencerenin neredeyse tamamı ayrıntılı bölge
  })

  it('thumb: düz zeminde nesne → nesnenin TAMAMI kalır, gerekirse zemin rengiyle dolgu (THUMB_PADDED)', async () => {
    // 300 × 100 beyaza yakın zemin, ortada 200 × 60 kırmızı nesne; kare küçük resim: cover nesneyi keserdi
    const w = 300
    const h = 100
    const d = solid(w, h, [246, 246, 246, 255])
    for (let y = 20; y < 80; y++) for (let x = 50; x < 250; x++) d.set([200, 20, 20, 255], (y * w + x) * 4)
    const blob = await pngBlob(d, w, h)
    const thumb = await run(blob, { resize: { method: 'thumb', width: 120, height: 120 }, format: 'png', quality: 100, background: '#00ff00' })
    expect([thumb.width, thumb.height]).toEqual([120, 120])
    expect(thumb.notes).toContain('THUMB_PADDED')
    expect(thumb.cropped).toBe(false)
    const t = decodePng(await bytesOf(thumb.blob))
    const at = (x, y) => Array.from(t.rgba.subarray((y * 120 + x) * 4, (y * 120 + x) * 4 + 4))
    expect(at(2, 2)).toEqual([246, 246, 246, 255])                        // dolgu görselin KENDİ zemin rengi (seçenekteki yeşil değil)
    expect(at(60, 118)).toEqual([246, 246, 246, 255])
    expect(at(60, 60)).toEqual([200, 20, 20, 255])
    // nesnenin sol ve sağ ucu da görünür: her satırdaki kırmızı genişlik nesnenin tamamı (≈ 200 × 120/224)
    let red = 0
    for (let x = 0; x < 120; x++) if (at(x, 60)[0] === 200 && at(x, 60)[1] === 20) red += 1
    expect(red).toBeGreaterThan(95)
    expect(red).toBeLessThan(115)
    // aynı görselde cover nesneyi keser (karşılaştırma)
    const cover = await run(blob, { resize: { method: 'cover', width: 100, height: 100 }, format: 'png', quality: 100 })
    expect(cover.notes).toContain('SMART_CROP')
    expect(cover.notes).not.toContain('THUMB_PADDED')
    // fotoğrafta thumb = cover (nesne yok)
    const photo = await run(fakeJpegBlob({ data: photoLike(300, 100, 4, 60), width: 300, height: 100 }, { padBytes: 90000 }),
      { resize: { method: 'thumb', width: 80, height: 80 }, format: 'jpeg', quality: 80 })
    expect(photo.notes).toEqual(['SMART_CROP'])
    expect([photo.width, photo.height, photo.cropped]).toEqual([80, 80, true])
  })

  it('thumb: saydam zeminli logo saydam dolguyla büyür; JPEG e giderken zemin `background` olur', async () => {
    const logo = logoWithAlpha(200, 80)
    const blob = await pngBlob(logo, 200, 80)
    const r = await run(blob, { resize: { method: 'thumb', width: 64, height: 64 }, format: 'png' })
    expect([r.width, r.height]).toEqual([64, 64])
    const out = decodePng(await bytesOf(r.blob))
    expect(out.rgba[3]).toBe(0)                                           // köşe saydam
    expect(out.rgba[(32 * 64 + 32) * 4 + 3]).toBe(255)                    // orta opak
    const j = await run(blob, { resize: { method: 'thumb', width: 64, height: 64 }, format: 'jpeg', quality: 90, background: '#102030' })
    expect(j.notes).toContain('ALPHA_FLATTENED')
    const px = await readFakeLossy(j.blob)
    expect(Math.abs(px.data[0] - 0x10)).toBeLessThan(6)
    expect(Math.abs(px.data[2] - 0x30)).toBeLessThan(6)
    expect(px.data[3]).toBe(255)
  })
})

describe('görsel motoru — iş akışı: biçimler', () => {
  it('saydam PNG → JPEG: zemine bindirilir (ALPHA_FLATTENED); opak görselde not yok', async () => {
    const logo = logoWithAlpha(64, 64)
    const r = await run(await pngBlob(logo, 64, 64), { format: 'jpeg', quality: 95, background: '#0000ff' })
    expect(r.type).toBe('jpeg')
    expect(r.notes).toEqual(['ALPHA_FLATTENED'])
    const px = await readFakeLossy(r.blob)
    expect(Array.from(px.data.subarray(0, 4)).map((v, i) => (i < 3 ? Math.round(v / 10) : v))).toEqual([0, 0, 26, 255])    // köşe: mavi zemin
    expect(r.original.hasAlpha).toBe(true)
    const o = await run(await pngBlob(photoLike(64, 64, 1), 64, 64), { format: 'jpeg', quality: 80 })
    expect(o.notes).toEqual([])
    expect(o.original.hasAlpha).toBe(false)
  })

  it('WebP kodlanamıyorsa: WEBP_UNAVAILABLE + saydamlığı koruyan yedek biçim', async () => {
    const noWebp = fakePlatform({ webp: false })
    const logoBlob = await pngBlob(logoWithAlpha(64, 64), 64, 64, { level: 0 })
    const a = await run(logoBlob, { format: 'webp' }, noWebp)
    expect(a.type).toBe('png')
    expect(a.notes).toEqual(['WEBP_UNAVAILABLE', 'ALPHA_COLORS_ROUNDED'])   // logonun yumuşatılmış kenarı yarı saydam
    const b = await run(fakeJpegBlob({ data: photoLike(64, 64, 1), width: 64, height: 64 }, { padBytes: 50000 }), { format: 'webp' }, noWebp)
    expect(b.type).toBe('jpeg')
    expect(b.notes).toEqual(['WEBP_UNAVAILABLE'])
    const c = await run(logoBlob, { format: 'webp', quality: 80 }, fakePlatform())
    expect(c.type).toBe('webp')
    expect(c.mime).toBe('image/webp')
    expect(c.notes).toEqual([])
    expect((await readFakeLossy(c.blob)).data[3]).toBe(0)                 // WebP saydamlığı taşır
  })

  it('"en küçük biçim": saydam logo PNG kalır (JPEG aday değil); fotoğrafta eşiği geçen EN KÜÇÜK aday seçilir', async () => {
    const platform = fakePlatform()
    const logo = await run(await pngBlob(logoWithAlpha(128, 128), 128, 128, { level: 0 }), { format: 'smallest' }, platform)
    expect(logo.type).toBe('png')
    expect(logo.similarity).toBeNull()
    expect(logo.notes).toEqual(['ALPHA_COLORS_ROUNDED'])
    // Fotoğraf: üç biçim ayrı ayrı üretilir; "smallest" eşiği geçenlerin en küçüğünü vermelidir (sahte kodlayıcıda
    // hangisinin kazandığı önemli değil — kural önemli; gerçek kodlayıcılarla sonuç e2e'de ölçülür)
    const photoBlob = () => pngBlob(photoLike(200, 150, 6, 30), 200, 150)
    const each = {}
    for (const format of ['jpeg', 'png', 'webp']) each[format] = await run(await photoBlob(), { format }, fakePlatform())
    const photo = await run(await photoBlob(), { format: 'smallest' }, fakePlatform())
    // kayıplı adaylar her zaman yarışır; PNG yalnız eşiği tutturuyorsa (search.js pickSmallest)
    const eligible = (list) => list.filter((r) => r.type !== 'png' || r.similarity >= SIMILARITY_TARGET)
    const all = eligible(Object.values(each))
    expect(photo.bytes).toBe(Math.min(...all.map((r) => r.bytes)))
    expect(photo.type).toBe(all.find((r) => r.bytes === photo.bytes).type)
    expect(photo.bytes).toBeLessThan(photo.original.bytes)
    expect(photo.notes).not.toContain('WEBP_UNAVAILABLE')
    // WebP kodlanamıyorsa aday değildir ve bu bildirilir
    const noWebp = await run(await photoBlob(), { format: 'smallest' }, fakePlatform({ webp: false }))
    expect(['jpeg', 'png']).toContain(noWebp.type)
    expect(noWebp.bytes).toBe(Math.min(...eligible([each.jpeg, each.png]).map((r) => r.bytes)))
    expect(noWebp.notes).toContain('WEBP_UNAVAILABLE')
  })

  it('PNG: çok renkli görsel paletle kabul edilemeyecek kadar bozulacaksa GERÇEK RENK kalır (TRUECOLOR_KEPT)', async () => {
    const w = 96
    const h = 96
    const rnd = rng(31)
    const d = new Uint8ClampedArray(w * h * 4)
    for (let i = 0; i < d.length; i += 4) d.set([rnd() * 256, rnd() * 256, rnd() * 256, 255], i)      // tam gürültü: 256 renk yetmez
    const r = await run(fakeJpegBlob({ data: d, width: w, height: h }, { padBytes: 10 }), { format: 'png' })
    expect(r.type).toBe('png')
    expect(r.notes).toEqual(['TRUECOLOR_KEPT'])
    expect(r.colors).toBeNull()
    expect(r.similarity).toBe(1)
    const out = decodePng(await bytesOf(r.blob))
    expect(out.colorType).toBe(2)
    expect(Buffer.from(out.rgba).equals(Buffer.from(d))).toBe(true)
    // sayısal kalite < 100: palet ZORLANIR; 100: kayıpsız
    const forced = await run(fakeJpegBlob({ data: d, width: w, height: h }), { format: 'png', quality: 50 })
    expect(forced.colors).toBeLessThanOrEqual(64)
    expect(forced.notes).toEqual([])
    expect(forced.similarity).toBeLessThan(1)
    const lossless = await run(fakeJpegBlob({ data: d, width: w, height: h }), { format: 'png', quality: 100 })
    expect(lossless.notes).toEqual(['TRUECOLOR_KEPT'])
  })

  it('PNG: yumuşak yapay geçişte kayıpsız gerçek renk titreşimli paletten KÜÇÜKSE o döner', async () => {
    const w = 256
    const h = 128
    const d = new Uint8ClampedArray(w * h * 4)
    for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) d.set([x, (x + y) & 255, y * 2, 255], (y * w + x) * 4)
    const r = await run(fakeJpegBlob({ data: d, width: w, height: h }, { padBytes: 100000 }), { format: 'png' })
    expect(r.notes).toEqual(['TRUECOLOR_KEPT'])
    expect(r.bytes).toBeLessThan(2000)
    expect(Buffer.from(decodePng(await bytesOf(r.blob)).rgba).equals(Buffer.from(d))).toBe(true)
  })

  it('GIF / BMP kaynak "keep" ile PNG e, AVIF / WebP kaynak WebP e gider; animasyon ve renk profili notları', async () => {
    // GIF yarı saydamlık taşımaz (piksel ya opak ya tam saydam): sahte pikseller de öyle
    const img = { data: Uint8ClampedArray.from(logoWithAlpha(48, 48), (v, i) => ((i & 3) === 3 ? (v >= 128 ? 255 : 0) : v)), width: 48, height: 48 }
    // iki kareli GIF başlığı (pikseller sahte platformdan)
    const gif = [0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 48, 0, 48, 0, 0, 0, 0]
    // kare verisi şişirilir (alt bloklar): sonlandırıcıdan SONRAKİ bayt dosya eki sayılır ve ayıklanırdı
    for (let f = 0; f < 2; f++) {
      gif.push(0x2c, 0, 0, 0, 0, 48, 0, 48, 0, 0, 2)
      for (let k = 0; k < 40; k++) gif.push(255, ...new Array(255).fill(0))
      gif.push(0)
    }
    gif.push(0x3b)
    const g = await run(registerBlob(Uint8Array.from(gif), img, 'image/gif'), { format: 'keep' })
    expect(g.type).toBe('png')
    expect(g.original.type).toBe('gif')
    expect(g.original.animated).toBe(true)
    expect(g.notes).toEqual(['ANIMATION_DROPPED'])
    // ICC profilli JPEG: profil sRGB ye çevrildi notu
    const icc = await run(fakeJpegBlob({ data: photoLike(64, 64, 1), width: 64, height: 64 }, { padBytes: 60000, icc: true }), { quality: 80 })
    expect(icc.notes).toEqual(['COLOR_PROFILE_CONVERTED'])
    // EXIF yönü 6: tarayıcı döndürür → not; ölçüler çözülmüş (dönmüş) görselinkidir
    const orient = Uint8Array.from([0x49, 0x49, 42, 0, 8, 0, 0, 0, 1, 0, 0x12, 0x01, 3, 0, 1, 0, 0, 0, 6, 0, 0, 0, 0, 0, 0, 0])
    const rot = await run(fakeJpegBlob({ data: photoLike(40, 60, 1), width: 40, height: 60 }, { padBytes: 60000, exifTiff: orient }), { quality: 80 })
    expect(rot.notes).toEqual(['ORIENTATION_APPLIED'])
    expect([rot.width, rot.height]).toEqual([40, 60])
  })
})

describe('görsel motoru — iş akışı: "asla daha kötü"', () => {
  it('aynı biçim, ölçü değişmedi, çıktı küçülmedi → ÖZGÜN dosya (kept, KEPT_ORIGINAL, tasarruf 0)', async () => {
    const img = { data: photoLike(120, 90, 8), width: 120, height: 90 }
    const tiny = fakeJpegBlob(img, { padBytes: 0 })                       // 39 baytlık "özgün": hiçbir kodlama bunu geçemez
    const r = await run(tiny, { format: 'keep' })
    expect(r.kept).toBe(true)
    expect(r.blob).toBeNull()                                             // çekirdek Blob taşımaz; index.js özgün Blob u koyar
    expect(r.type).toBe('jpeg')
    expect(r.bytes).toBe(tiny.size)
    expect([r.width, r.height]).toEqual([120, 90])
    expect(r.savingPct).toBe(0)
    expect(r.notes).toEqual(['KEPT_ORIGINAL'])
    expect(r.resized).toBe(false)
    expect(r.quality).toBeNull()
    expect(r.similarity).toBe(1)
    // en küçük biçim de özgünü geçemiyorsa özgün kalır
    expect((await run(tiny, { format: 'smallest' })).kept).toBe(true)
    // AÇIKÇA başka biçim istenirse büyük de olsa dönüştürülür
    const conv = await run(tiny, { format: 'png' })
    expect(conv.kept).toBe(false)
    expect(conv.type).toBe('png')
    expect(conv.savingPct).toBe(0)
    // yeniden boyutlandırma istenirse çıktı döner (özgünden büyük olsa da)
    const resized = await run(tiny, { resize: { method: 'scale', width: 60 }, quality: 90 })
    expect(resized.kept).toBe(false)
    expect(resized.width).toBe(60)
  })

  it('animasyonlu GIF küçülmüyorsa özgün (animasyonuyla) kalır: ANIMATION_DROPPED yazılmaz', async () => {
    const img = { data: solid(8, 8, [1, 2, 3, 255]), width: 8, height: 8 }
    const gif = [0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 8, 0, 8, 0, 0, 0, 0]
    for (let f = 0; f < 2; f++) gif.push(0x2c, 0, 0, 0, 0, 8, 0, 8, 0, 0, 2, 1, 0, 0)
    gif.push(0x3b)
    const r = await run(registerBlob(Uint8Array.from(gif), img, 'image/gif'), {})
    expect(r.kept).toBe(true)
    expect(r.type).toBe('gif')
    expect(r.mime).toBe('image/gif')
    expect(r.notes).toEqual(['KEPT_ORIGINAL'])
  })
})

describe('görsel motoru — iş akışı: hedef boyut', () => {
  const img = { data: photoLike(400, 300, 12, 40), width: 400, height: 300 }
  const src = () => fakeJpegBlob(img, { padBytes: 900000 })

  it('önce KALİTE düşer (ölçü aynı kalır)', async () => {
    // Hedef = kalite 60'ın boyutu: kalite 90'dan başlayan iş, tabana (35) inmeden yalnız kaliteyle sığar
    const at60 = await run(src(), { format: 'jpeg', quality: 60 })
    const at90 = await run(src(), { format: 'jpeg', quality: 90 })
    expect(at60.bytes).toBeLessThan(at90.bytes)
    const target = at60.bytes
    const platform = fakePlatform()
    const r = await run(src(), { format: 'jpeg', quality: 90, targetBytes: target }, platform)
    expect(r.bytes).toBeLessThanOrEqual(target)
    expect([r.width, r.height]).toEqual([400, 300])
    expect(r.resized).toBe(false)
    expect(r.quality).toBeLessThan(90)
    expect(r.quality).toBeGreaterThanOrEqual(TARGET_QUALITY_FLOOR)
    expect(r.quality).toBeGreaterThanOrEqual(50)                           // sığan EN YÜKSEK kalite aranır (taban değil)
    expect(r.notes).toEqual([])
    expect(platform.stats.encodes).toBeLessThan(12)
  })

  it('kalite yetmezse ÖLÇÜ küçülür (oran korunur, kenar ≥ 16); sonuç hedefin altında', async () => {
    const r = await run(src(), { format: 'jpeg', targetBytes: 6000 })
    expect(r.bytes).toBeLessThanOrEqual(6000)
    expect(r.width).toBeLessThan(400)
    expect(r.resized).toBe(true)
    expect(Math.abs(r.width / r.height - 4 / 3)).toBeLessThan(0.05)
    expect(Math.min(r.width, r.height)).toBeGreaterThanOrEqual(16)
    expect(r.notes).toEqual([])
    const out = await readFakeLossy(r.blob)
    expect([out.width, out.height]).toEqual([r.width, r.height])
  })

  it('ulaşılamayan hedef: en iyi çaba + TARGET_NOT_REACHED; adım sayısı sınırlı', async () => {
    const platform = fakePlatform()
    const r = await run(src(), { format: 'jpeg', targetBytes: 20 }, platform)
    expect(r.notes).toContain('TARGET_NOT_REACHED')
    expect(r.bytes).toBeGreaterThan(20)
    expect(Math.min(r.width, r.height)).toBeGreaterThanOrEqual(16)
    expect(r.bytes).toBeLessThan(3000)
    expect(platform.stats.encodes).toBeLessThan(60)
    // zaten hedefin altındaysa hiçbir şey değişmez
    const easy = await run(src(), { format: 'jpeg', targetBytes: 5_000_000 })
    expect(easy.notes).toEqual([])
    expect(easy.resized).toBe(false)
  })

  it('PNG: önce palet boyu, sonra ölçü', async () => {
    const blob = await pngBlob(photoLike(200, 150, 3, 30), 200, 150)
    const full = await run(blob, { format: 'png', quality: 99 })
    expect(full.colors).toBeGreaterThan(128)
    const fewer = await run(blob, { format: 'png', quality: 99, targetBytes: Math.round(full.bytes * 0.7) })
    expect(fewer.bytes).toBeLessThanOrEqual(Math.round(full.bytes * 0.7))
    expect([fewer.width, fewer.height]).toEqual([200, 150])
    expect(fewer.colors).toBeLessThan(full.colors)
    const tiny = await run(blob, { format: 'png', quality: 99, targetBytes: 1500 })
    expect(tiny.bytes).toBeLessThanOrEqual(1500)
    expect(tiny.width).toBeLessThan(200)
    expect(decodePng(await bytesOf(tiny.blob)).width).toBe(tiny.width)
  })
})

describe('görsel motoru — iş akışı: üstveri', () => {
  const rational = (...pairs) => { const b = new Uint8Array(pairs.length * 8); const dv = new DataView(b.buffer); pairs.forEach(([n, d], i) => { dv.setUint32(i * 8, n, true); dv.setUint32(i * 8 + 4, d, true) }); return b }
  const gps = [{ tag: 1, type: 2, count: 2, value: enc('N\u0000') }, { tag: 2, type: 5, count: 3, value: rational([41, 1], [0, 1], [0, 1]) },
    { tag: 3, type: 2, count: 2, value: enc('E\u0000') }, { tag: 4, type: 5, count: 3, value: rational([29, 1], [0, 1], [0, 1]) }]
  const tiff = buildExif({ copyrightBytes: enc('© Biz'), creation: '2026:11:14 09:30:00', gps })
  const img = { data: photoLike(100, 80, 3), width: 100, height: 80 }
  const src = () => fakeJpegBlob(img, { padBytes: 200000, exifTiff: tiff })

  it('varsayılan: üstveri ATILIR; hangi türlerin bulunduğu yine bildirilir', async () => {
    const r = await run(src(), { quality: 80 })
    expect(r.original.metadata).toEqual({ copyright: true, creation: true, location: true })
    expect(r.notes).toEqual([])
    expect(new Uint8Array(await r.blob.arrayBuffer()).subarray(0, 4)).toEqual(Uint8Array.from([0x46, 0x4b, 0x45, 0x21]))   // çıktıya dokunulmadı
  })

  it('JPEG → JPEG: yalnız istenen türler en küçük EXIF bloğunda; boyut ve not doğru', async () => {
    // sahte kodlayıcının çıktısı JPEG değildir: EXIF eklenemez → dürüstçe METADATA_NOT_PRESERVED
    const plain = await run(src(), { quality: 80, preserve: { copyright: true, creation: false, location: true } })
    expect(plain.notes).toEqual(['METADATA_NOT_PRESERVED'])
    // gerçek JPEG baytı veren bir kodlayıcıyla: blok eklenir
    const platform = fakePlatform()
    const inner = platform.prepare
    platform.prepare = async (image) => {
      const s = await inner(image)
      return { ...s, encode: async () => new Blob([Uint8Array.from([0xff, 0xd8, 0xff, 0xe0, 0, 16, 0x4a, 0x46, 0x49, 0x46, 0, 1, 1, 0, 0, 1, 0, 1, 0, 0, 0xff, 0xd9])], { type: 'image/jpeg' }) }
    }
    platform.decodeRegions = async (_b, rects) => rects.map((r) => new Uint8ClampedArray(r.w * r.h * 4).fill(255))
    const r = await run(src(), { quality: 80, preserve: { copyright: true, creation: false, location: true } }, platform)
    expect(r.notes).toEqual(['METADATA_PRESERVED'])
    const out = await bytesOf(r.blob)
    expect(r.bytes).toBe(out.length)
    expect(r.blob.type).toBe('image/jpeg')
    const range = findJpegExif(out)
    const back = parseExif(out, range.t, range.end)
    expect(new TextDecoder().decode(back.copyrightBytes)).toBe('© Biz')
    expect(back.creation).toBeNull()                                      // istenmedi: taşınmadı
    expect(back.gps.map((e) => e.tag)).toEqual([1, 2, 3, 4])
  })

  it('→ PNG: telif ve oluşturma zamanı metin parçası olur; konum taşınmaz (ikisi de bildirilir)', async () => {
    const r = await run(src(), { format: 'png', preserve: { copyright: true, creation: true, location: true } })
    expect(r.notes).toEqual(expect.arrayContaining(['METADATA_PRESERVED', 'METADATA_NOT_PRESERVED']))
    const chunks = decodePng(await bytesOf(r.blob)).chunks.filter((c) => c.type === 'tEXt')
    const text = chunks.map((c) => Array.from(c.data, (v) => String.fromCharCode(v)).join(''))
    expect(text).toEqual(['Copyright\u0000© Biz', 'Creation Time\u00002026-11-14T09:30:00'])
    // kaynakta olmayan tür istenirse not yok
    const none = await run(fakeJpegBlob(img, { padBytes: 200000 }), { format: 'png', preserve: { copyright: true, creation: true, location: true } })
    expect(none.notes.filter((n) => n.startsWith('METADATA'))).toEqual([])
  })
})

describe('görsel motoru — iş akışı: sınırlar, hatalar, iptal', () => {
  it('boyut / tür / ölçü sınırı ÇÖZMEDEN önce (platform.decode hiç çağrılmaz)', async () => {
    const platform = fakePlatform()
    await expect(run({ size: ENGINE_LIMITS.maxInputBytes + 1 }, {}, platform)).rejects.toMatchObject({ code: 'TOO_LARGE' })
    await expect(run(new Blob([]), {}, platform)).rejects.toMatchObject({ code: 'UNSUPPORTED_FORMAT', params: { detected: 'empty' } })
    await expect(run(new Blob(['<svg xmlns="http://www.w3.org/2000/svg"></svg>']), {}, platform)).rejects.toMatchObject({ code: 'UNSUPPORTED_FORMAT', params: { detected: 'svg' } })
    await expect(run(null, {}, platform)).rejects.toMatchObject({ code: 'INVALID_OPTIONS' })
    await expect(run(new Blob([1]), { format: 'tiff' }, platform)).rejects.toMatchObject({ code: 'INVALID_OPTIONS', params: { field: 'format' } })
    // 20000 × 20000 "PNG": başlık yeter
    const bomb = Uint8Array.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 13, 0x49, 0x48, 0x44, 0x52, 0, 0, 0x4e, 0x20, 0, 0, 0x4e, 0x20, 8, 6, 0, 0, 0, 0, 0, 0, 0,
      0, 0, 0, 0, 0x49, 0x44, 0x41, 0x54, 0, 0, 0, 0])
    await expect(run(new Blob([bomb]), {}, platform)).rejects.toMatchObject({ code: 'TOO_MANY_PIXELS', params: { width: 20000, height: 20000 } })
    expect(platform.stats.decodes).toBe(0)
  })

  it('çözme hatası EngineError olarak çıkar; başlık yalan söylediyse (çözülen ölçü sınırı aşıyor) kaynak yine kapatılır', async () => {
    await expect(run(fakeJpegBlob({ data: solid(4, 4, [1, 1, 1, 255]), width: 4, height: 4 }), {}, fakePlatform({ failDecode: true })))
      .rejects.toMatchObject({ code: 'DECODE_FAILED' })
    const platform = fakePlatform()
    const liar = fakeJpegBlob({ data: new Uint8ClampedArray(4), width: 40000, height: 1 })      // başlık 40000 × 1 diyor…
    await expect(run(liar, {}, platform)).rejects.toMatchObject({ code: 'TOO_MANY_PIXELS' })
    // …başlık küçük, çözülen dev: sınır çözmeden sonra da denetlenir ve kaynak kapatılır
    const p2 = fakePlatform()
    const inner = p2.decode
    p2.decode = async (b) => { const s = await inner(b); return { ...s, width: 31000, height: 10 } }
    await expect(run(fakeJpegBlob({ data: solid(4, 4, [1, 1, 1, 255]), width: 4, height: 4 }), {}, p2)).rejects.toMatchObject({ code: 'TOO_MANY_PIXELS' })
    expect(p2.stats.closed).toBe(1)
  })

  it('iptal: başlamadan önce ve iş sürerken → CANCELLED; kaynak ve oturumlar kapatılır', async () => {
    const pre = new AbortController()
    pre.abort()
    await expect(run(fakeJpegBlob({ data: photoLike(50, 50, 1), width: 50, height: 50 }), {}, fakePlatform(), { signal: pre.signal }))
      .rejects.toMatchObject({ code: 'CANCELLED' })
    const platform = fakePlatform()
    const mid = new AbortController()
    const inner = platform.prepare
    platform.prepare = async (image) => {
      const s = await inner(image)
      return { ...s, encode: async (m, q) => { const b = await s.encode(m, q); mid.abort(); return b } }      // ilk kodlamadan sonra iptal
    }
    await expect(run(fakeJpegBlob({ data: photoLike(200, 150, 1), width: 200, height: 150 }, { padBytes: 99999 }), { resize: { method: 'scale', width: 100 } },
      platform, { signal: mid.signal, cooperative: true })).rejects.toMatchObject({ code: 'CANCELLED' })
    expect(platform.stats.closed).toBe(1)
    expect(platform.stats.sessionsClosed).toBe(platform.stats.prepared)
    expect(platform.stats.encodes).toBeLessThanOrEqual(2)
  })

  it('"en küçük biçim": umutsuz paletli PNG adayı (büyük fotoğraf) hiç kodlanmaz — boyut 8 banttan kestirilir', async () => {
    const w = 1200
    const h = 900                                                           // 1,08 MP: kestirim eşiğinin (1 MP) üstünde
    const img = { data: photoLike(w, h, 5), width: w, height: h, hasAlpha: false }
    const ctx = makeCtx({})
    const full = await runPng(img, { kind: 'auto' }, [], ctx)               // sınır yok: aday üretilir
    expect(full.colors).toBe(256)
    // kestirim (8 bant × 16 satır, görsele oranlanır) gerçek boyuta yakın olmalı
    const estimate = await estimatePalettePng(img, buildPalette(img.data, w * h, 256), ctx)
    expect(estimate).toBeGreaterThan(full.size * 0.6)
    expect(estimate).toBeLessThan(full.size * 1.6)
    // sınır gerçek boyutun çeyreğiyse aday elenir (tam kodlama yapılmadan)
    expect(await runPng(img, { kind: 'auto' }, [], ctx, Math.round(full.size / 4))).toBeNull()
    // küçük görselde (≤ 1 MP) kestirim yapılmaz: sınır ne olursa olsun aday üretilir
    const small = { data: photoLike(300, 200, 5), width: 300, height: 200, hasAlpha: false }
    expect(await runPng(small, { kind: 'auto' }, [], ctx, 1)).not.toBeNull()
    // birebir palet (logo / düz renk) hiçbir zaman elenmez
    const flat = { data: solid(w, h, [10, 20, 30, 255]), width: w, height: h, hasAlpha: false }
    expect((await runPng(flat, { kind: 'auto' }, [], ctx, 1)).colors).toBe(1)
  })

  it('büyük görselde (> 3 MP) kalite araması ÖRNEKLEM üstünde: tam görsel yalnız bir kez kodlanır', async () => {
    const w = 2200
    const h = 1500
    const d = new Uint8ClampedArray(w * h * 4)
    for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) { const i = (y * w + x) * 4; d[i] = (x * 7 + y) & 255; d[i + 1] = (x + y * 3) & 255; d[i + 2] = (x ^ y) & 255; d[i + 3] = 255 }
    const sizes = []
    const platform = fakePlatform()
    const inner = platform.prepare
    platform.prepare = async (image) => { sizes.push(image.width * image.height); return inner(image) }
    const r = await run(fakeJpegBlob({ data: d, width: w, height: h }, { padBytes: 20_000_000 }), { format: 'jpeg' }, platform)
    expect([r.width, r.height]).toEqual([w, h])
    expect(sizes).toEqual([w * h, 1536 * 1024])                            // tam görsel oturumu + 3 × 2 döşemelik mozaik oturumu
    expect(platform.stats.encodes).toBeLessThanOrEqual(MAX_SEARCH_ENCODES)
    expect(r.similarity).toBeGreaterThan(0.5)
  }, 60000)

  it('notlar sözleşmedeki sırayla ve yalnız bilinen kodlarla', async () => {
    const r = await run(await pngBlob(logoWithAlpha(64, 40), 64, 40), { resize: { method: 'cover', width: 300, height: 300 }, format: 'jpeg', quality: 80 })
    for (const n of r.notes) expect(NOTE_CODES).toContain(n)
    expect(r.notes).toEqual([...r.notes].sort((a, b) => NOTE_CODES.indexOf(a) - NOTE_CODES.indexOf(b)))
    expect(r.notes).toEqual(expect.arrayContaining(['NOT_UPSCALED', 'ALPHA_FLATTENED', 'SMART_CROP']))
  })

  it('destek sorgusu: platform çalışmıyorsa ok = false; WebP kodlanabilirliği ayrı', async () => {
    expect(await supportCore(fakePlatform())).toEqual({ ok: true, webp: true })
    expect(await supportCore(fakePlatform({ webp: false }))).toEqual({ ok: true, webp: false })
    expect(await supportCore({ available: async () => false, canEncode: async () => true })).toEqual({ ok: false, webp: false })
    expect(await supportCore({ available: async () => { throw new Error('x') }, canEncode: async () => true })).toEqual({ ok: false, webp: false })
  })
})

describe('görsel motoru — iş akışı: korunan ÖZGÜN dosyada üstveri sızmaz (inceleme 2026-11-14)', () => {
  const rational = (...pairs) => { const b = new Uint8Array(pairs.length * 8); const dv = new DataView(b.buffer); pairs.forEach(([n, d], i) => { dv.setUint32(i * 8, n, true); dv.setUint32(i * 8 + 4, d, true) }); return b }
  const GPS = [
    { tag: 1, type: 2, count: 2, value: enc('N\u0000') },
    { tag: 2, type: 5, count: 3, value: rational([41, 1], [0, 1], [4932, 100]) },
    { tag: 3, type: 2, count: 2, value: enc('E\u0000') },
    { tag: 4, type: 5, count: 3, value: rational([28, 1], [58, 1], [4212, 100]) },
  ]
  const img = { data: photoLike(120, 90, 8), width: 120, height: 90 }
  const tiffAll = (extra = {}) => buildExif({ gps: GPS, creation: '2026:11:14 09:30:00', copyrightBytes: enc('© Biz'), ...extra })

  it('GPS li JPEG kodlamayla küçülmüyor: özgün döner ama EXIF / ek ATILMIŞ; bayt ve tasarruf ayıklanmış dosyanınki', async () => {
    const tiff = tiffAll()
    const src = fakeJpegBlob(img, { padBytes: 400, exifTiff: tiff, trailer: 25 })
    const r = await run(src, {})
    expect(r.kept).toBe(true)
    expect(r.type).toBe('jpeg')
    expect(r.notes).toEqual(['KEPT_ORIGINAL', 'METADATA_STRIPPED'])
    expect(r.blob).not.toBeNull()                                           // yeni (temiz) dosya — özgün Blob değil
    const out = await bytesOf(r.blob)
    expect(r.blob.type).toBe('image/jpeg')
    expect(r.bytes).toBe(out.length)
    expect(out.length).toBe(src.size - (2 + 2 + 6 + tiff.length) - 25)      // APP1 bölümü + dosya sonu eki
    expect(r.savingPct).toBe(Math.floor((1 - out.length / src.size) * 1000) / 10)
    expect(r.savingPct).toBeGreaterThan(0)
    expect(r.similarity).toBe(1)
    expect(findJpegExif(out)).toBeNull()
    expect(readMetadata(out, 'jpeg').present).toEqual({ copyright: false, creation: false, location: false })
    expect(r.original.metadata).toEqual({ copyright: true, creation: true, location: true })
    // görüntü verisi (SOS sonrası entropi baytları) aynı kalır
    const orig = await bytesOf(src)
    expect(Array.from(out.subarray(out.length - 402))).toEqual(Array.from(orig.subarray(orig.length - 25 - 402, orig.length - 25)))
  })

  it('preserve ile: YALNIZ istenen türler en küçük EXIF bloğuyla geri yazılır (METADATA_PRESERVED)', async () => {
    const src = fakeJpegBlob(img, { padBytes: 400, exifTiff: tiffAll() })
    const r = await run(src, { preserve: { location: true } })
    expect(r.kept).toBe(true)
    expect(r.notes).toEqual(['KEPT_ORIGINAL', 'METADATA_PRESERVED', 'METADATA_STRIPPED'])
    const out = await bytesOf(r.blob)
    const exif = parseExif(out, findJpegExif(out).t, findJpegExif(out).end)
    expect(exif.gps.map((e) => e.tag)).toEqual([1, 2, 3, 4])
    expect(exif.creation).toBeNull()
    expect(exif.copyrightBytes).toBeNull()
  })

  it('EXIF yönü: pikseller döndürülmediği için yön en küçük EXIF ile KALIR; GPS gider', async () => {
    const src = fakeJpegBlob({ data: photoLike(40, 60, 1), width: 40, height: 60 }, { padBytes: 400, exifTiff: tiffAll({ orientation: 6 }) })
    const r = await run(src, {})
    expect(r.kept).toBe(true)
    expect(r.notes).toEqual(['KEPT_ORIGINAL', 'METADATA_STRIPPED'])
    const out = await bytesOf(r.blob)
    const at = findJpegExif(out)
    expect(exifOrientation(out, at.t, at.end)).toBe(6)
    expect(parseExif(out, at.t, at.end)).toEqual({ copyrightBytes: null, creation: null, gps: null })
  })

  it('güvenle ayıklanamayan yapı (EOI olmayan JPEG): özgün dosya DÖNMEZ — normal (yeniden kodlanmış) sonuç', async () => {
    const tiff = tiffAll()
    const bytes = Uint8Array.from([0xff, 0xd8, 0xff, 0xe1, ((tiff.length + 8) >> 8) & 255, (tiff.length + 8) & 255, 0x45, 0x78, 0x69, 0x66, 0, 0, ...tiff,
      0xff, 0xc0, 0, 11, 8, 0, 90, 0, 120, 1, 1, 0x11, 0, 0xff, 0xda, 0, 8, 1, 1, 0, 0, 0x3f, 0, 1, 2, 3])
    const r = await run(registerBlob(bytes, img, 'image/jpeg'), {})
    expect(r.kept).toBe(false)
    expect(r.notes).not.toContain('KEPT_ORIGINAL')
    expect(r.bytes).toBeGreaterThan(bytes.length)                         // daha büyük — ama üstveri sızmaz
  })

  it('PNG: metin parçalı (konum) özgün korunurken parça atılır; görüntü baytları aynı', async () => {
    const first = await run(await pngBlob(logoWithAlpha(64, 40), 64, 40, { level: 0 }), {})
    const clean = await bytesOf(first.blob)
    const text = (await encodePng({ width: 1, height: 1, rgba: solid(1, 1, [0, 0, 0, 0]) }, { text: [{ keyword: 'Location', text: '41.0082,28.9784' }] })).bytes
    // metin parçası (uzunluk + tür + veri + CRC) bulunur ve temiz dosyanın IHDR'sinden sonra eklenir
    let o = 8
    let chunk = null
    for (let g = 0; g < 20 && o < text.length; g++) {
      const len = (text[o] << 24 >>> 0) + (text[o + 1] << 16) + (text[o + 2] << 8) + text[o + 3]
      if (String.fromCharCode(...text.subarray(o + 4, o + 8)) === 'tEXt') { chunk = text.subarray(o, o + 12 + len); break }
      o += 12 + len
    }
    expect(chunk).not.toBeNull()
    const withText = new Uint8Array(clean.length + chunk.length)
    withText.set(clean.subarray(0, 33)); withText.set(chunk, 33); withText.set(clean.subarray(33), 33 + chunk.length)
    const r = await run(new Blob([withText], { type: 'image/png' }), {})
    expect(r.kept).toBe(true)
    expect(r.notes).toEqual(['KEPT_ORIGINAL', 'METADATA_STRIPPED'])
    const out = await bytesOf(r.blob)
    expect(Array.from(out)).toEqual(Array.from(clean))
    expect(pngChunks(out).map((c) => c.type)).not.toContain('tEXt')
  })

  it('üstverisiz özgün: hiçbir şey değişmez — blob null (çağıranın Blob u döner), not yalnız KEPT_ORIGINAL', async () => {
    const tiny = fakeJpegBlob(img, { padBytes: 0 })
    const r = await run(tiny, {})
    expect(r.blob).toBeNull()
    expect(r.notes).toEqual(['KEPT_ORIGINAL'])
  })
})

describe('görsel motoru — iş akışı: yalan başlık + yarı saydam kayıpsız (inceleme 2026-11-14)', () => {
  it('çözülen görsel başlıktakinden BÜYÜKSE: TOO_MANY_PIXELS (header_mismatch); çözülen kapatılır', async () => {
    const platform = fakePlatform()
    const gif = [...enc('GIF89a'), 10, 0, 10, 0, 0, 0, 0, 0x2c, 0, 0, 0, 0, 10, 0, 10, 0, 0, 2, 1, 0, 0, 0x3b]
    const lie = registerBlob(Uint8Array.from(gif), { data: solid(40, 30, [1, 2, 3, 255]), width: 40, height: 30 }, 'image/gif')
    await expect(run(lie, {}, platform)).rejects.toMatchObject({ code: 'TOO_MANY_PIXELS', params: { width: 40, height: 30, reason: 'header_mismatch' } })
    expect(platform.stats.closed).toBe(1)
    // yön uygulanınca en / boy yer değiştirebilir: alan ve uzun kenar aynıysa sorun yok
    expect(() => assertDecodedWithinProbe(60, 40, { width: 40, height: 60 })).not.toThrow()
    expect(() => assertDecodedWithinProbe(41, 60, { width: 40, height: 60 })).toThrow(expect.objectContaining({ code: 'TOO_MANY_PIXELS' }))
    expect(() => assertDecodedWithinProbe(10, 61, { width: 40, height: 60 })).toThrow(expect.objectContaining({ code: 'TOO_MANY_PIXELS' }))
    // ilk karesi ekrandan büyük GIF: başlık o ölçüyü verir, iş biter
    const framed = [...enc('GIF89a'), 1, 0, 1, 0, 0, 0, 0, 0x2c, 0, 0, 0, 0, 40, 0, 30, 0, 0, 2, 1, 0, 0, 0x3b]
    const ok = await run(registerBlob(Uint8Array.from(framed), { data: solid(40, 30, [1, 2, 3, 255]), width: 40, height: 30 }, 'image/gif'), { format: 'png' })
    expect([ok.width, ok.height]).toEqual([40, 30])
  })

  it('kayıpsız PNG yalnız OPAK / tam saydam piksellerde "birebir"; yarı saydam piksel varsa benzerlik null + ALPHA_COLORS_ROUNDED', async () => {
    const opaque = new Uint8ClampedArray(solid(20, 20, [10, 20, 30, 255]))
    for (let i = 0; i < 40; i++) opaque[i * 4 + 3] = 0                        // tam saydam satırlar: yuvarlanma yok
    const a = await run(await pngBlob(opaque, 20, 20, { level: 0 }), { format: 'png', quality: 100 })
    expect(a.similarity).toBe(1)
    expect(a.notes).not.toContain('ALPHA_COLORS_ROUNDED')
    const semi = new Uint8ClampedArray(opaque)
    semi[3] = 7                                                                // tek yarı saydam piksel
    const b = await run(await pngBlob(semi, 20, 20, { level: 0 }), { format: 'png', quality: 100 })
    expect(b.similarity).toBeNull()
    expect(b.notes).toContain('ALPHA_COLORS_ROUNDED')
    // ölçü değişirse (küçültme) benzerlik küçültülmüş görsele göredir: not yazılmaz
    const c = await run(await pngBlob(semi, 20, 20, { level: 0 }), { format: 'png', quality: 100, resize: { method: 'scale', width: 10 } })
    expect(c.notes).not.toContain('ALPHA_COLORS_ROUNDED')
  })
})
