package com.sitemonitor.it;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.AppenderBase;
import com.sitemonitor.SiteMonitorApplication;
import com.sitemonitor.service.schema.SchemaPatchRunner;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.slf4j.LoggerFactory;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.event.ApplicationPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Gerçek PostgreSQL entegrasyon testlerinin JUnit uzantısı ve paylaşılan uygulama bağlamı (2026-10-02, onaylı öneri 28).
 *
 * <p><b>Neden:</b> tüm birim/dilim testleri H2'de (PostgreSQL uyumluluk modu) koşar. Şema yamalarının ~%30'u PostgreSQL'e
 * özgüdür (kısmi indeks, {@code CONCURRENTLY}, {@code text_pattern_ops}, advisory lock, {@code ON CONFLICT … DO UPDATE},
 * {@code LATERAL}); {@link SchemaPatchRunner} H2'de bunların düşmesini BEKLENEN sayıp DEBUG'da yutar. Yani bir yama
 * PostgreSQL'de gerçekten bozulsa hiçbir test görmez — ilk görüldüğü yer prod pod'unun WARN satırıdır. Bu uzantı
 * aynı uygulamayı BOŞ bir PostgreSQL veritabanında açar.
 *
 * <p><b>Bağlantı:</b> {@value #URL_KEY} / {@value #USER_KEY} / {@value #PASSWORD_KEY} — önce {@code -D} sistem
 * özelliği, yoksa ortam değişkeni. URL ya da kullanıcı yoksa sınıf açık bir mesajla ATLANIR (devre dışı raporlanır);
 * yalnız {@value #REQUIRED_KEY}=true verilmişse (CI işi) atlamak yerine DÜŞER.
 *
 * <p><b>Açılış:</b> koşu başına BİR KEZ — (1) veritabanının boş olduğu doğrulanır (dolu bir veritabanına tohum satırı
 * yazmamak için; doluysa test DÜŞER), (2) tam bağlam açılır ({@code ddl-auto=update} + {@code applySchemaPatches()}
 * ApplicationReadyEvent'te), yama özeti alınır, bağlam kapatılır, (3) bağlam İKİNCİ kez aynı (artık yamalanmış) şemaya
 * açılır — idempotentlik kanıtı — ve koşu sonuna dek açık tutulur. Her açılışta Hibernate şema aracının ve genel
 * ERROR günlüklerinin uyarıları toplanır ({@link BootRun}).
 *
 * <p>Yapılandırma test sınıf yolunun {@code application.properties}'i (H2) üzerine komut satırı argümanlarıyla
 * PostgreSQL'e çevrilir; üretimin JPA/Hibernate SQL ayarları ({@code application.properties}) aynen verilir. Uzun
 * aralıklı zamanlanmış işler (açılış taraması, push outbox süpürmesi) test süresince tetiklenmesin diye ertelenir —
 * tohum satırlarını testin ortasında başka bir iş parçacığı sahiplenmesin.
 */
public final class PostgresIt implements ExecutionCondition, BeforeAllCallback {

    /** surefire etiket süzgecinin adı (pom: {@code surefire.excludedGroups}). */
    public static final String TAG = "postgres";
    public static final String URL_KEY = "IT_DB_URL";
    public static final String USER_KEY = "IT_DB_USER";
    public static final String PASSWORD_KEY = "IT_DB_PASSWORD";
    /** {@code true} → bağlantı tanımlı değilse testler atlanmaz, DÜŞER (CI işi bunu verir). */
    public static final String REQUIRED_KEY = "IT_DB_REQUIRED";

    static final String SKIP_MESSAGE = "PostgreSQL entegrasyon testleri ATLANDI: " + URL_KEY + " ve " + USER_KEY
            + " (gerekiyorsa " + PASSWORD_KEY + ") ortam değişkeni ya da -D sistem özelliği olarak verilmedi. Koşmak için"
            + " BOŞ bir PostgreSQL veritabanı gösterin: " + URL_KEY + "=jdbc:postgresql://localhost:5432/<bos_db> "
            + USER_KEY + "=… " + PASSWORD_KEY + "=… mvn -Ppostgres-it test";

    private static final ExtensionContext.Namespace NS = ExtensionContext.Namespace.create(PostgresIt.class);

    private static Booted booted;
    private static Throwable bootFailure;

    /** Bağlantı bilgisi — parola boş olabilir (trust kimlik doğrulaması). */
    public record Config(String url, String user, String password) {
        @Override
        public String toString() {   // parola asla günlüğe / hata mesajına düşmesin
            return "Config[url=" + url + ", user=" + user + ", password=***]";
        }
    }

    /**
     * Bir açılışın kanıtları: yama özeti, Hibernate şema aracı sorunları, ERROR günlükleri, runner'ın "beklenen hata"
     * sayıp sessizce yuttuğu yamalar (PostgreSQL mesajıyla; {@code failed} sayacına GİRMEZLER), runner'ın DEBUG satır
     * sayısı (dinleyicinin her yamayı gerçekten gördüğünün kanıtı — sıfır "sessiz atlama" ancak böyle anlamlıdır) ve süre.
     */
    public record BootRun(SchemaPatchRunner.Summary patches, List<String> schemaToolProblems,
                          List<String> errorLogs, List<String> silentlySkippedPatches, int patchDebugLines, long millis) {}

    /** {@code -D} önce, ortam değişkeni sonra; URL ya da kullanıcı yoksa {@code null}. */
    public static Config config() {
        String url = value(URL_KEY);
        String user = value(USER_KEY);
        if (url == null || user == null) return null;
        String password = value(PASSWORD_KEY);
        return new Config(url, user, password == null ? "" : password);
    }

    private static String value(String key) {
        String v = System.getProperty(key);
        if (v == null || v.isBlank()) v = System.getenv(key);
        return v == null || v.isBlank() ? null : v.trim();
    }

    /** CI'da {@code true}: bağlantı yoksa ATLAMAK yerine DÜŞ — yanlış kurulmuş bir iş "0 test, yeşil" geçmesin. */
    static boolean required() {
        String v = value(REQUIRED_KEY);
        return v != null && (v.equalsIgnoreCase("true") || v.equals("1") || v.equalsIgnoreCase("yes"));
    }

    /**
     * Bağlantı yoksa sınıf "devre dışı" raporlanır (surefire'da Skipped, nedeni bu mesaj) — sınıf düzeyi bir varsayım
     * hatası surefire'da "Tests run: 0" diye nedensiz görünüyordu. {@value #REQUIRED_KEY}=true ise atlanmaz, düşer.
     */
    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        if (config() != null || required()) return ConditionEvaluationResult.enabled("PostgreSQL bağlantısı tanımlı");
        return ConditionEvaluationResult.disabled(SKIP_MESSAGE);
    }

    @Override
    public void beforeAll(ExtensionContext context) {
        Config cfg = config();
        if (cfg == null) {
            throw new IllegalStateException(REQUIRED_KEY + "=true ama bağlantı yok — " + SKIP_MESSAGE);
        }
        ensureBooted(context, cfg);
    }

    private static synchronized void ensureBooted(ExtensionContext context, Config cfg) {
        if (booted != null) return;
        if (bootFailure != null) {
            // İkinci sınıf yeniden açmaya kalkmasın: veritabanı artık boş değil, ikinci hata asıl nedeni gizlerdi.
            throw new IllegalStateException("PostgreSQL açılışı bu koşuda zaten başarısız oldu — ilk hataya bakın", bootFailure);
        }
        try {
            booted = Booted.start(cfg);
            // Kök deposu koşu sonunda kapanır → AutoCloseable bağlamı kapatır (JUnit 6: AutoCloseable kapatma varsayılan).
            context.getRoot().getStore(NS).put(Booted.class, booted);
        } catch (RuntimeException | Error e) {
            bootFailure = e;
            throw e;
        }
    }

    /** İkinci açılışın (açık kalan) uygulaması; yalnız {@link PostgresIntegration} sınıflarından çağrılır. */
    public static Booted app() {
        if (booted == null) {
            throw new IllegalStateException("PostgreSQL bağlamı açılmadı — sınıf @PostgresIntegration ile işaretli mi?");
        }
        return booted;
    }

    /** Açık uygulama bağlamı + iki açılışın kanıtları. */
    public static final class Booted implements AutoCloseable {
        private final Config config;
        private final BootRun first;
        private final BootRun second;
        private final ConfigurableApplicationContext context;

        private Booted(Config config, BootRun first, BootRun second, ConfigurableApplicationContext context) {
            this.config = config;
            this.first = first;
            this.second = second;
            this.context = context;
        }

        static Booted start(Config cfg) {
            requireEmptyPostgres(cfg);
            Opened one = open(cfg);
            one.context().close();
            Opened two = open(cfg);
            if (two.run().patches() == one.run().patches()) {
                two.context().close();
                throw new IllegalStateException("İkinci açılış şema yamalarını koşmadı (SchemaPatchRunner.last() değişmedi)");
            }
            System.out.println("[postgres-it] açılış #1: " + describe(one.run()));
            System.out.println("[postgres-it] açılış #2: " + describe(two.run()));
            return new Booted(cfg, one.run(), two.run(), two.context());
        }

        private static String describe(BootRun r) {
            SchemaPatchRunner.Summary s = r.patches();
            return r.millis() + " ms — yamalar: " + s.applied() + " uygulandı, " + s.noop() + " zaten uygulanmış, "
                    + s.failed() + " başarısız, kilitli=" + s.locked() + "; Hibernate şema sorunu: "
                    + r.schemaToolProblems().size() + ", ERROR günlüğü: " + r.errorLogs().size()
                    + ", yama DEBUG satırı: " + r.patchDebugLines()
                    + ", sessizce atlanan yama: " + r.silentlySkippedPatches().size()
                    + (r.silentlySkippedPatches().isEmpty() ? "" : " " + r.silentlySkippedPatches());
        }

        public ConfigurableApplicationContext context() { return context; }
        public <T> T bean(Class<T> type) { return context.getBean(type); }
        public JdbcTemplate jdbc() { return context.getBean(JdbcTemplate.class); }
        public BootRun firstBoot() { return first; }
        public BootRun secondBoot() { return second; }

        /** Gömülü sunucunun rastgele portu ({@code server.port=0}). */
        public int port() {
            Integer p = context.getEnvironment().getProperty("local.server.port", Integer.class);
            if (p == null) throw new IllegalStateException("local.server.port yok — gömülü sunucu açılmadı");
            return p;
        }

        /** Havuzdan BAĞIMSIZ yeni bir PostgreSQL oturumu (advisory lock çekişmesi gibi oturum düzeyi denemeler için). */
        public Connection newSession() throws SQLException {
            return DriverManager.getConnection(config.url(), config.user(), config.password());
        }

        @Override
        public void close() {
            context.close();
        }
    }

    private record Opened(ConfigurableApplicationContext context, BootRun run) {}

    private static Opened open(Config cfg) {
        SchemaPatchRunner.Summary before = SchemaPatchRunner.last();
        BootLogCapture capture = new BootLogCapture();
        long t0 = System.nanoTime();
        ConfigurableApplicationContext ctx;
        try {
            ctx = new SpringApplicationBuilder(SiteMonitorApplication.class)
                    .listeners(capture)
                    .run(args(cfg));
        } finally {
            capture.detach();
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        SchemaPatchRunner.Summary after = SchemaPatchRunner.last();
        if (after == null || after == before) {
            ctx.close();
            throw new IllegalStateException("Açılış şema yamalarını koşmadı: SchemaPatchRunner.last() güncellenmedi"
                    + " (SchedulerService.runOnStartup ApplicationReadyEvent'te çalışmadı mı?)");
        }
        return new Opened(ctx, new BootRun(after, capture.schemaToolProblems(), capture.errors(), capture.skippedPatches(),
                capture.patchDebugLines(), ms));
    }

    /**
     * Komut satırı argümanı = en yüksek öncelik: test sınıf yolunun H2 {@code application.properties}'ini ezer.
     * JPA/Hibernate SQL ayarları üretim {@code application.properties} ile aynı (sorgu biçimini etkileyenler).
     */
    private static String[] args(Config cfg) {
        List<String> a = new ArrayList<>(List.of(
                "--spring.datasource.url=" + cfg.url(),
                "--spring.datasource.username=" + cfg.user(),
                "--spring.datasource.password=" + cfg.password(),
                "--spring.datasource.driver-class-name=org.postgresql.Driver",
                "--spring.datasource.hikari.maximum-pool-size=10",
                "--spring.datasource.hikari.minimum-idle=2",
                "--spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect",
                "--spring.jpa.hibernate.ddl-auto=update",
                "--spring.jpa.open-in-view=false",
                "--spring.jpa.properties.hibernate.default_batch_fetch_size=20",
                "--spring.jpa.properties.hibernate.jdbc.batch_size=50",
                "--spring.jpa.properties.hibernate.order_inserts=true",
                "--spring.jpa.properties.hibernate.order_updates=true",
                "--spring.jpa.properties.hibernate.query.in_clause_parameter_padding=true",
                // Üretim (prod profili) oturumları JDBC'de tutar; tablolar her açılışta "always" ile kurulur.
                "--spring.session.jdbc.initialize-schema=always",
                "--server.port=0",
                "--spring.main.banner-mode=off",
                // Test süresince tetiklenmesin: açılış taraması (60 sn) ve push outbox süpürmesi tohum satırlarını kiralamasın.
                "--site.monitor.scheduler.startup-check-delay-ms=3600000",
                "--site.monitor.userpush.outbox-sweep-initial-ms=3600000",
                "--site.monitor.userpush.outbox-sweep-ms=3600000",
                "--site.monitor.today.trend-snapshot-initial-ms=3600000"));
        return a.toArray(String[]::new);
    }

    /** Veritabanı PostgreSQL ve geçerli şemada HİÇ tablo yok — aksi halde test tohum satırı yazmayı reddeder. */
    private static void requireEmptyPostgres(Config cfg) {
        try (Connection c = DriverManager.getConnection(cfg.url(), cfg.user(), cfg.password())) {
            String product = c.getMetaData().getDatabaseProductName();
            if (product == null || !product.toLowerCase(Locale.ROOT).contains("postgres")) {
                throw new IllegalStateException(URL_KEY + " bir PostgreSQL veritabanı göstermiyor: " + product);
            }
            List<String> tables = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT table_name FROM information_schema.tables WHERE table_schema = current_schema() "
                  + "ORDER BY table_name LIMIT 10");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) tables.add(rs.getString(1));
            }
            if (!tables.isEmpty()) {
                throw new IllegalStateException(URL_KEY + " BOŞ olmayan bir veritabanı gösteriyor (ilk tablolar: " + tables
                        + "). Bu testler şemayı kurar ve tohum satırı yazar; yalnız bu iş için oluşturulmuş BOŞ bir veritabanı"
                        + " verin (ör. DROP DATABASE …; CREATE DATABASE …).");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("PostgreSQL'e bağlanılamadı (" + cfg.url() + "): " + e.getMessage(), e);
        }
    }

    /**
     * Açılış boyunca günlükleri dinler. Logback, Spring Boot'un günlük sistemi kurulurken sıfırlandığı için ekleyiciler
     * {@link ApplicationPreparedEvent}'te (günlük kurulumu SONRASI, bağlam yenilemesi — Hibernate DDL — ÖNCESİ) takılır.
     * <ul>
     *   <li>kökte: WARN+ — Hibernate şema aracı / JDBC hataları ve tüm ERROR satırları;</li>
     *   <li>{@link SchemaPatchRunner} günlüğünde: DEBUG — runner'ın "beklenen" sayıp SESSİZCE yuttuğu yamalar
     *       ({@code Schema patch skipped: …}). Bu DEBUG satırları konsola gitmez (günlük o süre additive değil);
     *       INFO+ satırları köke elle iletilir, konsol çıktısı değişmez.</li>
     * </ul>
     */
    static final class BootLogCapture implements ApplicationListener<ApplicationPreparedEvent> {

        private static final String RUNNER_LOGGER = SchemaPatchRunner.class.getName();
        private static final String SKIPPED_PREFIX = "Schema patch skipped: ";

        private final List<String> schemaTool = Collections.synchronizedList(new ArrayList<>());
        private final List<String> errors = Collections.synchronizedList(new ArrayList<>());
        private final List<String> skippedPatches = Collections.synchronizedList(new ArrayList<>());
        private final java.util.concurrent.atomic.AtomicInteger patchDebugLines = new java.util.concurrent.atomic.AtomicInteger();
        private volatile Logger root;
        private volatile Logger runner;

        /** Kök: WARN+ toplayıcı. */
        private final AppenderBase<ILoggingEvent> rootTap = new AppenderBase<>() {
            @Override
            protected void append(ILoggingEvent e) {
                if (!e.getLevel().isGreaterOrEqual(Level.WARN)) return;
                String logger = e.getLoggerName();
                String line = e.getLevel() + " " + logger + " — " + e.getFormattedMessage() + throwableText(e.getThrowableProxy());
                // Hibernate 7: DDL hataları ExceptionHandlerLoggedImpl (org.hibernate.tool.schema…), JDBC hataları
                // "org.hibernate.orm.jdbc.error". "org.hibernate.orm.jdbc.warn" BİLİNÇLİ dışarıda: DROP_RECREATE_QUIETLY'nin
                // her açılışta bastığı "constraint … does not exist, skipping" NOTICE'ları (SQLState 00000) hata değildir.
                boolean hibernateSchema = logger.startsWith("org.hibernate.tool.schema")
                        || logger.startsWith("org.hibernate.orm.jdbc.error")
                        || logger.startsWith("org.hibernate.engine.jdbc.spi.SqlExceptionHelper");
                if (hibernateSchema) schemaTool.add(line);
                if (e.getLevel().isGreaterOrEqual(Level.ERROR)) errors.add(line);
            }
        };

        /** SchemaPatchRunner: DEBUG "skipped" toplayıcı + INFO+ satırlarını köke iletir. */
        private final AppenderBase<ILoggingEvent> runnerTap = new AppenderBase<>() {
            @Override
            protected void append(ILoggingEvent e) {
                String msg = e.getFormattedMessage();
                if (e.getLevel() == Level.DEBUG) patchDebugLines.incrementAndGet();
                if (msg != null && msg.startsWith(SKIPPED_PREFIX)) skippedPatches.add(msg.substring(SKIPPED_PREFIX.length()));
                Logger r = root;
                if (r != null && e.getLevel().isGreaterOrEqual(Level.INFO)) r.callAppenders(e);
            }
        };

        @Override
        public void onApplicationEvent(ApplicationPreparedEvent event) {
            Logger r = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
            Logger p = (Logger) LoggerFactory.getLogger(RUNNER_LOGGER);
            rootTap.setContext(r.getLoggerContext());
            rootTap.setName("postgres-it-boot-root");
            rootTap.start();
            runnerTap.setContext(r.getLoggerContext());
            runnerTap.setName("postgres-it-boot-patches");
            runnerTap.start();
            root = r;
            r.addAppender(rootTap);
            p.setLevel(Level.DEBUG);
            p.setAdditive(false);
            p.addAppender(runnerTap);
            runner = p;
        }

        void detach() {
            Logger r = root;
            if (r != null) r.detachAppender(rootTap);
            Logger p = runner;
            if (p != null) {
                p.detachAppender(runnerTap);
                p.setAdditive(true);
                p.setLevel(null);   // kalıtıma dön (logback-test.xml'de bu günlük için ayar yok)
            }
            rootTap.stop();
            runnerTap.stop();
        }

        private static String throwableText(IThrowableProxy t) {
            if (t == null) return "";
            StringBuilder sb = new StringBuilder();
            for (IThrowableProxy p = t; p != null; p = p.getCause()) {
                sb.append(" | ").append(p.getClassName()).append(": ").append(p.getMessage());
            }
            return sb.toString();
        }

        List<String> schemaToolProblems() { synchronized (schemaTool) { return List.copyOf(schemaTool); } }
        List<String> errors() { synchronized (errors) { return List.copyOf(errors); } }
        List<String> skippedPatches() { synchronized (skippedPatches) { return List.copyOf(skippedPatches); } }
        int patchDebugLines() { return patchDebugLines.get(); }
    }
}
