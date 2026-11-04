import { describe, it, expect } from 'vitest'
import { TR } from '../i18n/tr.js'
import {
  activityCell, activityDetailHref, actionLabel, checkTypeOf, compareModel, diagnoseKindOf, formatDelta, formatIstanbul,
  hostOfTarget, legacyDetail, localizeSummary, measuresOf, normalizeDetail, normalizeLinks, parseObject, pseudoCheck,
  pseudoMonitor, rowHealth, rowReason, rowResponseMs, seriesModel, statusLabel, transitionOf,
} from '../components/activity/detail/activityDetailModel.js'

/** Gerçek TR sözlüğüyle t — eksik anahtarda anahtarın kendisi (useT ile aynı sözleşme). */
const t = (k, ...a) => (TR[k] ?? k).replace(/\{(\d+)\}/g, (_, i) => String(a[Number(i)] ?? ''))

const ACT = {
  id: 42, activity_time: '2026-11-05T10:00:00', monitor_type: 'PING', monitor_id: 9, monitor_name: 'ağ geçidi',
  target: '10.0.0.1', action: 'SCHEDULED_CHECK', result_status: 'ERROR', result_summary: 'zaman aşımı',
  error_message: 'Request timed out', team_id: 1, response_ms: null,
}

describe('aktivite ayrıntısı modeli — yanıt okuma', () => {
  it('normalizeDetail: failure_detail metni nesneye çevrilir, kontrol türü ve eşleşme okunur', () => {
    const d = normalizeDetail({
      success: true,
      data: { ...ACT, failure_reason: 'ICMP_NO_REPLY', failure_detail: '{"phase":"NETWORK","target":"10.0.0.1"}' },
      check: { type: 'PING', match: 'exact', row: { id: 7, up: false, failure_reason: 'ICMP_NO_REPLY' } },
      previous_check: { id: 6, up: true }, next_check: null,
      series: { from: '2026-11-04T10:00:00', to: '2026-11-06T10:00:00', points: [{ t: '2026-11-05T10:00:00', ms: null, status: 'ERROR' }] },
      alarm: { id: 5, alert_type: 'PING_DOWN', level: 'CRITICAL', match: 'weird', can_open: 'yes' },
      change: null,
      links: { monitor: { tab: 'ping', params: { monitor: 9, junk: { x: 1 } } }, history: null, can_diagnose: true },
      actor: { name: 'scheduler', request_id: 'abc123' },
    })
    expect(d.legacy).toBe(false)
    expect(d.data.failure_detail).toEqual({ phase: 'NETWORK', target: '10.0.0.1' })
    expect(d.check).toEqual({ type: 'ping', match: 'exact', row: { id: 7, up: false, failure_reason: 'ICMP_NO_REPLY' } })
    // Belirsiz eşleşme "kesin" sayılmaz; can_open yalnız tam true
    expect(d.alarm.match).toBe('approximate')
    expect(d.alarm.can_open).toBe(false)
    expect(d.links.monitor).toEqual({ tab: 'ping', params: { monitor: 9 } })
    expect(d.links.canDiagnose).toBe(true)
    expect(d.links.source).toBe('server')
    expect(d.actor).toEqual({ name: 'scheduler', requestId: 'abc123' })
    expect(d.series.points).toHaveLength(1)
  })

  it('normalizeDetail: data yoksa null; bozuk alanlar düşer', () => {
    expect(normalizeDetail({ success: true })).toBeNull()
    expect(normalizeDetail(null)).toBeNull()
    const d = normalizeDetail({ success: true, data: { ...ACT }, check: { match: 'exact' }, alarm: { level: 'HIGH' }, change: { kind: 'ping', id: 9 }, series: { points: 'x' } })
    expect(d.check).toBeNull()       // satırsız kontrol bloğu yok sayılır
    expect(d.alarm).toBeNull()       // kimliksiz alarm yok sayılır
    expect(d.change).toBeNull()      // seq yoksa değişiklik yok
    expect(d.series).toBeNull()
  })

  it('normalizeLinks: eski sunucuda satırdan türetilir (geçmiş = mtab=control, tanılama kapısı bilinmez)', () => {
    const l = normalizeLinks(undefined, { ...ACT, alert_event_id: 77 })
    expect(l.monitor).toEqual({ tab: 'ping', params: { monitor: 9 } })
    expect(l.history).toEqual({ tab: 'ping', params: { monitor: 9, mtab: 'control' } })
    expect(l.changes).toEqual({ tab: 'ping', params: { monitor: 9, mtab: 'changes' } })
    expect(l.alert).toEqual({ tab: 'alerthistory', params: { alert: 77 } })
    expect(l.canDiagnose).toBe(false)
    expect(l.source).toBe('derived')
    // Sertifika: izleme kimliği yok → geçmiş / değişiklik bağlantısı yok, kart penceresi
    const c = normalizeLinks(null, { monitor_type: 'CERT', target: 'a.example.com' })
    expect(c.monitor).toEqual({ tab: 'dashboard', params: { domain: 'a.example.com' } })
    expect(c.history).toBeNull()
  })

  it('legacyDetail: temel uç → yalnız satır + mini geçmiş', () => {
    const d = legacyDetail({ success: true, data: { ...ACT }, recent: [{ id: 1 }, 'bozuk', null] })
    expect(d.legacy).toBe(true)
    expect(d.recent).toEqual([{ id: 1 }])
    expect(d.check).toBeNull()
    expect(legacyDetail({ success: false })).toBeNull()
  })

  it('parseObject ve checkTypeOf', () => {
    expect(parseObject('{"a":1}')).toEqual({ a: 1 })
    expect(parseObject('[1]')).toBeNull()
    expect(parseObject('{bozuk')).toBeNull()
    expect(checkTypeOf('DBQUERY', null)).toBe('dbquery')
    expect(checkTypeOf('PING', { type: 'Port' })).toBe('port')
    expect(checkTypeOf('YENI_TUR', null)).toBeNull()
  })
})

describe('kontrol satırı okuma', () => {
  it('rowHealth: alan yoksa bilinmiyor — asla sağlıklı değil', () => {
    expect(rowHealth('ping', {})).toBe('unknown')
    expect(rowHealth('ping', { up: true })).toBe('up')
    expect(rowHealth('port', { open: false })).toBe('down')
    expect(rowHealth('uptime', { status: 'UP' })).toBe('up')
    expect(rowHealth('dns', { value: '1.2.3.4' })).toBe('up')
    expect(rowHealth('dns', {})).toBe('unknown')
    expect(rowHealth('cert', { status: 'warning' })).toBe('warn')
    expect(rowHealth('cert', { status: 'error' })).toBe('down')
    expect(rowHealth('cert', { status: '' })).toBe('unknown')
    expect(rowHealth('domain', { status: 'CRITICAL' })).toBe('warn')
    expect(rowHealth('domain', { status: 'UNKNOWN' })).toBe('down')
    expect(rowHealth('dbquery', { status: 'BREACH', breach_level: 'WARNING' })).toBe('warn')
    expect(rowHealth('dbquery', { status: 'BREACH', breach_level: 'CRITICAL' })).toBe('down')
    expect(rowHealth('dbquery', { status: 'UNKNOWN' })).toBe('unknown')
    expect(rowHealth('scripted', { status: 'NO_CHECKS' })).toBe('warn')
    expect(rowHealth('scripted', { status: 'SKIPPED' })).toBe('unknown')
    expect(rowHealth('http', { ok: true })).toBe('up')
    expect(rowHealth('keyword', {})).toBe('unknown')
    expect(rowHealth('page', { status: 'OK', ok: true })).toBe('up')
    expect(rowHealth('ping', null)).toBe('unknown')
  })

  it('rowResponseMs: ping rtt_ms, diğerleri response_ms', () => {
    expect(rowResponseMs('ping', { rtt_ms: 12, response_ms: 99 })).toBe(12)
    expect(rowResponseMs('http', { response_ms: '450' })).toBe(450)
    expect(rowResponseMs('http', {})).toBeNull()
  })

  it('rowReason: ortak teşhis modeli, HTTP tanısı, DB sorgusu; sağlıklı satırda null', () => {
    const ping = rowReason('ping', { up: false, failure_reason: 'ICMP_NO_REPLY' }, { host: '10.0.0.1' }, t)
    expect(ping.code).toBe('ICMP_NO_REPLY')
    expect(ping.short).toBe(TR['chkfail.ICMP_NO_REPLY.short'])
    expect(rowReason('ping', { up: true }, null, t)).toBeNull()
    expect(rowReason('ping', {}, null, t)).toBeNull()   // bilinmiyor → neden uydurulmaz
    const http = rowReason('http', { ok: false, error_detail: { kind: 'CONNECT_TIMEOUT' } }, null, t)
    expect(http).toEqual({ code: 'CONNECT_TIMEOUT', short: TR['httpdiag.kind.CONNECT_TIMEOUT'], tone: 'danger' })
    expect(rowReason('http', { ok: false, http_status: 503 }, null, t).short).toBe('HTTP 503')
    const db = rowReason('dbquery', { status: 'FAIL', failure_reason: 'DB_TIMEOUT', error_message: 'Sorgu zaman aşımı\nikinci satır' }, null, t)
    expect(db).toEqual({ code: 'DB_TIMEOUT', short: 'Sorgu zaman aşımı', tone: 'danger' })
  })

  it('pseudoCheck: yalnız ERROR / ZAMAN AŞIMI aktivitede; türün teşhis paneline hatalı satır', () => {
    expect(pseudoCheck('ping', { ...ACT, result_status: 'SUCCESS' })).toBeNull()
    const p = pseudoCheck('ping', { ...ACT, failure_reason: 'ICMP_NO_REPLY', http_status: null })
    expect(p).toMatchObject({ pseudo: true, up: false, failure_reason: 'ICMP_NO_REPLY', error: 'Request timed out' })
    expect(rowHealth('ping', p)).toBe('down')
    expect(pseudoCheck('scripted', { ...ACT, result_status: 'TIMEOUT' }).status).toBe('TIMEOUT')
    expect(pseudoCheck('cert', { ...ACT, target: 'https://a.example.com:8443/x', error_class: 'NETWORK' }))
      .toMatchObject({ status: 'error', domain: 'a.example.com', error_class: 'NETWORK' })
    expect(rowHealth('dns', pseudoCheck('dns', ACT))).toBe('down')
  })

  it('pseudoMonitor ve hostOfTarget', () => {
    expect(pseudoMonitor('port', { monitor_id: 3, target: 'db.example.com:5432' })).toMatchObject({ id: 3, host: 'db.example.com', port: 5432 })
    expect(pseudoMonitor('port', { monitor_id: 3, target: '[::1]:22' })).toMatchObject({ host: '[::1]', port: 22 })
    expect(pseudoMonitor('http', { monitor_id: 1, target: 'https://x.example.com/a' }).url).toBe('https://x.example.com/a')
    expect(pseudoMonitor('uptime', { target: 'x.example.com:443' }).domain).toBe('x.example.com')
    expect(hostOfTarget('https://user@x.example.com:8443/p?q')).toBe('user@x.example.com')
    expect(hostOfTarget('')).toBe('')
    expect(diagnoseKindOf('port')).toBe('port')
    expect(diagnoseKindOf('cert')).toBeNull()
  })
})

describe('kıyas', () => {
  it('transitionOf: başladı / sürüyor / düzeldi / sakin; bilinmeyende null', () => {
    expect(transitionOf('up', 'down')).toBe('started')
    expect(transitionOf('warn', 'down')).toBe('continues')
    expect(transitionOf('down', 'up')).toBe('recovered')
    expect(transitionOf('up', 'up')).toBe('stable')
    expect(transitionOf('unknown', 'down')).toBeNull()
    expect(transitionOf(null, 'down')).toBeNull()
  })

  it('compareModel: fark, HTTP / neden değişimi, geçiş; aktivite kimliği komşuya taşınır', () => {
    const m = compareModel({
      type: 'http', monitor: null, t,
      previous: { ok: true, response_ms: 200, http_status: 200, checked_at: '2026-11-05T09:55:00', activity_id: 41 },
      current: { ok: false, response_ms: 650, http_status: 503, checked_at: '2026-11-05T10:00:00' },
      next: { ok: true, response_ms: 210, http_status: 200, checked_at: '2026-11-05T10:05:00' },
    })
    const [prev, cur, next] = m.cells
    expect(prev.activityId).toBe(41)
    expect(cur.delta).toBe(450)
    expect(cur.httpChanged).toBe(true)
    expect(cur.reasonChanged).toBe(true)
    expect(next.delta).toBe(-440)
    expect(m.transition).toBe('started')
    expect(m.after).toBe('recovered')
    expect(formatDelta(450)).toBe('+450 ms')
    expect(formatDelta(-440)).toBe('−440 ms')
    expect(formatDelta(0)).toBe('±0 ms')
    expect(formatDelta(null)).toBe('')
  })

  it('compareModel: komşu yoksa hücre boş, geçiş yok; kontrol satırı yokken aktiviteden hücre', () => {
    const cell = activityCell('ping', { ...ACT, failure_reason: 'ICMP_NO_REPLY' }, { host: '10.0.0.1' }, t)
    expect(cell.health).toBe('down')
    expect(cell.reason.code).toBe('ICMP_NO_REPLY')
    const m = compareModel({ type: 'ping', currentCell: cell, previous: null, next: { up: true, rtt_ms: 5 }, monitor: null, t })
    expect(m.cells[0].row).toBeNull()
    expect(m.cells[1].key).toBe('cur')
    expect(m.transition).toBeNull()
    expect(m.after).toBe('recovered')
  })
})

describe('±24 sa seri', () => {
  it('boş kova boşluk, tek ölçüm "lone", hatalı nokta tabanda işaret; sayımlar ve ortalama', () => {
    const s = seriesModel({
      from: '2026-11-04T10:00:00', to: '2026-11-06T10:00:00', truncated: true,
      points: [
        { t: '2026-11-05T09:00:00', ms: 100, status: 'SUCCESS' },
        { t: '2026-11-05T09:30:00', ms: 300, status: 'WARNING' },
        { t: '2026-11-05T10:00:00', ms: null, status: 'ERROR' },
        { t: '2026-11-05T10:30:00', ms: 200, status: 'SUCCESS' },
        { t: '2026-11-05T11:00:00', ms: null, status: 'UNKNOWN' },
        { t: 'bozuk', ms: 1, status: 'SUCCESS' },
      ],
    }, '2026-11-05T10:00:00')
    expect(s.points).toHaveLength(5)
    expect(s.points[2]).toMatchObject({ fail: true, failY: 0, ms: null })
    expect(s.points[3].lone).toBe(true)          // iki yanı boş tek ölçüm
    expect(s.points[0].lone).toBe(false)
    expect(s.counts).toEqual({ total: 5, up: 2, warn: 1, fail: 1, unknown: 1 })
    expect(s.avgMs).toBe(200)
    expect(s.maxMs).toBe(300)
    expect(s.truncated).toBe(true)
    expect(s.anchor).toBe(Date.parse('2026-11-05T10:00:00Z'))
    expect(s.from).toBe(Date.parse('2026-11-04T10:00:00Z'))
    expect(seriesModel(null, null).empty).toBe(true)
  })
})

describe('biçim ve etiketler', () => {
  it('formatIstanbul: sunucu damgası UTC → İstanbul (UTC+3)', () => {
    expect(formatIstanbul('2026-11-05T10:00:00', 'tr-TR')).toContain('13:00:00')
    expect(formatIstanbul('', 'tr-TR')).toBe('—')
    expect(formatIstanbul('bozuk', 'tr-TR')).toBe('bozuk')
  })

  it('actionLabel / statusLabel: sözlükte yoksa ham kod ya da "Bilinmiyor" — ham anahtar asla', () => {
    expect(actionLabel('SCHEDULED_CHECK', t)).toBe(TR['act.ac.SCHEDULED_CHECK'])
    expect(actionLabel('YENI_EYLEM', t)).toBe('YENI_EYLEM')
    expect(actionLabel('', t)).toBe('—')
    expect(statusLabel('TIMEOUT', t)).toBe(TR['act.st.TIMEOUT'])
    expect(statusLabel('XYZ', t)).toBe(TR['act.st.UNKNOWN'])
  })

  it('measuresOf: yalnız kayıtta olan değerler, sırayla', () => {
    expect(measuresOf('http', { http_status: 200, response_ms: 123.4 }, t)).toEqual([
      { key: 'httpStatus', value: 'HTTP 200' }, { key: 'response', value: '123 ms' },
    ])
    expect(measuresOf('dns', { record_type: 'A', value: '1.2.3.4' }, t).map((m) => m.key)).toEqual(['recordType', 'value'])
    expect(measuresOf('cert', { days_remaining: 12, tls_version: 'TLSv1.3' }, t)).toEqual([
      { key: 'days', value: t('act.days', 12) }, { key: 'tls', value: 'TLSv1.3' },
    ])
    expect(measuresOf('ping', null, t)).toEqual([])
  })

  it('localizeSummary ve paylaşılabilir adres', () => {
    expect(localizeSummary('0 kırık · 12ms', (k) => (k === 'act.tok.broken' ? 'broken' : k))).toBe('0 broken · 12ms')
    expect(activityDetailHref(42)).toMatch(/\?tab=activity&act=42$/)
  })
})
