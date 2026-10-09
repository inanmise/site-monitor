package com.sitemonitor.repository;

import com.sitemonitor.model.LoginUnknownLockout;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bilinmeyen adın kilit deposu (2026-10-09) — GERÇEK SQL (H2, PostgreSQL kipi). Yazan sorgular işlem DIŞINDA koşar
 * ({@code NOT_SUPPORTED}): {@code @Transactional}'ı unutulmuş bir {@code @Modifying} sorgu burada
 * {@code TransactionRequiredException} ile düşer (ScriptedRepositoriesTest deseni; @DataJpaTest'in ambiyans işlemi bunu gizlerdi).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:unknownlockouts;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.driver-class-name=org.h2.Driver"})
class LoginUnknownLockoutRepositoryTest {

    @Autowired LoginUnknownLockoutRepository repo;

    @BeforeEach
    void clean() {
        repo.deleteAll();
    }

    @Test
    @DisplayName("INSERT … ON CONFLICT DO NOTHING: ilk yazım 1, aynı anahtarla ikinci yazım 0 (hata yok, satır değişmez)")
    void insertIfAbsent() {
        assertThat(repo.insertStepIfAbsent("GHOST", 1, "2026-10-09T08:00:30", "2026-10-09T08:00:00", "2026-10-09T08:00:00"))
                .isEqualTo(1);
        assertThat(repo.insertStepIfAbsent("GHOST", 2, "2026-10-09T08:02:00", "2026-10-09T08:00:00", "2026-10-09T08:00:00"))
                .isZero();

        LoginUnknownLockout r = repo.findById("GHOST").orElseThrow();
        assertThat(r.getLockoutLevel()).isEqualTo(1);
        assertThat(r.getLockoutUntil()).isEqualTo("2026-10-09T08:00:30");
        assertThat(r.getLastLockoutAt()).isEqualTo("2026-10-09T08:00:00");
        assertThat(r.getCreatedAt()).isEqualTo("2026-10-09T08:00:00");
        assertThat(r.getUpdatedAt()).isEqualTo("2026-10-09T08:00:00");
    }

    @Test
    @DisplayName("Koşullu UPDATE: satır yoksa 0, varsa kademe / bitiş / son kilit / son yazım güncellenir")
    void updateStep() {
        assertThat(repo.updateStep("GHOST", 2, "x", "y", "z")).isZero();
        assertThat(repo.findById("GHOST")).isEmpty();

        repo.insertStepIfAbsent("GHOST", 1, "2026-10-09T08:00:30", "2026-10-09T08:00:00", "2026-10-09T08:00:00");
        assertThat(repo.updateStep("GHOST", 2, "2026-10-09T08:03:00", "2026-10-09T08:01:00", "2026-10-09T08:01:00"))
                .isEqualTo(1);

        LoginUnknownLockout r = repo.findById("GHOST").orElseThrow();
        assertThat(r.getLockoutLevel()).isEqualTo(2);
        assertThat(r.getLockoutUntil()).isEqualTo("2026-10-09T08:03:00");
        assertThat(r.getLastLockoutAt()).isEqualTo("2026-10-09T08:01:00");
        assertThat(r.getCreatedAt()).isEqualTo("2026-10-09T08:00:00");   // ilk yazım korunur
        assertThat(r.getUpdatedAt()).isEqualTo("2026-10-09T08:01:00");
    }

    @Test
    @DisplayName("Saklama: son yazımı eşikten eski satırlar silinir, yeniler kalır")
    void deleteUpdatedBefore() {
        repo.insertStepIfAbsent("OLD", 1, "u", "l", "2025-01-01T00:00:00");
        repo.insertStepIfAbsent("NEW", 1, "u", "l", "2026-10-01T00:00:00");

        assertThat(repo.deleteUpdatedBefore("2026-01-01T00:00:00")).isEqualTo(1);
        assertThat(repo.findAll()).extracting(LoginUnknownLockout::getUsernameKey).containsExactly("NEW");
    }

    @Test
    @DisplayName("Üst sınır: en eski N satır (son yazım, eşitlikte anahtar sırası) silinir")
    void deleteOldest() {
        repo.insertStepIfAbsent("C", 1, "u", "l", "2026-10-03T00:00:00");
        repo.insertStepIfAbsent("A2", 1, "u", "l", "2026-10-01T00:00:00");
        repo.insertStepIfAbsent("A1", 1, "u", "l", "2026-10-01T00:00:00");
        repo.insertStepIfAbsent("B", 1, "u", "l", "2026-10-02T00:00:00");

        assertThat(repo.deleteOldest(3)).isEqualTo(3);
        List<String> left = repo.findAll().stream().map(LoginUnknownLockout::getUsernameKey).toList();
        assertThat(left).containsExactly("C");
        assertThat(repo.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("Servis gerçek SQL üstünde: kademe 1..5 yazılır (30/120/600/1800/1800), bağlam ve kalan süre satırdan okunur")
    void serviceLadderOverRealSql() {
        var users = org.mockito.Mockito.mock(com.sitemonitor.service.UserService.class);
        org.mockito.Mockito.when(users.lockoutLadder()).thenReturn(
                new com.sitemonitor.service.lockout.LockoutLadder(List.of(30L, 120L, 600L, 1800L), List.of(5, 3, 2, 1)));
        var svc = new com.sitemonitor.service.lockout.UnknownUserLockoutService(repo, users);
        java.time.Instant t = java.time.Instant.parse("2026-10-09T08:00:00Z");
        long[] expected = {30, 120, 600, 1800, 1800};
        int[] nextThreshold = {3, 2, 1, 1, 1};
        for (int i = 0; i < expected.length; i++) {
            org.springframework.test.util.ReflectionTestUtils.setField(svc, "clock",
                    java.time.Clock.fixed(t, java.time.ZoneOffset.UTC));
            assertThat(svc.escalate("GHOST").secondsRemaining()).isEqualTo(expected[i]);
            assertThat(svc.status("GHOST").secondsRemaining()).isEqualTo(expected[i]);
            var ctx = svc.context("GHOST");
            assertThat(ctx.failuresNeeded()).isEqualTo(nextThreshold[i]);
            assertThat(ctx.lastLockoutAt()).isEqualTo(com.sitemonitor.service.lockout.LockoutLadder.ISO.format(t));
            t = t.plusSeconds(expected[i] + 60);   // kilit bitti, bir dakika sonra yeni ihlal
        }
        assertThat(repo.findById("GHOST").orElseThrow().getLockoutLevel()).isEqualTo(5);
        assertThat(repo.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("100 karakterlik anahtar sütuna sığar")
    void longKeyFits() {
        String key = "K".repeat(100);
        assertThat(repo.insertStepIfAbsent(key, 1, "u", "l", "2026-10-09T08:00:00")).isEqualTo(1);
        assertThat(repo.findById(key)).isPresent();
    }
}
