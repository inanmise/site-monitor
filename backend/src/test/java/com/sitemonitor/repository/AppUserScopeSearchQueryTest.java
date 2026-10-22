package com.sitemonitor.repository;

import com.sitemonitor.model.AppUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kapsamlı müdürün kullanıcı araması (2026-10-22, hata avı UIA-2) — {@code findFilteredInTeams} GERÇEK JPQL'i H2 üstünde:
 * kullanıcı görüş kümesindeki herhangi bir takıma birincil YA DA ek üyelikle bağlıysa listelenir; istemcinin teamId
 * süzgeci kümeyle kesişir; uyuyan hesap süzgeci aynı. Controller testi depoyu mock'ladığı için sorgu başka yerde koşmaz.
 * {@code NOT_SUPPORTED}: @DataJpaTest'in test başına işlemi kapalı — sorgu üretimdeki gibi işlemsiz koşar.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AppUserScopeSearchQueryTest {

    @Autowired AppUserRepository repo;

    @BeforeEach
    @org.junit.jupiter.api.AfterEach
    void clean() {
        repo.deleteAll();   // işlemsiz test: satırlar bağlamı paylaşan öteki testlere sızmasın
    }

    private void user(String name, Long primary, String lastLogin, Long... extras) {
        AppUser u = new AppUser();
        u.setUsername(name);
        u.setSystemRole("USER");
        u.setActive(true);
        u.setTeamId(primary);
        u.setLastLoginAt(lastLogin);
        LinkedHashSet<Long> teams = new LinkedHashSet<>();
        if (primary != null) teams.add(primary);
        teams.addAll(List.of(extras));
        u.setTeamIds(teams);
        repo.save(u);
    }

    private List<String> names(Long teamId, List<Long> scope, String dormantBefore, boolean never) {
        return repo.findFilteredInTeams(null, null, null, teamId, scope, dormantBefore, never, PageRequest.of(0, 50))
                .getContent().stream().map(AppUser::getUsername).toList();
    }

    @Test
    @DisplayName("görüş kümesi {2, 3}: birincil ya da EK üyelik eşleşir; kapsam dışı kullanıcı yok; teamId süzgeci kümeyle kesişir")
    void scopeMatchesPrimaryAndExtraMembership() {
        user("A_BIRINCIL2", 2L, null);
        user("B_EK3", 9L, null, 3L);
        user("C_DISARIDA", 9L, null);
        user("D_TAKIMSIZ", null, null);

        assertThat(names(null, List.of(2L, 3L), null, false)).containsExactly("A_BIRINCIL2", "B_EK3");
        assertThat(names(3L, List.of(2L, 3L), null, false)).containsExactly("B_EK3");
        // Süzgeçler KULLANICIYA uygulanır: 9'un üyesi olup kapsama (3) de üye olan görünür, yalnız 9'un üyesi görünmez.
        // Kapsam dışı teamId isteği zaten controller'da (AdminController.searchUsers) sorguya inmeden boş döner.
        assertThat(names(9L, List.of(2L, 3L), null, false)).containsExactly("B_EK3");
    }

    @Test
    @DisplayName("uyuyan hesap süzgeci kapsamla birlikte: eşikten eski / hiç girmemiş; neverOnly yalnız hiç girmemiş")
    void dormantWithinScope() {
        user("ESKI", 2L, "2026-01-01T00:00:00");
        user("HIC", 2L, null);
        user("TAZE", 2L, "2026-10-20T00:00:00");
        user("DISARIDA_ESKI", 5L, "2026-01-01T00:00:00");

        assertThat(names(null, List.of(2L), "2026-06-01T00:00:00", false)).containsExactly("ESKI", "HIC");
        assertThat(names(null, List.of(2L), null, true)).containsExactly("HIC");
    }
}
