package com.sitemonitor.repository;

import com.sitemonitor.model.AppUser;
import com.sitemonitor.model.UserBulkOperation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Toplu pasife alma sorguları (2026-10-02) — gerçek JPQL H2 üstünde. Test transaction'ı KAPALI
 * ({@code NOT_SUPPORTED}): {@code @Modifying} geri alma sahiplenmesi kendi {@code @Transactional}'ını taşımazsa burada
 * {@code TransactionRequiredException} verir (CLAUDE.md "derived delete" tuzağı; @DataJpaTest'in sarmalayan transaction'ı
 * bunu gizlerdi).
 */
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class UserBulkOperationQueriesTest {

    @Autowired UserBulkOperationRepository opRepo;
    @Autowired AppUserRepository userRepo;

    @AfterEach
    void clean() {
        opRepo.deleteAll();
        userRepo.deleteAll();
    }

    private UserBulkOperation op(String status, String undoneAt) {
        UserBulkOperation o = new UserBulkOperation();
        o.setKind(UserBulkOperation.KIND_DEACTIVATE);
        o.setStatus(status);
        o.setCreatedAt("2026-10-02T12:00:00Z");
        o.setUserIds("[1,2]");
        o.setUndoneAt(undoneAt);
        return opRepo.save(o);
    }

    @Test
    @DisplayName("markUndone ATOMİK sahiplenir: bitmiş + geri alınmamış satırda 1, ikinci çağrıda 0; sürüyor/geri alınmış satırda 0")
    void markUndone_claimsOnce() {
        Long done = op(UserBulkOperation.STATUS_DONE, null).getId();
        Long running = op(UserBulkOperation.STATUS_RUNNING, null).getId();

        assertThat(opRepo.markUndone(done, "2026-10-02T13:00:00Z", "ADMIN1", 1L)).isEqualTo(1);
        assertThat(opRepo.markUndone(done, "2026-10-02T13:01:00Z", "ADMIN2", 2L)).isEqualTo(0);
        assertThat(opRepo.markUndone(running, "2026-10-02T13:00:00Z", "ADMIN1", 1L)).isEqualTo(0);

        UserBulkOperation reloaded = opRepo.findById(done).orElseThrow();
        assertThat(reloaded.getUndoneAt()).isEqualTo("2026-10-02T13:00:00Z");
        assertThat(reloaded.getUndoneBy()).isEqualTo("ADMIN1");

        assertThat(opRepo.recordUndoResult(done, 3, 1, 0)).isEqualTo(1);
        assertThat(opRepo.findById(done).orElseThrow().getUndoOkCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("Sonraki geri alınmamış işlemler + son 20 (yeni → eski)")
    void laterOpsAndHistory() {
        Long first = op(UserBulkOperation.STATUS_DONE, null).getId();
        Long second = op(UserBulkOperation.STATUS_DONE, null).getId();
        op(UserBulkOperation.STATUS_DONE, "2026-10-02T13:00:00Z");

        assertThat(opRepo.findByKindAndIdGreaterThanAndUndoneAtIsNull(UserBulkOperation.KIND_DEACTIVATE, first))
                .extracting(UserBulkOperation::getId).containsExactly(second);
        assertThat(opRepo.findTop20ByOrderByIdDesc()).extracting(UserBulkOperation::getId).first().isNotEqualTo(first);
        assertThat(opRepo.findTop20ByOrderByIdDesc()).hasSize(3);
    }

    @Test
    @DisplayName("Aday projeksiyonu: 11 kolon, sıra sözleşmesi; üyelik çiftleri app_user_teams'ten")
    void candidateProjection() {
        AppUser u = new AppUser();
        u.setUsername("ALICE");
        u.setDisplayName("Alice");
        u.setEmail("alice@x.com");
        u.setSystemRole("USER");
        u.setAuthSource("LDAP");
        u.setLastLoginAt("2026-01-01T00:00:00");
        u.setCreatedAt("2025-01-01T00:00:00");
        u.setActive(true);
        u.setTeamId(5L);
        u.setEmployeeId("S1");
        u.setTeamIds(new LinkedHashSet<>(List.of(5L, 6L)));
        Long id = userRepo.save(u).getId();

        List<Object[]> rows = userRepo.findBulkCandidateRows();
        assertThat(rows).singleElement().satisfies(r -> {
            assertThat(r).hasSize(11);
            assertThat(r[0]).isEqualTo(id);
            assertThat(r[1]).isEqualTo("ALICE");
            assertThat(r[4]).isEqualTo("USER");
            assertThat(r[5]).isEqualTo("LDAP");
            assertThat(r[6]).isEqualTo("2026-01-01T00:00:00");
            assertThat(r[7]).isEqualTo("2025-01-01T00:00:00");
            assertThat(r[8]).isEqualTo(Boolean.TRUE);
            assertThat(r[9]).isEqualTo(5L);
            assertThat(r[10]).isEqualTo("S1");
        });
        Set<Long> teams = new java.util.HashSet<>();
        for (Object[] p : userRepo.findAllTeamMembershipPairs()) {
            assertThat(p[0]).isEqualTo(id);
            teams.add((Long) p[1]);
        }
        assertThat(teams).containsExactlyInAnyOrder(5L, 6L);
    }
}
