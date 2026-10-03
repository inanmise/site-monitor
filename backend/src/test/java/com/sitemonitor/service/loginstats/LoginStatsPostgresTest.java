package com.sitemonitor.service.loginstats;

import com.sitemonitor.it.PostgresIntegration;
import com.sitemonitor.it.PostgresIt;
import com.sitemonitor.it.SwallowedSqlErrors;
import com.sitemonitor.model.AppUser;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.repository.AuditLogRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Giriş istatistikleri (2026-10-03, Giriş Yöntemleri → İstatistikler — yoklanan ekran) sorguları GERÇEK PostgreSQL'de:
 * izdüşüm ({@code IN} + zaman aralığı + {@code ORDER BY … LIMIT}), gruplu sayım ({@code COUNT(DISTINCT LOWER(actor))}),
 * kullanıcı ayrıntısı ({@code LOWER(actor) = ?}) ve dizin izdüşümü. Repository DOĞRUDAN (hata yutulmaz) + servis girişi
 * ({@link SwallowedSqlErrors} yutulan SQL hatasını yakalar).
 */
@PostgresIntegration
class LoginStatsPostgresTest {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    private static String user;

    private static <T> T bean(Class<T> type) { return PostgresIt.app().bean(type); }

    @BeforeAll
    static void seed() {
        String tag = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        user = "IT-LS-" + tag;
        AppUser u = new AppUser();
        u.setUsername(user);
        u.setAuthSource("LDAP");
        u.setActive(true);
        bean(AppUserRepository.class).save(u);
        // Satırlar AuditService'in zincirli yazımından (seq / hash zinciri bozulmaz); IP yok → coğrafi zenginleştirme ağa çıkmaz.
        // recordOtp tür-bağımsız eşzamanlı yazıcıdır — burada yalnız tohum için LOGIN / LOGIN_FAILED da yazdırılır.
        com.sitemonitor.service.AuditService audit = bean(com.sitemonitor.service.AuditService.class);
        audit.recordOtp("LOGIN", user, null, null, null, "SUCCESS", null, "{\"method\":\"LDAP\"}", null, null);
        audit.recordOtp("LOGIN", user.toLowerCase(), null, null, null, "SUCCESS", null, null, null, null);   // eski: yöntemsiz
        audit.recordOtp("LOGIN_FAILED", user, null, null, null, "FAILURE", "BAD_PASSWORD: attempt #1/5 for x",
                "{\"method\":\"LDAP\"}", null, null);
        audit.recordOtp("LOGIN_OTP_REQUESTED", user, null, null, null, "SUCCESS", null,
                "{\"channel\":\"PUSH\",\"result\":\"SENT\"}", null, null);
    }

    @Test
    @DisplayName("repository sorguları PostgreSQL'de: izdüşüm (sayfa tavanı), gruplu sayım, kullanıcı satırları, dizin")
    void repositoryQueries() {
        AuditLogRepository audit = bean(AuditLogRepository.class);
        String since = ISO.format(Instant.now().minus(1, ChronoUnit.DAYS));
        List<Object[]> rows = audit.findLoginStatRows(LoginEventClassifier.EVENT_TYPES, since, PageRequest.of(0, 1000));
        assertThat(rows).anySatisfy(r -> assertThat(r[2]).isEqualTo(user));
        assertThat(audit.findLoginStatRows(LoginEventClassifier.EVENT_TYPES, since, PageRequest.of(0, 2))).hasSize(2);
        List<Object[]> counts = audit.countLoginStatTypes(LoginEventClassifier.EVENT_TYPES, since,
                ISO.format(Instant.now().plusSeconds(60)));
        assertThat(counts).anySatisfy(r -> assertThat(r[0]).isEqualTo("LOGIN"));
        assertThat(audit.findLoginStatEventsForActor(LoginEventClassifier.EVENT_TYPES, since, user.toLowerCase(),
                PageRequest.of(0, 100))).hasSize(4);
        assertThat(bean(AppUserRepository.class).findLoginStatDirectory()).anySatisfy(r -> assertThat(r[0]).isEqualTo(user));
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("servis girişi: özet / kullanıcı tablosu / ayrıntı — yutulan SQL hatası YOK; eski satır harf farkıyla da aynı kişiye")
    void serviceEntryPoints() {
        LoginStatsService svc = bean(LoginStatsService.class);
        try (SwallowedSqlErrors sql = SwallowedSqlErrors.capture()) {
            Map<String, Object> s = svc.summary(1, true);
            assertThat((Map<String, Object>) s.get("totals")).containsKey("attempts");
            Map<String, Object> users = svc.users(1, user, null, "logins", 1, 25, true);
            List<Map<String, Object>> items = (List<Map<String, Object>>) users.get("items");
            assertThat(items).hasSize(1);
            assertThat((Map<String, Object>) items.get(0).get("success")).containsEntry("LDAP", 2L);
            assertThat(items.get(0)).containsEntry("failed", 1L).containsEntry("estimated", 1L);
            Map<String, Object> d = svc.user(user, 1, true, "IT-ADMIN");
            assertThat((List<?>) d.get("recent")).hasSize(4);
            assertThat(sql.errors()).as("yutulan SQL hatası").isEmpty();
        }
    }
}
