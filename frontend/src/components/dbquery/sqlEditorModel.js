// SQL düzenleyicisinin SAF yardımcıları (2026-10-31): satır/sütun ↔ konum, girinti / girinti azaltma, otomatik girinti.
// Hepsi tek geçişli (doğrusal) — 10 000 karakterlik sorguda bile anlık.

export const MAX_SQL_CHARS = 10_000
export const INDENT = '  '

/** Bilinen ihlal kodları — sunucu `DbQuerySqlGuard.CODES` ile aynı sıra; bilinmeyen kod genel metne düşer. */
export const GUARD_CODES = [
  'SQL_EMPTY', 'SQL_TOO_LONG', 'SQL_CONTROL_CHAR', 'SQL_UNTERMINATED', 'SQL_MULTIPLE_STATEMENTS', 'SQL_NOT_SELECT',
  'SQL_FORBIDDEN_KEYWORD', 'SQL_LOCKING_CLAUSE', 'SQL_INTO', 'SQL_FORBIDDEN_FUNCTION', 'SQL_LOCK_HINT',
  'SQL_MSSQL_NOLOCK_REQUIRED', 'SQL_UNPARSABLE', 'SQL_REMOTE_OBJECT',
]
export const isKnownGuardCode = (code) => GUARD_CODES.includes(code)

/** Sunucu `DbQuerySqlGuard.TOKEN_TIMEOUT` — ayrıştırma zaman sınırı (sorgu reddedilmedi, şimdilik denetlenemedi). */
export const GUARD_TIMEOUT_TOKEN = 'TIMEOUT'

/**
 * Sunucu `DbQuerySqlGuard.isTimeout` aynası: TEK ihlal, kod `SQL_UNPARSABLE`, belirteç `TIMEOUT` → "denetlenemedi"
 * (nötr) — asla "reddedildi" (kırmızı) değil, asla "güvenli" (yeşil) değil.
 */
export function isGuardTimeout(violations) {
  return Array.isArray(violations) && violations.length === 1
    && violations[0]?.code === 'SQL_UNPARSABLE' && violations[0]?.token === GUARD_TIMEOUT_TOKEN
}

/** 1 tabanlı (satır, sütun) → metindeki konum; sınır dışı değerler kenara yapışır. */
export function offsetOf(text, line, column) {
  const s = String(text || '')
  const ln = Math.max(1, Number(line) || 1)
  let pos = 0
  for (let i = 1; i < ln; i++) {
    const nl = s.indexOf('\n', pos)
    if (nl === -1) return s.length
    pos = nl + 1
  }
  const end = s.indexOf('\n', pos)
  const lineEnd = end === -1 ? s.length : end
  return Math.min(lineEnd, pos + Math.max(0, (Number(column) || 1) - 1))
}

/** Konum → 1 tabanlı satır. */
export function lineOf(text, offset) {
  const s = String(text || '')
  let n = 1
  const lim = Math.min(Math.max(0, offset), s.length)
  for (let i = 0; i < lim; i++) if (s.charCodeAt(i) === 10) n++
  return n
}

/** Satır sayısı (boş metin = 1 satır). */
export function lineCount(text) {
  const s = String(text || '')
  let n = 1
  for (let i = 0; i < s.length; i++) if (s.charCodeAt(i) === 10) n++
  return n
}

/** Seçimin kapsadığı satırların başlangıç konumları [ilkSatırBaşı, sonSatırSonu]. */
function lineSpan(s, start, end) {
  const a = s.lastIndexOf('\n', Math.max(0, start - 1)) + 1
  // seçim bir satırın BAŞINDA bitiyorsa o satır dahil değil (çok satırlı seçimin olağan hâli)
  const effEnd = end > start && s[end - 1] === '\n' ? end - 1 : end
  const nl = s.indexOf('\n', effEnd)
  return [a, nl === -1 ? s.length : nl]
}

/**
 * Tab / Shift+Tab: seçim yoksa (ya da tek satırdaysa ve Shift yoksa) imlece iki boşluk; çok satırlı seçimde her satır
 * girintilenir / girintisi azaltılır. Döner: { value, start, end }.
 */
export function indent(text, start, end, outdent = false) {
  const s = String(text || '')
  const multi = s.slice(start, end).includes('\n')
  if (!outdent && !multi) {
    return { value: s.slice(0, start) + INDENT + s.slice(end), start: start + INDENT.length, end: start + INDENT.length }
  }
  const [a, b] = lineSpan(s, start, end)
  const block = s.slice(a, b).split('\n')
  let firstDelta = 0
  let total = 0
  const out = block.map((ln, i) => {
    if (outdent) {
      const n = ln.startsWith(INDENT) ? INDENT.length : (ln.startsWith(' ') || ln.startsWith('\t') ? 1 : 0)
      if (i === 0) firstDelta = -n
      total -= n
      return ln.slice(n)
    }
    if (i === 0) firstDelta = INDENT.length
    total += INDENT.length
    return INDENT + ln
  })
  const value = s.slice(0, a) + out.join('\n') + s.slice(b)
  const newStart = Math.max(a, start + firstDelta)
  const newEnd = multi ? Math.max(newStart, end + total) : Math.max(newStart, end + firstDelta)
  return { value, start: newStart, end: newEnd }
}

/** Enter: yeni satır + bulunulan satırın baştaki boşlukları (otomatik girinti). */
export function newlineWithIndent(text, start, end) {
  const s = String(text || '')
  const lineStart = s.lastIndexOf('\n', Math.max(0, start - 1)) + 1
  let i = lineStart
  while (i < start && (s[i] === ' ' || s[i] === '\t')) i++
  const ins = '\n' + s.slice(lineStart, i)
  return { value: s.slice(0, start) + ins + s.slice(end), start: start + ins.length, end: start + ins.length }
}
