package com.sitemonitor.service.userref;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpSession;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Opak kullanıcı kimliği servisi (2026-10-08): bir kez atanır, değişmez; opak kimlik herkesten, sayısal kimlik YALNIZ
 * global admin'den kabul edilir (AUDIT ve kapsamlı müdür dahil diğerleri — kullanıcı kararı).
 */
class UserPublicIdsTest {

    private static final String FIXED = "11111111-2222-4333-8444-555555555555";

    private JdbcTemplate jdbc;
    private UserPublicIds svc;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:upid_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL", "sa", "");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE app_users (id BIGINT PRIMARY KEY, username VARCHAR(100), public_id VARCHAR(36))");
        jdbc.update("INSERT INTO app_users (id, username, public_id) VALUES (1, 'a', NULL), (2, 'b', NULL), (3, 'c', ?)", FIXED);
        svc = new UserPublicIds(jdbc);
    }

    private static MockHttpSession session(String role, List<Long> viewTeamIds) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("systemRole", role);
        if (viewTeamIds != null) s.setAttribute("viewTeamIds", viewTeamIds);
        return s;
    }

    @Test
    @DisplayName("backfill: kimliği olmayan her satıra benzersiz UUID; var olan kimlik değişmez; ikinci koşu hiçbir şey yapmaz")
    void backfill() {
        assertThat(svc.backfill()).isEqualTo(2);
        List<String> ids = jdbc.queryForList("SELECT public_id FROM app_users ORDER BY id", String.class);
        assertThat(ids).doesNotContainNull();
        assertThat(new HashSet<>(ids)).hasSize(3);
        assertThat(ids).allMatch(p -> UserPublicIds.FORMAT.matcher(p).matches());
        assertThat(ids.get(2)).isEqualTo(FIXED);
        assertThat(svc.backfill()).isZero();
    }

    @Test
    @DisplayName("publicIdOf ↔ idOf: çift yönlü; büyük harf kabul; biçim dışı / bilinmeyen / silinmiş → null")
    void lookups() {
        assertThat(svc.publicIdOf(3L)).isEqualTo(FIXED);
        assertThat(svc.idOf(FIXED)).isEqualTo(3L);
        assertThat(svc.idOf(FIXED.toUpperCase())).isEqualTo(3L);
        assertThat(svc.idOf("  " + FIXED + " ")).isEqualTo(3L);
        assertThat(svc.idOf("3")).isNull();
        assertThat(svc.idOf("not-a-uuid")).isNull();
        assertThat(svc.idOf(UUID.randomUUID().toString())).isNull();
        assertThat(svc.publicIdOf(999L)).isNull();
        assertThat(svc.publicIdOf(null)).isNull();
    }

    @Test
    @DisplayName("kimliği olmayan satır ilk okumada kimlik alır, kalıcıdır ve sonraki okumada aynı kalır")
    void lazyAssign() {
        jdbc.update("INSERT INTO app_users (id, username, public_id) VALUES (4, 'd', NULL)");
        String first = svc.publicIdOf(4L);
        assertThat(first).matches(UserPublicIds.FORMAT);
        assertThat(jdbc.queryForObject("SELECT public_id FROM app_users WHERE id = 4", String.class)).isEqualTo(first);
        assertThat(new UserPublicIds(jdbc).publicIdOf(4L)).isEqualTo(first);
    }

    @Test
    @DisplayName("resolve: opak kimlik herkesten; sayı (Number ya da rakam metni) yalnız izinliyken; boş/çöp → null")
    void resolve() {
        assertThat(svc.resolve(FIXED, false)).isEqualTo(3L);
        assertThat(svc.resolve(3L, false)).isNull();
        assertThat(svc.resolve("3", false)).isNull();
        assertThat(svc.resolve(3L, true)).isEqualTo(3L);
        assertThat(svc.resolve("3", true)).isEqualTo(3L);
        assertThat(svc.resolve(3, true)).isEqualTo(3L);
        assertThat(svc.resolve("", true)).isNull();
        assertThat(svc.resolve(null, true)).isNull();
        assertThat(svc.resolve("abc", true)).isNull();
    }

    @Test
    @DisplayName("oturum kuralı: global admin sayı gönderebilir; AUDIT, kapsamlı müdür, kullanıcı ve oturumsuz istek gönderemez")
    void sessionRule() {
        assertThat(UserPublicIds.resolve(svc, "3", session("ADMIN", null))).isEqualTo(3L);
        assertThat(UserPublicIds.resolve(svc, "3", session("AUDIT", null))).isNull();
        assertThat(UserPublicIds.resolve(svc, "3", session("ADMIN", List.of(1L)))).isNull();
        assertThat(UserPublicIds.resolve(svc, "3", session("USER", List.of(1L)))).isNull();
        assertThat(UserPublicIds.resolve(svc, "3", null)).isNull();
        assertThat(UserPublicIds.resolve(svc, FIXED, session("USER", List.of(1L)))).isEqualTo(3L);
        // Bean'siz (elle kurulan denetleyici, birim testi) yol: eski sayısal ayrıştırma
        assertThat(UserPublicIds.resolve(null, "3", session("USER", List.of(1L)))).isEqualTo(3L);
        assertThat(UserPublicIds.resolve(null, FIXED, null)).isNull();
    }
}
