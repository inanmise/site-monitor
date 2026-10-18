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
 * Dönem hatırlatmasının push anahtarı (`reminder_push`) okunur ama bu kartın formunda / gövdesinde YOK (2026-10-18): anahtar
 * "Push bildirimleri" kartına taşındı (`techErrorPushModel.js`, `…/settings/push`) — iki kart aynı anahtarı tutsaydı
 * kaydedilmemiş taslaklı bir yenileme diğer kartın kaydını eski değerle ezebilirdi.
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
  const out = {
    team_ids: normalizeIds(r.team_ids), reminder_push: r.reminder_push !== false, limits, read_only: r.read_only === true,
    out_of_range: normalizeOutOfRange(r.out_of_range),
  }
  for (const k of REM_INT_FIELDS) out[k] = intOr(r[k], limits[k].default)
  return out
}

/**
 * Aralık dışı değer notları (inceleme 2026-10-18): `{ <alan>: { source: 'env' | 'stored', value, default } }` — sunucu, ortam
 * ya da kayıtlı değeri aralık dışı olup varsayılana düşen alanları söyler (eski sunucu alanı göndermez → boş nesne, not yok).
 * Bilinmeyen alan / kaynak atlanır; değer metin olarak tutulur (biçimsiz kayıtlı değer de gösterilebilsin, ≤ 40 karakter).
 */
export function normalizeOutOfRange(raw) {
  const out = {}
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return out
  for (const k of REM_INT_FIELDS) {
    const o = raw[k]
    if (!o || typeof o !== 'object') continue
    if (o.source !== 'env' && o.source !== 'stored') continue
    const def = Number(o.default)
    if (!Number.isInteger(def)) continue
    const value = String(o.value ?? '').trim()
    out[k] = { source: o.source, value: value.length > 40 ? `${value.slice(0, 40)}…` : value, default: def }
  }
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
  const f = { team_ids: [...s.team_ids] }
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
 * gövde bütçesi posta sınırından KÜÇÜK (ikisi de geçerliyse). `saved` verilirse YALNIZ değişen alanlar denetlenir (kural 9,
 * inceleme 2026-10-18): kayıtlı değerle aynı sayı (aynı değerin başka yazımı da — "06" = 6) yeniden doğrulanmaz; alanlar-arası
 * kural yalnız iki sınırdan biri değiştiyse (sunucu da yalnız gönderilen tarafı etkin değerle karşılaştırır).
 */
export function validateForm(form, limits = REM_DEFAULT_LIMITS, saved = null) {
  const errs = {}
  const lim = limits || REM_DEFAULT_LIMITS
  // Onarım alanı (aralık dışı değer varsayılana düştü) kayıtta YAZILIR → değişmiş sayılır ve doğrulanır (alanlar-arası kural da)
  const unchanged = (k) => saved != null && !isRepairKey(saved, k)
    && parseWhole(form?.[k]) != null && parseWhole(form?.[k]) === saved[k]
  for (const k of REM_INT_FIELDS) {
    if (unchanged(k)) continue
    const l = lim[k] || REM_DEFAULT_LIMITS[k]
    const n = parseWhole(form?.[k])
    if (n == null || n < l.min || n > l.max) errs[k] = { key: 'te.rem.v.whole', params: [l.min, l.max] }
  }
  const max = parseWhole(form?.max_mail_mb)
  const inline = parseWhole(form?.mail_inline_mb)
  const limitsChanged = !unchanged('max_mail_mb') || !unchanged('mail_inline_mb')
  if (limitsChanged && !errs.max_mail_mb && !errs.mail_inline_mb && max != null && inline != null && inline >= max) {
    errs.mail_inline_mb = { key: 'te.rem.v.inline', params: [inline, max] }
  }
  return errs
}

/**
 * Onarım alanı (inceleme 2026-10-18, 2. tur): sunucu bu alanın etkin değerinin aralık DIŞI bir ortam / kayıtlı değerden
 * varsayılana düştüğünü söyledi (`out_of_range`). Form orada etkin değeri (= varsayılan) tutar ve kayıtlı görüntü de aynı
 * sayıyı söyler — "kayıtlıyla aynı = değişiklik değil" kuralı onu atar, varsayılan hiç yazılamazdı (iki kayıt gerekirdi).
 * Böyle bir alandaki GEÇERLİ form değeri değişiklik sayılır: Kaydet onu açıkça yazar — sunucu override olarak saklar, ortam
 * değeri artık uygulanmaz, bozuk kayıtlı değer ezilir ve not kalkar.
 */
export function isRepairKey(saved, k) {
  return !!saved?.out_of_range?.[k]
}

/** Kayıtta yazılacak onarım alanları (`isRepairKey`) — sırası `REM_INT_FIELDS`. */
export function repairKeys(saved) {
  return REM_INT_FIELDS.filter((k) => isRepairKey(saved, k))
}

/**
 * PUT gövdesi: YALNIZ kayıtlı değerden farklı alanlar (sayılar tam sayıya çevrilir) + onarım alanları (`isRepairKey`; aynı
 * sayı da yazılır). Değişiklik yoksa boş nesne — çağıran istek göndermez. Biçimsiz sayı "değişiklik" sayılır ama gövdeye
 * girmez (doğrulama önce durdurur). `{ repairs: false }` onarımları saymaz (kullanıcının kendi değişiklikleri).
 */
export function changedFields(saved, form, { repairs = true } = {}) {
  const out = {}
  if (!saved || !form) return out
  if (isDirty(saved.team_ids, form.team_ids)) out.reminder_team_ids = normalizeIds(form.team_ids)
  for (const k of REM_INT_FIELDS) {
    const n = parseWhole(form[k])
    if (n != null && (n !== saved[k] || (repairs && isRepairKey(saved, k)))) out[k] = n
  }
  return out
}

/**
 * Kaydedilmemiş değişiklik var mı: değişen alan, onarım alanı ya da yarım / biçimsiz yazılmış sayı (boş, "1.5" — Kaydet
 * doğrulamayı gösterir). Aynı değerin farklı yazımı ("06" = 6) değişiklik değildir. `{ repairs: false }` = yalnız
 * kullanıcının kendi değişiklikleri — sayfadan ayrılırken sorulan onay ve Yenile'de taslağı koruma bunu okur (yalnız onarım
 * bekleyen form her ziyarette "kaydedilmemiş değişiklik" diye sormasın; ayrılınca kaybolan bir şey yok).
 */
export function isFormDirty(saved, form, { repairs = true } = {}) {
  if (!saved || !form) return false
  if (Object.keys(changedFields(saved, form, { repairs })).length > 0) return true
  return REM_INT_FIELDS.some((k) => parseWhole(form[k]) == null)
}
