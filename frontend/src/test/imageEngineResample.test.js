import { describe, it, expect } from 'vitest'
import { FILTERS, contributions, createResampler, resampleRGBA } from '../utils/imageEngine/resample.js'
import { LINEAR_TO_SRGB8, SRGB_TO_LINEAR, linearToSrgb8 } from '../utils/imageEngine/color.js'
import { createSharpener, sharpenAmount, sharpenRGBA } from '../utils/imageEngine/sharpen.js'
import { checkerboard, photoLike, resampleOracle, solid, logoWithAlpha } from './imageEngineKit.test.helpers.js'

const px = (d, w, x, y) => Array.from(d.subarray((y * w + x) * 4, (y * w + x) * 4 + 4))

describe('görsel motoru — renk tabloları', () => {
  it('sRGB → doğrusal → sRGB her 8 bit değerde birebir döner', () => {
    for (let v = 0; v < 256; v++) expect(linearToSrgb8(SRGB_TO_LINEAR[v])).toBe(v)
    expect(LINEAR_TO_SRGB8.length).toBe(65536)
    expect(linearToSrgb8(-0.5)).toBe(0)
    expect(linearToSrgb8(NaN)).toBe(0)
    expect(linearToSrgb8(7)).toBe(255)
    // doğrusal 0,5 ≈ sRGB 188 (gama kodlu ortalama 128 olurdu)
    expect(linearToSrgb8(0.5)).toBe(188)
  })
})

describe('görsel motoru — yeniden örnekleme', () => {
  it('süzgeç ağırlıkları her hedef pikselde 1 eder (kenarlar dahil, her ölçekte, iki süzgeçte)', () => {
    for (const filter of Object.keys(FILTERS)) {
      for (const [src, dst] of [[64, 32], [1000, 333], [97, 13], [5000, 16], [17, 16], [8, 8], [3, 1], [1, 1]]) {
        const c = contributions(src, dst, filter)
        for (let i = 0; i < dst; i++) {
          let sum = 0
          for (let k = 0; k < c.count[i]; k++) sum += c.weights[i * c.maxTaps + k]
          expect(Math.abs(sum - 1), `${filter} ${src}→${dst} #${i}`).toBeLessThan(1e-5)
          expect(c.start[i]).toBeGreaterThanOrEqual(0)
          expect(c.start[i] + c.count[i]).toBeLessThanOrEqual(src)
          expect(c.count[i]).toBeGreaterThanOrEqual(1)
          expect(c.count[i]).toBeLessThanOrEqual(c.maxTaps)
        }
      }
    }
  })

  it('küçültmede çekirdek ölçek kadar genişler (örtüşme olmasın)', () => {
    expect(contributions(64, 64).maxTaps).toBeLessThanOrEqual(7)
    const c = contributions(640, 64)                       // 10×: Catmull-Rom desteği 2 × 10 kaynak pikseli
    expect(c.count[32]).toBeGreaterThanOrEqual(40)
    expect(c.count[32]).toBeLessThanOrEqual(43)
  })

  it('GAMA DOĞRU: 1 piksellik siyah / beyaz dama 2× küçülünce ≈ sRGB 188 olur (128 değil)', () => {
    const out = resampleRGBA(checkerboard(64, 64), 64, 64, 32, 32)
    // iç pikseller tam 188; kenarda çekirdek kırpıldığı için ± birkaç seviye
    for (let y = 4; y < 28; y++) for (let x = 4; x < 28; x++) expect(px(out, 32, x, y)).toEqual([188, 188, 188, 255])
    for (let i = 0; i < out.length; i += 4) {
      expect(out[i]).toBeGreaterThan(180)
      expect(out[i]).toBeLessThan(196)
      expect(out[i + 3]).toBe(255)
    }
  })

  it('ÖNÇARPIMLI saydamlık: opak kırmızının yanındaki SAYDAM yeşil renge karışmaz, saydamlık ortalanır', () => {
    // sol yarı opak kırmızı; sağ yarı TAM saydam ama "rengi" yeşil (önçarpımsız süzgeç kenarı sarıya / koyuya boyardı)
    const w = 64
    const h = 16
    const d = new Uint8ClampedArray(w * h * 4)
    for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
      const i = (y * w + x) * 4
      if (x < 34) { d[i] = 255; d[i + 3] = 255 } else { d[i + 1] = 255; d[i + 3] = 0 }
    }
    const out = resampleRGBA(d, w, h, 16, 4)                // 4×: 34. sütun bir hedef pikselin ortasına düşer
    let partial = 0
    for (let y = 0; y < 4; y++) for (let x = 0; x < 16; x++) {
      const [r, g, b, a] = px(out, 16, x, y)
      if (a === 0) { expect([r, g, b]).toEqual([0, 0, 0]); continue }
      expect(g, `(${x},${y}) yeşil sızdı`).toBe(0)          // saydam pikselin rengi hiç karışmaz
      expect(b).toBe(0)
      expect(r, `(${x},${y}) kırmızı karardı`).toBe(255)     // kenar pikseli de TAM kırmızı (hale yok)
      if (a < 255) partial += 1
    }
    expect(partial).toBeGreaterThan(0)                       // kenarda yarı saydam pikseller var (saydamlık ortalandı)
    const edge = px(out, 16, 8, 2)                            // 32–35. sütunlar: yarısı opak
    expect(edge[3]).toBeGreaterThan(100)
    expect(edge[3]).toBeLessThan(160)
  })

  it('akışlı sonuç = tek geçiş sonucu (şeritler farklı tamponlardan, farklı boylarda gelse de bayt bayt aynı)', () => {
    const w = 211
    const h = 157
    const src = photoLike(w, h, 3)
    const whole = resampleRGBA(src, w, h, 67, 41)
    const out = new Uint8ClampedArray(67 * 41 * 4)
    const rs = createResampler({ srcW: w, srcH: h, dstW: 67, dstH: 41, onRow: (y, row) => out.set(row, y * 67 * 4) })
    let r = 0
    for (const n of [1, 7, 64, 3, 50, 32]) {
      const strip = new Uint8ClampedArray(src.subarray(r * w * 4, (r + n) * w * 4))    // kopya: kaynakla bağı yok
      for (let k = 0; k < n; k++) rs.pushRow(strip, k * w * 4)
      r += n
    }
    expect(r).toBe(h)
    expect(rs.rowsPushed()).toBe(h)
    expect(rs.rowsEmitted()).toBe(41)
    expect(Buffer.from(out).equals(Buffer.from(whole))).toBe(true)
    expect(() => rs.pushRow(src, 0)).toThrow(RangeError)
  })

  it('kâhinle (yalın Float64 uygulama) ± 1 seviye içinde aynı: opak fotoğraf ve saydam logo', () => {
    for (const [src, w, h, dw, dh] of [[photoLike(120, 90, 5), 120, 90, 40, 30], [logoWithAlpha(96, 96), 96, 96, 31, 31],
      [photoLike(50, 50, 9), 50, 50, 49, 17]]) {
      const got = resampleRGBA(src, w, h, dw, dh)
      const want = resampleOracle(src, w, h, dw, dh)
      let worst = 0
      for (let i = 0; i < got.length; i += 4) {
        // çok düşük saydamlıkta renk oranı sayısal olarak oynaktır (görünmez): yalnız görünür piksellerde karşılaştır
        if (want[i + 3] < 8) { expect(got[i + 3]).toBeLessThan(10); continue }
        for (let c = 0; c < 4; c++) worst = Math.max(worst, Math.abs(got[i + c] - want[i + c]))
      }
      expect(worst).toBeLessThanOrEqual(1)
    }
  })

  it('düz renk küçülünce aynı renk kalır; aynı ölçü birebir kopyadır', () => {
    const flat = resampleRGBA(solid(40, 30, [37, 150, 201, 255]), 40, 30, 13, 7)
    for (let i = 0; i < flat.length; i += 4) expect(Array.from(flat.subarray(i, i + 4))).toEqual([37, 150, 201, 255])
    const semi = resampleRGBA(solid(40, 30, [200, 100, 50, 128]), 40, 30, 10, 10)
    for (let i = 0; i < semi.length; i += 4) expect(Array.from(semi.subarray(i, i + 4))).toEqual([200, 100, 50, 128])
    const src = photoLike(23, 19, 11)
    expect(Buffer.from(resampleRGBA(src, 23, 19, 23, 19)).equals(Buffer.from(src))).toBe(true)
  })

  it('tek satır / tek sütun ve 1 piksele küçültme çalışır', () => {
    expect(Array.from(resampleRGBA(solid(100, 1, [9, 8, 7, 255]), 100, 1, 10, 1).subarray(0, 4))).toEqual([9, 8, 7, 255])
    expect(Array.from(resampleRGBA(solid(1, 100, [9, 8, 7, 255]), 1, 100, 1, 10).subarray(0, 4))).toEqual([9, 8, 7, 255])
    const one = resampleRGBA(checkerboard(32, 32), 32, 32, 1, 1)
    expect(one[0]).toBeGreaterThan(180)
    expect(one[0]).toBeLessThan(196)
    expect(() => createResampler({ srcW: 0, srcH: 5, dstW: 1, dstH: 1, onRow() {} })).toThrow(RangeError)
  })
})

describe('görsel motoru — netleştirme', () => {
  it('miktar ölçeğe göre: gerçek küçültme yoksa 0, 2× ve üstünde 0,3 (hafif)', () => {
    expect(sharpenAmount(1)).toBe(0)
    expect(sharpenAmount(0.97)).toBe(0)
    expect(sharpenAmount(0.94)).toBe(0)                    // çok küçük miktar: hiç uygulanmaz
    expect(sharpenAmount(0.75)).toBeCloseTo(0.1, 5)
    expect(sharpenAmount(0.5)).toBeCloseTo(0.3, 5)
    expect(sharpenAmount(0.1)).toBe(0.3)
    expect(sharpenAmount(0)).toBe(0)
    expect(sharpenAmount(NaN)).toBe(0)
  })

  it('düz bölge değişmez; kenarın karşıtlığı artar; saydamlık kanalına dokunulmaz', () => {
    const flat = solid(16, 16, [120, 130, 140, 255])
    expect(Buffer.from(sharpenRGBA(flat, 16, 16, 0.3)).equals(Buffer.from(flat))).toBe(true)
    // dikey kenar: sol 100, sağ 160
    const e = new Uint8ClampedArray(16 * 8 * 4)
    for (let y = 0; y < 8; y++) for (let x = 0; x < 16; x++) { const i = (y * 16 + x) * 4; e[i] = e[i + 1] = e[i + 2] = x < 8 ? 100 : 160; e[i + 3] = 255 }
    const s = sharpenRGBA(e, 16, 8, 0.3)
    expect(s[(4 * 16 + 7) * 4]).toBeLessThan(100)          // koyu taraf biraz daha koyu
    expect(s[(4 * 16 + 8) * 4]).toBeGreaterThan(160)        // açık taraf biraz daha açık
    expect(s[(4 * 16 + 7) * 4]).toBeGreaterThan(90)         // hafif: aşırı hale yok
    expect(s[(4 * 16 + 8) * 4]).toBeLessThan(170)
    expect(s[(4 * 16 + 2) * 4]).toBe(100)                   // kenardan uzakta değişmez
    // saydam kenar: saydamlık aynen kalır, saydam komşunun rengi karışmaz
    const logo = logoWithAlpha(32, 32, [200, 40, 40])
    const sl = sharpenRGBA(logo, 32, 32, 0.3)
    for (let i = 0; i < logo.length; i += 4) {
      expect(sl[i + 3]).toBe(logo[i + 3])
      if (logo[i + 3] === 0) expect([sl[i], sl[i + 1], sl[i + 2]]).toEqual([0, 0, 0])
      else expect([sl[i], sl[i + 1], sl[i + 2]]).toEqual([200, 40, 40])
    }
  })

  it('akışlı netleştirici satırları sırayla ve eksiksiz verir; miktar 0 ise olduğu gibi geçirir', () => {
    const src = photoLike(20, 9, 2)
    const rows = []
    const sh = createSharpener(20, 9, 0.3, (y, row) => rows.push([y, Uint8ClampedArray.from(row)]))
    for (let y = 0; y < 9; y++) sh.push(src, y * 80)
    sh.finish()
    expect(rows.map((r) => r[0])).toEqual([0, 1, 2, 3, 4, 5, 6, 7, 8])
    const whole = sharpenRGBA(src, 20, 9, 0.3)
    rows.forEach(([y, row]) => expect(Buffer.from(row).equals(Buffer.from(whole.subarray(y * 80, (y + 1) * 80)))).toBe(true))
    expect(Buffer.from(sharpenRGBA(src, 20, 9, 0)).equals(Buffer.from(src))).toBe(true)
    expect(Buffer.from(sharpenRGBA(src.subarray(0, 80), 20, 1, 0.3)).equals(Buffer.from(src.subarray(0, 80)))).toBe(true)
  })
})
