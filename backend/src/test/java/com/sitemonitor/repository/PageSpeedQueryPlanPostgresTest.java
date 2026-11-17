package com.sitemonitor.repository;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sitemonitor.it.PostgresIntegration;
import com.sitemonitor.it.PostgresIt;
import com.sitemonitor.service.schema.DeferredIndexBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sayfa Hızı sorgularının planı GERÇEK PostgreSQL'de (inceleme 2026-11-17, madde 9) — {@code mvn -Ppostgres-it test}.
 *
 * <p>Repository metotları gerçekten çağrılır, Hibernate'in ÜRETTİĞİ SQL yakalanır ({@code org.hibernate.SQL}) ve planı
 * üretime benzer bir LABORATUVAR şemasında okunur (aynı tablolar, canlı şemanın dizinleri + ertelenmiş katalog; zaman sırasıyla
 * iç içe 30 izleme ölçümü, 120 izleme kaynağı). Kanıtlanan:
 * <ul>
 *   <li>{@code PageSpeedCheckRepository.growthWindow} (büyüme referansı, her ölçümde): özel ve genel planda bileşik
 *       {@code idx_ps_monitor_checked} dizininden, {@code monitor_id} süzgeçsiz, sıralamasız — tablo taranmaz;</li>
 *   <li>{@code PageSpeedResourceRepository.waterfall} (şelale, tek kopya kuralıyla): ölçüm kimliği dizininden
 *       ({@code idx_psr_check}), tablo taranmaz — ana sorgu da {@code NOT EXISTS} alt sorgusu da;</li>
 *   <li>{@code PageSpeedResourceRepository.findStoredImage} (görsel aracı köprüsü): izleme kimliği önce gelen dizinden
 *       ({@code idx_psr_monitor_reason}), tablo taranmaz — bulunamayan adres tüm tabloyu okumaz.</li>
 *   <li>2026-11-17 (performans): tek kopya kuralının {@code NOT EXISTS}'i satır başına SubPlan değil TEK InitPlan;
 *       {@code breachSnapshots} izleme önce gelen ertelenmiş {@code idx_psr_monitor_reason_checked}'ten (başka izlemelerin
 *       BREACH satırları süzgeçle elenmez); {@code findStoredImageInCheck} ölçüm kimliği dizininden.</li>
 * </ul>
 * Diğer testlerin satırlarına ve istatistiklerine dokunulmaz; şema sonda düşürülür.
 */
@PostgresIntegration
class PageSpeedQueryPlanPostgresTest {

    private static final String LAB = "ps_plan_" + UUID.randomUUID().toString().substring(0, 8);
    /** Laboratuvar dışında satırı olmayan kimlik — repository çağrıları canlı şemada boş döner. */
    private static final long MONITOR = 913L;
    private static final long CHECK = 913L * 100_000L + 7;
    private static final String FROM = "2026-10-20T00:00:00";
    private static final String TO = "2026-10-27T00:00:00";
    private static final Pattern MONITOR_FILTER = Pattern.compile("Filter:[^\\n]*\\bmonitor_id\\b");

    private static JdbcTemplate jdbc() { return PostgresIt.app().jdbc(); }

    private static <T> T bean(Class<T> type) { return PostgresIt.app().bean(type); }

    @BeforeAll
    static void lab() throws Exception {
        CompletableFuture<DeferredIndexBuilder.Result> run = DeferredIndexBuilder.lastRun();
        if (run != null) run.get(5, TimeUnit.MINUTES);
        String live = jdbc().queryForObject("SELECT current_schema()", String.class);
        jdbc().execute("CREATE SCHEMA " + LAB);
        clone(live, "pagespeed_checks");
        clone(live, "pagespeed_resources");
        // 30 izleme × 4320 ölçüm (10 dk'da bir, 30 gün), zaman sırasıyla iç içe
        String at = "to_char(timestamp '2026-10-01' + s * interval '600 second', 'YYYY-MM-DD\"T\"HH24:MI:SS')";
        jdbc().execute("INSERT INTO " + LAB + ".pagespeed_checks (monitor_id, checked_at, ok, total_bytes, request_count,"
                + " capped, bytes_truncated, time_truncated, extended, manual, recheck, breached_metrics)"
                + " SELECT m, " + at + ", true, 1000000 + s, 60, false, false, NULL, NULL, NULL, NULL,"
                + " CASE WHEN (s + m) % 97 = 0 THEN 'SIZE' END"
                + " FROM generate_series(0, 4319) s CROSS JOIN generate_series(901, 930) m ORDER BY s, m");
        // Kaynaklar (120 izleme): her izlemenin son ölçümü (LATEST, 100 satır) + 20 ihlal anının donmuş kopyası (BREACH, 100'er satır),
        // ölçüm başına toplu yazıldıkları için fiziksel olarak ölçüm ölçüm kümeli; izlemeler zaman sırasıyla iç içe
        String rowAt = "to_char(timestamp '2026-10-01' + k * interval '36 hour', 'YYYY-MM-DD\"T\"HH24:MI:SS')";
        jdbc().execute("INSERT INTO " + LAB + ".pagespeed_resources (monitor_id, check_id, checked_at, url, type, bytes,"
                + " duration_ms, start_ms, status_code, third_party, truncated, keep_reason)"
                + " SELECT m, m * 100000 + k, " + rowAt + ", 'https://site' || m || '.example.test/r/' || k || '/' || i,"
                + " CASE WHEN i % 4 = 0 THEN 'IMG' WHEN i % 4 = 1 THEN 'JS' WHEN i % 4 = 2 THEN 'CSS' ELSE 'OTHER' END,"
                + " 1000 + i, 20 + i % 50, i * 3, 200, i % 7 = 0, false,"
                + " CASE WHEN k = 20 THEN 'LATEST' ELSE 'BREACH' END"
                + " FROM generate_series(0, 20) k CROSS JOIN generate_series(901, 1020) m CROSS JOIN generate_series(0, 99) i"
                + " ORDER BY k, m, i");
        jdbc().execute("VACUUM (ANALYZE) " + LAB + ".pagespeed_checks");
        jdbc().execute("VACUUM (ANALYZE) " + LAB + ".pagespeed_resources");
    }

    @AfterAll
    static void dropLab() {
        jdbc().execute("DROP SCHEMA IF EXISTS " + LAB + " CASCADE");
    }

    /** Tabloyu laboratuvara kopyalar: kolonlar (NOT NULL'suz — tohum yalnız gereken kolonları yazar), canlı dizinler, katalog. */
    private static void clone(String live, String table) {
        jdbc().execute("CREATE TABLE " + LAB + "." + table + " (LIKE " + live + "." + table
                + " INCLUDING DEFAULTS INCLUDING IDENTITY)");
        for (String col : jdbc().queryForList("SELECT column_name FROM information_schema.columns WHERE table_schema = ?"
                + " AND table_name = ? AND is_nullable = 'NO' AND column_name <> 'id'", String.class, LAB, table)) {
            jdbc().execute("ALTER TABLE " + LAB + "." + table + " ALTER COLUMN \"" + col + "\" DROP NOT NULL");
        }
        for (String def : jdbc().queryForList("SELECT indexdef FROM pg_indexes WHERE schemaname = ? AND tablename = ?",
                String.class, live, table)) {
            jdbc().execute(def.replace(" ON " + live + "." + table + " ", " ON " + LAB + "." + table + " "));
        }
        for (DeferredIndexBuilder.Spec s : DeferredIndexBuilder.CATALOG) {
            if (!s.table().equals(table)) continue;
            jdbc().execute(s.postgresDdl().replaceFirst("(?i)\\s+CONCURRENTLY\\s+", " ")
                    .replace(" ON " + table + "(", " ON " + LAB + "." + table + "("));
        }
    }

    /** Çağrının BU iş parçacığında Hibernate'e ürettirdiği SQL'ler ({@code org.hibernate.SQL} DEBUG). */
    private static List<String> sqlOf(Runnable call) {
        Logger sql = (Logger) LoggerFactory.getLogger("org.hibernate.SQL");
        Level prev = sql.getLevel();
        boolean additive = sql.isAdditive();
        ListAppender<ILoggingEvent> app = new ListAppender<>();
        app.start();
        sql.addAppender(app);
        sql.setAdditive(false);
        sql.setLevel(Level.DEBUG);
        String thread = Thread.currentThread().getName();
        try {
            call.run();
        } finally {
            sql.setLevel(prev);
            sql.setAdditive(additive);
            sql.detachAppender(app);
        }
        return app.list.stream().filter(e -> thread.equals(e.getThreadName())).map(ILoggingEvent::getFormattedMessage).toList();
    }

    private static String select(List<String> sqls, String table) {
        for (String s : sqls) {
            String l = s.toLowerCase(Locale.ROOT);
            if (l.trim().startsWith("select") && l.contains(" " + table + " ")) return s;
        }
        throw new AssertionError("yakalanmadı (" + table + "): " + sqls);
    }

    private static long marks(String sql) {
        return sql.chars().filter(c -> c == '?').count();
    }

    private static String explain(String statement) {
        PostgresIt.Config cfg = PostgresIt.config();
        String url = cfg.url() + (cfg.url().contains("?") ? "&" : "?") + "preferQueryMode=simple";
        try (Connection c = DriverManager.getConnection(url, cfg.user(), cfg.password());
             Statement st = c.createStatement()) {
            st.execute("SET search_path TO " + LAB);
            StringBuilder plan = new StringBuilder();
            try (ResultSet rs = st.executeQuery(statement)) {
                while (rs.next()) plan.append(rs.getString(1)).append('\n');
            }
            return plan.toString();
        } catch (SQLException e) {
            throw new IllegalStateException("plan okunamadı: " + statement, e);
        }
    }

    /** Genel plan: {@code ?} → {@code $n} (basit sorgu kipinde ayrı oturum — GENERIC_PLAN parametresiz basit sorgu ister). */
    private static String genericPlan(String sql) {
        StringBuilder positional = new StringBuilder();
        int n = 0;
        for (char ch : sql.toCharArray()) {
            if (ch == '?') positional.append('$').append(++n); else positional.append(ch);
        }
        return explain("EXPLAIN (GENERIC_PLAN) " + positional);
    }

    /** Gerçek değerlerle özel plan; değer sayısı yer tutucu sayısıyla eşleşmezse test durur (Hibernate SQL'i değişti). */
    private static String customPlan(String sql, Object... values) {
        StringBuilder literal = new StringBuilder();
        int n = 0;
        for (char ch : sql.toCharArray()) {
            if (ch != '?') { literal.append(ch); continue; }
            if (n >= values.length) throw new AssertionError("değer eksik (" + values.length + "): " + sql);
            Object v = values[n++];
            literal.append(v instanceof Number ? v.toString() : "'" + v.toString().replace("'", "''") + "'");
        }
        if (n != values.length) throw new AssertionError("fazla değer (" + values.length + " / " + n + "): " + sql);
        return explain("EXPLAIN " + literal);
    }

    private static void assertNoSeqScan(String what, String plan, String table) {
        assertThat(plan).as("%s — tablo baştan sona taranmamalı:%n%s", what, plan).doesNotContain("Seq Scan on " + table);
    }

    @Test
    @DisplayName("PostgreSQL: büyüme referansı (growthWindow) özel ve genel planda bileşik dizinden — süzgeçsiz, sıralamasız")
    void growthWindowUsesMonitorIndex() {
        PageSpeedCheckRepository repo = bean(PageSpeedCheckRepository.class);
        List<String> sqls = sqlOf(() -> repo.growthWindow(MONITOR, FROM, TO, 3000));
        String sql = select(sqls, "pagespeed_checks");
        // Yer tutucu sırası: in (?,?) … >= ? … (<= ? or cast(? …) is null …) … LIMIT ?
        List<Object> values = new ArrayList<>(List.of(MONITOR, MONITOR, FROM, TO, TO));
        if (marks(sql) == 6) values.add(3000);
        // Pencere en çok 7 gün ve "şimdi"de biter: satırlar zaman sırasıyla yazıldığı için (üretimde de böyle) PostgreSQL dar
        // bir pencerede zaman dizinini seçebilir — alt sınırdan ileri doğru, en çok pencere kadar satır okur, tabloyu taramaz.
        // Asıl kural (CLAUDE.md "Performance rules"): tablo taranmaz ve sınırsız GERİYE tarama olmaz (duraklatılmış izlemenin
        // son satırını aramak) — ya bileşik dizin (süzgeçsiz, sıralamasız) ya da checked_at'in ALT sınırını taşıyan dizin koşulu.
        for (String plan : List.of(customPlan(sql, values.toArray()), genericPlan(sql))) {
            assertNoSeqScan("growthWindow", plan, "pagespeed_checks");
            if (plan.contains("idx_ps_monitor_checked")) {
                assertThat(plan).as("monitor_id süzgeçle elenmemeli:%n%s", plan).doesNotContainPattern(MONITOR_FILTER);
                assertThat(plan).as("sıra dizinden gelir (sıralama yok):%n%s", plan).doesNotContain("Sort Key");
            } else {
                assertThat(plan).as("zaman dizini alt sınırla başlamalı (sınırsız geriye tarama yok):%n%s", plan)
                        .containsPattern("Index Cond:[^\\n]*checked_at[^\\n]*>=");
            }
        }
        assertThat(repo.growthWindow(MONITOR, FROM, TO, 5)).as("canlı şemada bu izlemenin satırı yok").isEmpty();
    }

    @Test
    @DisplayName("PostgreSQL: şelale (waterfall, tek kopya kuralı) ölçüm kimliği dizininden — ana sorgu da NOT EXISTS de taramaz; NOT EXISTS satır başına değil TEK InitPlan")
    void waterfallUsesCheckIndex() {
        PageSpeedResourceRepository repo = bean(PageSpeedResourceRepository.class);
        List<String> sqls = sqlOf(() -> repo.waterfall(MONITOR, CHECK, 2001));
        String sql = select(sqls, "pagespeed_resources");
        // Yer tutucu sırası (2026-11-17): r.monitor_id, r.check_id, NOT EXISTS içindeki b.monitor_id, b.check_id [, LIMIT]
        List<Object> values = new ArrayList<>(List.of(MONITOR, CHECK, MONITOR, CHECK));
        if (marks(sql) == 5) values.add(2001);
        for (String plan : List.of(customPlan(sql, values.toArray()), genericPlan(sql))) {
            assertNoSeqScan("waterfall", plan, "pagespeed_resources");
            assertThat(plan).as("ölçüm kimliği dizini:%n%s", plan).contains("idx_psr_check");
            // 2026-11-17 performans: alt sorgu dış satıra bağlı değil → InitPlan (bir kez); ilişkili SubPlan satır başına koşuyordu
            assertThat(plan).as("NOT EXISTS tek InitPlan olmalı:%n%s", plan).contains("InitPlan").doesNotContain("SubPlan");
        }
        assertThat(repo.waterfall(MONITOR, CHECK, 10)).isEmpty();
    }

    @Test
    @DisplayName("PostgreSQL: ölçüm sayfası (checkPage, tek kopya kuralı) — NOT EXISTS tek InitPlan, ölçüm kimliği dizininden, tablo taranmaz")
    void checkPageOneCopyIsInitPlan() {
        PageSpeedResourceRepository repo = bean(PageSpeedResourceRepository.class);
        List<String> sqls = sqlOf(() -> repo.countByMonitorIdAndCheckId(MONITOR, CHECK));
        String sql = select(sqls, "pagespeed_resources");
        assertThat(marks(sql)).as("r.monitor_id, r.check_id, b.monitor_id, b.check_id: %s", sql).isEqualTo(4);
        for (String plan : List.of(customPlan(sql, MONITOR, CHECK, MONITOR, CHECK), genericPlan(sql))) {
            assertNoSeqScan("countByMonitorIdAndCheckId", plan, "pagespeed_resources");
            assertThat(plan).as("ölçüm kimliği dizini:%n%s", plan).contains("idx_psr_check");
            assertThat(plan).as("NOT EXISTS tek InitPlan olmalı:%n%s", plan).contains("InitPlan").doesNotContain("SubPlan");
        }
        assertThat(repo.countByMonitorIdAndCheckId(MONITOR, CHECK)).isZero();
    }

    @Test
    @DisplayName("PostgreSQL: ihlal anları listesi (breachSnapshots) izleme önce gelen dizinden — başka izlemelerin BREACH satırları okunup süzgeçle elenmez")
    void breachSnapshotsUseMonitorIndex() {
        PageSpeedResourceRepository repo = bean(PageSpeedResourceRepository.class);
        List<String> sqls = sqlOf(() -> repo.breachSnapshots(MONITOR, 20));
        String sql = select(sqls, "pagespeed_resources");
        List<Object> values = new ArrayList<>(List.of(MONITOR));
        if (marks(sql) == 2) values.add(20);
        for (String plan : List.of(customPlan(sql, values.toArray()), genericPlan(sql))) {
            assertNoSeqScan("breachSnapshots", plan, "pagespeed_resources");
            // 2026-11-17: yalnız (keep_reason, checked_at) dizini geriye taranıp monitor_id süzgeçle eleniyordu
            assertThat(plan).as("keep_reason + checked_at dizini (izleme süzgeçli) kullanılmamalı:%n%s", plan)
                    .doesNotContain("idx_psr_reason_checked");
            assertThat(plan).as("izleme kimliği önce gelen dizin:%n%s", plan).contains("idx_psr_monitor_reason");
            assertThat(plan).as("monitor_id süzgeçle elenmemeli:%n%s", plan).doesNotContainPattern(MONITOR_FILTER);
        }
        assertThat(repo.breachSnapshots(MONITOR, 20)).as("canlı şemada bu izlemenin satırı yok").isEmpty();
    }

    @Test
    @DisplayName("PostgreSQL: görsel köprüsü ölçüm kimliğiyle (findStoredImageInCheck) ölçüm kimliği dizininden — tablo taranmaz")
    void findStoredImageInCheckUsesCheckIndex() {
        PageSpeedResourceRepository repo = bean(PageSpeedResourceRepository.class);
        String url = "https://site913.example.test/r/7/0";
        List<String> sqls = sqlOf(() -> repo.findStoredImageInCheck(MONITOR, CHECK, url));
        String sql = select(sqls, "pagespeed_resources");
        List<Object> values = new ArrayList<>(List.of(MONITOR, CHECK, url));
        if (marks(sql) == 4) values.add(1);
        for (String plan : List.of(customPlan(sql, values.toArray()), genericPlan(sql))) {
            assertNoSeqScan("findStoredImageInCheck", plan, "pagespeed_resources");
            assertThat(plan).as("ölçüm kimliği dizini:%n%s", plan).contains("idx_psr_check");
        }
        assertThat(repo.findStoredImageInCheck(MONITOR, CHECK, url)).isEmpty();
        assertThat(repo.anyRowOfCheck(MONITOR, CHECK)).isEmpty();
    }

    @Test
    @DisplayName("PostgreSQL: görsel aracı köprüsü (findStoredImage) izleme dizininden — bulunamayan adres tüm tabloyu okumaz")
    void findStoredImageUsesMonitorIndex() {
        PageSpeedResourceRepository repo = bean(PageSpeedResourceRepository.class);
        String missing = "https://site913.example.test/yok.png";
        List<String> sqls = sqlOf(() -> repo.findStoredImage(MONITOR, missing));
        String sql = select(sqls, "pagespeed_resources");
        List<Object> values = new ArrayList<>(List.of(MONITOR, missing));
        if (marks(sql) == 3) values.add(1);
        for (String plan : List.of(customPlan(sql, values.toArray()), genericPlan(sql))) {
            assertNoSeqScan("findStoredImage", plan, "pagespeed_resources");
            assertThat(plan).as("izleme kimliği önce gelen dizin:%n%s", plan).contains("idx_psr_monitor_reason");
            assertThat(plan).as("birincil anahtar geriye taranmaz:%n%s", plan).doesNotContain("Index Scan Backward");
        }
        assertThat(repo.findStoredImage(MONITOR, missing)).isEmpty();
    }
}
