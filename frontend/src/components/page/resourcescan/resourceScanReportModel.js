import {
  CATEGORIES, KB, NOT_PAGE_LOAD, SEVERITIES, categoryLabel, formatMs, formatNumber, formatPct, formatSize, gradeLetter, gradeTone,
  isCapped, plural, recTextKey, recommendationArgs, scoreTone, toMs,
} from './resourceScanModel.js'

/**
 * Kaynak analizi — BÜTÇE, EĞİLİM, ÇOK SAYFALI (SİTE) ANALİZ ve YÖNETİCİ RAPORU (prs3 F + G, 2026-11-04 kullanıcı kararı).
 * SAF mantık (React yok): ekran bileşenleri (ResourceScanBudget / TrendView / Site / Start), dışa aktarım
 * (resourceScanExport.js) ve testler yalnız bunu kullanır. Genel biçimleyiciler resourceScanModel.js'ten gelir.
 *
 * Sunucu sözleşmesi (prs3-contract F1 / F2 / G):
 *  - GET / PUT /api/monitoring/page/{id}/resource-budget — alanlar null = sınır yok; PUT YALNIZ değişen alanları taşır.
 *  - GET …/resource-scans/{id} → `budget` = { defined, checks:[{ key, limit, actual, ok }], exceeded } (okuma anında GÜNCEL
 *    bütçeyle değerlendirilir); liste satırı `budget_status` (ok | exceeded | none | unknown) + `budget_exceeded`.
 *  - GET …/resource-scans/trend?days=30|90|365 → { points:[…], growth:[…] }.
 *  - POST …/resource-scans { scope: 'site', max_pages: N } → `analysis.site` = { pages, shared, totals }.
 * Kural: bilinmeyen / okunamayan değer ASLA "geçti" / yeşil değildir; eski analizlerde olmayan alan uydurulmaz.
 */

const num = (v) => (v === null || v === undefined || v === '' ? null : (Number.isFinite(Number(v)) ? Number(v) : null))
const isObj = (v) => v !== null && typeof v === 'object' && !Array.isArray(v)
const arr = (v) => (Array.isArray(v) ? v : [])

// ── F1 Bütçe ─────────────────────────────────────────────────────────────────

/**
 * Bütçe alanları (sunucu adları, sabit sıra): birim türü, izin verilen aralık (sunucuyla aynı), yön (`max` = üst sınır,
 * `min` = alt sınır) ve gerçek değerin analiz özetindeki alanı (bayt alanları KB sınırla karşılaştırılır).
 */
export const BUDGET_FIELDS = [
  { key: 'max_page_kb', kind: 'kb', min: 50, max: 51200, dir: 'max', scanField: 'page_bytes' },
  { key: 'max_requests', kind: 'count', min: 5, max: 2000, dir: 'max', scanField: 'request_count' },
  { key: 'max_js_kb', kind: 'kb', min: 10, max: 20480, dir: 'max', scanField: 'js_bytes' },
  { key: 'max_image_kb', kind: 'kb', min: 10, max: 51200, dir: 'max', scanField: 'image_bytes' },
  { key: 'max_load_ms', kind: 'ms', min: 200, max: 60000, dir: 'max', scanField: 'load_ms' },
  { key: 'min_grade', kind: 'score', min: 0, max: 100, dir: 'min', scanField: 'grade' },
  { key: 'min_security_score', kind: 'score', min: 0, max: 100, dir: 'min', scanField: 'security_score' },
]
export const BUDGET_KEYS = BUDGET_FIELDS.map((f) => f.key)
const FIELD = Object.fromEntries(BUDGET_FIELDS.map((f) => [f.key, f]))
export const budgetField = (key) => FIELD[key] || null
/** Liste satırının bütçe durumu → rozet tonu ("bilinmiyor" soluk — asla yeşil değil). */
export const BUDGET_STATUS_TONE = { ok: 'success', exceeded: 'danger', unknown: 'muted' }

/** Tamsayı (sunucu tamsayı ister): "1.5" gibi kesirli / bozuk değer null. */
const int = (v) => {
  const n = num(v)
  return n !== null && Number.isInteger(n) ? n : null
}

/**
 * Sunucu yanıtının `data`'sı → { values, canEdit }. Gövde düz alanlar ya da `{ budget: {…} }` olabilir; tanınmayan şekil
 * (dizi, null) tüm alanları null sayar (= sınır yok) — ama `known: false` döner: çağıran "okunamadı" diyebilsin.
 * `fields: false` = gövde nesne ama bütçe alanı yok (ör. kayıt yanıtı yalnız `{ saved: true }`).
 * `canEdit` yalnız açık boolean ise; yoksa null (çağıran izleme satırının `can_edit`'ine düşer).
 */
export function normalizeBudget(data) {
  const known = isObj(data)
  const src = known ? (isObj(data.budget) ? data.budget : data) : {}
  const values = {}
  for (const f of BUDGET_FIELDS) values[f.key] = int(src[f.key])
  const ce = known ? (typeof data.can_edit === 'boolean' ? data.can_edit : (typeof src.can_edit === 'boolean' ? src.can_edit : null)) : null
  // `fields`: gövde bütçe alanlarından en az birini (null dahil) taşıyor mu — taşımayan kayıt yanıtı değer bildirmez
  const fields = known && BUDGET_FIELDS.some((f) => Object.prototype.hasOwnProperty.call(src, f.key))
  return { values, canEdit: ce, known, fields }
}

/** Kayıtlı değerler → form metinleri (null → ''). */
export function budgetFormFrom(values) {
  const out = {}
  for (const f of BUDGET_FIELDS) out[f.key] = values?.[f.key] == null ? '' : String(values[f.key])
  return out
}

/**
 * Form doğrulaması: boş = sınır yok (null); dolu alan TAMSAYI ve aralıkta olmalı. Hata = { key: { code, args } }
 * (`int` | `range`); metin çağıranda (`prs.budget.err.*`).
 */
export function parseBudgetForm(form) {
  const values = {}
  const errors = {}
  for (const f of BUDGET_FIELDS) {
    const raw = String(form?.[f.key] ?? '').trim()
    if (raw === '') { values[f.key] = null; continue }
    // Yerel ayraçlar ("1.024" / "1,024") kabul edilmez: alan tamsayı; boşluk da sayı içinde olmaz
    if (!/^\d{1,9}$/.test(raw)) { errors[f.key] = { code: 'int', args: [] }; continue }
    const n = Number(raw)
    if (n < f.min || n > f.max) { errors[f.key] = { code: 'range', args: [f.min, f.max] }; continue }
    values[f.key] = n
  }
  return { values, errors }
}

/** Yalnız DEĞİŞEN alanlar (null = sınırı kaldır). Değişiklik yoksa {} — çağıran istek göndermez. */
export function budgetChanges(values, base) {
  const out = {}
  for (const f of BUDGET_FIELDS) {
    const v = values?.[f.key] ?? null
    const b = base?.[f.key] ?? null
    if (v !== b) out[f.key] = v
  }
  return out
}

/** Tanımlı sınır sayısı. */
export const budgetDefinedCount = (values) => BUDGET_FIELDS.filter((f) => values?.[f.key] != null).length

/** Eski sunucu (uç yok): 404 / 405. Kodsuz ya da genel NOT_FOUND kodlu 404 — izlemenin geçmişi okunabildiği için. */
export const isBudgetUnsupported = (res) => res?.status === 404 || res?.status === 405

/** Sunucunun alan hatası (`field`) bütçe alanıysa o anahtar; değilse null (genel hata şeridine düşer). */
export function budgetErrorField(res) {
  const f = typeof res?.field === 'string' ? res.field.trim() : ''
  return FIELD[f] ? f : null
}

/** Sınır değerinin metni birimine göre: KB → "2,5 MB", ms → "3,00 sn", sayı → "120", puan → "80 / 100". */
export function formatBudgetLimit(key, v, locale) {
  const f = FIELD[key]
  const n = num(v)
  if (n === null) return '—'
  if (!f) return formatNumber(n, locale, 1)
  if (f.kind === 'kb') return formatSize(n * KB, { locale })
  if (f.kind === 'ms') return formatMs(n, locale)
  if (f.kind === 'score') return `${formatNumber(n, locale)} / 100`
  return formatNumber(n, locale)
}

/**
 * Kalemin GERÇEK değeri: önce analizin özet satırından (birim belirsizliği yok: bayt alanı bayt olarak), yoksa sunucunun
 * `actual`ı (alan biriminde: KB / ms / sayı / puan). İkisi de yoksa null. Dönüş: { value, kind } — `bytes` ya da alan birimi.
 */
export function budgetActual(key, scan, check) {
  const f = FIELD[key]
  const fromScan = f ? num(scan?.[f.scanField]) : null
  if (fromScan !== null) return { value: fromScan, kind: f.kind === 'kb' ? 'bytes' : f.kind }
  const a = num(check?.actual)
  if (a === null) return null
  return { value: a, kind: f ? f.kind : 'count' }
}

/** Gerçek değerin metni. */
export function formatBudgetActual(key, actual, locale) {
  if (!actual) return '—'
  if (actual.kind === 'bytes') return formatSize(actual.value, { locale })
  return formatBudgetLimit(key, actual.value, locale)
}

/**
 * Analiz sonucu bütçe bandı. `budget` = ayrıntı yanıtının alanı; YOKSA null (eski sunucu — bant çizilmez).
 * Durum: `none` (bütçe tanımlı değil), `exceeded` (en az bir kalem aşıldı), `partial` (aşılan yok ama ölçülemeyen var —
 * yeşil DEĞİL), `ok` (her kalem ölçüldü ve geçti). Satırlar sabit alan sırasıyla; bilinmeyen anahtar sona.
 */
export function budgetBand(budget, scan) {
  if (!isObj(budget)) return null
  const checks = arr(budget.checks).filter((c) => isObj(c) && typeof c.key === 'string' && c.key)
  if (budget.defined === false || (!checks.length && !(num(budget.exceeded) > 0))) {
    return { state: 'none', rows: [], exceeded: 0, unknown: 0 }
  }
  const order = (k) => (BUDGET_KEYS.indexOf(k) < 0 ? 99 : BUDGET_KEYS.indexOf(k))
  const rows = [...checks].sort((a, b) => order(a.key) - order(b.key)).map((c) => ({
    key: c.key, known: Boolean(FIELD[c.key]), limit: num(c.limit), actual: budgetActual(c.key, scan, c),
    ok: c.ok === true ? true : c.ok === false ? false : null,
  }))
  const exceeded = rows.filter((r) => r.ok === false).length || (rows.length ? 0 : Math.max(0, num(budget.exceeded) ?? 0))
  const unknown = rows.filter((r) => r.ok === null).length
  const state = exceeded > 0 ? 'exceeded' : unknown > 0 ? 'partial' : 'ok'
  return { state, rows, exceeded, unknown }
}

/**
 * Liste satırının bütçe durumu (geçmiş / karşılaştırma işareti): ok | exceeded | unknown; `none` ve alan yok → null
 * (işaret çizilmez — eski sunucu ya da bütçe tanımsız). Bilinmeyen değer `unknown` (asla "geçti").
 */
export function budgetStatusOf(row) {
  const s = row?.budget_status
  if (s === 'ok' || s === 'exceeded') return s
  if (s === 'unknown') return 'unknown'
  if (s === 'none' || s === null || s === undefined) return null
  return 'unknown'
}

/**
 * Karşılaştırma işareti: iki analizin kategori puanları (özet satırı `security_score` / `seo_score` / `a11y_score`).
 * İki tarafta da bilinmeyen puan atılır (eski analiz — satır yok, 0 uydurulmaz).
 */
export function scanMarkerScores(older, newer) {
  return [['security', 'security_score'], ['seo', 'seo_score'], ['a11y', 'a11y_score']].map(([key, f]) => ({
    key, old: num(older?.[f]), now: num(newer?.[f]),
  })).filter((r) => r.old !== null || r.now !== null)
}

// ── F2 Eğilim ────────────────────────────────────────────────────────────────

/** Eğilim aralıkları (gün) ve varsayılan. */
export const TREND_DAYS = [30, 90, 365]
export const TREND_DEFAULT_DAYS = 90
/** Kategori yığını (bayt): sunucu alanı → seri anahtarı; "Diğer" = sayfa boyutu − bunların toplamı. */
export const TREND_STACK = [
  ['js', 'js_bytes'], ['css', 'css_bytes'], ['image', 'image_bytes'], ['font', 'font_bytes'],
]
export const TREND_STACK_KEYS = [...TREND_STACK.map(([k]) => k), 'other']
/** Kategori puanı serileri (0–100). */
export const TREND_SCORE_KEYS = ['security', 'seo', 'a11y']
/** Büyüyen nesneler listesinde gösterilen en fazla satır. */
export const GROWTH_MAX = 20

/**
 * Sunucunun eğilim noktaları → grafik noktaları (eskiden yeniye). Zaman = bitiş (yoksa başlangıç) damgası; zamanı
 * okunamayan nokta atılır. Kategori yığını YALNIZ dört kategorinin ve sayfa boyutunun hepsi biliniyorsa çizilir — eski
 * analizde (alan yok) yığın BOŞLUKTUR, sıfır değil; sayfa boyutu çizgisi yine görünür.
 */
export function trendSeries(points) {
  const pts = arr(points).filter(isObj).map((p) => {
    const t = toMs(p.finished_at) ?? toMs(p.started_at)
    const size = num(p.page_bytes)
    const parts = Object.fromEntries(TREND_STACK.map(([k, f]) => [k, num(p[f])]))
    const known = size !== null && TREND_STACK.every(([k]) => parts[k] !== null)
    const sum = known ? TREND_STACK.reduce((s, [k]) => s + parts[k], 0) : null
    const other = known && size - sum >= 0 ? size - sum : (known ? 0 : null)
    const grade = num(p.grade)
    return {
      t, id: p.scan_id ?? p.id ?? null, ts: p.finished_at || p.started_at || null,
      algo: num(p.algorithm_version) ?? 1,
      grade, letter: gradeLetter(grade, p.grade_letter), load: num(p.load_ms), size, requests: num(p.request_count),
      js: known ? parts.js : null, css: known ? parts.css : null, image: known ? parts.image : null, font: known ? parts.font : null,
      other: known ? other : null, stackKnown: known,
      security: num(p.security_score), seo: num(p.seo_score), a11y: num(p.a11y_score),
    }
  }).filter((p) => p.t !== null)
  pts.sort((a, b) => a.t - b.t || String(a.id).localeCompare(String(b.id)))
  return pts
}

/** Not algoritması sürümünün DEĞİŞTİĞİ noktalar (öncekiyle farklı) — grafikte dikey işaret: notlar doğrudan kıyaslanamaz. */
export function algoChanges(points) {
  const out = []
  const list = arr(points)
  for (let i = 1; i < list.length; i += 1) {
    if (list[i].algo !== list[i - 1].algo) out.push({ t: list[i].t, id: list[i].id, from: list[i - 1].algo, to: list[i].algo })
  }
  return out
}

/** Bir serinin özeti: ilk ve son bilinen değer + fark (seri hiç bilinmiyorsa null). */
export function seriesSummary(points, key) {
  const known = arr(points).filter((p) => num(p?.[key]) !== null)
  if (!known.length) return null
  const first = num(known[0][key])
  const last = num(known.at(-1)[key])
  return { first, last, diff: last - first, count: known.length }
}

/** Serilerden en az biri bilinen nokta var mı (grafik ya da "bu aralıkta veri yok" kararı). */
export const hasSeries = (points, keys) => arr(points).some((p) => keys.some((k) => num(p?.[k]) !== null))

/**
 * En çok büyüyen nesneler: geçersiz satır atılır, fark yüzdesine (yoksa bayt farkına) göre büyükten küçüğe, en çok
 * GROWTH_MAX. `delta_pct` sunucudan; yoksa ilk / son bayttan hesaplanır (ilk 0 → null — sonsuz yüzde uydurulmaz).
 */
export function growthRows(growth) {
  return arr(growth).filter((g) => isObj(g) && typeof g.url === 'string' && g.url.trim()).map((g) => {
    const first = num(g.first_bytes)
    const last = num(g.last_bytes)
    const pct = num(g.delta_pct) ?? (first !== null && last !== null && first > 0 ? ((last - first) / first) * 100 : null)
    return { url: g.url, category: g.category || 'OTHER', first, last, diff: first !== null && last !== null ? last - first : null, pct, scans: num(g.scans) }
  }).sort((a, b) => (b.pct ?? -Infinity) - (a.pct ?? -Infinity) || (b.diff ?? -Infinity) - (a.diff ?? -Infinity))
    .slice(0, GROWTH_MAX)
}

/** Eğilim ucu desteklenmiyor mu (eski sunucu: 404 / 405). */
export const isTrendUnsupported = (res) => res?.status === 404 || res?.status === 405

// ── G Çok sayfalı (site) analiz ──────────────────────────────────────────────

/** Sayfa sayısı sınırları (sunucu ayarı `site.monitor.page.resource-scan.max-pages`: 10, 2–20). */
export const SITE_MIN_PAGES = 2
export const SITE_DEFAULT_MAX_PAGES = 10
export const SITE_MAX_PAGES_CAP = 20
export const SITE_DEFAULT_MAX_SECONDS = 900
/** Sayfa tablosu ↔ kart eşiği (KAP genişliği, px). jsdom 0 → tablo. */
export const SITE_TABLE_MIN_PX = 760
export const SITE_SORTS = ['grade_asc', 'load_desc', 'size_desc', 'url']
export const SITE_SHARED_MAX = 50

/** Sunucu sınırlarından site analizi sınırları (alan yoksa varsayılan; aralık dışı değer kırpılır). */
export function siteLimits(limits) {
  const mp = int(limits?.max_pages ?? limits?.site_max_pages)
  const ms = num(limits?.site_max_seconds)
  return {
    maxPages: Math.min(SITE_MAX_PAGES_CAP, Math.max(SITE_MIN_PAGES, mp ?? SITE_DEFAULT_MAX_PAGES)),
    maxSeconds: ms !== null && ms > 0 ? ms : SITE_DEFAULT_MAX_SECONDS,
  }
}

/** Sayfa sayısı seçenekleri: 2 … en çok. */
export function sitePageChoices(maxPages) {
  const max = Math.min(SITE_MAX_PAGES_CAP, Math.max(SITE_MIN_PAGES, int(maxPages) ?? SITE_DEFAULT_MAX_PAGES))
  const out = []
  for (let n = SITE_MIN_PAGES; n <= max; n += 1) out.push(n)
  return out
}

/** Varsayılan seçim: 5 (en çok değerini aşmaz). */
export const defaultSitePages = (maxPages) => Math.min(5, sitePageChoices(maxPages).at(-1))

/** Analiz çok sayfalı mı (özet `scope` ya da analizde `site` bloğu). */
export const isSiteScan = (scan, analysis) => scan?.scope === 'site' || isObj(analysis?.site)

/** Site istendi ama sunucu tek sayfa başlattı (eski sunucu `scope`'u bilmiyor). */
export const siteScopeIgnored = (requestedSite, scan) => requestedSite === true && !!scan && scan.scope !== 'site'

/** Sayfanın durumu: ok | failed | skipped (süre / sınır nedeniyle taranmadı) | unknown. */
export function pageStatus(page) {
  const s = String(page?.status ?? '').toLowerCase()
  if (s === 'skipped' || s === 'capped' || s === 'not_scanned') return 'skipped'
  if (s === 'failed' || s === 'error' || (page?.error && s !== 'ok' && s !== 'done')) return 'failed'
  if (s === 'ok' || s === 'done') return 'ok'
  if (!s && !page?.error && num(page?.grade) !== null) return 'ok'
  return 'unknown'
}

const PAGE_STATUS_TONE = { ok: 'success', failed: 'danger', skipped: 'muted', unknown: 'muted' }
export const pageStatusTone = (status) => PAGE_STATUS_TONE[status] || 'muted'

/**
 * `analysis.site` → ekran modeli: sayfalar (sunucu sırası = tarama sırası; ilk sayfa izlemenin sayfası), en kötü üç
 * sayfa (YALNIZ taranmış sayfalar arasından, notu en düşük), ortak kaynaklar (bayta göre, en çok 50) ve toplamlar.
 * Blok yoksa null.
 */
export function siteModel(site) {
  if (!isObj(site)) return null
  const pages = arr(site.pages).filter(isObj).map((p, i) => {
    const grade = num(p.grade)
    const status = pageStatus(p)
    return {
      i, url: typeof p.url === 'string' ? p.url : '', status, error: p.error || null, grade,
      letter: status === 'ok' ? gradeLetter(grade, p.letter ?? p.grade_letter) : null,
      load: num(p.load_ms), size: num(p.page_bytes), requests: num(p.requests ?? p.request_count),
      security: num(p.scores?.security), seo: num(p.scores?.seo), a11y: num(p.scores?.accessibility),
      recs: arr(p.top_recs).filter((c) => typeof c === 'string' && c).slice(0, 5),
      unique: num(p.unique_bytes), shared: num(p.shared_count), main: i === 0,
    }
  })
  const scanned = pages.filter((p) => p.status === 'ok' && p.grade !== null)
  const worst = scanned.length >= 2
    ? [...scanned].sort((a, b) => a.grade - b.grade || a.i - b.i).slice(0, Math.min(3, scanned.length - 1))
    : []
  const shared = arr(site.shared).filter((s) => isObj(s) && typeof s.url === 'string' && s.url)
    .map((s) => ({ url: s.url, category: s.category || 'OTHER', bytes: num(s.bytes), pages: num(s.pages) }))
    .sort((a, b) => (b.bytes ?? -1) - (a.bytes ?? -1) || (b.pages ?? 0) - (a.pages ?? 0)).slice(0, SITE_SHARED_MAX)
  const tt = isObj(site.totals) ? site.totals : {}
  const totals = {
    pages: num(tt.pages) ?? pages.length,
    scanned: num(tt.scanned) ?? scanned.length,
    failed: num(tt.failed) ?? pages.filter((p) => p.status === 'failed').length,
    avgGrade: num(tt.avg_grade),
    uniqueBytes: num(tt.total_unique_bytes),
    sharedCount: arr(site.shared).length,
  }
  return { pages, worst, shared, totals }
}

/** Sayfa sıralaması: not artan (en kötü önce; notsuz sona), süre / boyut azalan, adres. Eşitlikte tarama sırası. */
export function sortSitePages(pages, sort = 'grade_asc', locale) {
  const list = arr(pages).slice()
  const coll = new Intl.Collator(locale || undefined, { sensitivity: 'base', numeric: true })
  const nil = (v, dir) => (v === null ? (dir > 0 ? Infinity : -Infinity) : v)
  const cmp = {
    grade_asc: (a, b) => nil(a.grade, 1) - nil(b.grade, 1) || a.i - b.i,
    load_desc: (a, b) => nil(b.load, -1) - nil(a.load, -1) || a.i - b.i,
    size_desc: (a, b) => nil(b.size, -1) - nil(a.size, -1) || a.i - b.i,
    url: (a, b) => coll.compare(a.url, b.url) || a.i - b.i,
  }[SITE_SORTS.includes(sort) ? sort : 'grade_asc']
  return list.sort(cmp)
}

/**
 * Önerinin KISA adı (site sayfası çipleri, yönetici listesi): `prs.site.rec.<KOD>`; bilinmeyen kod genel "Diğer
 * öneri" (ham kod gösterilmez).
 */
export function recShortLabel(code, t) {
  const key = `prs.site.rec.${code}`
  const v = typeof code === 'string' && /^[A-Z0-9_]+$/.test(code) ? t(key) : key
  return typeof v === 'string' && v && v !== key ? v : t('prs.site.rec.other')
}

// ── F3 Yönetici özeti (PDF / Excel) ──────────────────────────────────────────

/** Kategori puanları (sunucu `category_scores`; yoksa ilgili bölüm / özet satırı). Hesaplanamayan = null — 0 uydurulmaz. */
export const CATEGORY_SCORE_KEYS = ['performance', 'security', 'seo', 'accessibility']

export function categoryScoreRows(analysis, scan) {
  const cs = isObj(analysis?.category_scores) ? analysis.category_scores : {}
  const pick = (k, ...fallbacks) => {
    const v = isObj(cs[k]) ? num(cs[k].score) : num(cs[k])
    if (v !== null) return { score: v, letter: isObj(cs[k]) ? cs[k].letter ?? null : null }
    for (const f of fallbacks) if (num(f) !== null) return { score: num(f), letter: null }
    return { score: null, letter: null }
  }
  const rows = {
    performance: pick('performance', scan?.grade),
    security: pick('security', analysis?.security?.score, scan?.security_score),
    seo: pick('seo', analysis?.html_audit?.seo?.score, scan?.seo_score),
    accessibility: pick('accessibility', analysis?.html_audit?.accessibility?.score, scan?.a11y_score),
  }
  if (rows.performance.letter == null) rows.performance.letter = gradeLetter(rows.performance.score, scan?.grade_letter)
  if (rows.security.letter == null && isObj(analysis?.security)) rows.security.letter = analysis.security.letter ?? null
  return CATEGORY_SCORE_KEYS.map((key) => {
    const r = rows[key]
    const letter = typeof r.letter === 'string' && /^[A-F]$/.test(r.letter.trim()) ? r.letter.trim() : null
    // Sınırlı (kısmi) ölçümün performans notu yeşil değil
    const tone = key === 'performance' && isCapped(scan) ? 'muted' : letter ? gradeTone(letter) : scoreTone(r.score)
    return { key, score: r.score, letter, tone }
  })
}

const SEV_RANK = (s) => (SEVERITIES.indexOf(s) < 0 ? 9 : SEVERITIES.indexOf(s))

/**
 * Öncelikli eylem listesi: öneriler önem sırasına (yüksek → düşük), eşitlikte olası kazanca (süre, sonra bayt) göre;
 * "her şey iyi" (ALL_GOOD) eylem değildir. En çok `n`.
 */
export function priorityActions(recs, n = 10) {
  return arr(recs).filter((r) => isObj(r) && r.code && r.code !== 'ALL_GOOD').map((r, i) => ({ r, i }))
    .sort((a, b) => SEV_RANK(a.r.severity) - SEV_RANK(b.r.severity)
      || (num(b.r.saving_ms) ?? 0) - (num(a.r.saving_ms) ?? 0)
      || (num(b.r.saving_bytes) ?? 0) - (num(a.r.saving_bytes) ?? 0) || a.i - b.i)
    .slice(0, n).map((x) => x.r)
}

/** Eylem satırı metinleri: önem, başlık (i18n + parametreler), kazanç. */
export function actionRow(rec, t, locale) {
  const sev = SEVERITIES.includes(rec?.severity) ? rec.severity : 'low'
  const b = num(rec?.saving_bytes)
  const m = num(rec?.saving_ms)
  const saving = [m !== null && m > 0 ? formatMs(m, locale) : null, b !== null && b > 0 ? formatSize(b, { locale }) : null].filter(Boolean).join(' · ')
  return { severity: sev, sevText: t(`prs.sev.${sev}`), title: t(recTextKey(rec, 'title'), ...recommendationArgs(rec, locale, 'title')), saving }
}

/**
 * Sayfa yükü kategori baytları (yönetici grafiği): yalnız sayfa yüküne sayılan kategoriler, bayta göre büyükten küçüğe;
 * paylar toplam baytın yüzdesi. Bayt bilinmiyorsa atılır.
 */
export function categoryBytesChart(categories) {
  const rows = arr(categories).filter((c) => isObj(c) && c.category && !NOT_PAGE_LOAD.has(c.category))
    .map((c) => ({ category: CATEGORIES.includes(c.category) ? c.category : 'OTHER', bytes: num(c.bytes) }))
    .filter((r) => r.bytes !== null && r.bytes > 0)
  const total = rows.reduce((s, r) => s + r.bytes, 0)
  return rows.sort((a, b) => b.bytes - a.bytes).map((r) => ({ ...r, pct: total > 0 ? (r.bytes / total) * 100 : 0 }))
}

/** Önceki analizle karşılaştırma satırları (PDF): not, süre, boyut, istek, güvenlik puanı. Değer bilinmiyorsa satır yok. */
export const EXEC_COMPARE = [
  ['grade', 'grade', false], ['load', 'load_ms', true], ['size', 'page_bytes', true], ['requests', 'request_count', true],
  ['security', 'security_score', false],
]
export function compareRows(scan, prev) {
  if (!scan || !prev) return []
  return EXEC_COMPARE.map(([key, field, lowerIsBetter]) => {
    const now = num(scan[field])
    const old = num(prev[field])
    if (now === null || old === null) return null
    const diff = now - old
    const pct = old !== 0 ? (diff / Math.abs(old)) * 100 : null
    const same = diff === 0 || (pct !== null && Math.abs(pct) < 1)
    return { key, old, now, diff, direction: same ? 'same' : ((lowerIsBetter ? diff < 0 : diff > 0) ? 'better' : 'worse') }
  }).filter(Boolean)
}

/** Karşılaştırma değerinin metni. */
export function compareValueText(key, v, locale) {
  if (key === 'load') return formatMs(v, locale)
  if (key === 'size') return formatSize(v, { locale })
  return formatNumber(v, locale)
}

/** Kategori puanının metni ("72 / 100 · C"; hesaplanamayan "—"). */
export function categoryScoreText(row, locale) {
  if (!row || row.score === null) return '—'
  return `${formatNumber(row.score, locale)} / 100${row.letter ? ` · ${row.letter}` : ''}`
}

/**
 * Bütçenin dışa aktarım satırları: [kalem, sınır, gerçek, durum] + özet cümlesi. `budget` yoksa (eski sunucu) `null`
 * döner — çağıran "bu analizde bütçe bilgisi yok" yazar; tanımsız bütçe ayrı cümle.
 */
export function budgetExportRows(budget, scan, t, locale) {
  const band = budgetBand(budget, scan)
  if (!band) return null
  const summary = band.state === 'none' ? t('prs.budget.band.none')
    : band.state === 'exceeded' ? plural(t, 'prs.budget.band.exceeded', band.exceeded, formatNumber(band.exceeded, locale))
      : band.state === 'partial' ? plural(t, 'prs.budget.band.partial', band.unknown, formatNumber(band.unknown, locale))
        : plural(t, 'prs.budget.band.ok', band.rows.length, formatNumber(band.rows.length, locale))
  const rows = band.rows.map((r) => {
    const f = FIELD[r.key]
    return [
      r.known ? t(`prs.budget.f.${r.key}`) : t('prs.budget.f.other'),
      `${f?.dir === 'min' ? '≥' : '≤'} ${formatBudgetLimit(r.key, r.limit, locale)}`,
      formatBudgetActual(r.key, r.actual, locale),
      t(`prs.budget.state.${r.ok === true ? 'ok' : r.ok === false ? 'exceeded' : 'unknown'}`),
    ]
  })
  return { state: band.state, summary, rows }
}

/** Karşılaştırma satırının metinleri: [metrik, önceki, bu analiz, değişim]. */
export function compareExportRows(scan, prev, t, locale) {
  return compareRows(scan, prev).map((r) => [
    t(`prs.pdf.cmp.${r.key}`), compareValueText(r.key, r.old, locale), compareValueText(r.key, r.now, locale),
    r.direction === 'same' ? t('prs.delta.same')
      : `${r.diff < 0 ? '−' : '+'}${compareValueText(r.key, Math.abs(r.diff), locale)} · ${t(`prs.pdf.dir.${r.direction}`)}`,
  ])
}

/**
 * Excel "Yönetici özeti" sayfası: kategori puanları, bütçe, öncelikli eylemler, önceki analizle karşılaştırma ve A–E
 * bölümlerinin özet cümleleri (yoksa "bu analizde yok — yeniden analiz edin").
 */
export function executiveSheetRows({ scan, analysis, budget, prev, t, locale }) {
  const rows = [[t('prs.pdf.execTitle')]]
  const head = (title) => { rows.push([], [title]) }
  head(t('prs.pdf.catScores'))
  for (const r of categoryScoreRows(analysis, scan)) rows.push([t(`prs.pdf.cat.${r.key}`), categoryScoreText(r, locale)])
  head(t('prs.pdf.budgetTitle'))
  const b = budgetExportRows(budget, scan, t, locale)
  if (!b) rows.push([t('prs.pdf.budgetMissing')])
  else {
    rows.push([b.summary])
    if (b.rows.length) rows.push([t('prs.pdf.col.item'), t('prs.pdf.col.limit'), t('prs.pdf.col.actual'), t('prs.pdf.col.state')], ...b.rows)
  }
  head(t('prs.pdf.actionsTitle'))
  const acts = priorityActions(analysis?.recommendations)
  if (!acts.length) rows.push([t('prs.pdf.actionsNone')])
  else {
    rows.push([t('prs.export.col.severity'), t('prs.pdf.col.action'), t('prs.export.col.saving')])
    for (const r of acts) { const a = actionRow(r, t, locale); rows.push([a.sevText, a.title, a.saving]) }
  }
  head(t('prs.pdf.compareTitle'))
  const cmp = compareExportRows(scan, prev, t, locale)
  if (!prev) rows.push([t('prs.pdf.compareNone')])
  else if (!cmp.length) rows.push([t('prs.pdf.compareNoValues')])
  else rows.push([t('prs.pdf.col.metric'), t('prs.pdf.col.previous'), t('prs.pdf.col.current'), t('prs.pdf.col.change')], ...cmp)
  head(t('prs.pdf.deepTitle'))
  const secs = executiveSections(analysis, t, locale)
  if (!secs.length) rows.push([t('prs.pdf.deepMissing')])
  for (const sec of secs) {
    rows.push([sec.title])
    for (const line of sec.lines) rows.push(['', line])
  }
  return rows
}

/** Güvenlik başlığı anahtarı → gerçek başlık adı (teknik ad; çevrilmez). */
export const SECURITY_HEADER_NAMES = {
  csp: 'Content-Security-Policy', hsts: 'Strict-Transport-Security', x_content_type_options: 'X-Content-Type-Options',
  frame_protection: 'X-Frame-Options / frame-ancestors', referrer_policy: 'Referrer-Policy', permissions_policy: 'Permissions-Policy',
  coop: 'Cross-Origin-Opener-Policy', corp: 'Cross-Origin-Resource-Policy',
}
export const headerName = (key) => SECURITY_HEADER_NAMES[key] || String(key || '')

/** SEO / erişilebilirlik denetiminin bilinen anahtarları (sunucu sözleşmesi E). */
export const SEO_CHECK_KEYS = ['title', 'meta_description', 'h1', 'canonical', 'robots', 'lang', 'viewport', 'open_graph', 'structured_data']
export const A11Y_CHECK_KEYS = ['img_alt', 'form_labels', 'link_names', 'button_names', 'heading_order', 'html_lang', 'duplicate_ids']
const CHECK_KEYS = new Set([...SEO_CHECK_KEYS, ...A11Y_CHECK_KEYS])

/**
 * Denetim adı: önce pano ekranının metni (`prs.check.<alan>.<anahtar>` ya da `prs.check.<anahtar>` — fe1 ailesi),
 * yoksa raporun kendi metni (`prs.pdf.chk.<anahtar>`); bilinmeyen anahtar genel "Diğer denetim". Yer tutuculu metin
 * kabul edilmez (ad değil, cümle).
 */
export function checkLabel(t, area, key) {
  const k = String(key || '')
  if (!/^[a-z0-9_]+$/.test(k)) return t('prs.pdf.chk.other')
  const tries = [`prs.check.${area}.${k}`, `prs.check.${k}`]
  if (CHECK_KEYS.has(k)) tries.push(`prs.pdf.chk.${k}`)
  for (const key2 of tries) {
    const v = t(key2)
    if (typeof v === 'string' && v && v !== key2 && !/\{\d\}/.test(v)) return v
  }
  return t('prs.pdf.chk.other')
}

const sumBytes = (list, field) => arr(list).reduce((s, x) => s + (num(x?.[field]) ?? 0), 0)

/**
 * A–E bölümlerinin YÖNETİCİ ÖZETİ satırları (PDF): yalnız analizde OLAN bölümler; her bölüm { key, title, lines }.
 * Eski analiz (algoritma 1 / 2) bu alanları taşımaz → dizi boş (çağıran "bu analizde yok" der; "sorun yok" demez).
 */
export function executiveSections(analysis, t, locale) {
  const a = isObj(analysis) ? analysis : {}
  const out = []
  const sz = (b) => formatSize(b, { locale })
  const n = (v) => formatNumber(v, locale)

  if (isObj(a.loading)) {
    const L = a.loading
    const lines = []
    const rb = isObj(L.render_blocking) ? L.render_blocking : null
    if (rb) {
      const c = num(rb.count) ?? arr(rb.items).length
      lines.push(c > 0 ? plural(t, 'prs.pdf.load.blocking', c, n(c), sz(num(rb.bytes) ?? sumBytes(rb.items, 'bytes'))) : t('prs.pdf.load.blockingNone'))
      if (num(rb.est_first_paint_ms) !== null) lines.push(t('prs.pdf.load.firstPaint', formatMs(rb.est_first_paint_ms, locale)))
    }
    const mp = arr(L.hints?.missing_preconnect).length
    if (mp > 0) lines.push(plural(t, 'prs.pdf.load.missingPreconnect', mp, n(mp)))
    const unusedPreload = arr(L.hints?.preload).filter((p) => p?.used === false).length
    if (unusedPreload > 0) lines.push(plural(t, 'prs.pdf.load.unusedPreload', unusedPreload, n(unusedPreload)))
    if (num(L.hosts_total) !== null) lines.push(plural(t, 'prs.pdf.load.hosts', num(L.hosts_total), n(L.hosts_total)))
    if (num(L.http1_first_party_hosts) > 0) lines.push(plural(t, 'prs.pdf.load.http1', num(L.http1_first_party_hosts), n(L.http1_first_party_hosts)))
    const prof = arr(L.profiles).filter((p) => isObj(p) && p.key && num(p.load_ms) !== null)
    if (prof.length) {
      lines.push(t('prs.pdf.load.profiles', prof.map((p) => `${profileLabel(p.key, t)} ${formatMs(p.load_ms, locale)}`).join(' · ')))
    }
    out.push({ key: 'loading', title: t('prs.pdf.sec.loading'), lines })
  }

  if (isObj(a.waste)) {
    const W = a.waste
    const lines = []
    const dup = arr(W.duplicates)
    if (dup.length) lines.push(plural(t, 'prs.pdf.waste.duplicates', dup.length, n(dup.length), sz(sumBytes(dup, 'bytes_wasted'))))
    const libs = arr(W.libraries).filter((l) => isObj(l) && l.name)
    if (libs.length) lines.push(t('prs.pdf.waste.libraries', libs.map((l) => `${l.name} (${arr(l.versions).join(', ')})`).join('; ')))
    const um = arr(W.unminified)
    if (um.length) lines.push(plural(t, 'prs.pdf.waste.unminified', um.length, n(um.length), sz(sumBytes(um, 'est_saving_bytes'))))
    const maps = arr(W.source_maps).filter((m) => m?.exposed === true).length
    if (maps > 0) lines.push(plural(t, 'prs.pdf.waste.sourceMaps', maps, n(maps)))
    if (isObj(W.html)) {
      const h = W.html
      lines.push(t('prs.pdf.waste.html', sz(h.html_bytes), n(h.dom_nodes), n(h.max_depth)))
    }
    if (isObj(W.caching)) {
      const c = W.caching
      const b = isObj(c.buckets) ? c.buckets : {}
      lines.push(t('prs.pdf.waste.cacheBuckets', n(b.none ?? 0), n(b.lt_1h ?? 0), n(b.lt_1d ?? 0), n(b.lt_7d ?? 0), n(b.ge_7d ?? 0)))
      if (num(c.repeat_visit_bytes) !== null) {
        lines.push(t('prs.pdf.waste.repeatVisit', sz(c.repeat_visit_bytes), formatPct(c.repeat_visit_pct, locale, 0)))
      }
    }
    out.push({ key: 'waste', title: t('prs.pdf.sec.waste'), lines })
  }

  if (isObj(a.media)) {
    const M = a.media
    const lines = []
    const im = isObj(M.images) ? M.images : {}
    const parts = [
      ['missing_dimensions', 'prs.pdf.media.missingDimensions'], ['missing_srcset', 'prs.pdf.media.missingSrcset'],
      ['not_lazy', 'prs.pdf.media.notLazy'], ['animated_gif', 'prs.pdf.media.animatedGif'],
    ]
    for (const [k, key] of parts) {
      const c = arr(im[k]).length
      if (c > 0) lines.push(plural(t, key, c, n(c)))
    }
    if (isObj(M.fonts)) {
      const f = M.fonts
      const fc = num(f.count) ?? 0
      lines.push(plural(t, 'prs.pdf.media.fonts', fc, n(fc), sz(f.bytes), n(arr(f.families).length)))
      const nw = arr(f.non_woff2).length
      if (nw > 0) lines.push(plural(t, 'prs.pdf.media.nonWoff2', nw, n(nw)))
      const md = arr(f.missing_display).length
      if (md > 0) lines.push(plural(t, 'prs.pdf.media.missingDisplay', md, n(md)))
    }
    if (!lines.length) lines.push(t('prs.pdf.media.noFindings'))
    out.push({ key: 'media', title: t('prs.pdf.sec.media'), lines })
  }

  if (isObj(a.security)) {
    const S = a.security
    const lines = []
    if (num(S.score) !== null) lines.push(t('prs.pdf.secu.score', n(S.score), S.letter ? ` (${S.letter})` : ''))
    const hdr = arr(S.headers).filter(isObj)
    const missing = hdr.filter((h) => h.status === 'missing').map((h) => headerName(h.key))
    const weak = hdr.filter((h) => h.status === 'weak').map((h) => headerName(h.key))
    if (missing.length) lines.push(t('prs.pdf.secu.missingHeaders', missing.join(', ')))
    if (weak.length) lines.push(t('prs.pdf.secu.weakHeaders', weak.join(', ')))
    const sri = arr(S.sri_missing).length
    if (sri > 0) lines.push(plural(t, 'prs.pdf.secu.sri', sri, n(sri)))
    const mixed = arr(S.mixed_content).length
    if (mixed > 0) lines.push(plural(t, 'prs.pdf.secu.mixed', mixed, n(mixed)))
    const cookies = arr(S.cookies).filter(isObj)
    const bad = cookies.filter((c) => arr(c.issues).length > 0).length
    if (cookies.length) lines.push(plural(t, 'prs.pdf.secu.cookies', cookies.length, n(cookies.length), n(bad)))
    const vuln = arr(S.libraries).filter((l) => isObj(l) && arr(l.vulns).length > 0)
    if (vuln.length) {
      lines.push(t('prs.pdf.secu.vulnLibs', vuln.map((l) => `${l.name} ${l.version || ''}`.trim()).join(', ')))
    }
    const track = arr(S.tracking_on_load).length
    if (track > 0) lines.push(plural(t, 'prs.pdf.secu.tracking', track, n(track)))
    if (!lines.length) lines.push(t('prs.pdf.secu.noFindings'))
    out.push({ key: 'security', title: t('prs.pdf.sec.security'), lines })
  }

  if (isObj(a.html_audit)) {
    const H = a.html_audit
    const lines = []
    for (const [area, block, titleKey] of [['seo', H.seo, 'prs.pdf.audit.seo'], ['a11y', H.accessibility, 'prs.pdf.audit.a11y']]) {
      if (!isObj(block)) continue
      const checks = arr(block.checks).filter(isObj)
      const fail = checks.filter((c) => c.status === 'fail').map((c) => checkLabel(t, area, c.key))
      const warn = checks.filter((c) => c.status === 'warn').map((c) => checkLabel(t, area, c.key))
      const score = num(block.score) !== null ? `${n(block.score)} / 100` : '—'
      lines.push(t(titleKey, score))
      if (fail.length) lines.push(t('prs.pdf.audit.fail', fail.join(', ')))
      if (warn.length) lines.push(t('prs.pdf.audit.warn', warn.join(', ')))
    }
    if (!lines.length) lines.push(t('prs.pdf.audit.none'))
    out.push({ key: 'html_audit', title: t('prs.pdf.sec.audit'), lines })
  }
  return out
}

/** Bağlantı profili adı (fiber / 4G / 3G / yavaş 3G). */
export function profileLabel(key, t) {
  return ['fiber', '4g', '3g', 'slow3g'].includes(key) ? t(`prs.pdf.profile.${key}`) : String(key)
}

const yn = (v, t) => (v === true ? t('prs.export.yes') : v === false ? t('prs.export.no') : '')

/**
 * Excel A–E sayfaları: analizde OLAN her bölüm için bir sayfa; sayfa, alt başlıklı bloklardan oluşur (blok başlığı, sütun
 * başlıkları, satırlar, boş ayraç). Liste sunucuda ≤ 50 satırdır; fazlası `*_total` ile söylenir. Bölüm yoksa sayfa yok.
 */
export function deepSheets(analysis, t, locale) {
  const a = isObj(analysis) ? analysis : {}
  const sheets = []
  const block = (rows, title, head, body, total) => {
    if (rows.length) rows.push([])
    rows.push([title])
    if (!body.length) { rows.push([t('prs.pdf.xl.none')]); return }
    rows.push(head)
    for (const r of body) rows.push(r)
    if (num(total) !== null && num(total) > body.length) rows.push([t('prs.pdf.xl.more', formatNumber(num(total) - body.length, locale))])
  }

  if (isObj(a.loading)) {
    const L = a.loading
    const rows = []
    const rb = isObj(L.render_blocking) ? L.render_blocking : {}
    block(rows, t('prs.pdf.xl.renderBlocking'), [t('prs.export.col.url'), t('prs.pdf.xl.kind'), t('prs.export.col.bytes'), t('prs.export.col.duration'), t('prs.pdf.xl.reason')],
      arr(rb.items).filter(isObj).map((i) => [i.url ?? '', i.kind ?? '', num(i.bytes), num(i.duration_ms), i.reason ?? '']), rb.count)
    block(rows, t('prs.pdf.xl.hosts'), [t('prs.export.col.host'), t('prs.export.col.thirdParty'), t('prs.export.col.count'), t('prs.export.col.bytes'),
      t('prs.pdf.xl.httpVersion'), t('prs.pdf.xl.dns'), t('prs.pdf.xl.connect'), t('prs.pdf.xl.tls'), t('prs.pdf.xl.measured')],
    arr(L.hosts).filter(isObj).map((h) => [h.host ?? '', yn(h.third_party, t), num(h.requests), num(h.bytes), h.http_version ?? '', num(h.dns_ms),
      num(h.connect_ms), num(h.tls_ms), h.measured === false ? (h.reason ? `${t('prs.export.no')} (${h.reason})` : t('prs.export.no')) : yn(h.measured, t)]), L.hosts_total)
    block(rows, t('prs.pdf.xl.missingPreconnect'), [t('prs.export.col.host'), t('prs.export.col.count'), t('prs.export.col.bytes')],
      arr(L.hints?.missing_preconnect).filter(isObj).map((h) => [h.host ?? '', num(h.requests), num(h.bytes)]))
    block(rows, t('prs.pdf.xl.profiles'), [t('prs.pdf.xl.profile'), t('prs.pdf.xl.rtt'), t('prs.pdf.xl.mbps'), t('prs.pdf.xl.loadMs'), t('prs.pdf.xl.firstPaintMs')],
      arr(L.profiles).filter(isObj).map((p) => [profileLabel(p.key, t), num(p.rtt_ms), num(p.mbps), num(p.load_ms), num(p.first_paint_ms)]))
    sheets.push({ key: 'loading', name: t('prs.pdf.sheet.loading'), rows, widths: [70, 14, 14, 14, 16, 12, 12, 12, 16] })
  }

  if (isObj(a.waste)) {
    const W = a.waste
    const rows = []
    block(rows, t('prs.pdf.xl.duplicates'), [t('prs.pdf.xl.kind'), t('prs.pdf.xl.urls'), t('prs.pdf.xl.wasted')],
      arr(W.duplicates).filter(isObj).map((d) => [d.kind ?? '', arr(d.urls).join('\n'), num(d.bytes_wasted)]))
    block(rows, t('prs.pdf.xl.libraries'), [t('prs.pdf.xl.library'), t('prs.pdf.xl.versions'), t('prs.pdf.xl.urls')],
      arr(W.libraries).filter(isObj).map((l) => [l.name ?? '', arr(l.versions).join(', '), arr(l.urls).join('\n')]))
    block(rows, t('prs.pdf.xl.unminified'), [t('prs.export.col.url'), t('prs.export.col.bytes'), t('prs.pdf.xl.saving')],
      arr(W.unminified).filter(isObj).map((u) => [u.url ?? '', num(u.bytes), num(u.est_saving_bytes)]))
    block(rows, t('prs.pdf.xl.sourceMaps'), [t('prs.export.col.url'), t('prs.pdf.xl.mapUrl'), t('prs.pdf.xl.exposed')],
      arr(W.source_maps).filter(isObj).map((m) => [m.url ?? '', m.map_url ?? '', yn(m.exposed, t)]))
    if (isObj(W.caching)) {
      const b = isObj(W.caching.buckets) ? W.caching.buckets : {}
      block(rows, t('prs.pdf.xl.caching'), [t('prs.pdf.xl.bucket'), t('prs.export.col.count')],
        [['none', b.none], ['lt_1h', b.lt_1h], ['lt_1d', b.lt_1d], ['lt_7d', b.lt_7d], ['ge_7d', b.ge_7d]]
          .map(([k, v]) => [t(`prs.pdf.cache.${k}`), num(v) ?? 0]))
    }
    sheets.push({ key: 'waste', name: t('prs.pdf.sheet.waste'), rows, widths: [40, 80, 18] })
  }

  if (isObj(a.media)) {
    const M = a.media
    const im = isObj(M.images) ? M.images : {}
    const rows = []
    for (const [k, key] of [['missing_dimensions', 'prs.pdf.xl.missingDimensions'], ['missing_srcset', 'prs.pdf.xl.missingSrcset'],
      ['not_lazy', 'prs.pdf.xl.notLazy'], ['animated_gif', 'prs.pdf.xl.animatedGif']]) {
      block(rows, t(key), [t('prs.export.col.url'), t('prs.pdf.xl.detail')],
        arr(im[k]).filter(isObj).map((x) => [x.url ?? '', k === 'missing_srcset' ? (num(x.width) !== null ? `${x.width} px` : '')
          : k === 'animated_gif' ? [num(x.bytes) !== null ? formatSize(x.bytes, { locale }) : '', num(x.frames) !== null ? t('prs.pdf.xl.frames', formatNumber(x.frames, locale)) : ''].filter(Boolean).join(' · ') : '']))
    }
    if (isObj(M.fonts)) {
      const f = M.fonts
      block(rows, t('prs.pdf.xl.fontFamilies'), [t('prs.pdf.xl.family'), t('prs.pdf.xl.weights'), t('prs.pdf.xl.files')],
        arr(f.families).filter(isObj).map((x) => [x.family ?? '', arr(x.weights).join(', '), num(x.files)]))
      block(rows, t('prs.pdf.xl.nonWoff2'), [t('prs.export.col.url'), t('prs.pdf.xl.format')],
        arr(f.non_woff2).filter(isObj).map((x) => [x.url ?? '', x.format ?? '']))
      block(rows, t('prs.pdf.xl.missingDisplay'), [t('prs.pdf.xl.family'), t('prs.pdf.xl.cssUrl')],
        arr(f.missing_display).filter(isObj).map((x) => [x.family ?? '', x.css_url ?? '']))
    }
    sheets.push({ key: 'media', name: t('prs.pdf.sheet.media'), rows, widths: [80, 40, 12] })
  }

  if (isObj(a.security)) {
    const S = a.security
    const rows = []
    block(rows, t('prs.pdf.xl.headers'), [t('prs.pdf.xl.header'), t('prs.pdf.xl.status'), t('prs.pdf.xl.value'), t('prs.pdf.xl.issue')],
      arr(S.headers).filter(isObj).map((h) => [headerName(h.key), t(`prs.pdf.hstatus.${['ok', 'weak', 'missing'].includes(h.status) ? h.status : 'unknown'}`),
        h.value ?? '', h.issue ?? '']))
    block(rows, t('prs.pdf.xl.sri'), [t('prs.export.col.url'), t('prs.pdf.xl.kind')],
      arr(S.sri_missing).filter(isObj).map((x) => [x.url ?? '', x.kind ?? '']))
    block(rows, t('prs.pdf.xl.mixed'), [t('prs.export.col.url')], arr(S.mixed_content).filter(isObj).map((x) => [x.url ?? '']))
    block(rows, t('prs.pdf.xl.cookies'), [t('prs.pdf.xl.cookie'), t('prs.export.col.host'), t('prs.export.col.thirdParty'), 'Secure', 'HttpOnly', 'SameSite', t('prs.pdf.xl.issue')],
      arr(S.cookies).filter(isObj).map((c) => [c.name ?? '', c.host ?? '', yn(c.third_party, t), yn(c.secure, t), yn(c.http_only, t), c.same_site ?? '', arr(c.issues).join(', ')]))
    block(rows, t('prs.pdf.xl.libraries'), [t('prs.pdf.xl.library'), t('prs.pdf.xl.version'), t('prs.export.col.url'), t('prs.pdf.xl.vulns')],
      arr(S.libraries).filter(isObj).map((l) => [l.name ?? '', l.version ?? '', l.url ?? '',
        arr(l.vulns).filter(isObj).map((v) => [v.id, v.severity].filter(Boolean).join(' · ')).join('\n')]))
    block(rows, t('prs.pdf.xl.thirdParty'), [t('prs.pdf.xl.category'), t('prs.pdf.xl.hostsCol'), t('prs.export.col.count'), t('prs.export.col.bytes')],
      arr(S.third_party_categories).filter(isObj).map((c) => [c.category ?? '', arr(c.hosts).join(', '), num(c.requests), num(c.bytes)]))
    block(rows, t('prs.pdf.xl.tracking'), [t('prs.export.col.host'), t('prs.pdf.xl.category')],
      arr(S.tracking_on_load).filter(isObj).map((x) => [x.host ?? '', x.category ?? '']))
    if (S.catalog_date) rows.push([], [t('prs.pdf.xl.catalogDate', String(S.catalog_date))])
    sheets.push({ key: 'security', name: t('prs.pdf.sheet.security'), rows, widths: [40, 30, 60, 50, 12, 12, 30] })
  }

  if (isObj(a.html_audit)) {
    const H = a.html_audit
    const rows = []
    for (const [area, b, key] of [['seo', H.seo, 'prs.pdf.xl.seo'], ['a11y', H.accessibility, 'prs.pdf.xl.a11y']]) {
      if (!isObj(b)) continue
      block(rows, t(key, num(b.score) !== null ? formatNumber(b.score, locale) : '—'),
        [t('prs.pdf.xl.check'), t('prs.pdf.xl.status'), t('prs.pdf.xl.value'), t('prs.export.col.count'), t('prs.pdf.xl.samples')],
        arr(b.checks).filter(isObj).map((c) => [checkLabel(t, area, c.key), t(`prs.pdf.status.${['pass', 'warn', 'fail'].includes(c.status) ? c.status : 'unknown'}`),
          c.value == null ? '' : String(c.value), num(c.count), arr(c.samples).join('\n')]))
    }
    sheets.push({ key: 'html_audit', name: t('prs.pdf.sheet.audit'), rows, widths: [36, 14, 50, 10, 60] })
  }
  return sheets
}

/** Excel "Sayfalar" sayfası (site analizi). */
export function sitePageSheetRows(model, t) {
  return [
    [t('prs.export.col.url'), t('prs.site.col.status'), t('prs.grade'), t('prs.site.col.letter'), t('prs.kpi.load'), t('prs.export.col.bytes'),
      t('prs.kpi.requests'), t('prs.site.score.security'), t('prs.site.score.seo'), t('prs.site.score.a11y'), t('prs.site.col.unique'),
      t('prs.site.col.shared'), t('prs.site.col.recs'), t('prs.site.col.problem')],
    ...arr(model?.pages).map((p) => [p.url, t(`prs.site.status.${p.status}`), p.grade, p.letter || '', p.load, p.size, p.requests,
      p.security, p.seo, p.a11y, p.unique, p.shared, p.recs.map((c) => recShortLabel(c, t)).join(', '), p.error || '']),
  ]
}

/** Excel "Ortak kaynaklar" sayfası (site analizi). */
export function siteSharedSheetRows(model, t) {
  return [
    [t('prs.export.col.url'), t('prs.export.col.category'), t('prs.export.col.bytes'), t('prs.site.col.pages')],
    ...arr(model?.shared).map((s) => [s.url, categoryLabel(s.category, t), s.bytes, s.pages]),
  ]
}
