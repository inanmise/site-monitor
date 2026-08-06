import { describe, it, expect } from 'vitest'
import { deriveGlobalStatus } from '../utils/brandStatus.js'

/** BRAND.md §3 öncelik: critical > warning > ok; muted yalnız veri yokluğunda. */
describe('deriveGlobalStatus', () => {
  it('stats yok (undefined/null) → muted (veri bekleniyor)', () => {
    expect(deriveGlobalStatus(undefined)).toBe('muted')
    expect(deriveGlobalStatus(null, true)).toBe('muted')
  })

  it('tüm sayaçlar 0 → ok', () => {
    expect(deriveGlobalStatus({ critical_count: 0, high_count: 0, warning_count: 0, error_count: 0, expired: 0 })).toBe('ok')
    expect(deriveGlobalStatus({})).toBe('ok')   // eksik alanlar 0 sayılır
  })

  it('yalnız warning/high → warning', () => {
    expect(deriveGlobalStatus({ warning_count: 3 })).toBe('warning')
    expect(deriveGlobalStatus({ high_count: 1 })).toBe('warning')
  })

  it('critical/error/expired herhangi biri > 0 → critical (öncelik warning üstünde)', () => {
    expect(deriveGlobalStatus({ critical_count: 1, warning_count: 5 })).toBe('critical')
    expect(deriveGlobalStatus({ error_count: 2 })).toBe('critical')
    expect(deriveGlobalStatus({ expired: 1 })).toBe('critical')
  })

  it('ağ kesinti alarmı (networkAlarm) → sayaçlardan bağımsız critical', () => {
    expect(deriveGlobalStatus({ warning_count: 1 }, true)).toBe('critical')
    expect(deriveGlobalStatus({}, true)).toBe('critical')
  })

  it('sayı olmayan değerler 0 sayılır (savunmacı)', () => {
    expect(deriveGlobalStatus({ critical_count: null, warning_count: 'x' })).toBe('ok')
  })
})
