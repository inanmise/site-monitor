/**
 * Ping / Port / DNS uçtan uca tanılama test verileri (2026-10-05) — sözleşmenin (POST /api/monitoring/{ping|port|dns}/{id}/
 * diagnose) yanıt biçiminde. Yalnız RFC 5737 / 2001:db8:: adresleri ve `*.example.test` adları (IdentityLeakGuardTest).
 *   • pingFiltered(): ICMP %100 kayıp ama TCP 443 yanıt veriyor → ICMP_FILTERED_HOST_ALIVE (güvenlik duvarı ICMP düşürüyor);
 *     traceroute istendi ama podda araç yok (atlandı: unavailable).
 *   • portPathDiffers(): izlemenin yolu VEKİL, vekil 5432'ye tünel açmıyor (PROXY_PORT_NOT_ALLOWED); öteki yol DOĞRUDAN
 *     başarılı ama 3 IP'den biri zaman aşımında (SOME_IPS_DOWN) → PATH_DIFFERS.
 *   • dnsStale(): sistem çözücüsü eski değeri önbellekte tutuyor (AUTH_RESOLVER_MISMATCH), beklenen değer tutmuyor
 *     (EXPECTED_MISMATCH), yayılım çözücüsü zaman aşımında (RESOLVER_TIMEOUT, warn).
 */
export const LONG_HOST = 'odeme-servisleri-yedek-bolge-2.cok-uzun-bir-alt-alan-adi.example.test'

export const PING_ROW = {
  id: 41, name: 'Ağ geçidi', host: 'gw-01.example.test', ip_version: 'auto', packet_count: 4, timeout_ms: 5000,
  team_id: 5, team_name: 'Takım A', group_name: 'Ağ', tags: 'prod', status: 'down', up: false, active: true,
  rtt_ms: null, packet_loss: 100, checked_at: '2026-10-05T08:00:00', can_check: true,
}
export const PORT_ROW = {
  id: 52, name: 'Veritabanı', host: 'db-01.example.test', port: 5432, protocol: 'TCP', timeout_ms: 5000,
  team_id: 5, team_name: 'Takım A', group_name: 'Veri', tags: 'prod', status: 'closed', open: false, active: true,
  proxy_effective: 'proxy', proxy_source: 'monitor', checked_at: '2026-10-05T08:00:00', can_check: true,
}
export const DNS_ROW = {
  id: 63, name: 'Kurumsal web', domain: 'www.example.test', record_type: 'A', expected_value: '203.0.113.20',
  team_id: 5, team_name: 'Takım A', group_name: 'Web', tags: 'prod', value: '198.51.100.7', ttl: 240, response_ms: 14,
  status: 'warning', active: true, standalone: true, checked_at: '2026-10-05T08:00:00', can_check: true,
}

const SOURCE = { pod: 'sitemonitor-6c9d-abcde', node: 'worker-07.example.test', pod_ip: '198.51.100.40' }

export function pingFiltered() {
  const host = 'gw-01.example.test'
  return {
    run_id: 501, kind: 'ping', type: 'ping', started_at: '2026-10-05T08:10:00Z', duration_ms: 6120, executed_by: 'operator1',
    monitor: { id: 41, name: 'Ağ geçidi', host, ip_version: 'auto', timeout_ms: 5000, packet_count: 4 },
    target: { host, ip_version: 'auto', protocol: 'ICMP' },
    route: { own: 'direct', proxy_configured: true },
    proxy: { configured: true, address: 'proxy.example.test:8080', auth: false },
    source: SOURCE,
    verdict: { code: 'ICMP_FILTERED_HOST_ALIVE', status: 'fail', params: { host, ip: '192.0.2.10', port: 443 }, path: null },
    findings: [
      { code: 'ICMP_FILTERED_HOST_ALIVE', severity: 'fail', path: null, params: { host, ip: '192.0.2.10', port: 443 } },
      { code: 'PROXY_NOT_APPLICABLE', severity: 'info', path: null, params: { proxy: 'proxy.example.test:8080', protocol: 'ICMP' } },
      { code: 'TRACEROUTE_UNAVAILABLE', severity: 'info', path: null, params: { command: 'traceroute' } },
    ],
    steps: [
      { key: 'policy', status: 'ok', ms: 1, detail: { host, result: 'allowed' } },
      { key: 'dns', status: 'ok', ms: 3, detail: { host, ip_version: 'auto', addresses: ['192.0.2.10', '2001:db8::10'], v4: 1, v6: 1, target_ip: '192.0.2.10' } },
      { key: 'icmp', status: 'fail', ms: 4012, detail: { target_ip: '192.0.2.10', packets: 4, command: 'ping -n -c 4 -W 1 192.0.2.10',
        available: true, exit_code: 1, packets_sent: 4, packets_received: 0, loss_pct: 100, rtt_min_ms: null, rtt_avg_ms: null, rtt_max_ms: null },
        error: { class: null, message: '%100 paket kaybı' } },
      { key: 'tcp_alive', status: 'ok', ms: 21, detail: { target_ip: '192.0.2.10', alive: true, probes: [
        { port: 443, ip: '192.0.2.10', result: 'open', ms: 12 },
        { port: 80, ip: '192.0.2.10', result: 'refused', ms: 9, error: 'Connection refused' },
      ] } },
      { key: 'traceroute', status: 'skip', ms: null, detail: { reason: 'unavailable' } },
    ],
    client_check: { ok: false, up: false, na: false, rtt_ms: null, packet_loss: 100, error: '%100 paket kaybı', failure_reason: 'ICMP_NO_REPLY' },
    options: { traceroute: true },
    transcript: [
      '* Ping tanılaması: gw-01.example.test (IP sürümü auto)',
      '── ICMP ──',
      '> ping -n -c 4 -W 1 192.0.2.10',
      '< 4 packets transmitted, 0 received, 100% packet loss',
      '── TCP yoklaması ──',
      '* 192.0.2.10:443 açık (12 ms)',
      '',
    ].join('\n'),
  }
}

export function portPathDiffers() {
  const host = 'db-01.example.test'
  const proxy = 'proxy.example.test:8080'
  const refused = { port: 5432, allowed: [443, 8443], status_line: 'HTTP/1.1 403 Forbidden', proxy }
  const tunnel = { key: 'proxy_tunnel', status: 'fail', ms: 18, detail: { route: 'proxy', proxy, request_line: `CONNECT ${host}:5432 HTTP/1.1`,
    allowed_ports: [443, 8443], port_allowed: false, status_line: 'HTTP/1.1 403 Forbidden' },
  error: { class: 'TunnelRefusedException', message: 'HTTP/1.1 403 Forbidden' } }
  const connect = { key: 'connect', status: 'ok', ms: 5012, detail: { route: 'direct', port: 5432, timeout_ms: 5000, ip: '203.0.113.11', tried: [
    { ip: '203.0.113.12', ms: 5000, result: 'timeout', error: 'connect timed out' },
    { ip: '203.0.113.11', ms: 7, result: 'open' },
  ] } }
  return {
    run_id: 602, kind: 'port', type: 'port', started_at: '2026-10-05T08:12:00Z', duration_ms: 5120, executed_by: 'operator1',
    monitor: { id: 52, name: 'Veritabanı', host, port: 5432, protocol: 'TCP', ip_version: 'auto', timeout_ms: 5000, expect: null,
      send_data: false, proxy_mode: 'on', slow_threshold_ms: null },
    target: { host, port: 5432, protocol: 'TCP', ip_version: 'auto' },
    route: { own: 'proxy', proxy_configured: true, decision: { mode: 'on', source: 'monitor', wanted: true, bypassed: false } },
    proxy: { configured: true, address: proxy, auth: true, connect_ports: [443, 8443] },
    source: SOURCE,
    verdict: { code: 'PATH_DIFFERS', status: 'fail', params: { failing_route: 'proxy', working_route: 'direct', protocol: 'TCP' }, path: null },
    findings: [
      { code: 'PATH_DIFFERS', severity: 'fail', path: null, params: { failing_route: 'proxy', working_route: 'direct', protocol: 'TCP' } },
      { code: 'PROXY_PORT_NOT_ALLOWED', severity: 'fail', path: 'monitor', params: refused },
      { code: 'SOME_IPS_DOWN', severity: 'warn', path: 'alternate', params: { down: 1, total: 2, ips: '203.0.113.12', port: 5432 } },
    ],
    steps: [
      { key: 'policy', status: 'ok', ms: 1, detail: { host, result: 'allowed' } },
      { key: 'dns', status: 'ok', ms: 2, detail: { host, ip_version: 'auto', addresses: ['203.0.113.11', '203.0.113.12'], v4: 2, v6: 0 } },
      tunnel,
    ],
    paths: [
      { key: 'monitor', route: 'proxy', outcome: 'fail', ms: 18, ip: null, steps: [tunnel],
        verdict: { code: 'PROXY_PORT_NOT_ALLOWED', status: 'fail', params: refused, path: 'monitor' },
        findings: [{ code: 'PROXY_PORT_NOT_ALLOWED', severity: 'fail', path: 'monitor', params: refused }] },
      { key: 'alternate', route: 'direct', outcome: 'ok', ms: 5012, ip: '203.0.113.11', steps: [connect],
        verdict: { code: 'PORT_OK', status: 'ok', params: { protocol: 'TCP', ms: 7, route: 'direct', ip: '203.0.113.11' }, path: 'alternate' },
        findings: [{ code: 'SOME_IPS_DOWN', severity: 'warn', path: 'alternate', params: { down: 1, total: 2, ips: '203.0.113.12', port: 5432 } }] },
    ],
    comparison: { available: true, differs: true },
    client_check: { ok: false, open: false, response_ms: null, via: 'proxy', detail: null, proxy_refused: true,
      error: 'vekil tüneli reddetti: HTTP/1.1 403 Forbidden', failure_reason: 'PROXY_REFUSED' },
    options: {},
    transcript: [
      `* Port tanılaması: ${host}:5432 (TCP, IP sürümü auto, zaman aşımı 5000 ms, izlemenin yolu vekil ${proxy})`,
      '── İzlemenin yolu: vekil ──',
      `> CONNECT ${host}:5432 HTTP/1.1`,
      `> Host: ${host}:5432`,
      '> Proxy-Authorization: ••••',
      '< HTTP/1.1 403 Forbidden',
      '── Öteki yol: doğrudan ──',
      '* 203.0.113.12:5432 zaman aşımı (5000 ms)',
      '* 203.0.113.11:5432 açık (7 ms)',
    ].join('\n'),
  }
}

export function dnsStale() {
  const name = 'www.example.test'
  const row = (o) => ({ aa: false, ad: false, tc: false, tcp: false, error: null, error_kind: null, ttl: null, ms: null, rcode: null, answers: [], ...o })
  return {
    run_id: 703, kind: 'dns', type: 'dns', started_at: '2026-10-05T08:14:00Z', duration_ms: 2310, executed_by: 'operator1',
    monitor: { id: 63, name: 'Kurumsal web', domain: name, record_type: 'A', expected_value: ['203.0.113.20'], propagation_check: true, slow_threshold_ms: 500 },
    target: { host: name, record_type: 'A' },
    route: { own: 'direct', proxy_configured: false },
    proxy: { configured: false, address: null, auth: false },
    source: SOURCE,
    verdict: { code: 'EXPECTED_MISMATCH', status: 'fail', params: { unexpected: '198.51.100.7', expected: '203.0.113.20', record_type: 'A' }, path: null },
    findings: [
      { code: 'EXPECTED_MISMATCH', severity: 'fail', path: null, params: { unexpected: '198.51.100.7', expected: '203.0.113.20', record_type: 'A' } },
      { code: 'AUTH_RESOLVER_MISMATCH', severity: 'warn', path: null, params: { resolver_values: '198.51.100.7', auth_values: '203.0.113.20',
        ttl_remaining: 240, server: '192.0.2.53', ns: 'ns1.example.test' } },
      { code: 'RESOLVER_TIMEOUT', severity: 'warn', path: null, params: { servers: '198.51.100.53', ms: 2000, reason: null, error: null } },
      { code: 'DNS_OK', severity: 'info', path: null, params: { record_type: 'A', answers: 1, values: '198.51.100.7', ms: 14, server: '192.0.2.53' } },
    ],
    steps: [
      { key: 'resolvers', status: 'warn', ms: 2004, detail: { name, record_type: 'A', queried: 2, answered: 1, servers: ['192.0.2.53', '198.51.100.53'] } },
      { key: 'dnssec', status: 'skip', ms: null, detail: { reason: 'not_needed' } },
      { key: 'zone', status: 'ok', ms: 31, detail: { zone: 'example.test', ns: ['ns1.example.test', 'ns2.example.test'], soa_serial: 2026100501, via: '192.0.2.53' } },
      { key: 'authoritative', status: 'ok', ms: 26, detail: { zone: 'example.test', queried: 2, authoritative_answers: 2, servers: ['ns1.example.test', 'ns2.example.test'] } },
      { key: 'compare', status: 'fail', ms: 0, detail: { resolvers_agree: true, authoritative_agree: true, auth_vs_resolver: 'differs',
        expected: ['203.0.113.20'], unexpected: ['198.51.100.7'] }, error: { class: null, message: 'unexpected values' } },
    ],
    dns: {
      name, record_type: 'A', zone: 'example.test', ns: ['ns1.example.test', 'ns2.example.test'], timeout_ms: 2000,
      resolvers: [
        row({ server: '192.0.2.53', label: 'system', rcode: 'NOERROR', answers: ['198.51.100.7'], ttl: 240, ms: 14 }),
        row({ server: '198.51.100.53', label: 'propagation', error: 'Zaman aşımı (2000 ms)', error_kind: 'timeout' }),
      ],
      authoritative: [
        row({ server: '203.0.113.53', label: 'authoritative', ns: 'ns1.example.test', rcode: 'NOERROR', answers: ['203.0.113.20'], ttl: 300, ms: 22, aa: true, soa_serial: 2026100501 }),
        row({ server: '203.0.113.54', label: 'authoritative', ns: 'ns2.example.test', rcode: 'NOERROR', answers: ['203.0.113.20'], ttl: 300, ms: 25, aa: true, tc: true, tcp: true }),
      ],
    },
    client_check: { ok: true, values: ['198.51.100.7'], ttl: 240, response_ms: 15, error: null, failure_reason: null },
    options: {},
    transcript: '* DNS tanılaması: www.example.test A (sorgu zaman aşımı 2000 ms)\n── Çözücüler ──\n< 192.0.2.53 NOERROR 198.51.100.7 (14 ms)\n* 198.51.100.53 zaman aşımı\n',
  }
}

/** Kayıtlı çalıştırma (geçmiş ucu): aynı sözleşme + `executed_by`; karşılama önizlemeleri saklanmaz. */
export const stored = (data) => ({ ...data, executed_by: data.executed_by || 'operator1' })

export const PING_HISTORY = [
  { id: 501, started_at: '2026-10-05T08:10:00Z', executed_by: 'operator1', verdict_code: 'ICMP_FILTERED_HOST_ALIVE', verdict_status: 'fail',
    route: 'direct', target: 'gw-01.example.test', findings: 3, traceroute: true, duration_ms: 6120 },
  { id: 488, started_at: '2026-10-04T16:00:00Z', executed_by: 'operator2', verdict_code: 'PING_OK', verdict_status: 'ok',
    route: 'direct', target: 'gw-01.example.test', findings: 1, traceroute: false, duration_ms: 3050 },
]
export const PORT_HISTORY = [
  { id: 602, started_at: '2026-10-05T08:12:00Z', executed_by: 'operator1', verdict_code: 'PATH_DIFFERS', verdict_status: 'fail',
    route: 'proxy', target: 'db-01.example.test:5432', findings: 3, traceroute: false, duration_ms: 5120 },
]
export const DNS_HISTORY = [
  { id: 703, started_at: '2026-10-05T08:14:00Z', executed_by: 'operator1', verdict_code: 'EXPECTED_MISMATCH', verdict_status: 'fail',
    route: 'direct', target: 'www.example.test A', findings: 4, traceroute: false, duration_ms: 2310 },
]

/** Kontrol geçmişi zarfı (CheckHistoryTab) — bir başarısız + bir başarılı satır. */
export function historyEnvelope(items) {
  return { success: true, data: {
    items, counts: { total: items.length, fail: 1, errors: items.filter((r) => r.value === '').length },
    buckets: [], alerts: [], range: { from: '2026-10-04T08:00:00', to: '2026-10-05T08:30:00' }, total: items.length, page: 0, size: 50,
  } }
}

export const PING_CHECKS = [
  { id: 2, monitor_id: 41, up: false, rtt_ms: null, packet_loss: 100, checked_at: '2026-10-05T08:00:00', error: '%100 paket kaybı',
    failure_reason: 'ICMP_NO_REPLY', failure_detail: JSON.stringify({ phase: 'ICMP', target: 'gw-01.example.test', resolved_ips: ['192.0.2.10'], loss: 100 }) },
  { id: 1, monitor_id: 41, up: true, rtt_ms: 4, packet_loss: 0, checked_at: '2026-10-05T07:55:00' },
]
export const PORT_CHECKS = [
  { id: 2, monitor_id: 52, open: false, response_ms: null, checked_at: '2026-10-05T08:00:00',
    error: 'vekil tüneli reddetti: HTTP/1.1 403 Forbidden', failure_reason: 'PROXY_REFUSED',
    failure_detail: JSON.stringify({ phase: 'CONNECT', via: 'proxy', proxy_status: 403, proxy_refused: true, allowed_ports: [443, 8443],
      target: 'db-01.example.test:5432', protocol: 'TCP', timeout_ms: 5000 }) },
  { id: 1, monitor_id: 52, open: true, response_ms: 7, checked_at: '2026-10-05T07:55:00' },
]
export const DNS_CHECKS = [
  { id: 2, monitor_id: 63, record_type: 'A', value: '', changed: false, rotated: false, previous_value: null, ttl: null, response_ms: 2004,
    checked_at: '2026-10-05T08:00:00', error: 'SERVFAIL', failure_reason: 'DNS_SERVFAIL',
    failure_detail: JSON.stringify({ phase: 'DNS', rcode: 'SERVFAIL', record_type: 'A', target: 'www.example.test', response_ms: 2004 }) },
  { id: 1, monitor_id: 63, record_type: 'A', value: '198.51.100.7', changed: false, rotated: false, previous_value: null, ttl: 240,
    response_ms: 14, checked_at: '2026-10-05T07:55:00' },
]
