// Kaynak analizi — algoritma 3 (derin analiz paketleri A–E, prs3) için gerçekçi veri: vitest ve e2e
// (page-resource-scan-deep.spec.js) ortak. Sözleşme: scratchpad prs3-contract.md (A loading, B waste, C media, D security,
// E html_audit, category_scores, nesne alanları). Uzun URL'ler, uzun çerez / aile adları ve 50'yi aşan liste (`*_total`)
// bilinçli: arayüz kesmeden sarmalı, "+N daha" demeli.
import { KB, MB, LONG, scanAnalysisV2, scanItemsV2, scanSummary } from './resourceScanFixtures.js'

const JS = 'https://www.example.com/static/app.bundle.js'
const CSS = 'https://www.example.com/static/site.css'
const CDN_JQ = 'https://cdn.cok-uzun-bir-icerik-dagitim-agi.example.test/libs/jquery/1.12.4/jquery.min.js'
const OLD_JQ = 'https://www.example.com/static/vendor/jquery-3.5.1.min.js'

/** Algoritma 3 nesne ek alanları (sözleşme "Nesne alanları"); olmayan dizinler varsayılanı alır. */
const V3_ITEM = {
  0: { http_version: '2', content_hash: 'a1b2c3d4e5f60718', minified: null, library: null, source_map: null, render_blocking: false,
    integrity: null, third_party_category: null },
  1: { http_version: '1.1', content_hash: 'ffeeddccbbaa9988', minified: null, library: null, source_map: null, render_blocking: false,
    integrity: null, third_party_category: 'cdn', findings_add: ['NOT_LAZY', 'MISSING_DIMENSIONS'] },
  2: { http_version: '2', content_hash: '0011223344556677', minified: false, library: null, source_map: `${JS}.map`, render_blocking: true,
    integrity: false, third_party_category: null, findings_add: ['RENDER_BLOCKING', 'UNMINIFIED', 'SOURCE_MAP_EXPOSED'],
    headers_add: { etag: '"5f2a-17c3b"', last_modified: 'Fri, 30 Oct 2026 08:00:00 GMT', age: null, cdn_cache: null, alt_svc: 'h3=":443"; ma=86400' } },
  3: { http_version: '2', content_hash: '8899aabbccddeeff', minified: true, library: null, source_map: null, render_blocking: true,
    integrity: null, third_party_category: null, findings_add: ['RENDER_BLOCKING'] },
  4: { http_version: '2', content_hash: '1234123412341234', minified: null, library: null, source_map: null, render_blocking: false,
    integrity: null, third_party_category: null, findings_add: ['FONT_DISPLAY_MISSING'] },
}

/** Algoritma 3 nesneleri: sürüm 2 nesneleri + derin alanlar + iki kütüphane betiği (biri açıklı, biri yinelenen sürüm). */
export function scanItemsV3() {
  const base = scanItemsV2().map((it, i) => {
    const x = V3_ITEM[i] || { http_version: '2', content_hash: null, minified: null, library: null, source_map: null,
      render_blocking: false, integrity: null, third_party_category: it.third_party ? 'other' : null }
    const { findings_add: add = [], headers_add: hAdd = null, ...fields } = x
    return {
      ...it, ...fields,
      findings: [...(it.findings || []), ...add],
      headers: it.headers ? { ...it.headers, etag: null, last_modified: null, age: null, cdn_cache: null, alt_svc: null, ...(hAdd || {}) } : it.headers,
    }
  })
  base.push({
    url: CDN_JQ, category: 'JS', content_type: 'application/javascript', status: 200, bytes: 95 * KB, truncated: false, size_source: 'body',
    ttfb_ms: 70, duration_ms: 230, start_ms: 440, third_party: true, compressed: true, cache_ttl_s: 31_536_000, width: null, height: null,
    redirects: 0, from_css: false, page_load: true, lazy: false, error: null, findings: ['RENDER_BLOCKING', 'MISSING_SRI', 'VULNERABLE_LIBRARY', 'DUPLICATE'],
    verdict: 'ok', via: 'direct', host: 'cdn.cok-uzun-bir-icerik-dagitim-agi.example.test', found_in: 'page', final_url: null,
    headers: { content_type: 'application/javascript', content_encoding: 'br', cache_control: 'public, max-age=31536000', expires: null,
      content_length: String(95 * KB), server: 'cloudflare', etag: null, last_modified: null, age: '3600', cdn_cache: 'hit', alt_svc: 'h3=":443"' },
    http_version: '2', content_hash: 'deadbeefcafe0001', minified: true, library: { name: 'jquery', version: '1.12.4' }, source_map: null,
    render_blocking: true, integrity: false, third_party_category: 'cdn',
  })
  base.push({
    url: OLD_JQ, category: 'JS', content_type: 'application/javascript', status: 200, bytes: 89 * KB, truncated: false, size_source: 'body',
    ttfb_ms: 40, duration_ms: 120, start_ms: 445, third_party: false, compressed: true, cache_ttl_s: 600, width: null, height: null,
    redirects: 0, from_css: false, page_load: true, lazy: false, error: null, findings: ['DUPLICATE', 'NO_VALIDATOR'],
    verdict: 'ok', via: 'direct', host: 'www.example.com', found_in: 'page', final_url: null,
    headers: { content_type: 'application/javascript', content_encoding: 'gzip', cache_control: 'max-age=600', expires: null,
      content_length: null, server: 'nginx', etag: null, last_modified: null, age: null, cdn_cache: null, alt_svc: null },
    http_version: '1.1', content_hash: 'deadbeefcafe0002', minified: true, library: { name: 'jquery', version: '3.5.1' }, source_map: null,
    render_blocking: false, integrity: null, third_party_category: null,
  })
  return base
}

/** 60 satırlık bir liste (sunucu ≤ 50 gönderir, `*_total` 60) — "+N daha" metni için. */
const manyImages = (n) => Array.from({ length: n }, (_, i) => ({ url: `https://www.example.com/img/urun-galeri-${String(i).padStart(2, '0')}-yuksek-cozunurluk.jpg` }))

export function scanAnalysisV3() {
  const a = scanAnalysisV2()
  return {
    ...a,
    algorithm_version: 3,
    scores: [
      ...a.scores.map((s) => (s.key === 'load_time' ? { ...s, weight: 0.2 } : s.key === 'page_weight' ? { ...s, weight: 0.15 } : s)),
      { key: 'render_blocking', score: 45, weight: 0.1, value: 863 * KB, unit: 'bytes', lost: 5.5, full_at: 51_200, zero_at: 2_097_152 },
    ],
    category_scores: {
      performance: { score: 72, letter: 'C' }, security: { score: 58, letter: 'E' }, seo: { score: 85 }, accessibility: { score: null },
    },
    // Algoritma 3 öneri satırı `finding` taşır (null = sayfa düzeyi; `item_count` null ⇔ `finding` null — be1 kataloğu)
    recommendations: [
      ...a.recommendations.map((r) => ({ ...r, finding: r.code === 'THIRD_PARTY_HEAVY' ? null : r.code })),
      { code: 'RENDER_BLOCKING_RESOURCES', severity: 'high', params: { count: 4, bytes: 863 * KB, est_first_paint_ms: 1640 }, saving_bytes: null,
        saving_ms: 900, item_urls: [JS, CSS, CDN_JQ], item_count: 3, finding: 'RENDER_BLOCKING' },
      { code: 'MISSING_PRECONNECT', severity: 'medium', params: { count: 1, host_list: ['cdn.cok-uzun-bir-icerik-dagitim-agi.example.test'] },
        saving_bytes: null, saving_ms: 110, item_urls: [CDN_JQ], item_count: null, finding: null },
      { code: 'VULNERABLE_LIBRARY', severity: 'medium', params: { count: 1, name_list: ['jquery 1.12.4'], max_severity: 'medium' }, saving_bytes: null,
        saving_ms: null, item_urls: [CDN_JQ], item_count: 1, finding: 'VULNERABLE_LIBRARY' },
      { code: 'SECURITY_HEADERS_MISSING', severity: 'medium', params: { count: 3, keys: ['x_content_type_options', 'frame_protection', 'permissions_policy'] },
        saving_bytes: null, saving_ms: null, item_urls: [], item_count: null, finding: null },
      { code: 'WEAK_CSP', severity: 'medium', params: { issues: ['unsafe_inline', 'unsafe_eval', 'wildcard_source'] }, saving_bytes: null, saving_ms: null,
        item_urls: [], item_count: null, finding: null },
      { code: 'INSECURE_COOKIES', severity: 'medium', params: { count: 1, name_list: ['_ga_cok_uzun_bir_izleme_cerezi_adi_ornek_X7Y8Z9'] },
        saving_bytes: null, saving_ms: null, item_urls: [], item_count: null, finding: null },
      { code: 'TRACKING_ON_LOAD', severity: 'low', params: { count: 2, categories: ['analytics', 'advertising'] }, saving_bytes: null, saving_ms: null,
        item_urls: [], item_count: null, finding: null },
      { code: 'DUPLICATE_LIBRARIES', severity: 'medium', params: { count: 1, name_list: ['jquery'] }, saving_bytes: null, saving_ms: null,
        item_urls: [CDN_JQ, OLD_JQ], item_count: null, finding: null },
      { code: 'DUPLICATE_RESOURCES', severity: 'low', params: { count: 2, bytes: 101 * KB }, saving_bytes: 101 * KB, saving_ms: null,
        item_urls: [CDN_JQ, OLD_JQ], item_count: 2, finding: 'DUPLICATE' },
      { code: 'IMAGES_WITHOUT_DIMENSIONS', severity: 'medium', params: { count: 60 }, saving_bytes: null, saving_ms: null, item_urls: [],
        item_count: 1, finding: 'MISSING_DIMENSIONS' },
      { code: 'LARGE_DOM', severity: 'medium', params: { nodes: 1850, depth: 24, limit: 1500 }, saving_bytes: null, saving_ms: null, item_urls: [],
        item_count: null, finding: null },
    ],
    notes: [...a.notes, { code: 'JS_COOKIES_NOT_SEEN', params: {} }, { code: 'CONTRAST_NOT_CHECKED', params: {} },
      { code: 'HOSTS_NOT_MEASURED', params: { count: 1, limit: 30 } }],
    loading: {
      render_blocking: {
        items: [
          { url: JS, kind: 'script', bytes: 640 * KB, duration_ms: 880, reason: 'no_async_defer' },
          { url: CSS, kind: 'stylesheet', bytes: 120 * KB, duration_ms: 210, reason: 'no_media' },
          { url: CDN_JQ, kind: 'script', bytes: 95 * KB, duration_ms: 230, reason: 'no_async_defer' },
          { url: 'https://www.example.com/static/eski-tema-yazdirma-ve-ekran-stilleri-icin-cok-uzun-adli-dosya.css', kind: 'css_import', bytes: 8 * KB,
            duration_ms: 60, reason: 'import' },
        ],
        count: 4, bytes: 863 * KB, est_first_paint_ms: 1640,
      },
      hints: {
        preload: [{ url: 'https://www.example.com/fonts/inter.woff2', as: 'font', used: true }, { url: 'https://www.example.com/img/kullanilmayan-hero.webp', as: 'image', used: false }],
        preconnect: [{ host: 'fonts.example.org', used: false }],
        dns_prefetch: [{ host: 'analytics.example.net', used: true }],
        missing_preconnect: [{ host: 'cdn.cok-uzun-bir-icerik-dagitim-agi.example.test', requests: 1, bytes: 95 * KB, blocking: true }],
        fetchpriority: [{ url: LONG(1), value: 'high' }],
      },
      hosts: [
        { host: 'www.example.com', third_party: false, requests: 38, bytes: Math.round(1.5 * MB), http_version: '2', h3_advertised: false,
          dns_ms: 12, connect_ms: 18, tls_ms: 31, measured: true, reason: null },
        { host: 'cdn.cok-uzun-bir-icerik-dagitim-agi.example.test', third_party: true, requests: 1, bytes: 95 * KB, http_version: '2',
          h3_advertised: true, dns_ms: 40, connect_ms: 22, tls_ms: 48, measured: true, reason: null },
        { host: 'cok-uzun-bir-icerik-dagitim-agi-adresi-1.example.test', third_party: true, requests: 1, bytes: Math.round(1.6 * MB),
          http_version: '1.1', h3_advertised: false, dns_ms: null, connect_ms: null, tls_ms: null, measured: false, reason: 'proxy' },
        { host: 'analytics.example.net', third_party: true, requests: 3, bytes: 90 * KB, http_version: null, h3_advertised: false,
          dns_ms: null, connect_ms: null, tls_ms: null, measured: false, reason: 'not_measured' },
      ],
      hosts_total: 4, http1_first_party_hosts: 0,
      profiles: [
        { key: 'fiber', rtt_ms: 10, mbps: 100, load_ms: 820, first_paint_ms: 410 },
        { key: '4g', rtt_ms: 70, mbps: 9, load_ms: 4100, first_paint_ms: 1640 },
        { key: '3g', rtt_ms: 300, mbps: 1.6, load_ms: 19_800, first_paint_ms: 6200 },
        { key: 'slow3g', rtt_ms: 400, mbps: 0.4, load_ms: 74_500, first_paint_ms: 21_300 },
      ],
    },
    waste: {
      duplicates: [
        { kind: 'same_content', urls: [CDN_JQ, 'https://www.example.com/static/vendor/jquery.min.js'], bytes_wasted: 95 * KB },
        { kind: 'same_path', urls: ['https://www.example.com/img/logo.svg', 'https://www.example.com/img/logo.svg?v=2'], bytes_wasted: 6 * KB },
      ],
      libraries: [{ name: 'jquery', versions: ['1.12.4', '3.5.1'], urls: [CDN_JQ, OLD_JQ] }],
      unminified: [{ url: JS, bytes: 640 * KB, est_saving_bytes: 210 * KB }],
      source_maps: [{ url: JS, map_url: `${JS}.map`, exposed: true }, { url: CSS, map_url: `${CSS}.map`, exposed: false }],
      html: { html_bytes: 48 * KB, inline_script_bytes: 22 * KB, inline_style_bytes: 9 * KB, dom_nodes: 1850, max_depth: 24, data_uri_count: 3, data_uri_bytes: 14 * KB },
      caching: {
        buckets: { none: 2, lt_1h: 32, lt_1d: 0, lt_7d: 2, ge_7d: 3 }, no_validator: 5,
        versioned_short: [{ url: 'https://www.example.com/static/app.bundle.js?v=8f3e2a', ttl_s: 600 }],
        cdn: { hit: 1, miss: 0, unknown: 38 }, repeat_visit_bytes: Math.round(2.6 * MB), repeat_visit_pct: 76.5,
      },
    },
    media: {
      images: {
        missing_dimensions: manyImages(50), missing_dimensions_total: 60,
        missing_srcset: [{ url: LONG(1), width: 3840 }],
        not_lazy: [{ url: 'https://www.example.com/img/alt-banner.webp' }, { url: 'https://www.example.com/img/thumb-25.jpg' }],
        animated_gif: [{ url: 'https://www.example.com/img/kampanya-animasyonu-buyuk.gif', bytes: Math.round(1.2 * MB), frames: 48 }],
      },
      fonts: {
        count: 4, bytes: 310 * KB,
        families: [
          { family: 'Inter Variable Display Çok Uzun Aile Adı Kurumsal Sürüm', weights: ['400', '600', '700'], files: 3 },
          { family: 'Roboto Mono', weights: ['400'], files: 1 },
        ],
        non_woff2: [{ url: 'https://www.example.com/fonts/roboto-mono.ttf', format: 'ttf' }],
        missing_display: [{ family: 'Roboto Mono', css_url: CSS }],
        preloaded: 1,
      },
    },
    security: {
      score: 58, letter: 'E',
      headers: [
        { key: 'csp', status: 'weak', value: "default-src 'self' 'unsafe-inline' 'unsafe-eval' https:; img-src * data:", issue: 'unsafe_inline' },
        { key: 'hsts', status: 'ok', value: 'max-age=31536000; includeSubDomains', issue: null },
        { key: 'x_content_type_options', status: 'missing', value: null, issue: null },
        { key: 'frame_protection', status: 'missing', value: null, issue: null },
        { key: 'referrer_policy', status: 'ok', value: 'strict-origin-when-cross-origin', issue: null },
        { key: 'permissions_policy', status: 'missing', value: null, issue: null },
        { key: 'coop', status: 'weak', value: 'unsafe-none', issue: 'unsafe_none' },
        { key: 'corp', status: 'ok', value: 'same-site', issue: null },
      ],
      sri_missing: [{ url: CDN_JQ, kind: 'script' }],
      mixed_content: [{ url: 'http://eski-kampanya-sunucusu.example.test/banner.jpg' }],
      cookies: [
        { name: 'JSESSIONID', host: 'www.example.com', third_party: false, secure: true, http_only: true, same_site: 'Strict', issues: [] },
        { name: '_ga_cok_uzun_bir_izleme_cerezi_adi_ornek_X7Y8Z9', host: 'analytics.example.net', third_party: true, secure: false, http_only: false,
          same_site: null, issues: ['not_secure', 'no_same_site'] },
      ],
      libraries: [
        { name: 'jquery', version: '1.12.4', url: CDN_JQ,
          vulns: [
            { id: 'CVE-2020-11022', severity: 'medium', kind: 'xss', summary: 'Passing HTML from untrusted sources to manipulation methods may execute untrusted code.' },
            { id: 'CVE-2019-11358', severity: 'medium', kind: 'prototype_pollution', summary: 'Prototype pollution in jQuery.extend.' },
          ] },
        { name: 'jquery', version: '3.5.1', url: OLD_JQ, vulns: [] },
      ],
      catalog_date: '2026-10-15',
      third_party_categories: [
        { category: 'analytics', hosts: ['analytics.example.net'], requests: 3, bytes: 90 * KB },
        { category: 'cdn', hosts: ['cdn.cok-uzun-bir-icerik-dagitim-agi.example.test', 'cok-uzun-bir-icerik-dagitim-agi-adresi-1.example.test'],
          requests: 2, bytes: Math.round(1.7 * MB) },
        { category: 'advertising', hosts: ['ads.reklam-agi.example.test'], requests: 1, bytes: 4 * KB },
      ],
      tracking_on_load: [{ host: 'analytics.example.net', category: 'analytics' }, { host: 'ads.reklam-agi.example.test', category: 'advertising' }],
    },
    html_audit: {
      seo: {
        score: 85,
        checks: [
          { key: 'title', status: 'warn', issue: 'too_long', value: 'Kurumsal Ana Sayfa — Ürünler, Hizmetler ve Çok Uzun Bir Kampanya Başlığı', count: 1 },
          { key: 'meta_description', status: 'fail', issue: 'missing', value: null, count: 0 },
          { key: 'h1', status: 'warn', issue: 'multiple', value: 'Hoş geldiniz', count: 2 },
          { key: 'canonical', status: 'pass', issue: null, value: 'https://www.example.com/', count: 1 },
          { key: 'robots', status: 'pass', issue: null, value: 'index, follow', count: 1 },
          { key: 'lang', status: 'pass', issue: null, value: 'tr', count: 1 },
          { key: 'viewport', status: 'pass', issue: null, value: 'width=device-width, initial-scale=1', count: 1 },
          { key: 'open_graph', status: 'warn', issue: 'partial', value: 'og:title, og:url', count: 2 },
          { key: 'structured_data', status: 'pass', issue: null, value: 'Organization', count: 1 },
        ],
      },
      accessibility: {
        score: null,
        checks: [
          { key: 'img_alt', status: 'fail', issue: null, count: 7, samples: ['img[src=…/urun-galeri-01.jpg]', 'img[src=…/urun-galeri-02.jpg]',
            'img[src=…/ana-sayfa-banner-yuksek-cozunurluk-varyant-cok-uzun-dosya-adi.webp]'] },
          { key: 'form_labels', status: 'warn', issue: null, count: 1, samples: ['input[type=email][name=bulten-eposta]'] },
          { key: 'link_names', status: 'pass', issue: null, count: 0, samples: [] },
          { key: 'button_names', status: 'warn', issue: null, count: 2, samples: ['button#menu-toggle', 'button#kapat'] },
          { key: 'heading_order', status: 'warn', issue: 'skipped', count: 1, samples: ['h2 → h4'] },
          { key: 'html_lang', status: 'pass', issue: null, count: 0, samples: [] },
          { key: 'duplicate_ids', status: 'pass', issue: null, count: 0, samples: [] },
        ],
      },
    },
  }
}

/** Algoritma 3 analizin ayrıntısı. */
export function scanDetailV3(id = 12, over = {}) {
  return {
    scan: scanSummary(id, { algorithm_version: 3, redirect_count: 2, redirect_ms: 480, ttfb_ms: 300, owner_pod: 'site-monitor-7f9c-abcde',
      js_bytes: 824 * KB, css_bytes: 120 * KB, font_bytes: 310 * KB, security_score: 58, seo_score: 85, a11y_score: null, ...over.scan }),
    analysis: scanAnalysisV3(), items: scanItemsV3(), items_kept: true, ...over.rest,
  }
}

export { JS, CSS, CDN_JQ, OLD_JQ, KB, MB }
