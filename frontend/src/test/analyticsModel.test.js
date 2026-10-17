import { describe, it, expect } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'
import {
  DEFAULT_MONTHS, LINE_STYLES, MAX_FIELDS, MAX_LINES, MONTH_CHOICES, buildChart, channelCards, csvFileName, csvTable,
  fieldOptions, formatCell, isLonePoint, lineStyle, monthFullLabel, monthShortLabel, normalizeMonths, normalizeTeam,
  parseFieldsParam, requestParams, seriesOf, serializeFields, tableRows, toggleField, valueCount,
} from '../components/techerror/analytics/analyticsModel.js'

// Kanallar arası analiz modeli (2026-10-18, R22): boş ay null (grafikte boşluk, tabloda "—" — asla 0), en çok 8 çizgi,
// yalnız başına kalan değer nokta, PDF biçimi, CSV ham sayı, sunucu sınırlarıyla aynı sabitler.

const months = (n, endYear = 2026, endMonth = 9) => {
  const out = []
  for (let i = n - 1; i >= 0; i--) {
    const d = new Date(Date.UTC(endYear, endMonth - 1 - i, 1))
    out.push({ year: d.getUTCFullYear(), month: d.getUTCMonth() + 1 })
  }
  return out
}

function data(channelCount = 2, n = 12) {
  const channels = []
  for (let c = 0; c < channelCount; c++) {
    const tx = Array(n).fill(null)
    const rate = Array(n).fill(null)
    const reports = Array(n).fill(null)
    tx[n - 1] = 1_250_000 + c
    tx[n - 2] = 1_100_000
    rate[n - 1] = 0.42
    rate[3] = 0.5                                     // yalnız başına
    reports[n - 1] = { id: 100 + c, sent: c === 0 }
    reports[n - 2] = { id: 200 + c, sent: true }
    reports[3] = { id: 300 + c, sent: true }
    channels.push({ id: 10 + c, name: `Kanal ${c}`, team_id: 1, team_name: 'Dijital Kanallar', points: { 'total-tx': tx, 'error-rate': rate }, reports })
  }
  return {
    months: months(n),
    fields: [{ id: 'error-rate', label: 'Teknik hata oranı', type: 'percent' }, { id: 'total-tx', label: 'Toplam işlem adedi', type: 'number' }],
    available_fields: [
      { id: 'total-tx', label: 'Toplam işlem adedi', type: 'number', channels: channelCount },
      { id: 'error-rate', label: 'Teknik hata oranı', type: 'percent', channels: channelCount },
      { id: 'sla-rate', label: 'Kritik fonksiyonların SLA oranı', type: 'percent', channels: 1 },
    ],
    channels,
  }
}

describe('sabitler ve adres parametreleri', () => {
  it('sunucuyla aynı sınırlar (TechErrorAnalyticsService)', () => {
    const java = fs.readFileSync(path.resolve(__dirname, '../../../backend/src/main/java/com/sitemonitor/service/techerror/TechErrorAnalyticsService.java'), 'utf8')
    expect(java).toContain(`MONTH_CHOICES = List.of(${MONTH_CHOICES.join(', ')})`)
    expect(java).toContain(`DEFAULT_MONTHS = ${DEFAULT_MONTHS};`)
    expect(java).toContain(`MAX_FIELDS = ${MAX_FIELDS};`)
    expect(MAX_LINES).toBe(8)
    expect(LINE_STYLES).toHaveLength(8)
  })

  it('ay: yalnız 6 / 12 / 24, değilse 12; takım: kimlik ya da null; alanlar: geçerli, tekil, en çok 4', () => {
    expect(normalizeMonths('24')).toBe(24)
    expect(normalizeMonths(7)).toBe(12)
    expect(normalizeMonths('abc')).toBe(12)
    expect(normalizeTeam('15')).toBe('15')
    expect(normalizeTeam('all')).toBeNull()
    expect(normalizeTeam('0')).toBeNull()
    expect(normalizeTeam('-3')).toBeNull()
    expect(parseFieldsParam('')).toBeNull()
    expect(parseFieldsParam('error-rate, total-tx,error-rate,Bad Id,a,b,c')).toEqual(['error-rate', 'total-tx', 'a', 'b'])
    expect(parseFieldsParam(',,')).toBeNull()
    expect(serializeFields(['a', 'b'])).toBe('a,b')
    expect(serializeFields([])).toBeNull()
  })

  it('istek paramları: takım yalnız geçerliyse, ay her zaman, alanlar varsa', () => {
    expect(requestParams({ team: null, months: 12, fields: null })).toEqual({ months: 12 })
    expect(requestParams({ team: '5', months: '6', fields: ['error-rate', 'total-tx'] })).toEqual({ team: '5', months: 6, fields: 'error-rate,total-tx' })
    expect(requestParams({ team: 'x', months: 99, fields: ['Bad'] })).toEqual({ months: 12 })
  })

  it('alan seçimi: ekle / çıkar; son alan çıkarılamaz, sınır doluysa eklenmez (aynı dizi döner)', () => {
    const one = ['error-rate']
    expect(toggleField(one, 'error-rate')).toBe(one)
    expect(toggleField(one, 'total-tx')).toEqual(['error-rate', 'total-tx'])
    expect(toggleField(['a', 'b'], 'a')).toEqual(['b'])
    const full = ['a', 'b', 'c', 'd']
    expect(toggleField(full, 'e')).toBe(full)
    const opts = fieldOptions(data().available_fields, ['error-rate'])
    expect(opts.find((o) => o.id === 'error-rate')).toMatchObject({ checked: true, disabled: true })
    expect(opts.find((o) => o.id === 'total-tx')).toMatchObject({ checked: false, disabled: false })
    const fullOpts = fieldOptions([{ id: 'x' }, { id: 'a' }], full)
    expect(fullOpts.find((o) => o.id === 'x').disabled).toBe(true)
    expect(fullOpts.find((o) => o.id === 'a').disabled).toBe(false)
  })
})

describe('seriler, grafik ve tablo', () => {
  it('seri ay sayısına hizalanır; bozuk / eksik değer null (asla 0); değer sayısı', () => {
    expect(seriesOf({ points: { x: [1, null, 'abc', Infinity] } }, 'x', 5)).toEqual([1, null, null, null, null])
    expect(seriesOf({}, 'x', 2)).toEqual([null, null])
    expect(valueCount(data(2), 'error-rate')).toBe(4)
    expect(valueCount(data(2), 'sla-rate')).toBe(0)
  })

  it('grafik: aylar eskiden yeniye, güvenli anahtarlar c0…, boş ay null; en çok 8 çizgi, diğerleri gizli listede', () => {
    const d = data(10)
    const { rows, series, hidden } = buildChart(d, 'total-tx')
    expect(rows).toHaveLength(12)
    expect(rows[0]).toMatchObject({ year: 2025, month: 10 })
    expect(rows[11]).toMatchObject({ year: 2026, month: 9, c0: 1_250_000, c7: 1_250_007 })
    expect(rows[0].c0).toBeNull()
    expect(series).toHaveLength(8)
    expect(series.map((s) => s.key)).toEqual(['c0', 'c1', 'c2', 'c3', 'c4', 'c5', 'c6', 'c7'])
    expect(series[0]).toMatchObject({ channelId: 10, label: 'Kanal 0', team: 'Dijital Kanallar', dashed: false })
    expect(series[4].dashed).toBe(true)
    expect(series[0].color).toBe(series[4].color)
    expect(hidden.map((h) => h.name)).toEqual(['Kanal 8', 'Kanal 9'])
    // Durum rengi yok (yeşil / kırmızı / amber = chart-2/3/4)
    for (const s of LINE_STYLES) expect(s.color).not.toMatch(/chart-[234]/)
    expect(lineStyle(9)).toBe(LINE_STYLES[1])
  })

  it('yalnız başına kalan değer nokta alır; komşusu olan almaz', () => {
    const { rows } = buildChart(data(1), 'error-rate')
    expect(isLonePoint(rows, 'c0', 3)).toBe(true)
    expect(isLonePoint(rows, 'c0', 11)).toBe(true)        // önceki ay boş
    const tx = buildChart(data(1), 'total-tx').rows
    expect(isLonePoint(tx, 'c0', 11)).toBe(false)         // Ağustos dolu
    expect(isLonePoint(tx, 'c0', 0)).toBe(false)          // değer yok
  })

  it('tablo: en yeni ay önce; hücre PDF biçiminde, değer yoksa "—"; rapor kimliği + gönderildi', () => {
    const rows = tableRows(data(2), { id: 'error-rate', type: 'percent' }, 'tr')
    expect(rows[0]).toMatchObject({ year: 2026, month: 9 })
    expect(rows[0].cells[0]).toMatchObject({ channelId: 10, value: 0.42, text: '%0,42', report: { id: 100, sent: true } })
    expect(rows[0].cells[1].report).toEqual({ id: 101, sent: false })
    expect(rows[1].cells[0]).toMatchObject({ value: null, text: '—', report: { id: 200, sent: true } })
    expect(rows[2].cells[0]).toMatchObject({ value: null, text: '—', report: null })
    const en = tableRows(data(1), { id: 'total-tx', type: 'number' }, 'en')
    expect(en[0].cells[0].text).toBe('1,250,000')
    expect(formatCell(0, 'number', 'tr')).toBe('0')
    expect(formatCell(null, 'percent', 'tr')).toBe('—')
    expect(formatCell(0.004, 'percent', 'tr')).toBe('%0,004')
  })

  it('kart görünümü kanal başına ay listesi (tablo ile aynı hücreler)', () => {
    const cards = channelCards(data(2), { id: 'total-tx', type: 'number' }, 'tr')
    expect(cards).toHaveLength(2)
    expect(cards[0]).toMatchObject({ id: 10, name: 'Kanal 0', team_name: 'Dijital Kanallar' })
    expect(cards[0].items).toHaveLength(12)
    expect(cards[0].items[0]).toMatchObject({ year: 2026, month: 9, text: '1.250.000' })
  })

  it('CSV: başlık Ay + kanal (takım), satırlar tablo sırası, değer HAM sayı, boş değer boş hücre (0 değil)', () => {
    const { headers, rows } = csvTable(data(2), { id: 'total-tx', type: 'number' }, 'tr', 'Ay')
    expect(headers).toEqual(['Ay', 'Kanal 0 (Dijital Kanallar)', 'Kanal 1 (Dijital Kanallar)'])
    expect(rows[0]).toEqual(['Eylül 2026', 1_250_000, 1_250_001])
    expect(rows[2]).toEqual(['Temmuz 2026', '', ''])
    expect(csvFileName({ id: 'total-tx' }, data().months)).toBe('te-analiz-total-tx-2025-10_2026-09.csv')
  })

  it('ay etiketleri arayüz dilinde', () => {
    expect(monthShortLabel({ year: 2026, month: 9 }, 'tr')).toBe('Eyl 2026')
    expect(monthShortLabel({ year: 2026, month: 9 }, 'en')).toBe('Sep 2026')
    expect(monthFullLabel({ year: 2026, month: 2 }, 'tr')).toBe('Şubat 2026')
    expect(monthFullLabel(null, 'tr')).toBe('')
  })
})
