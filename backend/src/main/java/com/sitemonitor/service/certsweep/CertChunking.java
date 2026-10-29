package com.sitemonitor.service.certsweep;

import com.sitemonitor.service.AppSettingsChangedEvent;
import com.sitemonitor.service.AppSettingsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Parçalı sertifika taramasının ANAHTARI, pod ayarları ve tembel başlatıcısı (2026-10-29, çok pod Sürüm 3-4 P0, tasarım
 * R3-4 §6; kullanıcı kararı V-2). Tek ayar: {@value #KEY_ENABLED} — BOOL, {@code AppSettingsCatalog} grubu {@code scheduler}
 * (Ayarlar → Genel → Zamanlayıcı "Parçalı sertifika taraması (çok pod)"), GLOBAL_ONLY, 10 sn ayar yenilemesiyle CANLI,
 * VARSAYILAN KAPALI (env {@code CERT_CHUNKS_ENABLED}). 3-3'ün dağıtık kontrol anahtarından ve 3-2 okuma ayarından BAĞIMSIZ.
 *
 * <p><b>Kapalı ≡ v20.170.0 (L-1):</b> {@link #enabled()} YALNIZ BELLEKTİR ({@code AppSettingsService} geçersiz kılma haritası +
 * Environment) — veritabanı, günlük satırı, iş parçacığı yok. Okuma hatası (servis yok, istisna) = KAPALI. Koordinatör
 * ({@code CertRunCoordinator.gather}, paket B) İLK deyim olarak bunu okur ve kapalıysa {@code null} döner → klasik toplama.
 *
 * <p><b>Ayar ≠ kip.</b> {@link #enabled()} yalnız AYARI söyler. Bir turun gerçekten parçalı koşması (koordinatörün
 * {@code chunkedRunWanted}, tasarım §4.5 adım 1) ayrıca depo + işçi bean'lerini, {@link Tuning#minDomains()} kadar alan adını,
 * BAŞKA canlı bir pod'u ({@code PodLiveness.anotherLivePod()} — bilinmiyor = yok) ve kapanmıyor olmayı ister; tek pod'da ayar
 * açık olsa da tur klasik koşar.
 *
 * <p><b>Tembel başlatma (§4.4).</b> Parça işçisi (paket A) açılışta HİÇBİR ŞEY kurmaz: {@link #whenActivated} ile bir
 * başlatıcı kaydeder; bu sınıf anahtarı İLK KEZ açık gördüğünde ({@code ApplicationReadyEvent} ya da anahtara dokunan
 * {@code AppSettingsChangedEvent} — başka pod'un kaydı 10 sn yenilemesiyle) her başlatıcıyı BİR KEZ çağırır. Anahtar
 * açılıştan beri kapalıysa hiçbiri çağrılmaz (sıfır iş parçacığı, sıfır sorgu). Sonradan kapatılınca başlatılanlar durmaz —
 * kendi döngülerinde {@link #enabled()} (bellek) okuyup açık turu bitirir, sonra boşta bekler. {@code ContextClosedEvent}'ten
 * sonra etkinleşme yok (yeni iş başlamaz). Desen 3-3 {@code DistributedChecks} ile aynıdır (o sınıfa dokunulmaz).
 *
 * <p><b>Pod sayaçları.</b> İşçi (A) bu pod'un sayaçlarını {@link #registerWorkerStatus}, koordinatör (B)
 * {@link #registerCoordinatorStatus} ile kaydeder; Sistem Sağlığı / ölçümler (paket C) {@link #workerStatus()} /
 * {@link #coordinatorStatus()} ile okur (bellek; kayıt yoksa null). Anahtar kapalıyken C bunları çağırmaz
 * ({@code {enabled: false}}).
 */
@Slf4j
@Component
public class CertChunking {

    /** Ana anahtar — kalıcı. */
    public static final String KEY_ENABLED = "site.monitor.checks.cert-chunks.enabled";
    /** Ayar yoksa / okunamazsa: kapalı. */
    public static final boolean DEFAULT_ENABLED = false;

    // ── Açılışta okunan pod anahtarları (katalogda YOK — 3-3 işçi ayarları gibi; application.properties + env) ─────────
    /** Bu pod parça alır mı (env {@code CERT_CHUNK_WORKER_ENABLED}); false = parça almaz (kilidi kazanırsa yine koordinatördür). */
    public static final String KEY_WORKER_ENABLED = "site.monitor.checks.cert-chunks.worker.enabled";
    /** Parça başına alan adı (env {@code CERT_CHUNK_SIZE}), {@value Tuning#MIN_CHUNK_SIZE}–{@value Tuning#MAX_CHUNK_SIZE}; koordinatör okur. */
    public static final String KEY_CHUNK_SIZE = "site.monitor.checks.cert-chunks.chunk-size";
    /** Bundan az alan adlı tur klasik koşar (env {@code CERT_CHUNK_MIN_DOMAINS}); koordinatör okur. */
    public static final String KEY_MIN_DOMAINS = "site.monitor.checks.cert-chunks.min-domains";
    /** Aynı anda en çok kaç parça (= parça koşucu iş parçacığı) (env {@code CERT_CHUNK_MAX_CHUNKS}). */
    public static final String KEY_MAX_CHUNKS = "site.monitor.checks.cert-chunks.max-chunks";
    /** Uçuştaki alan adı bütçesi (env {@code CERT_CHUNK_MAX_INFLIGHT_DOMAINS}); 0 = 2 × görev havuzunun GÜNCEL çekirdek sayısı. */
    public static final String KEY_MAX_INFLIGHT_DOMAINS = "site.monitor.checks.cert-chunks.max-inflight-domains";
    /** Parça kirası (ms, env {@code CERT_CHUNK_LEASE_MS}); {@link Tuning#renewEveryMs()}'de bir yenilenir. */
    public static final String KEY_LEASE_MS = "site.monitor.checks.cert-chunks.lease-ms";
    /** Başka canlı pod varken tarama lideri yalnız bu kadar (ms) bekleyen parçaları alır (env {@code CERT_CHUNK_STEAL_AFTER_MS}). */
    public static final String KEY_STEAL_AFTER_MS = "site.monitor.checks.cert-chunks.steal-after-ms";
    /** Başka canlı pod varken liderin bütçe payı (%, env {@code CERT_CHUNK_LEADER_SHARE_PCT}). */
    public static final String KEY_LEADER_SHARE_PCT = "site.monitor.checks.cert-chunks.leader-share-pct";
    /** Parça uçuştayken / son sahiplenme satır döndürdüyse işçi yoklama aralığı (ms, env {@code CERT_CHUNK_POLL_MS}). */
    public static final String KEY_POLL_MS = "site.monitor.checks.cert-chunks.poll-ms";
    /** Boşta işçi yoklama aralığı (ms, env {@code CERT_CHUNK_IDLE_POLL_MS}). */
    public static final String KEY_IDLE_POLL_MS = "site.monitor.checks.cert-chunks.idle-poll-ms";

    // ── Protokol sabitleri (ayar DEĞİL; A ve B'nin ortak kuralları — tasarım §2.4–§2.6, §4.2–§4.5) ─────────────────────
    /** Bir parça işçilerce en çok bu kadar kez sahiplenilir; sonrasında koordinatör onu kendisi ölçer (§2.4). */
    public static final int MAX_ATTEMPTS = 2;
    /** Koordinatörün bekleme döngüsü aralığı (ms) — kilit / kapanış / parça durumu her turda okunur (§2.5). */
    public static final long COORDINATOR_WAIT_POLL_MS = 500L;
    /** Koordinatörün tur satırı nabzı (ms) — {@code heartbeat_ms} yazılır (§4.5 adım 3). */
    public static final long COORDINATOR_HEARTBEAT_MS = 10_000L;
    /**
     * Nabzı bundan eski koşunun parçaları SAHİPLENİLMEZ (§4.2 sahiplenme deyimi {@code r.heartbeat_ms >= :now - 60000}) —
     * ölmüş koordinatörün turu için ölçüm yapılmaz. {@link #COORDINATOR_HEARTBEAT_MS}'in 6 katı.
     */
    public static final long COORDINATOR_ALIVE_MS = 60_000L;
    /** Bu kadar (ms) ardışık depo hatasından sonra koordinatör toplanmamış parçaları kendisi ölçer (§2.5). */
    public static final long STORE_FAILURE_LOCAL_MS = 60_000L;
    /** İkinci görüş parçası bu kadar (ms) içinde sahiplenilmezse koordinatör kendisi ölçer (§2.6). */
    public static final long SECOND_OPINION_WAIT_MS = 30_000L;
    /** Bir turun en çok parça sayısı; aşılırsa parça boyutu büyür ({@link Tuning#effectiveChunkSize}, §4.5 adım 2). */
    public static final int MAX_CHUNKS_PER_RUN = 500;
    /** Bitmiş koşu satırlarının saklama süresi (ms) — her parçalı tur başında daha eskileri silinir (§4.1). */
    public static final long RUN_RETENTION_MS = 7L * 24 * 60 * 60 * 1000;
    /** Tur başı budamasının tek seferde sildiği en çok koşu satırı. */
    public static final int RUN_PRUNE_LIMIT = 1_000;

    /**
     * Açılışta okunan pod ayarları — sınırlanmış (geçersiz / aralık dışı değer sessizce varsayılana ya da sınıra çekilir,
     * günlük yazılmaz: L-1, varsayılan yapılandırmada fark yok).
     *
     * @param workerEnabled      bu pod parça alır mı
     * @param chunkSize          parça başına alan adı (5–200; koordinatörün ayarı)
     * @param minDomains         bundan az alan adlı tur klasik koşar (1–100 000)
     * @param maxChunks          aynı anda en çok uçuştaki parça (1–16)
     * @param maxInflightDomains uçuştaki alan adı bütçesi (0 = 2 × görev havuzu çekirdeği; aksi 1–2 000)
     * @param leaseMs            parça kirası (15 000–600 000 ms)
     * @param stealAfterMs       lider bu kadar bekleyen parçayı alır (0–600 000 ms; 0 = hemen)
     * @param leaderSharePct     başka canlı pod varken liderin bütçe payı (0–100 %)
     * @param pollMs             etkin yoklama aralığı (50–5 000 ms)
     * @param idlePollMs         boşta yoklama aralığı ({@code pollMs}–60 000 ms, en az 250)
     */
    public record Tuning(boolean workerEnabled, int chunkSize, int minDomains, int maxChunks, int maxInflightDomains,
                         long leaseMs, long stealAfterMs, int leaderSharePct, long pollMs, long idlePollMs) {

        public static final int MIN_CHUNK_SIZE = 5;
        public static final int MAX_CHUNK_SIZE = 200;
        public static final int MAX_MIN_DOMAINS = 100_000;
        public static final int MAX_MAX_CHUNKS = 16;
        public static final int MAX_INFLIGHT_DOMAINS = 2_000;
        public static final long MIN_LEASE_MS = 15_000L;
        public static final long MAX_LEASE_MS = 600_000L;

        /** application.properties varsayılanları. */
        public static final Tuning DEFAULTS = new Tuning(true, 20, 40, 4, 0, 60_000L, 10_000L, 50, 250L, 5_000L);

        public Tuning {
            chunkSize = clamp(chunkSize, MIN_CHUNK_SIZE, MAX_CHUNK_SIZE);
            minDomains = clamp(minDomains, 1, MAX_MIN_DOMAINS);
            maxChunks = clamp(maxChunks, 1, MAX_MAX_CHUNKS);
            maxInflightDomains = clamp(maxInflightDomains, 0, MAX_INFLIGHT_DOMAINS);
            leaseMs = clamp(leaseMs, MIN_LEASE_MS, MAX_LEASE_MS);
            stealAfterMs = clamp(stealAfterMs, 0L, 600_000L);
            leaderSharePct = clamp(leaderSharePct, 0, 100);
            pollMs = clamp(pollMs, 50L, 5_000L);
            idlePollMs = clamp(idlePollMs, Math.max(250L, pollMs), 60_000L);
        }

        /** Kira yenileme aralığı (ms) = kira / 4, en az 1 sn (60 sn kira → 15 sn). Yaşayan işçi uzun parçayı kaybetmez. */
        public long renewEveryMs() {
            return Math.max(1_000L, leaseMs / 4);
        }

        /**
         * Bu pod'un uçuştaki alan adı bütçesi: {@link #maxInflightDomains} doluysa o, değilse 2 × görev havuzunun GÜNCEL
         * çekirdek sayısı ({@code certCheckExecutor}, canlı ayar — 20 → 40); en az 1.
         *
         * @param executorCoreSize havuzun şu anki çekirdek sayısı ({@code CertChunkRunner.executorCoreSize()}); ≤ 0 → 1 sayılır
         */
        public int budget(int executorCoreSize) {
            if (maxInflightDomains > 0) return maxInflightDomains;
            return Math.max(1, 2 * Math.max(1, executorCoreSize));
        }

        /**
         * Tarama lideri + başka canlı pod varken bütçe = {@code budget × leaderSharePct / 100} (aşağı yuvarlanır; 40 × 50 % =
         * 20; 0 % → 0 = lider hiç almaz). Lider ayrıca yalnız {@link #stealAfterMs} kadar bekleyen parçaları alır (§3.4).
         */
        public int leaderBudget(int executorCoreSize) {
            return (int) ((long) budget(executorCoreSize) * leaderSharePct / 100L);
        }

        /**
         * Bu turdaki gerçek parça boyutu = max({@link #chunkSize}, ⌈domains / {@value CertChunking#MAX_CHUNKS_PER_RUN}⌉) —
         * bir tur en çok {@value CertChunking#MAX_CHUNKS_PER_RUN} parça olur (§4.5 adım 2).
         */
        public int effectiveChunkSize(int domains) {
            if (domains <= 0) return chunkSize;
            int needed = (int) ((domains + (long) MAX_CHUNKS_PER_RUN - 1) / MAX_CHUNKS_PER_RUN);
            return Math.max(chunkSize, needed);
        }

        /** Bu turdaki parça sayısı = ⌈domains / {@link #effectiveChunkSize}⌉ (0 alan adı → 0). */
        public int chunkCount(int domains) {
            if (domains <= 0) return 0;
            int size = effectiveChunkSize(domains);
            return (int) ((domains + (long) size - 1) / size);
        }

        /** Environment'tan okur (null → {@link #DEFAULTS}); çevrilemeyen değer o alanın varsayılanına düşer. Asla fırlatmaz. */
        public static Tuning from(Environment env) {
            if (env == null) return DEFAULTS;
            return new Tuning(
                    bool(env, KEY_WORKER_ENABLED, DEFAULTS.workerEnabled()),
                    intNum(env, KEY_CHUNK_SIZE, DEFAULTS.chunkSize()),
                    intNum(env, KEY_MIN_DOMAINS, DEFAULTS.minDomains()),
                    intNum(env, KEY_MAX_CHUNKS, DEFAULTS.maxChunks()),
                    intNum(env, KEY_MAX_INFLIGHT_DOMAINS, DEFAULTS.maxInflightDomains()),
                    num(env, KEY_LEASE_MS, DEFAULTS.leaseMs()),
                    num(env, KEY_STEAL_AFTER_MS, DEFAULTS.stealAfterMs()),
                    intNum(env, KEY_LEADER_SHARE_PCT, DEFAULTS.leaderSharePct()),
                    num(env, KEY_POLL_MS, DEFAULTS.pollMs()),
                    num(env, KEY_IDLE_POLL_MS, DEFAULTS.idlePollMs()));
        }

        private static boolean bool(Environment env, String key, boolean def) {
            try {
                String v = env.getProperty(key);
                if (v == null || v.isBlank()) return def;
                String t = v.trim();
                if ("true".equalsIgnoreCase(t)) return true;
                if ("false".equalsIgnoreCase(t)) return false;
                return def;
            } catch (RuntimeException e) {
                return def;
            }
        }

        /** Sayı okur; çevrilemezse varsayılan. */
        private static long num(Environment env, String key, long def) {
            try {
                String v = env.getProperty(key);
                if (v == null || v.isBlank()) return def;
                return Long.parseLong(v.trim());
            } catch (RuntimeException e) {
                return def;
            }
        }

        /** int alanı: {@link #num} değeri int aralığına DOYURULUR (5 000 000 000 → en büyük int, eksiye taşmaz). */
        private static int intNum(Environment env, String key, int def) {
            long n = num(env, key, def);
            return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, n));
        }

        private static int clamp(int v, int lo, int hi) {
            return Math.max(lo, Math.min(hi, v));
        }

        private static long clamp(long v, long lo, long hi) {
            return Math.max(lo, Math.min(hi, v));
        }
    }

    private final Supplier<AppSettingsService> settings;
    private final Tuning tuning;

    private final Object activationLock = new Object();
    private final List<Starter> pending = new ArrayList<>();
    private volatile boolean activated;
    private volatile boolean ready;
    private volatile boolean closed;
    private final AtomicReference<Supplier<CertSweepStatus.Worker>> workerStatus = new AtomicReference<>();
    private final AtomicReference<Supplier<CertSweepStatus.Coordinator>> coordinatorStatus = new AtomicReference<>();

    @Autowired(required = false)
    private ApplicationContext applicationContext;

    private record Starter(String name, Runnable start) {}

    /** Spring kurucusu — ayar servisi tembel çözülür (bağımlılık döngüsü kurmaz). */
    @Autowired
    public CertChunking(ObjectProvider<AppSettingsService> settingsProvider, Environment environment) {
        this.settings = lazy(settingsProvider);
        this.tuning = Tuning.from(environment);
    }

    /** Elle kurulum / test: verilen servis (null = hep kapalı) ve pod ayarları (null = {@link Tuning#DEFAULTS}). */
    public CertChunking(AppSettingsService settings, Tuning tuning) {
        this.settings = () -> settings;
        this.tuning = tuning == null ? Tuning.DEFAULTS : tuning;
    }

    // ── Okumalar (yalnız bellek) ────────────────────────────────────────────────────────────────────────────────────

    /** Anahtar açık mı — servis yok / istisna → false (asla fırlatmaz). */
    public boolean enabled() {
        return enabled(settingsOrNull());
    }

    /** Açılışta okunan pod ayarları. */
    public Tuning tuning() {
        return tuning;
    }

    /** Statik okuma (bean'siz yollar için; {@link #enabled()} ile aynı kural). */
    public static boolean enabled(AppSettingsService settings) {
        if (settings == null) return DEFAULT_ENABLED;
        try {
            return settings.getBoolean(KEY_ENABLED, DEFAULT_ENABLED);
        } catch (RuntimeException e) {
            return DEFAULT_ENABLED;
        }
    }

    /** {@code ContextClosedEvent} görüldü mü (bellek) — sonrasında yeni tur / sahiplenme / etkinleşme yok. */
    public boolean closing() {
        return closed;
    }

    // ── İşçi durumu (bellek) ────────────────────────────────────────────────────────────────────────────────────────

    /**
     * İşçinin (A) bu pod sayaçlarını sağlayan kaynağı kaydeder (son kayıt geçerli; null → kaydı siler). Kaynak hızlı ve
     * yalnız bellek okumalı (System Health isteğinde çağrılır).
     */
    public void registerWorkerStatus(Supplier<CertSweepStatus.Worker> source) {
        workerStatus.set(source);
    }

    /** Bu pod'un işçi durumu — kaynak yok / null döndü / istisna → null ("bu pod'da işçi yok"). Asla fırlatmaz. */
    public CertSweepStatus.Worker workerStatus() {
        return read(workerStatus.get());
    }

    /** Koordinatörün (B) bu pod sayaçlarını sağlayan kaynağı kaydeder (son kayıt geçerli; null → siler). Yalnız bellek. */
    public void registerCoordinatorStatus(Supplier<CertSweepStatus.Coordinator> source) {
        coordinatorStatus.set(source);
    }

    /** Bu pod'un koordinatör sayaçları — kaynak yok / null / istisna → null. Asla fırlatmaz. */
    public CertSweepStatus.Coordinator coordinatorStatus() {
        return read(coordinatorStatus.get());
    }

    private static <T> T read(Supplier<T> s) {
        if (s == null) return null;
        try {
            return s.get();
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ── Tembel başlatma ─────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Anahtar İLK KEZ açık görüldüğünde ({@link #enabled()}) bir kez çağrılacak başlatıcıyı kaydeder. Zaten etkinleştiyse
     * başlatıcı HEMEN, çağıran iş parçacığında çalışır. Kapanış başladıysa ({@code ContextClosedEvent}) kaydedilmez,
     * çalışmaz. Başlatıcı kısa olmalı (iş parçacığı kurmak); fırlattığı istisna yutulur (bir WARN, ileti yok — sınıf adı) ve
     * diğer başlatıcıları durdurmaz.
     *
     * @param name  günlükte görünen ad (ör. {@code "cert-chunk-worker"})
     * @param start çalıştırılacak iş (null → yok sayılır)
     */
    public void whenActivated(String name, Runnable start) {
        if (start == null) return;
        Starter s = new Starter(name == null ? "?" : name, start);
        boolean runNow;
        synchronized (activationLock) {
            if (closed) return;
            runNow = activated;
            if (!runNow) pending.add(s);
        }
        if (runNow) run(s);
    }

    /** Başlatıcılar çalıştı mı (anahtar açılıştan beri bir kez açık görüldü). */
    public boolean activated() {
        return activated;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady(ApplicationReadyEvent event) {
        if (!thisContext(event == null ? null : event.getApplicationContext())) return;
        ready = true;
        maybeActivate();
    }

    @EventListener
    public void onSettingsChanged(AppSettingsChangedEvent event) {
        if (event == null || !event.touches(KEY_ENABLED)) return;
        maybeActivate();
    }

    /** Kapanış: yalnız bayrak (bekleme / veritabanı yok) — sonra etkinleşme ve yeni başlatıcı yok. */
    @EventListener
    public void onContextClosed(ContextClosedEvent event) {
        if (!thisContext(event == null ? null : event.getApplicationContext())) return;
        synchronized (activationLock) {
            closed = true;
            pending.clear();
        }
    }

    /** Hazırsa, kapanmıyorsa ve anahtar açıksa (bellek) bekleyen başlatıcıları bir kez çalıştırır. */
    void maybeActivate() {
        if (activated || !ready || closed) return;
        if (!enabled()) return;
        List<Starter> toRun;
        synchronized (activationLock) {
            if (activated || closed) return;
            activated = true;
            toRun = new ArrayList<>(pending);
            pending.clear();
        }
        log.info("Parçalı sertifika taraması açık görüldü — {} başlatıcı çalıştırılıyor", toRun.size());
        for (Starter s : toRun) run(s);
    }

    private void run(Starter s) {
        try {
            s.start().run();
        } catch (RuntimeException e) {
            log.warn("Parçalı tarama başlatıcısı çalıştırılamadı ({}): {}", s.name(), e.getClass().getSimpleName());
        }
    }

    private boolean thisContext(ApplicationContext source) {
        return applicationContext == null || source == null || source == applicationContext;
    }

    private AppSettingsService settingsOrNull() {
        try {
            return settings.get();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Supplier<AppSettingsService> lazy(ObjectProvider<AppSettingsService> provider) {
        AtomicReference<AppSettingsService> ref = new AtomicReference<>();
        return () -> {
            AppSettingsService s = ref.get();
            if (s != null) return s;
            if (provider == null) return null;
            try {
                s = provider.getIfAvailable();
            } catch (RuntimeException e) {
                return null;
            }
            if (s != null) ref.set(s);
            return s;
        };
    }
}
