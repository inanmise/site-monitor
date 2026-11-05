import { describe, it, expect, vi } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'
import { TR } from '../i18n/tr.js'
import { EN } from '../i18n/en.js'
import {
  FIX_CODES as STEP_FIX_CODES, STEP_KEYS, healthModel, healthMonitorState, isUnsupportedResponse, normalizeSteps, stepFixKey, stepsSummary,
} from '../components/dbquery/connectionDiagnosis.js'
import {
  DIFF_MAX_CELLS, FIX_CODES, diffStats, fixWarnings, hasFixableViolation, lineDiff, maskSqlLiterals, normalizeFixes, quoteColumn,
  normalizeSkipped, rowLimitSuggestable, selectStarFix, SKIP_REASONS, busyWaitSeconds, BUSY_MAX_WAIT_S,
} from '../components/dbquery/sqlFix.js'
import {
  WIZ_STEPS, canJump, firstErrorStep, firstInvalidStep, stepBannerErrors, stepErrorCounts, stepOfField, suggestName, translateErrors,
  validateStep,
} from '../components/dbquery/wizard/wizardModel.js'
import {
  MAX_BULK, applyNamePattern, bulkCandidates, bulkCounts, bulkPlan, bulkSummaryText, hasUnfinished, isUnsureFailure, patternHasConnection,
  planReady, resolveUnknownRows, retryProfileIds, runBulk,
} from '../components/dbquery/bulkCreateModel.js'
import { CONNECTION_NAME_MAX, copyFormFromProfile } from '../components/dbquery/connectionModel.js'
import { DBA_KEYS, BUILTIN_KEYS, builtinSql, builtinTemplates, dbaTemplates } from '../components/dbquery/builtinTemplates.js'
import { smartParam, validateRules, validateSmart } from '../components/dbquery/ruleModel.js'
import { PASTE_ERRORS, PASTE_NOTES, VALUED_NOTES } from '../components/dbquery/connectionString.js'

const tTR = (k, ...a) => { let s = TR[k] ?? k; a.forEach((v, i) => { s = s.split(`{${i}}`).join(String(v)) }); return s }
const tEN = (k, ...a) => { let s = EN[k] ?? k; a.forEach((v, i) => { s = s.split(`{${i}}`).join(String(v)) }); return s }
const both = (k) => expect(TR[k] && EN[k], k).toBeTruthy()

describe('dinamik i18n anahtarları (A1–B5) iki dilde de var', () => {
  it('yapıştırma hataları / notları, teşhis adımları, çareler, düzeltme kodları, sihirbaz adımları', () => {
    PASTE_ERRORS.forEach((c) => both(`dbq.conn.paste.err.${c}`))
    PASTE_NOTES.forEach((c) => both(`dbq.conn.paste.note.${c}`))
    VALUED_NOTES.forEach((c) => both(`dbq.conn.paste.noteNoValue.${c}`))
    ;['ok', 'rejected', 'unknown', 'skipped'].forEach((k) => { both(`dbq.bulk.sum.${k}`); both(`dbq.bulk.sum.${k}.one`) })
    ;['dbq.bulk.run', 'dbq.bulk.retryFailed', 'dbq.bulk.rowWarnings', 'dbq.conn.usedByMore', 'dbq.fix.diffAdded', 'dbq.fix.diffRemoved',
      'dbq.wiz.stepInvalid'].forEach((k) => { both(k); both(`${k}.one`) })
    both('dbq.fix.warn.COLUMNS_FROM_TEST')
    ;['ok', 'fail', 'skip', 'warn', 'unknown'].forEach((s) => both(`dbq.conn.steps.status.${s}`))
    ;[...STEP_KEYS, 'other'].forEach((k) => { both(`dbq.conn.step.${k}`); both(`dbq.conn.stepFix.${k}`) })
    STEP_FIX_CODES.forEach((c) => both(`dbq.conn.fix.${c}`))
    ;['up', 'warn', 'down', 'paused', 'unknown'].forEach((s) => both(`dbq.conn.usedState.${s}`))
    FIX_CODES.forEach((c) => { both(`dbq.fix.code.${c}.title`); both(`dbq.fix.code.${c}.desc`) })
    both('dbq.fix.warn.ROW_COUNT_AFFECTED')
    SKIP_REASONS.forEach((r) => both(`dbq.fix.reason.${r}`))
    both('dbq.conn.usedState.never')
    WIZ_STEPS.forEach((s) => { both(`dbq.wiz.step.${s}`); both(`dbq.wiz.stepHint.${s}`) })
    ;['WARNING', 'HIGH', 'CRITICAL'].forEach((l) => both(`dbq.wiz.level.${l}`))
    ;['empty', 'tooLong', 'duplicate'].forEach((p) => both(`dbq.bulk.problem.${p}`))
    ;['ok', 'okResolved', 'unknown', 'skipped', 'pending'].forEach((p) => both(`dbq.bulk.status.${p}`))
    for (const k of DBA_KEYS) {
      ;['name', 'desc', 'purpose', 'rule', 'priv.POSTGRESQL', 'priv.MSSQL', 'priv.ORACLE'].forEach((f) => both(`dbq.btpl.${k}.${f}`))
    }
    both('dbq.btpl.replica_lag.rule2')
  })
})

/**
 * Sunucu sabitleriyle eşlik (be1 `DbConnectionSteps.CODES`, `DbQuerySqlFix.CODES / REASONS`). Java kaynağı okunur; liste
 * başka sınıfın sabitine başvurursa (`DbQueryErrors.DB_DNS`) o sınıfta çözülür. Dosya bu ağaçta yoksa (paralel geliştirme)
 * atlanır — birleşik ağaçta ÇALIŞMALI.
 */
const JAVA_DIR = path.resolve(__dirname, '../../../backend/src/main/java/com/sitemonitor/service/dbquery')
const javaSrc = (cls) => { try { return fs.readFileSync(path.join(JAVA_DIR, `${cls}.java`), 'utf8') } catch { return null } }
function resolveJava(cls, name, depth = 0) {
  const src = javaSrc(cls)
  if (!src || depth > 4) return null
  const m = new RegExp(`\\b${name}\\s*=\\s*(?:"([A-Za-z_]+)"|([A-Za-z]+)\\.([A-Z_]+))`).exec(src)
  if (!m) return null
  return m[1] ?? resolveJava(m[2], m[3], depth + 1)
}
function javaList(cls, listName) {
  const src = javaSrc(cls)
  const m = src && new RegExp(`List<String>\\s+${listName}\\s*=\\s*List\\.of\\(([^)]*)\\)`).exec(src)
  if (!m) return null
  return m[1].split(',').map((x) => x.trim()).filter(Boolean).map((ref) => {
    const lit = /^"(.+)"$/.exec(ref)
    if (lit) return lit[1]
    const [a, b] = ref.includes('.') ? ref.split('.') : [cls, ref]
    return resolveJava(a, b)
  })
}
const STEPS_JAVA = fs.existsSync(path.join(JAVA_DIR, 'DbConnectionSteps.java'))

describe.skipIf(!STEPS_JAVA)('sunucu sabitleriyle eşlik (birleşik ağaçta)', () => {
  it('adım anahtarları ve çare kodları = DbConnectionSteps.KEYS / CODES (iki yönde)', () => {
    expect(javaList('DbConnectionSteps', 'KEYS')).toEqual([...STEP_KEYS])
    expect([...javaList('DbConnectionSteps', 'CODES')].sort()).toEqual([...STEP_FIX_CODES].sort())
  })
  it('düzeltme kodları / önerilmeme nedenleri = DbQuerySqlFix.CODES / REASONS', () => {
    expect(javaList('DbQuerySqlFix', 'CODES')).toEqual(['ADD_NOLOCK', 'ADD_ROW_LIMIT'])
    expect(javaList('DbQuerySqlFix', 'REASONS')).toEqual([...SKIP_REASONS])
  })
})

describe('bağlantı teşhisi (A3)', () => {
  it('normalizeSteps: alan yok → null; bilinmeyen durum ayrı; süre yuvarlanır; en çok 12', () => {
    expect(normalizeSteps(undefined)).toBeNull()
    const s = normalizeSteps([{ key: 'dns', status: 'ok', ms: 3.6 }, { key: 'tcp', status: 'weird', ms: 'x' }, null, { status: 'ok' },
      { key: 'tls', status: 'fail', code: 'TLS_FAILED', detail: '  sertifika  ' }])
    expect(s).toEqual([
      { key: 'dns', status: 'ok', ms: 4, code: null, detail: null },
      { key: 'tcp', status: 'unknown', ms: null, code: null, detail: null },
      { key: 'tls', status: 'fail', ms: null, code: 'TLS_FAILED', detail: 'sertifika' },
    ])
    expect(normalizeSteps(Array.from({ length: 30 }, () => ({ key: 'dns', status: 'ok' })))).toHaveLength(12)
  })

  it('stepFixKey: koda özgü → adıma özgü genel; başarılı / atlanan adımın çaresi yok', () => {
    expect(stepFixKey({ key: 'tcp', status: 'fail', code: 'TCP_REFUSED' })).toBe('dbq.conn.fix.TCP_REFUSED')
    expect(stepFixKey({ key: 'tcp', status: 'fail', code: 'YENI_KOD' })).toBe('dbq.conn.stepFix.tcp')
    expect(stepFixKey({ key: 'xyz', status: 'warn' })).toBe('dbq.conn.stepFix.other')
    expect(stepFixKey({ key: 'tls', status: 'warn', code: 'SSL_DISABLED' })).toBe('dbq.conn.fix.SSL_DISABLED')
    expect(stepFixKey({ key: 'dns', status: 'ok' })).toBeNull()
    expect(stepFixKey({ key: 'query', status: 'skip' })).toBeNull()
    // gerekçeli atlama (yetki denetimi ayarla kapalı) açıklanır; tanınmayan kodlu atlama sessiz
    expect(stepFixKey({ key: 'privileges', status: 'skip', code: 'NOT_CHECKED' })).toBe('dbq.conn.fix.NOT_CHECKED')
    expect(stepFixKey({ key: 'privileges', status: 'skip', code: 'X' })).toBeNull()
  })

  it('stepsSummary: başarısız önce; yalnız atlanan / bilinmeyen asla "ok" değil', () => {
    expect(stepsSummary([{ key: 'dns', status: 'ok' }, { key: 'tcp', status: 'fail' }, { key: 'tls', status: 'skip' }]).state).toBe('fail')
    expect(stepsSummary([{ key: 'dns', status: 'ok' }, { key: 'tls', status: 'warn' }]).state).toBe('warn')
    expect(stepsSummary([{ key: 'dns', status: 'ok' }]).state).toBe('ok')
    expect(stepsSummary([{ key: 'dns', status: 'skip' }]).state).toBe('unknown')
    expect(stepsSummary([{ key: 'dns', status: 'ok' }, { key: 'x', status: 'unknown' }]).state).toBe('unknown')
  })

  it('isUnsupportedResponse: 404 / 405 = eski sunucu', () => {
    expect(isUnsupportedResponse({ success: false, status: 404 })).toBe(true)
    expect(isUnsupportedResponse({ success: false, status: 405 })).toBe(true)
    expect(isUnsupportedResponse({ success: false, status: 500 })).toBe(false)
  })
})

describe('be1: 429 "kısa süre bekle" (sql-check / sql-fix)', () => {
  it('yalnız DBQUERY_BUSY / RATE_LIMITED (ya da kodsuz 429) bekleme sayılır; süre 1–60 sn, yoksa varsayılan', () => {
    expect(busyWaitSeconds({ success: false, status: 429, code: 'DBQUERY_BUSY' })).toBe(2)
    expect(busyWaitSeconds({ success: false, status: 429, code: 'RATE_LIMITED', retry_after_seconds: 7.2 })).toBe(8)
    expect(busyWaitSeconds({ success: false, code: 'RATE_LIMITED', retry_after_seconds: 3600 })).toBe(BUSY_MAX_WAIT_S)
    expect(busyWaitSeconds({ success: false, status: 429 })).toBe(2)
    expect(busyWaitSeconds({ success: false, status: 429, code: 'PAYLOAD_TOO_LARGE' })).toBeNull()
    expect(busyWaitSeconds({ success: false, status: 400, code: 'VALIDATION_FAILED' })).toBeNull()
    expect(busyWaitSeconds({ success: false, status: 503, error: 'x' })).toBeNull()
    expect(busyWaitSeconds({ success: true, data: {} })).toBeNull()
    expect(busyWaitSeconds(null)).toBeNull()
  })
})

describe('bağlantı sağlık kartı (A4)', () => {
  it('yeşil yalnız çalışma var ve hata yoksa; çalışma yok → nötr; okunamayan alan null (0 değil)', () => {
    expect(healthModel({ runs_24h: 48, failed_24h: 0, success_pct: 100, monitors: [], monitor_count: 2 }).tone).toBe('success')
    expect(healthModel({ runs_24h: 48, failed_24h: 2, success_pct: 95.8 }).tone).toBe('warning')
    expect(healthModel({ runs_24h: 10, failed_24h: 5, success_pct: 50 }).tone).toBe('danger')
    const none = healthModel({ runs_24h: 0, failed_24h: 0, success_pct: 100, monitor_count: 0 })
    expect(none.tone).toBe('neutral')
    expect(none.successPct).toBeNull()
    expect(none.unused).toBe(true)
    const partial = healthModel({ monitors: [{ id: 1, name: 'A', status: 'OK' }] })
    expect(partial).toMatchObject({ runs: null, failed: null, avgMs: null, tone: 'neutral', monitorCount: 1, unused: false })
    expect(healthModel([])).toBeNull()
    expect(healthModel(null)).toBeNull()
  })

  it('izleme listesi en çok 100; fazlası "daha" sayısı; son hata alanları', () => {
    const many = Array.from({ length: 130 }, (_, i) => ({ id: i + 1, name: `M${i}`, status: 'OK' }))
    const m = healthModel({ monitors: many, monitor_count: 140, last_error: { at: 'x', code: 'DB_AUTH', message: 'm' } })
    expect(m.monitors).toHaveLength(100)
    expect(m.more).toBe(40)
    expect(m.lastError).toEqual({ at: 'x', code: 'DB_AUTH', message: 'm', connectionClass: null })
  })

  it('be1: failed_24h yalnız bağlantı sınıfı; other_failed_24h ayrı ve tonu düşürmez; son hatanın sınıfı (true / false / bilinmiyor)', () => {
    const m = healthModel({ runs_24h: 48, failed_24h: 0, other_failed_24h: 7, success_pct: 100,
      last_error: { at: 'x', code: 'DB_SQL_ERROR', message: 'm', connection_class: false } })
    expect(m.tone).toBe('success')
    expect(m.otherFailed).toBe(7)
    expect(m.lastError.connectionClass).toBe(false)
    expect(healthModel({ runs_24h: 4, failed_24h: 1, last_error: { at: 'x', code: 'DB_AUTH', connection_class: true } }).lastError.connectionClass).toBe(true)
    // Eski sunucu / başarısız deneme (kod yok): alan yok → bilinmiyor; okunamayan sayaç 0 değil null
    expect(healthModel({ runs_24h: 4, failed_24h: 0, last_error: { at: 'x', code: null, connection_class: 'evet' } }).lastError.connectionClass).toBeNull()
    expect(healthModel({ runs_24h: 4, failed_24h: 0 }).otherFailed).toBeNull()
  })

  it('healthMonitorState', () => {
    expect(healthMonitorState({ active: false, status: 'OK' })).toBe('paused')
    expect(healthMonitorState({ status: 'OK' })).toBe('up')
    expect(healthMonitorState({ status: 'BREACH' })).toBe('warn')
    expect(healthMonitorState({ status: 'FAIL' })).toBe('down')
    expect(healthMonitorState({ status: 'UNKNOWN' })).toBe('unknown')
    expect(healthMonitorState({ status: null })).toBe('never')
    expect(healthMonitorState({ status: null }, false)).toBe('unknown')
    expect(healthModel({ status_read: false, monitors: [] }).statusRead).toBe(false)
  })
})

describe('"Bu bağlantıdan yeni" (A2)', () => {
  it('kimlik yok, ad (kopya) eki, parola boş ve kayıtlı sayılmaz; 120 karaktere sığar', () => {
    const p = { id: 7, team_id: 3, name: 'Rapor DB', db_type: 'MSSQL', host: 'sql01', port: 1433, database_name: 'Sales',
      username: 'ro', password_set: true, mssql_instance: 'RPT', mssql_encrypt: true }
    const f = copyFormFromProfile(p, ' (kopya)')
    expect(f).toMatchObject({ id: null, team_id: 3, name: 'Rapor DB (kopya)', db_type: 'MSSQL', host: 'sql01', port: '1433',
      database_name: 'Sales', username: 'ro', mssql_instance: 'RPT', password: '', password_set: false, password_clear: false })
    const long = copyFormFromProfile({ ...p, name: 'x'.repeat(130) }, ' (kopya)')
    expect(long.name).toHaveLength(CONNECTION_NAME_MAX)
    expect(long.name.endsWith(' (kopya)')).toBe(true)
  })

  it('kopyanın takımı yazılabilir takımlardan biri: kaynak yazılabilirse o, değilse ilki (D2)', () => {
    const p = { id: 7, team_id: 3, name: 'X', db_type: 'POSTGRESQL', host: 'h', port: 5432 }
    expect(copyFormFromProfile(p, ' (k)', [3, 5]).team_id).toBe(3)
    expect(copyFormFromProfile(p, ' (k)', [5, 9]).team_id).toBe(5)
    expect(copyFormFromProfile(p, ' (k)', []).team_id).toBe(3)
    expect(copyFormFromProfile(p, ' (k)').team_id).toBe(3)
  })
})

describe('güvenli düzeltme (B2)', () => {
  it('normalizeFixes: yalnız tanınan, SQL\'li, asıl sorgudan farklı; aynı kod bir kez', () => {
    const out = normalizeFixes({ fixes: [
      { code: 'ADD_NOLOCK', sql: 'SELECT 1 FROM t WITH (NOLOCK)', changed: ['t'], note: ' x ' },
      { code: 'ADD_NOLOCK', sql: 'tekrar' },
      { code: 'BILINMEYEN', sql: 'SELECT 2' },
      { code: 'ADD_ROW_LIMIT', sql: 'SELECT 1 FROM t' },
      { code: 'ADD_ROW_LIMIT', sql: '' },
    ] }, 'SELECT 1 FROM t')
    expect(out).toEqual([{ code: 'ADD_NOLOCK', sql: 'SELECT 1 FROM t WITH (NOLOCK)', changed: ['t'], note: 'x', source: 'server' }])
    expect(normalizeFixes(null)).toEqual([])
  })

  it('lineDiff: değişen satır silinir + eklenir; ortak baş / son korunur; sınır aşılınca kaba fark', () => {
    const d = lineDiff('SELECT a\nFROM dbo.T\nWHERE x = 1', 'SELECT a\nFROM dbo.T WITH (NOLOCK)\nWHERE x = 1')
    expect(d).toEqual([
      { type: 'same', text: 'SELECT a' }, { type: 'del', text: 'FROM dbo.T' }, { type: 'add', text: 'FROM dbo.T WITH (NOLOCK)' },
      { type: 'same', text: 'WHERE x = 1' },
    ])
    expect(diffStats(d)).toEqual({ add: 1, del: 1 })
    expect(lineDiff('a', 'a')).toEqual([{ type: 'same', text: 'a' }])
    const n = Math.ceil(Math.sqrt(DIFF_MAX_CELLS)) + 5
    const big = lineDiff(Array.from({ length: n }, (_, i) => `a${i}`).join('\n'), Array.from({ length: n }, (_, i) => `b${i}`).join('\n'))
    expect(diffStats(big)).toEqual({ add: n, del: n })
  })

  it('maskSqlLiterals: dizge, yorum, tırnaklı ad maskelenir; konum korunur', () => {
    const s = "SELECT 'JOIN' -- JOIN\nFROM [a b] /* x */"
    const m = maskSqlLiterals(s)
    expect(m).toHaveLength(s.length)
    expect(m).not.toMatch(/JOIN/)
    expect(m.indexOf('FROM')).toBe(s.indexOf('FROM'))
  })

  it('selectStarFix: tek tablolu basit SELECT * → kolonlar (gerekirse tırnak); diğerleri null', () => {
    const cols = [{ name: 'id' }, { name: 'durum' }, { name: 'tutar tl' }]
    expect(selectStarFix('SELECT * FROM orders WHERE status = 0', cols, 'POSTGRESQL').sql)
      .toBe('SELECT id, durum, "tutar tl" FROM orders WHERE status = 0')
    expect(selectStarFix('SELECT *\nFROM dbo.Orders WITH (NOLOCK)', cols, 'MSSQL').sql)
      .toBe('SELECT [id], [durum], [tutar tl]\nFROM dbo.Orders WITH (NOLOCK)')
    expect(selectStarFix('SELECT * FROM a JOIN b ON a.id = b.id', cols, 'POSTGRESQL')).toBeNull()
    expect(selectStarFix('SELECT * FROM a, b', cols, 'POSTGRESQL')).toBeNull()
    expect(selectStarFix('SELECT * FROM (SELECT 1 AS x) s', cols, 'POSTGRESQL')).toBeNull()
    expect(selectStarFix('SELECT * FROM a UNION SELECT * FROM b', cols, 'POSTGRESQL')).toBeNull()
    expect(selectStarFix('SELECT id FROM a', cols, 'POSTGRESQL')).toBeNull()
    expect(selectStarFix('SELECT * FROM a', [], 'POSTGRESQL')).toBeNull()
    expect(selectStarFix("SELECT * FROM a WHERE note = 'JOIN, x'", cols, 'POSTGRESQL')).not.toBeNull()
    expect(selectStarFix('SELECT * FROM a WHERE x IN (SELECT y FROM b)', cols, 'POSTGRESQL')).toBeNull()
    expect(quoteColumn('a]b', 'MSSQL')).toBe('[a]]b]')
    expect(quoteColumn('a"b', 'ORACLE')).toBe('"a""b"')
  })

  it('quoteColumn türün kuralıyla (O3): MSSQL hep köşeli; PG yalnız küçük harfli sade ad; Oracle yalnız büyük harfli sade ad; ayrılmış sözcük tırnaklı', () => {
    expect(quoteColumn('id', 'MSSQL')).toBe('[id]')
    expect(quoteColumn('customer_id', 'POSTGRESQL')).toBe('customer_id')
    expect(quoteColumn('CustomerId', 'POSTGRESQL')).toBe('"CustomerId"')
    expect(quoteColumn('order', 'POSTGRESQL')).toBe('"order"')
    expect(quoteColumn('user', 'POSTGRESQL')).toBe('"user"')
    expect(quoteColumn('CREATED_AT', 'ORACLE')).toBe('CREATED_AT')
    expect(quoteColumn('created_at', 'ORACLE')).toBe('"created_at"')
    expect(quoteColumn('DATE', 'ORACLE')).toBe('"DATE"')
    expect(quoteColumn('LEVEL', 'ORACLE')).toBe('"LEVEL"')
    expect(quoteColumn('id', null)).toBe('id')
    expect(quoteColumn('select', null)).toBe('"select"')
  })

  it('rowLimitSuggestable: sınır yoksa ve tek satırlık özet değilse', () => {
    expect(rowLimitSuggestable('SELECT id FROM t')).toBe(true)
    expect(rowLimitSuggestable('SELECT id FROM t LIMIT 10')).toBe(false)
    expect(rowLimitSuggestable('SELECT TOP (5) id FROM t')).toBe(false)
    expect(rowLimitSuggestable('SELECT id FROM t FETCH FIRST 5 ROWS ONLY')).toBe(false)
    expect(rowLimitSuggestable('SELECT COUNT(*) FROM t')).toBe(false)
    expect(rowLimitSuggestable('SELECT COUNT(*) FROM t GROUP BY d')).toBe(true)
    expect(rowLimitSuggestable("SELECT id FROM t WHERE x = 'LIMIT'")).toBe(true)
    expect(rowLimitSuggestable('')).toBe(false)
  })

  it('normalizeSkipped: yalnız tanınan kod; gürültü nedenleri (NOT_MSSQL / NOTHING_TO_FIX) atlanır; not yoksa neden', () => {
    expect(normalizeSkipped({ skipped: [
      { code: 'ADD_NOLOCK', reason: 'NOT_MSSQL', note: 'x' }, { code: 'ADD_ROW_LIMIT', reason: 'ALREADY_LIMITED', note: 'Sorguda zaten LIMIT var.' },
      { code: 'ADD_NOLOCK', reason: 'OTHER_HINT' }, { code: 'BILINMEYEN', reason: 'UNPARSABLE' }, { code: 'ADD_ROW_LIMIT', reason: 'YENI' },
    ] })).toEqual([
      { code: 'ADD_ROW_LIMIT', reason: 'ALREADY_LIMITED', note: 'Sorguda zaten LIMIT var.' },
      { code: 'ADD_NOLOCK', reason: 'OTHER_HINT', note: null },
    ])
    expect(normalizeSkipped({})).toEqual([])
  })

  it('fixWarnings + hasFixableViolation', () => {
    expect(fixWarnings({ code: 'ADD_ROW_LIMIT' }, [{ kind: 'ROW_COUNT' }])).toEqual(['ROW_COUNT_AFFECTED'])
    expect(fixWarnings({ code: 'ADD_ROW_LIMIT' }, [{ kind: 'COLUMN_VALUE' }])).toEqual([])
    expect(fixWarnings({ code: 'ADD_NOLOCK' }, [{ kind: 'ROW_COUNT' }])).toEqual([])
    // istemci önerisi sunucudan geçmedi — her zaman uyarı (O3)
    expect(fixWarnings({ code: 'SELECT_COLUMNS' }, [])).toEqual(['COLUMNS_FROM_TEST'])
    expect(hasFixableViolation([{ code: 'SQL_MSSQL_NOLOCK_REQUIRED' }])).toBe(true)
    expect(hasFixableViolation([{ code: 'SQL_NOT_SELECT' }])).toBe(false)
  })
})

describe('sihirbaz modeli (B1)', () => {
  const form = {
    teamId: '5', profileId: 3, sqlText: 'SELECT 1', rules: [], slowQueryMs: '', name: 'Ad', purpose: 'Amaç metni', groupName: 'G', tags: 'a',
  }

  it('adım doğrulaması', () => {
    expect(validateStep('connection', { ...form, teamId: '', profileId: null }, { needTeam: true }))
      .toEqual({ team_id: 'mon.teamRequired', profile_id: 'dbq.form.connectionRequired' })
    expect(validateStep('query', { ...form, sqlText: '  ' })).toEqual({ sql_text: 'dbq.form.sqlRequired' })
    expect(validateStep('query', form, { guardStatus: 'rejected' })).toEqual({ sql_text: 'dbq.wiz.v.sqlRejected' })
    expect(validateStep('query', form, { guardStatus: 'unknown' })).toEqual({})
    expect(validateStep('test', form)).toEqual({})
    expect(Object.keys(validateStep('rules', { ...form, rules: [{ id: 'r', kind: 'COLUMN_VALUE', operator: 'GT' }] })).length).toBeGreaterThan(0)
    expect(validateStep('rules', { ...form, slowQueryMs: '50' })).toEqual({ slow_query_ms: 'dbq.wiz.v.slowRange' })
    expect(validateStep('summary', { ...form, name: '', purpose: 'kısa', groupName: '', tags: '' })).toEqual({
      name: 'mon.fieldRequired', purpose: ['dbq.form.purposeLength', 5, 2000], group_name: 'mon.groupRequired', tags: 'mon.tagsRequired',
    })
    expect(translateErrors({ purpose: ['dbq.form.purposeLength', 5, 2000] }, tTR).purpose).toContain('5')
  })

  it('sunucu alanı → adım; ilk hatalı adım; adım sayaçları', () => {
    expect(stepOfField('profile_id')).toBe('connection')
    expect(stepOfField('rules[2].warning')).toBe('rules')
    expect(stepOfField('cron_expression')).toBe('schedule')
    expect(stepOfField('name')).toBe('summary')
    expect(stepOfField('max_rows')).toBe('advanced')
    expect(firstErrorStep({ name: 'x', sql_text: 'y' })).toBe('query')
    expect(firstErrorStep({ max_rows: 'x' })).toBe('advanced')
    expect(firstErrorStep({ max_rows: 'x', tags: 'y' })).toBe('summary')
    expect(firstErrorStep({})).toBeNull()
    expect(stepErrorCounts({ name: 'a', tags: 'b', 'rules[0].x': 'c', z: '' })).toEqual({ summary: 2, rules: 1 })
    expect(firstInvalidStep(form)).toBeNull()
    expect(firstInvalidStep({ ...form, profileId: null }, { needTeam: true }).step).toBe('connection')
  })

  it('önerilen ad ve atlama izni', () => {
    expect(suggestName({ templateName: 'Bloklanan oturumlar', connectionName: 'X', t: tTR })).toBe('Bloklanan oturumlar')
    expect(suggestName({ connectionName: 'Sipariş DB', t: tTR })).toBe('Sorgu · Sipariş DB')
    expect(suggestName({ t: tEN })).toBe('Database query')
    expect(canJump('connection', 'rules', 'rules')).toBe(true)
    expect(canJump('schedule', 'rules', 'schedule')).toBe(true)
    expect(canJump('summary', 'rules', 'schedule')).toBe(false)
  })
})

describe('ANOMALY boş kritik katsayı = max(3,5, uyarı) — sunucu kuralı (D1)', () => {
  it('uyarı 5, kritik boş: hata yok, kritik 5 sayılır', () => {
    const r = { id: 'a', kind: 'ANOMALY', column: 'x', aggregate: 'FIRST', k_warning: 5, k_critical: '' }
    expect(validateSmart(r).k_critical).toBeUndefined()
    expect(smartParam(r, 'k_critical')).toBe(5)
    expect(smartParam({ ...r, k_warning: 2 }, 'k_critical')).toBe(3.5)
    expect(smartParam({ ...r, k_warning: '' }, 'k_critical')).toBe(3.5)
    // açıkça yazılmış küçük kritik yine hata
    expect(validateSmart({ ...r, k_critical: 4 }).k_critical).toBe('k_order')
  })
})

describe('sihirbaz adım bandı (O4)', () => {
  it('adıma eşlenen ama alanının altında çizilmeyen hatalar banda; çizilenler banda girmez', () => {
    const errs = { alertLevel: 'Seviye geçersiz', nocGroupIds: '7/24 grubu yok', skip_holidays: 'x', interval_seconds: 'Aralık kısa',
      rule_logic: 'Mantık geçersiz', 'rules[0].warning': 'Eşik', name: 'Ad', groupName: 'Grup' }
    expect(stepBannerErrors('schedule', errs).map(([k]) => k)).toEqual(['alertLevel', 'nocGroupIds', 'skip_holidays'])
    expect(stepBannerErrors('rules', errs).map(([k]) => k)).toEqual(['rule_logic'])
    expect(stepBannerErrors('summary', errs).map(([k]) => k)).toEqual(['groupName'])
    expect(stepBannerErrors('query', errs)).toEqual([])
    expect(stepBannerErrors('connection', null)).toEqual([])
  })
})

describe('toplu oluşturma modeli (B4)', () => {
  const P = (id, name) => ({ id, name })

  it('ad kalıbı (TR / EN yer tutucuları, büyük-küçük harf duyarsız)', () => {
    expect(applyNamePattern('{ad} · {bağlantı}', 'Bekleyen', 'DB-1')).toBe('Bekleyen · DB-1')
    expect(applyNamePattern('{NAME} @ {Connection}', 'A', 'B')).toBe('A @ B')
    expect(applyNamePattern('  {ad}   {baglanti} ', 'A', 'B')).toBe('A B')
    expect(patternHasConnection('{ad}')).toBe(false)
    expect(patternHasConnection('{ad} {bağlantı}')).toBe(true)
  })

  it('plan: boş / uzun / yinelenen ad işaretlenir; en çok 50', () => {
    const rows = bulkPlan('{ad}', 'Aynı', [P(1, 'a'), P(2, 'b')])
    expect(rows.map((r) => r.problem)).toEqual(['duplicate', 'duplicate'])
    expect(planReady(rows)).toBe(false)
    expect(bulkPlan('{bağlantı}', '', [P(1, '')])[0].problem).toBe('empty')
    expect(bulkPlan('{ad} {bağlantı}', 'x'.repeat(199), [P(1, 'yz')])[0].problem).toBe('tooLong')
    const ok = bulkPlan('{ad} · {bağlantı}', 'A', [P(1, 'DB1'), P(2, 'db1 ')])
    expect(ok.map((r) => r.problem)).toEqual(['duplicate', 'duplicate'])
    expect(planReady(bulkPlan('{ad} · {bağlantı}', 'A', [P(1, 'DB1'), P(2, 'DB2')]))).toBe(true)
    expect(bulkPlan('{ad} {bağlantı}', 'A', Array.from({ length: 80 }, (_, i) => P(i, `c${i}`)))).toHaveLength(MAX_BULK)
  })

  it('runBulk: sırayla, ilk hatada durmaz; istisna satır hatası olur; durdurunca kalanlar atlanır', async () => {
    const rows = bulkPlan('{ad} · {bağlantı}', 'A', [P(1, 'c1'), P(2, 'c2'), P(3, 'c3')])
    const calls = []
    const create = vi.fn(async (body) => {
      calls.push(body)
      if (body.profile_id === 1) return { success: false, error: 'Bu ad zaten kullanılıyor', code: 'DUPLICATE_RECORD' }
      if (body.profile_id === 2) throw new Error('ağ')
      return { success: true, data: { id: 99 } }
    })
    const seen = []
    const res = await runBulk(rows, { sql_text: 'SELECT 1', rules: [] }, create, { onRow: (i, r) => seen.push([i, r.status]) })
    expect(calls.map((b) => [b.profile_id, b.name, b.sql_text])).toEqual([[1, 'A · c1', 'SELECT 1'], [2, 'A · c2', 'SELECT 1'], [3, 'A · c3', 'SELECT 1']])
    // ağ hatası = sonuç bilinmiyor (izleme oluşmuş olabilir — O2); sunucunun reddi = hata
    expect(res.map((r) => r.status)).toEqual(['error', 'unknown', 'ok'])
    expect(res[0]).toMatchObject({ error: 'Bu ad zaten kullanılıyor', code: 'DUPLICATE_RECORD' })
    expect(res[1]).toMatchObject({ code: 'NETWORK_ERROR' })
    expect(res[2]).toMatchObject({ id: 99 })
    expect(seen).toEqual([[0, 'error'], [1, 'unknown'], [2, 'ok']])
    expect(bulkCounts(res)).toEqual({ ok: 1, error: 1, unknown: 1, skipped: 0 })

    let n = 0
    const stopAfterOne = await runBulk(rows, {}, async () => ({ success: true, data: { id: ++n } }), { shouldStop: () => n >= 1 })
    expect(stopAfterOne.map((r) => r.status)).toEqual(['ok', 'skipped', 'skipped'])
  })

  it('isUnsureFailure: yanıt yok / 0 / 502–504 / ağ kodları bilinmiyor; 400 / 403 / 409 kesin hata (O2)', () => {
    expect(isUnsureFailure(null)).toBe(true)
    expect(isUnsureFailure({ success: false, status: 504 })).toBe(true)
    expect(isUnsureFailure({ success: false, status: 502 })).toBe(true)
    expect(isUnsureFailure({ success: false, code: 'TIMEOUT' })).toBe(true)
    expect(isUnsureFailure({ success: false, errorInfo: { status: 0 } })).toBe(true)
    expect(isUnsureFailure({ success: false, status: 409, code: 'DUPLICATE_RECORD' })).toBe(false)
    expect(isUnsureFailure({ success: false, status: 400, code: 'VALIDATION_FAILED' })).toBe(false)
  })

  it('bilinmeyen satır listede aynı adla varsa oluşmuştur (büyük / küçük harf duyarsız); liste okunamazsa değişmez (O2)', () => {
    const rows = [{ profileId: 1, name: 'A · DB1', status: 'unknown' }, { profileId: 2, name: 'A · DB2', status: 'unknown' }, { profileId: 3, name: 'X', status: 'error' }]
    const out = resolveUnknownRows(rows, [{ id: 41, name: 'a · db1' }])
    expect(out.map((r) => r.status)).toEqual(['ok', 'unknown', 'error'])
    expect(out[0]).toMatchObject({ id: 41, resolved: true })
    expect(resolveUnknownRows(rows, null)).toBe(rows)
    expect(resolveUnknownRows(rows, [])).toBe(rows)
  })

  it('yeniden deneme yalnız reddedilen + atlanan; bilinmeyen HARİÇ; kapatma onayı başarısız / bilinmeyen / atlanan varken (O1)', () => {
    const rows = [{ profileId: 1, status: 'ok' }, { profileId: 2, status: 'error' }, { profileId: 3, status: 'unknown' }, { profileId: 4, status: 'skipped' }]
    expect(retryProfileIds(rows)).toEqual([2, 4])
    expect(hasUnfinished(rows)).toBe(true)
    expect(hasUnfinished([{ status: 'ok' }])).toBe(false)
    expect(hasUnfinished(null)).toBe(false)
  })

  it('önizleme ile koşu aynı liste: ilk 50 bağlantı (O6)', () => {
    const many = Array.from({ length: 60 }, (_, i) => P(i, `c${i}`))
    expect(bulkCandidates(many)).toHaveLength(MAX_BULK)
    expect(bulkCandidates(many).some((c) => c.id === 55)).toBe(false)
    expect(bulkCandidates(null)).toEqual([])
  })

  it('özet metni sayılı parçalar, tekil biçim (D10)', () => {
    expect(bulkSummaryText(tEN, { ok: 1, error: 1, unknown: 0, skipped: 2 })).toBe('1 monitor created · 1 rejected · 2 skipped')
    expect(bulkSummaryText(tTR, { ok: 3, error: 0, unknown: 1, skipped: 0 })).toBe('3 izleme oluşturuldu · 1 sonucu bilinmiyor')
  })
})

describe('hazır DBA şablonları (B5)', () => {
  it('5 şablon × 3 tür: yalnız SELECT, MSSQL\'de her FROM / JOIN satırında NOLOCK, geçerli kurallar, yetki + aralık', () => {
    expect(DBA_KEYS).toEqual(['replica_lag', 'blocked_sessions', 'long_transactions', 'database_size', 'failed_jobs'])
    for (const type of ['POSTGRESQL', 'MSSQL', 'ORACLE']) {
      const list = dbaTemplates(tTR, type)
      expect(list).toHaveLength(5)
      for (const tpl of list) {
        expect(tpl).toMatchObject({ builtin: true, category: 'dba', db_type: type, rule_logic: 'ANY' })
        expect(tpl.privilege, `${type} ${tpl.key}`).toBeTruthy()
        expect(tpl.privilege).not.toBe(`dbq.btpl.${tpl.key}.priv.${type}`)
        expect(tpl.interval_seconds).toBeGreaterThanOrEqual(300)
        expect(tpl.sql_text).toMatch(/^SELECT /)
        expect(tpl.sql_text).not.toMatch(/\b(INSERT|UPDATE|DELETE|DROP|EXEC|MERGE|ALTER|CREATE)\b/i)
        expect(Object.keys(validateRules(tpl.rules)), `${type} ${tpl.key}`).toEqual([])
        // her kuralın kolonu sorgunun kolon takma adlarından biri
        for (const r of tpl.rules) expect(tpl.sql_text, `${type} ${tpl.key} ${r.column}`).toContain(` AS ${r.column}`)
        if (type === 'MSSQL') {
          for (const line of tpl.sql_text.split('\n').filter((l) => /\bFROM\b|\bJOIN\b/i.test(l))) expect(line).toContain('WITH (NOLOCK)')
        }
      }
    }
    // Oracle Data Guard görünümü tek satır: "bağlı yedek yok" kuralı yalnız PostgreSQL / SQL Server'da
    expect(dbaTemplates(tTR, 'ORACLE').find((x) => x.key === 'replica_lag').rules).toHaveLength(1)
    expect(dbaTemplates(tTR, 'MSSQL').find((x) => x.key === 'replica_lag').rules).toHaveLength(2)
    expect(dbaTemplates(tEN, 'MSSQL').find((x) => x.key === 'failed_jobs').privilege).toContain('SQLAgentReaderRole')
    // uygulama şablonları değişmedi
    expect(builtinTemplates(tTR, 'MSSQL').map((x) => x.key)).toEqual([...BUILTIN_KEYS])
    expect(builtinSql('replica_lag', 'ORACLE')).toContain('v$dataguard_stats')
  })
})
