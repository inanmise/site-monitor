package com.certmonitor.repository;

import com.certmonitor.model.AuditLog;
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

    @Test
    @DisplayName("findAdvanced: filtresiz → tümü, en yeni üstte")
    void findAdvanced_noFilter_all() {
        Page<AuditLog> p = repo.findAdvanced(null, null, false, NO_TYPES, null, null, null, null, null, null, false, null, PAGE);
        assertThat(p.getTotalElements()).isEqualTo(4);
        assertThat(p.getContent().get(0).getEventType()).isEqualTo("MONITOR_UPDATE");   // en yeni
    }

    @Test
    @DisplayName("findAdvanced: actorId exact → yalnız o kullanıcı")
    void findAdvanced_actorId() {
        Page<AuditLog> p = repo.findAdvanced(null, 2L, false, NO_TYPES, null, null, null, null, null, null, false, null, PAGE);
        assertThat(p.getTotalElements()).isEqualTo(2);
        assertThat(p.getContent()).allMatch(x -> x.getActorId() == 2L);
    }

    @Test
    @DisplayName("findAdvanced: çoklu eventType IN")
    void findAdvanced_multiEventType() {
        Page<AuditLog> p = repo.findAdvanced(null, null, true, List.of("USER_UPDATE", "ACCESS_DENIED"),
                null, null, null, null, null, null, false, null, PAGE);
        assertThat(p.getTotalElements()).isEqualTo(2);
    }

    @Test
    @DisplayName("findAdvanced: kaynak tür+id → o kaynağın olayları")
    void findAdvanced_resource() {
        Page<AuditLog> p = repo.findAdvanced(null, null, false, NO_TYPES, "PORT_MONITOR", "7", null, null, null, null, false, null, PAGE);
        assertThat(p.getTotalElements()).isEqualTo(2);   // create + update
    }

    @Test
    @DisplayName("findAdvanced: outcome=BLOCKED → yalnız güvenlik olayı")
    void findAdvanced_outcome() {
        Page<AuditLog> p = repo.findAdvanced(null, null, false, NO_TYPES, null, null, "BLOCKED", null, null, null, false, null, PAGE);
        assertThat(p.getTotalElements()).isEqualTo(1);
        assertThat(p.getContent().get(0).getEventType()).isEqualTo("ACCESS_DENIED");
    }

    @Test
    @DisplayName("findAdvanced: serbest metin q (resource_id) LIKE")
    void findAdvanced_freeText() {
        Page<AuditLog> p = repo.findAdvanced(null, null, false, NO_TYPES, null, null, null, null, null, null, false, "%get /x%", PAGE);
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
