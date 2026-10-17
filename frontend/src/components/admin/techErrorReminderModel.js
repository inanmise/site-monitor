import { isDirty, normalizeIds } from './noc/operatorTeamsModel.js'

/**
 * Ayarlar → Teknik hata raporu → "Hatırlatmalar ve posta sınırları" kartının SAF modeli (12. sürüm, 2026-10-17 — kullanıcı
 * onayı C9 / C10 / E21). Sunucu sözleşmesi AYRI uç (inceleme 2026-10-18: yuvarlanan dağıtımda eski pod `PUT /settings`
 * gövdesindeki eksik `team_ids`'i boş okuyup modül görünürlük kısıtını siliyordu): `GET /api/admin/tech-error-reports/settings/reminders`
 * → `{ team_ids, reminder_day, reminder_repeat_days, approval_reminder_days, max_mail_mb, mail_inline_mb, reminder_push,
 * limits: { <alan>: { min, max, default } }, read_only }`; `PUT` aynı yola, gövde YALNIZ değişen alanlar (aynı adlarla).
 * Alan hataları sunucudan `field` = bu adlardan biri olarak döner. Eski pod bu yolu tanımaz: GET 404, PUT 405 / 404 → kart
 * "henüz desteklenmiyor" der ve salt okunur olur (`reminderState`, `isUnsupportedResponse`).
 *
 * Form sayı alanlarını METİN tutar (yarım yazılmış değer kaybolmasın); kayıtta tam sayıya çevrilir. Bilinmeyen ≠ varsayılan:
 * okunamayan blok için değer uydurulmaz (`normalizeReminders` null döner).
 */
export const REM_INT_FIELDS = Object.freeze(['reminder_day', 'reminder_repeat_days', 'approval_reminder_days', 'max_mail_mb', 'mail_inline_mb'])

/** Sunucu aralıkları gelmezse (kısmi yanıt) kullanılan sınırlar — `TechErrorReportSettings.java` ile aynı. */
export const REM_DEFAULT_LIMITS = Object.freeze({
  reminder_day: { min: 1, max: 28, default: 5 },
  reminder_repeat_days: { min: 0, max: 14, default: 3 },
  approval_reminder_days: { min: 1, max: 14, default: 3 },
  max_mail_mb: { min: 5, max: 50, default: 15 },
  mail_inline_mb: { min: 0, max: 20, default: 6 },
})

function intOr(v, fallback) {
  const n = Number(v)
  return Number.isInteger(n) ? n : fallback
}

/** Sunucu bloğu → kayıtlı görüntü; blok yoksa null (kart çizilmez — eski sunucu). */
export function normalizeReminders(r) {
  if (!r || typeof r !== 'object' || Array.isArray(r)) return null
  const limits = {}
  for (const k of REM_INT_FIELDS) {
    const l = r.limits && typeof r.limits === 'object' ? r.limits[k] : null
    const d = REM_DEFAULT_LIMITS[k]
    limits[k] = { min: intOr(l?.min, d.min), max: intOr(l?.max, d.max), default: intOr(l?.default, d.default) }
  }
  const out = { team_ids: normalizeIds(r.team_ids), reminder_push: r.reminder_push !== false, limits, read_only: r.read_only === true }
  for (const k of REM_INT_FIELDS) out[k] = intOr(r[k], limits[k].default)
  return out
}

/** Bu uç sunucuda yok (eski pod: GET 404, PUT 405 / 404) — hiçbir şey yazılmadı, "henüz desteklenmiyor". */
export function isUnsupportedResponse(res) {
  return !!res && typeof res === 'object' && res.success === false && (res.status === 404 || res.status === 405)
}

/**
 * Sayfanın hatırlatma kartı durumu (saf): `{ status: 'ok' | 'unsupported' | 'failed', data, error }`; beklenmeyen biçimde
 * başarılı yanıt ve önceki değer yoksa null (kart çizilmez). Başarısız / desteklenmeyen okuma ÖNCEKİ kayıtlı değerleri
 * korur — değer uydurulmaz (bilinmeyen ≠ varsayılan).
 */
export function reminderState(res, prev = null) {
  const prevData = prev?.data || null
  if (isUnsupportedResponse(res)) return { status: 'unsupported', data: prevData, error: null }
  if (res == null || (typeof res === 'object' && res.success === false)) {
    return { status: 'failed', data: prevData, error: res?.error || res?.message || null }
  }
  const raw = typeof res === 'object' && !Array.isArray(res) && 'success' in res ? res.data : res
  const data = normalizeReminders(raw)
  if (!data) return prevData ? { status: 'failed', data: prevData, error: null } : null
  return { status: 'ok', data, error: null }
}

/** Kayıtlı görüntü → düzenleme formu (sayılar metin). */
export function toForm(saved) {
  const s = saved || normalizeReminders({})
  const f = { team_ids: [...s.team_ids], reminder_push: s.reminder_push }
  for (const k of REM_INT_FIELDS) f[k] = String(s[k])
  return f
}

/** Metin → tam sayı (yalnız rakamlar); boş / biçimsiz → null. */
export function parseWhole(v) {
  const t = String(v ?? '').trim()
  if (!/^\d{1,6}$/.test(t)) return null
  return Number(t)
}

/**
 * Doğrulama — `{ alan: { key, params } }` (hata yoksa boş nesne). Kurallar sunucuyla aynı: her sayı aralık içinde tam sayı,
 * gövde bütçesi posta sınırından KÜÇÜK (ikisi de geçerliyse).
 */
export function validateForm(form, limits = REM_DEFAULT_LIMITS) {
  const errs = {}
  for (const k of REM_INT_FIELDS) {
    const l = limits[k] || REM_DEFAULT_LIMITS[k]
    const n = parseWhole(form?.[k])
    if (n == null || n < l.min || n > l.max) errs[k] = { key: 'te.rem.v.whole', params: [l.min, l.max] }
  }
  const max = parseWhole(form?.max_mail_mb)
  const inline = parseWhole(form?.mail_inline_mb)
  if (!errs.max_mail_mb && !errs.mail_inline_mb && max != null && inline != null && inline >= max) {
    errs.mail_inline_mb = { key: 'te.rem.v.inline', params: [inline, max] }
  }
  return errs
}

/**
 * PUT gövdesi: YALNIZ kayıtlı değerden farklı alanlar (sayılar tam sayıya çevrilir). Değişiklik yoksa boş nesne — çağıran
 * istek göndermez. Biçimsiz sayı "değişiklik" sayılır ama gövdeye girmez (doğrulama önce durdurur).
 */
export function changedFields(saved, form) {
  const out = {}
  if (!saved || !form) return out
  if (isDirty(saved.team_ids, form.team_ids)) out.reminder_team_ids = normalizeIds(form.team_ids)
  for (const k of REM_INT_FIELDS) {
    const n = parseWhole(form[k])
    if (n != null && n !== saved[k]) out[k] = n
  }
  if (Boolean(form.reminder_push) !== Boolean(saved.reminder_push)) out.reminder_push = Boolean(form.reminder_push)
  return out
}

/**
 * Kaydedilmemiş değişiklik var mı: değişen alan ya da yarım / biçimsiz yazılmış sayı (boş, "1.5" — Kaydet doğrulamayı
 * gösterir). Aynı değerin farklı yazımı ("06" = 6) değişiklik değildir.
 */
export function isFormDirty(saved, form) {
  if (!saved || !form) return false
  if (Object.keys(changedFields(saved, form)).length > 0) return true
  return REM_INT_FIELDS.some((k) => parseWhole(form[k]) == null)
}
