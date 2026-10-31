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

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * r177 çalıştırma akışı: değişim tabanı yalnız aynı sorgu + aynı ölçüm tanımıyla (yoksa {@code baseline_reset}), eski
 * (parmak izsiz) satırlar uyumlu, mevsimsel taban, UNKNOWN durumu, tazelik için veritabanı saati, yazabilen hesap kipi
 * (BLOCK oturum denetimi + 10 dk salt-okur belleği), DBA izi uygulama adı, "yalnız seçilen kolonlar" mail kipi.
 */
class DbQueryCheckerR177Test {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final String CHANGE = "[{\"id\":\"c\",\"kind\":\"CHANGE\",\"operator\":\"GT\",\"warning\":10,"
            + "\"change\":{\"of\":\"ROW_COUNT\",\"mode\":\"PERCENT\",\"direction\":\"INCREASE\"}}]";

    private DbConnectionProfileRepository profiles;
    private DbQueryCheckRepository checks;
    private DbQueryRunner runner;
    private AppSettingsService settings;
    private DbQueryCheckerService svc;
    private DbConnectionProfile profile;

    @BeforeEach
    void setUp() {
        DbQuerySqlGuard.clearCache();
        profiles = mock(DbConnectionProfileRepository.class);
        checks = mock(DbQueryCheckRepository.class);
        SecretCipher cipher = mock(SecretCipher.class);
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

    private static DbQueryMonitor monitor(String sql, String rules) {
        DbQueryMonitor m = new DbQueryMonitor();
        m.setId(11L);
        m.setTeamId(3L);
        m.setProfileId(7L);
        m.setSqlText(sql);
        m.setRulesJson(rules);
        return m;
    }

    private static DbQueryRunner.QueryResult rows(int n) {
        List<List<Object>> r = new ArrayList<>();
        for (int i = 0; i < n; i++) r.add(Arrays.asList((Object) i));
        return new DbQueryRunner.QueryResult(true, 3, n, false, List.of(new DbQueryRunner.Column("a", "INT")), r, false,
                null, null, "PostgreSQL 16");
    }

    private static DbQueryCheck prev(String sqlHash, String ruleResultsJson) {
        DbQueryCheck c = new DbQueryCheck();
        c.setId(5L);
        c.setSqlHash(sqlHash);
        c.setRuleResultsJson(ruleResultsJson);
        c.setOk(true);
        return c;
    }

    private DbQueryCheckerService.Result runWith(DbQueryMonitor m, DbQueryRunner.QueryResult qr) {
        when(runner.run(any(), anyString(), anyInt(), anyInt(), any(DbQueryRunner.RunOptions.class))).thenReturn(qr);
        return svc.check(m, false);
    }

    private static String fp(String rulesJson) {
        return DbQueryRules.fingerprint(DbQueryRules.fromJson(rulesJson).get(0));
    }

    // ── Değişim tabanı ───────────────────────────────────────────────────────

    @Test
    void sameQueryAndDefinitionUsesTheBaseline() {
        DbQueryMonitor m = monitor("SELECT a FROM t", CHANGE);
        String hash = DbQueryCheckerService.sqlHash("SELECT a FROM t");
        when(checks.findRecentScheduledOk(11L)).thenReturn(List.of(prev(hash,
                "[{\"id\":\"c\",\"kind\":\"CHANGE\",\"measure\":2,\"fingerprint\":\"" + fp(CHANGE) + "\"}]")));
        DbQueryCheckerService.Result r = runWith(m, rows(3));
        assertThat(r.status()).isEqualTo("BREACH");
        assertThat(r.ruleResults().get(0).compared()).isEqualTo(50.0);
        assertThat(r.ruleResults().get(0).fingerprint()).isEqualTo(fp(CHANGE));
    }

    @Test
    void changedQueryResetsTheBaselineInsteadOfAFakeChange() {
        DbQueryMonitor m = monitor("SELECT a FROM t WHERE b = 1", CHANGE);
        when(checks.findRecentScheduledOk(11L)).thenReturn(List.of(prev("0000000000000000",
                "[{\"id\":\"c\",\"kind\":\"CHANGE\",\"measure\":2,\"fingerprint\":\"" + fp(CHANGE) + "\"}]")));
        DbQueryCheckerService.Result r = runWith(m, rows(3_000));
        assertThat(r.ruleResults().get(0).note()).isEqualTo(DbQueryEvaluator.NOTE_BASELINE_RESET);
        assertThat(r.ruleResults().get(0).evaluated()).isFalse();
        assertThat(r.status()).isEqualTo("UNKNOWN");   // taban kuruluyor — asla yeşil "OK" değil
        assertThat(r.level()).isEqualTo("NONE");
    }

    @Test
    void changedMeasureDefinitionResetsTheBaseline() {
        String colChange = "[{\"id\":\"c\",\"kind\":\"CHANGE\",\"column\":\"tutar\",\"aggregate\":\"SUM\",\"operator\":\"GT\","
                + "\"warning\":10,\"change\":{\"of\":\"COLUMN\",\"mode\":\"PERCENT\",\"direction\":\"INCREASE\"}}]";
        String oldDef = "[{\"id\":\"c\",\"kind\":\"CHANGE\",\"column\":\"adet\",\"aggregate\":\"SUM\",\"operator\":\"GT\","
                + "\"warning\":10,\"change\":{\"of\":\"COLUMN\",\"mode\":\"PERCENT\",\"direction\":\"INCREASE\"}}]";
        DbQueryMonitor m = monitor("SELECT tutar FROM t", colChange);
        String hash = DbQueryCheckerService.sqlHash("SELECT tutar FROM t");
        when(checks.findRecentScheduledOk(11L)).thenReturn(List.of(prev(hash,
                "[{\"id\":\"c\",\"kind\":\"CHANGE\",\"measure\":2,\"fingerprint\":\"" + fp(oldDef) + "\"}]")));
        DbQueryRunner.QueryResult qr = new DbQueryRunner.QueryResult(true, 3, 1, false,
                List.of(new DbQueryRunner.Column("tutar", "NUMERIC")), List.of(Arrays.asList((Object) 900)), false, null,
                null, "PG");
        DbQueryCheckerService.Result r = runWith(m, qr);
        assertThat(r.ruleResults().get(0).note()).isEqualTo(DbQueryEvaluator.NOTE_BASELINE_RESET);
        assertThat(r.ruleResults().get(0).measure()).isEqualTo(900.0);   // yeni taban bu çalıştırma
        assertThat(r.status()).isEqualTo("UNKNOWN");
    }

    @Test
    void legacyRowWithoutFingerprintStillCountsAsBaseline() {
        DbQueryMonitor m = monitor("SELECT a FROM t", CHANGE);
        String hash = DbQueryCheckerService.sqlHash("SELECT a FROM t");
        when(checks.findRecentScheduledOk(11L)).thenReturn(List.of(prev(hash, "[{\"id\":\"c\",\"kind\":\"CHANGE\",\"measure\":2}]")));
        assertThat(runWith(m, rows(3)).ruleResults().get(0).compared()).isEqualTo(50.0);
        // eski satırda sorgu özeti de yok (bu özellik öncesi) → uyumlu sayılır
        when(checks.findRecentScheduledOk(11L)).thenReturn(List.of(prev(null, "[{\"id\":\"c\",\"measure\":2}]")));
        assertThat(runWith(m, rows(3)).status()).isEqualTo("BREACH");
    }

    @Test
    void seasonalBaselineIsTheClosestRowOfYesterday() {
        String rules = "[{\"id\":\"y\",\"kind\":\"CHANGE\",\"operator\":\"GT\",\"warning\":50,"
                + "\"change\":{\"of\":\"ROW_COUNT\",\"mode\":\"PERCENT\",\"direction\":\"INCREASE\",\"baseline\":\"same_time_yesterday\"}}]";
        DbQueryMonitor m = monitor("SELECT a FROM t", rules);
        m.setIntervalSeconds(600);   // tolerans = min(5 dk, 30 dk)
        String hash = DbQueryCheckerService.sqlHash("SELECT a FROM t");
        Instant target = Instant.now().minusSeconds(86_400);
        DbQueryCheck far = prev(hash, "[{\"id\":\"y\",\"kind\":\"CHANGE\",\"measure\":1}]");
        far.setCheckedAt(ISO.format(target.minusSeconds(250)));
        DbQueryCheck near = prev(hash, "[{\"id\":\"y\",\"kind\":\"CHANGE\",\"measure\":10}]");
        near.setCheckedAt(ISO.format(target.plusSeconds(20)));
        DbQueryCheck otherQuery = prev("ffffffffffffffff", "[{\"id\":\"y\",\"kind\":\"CHANGE\",\"measure\":100}]");
        otherQuery.setCheckedAt(ISO.format(target));
        ArgumentCaptor<String> from = ArgumentCaptor.forClass(String.class), to = ArgumentCaptor.forClass(String.class);
        when(checks.findScheduledOkBetween(eq(11L), from.capture(), to.capture())).thenReturn(List.of(far, near, otherQuery));
        DbQueryCheckerService.Result r = runWith(m, rows(30));
        assertThat(r.status()).isEqualTo("BREACH");
        assertThat(r.ruleResults().get(0).compared()).isEqualTo(200.0);   // 10 → 30
        // ± 5 dk pencere okunur, önceki-çalıştırma sorgusu hiç yapılmaz
        long span = Instant.parse(to.getValue() + "Z").getEpochSecond() - Instant.parse(from.getValue() + "Z").getEpochSecond();
        assertThat(span).isBetween(599L, 601L);
        verify(checks, never()).findRecentScheduledOk(anyLong());
    }

    @Test
    void noSeasonalRowMeansWaitingNotOk() {
        String rules = "[{\"id\":\"w\",\"kind\":\"CHANGE\",\"operator\":\"GT\",\"warning\":50,"
                + "\"change\":{\"of\":\"ROW_COUNT\",\"mode\":\"PERCENT\",\"direction\":\"ANY\",\"baseline\":\"same_time_last_week\"}}]";
        when(checks.findScheduledOkBetween(eq(11L), anyString(), anyString())).thenReturn(List.of());
        DbQueryCheckerService.Result r = runWith(monitor("SELECT a FROM t", rules), rows(30));
        assertThat(r.ruleResults().get(0).note()).isEqualTo(DbQueryEvaluator.NOTE_NO_SEASONAL_BASELINE);
        assertThat(r.status()).isEqualTo("UNKNOWN");
        // okuma hatası: prev_unreadable (kanıt eksikliği)
        when(checks.findScheduledOkBetween(eq(11L), anyString(), anyString())).thenThrow(new org.springframework.dao.QueryTimeoutException("x"));
        assertThat(runWith(monitor("SELECT a FROM t", rules), rows(30)).ruleResults().get(0).note())
                .isEqualTo(DbQueryEvaluator.NOTE_PREVIOUS_UNREADABLE);
    }

    // ── Tazelik, UNKNOWN ─────────────────────────────────────────────────────

    @Test
    void freshnessAsksForTheDatabaseClockOnlyWhenNeeded() {
        ArgumentCaptor<DbQueryRunner.RunOptions> opts = ArgumentCaptor.forClass(DbQueryRunner.RunOptions.class);
        when(runner.run(any(), anyString(), anyInt(), anyInt(), opts.capture())).thenReturn(rows(1));
        svc.check(monitor("SELECT a FROM t", "[{\"id\":\"f\",\"kind\":\"FRESHNESS\",\"column\":\"a\",\"operator\":\"GT\",\"warning\":60}]"), false);
        svc.check(monitor("SELECT a FROM t", "[{\"id\":\"r\",\"kind\":\"ROW_COUNT\",\"operator\":\"GT\",\"warning\":60}]"), false);
        assertThat(opts.getAllValues()).extracting(DbQueryRunner.RunOptions::dbClock).containsExactly(true, false);
    }

    @Test
    void columnMissingIsUnknownNotOk() {
        DbQueryCheckerService.Result r = runWith(monitor("SELECT a FROM t",
                "[{\"id\":\"v\",\"kind\":\"COLUMN_VALUE\",\"column\":\"yok\",\"operator\":\"GT\",\"warning\":1}]"), rows(2));
        assertThat(r.status()).isEqualTo(DbQueryCheckerService.STATUS_UNKNOWN);
        // kural yokken satır sayısı ihlali yok → OK
        assertThat(runWith(monitor("SELECT a FROM t", null), rows(2)).status()).isEqualTo("OK");
    }

    // ── DBA izi ──────────────────────────────────────────────────────────────

    @Test
    void savedMonitorRunsUnderItsOwnApplicationName() {
        ArgumentCaptor<DbConnectionSpec> spec = ArgumentCaptor.forClass(DbConnectionSpec.class);
        when(runner.run(spec.capture(), anyString(), anyInt(), anyInt(), any(DbQueryRunner.RunOptions.class))).thenReturn(rows(1));
        svc.check(monitor("SELECT 1", null), false);
        DbQueryMonitor draft = monitor("SELECT 1", null);
        draft.setId(null);
        svc.test(draft, null);
        assertThat(spec.getAllValues().get(0).safeAppName()).isEqualTo("SiteMonitor dbq#11");
        assertThat(spec.getAllValues().get(1).safeAppName()).isEqualTo("SiteMonitor dbq-test");
    }

    // ── Yazabilen hesap ──────────────────────────────────────────────────────

    @Test
    void writableModeDefaultsToWarnAndBlockChecksInSession() {
        assertThat(svc.writableMode()).isEqualTo("WARN");
        when(settings.getString(eq(DbQueryCheckerService.KEY_WRITABLE_MODE), any())).thenReturn("nonsense");
        assertThat(svc.writableMode()).isEqualTo("WARN");
        ArgumentCaptor<DbQueryRunner.RunOptions> opts = ArgumentCaptor.forClass(DbQueryRunner.RunOptions.class);
        DbQueryRunner.QueryResult ok = new DbQueryRunner.QueryResult(true, 3, 1, false, List.of(new DbQueryRunner.Column("a", "INT")),
                List.of(Arrays.asList((Object) 1)), false, null, null, "PG", null,
                new DbAccountPrivileges.Result(true, true, List.of()));
        when(runner.run(any(), anyString(), anyInt(), anyInt(), opts.capture())).thenReturn(ok);
        svc.check(monitor("SELECT 1", null), false);                 // WARN: oturumda denetim yok
        when(settings.getString(eq(DbQueryCheckerService.KEY_WRITABLE_MODE), any())).thenReturn("block");
        svc.check(monitor("SELECT 1", null), false);                 // BLOCK: denetlenir, salt-okur doğrulandı
        svc.check(monitor("SELECT 1", null), false);                 // 10 dk içinde yeniden denetlenmez
        profile.setUpdatedAt("2026-10-31T10:00:00");                 // bağlantı düzenlendi → yeniden denetlenir
        svc.check(monitor("SELECT 1", null), false);
        assertThat(opts.getAllValues()).extracting(DbQueryRunner.RunOptions::refuseWritable)
                .containsExactly(false, true, false, true);
    }

    @Test
    void writableAccountUnderBlockFailsWithoutRunningTheQuery() {
        when(settings.getString(eq(DbQueryCheckerService.KEY_WRITABLE_MODE), any())).thenReturn("BLOCK");
        DbQueryRunner.QueryResult refused = new DbQueryRunner.QueryResult(false, 4, 0, false, List.of(), List.of(), false,
                "hesap yazabiliyor", DbQueryErrors.DB_ACCOUNT_WRITABLE, "PG", null,
                new DbAccountPrivileges.Result(true, false, List.of(new DbAccountPrivileges.Finding(DbAccountPrivileges.CAN_WRITE_TABLES, "x"))));
        DbQueryCheckerService.Result r = runWith(monitor("SELECT 1", null), refused);
        assertThat(r.status()).isEqualTo("FAIL");
        assertThat(r.failureReason()).isEqualTo("DB_ACCOUNT_WRITABLE");
        // yazabilen sonuç belleğe "salt-okur" yazmaz: sonraki çalıştırma da denetler
        ArgumentCaptor<DbQueryRunner.RunOptions> opts = ArgumentCaptor.forClass(DbQueryRunner.RunOptions.class);
        when(runner.run(any(), anyString(), anyInt(), anyInt(), opts.capture())).thenReturn(refused);
        svc.check(monitor("SELECT 1", null), false);
        assertThat(opts.getValue().refuseWritable()).isTrue();
    }

    @Test
    void connectionTestChecksPrivilegesUnlessOffAndStoresTheSummary() {
        DbAccountPrivileges.Result writable = new DbAccountPrivileges.Result(true, false,
                List.of(new DbAccountPrivileges.Finding(DbAccountPrivileges.SUPERUSER, "rolsuper")));
        when(runner.test(any(), anyBoolean())).thenReturn(new DbQueryRunner.ConnectionTest(true, 9, "PG 16", null, null, writable));
        DbQueryRunner.ConnectionTest t = svc.testConnection(profile, null);
        assertThat(t.privileges().readOnly()).isFalse();
        ArgumentCaptor<DbConnectionSpec> spec = ArgumentCaptor.forClass(DbConnectionSpec.class);
        verify(runner).test(spec.capture(), eq(true));
        assertThat(spec.getValue().safeAppName()).isEqualTo("SiteMonitor dbq-test");
        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(profiles).recordPrivileges(eq(7L), json.capture());
        assertThat(DbAccountPrivileges.storedWritable(json.getValue())).isTrue();
        // parola geçersiz kılmalı deneme sağlığa / özete yazmaz
        svc.testConnection(profile, "yeni");
        verify(profiles, org.mockito.Mockito.times(1)).recordPrivileges(anyLong(), anyString());
        // OFF: denetim yok
        when(settings.getString(eq(DbQueryCheckerService.KEY_WRITABLE_MODE), any())).thenReturn("OFF");
        when(runner.test(any(), eq(false))).thenReturn(new DbQueryRunner.ConnectionTest(true, 9, "PG 16", null, null));
        svc.testConnection(profile, null);
        verify(runner).test(any(), eq(false));
        // başarısız bağlantı eski özeti silmez
        when(runner.test(any(), anyBoolean())).thenReturn(new DbQueryRunner.ConnectionTest(false, 9, null, "x", "DB_AUTH"));
        when(settings.getString(eq(DbQueryCheckerService.KEY_WRITABLE_MODE), any())).thenReturn("WARN");
        svc.testConnection(profile, null);
        verify(profiles, org.mockito.Mockito.times(1)).recordPrivileges(anyLong(), anyString());
    }

    // ── Mail kolonları ───────────────────────────────────────────────────────

    @Test
    void selectedModeNeverFallsBackToTheFirstColumns() {
        DbQueryMonitor m = monitor("SELECT a FROM t", null);
        m.setMailColumnsMode(DbQueryMonitor.MAIL_COLUMNS_SELECTED);
        DbQueryRunner.QueryResult qr = new DbQueryRunner.QueryResult(true, 3, 1, false,
                List.of(new DbQueryRunner.Column("tckn", "VARCHAR"), new DbQueryRunner.Column("adet", "INT")),
                List.of(Arrays.asList((Object) "12345678901", 4)), false, null, null, "PG");
        DbQueryCheckerService.Result r = runWith(m, qr);
        assertThat(r.mailColumns()).isEmpty();
        assertThat(r.mailRows()).isEmpty();
        m.setMailColumnsJson("[\"adet\"]");
        DbQueryCheckerService.Result r2 = runWith(m, qr);
        assertThat(r2.mailColumns()).containsExactly("adet");
        assertThat(r2.mailRows()).containsExactly(List.of("4"));
        // FIRST_N (eski kayıtlar, null) bugünkü davranış: seçim yoksa ilk kolonlar
        m.setMailColumnsMode(null);
        m.setMailColumnsJson(null);
        assertThat(runWith(m, qr).mailColumns()).containsExactly("tckn", "adet");
    }

    @Test
    void planOffIsNeverOk() {
        when(settings.getString(eq(DbQueryCheckerService.KEY_PLAN_MODE), any())).thenReturn("OFF");
        DbQueryCheckerService.PlanVerdict v = svc.planCheck(profile, "SELECT 1");
        assertThat(v.verdict()).isEqualTo("OFF");
        assertThat(v.message()).isNotBlank();
        verify(runner, never()).plan(any(), anyString(), anyInt());
    }
}
