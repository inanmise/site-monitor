import { describe, it, expect, vi } from 'vitest'
import { buildPalette, buildPaletteAsync } from '../utils/imageEngine/quantize.js'
import { similarity, similarityAsync } from '../utils/imageEngine/ssim.js'
import { flattenOnto, flattenOntoAsync } from '../utils/imageEngine/search.js'
import { drive, runSteps } from '../utils/imageEngine/steps.js'
import { makeCtx, runPng } from '../utils/imageEngine/pipeline.js'
import { logoWithAlpha, photoLike, rng } from './imageEngineKit.test.helpers.js'

// Piksel döngüleri kapsam (v8) ölçümüyle birkaç kat yavaşlar (CI: 2 vCPU): varsayılan 15 sn yerine geniş süre sınırı.
vi.setConfig({ testTimeout: 60_000 })

/**
 * Ana iş parçacığı yedeği (Worker yok): palet tasarımı, benzerlik ölçümü ve zemine bindirme PARÇALARA bölünür, parçalar
 * arasında `tick` beklenir (sayfa donmaz, iptal araya girer). Sonuç parçasız hesapla BİREBİR aynıdır (toplama sırası
 * değişmez); Worker'da `tick` yoktur, hiç beklenmez.
 */
const json = (v) => JSON.stringify(v, (k, x) => (ArrayBuffer.isView(x) ? Array.from(x) : x))
function noisy(w, h, seed) {
  const r = rng(seed)
  const d = new Uint8ClampedArray(w * h * 4)
  for (let i = 0; i < d.length; i += 4) { d[i] = r() * 256; d[i + 1] = r() * 256; d[i + 2] = r() * 256; d[i + 3] = 255 }
  return d
}

describe('görsel motoru — adım adım hesap (ana iş parçacığı yedeği)', () => {
  it('sürücü: tick yoksa eşzamanlı ve beklemesiz; tick varsa her adımda beklenir; tick atarsa (iptal) durur', async () => {
    function* g() { yield; yield; return 7 }
    expect(runSteps(g())).toBe(7)
    expect(await drive(g())).toBe(7)
    const seen = []
    expect(await drive(g(), async () => { seen.push(1) })).toBe(7)
    expect(seen.length).toBe(2)
    let after = false
    function* h() { yield; after = true; return 1 }
    await expect(drive(h(), async () => { throw new Error('iptal') })).rejects.toThrow('iptal')
    expect(after).toBe(false)
  })

  it('palet tasarımı: parçalar arasında beklenir, sonuç eşzamanlı ile aynı (histogram, kovalama, bölme, k-ortalama)', async () => {
    // 1,1 MP tam gürültü: > 1 M farklı renk → histogram bit atarak yeniden kurulur, girdiler kovalanır
    const w = 1100
    const h = 1000
    const d = noisy(w, h, 4)
    let ticks = 0
    const a = await buildPaletteAsync(d, w * h, 256, async () => { ticks += 1 })
    expect(json(a)).toBe(json(buildPalette(d, w * h, 256)))
    // en az: iki histogram denemesinin parçaları (≥ 2 × 5) + kovalama + bölme + k-ortalama atamaları
    expect(ticks).toBeGreaterThan(20)
    // yarı saydam, az renkli görsel de aynı
    const logo = logoWithAlpha(300, 200)
    expect(json(await buildPaletteAsync(logo, 300 * 200, 64, async () => {}))).toBe(json(buildPalette(logo, 300 * 200, 64)))
    // tick yoksa (Worker) hiç beklenmez: aynı sonuç
    expect(json(await buildPaletteAsync(logo, 300 * 200, 64))).toBe(json(buildPalette(logo, 300 * 200, 64)))
  })

  it('benzerlik (SSIM + renk cezası) ve zemine bindirme: parçalı, sonuç birebir aynı', async () => {
    const w = 900
    const h = 700
    const x = photoLike(w, h, 3)
    const y = photoLike(w, h, 4, 25)
    let ticks = 0
    expect(await similarityAsync(x, y, w, h, async () => { ticks += 1 })).toBe(similarity(x, y, w, h))
    expect(ticks).toBeGreaterThan(8)
    const logo = logoWithAlpha(500, 400)                                   // saydam: iki zeminde ölçülür
    const logo2 = Uint8ClampedArray.from(logo, (v, i) => ((i & 3) === 3 ? v : Math.min(255, v + 3)))
    expect(await similarityAsync(logo, logo2, 500, 400, async () => {})).toBe(similarity(logo, logo2, 500, 400))
    let ft = 0
    const flat = await flattenOntoAsync(logo, 500 * 400, [10, 200, 30], async () => { ft += 1 })
    expect(Array.from(flat)).toEqual(Array.from(flattenOnto(logo, 500 * 400, [10, 200, 30])))
    expect(ft).toBeGreaterThan(0)
  })

  it('PNG adayı: ana iş parçacığında (cooperative) palet ve benzerlik arasına bekleme girer; Worker da girmez; çıktı aynı', async () => {
    const w = 800
    const h = 700
    const image = { data: photoLike(w, h, 9, 20), width: w, height: h, hasAlpha: false }
    const worker = makeCtx({})
    expect(worker.stepTick).toBeUndefined()                                // Worker: adım bekleme yok
    const coop = makeCtx({ cooperative: true })
    expect(typeof coop.stepTick).toBe('function')
    let ticks = 0
    coop.stepTick = async () => { ticks += 1 }
    const a = await runPng(image, { kind: 'palette', colors: 256 }, [], coop)
    const b = await runPng(image, { kind: 'palette', colors: 256 }, [], worker)
    expect(ticks).toBeGreaterThan(10)
    expect(a.similarity).toBe(b.similarity)
    expect(Array.from(a.bytes)).toEqual(Array.from(b.bytes))
  })
})
