import { csvNumber, csvRows } from '../../../../utils/csv.js'
import { KB, MB, formatNumber, formatPct, formatSize, gradeLetter, problemText, scoreLabelKey, verdictOf } from '../resourceScanModel.js'
import { isEndpointUnsupported } from '../resourceScanReportModel.js'

/**
 * Kaynak analizi → GÖRSEL ARACI — saf mantık (2026-11-14; React yok). Sözleşme: scratchpad image-tool-contract.md.
 *
 *  - taramanın bulduğu bir görsel indirilebilir mi (yetki / maskeli adres / SVG / saklanmayan ayrıntı …) ve neden değil;
 *  - önerilen hedef genişlik (sunucunun `suggested_width` / `suggested_reason` alanı; yoksa istemcide yalnız
 *    "2000 pikselden geniş → 1920" kuralı);
 *  - ayar formu → motor seçenekleri, alan doğrulaması (hata ALANIN altında gösterilir);
 *  - dosya adları (ZIP içinde tekil), eşleşme listesi (CSV), eksik listesi, duyarlı set genişlikleri ve hazır
 *    `<img srcset>` / `<picture>` kodu;
 *  - sunucu ve motor hata kodlarının metinleri, "not ne olurdu?" projeksiyonunun görünüm modeli.
 *
 * Bilinmeyen / başarısız hiçbir şey 0 ya da "başarılı" gösterilmez; her döngü sınırlıdır.
 */

/** Araca bir seferde bırakılabilecek en çok dosya. */
export const TOOL_MAX_FILES = 30
/** Analizin "büyük görsel" eşiği (PageResourceAnalyzer — LARGE_IMAGES 200 KB); hedef boyut kipinin varsayılanı da bu. */
export const LARGE_IMAGE_BYTES = 200 * KB
export const DEFAULT_TARGET_KB = 200
/** `PageResourceAnalyzer.OVERSIZED_WIDTH` — bundan geniş görsele sunucu "1920 yeter" der. */
export const OVERSIZED_WIDTH = 2000
export const SUGGESTED_MAX_WIDTH = 1920
/** Duyarlı set genişlikleri (kullanıcı kararı 8). */
export const RESPONSIVE_WIDTHS = Object.freeze([480, 768, 1280, 1920])
/** Sunucunun tek görsel için gövde sınırı (sözleşme §1.1: 25 MiB → 413). */
export const SERVER_MAX_BYTES = 25 * MB
/** Toplu indirmede en çok bu kadar görsel (en büyükler önce) — sınırsız döngü yok. */
export const BULK_MAX_IMAGES = 100
/** Sunucu sınırı: kişi başına aynı anda en çok 2 görsel isteği (sözleşme §1.1). */
export const SERVER_CONCURRENCY = 2
/** Projeksiyon isteğinde en çok bu kadar görsel (sözleşme §1.2). */
export const PROJECTION_MAX_IMAGES = 200
export const MISSING_FILE = 'missing.txt'
const BOM = String.fromCharCode(0xfeff)

/** Araç ayarlarının seçenekleri (motorun dışa aktardığı listelerle AYNI — test motor listesini okur). */
export const FORMATS = Object.freeze(['keep', 'jpeg', 'png', 'webp', 'smallest'])
export const METHODS = Object.freeze(['none', 'scale', 'fit', 'cover', 'thumb'])
export const QUALITY_MODES = Object.freeze(['auto', 'manual', 'target'])
export const PHASES = Object.freeze(['decode', 'resize', 'encode', 'search'])
/** Motor not kodları (sözleşme §2 `NOTE_CODES`) — her biri TR + EN metin taşır (`prs.it.note.<KOD>`). */
export const NOTE_CODES = Object.freeze(['KEPT_ORIGINAL', 'NOT_UPSCALED', 'TRUECOLOR_KEPT', 'ALPHA_FLATTENED', 'WEBP_UNAVAILABLE',
  'TARGET_NOT_REACHED', 'METADATA_PRESERVED', 'METADATA_NOT_PRESERVED', 'ORIENTATION_APPLIED', 'ANIMATION_DROPPED', 'SMART_CROP',
  'THUMB_PADDED', 'COLOR_PROFILE_CONVERTED'])
/** Motor hata kodları (sözleşme §2 `ERROR_CODES`) — her biri TR + EN metin taşır (`prs.it.eng.<KOD>`). */
export const ENGINE_CODES = Object.freeze(['UNSUPPORTED_FORMAT', 'TOO_LARGE', 'TOO_MANY_PIXELS', 'DECODE_FAILED', 'BROWSER_UNSUPPORTED',
  'ENCODE_FAILED', 'CANCELLED', 'INVALID_OPTIONS'])
/** Sunucunun görsel / projeksiyon uçlarının hata kodları (sözleşme §1.1, §1.2) — her biri `prs.it.srv.<KOD>`. */
export const SERVER_CODES = Object.freeze(['RESOURCE_SCAN_NOT_FOUND', 'RESOURCE_SCAN_ITEMS_GONE', 'RESOURCE_IMAGE_NOT_FOUND',
  'RESOURCE_IMAGE_URL_MASKED', 'RESOURCE_IMAGE_TOO_LARGE', 'RESOURCE_IMAGE_FETCH_FAILED', 'RESOURCE_IMAGE_UNSUPPORTED',
  'RESOURCE_IMAGE_BUSY', 'RESOURCE_SCAN_NOT_DONE', 'RESOURCE_SCAN_NOT_PROJECTABLE', 'RATE_LIMITED', 'BLOCKED'])
/** Projeksiyonun yapılamama nedenleri (sunucu `reason`) → metin anahtarı (bilinmeyen neden → genel metin). */
const NOT_PROJECTABLE_KEYS = Object.freeze({
  algorithm: 'prs.it.srv.RESOURCE_SCAN_NOT_PROJECTABLE.algorithm',
  baseline: 'prs.it.srv.RESOURCE_SCAN_NOT_PROJECTABLE.baseline',
})
/** Projeksiyonda HİÇ yeniden hesaplanmayan bileşen: yükleme tahmini ölçülen sürelerden gelir, bayttan değil (sunucu). */
export const PROJECTION_FIXED_COMPONENTS = Object.freeze(['load_time'])
/** Sunucu kodu olmayan istemci durumları (`prs.it.srv.<kod>`). */
export const CLIENT_PROBLEMS = Object.freeze(['unsupported', 'session', 'forbidden', 'network', 'download', 'other'])
/** Tarama görselinin indirilememe nedenleri (`prs.it.na.<neden>`). */
export const UNAVAILABLE_REASONS = Object.freeze(['no_permission', 'items_gone', 'masked', 'svg', 'broken', 'too_large', 'no_url'])

const num = (v) => (v === null || v === undefined || v === '' ? null : (Number.isFinite(Number(v)) ? Number(v) : null))
const posInt = (v) => {
  const s = typeof v === 'number' ? String(v) : String(v ?? '').trim()
  if (!/^\d{1,9}$/.test(s)) return null
  const n = Number(s)
  return n > 0 ? n : null
}

// ── Tarama görseli: indirilebilir mi, önerilen genişlik ─────────────────────

/** Sunucu yalnız IMAGE kategorisindeki nesneyi verir (sözleşme §1.1). */
export const isImageItem = (item) => item?.category === 'IMAGE'

const isMasked = (url) => typeof url === 'string' && url.includes('*****')

/** SVG mi (içerik türü ya da uzantı) — sunucu vektörü işlemez (415), araç da piksel görseller içindir. */
export function isSvgItem(item) {
  const ct = String(item?.content_type || '').toLowerCase()
  if (ct.includes('svg')) return true
  const u = String(item?.url || '').slice(0, 2000)
  const cut = u.search(/[?#]/)
  const path = (cut >= 0 ? u.slice(0, cut) : u).toLowerCase()
  return path.endsWith('.svg') || path.endsWith('.svgz')
}

/**
 * Tarama görseli neden indirilemez: null (indirilebilir) ya da {@link UNAVAILABLE_REASONS} kodu. Sıra: yetki → nesne
 * ayrıntısı saklanmıyor → adres yok / maskeli → SVG → analizde açılmamış → sunucu sınırından büyük.
 */
export function imageUnavailable(item, { canRun = false, itemsKept = true } = {}) {
  if (!canRun) return 'no_permission'
  if (!itemsKept) return 'items_gone'
  if (typeof item?.url !== 'string' || !item.url) return 'no_url'
  if (isMasked(item.url)) return 'masked'
  if (isSvgItem(item)) return 'svg'
  if (verdictOf(item) !== 'ok') return 'broken'
  const b = num(item?.bytes)
  if (b !== null && b > SERVER_MAX_BYTES) return 'too_large'
  return null
}

/**
 * Önerilen hedef genişlik: `{ width, reason: 'declared'|'max_width', natural, declared, source: 'server'|'client' }` ya da
 * null. Sunucu alanı (`suggested_width` + `suggested_reason`) varsa o; yoksa (eski analiz / eski pod) istemcide YALNIZ
 * "doğal genişlik > 2000 → 1920" kuralı. Öneri hiçbir zaman doğal genişlikten büyük (ya da eşit) değildir.
 */
export function suggestedWidth(item) {
  const natural = num(item?.width)
  const sw = num(item?.suggested_width)
  const reason = item?.suggested_reason
  if (sw !== null && sw >= 1 && (reason === 'declared' || reason === 'max_width')) {
    if (natural !== null && sw >= natural) return null
    return { width: Math.round(sw), reason, natural, declared: num(item?.declared_width), source: 'server' }
  }
  if (natural !== null && natural > OVERSIZED_WIDTH) {
    return { width: SUGGESTED_MAX_WIDTH, reason: 'max_width', natural, declared: null, source: 'client' }
  }
  return null
}

/** Önerinin cümlesi: "3000 piksel geniş, sayfa 600 piksel bildiriyor → 1200 piksel yeter" / "2000 pikselden geniş → 1920 yeter". */
export function suggestionText(s, t, locale) {
  if (!s) return ''
  const n = (v) => formatNumber(v, locale)
  if (s.reason === 'declared' && s.natural !== null && s.declared !== null) {
    return t('prs.it.suggest.declared', n(s.natural), n(s.declared), n(s.width))
  }
  if (s.reason === 'declared') return t('prs.it.suggest.declaredShort', n(s.width))
  return s.natural !== null ? t('prs.it.suggest.maxWidth', n(s.natural), n(OVERSIZED_WIDTH), n(s.width)) : t('prs.it.suggest.maxWidthShort', n(s.width))
}

/**
 * Bir analizin "büyük görselleri" (LARGE_IMAGES önerisiyle AYNI küme): nesnenin bulgu listesi varsa (sunucunun yazdığı ya da
 * eski analizde aynı kuralla istemcinin hesapladığı — `withFindings`) LARGE_IMAGES taşıyanlar, bulgu listesi hiç yoksa
 * ≥ 200 KB IMAGE nesneleri; büyükten küçüğe, adres tekil.
 */
export function largeImages(items) {
  const list = (Array.isArray(items) ? items : []).filter(isImageItem)
  const picked = list.filter((it) => (Array.isArray(it.findings)
    ? it.findings.includes('LARGE_IMAGES')
    : (num(it.bytes) ?? 0) >= LARGE_IMAGE_BYTES))
  const seen = new Set()
  const out = []
  for (const it of picked) {
    if (typeof it.url !== 'string' || seen.has(it.url)) continue
    seen.add(it.url)
    out.push(it)
  }
  return out.sort((a, b) => (num(b.bytes) ?? 0) - (num(a.bytes) ?? 0))
}

/**
 * Toplu indirme planı: indirilebilen büyük görseller (en çok {@link BULK_MAX_IMAGES}), indirilemeyenler nedeniyle ayrı
 * (ZIP'teki `missing.txt`'ye yazılır — sessizce atlanmaz).
 * @returns {{ plan: object[], skipped: { item, reason }[], truncated: number }}
 */
export function bulkPlan(items, ctx) {
  const plan = []
  const skipped = []
  for (const it of largeImages(items)) {
    const why = imageUnavailable(it, ctx)
    if (why) skipped.push({ item: it, reason: why })
    else plan.push(it)
  }
  const truncated = Math.max(0, plan.length - BULK_MAX_IMAGES)
  return { plan: plan.slice(0, BULK_MAX_IMAGES), skipped, truncated }
}

// ── Ayarlar → motor seçenekleri ─────────────────────────────────────────────

export const DEFAULT_SETTINGS = Object.freeze({
  format: 'keep', qualityMode: 'auto', quality: 80, targetKb: String(DEFAULT_TARGET_KB),
  method: 'none', width: '', height: '', background: '#ffffff',
  keepCopyright: false, keepCreation: false, keepLocation: false,
})

/** Tarama görseli için hazır ayar: aynı boyutta optimize / verilen genişliğe küçült + optimize. */
export const optimizeSettings = (over = {}) => ({ ...DEFAULT_SETTINGS, ...over })
export const resizeSettings = (width, over = {}) => ({ ...DEFAULT_SETTINGS, method: 'scale', width: String(width), height: '', ...over })

const HEX = /^#[0-9a-f]{6}$/i

/**
 * Ayar formunun alan hataları: `{ alan: { key, args } }` ya da null. Yalnız etkin kipin alanları denetlenir (gizli alan
 * doğrulanmaz). `limits` motorun sınırları (`maxSide`, `maxInputBytes`).
 */
export function validateSettings(s, limits = {}) {
  const maxSide = num(limits.maxSide) ?? 30_000
  const maxKb = Math.floor((num(limits.maxInputBytes) ?? SERVER_MAX_BYTES) / KB)
  const errs = {}
  const side = (raw) => {
    const n = posInt(raw)
    return n !== null && n <= maxSide ? n : null
  }
  const filled = (v) => String(v ?? '').trim() !== ''
  if (s.method === 'scale') {
    const w = filled(s.width)
    const h = filled(s.height)
    if (!w && !h) errs.width = { key: 'prs.it.v.scaleOne', args: [] }
    else if (w && h) errs.height = { key: 'prs.it.v.scaleOnlyOne', args: [] }
    else if (w && side(s.width) === null) errs.width = { key: 'prs.it.v.side', args: [maxSide] }
    else if (h && side(s.height) === null) errs.height = { key: 'prs.it.v.side', args: [maxSide] }
  } else if (s.method === 'fit' || s.method === 'cover' || s.method === 'thumb') {
    if (side(s.width) === null) errs.width = { key: filled(s.width) ? 'prs.it.v.side' : 'prs.it.v.sideRequired', args: [maxSide] }
    if (side(s.height) === null) errs.height = { key: filled(s.height) ? 'prs.it.v.side' : 'prs.it.v.sideRequired', args: [maxSide] }
  }
  if (s.qualityMode === 'target') {
    const kb = posInt(s.targetKb)
    if (kb === null || kb > maxKb) errs.targetKb = { key: 'prs.it.v.targetKb', args: [maxKb] }
  }
  if (s.qualityMode === 'manual') {
    const q = Number(s.quality)
    if (!Number.isInteger(q) || q < 1 || q > 100) errs.quality = { key: 'prs.it.v.quality', args: [] }
  }
  if (!HEX.test(String(s.background || ''))) errs.background = { key: 'prs.it.v.background', args: [] }
  return Object.keys(errs).length ? errs : null
}

/** Geçerli ayar → motor seçenekleri (sözleşme §2 `processImage` `options`). Geçersiz ayarda null. */
export function buildOptions(s, limits = {}) {
  if (!s || !FORMATS.includes(s.format) || !METHODS.includes(s.method) || validateSettings(s, limits)) return null
  let resize = { method: 'none' }
  if (s.method === 'scale') {
    resize = String(s.width ?? '').trim() !== '' ? { method: 'scale', width: posInt(s.width) } : { method: 'scale', height: posInt(s.height) }
  } else if (s.method !== 'none') {
    resize = { method: s.method, width: posInt(s.width), height: posInt(s.height) }
  }
  return {
    resize,
    format: s.format,
    quality: s.qualityMode === 'manual' ? Number(s.quality) : 'auto',
    targetBytes: s.qualityMode === 'target' ? posInt(s.targetKb) * KB : null,
    background: String(s.background).toLowerCase(),
    preserve: { copyright: s.keepCopyright === true, creation: s.keepCreation === true, location: s.keepLocation === true },
  }
}

/** Seçeneklerin kararlı anahtarı (aynı ayarla ikinci tık = önbellekteki sonuç). */
export function optionsKey(o) {
  if (!o) return ''
  const r = o.resize || {}
  return [r.method, r.width ?? '', r.height ?? '', o.format, o.quality, o.targetBytes ?? '', o.background,
    o.preserve?.copyright ? 1 : 0, o.preserve?.creation ? 1 : 0, o.preserve?.location ? 1 : 0].join('|')
}

/** Ayarın kısa özeti (kuyruk satırındaki "kendi ayarı" çipi): "1200 piksel genişliğe küçült" … */
export function settingsSummary(s, t, locale) {
  if (!s) return ''
  const n = (v) => formatNumber(posInt(v), locale)
  if (s.method === 'scale') return String(s.width ?? '').trim() !== '' ? t('prs.it.sum.scaleW', n(s.width)) : t('prs.it.sum.scaleH', n(s.height))
  if (s.method === 'fit' || s.method === 'cover' || s.method === 'thumb') return t(`prs.it.sum.${s.method}`, n(s.width), n(s.height))
  return t('prs.it.sum.none')
}

// ── Türler, adlar ───────────────────────────────────────────────────────────

const TYPE_LABEL = { jpeg: 'JPEG', png: 'PNG', webp: 'WebP', gif: 'GIF', bmp: 'BMP', avif: 'AVIF' }
const TYPE_EXT = { jpeg: 'jpg', png: 'png', webp: 'webp', gif: 'gif', bmp: 'bmp', avif: 'avif' }
const TYPE_MIME = { jpeg: 'image/jpeg', png: 'image/png', webp: 'image/webp', gif: 'image/gif', bmp: 'image/bmp', avif: 'image/avif' }
export const typeLabel = (type) => TYPE_LABEL[String(type || '').toLowerCase()] || (type ? String(type).toUpperCase().slice(0, 12) : '—')
export const extOfType = (type) => TYPE_EXT[String(type || '').toLowerCase()] || 'img'
export const mimeOfType = (type) => TYPE_MIME[String(type || '').toLowerCase()] || 'application/octet-stream'

/** Dosya adı → `{ base, ext }` (uzantı küçük harf, noktasız; yoksa ''). */
export function splitName(name) {
  const s = String(name ?? '')
  const dot = s.lastIndexOf('.')
  if (dot <= 0 || dot === s.length - 1 || s.length - dot > 8) return { base: s, ext: '' }
  return { base: s.slice(0, dot), ext: s.slice(dot + 1).toLowerCase() }
}

/**
 * Güvenli dosya adı gövdesi: denetim karakteri, yol ayırıcı ve Windows'un yasak karakterleri atılır, baştaki nokta ve
 * uçtaki boşluk / nokta temizlenir, en çok 100 karakter; boş kalırsa `fallback`.
 */
export function safeFileBase(name, fallback = 'image') {
  // eslint-disable-next-line no-control-regex
  let s = String(name ?? '').slice(0, 400).replace(/[\u0000-\u001f\u007f]/g, '').replace(/[/\\:*?"<>|]/g, '-').replace(/^\.+/, '').trim()
  let end = s.length
  while (end > 0 && (s[end - 1] === '.' || s[end - 1] === ' ')) end -= 1
  s = s.slice(0, end)
  if (s.length > 100) s = s.slice(0, 100)
  return s || fallback
}

/** HTML'de kaçışsız kullanılabilen ad gövdesi (duyarlı set): yalnız a–z, 0–9, "-", "_"; Türkçe harfler sadeleşir. */
export function slugFileBase(name, fallback = 'image') {
  const folded = String(name ?? '').slice(0, 400).toLocaleLowerCase('tr')
    .replace(/ç/g, 'c').replace(/ğ/g, 'g').replace(/ı/g, 'i').replace(/ö/g, 'o').replace(/ş/g, 's').replace(/ü/g, 'u')
    .replace(/â/g, 'a').replace(/î/g, 'i').replace(/û/g, 'u')
  let out = ''
  let dash = false
  for (let i = 0; i < folded.length && out.length < 80; i += 1) {
    const ch = folded[i]
    if ((ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9') || ch === '_') { out += ch; dash = false } else if (!dash && out.length > 0) { out += '-'; dash = true }
  }
  let end = out.length
  while (end > 0 && out[end - 1] === '-') end -= 1
  return out.slice(0, end) || fallback
}

/** Adresin son yol parçası (çözülmüş, uzantısız, güvenli) — görselin dosya adı gövdesi. */
export function baseNameOfUrl(url) {
  let seg = ''
  try {
    const u = new URL(String(url))
    const parts = u.pathname.split('/').filter(Boolean)
    seg = parts.length ? parts[parts.length - 1] : u.hostname
  } catch { seg = '' }
  try { seg = decodeURIComponent(seg) } catch { /* bozuk kaçış — ham parça */ }
  return safeFileBase(splitName(seg).base)
}

/**
 * Çıktı dosyasının adı. `mode`: `replace` (ZIP — özgün ad, yeni uzantı: yerine koymaya hazır), `optimized`
 * ("ad-opt.jpg" — özgün dosyayla aynı klasöre inerken karışmasın), `width` ("ad-1200w.webp").
 */
export function outputName(base, type, { mode = 'replace', width = null } = {}) {
  const b = safeFileBase(base)
  const ext = extOfType(type)
  if (mode === 'width' && posInt(width) !== null) return `${b}-${posInt(width)}w.${ext}`
  if (mode === 'optimized') return `${b}-opt.${ext}`
  return `${b}.${ext}`
}

/** Aynı ad ikinci kez gelirse `ad-2.jpg`, `ad-3.jpg` … (büyük / küçük harf duyarsız; `used` = Set, güncellenir). */
export function uniqueName(name, used) {
  const key = (s) => s.toLocaleLowerCase('tr')
  if (!used.has(key(name))) { used.add(key(name)); return name }
  const { base, ext } = splitName(name)
  for (let i = 2; i < 10_000; i += 1) {
    const candidate = ext ? `${base}-${i}.${ext}` : `${base}-${i}`
    if (!used.has(key(candidate))) { used.add(key(candidate)); return candidate }
  }
  const last = ext ? `${base}-${Date.now()}.${ext}` : `${base}-${Date.now()}`
  used.add(key(last))
  return last
}

/** ZIP'in adı: `<ad>-<son ek>-<yyyyMMdd-HHmm>.zip`. */
export function zipFileName(base, suffix, now = new Date()) {
  const p = (n) => String(n).padStart(2, '0')
  const stamp = `${now.getFullYear()}${p(now.getMonth() + 1)}${p(now.getDate())}-${p(now.getHours())}${p(now.getMinutes())}`
  return `${slugFileBase(base, 'gorseller')}-${slugFileBase(suffix, 'images')}-${stamp}.zip`
}

// ── Sonuç özeti ─────────────────────────────────────────────────────────────

/** Motor sonucunun saklanan özeti (Blob'suz) — satır, eşleşme listesi, projeksiyon ve dışa aktarım bunu okur. */
export function resultSummary(r) {
  if (!r || typeof r !== 'object') return null
  const o = r.original || {}
  const notes = Array.isArray(r.notes) ? r.notes.filter((c) => typeof c === 'string').slice(0, NOTE_CODES.length + 8) : []
  return {
    bytes: num(r.bytes), width: num(r.width), height: num(r.height), type: r.type || null,
    savingPct: num(r.savingPct), kept: r.kept === true, resized: r.resized === true, cropped: r.cropped === true,
    quality: num(r.quality), colors: num(r.colors), similarity: num(r.similarity), notes,
    original: { bytes: num(o.bytes), width: num(o.width), height: num(o.height), type: o.type || null,
      hasAlpha: o.hasAlpha === true, animated: o.animated === true,
      metadata: { copyright: o.metadata?.copyright === true, creation: o.metadata?.creation === true, location: o.metadata?.location === true } },
  }
}

/** Kazanç yüzdesi (motorun değeri yoksa baytlardan; bilinmiyorsa null — 0 UYDURULMAZ). */
export function savingPctOf(sum) {
  if (!sum) return null
  if (sum.savingPct !== null) return sum.savingPct
  const a = sum.original?.bytes
  const b = sum.bytes
  if (a === null || a === undefined || b === null || a <= 0) return null
  return b >= a ? 0 : Math.floor(((a - b) / a) * 1000) / 10
}

/**
 * Yüzde metni: küçük değer SAHTE 0'a yuvarlanmaz — 1'in altı 2 ondalık, 10'un altı 1 ondalık; 0'dan büyük ama 0,01'in
 * altı "<%0,01". Bilinmeyen (null) → ''.
 */
export function pctText(p, locale) {
  const v = num(p)
  if (v === null) return ''
  if (v > 0 && v < 0.01) return `<${formatPct(0.01, locale, 2)}`
  // 99,6 → "%100" yazılmaz (gerçekte bir şey kaldı): 99'un üstü bir ondalıkla, aşağı yuvarlanarak (en çok %99,9)
  if (v > 99 && v < 100) return formatPct(Math.min(99.9, Math.floor(v * 10) / 10), locale, 1)
  return formatPct(v, locale, v < 1 ? 2 : v < 10 ? 1 : 0)
}

/**
 * Sonucun sınıfı: `kept` (motor özgünü korudu), `smaller`, `same` (aynı bayt), `larger` (açıkça istenen biçime
 * dönüşüm özgünden büyük çıktı — motor yine üretir, `savingPct` 0), `unknown` (özgün boyut bilinmiyor).
 */
export function resultTone(sum) {
  if (!sum || sum.bytes === null || sum.bytes === undefined) return 'unknown'
  if (sum.kept) return 'kept'
  const before = num(sum.original?.bytes)
  if (before === null || before <= 0) return 'unknown'
  if (sum.bytes > before) return 'larger'
  if (sum.bytes === before) return 'same'
  return 'smaller'
}

/** Büyüme yüzdesi (yalnız `larger` sonuçta; değilse null). */
export function growthPctOf(sum) {
  if (resultTone(sum) !== 'larger') return null
  const before = num(sum.original?.bytes)
  return Math.ceil(((sum.bytes - before) / before) * 1000) / 10
}

/** PNG çıktısında elle kalite → palet renk sayısı (motor kuralı: 100 = kayıpsız → null; altı round(256·(q/100)²), en az 8). */
export function pngPaletteColors(q) {
  const v = num(q)
  if (v === null || v >= 100 || v < 1) return null
  return Math.max(8, Math.round(256 * (v / 100) ** 2))
}

export const dimsText = (w, h) => (num(w) !== null && num(h) !== null ? `${num(w)}×${num(h)}` : '')

/**
 * Sonuç satırı: "1,6 MB → 400 KB (−%75)". Motor özgünü koruduysa "küçültülemedi — özgün dosya korundu"; açıkça istenen
 * biçime dönüşüm özgünden BÜYÜK çıktıysa "… (özgünden %12 büyük)"; aynı baytsa "kazanç yok" — hiçbiri "korundu" sayılmaz.
 */
export function resultLine(sum, t, locale) {
  if (!sum || sum.bytes === null) return ''
  const size = (x) => formatSize(x, { locale })
  const tone = resultTone(sum)
  if (tone === 'kept') return t('prs.it.res.kept', size(sum.bytes))
  if (tone === 'unknown') return size(sum.bytes)
  const before = num(sum.original?.bytes)
  if (tone === 'larger') return t('prs.it.res.larger', size(before), size(sum.bytes), pctText(growthPctOf(sum), locale))
  const pct = savingPctOf(sum)
  if (tone === 'same' || pct === null || pct <= 0) {
    // Motorun yüzdesi 0 ama bayt azaldıysa (yuvarlama) baytlardan hesaplanır — sahte "kazanç yok" yazılmaz
    const fromBytes = before > 0 && sum.bytes < before ? ((before - sum.bytes) / before) * 100 : 0
    if (tone === 'smaller' && fromBytes > 0) return t('prs.img.optimizedTo', size(before), size(sum.bytes), pctText(fromBytes, locale))
    return t('prs.it.res.noSaving', size(before), size(sum.bytes))
  }
  return t('prs.img.optimizedTo', size(before), size(sum.bytes), pctText(pct, locale))
}

/** Sonucun ikinci satırı: "3000×2000 → 1200×800 · WebP · kalite 78". */
export function resultMeta(sum, t, locale) {
  if (!sum) return ''
  const parts = []
  const a = dimsText(sum.original?.width, sum.original?.height)
  const b = dimsText(sum.width, sum.height)
  if (a && b && a !== b) parts.push(`${a} → ${b}`)
  else if (b) parts.push(b)
  const from = sum.original?.type
  if (sum.type && from && from !== sum.type) parts.push(`${typeLabel(from)} → ${typeLabel(sum.type)}`)
  else if (sum.type) parts.push(typeLabel(sum.type))
  if (sum.quality !== null && !sum.kept) parts.push(t('prs.it.res.quality', formatNumber(sum.quality, locale)))
  if (sum.colors !== null && !sum.kept) parts.push(t('prs.it.res.colors', formatNumber(sum.colors, locale)))
  return parts.join(' · ')
}

/** Benzerlik (0..1) → "%99,2" ya da null (ölçülmediyse — uydurulmaz). */
export function similarityText(sum, locale) {
  const s = sum?.similarity
  if (s === null || s === undefined || s < 0 || s > 1) return null
  return formatPct(Math.floor(s * 1000) / 10, locale, 1)
}

/** Not kodunun metni (bilinmeyen kod → genel not). */
export const noteText = (code, t) => t(NOTE_CODES.includes(code) ? `prs.it.note.${code}` : 'prs.it.note.other')
/** Aşama adı. */
export const phaseText = (phase, t) => t(PHASES.includes(phase) ? `prs.it.phase.${phase}` : 'prs.it.phase.other')

// ── Hata metinleri ──────────────────────────────────────────────────────────

/** Sunucu yanıtı → arayüz sorun kodu ({@link SERVER_CODES} ya da {@link CLIENT_PROBLEMS}). */
export function serverProblemCode(res) {
  const code = typeof res?.code === 'string' ? res.code : ''
  if (SERVER_CODES.includes(code)) return code
  if (code === 'DOWNLOAD_FAILED') return 'download'
  const st = Number(res?.status) || 0
  if (st === 401) return 'session'
  if (st === 403) return 'forbidden'
  if (st === 429) return 'RATE_LIMITED'
  if (st === 0) return 'network'
  // Eski pod (uç yok): 404 / 405 ya da tür uyuşmazlığı 400'ü — tek kural (resourceScanReportModel.isEndpointUnsupported)
  if (isEndpointUnsupported(res)) return 'unsupported'
  return 'other'
}

/**
 * Sunucu sorununun açıklayıcı metni (ne oldu · neden · ne yapmalı). Tanınmayan kodda sunucunun kendi iletisi (çevrilmiş
 * ve dostça — api/client), o da yoksa genel metin.
 */
export function serverProblemText(res, t, locale) {
  const code = serverProblemCode(res)
  if (code === 'other') return (typeof res?.error === 'string' && res.error.trim()) ? res.error : t('prs.it.srv.other')
  if (code === 'network') return (typeof res?.error === 'string' && res.error.trim()) ? res.error : t('prs.it.srv.network')
  if (code === 'RATE_LIMITED' || code === 'RESOURCE_IMAGE_BUSY') {
    const wait = num(res?.retryAfter) ?? num(res?.retry_after_seconds)
    return wait !== null && wait > 0 ? t(`prs.it.srv.${code}.wait`, formatNumber(Math.ceil(wait), locale)) : t(`prs.it.srv.${code}`)
  }
  if (code === 'RESOURCE_IMAGE_TOO_LARGE') {
    const max = formatSize(num(res?.max_bytes) ?? SERVER_MAX_BYTES, { locale })
    // `bytes` yalnız analizin kendi ölçtüğü boyut zaten sınırın üstündeyse gelir
    const size = num(res?.bytes)
    return size !== null && size > 0 ? t('prs.it.srv.RESOURCE_IMAGE_TOO_LARGE.size', formatSize(size, { locale }), max) : t('prs.it.srv.RESOURCE_IMAGE_TOO_LARGE', max)
  }
  if (code === 'RESOURCE_SCAN_NOT_PROJECTABLE') {
    const key = NOT_PROJECTABLE_KEYS[typeof res?.reason === 'string' ? res.reason : '']
    return t(key || 'prs.it.srv.RESOURCE_SCAN_NOT_PROJECTABLE')
  }
  if (code === 'RESOURCE_IMAGE_FETCH_FAILED') {
    const hs = num(res?.http_status)
    if (hs !== null && hs > 0) return t('prs.it.srv.RESOURCE_IMAGE_FETCH_FAILED.http', String(hs))
    const why = typeof res?.reason === 'string' && res.reason ? problemText(res.reason, t) : ''
    return why ? t('prs.it.srv.RESOURCE_IMAGE_FETCH_FAILED.reason', why) : t('prs.it.srv.RESOURCE_IMAGE_FETCH_FAILED')
  }
  return t(`prs.it.srv.${code}`)
}

/** Motorun algıladığı desteklenmeyen tür (`UNSUPPORTED_FORMAT.detected`) → kendi metni; `unknown` / tanınmayan → genel. */
const UNSUPPORTED_KEYS = Object.freeze({
  svg: 'prs.it.eng.UNSUPPORTED_FORMAT.svg', pdf: 'prs.it.eng.UNSUPPORTED_FORMAT.pdf', tiff: 'prs.it.eng.UNSUPPORTED_FORMAT.tiff',
  heic: 'prs.it.eng.UNSUPPORTED_FORMAT.heic', empty: 'prs.it.eng.UNSUPPORTED_FORMAT.empty',
})

/**
 * Motor hatasının metni (`code` ∈ {@link ENGINE_CODES}; bilinmeyen kod → genel metin). Motorun parametreleri
 * (`errors.js`) yardım ettiği yerde kullanılır: algılanan tür (SVG / PDF / TIFF / HEIC / boş), dosya boyutu ve sınır,
 * piksel ölçüsü ve sınırlar, zaman aşımı / bellek / üretilemeyen biçim. Ham neden kodu (`reason`) hiçbir zaman yazılmaz.
 */
export function engineProblemText(code, params, t, locale) {
  const p = params && typeof params === 'object' ? params : {}
  if (code === 'UNSUPPORTED_FORMAT') return t(UNSUPPORTED_KEYS[typeof p.detected === 'string' ? p.detected : ''] || 'prs.it.eng.UNSUPPORTED_FORMAT')
  if (code === 'TOO_LARGE') {
    const max = formatSize(num(p.maxBytes) ?? num(p.max_bytes) ?? SERVER_MAX_BYTES, { locale })
    const size = num(p.bytes)
    return size !== null && size > 0 ? t('prs.it.eng.TOO_LARGE.size', formatSize(size, { locale }), max) : t('prs.it.eng.TOO_LARGE', max)
  }
  if (code === 'TOO_MANY_PIXELS') {
    const mp = formatNumber(Math.round((num(p.maxPixels) ?? num(p.max_pixels) ?? 50_000_000) / 1_000_000), locale)
    const w = num(p.width)
    const h = num(p.height)
    const side = num(p.maxSide)
    if (w !== null && h !== null && side !== null) {
      return t('prs.it.eng.TOO_MANY_PIXELS.dims', formatNumber(w, locale), formatNumber(h, locale), mp, formatNumber(side, locale))
    }
    return t('prs.it.eng.TOO_MANY_PIXELS', mp)
  }
  const reason = typeof p.reason === 'string' ? p.reason : ''
  if (code === 'DECODE_FAILED' && reason === 'timeout') return t('prs.it.eng.DECODE_FAILED.timeout')
  if (code === 'BROWSER_UNSUPPORTED' && reason === 'memory') return t('prs.it.eng.BROWSER_UNSUPPORTED.memory')
  if (code === 'ENCODE_FAILED' && reason === 'timeout') return t('prs.it.eng.ENCODE_FAILED.timeout')
  if (code === 'ENCODE_FAILED' && reason === 'unsupported_type') return t('prs.it.eng.ENCODE_FAILED.type')
  return t(ENGINE_CODES.includes(code) ? `prs.it.eng.${code}` : 'prs.it.eng.other')
}

/**
 * Saklanan sorun kaydının metni. Kayıt: `{ kind: 'server', res }`, `{ kind: 'engine', code, params }`,
 * `{ kind: 'unavailable', reason }` ya da `{ kind: 'client', code }`.
 */
export function problemLine(problem, t, locale) {
  if (!problem) return ''
  if (problem.kind === 'server') return serverProblemText(problem.res, t, locale)
  if (problem.kind === 'engine') return engineProblemText(problem.code, problem.params, t, locale)
  if (problem.kind === 'unavailable') return t(`prs.it.na.${UNAVAILABLE_REASONS.includes(problem.reason) ? problem.reason : 'broken'}`)
  if (problem.kind === 'client' && problem.code === 'download') return t('prs.it.srv.download')
  if (problem.kind === 'client' && problem.code === 'tooLargeFile') return engineProblemText('TOO_LARGE', problem.params, t, locale)
  return t('prs.it.srv.other')
}

// ── Eşleşme listesi (CSV) ve eksik listesi ──────────────────────────────────

/**
 * Eşleşme listesinin satırları: eski adres (ya da yüklenen dosyanın adı) → yeni dosya adı, eski / yeni bayt, eski / yeni
 * ölçü, biçim, kazanç, notlar. Sayılar gerçek sayı hücresi (Excel hesaplayabilsin); metin hücreleri formül korumalı.
 * @param {{ source: string, name: string, summary: object }[]} files
 */
export function mappingRows(files, t) {
  const head = [t('prs.it.map.source'), t('prs.it.map.file'), t('prs.it.map.oldBytes'), t('prs.it.map.newBytes'), t('prs.it.map.oldDims'),
    t('prs.it.map.newDims'), t('prs.it.map.format'), t('prs.it.map.saving'), t('prs.it.map.notes')]
  const rows = (files || []).map((f) => {
    const s = f.summary || {}
    const pct = savingPctOf(s)
    return [
      f.source ?? '', f.name ?? '', csvNumber(s.original?.bytes ?? null), csvNumber(s.bytes ?? null),
      dimsText(s.original?.width, s.original?.height), dimsText(s.width, s.height), s.type ? typeLabel(s.type) : '',
      csvNumber(pct), (s.notes || []).map((c) => noteText(c, t)).join(' | '),
    ]
  })
  return [head, ...rows]
}

/** Eşleşme listesi CSV metni (BOM'lu UTF-8, CRLF — Excel Türkçe karakterleri doğru açsın). */
export const mappingCsv = (files, t) => `${BOM}${csvRows(mappingRows(files, t))}\r\n`

/**
 * `missing.txt`: alınamayan / işlenemeyen görseller ve nedenleri (arayüz dilinde). Satır sonu CRLF, başta BOM.
 * @param {{ source: string, text: string }[]} missing
 */
export function missingText(missing, t) {
  const lines = [t('prs.it.zip.missingHeading'), '']
  for (const m of missing || []) lines.push(`- ${m.source} — ${m.text}`)
  lines.push('', t('prs.it.zip.missingAdvice'))
  return `${BOM}${lines.join('\r\n')}\r\n`
}

// ── Duyarlı set ─────────────────────────────────────────────────────────────

/** Doğal genişliğe göre üretilecek genişlikler: 480 / 768 / 1280 / 1920'den ≤ doğal olanlar; hepsi büyükse yalnız doğal genişlik. */
export function responsiveWidths(naturalWidth) {
  const n = posInt(naturalWidth)
  if (n === null) return []
  const list = RESPONSIVE_WIDTHS.filter((w) => w <= n)
  return list.length ? list : [n]
}

/** HTML öznitelik değeri kaçışı. */
export function escapeAttr(v) {
  return String(v ?? '').replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
}

const srcsetOf = (files, prefix) => files.map((f) => `${prefix}${encodeURI(f.name)} ${f.width}w`).join(', ')

/**
 * Hazır HTML: `kind` = `img` (`<img srcset sizes>`) ya da `picture` (`<picture><source type srcset sizes><img></picture>` —
 * yedek `<img>` özgün dosyayı gösterir). Dosya adları ZIP'tekiyle AYNI; değerler kaçışlı.
 * @param {{ files: { name: string, width: number, height?: number, type: string }[], alt: string, sizes: string,
 *           kind?: 'img'|'picture', prefix?: string, fallbackName?: string }} p
 */
export function pictureSnippet({ files, alt = '', sizes = '100vw', kind = 'img', prefix = '', fallbackName = '' }) {
  const list = (Array.isArray(files) ? files : []).filter((f) => f && f.name && posInt(f.width) !== null)
    .sort((a, b) => a.width - b.width)
  if (!list.length) return ''
  const largest = list[list.length - 1]
  const pre = String(prefix ?? '')
  const srcset = escapeAttr(srcsetOf(list, pre))
  const sz = escapeAttr(String(sizes ?? '').trim() || '100vw')
  const dims = posInt(largest.height) !== null ? ` width="${largest.width}" height="${posInt(largest.height)}"` : ''
  if (kind === 'picture') {
    const fb = escapeAttr(`${pre}${encodeURI(fallbackName || largest.name)}`)
    return [
      '<picture>',
      `  <source type="${escapeAttr(mimeOfType(largest.type))}" srcset="${srcset}" sizes="${sz}">`,
      `  <img src="${fb}" alt="${escapeAttr(alt)}"${dims} loading="lazy" decoding="async">`,
      '</picture>',
    ].join('\n')
  }
  return `<img src="${escapeAttr(`${pre}${encodeURI(largest.name)}`)}" srcset="${srcset}" sizes="${sz}" alt="${escapeAttr(alt)}"${dims} loading="lazy" decoding="async">`
}

// ── "Not ne olurdu?" projeksiyonu ───────────────────────────────────────────

/** Projeksiyon isteğinin `images` listesi: bu sekmede işlenen görsellerin gerçek sonuçları (≤ 200, geçerli olanlar). */
export function projectionImages(processed) {
  const out = []
  for (const p of processed || []) {
    const s = p?.summary
    if (typeof p?.url !== 'string' || !s || s.bytes === null || s.bytes < 0) continue
    const row = { url: p.url, bytes: s.bytes }
    if (s.width !== null) row.width = s.width
    if (s.height !== null) row.height = s.height
    if (s.type) row.type = s.type
    out.push(row)
    if (out.length >= PROJECTION_MAX_IMAGES) break
  }
  return out
}

const side = (x) => {
  const g = num(x?.grade)
  return {
    grade: g, letter: gradeLetter(g, x?.letter), pageBytes: num(x?.page_bytes), imageBytes: num(x?.image_bytes), loadMs: num(x?.load_ms),
    largeImages: num(x?.large_images), scores: x?.scores && typeof x.scores === 'object' && !Array.isArray(x.scores) ? x.scores : {},
  }
}

/**
 * Projeksiyon yanıtının görünüm modeli. Bilinmeyen değer null kalır (ekranda "—", asla yeşil); `capped` → sonuç KISMİ
 * (alt sınır) — kesin gibi sunulmaz. Bileşen satırları: önce değişenler (kazanılan puan büyükten küçüğe).
 */
export function projectionView(data) {
  if (!data || typeof data !== 'object' || !data.before || !data.after) return null
  const before = side(data.before)
  const after = side(data.after)
  const keys = [...new Set([...Object.keys(before.scores), ...Object.keys(after.scores)])].slice(0, 40)
  // Sunucunun iki tarafta da SAKLI puanını kullandığı bileşenler (ör. render_blocking — saklanmayan HTML gerekir)
  const reused = Array.isArray(data.reused_components)
    ? [...new Set(data.reused_components.filter((k) => typeof k === 'string' && /^[a-z0-9_]{1,40}$/.test(k)))].slice(0, 10)
    : []
  const fixedSet = new Set([...PROJECTION_FIXED_COMPONENTS, ...reused])
  const rows = keys.map((key) => {
    const b = before.scores[key] || {}
    const a = after.scores[key] || {}
    const sb = num(b.score)
    const sa = num(a.score)
    const lb = num(b.lost)
    const la = num(a.lost)
    const gained = lb !== null && la !== null ? Math.round((lb - la) * 10) / 10 : null
    const fixed = fixedSet.has(key)
    return { key, labelKey: scoreLabelKey(key), before: sb, after: sa, lostBefore: lb, lostAfter: la, gained: fixed ? null : gained, fixed,
      reused: reused.includes(key),
      changed: !fixed && ((sb !== null && sa !== null && sb !== sa) || (gained !== null && gained !== 0)) }
  }).sort((x, y) => (Number(y.changed) - Number(x.changed)) || ((y.gained ?? 0) - (x.gained ?? 0)))
  const diff = (a, b) => (a !== null && b !== null ? a - b : null)
  return {
    capped: data.capped === true,
    applied: num(data.applied), ignored: num(data.ignored), algorithmVersion: num(data.algorithm_version),
    reused, reusedLabelKeys: reused.map((k) => scoreLabelKey(k)),
    before, after,
    gradeDiff: diff(after.grade, before.grade),
    pageBytesDiff: diff(after.pageBytes, before.pageBytes),
    imageBytesDiff: diff(after.imageBytes, before.imageBytes),
    loadMsDiff: diff(after.loadMs, before.loadMs),
    rows,
    changedCount: rows.filter((r) => r.changed).length,
  }
}

// ── Dışa aktarım (Excel / PDF görsel sayfası) ───────────────────────────────

/**
 * Bu sekmede işlenen görsellerin dışa aktarım hücreleri: [ölçülen optimize boyut (bayt), kazanç %, yeni ölçü, biçim].
 * İşlenmemiş görselde hücreler BOŞ (asla 0).
 */
export function processedCells(summary, locale) {
  if (!summary || summary.bytes === null) return ['', '', '', '']
  const pct = savingPctOf(summary)
  return [summary.bytes, pct === null ? '' : formatPct(pct, locale, 1), dimsText(summary.width, summary.height), summary.type ? typeLabel(summary.type) : '']
}
