/**
 * Anahtar kelime izlemesi → SENTETİK (k6) izleme taslağı üreteci (kw paket F1, 2026-11-05) — saf, İSTEMCİ tarafında.
 *
 * <p><b>Neden istemcide (sözleşme §9.1):</b> sır değeri HİÇBİR koşulda taşınmaz — sunucuda bir "sır kopyala" yolu, paylaşımla
 * (grant) düzenleyen birinin başka bir izlemenin sırrını k6 betiğiyle sızdırmasına kapı açardı. Gereken her alan liste
 * satırında zaten var; üretilen betik sentetik kaydının KENDİ doğrulamasından (dosya okuma BLOCK, güvenlik kuralları, env adı
 * politikası) yine geçer; yeni uç ya da yetki yüzeyi yoktur.
 *
 * <p><b>Betik kuralları:</b> yalnız yerleşik modüller (`k6/http`, `k6`, gerekirse `k6/encoding`, `k6/html`); `open()`,
 * `handleSummary`, `k6/x/…`, yerel / uzak import ASLA. Kullanıcı verisi (desen, gövde, seçici, ad) JS dizge sabitine
 * kaçışlı yazılır: tırnaklar, ters bölü, parantezler, denetim karakterleri ve BAŞTAKİ "/" `\uXXXX` olur — sentetik kaydın
 * kaynak taraması (sistem yolu `"/etc…"`, yerel import, sonsuz döngü kalıpları) içerik dizgesini koda benzetip yanlışlıkla
 * reddetmesin (betik dosya okumaz; bu kaçış anlamı değiştirmez). Gizli env değerleri BOŞ yazılır.
 *
 * <p><b>Taşınan / taşınmayan</b> (notlar `keyword.k6.note.<KOD>`): istek (yöntem, gövde, içerik türü, Basic auth — kullanıcı
 * adı düz env, parola GİZLİ ve boş —, User-Agent kipi, özel başlıklar yalnız ADLARI görünüyorsa gizli + boş env), yönlendirme
 * takibi, zaman aşımı, kabul edilen durum kodları, son URL, kurallar (TEXT → Türkçe katlamalı sayım, REGEX → yalnız
 * taşınabilir altküme, CSS → `parseHTML`, JSON → küçük yol yürütücü; VE → kural başına `check`, VEYA → tek birleşik
 * `check`), boyut (karakterle — yaklaşık). İki seviyeli eşikten yalnız uyarı koşulu, içerik değişimi hiç taşınmaz.
 */
import { rulesFromRow, logicOf, describeRule, parseCount } from '../rules/ruleModel.js'

/** Sentetik izlemenin en kısa aralığı (zamanlama düzenleyicisi `minInterval`). */
export const K6_INTERVAL_MIN = 60
/** Varsayılan anahtar kelime UA'sı (sunucu `KeywordRequestRules` DEFAULT). */
export const DEFAULT_USER_AGENT = 'SiteMonitor-KeywordMonitor/1.0'
/** "Tarayıcı benzeri" UA — sunucunun `KeywordRequestRules.BROWSER_USER_AGENT` sabitiyle AYNI olmalı (test kaynaktan denetler). */
export const BROWSER_USER_AGENT = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36'
/** REGEX tarama sınırı ve sayım tavanı (sunucu `SafeRegex`). */
export const REGEX_SCAN_CAP = 512 * 1024
export const REGEX_COUNT_CAP = 10_000
export const TEXT_COUNT_CAP = 1_000_000
/** Not kodları — her biri `keyword.k6.note.<KOD>` (TR + EN). */
export const K6_NOTE_CODES = Object.freeze([
  'HEADERS_NOT_CARRIED', 'REGEX_NOT_PORTABLE', 'SIZE_APPROXIMATE', 'LEVELS_NOT_CARRIED', 'CHANGE_NOT_CARRIED', 'SCOPE_APPROXIMATE',
  'INTERVAL_RAISED',
])

const ENV_NAME = /^[A-Za-z_][A-Za-z0-9_]{0,63}$/
const RESERVED_PREFIXES = ['K6_', 'SSL_', 'LD_', 'DYLD_', 'JAVA_', 'NODE_']
const RESERVED_EXACT = new Set(['PATH', 'HOME', 'TMPDIR'])

/** Sentetik env adı politikası (sunucu `ScriptedEnvPolicy`): biçim + sistem adı değil. */
export function validEnvName(name) {
  if (!ENV_NAME.test(String(name ?? ''))) return false
  const u = String(name).toUpperCase()
  if (RESERVED_EXACT.has(u) || u.includes('PROXY') || /^GO[A-Z0-9]*$/.test(u)) return false
  return !RESERVED_PREFIXES.some((p) => u.startsWith(p))
}

const hex4 = (c) => `\\u${c.charCodeAt(0).toString(16).toUpperCase().padStart(4, '0')}`

/**
 * Kullanıcı verisini çift tırnaklı JS dizge sabitine çevirir. Kaçışlanan: ters bölü, üç tırnak türü, parantezler, denetim
 * karakterleri, U+2028/2029 ve İLK karakter "/" ise o — hepsi `\uXXXX` (ters bölü `\\`). Diğer her şey (Türkçe harfler, URL)
 * olduğu gibi okunur.
 */
export function jsString(value) {
  const s = String(value ?? '')
  let out = '"'
  for (let i = 0; i < s.length; i++) {
    const c = s[i]
    const code = c.charCodeAt(0)
    if (c === '\\') out += '\\\\'
    else if (c === '"' || c === '\'' || c === '`' || c === '(' || c === ')') out += hex4(c)
    else if (code < 0x20 || code === 0x7f || code === 0x2028 || code === 0x2029) out += hex4(c)
    else if (c === '/' && i === 0) out += hex4(c)
    else out += c
  }
  return `${out}"`
}

/** Yorum satırına güvenli metin (satır sonu / ayraç yok, 200 karakter). */
function commentText(value) {
  // eslint-disable-next-line no-control-regex
  return String(value ?? '').replace(/[\u0000-\u001f\u007f\u2028\u2029]/g, ' ').slice(0, 200)
}

/** "2xx, 301, 400-404" → [[200,299],[301,301],[400,404]] (sunucu `matchesStatus` dilbilgisi); geçersiz öğe atlanır. */
export function statusRanges(pattern) {
  const out = []
  for (const raw of String(pattern ?? '').split(',').slice(0, 20)) {
    const p = raw.trim().toLowerCase()
    let m
    if ((m = /^([1-5])xx$/.exec(p))) out.push([Number(m[1]) * 100, Number(m[1]) * 100 + 99])
    else if ((m = /^(\d{3})$/.exec(p))) { const n = Number(m[1]); if (n >= 100 && n <= 599) out.push([n, n]) }
    else if ((m = /^(\d{3})\s*-\s*(\d{3})$/.exec(p))) {
      const a = Number(m[1]); const b = Number(m[2])
      if (a >= 100 && b <= 599 && a <= b) out.push([a, b])
    }
  }
  return out
}

/**
 * JSON yolu → bölümler (`$.items[0]['a b']` → ['items', 0, 'a b']); `isValidJsonPath` ile aynı dilbilgisi, geçersizde null.
 */
export function jsonPathSegments(path) {
  const s = String(path ?? '').trim()
  if (!s) return null
  const out = []
  let i = s[0] === '$' ? 1 : 0
  const nameChar = (ch) => ch != null && !/[.[\]'"$\s]/.test(ch)
  let first = true
  while (i < s.length) {
    const c = s[i]
    if (c === '.') {
      if (first && s[0] !== '$') return null
      const start = ++i
      while (i < s.length && nameChar(s[i])) i++
      if (i === start) return null
      out.push(s.slice(start, i))
    } else if (c === '[') {
      i++
      const q = s[i]
      if (q === '\'' || q === '"') {
        const start = ++i
        while (i < s.length && s[i] !== q) i++
        if (i >= s.length || i === start || s[i + 1] !== ']') return null
        out.push(s.slice(start, i))
        i += 2
      } else {
        const start = i
        while (i < s.length && s[i] >= '0' && s[i] <= '9') i++
        if (i === start || i - start > 9 || s[i] !== ']') return null
        out.push(Number(s.slice(start, i)))
        i++
      }
    } else if (first && s[0] !== '$' && nameChar(c)) {
      const start = i
      while (i < s.length && nameChar(s[i])) i++
      out.push(s.slice(start, i))
    } else {
      return null
    }
    first = false
  }
  return out
}

/**
 * RE2 deseni JavaScript'e taşınabilir mi? Baştaki `(?i)`/`(?s)`/`(?m)` (ve birleşimleri) JS bayrağına çevrilir; şunlar
 * taşınmaz → null: `\p{…}`/`\P{…}`, `\Q…\E`, `(?P<…>)`, ortadaki satır içi bayrak grubu, `\A` `\z` `\C`, `[[:sınıf:]]`,
 * `\x{…}`, `(?U)`. Duyarsız eşleşmede desenin düz İ/I/ı/i harfleri "i"ye katlanır (sunucu `SafeRegex.foldPattern`). Dönüş:
 * `{ source, flags }`. Derlenemeyen sonuç da null.
 */
export function portableRegex(pattern, caseSensitive = false) {
  let p = String(pattern ?? '')
  let flags = ''
  const lead = /^\(\?([a-zA-Z]+)\)/.exec(p)
  if (lead) {
    if (!/^[ims]+$/.test(lead[1])) return null
    flags = [...new Set(lead[1].split(''))].join('')
    p = p.slice(lead[0].length)
  }
  let inClass = false
  let out = ''
  for (let i = 0; i < p.length; i++) {
    const c = p[i]
    if (c === '\\') {
      const nx = p[i + 1]
      if (nx === undefined) return null
      if ('pPQACz'.includes(nx) || (nx === 'x' && p[i + 2] === '{')) return null
      out += c + nx
      i++
      continue
    }
    if (inClass) {
      if (c === '[' && p[i + 1] === ':') return null
      if (c === ']') inClass = false
      out += !caseSensitive && 'İIıi'.includes(c) ? 'i' : c
      continue
    }
    if (c === '[') {
      if (p[i + 1] === '[' && p[i + 2] === ':') return null
      inClass = true
      out += c
      if (p[i + 1] === '^') { out += '^'; i++ }
      if (p[i + 1] === ']') { out += ']'; i++ }
      continue
    }
    if (c === '(' && p[i + 1] === '?') {
      const a = p[i + 2]
      if (a !== ':' && a !== '<' && a !== '=' && a !== '!') return null   // satır içi bayrak / (?P<…>) taşınmaz
      if (a === '<' && (p[i + 3] === '=' || p[i + 3] === '!')) return null
    }
    out += !caseSensitive && 'İIıi'.includes(c) ? 'i' : c
  }
  const f = [...new Set((flags + (caseSensitive ? '' : 'i')).split(''))].join('') + 'g'
  try { new RegExp(out, f) } catch { return null }
  return { source: out, flags: f }
}

/** Sağlıklı koşulun JS ifadesi (`c` = sayım değişkeni; null = bölge yok → sağlanmadı). */
function holdsExpr(op, n, v) {
  const sym = { GTE: '>=', LTE: '<=', EQ: '===', GT: '>', LT: '<' }[op] || '>='
  return `(${v} !== null && ${v} ${sym} ${Number(n)})`
}

/** Önerilen başlangıç değerlerinin sentetik formun sınırlarına kırpılması. */
const clamp = (v, lo, hi, d) => {
  const n = Number(v)
  return Number.isFinite(n) ? Math.min(hi, Math.max(lo, Math.round(n))) : d
}

/** Varsayılan çeviri (testsiz kullanım): anahtarı döndürür. */
const keyT = (k, ...a) => (a.length ? `${k}(${a.join(',')})` : k)

/**
 * Üreteç. Girdi: liste satırı (snake_case). Çıktı: `{ form, script, env, notes }` — `form` sentetik formun taslağı
 * (`formJson` olarak `new` taslağına yazılır; gizli env değerleri boş), `notes` = `[{ code, params }]`.
 */
export function keywordToK6(m, { t = keyT } = {}) {
  const notes = []
  const note = (code, ...params) => {
    const key = `${code}|${params.join('|')}`
    if (!notes.some((n) => `${n.code}|${n.params.join('|')}` === key)) notes.push({ code, params })
  }
  const row = m || {}
  const rules = rulesFromRow(row)
  const logic = logicOf(row)
  const level = row.alert_level || 'WARNING'
  const scopeVisible = String(row.match_scope || '').toUpperCase() === 'VISIBLE'
  const normalizeWs = scopeVisible || row.normalize_ws === true
  const method = String(row.method || 'GET').toUpperCase() === 'POST' ? 'POST' : 'GET'
  const env = []
  const lines = []
  const helpers = new Set()

  // ── İstek ──
  const uaMode = String(row.user_agent_mode || 'DEFAULT').toUpperCase()
  const ua = uaMode === 'BROWSER' ? BROWSER_USER_AGENT : uaMode === 'CUSTOM' && row.user_agent ? String(row.user_agent) : DEFAULT_USER_AGENT
  const hasAuth = !!(row.basic_auth_user || row.has_basic_auth_pass)
  if (hasAuth) {
    env.push({ name: 'BASIC_USER', value: String(row.basic_auth_user || ''), secret: false })
    env.push({ name: 'BASIC_PASS', value: '', secret: true })
  }
  const headerEnv = []
  if (row.has_custom_headers) {
    const names = (Array.isArray(row.custom_header_names) ? row.custom_header_names : []).filter((h) => typeof h === 'string' && h.trim())
    if (!names.length) note('HEADERS_NOT_CARRIED')
    const used = new Set(env.map((e) => e.name))
    names.slice(0, 20).forEach((h, i) => {
      let name = `HDR_${h.trim().toUpperCase().replace(/[^A-Z0-9]+/g, '_').replace(/^_+|_+$/g, '')}`.slice(0, 64)
      if (!validEnvName(name) || used.has(name)) name = `HDR_${i + 1}`
      used.add(name)
      headerEnv.push({ header: h.trim(), env: name })
      env.push({ name, value: '', secret: true })
    })
  }
  const timeoutMs = clamp(row.timeout_ms, 1000, 60000, 10000)
  const follow = row.follow_redirects !== false

  // ── Kurallar ──
  const ruleExprs = []
  let needDoc = scopeVisible
  rules.forEach((r, i) => {
    const title = (r.label && r.label.trim()) || t('keyword.rule.cardTitle', i + 1)
    const v = `c${i + 1}`
    if (r.critical) note('LEVELS_NOT_CARRIED')
    let src = 'PAGE'
    if (r.scope?.type === 'CSS') { src = `cssText(${jsString(r.scope.value.trim())})`; needDoc = true; helpers.add('css') } else if (r.scope?.type === 'JSON') {
      const segs = jsonPathSegments(r.scope.value)
      src = segs ? `jsonText([${segs.map((s) => (typeof s === 'number' ? String(s) : jsString(s))).join(', ')}])` : 'null'
      helpers.add('json')
    }
    let countExpr
    if (r.kind === 'REGEX') {
      const pr = portableRegex(r.pattern, r.case_sensitive)
      if (!pr) {
        note('REGEX_NOT_PORTABLE', title)
        lines.push(`  // ${commentText(t('keyword.k6.comment.regexNotPortable', title, r.pattern))}`)
        return
      }
      helpers.add('regex')
      countExpr = `countRegex(${src}, new RegExp(${jsString(pr.source)}, ${jsString(pr.flags)}), ${r.case_sensitive})`
    } else {
      helpers.add('text')
      countExpr = `countText(${src}, ${jsString(r.pattern)}, ${r.case_sensitive === true}, ${normalizeWs || r.scope?.type === 'CSS'})`
    }
    lines.push(`  const ${v} = ${countExpr}`)
    const desc = describeRule(r, t, level).healthy || title
    ruleExprs.push({ name: t('keyword.k6.check.rule', title, desc), expr: holdsExpr(r.operator, parseCount(r.count), v), title })
  })
  if (needDoc) helpers.add('doc')
  if (scopeVisible) note('SCOPE_APPROXIMATE')
  if (row.change_detect === true) note('CHANGE_NOT_CARRIED')
  const minB = parseCount(row.min_bytes)
  const maxB = parseCount(row.max_bytes)
  const sizeOn = Number.isFinite(minB) || Number.isFinite(maxB)
  if (sizeOn) note('SIZE_APPROXIMATE')
  const ranges = row.expected_status ? statusRanges(row.expected_status) : []
  const finalUrl = row.expected_final_url ? absoluteUrl(String(row.expected_final_url), String(row.url || '')) : null
  const interval = clamp(row.interval_seconds, 30, 86400, 300)
  if (interval < K6_INTERVAL_MIN) note('INTERVAL_RAISED', K6_INTERVAL_MIN)

  // ── Betik ──
  const name = commentText(row.name || row.url || '')
  const out = []
  out.push(`// ${commentText(t('keyword.k6.comment.source', name, row.id ?? '—'))}`)
  out.push(`// ${commentText(t('keyword.k6.comment.review'))}`)
  if (env.some((e) => e.secret)) out.push(`// ${commentText(t('keyword.k6.comment.secrets'))}`)
  out.push("import http from 'k6/http'")
  out.push("import { check } from 'k6'")
  if (hasAuth) out.push("import encoding from 'k6/encoding'")
  if (helpers.has('doc')) out.push("import { parseHTML } from 'k6/html'")
  out.push('')
  out.push(`const TARGET = ${jsString(row.url || '')}`)
  out.push(`const USER_AGENT = ${jsString(ua)}`)
  if (method === 'POST') {
    out.push(`const BODY = ${jsString(row.request_body ?? '')}`)
    out.push(`const CONTENT_TYPE = ${jsString(row.request_content_type || 'application/json')}`)
  }
  if (ranges.length) out.push(`const ACCEPTED_STATUS = ${JSON.stringify(ranges)}`)
  if (finalUrl) out.push(`const EXPECTED_FINAL_URL = ${jsString(normUrl(finalUrl))}`)
  out.push('')
  out.push(...helperSource(helpers, finalUrl))
  out.push('export default function () {')
  out.push("  const url = TARGET.split('{timestamp}').join(String(Date.now()))")
  out.push("  const headers = { 'User-Agent': USER_AGENT }")
  if (hasAuth) {
    out.push("  if (__ENV.BASIC_USER || __ENV.BASIC_PASS) {")
    out.push("    headers['Authorization'] = 'Basic ' + encoding.b64encode((__ENV.BASIC_USER || '') + ':' + (__ENV.BASIC_PASS || ''))")
    out.push('  }')
  }
  if (method === 'POST') out.push("  headers['Content-Type'] = CONTENT_TYPE")
  for (const h of headerEnv) out.push(`  if (__ENV.${h.env}) headers[${jsString(h.header)}] = __ENV.${h.env}`)
  out.push(`  const res = http.request(${jsString(method)}, url, ${method === 'POST' ? 'BODY' : 'null'}, { headers, redirects: ${follow ? 5 : 0}, timeout: ${jsString(`${timeoutMs}ms`)} })`)
  out.push("  const BODY_TEXT = typeof res.body === 'string' ? res.body : ''")
  if (helpers.has('doc')) out.push('  const DOC = parseHTML(BODY_TEXT)')
  out.push(scopeVisible ? '  const PAGE = visibleText(DOC)' : '  const PAGE = BODY_TEXT')
  if (helpers.has('css')) out.push('  const cssText = (sel) => cssTextOf(DOC, sel)')
  if (helpers.has('json')) out.push('  const jsonText = (segs) => jsonTextOf(BODY_TEXT, segs)')
  out.push(...lines)
  out.push('  check(res, {')
  if (ranges.length) out.push(`    ${jsString(t('keyword.k6.check.status', row.expected_status))}: (r) => ACCEPTED_STATUS.some((x) => r.status >= x[0] && r.status <= x[1]),`)
  if (finalUrl) out.push(`    ${jsString(t('keyword.k6.check.finalUrl', finalUrl))}: (r) => normUrl(r.url).indexOf(EXPECTED_FINAL_URL) === 0,`)
  if (sizeOn) {
    const conds = []
    if (Number.isFinite(minB)) conds.push(`BODY_TEXT.length >= ${minB}`)
    if (Number.isFinite(maxB)) conds.push(`BODY_TEXT.length <= ${maxB}`)
    out.push(`    ${jsString(t('keyword.k6.check.size', [Number.isFinite(minB) ? `≥ ${minB}` : null, Number.isFinite(maxB) ? `≤ ${maxB}` : null].filter(Boolean).join(' · ')))}: () => ${conds.join(' && ')},`)
  }
  if (logic === 'OR' && ruleExprs.length > 1) {
    out.push(`    ${jsString(t('keyword.k6.check.any', ruleExprs.map((x) => x.title).join(', ')))}: () => ${ruleExprs.map((x) => x.expr).join(' || ')},`)
  } else {
    for (const x of ruleExprs) out.push(`    ${jsString(x.name)}: () => ${x.expr},`)
  }
  // Hiç denetim kalmadıysa (tek kural taşınamadı, durum / URL / boyut kuralı yok) koşum "denetimsiz" sayılmasın
  if (!ranges.length && !finalUrl && !sizeOn && !ruleExprs.length) out.push(`    ${jsString(t('keyword.k6.check.responded'))}: (r) => r.status > 0,`)
  out.push('  })')
  out.push('}')
  const script = `${out.join('\n')}\n`

  // ── Form taslağı ──
  const baseName = String(row.name || row.url || '').trim()
  const form = {
    name: t('keyword.k6.nameSuffix', baseName).slice(0, 200),
    description: commentText(t('keyword.k6.description', row.url || '')),
    teamId: row.team_id != null ? String(row.team_id) : '',
    groupName: row.group_name || '',
    tags: row.tags || '',
    notifyEmail: row.notify_email !== false,
    notifyWebhook: row.notify_webhook !== false,
    alertLevel: row.alert_level || 'WARNING',
    notificationGroupId: row.notification_group_id != null ? String(row.notification_group_id) : '',
    nocNotify: !!row.noc_notify,
    nocGroupIds: Array.isArray(row.noc_group_ids) ? row.noc_group_ids.map(String) : [],
    intervalSeconds: Math.max(K6_INTERVAL_MIN, interval),
    timeoutSeconds: Math.min(180, Math.max(30, Math.ceil(timeoutMs / 1000) + 20)),
    confirmAttempts: clamp(row.confirm_attempts, 0, 10, 3),
    confirmIntervalSeconds: clamp(row.confirm_interval_seconds, 10, 600, 30),
    recoveryChecks: clamp(row.recovery_checks, 1, 10, 3),
    recoveryIntervalSeconds: clamp(row.recovery_interval_seconds, 10, 600, 30),
    slowResponseEnabled: row.slow_response_enabled === true,
    slowThresholdMs: clamp(row.slow_threshold_ms, 500, 180000, 15000),
    useProxy: ['AUTO', 'ON', 'OFF'].includes(String(row.use_proxy || '').toUpperCase()) ? String(row.use_proxy).toUpperCase() : 'AUTO',
    active: true,
    template: '',
    script,
    env: env.map((e) => ({ name: e.name, secret: e.secret, value: e.secret ? '' : e.value })),
  }
  return { form, script, env: form.env, notes }
}

/** Göreli beklenen son URL'yi izleme URL'sinin kaynağına göre mutlak yapar (sistem yolu taraması "/…" görmesin). */
export function absoluteUrl(expected, base) {
  const e = expected.trim()
  if (/^https?:\/\//i.test(e)) return e
  const m = /^(https?:\/\/[^/?#]+)/i.exec(base.replace('{timestamp}', '0'))
  return m ? `${m[1]}${e.startsWith('/') ? '' : '/'}${e}` : e
}

/** URL karşılaştırma biçimi (sunucu §5.3): şema + host küçük harf, varsayılan port ve parça düşer. */
export function normUrl(u) {
  const s = String(u ?? '').split('#')[0]
  const m = /^([a-z][a-z0-9+.-]*):\/\/([^/?#]*)(.*)$/i.exec(s)
  if (!m) return s
  const scheme = m[1].toLowerCase()
  let host = m[2].toLowerCase()
  if ((scheme === 'http' && host.endsWith(':80')) || (scheme === 'https' && host.endsWith(':443'))) host = host.replace(/:\d+$/, '')
  return `${scheme}://${host}${m[3]}`
}

/** Yalnız gereken yardımcıların kaynağı (betik küçük kalsın). Döngüler sınırlı (sayım tavanı); `open` adı GEÇMEZ. */
function helperSource(helpers, finalUrl) {
  const out = []
  if (helpers.has('text') || helpers.has('regex')) {
    out.push('// Türkçe harf katlama — sunucunun KeywordFold kuralı: İ, I, ı, i → i; diğerleri küçük harf')
    out.push("function fold(s) { return String(s).split('\\u0130').join('i').split('\\u0131').join('i').toLowerCase() }")
  }
  if (helpers.has('text')) {
    out.push("function normText(s) { return String(s).replace(/&nbsp;|&#160;/g, ' ').replace(/&amp;/g, '&').replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '\"').replace(/&#39;/g, \"'\").replace(/\\s+/g, ' ').trim() }")
    out.push('function countText(hay, needle, caseSensitive, normalize) {')
    out.push('  if (hay === null || hay === undefined || !needle) return hay === null || hay === undefined ? null : 0')
    out.push('  let h = normalize ? normText(hay) : String(hay)')
    out.push('  let n = normalize ? normText(needle) : String(needle)')
    out.push('  if (!caseSensitive) { h = fold(h); n = fold(n) }')
    out.push('  if (!n) return 0')
    out.push('  let count = 0')
    out.push('  let from = 0')
    out.push(`  while (count < ${TEXT_COUNT_CAP}) {`)
    out.push('    const idx = h.indexOf(n, from)')
    out.push('    if (idx < 0) break')
    out.push('    count++')
    out.push('    from = idx + n.length')
    out.push('  }')
    out.push('  return count')
    out.push('}')
  }
  if (helpers.has('regex')) {
    out.push('function countRegex(hay, re, caseSensitive) {')
    out.push('  if (hay === null || hay === undefined) return null')
    out.push(`  const h = caseSensitive ? String(hay).slice(0, ${REGEX_SCAN_CAP}) : fold(String(hay).slice(0, ${REGEX_SCAN_CAP}))`)
    out.push('  let count = 0')
    out.push(`  while (count < ${REGEX_COUNT_CAP}) {`)
    out.push('    const m = re.exec(h)')
    out.push('    if (m === null) break')
    out.push('    if (m[0] === \'\') { re.lastIndex++; continue }')
    out.push('    count++')
    out.push('  }')
    out.push('  return count')
    out.push('}')
  }
  if (helpers.has('doc')) {
    out.push('function visibleText(doc) {')
    out.push("  const body = doc.find('body')")
    out.push("  return body.size() > 0 ? body.text() : doc.text()")
    out.push('}')
  }
  if (helpers.has('css')) {
    out.push('function cssTextOf(doc, sel) {')
    // Geçersiz seçici: k6 (goquery) boş seçim döner; başka bir çalışma ortamı atarsa da kapsam "bulunamadı" sayılır
    out.push('  let found')
    out.push('  try { found = doc.find(sel) } catch (e) { return null }')
    out.push('  if (found.size() === 0) return null')
    out.push("  return found.toArray().slice(0, 1000).map((e) => e.text()).join('\\n')")
    out.push('}')
  }
  if (helpers.has('json')) {
    out.push('function jsonTextOf(body, segs) {')
    out.push('  let cur')
    out.push('  try { cur = JSON.parse(body) } catch (e) { return null }')
    out.push('  for (const s of segs) {')
    out.push("    if (cur === null || typeof cur !== 'object') return null")
    out.push('    cur = cur[s]')
    out.push('  }')
    out.push('  if (cur === undefined) return null')
    out.push("  if (typeof cur === 'string') return cur")
    out.push("  return typeof cur === 'object' && cur !== null ? JSON.stringify(cur) : String(cur)")
    out.push('}')
  }
  if (finalUrl) {
    out.push('function normUrl(u) {')
    out.push("  const s = String(u || '').split('#')[0]")
    out.push("  const i = s.indexOf('://')")
    out.push('  if (i < 0) return s')
    out.push("  const j = s.slice(i + 3).search(/[\\/?#]/)")
    out.push('  const end = j < 0 ? s.length : i + 3 + j')
    out.push('  const scheme = s.slice(0, i).toLowerCase()')
    out.push('  let host = s.slice(i + 3, end).toLowerCase()')
    out.push("  if ((scheme === 'http' && host.endsWith(':80')) || (scheme === 'https' && host.endsWith(':443'))) host = host.slice(0, host.lastIndexOf(':'))")
    out.push("  return scheme + '://' + host + s.slice(end)")
    out.push('}')
  }
  if (out.length) out.push('')
  return out
}
