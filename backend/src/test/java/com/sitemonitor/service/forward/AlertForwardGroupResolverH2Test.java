package com.sitemonitor.service.forward;

import com.sitemonitor.model.AlertForward;
import com.sitemonitor.model.NotificationGroup;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.AlertForwardRepository;
import com.sitemonitor.repository.NotificationGroupRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.service.NotificationGroupService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Alarm iletimi görüntüsünün alıcı takım adresleri (performans 2026-11-17 — toplu bildirim grubu çözümleyicisi): eski takım
 * başına {@code overrideFor} yoluyla AYNI adresler (varsayılan grup, adressiz varsayılan → takım adresi, grupsuz takım) ve grup
 * deposu çağrısı alıcı takım sayısıyla BÜYÜMEZ.
 */
@DataJpaTest
@Import(AlertForwardService.class)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AlertForwardGroupResolverH2Test {

    @Autowired AlertForwardService svc;
    @Autowired AlertForwardRepository repo;
    @Autowired TeamRepository teamRepo;
    @Autowired NotificationGroupRepository groupRepo;

    final List<String> calls = new CopyOnWriteArrayList<>();
    AlertForwardService raw;

    @BeforeEach
    void setUp() {
        clean();
        raw = org.springframework.test.util.AopTestUtils.getTargetObject(svc);
    }

    void clean() {
        repo.deleteAll();
        groupRepo.deleteAll();
        teamRepo.deleteAll();
    }

    NotificationGroupRepository counting() {
        return (NotificationGroupRepository) Proxy.newProxyInstance(NotificationGroupRepository.class.getClassLoader(),
                new Class<?>[] { NotificationGroupRepository.class }, (p, m, args) -> {
                    calls.add(m.getName());
                    try {
                        return m.invoke(groupRepo, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    static NotificationGroupService legacy(NotificationGroupRepository repo) {
        return new NotificationGroupService(repo) {
            @java.lang.Override
            public Resolver resolver(Collection<Long> stampedGroupIds) {
                return null;   // sahte servisin döndürdüğü gibi → takım başına overrideFor (eski yol)
            }
        };
    }

    long team(String name, String email) {
        Team t = new Team();
        t.setName(name);
        t.setEmail(email);
        t.setActive(true);
        return teamRepo.saveAndFlush(t).getId();
    }

    void group(long team, String emails) {
        NotificationGroup g = new NotificationGroup();
        g.setTeamId(team);
        g.setName("Varsayılan " + team);
        g.setEmails(emails);
        g.setIsDefault(true);
        g.setActive(true);
        groupRepo.saveAndFlush(g);
    }

    void forward(long owner, long recipient) {
        AlertForward f = new AlertForward();
        f.setOwnerTeamId(owner);
        f.setScopeKind(AlertForward.SCOPE_ALL);
        f.setRecipientKind(AlertForward.RECIPIENT_TEAM);
        f.setRecipientTeamId(recipient);
        f.setEnabled(true);
        repo.saveAndFlush(f);
    }

    /** n alıcı takım: çiftler varsayılan grup, her üçüncüsü adressiz varsayılan (takım adresine düşer), diğerleri grupsuz. */
    void scenario(int n) {
        long owner = team("Sahip", "sahip@example.com");
        for (int i = 0; i < n; i++) {
            long rt = team("Alıcı " + i, "alici" + i + "@example.com");
            if (i % 3 == 0) group(rt, "g" + i + "@example.com, ops" + i + "@example.com");
            else if (i % 3 == 1) group(rt, "adres-degil");
            forward(owner, rt);
        }
    }

    Map<Long, List<String>> emailsByRecipient(NotificationGroupService g) {
        raw.setGroups(g);
        AlertForwardService.Snapshot s = raw.build(System.currentTimeMillis());
        Map<Long, List<String>> out = new TreeMap<>();
        for (AlertForwardService.Fwd f : s.all()) out.put(f.recipientTeamId(), f.emails());
        return out;
    }

    @Test
    @DisplayName("Alıcı takım adresleri eski takım başına yolla aynı; grup deposu çağrısı alıcı takım sayısından bağımsız")
    void sameEmailsConstantCalls() {
        int[] sizes = { 3, 12 };
        int[] newCalls = new int[2], oldCalls = new int[2];
        for (int round = 0; round < 2; round++) {
            clean();
            scenario(sizes[round]);
            calls.clear();
            Map<Long, List<String>> fresh = emailsByRecipient(new NotificationGroupService(counting()));
            newCalls[round] = calls.size();
            calls.clear();
            Map<Long, List<String>> old = emailsByRecipient(legacy(counting()));
            oldCalls[round] = calls.size();
            assertThat(fresh).hasSize(sizes[round]).isEqualTo(old);
        }
        assertThat(newCalls[0]).isEqualTo(newCalls[1]).isEqualTo(1);
        assertThat(oldCalls[1]).isGreaterThan(oldCalls[0]);
        Map<Long, List<String>> sample = emailsByRecipient(new NotificationGroupService(counting()));
        assertThat(sample.values()).anyMatch(l -> l.contains("g0@example.com") && l.contains("ops0@example.com"))
                .anyMatch(l -> l.contains("alici1@example.com"))    // adressiz varsayılan → takım adresi
                .anyMatch(l -> l.contains("alici2@example.com"));   // grupsuz takım
    }
}
