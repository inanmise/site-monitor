import { toUtc } from '../../../utils/localDay.js'
import { foldSearchText, matchesSearchText } from '../../../utils/monitorFilters.js'
import { CHECK_FAILURE_SET } from '../../checks/checkFailureCodes.js'

/**
 * Sayfa bütünlüğü → "Kaynak analizi" (2026-10-31, kullanıcı isteği: "sayfanın tüm nesnelerini tara, kategorize et, boyut ve
 * indirme süresiyle büyükten küçüğe sırala, süz, Excel / PDF ver; puan, yüklenme süresi, sayfa boyutu, geçmiş grafiği,
 * öneriler, büyük görsellerin hıza etkisi"). Bu dosya SAF mantıktır (React yok): boyut kovaları, kategori sırası, süzgeç,
 * sıralama, biçimleme (1024 tabanlı bayt, süre, yüzde), önceki koşuya göre fark, grafik noktaları ve dışa aktarım satırları.
 * Ekran bileşenleri (ResourceScanTab / Dashboard / Trend / Objects / History) ve dışa aktarım modülü yalnız bunu kullanır.
 *
 * Sözleşme (sunucu): GET /api/monitoring/page/{id}/resource-scans[/{scanId}], POST …/resource-scans. Belge ve bağlantılar
 * ölçülür ama sayfa boyutuna, istek sayısına ve puana SAYILMAZ (`page_load=false`). Yüklenme süresi TAHMİNDİR (6 paralel
 * bağlantı, sunucu tarafı indirme; JavaScript'in sonradan eklediği nesneler görülmez).
 */

export const KB = 1024
export const MB = 1024 * 1024

/** Koşu izlenirken ilerleme sorgusu aralığı (ms) — sunucu sözleşmesi "~1,5 sn". */
export const POLL_MS = 1500
/** Art arda bu kadar başarısız ilerleme okuması → izleme durur, "tekrar dene" gösterilir (sonsuz deneme yok). */
export const POLL_MAX_FAILURES = 5
/** İstemci tavanı: sunucunun koşu sınırı (max_seconds) + pay — bunu aşan bir koşu hâlâ RUNNING ise izleme durur. */
export const POLL_GRACE_MS = 120_000
export const POLL_MIN_CAP_MS = 10 * 60_000

/** Kategoriler — sunucunun SABİT sırası. DOCUMENT ve LINK sayfa yüküne sayılmaz. */
export const CATEGORIES = ['HTML', 'CSS', 'JS', 'IMAGE', 'FONT', 'MEDIA', 'IFRAME', 'FAVICON', 'DOCUMENT', 'LINK', 'OTHER']
export const NOT_PAGE_LOAD = new Set(['DOCUMENT', 'LINK'])
/** Metin türleri — sıkıştırılmamış gelmeleri bir bulgu (diğer türlerde sıkıştırma beklenmez). */
export const TEXT_CATEGORIES = new Set(['HTML', 'CSS', 'JS'])

/** Puan bileşenleri (sunucu `analysis.scores[].key`) — ekrandaki sıra. */
export const SCORE_KEYS = ['load_time', 'page_weight', 'requests', 'server_response', 'images', 'compression', 'caching', 'errors']

/** Puan bileşeni → i18n anahtarı (`errors` bileşeni "açılmayan nesneler" diye adlanır; bilinmeyen bileşen genel ad). */
export function scoreLabelKey(key) {
  if (!SCORE_KEYS.includes(key)) return 'prs.score.other'
  return key === 'errors' ? 'prs.score.brokenObjects' : `prs.score.${key}`
}

/** Öneri parametresi → biçim türü (sunucu adları; her kodda aynı anlam). */
export const REC_PARAM_KIND = {
  count: 'count', bytes: 'bytes', limit_kb: 'kb', max_width: 'px', limit_mb: 'mb', limit: 'count', ttfb_ms: 'ms', limit_ms: 'ms',
  share_pct: 'pct', hosts: 'hosts',
}

/**
 * Öneri kodları ve i18n metinlerine giden parametre sırası — başlık ve gövde AYRI (`prs.rec.<CODE>.title|body`; her metin
 * kendi parametrelerini {0}'dan başlayarak kullanır, i18n-parity bitişik yer tutucu ister).
 */
export const REC_TEXT = {
  LARGE_IMAGES: { title: ['count', 'bytes'], body: ['limit_kb'] },
  MODERN_IMAGE_FORMAT: { title: ['count'], body: ['bytes'] },
  OVERSIZED_IMAGE_DIMENSIONS: { title: ['count'], body: ['max_width'] },
  OVERSIZED_PAGE: { title: ['bytes'], body: ['limit_mb'] },
  TOO_MANY_REQUESTS: { title: ['count'], body: ['count', 'limit'] },
  SLOW_SERVER: { title: ['ttfb_ms'], body: ['ttfb_ms', 'limit_ms'] },
  UNCOMPRESSED_TEXT: { title: ['count'], body: ['bytes'] },
  NO_CACHE: { title: ['count'], body: [] },
  BROKEN_RESOURCES: { title: ['count'], body: [] },
  HEAVY_JS: { title: ['bytes'], body: ['bytes', 'count'] },
  HEAVY_CSS: { title: ['bytes'], body: ['bytes', 'count'] },
  HEAVY_FONTS: { title: ['bytes'], body: ['bytes', 'count'] },
  THIRD_PARTY_HEAVY: { title: ['bytes'], body: ['share_pct', 'hosts'] },
  SLOW_OBJECTS: { title: ['count'], body: ['limit_ms'] },
  REDIRECTED_OBJECTS: { title: ['count'], body: [] },
  LARGE_DOCUMENTS: { title: ['count', 'bytes'], body: [] },
  BROKEN_LINKS: { title: ['count'], body: [] },
  ALL_GOOD: { title: [], body: [] },
}
export const REC_CODES = Object.keys(REC_TEXT)

/**
 * İngilizce tekil / çoğul (2026-10-31): sayı TAM 1 iken `<key>.one`, aksi hâlde `key` — techerror/ReportIssues deseni.
 * TR'de `.one` metni tabanla aynıdır (Türkçede sayıdan sonra ad tekil kalır). Sayıyı BİÇİMLENMEDEN verin.
 */
export const pluralKey = (key, n) => (Number(n) === 1 ? `${key}.one` : key)
export const plural = (t, key, n, ...args) => t(pluralKey(key, n), ...args)

/**
 * Öneri metninin (başlık / gövde) tekil biçimini seçen parametre — metinde geçmese de (ör. gövde "bu görseller" der,
 * sayı başlıktadır). Listede olmayan metin tekil biçim taşımaz.
 */
export const REC_PLURAL = {
  LARGE_IMAGES: { title: 'count', body: 'count' },
  MODERN_IMAGE_FORMAT: { title: 'count', body: 'count' },
  OVERSIZED_IMAGE_DIMENSIONS: { title: 'count', body: 'count' },
  UNCOMPRESSED_TEXT: { title: 'count', body: 'count' },
  NO_CACHE: { title: 'count', body: 'count' },
  BROKEN_RESOURCES: { title: 'count', body: 'count' },
  HEAVY_JS: { body: 'count' },
  HEAVY_CSS: { body: 'count' },
  HEAVY_FONTS: { body: 'count' },
  THIRD_PARTY_HEAVY: { body: 'hosts' },
  SLOW_OBJECTS: { title: 'count', body: 'count' },
  REDIRECTED_OBJECTS: { title: 'count' },
  LARGE_DOCUMENTS: { title: 'count' },
  BROKEN_LINKS: { title: 'count', body: 'count' },
}
export const SEVERITIES = ['high', 'medium', 'low']
export const SEVERITY_TONE = { high: 'danger', medium: 'warning', low: 'info' }

/** Boyut kovaları (1024 tabanlı) — kullanıcının süzgeç çipleri; büyükten küçüğe. */
export const SIZE_BUCKETS = [
  { key: 'xl', min: MB, max: Infinity },
  { key: 'lg', min: 500 * KB, max: MB },
  { key: 'md', min: 100 * KB, max: 500 * KB },
  { key: 'sm', min: 10 * KB, max: 100 * KB },
  { key: 'xs', min: 0, max: 10 * KB },
]
export const BUCKET_KEYS = SIZE_BUCKETS.map((b) => b.key)

export const SCOPES = ['page', 'all', 'documents', 'links']
/** `failed` = kırık (sunucu `verdict=broken`); `unverified` = doğrulanamadı / engellendi (kırık SAYILMAZ, 2026-10-31 r177). */
export const STATUS_FILTERS = ['all', 'failed', 'unverified']
export const SORTS = ['size_desc', 'size_asc', 'time_desc', 'time_asc', 'start_asc', 'type', 'url']
/**
 * Varsayılan kapsam SAYFA YÜKÜ (r177 inceleme): bağlantılı belgeler / sayfalar listenin başını almasın — kapsam çipiyle
 * görülür. `findings`: bulgu süzgeci (nesnenin `findings` kodlarından BİRİ seçiliyse geçer). `rec`: süzgeci kuran önerinin
 * kodu (yalnız etiket — "Öneri: … nesneleri"). `urls`: kodu nesne bulgusu olmayan öneriler (SLOW_SERVER …) için sunucunun
 * saydığı adresler (`item_urls`, en çok 10). Boş = süzgeç yok.
 */
export const DEFAULT_FILTERS = Object.freeze({
  q: '', types: [], buckets: [], findings: [], scope: 'page', status: 'all', sort: 'size_desc', urls: [], rec: null,
})

/**
 * Bulgu kodları (nesne `findings` — sunucunun NESNEYE bağlı 13 öneri kodu), çip sırası: kırıklar önce. Sayfa düzeyi
 * öneriler (OVERSIZED_PAGE, TOO_MANY_REQUESTS, SLOW_SERVER, THIRD_PARTY_HEAVY, ALL_GOOD) nesneye yazılmaz — sunucu
 * `item_count`'larını null gönderir; onlar nesne listesine `item_urls` (en çok 10) ile bağlanır.
 */
export const FINDING_CODES = [
  'BROKEN_RESOURCES', 'BROKEN_LINKS', 'LARGE_IMAGES', 'UNCOMPRESSED_TEXT', 'NO_CACHE', 'SLOW_OBJECTS', 'MODERN_IMAGE_FORMAT',
  'OVERSIZED_IMAGE_DIMENSIONS', 'REDIRECTED_OBJECTS', 'LARGE_DOCUMENTS', 'HEAVY_JS', 'HEAVY_CSS', 'HEAVY_FONTS',
]
export const FINDING_SET = new Set(FINDING_CODES)
/** Bulgu rozetinin tonu — kırık tehlike, düzeltilecek uyarı, katkı / bilgi nötr (bilinmeyen asla yeşil değil). */
export const FINDING_TONE = {
  BROKEN_RESOURCES: 'danger', BROKEN_LINKS: 'danger', LARGE_IMAGES: 'warning', UNCOMPRESSED_TEXT: 'warning', NO_CACHE: 'warning',
  SLOW_OBJECTS: 'warning', MODERN_IMAGE_FORMAT: 'warning', OVERSIZED_IMAGE_DIMENSIONS: 'warning', REDIRECTED_OBJECTS: 'info',
  LARGE_DOCUMENTS: 'info', HEAVY_JS: 'muted', HEAVY_CSS: 'muted', HEAVY_FONTS: 'muted',
}

/** Sunucu nesne kararı (`verdict`) — Sayfa Bütünlüğü izlemesinin kuralıyla aynı; yalnız `broken` kırık sayılır. */
export const VERDICTS = ['ok', 'broken', 'unverified', 'blocked', 'skipped']
const UNVERIFIED_VERDICTS = new Set(['unverified', 'blocked'])

/** Ölçüm notları (sunucu `analysis.notes[]` — `{ code, params }`) ve i18n'e giden parametre sırası. */
export const NOTE_ARGS = {
  JS_NOT_EXECUTED: [],
  CAPPED_OBJECTS: [['limit', 'count']],
  CAPPED_TIME: [['seconds', 'count']],
  CAPPED_BYTES: [['mb', 'mb']],
  EXCLUDES_APPLIED: [['count', 'count']],
}
/** Ölçüm notunun tekil biçimini seçen parametre. */
export const NOTE_PLURAL = { CAPPED_OBJECTS: 'limit', EXCLUDES_APPLIED: 'count' }

/** Nesne listesinde tablo ↔ kart eşiği (KAP genişliği, px). jsdom 0 → tablo. */
export const OBJECTS_TABLE_MIN_PX = 900
/** Grafikte bu kadar ya da daha az nokta varsa her nokta işaretlenir. */
export const TREND_SPARSE_POINTS = 30
/** Grafiğin başvuru çizgileri — tipik "iyi" (3 sn) ve "yavaş" (5 sn) eşikleri. */
export const TREND_REFERENCE_MS = [3000, 5000]
/** Görsel etkisi listesinde gösterilen en fazla görsel. */
export const TOP_IMAGES_MAX = 10

const num = (v) => (v === null || v === undefined || v === '' ? null : (Number.isFinite(Number(v)) ? Number(v) : null))
const isTr = (locale) => String(locale || '').toLowerCase().startsWith('tr')

// ── Biçimleme ────────────────────────────────────────────────────────────────

/** Yerel sayı ("1.234" TR / "1,234" EN). null → "—". */
export function formatNumber(v, locale, maxFraction = 0) {
  const n = num(v)
  if (n === null) return '—'
  return n.toLocaleString(locale || undefined, { maximumFractionDigits: maxFraction })
}

/**
 * İnsan okunur boyut (1024 tabanlı): < 1 KB bayt, < 1 MB KB (10 KB altı 1 ondalık), üstü MB (10 MB altı 2 ondalık).
 * `truncated` → değer alt sınırdır, "≥" ile gösterilir.
 */
export function formatSize(bytes, { truncated = false, locale } = {}) {
  const n = num(bytes)
  if (n === null || n < 0) return '—'
  const p = truncated ? '≥ ' : ''
  if (n < KB) return `${p}${formatNumber(n, locale)} B`
  if (n < MB) {
    const kb = n / KB
    return `${p}${formatNumber(kb, locale, kb < 10 ? 1 : 0)} KB`
  }
  const mb = n / MB
  return `${p}${formatNumber(mb, locale, mb < 10 ? 2 : 1)} MB`
}

/** Tam bayt ("1.234.567 B") — insan okunur boyutun yanında küçük harfle. */
export function formatBytesExact(bytes, locale) {
  const n = num(bytes)
  if (n === null || n < 0) return '—'
  return `${formatNumber(n, locale)} B`
}

/** Süre: < 1 sn "820 ms", < 60 sn "2,45 sn" / "2.45 s", üstü "1 dk 05 sn" / "1 min 05 s". */
export function formatMs(ms, locale) {
  const n = num(ms)
  if (n === null || n < 0) return '—'
  const tr = isTr(locale)
  if (n < 1000) return `${formatNumber(Math.round(n), locale)} ms`
  if (n < 60_000) {
    const s = n / 1000
    return `${formatNumber(s, locale, s < 10 ? 2 : 1)} ${tr ? 'sn' : 's'}`
  }
  const total = Math.round(n / 1000)
  const m = Math.floor(total / 60)
  const s = String(total % 60).padStart(2, '0')
  return tr ? `${m} dk ${s} sn` : `${m} min ${s} s`
}

/** Yüzde: TR "%45,3", EN "45.3%". */
export function formatPct(v, locale, maxFraction = 1) {
  const n = num(v)
  if (n === null) return '—'
  const s = formatNumber(n, locale, maxFraction)
  return isTr(locale) ? `%${s}` : `${s}%`
}

/** Önbellek süresi (max-age saniye) kısa metni: "45 sn", "10 dk", "6 sa", "7 g" / "45 s", "10 min", "6 h", "7 d". */
export function formatTtl(seconds, locale) {
  const n = num(seconds)
  if (n === null || n < 0) return '—'
  const tr = isTr(locale)
  if (n < 60) return `${formatNumber(n, locale)} ${tr ? 'sn' : 's'}`
  if (n < 3600) return `${formatNumber(Math.round(n / 60), locale)} ${tr ? 'dk' : 'min'}`
  if (n < 86_400) return `${formatNumber(Math.round(n / 3600), locale)} ${tr ? 'sa' : 'h'}`
  return `${formatNumber(Math.round(n / 86_400), locale)} ${tr ? 'g' : 'd'}`
}

/** Önbellek durumu: `none` (başlık yok), `nostore` (-1: no-store / no-cache), `ttl` (saniye). */
export function cacheState(ttl) {
  if (ttl === null || ttl === undefined) return 'none'
  const n = num(ttl)
  if (n === null) return 'none'
  return n < 0 ? 'nostore' : (n === 0 ? 'nostore' : 'ttl')
}

// ── Nesne kuralları ──────────────────────────────────────────────────────────

/** Nesne sayfa yüküne sayılıyor mu (sunucu bayrağı; eksikse kategoriden). */
export function isPageLoad(item) {
  if (typeof item?.page_load === 'boolean') return item.page_load
  return !NOT_PAGE_LOAD.has(item?.category)
}

/**
 * Nesnenin kararı: sunucunun `verdict`'i (bilinen değer) esastır; eski kayıtta (alan yok) aktarım hatası (status 0),
 * HTTP ≥ 400 ya da hata metni → `broken`, aksi hâlde `ok`.
 */
export function verdictOf(item) {
  if (typeof item?.verdict === 'string' && VERDICTS.includes(item.verdict)) return item.verdict
  const s = num(item?.status)
  if (s === 0 || (s !== null && s >= 400) || (typeof item?.error === 'string' && item.error.trim() !== '')) return 'broken'
  return 'ok'
}

/**
 * Başarısız (KIRIK) nesne: karar `broken`. 401/403/429 / WAF engeli gibi "doğrulanamadı" ve güvenlik kuralının engellediği
 * nesneler kırık SAYILMAZ (sunucu kararıyla; eski kayıtta eski kural).
 */
export function isFailed(item) {
  return verdictOf(item) === 'broken'
}

/** Doğrulanamayan / engellenen nesne (kırık değil, ama açıldığı da bilinmiyor). */
export const isUnverified = (item) => UNVERIFIED_VERDICTS.has(verdictOf(item))

// Eski kayıtlar için istemci bulgu kuralı — sunucunun PageResourceAnalyzer kurallarının AYNISI (eşikler oradan; değişirse
// burası da değişir). Yalnız `findings` alanı OLMAYAN (sürümden önce yazılmış) analizde kullanılır.
const RULE = {
  largeImage: 200 * KB, legacyMin: 50 * KB, oversizedWidth: 2000, textMin: KB, cacheOkS: 3600, slowMs: 1000, largeDoc: 5 * MB,
}
const CACHEABLE = new Set(['CSS', 'JS', 'IMAGE', 'FONT', 'MEDIA', 'FAVICON'])
const LEGACY_EXT = /\.(jpe?g|png|gif|bmp)(?:$|[?#])/i

/** Eski görsel biçimi (JPEG / PNG / GIF / BMP) — içerik türünden, yoksa uzantıdan (sunucu `legacyFormat`). */
export function legacyImageFormat(item) {
  const ct = String(item?.content_type || '').toLowerCase()
  if (ct.startsWith('image/')) return /jpe?g|png|gif|bmp/.test(ct)
  return LEGACY_EXT.test(String(item?.url || ''))
}

/** Sıkıştırılabilir metin (sunucu `compressibleText`): birinci taraf, 2xx/3xx, > 1 KB, HTML / CSS / JS / metin türü. */
export function compressibleText(item) {
  const s = num(item?.status)
  if (item?.third_party === true || s === null || s < 200 || s >= 400 || !((num(item?.bytes) ?? 0) > RULE.textMin)) return false
  if (TEXT_CATEGORIES.has(item?.category)) return true
  const ct = String(item?.content_type || '').toLowerCase()
  return ct.startsWith('text/') || /javascript|json|xml|svg/.test(ct)
}

/** Önbelleklenebilir durağan nesne (sunucu `cacheableStatic`): birinci taraf, 2xx, stil / betik / görsel / yazı tipi … */
export function cacheableStatic(item) {
  const s = num(item?.status)
  return item?.third_party !== true && s !== null && s >= 200 && s < 300 && CACHEABLE.has(item?.category)
}

/** Eski kayıt için nesnenin bulguları (sunucu kuralıyla); `recCodes` = analizdeki öneri kodları (katkı bulguları için). */
function fallbackFindings(it, recCodes) {
  const out = new Set()
  const page = isPageLoad(it)
  const bytes = num(it?.bytes) ?? 0
  const failed = isFailed(it)
  const img = it?.category === 'IMAGE'
  const ttl = num(it?.cache_ttl_s)
  if (page && failed) out.add('BROKEN_RESOURCES')
  if (!page && failed) out.add('BROKEN_LINKS')
  if (page && img && bytes >= RULE.largeImage) out.add('LARGE_IMAGES')
  if (page && img && legacyImageFormat(it) && bytes >= RULE.legacyMin && bytes < RULE.largeImage) out.add('MODERN_IMAGE_FORMAT')
  if (page && img && (num(it?.width) ?? 0) > RULE.oversizedWidth) out.add('OVERSIZED_IMAGE_DIMENSIONS')
  if (page && it?.compressed === false && compressibleText(it)) out.add('UNCOMPRESSED_TEXT')
  if (page && cacheableStatic(it) && (ttl === null || ttl < RULE.cacheOkS)) out.add('NO_CACHE')
  if (page && (num(it?.duration_ms) ?? 0) > RULE.slowMs) out.add('SLOW_OBJECTS')
  if (page && (num(it?.redirects) ?? 0) > 0) out.add('REDIRECTED_OBJECTS')
  if (it?.category === 'DOCUMENT' && bytes >= RULE.largeDoc) out.add('LARGE_DOCUMENTS')
  if (page && recCodes.has('HEAVY_JS') && it?.category === 'JS') out.add('HEAVY_JS')
  if (page && recCodes.has('HEAVY_CSS') && it?.category === 'CSS') out.add('HEAVY_CSS')
  if (page && recCodes.has('HEAVY_FONTS') && it?.category === 'FONT') out.add('HEAVY_FONTS')
  return FINDING_CODES.filter((c) => out.has(c))
}

/** Sunucu nesneye `findings` yazdı mı (sürümden sonraki analiz). */
export const hasServerFindings = (item) => Array.isArray(item?.findings)

/**
 * Nesnelere bulgu ekler: sunucunun `findings`'i (bilinen kodlar, sabit sırada) esastır; alan YOKSA (eski analiz) istemci
 * aynı kuralla hesaplar. Dönen nesnelerde `findings` hep dizidir, `findings_source` = `server` | `client`.
 */
export function withFindings(items, recommendations) {
  const recCodes = new Set((Array.isArray(recommendations) ? recommendations : []).map((r) => r?.code).filter(Boolean))
  return (Array.isArray(items) ? items : []).map((it) => {
    if (hasServerFindings(it)) {
      const set = new Set(it.findings)
      return { ...it, findings: FINDING_CODES.filter((c) => set.has(c)), findings_source: 'server' }
    }
    return { ...it, findings: fallbackFindings(it, recCodes), findings_source: 'client' }
  })
}

/** Bir öneriyle eşleşen nesne sayısı: sunucunun `item_count`'u; yoksa nesnelerdeki bulgu; o da yoksa adres sayısı. */
export function recItemCount(rec, items) {
  const n = num(rec?.item_count)
  if (n !== null) return n
  if (FINDING_SET.has(rec?.code) && Array.isArray(items)) return items.filter((it) => (it.findings || []).includes(rec.code)).length
  return recItemUrls(rec).length
}

/** Öneri nesne listesine BULGU süzgeciyle mi (tam liste) yoksa adres listesiyle mi (en çok 10) bağlanır. */
export const recFiltersByFinding = (rec) => FINDING_SET.has(rec?.code)

/**
 * Nesnenin bayt sayısı: sunucu boyutu bilemediyse (`size_source=unknown` — HEAD yanıtında Content-Length yok, bytes 0)
 * null — "0 B" değil, "bilinmiyor".
 */
export function itemBytes(item) {
  if (item?.size_source === 'unknown') return null
  return num(item?.bytes)
}

/** Nesnenin insan okunur boyutu (bilinmeyen boyut → "Bilinmiyor"). */
export function itemSizeText(item, t, locale) {
  if (item?.size_source === 'unknown') return t('prs.obj.sizeUnknown')
  return formatSize(item?.bytes, { truncated: item?.truncated === true, locale })
}

/** Boyut kovası anahtarı (xl … xs); bilinmeyen boyut null. */
export function sizeBucket(bytes) {
  const n = num(bytes)
  if (n === null || n < 0) return null
  return SIZE_BUCKETS.find((b) => n >= b.min && n < b.max)?.key ?? null
}

function matchesScope(item, scope) {
  if (scope === 'page') return isPageLoad(item)
  if (scope === 'documents') return item?.category === 'DOCUMENT'
  if (scope === 'links') return item?.category === 'LINK'
  return true
}

function matchesStatus(item, status) {
  if (status === 'failed') return isFailed(item)
  if (status === 'unverified') return isUnverified(item)
  return true
}

/** Süzgeçlerin hepsi (verilen boyut `skip` hariç) — sayaçlar için o boyut dışlanır. */
function passes(item, f, skip) {
  if (skip !== 'q' && !matchesSearchText(f.q, [item?.url, item?.content_type, item?.error, item?.host])) return false
  if (skip !== 'types' && f.types?.length && !f.types.includes(item?.category)) return false
  if (skip !== 'buckets' && f.buckets?.length && !f.buckets.includes(sizeBucket(itemBytes(item)))) return false
  if (skip !== 'findings' && f.findings?.length && !(item?.findings || []).some((c) => f.findings.includes(c))) return false
  if (skip !== 'scope' && !matchesScope(item, f.scope)) return false
  if (skip !== 'status' && !matchesStatus(item, f.status)) return false
  if (skip !== 'urls' && f.urls?.length && !f.urls.includes(item?.url)) return false
  return true
}

/** Süzgeçlerin tamamını uygular (sıra korunur). */
export function filterItems(items, filters = DEFAULT_FILTERS) {
  const f = { ...DEFAULT_FILTERS, ...(filters || {}) }
  return (Array.isArray(items) ? items : []).filter((it) => passes(it, f, null))
}

/**
 * Çip sayaçları: bir boyutun (types / buckets) her değeri için, ÖTEKİ süzgeçler uygulanmış listede kaç nesne var.
 * Kendi boyutu sayılmaz — bir tür seçiliyken ötekilerin sayısı da görünür (çoklu seçim).
 */
export function countsBy(items, filters, dim) {
  const f = { ...DEFAULT_FILTERS, ...(filters || {}) }
  const out = {}
  for (const it of Array.isArray(items) ? items : []) {
    if (!passes(it, f, dim)) continue
    if (dim === 'findings') {
      for (const c of it?.findings || []) out[c] = (out[c] || 0) + 1
      continue
    }
    if (dim === 'scope') {
      for (const s of SCOPES) if (matchesScope(it, s)) out[s] = (out[s] || 0) + 1
      continue
    }
    if (dim === 'status') {
      for (const s of STATUS_FILTERS) if (matchesStatus(it, s)) out[s] = (out[s] || 0) + 1
      continue
    }
    const k = dim === 'types' ? it?.category : sizeBucket(itemBytes(it))
    if (k == null) continue
    out[k] = (out[k] || 0) + 1
  }
  return out
}

/** Etkin süzgeç sayısı (sıralama ve varsayılan kapsam sayılmaz). */
export function activeFilterCount(filters) {
  const f = { ...DEFAULT_FILTERS, ...(filters || {}) }
  return (f.q.trim() ? 1 : 0) + (f.types.length ? 1 : 0) + (f.buckets.length ? 1 : 0) + (f.findings?.length ? 1 : 0)
    + (f.scope !== DEFAULT_FILTERS.scope ? 1 : 0) + (f.status !== 'all' ? 1 : 0) + (f.urls?.length ? 1 : 0)
}

/**
 * Seçili analiz değişince taşınmaması gereken süzgeçler (r177 H5): bir önerinin adres / bulgu süzgeci o analize aittir.
 * Tür / boyut / kapsam / durum / arama / sıralama kalır.
 */
export function filtersForNewScan(filters) {
  const f = { ...DEFAULT_FILTERS, ...(filters || {}) }
  return f.urls.length || f.rec || f.findings.length ? { ...f, urls: [], rec: null, findings: [] } : filters
}

/** Kararlı sıralama; boş değerler HER İKİ yönde de sonda. Eşitlikte büyük boyut, sonra URL. */
export function sortItems(items, sort = 'size_desc', locale) {
  const list = (Array.isArray(items) ? items : []).map((it, i) => ({ it, i }))
  const collator = new Intl.Collator(locale || undefined, { sensitivity: 'base', numeric: true })
  const nullsLast = (a, b, dir) => {
    if (a === null && b === null) return 0
    if (a === null) return 1
    if (b === null) return -1
    return dir * (a - b)
  }
  const byBytesDesc = (a, b) => nullsLast(itemBytes(a.it), itemBytes(b.it), -1)
  const tie = (a, b) => byBytesDesc(a, b) || collator.compare(String(a.it.url ?? ''), String(b.it.url ?? '')) || a.i - b.i
  const cmp = {
    size_desc: (a, b) => byBytesDesc(a, b) || tie(a, b),
    size_asc: (a, b) => nullsLast(itemBytes(a.it), itemBytes(b.it), 1) || tie(a, b),
    time_desc: (a, b) => nullsLast(num(a.it.duration_ms), num(b.it.duration_ms), -1) || tie(a, b),
    time_asc: (a, b) => nullsLast(num(a.it.duration_ms), num(b.it.duration_ms), 1) || tie(a, b),
    start_asc: (a, b) => nullsLast(num(a.it.start_ms), num(b.it.start_ms), 1)
      || nullsLast(num(a.it.duration_ms), num(b.it.duration_ms), -1) || tie(a, b),
    type: (a, b) => {
      const ia = CATEGORIES.indexOf(a.it.category)
      const ib = CATEGORIES.indexOf(b.it.category)
      return ((ia < 0 ? 99 : ia) - (ib < 0 ? 99 : ib)) || tie(a, b)
    },
    url: (a, b) => collator.compare(String(a.it.url ?? ''), String(b.it.url ?? '')) || a.i - b.i,
  }[SORTS.includes(sort) ? sort : 'size_desc']
  return list.sort(cmp).map((x) => x.it)
}

/** Şelale ölçeği: tahmini yüklenme süresi ya da en geç biten nesne (hangisi büyükse). */
export function waterfallTotal(items, loadMs) {
  let max = num(loadMs) ?? 0
  for (const it of Array.isArray(items) ? items : []) {
    const s = num(it?.start_ms)
    const d = num(it?.duration_ms)
    if (s !== null && d !== null) max = Math.max(max, s + d)
  }
  return max
}

/** Şelale çubuğu (yüzde): başlangıç ve uzunluk; başlangıcı bilinmeyen (belge / bağlantı) nesnede null. */
export function waterfallBar(item, total) {
  const s = num(item?.start_ms)
  const d = num(item?.duration_ms)
  if (s === null || d === null || !(total > 0)) return null
  const left = Math.max(0, Math.min(100, (s / total) * 100))
  const width = Math.max(0.6, Math.min(100 - left, (d / total) * 100))
  return { left, width }
}

/** Şelale görünümünün sıraları: başlangıca göre (varsayılan), süreye göre (en uzun önce) ya da bitişe göre. */
export const WATERFALL_SORTS = ['start', 'duration', 'end']

/**
 * Şelale satırları: YALNIZ yükleme tahminine giren nesneler (başlangıç + süre bilinen). Geç yüklenen görseller, belgeler
 * ve bağlantılar dışarıda kalır (`excluded` sayısı). Eşitlikte keşif sırası korunur.
 */
export function waterfallRows(items, sort = 'start') {
  const list = (Array.isArray(items) ? items : []).map((it, i) => ({ it, i }))
  const rows = list.filter(({ it }) => num(it?.start_ms) !== null && num(it?.duration_ms) !== null)
  const s = (x) => num(x.it.start_ms)
  const d = (x) => num(x.it.duration_ms)
  const cmp = {
    start: (a, b) => s(a) - s(b) || d(b) - d(a) || a.i - b.i,
    duration: (a, b) => d(b) - d(a) || s(a) - s(b) || a.i - b.i,
    end: (a, b) => (s(a) + d(a)) - (s(b) + d(b)) || a.i - b.i,
  }[WATERFALL_SORTS.includes(sort) ? sort : 'start']
  return { rows: rows.sort(cmp).map((x) => x.it), excluded: list.length - rows.length }
}

/** Ekseni "güzel" adımlarla böler: en çok `maxTicks` aralık; tavan adımın katı. ms adımları. */
const MS_STEPS = [50, 100, 200, 250, 500, 1000, 2000, 2500, 5000, 10_000, 15_000, 20_000, 30_000, 60_000, 120_000, 300_000]
export function niceMsTicks(maxMs, maxTicks = 5) {
  const m = Math.max(1, num(maxMs) ?? 0)
  const n = Math.max(1, Math.floor(maxTicks))
  const step = MS_STEPS.find((st) => Math.ceil(m / st) <= n) ?? Math.ceil(m / n / 60_000) * 60_000
  const top = Math.max(step, Math.ceil(m / step) * step)
  const ticks = []
  for (let v = 0; v <= top + 1e-9 && ticks.length <= n + 1; v += step) ticks.push(v)
  return { step, top, ticks }
}

/** Bayt ekseni (1024 tabanlı): KB ya da MB'de yuvarlak adımlar ("0,5 MB / 1 MB / 1,5 MB" yerine "1 MB / 2 MB"). */
const BYTE_STEPS = [
  10 * KB, 25 * KB, 50 * KB, 100 * KB, 250 * KB, 500 * KB,
  MB, 2 * MB, 5 * MB, 10 * MB, 20 * MB, 50 * MB, 100 * MB, 250 * MB, 500 * MB,
]
export function niceByteTicks(maxBytes, maxTicks = 3) {
  const m = Math.max(1, num(maxBytes) ?? 0)
  const n = Math.max(1, Math.floor(maxTicks))
  const step = BYTE_STEPS.find((st) => Math.ceil(m / st) <= n) ?? Math.ceil(m / n / (500 * MB)) * 500 * MB
  const top = Math.max(step, Math.ceil(m / step) * step)
  const ticks = []
  for (let v = 0; v <= top + 1e-9 && ticks.length <= n + 1; v += step) ticks.push(v)
  return { step, top, ticks }
}

/** Not ekseni: her zaman 0–100, 25'er. */
export const GRADE_TICKS = [0, 25, 50, 75, 100]

/**
 * Şelale çubuğunun parçaları (yüzde): bekleme (TTFB — ilk bayta kadar) ve indirme. TTFB bilinmiyorsa tek parça.
 * `max` = eksenin tavanı.
 */
export function waterfallSegments(item, max) {
  const s = num(item?.start_ms)
  const d = num(item?.duration_ms)
  if (s === null || d === null || !(max > 0)) return null
  const left = Math.max(0, Math.min(100, (s / max) * 100))
  const total = Math.max(0.6, Math.min(100 - left, (d / max) * 100))
  const tt = num(item?.ttfb_ms)
  const wait = tt !== null && d > 0 ? Math.min(total, total * Math.min(1, Math.max(0, tt) / d)) : 0
  return { left, wait, download: Math.max(0, total - wait), total }
}

// ── Puan / ton / fark ───────────────────────────────────────────────────────

/** Harf notu: sunucunun harfi (A–F) esastır; yoksa puandan türetilir. Bilinmiyorsa null. */
export function gradeLetter(grade, letter) {
  if (typeof letter === 'string' && /^[A-F]$/.test(letter.trim().toUpperCase())) return letter.trim().toUpperCase()
  const g = num(grade)
  if (g === null) return null
  if (g >= 90) return 'A'
  if (g >= 80) return 'B'
  if (g >= 70) return 'C'
  if (g >= 60) return 'D'
  if (g >= 50) return 'E'
  return 'F'
}

/** Not tonu: A/B başarı, C/D uyarı, E/F tehlike; bilinmiyor = soluk (asla yeşil değil). */
export function gradeTone(letter) {
  if (letter === 'A' || letter === 'B') return 'success'
  if (letter === 'C' || letter === 'D') return 'warning'
  if (letter === 'E' || letter === 'F') return 'danger'
  return 'muted'
}

/** Ölçüm sınırına takılmış (kısmi) koşu mu — sunucunun `capped` bayrağı; yalnız açık `true` sayılır. */
export const isCapped = (scan) => scan?.capped === true

/**
 * Koşunun not tonu: sınırlı (kısmi) ölçüm notu ASLA başarı rengi almaz — soluk (CLAUDE.md "Before a feature counts as
 * done" kural 5: kısmi veriden yeşil türetilmez). Aksi hâlde harf notunun tonu.
 */
export function scanTone(scan) {
  if (isCapped(scan)) return 'muted'
  return gradeTone(gradeLetter(scan?.grade, scan?.grade_letter))
}

/** Önceki koşuyla fark gösterilir mi: ikisi de var ve HİÇBİRİ sınırlı ölçüm değil (kısmi sayıyla kıyas yanıltır). */
export function deltasComparable(scan, prev) {
  return !!scan && !!prev && !isCapped(scan) && !isCapped(prev)
}

/** Kısa metinleri adlı yer tutucu taşıyan (nesne bağlamında anlamsız) kodlar — genel "bağlantı hatası" metnine düşer. */
const GENERIC_PROBLEM_CODES = new Set(['UNKNOWN', 'HTTP_STATUS', 'RESOURCES_BROKEN', 'RESOURCES_TIMEOUT', 'MIXED_CONTENT'])

/**
 * Sunucunun nesne / sayfa sorun KODU → kullanıcı metni (ham kod asla gösterilmez). `BLOCKED` → güvenlik kuralı;
 * `CheckFailureReason` kodları → `chkfail.<KOD>.short`; `TRANSPORT_ERROR`, bilinmeyen kod ve metni olmayan kod →
 * genel "Bağlantı hatası". Boş kod → ''.
 */
export function problemText(code, t) {
  const c = typeof code === 'string' ? code.trim() : ''
  if (!c) return ''
  if (c === 'BLOCKED') return t('prs.objProblem.blocked')
  if (CHECK_FAILURE_SET.has(c) && !GENERIC_PROBLEM_CODES.has(c)) {
    const key = `chkfail.${c}.short`
    const text = t(key)
    if (typeof text === 'string' && text !== '' && text !== key && !/\{[a-z]/i.test(text)) return text
  }
  return t('prs.objProblem.connection')
}

/** Alt puan tonu (0–100). */
export function scoreTone(score) {
  const s = num(score)
  if (s === null) return 'muted'
  if (s >= 80) return 'success'
  if (s >= 50) return 'warning'
  return 'danger'
}

/**
 * Önceki tamamlanmış koşuya göre fark. %1'in altındaki oynama "aynı" sayılır (ölçüm gürültüsü). `lowerIsBetter`:
 * süre / boyut / istek için true, not için false. Bilinmeyen değer → null (fark gösterilmez, "0" uydurulmaz).
 */
export function delta(current, previous, { lowerIsBetter = true } = {}) {
  const c = num(current)
  const p = num(previous)
  if (c === null || p === null) return null
  const diff = c - p
  const pct = p !== 0 ? (diff / Math.abs(p)) * 100 : null
  if (diff === 0 || (pct !== null && Math.abs(pct) < 1)) return { diff, pct, direction: 'same' }
  const better = lowerIsBetter ? diff < 0 : diff > 0
  return { diff, pct, direction: better ? 'better' : 'worse' }
}

/** Geçmiş listesinde (yeniden eskiye) seçili koşudan önceki İLK tamamlanmış koşu. */
export function previousDone(items, scanId) {
  const list = Array.isArray(items) ? items : []
  const idx = list.findIndex((s) => s?.id === scanId)
  if (idx < 0) return null
  return list.slice(idx + 1).find((s) => s?.status === 'DONE') ?? null
}

/** Varsayılan seçim: en yeni tamamlanmış koşu; yoksa en yeni koşu (başarısız olsa bile); hiç yoksa null. */
export function pickDefaultScan(items) {
  const list = Array.isArray(items) ? items : []
  return (list.find((s) => s?.status === 'DONE') ?? list.find((s) => s?.status !== 'RUNNING') ?? null)?.id ?? null
}

export const isRunning = (scan) => scan?.status === 'RUNNING'

/** UTC damgası → epoch ms (geçersiz → null). */
export function toMs(iso) {
  if (!iso) return null
  const t = Date.parse(toUtc(String(iso)))
  return Number.isFinite(t) ? t : null
}

/** Koşunun geçen süresi (ms) — bitmişse bitiş, sürüyorsa şimdi; saat kayması eksiye düşürmez. */
export function elapsedMs(scan, now = Date.now()) {
  const s = toMs(scan?.started_at)
  if (s === null) return null
  const e = toMs(scan?.finished_at) ?? now
  return Math.max(0, e - s)
}

/**
 * Süren koşunun GÖRÜLDÜĞÜ andaki geçen süresi (ms, r177 H13): sunucunun yanıt damgası (`timestamp`) varsa sunucu
 * saatiyle (başlangıç da sunucu damgası — tarayıcı saati kaymış olsa da doğru), yoksa tarayıcı saatiyle; eksiye düşmez.
 * Ekranda bu taban + o andan beri geçen MONOTON süre gösterilir.
 */
export function elapsedAtSighting(scan, serverTimestamp, browserNow = Date.now()) {
  const s = toMs(scan?.started_at)
  if (s === null) return null
  const srv = toMs(serverTimestamp)
  return Math.max(0, (srv ?? browserNow) - s)
}

/** Analiz kullanıcı tarafından iptal edildi mi (sunucu `cancelled` ya da hata kodu `CANCELLED`). */
export const isCancelled = (scan) => scan?.cancelled === true || scan?.error_code === 'CANCELLED'

/**
 * İlerleme GERİ GİTMEZ (r177 H16): CSS'ten yeni nesne bulundukça toplam büyür ve yüzde düşebilirdi; ekranda görülen
 * en yüksek değer korunur. `pct` null (belirsiz) → önceki değer.
 */
export function monotonicPct(prevMax, pct) {
  const p = num(pct)
  const m = num(prevMax)
  if (p === null) return m
  return m === null ? p : Math.max(m, p)
}

/** İlerleme yüzdesi; toplam bilinmiyorsa (0) null = belirsiz. */
export function progressPct(scan) {
  const done = num(scan?.progress?.done)
  const total = num(scan?.progress?.total)
  if (done === null || total === null || total <= 0) return null
  return Math.max(0, Math.min(100, Math.round((done / total) * 100)))
}

/** İstemci izleme tavanı (ms): sunucunun süre sınırı + pay; en az 10 dk. */
export function pollCapMs(limits) {
  const max = num(limits?.max_seconds)
  return Math.max(POLL_MIN_CAP_MS, max !== null ? max * 1000 + POLL_GRACE_MS : 0)
}

/**
 * İzleme tavanı aşıldı mı — süre BU istemcinin izlemeye başladığı andan ölçülür (`startedMs`, monoton saat), sunucunun
 * `started_at` damgasından değil: tarayıcı saati sunucudan ilerideyse izleme hemen durmasın (2026-10-31 inceleme).
 */
export function pollCapReached(startedMs, nowMs, cap) {
  const s = num(startedMs)
  const n = num(nowMs)
  const c = num(cap)
  if (s === null || n === null || c === null) return false
  return n - s > c
}

// ── Grafik ───────────────────────────────────────────────────────────────────

/**
 * Yüklenme süresi geçmişi noktaları (eskiden yeniye). Tamamlanmış koşu değer taşır; başarısız / yarıda kalan koşu bir
 * BOŞLUK noktasıdır (değer null — çizgi kopar, sıfıra düşmez). Süren koşu çizilmez. `isolated`: komşuları boş olan
 * tek değerli nokta (çizgi çizilemez, nokta olarak işaretlenir).
 */
export function trendPoints(items) {
  const pts = (Array.isArray(items) ? items : [])
    .filter((s) => s && s.status !== 'RUNNING')
    .map((s) => {
      const t = toMs(s.started_at)
      const done = s.status === 'DONE' && num(s.load_ms) !== null
      return {
        t, id: s.id, ts: s.started_at, status: s.status,
        load: done ? num(s.load_ms) : null,
        size: done ? num(s.page_bytes) : null,
        grade: done ? num(s.grade) : null,
        letter: done ? gradeLetter(s.grade, s.grade_letter) : null,
        capped: done && isCapped(s),
        gap: !done,
      }
    })
    .filter((p) => p.t !== null)
    .sort((a, b) => a.t - b.t || String(a.id).localeCompare(String(b.id)))
  return pts.map((p, i) => ({
    ...p,
    isolated: p.load !== null && (pts[i - 1]?.load ?? null) === null && (pts[i + 1]?.load ?? null) === null,
  }))
}

/** Grafik zaman aralığı; tek nokta ±1 sa genişletilir (eksen çökmesin). */
export function trendDomain(points) {
  const ts = (points || []).map((p) => p.t).filter((t) => Number.isFinite(t))
  if (!ts.length) return null
  const lo = Math.min(...ts)
  const hi = Math.max(...ts)
  return lo === hi ? [lo - 3_600_000, hi + 3_600_000] : [lo, hi]
}

// ── Analiz blokları ──────────────────────────────────────────────────────────

/**
 * Kategori kırılımı: önce sayfa yükü (bayta göre büyükten küçüğe), sonra sayfa boyutuna sayılmayanlar (belge / bağlantı).
 * `share` yalnız sayfa yükü kategorilerinde (sayfa boyutunun yüzdesi). `bar` (r177 H3): sayfa yükü kategorisinde PAYIN
 * kendisi (çubuk yanındaki yüzdeyle aynı ölçek); sayılmayanlarda kendi aralarındaki en büyüğe göre (ayrı ölçek) — bir
 * 5 MB PDF sayfa yükü çubuklarını küçültmez.
 */
export function categoryRows(categories, pageBytes) {
  const list = (Array.isArray(categories) ? categories : []).filter((c) => c && c.category)
  const maxOther = Math.max(0, ...list.filter((c) => NOT_PAGE_LOAD.has(c.category)).map((c) => num(c.bytes) ?? 0))
  const maxPage = Math.max(0, ...list.filter((c) => !NOT_PAGE_LOAD.has(c.category)).map((c) => num(c.bytes) ?? 0))
  const total = num(pageBytes)
  const rows = list.map((c) => {
    const bytes = num(c.bytes)
    const pageLoad = !NOT_PAGE_LOAD.has(c.category)
    const share = pageLoad && total !== null && total > 0 && bytes !== null ? (bytes / total) * 100 : null
    let bar = 0
    if (bytes !== null) {
      if (pageLoad) bar = share !== null ? Math.min(100, share) : (maxPage > 0 ? (bytes / maxPage) * 100 : 0)
      else bar = maxOther > 0 ? (bytes / maxOther) * 100 : 0
    }
    return {
      category: c.category, known: CATEGORIES.includes(c.category), count: num(c.count) ?? 0, bytes, durationMs: num(c.duration_ms),
      pageLoad, share, bar,
    }
  })
  const order = (r) => (CATEGORIES.indexOf(r.category) < 0 ? 99 : CATEGORIES.indexOf(r.category))
  const byBytes = (a, b) => (b.bytes ?? -1) - (a.bytes ?? -1) || order(a) - order(b)
  return [...rows.filter((r) => r.pageLoad).sort(byBytes), ...rows.filter((r) => !r.pageLoad).sort(byBytes)]
}

/** Ağırlık yüzdesi — sunucu 0–1 kesir ya da 0–100 yüzde gönderebilir. */
export function weightPct(weight) {
  const w = num(weight)
  if (w === null) return null
  return w <= 1 ? Math.round(w * 1000) / 10 : w
}

/** Puan bileşeni satırları: bilinen sıra, sonra bilinmeyen anahtarlar (sunucuda yeni bir bileşen). */
export function scoreRows(scores) {
  const list = (Array.isArray(scores) ? scores : []).filter((s) => s && s.key)
  const order = (k) => (SCORE_KEYS.indexOf(k) < 0 ? 99 : SCORE_KEYS.indexOf(k))
  return [...list].sort((a, b) => order(a.key) - order(b.key)).map((s) => {
    const row = {
      key: s.key, known: SCORE_KEYS.includes(s.key), score: num(s.score), weight: weightPct(s.weight),
      value: s.value, unit: s.unit, tone: scoreTone(s.score), fullAt: num(s.full_at), zeroAt: num(s.zero_at),
    }
    row.lost = lostPoints({ ...row, lost: s.lost })
    return row
  })
}

/**
 * Bileşenin nottan götürdüğü puan: sunucunun `lost` değeri (ağırlık × (100 − puan) / 100); eski kayıtta aynı formülle
 * istemcide (1 ondalık). Puan ya da ağırlık bilinmiyorsa null.
 */
export function lostPoints(row) {
  const l = num(row?.lost)
  if (l !== null) return Math.max(0, l)
  const s = num(row?.score)
  const w = num(row?.weight)
  if (s === null || w === null) return null
  return Math.max(0, Math.round((w * (100 - s)) / 10) / 10)
}

/** "Notu en çok düşüren" bileşenler: kayıp > 0, büyükten küçüğe, en çok `n`. */
export function topLosses(scores, n = 3) {
  return scoreRows(scores).filter((r) => r.lost !== null && r.lost > 0)
    .sort((a, b) => b.lost - a.lost || SCORE_KEYS.indexOf(a.key) - SCORE_KEYS.indexOf(b.key)).slice(0, n)
}

/** Puan bileşeni → onu iyileştiren öneri kodları (öncelik sırasıyla). */
export const SCORE_RECS = {
  load_time: ['SLOW_OBJECTS', 'OVERSIZED_PAGE', 'LARGE_IMAGES', 'HEAVY_JS'],
  page_weight: ['OVERSIZED_PAGE', 'LARGE_IMAGES', 'HEAVY_JS', 'HEAVY_CSS', 'HEAVY_FONTS', 'THIRD_PARTY_HEAVY'],
  images: ['LARGE_IMAGES', 'MODERN_IMAGE_FORMAT', 'OVERSIZED_IMAGE_DIMENSIONS'],
  requests: ['TOO_MANY_REQUESTS'],
  server_response: ['SLOW_SERVER'],
  compression: ['UNCOMPRESSED_TEXT'],
  caching: ['NO_CACHE'],
  errors: ['BROKEN_RESOURCES'],
}

/** Bileşenin analizdeki ilgili önerisi (yoksa null). */
export function relatedRec(key, recs) {
  const codes = SCORE_RECS[key] || []
  const list = Array.isArray(recs) ? recs : []
  for (const c of codes) {
    const r = list.find((x) => x?.code === c)
    if (r) return r
  }
  return null
}

/** Bileşen hedefinin bir sınırını biriminde biçimler. */
function formatBound(v, unit, locale) {
  return formatScoreValue({ value: v, unit }, locale)
}

/**
 * Bileşenin hedefleri (sunucu `full_at` / `zero_at`, `unit` biriminde): tam puan ve sıfır puan eşiği; yön eşiklerden
 * (tam < sıfır → düşük daha iyi). Alanlar yoksa (eski analiz) null — hedef UYDURULMAZ.
 */
export function scoreTarget(row, locale) {
  const full = num(row?.fullAt ?? row?.full_at)
  const zero = num(row?.zeroAt ?? row?.zero_at)
  if (full === null || zero === null || full === zero) return null
  return { full: formatBound(full, row.unit, locale), zero: formatBound(zero, row.unit, locale), lowerIsBetter: full < zero }
}

/** Bileşenin ölçülen değeri birimine göre ("1,2 MB", "820 ms", "%45", "54"); bilinmeyen birim → yalın sayı. */
export function formatScoreValue(row, locale) {
  const v = row?.value
  if (v === null || v === undefined || v === '') return '—'
  const unit = String(row?.unit || '').toLowerCase()
  if (unit === 'ms') return formatMs(v, locale)
  if (unit === 'bytes' || unit === 'byte' || unit === 'b') return formatSize(v, { locale })
  if (unit === 'pct' || unit === 'percent' || unit === '%') return formatPct(v, locale)
  if (num(v) !== null) return formatNumber(v, locale, 1)
  return String(v)
}

/** Öneri biliniyor mu (bilinmeyen kod ham kod olarak GÖSTERİLMEZ — genel metin). */
export const knownRec = (code) => Object.prototype.hasOwnProperty.call(REC_TEXT, code)

/** Öneri metninin i18n anahtarı (`part`: title | body) — bilinmeyen kod genel metin, sayı 1 ise `.one`. */
export function recTextKey(rec, part = 'title') {
  const which = part === 'body' ? 'body' : 'title'
  if (!knownRec(rec?.code)) return `prs.rec.other.${which}`
  const name = REC_PLURAL[rec.code]?.[which]
  const v = name ? rec?.params?.[name] : undefined
  return pluralKey(`prs.rec.${rec.code}.${which}`, Array.isArray(v) ? v.length : v)
}

/** Öneri metnine (`part`: title | body) giden sıralı parametreler (biçimlenmiş). */
export function recommendationArgs(rec, locale, part = 'title') {
  const spec = REC_TEXT[rec?.code]?.[part === 'body' ? 'body' : 'title'] || []
  const p = rec?.params || {}
  return spec.map((name) => formatRecParam(p[name], REC_PARAM_KIND[name] || 'count', locale))
}

function formatRecParam(v, kind, locale) {
  if (kind === 'hosts') {
    if (Array.isArray(v)) {
      const list = v.filter((x) => typeof x === 'string' && x.trim())
      if (!list.length) return '—'
      return list.length > 3 ? `${list.slice(0, 3).join(', ')} +${list.length - 3}` : list.join(', ')
    }
    if (typeof v === 'string') return v || '—'
    return formatNumber(v, locale)
  }
  if (v === null || v === undefined || v === '') return '—'
  if (kind === 'bytes') return formatSize(v, { locale })
  if (kind === 'ms') return formatMs(v, locale)
  if (kind === 'pct') return formatPct(v, locale)
  if (kind === 'kb') return `${formatNumber(v, locale)} KB`
  if (kind === 'mb') return `${formatNumber(v, locale, 1)} MB`
  if (kind === 'px') return `${formatNumber(v, locale)} px`
  return formatNumber(v, locale)
}

/** Önerinin nesne adresleri (`item_urls`, en çok 10; boş / eksik → []). */
export function recItemUrls(rec) {
  return (Array.isArray(rec?.item_urls) ? rec.item_urls : []).filter((u) => typeof u === 'string' && u.trim()).slice(0, 10)
}

/** THIRD_PARTY_HEAVY'nin üçüncü taraf ana makineleri (`params.host_list`, en çok 5). */
export function recHostList(rec) {
  return (Array.isArray(rec?.params?.host_list) ? rec.params.host_list : []).filter((h) => typeof h === 'string' && h.trim()).slice(0, 5)
}

/** Ölçüm notları: bilinen kodlar sırayla + biçimlenmiş parametreler; bilinmeyen kod `known: false` (genel metin). */
export function noteRows(notes, locale) {
  return (Array.isArray(notes) ? notes : []).filter((n) => n && n.code).map((n) => {
    const spec = NOTE_ARGS[n.code]
    return {
      code: n.code, known: Boolean(spec),
      key: spec ? pluralKey(`prs.note.${n.code}`, n.params?.[NOTE_PLURAL[n.code]]) : 'prs.note.other',
      args: (spec || []).map(([name, kind]) => formatRecParam(n.params?.[name], kind, locale)),
    }
  })
}

/** Önerileri önem sırasına dizer (yüksek → düşük), eşitlikte sunucu sırası. */
export function sortRecommendations(recs) {
  const rank = (s) => (SEVERITIES.indexOf(s) < 0 ? 9 : SEVERITIES.indexOf(s))
  return (Array.isArray(recs) ? recs : []).filter((r) => r && r.code)
    .map((r, i) => ({ r, i })).sort((a, b) => rank(a.r.severity) - rank(b.r.severity) || a.i - b.i).map((x) => x.r)
}

/** Önerinin olası kazancı: { bytes, ms } metinleri ya da null. */
export function recSaving(rec, locale) {
  const b = num(rec?.saving_bytes)
  const m = num(rec?.saving_ms)
  if ((b === null || b <= 0) && (m === null || m <= 0)) return null
  return { bytes: b !== null && b > 0 ? formatSize(b, { locale }) : null, ms: m !== null && m > 0 ? formatMs(m, locale) : null }
}

/**
 * Tek görselin 4G'de tahmini indirme süresi: sunucunun toplam görsel tahmini oranından (aynı varsayım — hız uydurulmaz).
 * Toplam bilinmiyorsa null.
 */
export function estImageMs(bytes, images) {
  const b = num(bytes)
  const tb = num(images?.bytes)
  const tm = num(images?.est_ms_4g)
  if (b === null || tb === null || tm === null || tb <= 0) return null
  return Math.round((b * tm) / tb)
}

/** Görselin ölçülmüş optimize boyutu (sunucu `optimized_bytes`, yalnız ölçülen görsellerde) ve kazanç yüzdesi. */
export function optimizedImage(item) {
  const b = num(item?.bytes)
  const o = num(item?.optimized_bytes)
  if (b === null || o === null || b <= 0 || o < 0) return null
  // Yeniden sıkıştırma küçültmediyse (= ya da büyük) görsel "zaten iyi sıkıştırılmış"
  return { bytes: b, optimized: o, savedPct: o >= b ? 0 : ((b - o) / b) * 100, alreadyGood: o >= b }
}

/** Ölçülmüş görsel kazancının metni: "1,6 MB → 400 KB (−%75)" ya da "zaten iyi sıkıştırılmış". */
export function optimizedText(item, t, locale) {
  const opt = optimizedImage(item)
  if (!opt) return ''
  if (opt.alreadyGood) return t('prs.img.alreadyGood')
  return t('prs.img.optimizedTo', formatSize(opt.bytes, { locale }), formatSize(opt.optimized, { locale }), formatPct(opt.savedPct, locale, 0))
}

/** Ana belgenin TTFB'si (sunucu r177 `ttfb_ms` = YALNIZ son adımın ilk baytı; eski kayıtta `html_ttfb_ms`). */
export const scanTtfb = (scan) => num(scan?.ttfb_ms) ?? num(scan?.html_ttfb_ms)

// ── Üçüncü taraflar ──────────────────────────────────────────────────────────

/** Üçüncü taraf satırı için kategori listesini bilinen sıraya dizer (bilinmeyen sonda). */
function orderCategories(list) {
  const set = new Set((Array.isArray(list) ? list : []).filter((c) => typeof c === 'string' && c))
  return [...CATEGORIES.filter((c) => set.has(c)), ...[...set].filter((c) => !CATEGORIES.includes(c))]
}

/**
 * Üçüncü taraf sunucular: analizin `third_parties` listesi (sunucu, en çok 20, bayta göre); eski analizde nesnelerden
 * (yalnız sayfa yükü, `third_party=true`) aynı şekilde hesaplanır. Hiçbiri yoksa null (bölüm çizilmez).
 * `source` = server | client.
 */
export function thirdPartyRows(analysis, items) {
  if (Array.isArray(analysis?.third_parties)) {
    return {
      source: 'server',
      rows: analysis.third_parties.filter((r) => r && r.host).map((r) => ({
        host: String(r.host), count: num(r.count) ?? 0, bytes: num(r.bytes) ?? 0, failed: num(r.failed) ?? 0,
        categories: orderCategories(r.categories),
      })),
    }
  }
  if (!Array.isArray(items)) return null
  const by = new Map()
  for (const it of items) {
    if (it?.third_party !== true || !isPageLoad(it)) continue
    const host = (it.host || hostOf(it.url) || '').toLowerCase()
    if (!host) continue
    const r = by.get(host) || { host, count: 0, bytes: 0, failed: 0, categories: [] }
    r.count += 1
    r.bytes += num(it.bytes) ?? 0
    if (verdictOf(it) !== 'ok' && verdictOf(it) !== 'skipped') r.failed += 1
    if (it.category && !r.categories.includes(it.category)) r.categories.push(it.category)
    by.set(host, r)
  }
  if (!by.size) return { source: 'client', rows: [] }
  const rows = [...by.values()].map((r) => ({ ...r, categories: orderCategories(r.categories) }))
    .sort((a, b) => b.bytes - a.bytes || a.host.localeCompare(b.host)).slice(0, 20)
  return { source: 'client', rows }
}

// ── Seçim, eski analiz, karşılaştırma ───────────────────────────────────────

/** En yeni tamamlanmış analizin kimliği (yoksa null). */
export function latestDoneId(items) {
  return (Array.isArray(items) ? items : []).find((s) => s?.status === 'DONE')?.id ?? null
}

/** Karşılaştırmada seçilebilen analizler: yalnız tamamlanmış olanlar (yeniden eskiye). */
export const comparableScans = (items) => (Array.isArray(items) ? items : []).filter((s) => s?.status === 'DONE')

/** Analizin not algoritması sürümü (`algorithm_version`; alan yok = 1 — sürümden önceki kayıt). */
export function algorithmVersion(scan, analysis) {
  return num(analysis?.algorithm_version) ?? num(scan?.algorithm_version) ?? 1
}

/** Karşılaştırmanın KPI'ları (anahtar, alan, iyi yön) — ekran ve testler aynı listeyi kullanır. */
export const COMPARE_KPIS = [
  ['grade', 'grade', false], ['load', 'load_ms', true], ['ttfb', 'ttfb', true], ['size', 'page_bytes', true],
  ['requests', 'request_count', true], ['images', 'image_bytes', true], ['broken', 'failed_count', true],
]
/** "Büyüyen nesne" eşiği: en az %10 VE en az 10 KB büyüme (ölçüm gürültüsü sayılmaz). */
export const GROWN_MIN_PCT = 10
export const GROWN_MIN_BYTES = 10 * KB
/** Karşılaştırma listelerinde gösterilen en fazla nesne (fazlası "+N daha"). */
export const COMPARE_LIST_MAX = 20

/**
 * İki tamamlanmış analizi karşılaştırır (İSTEMCİDE — sunucu ucu yok). `a`, `b` = `{ scan, analysis, items }`; sonuç
 * ESKİ → YENİ yönündedir (başlangıç zamanına göre; eşitse kimliğe göre). Nesne listeleri yalnız İKİSİNDE de nesne
 * ayrıntısı varsa (son 20 analiz) — yoksa `objects: null` (yalnız özet karşılaştırılır).
 */
export function compareScans(a, b) {
  if (!a?.scan || !b?.scan) return null
  const ta = toMs(a.scan.started_at) ?? 0
  const tb = toMs(b.scan.started_at) ?? 0
  const [older, newer] = ta < tb || (ta === tb && Number(a.scan.id) < Number(b.scan.id)) ? [a, b] : [b, a]
  const algoOld = algorithmVersion(older.scan, older.analysis)
  const algoNew = algorithmVersion(newer.scan, newer.analysis)
  const val = (scan, field) => (field === 'ttfb' ? scanTtfb(scan) : num(scan[field]))
  const kpis = COMPARE_KPIS.map(([key, field, lowerIsBetter]) => ({
    key, field, old: val(older.scan, field), now: val(newer.scan, field),
    d: delta(val(newer.scan, field), val(older.scan, field), { lowerIsBetter }),
  }))
  // Kategori farkı: iki taraftaki her tür (bilinen sıra)
  const catOf = (an) => new Map((Array.isArray(an?.categories) ? an.categories : []).filter((c) => c?.category)
    .map((c) => [c.category, { bytes: num(c.bytes) ?? 0, count: num(c.count) ?? 0 }]))
  const co = catOf(older.analysis)
  const cn = catOf(newer.analysis)
  const cats = [...new Set([...co.keys(), ...cn.keys()])]
    .sort((x, y) => (CATEGORIES.indexOf(x) < 0 ? 99 : CATEGORIES.indexOf(x)) - (CATEGORIES.indexOf(y) < 0 ? 99 : CATEGORIES.indexOf(y)))
    .map((c) => {
      const o = co.get(c) || { bytes: 0, count: 0 }
      const n = cn.get(c) || { bytes: 0, count: 0 }
      return { category: c, oldBytes: o.bytes, newBytes: n.bytes, oldCount: o.count, newCount: n.count, diff: n.bytes - o.bytes }
    })
  // Öneriler: yeni çıkan / çözülen (ALL_GOOD sayılmaz)
  const codes = (an) => new Set((Array.isArray(an?.recommendations) ? an.recommendations : []).map((r) => r?.code)
    .filter((c) => c && c !== 'ALL_GOOD'))
  const ro = codes(older.analysis)
  const rn = codes(newer.analysis)
  const recNew = (Array.isArray(newer.analysis?.recommendations) ? newer.analysis.recommendations : []).filter((r) => r?.code && r.code !== 'ALL_GOOD' && !ro.has(r.code))
  const recResolved = (Array.isArray(older.analysis?.recommendations) ? older.analysis.recommendations : []).filter((r) => r?.code && r.code !== 'ALL_GOOD' && !rn.has(r.code))
  let objects = null
  if (Array.isArray(older.items) && Array.isArray(newer.items)) {
    const map = (list) => {
      const m = new Map()
      for (const it of list) if (it?.url && !m.has(it.url)) m.set(it.url, it)
      return m
    }
    const mo = map(older.items)
    const mn = map(newer.items)
    const added = [...mn.values()].filter((it) => !mo.has(it.url)).sort((x, y) => (num(y.bytes) ?? -1) - (num(x.bytes) ?? -1))
    const removed = [...mo.values()].filter((it) => !mn.has(it.url)).sort((x, y) => (num(y.bytes) ?? -1) - (num(x.bytes) ?? -1))
    const grown = []
    for (const [url, it] of mn) {
      const prev = mo.get(url)
      const nb = num(it.bytes)
      const ob = num(prev?.bytes)
      if (!prev || nb === null || ob === null) continue
      const diff = nb - ob
      if (diff >= GROWN_MIN_BYTES && (ob === 0 || (diff / ob) * 100 >= GROWN_MIN_PCT)) grown.push({ ...it, old_bytes: ob, diff })
    }
    grown.sort((x, y) => y.diff - x.diff)
    objects = { added, removed, grown }
  }
  return { older, newer, algoMismatch: algoOld !== algoNew, algoOld, algoNew, kpis, categories: cats, recNew, recResolved, objects }
}

// ── URL durumu (paylaşılabilir bağlantı) ────────────────────────────────────

/** Görünümler: pano, nesne listesi, şelale, geçmiş, karşılaştırma (URL `prs_v`; pano varsayılan — yazılmaz). */
export const VIEWS = ['dash', 'objects', 'waterfall', 'history', 'compare']
export const URL_SCAN = 'prs'
export const URL_VIEW = 'prs_v'
export const URL_OTHER = 'prs_b'

/** Görünüm → üst bölüm (Pano / Nesneler / Geçmiş) ve alt kip. */
export function sectionOfView(view) {
  if (view === 'objects' || view === 'waterfall') return 'objects'
  if (view === 'history' || view === 'compare') return 'history'
  return 'overview'
}
export function viewOf(section, objectsMode, historyMode) {
  if (section === 'objects') return objectsMode === 'waterfall' ? 'waterfall' : 'objects'
  if (section === 'history') return historyMode === 'compare' ? 'compare' : 'history'
  return 'dash'
}

const posInt = (v) => {
  const n = Number(v)
  return Number.isInteger(n) && n > 0 ? n : null
}

/** Adres çubuğundaki durum: `{ scanId, view, otherId }` (geçersiz değer null / `dash`). */
export function parseUrlState(search) {
  let p
  try { p = new URLSearchParams(search || '') } catch { p = new URLSearchParams() }
  const view = p.get(URL_VIEW)
  return { scanId: posInt(p.get(URL_SCAN)), view: VIEWS.includes(view) ? view : 'dash', otherId: posInt(p.get(URL_OTHER)) }
}

/** URL'e yazılacak değerler (varsayılan görünüm ve karşılaştırma dışı ikinci analiz yazılmaz → null). */
export function urlStateValues({ scanId, view, otherId }) {
  return {
    [URL_SCAN]: scanId ?? null,
    [URL_VIEW]: view && view !== 'dash' && VIEWS.includes(view) ? view : null,
    [URL_OTHER]: view === 'compare' && otherId != null ? otherId : null,
  }
}

/**
 * Paylaşılabilir mutlak bağlantı: izleme sayfası, detay penceresi bu izlemede, "Kaynak analizi" sekmesi, seçili analiz
 * ve görünüm. (Adres çubuğuna güvenmez — debounce'lu yazım henüz düşmemiş olabilir.)
 */
export function resourceScanLink({ origin, monitorId, scanId, view, otherId }) {
  const p = new URLSearchParams()
  p.set('tab', 'page')
  if (monitorId != null) p.set('monitor', String(monitorId))
  p.set('mtab', 'resources')
  for (const [k, v] of Object.entries(urlStateValues({ scanId, view, otherId }))) if (v != null) p.set(k, String(v))
  return `${origin || ''}/?${p.toString()}`
}

// ── Dışa aktarım ─────────────────────────────────────────────────────────────

/** Dosya adı parçası: Türkçe harfler sadeleşir, harf / rakam dışı "-", en çok 60 karakter. */
export function slugify(s, max = 60) {
  const out = foldSearchText(s).replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, max).replace(/-+$/g, '')
  return out || 'page'
}

/** Koşunun başlangıcı yerel saatle "yyyy-MM-dd-HHmm" (bilinmiyorsa şimdi). */
export function fileStamp(iso, now = new Date()) {
  const ms = toMs(iso)
  const d = ms !== null ? new Date(ms) : now
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}-${p(d.getHours())}${p(d.getMinutes())}`
}

/** "<izleme-adi>-kaynak-analizi-<yyyy-MM-dd-HHmm>.<ext>" (`base` i18n'den: kaynak-analizi / resource-analysis). */
export function exportFileName(monitor, base, ext, scanStartedAt, now = new Date()) {
  const name = monitor?.name || hostOf(monitor?.url) || monitor?.url || ''
  return `${slugify(name)}-${slugify(base || 'kaynak-analizi', 40)}-${fileStamp(scanStartedAt, now)}.${ext}`
}

/** URL'in ana makinesi (geçersizse null). */
export function hostOf(url) {
  try { return new URL(String(url)).host || null } catch { return null }
}

const yesNo = (v, t) => (v === true ? t('prs.export.yes') : v === false ? t('prs.export.no') : '')

/** Kategori etiketi (bilinmeyen kategori "Diğer"). */
export const categoryLabel = (c, t) => t(`prs.cat.${CATEGORIES.includes(c) ? c : 'OTHER'}`)

/**
 * Durum metni (r177 H6): 0 → "Yanıt yok" (bağlantı kurulamadı DA olabilir, yanıt gecikti DE — nedeni sorun satırı
 * söyler; eskiden "Bağlantı kurulamadı" + "Yanıt zaman aşımı" aynı satırda çelişiyordu), diğerleri HTTP kodu.
 */
export function statusText(item, t) {
  // İndirilmeyen nesne (preload=none medya): sunucu bilerek istemedi — "yanıt yok" değil
  if (verdictOf(item) === 'skipped') return t('prs.verdict.obj.skipped')
  const s = num(item?.status)
  if (s === null) return '—'
  return s === 0 ? t('prs.obj.noResponse') : String(s)
}

/** Kararın kısa metni (ok → boş: durum kodu yeterli). */
export function verdictText(item, t) {
  const v = verdictOf(item)
  // ok → durum kodu yeter; skipped → durum metni zaten "İndirilmedi"
  return v === 'ok' || v === 'skipped' ? '' : t(`prs.verdict.obj.${v}`)
}

/** Kararın tonu: kırık tehlike; doğrulanamadı / engellendi / indirilmedi NÖTR (amber değil — kırık değiller). */
export const verdictTone = (item) => (verdictOf(item) === 'broken' ? 'danger' : 'muted')

/** Önbellek metni (dışa aktarım ve kart). */
export function cacheText(item, t, locale) {
  const st = cacheState(item?.cache_ttl_s)
  if (st === 'none') return t('prs.obj.cacheNone')
  if (st === 'nostore') return t('prs.obj.cacheNoStore')
  return t('prs.obj.cacheTtl', formatTtl(item.cache_ttl_s, locale))
}

/** Bulgunun kısa adı (rozet / süzgeç / dışa aktarım). */
export const findingLabel = (code, t) => t(FINDING_SET.has(code) ? `prs.finding.${code}` : 'prs.finding.other')

/** Nesnenin bulunduğu yer: `page` → sayfanın HTML'i; adres → o CSS; alan yoksa CSS bayrağından (adres bilinmez). */
export function foundInText(item, t) {
  const f = item?.found_in
  if (f === 'page') return t('prs.od.foundPage')
  if (typeof f === 'string' && f.trim()) return t('prs.od.foundCss', f)
  if (item?.from_css === true) return t('prs.od.foundCssUnknown')
  return item?.found_in === undefined ? '' : t('prs.od.foundPage')
}

/** Nesneler sayfası: başlık + satırlar (sayılar SAYI hücresi — Excel'de sıralanabilir / toplanabilir). */
export function objectSheetRows(items, t, locale) {
  const head = [
    t('prs.export.col.url'), t('prs.export.col.category'), t('prs.export.col.contentType'), t('prs.export.col.status'),
    t('prs.export.col.bytes'), t('prs.export.col.size'), t('prs.export.col.bucket'), t('prs.export.col.ttfb'),
    t('prs.export.col.duration'), t('prs.export.col.start'), t('prs.export.col.pageLoad'), t('prs.export.col.thirdParty'),
    t('prs.export.col.compressed'), t('prs.export.col.cache'), t('prs.export.col.width'), t('prs.export.col.height'),
    t('prs.export.col.redirects'), t('prs.export.col.fromCss'), t('prs.export.col.lazy'), t('prs.export.col.truncated'),
    t('prs.export.col.problem'), t('prs.export.col.verdict'), t('prs.export.col.findings'), t('prs.export.col.host'),
    t('prs.export.col.via'), t('prs.export.col.foundIn'), t('prs.export.col.finalUrl'), t('prs.export.col.encoding'),
    t('prs.export.col.cacheControl'), t('prs.export.col.optimized'),
  ]
  const rows = (Array.isArray(items) ? items : []).map((it) => {
    const bucket = sizeBucket(itemBytes(it))
    const h = it.headers && typeof it.headers === 'object' ? it.headers : {}
    return [
      it.url ?? '', categoryLabel(it.category, t), it.content_type ?? '', statusText(it, t),
      itemBytes(it), itemSizeText(it, t, locale), bucket ? t(`prs.bucket.${bucket}`) : '',
      num(it.ttfb_ms), num(it.duration_ms), num(it.start_ms), yesNo(isPageLoad(it), t), yesNo(it.third_party, t),
      yesNo(it.compressed, t), cacheText(it, t, locale), num(it.width), num(it.height), num(it.redirects),
      yesNo(it.from_css, t), yesNo(it.lazy, t), yesNo(it.truncated, t), problemText(it.error, t),
      verdictText(it, t), (Array.isArray(it.findings) ? it.findings : []).map((c) => findingLabel(c, t)).join(', '),
      it.host || hostOf(it.url) || '', it.via === 'proxy' ? t('prs.via.proxy') : it.via === 'direct' ? t('prs.via.direct') : '',
      foundInText(it, t), it.final_url || '', h.content_encoding || '', h.cache_control || '', num(it.optimized_bytes),
    ]
  })
  return [head, ...rows]
}

/** Türler sayfası: tür, sayfa yüküne sayılır mı, nesne sayısı, bayt, sayfa payı. */
export function categorySheetRows(categories, pageBytes, t, locale) {
  return [
    [t('prs.export.col.category'), t('prs.export.col.pageLoad'), t('prs.export.col.count'), t('prs.export.col.bytes'),
      t('prs.export.col.size'), t('prs.export.col.share')],
    ...categoryRows(categories, pageBytes).map((r) => [
      categoryLabel(r.category, t), yesNo(r.pageLoad, t), r.count, r.bytes, formatSize(r.bytes, { locale }),
      r.share != null ? formatPct(r.share, locale) : '',
    ]),
  ]
}

/** Üçüncü taraflar sayfası. */
export function thirdPartySheetRows(tp, t, locale) {
  return [
    [t('prs.export.col.host'), t('prs.export.col.count'), t('prs.export.col.bytes'), t('prs.export.col.size'),
      t('prs.export.col.brokenCount'), t('prs.export.col.categories')],
    ...(tp?.rows || []).map((r) => [
      r.host, r.count, r.bytes, formatSize(r.bytes, { locale }), r.failed, r.categories.map((c) => categoryLabel(c, t)).join(', '),
    ]),
  ]
}

/** Özet sayfası: [alan, değer] satırları. */
export function summarySheetRows(monitor, scan, analysis, t, locale, fmtDate = (v) => v ?? '') {
  const s = scan || {}
  const letter = gradeLetter(s.grade, s.grade_letter)
  return [
    [t('prs.export.field'), t('prs.export.value')],
    [t('prs.export.monitor'), monitor?.name || monitor?.url || ''],
    [t('prs.export.url'), s.url || monitor?.url || ''],
    [t('prs.export.startedAt'), s.started_at ? fmtDate(s.started_at) : ''],
    [t('prs.export.finishedAt'), s.finished_at ? fmtDate(s.finished_at) : ''],
    [t('prs.export.requestedBy'), s.requested_by || ''],
    [t('prs.export.via'), s.via === 'proxy' ? t('prs.via.proxy') : s.via === 'direct' ? t('prs.via.direct') : ''],
    [t('prs.grade'), num(s.grade) !== null
      ? `${num(s.grade)} / 100${letter ? ` (${letter})` : ''}${isCapped(s) ? ` · ${t('prs.grade.partial')}` : ''}` : ''],
    [t('prs.kpi.load'), formatMs(s.load_ms, locale)],
    [t('prs.kpi.ttfb'), formatMs(scanTtfb(s), locale)],
    [t('prs.export.redirects'), num(s.redirect_count) !== null
      ? t('prs.export.redirectsValue', formatNumber(s.redirect_count, locale), formatMs(s.redirect_ms ?? 0, locale)) : ''],
    [t('prs.kpi.htmlTime'), formatMs(s.html_ms, locale)],
    [t('prs.kpi.size'), formatSize(s.page_bytes, { locale })],
    [t('prs.export.pageBytes'), num(s.page_bytes)],
    [t('prs.kpi.requests'), num(s.request_count)],
    [t('prs.kpi.images'), `${formatSize(s.image_bytes, { locale })} · ${formatNumber(s.image_count, locale)}`],
    [t('prs.kpi.documents'), `${formatNumber(s.document_count, locale)} · ${formatSize(s.document_bytes, { locale })}`],
    [t('prs.kpi.links'), `${formatNumber(s.link_count, locale)} · ${formatSize(s.link_bytes, { locale })}`],
    [t('prs.kpi.broken'), num(s.failed_count)],
    [t('prs.export.capped'), s.capped === true ? t('prs.export.yes') : t('prs.export.no')],
    [t('prs.export.algorithm'), String(algorithmVersion(s, analysis))],
    [t('prs.export.estimateNote'), plural(t, 'prs.method.estimate', analysis?.timing?.parallel ?? 6, analysis?.timing?.parallel ?? 6)],
    [t('prs.export.methodNote'), t('prs.method.notLighthouse')],
  ]
}

/** Bileşen hedefinin metni ("Tam puan ≤ 1 sn · 0 puan ≥ 12 sn"; hedef yoksa boş). */
export function scoreTargetText(row, t, locale) {
  const tg = scoreTarget(row, locale)
  if (!tg) return ''
  return t(tg.lowerIsBetter ? 'prs.score.targetLow' : 'prs.score.targetHigh', tg.full, tg.zero)
}

/** Puan bileşenleri sayfası. */
export function scoreSheetRows(scores, t, locale) {
  return [
    [t('prs.export.col.component'), t('prs.export.col.score'), t('prs.export.col.weight'), t('prs.export.col.measured'),
      t('prs.export.col.lost'), t('prs.export.col.target')],
    ...scoreRows(scores).map((r) => [
      t(scoreLabelKey(r.key)), r.score, r.weight, formatScoreValue(r, locale), r.lost, scoreTargetText(r, t, locale),
    ]),
  ]
}

/** Öneriler sayfası (metinler i18n'den, parametreler biçimlenmiş). */
export function recommendationSheetRows(recs, t, locale, items = null) {
  return [
    [t('prs.export.col.severity'), t('prs.export.col.recommendation'), t('prs.export.col.detail'), t('prs.export.col.saving'),
      t('prs.export.col.objects'), t('prs.export.col.count')],
    ...sortRecommendations(recs).map((r) => {
      const args = recommendationArgs(r, locale, 'title')
      const bodyArgs = recommendationArgs(r, locale, 'body')
      const sv = recSaving(r, locale)
      const hosts = recHostList(r)
      const urls = recItemUrls(r)
      return [
        t(`prs.sev.${SEVERITIES.includes(r.severity) ? r.severity : 'low'}`),
        t(recTextKey(r, 'title'), ...args),
        [t(recTextKey(r, 'body'), ...bodyArgs), hosts.length ? hosts.join(', ') : '']
          .filter(Boolean).join(' — '),
        sv ? [sv.bytes, sv.ms].filter(Boolean).join(' · ') : '',
        urls.join('\n'),
        r.code === 'ALL_GOOD' ? null : recItemCount(r, items),
      ]
    }),
  ]
}

/** Görseller sayfası: en büyük görseller (boyut, ölçü, 4G süresi, sayfadaki payı). */
export function imageSheetRows(images, pageBytes, t, locale) {
  const top = Array.isArray(images?.top) ? images.top : []
  const pb = num(pageBytes)
  return [
    [t('prs.export.col.url'), t('prs.export.col.bytes'), t('prs.export.col.size'), t('prs.export.col.dimensions'),
      t('prs.export.col.est4g'), t('prs.export.col.share'), t('prs.export.col.optimized')],
    ...top.map((it) => {
      const est = estImageMs(it.bytes, images)
      const b = num(it.bytes)
      const opt = optimizedImage(it)
      return [
        it.url ?? '', b, formatSize(b, { truncated: it.truncated === true, locale }), dimensionsText(it),
        est !== null ? formatMs(est, locale) : '', pb && b !== null ? formatPct((b / pb) * 100, locale) : '',
        opt ? formatSize(opt.optimized, { locale }) : '',
      ]
    }),
  ]
}

/** "1920×1080" ya da boş. */
export function dimensionsText(item) {
  const w = num(item?.width)
  const h = num(item?.height)
  return w !== null && h !== null ? `${w}×${h}` : ''
}
