/**
 * Sessiz saat (2026-10-01, onaylı öneri 15) — takım alarm bildirimleri ve kişisel push için ORTAK form modeli.
 * Sunucu kuralının aynası (backend `QuietHours`): "HH:mm" Europe/Istanbul, pencere gece yarısını geçebilir, gün süzgeci
 * pencerenin BAŞLADIĞI güne uygulanır, boş/yedi gün = her gün. `minLevel` = pencerede HEMEN giden en düşük seviye:
 * HIGH (varsayılan) → yalnız UYARI ertelenir; CRITICAL → UYARI + YÜKSEK. KRİTİK hiçbir ayarla ertelenmez.
 * Arayüz anında geri bildirim verir; garanti sunucudadır (aynı kurallar, 400 + Msg.t).
 */

export const QUIET_DAYS = ['MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT', 'SUN']
export const QUIET_LEVELS = ['HIGH', 'CRITICAL']
const HHMM = /^([01]\d|2[0-3]):[0-5]\d$/

/** Boş form değeri — pencere YOK (bugünkü davranış). */
export const EMPTY_QUIET = Object.freeze({ start: '', end: '', days: [...QUIET_DAYS], minLevel: 'HIGH' })

function daysFrom(raw) {
  const list = Array.isArray(raw) ? raw : String(raw || '').split(',')
  const set = new Set(list.map((d) => String(d).trim().toUpperCase()).filter((d) => QUIET_DAYS.includes(d)))
  return set.size === 0 ? [...QUIET_DAYS] : QUIET_DAYS.filter((d) => set.has(d))
}

/** Sunucu alanları → form değeri. `src` = { start, end, days, minLevel } (anahtar adlarını çağıran eşler). */
export function quietFromServer(src) {
  const start = src?.start || ''
  const end = src?.end || ''
  if (!start || !end) return { ...EMPTY_QUIET, days: [...QUIET_DAYS] }
  const lvl = String(src?.minLevel || '').toUpperCase()
  return { start, end, days: daysFrom(src?.days), minLevel: QUIET_LEVELS.includes(lvl) ? lvl : 'HIGH' }
}

/** Takım kaydı (snake_case ya da camelCase) → form değeri. */
export function quietFromTeam(team) {
  return quietFromServer({
    start: team?.quiet_start ?? team?.quietStart,
    end: team?.quiet_end ?? team?.quietEnd,
    days: team?.quiet_days ?? team?.quietDays,
    minLevel: team?.quiet_min_level ?? team?.quietMinLevel,
  })
}

/** /me `push_quiet` → form değeri. */
export function quietFromMe(pushQuiet) {
  return quietFromServer({ start: pushQuiet?.start, end: pushQuiet?.end, days: pushQuiet?.days, minLevel: pushQuiet?.min_level })
}

export function quietIsSet(v) {
  return !!(v && (v.start || v.end))
}

/** İki form değeri anlamca aynı mı (gün sırası/kümesi, boş pencere eşitliği). */
export function quietEqual(a, b) {
  const sa = quietIsSet(a), sb = quietIsSet(b)
  if (!sa && !sb) return true
  if (sa !== sb) return false
  return a.start === b.start && a.end === b.end && (a.minLevel || 'HIGH') === (b.minLevel || 'HIGH')
    && daysFrom(a.days).join(',') === daysFrom(b.days).join(',')
}

/**
 * Doğrulama — `{ anahtar: mesaj | false }` (useFormErrors.check'e verilir). Anahtarlar `keys` ile eşlenir
 * (takım formu `quiet_start`…; kişi kartı `start`…). Pencere boşsa hata yok (kaldırma geçerli).
 */
export function quietErrors(v, t, keys = { start: 'quiet_start', end: 'quiet_end', days: 'quiet_days' }) {
  const out = { [keys.start]: false, [keys.end]: false, [keys.days]: false }
  if (!quietIsSet(v)) return out
  if (!v.start) out[keys.start] = t('quiet.err.bothRequired')
  else if (!HHMM.test(v.start)) out[keys.start] = t('quiet.err.format')
  if (!v.end) out[keys.end] = t('quiet.err.bothRequired')
  else if (!HHMM.test(v.end)) out[keys.end] = t('quiet.err.format')
  if (v.start && v.end && !out[keys.start] && !out[keys.end] && v.start === v.end) out[keys.end] = t('quiet.err.same')
  if (!Array.isArray(v.days) || v.days.length === 0) out[keys.days] = t('quiet.err.days')
  return out
}

/** Form değeri → sunucu alanları. Yedi gün = boş liste (her gün); pencere yoksa her şey boş (= kaldır). */
export function quietPayload(v) {
  if (!quietIsSet(v)) return { start: '', end: '', days: [], minLevel: '' }
  const days = daysFrom(v.days)
  return {
    start: v.start,
    end: v.end,
    days: days.length === QUIET_DAYS.length ? [] : days,
    minLevel: v.minLevel === 'CRITICAL' ? 'CRITICAL' : '',
  }
}

/** Takım PUT gövdesine eklenecek anahtarlar. */
export function quietTeamPayload(v) {
  const p = quietPayload(v)
  return { quiet_start: p.start, quiet_end: p.end, quiet_days: p.days, quiet_min_level: p.minLevel }
}

/** Kişisel kayıt gövdesi (POST /me/push-quiet-hours). */
export function quietUserPayload(v) {
  const p = quietPayload(v)
  return { start: p.start, end: p.end, days: p.days, min_level: p.minLevel }
}

/** Kısa özet: "22:00–07:00 · Pzt, Sal" (gün etiketleri çağırandan). */
export function quietSummary(v, t) {
  if (!quietIsSet(v)) return t('quiet.none')
  const days = daysFrom(v.days)
  const dayText = days.length === QUIET_DAYS.length ? t('quiet.everyDay') : days.map((d) => t('quiet.day.' + d)).join(', ')
  return `${v.start}–${v.end} · ${dayText}`
}
