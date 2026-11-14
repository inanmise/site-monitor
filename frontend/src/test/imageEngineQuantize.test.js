import { describe, it, expect, vi } from 'vitest'
import {
  DITHER_MIN_MSE, MAX_DESIGN_ENTRIES, buildHistogram, buildPalette, expandPalette, nearestBrute, nearestIn, remapPixels,
} from '../utils/imageEngine/quantize.js'
import { logoWithAlpha, photoLike, rng, solid } from './imageEngineKit.test.helpers.js'

// Piksel döngüleri kapsam (v8) ölçümüyle birkaç kat yavaşlar (CI: 2 vCPU): varsayılan 15 sn yerine geniş süre sınırı.
vi.setConfig({ testTimeout: 60_000 })

function gradient(w, h) {
  const d = new Uint8ClampedArray(w * h * 4)
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
    const i = (y * w + x) * 4
    d[i] = Math.round((x * 255) / (w - 1)); d[i + 1] = Math.round((y * 255) / (h - 1)); d[i + 2] = 128; d[i + 3] = 255
  }
  return d
}
const key = (d, i) => `${d[i]},${d[i + 1]},${d[i + 2]},${d[i + 3]}`
function uniqueColors(d) {
  const s = new Set()
  for (let i = 0; i < d.length; i += 4) s.add(d[i + 3] === 0 ? 'T' : key(d, i))
  return s
}

describe('görsel motoru — histogram', () => {
  it('farklı renkleri sayar; tam saydam pikseller rengi ne olursa olsun TEK anahtardır', () => {
    const d = new Uint8ClampedArray([255, 0, 0, 255, 255, 0, 0, 255, 0, 255, 0, 0, 0, 0, 255, 0, 10, 20, 30, 128])
    const h = buildHistogram(d, 5)
    expect(h.unique).toBe(3)
    expect(h.bits).toBe(0)
    const byKey = new Map(Array.from(h.keys).map((k, i) => [k >>> 0, h.counts[i]]))
    expect(byKey.get(0)).toBe(2)                                    // iki saydam piksel
    expect(byKey.get(0xff0000ff)).toBe(2)                           // r | g<<8 | b<<16 | a<<24
    expect(byKey.get(((128 << 24) | (30 << 16) | (20 << 8) | 10) >>> 0)).toBe(1)
  })

  it('hizasız tampon görünümünde de aynı sonucu verir (Uint32 kısayolu yalnız hizalıyken)', () => {
    const src = photoLike(40, 30, 4)
    const shifted = new Uint8ClampedArray(src.length + 1)
    shifted.set(src, 1)
    const a = buildHistogram(src, 1200)
    const b = buildHistogram(shifted.subarray(1), 1200)
    expect(b.unique).toBe(a.unique)
    expect(Array.from(b.keys).sort()).toEqual(Array.from(a.keys).sort())
  })
})

describe('görsel motoru — palet', () => {
  it('≤ 256 rengi olan görselde palet BİREBİR: aynı renkler, kayıp 0, titreşim yok', async () => {
    const rnd = rng(5)
    const colors = Array.from({ length: 200 }, () => [rnd() * 256 | 0, rnd() * 256 | 0, rnd() * 256 | 0, 255])
    colors.push([0, 0, 0, 0], [50, 60, 70, 128])
    const w = 64
    const h = 64
    const d = new Uint8ClampedArray(w * h * 4)
    for (let p = 0; p < w * h; p++) d.set(colors[(rnd() * colors.length) | 0], p * 4)
    const pal = buildPalette(d, w * h, 256)
    expect(pal.exact).toBe(true)
    expect(pal.mse).toBe(0)
    expect(pal.colors).toBe(uniqueColors(d).size)
    const idx = await remapPixels(d, w, h, pal, { dither: true })
    const back = expandPalette(idx, pal.palette)
    for (let i = 0; i < d.length; i += 4) {
      if (d[i + 3] === 0) expect(back[i + 3]).toBe(0)
      else expect(key(back, i)).toBe(key(d, i))
    }
  })

  it('palet sırası: saydamlar önce (tRNS kısa kalsın), opaklar sonda', () => {
    const d = new Uint8ClampedArray([9, 9, 9, 255, 200, 0, 0, 255, 1, 2, 3, 0, 5, 5, 5, 100, 5, 5, 5, 30])
    const pal = buildPalette(d, 5, 256)
    const alphas = Array.from({ length: pal.colors }, (_, i) => pal.palette[i * 4 + 3])
    expect(alphas).toEqual([0, 30, 100, 255, 255])
    expect(pal.hasTransparent).toBe(true)
    expect(pal.opaque).toBe(2)
    expect(pal.semi).toBe(2)
  })

  it('çok renkli görsel ≤ istenen sayıda renge iner; sonuç belirlenimci', async () => {
    const g = gradient(128, 128)
    const a = buildPalette(g, 128 * 128, 256)
    const b = buildPalette(Uint8ClampedArray.from(g), 128 * 128, 256)
    expect(a.exact).toBe(false)
    expect(a.colors).toBeLessThanOrEqual(256)
    expect(a.colors).toBeGreaterThan(200)
    expect(Array.from(a.palette)).toEqual(Array.from(b.palette))
    expect(a.mse).toBe(b.mse)
    const i1 = await remapPixels(g, 128, 128, a, { dither: true })
    const i2 = await remapPixels(g, 128, 128, b, { dither: true })
    expect(Buffer.from(i1).equals(Buffer.from(i2))).toBe(true)
    for (const k of [128, 64, 16, 4, 2]) {
      const p = buildPalette(g, 128 * 128, k)
      expect(p.colors).toBeLessThanOrEqual(k)
      expect(p.colors).toBeGreaterThanOrEqual(Math.min(k, 2))
    }
    // renk azaldıkça hata artar
    expect(buildPalette(g, 128 * 128, 16).mse).toBeGreaterThan(a.mse * 4)
  })

  it('geçişte (gradient) hata SINIRLI: titreşimsiz en kötü kanal hatası küçük, titreşimli ortalama renk korunur', async () => {
    const w = 128
    const h = 128
    const g = gradient(w, h)
    const pal = buildPalette(g, w * h, 256)
    expect(pal.mse).toBeLessThan(1e-3)
    expect(pal.mse).toBeGreaterThan(DITHER_MIN_MSE)
    const plain = expandPalette(await remapPixels(g, w, h, pal, { dither: false }), pal.palette)
    let worst = 0
    let sq = 0
    for (let i = 0; i < g.length; i += 4) for (let c = 0; c < 3; c++) {
      const e = Math.abs(plain[i + c] - g[i + c])
      worst = Math.max(worst, e)
      sq += e * e
    }
    expect(worst).toBeLessThanOrEqual(24)
    expect(Math.sqrt(sq / (w * h * 3))).toBeLessThan(6)
    // titreşim: 8×8 blok ortalamaları kaynağa yakın (hata komşulara dağıtıldı, kaybolmadı)
    const dith = expandPalette(await remapPixels(g, w, h, pal, { dither: true }), pal.palette)
    let worstBlock = 0
    for (let by = 0; by < h; by += 8) for (let bx = 0; bx < w; bx += 8) for (let c = 0; c < 3; c++) {
      let a = 0
      let b = 0
      for (let y = 0; y < 8; y++) for (let x = 0; x < 8; x++) { const i = ((by + y) * w + bx + x) * 4 + c; a += g[i]; b += dith[i] }
      worstBlock = Math.max(worstBlock, Math.abs(a - b) / 64)
    }
    expect(worstBlock).toBeLessThan(4)
    for (let i = 3; i < dith.length; i += 4) expect(dith[i]).toBe(255)       // opak görselde saydam piksel üremez
  })

  it('SAYDAMLIK korunur: tam saydam piksel tam saydam, opak piksel opak kalır; yarı saydamlar yarı saydam', async () => {
    // saydam zemin + opak renkli gövde (çok renk) + yarı saydam kenar
    const w = 96
    const h = 96
    const d = logoWithAlpha(w, h)
    const rnd = rng(8)
    for (let i = 0; i < d.length; i += 4) if (d[i + 3] === 255) { d[i] = 100 + rnd() * 155; d[i + 1] = rnd() * 255; d[i + 2] = rnd() * 255 }
    for (let y = 0; y < 20; y++) for (let x = 0; x < w; x++) { const i = (y * w + x) * 4; d[i] = x * 2; d[i + 1] = 255 - x; d[i + 2] = y * 10; d[i + 3] = 40 + y * 8 }
    const pal = buildPalette(d, w * h, 64)
    expect(pal.exact).toBe(false)
    expect(pal.hasTransparent).toBe(true)
    expect(pal.colors).toBeLessThanOrEqual(64)
    for (const dither of [false, true]) {
      const back = expandPalette(await remapPixels(d, w, h, pal, { dither }), pal.palette)
      for (let i = 0; i < d.length; i += 4) {
        if (d[i + 3] === 0) expect(key(back, i)).toBe('0,0,0,0')
        else if (d[i + 3] === 255) expect(back[i + 3]).toBe(255)
        else {
          expect(back[i + 3]).toBeGreaterThan(0)
          expect(back[i + 3]).toBeLessThan(255)
          expect(Math.abs(back[i + 3] - d[i + 3])).toBeLessThan(70)
        }
      }
    }
  })

  it('en yakın renk araması kaba kuvvetle aynı uzaklığı bulur', () => {
    const rnd = rng(21)
    const k = 200
    const cent = new Float32Array(k * 4)
    for (let i = 0; i < k * 4; i++) cent[i] = rnd()
    // remapPixels'in kullandığı arama yapısını palet üstünden dolaylı sına: 2000 rastgele sorgu
    const pal = { palette: new Uint8Array(k * 4), colors: k, exact: false }
    for (let i = 0; i < k; i++) { pal.palette[i * 4] = rnd() * 256; pal.palette[i * 4 + 1] = rnd() * 256; pal.palette[i * 4 + 2] = rnd() * 256; pal.palette[i * 4 + 3] = 255 }
    expect(nearestIn({ order: new Int32Array(0), vals: new Float32Array(0), k: 0, axis: 0 }, cent, 0, 0, 0, 0)).toBe(-1)
    const order = Int32Array.from(Array.from({ length: k }, (_, i) => i).sort((a, b) => cent[a * 4 + 1] - cent[b * 4 + 1]))
    const search = { axis: 1, order, vals: Float32Array.from(order, (i) => cent[i * 4 + 1]), k }
    const dist = (i, q) => { let s = 0; for (let c = 0; c < 4; c++) s += (cent[i * 4 + c] - q[c]) ** 2; return s }
    for (let t = 0; t < 2000; t++) {
      const q = [rnd() * 1.4 - 0.2, rnd() * 1.4 - 0.2, rnd(), rnd()]
      const a = nearestIn(search, cent, q[0], q[1], q[2], q[3])
      const b = nearestBrute(cent, 0, k, q[0], q[1], q[2], q[3])
      expect(dist(a, q)).toBeCloseTo(dist(b, q), 10)
    }
  })

  it('dev renk sayısında (> tasarım sınırı) girdiler kovalanır ve iş biter', async () => {
    const w = 400
    const h = 300
    const rnd = rng(77)
    const d = new Uint8ClampedArray(w * h * 4)
    for (let i = 0; i < d.length; i += 4) { d[i] = rnd() * 256; d[i + 1] = rnd() * 256; d[i + 2] = rnd() * 256; d[i + 3] = 255 }
    const hist = buildHistogram(d, w * h)
    expect(hist.unique).toBeGreaterThan(MAX_DESIGN_ENTRIES)
    const t0 = Date.now()
    const pal = buildPalette(d, w * h, 256)
    expect(pal.colors).toBeLessThanOrEqual(256)
    expect(pal.colors).toBeGreaterThan(128)
    const idx = await remapPixels(d, w, h, pal, { dither: true })
    expect(idx.length).toBe(w * h)
    for (let i = 0; i < idx.length; i++) if (idx[i] >= pal.colors) throw new Error('palet dışı dizin')
    expect(Date.now() - t0).toBeLessThan(15000)
  })

  it('tek renk / tek piksel / iki renk hakkı gibi uç durumlar', async () => {
    const one = buildPalette(solid(8, 8, [1, 2, 3, 255]), 64, 256)
    expect(one.colors).toBe(1)
    expect(one.exact).toBe(true)
    expect(Array.from(await remapPixels(solid(8, 8, [1, 2, 3, 255]), 8, 8, one))).toEqual(new Array(64).fill(0))
    // saydam + opak + yarı saydam sınıfları varken 2 renk hakkı: iş yine biter, saydam piksel saydam kalır
    const d = logoWithAlpha(32, 32)
    const rnd = rng(3)
    for (let i = 0; i < d.length; i += 4) if (d[i + 3] === 255) { d[i] = rnd() * 255; d[i + 1] = rnd() * 255 }
    const two = buildPalette(d, 32 * 32, 2)
    expect(two.colors).toBeLessThanOrEqual(2)
    const back = expandPalette(await remapPixels(d, 32, 32, two, { dither: true }), two.palette)
    for (let i = 0; i < d.length; i += 4) if (d[i + 3] === 0) expect(back[i + 3]).toBe(0)
  })

  it('uzun işte `tick` çağrılır (ana iş parçacığı yedeğinde sayfa nefes alır / iptal araya girer)', async () => {
    const g = gradient(64, 200)
    const pal = buildPalette(g, 64 * 200, 32)
    let ticks = 0
    await remapPixels(g, 64, 200, pal, { dither: true, tick: async () => { ticks += 1 } })
    expect(ticks).toBeGreaterThanOrEqual(5)
    await expect(remapPixels(g, 64, 200, pal, { dither: false, tick: async () => { throw new Error('iptal') } })).rejects.toThrow('iptal')
  })
})
