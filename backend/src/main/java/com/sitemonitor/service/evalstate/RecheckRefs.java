package com.sitemonitor.service.evalstate;

import com.sitemonitor.service.EscalationService;
import com.sitemonitor.service.monitoring.ManualEvalInbox;

import java.util.List;

/**
 * Alarm türü → izleme türü eşlemesi (2026-10-23, çok pod Sürüm 3-1, paket A1; tasarım R3-1 §2.7). Saf, durumsuz; asla
 * fırlatmaz. {@code monitor_confirmations.monitor_type} sütunu ve doğrulamanın {@code descriptor} türü bunu kullanır:
 * 3-2 / 3-3 bir teyit zincirini satırdan yeniden kurarken ({@code SchedulerService.rebuildRecheck}) hangi izleme
 * tablosuna / yeniden kontrol yöntemine gideceğini bu adla seçer.
 *
 * <p>Kural, {@code SchedulerService.isStillMonitored}'ın önek dağıtımının AYNASIDIR (değişirse ikisi birlikte):
 * {@code ACCESSIBILITY → uptime} (envanter kaynaklı erişilebilirlik), {@code PORT_* → port}, {@code DNS_* → dns},
 * {@code KEYWORD* → keyword}, {@code PING_* → ping}, {@code HTTP_* | DOMAIN_EXPIRY → http},
 * {@code PAGESPEED_* → pagespeed} ({@code PAGE_}'den ÖNCE), {@code PAGE_* → page}, {@code SCRIPTED_* → scripted},
 * {@code DOMAINMON_* → domain}. Adlar {@code ManualEvalInbox.TYPE_*} ile aynı (+ {@link #UPTIME}) — KALICI (satırlarda
 * saklanır). Bilinmeyen tür → {@code null} (zincir 3-2'de sürdürülemez, doğrulama {@code descriptor} olarak sayar).
 *
 * <p>Kapı: {@code RecheckRefsCoverageTest} — {@code EscalationService.MONITORING_ALERT_TYPES}'ın her üyesi eşlenir; yeni
 * bir izleme alarm türü eşlemesi eklenene dek derlemeyi düşürür ("{@code HTTP_SLOW}'un geçtiği her yere ekle" kuralı).
 */
public final class RecheckRefs {

    private RecheckRefs() {}

    /** Envanter kaynaklı ACCESSIBILITY (uptime taraması) — gelen kutusunda karşılığı yok, kalıcı ad. */
    public static final String UPTIME = "uptime";

    /** Bilinen tüm izleme türleri (eşlemenin değer kümesi). */
    public static final List<String> MONITOR_TYPES = List.of(UPTIME, ManualEvalInbox.TYPE_PORT, ManualEvalInbox.TYPE_DNS,
            ManualEvalInbox.TYPE_KEYWORD, ManualEvalInbox.TYPE_PING, ManualEvalInbox.TYPE_HTTP, ManualEvalInbox.TYPE_PAGE,
            ManualEvalInbox.TYPE_PAGESPEED, ManualEvalInbox.TYPE_SCRIPTED, ManualEvalInbox.TYPE_DOMAIN);

    /**
     * Alarm türünün izleme türü ({@link #MONITOR_TYPES}'tan biri) ya da tanınmıyorsa {@code null}.
     *
     * @param alertType {@code EscalationService.TYPE_*} değeri (null / boş → null)
     */
    public static String monitorType(String alertType) {
        if (alertType == null || alertType.isEmpty()) return null;
        String t = alertType;
        if (EscalationService.TYPE_ACCESSIBILITY.equals(t)) return UPTIME;
        if (t.startsWith("HTTP_") || EscalationService.TYPE_DOMAIN_EXPIRY.equals(t)) return ManualEvalInbox.TYPE_HTTP;
        if (t.startsWith("PORT_")) return ManualEvalInbox.TYPE_PORT;
        if (t.startsWith("DNS_")) return ManualEvalInbox.TYPE_DNS;
        if (t.startsWith("KEYWORD")) return ManualEvalInbox.TYPE_KEYWORD;
        if (t.startsWith("PING_")) return ManualEvalInbox.TYPE_PING;
        if (t.startsWith("PAGESPEED_")) return ManualEvalInbox.TYPE_PAGESPEED;
        if (t.startsWith("PAGE_")) return ManualEvalInbox.TYPE_PAGE;
        if (t.startsWith("SCRIPTED_")) return ManualEvalInbox.TYPE_SCRIPTED;
        if (t.startsWith("DOMAINMON_")) return ManualEvalInbox.TYPE_DOMAIN;
        return null;
    }
}
