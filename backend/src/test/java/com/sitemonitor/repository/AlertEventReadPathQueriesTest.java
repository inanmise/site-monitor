package com.sitemonitor.repository;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.CertificateInventory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestPropertySource;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Okuma yolu sorguları (2026-10-22, PKG-5) — gerçek JPQL (H2):
 * <ul>
 *   <li>DATA-4: Kontrol Geçmişi aralıkla KESİŞEN alarmı alır — aralıktan önce açılıp süren / aralığı kaplayan kesinti dâhil;</li>
 *   <li>DATA-8 + DATA-7: olay listesi önem sırası CASE ile (CRITICAL önce, alfabetik değil), eşitlikte kimlik;</li>
 *   <li>DATA-11: bildirim kutusu geçmişi kapsam (damga ya da aktif envanter SY / UG) + sayfa SQL'de, açılış alt sınırı yok.</li>
 * </ul>
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class AlertEventReadPathQueriesTest {

    @Autowired AlertEventRepository repo;
    @Autowired CertificateInventoryRepository invRepo;

    private AlertEvent alert(String domain, String type, String level, String createdAt, String resolvedAt, Long teamId) {
        AlertEvent e = new AlertEvent();
        e.setDomain(domain);
        e.setAlertType(type);
        e.setAlertLevel(level);
        e.setAcknowledged(false);
        e.setResolved(resolvedAt != null);
        e.setResolvedAt(resolvedAt);
        e.setCreatedAt(createdAt);
        e.setTeamId(teamId);
        return repo.save(e);
    }

    @Test
    @DisplayName("DATA-4: aralıktan ÖNCE açılıp hâlâ açık / aralığı kaplayıp sonra çözülen kesinti gelir; aralıktan önce biten ve sonra başlayan gelmez")
    void overlapIncludesOutagesStartedBeforeRange() {
        String from = "2026-10-20T00:00:00", to = "2026-10-21T00:00:00";
        AlertEvent ongoing = alert("h.example.com", "HTTP_DOWN", "CRITICAL", "2026-10-18T08:00:00", null, 1L);
        AlertEvent spans = alert("h.example.com", "HTTP_DOWN", "CRITICAL", "2026-10-19T08:00:00", "2026-10-21T05:00:00", 1L);
        AlertEvent inside = alert("h.example.com", "HTTP_DOWN", "CRITICAL", "2026-10-20T03:00:00", "2026-10-20T04:00:00", 1L);
        alert("h.example.com", "HTTP_DOWN", "CRITICAL", "2026-10-17T08:00:00", "2026-10-19T00:00:00", 1L);   // önce bitti
        alert("h.example.com", "HTTP_DOWN", "CRITICAL", "2026-10-21T01:00:00", null, 1L);                    // sonra başladı
        alert("h.example.com", "PORT_DOWN", "CRITICAL", "2026-10-18T08:00:00", null, 1L);                    // başka tür
        alert("other.example.com", "HTTP_DOWN", "CRITICAL", "2026-10-18T08:00:00", null, 1L);                // başka anahtar

        List<AlertEvent> got = repo.findOverlappingForHistory("h.example.com", List.of("HTTP_DOWN"), from, to);
        assertThat(got).extracting(AlertEvent::getId)
                .containsExactly(inside.getId(), spans.getId(), ongoing.getId());   // en yeni önce
    }

    @Test
    @DisplayName("DATA-8: olay listesi 'önem' sırası CASE ile — desc: CRITICAL, HIGH, WARNING (alfabetik WARNING önce DEĞİL); eşitlikte kimlik azalan")
    void incidentsSeveritySort() {
        AlertEvent w = alert("a.example.com", "HTTP_DOWN", "WARNING", "2026-10-20T10:00:00", null, 1L);
        AlertEvent c1 = alert("b.example.com", "HTTP_DOWN", "CRITICAL", "2026-10-20T10:00:00", null, 1L);
        AlertEvent h = alert("c.example.com", "HTTP_DOWN", "HIGH", "2026-10-20T10:00:00", null, 1L);
        AlertEvent c2 = alert("d.example.com", "HTTP_DOWN", "CRITICAL", "2026-10-20T10:00:00", null, 1L);
        Page<AlertEvent> p = repo.findIncidents(null, null, null, null, null, false, false, List.of(-1L),
                PageRequest.of(0, 10, com.sitemonitor.controller.IncidentsController.sortFor("severity", "desc")));
        assertThat(p.getContent()).extracting(AlertEvent::getId)
                .containsExactly(c2.getId(), c1.getId(), h.getId(), w.getId());
        Page<AlertEvent> asc = repo.findIncidents(null, null, null, null, null, false, false, List.of(-1L),
                PageRequest.of(0, 10, com.sitemonitor.controller.IncidentsController.sortFor("severity", "asc")));
        assertThat(asc.getContent().get(0).getId()).isEqualTo(w.getId());
    }

    @Test
    @DisplayName("DATA-7: aynı saniyede açılan 23 alarm, 5'lik sayfalarla — kararlı sıra (createdAt, id) her alarmı tam bir kez verir")
    void stablePagingSameSecond() {
        Set<Long> all = new HashSet<>();
        for (int i = 0; i < 23; i++) all.add(alert("s" + i + ".example.com", "HTTP_DOWN", "WARNING", "2026-10-20T10:00:00", null, 7L).getId());
        Set<Long> seen = new HashSet<>();
        int rows = 0;
        for (int p = 0; p < 5; p++) {
            Page<AlertEvent> page = repo.findFiltered(null, "2026-10-20T00:00:00", "2026-10-21T00:00:00", null, null, null, null,
                    true, List.of(7L), PageRequest.of(p, 5, com.sitemonitor.service.report.WeeklyOutageReportService.STABLE_ORDER));
            for (AlertEvent e : page.getContent()) { seen.add(e.getId()); rows++; }
        }
        assertThat(rows).isEqualTo(23);
        assertThat(seen).isEqualTo(all);
    }

    @Test
    @DisplayName("DATA-11: geçmiş — 90 gün önce açılıp bu hafta çözülen gelir; kapsam damga ya da AKTİF envanterin SY / UG takımı; sayfa + toplam SQL'de")
    void resolvedHistoryScopeAndPaging() {
        CertificateInventory inv = new CertificateInventory();
        inv.setDomain("ug.example.com"); inv.setTeamId(2L); inv.setUgTeamId(3L); inv.setActive(true); inv.setPort(443);
        invRepo.save(inv);
        String since = "2026-10-01T00:00:00";
        AlertEvent old = alert("x.example.com", "HTTP_DOWN", "WARNING", "2026-07-20T10:00:00", "2026-10-15T10:00:00", 3L);
        AlertEvent viaUg = alert("ug.example.com", "EXPIRY", "HIGH", "2026-10-02T10:00:00", "2026-10-16T10:00:00", 2L);
        alert("y.example.com", "HTTP_DOWN", "WARNING", "2026-10-02T10:00:00", "2026-10-17T10:00:00", 4L);   // başka takım
        alert("x.example.com", "HTTP_DOWN", "WARNING", "2026-09-01T10:00:00", "2026-09-20T10:00:00", 3L);   // pencereden önce çözüldü
        alert("x.example.com", "HTTP_DOWN", "WARNING", "2026-10-03T10:00:00", null, 3L);                    // açık

        Page<AlertEvent> p0 = repo.findResolvedHistory(since, true, List.of(3L), PageRequest.of(0, 1));
        assertThat(p0.getTotalElements()).isEqualTo(2);
        assertThat(p0.getContent()).extracting(AlertEvent::getId).containsExactly(viaUg.getId());   // en yeni çözülen önce
        Page<AlertEvent> p1 = repo.findResolvedHistory(since, true, List.of(3L), PageRequest.of(1, 1));
        assertThat(p1.getContent()).extracting(AlertEvent::getId).containsExactly(old.getId());
        assertThat(repo.findResolvedHistory(since, false, List.of(-1L), PageRequest.of(0, 10)).getTotalElements()).isEqualTo(3);
        assertThat(repo.findResolvedHistory(since, true, List.of(-1L), PageRequest.of(0, 10)).getTotalElements()).isZero();
    }
}
