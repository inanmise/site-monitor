/**
 * Giriş sayfası "Kullanım istatistikleri" (2026-10-04) — saf model: alan listesi, kip seçimi ve yerel biçimlendirme.
 *
 * <p>Kaynak `GET /api/public-stats` (oturumsuz, sunucuda 60 sn bellekli). Sunucu kullanım şeridi açıkken
 * {@link USAGE_KEYS} alanlarını (değer null olabilir → "—") döner; Marka ayarı
 * `site.monitor.public-stats.usage-enabled` kapalıyken YALNIZ eski iki alanı (`monitored_targets`, `availability_pct`)
 * döner → arayüz şeridi gizler, eski iki rakamı gösterir.
 */

/** Kullanım şeridi alanları — biri bile yanıtta varsa (null dahil) şerit kipi. Sunucu: PublicStatsController.USAGE_FIELDS. */
export const USAGE_KEYS = Object.freeze([
  'healthy_monitors', 'active_monitors', 'total_monitors', 'checks_24h', 'failed_checks_24h',
  'alerts_24h', 'teams', 'active_users', 'online_users', 'logins_24h',
  // 2026-10-05: koşuma sertifika taramaları dahil (ayrıntı alanları) + sertifika izlemesi kutucuğu
  'monitor_checks_24h', 'cert_checks_24h', 'cert_failed_checks_24h',
  'certificates', 'certificates_ok', 'certificates_expiring_30d', 'certificates_expired',
])

/** Yanıt kullanım şeridi alanlarını taşıyor mu (ayar açık + yeni sunucu)? */
export function hasUsage(stats) {
  if (!stats || typeof stats !== 'object' || Array.isArray(stats)) return false
  return USAGE_KEYS.some((k) => Object.prototype.hasOwnProperty.call(stats, k))
}

/** Sonlu sayı mı (null / boş / NaN değil)? `Number(null) === 0` tuzağına düşmez. */
function finite(v) {
  if (v == null || v === '' || typeof v === 'boolean') return null
  const n = Number(v)
  return Number.isFinite(n) ? n : null
}

/** Adet — yerel binlik ayırıcıyla (tr-TR `12.345`, en-GB `12,345`); değer yoksa null (arayüz "—"). */
export function formatCount(v, locale) {
  const n = finite(v)
  return n == null ? null : new Intl.NumberFormat(locale, { maximumFractionDigits: 0 }).format(n)
}

/** Yüzde (0–100 ölçeğinde gelir) — yerel biçim (tr-TR `%99,9`, en-GB `99.9%`); en çok 1 ondalık. */
export function formatPct(v, locale) {
  const n = finite(v)
  return n == null ? null
    : new Intl.NumberFormat(locale, { style: 'percent', minimumFractionDigits: 0, maximumFractionDigits: 1 }).format(n / 100)
}

/**
 * Sağlıklı oranı (0–100) — sağlıklı / aktif izleme; aktif yoksa ya da değerler eksikse null (çubuk/oran çizilmez).
 */
export function healthyRatio(stats) {
  const healthy = finite(stats?.healthy_monitors)
  const active = finite(stats?.active_monitors)
  if (healthy == null || active == null || active <= 0) return null
  return Math.max(0, Math.min(100, (healthy * 100) / active))
}

/** Geçerli sertifika oranı (0–100) — son durumu geçerli / aktif sertifika; eksikse null (2026-10-05). */
export function certRatio(stats) {
  const ok = finite(stats?.certificates_ok)
  const total = finite(stats?.certificates)
  if (ok == null || total == null || total <= 0) return null
  return Math.max(0, Math.min(100, (ok * 100) / total))
}

/**
 * Kutucuk tanımları. `t` = useT, `locale` = useDateLocale. Kullanım kipinde yedi kutucuk: ÖNDE sağlıklı izleme (`hero`
 * — tam satır, sağlıklı oranı çubuğuyla), ardından izleme üçlüsü (24 sa koşum, 24 sa alarm, 7 gün erişilebilirlik) ve
 * kullanıcı üçlüsü (takım, aktif kullanıcı, şu an çevrimiçi) — üç sütunda her üçlü bir satır, iki sütunda ikişer.
 * Eski kipte iki kutucuk (izlenen + erişilebilirlik).
 * Her kutucuk üç satırlık aynı iskelet: `label` (kısa ad — dar kutucukta da tek satır), `value` (biçimli ya da null →
 * "—") + `unit` (değerin yanında küçük ek, ör. "/ 702 aktif"), `caption` (alt satır: zaman penceresi / ikincil sayı).
 * `tip` = dokun-gör açıklama (tanım). `ratio` (yalnız hero) = sağlıklı oranı 0–100 ya da null.
 */
export function buildTiles(stats, { t, locale, usage }) {
  const s = stats && typeof stats === 'object' ? stats : {}
  const count = (k) => formatCount(s[k], locale)
  const availability = { key: 'availability', label: t('login.usage.availability'), value: formatPct(s.availability_pct, locale),
    unit: null, caption: t('login.usage.last7d'), tip: t('login.usage.availabilityTip') }
  if (!usage) {
    return [
      { key: 'monitored', label: t('login.usage.monitored'), value: count('monitored_targets'), unit: null, caption: null,
        tip: t('login.usage.monitoredTip') },
      availability,
    ]
  }
  const active = count('active_monitors')
  const failed = count('failed_checks_24h')
  const certScans = count('cert_checks_24h')
  const logins = count('logins_24h')
  const ratio = healthyRatio(s)
  const certOk = count('certificates_ok')
  const certExpiring = count('certificates_expiring_30d')
  const certExpired = count('certificates_expired')
  const cRatio = certRatio(s)
  // Koşum alt satırı: sertifika taraması sayısı biliniyorsa "N sertifika taraması dahil · M başarısız"
  const checksCaption = certScans != null && failed != null ? t('login.usage.checksSubCert', certScans, failed)
    : failed != null ? t('login.usage.checksSub', failed) : t('login.usage.last24h')
  return [
    { key: 'healthy', hero: true, label: t('login.usage.healthy'), value: count('healthy_monitors'),
      unit: active != null ? t('login.usage.healthySub', active) : null,
      caption: ratio != null ? t('login.usage.healthyRate', formatPct(ratio, locale)) : null, ratio,
      tip: t('login.usage.healthyTip') },
    // Sertifika izlemesi (2026-10-05) — ikinci tam satır: aktif sertifika, geçerli olanlar, 30 gün içinde dolan / dolmuş
    { key: 'certs', hero: true, label: t('login.usage.certs'), value: count('certificates'),
      unit: certOk != null ? t('login.usage.certsSub', certOk) : null,
      caption: certExpiring != null && certExpired != null ? t('login.usage.certsCaption', certExpiring, certExpired) : null,
      ratio: cRatio, tip: t('login.usage.certsTip') },
    { key: 'checks', label: t('login.usage.checks'), value: count('checks_24h'), unit: null,
      caption: checksCaption, tip: t('login.usage.checksTip') },
    { key: 'alerts', label: t('login.usage.alerts'), value: count('alerts_24h'), unit: null,
      caption: t('login.usage.last24h'), tip: t('login.usage.alertsTip') },
    availability,
    { key: 'teams', label: t('login.usage.teams'), value: count('teams'), unit: null, caption: null, tip: t('login.usage.teamsTip') },
    { key: 'users', label: t('login.usage.users'), value: count('active_users'), unit: null, caption: null,
      tip: t('login.usage.usersTip') },
    { key: 'online', label: t('login.usage.online'), value: count('online_users'), unit: null,
      caption: logins != null ? t('login.usage.onlineSub', logins) : null, tip: t('login.usage.onlineTip') },
  ]
}
