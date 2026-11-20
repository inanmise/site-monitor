import { LIMITS } from './techErrorModel.js'

/**
 * EXCEL'DEN TABLOYA YAPIŞTIRMA (2026-11-20, C5): odaklı hücreye yapıştırılan sekmeli / satırlı metin (Excel, Google E-Tablolar,
 * raporlama aracının tablosu) o hücreden başlayarak satır satır doldurulur. Saf işlevler — tablo sınamalı.
 *
 * Kural: Excel tek hücreyi de sonunda bir satır sonuyla kopyalar — SONDAKİ TEK satır sonu atılır. Ondan sonra metinde sekme
 * ya da satır sonu YOKSA bu ızgara değildir (bugünkü davranış: tarayıcı değeri hücreye yapıştırır). Tırnaklı alan (Excel
 * hücre içinde sekme / satır sonu / tırnak taşıyorsa `"…"` ile yazar, içteki tırnak `""`) desteklenir. Tarama doğrusaldır ve
 * en çok {@link PASTE_MAX_CHARS} karakter okunur; tablonun dışına düşen DOLU değerler sayılır.
 */

export const PASTE_MAX_ROWS = LIMITS.rows
export const PASTE_MAX_COLS = LIMITS.columns
export const PASTE_MAX_CHARS = 200_000

/** Sondaki tek satır sonunu (`\r\n`, `\n`, `\r`) atar. */
export function dropTrailingNewline(text) {
  const s = String(text ?? '')
  if (s.endsWith('\r\n')) return s.slice(0, -2)
  if (s.endsWith('\n') || s.endsWith('\r')) return s.slice(0, -1)
  return s
}

/** Yapıştırılan metin bir ızgara mı (sondaki satır sonu atıldıktan sonra sekme ya da satır sonu içeriyor mu)? */
export function isGridText(text) {
  const s = dropTrailingNewline(text)
  return /[\t\r\n]/.test(s)
}

/**
 * Sekmeli metni satırlara ayırır (doğrusal; tırnaklı alanlar). Dönüş `{ rows: string[][], cut }` — `cut`: metin
 * {@link PASTE_MAX_CHARS}'tan uzundu, kalan okunmadı. Her hücre kırpılır (`trim`).
 */
export function parseGrid(text) {
  const src = dropTrailingNewline(text)
  const n = Math.min(src.length, PASTE_MAX_CHARS)
  const cut = src.length > PASTE_MAX_CHARS
  const rows = []
  let row = []
  let field = ''
  let start = true          // alanın başında mıyız (tırnak yalnız başta açar)
  let i = 0
  const endField = () => { row.push(field.trim()); field = ''; start = true }
  const endRow = () => { endField(); rows.push(row); row = [] }
  while (i < n) {
    const ch = src[i]
    if (start && ch === '"') {
      // Tırnaklı alan: kapanış tırnağına kadar (içteki "" → ")
      i++
      let closed = false
      while (i < n) {
        const c = src[i]
        if (c === '"') {
          if (src[i + 1] === '"') { field += '"'; i += 2; continue }
          i++
          closed = true
          break
        }
        field += c
        i++
      }
      start = false
      if (!closed) break
      continue
    }
    start = false
    if (ch === '\t') { endField(); i++; continue }
    if (ch === '\r') { endRow(); i += src[i + 1] === '\n' ? 2 : 1; continue }
    if (ch === '\n') { endRow(); i++; continue }
    field += ch
    i++
  }
  endRow()
  return { rows, cut }
}

/**
 * Izgarayı tabloya yerleştirir: odaklı hücre (`startRow`, `startCol` — sıra numarası) sol üst köşe, satırlar ve sütunlar
 * şablon sırasıyla. Dönüş `{ cells: [{ rowId, colId, raw }], outside }` — `outside`: tablonun dışına düşen DOLU değer sayısı
 * ({@link PASTE_MAX_ROWS} × {@link PASTE_MAX_COLS} üstü de dışarıda sayılır).
 */
export function planTablePaste(grid, rowIds, colIds, startRow, startCol) {
  const cells = []
  let outside = 0
  const rIds = rowIds || []
  const cIds = colIds || []
  const rows = grid || []
  for (let r = 0; r < rows.length; r++) {
    const line = rows[r] || []
    for (let c = 0; c < line.length; c++) {
      const raw = line[c] ?? ''
      const ri = startRow + r
      const ci = startCol + c
      const inside = r < PASTE_MAX_ROWS && c < PASTE_MAX_COLS && ri >= 0 && ri < rIds.length && ci >= 0 && ci < cIds.length
      if (!inside) {
        if (raw !== '') outside++
        continue
      }
      cells.push({ rowId: rIds[ri], colId: cIds[ci], raw })
    }
  }
  return { cells, outside }
}
