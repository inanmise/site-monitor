package com.sitemonitor.service.session;

import com.sitemonitor.it.PostgresIntegration;
import com.sitemonitor.it.PostgresIt;
import com.sitemonitor.model.AppUser;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.service.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Oturum deposu açılış temizliği GERÇEK PostgreSQL'de (2026-10-09) — {@code mvn -Ppostgres-it test}. Spring Session'ın
 * KENDİ PostgreSQL şeması ({@code schema-postgresql.sql}: CHAR(36) session_id, BIGINT expiry_time) ile ilişkili
 * NOT EXISTS güncellemesi: canlı oturumun işareti kalır, süresi dolmuş / depoda olmayan silinir, TERMINATED korunur.
 * Kullanıcı adları rastgele: paylaşılan bağlamdaki başka testlerle çakışmaz.
 */
@PostgresIntegration
class SessionStorePostgresTest {

    @Test
    @DisplayName("PostgreSQL: yalnız depoda canlı karşılığı olmayan tek-oturum işaretleri temizlenir")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void clearsOnlyStaleMarkersOnPostgres() {
        JdbcTemplate jdbc = PostgresIt.app().jdbc();
        ResourceDatabasePopulator p = new ResourceDatabasePopulator(new ClassPathResource("org/springframework/session/jdbc/schema-postgresql.sql"));
        p.setContinueOnError(true);   // tablo bir önceki koşudan / yeniden açılıştan kalmış olabilir
        p.execute(jdbc.getDataSource());

        AppUserRepository users = PostgresIt.app().bean(AppUserRepository.class);
        String run = UUID.randomUUID().toString().substring(0, 8);
        long now = System.currentTimeMillis();
        String live = UUID.randomUUID().toString(), expired = UUID.randomUUID().toString(), missing = UUID.randomUUID().toString();
        save(users, "it-sess-live-" + run, live);
        save(users, "it-sess-exp-" + run, expired);
        save(users, "it-sess-miss-" + run, missing);
        save(users, "it-sess-term-" + run, "TERMINATED:" + run);
        session(jdbc, live, now + 3_600_000);
        session(jdbc, expired, now - 1_000);

        ObjectProvider repo = mock(ObjectProvider.class);   // ham tip — bkz. SessionStoreServiceJpaTest
        when(repo.getIfAvailable()).thenReturn(mock(JdbcIndexedSessionRepository.class));
        ObjectProvider jp = mock(ObjectProvider.class);
        when(jp.getIfAvailable()).thenReturn(jdbc);
        SessionStoreService svc = new SessionStoreService(repo, jp, mock(UserService.class), "SPRING_SESSION");
        svc.setClock(() -> now);

        assertThat(svc.clearStaleActiveSessionMarkers()).isGreaterThanOrEqualTo(2);
        assertThat(sid(jdbc, "it-sess-live-" + run)).isEqualTo(live);
        assertThat(sid(jdbc, "it-sess-exp-" + run)).isNull();
        assertThat(sid(jdbc, "it-sess-miss-" + run)).isNull();
        assertThat(sid(jdbc, "it-sess-term-" + run)).isEqualTo("TERMINATED:" + run);
        assertThat(svc.status()).containsEntry("store", "jdbc").containsKey("live").containsKey("expired");
    }

    private static void save(AppUserRepository users, String name, String sid) {
        AppUser u = new AppUser();
        u.setUsername(name); u.setSystemRole("USER"); u.setActive(true); u.setActiveSessionId(sid);
        users.saveAndFlush(u);
    }

    private static void session(JdbcTemplate jdbc, String sid, long expiry) {
        jdbc.update("INSERT INTO spring_session (primary_id, session_id, creation_time, last_access_time, max_inactive_interval, expiry_time) "
                + "VALUES (?, ?, ?, ?, ?, ?)", UUID.randomUUID().toString(), sid, expiry - 86_400_000, expiry - 86_400_000, 86_400, expiry);
    }

    private static String sid(JdbcTemplate jdbc, String name) {
        return jdbc.queryForObject("SELECT active_session_id FROM app_users WHERE username = ?", String.class, name);
    }
}
