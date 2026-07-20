import { describe, it, expect } from 'vitest'
import { render } from './test-utils.jsx'
import WeeklySummaryBrief from '../components/WeeklySummaryBrief.jsx'

const t = (key, ...a) => {
  const m = {
    'wr.sumScore': 'Skor', 'wr.sumActions': 'Aksiyonlar', 'wr.sumLookahead': 'Önümüzdeki 30 Gün',
    'wr.sumNoRecords': 'Kayıt yok', 'wr.sumTypeCert': 'Sertifika', 'wr.sumTypeDomain': 'Domain',
    'wr.sumDaysLeft': `${a[0]} gün`, 'wr.sumHasAlarm': 'Açık alarm',
  }
  return m[key] ?? key
}

// Backend snake_case (SNAKE_CASE) — bileşen bu formatı okumalı.
const summary = {
  manager_text: { tr: 'TR paragraf metni.', en: 'EN paragraph text.' },
  score: { value: 74, delta: 6, band: 'amber' },
  actions: [
    { name: 't1a.com', type: 'cert', tier: 1, days_left: 10, has_open_alarm: true },
    { name: 'reg.com', type: 'domain', tier: 2, days_left: 5, has_open_alarm: false },
  ],
  lookahead: [],
}

describe('WeeklySummaryBrief', () => {
  it('summary yoksa hiçbir şey render etmez (eski rapor gizli)', () => {
    const { container } = render(<WeeklySummaryBrief kpis={{ summary: null }} t={t} lang="tr" />)
    expect(container.querySelector('.wr-sum')).toBeNull()
  })

  it('paragraf + skor + dairesel gösterge + aksiyon satırları (tier şeridi/tip/gün/alarm)', () => {
    const { container, getByText } = render(<WeeklySummaryBrief kpis={{ summary }} t={t} lang="tr" />)
    expect(getByText('TR paragraf metni.')).toBeTruthy()
    expect(getByText('74')).toBeTruthy()
    expect(container.querySelector('svg.wr-gauge')).toBeTruthy()
    expect(container.querySelectorAll('.wr-sum-row').length).toBe(2)
    expect(container.querySelector('.wr-sum-tier1')).toBeTruthy()
    expect(getByText('Sertifika')).toBeTruthy()
    expect(getByText('10 gün')).toBeTruthy()
    expect(container.querySelector('.wr-sum-alarm')).toBeTruthy()      // açık-alarm işareti
    expect(container.querySelector('.wr-sum-delta--up')).toBeTruthy()  // skor arttı → yeşil ok
  })

  it('EN dilinde EN paragrafı seçilir', () => {
    const { getByText } = render(<WeeklySummaryBrief kpis={{ summary }} t={t} lang="en" />)
    expect(getByText('EN paragraph text.')).toBeTruthy()
  })

  it('lookahead boş → "Önümüzdeki 30 Gün" kolonu hiç render edilmez (Kayıt yok göstermez)', () => {
    const { queryByText, container } = render(<WeeklySummaryBrief kpis={{ summary }} t={t} lang="tr" />)
    expect(container.querySelectorAll('.wr-sum-col').length).toBe(1)   // yalnız Aksiyonlar kolonu
    expect(queryByText('Kayıt yok')).toBeNull()                        // lookahead gizli, actions dolu → hiç "Kayıt yok" yok
    expect(queryByText('Önümüzdeki 30 Gün')).toBeNull()                // lookahead başlığı da yok
  })

  it('lookahead dolu → "Önümüzdeki 30 Gün" kolonu + satırlar render edilir', () => {
    const withLook = { ...summary, lookahead: [{ name: 'soon.com', type: 'cert', tier: 3, days_left: 20, has_open_alarm: false }] }
    const { container, getByText } = render(<WeeklySummaryBrief kpis={{ summary: withLook }} t={t} lang="tr" />)
    expect(container.querySelectorAll('.wr-sum-col').length).toBe(2)   // Aksiyonlar + Önümüzdeki 30 Gün
    expect(getByText('soon.com')).toBeTruthy()
    expect(getByText('Önümüzdeki 30 Gün')).toBeTruthy()                // lookahead başlığı
  })

  it('band → skor rengi (amber token)', () => {
    const { container } = render(<WeeklySummaryBrief kpis={{ summary }} t={t} lang="tr" />)
    expect(container.querySelector('.wr-sum-score-num').getAttribute('style')).toContain('severity-warn')
  })
})
