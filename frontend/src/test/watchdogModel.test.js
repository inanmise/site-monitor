import { describe, it, expect } from 'vitest'
import { checkState, checkTone, openProblems, watchdogReasons } from '../components/admin/health/watchdogModel.js'
import { deriveOverall } from '../components/admin/health/healthModel.js'

/** Kendi kendini izleme modeli (2026-10-11): satır durumu / tonu, açık sorunlar, Sistem Sağlığı sebepleri. */
describe('watchdogModel', () => {
  it('checkState / checkTone: onaylı sorun, tek kötü ölçüm (izleniyor), ölçülemedi, uygulanmaz, bekliyor', () => {
    expect(checkState({ status: 'PROBLEM', last_result: 'PROBLEM' })).toBe('problem')
    expect(checkTone({ status: 'PROBLEM', severity: 'CRITICAL' })).toBe('danger')
    expect(checkTone({ status: 'PROBLEM', severity: 'HIGH' })).toBe('warning')
    expect(checkState({ status: 'OK', last_result: 'PROBLEM' })).toBe('watch')
    expect(checkState({ status: 'OK', last_result: 'UNKNOWN' })).toBe('unknown')
    expect(checkState({ status: 'OK', last_result: 'NA' })).toBe('na')
    expect(checkState({ status: 'PENDING' })).toBe('pending')
    expect(checkTone({ status: 'OK', last_result: 'OK' })).toBe('success')
  })

  it('sebepler: kritik → down, diğer → warn, değerlendirme durmuş → down; kapalıysa yok', () => {
    const data = { enabled: true, stale: false, checks: [
      { key: 'cert_sweep', label: 'Sertifika taraması', status: 'PROBLEM', severity: 'HIGH' },
      { key: 'sweep_leader', label: 'Tarama liderliği', status: 'PROBLEM', severity: 'CRITICAL' },
      { key: 'database', label: 'Veritabanı', status: 'OK' }] }
    expect(openProblems(data).map((c) => c.key)).toEqual(['sweep_leader', 'cert_sweep'])
    expect(watchdogReasons(data).map((r) => r.level)).toEqual(['down', 'warn'])
    expect(watchdogReasons({ ...data, stale: true })[0]).toMatchObject({ level: 'down', code: 'watchdogStale' })
    expect(watchdogReasons({ ...data, enabled: false })).toEqual([])
    expect(watchdogReasons(null)).toEqual([])
    const overall = deriveOverall({ health: {}, watchdog: data })
    expect(overall.level).toBe('down')
    expect(overall.reasons.filter((r) => r.section === 'watchdog')).toHaveLength(2)
  })
})
