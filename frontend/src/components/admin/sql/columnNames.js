import { lexForFormat } from './sqlFormat.js'

/**
 * Aynı adlı sonuç kolonları (2026-11-13, son inceleme DÜŞÜK 5).
 *
 * Sunucu ekran sonucunu satır başına AD → değer haritası olarak döndürür (büyük / küçük harf duyarsız, ilk değer kalır):
 * `SELECT a.id, b.id` iki `id` kolonundan yalnız ilkini taşır, ikincisi sessizce düşer. Yanıtta kolon listesi yok;
 * bu yüzden ekran yalnız UYARABİLİR — en dış SELECT listesinin çıktı adları sorgu metninden çıkarılır (PostgreSQL ad
 * kuralının yaklaşığı: takma ad, yoksa son kolon adı, fonksiyon adı, `case`, aksi `?column?`; `*` bilinemez) ve tekrar
 * eden adlar not olarak gösterilir. Tam dışa aktarımın JSON'u ise kolon başlıklarını ve satırları DİZİ olarak taşır: orada
 * başlıklar ayrıştırılır (`id`, `id_2`) — hiçbir değer kaybolmaz.
 *
 * Doğrusal: belirteçler bir kez taranır (biçimlendiricinin sözlüksel ayırıcısı — dize / yorum / dolar bloğu güvenli).
 */

const CLAUSE_END = new Set(['from', 'into', 'where', 'group', 'order', 'having', 'limit', 'offset', 'fetch', 'union', 'intersect',
  'except', 'window', 'for'])
/** AS'siz takma ad sanılmaması gereken sözcükler (ifadeyi bitiren anahtar sözcükler). */
const NOT_ALIAS = new Set(['and', 'or', 'not', 'is', 'null', 'in', 'like', 'ilike', 'between', 'end', 'then', 'else', 'when',
  'asc', 'desc', 'over', 'filter', 'within', 'from', 'as', 'distinct', 'collate', 'at', 'true', 'false', 'unknown'])

const isName = (tk) => Boolean(tk) && (tk.t === 'word' || tk.t === 'quoted')
const isP = (tk, v) => Boolean(tk) && tk.t === 'punct' && tk.v === v

/** Çift tırnaklı ad → içeriği (`""` kaçışı açılır); düz ad → küçük harf (PostgreSQL katlaması). */
function identName(tk) {
  if (tk?.t === 'quoted') {
    const inner = tk.v.length > 1 && tk.v.endsWith('"') ? tk.v.slice(1, -1) : tk.v.slice(1)
    return inner.split('""').join('"')
  }
  if (tk?.t === 'word') return tk.v.toLowerCase()
  return null
}

/** `start`taki açılan parantezin kapanış dizini; yoksa -1. */
function closeOf(tk, start) {
  let d = 0
  for (let k = start; k < tk.length; k++) {
    if (isP(tk[k], '(')) d++
    else if (isP(tk[k], ')')) { d--; if (d === 0) return k }
  }
  return -1
}

/** Sondaki `::tip[(…)][[]]` dönüşümlerini atar (`a::text` → `a`, `count(*)::int` → `count(*)`). */
function stripCasts(tk) {
  let end = tk.length
  for (;;) {
    let found = -1
    let d = 0
    for (let k = end - 1; k >= 0; k--) {
      const x = tk[k]
      if (isP(x, ')')) d++
      else if (isP(x, '(')) { if (d === 0) break; d-- }
      else if (d === 0 && isP(x, '::')) { found = k; break }
      else if (d === 0 && !(isName(x) || x.t === 'number' || isP(x, '.') || isP(x, '[') || isP(x, ']'))) break
    }
    if (found <= 0) return tk.slice(0, end)
    end = found
  }
}

/** Tek seçim öğesinin çıktı adı; bilinemezse (`*`, `t.*`) null. */
function itemName(tokens) {
  const tk = tokens.filter((x) => x.t !== 'lcomment' && x.t !== 'bcomment')
  if (!tk.length) return null
  const last = tk[tk.length - 1]
  const prev = tk.length > 1 ? tk[tk.length - 2] : null
  if (isP(last, '*')) return null
  // `ifade AS ad`
  if (isName(last) && prev?.t === 'word' && prev.v.toLowerCase() === 'as') return identName(last)
  // `ifade ad` (AS'siz takma ad): öncesi bir değeri bitirir ve son sözcük ifade anahtar sözcüğü değil
  if (isName(last) && prev && (isName(prev) || prev.t === 'number' || prev.t === 'string' || prev.t === 'dollar' || isP(prev, ')'))
    && !(last.t === 'word' && NOT_ALIAS.has(last.v.toLowerCase()))
    && !(prev.t === 'word' && ['case', 'when', 'then', 'else', 'and', 'or', 'not', 'is'].includes(prev.v.toLowerCase()))) {
    return identName(last)
  }
  const core = stripCasts(tk)
  // `a`, `t.a`, `s.t.a` → son ad
  if (core.length % 2 === 1 && core.every((x, k) => (k % 2 === 0 ? isName(x) : isP(x, '.')))) return identName(core[core.length - 1])
  // `f(…)`, `s.f(…)`, ardından OVER / FILTER / WITHIN GROUP olabilir → fonksiyon adı
  let p = 0
  while (p + 2 < core.length && isName(core[p]) && isP(core[p + 1], '.')) p += 2
  if (core[p]?.t === 'word' && isP(core[p + 1], '(')) {
    const close = closeOf(core, p + 1)
    const after = core[close + 1]
    if (close === core.length - 1 || (after?.t === 'word' && ['over', 'filter', 'within'].includes(after.v.toLowerCase()))) {
      return core[p].v.toLowerCase()
    }
  }
  if (core[0]?.t === 'word' && core[0].v.toLowerCase() === 'case') return 'case'
  return '?column?'
}

/**
 * En dış sorgunun (WITH gövdeleri ve alt sorgular dışında) seçim listesinin çıktı adları, sırasıyla. Bilinmeyen öğe null.
 * Seçim bulunamazsa boş dizi.
 */
export function selectListNames(sql) {
  const tokens = lexForFormat(sql)
  let depth = 0
  let i = -1
  for (let k = 0; k < tokens.length; k++) {
    const tk = tokens[k]
    if (isP(tk, '(')) depth++
    else if (isP(tk, ')')) depth = Math.max(0, depth - 1)
    else if (depth === 0 && tk.t === 'word' && tk.v.toLowerCase() === 'select') { i = k + 1; break }
  }
  if (i < 0) return []
  const lw = (k) => (tokens[k]?.t === 'word' ? tokens[k].v.toLowerCase() : null)
  if (lw(i) === 'all') i++
  else if (lw(i) === 'distinct') {
    i++
    if (lw(i) === 'on' && isP(tokens[i + 1], '(')) {
      const close = closeOf(tokens, i + 1)
      i = close < 0 ? tokens.length : close + 1
    }
  }
  const names = []
  let cur = []
  depth = 0
  for (; i < tokens.length; i++) {
    const tk = tokens[i]
    if (isP(tk, '(') || isP(tk, '[')) depth++
    else if (isP(tk, ')') || isP(tk, ']')) { if (depth === 0) break; depth-- }
    if (depth === 0 && tk.t === 'word' && CLAUSE_END.has(tk.v.toLowerCase())) break
    if (depth === 0 && isP(tk, ';')) break
    if (depth === 0 && isP(tk, ',')) { names.push(itemName(cur)); cur = []; continue }
    cur.push(tk)
  }
  if (cur.length) names.push(itemName(cur))
  return names
}

/**
 * Tekrar eden adlar (sunucu haritası gibi büyük / küçük harf duyarsız), ilk görülme sırasıyla. null (bilinmeyen) sayılmaz.
 * @param names ad dizisi (sunucunun kolon listesi ya da {@link selectListNames})
 */
export function duplicateColumnNames(names) {
  const seen = new Map()
  const dup = []
  for (const n of Array.isArray(names) ? names : []) {
    if (n == null) continue
    const k = String(n).toLowerCase()
    const c = (seen.get(k) ?? 0) + 1
    seen.set(k, c)
    if (c === 2) dup.push(String(n))
  }
  return dup
}

/**
 * Başlıkları benzersizleştirir (büyük / küçük harf duyarsız): ikinci `id` → `id_2`, üçüncüsü `id_3`; aday başka bir
 * gerçek kolonun adıysa sayaç artar. Boş başlık `column`. @returns {{ names: string[], renamed: Array<{ from, to }> }}
 */
export function uniqueColumnNames(columns) {
  const list = (Array.isArray(columns) ? columns : []).map((c) => (c == null || String(c) === '' ? 'column' : String(c)))
  const reserved = new Set(list.map((c) => c.toLowerCase()))
  const taken = new Set()
  const names = []
  const renamed = []
  for (const c of list) {
    const k = c.toLowerCase()
    if (!taken.has(k)) { taken.add(k); names.push(c); continue }
    let n = 2
    let cand = `${c}_${n}`
    // Sınırlı: her atlama dolu bir ada karşılık gelir (en çok 2 × kolon sayısı)
    while ((taken.has(cand.toLowerCase()) || reserved.has(cand.toLowerCase())) && n <= list.length * 2 + 2) cand = `${c}_${++n}`
    taken.add(cand.toLowerCase())
    names.push(cand)
    renamed.push({ from: c, to: cand })
  }
  return { names, renamed }
}
