/**
 * Kullanıcı adı ön eki kartının saf modeli (2026-10-21, kullanıcı isteği: "yalnız N ile başlayan kullanıcıya login izni; harfleri
 * ayarlardan yöneteyim"). Kurallar sunucudaki `UsernamePrefixRule` ile aynı (sunucu yine her kayıtta doğrular):
 *
 * - izinli ön ek: 1–10 karakter, yalnız harf (A–Z) ve rakam; büyük harfe çevrilir, tekrar atılır; en çok 20;
 * - muaf kullanıcı adı: 1–100 karakter, boşluk / virgül / noktalı virgül / denetim karakteri yok; büyük harf; en çok 50;
 * - kural açıkken izinli liste boş olamaz.
 *
 * Form alanları (`username_prefix_enabled` / `_allowed` / `_exempt`) Giriş Yöntemleri formunun içindedir; kayıtta YALNIZ
 * değişen alanlar gönderilir (öteki alanların kaydı, depodaki ön ek değerini yeniden doğrulatmasın).
 */

export const FIELD_ENABLED = 'username_prefix_enabled'
export const FIELD_ALLOWED = 'username_prefix_allowed'
export const FIELD_EXEMPT = 'username_prefix_exempt'
export const PREFIX_FIELDS = [FIELD_ENABLED, FIELD_ALLOWED, FIELD_EXEMPT]

/** Sunucu sınırları gelmezse (eski sürüm) varsayılanlar — `UsernamePrefixRule` sabitleriyle aynı. */
export const DEFAULT_LIMITS = Object.freeze({
  prefix_max_len: 10, max_prefixes: 20, exempt_max_len: 100, max_exempt: 50, sample_limit: 50,
})

/**
 * Karşılaştırma biçimi — sunucunun `fold`'u: kırp, büyük harf; Türkçe `İ` → `I`, birleşik nokta atılır (`ı` zaten `I`).
 * `toUpperCase` (yerel ayardan bağımsız) kullanılır, `toLocaleUpperCase('tr')` DEĞİL — "i → İ" tuzağı yok.
 */
export function foldName(s) {
  return String(s ?? '').trim().toUpperCase().replace(/İ/g, 'I').replace(/̇/g, '')
}

/** Ayırıcıya göre (virgül, noktalı virgül, boşluk, satır sonu) parçalar — yapıştırılan liste için. */
export function splitTokens(text) {
  return String(text ?? '').split(/[,;\s]+/).map((x) => x.trim()).filter(Boolean)
}

/** İzinli ön ek hatası (metin anahtarı + parametreler) ya da null. */
export function prefixProblem(raw, limits = DEFAULT_LIMITS) {
  const s = String(raw ?? '').trim()
  const max = Number(limits?.prefix_max_len) || DEFAULT_LIMITS.prefix_max_len
  if (!s) return null
  if (s.length > max || !/^[A-Za-z0-9]+$/.test(s)) return { key: 'lm.prefix.err.prefixFormat', params: [s, max] }
  return null
}

/** Muaf ad hatası (metin anahtarı + parametreler) ya da null. */
export function exemptProblem(raw, limits = DEFAULT_LIMITS) {
  const s = String(raw ?? '').trim()
  const max = Number(limits?.exempt_max_len) || DEFAULT_LIMITS.exempt_max_len
  if (!s) return null
  // eslint-disable-next-line no-control-regex
  if (s.length > max || /[\s,;\u0000-\u001f\u007f]/.test(s)) return { key: 'lm.prefix.err.exemptFormat', params: [s, max] }
  return null
}

/** Ön eki listede tutulacak biçime getirir (büyük harf). */
export const normalizePrefix = (s) => String(s ?? '').trim().toUpperCase()
/** Muaf adı listede tutulacak biçime getirir (katlanmış büyük harf — sunucuyla aynı). */
export const normalizeExempt = (s) => foldName(s)

/** İki listenin içeriği (sıra dahil) aynı mı. */
export function sameList(a, b) {
  const x = Array.isArray(a) ? a : []
  const y = Array.isArray(b) ? b : []
  return x.length === y.length && x.every((v, i) => v === y[i])
}

/** Forma gelen değer → liste (sunucu dizi döner; CSV gelirse de çalışsın). */
export function asList(v) {
  if (Array.isArray(v)) return v.filter((x) => typeof x === 'string' && x)
  if (typeof v === 'string') return v.split(',').map((x) => x.trim()).filter(Boolean)
  return []
}

/** Kayıt gövdesine girecek DEĞİŞEN ön ek alanları (kaydedilmişle aynı olanlar gönderilmez). */
export function changedPrefixFields(form, saved) {
  const out = {}
  if (!form || form[FIELD_ENABLED] === undefined) return out   // sunucu bloğu hiç göndermedi (eski sürüm)
  if (!!form[FIELD_ENABLED] !== !!saved?.[FIELD_ENABLED]) out[FIELD_ENABLED] = !!form[FIELD_ENABLED]
  if (!sameList(asList(form[FIELD_ALLOWED]), asList(saved?.[FIELD_ALLOWED]))) out[FIELD_ALLOWED] = asList(form[FIELD_ALLOWED])
  if (!sameList(asList(form[FIELD_EXEMPT]), asList(saved?.[FIELD_EXEMPT]))) out[FIELD_EXEMPT] = asList(form[FIELD_EXEMPT])
  return out
}

/** Kayıt öncesi alan doğrulaması → hata haritası (useFormErrors.check). Açıkken boş liste yasak; sınırlar. */
export function validatePrefixForm(form, limits, t) {
  const errs = {}
  if (!form || form[FIELD_ENABLED] === undefined) return errs
  const allowed = asList(form[FIELD_ALLOWED])
  const exempt = asList(form[FIELD_EXEMPT])
  const maxP = Number(limits?.max_prefixes) || DEFAULT_LIMITS.max_prefixes
  const maxE = Number(limits?.max_exempt) || DEFAULT_LIMITS.max_exempt
  if (form[FIELD_ENABLED] && allowed.length === 0) errs[FIELD_ALLOWED] = t('lm.prefix.err.emptyAllowed')
  else if (allowed.length > maxP) errs[FIELD_ALLOWED] = t('lm.prefix.err.tooManyPrefixes', maxP)
  if (exempt.length > maxE) errs[FIELD_EXEMPT] = t('lm.prefix.err.tooManyExempt', maxE)
  return errs
}

/**
 * Kuralı SIKILAŞTIRAN bir değişiklik mi (onay sorulur): kapalıyken açılıyor, ya da açıkken izinli / muaf liste değişiyor.
 * Kapatmak ya da ilgisiz bir alanı değiştirmek onay istemez.
 */
export function tightensRule(form, saved) {
  if (!form?.[FIELD_ENABLED]) return false
  if (!saved?.[FIELD_ENABLED]) return true
  return !sameList(asList(form[FIELD_ALLOWED]), asList(saved?.[FIELD_ALLOWED]))
    || !sameList(asList(form[FIELD_EXEMPT]), asList(saved?.[FIELD_EXEMPT]))
}

/** Önizleme isteği için anahtar (aynı değerlerle iki kez sorulmasın). */
export function previewKey(allowed, exempt) {
  return `${asList(allowed).join(',')}|${asList(exempt).join(',')}`
}
