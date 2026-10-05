/**
 * Sayfa Bütünlüğü + Sayfa Hızı uçtan uca tanılama test verileri (2026-10-05) — sözleşmenin (POST
 * /api/monitoring/{page|pagespeed}/{id}/diagnose) yanıt biçiminde. Örnek adlar example.com / example.test, IP'ler RFC 5737.
 *   • pageMonitor / speedMonitor: izleme satırları (can_diagnose verilebilir)
 *   • pageBroken(): izlemenin yolu DOĞRUDAN; sayfa 200, 3 kırık kaynak (biri kendi sitede, alarm) → RESOURCES_BROKEN
 *   • pagePathDiffers(): izlemenin yolu VEKİL (403), öteki yol DOĞRUDAN (sağlam) → PATH_DIFFERS reason=page
 *   • speedSlow(): eşik aşımı (LOAD + SIZE), yavaş sunucu fazı, ağır görsel → THRESHOLD_BREACH (LOAD)
 */
export const pageMonitor = {
  id: 9, name: 'Mağaza vitrini', url: 'https://shop.example.com/', mode: 'SINGLE_PAGE', crawl_max_pages: 50,
  status: 'DEGRADED', ok: false, http_status: 200, response_ms: 640, total_resources: 42, broken_resources: 3,
  timeout_count: 0, mixed_content_count: 0, team_id: 5, team_name: 'Takım A', active: true, interval_seconds: 300,
  timeout_ms: 4000, slow_resource_ms: 2000, checked_at: '2026-10-05T09:00:00', proxy_effective: 'direct',
  proxy_source: 'monitor', use_proxy: 'AUTO', can_check: true,
}

export const speedMonitor = {
  id: 11, name: 'Vitrin hızı', url: 'https://shop.example.com/', status: 'SLOW', ok: true, response_ms: 9100,
  ttfb_ms: 240, total_bytes: 4194304, request_count: 64, team_id: 5, team_name: 'Takım A', active: true,
  interval_seconds: 1800, timeout_ms: 10000, max_load_ms: 5000, max_ttfb_ms: 800, max_page_kb: 2000, max_requests: null,
  checked_at: '2026-10-05T09:00:00', proxy_effective: 'direct', proxy_source: 'monitor', use_proxy: 'OFF', can_check: true,
}

function hop(url, status, { route = 'direct', title = 'Mağaza', bodyText = 'Merhaba', host = 'shop.example.com' } = {}) {
  return {
    index: 0, url, method: 'GET',
    steps: [
      { key: 'dns', status: 'ok', ms: 2 }, { key: route === 'proxy' ? 'proxy_connect' : 'tcp', status: 'ok', ms: 3 },
      { key: 'tls', status: 'ok', ms: 11 }, { key: 'request', status: 'ok', ms: 0 }, { key: 'response', status: 'ok', ms: 40 },
      { key: 'body', status: 'ok', ms: 6 },
    ],
    dns: { host, addresses: ['192.0.2.10'], ms: 2, via_proxy: route === 'proxy', error: null },
    tcp: { remote: '192.0.2.10:443', local: '10.0.0.5:51000', ms: 3, attempts: [] },
    proxy: route === 'proxy' ? { host: 'proxy.example.com', port: 8080, mode: 'connect', connect_request: [], connect_response: null, ms: 4 } : null,
    tls: null,
    request: { line: 'GET / HTTP/1.1', headers: [
      { name: 'Host', value: host, masked: false },
      { name: 'User-Agent', value: 'Mozilla/5.0 (compatible; SiteMonitor-PageCheck/1.0; +https://sitemonitor)', masked: false },
      { name: 'Accept-Encoding', value: 'gzip, deflate', masked: false },
    ], body_bytes: 0, sent_ms: 0 },
    response: {
      status_line: `HTTP/1.1 ${status}`, status, http_version: 'HTTP/1.1',
      headers: [{ name: 'Content-Type', value: 'text/html', masked: false }, { name: 'Content-Encoding', value: 'gzip', masked: false }],
      ttfb_ms: 40,
      body: { bytes: 2400, complete: true, content_type: 'text/html', text: true,
        preview: `<html><title>${title}</title><body>${bodyText}</body></html>`, preview_truncated: false, download_ms: 6,
        content_encoding: 'gzip' },
    },
    redirect: null,
  }
}

const timeline = (extra = {}) => ({ dns_ms: 2, connect_ms: 3, proxy_ms: 0, tls_ms: 11, ttfb_ms: 40, download_ms: 6, total_ms: 64, ...extra })

const LONG = 'https://cdn.cok-uzun-bir-icerik-dagitim-agi-adresi.example.test/assets/2026/10/kampanya/gorseller/ana-sayfa-banner-yuksek-cozunurluk-v3.webp?token=*****'

export function pageBroken() {
  return {
    run_id: 601, kind: 'page', started_at: '2026-10-05T09:05:00Z', duration_ms: 1840, executed_by: 'kullanici-a',
    monitor: { id: 9, name: 'Mağaza vitrini', url: 'https://shop.example.com/', method: 'GET', mode: 'SINGLE_PAGE', timeout_ms: 4000,
      diag_timeout_ms: 4000, verify_ssl: false, follow_redirects: true, proxy_mode: 'AUTO', slow_resource_ms: 2000, check_budget_s: 45 },
    source: { pod: 'sitemonitor-abc', node: null, pod_ip: '10.0.0.5' },
    proxy: { configured: false, host: null, port: null, auth: false, no_proxy: '' },
    verdict: { status: 'fail', code: 'RESOURCES_BROKEN', failed_step: 'resources', path: 'monitor',
      params: { count: 3, alarm: 1, total: 42, sample: 'https://shop.example.com/img/yok.png' } },
    findings: [
      { code: 'RESOURCES_BROKEN', severity: 'fail', path: 'monitor', params: { count: 3, alarm: 1, total: 42, sample: 'https://shop.example.com/img/yok.png' } },
      { code: 'SAME_HOST_BROKEN', severity: 'warn', path: 'monitor', params: { count: 1, host: 'shop.example.com', sample: 'https://shop.example.com/img/yok.png' } },
      { code: 'RESOURCES_BLOCKED', severity: 'info', path: 'monitor', params: { count: 1, sample: 'https://shop.example.com/admin.js' } },
    ],
    paths: [{
      key: 'monitor', route: 'direct', decision: { source: 'monitor', wanted: false, bypassed: false }, outcome: 'fail',
      http_status: 200, total_ms: 64, failed_step: 'resources', error: null, timeline: timeline(),
      hops: [hop('https://shop.example.com/', 200)], transcript: ['* Resolving shop.example.com', '> GET / HTTP/1.1', '< HTTP/1.1 200'],
      client_check: { ok: false, main_reachable: true, http_status: 200, response_ms: 1700, http_version: null, error: null, status: 'DEGRADED',
        failure_reason: null, resources: 42, broken: 3, timeouts: 0, mixed: 0 },
      page: { recorded_status: 'DEGRADED', resources: 42, broken: 3, timeouts: 0, mixed: 0, alarm: 1 },
    }],
    comparison: { available: false, differs: false },
    page: {
      path: 'monitor', analyzed: true, mode: 'SINGLE_PAGE', monitor_mode: 'SINGLE_PAGE', crawl_max_pages: 50,
      recorded_status: 'DEGRADED', monitor_ok: false, main_reachable: true, http_status: 200, response_ms: 1700, body_bytes: 48200,
      failure_reason: null, error: null,
      totals: { resources: 42, broken: 3, timeouts: 0, mixed: 0, blocked: 1, slow: 0, first_party: 1, third_party: 2, alarm: 1 },
      issues: [
        { url: 'https://shop.example.com/img/yok.png', resource_type: 'IMG', kind: 'BROKEN', status: 404, ms: 12, first_party: true, alarm: true, via: 'direct', source_page: null },
        { url: LONG, resource_type: 'IMG', kind: 'BROKEN', status: 404, ms: 33, first_party: false, alarm: false, via: 'direct', source_page: null },
        { url: 'https://social.example.test/paylas', resource_type: 'LINK', kind: 'BROKEN', status: null, ms: 8, first_party: false, alarm: false, via: 'direct', source_page: null },
        { url: 'https://shop.example.com/admin.js', resource_type: 'JS', kind: 'BLOCKED', status: 403, ms: 9, first_party: true, alarm: false, via: 'direct', source_page: null },
      ],
      issues_total: 4,
      limits: { resource_cap: 500, resource_cap_hit: false, time_budget_s: 45, time_budget_hit: false },
      toggles: { alert_third_party: false, alert_mixed_content: true, alert_timeout: true, slow_resource_ms: 2000 },
    },
  }
}

export function pagePathDiffers() {
  const d = pageBroken()
  d.run_id = 602
  d.proxy = { configured: true, host: 'proxy.example.com', port: 8080, auth: false, no_proxy: '' }
  d.verdict = { status: 'fail', code: 'PATH_DIFFERS', failed_step: 'response', path: 'monitor',
    params: { failing_route: 'proxy', working_route: 'direct', working_status: 200, reason: 'page' } }
  d.findings = [
    { code: 'PATH_DIFFERS', severity: 'fail', path: 'monitor', params: { failing_route: 'proxy', working_route: 'direct', working_status: 200, reason: 'page' } },
    { code: 'PAGE_HTTP_STATUS', severity: 'fail', path: 'monitor', params: { status: 403, route: 'proxy' } },
    { code: 'PAGE_OK', severity: 'info', path: 'alternate', params: { status: 200, resources: 42, ms: 900 } },
  ]
  d.paths = [
    { ...d.paths[0], route: 'proxy', http_status: 403, failed_step: 'response', outcome: 'fail',
      hops: [hop('https://shop.example.com/', 403, { route: 'proxy', title: 'Access Denied', bodyText: 'Rejected' })],
      client_check: { ok: false, main_reachable: false, http_status: 403, response_ms: 30, http_version: null, error: 'ana sayfa HTTP 403',
        status: 'DOWN', failure_reason: 'HTTP_STATUS' },
      page: { recorded_status: 'DOWN', resources: null, broken: null, timeouts: null, mixed: null, alarm: null } },
    { ...d.paths[0], key: 'alternate', route: 'direct', decision: { source: 'compare', wanted: false, bypassed: false }, outcome: 'ok',
      failed_step: null, client_check: { ok: true, main_reachable: true, http_status: 200, response_ms: 900, http_version: null, error: null,
        status: 'OK', failure_reason: null, resources: 42, broken: 0, timeouts: 0, mixed: 0 },
      page: { recorded_status: 'OK', resources: 42, broken: 0, timeouts: 0, mixed: 0, alarm: 0 } },
  ]
  d.comparison = { available: true, differs: true }
  d.page = { ...d.page, analyzed: false, recorded_status: 'DOWN', monitor_ok: false, main_reachable: false, http_status: 403,
    failure_reason: 'HTTP_STATUS', issues: [], issues_total: 0,
    totals: { resources: null, broken: null, timeouts: null, mixed: null, blocked: null, slow: null, first_party: null, third_party: null, alarm: null } }
  return d
}

export function speedSlow() {
  return {
    run_id: 701, kind: 'pagespeed', started_at: '2026-10-05T09:10:00Z', duration_ms: 9800, executed_by: 'kullanici-a',
    monitor: { id: 11, name: 'Vitrin hızı', url: 'https://shop.example.com/', method: 'GET', timeout_ms: 10000, diag_timeout_ms: 10000,
      verify_ssl: false, follow_redirects: true, proxy_mode: 'OFF', user_agent: 'SiteMonitor-PageSpeed/1.0', check_budget_s: 45,
      advanced: { custom_headers: 1, basic_auth: true, send_dnt: false, exclude_trackers: true },
      thresholds: { max_load_ms: 5000, max_ttfb_ms: 800, max_page_kb: 2000, max_requests: null } },
    source: { pod: 'sitemonitor-abc', node: null, pod_ip: '10.0.0.5' },
    proxy: { configured: false, host: null, port: null, auth: false, no_proxy: '' },
    verdict: { status: 'warn', code: 'THRESHOLD_BREACH', failed_step: null, path: 'monitor',
      params: { reason: 'LOAD', value: 9100, limit: 5000, over: 4100, unit: 'ms' } },
    findings: [
      { code: 'THRESHOLD_BREACH', severity: 'warn', path: 'monitor', params: { reason: 'LOAD', value: 9100, limit: 5000, over: 4100, unit: 'ms' } },
      { code: 'THRESHOLD_BREACH', severity: 'warn', path: 'monitor', params: { reason: 'SIZE', value: 4096, limit: 2000, over: 2096, unit: 'KB' } },
      { code: 'SLOW_SERVER', severity: 'warn', path: 'monitor', params: { ms: 1200, limit: 800, share: 80, route: 'direct', reason: 'threshold' } },
      { code: 'SLOW_RESOURCES', severity: 'warn', path: 'monitor', params: { html_ms: 1500, total_ms: 9100, count: 63, top_url: LONG, top_ms: 6800 } },
      { code: 'HEAVY_RESOURCES', severity: 'warn', path: 'monitor', params: { count: 3, share: 78, top_url: LONG, top_kb: 3072, kb: 4096 } },
    ],
    paths: [{
      key: 'monitor', route: 'direct', decision: { source: 'monitor', wanted: false, bypassed: false }, outcome: 'slow',
      http_status: 200, total_ms: 1500, failed_step: null, error: null, timeline: timeline({ ttfb_ms: 1200, download_ms: 250, total_ms: 1500 }),
      hops: [hop('https://shop.example.com/', 200)], transcript: ['* Resolving shop.example.com'],
      client_check: { ok: true, http_status: 200, response_ms: 9100, http_version: null, error: null, status: 'SLOW', failure_reason: null,
        total_bytes: 4194304, requests: 64, breached: ['LOAD', 'SIZE'] },
      pagespeed: { status: 'SLOW', total_ms: 9100, total_bytes: 4194304, requests: 64, breached: ['LOAD', 'SIZE'] },
    }],
    comparison: { available: false, differs: false },
    pagespeed: {
      path: 'monitor', analyzed: true,
      thresholds: { max_load_ms: 5000, max_ttfb_ms: 800, max_page_kb: 2000, max_requests: null },
      measured: { status: 'SLOW', http_status: 200, ttfb_ms: 760, server_ms: 690, html_ms: 1500, total_ms: 9100, total_bytes: 4194304,
        request_count: 64, failed_count: 1, capped: false, bytes_truncated: false, skipped_lazy: 6, breached: ['LOAD', 'SIZE'], error: null,
        failure_reason: null, phases: { dns_ms: 2, connect_ms: 3, tls_ms: 11, server_ms: 690, error: null } },
      metrics: [
        { key: 'LOAD', value: 9100, limit: 5000, unit: 'ms', breached: true, over: 4100, ratio: 182 },
        { key: 'TTFB', value: 690, limit: 800, unit: 'ms', breached: false, over: null, ratio: 86 },
        { key: 'SIZE', value: 4096, limit: 2000, unit: 'KB', breached: true, over: 2096, ratio: 205 },
        { key: 'REQUESTS', value: 64, limit: null, unit: 'req', breached: false, over: null, ratio: null },
      ],
      routes: [{ key: 'monitor', route: 'direct', dns_ms: 2, connect_ms: 3, proxy_ms: 0, tls_ms: 11, ttfb_ms: 1200, download_ms: 250,
        total_ms: 1500, body_bytes: 48200, http_status: 200, measured_total_ms: 9100, measured_bytes: 4194304 }],
      phase_limits: { dns_ms: 500, connect_ms: 500, proxy_ms: 1000, tls_ms: 1000, ttfb_ms: 800, download_ms: 2000 },
      heaviest: [
        { url: LONG, type: 'IMG', bytes: 3145728, ms: 6800, status: 200, third_party: true, failed: false, truncated: false },
        { url: 'https://shop.example.com/app.js', type: 'JS', bytes: 614400, ms: 900, status: 200, third_party: false, failed: false, truncated: false },
      ],
      slowest: [
        { url: LONG, type: 'IMG', bytes: 3145728, ms: 6800, status: 200, third_party: true, failed: false, truncated: false },
        { url: 'https://cdn.example.test/x.js', type: 'JS', bytes: 0, ms: 4000, status: 404, third_party: true, failed: true, truncated: false },
      ],
      by_type: [{ type: 'IMG', count: 30, bytes: 3500000 }, { type: 'JS', count: 12, bytes: 640000 }],
    },
  }
}

export const DIAG_HISTORY_ROWS = [
  { id: 601, started_at: '2026-10-05T09:05:00Z', executed_by: 'kullanici-a', verdict_code: 'RESOURCES_BROKEN', verdict_status: 'fail',
    monitor_route: 'direct', monitor_http_status: 200, alternate_route: null, alternate_http_status: null, findings: 3, duration_ms: 1840 },
]
