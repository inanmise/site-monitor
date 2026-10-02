package com.sitemonitor.service;

import com.sitemonitor.model.IncidentRecord;
import com.sitemonitor.repository.IncidentRecordRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Alarm ↔ olay kaydı bağı H2'de GERÇEK JPQL ile (2026-10-01): {@code alert_event_id} create/update'te kayda geçer,
 * anahtarsız güncelleme bağı korur; {@code findLinkedToAlert} takım kapsamını (kayıt takımı ya da girenin takımı)
 * listeyle aynı kuralla uygular ve en yeniyi önce döndürür.
 */
@DataJpaTest
@Import(IncidentService.class)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class IncidentAlertLinkRepositoryTest {

    @Autowired IncidentService service;
    @Autowired IncidentRecordRepository repo;

    private Map<String, Object> body(Long teamId, Object alertEventId) {
        Map<String, Object> m = new HashMap<>();
        m.put("title", "Ödeme · İçerik doğrulama");
        m.put("occurred_at", "2026-10-01T06:00:00");
        m.put("severity", "CRITICAL");
        m.put("status", "OPEN");
        m.put("category", "APPLICATION");
        m.put("team_id", teamId);
        if (alertEventId != null) m.put("alert_event_id", alertEventId);
        return m;
    }

    @Test
    @DisplayName("create/update: alert_event_id kayda geçer (sayı ya da metin); anahtarsız güncelleme bağı KORUR")
    void createAndUpdate_persistLink() {
        IncidentRecord a = service.create(body(5L, 77), "sre1", 1L, 5L);
        IncidentRecord b = service.create(body(5L, "78"), "sre1", 1L, 5L);
        IncidentRecord c = service.create(body(5L, null), "sre1", 1L, 5L);
        assertThat(repo.findById(a.getId()).orElseThrow().getAlertEventId()).isEqualTo(77L);
        assertThat(repo.findById(b.getId()).orElseThrow().getAlertEventId()).isEqualTo(78L);
        assertThat(repo.findById(c.getId()).orElseThrow().getAlertEventId()).isNull();

        service.update(a.getId(), Map.of("status", "MITIGATED"), "sre1");
        assertThat(repo.findById(a.getId()).orElseThrow().getAlertEventId()).isEqualTo(77L);
    }

    @Test
    @DisplayName("findLinkedToAlert: yalnız o alarmın kayıtları, takım kapsamıyla, en yeni önce")
    void findLinkedToAlert_scopedNewestFirst() {
        IncidentRecord first = service.create(body(5L, 77L), "sre1", 1L, 5L);
        IncidentRecord second = service.create(body(5L, 77L), "sre1", 1L, 5L);
        IncidentRecord foreign = service.create(body(9L, 77L), "other", 2L, 9L);
        service.create(body(5L, 99L), "sre1", 1L, 5L);   // başka alarm

        List<Long> global = repo.findLinkedToAlert(77L, false, List.of(-1L)).stream().map(IncidentRecord::getId).toList();
        assertThat(global).containsExactly(foreign.getId(), second.getId(), first.getId());

        List<Long> team5 = repo.findLinkedToAlert(77L, true, List.of(5L)).stream().map(IncidentRecord::getId).toList();
        assertThat(team5).containsExactly(second.getId(), first.getId());

        // Girenin takımı kapsamdaysa (kayıt başka takıma aktarılmış olsa da) görünür — liste kuralıyla aynı
        IncidentRecord moved = service.create(body(9L, 77L), "sre1", 1L, 5L);
        assertThat(repo.findLinkedToAlert(77L, true, List.of(5L))).extracting(IncidentRecord::getId).contains(moved.getId());
        assertThat(repo.findLinkedToAlert(12345L, false, List.of(-1L))).isEmpty();
    }
}
