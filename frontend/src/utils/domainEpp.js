/**
 * EPP durum kodu yardımcıları (2026-09-22).
 *
 * Aynı kod iki kaynaktan iki biçimde geliyordu: RDAP "client transfer prohibited" (boşluklu),
 * WHOIS/.tr "clientTransferProhibited" (camelCase). Kartlar yan yana durunca aynı şey iki
 * farklı şeymiş gibi okunuyordu. Anahtar (`eppKey`) i18n açıklaması ve karşılaştırma için,
 * etiket (`eppLabel`) ekranda tek biçim için: küçük harf, boşluklu.
 */
export const eppKey = (c) => String(c || '').replace(/\s+/g, '').toLowerCase()

export function eppLabel(c) {
  const s = String(c || '').trim()
  if (!s) return ''
  if (/\s/.test(s)) return s.toLowerCase()
  // camelCase → boşluklu: clientTransferProhibited → client transfer prohibited
  return s.replace(/([a-z])([A-Z])/g, '$1 $2').toLowerCase()
}

/**
 * Kayıt dönemi: başlangıç (son güncelleme = çoğunlukla son yenileme; yoksa oluşturma) → bitiş arasında geçen/kalan gün.
 * Tarihlerden biri yoksa ya da toplam ≤ 0 ise null — "ölçülemeyen null kalır".
 */
export function domainLife(registrationDate, expiryDate, now = Date.now()) {
  const reg = parseIso(registrationDate), exp = parseIso(expiryDate)
  if (reg == null || exp == null || exp <= reg) return null
  const total = Math.round((exp - reg) / 86_400_000)
  const elapsed = Math.min(total, Math.max(0, Math.round((now - reg) / 86_400_000)))
  return { total, elapsed, pct: Math.round((elapsed / total) * 100) }
}

function parseIso(v) {
  if (!v) return null
  const s = String(v)
  const iso = s.length <= 10 ? s + 'T00:00:00Z' : (s.endsWith('Z') || s.includes('+') ? s : s + 'Z')
  const t = new Date(iso).getTime()
  return Number.isNaN(t) ? null : t
}
