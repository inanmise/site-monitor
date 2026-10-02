import { describe, it, expect } from 'vitest'
import {
  EMPTY_QUIET, QUIET_DAYS, quietFromTeam, quietFromMe, quietEqual, quietErrors, quietTeamPayload, quietUserPayload,
  quietIsSet, quietSummary,
} from '../utils/quietHours.js'

/** Sessiz saat form modeli (2026-10-01, onaylı öneri 15) — sunucu kuralının (QuietHours.normalize) aynası. */
describe('utils/quietHours', () => {
  const t = (k, ...a) => (a.length ? `${k}(${a.join(',')})` : k)

  it('takım kaydı → form: boş alan = pencere yok, yedi gün; snake_case ve camelCase okunur', () => {
    expect(quietFromTeam({})).toEqual({ ...EMPTY_QUIET, days: QUIET_DAYS })
    expect(quietFromTeam({ quiet_start: '22:00', quiet_end: '07:00', quiet_days: 'FRI,MON', quiet_min_level: 'CRITICAL' }))
      .toEqual({ start: '22:00', end: '07:00', days: ['MON', 'FRI'], minLevel: 'CRITICAL' })
    expect(quietFromTeam({ quietStart: '22:00', quietEnd: '07:00' }).days).toEqual(QUIET_DAYS)
    expect(quietFromMe({ start: '23:00', end: '06:30', days: null, min_level: null }).minLevel).toBe('HIGH')
  })

  it('eşitlik: gün sırası ve boş pencere anlamca karşılaştırılır (dokunulmamış form = değişiklik yok)', () => {
    expect(quietEqual(EMPTY_QUIET, quietFromTeam({}))).toBe(true)
    expect(quietEqual({ start: '22:00', end: '07:00', days: ['FRI', 'MON'], minLevel: 'HIGH' },
      { start: '22:00', end: '07:00', days: ['MON', 'FRI'], minLevel: 'HIGH' })).toBe(true)
    expect(quietEqual(EMPTY_QUIET, { ...EMPTY_QUIET, start: '22:00' })).toBe(false)
  })

  it('doğrulama: pencere boşsa hata yok; tek uç, biçim, eşit uçlar, gün seçimi', () => {
    expect(Object.values(quietErrors(EMPTY_QUIET, t)).some(Boolean)).toBe(false)
    expect(quietErrors({ ...EMPTY_QUIET, start: '22:00' }, t).quiet_end).toBe('quiet.err.bothRequired')
    expect(quietErrors({ ...EMPTY_QUIET, start: '9:00', end: '10:00' }, t).quiet_start).toBe('quiet.err.format')
    expect(quietErrors({ ...EMPTY_QUIET, start: '10:00', end: '10:00' }, t).quiet_end).toBe('quiet.err.same')
    expect(quietErrors({ start: '22:00', end: '07:00', days: [], minLevel: 'HIGH' }, t).quiet_days).toBe('quiet.err.days')
    expect(quietErrors({ ...EMPTY_QUIET, start: '22:00' }, t, { start: 'start', end: 'end', days: 'days' }).end)
      .toBe('quiet.err.bothRequired')
  })

  it('gövde: yedi gün = boş liste (her gün), HIGH = boş seviye; pencere yok = kaldır', () => {
    expect(quietTeamPayload({ start: '22:00', end: '07:00', days: [...QUIET_DAYS], minLevel: 'HIGH' }))
      .toEqual({ quiet_start: '22:00', quiet_end: '07:00', quiet_days: [], quiet_min_level: '' })
    expect(quietUserPayload({ start: '22:00', end: '07:00', days: ['SAT', 'SUN'], minLevel: 'CRITICAL' }))
      .toEqual({ start: '22:00', end: '07:00', days: ['SAT', 'SUN'], min_level: 'CRITICAL' })
    expect(quietTeamPayload(EMPTY_QUIET)).toEqual({ quiet_start: '', quiet_end: '', quiet_days: [], quiet_min_level: '' })
  })

  it('özet metni', () => {
    expect(quietIsSet(EMPTY_QUIET)).toBe(false)
    expect(quietSummary(EMPTY_QUIET, t)).toBe('quiet.none')
    expect(quietSummary({ start: '22:00', end: '07:00', days: [...QUIET_DAYS] }, t)).toBe('22:00–07:00 · quiet.everyDay')
    expect(quietSummary({ start: '22:00', end: '07:00', days: ['SAT'] }, t)).toBe('22:00–07:00 · quiet.day.SAT')
  })
})
