package com.sitemonitor.repository;

import com.sitemonitor.model.AlertEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "Son N günde ne değişti" alarm sayaçlarının dar izdüşümleri (2026-11-17, performans) — gerçek JPQL (H2). Kahin: ESKİ yol
 * (tam entity sorgusu + Java süzgeci) aynı satırlarda; dar sorgu aynı (takım, alan) çiftlerini döndürmeli.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class AlertEventChangeStatQueriesTest {

    private static final String SINCE = "2026-11-10T00:00:00";

    @Autowired AlertEventRepository repo;

    private void alert(String domain, Long team, String createdAt, Boolean resolved, String resolvedAt, Boolean silently) {
        AlertEvent e = new AlertEvent();
        e.setDomain(domain);
        e.setTeamId(team);
        e.setAlertType("HTTP_DOWN");
        e.setAlertLevel("CRITICAL");
        e.setAcknowledged(false);
        e.setCreatedAt(createdAt);
        e.setResolved(resolved);
        e.setResolvedAt(resolvedAt);
        e.setResolvedSilently(silently);
        repo.save(e);
    }

    private static List<String> pairs(List<Object[]> rows) {
        List<String> out = new ArrayList<>();
        for (Object[] r : rows) out.add(r[0] + "|" + r[1]);
        return out;
    }

    @Test
    @DisplayName("Çözülen (sessiz olmayan) alarmlar: dar izdüşüm = eski entity sorgusu + Java süzgeci (resolved true, sessiz NULL / false)")
    void resolvedStatRowsEqualOldEntityPath() {
        alert("a.example.com", 1L, "2026-11-01T00:00:00", true, "2026-11-11T00:00:00", null);      // eskiden açılmış, pencerede çözülmüş
        alert("b.example.com", 2L, "2026-11-11T00:00:00", true, "2026-11-12T00:00:00", false);
        alert("c.example.com", null, "2026-11-11T00:00:00", true, "2026-11-12T00:00:00", true);     // sessiz kapanış → sayılmaz
        alert("d.example.com", 1L, "2026-11-01T00:00:00", true, "2026-11-05T00:00:00", null);      // pencereden önce çözülmüş
        alert("e.example.com", 3L, "2026-11-11T00:00:00", false, null, null);                      // açık
        alert("f.example.com", 3L, "2026-11-11T00:00:00", false, "2026-11-12T00:00:00", null);     // tutarsız: çözülmedi ama damga var
        alert("g.example.com", null, "2026-11-11T00:00:00", true, SINCE, false);                   // tam sınır: dahil

        List<String> oracle = new ArrayList<>();
        for (AlertEvent e : repo.findByResolvedAtGreaterThanEqual(SINCE)) {
            if (!Boolean.TRUE.equals(e.getResolved()) || Boolean.TRUE.equals(e.getResolvedSilently())) continue;
            oracle.add(e.getTeamId() + "|" + e.getDomain());
        }
        assertThat(pairs(repo.findResolvedStatRowsSince(SINCE))).containsExactlyInAnyOrderElementsOf(oracle)
                .containsExactlyInAnyOrder("1|a.example.com", "2|b.example.com", "null|g.example.com");
    }

    @Test
    @DisplayName("Açılan alarmlar: dar izdüşüm (findCreatedStatRowsSince) = eski entity sorgusunun (takım, alan, açılış) satırları")
    void createdStatRowsEqualOldEntityPath() {
        alert("a.example.com", 1L, "2026-11-11T00:00:00", false, null, null);
        alert("b.example.com", null, "2026-11-12T00:00:00", true, "2026-11-13T00:00:00", true);
        alert("c.example.com", 2L, "2026-11-01T00:00:00", false, null, null);                      // pencereden önce açılmış
        alert("d.example.com", 3L, SINCE, false, null, null);

        List<String> oracle = new ArrayList<>();
        for (AlertEvent e : repo.findByCreatedAtGreaterThanEqualOrderByCreatedAtDesc(SINCE)) {
            oracle.add(e.getTeamId() + "|" + e.getDomain() + "|" + e.getCreatedAt());
        }
        List<String> narrow = new ArrayList<>();
        for (Object[] r : repo.findCreatedStatRowsSince(SINCE)) narrow.add(r[0] + "|" + r[1] + "|" + r[2]);
        assertThat(narrow).containsExactlyInAnyOrderElementsOf(oracle).hasSize(3);
    }
}
