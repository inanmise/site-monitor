import { KEYWORDS } from './sqlHighlight.jsx'

/**
 * SQL Playground otomatik tamamlama (2026-11-13, B1) — saf model. Kaynak: şema gezgininin önbelleği (tablo listesi + açılan
 * tabloların kolonları); ağ isteği yalnız sorguda geçen tablonun kolonları henüz yüklenmediyse ({@link completionItems}
 * `needColumns` döner — çağıran yükler). Dize / yorum / dolar bloğu içinde ÖNERİ YOK. Doğrusal tarama, sınırlı çıktı.
 */
export const COMPLETION_LIMIT = 200
/** Takma ad / tablo niteleyicili (`u.`) kolon önerisi: tablonun TÜM kolonları (liste kaydırılır). */
export const QUALIFIED_LIMIT = 2000
const SCAN_MAX = 200_000

const isNameStart = (c) => /[A-Za-z_]/.test(c)
const isNamePart = (c) => /[A-Za-z0-9_$]/.test(c)
const IDENT_RE = /[\p{L}\p{Nd}_$]/u

/**
 * `'` bir `E'…'` (ters bölü kaçışlı) dizeyi mi açıyor: önceki karakter E / e ve ondan önce tanımlayıcı karakteri yok —
 * sunucunun (`SqlParams` / `SqlLexer`) ve `sqlParams.scanParams`'ın kuralı.
 */
function escapeString(s, i) {
  return s[i] === "'" && i > 0 && (s[i - 1] === 'E' || s[i - 1] === 'e') && (i < 2 || !IDENT_RE.test(s[i - 2]))
}

/** Tırnaklı bölümün kapanış dizini (kapanış tırnağının konumu; kapanmazsa metnin uzunluğu). `''` / `""` ikilenmesi kaçıştır. */
function quotedClose(s, i) {
  const c = s[i]
  const backslash = escapeString(s, i)
  let j = i + 1
  while (j < s.length) {
    if (backslash && s[j] === '\\') { j += 2; continue }
    if (s[j] === c) { if (s[j + 1] === c) { j += 2; continue } break }
    j++
  }
  return Math.min(j, s.length)
}

/** İmleçten önceki metin bir dize / yorum / dolar bloğu / tırnaklı ad içinde mi (doğrusal ileri tarama). */
export function insideLiteral(text, caret) {
  const s = String(text ?? '')
  const end = Math.min(Math.max(0, caret), s.length, SCAN_MAX)
  let i = 0
  while (i < end) {
    const c = s[i]
    const c1 = s[i + 1]
    if (c === '-' && c1 === '-') {
      const nl = s.indexOf('\n', i + 2)
      if (nl < 0 || nl >= end) return true
      i = nl + 1
      continue
    }
    if (c === '/' && c1 === '*') {
      let depth = 1
      let j = i + 2
      while (j < s.length && depth > 0) {
        if (s[j] === '/' && s[j + 1] === '*') { depth++; j += 2 } else if (s[j] === '*' && s[j + 1] === '/') { depth--; j += 2 } else j++
      }
      if (depth > 0 || j > end) return true
      i = j
      continue
    }
    if (c === "'" || c === '"') {
      const j = quotedClose(s, i)
      if (j >= end) return true
      i = j + 1
      continue
    }
    if (c === '$' && !(i > 0 && isNamePart(s[i - 1]))) {
      let j = i + 1
      let tagEnd = -1
      if (s[j] === '$') tagEnd = j
      else if (j < s.length && isNameStart(s[j])) {
        while (j < s.length && /[A-Za-z0-9_]/.test(s[j])) j++
        if (s[j] === '$') tagEnd = j
      }
      if (tagEnd > 0) {
        const tag = s.slice(i, tagEnd + 1)
        const close = s.indexOf(tag, tagEnd + 1)
        if (close < 0 || close + tag.length > end) return true
        i = close + tag.length
        continue
      }
    }
    i++
  }
  return false
}

/**
 * İmleçteki tamamlama bağlamı: `{ start, prefix, qualifier }` ya da null (dize / yorum içinde ya da sözcük yok).
 * `qualifier` = `takma_ad.` / `tablo.` öneki (yalnız düz ad); `prefix` imlece kadar yazılan sözcük parçası.
 */
export function completionContext(text, caret, { force = false } = {}) {
  const s = String(text ?? '')
  const pos = Math.min(Math.max(0, caret ?? 0), s.length)
  let a = pos
  while (a > 0 && /[A-Za-z0-9_]/.test(s[a - 1])) a--
  // Sözcük rakamla başlıyorsa (sayı) öneri yok
  if (a < pos && /[0-9]/.test(s[a])) return null
  const prefix = s.slice(a, pos)
  let qualifier = null
  if (a > 0 && s[a - 1] === '.') {
    let q = a - 1
    while (q > 0 && /[A-Za-z0-9_]/.test(s[q - 1])) q--
    const name = s.slice(q, a - 1)
    if (name && isNameStart(name[0])) qualifier = name
    else return null
  }
  if (!prefix && !qualifier && !force) return null
  if (insideLiteral(s, a)) return null
  return { start: a, prefix, qualifier }
}

const NOT_ALIAS = new Set(['on', 'using', 'where', 'join', 'left', 'right', 'inner', 'full', 'cross', 'natural', 'outer',
  'group', 'order', 'having', 'limit', 'offset', 'union', 'intersect', 'except', 'window', 'fetch', 'lateral', 'as', 'select',
  'with', 'and', 'or', 'set', 'tablesample', 'values'])
/** FROM yan cümlesini (aynı derinlikte) bitiren sözcükler. */
const FROM_END = new Set(['where', 'group', 'order', 'having', 'limit', 'offset', 'union', 'intersect', 'except', 'window',
  'fetch', 'select', 'returning', 'values', 'set'])

/**
 * Sorgudaki tablo ↔ takma ad eşlemesi (`FROM t a`, `JOIN t AS a`, `FROM s.t a`, virgülle çoklu FROM). Anahtarlar küçük harf.
 * Tablonun kendi adı da kendisine eşlenir. Dize / yorumlar atlanır (biçimlendiricinin sözcük ayırıcısıyla aynı mantık).
 * @returns {Map<string, string>}
 */
export function parseAliases(text) {
  const s = String(text ?? '').slice(0, SCAN_MAX)
  const words = []
  let i = 0
  while (i < s.length) {
    const c = s[i]
    if (c === '-' && s[i + 1] === '-') { const nl = s.indexOf('\n', i); i = nl < 0 ? s.length : nl + 1; continue }
    if (c === '/' && s[i + 1] === '*') { const e = s.indexOf('*/', i + 2); i = e < 0 ? s.length : e + 2; continue }
    if (c === "'" || c === '"') {
      i = quotedClose(s, i) + 1
      words.push({ t: 'lit', v: '' })
      continue
    }
    if (isNameStart(c)) {
      let j = i + 1
      while (j < s.length && /[A-Za-z0-9_]/.test(s[j])) j++
      words.push({ t: 'w', v: s.slice(i, j) })
      i = j
      continue
    }
    if (/\s/.test(c)) { i++; continue }
    words.push({ t: 'p', v: c })
    i++
  }
  const map = new Map()
  // Parantez derinliği başına "FROM yan cümlesindeyiz" bayrağı: ON / USING koşulundan sonraki virgül de yeni tablodur
  // (`FROM a JOIN b ON … , c x`); koşul içindeki parantezli virgüller (fonksiyon çağrısı) daha derinde kalır.
  const inFrom = [false]
  let depth = 0
  for (let k = 0; k < words.length; k++) {
    const w = words[k]
    const lw = w.t === 'w' ? w.v.toLowerCase() : null
    if (w.t === 'p' && w.v === '(') { depth = Math.min(depth + 1, 200); inFrom[depth] = false; continue }
    if (w.t === 'p' && w.v === ')') { inFrom[depth] = false; depth = Math.max(0, depth - 1); continue }
    if (lw === 'from' || lw === 'join') { inFrom[depth] = true; readTable(k + 1); continue }
    if (inFrom[depth] && w.t === 'p' && w.v === ',') { readTable(k + 1); continue }
    if (lw && FROM_END.has(lw)) inFrom[depth] = false
  }
  return map

  function readTable(k) {
    let j = k
    if (words[j]?.t === 'w' && words[j].v.toLowerCase() === 'lateral') j++
    if (words[j]?.t !== 'w') return
    let table = words[j].v
    // şema.tablo → tablo
    while (words[j + 1]?.t === 'p' && words[j + 1].v === '.' && words[j + 2]?.t === 'w') { table = words[j + 2].v; j += 2 }
    const tl = table.toLowerCase()
    if (KEYWORDS.has(tl) && tl !== 'user') return
    map.set(tl, table)
    let a = j + 1
    if (words[a]?.t === 'w' && words[a].v.toLowerCase() === 'as') a++
    const al = words[a]?.t === 'w' ? words[a].v.toLowerCase() : null
    if (al && !NOT_ALIAS.has(al) && !KEYWORDS.has(al)) map.set(al, table)
  }
}

/** Ad öneki eşleşmesi: baştan ya da `_` ile ayrılmış bir parçadan (`user` → `app_users`). 0 = baştan, 1 = parçadan. */
function matchRank(name, prefix) {
  if (!prefix) return 0
  const n = name.toLowerCase()
  const p = prefix.toLowerCase()
  if (n.startsWith(p)) return 0
  if (n.split('_').some((part, i) => i > 0 && part.startsWith(p))) return 1
  return -1
}

/**
 * Öneri listesi.
 * @param ctx        {@link completionContext} sonucu
 * @param tables     şema gezgininin tablo listesi (`[{ table_name }]` ya da ad dizisi)
 * @param columnsMap `{ tablo: [{ column_name, data_type }] }` (önbellek)
 * @param aliases    {@link parseAliases}
 * @returns {{ items: Array<{ label, insert, kind: 'column'|'table'|'keyword', detail? }>, needColumns: string[] }}
 */
export function completionItems(ctx, { tables = [], columnsMap = {}, aliases = new Map(), limit = COMPLETION_LIMIT } = {}) {
  if (!ctx) return { items: [], needColumns: [] }
  const tableNames = tables.map((t) => (typeof t === 'string' ? t : t?.table_name)).filter(Boolean)
  const tableSet = new Map(tableNames.map((n) => [n.toLowerCase(), n]))
  const needColumns = []
  const items = []
  const seen = new Set()
  const add = (label, kind, rank, detail) => {
    const key = `${kind}:${label.toLowerCase()}`
    if (seen.has(key)) return
    seen.add(key)
    items.push({ label, insert: kind === 'keyword' ? label.toUpperCase() : label, kind, detail, rank })
  }
  const colsOf = (table) => {
    const cols = columnsMap?.[table]
    if (!cols) { if (!needColumns.includes(table)) needColumns.push(table); return [] }
    return cols
  }

  if (ctx.qualifier) {
    const q = ctx.qualifier.toLowerCase()
    // Takma ad tablonun YAZILDIĞI biçimi döndürür (`FROM TEAMS t`); kolon önbelleği ve tablo listesi sunucunun adlarıyla
    // (PostgreSQL tırnaksız adı küçük harfe katlar) — arama küçük harfe normalize edilir
    const raw = aliases.get(q) ?? null
    const table = (raw != null ? tableSet.get(String(raw).toLowerCase()) : null) ?? tableSet.get(q) ?? raw
    if (!table) return { items: [], needColumns: [] }
    for (const c of colsOf(table)) {
      const r = matchRank(c.column_name, ctx.prefix)
      if (r >= 0) add(c.column_name, 'column', r, c.data_type)
    }
  } else {
    const referenced = [...new Set([...aliases.values()])].filter((t) => tableSet.has(t.toLowerCase()))
    for (const t of referenced) {
      for (const c of colsOf(tableSet.get(t.toLowerCase()))) {
        const r = matchRank(c.column_name, ctx.prefix)
        if (r >= 0) add(c.column_name, 'column', r, t)
      }
    }
    for (const n of tableNames) {
      const r = matchRank(n, ctx.prefix)
      if (r >= 0) add(n, 'table', r)
    }
    if (ctx.prefix.length >= 2) {
      for (const k of KEYWORDS) {
        if (k.startsWith(ctx.prefix.toLowerCase())) add(k, 'keyword', 0)
      }
    }
  }
  const kindOrder = { column: 0, table: 1, keyword: 2 }
  items.sort((a, b) => a.rank - b.rank || kindOrder[a.kind] - kindOrder[b.kind] || a.label.localeCompare(b.label))
  // Yazılanın aynısı tek öneri ise liste gereksiz
  const exact = items.length === 1 && items[0].label.toLowerCase() === ctx.prefix.toLowerCase()
  const cap = ctx.qualifier ? Math.max(limit, QUALIFIED_LIMIT) : limit
  return { items: exact ? [] : items.slice(0, cap).map((it) => ({ label: it.label, insert: it.insert, kind: it.kind, detail: it.detail })), needColumns }
}
