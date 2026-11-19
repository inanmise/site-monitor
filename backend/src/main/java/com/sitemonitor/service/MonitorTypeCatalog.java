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
            List.of("cert", "domain", "http", "ping", "port", "dns", "keyword", "page", "pagespeed", "scripted", "dbquery");

    /**
     * Tür → o türe ait {@code AlertEvent.alertType} kümesi.
     *
     * <p>Sertifika tipleri burada LITERAL yazılır. {@code HOSTNAME_MISMATCH}/{@code UNTRUSTED_CA}
     * için EscalationService'te artık sabit var ama {@code monitorTypeSurfaces} kapısı bu dosyayı
     * KAYNAK OLARAK okuyup frontend aynasıyla karşılaştırıyor; sabit kullanılsa tipler kapıya
     * görünmez ve aynı alarm arayuzde sekmesiz kalırdı. Büyüyen listeler {@code TYPE_*}
     * sabitlerinden gelir ve kapı onları dener.
     */
    public static final Map<String, Set<String>> ALERT_TYPES = Map.ofEntries(
            // Map.of en çok 10 çift alır — 11. tür (dbquery, 2026-10-31) ile Map.ofEntries. Kapı regex'i
            // (monitorTypeSurfaces.test.jsx) her girişi `"tür", Set.of(` biçiminde okur; biçim korunur.
            Map.entry("cert",     Set.of("EXPIRY", "CHAIN_BROKEN", "REVOKED", "MISMATCH",
                                   "HOSTNAME_MISMATCH", "UNTRUSTED_CA")),
            Map.entry("http",     Set.of("ACCESSIBILITY", "HTTP_DOWN", "HTTP_SSL", "HTTP_SLOW", "DOMAIN_EXPIRY")),
            Map.entry("port",     Set.of("PORT_DOWN", "PORT_SLOW")),
            Map.entry("dns",      Set.of("DNS_FAILURE", "DNS_CHANGED", "DNS_SLOW", "DNS_UNEXPECTED", "DNS_INCONSISTENT")),
            Map.entry("keyword",  Set.of("KEYWORD", "KEYWORD_SLOW", "KEYWORD_SSL", "KEYWORD_DOMAIN_EXPIRY", "KEYWORD_CHANGED")),
            Map.entry("ping",     Set.of("PING_DOWN", "PING_SLOW")),
            Map.entry("domain",   Set.of("DOMAINMON_EXPIRY", "DOMAINMON_UNKNOWN", "DOMAINMON_STATUS", "DOMAINMON_CHANGED",
                               "DOMAINMON_TRANSFER_LOCK", "DOMAINMON_BLACKLIST")),
            Map.entry("page",     Set.of("PAGE_DOWN", "PAGE_INTEGRITY")),
            Map.entry("scripted", Set.of("SCRIPTED_FAIL", "SCRIPTED_SLOW")),
            Map.entry("pagespeed", Set.of("PAGESPEED_DOWN", "PAGESPEED_SLOW")),
            Map.entry("dbquery",  Set.of("DBQUERY_FAIL", "DBQUERY_THRESHOLD", "DBQUERY_SLOW")));

    /** Tür → Türkçe etiket (e-posta + PDF ortak). */
    public static final Map<String, String> LABELS_TR = Map.ofEntries(
            Map.entry("cert", "Sertifika"), Map.entry("domain", "Alan Adı"), Map.entry("http", "HTTP/Website"),
            Map.entry("ping", "Ping"), Map.entry("port", "Port"), Map.entry("dns", "DNS"), Map.entry("keyword", "Keyword"),
            Map.entry("page", "Sayfa Bütünlüğü"), Map.entry("pagespeed", "Sayfa Hızı"), Map.entry("scripted", "Sentetik (k6)"),
            Map.entry("dbquery", "Veritabanı Sorgusu"));

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

    // ── İzleme SAYFASI kategorisi (2026-11-19, kullanıcı isteği: izleme sayfasında o türün alarmları) ─────────────────
    // Raporlama kümeleri (ALERT_TYPES — haftalık şerit, PDF, push aileleri, yeniden uyarı aralıkları) DEĞİŞMEZ: orada
    // "HTTP/Website" envanterin erişilebilirlik alarmını da kapsar. Sayfa düzeyinde ise HTTP sayfası YALNIZ kendi
    // izlemelerinin alarmlarını gösterir; ACCESSIBILITY (envanter durum izlemesi) kendi sayfasında — Erişilebilirlik
    // (sekme `uptime`). Menü rozetleri, Alarm Geçmişi'nin `src` süzgeci ve sayfanın Alarmlar görünümü bu eşlemeyi kullanır.
    // Frontend aynası: utils/monitorAlertTypes.js PAGE_ALERT_TYPES (monitorTypeSurfaces.test.jsx karşılaştırır).

    /** Erişilebilirlik sayfasının sekme anahtarı. */
    public static final String UPTIME_PAGE = "uptime";
    /** Erişilebilirlik sayfasına ait TEK alarm tipi (raporlamada `http` ailesindedir). */
    public static final String UPTIME_ALERT_TYPE = "ACCESSIBILITY";

    /** Sayfa sırası: rapor sırası + Erişilebilirlik (sertifika envanterinin ardından). */
    public static final List<String> PAGE_ORDER = buildPageOrder();

    private static List<String> buildPageOrder() {
        List<String> out = new java.util.ArrayList<>();
        for (String type : ORDER) {
            out.add(type);
            if ("cert".equals(type)) out.add(UPTIME_PAGE);
        }
        return List.copyOf(out);
    }

    /** Alarm tipinin İZLEME SAYFASI; bilinmeyen tip için {@code null}. */
    public static String pageOfAlert(String alertType) {
        if (UPTIME_ALERT_TYPE.equals(alertType)) return UPTIME_PAGE;
        return typeOfAlert(alertType);
    }

    /** Sayfanın alarm tipleri; bilinmeyen sayfa → boş küme. */
    public static Set<String> pageAlertTypes(String page) {
        if (page == null) return Set.of();
        if (UPTIME_PAGE.equals(page)) return Set.of(UPTIME_ALERT_TYPE);
        Set<String> base = ALERT_TYPES.get(page);
        if (base == null) return Set.of();
        if (!base.contains(UPTIME_ALERT_TYPE)) return base;
        Set<String> out = new java.util.LinkedHashSet<>(base);
        out.remove(UPTIME_ALERT_TYPE);
        return Set.copyOf(out);
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
