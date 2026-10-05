import { describe, it, expect } from 'vitest'
import { EN } from '../i18n/en.js'
import { TR } from '../i18n/tr.js'
import {
  CHECK_FAILURE_CODES, CHECK_FAILURE_PHASE, CHECK_FAILURE_PHASES, CHECK_FAILURE_SET,
} from '../components/checks/checkFailureCodes.js'
import {
  certCode, codeFromText, detailRows, domainCodeFromText, failureOf, failureTexts, failureTone, isHealthy,
  parseFailureDetail, technicalText,
} from '../components/checks/checkFailureModel.js'

/** useT ile aynı konumsal yer tutucu kuralı ({0}, {1} …) — gerçek sözlükten. */
const tOf = (dict) => (key, ...args) => {
  let s = dict[key] ?? key
  args.forEach((a, i) => { s = s.split(`{${i}}`).join(String(a ?? '')) })
  return s
}
const t = tOf(EN)
const tTr = tOf(TR)

const PING = { id: 1, host: 'gw.example.test', timeout_ms: 3000 }
const PORT = { id: 2, host: 'db.example.test', port: 5432, protocol: 'TCP', timeout_ms: 4000 }
const DNS = { id: 3, domain: 'app.example.test', record_type: 'A' }
const PAGE = { id: 4, url: 'https://site.example.test/', timeout_ms: 8000 }
const DOMAIN = { id: 5, domain: 'example.test' }

const legacy = (type, row, monitor) => failureOf(type, row, monitor)

/** Ham anahtar ya da doldurulmamış yer tutucu ekrana düşmesin. */
function expectClean(texts) {
  for (const s of [texts.short, texts.why, texts.effect, texts.fix]) {
    expect(s).not.toMatch(/\{[a-z_]+\}|chkfail\./)
    expect(s.trim().length).toBeGreaterThan(0)
  }
}

describe('checkFailureCodes — katalog sözleşmesi', () => {
  it('her kodun TR + EN kısa/neden/etki/çözüm metni ve evresi var; evre etiketleri tam', () => {
    const missing = []
    for (const code of CHECK_FAILURE_CODES) {
      for (const part of ['short', 'why', 'effect', 'fix']) {
        const key = `chkfail.${code}.${part}`
        if (!TR[key]?.trim()) missing.push(`TR ${key}`)
        if (!EN[key]?.trim()) missing.push(`EN ${key}`)
      }
      if (!CHECK_FAILURE_PHASE[code]) missing.push(`phase ${code}`)
    }
    for (const p of CHECK_FAILURE_PHASES) {
      if (!TR[`chkfail.phase.${p}`]) missing.push(`TR phase ${p}`)
      if (!EN[`chkfail.phase.${p}`]) missing.push(`EN phase ${p}`)
    }
    expect(missing).toEqual([])
    expect(new Set(CHECK_FAILURE_CODES).size).toBe(CHECK_FAILURE_CODES.length)
  })

  it('adlı yer tutucular TR ↔ EN aynı ve yalnız modelin doldurduğu adlar', () => {
    const ALLOWED = new Set(['target', 'ms', 'status', 'expected', 'rcode', 'record', 'loss', 'proxy_status', 'allowed',
      'got', 'broken', 'timeouts', 'mixed'])
    const names = (s) => [...String(s).matchAll(/\{([a-z_]+)\}/g)].map((m) => m[1]).sort()
    const bad = []
    for (const code of CHECK_FAILURE_CODES) {
      for (const part of ['short', 'why', 'effect', 'fix']) {
        const key = `chkfail.${code}.${part}`
        const a = names(TR[key])
        const b = names(EN[key])
        if (a.join() !== b.join()) bad.push(`${key}: TR=${a} EN=${b}`)
        for (const n of [...a, ...b]) if (!ALLOWED.has(n)) bad.push(`${key}: bilinmeyen {${n}}`)
      }
    }
    expect(bad).toEqual([])
  })

  it('panel / ayrıntı etiketleri iki dilde de var (dinamik anahtarlar used-keys kapısına görünmez)', () => {
    const keys = ['phase', 'target', 'via', 'resolvedIps', 'protocol', 'ipVersion', 'family', 'httpStatus', 'expected', 'got',
      'recordType', 'rcode', 'packetLoss', 'packets', 'proxyStatus', 'allowedPorts', 'broken', 'timeouts', 'mixed',
      'totalResources', 'pages', 'dnsMs', 'connectMs', 'tlsMs', 'ttfbMs', 'responseMs', 'timeoutMs', 'maxRedirects', 'source',
      'registryRdap', 'whoisError', 'errorStage', 'exception'].map((k) => `chkhist.kv.${k}`)
    keys.push('chkhist.via.direct', 'chkhist.via.proxy', 'chkhist.stage.dns', 'chkhist.stage.tcp-connect',
      'chkhist.stage.proxy-connect', 'chkhist.stage.tls-handshake', 'chkhist.stage.cert-ok')
    expect(keys.filter((k) => !TR[k] || !EN[k])).toEqual([])
  })
})

describe('checkFailureModel — sunucu kodu', () => {
  it('kod + JSON ayrıntı → kullanıcının dilinde kısa etiket ve dolu metinler (legacy=false)', () => {
    const row = { id: 9, up: false, error: 'Yanıt yok (%100 paket kaybı)', failure_reason: 'ICMP_NO_REPLY',
      failure_detail: JSON.stringify({ phase: 'ICMP', packet_loss: 100, target: 'gw.example.test', timeout_ms: 3000 }) }
    const f = failureTexts('ping', row, PING, t)
    expect(f).toMatchObject({ code: 'ICMP_NO_REPLY', legacy: false, phase: 'ICMP', tone: 'danger', short: 'No ping reply' })
    expect(f.why).toContain('gw.example.test')
    expect(f.why).toContain('100%')
    expectClean(f)
    const tr = failureTexts('ping', row, PING, tTr)
    expect(tr.short).toBe('Ping yanıtı yok')
    expect(tr.why).toContain('gw.example.test')
    expectClean(tr)
  })

  it('her kod için metinler yer tutucusuz dolar (iki dil)', () => {
    for (const code of CHECK_FAILURE_CODES) {
      const row = { up: false, failure_reason: code, failure_detail: '{}' }
      expectClean(failureTexts('ping', row, PING, t))
      expectClean(failureTexts('ping', row, PING, tTr))
    }
  })

  it('HTTP durumu: kod + beklenen; beklenen yoksa 2xx/3xx', () => {
    const f = failureTexts('port', { open: false, failure_reason: 'HTTP_STATUS',
      failure_detail: JSON.stringify({ http_status: 503, expected: '200' }) }, PORT, t)
    expect(f.short).toBe('Returned HTTP 503')
    expect(f.why).toContain('expected: 200')
    const p = failureTexts('page', { status: 'DOWN', ok: false, http_status: 502, failure_reason: 'HTTP_STATUS' }, PAGE, t)
    expect(p.why).toContain('2xx/3xx')
    expect(p.why).toContain('site.example.test')
  })

  it('bilinmeyen sunucu kodu → eski kural (ham anahtar çizilmez); bozuk JSON ayrıntı → boş', () => {
    const f = failureOf('port', { open: false, failure_reason: 'SOMETHING_NEW', error: 'Connection refused' }, PORT)
    expect(f).toMatchObject({ code: 'CONNECT_REFUSED', legacy: true })
    expect(parseFailureDetail({ failure_detail: '{bozuk' })).toEqual({})
    expect(parseFailureDetail({ failure_detail: '[1,2]' })).toEqual({})
    expect(parseFailureDetail({})).toEqual({})
  })

  it('ton: ayar / politika / ortam kökenli nedenler uyarı, diğerleri tehlike', () => {
    expect(failureTone('CONFIG_ERROR')).toBe('warning')
    expect(failureTone('ICMP_UNAVAILABLE')).toBe('warning')
    expect(failureTone('MIXED_CONTENT')).toBe('warning')
    expect(failureTone('CONNECT_REFUSED')).toBe('danger')
  })
})

describe('checkFailureModel — sağlıklı satır', () => {
  it('her tür kendi sunucu sözleşmesiyle: sağlıklı satırda neden YOK', () => {
    expect(failureOf('ping', { up: true }, PING)).toBeNull()
    expect(failureOf('port', { open: true }, PORT)).toBeNull()
    expect(failureOf('uptime', { status: 'up' }, {})).toBeNull()
    expect(failureOf('dns', { value: '192.0.2.1' }, DNS)).toBeNull()
    expect(failureOf('page', { status: 'OK', ok: true }, PAGE)).toBeNull()
    expect(failureOf('pagespeed', { ok: true, breached_metrics: 'LOAD' }, PAGE)).toBeNull()   // SLOW kesinti değil
    expect(failureOf('domain', { status: 'CRITICAL' }, DOMAIN)).toBeNull()                    // veri var → kontrol başarılı
    expect(failureOf('cert', { status: 'warning' }, {})).toBeNull()
    expect(isHealthy('dns', { value: '' })).toBe(false)
    expect(isHealthy('page', { status: 'DEGRADED', ok: true })).toBe(false)
  })
})

describe('checkFailureModel — eski satırlar (kod yok): mevcut kart sınıflandırıcılarından EN YAKIN neden', () => {
  it('ping (pingCardModel.failureReason)', () => {
    expect(legacy('ping', { up: false, error: 'ICMP bu ortamda kullanılamıyor (yetki/binary)' }, PING))
      .toMatchObject({ code: 'ICMP_UNAVAILABLE', legacy: true })
    expect(legacy('ping', { up: false, error: 'ping: unknown host nx.example.test' }, PING).code).toBe('DNS_RESOLVE')
    expect(legacy('ping', { up: false, error: 'connect: Network is unreachable' }, PING).code).toBe('HOST_UNREACHABLE')
    expect(legacy('ping', { up: false, error: 'Yanıt yok (%100 paket kaybı)', packet_loss: 100 }, PING).code).toBe('ICMP_NO_REPLY')
    expect(legacy('ping', { up: false, error: 'garip çıktı' }, PING).code).toBe('UNKNOWN')
  })

  it('port + durum (portCardModel.portResult)', () => {
    const p = (error, mon = PORT) => legacy('port', { open: false, error }, mon).code
    expect(p('Connection refused: connect')).toBe('CONNECT_REFUSED')
    expect(p('Connect timed out')).toBe('CONNECT_TIMEOUT')
    expect(p('çözümlenemeyen host: nx.example.test')).toBe('DNS_RESOLVE')
    expect(p('HTTP 503 (beklenen: 2xx)', { ...PORT, protocol: 'HTTP' })).toBe('HTTP_STATUS')
    expect(p("Beklenen yanit yok: '220' (gelen: SSH-2.0)", { ...PORT, protocol: 'BANNER' })).toBe('BANNER_MISMATCH')
    expect(p('vekil tüneli reddetti: HTTP/1.1 403 — vekil bu porta tünel açmıyor olabilir (izinli: 443, 8443)')).toBe('PROXY_REFUSED')
    expect(p('izin verilmeyen hedef x → 169.254.169.254 (metadata)')).toBe('SSRF_BLOCKED')
    expect(p('PKIX path building failed')).toBe('TLS_TRUST')
    expect(p('Remote host terminated the handshake')).toBe('TLS_HANDSHAKE')
    expect(p('UDP yanit yok (timeout — acik/filtreli olabilir)', { ...PORT, protocol: 'UDP' })).toBe('UDP_NO_REPLY')
    expect(p('No route to host')).toBe('HOST_UNREACHABLE')
    expect(legacy('uptime', { status: 'down', error: 'Connection refused', domain: 'app.example.test', port: 443 }, {}).code)
      .toBe('CONNECT_REFUSED')
  })

  it('eski satırın metinleri sunucu metninden dolar (boş "HTTP —" kalmaz): HTTP kodu + beklenen, banner beklenen + gelen', () => {
    const http = failureTexts('port', { open: false, error: 'HTTP 503 (beklenen: 2xx)' }, { ...PORT, protocol: 'HTTP' }, t)
    expect(http).toMatchObject({ code: 'HTTP_STATUS', legacy: true, short: 'Returned HTTP 503' })
    expect(http.why).toContain('expected: 2xx')
    const banner = failureTexts('port', { open: false, error: "Beklenen yanit yok: '220' (gelen: SSH-2.0-OpenSSH)" },
      { ...PORT, protocol: 'BANNER' }, t)
    expect(banner.why).toContain('Expected: 220')
    expect(banner.why).toContain('received: SSH-2.0-OpenSSH')
    const proxy = failureTexts('port', { open: false,
      error: 'vekil tüneli reddetti: HTTP/1.1 403 Forbidden — vekil bu porta tünel açmıyor olabilir (izinli: 443, 8443)' }, PORT, t)
    expect(proxy).toMatchObject({ code: 'PROXY_REFUSED', legacy: true })
    expect(proxy.why).toContain('proxy response: 403')
    expect(proxy.why).toContain('allowed ports: 443, 8443')
    expectClean(http)
    expectClean(banner)
    expectClean(proxy)
  })

  it('dns: eski satırda hata metni yok → ad çözümlenemedi; metin varsa rcode', () => {
    expect(legacy('dns', { value: '' }, DNS)).toMatchObject({ code: 'DNS_RESOLVE', legacy: true, phase: 'DNS' })
    expect(legacy('dns', { value: '', error: 'NXDOMAIN' }, DNS).code).toBe('DNS_NXDOMAIN')
    expect(legacy('dns', { value: '', error: 'SERVFAIL' }, DNS).code).toBe('DNS_SERVFAIL')
    expect(legacy('dns', { value: '', error: 'REFUSED' }, DNS).code).toBe('DNS_REFUSED')
    expect(legacy('dns', { value: '', error: 'no answer' }, DNS).code).toBe('DNS_NO_ANSWER')
    expect(legacy('dns', { value: '', error: 'Timed out while trying to resolve' }, DNS).code).toBe('DNS_TIMEOUT')
  })

  it('sayfa (pageCardModel.pageFailure) + sayfa hızı', () => {
    expect(legacy('page', { status: 'CONFIG_ERROR', ok: false }, PAGE).code).toBe('CONFIG_ERROR')
    expect(legacy('page', { status: 'DEGRADED', ok: false, broken_resources: 2 }, PAGE).code).toBe('RESOURCES_BROKEN')
    expect(legacy('page', { status: 'DEGRADED', ok: false, broken_resources: 0, timeout_count: 3 }, PAGE).code).toBe('RESOURCES_TIMEOUT')
    expect(legacy('page', { status: 'DEGRADED', ok: false, mixed_content_count: 1 }, PAGE).code).toBe('MIXED_CONTENT')
    expect(legacy('page', { status: 'DOWN', ok: false, error: 'ana sayfa HTTP 503', http_status: 503 }, PAGE).code).toBe('HTTP_STATUS')
    expect(legacy('page', { status: 'DOWN', ok: false, error: 'Connection refused' }, PAGE).code).toBe('CONNECT_REFUSED')
    expect(legacy('pagespeed', { ok: false, error_message: "yapılandırma hatası: URL'de geçerli bir host yok" }, PAGE).code).toBe('CONFIG_ERROR')
    expect(legacy('pagespeed', { ok: false, status_code: 502, error_message: 'sayfa HTTP 502' }, PAGE).code).toBe('HTTP_STATUS')
    expect(legacy('pagespeed', { ok: false, error_message: 'request timed out' }, PAGE).code).toBe('READ_TIMEOUT')
  })

  it('alan adı (domainCardModel.unknownReasonOf): RDAP / WHOIS / bitiş yok / yapılandırma / ağ', () => {
    const d = (error) => legacy('domain', { status: 'UNKNOWN', error, checked_at: '2026-10-05T10:00:00' }, DOMAIN).code
    expect(d('rdap http 404')).toBe('RDAP_NOT_FOUND')
    expect(d('rdap http 429')).toBe('RDAP_RATE_LIMITED')
    expect(d('rdap http 503')).toBe('RDAP_UNAVAILABLE')
    expect(d('whois: no expiry parsed')).toBe('REGISTRY_NO_EXPIRY')
    expect(d('whois: Connection refused')).toBe('WHOIS_UNAVAILABLE')
    expect(d('geçersiz/çözümlenemeyen domain')).toBe('CONFIG_ERROR')
    expect(d('PKIX path building failed')).toBe('TLS_TRUST')
    expect(d(null)).toBe('REGISTRY_NO_EXPIRY')                         // kaynak yanıt verdi, bitiş yok
    expect(domainCodeFromText('garip')).toBe('RDAP_UNAVAILABLE')
  })

  it('sertifika: error_class + ileti + aşama → kod; aşama kayıtlıysa legacy=false', () => {
    expect(certCode({ error_class: 'DNS', error: 'Domain resolution failed' })).toBe('DNS_RESOLVE')
    expect(certCode({ error_class: 'BLOCKED', error: 'izin verilmeyen hedef' })).toBe('SSRF_BLOCKED')
    expect(certCode({ error_class: 'SSL', error: 'SSL handshake: PKIX path building failed' })).toBe('TLS_TRUST')
    expect(certCode({ error_class: 'SSL', error: 'SSL handshake: No name matching x found' })).toBe('TLS_HOSTNAME')
    expect(certCode({ error_class: 'SSL', error: 'SSL handshake: Received fatal alert' })).toBe('TLS_HANDSHAKE')
    expect(certCode({ error_class: 'NETWORK', error: 'Connection timeout after 6s', error_stage: 'tcp-connect' })).toBe('CONNECT_TIMEOUT')
    expect(certCode({ error_class: 'NETWORK', error: 'Connection timeout after 6s', error_stage: 'tls-handshake' })).toBe('TLS_HANDSHAKE')
    expect(certCode({ error_class: 'NETWORK', error: 'Connection refused/unreachable: Connection refused' })).toBe('CONNECT_REFUSED')
    expect(certCode({ error_class: 'NETWORK', error: 'I/O error: vekil tüneli reddetti', error_stage: 'proxy-connect' })).toBe('PROXY_ERROR')
    expect(certCode({ error_class: 'UNKNOWN', error: 'Error: x' })).toBe('UNKNOWN')
    expect(failureOf('cert', { status: 'error', error_class: 'DNS', error_stage: 'dns' }, {}).legacy).toBe(false)
    expect(failureOf('cert', { status: 'error', error_class: 'DNS' }, {}).legacy).toBe(true)
    const f = failureTexts('cert', { status: 'error', error_class: 'NETWORK', error: 'Connection timeout after 6s',
      error_stage: 'tcp-connect', domain: 'api.example.test' }, {}, t)
    expect(f.why).toContain('6000 ms')
    expect(f.why).toContain('api.example.test')
  })

  it('metin sınıflandırıcısı: eşleşmeyen → null', () => {
    expect(codeFromText('')).toBeNull()
    expect(codeFromText('tamamen başka bir şey')).toBeNull()
    expect(codeFromText('HTTP connect timed out')).toBe('CONNECT_TIMEOUT')
  })

  it('tüm eski çıkarımlar katalog içinde kalır', () => {
    const rows = [
      ['ping', { up: false, error: 'x' }], ['port', { open: false }], ['uptime', { status: 'down' }], ['dns', { value: '' }],
      ['page', { status: 'DOWN', ok: false }], ['pagespeed', { ok: false }], ['domain', { status: 'UNKNOWN' }],
      ['cert', { status: 'error' }],
    ]
    for (const [type, row] of rows) expect(CHECK_FAILURE_SET.has(failureOf(type, row, {}).code)).toBe(true)
  })
})

describe('checkFailureModel — ayrıntı satırları ve teknik metin', () => {
  it('yalnız kayıtta olan değerler; yol ve evre yerelleşir; IP virgüllü metinden de okunur', () => {
    const row = { open: false, response_ms: null, error: 'Connection refused', failure_reason: 'CONNECT_REFUSED',
      failure_detail: JSON.stringify({ phase: 'CONNECT', target: 'db.example.test:5432', via: 'direct', timeout_ms: 4000,
        resolved_ips: ['192.0.2.10', '192.0.2.11'], protocol: 'TCP', exception: 'ConnectException' }) }
    const f = failureOf('port', row, PORT)
    const rows = detailRows('port', row, PORT, t, f)
    const byKey = Object.fromEntries(rows.map((r) => [r.key, r.value]))
    expect(byKey).toMatchObject({ phase: 'Connection (TCP / proxy)', target: 'db.example.test:5432', via: 'Direct',
      resolvedIps: '192.0.2.10, 192.0.2.11', protocol: 'TCP', timeoutMs: '4000 ms', exception: 'ConnectException' })
    expect(byKey.httpStatus).toBeUndefined()
    const cert = { status: 'error', error_class: 'NETWORK', error: 'Connection timeout after 6s', error_stage: 'tcp-connect',
      resolved_ips: '192.0.2.5,192.0.2.6', domain: 'api.example.test' }
    const crow = Object.fromEntries(detailRows('cert', cert, {}, t, failureOf('cert', cert, {})).map((r) => [r.key, r.value]))
    expect(crow).toMatchObject({ errorStage: 'TCP connection', resolvedIps: '192.0.2.5, 192.0.2.6', target: 'api.example.test' })
  })

  it('eski satırda ayrıntı yok → liste kısa (uydurma yok); HTTP kodu ≥400 kötü tonda', () => {
    const row = { status: 'DOWN', ok: false, http_status: 503, error: 'ana sayfa HTTP 503' }
    const rows = detailRows('page', row, PAGE, t, failureOf('page', row, PAGE))
    expect(rows.map((r) => r.key)).toEqual(['phase', 'target', 'httpStatus'])
    expect(rows.find((r) => r.key === 'httpStatus').tone).toBe('bad')
  })

  it('teknik metin: ham hata + ileti + istisna zinciri (tekrarsız)', () => {
    const row = { status: 'down', error: 'Connection refused', failure_reason: 'CONNECT_REFUSED',
      failure_detail: JSON.stringify({ message: 'Connection refused', cause_chain: ['ConnectException: Connection refused', 'X: y'] }) }
    expect(technicalText('uptime', row, failureOf('uptime', row, {})))
      .toBe('Connection refused\nConnectException: Connection refused\nX: y')
    expect(technicalText('pagespeed', { ok: false, error_message: 'request timed out' }, { detail: {} })).toBe('request timed out')
  })
})
