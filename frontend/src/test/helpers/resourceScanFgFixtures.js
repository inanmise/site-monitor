// Kaynak analizi prs3 (fe2) — bütçe (F1), eğilim (F2), yönetici raporu (F3) ve çok sayfalı analiz (G) için gerçekçi
// sahte veri; vitest ve e2e (page-resource-scan-fg.spec.js) ortak kullanır. Sözleşme: scratchpad prs3-contract.md.
// Sürüm 3 alanları (A–E bölümleri, kategori puanları) be1'in sözleşmesindeki şekilde — G'nin arka ucu henüz yok (sahte).
import { KB, MB, LONG, PERSON, scanDetailV2, scanHistory, scanSummary } from './resourceScanFixtures.js'

export { KB, MB, LONG, PERSON }

/** Bütçe ucu yanıtının `data`'sı (düz alanlar + düzenleme izni). */
export function budgetData(over = {}, canEdit = true) {
  return {
    max_page_kb: null, max_requests: null, max_js_kb: null, max_image_kb: null, max_load_ms: null, min_grade: null,
    min_security_score: null, can_edit: canEdit, ...over,
  }
}

/** Ayrıntının bütçe değerlendirmesi: sayfa boyutu ve not aşıldı, istek geçti, güvenlik puanı ölçülemedi. */
export function budgetEval() {
  return {
    defined: true, exceeded: 2,
    checks: [
      { key: 'max_page_kb', limit: 2048, actual: 3482, ok: false },
      { key: 'max_requests', limit: 80, actual: 54, ok: true },
      { key: 'min_grade', limit: 80, actual: 72, ok: false },
      { key: 'min_security_score', limit: 70, actual: null, ok: null },
    ],
  }
}

/** Sürüm 3 analiz blokları (A–E) + kategori puanları. */
export function deepAnalysis() {
  return {
    algorithm_version: 3,
    category_scores: { performance: { score: 72, letter: 'C' }, security: { score: 58, letter: 'E' }, seo: { score: 86 }, accessibility: { score: null } },
    loading: {
      render_blocking: { items: [
        { url: 'https://www.example.com/static/site.css', kind: 'stylesheet', bytes: 120 * KB, duration_ms: 210, reason: 'no_media' },
        { url: 'https://www.example.com/static/app.bundle.js', kind: 'script', bytes: 640 * KB, duration_ms: 880, reason: 'no_async_defer' },
      ], count: 2, bytes: 760 * KB, est_first_paint_ms: 1340 },
      hints: { preload: [{ url: 'https://www.example.com/fonts/inter.woff2', as: 'font', used: false }], preconnect: [], dns_prefetch: [],
        missing_preconnect: [{ host: 'analytics.example.net', requests: 3, bytes: 90 * KB, blocking: false }], fetchpriority: [] },
      hosts: [
        { host: 'www.example.com', third_party: false, requests: 40, bytes: Math.round(1.6 * MB), http_version: '1.1', h3_advertised: false,
          dns_ms: 12, connect_ms: 30, tls_ms: 45, measured: true, reason: null },
        // be1: vekil üzerinden giden host'un HTTP sürümü bilinmez (null + reason proxy)
        { host: 'analytics.example.net', third_party: true, requests: 3, bytes: 90 * KB, http_version: null, h3_advertised: true,
          dns_ms: null, connect_ms: null, tls_ms: null, measured: false, reason: 'proxy' },
      ],
      hosts_total: 4, http1_first_party_hosts: 1,
      profiles: [
        { key: 'fiber', rtt_ms: 10, mbps: 100, load_ms: 900, first_paint_ms: 400 },
        { key: '4g', rtt_ms: 70, mbps: 9, load_ms: 3600, first_paint_ms: 1500 },
        { key: '3g', rtt_ms: 300, mbps: 1.6, load_ms: 17_800, first_paint_ms: 6200 },
        { key: 'slow3g', rtt_ms: 400, mbps: 0.4, load_ms: 69_000, first_paint_ms: 21_000 },
      ],
    },
    waste: {
      duplicates: [{ kind: 'same_content', urls: ['https://www.example.com/a/jquery.js', 'https://www.example.com/b/jquery.js'], bytes_wasted: 88 * KB }],
      libraries: [{ name: 'jQuery', versions: ['1.12.4', '3.6.0'], urls: ['https://www.example.com/a/jquery.js'] }],
      unminified: [{ url: 'https://www.example.com/static/app.bundle.js', bytes: 640 * KB, est_saving_bytes: 210 * KB }],
      source_maps: [{ url: 'https://www.example.com/static/app.bundle.js', map_url: 'https://www.example.com/static/app.bundle.js.map', exposed: true }],
      html: { html_bytes: 48 * KB, inline_script_bytes: 12 * KB, inline_style_bytes: 4 * KB, dom_nodes: 1840, max_depth: 22, data_uri_count: 3, data_uri_bytes: 6 * KB },
      caching: { buckets: { none: 2, lt_1h: 31, lt_1d: 0, lt_7d: 4, ge_7d: 3 }, no_validator: 5, versioned_short: [], cdn: { hit: 1, miss: 0, unknown: 39 },
        repeat_visit_bytes: Math.round(2.4 * MB), repeat_visit_pct: 71 },
    },
    media: {
      images: { missing_dimensions: [{ url: LONG(1) }, { url: 'https://www.example.com/img/alt-banner.webp' }], missing_srcset: [{ url: LONG(1), width: 3840 }],
        not_lazy: [{ url: 'https://www.example.com/img/thumb-10.jpg' }], animated_gif: [] },
      fonts: { count: 1, bytes: 96 * KB, families: [{ family: 'Inter', weights: ['400', '700'], files: 1 }], non_woff2: [], missing_display: [{ family: 'Inter', css_url: 'https://www.example.com/static/site.css' }], preloaded: 1 },
    },
    security: {
      score: 58, letter: 'E',
      headers: [
        { key: 'csp', status: 'missing', value: null, issue: null },
        { key: 'hsts', status: 'weak', value: 'max-age=300', issue: 'max-age kısa' },
        { key: 'x_content_type_options', status: 'ok', value: 'nosniff', issue: null },
      ],
      sri_missing: [{ url: 'https://cdn.example.org/lib.js', kind: 'script' }],
      mixed_content: [],
      cookies: [{ name: 'SESSIONID', host: 'www.example.com', third_party: false, secure: true, http_only: false, same_site: null, issues: ['no_http_only', 'no_same_site'] }],
      libraries: [{ name: 'jQuery', version: '1.12.4', url: 'https://www.example.com/a/jquery.js', vulns: [{ id: 'CVE-2020-11022', severity: 'medium', summary: 'XSS' }] }],
      catalog_date: '2026-10-01',
      third_party_categories: [{ category: 'analytics', hosts: ['analytics.example.net'], requests: 3, bytes: 90 * KB }],
      tracking_on_load: [{ host: 'analytics.example.net', category: 'analytics' }],
    },
    html_audit: {
      seo: { score: 86, checks: [
        { key: 'title', status: 'pass', value: 'Kurumsal Ana Sayfa', count: 1 },
        { key: 'meta_description', status: 'fail', value: null, count: 0 },
        { key: 'canonical', status: 'warn', value: null, count: 0 },
      ] },
      accessibility: { score: null, checks: [
        { key: 'img_alt', status: 'fail', count: 6, samples: ['img.banner', 'img.thumb'] },
        { key: 'html_lang', status: 'pass', count: 1, samples: [] },
      ] },
    },
  }
}

/** Sürüm 3 ayrıntı (yönetici raporu testleri): v2 ayrıntısı + A–E + bütçe değerlendirmesi. */
export function deepDetail(id = 12, { budget = budgetEval(), scan = {} } = {}) {
  const d = scanDetailV2(id, { scan: { algorithm_version: 3, js_bytes: 640 * KB, css_bytes: 120 * KB, font_bytes: 96 * KB,
    security_score: 58, seo_score: 86, a11y_score: null, ...scan } })
  return { ...d, analysis: { ...d.analysis, ...deepAnalysis() }, budget }
}

/** Çok sayfalı analiz bloğu (`analysis.site`): ana sayfa + 3 iç sayfa (biri açılamadı, biri süre dolunca taranmadı). */
export function siteBlock() {
  return {
    pages: [
      { url: 'https://www.example.com/', status: 'ok', error: null, grade: 72, letter: 'C', load_ms: 2450, page_bytes: Math.round(3.4 * MB), requests: 54,
        scores: { security: 58, seo: 86, accessibility: null }, top_recs: ['LARGE_IMAGES', 'RENDER_BLOCKING_RESOURCES', 'UNCOMPRESSED_TEXT'],
        unique_bytes: Math.round(1.9 * MB), shared_count: 6 },
      { url: 'https://www.example.com/kurumsal/hakkimizda-ve-yonetim-kurulu-uyeleri-ile-cok-uzun-bir-sayfa-adresi-2026', status: 'ok', error: null,
        grade: 48, letter: 'F', load_ms: 6100, page_bytes: Math.round(7.8 * MB), requests: 132,
        scores: { security: 58, seo: 64, accessibility: 71 }, top_recs: ['OVERSIZED_PAGE', 'LARGE_IMAGES', 'MISSING_SRCSET', 'BILINMEYEN_KOD'],
        unique_bytes: Math.round(6.2 * MB), shared_count: 6 },
      // Gerçek biçim (be2 localizeSite): `error` isteğin dilinde CÜMLE, `error_code` önek, `error_reason` neden
      { url: 'https://www.example.com/iletisim', status: 'failed', error: 'Sayfa HTTP 503 döndürdü; analiz yapılamadı. Sayfanın açıldığını kontrol edip yeniden deneyin.',
        error_code: 'PAGE_HTTP', error_reason: null, grade: null, letter: null, load_ms: null, page_bytes: null,
        requests: null, scores: {}, top_recs: [], unique_bytes: null, shared_count: null, capped: false },
      { url: 'https://www.example.com/kampanyalar', status: 'skipped', error: 'Süre bütçesi doldu; bu sayfa taranmadı. Daha az sayfayla ya da daha uzun süreyle yeniden çalıştırın.',
        error_code: 'NOT_SCANNED', error_reason: null, grade: null, letter: null, load_ms: null, page_bytes: null,
        requests: null, scores: {}, top_recs: [], unique_bytes: null, shared_count: null, capped: true },
      { url: 'https://www.example.com/urunler', status: 'ok', error: null, grade: 81, letter: 'B', load_ms: 1900, page_bytes: Math.round(1.2 * MB), requests: 38,
        scores: { security: 58, seo: 90, accessibility: 88 }, top_recs: [], unique_bytes: 300 * KB, shared_count: 6 },
    ],
    shared: [
      { url: 'https://www.example.com/static/app.bundle.js', category: 'JS', bytes: 640 * KB, pages: 3 },
      { url: 'https://www.example.com/static/site.css', category: 'CSS', bytes: 120 * KB, pages: 3 },
      { url: 'https://www.example.com/fonts/inter.woff2', category: 'FONT', bytes: 96 * KB, pages: 3 },
    ],
    totals: { pages: 5, scanned: 3, failed: 1, skipped: 1, avg_grade: 67, total_unique_bytes: Math.round(8.4 * MB), shared: 3 },
    incomplete: true,
  }
}

/** Çok sayfalı analizin ayrıntısı. */
export function siteDetail(id = 30, over = {}) {
  const d = deepDetail(id, { scan: { scope: 'site', pages_count: 3, started_at: '2026-11-01T09:00:00', finished_at: '2026-11-01T09:04:00', ...over.scan } })
  return { ...d, analysis: { ...d.analysis, site: siteBlock() } }
}

/**
 * Geçmiş: 30 site analizi (bütçe aşıldı), sonra standart geçmiş (12 = bütçe içinde, 10 = bilinmiyor, diğerleri bütçesiz).
 * `site: false` → 30 yok.
 */
export function fgHistory({ site = true, running = null, canRun = true, extraLimits = {} } = {}) {
  const base = scanHistory({ canRun })
  const items = base.items.map((s) => {
    if (s.status !== 'DONE') return s
    const v = { ...s, algorithm_version: s.id === 10 ? 1 : 2 }
    if (s.id === 12) return { ...v, budget_status: 'ok', budget_exceeded: 0 }
    if (s.id === 10) return { ...v, budget_status: 'unknown', budget_exceeded: null }
    return { ...v, budget_status: 'none' }
  })
  const siteRow = scanSummary(30, { started_at: '2026-11-01T09:00:00', finished_at: '2026-11-01T09:04:00', algorithm_version: 3, scope: 'site',
    pages_count: 3, budget_status: 'exceeded', budget_exceeded: 2, security_score: 58, seo_score: 86, a11y_score: null })
  const all = site ? [siteRow, ...items] : items
  return { items: running ? [running, ...all] : all, running, can_run: canRun,
    limits: { max_seconds: 300, max_objects: 1500, detail_runs: 20, max_pages: 10, site_max_seconds: 900, ...extraLimits } }
}

/** Eğilim ucu yanıtının `data`'sı: 6 nokta (iki eski analizde kategori ve puan yok; algoritma 2 → 3), büyüyen nesneler. */
export function trendData() {
  const p = (id, day, over) => ({ scan_id: id, finished_at: `2026-10-${String(day).padStart(2, '0')}T09:01:00`, algorithm_version: 2, grade: 70,
    load_ms: 2600, page_bytes: Math.round(3.4 * MB), request_count: 54, js_bytes: null, css_bytes: null, image_bytes: Math.round(2.1 * MB),
    font_bytes: null, security_score: null, seo_score: null, a11y_score: null, ...over })
  return {
    points: [
      p(6, 3, { grade: 63, load_ms: 3400 }),
      p(7, 10, { grade: 62, load_ms: 3300 }),
      p(9, 20, { grade: 66, load_ms: 3000, js_bytes: 600 * KB, css_bytes: 110 * KB, font_bytes: 90 * KB }),
      p(12, 31, { grade: 72, load_ms: 2450, js_bytes: 640 * KB, css_bytes: 120 * KB, font_bytes: 96 * KB }),
      { ...p(30, 31, { algorithm_version: 3, grade: 72, load_ms: 2450, js_bytes: 640 * KB, css_bytes: 120 * KB, font_bytes: 96 * KB,
        security_score: 58, seo_score: 86, a11y_score: 74 }), finished_at: '2026-11-01T09:04:00' },
    ],
    growth: [
      { url: LONG(1), category: 'IMAGE', first_bytes: Math.round(0.8 * MB), last_bytes: Math.round(1.6 * MB), delta_pct: 100, scans: 5 },
      { url: 'https://www.example.com/static/app.bundle.js', category: 'JS', first_bytes: 480 * KB, last_bytes: 640 * KB, delta_pct: 33.3, scans: 5 },
    ],
  }
}
