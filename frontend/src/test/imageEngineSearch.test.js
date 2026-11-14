import { describe, it, expect } from 'vitest'
import {
  MAX_SEARCH_ENCODES, QUALITY_RANGE, SIMILARITY_TARGET, bestQualityUnder, flattenOnto, hasTransparency, pickForTarget,
  pickSmallest, pngColorsForQuality, resolveFormats, savingPct, searchQuality, shouldKeepOriginal,
} from '../utils/imageEngine/search.js'
import { ANALYSIS_SIDE, bestWindowOffset, detectObject, energyMap, integralImage, smartCropOffset } from '../utils/imageEngine/smartcrop.js'
import { planResize } from '../utils/imageEngine/geometry.js'
import { photoLike, rng, solid } from './imageEngineKit.test.helpers.js'

/** Sahte kodlayıcı: benzerlik kaliteyle tek yönlü artar; `pass` eşiği geçen en düşük kalite. */
function fakeEncoder(pass) {
  const calls = []
  const evalQ = async (q) => { calls.push(q); return { similarity: q >= pass ? 0.99 : 0.9, size: q * 1000, blob: `q${q}` } }
  return { calls, evalQ }
}

describe('görsel motoru — otomatik kalite araması', () => {
  it('eşiği geçen EN DÜŞÜK kaliteyi bulur; kodlama sayısı her zaman ≤ 8', async () => {
    const [lo, hi] = QUALITY_RANGE.jpeg
    for (let pass = lo - 5; pass <= hi + 5; pass++) {
      const enc = fakeEncoder(pass)
      const r = await searchQuality(enc.evalQ, lo, hi, 0.95)
      expect(enc.calls.length, `eşik ${pass}`).toBeLessThanOrEqual(MAX_SEARCH_ENCODES)
      expect(r.encodes).toBe(enc.calls.length)
      expect(new Set(enc.calls).size).toBe(enc.calls.length)          // aynı kalite iki kez kodlanmaz
      if (pass <= lo) expect(r.q).toBe(lo)
      else if (pass <= hi) expect(r.q, `eşik ${pass}`).toBe(pass)
      else { expect(r.q).toBe(hi); expect(r.similarity).toBe(0.9) }    // hiçbiri geçmiyor: üst uç (en iyi çaba)
      expect(r.blob).toBe(`q${r.q}`)
    }
  })

  it('kodlama bütçesi daralınca da sınırı aşmaz ve geçen bir kalite döner', async () => {
    for (const budget of [1, 2, 3, 5]) {
      const enc = fakeEncoder(61)
      const r = await searchQuality(enc.evalQ, 40, 95, 0.95, budget)
      expect(enc.calls.length).toBeLessThanOrEqual(budget)
      if (budget >= 2) expect(r.similarity).toBe(0.99)
      expect(r.q).toBeGreaterThanOrEqual(40)
    }
    // tek değerli aralık
    const one = fakeEncoder(10)
    expect((await searchQuality(one.evalQ, 80, 80, 0.95)).q).toBe(80)
    expect(one.calls).toEqual([80])
  })

  it('sabitler: eşik ve aralık makul sınırlarda', () => {
    expect(SIMILARITY_TARGET).toBeGreaterThan(0.9)
    expect(SIMILARITY_TARGET).toBeLessThan(1)
    expect(MAX_SEARCH_ENCODES).toBe(8)
    for (const [lo, hi] of Object.values(QUALITY_RANGE)) { expect(lo).toBeGreaterThanOrEqual(30); expect(hi).toBeLessThanOrEqual(95); expect(lo).toBeLessThan(hi) }
  })
})

describe('görsel motoru — hedef boyut araması', () => {
  const sizeEncoder = (bytesPerQ) => { const calls = []; return { calls, encodeQ: async (q) => { calls.push(q); return { size: q * bytesPerQ } } } }

  it('hedefin altında kalan en yüksek kalite; sınırlı adım', async () => {
    const e = sizeEncoder(1000)
    const r = await bestQualityUnder(e.encodeQ, 35, 80, 60500)
    expect(r.fit.size).toBeLessThanOrEqual(60500)
    expect(r.fit.q).toBeGreaterThanOrEqual(58)                        // 4 adımda 60'a ±2 yaklaşır
    expect(r.fit.q).toBeLessThanOrEqual(60)
    expect(e.calls.length).toBeLessThanOrEqual(6)
    expect(r.encodes).toBe(e.calls.length)
  })

  it('üst kalite zaten sığıyorsa o; taban bile sığmıyorsa fit yok (ölçü küçültülecek)', async () => {
    const a = sizeEncoder(10)
    expect((await bestQualityUnder(a.encodeQ, 35, 80, 5000)).fit.q).toBe(80)
    expect(a.calls).toEqual([35, 80])
    const b = sizeEncoder(1000)
    const r = await bestQualityUnder(b.encodeQ, 35, 80, 20000)
    expect(r.fit).toBeNull()
    expect(r.low).toMatchObject({ q: 35, size: 35000 })
    expect(b.calls).toEqual([35])
    const c = sizeEncoder(1000)
    expect((await bestQualityUnder(c.encodeQ, 50, 50, 60000)).fit.q).toBe(50)
    expect(c.calls).toEqual([50])
  })

  it('hedef kipinde aday seçimi: sığan önce, sonra büyük ölçü, sonra benzerlik, sonra küçük dosya', () => {
    const c = (type, size, w, h, similarity) => ({ type, size, width: w, height: h, similarity })
    expect(pickForTarget([c('jpeg', 300, 100, 100, 0.9), c('png', 150, 50, 50, 1)], 200).type).toBe('png')
    expect(pickForTarget([c('jpeg', 190, 100, 100, 0.9), c('png', 150, 50, 50, 1)], 200).type).toBe('jpeg')      // az küçültülmüş
    expect(pickForTarget([c('jpeg', 190, 100, 100, 0.9), c('webp', 195, 100, 100, 0.97)], 200).type).toBe('webp')
    expect(pickForTarget([c('jpeg', 190, 100, 100, 0.951), c('webp', 150, 100, 100, 0.95)], 200).type).toBe('webp')
    expect(pickForTarget([c('jpeg', 900, 100, 100, 0.9), c('png', 700, 20, 20, 1)], 200).type).toBe('png')       // hiçbiri sığmıyor: en küçük
    expect(pickForTarget([], 200)).toBeNull()
  })
})

describe('görsel motoru — biçim kararı', () => {
  it('"en küçük biçim" karar tablosu', () => {
    const c = (type, size, similarity) => ({ type, size, similarity })
    const T = 0.95
    // hepsi geçiyor: en küçük
    expect(pickSmallest([c('jpeg', 300, 0.96), c('png', 500, 1), c('webp', 200, 0.97)], T).type).toBe('webp')
    // küçük ama eşiğin altında kalan elenir
    expect(pickSmallest([c('jpeg', 300, 0.96), c('png', 100, 0.8), c('webp', 320, 0.97)], T).type).toBe('jpeg')
    // düz renkli logo: paletli PNG birebir ve en küçük
    expect(pickSmallest([c('jpeg', 900, 0.97), c('png', 120, 1), c('webp', 400, 0.99)], T).type).toBe('png')
    // kayıplı adaylar HER ZAMAN yarışır (arama aralığın üst ucunda eşiğin altında bitse bile): eşik altı PNG elenir,
    // biraz daha benzer diye 244 KB'lık PNG 62 KB'lık WebP'nin önüne geçmez
    expect(pickSmallest([c('jpeg', 300, 0.90), c('png', 100, 0.8), c('webp', 320, 0.93)], T).type).toBe('jpeg')
    expect(pickSmallest([c('jpeg', 76, 0.94), c('png', 244, 0.96), c('webp', 62, 0.945)], T).type).toBe('webp')
    // ölçülmemiş (null) benzerlik = kayıpsız sayılır → PNG yarışır
    expect(pickSmallest([c('png', 100, null), c('jpeg', 190, 0.5)], T).type).toBe('png')
    expect(pickSmallest([c('png', 100, null), c('jpeg', 90, 0.5)], T).type).toBe('jpeg')
    // yalnız eşik altı PNG varsa (saydam görsel, WebP yok) o döner
    expect(pickSmallest([c('png', 100, 0.8)], T).type).toBe('png')
    // eşitlikte liste sırası
    expect(pickSmallest([c('jpeg', 100, 0.99), c('webp', 100, 0.99)], T).type).toBe('jpeg')
    expect(pickSmallest([c('jpeg', 100, 0.99)], T).type).toBe('jpeg')
    expect(pickSmallest([], T)).toBeNull()
  })

  it('biçim çözümü: istenen biçim × kaynak × saydamlık × WebP kodlanabilirliği', () => {
    const r = (format, source, alpha, webp) => resolveFormats(format, source, alpha, webp)
    expect(r('jpeg', 'png', true, true)).toEqual({ list: ['jpeg'], notes: [] })
    expect(r('png', 'jpeg', false, true)).toEqual({ list: ['png'], notes: [] })
    expect(r('webp', 'png', true, true)).toEqual({ list: ['webp'], notes: [] })
    expect(r('webp', 'png', true, false)).toEqual({ list: ['png'], notes: ['WEBP_UNAVAILABLE'] })    // saydamlık korunur
    expect(r('webp', 'jpeg', false, false)).toEqual({ list: ['jpeg'], notes: ['WEBP_UNAVAILABLE'] })
    // en küçük: saydam görsel JPEG e gitmez
    expect(r('smallest', 'png', true, true)).toEqual({ list: ['png', 'webp'], notes: [] })
    expect(r('smallest', 'png', false, true)).toEqual({ list: ['jpeg', 'png', 'webp'], notes: [] })
    expect(r('smallest', 'jpeg', false, false)).toEqual({ list: ['jpeg', 'png'], notes: ['WEBP_UNAVAILABLE'] })
    expect(r('smallest', 'gif', true, false)).toEqual({ list: ['png'], notes: ['WEBP_UNAVAILABLE'] })
    // keep: yazılamayan biçimler en yakın eşdeğere
    expect(r('keep', 'jpeg', false, true).list).toEqual(['jpeg'])
    expect(r('keep', 'png', true, true).list).toEqual(['png'])
    expect(r('keep', 'gif', true, true).list).toEqual(['png'])
    expect(r('keep', 'bmp', false, true).list).toEqual(['png'])
    expect(r('keep', 'webp', false, true).list).toEqual(['webp'])
    expect(r('keep', 'avif', false, true).list).toEqual(['webp'])
    expect(r('keep', 'webp', false, false)).toEqual({ list: ['jpeg'], notes: ['WEBP_UNAVAILABLE'] })
    expect(r('keep', 'avif', true, false)).toEqual({ list: ['png'], notes: ['WEBP_UNAVAILABLE'] })
  })

  it('"asla daha kötü": aynı biçim + aynı ölçü + küçülmeyen çıktı → özgün dosya', () => {
    const k = (o) => shouldKeepOriginal({ requestedFormat: 'keep', sourceType: 'jpeg', outType: 'jpeg', sameDimensions: true, outBytes: 100, sourceBytes: 100, ...o })
    expect(k({})).toBe(true)                                             // eşit de "küçülmedi"
    expect(k({ outBytes: 99 })).toBe(false)
    expect(k({ outBytes: 500 })).toBe(true)
    expect(k({ sameDimensions: false, outBytes: 500 })).toBe(false)      // yeniden boyutlandırma istendi: çıktı döner
    // açıkça başka biçim istendi: büyük de olsa dönüştürülür
    expect(k({ requestedFormat: 'png', outType: 'png', outBytes: 500 })).toBe(false)
    expect(k({ requestedFormat: 'jpeg', outBytes: 500 })).toBe(true)     // aynı biçim açıkça istendi
    // keep / smallest başka biçime düştüyse (gif → png, en küçük biçim) ve özgün daha küçükse özgün kalır
    expect(k({ sourceType: 'gif', outType: 'png', outBytes: 500 })).toBe(true)
    expect(k({ requestedFormat: 'smallest', sourceType: 'png', outType: 'webp', outBytes: 500 })).toBe(true)
    expect(k({ requestedFormat: 'smallest', sourceType: 'png', outType: 'webp', outBytes: 50 })).toBe(false)
  })

  it('tasarruf yüzdesi: bir ondalık, AŞAĞI yuvarlanır; büyüyen çıktıda 0', () => {
    expect(savingPct(1000, 400)).toBe(60)
    expect(savingPct(3000, 1001)).toBe(66.6)                              // %66,63 → 66,6
    expect(savingPct(1000, 1)).toBe(99.9)
    expect(savingPct(1000, 999)).toBe(0.1)
    expect(savingPct(1000, 1000)).toBe(0)
    expect(savingPct(1000, 2000)).toBe(0)
    expect(savingPct(0, 10)).toBe(0)
  })

  it('PNG kalite → palet boyu; 100 = kayıpsız', () => {
    expect(pngColorsForQuality(100)).toBe(0)
    expect(pngColorsForQuality(99)).toBe(251)
    expect(pngColorsForQuality(50)).toBe(64)
    expect(pngColorsForQuality(25)).toBe(16)
    expect(pngColorsForQuality(1)).toBe(8)
  })

  it('saydamlığı düz renge bindirme ve saydamlık taraması', () => {
    const d = new Uint8ClampedArray([255, 0, 0, 255, 10, 20, 30, 0, 0, 0, 0, 128])
    expect(hasTransparency(d, 3)).toBe(true)
    expect(hasTransparency(d, 1)).toBe(false)
    const f = flattenOnto(d, 3, [255, 255, 255])
    expect(Array.from(f)).toEqual([255, 0, 0, 255, 255, 255, 255, 255, 127, 127, 127, 255])
    expect(Array.from(flattenOnto(d, 3, [0, 100, 200]).subarray(4, 8))).toEqual([0, 100, 200, 255])
    expect(hasTransparency(f, 3)).toBe(false)
  })
})

describe('görsel motoru — akıllı kırpma', () => {
  /** Geniş görsel: bir yanında ayrıntı (dama + renk), gerisi düz. */
  function detailAt(w, h, x0, x1) {
    const d = solid(w, h, [200, 200, 200, 255])
    const rnd = rng(2)
    for (let y = 0; y < h; y++) for (let x = x0; x < x1; x++) { const i = (y * w + x) * 4; d[i] = rnd() * 255; d[i + 1] = rnd() * 255; d[i + 2] = rnd() * 255 }
    return d
  }

  it('enerji haritası düz bölgede 0, ayrıntıda yüksek; toplam alan tablosu doğru', () => {
    const d = detailAt(40, 20, 30, 40)
    const e = energyMap(d, 40, 20)
    expect(e[5 * 40 + 5]).toBe(0)
    expect(e[5 * 40 + 35]).toBeGreaterThan(10)
    const sat = integralImage(Float32Array.from([1, 2, 3, 4, 5, 6]), 3, 2)
    expect(sat[(2) * 4 + 3]).toBe(21)
    expect(sat[(1) * 4 + 2]).toBe(3)
    expect(sat[0]).toBe(0)
  })

  it('pencere AYRINTILI tarafı seçer ve her zaman görselin içinde kalır', () => {
    for (const [x0, x1, side] of [[0, 30, 'sol'], [170, 200, 'sağ'], [90, 110, 'orta']]) {
      const w = 200
      const h = 50
      const d = detailAt(w, h, x0, x1)
      const plan = planResize(w * 10, h * 10, { method: 'cover', width: 300, height: 300 })       // kaynak 2000 × 500
      const off = smartCropOffset({ data: d, width: w, height: h }, w * 10, h * 10, plan)
      expect(off, side).toBeGreaterThanOrEqual(0)
      expect(off + plan.window.w, side).toBeLessThanOrEqual(w * 10)
      const center = off + plan.window.w / 2
      expect(Math.abs(center - ((x0 + x1) / 2) * 10), side).toBeLessThan(260)                    // ayrıntı pencerenin içinde
      expect(off <= x0 * 10 + 1 && off + plan.window.w >= x1 * 10 - 1, side).toBe(true)
    }
  })

  it('düz görselde pencere ORTADA kalır; dikey eksende de çalışır', () => {
    const flat = solid(100, 40, [50, 60, 70, 255])
    const plan = planResize(1000, 400, { method: 'cover', width: 100, height: 100 })
    expect(smartCropOffset({ data: flat, width: 100, height: 40 }, 1000, 400, plan)).toBe(300)
    // dikey: ayrıntı altta
    const w = 40
    const h = 160
    const d = solid(w, h, [10, 10, 10, 255])
    const rnd = rng(4)
    for (let y = 130; y < 160; y++) for (let x = 0; x < w; x++) { const i = (y * w + x) * 4; d[i] = rnd() * 255; d[i + 1] = rnd() * 255 }
    const tall = planResize(400, 1600, { method: 'cover', width: 200, height: 200 })
    expect(tall.cropAxis).toBe('y')
    const off = smartCropOffset({ data: d, width: w, height: h }, 400, 1600, tall)
    expect(off).toBe(1200)
    const sat = integralImage(energyMap(d, w, h), w, h)
    expect(bestWindowOffset(sat, w, h, w, h, 'y')).toBe(0)                 // pencere görsel kadar: kayacak yer yok
    expect(ANALYSIS_SIDE).toBeLessThanOrEqual(256)
  })
})

describe('görsel motoru — düz zeminde serbest duran nesne', () => {
  function objectOn(w, h, bg, box, color = [200, 30, 30, 255]) {
    const d = solid(w, h, bg)
    for (let y = box.y0; y < box.y1; y++) for (let x = box.x0; x < box.x1; x++) d.set(color, (y * w + x) * 4)
    return d
  }

  it('düz zemin + nesne: zemin rengi ve nesne kutusu bulunur (opak ve saydam zemin)', () => {
    const box = { x0: 30, y0: 20, x1: 70, y1: 60 }
    const white = detectObject(objectOn(100, 80, [250, 250, 250, 255], box), 100, 80)
    expect(white.background).toEqual([250, 250, 250, 255])
    expect(white.bbox).toEqual(box)
    const clear = detectObject(objectOn(100, 80, [0, 0, 0, 0], box), 100, 80)
    expect(clear.background).toEqual([0, 0, 0, 0])
    expect(clear.bbox).toEqual(box)
    // JPEG gürültüsü kadar oynayan zemin de "düz" sayılır
    const noisy = objectOn(100, 80, [240, 240, 240, 255], box)
    const rnd = rng(6)
    for (let i = 0; i < noisy.length; i += 4) if (noisy[i] === 240) { const n = (rnd() - 0.5) * 12; noisy[i] += n; noisy[i + 1] += n; noisy[i + 2] += n }
    expect(detectObject(noisy, 100, 80).bbox).toEqual(box)
  })

  it('fotoğraf, kenara taşan içerik, çok küçük ya da her yeri kaplayan nesne: tespit YOK', () => {
    expect(detectObject(photoLike(100, 80, 3, 40), 100, 80)).toBeNull()
    expect(detectObject(solid(100, 80, [255, 255, 255, 255]), 100, 80)).toBeNull()                          // nesne yok
    expect(detectObject(objectOn(100, 80, [255, 255, 255, 255], { x0: 50, y0: 40, x1: 51, y1: 41 }), 100, 80)).toBeNull()   // 1 piksel
    expect(detectObject(objectOn(100, 80, [255, 255, 255, 255], { x0: 1, y0: 1, x1: 99, y1: 79 }), 100, 80)).toBeNull()    // neredeyse tamamı
    expect(detectObject(objectOn(100, 80, [255, 255, 255, 255], { x0: 0, y0: 0, x1: 60, y1: 80 }), 100, 80)).toBeNull()    // kenara taşıyor
    expect(detectObject(solid(4, 4, [1, 2, 3, 255]), 4, 4)).toBeNull()                                      // çok küçük görsel
  })
})
