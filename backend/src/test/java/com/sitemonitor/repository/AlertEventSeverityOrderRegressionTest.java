package com.sitemonitor.repository;

import com.sitemonitor.model.AlertEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

// Regression: ISSUE-001 — açık alarmlar alfabetik "DESC" sıralandığı için CRITICAL listenin sonuna düşüyor,
// "Sizin için — bugün / Açık alarm" kartının ilk 5 satırında hiç görünmüyordu.
// Found by /qa on 2026-09-24
// Report: .gstack/qa-reports/qa-report-localhost-5173-2026-09-24.md
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class AlertEventSeverityOrderRegressionTest {

    @Autowired AlertEventRepository repo;

    private AlertEvent alert(String domain, String level, String createdAt, boolean resolved) {
        AlertEvent e = new AlertEvent();
        e.setDomain(domain);
        e.setAlertType("HTTP_DOWN");
        e.setAlertLevel(level);
        e.setAcknowledged(false);
        e.setResolved(resolved);
        e.setCreatedAt(createdAt);
        return e;
    }

    @Test
    @DisplayName("findAllOpenOrderBySeverity: CRITICAL → HIGH → WARNING → LOW → INFO → bilinmeyen; aynı seviyede en yeni önce; çözülmüş hiç dönmez")
    void openAlertsComeMostUrgentFirst() {
        // Kasıtlı olarak alfabetik-DESC'in (WARNING > LOW > INFO > HIGH > CRITICAL) tam tersi bir karışımla kaydet
        repo.save(alert("w.example.com", "WARNING", "2026-07-05T10:00:00", false));
        repo.save(alert("i.example.com", "INFO", "2026-07-05T10:00:00", false));
        repo.save(alert("c-old.example.com", "CRITICAL", "2026-07-01T10:00:00", false));
        repo.save(alert("l.example.com", "LOW", "2026-07-05T10:00:00", false));
        repo.save(alert("h.example.com", "HIGH", "2026-07-05T10:00:00", false));
        repo.save(alert("c-new.example.com", "CRITICAL", "2026-07-03T10:00:00", false));
        repo.save(alert("x.example.com", "WEIRD", "2026-07-09T10:00:00", false));        // bilinmeyen seviye → en sona
        repo.save(alert("c-lower.example.com", "critical", "2026-07-02T10:00:00", false)); // küçük harf de kritik sayılır
        repo.save(alert("closed.example.com", "CRITICAL", "2026-07-09T10:00:00", true));  // çözülmüş → dönmez

        assertThat(repo.findAllOpenOrderBySeverity()).extracting(AlertEvent::getDomain).containsExactly(
                "c-new.example.com", "c-lower.example.com", "c-old.example.com",
                "h.example.com", "w.example.com", "l.example.com", "i.example.com", "x.example.com");
    }
}
