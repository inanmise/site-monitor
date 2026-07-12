import { describe, it, expect } from 'vitest'
import { render } from './test-utils.jsx'
import WeeklyKpiStrip from '../components/WeeklyKpiStrip.jsx'

// Basit t: KPI etiketleri + {0} interpolasyonu (parity gerçek i18n'de test edilir).
const t = (key, ...a) => {
  const m = {
    'wr.kpiTotalCerts': 'Toplam Sertifika', 'wr.kpiExpiring': 'Bu Hafta Dolan',
    'wr.kpiRenewedSub': `yenilenen: ${a[0]}`, 'wr.kpiAlarms': 'Bu Hafta Açılan Alarm',
    'wr.kpiCritical': 'Kritik', 'wr.kpiCriticalDomainsSub': `domain: ${a[0]}`,
    'wr.kpiUptime': 'Uptime', 'wr.kpiPt': 'puan',
  }
  return m[key] ?? key
}

// Backend snake_case (spring.jackson SNAKE_CASE) — bileşen bu formatı okumalı.
const kpis = {
  current: { total_certs: 10, expiring_in_window: 5, renewed_in_window: 2, alarms_opened: 4, critical_certs: 3, critical_domains: 1, uptime_pct: 99.5, week_label: '28. Hafta' },
  previous: { total_certs: 10, expiring_in_window: 3, renewed_in_window: 1, alarms_opened: 6, critical_certs: 3, critical_domains: 1, uptime_pct: 99.0, week_label: '27. Hafta' },
  trend8w: Array.from({ length: 8 }, (_, i) => ({ year: 2026, week: 21 + i, week_label: `${21 + i}`, alarms_opened: i, expiring: 8 - i })),
}

describe('WeeklyKpiStrip', () => {
  it('5 kart + değerler; windowed kartlarda delta + sparkline', () => {
    const { container, getByText } = render(<WeeklyKpiStrip kpis={kpis} loading={false} t={t} />)
    // Kartlar + değerler
    expect(getByText('Toplam Sertifika')).toBeTruthy()
    expect(getByText('10')).toBeTruthy()
    expect(getByText('99.50%')).toBeTruthy()
    // Sparkline: expiring + alarms kartlarında (svg.spark)
    expect(container.querySelectorAll('svg.spark').length).toBeGreaterThanOrEqual(2)
    // Delta polaritesi: expiring artışı KÖTÜ (5>3 → bad), uptime artışı İYİ (99.5>99.0 → good)
    expect(container.querySelector('.wr-kpi-delta--bad')).toBeTruthy()
    expect(container.querySelector('.wr-kpi-delta--good')).toBeTruthy()
  })

  it('önceki hafta yoksa windowed kartlar delta göstermez (snapshot placeholder yok)', () => {
    const noPrev = { current: kpis.current, previous: null, trend8w: [] }
    const { container } = render(<WeeklyKpiStrip kpis={noPrev} loading={false} t={t} />)
    expect(container.querySelector('.wr-kpi-delta')).toBeNull()
  })

  it('snapshot kartlar (toplam cert, kritik) hiç delta taşımaz', () => {
    // totalCerts current==previous → zaten delta yok; kritik kartın delta prop\'u tanımsız.
    const { container, getByText } = render(<WeeklyKpiStrip kpis={kpis} loading={false} t={t} />)
    expect(getByText('domain: 1')).toBeTruthy()
    // İki delta tag olmalı: expiring(bad) + alarms(good, 4<6 düşüş=iyi) + uptime(good) → toplam 3
    expect(container.querySelectorAll('.wr-kpi-delta').length).toBe(3)
  })

  it('yükleniyor + veri yok → loading göstergesi; kpis null → hiçbir şey', () => {
    const { container, rerender } = render(<WeeklyKpiStrip kpis={null} loading={true} t={t} />)
    expect(container.querySelector('.wr-kpi-strip--loading')).toBeTruthy()
    rerender(<WeeklyKpiStrip kpis={null} loading={false} t={t} />)
    expect(container.querySelector('.wr-kpi-strip')).toBeNull()
  })
})
