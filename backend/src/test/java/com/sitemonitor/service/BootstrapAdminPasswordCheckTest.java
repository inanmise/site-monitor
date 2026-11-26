package com.sitemonitor.service;

import com.sitemonitor.model.AppUser;
import com.sitemonitor.repository.AppUserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * H paketi B3 (2026-11-26): eski kurulumda yer tutucu ({@code REPLACE_ME}) parolası hiç değişmemiş önyükleme yöneticisine
 * tek seferlik, koşullu "parola değiştir" bayrağı; nişan; AD hesabına / değişmiş parolaya dokunmaz. Ayrıca H paketinin yeni
 * hedefli {@link AppUserRepository} sorguları (B3, B4, B8) gerçek H2 üzerinde. Satırlar gerçekten COMMIT olur, her test
 * sonunda silinir.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:bootstrapadmincheck;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop"})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class BootstrapAdminPasswordCheckTest {

    private static final BCryptPasswordEncoder ENC = new BCryptPasswordEncoder();

    @Autowired AppUserRepository userRepo;
    @Autowired JdbcTemplate jdbc;

    private SchedulerService scheduler;
    private AuditService audit;

    @BeforeEach
    void setUp() {
        jdbc.execute(StandaloneMonitorDeletionBackfill.MARKERS_DDL);
        cleanUp();
        scheduler = mock(SchedulerService.class);
        when(scheduler.callWithSchedulerLock(eq(BootstrapAdminPasswordCheck.LOCK), any(Supplier.class)))
                .thenAnswer(inv -> Optional.ofNullable(((Supplier<?>) inv.getArgument(1)).get()));
        audit = mock(AuditService.class);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM app_users");
        jdbc.update("DELETE FROM schema_patch_markers WHERE patch_key = ?", BootstrapAdminPasswordCheck.KEY);
    }

    @SuppressWarnings("unchecked")
    private BootstrapAdminPasswordCheck job(String username) {
        ObjectProvider<SchedulerService> sp = mock(ObjectProvider.class);
        when(sp.getIfAvailable()).thenReturn(scheduler);
        ObjectProvider<AuditService> ap = mock(ObjectProvider.class);
        when(ap.getIfAvailable()).thenReturn(audit);
        return new BootstrapAdminPasswordCheck(jdbc, userRepo, sp, ap, username);
    }

    private AppUser user(String username, String rawPassword, String role, String source) {
        AppUser u = new AppUser();
        u.setUsername(username);
        u.setPasswordHash(rawPassword == null ? null : ENC.encode(rawPassword));
        u.setDisplayName(username);
        u.setSystemRole(role);
        u.setAuthSource(source);
        u.setActive(true);
        u.setCreatedAt("2026-01-01T00:00:00");
        u.setUpdatedAt("2026-01-01T00:00:00");
        return userRepo.save(u);
    }

    private boolean mustChange(Long id) {
        return Boolean.TRUE.equals(userRepo.findById(id).orElseThrow().getMustChangePassword());
    }

    private int markerRows() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM schema_patch_markers WHERE patch_key = ?",
                Integer.class, BootstrapAdminPasswordCheck.KEY);
        return n == null ? 0 : n;
    }

    @Test
    @DisplayName("B3: REPLACE_ME parolası hiç değişmemiş yerel yönetici → bayrak + bir denetim kaydı + nişan; ikinci tur hiçbir şey yapmaz")
    void placeholderAdmin_flaggedOnce() {
        AppUser admin = user("admin", "REPLACE_ME", "ADMIN", "LOCAL");
        BootstrapAdminPasswordCheck job = job("admin");

        job.run();

        assertThat(mustChange(admin.getId())).isTrue();
        assertThat(markerRows()).isEqualTo(1);
        verify(audit, times(1)).recordSystemEvent(eq("USER_UPDATE"), eq("USER"), eq(String.valueOf(admin.getId())), anyString());

        // Yeni pod (bellekteki nişan yok): nişan DB'de → iş koşmaz, ikinci kayıt yok
        job("admin").run();
        verify(audit, times(1)).recordSystemEvent(anyString(), anyString(), anyString(), anyString());
        assertThat(markerRows()).isEqualTo(1);
    }

    @Test
    @DisplayName("B3: parolası değişmiş yönetici, AD hesabı, zaten bayraklı hesap ve olmayan hesap → dokunulmaz, nişan yine yazılır")
    void otherCases_untouched() {
        AppUser changed = user("admin", "Gercek-Parola-2026", "ADMIN", "LOCAL");
        job("admin").run();
        assertThat(mustChange(changed.getId())).isFalse();
        assertThat(markerRows()).isEqualTo(1);
        verify(audit, never()).recordSystemEvent(anyString(), anyString(), anyString(), anyString());

        cleanUp();
        AppUser ldap = user("admin", "REPLACE_ME", "ADMIN", "LDAP");
        job("admin").run();
        assertThat(mustChange(ldap.getId())).as("AD hesabı yerel parolayla girmez").isFalse();

        cleanUp();
        job("yok").run();
        assertThat(markerRows()).isEqualTo(1);
    }

    @Test
    @DisplayName("B3: yazım koşullu — denetim okuduktan sonra parola değiştiyse bayrak konmaz (0 satır, denetim kaydı yok)")
    void hashChangedMeanwhile_noFlag() {
        AppUser admin = user("admin", "REPLACE_ME", "ADMIN", "LOCAL");
        String oldHash = admin.getPasswordHash();
        jdbc.update("UPDATE app_users SET password_hash = ? WHERE id = ?", ENC.encode("Yeni-Parola-2026"), admin.getId());

        assertThat(userRepo.requirePasswordChangeIfHashUnchanged(admin.getId(), oldHash)).isZero();
        assertThat(mustChange(admin.getId())).isFalse();
        assertThat(BootstrapAdminPasswordCheck.eligible(userRepo.findById(admin.getId()).orElseThrow())).isTrue();
    }

    @Test
    @DisplayName("B3: nişan okunamıyorsa tur koşmaz (kilit bile alınmaz) — sonsuz deneme yok, açılışı bloklamaz")
    void markerUnreadable_skips() {
        JdbcTemplate broken = mock(JdbcTemplate.class);
        org.mockito.Mockito.doThrow(new org.springframework.dao.DataAccessResourceFailureException("db down"))
                .when(broken).execute(anyString());
        @SuppressWarnings("unchecked") ObjectProvider<SchedulerService> sp = mock(ObjectProvider.class);
        when(sp.getIfAvailable()).thenReturn(scheduler);
        @SuppressWarnings("unchecked") ObjectProvider<AuditService> ap = mock(ObjectProvider.class);
        new BootstrapAdminPasswordCheck(broken, userRepo, sp, ap, "admin").run();
        verify(scheduler, never()).callWithSchedulerLock(anyString(), any(Supplier.class));
    }

    // ── H paketinin hedefli sorguları ────────────────────────────────────────────

    @Test
    @DisplayName("B8: countActiveGlobalAdmins yalnız AKTİF, AD-dışı ADMIN'leri sayar (müdür ve pasif sayılmaz)")
    void countActiveGlobalAdmins() {
        user("g1", "x", "ADMIN", "LOCAL");
        user("g2", "x", "ADMIN", null);
        user("mudur", "x", "ADMIN", "LDAP");
        AppUser passive = user("p", "x", "ADMIN", "LOCAL");
        jdbc.update("UPDATE app_users SET active = FALSE WHERE id = ?", passive.getId());
        user("u", "x", "USER", "LOCAL");

        assertThat(userRepo.countActiveGlobalAdmins()).isEqualTo(2L);
    }

    @Test
    @DisplayName("B4: markAdVerified yalnız daha yeni damgayı yazar; varlık kaydı (save) damgayı EZMEZ")
    void markAdVerified_targetedAndMonotonic() {
        AppUser u = user("n60146", null, "USER", "LDAP");
        assertThat(userRepo.markAdVerified(u.getId(), "2026-11-26T10:00:00")).isEqualTo(1);
        assertThat(userRepo.markAdVerified(u.getId(), "2026-11-26T09:00:00")).as("eski damga geri almaz").isZero();
        assertThat(userRepo.markAdVerified(u.getId(), "2026-11-26T11:00:00")).isEqualTo(1);

        AppUser fresh = userRepo.findById(u.getId()).orElseThrow();
        assertThat(fresh.getAdVerifiedAt()).isEqualTo("2026-11-26T11:00:00");
        fresh.setDisplayName("Ad Soyad (Bölüm)");
        fresh.setAdVerifiedAt(null);   // updatable=false — varlık yazımı kolona dokunmaz
        userRepo.save(fresh);
        assertThat(jdbc.queryForObject("SELECT ad_verified_at FROM app_users WHERE id = ?", String.class, u.getId()))
                .isEqualTo("2026-11-26T11:00:00");
    }
}
