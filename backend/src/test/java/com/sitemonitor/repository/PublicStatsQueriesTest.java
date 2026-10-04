package com.sitemonitor.repository;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.AuditLog;
import com.sitemonitor.model.Team;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Giriş sayfası kullanım istatistiklerinin dört COUNT sorgusu (2026-10-04) — gerçek JPQL H2 üstünde:
 * 24 sa'te açılan alarm, aktif takım, aktif kullanıcı, 24 sa'te başarılı giriş yapan FARKLI kullanıcı.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class PublicStatsQueriesTest {

    @Autowired AlertEventRepository alertRepo;
    @Autowired TeamRepository teamRepo;
    @Autowired AuditLogRepository auditRepo;
    @Autowired AppUserRepository userRepo;

    private static final String SINCE = "2026-10-03T08:00:00";

    private AlertEvent alert(String domain, String type, String createdAt, boolean resolved) {
        AlertEvent e = new AlertEvent();
        e.setDomain(domain);
        e.setAlertType(type);
        e.setAlertLevel("CRITICAL");
        e.setAcknowledged(false);
        e.setResolved(resolved);
        e.setCreatedAt(createdAt);
        return e;
    }

    private static long seq = 1;

    private AuditLog audit(String type, String actor, String outcome, String time) {
        AuditLog a = new AuditLog();
        a.setSeq(seq++);
        a.setEventType(type);
        a.setActor(actor);
        a.setOutcome(outcome);
        a.setEventTime(time);
        return a;
    }

    @Test
    @DisplayName("countCreatedSince: pencerede AÇILAN alarmlar — tüm türler, açık ya da çözülmüş; sınır dahil; öncesi sayılmaz")
    void alertsCreatedSince() {
        alertRepo.save(alert("https://a.example.com", "HTTP_DOWN", "2026-10-04T07:00:00", false));
        alertRepo.save(alert("a.example.com", "EXPIRY", "2026-10-03T09:30:00", true));     // sertifika, çözülmüş
        alertRepo.save(alert("b.example.com", "DOMAINMON_EXPIRY", SINCE, false));           // tam sınır
        alertRepo.save(alert("c.example.com", "PORT_DOWN", "2026-10-03T07:59:59", false));  // pencere dışı

        assertThat(alertRepo.countCreatedSince(SINCE)).isEqualTo(3L);
        assertThat(alertRepo.countCreatedSince("2026-10-05T00:00:00")).isZero();
    }

    @Test
    @DisplayName("AppUserRepository.countByActiveTrue: yalnız aktif kullanıcı hesapları (pasifler hariç)")
    void activeUsers() {
        for (String[] u : new String[][]{{"user1", "true"}, {"user2", "true"}, {"user3", "false"}, {"user4", "true"}}) {
            com.sitemonitor.model.AppUser a = new com.sitemonitor.model.AppUser();
            a.setUsername(u[0]);
            a.setSystemRole("USER");
            a.setActive(Boolean.parseBoolean(u[1]));
            userRepo.save(a);
        }
        assertThat(userRepo.countByActiveTrue()).isEqualTo(3L);
    }

    @Test
    @DisplayName("countByActiveTrue: yalnız aktif takımlar")
    void activeTeams() {
        Team a = new Team(); a.setName("Takim A"); a.setActive(true); teamRepo.save(a);
        Team b = new Team(); b.setName("Takim B"); b.setActive(true); teamRepo.save(b);
        Team c = new Team(); c.setName("Takim C"); c.setActive(false); teamRepo.save(c);

        assertThat(teamRepo.countByActiveTrue()).isEqualTo(2L);
    }

    @Test
    @DisplayName("countDistinctLoginActorsSince: başarılı LOGIN, FARKLI kullanıcı (harf duyarsız); başarısız/engelli/çıkış/pencere dışı/aktörsüz sayılmaz")
    void distinctLoginActors() {
        auditRepo.save(audit("LOGIN", "user1", "SUCCESS", "2026-10-04T07:00:00"));
        auditRepo.save(audit("LOGIN", "USER1", "SUCCESS", "2026-10-04T07:10:00"));        // aynı kişi, büyük harf
        auditRepo.save(audit("LOGIN", "user1", "SUCCESS", "2026-10-03T12:00:00"));        // aynı kişi, ikinci giriş
        auditRepo.save(audit("LOGIN", "user2", "SUCCESS", SINCE));                        // tam sınır
        auditRepo.save(audit("LOGIN_FAILED", "user3", "FAILURE", "2026-10-04T07:00:00"));
        auditRepo.save(audit("LOGIN_FAILED", "user4", "BLOCKED", "2026-10-04T07:00:00"));
        auditRepo.save(audit("LOGOUT", "user5", "SUCCESS", "2026-10-04T07:00:00"));
        auditRepo.save(audit("LOGIN", "user6", "SUCCESS", "2026-10-03T07:59:59"));       // pencere dışı
        auditRepo.save(audit("LOGIN", null, "SUCCESS", "2026-10-04T07:00:00"));          // aktörsüz

        assertThat(auditRepo.countDistinctLoginActorsSince(SINCE)).isEqualTo(2L);
    }
}
