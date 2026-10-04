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
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 7/24 izleme ekibi takımlarının aktif üyeleri (2026-10-04, {@code NocOperatorService} önbelleğinin tek sorgusu) — gerçek
 * JPQL H2 üstünde: üyelik = birincil takım VEYA çoklu üyelik ({@code memberTeamIds} ile aynı); PASİF kullanıcı dönmez;
 * başka takımın üyesi dönmez. Satırlar üyelik başına; servis kesişimi Java'da kurar.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class NocOperatorMembershipQueryTest {

    @Autowired AppUserRepository repo;

    private Long user(String username, boolean active, Long primary, Long... extra) {
        AppUser u = new AppUser();
        u.setUsername(username);
        u.setSystemRole("USER");
        u.setActive(active);
        u.setTeamId(primary);
        for (Long t : extra) u.getTeamIds().add(t);
        if (primary != null) u.getTeamIds().add(primary);
        return repo.save(u).getId();
    }

    /** kullanıcı id → satırlardaki (birincil ∪ üyelik) ∩ istenen takımlar. */
    private static Map<Long, Set<Long>> byUser(List<Object[]> rows, Set<Long> wanted) {
        Map<Long, Set<Long>> m = new HashMap<>();
        for (Object[] r : rows) {
            Long id = ((Number) r[0]).longValue();
            Set<Long> s = m.computeIfAbsent(id, k -> new TreeSet<>());
            if (r[5] instanceof Number p && wanted.contains(p.longValue())) s.add(p.longValue());
            if (r[6] instanceof Number t && wanted.contains(t.longValue())) s.add(t.longValue());
        }
        return m;
    }

    @Test
    @DisplayName("birincil VEYA ek üyelikle 7/24 takımında olan AKTİF kullanıcılar döner; pasif ve ilgisiz kullanıcı dönmez")
    void activeMembersOnly() {
        Long primary = user("NOC1", true, 10L);
        Long extra = user("NOC2", true, 20L, 10L);
        Long passive = user("PAS1", false, 10L);
        Long other = user("OTH1", true, 20L);
        Long teamless = user("NONE", true, null);

        Set<Long> wanted = Set.of(10L);
        Map<Long, Set<Long>> m = byUser(repo.findActiveMemberRowsOfTeams(wanted), wanted);
        assertThat(m).containsOnlyKeys(primary, extra);
        assertThat(m.get(primary)).containsExactly(10L);
        assertThat(m.get(extra)).containsExactly(10L);
        assertThat(m).doesNotContainKeys(passive, other, teamless);

        // Satırlar görünen ad parçalarını ve rolü taşır (fotoğraf / koleksiyon yüklenmez)
        Object[] any = repo.findActiveMemberRowsOfTeams(wanted).get(0);
        assertThat(any).hasSize(8);
        assertThat(any[7]).isEqualTo("USER");
    }
}
