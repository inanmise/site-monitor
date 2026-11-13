import { changedFields } from '../../../utils/changedFields.js'
import { duplicateColumnNames, uniqueColumnNames } from './columnNames.js'
import { cellText } from './sqlUtils.js'

/**
 * SQL Playground kitaplık modelleri (2026-11-13): sorgu geçmişi (A4), kayıtlı sorgular (D1), örnek kataloğu (D2), dışa
 * aktarma yardımcıları (A3 / C3). Saf — sunum yok.
 */

// ── A4 · Geçmiş ─────────────────────────────────────────────────────────────────────────────────────────────────────
export const HISTORY_STATUSES = ['all', 'ok', 'error', 'slow']
export const HISTORY_SCOPES = ['mine', 'all']
export const HISTORY_PAGE_SIZE = 25
export const SLOW_MS_DEFAULT = 5000
export const HISTORY_Q_MAX = 200

/** Sayfalı geçmiş sorgu dizesi (yalnız dolu alanlar; `page` / `size` daima — sunucu sayfalı kipe geçsin). */
export function historyQuery({ q = '', status = 'all', scope = 'mine', from = '', to = '', page = 0, size = HISTORY_PAGE_SIZE } = {}) {
  const p = new URLSearchParams()
  const qq = String(q || '').trim().slice(0, HISTORY_Q_MAX)
  if (qq) p.set('q', qq)
  if (status && status !== 'all') p.set('status', status)
  p.set('scope', HISTORY_SCOPES.includes(scope) ? scope : 'mine')
  if (from) p.set('from', from)
  if (to) p.set('to', to)
  p.set('page', String(Math.max(0, page | 0)))
  p.set('size', String(Math.min(100, Math.max(1, size | 0))))
  return p.toString()
}

/** Ters tarih aralığı (istemci denetimi — sunucu da 400 `from` verir). */
export function historyRangeError(from, to) {
  return from && to && String(from) > String(to) ? 'reversed' : null
}

/** Eski sunucu sayfalı kipi bilmez: `total` yoksa parametresiz yanıt (kendi son 50) dönmüştür. */
export function isPagedHistory(r) {
  return Boolean(r && typeof r === 'object' && Number.isFinite(Number(r.total)) && r.total != null)
}

/** Satırın durumu: cancelled · error · slow · ok. */
export function historyState(row, slowMs = SLOW_MS_DEFAULT) {
  if (row?.cancelled) return 'cancelled'
  if (row?.success === false) return 'error'
  const ms = Number(row?.duration_ms ?? row?.durationMs)
  if (Number.isFinite(ms) && ms >= slowMs) return 'slow'
  return 'ok'
}

/** Eski sunucu yedeği: son 50 içinde metin + durum süzgeci (kapsam / tarih uygulanamaz). */
export function filterLocalHistory(rows, { q = '', status = 'all' } = {}, slowMs = SLOW_MS_DEFAULT) {
  const needle = String(q || '').trim().toLocaleLowerCase()
  return (rows || []).filter((r) => {
    const text = String(r?.sql_text ?? r?.sqlText ?? '').toLocaleLowerCase()
    if (needle && !text.includes(needle)) return false
    if (status === 'all') return true
    const st = historyState(r, slowMs)
    return status === 'error' ? st === 'error' || st === 'cancelled' : st === status
  })
}

// ── D1 · Kayıtlı sorgular ───────────────────────────────────────────────────────────────────────────────────────────
export const SAVED_SCOPES = ['mine', 'shared', 'all']
export const SAVED_TITLE_MAX = 120
export const SAVED_DESC_MAX = 1000
export const SAVED_TAGS_MAX = 10
export const SAVED_TAG_LEN = 30
export const SAVED_SQL_MAX = 100_000
export const SAVED_PAGE_SIZE = 20

/** Etiket listesi: virgül / boşlukla ayrılmış ya da dizi → kırpılmış, tekil (büyük / küçük harf duyarsız), en çok 10. */
export function normalizeTags(input) {
  const raw = Array.isArray(input) ? input : String(input ?? '').split(/[,\n]/)
  const out = []
  const seen = new Set()
  for (const t of raw) {
    const v = String(t ?? '').trim()
    if (!v) continue
    const k = v.toLocaleLowerCase('tr')
    if (seen.has(k)) continue
    seen.add(k)
    out.push(v)
  }
  return out
}

/** Form denetimi → `{ alan: kod }` ya da null. Kodlar: required · tooLong · tooMany · tagTooLong · sqlRequired · sqlTooLong. */
export function validateSaved(form) {
  const e = {}
  const title = String(form?.title ?? '').trim()
  if (!title) e.title = 'required'
  else if (title.length > SAVED_TITLE_MAX) e.title = 'tooLong'
  if (String(form?.description ?? '').length > SAVED_DESC_MAX) e.description = 'tooLong'
  const tags = normalizeTags(form?.tags)
  if (tags.length > SAVED_TAGS_MAX) e.tags = 'tooMany'
  else if (tags.some((t) => t.length > SAVED_TAG_LEN)) e.tags = 'tagTooLong'
  const sql = String(form?.sql_text ?? '')
  if (!sql.trim()) e.sql_text = 'sqlRequired'
  else if (sql.length > SAVED_SQL_MAX) e.sql_text = 'sqlTooLong'
  return Object.keys(e).length ? e : null
}

/** Gövde (yeni kayıt) — `params` yalnız TÜRLER (değer asla). */
export function savedBody(form, paramTypes) {
  const body = {
    title: String(form.title ?? '').trim(),
    description: String(form.description ?? '').trim(),
    tags: normalizeTags(form.tags),
    shared: Boolean(form.shared),
    sql_text: String(form.sql_text ?? ''),
  }
  if (paramTypes && Object.keys(paramTypes).length) body.params = paramTypes
  return body
}

/**
 * Güncelleme gövdesi: yalnız değişen alanlar + `version`. Değişiklik yoksa null (istek gönderilmez). Parametre türleri
 * yalnız SQL değiştiğinde gider (satır yanıtı türleri döndürmüyor — her seferinde "değişti" sayılmasın).
 */
export function savedUpdateBody(form, base, paramTypes) {
  const next = savedBody(form, null)
  const prev = base ? savedBody({
    title: base.title, description: base.description ?? '', tags: base.tags ?? [], shared: base.shared, sql_text: base.sql_text,
  }, null) : null
  const diff = changedFields(next, prev)
  if (diff.sql_text != null && paramTypes && Object.keys(paramTypes).length) diff.params = paramTypes
  if (!Object.keys(diff).length) return null
  return { ...diff, version: base?.version }
}

/** Paramların yalnız TÜRLERİ (`{ ad: tür }`). */
export function paramTypesOf(names, state) {
  const out = {}
  for (const n of names || []) if (state?.[n]?.type) out[n] = state[n].type
  return out
}

/** Paylaşılabilir bağlantı. */
export function savedLink(id, origin = (typeof window !== 'undefined' ? window.location.origin : '')) {
  return `${origin}/?tab=sqlplayground&sq=${encodeURIComponent(String(id))}`
}

/** `sq` derin bağlantısı (sayısal kimlik) ya da null. */
export function savedIdFromSearch(search) {
  try {
    const v = new URLSearchParams(search || '').get('sq')
    return v && /^\d{1,18}$/.test(v) ? v : null
  } catch { return null }
}

// ── D2 · Örnekler ───────────────────────────────────────────────────────────────────────────────────────────────────
/** Bilinen kategori kodları — arayüz metni `sql.sampleCat.<kod>`; sunucu düz metin yollarsa olduğu gibi gösterilir. */
export const SAMPLE_CATEGORIES = ['monitoring', 'alerts', 'notifications', 'inventory', 'teams', 'system']

/**
 * Örnekleri kategoriye göre gruplar (sunucu sırası korunur). Eski sunucu `category` göndermezse TEK grup (`null`).
 * @returns {Array<{ category: string|null, items: Array }>}
 */
export function groupSamples(samples) {
  const groups = []
  const byKey = new Map()
  for (const s of samples || []) {
    const cat = typeof s?.category === 'string' && s.category.trim() ? s.category.trim() : null
    if (!byKey.has(cat)) { const g = { category: cat, items: [] }; byKey.set(cat, g); groups.push(g) }
    byKey.get(cat).items.push(s)
  }
  return groups
}

// ── A3 / C3 · Dışa aktarma ──────────────────────────────────────────────────────────────────────────────────────────
export const EXPORT_LIMITS = { csv: 50_000, json: 20_000 }

/** Excel sayfası (ekrandaki ya da sunucudan gelen satırlar): başlık + satırlar; sayı tipli kolon sayı hücresi. */
export function excelSheet(rows, cols, types, name = 'SQL') {
  const out = [cols.slice()]
  for (const r of rows || []) {
    out.push(cols.map((c) => {
      const v = r?.[c]
      if (v == null) return null
      if (types?.[c] === 'number') {
        const n = typeof v === 'number' ? v : Number(v)
        if (Number.isFinite(n)) return n
      }
      return cellText(v)
    }))
  }
  return { name, rows: out, freezeHeader: true, autoFilter: true, widths: cols.map((c) => Math.min(48, Math.max(10, String(c).length + 2))) }
}

/**
 * Sunucu JSON dışa aktarım gövdesi → satırlar + kolonlar. Sunucu biçimi (`SqlExportWriter`):
 * `{ success, data: { columns: [...], rows: [[...]] } }`; ayrıca satır nesneleri dizisi ya da `{ rows | data: [...], columns? }`
 * okunur. Tanınmazsa null.
 *
 * <p>Aynı adlı kolonlar (DÜŞÜK 5, 2026-11-13): satırlar DİZİ olarak gelince başlıklar benzersizleştirilir (`id`, `id_2`) ve
 * her hücre KENDİ konumundan okunur — `SELECT a.id, b.id` iki kolonun değerini de taşır; `renamed` hangi başlıkların
 * değiştiğini söyler. Satırlar nesneyse aynı adlı ikinci kolonun değeri zaten yoktur: başlık bir kez yazılır (`collapsed`).
 * @returns {{ rows: Object[], columns: string[]|null, renamed: Array<{from, to}>, collapsed: string[] }|null}
 */
export function unwrapExportJson(body) {
  let rows = null
  let columns = null
  if (Array.isArray(body)) rows = body
  else if (body && typeof body === 'object') {
    if (Array.isArray(body.rows)) rows = body.rows
    else if (Array.isArray(body.data)) rows = body.data
    else if (body.data && Array.isArray(body.data.rows)) { rows = body.data.rows; columns = body.data.columns }
    if (Array.isArray(body.columns)) columns = body.columns
  }
  if (!rows) return null
  const cols = Array.isArray(columns) ? columns.map((c) => (c == null ? '' : String(c))) : null
  if (!cols) return { rows, columns: null, renamed: [], collapsed: [] }
  const arrayRows = rows.length ? Array.isArray(rows[0]) : true
  if (arrayRows) {
    const { names, renamed } = uniqueColumnNames(cols)
    const out = rows.map((r) => Object.fromEntries(names.map((c, i) => [c, Array.isArray(r) ? (r[i] ?? null) : null])))
    return { rows: out, columns: names, renamed, collapsed: [] }
  }
  const collapsed = duplicateColumnNames(cols)
  const seen = new Set()
  const unique = cols.filter((c) => { const k = c.toLowerCase(); if (seen.has(k)) return false; seen.add(k); return true })
  return { rows, columns: unique, renamed: [], collapsed }
}
