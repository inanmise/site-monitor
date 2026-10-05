import { describe, it, expect } from 'vitest'
import { TR } from '../i18n/tr.js'
import { EN } from '../i18n/en.js'
import { PG_FINDING_CODES, PS_FINDING_CODES, PAGE_DIAG_PATH, PAGESPEED_DIAG_PATH } from '../components/page/diagnose/pageDiagCodes.js'
import {
  buildReport, buildVerdict, clientAgrees, findingText, findingTitle, failureKind, localizeParams, outcomeTone,
} from '../components/http/diagnose/httpDiagnoseModel.js'
import {
  counterRows, issueRows, issueMeta, pageReportExtra, recordedTone, ruleRows,
} from '../components/page/diagnose/pageDiagnoseModel.js'
import {
  formatValue, metricRows, pageSpeedReportExtra, phaseRows, resourceRows, statusTone, thresholdChips,
} from '../components/pagespeed/diagnose/pageSpeedDiagnoseModel.js'
import { pageBroken, pagePathDiffers, speedMonitor, speedSlow } from './helpers/pageDiagnoseFixtures.js'

/** Sözlükten gerçek `t` (useT ile aynı {N} doldurma; eksik anahtarda anahtarın kendisi). */
const tFor = (dict) => (key, ...args) => {
  let s = dict[key] ?? key
  args.forEach((a, i) => { s = s.split(`{${i}}`).join(String(a ?? '')) })
  return s
}
const tr = tFor(TR)
const en = tFor(EN)
const named = (s) => [...new Set((String(s).match(/\{([a-z][a-z0-9_]*)\}/gi) || []).map((m) => m.slice(1, -1)))].sort()

/**
 * Sözleşmedeki bulgu kodu → parametre adları (backend PageDiagnosticsService / PageSpeedDiagnosticsService `params(...)`
 * çağrıları). Gövdelerin ve varyant gövdelerinin ADLI yer tutucuları bu kümenin ALT kümesi olmalı (olmayan parametre metinde
 * "—" kalırdı).
 */
const PG_PARAMS = {
  PAGE_OK: ['status', 'resources', 'ms'],
  PAGE_DOWN: ['route', 'raw_status', 'status', 'failure', 'error', 'reason'],
  PAGE_HTTP_STATUS: ['status', 'route'],
  PAGE_BODY_UNREAD: ['ms', 'status'],
  RESOURCES_BROKEN: ['count', 'alarm', 'total', 'sample'],
  RESOURCES_TIMEOUT: ['count', 'alarm', 'ms'],
  MIXED_CONTENT: ['count', 'sample'],
  SAME_HOST_BROKEN: ['count', 'host', 'sample'],
  THIRD_PARTY_ONLY: ['count', 'hosts', 'reason'],
  RESOURCES_BLOCKED: ['count', 'sample'],
  RESOURCES_SLOW: ['count', 'ms', 'slowest_ms', 'sample'],
  CRAWL_LIMIT: ['reason', 'cap', 'total', 'seconds', 'pages'],
}
const PG_VARIANTS = { PAGE_DOWN: ['unfinished'], THIRD_PARTY_ONLY: ['alarm', 'quiet'], CRAWL_LIMIT: ['resources', 'time', 'single_page'] }
const PHASE = ['ms', 'limit', 'share', 'route', 'reason']
const PS_PARAMS = {
  PAGESPEED_OK: ['total_ms', 'kb', 'requests'],
  PAGESPEED_DOWN: ['route', 'raw_status', 'status', 'failure', 'error', 'reason'],
  PAGESPEED_HTTP_STATUS: ['status', 'route'],
  PAGESPEED_BODY_UNREAD: ['ms', 'status'],
  THRESHOLD_BREACH: ['reason', 'value', 'limit', 'over', 'unit'],
  SLOW_DNS: PHASE, SLOW_CONNECT: PHASE, SLOW_TLS: PHASE, SLOW_SERVER: PHASE, SLOW_DOWNLOAD: [...PHASE, 'kb'],
  LARGE_BODY: ['kb', 'limit_kb'],
  SLOW_RESOURCES: ['html_ms', 'total_ms', 'count', 'top_url', 'top_ms'],
  HEAVY_RESOURCES: ['count', 'share', 'top_url', 'top_kb', 'kb'],
  PROXY_SLOWER: ['slow_route', 'fast_route', 'slow_ms', 'fast_ms', 'ratio'],
  DIRECT_SLOWER: ['slow_route', 'fast_route', 'slow_ms', 'fast_ms', 'ratio'],
  MEASUREMENT_PARTIAL: ['reason', 'cap', 'seconds'],
  RESOURCES_FAILED: ['count', 'sample'],
}
const PS_VARIANTS = {
  PAGESPEED_DOWN: ['unfinished'], THRESHOLD_BREACH: ['LOAD', 'TTFB', 'SIZE', 'REQUESTS'], SLOW_SERVER: ['threshold'],
  SLOW_CONNECT: ['proxy_connect', 'proxy_tunnel'], MEASUREMENT_PARTIAL: ['resources', 'bytes', 'time'],
}

function checkCatalogue(prefix, contract, variants) {
  const bad = []
  for (const [code, params] of Object.entries(contract)) {
    const keys = [`${prefix}.finding.${code}.body`, ...(variants[code] || []).map((r) => `${prefix}.finding.${code}.body.${r}`)]
    for (const k of keys) {
      if (!TR[k] || !EN[k]) { bad.push(`eksik ${k}`); continue }
      if (named(TR[k]).join(',') !== named(EN[k]).join(',')) bad.push(`${k}: TR ${named(TR[k])} ≠ EN ${named(EN[k])}`)
      for (const p of named(TR[k])) if (!params.includes(p)) bad.push(`${k}: {${p}} sözleşmede yok`)
    }
    for (const [lang, dict] of [['TR', TR], ['EN', EN]]) {
      if (!dict[`${prefix}.finding.${code}.title`]) bad.push(`${lang} ${code}.title yok`)
      if (named(dict[`${prefix}.finding.${code}.title`]).length) bad.push(`${lang} ${code}.title yer tutucu taşıyor`)
    }
  }
  return bad
}

describe('Sayfa Bütünlüğü / Sayfa Hızı tanılama — katalog ve i18n', () => {
  it('kataloglar backend ile aynı uzunlukta, kesişmiyor; sözleşme haritası her kodu kapsıyor', () => {
    expect(PG_FINDING_CODES).toHaveLength(12)
    expect(PS_FINDING_CODES).toHaveLength(17)
    expect(PG_FINDING_CODES.filter((c) => PS_FINDING_CODES.includes(c))).toEqual([])
    expect(Object.keys(PG_PARAMS).sort()).toEqual([...PG_FINDING_CODES].sort())
    expect(Object.keys(PS_PARAMS).sort()).toEqual([...PS_FINDING_CODES].sort())
  })

  it('gövdelerin (ve varyantlarının) adlı yer tutucuları sözleşmenin alt kümesi, TR = EN; başlıklar yer tutucusuz', () => {
    expect(checkCatalogue('pgdx', PG_PARAMS, PG_VARIANTS)).toEqual([])
    expect(checkCatalogue('psdx', PS_PARAMS, PS_VARIANTS)).toEqual([])
  })

  it('tüm pgdx.* / psdx.* anahtarları iki sözlükte; adlı yer tutucular aynı; "*" yok; boş değer yok', () => {
    for (const pre of ['pgdx.', 'psdx.']) {
      const trK = Object.keys(TR).filter((k) => k.startsWith(pre))
      const enK = Object.keys(EN).filter((k) => k.startsWith(pre))
      expect(trK.sort()).toEqual(enK.sort())
      const bad = trK.filter((k) => named(TR[k]).join(',') !== named(EN[k]).join(',') || /\*/.test(TR[k] + EN[k]) || !TR[k].trim() || !EN[k].trim())
      expect(bad).toEqual([])
    }
  })

  it('kod çevirisi doğru ad alanından: pgdx / psdx; ham anahtar yok; HTTP kodları httpdx\'te kalır', () => {
    expect(findingTitle('RESOURCES_BROKEN', tr)).toBe(TR['pgdx.finding.RESOURCES_BROKEN.title'])
    expect(findingTitle('THRESHOLD_BREACH', en)).toBe(EN['psdx.finding.THRESHOLD_BREACH.title'])
    expect(findingTitle('DNS_FAIL', tr)).toBe(TR['httpdx.finding.DNS_FAIL.title'])
    for (const c of [...PG_FINDING_CODES, ...PS_FINDING_CODES]) {
      expect(findingTitle(c, tr)).not.toMatch(/finding\./)
      expect(findingTitle(c, en)).not.toMatch(/finding\./)
    }
  })

  it('reason varyantları: eşik metriği, sınır türü, yol farkı (page); kontrol hata kodu {failure} yerelleşir', () => {
    const load = findingText({ code: 'THRESHOLD_BREACH', params: { reason: 'LOAD', value: 9100, limit: 5000, over: 4100, unit: 'ms' } }, tr)
    expect(load.title).toBe(TR['psdx.finding.THRESHOLD_BREACH.title.LOAD'])
    expect(load.body).toMatch(/9100 ms/)
    expect(load.body).toMatch(/4100 ms/)
    const diff = findingText({ code: 'PATH_DIFFERS', params: { failing_route: 'proxy', working_route: 'direct', working_status: 200, reason: 'page' } }, en)
    expect(diff.title).toBe(EN['httpdx.finding.PATH_DIFFERS.title.page'])
    expect(diff.body).toMatch(/On the Proxy route/)
    const down = findingText({ code: 'PAGE_DOWN', params: { route: 'direct', raw_status: 200, status: 503, failure: 'HTTP_STATUS', error: 'x' } }, tr)
    expect(down.body).toMatch(/HTTP 503 döndü/)
    expect(down.body).not.toMatch(/\{status\}/)
    const unfinished = findingText({ code: 'PAGE_DOWN', params: { route: 'direct', raw_status: 200, reason: 'unfinished' } }, tr)
    expect(unfinished.body).toBe(TR['pgdx.finding.PAGE_DOWN.body.unfinished'].replace('{route}', 'Doğrudan').replace('{raw_status}', '200'))
    expect(localizeParams({ slow_route: 'proxy', fast_route: 'direct', failed_step: 'resources' }, tr))
      .toEqual({ slow_route: TR['httpdx.route.proxy'], fast_route: TR['httpdx.route.direct'], failed_step: TR['httpdx.step.resources'] })
  })

  it('hüküm + yol sonucu: "slow" uyarı tonunda, istemci ulaştıysa uyumlu; 429 türe özgü yoldan', () => {
    const v = buildVerdict(speedSlow(), en)
    expect(v.status).toBe('warn')
    expect(v.title).toBe(EN['psdx.finding.THRESHOLD_BREACH.title.LOAD'])
    expect(outcomeTone('slow')).toBe('warning')
    expect(clientAgrees(speedSlow().paths[0])).toBe(true)
    expect(clientAgrees(pageBroken().paths[0])).toBe(true)
    expect(failureKind({ success: false }, [{ path: '/api/monitoring/page/9/diagnose', status: 429 }], PAGE_DIAG_PATH)).toBe('rateLimited')
    expect(failureKind({ success: false }, [{ path: '/api/monitoring/pagespeed/9/diagnose', status: 429 }], PAGE_DIAG_PATH)).toBe('other')
    expect(PAGESPEED_DIAG_PATH.test('/api/monitoring/page/9/diagnose')).toBe(false)
  })
})

describe('pageDiagnoseModel', () => {
  it('sayaçlar, kayıt tonu, kurallar', () => {
    const p = pageBroken().page
    expect(counterRows(p).map((c) => [c.key, c.value, c.bad])).toEqual([
      ['resources', 42, false], ['broken', 3, true], ['timeouts', 0, false], ['mixed', 0, false], ['blocked', 1, false],
      ['slow', 0, false], ['alarm', 1, true],
    ])
    expect(recordedTone(p)).toBe('danger')
    expect(recordedTone({ ...p, monitor_ok: true })).toBe('warning')
    expect(recordedTone({ recorded_status: 'OK' })).toBe('success')
    expect(ruleRows(p)).toEqual([{ key: 'thirdParty', on: false }, { key: 'mixed', on: true }, { key: 'timeout', on: true }])
  })

  it('sorun satırları + meta metni', () => {
    const rows = issueRows(pageBroken().page)
    expect(rows).toHaveLength(4)
    expect(rows[0]).toMatchObject({ kind: 'BROKEN', status: 404, alarm: true, firstParty: true, type: 'IMG' })
    expect(issueMeta(rows[0], tr)).toBe(`IMG · HTTP 404 · 12 ms · ${TR['pgdx.party.first']}`)
    expect(issueMeta(rows[2], en)).toBe(`LINK · 8 ms · ${EN['pgdx.party.third']}`)
  })

  it('rapor: başlık + kaynak bölümü + sorunlar; çözümlenemeyen sayfada not', () => {
    const text = buildReport({ data: pageBroken(), t: tr, format: 'markdown', titleKey: 'pgdx.report.title', extra: pageReportExtra })
    expect(text).toMatch(/^# SiteMonitor — Sayfa Bütünlüğü tanılama raporu/)
    expect(text).toContain(`## ${TR['pgdx.analysis.title']}`)
    expect(text).toContain('https://shop.example.com/img/yok.png')
    expect(text).toContain(TR['pgdx.alarm.yes'])
    const down = buildReport({ data: pagePathDiffers(), t: en, format: 'text', titleKey: 'pgdx.report.title', extra: pageReportExtra })
    expect(down).toContain(EN['pgdx.analysis.unanalyzed'])
    expect(down).toContain(EN['httpdx.finding.PATH_DIFFERS.title.page'].toUpperCase())   // düz metinde h2 büyük harf
  })
})

describe('pageSpeedDiagnoseModel', () => {
  it('eşik satırları: çubuk oranları sınır işaretiyle; eşiksiz metrik işaretsiz', () => {
    const rows = metricRows(speedSlow().pagespeed)
    expect(rows.map((r) => r.key)).toEqual(['LOAD', 'TTFB', 'SIZE', 'REQUESTS'])
    const load = rows[0]
    expect(load.breached).toBe(true)
    expect(load.pct).toBeLessThanOrEqual(100)
    expect(load.limitPct).toBeGreaterThan(0)
    expect(load.limitPct).toBeLessThan(load.pct)
    expect(rows[3].limitPct).toBeNull()
    expect(rows[3].pct).toBe(100)
  })

  it('faz satırları: uygulanmayan vekil fazı gizli, sınırı aşan faz işaretli', () => {
    const ps = speedSlow().pagespeed
    const rows = phaseRows(ps.routes[0], ps.phase_limits)
    expect(rows.map((r) => r.key)).toEqual(['dns', 'connect', 'tls', 'ttfb', 'download'])
    expect(rows.find((r) => r.key === 'ttfb')).toMatchObject({ ms: 1200, limit: 800, over: true })
    expect(rows.find((r) => r.key === 'dns').over).toBe(false)
  })

  it('biçim, eşik çipleri, durum tonu, kaynak satırları', () => {
    expect(formatValue(9100, 'ms')).toBe('9100 ms')
    expect(formatValue(4096, 'KB')).toBe('4.0 MB')
    expect(formatValue(64, 'req')).toBe('64')
    expect(thresholdChips(speedMonitor, tr).map((c) => c.key)).toEqual(['LOAD', 'TTFB', 'SIZE'])
    expect(thresholdChips(speedSlow().monitor, en)[0].text).toBe(`${EN['pspd.metricLoad']} ≤ 5000 ms`)
    expect(statusTone('SLOW')).toBe('warning')
    expect(resourceRows(speedSlow().pagespeed.slowest)[1]).toMatchObject({ failed: true, status: 404, thirdParty: true })
  })

  it('rapor: ölçüm bölümü, eşik satırları, fazlar, en ağır kaynak', () => {
    const text = buildReport({ data: speedSlow(), t: en, format: 'markdown', titleKey: 'psdx.report.title', extra: pageSpeedReportExtra })
    expect(text).toMatch(/^# SiteMonitor — Page speed diagnosis report/)
    expect(text).toContain(`## ${EN['psdx.analysis.title']}`)
    expect(text).toContain(`${EN['pspd.metricLoad']}: 9100 ms / 5000 ms — ${EN['psdx.metric.breached']} (+4100 ms)`)
    expect(text).toContain(EN['psdx.analysis.heaviest'])
    expect(text).toMatch(/Time to first byte 1200 ms \(!\)/)
  })
})
