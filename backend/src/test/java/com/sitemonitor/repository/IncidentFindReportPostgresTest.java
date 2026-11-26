package com.sitemonitor.repository;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sitemonitor.it.PostgresIntegration;
import com.sitemonitor.it.PostgresIt;
import com.sitemonitor.model.IncidentRecord;
import com.sitemonitor.model.TechErrorReportIncident;
import com.sitemonitor.service.IncidentService;
import com.sitemonitor.service.schema.DeferredIndexBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "Bul ve Raporla" (Olay ve Hata Geçmişi Paket 3, 2026-11-26) GERÇEK PostgreSQL'de — {@code mvn -Ppostgres-it test}.
 *
 * <ul>
 *   <li>Dinamik süzgeç JPQL'i (CSV tam öğe kalıbı, {@code ESCAPE '!'}, kimlik araması, kod / kaydeden tam eşleşme,
 *       postmortem {@code NOT EXISTS}), süzgeçli eğilimin UTC SAAT gruplaması (eski yolla aynı İstanbul günleri) ve seçenek
 *       yeniden adlandırmanın rapor bağı taşıması ({@code carryInSyncLinks}, {@code EXISTS} alt sorgusu) PG'de çalışır;</li>
 *   <li>yeniden adlandırma satırları KİLİTLER ve yeni değeri güncel değerden hesaplar: aday taramasından sonra işlenen bir
 *       düzenleme (başka oturum, kilidi tutarken) ezilmez;</li>
 *   <li>plan (üretime benzer LABORATUVAR şemasında 60 000 kayıt): varsayılan liste sayfası, tarih aralıklı liste, kapsamlı
 *       (takım) liste ve tarih aralıklı eğilim saat gruplaması tabloyu baştan sona TARAMAZ.</li>
 * </ul>
 * Canlı şemaya yazılan satırlar sonda silinir; laboratuvar şeması düşürülür.
 */
@PostgresIntegration
class IncidentFindReportPostgresTest {

    private static final String LAB = "inc_plan_" + UUID.randomUUID().toString().substring(0, 8);

    private static JdbcTemplate jdbc() { return PostgresIt.app().jdbc(); }

    private static <T> T bean(Class<T> type) { return PostgresIt.app().bean(type); }

    @BeforeAll
    static void lab() throws Exception {
        CompletableFuture<DeferredIndexBuilder.Result> run = DeferredIndexBuilder.lastRun();
        if (run != null) run.get(5, TimeUnit.MINUTES);
        String live = jdbc().queryForObject("SELECT current_schema()", String.class);
        jdbc().execute("CREATE SCHEMA " + LAB);
        clone(live, "incident_records");
        clone(live, "incident_postmortems");
        // 60 000 kayıt, 5 yıl (≈ 45 dk'da bir), 40 takım; takımsız kayıtların bir kısmında giren takım dolu
        String at = "to_char(timestamp '2021-11-01' + s * interval '2628 second', 'YYYY-MM-DD\"T\"HH24:MI:SS')";
        jdbc().execute("INSERT INTO " + LAB + ".incident_records (title, occurred_at, severity, status, category, sla_breached,"
                + " team_id, created_by_team_id, created_by, channel, service, error_code, updated_at, version)"
                + " SELECT 'olay ' || s, " + at + ","
                + " CASE s % 4 WHEN 0 THEN 'CRITICAL' WHEN 1 THEN 'HIGH' WHEN 2 THEN 'MEDIUM' ELSE 'LOW' END,"
                + " CASE WHEN s % 7 = 0 THEN 'OPEN' ELSE 'RESOLVED' END, 'APPLICATION', s % 9 = 0,"
                + " CASE WHEN s % 50 = 0 THEN NULL ELSE 1 + s % 40 END, 1 + s % 40, 'kullanici' || (s % 25),"
                + " CASE s % 3 WHEN 0 THEN 'ATM, POS' WHEN 1 THEN 'Mobil Şube' ELSE 'İnternet Şubesi, ATM' END,"
                + " 'servis' || (s % 30), 'E-' || (s % 200), " + at + ", 0"
                + " FROM generate_series(0, 59999) s ORDER BY s");
        // %5'inde postmortem
        jdbc().execute("INSERT INTO " + LAB + ".incident_postmortems (incident_id, status)"
                + " SELECT id, CASE WHEN id % 2 = 0 THEN 'PUBLISHED' ELSE 'DRAFT' END FROM " + LAB + ".incident_records"
                + " WHERE id % 20 = 0");
        jdbc().execute("VACUUM (ANALYZE) " + LAB + ".incident_records");
        jdbc().execute("VACUUM (ANALYZE) " + LAB + ".incident_postmortems");
    }

    @AfterAll
    static void dropLab() {
        jdbc().execute("DROP SCHEMA IF EXISTS " + LAB + " CASCADE");
    }

    /** Tabloyu laboratuvara kopyalar: kolonlar (NOT NULL'suz — tohum yalnız gereken kolonları yazar), canlı dizinler. */
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

    /** Gerçek değerlerle özel plan; değer sayısı yer tutucu sayısıyla eşleşmezse test durur (Hibernate SQL'i değişti). */
    private static String customPlan(String sql, List<Object> values) {
        StringBuilder literal = new StringBuilder();
        int n = 0;
        for (char ch : sql.toCharArray()) {
            if (ch != '?') { literal.append(ch); continue; }
            if (n >= values.size()) throw new AssertionError("değer eksik (" + values.size() + "): " + sql);
            Object v = values.get(n++);
            literal.append(v instanceof Number || v instanceof Boolean ? v.toString() : "'" + v.toString().replace("'", "''") + "'");
        }
        if (n != values.size()) throw new AssertionError("fazla değer (" + values.size() + " / " + n + "): " + sql);
        return explain("EXPLAIN " + literal);
    }

    private static void assertNoSeqScan(String what, String plan) {
        System.out.println("[plan] " + what + System.lineSeparator() + plan);
        assertThat(plan).as("%s — tablo baştan sona taranmamalı:%n%s", what, plan).doesNotContain("Seq Scan on incident_records");
    }

    private static List<Object> withPaging(String sql, List<Object> values, int pageSize) {
        List<Object> out = new ArrayList<>(values);
        long extra = marks(sql) - values.size();
        if (extra == 2) { out.add(0); out.add(pageSize); }   // offset ? rows fetch first ? rows only
        else if (extra == 1) out.add(pageSize);
        return out;
    }

    // ── Plan ────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("PostgreSQL planı: varsayılan liste sayfası (oluş zamanı ↓, kimlik ↓, 50 satır) tabloyu taramaz ve tümünü sıralamaz")
    void defaultListPageUsesOccurredIndex() {
        IncidentService service = bean(IncidentService.class);
        String sql = select(sqlOf(() -> service.list(IncidentService.ListFilter.dates(null, null), null,
                PageRequest.of(0, 50), "occurred_desc")), "incident_records");
        String plan = customPlan(sql, withPaging(sql, List.of(), 50));
        assertNoSeqScan("varsayılan liste", plan);
        assertThat(plan).as("oluş zamanı dizini:%n%s", plan).contains("idx_inc_occurred");
    }

    @Test
    @DisplayName("PostgreSQL planı: tarih aralıklı liste ve kapsamlı (takım) liste tabloyu taramaz")
    void rangeAndScopedListUseOccurredIndex() {
        IncidentService service = bean(IncidentService.class);
        String since = "2026-08-31T21:00:00", until = "2026-09-30T20:59:59";
        String sql = select(sqlOf(() -> service.list(IncidentService.ListFilter.dates(since, until), null,
                PageRequest.of(0, 50), "occurred_desc")), "incident_records");
        String plan = customPlan(sql, withPaging(sql, List.of(since, until), 50));
        assertNoSeqScan("tarih aralıklı liste", plan);
        assertThat(plan).as("tarih alt sınırı dizin koşulunda:%n%s", plan).containsPattern("Index Cond:[^\\n]*occurred_at");

        List<Long> scope = List.of(3L, 17L, 29L, 33L);   // 4: Hibernate IN listesini 2'nin kuvvetine doldurur
        String scoped = select(sqlOf(() -> service.list(IncidentService.ListFilter.dates(null, null), scope,
                PageRequest.of(0, 50), "occurred_desc")), "incident_records");
        List<Object> values = new ArrayList<>(scope);
        values.addAll(scope);
        String scopedPlan = customPlan(scoped, withPaging(scoped, values, 50));
        assertNoSeqScan("kapsamlı liste", scopedPlan);
    }

    @Test
    @DisplayName("PostgreSQL planı: süzgeçli eğilimin saat gruplaması (30 gün, bir kaydeden) tarih dizininden — tablo taranmaz")
    void trendHourGroupUsesOccurredIndex() {
        IncidentService service = bean(IncidentService.class);
        String since = "2026-08-31T21:00:00", until = "2026-09-30T20:59:59";
        List<String> sqls = sqlOf(() -> service.trends(new IncidentService.ListFilter(null, null, null, null, null, null,
                since, until, null, null, null, null, null, null, null, null, "kullanici3", null), null));
        String sql = sqls.stream().filter(s -> s.toLowerCase(Locale.ROOT).contains("substr")).findFirst()
                .orElseThrow(() -> new AssertionError("saat gruplaması yakalanmadı: " + sqls));
        List<Object> values = new ArrayList<>(List.of(since, until, "kullanici3"));
        if (marks(sql) == 4) values.add(IncidentService.TREND_DAILY_MAX_ROWS + 1);
        String plan = customPlan(sql, values);
        assertNoSeqScan("eğilim saat gruplaması", plan);
        assertThat(plan).as("tarih dizini:%n%s", plan).containsPattern("Index Cond:[^\\n]*occurred_at");
    }

    @Test
    @DisplayName("PostgreSQL planı: dışa aktarımın kimlik sorgusu (10 001 kimlik, varsayılan sıra) tarih dizininden — tablo taranmaz;"
            + " postmortem süzgeçli liste sayfası tabloyu taramaz")
    void exportIdsAndPostmortemListUseIndexes() {
        IncidentService service = bean(IncidentService.class);
        String ids = select(sqlOf(() -> service.exportIds(IncidentService.ListFilter.dates(null, null), null, "occurred_desc",
                10_000)), "incident_records");
        List<Object> values = new ArrayList<>();
        if (marks(ids) == 1) values.add(10_001);
        String plan = customPlan(ids, values);
        assertNoSeqScan("dışa aktarım kimlikleri", plan);
        assertThat(plan).as("oluş zamanı dizini:%n%s", plan).contains("idx_inc_occurred");

        IncidentService.ListFilter pm = new IncidentService.ListFilter(null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, "PUBLISHED");
        String pmSql = select(sqlOf(() -> service.list(pm, null, PageRequest.of(0, 50), "occurred_desc")), "incident_records");
        String pmPlan = customPlan(pmSql, withPaging(pmSql, List.of("PUBLISHED"), 50));
        assertNoSeqScan("postmortem süzgeçli liste", pmPlan);
    }

    // ── İşlev (canlı şema) ──────────────────────────────────────────────────────────────────────────────────────────

    private static IncidentRecord save(long team, String title, Consumer<IncidentRecord> c) {
        IncidentRecord e = new IncidentRecord();
        e.setTitle(title);
        e.setOccurredAt("2026-11-10T10:00:00");
        e.setSeverity("HIGH");
        e.setStatus("OPEN");
        e.setCategory("APPLICATION");
        e.setSlaBreached(false);
        e.setTeamId(team);
        e.setCreatedByTeamId(team);
        e.setCreatedBy("pg-user");
        e.setCreatedAt("2026-11-10T10:05:00");
        e.setUpdatedAt("2026-11-10T10:05:00");
        if (c != null) c.accept(e);
        return bean(IncidentRecordRepository.class).saveAndFlush(e);
    }

    private static List<Long> ids(IncidentService.ListFilter f, long team) {
        return bean(IncidentService.class).list(f, List.of(team), PageRequest.of(0, 50), "occurred_desc").getContent()
                .stream().map(IncidentRecord::getId).toList();
    }

    @Test
    @DisplayName("PostgreSQL işlev: CSV tam öğe, LIKE kaçışı, kimlik araması, kod / kaydeden tam eşleşme, postmortem NOT EXISTS, saat"
            + " gruplu günlük seri eski yolla aynı")
    void filtersAndTrendsOnPostgres() {
        long team = 880_000L + (long) (Math.random() * 10_000);
        String tag = "ip3-it-" + UUID.randomUUID().toString().substring(0, 8);
        List<Long> created = new ArrayList<>();
        IncidentRecordRepository repo = bean(IncidentRecordRepository.class);
        try {
            IncidentRecord a = save(team, tag + " a", e -> { e.setChannel("ATM, POS"); e.setErrorCode("E-1");
                e.setOccurredAt("2026-11-09T21:30:00"); e.setTags("kart, mobil"); });
            IncidentRecord b = save(team, tag + " b", e -> { e.setChannel("Mobil ATM"); e.setErrorCode("e-1"); e.setCreatedBy("Other"); });
            IncidentRecord c = save(team, tag + " 100% bitti", e -> e.setOccurredAt("2026-11-09T20:30:00"));
            created.addAll(List.of(a.getId(), b.getId(), c.getId()));

            IncidentService.ListFilter base = IncidentService.ListFilter.dates(null, null);
            assertThat(ids(new IncidentService.ListFilter(null, null, null, null, null, null, null, null, null, null, null,
                    null, "atm", null, null, null, null, null), team)).containsExactly(a.getId());
            assertThat(ids(IncidentService.ListFilter.legacy(null, null, null, null, null, "atm", null, null, null, null, null), team))
                    .containsExactlyInAnyOrder(a.getId(), b.getId());
            assertThat(ids(IncidentService.ListFilter.legacy("%", null, null, null, null, null, null, null, null, null, null), team))
                    .containsExactly(c.getId());
            assertThat(ids(IncidentService.ListFilter.legacy("#" + a.getId(), null, null, null, null, null, null, null, null, null,
                    null), team)).containsExactly(a.getId());
            assertThat(ids(new IncidentService.ListFilter(null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, "E-1", null, null, null), team)).containsExactlyInAnyOrder(a.getId(), b.getId());
            assertThat(ids(new IncidentService.ListFilter(null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, "MOBIL", null, null), team)).containsExactly(a.getId());
            assertThat(ids(new IncidentService.ListFilter(null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, "PG-USER", "NONE"), team)).containsExactlyInAnyOrder(a.getId(), c.getId());
            assertThat(ids(base, team)).hasSize(3);

            // süzgeçli (saat gruplu) günlük seri: 20:30 UTC = 9 Kasım 23:30, 21:30 UTC = 10 Kasım 00:30 (İstanbul)
            Map<String, Object> viaFilter = bean(IncidentService.class).trends(new IncidentService.ListFilter(null, null, null,
                    null, null, null, null, null, team, null, null, null, null, null, null, null, null, null), null);
            assertThat(viaFilter.get("filtered")).isEqualTo(true);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> daily = (List<Map<String, Object>>) viaFilter.get("daily");
            assertThat(daily).extracting(m -> m.get("day")).containsExactly("2026-11-09", "2026-11-10");
            assertThat(daily).extracting(m -> m.get("count")).containsExactly(1L, 2L);
        } finally {
            repo.deleteAllById(created);
        }
    }

    @Test
    @DisplayName("PostgreSQL işlev: yeniden adlandırma eşit rapor bağını taşır (bayat bağa dokunmaz); kilit tutarken işlenen düzenleme ezilmez")
    void renameCarriesLinksAndRespectsConcurrentEdit() throws Exception {
        long team = 890_000L + (long) (Math.random() * 10_000);
        String tag = "ip3-it-" + UUID.randomUUID().toString().substring(0, 8);
        IncidentRecordRepository repo = bean(IncidentRecordRepository.class);
        TechErrorReportIncidentRepository links = bean(TechErrorReportIncidentRepository.class);
        IncidentService service = bean(IncidentService.class);
        List<Long> created = new ArrayList<>();
        long reportId = 7_700_000L + (long) (Math.random() * 100_000);
        try {
            IncidentRecord a = save(team, tag + " a", e -> e.setChannel("ATM, POS"));
            IncidentRecord b = save(team, tag + " b", e -> e.setChannel("ATM"));
            created.addAll(List.of(a.getId(), b.getId()));
            Long inSync = link(links, reportId, "i-a", a.getId(), a.getUpdatedAt());
            Long stale = link(links, reportId, "i-b", b.getId(), "2026-01-01T00:00:00");

            // Başka oturum a'yı kilitleyip kanalını değiştirir (henüz commit yok); yeniden adlandırma aday taramasında eski
            // değeri ("ATM, POS") görür, kilitte bekler, commit'ten sonra GÜNCEL değeri ("ATM, Kiosk") yeniden yazar.
            try (Connection other = PostgresIt.app().newSession()) {
                other.setAutoCommit(false);
                try (Statement st = other.createStatement()) {
                    st.executeUpdate("UPDATE incident_records SET channel = 'ATM, Kiosk', updated_at = '2026-11-11T09:00:00',"
                            + " version = version + 1 WHERE id = " + a.getId());
                }
                CompletableFuture<IncidentService.RenameResult> rename = CompletableFuture.supplyAsync(
                        () -> service.renameOptions("CHANNEL", team, List.of("atm"), "Bankamatik", "pg-admin"));
                Thread.sleep(1500);
                assertThat(rename).as("yeniden adlandırma kilitte beklemeli").isNotDone();
                other.commit();
                IncidentService.RenameResult r = rename.get(60, TimeUnit.SECONDS);
                assertThat(r.records()).isEqualTo(2);
            }
            assertThat(repo.findById(a.getId()).orElseThrow().getChannel()).isEqualTo("Bankamatik, Kiosk");
            assertThat(repo.findById(b.getId()).orElseThrow().getChannel()).isEqualTo("Bankamatik");
            String newTs = repo.findById(b.getId()).orElseThrow().getUpdatedAt();
            // a: bağ olayın ESKİ updated_at'iyle eşitti ama araya giren düzenleme (kilitten önce commit) onu değiştirdi →
            // bağ artık bayat (doğru: başlık / içerik değişmiş olabilir); b: bayat bağa dokunulmaz.
            assertThat(links.findById(inSync).orElseThrow().getIncidentUpdatedAt()).isEqualTo(a.getUpdatedAt());
            assertThat(links.findById(stale).orElseThrow().getIncidentUpdatedAt()).isEqualTo("2026-01-01T00:00:00");

            // Eşit bağ, araya giren düzenleme olmadan: yeni zamana taşınır
            IncidentRecord c = save(team, tag + " c", e -> e.setChannel("POS"));
            created.add(c.getId());
            Long cLink = link(links, reportId, "i-c", c.getId(), c.getUpdatedAt());
            service.renameOptions("CHANNEL", team, List.of("POS"), "Yazarkasa", "pg-admin");
            IncidentRecord c2 = repo.findById(c.getId()).orElseThrow();
            assertThat(c2.getChannel()).isEqualTo("Yazarkasa");
            assertThat(links.findById(cLink).orElseThrow().getIncidentUpdatedAt()).isEqualTo(c2.getUpdatedAt());
            assertThat(newTs).isNotNull();
        } finally {
            links.deleteForReport(reportId);
            repo.deleteAllById(created);
            jdbc().update("DELETE FROM incident_options WHERE team_id = ?", team);
        }
    }

    @Test
    @DisplayName("inceleme 2026-11-26: satır kilidi beklemesi SINIRLI — başka oturum satırı tutarken lockRows lock_timeout sonunda "
            + "PessimisticLockingFailureException (503 LOCK_CONFLICT) verir; SET LOCAL işlemle biter, sonraki işlem bekler")
    void lockRowsWaitIsBounded() throws Exception {
        long team = 895_000L + (long) (Math.random() * 4_000);
        IncidentRecordRepository repo = bean(IncidentRecordRepository.class);
        org.springframework.transaction.support.TransactionTemplate tx = new org.springframework.transaction.support.TransactionTemplate(
                bean(org.springframework.transaction.PlatformTransactionManager.class));
        List<Long> created = new ArrayList<>();
        try {
            IncidentRecord a = save(team, "ip3-lock a", e -> e.setChannel("ATM"));
            created.add(a.getId());
            try (Connection other = PostgresIt.app().newSession()) {
                other.setAutoCommit(false);
                try (Statement st = other.createStatement()) {
                    st.executeQuery("SELECT id FROM incident_records WHERE id = " + a.getId() + " FOR UPDATE").close();
                }
                long t0 = System.nanoTime();
                org.assertj.core.api.Assertions.assertThatThrownBy(
                                () -> tx.executeWithoutResult(s -> repo.lockRows(List.of(a.getId()), 500L)))
                        .isInstanceOf(org.springframework.dao.PessimisticLockingFailureException.class);
                long ms = (System.nanoTime() - t0) / 1_000_000L;
                assertThat(ms).as("kilit beklemesi lock_timeout'la kesildi (%d ms)", ms).isBetween(300L, 5_000L);
                // aynı havuzdan yeni işlem: SET LOCAL sızmadı — kilit bırakılınca normal alınır
                other.commit();
            }
            List<Long> got = tx.execute(s -> repo.lockRows(List.of(a.getId())));
            assertThat(got).containsExactly(a.getId());
            // lock_timeout işlem dışına taşmadı
            String outside = jdbc().queryForObject("SHOW lock_timeout", String.class);
            assertThat(outside).isNotEqualTo("500ms");
        } finally {
            repo.deleteAllById(created);
        }
    }

    private static Long link(TechErrorReportIncidentRepository repo, long reportId, String issueId, Long incidentId,
                             String incidentUpdatedAt) {
        TechErrorReportIncident l = new TechErrorReportIncident();
        l.setReportId(reportId);
        l.setChannelId(reportId);
        l.setSectionId("issues");
        l.setIssueId(issueId);
        l.setIncidentId(incidentId);
        l.setIncidentUpdatedAt(incidentUpdatedAt);
        return repo.saveAndFlush(l).getId();
    }
}
