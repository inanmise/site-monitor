import { describe, it, expect } from 'vitest'
import {
  buildChart, buildTables, cacheTone, compare, connModel, connStates, deadSummary, deadTone, errorKind, filterSort,
  fmtBytes, msTone, needsVacuum, seqHeavy, snippet, sqlState, pct, dec,
} from '../components/admin/dbanalytics/dbModel.js'
import { setDateLocale } from '../i18n/dateLocale.js'
import { niceCeil } from '../components/admin/dbanalytics/DbLoadChart.jsx'

/**
 * Veritabanı Analitiği saf türetimleri. Kural: okunamayan / tanımsız değer `null` → ekranda "Bilinmiyor"; hiçbir
 * türetim bilinmeyeni "sorun yok" (yeşil) ya da "0" yapmaz.
 */
describe('dbModel', () => {
  it('buildChart: sorgusuz kovada ortalama süre null (0 ms uydurulmaz); toplam, hata oranı, zirve', () => {
    const c = buildChart([
      { ts: '2026-09-24T21:00:00', count: 0, avg_ms: 0, failed: 0 },
      { ts: '2026-09-25T21:00:00', count: 120, avg_ms: 38, failed: 1 },
      { ts: '2026-09-26T21:00:00', count: 60, avg_ms: 420, failed: 90 },   // bozuk veri: failed > count kırpılır
    ], 'day')
    expect(c.points[0].avg).toBeNull()
    expect(c.points[1]).toMatchObject({ count: 120, failed: 1, ok: 119, avg: 38 })
    expect(c.points[2]).toMatchObject({ failed: 60, ok: 0 })
    expect(c.total).toBe(180)
    expect(c.errPct).toBe(33.9)
    expect(c.peak.count).toBe(120)
    expect(c.maxAvg).toBe(420)
    expect(c.withData).toBe(2)
    expect(c.empty).toBe(false)
    expect(buildChart([], 'hour')).toMatchObject({ empty: true, errPct: null, peak: null, maxAvg: 0 })
    expect(buildChart(null, 'hour').points).toEqual([])
  })

  it('buildChart: saatlik pencere HH:00, günlük DD.MM etiketi (yerel saat)', () => {
    const ts = '2026-09-25T21:00:00'
    const local = new Date(ts + 'Z')
    const hh = String(local.getHours()).padStart(2, '0')
    expect(buildChart([{ ts, count: 1 }], 'hour').points[0].label).toBe(`${hh}:00`)
    const dd = String(local.getDate()).padStart(2, '0'), mm = String(local.getMonth() + 1).padStart(2, '0')
    expect(buildChart([{ ts, count: 1 }], 'day').points[0].label).toBe(`${dd}.${mm}`)
    expect(buildChart([{ ts: 'bozuk', count: 1 }], 'day').points[0].label).toBe('bozuk')
  })

  it('connStates: sabit sıra, tanınmayan durum "diğer"e birleşir; yoksa null (bilinmiyor)', () => {
    const s = connStates({ states: [
      { state: 'idle', count: 5 }, { state: 'fastpath function call', count: 1 }, { state: 'active', count: 2 },
      { state: 'disabled', count: 1 }, { state: 'unknown', count: 0 },
    ] })
    expect(s.map((x) => x.state)).toEqual(['active', 'idle', 'other'])
    expect(s.find((x) => x.state === 'other').count).toBe(2)
    expect(s.reduce((a, x) => a + x.pct, 0)).toBeCloseTo(100)
    expect(connStates({})).toBeNull()
    expect(connStates(null)).toBeNull()
  })

  it('connModel: doluluk ve ton; max yoksa yüzde null (tahmin yok); eski sunucuda summary.active_connections', () => {
    expect(connModel({ connections: { active: 91, max: 100 } })).toMatchObject({ pct: 91, tone: 'danger' })
    expect(connModel({ connections: { active: 75, max: 100 } }).tone).toBe('warning')
    expect(connModel({ connections: { active: 5 } })).toMatchObject({ pct: null, tone: 'muted', states: null })
    expect(connModel({ summary: { active_connections: 4 }, connections: { max: 8 } })).toMatchObject({ active: 4, pct: 50 })
    expect(connModel({ connections: {} }).longThreshold).toBe(60)
  })

  it('buildTables: eski sunucuda okuma/yazma top_tables\'tan tamamlanır; yalnız kullanım listesindeki tablo da kaybolmaz', () => {
    const rows = buildTables({
      table_sizes: [{ table_name: 'a', total_size_bytes: 100, table_size_bytes: 60, index_size_bytes: 30 }, { table_name: 'b' }],
      top_tables: [{ table_name: 'a', reads: 9, writes: 2 }, { table_name: 'orphan', reads: 1, writes: 0, row_count: 3 }],
    })
    expect(rows.map((r) => r.table_name)).toEqual(['a', 'b', 'orphan'])
    expect(rows[0]).toMatchObject({ reads: 9, writes: 2, other_size_bytes: 10 })
    expect(rows[1].other_size_bytes).toBeNull()
    expect(buildTables({ table_sizes: [{ table_name: 'x', reads: 7 }], top_tables: [{ table_name: 'x', reads: 1 }] })[0].reads).toBe(7)
  })

  it('ölü satır: küçük tablo gürültü değildir; bakım eşiği oran + mutlak sayı; özet yoksa null', () => {
    expect(deadTone({ dead_pct: 50, dead_rows: 10 })).toBe('success')
    expect(deadTone({ dead_pct: 25, dead_rows: 5000 })).toBe('danger')
    expect(deadTone({ dead_pct: 12, dead_rows: 5000 })).toBe('warning')
    expect(deadTone({})).toBe('muted')
    expect(needsVacuum({ dead_pct: 25, dead_rows: 5000 })).toBe(true)
    expect(needsVacuum({ dead_pct: 25, dead_rows: 50 })).toBe(false)
    expect(deadSummary([{ row_count: 900, dead_rows: 100, dead_pct: 10 }, { row_count: 0, dead_rows: 0 }])).toEqual({ pct: 10, dead: 100, vacuum: 0 })
    expect(deadSummary([{ table_name: 'eski-sunucu' }])).toBeNull()
  })

  it('sıralı tarama uyarısı yalnız büyük ve çok okunan tabloda', () => {
    expect(seqHeavy({ idx_scan_pct: 10, row_count: 50_000, seq_scan: 5000 })).toBe(true)
    expect(seqHeavy({ idx_scan_pct: 10, row_count: 50, seq_scan: 5000 })).toBe(false)
    expect(seqHeavy({ idx_scan_pct: null, row_count: 50_000, seq_scan: 5000 })).toBe(false)
  })

  it('tonlar: bilinmeyen değer "muted" — yeşil değil', () => {
    expect(msTone(null)).toBe('muted')
    expect(msTone(600)).toBe('danger')
    expect(msTone(250)).toBe('warning')
    expect(msTone(10)).toBe('success')
    expect(cacheTone(null)).toBe('muted')
    expect(cacheTone(99.4)).toBe('success')
    expect(cacheTone(95)).toBe('warning')
    expect(cacheTone(80)).toBe('danger')
  })

  it('errorKind / sqlState: iletiden sınıf ve SQLSTATE (metin uydurulmaz)', () => {
    expect(errorKind('ERROR: canceling statement due to statement timeout')).toBe('timeout')
    expect(errorKind('ERROR: cannot execute UPDATE in a read-only transaction')).toBe('readonly')
    expect(errorKind('ERROR: permission denied for table app_user')).toBe('denied')
    expect(errorKind('ERROR: syntax error at or near "FORM"')).toBe('syntax')
    expect(errorKind('ERROR: relation "nope" does not exist')).toBe('missing')
    expect(errorKind('ERROR: division by zero')).toBe('other')
    expect(errorKind('')).toBeNull()
    expect(sqlState('ERROR: x; SQL state [42P01]; nested')).toBe('42P01')
    expect(sqlState('SQLSTATE: 57014')).toBe('57014')
    expect(sqlState('no code here')).toBeNull()
  })

  it('filterSort: arama küçük harf, boşlar her iki yönde de EN SONDA; girdi değişmez', () => {
    const rows = [{ n: 'b', v: 2 }, { n: 'A', v: null }, { n: 'c', v: 10 }]
    const cols = [{ key: 'v' }]
    expect(filterSort(rows, { q: 'a', keys: ['n'] }).map((r) => r.n)).toEqual(['A'])
    expect(filterSort(rows, { sort: { key: 'v', dir: 'desc' }, columns: cols }).map((r) => r.n)).toEqual(['c', 'b', 'A'])
    expect(filterSort(rows, { sort: { key: 'v', dir: 'asc' }, columns: cols }).map((r) => r.n)).toEqual(['b', 'c', 'A'])
    expect(rows.map((r) => r.n)).toEqual(['b', 'A', 'c'])
    expect(compare('table_2', 'table_10')).toBeLessThan(0)
    expect(compare('5', '40')).toBeLessThan(0)
  })

  it('fmtBytes: 1024 tabanı, birim adları pg_size_pretty ile aynı; boş → —', () => {
    expect(fmtBytes(512)).toBe('512 bytes')
    expect(fmtBytes(63_194_112)).toMatch(/^60[.,]3 MB$/)
    expect(fmtBytes(5 * 1024 ** 3)).toBe('5 GB')
    expect(fmtBytes(null)).toBe('—')
  })

  it('niceCeil: eksen tavanı okunur adıma yuvarlanır', () => {
    expect(niceCeil(405)).toBe(500)
    expect(niceCeil(210)).toBe(250)
    expect(niceCeil(38)).toBe(50)
    expect(niceCeil(0)).toBe(1)
  })

  it('snippet tek satıra indirger ve kırpar', () => {
    expect(snippet('SELECT\n  1\tFROM x', 48)).toBe('SELECT 1 FROM x')
    expect(snippet('x'.repeat(60), 10)).toBe('xxxxxxxxxx…')
    expect(snippet(null)).toBe('')
  })

  it('pct / dec: yüzde YEREL ondalıkla (TR "%99,5", EN "99.5%") — formatPercent sayıyı çıplak yazıyordu (2026-09-28c ek-7)', () => {
    try {
      setDateLocale('tr')
      expect(pct(99.5)).toBe('%99,5')
      expect(dec(97.25)).toBe('97,3')
      expect(pct(null)).toBe('—')
      expect(pct('')).toBe('—')
      setDateLocale('en')
      expect(pct(99.5)).toBe('99.5%')
      expect(dec(99.5)).toBe('99.5')
    } finally {
      setDateLocale('en')
    }
  })
})
