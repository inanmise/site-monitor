import { isUnsupportedResponse } from './techErrorReminderModel.js'

/**
 * Ayarlar → Teknik hata raporu → "Push bildirimleri" kartının SAF modeli (2026-10-18, kullanıcı isteği: "teknik hata raporu
 * bildirimlerini haftalık rapordaki gibi push ile de yapalım, ayarlardan kontrol edilebilsin").
 *
 * Sunucu sözleşmesi AYRI uç: `GET /api/admin/tech-error-reports/settings/push` → `{ period_reminder, approval_request,
 * approval_reminder, decision, delivered, failed, push_enabled, read_only }`; `PUT` aynı yola, gövde YALNIZ değişen alanlar
 * (aynı adlarla, boolean). Eski pod bu yolu tanımaz (404 / 405 — hiçbir şey yazmaz): kart "henüz desteklenmiyor" der ve
 * salt okunur olur. Alan sırası `TechErrorSettingsController.PUSH_FIELDS` ile aynı (test Java kaynağını okur).
 *
 * Bilinmeyen ≠ açık: okunamayan blok için değer uydurulmaz (`normalizePush` null döner); eksik alan sunucunun varsayılanı
 * gibi AÇIK sayılır (sunucu her alanı gönderir — eksik alan yalnız daha yeni bir sunucudaki yeni olay olabilir).
 */
export const PUSH_FIELDS = Object.freeze(['period_reminder', 'approval_request', 'approval_reminder', 'decision', 'delivered', 'failed'])

/** Kart grupları (sıra ekranla aynı). */
export const PUSH_GROUPS = Object.freeze([
  { id: 'period', fields: ['period_reminder'] },
  { id: 'approval', fields: ['approval_request', 'approval_reminder', 'decision'] },
  { id: 'send', fields: ['delivered', 'failed'] },
])

/** Sunucu bloğu → kayıtlı görüntü; blok yoksa null. `push_enabled` bilinmiyorsa null (uyarı çizilmez). */
export function normalizePush(r) {
  if (!r || typeof r !== 'object' || Array.isArray(r)) return null
  const out = { read_only: r.read_only === true, push_enabled: typeof r.push_enabled === 'boolean' ? r.push_enabled : null }
  for (const k of PUSH_FIELDS) out[k] = r[k] !== false
  return out
}

/**
 * Kart durumu (saf): `{ status: 'ok' | 'unsupported' | 'failed', data, error }`; beklenmeyen biçimde başarılı yanıt ve önceki
 * değer yoksa null (kart çizilmez). Başarısız / desteklenmeyen okuma ÖNCEKİ kayıtlı değerleri korur.
 */
export function pushState(res, prev = null) {
  const prevData = prev?.data || null
  if (isUnsupportedResponse(res)) return { status: 'unsupported', data: prevData, error: null }
  if (res == null || (typeof res === 'object' && res.success === false)) {
    return { status: 'failed', data: prevData, error: res?.error || res?.message || null }
  }
  const raw = typeof res === 'object' && !Array.isArray(res) && 'success' in res ? res.data : res
  const data = normalizePush(raw)
  if (!data) return prevData ? { status: 'failed', data: prevData, error: null } : null
  return { status: 'ok', data, error: null }
}

/** Kayıtlı görüntü → form (yalnız anahtarlar). */
export function toPushForm(saved) {
  const f = {}
  for (const k of PUSH_FIELDS) f[k] = saved ? saved[k] !== false : true
  return f
}

/** PUT gövdesi: YALNIZ kayıtlı değerden farklı anahtarlar; değişiklik yoksa boş nesne (istek gitmez). */
export function changedPushFields(saved, form) {
  const out = {}
  if (!saved || !form) return out
  for (const k of PUSH_FIELDS) {
    if (Boolean(form[k]) !== Boolean(saved[k])) out[k] = Boolean(form[k])
  }
  return out
}

export function isPushDirty(saved, form) {
  return Object.keys(changedPushFields(saved, form)).length > 0
}

/** Kaydedilen gövde kayıtlı görüntüye uygulanır (yanıt bloğu gelmezse). */
export function applyPush(saved, body) {
  const out = { ...(saved || {}) }
  for (const k of PUSH_FIELDS) if (k in (body || {})) out[k] = Boolean(body[k])
  return out
}
