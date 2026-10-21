package com.sitemonitor.service;

import com.sitemonitor.it.MultiPodTestSupport;
import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.SmtpSettings;
import com.sitemonitor.repository.AlertThresholdRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.DnsRecordRepository;
import com.sitemonitor.repository.DomainCheckRepository;
import com.sitemonitor.repository.DomainMonitorRepository;
import com.sitemonitor.repository.EscalationContactRepository;
import com.sitemonitor.repository.LatestCheckRepository;
import com.sitemonitor.repository.NotificationLogRepository;
import com.sitemonitor.repository.PageCheckRepository;
import com.sitemonitor.repository.TeamRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/**
 * Çok pod sertifika alarm sahiplenmeleri (2026-10-21, çok pod R1 / MP-03) — iki {@link EscalationService} örneği AYNI H2
 * veritabanını ({@code alert_events} + {@code scheduler_lock}) ve aynı bildirim sahtelerini paylaşır; aynı sonucu aynı
 * anda işler. Ölçüt: alarm başına TEK olay satırı, tetik başına TEK bildirim (kişi push'u tetiği sayılır — her
 * {@code sendCombinedAlert} çağrısı bir kez tetikler).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EscalationMultiPodClaimTest {

    static final Long OWNER = 42L;
    static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    @Mock AlertThresholdRepository thresholdRepo;
    @Mock EscalationContactRepository contactRepo;
    @Mock CertificateInventoryRepository inventoryRepo;
    @Mock EmailNotificationService emailService;
    @Mock WeeklyAvailabilityReportService weeklyAvailability;
    @Mock WebhookService webhookService;
    @Mock NotificationLogRepository notificationLogRepo;
    @Mock LatestCheckRepository latestCheckRepo;
    @Mock TeamRepository teamRepo;
    @Mock SmtpSettingsService smtpSettings;
    @Mock MaintenanceService maintenanceService;
    @Mock StormService stormService;
    @Mock UserPushService userPushService;
    @Mock DomainMonitorRepository domainMonitorRepo;
    @Mock DomainCheckRepository domainCheckRepo;
    @Mock DnsRecordRepository dnsRecordRepo;
    @Mock PageCheckRepository pageCheckRepo;
    @Mock NotificationGroupService notificationGroups;
    @Mock AppSettingsService appSettings;

    MultiPodTestSupport mp;
    H2AlertEventsFixture store;

    @BeforeEach
    void setUp() {
        mp = MultiPodTestSupport.create();
        store = new H2AlertEventsFixture(mp.jdbc());
        when(appSettings.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> i.getArgument(1));
        SmtpSettings smtp = new SmtpSettings();
        smtp.setInterDomainDelayMs(0);
        when(smtpSettings.getOrDefaults()).thenReturn(smtp);
        when(inventoryRepo.findByDomainIn(anyCollection())).thenAnswer(inv -> owned(inv.getArgument(0)));
        when(inventoryRepo.findByDomain(anyString())).thenAnswer(inv -> {
            String d = inv.getArgument(0);
            return Optional.of(owned(List.of(d)).get(0));
        });
    }

    @AfterEach
    void tearDown() {
        mp.close();
    }

    static List<CertificateInventory> owned(Collection<?> domains) {
        List<CertificateInventory> out = new ArrayList<>();
        for (Object d : domains) {
            CertificateInventory i = new CertificateInventory();
            i.setDomain(String.valueOf(d));
            i.setTeamId(OWNER);
            out.add(i);
        }
        return out;
    }

    EscalationService pod(int n) {
        EscalationService s = new EscalationService(store.repo(), thresholdRepo, contactRepo, inventoryRepo, emailService,
                weeklyAvailability, webhookService, new ObjectMapper(), notificationLogRepo, latestCheckRepo, teamRepo,
                smtpSettings, maintenanceService, stormService, userPushService, domainMonitorRepo, domainCheckRepo,
                dnsRecordRepo, pageCheckRepo, notificationGroups, appSettings);
        ReflectionTestUtils.setField(s, "self", s);
        s.setJdbcTemplate(mp.jdbc());
        s.setLockOwner(MultiPodTestSupport.owner(n));
        return s;
    }

    static Map<String, Object> expiryResult(String domain, int days) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("domain", domain);
        m.put("status", "warning");
        m.put("warning", true);
        m.put("days_remaining", days);
        m.put("revocation_status", "VALID");
        m.put("chain_status", "VALID");
        m.put("deployment_status", "OK");
        return m;
    }

    AlertEvent openAlarm(String domain, String level, Instant createdAt, Instant lastReAlertAt) {
        AlertEvent e = new AlertEvent();
        e.setDomain(domain);
        e.setAlertType("EXPIRY");
        e.setAlertLevel(level);
        e.setMessage("eski");
        e.setTeamId(OWNER);
        e.setAcknowledged(false);
        e.setResolved(false);
        e.setCreatedAt(ISO.format(createdAt));
        e.setLastReAlertAt(lastReAlertAt == null ? null : ISO.format(lastReAlertAt));
        e.setRealertCount(0);
        return store.save(e);
    }

    /** {@code trigger} tetikli kişi push çağrısı sayısı (her sendCombinedAlert bir kez tetikler). */
    long pushes(String trigger) {
        return org.mockito.Mockito.mockingDetails(userPushService).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("enqueueAlert") && trigger.equals(i.getArgument(1)))
                .count();
    }

    long openRows(String domain) {
        return store.all().stream().filter(e -> domain.equals(e.getDomain()) && !Boolean.TRUE.equals(e.getResolved())).count();
    }

    @SafeVarargs
    final <T> List<T> race(Callable<T>... tasks) {
        return mp.race(tasks);
    }

    @Test
    @DisplayName("süpürme + yeni envanterin anında kontrolü (iki pod) aynı yeni sorunu aynı anda işler → TEK olay, TEK INITIAL (20 tur)")
    void sweepAndInstantCheckRacing_oneAlarmOneInitial() {
        EscalationService a = pod(1), b = pod(2);
        for (int round = 0; round < 20; round++) {
            String domain = "race" + round + ".example.com";
            Map<String, Object> result = expiryResult(domain, 25);
            race(() -> { a.processResults(List.of(result)); return true; },
                 () -> { b.processResults(List.of(result)); return true; });
            assertThat(openRows(domain)).as("tur %d: tek açık olay", round).isEqualTo(1);
        }
        assertThat(pushes("INITIAL")).as("her sorun için tek INITIAL").isEqualTo(20);
        assertThat(mp.jdbc().queryForObject("SELECT count(*) FROM scheduler_lock WHERE name LIKE 'mon-alert:%'", Integer.class))
                .as("açılış kilitleri bırakıldı").isZero();
    }

    @Test
    @DisplayName("açılış kilidi başka pod'dayken bu tur atlanır (olay yok); kilit süresi dolunca açılır")
    void keyLockHeldElsewhere_skipsThenOpens() {
        String domain = "locked.example.com";
        mp.jdbc().update("INSERT INTO scheduler_lock(name, locked_by, locked_until) VALUES(?, ?, ?)",
                EscalationService.alertKeyLockName("EXPIRY", domain), "baska-pod", ISO.format(Instant.now().plusSeconds(60)));
        EscalationService a = pod(1);
        a.processResults(List.of(expiryResult(domain, 25)));
        assertThat(openRows(domain)).isZero();
        assertThat(pushes("INITIAL")).isZero();

        mp.jdbc().update("UPDATE scheduler_lock SET locked_until = ? WHERE name = ?",
                ISO.format(Instant.now().minusSeconds(1)), EscalationService.alertKeyLockName("EXPIRY", domain));
        a.processResults(List.of(expiryResult(domain, 25)));
        assertThat(openRows(domain)).isEqualTo(1);
        assertThat(pushes("INITIAL")).isEqualTo(1);
    }

    @Test
    @DisplayName("günlük hatırlatma vakti gelmiş açık alarm iki pod'da aynı anda işlenir → TEK DAILY_REALERT, damga + sayaç bir kez")
    void dailyRealertRace_sendsOnce() {
        String domain = "realert.example.com";
        Instant now = Instant.now();
        AlertEvent e = openAlarm(domain, "WARNING", now.minus(3, ChronoUnit.DAYS), now.minus(25, ChronoUnit.HOURS));
        EscalationService a = pod(1), b = pod(2);
        race(() -> { a.processResults(List.of(expiryResult(domain, 25))); return true; },
             () -> { b.processResults(List.of(expiryResult(domain, 25))); return true; });
        assertThat(pushes("DAILY_REALERT")).isEqualTo(1);
        AlertEvent after = store.byId(e.getId()).orElseThrow();
        assertThat(after.getRealertCount()).isEqualTo(1);
        assertThat(after.getLastReAlertAt()).isNotEqualTo(e.getLastReAlertAt());
    }

    @Test
    @DisplayName("açılış telafisi (bir pod) + sertifika süpürmesi (diğer pod) aynı vakti gelmiş alarmda → TEK DAILY_REALERT")
    void catchUpAndSweep_oneDailyRealert() {
        String domain = "catchup.example.com";
        Instant now = Instant.now();
        openAlarm(domain, "WARNING", now.minus(3, ChronoUnit.DAYS), now.minus(25, ChronoUnit.HOURS));
        when(thresholdRepo.findFirstByActiveTrue()).thenReturn(Optional.empty());
        EscalationService a = pod(1), b = pod(2);
        race(() -> { a.catchUpMissedDailyAlerts(); return true; },
             () -> { b.processResults(List.of(expiryResult(domain, 25))); return true; });
        assertThat(pushes("DAILY_REALERT")).isEqualTo(1);
        // Hemen ardından ikisi yeniden koşsa da gönderim yok (damga ilerledi)
        a.catchUpMissedDailyAlerts();
        b.processResults(List.of(expiryResult(domain, 25)));
        assertThat(pushes("DAILY_REALERT")).isEqualTo(1);
    }

    @Test
    @DisplayName("seviye terfisi (UYARI → KRİTİK) iki pod'da aynı anda → TEK ESCALATION, seviye kalıcı")
    void escalationRace_sendsOnce() {
        String domain = "escalate.example.com";
        Instant now = Instant.now();
        AlertEvent e = openAlarm(domain, "WARNING", now.minus(2, ChronoUnit.DAYS), now.minus(1, ChronoUnit.HOURS));
        EscalationService a = pod(1), b = pod(2);
        race(() -> { a.processResults(List.of(expiryResult(domain, 3))); return true; },
             () -> { b.processResults(List.of(expiryResult(domain, 3))); return true; });
        assertThat(pushes("ESCALATION")).isEqualTo(1);
        assertThat(store.byId(e.getId()).orElseThrow().getAlertLevel()).isEqualTo("CRITICAL");
    }

    @Test
    @DisplayName("yarıda kalmış İLK bildirim (damgasız, 10 dk önce açılmış) iki pod'da → TEK INITIAL")
    void interruptedInitialRace_completedOnce() {
        String domain = "interrupted.example.com";
        openAlarm(domain, "WARNING", Instant.now().minus(10, ChronoUnit.MINUTES), null);
        EscalationService a = pod(1), b = pod(2);
        race(() -> { a.processResults(List.of(expiryResult(domain, 25))); return true; },
             () -> { b.processResults(List.of(expiryResult(domain, 25))); return true; });
        assertThat(pushes("INITIAL")).isEqualTo(1);
        assertThat(openRows(domain)).isEqualTo(1);
    }

    @Test
    @DisplayName("gönderim istisnayla düşerse sahiplenilen damga GERİ alınır → sonraki tur hatırlatmayı gönderir (kayıp yok)")
    void failedSendReleasesClaim() {
        String domain = "fail.example.com";
        Instant now = Instant.now();
        AlertEvent e = openAlarm(domain, "WARNING", now.minus(3, ChronoUnit.DAYS), now.minus(25, ChronoUnit.HOURS));
        String seen = e.getLastReAlertAt();
        EscalationService a = pod(1);
        doThrow(new IllegalStateException("şablon kırık")).when(emailService).buildAlertEmailHtml(
                any(), any(), any(), any(), any(), any(), any());
        assertThatThrownBy(() -> a.processResults(List.of(expiryResult(domain, 25))))
                .isInstanceOf(IllegalStateException.class);
        assertThat(store.byId(e.getId()).orElseThrow().getLastReAlertAt()).as("damga geri alındı").isEqualTo(seen);

        reset(emailService);
        a.processResults(List.of(expiryResult(domain, 25)));
        assertThat(pushes("DAILY_REALERT")).isEqualTo(1);
    }

    @Test
    @DisplayName("veritabanı yokken (birim testi kurulumu) sahiplenme her zaman kazanır — davranış eskisiyle aynı")
    void noJdbc_legacyBehaviour() {
        EscalationService s = pod(1);
        s.setJdbcTemplate(null);
        assertThat(s.claimReAlert(1L, "x", "y", "T")).isTrue();
        assertThat(s.acquireAlertKeyLock("mon-alert:EXPIRY:x")).isEqualTo(EscalationService.KeyLock.UNAVAILABLE);
        assertThat(s.claimResolutionNotify(1L, false)).isEqualTo(EscalationService.ResolutionClaim.LEGACY);
    }

    @Test
    @DisplayName("sahiplenme: görülen damga değiştiyse ya da olay kapandıysa kaybeder; NULL damga için ayrı ifade")
    void claimSemantics() {
        Instant now = Instant.now();
        AlertEvent e = openAlarm("claim.example.com", "WARNING", now.minus(1, ChronoUnit.DAYS), now.minus(2, ChronoUnit.HOURS));
        EscalationService a = pod(1);
        String seen = e.getLastReAlertAt();
        assertThat(a.claimReAlert(e.getId(), seen, "2099-01-01T00:00:00", "T")).isTrue();
        assertThat(a.claimReAlert(e.getId(), seen, "2099-01-01T00:00:01", "T")).as("ikinci yol eski damgayla").isFalse();
        a.releaseReAlert(e.getId(), "2099-01-01T00:00:00", seen);
        assertThat(store.byId(e.getId()).orElseThrow().getLastReAlertAt()).isEqualTo(seen);

        AlertEvent unstamped = openAlarm("claim2.example.com", "WARNING", now.minus(1, ChronoUnit.DAYS), null);
        assertThat(a.claimReAlert(unstamped.getId(), null, "2099-01-01T00:00:00", "T")).isTrue();
        assertThat(a.claimReAlert(unstamped.getId(), null, "2099-01-01T00:00:01", "T")).isFalse();

        mp.jdbc().update("UPDATE alert_events SET resolved = TRUE WHERE id = ?", e.getId());
        assertThat(a.claimReAlert(e.getId(), seen, "2099-01-02T00:00:00", "T")).as("kapanmış olay").isFalse();
    }

    // ── İnceleme F2 / S-6 (2026-10-21): terfi sahiplenmesi seviyeden ÖNCE; gönderim düşerse terfi geri alınır ──────────

    /** İlk {@code failures} yeniden uyarı sahiplenmesinde geçici veritabanı hatası veren şablon (havuz zaman aşımı benzetimi). */
    static final class FlakyClaimJdbc extends org.springframework.jdbc.core.JdbcTemplate {
        final java.util.concurrent.atomic.AtomicInteger failures;

        FlakyClaimJdbc(javax.sql.DataSource ds, int failures) {
            super(ds);
            this.failures = new java.util.concurrent.atomic.AtomicInteger(failures);
        }

        @Override
        public int update(String sql, Object... args) {
            if (sql.startsWith("UPDATE alert_events SET last_re_alert_at = ?") && failures.getAndDecrement() > 0) {
                throw new org.springframework.dao.QueryTimeoutException("havuz zaman aşımı (test)");
            }
            return super.update(sql, args);
        }
    }

    AlertEvent openMonitoringAlarm(String domain, String level) {
        AlertEvent e = new AlertEvent();
        e.setDomain(domain);
        e.setAlertType(EscalationService.TYPE_ACCESSIBILITY);
        e.setAlertLevel(level);
        e.setMessage("eski");
        e.setTeamId(OWNER);
        e.setAcknowledged(true);
        e.setAcknowledgedAt(ISO.format(Instant.now().minus(1, ChronoUnit.HOURS)));
        e.setAcknowledgedBy("ayse");
        e.setResolved(false);
        Instant now = Instant.now();
        e.setCreatedAt(ISO.format(now.minus(1, ChronoUnit.DAYS)));
        e.setLastReAlertAt(ISO.format(now.minus(2, ChronoUnit.HOURS)));
        return store.save(e);
    }

    @Test
    @DisplayName("F2: izleme terfisinin sahiplenmesi veritabanı hatasıyla düşerse seviye ve onay DEĞİŞMEZ; sonraki tur ESCALATION'ı BİR kez gönderir")
    void monitoringEscalation_claimDbError_nextSweepSends() {
        String domain = "esc-db.example.com";
        AlertEvent e = openMonitoringAlarm(domain, "HIGH");
        EscalationService a = pod(1);
        a.setJdbcTemplate(new FlakyClaimJdbc(mp.dataSource(), 1));
        Map<String, Object> ctx = new LinkedHashMap<>();

        a.processConfirmedOutage(domain, EscalationService.TYPE_ACCESSIBILITY, "CRITICAL", ctx);
        AlertEvent afterFail = store.byId(e.getId()).orElseThrow();
        assertThat(afterFail.getAlertLevel()).as("terfi yazılmadı").isEqualTo("HIGH");
        assertThat(afterFail.getAcknowledged()).as("onay sessizce düşmedi").isTrue();
        assertThat(pushes("ESCALATION")).isZero();

        a.processConfirmedOutage(domain, EscalationService.TYPE_ACCESSIBILITY, "CRITICAL", ctx);
        AlertEvent after = store.byId(e.getId()).orElseThrow();
        assertThat(after.getAlertLevel()).isEqualTo("CRITICAL");
        assertThat(after.getAcknowledged()).isFalse();
        assertThat(pushes("ESCALATION")).isEqualTo(1);

        a.processConfirmedOutage(domain, EscalationService.TYPE_ACCESSIBILITY, "CRITICAL", ctx);
        assertThat(pushes("ESCALATION")).as("bir kez").isEqualTo(1);
    }

    @Test
    @DisplayName("F2: izleme terfisi iki pod'da aynı anda → TEK ESCALATION")
    void monitoringEscalationRace_sendsOnce() {
        for (int round = 0; round < 20; round++) {
            store.jdbc().update("DELETE FROM alert_events");
            org.mockito.Mockito.clearInvocations(userPushService);
            escalationRaceRound("esc-race-" + round + ".example.com");
        }
    }

    @Test
    @DisplayName("F2: ikinci pod birincinin damgasını ESKİ seviyeyle okursa (seviye henüz kaydedilmemişken) sahiplenemez — seviye koşulda")
    void escalationClaimChecksTheSeenLevel() {
        AlertEvent e = openMonitoringAlarm("esc-stale.example.com", "HIGH");
        EscalationService a = pod(1), b = pod(2);
        String aAt = ISO.format(Instant.now());
        assertThat(a.claimEscalation(e.getId(), e.getLastReAlertAt(), "HIGH", "CRITICAL", aAt)).isTrue();
        assertThat(b.claimEscalation(e.getId(), aAt, "HIGH", "CRITICAL", ISO.format(Instant.now().plusSeconds(1)))).isFalse();
        AlertEvent row = store.byId(e.getId()).orElseThrow();
        assertThat(row.getAlertLevel()).isEqualTo("CRITICAL");
        assertThat(row.getLastReAlertAt()).isEqualTo(aAt);
    }

    void escalationRaceRound(String domain) {
        openMonitoringAlarm(domain, "HIGH");
        EscalationService a = pod(1), b = pod(2);
        race(() -> { a.processConfirmedOutage(domain, EscalationService.TYPE_ACCESSIBILITY, "CRITICAL", new LinkedHashMap<>()); return true; },
             () -> { b.processConfirmedOutage(domain, EscalationService.TYPE_ACCESSIBILITY, "CRITICAL", new LinkedHashMap<>()); return true; });
        assertThat(pushes("ESCALATION")).isEqualTo(1);
    }

    @Test
    @DisplayName("S-6: sertifika terfisinin gönderimi istisnayla düşerse seviye / onay / damga GERİ alınır → sonraki tur ESCALATION'ı gönderir")
    void certEscalation_sendFailure_revertsAndNextRunSends() {
        String domain = "esc-fail.example.com";
        Instant now = Instant.now();
        AlertEvent e = openAlarm(domain, "WARNING", now.minus(2, ChronoUnit.DAYS), now.minus(1, ChronoUnit.HOURS));
        String seen = e.getLastReAlertAt();
        EscalationService a = pod(1);
        doThrow(new IllegalStateException("şablon kırık")).when(emailService).buildAlertEmailHtml(
                any(), any(), any(), any(), any(), any(), any());
        assertThatThrownBy(() -> a.processResults(List.of(expiryResult(domain, 3)))).isInstanceOf(IllegalStateException.class);
        AlertEvent reverted = store.byId(e.getId()).orElseThrow();
        assertThat(reverted.getAlertLevel()).isEqualTo("WARNING");
        assertThat(reverted.getLastReAlertAt()).isEqualTo(seen);

        reset(emailService);
        a.processResults(List.of(expiryResult(domain, 3)));
        assertThat(pushes("ESCALATION")).isEqualTo(1);
        assertThat(store.byId(e.getId()).orElseThrow().getAlertLevel()).isEqualTo("CRITICAL");
    }
}
