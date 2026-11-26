package com.sitemonitor.service;

import java.util.Map;

/**
 * Erişilebilirliğe SAYILAN kontrol satırları — tek kaynak (H paketi 2026-11-26, rapor B1 / B2). Saatlik / günlük özet
 * ({@code SchedulerService.upsertSql} / {@code uptimeUpsertSql}) ve kart SLA / trend sorguları ({@code MonitorSparklineService})
 * aynı süzgeci kullanır; üretilen kovalar ve kartlar böylece aynı satırları sayar.
 *
 * <p><b>Neyi çıkarır:</b>
 * <ul>
 *   <li><b>Elle "Şimdi kontrol et" satırı</b> ({@code manual = TRUE}) ve <b>teyit / kurtarma yeniden kontrolü</b>
 *       ({@code recheck = TRUE}): planlı çizelgenin dışındaki ölçümlerdir. Bir kesintide teyit zinciri ve hata ayıklarken
 *       yapılan elle kontroller aynı tabloya ek BAŞARISIZ satır yazar; dahil edilince düşük frekanslı izlemenin aylık
 *       erişilebilirliği gerçekte olduğundan çok düşük çıkıyordu (günde bir CRON'luk sorgu: 29/30 yerine 29/43).</li>
 *   <li><b>ICMP'nin ölçemediği ping satırı</b> ({@code failure_reason = ICMP_UNAVAILABLE}; işaretten önceki eski satırlar
 *       hata metniyle tanınır): hedef hakkında bir şey söylemez — alarm hattı da bunu kesinti saymaz. Paydaya girince ICMP
 *       yetkisi olmayan pod'da her ping izlemesi %0'a yakın görünüyordu.</li>
 * </ul>
 *
 * <p>İşaret kolonları NULL'lanabilir ve yalnız {@code TRUE} yazılır (eski satırlar ve planlı satırlar NULL); dbquery /
 * sentetik / DNS / alan adı bazı satırlarda {@code FALSE} yazar — süzgeç ikisini de "sayılır" kabul eder. İfadeler yalnız
 * KOD sabitidir (dış veri yok); tarih aralığı süzgecinin ardında kalan satır süzgecidir, plan değiştirmez.
 */
public final class AvailabilityRows {

    private AvailabilityRows() {}

    /** Elle tetiklenmemiş satır. */
    static final String NOT_MANUAL = "(manual IS NULL OR manual = FALSE)";
    /** Teyit / kurtarma yeniden kontrolü olmayan satır. */
    static final String NOT_RECHECK = "(recheck IS NULL OR recheck = FALSE)";
    /**
     * ICMP'nin bu pod'da ölçebildiği ping satırı. Hata metni {@code PingCheckerService}'in "kullanılamıyor" dalının yazdığı
     * metnin ASCII öneki — {@code failure_reason} kolonundan (2026-10-05) önceki satırlar da tanınır.
     */
    static final String PING_MEASURED = "(failure_reason IS NULL OR failure_reason <> 'ICMP_UNAVAILABLE')"
            + " AND (error IS NULL OR error NOT LIKE 'ICMP bu ortamda kullan%')";

    private static final String PLANNED = NOT_MANUAL + " AND " + NOT_RECHECK;

    /** Tablo → satır süzgeci (öncü {@code AND} yok). Listede olmayan tablo: her satır sayılır. */
    static final Map<String, String> COUNTED = Map.ofEntries(
            Map.entry("http_checks", PLANNED),
            Map.entry("keyword_results", PLANNED),
            Map.entry("port_checks", PLANNED),
            Map.entry("page_checks", PLANNED),
            Map.entry("uptime_checks", PLANNED),
            Map.entry("ping_checks", PLANNED + " AND " + PING_MEASURED),
            Map.entry("scripted_checks", PLANNED),
            Map.entry("pagespeed_checks", PLANNED),
            Map.entry("dbquery_checks", PLANNED),
            // DNS teyit / kurtarma yeniden kontrolleri satır yazmaz (yalnız elle kontrol yazar).
            Map.entry("dns_records", NOT_MANUAL),
            // Alan adı kaydının erişilebilirlik özeti / kart SLA'sı yok — tutarlılık için (gelecekte bir okuyucu çıkarsa).
            Map.entry("domain_checks", NOT_MANUAL));

    /** Tablonun satır süzgeci, öncü {@code " AND "} ile; süzgeç yoksa boş dizi. */
    public static String andCounted(String table) {
        String expr = COUNTED.get(table);
        return expr == null ? "" : " AND (" + expr + ")";
    }

    /** Tablonun satır süzgeci (öncü AND yok) ya da null. */
    public static String counted(String table) {
        return COUNTED.get(table);
    }
}
