import { cellText, resultColumns } from './sqlUtils.js'

/**
 * Sonuç karşılaştırma (2026-11-13, C4) — aynı sekmedeki iki çalıştırma (sekmede tutulan son {@link KEEP_RUNS}).
 *
 * Anahtar: seçilen kolonlar (birincil anahtar biliniyorsa varsayılan o, yoksa `id`, yoksa TÜM SATIR). Anahtarla:
 * eklenen / silinen / değişen (hücre düzeyinde) / aynı. Tüm satırla: çoklu küme farkı — eklenen / silinen (değişen yok).
 * Anahtar tekrarlanıyorsa anahtarla karşılaştırma güvenilmez → tüm satıra düşülür (`fellBack`). Maskeli kolonlar
 * karşılaştırılmaz (`maskedSkipped`). Listeler ilk {@link SHOW_MAX} kayıtla döner, sayılar tamdır.
 */
export const KEEP_RUNS = 5
export const SHOW_MAX = 200
export const ROW_KEY = '__row__'

const val = (v) => (v == null ? null : cellText(v))
const keyOf = (row, cols) => JSON.stringify(cols.map((c) => val(row?.[c])))

/** Çalıştırmanın karşılaştırmaya giren kopyası (başarılı ve satırlı sonuçlar). */
export function snapshotRun(result) {
  if (!result || result.error || result.cancelled || !Array.isArray(result.rows)) return null
  return {
    seq: result.seq, at: result.finishedAt ?? Date.now(), sql: result.ranSql ?? '', rows: result.rows,
    cols: resultColumns(result.rows), masked: Array.isArray(result.masked_columns) ? result.masked_columns : [],
    rowCount: result.rowCount ?? result.rows.length,
  }
}

/** Sekmenin çalıştırma listesine ekler (en yeni sonda, en çok {@link KEEP_RUNS}). */
export function pushRun(runs, snap) {
  if (!snap) return runs || []
  const next = [...(runs || []), snap]
  return next.length > KEEP_RUNS ? next.slice(next.length - KEEP_RUNS) : next
}

/** Varsayılan anahtar: verilen PK kolonları (ikisinde de varsa), yoksa `id`, yoksa tüm satır. */
export function defaultKey(base, cur, pkCols = []) {
  const common = new Set((base?.cols || []).filter((c) => (cur?.cols || []).includes(c)))
  const masked = new Set([...(base?.masked || []), ...(cur?.masked || [])])
  const pk = (pkCols || []).filter((c) => common.has(c) && !masked.has(c))
  if (pk.length && pk.length === (pkCols || []).length) return pk
  if (common.has('id') && !masked.has('id')) return ['id']
  return [ROW_KEY]
}

/**
 * @param base  önceki çalıştırma ({@link snapshotRun})
 * @param cur   şimdiki çalıştırma
 * @param keyCols anahtar kolonları ya da `[ROW_KEY]`
 */
export function compareRuns(base, cur, keyCols) {
  const masked = new Set([...(base?.masked || []), ...(cur?.masked || [])])
  const baseCols = base?.cols || []
  const curCols = cur?.cols || []
  const common = baseCols.filter((c) => curCols.includes(c) && !masked.has(c))
  const out = {
    mode: 'key', key: [], fellBack: false, columns: common,
    addedCols: curCols.filter((c) => !baseCols.includes(c)), removedCols: baseCols.filter((c) => !curCols.includes(c)),
    maskedSkipped: [...masked].filter((c) => baseCols.includes(c) || curCols.includes(c)),
    added: [], removed: [], changed: [], counts: { added: 0, removed: 0, changed: 0, same: 0 },
  }
  const bRows = base?.rows || []
  const cRows = cur?.rows || []
  let key = (keyCols || []).filter((c) => c !== ROW_KEY && common.includes(c))
  if (key.length && (hasDuplicates(bRows, key) || hasDuplicates(cRows, key))) { key = []; out.fellBack = true }
  if (!key.length) {
    out.mode = 'row'
    const bag = new Map()
    for (const r of bRows) { const k = keyOf(r, common); bag.set(k, [...(bag.get(k) || []), r]) }
    for (const r of cRows) {
      const k = keyOf(r, common)
      const list = bag.get(k)
      if (list?.length) { list.pop(); out.counts.same++ } else { out.counts.added++; if (out.added.length < SHOW_MAX) out.added.push(r) }
    }
    for (const list of bag.values()) {
      for (const r of list) { out.counts.removed++; if (out.removed.length < SHOW_MAX) out.removed.push(r) }
    }
    return out
  }
  out.key = key
  const bMap = new Map(bRows.map((r) => [keyOf(r, key), r]))
  const seen = new Set()
  for (const r of cRows) {
    const k = keyOf(r, key)
    const b = bMap.get(k)
    if (!b) { out.counts.added++; if (out.added.length < SHOW_MAX) out.added.push(r); continue }
    seen.add(k)
    const diff = common.filter((c) => !key.includes(c) && val(b[c]) !== val(r[c]))
    if (diff.length) {
      out.counts.changed++
      if (out.changed.length < SHOW_MAX) out.changed.push({ key: key.map((c) => val(r[c])), before: b, after: r, cols: diff })
    } else out.counts.same++
  }
  for (const [k, r] of bMap) {
    if (!seen.has(k)) { out.counts.removed++; if (out.removed.length < SHOW_MAX) out.removed.push(r) }
  }
  return out
}

function hasDuplicates(rows, key) {
  const s = new Set()
  for (const r of rows) {
    const k = keyOf(r, key)
    if (s.has(k)) return true
    s.add(k)
  }
  return false
}
