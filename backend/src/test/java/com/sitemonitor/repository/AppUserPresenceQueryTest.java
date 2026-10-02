package com.sitemonitor.repository;

import com.sitemonitor.model.AppUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.TestPropertySource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Çevrimiçi sayım sorgusu (2026-10-02) — gerçek JPQL H2 üstünde: aktif + kayıtlı oturum + taze lastSeenAt sayılır;
 * pasif, oturumsuz, TERMINATED nöbetçili ve bayat kullanıcı sayılmaz; birincil takıma göre gruplanır (null = takımsız).
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class AppUserPresenceQueryTest {

    private static final String THRESHOLD = "2026-10-02T10:00:00";
    private static final String FRESH = "2026-10-02T10:01:30";
    private static final String STALE = "2026-10-02T09:55:00";

    @Autowired AppUserRepository repo;

    private void user(String username, Long teamId, boolean active, String sid, String lastSeen) {
        AppUser u = new AppUser();
        u.setUsername(username);
        u.setSystemRole("USER");
        u.setTeamId(teamId);
        u.setActive(active);
        u.setActiveSessionId(sid);
        u.setLastSeenAt(lastSeen);
        repo.save(u);
    }

    @Test
    @DisplayName("yalnız canlı oturumlar, birincil takıma göre; takımsız null kovası")
    void countsOnlyLiveSessionsByPrimaryTeam() {
        user("A1", 1L, true, "s-a1", FRESH);
        user("A2", 1L, true, "s-a2", THRESHOLD);              // eşikte = canlı
        user("B1", 2L, true, "s-b1", FRESH);
        user("N1", null, true, "s-n1", FRESH);                 // takımsız
        user("PASIF", 1L, false, "s-p", FRESH);                // pasif → sayılmaz
        user("CIKMIS", 1L, true, null, FRESH);                 // oturumu yok → sayılmaz
        user("ATILMIS", 2L, true, "TERMINATED:abc", FRESH);    // admin sonlandırdı → sayılmaz
        user("BAYAT", 2L, true, "s-old", STALE);               // pencere dışı → sayılmaz
        user("HIC", 2L, true, "s-never", null);                // hiç ping yok → sayılmaz

        Map<Object, Long> byTeam = new HashMap<>();
        for (Object[] r : repo.countOnlineByPrimaryTeam(THRESHOLD)) byTeam.put(r[0], ((Number) r[1]).longValue());

        assertThat(byTeam).containsEntry(1L, 2L).containsEntry(2L, 1L).containsEntry(null, 1L).hasSize(3);
    }

    @Test
    @DisplayName("clearLastSeen (ayrıldım sinyali): yalnız eşleşen oturum düşer; başka sid'li kayıt korunur")
    void clearLastSeen_onlyMatchingSession() {
        user("A1", 1L, true, "s-a1", FRESH);
        user("B1", 1L, true, "s-b1", FRESH);

        assertThat(repo.clearLastSeen("a1", "s-a1")).isEqualTo(1);       // harf duyarsız kullanıcı adı
        assertThat(repo.clearLastSeen("B1", "eski-sid")).isEqualTo(0);   // başka oturum → dokunulmaz

        Map<Object, Long> byTeam = new HashMap<>();
        for (Object[] r : repo.countOnlineByPrimaryTeam(THRESHOLD)) byTeam.put(r[0], ((Number) r[1]).longValue());
        assertThat(byTeam).containsEntry(1L, 1L).hasSize(1);             // yalnız B1 çevrimiçi
        assertThat(repo.findByUsername("A1").orElseThrow().getActiveSessionId()).isEqualTo("s-a1");   // oturum kaydı durur
    }

    @Test
    @DisplayName("kimse çevrimiçi değilse boş liste")
    void empty() {
        user("BAYAT", 1L, true, "s", STALE);
        List<Object[]> rows = repo.countOnlineByPrimaryTeam(THRESHOLD);
        assertThat(rows).isEmpty();
    }
}
