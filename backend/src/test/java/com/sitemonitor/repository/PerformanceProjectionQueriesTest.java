package com.sitemonitor.repository;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.AppUser;
import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.Team;
import com.sitemonitor.model.WeeklyReport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestPropertySource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Performans düzeltmelerinin (2026-10-01) dar projeksiyon / gruplu sorgularının H2 (MODE=PostgreSQL) üstünde SORGU-DÜZEYİ
 * kanıtı: JPQL geçerli, SÜTUN SIRASI servislerin okuduğu sırayla aynı, kapsam/süzgeç yüklemleri eski tam-entity
 * yollarıyla aynı kümeyi verir.
 * <ul>
 *   <li>{@code countOpenByTypeLevelAck} / {@code findOpenSummaryItems} — {@code OpenAlertsSummaryService}</li>
 *   <li>{@code findNoiseRowsSince} + {@code findActiveDomainTeams} — {@code AlertNoiseService}</li>
 *   <li>{@code countRecoveredSinceByTypeAndTeam} + {@code findActiveDomains} — {@code MonitoringOverviewService}</li>
 *   <li>{@code findLatestPerTeam} + {@code findByActiveTrueOrderByUsernameAsc} — {@code WeeklyReportTeamInfoService}</li>
 * </ul>
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class PerformanceProjectionQueriesTest {

    @Autowired AlertEventRepository alertRepo;
    @Autowired CertificateInventoryRepository invRepo;
    @Autowired TeamRepository teamRepo;
    @Autowired WeeklyReportRepository reportRepo;
    @Autowired AppUserRepository userRepo;

    private AlertEvent alert(String domain, String type, String level, String createdAt, Long teamId, boolean acked) {
        AlertEvent e = new AlertEvent();
        e.setDomain(domain); e.setAlertType(type); e.setAlertLevel(level); e.setCreatedAt(createdAt);
        e.setTeamId(teamId); e.setAcknowledged(acked); e.setResolved(false);
        return alertRepo.save(e);
    }

    private AlertEvent resolvedAlert(String domain, String type, String resolvedAt, Long teamId, Boolean silently) {
        AlertEvent e = new AlertEvent();
        e.setDomain(domain); e.setAlertType(type); e.setAlertLevel("HIGH"); e.setCreatedAt("2026-07-01T00:00:00");
        e.setTeamId(teamId); e.setAcknowledged(false); e.setResolved(true); e.setResolvedAt(resolvedAt); e.setResolvedSilently(silently);
        return alertRepo.save(e);
    }

    private CertificateInventory inv(String domain, Long teamId, Long ugTeamId, boolean active) {
        CertificateInventory i = new CertificateInventory();
        i.setDomain(domain); i.setPort(443); i.setActive(active); i.setTeamId(teamId); i.setUgTeamId(ugTeamId);
        return invRepo.save(i);
    }

    private Team team(String name) {
        Team t = new Team(); t.setName(name); t.setActive(true);
        return teamRepo.save(t);
    }

    private static Map<String, Long> groupCounts(List<Object[]> rows) {
        Map<String, Long> m = new HashMap<>();
        for (Object[] r : rows) m.merge(r[0] + "|" + r[1] + "|" + r[2], ((Number) r[3]).longValue(), Long::sum);
        return m;
    }

    // ── Menü alarm rozetleri ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("countOpenByTypeLevelAck: yalnız AÇIK alarmlar, (tip, seviye, sahiplenildi) gruplu; kapsam = damgalı takım VEYA envanter SY/UG")
    void countOpenByTypeLevelAck_groupsAndScopes() {
        inv("sy.example.com", 7L, null, true);
        inv("ug.example.com", 8L, 7L, true);
        alert("a.example.com", "HTTP_DOWN", "CRITICAL", "2026-07-01T10:00:00", 7L, false);
        alert("b.example.com", "HTTP_DOWN", "CRITICAL", "2026-07-01T11:00:00", 7L, false);
        alert("c.example.com", "HTTP_DOWN", "CRITICAL", "2026-07-01T12:00:00", 7L, true);
        alert("sy.example.com", "EXPIRY", "WARNING", "2026-07-01T12:00:00", null, false);   // takım envanterden (SY)
        alert("ug.example.com", "EXPIRY", "HIGH", "2026-07-01T12:00:00", null, false);      // takım envanterden (UG)
        alert("other.example.com", "PING_DOWN", "HIGH", "2026-07-01T12:00:00", 99L, false);  // kapsam dışı
        AlertEvent closed = alert("d.example.com", "HTTP_DOWN", "CRITICAL", "2026-07-01T13:00:00", 7L, false);
        closed.setResolved(true); alertRepo.save(closed);                                   // çözülmüş → sayılmaz

        Map<String, Long> scoped = groupCounts(alertRepo.countOpenByTypeLevelAck(true, List.of(7L)));
        assertThat(scoped).containsOnly(
                Map.entry("HTTP_DOWN|CRITICAL|false", 2L), Map.entry("HTTP_DOWN|CRITICAL|true", 1L),
                Map.entry("EXPIRY|WARNING|false", 1L), Map.entry("EXPIRY|HIGH|false", 1L));

        Map<String, Long> all = groupCounts(alertRepo.countOpenByTypeLevelAck(false, List.of(-1L)));
        assertThat(all.values().stream().mapToLong(Long::longValue).sum()).isEqualTo(6L);
        assertThat(all).containsEntry("PING_DOWN|HIGH|false", 1L);

        // Eski yolla aynı küme: countFilteredByType (açık, aynı kapsam) tip toplamları eşit
        Map<String, Long> legacy = new HashMap<>();
        for (Object[] r : alertRepo.countFilteredByType(false, null, null, null, null, null, true, List.of(7L)))
            legacy.put(String.valueOf(r[0]), ((Number) r[1]).longValue());
        Map<String, Long> byType = new HashMap<>();
        scoped.forEach((k, v) -> byType.merge(k.substring(0, k.indexOf('|')), v, Long::sum));
        assertThat(byType).isEqualTo(legacy);
    }

    @Test
    @DisplayName("findOpenSummaryItems: sütun sırası [id, domain, tip, seviye, açılış, sahiplenildi, takım, fırtına, takım adı]; en yeni önce; tip süzgeci; LIMIT; takımsız satırın adı null")
    void findOpenSummaryItems_projectionOrderAndLimit() {
        Team ops = team("Ops");
        AlertEvent newest = alert("n.example.com", "HTTP_DOWN", "CRITICAL", "2026-07-03T10:00:00", ops.getId(), true);
        newest.setStormId(42L); alertRepo.save(newest);
        alert("m.example.com", "PING_DOWN", "HIGH", "2026-07-02T10:00:00", null, false);
        alert("o.example.com", "HTTP_DOWN", "WARNING", "2026-07-01T10:00:00", ops.getId(), false);
        alert("x.example.com", "DNS_FAILURE", "HIGH", "2026-07-04T10:00:00", ops.getId(), false);   // tip süzgeci dışı

        List<Object[]> rows = alertRepo.findOpenSummaryItems(Set.of("HTTP_DOWN", "PING_DOWN"), false, List.of(-1L), PageRequest.of(0, 60));
        assertThat(rows).extracting(r -> r[1]).containsExactly("n.example.com", "m.example.com", "o.example.com");
        Object[] r0 = rows.get(0);
        assertThat(r0).hasSize(9);
        assertThat(((Number) r0[0]).longValue()).isEqualTo(newest.getId());
        assertThat(r0[2]).isEqualTo("HTTP_DOWN");
        assertThat(r0[3]).isEqualTo("CRITICAL");
        assertThat(r0[4]).isEqualTo("2026-07-03T10:00:00");
        assertThat(r0[5]).isEqualTo(Boolean.TRUE);
        assertThat(((Number) r0[6]).longValue()).isEqualTo(ops.getId());
        assertThat(((Number) r0[7]).longValue()).isEqualTo(42L);
        assertThat(r0[8]).isEqualTo("Ops");
        assertThat(rows.get(1)[6]).isNull();
        assertThat(rows.get(1)[8]).isNull();

        assertThat(alertRepo.findOpenSummaryItems(Set.of("HTTP_DOWN", "PING_DOWN"), false, List.of(-1L), PageRequest.of(0, 2))).hasSize(2);
        // Kapsam: takım 99 → hiçbir satır
        assertThat(alertRepo.findOpenSummaryItems(Set.of("HTTP_DOWN"), true, List.of(99L), PageRequest.of(0, 60))).isEmpty();
        assertThat(alertRepo.findOpenSummaryItems(Set.of("HTTP_DOWN"), true, List.of(ops.getId()), PageRequest.of(0, 60))).hasSize(2);
    }

    // ── Alarm gürültü analizi ───────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("findNoiseRowsSince: pencere alt sınırı (≥ since); sütun sırası AlertNoiseService.Ev ile aynı")
    void findNoiseRowsSince_columns() {
        AlertEvent e = alert("n.example.com", "HTTP_DOWN", "CRITICAL", "2026-07-05T10:00:00", 3L, true);
        e.setResolved(true); e.setResolvedAt("2026-07-05T10:30:00"); e.setResolvedSilently(true); alertRepo.save(e);
        alert("old.example.com", "HTTP_DOWN", "CRITICAL", "2026-07-01T10:00:00", 3L, false);   // pencere dışı

        List<Object[]> rows = alertRepo.findNoiseRowsSince("2026-07-05T10:00:00");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsExactly("n.example.com", "HTTP_DOWN", "CRITICAL", "2026-07-05T10:00:00",
                "2026-07-05T10:30:00", true, true, true, 3L);
    }

    @Test
    @DisplayName("findActiveDomainTeams / findActiveDomains: yalnız AKTİF envanter; (alan adı, SY takımı) sırası")
    void activeInventoryProjections() {
        inv("live.example.com", 5L, 6L, true);
        inv("nosy.example.com", null, null, true);
        inv("gone.example.com", 5L, null, false);

        Map<Object, Object> pairs = new HashMap<>();
        for (Object[] r : invRepo.findActiveDomainTeams()) pairs.put(r[0], r[1]);
        assertThat(pairs).containsOnlyKeys("live.example.com", "nosy.example.com");
        assertThat(pairs.get("live.example.com")).isEqualTo(5L);
        assertThat(pairs.get("nosy.example.com")).isNull();
        assertThat(invRepo.findActiveDomains()).containsExactlyInAnyOrder("live.example.com", "nosy.example.com");
        // Eski tam-entity yoluyla aynı küme
        assertThat(invRepo.findActiveDomains()).containsExactlyInAnyOrderElementsOf(
                invRepo.findByActiveTrueOrderByDomainAsc().stream().map(CertificateInventory::getDomain).toList());
    }

    // ── İzleme Panosu ───────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("countRecoveredSinceByTypeAndTeam: yalnız pencerede ÇÖZÜLEN ve sessiz OLMAYAN; (tip, takım) gruplu, takımsız satır ayrı grup")
    void countRecoveredSinceByTypeAndTeam() {
        resolvedAlert("a.example.com", "HTTP_DOWN", "2026-07-05T10:00:00", 14L, null);
        resolvedAlert("b.example.com", "HTTP_DOWN", "2026-07-05T11:00:00", 14L, false);
        resolvedAlert("c.example.com", "HTTP_DOWN", "2026-07-05T12:00:00", 14L, true);    // sessiz → hariç
        resolvedAlert("d.example.com", "HTTP_DOWN", "2026-07-01T12:00:00", 14L, null);    // pencere öncesi → hariç
        resolvedAlert("e.example.com", "PING_DOWN", "2026-07-05T12:00:00", null, null);   // takımsız
        alert("f.example.com", "HTTP_DOWN", "HIGH", "2026-07-05T12:00:00", 14L, false);   // açık → hariç

        Map<String, Long> m = new HashMap<>();
        for (Object[] r : alertRepo.countRecoveredSinceByTypeAndTeam("2026-07-05T00:00:00"))
            m.put(r[0] + "|" + r[1], ((Number) r[2]).longValue());
        assertThat(m).containsOnly(Map.entry("HTTP_DOWN|14", 2L), Map.entry("PING_DOWN|null", 1L));
    }

    // ── Haftalık rapor takım bilgisi ────────────────────────────────────────────────────────────

    private WeeklyReport report(long teamId, int year, int week) {
        WeeklyReport r = new WeeklyReport();
        r.setTeamId(teamId); r.setReportYear(year); r.setWeekNo(week); r.setContentJson("{}"); r.setStatus("DRAFT");
        return reportRepo.save(r);
    }

    @Test
    @DisplayName("findLatestPerTeam: takım başına EN GÜNCEL (yıl, hafta) tek satır — yıl sınırı (2025-W52 < 2026-W01), takım süzgeci")
    void findLatestPerTeam() {
        report(1L, 2025, 52);
        WeeklyReport t1 = report(1L, 2026, 1);
        report(1L, 2025, 40);
        WeeklyReport t2 = report(2L, 2026, 39);
        report(2L, 2026, 38);
        report(3L, 2026, 40);   // süzgeç dışı takım

        List<WeeklyReport> rows = reportRepo.findLatestPerTeam(List.of(1L, 2L, 9L));
        assertThat(rows).extracting(WeeklyReport::getId).containsExactlyInAnyOrder(t1.getId(), t2.getId());
        // Eski takım-başına yolla aynı sonuç
        assertThat(reportRepo.findFirstByTeamIdOrderByReportYearDescWeekNoDesc(1L)).get().extracting(WeeklyReport::getId).isEqualTo(t1.getId());
        assertThat(reportRepo.findFirstByTeamIdOrderByReportYearDescWeekNoDesc(2L)).get().extracting(WeeklyReport::getId).isEqualTo(t2.getId());
    }

    @Test
    @DisplayName("findByActiveTrueOrderByUsernameAsc: pasif kullanıcılar gelmez, kullanıcı adına göre sıralı")
    void activeUsersOnly() {
        for (String[] u : new String[][]{{"zeynep", "true"}, {"ali", "true"}, {"pasif", "false"}}) {
            AppUser a = new AppUser(); a.setUsername(u[0]); a.setSystemRole("USER"); a.setActive(Boolean.parseBoolean(u[1]));
            userRepo.save(a);
        }
        assertThat(userRepo.findByActiveTrueOrderByUsernameAsc()).extracting(AppUser::getUsername).containsExactly("ali", "zeynep");
    }
}
