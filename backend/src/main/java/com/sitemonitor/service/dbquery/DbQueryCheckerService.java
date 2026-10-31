package com.sitemonitor.service.dbquery;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitemonitor.model.DbConnectionProfile;
import com.sitemonitor.model.DbQueryCheck;
import com.sitemonitor.model.DbQueryMonitor;
import com.sitemonitor.repository.DbConnectionProfileRepository;
import com.sitemonitor.repository.DbQueryCheckRepository;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.SecretCipher;
import com.sitemonitor.util.Msg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntSupplier;

/**
 * Bir izlemenin tek çalıştırmasını uçtan uca yürütür (sözleşme §2–§3): bağlantıyı bulur ve parolayı çözer, sorguyu YENİDEN
 * denetler ({@link DbQuerySqlGuard}), eşzamanlılık izinlerini alır (pod / bağlantı), çalıştırır ({@link DbQueryRunner}),
 * kuralları değerlendirir ({@link DbQueryEvaluator}; değişim kuralının tabanı {@code DbQueryCheckRepository
 * .findRecentScheduledOk} — {@link Run}), yavaş sorguyu işaretler, mail / alarm satırlarını seçer + maskeler, bağlantı
 * sağlığını yazar. Kontrol satırını YAZMAZ ve alarm DEĞERLENDİRMEZ (zamanlayıcının işi). Asla fırlatmaz.
 *
 * <p><b>İzinler</b> canlı ayardan okunur ({@code site.monitor.dbquery.max-concurrency}, {@code …per-connection-concurrency})
 * ve HER ZAMAN önce bağlantı, sonra pod izni alınır (2026-10-31 inceleme O1): pod iznini tutarken bağlantı izni BEKLENMEZ —
 * yavaş bir veritabanının izlemeleri pod izinlerini kapıp öteki bağlantıların izlemelerini durduramaz. Zamanlanmış
 * çalıştırmalar paylaşılan havuzlarda izin BEKLEMEZ: tarama {@link #tryReserve} ile izni bekletmeden ayırır, ayrılan
 * çalıştırma bu sınıfın kendi sınırlı havuzunda koşar ({@link #runReserved}; iş parçacıkları {@value #RUN_THREAD_PREFIX}N,
 * en çok {@value #RUN_THREADS}). Teyit / kurtarma yeniden kontrolü (MOS havuzu) izni en çok {@value #RECHECK_WAIT_MS} ms,
 * elle / deneme {@value #INTERACTIVE_WAIT_MS} ms bekler; alınamazsa çalıştırma ATLANIR ({@code SKIPPED} / {@code DB_BUSY} —
 * satır yazılmaz, alarm yok). Ayrıştırma zaman / izin sınırına takılan güvenlik denetimi de ATLAMA sayılır (yük altında
 * sahte FAIL alarmı açılmasın); gerçek red ise FAIL {@code DB_GUARD_REJECTED}'dir ve sorgu ÇALIŞMAZ.
 */
@Service
public class DbQueryCheckerService {

    private static final Logger log = LoggerFactory.getLogger(DbQueryCheckerService.class);

    public static final String STATUS_OK = "OK", STATUS_BREACH = "BREACH", STATUS_FAIL = "FAIL", STATUS_SKIPPED = "SKIPPED";

    /** Çalıştırıcı hata kodları (kalıcı) — {@code failure_reason}. */
    public static final String DB_UNREACHABLE = DbQueryErrors.DB_UNREACHABLE, DB_DNS = DbQueryErrors.DB_DNS,
            DB_TIMEOUT = DbQueryErrors.DB_TIMEOUT, DB_AUTH = DbQueryErrors.DB_AUTH,
            DB_SQL_ERROR = DbQueryErrors.DB_SQL_ERROR, DB_DRIVER = DbQueryErrors.DB_DRIVER,
            DB_BLOCKED = DbQueryErrors.DB_BLOCKED;

    public static final String DB_GUARD_REJECTED = "DB_GUARD_REJECTED", DB_PROFILE_MISSING = "DB_PROFILE_MISSING",
            DB_PROFILE_OTHER_TEAM = "DB_PROFILE_OTHER_TEAM", DB_SECRET_UNREADABLE = "DB_SECRET_UNREADABLE",
            DB_BUSY = "DB_BUSY",
            /** SKIPPED kodu: SiteMonitor'ün kendi tarafında beklenmeyen hata (izlenen veritabanının suçu değil — alarm yok). */
            DB_INTERNAL = "DB_INTERNAL";

    public static final String KEY_MAX_CONCURRENCY = "site.monitor.dbquery.max-concurrency",
            KEY_PER_CONNECTION = "site.monitor.dbquery.per-connection-concurrency",
            KEY_MAX_TIMEOUT = "site.monitor.dbquery.max-timeout-seconds",
            KEY_MAX_ROWS_CAP = "site.monitor.dbquery.max-rows-cap",
            KEY_MAIL_MAX_ROWS = "site.monitor.dbquery.mail-max-rows",
            KEY_MAIL_MAX_COLUMNS = "site.monitor.dbquery.mail-max-columns",
            KEY_PLAN_MODE = "site.monitor.dbquery.plan-check-mode",
            KEY_MAX_PLAN_COST = "site.monitor.dbquery.max-plan-cost",
            KEY_MAX_PLAN_ROWS = "site.monitor.dbquery.max-plan-rows";

    static final long SCHEDULED_WAIT_MS = 30_000, INTERACTIVE_WAIT_MS = 10_000;
    /** Teyit / kurtarma yeniden kontrolünün izin beklemesi (MOS'un paylaşılan teyit / kurtarma havuzunda koşar). */
    static final long RECHECK_WAIT_MS = 2_000;
    /** Zamanlanmış çalıştırma havuzu: iş parçacığı adı öneki (kalıcı — günlük / iş parçacığı dökümü) ve üst sınır. */
    public static final String RUN_THREAD_PREFIX = "dbquery-run-";
    static final int RUN_THREADS = 32, RUN_QUEUE = 256;
    /** Kapanışta koşan sorguların bitmesi için en çok beklenen süre (ortak yok etme bütçesinden). */
    static final long SHUTDOWN_WAIT_MS = 1_000;

    /**
     * Çalıştırmanın türü — değişim kuralının TABANINI belirler (2026-10-31 inceleme Y1).
     * <ul>
     *   <li>{@link #SCHEDULED}: önceki zamanlanmış çalıştırma (son zamanlanmış ok satırı).</li>
     *   <li>{@link #RECHECK} (teyit / kurtarma) ve {@link #MANUAL} ("Şimdi kontrol et"): onaylanan son zamanlanmış
     *       çalıştırmanın KULLANDIĞI taban (son zamanlanmış satırdan önceki zamanlanmış ok satırı) — ihlal eden satırın
     *       kendisiyle kıyaslanıp "değişim yok" görünmesin. Bu satırlar bant dışıdır (zamanlayıcı {@code manual} /
     *       {@code recheck} işaretiyle yazar).</li>
     * </ul>
     */
    public enum Run { SCHEDULED, RECHECK, MANUAL }

    /** Bekletmeden ayrılmış izin ({@link #tryReserve}); {@link #runReserved} bırakır, gönderilemezse çağıran bırakır. */
    public interface Reservation {
        /** İzni bırakır (birden çok çağrı güvenli). */
        void release();
    }
    static final int MAIL_CELL_CHARS = 200;
    public static final String MASK = "••••";
    static final int DEFAULT_TIMEOUT = 30, DEFAULT_MAX_ROWS = 1_000, MIN_TIMEOUT = 5;

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * @param status         OK | BREACH | FAIL | SKIPPED (SKIPPED = pod / bağlantı izni alınamadı; satır yazılmaz, alarm yok)
     * @param level          NONE | WARNING | CRITICAL (BREACH seviyesi)
     * @param columnInfo     sorgunun TÜM kolonları (≤ 100) — ad + tür (satır verisi değil)
     * @param mailColumns    mail / alarm satırlarına seçilen kolonlar (mail_columns ya da ilk N)
     * @param mailRows       mail / alarm satırları: ≤ mail-max-rows × mailColumns, hücre ≤ 200 karakter, maskeli kolonlar
     *                       "••••"; YALNIZ mail_include_rows açıkken dolu — UI'ya (deneme ucu dahil) ASLA dönmez
     * @param maskedColumns  maskelenen kolon adları (mailColumns içinden)
     * @param rowsTruncated  mailRows satır / kolon sınırında kesildi
     * @param failureReason  FAIL kodu (DB_UNREACHABLE, DB_DNS, DB_TIMEOUT, DB_AUTH, DB_SQL_ERROR, DB_DRIVER, DB_BLOCKED,
     *                       DB_GUARD_REJECTED, DB_PROFILE_MISSING, DB_PROFILE_OTHER_TEAM, DB_SECRET_UNREADABLE) ya da
     *                       SKIPPED kodu (DB_BUSY — izin dolu ya da denetim süre sınırında; DB_INTERNAL — iç hata)
     * @param error          maskeli hata metni (≤ 500)
     * @param checkedAt      UTC ISO (yyyy-MM-dd'T'HH:mm:ss)
     */
    public record Result(String status, String level, long durationMs, Integer rowCount, boolean rowCountCapped,
                         List<DbQueryRunner.Column> columnInfo, List<String> mailColumns, List<List<String>> mailRows,
                         List<String> maskedColumns, boolean rowsTruncated, List<DbQueryEvaluator.RuleResult> ruleResults,
                         Double metricValue, boolean slow, String error, String failureReason, String checkedAt,
                         String sqlHash, Long profileId, String serverVersion) {}

    /** @param verdict OK | WARN | BLOCK | UNKNOWN (ayar {@code site.monitor.dbquery.plan-check-mode} + eşikler). */
    public record PlanVerdict(boolean supported, String verdict, Double cost, Double rows, String message) {}

    /** Kayıtlı parola çözülemedi (anahtar değişti / bozuk değer). */
    public static final class SecretUnreadableException extends IllegalStateException {
        public SecretUnreadableException() { super("bağlantı parolası çözülemedi"); }
    }

    private final DbConnectionProfileRepository profiles;
    private final DbQueryCheckRepository checks;
    private final SecretCipher cipher;
    private final DbQueryRunner runner;
    private final AppSettingsService settings;

    private final Permits podPermits = new Permits();
    /** İzin bekleme süreleri (ms) — testler kısaltır. */
    private volatile long scheduledWaitMs = SCHEDULED_WAIT_MS, interactiveWaitMs = INTERACTIVE_WAIT_MS,
            recheckWaitMs = RECHECK_WAIT_MS;
    private final Map<Long, Permits> connectionPermits = new ConcurrentHashMap<>();
    /** Bir izin bırakıldı — {@link #awaitRelease} bekleyen taramayı uyandırır. */
    private final Object releaseSignal = new Object();
    /** Zamanlanmış çalıştırma havuzu (ilk kullanımda kurulur; kapanışta {@link #shutdown}). */
    private volatile java.util.concurrent.ThreadPoolExecutor runPool;
    private volatile boolean closing;

    public DbQueryCheckerService(DbConnectionProfileRepository profiles, DbQueryCheckRepository checks,
                                 SecretCipher cipher, DbQueryRunner runner, AppSettingsService settings) {
        this.profiles = profiles;
        this.checks = checks;
        this.cipher = cipher;
        this.runner = runner;
        this.settings = settings;
    }

    // ── Giriş noktaları ──────────────────────────────────────────────────────

    /** Kayıtlı izlemenin çalıştırması ({@code manual} → {@link Run#MANUAL}, değilse {@link Run#SCHEDULED}). */
    public Result check(DbQueryMonitor m, boolean manual) {
        return check(m, manual ? Run.MANUAL : Run.SCHEDULED);
    }

    /** Teyit / kurtarma yeniden kontrolü ({@link Run#RECHECK}): kısa izin beklemesi, onaylanan çalıştırmanın tabanı. */
    public Result recheck(DbQueryMonitor m) {
        return check(m, Run.RECHECK);
    }

    /** Kayıtlı izlemenin çalıştırması; bağlantı sağlığını yazar. İzin türüne göre beklenir (sınıf belgesi). */
    public Result check(DbQueryMonitor m, Run run) {
        Run kind = run == null ? Run.SCHEDULED : run;
        long wait = switch (kind) {
            case MANUAL -> interactiveWaitMs;
            case RECHECK -> recheckWaitMs;
            case SCHEDULED -> scheduledWaitMs;
        };
        try {
            return execute(m, m == null ? null : m.getId(), kind, true, wait, true, null);
        } catch (RuntimeException e) {
            return internalError(m, e);
        }
    }

    /**
     * Zamanlanmış çalıştırmanın izinlerini BEKLEMEDEN ayırır — önce bağlantı, sonra pod (sınıf belgesi). İzin yoksa ya da
     * kapanış başladıysa null: tarama bu izlemeyi bir sonraki izin bırakılışında yeniden dener ({@link #awaitRelease}).
     */
    public Reservation tryReserve(DbQueryMonitor m) {
        if (closing || m == null) return null;
        return acquire(m.getProfileId(), 0);
    }

    /** Bir izin bırakılana ya da {@code ms} dolana dek bekler (tarama iş parçacığı; kesilirse bayrağı geri koyar). */
    public void awaitRelease(long ms) {
        if (ms <= 0) return;
        synchronized (releaseSignal) {
            try {
                releaseSignal.wait(ms);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Ayrılmış izinle zamanlanmış çalıştırmayı bu sınıfın sınırlı havuzunda koşar; sonuç gelince {@code then} (zamanlayıcının
     * kayıt adımı) aynı havuz iş parçacığında çalışır. İzin sorgu biter bitmez bırakılır. Havuz işi kabul etmezse (kapanış,
     * kuyruk dolu) izin bırakılır ve null döner — çağıran çalıştırmayı ATLANDI sayar.
     */
    public <T> java.util.concurrent.CompletableFuture<T> runReserved(DbQueryMonitor m, Reservation reservation,
                                                                     java.util.function.Function<Result, T> then) {
        if (reservation == null) return null;
        if (closing || then == null || !(reservation instanceof Lease lease)) {
            reservation.release();
            return null;
        }
        java.util.concurrent.CompletableFuture<T> f = new java.util.concurrent.CompletableFuture<>();
        try {
            runPool().execute(() -> {
                if (closing) {   // kuyrukta beklerken kapanış başladı — sorgu hiç çalıştırılmaz
                    lease.release();
                    f.cancel(false);
                    return;
                }
                Result res;
                try {
                    res = execute(m, m == null ? null : m.getId(), Run.SCHEDULED, true, 0, true, lease);
                } catch (RuntimeException e) {
                    res = internalError(m, e);
                } finally {
                    lease.release();
                }
                if (closing) {   // kapanış: kayıt adımı (veritabanı yazımı) koşmaz — tarama sonucu zaten düşer
                    f.cancel(false);
                    return;
                }
                try {
                    f.complete(then.apply(res));
                } catch (Throwable t) {
                    f.completeExceptionally(t);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            lease.release();
            return null;
        }
        return f;
    }

    private java.util.concurrent.ThreadPoolExecutor runPool() {
        java.util.concurrent.ThreadPoolExecutor p = runPool;
        if (p != null) return p;
        synchronized (this) {
            if (runPool == null) {
                java.util.concurrent.atomic.AtomicInteger n = new java.util.concurrent.atomic.AtomicInteger();
                java.util.concurrent.ThreadPoolExecutor ex = new java.util.concurrent.ThreadPoolExecutor(RUN_THREADS,
                        RUN_THREADS, 60, java.util.concurrent.TimeUnit.SECONDS,
                        new java.util.concurrent.LinkedBlockingQueue<>(RUN_QUEUE), r -> {
                            Thread t = new Thread(r, RUN_THREAD_PREFIX + n.incrementAndGet());
                            t.setDaemon(true);
                            return t;
                        });
                ex.allowCoreThreadTimeOut(true);
                runPool = ex;
            }
            return runPool;
        }
    }

    /**
     * Kapanış: yeni çalıştırma / ayırma kabul edilmez, koşan sorgular ortak yok etme bütçesinden en çok
     * {@value #SHUTDOWN_WAIT_MS} ms beklenir, sonra kesilir. Havuz hiç kurulmadıysa ortak bütçeye dokunulmaz.
     */
    @jakarta.annotation.PreDestroy
    public void shutdown() {
        closing = true;
        java.util.concurrent.ThreadPoolExecutor p = runPool;
        if (p == null) return;
        p.shutdown();
        try {
            if (!p.awaitTermination(com.sitemonitor.config.ShutdownDeadline.waitMs(SHUTDOWN_WAIT_MS),
                    java.util.concurrent.TimeUnit.MILLISECONDS)) p.shutdownNow();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.shutdownNow();
        }
    }

    /** İç hata (ör. SiteMonitor'ün kendi veritabanı) izlenen veritabanının kesintisi DEĞİLDİR → ATLA, alarm açma. */
    private Result internalError(DbQueryMonitor m, RuntimeException e) {
        log.warn("Veritabanı sorgu izlemesi çalıştırılamadı (#{}): {}", m == null ? null : m.getId(),
                e.getClass().getSimpleName());
        return skipped(m, null, DB_INTERNAL, Msg.t("Sorgu bu turda çalıştırılamadı (iç hata) · SiteMonitor tarafında "
                + "beklenmeyen bir hata oluştu; izlenen veritabanı sorumlu değil · Bir sonraki çalıştırmayı bekleyin; "
                + "sürerse yöneticinize bildirin.", "The query could not run this round (internal error) · An unexpected "
                + "error occurred on the SiteMonitor side; the monitored database is not to blame · Wait for the next "
                + "run; if it persists, tell your administrator."));
    }

    /**
     * Kaydetmeden deneme (form "Sorguyu dene"): taslağın bağlantısı + sorgusu + kuralları; değişim kuralı için
     * {@code previousForMonitorId} (düzenlenen izleme) verilirse onun önceki çalıştırması okunur. Bağlantı sağlığına YAZMAZ.
     * Mail satırları hiç seçilmez (deneme ucu satır verisi döndürmez).
     */
    public Result test(DbQueryMonitor draft, Long previousForMonitorId) {
        try {
            return execute(draft, previousForMonitorId, Run.SCHEDULED, false, interactiveWaitMs, false, null);
        } catch (RuntimeException e) {
            log.warn("Veritabanı sorgusu denenemedi: {}", e.getClass().getSimpleName());
            return skipped(draft, null, DB_INTERNAL, Msg.t("Sorgu denenemedi (iç hata) · Beklenmeyen bir hata oluştu · "
                    + "Biraz sonra yeniden deneyin.", "The query could not be tried (internal error) · An unexpected error "
                    + "occurred · Try again shortly."));
        }
    }

    /**
     * Bağlantı denemesi. {@code passwordOverride} null değilse kayıtlı parola yerine o kullanılır (formda yeni parola).
     * {@code profile.getId() != null} ve override yoksa sonuç sağlık kolonlarına yazılır.
     */
    public DbQueryRunner.ConnectionTest testConnection(DbConnectionProfile profile, String passwordOverride) {
        if (profile == null) return new DbQueryRunner.ConnectionTest(false, 0, null, msgProfileMissing(), DB_PROFILE_MISSING);
        DbConnectionSpec spec;
        try {
            spec = specOf(profile, passwordOverride);
        } catch (SecretUnreadableException e) {
            return new DbQueryRunner.ConnectionTest(false, 0, null, msgSecretUnreadable(), DB_SECRET_UNREADABLE);
        } catch (IllegalArgumentException e) {
            return new DbQueryRunner.ConnectionTest(false, 0, null, msgUnknownType(), DbQueryErrors.DB_DRIVER);
        }
        Lease lease = acquire(profile.getId(), interactiveWaitMs);
        if (lease == null) return new DbQueryRunner.ConnectionTest(false, 0, null, msgBusy(), DB_BUSY);
        DbQueryRunner.ConnectionTest t;
        try {
            t = runner.test(spec);
        } finally {
            lease.release();
        }
        if (profile.getId() != null && passwordOverride == null) {
            try {
                profiles.recordTest(profile.getId(), nowIso(), t.ok(), (int) Math.min(Integer.MAX_VALUE, t.durationMs()),
                        t.error(), t.serverVersion());
            } catch (RuntimeException e) {
                log.warn("Bağlantı denemesi sonucu yazılamadı (#{}): {}", profile.getId(), e.getClass().getSimpleName());
            }
        }
        return t;
    }

    public PlanVerdict planCheck(DbConnectionProfile profile, String sql) {
        if (profile == null) return new PlanVerdict(false, "UNKNOWN", null, null, msgProfileMissing());
        DbType type = DbType.parse(profile.getDbType());
        if (type == null) return new PlanVerdict(false, "UNKNOWN", null, null, msgUnknownType());
        if (type == DbType.ORACLE)
            return new PlanVerdict(false, "UNKNOWN", null, null, Msg.t(
                    "Oracle'da maliyet ön kontrolü yapılamıyor · Plan okumak için veritabanına plan tablosu yazmak gerekir · "
                            + "Sorguyu \"Sorguyu dene\" ile süresine bakarak sınayın.",
                    "The cost check is not available for Oracle · Reading a plan requires writing to a plan table · "
                            + "Try the query with \"Try query\" and look at its duration."));
        String mode = planMode();
        if ("OFF".equals(mode))
            return new PlanVerdict(true, "OK", null, null, Msg.t("Maliyet ön kontrolü kapalı.", "The cost check is turned off."));
        DbQuerySqlGuard.Result guard = DbQuerySqlGuard.check(sql, type);
        if (!guard.ok())
            return new PlanVerdict(true, "UNKNOWN", null, null, Msg.t(
                    "Sorgu güvenlik denetiminden geçmedi; maliyeti ölçülmedi · Önce güvenlik uyarılarını giderin.",
                    "The query did not pass the safety check, so its cost was not measured · Fix the safety issues first."));
        DbConnectionSpec spec;
        try {
            spec = specOf(profile, null);
        } catch (SecretUnreadableException e) {
            return new PlanVerdict(true, "UNKNOWN", null, null, msgSecretUnreadable());
        }
        Lease lease = acquire(profile.getId(), interactiveWaitMs);
        if (lease == null) return new PlanVerdict(true, "UNKNOWN", null, null, msgBusy());
        DbQueryRunner.PlanResult p;
        try {
            p = runner.plan(spec, sql, Math.min(maxTimeout(), DEFAULT_TIMEOUT));
        } finally {
            lease.release();
        }
        if (!p.supported()) return new PlanVerdict(false, "UNKNOWN", null, null, null);
        if (!p.ok() || p.cost() == null)
            return new PlanVerdict(true, "UNKNOWN", p.cost(), p.rows(), Msg.t(
                    "Sorgu planı okunamadı" + (p.error() == null ? "" : " (" + p.error() + ")") + " · Maliyet bilinmiyor; "
                            + "kayıt engellenmez · Sorguyu \"Sorguyu dene\" ile süresine bakarak sınayın.",
                    "The query plan could not be read" + (p.error() == null ? "" : " (" + p.error() + ")") + " · The cost is "
                            + "unknown; saving is not blocked · Try the query with \"Try query\" and look at its duration."));
        double maxCost = settings.getDouble(KEY_MAX_PLAN_COST, 1_000_000d);
        double maxRows = settings.getDouble(KEY_MAX_PLAN_ROWS, 10_000_000d);
        boolean over = p.cost() > maxCost || (p.rows() != null && p.rows() > maxRows);
        if (!over) return new PlanVerdict(true, "OK", p.cost(), p.rows(), Msg.t(
                "Tahmini maliyet sınırların içinde.", "The estimated cost is within the limits."));
        String verdict = "BLOCK".equals(mode) ? "BLOCK" : "WARN";
        String c = DbQueryEvaluator.fmt(p.cost(), Msg.isEn()), mc = DbQueryEvaluator.fmt(maxCost, Msg.isEn());
        String r = p.rows() == null ? "—" : DbQueryEvaluator.fmt(p.rows(), Msg.isEn());
        String mr = DbQueryEvaluator.fmt(maxRows, Msg.isEn());
        return new PlanVerdict(true, verdict, p.cost(), p.rows(), Msg.t(
                "Sorgunun tahmini maliyeti yüksek (maliyet " + c + ", sınır " + mc + "; satır " + r + ", sınır " + mr + ") · "
                        + "Bu sorgu her çalıştığında veritabanını yorabilir · Süzgeç ekleyin, dizinli kolonlara göre "
                        + "daraltın ya da toplanmış bir görünümü okuyun.",
                "The query's estimated cost is high (cost " + c + ", limit " + mc + "; rows " + r + ", limit " + mr + ") · "
                        + "Each run may load the database · Add filters, narrow on indexed columns, or read an "
                        + "aggregated view."));
    }

    /** Profili çalıştırıcı tanımına çevirir (parola çözülür; override verilirse o). */
    public DbConnectionSpec specOf(DbConnectionProfile profile, String passwordOverride) {
        DbType type = DbType.parse(profile.getDbType());
        if (type == null) throw new IllegalArgumentException("bilinmeyen veritabanı türü");
        String password;
        if (passwordOverride != null) {
            password = passwordOverride;
        } else {
            String enc = profile.getPasswordEnc();
            if (enc == null || enc.isBlank()) {
                password = null;
            } else {
                password = cipher.decrypt(enc);
                if (password == null) throw new SecretUnreadableException();
            }
        }
        int port = profile.getPort() == null ? type.defaultPort() : profile.getPort();
        int connectMs = profile.getConnectTimeoutMs() == null ? 10_000 : profile.getConnectTimeoutMs();
        return new DbConnectionSpec(type, profile.getHost(), port, profile.getDatabaseName(), profile.getOracleMode(),
                profile.getMssqlInstance(), profile.getUsername(), password, profile.getSslMode(),
                Boolean.TRUE.equals(profile.getMssqlEncrypt()), Boolean.TRUE.equals(profile.getMssqlTrustServerCertificate()),
                Math.max(1_000, Math.min(30_000, connectMs)));
    }

    // ── Akış ─────────────────────────────────────────────────────────────────

    /**
     * @param run      değişim kuralının tabanı ({@link Run}); deneme ucu {@link Run#SCHEDULED} (taslak "sıradaki çalıştırma"
     *                 gibi değerlendirilir)
     * @param reserved önceden ayrılmış izin ({@link #tryReserve}) ya da null (izin {@code waitMs} beklenerek alınır); her
     *                 yolda bırakılır
     */
    private Result execute(DbQueryMonitor m, Long previousForMonitorId, Run run, boolean recordHealth, long waitMs,
                           boolean selectMailRows, Lease reserved) {
        try {
            return executeBody(m, previousForMonitorId, run, recordHealth, waitMs, selectMailRows, reserved);
        } finally {
            if (reserved != null) reserved.release();
        }
    }

    private Result executeBody(DbQueryMonitor m, Long previousForMonitorId, Run run, boolean recordHealth, long waitMs,
                               boolean selectMailRows, Lease reserved) {
        if (m == null) return fail(null, DB_PROFILE_MISSING, msgProfileMissing(), null, null);
        DbConnectionProfile p = m.getProfileId() == null ? null : profiles.findById(m.getProfileId()).orElse(null);
        if (p == null) return fail(m, DB_PROFILE_MISSING, msgProfileMissing(), null, null);
        if (!Objects.equals(p.getTeamId(), m.getTeamId()))
            return fail(m, DB_PROFILE_OTHER_TEAM, Msg.t(
                    "Bağlantı bu izlemenin takımına ait değil · Bir izleme yalnız kendi takımının bağlantısını kullanabilir · "
                            + "İzlemede kendi takımınızın bir bağlantısını seçin.",
                    "The connection does not belong to this monitor's team · A monitor may only use its own team's "
                            + "connection · Choose a connection of your team in the monitor."), p.getId(), null);
        DbType type = DbType.parse(p.getDbType());
        if (type == null) return fail(m, DbQueryErrors.DB_DRIVER, msgUnknownType(), p.getId(), null);

        DbQuerySqlGuard.Result guard = DbQuerySqlGuard.check(m.getSqlText(), type);
        if (DbQuerySqlGuard.isTimeout(guard)) return skipped(m, p.getId(), DB_BUSY, msgBusy());
        if (!guard.ok()) {
            List<String> msgs = new ArrayList<>();
            for (DbQuerySqlGuard.Violation v : guard.violations()) {
                if (msgs.size() >= 3) break;
                msgs.add(v.message());
            }
            return fail(m, DB_GUARD_REJECTED, clip(String.join(" | ", msgs), DbQueryErrors.MAX_ERROR_CHARS), p.getId(), null);
        }
        DbConnectionSpec spec;
        try {
            spec = specOf(p, null);
        } catch (SecretUnreadableException e) {
            return fail(m, DB_SECRET_UNREADABLE, msgSecretUnreadable(), p.getId(), null);
        }

        int timeout = clampInt(m.getQueryTimeoutSeconds() == null ? DEFAULT_TIMEOUT : m.getQueryTimeoutSeconds(),
                MIN_TIMEOUT, Math.max(MIN_TIMEOUT, maxTimeout()));
        int maxRows = clampInt(m.getMaxRows() == null ? DEFAULT_MAX_ROWS : m.getMaxRows(), 1,
                Math.max(1, settings.getInt(KEY_MAX_ROWS_CAP, 5_000)));
        List<DbQueryRules.Rule> rules = DbQueryRules.fromJson(m.getRulesJson());
        Set<String> masked = lowerSet(stringList(m.getMaskedColumnsJson()));
        Set<String> discard = new LinkedHashSet<>(masked);
        for (DbQueryRules.Rule r : rules) if (r.column() != null) discard.remove(r.column().trim().toLowerCase(Locale.ROOT));

        Lease lease = reserved != null ? reserved : acquire(p.getId(), waitMs);
        if (lease == null) return skipped(m, p.getId(), DB_BUSY, msgBusy());
        DbQueryRunner.QueryResult qr;
        try {
            qr = runner.run(spec, m.getSqlText(), timeout, maxRows, discard);
        } finally {
            lease.release();
        }
        String at = nowIso();
        if (recordHealth) recordUse(p.getId(), at, qr);
        if (!qr.ok())
            return new Result(STATUS_FAIL, DbQueryEvaluator.LEVEL_NONE, qr.durationMs(), null, false, List.of(), List.of(),
                    List.of(), List.of(), false, List.of(), null, false, qr.error(), qr.failureReason(), at,
                    sqlHash(m.getSqlText()), p.getId(), qr.serverVersion());

        Baseline base = previousMeasures(previousForMonitorId, run);
        DbQueryEvaluator.Outcome outcome = DbQueryEvaluator.evaluate(rules, m.getRuleLogic(), qr, base.measures(), "tr",
                base.unreadable());
        boolean slow = m.getSlowQueryMs() != null && m.getSlowQueryMs() > 0 && qr.durationMs() >= m.getSlowQueryMs();

        MailRows mail = selectMailRows && !Boolean.FALSE.equals(m.getMailIncludeRows())
                ? mailRows(m, qr, masked) : mailColumnsOnly(m, qr, masked);
        return new Result(outcome.status(), outcome.level(), qr.durationMs(), qr.rowCount(), qr.rowCountCapped(),
                qr.columns(), mail.columns, mail.rows, mail.masked, mail.truncated, outcome.results(),
                outcome.metricValue(), slow, null, null, at, sqlHash(m.getSqlText()), p.getId(), qr.serverVersion());
    }

    private void recordUse(Long profileId, String at, DbQueryRunner.QueryResult qr) {
        if (profileId == null) return;
        int ms = (int) Math.min(Integer.MAX_VALUE, qr.durationMs());
        try {
            if (qr.ok()) profiles.recordUseOk(profileId, at, ms);
            else profiles.recordUseError(profileId, at, ms, qr.error());
        } catch (RuntimeException e) {
            log.warn("Bağlantı kullanım sağlığı yazılamadı (#{}): {}", profileId, e.getClass().getSimpleName());
        }
    }

    /** Değişim kuralının tabanı: kural kimliği → önceki ölçü; {@code unreadable} = okuma HATA verdi (ilk çalıştırma değil). */
    record Baseline(Map<String, Double> measures, boolean unreadable) {}

    /**
     * Değişim kuralının tabanı ({@link Run}): zamanlanmış çalıştırmada son zamanlanmış ok satırı; yeniden kontrol / elle
     * kontrolde ondan ÖNCEKİ zamanlanmış ok satırı (onaylanan çalıştırmanın kendi tabanı). Satır yoksa boş (ilk
     * çalıştırma); okunamazsa boş + {@code unreadable} (kural "değerlendirilemedi", D3 — "geçti" sayılmaz).
     */
    private Baseline previousMeasures(Long monitorId, Run run) {
        if (monitorId == null) return new Baseline(Map.of(), false);
        try {
            List<DbQueryCheck> recent = checks.findRecentScheduledOk(monitorId);
            int idx = run == Run.SCHEDULED ? 0 : 1;
            if (recent == null || recent.size() <= idx || recent.get(idx) == null) return new Baseline(Map.of(), false);
            return new Baseline(DbQueryEvaluator.measuresFromJson(recent.get(idx).getRuleResultsJson()), false);
        } catch (RuntimeException e) {
            log.warn("Önceki çalıştırma okunamadı (#{}): {}", monitorId, e.getClass().getSimpleName());
            return new Baseline(Map.of(), true);
        }
    }

    // ── Mail satırları ───────────────────────────────────────────────────────

    private record MailRows(List<String> columns, List<List<String>> rows, List<String> masked, boolean truncated) {}

    private MailRows mailColumnsOnly(DbQueryMonitor m, DbQueryRunner.QueryResult qr, Set<String> masked) {
        int[] idx = selectColumns(m, qr);
        List<String> names = new ArrayList<>(), maskedOut = new ArrayList<>();
        for (int i : idx) {
            String n = qr.columns().get(i).name();
            names.add(n);
            if (masked.contains(n.toLowerCase(Locale.ROOT))) maskedOut.add(n);
        }
        return new MailRows(List.copyOf(names), List.of(), List.copyOf(maskedOut), false);
    }

    private MailRows mailRows(DbQueryMonitor m, DbQueryRunner.QueryResult qr, Set<String> masked) {
        int[] idx = selectColumns(m, qr);
        int maxRows = Math.max(0, settings.getInt(KEY_MAIL_MAX_ROWS, 50));
        List<String> names = new ArrayList<>(), maskedOut = new ArrayList<>();
        boolean[] mask = new boolean[idx.length];
        for (int k = 0; k < idx.length; k++) {
            String n = qr.columns().get(idx[k]).name();
            names.add(n);
            mask[k] = masked.contains(n.toLowerCase(Locale.ROOT));
            if (mask[k]) maskedOut.add(n);
        }
        List<List<String>> rows = new ArrayList<>();
        List<List<Object>> src = qr.rows() == null ? List.of() : qr.rows();
        boolean truncated = false;
        for (List<Object> row : src) {
            if (rows.size() >= maxRows) { truncated = true; break; }
            List<String> out = new ArrayList<>(idx.length);
            for (int k = 0; k < idx.length; k++) {
                if (mask[k]) { out.add(MASK); continue; }
                Object v = idx[k] < row.size() ? row.get(idx[k]) : null;
                String s = DbQueryRunner.cellToText(v);
                if (s != null && s.length() > MAIL_CELL_CHARS) { s = s.substring(0, MAIL_CELL_CHARS - 1) + "…"; truncated = true; }
                out.add(s);
            }
            rows.add(java.util.Collections.unmodifiableList(out));
        }
        if (qr.rowCount() > rows.size() || qr.rowCountCapped() || qr.valuesTruncated()
                || idx.length < qr.columns().size()) truncated = true;
        return new MailRows(List.copyOf(names), List.copyOf(rows), List.copyOf(maskedOut), truncated);
    }

    /**
     * Mail kolonları: {@code mail_columns} doluysa sonuçta bulunanlar (o sırayla, tekil); hiçbiri bulunmazsa ya da boşsa
     * ilk {@code mail-max-columns} kolon.
     */
    private int[] selectColumns(DbQueryMonitor m, DbQueryRunner.QueryResult qr) {
        int max = Math.max(1, settings.getInt(KEY_MAIL_MAX_COLUMNS, 20));
        List<DbQueryRunner.Column> cols = qr.columns() == null ? List.of() : qr.columns();
        List<Integer> picked = new ArrayList<>();
        for (String want : stringList(m.getMailColumnsJson())) {
            if (picked.size() >= max) break;
            for (int i = 0; i < cols.size(); i++) {
                if (cols.get(i).name().equalsIgnoreCase(want.trim()) && !picked.contains(i)) { picked.add(i); break; }
            }
        }
        if (picked.isEmpty()) for (int i = 0; i < cols.size() && picked.size() < max; i++) picked.add(i);
        int[] out = new int[picked.size()];
        for (int i = 0; i < out.length; i++) out[i] = picked.get(i);
        return out;
    }

    // ── Eşzamanlılık izinleri ────────────────────────────────────────────────

    /** Canlı sınırlı izin sayacı: sınır her alışta ayardan okunur (ayar değişince hemen geçerli). */
    static final class Permits {
        private int inUse;

        synchronized boolean acquire(IntSupplier limit, long waitMs) {
            long deadline = System.currentTimeMillis() + Math.max(0, waitMs);
            while (inUse >= Math.max(1, limit.getAsInt())) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) return false;
                try {
                    wait(Math.min(left, 1_000));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            inUse++;
            return true;
        }

        synchronized void release() {
            if (inUse > 0) inUse--;
            notifyAll();
        }

        synchronized int inUse() { return inUse; }
    }

    /** Bağlantı + pod izni; ikisi de alınamazsa hiçbiri tutulmaz. Bırakılış bekleyen taramayı uyandırır. */
    private final class Lease implements Reservation {
        private final Permits connection;
        private final java.util.concurrent.atomic.AtomicBoolean released = new java.util.concurrent.atomic.AtomicBoolean();

        Lease(Permits connection) { this.connection = connection; }

        @Override
        public void release() {
            if (!released.compareAndSet(false, true)) return;
            podPermits.release();
            if (connection != null) connection.release();
            synchronized (releaseSignal) { releaseSignal.notifyAll(); }
        }
    }

    /**
     * Önce bağlantı izni (bekleyerek), sonra pod izni (kalan süreyle) — pod izni tutulurken bağlantı izni BEKLENMEZ (O1):
     * yavaş bir bağlantının izlemeleri pod izinlerini kapıp öteki bağlantıların izlemelerini durduramaz.
     */
    private Lease acquire(Long profileId, long waitMs) {
        long start = System.currentTimeMillis();
        Permits conn = null;
        if (profileId != null) {
            conn = connectionPermits.computeIfAbsent(profileId, k -> new Permits());
            if (!conn.acquire(() -> settings.getInt(KEY_PER_CONNECTION, 2), waitMs)) return null;
        }
        long left = waitMs - (System.currentTimeMillis() - start);
        if (!podPermits.acquire(() -> settings.getInt(KEY_MAX_CONCURRENCY, 4), Math.max(0, left))) {
            if (conn != null) {
                conn.release();
                synchronized (releaseSignal) { releaseSignal.notifyAll(); }
            }
            return null;
        }
        return new Lease(conn);
    }

    /** Testler: izin bekleme sürelerini kısaltır. */
    void setWaitsForTests(long scheduledMs, long interactiveMs) {
        this.scheduledWaitMs = scheduledMs;
        this.interactiveWaitMs = interactiveMs;
    }

    /** Testler: yeniden kontrolün izin beklemesini kısaltır. */
    void setRecheckWaitForTests(long ms) {
        this.recheckWaitMs = ms;
    }

    /** Testler / gözlem: bağlantının şu an tutulan izni. */
    int connectionInUse(Long profileId) {
        Permits p = profileId == null ? null : connectionPermits.get(profileId);
        return p == null ? 0 : p.inUse();
    }

    /** Testler / gözlem: şu an tutulan pod izni. */
    int podInUse() { return podPermits.inUse(); }

    // ── Yardımcılar ──────────────────────────────────────────────────────────

    private Result fail(DbQueryMonitor m, String code, String error, Long profileId, String version) {
        return new Result(STATUS_FAIL, DbQueryEvaluator.LEVEL_NONE, 0, null, false, List.of(), List.of(), List.of(),
                List.of(), false, List.of(), null, false, clip(error, DbQueryErrors.MAX_ERROR_CHARS), code, nowIso(),
                m == null ? null : sqlHash(m.getSqlText()), profileId, version);
    }

    private Result skipped(DbQueryMonitor m, Long profileId, String code, String error) {
        return new Result(STATUS_SKIPPED, DbQueryEvaluator.LEVEL_NONE, 0, null, false, List.of(), List.of(), List.of(),
                List.of(), false, List.of(), null, false, error, code, nowIso(),
                m == null ? null : sqlHash(m.getSqlText()), profileId, null);
    }

    private int maxTimeout() {
        return settings.getInt(KEY_MAX_TIMEOUT, 60);
    }

    private String planMode() {
        String v = settings.getString(KEY_PLAN_MODE, "WARN");
        String u = v == null ? "WARN" : v.trim().toUpperCase(Locale.ROOT);
        return u.equals("OFF") || u.equals("BLOCK") ? u : "WARN";
    }

    /**
     * AĞ sınıfı hata mı (DB_UNREACHABLE, DB_DNS, DB_TIMEOUT) — toplu kesinti / bastırma kuralı için (MonitoringOutageService
     * {@code isOutageClass} karşılığı). Kimlik, SQL, sürücü, engel ve denetim hataları ağ sınıfı DEĞİLDİR.
     */
    public static boolean isNetworkClass(String failureReason) {
        return failureReason != null && DbQueryErrors.NETWORK_CLASS.contains(failureReason);
    }

    /** Sorgunun SHA-256 öneki (16 hex) — değişiklik izi. */
    public static String sqlHash(String sql) {
        if (sql == null) return null;
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(sql.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d).substring(0, 16);
        } catch (Exception e) {
            return null;
        }
    }

    private static String nowIso() {
        return ISO.format(Instant.now().truncatedTo(ChronoUnit.SECONDS));
    }

    private static List<String> stringList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<Object> list = JSON.readValue(json, new TypeReference<List<Object>>() {});
            List<String> out = new ArrayList<>();
            if (list != null) for (Object o : list) if (o != null && !String.valueOf(o).isBlank()) out.add(String.valueOf(o));
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static Set<String> lowerSet(List<String> names) {
        Set<String> out = new LinkedHashSet<>();
        for (String n : names) out.add(n.trim().toLowerCase(Locale.ROOT));
        return out;
    }

    private static int clampInt(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static String clip(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static String msgProfileMissing() {
        return Msg.t("Bağlantı bulunamadı · İzlemenin bağlantısı silinmiş ya da seçilmemiş · İzlemede bir bağlantı seçin.",
                "The connection was not found · The monitor's connection was deleted or not chosen · Choose a connection "
                        + "in the monitor.");
    }

    private static String msgSecretUnreadable() {
        return Msg.t("Bağlantı parolası çözülemedi · Şifreleme anahtarı değişmiş ya da kayıt bozulmuş olabilir · Bağlantıda "
                        + "parolayı yeniden girip kaydedin.",
                "The connection password could not be decrypted · The encryption key may have changed or the record is "
                        + "damaged · Enter the password again in the connection and save it.");
    }

    private static String msgUnknownType() {
        return Msg.t("Bağlantının veritabanı türü tanınmıyor · Desteklenen türler: Oracle, MSSQL, PostgreSQL · Bağlantıyı "
                        + "düzeltin.",
                "The connection's database type is not recognised · Supported types: Oracle, MSSQL, PostgreSQL · Fix the "
                        + "connection.");
    }

    private static String msgBusy() {
        return Msg.t("Şu an çok sayıda veritabanı sorgusu çalışıyor; bu çalıştırma atlandı · Sunucu ya da bağlantı için "
                        + "eşzamanlı sorgu sınırı dolu · Bir sonraki çalıştırma kendiliğinden denenecek; elle denediyseniz "
                        + "birkaç saniye sonra yeniden deneyin.",
                "Too many database queries are running right now; this run was skipped · The concurrency limit for the "
                        + "server or the connection is full · The next run will try again by itself; if you ran it by hand, "
                        + "try again in a few seconds.");
    }
}
