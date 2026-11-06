/**
 * Anahtar kelime izlemesinin ÇOKLU KURAL modeli (kw sözleşmesi §6, paket C — 2026-11-05). Saf yardımcılar; React yok.
 *
 * <p><b>Biçim.</b> Kural nesneleri API ile AYNI (snake_case, `rules_json`): `{ id, kind: TEXT|REGEX, pattern, case_sensitive,
 * operator: GTE|LTE|EQ|GT|LT, count, critical: {operator, count}|null, scope: {type: CSS|JSON, value}|null, label|null }`.
 * `operator` + `count` = SAĞLIKLI koşul (bugünkü anlam) = uyarı eşiği; `critical` isteğe bağlı ikinci sağlıklı koşul. Formda
 * `count` yazılırken metin olabilir — biçim {@link toPayload}'da sayıya çevrilir, {@link validateRules} sunucuyla aynı
 * kurallarla (sınırlar, B10, iki eşik ailesi) alan anahtarlı hata kodları üretir (`rules[i].pattern` …).
 *
 * <p><b>Basit tek kural</b> (TEXT, tüm sayfa, kritik yok, etiket yok) eski alanlarla gönderilir (`rules: null` + `keyword`,
 * `operator`, `matchCount`, `caseSensitive`) — sunucu `rules_json`'ı NULL bırakır, eski pod ve eski istemci aynı izlemeyi görür
 * (sözleşme §6.6).
 *
 * <p><b>Seviye.</b> Kural seviyesi `ruleLevelFor` (kritik varsa kritik koşul ihlali → CRITICAL, değilse uyarı koşulu ihlali →
 * WARNING; kritik yoksa ihlal → MONITOR = izlemenin alarm seviyesi), birleşim `combinedLevel` (VE: biri ihlal → ihlal, en
 * YÜKSEK seviye; VEYA: hepsi ihlal → ihlal, en DÜŞÜK seviye) — sunucudaki `KeywordRuleEvaluator`'ın eşi.
 *
 * <p>Dikkat: dbquery'nin ANY/ALL'ı İHLAL anlamlıdır; burada AND/OR SAĞLANMA anlamlı (VE = hepsi sağlanmalı).
 */
import { isValidJsonPath } from '../../http/httpAdvancedModel.js'
import { MAX_COUNT } from '../../../utils/keywordFold.js'

/** Sunucu sınırları (`service/keyword/rules/*`; sözleşme §6.1). */
/** Sunucunun kural notları (`rule_results[].note`, canlı eşleşmede `rules[].note`) — bilinmeyen not ekrana ham düşmez. */
// PATTERN_INVALID (hata avı 2026-11-06): saklı düzenli ifade derlenemedi — kural ihlal sayılır, adet 0 bir ölçüm DEĞİL
export const RULE_NOTES = Object.freeze(['SCOPE_NOT_FOUND', 'SCOPE_INVALID', 'COUNT_CAPPED', 'SCAN_TRUNCATED', 'PATTERN_INVALID'])
/** Eşleşme hiç ARANAMADI (bölge yok / geçersiz, desen derlenemedi): sayı ve "eşleşme yok" gösterilmez, not söyler. */
export const NOT_SEARCHED_NOTES = Object.freeze(['SCOPE_NOT_FOUND', 'SCOPE_INVALID', 'PATTERN_INVALID'])
/** Sayı bir ALT SINIR olduğunda ("en az N") — tarama bütçesi / sayım tavanı. */
export const AT_LEAST_NOTES = Object.freeze(['SCAN_TRUNCATED', 'COUNT_CAPPED'])

export const RULE_LIMITS = Object.freeze({
  maxRules: 10,
  textMax: 255,
  regexMax: 200,
  scopeMax: 300,
  labelMax: 80,
  textCountMax: MAX_COUNT,
  regexCountMax: 10_000,
})

export const RULE_KINDS = Object.freeze(['TEXT', 'REGEX'])
export const SCOPE_TYPES = Object.freeze(['PAGE', 'CSS', 'JSON'])
export const OPERATORS = Object.freeze(['GTE', 'LTE', 'EQ', 'GT', 'LT'])
export const RULE_LOGICS = Object.freeze(['AND', 'OR'])
/** Koşul ön ayarları (arayüz): içermeli (≥ 1), içermemeli (≤ 0), serbest adet koşulu. */
export const RULE_MODES = Object.freeze(['contains', 'absent', 'count'])
/** Alarm seviyesi sırası (sunucuyla aynı): uyarı < yüksek < kritik. */
export const LEVEL_RANK = Object.freeze({ WARNING: 1, HIGH: 2, CRITICAL: 3 })

const ID_RE = /^[a-z0-9-]{1,20}$/
const NEWLINE = /[\r\n]/
const LEVELS = new Set(['WARNING', 'HIGH', 'CRITICAL'])

/** Yeni kural kimliği — `[a-z0-9-]{1,20}` (sunucu kuralı), listede tekil olması çağıranın `existing` kümesiyle sağlanır. */
export function newRuleId(existing = [], now = Date.now(), rand = Math.random) {
  const taken = new Set(existing)
  for (let attempt = 0; attempt < 8; attempt++) {
    const r = Math.floor(rand() * 36 ** 4).toString(36).padStart(4, '0')
    const id = `r-${(now + attempt).toString(36)}${r}`.slice(0, 20)
    if (!taken.has(id)) return id
  }
  // 8 denemede çakışma (yalnız sahte rastgele üreteçte) — sıra numarasıyla kesin tekil
  let n = taken.size + 1
  while (taken.has(`r${n}`)) n++
  return `r${n}`
}

/** Boş kural (içermeli: en az 1 kez). */
export function newRule(existingIds = []) {
  return { id: newRuleId(existingIds), kind: 'TEXT', pattern: '', case_sensitive: false, operator: 'GTE', count: 1, critical: null, scope: null, label: null }
}

const upper = (v) => String(v ?? '').trim().toUpperCase()

/** API / taslak nesnesini kanonik kural biçimine getirir (bilinmeyen değerler varsayılana düşer; metinler aynen). */
export function normalizeRule(raw, fallbackId = 'r1') {
  const r = raw && typeof raw === 'object' ? raw : {}
  const kind = RULE_KINDS.includes(upper(r.kind)) ? upper(r.kind) : 'TEXT'
  const op = OPERATORS.includes(upper(r.operator)) ? upper(r.operator) : 'GTE'
  const crit = r.critical && typeof r.critical === 'object'
    ? { operator: OPERATORS.includes(upper(r.critical.operator)) ? upper(r.critical.operator) : 'GTE', count: r.critical.count ?? '' }
    : null
  const st = r.scope && typeof r.scope === 'object' ? upper(r.scope.type) : ''
  const scope = st === 'CSS' || st === 'JSON' ? { type: st, value: String(r.scope.value ?? '') } : null
  const id = typeof r.id === 'string' && r.id ? r.id : fallbackId
  return {
    id, kind, pattern: String(r.pattern ?? ''), case_sensitive: r.case_sensitive === true || r.caseSensitive === true,
    operator: op, count: r.count ?? '', critical: crit, scope, label: r.label == null || r.label === '' ? null : String(r.label),
  }
}

/** Eski tek alanlardan (satır: snake_case; form: camelCase) tek kural — kimlik `r1` (sunucunun türettiğiyle aynı). */
export function legacyRule(src) {
  const s = src || {}
  return normalizeRule({
    id: 'r1', kind: 'TEXT', pattern: s.keyword ?? '',
    case_sensitive: s.case_sensitive ?? s.caseSensitive ?? false,
    operator: s.operator || 'GTE', count: s.match_count ?? s.matchCount ?? 1,
  })
}

/** İzleme satırının ETKİN kuralları: `rules` doluysa o (sunucu eski izlemede de tek kural türetir), yoksa eski alanlar. */
export function rulesFromRow(m) {
  const list = Array.isArray(m?.rules) ? m.rules : null
  if (list && list.length) return list.slice(0, RULE_LIMITS.maxRules).map((r, i) => normalizeRule(r, `r${i + 1}`))
  return [legacyRule(m)]
}

/** Formun kuralları: `form.rules` null ise (eski tek kural) camelCase alanlardan türetilir. */
export function rulesFromForm(form) {
  const list = Array.isArray(form?.rules) ? form.rules : null
  if (list && list.length) return list.map((r, i) => normalizeRule(r, `r${i + 1}`))
  return [legacyRule(form)]
}

/** Satırın birleşim mantığı (yoksa VE). */
export function logicOf(src) {
  const v = upper(src?.rule_logic ?? src?.ruleLogic)
  return v === 'OR' ? 'OR' : 'AND'
}

/** Basit tek kural mı (eski alanlarla tam ifade edilebilir)? */
export function isSimpleRule(r) {
  if (!r) return false
  const scopeType = r.scope?.type
  return r.kind === 'TEXT' && (!scopeType || scopeType === 'PAGE') && !r.critical && !(r.label && String(r.label).trim())
}

/** Tam sayı mı? Metin girdisi ("12", " 3 ") ya da sayı; ondalık / eksi / boş → NaN. */
export function parseCount(v) {
  if (typeof v === 'number') return Number.isInteger(v) ? v : NaN
  const s = String(v ?? '').trim()
  return /^\d{1,9}$/.test(s) ? Number(s) : NaN
}

/** Kuralın sayım tavanı (TEXT 1 000 000 · REGEX 10 000). */
export const countCap = (r) => (r?.kind === 'REGEX' ? RULE_LIMITS.regexCountMax : RULE_LIMITS.textCountMax)

/** Gönderilecek temiz kural (sayılar sayı; kapsam PAGE → null; boş etiket → null). */
export function cleanRule(r) {
  const n = normalizeRule(r, r?.id)
  return {
    id: n.id,
    kind: n.kind,
    pattern: n.pattern,
    case_sensitive: n.case_sensitive,
    operator: n.operator,
    count: parseCount(n.count),
    critical: n.critical ? { operator: n.critical.operator, count: parseCount(n.critical.count) } : null,
    scope: n.scope ? { type: n.scope.type, value: n.scope.value.trim() } : null,
    label: n.label && n.label.trim() ? n.label.trim() : null,
  }
}

/**
 * İstek gövdesi parçası (oluştur / güncelle / test — anahtarlar camelCase, kural nesneleri snake_case):
 * basit tek kural → `{ rules: null, keyword, operator, matchCount, caseSensitive }`; diğer → `{ rules, ruleLogic }`
 * (eski alanları sunucu kural #1'den yazar).
 */
export function toPayload(rules, logic = 'AND') {
  const clean = (Array.isArray(rules) ? rules : []).map(cleanRule)
  if (clean.length === 1 && isSimpleRule(clean[0])) {
    const r = clean[0]
    return { rules: null, keyword: r.pattern, operator: r.operator, matchCount: r.count, caseSensitive: r.case_sensitive }
  }
  return { rules: clean, ruleLogic: logic === 'OR' ? 'OR' : 'AND' }
}

/**
 * Forma yazılacak alanlar (fe1 `keywordFormModel` biçimi): `rules` (null = eski tek kural), `ruleLogic` ve eski alan AYNASI
 * (`keyword` = kural #1'in deseni …) — formun "anahtar kelime boş mu" denetimleri ve mükerrer kuralı (takım + URL + kural
 * #1) böylece değişmeden çalışır.
 */
export function formPatchFromRules(rules, logic = 'AND') {
  const list = Array.isArray(rules) ? rules : []
  const first = list[0] ? normalizeRule(list[0]) : legacyRule({})
  const mirror = { keyword: first.pattern, operator: first.operator, matchCount: first.count, caseSensitive: first.case_sensitive }
  if (list.length === 1 && isSimpleRule(first)) return { rules: null, ruleLogic: 'AND', ...mirror }
  return { rules: list.map((r) => normalizeRule(r, r?.id)), ruleLogic: logic === 'OR' ? 'OR' : 'AND', ...mirror }
}

/** Kararlı imza — "kurallar değişti mi?" karşılaştırması (aynı içerik → aynı dizge). */
export function rulesSignature(rules, logic = 'AND') {
  return JSON.stringify(toPayload(rules, logic))
}

/** Sıra değiştir (yukarı / aşağı düğmeleri); sınır dışı → aynı dizi. */
export function moveRule(rules, index, delta) {
  const j = index + delta
  if (!Array.isArray(rules) || index < 0 || index >= rules.length || j < 0 || j >= rules.length) return rules
  const next = rules.slice()
  const [r] = next.splice(index, 1)
  next.splice(j, 0, r)
  return next
}

// ── Koşul aileleri ve sınırlar ───────────────────────────────────────────────────────────────────────────────────

/** Yön ailesi: `upper` (LTE / LT — az olmalı), `lower` (GTE / GT — çok olmalı), `eq`. */
export function family(op) {
  if (op === 'LTE' || op === 'LT') return 'upper'
  if (op === 'GTE' || op === 'GT') return 'lower'
  return 'eq'
}

/** Etkin sınır: LTE n → n · LT n → n-1 · GTE n → n · GT n → n+1 (EQ → n). */
export function boundary(op, n) {
  if (op === 'LT') return n - 1
  if (op === 'GT') return n + 1
  return n
}

/** Sağlıklı mı? (sayım koşulu sağlanıyor mu). */
export function holds(op, n, count) {
  switch (op) {
    case 'LTE': return count <= n
    case 'LT': return count < n
    case 'EQ': return count === n
    case 'GT': return count > n
    default: return count >= n
  }
}

/** Koşul ön ayarı (arayüz seçicisinin başlangıç değeri): içermeli (GTE 1 / GT 0), içermemeli (LTE 0 / EQ 0 / LT 1), adet. */
export function modeOf(r) {
  const n = parseCount(r?.count)
  const op = r?.operator
  if ((op === 'GTE' && n === 1) || (op === 'GT' && n === 0)) return 'contains'
  if (((op === 'LTE' || op === 'EQ') && n === 0) || (op === 'LT' && n === 1)) return 'absent'
  return 'count'
}

/** Ön ayarı uygula: içermeli → GTE 1 (kritik yok — anlamsız), içermemeli → LTE 0, adet → koşul korunur. */
export function applyMode(r, mode) {
  if (mode === 'contains') return { ...r, operator: 'GTE', count: 1, critical: null }
  if (mode === 'absent') return { ...r, operator: 'LTE', count: 0, critical: r.critical && family(r.critical.operator) === 'upper' ? r.critical : null }
  return r
}

/**
 * Kritik eşik anlamlı olabilir mi? EQ'da asla; alt sınırda (GTE / GT) kritik sınır 1 ≤ k < uyarı sınırı olmalı → uyarı sınırı
 * en az 2; üst sınırda kritik sınır uyarı sınırının ötesinde ve tavanın altında olmalı (uyarı sınırı en çok tavan − 2).
 */
export function criticalPossible(r) {
  const n = parseCount(r?.count)
  if (!Number.isFinite(n)) return false
  const fam = family(r?.operator)
  if (fam === 'eq') return false
  const b = boundary(r.operator, n)
  // Üst sınırda kritik sınır tavanın ALTINDA kalmalı ("en fazla <tavan> kez" her zaman sağlanır — B10): uyarı sınırı ≤ tavan − 2
  return fam === 'lower' ? b >= 2 : b < countCap(r) - 1
}

/**
 * Kritik açılınca önerilen başlangıç (aynı işleç, sınır kuralına uyan): alt sınırda yarısı (GTE en az 1, GT en az 0 —
 * `criticalPossible` uyarı sınırının ≥ 2 olmasını garanti eder, yarısı her zaman ondan küçüktür), üst sınırda 4 katı
 * (0 ise 5), tavanla sınırlı (LTE en çok tavan − 1 — "en fazla <tavan> kez" anlamsız).
 */
export function defaultCritical(r) {
  const n = parseCount(r?.count)
  const op = r?.operator
  const base = Number.isFinite(n) ? n : 0
  if (op === 'GTE') return { operator: 'GTE', count: Math.max(1, Math.floor(base / 2)) }
  if (op === 'GT') return { operator: 'GT', count: Math.max(0, Math.floor(base / 2)) }
  // LTE tavanı anlamsızdır (B10): en çok tavan − 1; LT tavanı geçerli (sınır tavan − 1)
  const lt = op === 'LT'
  return { operator: lt ? 'LT' : 'LTE', count: Math.min(lt ? countCap(r) : countCap(r) - 1, base === 0 ? 5 : base * 4) }
}

// ── Doğrulama (sunucu aynası) ────────────────────────────────────────────────────────────────────────────────────

/**
 * Düzenli ifadede RE2'nin KESİN desteklemediği yapılar (doğrusal tarama; kaçış ve karakter sınıfı izlenir): geri başvuru
 * (`\1` — sekizlik kaçış `\12` değil — ve `\k<ad>`), ileri / geri bakış, atomik grup. `null` = sorun görülmedi (sözdizimini
 * sunucu derler; hata kayıtta alanın altında konumuyla döner).
 */
export function unsupportedRegex(pattern) {
  const p = String(pattern ?? '')
  let inClass = false
  for (let i = 0; i < p.length; i++) {
    const c = p[i]
    if (c === '\\') {
      const nx = p[i + 1]
      if (nx === 'k' && p[i + 2] === '<') return 'regexBackref'
      // RE2 / Go: tek basamak `\1` (ardında sekizlik basamak yoksa) karakter sınıfının içinde de geçersiz kaçıştır
      if (nx >= '1' && nx <= '9' && !(p[i + 2] >= '0' && p[i + 2] <= '7')) return 'regexBackref'
      i++
      continue
    }
    if (inClass) {
      if (c === ']') inClass = false
      continue
    }
    if (c === '[') {
      inClass = true
      if (p[i + 1] === '^') i++
      if (p[i + 1] === ']') i++   // sınıfın ilk karakteri olarak ']' düz harftir
      continue
    }
    if (c === '(' && p[i + 1] === '?') {
      const a = p[i + 2]
      if (a === '=' || a === '!' || a === '>') return 'regexLookaround'
      if (a === '<' && (p[i + 3] === '=' || p[i + 3] === '!')) return 'regexLookaround'
    }
  }
  return null
}

/**
 * Düzenli ifadenin YAPI hatası (RE2 ve JavaScript'te aynı biçimde geçersiz olanlar) — doğrusal tek geçiş: sonda yalnız
 * ters bölü, kapanmayan karakter sınıfı, dengesiz parantez, başta / `(` / `|` ardında tekrar işleci (`*`, `+`, `?`).
 * Karmaşıklık reddi (RE2J program / tahmin sınırı) istemcide YOK — sunucudan `rules[N].pattern` alan hatası olarak gelir.
 * Döner: `'regexSyntax'` ya da null.
 */
export function regexSyntaxError(pattern) {
  const p = String(pattern ?? '')
  let inClass = false
  let depth = 0
  let prev = 'start'   // 'start' | 'open' | 'alt' | 'atom'
  for (let i = 0; i < p.length; i++) {
    const c = p[i]
    if (c === '\\') {
      if (i + 1 >= p.length) return 'regexSyntax'
      // RE2 `\Q…\E` düz metin bloğu: içindeki parantez / işleç yapı değildir (kapanmazsa sona kadar düz metin)
      if (!inClass && p[i + 1] === 'Q') {
        const end = p.indexOf('\\E', i + 2)
        if (end < 0) return null
        i = end + 1
        prev = 'atom'
        continue
      }
      i++
      if (!inClass) prev = 'atom'
      continue
    }
    if (inClass) {
      if (c === ']') { inClass = false; prev = 'atom' }
      continue
    }
    if (c === '[') {
      inClass = true
      if (p[i + 1] === '^') i++
      if (p[i + 1] === ']') i++
      continue
    }
    if (c === '(') {
      // Grup sözdizimi `(?…` — soru işareti tekrar işleci değildir: `(?:`, `(?i)`, `(?i:`, `(?P<ad>`, `(?<ad>`
      if (p[i + 1] === '?') {
        let j = i + 2
        if ((p[j] === 'P' && p[j + 1] === '<') || p[j] === '<') {
          const end = p.indexOf('>', j)
          if (end < 0) return 'regexSyntax'
          i = end
          depth++
          prev = 'open'
          continue
        }
        while (j < p.length && /[a-zA-Z-]/.test(p[j])) j++
        if (p[j] === ')') { i = j; continue }   // yalnız bayrak grubu — tekrarlanacak bir şey değildir
        if (p[j] === ':') { i = j; depth++; prev = 'open'; continue }
        return 'regexSyntax'
      }
      depth++
      prev = 'open'
      continue
    }
    if (c === ')') {
      if (depth === 0) return 'regexSyntax'
      depth--
      prev = 'atom'
      continue
    }
    if (c === '|') { prev = 'alt'; continue }
    if (c === '*' || c === '+' || c === '?') {
      if (prev !== 'atom') return 'regexSyntax'
      continue
    }
    prev = 'atom'
  }
  if (inClass || depth !== 0) return 'regexSyntax'
  return null
}

/**
 * CSS seçicinin sunucuda reddedilen kalıpları (jsoup düzenli ifade seçicileri + pahalı `:has`) — tırnak içi atlanır,
 * doğrusal: `:matches…` sözde sınıfları (`:matches`, `:matchesOwn`, `:matchesWholeText` …) ve `[attr~=…]` → `'cssRegex'`;
 * `:has(` birden çok kez → `'cssHasMany'`. Yoksa null.
 */
export function cssSelectorProblem(selector) {
  const s = String(selector ?? '')
  const lower = s.toLowerCase()
  let quote = null
  let has = 0
  for (let i = 0; i < s.length; i++) {
    const c = s[i]
    if (quote) {
      if (c === '\\') i++
      else if (c === quote) quote = null
      continue
    }
    if (c === '"' || c === "'") { quote = c; continue }
    if (c === '\\') { i++; continue }
    if (c === '~' && s[i + 1] === '=') return 'cssRegex'
    if (c === ':') {
      if (lower.startsWith(':matches', i)) return 'cssRegex'
      if (lower.startsWith(':has(', i)) has++
    }
  }
  return has > 1 ? 'cssHasMany' : null
}

/**
 * Desen boş metinle eşleşiyor mu (sunucu reddeder: her konumu sayardı)? JavaScript'te derlenebilen desenler denenir —
 * baştaki satır içi bayrak grubu (`(?i)` …) JS bayrağına çevrilir; derlenemeyen (RE2'ye özgü sözdizimi) desen için
 * `null` (bilinmiyor — karar sunucunun). Eşleştirme yalnız BOŞ dizgeyle yapılır (girdi yok → süre sorunu yok).
 */
export function regexMatchesEmpty(pattern) {
  let p = String(pattern ?? '')
  let flags = ''
  const lead = /^\(\?([imsU]+)\)/.exec(p)
  if (lead) {
    if (lead[1].includes('U')) return null
    flags = [...new Set(lead[1].split(''))].join('')
    p = p.slice(lead[0].length)
  }
  for (const extra of ['', 'u']) {
    try {
      return new RegExp(p, flags + extra).test('')
    } catch { /* bir sonraki deneme; ikisi de olmazsa bilinmiyor */ }
  }
  return null
}

/** Tek kuralın alan hataları: `{ 'rules[i].<alan>': { code, params } }`. */
export function validateRule(rule, index) {
  const out = {}
  const r = normalizeRule(rule, rule?.id)
  const key = (alt) => `rules[${index}].${alt}`
  const put = (alt, code, params = []) => { if (!out[key(alt)]) out[key(alt)] = { code, params } }

  if (!ID_RE.test(r.id)) put('id', 'idInvalid')

  const max = r.kind === 'REGEX' ? RULE_LIMITS.regexMax : RULE_LIMITS.textMax
  if (!r.pattern.trim()) put('pattern', 'patternRequired')
  else if (NEWLINE.test(r.pattern)) put('pattern', 'patternNewline')
  else if (r.pattern.length > max) put('pattern', 'patternTooLong', [max, r.pattern.length])
  else if (r.kind === 'REGEX') {
    const bad = unsupportedRegex(r.pattern) || regexSyntaxError(r.pattern)
    if (bad) put('pattern', bad)
    else if (regexMatchesEmpty(r.pattern) === true) put('pattern', 'regexEmptyMatch')
  }

  const cap = countCap(r)
  const n = parseCount(r.count)
  if (!Number.isFinite(n)) put('count', 'countRequired')
  else if (n > cap) put('count', 'countRange', [cap])
  else if (r.operator === 'GTE' && n === 0) put('count', 'alwaysHealthy')
  else if (r.operator === 'LT' && n === 0) put('count', 'neverHealthy')
  // Sayım tavanında durur (B10 — sunucu `KeywordRules.meaningless` ile aynı, hata avı 2026-11-06): "en fazla <tavan> kez" her
  // zaman sağlanır, "<tavan> kezden fazla" hiç sağlanamaz
  else if (r.operator === 'LTE' && n >= cap) put('count', 'alwaysHealthyCap', [cap])
  else if (r.operator === 'GT' && n >= cap) put('count', 'neverHealthyCap', [cap])

  if (r.critical) {
    const k = parseCount(r.critical.count)
    const fam = family(r.operator)
    const cop = r.critical.operator
    if (r.operator === 'EQ') put('critical', 'criticalEq')
    else if (!Number.isFinite(k) || k > cap) put('critical', 'criticalCount', [cap])
    // Kritik koşulun kendisi anlamsız (B10 — sunucu kritik eşiğe de `meaningless` uygular, ikili kontrolden ÖNCE)
    else if (cop === 'GTE' && k === 0) put('critical', 'criticalNeverFires')
    else if (cop === 'LT' && k === 0) put('critical', 'criticalLtZero')
    else if (cop === 'LTE' && k >= cap) put('critical', 'criticalAlwaysCap', [cap])
    else if (cop === 'GT' && k >= cap) put('critical', 'criticalNeverCap', [cap])
    else if (family(cop) !== fam) put('critical', 'criticalFamily')
    else if (Number.isFinite(n)) {
      const wb = boundary(r.operator, n)
      const cb = boundary(r.critical.operator, k)
      if (fam === 'upper' && !(cb > wb)) put('critical', 'criticalOrderUpper')
      else if (fam === 'lower' && !(cb < wb)) put('critical', 'criticalOrderLower')
      else if (fam === 'lower' && cb <= 0) put('critical', 'criticalNeverFires')
    }
  }

  if (r.scope) {
    const v = r.scope.value
    const label = r.scope.type
    if (!v.trim()) put('scope', 'scopeRequired', [label])
    else if (NEWLINE.test(v)) put('scope', 'patternNewline')
    else if (v.trim().length > RULE_LIMITS.scopeMax) put('scope', 'scopeTooLong', [label, RULE_LIMITS.scopeMax])
    else if (r.scope.type === 'JSON' && !isValidJsonPath(v)) put('scope', 'scopeJsonSyntax')
    else if (r.scope.type === 'CSS' && cssSelectorProblem(v)) put('scope', cssSelectorProblem(v) === 'cssRegex' ? 'scopeCssRegex' : 'scopeCssHasMany')
  }

  if (r.label && r.label.trim().length > RULE_LIMITS.labelMax) put('label', 'labelTooLong', [RULE_LIMITS.labelMax])
  return out
}

/**
 * Sunucu alan hataları: eski tek kural anahtarları (`keyword`, `matchCount`, `operator`, `caseSensitive`) form artık kural
 * kartıyla çizildiği için kural #1'in alanına taşınır — `keyword` → `rules[0].pattern`, sayı / işleç → `rules[0].count`
 * (karttaki karşılık zaten varsa dokunulmaz). Diğer anahtarlar aynen kalır. Saf.
 */
export function legacyRuleErrors(errs) {
  const src = errs && typeof errs === 'object' ? errs : {}
  const out = {}
  const MAP = { keyword: 'rules[0].pattern', caseSensitive: 'rules[0].pattern', matchCount: 'rules[0].count', operator: 'rules[0].count' }
  for (const [k, v] of Object.entries(src)) {
    if (!v) continue
    const to = MAP[k]
    if (to) { if (!out[to] && !src[to]) out[to] = v } else out[k] = v
  }
  return out
}

/** Bütün liste: liste düzeyi (`rules`: boş / çok) + kural alanları + mükerrer kimlik. */
export function validateRules(rules) {
  const list = Array.isArray(rules) ? rules : []
  const out = {}
  if (list.length === 0) out.rules = { code: 'empty', params: [] }
  else if (list.length > RULE_LIMITS.maxRules) out.rules = { code: 'tooMany', params: [RULE_LIMITS.maxRules] }
  const seen = new Set()
  list.forEach((r, i) => {
    Object.assign(out, validateRule(r, i))
    const id = r?.id
    if (id && seen.has(id) && !out[`rules[${i}].id`]) out[`rules[${i}].id`] = { code: 'idInvalid', params: [] }
    seen.add(id)
  })
  return out
}

/** Hata kodu → metin (`keyword.rule.v.<kod>`); kapsam türü parametresi kendi etiketine çevrilir. */
export function ruleProblemText(problem, t) {
  if (!problem) return null
  if (typeof problem === 'string') return problem   // sunucu iletisi (istek dilinde)
  const params = (problem.params || []).map((p) => (p === 'CSS' || p === 'JSON' ? t(`keyword.rule.scopeValue.${p}`) : p))
  return t(`keyword.rule.v.${problem.code}`, ...params)
}

// ── Seviye ───────────────────────────────────────────────────────────────────────────────────────────────────────

/** Tek kuralın seviyesi (sayım verildiğinde): NONE | WARNING | CRITICAL | MONITOR. */
export function ruleLevelFor(rule, count) {
  const r = cleanRule(rule)
  if (r.critical && Number.isFinite(r.critical.count)) {
    if (!holds(r.critical.operator, r.critical.count, count)) return 'CRITICAL'
    return holds(r.operator, r.count, count) ? 'NONE' : 'WARNING'
  }
  return holds(r.operator, r.count, count) ? 'NONE' : 'MONITOR'
}

/** MONITOR → izlemenin etkin seviyesi (bilinmeyen değer → WARNING). */
export function concreteLevel(level, monitorLevel) {
  if (level === 'MONITOR') return LEVELS.has(monitorLevel) ? monitorLevel : 'WARNING'
  return level
}

/**
 * Kural seviyelerinin birleşimi (sunucu kuralı): VE — biri ihlal → ihlal, seviye en YÜKSEK; VEYA — hepsi ihlal → ihlal,
 * seviye en DÜŞÜK. Döner: 'NONE' | 'WARNING' | 'HIGH' | 'CRITICAL'. Boş liste → NONE.
 */
export function combinedLevel(levels, logic = 'AND', monitorLevel = 'WARNING') {
  const list = (Array.isArray(levels) ? levels : []).map((l) => concreteLevel(l, monitorLevel))
  if (!list.length) return 'NONE'
  const breached = list.filter((l) => l !== 'NONE' && LEVEL_RANK[l])
  if (logic === 'OR') {
    if (breached.length !== list.length) return 'NONE'
    return breached.reduce((a, b) => (LEVEL_RANK[b] < LEVEL_RANK[a] ? b : a))
  }
  if (!breached.length) return 'NONE'
  return breached.reduce((a, b) => (LEVEL_RANK[b] > LEVEL_RANK[a] ? b : a))
}

// ── İnsan dilinde açıklama (B11 — TÜMÜ sözlükten) ────────────────────────────────────────────────────────────────

/** Sağlıklı koşulun alarm tarafı: GTE n → LT n · GT n → LTE n · LTE n → GT n · LT n → GTE n · EQ n → NE n. */
export function alarmOf(op, n) {
  switch (op) {
    case 'GT': return { op: 'LTE', n }
    case 'LTE': return { op: 'GT', n }
    case 'LT': return { op: 'GTE', n }
    case 'EQ': return { op: 'NE', n }
    default: return { op: 'LT', n }
  }
}

/** Alarm koşulundan sağlıklı koşula (tersi; NE → EQ). */
export function healthyOf(alarm) {
  switch (alarm?.op) {
    case 'LTE': return { op: 'GT', n: alarm.n }
    case 'GT': return { op: 'LTE', n: alarm.n }
    case 'GTE': return { op: 'LT', n: alarm.n }
    case 'NE': return { op: 'EQ', n: alarm.n }
    default: return { op: 'GTE', n: alarm?.n }
  }
}

/** Kuralın hedefi: TEXT « metin », REGEX "/desen/ deseni" (sözlükten). */
export function ruleTarget(r, t) {
  const p = String(r?.pattern ?? '')
  if (r?.kind === 'REGEX') return t('keyword.rule.desc.regex', p)
  return p.trim() ? `« ${p} »` : t('keyword.theKeyword')
}

/** Nerede: sayfada · « seçici » bölgesinde · yol alanında. */
export function ruleWhere(r, t) {
  const type = r?.scope?.type
  if (type === 'CSS' || type === 'JSON') return t(`keyword.rule.where.${type}`, String(r.scope.value ?? '').trim())
  return t('keyword.rule.where.PAGE')
}

/** Alarm cümlesinin koşul kısmı ("« X » sayfada hiç bulunmazsa"). Geçersiz sayıda null. */
export function alarmPhrase(r, op, n, t) {
  if (!Number.isFinite(n)) return null
  const target = ruleTarget(r, t)
  const where = ruleWhere(r, t)
  const a = alarmOf(op, n)
  if ((a.op === 'LT' && a.n === 1) || (a.op === 'LTE' && a.n === 0)) return t('keyword.rule.alarm.ABSENT', target, where)
  if ((a.op === 'GTE' && a.n === 1) || (a.op === 'GT' && a.n === 0) || (a.op === 'NE' && a.n === 0)) return t('keyword.rule.alarm.PRESENT', target, where)
  return t(`keyword.rule.alarm.${a.op}`, target, where, a.n)
}

/**
 * Kartın canlı açıklaması: `{ healthy, warning, critical }` cümleleri. Kritik yoksa `warning` izlemenin seviyesiyle tek
 * alarm cümlesi ("Alarm (Uyarı): …"). Geçersiz sayıda ilgili cümle null (yazarken eksik metin görünmesin).
 */
export function describeRule(rule, t, monitorLevel = 'WARNING') {
  const r = normalizeRule(rule, rule?.id)
  const n = parseCount(r.count)
  const target = ruleTarget(r, t)
  const where = ruleWhere(r, t)
  const mode = modeOf(r)
  let healthy = null
  if (mode === 'contains') healthy = t('keyword.rule.desc.healthyContains', target, where)
  else if (mode === 'absent') healthy = t('keyword.rule.desc.healthyAbsent', target, where)
  else if (Number.isFinite(n)) healthy = t('keyword.rule.desc.healthy', target, where, t(`keyword.expect.${r.operator}`, n))
  const warnPhrase = alarmPhrase(r, r.operator, n, t)
  const crit = r.critical ? alarmPhrase(r, r.critical.operator, parseCount(r.critical.count), t) : null
  const lvl = LEVELS.has(monitorLevel) ? monitorLevel : 'WARNING'
  return {
    healthy,
    warning: warnPhrase ? (r.critical ? t('keyword.rule.desc.warning', warnPhrase) : t('keyword.rule.desc.alarm', t(`keyword.rule.level.${lvl}`), warnPhrase)) : null,
    critical: crit ? t('keyword.rule.desc.critical', crit) : null,
  }
}

/** Liste özeti ("3 kural · VE") — tek kuralda null. */
export function ruleSetBadge(rules, logic, t) {
  const n = Array.isArray(rules) ? rules.length : 0
  if (n < 2) return null
  return t('keyword.rule.badge', n, t(`keyword.rule.logicShort.${logic === 'OR' ? 'OR' : 'AND'}`))
}
