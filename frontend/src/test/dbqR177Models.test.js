import { describe, it, expect } from 'vitest'
import { TR } from '../i18n/tr.js'
import { EN } from '../i18n/en.js'
import {
  AGE_UNITS, ageParts, ageUnitFor, aggregatesFor, baselineSuffixKey, changeBaseline, changeKind, needsAggregate, needsColumn,
  newRule, operatorsFor, ruleSummary, strictnessHint, unitSeconds, validateRule, validateRules,
} from '../components/dbquery/ruleModel.js'
import {
  WARNING_CODES, columnKind, countTrapIndexes, defaultNewRule, isAggregateOnlySql, primaryValueColumn, ruleFromColumn,
  ruleOptionsFor, toColumnValueRule,
} from '../components/dbquery/ruleSuggest.js'
import {
  FAILURE_CODES, changedFieldGroups, dbqueryStatusKey, dbqueryStatusLabelKey, failureKey, planCheckAvailable, planTone,
  sectionErrorCounts, sectionOfField, serverFieldErrors, unevaluatedRules, warningsOf,
} from '../components/dbquery/dbqueryPageModel.js'
import {
  PRIVILEGE_CODES, TARGET_KEYS, changeDbType, changedConnectionPayload, emptyForm, formFromProfile, payloadFromForm, privilegeFindings,
  privilegeKey, privilegeState, readOnlyAccountScript, setOracleTcps,
} from '../components/dbquery/connectionModel.js'
import { holidayCoverage } from '../components/admin/holidayCalendarModel.js'
import { levelLines, MAX_LEVEL_LINES } from '../components/responsechart/responseChartModel.js'
import { BUILTIN_KEYS, builtinSql, builtinTemplates } from '../components/dbquery/builtinTemplates.js'
import { tierOf } from '../utils/monitorSort.js'

// r177 (2026-10-31) — Veritabanı Sorgu İzleme ön yüzünün SAF modelleri: tazelik kuralı + mevsimsel taban (2.D), COUNT
// tuzağı + kolondan kural (2.C), UNKNOWN durumu / maliyet OFF / bölüm hata sayısı (2.A / 2.C), bağlantı TCPS + yetki
// özeti + DBA betiği (2.B), tatil takvimi kapsamı (2.A), grafik eşik çizgileri (2.D), yerleşik şablonlar (2.C).

/** Sözlükten `t` (yer tutucular `{N}`). */
const tOf = (dict) => (key, ...args) => {
  const s = dict[key]
  if (s == null) return key
  return String(s).replace(/\{(\d+)\}/g, (_, i) => (args[Number(i)] ?? ''))
}
const tTR = tOf(TR)
const tEN = tOf(EN)

describe('tazelik kuralı (FRESHNESS, 2.D)', () => {
  it('yeni kural: kolon + toplama (en yeni) gerekli; işleç yalnız GT / GTE; toplamalar MAX / MIN / FIRST', () => {
    const r = newRule('FRESHNESS', 'r-f')
    expect(r).toMatchObject({ id: 'r-f', kind: 'FRESHNESS', aggregate: 'MAX', operator: 'GT', warning: null, critical: null })
    expect(needsColumn(r)).toBe(true)
    expect(needsAggregate(r)).toBe(true)
    expect(operatorsFor('FRESHNESS')).toEqual(['GT', 'GTE'])
    expect(aggregatesFor('FRESHNESS')).toEqual(['MAX', 'MIN', 'FIRST'])
    expect(aggregatesFor('COLUMN_VALUE')).toContain('SUM')
  })

  it('tür değişimi: tazeliğe geçişte / çıkışta eşikler düşer (saniye ≠ satır sayısı), kolon korunur; uygunsuz toplama alınmaz', () => {
    const cv = { ...newRule('COLUMN_VALUE', 'r1'), column: 'son_kayit', aggregate: 'SUM', warning: 10, critical: 100 }
    const f = changeKind(cv, 'FRESHNESS')
    expect(f).toMatchObject({ id: 'r1', kind: 'FRESHNESS', column: 'son_kayit', aggregate: 'MAX', warning: null, critical: null })
    const back = changeKind({ ...f, warning: 900 }, 'ROW_COUNT')
    expect(back.warning).toBeNull()
    expect(changeKind({ ...cv, aggregate: 'MIN' }, 'FRESHNESS').aggregate).toBe('MIN')
  })

  it('yaş birimi: en büyük TAM birim; özet TR çıkma ekiyle, EN yalın', () => {
    expect(ageParts(900)).toEqual({ unit: 'min', value: 15 })
    expect(ageParts(3600)).toEqual({ unit: 'hour', value: 1 })
    expect(ageParts(90)).toEqual({ unit: 'sec', value: 90 })
    expect(ageParts(172800)).toEqual({ unit: 'day', value: 2 })
    expect(ageParts('x')).toBeNull()
    const rule = { ...newRule('FRESHNESS', 'r'), column: 'last_record_at', warning: 900, critical: 3600 }
    expect(ruleSummary(rule, tTR, 'tr')).toBe('last_record_at kolonunun en yeni değeri 15 dakikadan eskiyse UYARI, 1 saatten eskiyse KRİTİK.')
    expect(ruleSummary(rule, tEN, 'en')).toBe('WARNING if the latest value of last_record_at is older than 15 minutes; CRITICAL if older than 1 hour.')
    expect(ruleSummary({ ...rule, operator: 'GTE', critical: null }, tTR, 'tr')).toBe('last_record_at kolonunun en yeni değeri en az 15 dakika eskiyse UYARI.')
    expect(ruleSummary({ ...rule, aggregate: 'MIN', warning: 90, critical: null }, tTR, 'tr')).toContain('en eski değeri 90 saniyeden eskiyse')
  })

  it('kutunun başlangıç birimi = eşiklerin hepsini bölen en büyük birim; boş kural dk', () => {
    expect(ageUnitFor({ warning: 900, critical: 3600 })).toBe('min')
    expect(ageUnitFor({ warning: 7200, critical: 3600 })).toBe('hour')
    expect(ageUnitFor({ warning: 90 })).toBe('sec')
    expect(ageUnitFor({})).toBe('min')
    expect(unitSeconds('hour')).toBe(3600)
    expect(AGE_UNITS.map((u) => u.key)).toEqual(['sec', 'min', 'hour', 'day'])
  })

  it('doğrulama: kolon zorunlu, eşik zorunlu; negatif yaş ipucu (hata değil)', () => {
    expect(validateRule(newRule('FRESHNESS', 'r'))).toMatchObject({ column: 'column', threshold: 'missing_threshold' })
    expect(validateRule({ ...newRule('FRESHNESS', 'r'), column: 'c', warning: 600 })).toEqual({})
    expect(strictnessHint({ ...newRule('FRESHNESS', 'r'), column: 'c', warning: -5 })).toBe('negative_age')
    expect(strictnessHint({ ...newRule('FRESHNESS', 'r'), column: 'c', warning: 3600, critical: 600 })).toBe('critical_looser')
  })
})

describe('değişim tabanı (mevsimsel, 2.D)', () => {
  it('varsayılan önceki çalışma; tanınmayan değer önceki; özet eki tabana göre', () => {
    const r = { ...newRule('CHANGE', 'r'), warning: 50 }
    expect(changeBaseline(r)).toBe('previous')
    expect(changeBaseline({ ...r, change: { ...r.change, baseline: 'bogus' } })).toBe('previous')
    expect(baselineSuffixKey({ ...r, change: { ...r.change, baseline: 'same_time_yesterday' } })).toBe('dbq.rule.sum.vsYesterday')
    expect(ruleSummary({ ...r, change: { ...r.change, baseline: 'same_time_last_week' } }, tTR, 'tr'))
      .toBe("Satır sayısındaki değişim %50'den büyükse UYARI (geçen hafta aynı saate göre).")
    expect(ruleSummary(r, tTR, 'tr')).toContain('(önceki başarılı çalışmaya göre).')
  })
})

describe('akıllı kural kurulumu (2.C)', () => {
  it('kolon türü sınıflandırması (PG / SQL Server / Oracle sürücü adları)', () => {
    const rows = [
      ['int8', 'number'], ['bigint', 'number'], ['NUMBER(10,2)', 'number'], ['numeric', 'number'], ['float8', 'number'],
      ['timestamp', 'time'], ['timestamptz', 'time'], ['datetime2', 'time'], ['DATE', 'time'], ['TIMESTAMP(6)', 'time'],
      ['varchar', 'text'], ['nvarchar', 'text'], ['VARCHAR2', 'text'], ['text', 'text'], ['uuid', 'text'],
      ['bytea', 'other'], ['', 'other'], [null, 'other'],
    ]
    for (const [type, kind] of rows) expect(columnKind(type), String(type)).toBe(kind)
  })

  it('özet sorgu tespiti: baştaki SELECT bir toplamayla başlıyor ve GROUP BY yok', () => {
    expect(isAggregateOnlySql('SELECT COUNT(*) AS adet FROM t')).toBe(true)
    expect(isAggregateOnlySql('  select max(x) from t where y = 1')).toBe(true)
    expect(isAggregateOnlySql('SELECT TOP 1 SUM(a) FROM t')).toBe(true)
    expect(isAggregateOnlySql('SELECT COUNT(*) FROM t GROUP BY a')).toBe(false)
    expect(isAggregateOnlySql('SELECT id, COUNT(*) FROM t')).toBe(false)
    expect(isAggregateOnlySql('SELECT a FROM t')).toBe(false)
    expect(isAggregateOnlySql(null)).toBe(false)
  })

  it('COUNT tuzağı: sunucu uyarısı (kural kimliği / sırası / genel) ya da deneme kanıtı; satır sayısı kuralı yoksa hiç', () => {
    const rules = [newRule('ROW_COUNT', 'a'), newRule('COLUMN_VALUE', 'b'), newRule('ROW_COUNT', 'c')]
    expect([...countTrapIndexes(rules, { warnings: [{ code: 'ROW_COUNT_ON_AGGREGATE', rule_id: 'c' }] })]).toEqual([2])
    expect([...countTrapIndexes(rules, { warnings: [{ code: 'ROW_COUNT_ON_AGGREGATE', rule_index: 0 }] })]).toEqual([0])
    expect([...countTrapIndexes(rules, { warnings: [{ code: 'ROW_COUNT_ON_AGGREGATE' }] })].sort()).toEqual([0, 2])
    expect([...countTrapIndexes(rules, { warnings: [{ code: 'ROW_COUNT_ON_AGGREGATE', rule_id: 'b' }] })]).toEqual([])
    // Sunucu uyarısı yok: deneme 1 satır + özet sorgu → kanıt; 2 satır ya da sınırlı sonuç → yok
    expect(countTrapIndexes(rules, { testResult: { row_count: 1 }, sqlText: 'SELECT COUNT(*) FROM t' }).size).toBe(2)
    expect(countTrapIndexes(rules, { testResult: { row_count: 2 }, sqlText: 'SELECT COUNT(*) FROM t' }).size).toBe(0)
    expect(countTrapIndexes(rules, { testResult: { row_count: 1 }, sqlText: 'SELECT a FROM t' }).size).toBe(0)
    expect(countTrapIndexes([newRule('COLUMN_VALUE', 'x')], { warnings: [{ code: 'ROW_COUNT_ON_AGGREGATE' }] }).size).toBe(0)
  })

  it('çeviri: kimlik / ad / işleç / eşikler korunur, ilk satırdaki değer + önerilen kolon', () => {
    const r = { ...newRule('ROW_COUNT', 'r-7'), label: 'Hata', operator: 'GTE', warning: 5, critical: 20 }
    expect(toColumnValueRule(r, 'hata_adedi')).toMatchObject({
      id: 'r-7', label: 'Hata', kind: 'COLUMN_VALUE', aggregate: 'FIRST', column: 'hata_adedi', operator: 'GTE', warning: 5, critical: 20,
    })
    expect(toColumnValueRule(r).column).toBeNull()
  })

  it('yeni kuralın varsayılanı: tek satır + sayısal kolon → o kolonda değer; aksi satır sayısı', () => {
    const cols = [{ name: 'durum', type: 'varchar' }, { name: 'adet', type: 'int8' }]
    expect(defaultNewRule({ row_count: 1 }, cols)).toMatchObject({ kind: 'COLUMN_VALUE', column: 'adet', aggregate: 'FIRST' })
    expect(defaultNewRule({ row_count: 4 }, cols).kind).toBe('ROW_COUNT')
    expect(defaultNewRule(null, cols).kind).toBe('ROW_COUNT')
    expect(primaryValueColumn(cols)).toBe('adet')
    expect(primaryValueColumn([{ name: 'durum', type: 'text' }])).toBe('durum')
    expect(primaryValueColumn([])).toBeNull()
  })

  it('kolondan kural: türe göre önerilen önce; kural kolonla doğar', () => {
    expect(ruleOptionsFor({ type: 'timestamptz' })[0]).toBe('FRESHNESS')
    expect(ruleOptionsFor({ type: 'varchar' })[0]).toBe('TEXT_MATCH')
    expect(ruleOptionsFor({ type: 'int8' })[0]).toBe('COLUMN_VALUE')
    expect(ruleFromColumn({ name: 'son' }, 'FRESHNESS')).toMatchObject({ kind: 'FRESHNESS', column: 'son', aggregate: 'MAX' })
    expect(ruleFromColumn({ name: 'x' }, 'BOGUS').kind).toBe('COLUMN_VALUE')
    for (const c of WARNING_CODES) {
      for (const d of [TR, EN]) {
        expect(d[`dbq.warn.${c}.title`], c).toBeTruthy()
        expect(d[`dbq.warn.${c}.body`], c).toBeTruthy()
      }
    }
  })
})

describe('sayfa modeli (2.A / 2.B / 2.C)', () => {
  it('UNKNOWN nötr "değerlendirilemedi" — asla up; sıralamada sorunlu katmanı değil', () => {
    const m = { status: 'UNKNOWN', active: true }
    expect(dbqueryStatusKey(m)).toBe('unknown')
    expect(dbqueryStatusLabelKey(m)).toBe('dbq.status.unevaluated')
    expect(dbqueryStatusLabelKey({ status: null })).toBe('dbq.status.unknown')
    expect(tierOf(m, 'dbquery')).toBe(2)
    expect(TR['dbq.status.unevaluated']).toBe('Değerlendirilemedi')
  })

  it('değerlendirilemeyen kural notları (en çok 3, satır verisi yok)', () => {
    const m = { rule_results: [
      { id: 'a', label: 'A', evaluated: false, note: 'column_missing' }, { id: 'b', evaluated: true },
      { id: 'c', evaluated: false, note: 'baseline_reset', kind: 'CHANGE' }, { id: 'd', evaluated: false }, { id: 'e', evaluated: false },
    ] }
    expect(unevaluatedRules(m).map((r) => r.id)).toEqual(['a', 'c', 'd'])
    expect(unevaluatedRules({})).toEqual([])
  })

  it('maliyet OFF nötr (asla success); düğme kapalıyken ve Oracle\'da gizli, ayar bilinmiyorsa görünür', () => {
    expect(planTone({ supported: true, verdict: 'OFF' })).toBe('neutral')
    expect(planTone({ supported: true, verdict: 'OK' })).toBe('success')
    expect(planCheckAvailable({ plan_check_mode: 'OFF' }, 'POSTGRESQL')).toBe(false)
    expect(planCheckAvailable({ plan_check_mode: 'WARN' }, 'ORACLE')).toBe(false)
    expect(planCheckAvailable({ plan_check_mode: 'WARN' }, 'MSSQL')).toBe(true)
    expect(planCheckAvailable(null, null)).toBe(true)
  })

  it('bölüm hata sayıları: kural alt alanları değerlendirmeye, zamanlama alanları zamanlamaya', () => {
    expect(sectionErrorCounts({ name: 'x', purpose: 'y', 'rules[0].warning': 'z', rules: 'w', cron_expression: 'c', sql_text: 's', unknown_x: 'u', empty: '' }))
      .toEqual({ basic: 2, evaluation: 2, schedule: 1, query: 1 })
    expect(sectionOfField('mail_columns_mode')).toBe('alerting')
    expect(sectionOfField('nope')).toBeNull()
  })

  it('uyarılar zarfta ya da data içinde; kodsuz öğe atılır', () => {
    expect(warningsOf({ warnings: [{ code: 'SELECT_STAR' }, { x: 1 }] })).toEqual([{ code: 'SELECT_STAR' }])
    expect(warningsOf({ data: { warnings: [{ code: 'SENSITIVE_UNMASKED', columns: ['tckn'] }] } })).toHaveLength(1)
    expect(warningsOf({})).toEqual([])
  })

  it('yazabilen hesap (BLOCK) bağlantı alanına; neden metni var; mail kipi mail kümesiyle birlikte gider', () => {
    expect(FAILURE_CODES).toContain('DB_ACCOUNT_WRITABLE')
    expect(failureKey('DB_ACCOUNT_WRITABLE')).toBe('dbq.fail.DB_ACCOUNT_WRITABLE')
    expect(serverFieldErrors({ success: false, code: 'DB_ACCOUNT_WRITABLE', error: 'yazabiliyor' }).fields).toEqual({ profile_id: 'yazabiliyor' })
    const base = { mail_include_rows: true, mail_columns_mode: 'FIRST_N', mail_columns: [], masked_columns: [] }
    expect(changedFieldGroups({ ...base, mail_columns_mode: 'SELECTED' }, base)).toEqual({ ...base, mail_columns_mode: 'SELECTED' })
    expect(changedFieldGroups(base, base)).toEqual({})
  })
})

describe('bağlantı modeli (2.B)', () => {
  it('yeni PostgreSQL bağlantısı require; kayıtlı prefer korunur', () => {
    expect(emptyForm(1).ssl_mode).toBe('require')
    expect(formFromProfile({ db_type: 'POSTGRESQL', ssl_mode: 'prefer' }).ssl_mode).toBe('prefer')
  })

  it('Oracle TCPS: port varsayılandaysa 1521 ↔ 2484, elle yazılan port korunur; gövdede yalnız Oracle', () => {
    let f = changeDbType(emptyForm(1), 'ORACLE')
    expect(f.port).toBe('1521')
    f = setOracleTcps(f, true)
    expect(f).toMatchObject({ oracle_tcps: true, port: '2484' })
    expect(setOracleTcps(f, false).port).toBe('1521')
    expect(setOracleTcps({ ...f, port: '3000' }, false).port).toBe('3000')
    expect(changeDbType(f, 'POSTGRESQL').port).toBe('5432')
    expect(payloadFromForm(f).oracle_tcps).toBe(true)
    expect('oracle_tcps' in payloadFromForm(emptyForm(1))).toBe(false)
    expect(TARGET_KEYS).toContain('oracle_tcps')
    // TCPS değişimi hedef değişimidir: hedef alanların hepsi gider (parola kuralı sunucuda)
    const base = formFromProfile({ id: 9, db_type: 'ORACLE', host: 'h', port: 1521, database_name: 'S', username: 'u', oracle_mode: 'SERVICE' })
    expect(changedConnectionPayload(setOracleTcps(base, true), base)).toMatchObject({ oracle_tcps: true, port: 2484, host: 'h' })
  })

  it('yetki özeti: salt okunur / yazabilen / bilinmiyor / yok; bulgu kodları çevrilir', () => {
    expect(privilegeState(undefined)).toBe('absent')
    expect(privilegeState({ checked: true, read_only: true })).toBe('read_only')
    expect(privilegeState({ checked: true, read_only: false })).toBe('writable')
    expect(privilegeState({ checked: true, read_only: null })).toBe('unknown')
    expect(privilegeState({ checked: false })).toBe('unknown')
    expect(privilegeFindings({ findings: [{ code: 'SUPERUSER' }, null, { detail: 'x' }] })).toEqual([{ code: 'SUPERUSER' }])
    expect(privilegeKey('NOPE')).toBe('dbq.conn.priv.OTHER')
    for (const c of [...PRIVILEGE_CODES, 'OTHER']) {
      expect(TR[`dbq.conn.priv.${c}`], c).toBeTruthy()
      expect(EN[`dbq.conn.priv.${c}`], c).toBeTruthy()
    }
  })

  it('DBA betiği: türe göre salt-okur hesap; yazma yetkisi VERMEZ, MSSQL yazmayı reddeder', () => {
    const pg = readOnlyAccountScript('POSTGRESQL', tTR)
    expect(pg).toContain('default_transaction_read_only = on')
    expect(pg).toContain('GRANT SELECT ON <sema>.<tablo>')
    expect(pg).not.toMatch(/GRANT\s+(INSERT|UPDATE|DELETE|ALL)/i)
    const ms = readOnlyAccountScript('MSSQL', tEN)
    expect(ms).toContain('DENY INSERT, UPDATE, DELETE, ALTER, EXECUTE')
    expect(ms).toContain("'<strong-password>'")
    const ora = readOnlyAccountScript('ORACLE', tTR)
    expect(ora).toContain('GRANT CREATE SESSION')
    expect(ora).toContain('SESSIONS_PER_USER 2')
    expect(ora).not.toMatch(/ANY\s+TABLE/i)
  })
})

describe('tatil takvimi kapsamı (2.A)', () => {
  it('sunucu alanı yoksa null (uyarı yok); yakında bitiyor / bitti / boş; sabit tatil yılı', () => {
    expect(holidayCoverage({ days: [] })).toBeNull()
    expect(holidayCoverage(null)).toBeNull()
    expect(holidayCoverage({ coverage_until: '2027-12-31', ends_soon: false }, '2026-10-31')).toEqual({ until: '2027-12-31', endsSoon: false, ended: false, nextYear: 2028 })
    expect(holidayCoverage({ coverage_until: '2026-12-31', ends_soon: true }, '2026-10-31')).toMatchObject({ endsSoon: true, ended: false, nextYear: 2027 })
    expect(holidayCoverage({ coverage_until: '2026-05-19', ends_soon: false }, '2026-10-31')).toMatchObject({ endsSoon: true, ended: true })
    expect(holidayCoverage({ coverage_until: null, ends_soon: true }, '2026-10-31')).toMatchObject({ until: null, endsSoon: true, nextYear: 2026 })
  })
})

describe('grafik eşik çizgileri (2.D)', () => {
  it('değer ve aralık uçları; geçersiz seviye / sayı atlanır; en çok 6', () => {
    const lines = levelLines([
      { rule_id: 'r1', label: 'Bekleyen', level: 'WARNING', value: 10 },
      { rule_id: 'r1', label: 'Bekleyen', level: 'CRITICAL', value: '100' },
      { rule_id: 'r2', level: 'WARNING', low: 5, high: 50 },
      { rule_id: 'r3', level: 'NONE', value: 1 }, { level: 'CRITICAL', value: 'x' }, null,
    ])
    expect(lines.map((l) => [l.level, l.value, l.edge])).toEqual([
      ['WARNING', 10, null], ['CRITICAL', 100, null], ['WARNING', 5, 'low'], ['WARNING', 50, 'high'],
    ])
    expect(lines[0].label).toBe('Bekleyen')
    expect(levelLines(undefined)).toEqual([])
    const many = Array.from({ length: 10 }, (_, i) => ({ rule_id: `r${i}`, level: 'WARNING', value: i + 1 }))
    expect(levelLines(many)).toHaveLength(MAX_LEVEL_LINES)
  })
})

describe('yerleşik başlangıç şablonları (2.C)', () => {
  it('4 şablon × 3 tür; kurallar geçerli; SQL Server\'da her FROM / JOIN satırında NOLOCK; kimlik yerleşik', () => {
    expect(BUILTIN_KEYS).toEqual(['recent_problems', 'stuck_queue', 'freshness', 'daily_job'])
    for (const type of ['POSTGRESQL', 'MSSQL', 'ORACLE']) {
      const list = builtinTemplates(tTR, type)
      expect(list).toHaveLength(4)
      for (const tpl of list) {
        expect(tpl).toMatchObject({ builtin: true, db_type: type, rule_logic: 'ANY' })
        expect(tpl.id).toBe(`builtin:${tpl.key}`)
        expect(tpl.name && tpl.description && tpl.purpose, tpl.key).toBeTruthy()
        expect(tpl.sql_text).toMatch(/^SELECT /)
        expect(tpl.sql_text).not.toMatch(/\b(INSERT|UPDATE|DELETE|DROP|EXEC)\b/i)
        expect(Object.keys(validateRules(tpl.rules)), `${type} ${tpl.key}`).toEqual([])
        if (type === 'MSSQL') {
          for (const line of tpl.sql_text.split('\n').filter((l) => /\bFROM\b|\bJOIN\b/i.test(l))) expect(line).toContain('WITH (NOLOCK)')
        }
      }
    }
    const fresh = builtinTemplates(tEN, 'ORACLE').find((x) => x.key === 'freshness')
    expect(fresh.rules[0]).toMatchObject({ kind: 'FRESHNESS', column: 'last_record_at', warning: 900, critical: 3600 })
    expect(fresh.name).toBe('Age of the latest record (freshness)')
    // Bağlantı yoksa (tür bilinmiyor) PostgreSQL sürümü
    expect(builtinTemplates(tTR, null)[0].db_type).toBe('POSTGRESQL')
    expect(builtinSql('recent_problems', 'MSSQL')).toContain('GETDATE()')
  })
})

describe('r177 entegrasyon — sunucu biçimleri', () => {
  it('ROW_COUNT_ON_AGGREGATE: sunucunun rule_ids dizisi yalnız o satır sayısı kurallarını işaretler', () => {
    const rules = [{ id: 'a', kind: 'ROW_COUNT' }, { id: 'b', kind: 'COLUMN_VALUE' }, { id: 'c', kind: 'ROW_COUNT' }]
    const set = countTrapIndexes(rules, { warnings: [{ code: 'ROW_COUNT_ON_AGGREGATE', rule_ids: ['c', 'b'] }] })
    expect([...set]).toEqual([2])
  })

  it('bağlantı güvenlik uyarıları: yalnız tanınan sunucu kodları, istemcinin zaten gösterdiği PG prefer / disable hariç', async () => {
    const { serverSecurityWarnings, SERVER_SECURITY_WARNINGS } = await import('../components/dbquery/ConnectionsManager.jsx')
    expect(serverSecurityWarnings({ security_warnings: ['SSL_PREFER_DOWNGRADE', 'MSSQL_UNENCRYPTED', 'BILINMEYEN'] })).toEqual(['MSSQL_UNENCRYPTED'])
    expect(serverSecurityWarnings({})).toEqual([])
    for (const code of SERVER_SECURITY_WARNINGS) {
      expect(TR[`dbq.conn.secw.${code}`]).toBeTruthy()
      expect(EN[`dbq.conn.secw.${code}`]).toBeTruthy()
    }
  })
})

describe('r177 2. tur — taban kuruluyor, bilgi yetkisi', () => {
  it('taban kuran notlar "building" (nötr), gerçek kanıt eksikliği "unevaluated"', async () => {
    const { resultState, BASELINE_NOTES, RESULT_NOTE_CODES } = await import('../components/dbquery/RuleResultsList.jsx')
    for (const note of BASELINE_NOTES) expect(resultState({ evaluated: false, note })).toBe('building')
    expect(resultState({ evaluated: false, note: 'column_missing' })).toBe('unevaluated')
    expect(resultState({ evaluated: false, note: 'future_timestamp' })).toBe('unevaluated')
    expect(RESULT_NOTE_CODES).toContain('future_timestamp')
    expect(TR['dbq.rr.state.building']).toBeTruthy()
    expect(EN['dbq.rr.state.building']).toBeTruthy()
  })

  it('PUBLIC_SCHEMA_CREATE bilgi kodudur — çevirisi var, uyarı sayılmaz', async () => {
    const { isInfoPrivilege, privilegeKey } = await import('../components/dbquery/connectionModel.js')
    expect(isInfoPrivilege('PUBLIC_SCHEMA_CREATE')).toBe(true)
    expect(isInfoPrivilege('CAN_CREATE')).toBe(false)
    expect(TR[privilegeKey('PUBLIC_SCHEMA_CREATE')]).toMatch(/public/)
    expect(EN[privilegeKey('PUBLIC_SCHEMA_CREATE')]).toMatch(/public/)
  })
})
