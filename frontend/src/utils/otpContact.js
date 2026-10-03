/**
 * Kodla giriş — kişi bilgisi alanı yardımcıları (2026-10-03, kullanıcı isteği: "push ile loginde telefon no, mail ile
 * loginde mail adresi de girilsin; kullanıcı adıyla eşleşirse kod gönderilsin").
 *
 * Saf fonksiyonlar (testte doğrudan sınanır). Telefon normalleştirmesi sunucudaki `OtpContactMatcher.normalizePhone`
 * ile AYNI kuraldır: yalnız rakamlar, baştaki 0090 / 90 (geride ≥ 10 hane kalıyorsa) ve trunk 0 düşer, en az 10 hane,
 * karşılaştırılan son 10 hane. Arayüz yalnız MAKULLÜĞÜ denetler (eksik hane / bozuk adres alanın yanında söylenir);
 * eşleşme kararı HER ZAMAN sunucudadır ve ekran eşleşip eşleşmediğini asla göstermez.
 */

/** Ulusal numara hane sayısı (karşılaştırılan son hane). */
export const PHONE_DIGITS = 10
/** E.164 üst sınırı — daha uzun rakam dizisi telefon değildir. */
export const PHONE_MAX_DIGITS = 15
/** Telefon alanının azami karakter sayısı (biçimli yazım dahil). */
export const PHONE_MAX_LENGTH = 24
/** E-posta alanının azami karakter sayısı (RFC 5321). */
export const EMAIL_MAX_LENGTH = 254

/** Unicode rakamlarını (tam genişlik, Arap-Hint) dahil yalnız rakamlar → ASCII. */
export function phoneDigits(value) {
  const s = String(value ?? '')
  let out = ''
  for (const ch of s) {
    if (/\p{Nd}/u.test(ch)) {
      const ascii = ch.normalize('NFKC')
      if (/^[0-9]$/.test(ascii)) out += ascii
      else {
        // Arap-Hint (U+0660–0669) ve Doğu Arap-Hint (U+06F0–06F9) NFKC ile değişmez — kod noktasından çevrilir.
        const cp = ch.codePointAt(0)
        if (cp >= 0x0660 && cp <= 0x0669) out += String(cp - 0x0660)
        else if (cp >= 0x06f0 && cp <= 0x06f9) out += String(cp - 0x06f0)
      }
    }
  }
  return out
}

/** Sunucuyla aynı normalleştirme → son 10 hane ya da '' (yetersiz). */
export function normalizePhone(value) {
  let d = phoneDigits(value)
  if (d.startsWith('0090')) d = d.slice(4)
  else if (d.startsWith('90') && d.length >= PHONE_DIGITS + 2) d = d.slice(2)
  if (d.startsWith('0')) d = d.slice(1)
  return d.length >= PHONE_DIGITS ? d.slice(-PHONE_DIGITS) : ''
}

/** Telefon makul mü: normalleşince ≥ 10 hane ve toplamda ≤ 15 hane. */
export function isPlausiblePhone(value) {
  const all = phoneDigits(value)
  return all.length <= PHONE_MAX_DIGITS && normalizePhone(value).length === PHONE_DIGITS
}

/** E-posta makul mü: boşluksuz, tek '@', alan adında nokta. */
export function isPlausibleEmail(value) {
  const s = String(value ?? '').trim()
  return s.length <= EMAIL_MAX_LENGTH && /^[^\s@]+@[^\s@.]+(\.[^\s@.]+)+$/.test(s)
}

/** Rakamları gruplara böler: [4,3,2,2] → "0532 123 45 67"; artan haneler son gruba eklenir. */
function group(d, sizes) {
  const parts = []
  let i = 0
  for (const n of sizes) {
    if (i >= d.length) break
    parts.push(d.slice(i, i + n))
    i += n
  }
  if (i < d.length) parts[parts.length - 1] += d.slice(i)
  return parts.join(' ')
}

/**
 * Yazarken HAFİF biçimlendirme — yalnız kullanıcı düz rakam (ve boşluk) yazıyorsa Türkiye cep düzenine gruplar:
 * "05321234567" → "0532 123 45 67", "5321234567" → "532 123 45 67", "905321234567" → "90 532 123 45 67".
 * `+ ( ) - .` gibi bir işaret varsa kullanıcının biçimi AYNEN korunur ("+90 (532) 123-45-67" engellenmez).
 */
export function formatPhoneInput(value) {
  const s = String(value ?? '')
  if (!/^[0-9\s]*$/.test(s)) return s
  const d = s.replace(/\s+/g, '')
  if (!d) return ''
  if (d.length > PHONE_MAX_DIGITS) return d
  if (d.startsWith('0090')) return group(d, [4, 3, 3, 2, 2])
  if (d.startsWith('90') && d.length > 11) return group(d, [2, 3, 3, 2, 2])
  if (d.startsWith('0')) return group(d, [4, 3, 2, 2])
  return group(d, [3, 3, 2, 2])
}

/**
 * Seçili kanal için istenen kişi bilgisi: push + `push_requires_phone` → 'phone', e-posta + `email_requires_email` →
 * 'email', aksi null. Bayraklar public `login-methods` yanıtından; eski sunucu göndermezse alan çizilmez.
 */
export function contactKindOf(methods, channel) {
  if (channel === 'push' && methods?.push_requires_phone === true) return 'phone'
  if (channel === 'email' && methods?.email_requires_email === true) return 'email'
  return null
}
