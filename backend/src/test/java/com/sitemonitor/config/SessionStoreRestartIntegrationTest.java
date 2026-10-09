package com.sitemonitor.config;

import com.sitemonitor.SiteMonitorApplication;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.session.SessionRepository;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Oturum deposu UÇTAN UCA (2026-10-09): gerçek uygulama bağlamı + gerçek HTTP, "yeniden başlatma" = bağlamı kapatıp
 * AYNI veritabanıyla yenisini açmak.
 *
 * <ul>
 *   <li>{@code jdbc}: giriş → {@code SESSION} çerezi, oturum {@code spring_session}'da → uygulama yeniden başlar → aynı
 *       çerezle {@code /api/me} 200 (oturum yaşadı) ve tek-oturum işareti açılış temizliğinde KORUNDU.</li>
 *   <li>{@code memory} (varsayılan — bugünkü davranış): {@code JSESSIONID} çerezi, oturum deposu bean'i yok, tabloya
 *       yazılmaz; yeniden başlatmadan sonra aynı çerez 401.</li>
 * </ul>
 * Kök neden: Boot 4'te oturum otomatik yapılandırması ayrı modüle taşındı ve proje bunu içermiyordu — prod "jdbc" derken
 * oturumlar 2026-06-17'den beri bellekteydi.
 */
class SessionStoreRestartIntegrationTest {

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private static ConfigurableApplicationContext start(String db, String store) {
        List<String> args = new java.util.ArrayList<>(List.of(
                "--server.port=0",
                "--spring.datasource.url=jdbc:h2:mem:" + db + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
                // create-drop değil: "yeniden başlatma" aynı tabloları görmeli (kullanıcı + tek-oturum işareti)
                "--spring.jpa.hibernate.ddl-auto=update",
                "--spring.session.jdbc.initialize-schema=always"));
        if (store != null) args.add("--site.monitor.session.store=" + store);
        return new SpringApplicationBuilder(SiteMonitorApplication.class).run(args.toArray(String[]::new));
    }

    private static String base(ConfigurableApplicationContext ctx) {
        return "http://localhost:" + ctx.getEnvironment().getProperty("local.server.port");
    }

    private record Login(int status, String cookieName, String cookieValue) {}

    private static Login login(ConfigurableApplicationContext ctx) throws Exception {
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(base(ctx) + "/api/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"testuser\",\"password\":\"testpass\",\"forceLogin\":true}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        Optional<String> sc = r.headers().allValues("Set-Cookie").stream()
                .filter(c -> c.startsWith("SESSION=") || c.startsWith("JSESSIONID=")).reduce((a, b) -> b);
        String cookie = sc.map(c -> c.substring(0, c.indexOf(';'))).orElse("");
        int eq = cookie.indexOf('=');
        return new Login(r.statusCode(), eq > 0 ? cookie.substring(0, eq) : "", eq > 0 ? cookie.substring(eq + 1) : "");
    }

    private static int me(ConfigurableApplicationContext ctx, Login l) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(base(ctx) + "/api/me"))
                .header("Cookie", l.cookieName() + "=" + l.cookieValue()).GET().build(),
                HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    @Test
    @DisplayName("jdbc: oturum spring_session'da; uygulama yeniden başlayınca AYNI çerezle oturum sürer, tek-oturum işareti korunur")
    void jdbcSessionSurvivesRestart() throws Exception {
        String db = "sessjdbc" + UUID.randomUUID().toString().replace("-", "");
        Login l;
        String sessionId;
        try (ConfigurableApplicationContext ctx1 = start(db, "jdbc")) {
            assertThat(ctx1.getBeanProvider(SessionRepository.class).getIfAvailable()).isNotNull();
            l = login(ctx1);
            assertThat(l.status()).isEqualTo(200);
            assertThat(l.cookieName()).isEqualTo("SESSION");
            assertThat(me(ctx1, l)).isEqualTo(200);
            JdbcTemplate j = ctx1.getBean(JdbcTemplate.class);
            assertThat(j.queryForObject("SELECT COUNT(*) FROM spring_session", Integer.class)).isGreaterThanOrEqualTo(1);
            sessionId = j.queryForObject("SELECT active_session_id FROM app_users WHERE UPPER(username) = 'TESTUSER'", String.class);
            assertThat(sessionId).isNotBlank();
            assertThat(j.queryForObject("SELECT COUNT(*) FROM spring_session WHERE session_id = ?", Integer.class, sessionId)).isEqualTo(1);
        }
        try (ConfigurableApplicationContext ctx2 = start(db, "jdbc")) {
            JdbcTemplate j = ctx2.getBean(JdbcTemplate.class);
            // Açılış temizliği canlı oturumun işaretini SİLMEDİ (bellek kipinde hepsi silinirdi)
            assertThat(j.queryForObject("SELECT active_session_id FROM app_users WHERE UPPER(username) = 'TESTUSER'", String.class))
                    .isEqualTo(sessionId);
            assertThat(me(ctx2, l)).as("yeniden başlatmadan sonra aynı çerez").isEqualTo(200);
        }
    }

    @Test
    @DisplayName("memory (varsayılan): JSESSIONID, oturum deposu bean'i yok, tabloya yazılmaz; yeniden başlatma oturumu düşürür")
    void memorySessionIsTodaysBehaviour() throws Exception {
        String db = "sessmem" + UUID.randomUUID().toString().replace("-", "");
        Login l;
        try (ConfigurableApplicationContext ctx1 = start(db, null)) {
            assertThat(ctx1.getBeanProvider(SessionRepository.class).getIfAvailable()).isNull();
            l = login(ctx1);
            assertThat(l.status()).isEqualTo(200);
            assertThat(l.cookieName()).isEqualTo("JSESSIONID");
            assertThat(me(ctx1, l)).isEqualTo(200);
        }
        try (ConfigurableApplicationContext ctx2 = start(db, "memory")) {
            assertThat(me(ctx2, l)).isEqualTo(401);
        }
    }
}
