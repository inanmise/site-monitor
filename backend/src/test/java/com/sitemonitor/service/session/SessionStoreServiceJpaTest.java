package com.sitemonitor.service.session;

import com.sitemonitor.model.AppUser;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.test.context.TestPropertySource;

import javax.sql.DataSource;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Oturum deposu servisi (2026-10-09) — gerçek SQL, H2 + Spring Session'ın KENDİ şeması: JDBC kipinde açılış temizliği
 * yalnız depoda canlı karşılığı olmayan tek-oturum işaretlerini siler; bellek kipi bugünkü davranıştır.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class SessionStoreServiceJpaTest {

    private static final long NOW = 1_800_000_000_000L;

    @Autowired AppUserRepository repo;
    @Autowired DataSource dataSource;
    JdbcTemplate jdbc;
    UserService userService;

    @BeforeEach
    void schema() {
        ResourceDatabasePopulator p = new ResourceDatabasePopulator(new ClassPathResource("org/springframework/session/jdbc/schema-h2.sql"));
        p.setContinueOnError(true);   // ikinci testte tablolar zaten var
        p.execute(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("DELETE FROM spring_session_attributes");
        jdbc.update("DELETE FROM spring_session");
        userService = mock(UserService.class);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private SessionStoreService service(boolean jdbcMode, String table) {
        // Ham tip: joker tipli SessionRepository<?> dönüşüne somut sınıf thenReturn ile verilemez (derleyici yakalama tipi)
        ObjectProvider repoProvider = mock(ObjectProvider.class);
        when(repoProvider.getIfAvailable()).thenReturn(jdbcMode ? mock(JdbcIndexedSessionRepository.class) : null);
        ObjectProvider jdbcProvider = mock(ObjectProvider.class);
        when(jdbcProvider.getIfAvailable()).thenReturn(jdbc);
        SessionStoreService s = new SessionStoreService(repoProvider, jdbcProvider, userService, table);
        s.setClock(() -> NOW);
        return s;
    }

    private void user(String name, String sid) {
        AppUser u = new AppUser();
        u.setUsername(name); u.setSystemRole("USER"); u.setActive(true); u.setActiveSessionId(sid);
        repo.saveAndFlush(u);
    }

    private void session(String sid, long expiry) {
        jdbc.update("INSERT INTO spring_session (primary_id, session_id, creation_time, last_access_time, max_inactive_interval, expiry_time, principal_name) "
                + "VALUES (?, ?, ?, ?, ?, ?, NULL)", "p-" + sid, sid, NOW - 1000, NOW - 1000, 86400, expiry);
    }

    private String sidOf(String name) {
        return jdbc.queryForObject("SELECT active_session_id FROM app_users WHERE username = ?", String.class, name);
    }

    @Test
    @DisplayName("jdbc: canlı oturumun işareti KALIR; süresi dolmuş / depoda olmayan silinir; TERMINATED ve boş dokunulmaz")
    void jdbcClearsOnlyMarkersWithoutLiveSession() {
        user("canli", "sid-live");     session("sid-live", NOW + 60_000);
        user("dolmus", "sid-expired"); session("sid-expired", NOW - 1);
        user("yok", "sid-missing");
        user("atilan", "TERMINATED:x");
        user("bos", null);

        int n = service(true, "SPRING_SESSION").clearStaleActiveSessionMarkers();

        assertThat(n).isEqualTo(2);
        assertThat(sidOf("canli")).isEqualTo("sid-live");
        assertThat(sidOf("dolmus")).isNull();
        assertThat(sidOf("yok")).isNull();
        assertThat(sidOf("atilan")).isEqualTo("TERMINATED:x");
        assertThat(sidOf("bos")).isNull();
        verify(userService).evictActiveSessionCaches();
        verify(userService, never()).clearAllActiveSessions();
    }

    @Test
    @DisplayName("memory: bugünkü davranış — UserService.clearAllActiveSessions, SQL yok")
    void memoryDelegatesToTodaysBehaviour() {
        user("canli", "sid-live"); session("sid-live", NOW + 60_000);
        when(userService.clearAllActiveSessions()).thenReturn(7);
        SessionStoreService s = service(false, "SPRING_SESSION");
        assertThat(s.mode()).isEqualTo(SessionStoreService.MEMORY);
        assertThat(s.clearStaleActiveSessionMarkers()).isEqualTo(7);
        assertThat(sidOf("canli")).isEqualTo("sid-live");   // servis kendisi SQL çalıştırmadı (mock)
        assertThat(s.status()).isEqualTo(Map.of("store", "memory"));
    }

    @Test
    @DisplayName("jdbc: depo sorgusu düşerse (tablo yok / geçersiz ad) bellek kipinin kuralına düşer — açılış durmaz")
    void jdbcFailureFallsBack() {
        when(userService.clearAllActiveSessions()).thenReturn(3);
        assertThat(service(true, "olmayan_tablo").clearStaleActiveSessionMarkers()).isEqualTo(3);
        assertThat(service(true, "spring_session; DROP TABLE app_users").clearStaleActiveSessionMarkers()).isEqualTo(3);
        verify(userService, times(2)).clearAllActiveSessions();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_users", Integer.class)).isNotNull();   // tablo yerinde
    }

    @Test
    @DisplayName("Sistem Sağlığı özeti: jdbc → depo, tablo, canlı ve süresi dolmuş oturum sayısı")
    void statusCountsLiveAndExpired() {
        session("a", NOW + 1000); session("b", NOW + 2000); session("c", NOW - 5);
        Map<String, Object> st = service(true, "SPRING_SESSION").status();
        assertThat(st).containsEntry("store", "jdbc").containsEntry("table", "SPRING_SESSION")
                .containsEntry("live", 2L).containsEntry("expired", 1L);
    }
}
