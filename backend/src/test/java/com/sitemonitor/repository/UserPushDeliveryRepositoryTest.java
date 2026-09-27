package com.sitemonitor.repository;

import com.sitemonitor.model.UserPushDelivery;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kişi-push saat tavanı sayımı — gerçek JPQL H2 üstünde (servis testleri depoyu mock'luyor).
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class UserPushDeliveryRepositoryTest {

    @Autowired UserPushDeliveryRepository repo;

    private void row(String username, String status, String createdAt, int n) {
        UserPushDelivery d = new UserPushDelivery();
        d.setTrigger("OPEN");
        d.setDedupeKey("k" + n);
        d.setAlertEventId((long) n);
        d.setUsername(username);
        d.setStatus(status);
        d.setCreatedAt(createdAt);
        repo.save(d);
    }

    @Test
    @DisplayName("O-3: saat tavanı yalnız GÖNDERİLEBİLİR satırları sayar (PENDING/SENT/FAILED) — RATE_LIMITED kendini beslemez")
    void countRecentForUser_countsOnlyDeliverableRows() {
        int n = 0;
        row("N00001", "SENT", "2026-09-25T10:00:00", ++n);
        row("N00001", "PENDING", "2026-09-25T10:01:00", ++n);
        row("N00001", "FAILED", "2026-09-25T10:02:00", ++n);
        for (int i = 0; i < 5; i++) row("N00001", "RATE_LIMITED", "2026-09-25T10:03:0" + i, ++n);   // tavanın kendi retleri
        row("N00001", "CIRCUIT_OPEN", "2026-09-25T10:04:00", ++n);
        row("N00001", "SKIPPED_USER_OPT_OUT", "2026-09-25T10:05:00", ++n);
        row("N00001", "SENT", "2026-09-25T08:00:00", ++n);   // pencere dışı
        row("N00002", "SENT", "2026-09-25T10:06:00", ++n);   // başka kullanıcı

        assertThat(repo.countRecentForUser("N00001", "2026-09-25T09:30:00")).isEqualTo(3L);
    }
}
