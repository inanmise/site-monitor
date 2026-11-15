import { describe, it, expect, vi } from 'vitest'
import {
  JFIF_SEGMENT_BYTES, PHYS_CHUNK_BYTES, densityOverhead, jfifFields, physFields, ppiOf, readDensity, withDensity, withJpegDensity,
  withPngDensity,
} from '../utils/imageEngine/density.js'
import { readImageFacts } from '../utils/imageEngine/analyze.js'
import { encodePng } from '../utils/imageEngine/pngWriter.js'
import { processCore } from '../utils/imageEngine/pipeline.js'
import { fakePlatform, photoLike, pngChunks, registerBlob, decodePng } from './imageEngineKit.test.helpers.js'

vi.setConfig({ testTimeout: 60_000 })

/**
 * Piksel yoğunluğu (PPI) korunur (2026-11-15, kullanıcı onayı): kaynağın JFIF / pHYs / BMP yoğunluğu yeniden kodlanmış
 * JPEG (JFIF APP0 yaması / eklemesi) ve PNG (pHYs, IDAT'tan önce, CRC doğru) çıktısına AYNEN yazılır; WebP / AVIF'te alan
 * yok. Okuyucu analyze.js ile ORTAK (density.js).
 */
const u16 = (v) => [(v >> 8) & 255, v & 255]
/** SOI + JFIF APP0 (units, x, y) + SOF0 + EOI — en küçük JPEG başlığı. */
function jpegWithJfif(units, x, y, { app0 = true } = {}) {
  const parts = [0xff, 0xd8]
  if (app0) parts.push(0xff, 0xe0, 0, 16, 0x4a, 0x46, 0x49, 0x46, 0, 1, 1, units, ...u16(x), ...u16(y), 0, 0)
  parts.push(0xff, 0xc0, 0, 11, 8, 0, 8, 0, 8, 1, 1, 0x11, 0, 0xff, 0xd9)
  return Uint8Array.from(parts)
}
const be32 = (b, o) => b[o] * 16777216 + (b[o + 1] << 16) + (b[o + 2] << 8) + b[o + 3]

describe('yoğunluk okuma (ortak okuyucu)', () => {
  it('JPEG JFIF: dpi / dpcm okunur; birimsiz (0) ya da sıfır değer yok sayılır', () => {
    expect(readDensity(jpegWithJfif(1, 300, 300), 'jpeg')).toEqual({ unit: 'in', x: 300, y: 300 })
    expect(readDensity(jpegWithJfif(2, 118, 120), 'jpeg')).toEqual({ unit: 'cm', x: 118, y: 120 })
    expect(readDensity(jpegWithJfif(0, 1, 1), 'jpeg')).toBeNull()
    expect(readDensity(jpegWithJfif(1, 0, 300), 'jpeg')).toBeNull()
    expect(readDensity(jpegWithJfif(1, 72, 72, { app0: false }), 'jpeg')).toBeNull()
    expect(readDensity(new Uint8Array([1, 2, 3]), 'jpeg')).toBeNull()
  })

  it('PNG pHYs (metre) ve BMP başlığı; ppiOf ekran değeri (analyze ile aynı)', async () => {
    const png = (await encodePng({ width: 4, height: 4, rgba: new Uint8ClampedArray(64).fill(200) })).bytes
    expect(readDensity(png, 'png')).toBeNull()
    const withPhys = withPngDensity(png, { unit: 'm', x: 11811, y: 11811 })
    expect(readDensity(withPhys, 'png')).toEqual({ unit: 'm', x: 11811, y: 11811 })
    expect(ppiOf({ unit: 'm', x: 11811, y: 11811 })).toEqual({ x: 300, y: 300 })
    expect(ppiOf({ unit: 'cm', x: 118, y: 118 })).toEqual({ x: 299.7, y: 299.7 })
    expect(ppiOf({ unit: 'in', x: 96, y: 72 })).toEqual({ x: 96, y: 72 })
    // analyze aynı okuyucuyu kullanır
    expect(readImageFacts(withPhys, 'png').ppi).toEqual({ x: 300, y: 300 })
    expect(readImageFacts(jpegWithJfif(2, 118, 118), 'jpeg').ppi).toEqual({ x: 299.7, y: 299.7 })
  })
})

describe('yoğunluk yazma', () => {
  it('JPEG: var olan JFIF APP0 YAMALANIR (boy aynı, başka bayt değişmez); birim korunur', () => {
    const src = jpegWithJfif(0, 1, 1)
    const out = withJpegDensity(src, { unit: 'in', x: 300, y: 300 })
    expect(out.length).toBe(src.length)
    const diff = [...out].map((v, i) => (v !== src[i] ? i : -1)).filter((i) => i >= 0)
    expect(diff.every((i) => i >= 13 && i <= 17)).toBe(true)            // yalnız units + Xdensity + Ydensity
    expect(readDensity(out, 'jpeg')).toEqual({ unit: 'in', x: 300, y: 300 })
    const cm = withJpegDensity(src, { unit: 'cm', x: 118, y: 118 })
    expect(readDensity(cm, 'jpeg')).toEqual({ unit: 'cm', x: 118, y: 118 })
  })

  it('JPEG: JFIF APP0 yoksa SOI nin hemen ardına eklenir (18 bayt); metre başına kaynak dpi ye yuvarlanır', () => {
    const src = jpegWithJfif(0, 0, 0, { app0: false })
    const out = withJpegDensity(src, { unit: 'm', x: 11811, y: 11811 })
    expect(out.length).toBe(src.length + JFIF_SEGMENT_BYTES)
    expect([...out.subarray(0, 4)]).toEqual([0xff, 0xd8, 0xff, 0xe0])
    expect([...out.subarray(2 + JFIF_SEGMENT_BYTES)]).toEqual([...src.subarray(2)])
    expect(readDensity(out, 'jpeg')).toEqual({ unit: 'in', x: 300, y: 300 })
    expect(readImageFacts(out, 'jpeg')).toMatchObject({ ppi: { x: 300, y: 300 }, ppiChecked: true })
    // sığmayan değer (16 bit) ya da JPEG olmayan dosya: yazılmaz
    expect(withJpegDensity(src, { unit: 'in', x: 70000, y: 300 })).toBeNull()
    expect(withJpegDensity(new Uint8Array([1, 2, 3, 4]), { unit: 'in', x: 300, y: 300 })).toBeNull()
  })

  it('PNG: pHYs İLK IDAT tan önce, CRC doğru (bağımsız çözücü doğrular); var olan pHYs yeniden yazılır; pikseller aynı', async () => {
    const rgba = new Uint8ClampedArray(photoLike(12, 9, 4))
    const png = (await encodePng({ width: 12, height: 9, rgba })).bytes
    const out = withPngDensity(png, { unit: 'in', x: 300, y: 300 })
    expect(out.length).toBe(png.length + PHYS_CHUNK_BYTES)
    const chunks = pngChunks(out)                                           // CRC'ler doğrulanır (yanlışsa fırlatır)
    const types = chunks.map((c) => c.type)
    expect(types.indexOf('pHYs')).toBeLessThan(types.indexOf('IDAT'))
    const phys = chunks.find((c) => c.type === 'pHYs').data
    expect([be32(phys, 0), be32(phys, 4), phys[8]]).toEqual([11811, 11811, 1])
    expect(Buffer.from(decodePng(out).rgba).equals(Buffer.from(rgba))).toBe(true)
    // yeniden yazım: tek pHYs, yeni değer
    const again = withPngDensity(out, { unit: 'm', x: 2835, y: 2835 })
    expect(again.length).toBe(out.length)
    expect(pngChunks(again).filter((c) => c.type === 'pHYs')).toHaveLength(1)
    expect(readDensity(again, 'png')).toEqual({ unit: 'm', x: 2835, y: 2835 })
    // dpcm → × 100; metre başına kaynak BİREBİR
    expect(physFields({ unit: 'cm', x: 118, y: 120 })).toEqual({ x: 11800, y: 12000 })
    expect(physFields({ unit: 'm', x: 3779, y: 3780 })).toEqual({ x: 3779, y: 3780 })
    expect(jfifFields({ unit: 'cm', x: 118, y: 118 })).toEqual({ units: 2, x: 118, y: 118 })
  })

  it('yoğunluk yoksa ya da WebP / AVIF çıktısında hiçbir şey yazılmaz; hedef payı yalnız JPEG / PNG de', () => {
    expect(withDensity(jpegWithJfif(0, 1, 1), 'jpeg', null)).toBeNull()
    expect(withDensity(new Uint8Array(40), 'webp', { unit: 'in', x: 300, y: 300 })).toBeNull()
    expect(withDensity(new Uint8Array(40), 'avif', { unit: 'in', x: 300, y: 300 })).toBeNull()
    expect(densityOverhead('jpeg', { unit: 'in', x: 300, y: 300 })).toBe(JFIF_SEGMENT_BYTES)
    expect(densityOverhead('png', { unit: 'in', x: 300, y: 300 })).toBe(PHYS_CHUNK_BYTES)
    expect(densityOverhead('webp', { unit: 'in', x: 300, y: 300 })).toBe(0)
    expect(densityOverhead('png', null)).toBe(0)
  })
})

describe('iş akışı: kaynağın PPI değeri çıktıda kalır', () => {
  const run = (blob, options, platform = fakePlatform()) => processCore(blob, options, { platform })

  it('PNG (300 dpi pHYs) → PNG: aynı pHYs (yeniden boyutlansa da); bayt / kazanç / analiz son dosyadan', async () => {
    const w = 64
    const h = 48
    const rgba = new Uint8ClampedArray(photoLike(w, h, 9))
    const base = (await encodePng({ width: w, height: h, rgba })).bytes
    const src = withPngDensity(base, { unit: 'm', x: 11811, y: 11811 })
    const blob = new Blob([src], { type: 'image/png' })
    const r = await run(blob, { format: 'png', resize: { method: 'scale', width: 32 } })
    const out = new Uint8Array(await r.blob.arrayBuffer())
    expect(r.bytes).toBe(out.length)
    expect(readDensity(out, 'png')).toEqual({ unit: 'm', x: 11811, y: 11811 })
    expect(r.analysis.output.ppi).toEqual({ x: 300, y: 300 })
    expect(r.analysis.input.ppi).toEqual({ x: 300, y: 300 })
    pngChunks(out)                                                          // CRC'ler geçerli
  })

  it('yoğunluksuz kaynak: çıktıya hiçbir şey eklenmez (bugünkü baytlar)', async () => {
    const rgba = new Uint8ClampedArray(photoLike(40, 30, 2))
    const base = (await encodePng({ width: 40, height: 30, rgba })).bytes
    const r = await run(new Blob([base], { type: 'image/png' }), { format: 'png', resize: { method: 'scale', width: 20 } })
    const out = new Uint8Array(await r.blob.arrayBuffer())
    expect(readDensity(out, 'png')).toBeNull()
    expect(pngChunks(out).map((c) => c.type)).not.toContain('pHYs')
  })

  it('WebP çıktısı: yoğunluk yazılmaz (alan yok); kaynak yoğunluğu analizde kalır', async () => {
    const rgba = new Uint8ClampedArray(photoLike(40, 30, 2))
    const src = withPngDensity((await encodePng({ width: 40, height: 30, rgba })).bytes, { unit: 'in', x: 300, y: 300 })
    const r = await run(registerBlob(src, { data: rgba, width: 40, height: 30 }, 'image/png'), { format: 'webp' })
    expect(r.type).toBe('webp')
    expect(r.analysis.input.ppi).toEqual({ x: 300, y: 300 })
    expect(r.analysis.output.ppi).toBeNull()
  })

  it('hedef boyut: yazılacak pHYs payı hedefe sayılır — son dosya hedefi aşmaz', async () => {
    const rgba = new Uint8ClampedArray(photoLike(80, 60, 6))
    const src = withPngDensity((await encodePng({ width: 80, height: 60, rgba })).bytes, { unit: 'in', x: 300, y: 300 })
    const blob = new Blob([src], { type: 'image/png' })
    const probe = await run(blob, { format: 'png', quality: 100 })
    const target = Math.floor(probe.bytes * 0.6)
    const r = await run(blob, { format: 'png', targetBytes: target })
    const out = new Uint8Array(await r.blob.arrayBuffer())
    expect(r.bytes).toBe(out.length)
    if (!r.notes.includes('TARGET_NOT_REACHED')) expect(r.bytes).toBeLessThanOrEqual(target)
    expect(readDensity(out, 'png')).toEqual({ unit: 'm', x: 11811, y: 11811 })
  })
})
