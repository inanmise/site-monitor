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
  http:      ['ACCESSIBILITY', 'HTTP_DOWN', 'HTTP_SSL', 'DOMAIN_EXPIRY'],
  port:      ['PORT_DOWN', 'PORT_SLOW'],
  dns:       ['DNS_FAILURE', 'DNS_CHANGED', 'DNS_SLOW', 'DNS_UNEXPECTED', 'DNS_INCONSISTENT'],
  keyword:   ['KEYWORD', 'KEYWORD_SLOW', 'KEYWORD_SSL', 'KEYWORD_DOMAIN_EXPIRY'],
  ping:      ['PING_DOWN'],
  domain:    ['DOMAINMON_EXPIRY', 'DOMAINMON_UNKNOWN', 'DOMAINMON_STATUS', 'DOMAINMON_CHANGED',
              'DOMAINMON_TRANSFER_LOCK', 'DOMAINMON_BLACKLIST'],
  page:      ['PAGE_DOWN', 'PAGE_INTEGRITY'],
  pagespeed: ['PAGESPEED_DOWN', 'PAGESPEED_SLOW'],
  scripted:  ['SCRIPTED_FAIL', 'SCRIPTED_SLOW'],
}

/** Bilinmeyen tür → boş dizi; AlertHistory bunu "süzme yok" sayar (mevcut davranış korunur). */
export function alertTypesFor(monitorType) {
  return MONITOR_ALERT_TYPES[monitorType] ?? []
}
