// Veritabanı Sorgu İzleme — "Hızlı ekle" sihirbazı (B1, 2026-11-05) SAF modeli: adımlar, adım doğrulaması, sunucu alan
// hatalarının adımlara dağıtımı, önerilen ad. Form biçimi sayfanınkiyle AYNI (DbQueryMonitorPage `emptyForm`); istek gövdesi
// sayfanın `payloadFromForm`'u ile kurulur (sihirbaz aynı create ucunu kullanır — ayrı sözleşme yok).

import { validateRules } from '../ruleModel.js'
import { MAX_SQL_CHARS } from '../sqlEditorModel.js'
import { SCHEDULE_FIELD_KEYS } from '../dbqueryPageModel.js'

/** Adım sırası — gösterge ve ilerleme bu sırayla. */
export const WIZ_STEPS = Object.freeze(['connection', 'query', 'test', 'rules', 'schedule', 'summary'])

export const PURPOSE_MIN = 5
export const PURPOSE_MAX = 2000
export const NAME_MAX = 200

/**
 * Sunucu alan anahtarı → sihirbaz adımı. Sihirbazda alanı OLMAYAN anahtarlar (zaman aşımı, en çok satır, mail kolonları …)
 * `advanced` döner — o hata yalnız "Gelişmiş görünüm"de düzeltilebilir (bant + geçiş düğmesi).
 */
const FIELD_STEP = {
  team_id: 'connection', profile_id: 'connection',
  sql_text: 'query',
  rules: 'rules', rule_logic: 'rules', slow_query_ms: 'rules',
  schedule: 'schedule', schedule_mode: 'schedule', interval_seconds: 'schedule', cron_expression: 'schedule', windows: 'schedule',
  blackouts: 'schedule', skip_dates: 'schedule', skip_holidays: 'schedule',
  alert_level: 'schedule', alertLevel: 'schedule', notificationGroupId: 'schedule', notification_group_id: 'schedule',
  nocNotify: 'schedule', nocGroupIds: 'schedule',
  name: 'summary', purpose: 'summary', group_name: 'summary', groupName: 'summary', tags: 'summary',
}
export function stepOfField(key) {
  if (typeof key !== 'string' || !key) return 'advanced'
  if (key.startsWith('rules[')) return 'rules'
  return FIELD_STEP[key] || 'advanced'
}

/**
 * Adımda bir alanın ALTINDA çizilen hata anahtarları (O4). Adıma eşlenen ama burada olmayan anahtar (bildirim seviyesi,
 * bildirim / 7/24 grupları, tatil atlama, kural mantığı …) adımın başındaki bantta METİN olarak yazılır — adım göstergesi
 * "1 hatalı alan" deyip ekranda hiçbir ileti göstermemesin.
 */
const RENDERED_KEYS = {
  connection: ['team_id', 'profile_id'],
  query: ['sql_text'],
  rules: ['rules', 'slow_query_ms'],
  schedule: SCHEDULE_FIELD_KEYS,
  summary: ['name', 'purpose', 'group_name', 'tags'],
}

/** Adımın bantta yazılacak hataları → `[[anahtar, ileti]]` (alanının altında çizilmeyenler). */
export function stepBannerErrors(step, errors) {
  const rendered = RENDERED_KEYS[step] || []
  return Object.entries(errors || {}).filter(([k, v]) => v && stepOfField(k) === step
    && !rendered.includes(k) && !(step === 'rules' && k.startsWith('rules[')))
}

/** Hata haritasındaki ilk (adım sırasına göre) adım; yalnız gelişmiş alan varsa `advanced`; boşsa null. */
export function firstErrorStep(errors) {
  let best = null
  for (const [k, v] of Object.entries(errors || {})) {
    if (!v) continue
    const s = stepOfField(k)
    if (s === 'advanced') { if (best == null) best = 'advanced'; continue }
    if (best == null || best === 'advanced' || WIZ_STEPS.indexOf(s) < WIZ_STEPS.indexOf(best)) best = s
  }
  return best
}

/** Adıma ait hata sayısı (gösterge rozetleri). */
export function stepErrorCounts(errors) {
  const out = {}
  for (const [k, v] of Object.entries(errors || {})) {
    if (!v) continue
    const s = stepOfField(k)
    out[s] = (out[s] || 0) + 1
  }
  return out
}

/**
 * Adımın istemci doğrulaması → `{ alan: sözlükAnahtarı | [anahtar, ...args] }` (boşsa adım geçerli). Sunucu asıl
 * doğrulayıcıdır; burada yalnız açık eksikler (zorunlu alan, reddedilen sorgu, kural kodları).
 * `ctx`: `{ guardStatus, needTeam }`.
 */
export function validateStep(step, form, ctx = {}) {
  const out = {}
  if (step === 'connection') {
    if (ctx.needTeam && (form.teamId === '' || form.teamId == null)) out.team_id = 'mon.teamRequired'
    if (form.profileId == null) out.profile_id = 'dbq.form.connectionRequired'
  } else if (step === 'query') {
    const sql = String(form.sqlText || '')
    if (!sql.trim()) out.sql_text = 'dbq.form.sqlRequired'
    else if (sql.length > MAX_SQL_CHARS) out.sql_text = ['dbq.sql.tooLong', MAX_SQL_CHARS]
    else if (ctx.guardStatus === 'rejected') out.sql_text = 'dbq.wiz.v.sqlRejected'
  } else if (step === 'rules') {
    const codes = validateRules(Array.isArray(form.rules) ? form.rules : [])
    for (const [k, code] of Object.entries(codes)) out[k] = `dbq.rule.v.${code}`
    if (form.slowQueryMs !== '' && form.slowQueryMs != null) {
      const n = Number(form.slowQueryMs)
      if (!Number.isFinite(n) || n < 100 || n > 600000) out.slow_query_ms = 'dbq.wiz.v.slowRange'
    }
  } else if (step === 'summary') {
    if (!String(form.name || '').trim()) out.name = 'mon.fieldRequired'
    else if (String(form.name).trim().length > NAME_MAX) out.name = ['dbq.wiz.v.nameTooLong', NAME_MAX]
    const len = String(form.purpose || '').trim().length
    if (len < PURPOSE_MIN || len > PURPOSE_MAX) out.purpose = ['dbq.form.purposeLength', PURPOSE_MIN, PURPOSE_MAX]
    if (!String(form.groupName || '').trim()) out.group_name = 'mon.groupRequired'
    if (!String(form.tags || '').trim()) out.tags = 'mon.tagsRequired'
  }
  return out
}

/** `validateStep` çıktısını çeviri işleviyle metne çevirir (`[anahtar, ...arg]` biçimi desteklenir). */
export function translateErrors(map, t) {
  const out = {}
  for (const [k, v] of Object.entries(map || {})) {
    if (!v) continue
    out[k] = Array.isArray(v) ? t(v[0], ...v.slice(1)) : t(v)
  }
  return out
}

/**
 * İlk geçersiz adım (Özet'te Kaydet öncesi tüm adımlar yeniden doğrulanır) → `{ step, errors }` ya da null.
 * `test` ve `schedule` adımlarının istemci doğrulaması yok (sunucu doğrular).
 */
export function firstInvalidStep(form, ctx = {}) {
  for (const step of WIZ_STEPS) {
    const errors = validateStep(step, form, ctx)
    if (Object.keys(errors).length) return { step, errors }
  }
  return null
}

/** Önerilen izleme adı — şablonun adı (varsa) ya da "Sorgu · <bağlantı>"; 200 karaktere kırpılmaz, boş dönmez. */
export function suggestName({ templateName, connectionName, t }) {
  const base = String(templateName || '').trim()
  if (base) return base
  const conn = String(connectionName || '').trim()
  return conn ? t('dbq.wiz.defaultNameWith', conn) : t('dbq.wiz.defaultName')
}

/** İki adım arası geçiş izni: geri her zaman; ileri yalnız ziyaret edilmiş en ileri adıma dek. */
export function canJump(target, current, furthest) {
  const ti = WIZ_STEPS.indexOf(target), fi = WIZ_STEPS.indexOf(furthest)
  if (ti < 0) return false
  return ti <= Math.max(fi, WIZ_STEPS.indexOf(current))
}
