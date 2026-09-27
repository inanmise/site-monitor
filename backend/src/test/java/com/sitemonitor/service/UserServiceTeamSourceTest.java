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

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * Yönetici tarafı (hipotez 8 + kaynak izi): elle değişen üyelik MANUAL işaretlenir (LDAP budaması ona
 * dokunmaz), çıkarılanın izi silinir; elle girilen müdür sicili kopya sicilde 500 üretmez. Yer tutucu adlar.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserServiceTeamSourceTest {

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
        when(contactRepo.findByUserId(anyLong())).thenReturn(List.of());
        when(userRepo.save(any())).thenAnswer(inv -> {
            AppUser u = inv.getArgument(0);
            if (u.getId() == null) u.setId(99L);
            return u;
        });
    }

    private AppUser user(long id, Long... teams) {
        AppUser u = new AppUser();
        u.setId(id);
        u.setUsername("U" + id);
        u.setSystemRole("USER");
        u.setAuthSource("LDAP");
        u.setTeamIds(new LinkedHashSet<>(List.of(teams)));
        u.setTeamId(teams.length > 0 ? teams[0] : null);
        when(userRepo.findById(id)).thenReturn(Optional.of(u));
        return u;
    }

    @Test
    @DisplayName("elle eklenen takım MANUAL; çıkarılanın izi silinir; değişmeyen takımın (AD grubu) izi KORUNUR")
    void updateUser_marksAddedManual_forgetsRemoved_keepsUnchanged() {
        AppUser u = user(5L, 10L, 11L);
        sources.put(5L, 10L, UserTeamSource.LDAP_GROUP, "Takım A");
        sources.put(5L, 11L, UserTeamSource.LDAP_GROUP, "Takım B");

        service.updateUser(5L, null, null, null, null, List.of(10L, 12L), null, null);

        assertThat(u.getTeamIds()).containsExactly(10L, 12L);
        assertThat(u.getTeamLocked()).isTrue();
        assertThat(sources.get(5L, 10L).getSource()).isEqualTo(UserTeamSource.LDAP_GROUP);
        assertThat(sources.get(5L, 12L).getSource()).isEqualTo(UserTeamSource.MANUAL);
        assertThat(sources.get(5L, 11L)).isNull();
    }

    @Test
    @DisplayName("aynı küme yeniden kaydedilince iz değişmez (form her kayıtta team_ids gönderir)")
    void updateUser_sameSet_doesNotTouchSources() {
        user(6L, 10L);
        sources.put(6L, 10L, UserTeamSource.LDAP_GROUP, "Takım A");

        service.updateUser(6L, null, null, null, null, List.of(10L), null, null);

        assertThat(sources.get(6L, 10L).getSource()).isEqualTo(UserTeamSource.LDAP_GROUP);
    }

    @Test
    @DisplayName("yönetici oluşturduğu kullanıcının takımları MANUAL")
    void createUser_marksManual() {
        AppUser created = service.createUser("yeni", "parola123", "Yeni", "yeni@example.com", "100070",
                "USER", List.of(10L, 11L), null);

        assertThat(sources.get(created.getId(), 10L).getSource()).isEqualTo(UserTeamSource.MANUAL);
        assertThat(sources.get(created.getId(), 11L).getSource()).isEqualTo(UserTeamSource.MANUAL);
    }

    @Test
    @DisplayName("kullanıcı silinince kaynak izleri de silinir")
    void deleteUser_forgetsSources() {
        when(teamRepo.findByLeaderId(7L)).thenReturn(List.of());
        sources.put(7L, 10L, UserTeamSource.MANUAL, "admin");

        service.deleteUser(7L);

        assertThat(sources.get(7L, 10L)).isNull();
    }

    @Test
    @DisplayName("H6: elle girilen müdür sicili iki kullanıcıda kayıtlıysa istisna YOK, bağ kurulmaz; kırpılmış sicil bağlanır")
    void applyProfileFields_duplicateSicil_noException() {
        AppUser a = new AppUser(); a.setId(20L); a.setActive(true); a.setAuthSource("LDAP"); a.setEmployeeId("100003");
        AppUser b = new AppUser(); b.setId(21L); b.setActive(true); b.setAuthSource("LDAP"); b.setEmployeeId("100003");
        when(userRepo.findAllByEmployeeIdNormalized("100003")).thenReturn(List.of(a, b));
        AppUser target = user(8L);

        assertThatCode(() -> service.applyProfileFields(target, Map.of("manager_sicil", "100003"))).doesNotThrowAnyException();
        assertThat(target.getManagerSicil()).isEqualTo("100003");
        assertThat(target.getManagerId()).isNull();

        when(userRepo.findAllByEmployeeIdNormalized("100004")).thenReturn(List.of(a));
        service.applyProfileFields(target, Map.of("manager_sicil", " 100004 "));
        assertThat(target.getManagerSicil()).isEqualTo("100004");
        assertThat(target.getManagerId()).isEqualTo(20L);
    }
}
