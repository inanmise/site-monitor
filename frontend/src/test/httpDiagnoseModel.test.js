import { describe, it, expect } from 'vitest'
import { TR } from '../i18n/tr.js'
import { EN } from '../i18n/en.js'
import {
  FINDING_CODES, STEP_ORDER, buildReport, buildVerdict, clampTimeout, clientAgrees, failureKind, filterTranscript,
  findingText, findingTitle, focusHopIndex, formatBytes, headerRows, historyItems, interpolate, isRateLimited,
  localizeParams, normalizeSteps, pathSummarySteps, reportFileName, routeLabel, timingSegments, verdictTone, MASK,
} from '../components/http/diagnose/httpDiagnoseModel.js'
import { okSingle, pathDiffers, SECRET } from './helpers/httpDiagnoseFixtures.js'

/** Sözlükten gerçek `t` (useT ile aynı {N} doldurma). */
const tFor = (dict) => (key, ...args) => {
  let s = dict[key] ?? key
  args.forEach((a, i) => { s = s.split(`{${i}}`).join(String(a ?? '')) })
  return s
}
const tr = tFor(TR)
const en = tFor(EN)

/** Sözleşmedeki bulgu kodu → parametre adları (contract tablosu). Gövdedeki adlı yer tutucular TAM bu küme. */
const CONTRACT_PARAMS = {
  OK: ['status', 'total_ms'], SLOW: ['total_ms', 'threshold_ms'], DNS_FAIL: ['host', 'error'], TCP_REFUSED: ['address'],
  TCP_TIMEOUT: ['address', 'ms'], PROXY_CONNECT_FAIL: ['proxy'], PROXY_AUTH_REQUIRED: ['proxy'], PROXY_TUNNEL_REFUSED: ['proxy', 'status'],
  TLS_HANDSHAKE_FAIL: ['error'], TLS_UNTRUSTED: ['error'], TLS_HOSTNAME_MISMATCH: ['host'], TLS_EXPIRED: ['days_left'],
  RESPONSE_TIMEOUT: ['ms', 'route'], BODY_TIMEOUT: ['ms'], STATUS_MISMATCH: ['status', 'expected'], AUTH_REQUIRED: ['status', 'expected'],
  REDIRECT_LOOP: ['hops'], SSRF_BLOCKED: ['host'], JSON_ASSERTION_FAIL: ['path', 'reason'],
  PATH_DIFFERS: ['failing_route', 'working_route', 'working_status'], BOTH_PATHS_FAIL: ['failed_step'],
  CLIENT_MISMATCH: ['route', 'raw_status', 'client_error'],
}
const named = (s) => [...new Set((String(s).match(/\{([a-z][a-z0-9_]*)\}/gi) || []).map((m) => m.slice(1, -1)))].sort()

describe('httpDiagnoseModel — bulgu metinleri', () => {
  it('sözleşmenin 22 kodu modelde ve iki sözlükte (.title + .body) var', () => {
    expect([...FINDING_CODES].sort()).toEqual(Object.keys(CONTRACT_PARAMS).sort())
    const missing = []
    for (const code of FINDING_CODES) {
      for (const part of ['title', 'body']) {
        const k = `httpdx.finding.${code}.${part}`
        if (!TR[k]) missing.push(`TR ${k}`)
        if (!EN[k]) missing.push(`EN ${k}`)
      }
    }
    expect(missing).toEqual([])
  })

  it('gövdelerin ADLI yer tutucuları sözleşme parametreleriyle aynı küme (TR = EN); başlıklar yer tutucusuz', () => {
    const bad = []
    for (const [code, params] of Object.entries(CONTRACT_PARAMS)) {
      const want = [...params].sort()
      for (const [lang, dict] of [['TR', TR], ['EN', EN]]) {
        const got = named(dict[`httpdx.finding.${code}.body`])
        if (got.join(',') !== want.join(',')) bad.push(`${lang} ${code}: ${got.join(',')} ≠ ${want.join(',')}`)
        if (named(dict[`httpdx.finding.${code}.title`]).length) bad.push(`${lang} ${code}.title yer tutucu taşıyor`)
      }
    }
    expect(bad).toEqual([])
  })

  it('interpolate: adlı yer tutucu dolar, eksik değer "—", $& gibi özel diziler bozulmaz', () => {
    expect(interpolate('HTTP {status} · {ms} ms', { status: 401, ms: 24 })).toBe('HTTP 401 · 24 ms')
    expect(interpolate('{a} / {b}', { a: 'x' })).toBe('x / —')
    expect(interpolate('{v}', { v: 'a$&b' })).toBe('a$&b')
  })

  it('localizeParams: yol ve adım değerleri yerelleşir, dizi birleşir, boş değer "—"', () => {
    expect(localizeParams({ failing_route: 'proxy', working_route: 'direct', failed_step: 'response', hops: ['a', 'b'], error: null }, tr))
      .toEqual({ failing_route: 'Vekil', working_route: 'Doğrudan', failed_step: 'Yanıt', hops: 'a, b', error: '—' })
  })

  it('PATH_DIFFERS metni parametrelerle dolar (TR + EN)', () => {
    const f = { code: 'PATH_DIFFERS', params: { failing_route: 'proxy', working_route: 'direct', working_status: 401 } }
    expect(findingText(f, tr).title).toBe('İzlemenin yolu takılıyor, öteki yol çalışıyor')
    expect(findingText(f, tr).body).toContain('Vekil yolunda istek tamamlanamadı; Doğrudan yol HTTP 401 yanıtı aldı')
    expect(findingText(f, en).body).toContain('over the Proxy route; the Direct route got an HTTP 401 response')
  })

  it('reason varyantı: kapanan / okunamayan bağlantı "yanıt gelmedi" diye anlatılmaz; varyant yoksa genel metin', () => {
    const closed = findingText({ code: 'RESPONSE_TIMEOUT', params: { ms: 812, route: 'proxy', reason: 'closed' } }, tr)
    expect(closed.title).toBe(TR['httpdx.finding.RESPONSE_TIMEOUT.title.closed'])
    expect(closed.body).toContain('812 ms sonra bağlantıyı yanıt vermeden kapattı')
    expect(closed.body).not.toContain('{')
    const error = findingText({ code: 'RESPONSE_TIMEOUT', params: { ms: 40, route: 'direct', reason: 'error' } }, en)
    expect(error.title).toBe(EN['httpdx.finding.RESPONSE_TIMEOUT.title.error'])
    // "timeout" gerekçesinin varyantı yok → genel "yanıt takıldı" metni
    const plain = findingText({ code: 'RESPONSE_TIMEOUT', params: { ms: 10000, route: 'proxy', reason: 'timeout' } }, tr)
    expect(plain.title).toBe(TR['httpdx.finding.RESPONSE_TIMEOUT.title'])
    expect(findingText({ code: 'BODY_TIMEOUT', params: { ms: 5, reason: 'error' } }, tr).title)
      .toBe(TR['httpdx.finding.BODY_TIMEOUT.title.error'])
    // Varyant gövdeleri de TR = EN aynı adlı yer tutucular
    for (const k of ['RESPONSE_TIMEOUT.body.closed', 'RESPONSE_TIMEOUT.body.error', 'BODY_TIMEOUT.body.error']) {
      expect(named(TR[`httpdx.finding.${k}`]), k).toEqual(named(EN[`httpdx.finding.${k}`]))
    }
  })

  it('bilinmeyen kod ham anahtar değil genel metin döner; findingTitle parametresiz çalışır', () => {
    const x = findingText({ code: 'NEW_THING', params: {} }, tr)
    expect(x.known).toBe(false)
    expect(x.title).toBe('Tanınmayan bulgu: NEW_THING')
    expect(findingTitle('RESPONSE_TIMEOUT', en)).toBe('Response stuck')
  })
})

describe('httpDiagnoseModel — hüküm, adımlar, zamanlama', () => {
  it('buildVerdict: sunucunun hükmü başta, aynı kod+yol bulgusu listede TEKRAR edilmez', () => {
    const v = buildVerdict(pathDiffers(), tr)
    expect(v).toMatchObject({ status: 'fail', tone: 'danger', code: 'PATH_DIFFERS', failedStep: 'response', path: 'monitor' })
    expect(v.others.map((f) => f.code)).toEqual(['RESPONSE_TIMEOUT', 'AUTH_REQUIRED'])
    expect(v.others[0].body).toBe('İstek Vekil yolundan gönderildi ama 10000 ms içinde yanıt gelmedi. Bağlantı kuruldu; sunucu ya da aradaki vekil isteği bekletiyor.')
    expect(v.others[1].tone).toBe('info')
    expect(buildVerdict(okSingle(), en)).toMatchObject({ status: 'ok', tone: 'success', title: 'Response as expected', others: [] })
    expect(verdictTone('warn')).toBe('warning')
  })

  it('normalizeSteps sözleşme sırasına dizer ve durumu sadeleştirir; bilinmeyen adım sona', () => {
    const s = normalizeSteps([{ key: 'response', status: 'fail', ms: 9 }, { key: 'x_new', status: 'ok' }, { key: 'dns', status: 'skip' }, { key: 'tls', status: 'weird' }])
    expect(s.map((x) => x.key)).toEqual(['dns', 'tls', 'response', 'x_new'])
    expect(s.map((x) => x.state)).toEqual(['skip', 'pending', 'fail', 'ok'])
    expect(STEP_ORDER).toEqual(['dns', 'proxy_connect', 'tcp', 'proxy_tunnel', 'tls', 'request', 'response', 'body'])
  })

  it('kart özeti takılan hop\'tan, yoksa son hop\'tan', () => {
    const d = pathDiffers()
    expect(focusHopIndex(d.paths[0])).toBe(0)
    expect(focusHopIndex(d.paths[1])).toBe(1)
    expect(pathSummarySteps(d.paths[0]).find((s) => s.state === 'fail').key).toBe('response')
    expect(focusHopIndex({ hops: [] })).toBe(-1)
  })

  it('timingSegments: yanıt gelmediyse kalan süre "yanıt beklendi" (stalled) olur; bölümler ardışık', () => {
    const { segments, total } = timingSegments(pathDiffers().paths[0].timeline)
    expect(total).toBe(10002)
    expect(segments.map((s) => s.key)).toEqual(['dns', 'connect', 'proxy', 'tls', 'waitResponse'])
    const wait = segments.at(-1)
    expect(wait).toMatchObject({ ms: 9997, kind: 'stalled', start: 5 })
    expect(wait.startPct + wait.widthPct).toBeLessThanOrEqual(100)
    // başarılı yol: artık yok, ölçek toplam
    const ok = timingSegments(okSingle().paths[0].timeline)
    expect(ok.segments.map((s) => s.key)).toEqual(['dns', 'connect', 'tls', 'ttfb', 'download'])
    expect(ok.segments.every((s) => s.kind === 'phase')).toBe(true)
    expect(timingSegments(null)).toEqual({ segments: [], total: null })
  })

  it('routeLabel vekil adresini hop\'tan alır; doğrudan yol yalın', () => {
    const d = pathDiffers()
    expect(routeLabel(d.paths[0], d, tr)).toBe('Vekil · dmzproxy.example.local:8080')
    expect(routeLabel(d.paths[1], d, en)).toBe('Direct')
  })
})

describe('httpDiagnoseModel — döküm, başlıklar, hatalar, yardımcılar', () => {
  it('filterTranscript: gönderilen / alınan / bilgi', () => {
    const lines = pathDiffers().paths[1].transcript
    expect(filterTranscript(lines, 'all')).toHaveLength(lines.length)
    expect(filterTranscript(lines, 'sent').every((r) => r.text.startsWith('>'))).toBe(true)
    expect(filterTranscript(lines, 'recv').map((r) => r.text)).toContain('< HTTP/1.1 401 Unauthorized')
    expect(filterTranscript(lines, 'info').every((r) => r.text.startsWith('*'))).toBe(true)
  })

  it('headerRows: maskeli ama maske izi taşımayan değer HİÇ gösterilmez; kısmi maske korunur', () => {
    const rows = headerRows(pathDiffers().paths[0].hops[0].request.headers)
    expect(rows.find((r) => r.name === 'X-Api-Key').display).toBe(MASK)
    expect(rows.find((r) => r.name === 'Authorization')).toMatchObject({ masked: true, display: '••••' })
    const cookie = headerRows(pathDiffers().paths[1].hops[1].response.headers).find((r) => r.name === 'Set-Cookie')
    expect(cookie.display).toBe('ASP.NET_SessionId=••••; path=/; HttpOnly')
  })

  it('failureKind / isRateLimited: 429 gövdeden ya da son başarısız çağrı halkasından', () => {
    expect(failureKind({ success: false, status: 429 })).toBe('rateLimited')
    expect(failureKind({ success: false, status: 403 })).toBe('forbidden')
    expect(failureKind({ success: false, status: 404 })).toBe('notFound')
    expect(failureKind({ success: false, status: 0 })).toBe('network')
    expect(failureKind(null)).toBe('network')
    expect(failureKind({ success: false, status: 500 })).toBe('other')
    expect(isRateLimited({ success: false }, [{ path: '/monitoring/http/36/diagnose', status: 429 }])).toBe(true)
    expect(isRateLimited({ success: false }, [{ path: '/monitoring/http/36/diagnose', status: 500 }])).toBe(false)
  })

  it('clampTimeout sözleşme aralığına kısar; formatBytes; historyItems; clientAgrees', () => {
    expect([clampTimeout(500), clampTimeout(10000), clampTimeout(90000), clampTimeout(null)]).toEqual([1000, 10000, 30000, 10000])
    expect([formatBytes(512), formatBytes(40960), formatBytes(null)]).toEqual(['512 B', '40 KB', '—'])
    expect(historyItems([{ id: 1 }])).toHaveLength(1)
    expect(historyItems({ items: [{ id: 1 }, { id: 2 }] })).toHaveLength(2)
    expect(historyItems(null)).toEqual([])
    const d = pathDiffers()
    expect(clientAgrees(d.paths[0])).toBe(true)
    expect(clientAgrees({ outcome: 'ok', client_check: { ok: false } })).toBe(false)
    expect(clientAgrees({ outcome: 'ok' })).toBeNull()
  })

  it('buildReport (Markdown): hüküm, yollar, başlıklar MASKELİ, döküm; gizli değer rapora da girmez', () => {
    const md = buildReport({ data: pathDiffers(), t: tr, format: 'markdown' })
    expect(md).toMatch(/^# SiteMonitor — HTTP tanılama raporu/)
    expect(md).toContain('## Hüküm: Sorun var — İzlemenin yolu takılıyor, öteki yol çalışıyor')
    expect(md).toContain('## İzlemenin yolu — Vekil · dmzproxy.example.local:8080')
    expect(md).toContain('## Öteki yol — Doğrudan')
    expect(md).toContain('Authorization: ••••')
    expect(md).toContain('X-Api-Key: ••••')
    expect(md).not.toContain(SECRET)
    expect(md).toContain('```http')
    expect(md).toContain('* No response within 10000 ms')
    expect(md).toContain('Takılan adım: Yanıt (İzlemenin yolu)')
  })

  it('buildReport (düz metin): Markdown işareti yok, içerik aynı', () => {
    const txt = buildReport({ data: pathDiffers(), t: en, format: 'text' })
    expect(txt).not.toMatch(/^#/m)
    expect(txt).not.toContain('```')
    expect(txt).toContain('VERDICT: PROBLEM — MONITOR\'S ROUTE IS STUCK, THE OTHER ROUTE WORKS')
    expect(txt).toContain('    > GET / HTTP/1.1')
  })

  it('reportFileName çalıştırma numarasıyla', () => {
    expect(reportFileName(pathDiffers())).toBe('http-diagnose-36-run123.json')
    expect(reportFileName({ monitor: { id: 5 } }, new Date(2026, 9, 2, 13, 5))).toBe('http-diagnose-5-20261002-1305.json')
  })
})
