import { describe, it, expect, vi } from 'vitest'
import {
  COLOR_COUNT_CAP, bandingRisk, countColors, estimateJpegQuality, readImageFacts, svgCandidate,
} from '../utils/imageEngine/analyze.js'
import { processCore } from '../utils/imageEngine/pipeline.js'
import { encodePng } from '../utils/imageEngine/pngWriter.js'
import { fakePlatform, logoWithAlpha, photoLike, solid } from './imageEngineKit.test.helpers.js'

vi.setConfig({ testTimeout: 60_000 })

// Görsel aracı derin karşılaştırma (2026-11-15): başlık bilgileri (bit derinliği, renk modeli, PPI, JPEG kalite tahmini,
// alt örnekleme, aşamalı), renk sayımı, SVG adaylığı, bantlaşma riski; iş akışı sonucunda `analysis` alanı.

const STD = [
  16, 11, 10, 16, 24, 40, 51, 61, 12, 12, 14, 19, 26, 58, 60, 55, 14, 13, 16, 24, 40, 57, 69, 56, 14, 17, 22, 29, 51, 87, 80, 62,
  18, 22, 37, 56, 68, 109, 103, 77, 24, 35, 55, 64, 81, 104, 113, 92, 49, 64, 78, 87, 103, 121, 120, 101, 72, 92, 95, 98, 112, 100, 103, 99,
]
/** IJG'nin kalite → tablo kuralı (libjpeg jpeg_quality_scaling). */
function ijgTable(q) {
  const scale = q < 50 ? Math.floor(5000 / q) : 200 - q * 2
  return STD.map((v) => Math.max(1, Math.min(255, Math.floor((v * scale + 50) / 100))))
}

function jpegHeader({ quality = 75, sampling = [0x22, 0x11, 0x11], progressive = false, density = { units: 1, x: 72, y: 72 }, precision = 8 } = {}) {
  const out = [0xff, 0xd8]
  if (density) {
    out.push(0xff, 0xe0, 0, 16, 0x4a, 0x46, 0x49, 0x46, 0, 1, 1, density.units, density.x >> 8, density.x & 255, density.y >> 8, density.y & 255, 0, 0)
  }
  const t = ijgTable(quality)
  out.push(0xff, 0xdb, 0, 67, 0x00, ...t)
  out.push(0xff, progressive ? 0xc2 : 0xc0, 0, 17, precision, 0, 16, 0, 16, 3, 1, sampling[0], 0, 2, sampling[1], 1, 3, sampling[2], 1)
  out.push(0xff, 0xda, 0, 2)
  out.push(0xff, 0xd9)
  return new Uint8Array(out)
}

function chunk(type, data) {
  const len = data.length
  return [len >>> 24, (len >> 16) & 255, (len >> 8) & 255, len & 255, ...[...type].map((c) => c.charCodeAt(0)), ...data, 0, 0, 0, 0]
}
function pngHeader({ bitDepth = 8, colorType = 6, ppm = null, palette = 0 } = {}) {
  const ihdr = [0, 0, 0, 10, 0, 0, 0, 10, bitDepth, colorType, 0, 0, 0]
  const out = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, ...chunk('IHDR', ihdr)]
  if (ppm) out.push(...chunk('pHYs', [ppm >>> 24, (ppm >> 16) & 255, (ppm >> 8) & 255, ppm & 255, ppm >>> 24, (ppm >> 16) & 255, (ppm >> 8) & 255, ppm & 255, 1]))
  if (palette) out.push(...chunk('PLTE', new Array(palette * 3).fill(0)))
  out.push(...chunk('IDAT', [1, 2, 3]), ...chunk('IEND', []))
  return new Uint8Array(out)
}

describe('readImageFacts — JPEG', () => {
  it('kalite tahmini IJG tablosuna göre (±2); alt örnekleme, aşamalı, bit derinliği, PPI', () => {
    for (const q of [30, 50, 75, 85, 92]) {
      const f = readImageFacts(jpegHeader({ quality: q }), 'jpeg')
      expect(Math.abs(f.jpeg.quality - q), `q=${q}`).toBeLessThanOrEqual(2)
    }
    const f = readImageFacts(jpegHeader({ quality: 80 }), 'jpeg')
    expect(f).toMatchObject({ bitDepth: 8, colorModel: 'rgb', ppi: { x: 72, y: 72 }, lossless: false, paletteSize: null })
    expect(f.jpeg).toMatchObject({ subsampling: '4:2:0', progressive: false, components: 3 })
    expect(readImageFacts(jpegHeader({ sampling: [0x11, 0x11, 0x11] }), 'jpeg').jpeg.subsampling).toBe('4:4:4')
    expect(readImageFacts(jpegHeader({ sampling: [0x21, 0x11, 0x11] }), 'jpeg').jpeg.subsampling).toBe('4:2:2')
    expect(readImageFacts(jpegHeader({ progressive: true }), 'jpeg').jpeg.progressive).toBe(true)
    expect(readImageFacts(jpegHeader({ density: { units: 2, x: 118, y: 118 } }), 'jpeg').ppi).toEqual({ x: 299.7, y: 299.7 })
    expect(readImageFacts(jpegHeader({ density: { units: 0, x: 1, y: 1 } }), 'jpeg').ppi).toBeNull()
    expect(readImageFacts(jpegHeader({ density: null }), 'jpeg').ppi).toBeNull()
  })

  it('estimateJpegQuality sınırları: 1–100, bozuk tablo null', () => {
    expect(estimateJpegQuality(new Array(64).fill(1))).toBe(100)
    expect(estimateJpegQuality(new Array(64).fill(255))).toBeGreaterThanOrEqual(1)
    expect(estimateJpegQuality(new Array(10).fill(5))).toBeNull()
    expect(estimateJpegQuality(null)).toBeNull()
  })
})

describe('readImageFacts — diğer türler', () => {
  it('PNG: bit derinliği, renk türü, pHYs → PPI, palet boyu', () => {
    expect(readImageFacts(pngHeader({ bitDepth: 16, colorType: 2, ppm: 2835 }), 'png'))
      .toMatchObject({ bitDepth: 16, colorModel: 'rgb', ppi: { x: 72, y: 72 }, lossless: true })
    expect(readImageFacts(pngHeader({ bitDepth: 8, colorType: 3, palette: 37 }), 'png')).toMatchObject({ colorModel: 'palette', paletteSize: 37 })
    expect(readImageFacts(pngHeader({ colorType: 4 }), 'png').colorModel).toBe('gray-alpha')
  })

  it('GIF palet; WebP kayıplı / kayıpsız; BMP', () => {
    const gif = new Uint8Array([0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 10, 0, 10, 0, 0x87, 0, 0])
    expect(readImageFacts(gif, 'gif')).toMatchObject({ colorModel: 'palette', paletteSize: 256, lossless: true })
    const riff = (fourcc, body) => new Uint8Array([0x52, 0x49, 0x46, 0x46, 0, 0, 0, 0, 0x57, 0x45, 0x42, 0x50,
      ...[...fourcc].map((c) => c.charCodeAt(0)), body.length, 0, 0, 0, ...body])
    expect(readImageFacts(riff('VP8 ', [0, 0, 0, 0, 0, 0, 0, 0, 0, 0]), 'webp')).toMatchObject({ lossless: false, colorModel: 'rgb' })
    expect(readImageFacts(riff('VP8L', [0x2f, 0, 0, 0, 0x10]), 'webp')).toMatchObject({ lossless: true, colorModel: 'rgba' })
    const bmp = new Uint8Array(54)
    bmp[0] = 0x42; bmp[1] = 0x4d; bmp[28] = 24; bmp[38] = 0x13; bmp[39] = 0x0b; bmp[42] = 0x13; bmp[43] = 0x0b
    expect(readImageFacts(bmp, 'bmp')).toMatchObject({ bitDepth: 8, colorModel: 'rgb', ppi: { x: 72, y: 72 } })
  })

  it('bozuk / rastgele bayt: fırlatmaz, tanınmayan alan null; döngüler hızlı biter', () => {
    const rnd = new Uint8Array(600_000)
    let x = 1
    for (let i = 0; i < rnd.length; i++) { x = (x * 1103515245 + 12345) >>> 0; rnd[i] = x >>> 24 }
    rnd[0] = 0xff; rnd[1] = 0xd8
    const t0 = Date.now()
    for (const type of ['jpeg', 'png', 'gif', 'webp', 'bmp', 'avif', null]) expect(() => readImageFacts(rnd, type)).not.toThrow()
    const ffs = new Uint8Array(600_000).fill(0xff)
    expect(() => readImageFacts(ffs, 'jpeg')).not.toThrow()
    expect(Date.now() - t0).toBeLessThan(2000)
    expect(readImageFacts(null, 'jpeg')).toEqual({ bitDepth: null, colorModel: null, ppi: null, jpeg: null, paletteSize: null, lossless: null })
    expect(readImageFacts(new Uint8Array(4), 'png').bitDepth).toBeNull()
  })
})

describe('renk sayımı, SVG adaylığı, bantlaşma', () => {
  it('düz renk 1, saydamlık ayrı; çok renkli görsel tavanda durur', () => {
    expect(countColors(solid(20, 20, [10, 20, 30]), 20, 20)).toEqual({ count: 1, atLeast: false, dominant: 1, dominantShare: 1 })
    const logo = logoWithAlpha(64, 64)
    const c = countColors(logo, 64, 64)
    expect(c.count).toBeGreaterThanOrEqual(2)
    expect(c.atLeast).toBe(false)
    const photo = photoLike(400, 400, 3, 40)
    expect(countColors(photo, 400, 400)).toEqual({ count: COLOR_COUNT_CAP, atLeast: true, dominant: null, dominantShare: null })
    expect(countColors(null, 10, 10)).toEqual({ count: null, atLeast: false })
    // baskın renk: 2 düz renk + 150 seyrek kenar rengi (her biri tek piksel) → 2 baskın, pay yüksek
    const W = 100
    const H = 100
    const buf = new Uint8ClampedArray(W * H * 4)
    for (let i = 0; i < W * H; i++) {
      const o = i * 4
      if (i < 150) { buf[o] = i; buf[o + 1] = 255 - i; buf[o + 2] = 7; buf[o + 3] = 120 }
      else if (i < 5000) { buf[o] = 129; buf[o + 1] = 51; buf[o + 2] = 135; buf[o + 3] = 255 }
      else { buf[o] = 100; buf[o + 1] = 166; buf[o + 2] = 79; buf[o + 3] = 255 }
    }
    const dom = countColors(buf, W, H)
    expect(dom.count).toBe(152)
    expect(dom.dominant).toBe(2)
    expect(dom.dominantShare).toBeCloseTo(0.985, 3)
    expect(svgCandidate(dom, W, H)).toBe(true)
  })

  it('svgCandidate: az renk + küçük ölçü; bilinmeyen / çok renk / büyük ölçü değil', () => {
    expect(svgCandidate({ count: 6, atLeast: false }, 256, 128)).toBe(true)
    expect(svgCandidate({ count: 6, atLeast: false }, 2400, 800)).toBe(false)
    expect(svgCandidate({ count: 300, atLeast: false }, 256, 128)).toBe(false)
    expect(svgCandidate({ count: COLOR_COUNT_CAP, atLeast: true }, 256, 128)).toBe(false)
    // kenar yumuşatmalı logo: çok ara renk ama 3 baskın renk alanın %90'ını kaplıyor → aday
    expect(svgCandidate({ count: 300, atLeast: false, dominant: 3, dominantShare: 0.9 }, 256, 128)).toBe(true)
    expect(svgCandidate({ count: 300, atLeast: false, dominant: 3, dominantShare: 0.6 }, 256, 128)).toBe(false)
    expect(svgCandidate({ count: 300, atLeast: false, dominant: 12, dominantShare: 0.95 }, 256, 128)).toBe(false)
    expect(svgCandidate({ count: 300, atLeast: false, dominant: 3, dominantShare: 0.9 }, 8, 8)).toBe(false)
    expect(svgCandidate({ count: null, atLeast: false }, 256, 128)).toBe(false)
  })

  it('bandingRisk: 16 bit kaynak; az renkli palet + benzerlik düşük', () => {
    expect(bandingRisk({ inputBitDepth: 16, outputType: 'jpeg' })).toBe('bit_depth')
    expect(bandingRisk({ inputBitDepth: 8, outputType: 'png', outputColors: 32, similarity: 0.97 })).toBe('palette')
    expect(bandingRisk({ inputBitDepth: 8, outputType: 'png', outputColors: 32, similarity: 0.99 })).toBeNull()
    expect(bandingRisk({ inputBitDepth: 8, outputType: 'png', outputColors: 200, similarity: 0.9 })).toBeNull()
    expect(bandingRisk({ inputBitDepth: null, outputType: 'webp' })).toBeNull()
  })
})

describe('iş akışı sonucu — analysis alanı', () => {
  it('PNG logo: kaynak / çıktı başlık bilgileri, renk sayısı, denenen biçimler, SVG adaylığı', async () => {
    const w = 120
    const h = 80
    const src = new Blob([(await encodePng({ width: w, height: h, rgba: logoWithAlpha(w, h) }, { level: 0 })).bytes], { type: 'image/png' })
    const r = await processCore(src, { format: 'smallest' }, { platform: fakePlatform() })
    expect(r.analysis).toBeTruthy()
    expect(r.analysis.input).toMatchObject({ bitDepth: 8, colorModel: 'rgba', lossless: true })
    expect(r.analysis.colors.count).toBeGreaterThan(0)
    expect(r.analysis.candidates.length).toBeGreaterThanOrEqual(2)
    for (const c of r.analysis.candidates) {
      expect(['jpeg', 'png', 'webp']).toContain(c.type)
      expect(c.bytes).toBeGreaterThan(0)
    }
    expect(r.analysis.svgCandidate).toBe(true)
    if (r.type === 'png') expect(r.analysis.output.colorModel).toBe('palette')
  })
})
