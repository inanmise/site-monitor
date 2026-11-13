import { describe, it, expect, beforeEach } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'
import { REPO_ROOT, javaStringTable } from './helpers/javaTable.js'
import {
  MAX_PARAMS, buildParamsBody, extractParams, guessParamType, normalizeParamValue, paramErrorsFromResponse, scanParams, syncParamState,
  validateParamValue,
} from '../components/admin/sql/sqlParams.js'
import { formatSql, lexForFormat } from '../components/admin/sql/sqlFormat.js'
import { completionContext, completionItems, insideLiteral, parseAliases } from '../components/admin/sql/sqlComplete.js'
import {
  MAX_TABS, MAX_TABS_BYTES, TABS_KEY, addTab, closeOthers, closeTab, duplicateTab, findSavedTab, initialTabs, loadTabs, parseTabs, saveTabs,
  serializeTabs, tabDirty, updateTab,
} from '../components/admin/sql/sqlTabs.js'
import { flattenPlan, planRoot, planSummary } from '../components/admin/sql/planModel.js'
import { OTHER_KEY, PIE_MAX_SLICES, chartData, defaultChartSpec, normalizeSpec } from '../components/admin/sql/chartModel.js'
import { KEEP_RUNS, ROW_KEY, compareRuns, defaultKey, pushRun, snapshotRun } from '../components/admin/sql/compareModel.js'
import {
  excelSheet, filterLocalHistory, groupSamples, historyQuery, historyRangeError, historyState, isPagedHistory, normalizeTags, paramTypesOf,
  savedBody, savedIdFromSearch, savedLink, savedUpdateBody, unwrapExportJson, validateSaved,
} from '../components/admin/sql/sqlLibraryModel.js'
import { DBQ_DRAFT_KEY, DRAFT_TTL_MS, takeDbQueryDraft, writeDbQueryDraft } from '../utils/sqlDbQueryDraft.js'
import { duplicateColumnNames, selectListNames, uniqueColumnNames } from '../components/admin/sql/columnNames.js'
import { inferType, isTimeType } from '../components/admin/sql/sqlUtils.js'
import { OWNER_KEY, PERSONAL_PREFIXES, clearPersonalStorage, personalKey } from '../utils/personalStorage.js'

/**
 * SQL Playground zenginleştirme (2026-11-13) — saf modeller: parametre yer tutucuları (B4, sunucuyla AYNI tablo),
 * biçimlendirici (B2), otomatik tamamlama (B1), sekmeler + taslak (B3), plan (C1), grafik (C2), karşılaştırma (C4),
 * geçmiş / kayıtlı sorgu / örnek / dışa aktarım yardımcıları (A3 / A4 / D1 / D2 / C3), D4 taslağı.
 */

// ── B4 · yer tutucu doğruluk tablosu ── { sorgu, "ad1,ad2" } — sunucu `SqlParamsTest.TABLE` ile AYNI kurallar
export const PARAM_TABLE = [
  ['SELECT * FROM t WHERE id = :id', 'id'],
  ['SELECT :a, :b, :a', 'a,b'],
  ["SELECT 'x :notparam', :p", 'p'],
  ['SELECT now()::date, :d::date', 'd'],
  ['-- :c\nSELECT :x', 'x'],
  ['/* :c /* nested :n */ :still */ SELECT :y', 'y'],
  ['SELECT $$ :in $$, $tag$ :in2 $tag$, :out', 'out'],
  ['SELECT "col:name", :q', 'q'],
  ["SELECT E'it\\'s :x', :z", 'z'],
  ['SELECT :_a1, :Ab, :ab', '_a1,Ab,ab'],
  ['SELECT :1, :', ''],
  ["SELECT ':' || :name", 'name'],
  [`SELECT :${'a'.repeat(41)}`, ''],
  [`SELECT :${'a'.repeat(40)}`, 'a'.repeat(40)],
  ["SELECT 'it''s :no', :yes", 'yes'],
  ['SELECT arr[1:2] FROM t', ''],
  ['SELECT x FROM t WHERE a=:a AND b= :b', 'a,b'],
  ['SELECT 1 /* unterminated :x', ''],
  ["SELECT 'unterminated :x", ''],
  ['SELECT $1, :ok', 'ok'],
  ['SELECT x$y$ :p', 'p'],
  ['SELECT :a::text', 'a'],
  ['SELECT :adı, :ok', 'ok'],
  ['SELECT :a$b', ''],
  ['SELECT :::a', ''],
  ['SELECT f(a := 1)', ''],
  ['SELECT ab$1 FROM t', ''],
  ['SELECT arr[lo:hi] FROM t', 'hi'],
]

describe('B4 · parametreler (yer tutucu kuralı)', () => {
  it('doğruluk tablosu', () => {
    for (const [sql, names] of PARAM_TABLE) expect(extractParams(sql).join(','), sql).toBe(names)
  })
  it('sunucunun SqlParamsTest.TABLE tablosu (varsa) aynı sonucu verir', () => {
    const file = path.join(REPO_ROOT, 'backend/src/test/java/com/sitemonitor/service/sqlplayground/SqlParamsTest.java')
    if (!fs.existsSync(file)) return   // arka uç başka dalda — birleşince kapı devreye girer
    const rows = javaStringTable(fs.readFileSync(file, 'utf8'), 'TABLE')
    expect(rows.length).toBeGreaterThan(5)
    for (const [sql, names] of rows) expect(extractParams(sql).join(','), sql).toBe(names ?? '')
  })
  it('her geçiş konumuyla gelir (aynı ad iki kez)', () => {
    const r = scanParams('SELECT :a + :a')
    expect(r.names).toEqual(['a'])
    expect(r.occurrences.map((o) => [o.start, o.end])).toEqual([[7, 9], [12, 14]])
  })
  it('doğrusal: 200 KB metin hızlı taranır', () => {
    const big = "SELECT '" + 'x'.repeat(200_000)
    const t0 = Date.now()
    expect(extractParams(big)).toEqual([])
    expect(Date.now() - t0).toBeLessThan(500)
  })
  it('tür tahmini', () => {
    expect(guessParamType('team_id')).toBe('number')
    expect(guessParamType('created_at')).toBe('timestamp')
    expect(guessParamType('start_date')).toBe('date')
    expect(guessParamType('is_active')).toBe('bool')
    expect(guessParamType('name')).toBe('text')
  })
  it('değer denetimi ve normalleştirme', () => {
    expect(validateParamValue('number', ' 42 ')).toBeNull()
    expect(validateParamValue('number', '4x')).toBe('number')
    expect(validateParamValue('date', '2026-02-30')).toBe('date')
    expect(validateParamValue('date', '2026-02-28')).toBeNull()
    expect(validateParamValue('timestamp', '2026-11-13T10:05')).toBeNull()
    expect(validateParamValue('timestamp', '2026-11-13T25:05')).toBe('timestamp')
    expect(validateParamValue('bool', 'TRUE')).toBeNull()
    expect(validateParamValue('bool', 'yes')).toBe('bool')
    expect(validateParamValue('text', '')).toBe('required')
    expect(validateParamValue('text', 'x'.repeat(1001))).toBe('tooLong')
    expect(validateParamValue('weird', 'x')).toBe('type')
    expect(normalizeParamValue('timestamp', '2026-11-13 10:05')).toBe('2026-11-13T10:05:00')
    expect(normalizeParamValue('bool', 'TRUE')).toBe('true')
    expect(normalizeParamValue('text', ' a ')).toBe(' a ')
  })
  it('gövde: hatalar params.<ad> anahtarıyla; parametre yoksa body null; 20 üstü tooMany', () => {
    expect(buildParamsBody([], {})).toEqual({ errors: null, body: null, tooMany: false })
    const r = buildParamsBody(['id', 'n'], { id: { value: '5', type: 'number' }, n: { value: '', type: 'text' } })
    expect(r.errors).toEqual({ 'params.n': 'required' })
    const ok = buildParamsBody(['id'], { id: { value: ' 5 ', type: 'number' } })
    expect(ok.body).toEqual({ id: { value: '5', type: 'number' } })
    const many = Array.from({ length: MAX_PARAMS + 1 }, (_, i) => `p${i}`)
    expect(buildParamsBody(many, {}).tooMany).toBe(true)
  })
  it('sunucu alan hatası eşlenir; durum eşitleme aynı nesneyi korur', () => {
    expect(paramErrorsFromResponse({ success: false, code: 'SQL_PARAM_MISSING', field: 'params.id', error: 'eksik' })).toEqual({ 'params.id': 'eksik' })
    expect(paramErrorsFromResponse({ success: false, fields: { 'params.a': 'x', title: 'y' } })).toEqual({ 'params.a': 'x' })
    expect(paramErrorsFromResponse({ success: false })).toBeNull()
    const st = { id: { value: '1', type: 'number' } }
    expect(syncParamState(['id'], st)).toBe(st)
    expect(syncParamState(['id', 'x_at'], st).x_at).toEqual({ value: '', type: 'timestamp' })
  })
})

const norm = (s) => lexForFormat(s).filter((t) => t.t !== 'lcomment' && t.t !== 'bcomment')
  .map((t) => (t.t === 'word' ? t.v.toLowerCase() : t.v))

describe('B2 · biçimlendirici', () => {
  const SAMPLES = [
    'select id, name, count(*) as n from teams t left join app_users u on u.team_id = t.id and u.active where t.id > 3 and t.name like \'%a%\' group by id, name order by n desc limit 10',
    "select a from t where x between 1 and 5 and y = 'and or' -- yorum, select\n, b",
    'with c as (select * from t where a = 1) select * from c union all select * from (select 1) z',
    "select $$ select from where $$ as s, $q$ x $q$, E'it\\'s', \"Weird Col\" from t",
    'select * from t where id in (select id from u where v = 1) and z = 2;select 1',
    '/* başlık */ select now()::date, coalesce(a,b) from t',
  ]
  it('sabit nokta: iki kez biçimlendirmek aynı sonucu verir', () => {
    for (const s of SAMPLES) {
      const once = formatSql(s)
      expect(formatSql(once), s).toBe(once)
    }
  })
  it('anlam korunur: belirteç dizisi (yorum dışı, sözcükler küçük harf) aynı', () => {
    for (const s of SAMPLES) expect(norm(formatSql(s)), s).toEqual(norm(s))
  })
  it('düzen: yan cümleler satır başında, SELECT listesi ve AND alt satırda; BETWEEN bölünmez', () => {
    const out = formatSql('select a, b from t where x between 1 and 5 and y = 2 order by a')
    expect(out).toBe('SELECT a,\n  b\nFROM t\nWHERE x BETWEEN 1 AND 5\n  AND y = 2\nORDER BY a')
  })
  it('alt sorgu girintili, satır yorumundan sonra kod yeni satırda', () => {
    expect(formatSql('select * from (select id from t) x')).toBe('SELECT *\nFROM (\n  SELECT id\n  FROM t\n) x')
    const out = formatSql('select a -- not\n, b from t')
    expect(out.split('\n').find((l) => l.includes('-- not'))).not.toMatch(/,\s*b/)
  })
  it('dize / dolar / tırnaklı ad / yorum içeriği bozulmaz', () => {
    const s = "select 'x  y', $$a\n  b$$, \"A  B\" /* k  l */ from t"
    const out = formatSql(s)
    for (const piece of ["'x  y'", '$$a\n  b$$', '"A  B"', '/* k  l */']) expect(out).toContain(piece)
  })
  it('boş ve çok uzun metin olduğu gibi döner; 200 KB doğrusal', () => {
    expect(formatSql('   ')).toBe('   ')
    const big = 'select ' + Array.from({ length: 20_000 }, (_, i) => `c${i}`).join(', ') + ' from t'
    const t0 = Date.now()
    formatSql(big)
    expect(Date.now() - t0).toBeLessThan(2000)
  })
})

describe('B1 · otomatik tamamlama', () => {
  const tables = [{ table_name: 'alerts' }, { table_name: 'app_users' }, { table_name: 'teams' }]
  const columnsMap = { teams: [{ column_name: 'id' }, { column_name: 'name' }, { column_name: 'manager_id' }] }
  it('bağlam: sözcük öneki, takma ad niteleyicisi, dize / yorum içinde yok', () => {
    expect(completionContext('SELECT * FROM te', 16)).toEqual({ start: 14, prefix: 'te', qualifier: null })
    expect(completionContext('SELECT t.na', 11)).toEqual({ start: 9, prefix: 'na', qualifier: 't' })
    expect(completionContext("SELECT 'te", 10)).toBeNull()
    expect(completionContext('-- te', 5)).toBeNull()
    expect(completionContext('SELECT 12', 9)).toBeNull()
    expect(completionContext('SELECT ', 7)).toBeNull()
    expect(completionContext('SELECT ', 7, { force: true })).toEqual({ start: 7, prefix: '', qualifier: null })
    expect(insideLiteral('select $$ x', 11)).toBe(true)
    expect(insideLiteral('select $$ x $$ y', 16)).toBe(false)
  })
  it('takma adlar', () => {
    const a = parseAliases('SELECT * FROM teams t JOIN app_users AS u ON u.team_id = t.id, public.alerts x WHERE 1=1')
    expect(a.get('t')).toBe('teams')
    expect(a.get('u')).toBe('app_users')
    expect(a.get('x')).toBe('alerts')
    expect(a.get('teams')).toBe('teams')
    expect(a.has('on')).toBe(false)
    const b = parseAliases('SELECT * FROM teams t JOIN u ON f(a, bb) = 1 WHERE x IN (SELECT 1 FROM alerts z), q')
    expect(b.has('bb')).toBe(false)
    expect(b.has('q')).toBe(false)
    expect(b.get('z')).toBe('alerts')
  })
  it('öneriler: tablo (parça eşleşmesi dahil), takma ad sonrası kolonlar, anahtar sözcük ≥ 2 harf', () => {
    const aliases = parseAliases('SELECT * FROM teams t')
    const r1 = completionItems({ start: 0, prefix: 'user', qualifier: null }, { tables, columnsMap, aliases: new Map() })
    expect(r1.items.map((i) => i.label)).toContain('app_users')
    const r2 = completionItems({ start: 0, prefix: 'na', qualifier: 't' }, { tables, columnsMap, aliases })
    expect(r2.items.map((i) => i.label)).toEqual(['name'])
    const r3 = completionItems({ start: 0, prefix: 'sel', qualifier: null }, { tables, columnsMap, aliases: new Map() })
    expect(r3.items.find((i) => i.kind === 'keyword')).toMatchObject({ label: 'select', insert: 'SELECT' })
    const r4 = completionItems({ start: 0, prefix: 'm', qualifier: null }, { tables, columnsMap, aliases })
    expect(r4.items[0]).toMatchObject({ label: 'manager_id', kind: 'column' })
  })
  it('kolonlar yüklenmemişse needColumns döner', () => {
    const aliases = parseAliases('SELECT * FROM alerts a')
    const r = completionItems({ start: 0, prefix: '', qualifier: 'a' }, { tables, columnsMap, aliases })
    expect(r.needColumns).toEqual(['alerts'])
    expect(r.items).toEqual([])
  })
})

describe('B3 · sekmeler + taslak', () => {
  beforeEach(() => localStorage.clear())
  it('ekle / kapat / çoğalt / diğerlerini kapat / güncelle', () => {
    let s = initialTabs()
    s = addTab(s, { sql: 'select 1' })
    expect(s.tabs).toHaveLength(2)
    expect(s.tabs[1].n).toBe(2)
    expect(s.active).toBe(s.tabs[1].id)
    s = duplicateTab(s, s.tabs[1].id)
    expect(s.tabs[2].sql).toBe('select 1')
    const same = updateTab(s, s.tabs[0].id, (t) => t)
    expect(same).toBe(s)
    s = closeOthers(s, s.tabs[0].id)
    expect(s.tabs).toHaveLength(1)
    s = closeTab(s, s.tabs[0].id)
    expect(s.tabs).toHaveLength(1)   // son sekme kapanınca boş yeni sekme
  })
  it('en çok 10 sekme', () => {
    let s = initialTabs()
    for (let i = 0; i < 15; i++) s = addTab(s)
    expect(s.tabs).toHaveLength(MAX_TABS)
  })
  it('kalıcılık: kişisel anahtar, bozuk veri null, 200 KB sınırı', () => {
    localStorage.setItem(OWNER_KEY, 'ali')
    const s = addTab(initialTabs(), { sql: 'select :a', params: { a: { value: '1', type: 'number' } } })
    expect(saveTabs(s).ok).toBe(true)
    expect(localStorage.getItem(personalKey(TABS_KEY, 'ali'))).toContain('select :a')
    const back = loadTabs()
    expect(back.tabs[1].params.a).toEqual({ value: '1', type: 'number' })
    expect(back.active).toBe(s.active)
    expect(parseTabs('{bozuk')).toBeNull()
    expect(parseTabs(JSON.stringify({ v: 2, tabs: [] }))).toBeNull()
    const big = updateTab(s, s.tabs[0].id, { sql: 'x'.repeat(MAX_TABS_BYTES) })
    expect(serializeTabs(big).ok).toBe(false)
    expect(saveTabs(big).ok).toBe(false)
  })
  it('çıkışta silinir (personalStorage öneki)', () => {
    expect(PERSONAL_PREFIXES).toContain('sm.sql.tabs')
    localStorage.setItem(OWNER_KEY, 'ali')
    saveTabs(initialTabs())
    clearPersonalStorage()
    expect(localStorage.getItem(personalKey(TABS_KEY, 'ali'))).toBeNull()
  })
  it('kayıtlı sekme bulma ve değişiklik', () => {
    const s = addTab(initialTabs(), { sql: 'a', savedId: 7, savedSql: 'a' })
    expect(findSavedTab(s, '7').id).toBe(s.tabs[1].id)
    expect(tabDirty(s.tabs[1])).toBe(false)
    expect(tabDirty({ ...s.tabs[1], sql: 'b' })).toBe(true)
    expect(tabDirty({ sql: '  ', savedId: null })).toBe(false)
  })
})

const PG_PLAN = [{
  Plan: {
    'Node Type': 'Hash Join', 'Join Type': 'Inner', 'Startup Cost': 1.5, 'Total Cost': 100, 'Plan Rows': 50,
    'Actual Total Time': 12, 'Actual Rows': 40, 'Actual Loops': 1,
    Plans: [
      { 'Node Type': 'Seq Scan', 'Relation Name': 'alerts', Alias: 'a', 'Startup Cost': 0, 'Total Cost': 80, 'Plan Rows': 1000, 'Actual Total Time': 9, 'Actual Rows': 990, 'Actual Loops': 1, Filter: '(resolved = false)', 'Rows Removed by Filter': 10 },
      { 'Node Type': 'Hash', 'Startup Cost': 1, 'Total Cost': 1, 'Plan Rows': 4, 'Actual Total Time': 0.1, 'Actual Rows': 4, 'Actual Loops': 1,
        Plans: [{ 'Node Type': 'Index Scan', 'Relation Name': 'teams', 'Index Name': 'teams_pkey', 'Startup Cost': 0, 'Total Cost': 1, 'Plan Rows': 4, 'Actual Total Time': 0.05, 'Actual Rows': 4, 'Actual Loops': 1 }] },
    ],
  },
  'Planning Time': 0.4, 'Execution Time': 12.5,
}]

describe('C1 · plan modeli', () => {
  it('kök: dizi / nesne / metin; tanınmayan null', () => {
    expect(planRoot(PG_PLAN).Plan['Node Type']).toBe('Hash Join')
    expect(planRoot(JSON.stringify(PG_PLAN))).not.toBeNull()
    expect(planRoot({ 'Node Type': 'Result' }).Plan['Node Type']).toBe('Result')
    expect(planRoot('x')).toBeNull()
  })
  it('düz liste önce-derinlik, derinlik ve pay', () => {
    const f = flattenPlan(PG_PLAN)
    expect(f.nodes.map((n) => `${n.depth}:${n.type}`)).toEqual(['0:Hash Join', '1:Seq Scan', '1:Hash', '2:Index Scan'])
    expect(f.analyzed).toBe(true)
    expect(f.nodes[1]).toMatchObject({ relation: 'alerts', alias: 'a', seqScan: true, rowsRemoved: 10 })
    expect(f.nodes[1].share).toBeCloseTo(0.75)
    expect(f.executionMs).toBe(12.5)
  })
  it('özet: sunucu özeti öncelikli, yoksa plandan', () => {
    const own = planSummary(PG_PLAN, null)
    expect(own).toMatchObject({ totalCost: 100, planRows: 50, actualMs: 12.5, nodes: 4 })
    expect(own.seqScans).toEqual([{ table: 'alerts', rows: 990 }])
    const srv = planSummary(PG_PLAN, { total_cost: 7, seq_scans: [{ table: 'x', rows: 3 }], top_nodes: [{ node_type: 'Seq Scan', share: 0.5 }] })
    expect(srv.totalCost).toBe(7)
    expect(srv.seqScans).toEqual([{ table: 'x', rows: 3 }])
    expect(srv.topNodes[0]).toMatchObject({ nodeType: 'Seq Scan', share: 0.5 })
  })
})

describe('C2 · grafik modeli', () => {
  const rows = [
    { day: '2026-11-02T00:00:00', team: 'A', n: 3, m: '7' },
    { day: '2026-11-01T00:00:00', team: 'B', n: null, m: 'x' },
    { day: '2026-11-03T00:00:00', team: 'A', n: 5, m: 2 },
  ]
  const types = { day: 'datetime', team: 'text', n: 'number', m: 'text' }
  it('varsayılan: zaman + sayı → çizgi; sayı yoksa null; maskeli seri olamaz', () => {
    expect(defaultChartSpec(['day', 'team', 'n'], types)).toEqual({ kind: 'line', x: 'day', ys: ['n'] })
    expect(defaultChartSpec(['team', 'n'], types)).toEqual({ kind: 'bar', x: 'team', ys: ['n'] })
    expect(defaultChartSpec(['team'], types)).toBeNull()
    expect(defaultChartSpec(['team', 'n'], types, new Set(['n']))).toBeNull()
  })
  it('çizgi: zamana göre sıralı, boş değer null (0 değil)', () => {
    const { data } = chartData(rows, { kind: 'line', x: 'day', ys: ['n'] }, types)
    expect(data.map((d) => d.n)).toEqual([null, 3, 5])
  })
  it('pasta: kategori toplamı, sıfır / boş atlanır, fazlası Diğer', () => {
    const r = chartData(rows, { kind: 'pie', x: 'team', ys: ['n'] }, types)
    expect(r.data).toEqual([{ name: 'A', value: 8, key: 's0' }])
    expect(r.skipped).toBe(1)
    const many = Array.from({ length: PIE_MAX_SLICES + 3 }, (_, i) => ({ k: `c${i}`, v: i + 1 }))
    const p = chartData(many, { kind: 'pie', x: 'k', ys: ['v'] }, { k: 'text', v: 'number' })
    expect(p.data).toHaveLength(PIE_MAX_SLICES + 1)
    expect(p.data.at(-1).name).toBe(OTHER_KEY)
    expect(p.truncated).toBe(true)
  })
  it('seçim geçerli kolonlara oturur', () => {
    expect(normalizeSpec({ kind: 'pie', x: 'team', ys: ['n', 'm'] }, ['team', 'n'], types, new Set())).toEqual({ kind: 'pie', x: 'team', ys: ['n'] })
    expect(normalizeSpec({ kind: 'bar', x: 'gone', ys: ['gone'] }, ['team', 'n'], types, new Set())).toEqual({ kind: 'bar', x: 'team', ys: ['n'] })
  })
})

describe('C4 · karşılaştırma', () => {
  const run = (seq, rows, masked = []) => snapshotRun({ seq, rows, ranSql: 'q', masked_columns: masked, finishedAt: seq })
  it('anahtarla: eklenen / silinen / değişen (hücre) / aynı', () => {
    const a = run(1, [{ id: 1, v: 'x' }, { id: 2, v: 'y' }, { id: 3, v: 'z' }])
    const b = run(2, [{ id: 1, v: 'x' }, { id: 2, v: 'Y' }, { id: 4, v: 'w' }])
    const d = compareRuns(a, b, defaultKey(a, b))
    expect(d.mode).toBe('key')
    expect(d.counts).toEqual({ added: 1, removed: 1, changed: 1, same: 1 })
    expect(d.changed[0]).toMatchObject({ key: ['2'], cols: ['v'] })
  })
  it('tüm satırla çoklu küme; tekrar eden anahtar tüm satıra düşer', () => {
    const a = run(1, [{ k: 1, v: 'a' }, { k: 1, v: 'a' }])
    const b = run(2, [{ k: 1, v: 'a' }, { k: 1, v: 'b' }])
    const d = compareRuns(a, b, ['k'])
    expect(d.fellBack).toBe(true)
    expect(d.mode).toBe('row')
    expect(d.counts).toEqual({ added: 1, removed: 1, changed: 0, same: 1 })
  })
  it('maskeli kolonlar karşılaştırılmaz; varsayılan anahtar PK > id > satır', () => {
    const a = run(1, [{ id: 1, pw: '••••' }], ['pw'])
    const b = run(2, [{ id: 1, pw: '••••' }], ['pw'])
    const d = compareRuns(a, b, [ROW_KEY])
    expect(d.maskedSkipped).toEqual(['pw'])
    expect(d.columns).toEqual(['id'])
    expect(defaultKey(run(1, [{ a: 1, b: 2 }]), run(2, [{ a: 1, b: 2 }]), ['a', 'b'])).toEqual(['a', 'b'])
    expect(defaultKey(run(1, [{ x: 1 }]), run(2, [{ x: 1 }]))).toEqual([ROW_KEY])
  })
  it('son 5 çalıştırma tutulur; hata / iptal girmez', () => {
    let runs = []
    for (let i = 1; i <= 8; i++) runs = pushRun(runs, run(i, [{ id: i }]))
    expect(runs).toHaveLength(KEEP_RUNS)
    expect(runs[0].seq).toBe(4)
    expect(snapshotRun({ error: 'x' })).toBeNull()
    expect(snapshotRun({ cancelled: true, rows: [] })).toBeNull()
  })
})

describe('A4 / D1 / D2 / C3 · kitaplık modelleri', () => {
  it('geçmiş sorgu dizesi, aralık, durum, eski sunucu yedeği', () => {
    expect(historyQuery({ q: ' ab ', status: 'slow', scope: 'all', from: '2026-11-01', page: 2 })).toBe('q=ab&status=slow&scope=all&from=2026-11-01&page=2&size=25')
    expect(historyQuery({})).toBe('scope=mine&page=0&size=25')
    expect(historyRangeError('2026-11-05', '2026-11-01')).toBe('reversed')
    expect(historyRangeError('2026-11-01', '')).toBeNull()
    expect(isPagedHistory({ data: [], total: 0 })).toBe(true)
    expect(isPagedHistory({ data: [] })).toBe(false)
    expect(historyState({ success: false })).toBe('error')
    expect(historyState({ success: true, duration_ms: 5000 })).toBe('slow')
    expect(historyState({ cancelled: true })).toBe('cancelled')
    const rows = [{ sql_text: 'SELECT A', success: true, duration_ms: 1 }, { sql_text: 'select b', success: false }]
    expect(filterLocalHistory(rows, { q: 'a' })).toHaveLength(1)
    expect(filterLocalHistory(rows, { status: 'error' })).toHaveLength(1)
  })
  it('kayıtlı sorgu: etiket, denetim, gövde, yalnız değişen alanlar + version', () => {
    expect(normalizeTags('a, b ,A,, c')).toEqual(['a', 'b', 'c'])
    expect(validateSaved({ title: ' ', sql_text: 'x' })).toEqual({ title: 'required' })
    expect(validateSaved({ title: 'x'.repeat(121), sql_text: '' })).toEqual({ title: 'tooLong', sql_text: 'sqlRequired' })
    expect(validateSaved({ title: 'a', tags: Array.from({ length: 11 }, (_, i) => `t${i}`), sql_text: 'x' })).toEqual({ tags: 'tooMany' })
    expect(savedBody({ title: ' T ', description: '', tags: 'x', shared: 1, sql_text: 'q' }, { id: 'number' }))
      .toEqual({ title: 'T', description: '', tags: ['x'], shared: true, sql_text: 'q', params: { id: 'number' } })
    const base = { id: 3, title: 'T', description: 'd', tags: ['x'], shared: false, sql_text: 'q', version: 4 }
    expect(savedUpdateBody({ title: 'T', description: 'd', tags: ['x'], shared: false, sql_text: 'q' }, base, { a: 'text' })).toBeNull()
    expect(savedUpdateBody({ title: 'T2', description: 'd', tags: ['x'], shared: true, sql_text: 'q' }, base, null))
      .toEqual({ title: 'T2', shared: true, version: 4 })
    expect(savedUpdateBody({ title: 'T', description: 'd', tags: ['x'], shared: false, sql_text: 'q2' }, base, { a: 'text' }))
      .toEqual({ sql_text: 'q2', params: { a: 'text' }, version: 4 })
    expect(paramTypesOf(['a', 'b'], { a: { type: 'number', value: 'SECRET' } })).toEqual({ a: 'number' })
    expect(savedLink(12, 'https://x')).toBe('https://x/?tab=sqlplayground&sq=12')
    expect(savedIdFromSearch('?tab=sqlplayground&sq=42')).toBe('42')
    expect(savedIdFromSearch('?sq=4a')).toBeNull()
  })
  it('örnekler kategoriye göre; eski sunucu tek grup', () => {
    expect(groupSamples([{ label: 'a' }, { label: 'b' }])).toEqual([{ category: null, items: [{ label: 'a' }, { label: 'b' }] }])
    const g = groupSamples([{ label: 'a', category: 'alerts' }, { label: 'b', category: 'teams' }, { label: 'c', category: 'alerts' }])
    expect(g.map((x) => [x.category, x.items.length])).toEqual([['alerts', 2], ['teams', 1]])
  })
  it('Excel sayfası: sayı tipli kolon sayı hücresi; JSON dışa aktarım biçimleri', () => {
    const sh = excelSheet([{ id: '7', n: 'x', j: { a: 1 } }], ['id', 'n', 'j'], { id: 'number', n: 'text', j: 'json' })
    expect(sh.rows).toEqual([['id', 'n', 'j'], [7, 'x', '{"a":1}']])
    expect(unwrapExportJson([{ a: 1 }]).rows).toHaveLength(1)
    expect(unwrapExportJson({ success: true, data: [{ a: 1 }] }).rows).toHaveLength(1)
    expect(unwrapExportJson({ columns: ['a', 'b'], rows: [[1, 2]] })).toMatchObject({ rows: [{ a: 1, b: 2 }], columns: ['a', 'b'], renamed: [] })
    expect(unwrapExportJson('x')).toBeNull()
    // Sunucunun akış yazıcısı (SqlExportWriter): {success, data: {columns, rows: [[…]], row_count, truncated, max_rows, masked_columns}}
    expect(unwrapExportJson({ success: true, data: { columns: ['id', 'pw'], rows: [[1, '••••'], [2, null]], row_count: 2, truncated: false } }))
      .toMatchObject({ rows: [{ id: 1, pw: '••••' }, { id: 2, pw: null }], columns: ['id', 'pw'] })
    expect(unwrapExportJson({ success: true, data: { columns: [], rows: [] } })).toMatchObject({ rows: [], columns: [] })
  })
})

describe('D4 · izleme taslağı', () => {
  beforeEach(() => { sessionStorage.clear(); localStorage.clear(); localStorage.setItem(OWNER_KEY, 'ali') })
  it('yaz → al (tek kullanım), kişisel anahtar, bayat yok sayılır', () => {
    expect(writeDbQueryDraft('SELECT 1', 1000)).toBe(true)
    expect(sessionStorage.getItem(personalKey(DBQ_DRAFT_KEY, 'ali'))).toContain('SELECT 1')
    expect(takeDbQueryDraft(2000)).toEqual({ sql: 'SELECT 1', from: 'sqlplayground' })
    expect(takeDbQueryDraft(2000)).toBeNull()
    writeDbQueryDraft('SELECT 2', 1000)
    expect(takeDbQueryDraft(1000 + DRAFT_TTL_MS + 1)).toBeNull()
    expect(writeDbQueryDraft('   ')).toBe(false)
  })
})

// ── Son inceleme düzeltmeleri (2026-11-13) ─────────────────────────────────────────────────────────────────────────
describe('İnceleme: doğrusal biçimlendirici (DÜŞÜK 8)', () => {
  it('uzun boşluk dizisi sondan doğrusal kırpılır (eski sondaki-boşluk düzenli ifadesi karesel ilerlerdi)', () => {
    const spaces = ' '.repeat(150_000)
    const t0 = Date.now()
    const a = formatSql(`select a -- not${spaces}x\nfrom t`)
    const half = ' '.repeat(90_000)   // FORMAT_MAX_CHARS (200 000) altında kalsın — üstü olduğu gibi döner
    const b = formatSql(`select a${half}, b from t -- son${half}`)
    expect(Date.now() - t0).toBeLessThan(1500)
    expect(a).toContain('-- not')
    expect(b).toMatch(/-- son$/)
    expect(lexForFormat(`-- x${spaces}`)[0].v).toBe('-- x')
  })
})

describe('İnceleme: aynı adlı kolonlar (DÜŞÜK 5)', () => {
  it('JSON dışa aktarımı: başlıklar ayrışır, her hücre KENDİ konumundan (sessiz veri kaybı yok)', () => {
    const r = unwrapExportJson({ success: true, data: { columns: ['id', 'id', 'ID', 'id_2'], rows: [[1, 2, 3, 4], [5, null, 7, 8]] } })
    expect(r.columns).toEqual(['id', 'id_3', 'ID_4', 'id_2'])
    expect(r.rows).toEqual([{ id: 1, id_3: 2, ID_4: 3, id_2: 4 }, { id: 5, id_3: null, ID_4: 7, id_2: 8 }])
    expect(r.renamed).toEqual([{ from: 'id', to: 'id_3' }, { from: 'ID', to: 'ID_4' }])
    expect(unwrapExportJson({ success: true, data: { columns: ['a', 'b'], rows: [[1, 2]] } }).renamed).toEqual([])
    // Nesne satırlarında ikinci değer zaten yok: başlık bir kez, düşen ad bildirilir
    expect(unwrapExportJson({ columns: ['id', 'id'], rows: [{ id: 1 }] })).toMatchObject({ columns: ['id'], collapsed: ['id'] })
  })
  it('ekran sonucu: en dış SELECT listesinin çıktı adları (PostgreSQL ad kuralının yaklaşığı)', () => {
    const T = [
      ['SELECT a.id, b.id FROM a JOIN b ON b.a_id = a.id', ['id', 'id']],
      ['SELECT a.id AS x, b.id y, count(*), count(b.id) FROM a', ['x', 'y', 'count', 'count']],
      ['select "Ad Soyad", u.name::text, 1 + 2, case when x then 1 end, t.* from t', ['Ad Soyad', 'name', '?column?', 'case', null]],
      ['WITH c AS (SELECT id, id FROM x) SELECT DISTINCT ON (a) a, b FROM c -- , a', ['a', 'b']],
      ['SELECT coalesce(a, b) total, f(x) OVER (PARTITION BY y), s.fn(z) FROM t', ['total', 'f', 'fn']],
      ['SELECT x IS NULL, NOT y, \'lit\' FROM t', ['?column?', '?column?', '?column?']],
      ['UPDATE t SET a = 1', []],
    ]
    for (const [sql, want] of T) expect(selectListNames(sql), sql).toEqual(want)
    expect(duplicateColumnNames(['id', 'ID', 'x', null, null, 'x', 'id'])).toEqual(['ID', 'x'])
    expect(duplicateColumnNames(selectListNames('SELECT a.id, b.name FROM a, b'))).toEqual([])
    expect(uniqueColumnNames(['', null, 'a']).names).toEqual(['column', 'column_2', 'a'])
  })
})

describe('İnceleme: tarih türü ve tamamlama sınırı', () => {
  it('yalnız gün → date; saatli (yerel ya da Z) → datetime; karışık → datetime', () => {
    expect(inferType(['2026-11-13', null, '2026-01-02'])).toBe('date')
    expect(inferType(['2026-11-13T09:30:00', '2026-11-13T09:30:00.123'])).toBe('datetime')
    expect(inferType(['2026-11-13T06:30:00Z'])).toBe('datetime')
    expect(inferType(['2026-11-13', '2026-11-13T09:30:00'])).toBe('datetime')
    expect(isTimeType('date') && isTimeType('datetime') && !isTimeType('text')).toBe(true)
    expect(defaultChartSpec(['day', 'n'], { day: 'date', n: 'number' })).toEqual({ kind: 'line', x: 'day', ys: ['n'] })
  })
  it('niteleyicili kolon önerisi tablonun TÜM kolonlarını verir (u. → 60 kolonun hepsi, username dahil)', () => {
    const cols = [...Array.from({ length: 59 }, (_, i) => ({ column_name: `a_col_${String(i).padStart(2, '0')}` })), { column_name: 'username' }]
    const aliases = parseAliases('SELECT u. FROM app_users u')
    const r = completionItems({ start: 9, prefix: '', qualifier: 'u' }, { tables: [{ table_name: 'app_users' }], columnsMap: { app_users: cols }, aliases })
    expect(r.items).toHaveLength(60)
    expect(r.items.map((i) => i.label)).toContain('username')
  })
})
