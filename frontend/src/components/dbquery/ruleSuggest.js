// Veritabanı Sorgu İzleme — "akıllı kural kurulumu" SAF modeli (2026-10-31, 2.C). Deneme sonucunun KOLON ADLARI ve
// TÜRLERİNDEN (satır verisi değil) kural önerir; COUNT(*) tuzağını (tek satır dönen toplama + "satır sayısı" kuralı —
// kural hiç tetiklenmez) yakalar. Sunucu uyarısı (`warnings` [{code: ROW_COUNT_ON_AGGREGATE}]) asıl kanıttır; sunucu
// uyarısı yokken deneme sonucu + sorgu metni istemci tarafında aynı tuzağı gösterir (eski sunucu / henüz kaydedilmemiş).

import { RULE_KINDS, changeKind, newRule } from './ruleModel.js'

/** Sunucunun deneme / kayıt uyarı kodları (engellemez). */
export const WARNING_CODES = Object.freeze(['ROW_COUNT_ON_AGGREGATE', 'SELECT_STAR', 'SENSITIVE_UNMASKED'])

/** Tür metni ≤ 64 karakter — düzenli ifadeler kısa metinde koşar (doğrusal, sınırlı). */
const TYPE_MAX = 64
const NUMERIC_TYPE = /^(int|integer|int2|int4|int8|bigint|smallint|tinyint|mediumint|numeric|decimal|dec|number|float|float4|float8|real|double|money|smallmoney|serial|bigserial|smallserial|binary_float|binary_double)\b/
const TIME_TYPE = /(timestamp|datetime|smalldatetime|datetimeoffset|^date$|^time$|^date\b|^time\b|timestamptz|timetz)/
const TEXT_TYPE = /(char|text|string|clob|uuid|uniqueidentifier|json|xml|citext|name)/

/**
 * Kolon türü → `number | time | text | other`. JDBC / sürücü tür adları (PostgreSQL `int8` / `timestamptz` / `varchar`,
 * SQL Server `bigint` / `datetime2` / `nvarchar`, Oracle `NUMBER` / `DATE` / `TIMESTAMP(6)` / `VARCHAR2`).
 */
export function columnKind(type) {
  const s = String(type || '').trim().toLowerCase().slice(0, TYPE_MAX)
  if (!s) return 'other'
  if (NUMERIC_TYPE.test(s)) return 'number'
  if (TIME_TYPE.test(s)) return 'time'
  if (TEXT_TYPE.test(s)) return 'text'
  return 'other'
}

/** Sorgu metni okunurken üst sınır — istemci ipucu; uzun metinde yalnız baş kısım okunur. */
const SQL_SCAN_MAX = 20000

/** Sorgunun seçim listesi bir toplama işleviyle mi başlıyor ve GROUP BY yok mu (tek satır dönen özet sorgusu). */
export function isAggregateOnlySql(sql) {
  const s = String(sql || '').slice(0, SQL_SCAN_MAX)
  // Yalnız baştaki SELECT — WITH / yorumla başlayan sorguda sunucu uyarısı asıl kanıttır (istemci uydurmaz)
  if (!/^\s*select\s+(?:top\s+\d+\s+)?(?:distinct\s+)?(count|sum|max|min|avg)\s*\(/i.test(s)) return false
  return !/\bgroup\s+by\b/i.test(s)
}

/** Uyarı listesi (sunucu biçimi) → yalnız tanınan kodlu öğeler; `{code, rule_id?, rule_index?, columns?, message?}`. */
export function knownWarnings(list) {
  return (Array.isArray(list) ? list : []).filter((w) => w && typeof w === 'object' && typeof w.code === 'string')
}

/**
 * COUNT tuzağına düşen kuralların sıraları. Sunucu uyarısı `rule_id` / `rule_index` taşıyorsa yalnız o kural; taşımıyorsa
 * tüm "satır sayısı" kuralları. Sunucu uyarısı yoksa: son deneme tek satır döndürdü VE sorgu bir toplamayla başlıyorsa
 * tüm satır sayısı kuralları (istemci kanıtı — kaydı engellemez).
 */
export function countTrapIndexes(rules, { warnings, testResult, sqlText } = {}) {
  const list = Array.isArray(rules) ? rules : []
  const rowCount = list.map((r, i) => (r?.kind === 'ROW_COUNT' ? i : -1)).filter((i) => i >= 0)
  if (!rowCount.length) return new Set()
  const trap = knownWarnings(warnings).filter((w) => w.code === 'ROW_COUNT_ON_AGGREGATE')
  if (trap.length) {
    const out = new Set()
    for (const w of trap) {
      const byId = w.rule_id != null ? list.findIndex((r) => r?.id === w.rule_id) : -1
      const byIdx = Number.isInteger(w.rule_index) ? w.rule_index : -1
      const hit = byId >= 0 ? byId : byIdx
      if (hit >= 0 && list[hit]?.kind === 'ROW_COUNT') out.add(hit)
      else if (w.rule_id == null && w.rule_index == null) rowCount.forEach((i) => out.add(i))
    }
    return out
  }
  if (testResult && testResult.row_count === 1 && !testResult.row_count_capped && isAggregateOnlySql(sqlText)) return new Set(rowCount)
  return new Set()
}

/** Önerilecek "değer" kolonu: denemedeki ilk sayısal kolon (yoksa ilk kolon); kolon bilinmiyorsa null. */
export function primaryValueColumn(columns) {
  const cols = (Array.isArray(columns) ? columns : []).filter((c) => c && typeof c.name === 'string' && c.name)
  if (!cols.length) return null
  return (cols.find((c) => columnKind(c.type) === 'number') || cols[0]).name
}

/**
 * Satır sayısı kuralını "kolon değeri" kuralına çevirir (ilk satırdaki değer). Kimlik, ad, işleç ve eşikler korunur;
 * kolon verildiyse o (yoksa kullanıcı seçer).
 */
export function toColumnValueRule(rule, column = null) {
  const next = changeKind(rule, 'COLUMN_VALUE')
  return { ...next, aggregate: 'FIRST', column: column || next.column || null }
}

/**
 * Yeni kuralın varsayılanı: son deneme TEK satır döndürdüyse ve sayısal bir kolon varsa o kolonda "ilk satırdaki değer";
 * aksi hâlde satır sayısı (bugünkü varsayılan).
 */
export function defaultNewRule(testResult, columns) {
  if (testResult && testResult.row_count === 1 && !testResult.row_count_capped) {
    const num = (Array.isArray(columns) ? columns : []).find((c) => columnKind(c?.type) === 'number')
    if (num) return { ...newRule('COLUMN_VALUE'), column: num.name }
  }
  return newRule('ROW_COUNT')
}

/** Kolondan kural kurma seçenekleri (deneme panelindeki kolon menüsü): türe göre önerilen önce. */
export function ruleOptionsFor(column) {
  const kind = columnKind(column?.type)
  if (kind === 'time') return ['FRESHNESS', 'COLUMN_VALUE', 'TEXT_MATCH']
  if (kind === 'text') return ['TEXT_MATCH', 'COLUMN_VALUE']
  return ['COLUMN_VALUE', 'TEXT_MATCH', 'FRESHNESS']
}

/** Seçilen kolondan yeni kural (tür RULE_KINDS dışındaysa kolon değeri). */
export function ruleFromColumn(column, kind) {
  const k = RULE_KINDS.includes(kind) ? kind : 'COLUMN_VALUE'
  return { ...newRule(k), column: column?.name || null }
}
