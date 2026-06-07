package com.certmonitor.repository;

import com.certmonitor.model.NotificationLog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.TestPropertySource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for the bulk count query that powers
 * AdminController.enrichAlerts(). Verifies the JPQL GROUP BY produces the
 * expected sent/failed split per alert id, including the case where a row
 * matches neither bucket (SKIPPED_DISABLED) and the case where an alert id
 * appears in the query but has no rows.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class NotificationLogRepositoryTest {

    @Autowired NotificationLogRepository repo;

    @Test
    @DisplayName("countByAlertIds groups SENT and FAILED counts per alert id")
    void countByAlertIds_groupsCorrectly() {
        save(1L, "SENT");
        save(1L, "SENT");
        save(1L, "FAILED:smtp_timeout");
        save(2L, "SENT");
        save(2L, "FAILED:relay_refused");
        save(2L, "FAILED:bounce");

        Map<Long, long[]> result = toMap(repo.countByAlertIds(List.of(1L, 2L)));

        assertThat(result.get(1L)).containsExactly(2L, 1L);
        assertThat(result.get(2L)).containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("countByAlertIds ignores SKIPPED rows and unknown alert ids")
    void countByAlertIds_skippedAndUnknownIds() {
        save(10L, "SENT");
        save(10L, "SKIPPED_DISABLED");
        save(10L, "FAILED:bounce");

        Map<Long, long[]> result = toMap(repo.countByAlertIds(List.of(10L, 999L)));

        assertThat(result.get(10L)).containsExactly(1L, 1L);
        assertThat(result).doesNotContainKey(999L);
    }

    @Test
    @DisplayName("countByAlertIds returns empty when no ids match")
    void countByAlertIds_emptyResult() {
        save(5L, "SENT");

        List<Object[]> rows = repo.countByAlertIds(List.of(77L, 88L));

        assertThat(rows).isEmpty();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void save(long alertId, String status) {
        NotificationLog n = new NotificationLog();
        n.setAlertEventId(alertId);
        n.setEmailStatus(status);
        n.setSentAt("2026-06-07T12:00:00");
        n.setRecipientEmail("ops@example.com");
        n.setSubject("test");
        repo.save(n);
    }

    private Map<Long, long[]> toMap(List<Object[]> rows) {
        Map<Long, long[]> out = new HashMap<>();
        for (Object[] r : rows) {
            long id     = ((Number) r[0]).longValue();
            long sent   = r[1] == null ? 0L : ((Number) r[1]).longValue();
            long failed = r[2] == null ? 0L : ((Number) r[2]).longValue();
            out.put(id, new long[]{ sent, failed });
        }
        return out;
    }
}
