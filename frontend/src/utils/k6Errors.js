/**
 * k6 hata metinlerinden SATIR:SÜTUN çıkarımı — editör cetvelinde hata satırını işaretlemek için.
 *
 * Desen backend'le AYNI: `ScriptedCheckerService.K6_LINE_COL`. İki biçim var:
 *   1. k6 yığın izi                     → `at script:34:12(24)`  (`sanitizeScriptPath` temp yolu `script`e
 *                                         çevirdiği için bu biçim oluşur)
 *   2. k6 2.x (sobek, 2026-10-29 ölçüldü) → `script: Line 4:1 Unexpected token ; (and 1 more errors)`
 * (0.49'un Babel biçimi `Unexpected token (46:29)` 2026-10-30'da kaldırıldı — imajda yalnız 2.x var.)
 *
 * Backend bu bilgiyi ayrı bir alan olarak DÖNDÜRMÜYOR — yalnız insan-okur metnin içinde geçiyor
 * (kaydetme hatası HTTP 400 gövdesinde `error`, uyarılar `warnings[]`, koşum hatası `error`).
 * Bu yüzden ayrıştırma frontend'de, tek ve saf bir yerde yapılır.
 */

/** `g` bayrağı: `matchAll` için şart. Modül düzeyinde `lastIndex` taşımasın diye her çağrıda yeni. */
function pattern() {
  return /script:(\d+):(\d+)|\bLine (\d+):(\d+)/g
}

/**
 * @param {string} text  k6 hata/uyarı metni (çok satırlı kod çerçevesi olabilir)
 * @param {'error'|'warning'} type
 * @returns {Array<{line:number, column:number, type:string, message:string}>}
 *   Satır başına TEK marker (ilk eşleşme kazanır); metin yoksa boş dizi.
 */
export function parseK6Markers(text, type = 'error') {
  if (typeof text !== 'string' || !text) return []
  const out = new Map()
  for (const m of text.matchAll(pattern())) {
    const line = Number(m[1] ?? m[3])
    const column = Number(m[2] ?? m[4])
    // 0 ya da negatif satır anlamsız; aynı satır ikinci kez geçerse ilkini koru.
    if (!Number.isFinite(line) || line < 1 || out.has(line)) continue
    out.set(line, { line, column: Number.isFinite(column) ? column : 0, type, message: text.trim() })
  }
  return [...out.values()]
}

/**
 * Birden çok kaynaktan gelen metinleri tek marker listesine indirger.
 * Kaynaklar: kaydetme hatası (engelleyen), kaydetme uyarıları, son test koşumunun hatası.
 * Aynı satır birden çok kaynakta geçerse ÖNCE gelen (daha şiddetli) kazanır.
 */
export function collectK6Markers({ saveError, warnings = [], runError } = {}) {
  const out = new Map()
  const push = (list) => { for (const m of list) if (!out.has(m.line)) out.set(m.line, m) }
  push(parseK6Markers(saveError, 'error'))
  for (const w of warnings || []) push(parseK6Markers(w, 'warning'))
  push(parseK6Markers(runError, 'error'))
  return [...out.values()]
}
