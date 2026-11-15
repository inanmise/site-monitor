// Görsel aracı derin karşılaştırma (2026-11-15) — SAF model: motorun `analysis` alanından önce → sonra satırları, biçim
// kazanımları, öneriler ve "nasıl yaptık" adımları. Bilinmeyen değer null / "—" (asla uydurulmuş 0); metinler çağıranın
// `t`'siyle (kodlar `prs.it.ins.*`).
import { formatNumber, formatSize } from '../resourceScanModel.js'

const num = (v) => (v === null || v === undefined || v === '' ? null : (Number.isFinite(Number(v)) ? Number(v) : null))
const isObj = (v) => v !== null && typeof v === 'object' && !Array.isArray(v)

/** Öneri kodları (sıra = ekrandaki sıra). */
export const INSIGHT_RECS = Object.freeze(['NEXT_GEN', 'SVG', 'BANNER_WIDTH', 'CONTENT_WIDTH', 'QUALITY_HIGH', 'JPEG_RESAVE', 'BANDING'])
/** "Nasıl yaptık" adım kodları. */
export const HOW_STEPS = Object.freeze(['LOCAL', 'KEPT', 'CROPPED', 'RESIZED', 'QUALITY_SEARCH', 'BROWSER_ENCODER', 'PNG_PALETTE',
  'PNG_LOSSLESS', 'METADATA', 'COLOR_PROFILE'])
/** Afiş / içerik görseli genişlik eşikleri (px). */
export const BANNER_MAX_WIDTH = 1920
export const CONTENT_MAX_WIDTH = 1200
/** Kayıplı kalitede "dengeli" aralığın üstü. */
export const QUALITY_BALANCED_MAX = 85

const COLOR_MODELS = ['gray', 'gray-alpha', 'rgb', 'rgba', 'palette', 'cmyk']
const SUBSAMPLINGS = ['4:2:0', '4:2:2', '4:4:4']

/** Motor sonucundaki `analysis`'in saklanan, sınırlı kopyası (yalnız bilinen alanlar; aday listesi ≤ 5). */
export function analysisSummary(a) {
  if (!isObj(a)) return null
  const facts = (f) => {
    if (!isObj(f)) return null
    const ppi = isObj(f.ppi) && num(f.ppi.x) !== null && num(f.ppi.y) !== null ? { x: num(f.ppi.x), y: num(f.ppi.y) } : null
    const ppiChecked = f.ppiChecked === true || ppi !== null
    const jpeg = isObj(f.jpeg) ? {
      quality: num(f.jpeg.quality),
      subsampling: SUBSAMPLINGS.includes(f.jpeg.subsampling) ? f.jpeg.subsampling : null,
      progressive: typeof f.jpeg.progressive === 'boolean' ? f.jpeg.progressive : null,
    } : null
    return {
      bitDepth: num(f.bitDepth), colorModel: COLOR_MODELS.includes(f.colorModel) ? f.colorModel : null, ppi, ppiChecked, jpeg,
      paletteSize: num(f.paletteSize), lossless: typeof f.lossless === 'boolean' ? f.lossless : null,
    }
  }
  const candidates = (Array.isArray(a.candidates) ? a.candidates : []).filter(isObj).slice(0, 5)
    .filter((c) => ['jpeg', 'png', 'webp', 'avif'].includes(c.type) && num(c.bytes) !== null)
    .map((c) => ({ type: c.type, bytes: num(c.bytes), quality: num(c.quality), colors: num(c.colors), similarity: num(c.similarity),
      belowTarget: c.belowTarget === true }))
  const colors = isObj(a.colors) && num(a.colors.count) !== null ? { count: num(a.colors.count), atLeast: a.colors.atLeast === true } : null
  return {
    input: facts(a.input), output: facts(a.output), colors, candidates,
    svgCandidate: a.svgCandidate === true, banding: a.banding === 'bit_depth' || a.banding === 'palette' ? a.banding : null,
    qualityMode: ['auto', 'manual', 'target'].includes(a.qualityMode) ? a.qualityMode : null,
    resampled: typeof a.resampled === 'boolean' ? a.resampled : null,
    sharpened: typeof a.sharpened === 'boolean' ? a.sharpened : null,
  }
}

function gcd(a, b) {
  let x = Math.abs(a)
  let y = Math.abs(b)
  let guard = 0
  while (y && guard < 64) { [x, y] = [y, x % y]; guard += 1 }
  return x || 1
}

/** En-boy oranı metni: tam oran küçükse "16:9", değilse "1,78:1". */
export function aspectText(w, h, locale) {
  const W = num(w)
  const H = num(h)
  if (!W || !H) return '—'
  const g = gcd(Math.round(W), Math.round(H))
  const a = Math.round(W) / g
  const b = Math.round(H) / g
  if (a <= 32 && b <= 32) return `${a}:${b}`
  return `${formatNumber(W / H, locale, 2)}:1`
}

const sameAspect = (w1, h1, w2, h2) => num(w1) && num(h1) && num(w2) && num(h2) && Math.abs(w1 / h1 - w2 / h2) < 0.01

/** Piksel başına bit (dosya bitleri / piksel); bilinmiyorsa null. */
export function bitsPerPixel(bytes, w, h) {
  const b = num(bytes)
  const p = num(w) * num(h)
  if (b === null || !(p > 0)) return null
  return (b * 8) / p
}

/** Sıkıştırma oranı: ham (sıkıştırılmamış) boyut / dosya boyutu; ham = piksel × kanal (saydamsa 4, değilse 3). */
export function compressionRatio(bytes, w, h, alpha) {
  const b = num(bytes)
  const p = num(w) * num(h)
  if (b === null || b <= 0 || !(p > 0)) return null
  return (p * (alpha ? 4 : 3)) / b
}

function colorText(f, t, locale) {
  if (!f || !f.colorModel) return null
  const model = t(`prs.it.ins.model.${f.colorModel}`)
  if (f.colorModel === 'palette' && f.paletteSize) return t('prs.it.ins.paletteOf', model, formatNumber(f.paletteSize, locale))
  return f.bitDepth ? t('prs.it.ins.modelBits', model, formatNumber(f.bitDepth, locale)) : model
}

const ppiText = (f, t, locale) => (f?.ppi
  ? (f.ppi.x === f.ppi.y ? t('prs.it.ins.ppi', formatNumber(f.ppi.x, locale, 1)) : t('prs.it.ins.ppiXY', formatNumber(f.ppi.x, locale, 1), formatNumber(f.ppi.y, locale, 1)))
  : null)

/**
 * Önce → sonra satırları: `[{ key, label, before, after, changed }]`. Değer yoksa "—" (ppi: "Kayıtlı değil"). `changed`
 * yalnız iki taraf da biliniyorsa.
 */
export function insightRows(sum, t, locale) {
  if (!sum) return []
  const a = sum.analysis
  const o = sum.original || {}
  const dash = '—'
  const rows = []
  const push = (key, before, after, changed = null) => rows.push({ key, label: t(`prs.it.ins.row.${key}`), before: before ?? dash, after: after ?? dash,
    changed: changed === null ? (before != null && after != null ? before !== after : null) : changed })
  push('size', o.bytes == null ? null : formatSize(o.bytes, { locale }), sum.bytes == null ? null : formatSize(sum.bytes, { locale }))
  push('dims', o.width && o.height ? `${formatNumber(o.width, locale)} × ${formatNumber(o.height, locale)}` : null,
    sum.width && sum.height ? `${formatNumber(sum.width, locale)} × ${formatNumber(sum.height, locale)}` : null)
  const aspectChanged = num(o.width) && num(sum.width) ? !sameAspect(o.width, o.height, sum.width, sum.height) : null
  push('aspect', aspectText(o.width, o.height, locale), aspectText(sum.width, sum.height, locale), aspectChanged)
  const mp = (w, h) => (num(w) && num(h) ? t('prs.it.ins.mp', formatNumber((w * h) / 1e6, locale, 2)) : null)
  push('pixels', mp(o.width, o.height), mp(sum.width, sum.height))
  const notStored = t('prs.it.ins.notStored')
  // "Kayıtlı değil" yalnız yoğunluğun yazıldığı başlık okunduysa; okunmadıysa (AVIF, yalnız EXIF'te, tarama sınırı) "—"
  const ppiOf = (f) => (f ? (ppiText(f, t, locale) || (f.ppiChecked ? notStored : null)) : null)
  push('ppi', ppiOf(a?.input), ppiOf(a?.output))
  const bppB = bitsPerPixel(o.bytes, o.width, o.height)
  const bppA = bitsPerPixel(sum.bytes, sum.width, sum.height)
  push('bpp', bppB === null ? null : formatNumber(bppB, locale, 2), bppA === null ? null : formatNumber(bppA, locale, 2))
  // İki taraf için TEK taban (kaynağın saydamlığına göre 3 ya da 4 kanal) — oran farkı tabandan gelmesin
  const crB = compressionRatio(o.bytes, o.width, o.height, o.hasAlpha)
  const crA = compressionRatio(sum.bytes, sum.width, sum.height, o.hasAlpha)
  push('compression', crB === null ? null : `1:${formatNumber(crB, locale, 1)}`, crA === null ? null : `1:${formatNumber(crA, locale, 1)}`)
  push('color', colorText(a?.input, t, locale), sum.kept ? colorText(a?.input, t, locale) : colorText(a?.output, t, locale)
    ?? (sum.colors ? t('prs.it.ins.paletteOf', t('prs.it.ins.model.palette'), formatNumber(sum.colors, locale)) : null))
  const qIn = a?.input?.jpeg?.quality
  const qOut = sum.kept ? qIn : (sum.quality ?? a?.output?.jpeg?.quality ?? null)
  if (qIn != null || (qOut != null && !sum.kept)) {
    push('quality', qIn != null ? t('prs.it.ins.qualityEst', formatNumber(qIn, locale)) : null,
      qOut != null ? (sum.quality != null && !sum.kept ? formatNumber(qOut, locale) : t('prs.it.ins.qualityEst', formatNumber(qOut, locale))) : null)
  }
  const ssIn = a?.input?.jpeg?.subsampling
  const ssOut = sum.kept ? ssIn : a?.output?.jpeg?.subsampling
  if (ssIn || ssOut) push('subsampling', ssIn || null, ssOut || null)
  return rows
}

/**
 * Biçim kazanımları: denenen her biçim (boyut, özgüne göre kazanç, seçilen mi), boyuta göre artan. Tek aday ya da
 * özgün boyut bilinmiyorsa boş.
 */
export function formatGains(sum) {
  const c = sum?.analysis?.candidates
  const before = num(sum?.original?.bytes)
  if (!Array.isArray(c) || c.length < 2 || before === null || before <= 0) return []
  // Seçilen satır SONUCUN boyutu / kalitesiyle (hedef boyut, eklenen üstveri sonrası); hedefin altındaki aday işaretli
  return [...c].map((x) => {
    const chosen = !sum.kept && x.type === sum.type
    const bytes = chosen && num(sum.bytes) !== null ? sum.bytes : x.bytes
    return {
      type: x.type, bytes, quality: chosen && sum.quality != null ? sum.quality : x.quality, colors: x.colors,
      pct: bytes < before ? Math.floor(((before - bytes) / before) * 1000) / 10 : 0,
      larger: bytes > before, chosen, belowTarget: !chosen && x.belowTarget === true,
    }
  }).sort((x, y) => x.bytes - y.bytes)
}

/** Öneriler: `[{ code, params }]` (INSIGHT_RECS sırasıyla). */
export function insightRecommendations(sum) {
  if (!sum) return []
  const a = sum.analysis
  const out = []
  const w = num(sum.width)
  const h = num(sum.height)
  const type = sum.kept ? sum.original?.type : sum.type
  const tried = new Set((a?.candidates || []).map((c) => c.type))
  const notes = new Set(sum.notes || [])
  // Tarayıcı WebP kodlayamıyorsa öneri bu tarayıcıda uygulanamaz — gösterilmez
  if ((type === 'jpeg' || type === 'png') && !tried.has('webp') && !(a?.svgCandidate) && !notes.has('WEBP_UNAVAILABLE')) {
    out.push({ code: 'NEXT_GEN', params: [] })
  }
  if (a?.svgCandidate) out.push({ code: 'SVG', params: [] })
  if (w !== null && w > BANNER_MAX_WIDTH) out.push({ code: 'BANNER_WIDTH', params: [w, BANNER_MAX_WIDTH] })
  else if (w !== null && h !== null && w > CONTENT_MAX_WIDTH && w / h < 2.2) out.push({ code: 'CONTENT_WIDTH', params: [w] })
  // Yalnız kullanıcının verdiği kalitede: otomatik kip 85'in üstünü benzerlik hedefi için seçmiştir (öneri çelişirdi)
  const q = sum.kept ? null : num(sum.quality)
  if (q !== null && q > QUALITY_BALANCED_MAX && type !== 'png' && a?.qualityMode === 'manual') out.push({ code: 'QUALITY_HIGH', params: [q] })
  if (sum.original?.type === 'jpeg' && type === 'jpeg' && !sum.kept) out.push({ code: 'JPEG_RESAVE', params: [a?.input?.jpeg?.quality ?? null] })
  if (a?.banding) out.push({ code: 'BANDING', params: [a.banding] })
  return out
}

/** "Nasıl yaptık": sonucun gerçekten geçtiği adımlar (kod + parametreler). */
export function howWeDid(sum) {
  if (!sum) return []
  const notes = new Set(sum.notes || [])
  const out = [{ code: 'LOCAL', params: [] }]
  if (sum.kept) {
    out.push({ code: 'KEPT', params: [] })
  } else {
    const a = sum.analysis
    if (sum.cropped) out.push({ code: 'CROPPED', params: [] })
    // Yeniden örnekleme: eski sonuçta bayrak yok → boyut değiştiyse varsayılır; netleştirme yalnız biliniyorsa söylenir
    const resampled = a?.resampled ?? (sum.resized && !sum.cropped)
    if (resampled) out.push({ code: 'RESIZED', params: [sum.width, sum.height, a?.sharpened === true] })
    if (sum.type === 'jpeg' || sum.type === 'webp') {
      out.push({ code: 'QUALITY_SEARCH', params: [sum.quality, a?.qualityMode ?? null] })
      out.push({ code: 'BROWSER_ENCODER', params: [sum.type === 'jpeg' ? (sum.analysis?.output?.jpeg?.subsampling ?? null) : null, sum.type] })
    } else if (sum.type === 'png') {
      // "Piksel verisi değişmedi" yalnız ölçü / kırpma / saydam renk yuvarlaması / profil dönüşümü yoksa
      const untouched = !sum.resized && !sum.cropped && !notes.has('ALPHA_COLORS_ROUNDED') && !notes.has('COLOR_PROFILE_CONVERTED')
      out.push(sum.colors ? { code: 'PNG_PALETTE', params: [sum.colors] } : { code: 'PNG_LOSSLESS', params: [untouched] })
    }
  }
  if (sum.kept) {
    // Korunan özgün: yalnız motor gerçekten bir şey sildiyse (kayıpsız temizlik) ya da seçilen üstveriyi koruduysa söylenir
    if (notes.has('METADATA_STRIPPED')) out.push({ code: 'METADATA', params: ['removed'] })
    else if (notes.has('METADATA_PRESERVED')) out.push({ code: 'METADATA', params: ['kept'] })
  } else {
    out.push({ code: 'METADATA', params: [notes.has('METADATA_PRESERVED') ? 'kept' : 'stripped'] })
  }
  if (notes.has('COLOR_PROFILE_CONVERTED')) out.push({ code: 'COLOR_PROFILE', params: [] })
  return out
}
