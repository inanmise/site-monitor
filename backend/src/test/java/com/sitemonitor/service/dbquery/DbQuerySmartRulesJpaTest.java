package com.sitemonitor.service.dbquery;

import com.sitemonitor.model.DbConnectionProfile;
import com.sitemonitor.model.DbQueryCheck;
import com.sitemonitor.model.DbQueryMonitor;
import com.sitemonitor.repository.DbConnectionProfileRepository;
import com.sitemonitor.repository.DbQueryCheckRepository;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.SecretCipher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Akıllı kurallar (dbq2 D) gerçek tabloda (JPA / H2, varlığın ürettiği şema): geçmiş okuması ({@link DbQueryHistory#read} —
 * TEK sorgu, yakın geçmiş / mevsimsel aralıklar / ikisi birlikte {@code UNION ALL}, elle / teyit / hatalı / başka izleme satırı
 * hariç, aynı saniyede kimlikle ayrım) ve uçtan uca çalıştırma ({@link DbQueryCheckerService} gerçek depo + gerçek
 * değerlendirici, çalıştırıcı taklit): taban kuruluyor → değerlendirilir → ihlal; teyit zinciri tabanı ihlal satırını DIŞLAR;
 * elle kontrol tabanı bozmaz; sorgu değişince taban sıfırlanır; kolon değeri kuralını anomaliye çevirmek geçmişi korur;
 * günlük mevsim; donmuş değer; geçmiş okunamazsa UNKNOWN. Yazmalar {@code NOT_SUPPORTED}: her test kendi satırlarını siler.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DbQuerySmartRulesJpaTest {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final long MON = 11L;

    @Autowired DbQueryCheckRepository checks;
    @Autowired DataSource dataSource;

    JdbcTemplate jdbc;
    DbQueryCheckerService svc;
    final AtomicReference<Object> next = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        DbQuerySqlGuard.clearCache();
        jdbc = new JdbcTemplate(dataSource);
        DbConnectionProfileRepository profiles = mock(DbConnectionProfileRepository.class);
        DbConnectionProfile p = new DbConnectionProfile();
        p.setId(7L);
        p.setTeamId(3L);
        p.setDbType("POSTGRESQL");
        p.setHost("db.example");
        p.setPort(5432);
        p.setDatabaseName("app");
        p.setUsername("u");
        when(profiles.findById(7L)).thenReturn(Optional.of(p));
        DbQueryRunner runner = mock(DbQueryRunner.class);
        when(runner.run(any(), anyString(), anyInt(), anyInt(), any(DbQueryRunner.RunOptions.class))).thenAnswer(i -> {
            List<List<Object>> rows = new ArrayList<>();
            rows.add(Arrays.asList(next.get()));
            return new DbQueryRunner.QueryResult(true, 4, 1, false, List.of(new DbQueryRunner.Column("adet", "NUMERIC")),
                    rows, false, null, null, "PostgreSQL 16");
        });
        AppSettingsService settings = mock(AppSettingsService.class);
        when(settings.getInt(anyString(), anyInt())).thenAnswer(i -> i.getArgument(1));
        when(settings.getString(anyString(), any())).thenAnswer(i -> i.getArgument(1));
        when(settings.getDouble(anyString(), anyDouble())).thenAnswer(i -> i.getArgument(1));
        svc = new DbQueryCheckerService(profiles, checks, mock(SecretCipher.class), runner, settings);
        svc.setJdbc(jdbc);
    }

    @AfterEach
    void cleanUp() {
        svc.shutdown();
        checks.deleteAll();
    }

    private static DbQueryMonitor monitor(String rules) {
        DbQueryMonitor m = new DbQueryMonitor();
        m.setId(MON);
        m.setTeamId(3L);
        m.setProfileId(7L);
        m.setSqlText("SELECT adet FROM kuyruk");
        m.setRulesJson(rules);
        m.setIntervalSeconds(300);
        return m;
    }

    /** Çalıştırır ve zamanlayıcının yazdığı gibi kontrol satırını kaydeder (recordDbQueryRun'ın taban kolonları). */
    private DbQueryCheckerService.Result run(DbQueryMonitor m, DbQueryCheckerService.Run run,
                                             DbQueryCheckerService.Anchor anchor, Object value) {
        next.set(value);
        DbQueryCheckerService.Result r = run == DbQueryCheckerService.Run.RECHECK ? svc.recheck(m, anchor) : svc.check(m, run);
        DbQueryCheck c = new DbQueryCheck();
        c.setMonitorId(m.getId());
        c.setCheckedAt(r.checkedAt());
        c.setOk(!"FAIL".equals(r.status()));
        c.setStatus(r.status());
        c.setRuleResultsJson(DbQueryAlarmText.toJson(r.ruleResults()));
        c.setSqlHash(r.sqlHash());
        c.setManual(run == DbQueryCheckerService.Run.MANUAL);
        c.setRecheck(run == DbQueryCheckerService.Run.RECHECK);
        lastSaved = checks.save(c);
        return r;
    }

    private DbQueryCheck lastSaved;

    private DbQueryCheckerService.Result scheduled(DbQueryMonitor m, Object value) {
        return run(m, DbQueryCheckerService.Run.SCHEDULED, null, value);
    }

    private DbQueryCheck insert(long monitorId, Instant at, boolean ok, Boolean manual, Boolean recheck, String hash,
                                String json) {
        DbQueryCheck c = new DbQueryCheck();
        c.setMonitorId(monitorId);
        c.setCheckedAt(ISO.format(at));
        c.setOk(ok);
        c.setStatus(ok ? "OK" : "FAIL");
        c.setManual(manual);
        c.setRecheck(recheck);
        c.setSqlHash(hash);
        c.setRuleResultsJson(json);
        return checks.save(c);
    }

    private static String stored(String id, String fp, Double v) {
        return DbQueryAlarmText.toJson(List.of(new DbQueryEvaluator.RuleResult(id, null, "ANOMALY", "x", "y", "NONE", true,
                v != null, null, v, v, fp)));
    }

    // ── Geçmiş okuması ───────────────────────────────────────────────────────

    @Test
    @DisplayName("read: yakın geçmiş en yeni N zamanlanmış ok satırı; elle / teyit / hatalı / başka izleme yok; çapa aynı saniyede kimlikle")
    void readRecent() {
        Instant t = Instant.parse("2026-11-02T06:00:00Z");
        insert(MON, t, true, null, null, "h", "[]");
        DbQueryCheck s2 = insert(MON, t.plusSeconds(60), true, false, false, "h", "[]");
        DbQueryCheck s2b = insert(MON, t.plusSeconds(60), true, false, false, "h", "[]");   // aynı saniye
        insert(MON, t.plusSeconds(90), true, false, true, "h", "[]");    // teyit
        insert(MON, t.plusSeconds(100), true, true, false, "h", "[]");   // elle
        insert(MON, t.plusSeconds(110), false, false, false, "h", "[]"); // hatalı
        insert(MON + 1, t.plusSeconds(120), true, false, false, "h", "[]");   // başka izleme
        DbQueryCheck s3 = insert(MON, t.plusSeconds(180), true, false, false, "h", "[]");
        DbQueryHistory.Plan plan = new DbQueryHistory.Plan(10, List.of(), 0, t.plusSeconds(200), 150_000);
        assertThat(DbQueryHistory.read(jdbc, MON, null, null, plan)).extracting(DbQueryHistory.Row::id)
                .containsExactly(s3.getId(), s2b.getId(), s2.getId(), s2.getId() - 1);
        DbQueryHistory.Plan two = new DbQueryHistory.Plan(2, List.of(), 0, t, 150_000);
        assertThat(DbQueryHistory.read(jdbc, MON, null, null, two)).extracting(DbQueryHistory.Row::id)
                .containsExactly(s3.getId(), s2b.getId());
        // çapa S2b → yalnız S2 ve öncesi (aynı saniyede küçük kimlik)
        assertThat(DbQueryHistory.read(jdbc, MON, s2b.getCheckedAt(), s2b.getId(), plan)).extracting(DbQueryHistory.Row::id)
                .containsExactly(s2.getId(), s2.getId() - 1);
        assertThat(DbQueryHistory.read(jdbc, MON, s3.getCheckedAt(), -1L, plan)).hasSize(3);
    }

    @Test
    @DisplayName("read: mevsimsel aralıklar dizinde; yakın geçmişle birlikte TEK ifade (UNION ALL), kimlik tekil, yeniden eskiye")
    void readSeasonalAndUnion() {
        Instant ref = Instant.parse("2026-11-05T10:00:00Z");
        long day = 86_400L;
        DbQueryCheck d1 = insert(MON, ref.minusSeconds(day - 30), true, false, false, "h", "[]");
        insert(MON, ref.minusSeconds(day + 3_600), true, false, false, "h", "[]");    // dün, başka saat
        DbQueryCheck d2 = insert(MON, ref.minusSeconds(2 * day), true, false, false, "h", "[]");
        insert(MON, ref.minusSeconds(2 * day + 30), true, true, false, "h", "[]");    // elle — hariç
        DbQueryCheck r1 = insert(MON, ref.minusSeconds(60), true, false, false, "h", "[]");
        DbQueryCheck r2 = insert(MON, ref.minusSeconds(120), true, false, false, "h", "[]");
        DbQueryRules.Rule daily = DbQuerySmartRulesTest.rule("kind", "ANOMALY", "column", "adet", "season", "daily",
                "window", 10);
        DbQueryHistory.Plan sp = DbQueryHistory.plan(List.of(daily), ref, 300);
        assertThat(DbQueryHistory.read(jdbc, MON, null, null, sp)).extracting(DbQueryHistory.Row::id)
                .containsExactly(d1.getId(), d2.getId());
        DbQueryRules.Rule recent = DbQuerySmartRulesTest.rule("id", "n", "kind", "ANOMALY", "column", "adet", "window", 10);
        DbQueryHistory.Plan both = DbQueryHistory.plan(List.of(daily, recent), ref, 300);
        List<DbQueryHistory.Row> rows = DbQueryHistory.read(jdbc, MON, null, null, both);
        // yakın dal en yeni 10 satırı (5 zamanlanmış) — mevsimsel dal d1, d2 (yinelenen kimlik tekilleşir)
        assertThat(rows).extracting(DbQueryHistory.Row::id).doesNotHaveDuplicates()
                .startsWith(r1.getId(), r2.getId(), d1.getId()).contains(d2.getId()).hasSize(5);
    }

    // ── Uçtan uca çalıştırma ─────────────────────────────────────────────────

    @Test
    @DisplayName("anomali: taban kuruluyor → değerlendirilir → ihlal; teyit / elle tabanı ihlal satırını dışlar; sonraki taban elle / teyit satırı içermez")
    void anomalyEndToEnd() {
        DbQueryMonitor m = monitor("[{\"id\":\"r\",\"label\":\"Kuyruk\",\"kind\":\"ANOMALY\",\"column\":\"adet\","
                + "\"window\":10,\"min_samples\":5}]");
        int[] warm = {100, 102, 98, 101, 99};
        for (int i = 0; i < warm.length; i++) {
            DbQueryCheckerService.Result r = scheduled(m, BigDecimal.valueOf(warm[i]));
            DbQueryEvaluator.RuleResult rr = r.ruleResults().get(0);
            assertThat(rr.note()).isEqualTo(DbQueryEvaluator.NOTE_BUILDING_BASELINE);
            assertThat(rr.stats().samples()).isEqualTo(i);
            assertThat(r.status()).as("taban kuruluyor: ihlal yok, UNKNOWN yok").isEqualTo("OK");
            assertThat(r.metricValue()).isEqualTo((double) warm[i]);
        }
        DbQueryCheckerService.Result normal = scheduled(m, BigDecimal.valueOf(100));
        assertThat(normal.ruleResults().get(0).evaluated()).isTrue();
        assertThat(normal.ruleResults().get(0).stats().samples()).isEqualTo(5);
        assertThat(normal.status()).isEqualTo("OK");

        DbQueryCheckerService.Result spike = scheduled(m, BigDecimal.valueOf(130));
        assertThat(spike.status()).isEqualTo("BREACH");
        assertThat(spike.level()).isEqualTo("CRITICAL");
        DbQueryCheck spikeRow = lastSaved;

        // Teyit: taban ZİNCİRİ BAŞLATAN satırdan önceki satırlar — ihlal değeri (130) tabanı şişirmez
        DbQueryCheckerService.Result confirm = run(m, DbQueryCheckerService.Run.RECHECK,
                new DbQueryCheckerService.Anchor(spikeRow.getCheckedAt(), spikeRow.getId()), BigDecimal.valueOf(131));
        assertThat(confirm.status()).isEqualTo("BREACH");
        assertThat(confirm.ruleResults().get(0).stats().samples()).isEqualTo(6);
        assertThat(confirm.ruleResults().get(0).stats().mean()).isEqualTo(100.0);
        // Elle "Şimdi kontrol et": tetikleyen = son zamanlanmış (130) → onun öncesi taban
        DbQueryCheckerService.Result manual = run(m, DbQueryCheckerService.Run.MANUAL, null, BigDecimal.valueOf(132));
        assertThat(manual.status()).isEqualTo("BREACH");
        assertThat(manual.ruleResults().get(0).stats().samples()).isEqualTo(6);

        // Sonraki zamanlanmış: taban = zamanlanmış satırlar (130 dahil, elle / teyit satırları HARİÇ) → 7 örnek
        DbQueryCheckerService.Result after = scheduled(m, BigDecimal.valueOf(100));
        assertThat(after.ruleResults().get(0).stats().samples()).as("elle / teyit satırı taban değil").isEqualTo(7);
        assertThat(after.status()).isEqualTo("OK");
    }

    @Test
    @DisplayName("sorgu değişince taban sıfırlanır (baseline_reset, ihlal değil); geçmiş okunamazsa kanıt eksikliği → UNKNOWN")
    void resetAndUnreadable() {
        DbQueryMonitor m = monitor("[{\"id\":\"r\",\"kind\":\"ANOMALY\",\"column\":\"adet\",\"window\":10,\"min_samples\":5}]");
        for (int i = 0; i < 6; i++) scheduled(m, BigDecimal.valueOf(100 + i % 2));
        m.setSqlText("SELECT adet FROM kuyruk WHERE durum = 'BEKLIYOR'");
        DbQueryCheckerService.Result reset = scheduled(m, BigDecimal.valueOf(9_000));
        assertThat(reset.ruleResults().get(0).note()).isEqualTo(DbQueryEvaluator.NOTE_BASELINE_RESET);
        assertThat(reset.status()).isEqualTo("OK");

        svc.setJdbc(null);
        DbQueryCheckerService.Result un = scheduled(m, BigDecimal.valueOf(1));
        assertThat(un.ruleResults().get(0).note()).isEqualTo(DbQueryEvaluator.NOTE_PREVIOUS_UNREADABLE);
        assertThat(un.status()).isEqualTo("UNKNOWN");
    }

    @Test
    @DisplayName("kolon değeri kuralını aynı kimlikle anomaliye çevirmek geçmişi korur — taban hemen hazır")
    void columnValueToAnomalyKeepsHistory() {
        DbQueryMonitor m = monitor("[{\"id\":\"r\",\"kind\":\"COLUMN_VALUE\",\"column\":\"adet\",\"operator\":\"GT\","
                + "\"warning\":1000}]");
        for (int i = 0; i < 6; i++) scheduled(m, BigDecimal.valueOf(i % 2 == 0 ? 98 : 102));
        m.setRulesJson("[{\"id\":\"r\",\"kind\":\"ANOMALY\",\"column\":\"adet\",\"window\":10,\"min_samples\":5}]");
        DbQueryCheckerService.Result r = scheduled(m, BigDecimal.valueOf(150));
        assertThat(r.ruleResults().get(0).evaluated()).isTrue();
        assertThat(r.ruleResults().get(0).stats().samples()).isEqualTo(6);
        assertThat(r.status()).isEqualTo("BREACH");
    }

    @Test
    @DisplayName("günlük mevsim: önceki günlerin aynı saati taban; aynı gün başka saatteki satırlar yok sayılır")
    void dailySeason() {
        String rules = "[{\"id\":\"r\",\"kind\":\"ANOMALY\",\"column\":\"adet\",\"season\":\"daily\",\"window\":10,"
                + "\"min_samples\":5}]";
        DbQueryMonitor m = monitor(rules);
        String fp = DbQueryRules.fingerprint(DbQueryRules.fromJson(rules).get(0));
        String hash = DbQueryCheckerService.sqlHash(m.getSqlText());
        Instant now = Instant.now();
        for (int k = 1; k <= 6; k++) {
            insert(MON, now.minusSeconds(86_400L * k - 20), true, false, false, hash, stored("r", fp, 100.0 + k % 3));
            insert(MON, now.minusSeconds(86_400L * k + 7_200), true, false, false, hash, stored("r", fp, 5_000.0));
        }
        DbQueryCheckerService.Result ok = scheduled(m, BigDecimal.valueOf(101));
        assertThat(ok.ruleResults().get(0).stats().samples()).isEqualTo(6);
        assertThat(ok.status()).isEqualTo("OK");
        assertThat(ok.ruleResults().get(0).expected()).contains("önceki 10 günün aynı saati");
        DbQueryCheckerService.Result spike = scheduled(m, BigDecimal.valueOf(300));
        assertThat(spike.status()).isEqualTo("BREACH");
    }

    @Test
    @DisplayName("donmuş değer: 5, 5, 5 → üçüncüde uyarı; değer değişince geçer; değersiz çalıştırma diziyi kırar")
    void frozenEndToEnd() {
        DbQueryMonitor m = monitor("[{\"id\":\"f\",\"kind\":\"FROZEN\",\"column\":\"adet\",\"runs_warning\":3}]");
        assertThat(scheduled(m, 5).ruleResults().get(0).note()).isEqualTo(DbQueryEvaluator.NOTE_BUILDING_BASELINE);
        assertThat(scheduled(m, 5).status()).isEqualTo("OK");
        DbQueryCheckerService.Result third = scheduled(m, 5);
        assertThat(third.status()).isEqualTo("BREACH");
        assertThat(third.level()).isEqualTo("WARNING");
        assertThat(third.ruleResults().get(0).actual()).isEqualTo("aynı değer 3 çalıştırmadır · 5");
        assertThat(scheduled(m, 6).status()).isEqualTo("OK");
        scheduled(m, null);   // değer boş → dizi kırılır
        assertThat(scheduled(m, 6).ruleResults().get(0).evaluated()).as("kesin kırılma → dizi 1, karar kesin").isTrue();
        assertThat(scheduled(m, 6).status()).as("6, (boş), 6, 6 → dizi 2").isEqualTo("OK");
    }
}
