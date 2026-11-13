import { describe, it, expect, beforeEach } from 'vitest'
import { formatSql } from '../components/admin/sql/sqlFormat.js'
import { scanParams, syncParamState } from '../components/admin/sql/sqlParams.js'
import { completionContext, completionItems, insideLiteral, parseAliases } from '../components/admin/sql/sqlComplete.js'
import { MAX_STORED_PARAMS, TABS_KEY, addTab, initialTabs, loadTabs, mergeForeignTabs, parseTabs, saveTabs, serializeTabs } from '../components/admin/sql/sqlTabs.js'
import { normalizeSpec } from '../components/admin/sql/chartModel.js'
import { savedUpdateBody, validateSaved } from '../components/admin/sql/sqlLibraryModel.js'
import { parseSqlError, unmapParamOffset } from '../components/admin/sql/sqlUtils.js'
import { OWNER_KEY, personalKey } from '../utils/personalStorage.js'

/**
 * SQL Playground — 3. tur tarama düzeltmeleri (2026-11-13), saf modeller. Her blok düzeltme geri alınırsa düşer.
 */

// ── 3 · biçimlendirici `:ad` parametrelerine dokunmaz (büyük / küçük harf ve satır sonu DUYARLI) ──
describe('biçimlendirici: :ad parametreleri', () => {
  it('anahtar sözcük adlı parametreler (:from, :limit, :end, :first) büyütülmez, önüne satır sonu konmaz', () => {
    expect(formatSql('select * from t where id >= :from and id < :limit and e = :end and f = :first'))
      .toBe('SELECT *\nFROM t\nWHERE id >= :from\n  AND id < :limit\n  AND e = :end\n  AND f = :first')
    expect(formatSql('select a from t where b = :Where_X')).toBe('SELECT a\nFROM t\nWHERE b = :Where_X')
  })
  it('parametre adları ve sırası biçimlendirmeden sonra aynen (karşılaştırma büyük / küçük harf duyarlı)', () => {
    const samples = [
      'select * from t where id >= :from and id < :limit',
      'select :select, :order, x from t where y between :start and :end order by z limit :limit offset :offset',
      'select a::text, :having from t group by a having count(*) > :min_n',
      'with c as (select * from t where a = :with) select * from c where d = :union',
    ]
    for (const s of samples) {
      const out = formatSql(s)
      expect(scanParams(out).occurrences.map((o) => o.name), s).toEqual(scanParams(s).occurrences.map((o) => o.name))
      expect(formatSql(out), s).toBe(out)   // sabit nokta
    }
  })
  it('`::tip` dönüşümü ve boşluklu iki nokta parametre değildir (eski davranış)', () => {
    expect(formatSql('select a::date from t')).toBe(formatSql('select a::date from t'))
    expect(formatSql('select a from t where b = : from')).toContain('\nFROM')
  })
})

// ── 7 · parametreli sorguda hata konumu ──
describe('hata konumu: parametreli sorgu ($n ile giden metin)', () => {
  const wrap = (body) => `SELECT * FROM (${body}) AS _capped LIMIT 1000`
  /** PostgreSQL'in gördüğü metin: her `:ad` geçişi sırayla `$1`, `$2`… */
  const sent = (body) => {
    let k = 0
    let out = ''
    let pos = 0
    for (const o of scanParams(body).occurrences) {
      out += body.slice(pos, o.start) + `$${++k}`
      pos = o.end
    }
    return out + body.slice(pos)
  }

  it('yer tutucu uzunluk farkları geri alınır', () => {
    const sql = 'SELECT * FROM alerts WHERE created_at > :since AND team_id = :team_id AND nme = 1'
    const position = wrap(sent(sql)).indexOf('nme') + 1
    const info = parseSqlError(`ERROR: column "nme" does not exist\n  Position: ${position}`, { executedSql: wrap(sql), sql })
    expect(info.editorOffset).toBe(sql.indexOf('nme'))
  })
  it('konum yer tutucunun içindeyse o yer tutucunun başı; 10. ve sonraki ($10) iki basamak', () => {
    const body = Array.from({ length: 11 }, (_, i) => `:p${i}`).join(' + ') + ' + zz'
    const s = sent(body)
    expect(unmapParamOffset(body, s.indexOf('zz'))).toBe(body.indexOf('zz'))
    expect(unmapParamOffset(body, s.indexOf('$10') + 2)).toBe(body.indexOf(':p9'))
    expect(unmapParamOffset(body, s.indexOf('$2'))).toBe(body.indexOf(':p1'))
  })
  it('aynı ad iki kez = iki yer tutucu; parametresiz metinde konum aynen; gerçek ? (??) uzunluğu değişmez', () => {
    const body = "SELECT :a, :a, data ? 'k', bad"
    expect(unmapParamOffset(body, sent(body).indexOf('bad'))).toBe(body.indexOf('bad'))
    expect(unmapParamOffset('SELECT bad FROM t', 7)).toBe(7)
  })
})

// ── 8 · ara adlar budanır, kalıcı sınır mevcut adlara önce uygulanır ──
describe('parametre durumu: ara adlar ve kalıcı sınır', () => {
  it('yazarken oluşan ara adlar (:r, :re, …) silinir; değer girilmiş ad korunur', () => {
    let st = {}
    const name = 'region_name'
    for (let i = 1; i <= name.length; i++) st = syncParamState([name.slice(0, i)], st)
    expect(Object.keys(st)).toEqual([name])
    st = { ...st, [name]: { value: 'Marmara', type: 'text' } }
    // yazım düzeltmesi: ad bir an sorgudan çıkar → değer kaybolmaz
    const mid = syncParamState(['region_nam'], st)
    expect(mid[name]).toEqual({ value: 'Marmara', type: 'text' })
    expect(syncParamState([name], mid)[name].value).toBe('Marmara')
    const same = { a: { value: '', type: 'text' } }
    expect(syncParamState(['a'], same)).toBe(same)
  })
  it('40 ara ad olsa da sorgudaki parametrenin değeri ve türü kalıcı taslaktan düşmez', () => {
    const params = {}
    for (let i = 0; i < 49; i++) params[`ara_${i}`] = { value: `v${i}`, type: 'text' }
    params.region_name = { value: 'Ege', type: 'text' }
    params.limit_n = { value: '5', type: 'number' }
    const s = addTab(initialTabs(), { sql: 'SELECT * FROM t WHERE r = :region_name LIMIT :limit_n', params })
    const back = parseTabs(serializeTabs(s).text)
    const p = back.tabs[1].params
    expect(Object.keys(p)).toHaveLength(MAX_STORED_PARAMS)
    expect(p.region_name).toEqual({ value: 'Ege', type: 'text' })
    expect(p.limit_n).toEqual({ value: '5', type: 'number' })
  })
})

// ── 9 / 10 · tamamlama: büyük harfli tablo, E'…\'…' ──
describe('tamamlama: büyük harfli tablo adı ve E dizesi kaçışı', () => {
  const tables = [{ table_name: 'teams' }, { table_name: 'alerts' }]
  const columnsMap = { teams: [{ column_name: 'id' }, { column_name: 'name' }] }
  it('FROM TEAMS t WHERE t. → teams kolonları (arama küçük harfe normalize)', () => {
    const text = 'SELECT * FROM TEAMS t WHERE t.'
    const ctx = completionContext(text, text.length)
    const { items, needColumns } = completionItems(ctx, { tables, columnsMap, aliases: parseAliases(text) })
    expect(items.map((i) => i.label)).toEqual(['id', 'name'])
    expect(needColumns).toEqual([])
    const t2 = 'SELECT * FROM Teams WHERE TEAMS.'
    expect(completionItems(completionContext(t2, t2.length), { tables, columnsMap, aliases: parseAliases(t2) }).items.map((i) => i.label)).toEqual(['id', 'name'])
  })
  it("E'it\\'s' kapandıktan sonra öneri açılır; takma ad bulunur; düz dizede ters bölü kaçış değildir", () => {
    const text = "SELECT * FROM teams t WHERE t.name = E'it\\'s' AND t."
    expect(insideLiteral(text, text.length)).toBe(false)
    expect(completionContext(text, text.length)).toMatchObject({ qualifier: 't', prefix: '' })
    expect(parseAliases("SELECT E'x\\'y' FROM teams t").get('t')).toBe('teams')
    // E dizesinin İÇİ hâlâ dize
    const inside = "SELECT E'it\\'s t."
    expect(insideLiteral(inside, inside.length)).toBe(true)
    // E öneki olmayan dizede \ kaçış değildir: 'a\' kapanmış dizedir
    const plain = "SELECT 'a\\' AS x, t."
    expect(insideLiteral(plain, plain.length)).toBe(false)
    // tanımlayıcının sonundaki e (ör. name'...') E öneki sayılmaz
    expect(insideLiteral("SELECT name'a\\' FROM t WHERE x", 30)).toBe(false)
  })
})

// ── 12 · grafik: aynı kolon iki seride olamaz ──
describe('grafik: yinelenen seri', () => {
  it('aynı kolon iki kez seçilirse tek seriye iner', () => {
    const cols = ['day', 'n', 'm']
    const types = { day: 'text', n: 'number', m: 'number' }
    expect(normalizeSpec({ kind: 'bar', x: 'day', ys: ['n', 'n'] }, cols, types, new Set()).ys).toEqual(['n'])
    expect(normalizeSpec({ kind: 'line', x: 'day', ys: ['n', 'm'] }, cols, types, new Set()).ys).toEqual(['n', 'm'])
  })
})

// ── 5 · panelden düzenleme SQL göndermez ──
describe('kayıtlı sorgu: yalnız üst bilgi düzenlemesi', () => {
  const base = { id: 4, title: 'A', description: '', tags: [], shared: false, sql_text: 'SELECT 2 /* başka oturum */', version: 7 }
  it('includeSql:false → sql_text / params hiç gitmez; yalnız değişen üst bilgi + version', () => {
    const form = { title: 'B', description: '', tags: [], shared: false, sql_text: 'SELECT 1 /* paneldeki eski metin */' }
    expect(savedUpdateBody(form, base, { a: 'text' }, { includeSql: false })).toEqual({ title: 'B', version: 7 })
    expect(savedUpdateBody({ ...form, title: 'A' }, base, null, { includeSql: false })).toBeNull()
    // varsayılan (düzenleyiciden): SQL farkı gider
    expect(savedUpdateBody(form, base, null)).toEqual({ title: 'B', sql_text: form.sql_text, version: 7 })
  })
  it('checkSql:false → boş / eksik SQL denetlenmez', () => {
    expect(validateSaved({ title: 'B', sql_text: '' }, { checkSql: false })).toBeNull()
    expect(validateSaved({ title: 'B', sql_text: '' })).toEqual({ sql_text: 'sqlRequired' })
  })
})

// ── 14 · iki tarayıcı sekmesi aynı taslak anahtarı ──
describe('sekme taslağı: başka tarayıcı sekmesinin sekmeleri korunur', () => {
  beforeEach(() => { localStorage.clear(); localStorage.setItem(OWNER_KEY, 'ali') })
  it('saf birleştirme: görülmemiş sekme eklenir, görülüp kapatılan düşer, etkin sekme bizim', () => {
    const ours = addTab(initialTabs(), { sql: 'SELECT A' })
    const closed = { id: 'closed-1', n: 9, title: '', sql: 'SELECT KAPANDI', params: {} }
    const foreign = { id: 'foreign-1', n: 2, title: '', sql: 'SELECT B', params: {} }
    const stored = { tabs: [ours.tabs[0], closed, foreign], active: 'foreign-1' }
    const known = new Set([...ours.tabs.map((t) => t.id), 'closed-1'])
    const merged = mergeForeignTabs(ours, stored, known)
    expect(merged.tabs.map((t) => t.id)).toEqual([...ours.tabs.map((t) => t.id), 'foreign-1'])
    expect(merged.active).toBe(ours.active)
    expect(mergeForeignTabs(ours, null, known)).toBe(ours)
  })
  it('saveTabs: depoda başka sayfanın yazdığı sekme kaybolmaz; known verilmezse eski davranış', () => {
    const a = initialTabs()
    const b = addTab(a, { sql: 'SELECT B' })   // B sayfası A'nın sekmesini görmüş + kendi sekmesini açmış
    expect(saveTabs(b).ok).toBe(true)
    const knownA = new Set(a.tabs.map((t) => t.id))
    const aEdited = { ...a, tabs: [{ ...a.tabs[0], sql: 'SELECT A2' }] }
    expect(saveTabs(aEdited, undefined, { known: knownA }).ok).toBe(true)
    const back = loadTabs()
    expect(back.tabs.map((t) => t.sql)).toEqual(['SELECT A2', 'SELECT B'])
    expect(localStorage.getItem(personalKey(TABS_KEY, 'ali'))).toContain('SELECT B')
    // known olmadan (tek sayfa) yazılan durum aynen
    saveTabs(aEdited)
    expect(loadTabs().tabs.map((t) => t.sql)).toEqual(['SELECT A2'])
  })
})
