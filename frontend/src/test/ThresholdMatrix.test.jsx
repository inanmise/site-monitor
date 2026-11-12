import { describe, it, expect } from 'vitest'
import { render } from '@testing-library/react'
import { LangProvider } from '../i18n/index.jsx'
import ThresholdMatrix from '../components/admin/thresholds/ThresholdMatrix.jsx'

/**
 * Eşik karşılaştırma matrisi (2026-11-12, #3): Varsayılan + Tier 1..4 yan yana; kendi eşiği olan tier'ın farklı alanları
 * vurgulu (data-override), eşiği olmayan tier devralır (data-inherited). jsdom genişlik 0 → tablo düzeni.
 */
const ROWS = [
  { id: 1, tier: null, critical_days: 7, high_days: 15, warning_days: 30, re_alert_interval_hours: 24 },
  { id: 2, tier: 1, critical_days: 10, high_days: 20, warning_days: 45, re_alert_interval_hours: 12, active: true },
]
const IMPACT = { default: { scope_total: 40 }, 1: { scope_total: 12 }, 2: { scope_total: 8 }, 3: { scope_total: 5 }, 4: { scope_total: 6 } }

describe('ThresholdMatrix', () => {
  it('5 satır; tier1 override hücreleri vurgulu; tier2 devralır; sınıflandırılmamış notu', () => {
    const { container } = render(<LangProvider><ThresholdMatrix rows={ROWS} impact={IMPACT} /></LangProvider>)
    expect(container.querySelector('[data-slot="threshold-matrix"]')).toBeTruthy()
    const rows = container.querySelectorAll('[data-slot="tm-row"]')
    expect(rows).toHaveLength(5)

    const t1 = container.querySelector('[data-slot="tm-row"][data-tier="1"]')
    expect(t1.querySelectorAll('[data-slot="tm-cell"][data-override="true"]')).toHaveLength(3)   // kritik+yüksek+uyarı farklı
    expect(t1.getAttribute('data-inherited')).toBeNull()

    const t2 = container.querySelector('[data-slot="tm-row"][data-tier="2"]')
    expect(t2.getAttribute('data-inherited')).toBe('true')
    expect(t2.querySelectorAll('[data-slot="tm-cell"][data-override="true"]')).toHaveLength(0)

    // Sınıflandırılmamış = 40 − (tier2:8 + tier3:5 + tier4:6) = 21 (tier1 kendi satırı var)
    expect(container.querySelector('[data-slot="tm-unclassified"]')).toBeTruthy()
  })

  it('kayıt yoksa yerleşik varsayılan satırı; sınıflandırılmamış notu yok', () => {
    const { container } = render(<LangProvider><ThresholdMatrix rows={[]} impact={{}} /></LangProvider>)
    expect(container.querySelectorAll('[data-slot="tm-row"]')).toHaveLength(5)
    expect(container.querySelector('[data-slot="tm-unclassified"]')).toBeNull()
  })
})
