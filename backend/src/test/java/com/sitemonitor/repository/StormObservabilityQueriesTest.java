package com.sitemonitor.repository;

import com.sitemonitor.model.AlertStorm;
import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.AlertStormMember;
import com.sitemonitor.service.StormService;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fırtına gözlem sorguları (H2, gerçek JPQL; 2026-09-30): geçmiş süzgeci (takım / tarih / yalnız çözülmüş), takım başına
 * son fırtına, üyelik sayımı ve damga güncellemeleri. Üyelik yazan metotlar {@code @Transactional} taşır — test işlem
 * DIŞINDA koşar ki eksik işlem sınıfı gizlenmesin (ScriptedRepositoriesTest deseni).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:stormobs;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=VALUE",
        "spring.datasource.driver-class-name=org.h2.Driver"})
class StormObservabilityQueriesTest {

    @Autowired AlertStormRepository storms;
    @Autowired AlertStormMemberRepository members;
    @Autowired AlertEventRepository events;
    @Autowired DataSource dataSource;

    private AlertStorm storm(Long teamId, String createdAt, boolean resolved) {
        AlertStorm s = new AlertStorm();
        s.setScopeKey(teamId == null ? "ACCOUNT" : "TEAM:" + teamId);
        s.setScopeType(teamId == null ? "ACCOUNT" : "TEAM");
        s.setTeamId(teamId); s.setResolved(resolved); s.setCreatedAt(createdAt);
        if (resolved) s.setResolvedAt(createdAt);
        return storms.saveAndFlush(s);
    }

    @Test
    @DisplayName("findHistory: takım süzgeci, tarih aralığı ve yalnız-çözülmüş; süzgeçsizde eski (takımsız) satır da gelir; yeniden eskiye")
    void history_filters() {
        storms.deleteAll(); members.deleteAll();
        AlertStorm a = storm(14L, "2026-09-10T10:00:00", true);
        AlertStorm b = storm(14L, "2026-09-20T10:00:00", false);
        AlertStorm c = storm(15L, "2026-09-25T10:00:00", true);
        AlertStorm legacy = storm(null, "2026-09-26T10:00:00", true);

        Page<AlertStorm> team14 = storms.findHistory(false, List.of(14L), null, null, false, PageRequest.of(0, 10));
        assertThat(team14.getContent()).extracting(AlertStorm::getId).containsExactly(b.getId(), a.getId());

        Page<AlertStorm> all = storms.findHistory(true, List.of(-1L), null, null, false, PageRequest.of(0, 10));
        assertThat(all.getTotalElements()).isEqualTo(4);
        assertThat(all.getContent().get(0).getId()).isEqualTo(legacy.getId());

        Page<AlertStorm> ranged = storms.findHistory(true, List.of(-1L), "2026-09-15T00:00:00", "2026-09-25T23:59:59", false, PageRequest.of(0, 10));
        assertThat(ranged.getContent()).extracting(AlertStorm::getId).containsExactly(c.getId(), b.getId());

        Page<AlertStorm> resolved = storms.findHistory(false, List.of(14L, 15L), null, null, true, PageRequest.of(0, 10));
        assertThat(resolved.getContent()).extracting(AlertStorm::getId).containsExactly(c.getId(), a.getId());

        List<Object[]> last = storms.lastStormPerTeam("2026-09-01T00:00:00");
        assertThat(last).hasSize(2);
        Object[] t14 = last.stream().filter(r -> ((Number) r[0]).longValue() == 14L).findFirst().orElseThrow();
        assertThat(t14[1]).isEqualTo("2026-09-20T10:00:00");
        assertThat(((Number) t14[2]).longValue()).isEqualTo(2L);
    }

    @Test
    @DisplayName("üyelik SQL'i (StormService JDBC toplu cümleleri, H2 PostgreSQL modu): batch INSERT çakışmayı yutar; duyuru yalnız duyurulmamışa; ayrılış yalnız ilk; countByStorms toplam/kurtulan")
    void members_jdbcStatements() {
        storms.deleteAll(); members.deleteAll();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        AlertStorm s = storm(14L, "2026-09-30T10:00:00", false);
        Long id = s.getId();

        int[] ins = jdbc.batchUpdate(StormService.SQL_MEMBER_INSERT, List.of(
                new Object[]{id, 1L, "2026-09-30T10:00:00", AlertStormMember.JOIN_TRIGGER},
                new Object[]{id, 2L, "2026-09-30T10:00:00", AlertStormMember.JOIN_PEER}));
        assertThat(ins).hasSize(2);
        jdbc.update(StormService.SQL_MEMBER_INSERT, id, 3L, "2026-09-30T10:05:00", AlertStormMember.JOIN_ATTACH);
        jdbc.update(StormService.SQL_MEMBER_INSERT, id, 3L, "2026-09-30T10:06:00", AlertStormMember.JOIN_ATTACH);   // çakışma → yutulur
        assertThat(members.findByStormIdOrderByJoinedAtAsc(id)).extracting(AlertStormMember::getAlertEventId).containsExactly(1L, 2L, 3L);

        assertThat(jdbc.update(StormService.SQL_MEMBER_ANNOUNCED_PREFIX + "?,?)", "2026-09-30T10:00:30", id, 1L, 2L)).isEqualTo(2);
        assertThat(jdbc.update(StormService.SQL_MEMBER_ANNOUNCED_PREFIX + "?,?,?)", "2026-09-30T11:00:00", id, 1L, 2L, 3L)).isEqualTo(1);   // yalnız 3
        assertThat(members.findByStormIdOrderByJoinedAtAsc(id)).extracting(AlertStormMember::getAnnouncedAt)
                .containsExactly("2026-09-30T10:00:30", "2026-09-30T10:00:30", "2026-09-30T11:00:00");

        jdbc.batchUpdate(StormService.SQL_MEMBER_LEFT, List.of(
                new Object[]{"2026-09-30T12:00:00", AlertStormMember.LEAVE_RECOVERED, id, 1L},
                new Object[]{"2026-09-30T12:00:00", AlertStormMember.LEAVE_NOTIFIED, id, 2L}));
        assertThat(jdbc.update(StormService.SQL_MEMBER_LEFT, "2026-09-30T13:00:00", AlertStormMember.LEAVE_UNLINKED, id, 1L)).isZero();   // ilk ayrılış kalır

        List<Object[]> counts = members.countByStorms(List.of(id));
        assertThat(counts).hasSize(1);
        assertThat(((Number) counts.get(0)[1]).longValue()).isEqualTo(3L);
        assertThat(((Number) counts.get(0)[2]).longValue()).isEqualTo(1L);
        assertThat(members.findByAlertEventIdOrderByJoinedAtDesc(1L)).hasSize(1);
    }

    @Test
    @DisplayName("liveMembers: açık fırtınaların üyeleri dar izdüşümle tek sorguda; takım süzgeçli analiz penceresi")
    void liveMembers_andTeamWindow() {
        storms.deleteAll(); members.deleteAll(); events.deleteAll();
        AlertStorm a = storm(14L, "2026-09-30T10:00:00", false);
        AlertStorm b = storm(15L, "2026-09-30T11:00:00", false);
        for (Object[] r : new Object[][]{{a.getId(), "h1", false}, {a.getId(), "h2", true}, {b.getId(), "h3", false}}) {
            AlertEvent e = new AlertEvent();
            e.setDomain((String) r[1]); e.setAlertType("HTTP_DOWN"); e.setAlertLevel("HIGH"); e.setTeamId(14L);
            e.setAcknowledged(false); e.setResolved((Boolean) r[2]); e.setStormId((Long) r[0]); e.setCreatedAt("2026-09-30T10:00:00");
            events.saveAndFlush(e);
        }
        List<Object[]> rows = storms.liveMembers(List.of(a.getId(), b.getId()));
        assertThat(rows).hasSize(3);
        assertThat(rows.stream().filter(r -> a.getId().equals(r[0])).count()).isEqualTo(2);
        assertThat(rows.get(0)).hasSize(7);

        assertThat(storms.findByTeamIdAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc(15L, "2026-09-01T00:00:00"))
                .extracting(AlertStorm::getId).containsExactly(b.getId());
    }

    @Test
    @DisplayName("SQL_ACTIVE_BY_TEAM: 10 kaynağın takım başına aktif izleme toplamı TEK sorguda (Port/DNS yalnız bağımsız satır) — H2'de geçerli")
    void activeByTeam_sqlRuns() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        java.util.Map<Long, Long> out = new java.util.HashMap<>();
        jdbc.query(StormService.SQL_ACTIVE_BY_TEAM, rs -> { out.put(rs.getLong(1), rs.getLong(2)); });
        assertThat(out).isNotNull();   // tablolar boş olabilir — amaç sözdizimi + tablo/kolon adlarının doğrulanması
    }
}
