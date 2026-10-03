import { pushSafe, pushTruncate } from '../../../utils/pushSafeText.js'

/**
 * Kodla giriş PUSH metni düzenleyicisinin saf modeli (2026-10-03) — backend `service/otp/OtpPushTemplate` aynası. Kayıt ve
 * "kendime test gönder" SUNUCUDA aynı kurallarla doğrulanır (400 + field); bu dosya yalnız alanın yanında ANINDA hata,
 * canlı sayaç ve telefon önizlemesi için.
 *
 * Yer tutucular yalnız mesajda: `{kod}` (zorunlu, TAM bir kez), `{sure}` (sn), `{saat}` (HH:mm, İstanbul). Başlık düz
 * metin, kanal süzgecinden sonra en çok 60 karakter. Mesaj uzunluğu EN KÖTÜ dolumla (kod 6, süre 3, saat 5 karakter)
 * ve süzgeçten SONRA ölçülür — push tavanını aşan metin telefonda kırpılır ve kod kesilebilirdi.
 */

export const LANGS = ['tr', 'en']
export const PLACEHOLDERS = ['kod', 'sure', 'saat']
export const TITLE_MAX = 60
/** Sunucu göndermezse (eski sürüm) kullanılan tavan — push kanalının varsayılanı. */
export const DEFAULT_MESSAGE_MAX = 200
const WORST = { kod: '000000', sure: '300', saat: '23:59' }
const BRACED = /\{([^{}]*)\}/g

/** Formdaki alan adları (kayıt gövdesi ve sunucu `field` ile aynı). */
export const titleKey = (lang) => `push_title_${lang}`
export const messageKey = (lang) => `push_message_${lang}`
export const PUSH_TEXT_FIELDS = LANGS.flatMap((l) => [titleKey(l), messageKey(l)])

/** Süslü parantez içindeki adlar (tekrarlar dahil). */
export function placeholdersIn(s) {
  return [...String(s ?? '').matchAll(BRACED)].map((m) => m[1])
}

/** Yer tutucuları doldurur; bilinmeyenlere dokunmaz. */
export function fillTemplate(tpl, values = {}) {
  return String(tpl ?? '').replace(BRACED, (all, name) => (PLACEHOLDERS.includes(name) ? String(values[name] ?? '') : all))
}

/** Başlığın telefondaki uzunluğu (süzgeçten sonra). */
export function titleLength(title) {
  return (pushSafe(String(title ?? '')) || '').length
}

/** Mesajın en kötü dolumla, süzgeçten SONRAKİ uzunluğu (sayaç + doğrulama aynı hesap). */
export function worstCaseLength(message) {
  return (pushSafe(fillTemplate(message, WORST)) || '').length
}

/** Boş metin = yerleşik varsayılan (sunucu da boş saklar). */
export function effective(value, fallback) {
  return String(value ?? '').trim() ? String(value) : String(fallback ?? '')
}

/** Başlık doğrulaması → hata metni ya da null (boş = varsayılan, geçerli). */
export function validateTitle(title, t) {
  const s = String(title ?? '').trim()
  if (!s) return null
  const ph = placeholdersIn(s)
  if (ph.includes('kod')) return t('lm.push.err.titleCode')
  if (ph.length) return t('lm.push.err.titlePlaceholder')
  const len = titleLength(s)
  if (len === 0) return t('lm.push.err.titleEmpty')
  if (len > TITLE_MAX) return t('lm.push.err.titleLong', TITLE_MAX, len)
  return null
}

/** Mesaj doğrulaması → hata metni ya da null. `max`: push mesaj tavanı. */
export function validateMessage(message, max, t) {
  const s = String(message ?? '').trim()
  if (!s) return null
  const ph = placeholdersIn(s)
  const unknown = [...new Set(ph.filter((p) => !PLACEHOLDERS.includes(p)))].map((p) => `{${p}}`)
  if (unknown.length) return t('lm.push.err.unknown', unknown.join(', '))
  const codes = ph.filter((p) => p === 'kod').length
  if (codes === 0) return t('lm.push.err.noCode')
  if (codes > 1) return t('lm.push.err.multiCode')
  const len = worstCaseLength(s)
  if (len > max) return t('lm.push.err.tooLong', len, max)
  return null
}

/** Formun tüm push metni alanları → hata haritası (boş = geçerli). */
export function validatePushTexts(form, max, t) {
  const errs = {}
  for (const lang of LANGS) {
    if (form?.[titleKey(lang)] !== undefined) {
      const e = validateTitle(form[titleKey(lang)], t)
      if (e) errs[titleKey(lang)] = e
    }
    if (form?.[messageKey(lang)] !== undefined) {
      const e = validateMessage(form[messageKey(lang)], max, t)
      if (e) errs[messageKey(lang)] = e
    }
  }
  return errs
}

/** Seçimin yerine (imleçte) jeton ekler → `{ value, caret }`. */
export function insertAtCursor(value, token, start, end) {
  const v = String(value ?? '')
  const a = Number.isInteger(start) ? Math.max(0, Math.min(start, v.length)) : v.length
  const b = Number.isInteger(end) ? Math.max(a, Math.min(end, v.length)) : a
  return { value: v.slice(0, a) + token + v.slice(b), caret: a + token.length }
}

/**
 * Telefonda TAM OLARAK görünecek metin: örnek değerlerle doldur → kanal süzgeci → tavan kırpması (sendDirect sırası).
 * Başlık boş kalırsa sunucu da push ayarındaki genel başlığa düşer — önizleme varsayılan başlığı gösterir.
 */
export function phoneView({ title, message, defaults = {}, samples = {}, max = DEFAULT_MESSAGE_MAX }) {
  const tpl = effective(message, defaults.message)
  const body = pushTruncate(pushSafe(fillTemplate(tpl, samples)) || '', max)
  const head = pushSafe(effective(title, defaults.title)) || pushSafe(String(defaults.title ?? '')) || ''
  return { title: head, message: body }
}
