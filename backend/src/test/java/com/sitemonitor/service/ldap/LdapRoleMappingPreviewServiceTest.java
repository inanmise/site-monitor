package com.sitemonitor.service.ldap;

import com.sitemonitor.model.AppUser;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.service.LdapDirectoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** "Kim ne olacak?" önizlemesi (2026-10-11) — giriş yoluyla AYNI korumalar; hiçbir şey yazmaz. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LdapRoleMappingPreviewServiceTest {

    @Mock AppUserRepository userRepo;
    @Mock LdapDirectoryService directory;
    LdapRoleMappingPreviewService svc;
    final List<AppUser> users = new ArrayList<>();

    @BeforeEach
    void setUp() {
        svc = new LdapRoleMappingPreviewService(userRepo, directory);
        when(userRepo.findByActiveTrueOrderByUsernameAsc()).thenReturn(users);
        when(userRepo.countBySystemRoleAndActiveTrue("ADMIN")).thenReturn(5L);
    }

    private AppUser user(String name, String role, String source, Boolean locked) {
        AppUser u = new AppUser();
        u.setId((long) (users.size() + 1));
        u.setUsername(name);
        u.setDisplayName(name + " Görünen Ad");
        u.setSystemRole(role);
        u.setAuthSource(source);
        u.setRoleLocked(locked);
        u.setActive(true);
        users.add(u);
        return u;
    }

    @SafeVarargs
    private void members(Set<String>... perRule) {
        List<LdapDirectoryService.GroupMembers> out = new ArrayList<>();
        for (int i = 0; i < perRule.length; i++) out.add(new LdapDirectoryService.GroupMembers("g" + i, List.of("CN=g" + i), perRule[i], false));
        when(directory.membersOfGroups(anyList())).thenReturn(out);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Integer> counts(Map<String, Object> p) { return (Map<String, Integer>) p.get("counts"); }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> p) { return (List<Map<String, Object>>) p.get("rows"); }

    @Test
    @DisplayName("ilk eşleşen kural; yükseltme / düşürme / aynı; yalnız LDAP hesapları; düşürme listenin başında")
    void outcomes() {
        user("ALI", "USER", "LDAP", null);
        user("VELI", "TEAM_ADMIN", "LDAP", null);
        user("AYSE", "AUDIT", "LDAP", null);
        user("YEREL", "USER", "LOCAL", null);      // yerel hesap önizlemeye girmez
        members(Set.of("ALI", "AYSE"), Set.of("VELI", "ALI"));
        var rules = List.of(new LdapRoleMapping.Rule("Denetim", "AUDIT"), new LdapRoleMapping.Rule("Kullanicilar", "USER"));
        var p = svc.preview(rules, "DERIVED");
        assertThat(p.get("ldap_users")).isEqualTo(3);
        assertThat(counts(p).get("PROMOTE")).isEqualTo(1);   // ALI USER → AUDIT (ilk kural)
        assertThat(counts(p).get("DEMOTE")).isEqualTo(1);    // VELI TEAM_ADMIN → USER
        assertThat(counts(p).get("SAME")).isEqualTo(1);      // AYSE AUDIT
        assertThat(rows(p)).extracting(r -> r.get("username")).containsExactly("VELI", "ALI");
        assertThat(rows(p).get(1).get("rule_group")).isEqualTo("Denetim");
        assertThat(LdapRoleMappingPreviewService.hasChanges(p)).isTrue();
    }

    @Test
    @DisplayName("kilitli rol LOCKED, son etkin ADMIN LAST_ADMIN, sabit varsayılan ADMIN/AUDIT'i PROTECTED tutar")
    void protections() {
        user("KILITLI", "TEAM_ADMIN", "LDAP", true);
        user("ADMIN1", "ADMIN", "LDAP", null);
        user("DENETCI", "AUDIT", "LDAP", null);
        user("SIRADAN", "USER", "LDAP", null);
        when(userRepo.countBySystemRoleAndActiveTrue("ADMIN")).thenReturn(1L);
        members(Set.of("KILITLI", "ADMIN1"));
        var p = svc.preview(List.of(new LdapRoleMapping.Rule("Herkes", "USER")), "TEAM_ADMIN");
        assertThat(counts(p).get("LOCKED")).isEqualTo(1);
        assertThat(counts(p).get("LAST_ADMIN")).isEqualTo(1);
        assertThat(counts(p).get("PROTECTED")).isEqualTo(1);   // DENETCI: eşleşme yok, sabit varsayılan AUDIT'i düşürmez
        assertThat(counts(p).get("PROMOTE")).isEqualTo(1);     // SIRADAN USER → TEAM_ADMIN (varsayılan)
        assertThat(LdapRoleMappingPreviewService.hasChanges(p)).isTrue();
    }

    @Test
    @DisplayName("\"bugünkü kural\" varsayılanında eşleşmeyenler DERIVED (satır yok); kural yoksa AD'ye hiç gidilmez")
    void derivedFallback_noDirectoryCallWithoutRules() {
        user("ALI", "USER", "LDAP", null);
        var p = svc.preview(List.of(), "DERIVED");
        assertThat(counts(p).get("DERIVED")).isEqualTo(1);
        assertThat(rows(p)).isEmpty();
        assertThat(LdapRoleMappingPreviewService.hasChanges(p)).isFalse();
        verifyNoInteractions(directory);
    }

    @Test
    @DisplayName("kural satırı: AD'de bulunan grup / üye sayısı ve uygulamada eşleşen hesap sayısı")
    void ruleRows() {
        user("ALI", "USER", "LDAP", null);
        members(Set.of("ALI", "AD_DE_VAR_UYGULAMADA_YOK"));
        var p = svc.preview(List.of(new LdapRoleMapping.Rule("Denetim", "AUDIT")), null);
        @SuppressWarnings("unchecked") var r = ((List<Map<String, Object>>) p.get("rules")).get(0);
        assertThat(r.get("ad_members")).isEqualTo(2);
        assertThat(r.get("matched_users")).isEqualTo(1);
        assertThat(r.get("groups_found")).isEqualTo(1);
        verify(directory).membersOfGroups(List.of("Denetim"));
    }
}
