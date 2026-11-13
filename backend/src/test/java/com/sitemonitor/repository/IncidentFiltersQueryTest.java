package com.sitemonitor.repository;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.CertificateInventory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.TestPropertySource;

import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Olaylar sunucu süzgeçleri (2026-11-13) — H2 entegrasyonu: önem (CSV), takım (Alarm Geçmişi'nin damga + envanter SY/UG
 * kuralı), onaylı, takımsız (takım sütunuyla aynı), çözülme anı ({@code resolvedSince} — açılış değil), tekrarlayan imza
 * (son pencerede en az iki kez), kaçışlı arama ("%" her şeyi döndürmez) ve özet sorguları (önem × onay, zamanlama
 * izdüşümü, takım seçenekleri).
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class IncidentFiltersQueryTest {

    private static final long A = 5L, B = 9L, C = 12L;
    private static final List<Long> NONE = List.of(-1L);

    @Autowired AlertEventRepository alerts;
    @Autowired CertificateInventoryRepository inventory;

    private AlertEvent save(String domain, Long teamId, String type, String level, boolean resolved, boolean ack,
                            String createdAt, String resolvedAt) {
        AlertEvent e = new AlertEvent();
        e.setDomain(domain);
        e.setTeamId(teamId);
        e.setAlertType(type);
        e.setAlertLevel(level);
        e.setAcknowledged(ack);
        e.setResolved(resolved);
        e.setCreatedAt(createdAt);
        e.setResolvedAt(resolvedAt);
        if (ack) e.setAcknowledgedAt(createdAt.substring(0, 14) + "30:00");
        return alerts.save(e);
    }

    private void inv(String domain, Long teamId, Long ugTeamId) {
        CertificateInventory i = new CertificateInventory();
        i.setDomain(domain); i.setPort(443); i.setActive(true); i.setTeamId(teamId); i.setUgTeamId(ugTeamId);
        inventory.save(i);
    }

    @BeforeEach
    void seed() {
        save("a-crit.example.com", A, "HTTP_DOWN", "CRITICAL", false, false, "2026-11-10T08:00:00", null);
        save("a-high.example.com", A, "PING_DOWN", "HIGH", false, true, "2026-11-10T09:00:00", null);
        save("b-warn.example.com", B, "HTTP_DOWN", "WARNING", true, false, "2026-11-01T10:00:00", "2026-11-12T10:00:00");
        save("cert-ug.example.com", null, "EXPIRY", "HIGH", false, false, "2026-11-11T08:00:00", null);   // envanter UG = C
        save("teamless.example.com", null, "EXPIRY", "WARNING", false, false, "2026-11-11T09:00:00", null);
        save("100%_lit.example.com", A, "HTTP_DOWN", "WARNING", true, false, "2026-11-09T09:00:00", "2026-11-09T10:00:00");
        // tekrarlayan imza: aynı (alan adı, tür) iki kez
        save("flap.example.com", B, "PORT_DOWN", "WARNING", true, false, "2026-11-12T01:00:00", "2026-11-12T01:10:00");
        save("flap.example.com", B, "PORT_DOWN", "WARNING", false, false, "2026-11-12T02:00:00", null);
        inv("cert-ug.example.com", null, C);
    }

    private List<String> domains(Boolean resolved, String resolvedSince, String q, Collection<String> levels, Boolean ack,
                                 Long teamId, boolean teamless, String recurSince) {
        boolean scoped = levels != null && !levels.isEmpty();
        return alerts.findIncidentsFiltered(resolved, null, null, resolvedSince, null, AlertEventRepository.incidentLike(q),
                        scoped, scoped ? levels : AlertEventRepository.NO_LEVELS, ack, teamId, teamless, recurSince,
                        false, false, NONE, PageRequest.of(0, 50, Sort.by("domain").and(Sort.by("id"))))
                .getContent().stream().map(AlertEvent::getDomain).toList();
    }

    @Test
    @DisplayName("önem CSV: CRITICAL + HIGH — uyarılar dışarıda")
    void levels() {
        assertThat(domains(null, null, null, List.of("CRITICAL", "HIGH"), null, null, false, null))
                .containsExactly("a-crit.example.com", "a-high.example.com", "cert-ug.example.com");
    }

    @Test
    @DisplayName("takım: damga YA DA envanter SY/UG (Alarm Geçmişi kuralı); takımsız: damga yok ve envanterde takım yok")
    void teamAndTeamless() {
        assertThat(domains(null, null, null, null, null, C, false, null)).containsExactly("cert-ug.example.com");
        assertThat(domains(null, null, null, null, null, A, false, null))
                .containsExactly("100%_lit.example.com", "a-crit.example.com", "a-high.example.com");
        assertThat(domains(null, null, null, null, null, null, true, null)).containsExactly("teamless.example.com");
    }

    @Test
    @DisplayName("onaylı + açık: yalnız sahiplenilmiş süren olay")
    void acknowledged() {
        assertThat(domains(Boolean.FALSE, null, null, null, Boolean.TRUE, null, false, null)).containsExactly("a-high.example.com");
    }

    @Test
    @DisplayName("Çözüldü (24 sa): ÇÖZÜLME anına bakar — 11 gün önce açılıp dün çözülen olay sayılır")
    void resolvedSinceUsesResolutionTime() {
        assertThat(domains(Boolean.TRUE, "2026-11-12T00:00:00", null, null, null, null, false, null))
                .containsExactly("b-warn.example.com", "flap.example.com");
    }

    @Test
    @DisplayName("tekrarlayan: aynı imza pencere içinde en az iki kez — her iki oluşum da listelenir")
    void recurring() {
        assertThat(domains(null, null, null, null, null, null, false, "2026-11-11T00:00:00"))
                .containsExactly("flap.example.com", "flap.example.com");
    }

    @Test
    @DisplayName("arama kaçışlı: '%' her şeyi DEĞİL, yalnız '%' içeren adı bulur; '_' joker değil")
    void searchIsEscaped() {
        assertThat(domains(null, null, "%", null, null, null, false, null)).containsExactly("100%_lit.example.com");
        assertThat(domains(null, null, "0%_", null, null, null, false, null)).containsExactly("100%_lit.example.com");
        assertThat(domains(null, null, "a_crit", null, null, null, false, null)).isEmpty();
        assertThat(AlertEventRepository.incidentLike("  ")).isNull();
        assertThat(AlertEventRepository.incidentLike("A!b")).isEqualTo("%a!!b%");
    }

    @Test
    @DisplayName("özet: önem × onay kırılımı, zamanlama izdüşümü ve takım seçenekleri (damgalı + envanter)")
    void summaryQueries() {
        List<Object[]> facets = alerts.countIncidentFacets(Boolean.FALSE, null, null, null, null, null, false,
                AlertEventRepository.NO_LEVELS, null, null, false, null, false, false, NONE);
        long open = facets.stream().mapToLong(r -> ((Number) r[2]).longValue()).sum();
        assertThat(open).isEqualTo(5);
        assertThat(facets.stream().filter(r -> Boolean.TRUE.equals(r[1])).mapToLong(r -> ((Number) r[2]).longValue()).sum()).isEqualTo(1);

        List<Object[]> timing = alerts.findIncidentTimingRows(Boolean.TRUE, null, null, "2026-11-01T00:00:00", null, null, false,
                AlertEventRepository.NO_LEVELS, null, null, false, null, false, false, NONE, PageRequest.of(0, 100));
        assertThat(timing).hasSize(3);
        assertThat(timing.get(0)).hasSize(5);

        assertThat(alerts.countIncidentStampedTeams(null, null, null, null, null, false, AlertEventRepository.NO_LEVELS,
                null, null, false, null, false, false, NONE)).extracting(r -> r[0] + "=" + r[1])
                .containsExactlyInAnyOrder(A + "=3", B + "=3");
        assertThat(alerts.countIncidentInventoryTeams(null, null, null, null, null, false, AlertEventRepository.NO_LEVELS,
                null, null, false, null, false, false, NONE)).extracting(r -> r[0] + "/" + r[1] + "=" + r[2])
                .containsExactly("null/" + C + "=1");
    }

    @Test
    @DisplayName("eski imzalar (default) aynı süzgeç gövdesini kullanır — sunucu süzgeçleri kapalı")
    void legacySignaturesDelegate() {
        assertThat(alerts.countIncidents(null, null, null, null, null, false, false, NONE)).isEqualTo(8);
        assertThat(alerts.countIncidents(Boolean.FALSE, null, null, "EXPIRY", null, false, false, NONE)).isEqualTo(2);
        assertThat(alerts.countIncidents(null, null, null, null, "%", false, false, NONE)).isEqualTo(1);
    }
}
