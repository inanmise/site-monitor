package com.sitemonitor.service;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.AlertStorm;
import com.sitemonitor.model.AlertStormMember;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.AlertStormMemberRepository;
import com.sitemonitor.repository.AlertStormRepository;
import com.sitemonitor.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Takım bazlı fırtına GÖZLEMİ (2026-09-30): durum sınıflandırması (STORM / NEAR / WATCH / CALM), görüş kapsamı,
 * geçmiş süzgeci, ayrıntı (üyelik tablosu ∪ bağlı olaylar, tetikleyen işareti) ve analiz kovaları.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StormStatusServiceTest {

    @Mock StormService stormService;
    @Mock AlertStormRepository stormRepo;
    @Mock AlertStormMemberRepository memberRepo;
    @Mock AlertEventRepository alertEventRepo;
    @Mock TeamRepository teamRepo;
    @Mock JdbcTemplate jdbcTemplate;

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    private StormStatusService svc;

    @BeforeEach
    void setUp() {
        svc = new StormStatusService(stormService, stormRepo, memberRepo, alertEventRepo, teamRepo, jdbcTemplate);
        when(stormService.windowMinutes()).thenReturn(5);
        when(stormService.quietMinutes()).thenReturn(5);
        when(stormService.isEnabled()).thenReturn(true);
        when(stormService.perGroup()).thenReturn(true);
        when(stormService.thresholdUnit()).thenReturn("COUNT");
        when(stormService.thresholdValue()).thenReturn(5);
        when(stormService.thresholdForTotal(anyLong())).thenReturn(5);
        when(stormService.activeMonitorsByTeam()).thenReturn(Map.of(14L, 40L, 15L, 12L));
        when(teamRepo.findByActiveTrueOrderByNameAsc()).thenReturn(List.of(team(14, "SY"), team(15, "UG"), team(16, "Gizli")));
        when(teamRepo.findAll()).thenReturn(List.of(team(14, "SY"), team(15, "UG"), team(16, "Gizli")));
    }

    private static String ago(long minutes) { return ISO.format(Instant.now().minus(minutes, ChronoUnit.MINUTES)); }

    private static Team team(long id, String name) { Team t = new Team(); t.setId(id); t.setName(name); t.setActive(true); return t; }

    private static AlertEvent down(long id, long teamId, String host, String createdAt) {
        AlertEvent e = new AlertEvent();
        e.setId(id); e.setDomain(host); e.setAlertType("HTTP_DOWN"); e.setAlertLevel("HIGH");
        e.setTeamId(teamId); e.setResolved(false); e.setCreatedAt(createdAt);
        return e;
    }

    private static AlertStorm storm(long id, Long teamId, String createdAt, boolean resolved, String reason) {
        AlertStorm s = new AlertStorm();
        s.setId(id); s.setScopeKey(teamId == null ? "ACCOUNT" : "TEAM:" + teamId); s.setScopeType(teamId == null ? "ACCOUNT" : "TEAM");
        s.setTeamId(teamId); s.setResolved(resolved); s.setCreatedAt(createdAt); s.setLastMemberAt(createdAt); s.setLastReAlertAt(createdAt);
        s.setResolveReason(reason); s.setMemberCount(4); s.setRootCause("HTTP_DOWN");
        s.setThresholdEffective(5); s.setTargetsAtOpen(5); s.setPeakTargets(6); s.setTriggerEventId(99L);
        if (resolved) s.setResolvedAt(ISO.format(Instant.now()));
        return s;
    }

    @Test
    @DisplayName("Durum: açık fırtınalı takım STORM, penceresinde eşiğin bir altında hedef olan NEAR, tek hedef WATCH, boş CALM; kapsam dışı takım listelenmez")
    void status_classifiesTeams() {
        when(alertEventRepo.findOpenDownSince(anyCollection(), anyString())).thenReturn(List.of(
                down(1, 15L, "a.example.com", ago(1)), down(2, 15L, "b.example.com", ago(1)), down(3, 15L, "c.example.com", ago(2)),
                down(4, 15L, "d.example.com", ago(2)),                       // UG: 4 farklı hedef, eşik 5 → NEAR
                down(5, 16L, "x.example.com", ago(1))));                     // Gizli: kapsam dışı
        AlertStorm open = storm(7, 14L, ago(20), false, null);
        when(stormRepo.findByResolvedFalse()).thenReturn(List.of(open));
        when(stormRepo.liveMembers(anyCollection())).thenReturn(List.of(
                new Object[]{7L, 10L, "h1", "HTTP_DOWN", "HIGH", ago(20), false},
                new Object[]{7L, 11L, "h2", "HTTP_DOWN", "HIGH", ago(19), false}));
        when(alertEventRepo.findAllById(anyIterable())).thenReturn(List.of(down(99, 14L, "trigger.example.com", ago(20))));
        when(stormService.isSealed(eq(open), anyString())).thenReturn(true);

        Map<String, Object> out = svc.status(id -> id != 16L, false);

        @SuppressWarnings("unchecked") List<Map<String, Object>> teams = (List<Map<String, Object>>) out.get("teams");
        assertThat(teams).extracting(t -> t.get("team_name")).containsExactly("SY", "UG");
        Map<String, Object> sy = teams.get(0), ug = teams.get(1);
        assertThat(sy).containsEntry("status", StormStatusService.STATUS_STORM).containsEntry("threshold", 5).containsEntry("active_monitors", 40L);
        // toplu yükleme: tetikleyen ve canlı üyeler fırtına başına değil TEK sorguda; takım başına COUNT yok
        verify(alertEventRepo, never()).findById(anyLong());
        verify(alertEventRepo, never()).findByStormId(anyLong());
        verify(stormService, never()).totalActiveMonitorsForTeam(anyLong());
        verify(stormRepo, times(1)).liveMembers(anyCollection());
        @SuppressWarnings("unchecked") List<Map<String, Object>> storms = (List<Map<String, Object>>) sy.get("storms");
        assertThat(storms).hasSize(1);
        Map<String, Object> dto = storms.get(0);
        assertThat(dto).containsEntry("id", 7L).containsEntry("team_name", "SY").containsEntry("sealed", true)
                .containsEntry("active_down", 2).containsEntry("resolve_floor", 3).containsEntry("targets_at_open", 5);
        assertThat(((Map<?, ?>) dto.get("trigger")).get("domain")).isEqualTo("trigger.example.com");
        assertThat(dto.get("seal_at")).isNotNull();
        assertThat(ug).containsEntry("status", StormStatusService.STATUS_NEAR).containsEntry("window_targets", 4).containsEntry("window_alerts", 4);
        @SuppressWarnings("unchecked") Map<String, Object> totals = (Map<String, Object>) out.get("totals");
        assertThat(totals).containsEntry("teams", 2).containsEntry("storming", 1).containsEntry("near", 1).containsEntry("open_storms", 1);
        @SuppressWarnings("unchecked") Map<String, Object> settings = (Map<String, Object>) out.get("settings");
        assertThat(settings).containsEntry("quiet_minutes", 5).containsEntry("window_minutes", 5).containsEntry("threshold_unit", "COUNT")
                .containsEntry("per_group", true).containsEntry("re_alert_hours", 24).containsEntry("min_threshold", 2).containsEntry("percent_min_targets", 3);
    }

    @Test
    @DisplayName("Durum: tek düşük hedef WATCH, hiç yoksa CALM; aynı host'un iki alarmı tek hedef")
    void status_watchAndCalm() {
        when(alertEventRepo.findOpenDownSince(anyCollection(), anyString())).thenReturn(List.of(
                down(1, 14L, "a.example.com", ago(1)), down(2, 14L, "a.example.com", ago(1))));
        when(stormRepo.findByResolvedFalse()).thenReturn(List.of());
        Map<String, Object> out = svc.status(id -> true, true);
        @SuppressWarnings("unchecked") List<Map<String, Object>> teams = (List<Map<String, Object>>) out.get("teams");
        java.util.function.Function<String, Map<String, Object>> byName = n -> teams.stream().filter(x -> n.equals(x.get("team_name"))).findFirst().orElseThrow();
        assertThat(teams).extracting(x -> x.get("team_name")).containsExactly("Gizli", "SY", "UG");   // ada göre (DB ORDER BY name ile aynı)
        assertThat(byName.apply("SY")).containsEntry("status", StormStatusService.STATUS_WATCH).containsEntry("window_targets", 1).containsEntry("window_alerts", 2);
        assertThat(byName.apply("UG")).containsEntry("status", StormStatusService.STATUS_CALM).containsEntry("window_targets", 0);
    }

    @Test
    @DisplayName("Geçmiş: takım süzgeci kapsam dışıysa boş; kapsamlı kullanıcı görüş takımlarıyla sorgular; her şeyi gören süzgeçsiz (eski satırlar dâhil)")
    void history_scope() {
        assertThat(svc.history(id -> id == 14L, false, List.of(14L), 16L, null, null, false, 0, 20)).containsEntry("total", 0L);

        when(stormRepo.findHistory(anyBoolean(), anyCollection(), any(), any(), anyBoolean(), any()))
                .thenReturn(new PageImpl<>(List.of(storm(3, 14L, ago(600), true, StormService.RESOLVE_SEALED)), PageRequest.of(0, 20), 1));
        when(memberRepo.countByStorms(anyCollection())).thenReturn(List.<Object[]>of(new Object[]{3L, 6L, 2L}));
        Map<String, Object> out = svc.history(id -> id == 14L, false, List.of(14L, 16L), null, null, null, false, 0, 20);
        @SuppressWarnings("unchecked") List<Map<String, Object>> items = (List<Map<String, Object>>) out.get("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0)).containsEntry("resolve_reason", "SEALED").containsEntry("members_total", 6L).containsEntry("members_recovered", 2L)
                .containsEntry("team_name", "SY");
        assertThat(items.get(0).get("duration_ms")).isNotNull();
        verify(stormRepo).findHistory(eq(false), eq(List.of(14L)), isNull(), isNull(), eq(false), eq(PageRequest.of(0, 20)));

        svc.history(id -> true, true, null, null, "2026-09-01", "2026-09-30", true, 1, 500);
        verify(stormRepo).findHistory(eq(true), eq(List.of(-1L)), eq("2026-08-31T21:00:00"), eq("2026-09-30T20:59:59"), eq(true),
                eq(PageRequest.of(1, StormStatusService.HISTORY_MAX_PAGE)));
    }

    @Test
    @DisplayName("Ayrıntı: üyelik tablosu ∪ hâlâ bağlı olaylar; tetikleyen işaretlenir; kapsam dışı → null; bildirim özeti okunur")
    void detail_mergesMembers() {
        AlertStorm s = storm(7, 14L, ago(60), true, StormService.RESOLVE_FLOOR);
        s.setTriggerEventId(2L);
        when(stormRepo.findById(7L)).thenReturn(Optional.of(s));
        AlertStormMember m1 = new AlertStormMember(7L, 1L, ago(60), AlertStormMember.JOIN_PEER); m1.setAnnouncedAt(ago(59)); m1.setLeftAt(ago(30)); m1.setLeaveKind(AlertStormMember.LEAVE_RECOVERED);
        AlertStormMember m2 = new AlertStormMember(7L, 2L, ago(60), AlertStormMember.JOIN_TRIGGER);
        when(memberRepo.findByStormIdOrderByJoinedAtAsc(7L)).thenReturn(List.of(m1, m2));
        AlertEvent e1 = down(1, 14L, "a", ago(60)); e1.setResolved(true); e1.setResolvedAt(ago(30));
        AlertEvent e2 = down(2, 14L, "b", ago(60));
        AlertEvent e3 = down(3, 14L, "c", ago(50));   // eski fırtına: yalnız storm_id ile bağlı (üyelik satırı yok)
        when(alertEventRepo.findByStormId(7L)).thenReturn(List.of(e1, e3));
        when(alertEventRepo.findAllById(anyIterable())).thenReturn(List.of(e2));   // yalnız bağı çözülmüş üye + tetikleyen
        when(jdbcTemplate.queryForList(startsWith("SELECT trigger, COUNT(*)"), any(Object[].class)))
                .thenReturn(List.of(Map.of("trigger", "STORM_INITIAL", "c", 3L, "last", ago(59))));
        when(jdbcTemplate.queryForObject(startsWith("SELECT COUNT(*) FROM notification_logs"), eq(Long.class), any(Object[].class))).thenReturn(1L);
        when(stormRepo.liveMembers(anyCollection())).thenReturn(List.of());
        when(jdbcTemplate.queryForObject(startsWith("SELECT COUNT(*) FROM user_push_deliveries"), eq(Long.class), any(), any())).thenReturn(4L);

        assertThat(svc.detail(7L, id -> false, false)).isNull();
        Map<String, Object> d = svc.detail(7L, id -> id == 14L, false);
        assertThat(d).isNotNull();
        @SuppressWarnings("unchecked") List<Map<String, Object>> members = (List<Map<String, Object>>) d.get("members");
        assertThat(members).extracting(m -> m.get("event_id")).containsExactly(1L, 2L, 3L);
        assertThat(members.get(0)).containsEntry("leave_kind", "RECOVERED").containsEntry("trigger", false);
        assertThat(members.get(1)).containsEntry("join_kind", "TRIGGER").containsEntry("trigger", true);
        assertThat(members.get(2)).containsEntry("join_kind", null).containsEntry("joined_at", e3.getCreatedAt());
        assertThat(d).containsEntry("members_total", 3).containsEntry("members_recovered", 1).containsEntry("members_down", 2);
        @SuppressWarnings("unchecked") Map<String, Object> n = (Map<String, Object>) d.get("notifications");
        assertThat(n).containsEntry("initial", 3L).containsEntry("suppressed", 1L).containsEntry("push", 4L);
        // bildirim özeti sabit alt sorguyla (üye sayısı kadar ? yok)
        verify(jdbcTemplate).queryForList(contains(StormStatusService.MEMBER_EVENTS_SUBQUERY), eq(7L), eq(7L));
    }

    @Test
    @DisplayName("Analiz: gün kovaları İstanbul takvimiyle, takım özeti, kapanış nedeni ve kök neden dağılımı; takımsız eski fırtına yalnız her şeyi görene")
    void analytics_buckets() {
        when(stormRepo.findByCreatedAtGreaterThanEqualOrderByCreatedAtDesc(anyString())).thenReturn(List.of(
                storm(1, 14L, ago(60), true, StormService.RESOLVE_SEALED),
                storm(2, 14L, ago(120), true, StormService.RESOLVE_FLOOR),
                storm(3, 15L, ago(180), false, null),
                storm(4, null, ago(200), true, StormService.RESOLVE_LEGACY)));

        Map<String, Object> scoped = svc.analytics(id -> id == 14L, false, null, 7);
        assertThat(scoped).containsEntry("total", 2).containsEntry("sealed", 1).containsEntry("days", 7);
        @SuppressWarnings("unchecked") Map<String, Integer> reasons = (Map<String, Integer>) scoped.get("reasons");
        assertThat(reasons).containsEntry("SEALED", 1).containsEntry("FLOOR", 1);
        @SuppressWarnings("unchecked") List<Map<String, Object>> series = (List<Map<String, Object>>) scoped.get("series");
        assertThat(series).hasSize(7);
        assertThat(series.stream().mapToInt(r -> (int) r.get("total")).sum()).isEqualTo(2);

        Map<String, Object> all = svc.analytics(id -> true, true, null, 7);
        assertThat(all).containsEntry("total", 4).containsEntry("open", 1L);
        @SuppressWarnings("unchecked") List<Map<String, Object>> teams = (List<Map<String, Object>>) all.get("teams");
        assertThat(teams.get(0)).containsEntry("team_name", "SY").containsEntry("storms", 2).containsEntry("sealed", 1L).containsEntry("floor", 1L);
        assertThat(teams).anySatisfy(t -> assertThat(t.get("team_id")).isNull());

        when(stormRepo.findByTeamIdAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc(eq(15L), anyString()))
                .thenReturn(List.of(storm(3, 15L, ago(180), false, null)));
        Map<String, Object> ug = svc.analytics(id -> true, true, 15L, 7);
        assertThat(ug).containsEntry("total", 1).containsEntry("open", 1L);
    }

    @Test
    @DisplayName("Durum ezberi: aynı kapsam anahtarı 10 sn içinde yeniden hesaplanmaz; fresh=true ve farklı kapsam hesaplar")
    void status_memo() {
        when(alertEventRepo.findOpenDownSince(anyCollection(), anyString())).thenReturn(List.of());
        when(stormRepo.findByResolvedFalse()).thenReturn(List.of());
        Map<String, Object> a = svc.status("ALL", id -> true, true, false);
        Map<String, Object> b = svc.status("ALL", id -> true, true, false);
        assertThat(b).isSameAs(a);
        verify(alertEventRepo, times(1)).findOpenDownSince(anyCollection(), anyString());
        svc.status("ALL", id -> true, true, true);
        svc.status("14", id -> id == 14L, false, false);
        verify(alertEventRepo, times(3)).findOpenDownSince(anyCollection(), anyString());
    }

    @Test
    @DisplayName("Tarih yardımcıları: İstanbul günü → UTC sınırları; boş/bozuk → null")
    void dayBounds() {
        assertThat(StormStatusService.dayStart("2026-09-30")).isEqualTo("2026-09-29T21:00:00");
        assertThat(StormStatusService.dayEnd("2026-09-30")).isEqualTo("2026-09-30T20:59:59");
        assertThat(StormStatusService.dayStart("")).isNull();
        assertThat(StormStatusService.dayEnd("x")).isNull();
    }
}
