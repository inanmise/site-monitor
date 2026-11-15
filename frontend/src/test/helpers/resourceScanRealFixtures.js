import onePage1 from './resourceScanReal/onePage1.json'
import onePage2 from './resourceScanReal/onePage2.json'
import oneSite from './resourceScanReal/oneSite.json'
import list from './resourceScanReal/list.json'
import trend from './resourceScanReal/trend.json'
import budget from './resourceScanReal/budget.json'
import startSite from './resourceScanReal/startSite.json'
import runningSite from './resourceScanReal/runningSite.json'

/**
 * Kaynak analizi — GERÇEK arka uç yanıtları (prs3 sözleşme incelemesi D9, 2026-11-05). Dökümler gerçek
 * `PageResourceScanController` ile (H2 + gerçek PageFetchCore + yerel HTTP test sunucusu `127.0.0.1:51674`) alındı:
 * 2 tek sayfa analizi (#1, #2 — ikincisinde app.js büyüdü), 1 site analizi (#3, 5 sayfa; 2'si açılamadı), geçmiş, eğilim,
 * bütçe, site başlatma ve süren site koşusu. Kişisel / kurum verisi yok (`requested_by` test adı; `owner_pod` "pod-a").
 *
 * DİKKAT — dökümler be1 / be2 inceleme düzeltmelerinden ÖNCE alındı; sonra değişenler (gerektiği yerde aşağıdaki
 * `with*` türevleriyle sınanır):
 *  - kırık bağlantılar (`/urunler` 500, `/yok` 404) dökümde `DOCUMENT` sayılıyor — artık `LINK` (be1 sözleşme D5);
 *  - `same_path` tekrarları yalnız içerik özeti eşitse — dökümde yalnız `same_content` var (değişmez);
 *  - vekil üzerinden giden host'ta `http_version` null + `reason: proxy` (döküm doğrudan — vekil yok);
 *  - `analysis.site.incomplete`, `fonts.unknown_format`, `third_party_categories_total`, `hosts_total` dökümde YOK;
 *  - RUNNING site koşusunda `progress.pages_done / pages_total` dökümde YOK (sayfa listesi seçilmeden önceki an);
 *  - eğilimde `growth_busy` yok; bütçe `reason: capped` / `unreadable` dökümde yok.
 */
const clone = (v) => JSON.parse(JSON.stringify(v))

export const REAL_MONITOR = { id: 77, name: 'Kurum Test Sitesi', url: 'http://127.0.0.1:51674/', can_edit: true }

/** Geçmiş yanıtı (`{ success, data: { items, running, limits, can_run, budget } }`). */
export const realList = () => clone(list)
/** Analiz ayrıntısı yanıtı: 1 ve 2 tek sayfa, 3 site. */
export function realDetail(id) {
  if (id === 1) return clone(onePage1)
  if (id === 2) return clone(onePage2)
  if (id === 3) return clone(oneSite)
  return { success: false, status: 404, code: 'RESOURCE_SCAN_NOT_FOUND', error: 'Analiz bulunamadı' }
}
export const realTrend = () => clone(trend)
export const realBudget = () => clone(budget)
export const realStartSite = () => clone(startSite)
export const realRunningSite = () => clone(runningSite)

/** Ayrıntı yanıtının `data`sı (export / model testleri için). */
export const realData = (id) => realDetail(id).data

/** be2: RUNNING site koşusu, sayfa listesi seçildikten sonra (`progress.pages_done / pages_total`). */
export function withSitePages(res, done = 2, total = 5) {
  const r = clone(res)
  const scan = r.data.scan ?? r.data
  scan.progress = { ...(scan.progress || {}), pages_done: done, pages_total: total }
  return r
}

/** be1 D-1: HTML ayrıştırılamadı — ipuçları / HTML ölçüsü / engelleyen bilinmiyor, puan bileşeni null. */
export function withHtmlUnread(data) {
  const d = clone(data)
  d.analysis.loading.hints = null
  d.analysis.loading.render_blocking = null
  d.analysis.waste.html = null
  d.analysis.media.images.html_checked = false
  d.analysis.scores = d.analysis.scores.map((s) => (s.key === 'render_blocking' ? { ...s, score: null, value: null, lost: null } : s))
  return d
}

/** Ana yanıtın güvenlik başlıkları okunamadı (`headers` null, puan null). */
export function withSecurityUnread(data) {
  const d = clone(data)
  d.analysis.security.headers = null
  d.analysis.security.score = null
  d.analysis.security.letter = null
  d.analysis.category_scores.security = { score: null, letter: null }
  d.scan.security_score = null
  return d
}

/** be2 D-2: kısmi (sınıra takılan) analizde aşılmayan sınır `ok: null, reason: capped` (değer dolu). */
export function withBudgetCapped(data) {
  const d = clone(data)
  d.scan.capped = true
  d.budget.capped = true
  d.budget.checks = d.budget.checks.map((c) => (c.ok === true ? { ...c, ok: null, reason: 'capped' } : c))
  d.budget.status = d.budget.checks.some((c) => c.ok === false) ? 'exceeded' : 'unknown'
  return d
}

/** be2: değerlendirme okunamadı — her kalem `not_done`, kökte `unreadable`. */
export function withBudgetUnreadable(data) {
  const d = clone(data)
  d.budget = { ...d.budget, status: 'unknown', exceeded: null, unreadable: true,
    checks: d.budget.checks.map((c) => ({ ...c, actual: null, actual_bytes: undefined, ok: null, reason: 'not_done' })) }
  return d
}

/** be2: site eksikliği — bir sayfa süre bütçesiyle taranmadı (kod biçiminde de gelebilir), açılamayan sayfa kodları. */
export function withSiteIncomplete(data, { asCodes = false } = {}) {
  const d = clone(data)
  const site = d.analysis.site
  site.pages.push({ url: 'http://127.0.0.1:51674/kampanya', status: 'skipped',
    error: asCodes ? 'NOT_SCANNED:BYTES' : 'Toplam indirme sınırı doldu; bu sayfa taranmadı. Ayarlardan sınırı artırıp yeniden çalıştırın.',
    error_code: 'NOT_SCANNED', error_reason: null, grade: null, letter: null, load_ms: null, page_bytes: null, requests: null,
    scores: { security: null, seo: null, accessibility: null }, top_recs: [], unique_bytes: null, shared_count: null, capped: true })
  if (asCodes) {
    site.pages[3].error = 'PAGE_OFFSITE'
    site.pages[3].error_code = 'PAGE_OFFSITE'
    site.pages[4].error = 'PAGE_FAILED:CONNECTION_REFUSED'
    site.pages[4].error_code = 'PAGE_FAILED'
    site.pages[4].error_reason = 'CONNECTION_REFUSED'
  }
  site.totals = { ...site.totals, pages: 6, skipped: 1, shared: 120 }
  site.incomplete = true
  return d
}

/** be1: üçüncü taraf kategorisi host listesi 50'de kesik + biçimi okunamayan yazı tipi sayısı. */
export function withTotals(data) {
  const d = clone(data)
  d.analysis.media.fonts.unknown_format = 2
  d.analysis.security.third_party_categories = d.analysis.security.third_party_categories.map((c) => ({ ...c, hosts_total: c.hosts.length + 7 }))
  d.analysis.security.third_party_categories_total = d.analysis.security.third_party_categories.length
  d.analysis.media.images.missing_dimensions_total = 130
  return d
}

/** be2: eğilim büyümesi şu an hesaplanamadı (`growth: null` + `growth_busy`); pencere 200 noktada kesildi. */
export function withTrendBusy(res) {
  const r = clone(res)
  r.data.growth = null
  r.data.growth_busy = true
  r.data.truncated = true
  return r
}

/**
 * Algoritma 4 (2026-11-15, önbellek sınıfları) örneği — gerçek #1 dökümünden türetilmiş: sürüm 4, nesnelerde `cache_class`
 * (+ sezgiselde `cache_heuristic_s`, başlıklarda doğrulayıcı / Pragma), eski `NO_CACHE` bulgu / önerisinin yerinde sınıf başına
 * `CACHE_*`, `waste.caching.classes` + `repeat_visit_requests`. Sınıf ataması (sunucu kuralına uygun başlıklarla):
 * app.js no-store, async.js her ziyarette doğrulanan (ETag), old.js kısa ömür (300 sn), kullanilmayan.png ömrü verilmemiş
 * (Last-Modified), print.css Pragma: no-cache + ETag (doğrulanan), kalan önbelleksizler `none`, ömürlüler `stored`.
 */
export function withCacheClassesV4(data) {
  const d = clone(data)
  d.scan.algorithm_version = 4
  d.analysis.algorithm_version = 4
  const set = (end, cls, extra = {}) => {
    const it = d.items.find((i) => i.url.endsWith(end))
    Object.assign(it, { cache_class: cls }, extra.item || {})
    if (extra.headers) it.headers = { ...(it.headers || {}), ...extra.headers }
    return it
  }
  const code = {
    short: 'CACHE_SHORT', revalidate: 'CACHE_REVALIDATE', heuristic: 'CACHE_HEURISTIC', none: 'CACHE_NONE', no_store: 'CACHE_NO_STORE',
  }
  for (const it of d.items) {
    if (it.category === 'HTML' || !it.page_load) continue
    const ttl = it.cache_ttl_s
    it.cache_class = ttl !== null && ttl >= 3600 ? 'stored' : 'none'
  }
  set('/js/app.js', 'no_store', { headers: { cache_control: 'no-store' } })
  set('/js/async.js', 'revalidate', { item: { cache_ttl_s: -1 }, headers: { cache_control: 'no-cache', etag: '"a1"' } })
  set('/js/old.js', 'short', { item: { cache_ttl_s: 300 }, headers: { cache_control: 'max-age=300' } })
  set('/img/kullanilmayan.png', 'heuristic', { item: { cache_heuristic_s: 259_200 }, headers: { last_modified: 'Sun, 01 Nov 2026 10:00:00 GMT' } })
  set('/css/print.css', 'revalidate', { item: { cache_ttl_s: -1 }, headers: { pragma: 'no-cache', etag: '"p1"' } })
  const counts = { stored: 0, short: 0, revalidate: 0, heuristic: 0, none: 0, no_store: 0 }
  const byCode = {}
  for (const it of d.items) {
    if (!Array.isArray(it.findings)) continue
    const had = it.findings.includes('NO_CACHE')
    it.findings = it.findings.filter((c) => c !== 'NO_CACHE')
    if (it.third_party || !it.cache_class || it.category === 'HTML' || !it.page_load || it.status < 200 || it.status >= 300) continue
    counts[it.cache_class]++
    const c = code[it.cache_class]
    if (!c || !(had || it.cache_class !== 'none')) continue
    it.findings.push(c)
    // NO_VALIDATOR: none / no_store nesnede yok (sunucu kuralı); doğrulanan / sezgiselin doğrulayıcısı var
    if (it.cache_class !== 'short') it.findings = it.findings.filter((x) => x !== 'NO_VALIDATOR')
    ;(byCode[c] = byCode[c] || []).push(it.url)
  }
  const noValidator = d.items.filter((i) => (i.findings || []).includes('NO_VALIDATOR'))
  const recs = d.analysis.recommendations.filter((r) => r.code !== 'NO_CACHE').map((r) => (r.code !== 'NO_VALIDATOR' ? r
    : { ...r, params: { count: noValidator.length }, item_count: noValidator.length, item_urls: noValidator.map((i) => i.url).slice(0, 10) }))
  d.analysis.waste.caching.no_validator = noValidator.length
  for (const [c, urls] of Object.entries(byCode)) {
    recs.push({ code: c, severity: urls.length >= 10 && !['CACHE_REVALIDATE', 'CACHE_HEURISTIC'].includes(c) ? 'medium' : 'low',
      params: { count: urls.length }, saving_bytes: null, saving_ms: null, item_urls: urls.slice(0, 10), item_count: urls.length, finding: c })
  }
  d.analysis.recommendations = recs
  d.analysis.waste.caching.classes = counts
  d.analysis.waste.caching.repeat_visit_requests = 17
  return d
}
