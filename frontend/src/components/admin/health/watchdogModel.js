/**
 * Kendi kendini izleme — saf model (2026-10-11). Sunucu `/api/admin/system/watchdog` yanıtından kontrol tonu, genel
 * durum ve Sistem Sağlığı sebepleri (`deriveOverall`) çıkar. Hesap sunucuda; burada yalnız sınıflama.
 */

/** Kontrol satırının görünür durumu: problem (onaylı) | watch (tek kötü ölçüm, henüz onaysız) | ok | unknown | na | pending. */
export function checkState(c) {
  if (!c || c.status === 'PENDING') return 'pending'
  if (c.status === 'PROBLEM') return 'problem'
  if (c.last_result === 'PROBLEM') return 'watch'
  if (c.last_result === 'UNKNOWN') return 'unknown'
  if (c.last_result === 'NA') return 'na'
  return 'ok'
}

/** Durum → ToneBadge tonu. Kritik sorun kırmızı, diğer sorun turuncu. */
export function checkTone(c) {
  const s = checkState(c)
  if (s === 'problem') return c.severity === 'CRITICAL' ? 'danger' : 'warning'
  if (s === 'watch') return 'warning'
  if (s === 'ok') return 'success'
  return 'muted'
}

/** Genel durum → ton. */
export const OVERALL_TONE = { UP: 'success', DEGRADED: 'warning', DOWN: 'danger', DISABLED: 'muted', UNKNOWN: 'muted' }

/** Açık (onaylı) sorunlar, kritik önce. */
export function openProblems(data) {
  const checks = Array.isArray(data?.checks) ? data.checks : []
  return checks.filter((c) => c.status === 'PROBLEM')
    .sort((a, b) => (a.severity === 'CRITICAL' ? 0 : 1) - (b.severity === 'CRITICAL' ? 0 : 1))
}

/**
 * Sistem Sağlığı sebepleri: her onaylı sorun bir sebep (kritik → down, diğer → warn); değerlendirme durmuşsa
 * kritik "değerlendirme koşmuyor". Kapalıysa sebep yok.
 */
export function watchdogReasons(data) {
  if (!data || data.enabled === false) return []
  const out = []
  if (data.stale) out.push({ level: 'down', code: 'watchdogStale', section: 'watchdog', args: [] })
  for (const c of openProblems(data)) {
    out.push({ level: c.severity === 'CRITICAL' ? 'down' : 'warn', code: 'watchdog', section: 'watchdog', args: [c.label] })
  }
  return out
}
