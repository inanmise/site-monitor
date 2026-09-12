/**
 * Zaman damgası → UTC ISO ve yerel gün anahtarı. `api/client.js`'ten ayrıldı (2026-09-13) ki
 * istemci modülünü tümüyle mock'layan sayfa testleri bileşenleri bu yardımcılardan yoksun bırakmasın.
 * `api/client.js` aynı adlarla yeniden dışa aktarır; eski import yolları geçerli kalır.
 */

/** Sunucu damgaları UTC'dir; ofset/Z taşımayan dizeye 'Z' eklenir, çıplak tarih gece yarısı UTC olur. */
export function toUtc(iso) {
  if (typeof iso !== 'string') return iso
  const s = iso.trim()
  if (/[zZ]$/.test(s)) return s                                  // zaten UTC
  if (/[+-]\d{2}:?\d{2}$/.test(s)) return s                      // ofset taşıyor (+03:00 / -0300)
  if (/^\d{4}-\d{2}-\d{2}$/.test(s)) return s + 'T00:00:00Z'     // yalnız tarih
  return s + 'Z'
}

/**
 * Yerel gün anahtarı 'YYYY-MM-DD' (QA 2026-09-12, ISSUE-004): sunucu zaman damgaları UTC'dir
 * ("2026-10-23T23:59:59" = 24/10 02:59 İstanbul). `.slice(0, 10)` UTC gününü alır ve takvim/ICS
 * olayı bir gün ERKEN düşer. Yalnız tarih ("YYYY-MM-DD") verildiyse olduğu gibi döner.
 */
export function localDayKey(iso) {
  if (!iso) return null
  if (typeof iso === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(iso.trim())) return iso.trim()
  try {
    const d = new Date(toUtc(iso))
    if (Number.isNaN(d.getTime())) return String(iso).slice(0, 10)
    return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
  } catch {
    return String(iso).slice(0, 10)
  }
}
