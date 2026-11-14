package com.sitemonitor.repository;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.service.AlertTrendService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kişisel Panom "Alarm eğilimi" (2026-11-14) — iki yeni DAR gruplu sorgunun H2 (gerçek JPQL) üstünde kanıtı: sahip (damgalı
 * takım + hedef) × UTC saat kovası başına sayım, alt sınır ({@code since}) dahil, kritik / sessiz kapanış ayrı sayılır; açık
 * alarm "kapanan"a, pencereden önce kapanan sonuca girmez. Son test gerçek depolarla servisin uçtan uca çıktısını doğrular.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class AlertTrendQueriesTest {

    @Autowired AlertEventRepository repo;
    @Autowired CertificateInventoryRepository invRepo;

    private AlertEvent alert(String domain, String level, String createdAt, String resolvedAt, Boolean silently, Long teamId) {
        AlertEvent e = new AlertEvent();
        e.setDomain(domain);
        e.setAlertType("HTTP_DOWN");
        e.setAlertLevel(level);
        e.setAcknowledged(false);
        e.setResolved(resolvedAt != null);
        e.setResolvedAt(resolvedAt);
        e.setResolvedSilently(silently);
        e.setCreatedAt(createdAt);
        e.setTeamId(teamId);
        return repo.save(e);
    }

    private static String line(Object[] r) {
        return Arrays.stream(r).map(String::valueOf).reduce((a, b) -> a + "|" + b).orElse("");
    }

    @Test
    @DisplayName("açılanlar: sahip × saat kovası başına sayı + kritik sayısı; alt sınır dahil, öncesi hariç")
    void openedGroupedByOwnerAndHour() {
        alert("a.example.com", "CRITICAL", "2026-11-13T20:05:00", null, null, 5L);
        alert("a.example.com", "WARNING", "2026-11-13T20:55:00", "2026-11-13T21:10:00", false, 5L);   // aynı sahip + saat → tek satır
        alert("a.example.com", "critical", "2026-11-13T21:00:00", null, null, 5L);                   // sonraki saat; seviye küçük harf
        alert("b.example.com", "HIGH", "2026-11-13T20:30:00", null, null, null);                     // damgasız
        alert("a.example.com", "HIGH", "2026-11-10T00:00:00", null, null, 5L);                       // tam alt sınırda → dahil
        alert("a.example.com", "HIGH", "2026-11-09T23:59:59", null, null, 5L);                       // sınırdan önce → hariç

        List<String> rows = repo.countOpenedByOwnerAndHour("2026-11-10T00:00:00").stream().map(AlertTrendQueriesTest::line).sorted().toList();
        assertThat(rows).containsExactly(
                "5|a.example.com|2026-11-10T00|1|0",
                "5|a.example.com|2026-11-13T20|2|1",
                "5|a.example.com|2026-11-13T21|1|1",
                "null|b.example.com|2026-11-13T20|1|0");
    }

    @Test
    @DisplayName("kapananlar: KAPANIŞ anına göre (açılış ne zaman olursa olsun); açık alarm yok; sessiz kapanış ayrı sayılır")
    void closedGroupedByOwnerAndHour() {
        alert("a.example.com", "HIGH", "2026-10-01T08:00:00", "2026-11-13T09:15:00", false, 5L);      // haftalar önce açıldı, pencerede kapandı
        alert("a.example.com", "HIGH", "2026-11-13T09:00:00", "2026-11-13T09:45:00", true, 5L);       // sessiz kapanış
        alert("a.example.com", "HIGH", "2026-11-13T09:00:00", "2026-11-13T09:50:00", null, 5L);       // eski satır: bayrak boş
        alert("a.example.com", "HIGH", "2026-11-13T09:00:00", null, null, 5L);                        // hâlâ açık
        alert("a.example.com", "HIGH", "2026-11-01T09:00:00", "2026-11-09T23:00:00", false, 5L);      // pencereden önce kapandı
        alert("c.example.com", "HIGH", "2026-11-13T09:00:00", "2026-11-13T10:00:00", false, 9L);

        List<String> rows = repo.countClosedByOwnerAndHour("2026-11-10T00:00:00").stream().map(AlertTrendQueriesTest::line).sorted().toList();
        assertThat(rows).containsExactly(
                "5|a.example.com|2026-11-13T09|3|1",
                "9|c.example.com|2026-11-13T10|1|0");
    }

    @Test
    @DisplayName("uçtan uca (gerçek depolar): kapsamlı kullanıcı damgalı takımını ve envanterinin SY / UG alarmlarını sayar; pasif envanter de sahibine bağlar")
    @SuppressWarnings("unchecked")
    void serviceOverRealRepositories() {
        CertificateInventory inv = new CertificateInventory();
        inv.setDomain("ug.example.com"); inv.setPort(443); inv.setTeamId(7L); inv.setUgTeamId(5L); inv.setActive(false);
        invRepo.save(inv);
        alert("standalone", "CRITICAL", "2026-11-13T20:05:00", "2026-11-13T21:05:00", false, 5L);
        alert("ug.example.com", "WARNING", "2026-11-13T19:00:00", null, null, null);
        alert("other", "HIGH", "2026-11-13T19:00:00", null, null, 9L);

        AlertTrendService svc = new AlertTrendService(repo, invRepo);
        ReflectionTestUtils.setField(svc, "clock", Clock.fixed(Instant.parse("2026-11-13T22:30:00Z"), ZoneOffset.UTC));
        Map<String, Object> scoped = svc.build("24h", false, List.of(5L));
        Map<String, Object> totals = (Map<String, Object>) scoped.get("totals");
        assertThat(totals).containsEntry("opened", 2L).containsEntry("closed", 1L).containsEntry("critical", 1L);
        Map<String, Object> all = (Map<String, Object>) svc.build("7d", true, null).get("totals");
        assertThat(all).containsEntry("opened", 3L).containsEntry("closed", 1L);
    }
}
