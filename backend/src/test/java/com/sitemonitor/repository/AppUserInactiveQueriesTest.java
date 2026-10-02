package com.sitemonitor.repository;

import com.sitemonitor.model.AppUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pasif hesap kapısı + bildirim süzgeci sorguları (2026-10-02) — gerçek JPQL H2 üstünde: tek-kolon aktiflik okuması
 * (harf duyarsız), pasif kimlik/e-posta projeksiyonu, aktif kullanıcıya da ait adreslerin ayıklanması.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class AppUserInactiveQueriesTest {

    @Autowired AppUserRepository repo;

    private Long gone;

    private AppUser user(String username, boolean active, String email) {
        AppUser u = new AppUser();
        u.setUsername(username);
        u.setSystemRole("USER");
        u.setActive(active);
        u.setEmail(email);
        return repo.save(u);
    }

    @BeforeEach
    void seed() {
        user("AKTIF", true, "aktif@x.com");
        gone = user("PASIF", false, " Pasif@X.com ").getId();
        user("PASIF2", false, "ortak@x.com");
        user("AKTIF2", true, "ORTAK@x.com");
        user("PASIF3", false, null);
    }

    @Test
    @DisplayName("findActiveFlagByUsername: harf duyarsız tek kolon; kullanıcı yoksa boş")
    void activeFlag() {
        assertThat(repo.findActiveFlagByUsername("pasif")).contains(false);
        assertThat(repo.findActiveFlagByUsername("Aktif")).contains(true);
        assertThat(repo.findActiveFlagByUsername("YOK")).isEmpty();
    }

    @Test
    @DisplayName("findInactiveIdsAndEmails: yalnız pasifler (e-postası boş olan dâhil)")
    void inactiveIdsAndEmails() {
        List<Object[]> rows = repo.findInactiveIdsAndEmails();
        assertThat(rows).hasSize(3);
        assertThat(rows).anySatisfy(r -> {
            assertThat(r[0]).isEqualTo(gone);
            assertThat(r[1]).isEqualTo(" Pasif@X.com ");
        });
    }

    @Test
    @DisplayName("findActiveEmailsLowerIn: aktif bir kullanıcıya da ait adres (küçük harf, kırpılmış) döner — süzgeç onu düşürmez")
    void activeEmailsLowerIn() {
        assertThat(repo.findActiveEmailsLowerIn(List.of("pasif@x.com", "ortak@x.com")))
                .containsExactly("ortak@x.com");
    }
}
