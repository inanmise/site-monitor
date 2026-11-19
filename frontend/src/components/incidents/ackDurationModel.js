import { formatDuration, parseUtc } from '../../utils/incidentMeta.js'

/**
 * Süreli alarm onayı (2026-11-19, kullanıcı kararı: "Süresiz + hazır süreler"). Onay penceresi (tekli + toplu) bu
 * süreleri sunar; sunucu aynı listeyi doğrular (`AckDurations.ALLOWED_MINUTES` — `ackDurationModel.test.js` Java
 * listesini okur). `null` = süresiz (bugünkü davranış, varsayılan seçim). Süre dolunca alarm hâlâ açıksa onay kalkar ve
 * alarmın normal alıcılarına hatırlatma gider.
 */
export const ACK_DURATION_MINUTES = [60, 240, 480, 1440, 4320]

/** Pencere seçenekleri — ilk (varsayılan) süresiz. */
export const ACK_DURATION_OPTIONS = [null, ...ACK_DURATION_MINUTES]

/** Seçenek değeri (ToggleGroup tek dize ister): süresiz `none`. */
export const optionValue = (min) => (min ? String(min) : 'none')

/** ToggleGroup değerinden dakika — bilinmeyen / boş değer süresiz sayılır (gönderilmez). */
export function minutesOf(value) {
  const n = Number(value)
  return ACK_DURATION_MINUTES.includes(n) ? n : null
}

/** Kısa seçenek etiketi: Süresiz / 1 sa / 4 sa / 8 sa / 1 gün / 3 gün. */
export function durationLabel(min, t) {
  if (!min) return t('ackdur.none')
  if (min % 1440 === 0) return t('ackdur.days', min / 1440)
  return t('ackdur.hours', min / 60)
}

/** Seçilen süreyle onayın düşeceği an (şimdi + süre) — süresizde null. */
export function plannedExpiry(min, nowMs) {
  if (!min || !Number.isFinite(nowMs)) return null
  return new Date(nowMs + min * 60_000)
}

/**
 * Onaylı alarmın süre bilgisi (`ack_expires_at`, UTC): `{ at: Date, remainingMs, expired }`; süre yok / okunamaz → null.
 * Süre dolduktan sonra süpürücü en geç bir dakika içinde onayı düşürür — o aralıkta `expired: true` ("süre doldu").
 */
export function ackExpiry(iso, nowMs) {
  const at = parseUtc(iso)
  if (!at || !Number.isFinite(nowMs)) return null
  const remainingMs = at.getTime() - nowMs
  return { at, remainingMs, expired: remainingMs <= 0 }
}

/** Rozet yanındaki kısa metin: "3 sa 20 dk sonra düşer" / "süresi doldu". */
export function ackExpiryText(iso, nowMs, t) {
  const e = ackExpiry(iso, nowMs)
  if (!e) return null
  if (e.expired) return t('ackdur.expiredNow')
  return t('ackdur.remaining', formatDuration(Math.max(60_000, e.remainingMs), t))
}
