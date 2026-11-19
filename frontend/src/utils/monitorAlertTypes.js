/**
 * İzleme türü → o türün SAHİP OLDUĞU alarm tipleri.
 *
 * <p>Neden var: izleme modallarındaki "Alarmlar" sekmesi yalnız URL/host'a göre süzülüyordu ve
 * aynı hedefi izleyen HER monitörün alarmı oraya düşüyordu. Sayfa Hızı modalinde HTTP izlemesinin
 * SSL alarmı ve Sayfa Bütünlüğü alarmı görünüyordu — kullanıcı orada onlara müdahale edemez,
 * üstelik sayfa kendi üretmediği alarmları üretmiş gibi görünür.
 *
 * <p>Süzme SUNUCUDA yapılır (`alertTypes` parametresi): istemcide süzmek yalnız açık sayfayı
 * süzer; sayfalama, tip çipleri ve seviye sayaçları yanlış kalırdı.
 *
 * <p><b>Tek kaynak backend'dir:</b> {@code MonitorTypeCatalog.ALERT_TYPES}. Bu dosya onun aynası
 * ve {@code monitorTypeSurfaces.test.jsx} ikisini karşılaştırıyor — biri değişip diğeri kalırsa
 * süit kırılır. Elle senkron tutulan iki liste, tam da bu ekranın düştüğü hata sınıfıdır.
 */
export const MONITOR_ALERT_TYPES = {
  cert:      ['EXPIRY', 'CHAIN_BROKEN', 'REVOKED', 'MISMATCH', 'HOSTNAME_MISMATCH', 'UNTRUSTED_CA'],
  http:      ['ACCESSIBILITY', 'HTTP_DOWN', 'HTTP_SSL', 'HTTP_SLOW', 'DOMAIN_EXPIRY'],
  port:      ['PORT_DOWN', 'PORT_SLOW'],
  dns:       ['DNS_FAILURE', 'DNS_CHANGED', 'DNS_SLOW', 'DNS_UNEXPECTED', 'DNS_INCONSISTENT'],
  keyword:   ['KEYWORD', 'KEYWORD_SLOW', 'KEYWORD_SSL', 'KEYWORD_DOMAIN_EXPIRY', 'KEYWORD_CHANGED'],
  ping:      ['PING_DOWN', 'PING_SLOW'],
  domain:    ['DOMAINMON_EXPIRY', 'DOMAINMON_UNKNOWN', 'DOMAINMON_STATUS', 'DOMAINMON_CHANGED',
              'DOMAINMON_TRANSFER_LOCK', 'DOMAINMON_BLACKLIST'],
  page:      ['PAGE_DOWN', 'PAGE_INTEGRITY'],
  pagespeed: ['PAGESPEED_DOWN', 'PAGESPEED_SLOW'],
  scripted:  ['SCRIPTED_FAIL', 'SCRIPTED_SLOW'],
  dbquery:   ['DBQUERY_FAIL', 'DBQUERY_THRESHOLD', 'DBQUERY_SLOW'],
}

/** Bilinmeyen tür → boş dizi; AlertHistory bunu "süzme yok" sayar (mevcut davranış korunur). */
export function alertTypesFor(monitorType) {
  return MONITOR_ALERT_TYPES[monitorType] ?? []
}

/**
 * İzleme SAYFASI kategorisi (2026-11-19, kullanıcı isteği: izleme sayfasında o türün alarmları + HTTP / Erişilebilirlik
 * ayrımı). Rapor kümesi (MONITOR_ALERT_TYPES) DEĞİŞMEZ; sayfa düzeyinde HTTP sayfası YALNIZ kendi izlemelerinin
 * alarmlarını gösterir, ACCESSIBILITY (envanter durum izlemesi) Erişilebilirlik sayfasının (`uptime`) alarmıdır. Menü
 * rozetleri (sunucu `/me/open-alerts` sekmeleri), Alarm Geçmişi'nin `src` süzgeci ve sayfaların Alarmlar görünümü bunu
 * kullanır. Sunucu aynası: `MonitorTypeCatalog.pageAlertTypes` / `PAGE_ORDER` (monitorTypeSurfaces.test.jsx karşılaştırır).
 */
export const UPTIME_PAGE = 'uptime'
export const UPTIME_ALERT_TYPE = 'ACCESSIBILITY'
export const PAGE_ALERT_TYPES = Object.freeze(Object.fromEntries(
  Object.entries(MONITOR_ALERT_TYPES).flatMap(([type, list]) => {
    const own = Object.freeze(list.filter((a) => a !== UPTIME_ALERT_TYPE))
    return type === 'cert'
      ? [[type, own], [UPTIME_PAGE, Object.freeze([UPTIME_ALERT_TYPE])]]   // sunucu PAGE_ORDER: sertifikanın hemen ardından
      : [[type, own]]
  }),
))

/** Sayfanın alarm tipleri; bilinmeyen sayfa → boş dizi. */
export function pageAlertTypesFor(page) {
  return PAGE_ALERT_TYPES[page] ?? []
}

/**
 * Alarm tipi → izleme SAYFASI türü (PAGE_ALERT_TYPES'ın TERSİ, tek kaynaktan türetilir) — 2026-11-19'dan beri Erişilebilirlik
 * (ACCESSIBILITY) kendi sayfasıyla (`uptime`) rozetlenir, HTTP değil (sayfanın Alarmlar görünümüyle tutarlı).
 */
const MONITOR_TYPE_OF_ALERT = Object.freeze(Object.fromEntries(
  Object.entries(PAGE_ALERT_TYPES).flatMap(([type, list]) => list.map((alertType) => [alertType, type])),
))

/**
 * Alarmın hangi izleme türünden geldiği (2026-10-08, kullanıcı: "Alarm geçmişine düşen bir alarmın hangi izleme tipinden
 * geldiği net görünmüyor") — `cert`, `http`, `port`, … ; bilinmeyen alarm tipi → null (rozet çizilmez).
 */
export function monitorTypeOfAlert(alertType) {
  return MONITOR_TYPE_OF_ALERT[String(alertType || '').trim().toUpperCase()] ?? null
}
