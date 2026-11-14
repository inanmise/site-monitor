import { describe, it, expect, vi } from 'vitest'
import { analyzeRgba, crc32, encodePng, textChunk } from '../utils/imageEngine/pngWriter.js'
import { buildPalette, remapPixels } from '../utils/imageEngine/quantize.js'
import { probeBytes } from '../utils/imageEngine/probe.js'
import { decodePng, logoWithAlpha, photoLike, pngChunks, rng, solid } from './imageEngineKit.test.helpers.js'

// Piksel döngüleri kapsam (v8) ölçümüyle birkaç kat yavaşlar (CI: 2 vCPU): varsayılan 15 sn yerine geniş süre sınırı.
vi.setConfig({ testTimeout: 60_000 })

const same = (a, b) => Buffer.from(a).equals(Buffer.from(b))
const types = (bytes) => pngChunks(bytes).map((c) => c.type)

describe('görsel motoru — PNG yazıcı', () => {
  it('CRC-32 bilinen değerleri verir ve parça parça sürdürülebilir', () => {
    const enc = (s) => new TextEncoder().encode(s)
    expect(crc32(enc('123456789'))).toBe(0xcbf43926)
    expect(crc32(enc(''))).toBe(0)
    expect(crc32(enc('IEND'))).toBe(0xae426082)
    const all = enc('The quick brown fox jumps over the lazy dog')
    expect(crc32(all)).toBe(0x414fa339)
    expect(crc32(all, 10, all.length, crc32(all, 0, 10))).toBe(0x414fa339)
  })

  it('gerçek renk gidiş-dönüş: pikseller BİREBİR, CRC ler doğru (RGB / RGBA / gri / gri + saydamlık)', async () => {
    const cases = [
      ['RGB', photoLike(37, 23, 1), 37, 23, 2],
      ['RGBA', logoWithAlpha(40, 40), 40, 40, 6],
      ['gri', (() => { const d = photoLike(31, 17, 2); for (let i = 0; i < d.length; i += 4) { d[i + 1] = d[i]; d[i + 2] = d[i] } return d })(), 31, 17, 0],
      ['gri+saydam', (() => { const d = logoWithAlpha(24, 24, [90, 90, 90]); return d })(), 24, 24, 4],
    ]
    for (const [name, rgba, w, h, colorType] of cases) {
      const { bytes, colorType: ct, bitDepth } = await encodePng({ width: w, height: h, rgba })
      expect(ct, name).toBe(colorType)
      expect(bitDepth).toBe(8)
      const back = decodePng(bytes)                              // CRC yanlışsa atar
      expect([back.width, back.height, back.colorType]).toEqual([w, h, colorType])
      if (colorType === 4 || colorType === 6) {
        // tam saydam pikselin rengi korunur (yazıcı kayıpsızdır; rengi sıfırlamak çağıranın işi)
        expect(same(back.rgba, rgba), name).toBe(true)
      } else {
        expect(same(back.rgba, rgba), name).toBe(true)
      }
      expect(types(bytes)).toEqual(['IHDR', 'IDAT', 'IEND'])
      // motorun kendi başlık okuyucusu da aynı ölçüyü görür
      expect(probeBytes(bytes)).toMatchObject({ type: 'png', width: w, height: h, hasAlpha: colorType === 4 || colorType === 6, animated: false })
    }
  })

  it('paletli gidiş-dönüş: dizinler palete geri eşlenir; PLTE + tRNS; bit derinliği renk sayısına göre', async () => {
    for (const [colors, depth] of [[2, 1], [3, 2], [4, 2], [5, 4], [16, 4], [17, 8], [256, 8]]) {
      const rnd = rng(colors)
      const w = 37                                                  // 8'e bölünmeyen genişlik: son bayt dolgulu
      const h = 11
      const palette = new Uint8Array(colors * 4)
      for (let i = 0; i < colors; i++) palette.set([rnd() * 256, rnd() * 256, rnd() * 256, i === 0 ? 0 : i === 1 ? 128 : 255], i * 4)
      const indices = new Uint8Array(w * h)
      for (let i = 0; i < indices.length; i++) indices[i] = (rnd() * colors) | 0
      const { bytes, colorType, bitDepth } = await encodePng({ width: w, height: h, indices, palette, colors })
      expect(colorType).toBe(3)
      expect(bitDepth, `${colors} renk`).toBe(depth)
      const back = decodePng(bytes)
      expect(same(back.indices, indices), `${colors} renk`).toBe(true)
      expect(back.palette.length).toBe(colors * 3)
      expect(Array.from(back.trns)).toEqual([0, 128])               // yalnız saydam olan ilk iki giriş
      for (let p = 0; p < indices.length; p++) {
        expect(Array.from(back.rgba.subarray(p * 4, p * 4 + 4))).toEqual(Array.from(palette.subarray(indices[p] * 4, indices[p] * 4 + 4)))
      }
      expect(types(bytes)).toEqual(['IHDR', 'PLTE', 'tRNS', 'IDAT', 'IEND'])
    }
  })

  it('tümü opak palette tRNS yazılmaz; nicemleyiciyle uçtan uca çıktı kaynağın paletli halidir', async () => {
    const w = 64
    const h = 48
    const src = photoLike(w, h, 12)
    const pal = buildPalette(src, w * h, 32)
    const indices = await remapPixels(src, w, h, pal, { dither: true })
    const { bytes } = await encodePng({ width: w, height: h, indices, palette: pal.palette, colors: pal.colors })
    expect(types(bytes)).toEqual(['IHDR', 'PLTE', 'IDAT', 'IEND'])
    const back = decodePng(bytes)
    expect(same(back.indices, indices)).toBe(true)
    expect(probeBytes(bytes)).toMatchObject({ type: 'png', width: w, height: h, hasAlpha: false })
    // paletli + saydam: başlık okuyucu saydamlığı tRNS'ten görür
    const logo = logoWithAlpha(32, 32)
    const lp = buildPalette(logo, 32 * 32, 256)
    const li = await remapPixels(logo, 32, 32, lp)
    const lb = await encodePng({ width: 32, height: 32, indices: li, palette: lp.palette, colors: lp.colors })
    expect(probeBytes(lb.bytes).hasAlpha).toBe(true)
    const lback = decodePng(lb.bytes)
    for (let i = 0; i < logo.length; i += 4) expect(lback.rgba[i + 3]).toBe(logo[i + 3])
  })

  it('satır süzgeci: gerçek renkte uyarlamalı (yumuşak geçişte Sub / Up / Paeth seçilir) ve dosya süzgeçsizden küçük', async () => {
    const w = 256
    const h = 64
    const d = new Uint8ClampedArray(w * h * 4)
    for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) { const i = (y * w + x) * 4; d[i] = x; d[i + 1] = (x + y) & 255; d[i + 2] = y * 3; d[i + 3] = 255 }
    const { bytes } = await encodePng({ width: w, height: h, rgba: d })
    const back = decodePng(bytes)
    expect(back.filters.some((f) => f !== 0)).toBe(true)
    expect(bytes.length).toBeLessThan(w * h)                          // 49 KB ham veri → birkaç yüz bayt
    expect(same(back.rgba, d)).toBe(true)
    // düz renkte her iki strateji de denenir, sonuç yine doğru
    const flat = await encodePng({ width: 50, height: 50, rgba: solid(50, 50, [10, 20, 30, 255]) })
    expect(same(decodePng(flat.bytes).rgba, solid(50, 50, [10, 20, 30, 255]))).toBe(true)
    expect(flat.bytes.length).toBeLessThan(120)
  })

  it('metin parçaları: Latin-1 → tEXt, diğerleri → iTXt (UTF-8); IDAT tan önce', async () => {
    const { bytes } = await encodePng({ width: 4, height: 4, rgba: solid(4, 4, [1, 2, 3, 255]) },
      { text: [{ keyword: 'Copyright', text: '© 2026 Örnek' }, { keyword: 'Creation Time', text: '2026-11-14T10:00:00' },
        { keyword: 'Copyright', text: 'İş Bankası ₺' }] })
    const chunks = pngChunks(bytes)
    expect(chunks.map((c) => c.type)).toEqual(['IHDR', 'tEXt', 'tEXt', 'iTXt', 'IDAT', 'IEND'])
    const latin = (u) => Array.from(u, (c) => String.fromCharCode(c)).join('')
    expect(latin(chunks[1].data)).toBe('Copyright\u0000© 2026 Örnek')
    expect(latin(chunks[2].data)).toBe('Creation Time\u00002026-11-14T10:00:00')
    expect(latin(chunks[3].data.subarray(0, 14))).toBe('Copyright\u0000\u0000\u0000\u0000\u0000')
    expect(new TextDecoder().decode(chunks[3].data.subarray(14))).toBe('İş Bankası ₺')
    expect(decodePng(bytes).rgba[0]).toBe(1)
    // anahtar en çok 79 karakter
    const long = pngChunks(Uint8Array.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, ...textChunk('k'.repeat(200), 'v'), 0, 0, 0, 0, 0x49, 0x45, 0x4e, 0x44, 0xae, 0x42, 0x60, 0x82]))
    expect(long[0].data.indexOf(0)).toBe(79)
  })

  it('akışlı sıkıştırma: büyük görselde çok parçalı itme de aynı pikselleri verir; `tick` çağrılır', async () => {
    const w = 700
    const h = 900                                                      // ham ≈ 1,9 MB → birçok 256 KB lık küme
    const src = photoLike(w, h, 31, 30)
    let ticks = 0
    const { bytes } = await encodePng({ width: w, height: h, rgba: src }, { tick: async () => { ticks += 1 } })
    expect(ticks).toBeGreaterThan(5)
    expect(same(decodePng(bytes).rgba, src)).toBe(true)
  })

  it('geçersiz girdi RangeError atar; içerik çözümlemesi doğru', async () => {
    await expect(encodePng({ width: 0, height: 4, rgba: new Uint8Array(0) })).rejects.toThrow(RangeError)
    await expect(encodePng({ width: 2, height: 2 })).rejects.toThrow(RangeError)
    await expect(encodePng({ width: 2, height: 2, indices: new Uint8Array(4), palette: new Uint8Array(0), colors: 0 })).rejects.toThrow(RangeError)
    expect(analyzeRgba(solid(2, 2, [5, 5, 5, 255]), 4)).toEqual({ opaque: true, gray: true })
    expect(analyzeRgba(solid(2, 2, [5, 6, 5, 255]), 4)).toEqual({ opaque: true, gray: false })
    expect(analyzeRgba(solid(2, 2, [5, 5, 5, 9]), 4)).toEqual({ opaque: false, gray: true })
  })
})
