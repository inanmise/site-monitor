package com.sitemonitor.repository;

import com.sitemonitor.model.AlertEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ESKİ FIRTINA EMEKLİLİĞİ — taşıma ve serbest bırakma sorguları (H2, gerçek JPQL; 2026-09-29, O-3 / D-b7).
 *
 * <p>İkisi de KOŞULLU ve atomik olmalı: yalnız hâlâ AÇIK ve hâlâ ESKİ fırtınaya bağlı satır. Serbest bırakmada fırtına
 * koşulu yoktu: kilit süresini aşıp üst üste binen ikinci emeklilik koşusu, birincinin takım fırtınasına taşıdığı üyeyi
 * fırtınadan koparıyordu (üye bireysel alarmlamaya düşer, takım fırtınası bir üyesini kaybeder).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:legacystormmove;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=VALUE",
        "spring.datasource.driver-class-name=org.h2.Driver"})
class LegacyStormMoveQueriesTest {

    private static final long LEGACY = 901L, TEAM_STORM = 950L;
    private static final String NOTIFIED = "2026-09-29T06:00:00";

    @Autowired AlertEventRepository repo;

    private AlertEvent member(String domain, Long stormId, boolean resolved) {
        AlertEvent e = new AlertEvent();
        e.setDomain(domain);
        e.setAlertLevel("WARNING");
        e.setAlertType("HTTP_DOWN");
        e.setTeamId(7L);
        e.setAcknowledged(false);
        e.setResolved(resolved);
        e.setStormId(stormId);
        e.setCreatedAt("2026-09-29T05:00:00");
        return repo.saveAndFlush(e);
    }

    @Test
    @DisplayName("serbest bırakma yalnız ESKİ fırtınaya bağlı açık üyede: başka fırtınaya taşınmış üyeye dokunmaz")
    void release_onlyWhileStillInLegacyStorm() {
        AlertEvent inLegacy = member("https://a.example.com", LEGACY, false);
        AlertEvent movedAway = member("https://b.example.com", TEAM_STORM, false);   // ilk koşu taşıdı

        assertThat(repo.releaseFromStormAsNotified(inLegacy.getId(), LEGACY, NOTIFIED)).isEqualTo(1);
        assertThat(repo.releaseFromStormAsNotified(movedAway.getId(), LEGACY, NOTIFIED)).isZero();

        AlertEvent a = repo.findById(inLegacy.getId()).orElseThrow();
        assertThat(a.getStormId()).isNull();
        assertThat(a.getLastReAlertAt()).isEqualTo(NOTIFIED);
        AlertEvent b = repo.findById(movedAway.getId()).orElseThrow();
        assertThat(b.getStormId()).as("takım fırtınasından koparılmaz").isEqualTo(TEAM_STORM);
        assertThat(b.getLastReAlertAt()).isNull();
    }

    @Test
    @DisplayName("çözülmüş üye ne taşınır ne serbest bırakılır — eski fırtınada kalır (toplu çözümü oradan gider)")
    void resolvedMember_staysInLegacyStorm() {
        AlertEvent done = member("https://c.example.com", LEGACY, true);

        assertThat(repo.moveToStormIfOpen(done.getId(), LEGACY, TEAM_STORM)).isZero();
        assertThat(repo.releaseFromStormAsNotified(done.getId(), LEGACY, NOTIFIED)).isZero();
        assertThat(repo.findById(done.getId()).orElseThrow().getStormId()).isEqualTo(LEGACY);
    }
}
