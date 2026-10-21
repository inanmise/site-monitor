package com.sitemonitor.service;

import com.sitemonitor.model.LoginAnomalyIncident;
import com.sitemonitor.repository.LoginAnomalyIncidentRepository;
import com.sitemonitor.repository.NotificationLogRepository;
import com.sitemonitor.service.FailedLoginAnomalyService.AnomalyReport;
import com.sitemonitor.service.FailedLoginAnomalyService.RuleHit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Başarısız-login anomali taraması iki pod'da (2026-10-21, çok pod R1 / MP-10) — H2'de GERÇEK depo + ortak
 * {@code login_anomaly_state}. Zamanlayıcı her pod'da fixedDelay ile koşar; kilit tarama bitince bırakılır. Kapılar:
 * (1) ortak "yakın zamanda tarandı" damgası ikinci pod'un aynı aralıktaki turunu atlatır; (2) gönderim DENEMESİ
 * damgası uçuştaki İLK postanın arkasından REALERT'i engeller; (3) dağıtıcının başarı damgası hedefli UPDATE — çözülmüş
 * anomaliyi geri açmaz; taramanın varlık kaydı da başarı damgasını geri saramaz.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class FailedLoginAnomalyMultiPodTest {

    static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    @Autowired LoginAnomalyIncidentRepository repo;
    @Autowired DataSource dataSource;

    JdbcTemplate jdbc;
    FailedLoginAnomalyService detector;
    AppSettingsService appSettings;
    SecurityMailDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE IF NOT EXISTS login_anomaly_state(id INTEGER PRIMARY KEY, last_scan_at VARCHAR(40))");
        jdbc.update("DELETE FROM login_anomaly_state");
        repo.deleteAll();
        detector = mock(FailedLoginAnomalyService.class);
        when(detector.isEnabled()).thenReturn(true);
        when(detector.evaluate(anyString(), anyString(), anyInt())).thenAnswer(i -> anomalous(i.getArgument(0), i.getArgument(1)));
        appSettings = mock(AppSettingsService.class);
        when(appSettings.getInt(anyString(), anyInt())).thenAnswer(i -> i.getArgument(1));
        when(appSettings.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> i.getArgument(1));
        when(appSettings.getCsv(anyString(), anyString())).thenReturn(List.of("ops@x"));
        dispatcher = mock(SecurityMailDispatcher.class);   // eşzamansız gönderim "yolda" — damga yazmaz
    }

    static AnomalyReport anomalous(String start, String end) {
        return new AnomalyReport(start, end, 10, 20, 0, List.of(new RuleHit("GLOBAL_VOLUME", 20, 20, "")),
                List.of(), List.of(), List.of(), List.of(), Map.of());
    }

    static AnomalyReport quiet() {
        return new AnomalyReport("2026-10-21T06:00:00", "2026-10-21T06:10:00", 10, 0, 0, List.of(),
                List.of(), List.of(), List.of(), List.of(), Map.of());
    }

    FailedLoginAnomalyIncidentService pod(SecurityMailDispatcher d) {
        FailedLoginAnomalyIncidentService s = new FailedLoginAnomalyIncidentService(detector, repo, d, appSettings, jdbc);
        ReflectionTestUtils.setField(s, "windowMinutesDefault", 10);
        ReflectionTestUtils.setField(s, "catchupCapDefault", 60);
        ReflectionTestUtils.setField(s, "cooldownMinutesDefault", 60);
        ReflectionTestUtils.setField(s, "scanMs", 600_000L);
        return s;
    }

    LoginAnomalyIncident only() {
        List<LoginAnomalyIncident> all = repo.findAll();
        assertThat(all).hasSize(1);
        return all.get(0);
    }

    @Test
    @DisplayName("iki pod aynı aralıkta tarar → ikinci tur atlanır: TEK INITIAL, uçuştaki postanın arkasından REALERT yok")
    void secondPodWithinInterval_skips() {
        FailedLoginAnomalyIncidentService a = pod(dispatcher), b = pod(dispatcher);
        a.scan();
        b.scan();
        verify(dispatcher, times(1)).dispatchAlert(any(), any(), eq("INITIAL"), anyLong());
        verify(dispatcher, never()).dispatchAlert(any(), any(), eq("REALERT"), anyLong());
        assertThat(only().getLastAlertAttemptAt()).as("deneme dağıtımdan ÖNCE damgalandı").isNotNull();
    }

    @Test
    @DisplayName("kapı aşılsa da (aralık geçti): ilk posta YOLDAYKEN (başarı damgası yok, deneme < 10 dk) REALERT yok; deneme eskiyince düşen posta yeniden denenir")
    void inFlightInitial_noImmediateRealert_thenRetry() {
        FailedLoginAnomalyIncidentService a = pod(dispatcher), b = pod(dispatcher);
        a.scan();
        jdbc.update("UPDATE login_anomaly_state SET last_scan_at = ? WHERE id = 1",
                ISO.format(Instant.now().minusSeconds(11 * 60)));
        b.scan();
        verify(dispatcher, times(1)).dispatchAlert(any(), any(), any(), anyLong());

        // Deneme 11 dk önce, başarı damgası hiç yazılmadı (posta düştü) → sonraki tarama yeniden dener (eski kadans)
        jdbc.update("UPDATE login_anomaly_incident SET last_alert_attempt_at = ?",
                ISO.format(Instant.now().minusSeconds(11 * 60)));
        jdbc.update("UPDATE login_anomaly_state SET last_scan_at = ? WHERE id = 1",
                ISO.format(Instant.now().minusSeconds(11 * 60)));
        b.scan();
        verify(dispatcher, times(1)).dispatchAlert(any(), any(), eq("REALERT"), anyLong());
    }

    @Test
    @DisplayName("çözen tarama geri ALINMAZ: dağıtıcının geç gelen başarı damgası yalnız last_alert_at yazar; taramanın varlık kaydı da damgayı geri saramaz")
    void lateStampDoesNotReopen_andEntitySaveKeepsStamp() {
        EmailNotificationService email = mock(EmailNotificationService.class);
        when(email.sendSystemAdminLoginAnomalyAlert(any(), any(), any())).thenReturn("SENT");
        SecurityMailDispatcher real = new SecurityMailDispatcher(email, repo, mock(NotificationLogRepository.class),
                mock(AuditService.class));
        FailedLoginAnomalyIncidentService a = pod(dispatcher);
        a.scan();
        Long id = only().getId();

        a.handle(quiet(), Instant.now());                       // anomali bitti → çözüldü
        assertThat(only().isResolved()).isTrue();

        real.dispatchAlert(new String[]{"ops@x"}, anomalous("a", "b"), "INITIAL", id);   // uçuştaki posta şimdi bitti
        LoginAnomalyIncident after = only();
        assertThat(after.isResolved()).as("çözüm geri alınmadı").isTrue();
        assertThat(after.getLastAlertAt()).isNotNull();

        // Varlık kaydı (bayat kopya, lastAlertAt=null) başarı damgasını EZMEZ
        LoginAnomalyIncident stale = new LoginAnomalyIncident();
        stale.setId(id);
        stale.setOpenedAt(after.getOpenedAt());
        stale.setResolved(true);
        stale.setLastAlertAt(null);
        repo.save(stale);
        assertThat(only().getLastAlertAt()).isEqualTo(after.getLastAlertAt());
    }

    @Test
    @DisplayName("damga okunamazsa / kapı kapalıysa tarama eskisi gibi koşar")
    void gateHelpers() {
        FailedLoginAnomalyIncidentService a = pod(dispatcher);
        Instant now = Instant.now();
        assertThat(a.recentlyScanned(null, now)).isFalse();
        assertThat(a.recentlyScanned("bozuk", now)).isFalse();
        assertThat(a.recentlyScanned(ISO.format(now.minusSeconds(60)), now)).isTrue();
        assertThat(a.recentlyScanned(ISO.format(now.minusSeconds(598)), now)).as("aralık − 5 sn payın dışında").isFalse();
        ReflectionTestUtils.setField(a, "scanMs", 0L);
        assertThat(a.recentlyScanned(ISO.format(now.minusSeconds(60)), now)).isFalse();
        assertThat(a.attemptRecent(null, ISO.format(now))).isFalse();
        assertThat(a.attemptRecent(ISO.format(now.minusSeconds(9 * 60)), ISO.format(now))).isTrue();
        assertThat(a.attemptRecent(ISO.format(now.minusSeconds(10 * 60)), ISO.format(now))).isFalse();
    }
}
