// Kaynak analizi (2026-10-31) — vitest ve e2e (page-resource-scan.spec.js) ortak gerçekçi verisi. Uzun URL'ler, gerçek
// uzunlukta kişi adı, belge / bağlantı / geç yüklenen görsel, başarısız nesneler, öneriler (host_list, item_urls) ve notlar.
// Sözleşme: scratchpad page-resource-scan-contract.md (GET /api/monitoring/page/{id}/resource-scans[/{scanId}]).

const KB = 1024
const MB = 1024 * 1024
const LONG = (i) => `https://cok-uzun-bir-icerik-dagitim-agi-adresi-${i}.example.test/assets/2026/10/kampanya/gorseller/ana-sayfa-banner-yuksek-cozunurluk-varyant-${i}.webp?v=${'x'.repeat(30)}`

export const PERSON = 'Ayşe Yılmaz Çok Uzun Soyadlı (Dijital Kanallar / Kıdemli Mühendis)'

/** Bir analiz özeti — `over` ile alan ezilir. */
export function scanSummary(id, over = {}) {
  return {
    id, monitor_id: 100, url: 'https://www.example.com/', status: 'DONE',
    started_at: '2026-10-31T09:00:00', finished_at: '2026-10-31T09:00:42', requested_by: PERSON,
    progress: { done: 64, total: 64 }, grade: 72, grade_letter: 'C', load_ms: 2450, html_ttfb_ms: 320, html_ms: 410,
    page_bytes: Math.round(3.4 * MB), request_count: 54, image_bytes: Math.round(2.1 * MB), image_count: 18,
    document_count: 3, document_bytes: 6 * MB, link_count: 7, link_bytes: 210 * KB, failed_count: 2, capped: false, error: null,
    items_kept: true, via: 'direct',
    ...over,
  }
}

/** Nesneler: 40 adet (sayfalama, süzgeç, kova sayıları için). */
export function scanItems() {
  const items = [
    { url: 'https://www.example.com/', category: 'HTML', content_type: 'text/html; charset=utf-8', status: 200, bytes: 48 * KB, truncated: false,
      size_source: 'body', ttfb_ms: 320, duration_ms: 410, start_ms: 0, third_party: false, compressed: true, cache_ttl_s: null, width: null,
      height: null, redirects: 0, from_css: false, page_load: true, lazy: false, error: null },
    { url: LONG(1), category: 'IMAGE', content_type: 'image/jpeg', status: 200, bytes: Math.round(1.6 * MB), truncated: false, size_source: 'body',
      ttfb_ms: 90, duration_ms: 1500, start_ms: 420, third_party: true, compressed: false, cache_ttl_s: 86_400, width: 3840, height: 2160,
      redirects: 0, from_css: false, page_load: true, lazy: false, error: null },
    { url: 'https://www.example.com/static/app.bundle.js', category: 'JS', content_type: 'application/javascript', status: 200, bytes: 640 * KB,
      truncated: false, size_source: 'body', ttfb_ms: 60, duration_ms: 880, start_ms: 430, third_party: false, compressed: false, cache_ttl_s: -1,
      width: null, height: null, redirects: 1, from_css: false, page_load: true, lazy: false, error: null },
    { url: 'https://www.example.com/static/site.css', category: 'CSS', content_type: 'text/css', status: 200, bytes: 120 * KB, truncated: false,
      size_source: 'body', ttfb_ms: 40, duration_ms: 210, start_ms: 425, third_party: false, compressed: true, cache_ttl_s: 604_800, width: null,
      height: null, redirects: 0, from_css: false, page_load: true, lazy: false, error: null },
    { url: 'https://www.example.com/fonts/inter.woff2', category: 'FONT', content_type: 'font/woff2', status: 200, bytes: 96 * KB, truncated: false,
      size_source: 'body', ttfb_ms: 30, duration_ms: 160, start_ms: 640, third_party: false, compressed: false, cache_ttl_s: 31_536_000,
      width: null, height: null, redirects: 0, from_css: true, page_load: true, lazy: false, error: null },
    { url: 'https://www.example.com/missing/logo.png', category: 'IMAGE', content_type: 'text/html', status: 404, bytes: 2 * KB, truncated: false,
      size_source: 'body', ttfb_ms: 35, duration_ms: 40, start_ms: 430, third_party: false, compressed: true, cache_ttl_s: null, width: null,
      height: null, redirects: 0, from_css: false, page_load: true, lazy: false, error: null },
    { url: 'https://www.example.com/docs/yillik-faaliyet-raporu-2025-tam-surum.pdf', category: 'DOCUMENT', content_type: 'application/pdf', status: 200,
      bytes: 5 * MB, truncated: true, size_source: 'header', ttfb_ms: 120, duration_ms: 2400, start_ms: null, third_party: false, compressed: false,
      cache_ttl_s: null, width: null, height: null, redirects: 0, from_css: false, page_load: false, lazy: false, error: null },
    { url: 'https://www.example.com/iletisim', category: 'LINK', content_type: 'text/html', status: 0, bytes: null, truncated: false, size_source: 'body',
      ttfb_ms: null, duration_ms: 5000, start_ms: null, third_party: false, compressed: null, cache_ttl_s: null, width: null, height: null,
      redirects: 0, from_css: false, page_load: false, lazy: false, error: 'READ_TIMEOUT' },
    { url: 'https://www.example.com/img/alt-banner.webp', category: 'IMAGE', content_type: 'image/webp', status: 200, bytes: 320 * KB, truncated: false,
      size_source: 'body', ttfb_ms: 50, duration_ms: 300, start_ms: null, third_party: false, compressed: false, cache_ttl_s: 86_400, width: 1600,
      height: 600, redirects: 0, from_css: false, page_load: true, lazy: true, error: null },
  ]
  for (let i = 0; i < 31; i += 1) {
    items.push({ url: `https://www.example.com/img/thumb-${String(i).padStart(2, '0')}.jpg`, category: 'IMAGE', content_type: 'image/jpeg', status: 200,
      bytes: (4 + i) * KB, truncated: false, size_source: 'body', ttfb_ms: 20, duration_ms: 40 + i, start_ms: 700 + i * 10, third_party: false,
      compressed: false, cache_ttl_s: 3600, width: 200, height: 200, redirects: 0, from_css: false, page_load: true, lazy: false, error: null })
  }
  return items
}

export function scanAnalysis() {
  const items = scanItems()
  return {
    scores: [
      { key: 'load_time', score: 62, weight: 0.25, value: 2450, unit: 'ms' },
      { key: 'page_weight', score: 55, weight: 0.2, value: Math.round(3.4 * MB), unit: 'bytes' },
      { key: 'requests', score: 80, weight: 0.1, value: 54, unit: 'count' },
      { key: 'server_response', score: 90, weight: 0.1, value: 320, unit: 'ms' },
      { key: 'images', score: 40, weight: 0.15, value: 62, unit: 'pct' },
      { key: 'compression', score: 70, weight: 0.05, value: 1, unit: 'count' },
      { key: 'caching', score: 75, weight: 0.1, value: 2, unit: 'count' },
      { key: 'errors', score: 85, weight: 0.05, value: 1, unit: 'count' },
    ],
    categories: [
      { category: 'HTML', count: 1, bytes: 48 * KB, duration_ms: 410 },
      { category: 'CSS', count: 1, bytes: 120 * KB, duration_ms: 210 },
      { category: 'JS', count: 1, bytes: 640 * KB, duration_ms: 880 },
      { category: 'IMAGE', count: 33, bytes: Math.round(2.1 * MB), duration_ms: 3200 },
      { category: 'FONT', count: 1, bytes: 96 * KB, duration_ms: 160 },
      { category: 'DOCUMENT', count: 1, bytes: 5 * MB, duration_ms: 2400 },
      { category: 'LINK', count: 1, bytes: 0, duration_ms: 5000 },
    ],
    recommendations: [
      { code: 'NO_CACHE', severity: 'low', params: { count: 2 }, saving_bytes: null, saving_ms: null, item_urls: [] },
      { code: 'LARGE_IMAGES', severity: 'high', params: { count: 1, bytes: Math.round(1.6 * MB), limit_kb: 200 }, saving_bytes: Math.round(1.2 * MB),
        saving_ms: 1400, item_urls: [items[1].url] },
      { code: 'THIRD_PARTY_HEAVY', severity: 'medium', params: { bytes: Math.round(1.6 * MB), share_pct: 47, hosts: 3,
        host_list: ['cok-uzun-bir-icerik-dagitim-agi-adresi-1.example.test', 'analytics.example.net', 'fonts.example.org'] },
        saving_bytes: null, saving_ms: null, item_urls: [items[1].url] },
      { code: 'UNCOMPRESSED_TEXT', severity: 'medium', params: { count: 1, bytes: 640 * KB }, saving_bytes: 450 * KB, saving_ms: null,
        item_urls: ['https://www.example.com/static/app.bundle.js'] },
      { code: 'BROKEN_LINKS', severity: 'medium', params: { count: 1 }, saving_bytes: null, saving_ms: null, item_urls: ['https://www.example.com/iletisim'] },
    ],
    images: {
      count: 33, bytes: Math.round(2.1 * MB), share_pct: 61.8, large_count: 2, large_bytes: Math.round(1.9 * MB), est_ms_4g: 1900, est_ms_3g: 11_200,
      saving_bytes: Math.round(1.3 * MB), saving_ms_4g: 1200,
      top: [items[1], items[8], items[39]],
    },
    timing: { load_ms: 2450, sum_ms: 9800, slowest_ms: 1500, parallel: 6 },
    notes: [{ code: 'JS_NOT_EXECUTED', params: {} }, { code: 'EXCLUDES_APPLIED', params: { count: 2 } }],
  }
}

/** Tamamlanmış analizin ayrıntısı. */
export function scanDetail(id = 12, over = {}) {
  return { scan: scanSummary(id, over.scan), analysis: scanAnalysis(), items: scanItems(), items_kept: true, ...over.rest }
}

/** Geçmiş: en yeni 12 (DONE), 11 (FAILED), 10 (DONE, eski — ayrıntı saklanmamış), 9 … 6 (DONE). */
export function scanHistory({ running = null, canRun = true, extra = [] } = {}) {
  const items = [
    scanSummary(12),
    scanSummary(11, { status: 'FAILED', grade: null, grade_letter: null, load_ms: null, page_bytes: null, error: 'Sayfa 30 sn içinde yanıt vermedi',
      started_at: '2026-10-30T15:00:00', finished_at: '2026-10-30T15:00:30' }),
    scanSummary(10, { grade: 64, grade_letter: 'D', load_ms: 2900, page_bytes: Math.round(4.1 * MB), request_count: 61, image_bytes: Math.round(2.8 * MB),
      started_at: '2026-10-29T09:00:00', items_kept: false, requested_by: null, via: 'proxy' }),
    ...[9, 8, 7, 6].map((id, i) => scanSummary(id, { grade: 60 + i, load_ms: 3200 - i * 100, started_at: `2026-10-2${8 - i}T09:00:00` })),
    ...extra,
  ]
  return { items: running ? [running, ...items] : items, running, can_run: canRun, limits: { max_seconds: 300, max_objects: 1500, detail_runs: 20 } }
}

export { KB, MB, LONG }
