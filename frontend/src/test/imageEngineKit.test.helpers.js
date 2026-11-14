// Görsel motoru testlerinin ortak araçları (vitest bunu test dosyası saymaz: ad `.test.js` ile bitmiyor).
//  • sentetik RGBA üreticileri (belirlenimci),
//  • PNG çözücü (motorun KENDİ çıktısını bağımsız doğrulamak için: CRC, zlib, süzgeç geri alma, palet açma),
//  • yeniden örnekleme kâhini (yalın, Float64, akışsız),
//  • sahte platform (çözme / kodlama tarayıcı API'si olmadan — iş akışı Node'da uçtan uca koşsun).
import { unzlibSync, zlibSync } from 'fflate'
import { crc32 } from '../utils/imageEngine/pngWriter.js'
import { EngineError } from '../utils/imageEngine/errors.js'

/** Belirlenimci sözde rastgele (LCG). */
export function rng(seed = 1) {
  let s = seed >>> 0
  return () => { s = (Math.imul(s, 1664525) + 1013904223) >>> 0; return s / 4294967296 }
}

export function solid(w, h, [r, g, b, a = 255]) {
  const d = new Uint8ClampedArray(w * h * 4)
  for (let i = 0; i < d.length; i += 4) { d[i] = r; d[i + 1] = g; d[i + 2] = b; d[i + 3] = a }
  return d
}

/** 1 piksellik siyah / beyaz dama. */
export function checkerboard(w, h) {
  const d = new Uint8ClampedArray(w * h * 4)
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
    const v = ((x + y) & 1) ? 255 : 0
    const i = (y * w + x) * 4
    d[i] = v; d[i + 1] = v; d[i + 2] = v; d[i + 3] = 255
  }
  return d
}

/** Yumuşak geçiş + hafif gürültü: fotoğraf benzeri. */
export function photoLike(w, h, seed = 7, noise = 10) {
  const rnd = rng(seed)
  const d = new Uint8ClampedArray(w * h * 4)
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
    const i = (y * w + x) * 4
    const u = x / w
    const v = y / h
    const n = (rnd() - 0.5) * noise
    d[i] = 120 + 90 * Math.sin(u * 5 + v * 2) + n
    d[i + 1] = 110 + 80 * Math.cos(v * 4 - u) + n
    d[i + 2] = 140 + 70 * Math.sin((u + v) * 3) + n
    d[i + 3] = 255
  }
  return d
}

/** Saydam zemin üstünde düz renkli "logo": ortada dolu daire + kenarı yumuşatılmış (yarı saydam) halka. */
export function logoWithAlpha(w, h, color = [220, 30, 40]) {
  const d = new Uint8ClampedArray(w * h * 4)
  const cx = w / 2
  const cy = h / 2
  const r = Math.min(w, h) * 0.3
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
    const i = (y * w + x) * 4
    const dist = Math.hypot(x + 0.5 - cx, y + 0.5 - cy)
    const a = Math.max(0, Math.min(1, r - dist + 0.5))
    if (a > 0) { d[i] = color[0]; d[i + 1] = color[1]; d[i + 2] = color[2]; d[i + 3] = Math.round(a * 255) }
  }
  return d
}

// ── PNG çözücü ────────────────────────────────────────────────────────────────────────────────────────────────────
const be32 = (b, o) => b[o] * 16777216 + (b[o + 1] << 16) + (b[o + 2] << 8) + b[o + 3]

/** PNG'yi parçalarına ayırır; her parçanın CRC'si doğrulanır (yanlışsa hata). */
export function pngChunks(bytes) {
  const sig = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]
  sig.forEach((v, i) => { if (bytes[i] !== v) throw new Error('PNG imzası yok') })
  const out = []
  let o = 8
  for (let guard = 0; guard < 100000 && o < bytes.length; guard++) {
    const len = be32(bytes, o)
    const type = String.fromCharCode(bytes[o + 4], bytes[o + 5], bytes[o + 6], bytes[o + 7])
    const data = bytes.subarray(o + 8, o + 8 + len)
    const crc = be32(bytes, o + 8 + len)
    if (crc32(bytes, o + 4, o + 8 + len) !== crc) throw new Error(`CRC tutmuyor: ${type}`)
    out.push({ type, data })
    o += 12 + len
    if (type === 'IEND') break
  }
  if (o !== bytes.length) throw new Error('IEND sonrası artık bayt')
  return out
}

const paeth = (a, b, c) => {
  const p = a + b - c
  const pa = Math.abs(p - a)
  const pb = Math.abs(p - b)
  const pc = Math.abs(p - c)
  return pa <= pb && pa <= pc ? a : pb <= pc ? b : c
}

/** PNG → { width, height, colorType, bitDepth, palette, indices, rgba, chunks, filters }. */
export function decodePng(bytes) {
  const chunks = pngChunks(bytes)
  const ihdr = chunks.find((c) => c.type === 'IHDR').data
  const width = be32(ihdr, 0)
  const height = be32(ihdr, 4)
  const bitDepth = ihdr[8]
  const colorType = ihdr[9]
  const plte = chunks.find((c) => c.type === 'PLTE')?.data
  const trns = chunks.find((c) => c.type === 'tRNS')?.data
  const idatLen = chunks.filter((c) => c.type === 'IDAT').reduce((n, c) => n + c.data.length, 0)
  const idat = new Uint8Array(idatLen)
  let p = 0
  for (const c of chunks) if (c.type === 'IDAT') { idat.set(c.data, p); p += c.data.length }
  const raw = unzlibSync(idat)
  const channels = [1, 0, 3, 1, 2, 0, 4][colorType]
  const bpp = Math.max(1, (channels * bitDepth) >> 3)
  const rowBytes = Math.ceil((width * channels * bitDepth) / 8)
  if (raw.length !== (rowBytes + 1) * height) throw new Error('ham veri boyu tutmuyor')
  const px = new Uint8Array(rowBytes * height)
  const filters = []
  for (let y = 0; y < height; y++) {
    const f = raw[y * (rowBytes + 1)]
    filters.push(f)
    for (let i = 0; i < rowBytes; i++) {
      const v = raw[y * (rowBytes + 1) + 1 + i]
      const a = i >= bpp ? px[y * rowBytes + i - bpp] : 0
      const b = y > 0 ? px[(y - 1) * rowBytes + i] : 0
      const c = y > 0 && i >= bpp ? px[(y - 1) * rowBytes + i - bpp] : 0
      const pred = f === 0 ? 0 : f === 1 ? a : f === 2 ? b : f === 3 ? (a + b) >> 1 : paeth(a, b, c)
      px[y * rowBytes + i] = (v + pred) & 0xff
    }
  }
  const rgba = new Uint8ClampedArray(width * height * 4)
  let indices = null
  if (colorType === 3) {
    indices = new Uint8Array(width * height)
    const per = 8 / bitDepth
    const mask = (1 << bitDepth) - 1
    for (let y = 0; y < height; y++) for (let x = 0; x < width; x++) {
      const byte = px[y * rowBytes + ((x / per) | 0)]
      const idx = bitDepth === 8 ? byte : (byte >> (8 - bitDepth * ((x % per) + 1))) & mask
      indices[y * width + x] = idx
      const o = (y * width + x) * 4
      rgba[o] = plte[idx * 3]; rgba[o + 1] = plte[idx * 3 + 1]; rgba[o + 2] = plte[idx * 3 + 2]
      rgba[o + 3] = trns && idx < trns.length ? trns[idx] : 255
    }
  } else {
    for (let q = 0, i = 0, o = 0; q < width * height; q++, o += 4) {
      if (colorType === 0) { rgba[o] = rgba[o + 1] = rgba[o + 2] = px[i++]; rgba[o + 3] = 255 }
      else if (colorType === 2) { rgba[o] = px[i++]; rgba[o + 1] = px[i++]; rgba[o + 2] = px[i++]; rgba[o + 3] = 255 }
      else if (colorType === 4) { rgba[o] = rgba[o + 1] = rgba[o + 2] = px[i++]; rgba[o + 3] = px[i++] }
      else { rgba[o] = px[i++]; rgba[o + 1] = px[i++]; rgba[o + 2] = px[i++]; rgba[o + 3] = px[i++] }
    }
  }
  return { width, height, colorType, bitDepth, palette: plte, trns, indices, rgba, chunks, filters }
}

// ── yeniden örnekleme kâhini ──────────────────────────────────────────────────────────────────────────────────────
const toLinear = (v) => { const c = v / 255; return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4) }
const toSrgb = (v) => {
  const c = v <= 0 ? 0 : v >= 1 ? 1 : v
  return Math.round((c <= 0.0031308 ? c * 12.92 : 1.055 * Math.pow(c, 1 / 2.4) - 0.055) * 255)
}
function catromOracle(t) {
  const x = Math.abs(t)
  if (x < 1) return 1.5 * x * x * x - 2.5 * x * x + 1
  if (x < 2) return -0.5 * x * x * x + 2.5 * x * x - 4 * x + 2
  return 0
}
function axisWeights(srcN, dstN) {
  const ratio = srcN / dstN
  const scale = Math.max(1, ratio)
  const support = 2 * scale
  const out = []
  for (let i = 0; i < dstN; i++) {
    const center = (i + 0.5) * ratio
    const taps = []
    let sum = 0
    for (let j = Math.max(0, Math.floor(center - support)); j <= Math.min(srcN - 1, Math.ceil(center + support)); j++) {
      const w = catromOracle((j + 0.5 - center) / scale)
      taps.push([j, w])
      sum += w
    }
    out.push(taps.map(([j, w]) => [j, w / sum]))
  }
  return out
}

/** Yalın (akışsız, Float64) doğrusal ışık + önçarpımlı Catmull-Rom küçültme — üretim kodunun kâhini. */
export function resampleOracle(src, srcW, srcH, dstW, dstH) {
  const lin = new Float64Array(srcW * srcH * 4)
  for (let p = 0, i = 0; p < srcW * srcH; p++, i += 4) {
    const a = src[i + 3] / 255
    lin[i] = toLinear(src[i]) * a; lin[i + 1] = toLinear(src[i + 1]) * a; lin[i + 2] = toLinear(src[i + 2]) * a; lin[i + 3] = a
  }
  const hx = axisWeights(srcW, dstW)
  const mid = new Float64Array(dstW * srcH * 4)
  for (let y = 0; y < srcH; y++) for (let x = 0; x < dstW; x++) for (const [j, w] of hx[x]) {
    for (let c = 0; c < 4; c++) mid[(y * dstW + x) * 4 + c] += lin[(y * srcW + j) * 4 + c] * w
  }
  const vy = axisWeights(srcH, dstH)
  const out = new Uint8ClampedArray(dstW * dstH * 4)
  for (let y = 0; y < dstH; y++) for (let x = 0; x < dstW; x++) {
    const acc = [0, 0, 0, 0]
    for (const [j, w] of vy[y]) for (let c = 0; c < 4; c++) acc[c] += mid[(j * dstW + x) * 4 + c] * w
    const o = (y * dstW + x) * 4
    if (acc[3] < 0.5 / 255) continue
    out[o] = toSrgb(acc[0] / acc[3]); out[o + 1] = toSrgb(acc[1] / acc[3]); out[o + 2] = toSrgb(acc[2] / acc[3])
    out[o + 3] = Math.round(Math.min(1, acc[3]) * 255)
  }
  return out
}

// ── sahte dosyalar + sahte platform ───────────────────────────────────────────────────────────────────────────────
const registry = new WeakMap()

/**
 * Gerçek başlıklı (SOI + JFIF + SOF0 [+ SOS + entropi] + EOI) en küçük "JPEG" — pikselleri sahte platform kayıttan verir.
 * `padBytes` ≥ 10 ise dosya büyüklüğü bir SOS bölümü + o kadar "entropi" baytıyla (EOI'den ÖNCE — gerçek JPEG gibi)
 * sağlanır; toplam boyut başlık + padBytes kalır. (EOI'den SONRAKİ bayt dosya eki sayılır ve ayıklanır.)
 */
export function fakeJpegBlob(image, { padBytes = 0, exifTiff = null, icc = false, trailer = 0 } = {}) {
  const parts = [[0xff, 0xd8], [0xff, 0xe0, 0, 16, 0x4a, 0x46, 0x49, 0x46, 0, 1, 1, 0, 0, 1, 0, 1, 0, 0]]
  if (exifTiff) {
    const len = 2 + 6 + exifTiff.length
    parts.push([0xff, 0xe1, (len >> 8) & 255, len & 255, 0x45, 0x78, 0x69, 0x66, 0, 0, ...exifTiff])
  }
  if (icc) parts.push([0xff, 0xe2, 0, 16, ...[...'ICC_PROFILE'].map((c) => c.charCodeAt(0)), 0, 1, 1])
  parts.push([0xff, 0xc0, 0, 11, 8, (image.height >> 8) & 255, image.height & 255, (image.width >> 8) & 255, image.width & 255, 1, 1, 0x11, 0])
  const sos = padBytes >= 10
  if (sos) parts.push([0xff, 0xda, 0, 8, 1, 1, 0, 0, 0x3f, 0])
  const head = Uint8Array.from(parts.flat())
  const body = sos ? padBytes - 10 : 0
  const bytes = new Uint8Array(head.length + body + 2 + (sos ? 0 : padBytes) + trailer)
  bytes.set(head)
  bytes.set([0xff, 0xd9], head.length + body)
  const blob = new Blob([bytes], { type: 'image/jpeg' })
  registry.set(blob, image)
  return blob
}

/** Kayıtlı pikselleri olan, başlığı elle yazılmış dosya (GIF / BMP / WebP / AVIF kaynak denemeleri). */
export function registerBlob(bytes, image, type = '') {
  const blob = new Blob([bytes], { type })
  registry.set(blob, image)
  return blob
}

const FAKE_MAGIC = [0x46, 0x4b, 0x45, 0x21]

/**
 * Sahte kayıplı kodlayıcı: kaliteye göre değerleri kaba adımlara yuvarlar, zlib ile sıkıştırır. Gerçek kodlayıcı gibi
 * kalite düştükçe dosya küçülür ve benzerlik azalır (tek yönlü). `encodes` kodlama sayacıdır.
 */
export function fakePlatform({ webp = true, failDecode = false } = {}) {
  const stats = { encodes: 0, decodes: 0, regionDecodes: 0, closed: 0, prepared: 0, sessionsClosed: 0 }
  const lossy = (image, mime, quality) => {
    const q = Math.round(quality * 100)
    const step = Math.max(1, Math.round((100 - q) / 3))
    const n = image.width * image.height
    const body = new Uint8Array(n * 4)
    for (let i = 0; i < n * 4; i++) body[i] = (i & 3) === 3 ? image.data[i] : Math.min(255, Math.round(image.data[i] / step) * step)
    const packed = zlibSync(body, { level: 3 })
    const head = new Uint8Array(13)
    head.set(FAKE_MAGIC)
    new DataView(head.buffer).setUint32(4, image.width)
    new DataView(head.buffer).setUint32(8, image.height)
    head[12] = q
    return new Blob([head, packed], { type: mime })
  }
  const unpack = async (blob) => {
    const b = new Uint8Array(await blob.arrayBuffer())
    const dv = new DataView(b.buffer, b.byteOffset)
    return { width: dv.getUint32(4), height: dv.getUint32(8), data: new Uint8ClampedArray(unzlibSync(b.subarray(13))) }
  }
  const platform = {
    stats,
    async available() { return true },
    async canEncode(mime) { return mime !== 'image/webp' || webp },
    async decode(blob) {
      stats.decodes += 1
      if (failDecode) throw new EngineError('DECODE_FAILED', { reason: 'decoder' })
      let image = registry.get(blob)
      if (!image) {
        const png = decodePng(new Uint8Array(await blob.arrayBuffer()))
        image = { data: png.rgba, width: png.width, height: png.height }
      }
      const { data, width, height } = image
      return {
        width,
        height,
        async readRows(x, y, w, h, bg = [0, 0, 0, 0]) {
          const out = new Uint8ClampedArray(w * h * 4)
          for (let yy = 0; yy < h; yy++) for (let xx = 0; xx < w; xx++) {
            const sx = x + xx
            const sy = y + yy
            const o = (yy * w + xx) * 4
            if (sx < 0 || sy < 0 || sx >= width || sy >= height) { out[o] = bg[0]; out[o + 1] = bg[1]; out[o + 2] = bg[2]; out[o + 3] = bg[3] }
            else { const i = (sy * width + sx) * 4; out[o] = data[i]; out[o + 1] = data[i + 1]; out[o + 2] = data[i + 2]; out[o + 3] = data[i + 3] }
          }
          return out
        },
        async thumbnail(maxSide) {
          const f = Math.min(1, maxSide / Math.max(width, height))
          const tw = Math.max(1, Math.round(width * f))
          const th = Math.max(1, Math.round(height * f))
          const out = new Uint8ClampedArray(tw * th * 4)
          for (let yy = 0; yy < th; yy++) for (let xx = 0; xx < tw; xx++) {
            const i = (Math.min(height - 1, Math.floor((yy + 0.5) * height / th)) * width + Math.min(width - 1, Math.floor((xx + 0.5) * width / tw))) * 4
            out.set(data.subarray(i, i + 4), (yy * tw + xx) * 4)
          }
          return { data: out, width: tw, height: th }
        },
        close() { stats.closed += 1 },
      }
    },
    async prepare(image) {
      stats.prepared += 1
      return {
        async encode(mime, quality) { stats.encodes += 1; return lossy(image, mime, quality) },
        close() { stats.sessionsClosed += 1 },
      }
    },
    async decodeRegions(blob, rects, expectW, expectH) {
      stats.regionDecodes += 1
      const img = await unpack(blob)
      if (img.width !== expectW || img.height !== expectH) throw new EngineError('ENCODE_FAILED', { reason: 'size_mismatch' })
      return rects.map((r) => {
        const out = new Uint8ClampedArray(r.w * r.h * 4)
        for (let y = 0; y < r.h; y++) {
          const s = ((r.y + y) * img.width + r.x) * 4
          out.set(img.data.subarray(s, s + r.w * 4), y * r.w * 4)
        }
        return out
      })
    },
  }
  return platform
}

/** Sahte kayıplı dosyayı açar (testte çıktının ölçüsünü / kalitesini okumak için). */
export async function readFakeLossy(blob) {
  const b = new Uint8Array(await blob.arrayBuffer())
  const dv = new DataView(b.buffer, b.byteOffset)
  return { width: dv.getUint32(4), height: dv.getUint32(8), quality: b[12], data: new Uint8ClampedArray(unzlibSync(b.subarray(13))) }
}
