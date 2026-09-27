/**
 * Yenileme planı penceresinin (RenewalPlanModal) SAF modeli — 2026-09-27 shadcn + mweb yeniden tasarımı. React yok.
 *
 * Tüm tarihler YEREL gün anahtarıdır ('yyyy-MM-dd'; kurumda Europe/Istanbul). `new Date('yyyy-MM-dd')` dizeyi UTC gece
 * yarısı okur ve batıdaki saat dilimlerinde bir gün geri kayar — burada yalnız forecastModel'in yerel yardımcıları
 * (`addDays` / `dayDiff`, 'T00:00:00' ekleyip YEREL okur) ve `dayDate` (yıl/ay/gün kurucusu) kullanılır.
 *
 * Kavramlar:
 *   • renew-by  — sertifikanın "en geç yenileme" günü (bitiş − tier başına yenileme öncesi süre; sunucu hesaplar).
 *                 Alan adı kaydında YOKTUR (çağıran null verir).
 *   • plan durumu — seçilen günün bugün / renew-by / bitişe göre konumu → uyarı tonu (`planState`).
 */
import { addDays, dayDiff, isHoliday, isWeekend, lastBusinessDay } from '../pages/forecastModel.js'

/** Not üst sınırı — sunucudaki kolonla aynı (renewal_planned_note length = 500; sunucu fazlasını keser). */
export const NOTE_MAX = 500
/** Sayaç bu eşikten sonra uyarı tonuna döner (son 50 karakter). */
export const NOTE_WARN_AT = NOTE_MAX - 50

export function isOffDay(key) { return !!key && (isWeekend(key) || isHoliday(key)) }

/** Tarih iş günü değilse SONRAKİ iş günü (lastBusinessDay'in ileri yönlüsü). */
export function nextBusinessDay(key) {
  let k = key; let guard = 0
  while (isOffDay(k) && guard++ < 14) k = addDays(k, 1)
  return k
}

/** Gün anahtarı → yerel Date (00:00). Biçimlendirme içindir; saat dilimi kayması yok. */
export function dayDate(key) {
  const [y, m, d] = String(key).slice(0, 10).split('-').map(Number)
  return new Date(y, m - 1, d)
}

/**
 * Gün anahtarını okunur biçimde yazar: en-GB "Wed, 30 Sept 2026" · tr-TR "30 Eyl 2026 Çar".
 * Haftanın günü bilinçli olarak VAR — plan gününün hafta sonuna denk gelip gelmediği bir bakışta görünsün.
 */
export function formatDay(key, locale, { year = true, weekday = 'short' } = {}) {
  if (!key) return '—'
  try {
    return dayDate(key).toLocaleDateString(locale, {
      weekday: weekday || undefined, day: 'numeric', month: 'short', ...(year ? { year: 'numeric' } : {}),
    })
  } catch {
    return key
  }
}

/** Haftanın günü, uzun ad (Wednesday / Çarşamba). */
export function weekdayName(key, locale) {
  try { return dayDate(key).toLocaleDateString(locale, { weekday: 'long' }) } catch { return '' }
}

/**
 * Seçilen günün durumu (uyarı tonunu belirler):
 *   'past'         bugünden önce (plan kaydedilir ama hemen gecikmiş görünür)
 *   'afterExpiry'  bitişten sonra (en ağır uyarı — engellenmez: sunucuda tarih kuralı yok, yalnız biçim denetimi)
 *   'onExpiry'     tam bitiş günü (güvenlik payı yok)
 *   'afterRenewBy' renew-by'dan sonra, bitişten önce (geç yenileme penceresi)
 *   'ok'           aksi hâlde
 */
export function planState({ today, renewBy, expiry, planned }) {
  if (!planned) return null
  if (planned < today) return 'past'
  if (expiry && planned > expiry) return 'afterExpiry'
  if (expiry && planned === expiry) return 'onExpiry'
  if (renewBy && planned > renewBy) return 'afterRenewBy'
  return 'ok'
}

/**
 * Hızlı seçimler — her biri İŞ GÜNÜNE düşer (hafta sonu / resmî tatil ise önceki iş günü), bugünden önceye ve
 * (bitiş gelecekteyse) bitişten sonraya ÖNERİLMEZ, aynı güne düşen ikinciler elenir, sonuç tarih sırasındadır.
 *   • renew-by varsa  → 'renewBy'       (en geç gün)
 *     yoksa bitiş varsa → 'beforeExpiry' (bitişten 2 hafta önce)
 *     bu çapa geçmişte/uygunsuzsa → 'nextWorkingDay' (yarından itibaren ilk iş günü)
 *   • 'week' / 'twoWeeks' → bugünden 7 / 14 gün sonra
 */
export function quickPicks({ today, renewBy, expiry }) {
  const latest = expiry && expiry >= today ? expiry : null
  const ok = (d) => !!d && d >= today && (!latest || d <= latest)
  const out = []
  const push = (key, date) => { if (ok(date) && !out.some((p) => p.date === date)) out.push({ key, date }) }
  const anchor = renewBy
    ? ['renewBy', lastBusinessDay(renewBy)]
    : expiry ? ['beforeExpiry', lastBusinessDay(addDays(expiry, -14))] : null
  if (anchor && ok(anchor[1])) push(...anchor)
  else push('nextWorkingDay', nextBusinessDay(addDays(today, 1)))
  push('week', lastBusinessDay(addDays(today, 7)))
  push('twoWeeks', lastBusinessDay(addDays(today, 14)))
  return out.sort((a, b) => (a.date < b.date ? -1 : a.date > b.date ? 1 : 0))
}

/**
 * Hafta sonu / tatil düzeltmesi: önce ÖNCEKİ iş günü önerilir (geç kalmaktansa erken); o gün bugünden önceyse
 * SONRAKİ iş günü. `direction` metni seçmek içindir ('before' | 'after').
 */
export function offDaySuggestion(key, today) {
  if (!isOffDay(key)) return null
  const prev = lastBusinessDay(key)
  if (prev >= today) return { date: prev, direction: 'before' }
  return { date: nextBusinessDay(key), direction: 'after' }
}

/**
 * Zaman şeridi (bugün → bitiş) konumları, yüzde. Bitiş yoksa ya da bugün/geçmişteyse şerit çizilmez (null) —
 * dolmuş bir kaydın şeridi anlamsız; rozetler ve uyarı durumu anlatır. Aralık dışındaki plan uca SABİTLENİR;
 * rengi (planState) aralık dışında olduğunu söyler.
 */
export function timelineModel({ today, renewBy, expiry, planned }) {
  if (!expiry) return null
  const span = dayDiff(today, expiry)
  if (!(span > 0)) return null
  const pos = (k) => Math.min(100, Math.max(0, (dayDiff(today, k) / span) * 100))
  return {
    renewBy: renewBy ? pos(renewBy) : null,
    planned: planned ? pos(planned) : null,
    state: planState({ today, renewBy, expiry, planned }),
  }
}

/** Kalan gün → rozet tonu (kart ve takvimle aynı varsayılan eşikler: 7 kritik, 30 uyarı). */
export function daysTone(days) {
  if (days == null) return 'ok'
  if (days <= 7) return 'critical'
  if (days <= 30) return 'warning'
  return 'ok'
}

/** Kısayol ipucu: Apple aygıtlarında ⌘, diğerlerinde Ctrl. */
export function modKeyLabel() {
  try {
    const p = (typeof navigator !== 'undefined' && (navigator.userAgentData?.platform || navigator.platform || navigator.userAgent)) || ''
    return /mac|iphone|ipad|ipod/i.test(p) ? '⌘' : 'Ctrl'
  } catch {
    return 'Ctrl'
  }
}
