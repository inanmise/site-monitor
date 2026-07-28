package com.certmonitor.repository;

import com.certmonitor.model.ActivityLog;
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
 * {@link ActivityLogRepository#findFiltered} — takım-scope + tür/durum/tarih/serbest-metin filtreleri
 * + sıralama (en yeni üstte) H2 üstünde. İzolasyonun sorgu-düzeyi kanıtı.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class ActivityLogRepositoryTest {

    @Autowired ActivityLogRepository repo;

    private static ActivityLog a(String time, String type, String status, Long teamId, String target) {
        ActivityLog x = new ActivityLog();
        x.setActivityTime(time);
        x.setMonitorType(type);
        x.setAction("SCHEDULED_CHECK");
        x.setResultStatus(status);
        x.setTeamId(teamId);
        x.setTarget(target);
        x.setMonitorName(target);
        return x;
    }

    @BeforeEach
    void seed() {
        repo.deleteAll();
        repo.save(a("2026-07-28T10:00:00", "HTTP", "SUCCESS", 1L, "https://a.com"));
        repo.save(a("2026-07-28T11:00:00", "PORT", "ERROR",   1L, "a.com:443"));
        repo.save(a("2026-07-28T12:00:00", "HTTP", "SUCCESS", 2L, "https://b.com"));   // farklı takım
        repo.save(a("2026-07-28T09:00:00", "DNS",  "SUCCESS", null, "c.com A"));       // takımsız
    }

    private static final PageRequest PAGE = PageRequest.of(0, 10);

    @Test
    @DisplayName("scoped=[1] → yalnız team=1 kayıtları (team=2 ve takımsız GÖRÜNMEZ)")
    void findFiltered_scopedToTeam_isolates() {
        Page<ActivityLog> p = repo.findFiltered(true, List.of(1L), false, List.of(""),
                null, null, null, null, null, PAGE);
        assertThat(p.getContent()).allMatch(x -> x.getTeamId().equals(1L));
        assertThat(p.getTotalElements()).isEqualTo(2);
    }

    @Test
    @DisplayName("scoped=false (global görücü) → tüm takımlar + takımsız (4 kayıt)")
    void findFiltered_unscoped_returnsAll() {
        Page<ActivityLog> p = repo.findFiltered(false, List.of(-1L), false, List.of(""),
                null, null, null, null, null, PAGE);
        assertThat(p.getTotalElements()).isEqualTo(4);
    }

    @Test
    @DisplayName("tür filtresi (HTTP) + scope=[1] → yalnız team=1 HTTP")
    void findFiltered_typeFilter() {
        Page<ActivityLog> p = repo.findFiltered(true, List.of(1L), true, List.of("HTTP"),
                null, null, null, null, null, PAGE);
        assertThat(p.getTotalElements()).isEqualTo(1);
        assertThat(p.getContent().get(0).getMonitorType()).isEqualTo("HTTP");
    }

    @Test
    @DisplayName("durum filtresi (ERROR) global → yalnız hatalı kayıt")
    void findFiltered_statusFilter() {
        Page<ActivityLog> p = repo.findFiltered(false, List.of(-1L), false, List.of(""),
                null, "ERROR", null, null, null, PAGE);
        assertThat(p.getTotalElements()).isEqualTo(1);
        assertThat(p.getContent().get(0).getResultStatus()).isEqualTo("ERROR");
    }

    @Test
    @DisplayName("tarih aralığı (from) → eşik sonrası kayıtlar")
    void findFiltered_dateFrom() {
        Page<ActivityLog> p = repo.findFiltered(false, List.of(-1L), false, List.of(""),
                null, null, "2026-07-28T11:00:00", null, null, PAGE);
        assertThat(p.getTotalElements()).isEqualTo(2);   // 11:00 (PORT) + 12:00 (HTTP b.com)
    }

    @Test
    @DisplayName("serbest metin (q='b.com') → hedefte eşleşen kayıt")
    void findFiltered_freeTextSearch() {
        Page<ActivityLog> p = repo.findFiltered(false, List.of(-1L), false, List.of(""),
                null, null, null, null, "%b.com%", PAGE);
        assertThat(p.getTotalElements()).isEqualTo(1);
        assertThat(p.getContent().get(0).getTarget()).contains("b.com");
    }

    @Test
    @DisplayName("sıralama: en yeni üstte (activity_time DESC)")
    void findFiltered_ordersbyTimeDesc() {
        Page<ActivityLog> p = repo.findFiltered(false, List.of(-1L), false, List.of(""),
                null, null, null, null, null, PAGE);
        List<String> times = p.getContent().stream().map(ActivityLog::getActivityTime).toList();
        assertThat(times).isSortedAccordingTo((x, y) -> y.compareTo(x));   // DESC
    }
}
