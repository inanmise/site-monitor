package com.sitemonitor.service.alarmfeed;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.NotificationGroup;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.NotificationGroupRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.service.NotificationGroupService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Alarm akışının MAILS sütunu bildirim gruplarıyla (performans 2026-11-17 — toplu grup çözümleyicisi): eski takım × damga
 * başına {@code overrideFor} yoluyla AYNI adresler (varsayılan grup, aynı takımın damgalı grubu, başka takımın damgası, olmayan
 * damga, grubu olmayan takım → takım adresi) ve grup deposu çağrı sayısı takım sayısıyla BÜYÜMEZ.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AlarmFeedNotificationGroupsTest {

    static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    static final long T0 = Instant.parse("2026-10-30T10:00:00Z").toEpochMilli();

    @Autowired AlertEventRepository alerts;
    @Autowired CertificateInventoryRepository inventory;
    @Autowired TeamRepository teams;
    @Autowired NotificationGroupRepository groups;
    @Autowired DataSource dataSource;

    final List<String> calls = new CopyOnWriteArrayList<>();

    @AfterEach
    void clean() {
        new JdbcTemplate(dataSource).update("DELETE FROM alarm_feed_events");
        alerts.deleteAll();
        groups.deleteAll();
        teams.deleteAll();
    }

    static final AlarmFeedSettings.Config CFG = new AlarmFeedSettings.Config(true, "WARNING", Set.of(), Set.of(), 15, "status",
            "Clear", "Critical", "Major", "Warning", "target_type", "SiteMonitor", "", "semicolon", List.of(), 30, 2000, true);

    NotificationGroupRepository counting() {
        return (NotificationGroupRepository) Proxy.newProxyInstance(NotificationGroupRepository.class.getClassLoader(),
                new Class<?>[] { NotificationGroupRepository.class }, (p, m, args) -> {
                    calls.add(m.getName());
                    try {
                        return m.invoke(groups, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    AlarmFeedService service(NotificationGroupService g) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        AlarmFeedService s = new AlarmFeedService(alerts, inventory, teams, jdbc, null);
        s.clock = () -> T0;
        s.cacheMs = 0;
        s.setNotificationGroups(g);
        return s;
    }

    long team(String name, String email) {
        Team t = new Team();
        t.setName(name);
        t.setEmail(email);
        return teams.save(t).getId();
    }

    long group(long team, String name, String emails, boolean isDefault) {
        NotificationGroup g = new NotificationGroup();
        g.setTeamId(team);
        g.setName(name);
        g.setEmails(emails);
        g.setIsDefault(isDefault);
        g.setActive(true);
        return groups.saveAndFlush(g).getId();
    }

    void alarm(String domain, Long teamId, Long stampedGroup) {
        AlertEvent e = new AlertEvent();
        e.setDomain(domain);
        e.setAlertType("PING_DOWN");
        e.setAlertLevel("CRITICAL");
        e.setTeamId(teamId);
        e.setNotificationGroupId(stampedGroup);
        e.setAcknowledged(false);
        e.setResolved(false);
        e.setCreatedAt(ISO.format(Instant.ofEpochMilli(T0)));
        e.setMessage("ileti");
        alerts.save(e);
    }

    /** n takım: her biri varsayılan grup + damgalı ikinci grup; ayrıca grupsuz takım, başka takımın / olmayan damga. */
    void scenario(int n) {
        long noGroup = team("Grupsuz", "grupsuz@example.com");
        alarm("host-grupsuz", noGroup, null);
        Long firstSecond = null;
        for (int i = 0; i < n; i++) {
            long t = team("Takım " + i, "takim" + i + "@example.com");
            group(t, "Varsayılan " + i, "v" + i + "@example.com", true);
            long second = group(t, "İkinci " + i, "s" + i + "@example.com, ops" + i + "@example.com", false);
            if (firstSecond == null) firstSecond = second;
            alarm("host-" + i + "-a", t, null);            // varsayılan grup
            alarm("host-" + i + "-b", t, second);          // aynı takımın damgalı grubu
            alarm("host-" + i + "-c", t, firstSecond);     // (i > 0) başka takımın damgası → yok sayılır
            alarm("host-" + i + "-d", t, 9_999_999L);      // olmayan damga
        }
    }

    static Map<String, Object> mails(AlarmFeedService.Feed f) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map<String, Object> r : f.rows()) out.put(String.valueOf(r.get("RCI")), r.get("MAILS"));
        return out;
    }

    /** Eski yol: toplu çözümleyici YOK (sahte servisin döndürdüğü gibi) → takım × damga başına overrideFor. */
    static NotificationGroupService legacy(NotificationGroupRepository repo) {
        return new NotificationGroupService(repo) {
            @java.lang.Override
            public Resolver resolver(Collection<Long> stampedGroupIds) {
                return null;
            }
        };
    }

    @Test
    @DisplayName("MAILS eski takım × damga yoluyla birebir aynı; grup deposu çağrısı takım sayısından bağımsız (eski yol büyür)")
    void sameMailsConstantCalls() {
        int[] newCalls = new int[2], oldCalls = new int[2];
        int[] sizes = { 2, 8 };
        for (int round = 0; round < 2; round++) {
            clean();
            scenario(sizes[round]);
            calls.clear();
            AlarmFeedService.Feed fresh = service(new NotificationGroupService(counting())).build(CFG, true);
            newCalls[round] = calls.size();
            calls.clear();
            AlarmFeedService.Feed old = service(legacy(counting())).build(CFG, true);
            oldCalls[round] = calls.size();
            assertThat(fresh.rows()).hasSize(1 + 4 * sizes[round]);
            assertThat(mails(fresh)).as("tur %d", round).isEqualTo(mails(old));
        }
        assertThat(newCalls[0]).isEqualTo(newCalls[1]).isLessThanOrEqualTo(2);
        assertThat(oldCalls[1]).isGreaterThan(oldCalls[0]);
        // örneklem: varsayılan grup, damgalı grup, grupsuz takım
        Map<String, Object> m = mails(service(new NotificationGroupService(counting())).build(CFG, true));
        List<String> values = new ArrayList<>();
        m.values().forEach(v -> values.add(String.valueOf(v)));
        assertThat(values).anyMatch(v -> v.contains("v0@example.com"))
                .anyMatch(v -> v.contains("s0@example.com") && v.contains("ops0@example.com"))
                .anyMatch(v -> v.contains("grupsuz@example.com"));
    }
}
