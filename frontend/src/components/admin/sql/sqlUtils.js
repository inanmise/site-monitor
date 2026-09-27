import { toCsv } from '../../../utils/csvExport.js'
import { neutraliseFormula } from '../../../utils/csv.js'

/**
 * SQL Playground — saf yardımcılar (sunum yok, test edilebilir).
 *
 * Sunucu sözleşmesi (SqlPlaygroundService): tek SELECT/WITH ifadesi, sonuç en fazla MAX_ROWS satır
 * (dış sorgu `SELECT * FROM (…) AS _capped LIMIT 1000` ile sarılır), 30 sn sorgu zaman aşımı,
 * JDBC seviyesinde salt-okunur bağlantı. Buradaki sabitler yalnız arayüz metni/uyarısı içindir;
 * garanti sunucudadır.
 */
export const MAX_ROWS = 1000
export const QUERY_TIMEOUT_SEC = 30

/** Sunucunun sorguyu sardığı önek — hata konumunu düzenleyiciye geri eşlemek için. */
const CAP_PREFIX = 'SELECT * FROM ('

const ISO_DATE = /^\d{4}-\d{2}-\d{2}(?:[T ]\d{2}:\d{2}(?::\d{2}(?:\.\d+)?)?(?:Z|[+-]\d{2}:?\d{2})?)?$/

/** Hücre değerinin görünür metni: null → null (çağıran NULL rozeti çizer), nesne → JSON. */
export function cellText(v) {
  if (v == null) return null
  if (typeof v === 'object') {
    try { return JSON.stringify(v) } catch { return String(v) }
  }
  return String(v)
}

/**
 * Sütun tipi çıkarımı (ilk 200 değer): number · boolean · datetime · json · text · null (hepsi NULL).
 * Sunucu sonuç kümesinde tip bilgisi döndürmüyor (API boşluğu) — başlıktaki tip ipucu buradan gelir.
 */
export function inferType(values) {
  let seen = 0, num = 0, bool = 0, date = 0, json = 0
  let i = 0
  for (const v of values) {
    if (i++ >= 200) break
    if (v == null) continue
    seen++
    if (typeof v === 'number' || typeof v === 'bigint') num++
    else if (typeof v === 'boolean') bool++
    else if (typeof v === 'object') json++
    else if (typeof v === 'string' && ISO_DATE.test(v)) date++
  }
  if (!seen) return 'null'
  if (num === seen) return 'number'
  if (bool === seen) return 'boolean'
  if (date === seen) return 'datetime'
  if (json === seen) return 'json'
  return 'text'
}

/** Satırların sütun listesi — ilk satırın anahtar sırası, sonraki satırlarda yeni anahtar varsa sona. */
export function resultColumns(rows) {
  if (!rows?.length) return []
  const cols = Object.keys(rows[0])
  const seen = new Set(cols)
  for (let i = 1; i < rows.length && i < 50; i++) {
    for (const k of Object.keys(rows[i])) if (!seen.has(k)) { seen.add(k); cols.push(k) }
  }
  return cols
}

/** Sütun başına tip haritası. */
export function columnTypes(rows, cols) {
  const out = {}
  for (const c of cols) out[c] = inferType((rows || []).map((r) => r?.[c]))
  return out
}

/**
 * İstemci tarafı sıralama — NULL her yönde SONDA; sayı sayısal, tarih/metin doğal (numeric) karşılaştırma.
 * Kaynak dizi değişmez; eşit anahtarlar özgün sırayı korur.
 */
export function sortRows(rows, col, dir = 'asc', type = 'text') {
  if (!col || !rows?.length) return rows || []
  const sign = dir === 'desc' ? -1 : 1
  const collator = new Intl.Collator(undefined, { numeric: true, sensitivity: 'base' })
  return rows
    .map((r, i) => [r, i])
    .sort(([a, ia], [b, ib]) => {
      const va = a?.[col], vb = b?.[col]
      const na = va == null, nb = vb == null
      if (na || nb) return na === nb ? ia - ib : (na ? 1 : -1)
      let c
      if (type === 'number') c = Number(va) - Number(vb)
      else if (type === 'boolean') c = (va === vb ? 0 : va ? 1 : -1)
      else c = collator.compare(cellText(va), cellText(vb))
      return c === 0 ? ia - ib : c * sign
    })
    .map(([r]) => r)
}

/** Hızlı süzgeç — görünür sütunların metninde büyük/küçük harf duyarsız arama. */
export function filterRows(rows, cols, query) {
  const q = String(query || '').trim().toLocaleLowerCase()
  if (!q) return rows || []
  return (rows || []).filter((r) => cols.some((c) => {
    const s = cellText(r?.[c])
    return s != null && s.toLocaleLowerCase().includes(q)
  }))
}

/** Görünür sütunlarla CSV (BOM + CRLF + formül nötrleme — utils/csv.js ortak kuralı). */
export function rowsToCsv(rows, cols) {
  return toCsv(cols, (rows || []).map((r) => cols.map((c) => cellText(r?.[c]))))
}

/** Görünür sütunlarla JSON (girinti 2) — değerler özgün tipleriyle. */
export function rowsToJson(rows, cols) {
  return JSON.stringify((rows || []).map((r) => Object.fromEntries(cols.map((c) => [c, r?.[c] ?? null]))), null, 2)
}

/**
 * Panoya yapıştırılabilir TSV (Excel/Sheets): sekme ve satır sonu boşluğa çevrilir.
 *
 * <p>Formül nötrleme (CWE-1236, 2026-09-27 regresyon B8): `=`, `+`, `-`, `@` (sekme/CR) ile başlayan hücre Excel'e
 * yapıştırılınca FORMÜL olur — CSV dışa aktarımındaki kural (utils/csv.js `neutraliseFormula`) burada da uygulanır,
 * başlıklar dâhil. Sayı TİPİNDEKİ değer muaf: `-5` formül olamaz, nötrlense yapıştırılan sütun metne döner.
 */
export function rowsToTsv(rows, cols, { header = true } = {}) {
  const clean = (v) => (v == null ? '' : String(v).replace(/[\t\r\n]+/g, ' '))
  const cell = (v) => (typeof v === 'number' || typeof v === 'bigint' ? String(v) : clean(neutraliseFormula(cellText(v))))
  const lines = (rows || []).map((r) => cols.map((c) => cell(r?.[c])).join('\t'))
  return (header ? [cols.map((c) => clean(neutraliseFormula(c))).join('\t'), ...lines] : lines).join('\n')
}

/** Tarih damgalı dosya adı: `<base>-YYYYMMDD-HHmm.<ext>`. */
export function stampedFile(base, ext, d = new Date()) {
  const p = (n) => String(n).padStart(2, '0')
  return `${base}-${d.getFullYear()}${p(d.getMonth() + 1)}${p(d.getDate())}-${p(d.getHours())}${p(d.getMinutes())}.${ext}`
}

/** Blob indir — jsdom'da (test) sessizce çıkar. */
export function downloadBlob(filename, blob) {
  try {
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = filename
    document.body.appendChild(a)
    a.click()
    a.remove()
    setTimeout(() => URL.revokeObjectURL(url), 1500)
    return true
  } catch { return false }
}

/** Metin indir (JSON, SVG, CSV). */
export function downloadText(filename, text, mime) {
  return downloadBlob(filename, new Blob([text], { type: `${mime};charset=utf-8` }))
}

/** 0-tabanlı karakter konumu → 1-tabanlı satır/sütun. */
export function offsetToLineCol(text, offset) {
  const s = String(text || '')
  const o = Math.max(0, Math.min(offset, s.length))
  let line = 1, last = -1
  for (let i = 0; i < o; i++) if (s[i] === '\n') { line++; last = i }
  return { line, col: o - last }
}

/** Satır/sütun (imleç göstergesi) — seçim başlangıcından. */
export function caretInfo(text, start, end) {
  const { line, col } = offsetToLineCol(text, start)
  return { line, col, selected: Math.max(0, (end ?? start) - start) }
}

/**
 * Sunucu hata metnini ayrıştırır. İki biçim gelir:
 *  - Koruma reddi (HTTP 400, `success:false`): düz Türkçe cümle — olduğu gibi gösterilir.
 *  - Veritabanı hatası (`ok:false`): Spring çevirisi, örn. `StatementCallback; bad SQL grammar [SELECT …]` ve
 *    (varsa) PostgreSQL gövdesi `ERROR: … Hint: … Position: N`. Spring 6+ `getMessage()` nedeni EKLEMEDİĞİ için
 *    gövde çoğu zaman YOKTUR (API boşluğu) — o zaman özet "sözdizimi reddedildi" olur, ham metin ayrıntıda kalır.
 *
 * Konum (Position) sunucunun sardığı sorguya göredir; kullanıcının metnine yorum içermiyorsa geri eşlenir.
 *
 * @returns {{ kind: 'guard'|'grammar'|'timeout'|'db', summary: string|null, hint: string|null, detail: string|null,
 *             position: number|null, editorOffset: number|null, raw: string }}
 */
export function parseSqlError(message, { rejected = false, executedSql = '', sql = '' } = {}) {
  const raw = String(message || '').trim()
  const pgBody = raw.match(/ERROR:\s*([\s\S]*?)(?:\n\s*(?:Hint|Detail|Position|Where):|$)/)
  const hint = raw.match(/Hint:\s*([^\n]+)/)?.[1]?.trim() || null
  const detail = raw.match(/Detail:\s*([^\n]+)/)?.[1]?.trim() || null
  const position = Number(raw.match(/Position:\s*(\d+)/)?.[1]) || null
  let kind = 'db'
  if (rejected) kind = 'guard'
  else if (/statement timeout|canceling statement|QueryTimeout/i.test(raw)) kind = 'timeout'
  else if (/bad SQL grammar|syntax error/i.test(raw)) kind = 'grammar'
  const summary = rejected ? raw : (pgBody ? pgBody[1].trim() : null)

  let editorOffset = null
  if (position && !/--|\/\*/.test(sql) && executedSql.startsWith(CAP_PREFIX)) {
    const inner = position - 1 - CAP_PREFIX.length
    const lead = sql.length - sql.trimStart().length
    const body = sql.trim().replace(/;$/, '')
    if (inner >= 0 && inner <= body.length) editorOffset = lead + inner
  }
  return { kind, summary, hint, detail, position, editorOffset, raw }
}

/** Tek sözcüğün sınırları (hata konumunu düzenleyicide seçmek için). */
export function wordAt(text, offset) {
  const s = String(text || '')
  let a = Math.max(0, Math.min(offset, s.length)), b = a
  while (a > 0 && /[\w$."]/.test(s[a - 1])) a--
  while (b < s.length && /[\w$."]/.test(s[b])) b++
  if (a === b) b = Math.min(s.length, a + 1)
  return [a, b]
}

/** Kısa sayı: 1234 → "1.2K" (yerel biçim). */
export function formatCompact(n, locale) {
  if (n == null || Number.isNaN(Number(n))) return null
  try {
    return new Intl.NumberFormat(locale, { notation: 'compact', maximumFractionDigits: 1 }).format(Number(n))
  } catch { return String(n) }
}

/** Göreli zaman ("5 dk önce") — Intl.RelativeTimeFormat, i18n anahtarı gerektirmez. */
export function relativeFrom(iso, locale, now = Date.now()) {
  if (!iso) return null
  const s = String(iso)
  // Sunucu zaman damgaları UTC ve bölgesiz (`2026-09-26T09:30:00`) — Z eklenerek yorumlanır.
  const ms = Date.parse(/[zZ]|[+-]\d{2}:?\d{2}$/.test(s) ? s : `${s}Z`)
  if (Number.isNaN(ms)) return null
  const diff = Math.round((ms - now) / 1000)
  const abs = Math.abs(diff)
  const rtf = new Intl.RelativeTimeFormat(locale, { numeric: 'auto', style: 'short' })
  if (abs < 60) return rtf.format(diff, 'second')
  if (abs < 3600) return rtf.format(Math.round(diff / 60), 'minute')
  if (abs < 86400) return rtf.format(Math.round(diff / 3600), 'hour')
  if (abs < 86400 * 30) return rtf.format(Math.round(diff / 86400), 'day')
  if (abs < 86400 * 365) return rtf.format(Math.round(diff / (86400 * 30)), 'month')
  return rtf.format(Math.round(diff / (86400 * 365)), 'year')
}

/** Süre metni: 840 → "840 ms", 3200 → "3.2 s". */
export function formatDuration(ms) {
  if (ms == null || Number.isNaN(Number(ms))) return '—'
  const n = Number(ms)
  return n < 1000 ? `${Math.round(n)} ms` : `${(n / 1000).toFixed(n < 10_000 ? 1 : 0)} s`
}

const TYPE_SHORT = {
  'character varying': 'varchar', character: 'char', 'timestamp without time zone': 'timestamp',
  'timestamp with time zone': 'timestamptz', 'time without time zone': 'time', 'time with time zone': 'timetz',
  'double precision': 'float8', integer: 'int4', smallint: 'int2', bigint: 'int8', boolean: 'bool', 'USER-DEFINED': 'enum',
}

/** information_schema tip adının kısa hâli (kart/gezgin satırı dar). */
export function shortType(t) {
  if (!t) return ''
  return TYPE_SHORT[t] ?? String(t)
}

/** Tablo sorgusu şablonu (şema gezgini / diyagram / ayrıntı penceresi — TEK kaynak). */
export function tableQuery(name) {
  return `SELECT *\nFROM ${name}\nLIMIT 100;`
}
