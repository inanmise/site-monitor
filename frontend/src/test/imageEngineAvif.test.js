import { describe, it, expect, vi } from 'vitest'
import { avifSkipNote, processCore, supportCore } from '../utils/imageEngine/pipeline.js'
import { readImageFacts, bandingRisk } from '../utils/imageEngine/analyze.js'
import { normalizeOptions } from '../utils/imageEngine/options.js'
import { EngineError, JOB_TIMEOUT_MS, NOTE_CODES, OUTPUT_FORMATS } from '../utils/imageEngine/errors.js'
import { buildExif, planMetadata } from '../utils/imageEngine/metadata.js'
import {
  AVIF_CANDIDATE_DEADLINE_MS, AVIF_FALLBACK_QUALITY, AVIF_JOB_DEADLINE_MS, AVIF_MAX_DIM_STEPS, AVIF_MAX_PIXELS, AVIF_QUALITY_MAX,
  AVIF_QUALITY_MIN, AVIF_SEARCH_ENCODES, AVIF_SPEED, AVIF_TARGET_FLOOR, AVIF_TARGET_STEPS,
  QUALITY_RANGE, SIMILARITY_TARGET, pickSmallest, resolveFormats, targetQualityFloor,
} from '../utils/imageEngine/search.js'
import { fakeJpegBlob, fakePlatform, photoLike, registerBlob, logoWithAlpha } from './imageEngineKit.test.helpers.js'
import { AVIF_TARGET_FLOOR as UI_AVIF_TARGET_FLOOR } from '../components/page/resourcescan/imagetool/imageInsightsModel.js'

vi.setConfig({ testTimeout: 60_000 })

/**
 * GERÇEK AVIF (2026-11-15, kullanıcı kararı "gerçek AVIF üret"): iş akışının AVIF dalları SAHTE platformla — kodlayıcı
 * (ayrı Worker'daki WASM) taklit edilir, karar kodu gerçektir. Gerçek kodlayıcıyla aynı akış e2e/image-engine.spec.js'te.
 */
const run = (blob, options, platform, extra = {}) => processCore(blob, options, { platform, ...extra })
const photo = (w = 96, h = 72, seed = 3) => fakeJpegBlob({ data: photoLike(w, h, seed), width: w, height: h }, { padBytes: 60000 })

const u32 = (v) => [(v >>> 24) & 255, (v >>> 16) & 255, (v >>> 8) & 255, v & 255]
const str = (s) => [...s].map((c) => c.charCodeAt(0))
const box = (type, ...parts) => { const body = parts.flat(); return [...u32(8 + body.length), ...str(type), ...body] }
const fullBox = (type, ...parts) => box(type, [0, 0, 0, 0], ...parts)
/** En küçük AVIF başlığı: ftyp + meta/iprp/ipco (ispe, pixi, av1C, isteğe bağlı alfa auxC) + dolgu mdat. */
function avifBytes(w, h, { alpha = false, pad = 0, av1c = 0x0c, bits = 8 } = {}) {
  const props = [fullBox('ispe', u32(w), u32(h)), fullBox('pixi', [3, bits, bits, bits]), box('av1C', [0x81, 0x00, av1c, 0x00])]
  if (alpha) props.push(fullBox('auxC', str('urn:mpeg:mpegB:cicp:systems:auxiliary:alpha'), [0]))
  const bytes = [...box('ftyp', str('avif'), u32(0), str('mif1'), str('avif')), ...fullBox('meta', box('iprp', box('ipco', ...props))),
    ...box('mdat', new Array(pad).fill(0))]
  return Uint8Array.from(bytes)
}
const avifSource = (w, h, opts) => registerBlob(avifBytes(w, h, { pad: 60000, ...opts }), { data: photoLike(w, h, 5), width: w, height: h }, 'image/avif')

describe('AVIF — sabitler ve seçenekler', () => {
  it('çıktı biçimleri AVIF i SONA ekler; seçenek doğrulaması kabul eder', () => {
    expect(OUTPUT_FORMATS).toEqual(['keep', 'jpeg', 'png', 'webp', 'smallest', 'avif'])
    expect(normalizeOptions({ format: 'avif' }).format).toBe('avif')
    expect(NOTE_CODES.slice(-4)).toEqual(['AVIF_UNAVAILABLE', 'AVIF_TOO_LARGE', 'AVIF_NOT_MEASURED', 'AVIF_TIME_LIMIT'])
  })

  it('kalibrasyon sabitleri tutarlı: aralık, taban, yedek kalite, arama sınırı, hız, piksel sınırı', () => {
    expect(QUALITY_RANGE.avif).toEqual([AVIF_QUALITY_MIN, AVIF_QUALITY_MAX])
    expect(AVIF_QUALITY_MIN).toBeLessThan(AVIF_QUALITY_MAX)
    expect(AVIF_TARGET_FLOOR).toBeLessThan(AVIF_QUALITY_MIN)
    expect(AVIF_FALLBACK_QUALITY).toBeGreaterThanOrEqual(AVIF_QUALITY_MIN)
    expect(AVIF_FALLBACK_QUALITY).toBeLessThanOrEqual(AVIF_QUALITY_MAX)
    expect(AVIF_SEARCH_ENCODES).toBeGreaterThanOrEqual(3)
    expect(AVIF_SPEED).toBeGreaterThanOrEqual(0)
    expect(AVIF_SPEED).toBeLessThanOrEqual(10)
    expect(AVIF_MAX_PIXELS).toBeGreaterThan(1_000_000)
    expect(targetQualityFloor('avif')).toBe(AVIF_TARGET_FLOOR)
    expect(targetQualityFloor('jpeg')).toBe(50)
    // arayüzün "hedef boyutta kalite tabanı" metni motorun değerini gösterir
    expect(UI_AVIF_TARGET_FLOOR).toBe(AVIF_TARGET_FLOOR)
  })
})

describe('AVIF — biçim çözümü (resolveFormats)', () => {
  it('platformda AVIF yoksa (null) bugünkü sonuç BİREBİR; açıkça istenirse yedek + not', () => {
    for (const f of ['keep', 'jpeg', 'png', 'webp', 'smallest']) {
      for (const src of ['jpeg', 'png', 'webp', 'avif', 'gif']) {
        for (const alpha of [false, true]) {
          for (const webp of [true, false]) {
            const r = resolveFormats(f, src, alpha, webp, null)
            expect(r.list).not.toContain('avif')
            expect(r.notes.filter((n) => n.startsWith('AVIF'))).toEqual([])
          }
        }
      }
    }
    expect(resolveFormats('avif', 'jpeg', false, true, null)).toEqual({ list: ['webp'], notes: ['AVIF_UNAVAILABLE'] })
    expect(resolveFormats('avif', 'png', true, false, 'AVIF_TOO_LARGE')).toEqual({ list: ['png'], notes: ['AVIF_TOO_LARGE', 'WEBP_UNAVAILABLE'] })
  })

  it('AVIF denenebilirken: açık istek → avif; "en küçük" listenin SONUNA (saydam görselde de); "keep" AVIF kaynağı → avif', () => {
    expect(resolveFormats('avif', 'jpeg', false, true, 'ok')).toEqual({ list: ['avif'], notes: [] })
    expect(resolveFormats('smallest', 'jpeg', false, true, 'ok')).toEqual({ list: ['jpeg', 'png', 'webp', 'avif'], notes: [] })
    expect(resolveFormats('smallest', 'png', true, true, 'ok')).toEqual({ list: ['png', 'webp', 'avif'], notes: [] })
    expect(resolveFormats('smallest', 'png', true, false, 'AVIF_UNAVAILABLE')).toEqual({ list: ['png'], notes: ['WEBP_UNAVAILABLE', 'AVIF_UNAVAILABLE'] })
    expect(resolveFormats('keep', 'avif', false, true, 'ok')).toEqual({ list: ['avif'], notes: [] })
    expect(resolveFormats('keep', 'avif', false, true, 'AVIF_UNAVAILABLE')).toEqual({ list: ['webp'], notes: ['AVIF_UNAVAILABLE'] })
    expect(resolveFormats('keep', 'jpeg', false, true, 'ok')).toEqual({ list: ['jpeg'], notes: [] })
  })

  it('avifSkipNote: iptal ve motorun eski paket hatası işi DÜŞÜRÜR; ötekiler AVIF i atlatır (doğru notla)', () => {
    expect(avifSkipNote(new EngineError('CANCELLED'))).toBeNull()
    expect(avifSkipNote(new EngineError('BROWSER_UNSUPPORTED', { reason: 'stale_bundle' }))).toBeNull()
    // AVIF kodlayıcı dosyası bulunamadı (inceleme 2026-11-15): YALNIZ açık AVIF isteği düşer; "en küçük" / "koru" atlar
    const missing = new EngineError('BROWSER_UNSUPPORTED', { reason: 'avif', detail: 'asset_missing' })
    expect(avifSkipNote(missing, 'avif')).toBeNull()
    expect(avifSkipNote(missing, 'smallest')).toBe('AVIF_UNAVAILABLE')
    expect(avifSkipNote(missing, 'keep')).toBe('AVIF_UNAVAILABLE')
    expect(avifSkipNote(missing)).toBe('AVIF_UNAVAILABLE')
    expect(avifSkipNote(new EngineError('BROWSER_UNSUPPORTED', { reason: 'avif', detail: 'time_budget' }), 'avif')).toBe('AVIF_TIME_LIMIT')
    expect(avifSkipNote(new EngineError('BROWSER_UNSUPPORTED', { reason: 'avif', detail: 'load_timeout' }))).toBe('AVIF_UNAVAILABLE')
    expect(avifSkipNote(new EngineError('BROWSER_UNSUPPORTED', { reason: 'avif', detail: 'not_measured' }))).toBe('AVIF_NOT_MEASURED')
    expect(avifSkipNote(new EngineError('ENCODE_FAILED', { reason: 'size_mismatch' }))).toBe('AVIF_UNAVAILABLE')
    expect(avifSkipNote(new RangeError('x'))).toBe('AVIF_UNAVAILABLE')
  })
})

describe('AVIF — iş akışı (sahte kodlayıcı)', () => {
  it('açık istek: AVIF üretilir, kalite AVIF aralığında, arama ≤ AVIF_SEARCH_ENCODES, hız kodlayıcıya gider; denenebilirlik analizde', async () => {
    const p = fakePlatform({ avif: {} })
    const r = await run(photo(), { format: 'avif' }, p)
    expect(r.type).toBe('avif')
    expect(r.mime).toBe('image/avif')
    expect(r.blob.type).toBe('image/avif')
    expect(r.quality).toBeGreaterThanOrEqual(AVIF_QUALITY_MIN)
    expect(r.quality).toBeLessThanOrEqual(AVIF_QUALITY_MAX)
    expect(p.stats.avifEncodes).toBeLessThanOrEqual(AVIF_SEARCH_ENCODES)
    expect(p.stats.avifQualities.every((q) => q >= AVIF_QUALITY_MIN && q <= AVIF_QUALITY_MAX)).toBe(true)
    expect(p.stats.avifTuning).toMatchObject({ speed: AVIF_SPEED })
    // yükleme beklemesi işin süre bütçesine bağlı (sınır 0'dan büyük, işin süresinden küçük)
    expect(p.stats.avifTuning.loadTimeoutMs).toBeGreaterThan(0)
    expect(p.stats.avifTuning.loadTimeoutMs).toBeLessThanOrEqual(AVIF_JOB_DEADLINE_MS)
    expect(p.stats.avifTimeouts.every((ms) => ms > 0 && ms <= AVIF_JOB_DEADLINE_MS)).toBe(true)
    expect(p.stats.encodes).toBe(0)                                       // tuval kodlayıcısı hiç kullanılmadı
    expect(r.similarity === null || r.similarity >= SIMILARITY_TARGET || r.quality === AVIF_QUALITY_MAX).toBe(true)
    expect(r.notes).toEqual([])
    expect(r.analysis.avifPossible).toBe(true)
    expect(r.analysis.candidates.map((c) => c.type)).toEqual(['avif'])
  })

  it('"en küçük": AVIF de yarışır (en son kodlanır); sonuç adayların en küçüğü', async () => {
    const p = fakePlatform({ avif: {} })
    const r = await run(photo(120, 90), { format: 'smallest' }, p)
    const types = r.analysis.candidates.map((c) => c.type)
    expect(types).toContain('avif')
    expect(types.indexOf('avif')).toBe(types.length - 1)
    const lossy = r.analysis.candidates.filter((c) => c.type !== 'png')
    expect(r.bytes).toBeLessThanOrEqual(Math.min(...lossy.map((c) => c.bytes)))
  })

  it('platformda AVIF YOK (ana iş parçacığı yedeği): "en küçük" bugünkü gibi — AVIF yok, not yok, analizde alan yok', async () => {
    const r = await run(photo(), { format: 'smallest' }, fakePlatform())
    expect(r.analysis.candidates.map((c) => c.type)).not.toContain('avif')
    expect(r.notes.filter((n) => n.startsWith('AVIF'))).toEqual([])
    expect('avifPossible' in r.analysis).toBe(false)
    // açık AVIF isteği yine yedeğe döner — not ile
    const a = await run(photo(), { format: 'avif' }, fakePlatform())
    expect(a.type).toBe('webp')
    expect(a.notes).toContain('AVIF_UNAVAILABLE')
  })

  it('denenemez (CSP / WebAssembly yok): istek → WebP + AVIF_UNAVAILABLE; "en küçük" → AVIF atlanır + not; kodlayıcı hiç açılmaz', async () => {
    const p = fakePlatform({ avif: { available: false } })
    const a = await run(photo(), { format: 'avif' }, p)
    expect(a.type).toBe('webp')
    expect(a.notes).toContain('AVIF_UNAVAILABLE')
    expect(a.analysis.avifPossible).toBe(false)
    const s = await run(photo(), { format: 'smallest' }, p)
    expect(s.analysis.candidates.map((c) => c.type)).not.toContain('avif')
    expect(s.notes).toContain('AVIF_UNAVAILABLE')
    expect(p.stats.avifPrepared).toBe(0)
  })

  it('kodlayıcı YÜKLENEMEZ (süre / ağ / bellek): iş DÜŞMEZ — istek WebP ile, "en küçük" AVIF siz biter (not)', async () => {
    const err = new EngineError('BROWSER_UNSUPPORTED', { reason: 'avif', detail: 'load_timeout' })
    const a = await run(photo(), { format: 'avif' }, fakePlatform({ avif: { prepareError: err } }))
    expect(a.type).toBe('webp')
    expect(a.notes).toContain('AVIF_UNAVAILABLE')
    const s = await run(photo(), { format: 'smallest' }, fakePlatform({ avif: { prepareError: err } }))
    expect(['jpeg', 'webp', 'png']).toContain(s.type)
    expect(s.notes).toContain('AVIF_UNAVAILABLE')
    expect(s.analysis.avifPossible).toBe(false)
    // WebP de yoksa AVIF isteğinin yedeği JPEG (saydamsa PNG)
    const j = await run(photo(), { format: 'avif' }, fakePlatform({ webp: false, avif: { encodeError: err } }))
    expect(j.type).toBe('jpeg')
    expect(j.notes).toEqual(expect.arrayContaining(['WEBP_UNAVAILABLE', 'AVIF_UNAVAILABLE']))
  })

  it('KODLAYICI DOSYASI BULUNAMADI (yuvarlanan dağıtım, inceleme 2026-11-15): "en küçük" / "koru" AVIF siz BİTER; yalnız açık istek AVIF e özgü hatayla düşer', async () => {
    const missing = new EngineError('BROWSER_UNSUPPORTED', { reason: 'avif', detail: 'asset_missing' })
    const s = await run(photo(120, 90), { format: 'smallest' }, fakePlatform({ avif: { prepareError: missing } }))
    expect(['jpeg', 'webp', 'png']).toContain(s.type)
    expect(s.notes).toContain('AVIF_UNAVAILABLE')
    expect(s.analysis.candidates.map((c) => c.type)).not.toContain('avif')
    const k = await run(avifSource(80, 60), { format: 'keep' }, fakePlatform({ avif: { prepareError: missing } }))
    expect(k.type).toBe('webp')
    expect(k.notes).toContain('AVIF_UNAVAILABLE')
    await expect(run(photo(), { format: 'avif' }, fakePlatform({ avif: { prepareError: missing } })))
      .rejects.toMatchObject({ code: 'BROWSER_UNSUPPORTED', params: { reason: 'avif', detail: 'asset_missing' } })
    // hata hatırlanmışken (avifBlocked): açık istek kodlayıcıyı açmadan AYNI hatayla biter; "en küçük" yine atlar
    const blocked = fakePlatform({ avif: { available: false, blocked: 'asset_missing' } })
    await expect(run(photo(), { format: 'avif' }, blocked)).rejects.toMatchObject({ params: { reason: 'avif', detail: 'asset_missing' } })
    const bs = await run(photo(), { format: 'smallest' }, blocked)
    expect(bs.notes).toContain('AVIF_UNAVAILABLE')
    expect(blocked.stats.avifPrepared).toBe(0)
    // başka bir nedenle hatırlanan yükleme hatası: açık istek WebP e döner (iş düşmez)
    const slow = fakePlatform({ avif: { available: false, blocked: 'load_timeout' } })
    const w = await run(photo(), { format: 'avif' }, slow)
    expect(w.type).toBe('webp')
    expect(w.notes).toContain('AVIF_UNAVAILABLE')
  })

  it('motorun KENDİ eski paket hatası (stale_bundle) AVIF yolundan gelse de iş düşer (savunma)', async () => {
    const stale = new EngineError('BROWSER_UNSUPPORTED', { reason: 'stale_bundle' })
    await expect(run(photo(), { format: 'smallest' }, fakePlatform({ avif: { prepareError: stale } })))
      .rejects.toMatchObject({ params: { reason: 'stale_bundle' } })
  })

  it('tarayıcı AVIF ÇÖZEMİYOR: istek → sabit kalite, benzerlik null + AVIF_NOT_MEASURED; "en küçük"te AVIF yarışmaz', async () => {
    const p = fakePlatform({ avif: { decodable: false } })
    const a = await run(photo(), { format: 'avif' }, p)
    expect(a.type).toBe('avif')
    expect(a.quality).toBe(AVIF_FALLBACK_QUALITY)
    expect(a.similarity).toBeNull()
    expect(a.notes).toContain('AVIF_NOT_MEASURED')
    // "nasıl yaptık": arama YAPILMADI — kalite kipi 'fixed' (inceleme 2026-11-15)
    expect(a.analysis.qualityMode).toBe('fixed')
    const s = await run(photo(), { format: 'smallest' }, fakePlatform({ avif: { decodable: false } }))
    expect(s.type).not.toBe('avif')
    expect(s.notes).toContain('AVIF_NOT_MEASURED')
    // elle kalite: aday kalır (eşdeğerlik iddiası yok), not düşülür
    const m = await run(photo(), { format: 'avif', quality: 55 }, fakePlatform({ avif: { decodable: false } }))
    expect(m.type).toBe('avif')
    expect(m.quality).toBe(55)
    expect(m.similarity).toBeNull()
    expect(m.notes).toContain('AVIF_NOT_MEASURED')
    expect(m.analysis.qualityMode).toBe('manual')
  })

  it('"keep" AVIF kaynağı: AVIF olarak yeniden kodlanır; kodlayıcı yoksa WebP + not; saydam AVIF saydam kalır', async () => {
    const r = await run(avifSource(80, 60), { format: 'keep' }, fakePlatform({ avif: {} }))
    expect(r.original.type).toBe('avif')
    expect(r.type).toBe('avif')
    const w = await run(avifSource(80, 60), { format: 'keep' }, fakePlatform({ avif: { available: false } }))
    expect(w.type).toBe('webp')
    expect(w.notes).toContain('AVIF_UNAVAILABLE')
    const logo = registerBlob(avifBytes(64, 64, { alpha: true, pad: 60000 }), { data: logoWithAlpha(64, 64), width: 64, height: 64 }, 'image/avif')
    const t = await run(logo, { format: 'avif' }, fakePlatform({ avif: {} }))
    expect(t.type).toBe('avif')
    expect(t.notes).not.toContain('ALPHA_FLATTENED')
  })

  it('üst veri AVIF e yazılmaz: istenen tür METADATA_NOT_PRESERVED; planMetadata AVIF için EXIF kurmaz', async () => {
    expect(planMetadata({ present: { copyright: true }, copyright: 'X' }, { copyright: true }, 'avif'))
      .toEqual({ exif: null, text: [], kept: [], dropped: ['copyright'] })
    expect(planMetadata({ present: {} }, {}, 'avif', 6)).toEqual({ exif: null, text: [], kept: [], dropped: [] })
    const exif = buildExif({ copyrightBytes: new TextEncoder().encode('SiteMonitor') })
    const src = fakeJpegBlob({ data: photoLike(64, 48, 2), width: 64, height: 48 }, { padBytes: 60000, exifTiff: exif })
    const r = await run(src, { format: 'avif', preserve: { copyright: true } }, fakePlatform({ avif: {} }))
    expect(r.type).toBe('avif')
    expect(r.notes).toContain('METADATA_NOT_PRESERVED')
    expect(r.notes).not.toContain('METADATA_PRESERVED')
  })

  it('hedef boyut: AVIF tabanı (AVIF_TARGET_FLOOR) kullanılır; kalite tabanın altına inmez', async () => {
    const p = fakePlatform({ avif: {} })
    const r = await run(photo(160, 120), { format: 'avif', targetBytes: 2500 }, p)
    expect(r.type).toBe('avif')
    expect(p.stats.avifQualities.every((q) => q >= AVIF_TARGET_FLOOR)).toBe(true)
    expect(Math.min(...p.stats.avifQualities)).toBeLessThan(AVIF_QUALITY_MIN)    // aralığın altına (tabana dek) indi
  })

  it('kodlama sırasında hata (süre aşımı): istek WebP e döner, iş biter; oturumlar kapatılır', async () => {
    const p = fakePlatform({ avif: { encodeError: new EngineError('BROWSER_UNSUPPORTED', { reason: 'avif', detail: 'encode_timeout' }) } })
    const r = await run(photo(), { format: 'avif' }, p)
    expect(r.type).toBe('webp')
    expect(r.notes).toContain('AVIF_UNAVAILABLE')
    expect(p.stats.sessionsClosed).toBeGreaterThanOrEqual(p.stats.avifPrepared + p.stats.prepared - 1)
  })

  it('destek sorgusu: platformda AVIF yoksa avif false; varsa ucuz denetimin sonucu (kodlayıcı açılmaz)', async () => {
    expect(await supportCore(fakePlatform())).toEqual({ ok: true, webp: true, avif: false })
    const p = fakePlatform({ avif: {} })
    expect(await supportCore(p)).toEqual({ ok: true, webp: true, avif: true })
    expect(p.stats.avifPrepared).toBe(0)
    expect(await supportCore(fakePlatform({ avif: { available: false } }))).toMatchObject({ avif: false })
  })
})

describe('AVIF — başlık bilgileri (readImageFacts)', () => {
  it('pixi / av1C / auxC: bit derinliği, renk modeli, alt örnekleme; yoğunluk bilinmiyor', () => {
    const f = readImageFacts(avifBytes(10, 10), 'avif')
    expect(f).toMatchObject({ bitDepth: 8, colorModel: 'rgb', subsampling: '4:2:0', ppi: null, ppiChecked: false })
    expect(readImageFacts(avifBytes(10, 10, { alpha: true }), 'avif')).toMatchObject({ colorModel: 'rgba' })
    expect(readImageFacts(avifBytes(10, 10, { av1c: 0x00 }), 'avif').subsampling).toBe('4:4:4')
    expect(readImageFacts(avifBytes(10, 10, { av1c: 0x08 }), 'avif').subsampling).toBe('4:2:2')
    expect(readImageFacts(avifBytes(10, 10, { av1c: 0x1c }), 'avif')).toMatchObject({ colorModel: 'gray', subsampling: null })
    expect(readImageFacts(avifBytes(10, 10, { bits: 10, av1c: 0x4c }), 'avif').bitDepth).toBe(10)
    // bozuk / kısa: fırlatmaz
    expect(readImageFacts(new Uint8Array([0, 0, 0, 1, 1, 2, 3, 4, 5]), 'avif').bitDepth).toBeNull()
  })

  it('bantlaşma: 16 bit kaynaktan AVIF (8 bit) çıktısı da riskli sayılır', () => {
    expect(bandingRisk({ inputBitDepth: 16, outputType: 'avif', outputColors: null, similarity: 0.99 })).toBe('bit_depth')
  })
})

describe('AVIF — "en küçük" seçimi (inceleme 2026-11-15)', () => {
  it('eşik altı AVIF, eşiği tutan JPEG / WebP i küçük diye GEÇEMEZ; eşiği tutan yoksa yarışır', () => {
    const jpegOk = { type: 'jpeg', size: 100, similarity: 0.99 }
    const avifLow = { type: 'avif', size: 60, similarity: 0.975 }
    expect(pickSmallest([jpegOk, avifLow]).type).toBe('jpeg')
    // eşiği tutan AVIF yine en küçük olarak kazanır
    expect(pickSmallest([jpegOk, { ...avifLow, similarity: 0.99 }]).type).toBe('avif')
    // kimse eşiği tutmuyorsa (ince doku: herkes aralığının üstünde) AVIF de yarışır — en küçük kazanır
    expect(pickSmallest([{ ...jpegOk, similarity: 0.97 }, avifLow]).type).toBe('avif')
    // JPEG / WebP eşik altı kalsa da yarışmaya devam eder (bugünkü kural)
    expect(pickSmallest([{ type: 'jpeg', size: 50, similarity: 0.97 }, { type: 'webp', size: 80, similarity: 0.99 }]).type).toBe('jpeg')
    // PNG kuralı değişmedi: eşik altı PNG yalnız yarışan yoksa
    expect(pickSmallest([{ type: 'png', size: 10, similarity: 0.9 }, jpegOk]).type).toBe('jpeg')
  })
})

describe('AVIF — süre bütçesi (inceleme 2026-11-15, ORTA-2)', () => {
  it('sabitler: AVIF işi iş süresinin içinde ve AVIF adayı ondan önce biter; hedef boyutta adımlar azaltılmış', () => {
    expect(AVIF_JOB_DEADLINE_MS).toBeLessThan(JOB_TIMEOUT_MS)
    expect(JOB_TIMEOUT_MS - AVIF_JOB_DEADLINE_MS).toBeGreaterThanOrEqual(30_000)
    expect(AVIF_CANDIDATE_DEADLINE_MS).toBeLessThan(AVIF_JOB_DEADLINE_MS)
    expect(AVIF_TARGET_STEPS).toBeLessThanOrEqual(2)
    expect(AVIF_MAX_DIM_STEPS).toBeLessThanOrEqual(2)
  })

  /**
   * 12 MP için UCUZ sahte platform: kodlayıcılar boyutu formülle verir (pikseller işlenmez), çözme kaynak pikselleri aynen
   * döndürür (benzerlik 1); her AVIF kodlaması sahte saati piksel sayısıyla orantılı ilerletir, istenen süre sınırını
   * (gerçek istemci gibi 90 sn'ye kırpılmış) aşarsa saat sınır kadar ilerler ve süre hatası atılır. AVIF boyut katsayısı
   * bilerek büyük (1,3): AVIF aday da hedefin üstünde kalsın, hedef boyut araması AVIF için de koşsun.
   */
  function cheapPlatform({ clock, avifMsFor12Mp }) {
    const base = fakePlatform()
    const origin = new WeakMap()
    const stats = { avifEncodes: 0, avifCalls: [], canvasEncodes: 0 }
    const make = (image, mime, quality, factor) => {
      const q = Math.round(quality * 100)
      const size = Math.max(64, Math.round(image.width * image.height * (0.02 + 0.004 * q) * factor))
      const blob = new Blob([new Uint8Array(size)], { type: mime })
      origin.set(blob, image)
      return blob
    }
    return {
      ...base,
      stats,
      async prepare(image) {
        return { async encode(mime, q) { stats.canvasEncodes += 1; clock.t += 150; return make(image, mime, q, 1) }, close() {} }
      },
      async decodeRegions(blob, rects) {
        const img = origin.get(blob)
        return rects.map((r) => {
          const out = new Uint8ClampedArray(r.w * r.h * 4)
          for (let y = 0; y < r.h; y++) { const s = ((r.y + y) * img.width + r.x) * 4; out.set(img.data.subarray(s, s + r.w * 4), y * r.w * 4) }
          return out
        })
      },
      avifAvailable: async () => true,
      async prepareAvif(image) {
        return {
          async encode(mime, q, opts) {
            stats.avifEncodes += 1
            stats.avifCalls.push({ at: clock.t, timeoutMs: opts?.timeoutMs })
            const ms = (avifMsFor12Mp * image.width * image.height) / 12_000_000
            const cap = Math.min(90_000, opts?.timeoutMs ?? Infinity)
            if (ms > cap) {
              clock.t += cap
              throw new EngineError('BROWSER_UNSUPPORTED', { reason: 'avif', detail: cap < 90_000 ? 'time_budget' : 'encode_timeout' })
            }
            clock.t += ms
            return make(image, mime, q, 1.3)
          },
          close() {},
        }
      },
    }
  }
  /**
   * 4000 × 3000 (tam 12 MP = AVIF_MAX_PIXELS) renk gürültüsü: 256 renkli palet çok bozar → "en küçük"te PNG adayı
   * üretilmez (ucuz). Kaynak dosya 20 MB: hiçbir çıktı ondan büyük değil — "asla daha kötü" özgünü döndürmez.
   */
  const noisy12mp = () => {
    const w = 4000
    const h = 3000
    const d = new Uint8ClampedArray(w * h * 4)
    let s = 12345
    for (let i = 0; i < d.length; i += 4) {
      s = (Math.imul(s, 1664525) + 1013904223) >>> 0
      d[i] = s >>> 24; d[i + 1] = (s >>> 16) & 255; d[i + 2] = (s >>> 8) & 255; d[i + 3] = 255
    }
    return fakeJpegBlob({ data: d, width: w, height: h }, { padBytes: 20_000_000 })
  }
  const src12 = noisy12mp()

  it.each([
    ['20 sn (geliştirici makinesi)', 20_000],
    ['60 sn (3 kat yavaş makine)', 60_000],
    ['400 sn (kodlama hiç sığmaz)', 400_000],
  ])('12 MP "en küçük" + hedef boyut, AVIF kodlaması %s: iş süre sınırının İÇİNDE biter, JPEG / WebP adayları kaybolmaz', async (_label, avifMs) => {
    const clock = { t: 0 }
    const p = cheapPlatform({ clock, avifMsFor12Mp: avifMs })
    const r = await run(src12, { format: 'smallest', targetBytes: 2_700_000 }, p, { clock: () => clock.t })
    expect(clock.t).toBeLessThanOrEqual(AVIF_JOB_DEADLINE_MS)                            // ve dolayısıyla JOB_TIMEOUT_MS'nin altında
    expect(r.kept).toBe(false)
    expect(r.analysis.qualityMode).toBe('target')
    expect(['jpeg', 'webp', 'avif']).toContain(r.type)
    const types = r.analysis.candidates.map((c) => c.type)
    expect(types).toEqual(expect.arrayContaining(['jpeg', 'webp']))
    // her AVIF kodlamasının süre sınırı, başladığı andan son ana dek kalan süreyi aşmaz
    for (const c of p.stats.avifCalls) {
      expect(c.timeoutMs).toBeGreaterThan(0)
      expect(c.at + c.timeoutMs).toBeLessThanOrEqual(AVIF_JOB_DEADLINE_MS)
    }
    if (avifMs === 20_000) {
      expect(r.notes).not.toContain('AVIF_TIME_LIMIT')
      expect(types).toContain('avif')
    } else {
      expect(r.notes).toContain('AVIF_TIME_LIMIT')
    }
    if (avifMs === 60_000) expect(types).toContain('avif')                              // otomatik AVIF adayı kaldı
    if (avifMs === 400_000) {
      expect(types).not.toContain('avif')
      expect(r.analysis.avifPossible).toBe(false)
    }
  }, 120_000)

  it('açık AVIF isteği süre bütçesine sığmazsa WebP e döner (iş düşmez), not AVIF_TIME_LIMIT; kalan süre ölçülen hızla kestirilir', async () => {
    const clock = { t: 0 }
    // her kodlama 70 sn: ilk kodlama sığar (hız ölçülür), sonrakiler son ana yetmez
    const p = fakePlatform({ avif: { clock, encodeMs: 70_000 } })
    const r = await run(photo(), { format: 'avif' }, p, { clock: () => clock.t })
    expect(clock.t).toBeLessThanOrEqual(AVIF_JOB_DEADLINE_MS)
    expect(r.type).toBe('webp')
    expect(r.notes).toEqual(expect.arrayContaining(['AVIF_TIME_LIMIT']))
    expect(p.stats.avifEncodes).toBeLessThan(AVIF_SEARCH_ENCODES)
  })

  it('hedef boyutta AVIF araması bütçe biterse o ana dek bulunan en iyi AVIF döner (not), hiç kodlama yapamazsa otomatik aday kalır', async () => {
    const clock = { t: 0 }
    const p = fakePlatform({ avif: { clock, encodeMs: 15_000 } })
    const r = await run(photo(160, 120), { format: 'avif', targetBytes: 2500 }, p, { clock: () => clock.t })
    expect(clock.t).toBeLessThanOrEqual(AVIF_JOB_DEADLINE_MS)
    expect(r.type).toBe('avif')
    // hedef aramasında AVIF adım sınırları: kalite ≤ 2 + 2 ara adım, ölçü ≤ AVIF_MAX_DIM_STEPS
    expect(p.stats.avifEncodes).toBeLessThanOrEqual(AVIF_SEARCH_ENCODES + (2 + AVIF_TARGET_STEPS) * (1 + AVIF_MAX_DIM_STEPS))
  })
})
