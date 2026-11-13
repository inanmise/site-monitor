/**
 * SQL Playground — `:ad` parametreleri (2026-11-13, B4). Sunucu (`SqlParams`, `SqlParamsTest.TABLE`) ile AYNI kural —
 * istemci formu hangi alanları soracağını buradan bilir, sunucu aynı yer tutucuları JDBC `?` ile türüyle bağlar (metin
 * birleştirme YOK).
 *
 * Yer tutucu: tek `:` ve hemen ardından gelen tanımlayıcı dizisi (Unicode harf / rakam, `_`, `$` — en uzun dizi). Dizi
 * `[A-Za-z_][A-Za-z0-9_]{0,39}` kalıbına TAMAMEN uyuyorsa parametre adıdır; uymuyorsa (`:adı`, `:a$b`, 41 karakter, rakamla
 * başlama) yer tutucu DEĞİLDİR ve sorguda olduğu gibi kalır. Önünde ya da arkasında başka `:` olan iki nokta (`::tip`) sayılmaz.
 * Büyük / küçük harf AYRIDIR (`:Ad` ≠ `:ad`). Aynı ad birden çok yerde = TEK parametre. Sayılmaz: tek tırnaklı metin (`''`
 * kaçışı; `E'…'` içinde ters bölü kaçışı), çift tırnaklı tanımlayıcı, `--` satır yorumu, blok yorum (İÇ İÇE), dolar tırnağı
 * (`$$…$$`, `$etiket$…$etiket$`). Kapanmayan metin / yorum / dolar bloğu sona kadar sürer.
 *
 * Doğrusal: her adım dizini ilerletir; dolar kapanışı yalnız `indexOf`. Düzenli ifade yalnız tek karakter sınıflarında.
 */
export const PARAM_NAME_MAX = 40
export const MAX_PARAMS = 20
export const MAX_PARAM_VALUE = 1000
export const PARAM_TYPES = ['text', 'number', 'date', 'timestamp', 'bool']

const ASCII_LETTER = (c) => (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
const IDENT_RE = /[\p{L}\p{Nd}_$]/u
const LETTER_RE = /\p{L}/u
const LETTER_DIGIT_RE = /[\p{L}\p{Nd}]/u
/** Sunucunun `Character.isLetterOrDigit(c) || '_' || '$'` karşılığı (UTF-16 birimi başına; tek vekil yarısı harf değildir). */
const isIdentChar = (c) => !!c && IDENT_RE.test(c)

/** `[A-Za-z_][A-Za-z0-9_]{0,39}` — ad dizisinin TAMAMI uymalı. */
function isValidName(s, from, to) {
  const len = to - from
  if (len < 1 || len > PARAM_NAME_MAX) return false
  const f = s[from]
  if (!(ASCII_LETTER(f) || f === '_')) return false
  for (let i = from + 1; i < to; i++) {
    const c = s[i]
    if (!(ASCII_LETTER(c) || (c >= '0' && c <= '9') || c === '_')) return false
  }
  return true
}

/** `$etiket$` / `$$` açılışının bitiş dizini (son `$`), yoksa -1. Etiket harf ya da `_` ile başlar (`$1` konum parametresi). */
function dollarTagEnd(s, i) {
  const n = s.length
  let j = i + 1
  if (j < n && s[j] === '$') return j
  if (j >= n || !(LETTER_RE.test(s[j]) || s[j] === '_')) return -1
  while (j < n && (LETTER_DIGIT_RE.test(s[j]) || s[j] === '_')) j++
  return j < n && s[j] === '$' ? j : -1
}

/** Kapanış karakterinin ikilenmesi kaçıştır; `backslash` → ters bölü sonraki karakteri kaçırır. Kapanmazsa metnin sonu. */
function quotedEnd(s, from, close, backslash) {
  const n = s.length
  let i = from
  while (i < n) {
    const c = s[i]
    if (backslash && c === '\\') { i += 2; continue }
    if (c === close) {
      if (i + 1 < n && s[i + 1] === close) { i += 2; continue }
      return i + 1
    }
    i++
  }
  return n
}

/** İç içe blok yorum (PostgreSQL); kapanmazsa metnin sonu. */
function blockCommentEnd(s, start) {
  const n = s.length
  let depth = 0
  let i = start
  while (i < n) {
    const c = s[i]
    const c1 = i + 1 < n ? s[i + 1] : ''
    if (c === '/' && c1 === '*') { depth++; i += 2; continue }
    if (c === '*' && c1 === '/') { depth--; i += 2; if (depth === 0) return i; continue }
    i++
  }
  return n
}

/**
 * Sorgudaki yer tutucular — sunucunun `SqlParams.find` adımlarının birebir karşılığı.
 * @returns {{ names: string[], occurrences: Array<{ name: string, start: number, end: number }> }}
 *   `names` ilk görülme sırasıyla tekil; `occurrences` her geçiş (`start` = `:` dizini, `end` hariç).
 */
export function scanParams(sql) {
  const s = String(sql ?? '')
  const n = s.length
  const occurrences = []
  const seen = new Set()
  const names = []
  let i = 0
  while (i < n) {
    const c = s[i]
    const c1 = i + 1 < n ? s[i + 1] : ''
    if (c === '-' && c1 === '-') {
      while (i < n && s[i] !== '\n' && s[i] !== '\r') i++
      continue
    }
    if (c === '/' && c1 === '*') { i = blockCommentEnd(s, i); continue }
    if (c === "'") {
      // E'…' (ters bölü kaçışlı): önceki karakter E/e ve ondan önce tanımlayıcı karakteri yok
      const backslash = i > 0 && (s[i - 1] === 'E' || s[i - 1] === 'e') && (i < 2 || !isIdentChar(s[i - 2]))
      i = quotedEnd(s, i + 1, "'", backslash)
      continue
    }
    if (c === '"') { i = quotedEnd(s, i + 1, '"', false); continue }
    if (c === '$') {
      const tagEnd = dollarTagEnd(s, i)
      if (tagEnd > 0 && (i === 0 || !isIdentChar(s[i - 1]))) {
        const tag = s.slice(i, tagEnd + 1)
        const close = s.indexOf(tag, tagEnd + 1)
        i = close < 0 ? n : close + tag.length
        continue
      }
      i++
      continue
    }
    if (c === ':') {
      const prevColon = i > 0 && s[i - 1] === ':'
      if (c1 === ':' || prevColon) { i++; continue }   // ::tip dönüşümü, :=
      let j = i + 1
      while (j < n && isIdentChar(s[j])) j++
      if (j > i + 1 && isValidName(s, i + 1, j)) {
        const name = s.slice(i + 1, j)
        occurrences.push({ name, start: i, end: j })
        if (!seen.has(name)) { seen.add(name); names.push(name) }
      }
      i = Math.max(j, i + 1)
      continue
    }
    if (isIdentChar(c)) {   // tanımlayıcı içindeki '$' dolar tırnağı değildir (ab$1)
      let j = i + 1
      while (j < n && isIdentChar(s[j])) j++
      i = j
      continue
    }
    i++
  }
  return { names, occurrences }
}

/** Yer tutucu adları (tekil, ilk görülme sırası). */
export function extractParams(sql) {
  return scanParams(sql).names
}

/** Ada göre tür tahmini (yalnız ilk görüşte; kullanıcı değiştirebilir). */
export function guessParamType(name) {
  const n = String(name || '').toLowerCase()
  if (/(^|_)(at|time|ts|timestamp)$|_at_|since|until/.test(n)) return 'timestamp'
  if (/(^|_)(date|day|gun)$|date|tarih/.test(n)) return 'date'
  if (/^(is|has|can)_|(^|_)(enabled|active|aktif|resolved|ok)$/.test(n)) return 'bool'
  if (/(^|_)(id|count|limit|offset|n|num|days|hours|minutes|size|port|level|tier)$/.test(n)) return 'number'
  return 'text'
}

const NUMBER_RE = /^[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?$/
const DATE_RE = /^(\d{4})-(\d{2})-(\d{2})$/
const TS_RE = /^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2})(?::(\d{2}))?$/

function realDate(y, m, d) {
  const dt = new Date(Date.UTC(Number(y), Number(m) - 1, Number(d)))
  return dt.getUTCFullYear() === Number(y) && dt.getUTCMonth() === Number(m) - 1 && dt.getUTCDate() === Number(d)
}

/**
 * Değeri türüne göre denetler. @returns hata kodu ya da null:
 * `required` (boş — metin dahil; sunucu SQL_PARAM_MISSING), `tooLong`, `number`, `date`, `timestamp`, `bool`, `type`.
 */
export function validateParamValue(type, value) {
  const raw = value == null ? '' : String(value)
  if (!PARAM_TYPES.includes(type)) return 'type'
  if (raw.length > MAX_PARAM_VALUE) return 'tooLong'
  const v = type === 'text' ? raw : raw.trim()
  if (v === '') return 'required'
  if (type === 'number') return NUMBER_RE.test(v) ? null : 'number'
  if (type === 'date') {
    const m = DATE_RE.exec(v)
    return m && realDate(m[1], m[2], m[3]) ? null : 'date'
  }
  if (type === 'timestamp') {
    const m = TS_RE.exec(v)
    if (!m || !realDate(m[1], m[2], m[3])) return 'timestamp'
    const [hh, mm, ss] = [Number(m[4]), Number(m[5]), Number(m[6] ?? 0)]
    return hh < 24 && mm < 60 && ss < 60 ? null : 'timestamp'
  }
  if (type === 'bool') return /^(true|false|1|0)$/i.test(v) ? null : 'bool'
  return null
}

/** Sunucuya giden değer: sayı / tarih kırpılır; zaman damgası `yyyy-MM-ddTHH:mm:ss`; mantıksal küçük harf; metin aynen. */
export function normalizeParamValue(type, value) {
  const raw = value == null ? '' : String(value)
  if (type === 'text') return raw
  const v = raw.trim()
  if (type === 'timestamp') {
    const m = TS_RE.exec(v)
    return m ? `${m[1]}-${m[2]}-${m[3]}T${m[4]}:${m[5]}:${m[6] ?? '00'}` : v
  }
  if (type === 'bool') return v === '1' ? 'true' : v === '0' ? 'false' : v.toLowerCase()
  return v
}

/**
 * Formun denetimi + istek gövdesi.
 * @param names  sorgudaki yer tutucular
 * @param state  `{ ad: { value, type } }` (sekmede saklanan)
 * @returns {{ errors: Object<string,string>|null, body: Object|null, tooMany: boolean }}
 *   `errors` anahtarı `params.<ad>` (sunucunun alan adıyla aynı); `body` hiç parametre yoksa null.
 */
export function buildParamsBody(names, state) {
  const list = Array.isArray(names) ? names : []
  if (!list.length) return { errors: null, body: null, tooMany: false }
  if (list.length > MAX_PARAMS) return { errors: null, body: null, tooMany: true }
  const errors = {}
  const body = {}
  for (const name of list) {
    const p = state?.[name] || {}
    const type = PARAM_TYPES.includes(p.type) ? p.type : guessParamType(name)
    const err = validateParamValue(type, p.value)
    if (err) errors[`params.${name}`] = err
    else body[name] = { value: normalizeParamValue(type, p.value), type }
  }
  return { errors: Object.keys(errors).length ? errors : null, body, tooMany: false }
}

/** Sunucu alan hatası (`field: "params.ad"` ya da `fields`) → `{ 'params.ad': metin }`. */
export function paramErrorsFromResponse(res) {
  if (!res || typeof res !== 'object') return null
  const out = {}
  const msg = typeof res.error === 'string' ? res.error : ''
  if (typeof res.field === 'string' && res.field.startsWith('params.')) out[res.field] = msg
  if (res.fields && typeof res.fields === 'object') {
    for (const [k, v] of Object.entries(res.fields)) {
      if (k.startsWith('params.')) out[k] = typeof v === 'string' && v ? v : msg
    }
  }
  return Object.keys(out).length ? out : null
}

/** Sekmenin parametre durumunu sorgudaki adlara göre tamamlar (yeni ad → tahmini tür, boş değer). Değişmezse aynı nesne. */
export function syncParamState(names, state) {
  const cur = state && typeof state === 'object' ? state : {}
  let changed = false
  const next = { ...cur }
  for (const name of names || []) {
    if (!next[name]) { next[name] = { value: '', type: guessParamType(name) }; changed = true }
  }
  return changed ? next : cur
}
