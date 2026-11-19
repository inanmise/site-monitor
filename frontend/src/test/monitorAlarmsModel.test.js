import { describe, it, expect } from 'vitest'
import {
  ALARM_TABS, alarmHistoryHref, alarmTabFromUrl, alarmTypesForPage, inPageTarget, isUnsupportedSummary, summaryFrom,
} from '../components/monitoring/alarms/monitorAlarmsModel.js'
import { alertHref, listParams, monitorNameShown, monitorRefOf, FILTER_DEFAULTS, isSrcKey } from '../components/admin/alerts/alertHistoryModel.js'
import { categoryFromAlertType } from '../components/admin/alerts/alertIncidentModel.js'
import { MONITOR_ALERT_TYPES, PAGE_ALERT_TYPES, pageAlertTypesFor } from '../utils/monitorAlertTypes.js'

/**
 * İzleme sayfasının Alarmlar görünümü (2026-11-19) — saf model: URL anahtarı, sunucu özeti, Alarm Geçmişi bağlantısı,
 * sayfa içi "İzlemeyi aç" kararı; Alarm Geçmişi satırının izleme referansı ve `src=uptime` (Erişilebilirlik ayrımı).
 */
describe('monitorAlarmsModel', () => {
  it('URL değeri → sekme; geçersiz / yok → İzlemeler görünümü (null)', () => {
    expect(ALARM_TABS).toEqual(['open', 'closed', 'all'])
    for (const v of ALARM_TABS) expect(alarmTabFromUrl(v)).toBe(v)
    expect(alarmTabFromUrl(null)).toBeNull()
    expect(alarmTabFromUrl('')).toBeNull()
    expect(alarmTabFromUrl('OPEN')).toBeNull()
    expect(alarmTabFromUrl('x')).toBeNull()
  })

  it('summaryFrom: seviye kırılımı, "diğer" ve şerit; biçimsiz yanıt → null (asla sahte sıfır)', () => {
    const s = summaryFrom({
      open: { total: 7, level_counts: { CRITICAL: 2, HIGH: 1, WARNING: 3, LOW: 1 }, unacked: 4, stale: 2, stale_hours: 24 },
      top: { days: 7, items: [
        { domain: 'https://a.example.com', count: 9, open: 1, last_alert_type: 'HTTP_DOWN',
          monitor: { name: 'Ana site', type: 'http', tab: 'http', monitor_id: 11 } },
        { domain: 'https://b.example.com', count: 4, open: 0 },
        { count: 3 },   // alan adı yok → elenir
      ] },
    })
    expect(s).toMatchObject({ total: 7, critical: 2, high: 1, warning: 3, other: 1, unacked: 4, stale: 2, staleHours: 24, days: 7 })
    expect(s.top).toHaveLength(2)
    expect(s.top[0]).toMatchObject({ name: 'Ana site', monitorId: 11, monitorTab: 'http', count: 9, open: 1, key: 'http:11' })
    expect(s.top[1]).toMatchObject({ name: 'https://b.example.com', monitorId: null })
    expect(summaryFrom(null)).toBeNull()
    expect(summaryFrom({})).toBeNull()
    expect(summaryFrom({ open: 'x' })).toBeNull()
    // eksik sayılar 0 değil "bilinmiyor" değildir: alan yoksa 0 sayılır ama yanıtın kendisi biçimli
    expect(summaryFrom({ open: {} })).toMatchObject({ total: 0, staleHours: 24, top: [] })
  })

  it('eski sunucu: 404 / 405 / kimliksiz 400 → desteklenmiyor; 500 ve kodlu 400 gerçek hata', () => {
    expect(isUnsupportedSummary({ status: 404 })).toBe(true)
    expect(isUnsupportedSummary({ status: 405 })).toBe(true)
    expect(isUnsupportedSummary({ status: 400, code: 'INVALID_PARAMETER' })).toBe(true)
    expect(isUnsupportedSummary({ status: 400 })).toBe(true)
    expect(isUnsupportedSummary({ status: 400, code: 'VALIDATION_FAILED' })).toBe(false)
    expect(isUnsupportedSummary({ status: 500 })).toBe(false)
    expect(isUnsupportedSummary({ status: 0 })).toBe(false)
  })

  it('Alarm Geçmişi bağlantısı sayfa kategorisi ve görünümle', () => {
    expect(alarmHistoryHref('uptime')).toBe('?tab=alerthistory&view=open&src=uptime')
    expect(alarmHistoryHref('http', 'closed')).toBe('?tab=alerthistory&view=closed&src=http')
    expect(alarmHistoryHref('http', 'bogus')).toBe('?tab=alerthistory&view=open&src=http')
  })

  it('sayfa alarm tipleri: http ACCESSIBILITY\'siz, uptime yalnız ACCESSIBILITY; rapor kümesi değişmedi', () => {
    expect(alarmTypesForPage('http')).not.toContain('ACCESSIBILITY')
    expect(alarmTypesForPage('http')).toContain('HTTP_DOWN')
    expect(alarmTypesForPage('uptime')).toEqual(['ACCESSIBILITY'])
    expect(MONITOR_ALERT_TYPES.http).toContain('ACCESSIBILITY')
    expect(pageAlertTypesFor('yok')).toEqual([])
    expect(Object.keys(PAGE_ALERT_TYPES)).toContain('uptime')
  })

  it('inPageTarget: aynı sayfanın açma kimliği → izleme; kimliksiz + arama → arama; başka sayfa / kimliksiz → null', () => {
    const httpRow = { alert_type: 'HTTP_DOWN', domain: 'https://a.example.com', monitor_ref: { name: 'A', tab: 'http', monitor_id: 5 } }
    expect(inPageTarget(httpRow, 'http')).toEqual({ kind: 'monitor', id: 5 })
    expect(inPageTarget(httpRow, 'ping')).toBeNull()   // başka sayfanın satırı → tarayıcı gezinir
    const noId = { alert_type: 'HTTP_DOWN', domain: 'https://a.example.com', monitor_ref: { name: 'A', tab: 'http' } }
    expect(inPageTarget(noId, 'http')).toBeNull()
    const acc = { alert_type: 'ACCESSIBILITY', domain: 'a.example.com' }
    expect(inPageTarget(acc, 'uptime', { canSearch: true })).toEqual({ kind: 'search', q: 'a.example.com' })
    expect(inPageTarget(acc, 'uptime')).toBeNull()
    expect(inPageTarget(null, 'http')).toBeNull()
  })
})

describe('Alarm Geçmişi — izleme referansı ve Erişilebilirlik ayrımı (2026-11-19)', () => {
  it('alertHref: açma kimliği varsa doğrudan izleme; yoksa arama; ACCESSIBILITY → uptime sayfası', () => {
    expect(alertHref({ alert_type: 'HTTP_DOWN', domain: 'https://a.example.com', monitor_ref: { name: 'A', tab: 'http', monitor_id: 5 } }))
      .toBe('?tab=http&monitor=5')
    expect(alertHref({ alert_type: 'HTTP_DOWN', domain: 'https://a.example.com', monitor_ref: { name: 'A', tab: 'http' } }))
      .toBe('?tab=http&q=' + encodeURIComponent('https://a.example.com'))
    expect(alertHref({ alert_type: 'ACCESSIBILITY', domain: 'a.example.com' })).toBe('?tab=uptime&q=a.example.com')
    expect(alertHref({ alert_type: 'EXPIRY', domain: 'a.example.com' })).toBe('?tab=all&domain=a.example.com')
    // başka sekmenin referansı izleme açmaz (savunma)
    expect(alertHref({ alert_type: 'PING_DOWN', domain: 'h', monitor_ref: { name: 'x', tab: 'http', monitor_id: 2 } })).toBe('?tab=ping&q=h')
  })

  it('monitorRefOf / monitorNameShown: ad alan adından farklıysa gösterilir; biçimsiz / eksik → null', () => {
    expect(monitorRefOf({ monitor_ref: { name: 'A', type: 'http', tab: 'http', monitor_id: '7' } })).toEqual({ name: 'A', type: 'http', tab: 'http', monitorId: 7 })
    expect(monitorRefOf({ monitor_ref: { name: '', tab: 'http' } })).toBeNull()
    expect(monitorRefOf({})).toBeNull()
    expect(monitorNameShown({ domain: 'https://a.example.com', monitor_ref: { name: 'Ana site' } })).toBe('Ana site')
    expect(monitorNameShown({ domain: 'https://a.example.com', monitor_ref: { name: 'https://a.example.com' } })).toBeNull()
  })

  it('src=uptime geçerli kategori; liste parametresi ACCESSIBILITY, src=http ACCESSIBILITY taşımaz', () => {
    expect(isSrcKey('uptime')).toBe(true)
    const up = listParams({ tab: 'open', filters: { ...FILTER_DEFAULTS, src: 'uptime' }, page: 0, pageSize: 20, domain: null, typesParam: null })
    expect(up.alertTypes).toBe('ACCESSIBILITY')
    const http = listParams({ tab: 'open', filters: { ...FILTER_DEFAULTS, src: 'http' }, page: 0, pageSize: 20, domain: null, typesParam: null })
    expect(http.alertTypes.split(',')).not.toContain('ACCESSIBILITY')
    expect(http.alertTypes.split(',')).toContain('HTTP_DOWN')
  })

  it('olay kaydı kategorisi değişmedi: ACCESSIBILITY ağ, HTTP uygulama', () => {
    expect(categoryFromAlertType('ACCESSIBILITY')).toBe('NETWORK')
    expect(categoryFromAlertType('HTTP_DOWN')).toBe('APPLICATION')
    expect(categoryFromAlertType('EXPIRY')).toBe('CERTIFICATE')
  })
})
