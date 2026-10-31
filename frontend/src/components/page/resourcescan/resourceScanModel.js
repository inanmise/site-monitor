import { toUtc } from '../../../utils/localDay.js'
import { foldSearchText, matchesSearchText } from '../../../utils/monitorFilters.js'

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

export const SCOPES = ['all', 'page', 'documents', 'links']
export const STATUS_FILTERS = ['all', 'failed']
export const SORTS = ['size_desc', 'size_asc', 'time_desc', 'time_asc', 'type', 'url']
/**
 * `urls`: bir önerinin "Nesneleri göster" eylemi — yalnız önerinin sunucunun saydığı nesneleri (`item_urls`, en çok 10).
 * Boş = süzgeç yok.
 */
export const DEFAULT_FILTERS = Object.freeze({ q: '', types: [], buckets: [], scope: 'all', status: 'all', sort: 'size_desc', urls: [] })

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

/** Başarısız nesne: aktarım hatası (status 0), HTTP ≥ 400 ya da sunucunun hata metni. */
export function isFailed(item) {
  const s = num(item?.status)
  return s === 0 || (s !== null && s >= 400) || (typeof item?.error === 'string' && item.error.trim() !== '')
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

/** Süzgeçlerin hepsi (verilen boyut `skip` hariç) — sayaçlar için o boyut dışlanır. */
function passes(item, f, skip) {
  if (skip !== 'q' && !matchesSearchText(f.q, [item?.url, item?.content_type, item?.error])) return false
  if (skip !== 'types' && f.types?.length && !f.types.includes(item?.category)) return false
  if (skip !== 'buckets' && f.buckets?.length && !f.buckets.includes(sizeBucket(item?.bytes))) return false
  if (skip !== 'scope' && !matchesScope(item, f.scope)) return false
  if (skip !== 'status' && f.status === 'failed' && !isFailed(item)) return false
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
    const k = dim === 'types' ? it?.category : sizeBucket(it?.bytes)
    if (k == null) continue
    out[k] = (out[k] || 0) + 1
  }
  return out
}

/** Etkin süzgeç sayısı (sıralama sayılmaz). */
export function activeFilterCount(filters) {
  const f = { ...DEFAULT_FILTERS, ...(filters || {}) }
  return (f.q.trim() ? 1 : 0) + (f.types.length ? 1 : 0) + (f.buckets.length ? 1 : 0)
    + (f.scope !== 'all' ? 1 : 0) + (f.status !== 'all' ? 1 : 0) + (f.urls?.length ? 1 : 0)
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
  const byBytesDesc = (a, b) => nullsLast(num(a.it.bytes), num(b.it.bytes), -1)
  const tie = (a, b) => byBytesDesc(a, b) || collator.compare(String(a.it.url ?? ''), String(b.it.url ?? '')) || a.i - b.i
  const cmp = {
    size_desc: (a, b) => byBytesDesc(a, b) || tie(a, b),
    size_asc: (a, b) => nullsLast(num(a.it.bytes), num(b.it.bytes), 1) || tie(a, b),
    time_desc: (a, b) => nullsLast(num(a.it.duration_ms), num(b.it.duration_ms), -1) || tie(a, b),
    time_asc: (a, b) => nullsLast(num(a.it.duration_ms), num(b.it.duration_ms), 1) || tie(a, b),
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
 * `share` yalnız sayfa yükü kategorilerinde (sayfa boyutunun yüzdesi); `bar` en büyük kategoriye göre görsel ölçek.
 */
export function categoryRows(categories, pageBytes) {
  const list = (Array.isArray(categories) ? categories : []).filter((c) => c && c.category)
  const max = Math.max(0, ...list.map((c) => num(c.bytes) ?? 0))
  const total = num(pageBytes)
  const rows = list.map((c) => {
    const bytes = num(c.bytes)
    const pageLoad = !NOT_PAGE_LOAD.has(c.category)
    return {
      category: c.category, known: CATEGORIES.includes(c.category), count: num(c.count) ?? 0, bytes, durationMs: num(c.duration_ms),
      pageLoad,
      share: pageLoad && total !== null && total > 0 && bytes !== null ? (bytes / total) * 100 : null,
      bar: max > 0 && bytes !== null ? (bytes / max) * 100 : 0,
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
  return [...list].sort((a, b) => order(a.key) - order(b.key)).map((s) => ({
    key: s.key, known: SCORE_KEYS.includes(s.key), score: num(s.score), weight: weightPct(s.weight),
    value: s.value, unit: s.unit, tone: scoreTone(s.score),
  }))
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

/** Durum metni: 0 → "bağlantı hatası", diğerleri HTTP kodu. */
export function statusText(item, t) {
  const s = num(item?.status)
  if (s === null) return '—'
  return s === 0 ? t('prs.obj.transport') : String(s)
}

/** Önbellek metni (dışa aktarım ve kart). */
export function cacheText(item, t, locale) {
  const st = cacheState(item?.cache_ttl_s)
  if (st === 'none') return t('prs.obj.cacheNone')
  if (st === 'nostore') return t('prs.obj.cacheNoStore')
  return t('prs.obj.cacheTtl', formatTtl(item.cache_ttl_s, locale))
}

/** Nesneler sayfası: başlık + satırlar (sayılar SAYI hücresi — Excel'de sıralanabilir / toplanabilir). */
export function objectSheetRows(items, t, locale) {
  const head = [
    t('prs.export.col.url'), t('prs.export.col.category'), t('prs.export.col.contentType'), t('prs.export.col.status'),
    t('prs.export.col.bytes'), t('prs.export.col.size'), t('prs.export.col.bucket'), t('prs.export.col.ttfb'),
    t('prs.export.col.duration'), t('prs.export.col.start'), t('prs.export.col.pageLoad'), t('prs.export.col.thirdParty'),
    t('prs.export.col.compressed'), t('prs.export.col.cache'), t('prs.export.col.width'), t('prs.export.col.height'),
    t('prs.export.col.redirects'), t('prs.export.col.fromCss'), t('prs.export.col.lazy'), t('prs.export.col.truncated'),
    t('prs.export.col.problem'),
  ]
  const rows = (Array.isArray(items) ? items : []).map((it) => {
    const bucket = sizeBucket(it.bytes)
    return [
      it.url ?? '', categoryLabel(it.category, t), it.content_type ?? '', statusText(it, t),
      num(it.bytes), formatSize(it.bytes, { truncated: it.truncated === true, locale }), bucket ? t(`prs.bucket.${bucket}`) : '',
      num(it.ttfb_ms), num(it.duration_ms), num(it.start_ms), yesNo(isPageLoad(it), t), yesNo(it.third_party, t),
      yesNo(it.compressed, t), cacheText(it, t, locale), num(it.width), num(it.height), num(it.redirects),
      yesNo(it.from_css, t), yesNo(it.lazy, t), yesNo(it.truncated, t), it.error ?? '',
    ]
  })
  return [head, ...rows]
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
    [t('prs.grade'), num(s.grade) !== null ? `${num(s.grade)} / 100${letter ? ` (${letter})` : ''}` : ''],
    [t('prs.kpi.load'), formatMs(s.load_ms, locale)],
    [t('prs.kpi.ttfb'), formatMs(s.html_ttfb_ms, locale)],
    [t('prs.kpi.htmlTime'), formatMs(s.html_ms, locale)],
    [t('prs.kpi.size'), formatSize(s.page_bytes, { locale })],
    [t('prs.export.pageBytes'), num(s.page_bytes)],
    [t('prs.kpi.requests'), num(s.request_count)],
    [t('prs.kpi.images'), `${formatSize(s.image_bytes, { locale })} · ${formatNumber(s.image_count, locale)}`],
    [t('prs.kpi.documents'), `${formatNumber(s.document_count, locale)} · ${formatSize(s.document_bytes, { locale })}`],
    [t('prs.kpi.links'), `${formatNumber(s.link_count, locale)} · ${formatSize(s.link_bytes, { locale })}`],
    [t('prs.kpi.broken'), num(s.failed_count)],
    [t('prs.export.capped'), s.capped === true ? t('prs.export.yes') : t('prs.export.no')],
    [t('prs.export.estimateNote'), plural(t, 'prs.method.estimate', analysis?.timing?.parallel ?? 6, analysis?.timing?.parallel ?? 6)],
  ]
}

/** Puan bileşenleri sayfası. */
export function scoreSheetRows(scores, t, locale) {
  return [
    [t('prs.export.col.component'), t('prs.export.col.score'), t('prs.export.col.weight'), t('prs.export.col.measured')],
    ...scoreRows(scores).map((r) => [
      t(scoreLabelKey(r.key)), r.score, r.weight, formatScoreValue(r, locale),
    ]),
  ]
}

/** Öneriler sayfası (metinler i18n'den, parametreler biçimlenmiş). */
export function recommendationSheetRows(recs, t, locale) {
  return [
    [t('prs.export.col.severity'), t('prs.export.col.recommendation'), t('prs.export.col.detail'), t('prs.export.col.saving'),
      t('prs.export.col.objects')],
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
      t('prs.export.col.est4g'), t('prs.export.col.share')],
    ...top.map((it) => {
      const est = estImageMs(it.bytes, images)
      const b = num(it.bytes)
      return [
        it.url ?? '', b, formatSize(b, { truncated: it.truncated === true, locale }), dimensionsText(it),
        est !== null ? formatMs(est, locale) : '', pb && b !== null ? formatPct((b / pb) * 100, locale) : '',
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
