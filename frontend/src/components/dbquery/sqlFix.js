// Veritabanı Sorgu İzleme — "Tek tıkla güvenli düzeltme" (B2, 2026-11-05) SAF modeli.
// Sunucu önerileri: `POST /dbquery/sql-fix {sql, db_type, row_limit?}` → `{fixes:[{code, sql, changed, note}]}`
// (`ADD_NOLOCK` yalnız SQL Server, `ADD_ROW_LIMIT`); sunucu önerdiği SQL'i güvenlik denetiminden GEÇİRİR. İstemci ek
// önerisi: "SELECT * yerine kolonlar" — YALNIZ son "Dene"nin kolon ADLARI biliniyorsa ve sorgu tek tablolu basit
// `SELECT * FROM …` ise. Önizleme satır farkıdır (önce / sonra). Hepsi doğrusal ya da sınırlı (fark tablosu ≤ 200 000 hücre).

import { MAX_SQL_CHARS } from './sqlEditorModel.js'

/** Sunucunun bilinen düzeltme kodları + istemcinin kendi önerisi (`dbq.fix.code.<KOD>`). */
export const SERVER_FIX_CODES = Object.freeze(['ADD_NOLOCK', 'ADD_ROW_LIMIT'])
export const CLIENT_FIX_CODES = Object.freeze(['SELECT_COLUMNS'])
export const FIX_CODES = Object.freeze([...SERVER_FIX_CODES, ...CLIENT_FIX_CODES])

/** Sunucu yanıtı → yalnız tanınan kodlu, SQL'i olan ve ASIL sorgudan farklı öneriler (aynı kod bir kez). */
export function normalizeFixes(data, currentSql = '') {
  const list = Array.isArray(data?.fixes) ? data.fixes : []
  const seen = new Set()
  const out = []
  for (const f of list.slice(0, 10)) {
    if (!f || !SERVER_FIX_CODES.includes(f.code) || seen.has(f.code)) continue
    const sql = typeof f.sql === 'string' ? f.sql : ''
    if (!sql.trim() || sql === currentSql || sql.length > MAX_SQL_CHARS) continue
    seen.add(f.code)
    out.push({
      code: f.code, sql,
      changed: (Array.isArray(f.changed) ? f.changed : []).map((c) => String(c)).filter(Boolean).slice(0, 50),
      note: typeof f.note === 'string' && f.note.trim() ? f.note.trim() : null,
      source: 'server',
    })
  }
  return out
}

/** Satır farkının hücre sınırı — aşılırsa fark "tümü silindi / tümü eklendi" olarak gösterilir (sınırlı iş). */
export const DIFF_MAX_CELLS = 200_000

/**
 * İki metnin SATIR farkı → `[{ type: 'same' | 'del' | 'add', text }]` (en uzun ortak alt dizi). Tablo sınırı aşılırsa
 * kaba fark (eski satırlar silindi + yeni satırlar eklendi), asla donma yok.
 */
export function lineDiff(a, b) {
  const A = String(a ?? '').split('\n')
  const B = String(b ?? '').split('\n')
  // Ortak baş / son satırlar tablodan önce ayrılır (düzeltmeler çoğunlukla tek satır değiştirir)
  let head = 0
  while (head < A.length && head < B.length && A[head] === B[head]) head++
  let tail = 0
  while (tail < A.length - head && tail < B.length - head && A[A.length - 1 - tail] === B[B.length - 1 - tail]) tail++
  const a2 = A.slice(head, A.length - tail)
  const b2 = B.slice(head, B.length - tail)
  const out = A.slice(0, head).map((text) => ({ type: 'same', text }))
  const n = a2.length, m = b2.length
  if (n * m > DIFF_MAX_CELLS) {
    for (const text of a2) out.push({ type: 'del', text })
    for (const text of b2) out.push({ type: 'add', text })
  } else {
    // dp[i][j] = a2[i..] ile b2[j..] arasındaki en uzun ortak alt dizi uzunluğu (düz dizi, (n+1)·(m+1) hücre)
    const w = m + 1
    const dp = new Uint32Array((n + 1) * w)
    for (let i = n - 1; i >= 0; i--) {
      for (let j = m - 1; j >= 0; j--) {
        dp[i * w + j] = a2[i] === b2[j] ? dp[(i + 1) * w + j + 1] + 1 : Math.max(dp[(i + 1) * w + j], dp[i * w + j + 1])
      }
    }
    let i = 0, j = 0
    while (i < n && j < m) {
      if (a2[i] === b2[j]) { out.push({ type: 'same', text: a2[i] }); i++; j++ }
      else if (dp[(i + 1) * w + j] >= dp[i * w + j + 1]) { out.push({ type: 'del', text: a2[i] }); i++ }
      else { out.push({ type: 'add', text: b2[j] }); j++ }
    }
    while (i < n) out.push({ type: 'del', text: a2[i++] })
    while (j < m) out.push({ type: 'add', text: b2[j++] })
  }
  for (const text of A.slice(A.length - tail)) out.push({ type: 'same', text })
  return out
}

/** Farkta değişen satır sayıları (önizleme özeti). */
export function diffStats(diff) {
  let add = 0, del = 0
  for (const d of diff || []) { if (d.type === 'add') add++; else if (d.type === 'del') del++ }
  return { add, del }
}

// ── İstemci önerisi: SELECT * → kolonlar ─────────────────────────────────────────────────────────────────────────────

/**
 * Dizge, tırnaklı ad ve yorumları BOŞLUKLA maskeler (konumlar korunur) — sözcük aramaları onların içine bakmaz.
 * Doğrusal tek geçiş; kapanmamış dizge / yorum sonuna kadar maskelenir.
 */
export function maskSqlLiterals(sql) {
  const s = String(sql ?? '')
  const out = s.split('')
  let i = 0
  const blank = (from, to) => { for (let k = from; k < to; k++) if (out[k] !== '\n') out[k] = ' ' }
  while (i < s.length) {
    const c = s[i], d = s[i + 1]
    if (c === '-' && d === '-') { const e = s.indexOf('\n', i); const end = e < 0 ? s.length : e; blank(i, end); i = end; continue }
    if (c === '/' && d === '*') { const e = s.indexOf('*/', i + 2); const end = e < 0 ? s.length : e + 2; blank(i, end); i = end; continue }
    if (c === "'" || c === '"' || c === '[') {
      const close = c === '[' ? ']' : c
      let j = i + 1
      while (j < s.length) {
        if (s[j] === close) { if (close !== ']' && s[j + 1] === close) { j += 2; continue } break }
        j++
      }
      const end = Math.min(s.length, j + 1)
      // Tırnaklı AD (çift tırnak / köşeli) sözcük olarak kalsın diye yer tutucu harfle dolar; dizge boşlukla
      if (c === "'") blank(i, end)
      else for (let k = i; k < end; k++) out[k] = 'x'
      i = end
      continue
    }
    i++
  }
  return out.join('')
}

const SIMPLE_IDENT = /^[A-Za-z_][A-Za-z0-9_$#]*$/
const STAR_HEAD = /^(\s*)SELECT(\s+)\*(\s+)FROM\s/i
const FORBIDDEN_IN_STAR = /\b(JOIN|UNION|INTERSECT|EXCEPT|MINUS|APPLY|PIVOT|UNPIVOT)\b/i

/**
 * Tırnaksız yazılamayan sözcükler (PostgreSQL + Oracle + SQL standardı ayrılmış sözcükleri; büyük harf). Kolon adı bunlardan
 * biriyse tırnaklanır — tırnaksız `order` / `user` / `date` / `level` sözdizimi hatası ya da başka anlam verir (O3).
 */
const RESERVED = new Set(('ACCESS ADD ALL ALTER ANALYSE ANALYZE AND ANY ARRAY AS ASC ASYMMETRIC AUDIT AUTHORIZATION BETWEEN BINARY BOTH BY '
  + 'CASE CAST CHAR CHECK CLUSTER COLLATE COLLATION COLUMN COMMENT COMPRESS CONCURRENTLY CONNECT CONSTRAINT CREATE CROSS CURRENT '
  + 'CURRENT_CATALOG CURRENT_DATE CURRENT_ROLE CURRENT_SCHEMA CURRENT_TIME CURRENT_TIMESTAMP CURRENT_USER DATE DECIMAL DEFAULT '
  + 'DEFERRABLE DELETE DESC DISTINCT DO DROP ELSE END EXCEPT EXCLUSIVE EXISTS FALSE FETCH FILE FLOAT FOR FOREIGN FREEZE FROM FULL '
  + 'GRANT GROUP HAVING IDENTIFIED ILIKE IMMEDIATE IN INCREMENT INDEX INITIAL INITIALLY INNER INSERT INTEGER INTERSECT INTO IS '
  + 'ISNULL JOIN LATERAL LEADING LEFT LEVEL LIKE LIMIT LOCALTIME LOCALTIMESTAMP LOCK LONG MAXEXTENTS MINUS MLSLABEL MODE MODIFY '
  + 'NATURAL NOAUDIT NOCOMPRESS NOT NOTNULL NOWAIT NULL NUMBER OF OFFLINE OFFSET ON ONLINE ONLY OPTION OR ORDER OUTER OVERLAPS '
  + 'PCTFREE PLACING PRIMARY PRIOR PUBLIC RAW REFERENCES RENAME RESOURCE RETURNING REVOKE RIGHT ROW ROWID ROWNUM ROWS SELECT '
  + 'SESSION SESSION_USER SET SHARE SIMILAR SIZE SMALLINT SOME START SUCCESSFUL SYMMETRIC SYNONYM SYSDATE TABLE TABLESAMPLE THEN '
  + 'TO TRAILING TRIGGER TRUE UID UNION UNIQUE UPDATE USER USING VALIDATE VALUES VARCHAR VARCHAR2 VARIADIC VERBOSE VIEW WHEN '
  + 'WHENEVER WHERE WINDOW WITH').split(' '))

/** PostgreSQL tırnaksız adı küçük harfe, Oracle büyük harfe katlar — yalnız katlamayla AYNI kalan ad tırnaksız yazılır. */
const PG_PLAIN = /^[a-z_][a-z0-9_$]*$/
const ORACLE_PLAIN = /^[A-Z][A-Z0-9_$#]*$/

/**
 * Kolon adı → türün kuralıyla (O3): SQL Server HER ZAMAN köşeli ayraç; PostgreSQL yalnız küçük harfli sade ad tırnaksız
 * (`CustomerId` → `"CustomerId"`); Oracle yalnız büyük harfli sade ad tırnaksız (`created_at` → `"created_at"`); ayrılmış
 * sözcük her türde tırnaklı. Tür bilinmiyorsa sade ad dışında çift tırnak.
 */
export function quoteColumn(name, dbType) {
  const s = String(name)
  if (dbType === 'MSSQL') return `[${s.replace(/]/g, ']]')}]`
  const reserved = RESERVED.has(s.toUpperCase())
  const plain = dbType === 'POSTGRESQL' ? PG_PLAIN.test(s) : dbType === 'ORACLE' ? ORACLE_PLAIN.test(s) : SIMPLE_IDENT.test(s)
  if (plain && !reserved) return s
  return `"${s.replace(/"/g, '""')}"`
}

/**
 * "SELECT * yerine kolonlar" — `columns` son denemenin kolon ADLARI (satır verisi değil). Koşul: sorgu `SELECT * FROM`
 * ile başlar, TEK SELECT, TEK tablo (FROM'dan WHERE / GROUP / ORDER / sona dek virgül, JOIN, alt sorgu yok), birleşim yok,
 * 1–100 kolon. Değilse null.
 */
export function selectStarFix(sql, columns, dbType) {
  const text = String(sql ?? '')
  if (!text.trim() || text.length > MAX_SQL_CHARS) return null
  const names = (Array.isArray(columns) ? columns : []).map((c) => (typeof c === 'string' ? c : c?.name)).filter((n) => typeof n === 'string' && n)
  if (names.length === 0 || names.length > 100 || new Set(names).size !== names.length) return null
  const masked = maskSqlLiterals(text)
  const head = STAR_HEAD.exec(masked)
  if (!head) return null
  const upper = masked.toUpperCase()
  if (FORBIDDEN_IN_STAR.test(masked)) return null
  // tek SELECT (alt sorgu yok)
  if (/\bSELECT\b/i.test(masked.slice(head[0].length))) return null
  // FROM öbeği: WHERE / GROUP / ORDER / HAVING / LIMIT / FETCH / OPTION'a ya da sona dek
  const fromStart = head[0].length
  const rest = upper.slice(fromStart)
  const stops = [' WHERE ', ' GROUP ', ' ORDER ', ' HAVING ', ' LIMIT ', ' FETCH ', ' OPTION', ' OFFSET ', '\nWHERE', '\nGROUP', '\nORDER']
  let end = rest.length
  for (const w of stops) { const k = rest.indexOf(w); if (k >= 0 && k < end) end = k }
  const fromPart = rest.slice(0, end)
  if (fromPart.includes(',') || fromPart.includes('(SELECT')) return null
  // yalnız "WITH (NOLOCK)" ipucu parantezine izin (başka parantez = tablo işlevi vb.)
  const parens = fromPart.replace(/WITH\s*\(\s*(NOLOCK|READUNCOMMITTED)\s*\)/g, '')
  if (parens.includes('(')) return null
  const starAt = head[1].length + 'SELECT'.length + head[2].length
  const list = names.map((n) => quoteColumn(n, dbType)).join(', ')
  const fixed = text.slice(0, starAt) + list + text.slice(starAt + 1)
  if (fixed.length > MAX_SQL_CHARS) return null
  return { code: 'SELECT_COLUMNS', sql: fixed, changed: names.slice(0, 50), note: null, source: 'client' }
}

/** Önizlemedeki uyarı kodları (`dbq.fix.warn.<KOD>`): satır sınırı satır sayısı kuralını etkiler. */
export function fixWarnings(fix, rules) {
  const out = []
  const list = Array.isArray(rules) ? rules : []
  if (fix?.code === 'ADD_ROW_LIMIT' && list.some((r) => r?.kind === 'ROW_COUNT')) out.push('ROW_COUNT_AFFECTED')
  // İstemci önerisi sunucunun denetiminden geçmedi: kolon adları son denemeden — uygulandıktan sonra sorgu yeniden denenmeli (O3)
  if (fix?.code === 'SELECT_COLUMNS') out.push('COLUMNS_FROM_TEST')
  return out
}

/** Guard ihlalleri arasında sunucunun düzeltebileceği var mı (NOLOCK eksikliği). */
export function hasFixableViolation(violations) {
  return (Array.isArray(violations) ? violations : []).some((v) => v?.code === 'SQL_MSSQL_NOLOCK_REQUIRED')
}

const ROW_LIMIT_WORDS = /\b(LIMIT|TOP|ROWNUM)\b|\bFETCH\s+(FIRST|NEXT)\b/i
const AGGREGATE_HEAD = /^\s*SELECT\s+(COUNT|COUNT_BIG|SUM|AVG|MIN|MAX)\s*\(/i
const GROUP_BY = /\bGROUP\s+BY\b/i

/**
 * Satır sınırı önerisi anlamlı mı (yalnız "Düzeltme önerileri" düğmesini göstermek için — kararı sunucu verir): sorguda
 * sınır sözcüğü yok ve tek satırlık özet (GROUP BY'sız `SELECT COUNT(…)` / `SUM(…)` …) değil.
 */
export function rowLimitSuggestable(sql) {
  const text = String(sql ?? '')
  if (!text.trim() || text.length > MAX_SQL_CHARS) return false
  const masked = maskSqlLiterals(text)
  if (ROW_LIMIT_WORDS.test(masked)) return false
  return !(AGGREGATE_HEAD.test(masked) && !GROUP_BY.test(masked))
}

/** Sunucunun değerlendirip ÖNERMEDİĞİ düzeltmelerin nedenleri (`DbQuerySqlFix.REASONS`; `dbq.fix.reason.<NEDEN>`). */
export const SKIP_REASONS = Object.freeze(['NOT_MSSQL', 'NOTHING_TO_FIX', 'OTHER_HINT', 'GUARD_STILL_FAILS', 'ALREADY_LIMITED',
  'NOT_LIMITABLE', 'UNPARSABLE', 'CHECK_TIMEOUT', 'SINGLE_ROW'])

/** Sunucunun "kısa süre bekle" kodları (sql-check / sql-fix; 429): hata değil, bekleme sonrası yeniden denenir. */
export const BUSY_CODES = Object.freeze(['DBQUERY_BUSY', 'RATE_LIMITED'])
/** Bekleme önerisi yoksa (`DBQUERY_BUSY` başlıkla `Retry-After: 1` döner, gövdede saniye yok) varsayılan bekleme, sn. */
export const BUSY_DEFAULT_WAIT_S = 2
/** Otomatik yeniden denemenin bekleme üst sınırı, sn (sunucu daha uzun söylese de düzenleyici en geç bu kadar sonra dener). */
export const BUSY_MAX_WAIT_S = 60

/**
 * 429 "kısa süre bekle" yanıtı mı → bekleme saniyesi (1–60), değilse null. `DBQUERY_BUSY` (eşzamanlı denetim dolu) ya da
 * `RATE_LIMITED` (kişi başı dakika sınırı); süre gövdenin `retry_after_seconds`'ından, yoksa varsayılan.
 */
export function busyWaitSeconds(res) {
  if (!res || typeof res !== 'object' || res.success === true) return null
  const status = Number(res.status ?? res.errorInfo?.status)
  const code = res.code ?? res.errorInfo?.code ?? null
  if (status !== 429 && !BUSY_CODES.includes(code)) return null
  if (status === 429 && code && !BUSY_CODES.includes(code)) return null
  const raw = Number(res.retry_after_seconds ?? res.retryAfter)
  const sec = Number.isFinite(raw) && raw > 0 ? Math.ceil(raw) : BUSY_DEFAULT_WAIT_S
  return Math.min(BUSY_MAX_WAIT_S, Math.max(1, sec))
}

/**
 * Sunucu yanıtının `skipped` listesi → gösterilecek öğeler (yalnız tanınan kod; neden tanınmazsa sunucunun notu, o da yoksa
 * atlanır). `NOT_MSSQL` / `NOTHING_TO_FIX` gürültüdür (zaten gereksiz) — gösterilmez.
 */
export function normalizeSkipped(data) {
  const list = Array.isArray(data?.skipped) ? data.skipped : []
  const out = []
  for (const s of list.slice(0, 10)) {
    if (!s || !SERVER_FIX_CODES.includes(s.code)) continue
    if (s.reason === 'NOT_MSSQL' || s.reason === 'NOTHING_TO_FIX') continue
    const reason = SKIP_REASONS.includes(s.reason) ? s.reason : null
    const note = typeof s.note === 'string' && s.note.trim() ? s.note.trim() : null
    if (!reason && !note) continue
    out.push({ code: s.code, reason, note })
  }
  return out
}
