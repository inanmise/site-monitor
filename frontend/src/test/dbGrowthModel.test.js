import { describe, it, expect, beforeAll } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'
import { setDateLocale } from '../i18n/dateLocale.js'
import {
  ADVISOR_CODES, SEVERITIES, ACTIONS, RANGES, DEFAULT_RANGE, HORIZONS, ANALYSIS_SECTIONS,
  CAPACITY_CRITICAL_PCT, CAPACITY_CRITICAL_DAYS, CAPACITY_WARN_PCT, CAPACITY_WARN_DAYS,
  formatBytes, formatSignedBytes, formatDay, usedTone, daysTone, layoutFor, viewState, totalChartRows, tableChartData,
  findingAction, findingArgs, parseCapacity, normalizeRange, retentionText,
} from '../components/admin/dbgrowth/dbGrowthModel.js'

/**
 * Veritabanı büyümesi modeli (2026-11-13). Sabitler Java'daki karşılıklarıyla AYNI olmalı — test Java kaynağını okur
 * (öneri kodu, önem, eylem, aralık, ufuk, analiz bölümü, kapasite eşikleri); arayüz kodu i18n anahtarına çevirir.
 */
const JAVA = path.resolve(__dirname, '../../../backend/src/main/java/com/sitemonitor/service/dbgrowth')
const read = (f) => fs.readFileSync(path.join(JAVA, f), 'utf8')
const listOf = (src, name) => {
  const m = src.match(new RegExp(`${name}\\s*=\\s*List\\.of\\(([^)]*)\\)`))
  return m ? [...m[1].matchAll(/"([^"]+)"|(\d+)/g)].map((x) => x[1] ?? Number(x[2])) : null
}
const arrayOf = (src, name) => {
  const m = src.match(new RegExp(`${name}\\s*=\\s*\\{([^}]*)\\}`))
  return m ? m[1].split(',').map((x) => Number(x.trim())) : null
}
const num = (src, name) => Number(src.match(new RegExp(`${name}\\s*=\\s*([0-9.]+)`))[1])

const t = (k, ...a) => (a.length ? `${k}(${a.join('|')})` : k)
const MB = 1024 * 1024
const GB = 1024 * MB

beforeAll(() => setDateLocale('tr'))

describe('Java ile aynı sabitler', () => {
  it('öneri kodları, önemler, eylemler, kapasite eşikleri', () => {
    const adv = read('DbGrowthAdvisor.java')
    expect(ADVISOR_CODES).toEqual(listOf(adv, 'CODES'))
    expect(SEVERITIES).toEqual(listOf(adv, 'SEVERITIES'))
    expect(ACTIONS).toEqual(listOf(adv, 'ACTIONS'))
    expect(CAPACITY_CRITICAL_PCT).toBe(num(adv, 'CAPACITY_CRITICAL_PCT'))
    expect(CAPACITY_WARN_PCT).toBe(num(adv, 'CAPACITY_WARN_PCT'))
    expect(CAPACITY_CRITICAL_DAYS).toBe(num(adv, 'CAPACITY_CRITICAL_DAYS'))
    expect(CAPACITY_WARN_DAYS).toBe(num(adv, 'CAPACITY_WARN_DAYS'))
  })
  it('aralıklar, ufuklar, analiz bölümleri', () => {
    const svc = read('DbGrowthService.java')
    expect(RANGES).toEqual(listOf(svc, 'RANGES'))
    expect(DEFAULT_RANGE).toBe(num(svc, 'DEFAULT_RANGE'))
    expect(HORIZONS).toEqual(arrayOf(read('DbGrowthForecast.java'), 'HORIZONS'))
    expect(ANALYSIS_SECTIONS).toEqual(listOf(read('DbHealthAnalysisService.java'), 'SECTIONS'))
  })
})

describe('biçimler', () => {
  it('bayt: 1024 tabanı, eksi işareti, bilinmeyen "—" (0 değil)', () => {
    expect(formatBytes(null)).toBe('—')
    expect(formatBytes(undefined)).toBe('—')
    expect(formatBytes('')).toBe('—')
    expect(formatBytes(0)).toBe('0 B')
    expect(formatBytes(1536 * MB)).toBe('1,5 GB')
    expect(formatBytes(-300 * MB)).toBe('−300 MB')
    expect(formatSignedBytes(2 * GB)).toBe('+2 GB')
    expect(formatSignedBytes(null)).toBe('—')
  })
  it('saf gün saat dilimi kaymadan biçimlenir', () => {
    expect(formatDay('2026-11-13')).toMatch(/13/)
    expect(formatDay(null)).toBe('—')
    expect(formatDay('bozuk')).toBe('—')
  })
  it('aralık normalize: bilinmeyen → 90', () => {
    expect(normalizeRange(30)).toBe(30)
    expect(normalizeRange('365')).toBe(365)
    expect(normalizeRange(7)).toBe(90)
  })
  it('saklama metni: kural yok / gün · kip', () => {
    expect(retentionText(null, t)).toBe('dbg.ret.none')
    expect(retentionText({ days: 90, mode: 'AGE' }, t)).toBe('dbg.ret.days(90|ret.mode.AGE)')
    expect(retentionText({ days: null, mode: 'BOUNDED' }, t)).toBe('ret.mode.BOUNDED')
  })
})

describe('tonlar — bilinmeyen asla yeşil değil', () => {
  it('doluluk', () => {
    expect(usedTone(null)).toBe('neutral')
    expect(usedTone(50)).toBe('neutral')
    expect(usedTone(70)).toBe('warn')
    expect(usedTone(85)).toBe('crit')
  })
  it('dolmaya kalan gün', () => {
    expect(daysTone(null)).toBe('neutral')
    expect(daysTone(400)).toBe('neutral')
    expect(daysTone(90)).toBe('warn')
    expect(daysTone(30)).toBe('crit')
    expect(daysTone(0)).toBe('crit')
  })
  it('yerleşim: 0 (jsdom) ve ≥ 860 tablo, altı kart', () => {
    expect(layoutFor(0)).toBe('table')
    expect(layoutFor(860)).toBe('table')
    expect(layoutFor(859)).toBe('cards')
  })
  it('görünüm durumu', () => {
    expect(viewState(null)).toBe('unknown')
    expect(viewState({ supported: false })).toBe('unsupported')
    expect(viewState({ supported: true, forecast_basis: 'cold_start' })).toBe('collecting')
    expect(viewState({ supported: true, forecast_basis: 'none' })).toBe('collecting')
    expect(viewState({ supported: true, forecast_basis: 'history' })).toBe('ready')
  })
})

describe('grafik satırları', () => {
  const view = {
    series: { database: [{ day: '2026-11-10', bytes: 100 }, { day: '2026-11-12', bytes: 120 }] },
    forecast: [{ day: '2026-11-13', value: 130, low: 125, high: 140 }, { day: '2026-11-14', value: 140, low: 130, high: 150 }],
  }
  it('eksik gün BOŞLUK (null), sıfır değil; tahmin son gerçek noktadan başlar', () => {
    const rows = totalChartRows(view, 30)
    expect(rows.map((r) => new Date(r.t).toISOString().slice(0, 10)))
      .toEqual(['2026-11-10', '2026-11-11', '2026-11-12', '2026-11-13', '2026-11-14'])
    expect(rows[1].actual).toBeNull()
    expect(rows[2]).toMatchObject({ actual: 120, forecast: 120, band: [120, 120] })
    expect(rows[3]).toMatchObject({ actual: null, forecast: 130, band: [125, 140] })
  })
  it('tahmin uzunluğu aralıkla sınırlı (30 günlük aralıkta 30 gün)', () => {
    const fc = Array.from({ length: 180 }, (_, i) => ({ day: new Date(Date.UTC(2026, 10, 13 + i)).toISOString().slice(0, 10), value: i, low: i, high: i }))
    const rows = totalChartRows({ series: { database: [{ day: '2026-11-12', bytes: 1 }] }, forecast: fc }, 30)
    expect(rows.filter((r) => typeof r.forecast === 'number').length).toBe(31)   // çapa + 30 gün
  })
  it('veri yoksa boş', () => {
    expect(totalChartRows({}, 90)).toEqual([])
    expect(tableChartData({}).rows).toEqual([])
  })
  it('tablo serileri: t0…t7, 4 renk × düz / kesikli, eksik gün boşluk', () => {
    const tables = Array.from({ length: 9 }, (_, i) => ({ name: `tbl_${i}`, points: [{ day: '2026-11-10', bytes: i }, { day: '2026-11-12', bytes: i + 1 }] }))
    const { rows, series } = tableChartData({ series: { tables } })
    expect(series).toHaveLength(8)
    expect(series[0]).toMatchObject({ key: 't0', label: 'tbl_0', shape: 'line' })
    expect(series[4].shape).toBe('dashed')
    expect(series[0].color).toBe(series[4].color)
    expect(rows).toHaveLength(3)
    expect(rows[1].t0).toBeUndefined()
  })
})

describe('öneriler', () => {
  const shorten = { code: 'SHORTEN_CANDIDATE', action: 'apply_retention', policy_id: 'series-port', setting_key: 'k', suggested_days: 30,
    current_days: 90, table: 'port_checks', est_rows: 1000, est_bytes: 10 * MB, est_method: 'histogram', params: { share_pct: 25, table_bytes: GB } }
  it('Uygula yalnız GLOBAL yönetici + kısaltma adayı; diğerleri Veri Saklama bağlantısı', () => {
    expect(findingAction(shorten, { canApply: true, canOpenRetention: true })).toBe('apply')
    expect(findingAction(shorten, { canApply: false, canOpenRetention: true })).toBe('open')
    expect(findingAction(shorten, { canApply: false, canOpenRetention: false })).toBeNull()
    expect(findingAction({ ...shorten, suggested_days: null }, { canApply: true, canOpenRetention: true })).toBe('open')
    expect(findingAction({ code: 'NOT_SATURATED', action: 'open_retention', policy_id: 'p' }, { canOpenRetention: true })).toBe('open')
    expect(findingAction({ code: 'DEAD_TUPLES', action: 'dba' }, { canApply: true, canOpenRetention: true })).toBeNull()
  })
  it('sıcak izleme yalnız bilinen türde bağlantı verir', () => {
    expect(findingAction({ code: 'HOT_MONITOR', action: 'none', params: { type: 'ping', monitor_id: 4 } })).toBe('monitor')
    expect(findingAction({ code: 'HOT_MONITOR', action: 'none', params: { type: 'tuhaf', monitor_id: 4 } })).toBeNull()
  })
  it('her kod için parametre üretilir, ham sayı yerine biçim', () => {
    const args = findingArgs(shorten, t)
    expect(args).toEqual(['port_checks', '%25', '1 GB', '90', '30', '1.000', '10 MB', 'dbg.estMethod.histogram'])
    for (const code of ADVISOR_CODES) expect(Array.isArray(findingArgs({ code, params: {} }, t))).toBe(true)
    expect(findingArgs({ code: 'CAPACITY', params: { used_pct: 86, db_bytes: GB, capacity_bytes: 2 * GB, days_until_full: null } }, t)[3])
      .toBe('dbg.adv.noForecast')
  })
})

describe('kapasite girdisi', () => {
  it('boş = 0 (bilinmiyor), tam sayı, üst sınır', () => {
    expect(parseCapacity('', 1000)).toEqual({ value: 0 })
    expect(parseCapacity(' 500 ', 1000)).toEqual({ value: 500 })
    expect(parseCapacity('1,5', 1000)).toEqual({ error: 'notInteger' })
    expect(parseCapacity('-3', 1000)).toEqual({ error: 'notInteger' })
    expect(parseCapacity('1001', 1000)).toEqual({ error: 'tooLarge' })
  })
})
