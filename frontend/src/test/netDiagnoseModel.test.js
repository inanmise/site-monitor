import { describe, it, expect } from 'vitest'
import { TR } from '../i18n/tr.js'
import { EN } from '../i18n/en.js'
import {
  FINDING_CODES, STEP_KEYS, SKIP_REASONS, buildReport, buildVerdict, clampTimeout, clientSummary, detailRows, dnsData,
  dnsRowBadge, dnsRowStatus, failureKind, findingText, findingTitle, localizeParams, normalizePaths, normalizeSteps,
  objectLine, reportFileName, routeLabel, severityTone, targetTag, targetText, transcriptLines, valueText,
} from '../components/diagnose/netDiagnoseModel.js'
import { dnsStale, pingFiltered, portPathDiffers } from './helpers/netDiagnoseFixtures.js'

/** Sözlükten gerçek `t` (useT ile aynı {N} doldurma; eksik anahtarda anahtarın kendisi). */
const tFor = (dict) => (key, ...args) => {
  let s = dict[key] ?? key
  args.forEach((a, i) => { s = s.split(`{${i}}`).join(String(a ?? '')) })
  return s
}
const tr = tFor(TR)
const en = tFor(EN)

/**
 * Sözleşmedeki bulgu kodu → parametre adları (backend NetDiagFindings + servislerin `params(...)` çağrıları). Gövdedeki ve
 * varyant gövdelerdeki ADLI yer tutucular bu kümenin ALT kümesi olmalı (olmayan parametre metinde "—" kalırdı).
 */
const CONTRACT_PARAMS = {
  POLICY_BLOCKED: ['host', 'reason'], DNS_FAILED: ['host', 'error'], NO_ADDRESS_FOR_IP_VERSION: ['host', 'ip_version', 'available'],
  PROXY_NOT_APPLICABLE: ['proxy', 'protocol'], CLIENT_MISMATCH: ['diag', 'client', 'client_error'], RUN_TIME_LIMIT: ['limit_s'],
  INCONCLUSIVE: [],
  PING_OK: ['rtt_avg_ms', 'loss_pct', 'ip'], ICMP_UNAVAILABLE_HERE: ['host', 'reason'], ICMP_FILTERED_HOST_ALIVE: ['host', 'ip', 'port'],
  HOST_UNREACHABLE: ['host', 'ip'], ALL_PACKETS_LOST: ['host', 'sent'], PARTIAL_LOSS: ['loss_pct', 'sent', 'received'],
  HIGH_RTT: ['rtt_avg_ms', 'threshold_ms'], TRACEROUTE_UNAVAILABLE: ['command'],
  PORT_OK: ['protocol', 'ms', 'route', 'ip'], CONNECT_REFUSED: ['host', 'port', 'ip', 'ms', 'error'],
  CONNECT_TIMEOUT_FILTERED: ['host', 'port', 'ip', 'ms', 'error'], NETWORK_UNREACHABLE: ['host', 'port', 'ip', 'error'],
  PROXY_UNREACHABLE: ['proxy', 'error', 'reason'], PROXY_PORT_NOT_ALLOWED: ['port', 'allowed', 'status_line', 'proxy'],
  PROXY_REFUSED: ['port', 'allowed', 'status_line', 'proxy'], TLS_HANDSHAKE_FAILED: ['host', 'port', 'error'],
  TLS_UNTRUSTED: ['error', 'host'], TLS_HOSTNAME_MISMATCH: ['host', 'subject', 'san'], CERT_EXPIRED: ['not_after', 'days_left'],
  CERT_EXPIRES_SOON: ['not_after', 'days_left'], HTTP_STATUS_MISMATCH: ['status', 'expected'], HTTP_BAD_RESPONSE: ['error', 'reason', 'ms'],
  BANNER_MISMATCH: ['expected', 'got', 'bytes'], BANNER_EMPTY: ['expected', 'ms', 'error'], UDP_NO_REPLY: ['port', 'ip', 'ms'],
  UDP_PORT_UNREACHABLE: ['port', 'ip'], PATH_DIFFERS: ['failing_route', 'working_route', 'protocol'],
  SOME_IPS_DOWN: ['down', 'total', 'ips', 'port'], SLOW_RESPONSE: ['ms', 'threshold_ms'],
  DNS_OK: ['record_type', 'answers', 'values', 'ms', 'server'], NXDOMAIN_AUTHORITATIVE: ['name', 'zone', 'reason'],
  NXDOMAIN_RESOLVER_ONLY: ['name', 'server', 'negative_ttl', 'auth_values', 'ns'], SERVFAIL_DNSSEC: ['name', 'server', 'cd_rcode'],
  SERVFAIL: ['name', 'server', 'cd_rcode', 'rcode', 'reason'], RESOLVER_REFUSED: ['server', 'name'],
  RESOLVER_TIMEOUT: ['servers', 'ms', 'reason', 'error'], RESOLVERS_DISAGREE: ['sets', 'count'],
  AUTH_RESOLVER_MISMATCH: ['resolver_values', 'auth_values', 'ttl_remaining', 'server', 'ns'], LAME_DELEGATION: ['ns', 'zone'],
  AUTH_UNREACHABLE: ['ns', 'reason'], ZONE_NOT_FOUND: ['name', 'zone'], NO_RECORD_OF_TYPE: ['name', 'record_type', 'cname', 'other_types', 'reason'],
  EXPECTED_MISMATCH: ['unexpected', 'expected', 'record_type'], TRUNCATED_UDP: ['servers'], SLOW_RESOLVER: ['server', 'ms', 'threshold_ms'],
}
/** Backend'in `params.reason` ile seçtirdiği gövde varyantları (her biri iki sözlükte de var olmalı). */
const REASON_VARIANTS = {
  ICMP_UNAVAILABLE_HERE: ['alive', 'no_answer', 'unknown'], HTTP_BAD_RESPONSE: ['invalid', 'timeout', 'error'],
  PROXY_UNREACHABLE: ['timeout', 'refused', 'unreachable'], NXDOMAIN_AUTHORITATIVE: ['unconfirmed'], SERVFAIL: ['other'],
  RESOLVER_TIMEOUT: ['error', 'none'], NO_RECORD_OF_TYPE: ['cname', 'other'], AUTH_UNREACHABLE: ['policy'],
}
const named = (s) => [...new Set((String(s).match(/\{([a-z][a-z0-9_]*)\}/gi) || []).map((m) => m.slice(1, -1)))].sort()

describe('netDiagnoseModel — bulgu kataloğu ve i18n', () => {
  it('backend kataloğunun 52 kodu, sırası korunarak, modelde; her kodun TR + EN başlık/gövde metni var', () => {
    expect(FINDING_CODES).toHaveLength(52)
    expect(new Set(FINDING_CODES).size).toBe(52)
    expect([...FINDING_CODES].sort()).toEqual(Object.keys(CONTRACT_PARAMS).sort())
    const missing = []
    for (const code of FINDING_CODES) {
      for (const part of ['title', 'body']) {
        const k = `ndx.finding.${code}.${part}`
        if (!TR[k]?.trim()) missing.push(`TR ${k}`)
        if (!EN[k]?.trim()) missing.push(`EN ${k}`)
      }
    }
    expect(missing).toEqual([])
  })

  it('16 adım anahtarı ve 8 atlama gerekçesi iki sözlükte; bilinmeyen kod için genel metin var', () => {
    expect(STEP_KEYS).toHaveLength(16)
    const missing = []
    for (const s of STEP_KEYS) for (const [n, d] of [['TR', TR], ['EN', EN]]) if (!d[`ndx.step.${s}`]) missing.push(`${n} step ${s}`)
    for (const s of SKIP_REASONS) for (const [n, d] of [['TR', TR], ['EN', EN]]) if (!d[`ndx.skip.${s}`]) missing.push(`${n} skip ${s}`)
    for (const p of ['title', 'body']) for (const [n, d] of [['TR', TR], ['EN', EN]]) if (!d[`ndx.finding.UNKNOWN.${p}`]) missing.push(`${n} UNKNOWN.${p}`)
    expect(missing).toEqual([])
  })

  it('gövdelerin (ve reason varyantlarının) ADLI yer tutucuları sözleşme parametrelerinin alt kümesi, TR = EN; başlıklar yer tutucusuz', () => {
    const bad = []
    for (const [code, params] of Object.entries(CONTRACT_PARAMS)) {
      const keys = [`ndx.finding.${code}.body`, ...(REASON_VARIANTS[code] || []).map((r) => `ndx.finding.${code}.body.${r}`)]
      for (const k of keys) {
        if (!TR[k] || !EN[k]) { bad.push(`eksik ${k}`); continue }
        if (named(TR[k]).join(',') !== named(EN[k]).join(',')) bad.push(`${k}: TR ${named(TR[k])} ≠ EN ${named(EN[k])}`)
        for (const p of named(TR[k])) if (!params.includes(p)) bad.push(`${k}: {${p}} sözleşmede yok`)
      }
      for (const [lang, dict] of [['TR', TR], ['EN', EN]]) {
        if (named(dict[`ndx.finding.${code}.title`]).length) bad.push(`${lang} ${code}.title yer tutucu taşıyor`)
      }
    }
    expect(bad).toEqual([])
  })

  it('tüm ndx.* anahtarları iki sözlükte; adlı yer tutucular aynı; "*" yok; boş değer yok', () => {
    const trK = Object.keys(TR).filter((k) => k.startsWith('ndx.'))
    const enK = Object.keys(EN).filter((k) => k.startsWith('ndx.'))
    expect(trK.sort()).toEqual(enK.sort())
    const bad = trK.filter((k) => named(TR[k]).join(',') !== named(EN[k]).join(',') || /\*/.test(TR[k] + EN[k]) || !TR[k].trim() || !EN[k].trim())
    expect(bad).toEqual([])
  })
})

describe('netDiagnoseModel — bulgu metinleri', () => {
  it('findingText: adlı parametreler dolar, yol değerleri yerelleşir (PATH_DIFFERS)', () => {
    const f = { code: 'PATH_DIFFERS', params: { failing_route: 'proxy', working_route: 'direct', protocol: 'TCP' } }
    const trTxt = findingText(f, tr)
    expect(trTxt.known).toBe(true)
    expect(trTxt.title).toBe(TR['ndx.finding.PATH_DIFFERS.title'])
    expect(trTxt.body).toContain('TCP kontrolü Vekil yolunda başarısız, Doğrudan yolunda başarılı')
    expect(trTxt.body).not.toMatch(/\{[a-z_]+\}/)
    expect(findingText(f, en).body).toContain('fails over the Proxy route and succeeds over the Direct route')
  })

  it('reason varyantı: varsa varyant gövdesi, yoksa genel gövde; serbest metinli reason genel gövdeye düşer', () => {
    const alive = findingText({ code: 'ICMP_UNAVAILABLE_HERE', params: { host: 'gw.example.test', reason: 'alive' } }, en)
    expect(alive.body).toBe(EN['ndx.finding.ICMP_UNAVAILABLE_HERE.body.alive'].replace('{host}', 'gw.example.test'))
    const none = findingText({ code: 'RESOLVER_TIMEOUT', params: { servers: '—', reason: 'none' } }, tr)
    expect(none.body).toBe(TR['ndx.finding.RESOLVER_TIMEOUT.body.none'])
    const plain = findingText({ code: 'RESOLVER_TIMEOUT', params: { servers: '192.0.2.53', ms: 2000, reason: null } }, tr)
    expect(plain.body).toContain('192.0.2.53')
    expect(plain.body).toContain('2000 ms')
    // POLICY_BLOCKED'ın reason'ı serbest metindir (SSRF iletisi): varyant aranmaz, genel gövdede {reason} olarak yazılır
    const pol = findingText({ code: 'POLICY_BLOCKED', params: { host: 'intra.example.test', reason: 'izin verilmeyen hedef' } }, tr)
    expect(pol.body).toContain('izin verilmeyen hedef')
    expect(pol.body).toContain('intra.example.test')
  })

  it('CLIENT_MISMATCH: diag/client değerleri sözlükten (up/down, open/closed, ok/fail); dizi parametreler virgülle', () => {
    const b = findingText({ code: 'CLIENT_MISMATCH', params: { diag: 'open', client: 'closed', client_error: 'reset' } }, en).body
    expect(b).toContain('"open"')
    expect(b).toContain('"closed"')
    const p = localizeParams({ allowed: [443, 8443], route: 'proxy', x: null }, en)
    expect(p).toEqual({ allowed: '443, 8443', route: EN['httpdx.route.proxy'], x: '—' })
  })

  it('bilinmeyen kod genel metinle düşer (ham anahtar yok); findingTitle parametresiz başlık', () => {
    const u = findingText({ code: 'BRAND_NEW', params: {} }, tr)
    expect(u.known).toBe(false)
    expect(u.title).not.toContain('ndx.')
    expect(u.body).toContain('BRAND_NEW')
    expect(findingTitle('PING_OK', en)).toBe(EN['ndx.finding.PING_OK.title'])
  })

  it('severityTone: fail → danger, warn → warning, OK kodları success, diğer bilgi info', () => {
    expect(severityTone('fail', 'X')).toBe('danger')
    expect(severityTone('warn', 'X')).toBe('warning')
    expect(severityTone('info', 'DNS_OK')).toBe('success')
    expect(severityTone('info', 'TRUNCATED_UDP')).toBe('info')
  })
})

describe('netDiagnoseModel — hüküm, adımlar, ayrıntı', () => {
  it('buildVerdict: sunucunun hükmü; aynı kod listede tekrar etmez, kalanlar SUNUCU sırasında', () => {
    const v = buildVerdict(pingFiltered(), en)
    expect(v.status).toBe('fail')
    expect(v.tone).toBe('danger')
    expect(v.code).toBe('ICMP_FILTERED_HOST_ALIVE')
    expect(v.body).toContain('192.0.2.10')
    expect(v.body).toContain('TCP port 443')
    expect(v.others.map((f) => f.code)).toEqual(['PROXY_NOT_APPLICABLE', 'TRACEROUTE_UNAVAILABLE'])
    const d = buildVerdict(dnsStale(), tr)
    expect(d.others.map((f) => [f.code, f.severity])).toEqual([['AUTH_RESOLVER_MISMATCH', 'warn'], ['RESOLVER_TIMEOUT', 'warn'], ['DNS_OK', 'info']])
    // hüküm yoksa ilk bulgu
    expect(buildVerdict({ findings: [{ code: 'DNS_OK', severity: 'info', params: {} }] }, en).status).toBe('ok')
  })

  it('normalizeSteps: sunucu sırası korunur (DNS: dnssec zone\'dan önce), durum normalleşir, atlama gerekçesi ayrılır', () => {
    const s = normalizeSteps(dnsStale().steps)
    expect(s.map((x) => x.key)).toEqual(['resolvers', 'dnssec', 'zone', 'authoritative', 'compare'])
    expect(s[1]).toMatchObject({ state: 'skip', reason: 'not_needed', ms: null })
    expect(s[4]).toMatchObject({ state: 'fail' })
    expect(s[4].error.message).toBe('unexpected values')
  })

  it('detailRows: ms biçimi, evet/hayır, sözlük değerleri, nesne dizisi satırları, null atlanır, bilinmeyen anahtar ham adıyla', () => {
    const icmp = normalizeSteps(pingFiltered().steps).find((x) => x.key === 'icmp')
    const rows = detailRows(icmp.detail, en)
    const by = Object.fromEntries(rows.map((r) => [r.key, r]))
    expect(by.loss_pct.value).toBe('100%')
    expect(by.available.value).toBe('Yes')
    expect(by.rtt_avg_ms).toBeUndefined()   // null atlanır
    expect(by.command.mono).toBe(true)
    const tcp = detailRows(normalizeSteps(pingFiltered().steps).find((x) => x.key === 'tcp_alive').detail, en)
    const probes = tcp.find((r) => r.key === 'probes')
    expect(probes.kind).toBe('list')
    expect(probes.items[0]).toBe('Port: 443 · IP: 192.0.2.10 · Result: open · 12 ms')
    expect(probes.items[1]).toContain('Result: refused')
    const tunnel = detailRows(portPathDiffers().steps[2].detail, en)
    expect(tunnel.find((r) => r.key === 'route').value).toBe(EN['httpdx.route.proxy'])
    expect(tunnel.find((r) => r.key === 'port_allowed')).toMatchObject({ value: 'No', tone: 'bad' })
    expect(tunnel.find((r) => r.key === 'allowed_ports').value).toBe('443, 8443')
    const cmp = detailRows({ auth_vs_resolver: 'n/a', brand_new_key: 'x', output: 'a\nb', reason: 'not_needed' }, tr, { skip: true })
    expect(cmp.find((r) => r.key === 'auth_vs_resolver').value).toBe(TR['ndx.val.na'])
    expect(cmp.find((r) => r.key === 'brand_new_key').label).toBe('brand_new_key')
    expect(cmp.find((r) => r.key === 'output').kind).toBe('code')
    expect(cmp.find((r) => r.key === 'reason')).toBeUndefined()   // atlanan adımın gerekçesi başlıkta
    expect(objectLine({ ip: '192.0.2.1', ms: 5, error: null }, en)).toBe('IP: 192.0.2.1 · 5 ms')
  })

  it('valueText: sözlükte yoksa ham değer; "n/a" → na', () => {
    expect(valueText('refused', tr)).toBe(TR['ndx.val.refused'])
    expect(valueText('n/a', en)).toBe(EN['ndx.val.na'])
    expect(valueText('weird value', en)).toBe('weird value')
    expect(valueText(null, en)).toBe('—')
  })
})

describe('netDiagnoseModel — Port yolları, DNS satırları, istemci, hedef', () => {
  it('normalizePaths + routeLabel: vekil yolu adresiyle; yol hükmü bulgu olarak', () => {
    const d = portPathDiffers()
    const p = normalizePaths(d, en)
    expect(p.map((x) => [x.key, x.route, x.outcome])).toEqual([['monitor', 'proxy', 'fail'], ['alternate', 'direct', 'ok']])
    expect(p[0].verdict.code).toBe('PROXY_PORT_NOT_ALLOWED')
    expect(p[1].verdict.tone).toBe('success')
    expect(routeLabel('proxy', d, en)).toBe(`${EN['httpdx.route.proxy']} · proxy.example.test:8080`)
    expect(routeLabel('direct', d, en)).toBe(EN['httpdx.route.direct'])
    expect(normalizePaths(pingFiltered(), en)).toEqual([])
  })

  it('dnsData + satır durumu: NOERROR+cevap ok, zaman aşımı fail, yetkili aa=false warn; rozet rcode ya da hata türü', () => {
    const dd = dnsData(dnsStale())
    expect(dd.zone).toBe('example.test')
    expect(dd.resolvers.map((r) => r.status)).toEqual(['ok', 'fail'])
    expect(dd.authoritative.map((r) => r.status)).toEqual(['ok', 'ok'])
    expect(dnsRowBadge(dd.resolvers[1], en)).toBe(EN['ndx.val.timeout'])
    expect(dnsRowBadge(dd.resolvers[0], en)).toBe('NOERROR')
    expect(dnsRowStatus({ rcode: 'NOERROR', answers: ['x'], aa: false }, 'authoritative')).toBe('warn')
    expect(dnsRowStatus({ rcode: 'NXDOMAIN', answers: [] })).toBe('fail')
    expect(dnsRowStatus({ rcode: 'NOERROR', answers: [] })).toBe('warn')
  })

  it('clientSummary: türün alanları, atlanan istemci, ICMP yok (na)', () => {
    const c = clientSummary('port', portPathDiffers().client_check, en)
    expect(c.state).toBe('fail')
    expect(c.rows.map((r) => r.key)).toEqual(['via', 'proxy_refused', 'failure_reason', 'error'])
    expect(c.rows.find((r) => r.key === 'via').value).toBe(EN['httpdx.route.proxy'])
    expect(clientSummary('ping', { ok: false, skipped: true, reason: 'policy' }, en).state).toBe('skipped')
    expect(clientSummary('ping', { ok: false, na: true }, en).state).toBe('na')
    expect(clientSummary('dns', dnsStale().client_check, en).rows.find((r) => r.key === 'ttl').value).toBe('240 s')
    expect(clientSummary('dns', null, en)).toBeNull()
  })

  it('targetText / targetTag / clampTimeout / reportFileName / failureKind', () => {
    expect(targetText('port', { host: 'db.example.test', port: 5432 })).toBe('db.example.test:5432')
    expect(targetText('dns', { domain: 'www.example.test' })).toBe('www.example.test')
    expect(targetTag('dns', { record_type: 'aaaa' })).toBe('AAAA')
    expect(targetTag('ping', {})).toBe('ICMP')
    expect(clampTimeout(null)).toBe(5000)
    expect(clampTimeout(90000)).toBe(30000)
    expect(clampTimeout(200)).toBe(1000)
    expect(reportFileName(dnsStale(), new Date(), 'dns')).toBe('dns-diagnose-63-run703.json')
    expect(failureKind({ success: false, status: 429 }, [], 'port')).toBe('rateLimited')
    expect(failureKind(null, [{ path: '/api/monitoring/dns/63/diagnose', status: 429 }], 'dns')).toBe('rateLimited')
    expect(failureKind(null, [{ path: '/api/monitoring/http/63/diagnose', status: 429 }], 'dns')).toBe('network')
    expect(failureKind({ success: false, status: 403 }, [], 'ping')).toBe('forbidden')
  })

  it('transcriptLines: satır türleri (gönderilen / alınan / bilgi / bölüm), sondaki boş satır atılır', () => {
    const l = transcriptLines(pingFiltered().transcript)
    expect(l.map((x) => x.kind)).toEqual(['info', 'section', 'sent', 'recv', 'section', 'info'])
  })
})

describe('netDiagnoseModel — rapor', () => {
  it('Markdown: başlık, künye, hüküm, bulgular, yollar, adımlar, DNS satırları, istemci, döküm; yer tutucu kalmaz', () => {
    const md = buildReport({ data: portPathDiffers(), t: en, format: 'markdown', type: 'port' })
    expect(md).toMatch(/^# SiteMonitor — Port diagnosis report/)
    expect(md).toContain('db-01.example.test:5432 (TCP)')
    expect(md).toContain('#602')
    expect(md).toContain(`## Verdict: ${EN['httpdx.status.fail']} — ${EN['ndx.finding.PATH_DIFFERS.title']}`)
    expect(md).toContain(EN['ndx.finding.PROXY_PORT_NOT_ALLOWED.title'])
    expect(md).toContain('Proxy · proxy.example.test:8080')
    expect(md).toContain('```text')
    expect(md).toContain('Proxy-Authorization: ••••')
    expect(md).not.toMatch(/\{[a-z_]+\}/)
    const dns = buildReport({ data: dnsStale(), t: tr, format: 'markdown', type: 'dns' })
    expect(dns).toContain(TR['ndx.dns.resolvers'])
    expect(dns).toContain('192.0.2.53 [sistem çözücüsü]: NOERROR · 198.51.100.7')
    expect(dns).toContain('ns1.example.test')
  })

  it('düz metin: Markdown işaretlemesi yok, aynı içerik', () => {
    const txt = buildReport({ data: pingFiltered(), t: en, format: 'text', type: 'ping' })
    expect(txt).toMatch(/^SiteMonitor — Ping diagnosis report\n=+/)
    expect(txt).not.toContain('**')
    expect(txt).not.toContain('```')
    // düz metinde h2 BÜYÜK harfle yazılır (rapor yazıcısı) — başlık büyük/küçük harften bağımsız aranır
    expect(txt.toLowerCase()).toContain(EN['ndx.finding.ICMP_FILTERED_HOST_ALIVE.title'].toLowerCase())
    expect(txt).toContain('- Traceroute: Yes')
  })
})
