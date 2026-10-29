package com.sitemonitor.service.checks;

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
import java.util.Set;
import java.util.function.Supplier;

/**
 * Dağıtık izleme kontrollerinin ANA ANAHTARI ve tür listesi (2026-10-29, çok pod Sürüm 3-3 P0, tasarım R3-3 §5; kullanıcı
 * kararı U-1). İki ayar, ikisi de {@code AppSettingsCatalog} grubu {@code scheduler} (Ayarlar → Genel → Zamanlayıcı),
 * GLOBAL_ONLY, 10 sn ayar yenilemesiyle CANLI:
 * <ul>
 *   <li>{@value #KEY_ENABLED} — BOOL, VARSAYILAN KAPALI (env {@code DISTRIBUTED_CHECKS_ENABLED});</li>
 *   <li>{@value #KEY_TYPES} — CSV, varsayılan sekiz tür ({@link CheckJobTypes#DEFAULT_CSV}, env
 *       {@code DISTRIBUTED_CHECKS_TYPES}); kayıtta bilinmeyen ad 400 {@code VALIDATION_FAILED} alan
 *       {@value CheckJobTypes#FIELD} ({@link CheckJobTypes#validateSetting}).</li>
 * </ul>
 *
 * <p><b>Kapalı ≡ v20.169.0 (K-1):</b> okumalar YALNIZ BELLEKTİR ({@code AppSettingsService} geçersiz kılma haritası +
 * Environment) — veritabanı, günlük satırı, iş parçacığı yok. Okuma hatası (servis yok, istisna) = KAPALI; tür listesi
 * okunamazsa boş küme (hiçbir tür dağıtılmaz → klasik tarama).
 *
 * <p><b>Ayar ≠ kip.</b> {@link #distributedType} yalnız AYARI söyler ("bu tür seçilmiş mi"). Bir türün gerçekten dağıtık
 * koşması ({@code SchedulerService.distributedMode(T)}, paket B) ayrıca bu pod'un tarama lideri olmasını ve en az bir BAŞKA
 * canlı, yetenekli işçinin varlığını ister ({@link CheckWorkerRegistry#liveOtherWorkers}); tek pod'da tür ayar açık olsa da
 * klasik koşar.
 *
 * <p><b>Tembel başlatma (§3.6 / §5.2).</b> Gönderme tıkı (B), işçi yoklaması (A) ve sonuç boşaltıcısı (C) HİÇBİR ŞEYİ
 * açılışta kurmaz: {@link #whenActivated} ile bir başlatıcı kaydeder; bu sınıf anahtarı İLK KEZ açık gördüğünde
 * ({@code ApplicationReadyEvent} ya da anahtarlara dokunan {@code AppSettingsChangedEvent} — başka pod'un kaydı 10 sn
 * yenilemesiyle) her başlatıcıyı BİR KEZ çağırır. Anahtar açılıştan beri kapalıysa hiçbiri çağrılmaz (sıfır tık, sıfır iş
 * parçacığı). Sonradan kapatılınca başlatılanlar durmaz — kendi döngülerinde {@link #anyDistributed()} (bellek) okuyup
 * döner. {@code ContextClosedEvent}'ten sonra etkinleşme yok (yeni iş başlamaz).
 */
@Slf4j
@Component
public class DistributedChecks {

    /** Ana anahtar — kalıcı. */
    public static final String KEY_ENABLED = "site.monitor.checks.distributed.enabled";
    /** Dağıtılan türler — kalıcı. */
    public static final String KEY_TYPES = "site.monitor.checks.distributed.types";
    /** Ayar yoksa / okunamazsa: kapalı. */
    public static final boolean DEFAULT_ENABLED = false;
    /** Tür listesi ayarı yoksa: sekiz tür. */
    public static final String DEFAULT_TYPES_CSV = CheckJobTypes.DEFAULT_CSV;

    // ── Açılışta okunan pod anahtarları (katalogda YOK — eval-state.flush-ms gibi; application.properties + env) ─────────
    /** Bu pod kontrol işi alır mı (env {@code CHECK_WORKER_ENABLED}); false = "burada kontrol yok" (lider olursa değerlendirir). */
    public static final String KEY_WORKER_ENABLED = "site.monitor.checks.worker.enabled";
    /** İşçi yuvası (eşzamanlı kontrol) sayısı (env {@code CHECK_WORKER_SLOTS}), {@value Tuning#MIN_SLOTS}–{@value Tuning#MAX_SLOTS}. */
    public static final String KEY_WORKER_SLOTS = "site.monitor.checks.worker.slots";
    /** İşçi yoklama aralığı (ms, env {@code CHECK_WORKER_POLL_MS}); boşta en çok {@value Tuning#MAX_IDLE_POLL_MS}'e uzar. */
    public static final String KEY_WORKER_POLL_MS = "site.monitor.checks.worker.poll-ms";
    /** Başka canlı işçi varken liderin kullandığı yuva yüzdesi (env {@code CHECK_WORKER_LEADER_SHARE_PCT}). */
    public static final String KEY_WORKER_LEADER_SHARE_PCT = "site.monitor.checks.worker.leader-share-pct";
    /** Bu kadar BEKLEYEN iş lider tarafından tam yuvayla alınır (ms, env {@code CHECK_WORKER_STEAL_AFTER_MS}). */
    public static final String KEY_WORKER_STEAL_AFTER_MS = "site.monitor.checks.worker.steal-after-ms";
    /** Liderin gönderme tıkı (ms, env {@code CHECK_DISPATCH_MS}). */
    public static final String KEY_DISPATCH_MS = "site.monitor.checks.dispatch-ms";
    /** Liderin sonuç boşaltma aralığı (ms, env {@code CHECK_DRAIN_MS}). */
    public static final String KEY_DRAIN_MS = "site.monitor.checks.drain-ms";
    /** Bu kadar eski (bitişten) DONE sonuç değerlendirilmeden atılır (ms, env {@code CHECK_RESULT_STALE_MS}). */
    public static final String KEY_RESULT_STALE_MS = "site.monitor.checks.result-stale-ms";

    /**
     * Açılışta okunan pod ayarları — sınırlanmış (geçersiz / aralık dışı değer sessizce varsayılana ya da sınıra çekilir,
     * günlük yazılmaz: K-1, varsayılan yapılandırmada fark yok).
     *
     * @param workerEnabled  bu pod iş alır mı
     * @param slots          eşzamanlı kontrol yuvası (1–64)
     * @param pollMs         işçi yoklama aralığı (100–10 000 ms)
     * @param dispatchMs     gönderme tıkı (1 000–60 000 ms)
     * @param drainMs        boşaltma aralığı (200–30 000 ms)
     * @param leaderSharePct başka canlı işçi varken liderin yuva payı (0–100 %)
     * @param stealAfterMs   bu kadar bekleyen işi lider tam yuvayla alır (1 000–120 000 ms)
     * @param resultStaleMs  bu kadar eski DONE sonuç atılır (60 000–86 400 000 ms)
     */
    public record Tuning(boolean workerEnabled, int slots, long pollMs, long dispatchMs, long drainMs, int leaderSharePct,
                         long stealAfterMs, long resultStaleMs) {

        public static final int MIN_SLOTS = 1;
        public static final int MAX_SLOTS = 64;
        /** Boşta yoklama aralığının üst sınırı (ms) — ilk alınan işte {@code pollMs}'e döner (§2.3). */
        public static final long MAX_IDLE_POLL_MS = 5_000L;

        /** application.properties varsayılanları. */
        public static final Tuning DEFAULTS = new Tuning(true, 16, 1_000L, 5_000L, 2_000L, 50, 5_000L, 600_000L);

        public Tuning {
            slots = clamp(slots, MIN_SLOTS, MAX_SLOTS);
            pollMs = clamp(pollMs, 100L, 10_000L);
            dispatchMs = clamp(dispatchMs, 1_000L, 60_000L);
            drainMs = clamp(drainMs, 200L, 30_000L);
            leaderSharePct = clamp(leaderSharePct, 0, 100);
            stealAfterMs = clamp(stealAfterMs, 1_000L, 120_000L);
            resultStaleMs = clamp(resultStaleMs, 60_000L, 86_400_000L);
        }

        /**
         * Başka canlı yetenekli işçi VARKEN liderin normal sahiplenme sınırı = {@code slots × leaderSharePct / 100} (aşağı
         * yuvarlanır; 16 × 50 % = 8). Yalnızken (ya da {@code stealAfterMs}'i aşan bekleyen işlerde) lider tüm
         * {@link #slots}'u kullanır (U-2).
         */
        public int leaderSlots() {
            return (int) ((long) slots * leaderSharePct / 100L);
        }

        /** Environment'tan okur (null → {@link #DEFAULTS}); çevrilemeyen değer o alanın varsayılanına düşer. Asla fırlatmaz. */
        public static Tuning from(Environment env) {
            if (env == null) return DEFAULTS;
            return new Tuning(
                    bool(env, KEY_WORKER_ENABLED, DEFAULTS.workerEnabled()),
                    (int) num(env, KEY_WORKER_SLOTS, DEFAULTS.slots()),
                    num(env, KEY_WORKER_POLL_MS, DEFAULTS.pollMs()),
                    num(env, KEY_DISPATCH_MS, DEFAULTS.dispatchMs()),
                    num(env, KEY_DRAIN_MS, DEFAULTS.drainMs()),
                    (int) num(env, KEY_WORKER_LEADER_SHARE_PCT, DEFAULTS.leaderSharePct()),
                    num(env, KEY_WORKER_STEAL_AFTER_MS, DEFAULTS.stealAfterMs()),
                    num(env, KEY_RESULT_STALE_MS, DEFAULTS.resultStaleMs()));
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

        private static long num(Environment env, String key, long def) {
            try {
                String v = env.getProperty(key);
                if (v == null || v.isBlank()) return def;
                return Long.parseLong(v.trim());
            } catch (RuntimeException e) {
                return def;
            }
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

    /** Son okunan tür listesi metni + çözümü (her çağrıda yeniden bölmesin; metin değişince yeniden hesaplanır). */
    private record Parsed(String raw, Set<String> types) {}
    private volatile Parsed parsed = new Parsed(null, Set.of());

    private final Object activationLock = new Object();
    private final List<Starter> pending = new ArrayList<>();
    private volatile boolean activated;
    private volatile boolean ready;
    private volatile boolean closed;

    @Autowired(required = false)
    private ApplicationContext applicationContext;

    private record Starter(String name, Runnable start) {}

    /** Spring kurucusu — ayar servisi tembel çözülür (bağımlılık döngüsü kurmaz). */
    @Autowired
    public DistributedChecks(ObjectProvider<AppSettingsService> settingsProvider, Environment environment) {
        this.settings = lazy(settingsProvider);
        this.tuning = Tuning.from(environment);
    }

    /** Elle kurulum / test: verilen servis (null = hep kapalı) ve pod ayarları (null = {@link Tuning#DEFAULTS}). */
    public DistributedChecks(AppSettingsService settings, Tuning tuning) {
        this.settings = () -> settings;
        this.tuning = tuning == null ? Tuning.DEFAULTS : tuning;
    }

    // ── Okumalar (yalnız bellek) ────────────────────────────────────────────────────────────────────────────────────

    /** Ana anahtar açık mı — servis yok / istisna → false (asla fırlatmaz). */
    public boolean enabled() {
        return enabled(settingsOrNull());
    }

    /**
     * Ayarda seçili türler ({@link CheckJobTypes#ALL} sırasında, değiştirilemez) — ana anahtardan BAĞIMSIZ; okunamazsa boş
     * küme. Bilinmeyen ad / boş parça atlanır.
     */
    public Set<String> types() {
        AppSettingsService s = settingsOrNull();
        if (s == null) return Set.of();
        String raw;
        try {
            raw = s.getString(KEY_TYPES, DEFAULT_TYPES_CSV);
        } catch (RuntimeException e) {
            return Set.of();
        }
        Parsed p = parsed;
        if (p.raw() != null && p.raw().equals(raw)) return p.types();
        Set<String> t = CheckJobTypes.parseLenient(raw);
        parsed = new Parsed(raw, t);
        return t;
    }

    /** Anahtar açık VE en az bir tür seçili — gönderme / işçi / boşaltıcı döngülerinin ilk (bellek) okuması. */
    public boolean anyDistributed() {
        return enabled() && !types().isEmpty();
    }

    /** Anahtar açık VE bu tür seçili (AYAR — kip değil; bkz. sınıf açıklaması). Bilinmeyen tür → false. */
    public boolean distributedType(String type) {
        return CheckJobTypes.isKnown(type) && enabled() && types().contains(type);
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

    // ── Tembel başlatma ─────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Anahtar İLK KEZ açık görüldüğünde ({@link #anyDistributed()}) bir kez çağrılacak başlatıcıyı kaydeder. Zaten
     * etkinleştiyse başlatıcı HEMEN, çağıran iş parçacığında çalışır. Kapanış başladıysa ({@code ContextClosedEvent})
     * kaydedilmez, çalışmaz. Başlatıcı kısa olmalı (iş parçacığı / zamanlanmış görev kurmak); fırlattığı istisna yutulur
     * (bir WARN, ileti yok — sınıf adı) ve diğer başlatıcıları durdurmaz.
     *
     * @param name  günlükte görünen ad (ör. {@code "check-worker"})
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
        if (event == null || (!event.touches(KEY_ENABLED) && !event.touches(KEY_TYPES))) return;
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
        if (!anyDistributed()) return;
        List<Starter> toRun;
        synchronized (activationLock) {
            if (activated || closed) return;
            activated = true;
            toRun = new ArrayList<>(pending);
            pending.clear();
        }
        log.info("Dağıtık kontroller açık görüldü — {} başlatıcı çalıştırılıyor (türler: {})", toRun.size(), types());
        for (Starter s : toRun) run(s);
    }

    private void run(Starter s) {
        try {
            s.start().run();
        } catch (RuntimeException e) {
            log.warn("Dağıtık kontrol başlatıcısı çalıştırılamadı ({}): {}", s.name(), e.getClass().getSimpleName());
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
        java.util.concurrent.atomic.AtomicReference<AppSettingsService> ref = new java.util.concurrent.atomic.AtomicReference<>();
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
