/**
 * Keyword izleme formu — SAF model (kw sözleşmesi §14.1, 2026-11-05): form alanları, liste satırı → form eşlemesi, kayıt
 * ve "Test et" yükleri, istemci tarafı doğrulama (sunucu kurallarının AYNASI — anında geri bildirim; GARANTİ sunucudadır),
 * durum kodu ön ayarları, posta konusu önizlemesi ve sunucu alan hatalarının forma eşlenmesi.
 *
 * <p><b>Yük sözleşmesi.</b> Yeni kayıt (ve Kopyala) TAM yük taşır: bugünkü (2026-11-05 öncesi) anahtarların hepsi + yeni
 * alanlardan yalnız varsayılandan FARKLI olanlar (sunucuda NULL = bugünkü davranış) → bölümlere dokunmayan kullanıcının
 * yükü eskisiyle birebir aynıdır. Düzenleme YALNIZ DEĞİŞEN alanları taşır (B14, "Before a feature counts as done" madde 9):
 * aynı izlemeyi açık tutan başka bir yöneticinin o arada kaydettiği alan (toplu duraklatma, takım taşıma …) sessizce eski
 * değere dönmez. Birlikte anlam taşıyan alanlar ({@link KW_FIELD_GROUPS}) biri değişince birlikte gider. Hiçbir şey
 * değişmediyse yük boştur — sayfa istek atmaz ("değişiklik yok").
 *
 * <p><b>Sırlar write-only.</b> Basic auth parolası ve özel başlıklar formda boş başlar, yalnız YAZILIRSA gönderilir; boş
 * bırakmak "kayıtlıyı koru" demektir. Kaldırma açık bir seçimdir (`clearBasicAuthPass: true`, `customHeaders: null`).
 *
 * <p><b>Bildirim yönlendirmesi sayfada.</b> `notificationGroupId` / `nocNotify` / `nocGroupIds` alanları bu modelde
 * DEĞİL, sayfanın kendi eşleme ve gövde işlevlerindedir (`routingBody` seçeneğiyle buraya verilir): izleme formu
 * bağlantı kapısı (`monitorFormWiring.test.js`) dört halkayı sayfa dosyasında arar ve 7/24 alan modeli (nocFormModel)
 * sayfanın bağımlılığıdır.
 *
 * <p>fe2 alanları (çoklu kural, boyut, değişim — `rules` … `changeSelector`) burada TANIMLI ve yük kurallarına bağlıdır;
 * bileşenleri Aşama 2'de sayfaya bağlanır. `rules` null = eski tek kural (anahtar + operatör + adet + harf).
 */
import { ADV_LIMITS, validateHttpAdvanced } from '../http/httpAdvancedModel.js'
import { normalizeUrl } from '../../utils/normalizeUrl.js'

/** Sunucu tavanları (KeywordRequestRules / HttpRequestRules ile aynı — kw sözleşmesi §3.1, §5). */
export const KW_LIMITS = Object.freeze({
  maxBodyBytes: ADV_LIMITS.maxBodyBytes,
  maxContentType: ADV_LIMITS.maxContentType,
  maxUserAgent: 300,
  maxExpectedStatus: 100,
  maxStatusItems: 20,
  maxFinalUrl: 2000,
  maxSubject: 200,
  minTimeoutMs: 1000,
  maxTimeoutMs: 60000,
  defaultTimeoutMs: 10000,
})

export const MATCH_SCOPES = Object.freeze(['RAW', 'VISIBLE'])
export const KW_METHODS = Object.freeze(['GET', 'POST'])
export const UA_MODES = Object.freeze(['DEFAULT', 'BROWSER', 'CUSTOM'])

/** Sunucunun varsayılan User-Agent'ı (kw sözleşmesi §5.1) — formda yalnız açıklamada gösterilir. */
export const DEFAULT_USER_AGENT = 'SiteMonitor-KeywordMonitor/1.0'

/**
 * Durum kodu ön ayarları (kw kararı 3: kural YENİ alan, varsayılan KAPALI — form yalnız ÖNERİR). Değer sunucuya giden
 * dilbilgisidir (`HttpCheckerService.matchesStatus`); `CUSTOM` serbest metin.
 */
export const STATUS_PRESETS = Object.freeze({ OFF: '', RECOMMENDED: '2xx,3xx', ONLY_200: '200' })
export const STATUS_MODES = Object.freeze(['OFF', 'RECOMMENDED', 'ONLY_200', 'CUSTOM'])

/** Yeni (2026-11-05) fe1 alanlarının bugünkü davranışa karşılık gelen boş hâli. */
export const KW_REQUEST_EMPTY = Object.freeze({
  matchScope: 'RAW', normalizeWs: false,
  method: 'GET', requestBody: '', requestContentType: '',
  basicAuthUser: '', basicAuthPass: '', clearBasicAuthPass: false,
  userAgentMode: 'DEFAULT', userAgent: '',
  expectedStatus: '', expectedFinalUrl: '', followRedirects: true,
  mailSubjectTemplate: '',
  customHeaders: '', clearHeaders: false,
})

/** fe2 alanlarının boş hâli (kural / boyut / değişim) — `rules: null` = eski tek kural. */
export const KW_RULES_EMPTY = Object.freeze({
  rules: null, ruleLogic: 'AND', minBytes: null, maxBytes: null, changeDetect: false, changeMask: Object.freeze([]), changeSelector: '',
})

/**
 * Formun boş hâli — bildirim yönlendirmesi (bildirim grubu + 7/24) hariç BÜTÜN alanlar (sayfa ekler; bkz. dosya başı).
 */
export const KW_EMPTY_FORM = Object.freeze({
  name: '', url: '', keyword: '', operator: 'GTE', matchCount: 1, groupName: '', teamId: '',
  caseSensitive: false, useProxy: 'AUTO', tags: '', notifyEmail: true, alertLevel: 'WARNING', notifyWebhook: true,
  checkSslErrors: false, sslExpiryReminders: false, domainExpiryReminders: false,
  sslReminderDays: '30,14,7', domainReminderDays: '30,14,7',
  slowResponseEnabled: false, slowThresholdMs: 3000,
  intervalSeconds: 60, timeoutMs: KW_LIMITS.defaultTimeoutMs, confirmAttempts: 3, confirmIntervalSeconds: 30,
  recoveryChecks: 3, recoveryIntervalSeconds: 30, active: true,
  ...KW_REQUEST_EMPTY,
  ...KW_RULES_EMPTY,
})

const isNum = (v) => typeof v === 'number' && Number.isFinite(v)
const trimOrNull = (v) => {
  const s = String(v ?? '').trim()
  return s === '' ? null : s
}
const rawOrNull = (v) => (String(v ?? '').trim() === '' ? null : String(v))
const numOrNull = (v) => {
  if (v === '' || v == null) return null
  const n = Number(v)
  return Number.isFinite(n) ? n : null
}

/**
 * Saklı zaman aşımı formda 1–60 sn'ye kırpılır (B13): eski API 120 sn'ye kadar kabul ediyordu ve sunucu çalışmada zaten
 * 60 sn'ye kırpıyor. Taban da aynı kırpmadan geçtiği için düzenlemede bu kırpma tek başına "değişiklik" sayılmaz.
 */
export function clampTimeoutMs(ms) {
  const n = Number(ms)
  if (!Number.isFinite(n)) return KW_LIMITS.defaultTimeoutMs
  return Math.min(KW_LIMITS.maxTimeoutMs, Math.max(KW_LIMITS.minTimeoutMs, n))
}

/** CSV ya da dizi → kırpılmış, boşsuz, tekrarsız dizi (değişim maskesi gibi liste alanları). */
function listOf(v) {
  const parts = Array.isArray(v) ? v : String(v ?? '').split(',')
  const out = []
  for (const p of parts) {
    const s = String(p ?? '').trim()
    if (s && !out.includes(s)) out.push(s)
  }
  return out
}

/**
 * Liste satırı (snake_case, `enrichKeyword`) → form alanları. Düzenle ve Kopyala AYNI eşlemeyi kullanır → alan kaçmaz.
 * Sırlar (parola, başlıklar) ASLA gelmez → boş başlar. Yeni alanları bilmeyen eski sunucunun satırı = bugünkü varsayılanlar.
 */
export function keywordFormFrom(m) {
  const row = m || {}
  return {
    name: row.name || '', url: row.url || '', keyword: row.keyword || '',
    operator: row.operator || 'GTE', matchCount: row.match_count ?? 1, groupName: row.group_name || '',
    teamId: row.team_id != null ? String(row.team_id) : '',
    caseSensitive: !!row.case_sensitive, useProxy: row.use_proxy || 'AUTO', tags: row.tags || '',
    notifyEmail: row.notify_email !== false, alertLevel: row.alert_level || 'WARNING', notifyWebhook: row.notify_webhook !== false,
    checkSslErrors: !!row.check_ssl_errors, sslExpiryReminders: !!row.ssl_expiry_reminders, domainExpiryReminders: !!row.domain_expiry_reminders,
    sslReminderDays: row.ssl_reminder_days || '30,14,7', domainReminderDays: row.domain_reminder_days || '30,14,7',
    slowResponseEnabled: !!row.slow_response_enabled, slowThresholdMs: row.slow_threshold_ms ?? 3000,
    intervalSeconds: row.interval_seconds ?? 60, timeoutMs: clampTimeoutMs(row.timeout_ms ?? KW_LIMITS.defaultTimeoutMs),
    confirmAttempts: row.confirm_attempts ?? 3, confirmIntervalSeconds: row.confirm_interval_seconds ?? 30,
    recoveryChecks: row.recovery_checks ?? 3, recoveryIntervalSeconds: row.recovery_interval_seconds ?? 30,
    active: row.active !== false,
    // ── Eşleşme + gelişmiş istek + posta konusu (kw fe1) ──
    matchScope: row.match_scope === 'VISIBLE' ? 'VISIBLE' : 'RAW',
    normalizeWs: row.normalize_ws === true,
    method: row.method === 'POST' ? 'POST' : 'GET',
    requestBody: row.request_body || '', requestContentType: row.request_content_type || '',
    basicAuthUser: row.basic_auth_user || '', basicAuthPass: '', clearBasicAuthPass: false,
    userAgentMode: UA_MODES.includes(row.user_agent_mode) ? row.user_agent_mode : 'DEFAULT', userAgent: row.user_agent || '',
    expectedStatus: row.expected_status || '', expectedFinalUrl: row.expected_final_url || '',
    followRedirects: row.follow_redirects !== false,
    mailSubjectTemplate: row.mail_subject_template || '',
    customHeaders: '', clearHeaders: false,
    // ── Kurallar / boyut / değişim (kw fe2 — Aşama 2'de bileşenlere bağlanır) ──
    rules: row.rules_custom === true && Array.isArray(row.rules) ? row.rules : null,
    ruleLogic: row.rule_logic === 'OR' ? 'OR' : 'AND',
    minBytes: isNum(row.min_bytes) ? row.min_bytes : null,
    maxBytes: isNum(row.max_bytes) ? row.max_bytes : null,
    changeDetect: row.change_detect === true,
    changeMask: listOf(row.change_mask),
    changeSelector: row.change_selector || '',
  }
}

/** Satırın write-only durumu (sayfa ve bölümler için): kayıtlı parola / başlık var mı, başlık adları (yalnız global admin). */
export function storedSecretsOf(m) {
  if (!m || typeof m !== 'object') return { hasPass: false, hasHeaders: false, headerNames: [] }
  return {
    hasPass: m.has_basic_auth_pass === true,
    hasHeaders: m.has_custom_headers === true,
    headerNames: Array.isArray(m.custom_header_names) ? m.custom_header_names.filter(Boolean) : [],
  }
}

/** Bugünkü (2026-11-05 öncesi) kayıt yükünün anahtarları — yeni kayıtta HEPSİ gider. */
export const KW_LEGACY_KEYS = Object.freeze([
  'name', 'url', 'keyword', 'operator', 'matchCount', 'groupName', 'teamId', 'caseSensitive', 'useProxy', 'tags',
  'notifyEmail', 'alertLevel', 'notifyWebhook', 'checkSslErrors', 'sslExpiryReminders', 'domainExpiryReminders',
  'sslReminderDays', 'domainReminderDays', 'slowResponseEnabled', 'slowThresholdMs', 'intervalSeconds', 'timeoutMs',
  'confirmAttempts', 'confirmIntervalSeconds', 'recoveryChecks', 'recoveryIntervalSeconds', 'active',
])

/** Eski tek kuralın koşul alanları — `rules` gönderilince düzenlemede bunlar GİTMEZ (sunucu kural #1'den türetir, §6.6). */
export const KW_CONDITION_KEYS = Object.freeze(['keyword', 'operator', 'matchCount', 'caseSensitive'])

/**
 * Birlikte anlam taşıyan alan kümeleri — biri değişince HEPSİ gönderilir (düzenlemede yalnız değişen alanlar kuralının
 * istisnası). Sunucu bir kümeyi bütün olarak doğrular / uygular: takım + grup (grup yeni takımda), 7/24 anahtarı + grupları,
 * bildirim kanalları + seviye + grup (paylaşım kilidi eşit değeri düşürür), koşul (mükerrer kuralı takım + URL + kelime),
 * yöntem + gövde + içerik türü, UA kipi + değeri, kapsam + normalleştirme, boyut alt / üst sınırı (min ≤ maks), değişim
 * ayarları (temel sıfırlama tek karar).
 */
export const KW_FIELD_GROUPS = Object.freeze([
  ['teamId', 'groupName'],
  ['nocNotify', 'nocGroupIds'],
  ['notifyEmail', 'notifyWebhook', 'alertLevel', 'notificationGroupId'],
  ['keyword', 'operator', 'matchCount', 'caseSensitive'],
  ['confirmAttempts', 'confirmIntervalSeconds', 'recoveryChecks', 'recoveryIntervalSeconds'],
  ['checkSslErrors', 'sslExpiryReminders', 'sslReminderDays'],
  ['domainExpiryReminders', 'domainReminderDays'],
  ['slowResponseEnabled', 'slowThresholdMs'],
  ['matchScope', 'normalizeWs'],
  ['method', 'requestBody', 'requestContentType'],
  ['userAgentMode', 'userAgent'],
  ['minBytes', 'maxBytes'],
  ['changeDetect', 'changeMask', 'changeSelector'],
])

/**
 * Formun karşılaştırılabilir gövdesi (sırlar ve `rules` / `ruleLogic` HARİÇ — onlar ayrı kurallı). Yeni alanlar sunucuya
 * gideceği biçimde: boş = null (NULL = bugünkü davranış).
 */
export function keywordBody(form) {
  const f = { ...KW_EMPTY_FORM, ...(form || {}) }
  return {
    name: (f.name || f.url || '').trim(), url: normalizeUrl(f.url), keyword: f.keyword,
    operator: f.operator, matchCount: Number(f.matchCount),
    groupName: trimOrNull(f.groupName), teamId: f.teamId === '' || f.teamId == null ? null : Number(f.teamId),
    caseSensitive: !!f.caseSensitive, useProxy: f.useProxy || 'AUTO', tags: trimOrNull(f.tags),
    notifyEmail: !!f.notifyEmail, alertLevel: f.alertLevel || 'WARNING', notifyWebhook: !!f.notifyWebhook,
    checkSslErrors: !!f.checkSslErrors, sslExpiryReminders: !!f.sslExpiryReminders, domainExpiryReminders: !!f.domainExpiryReminders,
    sslReminderDays: trimOrNull(f.sslReminderDays) || '30,14,7', domainReminderDays: trimOrNull(f.domainReminderDays) || '30,14,7',
    slowResponseEnabled: !!f.slowResponseEnabled, slowThresholdMs: Number(f.slowThresholdMs),
    intervalSeconds: Number(f.intervalSeconds), timeoutMs: Number(f.timeoutMs),
    confirmAttempts: Number(f.confirmAttempts), confirmIntervalSeconds: Number(f.confirmIntervalSeconds),
    recoveryChecks: Number(f.recoveryChecks), recoveryIntervalSeconds: Number(f.recoveryIntervalSeconds),
    active: f.active !== false,
    // yeni alanlar (kw fe1)
    matchScope: f.matchScope === 'VISIBLE' ? 'VISIBLE' : 'RAW',
    normalizeWs: !!f.normalizeWs,
    method: f.method === 'POST' ? 'POST' : 'GET',
    requestBody: rawOrNull(f.requestBody),
    requestContentType: trimOrNull(f.requestContentType),
    basicAuthUser: trimOrNull(f.basicAuthUser),
    userAgentMode: UA_MODES.includes(f.userAgentMode) ? f.userAgentMode : 'DEFAULT',
    userAgent: f.userAgentMode === 'CUSTOM' ? trimOrNull(f.userAgent) : null,
    expectedStatus: trimOrNull(f.expectedStatus),
    expectedFinalUrl: trimOrNull(f.expectedFinalUrl),
    followRedirects: f.followRedirects !== false,
    mailSubjectTemplate: trimOrNull(f.mailSubjectTemplate),
    // fe2 alanları (Aşama 2)
    minBytes: numOrNull(f.minBytes), maxBytes: numOrNull(f.maxBytes),
    changeDetect: !!f.changeDetect, changeMask: listOf(f.changeMask), changeSelector: trimOrNull(f.changeSelector),
  }
}

const EMPTY_BODY = keywordBody(KW_EMPTY_FORM)
const same = (a, b) => JSON.stringify(a) === JSON.stringify(b)

/**
 * Kuralların karşılaştırılabilir biçimi: anahtarlar sıralı, null / undefined değerler atılmış (sunucu satırı ile düzenleyicinin
 * ürettiği nesne aynı kuralı farklı anahtar sırasıyla taşıyabilir). Derinlik sınırlı (kural ≤ 3 düzey; güvenlik payı 8).
 */
export function stableRules(rules) {
  if (!Array.isArray(rules)) return null
  const walk = (v, depth) => {
    if (depth > 8) return null
    if (Array.isArray(v)) return v.map((x) => walk(x, depth + 1))
    if (v && typeof v === 'object') {
      const out = {}
      for (const k of Object.keys(v).sort()) {
        const x = v[k]
        if (x === null || x === undefined) continue
        out[k] = walk(x, depth + 1)
      }
      return out
    }
    return v
  }
  return JSON.stringify(walk(rules, 0))
}

/**
 * Kayıt yükü.
 *
 * @param form    güncel form
 * @param base    düzenlemede yüklenen hâlin formu (`keywordFormFrom(satır)` + sayfanın yönlendirme alanları); yeni kayıt /
 *                kopyada yok sayılır
 * @param opts.isNew        oluşturma (Kopyala dahil) mı
 * @param opts.globalAdmin  özel başlıklar yalnız global admin'den gönderilir (sunucu diğerlerininkini zaten yok sayar)
 * @param opts.routingBody  (form) → bildirim yönlendirmesi gövde anahtarları (sayfa verir; bkz. dosya başı)
 * @returns {object}  yük — düzenlemede değişiklik yoksa `{}`
 */
export function keywordPayload(form, base, { isNew = false, globalAdmin = false, routingBody = null } = {}) {
  const full = (f) => ({ ...keywordBody(f), ...(routingBody ? routingBody({ ...KW_EMPTY_FORM, ...(f || {}) }) : {}) })
  const next = full(form)
  const out = {}
  const f = { ...KW_EMPTY_FORM, ...(form || {}) }

  if (isNew || base == null) {
    // Bugünkü anahtarların HEPSİ + yönlendirme; yeni alanlar yalnız varsayılandan farklıysa (NULL = bugünkü davranış)
    for (const [k, v] of Object.entries(next)) {
      if (KW_LEGACY_KEYS.includes(k) || !(k in EMPTY_BODY) || !same(v, EMPTY_BODY[k])) out[k] = v
    }
    if (Array.isArray(f.rules)) { out.rules = f.rules; out.ruleLogic = f.ruleLogic === 'OR' ? 'OR' : 'AND' }
  } else {
    const prev = full(base)
    for (const k of Object.keys(next)) if (!same(next[k], prev[k])) out[k] = next[k]
    for (const g of KW_FIELD_GROUPS) {
      if (g.some((k) => k in out)) for (const k of g) if (k in next) out[k] = next[k]
    }
    // Kurallar: normalize karşılaştırma. Özel kural listesi değiştiyse liste + mantık gider, eski koşul alanları GİTMEZ
    // (sunucu kural #1'den türetir); özelden basit tek kurala dönüşte `rules: null` + eski koşul alanlarının hepsi.
    const nextRules = Array.isArray(f.rules) ? f.rules : null
    const prevRules = Array.isArray(base.rules) ? base.rules : null
    const logic = (x) => (x === 'OR' ? 'OR' : 'AND')
    const rulesChanged = stableRules(nextRules) !== stableRules(prevRules)
    if (nextRules && (rulesChanged || logic(f.ruleLogic) !== logic(base.ruleLogic))) {
      out.rules = nextRules
      out.ruleLogic = logic(f.ruleLogic)
      for (const k of KW_CONDITION_KEYS) delete out[k]
    } else if (!nextRules && prevRules) {
      out.rules = null
      for (const k of KW_CONDITION_KEYS) out[k] = next[k]
    }
  }

  // Sırlar (write-only): yalnız yazıldıysa / açıkça kaldırıldıysa
  if (f.clearBasicAuthPass && !isNew) out.clearBasicAuthPass = true
  else if (f.basicAuthPass) out.basicAuthPass = f.basicAuthPass
  if (globalAdmin) {
    const h = String(f.customHeaders || '').trim()
    if (h) out.customHeaders = h
    else if (f.clearHeaders && !isNew) out.customHeaders = null
  }
  return out
}

/**
 * Kayıt yükünde kontrolün SONUCUNU etkileyen (satırda geri okunamayan ya da `utils/checkAfterSave` listesinde olmayan)
 * bir alan var mı — varsa kayıttan sonra taze kontrol koşulur (kart eski kuralın sonucunu göstermesin).
 */
export const KW_CHECK_AFFECTING_KEYS = Object.freeze([
  'customHeaders', 'basicAuthPass', 'clearBasicAuthPass', 'basicAuthUser', 'matchScope', 'normalizeWs', 'method',
  'requestBody', 'requestContentType', 'userAgentMode', 'userAgent', 'expectedStatus', 'expectedFinalUrl',
  'followRedirects', 'rules', 'ruleLogic', 'minBytes', 'maxBytes', 'changeDetect', 'changeMask', 'changeSelector',
])
export function affectsCheck(payload) {
  return !!payload && KW_CHECK_AFFECTING_KEYS.some((k) => k in payload)
}

/**
 * "Test et" yükü (POST /monitoring/keyword/test, sözleşme §10.2). Eski anahtarlar bugünkü biçimde (özel başlık yalnız global
 * admin'de; diğerinde null — sunucu yok sayar); yeni alanlar yalnız kullanılıyorsa. Düzenlenen kayıtlı izlemede (monitorId)
 * kutuya yazılmamış bir sır KAYITLI değeriyle kullanılsın diye `useSaved` istenir — sunucu düzenleme yetkisini denetler.
 */
export function keywordTestPayload(form, { globalAdmin = false, monitorId = null, stored = null } = {}) {
  const f = { ...KW_EMPTY_FORM, ...(form || {}) }
  const out = {
    url: normalizeUrl(f.url), keyword: f.keyword, operator: f.operator,
    matchCount: Number(f.matchCount), timeoutMs: Number(f.timeoutMs),
    customHeaders: globalAdmin ? (String(f.customHeaders || '').trim() || null) : null,
    caseSensitive: !!f.caseSensitive, useProxy: f.useProxy || 'AUTO',
  }
  if (f.matchScope === 'VISIBLE') out.matchScope = 'VISIBLE'
  if (f.normalizeWs) out.normalizeWs = true
  if (f.method === 'POST') {
    out.method = 'POST'
    if (rawOrNull(f.requestBody)) out.requestBody = String(f.requestBody)
    if (trimOrNull(f.requestContentType)) out.requestContentType = trimOrNull(f.requestContentType)
  }
  const user = trimOrNull(f.basicAuthUser)
  if (user) out.basicAuthUser = user
  if (user && f.basicAuthPass && !f.clearBasicAuthPass) out.basicAuthPass = f.basicAuthPass
  if (f.userAgentMode === 'BROWSER') out.userAgentMode = 'BROWSER'
  if (f.userAgentMode === 'CUSTOM' && trimOrNull(f.userAgent)) { out.userAgentMode = 'CUSTOM'; out.userAgent = trimOrNull(f.userAgent) }
  if (trimOrNull(f.expectedStatus)) out.expectedStatus = trimOrNull(f.expectedStatus)
  if (trimOrNull(f.expectedFinalUrl)) out.expectedFinalUrl = trimOrNull(f.expectedFinalUrl)
  if (f.followRedirects === false) out.followRedirects = false
  if (Array.isArray(f.rules)) { out.rules = f.rules; out.ruleLogic = f.ruleLogic === 'OR' ? 'OR' : 'AND' }
  if (numOrNull(f.minBytes) != null) out.minBytes = numOrNull(f.minBytes)
  if (numOrNull(f.maxBytes) != null) out.maxBytes = numOrNull(f.maxBytes)
  if (monitorId != null) {
    out.monitorId = monitorId
    const useSaved = []
    if (stored?.hasPass && user && !f.basicAuthPass && !f.clearBasicAuthPass) useSaved.push('basicAuthPass')
    if (globalAdmin && stored?.hasHeaders && !String(f.customHeaders || '').trim() && !f.clearHeaders) useSaved.push('customHeaders')
    if (useSaved.length) out.useSaved = useSaved
  }
  return out
}

/** Kaç gelişmiş istek ayarı AÇIK (bölüm başlığındaki rozet) — kayıtlı sırlar da sayılır. */
export function requestActiveCount(form, stored = {}) {
  const f = { ...KW_EMPTY_FORM, ...(form || {}) }
  let n = 0
  if (f.method === 'POST') n++
  if (String(f.customHeaders || '').trim() || (stored.hasHeaders && !f.clearHeaders)) n++
  if (trimOrNull(f.basicAuthUser)) n++
  if (f.userAgentMode && f.userAgentMode !== 'DEFAULT') n++
  if (trimOrNull(f.expectedStatus)) n++
  if (trimOrNull(f.expectedFinalUrl)) n++
  if (f.followRedirects === false) n++
  return n
}

/** Kaç eşleşme ayarı varsayılandan farklı (bölüm başlığındaki rozet). */
export function matchActiveCount(form) {
  const f = { ...KW_EMPTY_FORM, ...(form || {}) }
  let n = 0
  if (f.matchScope === 'VISIBLE') n++
  else if (f.normalizeWs) n++
  return n
}

// ── Durum kodu kuralı ──────────────────────────────────────────────────────────────────────

/** Saklı / yazılı değer → ön ayar kipi (boşluk ve harf farkı yok sayılır; tanınmayan her değer `CUSTOM`). */
export function statusModeOf(value) {
  const v = String(value ?? '').replace(/\s+/g, '').toLowerCase()
  if (!v) return 'OFF'
  if (v === STATUS_PRESETS.RECOMMENDED || v === '200-399') return 'RECOMMENDED'
  if (v === STATUS_PRESETS.ONLY_200) return 'ONLY_200'
  return 'CUSTOM'
}

const CLASS_TOKEN = /^[1-5]xx$/i
const CODE_TOKEN = /^\d{3}$/
const inRange = (n) => n >= 100 && n <= 599

/**
 * Durum kodu dilbilgisinin aynası (kw sözleşmesi §5.2): virgülle ayrılmış ≤ 20 öğe; her öğe `2xx` (1–5 sınıfı), `200` ya da
 * `200-299` (100–599; aralığın iki yanında boşluk serbest), toplam ≤ 100 karakter. Boş = kural kapalı (hata yok).
 *
 * @returns {{key: string, args: Array}|null} ilk hata ya da null
 */
export function statusPatternError(value) {
  const raw = String(value ?? '')
  const s = raw.trim()
  if (!s) return null
  if (s.length > KW_LIMITS.maxExpectedStatus) return { key: 'keyword.req.err.statusTooLong', args: [KW_LIMITS.maxExpectedStatus] }
  const items = s.split(',')
  if (items.length > KW_LIMITS.maxStatusItems) return { key: 'keyword.req.err.statusTooMany', args: [KW_LIMITS.maxStatusItems] }
  for (const item of items) {
    const tok = item.trim()
    if (!tok) return { key: 'keyword.req.err.statusEmptyItem', args: [] }
    if (CLASS_TOKEN.test(tok)) continue
    if (CODE_TOKEN.test(tok) && inRange(Number(tok))) continue
    const dash = tok.indexOf('-')
    if (dash > 0) {
      const lo = tok.slice(0, dash).trim()
      const hi = tok.slice(dash + 1).trim()
      if (CODE_TOKEN.test(lo) && CODE_TOKEN.test(hi) && inRange(Number(lo)) && inRange(Number(hi))) continue
    }
    return { key: 'keyword.req.err.statusFormat', args: [tok.slice(0, 30)] }
  }
  return null
}

// eslint-disable-next-line no-control-regex
const CTL = /[\u0000-\u001f\u007f]/
const PRINTABLE_ASCII = /^[\x20-\x7e]*$/

/** Son URL kuralı aynası (§5.3): mutlak http(s) adresi ya da `/` ile başlayan yol; ≤ 2000; satır sonu / kontrol yok. */
export function finalUrlError(value) {
  const s = String(value ?? '').trim()
  if (!s) return null
  if (s.length > KW_LIMITS.maxFinalUrl) return { key: 'keyword.req.err.finalUrlTooLong', args: [KW_LIMITS.maxFinalUrl] }
  if (CTL.test(s) || /\s/.test(s)) return { key: 'keyword.req.err.finalUrl', args: [] }
  if (s.startsWith('/')) return null
  try {
    const u = new URL(s)
    if ((u.protocol === 'http:' || u.protocol === 'https:') && u.hostname) return null
  } catch { /* geçersiz */ }
  return { key: 'keyword.req.err.finalUrl', args: [] }
}

/** Özel User-Agent aynası: zorunlu, ≤ 300, yalnız yazdırılabilir ASCII (satır sonu / kontrol yok). */
export function userAgentError(mode, value) {
  if (mode !== 'CUSTOM') return null
  const s = String(value ?? '').trim()
  if (!s) return { key: 'keyword.req.err.uaRequired', args: [] }
  if (s.length > KW_LIMITS.maxUserAgent) return { key: 'keyword.req.err.uaTooLong', args: [KW_LIMITS.maxUserAgent] }
  if (!PRINTABLE_ASCII.test(s)) return { key: 'keyword.req.err.uaInvalid', args: [] }
  return null
}

/** Posta konusu aynası (E3): ≤ 200, satır sonu / kontrol karakteri yok. Boş = standart konu. */
export function subjectError(value) {
  const s = String(value ?? '')
  if (!s.trim()) return null
  if (s.length > KW_LIMITS.maxSubject) return { key: 'keyword.subject.err.tooLong', args: [KW_LIMITS.maxSubject] }
  if (CTL.test(s)) return { key: 'keyword.subject.err.control', args: [] }
  return null
}

/**
 * Yeni (fe1) alanların alan-bazlı hataları — `useFormErrors().check` haritasına katılır. Anahtarlar sunucunun alan adları
 * (istek anahtarları) — sunucu hatası da aynı alanın altına düşer. Boş / kapalı alan hata üretmez.
 */
export function validateKeywordForm(form, { globalAdmin = false, t } = {}) {
  const f = { ...KW_EMPTY_FORM, ...(form || {}) }
  const tr = (key, ...args) => (t ? t(key, ...args) : key)
  const pick = (e) => (e ? tr(e.key, ...e.args) : undefined)
  const errs = {
    ...validateHttpAdvanced(f, {
      method: f.method, canEditHeaders: globalAdmin, t,
      keys: { headers: 'customHeaders', basicUser: 'basicAuthUser', body: 'requestBody', contentType: 'requestContentType' },
      parts: { json: false, slow: false },
    }),
  }
  const to = Number(f.timeoutMs)
  if (!Number.isFinite(to) || to < KW_LIMITS.minTimeoutMs || to > KW_LIMITS.maxTimeoutMs) errs.timeoutMs = tr('keyword.req.err.timeout')
  const ua = pick(userAgentError(f.userAgentMode, f.userAgent))
  if (ua) errs.userAgent = ua
  const st = pick(statusPatternError(f.expectedStatus))
  if (st) errs.expectedStatus = st
  const fu = pick(finalUrlError(f.expectedFinalUrl))
  if (fu) errs.expectedFinalUrl = fu
  const sj = pick(subjectError(f.mailSubjectTemplate))
  if (sj) errs.mailSubjectTemplate = sj
  return errs
}

/** Alan anahtarı → formun açılır bölümü (hata varken bölüm açılır; kapalıyken içeriği DOM'da değildir). */
export const KW_SECTION_OF = Object.freeze({
  method: 'request', requestBody: 'request', requestContentType: 'request', basicAuthUser: 'request',
  basicAuthPass: 'request', clearBasicAuthPass: 'request', customHeaders: 'request', userAgentMode: 'request',
  userAgent: 'request', expectedStatus: 'request', expectedFinalUrl: 'request', followRedirects: 'request',
  matchScope: 'match', normalizeWs: 'match',
  timeoutMs: 'advanced', slowResponseEnabled: 'advanced', slowThresholdMs: 'advanced', confirmAttempts: 'advanced',
  confirmIntervalSeconds: 'advanced', recoveryChecks: 'advanced', recoveryIntervalSeconds: 'advanced', active: 'advanced',
})

/** Hata haritasındaki alanların açılması gereken bölümleri. */
export function sectionsForErrors(errs) {
  const out = new Set()
  for (const [k, v] of Object.entries(errs || {})) if (v && KW_SECTION_OF[k]) out.add(KW_SECTION_OF[k])
  return out
}

/** Formun çizdiği alan anahtarları (`data-field`) — sunucu hatası bunlardan birine düşmüyorsa sayfa tost da gösterir. */
export const KW_FORM_FIELD_KEYS = Object.freeze([
  'url', 'keyword', 'teamId', 'groupName', 'tags', 'requestBody', 'requestContentType', 'basicAuthUser', 'basicAuthPass',
  'customHeaders', 'userAgent', 'expectedStatus', 'expectedFinalUrl', 'mailSubjectTemplate', 'timeoutMs',
])

/**
 * Sunucu doğrulama yanıtı (400 `VALIDATION_FAILED` + `field` / `fields`) → `{ alan: ileti }`. Alan adı istek anahtarıdır
 * (sözleşme §10) — form anahtarlarıyla AYNI. Doğrulama yanıtı değilse boş nesne.
 */
export function serverFieldErrors(res) {
  if (!res || res.success !== false) return {}
  const out = {}
  if (res.fields && typeof res.fields === 'object' && !Array.isArray(res.fields)) {
    for (const [k, v] of Object.entries(res.fields)) {
      if (k) out[k] = typeof v === 'string' && v.trim() ? v : (res.error || '')
    }
  }
  if (typeof res.field === 'string' && res.field && !out[res.field]) out[res.field] = res.error || ''
  return Object.fromEntries(Object.entries(out).filter(([, v]) => !!v))
}

/** Hata haritasında formun gösterebildiği (alanın altına düşen) bir anahtar var mı — `rules[i].x` fe2 düzenleyicisinindir. */
export function hasRenderableField(errs) {
  return Object.keys(errs || {}).some((k) => KW_FORM_FIELD_KEYS.includes(k) || k.startsWith('rules['))
}

// ── Posta konusu (E3) ──────────────────────────────────────────────────────────────────────

/**
 * Yer tutucular — TR ve EN eşi AYNI değer (sözleşme §8.3). `key` önizleme değerinin adı ve `keyword.subject.ph.<key>`
 * açıklamasıdır. Sıra çip sırasıdır.
 */
export const SUBJECT_PLACEHOLDERS = Object.freeze([
  { key: 'name', tr: 'ad', en: 'name' },
  { key: 'url', tr: 'url', en: 'url' },
  { key: 'keyword', tr: 'kelime', en: 'keyword' },
  { key: 'count', tr: 'adet', en: 'count' },
  { key: 'reason', tr: 'neden', en: 'reason' },
  { key: 'level', tr: 'seviye', en: 'level' },
  { key: 'status', tr: 'durum', en: 'status' },
  { key: 'time', tr: 'zaman', en: 'time' },
])
const ALIAS = new Map(SUBJECT_PLACEHOLDERS.flatMap((p) => [[p.tr, p.key], [p.en, p.key]]))
/** Yer tutucu adının en uzun hâli — sunucuyla aynı (`DbQueryMailSubject`/`SubjectTemplate`: kapanış en çok 13 karakter ötede). */
const MAX_PLACEHOLDER = 12

/**
 * Konu şablonunu DOĞRUSAL tarar (düzenli ifade yok; sunucunun döngüsünün aynası): `{ad}` gibi parçalar — ad kırpılıp küçük
 * harfe çevrilerek eşlenir; bilinmeyen yer tutucu olduğu gibi kalır. Girdi en çok 4 × 200 karakter işlenir (sunucuyla aynı).
 * @returns {Array<{text: string, key: string|null, name: string|null}>} parçalar (yer tutucu olmayan metin `name: null`)
 */
export function subjectParts(template) {
  const s = String(template ?? '').slice(0, KW_LIMITS.maxSubject * 4)
  const out = []
  let buf = ''
  let i = 0
  while (i < s.length) {
    const c = s[i]
    if (c === '{') {
      const close = s.indexOf('}', i + 1)
      if (close > i + 1 && close - i <= MAX_PLACEHOLDER + 1) {
        const name = s.slice(i + 1, close)
        const key = ALIAS.get(name.trim().toLowerCase()) || null
        // Bilinen yer tutucu ya da içinde süslü parantez olmayan bilinmeyen ad (uyarı için ayrı parça; metni aynen kalır)
        if (key || !name.includes('{')) {
          if (buf) { out.push({ text: buf, key: null, name: null }); buf = '' }
          out.push({ text: s.slice(i, close + 1), key, name })
          i = close + 1
          continue
        }
      }
    }
    buf += c
    i++
  }
  if (buf) out.push({ text: buf, key: null, name: null })
  return out
}

/** Tanınmayan yer tutucular (tekrarsız, yazıldığı biçimde). */
export function unknownPlaceholders(template) {
  const out = []
  for (const p of subjectParts(template)) if (p.name != null && !p.key && !out.includes(p.text)) out.push(p.text)
  return out
}

/**
 * Önizleme (sunucunun çıktısının aynası): bilinen yer tutucular `values[key]` ile doldurulur, bilinmeyenler olduğu gibi kalır;
 * sonuç tek satır (satır sonu / sekme / kontrol karakteri boşluk, ardışık boşluk tek), 200 karakteri aşarsa 199 + "…".
 * Boş sonuç '' (sunucu o durumda standart konuya döner).
 */
export function renderSubject(template, values = {}) {
  let out = ''
  for (const p of subjectParts(template)) out += p.key ? String(values[p.key] ?? '') : p.text
  let flat = ''
  let space = false
  for (const ch of out) {
    if (CTL.test(ch) || /\s/.test(ch)) {
      if (!space && flat.length > 0) flat += ' '
      space = true
    } else {
      flat += ch
      space = false
    }
  }
  flat = flat.replace(/ +$/, '')
  return flat.length > KW_LIMITS.maxSubject ? `${flat.slice(0, KW_LIMITS.maxSubject - 1)}…` : flat
}

/** Yer tutucu metni (arayüz dilinde): TR arayüzde `{ad}`, EN'de `{name}`. */
export function placeholderText(p, lang) {
  return `{${lang === 'en' ? p.en : p.tr}}`
}
