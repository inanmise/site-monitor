/**
 * Keyword uçtan uca tanılama + kontrol geçmişi teşhisi test verileri (2026-10-04) — sözleşmenin (POST
 * /api/monitoring/keyword/{id}/diagnose, GET …/keyword/{id}/history) yanıt biçiminde. Örnek adlar example.com.
 *   • kwMonitor: izleme satırı (can_diagnose verilebilir)
 *   • failedChecks(): biri yeni (neden + ipuçları + alıntı + meta), biri ESKİ (yalnız hata metni) başarısız satır + bir başarılı satır
 *   • kwPathDiffers(): izlemenin yolu VEKİL (WAF 403 sayfası, kelime yok), öteki yol DOĞRUDAN (200, kelime 2 kez)
 */
export const kwMonitor = {
  id: 7, name: 'Kampanya sayfası', url: 'https://shop.example.com/kampanya', keyword: 'Kampanya', operator: 'GTE',
  match_count: 1, case_sensitive: false, group_name: 'G1', tags: 'prod', team_id: 5, team_name: 'Takım A',
  status: 'down', http_status: 403, occurrences: 0, found: false, ok: false, response_ms: 120, interval_seconds: 300,
  timeout_ms: 8000, active: true, checked_at: '2026-10-04T09:00:00', proxy_effective: 'proxy', proxy_source: 'monitor',
  use_proxy: 'ON', can_check: true, failure_reason: 'HTTP_STATUS', hints: ['WAF_OR_BLOCK_PAGE'],
}

export function failedChecks() {
  return {
    success: true,
    data: {
      items: [
        {
          id: 31, checked_at: '2026-10-04T09:00:00', ok: false, found: false, occurrences: 0, http_status: 403,
          response_ms: 120, error: null, failure_reason: 'HTTP_STATUS',
          failure_detail: 'Sunucu HTTP 403 döndürdü; gelen sayfada « Kampanya » bulunamadı.',
          final_url: 'https://shop.example.com/kampanya', redirect_count: 0, content_type: 'text/html',
          body_bytes: 2048, body_truncated: false, charset: 'UTF-8', via: 'proxy',
          hints: ['WAF_OR_BLOCK_PAGE', 'LOGIN_PAGE'],
          excerpt: 'Access Denied The requested URL was rejected. Your support ID is: 123',
        },
        { id: 30, checked_at: '2026-10-04T08:55:00', ok: false, found: false, occurrences: 0, http_status: null, response_ms: 8001,
          error: 'request timed out' },
        { id: 29, checked_at: '2026-10-04T08:50:00', ok: true, found: true, occurrences: 2, http_status: 200, response_ms: 98,
          snippet: 'Yeni Kampanya başladı', failure_reason: null, hints: [] },
      ],
      counts: { total: 3, fail: 2 }, buckets: [], alerts: [], range: {}, total: 3, page: 0, size: 50,
    },
  }
}

function hop(url, status, title, bodyText) {
  return {
    index: 0, url, method: 'GET',
    steps: [
      { key: 'dns', status: 'ok', ms: 1 }, { key: 'tcp', status: 'ok', ms: 2 }, { key: 'tls', status: 'ok', ms: 9 },
      { key: 'request', status: 'ok', ms: 0 }, { key: 'response', status: 'ok', ms: 20 }, { key: 'body', status: 'ok', ms: 2 },
    ],
    dns: { host: 'shop.example.com', addresses: ['192.0.2.10'], ms: 1, via_proxy: false, error: null },
    tcp: { remote: '192.0.2.10:443', local: '10.0.0.5:51000', ms: 2, attempts: [] },
    proxy: null, tls: null,
    request: { line: 'GET /kampanya HTTP/1.1', headers: [
      { name: 'Host', value: 'shop.example.com', masked: false },
      { name: 'User-Agent', value: 'SiteMonitor-KeywordMonitor/1.0', masked: false },
      { name: 'Authorization', value: '••••', masked: true },
    ], body_bytes: 0, sent_ms: 0 },
    response: {
      status_line: `HTTP/1.1 ${status}`, status, http_version: 'HTTP/1.1', headers: [{ name: 'Content-Type', value: 'text/html', masked: false }],
      ttfb_ms: 20,
      body: { bytes: bodyText.length, complete: true, content_type: 'text/html', text: true,
        preview: `<html><title>${title}</title><body>${bodyText}</body></html>`, preview_truncated: false, download_ms: 2 },
    },
    redirect: null,
  }
}

export function kwPathDiffers() {
  return {
    run_id: 501,
    kind: 'keyword',
    started_at: '2026-10-04T09:05:00Z',
    duration_ms: 812,
    executed_by: 'kullanici-a',
    monitor: {
      id: 7, name: 'Kampanya sayfası', url: 'https://shop.example.com/kampanya', method: 'GET', keyword: 'Kampanya',
      operator: 'GTE', match_count: 1, case_sensitive: false, timeout_ms: 8000, diag_timeout_ms: 8000, verify_ssl: false,
      follow_redirects: true, proxy_mode: 'ON', advanced: { custom_headers: 1, slow_threshold_ms: null },
    },
    source: { pod: 'sitemonitor-abc', node: null, pod_ip: '10.0.0.5' },
    proxy: { configured: true, host: 'proxy.example.com', port: 8080, auth: false, no_proxy: '' },
    verdict: {
      status: 'fail', code: 'PATH_DIFFERS', failed_step: 'response', path: 'monitor',
      params: { failing_route: 'proxy', working_route: 'direct', working_status: 200, reason: 'keyword' },
    },
    findings: [
      { code: 'PATH_DIFFERS', severity: 'fail', path: 'monitor',
        params: { failing_route: 'proxy', working_route: 'direct', working_status: 200, reason: 'keyword' } },
      { code: 'KEYWORD_HTTP_ERROR', severity: 'fail', path: 'monitor',
        params: { keyword: 'Kampanya', count: 0, expected: 'en az 1 kez', status: 403, bytes: 120, cap_kb: 1953 } },
      { code: 'WAF_OR_BLOCK_PAGE', severity: 'warn', path: 'monitor', params: { keyword: 'Kampanya', status: 403 } },
      { code: 'KEYWORD_OK', severity: 'info', path: 'alternate',
        params: { keyword: 'Kampanya', count: 2, expected: 'en az 1 kez', status: 200, bytes: 300, cap_kb: 1953 } },
    ],
    paths: [
      {
        key: 'monitor', route: 'proxy', decision: { source: 'monitor', wanted: true, bypassed: false },
        outcome: 'fail', http_status: 403, total_ms: 60, failed_step: 'response', error: null,
        timeline: { dns_ms: 1, connect_ms: 2, proxy_ms: 5, tls_ms: 9, ttfb_ms: 20, download_ms: 2, total_ms: 60 },
        hops: [hop('https://shop.example.com/kampanya', 403, 'Access Denied', 'The requested URL was rejected')],
        transcript: ['* Connecting to proxy proxy.example.com:8080', '> GET /kampanya HTTP/1.1', '> Authorization: ••••', '< HTTP/1.1 403'],
        client_check: { ok: false, http_status: 403, response_ms: 70, http_version: null, error: null, occurrences: 0, failure_reason: 'HTTP_STATUS' },
        keyword: { occurrences: 0, condition_met: false, failure_reason: 'HTTP_STATUS' },
      },
      {
        key: 'alternate', route: 'direct', decision: { source: 'compare', wanted: false, bypassed: false },
        outcome: 'ok', http_status: 200, total_ms: 40, failed_step: null, error: null,
        timeline: { dns_ms: 1, connect_ms: 2, proxy_ms: 0, tls_ms: 9, ttfb_ms: 20, download_ms: 2, total_ms: 40 },
        hops: [hop('https://shop.example.com/kampanya', 200, 'Mağaza', 'Kampanya başladı. Yeni kampanya ürünleri')],
        transcript: ['> GET /kampanya HTTP/1.1', '< HTTP/1.1 200'],
        client_check: { ok: true, http_status: 200, response_ms: 45, http_version: null, error: null, occurrences: 2, failure_reason: null },
        keyword: { occurrences: 2, condition_met: true, failure_reason: null },
      },
    ],
    comparison: { available: true, differs: true },
    keyword: {
      path: 'monitor', keyword: 'Kampanya', case_sensitive: false, operator: 'GTE', match_count: 1, absence_rule: false,
      analyzed: true, occurrences: 0, condition_met: false, failure_reason: 'HTTP_STATUS', http_status: 403,
      final_url: 'https://shop.example.com/kampanya', redirect_count: 0, content_type: 'text/html', text_like: true,
      body_bytes: 120, body_truncated: false, checker_cap_bytes: 2000000, charset: null,
      alternatives: { raw: 0, case_insensitive: 0, normalized: 0, charset: 0, charset_name: null, visible_text: 0 },
      hints: ['WAF_OR_BLOCK_PAGE'], title: 'Access Denied',
      contexts: [],
      visible_text_preview: 'Access Denied The requested URL was rejected',
      visible_text_truncated: false, preview_stored: true,
    },
  }
}

/** Kelime BULUNAN, alternatif okumanın fazlasını bulduğu tek yollu sonuç (vekil yok). */
export function kwFoundSingle() {
  const d = kwPathDiffers()
  d.run_id = 502
  d.verdict = { status: 'ok', code: 'KEYWORD_OK', failed_step: null, path: 'monitor', params: { keyword: 'Kampanya', count: 2, expected: 'en az 1 kez', status: 200 } }
  d.findings = [{ code: 'KEYWORD_OK', severity: 'info', path: 'monitor', params: { keyword: 'Kampanya', count: 2, expected: 'en az 1 kez', status: 200 } }]
  d.paths = [{ ...d.paths[1], key: 'monitor', route: 'direct' }]
  d.comparison = { available: false, differs: false }
  d.proxy = { configured: false, host: null, port: null, auth: false, no_proxy: '' }
  d.keyword = {
    ...d.keyword, occurrences: 2, condition_met: true, failure_reason: null, http_status: 200, hints: [],
    alternatives: { raw: 2, case_insensitive: 2, normalized: 3, charset: 0, charset_name: null, visible_text: 2 },
    contexts: [
      { before: 'Mağaza ', match: 'Kampanya', after: ' başladı.', source: 'visible' },
      { before: 'Yeni ', match: 'kampanya', after: ' ürünleri', source: 'visible' },
    ],
    visible_text_preview: 'Mağaza Kampanya başladı. Yeni kampanya ürünleri',
  }
  return d
}

export const KW_HISTORY_ROWS = [
  { id: 501, started_at: '2026-10-04T09:05:00Z', executed_by: 'kullanici-a', verdict_code: 'PATH_DIFFERS', verdict_status: 'fail',
    monitor_route: 'proxy', monitor_http_status: 403, alternate_route: 'direct', alternate_http_status: 200, occurrences: 0, duration_ms: 812 },
]
