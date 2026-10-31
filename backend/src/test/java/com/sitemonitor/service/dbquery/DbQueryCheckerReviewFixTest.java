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

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Motorun 2026-10-31 inceleme düzeltmeleri: değişim kuralının tabanı çalıştırma türüne göre (Y1), önceki çalıştırma
 * okunamadı = "ilk çalıştırma" değil (D3), izin sırası önce bağlantı sonra pod + bekletmeden ayırma + ayrı sınırlı havuz (O1).
 */
class DbQueryCheckerReviewFixTest {

    DbConnectionProfileRepository profiles;
    DbQueryCheckRepository checks;
    DbQueryRunner runner;
    AppSettingsService settings;
    DbQueryCheckerService svc;

    @BeforeEach
    void setUp() {
        DbQuerySqlGuard.clearCache();
        profiles = mock(DbConnectionProfileRepository.class);
        checks = mock(DbQueryCheckRepository.class);
        runner = mock(DbQueryRunner.class);
        settings = mock(AppSettingsService.class);
        when(settings.getInt(anyString(), anyInt())).thenAnswer(i -> i.getArgument(1));
        when(settings.getString(anyString(), any())).thenAnswer(i -> i.getArgument(1));
        when(settings.getDouble(anyString(), anyDouble())).thenAnswer(i -> i.getArgument(1));
        svc = new DbQueryCheckerService(profiles, checks, mock(SecretCipher.class), runner, settings);
        profile(7L);
        profile(8L);
    }

    @AfterEach
    void tearDown() {
        svc.shutdown();
    }

    private void profile(long id) {
        DbConnectionProfile p = new DbConnectionProfile();
        p.setId(id);
        p.setTeamId(3L);
        p.setDbType("POSTGRESQL");
        p.setHost("db" + id + ".example");
        p.setPort(5432);
        p.setDatabaseName("app");
        p.setUsername("u");
        when(profiles.findById(id)).thenReturn(Optional.of(p));
    }

    private static DbQueryMonitor monitor(long id, long profileId, String rulesJson) {
        DbQueryMonitor m = new DbQueryMonitor();
        m.setId(id);
        m.setTeamId(3L);
        m.setProfileId(profileId);
        m.setSqlText("SELECT id FROM siparis");
        m.setRulesJson(rulesJson);
        return m;
    }

    private static DbQueryRunner.QueryResult rows(int n) {
        return new DbQueryRunner.QueryResult(true, 5, n, false, List.of(new DbQueryRunner.Column("id", "INT")), List.of(),
                false, null, null, "PostgreSQL 16");
    }

    private static DbQueryCheck row(double measure) {
        DbQueryCheck c = new DbQueryCheck();
        c.setRuleResultsJson("[{\"id\":\"c\",\"measure\":" + measure + "}]");
        return c;
    }

    static final String CHANGE = "[{\"id\":\"c\",\"kind\":\"CHANGE\",\"operator\":\"GT\",\"warning\":100,"
            + "\"change\":{\"of\":\"ROW_COUNT\",\"mode\":\"ABSOLUTE\",\"direction\":\"INCREASE\"}}]";

    @Test
    @DisplayName("Y1: zamanlanmış çalıştırma son zamanlanmış satırla, teyit / elle kontrol onaylanan çalıştırmanın TABANIYLA kıyaslar")
    void changeBaselineFollowsRunKind() {
        DbQueryMonitor m = monitor(11L, 7L, CHANGE);
        // en yeni zamanlanmış satır = ihlal eden çalıştırma (500), ondan önceki = onun tabanı (300)
        when(checks.findRecentScheduledOk(11L)).thenReturn(List.of(row(500), row(300)));
        when(runner.run(any(), anyString(), anyInt(), anyInt(), any())).thenReturn(rows(500));

        DbQueryCheckerService.Result scheduled = svc.check(m, DbQueryCheckerService.Run.SCHEDULED);
        assertThat(scheduled.status()).as("sonraki zamanlanmış: 500 → 500 değişim yok").isEqualTo("OK");

        DbQueryCheckerService.Result recheck = svc.recheck(m);
        assertThat(recheck.status()).as("teyit: 300 → 500, ihlal sürüyor").isEqualTo("BREACH");
        assertThat(recheck.ruleResults().get(0).compared()).isEqualTo(200.0);

        DbQueryCheckerService.Result manual = svc.check(m, true);
        assertThat(manual.status()).as("elle kontrol açık değişim alarmını sahte kapanışla kapatmaz").isEqualTo("BREACH");
    }

    @Test
    @DisplayName("Y1: tek zamanlanmış satır varken teyit 'ilk çalıştırma' görür (kanıt yok — sahte geçti yok)")
    void recheckWithOnlyOneScheduledRowIsFirstRun() {
        DbQueryMonitor m = monitor(12L, 7L, CHANGE);
        when(checks.findRecentScheduledOk(12L)).thenReturn(List.of(row(500)));
        when(runner.run(any(), anyString(), anyInt(), anyInt(), any())).thenReturn(rows(500));
        DbQueryCheckerService.Result r = svc.recheck(m);
        assertThat(r.ruleResults().get(0).evaluated()).isFalse();
        assertThat(r.ruleResults().get(0).note()).isEqualTo(DbQueryEvaluator.NOTE_FIRST_RUN);
    }

    @Test
    @DisplayName("D3: önceki çalıştırma OKUNAMADI → 'prev_unreadable' (ilk çalıştırma değil) ve eşik kararı BİLİNMİYOR")
    void unreadablePreviousIsNotFirstRun() {
        DbQueryMonitor m = monitor(13L, 7L, CHANGE);
        when(checks.findRecentScheduledOk(13L)).thenThrow(new org.springframework.dao.QueryTimeoutException("x"));
        when(runner.run(any(), anyString(), anyInt(), anyInt(), any())).thenReturn(rows(9_999));
        DbQueryCheckerService.Result r = svc.check(m, false);
        assertThat(r.status()).isEqualTo("OK");
        assertThat(r.ruleResults().get(0).note()).isEqualTo(DbQueryEvaluator.NOTE_PREVIOUS_UNREADABLE);
        String verdict = DbQueryAlarmText.thresholdVerdict(r.status(), DbQueryAlarmText.toApi(r.ruleResults()), "ANY");
        assertThat(verdict).isEqualTo(DbQueryAlarmText.VERDICT_UNKNOWN);
    }

    @Test
    @DisplayName("O1: bağlantı izni pod izninden ÖNCE — dolu bağlantının izlemesi pod iznini tutmaz, öteki bağlantı çalışır")
    void connectionPermitFirstThenPod() {
        when(settings.getInt(eq(DbQueryCheckerService.KEY_MAX_CONCURRENCY), anyInt())).thenReturn(2);
        when(settings.getInt(eq(DbQueryCheckerService.KEY_PER_CONNECTION), anyInt())).thenReturn(1);
        DbQueryMonitor a1 = monitor(21L, 7L, null), a2 = monitor(22L, 7L, null), b1 = monitor(23L, 8L, null);

        DbQueryCheckerService.Reservation ra1 = svc.tryReserve(a1);
        assertThat(ra1).isNotNull();
        assertThat(svc.tryReserve(a2)).as("A bağlantısı dolu → bekletmeden null").isNull();
        assertThat(svc.podInUse()).as("A2 pod iznini TUTMADI").isEqualTo(1);
        DbQueryCheckerService.Reservation rb1 = svc.tryReserve(b1);
        assertThat(rb1).as("B bağlantısının izlemesi pod iznini alır").isNotNull();
        assertThat(svc.podInUse()).isEqualTo(2);

        ra1.release();
        ra1.release();   // iki kez bırakmak güvenli
        rb1.release();
        assertThat(svc.podInUse()).isZero();
        assertThat(svc.connectionInUse(7L)).isZero();
        assertThat(svc.connectionInUse(8L)).isZero();
    }

    @Test
    @DisplayName("O1: pod izni dolu iken bağlantı izni de bırakılır (yarım ayırma kalmaz)")
    void podFullReleasesConnectionPermit() {
        when(settings.getInt(eq(DbQueryCheckerService.KEY_MAX_CONCURRENCY), anyInt())).thenReturn(1);
        DbQueryCheckerService.Reservation held = svc.tryReserve(monitor(31L, 7L, null));
        assertThat(held).isNotNull();
        assertThat(svc.tryReserve(monitor(32L, 8L, null))).isNull();
        assertThat(svc.connectionInUse(8L)).as("B'nin bağlantı izni geri bırakıldı").isZero();
        held.release();
    }

    @Test
    @DisplayName("O1: ayrılmış çalıştırma motorun KENDİ havuzunda (dbquery-run-N) koşar; izin sorgudan sonra bırakılır")
    void reservedRunOnOwnPool() throws Exception {
        AtomicReference<String> thread = new AtomicReference<>();
        when(runner.run(any(), anyString(), anyInt(), anyInt(), any())).thenAnswer(i -> {
            thread.set(Thread.currentThread().getName());
            return rows(1);
        });
        DbQueryMonitor m = monitor(41L, 7L, null);
        DbQueryCheckerService.Reservation r = svc.tryReserve(m);
        CompletableFuture<String> f = svc.runReserved(m, r, res -> res.status() + "@" + svc.podInUse());
        assertThat(f.get(5, TimeUnit.SECONDS)).isEqualTo("OK@0");
        assertThat(thread.get()).startsWith(DbQueryCheckerService.RUN_THREAD_PREFIX);
        assertThat(svc.connectionInUse(7L)).isZero();
    }

    @Test
    @DisplayName("O1: kapanıştan sonra ayırma ve çalıştırma kabul edilmez — izin bırakılır")
    void closedRejectsAndReleases() {
        DbQueryMonitor m = monitor(51L, 7L, null);
        DbQueryCheckerService.Reservation r = svc.tryReserve(m);
        assertThat(r).isNotNull();
        svc.shutdown();
        assertThat(svc.runReserved(m, r, res -> res)).isNull();
        assertThat(svc.podInUse()).isZero();
        assertThat(svc.tryReserve(m)).isNull();
    }

    @Test
    @DisplayName("O1: teyit yeniden kontrolü izni kısa bekler (MOS havuzu 30 sn tutulmaz) → ATLA")
    void recheckWaitsShort() throws Exception {
        when(settings.getInt(eq(DbQueryCheckerService.KEY_MAX_CONCURRENCY), anyInt())).thenReturn(1);
        svc.setRecheckWaitForTests(100);
        DbQueryCheckerService.Reservation held = svc.tryReserve(monitor(61L, 8L, null));
        long t0 = System.nanoTime();
        DbQueryCheckerService.Result r = svc.recheck(monitor(62L, 7L, null));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertThat(r.status()).isEqualTo("SKIPPED");
        assertThat(r.failureReason()).isEqualTo("DB_BUSY");
        assertThat(ms).isLessThan(3_000);
        held.release();
    }

    @Test
    @DisplayName("O1: awaitRelease bir izin bırakılınca erken uyanır")
    void awaitReleaseWakesOnRelease() throws Exception {
        DbQueryCheckerService.Reservation r = svc.tryReserve(monitor(71L, 7L, null));
        CountDownLatch woke = new CountDownLatch(1);
        Thread t = new Thread(() -> { svc.awaitRelease(10_000); woke.countDown(); });
        t.start();
        Thread.sleep(100);
        r.release();
        assertThat(woke.await(3, TimeUnit.SECONDS)).isTrue();
        t.join(1_000);
    }

    @Test
    @DisplayName("D3: eşik kararı — ANY'de kanıt eksikliği BİLİNMİYOR, ALL'da sağlanan kural varsa kesin OK; satır yok / NULL geçti")
    void thresholdVerdictTruthTable() {
        Map<String, Object> passed = Map.of("evaluated", true, "passed", true);
        Map<String, Object> missing = Map.of("evaluated", false, "passed", true, "note", "column_missing");
        Map<String, Object> noRows = Map.of("evaluated", false, "passed", true, "note", "no_rows");
        Map<String, Object> nullValue = Map.of("evaluated", false, "passed", true, "note", "null_value");
        Map<String, Object> breached = Map.of("evaluated", true, "passed", false);
        assertThat(DbQueryAlarmText.thresholdVerdict("BREACH", List.of(breached, missing), "ANY")).isEqualTo("BREACH");
        assertThat(DbQueryAlarmText.thresholdVerdict("OK", List.of(passed, missing), "ANY")).isEqualTo("UNKNOWN");
        assertThat(DbQueryAlarmText.thresholdVerdict("OK", List.of(passed, missing), "ALL")).isEqualTo("OK");
        assertThat(DbQueryAlarmText.thresholdVerdict("OK", List.of(breached, missing), "ALL")).isEqualTo("UNKNOWN");
        assertThat(DbQueryAlarmText.thresholdVerdict("OK", List.of(noRows, nullValue, passed), "ANY")).isEqualTo("OK");
        assertThat(DbQueryAlarmText.thresholdVerdict("OK", List.of(), "ANY")).isEqualTo("OK");
        assertThat(DbQueryAlarmText.thresholdVerdict("OK", null, null)).isEqualTo("OK");
    }

    @Test
    @DisplayName("D3: boş kümede MAX = NULL 'değer yok' (geçti), metin değer 'sayı değil' (kanıt yok)")
    void nullCellIsNoValueNotNotNumeric() {
        DbQueryRunner.QueryResult nullCell = new DbQueryRunner.QueryResult(true, 5, 1, false,
                List.of(new DbQueryRunner.Column("gecikme", "INT")), List.of(java.util.Arrays.asList((Object) null)), false,
                null, null, null);
        DbQueryRules.Rule rule = DbQueryRules.fromJson("[{\"id\":\"v\",\"kind\":\"COLUMN_VALUE\",\"column\":\"gecikme\","
                + "\"operator\":\"GT\",\"warning\":10}]").get(0);
        DbQueryEvaluator.Outcome o = DbQueryEvaluator.evaluate(List.of(rule), "ANY", nullCell, Map.of(), "tr");
        assertThat(o.results().get(0).note()).isEqualTo(DbQueryEvaluator.NOTE_NULL_VALUE);
        assertThat(DbQueryAlarmText.thresholdVerdict(o.status(), DbQueryAlarmText.toApi(o.results()), "ANY")).isEqualTo("OK");

        DbQueryRunner.QueryResult text = new DbQueryRunner.QueryResult(true, 5, 1, false,
                List.of(new DbQueryRunner.Column("gecikme", "VARCHAR")), List.of(List.of((Object) "abc")), false,
                null, null, null);
        o = DbQueryEvaluator.evaluate(List.of(rule), "ANY", text, Map.of(), "tr");
        assertThat(o.results().get(0).note()).isEqualTo(DbQueryEvaluator.NOTE_NOT_NUMERIC);
        assertThat(DbQueryAlarmText.thresholdVerdict(o.status(), DbQueryAlarmText.toApi(o.results()), "ANY"))
                .isEqualTo("UNKNOWN");
    }
}
