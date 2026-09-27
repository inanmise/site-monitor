/**
 * Hafif SQL sözdizimi boyası — bağımlılık yok (Prism/CodeMirror yüklenmez).
 *
 * `tokenizeSql` metni kayıpsız parçalara böler (parçaların birleşimi = girdi; test bunu pinler) ve her parçaya
 * bir tür verir. `highlightSql` React düğümleri döndürür; renkler Tailwind jetonları + `dark:` karşılıkları.
 * Boya YALNIZ renk değiştirir: kalınlık/italik yok — eş aralıklı yazıda bile sentetik kalın/italik glif
 * genişliğini oynatabilir ve üstteki şeffaf textarea ile hizayı bozar (SqlEditor).
 */

const KEYWORDS = new Set(`
  select from where and or not in is null as on join inner left right full outer cross natural using
  group by order having limit offset fetch first next rows row only distinct all any some exists between like ilike
  similar case when then else end union intersect except with recursive lateral asc desc nulls last
  true false unknown cast interval filter over partition window range preceding following unbounded current
  values default collate escape array returning materialized not
`.trim().split(/\s+/))

const TYPES = new Set(`
  int int2 int4 int8 integer bigint smallint numeric decimal real double precision float float4 float8
  text varchar char character varying boolean bool date time timestamp timestamptz zone uuid json jsonb bytea serial bigserial
`.trim().split(/\s+/))

/** Tür → sınıf. `plain` boyanmaz. */
export const TOKEN_CLASS = {
  keyword: 'text-sky-700 dark:text-sky-300',
  type: 'text-teal-700 dark:text-teal-300',
  function: 'text-violet-700 dark:text-violet-300',
  string: 'text-emerald-700 dark:text-emerald-300',
  number: 'text-amber-700 dark:text-amber-300',
  comment: 'text-muted-foreground',
  operator: 'text-muted-foreground',
  quoted: 'text-foreground',
  param: 'text-rose-700 dark:text-rose-300',
}

// Sıra önemli: yorum/dize önce (içlerindeki anahtar sözcük boyanmasın).
const RULES = [
  ['comment', /--[^\n]*/y],
  ['comment', /\/\*[\s\S]*?(?:\*\/|$)/y],
  ['string', /[eE]?'(?:[^']|'')*'?/y],
  ['quoted', /"(?:[^"]|"")*"?/y],
  ['string', /\$\$[\s\S]*?(?:\$\$|$)/y],
  ['number', /\b\d+(?:\.\d+)?(?:[eE][+-]?\d+)?\b/y],
  ['param', /[:$]\w+/y],
  ['word', /[A-Za-z_][A-Za-z0-9_$]*/y],
  ['operator', /::|<=|>=|<>|!=|\|\||[-+*/%=<>(),.;[\]]/y],
  ['space', /\s+/y],
]

/** Kayıpsız parçalama: [{ type, text }]. */
export function tokenizeSql(input) {
  const src = String(input ?? '')
  const out = []
  let i = 0
  while (i < src.length) {
    let matched = false
    for (const [type, rx] of RULES) {
      rx.lastIndex = i
      const m = rx.exec(src)
      if (!m || m[0].length === 0) continue
      let kind = type
      if (type === 'word') {
        const w = m[0].toLowerCase()
        if (KEYWORDS.has(w)) kind = 'keyword'
        else if (TYPES.has(w)) kind = 'type'
        else if (/^\s*\(/.test(src.slice(i + m[0].length, i + m[0].length + 8))) kind = 'function'
        else kind = 'plain'
      } else if (type === 'space') kind = 'plain'
      const prev = out[out.length - 1]
      if (kind === 'plain' && prev?.type === 'plain') prev.text += m[0]
      else out.push({ type: kind, text: m[0] })
      i += m[0].length
      matched = true
      break
    }
    if (!matched) {
      const prev = out[out.length - 1]
      if (prev?.type === 'plain') prev.text += src[i]
      else out.push({ type: 'plain', text: src[i] })
      i++
    }
  }
  return out
}

/** React düğümleri — SqlEditor'ın `pre` katmanı. */
export function highlightSql(input) {
  return tokenizeSql(input).map((tk, i) => (
    tk.type === 'plain'
      ? tk.text
      : <span key={i} data-token={tk.type} className={TOKEN_CLASS[tk.type]}>{tk.text}</span>
  ))
}
