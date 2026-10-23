package com.sitemonitor.service.watchdog;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Kontrol olgularından okunur ayrıntı cümlesi (2026-10-11) — e-posta, webhook, push ve yönetici ekranı AYNI metni
 * kullanır. Yalnız sayılar, tür kodları ve pod adları; kayıt adı / adres / hata metni yok.
 *
 * <p>2026-10-23 (çok pod R2b, MP-36): turu değerlendiren pod'a özgü olgular (push devre kesicisi, o pod'un havuzu, açılış
 * şema yaması) cümlede "bilgi" olarak ve pod adıyla geçer — karar küme genelindeki verilere göredir.
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
                if (!open) yield base;
                String pod = podOf(f);
                yield base + (en
                        ? " For information: the push gateway circuit breaker is open on the pod that ran this round (" + pod
                                + "); the decision uses the cluster-wide overdue count."
                        : " Bilgi: bu turu değerlendiren pod'da (" + pod + ") push ağ geçidi devre kesici açık; karar küme genelindeki "
                                + "geciken satır sayısına göre verilir.");
            }
            case DATABASE -> database(f, en);
            case CLOCK_SKEW -> clockSkew(f, en);
        };
    }

    private static String database(Map<String, Object> f, boolean en) {
        StringBuilder sb = new StringBuilder(en ? "Database status: " + f.get("status") + "." : "Veritabanı durumu: " + f.get("status") + ".");
        if (f.get("pool_saturated_pods") instanceof List<?> l && !l.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (Object o : l) names.add(String.valueOf(o));
            sb.append(en ? " Connection pool full / waiting for a connection on: " : " Bağlantı havuzu dolu / bağlantı bekleyen pod: ")
                    .append(String.join(", ", names)).append('.');
        }
        if (f.get("evaluating_pod_schema_failed") instanceof Number n && n.intValue() > 0) {
            String pod = podOf(f);
            sb.append(en
                    ? " For information: " + n.intValue() + " schema patch(es) failed at the start of the evaluating pod (" + pod + ")."
                    : " Bilgi: değerlendiren pod'un (" + pod + ") açılışında " + n.intValue() + " şema yaması başarısız oldu.");
        }
        return sb.toString();
    }

    private static String clockSkew(Map<String, Object> f, boolean en) {
        Object worst = f.get("worst_pod");
        Object skew = f.get("worst_skew_ms");
        long unknown = f.get("unknown_pods") instanceof Number n ? n.longValue() : 0;
        String tail = unknown > 0 ? (en ? " Not measured on " + unknown + " pod(s)." : " " + unknown + " pod'da ölçülmedi.") : "";
        String thresholds = en
                ? " (warning above " + f.get("warn_seconds") + " s, serious above " + f.get("problem_seconds") + " s)"
                : " (uyarı " + f.get("warn_seconds") + " sn, ciddi " + f.get("problem_seconds") + " sn üstü)";
        if (worst == null || !(skew instanceof Number s)) return (en ? "No clock measurement." : "Saat ölçümü yok.") + tail;
        long ms = s.longValue();
        String value = seconds(ms, en);
        if (Math.abs(ms) <= WatchdogProbes.SKEW_WARN_MS) {
            return (en ? "Every pod's clock matches the database clock (largest difference " + value + " on " + worst + ")."
                    : "Tüm pod'ların saati veritabanı saatiyle uyumlu (en büyük fark " + worst + ": " + value + ").") + tail;
        }
        String dir = ms > 0 ? (en ? "ahead of" : "ileride") : (en ? "behind" : "geride");
        return (en ? "The clock of " + worst + " is " + value + " " + dir + " the database clock" + thresholds + "."
                : worst + " pod'unun saati veritabanı saatinden " + value + " " + dir + thresholds + ".") + tail;
    }

    /** Değerlendiren pod adı (olgu yoksa "?"). */
    private static String podOf(Map<String, Object> f) {
        Object p = f.get("evaluated_by");
        return p == null || String.valueOf(p).isBlank() ? "?" : String.valueOf(p);
    }

    /** ms → işaretsiz saniye, bir ondalık ("12,3 sn" / "12.3 s"). */
    static String seconds(long ms, boolean en) {
        double s = Math.abs(ms) / 1000.0;
        String n = String.format(en ? Locale.ROOT : Locale.forLanguageTag("tr"), "%.1f", s);
        return n + (en ? " s" : " sn");
    }

    static String reasonText(String reason, boolean en) {
        return switch (reason) {
            case "no_cluster_store", "no_lease" -> en ? "Not applicable: cluster scheduling is not in use." : "Uygulanmaz: küme zamanlaması kullanılmıyor.";
            case "no_active_inventory" -> en ? "Not applicable: no active network certificates." : "Uygulanmaz: etkin ağ sertifikası yok.";
            case "never_checked" -> en ? "Not applicable: no certificate result yet." : "Uygulanmaz: henüz sertifika sonucu yok.";
            case "push_disabled" -> en ? "Not applicable: personal push is off." : "Uygulanmaz: kişisel push kapalı.";
            case "metrics_disabled" -> en ? "Not applicable: the system metrics sampler is off (no per-pod samples)."
                    : "Uygulanmaz: sistem metrikleri örnekleyicisi kapalı (pod örneği yok).";
            case "no_live_samples" -> en ? "Could not be measured: no live pod has written a sample in the last minutes."
                    : "Ölçülemedi: son dakikalarda örnek yazan canlı pod yok.";
            case "skew_unknown" -> en ? "Could not be measured: the live pods have not reported their clock difference yet (older version or first minute)."
                    : "Ölçülemedi: canlı pod'lar saat farkını henüz bildirmedi (eski sürüm ya da ilk dakika).";
            case "overview_unavailable", "probe_failed" -> en ? "Could not be measured this round." : "Bu turda ölçülemedi.";
            default -> en ? "Not applicable." : "Uygulanmaz.";
        };
    }

    private static long mins(Object seconds) {
        return seconds instanceof Number n ? Math.max(0, n.longValue() / 60) : 0;
    }
}
