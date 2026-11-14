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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tek seferlik org rolü kilit onarımı (2026-11-14): "AD'den ekle" ile org rolü SEÇİLMEDEN eklenip boş + kilitli kalan
 * satırların kilidi açılır; yöneticinin bilerek seçtiği / boşalttığı rol, yerel hesap ve AD'den eklenmemiş kişi korunur.
 * Gerçek H2 şeması (app_users + audit_log), işlemsiz ({@code NOT_SUPPORTED} — üretimdeki gibi otomatik işlem).
 */
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class AdOrgRoleLockRepairTest {

    @Autowired AppUserRepository users;
    @Autowired DataSource dataSource;

    private JdbcTemplate jdbc;
    private DeploymentHistoryService history;
    private AuditService audit;
    private SchedulerService scheduler;
    private AdOrgRoleLockRepair job;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        clean();
        history = mock(DeploymentHistoryService.class);
        when(history.liveOtherVersions()).thenReturn(List.of());
        audit = mock(AuditService.class);
        scheduler = mock(SchedulerService.class);
        when(scheduler.callWithSchedulerLock(eq(AdOrgRoleLockRepair.LOCK), any(Supplier.class)))
                .thenAnswer(i -> Optional.ofNullable(((Supplier<?>) i.getArgument(1)).get()));
        job = new AdOrgRoleLockRepair(jdbc, provider(scheduler), provider(history), provider(audit));
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM audit_log");
        users.deleteAll();
        jdbc.execute(StandaloneMonitorDeletionBackfill.MARKERS_DDL);
        jdbc.update("DELETE FROM schema_patch_markers WHERE patch_key = ?", AdOrgRoleLockRepair.KEY);
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(value);
        return p;
    }

    private AppUser user(String username, String authSource, String orgRole, Boolean locked) {
        AppUser u = new AppUser();
        u.setUsername(username);
        u.setAuthSource(authSource);
        u.setSystemRole("USER");
        u.setActive(true);
        u.setOrgRole(orgRole);
        u.setOrgRoleLocked(locked);
        return users.save(u);
    }

    private void provisioned(AppUser u) {
        jdbc.update("INSERT INTO audit_log (event_type, event_time, resource_type, resource_id, detail) VALUES (?, ?, ?, ?, ?)",
                "USER_PROVISION_FROM_AD", "2026-11-12T08:00:00", "USER", u.getUsername(), "{\"source\":\"ad_search\"}");
    }

    private void updated(AppUser u, String changes) {
        jdbc.update("INSERT INTO audit_log (event_type, event_time, resource_type, resource_id, changes) VALUES (?, ?, ?, ?, ?)",
                "USER_UPDATE", "2026-11-12T09:00:00", "USER", String.valueOf(u.getId()), changes);
    }

    private Boolean locked(AppUser u) {
        return users.findById(u.getId()).orElseThrow().getOrgRoleLocked();
    }

    private boolean marked() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM schema_patch_markers WHERE patch_key = ?", Integer.class,
                AdOrgRoleLockRepair.KEY);
        return n != null && n > 0;
    }

    @Test
    @DisplayName("Yalnız hatalı satırlar onarılır: AD'den eklenmiş + rol boş + kilitli + rolü sonradan elle değiştirilmemiş")
    void repairsOnlyBuggyRows() {
        AppUser buggy = user("N10001", "LDAP", null, true);
        provisioned(buggy);
        AppUser buggyBlank = user("N10002", "LDAP", "", true);
        provisioned(buggyBlank);
        updated(buggyBlank, "{\"teamId\":{\"from\":1,\"to\":2}}");                 // org rolüne dokunmayan düzenleme engel değil
        AppUser chosen = user("N10003", "LDAP", "PO", true);                     // yönetici rol seçmiş — kilit doğru
        provisioned(chosen);
        AppUser cleared = user("N10004", "LDAP", null, true);                    // yönetici rolü sonradan bilerek boşaltmış
        provisioned(cleared);
        updated(cleared, "{\"orgRole\":{\"from\":\"TECH\",\"to\":null}}");
        AppUser notProvisioned = user("N10005", "LDAP", null, true);             // girişle gelmiş; "AD'den ekle" kaydı yok
        AppUser local = user("yerel", "LOCAL", null, true);                      // yerel hesap — kapsam dışı
        provisioned(local);
        AppUser fine = user("N10006", "LDAP", null, false);                      // zaten kilitsiz
        provisioned(fine);

        job.run();

        assertThat(locked(buggy)).isFalse();
        assertThat(locked(buggyBlank)).isFalse();
        assertThat(locked(chosen)).isTrue();
        assertThat(locked(cleared)).isTrue();
        assertThat(locked(notProvisioned)).isTrue();
        assertThat(locked(local)).isTrue();
        assertThat(locked(fine)).isFalse();
        assertThat(users.findById(chosen.getId()).orElseThrow().getOrgRole()).isEqualTo("PO");
        assertThat(marked()).isTrue();
        Integer rows = jdbc.queryForObject("SELECT rows_affected FROM schema_patch_markers WHERE patch_key = ?", Integer.class,
                AdOrgRoleLockRepair.KEY);
        assertThat(rows).isEqualTo(2);
        // her açılan kilit için bir denetim kaydı — yalnız onlar için
        verify(audit).recordSystemEvent(eq("USER_ORG_ROLE_UNLOCK"), eq("USER"), eq(String.valueOf(buggy.getId())),
                contains(AdOrgRoleLockRepair.REASON));
        verify(audit).recordSystemEvent(eq("USER_ORG_ROLE_UNLOCK"), eq("USER"), eq(String.valueOf(buggyBlank.getId())),
                contains("N10002"));
        verify(audit, times(2)).recordSystemEvent(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("Bir kez koşar: nişandan sonra oluşan benzer satıra dokunulmaz, kilit bile alınmaz")
    void runsOnce() {
        job.run();
        assertThat(marked()).isTrue();

        AppUser later = user("N20001", "LDAP", null, true);
        provisioned(later);
        job.run();
        new AdOrgRoleLockRepair(jdbc, provider(scheduler), provider(history), provider(audit)).run();   // başka pod / yeniden başlama

        assertThat(locked(later)).isTrue();
        verify(scheduler, times(1)).callWithSchedulerLock(eq(AdOrgRoleLockRepair.LOCK), any());
        verify(audit, never()).recordSystemEvent(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("Başka sürüm koşan canlı pod varken ya da sürümler okunamazken bekler; sonra onarır")
    void waitsForSingleVersion() {
        AppUser buggy = user("N30001", "LDAP", null, true);
        provisioned(buggy);

        when(history.liveOtherVersions()).thenReturn(List.of(Map.of("pod", "eski-pod", "version", "20.196.1")));
        job.run();
        when(history.liveOtherVersions()).thenReturn(null);
        job.run();
        assertThat(locked(buggy)).isTrue();
        assertThat(marked()).isFalse();
        verify(scheduler, never()).callWithSchedulerLock(anyString(), any());

        when(history.liveOtherVersions()).thenReturn(List.of());
        job.run();
        assertThat(locked(buggy)).isFalse();
        assertThat(marked()).isTrue();
    }

    @Test
    @DisplayName("Kilit başka pod'dayken hiçbir şey yazılmaz; hata sayılmaz")
    @SuppressWarnings("unchecked")
    void lockHeldElsewhere() {
        AppUser buggy = user("N40001", "LDAP", null, true);
        provisioned(buggy);
        when(scheduler.callWithSchedulerLock(eq(AdOrgRoleLockRepair.LOCK), any(Supplier.class))).thenReturn(Optional.empty());

        job.run();

        assertThat(locked(buggy)).isTrue();
        assertThat(marked()).isFalse();
    }

    @Test
    @DisplayName("Koşullu yazma: aday okunduktan sonra rol atanan / kilidi açılan satıra dokunulmaz")
    void conditionalWrite() {
        AppUser roleSet = user("N50001", "LDAP", "MANAGER", true);
        AppUser unlocked = user("N50002", "LDAP", null, false);
        AppUser buggy = user("N50003", "LDAP", null, true);

        assertThat(jdbc.update(AdOrgRoleLockRepair.SQL_UNLOCK, roleSet.getId())).isZero();
        assertThat(jdbc.update(AdOrgRoleLockRepair.SQL_UNLOCK, unlocked.getId())).isZero();
        assertThat(jdbc.update(AdOrgRoleLockRepair.SQL_UNLOCK, buggy.getId())).isEqualTo(1);
        assertThat(locked(roleSet)).isTrue();
        assertThat(users.findById(roleSet.getId()).orElseThrow().getOrgRole()).isEqualTo("MANAGER");
    }

    @Test
    @DisplayName("Tur sınırı: BATCH'ten fazla aday varsa nişan yazılmaz, sonraki tur kalanı bitirir")
    void batchesUntilDone() {
        List<AppUser> all = new ArrayList<>();
        for (int i = 0; i < AdOrgRoleLockRepair.BATCH + 3; i++) {
            AppUser u = new AppUser();
            u.setUsername("B" + (100000 + i));
            u.setAuthSource("LDAP");
            u.setSystemRole("USER");
            u.setActive(true);
            u.setOrgRoleLocked(true);
            all.add(u);
        }
        users.saveAll(all);
        jdbc.batchUpdate("INSERT INTO audit_log (event_type, event_time, resource_type, resource_id) VALUES (?, ?, ?, ?)",
                all.stream().map(u -> new Object[]{"USER_PROVISION_FROM_AD", "2026-11-12T08:00:00", "USER", u.getUsername()}).toList());

        job.run();
        assertThat(marked()).isFalse();
        Integer left = jdbc.queryForObject("SELECT COUNT(*) FROM app_users WHERE org_role_locked = TRUE", Integer.class);
        assertThat(left).isEqualTo(3);

        job.run();
        assertThat(marked()).isTrue();
        left = jdbc.queryForObject("SELECT COUNT(*) FROM app_users WHERE org_role_locked = TRUE", Integer.class);
        assertThat(left).isZero();
    }

    @Test
    @DisplayName("Hata: nişan yazılmaz, sayaç dolunca pod bırakır (sonsuz deneme yok)")
    @SuppressWarnings("unchecked")
    void failuresAreBounded() {
        when(scheduler.callWithSchedulerLock(eq(AdOrgRoleLockRepair.LOCK), any(Supplier.class)))
                .thenThrow(new IllegalStateException("veritabanı yok"));

        for (int i = 0; i < AdOrgRoleLockRepair.MAX_ATTEMPTS + 5; i++) job.run();

        assertThat(marked()).isFalse();
        verify(scheduler, times(AdOrgRoleLockRepair.MAX_ATTEMPTS)).callWithSchedulerLock(eq(AdOrgRoleLockRepair.LOCK), any());
    }

    @Test
    @DisplayName("Denetim kaydı yazılamazsa onarım yine tamamlanır")
    void auditFailureDoesNotBlock() {
        AppUser buggy = user("N60001", "LDAP", null, true);
        provisioned(buggy);
        org.mockito.Mockito.doThrow(new IllegalStateException("denetim yok")).when(audit)
                .recordSystemEvent(anyString(), anyString(), anyString(), anyString());

        job.run();

        assertThat(locked(buggy)).isFalse();
        assertThat(marked()).isTrue();
    }
}
