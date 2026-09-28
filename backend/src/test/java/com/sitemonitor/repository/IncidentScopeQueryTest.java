package com.sitemonitor.repository;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.CertificateInventory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Olaylar kapsam sorgusu (2026-09-28, org geneli salt okunur Olaylar) — H2 entegrasyonu.
 *
 * <p>Neden mock yetmez: sözleşmenin asıl kısmı SQL'in üç değerli mantığında. "Diğer ekiplerin olayları"
 * "Takımımın olayları"nın TAMAMLAYICISI olmalı; takımsız (teamId NULL) olay için {@code NOT (teamId IN …)} BİLİNMEYEN
 * döner ve olayı iki kümeden de sessizce düşürürdü. Ayrıca "kendi" olmak damgalı takım YA DA envanterin SY/UG takımı
 * üzerinden olur (IncidentsController.incidentTeamInScope ile aynı kural) — tamamlayıcı ikisini birden dışlamalı.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class IncidentScopeQueryTest {

    private static final long OWN = 5L, FOREIGN = 9L;
    private static final List<Long> SCOPE = List.of(OWN);
    private static final List<Long> NONE = List.of(-1L);

    @Autowired AlertEventRepository alerts;
    @Autowired CertificateInventoryRepository inventory;

    private AlertEvent save(String domain, Long teamId, String type, boolean resolved) {
        AlertEvent e = new AlertEvent();
        e.setDomain(domain);
        e.setTeamId(teamId);
        e.setAlertType(type);
        e.setAlertLevel("CRITICAL");
        e.setAcknowledged(false);
        e.setResolved(resolved);
        e.setCreatedAt("2026-09-28T0" + (alerts.count() % 10) + ":00:00");
        return alerts.save(e);
    }

    private void inv(String domain, Long teamId, Long ugTeamId) {
        CertificateInventory i = new CertificateInventory();
        i.setDomain(domain); i.setPort(443); i.setActive(true); i.setTeamId(teamId); i.setUgTeamId(ugTeamId);
        inventory.save(i);
    }

    private List<String> domains(boolean scoped, boolean outside, List<Long> scope) {
        Page<AlertEvent> p = alerts.findIncidents(null, null, null, null, null, scoped, outside, scope,
                PageRequest.of(0, 50, Sort.by("domain")));
        return p.getContent().stream().map(AlertEvent::getDomain).toList();
    }

    @BeforeEach
    void seed() {
        save("own-stamped.example.com", OWN, "HTTP_DOWN", false);          // damgalı kendi
        save("foreign-stamped.example.com", FOREIGN, "HTTP_DOWN", false);  // damgalı yabancı
        save("teamless.example.com", null, "EXPIRY", false);              // takımsız, envanter yok → yabancı
        save("ug-own.example.com", null, "EXPIRY", true);                 // damgasız; envanter SY yabancı, UG kendi → kendi
        save("sy-own.example.com", FOREIGN, "PING_DOWN", false);          // damga yabancı AMA envanter SY kendi → kendi
        save("inv-foreign.example.com", null, "EXPIRY", false);           // damgasız; envanter tamamen yabancı → yabancı
        inv("ug-own.example.com", FOREIGN, OWN);
        inv("sy-own.example.com", OWN, null);
        inv("inv-foreign.example.com", FOREIGN, null);
    }

    @Test
    @DisplayName("mine: damgalı kendi + envanter SY/UG üzerinden kendi (damga yabancı olsa bile)")
    void mine() {
        assertThat(domains(true, false, SCOPE))
                .containsExactly("own-stamped.example.com", "sy-own.example.com", "ug-own.example.com");
    }

    @Test
    @DisplayName("others: TAMAMLAYICI — takımsız (NULL teamId) olay da dahil, envanter üzerinden 'kendi' olan HARİÇ")
    void others() {
        assertThat(domains(true, true, SCOPE))
                .containsExactly("foreign-stamped.example.com", "inv-foreign.example.com", "teamless.example.com");
    }

    @Test
    @DisplayName("mine ∪ others = all ve ayrık; sayım sorgusu sayfa sorgusuyla aynı süzgeç (durum + kök neden dahil)")
    void partitionAndCounts() {
        List<String> all = domains(false, false, NONE);
        assertThat(all).hasSize(6);
        List<String> mine = domains(true, false, SCOPE);
        List<String> others = domains(true, true, SCOPE);
        assertThat(mine).doesNotContainAnyElementsOf(others);
        assertThat(mine.size() + others.size()).isEqualTo(all.size());

        assertThat(alerts.countIncidents(null, null, null, null, null, false, false, NONE)).isEqualTo(6);
        assertThat(alerts.countIncidents(null, null, null, null, null, true, false, SCOPE)).isEqualTo(3);
        assertThat(alerts.countIncidents(null, null, null, null, null, true, true, SCOPE)).isEqualTo(3);
        // durum (yalnız sürenler) + kök neden (EXPIRY) süzgeçleri sayımda da uygulanır
        assertThat(alerts.countIncidents(Boolean.FALSE, null, null, "EXPIRY", null, true, true, SCOPE)).isEqualTo(2);
        assertThat(alerts.countIncidents(Boolean.FALSE, null, null, "EXPIRY", null, true, false, SCOPE)).isZero();
    }

    @Test
    @DisplayName("kök neden sayaçları kapsamı izler: others kümesinde EXPIRY=2, HTTP_DOWN=1; mine kümesinde PING_DOWN=1")
    void typeCountsFollowScope() {
        var others = alerts.countIncidentsByType(null, null, null, null, true, true, SCOPE);
        assertThat(others).extracting(r -> r[0] + "=" + r[1]).containsExactlyInAnyOrder("EXPIRY=2", "HTTP_DOWN=1");
        var mine = alerts.countIncidentsByType(null, null, null, null, true, false, SCOPE);
        assertThat(mine).extracting(r -> r[0] + "=" + r[1]).containsExactlyInAnyOrder("HTTP_DOWN=1", "PING_DOWN=1", "EXPIRY=1");
    }
}
