import { describe, it, expect } from 'vitest'
import {
  cellText, inferType, resultColumns, sortRows, filterRows, rowsToCsv, rowsToJson, rowsToTsv, parseSqlError, offsetToLineCol,
  wordAt, shortType, tableQuery, formatDuration,
} from '../components/admin/sql/sqlUtils.js'
import { tokenizeSql } from '../components/admin/sql/sqlHighlight.jsx'
import {
  buildGraph, acyclicEdges, assignLayers, computeDiagram, neighbourhood, nodeRows, cardHeight, rowCenter, HEADER_H, COLLAPSE_AT,
} from '../components/admin/sql/diagram/layout.js'
import { buildDiagramSvg, xmlEscape, pngScale } from '../components/admin/sql/diagram/exportDiagram.js'

/**
 * SQL Playground saf modeli (2026-09-27 yeniden tasarımı): sonuç yardımcıları, hata ayrıştırma + konum eşleme,
 * sözdizimi boyası (kayıpsız), ilişki diyagramı düzeni (döngü kırma, katmanlar, çakışmasız yerleşim, kenar çapaları)
 * ve SVG dışa aktarımı. Veri: e2e mock'uyla aynı şekil — gerçek kişi/kurum adı yok.
 */

const E = (from, column, to, inferred = true) => ({ from, column, to, inferred })
const DATA = {
  tables: ['teams', 'app_users', 'http_monitors', 'http_checks', 'alerts', 'incident_records', 'smtp_settings', 'ldap_settings'],
  edges: [
    E('teams', 'manager_id', 'app_users'), E('app_users', 'team_id', 'teams'), E('app_users', 'manager_id', 'app_users'),
    E('http_monitors', 'team_id', 'teams'), E('http_checks', 'monitor_id', 'http_monitors'),
    E('alerts', 'team_id', 'teams'), E('alerts', 'incident_id', 'incident_records', false), E('incident_records', 'team_id', 'teams'),
    E('ghost', 'x_id', 'teams'),   // bilinmeyen tablo → düşer
  ],
}

describe('sonuç yardımcıları', () => {
  it('tip çıkarımı: sayı / mantıksal / zaman / json / metin; NULL yok sayılır', () => {
    expect(inferType([1, null, 2.5])).toBe('number')
    expect(inferType([true, false])).toBe('boolean')
    expect(inferType(['2026-09-26T09:30:00', '2026-09-27'])).toBe('datetime')
    expect(inferType([{ a: 1 }, null])).toBe('json')
    expect(inferType(['a', 1])).toBe('text')
    expect(inferType([null, null])).toBe('null')
    expect(cellText({ a: 1 })).toBe('{"a":1}')
    expect(cellText(null)).toBeNull()
  })

  it('sütunlar ilk satır sırasıyla, sonradan gelen anahtar sona eklenir', () => {
    expect(resultColumns([{ id: 1, b: 2 }, { id: 2, b: 3, extra: 4 }])).toEqual(['id', 'b', 'extra'])
  })

  it('sıralama: sayısal, NULL her yönde sonda, kararlı', () => {
    const rows = [{ n: 10 }, { n: null }, { n: 2 }, { n: 33 }]
    expect(sortRows(rows, 'n', 'asc', 'number').map((r) => r.n)).toEqual([2, 10, 33, null])
    expect(sortRows(rows, 'n', 'desc', 'number').map((r) => r.n)).toEqual([33, 10, 2, null])
    expect(sortRows([{ s: 'x10' }, { s: 'x9' }], 's', 'asc', 'text').map((r) => r.s)).toEqual(['x9', 'x10'])
    expect(rows.map((r) => r.n)).toEqual([10, null, 2, 33])   // kaynak değişmez
  })

  it('süzgeç yalnız görünür sütunlarda, büyük/küçük harf duyarsız', () => {
    const rows = [{ a: 'Alpha', b: 'zzz' }, { a: 'beta', b: 'ALPHA' }]
    expect(filterRows(rows, ['a'], 'alp')).toHaveLength(1)
    expect(filterRows(rows, ['a', 'b'], 'alp')).toHaveLength(2)
  })

  it('dışa aktarım: CSV ortak kaçışı (formül nötrleme, BOM, CRLF), JSON görünür sütunlar, TSV sekme/satır temizliği', () => {
    const rows = [{ id: 1, note: '=cmd|x', extra: 'gizli' }, { id: 2, note: 'a,b\nc', extra: null }]
    const csv = rowsToCsv(rows, ['id', 'note'])
    expect(csv.startsWith(String.fromCharCode(0xfeff) + 'id,note\r\n')).toBe(true)
    expect(csv).toContain("1,'=cmd|x")
    expect(csv).toContain('2,"a,b\nc"')
    expect(JSON.parse(rowsToJson(rows, ['id']))).toEqual([{ id: 1 }, { id: 2 }])
    expect(rowsToTsv([{ a: 'x\ty', b: null }], ['a', 'b'])).toBe('a\tb\nx y\t')
  })

  it('2026-09-27 (B8): TSV panoya kopyası formül NÖTRLER (=, +, -, @, sekme/CR) — CSV ile aynı kural; sayı tipi muaf', () => {
    const rows = [
      { a: '=cmd|x', b: '+1+1', c: '-2+3', d: '@SUM(A1)' },
      { a: '\t=HYPERLINK("x")', b: '\r=1', c: 'düz metin', d: -5 },
    ]
    const tsv = rowsToTsv(rows, ['a', 'b', 'c', 'd'])
    const [head, r1, r2] = tsv.split('\n')
    expect(head).toBe('a\tb\tc\td')
    expect(r1.split('\t')).toEqual(["'=cmd|x", "'+1+1", "'-2+3", "'@SUM(A1)"])
    // Sekme/CR ile başlayan hücre de nötr: önce tırnak, sonra sekme boşluğa — Excel'de metin kalır
    expect(r2.split('\t')).toEqual(["' =HYPERLINK(\"x\")", "' =1", 'düz metin', '-5'])
    // Başlık da (SQL takma adı) aynı kuraldan geçer
    expect(rowsToTsv([{ '=x': 1 }], ['=x']).split('\n')[0]).toBe("'=x")
  })

  it('küçük yardımcılar', () => {
    expect(shortType('character varying')).toBe('varchar')
    expect(shortType('timestamp without time zone')).toBe('timestamp')
    expect(tableQuery('teams')).toBe('SELECT *\nFROM teams\nLIMIT 100;')
    expect(formatDuration(840)).toBe('840 ms')
    expect(formatDuration(3200)).toBe('3.2 s')
    expect(offsetToLineCol('ab\ncd', 4)).toEqual({ line: 2, col: 2 })
    expect(wordAt('SELECT failz FROM t', 9)).toEqual([7, 12])
  })
})

describe('hata ayrıştırma', () => {
  const sql = '  SELECT fail FROM t'
  const executedSql = 'SELECT * FROM (SELECT fail FROM t) AS _capped LIMIT 1000'
  const pos = executedSql.indexOf('fail') + 1

  it('PostgreSQL gövdesi: özet, ipucu, konum → düzenleyici konumu (baştaki boşluk dâhil)', () => {
    const raw = `StatementCallback; bad SQL grammar [${executedSql}]; ERROR: column "fail" does not exist\n  Hint: Perhaps you meant "m.name".\n  Position: ${pos}`
    const info = parseSqlError(raw, { executedSql, sql })
    expect(info.kind).toBe('grammar')
    expect(info.summary).toBe('column "fail" does not exist')
    expect(info.hint).toBe('Perhaps you meant "m.name".')
    expect(info.position).toBe(pos)
    expect(info.editorOffset).toBe(sql.indexOf('fail'))
  })

  it('yorum içeren sorguda konum EŞLENMEZ (sunucu yorumları siler, kayma olur)', () => {
    const info = parseSqlError('ERROR: x\n  Position: 20', { executedSql, sql: '-- c\nSELECT fail FROM t' })
    expect(info.editorOffset).toBeNull()
  })

  it('gövdesiz Spring mesajı: özet yok (arayüz genel açıklama gösterir), tür grammar; koruma reddi olduğu gibi; zaman aşımı', () => {
    expect(parseSqlError(`StatementCallback; bad SQL grammar [${executedSql}]`, { executedSql, sql })).toMatchObject({ kind: 'grammar', summary: null })
    expect(parseSqlError('Sadece SELECT veya WITH ile başlayan sorgular çalıştırılabilir', { rejected: true }))
      .toMatchObject({ kind: 'guard', summary: 'Sadece SELECT veya WITH ile başlayan sorgular çalıştırılabilir' })
    expect(parseSqlError('ERROR: canceling statement due to statement timeout').kind).toBe('timeout')
  })
})

describe('sözdizimi boyası', () => {
  it('kayıpsız: parçaların birleşimi girdiyle aynı; türler doğru', () => {
    const src = "SELECT count(*), 'it''s' AS s -- not SELECT\nFROM \"Tbl\" WHERE id >= 10 AND x::int = $1 /* c */;"
    const tokens = tokenizeSql(src)
    expect(tokens.map((t) => t.text).join('')).toBe(src)
    const typeOf = (text) => tokens.find((t) => t.text === text)?.type
    expect(typeOf('SELECT')).toBe('keyword')
    expect(typeOf('count')).toBe('function')
    expect(typeOf("'it''s'")).toBe('string')
    expect(typeOf('-- not SELECT')).toBe('comment')
    expect(typeOf('"Tbl"')).toBe('quoted')
    expect(typeOf('10')).toBe('number')
    expect(typeOf('int')).toBe('type')
    expect(typeOf('$1')).toBe('param')
    expect(typeOf('/* c */')).toBe('comment')
  })
})

describe('ilişki diyagramı düzeni', () => {
  it('grafik: bilinmeyen tablo kenarı düşer; ilişkisizler ayrılır', () => {
    const g = buildGraph(DATA)
    expect(g.edges.some((e) => e.from === 'ghost')).toBe(false)
    expect(g.isolated).toEqual(['ldap_settings', 'smtp_settings'])
    expect(g.connected).toContain('app_users')
  })

  it('döngü kırma: karşılıklı başvuruda çok başvurulan tablo (teams) kök kalır; kendine başvuru katmanlamaya girmez', () => {
    const g = buildGraph(DATA)
    const dag = acyclicEdges(g.connected, g.edges)
    expect(dag).toContainEqual({ from: 'app_users', to: 'teams' })
    expect(dag).not.toContainEqual({ from: 'teams', to: 'app_users' })
    expect(dag.some((e) => e.from === e.to)).toBe(false)
  })

  it('katmanlar: ebeveyn ÜSTTE — her kenarda (düşen hariç) çocuk katmanı ebeveynden büyük', () => {
    const g = buildGraph(DATA)
    const dag = acyclicEdges(g.connected, g.edges)
    const layers = assignLayers(g.connected, dag)
    const L = new Map(layers.flatMap((l, i) => l.map((n) => [n, i])))
    expect(layers[0]).toEqual(['teams'])
    for (const e of dag) expect(L.get(e.from)).toBeGreaterThan(L.get(e.to))
    expect(L.get('http_checks')).toBe(L.get('http_monitors') + 1)
  })

  it('yerleşim (TB): ebeveyn çocuğun üstünde, kartlar çakışmaz, ilişkisizler en altta ayrı grupta', () => {
    const m = computeDiagram(DATA, { mode: 'keys', direction: 'TB' })
    const at = new Map(m.nodes.map((n) => [n.name, n]))
    expect(at.get('teams').y + at.get('teams').h).toBeLessThan(at.get('http_monitors').y)
    expect(at.get('http_monitors').y + at.get('http_monitors').h).toBeLessThan(at.get('http_checks').y)
    for (const a of m.nodes) for (const b of m.nodes) {
      if (a.name >= b.name) continue
      const overlap = a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h
      expect(overlap, `${a.name} × ${b.name}`).toBe(false)
    }
    const lowestRelated = Math.max(...m.nodes.filter((n) => !n.isolated).map((n) => n.y + n.h))
    expect(at.get('smtp_settings').isolated).toBe(true)
    expect(at.get('smtp_settings').y).toBeGreaterThan(lowestRelated)
    expect(m.isolatedLabel.y).toBeGreaterThan(lowestRelated)
    expect(m.width).toBeGreaterThan(0)
    expect(m.stats).toMatchObject({ tables: 8, isolated: 2, edges: 8, real: 1, inferred: 7 })
  })

  it('yerleşim (LR): ebeveyn çocuğun SOLUNDA; ilişkisizler gizlenebilir', () => {
    const m = computeDiagram(DATA, { mode: 'names', direction: 'LR', includeIsolated: false })
    const at = new Map(m.nodes.map((n) => [n.name, n]))
    expect(at.get('teams').x + at.get('teams').w).toBeLessThan(at.get('http_monitors').x)
    expect(at.has('smtp_settings')).toBe(false)
    expect(m.isolatedLabel).toBeNull()
  })

  it('kart satırları: anahtar kipinde PK (başvurulan) + FK satırları; kenar FK satırından PK satırına çapalanır', () => {
    const g = buildGraph(DATA)
    const alerts = nodeRows('alerts', g, { mode: 'keys' })
    expect(alerts.rows.map((r) => `${r.kind}:${r.name}`)).toEqual(['fk:incident_id', 'fk:team_id'])
    const teams = nodeRows('teams', g, { mode: 'keys' })
    expect(teams.rows[0]).toMatchObject({ kind: 'pk', name: 'id' })
    expect(cardHeight(alerts)).toBeGreaterThan(HEADER_H)
    const m = computeDiagram(DATA, { mode: 'keys', direction: 'TB' })
    const edge = m.edges.find((e) => e.from === 'alerts' && e.column === 'team_id')
    const a = m.nodes.find((n) => n.name === 'alerts')
    const t = m.nodes.find((n) => n.name === 'teams')
    expect(edge.sy).toBe(a.y + rowCenter(1))
    expect(edge.ty).toBe(t.y + rowCenter(0))
    expect(edge.d).toMatch(/^M [\d.]+ [\d.]+ C /)
    expect(m.edges.find((e) => e.self)).toBeTruthy()
  })

  it('tüm kolonlar kipi: uzun tablo katlanır, açılınca tamamı + "daha az" satırı', () => {
    const g = buildGraph(DATA)
    const cols = { alerts: Array.from({ length: 14 }, (_, i) => ({ column_name: i === 0 ? 'id' : `c${i}`, data_type: 'text', is_nullable: 'YES' })) }
    const folded = nodeRows('alerts', g, { mode: 'columns', columns: cols })
    expect(folded.rows).toHaveLength(COLLAPSE_AT)
    expect(folded.more).toBe(14 - COLLAPSE_AT)
    expect(folded.rows[0]).toMatchObject({ kind: 'pk', name: 'id' })
    const open = nodeRows('alerts', g, { mode: 'columns', columns: cols, expanded: new Set(['alerts']) })
    expect(open.rows).toHaveLength(14)
    expect(open.collapsible).toBe(true)
    expect(cardHeight(open)).toBe(cardHeight({ rows: open.rows, more: 1 }))
    expect(nodeRows('teams', g, { mode: 'columns', columns: {} }).partial).toBe(true)
  })

  it('komşuluk: seçilen + doğrudan başvurduğu + ona başvuranlar', () => {
    const g = buildGraph(DATA)
    expect([...neighbourhood(g, 'http_monitors')].sort()).toEqual(['http_checks', 'http_monitors', 'teams'])
    expect(neighbourhood(g, 'nope')).toBeNull()
  })
})

describe('SVG dışa aktarımı', () => {
  it('bağımsız, kaçışlı, temalı belge: her kart ve kenar, işaretçiler, başlık', () => {
    const m = computeDiagram({ tables: ['a<b', 'c'], edges: [E('c', 'ab_id', 'a<b')] }, { mode: 'keys', rowCounts: { c: 1234 } })
    const svg = buildDiagramSvg(m, { palette: { background: '#010203' }, title: 'T & R', rowsLabel: (n) => (n == null ? '' : `${n} rows`), unrelatedLabel: 'U' })
    expect(svg.startsWith('<svg xmlns="http://www.w3.org/2000/svg"')).toBe(true)
    expect(svg).toContain('<title>T &amp; R</title>')
    expect(svg).toContain('a&lt;b')
    expect(svg).not.toContain('a<b')
    expect(svg).toContain('fill="#010203"')
    expect(svg).toContain('marker-end="url(#dg-arrow)"')
    expect(svg).toContain('marker-start="url(#dg-many)"')
    expect(svg).toContain('stroke-dasharray="5 4"')
    expect(svg).toContain('1234 rows')
    expect((svg.match(/<g transform=/g) || []).length).toBe(2)
    expect(svg.trim().endsWith('</svg>')).toBe(true)
    expect(xmlEscape(`"'&`)).toBe('&quot;&#39;&amp;')
  })

  it('PNG ölçeği: küçükte 2×, çok büyükte ~24 MP tavanı', () => {
    expect(pngScale(800, 600)).toBe(2)
    expect(pngScale(8000, 6000)).toBeLessThan(1)
    expect(pngScale(8000, 6000)).toBeGreaterThanOrEqual(0.5)
  })
})
