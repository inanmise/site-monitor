package com.sitemonitor.service;

import com.sitemonitor.model.AppUser;
import com.sitemonitor.model.UserTeamSource;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.EscalationContactRepository;
import com.sitemonitor.repository.PasswordHistoryRepository;
import com.sitemonitor.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * AD'den kullanıcı çekme (2026-11-11, ürün isteği): {@link UserService#provisionAdUser} kişi giriş yapmadan
 * LDAP hesabı oluşturur — parolasız, AD profil alanları + yöneticinin seçtiği rol/takım, KİLİTLİ (giriş
 * provizyonu ezmesin), takım kaynağı MANUAL. Var olan ad reddedilir; ADMIN dışı rollerde takım zorunlu.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserServiceAdProvisionTest {

    @Mock AppUserRepository userRepo;
    @Mock TeamRepository teamRepo;
    @Mock CertificateInventoryRepository inventoryRepo;
    @Mock EscalationContactRepository contactRepo;
    @Mock PasswordHistoryRepository passwordHistoryRepo;

    private UserService service;
    private TeamSourceFakes.Store sources;

    @BeforeEach
    void setUp() {
        service = new UserService(userRepo, teamRepo, inventoryRepo, contactRepo, passwordHistoryRepo);
        sources = new TeamSourceFakes.Store();
        service.setTeamSources(sources.service);
        ReflectionTestUtils.setField(service, "passwordMinLength", 6);
        when(userRepo.existsByUsername(anyString())).thenReturn(false);
        when(userRepo.save(any())).thenAnswer(inv -> {
            AppUser u = inv.getArgument(0);
            if (u.getId() == null) u.setId(77L);
            return u;
        });
    }

    private static Map<String, Object> adAttrs() {
        return Map.of(
                "sAMAccountName", "MP60146",
                "mail", "ayse.yilmaz@banka.com",
                "cn", "60146",
                "givenName", "Ayşe",
                "sn", "Yılmaz",
                "displayName", "Ayşe Yılmaz (Dijital Kanallar)");
    }

    @Test
    @DisplayName("AD'den çekilen kullanıcı: LDAP hesabı, parolasız, AD profili + yönetici rol/takım, kilitli, MANUAL")
    void provisionsLdapAccountWithAdminChoices() {
        AppUser u = service.provisionAdUser("mp60146", adAttrs(), "USER", "TECH", List.of(5L), null);

        assertThat(u.getUsername()).isEqualTo("MP60146");          // normalize → BÜYÜK harf
        assertThat(u.getAuthSource()).isEqualTo("LDAP");
        assertThat(u.getPasswordHash()).isNull();                  // AD kullanıcısı yerel parola tutmaz
        assertThat(u.getActive()).isTrue();
        // AD profil alanları
        assertThat(u.getEmail()).isEqualTo("ayse.yilmaz@banka.com");
        assertThat(u.getEmployeeId()).isEqualTo("60146");
        assertThat(u.getFirstName()).isEqualTo("Ayşe");
        assertThat(u.getLastName()).isEqualTo("Yılmaz");
        assertThat(u.getDisplayName()).isEqualTo("Ayşe Yılmaz (Dijital Kanallar)");
        // Yönetici seçimi + KİLİT (giriş provizyonu ezmesin)
        assertThat(u.getSystemRole()).isEqualTo("USER");
        assertThat(u.getRoleLocked()).isTrue();
        assertThat(u.getOrgRole()).isEqualTo("TECH");
        assertThat(u.getOrgRoleLocked()).isTrue();
        assertThat(u.getTeamIds()).containsExactly(5L);
        assertThat(u.getTeamId()).isEqualTo(5L);
        assertThat(u.getTeamLocked()).isTrue();
        // Takım kaynağı MANUAL (LDAP budaması dokunmaz)
        assertThat(sources.get(77L, 5L).getSource()).isEqualTo(UserTeamSource.MANUAL);
    }

    @Test
    @DisplayName("Görünen ad AD'de yoksa kullanıcı adına düşer")
    void displayNameFallsBackToUsername() {
        AppUser u = service.provisionAdUser("zz1", Map.of("sAMAccountName", "ZZ1"), "USER", null, List.of(3L), null);
        assertThat(u.getDisplayName()).isEqualTo("ZZ1");
        assertThat(u.getOrgRole()).isNull();
    }

    @Test
    @DisplayName("Birincil takım: kümedeyse korunur, değilse ilk eleman")
    void primaryTeamHonoured() {
        assertThat(service.provisionAdUser("a", adAttrs(), "USER", null, List.of(5L, 9L), 9L).getTeamId()).isEqualTo(9L);
        assertThat(service.provisionAdUser("b", adAttrs(), "USER", null, List.of(5L, 9L), 42L).getTeamId()).isEqualTo(5L);
    }

    @Test
    @DisplayName("Zaten var olan kullanıcı adı reddedilir")
    void rejectsExistingUsername() {
        when(userRepo.existsByUsername("MP60146")).thenReturn(true);
        assertThatThrownBy(() -> service.provisionAdUser("mp60146", adAttrs(), "USER", null, List.of(5L), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("ADMIN dışı rolde takım zorunlu; ADMIN rolde takımsız olabilir")
    void teamRequiredUnlessAdmin() {
        assertThatThrownBy(() -> service.provisionAdUser("u1", adAttrs(), "USER", null, List.of(), null))
                .isInstanceOf(IllegalArgumentException.class);
        AppUser admin = service.provisionAdUser("u2", adAttrs(), "ADMIN", null, List.of(), null);
        assertThat(admin.getSystemRole()).isEqualTo("ADMIN");
        assertThat(admin.getTeamIds()).isEmpty();
    }

    @Test
    @DisplayName("Geçersiz e-posta reddedilir (AD'de bozuk mail)")
    void rejectsInvalidEmail() {
        assertThatThrownBy(() -> service.provisionAdUser("u3",
                Map.of("sAMAccountName", "U3", "mail", "bozuk-mail"), "USER", null, List.of(5L), null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
