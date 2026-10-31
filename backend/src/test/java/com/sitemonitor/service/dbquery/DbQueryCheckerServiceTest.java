package com.sitemonitor.service.dbquery;

import com.sitemonitor.model.DbConnectionProfile;
import com.sitemonitor.model.DbQueryCheck;
import com.sitemonitor.model.DbQueryMonitor;
import com.sitemonitor.repository.DbConnectionProfileRepository;
import com.sitemonitor.repository.DbQueryCheckRepository;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.SecretCipher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Çalıştırma akışı: bağlantı yok / başka takım / parola okunamaz / güvenlik reddi (sorgu ÇALIŞMAZ) / izin dolu (ATLA) /
 * eşik ihlali / değişim kuralı önceki ölçüyle / mail satırları + maskeleme / bağlantı sağlığı (yalnız kayıtlı çalıştırma) /
 * parola geçersiz kılmalı bağlantı denemesi (sağlığa yazmaz) / maliyet hükmü.
 */
class DbQueryCheckerServiceTest {

    private DbConnectionProfileRepository profiles;
    private DbQueryCheckRepository checks;
    private SecretCipher cipher;
    private DbQueryRunner runner;
    private AppSettingsService settings;
    private DbQueryCheckerService svc;
    private DbConnectionProfile profile;

    @BeforeEach
    void setUp() {
        DbQuerySqlGuard.clearCache();
        profiles = mock(DbConnectionProfileRepository.class);
        checks = mock(DbQueryCheckRepository.class);
        cipher = mock(SecretCipher.class);
        runner = mock(DbQueryRunner.class);
        settings = mock(AppSettingsService.class);
        when(settings.getInt(anyString(), anyInt())).thenAnswer(inv -> inv.getArgument(1));
        when(settings.getString(anyString(), any())).thenAnswer(inv -> inv.getArgument(1));
        when(settings.getDouble(anyString(), org.mockito.ArgumentMatchers.anyDouble())).thenAnswer(inv -> inv.getArgument(1));
        svc = new DbQueryCheckerService(profiles, checks, cipher, runner, settings);
        profile = new DbConnectionProfile();
        profile.setId(7L);
        profile.setTeamId(3L);
        profile.setDbType("POSTGRESQL");
        profile.setHost("db.example");
        profile.setPort(5432);
        profile.setDatabaseName("app");
        profile.setUsername("u");
        profile.setPasswordEnc("enc:v1:xyz");
        when(cipher.decrypt("enc:v1:xyz")).thenReturn("secret");
        when(profiles.findById(7L)).thenReturn(Optional.of(profile));
    }

    private DbQueryMonitor monitor(String sql, String rulesJson) {
        DbQueryMonitor m = new DbQueryMonitor();
        m.setId(11L);
        m.setTeamId(3L);
        m.setProfileId(7L);
        m.setSqlText(sql);
        m.setRulesJson(rulesJson);
        return m;
    }

    private static DbQueryRunner.QueryResult ok(List<String> cols, Object[]... rows) {
        List<List<Object>> r = new ArrayList<>();
        for (Object[] row : rows) r.add(Arrays.asList(row));
        return new DbQueryRunner.QueryResult(true, 42, r.size(), false,
                cols.stream().map(c -> new DbQueryRunner.Column(c, "VARCHAR")).toList(), r, false, null, null, "PostgreSQL 16");
    }

    @Test
    void missingOrForeignProfileFailsWithoutRunning() {
        DbQueryMonitor m = monitor("SELECT 1", null);
        m.setProfileId(99L);
        when(profiles.findById(99L)).thenReturn(Optional.empty());
        assertThat(svc.check(m, false).failureReason()).isEqualTo("DB_PROFILE_MISSING");
        DbQueryMonitor other = monitor("SELECT 1", null);
        other.setTeamId(4L);
        DbQueryCheckerService.Result r = svc.check(other, false);
        assertThat(r.status()).isEqualTo("FAIL");
        assertThat(r.failureReason()).isEqualTo("DB_PROFILE_OTHER_TEAM");
        verifyNoInteractions(runner);
    }

    @Test
    void unreadableSecretFails() {
        when(cipher.decrypt("enc:v1:xyz")).thenReturn(null);
        DbQueryCheckerService.Result r = svc.check(monitor("SELECT 1", null), false);
        assertThat(r.failureReason()).isEqualTo("DB_SECRET_UNREADABLE");
        verifyNoInteractions(runner);
    }

    @Test
    void guardRejectionNeverRunsTheQuery() {
        DbQueryCheckerService.Result r = svc.check(monitor("SELECT 1; DELETE FROM t", null), false);
        assertThat(r.status()).isEqualTo("FAIL");
        assertThat(r.failureReason()).isEqualTo("DB_GUARD_REJECTED");
        assertThat(r.error()).isNotBlank();
        verifyNoInteractions(runner);
        verify(profiles, never()).recordUseError(anyLong(), anyString(), anyInt(), anyString());
    }

    @Test
    void breachLevelSlowHealthAndHash() {
        DbQueryMonitor m = monitor("SELECT * FROM orders", "[{\"id\":\"r1\",\"kind\":\"ROW_COUNT\",\"operator\":\"GT\","
                + "\"warning\":1,\"critical\":5}]");
        m.setSlowQueryMs(40);
        when(runner.run(any(), anyString(), anyInt(), anyInt(), any())).thenReturn(ok(List.of("id"),
                new Object[] {1}, new Object[] {2}, new Object[] {3}));
        DbQueryCheckerService.Result r = svc.check(m, false);
        assertThat(r.status()).isEqualTo("BREACH");
        assertThat(r.level()).isEqualTo("WARNING");
        assertThat(r.slow()).isTrue();
        assertThat(r.rowCount()).isEqualTo(3);
        assertThat(r.metricValue()).isEqualTo(3.0);
        assertThat(r.sqlHash()).hasSize(16).isEqualTo(DbQueryCheckerService.sqlHash("SELECT * FROM orders"));
        assertThat(r.checkedAt()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}");
        assertThat(r.profileId()).isEqualTo(7L);
        verify(profiles).recordUseOk(eq(7L), anyString(), eq(42));
        // zaman aşımı / satır sınırı varsayılan ve tavanla
        verify(runner).run(any(), eq("SELECT * FROM orders"), eq(30), eq(1000), any());
    }

    @Test
    void limitsAreCappedBySettings() {
        when(settings.getInt(eq(DbQueryCheckerService.KEY_MAX_TIMEOUT), anyInt())).thenReturn(20);
        when(settings.getInt(eq(DbQueryCheckerService.KEY_MAX_ROWS_CAP), anyInt())).thenReturn(50);
        DbQueryMonitor m = monitor("SELECT 1", null);
        m.setQueryTimeoutSeconds(60);
        m.setMaxRows(5000);
        when(runner.run(any(), anyString(), anyInt(), anyInt(), any())).thenReturn(ok(List.of("a")));
        svc.check(m, false);
        verify(runner).run(any(), anyString(), eq(20), eq(50), any());
    }

    @Test
    void failedRunRecordsHealthError() {
        when(runner.run(any(), anyString(), anyInt(), anyInt(), any())).thenReturn(new DbQueryRunner.QueryResult(false, 5, 0,
                false, List.of(), List.of(), false, "connection refused", "DB_UNREACHABLE", null));
        DbQueryCheckerService.Result r = svc.check(monitor("SELECT 1", null), false);
        assertThat(r.status()).isEqualTo("FAIL");
        assertThat(r.failureReason()).isEqualTo("DB_UNREACHABLE");
        assertThat(r.error()).isEqualTo("connection refused");
        verify(profiles).recordUseError(eq(7L), anyString(), eq(5), eq("connection refused"));
    }

    @Test
    void changeRuleReadsPreviousScheduledMeasure() {
        DbQueryMonitor m = monitor("SELECT 1", "[{\"id\":\"c\",\"kind\":\"CHANGE\",\"operator\":\"GT\",\"warning\":10,"
                + "\"change\":{\"of\":\"ROW_COUNT\",\"mode\":\"PERCENT\",\"direction\":\"INCREASE\"}}]");
        DbQueryCheck prev = new DbQueryCheck();
        prev.setRuleResultsJson("[{\"id\":\"c\",\"measure\":2}]");
        when(checks.findPreviousScheduledOk(11L)).thenReturn(Optional.of(prev));
        when(runner.run(any(), anyString(), anyInt(), anyInt(), any())).thenReturn(ok(List.of("a"),
                new Object[] {1}, new Object[] {2}, new Object[] {3}));
        DbQueryCheckerService.Result r = svc.check(m, false);
        assertThat(r.status()).isEqualTo("BREACH");
        assertThat(r.ruleResults().get(0).compared()).isEqualTo(50.0);
    }

    @Test
    void mailRowsSelectedAndMaskedButNeverInTest() {
        DbQueryMonitor m = monitor("SELECT ad, kart, tutar FROM t", "[{\"id\":\"r1\",\"kind\":\"TEXT_MATCH\","
                + "\"column\":\"KART\",\"operator\":\"CONTAINS\",\"warning\":\"4111\"}]");
        m.setMaskedColumnsJson("[\"Kart\", \"tutar\"]");
        m.setMailColumnsJson("[\"tutar\", \"ad\", \"kart\", \"yok\"]");
        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<Set<String>> discard = ArgumentCaptor.forClass((Class) Set.class);
        when(runner.run(any(), anyString(), anyInt(), anyInt(), discard.capture())).thenReturn(ok(List.of("ad", "kart", "tutar"),
                new Object[] {"Ayşe", "4111-1", new BigDecimal("10.50")}, new Object[] {"x".repeat(300), "5", null}));
        DbQueryCheckerService.Result r = svc.check(m, false);
        // kural kullanan maskeli kolon okunur; kullanılmayan maskeli kolon hiç okunmaz
        assertThat(discard.getValue()).containsExactly("tutar");
        assertThat(r.mailColumns()).containsExactly("tutar", "ad", "kart");
        assertThat(r.maskedColumns()).containsExactly("tutar", "kart");
        assertThat(r.mailRows().get(0)).containsExactly("••••", "Ayşe", "••••");
        assertThat(r.mailRows().get(1).get(1)).hasSize(200).endsWith("…");
        assertThat(r.rowsTruncated()).isTrue();
        assertThat(r.status()).isEqualTo("BREACH");

        DbQueryCheckerService.Result t = svc.test(m, null);
        assertThat(t.mailRows()).isEmpty();
        assertThat(t.mailColumns()).containsExactly("tutar", "ad", "kart");
        // deneme sağlığa yazmaz
        verify(profiles, org.mockito.Mockito.times(1)).recordUseOk(anyLong(), anyString(), anyInt());
    }

    @Test
    void mailIncludeRowsOffAndDefaultColumns() {
        DbQueryMonitor m = monitor("SELECT 1", null);
        m.setMailIncludeRows(false);
        when(runner.run(any(), anyString(), anyInt(), anyInt(), any())).thenReturn(ok(List.of("a", "b"), new Object[] {1, 2}));
        DbQueryCheckerService.Result r = svc.check(m, false);
        assertThat(r.mailRows()).isEmpty();
        assertThat(r.mailColumns()).containsExactly("a", "b");
        when(settings.getInt(eq(DbQueryCheckerService.KEY_MAIL_MAX_COLUMNS), anyInt())).thenReturn(1);
        m.setMailIncludeRows(true);
        DbQueryCheckerService.Result r2 = svc.check(m, false);
        assertThat(r2.mailColumns()).containsExactly("a");
        assertThat(r2.mailRows()).containsExactly(List.of("1"));
        assertThat(r2.rowsTruncated()).isTrue();
    }

    @Test
    void busyPermitsSkip() throws Exception {
        when(settings.getInt(eq(DbQueryCheckerService.KEY_MAX_CONCURRENCY), anyInt())).thenReturn(1);
        svc.setWaitsForTests(200, 200);
        CountDownLatch inside = new CountDownLatch(1), release = new CountDownLatch(1);
        when(runner.run(any(), anyString(), anyInt(), anyInt(), any())).thenAnswer(inv -> {
            inside.countDown();
            release.await(5, TimeUnit.SECONDS);
            return ok(List.of("a"));
        });
        Thread t = new Thread(() -> svc.check(monitor("SELECT 1", null), false));
        t.start();
        assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();
        // ikinci çalıştırma izin alamaz → ATLA (bekleme süresini testte kısaltmak için doğrudan izinler sınanır)
        DbQueryCheckerService.Permits p = new DbQueryCheckerService.Permits();
        assertThat(p.acquire(() -> 1, 0)).isTrue();
        assertThat(p.acquire(() -> 1, 50)).isFalse();
        p.release();
        assertThat(p.acquire(() -> 1, 0)).isTrue();
        DbQueryRunner.ConnectionTest busy = runBusyTest();
        assertThat(busy.failureReason()).isEqualTo("DB_BUSY");
        release.countDown();
        t.join(5_000);
        assertThat(svc.podInUse()).isZero();
    }

    /** Pod izni dolu iken bağlantı denemesi kısa beklemeden sonra DB_BUSY döner. */
    private DbQueryRunner.ConnectionTest runBusyTest() {
        DbConnectionProfile unsaved = new DbConnectionProfile();
        unsaved.setDbType("POSTGRESQL");
        unsaved.setHost("h");
        unsaved.setPort(5432);
        unsaved.setDatabaseName("app");
        unsaved.setUsername("u");
        long t0 = System.nanoTime();
        DbQueryRunner.ConnectionTest r = svc.testConnection(unsaved, "pw");
        assertThat((System.nanoTime() - t0) / 1_000_000).isLessThan(3_000);
        return r;
    }

    @Test
    void networkClassCodes() {
        assertThat(DbQueryCheckerService.isNetworkClass("DB_UNREACHABLE")).isTrue();
        assertThat(DbQueryCheckerService.isNetworkClass("DB_DNS")).isTrue();
        assertThat(DbQueryCheckerService.isNetworkClass("DB_TIMEOUT")).isTrue();
        for (String c : List.of("DB_AUTH", "DB_SQL_ERROR", "DB_DRIVER", "DB_BLOCKED", "DB_GUARD_REJECTED", "DB_BUSY"))
            assertThat(DbQueryCheckerService.isNetworkClass(c)).as(c).isFalse();
        assertThat(DbQueryCheckerService.isNetworkClass(null)).isFalse();
    }

    @Test
    void parseTimeoutIsSkippedNotFailed() {
        DbQuerySqlGuard.Result timeout = new DbQuerySqlGuard.Result(false, List.of(new DbQuerySqlGuard.Violation(
                DbQuerySqlGuard.SQL_UNPARSABLE, DbQuerySqlGuard.TOKEN_TIMEOUT, "x", null, null)), List.of());
        assertThat(DbQuerySqlGuard.isTimeout(timeout)).isTrue();
        assertThat(DbQuerySqlGuard.isTimeout(DbQuerySqlGuard.check("SELECT 1;DROP", DbType.POSTGRESQL))).isFalse();
    }

    @Test
    void connectionTestHealthOnlyWithoutOverride() {
        when(runner.test(any())).thenReturn(new DbQueryRunner.ConnectionTest(true, 12, "PostgreSQL 16", null, null));
        assertThat(svc.testConnection(profile, null).ok()).isTrue();
        verify(profiles).recordTest(eq(7L), anyString(), eq(true), eq(12), isNull(), eq("PostgreSQL 16"));
        svc.testConnection(profile, "yeni-parola");
        verify(profiles, org.mockito.Mockito.times(1)).recordTest(anyLong(), anyString(), anyBoolean(), anyInt(), any(), any());
        ArgumentCaptor<DbConnectionSpec> spec = ArgumentCaptor.forClass(DbConnectionSpec.class);
        verify(runner, org.mockito.Mockito.times(2)).test(spec.capture());
        assertThat(spec.getAllValues().get(0).password()).isEqualTo("secret");
        assertThat(spec.getAllValues().get(1).password()).isEqualTo("yeni-parola");
        assertThat(spec.getAllValues().get(1).toString()).doesNotContain("yeni-parola");
    }

    @Test
    void planVerdicts() {
        when(runner.plan(any(), anyString(), anyInt())).thenReturn(new DbQueryRunner.PlanResult(true, true, 2e6, 10d, null));
        assertThat(svc.planCheck(profile, "SELECT * FROM t").verdict()).isEqualTo("WARN");
        when(settings.getString(eq(DbQueryCheckerService.KEY_PLAN_MODE), any())).thenReturn("BLOCK");
        assertThat(svc.planCheck(profile, "SELECT * FROM t").verdict()).isEqualTo("BLOCK");
        when(runner.plan(any(), anyString(), anyInt())).thenReturn(new DbQueryRunner.PlanResult(true, true, 10d, 10d, null));
        assertThat(svc.planCheck(profile, "SELECT * FROM t").verdict()).isEqualTo("OK");
        when(runner.plan(any(), anyString(), anyInt())).thenReturn(new DbQueryRunner.PlanResult(true, false, null, null, "x"));
        assertThat(svc.planCheck(profile, "SELECT * FROM t").verdict()).isEqualTo("UNKNOWN");
        // reddedilen sorgunun planı okunmaz
        assertThat(svc.planCheck(profile, "DELETE FROM t").verdict()).isEqualTo("UNKNOWN");
        when(settings.getString(eq(DbQueryCheckerService.KEY_PLAN_MODE), any())).thenReturn("OFF");
        assertThat(svc.planCheck(profile, "SELECT * FROM t").verdict()).isEqualTo("OK");
        profile.setDbType("ORACLE");
        assertThat(svc.planCheck(profile, "SELECT 1 FROM dual").supported()).isFalse();
        verify(runner, org.mockito.Mockito.times(4)).plan(any(), anyString(), anyInt());
    }
}
