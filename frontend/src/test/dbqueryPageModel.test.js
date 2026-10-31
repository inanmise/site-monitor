import { describe, it, expect } from 'vitest'
import {
  dbqueryStatusKey, dbqueryStatusLabelKey, breachLevelKey, dbTypeLabel, failureKey, FAILURE_CODES, rowCountText,
  metricValueText, intervalText, cronText, scheduleSummary, untilText, scheduleFromRow, DEFAULT_SCHEDULE,
  changedFieldGroups, listToCsv, csvToList, newRuleId, columnsFrom, orderFieldErrors, splitFieldErrors,
  serverFieldErrors, retryAfterOf, planTone, SUBJECT_PLACEHOLDERS,
} from '../components/dbquery/dbqueryPageModel.js'
import { EN } from '../i18n/en.js'
import { TR } from '../i18n/tr.js'

/** Test çevirmeni: anahtar + argümanlar (metin değil — sözleşme anahtar düzeyinde). */
const t = (k, ...a) => (a.length ? `${k}(${a.join('|')})` : k)

describe('dbqueryStatusKey — kart durumu (api §1)', () => {
  const cases = [
    [{ status: 'FAIL' }, 'down', 'dbq.status.fail'],
    [{ status: 'BREACH', breach_level: 'CRITICAL' }, 'down', 'dbq.status.breachCritical'],
    [{ status: 'BREACH', breach_level: 'WARNING' }, 'warn', 'dbq.status.breachWarning'],
    [{ status: 'BREACH' }, 'warn', 'dbq.status.breachWarning'],
    [{ status: 'OK', slow: true }, 'warn', 'dbq.status.slow'],
    [{ status: 'OK' }, 'up', 'dbq.status.ok'],
    [{ status: null }, 'unknown', 'dbq.status.unknown'],
    [{ status: 'SKIPPED' }, 'unknown', 'dbq.status.unknown'],
    [{}, 'unknown', 'dbq.status.unknown'],
    [null, 'unknown', 'dbq.status.unknown'],
  ]
  it.each(cases)('%j → %s', (row, key, label) => {
    expect(dbqueryStatusKey(row)).toBe(key)
    expect(dbqueryStatusLabelKey(row)).toBe(label)
  })
  it('bilinmeyen / hiç çalışmamış asla "up" değil', () => {
    for (const s of [undefined, null, '', 'ok', 'PENDING', 'SKIPPED']) expect(dbqueryStatusKey({ status: s })).not.toBe('up')
  })
  it('seviye rozeti yalnız WARNING / CRITICAL', () => {
    expect(breachLevelKey('WARNING')).toBe('dbq.status.levelWarning')
    expect(breachLevelKey('CRITICAL')).toBe('dbq.status.levelCritical')
    expect(breachLevelKey('NONE')).toBeNull()
    expect(breachLevelKey(null)).toBeNull()
  })
})

describe('veritabanı türü ve hata nedeni', () => {
  it('ürün adları çevrilmez; bilinmeyen tür ham, boş "—"', () => {
    expect(dbTypeLabel('POSTGRESQL')).toBe('PostgreSQL')
    expect(dbTypeLabel('MSSQL')).toBe('SQL Server')
    expect(dbTypeLabel('ORACLE')).toBe('Oracle')
    expect(dbTypeLabel('DB2')).toBe('DB2')
    expect(dbTypeLabel(null)).toBe('—')
  })
  it('bilinen kod kendi anahtarına, bilinmeyen OTHER, boş null', () => {
    expect(failureKey('DB_AUTH')).toBe('dbq.fail.DB_AUTH')
    expect(failureKey('SOMETHING_NEW')).toBe('dbq.fail.OTHER')
    expect(failureKey(null)).toBeNull()
  })
  it('her neden kodunun TR + EN başlığı ve "ne yapılmalı" metni var', () => {
    for (const code of [...FAILURE_CODES, 'OTHER']) {
      for (const dict of [TR, EN]) {
        expect(dict[`dbq.fail.${code}`], `${code}`).toBeTruthy()
        expect(dict[`dbq.fail.${code}.fix`], `${code}.fix`).toBeTruthy()
      }
    }
  })
})

describe('sayılar', () => {
  it('satır sayısı tavana takılınca "≥ N", bilinmiyorsa "—"', () => {
    expect(rowCountText(12, false)).toMatch(/^12$/)
    expect(rowCountText(1000, true)).toMatch(/^≥ 1[.,]?000$/)
    expect(rowCountText(null)).toBe('—')
    expect(rowCountText(Number.NaN)).toBe('—')
  })
  it('ölçülen değer: sayı değilse "—" (0 geçerli değer)', () => {
    expect(metricValueText(0)).toBe('0')
    expect(metricValueText(undefined)).toBe('—')
    expect(metricValueText('12')).toBe('—')
  })
})

describe('zamanlama özeti', () => {
  it('aralık en büyük kesin birimle', () => {
    expect(intervalText(300, t)).toBe('dbq.card.everyMin(5)')
    expect(intervalText(7200, t)).toBe('dbq.card.everyHour(2)')
    expect(intervalText(86400, t)).toBe('dbq.card.everyDay(1)')
    expect(intervalText(90, t)).toBe('dbq.card.everySec(90)')
    expect(intervalText(0, t)).toBeNull()
  })
  it('sık cron kalıpları insan diline; tanınmayan null (uydurma açıklama yok)', () => {
    expect(cronText('*/15 * * * *', t)).toBe('dbq.card.everyMin(15)')
    expect(cronText('5 */2 * * *', t)).toBe('dbq.card.cronEveryHourAt(2|05)')
    expect(cronText('30 9 * * *', t)).toBe('dbq.card.cronDaily(09:30)')
    expect(cronText('0 8 * * 1-5', t)).toBe('dbq.card.cronWeekdays(08:00)')
    expect(cronText('0 8 * * MON-FRI', t)).toBe('dbq.card.cronWeekdays(08:00)')
    expect(cronText('0 7 * * 0', t)).toBe('dbq.card.cronWeekly(dbq.card.dow7|07:00)')
    expect(cronText('0 6 1 * *', t)).toBe('dbq.card.cronMonthly(1|06:00)')
    expect(cronText('@daily', t)).toBe('dbq.card.cronDaily(00:00)')
    expect(cronText('0 30 9 * * *', t)).toBe('dbq.card.cronDaily(09:30)')
    expect(cronText('15 30 9 * * *', t)).toBeNull()
    expect(cronText('0 9 * 1 *', t)).toBeNull()
    expect(cronText('0 9 1,15 * *', t)).toBeNull()
    expect(cronText('garip', t)).toBeNull()
    expect(cronText('x'.repeat(500), t)).toBeNull()
  })
  it('özet: cron ifadesi tanınmazsa ifadenin kendisi; süzgeçler ek olarak', () => {
    expect(scheduleSummary({ schedule_mode: 'CRON', cron_expression: '0 9 1,15 * *' }, t)).toBe('dbq.card.cronRaw(0 9 1,15 * *)')
    expect(scheduleSummary({ schedule_mode: 'INTERVAL', interval_seconds: 600, windows: [{}], skip_holidays: true }, t))
      .toBe('dbq.card.everyMin(10) · dbq.card.schedWindows · dbq.card.schedHolidays')
    expect(scheduleSummary(null, t)).toBe('—')
  })
  it('sonraki çalışma: gelecek / şimdi / gecikti / bozuk', () => {
    const now = Date.parse('2026-10-31T10:00:00Z')
    expect(untilText('2026-10-31T10:05:00Z', t, now)).toBe('dbq.card.inMin(5)')
    expect(untilText('2026-10-31T13:00:00Z', t, now)).toBe('dbq.card.inHour(3)')
    expect(untilText('2026-11-02T10:00:00Z', t, now)).toBe('dbq.card.inDay(2)')
    expect(untilText('2026-10-31T10:00:30Z', t, now)).toBe('dbq.card.inNow')
    expect(untilText('2026-10-31T09:50:00Z', t, now)).toBe('dbq.card.overdue')
    expect(untilText('bozuk', t, now)).toBeNull()
    expect(untilText(null, t, now)).toBeNull()
  })
  it('satır → zamanlama düzenleyicisi değeri (eksikler varsayılanla)', () => {
    expect(scheduleFromRow({})).toEqual({ ...DEFAULT_SCHEDULE })
    expect(scheduleFromRow({ schedule_mode: 'CRON', cron_expression: '0 9 * * *', skip_holidays: 1, windows: 'x' }))
      .toEqual({ ...DEFAULT_SCHEDULE, schedule_mode: 'CRON', cron_expression: '0 9 * * *', skip_holidays: true })
  })
})

describe('düzenlemede yalnız değişen alanlar', () => {
  const base = {
    name: 'A', purpose: 'Amaç metni', profile_id: 3, sql_text: 'SELECT 1', rules: [{ id: 'r1' }], rule_logic: 'ANY',
    nocNotify: false, nocGroupIds: [], schedule_mode: 'INTERVAL', interval_seconds: 300, cron_expression: '', windows: [],
    blackouts: [], skip_holidays: false, skip_dates: [],
  }
  it('hiçbir şey değişmediyse boş', () => {
    expect(changedFieldGroups({ ...base }, base)).toEqual({})
  })
  it('tek alan', () => {
    expect(changedFieldGroups({ ...base, purpose: 'Yeni amaç' }, base)).toEqual({ purpose: 'Yeni amaç' })
  })
  it('kümenin bir üyesi değişince küme bütün gider (sorgu ↔ bağlantı, zamanlama, 7/24)', () => {
    expect(changedFieldGroups({ ...base, sql_text: 'SELECT 2' }, base)).toEqual({ profile_id: 3, sql_text: 'SELECT 2' })
    expect(changedFieldGroups({ ...base, rule_logic: 'ALL' }, base)).toEqual({ rules: [{ id: 'r1' }], rule_logic: 'ALL' })
    const sched = changedFieldGroups({ ...base, interval_seconds: 600 }, base)
    expect(Object.keys(sched).sort()).toEqual(['blackouts', 'cron_expression', 'interval_seconds', 'schedule_mode', 'skip_dates', 'skip_holidays', 'windows'])
    expect(changedFieldGroups({ ...base, nocGroupIds: [4] }, base)).toEqual({ nocNotify: false, nocGroupIds: [4] })
  })
  it('dizi içeriği değerle karşılaştırılır (yeni dizi nesnesi değişiklik değildir)', () => {
    expect(changedFieldGroups({ ...base, rules: [{ id: 'r1' }] }, base)).toEqual({})
  })
})

describe('listeler, kural kimliği, kolonlar', () => {
  it('CSV ↔ liste: boşlar atılır, tekrar bir kez, sıra korunur', () => {
    expect(listToCsv([' a', 'b', 'a', ''])).toBe('a, b')
    expect(csvToList('a, b,,a , c')).toEqual(['a', 'b', 'c'])
    expect(listToCsv(null)).toBe('')
  })
  it('kural kimliği api biçiminde', () => {
    expect(newRuleId(1, () => 0)).toBe('r-10')
    expect(newRuleId()).toMatch(/^r-[0-9a-z]+$/)
  })
  it('kolonlar: yalnız ad + tür; satır verisi okunmaz', () => {
    const r = columnsFrom({ columns: [{ name: 'a', type: 'INT' }, { name: '' }, null, { name: 'b' }], rows: [['gizli']] })
    expect(r).toEqual([{ name: 'a', type: 'INT' }, { name: 'b', type: '' }])
    expect(columnsFrom(null)).toEqual([])
  })
})

describe('sunucu doğrulama hataları → alanlar', () => {
  it('fields olduğu gibi + form sırasına dizilir (kural alt alanı rules yerinde)', () => {
    const r = serverFieldErrors({ success: false, code: 'VALIDATION_FAILED', error: 'x', field: 'cron_expression',
      fields: { cron_expression: 'Cron bozuk', 'rules[1].warning': 'Eşik', name: 'Ad var', purpose: 'Kısa' } })
    expect(Object.keys(r.fields)).toEqual(['name', 'purpose', 'rules[1].warning', 'cron_expression'])
    expect(r.violations).toBeNull()
  })
  it('kodun doğal alanı: SQL reddi → sql_text + ihlaller, plan → sql_text + plan, cron sık → cron_expression', () => {
    const rej = serverFieldErrors({ success: false, code: 'DBQUERY_SQL_REJECTED', error: 'SELECT değil', violations: [{ code: 'SQL_NOT_SELECT' }] })
    expect(rej.fields).toEqual({ sql_text: 'SELECT değil' })
    expect(rej.violations).toEqual([{ code: 'SQL_NOT_SELECT' }])
    const plan = serverFieldErrors({ success: false, code: 'DBQUERY_PLAN_TOO_COSTLY', error: 'Pahalı', plan: { verdict: 'BLOCK' } })
    expect(plan.fields).toEqual({ sql_text: 'Pahalı' })
    expect(plan.plan).toEqual({ verdict: 'BLOCK' })
    expect(serverFieldErrors({ success: false, code: 'DBQUERY_CRON_TOO_FREQUENT', error: 'Sık' }).fields).toEqual({ cron_expression: 'Sık' })
    expect(serverFieldErrors({ success: false, code: 'DBQUERY_CONNECTION_OTHER_TEAM', error: 'Başka takım' }).fields).toEqual({ profile_id: 'Başka takım' })
    expect(serverFieldErrors({ success: false, code: 'DUPLICATE_RECORD', error: 'Ad var' }).fields).toEqual({ name: 'Ad var' })
  })
  it('alana düşmeyen hata fields=null döner (çağıran bandı gösterir); başarı hiçbir şey döndürmez', () => {
    expect(serverFieldErrors({ success: false, code: 'INTERNAL', error: 'Sunucu hatası' }).fields).toBeNull()
    expect(serverFieldErrors({ success: true })).toEqual({ fields: null, violations: null, plan: null })
    expect(serverFieldErrors(null).fields).toBeNull()
  })
  it('hata haritası yapı taşlarına bölünür', () => {
    const s = splitFieldErrors({ rules: 'En az bir', 'rules[0].column': 'Kolon', cron_expression: 'Cron', windows: 'Pencere', name: 'Ad', x: '' })
    expect(s.rules).toEqual({ rules: 'En az bir', 'rules[0].column': 'Kolon' })
    expect(s.schedule).toEqual({ cron_expression: 'Cron', windows: 'Pencere' })
    expect(s.other).toEqual({ name: 'Ad' })
  })
  it('boş iletiler sıralamada atılır', () => {
    expect(orderFieldErrors({ name: '', purpose: 'p' })).toEqual({ purpose: 'p' })
  })
  it('bekleme süresi: retry_after_seconds yukarı yuvarlanır; geçersiz null', () => {
    expect(retryAfterOf({ retry_after_seconds: 4.2 })).toBe(5)
    expect(retryAfterOf({ retryAfter: '8' })).toBe(8)
    expect(retryAfterOf({ retry_after_seconds: 0 })).toBeNull()
    expect(retryAfterOf(null)).toBeNull()
  })
})

describe('plan kararı ve mail konusu', () => {
  it('UNKNOWN / desteklenmiyor asla yeşil değil', () => {
    expect(planTone({ verdict: 'OK' })).toBe('success')
    expect(planTone({ verdict: 'WARN' })).toBe('warning')
    expect(planTone({ verdict: 'BLOCK' })).toBe('danger')
    expect(planTone({ verdict: 'UNKNOWN' })).toBe('neutral')
    expect(planTone({ supported: false, verdict: 'OK' })).toBe('neutral')
    expect(planTone(null)).toBe('neutral')
  })
  it('her yer tutucunun TR + EN açıklaması var', () => {
    for (const ph of SUBJECT_PLACEHOLDERS) {
      const key = `dbq.form.ph.${ph.slice(1, -1)}`
      expect(TR[key], key).toBeTruthy()
      expect(EN[key], key).toBeTruthy()
    }
  })
})
