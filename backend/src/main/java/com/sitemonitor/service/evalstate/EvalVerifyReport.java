package com.sitemonitor.service.evalstate;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bir doğrulama turunun sonucu (2026-10-23, çok pod Sürüm 3-1 P0; tasarım R3-1 §2.6). Üreten: {@code EvalStateVerifier}
 * (paket A2, {@link EvalStateVerification}); saklayan: kaydedicinin {@link EvalStateSink.Status#lastVerify()} (A1);
 * yayımlanan: {@code cluster_observations} adı {@link #OBSERVATION} (yük = bu kaydın JSON'u, uygulamanın
 * {@code SNAKE_CASE} {@code ObjectMapper}'ı ile, ≤ 4000 karakter); okuyan: Sistem Sağlığı Küme kartı ve POD ölçüleri (B).
 *
 * <p><b>Bilinmeyen ≠ sıfır:</b> okuma başarısız / boşaltma gecikti / kuşak yok → {@link #STATUS_UNKNOWN} (sayılar o
 * turda anlamsızdır; ekran "Bilinmiyor" der, asla "0 uyuşmazlık" ya da yeşil değil).
 *
 * @param atMs            tur anı (epoch ms)
 * @param pod             turu koşan pod'un örnek kimliği ({@code SchedulerService.instanceId()})
 * @param generation      karşılaştırılan kuşak (0 = yok)
 * @param status          {@link #STATUS_OK} | {@link #STATUS_MISMATCH} | {@link #STATUS_UNKNOWN}
 * @param reason          yalnız {@link #STATUS_UNKNOWN}'da: {@link #REASON_FLUSH_LAG}, {@link #REASON_READ_FAILED},
 *                        {@link #REASON_NO_GENERATION}, {@link #REASON_SNAPSHOT_FAILED} ya da başka kısa kod; aksi null
 * @param kinds           tür → sonuç (sıra korunur; bilinen türler {@link #KINDS})
 * @param firstMismatches en çok {@link #MAX_KEYS} uyuşmaz anahtar, {@code "<tür>:<EvalStateKeys.shortHash(anahtar)>"} —
 *                        açık anahtar / alan adı / bağlam ASLA
 */
public record EvalVerifyReport(long atMs, String pod, long generation, String status, String reason,
                               Map<String, KindResult> kinds, List<String> firstMismatches) {

    /** {@code cluster_observations.name} — kalıcı. */
    public static final String OBSERVATION = com.sitemonitor.service.health.ClusterHealthStore.OBS_EVAL_STATE_VERIFY;

    public static final String STATUS_OK = "ok";
    public static final String STATUS_MISMATCH = "mismatch";
    public static final String STATUS_UNKNOWN = "unknown";

    public static final String REASON_FLUSH_LAG = "flush_lag";
    public static final String REASON_READ_FAILED = "read_failed";
    public static final String REASON_NO_GENERATION = "no_generation";
    public static final String REASON_SNAPSHOT_FAILED = "snapshot_failed";
    /**
     * Tur sürerken liderlik dönemi değişti, kuşak bırakıldı (kip kapandı / toplu düşüş) ya da pod çitlendi — karşılaştırma
     * penceresi geçersiz (2026-10-23, R3-1 FX-STATE). Böyle bir rapor yalnız bu pod hâlâ kuşak sahibiyse yayımlanır —
     * pratikte hiç (yalnız bu pod'un {@code lastVerify}'ında kalır).
     */
    public static final String REASON_LEADERSHIP_CHANGED = "leadership_changed";
    /**
     * Geçti / kaldı türlerinin HİÇBİRİNDE karşılaştırılan anahtar yok ama atlanan var (ör. toplu düşüşten hemen sonra, kirli
     * küme taşması) — "0 uyuşmazlık" denemez (R3-1 FX-STATE). Bu raporda {@link #kinds} atlama sayılarını taşır.
     */
    public static final String REASON_NOTHING_COMPARED = "nothing_compared";

    /** Karşılaştırma türleri — adlar kalıcı (ölçü etiketi {@code kind}). */
    public static final String KIND_RECOVERY = "recovery";
    public static final String KIND_SIBLING = "sibling";
    public static final String KIND_SUPPRESSION = "suppression";
    public static final String KIND_ROUND_SEEN = "round_seen";
    public static final String KIND_CONFIRMATION = "confirmation";
    public static final String KIND_DESCRIPTOR = "descriptor";
    public static final String KIND_WINDOW_SEED = "window_seed";

    /** Geçti / kaldı türleri: herhangi birinde uyuşmazlık > 0 → {@link #STATUS_MISMATCH}. */
    public static final List<String> PASS_FAIL_KINDS =
            List.of(KIND_RECOVERY, KIND_SIBLING, KIND_SUPPRESSION, KIND_ROUND_SEEN, KIND_CONFIRMATION);
    /** Yalnız ÖLÇÜLEN türler (3-2 kararına kanıt; tur durumunu değiştirmez). */
    public static final List<String> MEASURED_KINDS = List.of(KIND_DESCRIPTOR, KIND_WINDOW_SEED);
    /** Tüm türler, gösterim sırasıyla. */
    public static final List<String> KINDS = List.of(KIND_RECOVERY, KIND_SIBLING, KIND_SUPPRESSION, KIND_ROUND_SEEN,
            KIND_CONFIRMATION, KIND_DESCRIPTOR, KIND_WINDOW_SEED);

    /** {@link #firstMismatches} üst sınırı. */
    public static final int MAX_KEYS = 5;

    /**
     * Bir türün sonucu.
     *
     * @param compared   karşılaştırılan anahtar (window_seed: tür; descriptor: RUNNING satır) sayısı
     * @param mismatches uyuşmazlık (descriptor: sürdürülemeyecek satır; window_seed: bastırma kararı farklı çıkan tür)
     * @param skipped    atlanan anahtar (anlık görüntü sırasında değişen / kirli / bakım sınırını aşan)
     * @param maxDelta   yalnız window_seed: türler arasında en büyük {@code |Δoran|} (0..1); diğerlerinde null
     */
    public record KindResult(int compared, int mismatches, int skipped, Double maxDelta) {}

    public EvalVerifyReport {
        kinds = kinds == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(kinds));
        firstMismatches = firstMismatches == null ? List.of()
                : List.copyOf(firstMismatches.size() > MAX_KEYS ? firstMismatches.subList(0, MAX_KEYS) : firstMismatches);
    }

    /** Geçti / kaldı türlerindeki toplam uyuşmazlık ({@link #STATUS_UNKNOWN} turunda anlamsız). */
    public int passFailMismatches() {
        int n = 0;
        for (String k : PASS_FAIL_KINDS) {
            KindResult r = kinds.get(k);
            if (r != null) n += Math.max(0, r.mismatches());
        }
        return n;
    }

    /** Tur bilinmiyor mu (okuma / boşaltma / kuşak sorunu). */
    public boolean unknown() {
        return STATUS_UNKNOWN.equals(status);
    }

    /** Türlerin sayılarından durum: uyuşmazlık varsa {@link #STATUS_MISMATCH}, yoksa {@link #STATUS_OK}. */
    public static String statusOf(Map<String, KindResult> kinds) {
        if (kinds != null) {
            for (String k : PASS_FAIL_KINDS) {
                KindResult r = kinds.get(k);
                if (r != null && r.mismatches() > 0) return STATUS_MISMATCH;
            }
        }
        return STATUS_OK;
    }

    /** {@link #STATUS_UNKNOWN} turu (sayı yok). */
    public static EvalVerifyReport unknown(long atMs, String pod, long generation, String reason) {
        return new EvalVerifyReport(atMs, pod, generation, STATUS_UNKNOWN, reason, Map.of(), List.of());
    }
}
