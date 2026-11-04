package com.sitemonitor.service;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.DbConnectionProfile;
import com.sitemonitor.model.DbQueryCheck;
import com.sitemonitor.model.DbQueryMonitor;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.DbConnectionProfileRepository;
import com.sitemonitor.repository.DbQueryCheckRepository;
import com.sitemonitor.repository.DbQueryMonitorRepository;
import com.sitemonitor.service.dbquery.DbQueryAlertRowsService;
import com.sitemonitor.service.dbquery.DbQueryCheckerService;
import com.sitemonitor.service.dbquery.DbQueryEvaluator;
import com.sitemonitor.service.dbquery.DbQueryRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Akıllı kurallar (dbq2 D) gerçek alarm hattında: zamanlayıcının taraması + GERÇEK çalıştırma motoru (geçmiş H2'deki kontrol
 * satırlarından {@code DbQueryHistory} ile okunur) + GERÇEK MonitoringOutageService (teyit hemen). Olağan dışı değer: taban
 * kurulurken alarm yok; sıçrama teyitle (zinciri başlatan tabanla) alarm AÇAR; değer olağana dönünce kurtarmayla KAPANIR.
 * Donmuş değer: dizi uyarıya ulaşınca alarm açılır.
 */
class SchedulerDbQuerySmartRulesTest {

    static final Instant T0 = Instant.parse("2026-11-02T06:00:00Z");

    SchedulerService scheduler;
    DbQueryCheckerService real;
    DbQueryMonitorRepository monitorRepo;
    DbQueryCheckRepository checkRepo;
    AlertEventRepository alerts;
    EscalationService esc;
    JdbcTemplate h2;
    final AtomicLong clock = new AtomicLong(T0.toEpochMilli());
    final List<DbQueryCheck> saved = new ArrayList<>();
    final List<AlertEvent> open = new ArrayList<>();
    final List<Object> values = new ArrayList<>();
    final AtomicInteger next = new AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        Constructor<?> c = Arrays.stream(SchedulerService.class.getConstructors())
                .max(Comparator.comparingInt(Constructor::getParameterCount)).orElseThrow();
        Class<?>[] pt = c.getParameterTypes();
        Object[] args = new Object[pt.length];
        for (int i = 0; i < pt.length; i++) args[i] = mock(pt[i]);
        scheduler = (SchedulerService) c.newInstance(args);
        AppSettingsService settings = (AppSettingsService) ReflectionTestUtils.getField(scheduler, "appSettings");
        SchedulerDbQueryReviewFixTest.defaults(settings);
        ReflectionTestUtils.setField(scheduler, "orphanCleanupIntervalMs", 300_000L);
        ReflectionTestUtils.setField(scheduler, "scheduleClock", (java.util.function.LongSupplier) clock::get);
        ReflectionTestUtils.setField(scheduler, "dbQueryScheduling",
                SchedulerDbQueryReviewFixTest.fake(300, new AtomicReference<>(true)));

        // Kontrol satırları: depo taklidi (bellekte) + akıllı kural geçmişinin okunduğu H2 aynası (aynı satırlar)
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:dbq2smart" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", "sa", "");
        h2 = new JdbcTemplate(ds);
        h2.execute("CREATE TABLE dbquery_checks (id BIGINT PRIMARY KEY, monitor_id BIGINT, checked_at VARCHAR(30), "
                + "ok BOOLEAN, manual BOOLEAN, recheck BOOLEAN, sql_hash VARCHAR(16), rule_results_json VARCHAR(100000))");
        monitorRepo = mock(DbQueryMonitorRepository.class);
        lenient().when(monitorRepo.updateNextRunAt(anyLong(), any())).thenReturn(1);
        checkRepo = mock(DbQueryCheckRepository.class);
        lenient().when(checkRepo.save(any(DbQueryCheck.class))).thenAnswer(i -> {
            DbQueryCheck x = i.getArgument(0);
            x.setId(100L + saved.size());
            saved.add(x);
            h2.update("INSERT INTO dbquery_checks VALUES (?, ?, ?, ?, ?, ?, ?, ?)", x.getId(), x.getMonitorId(),
                    x.getCheckedAt(), x.getOk(), x.getManual(), x.getRecheck(), x.getSqlHash(), x.getRuleResultsJson());
            return x;
        });
        lenient().when(checkRepo.findLatestScheduled(anyLong())).thenAnswer(i -> saved.stream()
                .filter(x -> x.getMonitorId() == (long) i.getArgument(0) && !Boolean.TRUE.equals(x.getManual())
                        && !Boolean.TRUE.equals(x.getRecheck()))
                .max(Comparator.comparing(DbQueryCheck::getCheckedAt).thenComparing(DbQueryCheck::getId)));

        DbConnectionProfileRepository profiles = mock(DbConnectionProfileRepository.class);
        DbConnectionProfile p = new DbConnectionProfile();
        p.setId(3L);
        p.setTeamId(7L);
        p.setDbType("POSTGRESQL");
        p.setHost("db.example");
        p.setPort(5432);
        p.setDatabaseName("app");
        p.setUsername("u");
        when(profiles.findById(3L)).thenReturn(Optional.of(p));
        DbQueryRunner runner = mock(DbQueryRunner.class);
        when(runner.run(any(), anyString(), anyInt(), anyInt(), any(DbQueryRunner.RunOptions.class))).thenAnswer(i -> {
            Object v = values.get(Math.min(next.getAndIncrement(), values.size() - 1));
            List<List<Object>> rows = new ArrayList<>();
            rows.add(Arrays.asList(v));
            return new DbQueryRunner.QueryResult(true, 5, 1, false, List.of(new DbQueryRunner.Column("adet", "NUMERIC")),
                    rows, false, null, null, "PostgreSQL 16");
        });
        AppSettingsService es = mock(AppSettingsService.class);
        SchedulerDbQueryReviewFixTest.defaults(es);
        real = new DbQueryCheckerService(profiles, checkRepo, mock(SecretCipher.class), runner, es);
        ReflectionTestUtils.invokeMethod(real, "setJdbc", h2);
        ReflectionTestUtils.setField(scheduler, "dbQueryCheckerService", real);
        ReflectionTestUtils.setField(scheduler, "dbQueryMonitorRepo", monitorRepo);
        ReflectionTestUtils.setField(scheduler, "dbQueryCheckRepo", checkRepo);
        ReflectionTestUtils.setField(scheduler, "dbQueryAlertRows", mock(DbQueryAlertRowsService.class));

        alerts = mock(AlertEventRepository.class);
        when(alerts.findOpenByDomainIn(anyCollection())).thenAnswer(i -> List.copyOf(open));
        esc = mock(EscalationService.class);
        ReflectionTestUtils.setField(scheduler, "monitoringOutageService", SchedulerDbQueryReviewFixTest.realMos(alerts, esc,
                settings, mock(com.sitemonitor.repository.NetworkOutageEventRepository.class)));
    }

    @AfterEach
    void tearDown() {
        real.shutdown();
        h2.execute("SHUTDOWN");
    }

    DbQueryMonitor monitor(long id, String name, String rules) {
        DbQueryMonitor m = new DbQueryMonitor();
        m.setId(id);
        m.setName(name);
        m.setActive(true);
        m.setTeamId(7L);
        m.setProfileId(3L);
        m.setSqlText("SELECT adet FROM kuyruk");
        m.setRulesJson(rules);
        m.setIntervalSeconds(300);
        when(monitorRepo.findByActiveTrue()).thenReturn(List.of(m));
        return m;
    }

    void sweep(DbQueryMonitor m) {
        m.setNextRunAt(SchedulerDbQueryReviewFixTest.iso(Instant.ofEpochMilli(clock.get()).minusSeconds(1)));
        ReflectionTestUtils.invokeMethod(scheduler, "runDbQueryChecksLocked");
        clock.addAndGet(400_000);
    }

    AlertEvent openAlarm(String name, String type) {
        AlertEvent ev = new AlertEvent();
        ev.setId(900L);
        ev.setDomain(name);
        ev.setAlertType(type);
        ev.setTeamId(7L);
        ev.setContextJson("{\"monitor_id\":11}");
        ev.setResolved(false);
        open.add(ev);
        return ev;
    }

    @Test
    @DisplayName("olağan dışı değer: taban kurulurken alarm yok; sıçrama teyitle AÇAR (teyit tabanı ihlal satırını dışlar); olağana dönünce KAPANIR")
    void anomalyOpensAndCloses() {
        String name = "Kuyruk anomalisi";
        DbQueryMonitor m = monitor(11L, name, "[{\"id\":\"r\",\"label\":\"Bekleyen kuyruk\",\"kind\":\"ANOMALY\","
                + "\"column\":\"adet\",\"window\":10,\"min_samples\":5}]");
        for (int v : new int[]{100, 102, 98, 101, 99, 100}) values.add(BigDecimal.valueOf(v));
        values.addAll(List.of(BigDecimal.valueOf(130), BigDecimal.valueOf(131), BigDecimal.valueOf(100)));

        for (int i = 0; i < 6; i++) sweep(m);   // 5 taban kuruluyor + 1 olağan
        verify(esc, never()).processConfirmedOutage(anyString(), anyString(), anyString(), any());
        assertThat(saved).hasSize(6);
        assertThat(DbQueryEvaluator.fromJson(saved.get(0).getRuleResultsJson()).get(0).note())
                .isEqualTo(DbQueryEvaluator.NOTE_BUILDING_BASELINE);
        assertThat(saved).allSatisfy(x -> assertThat(x.getStatus()).as("taban kuruluyor UNKNOWN değil").isEqualTo("OK"));

        sweep(m);   // 130 → ihlal; teyit (131) zinciri başlatan satırdan ÖNCEKİ tabanla → ihlal → alarm
        verify(esc, times(1)).processConfirmedOutage(eq(name), eq(EscalationService.TYPE_DBQUERY_THRESHOLD), anyString(), any());
        DbQueryCheck spike = saved.get(6), confirm = saved.get(7);
        assertThat(spike.getStatus()).isEqualTo("BREACH");
        assertThat(confirm.getRecheck()).isTrue();
        assertThat(confirm.getStatus()).isEqualTo("BREACH");
        DbQueryEvaluator.RuleResult cr = DbQueryEvaluator.fromJson(confirm.getRuleResultsJson()).get(0);
        assertThat(cr.stats().samples()).as("teyit tabanı: 130 satırı hariç 6 örnek").isEqualTo(6);
        assertThat(cr.stats().mean()).isEqualTo(100.0);
        assertThat(cr.actual()).contains("olağan");

        openAlarm(name, EscalationService.TYPE_DBQUERY_THRESHOLD);
        sweep(m);   // 100 → olağan (taban 130'u içerir ama sapma küçük) → kurtarma → kapanır
        verify(esc, times(1)).resolveMonitoringAlertsForDomain(name, EscalationService.TYPE_DBQUERY_THRESHOLD);
        DbQueryCheck after = saved.stream().filter(x -> !Boolean.TRUE.equals(x.getRecheck())).reduce((a, b) -> b).orElseThrow();
        assertThat(DbQueryEvaluator.fromJson(after.getRuleResultsJson()).get(0).stats().samples())
                .as("sonraki zamanlanmış taban teyit satırını içermez").isEqualTo(7);
    }

    @Test
    @DisplayName("donmuş değer: 42 üç çalıştırmadır aynı → teyitle uyarı alarmı; değer değişince kapanır")
    void frozenOpensAndCloses() {
        String name = "Takılı sayaç";
        DbQueryMonitor m = monitor(11L, name, "[{\"id\":\"f\",\"label\":\"Sayaç\",\"kind\":\"FROZEN\",\"column\":\"adet\","
                + "\"runs_warning\":3}]");
        values.addAll(List.of(42, 42, 42, 42, 43));
        sweep(m);
        sweep(m);
        verify(esc, never()).processConfirmedOutage(anyString(), anyString(), anyString(), any());
        sweep(m);   // üçüncü 42 → uyarı; teyit (42) zinciri başlatan satırdan önceki iki 42 + kendisi → uyarı → alarm
        verify(esc, times(1)).processConfirmedOutage(eq(name), eq(EscalationService.TYPE_DBQUERY_THRESHOLD), anyString(), any());
        openAlarm(name, EscalationService.TYPE_DBQUERY_THRESHOLD);
        sweep(m);   // 43 → değer değişti → kapanır
        verify(esc, times(1)).resolveMonitoringAlertsForDomain(name, EscalationService.TYPE_DBQUERY_THRESHOLD);
    }

    @Test
    @DisplayName("geçmiş okunamazsa: kanıt eksikliği — eşik kalemi yok (alarm ne açılır ne kanıtsız kapanır), satır UNKNOWN")
    void unreadableHistoryIsNotOk() {
        String name = "Okunamayan geçmiş";
        DbQueryMonitor m = monitor(11L, name, "[{\"id\":\"r\",\"kind\":\"ANOMALY\",\"column\":\"adet\",\"min_samples\":5}]");
        values.add(BigDecimal.valueOf(5));
        ReflectionTestUtils.invokeMethod(real, "setJdbc", (Object) null);
        openAlarm(name, EscalationService.TYPE_DBQUERY_THRESHOLD);
        sweep(m);
        assertThat(saved.get(0).getStatus()).isEqualTo("UNKNOWN");
        verify(esc, never()).resolveMonitoringAlertsForDomain(name, EscalationService.TYPE_DBQUERY_THRESHOLD);
        verify(esc, never()).processConfirmedOutage(anyString(), anyString(), anyString(), any());
    }

    static Map<String, Object> unused() { return new HashMap<>(); }
}
