package com.sitemonitor.service;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.Team;
import com.sitemonitor.model.UserPushDelivery;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.repository.UserPushDeliveryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Teslimat günlüğü ↔ fırtına bağı (2026-10-04): fırtına bildirimi satırının ayrıntısı kapsadığı alarmları (kapsam süzgeci
 * GÖRÜNTÜLEYENİN takımlarıyla), {@code SKIPPED_STORM} karar satırı alarmı kapsayan fırtına push satırlarını (kapsam
 * süzgeçli) ve henüz duyurulmadığı açık fırtınaları taşır. Diğer satırlar değişmez; servis yoksa alan da yok.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@SuppressWarnings("unchecked")
class PushLogStormLinkTest {

    @Mock UserPushDeliveryRepository repo;
    @Mock TeamRepository teamRepo;
    @Mock UserPushService userPushService;
    @Mock StormPushCoverageService coverage;
    @Mock AlertEventRepository alertEvents;
    PushLogQueryService svc;

    static UserPushDelivery row(long id, Long team, String user, String status, String key, Long alertId) {
        UserPushDelivery d = new UserPushDelivery();
        d.setId(id); d.setTeamId(team); d.setUsername(user); d.setDisplayName(user); d.setStatus(status);
        d.setDedupeKey(key); d.setAlertEventId(alertId); d.setTrigger(alertId == null ? "STORM" : "OPEN");
        d.setMonitorType(alertId == null ? "STORM" : "http"); d.setCreatedAt("2026-10-04T08:00:10");
        return d;
    }

    @BeforeEach
    void setUp() {
        svc = new PushLogQueryService(repo, teamRepo, userPushService, null);
        svc.setStormPushCoverage(coverage);
        svc.setAlertEvents(alertEvents);
        Team a = new Team(); a.setId(1L); a.setName("Takım A");
        when(teamRepo.findAll()).thenReturn(List.of(a));
    }

    @Test
    @DisplayName("fırtına bildirimi satırı: kapsadığı alarmlar storm_coverage'da; alarm görünürlüğü görüntüleyenin takımlarıyla")
    void stormNoticeRow_coverage() {
        UserPushDelivery notice = row(5L, 1L, "u1", "SENT", "storm:7:INITIAL", null);
        when(repo.findById(5L)).thenReturn(Optional.of(notice));
        Map<String, Object> cov = new LinkedHashMap<>();
        cov.put("storm_id", 7L); cov.put("total", 2); cov.put("hidden", 1);
        cov.put("alarms", List.of(Map.of("id", 50L, "domain", "a.example.com")));
        when(coverage.noticeCoverage(eq(notice), any())).thenReturn(cov);

        Map<String, Object> out = svc.detail(5L, new PushLogQueryService.Scope(false, Set.of(1L), "u1"));

        assertThat(out.get("storm_coverage")).isEqualTo(cov);
        assertThat(out).doesNotContainKey("storm_pushes");
        ArgumentCaptor<Predicate<Long>> p = ArgumentCaptor.forClass(Predicate.class);
        verify(coverage).noticeCoverage(eq(notice), p.capture());
        assertThat(p.getValue().test(1L)).isTrue();
        assertThat(p.getValue().test(2L)).as("başka takımın alarmı").isFalse();
    }

    @Test
    @DisplayName("SKIPPED_STORM karar satırı: kapsayan fırtına push satırları (kapsam dışı satır düşer) + henüz duyurulmadığı fırtınalar")
    void decisionRow_coveringPushes() {
        UserPushDelivery dec = row(9L, 1L, "-", EscalationService.PUSH_SKIPPED_STORM, "OPEN", 50L);
        when(repo.findById(9L)).thenReturn(Optional.of(dec));
        AlertEvent e = new AlertEvent(); e.setId(50L); e.setTeamId(1L); e.setStormId(7L);
        when(alertEvents.findById(50L)).thenReturn(Optional.of(e));
        UserPushDelivery mine = row(11L, 1L, "u1", "SENT", "storm:7:INITIAL", null);
        UserPushDelivery foreign = row(12L, 2L, "u9", "SENT", "storm:7:INITIAL", null);
        StormPushCoverageService.PushRef ref = new StormPushCoverageService.PushRef(7L, 1L, "storm:7:INITIAL", "INITIAL", null,
                "2026-10-04T08:00:10", false);
        when(coverage.coveringPushes(e)).thenReturn(new StormPushCoverageService.AlarmPushes(
                List.of(new StormPushCoverageService.CoveringPush(ref, List.of(mine, foreign))), List.of(8L)));

        Map<String, Object> out = svc.detail(9L, new PushLogQueryService.Scope(false, Set.of(1L), "x"));

        List<Map<String, Object>> pushes = (List<Map<String, Object>>) out.get("storm_pushes");
        assertThat(pushes).singleElement().satisfies(p -> {
            assertThat(p.get("storm_id")).isEqualTo(7L);
            assertThat(p.get("trigger")).isEqualTo("INITIAL");
            assertThat((List<Map<String, Object>>) p.get("rows")).extracting(r -> r.get("id")).containsExactly(11L);
        });
        assertThat(out.get("storm_awaiting")).isEqualTo(List.of(8L));
        verify(coverage, never()).noticeCoverage(any(), any());
    }

    @Test
    @DisplayName("ilgisiz satır: fırtına alanı yok, kapsam servisi çağrılmaz; servis yokken detay bugünküyle aynı")
    void unrelatedRow_untouched() {
        UserPushDelivery open = row(3L, 1L, "u1", "SENT", "OPEN", 50L);
        when(repo.findById(3L)).thenReturn(Optional.of(open));
        Map<String, Object> out = svc.detail(3L, PushLogQueryService.Scope.all());
        assertThat(out).doesNotContainKeys("storm_coverage", "storm_pushes", "storm_awaiting");
        verifyNoInteractions(coverage);

        PushLogQueryService plain = new PushLogQueryService(repo, teamRepo, userPushService, null);
        UserPushDelivery notice = row(5L, 1L, "u1", "SENT", "storm:7:INITIAL", null);
        when(repo.findById(5L)).thenReturn(Optional.of(notice));
        assertThat(plain.detail(5L, PushLogQueryService.Scope.all())).doesNotContainKey("storm_coverage");
    }
}
