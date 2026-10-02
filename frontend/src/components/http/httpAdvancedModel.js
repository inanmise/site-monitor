/**
 * HTTP izlemesi — "Gelişmiş istek" bölümünün SAF modeli (2026-10-01, onaylı öneri 9): form alanları, istemci tarafı
 * doğrulama (sunucudaki `HttpRequestRules` / `JsonAssertion` kurallarının aynası — anında geri bildirim; GARANTİ
 * sunucudadır) ve kayıt yükü.
 *
 * <p><b>Geriye uyum sözleşmesi.</b> Yük YALNIZ DEĞİŞEN gelişmiş alanları taşır ({@link httpAdvancedPayload}): bu bölüme
 * dokunulmamış bir izlemenin oluşturma/düzenleme/kopyalama yükü 2026-10-01 öncesiyle BİREBİR aynıdır (sayfa testi
 * `HttpMonitorPage.test.jsx` "Kopyala" tam-yük karşılaştırması bunu kilitler). Sırlar (parola, başlıklar) write-only:
 * formda boş başlar, yalnız YAZILIRSA gönderilir — boş bırakmak "kayıtlıyı koru" demektir.
 */

/** Sunucu tavanları (HttpRequestRules ile aynı). */
export const ADV_LIMITS = Object.freeze({
  maxBodyBytes: 64 * 1024,
  maxHeaders: 20,
  maxHeaderValue: 4096,
  maxContentType: 100,
  maxJsonPath: 300,
  maxJsonExpected: 500,
  minSlowMs: 100,
  maxSlowMs: 300000,
  defaultSlowMs: 3000,
})

/** Gelişmiş alanların boş (bugünkü davranış) hâli — yeni izleme ve "değişmedi" karşılaştırmasının tabanı. */
export const ADV_EMPTY = Object.freeze({
  customHeaders: '', clearHeaders: false,
  basicAuthUser: '', basicAuthPass: '',
  requestBody: '', requestContentType: '',
  slowResponseEnabled: false, slowThresholdMs: ADV_LIMITS.defaultSlowMs,
  jsonPath: '', jsonExpected: '',
})

/** Liste satırı (snake_case) → gelişmiş form alanları. Sırlar ASLA gelmez → boş başlar. */
export function advFormFrom(m) {
  return {
    customHeaders: '', clearHeaders: false,
    basicAuthUser: m?.basic_auth_user || '', basicAuthPass: '',
    requestBody: m?.request_body || '', requestContentType: m?.request_content_type || '',
    slowResponseEnabled: !!m?.slow_response_enabled,
    slowThresholdMs: m?.slow_threshold_ms ?? ADV_LIMITS.defaultSlowMs,
    jsonPath: m?.json_path || '', jsonExpected: m?.json_expected || '',
  }
}

const trimOrNull = (v) => {
  const s = String(v ?? '').trim()
  return s === '' ? null : s
}
const rawOrNull = (v) => (String(v ?? '').trim() === '' ? null : String(v))

/**
 * Kayıt yüküne eklenecek gelişmiş anahtarlar — YALNIZ `base`'e göre değişenler (+ yazılmış sırlar).
 *
 * @param form            güncel form
 * @param base            karşılaştırma tabanı: yeni kayıt/kopya için {@link ADV_EMPTY}, düzenleme için advFormFrom(satır)
 * @param canEditHeaders  global admin mi (sunucu diğerlerinin başlığını zaten yok sayar; gönderilmez)
 */
export function httpAdvancedPayload(form, base = ADV_EMPTY, { canEditHeaders = false } = {}) {
  const out = {}
  const b = { ...ADV_EMPTY, ...(base || {}) }
  if (trimOrNull(form.basicAuthUser) !== trimOrNull(b.basicAuthUser)) out.basicAuthUser = trimOrNull(form.basicAuthUser)
  if (form.basicAuthPass) out.basicAuthPass = form.basicAuthPass
  if (canEditHeaders) {
    if (String(form.customHeaders || '').trim()) out.customHeaders = String(form.customHeaders).trim()
    else if (form.clearHeaders) out.customHeaders = null
  }
  if (rawOrNull(form.requestBody) !== rawOrNull(b.requestBody)) out.requestBody = rawOrNull(form.requestBody)
  if (trimOrNull(form.requestContentType) !== trimOrNull(b.requestContentType)) out.requestContentType = trimOrNull(form.requestContentType)
  if (!!form.slowResponseEnabled !== !!b.slowResponseEnabled) out.slowResponseEnabled = !!form.slowResponseEnabled
  // Eşik yalnız alarm AÇIKKEN gönderilir (kapalıyken boşaltılmış kutu sunucuda aralık hatası doğurmasın)
  if (form.slowResponseEnabled && Number(form.slowThresholdMs) !== Number(b.slowThresholdMs)) out.slowThresholdMs = Number(form.slowThresholdMs)
  if (trimOrNull(form.jsonPath) !== trimOrNull(b.jsonPath)) out.jsonPath = trimOrNull(form.jsonPath)
  if (trimOrNull(form.jsonExpected) !== trimOrNull(b.jsonExpected)) out.jsonExpected = trimOrNull(form.jsonExpected)
  return out
}

/** "Test et" yükü — formda YAZILI dolu değerler (deneme kaydı okumaz); boş form eskisiyle aynı yükü üretir. */
export function httpAdvancedTestPayload(form, { canEditHeaders = false, method = 'GET' } = {}) {
  const out = {}
  if (canEditHeaders && String(form.customHeaders || '').trim()) out.customHeaders = String(form.customHeaders).trim()
  if (trimOrNull(form.basicAuthUser)) out.basicAuthUser = trimOrNull(form.basicAuthUser)
  if (form.basicAuthPass) out.basicAuthPass = form.basicAuthPass
  if (method === 'POST' && rawOrNull(form.requestBody)) out.requestBody = String(form.requestBody)
  if (method === 'POST' && trimOrNull(form.requestContentType)) out.requestContentType = trimOrNull(form.requestContentType)
  if (trimOrNull(form.jsonPath)) out.jsonPath = trimOrNull(form.jsonPath)
  if (trimOrNull(form.jsonExpected)) out.jsonExpected = trimOrNull(form.jsonExpected)
  return out
}

/** Kaç gelişmiş ayar AÇIK (bölüm başlığındaki rozet) — kayıtlı sırlar da sayılır. */
export function advancedActiveCount(form, stored = {}) {
  let n = 0
  if (String(form.customHeaders || '').trim() || (stored.hasHeaders && !form.clearHeaders)) n++
  if (trimOrNull(form.basicAuthUser)) n++
  if (rawOrNull(form.requestBody)) n++
  if (trimOrNull(form.jsonPath)) n++
  if (form.slowResponseEnabled) n++
  return n
}

// ── Doğrulama (sunucu kurallarının aynası) ──────────────────────────────────────────────────

const TOKEN = /^[!#$%&'*+\-.^_`|~0-9A-Za-z]+$/
const RESTRICTED = new Set(['host', 'content-length', 'connection', 'expect', 'upgrade', 'transfer-encoding', 'te',
  'trailer', 'keep-alive', 'proxy-connection', 'http2-settings'])
// eslint-disable-next-line no-control-regex
const CTL = /[\u0000-\u0008\u000a-\u001f\u007f]/
const CONTENT_TYPE = /^[!#$%&'*+\-.^_`|~0-9A-Za-z]+\/[!#$%&'*+\-.^_`|~0-9A-Za-z]+(\s*;.*)?$/

/** Başlık metni → ilk hata `{ key, args }` ya da null. */
export function headerError(raw) {
  const text = String(raw ?? '')
  if (!text.trim()) return null
  const lines = text.split(/\r?\n/)
  let count = 0
  for (let i = 0; i < lines.length; i++) {
    const no = i + 1
    const s = lines[i].trim()
    if (!s || s.startsWith('#')) continue
    const c = s.indexOf(':')
    if (c <= 0) return { key: 'http.adv.errHeaderLine', args: [no] }
    const name = s.slice(0, c).trim()
    const value = s.slice(c + 1).trim()
    if (CTL.test(name) || CTL.test(value)) return { key: 'http.adv.errHeaderCtl', args: [no] }
    if (!TOKEN.test(name)) return { key: 'http.adv.errHeaderName', args: [no] }
    if (RESTRICTED.has(name.toLowerCase())) return { key: 'http.adv.errHeaderRestricted', args: [no, name] }
    if (value.length > ADV_LIMITS.maxHeaderValue) return { key: 'http.adv.errHeaderValue', args: [no, ADV_LIMITS.maxHeaderValue] }
    if (++count > ADV_LIMITS.maxHeaders) return { key: 'http.adv.errHeaderCount', args: [] }
  }
  return null
}

const isNameChar = (ch) => !/[.[\]'"$\s]/.test(ch) && !CTL.test(ch)

/** JSON yolu sözdizimi (sunucudaki JsonAssertion.parse ile aynı dilbilgisi) — geçerliyse true. */
export function isValidJsonPath(path) {
  const s = String(path ?? '').trim()
  if (!s || s.length > ADV_LIMITS.maxJsonPath) return false
  let i = 0
  const n = s.length
  const rooted = s[0] === '$'
  if (rooted) i = 1
  let first = true
  while (i < n) {
    const c = s[i]
    if (c === '.') {
      if (first && !rooted) return false
      i++
      const start = i
      while (i < n && isNameChar(s[i])) i++
      if (i === start) return false
    } else if (c === '[') {
      i++
      if (i >= n) return false
      const q = s[i]
      if (q === '\'' || q === '"') {
        const start = ++i
        while (i < n && s[i] !== q) i++
        if (i >= n || i === start) return false
        i++
        if (i >= n || s[i] !== ']') return false
        i++
      } else {
        const start = i
        while (i < n && s[i] >= '0' && s[i] <= '9') i++
        if (i === start || i - start > 9 || i >= n || s[i] !== ']') return false
        i++
      }
    } else if (first && !rooted && isNameChar(c)) {
      while (i < n && isNameChar(s[i])) i++
    } else {
      return false
    }
    first = false
  }
  return true
}

const utf8Length = (s) => new TextEncoder().encode(s).length

/**
 * Gelişmiş alanların alan-bazlı hataları — `useFormErrors().check` haritasına doğrudan katılır (anahtarlar `data-field`).
 * Boş/kapalı alan hata üretmez (bölüme dokunmayan kullanıcı hiçbir şey görmez).
 */
export function validateHttpAdvanced(form, { method = 'GET', canEditHeaders = false, t } = {}) {
  const tr = (key, ...args) => (t ? t(key, ...args) : key)
  const errs = {}
  if (canEditHeaders) {
    const h = headerError(form.customHeaders)
    if (h) errs.advHeaders = tr(h.key, ...h.args)
  }
  if (String(form.basicAuthUser ?? '').includes(':')) errs.advBasicUser = tr('http.adv.errBasicUser')
  if (method === 'POST' && form.requestBody && utf8Length(String(form.requestBody)) > ADV_LIMITS.maxBodyBytes) {
    errs.advBody = tr('http.adv.errBodyTooLarge')
  }
  const ct = String(form.requestContentType ?? '').trim()
  if (method === 'POST' && ct && (ct.length > ADV_LIMITS.maxContentType || CTL.test(ct) || !CONTENT_TYPE.test(ct))) {
    errs.advContentType = tr('http.adv.errContentType')
  }
  const jp = String(form.jsonPath ?? '').trim()
  if (jp) {
    if (!isValidJsonPath(jp)) errs.advJsonPath = tr('http.adv.errJsonPath')
    else if (method === 'HEAD') errs.advJsonPath = tr('http.adv.errJsonHead')
  }
  if (form.slowResponseEnabled) {
    const v = Number(form.slowThresholdMs)
    if (!Number.isFinite(v) || v < ADV_LIMITS.minSlowMs || v > ADV_LIMITS.maxSlowMs) {
      errs.advSlowThreshold = tr('http.adv.errSlowRange', ADV_LIMITS.minSlowMs, ADV_LIMITS.maxSlowMs)
    }
  }
  return errs
}
