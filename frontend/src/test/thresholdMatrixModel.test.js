import { describe, it, expect } from 'vitest'
import { buildThresholdMatrix } from '../components/admin/thresholds/thresholdMatrixModel.js'

describe('thresholdMatrixModel — buildThresholdMatrix', () => {
  it('varsayılan + 4 tier; override alanları, devralma, etki, sınıflandırılmamış', () => {
    const rows = [
      { id: 1, tier: null, critical_days: 7, high_days: 15, warning_days: 30, re_alert_interval_hours: 24 },
      { id: 2, tier: 1, critical_days: 10, high_days: 20, warning_days: 45, re_alert_interval_hours: 12, active: true }, // hepsi farklı
      { id: 3, tier: 3, critical_days: 7, high_days: 15, warning_days: 30, re_alert_interval_hours: 24, active: true },  // varsayılanla aynı
    ]
    const impact = { default: { scope_total: 40 }, 1: { scope_total: 12 }, 2: { scope_total: 8 }, 3: { scope_total: 5 }, 4: { scope_total: 6 } }
    const { rows: m, unclassified } = buildThresholdMatrix(rows, impact)
    expect(m.map(r => r.tier)).toEqual([null, 1, 2, 3, 4])

    const t1 = m.find(r => r.tier === 1)
    expect(t1).toMatchObject({ inherited: false, impact: 12 })
    expect(t1.override).toEqual({ critical: true, high: true, warning: true, reAlert: true })

    const t2 = m.find(r => r.tier === 2)   // kendi satırı yok → devralır
    expect(t2).toMatchObject({ inherited: true, days: { critical: 7, high: 15, warning: 30 }, impact: 8 })
    expect(t2.override.critical).toBe(false)

    const t3 = m.find(r => r.tier === 3)   // kendi satırı var ama varsayılanla aynı → override yok
    expect(t3.inherited).toBe(false)
    expect(t3.override).toEqual({ critical: false, high: false, warning: false, reAlert: false })

    // Sınıflandırılmamış = varsayılan(40) − kendi satırı OLMAYAN tier'lar (2,4) = 40 − (8+6) = 26
    expect(unclassified).toBe(26)
  })

  it('kayıt yoksa yerleşik varsayılan; etki null; sınıflandırılmamış null', () => {
    const { rows: m, unclassified } = buildThresholdMatrix([], {})
    expect(m[0]).toMatchObject({ tier: null, builtin: true, days: { critical: 7, high: 15, warning: 30 }, reAlert: 24 })
    expect(m.every(r => r.impact === null)).toBe(true)
    expect(unclassified).toBeNull()
  })

  it('pasif tier satırı yok sayılır → devralır', () => {
    const t1 = buildThresholdMatrix([{ id: 2, tier: 1, critical_days: 1, high_days: 2, warning_days: 3, active: false }], {})
      .rows.find(r => r.tier === 1)
    expect(t1.inherited).toBe(true)
  })

  it('etki kısmen bilinmiyorsa sınıflandırılmamış hesaplanmaz (null)', () => {
    const rows = [{ id: 1, tier: null, critical_days: 7, high_days: 15, warning_days: 30 }]
    const { unclassified } = buildThresholdMatrix(rows, { default: { scope_total: 40 }, 1: { scope_total: 5 } })  // 2,3,4 yok
    expect(unclassified).toBeNull()
  })
})
