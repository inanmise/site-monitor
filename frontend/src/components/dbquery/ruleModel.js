// Veritabanı Sorgu İzleme — kural oluşturucunun SAF modeli (2026-10-31). Kural biçimi sunucuyla tek sözleşme
// (dbquery-api.md §4, DbQueryRules.java): { id, label, kind, column, aggregate, operator, scope, warning, critical, change }.
// Arayüz metni burada ÜRETİLMEZ; insan dilindeki özet `ruleSummary(rule, t, lang)` sözlük kalıplarını doldurur.

export const MAX_RULES = 10
/** Sunucu sınırları (`DbQueryRules.MAX_LABEL / MAX_COLUMN / MAX_TEXT`): ad ve kolon kırpılmış, aranan metin olduğu gibi. */
export const MAX_LABEL = 120
export const MAX_COLUMN = 128
export const MAX_TEXT = 500
export const RULE_KINDS = ['ROW_COUNT', 'COLUMN_VALUE', 'TEXT_MATCH', 'CHANGE']
export const RULE_LOGICS = ['ANY', 'ALL']
export const AGGREGATES = ['FIRST', 'MAX', 'MIN', 'SUM', 'AVG', 'COUNT_NONNULL']
export const NUMERIC_OPERATORS = ['GT', 'GTE', 'LT', 'LTE', 'EQ', 'NEQ', 'BETWEEN', 'OUTSIDE']
export const TEXT_OPERATORS = ['CONTAINS', 'NOT_CONTAINS', 'EQUALS', 'NOT_EQUALS', 'EMPTY', 'NOT_EMPTY']
export const SCOPES = ['ANY_ROW', 'ALL_ROWS', 'FIRST_ROW']
export const CHANGE_OF = ['ROW_COUNT', 'COLUMN']
export const CHANGE_MODES = ['PERCENT', 'ABSOLUTE']
export const CHANGE_DIRECTIONS = ['INCREASE', 'DECREASE', 'ANY']
export const LEVELS = ['warning', 'critical']

const RANGE = new Set(['BETWEEN', 'OUTSIDE'])
const NO_VALUE = new Set(['EMPTY', 'NOT_EMPTY'])

export const isRangeOperator = (op) => RANGE.has(op)
export const isNoValueOperator = (op) => NO_VALUE.has(op)
export const isTextKind = (kind) => kind === 'TEXT_MATCH'
/** Kolon gerektiren kural mı (COLUMN_VALUE, TEXT_MATCH, kolonun değişimi). */
export function needsColumn(rule) {
  if (!rule) return false
  if (rule.kind === 'COLUMN_VALUE' || rule.kind === 'TEXT_MATCH') return true
  return rule.kind === 'CHANGE' && rule.change?.of === 'COLUMN'
}
/** Toplama (FIRST/MAX/…) gerektiren kural mı. */
export function needsAggregate(rule) {
  if (!rule) return false
  return rule.kind === 'COLUMN_VALUE' || (rule.kind === 'CHANGE' && rule.change?.of === 'COLUMN')
}
export const operatorsFor = (kind) => (isTextKind(kind) ? TEXT_OPERATORS : NUMERIC_OPERATORS)

/** Kural kimliği `r-<zaman><rastgele>` (sözleşme) — iki hızlı ekleme aynı kimliği almaz. */
export function newRuleId(now = Date.now(), rand = Math.random) {
  const r = Math.floor(rand() * 36 ** 4).toString(36).padStart(4, '0')
  return `r-${now.toString(36)}${r}`
}

/** Türe göre boş kural (eşikler boş — kullanıcı girer). */
export function newRule(kind = 'ROW_COUNT', id = newRuleId()) {
  const base = { id, label: '', kind, column: null, aggregate: null, operator: 'GT', scope: null, warning: null, critical: null, change: null }
  if (kind === 'COLUMN_VALUE') return { ...base, aggregate: 'FIRST' }
  if (kind === 'TEXT_MATCH') return { ...base, operator: 'CONTAINS', scope: 'ANY_ROW' }
  if (kind === 'CHANGE') return { ...base, change: { of: 'ROW_COUNT', mode: 'PERCENT', direction: 'ANY' } }
  return base
}

/**
 * Tür değişimi: kimlik ve etiket korunur, türe uymayan alanlar sıfırlanır. Sayısal türler arasında (ROW_COUNT ↔
 * COLUMN_VALUE ↔ CHANGE) eşikler ve işleç KALIR (kullanıcının yazdığı kaybolmasın); metne geçişte/metinden çıkışta düşer.
 */
export function changeKind(rule, kind) {
  if (!rule || rule.kind === kind) return rule
  const fresh = newRule(kind, rule.id)
  const keepNumbers = !isTextKind(kind) && !isTextKind(rule.kind)
  const out = { ...fresh, label: rule.label ?? '' }
  if (keepNumbers) {
    out.operator = NUMERIC_OPERATORS.includes(rule.operator) ? rule.operator : fresh.operator
    out.warning = rule.warning ?? null
    out.critical = rule.critical ?? null
  }
  if (needsColumn(out) && rule.column) out.column = rule.column
  if (needsAggregate(out) && rule.aggregate) out.aggregate = rule.aggregate
  return out
}

/** İşleç değişimi — tek değer ↔ aralık biçimine eşiği taşır (yazılan sayı kaybolmaz). */
export function changeOperator(rule, operator) {
  if (!rule || rule.operator === operator) return rule
  const toRange = isRangeOperator(operator)
  const fromRange = isRangeOperator(rule.operator)
  const conv = (v) => {
    if (v == null) return v
    if (toRange && !fromRange) return Array.isArray(v) ? v : [v, null]
    if (!toRange && fromRange) return Array.isArray(v) ? (v[0] ?? v[1] ?? null) : v
    return v
  }
  const out = { ...rule, operator, warning: conv(rule.warning), critical: conv(rule.critical) }
  if (isNoValueOperator(operator)) {
    // Değersiz işleç (EMPTY / NOT_EMPTY): seçili seviye alanı BOŞ METİN taşır (Threshold.text = "" ≠ null → tanımlı)
    const lvl = textLevel(rule)
    out.warning = lvl === 'warning' ? '' : null
    out.critical = lvl === 'critical' ? '' : null
  } else if (isNoValueOperator(rule.operator)) {
    out.warning = rule.warning === '' ? null : rule.warning
    out.critical = rule.critical === '' ? null : rule.critical
  }
  return out
}

/** Metin kuralının (tek seviye) seviyesi: hangi alan doluysa; ikisi boşsa kritik. */
export function textLevel(rule) {
  if (!rule) return 'critical'
  if (rule.warning != null && (rule.critical == null)) return 'warning'
  return 'critical'
}

/** Metin kuralında seviye değişimi — değer korunur, diğer alana taşınır. */
export function setTextLevel(rule, level) {
  // değer yoksa boş metin: seviye seçimi kalıcı olsun (iki alan da null olunca seviye 'kritik'e düşerdi)
  const value = rule.critical ?? rule.warning ?? ''
  return level === 'warning'
    ? { ...rule, warning: value, critical: null }
    : { ...rule, critical: value, warning: null }
}

// ── Sayı biçimi (TR "1.250,5" / EN "1,250.5") ───────────────────────────────────────────────────────────────────────────

const GROUP_DOT = /^\d{1,3}(\.\d{3})+$/
const GROUP_COMMA = /^\d{1,3}(,\d{3})+$/

/**
 * Kullanıcının yazdığı sayıyı çözer. TR: virgül ondalık, nokta binlik ("1.250,5" → 1250.5; "1.250" → 1250; "1.5" binlik
 * kalıbına uymadığı için 1.5). EN: tersi. Boş → null; çözülemeyen → NaN. Uzunluk sınırlı (doğrusal, ≤ 40 karakter).
 */
export function parseNumber(text, lang = 'tr') {
  if (text == null) return null
  if (typeof text === 'number') return Number.isFinite(text) ? text : NaN
  let s = String(text).replace(/[\s\u00A0\u202F]/g, '')
  if (s === '') return null
  if (s.length > 40) return NaN
  let sign = ''
  if (s[0] === '-' || s[0] === '+') { sign = s[0] === '-' ? '-' : ''; s = s.slice(1) }
  if (!/^[\d.,]+$/.test(s)) return NaN
  const dec = lang === 'en' ? '.' : ','
  const grp = lang === 'en' ? ',' : '.'
  if (s.includes(dec)) {
    const [intPart, frac, extra] = s.split(dec)
    if (extra !== undefined || frac === '' || frac.includes(grp)) return NaN
    const intClean = intPart.includes(grp) ? (((lang === 'en' ? GROUP_COMMA : GROUP_DOT).test(intPart)) ? intPart.split(grp).join('') : null) : intPart
    if (intClean == null || intClean === '') return NaN
    return Number(`${sign}${intClean}.${frac}`)
  }
  if (s.includes(grp)) {
    if ((lang === 'en' ? GROUP_COMMA : GROUP_DOT).test(s)) return Number(sign + s.split(grp).join(''))
    // Tek ayraç binlik kalıbına uymuyor ("1.5" TR'de, "1,5" EN'de): kullanıcı ondalık demek istemiştir
    const parts = s.split(grp)
    if (parts.length !== 2 || parts[0] === '' || parts[1] === '') return NaN
    return Number(`${sign}${parts[0]}.${parts[1]}`)
  }
  return Number(sign + s)
}

/** Sayı → dilin biçimi (gruplu, ≤ 6 ondalık). Sayı değilse olduğu gibi metne çevrilir. */
export function formatNumber(n, lang = 'tr') {
  if (typeof n !== 'number' || !Number.isFinite(n)) return n == null ? '' : String(n)
  return new Intl.NumberFormat(lang === 'en' ? 'en-GB' : 'tr-TR', { maximumFractionDigits: 6 }).format(n)
}

/** Düzenleme kutusunun gösterimi: gruplamasız (yazarken ayraç kayması olmasın), dilin ondalık ayracıyla. */
export function formatInputNumber(n, lang = 'tr') {
  if (typeof n !== 'number' || !Number.isFinite(n)) return n == null ? '' : String(n)
  const s = String(n)
  return lang === 'en' ? s : s.replace('.', ',')
}

// ── Türkçe çıkma eki (-den/-dan/-ten/-tan) — "10'dan büyükse", "%20'den büyükse" ────────────────────────────────────────

const UNIT_ABL = ['dan', 'den', 'den', 'ten', 'ten', 'ten', 'dan', 'den', 'den', 'dan']          // sıfır bir iki üç … dokuz
const TENS_ABL = [null, 'dan', 'den', 'dan', 'tan', 'den', 'tan', 'ten', 'den', 'dan']          // on yirmi otuz kırk …

/** Bir tamsayının Türkçe okunuşunun SON sözcüğüne göre çıkma eki. */
function intAbl(n) {
  if (!Number.isFinite(n)) return 'den'
  n = Math.abs(Math.trunc(n))
  if (n === 0) return 'dan'
  if (n % 10) return UNIT_ABL[n % 10]
  if (Math.floor(n / 10) % 10) return TENS_ABL[Math.floor(n / 10) % 10]
  if (Math.floor(n / 100) % 10) return 'den'                       // yüz
  if (n % 1e6 === 0) return 'dan'                                  // milyon / milyar / trilyon
  return 'den'                                                     // bin
}

/** "1,5" → "bir virgül beş" → beş'e göre; ondalıksız sayıda tamsayının son sözcüğü. */
export function trAblative(n) {
  if (typeof n !== 'number' || !Number.isFinite(n)) return 'den'
  const s = String(Math.abs(n))
  if (s.includes('e')) return intAbl(n)
  const frac = s.split('.')[1]
  return frac ? intAbl(Number(frac)) : intAbl(n)
}

/** Özet için değer metni: TR'de çıkma ekiyle ("10'dan"), EN'de yalın. `percent` → "%20" / "20%". */
export function valueText(n, lang, { percent = false, ablative = false } = {}) {
  const base = formatNumber(n, lang)
  const shown = percent ? (lang === 'en' ? `${base}%` : `%${base}`) : base
  if (lang === 'en' || !ablative || typeof n !== 'number') return shown
  return `${shown}'${trAblative(n)}`
}

// ── Doğrulama (istemci — anında geri bildirim; garanti sunucuda) ─────────────────────────────────────────────────────────

const isBlank = (v) => v == null || (typeof v === 'string' && v.trim() === '')
const isNum = (v) => typeof v === 'number' && Number.isFinite(v)

/** Eşik dolu mu (aralıkta iki uçtan biri yeter — eksik uç ayrıca hata). */
export function thresholdSet(rule, level) {
  const v = rule?.[level]
  if (isTextKind(rule?.kind)) return v != null && (isNoValueOperator(rule.operator) || !isBlank(v))
  if (Array.isArray(v)) return v.some((x) => !isBlank(x))
  return !isBlank(v)
}

/**
 * Tek kuralın istemci hataları → `{ alt: kod }` (kod → `dbq.rule.v.<kod>` metni). Boş kuralda "eşik yok" bir HATA değil
 * `missing_threshold` olarak döner; çağıran onu ipucu ya da (kaydetmeye çalışınca) hata olarak gösterir.
 */
export function validateRule(rule) {
  const out = {}
  if (!rule || !RULE_KINDS.includes(rule.kind)) { out.kind = 'kind'; return out }
  if (typeof rule.label === 'string' && rule.label.trim().length > MAX_LABEL) out.label = 'label_long'
  if (needsColumn(rule)) {
    if (isBlank(rule.column)) out.column = 'column'
    else if (String(rule.column).trim().length > MAX_COLUMN) out.column = 'column_long'
  }
  const levels = isTextKind(rule.kind) ? [rule.critical != null ? 'critical' : 'warning'] : LEVELS
  for (const lvl of levels) {
    const v = rule[lvl]
    if (isTextKind(rule.kind)) {   // boş metin = eşik yok (aşağıda missing_text); sunucu metni kırpmadan sayar
      if (typeof v === 'string' && v.length > MAX_TEXT) out[lvl] = 'text_long'
      continue
    }
    if (v == null) continue
    if (Array.isArray(v)) {
      const [lo, hi] = v
      const any = !isBlank(lo) || !isBlank(hi)
      if (!any) continue
      if ((!isBlank(lo) && !isNum(lo)) || (!isBlank(hi) && !isNum(hi))) out[lvl] = 'number'
      else if (isBlank(lo) || isBlank(hi)) out[lvl] = 'range_both'
      else if (lo > hi) out[lvl] = 'range_order'
      continue
    }
    // Negatif eşik sunucuda geçerli (değişimde artış / azalış işaretli karşılaştırılır) — burada HATA değil, ipucu
    if (!isBlank(v) && !isNum(v)) out[lvl] = 'number'
  }
  if (!thresholdSet(rule, 'warning') && !thresholdSet(rule, 'critical')) out.threshold = isTextKind(rule.kind) ? 'missing_text' : 'missing_threshold'
  return out
}

/**
 * Kural listesinin istemci hataları — sunucu alan adlarıyla (`rules[N].<alt>`). Sayfa kaydetmeden önce çağırabilir;
 * boş liste geçerlidir (kuralsız izleme yalnız hata / yavaşlık alarmı verir).
 */
export function validateRules(rules) {
  const out = {}
  const list = Array.isArray(rules) ? rules : []
  if (list.length > MAX_RULES) out.rules = 'too_many'
  list.forEach((r, i) => {
    for (const [k, code] of Object.entries(validateRule(r))) out[`rules[${i}].${k === 'threshold' ? 'warning' : k}`] = code
  })
  return out
}

/** Sıkılık uyarısı (hata değil): kritik eşik uyarıdan GEVŞEKSE uyarı hiç tetiklenmez — kullanıcıya söylenir. */
export function strictnessHint(rule) {
  if (!rule || isTextKind(rule.kind)) return null
  // Anlamsız negatif eşik (satır sayısı hiç negatif olmaz; "her iki yönde" değişim mutlak değerle ölçülür)
  const levelValues = LEVELS.flatMap((l) => (Array.isArray(rule[l]) ? rule[l] : [rule[l]])).filter(isNum)
  if (levelValues.some((n) => n < 0)) {
    if (rule.kind === 'ROW_COUNT') return 'negative_rows'
    if (rule.kind === 'CHANGE' && (rule.change?.direction || 'ANY') === 'ANY') return 'negative_any'
  }
  if (isRangeOperator(rule.operator)) return null
  const w = rule.warning, c = rule.critical
  if (!isNum(w) || !isNum(c)) return null
  const op = rule.operator
  if ((op === 'GT' || op === 'GTE') && c < w) return 'critical_looser'
  if ((op === 'LT' || op === 'LTE') && c > w) return 'critical_looser'
  if ((op === 'EQ' || op === 'NEQ') && c === w) return 'same_value'
  return null
}

// ── İnsan dilinde özet ─────────────────────────────────────────────────────────────────────────────────────────────────

function subjectOf(rule, t) {
  const col = rule.column && String(rule.column).trim() ? String(rule.column).trim() : t('dbq.rule.sum.noColumn')
  if (rule.kind === 'ROW_COUNT') return t('dbq.rule.sum.subj.rowCount')
  if (rule.kind === 'COLUMN_VALUE') return t(`dbq.rule.sum.subj.agg.${rule.aggregate || 'FIRST'}`, col)
  if (rule.kind === 'TEXT_MATCH') return t(`dbq.rule.sum.subj.text.${rule.scope || 'ANY_ROW'}`, col)
  const dir = rule.change?.direction || 'ANY'
  return rule.change?.of === 'COLUMN'
    ? t(`dbq.rule.sum.subj.change.col.${dir}`, col)
    : t(`dbq.rule.sum.subj.change.rows.${dir}`)
}

function numericPhrase(rule, value, t, lang) {
  const percent = rule.kind === 'CHANGE' && rule.change?.mode === 'PERCENT'
  const op = rule.operator || 'GT'
  if (isRangeOperator(op)) {
    const [lo, hi] = Array.isArray(value) ? value : [value, null]
    return t(`dbq.rule.op.${op}`, valueText(lo, lang, { percent }), valueText(hi, lang, { percent }))
  }
  const abl = op === 'GT' || op === 'LT'
  return t(`dbq.rule.op.${op}`, valueText(value, lang, { percent, ablative: abl }))
}

/**
 * Kuralın insan dilindeki özeti — "Satır sayısı 10'dan büyükse UYARI, 100'den büyükse KRİTİK." Eksik eşikte
 * `dbq.rule.sum.incomplete`; ayrıştırılamayan sayı metni olduğu gibi gösterilir (kullanıcı ne yazdığını görsün).
 */
export function ruleSummary(rule, t, lang = 'tr') {
  if (!rule) return ''
  const subject = subjectOf(rule, t)
  const hasW = thresholdSet(rule, 'warning')
  const hasC = thresholdSet(rule, 'critical')
  if (!hasW && !hasC) return t('dbq.rule.sum.incomplete', subject)
  if (isTextKind(rule.kind)) {
    const lvl = textLevel(rule)
    const v = rule[lvl]
    const phrase = t(`dbq.rule.op.${rule.operator || 'CONTAINS'}`, isBlank(v) ? '' : String(v))
    return t(lvl === 'warning' ? 'dbq.rule.sum.textWarn' : 'dbq.rule.sum.textCrit', subject, phrase)
  }
  // Değişim kuralının eki cümlenin İÇİNE, son noktadan önce girer: "… KRİTİK (önceki …)." — "KRİTİK. (önceki …)" değil
  const withSuffix = (text) => (rule.kind === 'CHANGE' ? beforeFinalStop(text, t('dbq.rule.sum.vsPrevious')) : text)
  if (hasW && hasC) return withSuffix(t('dbq.rule.sum.both', subject, numericPhrase(rule, rule.warning, t, lang), numericPhrase(rule, rule.critical, t, lang)))
  if (hasW) return withSuffix(t('dbq.rule.sum.warnOnly', subject, numericPhrase(rule, rule.warning, t, lang)))
  return withSuffix(t('dbq.rule.sum.critOnly', subject, numericPhrase(rule, rule.critical, t, lang)))
}

/** Eki cümlenin son noktasından önce ekler ("A." + "(b)" → "A (b)."); nokta yoksa sona boşlukla. */
function beforeFinalStop(text, suffix) {
  const s = String(text ?? '')
  return s.endsWith('.') ? `${s.slice(0, -1)} ${suffix}.` : `${s} ${suffix}`
}

/** Liste yer değiştirme (sınır dışında aynı dizi döner). */
export function moveRule(rules, index, delta) {
  const j = index + delta
  if (!Array.isArray(rules) || index < 0 || index >= rules.length || j < 0 || j >= rules.length) return rules
  const next = rules.slice()
  const [r] = next.splice(index, 1)
  next.splice(j, 0, r)
  return next
}
