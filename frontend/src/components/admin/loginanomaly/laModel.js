import { isValidEmail, parseEmailInput } from '../../noc/nocModel.js'
import { eventDate } from '../audit/auditFormat.js'

/**
 * Ayarlar → Başarısız Login Anomali Uyarısı — SAF model (React yok): alan tanımları, form ↔ tel biçimi,
 * doğrulama, alanlar-arası uyarılar, kirli alan sayımı, olay istatistikleri, süre biçimi.
 *
 * <p><b>Tel biçimi</b> (`LoginAnomalyController`, snake_case): `GET/PUT /admin/login-anomaly/settings` sayılar +
 * `enabled` / `resolved_email_enabled` + `alert_recipients` (VİRGÜLLÜ tek dize) + salt-okunur `system_admin_email`.
 * Olaylar (`/incidents`) `LoginAnomalyIncident` varlığı: `opened_at`, `resolved`, `resolved_at`, `peak_total`,
 * `rules_signature` (kural kodlarının CSV'si), `realert_count`, `last_alert_at`… — IP / hesap TAŞIMAZ.
 *
 * <p>Sınırlar sunucudakilerle AYNI (`saveSettings` → `req(v, min, max)`); arayüz anında geri bildirim verir, garanti
 * sunucudadır. Sayısal alanlar düzenlenirken DİZE tutulur (denetimli `type=number` kutusu "2." yazarken caret'i
 * zıplatmasın); kayıtta sayıya çevrilir.
 */

/** Sayısal alanlar — sunucu sınırları, birim, tetiklediği kural ve yardım anahtarı (settings-help-coverage). */
export const FIELDS = {
  threshold_per_account:              { min: 1, max: 100000, step: 1, int: true, unit: 'attempts', rule: 'ACCOUNT_TARGETED', help: 'help.set.site.monitor.failed-login.threshold-per-account' },
  threshold_per_ip:                   { min: 1, max: 100000, step: 1, int: true, unit: 'attempts', rule: 'IP_BRUTE_FORCE', help: 'help.set.site.monitor.failed-login.threshold-per-ip' },
  threshold_total:                    { min: 1, max: 100000, step: 1, int: true, unit: 'attempts', rule: 'GLOBAL_VOLUME', help: 'help.set.site.monitor.failed-login.threshold-total' },
  threshold_distinct_users_per_ip:    { min: 1, max: 100000, step: 1, int: true, unit: 'accounts', rule: 'IP_CREDENTIAL_STUFFING', help: 'help.set.site.monitor.failed-login.threshold-distinct-users-per-ip' },
  threshold_distinct_ips_per_account: { min: 1, max: 100000, step: 1, int: true, unit: 'ips', rule: 'DISTRIBUTED', help: 'help.set.site.monitor.failed-login.threshold-distinct-ips-per-account' },
  relative_multiplier:                { min: 1, max: 100, step: 0.5, int: false, unit: 'times', rule: 'RELATIVE_SPIKE', help: 'help.set.site.monitor.failed-login.relative-multiplier' },
  baseline_hours:                     { min: 1, max: 168, step: 1, int: true, unit: 'hours', help: 'help.set.site.monitor.failed-login.baseline-hours' },
  relative_floor:                     { min: 0, max: 100000, step: 1, int: true, unit: 'attempts', help: 'help.set.site.monitor.failed-login.relative-floor' },
  window_minutes:                     { min: 1, max: 120, step: 1, int: true, unit: 'minutes', help: 'help.set.site.monitor.failed-login.window-minutes' },
  cooldown_minutes:                   { min: 1, max: 10080, step: 1, int: true, unit: 'minutes', help: 'help.set.site.monitor.failed-login.cooldown-minutes' },
  catchup_cap_minutes:                { min: 1, max: 1440, step: 1, int: true, unit: 'minutes', help: 'help.set.site.monitor.failed-login.catchup-cap-minutes' },
  retention_days:                     { min: 7, max: 3650, step: 1, int: true, unit: 'days', help: 'help.set.site.monitor.failed-login.retention-days' },
}
export const NUM_KEYS = Object.keys(FIELDS)

/** Kural aileleri — her biri tek kart. */
export const ABSOLUTE_KEYS = ['threshold_per_account', 'threshold_per_ip', 'threshold_total', 'threshold_distinct_users_per_ip', 'threshold_distinct_ips_per_account']
export const RELATIVE_KEYS = ['relative_multiplier', 'baseline_hours', 'relative_floor']
export const TIMING_KEYS = ['window_minutes', 'cooldown_minutes', 'catchup_cap_minutes']

/** Detektörün kural kodları (`FailedLoginAnomalyService` R1–R6), olay rozetlerinde bu sırayla. */
export const RULE_CODES = ['GLOBAL_VOLUME', 'ACCOUNT_TARGETED', 'IP_BRUTE_FORCE', 'IP_CREDENTIAL_STUFFING', 'DISTRIBUTED', 'RELATIVE_SPIKE']

/** Alıcı listesi tavanı — çip girişinin sayacı (`noc/EmailChipsInput`) bu değeri gösterir. */
export const MAX_RECIPIENTS = 50

/** Sunucu yanıtı → düzenlenebilir form. Alıcı CSV'si çiplere ayrılır (geçersizler kırmızı çip olarak kalır). */
export function toForm(data) {
  const d = data || {}
  const f = {
    enabled: d.enabled !== false,
    resolved_email_enabled: d.resolved_email_enabled !== false,
    system_admin_email: String(d.system_admin_email ?? '').trim(),
  }
  for (const k of NUM_KEYS) f[k] = d[k] == null ? '' : String(d[k])
  const r = parseEmailInput(String(d.alert_recipients ?? ''), [])
  f.recipients = { emails: r.added, invalid: r.invalid }
  return f
}

/**
 * Form → PUT gövdesi (sunucunun beklediği biçim: sayılar, boolean'lar, alıcılar VİRGÜLLÜ dize).
 * `retentionReadOnly` (kapsamlı müdür): saklama HER ZAMAN yüklenen değerle gider — sunucu değişmeyen GLOBAL_ONLY
 * değeri yok sayar, değişeni 403'ler; kilitli alan zaten değişemez, bu yalnız emniyet kemeri.
 */
export function toPayload(form, loaded, { retentionReadOnly = false } = {}) {
  const out = { enabled: !!form.enabled, resolved_email_enabled: !!form.resolved_email_enabled }
  for (const k of NUM_KEYS) out[k] = Number(String(form[k]).trim())
  if (retentionReadOnly && loaded) out.retention_days = Number(loaded.retention_days)
  out.alert_recipients = (form.recipients?.emails || []).join(',')
  return out
}

/** Tek alanın doğrulaması → `null` (geçerli) ya da `{ code, arg }`; kodlar sayfada i18n'e eşlenir. */
export function validateField(key, raw) {
  const def = FIELDS[key]
  if (!def) return null
  const s = String(raw ?? '').trim()
  if (s === '') return { code: 'required' }
  const n = Number(s)
  if (!Number.isFinite(n)) return { code: 'number' }
  if (def.int && !Number.isInteger(n)) return { code: 'integer' }
  if (n < def.min) return { code: 'min', arg: def.min }
  if (n > def.max) return { code: 'max', arg: def.max }
  return null
}

/** Formun tüm hataları: `{ alan: { code, arg } }` — boş nesne = kaydedilebilir. */
export function validateForm(form, { retentionReadOnly = false } = {}) {
  const errors = {}
  if (!form) return errors
  for (const k of NUM_KEYS) {
    if (k === 'retention_days' && retentionReadOnly) continue
    const e = validateField(k, form[k])
    if (e) errors[k] = e
  }
  const r = form.recipients || { emails: [], invalid: [] }
  if (r.invalid.length) errors.recipients = { code: 'recipientsInvalid' }
  else if (r.emails.length > MAX_RECIPIENTS) errors.recipients = { code: 'recipientsTooMany', arg: MAX_RECIPIENTS }
  return errors
}

const num = (v) => {
  const s = String(v ?? '').trim()
  if (s === '') return NaN
  return Number(s)
}
/** Yalnız GEÇERLİ alan değeri (uyarılar geçersiz bir değeri — ör. pencere 500 — metne taşımasın); değilse NaN. */
const validNum = (form, key) => (validateField(key, form[key]) ? NaN : num(form[key]))

/**
 * Alanlar-arası UYARILAR (kaydı engellemez) — sunucu kabul eder ama etkisi beklenmedik olan yapılandırmalar:
 *  • catch-up sınırı < pencere: `computeWindow` başlangıcı sınıra çeker → her tarama pencerenin yalnız son kısmını sayar;
 *  • karşılaştırma dönemi < pencere: pencere başına ortalama tek kovaya düşer, anlamlı değil;
 *  • hesap/IP eşiği > toplam eşiği: toplam kuralı her zaman önce tetiklenir, bu kural fiilen hiç çalışmaz;
 *  • zemin 0: görece kural sessiz saatlerde gürültü üretir.
 */
export function fieldWarnings(form) {
  const w = {}
  if (!form) return w
  const win = validNum(form, 'window_minutes')
  const cap = validNum(form, 'catchup_cap_minutes')
  const base = validNum(form, 'baseline_hours')
  const total = validNum(form, 'threshold_total')
  if (Number.isFinite(win) && Number.isFinite(cap) && cap < win) w.catchup_cap_minutes = { code: 'catchupBelowWindow', args: [win, cap] }
  if (Number.isFinite(win) && Number.isFinite(base) && base * 60 < win) w.baseline_hours = { code: 'baselineBelowWindow', args: [] }
  for (const k of ['threshold_per_account', 'threshold_per_ip']) {
    const v = validNum(form, k)
    if (Number.isFinite(v) && Number.isFinite(total) && v > total) w[k] = { code: 'aboveTotal', args: [total] }
  }
  if (validNum(form, 'relative_floor') === 0) w.relative_floor = { code: 'floorZero', args: [] }
  return w
}

const recipientsKey = (r) => `${(r?.emails || []).join(',')}|${(r?.invalid || []).join(',')}`

/** Kaydedilmemiş alanlar (sayısal alanlarda "10" ile "10.0" aynı sayılır). */
export function changedKeys(form, loaded) {
  if (!form || !loaded) return []
  const out = []
  for (const k of ['enabled', 'resolved_email_enabled']) if (!!form[k] !== !!loaded[k]) out.push(k)
  for (const k of NUM_KEYS) {
    const a = String(form[k] ?? '').trim()
    const b = String(loaded[k] ?? '').trim()
    const na = Number(a)
    const nb = Number(b)
    const same = a !== '' && b !== '' && Number.isFinite(na) && Number.isFinite(nb) ? na === nb : a === b
    if (!same) out.push(k)
  }
  if (recipientsKey(form.recipients) !== recipientsKey(loaded.recipients)) out.push('recipients')
  return out
}

/** Saklama KISALIYOR mu (daha eski çözülmüş olaylar bir sonraki temizlikte silinir)? */
export function retentionShrinks(form, loaded) {
  const a = num(form?.retention_days)
  const b = num(loaded?.retention_days)
  return Number.isFinite(a) && Number.isFinite(b) && a < b && !validateField('retention_days', form.retention_days)
}

/** Uyarıların gerçekten gideceği adresler: liste boşsa sistem yöneticisi adresi (`resolveRecipients` ile aynı kural). */
export function effectiveRecipients(form) {
  const list = form?.recipients?.emails || []
  if (list.length) return { list, fallback: false }
  const admin = String(form?.system_admin_email ?? '').trim()
  return { list: admin ? [admin] : [], fallback: !!admin }
}

/** Test e-postası için varsayılan alıcı — ilk etkin alıcı. */
export function defaultTestRecipient(form) {
  const first = effectiveRecipients(form).list[0] || ''
  return isValidEmail(first) ? first : ''
}

/** "GLOBAL_VOLUME,IP_BRUTE_FORCE" → bilinen sırayla kodlar (bilinmeyenler sonda, olduğu gibi). */
export function parseSignature(sig) {
  const codes = String(sig ?? '').split(',').map((s) => s.trim()).filter(Boolean)
  const uniq = [...new Set(codes)]
  const known = RULE_CODES.filter((c) => uniq.includes(c))
  return [...known, ...uniq.filter((c) => !RULE_CODES.includes(c))]
}

const DAY = 86_400_000

/**
 * Başlık durum satırının sayıları — yüklenen SON olaylardan (en yeni üstte). Son 30 gün sayımı liste sayfasını
 * doldurduysa ("hepsi 30 günün içinde ve sunucuda daha fazlası var") `saturated` → "10+" gösterilir, eksik sayı
 * kesin sayı gibi sunulmaz.
 */
export function incidentStats(items, total, now = Date.now()) {
  const list = Array.isArray(items) ? items : []
  const cutoff = now - 30 * DAY
  let last30 = 0
  for (const it of list) {
    const d = eventDate(it?.opened_at)
    if (d && d.getTime() >= cutoff) last30++
  }
  const saturated = last30 === list.length && list.length > 0 && Number(total) > list.length
  return {
    last30,
    saturated,
    last: list[0] || null,
    open: list.some((it) => it && !it.resolved),
  }
}

/** Dakika → "45 dk" / "2 sa" / "1 sa 30 dk" / "7 gün" (anahtarlar çağırandan — dil bağımsız). */
export function fmtMinutes(total, t) {
  const m = Math.max(0, Math.round(Number(total) || 0))
  if (m < 60) return t('loginAnomaly.dur.min', m)
  if (m % 1440 === 0) return t('loginAnomaly.dur.day', m / 1440)
  const h = Math.floor(m / 60)
  const rest = m % 60
  if (h >= 24) {
    const d = Math.floor(h / 24)
    const hh = h % 24
    return hh ? `${t('loginAnomaly.dur.day', d)} ${t('loginAnomaly.dur.hour', hh)}` : t('loginAnomaly.dur.day', d)
  }
  return rest ? `${t('loginAnomaly.dur.hour', h)} ${t('loginAnomaly.dur.min', rest)}` : t('loginAnomaly.dur.hour', h)
}

/** Olay süresi (dakika) — açık olayda şimdiye kadar; bozuk damga `null`. */
export function incidentMinutes(it, now = Date.now()) {
  const a = eventDate(it?.opened_at)
  if (!a) return null
  const b = it?.resolved ? eventDate(it?.resolved_at) : null
  const end = b ? b.getTime() : now
  return Math.max(0, Math.round((end - a.getTime()) / 60000))
}

/** Olayın başarısız girişlerini Denetim Logu'nda açan süzgeç (`a_*`, `auditFilters.readUrlFilters`). */
export function auditParams(it, windowMinutes) {
  const params = { a_eventType: 'LOGIN_FAILED' }
  const opened = eventDate(it?.opened_at)
  if (!opened) return params
  const win = Math.max(1, Number(windowMinutes) || 10)
  params.a_since = new Date(opened.getTime() - win * 60000).toISOString().slice(0, 19)
  const end = it?.resolved ? eventDate(it?.resolved_at) : null
  if (end) params.a_until = end.toISOString().slice(0, 19)
  return params
}
