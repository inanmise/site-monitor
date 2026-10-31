/**
 * Sentetik İzleme (k6) formunun SAF modeli (2026-10-31, k6r fe1) — React'siz test edilir.
 *
 * İçerik:
 *  - `__ENV` kullanımı: betikte okunan değişken adları (yorum / dizge atlanır; şablon dizgesinin `${…}` ifadeleri
 *    taranır) ↔ formdaki env satırları → "tanımsız / kullanılmıyor / gizli işaretli değil" çipleri.
 *  - Kaynak değişiminde env BİRLEŞTİRME (H1): aynı adlı satır olduğu gibi kalır, yalnız eksikler eklenir, fazlalar
 *    silinmez — onay penceresi kaç değişkenin ekleneceğini söyler.
 *  - Test gövdesinin env'i (B1): düzenlemede değeri boş + `value_set` gizli satır `keep: true` ile gider, sunucu
 *    kayıtlı (şifreli) değeri kullanır.
 *  - Şablon değişken sihirbazı yardımcıları (açıklama, boş değer, "Hedef: host" önizlemesi).
 *  - Koşum sonucu yardımcıları: son başarılı adım, `run_context`, Türkçe bağlam satırının ayıklanması.
 *  - Zamanlama alanları (dbquery ScheduleEditor değeri ↔ form) ve "Zamanlama ve alarm" bölümünün alan anahtarları.
 *
 * Sınırsız döngü / düzenli ifade yok: betik taraması TEK geçiş ve uzunluk tavanlı; adlar sabit kalıplarla.
 */

// ── __ENV kullanımı ──────────────────────────────────────────────────────────────────────────────────────────

/** Taranan en uzun betik (karakter) — kayıt tavanıyla aynı büyüklük sınıfı; fazlası taranmaz (sonuç "kısmi"). */
export const MAX_ENV_SCAN_CHARS = 512 * 1024
/** İç içe şablon dizgesi derinliği tavanı — aşılırsa tarama durur (sonuç "kısmi", kullanılmıyor çipi çizilmez). */
const MAX_TEMPLATE_DEPTH = 64

const isIdStart = (c) => (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c === '_' || c === '$'
const isIdPart = (c) => isIdStart(c) || (c >= '0' && c <= '9')
const isSpace = (c) => c === ' ' || c === '\t' || c === '\n' || c === '\r'

/**
 * Betikte `__ENV.X`, `__ENV?.X`, `__ENV['X']`, `__ENV["X"]` ile okunan adlar.
 *
 * Yorumlar ve sıradan dizgeler atlanır; şablon dizgesinin İÇİ atlanır ama `${…}` ifadeleri kod gibi taranır (k6
 * betiklerinde en sık kullanım `` `${__ENV.BASE_URL}/api` ``). `__ENV` adı başka biçimde geçerse (`__ENV[ad]`,
 * `const { A } = __ENV`, işleve argüman) `dynamic = true` olur: hangi adların okunduğu bilinemez — çağıran
 * "kullanılmıyor" çipini çizmez. Bilinen sınır: düzenli ifade değişmezleri (`/…/`) ayrıştırılmaz; içinde tırnak
 * geçen bir düzenli ifade o satırın kalanını dizge sanabilir (yalnız çip eksik / fazla olur, hiçbir şey engellenmez).
 *
 * @returns {{ names: string[], dynamic: boolean, partial: boolean }} adlar ilk görülme sırasıyla, tekil
 */
export function scanEnvUsage(script) {
  const src = typeof script === 'string' ? script : ''
  const partial = src.length > MAX_ENV_SCAN_CHARS
  const s = partial ? src.slice(0, MAX_ENV_SCAN_CHARS) : src
  const names = []
  const seen = new Set()
  let dynamic = false
  const add = (n) => { if (n && !seen.has(n)) { seen.add(n); names.push(n) } }
  // Yığın: kod çerçevesi { kind:'code', depth } (en altta kök) ya da şablon dizgesi { kind:'tpl' }.
  const stack = [{ kind: 'code', depth: 0 }]
  let i = 0
  const n = s.length
  let stopped = false
  while (i < n && !stopped) {
    const top = stack[stack.length - 1]
    const c = s[i]
    if (top.kind === 'tpl') {
      if (c === '\\') { i += 2; continue }
      if (c === '`') { stack.pop(); i++; continue }
      if (c === '$' && s[i + 1] === '{') {
        if (stack.length >= MAX_TEMPLATE_DEPTH) { stopped = true; break }
        stack.push({ kind: 'code', depth: 0, inTpl: true }); i += 2; continue
      }
      i++; continue
    }
    // ── kod ──
    if (c === '/' && s[i + 1] === '/') {   // satır yorumu
      const e = s.indexOf('\n', i + 2); i = e < 0 ? n : e + 1; continue
    }
    if (c === '/' && s[i + 1] === '*') {   // blok yorumu (kapanmazsa sona kadar)
      const e = s.indexOf('*/', i + 2); i = e < 0 ? n : e + 2; continue
    }
    if (c === '\'' || c === '"') { i = skipQuoted(s, i, c); continue }
    if (c === '`') {
      if (stack.length >= MAX_TEMPLATE_DEPTH) { stopped = true; break }
      stack.push({ kind: 'tpl' }); i++; continue
    }
    if (c === '{') { top.depth++; i++; continue }
    if (c === '}') {
      if (top.depth > 0) top.depth--
      else if (top.inTpl) stack.pop()   // `${ … }` ifadesinin kapanışı → şablon dizgesine dönüş
      i++; continue
    }
    if (isIdStart(c)) {
      let j = i + 1
      while (j < n && isIdPart(s[j])) j++
      const word = s.slice(i, j)
      const prev = previousNonSpace(s, i)
      if (word === '__ENV' && prev !== '.') {
        const r = readEnvAccess(s, j)
        if (r.name) add(r.name)
        else dynamic = true
        i = r.next
        continue
      }
      i = j; continue
    }
    i++
  }
  return { names, dynamic: dynamic || stopped, partial: partial || stopped }
}

/** Tırnaklı dizgeyi atlar (kaçışlı tırnak dahil); kapanmazsa satır sonunda biter. Dönüş: sonraki konum. */
function skipQuoted(s, i, q) {
  let j = i + 1
  while (j < s.length) {
    const c = s[j]
    if (c === '\\') { j += 2; continue }
    if (c === q) return j + 1
    if (c === '\n') return j + 1
    j++
  }
  return s.length
}

function previousNonSpace(s, i) {
  let k = i - 1
  while (k >= 0 && isSpace(s[k])) k--
  return k >= 0 ? s[k] : ''
}

/** `__ENV` sonrası erişimi okur: `.X`, `?.X`, `['X']`, `["X"]` → ad; aksi hâlde ad yok (dinamik). */
function readEnvAccess(s, j) {
  let k = j
  while (k < s.length && isSpace(s[k])) k++
  if (s[k] === '?' && s[k + 1] === '.') k += 2
  else if (s[k] === '.') k += 1
  else if (s[k] === '[') {
    let m = k + 1
    while (m < s.length && isSpace(s[m])) m++
    const q = s[m]
    if (q === '\'' || q === '"' || q === '`') {
      let e = m + 1
      while (e < s.length && s[e] !== q && s[e] !== '\n' && s[e] !== '\\' && !(q === '`' && s[e] === '$')) e++
      if (s[e] === q) {
        const name = s.slice(m + 1, e)
        let after = e + 1
        while (after < s.length && isSpace(s[after])) after++
        if (s[after] === ']' && name) return { name, next: after + 1 }
      }
    }
    return { name: null, next: k + 1 }
  } else {
    return { name: null, next: k }
  }
  while (k < s.length && isSpace(s[k])) k++
  let e = k
  while (e < s.length && isIdPart(s[e])) e++
  return e > k ? { name: s.slice(k, e), next: e } : { name: null, next: k }
}

/** Değeri gizli görünen ad parçaları (alt çizgiyle ayrılmış). */
const SECRET_PARTS = new Set(['PASSWORD', 'PASSWD', 'PASS', 'PWD', 'TOKEN', 'SECRET', 'APIKEY', 'CREDENTIAL', 'CREDENTIALS'])
/** Bu parçalarla BİTEN ad bir adres / tanımlayıcıdır, sır değil (`TOKEN_URL`, `PASSWORD_FIELD`). */
const NON_SECRET_TAILS = new Set(['URL', 'URI', 'HOST', 'ENDPOINT', 'PATH', 'NAME', 'FIELD', 'HEADER', 'TYPE', 'ID', 'USER', 'USERNAME'])
/** `KEY` yalnız bu öneklerle sır sayılır (`API_KEY`, `PRIVATE_KEY`); `CACHE_KEY` sır değildir. */
const KEY_PREFIXES = new Set(['API', 'PRIVATE', 'SECRET', 'ACCESS', 'CLIENT', 'SUBSCRIPTION', 'SIGNING'])

/** Ad değeri gizli olması gereken bir değişkeni mi anlatıyor? (PASSWORD, CLIENT_SECRET, ACCESS_TOKEN, API_KEY …) */
export function looksSecretName(name) {
  const parts = String(name || '').toUpperCase().split(/[^A-Z0-9]+/).filter(Boolean)
  if (parts.length === 0 || parts.length > 12) return false
  if (NON_SECRET_TAILS.has(parts[parts.length - 1])) return false
  for (let i = 0; i < parts.length; i++) {
    if (SECRET_PARTS.has(parts[i])) return true
    if (parts[i] === 'KEY' && i > 0 && KEY_PREFIXES.has(parts[i - 1])) return true
  }
  return false
}

/**
 * Canlı `__ENV` denetimi: betik ↔ env satırları.
 * @returns {{ undefinedNames: string[], unusedNames: string[], notSecret: string[], dynamic: boolean }}
 */
export function envLint(script, env) {
  const usage = scanEnvUsage(script)
  const rows = (Array.isArray(env) ? env : []).filter((e) => (e?.name || '').trim())
  const rowNames = rows.map((e) => e.name.trim())
  const defined = new Set(rowNames)
  const used = new Set(usage.names)
  const undefinedNames = usage.names.filter((x) => !defined.has(x))
  // Dinamik / kısmi taramada hangi adın okunduğu bilinemez → "kullanılmıyor" demek yanlış olabilir
  const unusedNames = usage.dynamic || usage.partial ? [] : uniq(rowNames.filter((x) => !used.has(x)))
  const notSecret = uniq(rows.filter((e) => !e.secret && looksSecretName(e.name)).map((e) => e.name.trim()))
  return { undefinedNames, unusedNames, notSecret, dynamic: usage.dynamic }
}

const uniq = (list) => [...new Set(list)]

// ── Env birleştirme (kaynak değişimi, sürüm yükleme) ────────────────────────────────────────────────────────

/**
 * Formdaki env'e kaynaktan gelen satırları BİRLEŞTİRİR (H1 / D2).
 *  - Aynı adlı satır (kırpılmış, büyük/küçük harf duyarlı) OLDUĞU GİBİ kalır: değer, `value_set`, gizli işareti.
 *  - Eksik adlar sona eklenir: gizli satırın değeri taşınmaz (`value: ''`); `value_set` yalnız `keepSecret(row)`
 *    true ise korunur (kendi kaydına dönüş); gizli olmayan satırın değeri `withValues` ise taşınır.
 *  - Formda olup kaynakta olmayan satırlar SİLİNMEZ.
 * @returns {{ env: object[], added: string[], addedSecret: string[] }}
 */
export function mergeEnv(current, incoming, { withValues = false, keepSecret = () => false } = {}) {
  const env = Array.isArray(current) ? [...current] : []
  const have = new Set(env.map((e) => (e?.name || '').trim()).filter(Boolean))
  const added = []
  const addedSecret = []
  for (const r of Array.isArray(incoming) ? incoming : []) {
    const name = (r?.name || '').trim()
    if (!name || have.has(name)) continue
    have.add(name)
    const secret = !!r.secret
    const row = { name, secret, value: !secret && withValues ? String(r.value ?? '') : '' }
    if (secret) row.value_set = !!r.value_set && !!keepSecret(r)
    env.push(row)
    added.push(name)
    if (secret) addedSecret.push(name)
  }
  return { env, added, addedSecret }
}

// ── Test gövdesi (B1) ───────────────────────────────────────────────────────────────────────────────────────

/** Düzenleme formunda "kayıtlı değerle" koşacak satır mı? (gizli, değeri boş, sunucuda değeri kayıtlı) */
export function usesSavedSecret(row, editMode) {
  return !!editMode && !!row?.secret && !!row?.value_set && !(row?.value || '')
}

/**
 * Test isteğinin env'i: adı dolu satırlar. Düzenlemede değeri boş + `value_set` gizli satır `keep: true` ile gider
 * (sunucu izlemenin GÜNCEL env'indeki şifreli değeri kullanır); yeni izlemede / kopyada `keep` hiç gönderilmez.
 * @returns {{ env: object[], kept: string[] }}
 */
export function testEnvPayload(env, { editMode = false } = {}) {
  const out = []
  const kept = []
  for (const e of Array.isArray(env) ? env : []) {
    const name = (e?.name || '').trim()
    if (!name) continue
    if (usesSavedSecret(e, editMode)) {
      out.push({ name, value: '', secret: true, keep: true })
      kept.push(name)
    } else {
      out.push({ name, value: e.value || '', secret: !!e.secret })
    }
  }
  return { env: out, kept }
}

/**
 * Sunucu `keep` satırlarını tanıdı mı? Gönderdiğimiz `keep` satırı varken yanıtta `kept_secrets` dizisi YOKSA eski
 * sürüm bir pod yanıtlamıştır: kayıtlı gizli değerler teste gitmedi (koşum boş değerle yapıldı).
 */
export function keptSecretsIgnored(sentKept, data) {
  return Array.isArray(sentKept) && sentKept.length > 0 && !Array.isArray(data?.kept_secrets)
}

// ── Şablon değişken sihirbazı ───────────────────────────────────────────────────────────────────────────────

/** Şablon env tanımları → ad → açıklama (dil seçimli; açıklama yoksa ad haritada yok). */
export function templateEnvDescs(tpl, lang = 'tr') {
  const out = new Map()
  for (const e of Array.isArray(tpl?.env) ? tpl.env : []) {
    const name = (e?.name || '').trim()
    if (!name) continue
    const tr = (e.desc || '').trim()
    const en = (e.desc_en || e.descEn || '').trim()
    const d = lang === 'en' ? (en || tr) : (tr || en)
    if (d) out.set(name, d)
  }
  return out
}

/** Değeri boş satırların adları (gizli + `value_set` dolu sayılır — kayıtlı değer var). */
export function emptyEnvNames(env) {
  const out = []
  for (const e of Array.isArray(env) ? env : []) {
    const name = (e?.name || '').trim()
    if (!name) continue
    if ((e.value || '').trim()) continue
    if (e.secret && e.value_set) continue
    out.push(name)
  }
  return uniq(out)
}

/** `BASE_URL` / `*_URL` adlı, gizli olmayan satırın değerinden "Hedef: host" (yalnız http/https). */
export function targetHostOf(name, value) {
  const n = String(name || '').trim().toUpperCase()
  if (!(n === 'BASE_URL' || n.endsWith('_URL'))) return null
  const v = String(value || '').trim()
  if (!v || v.length > 2048) return null
  try {
    const u = new URL(v)
    if (u.protocol !== 'http:' && u.protocol !== 'https:') return null
    return u.host || null
  } catch { return null }
}

// ── Koşum sonucu ────────────────────────────────────────────────────────────────────────────────────────────

const failedStep = (s) => (Number(s?.checks_failed) > 0) || (Number(s?.failed_requests) > 0) || (s?.error_code != null)

/**
 * ERROR / TIMEOUT koşumunda "senaryo nerede durdu?":
 *  - `steps` varsa: ilk sorunlu adımdan ÖNCEKİ adım = son başarılı (`after`), sorunlu adım = `at`; sorunlu adım yoksa
 *    son adım `after` (senaryo son görülen adımdan sonra kesildi).
 *  - yoksa `checks_json` ([{name, passed}]) üzerinden aynı kural.
 * Adım adı `""` (kök istekler) olabilir — çağıran "Kök" yazar. Bilgi yoksa null.
 * @returns {{ after: string|null, at: string|null }|null}
 */
export function stopPoint(result) {
  if (!result || (result.status !== 'ERROR' && result.status !== 'TIMEOUT')) return null
  const steps = Array.isArray(result.steps) ? result.steps.filter((x) => x && typeof x === 'object') : null
  if (steps && steps.length) {
    const idx = steps.findIndex(failedStep)
    if (idx < 0) return { after: str(steps[steps.length - 1].name), at: null }
    return { after: idx > 0 ? str(steps[idx - 1].name) : null, at: str(steps[idx].name) }
  }
  let checks = []
  try {
    const raw = result.checks_json ?? result.checksJson
    checks = raw ? JSON.parse(raw) : []
  } catch { checks = [] }
  if (!Array.isArray(checks) || checks.length === 0) return null
  const idx = checks.findIndex((c) => c && c.passed === false)
  if (idx < 0) return { after: str(checks[checks.length - 1]?.name), at: null }
  return { after: idx > 0 ? str(checks[idx - 1]?.name) : null, at: str(checks[idx]?.name) }
}
const str = (v) => (v == null ? '' : String(v))

/**
 * Yapılandırılmış `run_context` varsa hata metnindeki Türkçe bağlam satırı ("süreç bütçesi=… · çıkış=…") düşer —
 * arayüz aynı bilgiyi dil doğru çiplerle gösterir. Alan yoksa (eski sunucu / eski satır) metin AYNEN kalır.
 */
export function stripContextLine(error, runContext) {
  if (typeof error !== 'string' || !error || !runContext || typeof runContext !== 'object') return error
  const kept = error.split('\n').filter((l) => !l.trimStart().startsWith('süreç bütçesi='))
  return kept.join('\n').trimEnd()
}

// ── Zamanlama ───────────────────────────────────────────────────────────────────────────────────────────────

/** Formun zamanlama alt nesnesi (dbquery ScheduleEditor değer adlarıyla; aralık formun `intervalSeconds`'ında). */
export const DEFAULT_SCRIPTED_SCHEDULE = Object.freeze({
  schedule_mode: 'INTERVAL', cron_expression: '', windows: [], blackouts: [], skip_holidays: false, skip_dates: [],
})

/** Satır → formun zamanlama alt nesnesi. Eski sunucunun satırı alan taşımaz → varsayılan (her aralıkta). */
export function scheduleFromScriptedRow(m) {
  const arr = (v) => (Array.isArray(v) ? v : [])
  return {
    schedule_mode: m?.schedule_mode === 'CRON' ? 'CRON' : 'INTERVAL',
    cron_expression: typeof m?.cron_expression === 'string' ? m.cron_expression : '',
    windows: arr(m?.windows), blackouts: arr(m?.blackouts),
    skip_holidays: !!m?.skip_holidays, skip_dates: arr(m?.skip_dates),
  }
}

/** ScheduleEditor değeri (aralık formdan). */
export function scheduleEditorValue(form) {
  const s = form?.schedule || DEFAULT_SCRIPTED_SCHEDULE
  return { ...DEFAULT_SCRIPTED_SCHEDULE, ...s, interval_seconds: form?.intervalSeconds ?? null }
}

/** Kayıt gövdesine giden zamanlama anahtarları (sözleşme: snake_case, aralık mevcut `intervalSeconds`). */
export function schedulePayload(schedule) {
  const s = { ...DEFAULT_SCRIPTED_SCHEDULE, ...(schedule || {}) }
  return {
    schedule_mode: s.schedule_mode === 'CRON' ? 'CRON' : 'INTERVAL',
    cron_expression: String(s.cron_expression || ''),
    windows: Array.isArray(s.windows) ? s.windows : [],
    blackouts: Array.isArray(s.blackouts) ? s.blackouts : [],
    skip_holidays: !!s.skip_holidays,
    skip_dates: Array.isArray(s.skip_dates) ? s.skip_dates : [],
  }
}

/** Zamanlama düzenleyicisinin alan anahtarları (sunucu `field` değerleri — dbquery kodlarıyla aynı). */
export const SCHEDULE_ERROR_KEYS = Object.freeze(['schedule', 'schedule_mode', 'interval_seconds', 'cron_expression',
  'windows', 'blackouts', 'skip_dates'])

/** "Zamanlama ve alarm" bölümündeki alanların hata anahtarları — biri hatalıysa bölüm açılır. */
export const TIMING_SECTION_KEYS = Object.freeze([...SCHEDULE_ERROR_KEYS, 'intervalSeconds', 'timeoutSeconds',
  'confirmAttempts', 'recoveryChecks', 'slowThresholdMs', 'useProxy'])

/** Hata haritasında bölüm alanı var mı? */
export function hasTimingError(errors) {
  return Object.entries(errors || {}).some(([k, v]) => !!v && TIMING_SECTION_KEYS.includes(k))
}

/** Hata haritasının zamanlama düzenleyicisine giden kısmı (`interval_seconds` formun `intervalSeconds`'ından da). */
export function scheduleErrorsOf(errors) {
  const out = {}
  for (const k of SCHEDULE_ERROR_KEYS) if (errors?.[k]) out[k] = errors[k]
  if (!out.interval_seconds && errors?.intervalSeconds) out.interval_seconds = errors.intervalSeconds
  return out
}

/**
 * Kaydetmeden önce istemcide söylenebilen zamanlama hataları (sunucu asıl doğrulayıcıdır): cron kipinde boş ifade,
 * aralık kipinde aralık sorunu, eksik pencere. Dönüş: `{ alan: kod | null }` — kodlar `dbq.sched.v.*` ve
 * `scripted.f.cronRequired`.
 */
export function scheduleProblems(form, { intervalProblem, windowProblems, minInterval = 60 }) {
  const s = form?.schedule || DEFAULT_SCRIPTED_SCHEDULE
  const out = {}
  if (s.schedule_mode === 'CRON') {
    if (!String(s.cron_expression || '').trim()) out.cron_expression = 'cron_required'
  } else {
    const p = intervalProblem(form?.intervalSeconds, minInterval)
    if (p) out.interval_seconds = p
  }
  for (const kind of ['windows', 'blackouts']) {
    for (const w of Array.isArray(s[kind]) ? s[kind] : []) {
      const p = windowProblems(w)
      const code = p.days || p.from || p.to
      if (code) { out[kind] = code; break }
    }
  }
  return out
}
