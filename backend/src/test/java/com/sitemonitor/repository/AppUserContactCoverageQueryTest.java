package com.sitemonitor.repository;

import com.sitemonitor.model.AppUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kodla giriş kişi bilgisi KAPSAMI (2026-10-03, Giriş Yöntemleri ayar sayfası) — gerçek JPQL H2 üstünde: TEK satır
 * {@code [aktif, kayıtlı telefonu olan, kayıtlı e-postası olan]}; pasifler sayılmaz; boş / kısa (dahili) telefon ve '@'
 * içermeyen e-posta "kayıtlı" sayılmaz. Örnek değerler yer tutucudur.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class AppUserContactCoverageQueryTest {

    @Autowired AppUserRepository repo;

    private void user(String username, boolean active, String phone, String email) {
        AppUser u = new AppUser();
        u.setUsername(username);
        u.setSystemRole("USER");
        u.setActive(active);
        u.setPhone(phone);
        u.setEmail(email);
        repo.save(u);
    }

    private long[] coverage() {
        List<Object[]> rows = repo.contactCoverage();
        assertThat(rows).hasSize(1);
        Object[] r = rows.get(0);
        return new long[] { ((Number) r[0]).longValue(), ((Number) r[1]).longValue(), ((Number) r[2]).longValue() };
    }

    @Test
    @DisplayName("boş tablo: 0 / 0 / 0 (SUM null değil — COALESCE)")
    void empty() {
        assertThat(coverage()).containsExactly(0L, 0L, 0L);
    }

    @Test
    @DisplayName("aktif 6 kullanıcı: 3 kayıtlı telefon (kısa dahili / boş sayılmaz), 3 kayıtlı e-posta ('@' yoksa sayılmaz); pasifler hariç")
    void counts() {
        user("U1", true, "0500 000 00 00", "u1@example.com");
        user("U2", true, "+90 (500) 000-00-01", "u2@example.com");
        user("U3", true, "5000000002", null);
        user("U4", true, "1234", "  ");
        user("U5", true, "   ", "not-an-address");
        user("U6", true, null, "u6@example.com");
        user("P1", false, "0500 000 00 03", "p1@example.com");
        assertThat(coverage()).containsExactly(6L, 3L, 3L);
    }
}
