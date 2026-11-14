import { describe, it, expect } from 'vitest'
import { normalizeOptions, parseBackground } from '../utils/imageEngine/options.js'
import { planResize, placeWindow, planThumbObject, shrinkDims } from '../utils/imageEngine/geometry.js'
import { ENGINE_LIMITS, EngineError, ERROR_CODES, NOTE_CODES, OUTPUT_FORMATS, RESIZE_METHODS } from '../utils/imageEngine/errors.js'

const invalid = (options) => {
  try {
    normalizeOptions(options)
  } catch (e) {
    expect(e).toBeInstanceOf(EngineError)
    expect(e.code).toBe('INVALID_OPTIONS')
    return e.params
  }
  throw new Error('hata bekleniyordu')
}

describe('görsel motoru — seçenek doğrulama', () => {
  it('eksik alanlar varsayılanla tamamlanır (hiçbir şey verilmese de)', () => {
    const d = { resize: { method: 'none' }, format: 'keep', quality: 'auto', targetBytes: null, background: '#ffffff',
      backgroundRgb: [255, 255, 255], preserve: { copyright: false, creation: false, location: false } }
    expect(normalizeOptions()).toEqual(d)
    expect(normalizeOptions({})).toEqual(d)
    expect(normalizeOptions(null)).toEqual(d)
  })

  it('doğrulanmış seçenek yeniden doğrulanınca aynı kalır (Worker iletisi ikinci kez doğrulanır)', () => {
    const o = normalizeOptions({ resize: { method: 'cover', width: 300, height: 200 }, format: 'smallest', quality: 72.4,
      targetBytes: 200000.9, background: '#A1B2C3', preserve: { copyright: true } })
    expect(o.quality).toBe(72)
    expect(o.targetBytes).toBe(200000)
    expect(o.background).toBe('#a1b2c3')
    expect(o.backgroundRgb).toEqual([0xa1, 0xb2, 0xc3])
    expect(normalizeOptions(o)).toEqual(o)
  })

  it('her yanlış alan INVALID_OPTIONS verir ve alanı adlandırır', () => {
    expect(invalid('x')).toEqual({ field: 'options', reason: 'type' })
    expect(invalid([])).toEqual({ field: 'options', reason: 'type' })
    expect(invalid({ resize: 5 })).toEqual({ field: 'resize', reason: 'type' })
    expect(invalid({ resize: { method: 'stretch' } })).toEqual({ field: 'resize.method', reason: 'unknown' })
    expect(invalid({ format: 'avif' })).toEqual({ field: 'format', reason: 'unknown' })
    expect(invalid({ quality: 'best' })).toEqual({ field: 'quality', reason: 'type' })
    expect(invalid({ quality: 0 })).toEqual({ field: 'quality', reason: 'range' })
    expect(invalid({ quality: 101 })).toEqual({ field: 'quality', reason: 'range' })
    expect(invalid({ quality: NaN })).toEqual({ field: 'quality', reason: 'type' })
    expect(invalid({ targetBytes: '200' })).toEqual({ field: 'targetBytes', reason: 'type' })
    expect(invalid({ targetBytes: 0 })).toEqual({ field: 'targetBytes', reason: 'range' })
    expect(invalid({ targetBytes: Infinity })).toEqual({ field: 'targetBytes', reason: 'type' })
    expect(invalid({ background: 'white' })).toEqual({ field: 'background', reason: 'format' })
    expect(invalid({ background: '#fff' })).toEqual({ field: 'background', reason: 'format' })
    expect(invalid({ background: '#gggggg' })).toEqual({ field: 'background', reason: 'format' })
    expect(invalid({ preserve: true })).toEqual({ field: 'preserve', reason: 'type' })
    expect(invalid({ preserve: { location: 'yes' } })).toEqual({ field: 'preserve.location', reason: 'type' })
  })

  it('scale: genişlik ya da yükseklikten TAM BİRİ; fit / cover / thumb: ikisi de', () => {
    expect(normalizeOptions({ resize: { method: 'scale', width: 800 } }).resize).toEqual({ method: 'scale', width: 800 })
    expect(normalizeOptions({ resize: { method: 'scale', height: 600 } }).resize).toEqual({ method: 'scale', height: 600 })
    expect(invalid({ resize: { method: 'scale' } })).toEqual({ field: 'resize.width', reason: 'exactly_one' })
    expect(invalid({ resize: { method: 'scale', width: 800, height: 600 } })).toEqual({ field: 'resize.height', reason: 'exactly_one' })
    for (const method of ['fit', 'cover', 'thumb']) {
      expect(invalid({ resize: { method, width: 100 } })).toEqual({ field: 'resize.height', reason: 'required' })
      expect(invalid({ resize: { method, height: 100 } })).toEqual({ field: 'resize.width', reason: 'required' })
      expect(normalizeOptions({ resize: { method, width: 100, height: 50 } }).resize).toEqual({ method, width: 100, height: 50 })
    }
  })

  it('ölçü: tamsayı ve 1..maxSide', () => {
    expect(invalid({ resize: { method: 'scale', width: 0 } })).toEqual({ field: 'resize.width', reason: 'range' })
    expect(invalid({ resize: { method: 'scale', width: -5 } })).toEqual({ field: 'resize.width', reason: 'range' })
    expect(invalid({ resize: { method: 'scale', width: 10.5 } })).toEqual({ field: 'resize.width', reason: 'integer' })
    expect(invalid({ resize: { method: 'scale', width: '800' } })).toEqual({ field: 'resize.width', reason: 'integer' })
    expect(invalid({ resize: { method: 'fit', width: 100, height: ENGINE_LIMITS.maxSide + 1 } })).toEqual({ field: 'resize.height', reason: 'range' })
    expect(parseBackground('#000000')).toEqual([0, 0, 0])
  })

  it('sözleşme sabitleri (UI bu adlara göre kodlanır)', () => {
    expect(ENGINE_LIMITS).toEqual({ maxInputBytes: 25 * 1024 * 1024, maxPixels: 50_000_000, maxSide: 30_000 })
    expect(OUTPUT_FORMATS).toEqual(['keep', 'jpeg', 'png', 'webp', 'smallest'])
    expect(RESIZE_METHODS).toEqual(['none', 'scale', 'fit', 'cover', 'thumb'])
    expect(ERROR_CODES).toEqual(['UNSUPPORTED_FORMAT', 'TOO_LARGE', 'TOO_MANY_PIXELS', 'DECODE_FAILED', 'BROWSER_UNSUPPORTED',
      'ENCODE_FAILED', 'CANCELLED', 'INVALID_OPTIONS'])
    expect(NOTE_CODES).toEqual(['KEPT_ORIGINAL', 'NOT_UPSCALED', 'TRUECOLOR_KEPT', 'ALPHA_FLATTENED', 'WEBP_UNAVAILABLE',
      'TARGET_NOT_REACHED', 'METADATA_PRESERVED', 'METADATA_NOT_PRESERVED', 'ORIENTATION_APPLIED', 'ANIMATION_DROPPED',
      'SMART_CROP', 'THUMB_PADDED', 'COLOR_PROFILE_CONVERTED'])
  })
})

describe('görsel motoru — hedef ölçü hesabı', () => {
  it('none: ölçü aynı, kırpma yok', () => {
    expect(planResize(800, 600, { method: 'none' })).toMatchObject({ outW: 800, outH: 600, resized: false, cropped: false, notUpscaled: false })
  })

  it('scale: oran korunur, tek kenar verilir', () => {
    expect(planResize(4000, 3000, { method: 'scale', width: 1000 })).toMatchObject({ outW: 1000, outH: 750, resized: true, cropped: false })
    expect(planResize(4000, 3000, { method: 'scale', height: 600 })).toMatchObject({ outW: 800, outH: 600, resized: true })
    expect(planResize(1000, 333, { method: 'scale', width: 100 })).toMatchObject({ outW: 100, outH: 33 })
    // aşırı ince görsel: kısa kenar en az 1 piksel
    expect(planResize(5000, 3, { method: 'scale', width: 100 })).toMatchObject({ outW: 100, outH: 1 })
  })

  it('HİÇBİR yöntem büyütmez (NOT_UPSCALED yalnız gerçekten istenince)', () => {
    expect(planResize(800, 600, { method: 'scale', width: 1600 })).toMatchObject({ outW: 800, outH: 600, resized: false, notUpscaled: true })
    expect(planResize(800, 600, { method: 'scale', width: 800 })).toMatchObject({ outW: 800, outH: 600, resized: false, notUpscaled: false })
    expect(planResize(800, 600, { method: 'scale', height: 601 })).toMatchObject({ resized: false, notUpscaled: true })
    expect(planResize(800, 600, { method: 'fit', width: 2000, height: 2000 })).toMatchObject({ outW: 800, outH: 600, resized: false, notUpscaled: true })
    expect(planResize(800, 600, { method: 'fit', width: 800, height: 600 })).toMatchObject({ resized: false, notUpscaled: false })
    // cover: kutu görselden büyük → oran tutulur ama büyütülmez
    const c = planResize(300, 200, { method: 'cover', width: 500, height: 500 })
    expect(c).toMatchObject({ outW: 200, outH: 200, notUpscaled: true, cropped: true })
    expect(c.window).toEqual({ x: 50, y: 0, w: 200, h: 200 })
  })

  it('fit: kutunun İÇİNE sığar, oran korunur', () => {
    expect(planResize(4000, 3000, { method: 'fit', width: 1000, height: 1000 })).toMatchObject({ outW: 1000, outH: 750, resized: true, cropped: false })
    expect(planResize(3000, 4000, { method: 'fit', width: 1000, height: 1000 })).toMatchObject({ outW: 750, outH: 1000 })
    expect(planResize(4000, 3000, { method: 'fit', width: 5000, height: 300 })).toMatchObject({ outW: 400, outH: 300 })
    for (const [w, h, bw, bh] of [[1234, 777, 300, 300], [777, 1234, 311, 97], [5000, 5000, 1, 1], [3, 5000, 100, 100]]) {
      const p = planResize(w, h, { method: 'fit', width: bw, height: bh })
      expect(p.outW).toBeLessThanOrEqual(bw)
      expect(p.outH).toBeLessThanOrEqual(bh)
      expect(p.outW).toBeGreaterThanOrEqual(1)
      expect(p.outH).toBeGreaterThanOrEqual(1)
      expect(p.outW === bw || p.outH === bh).toBe(true)
    }
  })

  it('cover: TAM kutu ölçüsü, kutunun oranındaki en büyük pencere ortadan', () => {
    const wide = planResize(4000, 2000, { method: 'cover', width: 500, height: 500 })
    expect(wide).toMatchObject({ outW: 500, outH: 500, resized: true, cropped: true, cropAxis: 'x' })
    expect(wide.window).toEqual({ x: 1000, y: 0, w: 2000, h: 2000 })
    const tall = planResize(2000, 4000, { method: 'cover', width: 600, height: 300 })
    expect(tall).toMatchObject({ outW: 600, outH: 300, cropAxis: 'y' })
    expect(tall.window).toEqual({ x: 0, y: 1500, w: 2000, h: 1000 })
    // oran aynı: kırpma yok
    expect(planResize(4000, 2000, { method: 'cover', width: 400, height: 200 })).toMatchObject({ outW: 400, outH: 200, cropped: false, cropAxis: null })
    // kırpma var ama küçültme yok (pencere tam kutu kadar)
    expect(planResize(800, 500, { method: 'cover', width: 500, height: 500 })).toMatchObject({ outW: 500, outH: 500, resized: true, cropped: true })
  })

  it('pencere yerleştirme görselin içinde kalır', () => {
    const p = planResize(4000, 2000, { method: 'cover', width: 500, height: 500 })
    expect(placeWindow(p, 4000, 2000, 0).window.x).toBe(0)
    expect(placeWindow(p, 4000, 2000, 99999).window.x).toBe(2000)
    expect(placeWindow(p, 4000, 2000, -50).window.x).toBe(0)
    expect(placeWindow(p, 4000, 2000, 1234.4).window).toEqual({ x: 1234, y: 0, w: 2000, h: 2000 })
    const none = planResize(800, 600, { method: 'none' })
    expect(placeWindow(none, 800, 600, 50)).toBe(none)
  })

  it('thumb nesne kipi: nesnenin tamamı pencerede; görsele sığmazsa dolgu', () => {
    // geniş görselde ortadaki nesne, kare küçük resim: pencere görselin içinde kalabilir → dolgu yok
    const a = planThumbObject(2000, 1000, 300, 300, { x0: 800, y0: 300, x1: 1200, y1: 700 })
    expect(a).toMatchObject({ outW: 300, outH: 300, padded: false, cropped: true })
    expect(a.window.x).toBeLessThanOrEqual(800)
    expect(a.window.x + a.window.w).toBeGreaterThanOrEqual(1200)
    expect(a.window.w).toBe(a.window.h)
    // nesne görselin neredeyse tamamı, oran farklı: pencere taşar → dolgu
    const b = planThumbObject(1000, 400, 300, 300, { x0: 50, y0: 20, x1: 950, y1: 380 })
    expect(b.padded).toBe(true)
    expect(b.cropped).toBe(false)
    expect(b.window.y).toBeLessThan(0)
    expect(b.window.x).toBeLessThanOrEqual(50)
    expect(b.window.x + b.window.w).toBeGreaterThanOrEqual(950)
    // nesne kutudan küçük: büyütülmez, pencere kutu kadar kaynak pikseli alır
    const c = planThumbObject(100, 100, 300, 300, { x0: 30, y0: 30, x1: 70, y1: 70 })
    expect(c.window.w).toBe(300)
    expect(c.window.h).toBe(300)
    expect(c.padded).toBe(true)
  })

  it('hedef boyut kipi: küçültme oranı korur, kenar 16 pikselin altına inmez', () => {
    expect(shrinkDims(1000, 500, 0.5, 16)).toEqual({ width: 500, height: 250 })
    expect(shrinkDims(100, 20, 0.5, 16)).toEqual({ width: 80, height: 16 })
    expect(shrinkDims(100, 16, 0.5, 16)).toBeNull()
    expect(shrinkDims(16, 16, 0.5, 16)).toBeNull()
    expect(shrinkDims(10, 400, 0.5, 16)).toBeNull()       // zaten tabanın altında: daha da küçültülmez
    expect(shrinkDims(17, 17, 0.99, 16)).toBeNull()        // yuvarlamada değişmeyen ölçü: adım yok
  })
})
