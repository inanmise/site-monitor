package com.sitemonitor.repository;

import com.sitemonitor.model.NotificationGroup;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bildirim grubu sorgularının H2 üstünde SORGU-DÜZEYİ kanıtı.
 *
 * <p>Bu sorgular JPQL — mock'lu bir servis testi onları hiç çalıştırmaz, dolayısıyla bir
 * {@code WHERE} koşulundaki hata ancak burada yakalanır.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class NotificationGroupRepositoryTest {

    private static final long TEAM_A = 1L;
    private static final long TEAM_B = 2L;

    @Autowired NotificationGroupRepository repo;

    private NotificationGroup save(long teamId, String name, boolean active, boolean isDefault) {
        NotificationGroup g = new NotificationGroup();
        g.setTeamId(teamId);
        g.setName(name);
        g.setEmails("n@example.com");
        g.setActive(active);
        g.setIsDefault(isDefault);
        return repo.save(g);
    }

    @Test
    @DisplayName("Aynı takımda AYNI ad reddedilir (büyük/küçük harf duyarsız)")
    void existsByTeamAndName_sameTeamSameName() {
        save(TEAM_A, "Nöbet", true, false);

        assertThat(repo.existsByTeamAndName(TEAM_A, "Nöbet", null)).isTrue();
        assertThat(repo.existsByTeamAndName(TEAM_A, "nöbet", null)).isTrue();
        assertThat(repo.existsByTeamAndName(TEAM_A, "NÖBET", null)).isTrue();
    }

    @Test
    @DisplayName("BAŞKA takımda aynı ad serbesttir")
    void existsByTeamAndName_otherTeamIsFree() {
        save(TEAM_A, "Nöbet", true, false);

        assertThat(repo.existsByTeamAndName(TEAM_B, "Nöbet", null)).isFalse();
    }

    @Test
    @DisplayName("Güncellemede KENDİ satırı sayılmaz (excludeId)")
    void existsByTeamAndName_excludesSelf() {
        NotificationGroup g = save(TEAM_A, "Nöbet", true, false);

        assertThat(repo.existsByTeamAndName(TEAM_A, "Nöbet", g.getId())).isFalse();
    }

    /**
     * Yumuşak silme döneminden kalan satırlar {@code active=false} ile duruyor. Adlarının
     * benzersizliği ENGELLEMEMESİ gerekir: kullanıcı o grubu hiçbir yerde göremiyor — listede
     * yok, seçilemiyor — ama aynı adı yeniden kullanmak istediğinde "bu takımda zaten var"
     * uyarısı alıyordu. Gruplar artık kalıcı siliniyor; bu koşul o eski satırlar için kalıyor.
     */
    @Test
    @DisplayName("PASİF satırın adı benzersizliği ENGELLEMEZ")
    void existsByTeamAndName_ignoresInactive() {
        save(TEAM_A, "Sy_MAIL_2", false, false);

        assertThat(repo.existsByTeamAndName(TEAM_A, "Sy_MAIL_2", null)).isFalse();
    }

    @Test
    @DisplayName("Varsayılan grup sorgusu yalnız AKTİF olanı bulur")
    void findDefault_onlyActive() {
        save(TEAM_A, "Eski", false, true);          // pasif ama isDefault kalmış (eski veri)
        NotificationGroup live = save(TEAM_A, "Güncel", true, true);

        assertThat(repo.findFirstByTeamIdAndIsDefaultTrueAndActiveTrue(TEAM_A))
                .get().extracting(NotificationGroup::getId).isEqualTo(live.getId());
    }

    @Test
    @DisplayName("Toplu takım sorgusu: yalnız istenen takımlar, aktif filtresiyle")
    void findByTeamIdIn_scopesAndFilters() {
        save(TEAM_A, "A1", true, false);
        save(TEAM_A, "A2", false, false);
        save(TEAM_B, "B1", true, false);

        List<NotificationGroup> activeOnly =
                repo.findByTeamIdInAndActiveTrueOrderByTeamIdAscNameAsc(List.of(TEAM_A));
        assertThat(activeOnly).extracting(NotificationGroup::getName).containsExactly("A1");

        List<NotificationGroup> all =
                repo.findByTeamIdInOrderByTeamIdAscNameAsc(List.of(TEAM_A, TEAM_B));
        assertThat(all).extracting(NotificationGroup::getName).containsExactly("A1", "A2", "B1");
    }

    @Test
    @DisplayName("clearOtherDefaults: takımın DİĞER varsayılanlarını indirir, korunanı bırakır")
    void clearOtherDefaults() {
        NotificationGroup keep = save(TEAM_A, "Yeni", true, true);
        NotificationGroup old = save(TEAM_A, "Eski", true, true);
        NotificationGroup other = save(TEAM_B, "Baska", true, true);

        // Sorgu clearAutomatically ile tanimli: asagidaki okumalar VERITABANINDAN gelir,
        // bayat kalicilik baglamindan degil.
        int demoted = repo.clearOtherDefaults(TEAM_A, keep.getId());

        assertThat(demoted).isEqualTo(1);
        assertThat(repo.findById(old.getId()).get().getIsDefault()).isFalse();
        assertThat(repo.findById(keep.getId()).get().getIsDefault()).isTrue();
        // Başka takımın varsayılanına DOKUNULMAZ.
        assertThat(repo.findById(other.getId()).get().getIsDefault()).isTrue();
    }
}
