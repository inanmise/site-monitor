package com.sitemonitor.service.schema;

import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Filtrelenen alanlara eksik indeksler — AÇILIŞI BEKLETMEDEN kurulur (2026-10-08, ürün sahibi isteği: "filtrelediğim
 * alanlarda eksik index var mı kontrol et"; kural: "yapılan işlemler mevcudu bozmasın").
 *
 * <p><b>Neden {@code applySchemaPatches()} içinde değil:</b> yamalar ApplicationReadyEvent'te SENKRON koşar ve pod bu
 * sürede {@code REFUSING_TRAFFIC}'tir. Startup probe bütçesi ~300 sn (helm values), dağıtım {@code --atomic --wait
 * --timeout 10m}. {@code CREATE INDEX CONCURRENTLY} yazmaları kilitlemez ama tabloyu iki kez tarar ve açık işlemleri
 * bekler; milyonlarca satırlık {@code uptime_checks} / {@code audit_log} / {@code activity_log} üzerinde bir düzine
 * indeks açılışı dakikalarca uzatabilir. Probe pod'u yarıda öldürürse indeks INVALID kalır ve {@code IF NOT EXISTS}
 * onu bir daha KURMAZ (sessiz kayıp); süre aşılırsa {@code --atomic} sürümü geri alır. Bu yüzden:
 * <ul>
 *   <li><b>PostgreSQL:</b> açılışta hiçbir şey kurulmaz. Pod trafiğe HAZIR olduktan SONRA tek bir daemon iş parçacığı
 *       ({@link #startInBackground()}) indeksleri sırayla {@code CONCURRENTLY} kurar. Oturum bağlantı havuzunun DIŞINDA
 *       açılır (dakikalar süren DDL havuzdan bağlantı yemesin, Hikari sızıntı uyarısı — 60 sn — düşmesin).</li>
 *   <li><b>Pod'lar arası tek kurucu:</b> oturum düzeyi advisory lock ({@link #LOCK_KEY}, yama kilidinden AYRI); kilit
 *       doluysa en fazla {@link #LOCK_WAIT_MS} beklenir. Her indeksten önce başka bir pod'un açılış yamalarını koşup
 *       koşmadığına bakılır ({@link SchemaPatchRunner#LOCK_KEY} başkasındaysa beklenir): o pod'un senkron yamaları aynı
 *       tabloda bizim CONCURRENTLY kurulumumuzu beklemesin (SHARE UPDATE EXCLUSIVE kendisiyle çakışır).</li>
 *   <li><b>Kendini onarır:</b> yarıda kalmış (pod öldü, iptal edildi) INVALID indeks bir sonraki açılışta düşürülüp
 *       yeniden kurulur; başka bir oturumun ŞU AN kurduğu indekse ({@code pg_stat_progress_create_index}) dokunulmaz.
 *       Kurulum hata verirse geride kalan INVALID indeks hemen düşürülür (yazımlarda boşuna bakım yükü olmasın) ve
 *       {@link #RETRY_DELAY_MS} sonra bir kez daha denenir (geçici kilitlenme); yine olmazsa sonraki açılış dener.</li>
 *   <li><b>PostgreSQL dışı (testlerdeki H2):</b> taşınabilir yedek DDL açılışta, normal yama gibi senkron koşar
 *       ({@link #applyFallbacks}); PG'ye özgü (fonksiyonel / kısmi) indekslerin yedeği yoktur.</li>
 * </ul>
 * İndeks yalnız okuma planını hızlandırır: kurulana kadar sorgular bugünkü gibi (indekssiz) çalışır, sonuçları değişmez.
 * Mevcut hiçbir indeks düşürülmez ya da yeniden adlandırılmaz.
 *
 * <p><b>Yeni indeks eklemek:</b> {@link #CATALOG}'a {@code CREATE INDEX CONCURRENTLY IF NOT EXISTS <ad> ON <tablo>(…)}
 * dizgesi olarak ekleyin (düz dizge — postgres-it kaynağı tarayıp adı geçen her indeksin kurulduğunu doğrular). Kapı:
 * {@code DeferredIndexBuilderTest}, {@code DeferredIndexFallbackH2Test}, {@code SchemaPatchPostgresTest}.
 */
public final class DeferredIndexBuilder {

    private static final Logger log = LoggerFactory.getLogger(DeferredIndexBuilder.class);

    /** Advisory lock anahtarı — "SMIDXBLD" baytları; yama kilidinden ({@link SchemaPatchRunner#LOCK_KEY}) ayrı. */
    public static final long LOCK_KEY = 0x534D_4944_5842_4C44L;
    /** Başka bir pod kurarken en fazla bu kadar beklenir (sonra bu açılışta vazgeçilir; sonraki açılış yeniden dener). */
    static final long LOCK_WAIT_MS = 30L * 60_000;
    /** Başka bir pod açılış yamalarını koşarken bir sonraki indekse geçmeden en fazla bu kadar beklenir. */
    static final long PATCH_YIELD_MS = 10L * 60_000;
    static final long POLL_MS = 5_000;
    /**
     * Başarısız kurulumlar bu kadar sonra BİR KEZ daha denenir: geçici nedenler (aynı tabloda eşzamanlı ANALYZE / başka
     * pod'un DDL'i ile kilitlenme, 40P01) pod haftalarca yeniden başlamasa da indeksi kalıcı olarak eksik bırakmasın.
     */
    static final long RETRY_DELAY_MS = 60_000;

    /**
     * Bir ertelenmiş indeks: PostgreSQL DDL'i (CONCURRENTLY + IF NOT EXISTS) ve PostgreSQL dışı için yedek DDL
     * ({@code null} = PG'ye özgü, H2'de kurulmaz).
     */
    public record Spec(String name, String table, String postgresDdl, String fallbackDdl) {

        private static final Pattern PG_DDL = Pattern.compile(
                "(?i)^CREATE\\s+(?:UNIQUE\\s+)?INDEX\\s+CONCURRENTLY\\s+IF\\s+NOT\\s+EXISTS\\s+(\\w+)\\s+ON\\s+(\\w+)\\s*\\(.+");

        public Spec {
            if (name == null || table == null || postgresDdl == null) throw new IllegalArgumentException("eksik indeks tanımı");
        }

        /** Her iki veritabanında aynı indeks: yedek = PostgreSQL DDL'inin CONCURRENTLY'siz hâli. */
        static Spec portable(String postgresDdl) {
            return parse(postgresDdl, postgresDdl.replaceFirst("(?i)\\s+CONCURRENTLY\\s+", " "));
        }

        /** Yalnız PostgreSQL (fonksiyonel / kısmi indeks): H2'de kurulmaz. */
        static Spec postgresOnly(String postgresDdl) {
            return parse(postgresDdl, null);
        }

        /** PostgreSQL'e özgü ek (ör. INCLUDE) taşıyan indeks: H2'de verilen sade hâli kurulur. */
        static Spec withFallback(String postgresDdl, String fallbackDdl) {
            return parse(postgresDdl, fallbackDdl);
        }

        static Spec parse(String postgresDdl, String fallbackDdl) {
            Matcher m = PG_DDL.matcher(postgresDdl.trim());
            if (!m.matches()) {
                throw new IllegalArgumentException("Ertelenmiş indeks CREATE INDEX CONCURRENTLY IF NOT EXISTS olmalı: " + postgresDdl);
            }
            return new Spec(m.group(1).toLowerCase(Locale.ROOT), m.group(2).toLowerCase(Locale.ROOT), postgresDdl, fallbackDdl);
        }
    }

    /**
     * 2026-10-08 indeks denetiminin eklediği indeksler (koddan okunan sorgu yollarına göre; EXPLAIN'siz). Sıra = kurulum
     * sırası: önce sıcak yollar. NOC teslim izinin indeksi küçük tabloda olduğu için {@code NocSchemaPatches}'te.
     */
    public static final List<Spec> CATALOG = List.of(
            // ── SICAK ──
            // UptimeCheckRepository.findLatestPerDomainPort: LATERAL (… WHERE u.domain = i.domain ORDER BY u.checked_at
            // DESC LIMIT 1). idx_uc_domain_port_checked(domain, port, checked_at) sorguda port olmadığından sıralamaya
            // hizmet etmez → envanterdeki her alan adı için tüm geçmiş taranıp sıralanıyordu.
            Spec.portable("CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_uc_domain_checked ON uptime_checks(domain, checked_at)"),
            // pagespeed_checks: checked_at tek başına hiç indekslenmemişti (diğer bütün kontrol tablolarında var) — filo
            // sparkline'ları (MonitorSparklineService), saatlik/günlük rollup (WHERE checked_at >= ? AND < ?) ve retention.
            Spec.portable("CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_ps_checked_at ON pagespeed_checks(checked_at)"),
            // ActivityLogRepository.findRecentForMonitor (monitor_type = ? AND monitor_id = ? ORDER BY activity_time DESC)
            // ve statusSequence (… AND activity_time >= ?) — idx_act_type_mon zamanı taşımıyordu.
            Spec.portable("CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_act_type_mon_time ON activity_log(monitor_type, monitor_id, activity_time)"),
            // Alarm geçmişi zenginleştirmesi: domain IN (…) + created_at aralığı / sırası, alan adı + tip gruplu
            // (countRecentByDomainAndType, summarizeHistoryByDomainAndType, findSignatureTimeline,
            // findByDomainOrderByCreatedAtDesc). INCLUDE (resolved_at): özet sorgusu (MAX(resolved_at)) yalnız indeksten
            // okunur — INCLUDE PostgreSQL'e özgü, H2'de sade hâli.
            Spec.withFallback(
                    "CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_ae_domain_type_created ON alert_events(domain, alert_type, created_at) INCLUDE (resolved_at)",
                    "CREATE INDEX IF NOT EXISTS idx_ae_domain_type_created ON alert_events(domain, alert_type, created_at)"),
            // ScriptedCheckRepository.monitorIdsWithSuccess: SELECT DISTINCT monitor_id WHERE ok = true — senaryo listesi
            // her istekte tüm kontrol serisini tarıyordu. Kısmi indeks (yalnız başarılı satırlar) PostgreSQL'e özgü.
            Spec.postgresOnly("CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_sc_success_monitor ON scripted_checks(monitor_id) WHERE ok = true"),
            // ── ILIK ──
            // Kişinin kendi denetim kaydı / giriş geçmişi: LOWER(a.actor) = :actor (findOwnFiltered, findOwnLogins,
            // findLatestOwnLogin, findDistinctLoginUserAgents, findLoginStatEventsForActor) — idx_audit_actor ham kolonda
            // olduğu için LOWER(...) ifadesine hizmet etmez. Fonksiyonel indeks PostgreSQL'e özgü.
            Spec.postgresOnly("CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_audit_lower_actor_type_time ON audit_log(lower(actor), event_type, event_time)"),
            // PushLogQueryService → findByBatchIdOrderByIdAsc (teslimat günlüğü ayrıntısı, aynı toplu isteğin satırları).
            Spec.portable("CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_push_batch ON user_push_deliveries(batch_id)"),
            // Push geçmişim + teslimat günlüğü süzgeci: LOWER(d.username) = LOWER(:username) [+ created_at >= :since]
            // (myHistory, myStatusCounts, mySummarizedCount, search, findStormNoticeRowsForViewer). PostgreSQL'e özgü.
            Spec.postgresOnly("CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_push_lower_user_created ON user_push_deliveries(lower(username), created_at)"),
            // MonitorChangeLogRepository.lastActiveChange: resource_id IN (…) resource_kind'siz — idx_mchg_resource
            // (resource_kind, resource_id) önde resource_kind taşıdığından kullanılamıyordu. Sorgu DEĞİŞMEDİ.
            Spec.portable("CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_mchg_resource_id ON monitor_change_log(resource_id)"),
            // Rollup'lar: durum sayfası 7 günlük kullanılabilirlik (day >= ? AND day < ?, tür süzgeçli, anahtarsız) ve
            // retention (day / hour_bucket < kesim). Birincil anahtar (monitor_type, monitor_key, …) bu aralığa hizmet etmez.
            Spec.portable("CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_mcd_day ON monitor_check_daily(day)"),
            Spec.portable("CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_mch_hour ON monitor_check_hourly(hour_bucket)"),
            // NotificationLogRepository.findByTriggerInOrderBySentAtDesc (haftalık erişilebilirlik giden mail arşivi):
            // trigger IN (…) ORDER BY sent_at DESC — (trigger) yerine (trigger, sent_at): süzgeç + sıra aynı indekste.
            Spec.portable("CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_nl_trigger_sent ON notification_logs(trigger, sent_at)"),
            // pagespeed_resources retention / BREACH temizliği: keep_reason = 'BREACH' AND checked_at < ? — eşitlik önde,
            // aralık arkada (checked_at tek başına yerine; aynı sorguya daha dar hizmet eder).
            Spec.portable("CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_psr_reason_checked ON pagespeed_resources(keep_reason, checked_at)"));

    /** Bir koşunun özeti. {@code locked=false} → kilit alınamadı, hiçbir şey denenmedi. */
    public record Result(int built, int rebuilt, int valid, int skipped, int failed, boolean locked, long millis) {}

    /** Bir indeksin veritabanındaki durumu. */
    enum State { ABSENT, VALID, INVALID, BUILDING }

    /** Veritabanı oturumu — gerçeği {@link JdbcSession}; birim testinde sahte. */
    interface Session extends AutoCloseable {
        boolean tryLock(long key) throws SQLException;
        void unlock(long key);
        boolean tableExists(String table) throws SQLException;
        State state(String index) throws SQLException;
        void execute(String ddl) throws SQLException;
        @Override void close();
    }

    @FunctionalInterface
    interface SessionFactory { Session open() throws SQLException; }

    @FunctionalInterface
    interface Sleeper { void sleep(long ms) throws InterruptedException; }

    private static final AtomicReference<CompletableFuture<Result>> LAST = new AtomicReference<>();

    /** Son başlatılan arka plan koşusu (postgres-it bekler); hiç başlatılmadıysa null. */
    public static CompletableFuture<Result> lastRun() { return LAST.get(); }

    private final List<Spec> specs;
    private final BooleanSupplier postgres;
    private final SessionFactory sessions;
    private final Sleeper sleeper;
    private final long lockWaitMs;
    private final long patchYieldMs;
    private final long pollMs;
    private final long retryDelayMs;

    public DeferredIndexBuilder(JdbcTemplate jdbc) {
        this(CATALOG, memo(() -> isPostgres(jdbc.getDataSource())), () -> JdbcSession.open(jdbc.getDataSource()),
                Thread::sleep, LOCK_WAIT_MS, PATCH_YIELD_MS, POLL_MS, RETRY_DELAY_MS);
    }

    DeferredIndexBuilder(List<Spec> specs, BooleanSupplier postgres, SessionFactory sessions, Sleeper sleeper,
                         long lockWaitMs, long patchYieldMs, long pollMs, long retryDelayMs) {
        this.specs = List.copyOf(specs);
        this.postgres = postgres;
        this.sessions = sessions;
        this.sleeper = sleeper;
        this.lockWaitMs = lockWaitMs;
        this.patchYieldMs = patchYieldMs;
        this.pollMs = Math.max(1, pollMs);
        this.retryDelayMs = Math.max(0, retryDelayMs);
    }

    /**
     * Açılış (yama kilidi altında, senkron): PostgreSQL DEĞİLSE taşınabilir yedekleri normal yama olarak koşar
     * ({@code patch} = {@code SchemaPatchRunner#patch}); PostgreSQL'de hiçbir şey yapmaz — kurulum hazır olduktan sonra.
     */
    public void applyFallbacks(Consumer<String> patch) {
        if (postgres.getAsBoolean()) return;
        for (Spec s : specs) {
            if (s.fallbackDdl() != null) patch.accept(s.fallbackDdl());
        }
    }

    /**
     * Trafiğe HAZIR olduktan sonra çağrılır: PostgreSQL'de kurulumu bir daemon iş parçacığında başlatır ve hemen döner
     * (çağıranı hiçbir koşulda bekletmez, istisna sızdırmaz). PostgreSQL değilse {@code null}.
     */
    public CompletableFuture<Result> startInBackground() {
        if (!postgres.getAsBoolean()) return null;
        CompletableFuture<Result> done = new CompletableFuture<>();
        LAST.set(done);
        Thread t = new Thread(() -> {
            try {
                done.complete(runBlocking());
            } catch (Throwable e) {
                log.warn("Ertelenmiş indeks kurulumu yarıda kaldı (sonraki açılışta yeniden denenecek): {}", firstLine(e));
                done.complete(new Result(0, 0, 0, 0, 1, false, 0));
            }
        }, "schema-deferred-indexes");
        t.setDaemon(true);
        t.start();
        return done;
    }

    /** Oturum açar, kilit altında katalogdaki her indeksi kurar/onarır ve özeti döner (iş parçacığı + postgres-it). */
    Result runBlocking() throws SQLException, InterruptedException {
        try (Session s = sessions.open()) {
            return run(s);
        }
    }

    Result run(Session s) throws InterruptedException {
        long t0 = System.nanoTime();
        if (!acquire(s)) {
            log.warn("Ertelenmiş indeks kilidi {} dk içinde alınamadı — başka bir pod hâlâ kuruyor olabilir; bu açılışta"
                    + " atlandı (sonraki açılış yeniden dener)", lockWaitMs / 60_000);
            return new Result(0, 0, 0, specs.size(), 0, false, elapsedMs(t0));
        }
        int[] n = new int[Outcome.values().length];
        try {
            List<Spec> failedOnce = new ArrayList<>();
            for (Spec spec : specs) {
                Outcome o = buildOne(s, spec, false);
                if (o == Outcome.FAILED) failedOnce.add(spec);
                else n[o.ordinal()]++;
            }
            if (!failedOnce.isEmpty()) {
                sleeper.sleep(retryDelayMs);
                for (Spec spec : failedOnce) n[buildOne(s, spec, true).ordinal()]++;
            }
        } finally {
            s.unlock(LOCK_KEY);
        }
        Result r = new Result(n[Outcome.BUILT.ordinal()], n[Outcome.REBUILT.ordinal()], n[Outcome.VALID.ordinal()],
                n[Outcome.SKIPPED.ordinal()], n[Outcome.FAILED.ordinal()], true, elapsedMs(t0));
        if (r.built() + r.rebuilt() + r.failed() > 0) {
            log.info("Ertelenmiş indeksler: {} kuruldu, {} yeniden kuruldu, {} zaten geçerli, {} atlandı, {} başarısız — {} ms",
                    r.built(), r.rebuilt(), r.valid(), r.skipped(), r.failed(), r.millis());
        } else {
            log.debug("Ertelenmiş indeksler: {} zaten geçerli, {} atlandı", r.valid(), r.skipped());
        }
        return r;
    }

    private enum Outcome { BUILT, REBUILT, VALID, SKIPPED, FAILED }

    private Outcome buildOne(Session s, Spec spec, boolean lastAttempt) throws InterruptedException {
        boolean rebuild = false;
        try {
            if (!s.tableExists(spec.table())) {
                log.debug("Ertelenmiş indeks atlandı (tablo yok): {} ON {}", spec.name(), spec.table());
                return Outcome.SKIPPED;
            }
            State st = s.state(spec.name());
            if (st == State.VALID) return Outcome.VALID;
            if (st == State.BUILDING) {
                log.info("Ertelenmiş indeks atlandı: {} şu an başka bir oturumda kuruluyor", spec.name());
                return Outcome.SKIPPED;
            }
            yieldToSchemaPatching(s);
            if (st == State.INVALID) {
                rebuild = true;
                log.warn("Yarıda kalmış INVALID indeks düşürülüp yeniden kuruluyor: {}", spec.name());
                s.execute("DROP INDEX CONCURRENTLY IF EXISTS " + spec.name());
            }
            long t0 = System.nanoTime();
            s.execute(spec.postgresDdl());
            log.info("Schema patch applied (deferred index built): {} ON {} — {} ms", spec.name(), spec.table(), elapsedMs(t0));
            return rebuild ? Outcome.REBUILT : Outcome.BUILT;
        } catch (SQLException | RuntimeException e) {
            if (lastAttempt) {
                log.warn("⚠ Şema yaması BAŞARISIZ (ertelenmiş indeks; sonraki açılış yeniden dener): {} — {}",
                        spec.name(), firstLine(e));
            } else {
                log.warn("Ertelenmiş indeks kurulamadı, {} sn sonra yeniden denenecek: {} — {}",
                        retryDelayMs / 1000, spec.name(), firstLine(e));
            }
            dropIfInvalid(s, spec.name());
            return Outcome.FAILED;
        }
    }

    /** Kurulum hata verdiyse geride kalan INVALID indeksi düşürür (yazımlar onu boşuna güncellemesin); hata yutulur. */
    private void dropIfInvalid(Session s, String index) {
        try {
            if (s.state(index) == State.INVALID) s.execute("DROP INDEX CONCURRENTLY IF EXISTS " + index);
        } catch (Exception e) {
            log.warn("INVALID indeks düşürülemedi ({}): {} — sonraki açılış yeniden dener", index, firstLine(e));
        }
    }

    /**
     * Başka bir pod açılış yamalarını koşuyorsa (yama kilidi onda) bitmesini bekler — onun senkron yamaları aynı tabloda
     * bizim CONCURRENTLY kurulumumuzu beklemesin (readiness'ı gecikmesin). Kilit yalnız yoklanır, hemen bırakılır.
     */
    private void yieldToSchemaPatching(Session s) throws InterruptedException {
        long deadline = System.currentTimeMillis() + patchYieldMs;
        boolean logged = false;
        while (true) {
            try {
                if (s.tryLock(SchemaPatchRunner.LOCK_KEY)) {
                    s.unlock(SchemaPatchRunner.LOCK_KEY);
                    return;
                }
            } catch (SQLException e) {
                return;   // yoklanamadı → beklemeden devam (en kötü ihtimalle bugünkü davranış)
            }
            if (System.currentTimeMillis() >= deadline) return;
            if (!logged) { log.info("Başka bir pod şema yamalarını koşuyor; ertelenmiş indeks kurulumu bekliyor…"); logged = true; }
            sleeper.sleep(pollMs);
        }
    }

    private boolean acquire(Session s) throws InterruptedException {
        long deadline = System.currentTimeMillis() + lockWaitMs;
        boolean logged = false;
        while (true) {
            try {
                if (s.tryLock(LOCK_KEY)) return true;
            } catch (SQLException e) {
                log.warn("Ertelenmiş indeks kilidi denenemedi: {}", firstLine(e));
                return false;
            }
            if (System.currentTimeMillis() >= deadline) return false;
            if (!logged) { log.info("Başka bir pod ertelenmiş indeksleri kuruyor; bekleniyor…"); logged = true; }
            sleeper.sleep(pollMs);
        }
    }

    private static long elapsedMs(long t0) { return (System.nanoTime() - t0) / 1_000_000; }

    private static String firstLine(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        String m = t.getMessage() != null ? t.getMessage() : e.getClass().getSimpleName();
        int i = m.indexOf('\n');
        String line = i >= 0 ? m.substring(0, i) : m;
        return line.length() > 200 ? line.substring(0, 200) + "…" : line;
    }

    private static BooleanSupplier memo(BooleanSupplier s) {
        AtomicReference<Boolean> v = new AtomicReference<>();
        return () -> {
            Boolean b = v.get();
            if (b == null) { b = s.getAsBoolean(); v.set(b); }
            return b;
        };
    }

    static boolean isPostgres(DataSource ds) {
        if (ds == null) return false;
        try (Connection c = ds.getConnection()) {
            String p = c.getMetaData().getDatabaseProductName();
            return p != null && p.toLowerCase(Locale.ROOT).contains("postgres");
        } catch (Exception e) {
            return false;
        }
    }

    /** Gerçek PostgreSQL oturumu — mümkünse havuz dışı, otomatik commit (CONCURRENTLY işlem bloğunda koşamaz). */
    static final class JdbcSession implements Session {

        private static final String STATE_SQL = """
                SELECT i.indisvalid FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace
                JOIN pg_index i ON i.indexrelid = c.oid
                WHERE n.nspname = current_schema() AND c.relname = ?
                """;
        /** PostgreSQL 12+; yoksa (eski sürüm) INVALID indeks "yarıda kalmış" sayılır. */
        private static final String BUILDING_SQL = """
                SELECT EXISTS (SELECT 1 FROM pg_stat_progress_create_index p
                JOIN pg_class c ON c.oid = p.index_relid
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = current_schema() AND c.relname = ?)
                """;

        private final Connection conn;
        private final boolean pooled;

        private JdbcSession(Connection conn, boolean pooled) {
            this.conn = conn;
            this.pooled = pooled;
        }

        static JdbcSession open(DataSource ds) throws SQLException {
            if (ds == null) throw new SQLException("DataSource yok");
            Connection c = null;
            try {
                if (ds.isWrapperFor(HikariDataSource.class)) {
                    HikariDataSource h = ds.unwrap(HikariDataSource.class);
                    if (h.getJdbcUrl() != null) {
                        Properties p = new Properties();
                        p.putAll(h.getDataSourceProperties());
                        if (h.getUsername() != null) p.setProperty("user", h.getUsername());
                        if (h.getPassword() != null) p.setProperty("password", h.getPassword());
                        p.putIfAbsent("ApplicationName", "sitemonitor-deferred-index");
                        c = DriverManager.getConnection(h.getJdbcUrl(), p);
                    }
                }
            } catch (Exception e) {
                log.debug("Havuz dışı oturum açılamadı, havuzdan alınıyor: {}", firstLine(e));
            }
            boolean pooled = c == null;
            if (pooled) c = ds.getConnection();
            try {
                c.setAutoCommit(true);
                // Rol düzeyinde bir statement_timeout büyük tabloda kurulumu her açılışta yarıda kesip INVALID bırakmasın.
                try (Statement st = c.createStatement()) { st.execute("SET statement_timeout = 0"); }
            } catch (SQLException e) {
                try { c.close(); } catch (SQLException ignore) { /* zaten düşüyor */ }
                throw e;
            }
            return new JdbcSession(c, pooled);
        }

        @Override
        public boolean tryLock(long key) throws SQLException {
            try (PreparedStatement ps = conn.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
                ps.setLong(1, key);
                try (ResultSet rs = ps.executeQuery()) { return rs.next() && rs.getBoolean(1); }
            }
        }

        @Override
        public void unlock(long key) {
            try (PreparedStatement ps = conn.prepareStatement("SELECT pg_advisory_unlock(?)")) {
                ps.setLong(1, key);
                ps.execute();
            } catch (SQLException e) {
                log.debug("Advisory lock bırakılamadı (oturum kapanınca zaten bırakılır): {}", firstLine(e));
            }
        }

        @Override
        public boolean tableExists(String table) throws SQLException {
            try (PreparedStatement ps = conn.prepareStatement("SELECT to_regclass(?) IS NOT NULL")) {
                ps.setString(1, table);
                try (ResultSet rs = ps.executeQuery()) { return rs.next() && rs.getBoolean(1); }
            }
        }

        @Override
        public State state(String index) throws SQLException {
            try (PreparedStatement ps = conn.prepareStatement(STATE_SQL)) {
                ps.setString(1, index);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return State.ABSENT;
                    if (rs.getBoolean(1)) return State.VALID;
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(BUILDING_SQL)) {
                ps.setString(1, index);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() && rs.getBoolean(1) ? State.BUILDING : State.INVALID;
                }
            } catch (SQLException e) {
                return State.INVALID;
            }
        }

        @Override
        public void execute(String ddl) throws SQLException {
            try (Statement st = conn.createStatement()) { st.execute(ddl); }
        }

        @Override
        public void close() {
            try {
                if (pooled) {
                    try (Statement st = conn.createStatement()) { st.execute("RESET statement_timeout"); }
                }
            } catch (SQLException ignore) {
                // havuza dönüşte bağlantı zaten doğrulanır
            } finally {
                try { conn.close(); } catch (SQLException ignore) { /* kapanış */ }
            }
        }
    }
}
