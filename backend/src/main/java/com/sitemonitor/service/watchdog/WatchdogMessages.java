package com.sitemonitor.service.watchdog;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Kontrol olgularından okunur ayrıntı cümlesi (2026-10-11) — e-posta, webhook, push ve yönetici ekranı AYNI metni
 * kullanır. Yalnız sayılar ve tür kodları; kayıt adı / adres / hata metni yok.
 */
public final class WatchdogMessages {

    private WatchdogMessages() {}

    public static String detail(WatchdogCheck c, Map<String, Object> f, boolean en) {
        if (c == null || f == null) return "";
        Object reason = f.get("reason");
        if (reason != null) return reasonText(String.valueOf(reason), en);
        return switch (c) {
            case SWEEP_LEADER -> en
                    ? "The leadership lease expired " + mins(f.get("expired_seconds")) + " min ago and no pod has taken it."
                    : "Liderlik kirası " + mins(f.get("expired_seconds")) + " dk önce doldu ve hiçbir pod devralmadı.";
            case MONITOR_SWEEP -> {
                List<String> parts = new ArrayList<>();
                if (f.get("late_types") instanceof List<?> l) for (Object o : l) {
                    if (o instanceof Map<?, ?> m) parts.add(m.get("type") + " " + m.get("stale") + "/" + m.get("active"));
                }
                yield parts.isEmpty()
                        ? (en ? "Every monitor type is checked on time." : "Tüm izleme türleri zamanında kontrol ediliyor.")
                        : (en ? "Monitors not checked on time (late/active): " : "Zamanında kontrol edilmeyen izlemeler (gecikmiş/etkin): ")
                                + String.join(", ", parts);
            }
            case CERT_SWEEP -> en
                    ? "Last certificate result " + f.get("hours_since") + " h ago (limit " + f.get("limit_hours") + " h)."
                    : "Son sertifika sonucu " + f.get("hours_since") + " sa önce (sınır " + f.get("limit_hours") + " sa).";
            case NOTIFICATIONS -> en
                    ? f.get("failed") + " of " + f.get("attempted") + " e-mails failed in the last " + f.get("window_minutes") + " min."
                    : "Son " + f.get("window_minutes") + " dk'da " + f.get("attempted") + " e-postanın " + f.get("failed") + "'i gönderilemedi.";
            case PUSH_OUTBOX -> {
                boolean open = Boolean.TRUE.equals(f.get("circuit_open"));
                String base = en
                        ? f.get("stuck") + " pushes overdue by more than " + f.get("stuck_minutes") + " min."
                        : f.get("stuck") + " push " + f.get("stuck_minutes") + " dk'dan uzun süredir gecikmede.";
                yield open ? base + (en ? " The push gateway circuit breaker is open." : " Push ağ geçidi devre kesici açık.") : base;
            }
            case DATABASE -> en ? "Database status: " + f.get("status") + "." : "Veritabanı durumu: " + f.get("status") + ".";
        };
    }

    static String reasonText(String reason, boolean en) {
        return switch (reason) {
            case "no_cluster_store", "no_lease" -> en ? "Not applicable: cluster scheduling is not in use." : "Uygulanmaz: küme zamanlaması kullanılmıyor.";
            case "no_active_inventory" -> en ? "Not applicable: no active network certificates." : "Uygulanmaz: etkin ağ sertifikası yok.";
            case "never_checked" -> en ? "Not applicable: no certificate result yet." : "Uygulanmaz: henüz sertifika sonucu yok.";
            case "push_disabled" -> en ? "Not applicable: personal push is off." : "Uygulanmaz: kişisel push kapalı.";
            case "overview_unavailable", "probe_failed" -> en ? "Could not be measured this round." : "Bu turda ölçülemedi.";
            default -> en ? "Not applicable." : "Uygulanmaz.";
        };
    }

    private static long mins(Object seconds) {
        return seconds instanceof Number n ? Math.max(0, n.longValue() / 60) : 0;
    }
}
