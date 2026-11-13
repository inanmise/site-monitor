import { KEYWORDS } from './sqlHighlight.jsx'

/**
 * SQL biçimlendirici (2026-11-13, B2) — bağımlılık yok, DOĞRUSAL.
 *
 * Yalnız düzeni değiştirir: ana yan cümleler (SELECT, FROM, WHERE, GROUP BY, ORDER BY, HAVING, LIMIT, OFFSET, birleşimler,
 * UNION / INTERSECT / EXCEPT, WITH, VALUES) satır başına; SELECT listesindeki virgüller ve WHERE / HAVING / ON içindeki
 * AND / OR alt satıra (iki boşluk girintiyle; `BETWEEN … AND …` bölünmez); `(SELECT …)` alt sorgu girintili; anahtar
 * sözcükler BÜYÜK harf. Dokunulmaz: metin (`'…'`, `E'…'`), dolar blokları (`$$…$$`, `$etiket$…$etiket$`), tırnaklı ad
 * (`"…"`), yorumlar (satır yorumundan sonra DAİMA yeni satır — sonraki kod yoruma girmesin) ve işleç aralığı (kaynaktaki
 * boşluk korunur). Aynı metni iki kez biçimlendirmek aynı sonucu verir (sabit nokta — testte pinli).
 */
export const FORMAT_MAX_CHARS = 200_000
const INDENT = '  '

const CLAUSES = new Set(['select', 'from', 'where', 'having', 'limit', 'offset', 'fetch', 'union', 'intersect', 'except',
  'with', 'values', 'window', 'returning'])
const TWO_WORD = { group: 'by', order: 'by' }
const JOIN_LEAD = new Set(['join', 'left', 'right', 'inner', 'full', 'cross', 'natural', 'outer'])
const COND_CLAUSES = new Set(['where', 'having', 'on'])
const EXTRA_KEYWORDS = new Set(['join', 'inner', 'left', 'right', 'full', 'outer', 'cross', 'natural', 'using', 'lateral'])

const isNameStart = (c) => /[A-Za-z_]/.test(c)
const isNamePart = (c) => /[A-Za-z0-9_$]/.test(c)

/** Biçimlendirme belirteçleri: { t: tür, v: metin, ws: önünde boşluk var mı, nl: önünde satır sonu var mı }. */
export function lexForFormat(src) {
  const s = String(src ?? '')
  const n = s.length
  const out = []
  let i = 0
  let ws = false
  let nl = false
  const push = (t, v) => { out.push({ t, v, ws, nl }); ws = false; nl = false }
  while (i < n) {
    const c = s[i]
    const c1 = s[i + 1] ?? ''
    if (c === ' ' || c === '\t' || c === '\r' || c === '\n' || c === '\f' || c === '\v') {
      ws = true
      if (c === '\n') nl = true
      i++
      continue
    }
    if (c === '-' && c1 === '-') {
      let j = s.indexOf('\n', i)
      if (j < 0) j = n
      push('lcomment', s.slice(i, j).replace(/\s+$/, ''))
      i = j
      continue
    }
    if (c === '/' && c1 === '*') {
      let depth = 1
      let j = i + 2
      while (j < n && depth > 0) {
        if (s[j] === '/' && s[j + 1] === '*') { depth++; j += 2 } else if (s[j] === '*' && s[j + 1] === '/') { depth--; j += 2 } else j++
      }
      push('bcomment', s.slice(i, j))
      i = j
      continue
    }
    if (c === "'" || ((c === 'E' || c === 'e') && c1 === "'" && !(i > 0 && isNamePart(s[i - 1])))) {
      const escaped = c !== "'"
      let j = escaped ? i + 2 : i + 1
      while (j < n) {
        if (escaped && s[j] === '\\') { j += 2; continue }
        if (s[j] === "'") { if (s[j + 1] === "'") { j += 2; continue } break }
        j++
      }
      push('string', s.slice(i, Math.min(n, j + 1)))
      i = j + 1
      continue
    }
    if (c === '"') {
      let j = i + 1
      while (j < n) { if (s[j] === '"') { if (s[j + 1] === '"') { j += 2; continue } break } j++ }
      push('quoted', s.slice(i, Math.min(n, j + 1)))
      i = j + 1
      continue
    }
    if (c === '$' && !(i > 0 && isNamePart(s[i - 1]))) {
      let j = i + 1
      let tagEnd = -1
      if (s[j] === '$') tagEnd = j
      else if (j < n && isNameStart(s[j])) {
        while (j < n && /[A-Za-z0-9_]/.test(s[j])) j++
        if (s[j] === '$') tagEnd = j
      }
      if (tagEnd > 0) {
        const tag = s.slice(i, tagEnd + 1)
        const close = s.indexOf(tag, tagEnd + 1)
        const end = close < 0 ? n : close + tag.length
        push('dollar', s.slice(i, end))
        i = end
        continue
      }
    }
    if (/[0-9]/.test(c) || (c === '.' && /[0-9]/.test(c1))) {
      let j = i
      while (j < n && /[0-9.]/.test(s[j])) j++
      if (j < n && /[eE]/.test(s[j]) && /[0-9+-]/.test(s[j + 1] ?? '')) {
        j += 2
        while (j < n && /[0-9]/.test(s[j])) j++
      }
      push('number', s.slice(i, j))
      i = j
      continue
    }
    if (isNameStart(c)) {
      let j = i + 1
      while (j < n && isNamePart(s[j])) j++
      push('word', s.slice(i, j))
      i = j
      continue
    }
    if (c === ':' && c1 === ':') { push('punct', '::'); i += 2; continue }
    const two = s.slice(i, i + 2)
    if (['<=', '>=', '<>', '!=', '||', '->', '=>'].includes(two)) { push('punct', two); i += 2; continue }
    push('punct', c)
    i++
  }
  return out
}

const lower = (tk) => (tk && tk.t === 'word' ? tk.v.toLowerCase() : null)

/** Ana biçimlendirici. Çok uzun metin (> {@link FORMAT_MAX_CHARS}) olduğu gibi döner. */
export function formatSql(src) {
  const text = String(src ?? '')
  if (!text.trim() || text.length > FORMAT_MAX_CHARS) return text
  const toks = lexForFormat(text)
  const lines = []
  let line = ''
  let lineHasCode = false
  // Seviye yığını: her parantez bir seviye. Alt sorgu parantezi (`sub`) yeni yerleşim düzeyi açar; yan cümle kuralları
  // yalnız alt sorgu / ifade düzeyinde işler (fonksiyon / pencere parantezinin içinde değil).
  const stack = [{ sub: true, base: 0, clause: null, between: false }]
  const top = () => stack[stack.length - 1]
  const contIndent = (lv) => (lv.sub ? lv.base + (lv.clause ? 1 : 0) : lv.base + 1)

  const breakLine = (indent) => {
    if (lineHasCode) lines.push(line.replace(/\s+$/, ''))
    line = INDENT.repeat(indent)
    lineHasCode = false
  }
  const emit = (str, space) => {
    if (lineHasCode && space) line += ' '
    line += str
    lineHasCode = true
  }

  let mustBreak = false   // satır yorumundan sonra sonraki belirteç YENİ satırda (yoruma girmesin)
  for (let k = 0; k < toks.length; k++) {
    const tk = toks[k]
    const lw = lower(tk)
    const prev = toks[k - 1]
    const lv = top()

    // Aralık: kaynaktaki boşluk korunur; belirli simgelerin çevresinde yok, virgülden sonra var
    let space = tk.ws
    if (tk.t === 'punct' && [',', ')', ';', '.', '::', ']'].includes(tk.v)) space = false
    if (prev && prev.t === 'punct' && ['(', '.', '::', '['].includes(prev.v)) space = false
    if (prev && prev.t === 'punct' && prev.v === ',') space = true

    let out = tk.v
    if (tk.t === 'word') {
      const next = toks[k + 1]
      const qualified = (prev && prev.t === 'punct' && prev.v === '.') || (next && next.t === 'punct' && next.v === '.')
      if (!qualified && (KEYWORDS.has(lw) || EXTRA_KEYWORDS.has(lw))) out = tk.v.toUpperCase()
    }

    let nlIndent = null
    if (mustBreak) { nlIndent = contIndent(lv); mustBreak = false }

    if (tk.t === 'word' && lv.sub) {
      const nextLw = lower(toks[k + 1])
      const prevLw = lower(prev)
      const isTwo = Boolean(TWO_WORD[lw]) && nextLw === TWO_WORD[lw]
      const joinStart = JOIN_LEAD.has(lw) && !JOIN_LEAD.has(prevLw) && (lw === 'join' || hasJoinAhead(toks, k))
      // `WITH` yalnız ifade başında yan cümle (WITH TIME ZONE / WITH ORDINALITY değil)
      const withStart = lw !== 'with' || !prev || prev.t === 'lcomment' || prev.t === 'bcomment'
        || (prev.t === 'punct' && (prev.v === '(' || prev.v === ';'))
      if ((CLAUSES.has(lw) && withStart) || isTwo || joinStart) {
        nlIndent = lv.base
        lv.clause = isTwo ? `${lw} ${nextLw}` : (joinStart ? 'join' : lw)
        lv.between = false
      } else if (lw === 'on' && lv.clause === 'join') {
        lv.clause = 'on'
        lv.between = false
      } else if ((lw === 'and' || lw === 'or') && COND_CLAUSES.has(lv.clause)) {
        if (lw === 'and' && lv.between) lv.between = false
        else nlIndent = lv.base + 1
      } else if (lw === 'between' && COND_CLAUSES.has(lv.clause)) {
        lv.between = true
      }
    }

    if (tk.t === 'punct' && tk.v === ',' && lv.sub && lv.clause === 'select') {
      if (nlIndent != null) breakLine(nlIndent)
      emit(',', false)
      breakLine(lv.base + 1)
      continue
    }
    if (tk.t === 'punct' && tk.v === ';') {
      if (nlIndent != null) breakLine(nlIndent)
      emit(';', false)
      stack.length = 1
      stack[0].clause = null
      stack[0].between = false
      breakLine(0)
      continue
    }
    if (tk.t === 'punct' && tk.v === '(') {
      if (nlIndent != null) breakLine(nlIndent)
      const nextLw = lower(nextCode(toks, k + 1))
      const sub = nextLw === 'select' || nextLw === 'with' || nextLw === 'values'
      emit('(', space)
      stack.push(sub ? { sub: true, base: lv.base + 1, clause: null, between: false } : { sub: false, base: contIndent(lv), clause: null, between: false })
      continue
    }
    if (tk.t === 'punct' && tk.v === ')' && stack.length > 1) {
      const closing = stack.pop()
      if (closing.sub) {
        breakLine(top().base)
        emit(')', false)
        continue
      }
      if (nlIndent != null) breakLine(nlIndent)
      emit(')', false)
      continue
    }

    if (nlIndent != null) breakLine(nlIndent)
    emit(out, space)
    if (tk.t === 'lcomment') mustBreak = true
  }
  if (lineHasCode) lines.push(line.replace(/\s+$/, ''))
  return lines.join('\n')
}

/** k'dan sonraki ilk yorum olmayan belirteç. */
function nextCode(toks, k) {
  for (let j = k; j < toks.length; j++) if (toks[j].t !== 'lcomment' && toks[j].t !== 'bcomment') return toks[j]
  return null
}

/** LEFT / RIGHT / INNER / FULL / CROSS / NATURAL / OUTER sözcüğünden sonra (≤ 3 sözcük içinde) JOIN geliyor mu. */
function hasJoinAhead(toks, k) {
  for (let j = k + 1; j < Math.min(toks.length, k + 4); j++) {
    const w = lower(toks[j])
    if (w === 'join') return true
    if (!JOIN_LEAD.has(w)) return false
  }
  return false
}
