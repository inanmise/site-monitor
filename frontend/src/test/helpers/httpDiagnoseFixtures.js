/**
 * HTTP uçtan uca tanılama test verileri (2026-10-02) — sözleşmenin (POST /api/monitoring/http/{id}/diagnose) yanıt
 * biçiminde. İki senaryo:
 *   • pathDiffers(): bugünkü prod olayı — izlemenin yolu VEKİL, istek gitti ama yanıt gelmedi (RESPONSE_TIMEOUT, 10 sn);
 *     öteki yol DOĞRUDAN 24 ms'de HTTP 401 aldı (302 → /login → 401, iki hop). Maskeli başlıklar, kesik gövde, döküm.
 *   • okSingle(): vekil yok — tek yol, her şey yolunda (karşılaştırma yok).
 */
export const SECRET = 'Bearer s3cr3t-token-XYZ'

export function pathDiffers() {
  return {
    run_id: 123,
    started_at: '2026-10-02T10:49:53Z',
    duration_ms: 10432,
    executed_by: 'erdi',
    monitor: {
      id: 36, name: 'Binbirfikir', url: 'http://binbirfikir.example.com/', method: 'GET', expected_status: '200',
      timeout_ms: 10000, verify_ssl: true, follow_redirects: true, proxy_mode: 'ON',
      advanced: { custom_headers: 2, basic_auth: true, body_bytes: 0, json_path: null, slow_threshold_ms: null },
    },
    source: { pod: 'sitemonitor-7d9f-snm45', node: 'worker-22.example.local', pod_ip: '100.80.15.128' },
    proxy: { configured: true, host: 'dmzproxy.example.local', port: 8080, auth: false, no_proxy: 'example.org' },
    verdict: {
      status: 'fail', code: 'PATH_DIFFERS', failed_step: 'response', path: 'monitor',
      params: { failing_route: 'proxy', working_route: 'direct', working_status: 401 },
    },
    findings: [
      { code: 'PATH_DIFFERS', severity: 'fail', path: 'monitor', params: { failing_route: 'proxy', working_route: 'direct', working_status: 401 } },
      { code: 'RESPONSE_TIMEOUT', severity: 'fail', path: 'monitor', params: { ms: 10000, route: 'proxy' } },
      { code: 'AUTH_REQUIRED', severity: 'info', path: 'alternate', params: { status: 401, expected: '200' } },
    ],
    paths: [
      {
        key: 'monitor', route: 'proxy',
        decision: { source: 'monitor', wanted: true, bypassed: false },
        outcome: 'fail', http_status: null, total_ms: 10002, failed_step: 'response',
        error: { class: 'java.net.SocketTimeoutException', message: 'Read timed out', chain: ['java.net.SocketTimeoutException: Read timed out'] },
        timeline: { dns_ms: 0, connect_ms: 3, proxy_ms: 2, tls_ms: 0, ttfb_ms: null, download_ms: null, total_ms: 10002 },
        hops: [{
          index: 0, url: 'http://binbirfikir.example.com/', method: 'GET',
          steps: [
            { key: 'dns', status: 'ok', ms: 0 }, { key: 'proxy_connect', status: 'ok', ms: 3 },
            { key: 'request', status: 'ok', ms: 1 }, { key: 'response', status: 'fail', ms: 10000 },
          ],
          dns: { host: 'binbirfikir.example.com', addresses: ['172.31.37.113'], ms: 0, via_proxy: true, error: null },
          tcp: { remote: 'dmzproxy.example.local:8080', local: '100.80.15.128:51234', ms: 3, attempts: [{ address: '10.0.0.8:8080', ms: 3, error: null }] },
          proxy: { host: 'dmzproxy.example.local', port: 8080, mode: 'absolute', connect_request: null, connect_response: null, ms: 2 },
          tls: null,
          request: {
            line: 'GET http://binbirfikir.example.com/ HTTP/1.1',
            headers: [
              { name: 'Host', value: 'binbirfikir.example.com', masked: false },
              { name: 'Authorization', value: '••••', masked: true },
              { name: 'X-Api-Key', value: SECRET, masked: true },   // savunma: maskeli dendiyse değer HİÇ çizilmez
            ],
            body_bytes: 0, sent_ms: 1,
          },
          response: null,
          redirect: null,
        }],
        transcript: [
          '* Resolving binbirfikir.example.com via proxy',
          '* Connecting to proxy dmzproxy.example.local:8080...',
          '* Connected (3 ms)',
          '> GET http://binbirfikir.example.com/ HTTP/1.1',
          '> Host: binbirfikir.example.com',
          '> Authorization: ••••',
          '>',
          '* No response within 10000 ms',
        ],
        client_check: { ok: false, http_status: null, response_ms: 10001, http_version: null, error: 'request timed out' },
      },
      {
        key: 'alternate', route: 'direct',
        decision: { source: 'compare', wanted: false, bypassed: false },
        outcome: 'fail', http_status: 401, total_ms: 24, failed_step: null,
        error: null,
        timeline: { dns_ms: 1, connect_ms: 2, proxy_ms: null, tls_ms: null, ttfb_ms: 18, download_ms: 3, total_ms: 24 },
        hops: [
          {
            index: 0, url: 'http://binbirfikir.example.com/', method: 'GET',
            steps: [
              { key: 'dns', status: 'ok', ms: 1 }, { key: 'tcp', status: 'ok', ms: 2 },
              { key: 'request', status: 'ok', ms: 0 }, { key: 'response', status: 'ok', ms: 6 },
            ],
            dns: { host: 'binbirfikir.example.com', addresses: ['172.31.37.113'], ms: 1, via_proxy: false, error: null },
            tcp: { remote: '172.31.37.113:80', local: '100.80.15.128:51240', ms: 2, attempts: [{ address: '172.31.37.113:80', ms: 2, error: null }] },
            proxy: null, tls: null,
            request: { line: 'GET / HTTP/1.1', headers: [{ name: 'Host', value: 'binbirfikir.example.com', masked: false }], body_bytes: 0, sent_ms: 0 },
            response: {
              status_line: 'HTTP/1.1 302 Found', status: 302, http_version: 'HTTP/1.1',
              headers: [{ name: 'Location', value: '/login', masked: false }], ttfb_ms: 6,
              body: { bytes: 0, complete: true, content_type: null, text: true, preview: '', preview_truncated: false, download_ms: 0 },
            },
            redirect: { status: 302, location: '/login', next_url: 'http://binbirfikir.example.com/login', cross_host: false, extras_dropped: false },
          },
          {
            index: 1, url: 'http://binbirfikir.example.com/login', method: 'GET',
            steps: [
              { key: 'dns', status: 'skip', ms: null }, { key: 'tcp', status: 'ok', ms: 1 },
              { key: 'request', status: 'ok', ms: 0 }, { key: 'response', status: 'ok', ms: 12 }, { key: 'body', status: 'ok', ms: 3 },
            ],
            dns: null,
            tcp: { remote: '172.31.37.113:80', local: '100.80.15.128:51241', ms: 1, attempts: [] },
            proxy: null, tls: null,
            request: { line: 'GET /login HTTP/1.1', headers: [{ name: 'Host', value: 'binbirfikir.example.com', masked: false }], body_bytes: 0, sent_ms: 0 },
            response: {
              status_line: 'HTTP/1.1 401 Unauthorized', status: 401, http_version: 'HTTP/1.1',
              headers: [
                { name: 'WWW-Authenticate', value: 'Negotiate', masked: false },
                { name: 'Set-Cookie', value: 'ASP.NET_SessionId=••••; path=/; HttpOnly', masked: true },
              ],
              ttfb_ms: 12,
              body: {
                bytes: 40960, complete: true, content_type: 'text/html; charset=utf-8', text: true,
                preview: '<html><head><title>Giriş gerekli</title></head><body>Oturum açın</body></html>',
                preview_truncated: true, download_ms: 3,
              },
            },
            redirect: null,
          },
        ],
        transcript: [
          '* Resolving binbirfikir.example.com',
          '* Trying 172.31.37.113:80...',
          '* Connected (2 ms)',
          '> GET / HTTP/1.1',
          '> Host: binbirfikir.example.com',
          '>',
          '< HTTP/1.1 302 Found',
          '< Location: /login',
          '* Following redirect to http://binbirfikir.example.com/login',
          '> GET /login HTTP/1.1',
          '< HTTP/1.1 401 Unauthorized',
          '< Set-Cookie: ASP.NET_SessionId=••••; path=/; HttpOnly',
        ],
        client_check: { ok: false, http_status: 401, response_ms: 26, http_version: 'HTTP_1_1', error: null },
      },
    ],
    comparison: { available: true, differs: true },
  }
}

export function okSingle() {
  return {
    run_id: 124,
    started_at: '2026-10-02T11:00:00Z',
    duration_ms: 182,
    monitor: { id: 7, name: 'Portal', url: 'https://portal.example.com/health', method: 'GET', expected_status: '200-399',
      timeout_ms: 5000, verify_ssl: true, follow_redirects: true, proxy_mode: 'OFF', advanced: {} },
    source: { pod: 'sitemonitor-7d9f-abcde', node: 'worker-03.example.local', pod_ip: '100.80.1.2' },
    proxy: { configured: false, host: null, port: null, auth: false, no_proxy: null },
    verdict: { status: 'ok', code: 'OK', params: { status: 200, total_ms: 182 }, failed_step: null, path: 'monitor' },
    findings: [{ code: 'OK', severity: 'info', path: 'monitor', params: { status: 200, total_ms: 182 } }],
    paths: [{
      key: 'monitor', route: 'direct', decision: { source: 'monitor', wanted: false, bypassed: false },
      outcome: 'ok', http_status: 200, total_ms: 182, failed_step: null, error: null,
      timeline: { dns_ms: 4, connect_ms: 9, proxy_ms: null, tls_ms: 41, ttfb_ms: 120, download_ms: 8, total_ms: 182 },
      hops: [{
        index: 0, url: 'https://portal.example.com/health', method: 'GET',
        steps: [
          { key: 'dns', status: 'ok', ms: 4 }, { key: 'tcp', status: 'ok', ms: 9 }, { key: 'tls', status: 'ok', ms: 41 },
          { key: 'request', status: 'ok', ms: 0 }, { key: 'response', status: 'ok', ms: 120 }, { key: 'body', status: 'ok', ms: 8 },
        ],
        dns: { host: 'portal.example.com', addresses: ['203.0.113.10', '2001:db8::10'], ms: 4, via_proxy: false, error: null },
        tcp: { remote: '203.0.113.10:443', local: '100.80.1.2:40000', ms: 9, attempts: [{ address: '203.0.113.10:443', ms: 9, error: null }] },
        proxy: null,
        tls: {
          protocol: 'TLSv1.3', cipher: 'TLS_AES_256_GCM_SHA384', alpn: 'http/1.1', sni: 'portal.example.com', handshake_ms: 41,
          trusted: true, trust_error: null, hostname_match: true,
          chain: [
            { subject: 'CN=portal.example.com', issuer: 'CN=Example Issuing CA', not_before: '2026-01-01', not_after: '2027-01-01',
              days_left: 91, san: ['portal.example.com', 'www.portal.example.com'], sig_alg: 'SHA256withRSA', key: 'RSA 2048', sha256: 'AB:CD:EF:01' },
            { subject: 'CN=Example Issuing CA', issuer: 'CN=Example Root CA', not_before: '2024-01-01', not_after: '2030-01-01',
              days_left: 1187, san: [], sig_alg: 'SHA256withRSA', key: 'RSA 4096', sha256: '12:34:56:78' },
          ],
        },
        request: { line: 'GET /health HTTP/1.1', headers: [{ name: 'Host', value: 'portal.example.com', masked: false }], body_bytes: 0, sent_ms: 0 },
        response: {
          status_line: 'HTTP/1.1 200 OK', status: 200, http_version: 'HTTP/1.1',
          headers: [{ name: 'Content-Type', value: 'application/json', masked: false }], ttfb_ms: 120,
          body: { bytes: 15, complete: true, content_type: 'application/json', text: true, preview: '{"status":"UP"}', preview_truncated: false, download_ms: 8 },
        },
        redirect: null,
      }],
      transcript: ['* Resolving portal.example.com', '> GET /health HTTP/1.1', '< HTTP/1.1 200 OK'],
      client_check: { ok: true, http_status: 200, response_ms: 190, http_version: 'HTTP_1_1', error: null },
    }],
    comparison: { available: false, differs: false },
  }
}

/** Liste satırı (detay penceresinin `monitor` prop'u) — sayfa testleri kendi satırını kullanır. */
export const MONITOR_ROW = {
  id: 36, name: 'Binbirfikir', url: 'http://binbirfikir.example.com/', method: 'GET', expected_status: '200',
  timeout_ms: 10000, use_proxy: 'ON', proxy_effective: 'proxy', proxy_source: 'monitor', proxy_bypassed: false,
  can_check: true, can_diagnose: true,
}

export const HISTORY_ROWS = [
  { id: 123, started_at: '2026-10-02T10:49:53Z', executed_by: 'erdi', verdict_code: 'PATH_DIFFERS', verdict_status: 'fail',
    monitor_route: 'proxy', monitor_http_status: null, alternate_route: 'direct', alternate_http_status: 401, duration_ms: 10432 },
  { id: 120, started_at: '2026-10-01T09:00:00Z', executed_by: 'ayse', verdict_code: 'OK', verdict_status: 'ok',
    monitor_route: 'proxy', monitor_http_status: 200, alternate_route: null, alternate_http_status: null, duration_ms: 310 },
]
