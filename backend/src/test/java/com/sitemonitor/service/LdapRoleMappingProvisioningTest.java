package com.sitemonitor.service;

import com.sitemonitor.model.AppUser;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.repository.EscalationContactRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.service.ldap.LdapRoleMapping;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LDAP grup → rol eşlemesi giriş yolunda (2026-10-11, kullanıcı kararı: "açılırsa, elle değişeni ezmez").
 * KAPALIYKEN rol bugünkü kuralla türetilir (mevcut LdapProvisioningServiceTest bunu kilitler; burada da bir örnek);
 * AÇIKKEN: ilk eşleşen kural, kilitli rol ezilmez, son etkin ADMIN düşürülmez, sabit varsayılan ADMIN/AUDIT'i düşürmez,
 * "bugünkü kural" varsayılanı eskisiyle aynı; rol değişirse USER_ROLE_FROM_AD denetlenir.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LdapRoleMappingProvisioningTest {

    @Mock AppUserRepository userRepo;
    @Mock TeamRepository teamRepo;
    @Mock LdapDirectoryService directory;
    @Mock EscalationContactRepository contactRepo;
    @Mock AppSettingsService appSettings;
    @Mock AuditService auditService;
    @Mock LdapSettingsService ldapSettings;
    private LdapProvisioningService service;

    static final String ADMINS_DN = "CN=SiteMonitor Admins,OU=Groups,DC=example,DC=com";
    static final String AUDIT_DN = "CN=SM-Denetim,OU=Groups,DC=example,DC=com";

    @BeforeEach
    void setUp() {
        service = new LdapProvisioningService(userRepo, teamRepo, directory, contactRepo, appSettings,
                new TeamSourceFakes.Store().service, auditService);
        service.setLdapSettings(ldapSettings);
        when(appSettings.getBoolean(anyString(), any(Boolean.class))).thenAnswer(inv -> inv.getArgument(1));
        when(appSettings.getInt(anyString(), org.mockito.ArgumentMatchers.anyInt())).thenAnswer(inv -> inv.getArgument(1));
        when(appSettings.getCsv(anyString(), anyString())).thenAnswer(inv -> List.of(((String) inv.getArgument(1)).split(",")));
        AtomicLong seq = new AtomicLong(0);
        when(userRepo.save(any(AppUser.class))).thenAnswer(inv -> {
            AppUser u = inv.getArgument(0);
            if (u.getId() == null) u.setId(seq.incrementAndGet());
            return u;
        });
        when(teamRepo.save(any(Team.class))).thenAnswer(inv -> inv.getArgument(0));
        when(userRepo.findByUsername(anyString())).thenReturn(Optional.empty());
        when(teamRepo.findByName(anyString())).thenReturn(Optional.empty());
        when(directory.groupMail(anyString())).thenReturn(Optional.empty());
        when(directory.findOne(anyString(), anyString())).thenReturn(Optional.empty());
        when(userRepo.countBySystemRoleAndActiveTrue("ADMIN")).thenReturn(3L);
    }

    private void mapping(String fallback, LdapRoleMapping.Rule... rules) {
        when(ldapSettings.activeRoleMapping()).thenReturn(new LdapSettingsService.RoleMappingConfig(List.of(rules), fallback));
    }

    private AppUser existing(String username, String role, Boolean locked) {
        AppUser u = new AppUser();
        u.setId(700L + username.length());
        u.setUsername(username);
        u.setSystemRole(role);
        u.setRoleLocked(locked);
        u.setAuthSource("LDAP");
        u.setActive(true);
        when(userRepo.findByUsername(username)).thenReturn(Optional.of(u));
        return u;
    }

    private static Map<String, Object> attrs(String... groups) {
        return Map.of("cn", "12345", "displayName", "Kişi", "memberOf", List.of(groups));
    }

    @Test
    @DisplayName("eşleme KAPALI (activeRoleMapping=null) → bugünkü kural: PO/müdür değil → USER; denetim yok")
    void mappingOff_derivedRuleUnchanged() {
        when(ldapSettings.activeRoleMapping()).thenReturn(null);
        AppUser u = service.provisionFromAd("kisi1", "CN=kisi1,DC=example,DC=com", attrs(ADMINS_DN));
        assertThat(u.getSystemRole()).isEqualTo("USER");
        verify(auditService, never()).recordSystemEvent(eq("USER_ROLE_FROM_AD"), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("açık + DN kuralı eşleşti → rol o kural (USER → AUDIT), USER_ROLE_FROM_AD denetlenir")
    void groupMatch_appliesRoleAndAudits() {
        mapping("DERIVED", new LdapRoleMapping.Rule(AUDIT_DN, "AUDIT"));
        existing("DENETCI", "USER", null);
        AppUser u = service.provisionFromAd("denetci", "CN=denetci,DC=example,DC=com", attrs(AUDIT_DN.toUpperCase()));
        assertThat(u.getSystemRole()).isEqualTo("AUDIT");
        verify(auditService).recordSystemEvent(eq("USER_ROLE_FROM_AD"), eq("USER"), anyString(), contains("AUDIT"));
    }

    @Test
    @DisplayName("ilk eşleşen kural kazanır (sıra yöneticide): ADMIN kuralı önde → ADMIN")
    void firstRuleWins() {
        mapping("DERIVED", new LdapRoleMapping.Rule("SiteMonitor Admins", "ADMIN"), new LdapRoleMapping.Rule("SM-Denetim", "AUDIT"));
        existing("IKIGRUP", "USER", null);
        AppUser u = service.provisionFromAd("ikigrup", "CN=ikigrup,DC=example,DC=com", attrs(AUDIT_DN, ADMINS_DN));
        assertThat(u.getSystemRole()).isEqualTo("ADMIN");
    }

    @Test
    @DisplayName("rol elle KİLİTLİ → eşleşen kural bile ezmez, denetim yok")
    void lockedRole_neverOverwritten() {
        mapping("DERIVED", new LdapRoleMapping.Rule(ADMINS_DN, "ADMIN"));
        existing("ELLE", "TEAM_ADMIN", true);
        AppUser u = service.provisionFromAd("elle", "CN=elle,DC=example,DC=com", attrs(ADMINS_DN));
        assertThat(u.getSystemRole()).isEqualTo("TEAM_ADMIN");
        verify(auditService, never()).recordSystemEvent(eq("USER_ROLE_FROM_AD"), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("açık kural ADMIN'i düşürebilir — ama SON etkin ADMIN düşürülmez")
    void lastActiveAdmin_notDemoted() {
        mapping("DERIVED", new LdapRoleMapping.Rule(AUDIT_DN, "USER"));
        existing("TEKADMIN", "ADMIN", null);
        when(userRepo.countBySystemRoleAndActiveTrue("ADMIN")).thenReturn(1L);
        AppUser u = service.provisionFromAd("tekadmin", "CN=tekadmin,DC=example,DC=com", attrs(AUDIT_DN));
        assertThat(u.getSystemRole()).isEqualTo("ADMIN");

        when(userRepo.countBySystemRoleAndActiveTrue("ADMIN")).thenReturn(4L);
        AppUser u2 = service.provisionFromAd("tekadmin", "CN=tekadmin,DC=example,DC=com", attrs(AUDIT_DN));
        assertThat(u2.getSystemRole()).isEqualTo("USER");
    }

    @Test
    @DisplayName("eşleşme yok + sabit varsayılan rol: USER → TEAM_ADMIN olur ama elle verilmiş ADMIN/AUDIT düşmez")
    void fixedFallback_neverDemotesAdminOrAudit() {
        mapping("TEAM_ADMIN", new LdapRoleMapping.Rule(ADMINS_DN, "ADMIN"));
        existing("SIRADAN", "USER", null);
        assertThat(service.provisionFromAd("siradan", "CN=siradan,DC=example,DC=com", attrs()).getSystemRole()).isEqualTo("TEAM_ADMIN");
        existing("DENETIM", "AUDIT", null);
        assertThat(service.provisionFromAd("denetim", "CN=denetim,DC=example,DC=com", attrs()).getSystemRole()).isEqualTo("AUDIT");
    }

    @Test
    @DisplayName("eşleşme yok + \"bugünkü kural\" → PO/müdür değil kişi USER, elle ADMIN korunur (eski davranış)")
    void derivedFallback_sameAsToday() {
        mapping("DERIVED", new LdapRoleMapping.Rule(ADMINS_DN, "ADMIN"));
        existing("NORMAL", "TEAM_ADMIN", null);
        assertThat(service.provisionFromAd("normal", "CN=normal,DC=example,DC=com", attrs()).getSystemRole()).isEqualTo("USER");
        existing("ELADMIN", "ADMIN", null);
        assertThat(service.provisionFromAd("eladmin", "CN=eladmin,DC=example,DC=com", attrs()).getSystemRole()).isEqualTo("ADMIN");
    }

    @Test
    @DisplayName("ayar okunamazsa giriş düşmez — bugünkü kural uygulanır")
    void settingsFailure_fallsBackToDerived() {
        when(ldapSettings.activeRoleMapping()).thenThrow(new IllegalStateException("db down"));
        existing("HATA", "TEAM_ADMIN", null);
        AppUser u = service.provisionFromAd("hata", "CN=hata,DC=example,DC=com", attrs(ADMINS_DN));
        assertThat(u.getSystemRole()).isEqualTo("USER");
    }
}
