package com.sitemonitor.repository;

import com.sitemonitor.model.AuditLog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AuditLogRepository#findAdvanced} + kaynak/aktör geçmişi — gerçek JPQL'i H2 üstünde doğrular
 * (controller testleri repo'yu mock'lar; burada sorgu doğruluğu test edilir).
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class AuditLogRepositoryTest {

    @Autowired AuditLogRepository repo;

    private static AuditLog a(long seq, String time, String type, String actor, Long actorId,
                              String resType, String resId, String outcome) {
        AuditLog x = new AuditLog();
        x.setSeq(seq);
        x.setEventTime(time);
        x.setEventType(type);
        x.setActor(actor);
        x.setActorId(actorId);
        x.setResourceType(resType);
        x.setResourceId(resId);
        x.setOutcome(outcome);
        return x;
    }

    @BeforeEach
    void seed() {
        repo.save(a(1, "2026-07-28T10:00:00", "USER_UPDATE", "alice", 1L, "USER", "5", "SUCCESS"));
        repo.save(a(2, "2026-07-28T11:00:00", "MONITOR_CREATE", "bob", 2L, "PORT_MONITOR", "7", "SUCCESS"));
        repo.save(a(3, "2026-07-28T12:00:00", "ACCESS_DENIED", "bob", 2L, "ENDPOINT", "GET /x", "BLOCKED"));
        repo.save(a(4, "2026-07-28T13:00:00", "MONITOR_UPDATE", "alice", 1L, "PORT_MONITOR", "7", "SUCCESS"));
    }

    private static final PageRequest PAGE = PageRequest.of(0, 20);
    private static final List<String> NO_TYPES = List.of("");
    /** Ekip kapsamının boş IN listeleri için kukla değerler (TeamActorScope ile aynı). */
    private static final List<Long> NO_IDS = List.of(-1L);
    private static final List<String> NO_NAMES = List.of("");

    @Test
    @DisplayName("findAdvanced: ekip kapsamı — aktörün takımı, kimliği ya da harf duyarsız adı eşleşen satırlar; yabancı YOK")
    void findAdvanced_teamScope() {
        AuditLog carol = a(5, "2026-07-28T14:00:00", "LOGIN", "carol", 3L, "USER", "3", "SUCCESS");
        carol.setActorTeamId(5L);                      // olay anındaki takımı kapsamda
        repo.save(carol);
        // Kimliksiz giriş olayı: yalnız kullanıcı adı taşır, büyük harfle yazılmış.
        repo.save(a(6, "2026-07-28T15:00:00", "LOGIN_FAILED", "DAVE", null, "USER", "dave", "FAILURE"));

        // Kapsam: takım 5 + üye kimliği 2 (bob) + üye adı "dave". alice (kimlik 1) ekip dışı.
        Page<AuditLog> p = repo.findAdvanced(null, null, false, NO_TYPES, null, null, null, null, null, null, false, null,
                false, List.of(5L), List.of(2L), List.of("dave"), PAGE);
        assertThat(p.getContent()).extracting(AuditLog::getActor)
                .containsExactlyInAnyOrder("carol", "bob", "bob", "DAVE");

        // Kukla kapsam (takımı/üyesi olmayan kullanıcı) hiçbir şey görmez.
        assertThat(repo.findAdvanced(null, null, false, NO_TYPES, null, null, null, null, null, null, false, null,
                false, NO_IDS, NO_IDS, NO_NAMES, PAGE).getTotalElements()).isZero();

        // Kapsam süzgeçlerle VE çalışır: ekip içinde yalnız başarısız olaylar.
        assertThat(repo.findAdvanced(null, null, false, NO_TYPES, null, null, "FAILURE", null, null, null, false, null,
                false, List.of(5L), List.of(2L), List.of("dave"), PAGE).getContent())
                .extracting(AuditLog::getActor).containsExactly("DAVE");
    }

    @Test
    @DisplayName("findAdvanced: filtresiz → tümü, en yeni üstte")
    void findAdvanced_noFilter_all() {
        Page<AuditLog> p = repo.findAdvanced(null, null, false, NO_TYPES, null, null, null, null, null, null, false, null, true, NO_IDS, NO_IDS, NO_NAMES, PAGE);
        assertThat(p.getTotalElements()).isEqualTo(4);
        assertThat(p.getContent().get(0).getEventType()).isEqualTo("MONITOR_UPDATE");   // en yeni
    }

    @Test
    @DisplayName("findAdvanced: actorId exact → yalnız o kullanıcı")
    void findAdvanced_actorId() {
        Page<AuditLog> p = repo.findAdvanced(null, 2L, false, NO_TYPES, null, null, null, null, null, null, false, null, true, NO_IDS, NO_IDS, NO_NAMES, PAGE);
        assertThat(p.getTotalElements()).isEqualTo(2);
        assertThat(p.getContent()).allMatch(x -> x.getActorId() == 2L);
    }

    @Test
    @DisplayName("findAdvanced: çoklu eventType IN")
    void findAdvanced_multiEventType() {
        Page<AuditLog> p = repo.findAdvanced(null, null, true, List.of("USER_UPDATE", "ACCESS_DENIED"),
                null, null, null, null, null, null, false, null, true, NO_IDS, NO_IDS, NO_NAMES, PAGE);
        assertThat(p.getTotalElements()).isEqualTo(2);
    }

    @Test
    @DisplayName("findAdvanced: kaynak tür+id → o kaynağın olayları")
    void findAdvanced_resource() {
        Page<AuditLog> p = repo.findAdvanced(null, null, false, NO_TYPES, "PORT_MONITOR", "7", null, null, null, null, false, null, true, NO_IDS, NO_IDS, NO_NAMES, PAGE);
        assertThat(p.getTotalElements()).isEqualTo(2);   // create + update
    }

    @Test
    @DisplayName("findAdvanced: outcome=BLOCKED → yalnız güvenlik olayı")
    void findAdvanced_outcome() {
        Page<AuditLog> p = repo.findAdvanced(null, null, false, NO_TYPES, null, null, "BLOCKED", null, null, null, false, null, true, NO_IDS, NO_IDS, NO_NAMES, PAGE);
        assertThat(p.getTotalElements()).isEqualTo(1);
        assertThat(p.getContent().get(0).getEventType()).isEqualTo("ACCESS_DENIED");
    }

    @Test
    @DisplayName("findAdvanced: serbest metin q (resource_id) LIKE")
    void findAdvanced_freeText() {
        Page<AuditLog> p = repo.findAdvanced(null, null, false, NO_TYPES, null, null, null, null, null, null, false, "%get /x%", true, NO_IDS, NO_IDS, NO_NAMES, PAGE);
        assertThat(p.getTotalElements()).isEqualTo(1);
    }

    @Test
    @DisplayName("kaynak geçmişi: findByResourceType... en yeni üstte")
    void resourceHistory() {
        List<AuditLog> rows = repo.findByResourceTypeAndResourceIdOrderByEventTimeDesc("PORT_MONITOR", "7", PageRequest.of(0, 10));
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).getEventType()).isEqualTo("MONITOR_UPDATE");
    }

    @Test
    @DisplayName("aktör geçmişi: findByActorId... bir kullanıcının tüm eylemleri")
    void actorHistory() {
        List<AuditLog> rows = repo.findByActorIdOrderByEventTimeDesc(1L, PageRequest.of(0, 10));
        assertThat(rows).hasSize(2);
        assertThat(rows).allMatch(x -> x.getActorId() == 1L);
    }

    private static AuditLog fl(long seq, String time, String actor, String ip, String reason) {
        AuditLog x = new AuditLog();
        x.setSeq(seq);
        x.setEventTime(time);
        x.setEventType("LOGIN_FAILED");
        x.setActor(actor);
        x.setIpAddress(ip);
        x.setFailureReason(reason);
        x.setOutcome("FAILURE");
        return x;
    }

    @Test
    @DisplayName("anomali toplu sorguları: hesap/IP/distinct-user/distinct-IP/reason-önek")
    void anomalyAggregates() {
        repo.save(fl(10, "2026-07-28T09:00:00", "alice", "1.1.1.1", "BAD_PASSWORD: attempt #1/5"));
        repo.save(fl(11, "2026-07-28T09:01:00", "alice", "1.1.1.1", "BAD_PASSWORD: attempt #2/5"));
        repo.save(fl(12, "2026-07-28T09:02:00", "alice", "2.2.2.2", "BAD_PASSWORD: attempt #3/5"));
        repo.save(fl(13, "2026-07-28T09:03:00", "bob",   "1.1.1.1", "UNKNOWN_USER: attempt #1/5"));
        repo.save(fl(14, "2026-07-28T09:04:00", "carol", "1.1.1.1", "UNKNOWN_USER: attempt #1/5"));

        String since = "2026-07-28T00:00:00";
        String now   = "2026-07-28T23:59:59";

        assertThat(repo.countFailedLoginsBetween(since, now)).isEqualTo(5);

        List<Object[]> byActor = repo.countFailedByActorSince(since);
        assertThat(byActor.get(0)[0]).isEqualTo("alice");
        assertThat(((Number) byActor.get(0)[1]).longValue()).isEqualTo(3);

        List<Object[]> byIp = repo.countFailedByIpSince(since);
        assertThat(byIp.get(0)[0]).isEqualTo("1.1.1.1");
        assertThat(((Number) byIp.get(0)[1]).longValue()).isEqualTo(4);

        List<Object[]> stuffing = repo.countDistinctUsersPerIpSince(since);
        assertThat(stuffing.get(0)[0]).isEqualTo("1.1.1.1");
        assertThat(((Number) stuffing.get(0)[1]).longValue()).isEqualTo(3);   // alice, bob, carol

        List<Object[]> distributed = repo.countDistinctIpsPerActorSince(since);
        assertThat(distributed.get(0)[0]).isEqualTo("alice");
        assertThat(((Number) distributed.get(0)[1]).longValue()).isEqualTo(2);   // 1.1.1.1, 2.2.2.2

        assertThat(repo.countFailedByReasonLikeBetween("BAD_PASSWORD:%", since, now)).isEqualTo(3);
        assertThat(repo.countFailedByReasonLikeBetween("UNKNOWN_USER:%", since, now)).isEqualTo(2);
    }

    @Test
    @DisplayName("günlük yoğunluk: countByDaySince → gün başına gruplar, tarihe göre artan")
    void countByDay() {
        repo.save(a(5, "2026-07-27T09:00:00", "LOGIN",  "carol", 3L, null, null, "SUCCESS"));
        repo.save(a(6, "2026-07-27T09:30:00", "LOGOUT", "carol", 3L, null, null, "SUCCESS"));

        List<Object[]> rows = repo.countByDaySince("2026-07-01T00:00:00");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)[0]).isEqualTo("2026-07-27");                       // en eski gün önce
        assertThat(((Number) rows.get(0)[1]).longValue()).isEqualTo(2);
        assertThat(rows.get(1)[0]).isEqualTo("2026-07-28");
        assertThat(((Number) rows.get(1)[1]).longValue()).isEqualTo(4);
    }
}
