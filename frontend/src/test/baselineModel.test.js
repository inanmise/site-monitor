import { describe, it, expect } from 'vitest'
import {
  BASELINE_DEFAULTS, DIRECTIONS, SEASONS, formFromConfig, isDirty, rangeOf, readiness, toBody, validate,
} from '../components/baseline/baselineModel.js'

describe('baselineModel', () => {
  it('öntanımlılar: yukarı yön, k 2,5/3,5, pencere 30, min 10, mevsimsiz, kapalı', () => {
    expect(BASELINE_DEFAULTS).toMatchObject({ enabled: false, direction: 'up', k_warning: 2.5, k_critical: 3.5, window: 30, min_samples: 10, season: 'none' })
    expect(DIRECTIONS).toEqual(['up', 'down', 'both'])
    expect(SEASONS).toEqual(['none', 'daily', 'weekly'])
  })

  it('formFromConfig: null → öntanımlı; kısmi → doldurur; geçersiz → öntanımlı', () => {
    expect(formFromConfig(null)).toEqual(BASELINE_DEFAULTS)
    const f = formFromConfig({ enabled: true, direction: 'both', k_warning: 3, window: 50, season: 'daily' })
    expect(f).toMatchObject({ enabled: true, direction: 'both', k_warning: 3, window: 50, season: 'daily', k_critical: 3.5, min_samples: 10 })
    expect(formFromConfig({ enabled: true, direction: 'bogus', season: 'yearly' })).toMatchObject({ direction: 'up', season: 'none' })
  })

  it('toBody: sayıya çevirir', () => {
    expect(toBody({ enabled: true, direction: 'up', k_warning: '2.5', k_critical: '3.5', window: '30', min_samples: '10', season: 'none' }))
      .toEqual({ enabled: true, direction: 'up', k_warning: 2.5, k_critical: 3.5, window: 30, min_samples: 10, season: 'none' })
  })

  it('validate: kapalıyken parametre denetlenmez', () => {
    expect(validate({ enabled: false, k_warning: 0.1, window: 5 })).toEqual({})
  })

  it('validate: sınır dışı k ve pencere → range', () => {
    const e = validate({ ...BASELINE_DEFAULTS, enabled: true, k_warning: 0.1, window: 5 })
    expect(e.k_warning).toBe('range')
    expect(e.window).toBe('range')
  })

  it('validate: kritik < uyarı → order', () => {
    expect(validate({ ...BASELINE_DEFAULTS, enabled: true, k_warning: 4, k_critical: 2 }).k_critical).toBe('order')
  })

  it('validate: min örnek pencereyi aşamaz', () => {
    expect(validate({ ...BASELINE_DEFAULTS, enabled: true, window: 12, min_samples: 50 }).min_samples).toBe('range')
  })

  it('validate: geçersiz yön / mevsim → invalid', () => {
    const e = validate({ ...BASELINE_DEFAULTS, enabled: true, direction: 'x', season: 'y' })
    expect(e.direction).toBe('invalid')
    expect(e.season).toBe('invalid')
  })

  it('readiness: kapalı / kuruluyor / hazır', () => {
    expect(readiness({ enabled: false }, 100)).toMatchObject({ state: 'off', tone: 'muted' })
    expect(readiness({ enabled: true, min_samples: 10 }, 4)).toMatchObject({ state: 'building', tone: 'info', n: 4, min: 10 })
    expect(readiness({ enabled: true, min_samples: 10 }, 30)).toMatchObject({ state: 'ready', tone: 'ok', n: 30 })
  })

  it('rangeOf: alan aralıkları', () => {
    expect(rangeOf('k_warning')).toEqual([1, 10])
    expect(rangeOf('window')).toEqual([10, 200])
    expect(rangeOf('min_samples', { window: 40 })).toEqual([5, 40])
  })

  it('isDirty: eşit → false, değişiklik → true', () => {
    const cfg = { enabled: true, direction: 'up', k_warning: 2.5, k_critical: 3.5, window: 30, min_samples: 10, season: 'none' }
    expect(isDirty(formFromConfig(cfg), cfg)).toBe(false)
    expect(isDirty({ ...formFromConfig(cfg), window: 60 }, cfg)).toBe(true)
  })
})
