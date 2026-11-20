import { describe, it, expect } from 'vitest'
import {
  PASTE_MAX_CHARS, PASTE_MAX_COLS, PASTE_MAX_ROWS, dropTrailingNewline, isGridText, parseGrid, planTablePaste,
} from '../components/techerror/tablePasteModel.js'
import { LIMITS } from '../components/techerror/techErrorModel.js'

// Excel'den tabloya yapıştırma (2026-11-20, C5): sekmeli / satırlı metin odaklı hücreden başlayarak satır satır yerleşir;
// tek değer bugünkü gibi hücreye yapışır; tablonun dışına düşen değerler sayılır.

describe('isGridText', () => {
  it.each([
    ['1250', false],
    ['1250\n', false],                // Excel tek hücreyi de sonunda satır sonuyla kopyalar
    ['1250\r\n', false],
    ['1,25M', false],
    ['1\t2', true],
    ['1\n2', true],
    ['1\r\n2\r\n', true],
    ['', false],
    [null, false],
  ])('%j → %s', (text, want) => {
    expect(isGridText(text)).toBe(want)
  })
  it('dropTrailingNewline yalnız SONDAKİ TEK satır sonunu atar', () => {
    expect(dropTrailingNewline('a\n\n')).toBe('a\n')
    expect(dropTrailingNewline('a\r\n')).toBe('a')
    expect(dropTrailingNewline('a\r')).toBe('a')
    expect(dropTrailingNewline('a')).toBe('a')
  })
})

describe('parseGrid', () => {
  it('sekme + satır sonu (LF, CRLF, CR), hücreler kırpılır, sondaki satır sonu atılır', () => {
    expect(parseGrid('1\t2\n3\t4\n').rows).toEqual([['1', '2'], ['3', '4']])
    expect(parseGrid(' 1 \t 2 \r\n3\t4').rows).toEqual([['1', '2'], ['3', '4']])
    expect(parseGrid('1\r2').rows).toEqual([['1'], ['2']])
  })
  it('boş hücreler korunur (yer tutucu)', () => {
    expect(parseGrid('1\t\t3\n\t5\t').rows).toEqual([['1', '', '3'], ['', '5', '']])
  })
  it('tırnaklı alan: içinde sekme / satır sonu / "" kaçışı', () => {
    expect(parseGrid('"a\tb"\t"c\nd"\t"e ""f"""').rows).toEqual([['a\tb', 'c\nd', 'e "f"']])
    // Ortadaki tırnak alan başlatmaz
    expect(parseGrid('a"b\tc').rows).toEqual([['a"b', 'c']])
  })
  it('kapanmamış tırnak: okunan kadarı alınır, döngü sonlanır', () => {
    expect(parseGrid('"açık\tmetin').rows).toEqual([['açık\tmetin']])
  })
  it(`en çok ${PASTE_MAX_CHARS} karakter okunur (cut)`, () => {
    const big = 'x'.repeat(PASTE_MAX_CHARS + 10)
    const res = parseGrid(big)
    expect(res.cut).toBe(true)
    expect(res.rows[0][0]).toHaveLength(PASTE_MAX_CHARS)
  })
  it('doğrusal: 200 K karakterlik sekmeli metin hızlı ayrıştırılır', () => {
    const line = Array.from({ length: 10 }, (_, i) => `${i}`).join('\t')
    const text = Array.from({ length: 20000 }, () => line).join('\n').slice(0, PASTE_MAX_CHARS)
    const t0 = performance.now()
    const res = parseGrid(text)
    expect(performance.now() - t0).toBeLessThan(2000)
    expect(res.rows.length).toBeGreaterThan(1000)
  })
})

describe('planTablePaste', () => {
  const ROWS = ['r1', 'r2', 'r3']
  const COLS = ['c1', 'c2']
  it('odaklı hücre sol üst köşe; sığan hücreler satır / sütun kimliğiyle', () => {
    const { cells, outside } = planTablePaste([['1', '2'], ['3', '4']], ROWS, COLS, 1, 0)
    expect(cells).toEqual([
      { rowId: 'r2', colId: 'c1', raw: '1' }, { rowId: 'r2', colId: 'c2', raw: '2' },
      { rowId: 'r3', colId: 'c1', raw: '3' }, { rowId: 'r3', colId: 'c2', raw: '4' },
    ])
    expect(outside).toBe(0)
  })
  it('tablonun dışına düşen DOLU değerler sayılır (boşlar sayılmaz)', () => {
    const res = planTablePaste([['1', '2', '3'], ['4', '', ''], ['7', '8', '9']], ROWS, COLS, 1, 1)
    expect(res.cells).toEqual([{ rowId: 'r2', colId: 'c2', raw: '1' }, { rowId: 'r3', colId: 'c2', raw: '4' }])
    // dışarıda: 2, 3 (satır 1), 7, 8, 9 (satır 3) — 5 dolu değer
    expect(res.outside).toBe(5)
  })
  it('boş hücre de yerleşir (hücreyi temizler)', () => {
    expect(planTablePaste([['', '5']], ROWS, COLS, 0, 0).cells).toEqual([
      { rowId: 'r1', colId: 'c1', raw: '' }, { rowId: 'r1', colId: 'c2', raw: '5' },
    ])
  })
  it(`sınır ${PASTE_MAX_ROWS} satır × ${PASTE_MAX_COLS} sütun (şablon sınırları)`, () => {
    expect(PASTE_MAX_ROWS).toBe(LIMITS.rows)
    expect(PASTE_MAX_COLS).toBe(LIMITS.columns)
    const rowIds = Array.from({ length: PASTE_MAX_ROWS + 5 }, (_, i) => `r${i}`)
    const colIds = Array.from({ length: PASTE_MAX_COLS + 5 }, (_, i) => `c${i}`)
    const grid = Array.from({ length: PASTE_MAX_ROWS + 1 }, () => Array.from({ length: PASTE_MAX_COLS + 1 }, () => '1'))
    const res = planTablePaste(grid, rowIds, colIds, 0, 0)
    expect(res.cells).toHaveLength(PASTE_MAX_ROWS * PASTE_MAX_COLS)
    expect(res.outside).toBe((PASTE_MAX_ROWS + 1) * (PASTE_MAX_COLS + 1) - PASTE_MAX_ROWS * PASTE_MAX_COLS)
  })
})
