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

    // ── 2026-09-28: outbox backoff satırda ────────────────────────────────────────────────────
    // "Şimdi" sorguya PARAMETRE olarak verilir (gerçek saat okunmaz) — sabit damgalar zaman bombası değil.

    @Autowired jakarta.persistence.EntityManager em;

    private UserPushDelivery pending(String nextAttemptAt, int n) {
        UserPushDelivery d = new UserPushDelivery();
        d.setTrigger("OPEN");
        d.setDedupeKey("k" + n);
        d.setAlertEventId((long) n);
        d.setUsername("N00001");
        d.setStatus("PENDING");
        d.setCreatedAt("2026-09-28T09:00:00");
        d.setNextAttemptAt(nextAttemptAt);
        return repo.save(d);
    }

    @Test
    @DisplayName("P4: outbox yalnız ZAMANI GELMİŞ PENDING satırları alır — damgasız hemen, backoff'taki ALINMAZ; id sırası + sayfa tavanı")
    void findDuePending_honoursBackoffStamp() {
        int n = 100;
        UserPushDelivery fresh = pending(null, ++n);                       // yeni satır → hemen
        UserPushDelivery elapsed = pending("2026-09-28T09:59:59", ++n);    // backoff bitmiş
        UserPushDelivery edge = pending("2026-09-28T10:00:00", ++n);       // tam sınır → alınır
        pending("2026-09-28T10:00:30", ++n);                               // backoff sürüyor → alınmaz
        row("N00001", "SENT", "2026-09-28T09:00:00", ++n);                 // PENDING değil

        String now = "2026-09-28T10:00:00";
        assertThat(repo.findDuePending(now, org.springframework.data.domain.PageRequest.of(0, 50)))
                .extracting(UserPushDelivery::getId)
                .containsExactly(fresh.getId(), elapsed.getId(), edge.getId());
        assertThat(repo.findDuePending(now, org.springframework.data.domain.PageRequest.of(0, 2)))
                .extracting(UserPushDelivery::getId)
                .containsExactly(fresh.getId(), elapsed.getId());
    }

    @Test
    @DisplayName("P5: dar sonuç güncellemesi durum/sayaç/zamanları yazar, diğer alanlara dokunmaz")
    void updateOutcome_writesOutcomeColumnsOnly() {
        UserPushDelivery d = pending(null, 200);
        d.setNotificationId("ilk");
        repo.saveAndFlush(d);

        int updated = repo.updateOutcome(d.getId(), "PENDING", 1, 503, "HTTP 503", null, "2026-09-28T10:00:30");
        em.clear();
        UserPushDelivery back = repo.findById(d.getId()).orElseThrow();

        assertThat(updated).isEqualTo(1);
        assertThat(back.getStatus()).isEqualTo("PENDING");
        assertThat(back.getAttempts()).isEqualTo(1);
        assertThat(back.getHttpStatus()).isEqualTo(503);
        assertThat(back.getError()).isEqualTo("HTTP 503");
        assertThat(back.getSentAt()).isNull();
        assertThat(back.getNextAttemptAt()).isEqualTo("2026-09-28T10:00:30");
        assertThat(back.getNotificationId()).isEqualTo("ilk");
    }

    @Test
    @DisplayName("P13: fırtına çözüm alıcı sorgusu — anahtar öneki fırtınaya ÖZEL (storm:1: ≠ storm:12:), takım ve SENT süzgeci")
    void stormPriorQuery_prefixTeamAndStatus() {
        UserPushDelivery open = stormRow("storm:1:INITIAL", 5L, "SENT", "N00001");
        UserPushDelivery realert = stormRow("storm:1:DAILY_REALERT:2026-09-28", 5L, "SENT", "N00002");
        stormRow("storm:12:INITIAL", 5L, "SENT", "N00003");        // başka fırtına
        stormRow("storm:1:INITIAL", 6L, "SENT", "N00004");         // başka takım
        stormRow("storm:1:INITIAL", 5L, "FAILED", "N00005");       // gönderilmemiş
        stormRow("storm-resolved:1", 5L, "SENT", "N00006");        // çözüm satırı

        assertThat(repo.findByDedupeKeyStartingWithAndTeamIdAndStatusOrderByIdAsc("storm:1:", 5L, "SENT"))
                .extracting(UserPushDelivery::getId).containsExactly(open.getId(), realert.getId());
    }

    private UserPushDelivery stormRow(String key, Long teamId, String status, String username) {
        UserPushDelivery d = new UserPushDelivery();
        d.setTrigger("STORM");
        d.setDedupeKey(key);
        d.setTeamId(teamId);
        d.setUsername(username);
        d.setStatus(status);
        d.setCreatedAt("2026-09-28T09:00:00");
        return repo.save(d);
    }
}
