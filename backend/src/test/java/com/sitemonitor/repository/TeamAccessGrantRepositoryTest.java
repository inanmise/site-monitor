package com.sitemonitor.repository;

import com.sitemonitor.model.TeamAccessGrant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Takım erişim paylaşımı deposu (2026-10-12, inceleme 4) — H2'de GERÇEK SQL: canlı paylaşım sayımı (süresi dolanlar
 * tavanı doldurmaz), sahip+alıcı benzersiz kısıtı, takım / kişi silinince temizlik ve sıralı okumalar.
 *
 * <p>{@code NOT_SUPPORTED}: {@code @DataJpaTest} her testi bir işleme sarar ve {@code @Modifying} silmelerinin kendi
 * {@code @Transactional}'ı eksik olsa bile testi yeşil gösterirdi (bkz. CLAUDE.md "derived deleteBy…" tuzağı). Burada
 * silmeler uygulamadaki gibi işlemsiz çağıranla koşar.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TeamAccessGrantRepositoryTest {

    @Autowired TeamAccessGrantRepository repo;

    private static TeamAccessGrant grant(long owner, String type, Long team, Long user, String level, String expiresAt) {
        TeamAccessGrant g = new TeamAccessGrant();
        g.setOwnerTeamId(owner);
        g.setGranteeType(type);
        g.setGranteeTeamId(team);
        g.setGranteeUserId(user);
        g.setGranteeKey(TeamAccessGrant.keyOf(type, team, user));
        g.setLevel(level);
        g.setExpiresAt(expiresAt);
        g.setCreatedAt("2026-10-12T09:00:00");
        return g;
    }

    @Test
    @DisplayName("canlı sayım: süresi dolmuş paylaşım sayılmaz; sınırda (expires_at == now) dolmuş sayılır")
    void countLive() {
        repo.deleteAll();
        repo.saveAndFlush(grant(1, "TEAM", 2L, null, "VIEW", null));
        repo.saveAndFlush(grant(1, "TEAM", 3L, null, "EDIT", "2026-10-13T21:00:00"));
        repo.saveAndFlush(grant(1, "USER", null, 50L, "DELETE", "2026-10-11T21:00:00"));   // dolmuş
        repo.saveAndFlush(grant(1, "USER", null, 51L, "VIEW", "2026-10-12T09:00:00"));    // tam şimdi biter
        repo.saveAndFlush(grant(9, "TEAM", 2L, null, "VIEW", null));                       // başka sahip
        assertThat(repo.countLiveByOwnerTeamId(1L, "2026-10-12T09:00:00")).isEqualTo(2);
        assertThat(repo.countByOwnerTeamId(1L)).isEqualTo(4);
        assertThat(repo.countLiveByOwnerTeamId(1L, "2026-10-14T00:00:00")).isEqualTo(1);
    }

    @Test
    @DisplayName("aynı sahip + aynı alıcı ikinci kez yazılamaz (benzersiz kısıt); başka sahip aynı alıcıya verebilir")
    void uniqueOwnerGrantee() {
        repo.deleteAll();
        repo.saveAndFlush(grant(1, "TEAM", 2L, null, "VIEW", null));
        assertThatThrownBy(() -> repo.saveAndFlush(grant(1, "TEAM", 2L, null, "EDIT", null)))
                .isInstanceOf(DataIntegrityViolationException.class);
        repo.saveAndFlush(grant(3, "TEAM", 2L, null, "EDIT", null));
        assertThat(repo.findByOwnerTeamIdAndGranteeKey(1L, TeamAccessGrant.keyOf("TEAM", 2L, null))).isPresent();
        assertThat(repo.findByOwnerTeamIdAndGranteeKey(1L, TeamAccessGrant.keyOf("TEAM", 4L, null))).isEmpty();
    }

    @Test
    @DisplayName("takım silinince sahibi YA DA alıcısı o takım olanlar, kişi silinince ona verilenler gider (işlemsiz çağıran)")
    void deletesForTeamAndUser() {
        repo.deleteAll();
        repo.saveAndFlush(grant(1, "TEAM", 2L, null, "VIEW", null));   // sahip 1
        repo.saveAndFlush(grant(3, "TEAM", 1L, null, "VIEW", null));   // alıcı 1
        repo.saveAndFlush(grant(3, "USER", null, 50L, "EDIT", null));
        repo.saveAndFlush(grant(4, "USER", null, 50L, "VIEW", null));
        repo.saveAndFlush(grant(4, "TEAM", 2L, null, "VIEW", null));
        assertThat(repo.deleteByTeam(1L)).isEqualTo(2);
        assertThat(repo.deleteByGranteeUser(50L)).isEqualTo(2);
        List<TeamAccessGrant> left = repo.findAllByOrderByIdAsc();
        assertThat(left).hasSize(1);
        assertThat(left.get(0).getOwnerTeamId()).isEqualTo(4L);
        assertThat(repo.findByOwnerTeamIdInOrderByIdAsc(List.of(4L, 1L))).hasSize(1);
        assertThat(repo.deleteByTeam(99L)).isZero();
    }
}
