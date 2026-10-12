/**
 * Kayıt gövdesi: yalnız yüklenen hâlden DEĞİŞEN alanlar (2026-10-12). Sunucu yalnız gelen anahtarları uygular; tam gövde
 * gönderilirse aynı ayarı açık tutan başka bir yöneticinin o arada kaydettiği alanlar sessizce eski değere dönerdi.
 * Karşılaştırma değer düzeyinde (JSON) — dizi / nesne alanları da doğru karşılaştırılır.
 *
 * @param next  formdan kurulan tam gövde
 * @param base  yüklenen (kayıtlı) hâlin aynı biçimdeki gövdesi; yoksa (ilk kayıt) tam gövde döner
 * @param skip  hiç gönderilmeyecek alanlar (ör. salt okunur damgalar)
 */
export function changedFields(next, base, skip = []) {
  const out = {}
  const omit = new Set(skip)
  for (const [k, v] of Object.entries(next || {})) {
    if (omit.has(k)) continue
    if (base == null || JSON.stringify(v) !== JSON.stringify(base[k])) out[k] = v
  }
  return out
}
