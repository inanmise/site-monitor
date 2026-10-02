package com.sitemonitor.service;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.EscalationContact;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.*;
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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tür bazlı yeniden uyarı sıklığı (2026-10-01, opt-in) — "Yeniden uyarının varsayılanı bugünkü 24 saat kalır."
 * Ailenin ayarı 0 iken karar bugünkü {@code reAlertDue(son, şimdi, genelSaat)} ile BİREBİR aynıdır; değer verilen aile kendi
 * dakika aralığını kullanır, diğer aileler etkilenmez.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReAlertIntervalOverrideTest {

    @Mock AlertEventRepository alertEventRepo;
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

    static final long TEAM = 42L;
    static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    EscalationService service;
    final Map<String, Integer> overrides = new HashMap<>();

    @BeforeEach
    void setUp() {
        service = new EscalationService(alertEventRepo, thresholdRepo, contactRepo, inventoryRepo, emailService,
                weeklyAvailability, webhookService, new ObjectMapper(), notificationLogRepo, latestCheckRepo, teamRepo,
                smtpSettings, maintenanceService, stormService, userPushService, domainMonitorRepo, domainCheckRepo,
                dnsRecordRepo, pageCheckRepo, notificationGroups, appSettings);
        ReflectionTestUtils.setField(service, "self", service);
        when(appSettings.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> i.getArgument(1));
        when(appSettings.getInt(anyString(), anyInt()))
                .thenAnswer(i -> overrides.getOrDefault((String) i.getArgument(0), (Integer) i.getArgument(1)));
        when(smtpSettings.getOrDefaults()).thenReturn(new com.sitemonitor.model.SmtpSettings());
        when(thresholdRepo.findFirstByActiveTrue()).thenReturn(Optional.empty());   // genel aralık: 24 saat
        Team team = new Team();
        team.setId(TEAM);
        team.setName("Platform");
        team.setEmail("team@x.com");
        when(teamRepo.findById(TEAM)).thenReturn(Optional.of(team));
        when(alertEventRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(emailService.sendAlert(any(String[].class), anyString(), anyString(), any(), any(), any(), any(), any()))
                .thenReturn("SENT");
        EscalationContact c = new EscalationContact();
        c.setId(1L); c.setTeamId(TEAM); c.setEmail("mgr@x.com"); c.setRole("MANAGER"); c.setMinAlertLevel("WARNING"); c.setActive(true);
        when(contactRepo.findByTeamIdAndActiveTrueOrderByRoleAsc(TEAM)).thenReturn(List.of(c));
        when(contactRepo.findByTeamIdAndMinAlertLevelAndActiveTrue(TEAM, "WARNING")).thenReturn(List.of(c));
    }

    private static String ago(int minutes) {
        return ISO.format(Instant.now().minus(minutes, ChronoUnit.MINUTES));
    }

    private AlertEvent openMonitoringAlarm(String domain, String type, int lastAlertMinutesAgo) {
        AlertEvent e = new AlertEvent();
        e.setId(9L);
        e.setDomain(domain);
        e.setAlertType(type);
        e.setAlertLevel("CRITICAL");
        e.setTeamId(TEAM);
        e.setAcknowledged(false);
        e.setResolved(false);
        e.setCreatedAt(ago(3 * 24 * 60));
        e.setLastReAlertAt(ago(lastAlertMinutesAgo));
        e.setContextJson("{\"team_id\":42}");
        when(alertEventRepo.findOpenAlert(domain, type)).thenReturn(Optional.of(e));
        return e;
    }

    private static Map<String, Object> ctx() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("team_id", TEAM);
        return m;
    }

    private void verifyRealertMails(int n) {
        verify(emailService, times(n)).sendAlert(any(String[].class), contains("[RE-ALERT]"), anyString(),
                any(), any(), any(), any(), any());
    }

    // ── saf karar ───────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Ayar 0 (varsayılan): her aile ve her zaman için karar bugünkü reAlertDue ile BİREBİR aynı")
    void zeroOverride_isExactlyTheGlobalDecision() {
        String[] lasts = {"2026-07-04T10:00:00", "2026-07-04T23:59:00", "bozuk", "2026-07-04T00:00:00"};
        String[] nows = {"2026-07-04T10:14:59", "2026-07-04T22:00:00", "2026-07-05T00:00:30", "2026-07-05T10:00:00",
                "2026-07-11T10:00:00"};
        int[] hours = {1, 12, 24, 72};
        Set<String> types = new LinkedHashSet<>(MonitorTypeCatalog.allAlertTypes());
        types.add("UNKNOWN_TYPE");
        types.add(null);
        int checked = 0;
        for (String type : types) for (String last : lasts) for (String now : nows) for (int h : hours) {
            assertThat(service.reAlertDueFor(type, last, now, h))
                    .as("%s %s→%s %dh", type, last, now, h)
                    .isEqualTo(EscalationService.reAlertDue(last, now, h));
            checked++;
        }
        assertThat(checked).isGreaterThan(1000);
    }

    @Test
    @DisplayName("HTTP ailesi 15 dk → HTTP alarmı 15 dk'da bir; ping / sertifika / DNS genel aralıkta (24 saat) kalır")
    void familyOverride_appliesOnlyToItsFamily() {
        overrides.put("site.monitor.realert.http-minutes", 15);
        String last = "2026-07-04T10:00:00", at20 = "2026-07-04T10:20:00", at14 = "2026-07-04T10:14:00";

        assertThat(service.reAlertDueFor("HTTP_DOWN", last, at20, 24)).isTrue();
        assertThat(service.reAlertDueFor("ACCESSIBILITY", last, at20, 24)).isTrue();   // http ailesi
        assertThat(service.reAlertDueFor("HTTP_DOWN", last, at14, 24)).isFalse();
        for (String other : List.of("PING_DOWN", "EXPIRY", "DNS_FAILURE", "SCRIPTED_FAIL", "DOMAINMON_EXPIRY")) {
            assertThat(service.reAlertDueFor(other, last, at20, 24)).as(other).isFalse();
        }
        assertThat(service.familyReAlertMinutes("PORT_DOWN")).isZero();
    }

    @Test
    @DisplayName("Kıskaç: elle yazılmış aralık dışı değer güvenli sınıra çekilir; negatif = geçersiz kılma yok")
    void effective_clampsOutOfRange() {
        assertThat(ReAlertIntervals.effective(0)).isZero();
        assertThat(ReAlertIntervals.effective(-5)).isZero();
        assertThat(ReAlertIntervals.effective(5)).isEqualTo(15);
        assertThat(ReAlertIntervals.effective(60)).isEqualTo(60);
        assertThat(ReAlertIntervals.effective(999_999)).isEqualTo(10080);
        assertThat(EscalationService.reAlertDueMinutes("2026-07-04T10:00:00", "2026-07-04T10:59:59", 60)).isFalse();
        assertThat(EscalationService.reAlertDueMinutes("2026-07-04T10:00:00", "2026-07-04T11:00:00", 60)).isTrue();
        assertThat(EscalationService.reAlertDueMinutes("bozuk", "2026-07-04T11:00:00", 60)).isTrue();
    }

    // ── gerçek yollar ───────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("İzleme yolu: HTTP 15 dk ayarında 20 dk önceki hatırlatmadan sonra yeniden uyarı gider; ayar 0 iken gitmez")
    void monitoringPath_familyIntervalDrivesRealert() {
        openMonitoringAlarm("api.example.com", "HTTP_DOWN", 20);
        service.processConfirmedOutage("api.example.com", "HTTP_DOWN", "CRITICAL", ctx());
        verifyRealertMails(0);   // bugünkü davranış: 24 saat dolmadı

        overrides.put("site.monitor.realert.http-minutes", 15);
        service.processConfirmedOutage("api.example.com", "HTTP_DOWN", "CRITICAL", ctx());
        verifyRealertMails(1);
    }

    @Test
    @DisplayName("İzleme yolu: HTTP ailesinin ayarı PING alarmını etkilemez")
    void monitoringPath_otherFamilyUnaffected() {
        overrides.put("site.monitor.realert.http-minutes", 15);
        openMonitoringAlarm("10.0.0.5", "PING_DOWN", 20);

        service.processConfirmedOutage("10.0.0.5", "PING_DOWN", "CRITICAL", ctx());

        verifyRealertMails(0);
    }

    @Test
    @DisplayName("Sertifika süpürmesi: cert ailesi 60 dk → 2 saat önceki hatırlatmadan sonra [RE-ALERT]; 0 iken (24 saat) gitmez")
    void certPath_familyIntervalDrivesRealert() {
        String domain = "pay.example.com";
        AlertEvent e = new AlertEvent();
        e.setId(5L); e.setDomain(domain); e.setAlertType("EXPIRY"); e.setAlertLevel("WARNING"); e.setTeamId(TEAM);
        e.setAcknowledged(false); e.setResolved(false);
        e.setCreatedAt(ago(5 * 24 * 60)); e.setLastReAlertAt(ago(120));
        when(alertEventRepo.findOpenByDomainIn(anyCollection())).thenReturn(List.of(e));
        CertificateInventory inv = new CertificateInventory();
        inv.setDomain(domain); inv.setTeamId(TEAM);
        when(inventoryRepo.findByDomainIn(anyCollection())).thenReturn(List.of(inv));
        Map<String, Object> result = new LinkedHashMap<>(Map.of("domain", domain, "status", "warning", "warning", true,
                "days_remaining", 25, "revocation_status", "VALID", "chain_status", "VALID", "deployment_status", "OK"));

        service.processResults(List.of(result));
        verifyRealertMails(0);

        overrides.put("site.monitor.realert.cert-minutes", 60);
        e.setLastReAlertAt(ago(120));
        service.processResults(List.of(result));
        verifyRealertMails(1);
    }

    // ── katalog / kaydetme doğrulaması ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("Her aile anahtarı katalogda INT / 'realert' grubunda, GLOBAL_ONLY değil; varsayılanı 0 (application.properties)")
    void catalogAndDefaults() throws IOException {
        assertThat(ReAlertIntervals.KEYS.keySet()).containsExactlyElementsOf(MonitorTypeCatalog.ORDER);
        Path props = Path.of("src/main/resources/application.properties");
        if (!Files.exists(props)) props = Path.of("backend/src/main/resources/application.properties");
        String src = Files.readString(props, StandardCharsets.ISO_8859_1);
        for (String key : ReAlertIntervals.KEYS.values()) {
            AppSettingsCatalog.Setting s = AppSettingsCatalog.byKey(key);
            assertThat(s).as(key).isNotNull();
            assertThat(s.type()).isEqualTo(AppSettingsCatalog.Type.INT);
            assertThat(s.group()).isEqualTo("realert");
            assertThat(AppSettingsCatalog.isGlobalOnly(key)).as(key).isFalse();
            assertThat(src).as("varsayılan 0: " + key).containsPattern(java.util.regex.Pattern.quote(key) + "=\\$\\{[A-Z_]+:0}");
        }
    }

    @Test
    @DisplayName("Kaydetme doğrulaması: 0 ya da 15–10080; aralık dışı / sayı olmayan değer 400 (boş = varsayılana dön)")
    void validate_rangeRule() {
        String k = "site.monitor.realert.dns-minutes";
        for (String ok : new String[]{"0", "15", "60", "10080", "", null, " 30 "}) ReAlertIntervals.validate(k, ok);
        for (String bad : new String[]{"1", "14", "10081", "-1", "abc", "1.5"}) {
            assertThatThrownBy(() -> ReAlertIntervals.validate(k, bad)).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(ReAlertIntervals.isKey(k)).isTrue();
        assertThat(ReAlertIntervals.isKey("site.monitor.storm.quiet-minutes")).isFalse();
        assertThat(ReAlertIntervals.keyForAlertType("KEYWORD_SLOW")).isEqualTo("site.monitor.realert.keyword-minutes");
        assertThat(ReAlertIntervals.keyForAlertType("NOT_A_TYPE")).isNull();
    }
}
