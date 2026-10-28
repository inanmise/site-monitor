package com.sitemonitor.service.evalstate;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Bir devirde kalıcı değerlendirme durumunun yüklenip yüklenmediğinin raporu (2026-10-28, çok pod Sürüm 3-2 P0; tasarım
 * R3-2 §3.5 / §8). Üreten: {@code MonitoringOutageService} (paket B — uygulama, atma; {@link EvalStateSource#lastEvalLoad})
 * ve kaydedici / yükleyici (paket A — süreklilik ya da okuma yüzünden yüklenmediyse {@link EvalStateSource#evalLoadSkipped}).
 * Yayımlanan: {@code cluster_observations} adı {@link #OBSERVATION} (yük = {@link #payload()}, SNAKE_CASE JSON, HER ZAMAN
 * geçerli ve ≤ {@link #PAYLOAD_MAX}; ayar KAPALIYKEN ({@link #REASON_SETTING_OFF}) yayımlanmaz — J-1). Okuyan: Sistem Sağlığı
 * Küme kartı (paket C) — {@link #fromJson} / {@link #asMap()}.
 *
 * <p><b>Sonuç:</b> {@link #OUTCOME_FULL} | {@link #OUTCOME_OBSERVATIONS} | {@link #OUTCOME_NONE} (= yeniden başlatma
 * semantiği, bugünkü davranış). {@code reason}: NONE'da neden yüklenmediği; OBSERVATIONS'da neden TAM olmadığı
 * ({@link #OBSERVATIONS_REASONS}); FULL'da null. Kodlar KALICIDIR ({@link #REASONS}; ekran TR / EN {@code hmp.esLoad.reason.*},
 * kapı {@code clusterRuntimeModel.test.js} iki yönlü eşitlik).
 *
 * <p><b>"pod" alanları örnek kimliğidir</b> ({@code SchedulerService.instanceId()}, ör. {@code sitemonitor-a-0000000a}) —
 * {@link EvalVerifyReport#pod()} gibi; ekran pod adını {@code ClusterHealthStore.podOf} ile türetir. Açık anahtar / alan adı /
 * kullanıcı verisi ASLA taşınmaz — yalnız sayılar.
 *
 * <p>Kurucu metinleri sütun / yük sınırına kırpar (pod kimlikleri {@value #ID_MAX}, sonuç / katman {@value #CODE_MAX}, neden
 * {@value #REASON_MAX} karakter) ve negatif sayıları 0 yapar — bu yüzden JSON en kötü durumda da {@link #PAYLOAD_MAX}'ın çok
 * altındadır (kapı {@code EvalStateContractsTest}).
 *
 * @param atMs           rapor anı (epoch ms; yayımlanırken "en yeni kazanır" sırası)
 * @param pod            raporu yazan pod'un örnek kimliği (yükleyen / atan yeni lider)
 * @param outcome        {@link #OUTCOME_FULL} | {@link #OUTCOME_OBSERVATIONS} | {@link #OUTCOME_NONE}
 * @param reason         {@link #REASONS}'dan bir kod (FULL'da null)
 * @param attemptedTier  denenen katman (süreklilik kararından sonra okuma / uygulama düştüyse {@code full} | {@code observations});
 *                       süreklilik reddettiyse null
 * @param fromGeneration önceki sahibin kuşağı (bilinmiyorsa null)
 * @param fromPod        önceki sahibin örnek kimliği (bilinmiyorsa null)
 * @param handoverAgeMs  devir işaretinin yaşı (yalnız temiz devirde; null = yok)
 * @param durableAgeMs   önceki sahibin son tam ve kalıcı yazımının yaşı (null = bilinmiyor)
 * @param recoveryCounters yüklenen kurtarma sayacı sayısı (NONE'da 0)
 * @param siblings       yüklenen DOWN kardeş sayısı
 * @param suppressions   yüklenen açık bastırma sayısı
 * @param roundSeen      yüklenen "tur görüldü" tür sayısı
 * @param windowTypes    pencere tohumundaki tür sayısı
 * @param windowDomains  pencere tohumundaki (tür × alan adı) işaret sayısı
 * @param readMs         okuma süresi (ms)
 * @param maxWaitMs      yükleme kapısında bekleyen değerlendirmelerin en uzun bekleyişi (ms)
 * @param waiters        yükleme kapısında bekleyen değerlendirme sayısı
 */
public record EvalLoadReport(long atMs, String pod, String outcome, String reason,
                             String attemptedTier, Long fromGeneration, String fromPod, Long handoverAgeMs,
                             Long durableAgeMs,
                             int recoveryCounters, int siblings, int suppressions, int roundSeen, int windowTypes,
                             int windowDomains,
                             long readMs, long maxWaitMs, int waiters) {

    /** {@code cluster_observations.name} — kalıcı (≤ 60 karakter). */
    public static final String OBSERVATION = "eval-state-load";

    public static final String OUTCOME_FULL = "full";
    public static final String OUTCOME_OBSERVATIONS = "observations";
    public static final String OUTCOME_NONE = "none";
    /** Sonuçlar, gösterim sırasıyla. */
    public static final List<String> OUTCOMES = List.of(OUTCOME_FULL, OUTCOME_OBSERVATIONS, OUTCOME_NONE);

    // ── Neden kodları (kalıcı) ─────────────────────────────────────────────────────────────────────────────────────
    // Arma anında (MOS GAINED dinleyicisi, bellek): ayar / kip / kaydedici
    /** Okuma ayarı ({@link EvalStateReadSwitch#KEY}) kapalı — beklenen; yayımlanmaz, günlük yazılmaz (J-1). */
    public static final String REASON_SETTING_OFF = "setting_off";
    /** Kayıt kipi {@code off} — durum yazılmıyor. */
    public static final String REASON_MODE_OFF = "mode_off";
    /** Bu pod'da kaydedici yok (ya da NOOP) — {@link EvalStateSink#loadBlocker()} varsayılanı. */
    public static final String REASON_NO_RECORDER = "no_recorder";
    /** Kaydedici kapanıyor (ContextClosedEvent). */
    public static final String REASON_RECORDER_CLOSING = "recorder_closing";
    /** Durum tabloları henüz yok (ilk açılışın ilk saniyeleri). */
    public static final String REASON_TABLES_MISSING = "tables_missing";
    // Süreklilik (EvalLoadContinuity, paket A — tasarım §2.4 sırasıyla)
    /** Devir işareti sütunları henüz yok / okunamadı (şema yaması bitmedi). */
    public static final String REASON_COLUMNS_MISSING = "columns_missing";
    /** Önceki kuşak yok ({@code prior.gen == 0} — ilk lider). */
    public static final String REASON_NO_PREVIOUS_GENERATION = "no_previous_generation";
    /** Son kayıtlı kuşak bu pod'undu — arada kaydetmeyen bir lider olabilir. */
    public static final String REASON_WRITER_IS_SELF = "writer_is_self";
    /** Önceki sahip bu kuşak için 3-2 işareti yazmadı (eski sürüm / ayar kapalıydı / arada 3-1 CAS'ı). */
    public static final String REASON_PREVIOUS_UNMARKED = "previous_unmarked";
    /** Önceki sahibin kuşağı eksikti ({@code flushed_ms} null — kip değişti / üst sınır aşıldı / eşitleme bitmedi). */
    public static final String REASON_PREVIOUS_INCOMPLETE = "previous_incomplete";
    /** Pod saatleri arasındaki fark 30 sn'den büyük (NONE'da: kalıcılık anı; OBSERVATIONS'da: devir anı). */
    public static final String REASON_CLOCK_SKEW = "clock_skew";
    /** Önceki sahibin son tam yazımı 5 dk'dan eski. */
    public static final String REASON_PREVIOUS_STALE = "previous_stale";
    /** Temiz devir yok (çökme / kira süresi doldu) — yalnız gözlemler. */
    public static final String REASON_NO_HANDOVER = "no_handover";
    /** Devir 5 dk'dan eski — yalnız gözlemler. */
    public static final String REASON_HANDOVER_TOO_OLD = "handover_too_old";
    /** Devirden sonra kaydetmeyen bir lider vardı ({@code monitor_check_schedule}) — yalnız gözlemler. */
    public static final String REASON_INTERMEDIATE_LEADER = "intermediate_leader";
    // Okuma (EvalStateLoader, paket A)
    /** Okuma başarısız ({@code DataAccessException}). */
    public static final String REASON_READ_FAILED = "read_failed";
    /** Bir okuma {@code MAX_LOAD_ROWS} üst sınırını aştı — kısmi yük ASLA. */
    public static final String REASON_TOO_LARGE = "too_large";
    // Uygulama (MOS + EvalLoadGate, paket B)
    /** Yükleme son tarihi (GAINED + 20 sn) geçti. */
    public static final String REASON_TIMEOUT = "timeout";
    /** Uçuştaki değerlendirme süresinde bitmedi. */
    public static final String REASON_EVALUATION_BUSY = "evaluation_busy";
    /** Yükleme bitmeden bir değerlendirme belleği değiştirdi — birleştirme yok, yük atıldı. */
    public static final String REASON_EVALUATED_BEFORE_LOAD = "evaluated_before_load";
    /** Yükleme sırasında liderlik değişti (belirteç eskidi). */
    public static final String REASON_LEADERSHIP_CHANGED = "leadership_changed";
    /** Ayar yükleme sırasında kapatıldı. */
    public static final String REASON_SETTING_TURNED_OFF = "setting_turned_off";

    /** Tüm neden kodları, tasarım §3.5 sırasıyla — kalıcı; ekran sözlüğü bununla iki yönlü eşit olmalı. */
    public static final List<String> REASONS = List.of(
            REASON_SETTING_OFF, REASON_MODE_OFF, REASON_NO_RECORDER, REASON_RECORDER_CLOSING, REASON_TABLES_MISSING,
            REASON_COLUMNS_MISSING, REASON_NO_PREVIOUS_GENERATION, REASON_WRITER_IS_SELF, REASON_PREVIOUS_UNMARKED,
            REASON_PREVIOUS_INCOMPLETE, REASON_CLOCK_SKEW, REASON_PREVIOUS_STALE, REASON_NO_HANDOVER,
            REASON_HANDOVER_TOO_OLD, REASON_INTERMEDIATE_LEADER, REASON_READ_FAILED, REASON_TOO_LARGE, REASON_TIMEOUT,
            REASON_EVALUATION_BUSY, REASON_EVALUATED_BEFORE_LOAD, REASON_LEADERSHIP_CHANGED, REASON_SETTING_TURNED_OFF);

    /**
     * NONE'un BEKLENEN nedenleri (tasarım §8.3: soluk ton, günlükte INFO) — kurulum / sürüm / ilk açılış / sıradan liderlik
     * değişimi.
     */
    public static final List<String> EXPECTED_REASONS = List.of(
            REASON_SETTING_OFF, REASON_MODE_OFF, REASON_NO_RECORDER, REASON_RECORDER_CLOSING, REASON_TABLES_MISSING,
            REASON_COLUMNS_MISSING, REASON_NO_PREVIOUS_GENERATION, REASON_WRITER_IS_SELF, REASON_PREVIOUS_UNMARKED,
            REASON_LEADERSHIP_CHANGED);

    /**
     * NONE'un İŞLETİM nedenleri (tasarım §8.3: uyarı tonu, kartı uyarıya yükseltir; günlükte WARN) — karar yine güvenli
     * tarafta (bugünkü davranış), ama incelenmeli.
     */
    public static final List<String> OPERATIONAL_REASONS = List.of(
            REASON_READ_FAILED, REASON_TOO_LARGE, REASON_TIMEOUT, REASON_EVALUATION_BUSY, REASON_EVALUATED_BEFORE_LOAD,
            REASON_PREVIOUS_INCOMPLETE, REASON_PREVIOUS_STALE, REASON_CLOCK_SKEW, REASON_SETTING_TURNED_OFF);

    /**
     * OBSERVATIONS sonucunun nedenleri (neden TAM değil — bilgi tonu). {@link #REASON_CLOCK_SKEW} burada da vardır (devir
     * anındaki saat farkı FULL'u düşürür; kalıcılık anındaki saat farkı NONE'a götürür).
     */
    public static final List<String> OBSERVATIONS_REASONS = List.of(
            REASON_NO_HANDOVER, REASON_HANDOVER_TOO_OLD, REASON_INTERMEDIATE_LEADER, REASON_CLOCK_SKEW);

    /** {@link #asMap()} / {@link #toJson()} alan adları (sırayla) — kalıcı yanıt / gözlem alan adları. */
    public static final Set<String> FIELDS = Collections.unmodifiableSet(new LinkedHashSet<>(List.of(
            "at_ms", "pod", "outcome", "reason", "attempted_tier", "from_generation", "from_pod", "handover_age_ms",
            "durable_age_ms", "recovery_counters", "siblings", "suppressions", "round_seen", "window_types",
            "window_domains", "read_ms", "max_wait_ms", "waiters")));

    /** Gözlem yükü sınırı ({@code ClusterHealthStore.PAYLOAD_MAX}, {@code cluster_observations.payload VARCHAR(4000)}). */
    public static final int PAYLOAD_MAX = 4000;
    /** Örnek kimliği üst sınırı ({@code ClusterHealthStore.ID_MAX}). */
    static final int ID_MAX = 120;
    /** Sonuç / katman kodu üst sınırı. */
    static final int CODE_MAX = 20;
    /** Neden kodu üst sınırı. */
    static final int REASON_MAX = 60;

    /** Gözlem yükü — uygulamanın genel kuralıyla aynı ad biçimi (SNAKE_CASE); yeni sürümün ek alanları okumayı bozmaz. */
    private static final ObjectMapper JSON = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .build();

    public EvalLoadReport {
        pod = clip(pod, ID_MAX);
        outcome = clip(outcome, CODE_MAX);
        reason = clip(reason, REASON_MAX);
        attemptedTier = clip(attemptedTier, CODE_MAX);
        fromPod = clip(fromPod, ID_MAX);
        recoveryCounters = Math.max(0, recoveryCounters);
        siblings = Math.max(0, siblings);
        suppressions = Math.max(0, suppressions);
        roundSeen = Math.max(0, roundSeen);
        windowTypes = Math.max(0, windowTypes);
        windowDomains = Math.max(0, windowDomains);
        readMs = Math.max(0L, readMs);
        maxWaitMs = Math.max(0L, maxWaitMs);
        waiters = Math.max(0, waiters);
    }

    // ── Fabrikalar ──────────────────────────────────────────────────────────────────────────────────────────────────

    /** Hiçbir şey yüklenmedi — yalnız neden (ör. arma anında {@link #REASON_SETTING_OFF} / {@link #REASON_MODE_OFF}). */
    public static EvalLoadReport none(long atMs, String pod, String reason) {
        return new EvalLoadReport(atMs, pod, OUTCOME_NONE, reason, null, null, null, null, null,
                0, 0, 0, 0, 0, 0, 0L, 0L, 0);
    }

    /**
     * Hiçbir şey yüklenmedi, ama önceki sahip biliniyor (süreklilik reddetti — {@code attemptedTier} null — ya da süreklilik
     * kabul ettikten sonra okuma düştü — {@code attemptedTier} denenen katman).
     */
    public static EvalLoadReport none(long atMs, String pod, String reason, String attemptedTier, Long fromGeneration,
                                      String fromPod, Long handoverAgeMs, Long durableAgeMs, long readMs) {
        return new EvalLoadReport(atMs, pod, OUTCOME_NONE, reason, attemptedTier, fromGeneration, fromPod, handoverAgeMs,
                durableAgeMs, 0, 0, 0, 0, 0, 0, readMs, 0L, 0);
    }

    /**
     * Okunan yük UYGULANMADI (MOS kapısı attı: {@link #REASON_TIMEOUT}, {@link #REASON_EVALUATION_BUSY},
     * {@link #REASON_EVALUATED_BEFORE_LOAD}, {@link #REASON_LEADERSHIP_CHANGED}, {@link #REASON_SETTING_TURNED_OFF}) — sonuç
     * NONE, sayılar 0 (yüklenen yok), kaynak ve okuma süresi yükün {@link EvalLoad.Meta}'sından.
     */
    public static EvalLoadReport discarded(long atMs, String pod, String reason, EvalLoad load) {
        EvalLoad.Meta m = load == null ? EvalLoad.Meta.EMPTY : load.meta();
        return new EvalLoadReport(atMs, pod, OUTCOME_NONE, reason, load == null ? null : load.tier(),
                generationOrNull(m.fromGeneration()), m.fromWriter(), m.handoverAgeMs(), m.durableAgeMs(),
                0, 0, 0, 0, 0, 0, m.readMs(), 0L, 0);
    }

    /**
     * Yük UYGULANDI — sonuç = yükün katmanı, neden = OBSERVATIONS'da {@link EvalLoad.Meta#partialReason()} (FULL'da null),
     * sayılar yükün koleksiyonlarından.
     */
    public static EvalLoadReport applied(long atMs, String pod, EvalLoad load) {
        EvalLoad.Meta m = load.meta();
        return new EvalLoadReport(atMs, pod, load.tier(), load.full() ? null : m.partialReason(), load.tier(),
                generationOrNull(m.fromGeneration()), m.fromWriter(), m.handoverAgeMs(), m.durableAgeMs(),
                load.recovery().size(), load.siblings().size(), load.suppressionActive().size(), load.roundSeen().size(),
                load.window().size(), load.windowDomains(), m.readMs(), 0L, 0);
    }

    /** Aynı rapor, yükleme kapısının bekleme ölçüleriyle (B: kapının {@code waiters} / {@code maxWaitMs} değerleri). */
    public EvalLoadReport withWaits(long maxWaitMs, int waiters) {
        return new EvalLoadReport(atMs, pod, outcome, reason, attemptedTier, fromGeneration, fromPod, handoverAgeMs,
                durableAgeMs, recoveryCounters, siblings, suppressions, roundSeen, windowTypes, windowDomains, readMs,
                maxWaitMs, waiters);
    }

    // ── Sorular ─────────────────────────────────────────────────────────────────────────────────────────────────────

    /** Bir şey yüklendi mi (FULL ya da OBSERVATIONS). */
    public boolean loaded() {
        return OUTCOME_FULL.equals(outcome) || OUTCOME_OBSERVATIONS.equals(outcome);
    }

    /**
     * İşletim nedeni mi ({@link #OPERATIONAL_REASONS}; günlükte WARN, kartta uyarı). Yalnız NONE sonucunda anlamlıdır —
     * OBSERVATIONS'daki {@link #REASON_CLOCK_SKEW} bilgi tonudur (çağıran sonuca önce bakar).
     */
    public static boolean operational(String reason) {
        return reason != null && OPERATIONAL_REASONS.contains(reason);
    }

    // ── JSON / harita ───────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * SNAKE_CASE JSON ({@code at_ms, pod, outcome, reason, attempted_tier, from_generation, from_pod, handover_age_ms,
     * durable_age_ms, recovery_counters, siblings, suppressions, round_seen, window_types, window_domains, read_ms,
     * max_wait_ms, waiters}; null alanlar {@code null} olarak yazılır).
     */
    public String toJson() {
        return JSON.writeValueAsString(this);
    }

    /**
     * Yayımlanacak gözlem yükü — HER ZAMAN geçerli JSON ve ≤ {@link #PAYLOAD_MAX}. Kurucunun kırpması yüzünden tam rapor her
     * zaman sığar; yine de sığmazsa (beklenmez) pod kimlikleri atılır, sonuç / neden / sayılar korunur.
     */
    public String payload() {
        String json = toJson();
        if (json.length() <= PAYLOAD_MAX) return json;
        return new EvalLoadReport(atMs, null, outcome, reason, attemptedTier, fromGeneration, null, handoverAgeMs,
                durableAgeMs, recoveryCounters, siblings, suppressions, roundSeen, windowTypes, windowDomains, readMs,
                maxWaitMs, waiters).toJson();
    }

    /** Gözlem yükünü okur (Sistem Sağlığı); boş / bozuk → null (asla fırlatmaz). Bilinmeyen alanlar yok sayılır. */
    public static EvalLoadReport fromJson(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return JSON.readValue(json, EvalLoadReport.class);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Yanıt haritası — {@link #toJson()} ile AYNI SNAKE_CASE adlar ve sıra (Sistem Sağlığı {@code this_pod.last_load} ile küme
     * gözlemi {@code load} aynı alan adlarını taşısın; kapı {@code EvalStateContractsTest}).
     */
    public Map<String, Object> asMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("at_ms", atMs);
        m.put("pod", pod);
        m.put("outcome", outcome);
        m.put("reason", reason);
        m.put("attempted_tier", attemptedTier);
        m.put("from_generation", fromGeneration);
        m.put("from_pod", fromPod);
        m.put("handover_age_ms", handoverAgeMs);
        m.put("durable_age_ms", durableAgeMs);
        m.put("recovery_counters", recoveryCounters);
        m.put("siblings", siblings);
        m.put("suppressions", suppressions);
        m.put("round_seen", roundSeen);
        m.put("window_types", windowTypes);
        m.put("window_domains", windowDomains);
        m.put("read_ms", readMs);
        m.put("max_wait_ms", maxWaitMs);
        m.put("waiters", waiters);
        return m;
    }

    private static Long generationOrNull(long gen) {
        return gen > 0 ? gen : null;
    }

    /** Uzun metni sınıra kırpar; vekil çifti bölmez. */
    private static String clip(String s, int max) {
        if (s == null || s.length() <= max) return s;
        int end = max;
        if (Character.isHighSurrogate(s.charAt(end - 1))) end--;
        return s.substring(0, end);
    }
}
