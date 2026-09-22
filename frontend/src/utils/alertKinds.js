/**
 * Alarm türü sınıflandırması (2026-09-22, kullanıcı bildirimi): kesinti zaman çizelgesi HTTP_SSL gibi UYARI
 * niteliğindeki alarmları da kırmızı segment olarak çizip "erişilebilirlik" hesabına katıyordu — sertifika
 * uyarısı kesinti gibi algılanıyordu.
 *
 * KESİNTİ (erişilebilirlik) alarmları: hedefe ulaşılamıyor / kontrol geçmiyor. Bunlar zaman çizelgesinde
 * segment olur ve erişilebilirlik yüzdesini düşürür. Geri kalan her tür (yavaşlık, TLS/sertifika, alan adı
 * bitişi, DNS değişimi, bütünlük, kilit/kara liste…) UYARIDIR: işaretçi olarak gösterilir, kesinti SAYILMAZ.
 * Kanonik liste EscalationService.TYPE_* — yeni bir "_DOWN"/"_FAIL" türü buraya da eklenmeli.
 */
export const OUTAGE_ALERT_TYPES = new Set([
  'ACCESSIBILITY', 'HTTP_DOWN', 'PORT_DOWN', 'PING_DOWN', 'DNS_FAILURE', 'KEYWORD', 'PAGE_DOWN', 'SCRIPTED_FAIL', 'PAGESPEED_DOWN',
])

export function isOutageAlert(type) {
  return OUTAGE_ALERT_TYPES.has(String(type || '').toUpperCase())
}
