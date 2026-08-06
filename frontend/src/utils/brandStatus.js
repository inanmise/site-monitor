/**
 * Genel filo durumu türetimi (saf fonksiyon — BrandLogo/favicon besler).
 * Kaynak: App.jsx'in ZATEN yüklediği stats (5 dk poll) + networkStatus.alarm (60 sn poll);
 * yeni endpoint/zamanlayıcı açılmaz. Öncelik BRAND.md §3: critical > warning > ok;
 * muted yalnız veri yokluğunda.
 */
export function deriveGlobalStatus(stats, networkAlarm = false) {
  if (!stats) return 'muted'
  const n = (v) => (typeof v === 'number' ? v : 0)
  if (networkAlarm || n(stats.critical_count) > 0 || n(stats.error_count) > 0 || n(stats.expired) > 0) {
    return 'critical'
  }
  if (n(stats.high_count) > 0 || n(stats.warning_count) > 0) return 'warning'
  return 'ok'
}
