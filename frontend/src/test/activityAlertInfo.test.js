import { describe, it, expect } from 'vitest'
import { TR } from '../i18n/tr.js'
import { EN } from '../i18n/en.js'
import { alertInfoSummary } from '../components/activity/alertInfoSummary.js'
import { localizeSummary } from '../components/activity/activityModel.js'
import { localizeSummary as detailLocalize, pseudoMonitor } from '../components/activity/detail/activityDetailModel.js'

/** Sözlüğe dayalı küçük t (projedeki {0} konumsal yer tutucu kuralı). */
const tOf = (dict) => (key, ...args) => {
  const v = dict[key]
  if (v == null) return key
  return args.reduce((s, a, i) => s.split(`{${i}}`).join(String(a)), v)
}

/**
 * Alarm olayı satırının özeti satırın dilinde (2026-11-05, aktivite incelemesi FR D5) — sunucunun makinece okunur
 * `alert_info`'sundan; DNS sahte izlemesinin alan adı kayıt türünü içermez (FR D13).
 */
describe('alarm olayı satırının özeti (alert_info)', () => {
  const row = (info) => ({ action: 'ALERT_RESOLVED', result_summary: 'Port kapalı · HIGH · elle Ayşe', alert_info: info })

  it('İngilizce arayüzde sunucunun Türkçe özeti değil, satırın dilinde metin', () => {
    const t = tOf(EN)
    const s = localizeSummary('Port kapalı · HIGH · elle Ayşe', t,
      row({ alert_type: 'PORT_DOWN', level: 'HIGH', closed_by: 'manual', closer: 'Ayşe' }))
    expect(s).toContain('HIGH')
    expect(s).toContain('closed by Ayşe')
    expect(s).not.toContain('elle')
    expect(detailLocalize('x', t, row({ alert_type: 'PORT_DOWN', level: 'HIGH', closed_by: 'manual', closer: 'Ayşe' }))).toBe(s)
  })

  it('yükseltme önceki → yeni seviye; otomatik / sessiz kapanış; bakımda açılış', () => {
    const t = tOf(TR)
    expect(alertInfoSummary({ alert_info: { alert_type: 'HTTP_DOWN', level: 'CRITICAL', from_level: 'HIGH' } }, t))
      .toContain('YÜKSEK → KRİTİK')
    expect(alertInfoSummary({ alert_info: { alert_type: 'HTTP_DOWN', level: 'HIGH', closed_by: 'auto' } }, t))
      .toContain('otomatik kapandı')
    expect(alertInfoSummary({ alert_info: { alert_type: 'HTTP_DOWN', level: 'HIGH', closed_by: 'silent', closer: 'Sistem (izleme silindi)' } }, t))
      .toContain('sessizce kapandı: Sistem (izleme silindi)')
    expect(alertInfoSummary({ alert_info: { alert_type: 'DNS_CHANGED', level: 'WARNING', code: 'maintenance_open' } }, t))
      .toContain('bakım penceresinde açıldı')
  })

  it('alert_info yoksa / bozuksa sunucu özeti (eski sunucu, eski satır)', () => {
    const t = tOf(TR)
    expect(localizeSummary('Port kapalı · HIGH', t, { action: 'ALERT_TRIGGERED' })).toBe('Port kapalı · HIGH')
    expect(localizeSummary('Port kapalı · HIGH', t, { alert_info: 'bozuk' })).toBe('Port kapalı · HIGH')
    expect(localizeSummary('Port kapalı · HIGH', t, { alert_info: { level: 'HIGH' } })).toBe('Port kapalı · HIGH')
    expect(localizeSummary('0 kırık · 12ms', t)).toBe('0 kırık · 12ms')
  })
})

describe('DNS sahte izlemesi (FR D13)', () => {
  it('hedef "alan.com A" → alan adı ve kayıt türü ayrı', () => {
    expect(pseudoMonitor('dns', { monitor_id: 4, target: 'alan.com A' })).toMatchObject({ id: 4, domain: 'alan.com', record_type: 'A' })
    expect(pseudoMonitor('dns', { monitor_id: 4, target: 'alan.com' })).toMatchObject({ domain: 'alan.com', record_type: null })
    expect(pseudoMonitor('domain', { monitor_id: 5, target: 'alan.com' })).toMatchObject({ domain: 'alan.com' })
  })
})
