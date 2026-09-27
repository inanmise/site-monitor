import { describe, it, expect } from 'vitest'
import {
  buildSteps, buildVerdict, buildLanes, buildReport, diffRuns, wasRateLimited, comboPassed, comboFailedAt,
  normaliseDomainInput, isValidDomain, readRecent, pushRecent, clearRecent, curlStatusLine, shortCn, daysTone,
} from '../components/diagnostics/diagModel.js'

/**
 * Bağlantı tanılama MODELİ — saf fonksiyonlar. Pencere bileşeni yalnız çizer; "hangi adımda takıldı, hüküm ne,
 * zamanlama, rapor, karşılaştırma, 429, alan adı doğrulama, son sorgular" burada, i18n'siz sınanır.
 * Sabitler backend şeklinde (snake_case tel biçimi; ConnectionDiagnosticsService.diagnose).
 */
const t = (k, ...args) => [k, ...args].join('|')   // anahtar + argümanlar görünür kalsın

function combo(via, mode, ok, extra = {}) {
  return {
    id: `${via}+${mode}`, via, tls_mode: mode, status: ok ? 'ok' : 'error',
    step_reached: ok ? 'cert-ok' : 'unknown', elapsed_ms: 200,
    source_ip: '10.0.0.5', source_port: 40000, peer_ip: via === 'proxy' ? '10.10.0.8' : '203.0.113.10', peer_port: via === 'proxy' ? 8080 : 443,
    ...(ok ? { subject: 'CN=www.example.com', days_remaining: 62, tls_version: mode === 'browser' ? 'TLSv1.2' : 'TLSv1.3', cipher_suite: 'TLS_AES_256_GCM_SHA384', alpn: mode === 'browser' ? 'h2' : null, error: null }
      : { error_class: 'UNKNOWN', error: 'Probe failed' }),
    ...extra,
  }
}

const OK = {
  domain: 'www.example.com', port: 443, proxy_configured: true, proxy_address: 'proxy.example.com:8080',
  source: { hostname: 'pod-a', ips: ['10.0.0.5'], node_name: 'worker-1' },
  dns: { ips: ['203.0.113.10', '2001:db8::10'], error: null, elapsed_ms: 12 },
  combos: [combo('direct', 'browser', true, { elapsed_ms: 210 }), combo('direct', 'default', true, { elapsed_ms: 190 }), combo('proxy', 'browser', true, { elapsed_ms: 340 }), combo('proxy', 'default', true, { elapsed_ms: 355 })],
  elapsed_ms: 400,
}

const TLS_FAIL_DIRECT = {
  ...OK, domain: 'api.example.com',
  combos: [
    combo('direct', 'browser', false, { step_reached: 'tls-handshake', elapsed_ms: 5004, error_class: 'TIMEOUT', error: 'Read timed out during TLS handshake' }),
    combo('direct', 'default', false, { step_reached: 'tls-handshake', elapsed_ms: 5002, error_class: 'TIMEOUT', error: 'Read timed out' }),
    combo('proxy', 'browser', true, { days_remaining: 12 }), combo('proxy', 'default', true, { days_remaining: 12 }),
  ],
}

const DNS_FAIL = {
  domain: 'intranet.example.com', port: 443, proxy_configured: false, proxy_address: null, source: { hostname: 'pod-a', ips: ['10.0.0.5'] },
  dns: { ips: [], error: 'intranet.example.com: Name or service not known', elapsed_ms: 31 },
  combos: [
    combo('direct', 'browser', false, { step_reached: 'dns', elapsed_ms: 33, error_class: 'DNS', error: 'Name or service not known' }),
    combo('direct', 'default', false, { step_reached: 'dns', elapsed_ms: 30, error_class: 'DNS', error: 'Name or service not known' }),
  ],
  elapsed_ms: 70,
}

describe('diagModel — comboPassed / comboFailedAt', () => {
  it('başarılı sonda her aşamayı geçer; düşen sonda düştüğü aşamadan öncekileri geçer, sonrakileri geçmez', () => {
    const ok = combo('direct', 'browser', true)
    const tls = combo('direct', 'browser', false, { step_reached: 'tls-handshake' })
    expect(comboPassed(ok, 'tls-handshake')).toBe(true)
    expect(comboPassed(tls, 'tcp-connect')).toBe(true)
    expect(comboPassed(tls, 'tls-handshake')).toBe(false)
    expect(comboFailedAt(tls, 'tls-handshake')).toBe(true)
    expect(comboFailedAt(tls, 'tcp-connect')).toBe(false)
    // aşaması bilinmeyen sentetik zaman aşımı: ne geçti ne düştü (null)
    expect(comboPassed(combo('direct', 'browser', false), 'tcp-connect')).toBeNull()
  })
})

describe('diagModel — buildSteps', () => {
  it('sağlıklı koşu: DNS/TCP/Proxy/TLS/Sertifika ok, HTTP çalıştırılmadı (temel koşu HTTP yapmaz)', () => {
    const steps = buildSteps(OK)
    expect(steps.map((s) => s.key)).toEqual(['dns', 'tcp', 'proxy', 'tls', 'cert', 'http'])
    expect(steps.map((s) => s.status)).toEqual(['ok', 'ok', 'ok', 'ok', 'ok', 'pending'])
    expect(steps[0].facts).toMatchObject({ ips: ['203.0.113.10', '2001:db8::10'], ipv4: 1, ipv6: 1, error: null })
    expect(steps[0].duration).toEqual({ ms: 12, kind: 'dns' })
    expect(steps[1].facts.peers).toEqual(['203.0.113.10:443'])
    expect(steps[2].facts).toMatchObject({ configured: true, address: 'proxy.example.com:8080' })
    expect(steps[3].facts.negotiated).toHaveLength(4)
    expect(steps[3].facts.negotiated[0]).toMatchObject({ mode: 'direct · browser', version: 'TLSv1.2', alpn: 'h2' })
    expect(steps[4].facts).toMatchObject({ cn: 'www.example.com', days: 62, issuer: null })
    // süre: başarılı adımda en hızlı tam yol
    expect(steps[1].duration).toEqual({ ms: 190, kind: 'path' })
  })

  it('doğrudan yol TLS\'te düşüyor, vekil OK → TLS adımı "warning" (karışık), düşme süresi en uzun bekleme', () => {
    const steps = buildSteps(TLS_FAIL_DIRECT)
    const tls = steps.find((s) => s.key === 'tls')
    expect(tls.status).toBe('warning')
    expect(tls.duration).toEqual({ ms: 5004, kind: 'fail' })
    expect(tls.facts.failures).toHaveLength(2)
    expect(tls.facts.failures[0]).toMatchObject({ mode: 'direct · browser', errorClass: 'TIMEOUT' })
    expect(steps.find((s) => s.key === 'tcp').status).toBe('ok')          // doğrudan sondalar TCP'yi geçti
    expect(steps.find((s) => s.key === 'cert').facts.days).toBe(12)
  })

  it('DNS düşüyor, vekil yok → DNS failed; TCP/Proxy/TLS/Sertifika hiç denenmedi = skipped', () => {
    const steps = buildSteps(DNS_FAIL)
    expect(steps.map((s) => s.status)).toEqual(['failed', 'skipped', 'skipped', 'skipped', 'skipped', 'pending'])
    expect(steps[0].facts.error).toContain('Name or service not known')
    expect(steps[2].facts.configured).toBe(false)
  })

  it('HTTP adımı HSTS sonucuyla dolar (durum satırı + yönlendirme); CONNECT_FAILED → failed', () => {
    const okHttp = buildSteps(OK, { hsts: { status: 'ok', verdict: 'ENFORCED', status_line: 'HTTP/1.1 200 OK', http_redirects_to_https: true, raw_value: 'max-age=1', elapsed_ms: 640 } }).at(-1)
    expect(okHttp).toMatchObject({ status: 'ok', facts: { source: 'hsts', statusLine: 'HTTP/1.1 200 OK', redirect: true } })
    const bad = buildSteps(OK, { hsts: { status: 'error', verdict: 'CONNECT_FAILED', error: 'refused' } }).at(-1)
    expect(bad.status).toBe('failed')
    expect(bad.facts.error).toBe('refused')
  })

  it('HSTS yoksa ağ analizinin curl izi HTTP adımını doldurur; durum satırı çıktıdan ayıklanır', () => {
    const net = { checks: [{ key: 'curl', status: 'ok', summary: 'x', command: 'curl -v', output: '* Connected\n< HTTP/2 200\n< server: nginx' }] }
    const http = buildSteps(OK, { net }).at(-1)
    expect(http).toMatchObject({ status: 'ok', facts: { source: 'curl', statusLine: 'HTTP/2 200' } })
    expect(curlStatusLine('')).toBeNull()
  })

  it('openssl sertifika bilgisi Sertifika adımına işlenir (veren, son geçerlilik, anahtar/imza)', () => {
    const ossl = { certificate: { issuer: 'CN=Example CA', not_after: 'Nov 27 2026', key_bits: 2048, signature_algorithm: 'sha256WithRSA' }, flags: ['WEAK_KEY'] }
    const cert = buildSteps(OK, { ossl }).find((s) => s.key === 'cert')
    expect(cert.facts).toMatchObject({ issuer: 'CN=Example CA', notAfter: 'Nov 27 2026', keySig: '2048 bit · sha256WithRSA', flags: ['WEAK_KEY'] })
  })

  it('boş/eksik veri çökmez', () => {
    expect(buildSteps(null).map((s) => s.status)).toEqual(['skipped', 'skipped', 'skipped', 'skipped', 'skipped', 'pending'])
    expect(buildSteps({ combos: 'yanlış tip' })).toHaveLength(6)
  })
})

describe('diagModel — buildVerdict', () => {
  it('hepsi OK → success, "reachable 4/4", vekilli neden', () => {
    const v = buildVerdict(OK, buildSteps(OK), t)
    expect(v).toMatchObject({ tone: 'success', stage: null, ok: 4, total: 4, title: 'diag.verdict.reachable|4|4', cause: 'diag.verdict.reachableCause', next: null, notes: [] })
  })

  it('vekil yapılandırılmamış ve OK → doğrudan-yol nedeni', () => {
    const d = { ...OK, proxy_configured: false, combos: OK.combos.slice(0, 2) }
    expect(buildVerdict(d, buildSteps(d), t).cause).toBe('diag.verdict.reachableDirectCause')
  })

  it('doğrudan TLS\'te düşüyor, vekil OK → warning "yalnız vekil", takılan adım TLS, sertifika 12 gün notu', () => {
    const v = buildVerdict(TLS_FAIL_DIRECT, buildSteps(TLS_FAIL_DIRECT), t)
    expect(v.tone).toBe('warning')
    expect(v.stage).toBe('tls')
    expect(v.title).toBe('diag.verdict.proxyOnly')
    expect(v.cause).toBe('diag.verdict.proxyOnlyCause|inv.diagStepTls')
    expect(v.notes).toEqual([{ tone: 'warning', text: 'diag.verdict.certExpiring|12' }])
  })

  it('DNS düşüyor, hiç yol yok → danger "DNS\'te takıldı: <hata>"', () => {
    const v = buildVerdict(DNS_FAIL, buildSteps(DNS_FAIL), t)
    expect(v).toMatchObject({ tone: 'danger', stage: 'dns', ok: 0, total: 2 })
    expect(v.title).toBe('diag.verdict.dns|intranet.example.com: Name or service not known')
  })

  it('doğrudan DNS\'te düşüyor ama vekil OK → split-DNS uyarısı', () => {
    const d = { ...OK, dns: { ips: [], error: 'NXDOMAIN', elapsed_ms: 5 }, combos: [
      combo('direct', 'browser', false, { step_reached: 'dns', error_class: 'DNS' }), combo('direct', 'default', false, { step_reached: 'dns', error_class: 'DNS' }),
      combo('proxy', 'browser', true), combo('proxy', 'default', true),
    ] }
    expect(buildVerdict(d, buildSteps(d), t)).toMatchObject({ tone: 'warning', stage: 'dns', title: 'diag.verdict.splitDns' })
  })

  it('hepsi TCP\'de düşüyor → danger TCP; sonraki adımda kaynak IP ve hedef yer alır', () => {
    const d = { ...OK, combos: OK.combos.map((c) => ({ ...c, status: 'error', step_reached: c.via === 'proxy' ? 'proxy-connect' : 'tcp-connect', error_class: 'CONNECT_TIMEOUT', error: 'timeout' })) }
    // 2 tcp-connect + 2 proxy-connect: eşitlikte en erken aşama (tcp-connect) kazanır
    const v = buildVerdict(d, buildSteps(d), t)
    expect(v).toMatchObject({ tone: 'danger', stage: 'tcp', title: 'diag.verdict.tcp' })
    expect(v.next).toBe('diag.verdict.tcpNext|10.0.0.5|www.example.com:443')
  })

  it('vekil CONNECT reddediliyor, doğrudan OK → warning "doğrudan erişilebilir, vekil düşüyor"', () => {
    const d = { ...OK, combos: [combo('direct', 'browser', true), combo('direct', 'default', true),
      combo('proxy', 'browser', false, { step_reached: 'proxy-connect', error_class: 'PROXY' }), combo('proxy', 'default', false, { step_reached: 'proxy-connect', error_class: 'PROXY' })] }
    const v = buildVerdict(d, buildSteps(d), t)
    expect(v).toMatchObject({ tone: 'warning', stage: 'proxy', title: 'diag.verdict.directOnly', cause: 'diag.verdict.directOnlyCause|inv.diagStepProxyConnect' })
  })

  it('aynı yolda yalnız browser modu el sıkışmada düşüyor → parmak izi filtresi şüphesi', () => {
    const d = { ...OK, combos: [combo('direct', 'browser', false, { step_reached: 'tls-handshake', error_class: 'TIMEOUT' }), combo('direct', 'default', true),
      combo('proxy', 'browser', true), combo('proxy', 'default', true)] }
    const v = buildVerdict(d, buildSteps(d), t)
    expect(v).toMatchObject({ tone: 'warning', stage: 'tls', title: 'diag.verdict.fingerprint', cause: 'diag.verdict.fingerprintCause|inv.diagTlsBrowserMode' })
  })

  it('süresi dolmuş sertifika erişilebilir hükmü danger\'a çeker', () => {
    const d = { ...OK, combos: OK.combos.map((c) => ({ ...c, days_remaining: -2 })) }
    const v = buildVerdict(d, buildSteps(d), t)
    expect(v.tone).toBe('danger')
    expect(v.notes[0]).toEqual({ tone: 'danger', text: 'diag.verdict.certExpired' })
  })

  it('bütün sondalar aşamasız (zaman aşımı sentetik) → belirsiz; kısmen bitmişse not düşer', () => {
    const d = { ...OK, combos: [combo('direct', 'browser', false), combo('direct', 'default', false)] }
    expect(buildVerdict(d, buildSteps(d), t).title).toBe('diag.verdict.unknown')
    const partial = { ...OK, combos: [combo('direct', 'browser', true), combo('direct', 'default', false)] }
    const v = buildVerdict(partial, buildSteps(partial), t)
    expect(v.notes).toEqual([{ tone: 'warning', text: 'diag.verdict.unknownNote|1' }])
    expect(buildVerdict(null, buildSteps(null), t).title).toBe('diag.verdict.unknown')
  })
})

describe('diagModel — zamanlama, rapor, karşılaştırma', () => {
  it('buildLanes: DNS + her sonda, en uzun süreye göre ölçek, toplam', () => {
    const { lanes, max, total } = buildLanes(OK)
    expect(lanes.map((l) => l.key)).toEqual(['dns', 'direct+browser', 'direct+default', 'proxy+browser', 'proxy+default'])
    expect(lanes[0]).toMatchObject({ kind: 'dns', ms: 12, status: 'ok' })
    expect(lanes[3]).toMatchObject({ via: 'proxy', mode: 'browser', ms: 340, status: 'ok', stage: 'cert-ok' })
    expect(max).toBe(400)
    expect(total).toBe(400)
    expect(buildLanes(null)).toEqual({ lanes: [], max: 1, total: null })
  })

  it('buildReport: hedef, hüküm, adımlar ve matris satırları düz metinde', () => {
    const steps = buildSteps(TLS_FAIL_DIRECT)
    const verdict = buildVerdict(TLS_FAIL_DIRECT, steps, t)
    const text = buildReport({ data: TLS_FAIL_DIRECT, steps, verdict, t, formatDate: (s) => s, now: new Date('2026-09-26T10:00:00Z') })
    expect(text).toContain('diag.report.target: api.example.com:443')
    expect(text).toContain('diag.report.verdict: diag.verdict.proxyOnly')
    expect(text).toContain('direct · browser: TIMEOUT — Read timed out during TLS handshake')
    expect(text).toContain('proxy · browser: inv.diagOk · cert-ok · 200 ms · TLSv1.2 · TLS_AES_256_GCM_SHA384 · ALPN h2 · 12d')
    expect(text).toContain('! diag.verdict.certExpiring|12')
    expect(text).toContain('(pod-a · 10.0.0.5 · node worker-1)')
  })

  it('diffRuns: yalnız değişen alanlar; aynı koşu → boş', () => {
    expect(diffRuns(OK, OK, t)).toEqual([])
    const rows = diffRuns(TLS_FAIL_DIRECT, OK, t)
    const keys = rows.map((r) => r[0])
    expect(keys).toContain('ok')
    expect(rows.find((r) => r[0] === 'ok').slice(2)).toEqual(['4/4', '2/4'])
    expect(rows.find((r) => r[0] === 'direct+browser:status').slice(2)).toEqual(['inv.diagOk', 'TIMEOUT @ tls-handshake'])
    expect(rows.find((r) => r[0] === 'proxy+browser:days').slice(2)).toEqual([62, 12])
    expect(keys).not.toContain('dns')     // DNS aynı
  })
})

describe('diagModel — 429 tespiti ve alan adı yardımcıları', () => {
  it('wasRateLimited: gövde status 429 ya da halkadaki EN SON aynı-yol kaydı 429', () => {
    expect(wasRateLimited({ success: false, status: 429 }, '/x', [])).toBe(true)
    const ring = [{ path: '/admin/diagnostics/domain-expiry', status: 429 }, { path: '/admin/other', status: 500 }]
    expect(wasRateLimited({ success: false }, '/admin/diagnostics/domain-expiry', ring)).toBe(true)
    // aynı yol için daha sonra 500 geldiyse artık 429 değil
    expect(wasRateLimited({ success: false }, '/admin/diagnostics/domain-expiry', [...ring, { path: '/admin/diagnostics/domain-expiry', status: 500 }])).toBe(false)
    expect(wasRateLimited({ success: false }, '/admin/diagnostics', ring)).toBe(false)
    expect(wasRateLimited(null, '/x', null)).toBe(false)
  })

  it('normaliseDomainInput: şema/yol/kimlik/port/son nokta atılır, küçük harf', () => {
    expect(normaliseDomainInput(' HTTPS://User:pw@WWW.Example.com:8443/path?q=1#f ')).toBe('www.example.com')
    expect(normaliseDomainInput('example.com.')).toBe('example.com')
    expect(normaliseDomainInput(null)).toBe('')
  })

  it('isValidDomain: en az iki etiket, tire kuralları, sayısal TLD yok, 253 sınırı', () => {
    expect(isValidDomain('example.com')).toBe(true)
    expect(isValidDomain('sub.örnek.com')).toBe(true)
    expect(isValidDomain('localhost')).toBe(false)
    expect(isValidDomain('-bad.example.com')).toBe(false)
    expect(isValidDomain('bad-.example.com')).toBe(false)
    expect(isValidDomain('203.0.113.10')).toBe(false)
    expect(isValidDomain('a'.repeat(64) + '.com')).toBe(false)
    expect(isValidDomain('')).toBe(false)
  })

  it('son sorgular: en yeni başta, tekrar yok, en fazla 8; bozuk depo boş döner; temizleme', () => {
    const store = new Map()
    const storage = { getItem: (k) => store.get(k) ?? null, setItem: (k, v) => store.set(k, v), removeItem: (k) => store.delete(k) }
    expect(readRecent(storage)).toEqual([])
    pushRecent('a.example.com', storage)
    pushRecent('b.example.com', storage)
    expect(pushRecent('a.example.com', storage)).toEqual(['a.example.com', 'b.example.com'])
    for (let i = 0; i < 10; i++) pushRecent(`h${i}.example.com`, storage)
    expect(readRecent(storage)).toHaveLength(8)
    storage.setItem('sm.dexp.recent', '{bozuk')
    expect(readRecent(storage)).toEqual([])
    expect(clearRecent(storage)).toEqual([])
    expect(store.has('sm.dexp.recent')).toBe(false)
  })

  it('shortCn / daysTone', () => {
    expect(shortCn('CN=www.example.com, O=Example')).toBe('www.example.com')
    expect(shortCn('O=NoCN')).toBe('O=NoCN')
    expect(shortCn(null)).toBeNull()
    expect([daysTone(null), daysTone(0), daysTone(7), daysTone(8), daysTone(30), daysTone(31)]).toEqual(['muted', 'danger', 'danger', 'warning', 'warning', 'success'])
  })
})
