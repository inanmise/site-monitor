package com.sitemonitor.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * İzleme türlerinin TEK kanonik kataloğu: tür anahtarı → o türe ait alarm tipleri + Türkçe etiket.
 *
 * <p><b>Neden ayrı bir sınıf.</b> Bu eşleme üç ayrı yerde kopyalanmıştı ve üçü de birbirinden
 * habersiz bayatlıyordu:
 * <ul>
 *   <li>{@link MonitoringWeeklyStatsService} — haftalık tür istatistikleri (alarm kovaları)</li>
 *   <li>{@code EmailNotificationService.weeklyMonitoringBlock} — haftalık e-postadaki tür etiketleri</li>
 *   <li>haftalık kesinti PDF'i (bu iş)</li>
 * </ul>
 * Kopyalar tutulurken iki tür sessizce düşmüştü: <b>sayfa bütünlüğü</b> (page) alarm eşlemesinde
 * hiç yoktu — yani PAGE_DOWN/PAGE_INTEGRITY alarmları haftalık göstergelerde HİÇ sayılmıyordu —
 * ve <b>sentetik</b> (scripted) e-posta etiket haritasında yoktu, ham "scripted" yazılıyordu.
 * İkisi de kimseye hata vermeden, yalnızca eksik veri üreterek çalışıyordu.
 *
 * <p><b>Kapı.</b> {@code MonitorTypeCatalogTest} EscalationService'teki {@code TYPE_*} sabitlerini
 * kaynaktan okur ve her birinin burada bir türe bağlı olmasını şart koşar. Yeni bir alarm tipi
 * eklendiği anda süit KIRMIZI olur ve eksik tipin adını söyler — kopya-bayatlama bir daha sessiz
 * olamaz. (Aynı korumanın frontend tarafı: {@code alertTypeMeta.test.jsx}.)
 *
 * <p>Sıra {@code frontend/src/components/WeeklyMonitoringStrip.jsx} ORDER'ı ile birebir aynıdır;
 * iki uçtaki sıra ayrışırsa aynı rapor iki yerde farklı okunur.
 */
public final class MonitorTypeCatalog {

    private MonitorTypeCatalog() {}

    /** Ekranda/PDF'te tür sırası — frontend WeeklyMonitoringStrip.ORDER ile AYNI. */
    public static final List<String> ORDER =
            List.of("cert", "domain", "http", "ping", "port", "dns", "keyword", "page", "pagespeed", "scripted");

    /**
     * Tür → o türe ait {@code AlertEvent.alertType} kümesi.
     *
     * <p>Sertifika tipleri burada LITERAL yazılır. {@code HOSTNAME_MISMATCH}/{@code UNTRUSTED_CA}
     * için EscalationService'te artık sabit var ama {@code monitorTypeSurfaces} kapısı bu dosyayı
     * KAYNAK OLARAK okuyup frontend aynasıyla karşılaştırıyor; sabit kullanılsa tipler kapıya
     * görünmez ve aynı alarm arayuzde sekmesiz kalırdı. Büyüyen listeler {@code TYPE_*}
     * sabitlerinden gelir ve kapı onları dener.
     */
    public static final Map<String, Set<String>> ALERT_TYPES = Map.of(
            "cert",     Set.of("EXPIRY", "CHAIN_BROKEN", "REVOKED", "MISMATCH",
                                   "HOSTNAME_MISMATCH", "UNTRUSTED_CA"),
            "http",     Set.of("ACCESSIBILITY", "HTTP_DOWN", "HTTP_SSL", "DOMAIN_EXPIRY"),
            "port",     Set.of("PORT_DOWN", "PORT_SLOW"),
            "dns",      Set.of("DNS_FAILURE", "DNS_CHANGED", "DNS_SLOW", "DNS_UNEXPECTED", "DNS_INCONSISTENT"),
            "keyword",  Set.of("KEYWORD", "KEYWORD_SLOW", "KEYWORD_SSL", "KEYWORD_DOMAIN_EXPIRY"),
            "ping",     Set.of("PING_DOWN", "PING_SLOW"),
            "domain",   Set.of("DOMAINMON_EXPIRY", "DOMAINMON_UNKNOWN", "DOMAINMON_STATUS", "DOMAINMON_CHANGED",
                               "DOMAINMON_TRANSFER_LOCK", "DOMAINMON_BLACKLIST"),
            "page",     Set.of("PAGE_DOWN", "PAGE_INTEGRITY"),
            "scripted", Set.of("SCRIPTED_FAIL", "SCRIPTED_SLOW"),
            "pagespeed", Set.of("PAGESPEED_DOWN", "PAGESPEED_SLOW"));

    /** Tür → Türkçe etiket (e-posta + PDF ortak). */
    public static final Map<String, String> LABELS_TR = Map.of(
            "cert", "Sertifika", "domain", "Alan Adı", "http", "HTTP/Website", "ping", "Ping",
            "port", "Port", "dns", "DNS", "keyword", "Keyword", "page", "Sayfa Bütünlüğü",
            "pagespeed", "Sayfa Hızı", "scripted", "Sentetik (k6)");

    /** alertType → tür (ters eşleme, tek sefer kurulur). */
    private static final Map<String, String> TYPE_OF_ALERT = buildReverse();

    private static Map<String, String> buildReverse() {
        Map<String, String> m = new LinkedHashMap<>();
        for (String type : ORDER) {
            for (String alert : ALERT_TYPES.getOrDefault(type, Set.of())) {
                String prev = m.put(alert, type);
                if (prev != null) {
                    // İki tür aynı alarm tipini sahiplenirse gruplama sessizce keyfi olurdu.
                    throw new IllegalStateException(
                            "Alarm tipi iki türe birden bağlı: " + alert + " → " + prev + " ve " + type);
                }
            }
        }
        return Map.copyOf(m);
    }

    /** Alarm tipinin izleme türü; bilinmeyen tip için {@code null} (çağıran "Diğer"e düşürür). */
    public static String typeOfAlert(String alertType) {
        return alertType == null ? null : TYPE_OF_ALERT.get(alertType);
    }

    /** Tür etiketi; bilinmeyen tür için anahtarın kendisi (çökmek yerine ham anahtar görünür). */
    public static String label(String type) {
        return LABELS_TR.getOrDefault(type, type == null ? "—" : type);
    }

    /** Katalogda bağlı olan TÜM alarm tipleri (kapı testi ve kapsam kontrolü için). */
    public static Set<String> allAlertTypes() {
        return TYPE_OF_ALERT.keySet();
    }
}
