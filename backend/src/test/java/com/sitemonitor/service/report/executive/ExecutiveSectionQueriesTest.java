package com.sitemonitor.service.report.executive;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.TlsGradeChange;
import com.sitemonitor.model.TlsProfile;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.TlsGradeChangeRepository;
import com.sitemonitor.repository.TlsGradeStatusRepository;
import com.sitemonitor.repository.TlsProfileRepository;
import com.sitemonitor.service.MonitoringOverviewService;
import com.sitemonitor.service.quality.DataQualityService;
import com.sitemonitor.service.quality.DataQualitySource;
import com.sitemonitor.service.tlsgrade.TlsGradeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Yönetici özetinin yeni bölümlerinin GERÇEK sorguları (H2, PostgreSQL kipi): TLS not düşüşlerinin ay penceresi (İstanbul
 * ayı → UTC damga, yarı açık, yalnız DROP), profil kapsaması ve veri kalitesinin ay sonu görüntüleri. TAKIM kapsamı
 * (2026-10-10): erişilebilirliğin izleme × İstanbul günü sorgusu, gürültünün bağlamlı izdüşümü ve koşu boyu paylaşımla
 * sabit sorgu sayısı (iki takım özeti = kurum ham sorguları BİR kez).
 */
@DataJpaTest
@org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase(
        replace = org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        // monitor_check_daily.day / dns_records.value — H2'de anahtar sözcük (PostgreSQL'de değil); erişilebilirlik
        // takım kapsamının günlük özet sorgusu bu tabloyu okur (DeletedRecordsPurgeTest ile aynı ayar)
        "spring.datasource.url=jdbc:h2:mem:execsections;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=VALUE,DAY"})
class ExecutiveSectionQueriesTest {

    @Autowired DataSource dataSource;
    @Autowired TlsProfileRepository profiles;
    @Autowired TlsGradeStatusRepository statuses;
    @Autowired TlsGradeChangeRepository changes;

    private TlsGradeChange change(long inv, String direction, String at) {
        TlsGradeChange c = new TlsGradeChange();
        c.setInventoryId(inv);
        c.setDomain("d" + inv + ".example.com");
        c.setFromGrade("A");
        c.setToGrade("B");
        c.setDirection(direction);
        c.setChangedAt(at);
        return changes.save(c);
    }

    @Test
    @DisplayName("TLS düşüşleri: Eylül (İstanbul) = [31.08 21:00Z, 30.09 21:00Z); sınır dahil/hariç, RISE/REFINE sayılmaz, liste sınırlı")
    void tlsDropsMonthWindow() {
        change(1, TlsGradeChange.DROP, "2026-08-31T20:59:59");   // Ağustos (TR 23:59:59)
        change(2, TlsGradeChange.DROP, "2026-08-31T21:00:00");   // Eylül'ün ilk anı (TR 00:00)
        change(3, TlsGradeChange.DROP, "2026-09-15T10:00:00");
        change(4, TlsGradeChange.DROP, "2026-09-30T20:59:59");   // Eylül'ün son anı
        change(5, TlsGradeChange.DROP, "2026-09-30T21:00:00");   // Ekim (TR 1 Ekim 00:00)
        change(6, TlsGradeChange.RISE, "2026-09-10T10:00:00");
        change(7, TlsGradeChange.REFINE, "2026-09-11T10:00:00");
        ExecutiveSummaryContext ctx = new ExecutiveSummaryContext(YearMonth.of(2026, 9),
                java.time.Instant.parse("2026-10-10T09:00:00Z"), 99.9, 30, List::of, Map::of, Map::of);
        TlsGradeService svc = new TlsGradeService(profiles, statuses, changes);

        TlsGradeService.DropWindow w = svc.dropsBetween(ctx.fromIso(), ctx.toIso(), 500);
        assertThat(w.total()).isEqualTo(3);
        assertThat(w.rows()).extracting(TlsGradeChange::getInventoryId).containsExactly(4L, 3L, 2L);

        TlsGradeService.DropWindow capped = svc.dropsBetween(ctx.fromIso(), ctx.toIso(), 2);
        assertThat(capped.total()).isEqualTo(3);
        assertThat(capped.rows()).hasSize(2);

        TlsGradeService.DropWindow aug = svc.dropsBetween("2026-07-31T21:00:00", "2026-08-31T21:00:00", 500);
        assertThat(aug.total()).isEqualTo(1);
        assertThat(aug.rows()).extracting(TlsGradeChange::getInventoryId).containsExactly(1L);
    }

    @Test
    @DisplayName("TLS profil kapsaması: verilen ağ alan adları; yoklanan / taranamayan / bekleyen")
    void tlsCoverageForDomains() {
        for (String[] p : new String[][]{ {"a.com", TlsProfile.STATUS_OK}, {"b.com", TlsProfile.STATUS_PARTIAL},
                {"c.com", TlsProfile.STATUS_FAILED}, {"other.com", TlsProfile.STATUS_OK} }) {
            TlsProfile t = new TlsProfile();
            t.setDomain(p[0]);
            t.setStatus(p[1]);
            t.setProbedAt("2026-10-10T06:00:00");
            profiles.save(t);
        }
        Map<String, Object> c = new TlsGradeService(profiles, statuses, changes)
                .coverageForDomains(Set.of("a.com", "b.com", "c.com", "d.com"));
        assertThat(c).containsEntry("endpoints", 4).containsEntry("ok", 1).containsEntry("partial", 1)
                .containsEntry("failed", 1).containsEntry("pending", 1);
    }

    @Test
    @DisplayName("veri kalitesi ay sonu görüntüleri: ayın ve önceki ayın SON günü (kurum satırı), o iki günün kova puanları")
    void dataQualityMonthEnds() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        String ins = "INSERT INTO data_quality_daily(snap_day, team_key, score, findings, items, created_at) VALUES (?, ?, ?, 0, 0, ?)";
        jdbc.update(ins, "2026-08-15", 0L, 70, "x");
        jdbc.update(ins, "2026-08-31", 0L, 72, "x");
        jdbc.update(ins, "2026-08-31", 5L, 60, "x");
        jdbc.update(ins, "2026-09-10", 0L, 75, "x");
        jdbc.update(ins, "2026-09-30", 0L, 80, "x");
        jdbc.update(ins, "2026-09-30", 5L, 66, "x");
        jdbc.update(ins, "2026-09-30", -1L, null, "x");
        jdbc.update(ins, "2026-10-01", 0L, 90, "x");                 // sonraki ay — Eylül'e girmez
        DataQualityService svc = new DataQualityService(mock(DataQualitySource.class), jdbc, 60_000);

        DataQualityService.MonthEnds sep = svc.monthEnds(LocalDate.of(2026, 9, 1));
        assertThat(sep.prevDay()).isEqualTo("2026-08-31");
        assertThat(sep.curDay()).isEqualTo("2026-09-30");
        assertThat(sep.prev()).containsEntry(0L, 72).containsEntry(5L, 60);
        assertThat(sep.cur()).containsEntry(0L, 80).containsEntry(5L, 66).doesNotContainKey(-1L);

        DataQualityService.MonthEnds oct = svc.monthEnds(LocalDate.of(2026, 10, 1));
        assertThat(oct.prevDay()).isEqualTo("2026-09-30");
        assertThat(oct.curDay()).isEqualTo("2026-10-01");
        assertThat(oct.cur()).containsEntry(0L, 90);

        DataQualityService.MonthEnds aug = svc.monthEnds(LocalDate.of(2026, 8, 1));
        assertThat(aug.prevDay()).isNull();
        assertThat(aug.curDay()).isEqualTo("2026-08-31");
        assertThat(aug.cur()).containsEntry(0L, 72);
        assertThat(aug.prev()).isEmpty();

        assertThat(svc.monthEnds(LocalDate.of(2026, 3, 1))).isEqualTo(DataQualityService.MonthEnds.NONE);
    }

    // ── Takım kapsamı (2026-10-10) ──────────────────────────────────────────────────────────────────────────────

    @Autowired AlertEventRepository alerts;

    /** Takım testlerinin envanteri: sy (SY=1), ug (SY=2, UG=1). */
    private static final Map<String, ExecutiveSummaryContext.InventoryRow> TEAM_INV = ExecTestSupport.invMap(
            ExecTestSupport.inv("sy.example.com", 1L, null, 1, null),
            ExecTestSupport.inv("ug.example.com", 2L, 1L, 2, null));

    private static void rollupTables(JdbcTemplate jdbc) {
        for (String t : List.of("monitor_check_hourly(monitor_type VARCHAR(16) NOT NULL, monitor_key VARCHAR(255) NOT NULL, "
                        + "hour_bucket VARCHAR(13) NOT NULL, total_checks BIGINT DEFAULT 0, up_checks BIGINT DEFAULT 0, "
                        + "avg_response_ms INTEGER, max_response_ms INTEGER, PRIMARY KEY (monitor_type, monitor_key, hour_bucket))",
                "monitor_check_daily(monitor_type VARCHAR(16) NOT NULL, monitor_key VARCHAR(255) NOT NULL, "
                        + "day VARCHAR(10) NOT NULL, total_checks BIGINT DEFAULT 0, up_checks BIGINT DEFAULT 0, "
                        + "avg_response_ms INTEGER, max_response_ms INTEGER, PRIMARY KEY (monitor_type, monitor_key, day))")) {
            jdbc.execute("CREATE TABLE IF NOT EXISTS " + t);
        }
    }

    /** Bölümün KENDİ çağırdığı JdbcTemplate sorguları (casusun iç çağrıları sayılmaz). */
    private static long sectionQueries(JdbcTemplate spy, String caller) {
        return mockingDetails(spy).getInvocations().stream()
                .filter(i -> i.getMethod().getName().startsWith("query"))
                .filter(i -> String.valueOf(i.getLocation()).contains(caller))
                .count();
    }

    @Test
    @DisplayName("erişilebilirlik TAKIM: izleme × İstanbul günü (21Z sınırı, ay dışı kova yok) H2'de; iki takım aynı haritayla → 2 sorgu toplam")
    void availabilityTeamQueries() {
        JdbcTemplate real = new JdbcTemplate(dataSource);
        rollupTables(real);
        String ins = "INSERT INTO monitor_check_hourly(monitor_type, monitor_key, hour_bucket, total_checks, up_checks) VALUES (?,?,?,?,?)";
        real.update(ins, "HTTP", "1", "2026-08-31T20", 10, 10);              // 31 Ağustos 23:00 TR — ay dışı
        real.update(ins, "HTTP", "1", "2026-08-31T21", 10, 9);               // 1 Eylül 00:00 TR
        real.update(ins, "HTTP", "1", "2026-09-01T20", 10, 10);              // 1 Eylül 23:00 TR
        real.update(ins, "HTTP", "1", "2026-09-01T21", 10, 8);               // 2 Eylül 00:00 TR
        real.update(ins, "HTTP", "1", "2026-09-30T20", 10, 10);              // 30 Eylül 23:00 TR
        real.update(ins, "HTTP", "1", "2026-09-30T21", 10, 0);               // 1 Ekim — ay dışı
        real.update(ins, "HTTP", "2", "2026-09-05T10", 10, 5);               // takım 2
        real.update(ins, "UPTIME", "ug.example.com", "2026-09-05T10", 10, 10); // UG envanteri → takım 1 de görür
        JdbcTemplate jdbc = spy(real);
        MonitoringOverviewService ov = mock(MonitoringOverviewService.class);
        Map<String, Object> h1 = new java.util.HashMap<>(Map.of("type", "http", "id", 1L, "name", "API", "team_id", 1L,
                "active", true, "deleted", false));
        Map<String, Object> h2 = new java.util.HashMap<>(Map.of("type", "http", "id", 2L, "name", "B", "team_id", 2L,
                "active", true, "deleted", false));
        when(ov.build(anyString(), any(), anyBoolean(), anyInt(), anyBoolean())).thenReturn(Map.of("monitors", List.of(h1, h2)));
        AvailabilitySection section = new AvailabilitySection(ov, jdbc);

        // Ham sorgu: SPLIT kipinde 31 Ağustos 21Z kovası 1 Eylül'e, 1 Eylül 21Z kovası 2 Eylül'e düşer
        Map<String, Map<LocalDate, long[]>> days = section.monitorDays(AvailabilitySection.perMonitorDaySqlHourly(21),
                AvailabilitySection.DayMode.SPLIT, "2026-08-31T21", "2026-09-30T21");
        assertThat(days.get("HTTP|1")).containsOnlyKeys(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 2),
                LocalDate.of(2026, 9, 30));
        assertThat(days.get("HTTP|1").get(LocalDate.of(2026, 9, 1))).containsExactly(20L, 19L);
        assertThat(days.get("HTTP|1").get(LocalDate.of(2026, 9, 2))).containsExactly(10L, 8L);
        // Saat kovası kipi (ofset tam saat değilse) aynı İstanbul günlerini verir
        Map<String, Map<LocalDate, long[]>> hourly = section.monitorDays(AvailabilitySection.perMonitorDaySqlHourly(-1),
                AvailabilitySection.DayMode.HOUR, "2026-08-31T21", "2026-09-30T21");
        assertThat(hourly.get("HTTP|1").get(LocalDate.of(2026, 9, 1))).containsExactly(20L, 19L);
        assertThat(hourly.keySet()).isEqualTo(days.keySet());

        clearInvocations(jdbc);
        Map<String, Object> shared = new java.util.concurrent.ConcurrentHashMap<>();
        SectionResult t1 = section.compute(ExecTestSupport.teamCtx(1, List.of(), TEAM_INV, shared));
        SectionResult t2 = section.compute(ExecTestSupport.teamCtx(2, List.of(), TEAM_INV, shared));
        // izleme toplamı (queryForList) + izleme × gün (query) — koşuda BİR kez; kurum kova sorgusu hiç
        assertThat(sectionQueries(jdbc, "AvailabilitySection")).isEqualTo(2);
        // Takım 1: HTTP|1 (40/37) + UPTIME ug (10/10) = 50 / 47; takım 2: HTTP|2 (10/5) + UPTIME ug (SY=2) = 20 / 15
        assertThat(ExecTestSupport.kpi(t1, "team_availability").value()).isEqualTo(AvailabilitySection.pctOf(47, 50));
        assertThat(ExecTestSupport.kpi(t2, "team_availability").value()).isEqualTo(AvailabilitySection.pctOf(15, 20));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> daily = (List<Map<String, Object>>) t1.data().get("daily");
        assertThat(daily).extracting(p -> p.get("date"))
                .containsExactly("2026-09-01", "2026-09-02", "2026-09-05", "2026-09-30");

        // Kurum özeti aynı haritayla: izleme toplamı yeniden okunmaz, yalnız kurum kova serisi (1 sorgu)
        clearInvocations(jdbc);
        SectionResult org = section.compute(new ExecutiveSummaryContext(YearMonth.of(2026, 9),
                java.time.Instant.parse("2026-10-10T09:00:00Z"), 99.9, 30, List::of, () -> TEAM_INV, Map::of, null, null, shared));
        assertThat(sectionQueries(jdbc, "AvailabilitySection")).isEqualTo(1);
        assertThat(ExecTestSupport.kpi(org, "org_availability").value()).isEqualTo(AvailabilitySection.pctOf(52, 60));
    }

    @Test
    @DisplayName("erişilebilirlik TAKIM: saatlik özet yoksa günlük özetin izleme × gün sorgusu H2'de çalışır (yaklaşık)")
    void availabilityTeamDailyFallback() {
        JdbcTemplate real = new JdbcTemplate(dataSource);
        rollupTables(real);
        String ins = "INSERT INTO monitor_check_daily(monitor_type, monitor_key, day, total_checks, up_checks) VALUES (?,?,?,?,?)";
        real.update(ins, "HTTP", "1", "2026-09-01", 100, 99);
        real.update(ins, "HTTP", "1", "2026-10-01", 100, 0);                 // ay dışı
        MonitoringOverviewService ov = mock(MonitoringOverviewService.class);
        Map<String, Object> h1 = new java.util.HashMap<>(Map.of("type", "http", "id", 1L, "name", "API", "team_id", 1L,
                "active", true, "deleted", false));
        when(ov.build(anyString(), any(), anyBoolean(), anyInt(), anyBoolean())).thenReturn(Map.of("monitors", List.of(h1)));
        SectionResult r = new AvailabilitySection(ov, real).compute(ExecTestSupport.teamCtx(1, List.of(), TEAM_INV));
        assertThat(r.data()).containsEntry("source", "daily_rollup").containsEntry("approximate", true);
        assertThat(ExecTestSupport.kpi(r, "team_availability").value()).isEqualTo(99.0);
        assertThat(ExecTestSupport.noteCodes(r)).contains("APPROX", "TEAM_METHOD");
    }

    private AlertEvent alert(String domain, String type, String created, Long team, String context) {
        AlertEvent e = new AlertEvent();
        e.setDomain(domain);
        e.setAlertType(type);
        e.setAlertLevel("HIGH");
        e.setCreatedAt(created);
        e.setTeamId(team);
        e.setContextJson(context);
        return alerts.save(e);
    }

    @Test
    @DisplayName("gürültü TAKIM: izdüşüm bağlam JSON'unu da taşır; başka takımın bağımsız Port'u süzülür; iki takım → ay başına BİR okuma")
    void noiseTeamQueries() {
        alert("sy.example.com", "ACCESSIBILITY", "2026-09-01T10:00:00", 1L, null);
        alert("ug.example.com", "PORT_DOWN", "2026-09-02T10:00:00", 2L, null);                                   // türev → takım 1 (UG)
        alert("ug.example.com", "PORT_DOWN", "2026-09-03T10:00:00", 3L, "{\"team_id\":3,\"standalone\":true}");  // bağımsız → yalnız 3
        alert("ug.example.com", "PORT_DOWN", "2026-08-20T10:00:00", 2L, null);                                   // önceki ay
        alert("sy.example.com", "ACCESSIBILITY", "2026-09-30T21:00:00", 1L, null);                               // Ekim — dışarıda
        List<Object[]> rows = alerts.findExecutiveRows("2026-08-31T21:00:00", "2026-09-30T21:00:00");
        assertThat(rows).hasSize(3);
        assertThat(rows).allSatisfy(r -> assertThat(r).hasSize(11));
        assertThat(rows).anySatisfy(r -> assertThat(r[10]).isEqualTo("{\"team_id\":3,\"standalone\":true}"));

        AlertEventRepository spyRepo = mock(AlertEventRepository.class, delegatesTo(alerts));
        AlarmNoiseSection section = new AlarmNoiseSection(spyRepo);
        Map<String, Object> shared = new java.util.concurrent.ConcurrentHashMap<>();
        SectionResult t1 = section.compute(ExecTestSupport.teamCtx(1, List.of(), TEAM_INV, shared));
        SectionResult t3 = section.compute(ExecTestSupport.teamCtx(3, List.of(), TEAM_INV, shared));
        verify(spyRepo, times(1)).findExecutiveRows("2026-08-31T21:00:00", "2026-09-30T21:00:00");
        verify(spyRepo, times(1)).findExecutiveRows("2026-07-31T21:00:00", "2026-08-31T21:00:00");
        verify(spyRepo, never()).countCreatedBetween(anyString(), anyString());
        assertThat(ExecTestSupport.kpi(t1, "total_alarms").value()).isEqualTo(2);
        assertThat(t1.data().get("prev_total")).isEqualTo(1L);
        assertThat(ExecTestSupport.kpi(t3, "total_alarms").value()).isEqualTo(1);
        assertThat(t3.data().get("prev_total")).isEqualTo(0L);
    }
}
