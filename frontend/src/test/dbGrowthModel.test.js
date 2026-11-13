import { describe, it, expect, beforeAll } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'
import { setDateLocale } from '../i18n/dateLocale.js'
import {
  ADVISOR_CODES, SEVERITIES, ACTIONS, RANGES, DEFAULT_RANGE, HORIZONS, ANALYSIS_SECTIONS,
  CAPACITY_CRITICAL_PCT, CAPACITY_CRITICAL_DAYS, CAPACITY_WARN_PCT, CAPACITY_WARN_DAYS,
  formatBytes, formatSignedBytes, formatDay, usedTone, daysTone, layoutFor, viewState, totalChartRows, tableChartData,
  findingAction, findingArgs, parseCapacity, normalizeRange, retentionText, findingBodyKey, affectedText, applyGate,
  analysisIncomplete, sectionTone, monitorTypeLabel, MONITOR_TABS,
} from '../components/admin/dbgrowth/dbGrowthModel.js'
import { TR } from '../i18n/tr.js'
import { EN } from '../i18n/en.js'

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
  it('bayt: 1024 tabanı ikili (IEC) birimle, eksi işareti, bilinmeyen "—" (0 değil)', () => {
    expect(formatBytes(null)).toBe('—')
    expect(formatBytes(undefined)).toBe('—')
    expect(formatBytes('')).toBe('—')
    expect(formatBytes(0)).toBe('0 B')
    expect(formatBytes(1536 * MB)).toBe('1,5 GiB')
    expect(formatBytes(-300 * MB)).toBe('−300 MiB')
    expect(formatBytes(2048)).toBe('2 KiB')
    expect(formatSignedBytes(2 * GB)).toBe('+2 GiB')
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
    expect(args).toEqual(['port_checks', '%25', '1 GiB', '90', '30', '1.000', '10 MiB', 'dbg.estMethod.histogram'])
    for (const code of ADVISOR_CODES) expect(Array.isArray(findingArgs({ code, params: {} }, t))).toBe(true)
    expect(findingArgs({ code: 'CAPACITY', params: { used_pct: 86, db_bytes: GB, capacity_bytes: 2 * GB, days_until_full: null } }, t)[3])
      .toBe('dbg.adv.noForecast')
  })
  it('ölü satır: görüntüden gelen bulgu (son vacuum anahtarı YOK) "hiç çalışmamış" demez; analizinki anahtara göre (2026-11-13 hata taraması)', () => {
    const view = { code: 'DEAD_TUPLES', table: 'port_checks', params: { dead_pct: 40, dead_rows: 40000, table_bytes: GB } }
    expect(findingArgs(view, t)[3]).toBe('')
    expect(findingArgs({ ...view, params: { ...view.params, last_autovacuum: null } }, t)[3]).toBe('dbg.adv.noAutovacuum')
    expect(findingArgs({ ...view, params: { ...view.params, last_autovacuum: '2026-11-01T03:04:05' } }, t)[3])
      .toBe('dbg.adv.lastAutovacuum(2026-11-01 03:04)')
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

describe('bağımsız inceleme düzeltmeleri (2026-11-13)', () => {
  const shorten = { code: 'SHORTEN_CANDIDATE', action: 'apply_retention', policy_id: 'p', setting_key: 'k', suggested_days: 30,
    current_days: 90, table: 'notification_logs', params: {} }
  it('yasal tutmada kısaltma gövdesi "ilk gece silinir" DEMEZ', () => {
    expect(findingBodyKey(shorten)).toBe('dbg.adv.SHORTEN_CANDIDATE.body')
    expect(findingBodyKey({ ...shorten, params: { hold: true } })).toBe('dbg.adv.SHORTEN_CANDIDATE.bodyHold')
    expect(findingBodyKey({ code: 'NO_RULE', params: { hold: true } })).toBe('dbg.adv.NO_RULE.body')
    expect(TR['dbg.adv.SHORTEN_CANDIDATE.bodyHold']).toMatch(/hiçbir satır silinmez/)
    expect(TR['dbg.adv.SHORTEN_CANDIDATE.bodyHold']).not.toMatch(/ilk gece/)
  })
  it('ortak ayar anahtarı: birden çok tablo adıyla söylenir, tek tabloda sessiz', () => {
    expect(affectedText(shorten, t)).toBeNull()
    expect(affectedText({ ...shorten, params: { affected_count: 1 } }, t)).toBeNull()
    expect(affectedText({ ...shorten, params: { affected_count: 3, affected_tables: ['a', 'b', 'c'] } }, t))
      .toBe('dbg.adv.affects(3|a, b, c)')
    expect(affectedText({ ...shorten, params: { affected_count: 2 } }, t)).toBe('dbg.adv.affectsCount(2)')
  })
  it('Uygula kapısı: güncel süre okunmadan / okunamazsa / önerilenden kısa-eşitse kapalı', () => {
    expect(applyGate(null, null, 30)).toBe('loading')
    expect(applyGate(null, 'hata', 30)).toBe('unknown')
    expect(applyGate({ current_days: null }, null, 30)).toBe('unknown')
    expect(applyGate({ current_days: 30 }, null, 30)).toBe('stale')
    expect(applyGate({ current_days: 14 }, null, 30)).toBe('stale')
    expect(applyGate({ current_days: 90 }, null, 30)).toBe('ok')
  })
  it('eksik analiz: kısmi / bilinmeyen sonuç ya da tam olmayan bölüm', () => {
    expect(analysisIncomplete(null)).toBe(false)
    expect(analysisIncomplete({ status: 'OK', sections: { policies: 'ok', vacuum: 'ok' } })).toBe(false)
    expect(analysisIncomplete({ status: 'OK', sections: { policies: 'partial' } })).toBe(true)
    expect(analysisIncomplete({ status: 'PARTIAL', sections: {} })).toBe(true)
    expect(analysisIncomplete({ status: 'UNSUPPORTED', supported: false })).toBe(false)
    expect(sectionTone('partial')).toBe('warning')
    expect(sectionTone('ok')).toBe('success')
  })
  it('sıcak izleme türlerinin hepsinin TR + EN adı var (ham anahtar görünmez)', () => {
    for (const type of Object.keys(MONITOR_TABS)) {
      expect(TR[`alh.mtype.${type}`], type).toBeTruthy()
      expect(EN[`alh.mtype.${type}`], type).toBeTruthy()
    }
    expect(monitorTypeLabel('dbquery', (k) => TR[k])).toBe('Veritabanı sorgusu')
    expect(monitorTypeLabel('tuhaf', (k) => TR[k])).toBe(TR['dbg.monTypeOther'])
  })
  it('kapasite metinleri ikili birim (GiB) ve WAL notu taşır', () => {
    for (const d of [TR, EN]) {
      expect(d['dbg.cap.value']).toBe('{0} GiB')
      expect(d['dbg.cap.field']).toMatch(/GiB/)
      expect(d['dbg.cap.hint']).toMatch(/WAL/)
      expect(d['help.set.site.monitor.db.capacity-gb']).toMatch(/GiB/)
      expect(d['help.set.site.monitor.db.capacity-gb']).toMatch(/WAL/)
    }
  })
})
