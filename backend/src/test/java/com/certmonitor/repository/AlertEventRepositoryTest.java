package com.certmonitor.repository;

import com.certmonitor.model.AlertEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AlertEventRepository entegrasyon testleri (H2). M3: çift açık alarmda findOpenAlert güvenli;
 * M6: linkToStormIfOpen yalnız açık + bağsız satırı günceller (çözülmüş incident'i diriltmez).
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class AlertEventRepositoryTest {

    @Autowired AlertEventRepository repo;

    private AlertEvent alert(String domain, String type, String createdAt, boolean resolved) {
        AlertEvent e = new AlertEvent();
        e.setDomain(domain);
        e.setAlertType(type);
        e.setAlertLevel("CRITICAL");
        e.setAcknowledged(false);
        e.setResolved(resolved);
        e.setCreatedAt(createdAt);
        return e;
    }

    @Test
    @DisplayName("findOpenAlert returns the newest and does NOT throw on duplicate open alerts (M3)")
    void findOpenAlert_multipleOpen_returnsNewestNoThrow() {
        repo.save(alert("dup.example.com", "HTTP_DOWN", "2026-07-01T10:00:00", false));
        repo.save(alert("dup.example.com", "HTTP_DOWN", "2026-07-02T10:00:00", false));   // newer

        // Before the fix, the single-Optional @Query threw IncorrectResultSizeDataAccessException here.
        Optional<AlertEvent> result = repo.findOpenAlert("dup.example.com", "HTTP_DOWN");

        assertThat(result).isPresent();
        assertThat(result.get().getCreatedAt()).isEqualTo("2026-07-02T10:00:00");   // newest
    }

    @Test
    @DisplayName("findOpenAlert is empty when nothing is open (M3)")
    void findOpenAlert_none_empty() {
        repo.save(alert("closed.example.com", "HTTP_DOWN", "2026-07-01T10:00:00", true)); // resolved
        assertThat(repo.findOpenAlert("closed.example.com", "HTTP_DOWN")).isEmpty();
    }

    @Test
    @DisplayName("linkToStormIfOpen links an OPEN unlinked alert but NOT a resolved one — no resurrection (M6)")
    void linkToStormIfOpen_onlyOpenUnlinked() {
        AlertEvent open     = repo.save(alert("open.example.com",   "HTTP_DOWN", "2026-07-01T10:00:00", false));
        AlertEvent resolved = repo.save(alert("closed.example.com", "HTTP_DOWN", "2026-07-01T10:00:00", true));

        assertThat(repo.linkToStormIfOpen(open.getId(), 42L)).isEqualTo(1);
        assertThat(repo.linkToStormIfOpen(resolved.getId(), 42L)).isZero();   // resolved satır dokunulmaz

        assertThat(repo.findById(open.getId()).orElseThrow().getStormId()).isEqualTo(42L);
        AlertEvent stillResolved = repo.findById(resolved.getId()).orElseThrow();
        assertThat(stillResolved.getStormId()).isNull();
        assertThat(stillResolved.getResolved()).isTrue();   // full-save ile resolved=false'a DÖNMEDİ (dirilmedi)
    }

    @Test
    @DisplayName("linkToStormIfOpen skips an already-linked alert (M6)")
    void linkToStormIfOpen_skipsAlreadyLinked() {
        AlertEvent e = alert("x.example.com", "HTTP_DOWN", "2026-07-01T10:00:00", false);
        e.setStormId(7L);
        e = repo.save(e);
        assertThat(repo.linkToStormIfOpen(e.getId(), 99L)).isZero();          // storm_id NOT NULL → dokunma
        assertThat(repo.findById(e.getId()).orElseThrow().getStormId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("unlinkFromStorm clears only an OPEN member's link, leaves a resolved one (M6)")
    void unlinkFromStorm_onlyOpen() {
        AlertEvent open = alert("o.example.com", "HTTP_DOWN", "2026-07-01T10:00:00", false);
        open.setStormId(5L); open = repo.save(open);
        AlertEvent resolved = alert("r.example.com", "HTTP_DOWN", "2026-07-01T10:00:00", true);
        resolved.setStormId(5L); resolved = repo.save(resolved);

        assertThat(repo.unlinkFromStorm(open.getId())).isEqualTo(1);
        assertThat(repo.unlinkFromStorm(resolved.getId())).isZero();

        assertThat(repo.findById(open.getId()).orElseThrow().getStormId()).isNull();
        assertThat(repo.findById(resolved.getId()).orElseThrow().getStormId()).isEqualTo(5L);
    }
}
