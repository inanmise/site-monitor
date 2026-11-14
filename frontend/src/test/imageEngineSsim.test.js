import { describe, it, expect, vi } from 'vitest'
import {
  SAMPLE_MAX_PIXELS, TILE, assembleSample, extractSample, half, lumaPlane, samplePlan, similarity, ssimPlane, ssimPlaneSimple,
} from '../utils/imageEngine/ssim.js'
import { logoWithAlpha, photoLike, rng, solid } from './imageEngineKit.test.helpers.js'

// Piksel döngüleri kapsam (v8) ölçümüyle birkaç kat yavaşlar (CI: 2 vCPU): varsayılan 15 sn yerine geniş süre sınırı.
vi.setConfig({ testTimeout: 60_000 })

function withNoise(src, amount, seed = 1) {
  const rnd = rng(seed)
  const out = Uint8ClampedArray.from(src)
  for (let i = 0; i < out.length; i += 4) for (let c = 0; c < 3; c++) out[i + c] = src[i + c] + (rnd() - 0.5) * 2 * amount
  return out
}

describe('görsel motoru — yapısal benzerlik (SSIM)', () => {
  it('özdeş görsel = 1 (tam); gürültü arttıkça benzerlik TEK YÖNLÜ düşer', () => {
    const w = 160
    const h = 120
    const src = photoLike(w, h, 4)
    expect(similarity(src, src, w, h)).toBe(1)
    expect(similarity(src, Uint8ClampedArray.from(src), w, h)).toBe(1)
    let prev = 1
    for (const amount of [2, 6, 14, 30, 60]) {
      const s = similarity(src, withNoise(src, amount), w, h)
      expect(s, `gürültü ${amount}`).toBeLessThan(prev)
      expect(s).toBeGreaterThanOrEqual(0)
      prev = s
    }
    expect(similarity(src, withNoise(src, 2), w, h)).toBeGreaterThan(0.97)
    expect(similarity(src, withNoise(src, 60), w, h)).toBeLessThan(0.6)
  })

  it('hızlı (hücre toplamlı) SSIM, her pencereyi baştan toplayan yalın sürümle aynı', () => {
    for (const [w, h] of [[64, 64], [67, 45], [9, 130], [8, 8], [33, 12]]) {
      const a = lumaPlane(photoLike(w, h, w), w, h)
      const b = lumaPlane(withNoise(photoLike(w, h, w), 20, h), w, h)
      expect(Math.abs(ssimPlane(a, b, w, h) - ssimPlaneSimple(a, b, w, h))).toBeLessThan(1e-9)
      expect(ssimPlane(a, a, w, h)).toBe(1)
    }
  })

  it('8 pikselden küçük görselde tek pencere; boş görselde 1', () => {
    const a = Float32Array.from([10, 20, 30, 40, 50, 60])
    expect(ssimPlane(a, a, 3, 2)).toBe(1)
    expect(ssimPlane(a, Float32Array.from([60, 50, 40, 30, 20, 10]), 3, 2)).toBeLessThan(0.5)
    expect(ssimPlane(new Float32Array(0), new Float32Array(0), 0, 0)).toBe(1)
    expect(similarity(solid(2, 2, [9, 9, 9, 255]), solid(2, 2, [9, 9, 9, 255]), 2, 2)).toBe(1)
  })

  it('renk kayması (parlaklık aynı) cezalandırılır; saydamlık farkı görülür', () => {
    const w = 64
    const h = 64
    const src = solid(w, h, [100, 100, 100, 255])
    // aynı parlaklık (≈ 100), farklı renk: yalnız luma SSIM bunu "aynı" sayardı
    const tinted = solid(w, h, [160, 80, 62, 255])
    const s = similarity(src, tinted, w, h)
    expect(s).toBeLessThan(0.96)
    expect(s).toBeGreaterThan(0.8)
    // saydamlık: logo ile opak hali
    const logo = logoWithAlpha(w, h, [255, 255, 255])
    const opaque = Uint8ClampedArray.from(logo)
    for (let i = 3; i < opaque.length; i += 4) opaque[i] = 255
    expect(similarity(logo, opaque, w, h)).toBeLessThan(0.9)
    expect(similarity(logo, logo, w, h)).toBe(1)
  })

  it('yarıya indirme 2×2 ortalamadır', () => {
    const p = Float32Array.from([0, 2, 4, 6, 8, 10, 12, 14, 16, 18, 20, 22, 100, 100, 100, 100, 100, 100])
    const r = half(p, 6, 3)
    expect([r.w, r.h]).toEqual([3, 1])
    expect(Array.from(r.plane)).toEqual([7, 11, 15])                // (0+2+12+14)/4 …
    expect(lumaPlane(new Uint8ClampedArray([255, 255, 255, 255, 0, 0, 0, 0, 100, 100, 100, 128]), 3, 1, 255)[1]).toBe(255)
  })

  it('maliyet SINIRLI: büyük görselde sabit örneklem (en çok 9 döşeme), küçükte tamamı', () => {
    const small = samplePlan(1500, 2000)
    expect(small.whole).toBe(true)
    expect(small.tiles).toEqual([{ x: 0, y: 0, w: 1500, h: 2000 }])
    const big = samplePlan(8000, 6000)                                // 48 MP
    expect(big.whole).toBe(false)
    expect(big.tiles.length).toBe(9)
    expect([big.width, big.height]).toEqual([3 * TILE, 3 * TILE])
    expect(big.width * big.height).toBeLessThan(SAMPLE_MAX_PIXELS)
    for (const t of big.tiles) {
      expect(t.x % 16).toBe(0)
      expect(t.y % 16).toBe(0)
      expect(t.x + t.w).toBeLessThanOrEqual(8000)
      expect(t.y + t.h).toBeLessThanOrEqual(6000)
    }
    // görsele yayılır: ilk döşeme sol üstte, son döşeme sağ altta
    expect(big.tiles[0]).toMatchObject({ x: 0, y: 0 })
    expect(big.tiles[8].x).toBeGreaterThan(7400)
    expect(big.tiles[8].y).toBeGreaterThan(5400)
    // çok ince / uzun görsel: eksen başına döşeme sayısı sığana göre
    const strip = samplePlan(30000, 300)
    expect(strip.whole).toBe(false)
    expect(strip.rows).toBe(1)
    expect(strip.cols).toBe(3)
    expect(strip.tiles.every((t) => t.h === 300 && t.w === TILE)).toBe(true)
  })

  it('örneklem çıkarma ve döşemelerden dizme aynı mozaiği verir', () => {
    const w = 2400
    const h = 1400                                                    // 3,36 MP → mozaik
    const src = new Uint8ClampedArray(w * h * 4)
    for (let y = 0; y < h; y += 1) for (let x = 0; x < w; x += 1) { const i = (y * w + x) * 4; src[i] = x & 255; src[i + 1] = y & 255; src[i + 2] = (x >> 8) * 16 + (y >> 8); src[i + 3] = 255 }
    const plan = samplePlan(w, h)
    expect(plan.whole).toBe(false)
    const mosaic = extractSample(src, w, h, plan)
    expect(mosaic.length).toBe(plan.width * plan.height * 4)
    const tiles = plan.tiles.map((t) => {
      const out = new Uint8ClampedArray(t.w * t.h * 4)
      for (let y = 0; y < t.h; y++) out.set(src.subarray(((t.y + y) * w + t.x) * 4, ((t.y + y) * w + t.x + t.w) * 4), y * t.w * 4)
      return out
    })
    expect(Buffer.from(assembleSample(tiles, plan)).equals(Buffer.from(mosaic))).toBe(true)
    expect(similarity(mosaic, assembleSample(tiles, plan), plan.width, plan.height)).toBe(1)
    const wholePlan = samplePlan(10, 10)
    const tiny = solid(10, 10, [1, 2, 3, 255])
    expect(extractSample(tiny, 10, 10, wholePlan)).toBe(tiny)
    expect(assembleSample([tiny], wholePlan)).toBe(tiny)
  })

  it('3 MP görselde ölçüm sınırlı maliyetli (yerelde < 1 sn; kapsam ölçümlü yavaş CI için geniş pay)', () => {
    const w = 2000
    const h = 1500
    const a = photoLike(w, h, 1)
    const b = withNoise(a, 8)
    const t0 = Date.now()
    const s = similarity(a, b, w, h)
    expect(Date.now() - t0).toBeLessThan(20000)
    expect(s).toBeGreaterThan(0.5)
    expect(s).toBeLessThan(1)
  })
})
