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
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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
 * Tek izleme sorgularının planı gerçek PostgreSQL'de (performans 2026-11-06, PF1 madde 1 / 2 / 3 / 6).
 *
 * <p>Bu test repository metotlarını gerçekten çağırıp Hibernate'in ÜRETTİĞİ SQL'i yakalar ({@code org.hibernate.SQL}
 * günlüğü) ve planını okur — repository eski biçime dönerse test kırmızıya döner. Anahtarlı tek satır sorguları (en yeni,
 * son başarılı, son değişim) GENEL planla ({@code EXPLAIN (GENERIC_PLAN)}: pgjdbc aynı sorguyu 5 kez çalıştırınca sunucuda
 * hazırlar, PostgreSQL bir süre sonra değeri bilmeyen genel plana geçebilir) denetlenir. Aralıklı sorgularda (geçmiş
 * sayfası, seri, sayımlar) hem GERÇEK değerli özel plan hem genel plan anahtarlı olmalı: sıralı sorgularda üst sınır
 * {@code MonitorIndexSort.UNTIL_*} biçiminde (genel planda aralık çifti sayılmaz — yoksa bu laboratuvarda tekil checked_at
 * dizini + tüm aralığı sıralama seçilir), sayımlar {@code COUNT(monitorId)} ile bileşik dizinden yalnız-dizin taraması. Plan, üretim dağılımına benzeyen bir LABORATUVAR şemasında okunur: aynı tablolar (LIKE), aynı
 * dizinler (canlı şemanın tanımları + ertelenmiş katalog), zaman sırasıyla iç içe yazılmış 18 izleme × 10 000 kontrol
 * (checked_at fiziksel korelasyonu ≈ 1 — tekil checked_at dizinini cazip kılan durum), biri 3000. kontrolde durmuş.
 * Diğer testlerin satırlarına ve istatistiklerine dokunulmaz; şema sonda düşürülür.
 *
 * <p>Kanıtlanan: tekil {@code checked_at} dizini {@code Filter: monitor_id} ile KULLANILMAZ; anahtar kolon dizin koşulunda;
 * kısmi dizinler (hata, DNS değişikliği, anahtar kelime değişim satırı, alan başına son alarm) seçilir. Kontrol: aynı
 * laboratuvarda ESKİ biçim sorunu yeniden üretir.
 */
@PostgresIntegration
class SingleMonitorQueryPlanPostgresTest {

    private static final String LAB = "pf_plan_" + UUID.randomUUID().toString().substring(0, 8);
    private static final String FROM = "2026-10-07T00:00:00";
    private static final String TO = "2026-11-06T23:59:59";
    /** Laboratuvar dışında hiçbir satırı olmayan kimlik — repository çağrıları canlı şemada boş döner. */
    private static final long PAUSED = 904L;
    /** Laboratuvar verisinin içinde, duraklatılmış 904'ün son kontrolünden SONRA kalan aralık (özel plan değerleri). */
    private static final String DATA_FROM = "2026-09-15T00:00:00";
    private static final String DATA_TO = "2026-10-05T23:59:59";

    private static JdbcTemplate jdbc() {
        return PostgresIt.app().jdbc();
    }

    private static <T> T bean(Class<T> type) {
        return PostgresIt.app().bean(type);
    }

    @BeforeAll
    static void lab() throws Exception {
        CompletableFuture<DeferredIndexBuilder.Result> run = DeferredIndexBuilder.lastRun();
        if (run != null) run.get(5, TimeUnit.MINUTES);
        String live = jdbc().queryForObject("SELECT current_schema()", String.class);
        jdbc().execute("CREATE SCHEMA " + LAB);
        for (String t : List.of("port_checks", "dns_records", "dns_monitors", "keyword_results", "alert_events",
                "certificate_inventory")) {
            clone(live, t);
        }
        // 18 izleme × 10 000 kontrol, 5 dakikada bir, zaman sırasıyla iç içe; 904 3000. kontrolde durmuş (duraklatılmış)
        String grid = " FROM generate_series(0, 9999) s CROSS JOIN generate_series(901, 918) m"
                + " WHERE NOT (m = 904 AND s > 3000) ORDER BY s, m";
        String at = "to_char(timestamp '2026-09-01' + s * interval '300 second', 'YYYY-MM-DD\"T\"HH24:MI:SS')";
        jdbc().execute("INSERT INTO " + LAB + ".port_checks (monitor_id, checked_at, open, response_ms)"
                + " SELECT m, " + at + ", (s + m) % 97 <> 0, 50" + grid);
        jdbc().execute("INSERT INTO " + LAB + ".dns_records (monitor_id, checked_at, record_type, value, changed, rotated, response_ms)"
                + " SELECT m, " + at + ", 'A', CASE WHEN (s + m) % 211 = 0 THEN '' ELSE '1.2.3.4' END,"
                + " (s + m) % 2003 = 0, (s + m) % 2999 = 0, 20" + grid);
        jdbc().execute("INSERT INTO " + LAB + ".dns_monitors (domain, record_type, active)"
                + " SELECT 'd' || m || '.example.test', 'A', true FROM generate_series(901, 918) m");
        jdbc().execute("INSERT INTO " + LAB + ".keyword_results (monitor_id, checked_at, ok, found, content_changed)"
                + " SELECT m, " + at + ", true, true, CASE WHEN m < 910 THEN (s % 50 = 0) END" + grid);
        // Alarmlar: 400 alan adının 200'ünde alarm (60 000 satır), envanterde 300 etkin alan (100'ünün alarmı yok)
        String alarmAt = "to_char(timestamp '2026-01-01' + i * interval '60 second', 'YYYY-MM-DD\"T\"HH24:MI:SS')";
        jdbc().execute("INSERT INTO " + LAB + ".alert_events (domain, alert_type, alert_level, resolved, created_at, resolved_at,"
                + " resolution_notify) SELECT 'a' || (i % 200) || '.example.test', 'EXPIRY', 'WARNING', true, " + alarmAt + ", "
                + alarmAt + ", CASE WHEN i % 5000 = 0 THEN 'PENDING' ELSE 'DONE' END FROM generate_series(1, 60000) i");
        jdbc().execute("INSERT INTO " + LAB + ".certificate_inventory (domain, port, active)"
                + " SELECT 'a' || i || '.example.test', 443, true FROM generate_series(100, 399) i");
        for (String t : List.of("port_checks", "dns_records", "dns_monitors", "keyword_results", "alert_events",
                "certificate_inventory")) {
            // VACUUM: görünürlük haritası dolu (üretimde autovacuum) — yalnız-dizin taraması gerçekçi maliyetlensin
            jdbc().execute("VACUUM (ANALYZE) " + LAB + "." + t);
        }
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

    // ── SQL yakalama + genel plan ────────────────────────────────────────────────────────────────

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

    /** {@code table}'ı okuyan ilk SELECT (sayfa sorgusunda sayım değil, satır sorgusu). */
    private static String firstSelect(List<String> sqls, String table, boolean wantCount) {
        for (String s : sqls) {
            String l = s.toLowerCase(Locale.ROOT);
            if (!l.trim().startsWith("select") || !l.contains(" " + table + " ")) continue;
            if (wantCount == l.contains("count(")) return s;
        }
        throw new AssertionError("yakalanmadı (" + table + ", sayım=" + wantCount + "): " + sqls);
    }

    /**
     * Laboratuvar şemasında genel plan: {@code ?} → {@code $n}. Basit sorgu kipinde ayrı bir oturumla — genişletilmiş
     * protokolde (pgjdbc varsayılanı) sunucu {@code $n}'leri bağlanacak parametre sayar ve "bind message supplies 0
     * parameters" der; {@code GENERIC_PLAN} parametresiz basit sorgu için tasarlanmıştır (psql).
     */
    static String genericPlan(String sql) {
        StringBuilder positional = new StringBuilder();
        int n = 0;
        for (char ch : sql.toCharArray()) {
            if (ch == '?') positional.append('$').append(++n); else positional.append(ch);
        }
        return explain("EXPLAIN (GENERIC_PLAN) " + positional);
    }

    /**
     * Gerçek değerlerle özel plan: {@code ?} yer tutucuları sırayla değerlerle değişir (sayı olduğu gibi, metin tırnaklı).
     * Değer sayısı yer tutucu sayısıyla eşleşmezse test durur (Hibernate SQL'i değişti demektir).
     */
    static String customPlan(String sql, Object... values) {
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

    /** Planı basit sorgu kipli ayrı oturumda, laboratuvar şemasında okur. */
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

    private static final Pattern MONITOR_FILTER = Pattern.compile("Filter:[^\\n]*\\bmonitor_id\\b");

    /** Anahtar kolon dizin koşulunda, süzgeçte DEĞİL; tablo baştan sona taranmıyor; beklenen dizin seçilmiş. */
    private static void assertKeyed(String what, String plan, String table, String expectedIndex) {
        assertThat(plan).as("%s — monitor_id süzgeçle elenmemeli:%n%s", what, plan).doesNotContainPattern(MONITOR_FILTER);
        assertThat(plan).as("%s — tablo taranmamalı:%n%s", what, plan).doesNotContain("Seq Scan on " + table);
        assertThat(plan).as("%s — beklenen dizin:%n%s", what, plan).contains(expectedIndex);
        assertThat(plan).as("%s — tekil checked_at dizini sıra için kullanılmamalı:%n%s", what, plan)
                .doesNotContainPattern("using \\w*checked_at on " + table);
    }

    /**
     * Aralıklı sıralı sorgu: gerçek değerli özel plan VE genel plan anahtarlı. Değerler: duraklatılmış izlemenin son
     * kontrolünden sonraki veri aralığı (eski biçimde tüm aralığın satırlarının okunduğu durum); yer tutucu sırası
     * {@code in (?,?) … >= ? … (<= ? or cast(? …) is null …) … fetch first ?}.
     */
    private static void assertRangeKeyed(String what, String sql, String table, String expectedIndex, int limit) {
        assertKeyed(what + " (özel plan)", customPlan(sql, PAUSED, PAUSED, DATA_FROM, DATA_TO, DATA_TO, limit), table,
                expectedIndex);
        assertKeyed(what + " (genel plan)", genericPlan(sql), table, expectedIndex);
    }

    /** Aralık sayımı: özel ve genel planda bileşik / kısmi dizinden YALNIZ-DİZİN taraması (yığın okunmaz). */
    private static void assertCountKeyed(String what, String sql, String table, String expectedIndex) {
        for (String plan : List.of(customPlan(sql, PAUSED, DATA_FROM, DATA_TO), genericPlan(sql))) {
            assertKeyed(what, plan, table, expectedIndex);
            assertThat(plan).as("%s — yalnız-dizin taraması:%n%s", what, plan).contains("Index Only Scan");
        }
    }

    private static PageRequest newestFirst() {
        return PageRequest.of(0, 50, Sort.by(Sort.Direction.DESC, "checkedAt"));
    }

    // ── Kontrol: laboratuvar sorunu yeniden üretir ───────────────────────────────────────────────

    @Test
    @DisplayName("Kontrol: ESKİ biçim (monitor_id = ? … ORDER BY checked_at DESC LIMIT) genel planda tekil checked_at + süzgeç")
    void labReproducesTheOldPlan() {
        String plan = genericPlan("SELECT * FROM port_checks WHERE monitor_id = ? AND checked_at >= ? AND checked_at <= ?"
                + " ORDER BY checked_at DESC OFFSET ? ROWS FETCH FIRST ? ROWS ONLY");
        assertThat(plan).as("laboratuvar dağılımı sorunu üretmeli:%n%s", plan).containsPattern(MONITOR_FILTER)
                .contains("idx_portc_checked_at");
        // Özel planda da (gerçek değerler): duraklatılmış izlemenin boş aralığında tekil checked_at + süzgeç
        String custom = customPlan("SELECT * FROM port_checks WHERE monitor_id = ? AND checked_at >= ? AND checked_at <= ?"
                + " ORDER BY checked_at DESC FETCH FIRST ? ROWS ONLY", PAUSED, DATA_FROM, DATA_TO, 50);
        assertThat(custom).as("eski biçim özel planda da süzgeçli olmalı:%n%s", custom).containsPattern(MONITOR_FILTER)
                .contains("idx_portc_checked_at");
    }

    // ── Port ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("port: en yeni kontrol, Kontrol Geçmişi sayfası, hata sayfası, hata sayacı, yanıt serisi — bileşik / kısmi dizin")
    void portQueries() {
        PortCheckRepository repo = bean(PortCheckRepository.class);
        String top = firstSelect(sqlOf(() -> repo.findTopByMonitorIdOrderByCheckedAtDesc(PAUSED)), "port_checks", false);
        // İki ayrı yer tutucu: Hibernate tek öğeye indirseydi PostgreSQL eşitliğe çevirip eski planı seçerdi
        assertThat(top.toLowerCase(Locale.ROOT)).as("IN (:id, :id) iki yer tutucuyla gitmeli: %s", top)
                .containsPattern("monitor_id in \\(\\?,\\s*\\?\\)");
        assertKeyed("findTop", genericPlan(top), "port_checks", "idx_portc_monitor_checked");
        assertRangeKeyed("geçmiş sayfası", firstSelect(sqlOf(() -> repo.findByMonitorIdAndCheckedAtBetween(PAUSED, FROM, TO,
                newestFirst())), "port_checks", false), "port_checks", "idx_portc_monitor_checked", 50);
        assertRangeKeyed("hata sayfası", firstSelect(sqlOf(() -> repo.findByMonitorIdAndOpenFalseAndCheckedAtBetween(PAUSED,
                FROM, TO, newestFirst())), "port_checks", false), "port_checks", "idx_portc_fail", 50);
        // Sayım sorgusu yalnız ilk sayfa dolmadığında atlanır (Spring Data) — ikinci sayfa sayımı çalıştırır
        assertCountKeyed("geçmiş sayfası sayımı", firstSelect(sqlOf(() -> repo.findByMonitorIdAndCheckedAtBetween(PAUSED, FROM,
                TO, PageRequest.of(1, 50, Sort.by(Sort.Direction.DESC, "checkedAt")))), "port_checks", true), "port_checks",
                "idx_portc_monitor_checked");
        assertCountKeyed("toplam sayaç", firstSelect(sqlOf(() -> repo.countByMonitorIdAndCheckedAtBetween(PAUSED, FROM, TO)),
                "port_checks", true), "port_checks", "idx_portc_monitor_checked");
        assertCountKeyed("hata sayacı", firstSelect(sqlOf(() -> repo.countByMonitorIdAndOpenFalseAndCheckedAtBetween(PAUSED,
                FROM, TO)), "port_checks", true), "port_checks", "idx_portc_fail");
        assertRangeKeyed("yanıt serisi", firstSelect(sqlOf(() -> repo.responseSeriesRaw(PAUSED, FROM, TO, 500)),
                "port_checks", false), "port_checks", "idx_portc_monitor_checked", 500);
    }

    // ── DNS ──────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("dns: en yeni, değişiklik tabanı (son başarılı), son changed kaydı (izleme / alan adı), Değişenler sayfası + sayaç")
    void dnsQueries() {
        DnsRecordRepository repo = bean(DnsRecordRepository.class);
        assertKeyed("findTop", genericPlan(firstSelect(sqlOf(() -> repo.findTopByMonitorIdOrderByCheckedAtDesc(PAUSED)),
                "dns_records", false)), "dns_records", "idx_dnsr_monitor_checked");
        assertKeyed("son başarılı (taban)", genericPlan(firstSelect(sqlOf(() -> repo.findLatestScheduledSuccessful(PAUSED)),
                "dns_records", false)), "dns_records", "idx_dnsr_monitor_checked");
        // changed = true: mevcut idx_dnsr_monitor_changed (monitor_id, changed) + artımlı sıralama da anahtarlıdır
        assertKeyed("son changed (izleme)", genericPlan(firstSelect(sqlOf(() -> repo.findChangedByMonitorId(PAUSED,
                PageRequest.of(0, 1))), "dns_records", false)), "dns_records", "idx_dnsr_");
        assertRangeKeyed("Değişenler sayfası", firstSelect(sqlOf(() -> repo.findChangedByMonitorIdBetween(PAUSED, FROM, TO,
                newestFirst())), "dns_records", false), "dns_records", "idx_dnsr_changed", 50);
        assertKeyed("Değişenler sayacı", genericPlan(firstSelect(sqlOf(() -> repo.countChangedByMonitorIdBetween(PAUSED, FROM, TO)),
                "dns_records", true)), "dns_records", "idx_dnsr_changed");
        assertKeyed("başarısız sayacı", genericPlan(firstSelect(sqlOf(() -> repo.countFailedByMonitorIdBetween(PAUSED, FROM, TO)),
                "dns_records", true)), "dns_records", "idx_dnsr_fail");
        // Alan adı düzeyi: önce izleme kimlikleri, sonra izleme başına yukarıdaki "son changed (izleme)" sorgusu — birden çok
        // izlemeyi kapsayan tek ORDER BY checked_at sorgusu (hiçbir izleme dizini sıralayamaz) artık yok. Canlı şemada bu alan
        // adında izleme yok: yalnız kimlik sorgusu koşar.
        List<String> domainSqls = sqlOf(() -> repo.findChangedByDomain("d904.example.test", PageRequest.of(0, 1)));
        assertThat(domainSqls).as("izleme kimlikleri okunmalı: %s", domainSqls)
                .anyMatch(q -> q.toLowerCase(Locale.ROOT).contains(" dns_monitors "));
        assertThat(domainSqls).as("alan adı düzeyinde dns_records alt sorgulu tek sorgu kalmamalı: %s", domainSqls)
                .noneMatch(q -> q.toLowerCase(Locale.ROOT).contains(" dns_records ") && q.toLowerCase(Locale.ROOT).contains(" in ("));
        // Laboratuvarda gerçek izleme kimliğiyle izleme başına sorgu da kısmi dizinden
        assertKeyed("son changed (alan adının izlemesi)", genericPlan(firstSelect(sqlOf(() -> repo.findChangedByMonitorId(910L,
                PageRequest.of(0, 1))), "dns_records", false)), "dns_records", "idx_dnsr_");
    }

    // ── Anahtar kelime ───────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("anahtar kelime: en yeni, içerik serisi, son değişim satırı (kısmi idx_kwr_change_checked)")
    void keywordQueries() {
        KeywordResultRepository repo = bean(KeywordResultRepository.class);
        KeywordContentQueryRepository content = bean(KeywordContentQueryRepository.class);
        assertKeyed("findTop", genericPlan(firstSelect(sqlOf(() -> repo.findTopByMonitorIdOrderByCheckedAtDesc(PAUSED)),
                "keyword_results", false)), "keyword_results", "idx_kwr_monitor_checked");
        assertKeyed("hata sayacı", genericPlan(firstSelect(sqlOf(() -> repo.countByMonitorIdAndOkFalseAndCheckedAtBetween(PAUSED,
                FROM, TO)), "keyword_results", true)), "keyword_results", "idx_kwr_fail");
        assertRangeKeyed("içerik serisi", firstSelect(sqlOf(() -> content.contentSeriesRaw(PAUSED, FROM, TO, 500)),
                "keyword_results", false), "keyword_results", "idx_kwr_monitor_checked", 500);
        assertKeyed("son değişim satırı", genericPlan(firstSelect(sqlOf(() -> content.latestChangeRow(915L)),
                "keyword_results", false)), "keyword_results", "idx_kwr_change_checked");
        assertKeyed("son satır", genericPlan(firstSelect(sqlOf(() -> content.latestRow(915L)),
                "keyword_results", false)), "keyword_results", "idx_kwr_monitor_checked");
    }

    // ── Alan başına son alarm ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Genel Bakış 'şu an' şeridi: alan başına MAX(id) idx_ae_domain_id'den (birincil anahtar geriye taranmaz)")
    void latestAlertPerInventoryDomain() {
        AlertEventRepository repo = bean(AlertEventRepository.class);
        List<String> sqls = new ArrayList<>(sqlOf(repo::findLatestForActiveInventoryDomains));
        String plan = genericPlan(firstSelect(sqls, "certificate_inventory", false));
        assertThat(plan).as("alan başına son alarm:%n%s", plan).contains("idx_ae_domain_id")
                .doesNotContain("Seq Scan on alert_events").doesNotContain("Index Scan Backward using alert_events_pkey");
    }

    // ── Çözüm bildirimi süpürücüsü + autovacuum ekleme eşiği ─────────────────────────────────────

    @Test
    @DisplayName("Çözüm bildirimi süpürücüsü (EscalationService.deliverPendingResolutions): bekleyenler kısmi dizinden")
    void resolutionSweepUsesThePartialIndex() {
        // EscalationService'teki üç sorgunun metni (satır sırası ve LIMIT aynı)
        for (String sql : List.of(
                "SELECT id FROM alert_events WHERE resolved_at >= ? AND resolution_notify = 'SENDING' AND resolution_notify_at < ?"
                        + " ORDER BY id LIMIT 100",
                "SELECT id FROM alert_events WHERE resolved_at >= ? AND resolved_at < ? AND resolution_notify = 'PENDING'"
                        + " ORDER BY id LIMIT 100",
                "SELECT id FROM alert_events WHERE resolved_at >= ? AND resolved_at <= ? AND resolution_notify = 'PENDING'"
                        + " ORDER BY id LIMIT 100")) {
            String plan = genericPlan(sql);
            assertThat(plan).as("%s%n%s", sql, plan).contains("idx_ae_resolution_notify_open").doesNotContain("Seq Scan on alert_events");
        }
    }

    @Test
    @DisplayName("Ekleme ağırlıklı büyük tablolarda autovacuum_vacuum_insert_scale_factor = 0.02 açılış yamasıyla yerinde")
    void insertScaleFactorPatched() {
        for (String t : List.of("port_checks", "dns_records", "keyword_results", "ping_checks", "http_checks", "page_checks",
                "activity_log", "uptime_checks", "certificate_checks")) {
            List<String> opts = jdbc().queryForList("SELECT unnest(reloptions) FROM pg_class WHERE oid = to_regclass(?::text)",
                    String.class, t);
            assertThat(opts).as(t).contains("autovacuum_vacuum_insert_scale_factor=0.02")
                    .contains("autovacuum_vacuum_scale_factor=0.02");   // eski seçenekler korunur
        }
    }
}
