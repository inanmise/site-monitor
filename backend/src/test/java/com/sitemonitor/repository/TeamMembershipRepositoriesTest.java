package com.sitemonitor.repository;

import com.sitemonitor.model.AppUser;
import com.sitemonitor.model.UserTeamSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prod hatası 2026-09-26 sorgularının H2 üstünde SORGU-DÜZEYİ kanıtı: sicil eşleşmesi (boşluk/harf
 * duyarsız, kopyada istisna yok) ve üyelik kaynak izi tablosunun silme uçları (açık transaction).
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class TeamMembershipRepositoriesTest {

    @Autowired AppUserRepository userRepo;
    @Autowired UserTeamSourceRepository sourceRepo;

    private AppUser user(String username, String sicil) {
        AppUser u = new AppUser();
        u.setUsername(username);
        u.setEmployeeId(sicil);
        u.setSystemRole("USER");
        u.setActive(true);
        return userRepo.save(u);
    }

    @Test
    @DisplayName("sicil eşleşmesi baş/son boşluk ve harf duyarsız; aynı sicilli iki satır LİSTE döner (istisna yok)")
    void findAllByEmployeeIdNormalized() {
        user("U1", " 100001 ");
        user("U2", "100001");
        user("U3", "n100002");
        user("U4", "0100001");   // baştaki sıfır normalize EDİLMEZ

        assertThat(userRepo.findAllByEmployeeIdNormalized("100001")).extracting(AppUser::getUsername)
                .containsExactly("U1", "U2");
        assertThat(userRepo.findAllByEmployeeIdNormalized(" N100002")).extracting(AppUser::getUsername)
                .containsExactly("U3");
        assertThat(userRepo.findAllByEmployeeIdNormalized("999")).isEmpty();
    }

    @Test
    @DisplayName("üyelik kaynak izi: (kullanıcı, takım) başına TEK satır — ikinci kayıt günceller")
    void source_upsertPerUserTeam() {
        sourceRepo.save(new UserTeamSource(1L, 10L, UserTeamSource.LDAP_GROUP, "Takım A", "2026-09-26T00:00:00", "LDAP"));
        sourceRepo.save(new UserTeamSource(1L, 10L, UserTeamSource.MANUAL, "admin", "2026-09-26T00:00:01", "yonetici"));
        sourceRepo.save(new UserTeamSource(1L, 11L, UserTeamSource.LDAP_COMPANY, "company=X", "2026-09-26T00:00:02", "LDAP"));

        List<UserTeamSource> rows = sourceRepo.findByUserId(1L);
        assertThat(rows).hasSize(2);
        assertThat(rows).filteredOn(r -> r.getTeamId() == 10L).extracting(UserTeamSource::getSource).containsExactly(UserTeamSource.MANUAL);
        assertThat(sourceRepo.findByTeamId(11L)).hasSize(1);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)   // ambiyans tx YOK: silme uçlarının kendi tx'i şart
    @DisplayName("türetilmiş silme uçları transaction'sız çağrıda da çalışır (open-in-view=false tuzağı)")
    void deletes_workWithoutAmbientTransaction() {
        try {
            sourceRepo.save(new UserTeamSource(7L, 10L, UserTeamSource.LDAP_GROUP, "A", null, null));
            sourceRepo.save(new UserTeamSource(7L, 11L, UserTeamSource.MANUAL, "B", null, null));
            sourceRepo.save(new UserTeamSource(8L, 10L, UserTeamSource.MANUAL, "C", null, null));

            sourceRepo.deleteByUserIdAndTeamIdIn(7L, List.of(10L));
            assertThat(sourceRepo.findByUserId(7L)).extracting(UserTeamSource::getTeamId).containsExactly(11L);

            sourceRepo.deleteByUserId(7L);
            assertThat(sourceRepo.findByUserId(7L)).isEmpty();
            assertThat(sourceRepo.findByUserId(8L)).hasSize(1);
        } finally {
            sourceRepo.deleteAll();
        }
    }
}
