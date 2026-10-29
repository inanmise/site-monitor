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
            case CERT_SWEEP -> (en
                    ? "Last certificate result " + f.get("hours_since") + " h ago (limit " + f.get("limit_hours") + " h)."
                    : "Son sertifika sonucu " + f.get("hours_since") + " sa önce (sınır " + f.get("limit_hours") + " sa).")
                    + certChunks(f, en);
            case NOTIFICATIONS -> en
                    ? f.get("failed") + " of " + f.get("attempted") + " e-mails failed in the last " + f.get("window_minutes") + " min."
                    : "Son " + f.get("window_minutes") + " dk'da " + f.get("attempted") + " e-postanın " + f.get("failed") + "'i gönderilemedi.";
            case PUSH_OUTBOX -> {
                boolean open = Boolean.TRUE.equals(f.get("circuit_open"));
                StringBuilder base = new StringBuilder(en
                        ? f.get("stuck") + " pushes overdue by more than " + f.get("stuck_minutes") + " min."
                        : f.get("stuck") + " push " + f.get("stuck_minutes") + " dk'dan uzun süredir gecikmede.");
                long failed = f.get("recent_failed") instanceof Number n ? n.longValue() : 0;
                long sent = f.get("recent_sent") instanceof Number n ? n.longValue() : 0;
                if (failed + sent > 0) {
                    base.append(en
                            ? " " + failed + " of " + (failed + sent) + " pushes failed in the last " + f.get("outcome_window_minutes") + " min."
                            : " Son " + f.get("outcome_window_minutes") + " dk'da " + (failed + sent) + " push'un " + failed + "'i gönderilemedi.");
                }
                if (open) {
                    String pod = podOf(f);
                    base.append(en
                            ? " For information: the push gateway circuit breaker is open on the pod that ran this round (" + pod
                                    + "); the decision uses cluster-wide push outcomes."
                            : " Bilgi: bu turu değerlendiren pod'da (" + pod + ") push ağ geçidi devre kesici açık; karar küme genelindeki "
                                    + "push sonuçlarına göre verilir.");
                }
                yield base.toString();
            }
            case DATABASE -> database(f, en);
            case CLOCK_SKEW -> clockSkew(f, en);
            case CHECK_QUEUE -> checkQueue(f, en);
        };
    }

    /**
     * Parçalı sertifika taraması olguları (çok pod R3-4, yalnız anahtar açıkken eklenir — kapalıyken cümle 20.170.0 ile AYNI):
     * açık tur yaşı, en eski bekleyen parça, son 24 saatte terk edilen tur; okunamadıysa bunu söyler. Bilgidir — karar
     * {@code latest_checks} tazeliğidir.
     */
    private static String certChunks(Map<String, Object> f, boolean en) {
        if (!Boolean.TRUE.equals(f.get("chunked"))) return "";
        if ("unreadable".equals(f.get("chunked_status"))) {
            return en ? " Chunked sweep is on; its run state could not be read." : " Parçalı tarama açık; tur durumu okunamadı.";
        }
        StringBuilder sb = new StringBuilder(en ? " Chunked sweep is on: " : " Parçalı tarama açık: ");
        if (f.get("chunked_run_age_s") instanceof Number age) {
            sb.append(en ? "open run " + age.longValue() + " s old" : "açık tur " + age.longValue() + " sn'dir sürüyor");
            if (f.get("oldest_pending_chunk_s") instanceof Number p) {
                sb.append(en ? ", oldest waiting chunk " + p.longValue() + " s" : ", en eski bekleyen parça " + p.longValue() + " sn");
            }
        } else if (Boolean.TRUE.equals(f.get("chunked_orphan_run"))) {
            sb.append(en ? "no open run (an abandoned run of a coordinator that no longer holds the sweep lock is still recorded; the next run closes it)"
                    : "açık tur yok (tarama kilidini artık tutmayan bir koordinatörün terk edilmiş turu kayıtlı; sonraki tur kapatır)");
        } else {
            sb.append(en ? "no open run" : "açık tur yok");
        }
        long ab = num(f.get("abandoned_runs_24h"));
        boolean atLeast = Boolean.TRUE.equals(f.get("abandoned_runs_24h_lower_bound"));
        sb.append(en ? "; abandoned runs in 24 h: " + (atLeast ? "at least " : "") + ab + "."
                : "; son 24 saatte terk edilen tur: " + (atLeast ? "en az " : "") + ab + ".");
        return sb.toString();
    }

    /** Dağıtık kontrol kuyruğu (R3-3): sayılar, en eski yaşlar, kirası dolmuş sahiplenme, canlı işçi. */
    private static String checkQueue(Map<String, Object> f, boolean en) {
        long pending = num(f.get("pending")), claimed = num(f.get("claimed")), done = num(f.get("done"));
        long expired = num(f.get("expired_claims"));
        Object limit = f.get("limit_seconds");
        StringBuilder sb = new StringBuilder(en
                ? "Queue: " + pending + " pending, " + claimed + " running, " + done + " waiting for evaluation."
                : "Kuyruk: " + pending + " bekleyen, " + claimed + " koşan, " + done + " değerlendirme bekleyen.");
        if (f.get("oldest_pending_seconds") instanceof Number n) {
            sb.append(en ? " Oldest pending job: " + n.longValue() + " s" : " En eski bekleyen iş: " + n.longValue() + " sn");
            sb.append(en ? " (limit " + limit + " s)." : " (sınır " + limit + " sn).");
        }
        if (f.get("oldest_done_seconds") instanceof Number n) {
            sb.append(en ? " Oldest unevaluated result: " + n.longValue() + " s (limit " + limit + " s)."
                    : " En eski değerlendirilmeyen sonuç: " + n.longValue() + " sn (sınır " + limit + " sn).");
        }
        if (expired > 0) {
            sb.append(en ? " " + expired + " claim(s) past their lease (a worker stopped mid-check)."
                    : " Kirası dolmuş " + expired + " sahiplenme var (bir işçi kontrolün ortasında durdu).");
        }
        if (f.get("live_workers") instanceof Number n) {
            sb.append(en ? " Live workers: " + n.intValue() + "." : " Canlı işçi: " + n.intValue() + ".");
        }
        return sb.toString();
    }

    private static long num(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }

    private static String database(Map<String, Object> f, boolean en) {
        StringBuilder sb = new StringBuilder(en ? "Database status: " + f.get("status") + "." : "Veritabanı durumu: " + f.get("status") + ".");
        if (f.get("pool_saturated_pods") instanceof List<?> l && !l.isEmpty()) {
            sb.append(en ? " Connection pool full / waiting for a connection on: " : " Bağlantı havuzu dolu / bağlantı bekleyen pod: ")
                    .append(join(l)).append('.');
        }
        if (f.get("schema_failed_pods") instanceof List<?> l && !l.isEmpty()) {
            sb.append(en ? " Schema patches failed at the start of: " : " Açılışta şema yaması başarısız olan pod: ")
                    .append(join(l)).append('.');
        }
        String pod = podOf(f);
        if ("evaluating_pod".equals(f.get("decision_source"))) {
            sb.append(en
                    ? " No per-pod samples were available, so the pool and schema patches of the evaluating pod (" + pod + ") decided."
                    : " Pod örneği olmadığından havuz ve şema yaması için değerlendiren pod'un (" + pod + ") kendi bilgisi kullanıldı.");
        } else if (f.get("evaluating_pod_schema_failed") instanceof Number n && n.intValue() > 0
                && !(f.get("schema_failed_pods") instanceof List<?>)) {
            sb.append(en
                    ? " For information: " + n.intValue() + " schema patch(es) failed at the start of the evaluating pod (" + pod + ")."
                    : " Bilgi: değerlendiren pod'un (" + pod + ") açılışında " + n.intValue() + " şema yaması başarısız oldu.");
        }
        return sb.toString();
    }

    private static String clockSkew(Map<String, Object> f, boolean en) {
        long unknown = f.get("unknown_pods") instanceof Number n ? n.longValue() : 0;
        String tail = unknown > 0 ? (en ? " Not measured on " + unknown + " pod(s)." : " " + unknown + " pod'da ölçülmedi.") : "";
        if (!(f.get("spread_ms") instanceof Number sp)) return (en ? "No clock measurement." : "Saat ölçümü yok.") + tail;
        long spread = sp.longValue();
        String thresholds = en
                ? " (warning above " + f.get("warn_seconds") + " s, serious above " + f.get("problem_seconds") + " s)"
                : " (uyarı " + f.get("warn_seconds") + " sn, ciddi " + f.get("problem_seconds") + " sn üstü)";
        String main;
        Object minPod = f.get("min_pod"), maxPod = f.get("max_pod");
        if (minPod == null || maxPod == null) {
            main = en ? "Only one pod's clock was measured; there is no difference between pods."
                    : "Yalnız bir pod'un saati ölçüldü; pod'lar arasında fark yok.";
        } else if (spread <= WatchdogProbes.SKEW_WARN_MS) {
            main = en ? "The pods' clocks agree (largest difference " + seconds(spread, true) + ")."
                    : "Pod'ların saatleri birbiriyle uyumlu (en büyük fark " + seconds(spread, false) + ").";
        } else {
            main = en ? "The clocks of " + maxPod + " and " + minPod + " differ by " + seconds(spread, true) + thresholds + "."
                    : maxPod + " ile " + minPod + " pod'larının saatleri arasında " + seconds(spread, false) + " fark var" + thresholds + ".";
        }
        String db = "";
        if (f.get("db_offset_ms") instanceof Number off && Math.abs(off.longValue()) > WatchdogProbes.SKEW_WARN_MS) {
            long o = off.longValue();
            // o = pod − veritabanı: pozitif → pod'lar ileride, yani veritabanı geride
            db = en ? " For information: the database clock is " + seconds(o, true) + (o > 0 ? " behind" : " ahead of")
                    + " the pods (leases and locks use the pod clocks)."
                    : " Bilgi: veritabanı saati pod'lardan " + seconds(o, false) + (o > 0 ? " geride" : " ileride")
                    + " (kira ve kilitler pod saatini kullanır).";
        }
        return main + db + tail;
    }

    private static String join(List<?> l) {
        List<String> names = new ArrayList<>();
        for (Object o : l) names.add(String.valueOf(o));
        return String.join(", ", names);
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
            case "distributed_off" -> en ? "Not applicable: distributed checks are off (every type runs on the sweep leader)."
                    : "Uygulanmaz: dağıtık kontroller kapalı (bütün türler tarama liderinde koşar).";
            case "no_check_store" -> en ? "Not applicable: the check job queue is not available on this pod."
                    : "Uygulanmaz: kontrol işi kuyruğu bu pod'da yok.";
            case "overview_unavailable", "probe_failed" -> en ? "Could not be measured this round." : "Bu turda ölçülemedi.";
            default -> en ? "Not applicable." : "Uygulanmaz.";
        };
    }

    private static long mins(Object seconds) {
        return seconds instanceof Number n ? Math.max(0, n.longValue() / 60) : 0;
    }
}
