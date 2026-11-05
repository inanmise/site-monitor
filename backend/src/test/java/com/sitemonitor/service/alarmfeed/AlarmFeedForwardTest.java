package com.sitemonitor.service.alarmfeed;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.service.forward.AlertForwardRouting;
import com.sitemonitor.service.forward.ForwardTarget;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Alarm akışının MAILS sütunu ve alarm iletimi (2026-11-05, kullanıcı kararı 7): iletim alıcılarının adresleri sahiplerinkinden
 * SONRA, tekil; RCI / anahtar değişmez; yönlendirme yok / iletim yok → sütun bugünkü gibi. Yalnız bellek (yönlendirme).
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AlarmFeedForwardTest {

    static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    static final long T0 = Instant.parse("2026-10-30T10:00:00Z").toEpochMilli();

    @Autowired AlertEventRepository alerts;
    @Autowired CertificateInventoryRepository inventory;
    @Autowired TeamRepository teams;
    @Autowired DataSource dataSource;

    JdbcTemplate jdbc;
    AlarmFeedService svc;
    Long teamA;

    /** Sahte yönlendirme: sahip → hedefler; çağrılar kaydedilir. */
    static final class Routing implements AlertForwardRouting {
        final Map<Long, List<ForwardTarget>> byOwner;
        final List<AlertRef> refs = new ArrayList<>();

        Routing(Map<Long, List<ForwardTarget>> byOwner) {
            this.byOwner = byOwner;
        }

        @Override
        public boolean anyFor(Collection<Long> owners) {
            for (Long o : owners) if (byOwner.containsKey(o)) return true;
            return false;
        }

        @Override
        public List<ForwardTarget> targetsFor(AlertRef ref) {
            refs.add(ref);
            List<ForwardTarget> out = new ArrayList<>();
            for (Long o : ref.ownerTeamIds()) out.addAll(byOwner.getOrDefault(o, List.of()));
            return out;
        }
    }

    static ForwardTarget target(long rt, List<String> emails) {
        return new ForwardTarget(rt, "Takım " + rt, List.of("Dijital Kanallar"), List.of(rt), emails, List.of(), true);
    }

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        svc = new AlarmFeedService(alerts, inventory, teams, jdbc, null);
        svc.clock = () -> T0;
        svc.cacheMs = 0;
        Team t = new Team();
        t.setName("Dijital Kanallar");
        t.setEmail("dijital@example.com; ops@example.com");
        teamA = teams.save(t).getId();
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM alarm_feed_events");
        alerts.deleteAll();
        teams.deleteAll();
    }

    static AlarmFeedSettings.Config cfg() {
        return new AlarmFeedSettings.Config(true, "WARNING", Set.of(), Set.of(), 15, "status", "Clear", "Critical", "Major",
                "Warning", "target_type", "SiteMonitor", "", "semicolon", List.of(), 30, 2000, true);
    }

    AlertEvent alarm() {
        AlertEvent e = new AlertEvent();
        e.setDomain("https://shop.example.com");
        e.setAlertType("HTTP_DOWN");
        e.setAlertLevel("HIGH");
        e.setTeamId(teamA);
        e.setAcknowledged(false);
        e.setResolved(false);
        e.setCreatedAt(ISO.format(Instant.ofEpochMilli(T0)));
        e.setMessage("ileti");
        e.setContextJson("{\"monitor_id\":44}");
        return alerts.save(e);
    }

    @Test
    @DisplayName("MAILS: iletim adresleri sahiplerden SONRA, tekil; RCI değişmez; izleme kimliği yönlendirmeye gider")
    void forwardEmailsAppended() {
        alarm();
        Map<String, Object> before = svc.preview(cfg()).rows().get(0);
        assertThat(before.get("MAILS")).isEqualTo("dijital@example.com; ops@example.com");

        Routing r = new Routing(Map.of(teamA, List.of(target(90, List.of("OPS@example.com", "noc@example.com")),
                target(91, List.of("noc@example.com", "dba@example.com")), target(teamA, List.of("kendi@example.com")))));
        svc.setForwardRouting(r);
        Map<String, Object> after = svc.preview(cfg()).rows().get(0);
        assertThat(after.get("MAILS")).isEqualTo("dijital@example.com; ops@example.com; noc@example.com; dba@example.com");
        assertThat(after.get("RCI")).isEqualTo(before.get("RCI"));
        assertThat(r.refs).singleElement().satisfies(ref -> {
            assertThat(ref.monitorId()).isEqualTo(44L);
            assertThat(ref.alertType()).isEqualTo("HTTP_DOWN");
            assertThat(ref.ownerTeamIds()).containsExactly(teamA);
        });
    }

    @Test
    @DisplayName("sahibin iletimi yoksa yönlendirme sorulmaz, sütun bugünkü gibi")
    void noForwardsUnchanged() {
        alarm();
        Routing r = new Routing(Map.of(999L, List.of(target(90, List.of("noc@example.com")))));
        svc.setForwardRouting(r);
        assertThat(svc.preview(cfg()).rows().get(0).get("MAILS")).isEqualTo("dijital@example.com; ops@example.com");
        assertThat(r.refs).isEmpty();
    }

    @Test
    @DisplayName("satır kurucu: iletim adresleri sahiplerinkiyle tekilleşir; 16 bileşenli eski kurucu boş liste")
    void rowsMails() {
        AlarmFeedRows.Alarm legacy = new AlarmFeedRows.Alarm(1L, "PING_DOWN", "HIGH", "h", null, null, "2026-10-30T10:00:00",
                false, null, null, false, null, null, null, new AlarmFeedRows.TeamInfo(1L, "A", List.of("a@x.com")), null);
        assertThat(legacy.forwardEmails()).isEmpty();
        assertThat(AlarmFeedRows.mails(legacy, AlarmFeedSettings.defaults())).isEqualTo("a@x.com");
        AlarmFeedRows.Alarm fwd = new AlarmFeedRows.Alarm(1L, "PING_DOWN", "HIGH", "h", null, null, "2026-10-30T10:00:00",
                false, null, null, false, null, null, null, new AlarmFeedRows.TeamInfo(1L, "A", List.of("a@x.com")), null,
                List.of("A@x.com", " b@x.com "));
        assertThat(AlarmFeedRows.mails(fwd, AlarmFeedSettings.defaults())).isEqualTo("a@x.com; b@x.com");
    }
}
