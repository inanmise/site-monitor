package com.sitemonitor.repository;

import com.sitemonitor.model.UserPushDelivery;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestPropertySource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Saat tavanı özeti + "Push geçmişim" sorguları (2026-10-04) — gerçek JPQL H2 üstünde (servis testleri depoyu mock'luyor).
 * Kişi kendi satırlarını (harf duyarsız) ve YALNIZ üyesi olduğu takımların olay düzeyi karar satırlarını görür; başka
 * kişinin ya da başka takımın satırı asla dönmez.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class UserPushOverflowHistoryRepositoryTest {

    @Autowired UserPushDeliveryRepository repo;

    private int n = 0;

    private UserPushDelivery row(String username, String status, String trigger, Long teamId, String createdAt) {
        UserPushDelivery d = new UserPushDelivery();
        d.setTrigger(trigger);
        d.setDedupeKey("k" + (++n));
        d.setAlertEventId((long) n);
        d.setUsername(username);
        d.setStatus(status);
        d.setTeamId(teamId);
        d.setCreatedAt(createdAt);
        d.setMonitorName("izleme-" + n);
        return repo.save(d);
    }

    @Test
    @DisplayName("özet adayları: yalnız özetsiz RATE_LIMITED satırlar, kullanıcı başına ilk damga + adet; sistem satırı ve pencere dışı hariç")
    void overflowCandidates() {
        row("N00001", "RATE_LIMITED", "OPEN", 1L, "2026-10-04T10:00:00");
        row("N00001", "RATE_LIMITED", "OPEN", 1L, "2026-10-04T10:05:00");
        UserPushDelivery done = row("N00001", "RATE_LIMITED", "OPEN", 1L, "2026-10-04T09:59:00");
        done.setOverflowSummaryId(999L);
        repo.save(done);
        row("N00001", "SENT", "OPEN", 1L, "2026-10-04T09:00:00");
        row("N00002", "RATE_LIMITED", "OPEN", 1L, "2026-10-01T10:00:00");   // pencere dışı
        row("-", "RATE_LIMITED", "OPEN", 1L, "2026-10-04T10:00:00");        // sistem satırı

        Map<String, Object[]> byUser = new HashMap<>();
        for (Object[] r : repo.overflowCandidates("2026-10-03T00:00:00")) byUser.put((String) r[0], r);
        assertThat(byUser).containsOnlyKeys("N00001");
        assertThat(byUser.get("N00001")[1]).isEqualTo("2026-10-04T10:00:00");
        assertThat(((Number) byUser.get("N00001")[2]).longValue()).isEqualTo(2L);
    }

    @Test
    @DisplayName("sahiplenme EN ÇOK BİR KEZ: ikinci claimOverflow 0 döner; bağ sayılır ve listelenir; son özet damgası")
    void claimOverflow_atMostOnce() {
        UserPushDelivery a = row("N00001", "RATE_LIMITED", "OPEN", 1L, "2026-10-04T10:00:00");
        UserPushDelivery b = row("N00001", "RATE_LIMITED", "OPEN", 1L, "2026-10-04T10:01:00");
        UserPushDelivery s = row("N00001", "PENDING", "OVERFLOW_SUMMARY", null, "2026-10-04T10:20:00");
        assertThat(repo.findUnsummarizedOverflow("N00001", "2026-10-04T00:00:00", PageRequest.of(0, 10)))
                .extracting(UserPushDelivery::getId).containsExactly(a.getId(), b.getId());

        assertThat(repo.claimOverflow(List.of(a.getId(), b.getId()), s.getId())).isEqualTo(2);
        assertThat(repo.claimOverflow(List.of(a.getId(), b.getId()), 12345L)).as("ikinci tur/pod").isZero();
        assertThat(repo.countByOverflowSummaryId(s.getId())).isEqualTo(2L);
        assertThat(repo.findByOverflowSummaryIdOrderByIdAsc(s.getId())).extracting(UserPushDelivery::getStatus)
                .containsOnly("RATE_LIMITED");
        assertThat(repo.findUnsummarizedOverflow("N00001", "2026-10-04T00:00:00", PageRequest.of(0, 10))).isEmpty();
        assertThat(repo.lastOverflowSummaryAt("N00001")).isEqualTo("2026-10-04T10:20:00");
        assertThat(repo.lastOverflowSummaryAt("N00009")).isNull();
    }

    @Test
    @DisplayName("Push geçmişim: kendi satırları (harf duyarsız) + üyesi olunan takımın karar satırları; başka kişi / başka takım / eskalasyon kişi kararı ASLA")
    void myHistory_ownAndMemberTeamDecisionsOnly() {
        UserPushDelivery own1 = row("N00001", "SENT", "OPEN", 1L, "2026-10-04T10:00:00");
        UserPushDelivery own2 = row("n00001", "SKIPPED_USER_SNOOZE", "OPEN", 2L, "2026-10-04T10:01:00");
        UserPushDelivery own3 = row("N00001", "PENDING", "OPEN", 1L, "2026-10-04T10:02:00");
        UserPushDelivery team = row("-", "SKIPPED_STORM", "OPEN", 1L, "2026-10-04T10:03:00");
        row("-", "SKIPPED_SYSTEM_MAINTENANCE", "OPEN", 9L, "2026-10-04T10:04:00");          // başka takımın kararı
        row("-", "SKIPPED_NO_USER_MATCH", "ESCALATION_STEP", 1L, "2026-10-04T10:05:00");   // tek kişinin kaydı
        row("N00002", "SENT", "OPEN", 1L, "2026-10-04T10:06:00");                          // başka kişi
        row("N00001", "SENT", "OPEN", 1L, "2026-09-01T10:00:00");                          // pencere dışı

        String since = "2026-10-01T00:00:00";
        assertThat(repo.myHistory("N00001", List.of(1L), since, "all", PageRequest.of(0, 50)).getContent())
                .extracting(UserPushDelivery::getId)
                .containsExactly(team.getId(), own3.getId(), own2.getId(), own1.getId());
        assertThat(repo.myHistory("N00001", List.of(1L), since, "sent", PageRequest.of(0, 50)).getContent())
                .extracting(UserPushDelivery::getId).containsExactly(own1.getId());
        assertThat(repo.myHistory("N00001", List.of(1L), since, "not_sent", PageRequest.of(0, 50)).getContent())
                .extracting(UserPushDelivery::getId).containsExactly(team.getId(), own2.getId());
        assertThat(repo.myHistory("N00001", List.of(-1L), since, "all", PageRequest.of(0, 50)).getContent())
                .as("takımsız kişi: yalnız kendi satırları").extracting(UserPushDelivery::getId)
                .containsExactly(own3.getId(), own2.getId(), own1.getId());

        Map<String, Long> own = new HashMap<>();
        for (Object[] r : repo.myStatusCounts("N00001", since)) own.put((String) r[0], ((Number) r[1]).longValue());
        assertThat(own).containsOnly(Map.entry("SENT", 1L), Map.entry("SKIPPED_USER_SNOOZE", 1L), Map.entry("PENDING", 1L));
        Map<String, Long> teamCounts = new HashMap<>();
        for (Object[] r : repo.teamDecisionCounts(List.of(1L), since)) teamCounts.put((String) r[0], ((Number) r[1]).longValue());
        assertThat(teamCounts).containsOnly(Map.entry("SKIPPED_STORM", 1L));
    }

    @Test
    @DisplayName("Push geçmişim KPI: özete katılmış RATE_LIMITED satırları ayrıca sayılır")
    void mySummarizedCount() {
        UserPushDelivery a = row("N00001", "RATE_LIMITED", "OPEN", 1L, "2026-10-04T10:00:00");
        row("N00001", "RATE_LIMITED", "OPEN", 1L, "2026-10-04T10:01:00");
        a.setOverflowSummaryId(50L);
        repo.save(a);
        assertThat(repo.mySummarizedCount("n00001", "2026-10-01T00:00:00")).isEqualTo(1L);
    }
}
