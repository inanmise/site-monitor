package com.sitemonitor.service;

import com.sitemonitor.model.NotificationGroup;
import com.sitemonitor.repository.NotificationGroupRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Toplu bildirim grubu çözümleyicisi ({@link NotificationGroupService#resolver}, performans 2026-11-17) — GERÇEK SQL (H2):
 * her (takım, damga) çifti için {@link NotificationGroupService#overrideFor(Long, Long)} ile AYNI sonuç (varsayılan / damgalı
 * grup, pasif grup, başka takımın damgası, olmayan damga, adressiz grup, iki varsayılanlı belirsiz takım, ön yüklenmemiş
 * damga, takımsız) ve depo çağrı sayısı takım sayısından bağımsız.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class NotificationGroupResolverTest {

    @Autowired NotificationGroupRepository repo;

    final List<String> calls = new CopyOnWriteArrayList<>();
    NotificationGroupService svc;

    static NotificationGroupRepository counting(NotificationGroupRepository target, List<String> calls) {
        return (NotificationGroupRepository) Proxy.newProxyInstance(NotificationGroupRepository.class.getClassLoader(),
                new Class<?>[] { NotificationGroupRepository.class }, (p, m, args) -> {
                    calls.add(m.getName());
                    try {
                        return m.invoke(target, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    @BeforeEach
    void setUp() {
        repo.deleteAll();
        svc = new NotificationGroupService(counting(repo, calls));
    }

    long group(long team, String name, String emails, boolean isDefault, boolean active) {
        NotificationGroup g = new NotificationGroup();
        g.setTeamId(team);
        g.setName(name);
        g.setEmails(emails);
        g.setIsDefault(isDefault);
        g.setActive(active);
        return repo.saveAndFlush(g).getId();
    }

    @Test
    @DisplayName("Her (takım, damga) çifti için overrideFor ile aynı sonuç — tüm kurallar ve belirsiz durumlar")
    void sameAsOverrideFor() {
        long dA = group(1, "A varsayılan", "a@example.com, ops@example.com", true, true);
        long sA = group(1, "A ikinci", "a2@example.com", false, true);
        long pasifA = group(1, "A pasif", "pasif@example.com", false, false);
        long bozukA = group(1, "A bozuk", "adres-degil", false, true);
        long dB = group(2, "B varsayılan bozuk", "yok", true, true);      // adressiz varsayılan → takım adresine düşer
        group(3, "C varsayılan pasif", "c@example.com", true, false);      // pasif varsayılan sayılmaz
        long sD = group(4, "D damga", "d@example.com", false, true);
        group(5, "E bir", "e1@example.com", true, true);                  // iki aktif varsayılan → belirsiz "ilk"
        group(5, "E iki", "e2@example.com", true, true);
        long[] teams = { 1, 2, 3, 4, 5, 6 };
        List<Long> stamps = new ArrayList<>(List.of(dA, sA, pasifA, bozukA, dB, sD, 999_999L));
        NotificationGroupService.Resolver r = svc.resolver(stamps);
        List<Long> probe = new ArrayList<>(stamps);
        probe.add(null);
        long notPreloaded = group(1, "A sonradan", "late@example.com", false, true);   // ön yüklemeden sonra eklendi
        probe.add(notPreloaded);
        for (long t : teams) {
            for (Long s : probe) {
                assertThat(r.overrideFor(t, s)).as("takım %d damga %s", t, s).isEqualTo(svc.overrideFor(t, s));
            }
        }
        assertThat(r.overrideFor(null, dA)).isEqualTo(NotificationGroupService.Override.NONE);
        // anlamlı bir örneklem: damgalı grup, varsayılan, düşüş
        assertThat(r.overrideFor(1L, sA).source()).isEqualTo(NotificationGroupService.Source.GROUP);
        assertThat(r.overrideFor(1L, null).source()).isEqualTo(NotificationGroupService.Source.TEAM_DEFAULT_GROUP);
        assertThat(r.overrideFor(4L, dA).applies()).isFalse();           // başka takımın damgası yok sayılır, D'nin varsayılanı yok
        assertThat(r.overrideFor(4L, sD).emails()).containsExactly("d@example.com");
        assertThat(r.overrideFor(1L, notPreloaded).emails()).containsExactly("late@example.com");
    }

    @Test
    @DisplayName("Depo çağrısı takım sayısından bağımsız: ön yükleme 2 çağrı, çözümler 0 çağrı (belirsiz / ön yüklenmemişler hariç)")
    void callCountIndependentOfTeamCount() {
        int[] counts = new int[2];
        int[] sizes = { 3, 30 };
        for (int round = 0; round < 2; round++) {
            repo.deleteAll();
            List<Long> stamps = new ArrayList<>();
            for (long t = 1; t <= sizes[round]; t++) {
                group(t, "V" + t, "v" + t + "@example.com", true, true);
                stamps.add(group(t, "S" + t, "s" + t + "@example.com", false, true));
            }
            calls.clear();
            NotificationGroupService.Resolver r = svc.resolver(stamps);
            for (long t = 1; t <= sizes[round]; t++) {
                r.overrideFor(t, null);
                r.overrideFor(t, stamps.get((int) t - 1));
            }
            counts[round] = calls.size();
        }
        assertThat(counts[0]).isEqualTo(2).isEqualTo(counts[1]);
        // eski yol: takım × damga başına 1–2 çağrı
        calls.clear();
        for (long t = 1; t <= 30; t++) svc.overrideFor(t, null);
        assertThat(calls).hasSize(30);
    }
}
