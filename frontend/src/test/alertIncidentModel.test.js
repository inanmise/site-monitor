import { describe, it, expect } from 'vitest'
import {
  incidentPrefillFromAlert, severityFromAlertLevel, categoryFromAlertType, toFormIso, alertOwnerTeam,
} from '../components/admin/alerts/alertIncidentModel.js'
import { SEVERITIES, CATEGORIES, STATUSES } from '../components/incidenthistory/incidentHistoryModel.js'

/** Alarm → olay kaydı ön dolgusu (2026-10-01) — saf eşlemeler; değerler olay formunun KENDİ seçeneklerinden olmalı. */
const t = (k, ...a) => (k === 'incov.type.HTTP_DOWN' ? 'HTTP/Website erişilemez' : k.startsWith('incov.type.') ? k : `${k}|${a.join('|')}`)

describe('eşlemeler', () => {
  it('seviye → önem: olay formunun önem seçenekleri; bilinmeyen → HIGH', () => {
    expect(severityFromAlertLevel('CRITICAL')).toBe('CRITICAL')
    expect(severityFromAlertLevel('HIGH')).toBe('HIGH')
    expect(severityFromAlertLevel('WARNING')).toBe('MEDIUM')
    expect(severityFromAlertLevel('info')).toBe('LOW')
    expect(severityFromAlertLevel(undefined)).toBe('HIGH')
    for (const l of ['CRITICAL', 'HIGH', 'WARNING', 'LOW', 'X']) expect(SEVERITIES).toContain(severityFromAlertLevel(l))
  })

  it('tür → kategori: ağ / altyapı / uygulama / sertifika', () => {
    expect(categoryFromAlertType('DNS_FAILURE')).toBe('NETWORK')
    expect(categoryFromAlertType('PORT_DOWN')).toBe('NETWORK')
    expect(categoryFromAlertType('PING_DOWN')).toBe('NETWORK')
    expect(categoryFromAlertType('ACCESSIBILITY')).toBe('NETWORK')
    expect(categoryFromAlertType('DOMAINMON_EXPIRY')).toBe('INFRASTRUCTURE')
    expect(categoryFromAlertType('HTTP_DOWN')).toBe('APPLICATION')
    expect(categoryFromAlertType('KEYWORD')).toBe('APPLICATION')
    expect(categoryFromAlertType('SCRIPTED_FAIL')).toBe('APPLICATION')
    expect(categoryFromAlertType('EXPIRY')).toBe('CERTIFICATE')
    for (const ty of ['DNS_FAILURE', 'DOMAINMON_STATUS', 'PAGE_DOWN', 'REVOKED']) expect(CATEGORIES).toContain(categoryFromAlertType(ty))
  })

  it('zaman: sunucu ISO → formun UTC ISO biçimi (ek/kesir atılır); bozuk → ""', () => {
    expect(toFormIso('2026-10-01T06:00:00')).toBe('2026-10-01T06:00:00')
    expect(toFormIso('2026-10-01T06:00:00.123Z')).toBe('2026-10-01T06:00:00')
    expect(toFormIso('2026-10-01T06:00')).toBe('2026-10-01T06:00:00')
    expect(toFormIso('dün')).toBe('')
    expect(toFormIso(null)).toBe('')
  })

  it('sahip takım: damgalı takım → SY → UG', () => {
    expect(alertOwnerTeam({ team_id: 3, team_name: 'C' })).toEqual({ id: 3, name: 'C' })
    expect(alertOwnerTeam({ team_id: 3 }, 'Ad')).toEqual({ id: 3, name: 'Ad' })
    expect(alertOwnerTeam({ sy_team_id: 4, sy_team_name: 'SY', ug_team_id: 5 })).toEqual({ id: 4, name: 'SY' })
    expect(alertOwnerTeam({ ug_team_id: 5, ug_team_name: 'UG' })).toEqual({ id: 5, name: 'UG' })
    expect(alertOwnerTeam({})).toEqual({ id: null, name: '' })
  })
})

describe('incidentPrefillFromAlert', () => {
  it('açık alarm: başlık, oluş, durum OPEN, çözülme boş, servis, takım, açıklama (mesaj + alarm bağlantısı), alert_event_id', () => {
    const p = incidentPrefillFromAlert({
      id: 7, domain: 'https://www.example.com/', alert_type: 'HTTP_DOWN', alert_level: 'HIGH', resolved: false,
      created_at: '2026-10-01T06:00:00', sy_team_id: 4, sy_team_name: 'SY', message: 'YÜKSEK: site erişilemez',
    }, t)
    expect(p).toMatchObject({
      title: 'https://www.example.com/ · HTTP/Website erişilemez', occurred_at: '2026-10-01T06:00:00', resolved_at: '',
      severity: 'HIGH', status: 'OPEN', category: 'APPLICATION', service: 'https://www.example.com/',
      team_id: '4', team_name: 'SY', alert_event_id: 7,
    })
    expect(p.description).toMatch(/^YÜKSEK: site erişilemez\n\nalh\.incident\.descRef\|7: .*\?tab=alerthistory&alert=7/)
    expect(STATUSES).toContain(p.status)
  })

  it('çözülmüş alarm: durum RESOLVED + çözülme = alarm kapanışı; mesajsız açıklama yalnız bağlantı; uzun başlık 255', () => {
    const p = incidentPrefillFromAlert({
      id: 8, domain: 'x'.repeat(400), alert_type: 'HTTP_DOWN', alert_level: 'WARNING', resolved: true,
      created_at: '2026-10-01T06:00:00', resolved_at: '2026-10-01T07:30:00Z', team_id: 2,
    }, t, { teamName: 'Takım B' })
    expect(p.status).toBe('RESOLVED')
    expect(p.resolved_at).toBe('2026-10-01T07:30:00')
    expect(p.severity).toBe('MEDIUM')
    expect(p.team_name).toBe('Takım B')
    expect(p.title.length).toBe(255)
    expect(p.description).toMatch(/^alh\.incident\.descRef\|8: /)
  })

  it('alarm yoksa null', () => {
    expect(incidentPrefillFromAlert(null, t)).toBeNull()
  })
})
