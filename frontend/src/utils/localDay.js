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

const DAY_MS = 86_400_000

/**
 * Bir tarihe kalan gün (geçmiş negatif; bozuk / boş → null) — Ek 3/1 (2026-09-28).
 *
 * <p><b>Yalnız tarih</b> ("YYYY-MM-DD": yenileme planı, WHOIS bitişi) = YEREL TAKVİM GÜNÜ farkı: bugüne planlanan 0, yarın
 * 1, dün −1 — günün saatinden bağımsız. Eskiden UTC gece yarısına çevrilip `floor((tarih − şimdi) / gün)` alınıyordu:
 * öğlen bugünün tarihi "1 gün önce", yarınınki "bugün", bitiş günü kırmızı "1 gün önce doldu" oluyor, ≤ 30 gün eşiği de
 * bir gün kayıyordu. İki uç da UTC takvimiyle (`Date.UTC`) çıkarılır → yaz saati geçişinde 23/25 saatlik gün kaydırmaz.
 * <p><b>Zaman damgası</b> (UTC, ofsetsiz → `Z`) eskisi gibi kalan TAM gün (`floor`).
 */
export function daysFromToday(iso, now = Date.now()) {
  if (!iso) return null
  const s = String(iso).trim()
  const ymd = /^(\d{4})-(\d{2})-(\d{2})$/.exec(s)
  if (ymd) {
    const y = Number(ymd[1]), m = Number(ymd[2]), d = Number(ymd[3])
    if (m < 1 || m > 12 || d < 1 || d > 31) return null
    const today = new Date(now)
    if (Number.isNaN(today.getTime())) return null
    return Math.round((Date.UTC(y, m - 1, d) - Date.UTC(today.getFullYear(), today.getMonth(), today.getDate())) / DAY_MS)
  }
  const at = new Date(toUtc(s)).getTime()
  return Number.isNaN(at) ? null : Math.floor((at - now) / DAY_MS)
}
