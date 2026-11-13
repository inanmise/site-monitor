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

  it('readiness: kapalı / kuruluyor / hazır — kaynak sunucunun değerlendirme penceresi', () => {
    const saved = { enabled: true, direction: 'up', k_warning: 2.5, k_critical: 3.5, window: 30, min_samples: 10, season: 'none' }
    const form = formFromConfig(saved)
    expect(readiness({ enabled: false }, saved)).toMatchObject({ state: 'off', tone: 'muted' })
    expect(readiness(form, { ...saved, global_enabled: true, ready_state: 'building', ready_samples: 4, ready_min: 10 }))
      .toMatchObject({ state: 'building', tone: 'info', n: 4, min: 10 })
    expect(readiness(form, { ...saved, global_enabled: true, ready_state: 'ready', ready_samples: 30, ready_min: 10 }))
      .toMatchObject({ state: 'ready', tone: 'ok', n: 30 })
  })

  it('readiness: bilinmeyen ASLA yeşil değil (hata taraması #8)', () => {
    const saved = { enabled: true, direction: 'up', k_warning: 2.5, k_critical: 3.5, window: 30, min_samples: 10, season: 'daily' }
    const form = formFromConfig(saved)
    // Ana anahtar kapalı → nötr (toplam örnek çok olsa da)
    expect(readiness(form, { ...saved, global_enabled: false, sample_count: 900, ready_state: 'ready', ready_samples: 30 }))
      .toMatchObject({ state: 'switchOff', tone: 'muted' })
    // Mevsimselde eşleşen örnek yetersiz → nötr (toplam 900 olsa da)
    expect(readiness(form, { ...saved, global_enabled: true, sample_count: 900, ready_state: 'insufficient', ready_samples: 3, ready_min: 10 }))
      .toMatchObject({ state: 'insufficient', tone: 'muted', n: 3, min: 10 })
    // Eski sunucu (ready_state / global_enabled yok): toplam ≥ min → doğrulanamaz (nötr), yeşil değil
    expect(readiness(form, { ...saved, sample_count: 900 })).toMatchObject({ state: 'unverified', tone: 'muted', n: 900 })
    expect(readiness(form, { ...saved, sample_count: 4 })).toMatchObject({ state: 'building', tone: 'info', n: 4, min: 10 })
    // Kayıtlı değil (yeni açılıyor) → bilgi; pencere / min / mevsim değişti → yeniden hesaplanacak
    expect(readiness(form, { ...saved, enabled: false, global_enabled: true })).toMatchObject({ state: 'notSaved', tone: 'info' })
    expect(readiness({ ...form, window: 60 }, { ...saved, global_enabled: true, ready_state: 'ready', ready_samples: 30 }))
      .toMatchObject({ state: 'pending', tone: 'info' })
    // k / yön değişimi hazırlığı etkilemez
    expect(readiness({ ...form, k_warning: 3 }, { ...saved, global_enabled: true, ready_state: 'ready', ready_samples: 30 }))
      .toMatchObject({ state: 'ready' })
    // Tanınmayan durum → nötr
    expect(readiness(form, { ...saved, global_enabled: true, ready_state: 'weird' })).toMatchObject({ state: 'unknown', tone: 'muted' })
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
