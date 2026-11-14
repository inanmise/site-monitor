// Görsel motoru — iş akışı (tarayıcı API'lerine DOKUNMAZ; çözme / kodlama `platform` ile dışarıdan gelir — testte sahte
// platform, tarayıcıda platform.js). Sıra: seçenekler → başlık (sınırlar çözmeden ÖNCE) → üstveri → çöz → ölçü planı
// (akıllı kırpma / nesne) → şerit şerit doğrusal ışıkta küçült + netleştir → biçim adayları → hedef boyut → üstveri →
// "asla daha kötü" → sonuç. Her arama kodlama sayısıyla, her döngü sabitlerle sınırlıdır.
import { ENGINE_LIMITS, EngineError, MIME, MIN_TARGET_SIDE, NOTE_CODES } from './errors.js'
import { normalizeOptions } from './options.js'
import { checkLimits, probeBytes, readBlob } from './probe.js'
import { placeWindow, planResize, planThumbObject, shrinkDims } from './geometry.js'
import { createResampler } from './resample.js'
import { createSharpener, sharpenAmount } from './sharpen.js'
import { ANALYSIS_SIDE, detectObject, smartCropOffset } from './smartcrop.js'
import { DITHER_MIN_MSE, buildPaletteAsync, expandPalette, remapPixels } from './quantize.js'
import { encodePng, textChunk } from './pngWriter.js'
import { insertPngChunks, pngExifChunk, stripMetadata } from './strip.js'
import { assembleSample, extractSample, samplePlan, similarityAsync } from './ssim.js'
import { insertJpegExif, insertWebpExif, planMetadata, readMetadata } from './metadata.js'
import {
  MAX_DIM_STEPS, MAX_SEARCH_ENCODES, PALETTE_MAX_MSE, PNG_COLOR_LADDER, QUALITY_RANGE, SIMILARITY_TARGET,
  TARGET_QUALITY_FLOOR, bestQualityUnder, flattenOntoAsync, hasTransparency, pickForTarget, pickSmallest,
  keepEligible, pngColorsForQuality, resolveFormats, savingPct, searchQuality, shouldKeepOriginal,
} from './search.js'

/** Bu piksel sayısına kadar PNG'nin gerçek renk kodlaması da doğrudan denenir (üstünde önce bant karşılaştırması). */
const TRUECOLOR_TRY_PIXELS = 1_000_000
/** "En küçük biçim"de bu piksel sayısının üstündeki fotoğrafın gerçek renk PNG'si hiç üretilmez (kayıplı aday varken). */
const SKIP_TRUECOLOR_PIXELS = 1_500_000
/**
 * "En küçük biçim"de paletli PNG'nin bantlardan kestirilen boyutu en küçük kayıplı adayın bu katını aşıyorsa PNG adayı
 * hiç üretilmez (8 MP fotoğrafta titreşim + en yüksek sıkıştırma ≈ 7 sn sürer ve dosya 5 kat büyük çıkar).
 */
const PNG_HOPELESS_FACTOR = 2
/** Bir şeritte okunan en çok piksel (≈ 8 MB RGBA): 50 MP kaynak da küçük parçalarla akar. */
const STRIP_PIXELS = 2_000_000
/** Ana iş parçacığı yedeğinde şerit küçüktür: iki şerit arasında sayfaya nefes aldırılır (bir şerit ≈ onlarca ms). */
const STRIP_PIXELS_COOPERATIVE = 250_000
const now = () => (typeof performance !== 'undefined' && performance.now ? performance.now() : Date.now())

function macrotask() {
  return new Promise((resolve) => {
    if (typeof MessageChannel === 'function') {
      const ch = new MessageChannel()
      ch.port1.onmessage = () => { ch.port1.close(); resolve() }
      ch.port2.postMessage(0)
    } else {
      setTimeout(resolve, 0)
    }
  })
}

/**
 * İş bağlamı: iptal denetimi, ilerleme (seyreltilmiş) ve — ana iş parçacığı yedeğinde — sayfaya nefes aldırma.
 * Worker'da `cooperative` kapalıdır: iptal Worker'ın sonlandırılmasıyla olur, araya girmeye gerek yoktur.
 */
export function makeCtx(env) {
  const { signal, onProgress, cooperative } = env
  let lastYield = now()
  let lastPhase = null
  let lastFraction = -1
  const cancelled = () => new EngineError('CANCELLED')
  const checkpoint = async () => {
    if (signal?.aborted) throw cancelled()
    if (cooperative && now() - lastYield > 12) {
      await macrotask()
      lastYield = now()
      if (signal?.aborted) throw cancelled()
    }
  }
  return {
    checkpoint,
    cooperative: !!cooperative,
    /** Saf modüllere verilen `tick`: yalnız iptal / nefes gerektiğinde tanımlı (Worker'da boş → hiç beklenmez). */
    tick: signal || cooperative ? checkpoint : undefined,
    /**
     * Adım adım hesaplara (palet tasarımı, benzerlik, zemine bindirme) verilen `tick`: YALNIZ ana iş parçacığı
     * yedeğinde tanımlı — Worker'da bu hesaplar eşzamanlı, tek seferde koşar (araya bekleme girmez).
     */
    stepTick: cooperative ? checkpoint : undefined,
    progress(phase, fraction) {
      if (typeof onProgress !== 'function') return
      const f = fraction < 0 ? 0 : fraction > 1 ? 1 : fraction
      if (phase === lastPhase && f < 1 && f - lastFraction < 0.02) return
      lastPhase = phase
      lastFraction = f
      try { onProgress(phase, f) } catch { /* dinleyici hatası işi durdurmaz */ }
    },
  }
}

/** Ölçü planı: kırpma penceresinin yeri (akıllı kırpma) ve `thumb`ın nesne kipi. */
async function planGeometry(src, opts) {
  const notes = []
  let plan = planResize(src.width, src.height, opts.resize)
  let background = [0, 0, 0, 0]
  const method = opts.resize.method
  if ((method === 'cover' || method === 'thumb') && (plan.cropAxis || method === 'thumb')) {
    let thumb = null
    try { thumb = await src.thumbnail(ANALYSIS_SIDE) } catch { thumb = null }
    let objectPlan = null
    if (thumb && method === 'thumb') {
      const obj = detectObject(thumb.data, thumb.width, thumb.height)
      if (obj) {
        const sx = src.width / thumb.width
        const sy = src.height / thumb.height
        const bbox = { x0: Math.floor(obj.bbox.x0 * sx), y0: Math.floor(obj.bbox.y0 * sy),
          x1: Math.min(src.width, Math.ceil(obj.bbox.x1 * sx)), y1: Math.min(src.height, Math.ceil(obj.bbox.y1 * sy)) }
        // null: nesnenin oranı kutudan çok farklı (dolgulu pencere sınırları aşardı) → akıllı kırpmaya düşülür
        objectPlan = planThumbObject(src.width, src.height, opts.resize.width, opts.resize.height, bbox)
        if (objectPlan) {
          background = obj.background
          if (objectPlan.padded) notes.push('THUMB_PADDED')
          else if (objectPlan.cropped) notes.push('SMART_CROP')
        }
      }
    }
    if (objectPlan) plan = objectPlan
    else if (plan.cropAxis) {
      if (thumb) plan = placeWindow(plan, src.width, src.height, smartCropOffset(thumb, src.width, src.height, plan))
      notes.push('SMART_CROP')
    }
  }
  if (plan.notUpscaled) notes.push('NOT_UPSCALED')
  return { plan, notes, background }
}

/** Planın penceresini kaynaktan şerit şerit okur; gerekirse doğrusal ışıkta küçültür ve netleştirir. */
async function materialise(src, plan, background, ctx) {
  const win = plan.window
  const { outW, outH } = plan
  checkLimits(win.w, win.h)                                   // okunacak pencere de sınır içinde (dolgulu thumb dahil)
  checkLimits(outW, outH)
  const out = new Uint8ClampedArray(outW * outH * 4)
  const identity = win.w === outW && win.h === outH
  const rowsPerStrip = Math.max(8, Math.min(1024, Math.floor((ctx.cooperative ? STRIP_PIXELS_COOPERATIVE : STRIP_PIXELS) / win.w)))
  const phase = identity ? 'decode' : 'resize'
  let sawAlpha = false
  let sawPartial = false
  let push
  let finish = () => {}
  if (identity) {
    let y = 0
    push = (strip, n) => { out.set(strip.subarray(0, n * win.w * 4), y * outW * 4); y += n }
  } else {
    const sharp = createSharpener(outW, outH, sharpenAmount(Math.min(outW / win.w, outH / win.h)),
      (y, row) => out.set(row, y * outW * 4))
    const rs = createResampler({ srcW: win.w, srcH: win.h, dstW: outW, dstH: outH, onRow: (y, row) => sharp.push(row) })
    push = (strip, n) => { for (let r = 0; r < n; r++) rs.pushRow(strip, r * win.w * 4) }
    finish = () => sharp.finish()
  }
  for (let y = 0; y < win.h; y += rowsPerStrip) {
    await ctx.checkpoint()
    const n = Math.min(rowsPerStrip, win.h - y)
    const strip = await src.readRows(win.x, win.y + y, win.w, n, background)
    if (!sawAlpha) sawAlpha = hasTransparency(strip, n * win.w)
    if (sawAlpha && !sawPartial) sawPartial = hasPartialAlpha(strip, n * win.w)
    push(strip, n)
    ctx.progress(phase, identity ? 0.5 + 0.5 * ((y + n) / win.h) : (y + n) / win.h)
  }
  finish()
  return { data: out, width: outW, height: outH, hasAlpha: identity ? sawAlpha : hasTransparency(out, outW * outH), sourceAlpha: sawAlpha,
    partialAlpha: sawPartial }
}

/** 0 < a < 255 olan piksel var mı (tarayıcı bunları önçarpımlı saklar: renk değerleri yuvarlanır). */
function hasPartialAlpha(rgba, pixelCount) {
  for (let i = 3, end = pixelCount * 4; i < end; i += 4) { const a = rgba[i]; if (a !== 0 && a !== 255) return true }
  return false
}

/**
 * "Asla daha kötü" kuralının döndüreceği ÖZGÜN dosya — ama üstverisi ayıklanmış: görüntü baytları aynı kalır, EXIF /
 * XMP / IPTC / yorum / metin / dosya sonu ekleri atılır; yalnız `preserve`in istediği türler (ve özgün EXIF yönü —
 * pikseller döndürülmediği için gerekir) en küçük blokla yeniden yazılır.
 * @returns {Promise<{ bytes: Uint8Array|null, size: number, notes: string[] }|null>} `bytes` null = dosya hiç değişmedi
 *   (çağıranın Blob'u döner). `null` = güvenle ayıklanamadı → özgün dosya DÖNMEZ (üstveri sızmasın), normal sonuç döner.
 */
async function keptOriginal(blob, info, meta, preserve) {
  let bytes
  try { bytes = await readBlob(blob) } catch { return null }
  const s = stripMetadata(bytes, info.type)
  if (!s) return null
  if (!s.removed) return { bytes: null, size: bytes.length, notes: [] }
  const writable = info.type === 'jpeg' || info.type === 'webp' || info.type === 'png'
  const plan = writable ? planMetadata(meta, preserve, info.type, s.orientation) : { exif: null, text: [], kept: [], dropped: [] }
  let kept = plan.kept
  let dropped = plan.dropped
  let out = s.bytes
  const needOrientation = s.orientation >= 2 && s.orientation <= 8
  let written = true
  if (info.type === 'png') {
    const chunks = plan.text.map((t) => textChunk(t.keyword, t.text))
    if (plan.exif) chunks.push(pngExifChunk(plan.exif))
    if (chunks.length) {
      const withChunks = insertPngChunks(out, s.insertAt, chunks)
      if (withChunks) out = withChunks
      else written = false
    }
  } else if (plan.exif) {
    const withExif = info.type === 'jpeg' ? insertJpegExif(out, plan.exif) : insertWebpExif(out, plan.exif)
    if (withExif && withExif !== out) out = withExif
    else written = false
  } else if (needOrientation) {
    written = false                                           // GIF / BMP / AVIF yön taşımaz (buraya gelinmez)
  }
  if (!written) {
    if (needOrientation) return null                          // yönsüz özgün dosya YANLIŞ görünürdü: normal işlenir
    dropped = [...dropped, ...kept]
    kept = []
  }
  const notes = ['METADATA_STRIPPED']
  if (kept.length) notes.push('METADATA_PRESERVED')
  if (dropped.length) notes.push('METADATA_NOT_PRESERVED')
  return { bytes: out, size: out.length, notes }
}

/** Bellekteki görseli (hedef boyut kipi) daha küçük ölçüye indirir — aynı süzgeç + netleştirme. */
async function shrinkImage(image, width, height, ctx) {
  const out = new Uint8ClampedArray(width * height * 4)
  const sharp = createSharpener(width, height, sharpenAmount(Math.min(width / image.width, height / image.height)),
    (y, row) => out.set(row, y * width * 4))
  const rs = createResampler({ srcW: image.width, srcH: image.height, dstW: width, dstH: height, onRow: (y, row) => sharp.push(row) })
  for (let r = 0; r < image.height; r++) {
    rs.pushRow(image.data, r * image.width * 4)
    if ((r & 63) === 63) await ctx.checkpoint()
  }
  sharp.finish()
  return { data: out, width, height, hasAlpha: image.hasAlpha && hasTransparency(out, width * height) }
}

const lossyCandidate = (type, r, notes, image) => ({ type, blob: r.blob, bytes: null, size: r.size, quality: r.q,
  colors: null, similarity: r.similarity, notes: [...notes], width: image.width, height: image.height })

/**
 * Kayıplı kodlayıcı oturumu (JPEG / WebP): kodla, ölç, otomatik kalite ara. Her kodlamadan önce iptal denetlenir (ana iş
 * parçacığı yedeğinde arama, iptalden sonra kalan kodlamaları yapmaz).
 */
async function openLossy(type, image, opts, env, ctx) {
  const { platform } = env
  const notes = []
  let img = image
  if (type === 'jpeg' && image.hasAlpha) {
    img = { data: await flattenOntoAsync(image.data, image.width * image.height, opts.backgroundRgb, ctx.stepTick),
      width: image.width, height: image.height, hasAlpha: false }
    notes.push('ALPHA_FLATTENED')
  }
  const plan = samplePlan(img.width, img.height)
  const ref = extractSample(img.data, img.width, img.height, plan)
  const mime = MIME[type]
  const session = await platform.prepare(img)
  let proxy = null
  const encode = async (q) => { await ctx.checkpoint(); return session.encode(mime, q / 100) }
  const measure = async (blob) => {
    const tiles = await platform.decodeRegions(blob, plan.tiles, img.width, img.height)
    return similarityAsync(ref, assembleSample(tiles, plan), plan.width, plan.height, ctx.stepTick)
  }
  const auto = async (lo, hi, onStep) => {
    if (plan.whole) {
      return searchQuality(async (q) => {
        const blob = await encode(q)
        const s = await measure(blob)
        onStep()
        return { blob, size: blob.size, similarity: s }
      }, lo, hi, SIMILARITY_TARGET, MAX_SEARCH_ENCODES)
    }
    // Büyük görsel: arama ÖRNEKLEM mozaiği üstünde (aynı pikseller, tam çözünürlük); tam görsel bir kez kodlanır
    proxy = await platform.prepare({ data: ref, width: plan.width, height: plan.height, hasAlpha: img.hasAlpha })
    const whole = [{ x: 0, y: 0, w: plan.width, h: plan.height }]
    const found = await searchQuality(async (q) => {
      await ctx.checkpoint()
      const b = await proxy.encode(mime, q / 100)
      const px = await platform.decodeRegions(b, whole, plan.width, plan.height)
      onStep()
      return { similarity: await similarityAsync(ref, px[0], plan.width, plan.height, ctx.stepTick) }
    }, lo, hi, SIMILARITY_TARGET, MAX_SEARCH_ENCODES - 1)
    const blob = await encode(found.q)
    return { q: found.q, blob, size: blob.size, similarity: await measure(blob), encodes: found.encodes + 1 }
  }
  return {
    type, notes, encode, measure, auto, image: img,
    close() {
      try { proxy?.close() } catch { /* yok */ }
      try { session.close() } catch { /* yok */ }
    },
  }
}

async function runLossy(type, image, opts, env, ctx) {
  await ctx.checkpoint()
  const enc = await openLossy(type, image, opts, env, ctx)
  try {
    let r
    if (opts.quality === 'auto') {
      const [lo, hi] = QUALITY_RANGE[type]
      let step = 0
      ctx.progress('search', 0)
      r = await enc.auto(lo, hi, () => { step += 1; ctx.progress('search', Math.min(0.95, step / MAX_SEARCH_ENCODES)) })
      ctx.progress('search', 1)
    } else {
      ctx.progress('encode', 0)
      const blob = await enc.encode(opts.quality)
      r = { q: opts.quality, blob, size: blob.size, similarity: await enc.measure(blob) }
      ctx.progress('encode', 1)
    }
    await ctx.checkpoint()
    return lossyCandidate(type, r, enc.notes, image)
  } finally {
    enc.close()
  }
}

function sampleFromIndices(indices, palette, width, plan) {
  if (plan.whole) return expandPalette(indices, palette)
  const out = new Uint8ClampedArray(plan.width * plan.height * 4)
  plan.tiles.forEach((t, k) => {
    const ox = (k % plan.cols) * t.w
    const oy = Math.floor(k / plan.cols) * t.h
    for (let y = 0; y < t.h; y++) {
      let o = ((oy + y) * plan.width + ox) * 4
      for (let x = 0, p = (t.y + y) * width + t.x; x < t.w; x++, p++, o += 4) {
        const c = indices[p] * 4
        out[o] = palette[c]; out[o + 1] = palette[c + 1]; out[o + 2] = palette[c + 2]; out[o + 3] = palette[c + 3]
      }
    }
  })
  return out
}

/**
 * PNG adayı. `mode.kind`: 'auto' (palet; hata eşiği aşılırsa gerçek renk), 'lossless' (birebir palet ya da gerçek
 * renk), 'palette' (her durumda `mode.colors` renkli palet), 'auto-or-skip' (palet kabul edilemezse aday YOK — "en
 * küçük biçim"de fotoğrafın gerçek renk PNG'si boşuna üretilmesin).
 */
export async function runPng(image, mode, text, ctx, hopelessAbove = 0) {
  const { width, height } = image
  await ctx.checkpoint()
  ctx.progress('encode', 0)
  const pal = await buildPaletteAsync(image.data, width * height, mode.colors || 256, ctx.stepTick)
  await ctx.checkpoint()
  let usePalette = true
  if (!pal.exact) {
    if (mode.kind === 'lossless') usePalette = false
    else if (mode.kind === 'auto' || mode.kind === 'auto-or-skip') usePalette = pal.mse <= PALETTE_MAX_MSE
  }
  const base = { type: 'png', blob: null, quality: null, width, height }
  // "En küçük biçim": kayıplı aday varken umutsuz (fotoğraf) paletli PNG'nin tamamı kodlanmaz
  if (usePalette && !pal.exact && hopelessAbove > 0 && await estimatePalettePng(image, pal, ctx) > hopelessAbove) return null
  if (!usePalette) {
    if (mode.kind === 'auto-or-skip') return null
    const png = await encodePng({ width, height, rgba: image.data }, { text, tick: ctx.tick })
    ctx.progress('encode', 1)
    return { ...base, bytes: png.bytes, size: png.bytes.length, colors: null, similarity: 1, notes: ['TRUECOLOR_KEPT'] }
  }
  const dither = !pal.exact && pal.mse > DITHER_MIN_MSE
  const indices = await remapPixels(image.data, width, height, pal, { dither, tick: ctx.tick })
  ctx.progress('encode', 0.5)
  let sim = 1
  if (!pal.exact) {
    const plan = samplePlan(width, height)
    sim = await similarityAsync(extractSample(image.data, width, height, plan), sampleFromIndices(indices, pal.palette, width, plan),
      plan.width, plan.height, ctx.stepTick)
  }
  await ctx.checkpoint()
  const png = await encodePng({ width, height, indices, palette: pal.palette, colors: pal.colors }, { text, tick: ctx.tick })
  // Düzgün geçişli yapay görselde (zemin degradesi) KAYIPSIZ gerçek renk, titreşimli paletten küçük çıkabilir:
  // kayıplı sonuç kayıpsızdan büyükse kayıpsız olan döner.
  if (!pal.exact && mode.kind !== 'palette' && await truecolorMayWin(image, indices, pal, ctx)) {
    const tc = await encodePng({ width, height, rgba: image.data }, { text, tick: ctx.tick })
    if (tc.bytes.length <= png.bytes.length) {
      ctx.progress('encode', 1)
      return { ...base, bytes: tc.bytes, size: tc.bytes.length, colors: null, similarity: 1, notes: ['TRUECOLOR_KEPT'] }
    }
  }
  ctx.progress('encode', 1)
  return { ...base, bytes: png.bytes, size: png.bytes.length, colors: pal.colors, similarity: sim, notes: [] }
}

/**
 * Gerçek renk kodlaması paletliyi yenebilir mi? Küçük görselde doğrudan denenir; büyükte görsele yayılmış 8 bandın
 * (16'şar satır) iki kodlaması karşılaştırılır — tam kodlama yalnız umut varsa yapılır.
 */
async function truecolorMayWin(image, indices, pal, ctx) {
  const { width, height } = image
  if (width * height <= TRUECOLOR_TRY_PIXELS) return true
  if (height < BANDS * BAND_ROWS * 2) return true
  const bh = BANDS * BAND_ROWS
  const rgba = bandPixels(image)
  const idx = new Uint8Array(width * bh)
  for (let b = 0; b < BANDS; b++) {
    const y0 = bandStart(height, b)
    idx.set(indices.subarray(y0 * width, (y0 + BAND_ROWS) * width), b * BAND_ROWS * width)
  }
  const a = await encodePng({ width, height: bh, rgba }, { level: 6, tick: ctx.tick })
  const p = await encodePng({ width, height: bh, indices: idx, palette: pal.palette, colors: pal.colors }, { level: 6, tick: ctx.tick })
  return a.bytes.length <= p.bytes.length * 1.15
}

const BANDS = 8
const BAND_ROWS = 16
const bandStart = (height, b) => Math.floor(((height - BAND_ROWS) * b) / (BANDS - 1))

/** Görsele eşit aralıkla yayılmış 8 bandın (16'şar satır) pikselleri, alt alta. */
function bandPixels(image) {
  const { width, height } = image
  const out = new Uint8ClampedArray(width * BANDS * BAND_ROWS * 4)
  for (let b = 0; b < BANDS; b++) {
    const y0 = bandStart(height, b)
    out.set(image.data.subarray(y0 * width * 4, (y0 + BAND_ROWS) * width * 4), b * BAND_ROWS * width * 4)
  }
  return out
}

/**
 * Paletli PNG'nin YAKLAŞIK boyutu (bayt): 8 bant palete eşlenir (titreşimli), kodlanır ve görselin yüksekliğine
 * oranlanır. Küçük görselde (≤ 1 MP ya da bantlara sığmayan) kestirim yapılmaz → 0 ("bilinmiyor": aday üretilir).
 */
export async function estimatePalettePng(image, pal, ctx) {
  const { width, height } = image
  if (width * height <= TRUECOLOR_TRY_PIXELS || height < BANDS * BAND_ROWS * 2) return 0
  const bh = BANDS * BAND_ROWS
  const idx = await remapPixels(bandPixels(image), width, bh, pal, { dither: pal.mse > DITHER_MIN_MSE, tick: ctx.tick })
  const p = await encodePng({ width, height: bh, indices: idx, palette: pal.palette, colors: pal.colors }, { level: 6, tick: ctx.tick })
  return (p.bytes.length * height) / bh
}

function pngMode(opts, smallest) {
  if (opts.quality === 'auto') return { kind: smallest ? 'auto-or-skip' : 'auto' }
  const colors = pngColorsForQuality(opts.quality)
  return colors === 0 ? { kind: 'lossless' } : { kind: 'palette', colors }
}

/** Hedef boyut — kayıplı biçim: önce kalite (tabana dek), sonra ölçü. */
async function fitLossy(type, work, start, target, opts, env, ctx) {
  const hi = start.quality
  const lo = Math.min(TARGET_QUALITY_FLOOR, hi)
  const tryAt = async (image) => {
    const enc = await openLossy(type, image, opts, env, ctx)
    try {
      const res = await bestQualityUnder(async (q) => { const blob = await enc.encode(q); return { blob, size: blob.size } }, lo, hi, target)
      const pick = res.fit || res.low
      const sim = await enc.measure(pick.blob)
      return { fit: !!res.fit, cand: lossyCandidate(type, { q: pick.q, blob: pick.blob, size: pick.size, similarity: sim }, enc.notes, image) }
    } finally {
      enc.close()
    }
  }
  let r = await tryAt(work)
  if (r.fit) return r.cand
  let best = r.cand.size < start.size ? r.cand : start
  let cur = { width: work.width, height: work.height }
  for (let step = 0; step < MAX_DIM_STEPS; step++) {
    await ctx.checkpoint()
    const f = Math.max(0.2, Math.min(0.9, Math.sqrt(target / best.size) * 0.9))
    const dims = shrinkDims(cur.width, cur.height, f, MIN_TARGET_SIDE)
    if (!dims) break
    cur = dims
    r = await tryAt(await shrinkImage(work, dims.width, dims.height, ctx))
    ctx.progress('search', (step + 1) / MAX_DIM_STEPS)
    if (r.fit) return r.cand
    if (r.cand.size < best.size) best = r.cand
  }
  return best
}

/** Hedef boyut — PNG: önce palet boyu (256 → 16), sonra ölçü. */
async function fitPng(work, start, target, text, ctx) {
  let best = start
  const ladder = PNG_COLOR_LADDER.filter((k) => start.colors == null || k < start.colors)
  for (const colors of ladder) {
    const c = await runPng(work, { kind: 'palette', colors }, text, ctx)
    if (c.size <= target) return c
    if (c.size < best.size) best = c
  }
  let cur = { width: work.width, height: work.height }
  for (let step = 0; step < MAX_DIM_STEPS; step++) {
    await ctx.checkpoint()
    const f = Math.max(0.2, Math.min(0.9, Math.sqrt(target / best.size) * 0.9))
    const dims = shrinkDims(cur.width, cur.height, f, MIN_TARGET_SIDE)
    if (!dims) break
    cur = dims
    const small = await shrinkImage(work, dims.width, dims.height, ctx)
    for (const colors of [256, 64, 16]) {
      const c = await runPng(small, { kind: 'palette', colors }, text, ctx)
      if (c.size <= target) return c
      if (c.size < best.size) best = c
    }
    ctx.progress('search', (step + 1) / MAX_DIM_STEPS)
  }
  return best
}

const orderNotes = (set) => NOTE_CODES.filter((n) => set.has(n))

/**
 * Çözülen görsel başlıkta okunandan BÜYÜK olamaz (yalan başlık / tarayıcının ilk kareye göre büyüttüğü GIF):
 * büyükse TOO_MANY_PIXELS (`reason: 'header_mismatch'`). Yalnız alan ve uzun kenar karşılaştırılır (yön uygulanınca
 * en ve boy yer değiştirebilir).
 */
export function assertDecodedWithinProbe(width, height, info) {
  const pw = info?.width > 0 ? info.width : 0
  const ph = info?.height > 0 ? info.height : 0
  if (width * height > pw * ph || Math.max(width, height) > Math.max(pw, ph)) {
    throw new EngineError('TOO_MANY_PIXELS', { width, height, maxPixels: ENGINE_LIMITS.maxPixels, maxSide: ENGINE_LIMITS.maxSide,
      reason: 'header_mismatch' })
  }
}

/**
 * TEK görseli işler. @param {Blob} blob @param {object} rawOptions sözleşme §2 seçenekleri
 * @param {{ platform: object, signal?: AbortSignal, onProgress?: Function, cooperative?: boolean }} env
 * @returns {Promise<object>} EngineResult — `kept` ise `blob` null döner (çağıran özgün Blob'u koyar)
 */
export async function processCore(blob, rawOptions, env) {
  const opts = normalizeOptions(rawOptions)
  const ctx = makeCtx(env)
  const { platform } = env
  const sourceBytes = Number(blob?.size)
  if (!blob || !Number.isFinite(sourceBytes)) throw new EngineError('INVALID_OPTIONS', { field: 'blob', reason: 'type' })
  if (sourceBytes > ENGINE_LIMITS.maxInputBytes) throw new EngineError('TOO_LARGE', { bytes: sourceBytes, maxBytes: ENGINE_LIMITS.maxInputBytes })
  if (sourceBytes === 0) throw new EngineError('UNSUPPORTED_FORMAT', { detected: 'empty' })
  await ctx.checkpoint()
  let bytes
  try { bytes = await readBlob(blob) } catch { throw new EngineError('DECODE_FAILED', { reason: 'read' }) }
  const info = probeBytes(bytes, bytes.length)               // tür + ölçü sınırı: ÇÖZMEDEN önce
  const meta = readMetadata(bytes, info.type)
  bytes = null
  ctx.progress('decode', 0)
  await ctx.checkpoint()

  const src = await platform.decode(blob, info)
  let work
  let plan
  let srcW
  let srcH
  const notes = new Set()
  try {
    srcW = src.width
    srcH = src.height
    if (!(srcW > 0 && srcH > 0)) throw new EngineError('DECODE_FAILED', { reason: 'empty' })
    assertDecodedWithinProbe(srcW, srcH, info)                // yalan başlık: çözülen, başlıktakinden büyük
    checkLimits(srcW, srcH)
    ctx.progress('decode', 0.5)
    const geo = await planGeometry(src, opts)
    plan = geo.plan
    geo.notes.forEach((n) => notes.add(n))
    work = await materialise(src, plan, geo.background, ctx)
  } finally {
    try { src.close() } catch { /* yok */ }
  }
  ctx.progress('decode', 1)

  const webpNeeded = opts.format === 'webp' || opts.format === 'smallest'
    || (opts.format === 'keep' && (info.type === 'webp' || info.type === 'avif'))
  const webpOk = webpNeeded ? await platform.canEncode(MIME.webp) : false
  const formats = resolveFormats(opts.format, info.type, work.hasAlpha, webpOk)
  formats.notes.forEach((n) => notes.add(n))
  const pngText = planMetadata(meta, opts.preserve, 'png')
  const exifPlan = { jpeg: planMetadata(meta, opts.preserve, 'jpeg'), webp: planMetadata(meta, opts.preserve, 'webp') }
  const smallest = opts.format === 'smallest'

  // "En küçük biçim"de önce kayıplı adaylar üretilir: PNG adayı onların boyutuna bakarak erken elenebilir
  const order = smallest ? [...formats.list.filter((t) => t !== 'png'), ...formats.list.filter((t) => t === 'png')] : formats.list
  const made = new Map()
  for (const type of order) {
    await ctx.checkpoint()
    let c
    if (type === 'png') {
      const lossy = [...made.values()].map((x) => x.size)
      const hopelessAbove = smallest && lossy.length ? Math.min(...lossy) * PNG_HOPELESS_FACTOR : 0
      c = await runPng(work, pngMode(opts, smallest && formats.list.length > 1 && work.width * work.height > SKIP_TRUECOLOR_PIXELS), pngText.text, ctx, hopelessAbove)
    } else {
      c = await runLossy(type, work, opts, env, ctx)
    }
    if (c) made.set(type, c)
  }
  const candidates = formats.list.map((t) => made.get(t)).filter(Boolean)        // eşitlikte liste sırası (jpeg, png, webp)
  if (!candidates.length) candidates.push(await runPng(work, { kind: 'auto' }, pngText.text, ctx))
  let final = smallest ? pickSmallest(candidates, SIMILARITY_TARGET) : candidates[0]

  // Hedef boyut: kaliteyi, sonra ölçüyü düşür (üstveri payı hedeften düşülür)
  if (opts.targetBytes != null && final.size > opts.targetBytes) {
    const fitted = []
    for (const c of candidates) {
      await ctx.checkpoint()
      if (c.type === 'png') fitted.push(await fitPng(work, c, opts.targetBytes, pngText.text, ctx))
      else {
        const overhead = exifPlan[c.type].exif ? exifPlan[c.type].exif.length + 26 : 0
        fitted.push(await fitLossy(c.type, work, c, Math.max(1, opts.targetBytes - overhead), opts, env, ctx))
      }
    }
    final = pickForTarget(fitted, opts.targetBytes)
  }
  final = { ...final, notes: [...final.notes] }

  // Üstveri: PNG'de metin parçaları kodlamada yazıldı; JPEG / WebP'ye en küçük EXIF bloğu eklenir
  const mplan = final.type === 'png' ? pngText : exifPlan[final.type]
  let kept = mplan.kept
  let dropped = mplan.dropped
  if (final.type !== 'png' && mplan.exif) {
    const raw = await readBlob(final.blob)
    const withExif = final.type === 'jpeg' ? insertJpegExif(raw, mplan.exif) : insertWebpExif(raw, mplan.exif)
    if (withExif && withExif !== raw) {
      final.bytes = withExif
      final.blob = null
      final.size = withExif.length
    } else {
      dropped = [...dropped, ...kept]
      kept = []
    }
  }
  if (kept.length) notes.add('METADATA_PRESERVED')
  if (dropped.length) notes.add('METADATA_NOT_PRESERVED')
  final.notes.forEach((n) => notes.add(n))

  const mime = MIME[info.type]
  const original = { type: info.type, mime, width: srcW, height: srcH, bytes: sourceBytes,
    hasAlpha: info.hasAlpha === true || work.sourceAlpha, animated: info.animated, metadata: { ...meta.present } }
  const sameDimensions = final.width === srcW && final.height === srcH && !plan.cropped && !plan.padded
  if (opts.targetBytes != null && final.size > opts.targetBytes) notes.add('TARGET_NOT_REACHED')

  // "Asla daha kötü": özgün dosya (üstverisi ayıklanmış) çıktıdan küçük ya da eşitse o döner. Ayıklanamayan dosya
  // (bozuk yapı, AVIF'te EXIF / XMP …) DÖNMEZ — üstveri sızmasın diye normal sonuç verilir.
  const keepRule = { requestedFormat: opts.format, sourceType: info.type, outType: final.type, sameDimensions }
  if (keepEligible(keepRule)) {
    await ctx.checkpoint()
    const k = await keptOriginal(blob, info, meta, opts.preserve)
    if (k && shouldKeepOriginal({ ...keepRule, outBytes: final.size, sourceBytes: k.size })) {
      const keptNotes = new Set(['KEPT_ORIGINAL', ...k.notes])
      if (notes.has('WEBP_UNAVAILABLE')) keptNotes.add('WEBP_UNAVAILABLE')
      if (notes.has('NOT_UPSCALED')) keptNotes.add('NOT_UPSCALED')
      if (opts.targetBytes != null && k.size > opts.targetBytes) keptNotes.add('TARGET_NOT_REACHED')
      return { blob: k.bytes ? new Blob([k.bytes], { type: mime }) : null, type: info.type, mime, width: srcW, height: srcH,
        bytes: k.size, original, savingPct: savingPct(sourceBytes, k.size), quality: null, colors: null, similarity: 1,
        resized: false, cropped: false, kept: true, notes: orderNotes(keptNotes) }
    }
  }

  // Kayıpsız iddiası (benzerlik 1) yarı saydam piksellerde DOĞRU DEĞİL: tarayıcı çözerken renkleri önçarpımlı saklar,
  // düşük saydamlıktaki renk değerleri yuvarlanır (her zeminde görünüm aynı). Ölçülemez → benzerlik null + not.
  let similarityOut = final.similarity
  if (final.type === 'png' && final.similarity === 1 && sameDimensions && work.partialAlpha) {
    similarityOut = null
    notes.add('ALPHA_COLORS_ROUNDED')
  }
  if (info.orientation && info.orientation !== 1) notes.add('ORIENTATION_APPLIED')
  if (info.animated) notes.add('ANIMATION_DROPPED')
  if (info.icc) notes.add('COLOR_PROFILE_CONVERTED')
  const outMime = MIME[final.type]
  const outBlob = final.blob || new Blob([final.bytes], { type: outMime })
  return {
    blob: outBlob, type: final.type, mime: outMime, width: final.width, height: final.height, bytes: final.size, original,
    savingPct: savingPct(sourceBytes, final.size), quality: final.quality, colors: final.colors,
    similarity: similarityOut == null ? null : Math.round(similarityOut * 10000) / 10000,
    resized: final.width !== srcW || final.height !== srcH, cropped: !!plan.cropped, kept: false, notes: orderNotes(notes),
  }
}

/** Bu ortam ne yapabiliyor: çözme / tuval var mı, WebP kodlanabiliyor mu. */
export async function supportCore(platform) {
  let ok = false
  let webp = false
  try { ok = await platform.available() } catch { ok = false }
  if (ok) { try { webp = await platform.canEncode(MIME.webp) } catch { webp = false } }
  return { ok, webp }
}
