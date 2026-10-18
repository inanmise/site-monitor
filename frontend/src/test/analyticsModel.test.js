import { describe, it, expect } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'
import {
  ANALYTICS_KEYS, DEFAULT_MONTHS, LINE_STYLES, MAX_FIELDS, MAX_LINES, MONTH_CHOICES, adoptedFields, buildChart, channelCards,
  csvFileName, csvTable, fieldOptions, fieldUnitLabel, filterKey, formatCell, isDefaultFilter, isInactiveChannel, isLonePoint,
  lineStyle, monthFullLabel, monthShortLabel, niceStep, normalizeMonths, normalizeTeam, parseFieldsParam, reportAt, requestParams,
  seriesOf, serializeFields, tableRows, toggleField, typeMismatchIds, valueCount, yAxisScale,
} from '../components/techerror/analytics/analyticsModel.js'
import { toCsv } from '../utils/csvExport.js'

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

  it('seçici SEÇİLİ her alanı listeler: yalnız varsayılan şablondaki alan (kanallarda yok) ve adresteki bilinmeyen kimlik kaldırılabilir', () => {
    const shown = [{ id: 'quota-rate', label: 'Kota hatalarının teknik hatalara oranı', type: 'percent' }]
    const opts = fieldOptions(data().available_fields, ['error-rate', 'quota-rate', 'eski-alan'], MAX_FIELDS, shown)
    const quota = opts.find((o) => o.id === 'quota-rate')
    expect(quota).toMatchObject({ label: 'Kota hatalarının teknik hatalara oranı', type: 'percent', channels: 0, checked: true, disabled: false })
    expect(opts.find((o) => o.id === 'eski-alan')).toMatchObject({ label: 'eski-alan', type: null, channels: null, checked: true })
    expect(toggleField(['error-rate', 'quota-rate', 'eski-alan'], 'quota-rate')).toEqual(['error-rate', 'eski-alan'])
    // Listede olanlar tekrarlanmaz
    expect(opts.filter((o) => o.id === 'error-rate')).toHaveLength(1)
  })

  it('süzgeç anahtarı / varsayılan mı / adres anahtarları', () => {
    expect(filterKey({ team: '1', months: 24, fields: ['a'] })).toBe(JSON.stringify({ team: '1', months: 24, fields: 'a' }))
    expect(filterKey({ team: null, months: 12, fields: null })).toBe(filterKey({}))
    expect(filterKey({ team: '1', months: 12, fields: null })).not.toBe(filterKey({ team: '2', months: 12, fields: null }))
    expect(isDefaultFilter({ team: null, months: 12, fields: null })).toBe(true)
    expect(isDefaultFilter({ team: '1', months: 12, fields: null })).toBe(false)
    expect(isDefaultFilter({ team: null, months: 6, fields: null })).toBe(false)
    expect(isDefaultFilter({ team: null, months: 12, fields: ['total-tx'] })).toBe(false)
    expect(ANALYTICS_KEYS).toEqual(['te_an_team', 'te_an_months', 'te_an_fields'])
  })

  it('sunucunun alan çözümü benimsenir: hiçbiri yoksa varsayılan (null), bir kısmı yoksa kalanlar, değişmiyorsa undefined', () => {
    expect(adoptedFields({ fields_fallback: true, dropped_fields: ['x'] }, ['x'])).toBeNull()
    expect(adoptedFields({ fields_fallback: false, dropped_fields: ['x'] }, ['x', 'total-tx'])).toEqual(['total-tx'])
    expect(adoptedFields({ fields_fallback: false, dropped_fields: [] }, ['total-tx'])).toBeUndefined()
    expect(adoptedFields({ fields_fallback: true }, null)).toBeUndefined()                // istek zaten varsayılan
    expect(adoptedFields({}, ['total-tx'])).toBeUndefined()                               // eski sunucu (alan yok)
  })
})

describe('Y ekseni ölçeği (veriye göre)', () => {
  const rows = (vals) => vals.map((v, i) => ({ key: `k${i}`, c0: v }))

  it('güzel adım 1 / 2 / 5 × 10ⁿ', () => {
    expect(niceStep(0.05)).toBeCloseTo(0.05)
    expect(niceStep(0.06)).toBeCloseTo(0.1)
    expect(niceStep(3)).toBe(5)
    expect(niceStep(1.5)).toBe(2)
    expect(niceStep(50_000)).toBe(50_000)
    expect(niceStep(0)).toBe(1)
  })

  it('SLA gibi dar yüzde aralığı (%99,80–%99,95): eksen 0\'dan değil, verinin çevresinden; 100\'ü aşmaz', () => {
    const s = yAxisScale(rows([99.8, 99.85, null, 99.95]), ['c0'], 'percent', 'tr')
    expect(s.domain[0]).toBeGreaterThan(99)
    expect(s.domain[0]).toBeLessThanOrEqual(99.8)
    expect(s.domain[1]).toBeGreaterThanOrEqual(99.95)
    expect(s.domain[1]).toBeLessThanOrEqual(100)
    expect(s.ticks[0]).toBe(s.domain[0])
    expect(s.ticks.at(-1)).toBe(s.domain[1])
    expect(s.ticks.length).toBeGreaterThanOrEqual(3)
    expect(s.ticks.length).toBeLessThanOrEqual(8)
    expect(s.labels).toHaveLength(s.ticks.length)
    expect(new Set(s.labels).size).toBe(s.labels.length)
    expect(s.labels[0]).toMatch(/^%99/)
  })

  it('adet ekseni: negatif değer yoksa 0\'ın altına inmez, adım tam sayı; kısa etiketler çakışırsa tam biçim', () => {
    const near = yAxisScale(rows([3, 5, 4]), ['c0'], 'number', 'tr')
    expect(near.domain[0]).toBeGreaterThanOrEqual(0)
    for (const v of near.ticks) expect(Number.isInteger(v)).toBe(true)
    const big = yAxisScale(rows([1_100_000, 1_250_000]), ['c0'], 'number', 'tr')
    expect(big.domain[0]).toBeGreaterThan(0)
    expect(big.domain[0]).toBeLessThanOrEqual(1_100_000)
    expect(big.domain[1]).toBeGreaterThanOrEqual(1_250_000)
    expect(new Set(big.labels).size).toBe(big.labels.length)            // "1,1Mn" iki kez yazılmaz
    const zero = yAxisScale(rows([0, 0]), ['c0'], 'number', 'tr')
    expect(zero.domain[0]).toBe(0)
    expect(zero.domain[1]).toBeGreaterThan(0)
  })

  it('negatif değer varsa eksen 0\'ın altına iner; tek değer çevresinde pencere; değer yoksa null', () => {
    const neg = yAxisScale(rows([-2, 3]), ['c0'], 'percent', 'tr')
    expect(neg.domain[0]).toBeLessThanOrEqual(-2)
    expect(neg.domain[1]).toBeGreaterThanOrEqual(3)
    const flat = yAxisScale(rows([99.9, 99.9]), ['c0'], 'percent', 'tr')
    expect(flat.domain[0]).toBeLessThan(99.9)
    expect(flat.domain[1]).toBeGreaterThanOrEqual(99.9)
    expect(flat.domain[1]).toBeLessThanOrEqual(100)
    expect(yAxisScale(rows([null, null]), ['c0'], 'percent', 'tr')).toBeNull()
    // Birden çok seri: hepsinin en küçük / en büyüğü
    const two = yAxisScale([{ c0: 10, c1: 400 }, { c0: 20, c1: null }], ['c0', 'c1'], 'number', 'tr')
    expect(two.domain[0]).toBeLessThanOrEqual(10)
    expect(two.domain[1]).toBeGreaterThanOrEqual(400)
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
    expect(rows[0].cells[1].report).toEqual({ id: 101, sent: false, queued: false })
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

  it('CSV: başlık Ay · alan (birim) + kanal (takım), satırlar tablo sırası, değer HAM sayı, boş değer boş hücre (0 değil)', () => {
    const { headers, rows } = csvTable(data(2), { id: 'total-tx', label: 'Toplam işlem adedi', type: 'number' }, 'tr', 'Ay')
    expect(headers).toEqual(['Ay · Toplam işlem adedi', 'Kanal 0 (Dijital Kanallar)', 'Kanal 1 (Dijital Kanallar)'])
    const lines = toCsv(headers, rows).slice(1).split('\r\n')
    expect(lines[1]).toBe('Eylül 2026,1250000,1250001')
    expect(lines[3]).toBe('Temmuz 2026,,')
    expect(csvFileName({ id: 'total-tx' }, data().months)).toBe('te-analiz-total-tx-2025-10_2026-09.csv')
  })

  it('CSV: negatif yüzde SAYI kalır (formül koruması metne çevirmez); yüzde başlığında birim', () => {
    const d = data(1)
    d.channels[0].points['error-rate'][11] = -0.5
    const field = { id: 'error-rate', label: 'Teknik hata oranı', type: 'percent' }
    expect(fieldUnitLabel(field)).toBe('Teknik hata oranı (%)')
    const { headers, rows } = csvTable(d, field, 'tr', 'Ay')
    expect(headers[0]).toBe('Ay · Teknik hata oranı (%)')
    const lines = toCsv(headers, rows).slice(1).split('\r\n')
    expect(lines[1]).toBe('Eylül 2026,-0.5')
    expect(lines[1]).not.toContain("'-0")
    // Kanal adı gibi METİN hâlâ formül korumasından geçer
    d.channels[0].name = '=cmd'
    expect(toCsv(...Object.values(csvTable(d, field, 'tr', 'Ay'))).split('\r\n')[0]).toContain("'=cmd")
  })

  it('kuyruktaki rapor: queued (gönderilmedi değil); gönderilmiş raporda queued yok sayılır', () => {
    expect(reportAt({ reports: [{ id: 1, sent: false, queued: true }] }, 0)).toEqual({ id: 1, sent: false, queued: true })
    expect(reportAt({ reports: [{ id: 2, sent: true, queued: true }] }, 0)).toEqual({ id: 2, sent: true, queued: false })
    expect(reportAt({ reports: [{ id: 3, sent: false }] }, 0)).toEqual({ id: 3, sent: false, queued: false })
    expect(reportAt({ reports: [null] }, 0)).toBeNull()
  })

  it('alan türü farkı: yanıttaki type_mismatch kanalları grafikte, gizli listede ve kartlarda işaretli', () => {
    const d = data(10)
    d.fields[1].type_mismatch = [11, 19]
    expect([...typeMismatchIds(d, 'total-tx')]).toEqual(['11', '19'])
    expect(typeMismatchIds(d, 'error-rate').size).toBe(0)
    const chart = buildChart(d, 'total-tx')
    expect(chart.series.find((s) => s.channelId === 11).mismatch).toBe(true)
    expect(chart.series.find((s) => s.channelId === 10).mismatch).toBe(false)
    expect(chart.hidden.find((h) => h.id === 19).mismatch).toBe(true)
    const cards = channelCards(d, { id: 'total-tx', type: 'number' }, 'tr')
    expect(cards.find((c) => c.id === 11).mismatch).toBe(true)
    expect(cards.find((c) => c.id === 12).mismatch).toBe(false)
  })

  it('pasif kanal (2026-10-18): yalnız active === false pasif — grafikte (gizli listede de), kartlarda işaretli, CSV başlığına etiket; eski sunucu (alan yok) aktif', () => {
    const d = data(10)
    d.channels[8].active = false                                       // sunucu pasifleri aktiflerin ARDINDAN gönderir
    d.channels[9].active = false
    d.channels[0].active = true
    expect(isInactiveChannel(d.channels[8])).toBe(true)
    expect(isInactiveChannel(d.channels[0])).toBe(false)
    expect(isInactiveChannel(d.channels[1])).toBe(false)               // alan yok → aktif (eski sunucu)
    expect(isInactiveChannel({ active: null })).toBe(false)
    expect(isInactiveChannel(null)).toBe(false)
    const chart = buildChart(d, 'total-tx')
    expect(chart.series.map((s) => s.inactive)).toEqual(Array(8).fill(false))
    expect(chart.hidden.map((h) => [h.id, h.inactive])).toEqual([[18, true], [19, true]])   // sıra korunur
    const cards = channelCards(d, { id: 'total-tx', type: 'number' }, 'tr')
    expect(cards.map((c) => c.inactive)).toEqual([...Array(8).fill(false), true, true])
    const field = { id: 'total-tx', label: 'Toplam işlem adedi', type: 'number' }
    const { headers } = csvTable(d, field, 'tr', 'Ay', '(pasif)')
    expect(headers[1]).toBe('Kanal 0 (Dijital Kanallar)')
    expect(headers[9]).toBe('Kanal 8 (Dijital Kanallar) (pasif)')
    expect(headers[10]).toBe('Kanal 9 (Dijital Kanallar) (pasif)')
    // Takımsız kanal ve etiket verilmeyen çağrı
    d.channels[9].team_name = null
    expect(csvTable(d, field, 'en', 'Month', '(inactive)').headers[10]).toBe('Kanal 9 (inactive)')
    expect(csvTable(d, field, 'tr', 'Ay').headers[10]).toBe('Kanal 9')
    // Eski sunucu: hiçbir kanalda alan yok → hiç işaret yok
    const old = data(3)
    expect(buildChart(old, 'total-tx').series.some((s) => s.inactive)).toBe(false)
    expect(csvTable(old, field, 'tr', 'Ay', '(pasif)').headers.some((h) => h.includes('(pasif)'))).toBe(false)
  })

  it('ay etiketleri arayüz dilinde', () => {
    expect(monthShortLabel({ year: 2026, month: 9 }, 'tr')).toBe('Eyl 2026')
    expect(monthShortLabel({ year: 2026, month: 9 }, 'en')).toBe('Sep 2026')
    expect(monthFullLabel({ year: 2026, month: 2 }, 'tr')).toBe('Şubat 2026')
    expect(monthFullLabel(null, 'tr')).toBe('')
  })
})
