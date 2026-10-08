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

    @Test
    @DisplayName("cancelPendingForResolved (2026-10-09): yalnız O alarmın PENDING, RESOLVE olmayan satırları iptal edilir")
    void cancelPendingForResolved_onlyPendingNonResolveOfThatAlarm() {
        UserPushDelivery open = delivery(500L, "OPEN", "PENDING", "N1");
        open.setNextAttemptAt("2026-10-09T10:00:00");
        open = repo.save(open);
        UserPushDelivery esc = repo.save(delivery(500L, "ESCALATION", "PENDING", "N2"));
        UserPushDelivery sent = repo.save(delivery(500L, "OPEN", "SENT", "N3"));
        UserPushDelivery resolve = repo.save(delivery(500L, "RESOLVE", "PENDING", "N1"));
        UserPushDelivery other = repo.save(delivery(501L, "OPEN", "PENDING", "N1"));

        assertThat(repo.cancelPendingForResolved(500L, "SKIPPED_RESOLVED_BEFORE_SEND")).isEqualTo(2);

        assertThat(repo.findById(open.getId()).orElseThrow().getStatus()).isEqualTo("SKIPPED_RESOLVED_BEFORE_SEND");
        assertThat(repo.findById(open.getId()).orElseThrow().getNextAttemptAt()).isNull();   // kiralı tur da göndermez
        assertThat(repo.findById(esc.getId()).orElseThrow().getStatus()).isEqualTo("SKIPPED_RESOLVED_BEFORE_SEND");
        assertThat(repo.findById(sent.getId()).orElseThrow().getStatus()).isEqualTo("SENT");
        assertThat(repo.findById(resolve.getId()).orElseThrow().getStatus()).isEqualTo("PENDING");
        assertThat(repo.findById(other.getId()).orElseThrow().getStatus()).isEqualTo("PENDING");
    }

    private static UserPushDelivery delivery(long eventId, String trigger, String status, String username) {
        UserPushDelivery d = new UserPushDelivery();
        d.setTrigger(trigger);
        d.setDedupeKey(trigger + ":" + username);
        d.setAlertEventId(eventId);
        d.setUsername(username);
        d.setStatus(status);
        d.setCreatedAt("2026-10-09T09:00:00");
        return d;
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

    // ── 2026-10-01: outbox sahiplenme (onaylı öneri 3) ─────────────────────────────────────────────

    @Test
    @DisplayName("Öneri 3: claimDue zamanı gelmiş PENDING satırı KİRALAR; ikinci pod alamaz; kira bitince yeniden alınır")
    void claimDue_leasesDueRowsOnce() {
        int n = 300;
        UserPushDelivery fresh = pending(null, ++n);
        UserPushDelivery elapsed = pending("2026-09-28T09:59:00", ++n);
        UserPushDelivery backoff = pending("2026-09-28T10:05:00", ++n);   // backoff sürüyor → kiralanmaz
        var ids = java.util.List.of(fresh.getId(), elapsed.getId(), backoff.getId());
        String now = "2026-09-28T10:00:00";
        String leaseA = "2026-09-28T10:02:00.000000001";

        assertThat(repo.claimDue(ids, now, leaseA)).isEqualTo(2);
        em.clear();
        assertThat(repo.findByIdInAndNextAttemptAtOrderByIdAsc(ids, leaseA))
                .extracting(UserPushDelivery::getId).containsExactly(fresh.getId(), elapsed.getId());
        // ikinci pod aynı anda: kiralı satırların hiçbirini alamaz, tarama da onları vermez
        assertThat(repo.claimDue(ids, now, "2026-09-28T10:02:00.000000002")).isZero();
        assertThat(repo.findDuePending(now, org.springframework.data.domain.PageRequest.of(0, 50))).isEmpty();
        // pod gönderirken öldü: kira bitince satırlar yeniden alınır (backoff'taki hâlâ beklemede)
        assertThat(repo.findDuePending("2026-09-28T10:03:00", org.springframework.data.domain.PageRequest.of(0, 50)))
                .extracting(UserPushDelivery::getId).containsExactly(fresh.getId(), elapsed.getId());
    }

    @Test
    @DisplayName("Öneri 3: PENDING olmayan satır kiralanmaz (gönderilmiş / başarısız satıra dokunulmaz)")
    void claimDue_ignoresNonPending() {
        UserPushDelivery sent = pending(null, 400);
        sent.setStatus("SENT");
        repo.save(sent);
        assertThat(repo.claimDue(java.util.List.of(sent.getId()), "2026-09-28T10:00:00", "2026-09-28T10:02:00.000000001")).isZero();
    }
}
