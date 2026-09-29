package com.sitemonitor.service;

import tools.jackson.databind.ObjectMapper;
import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.AlertThresholdRepository;
import com.sitemonitor.repository.EscalationContactRepository;
import com.sitemonitor.repository.NotificationLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Regression (prod 2026-09-29): Alarm Geçmişi'nde rozet WARNING, ileti "KRİTİK: … 443/TCP portuna erişilemiyor".
 *
 * <p>İzleme alarm iletileri seviye etiketini SABİT yazıyordu ("KRİTİK:" / "YÜKSEK:"). 2026-09-19 ürün kararından beri
 * izleme alarmları varsayılan WARNING açılıyor (seviye izlemeden gelir), ama ileti şablonları güncellenmemişti — aynı
 * satırda rozet UYARI, metin KRİTİK. İleti artık alarmın GERÇEK seviyesinden etiketlenir (UYARI / YÜKSEK / KRİTİK;
 * Alarm Geçmişi rozetiyle ve push metninin seviye sözcüğüyle aynı). Kapsam: izleme kaynaklı TÜM türler, hem zengin
 * ileti (monitoringMessage) hem bağlamsız yedek (buildMessage — "Tekrar bildir" bu yoldan geçer).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MonitoringAlertLevelWordTest {

    @Mock com.sitemonitor.service.NotificationGroupService notificationGroups;
    @Mock AlertEventRepository alertEventRepo;
    @Mock AlertThresholdRepository thresholdRepo;
    @Mock EscalationContactRepository contactRepo;
    @Mock com.sitemonitor.repository.CertificateInventoryRepository inventoryRepo;
    @Mock EmailNotificationService emailService;
    @Mock WeeklyAvailabilityReportService weeklyAvailability;
    @Mock WebhookService webhookService;
    @Mock NotificationLogRepository notificationLogRepo;
    @Mock com.sitemonitor.repository.LatestCheckRepository latestCheckRepo;
    @Mock com.sitemonitor.repository.TeamRepository teamRepo;
    @Mock SmtpSettingsService smtpSettings;
    @Mock MaintenanceService maintenanceService;
    @Mock StormService stormService;
    @Mock UserPushService userPushService;
    @Mock com.sitemonitor.repository.DomainMonitorRepository domainMonitorRepo;
    @Mock com.sitemonitor.repository.DomainCheckRepository domainCheckRepo;
    @Mock com.sitemonitor.repository.DnsRecordRepository dnsRecordRepo;
    @Mock com.sitemonitor.repository.PageCheckRepository pageCheckRepo;
    @Mock AppSettingsService appSettings;

    private EscalationService service;

    @BeforeEach
    void setUp() {
        service = new EscalationService(alertEventRepo, thresholdRepo, contactRepo,
                inventoryRepo, emailService, weeklyAvailability, webhookService, new ObjectMapper(), notificationLogRepo,
                latestCheckRepo, teamRepo, smtpSettings, maintenanceService, stormService, userPushService,
                domainMonitorRepo, domainCheckRepo, dnsRecordRepo, pageCheckRepo, notificationGroups, appSettings);
        lenient().when(appSettings.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> i.getArgument(1));
        ReflectionTestUtils.setField(service, "self", service);
        when(smtpSettings.getOrDefaults()).thenReturn(new com.sitemonitor.model.SmtpSettings());
    }

    private static String word(String level) {
        return switch (level) {
            case "CRITICAL" -> "KRİTİK";
            case "HIGH" -> "YÜKSEK";
            default -> "UYARI";
        };
    }

    /** Her türün ZENGİN ileti dalını tetikleyen gerçek sweep bağlamı (SchedulerService'in yazdığı anahtarlar). */
    private static Map<String, Map<String, Object>> richContexts() {
        Map<String, Map<String, Object>> m = new LinkedHashMap<>();
        m.put(EscalationService.TYPE_PORT_DOWN, Map.of("port", 443, "protocol", "TCP"));
        m.put(EscalationService.TYPE_PORT_SLOW, Map.of("port", 443, "protocol", "TCP", "response_ms", 4200, "threshold_ms", 3000));
        m.put(EscalationService.TYPE_DNS_FAILURE, Map.of("record_type", "A"));
        m.put(EscalationService.TYPE_DNS_SLOW, Map.of("record_type", "A", "response_ms", 900));
        m.put(EscalationService.TYPE_DNS_CHANGED, Map.of("record_type", "A", "old_values", List.of("192.0.2.1"), "new_values", List.of("192.0.2.2")));
        m.put(EscalationService.TYPE_DNS_UNEXPECTED, Map.of("record_type", "A", "unexpected_values", List.of("192.0.2.9")));
        m.put(EscalationService.TYPE_DNS_INCONSISTENT, Map.of("record_type", "A"));
        m.put(EscalationService.TYPE_KEYWORD, Map.of("url", "https://app.example.com/", "keyword", "Giriş"));
        m.put(EscalationService.TYPE_KEYWORD_SLOW, Map.of("url", "https://app.example.com/"));
        m.put(EscalationService.TYPE_KEYWORD_SSL, Map.of("url", "https://app.example.com/"));
        m.put(EscalationService.TYPE_KEYWORD_DOMAIN_EXPIRY, Map.of("url", "https://app.example.com/"));
        m.put(EscalationService.TYPE_PING_DOWN, Map.of("host", "app.example.com"));
        m.put(EscalationService.TYPE_PING_SLOW, Map.of("host", "app.example.com"));
        m.put(EscalationService.TYPE_HTTP_DOWN, Map.of("url", "https://app.example.com/"));
        m.put(EscalationService.TYPE_HTTP_SSL, Map.of("url", "https://app.example.com/"));
        m.put(EscalationService.TYPE_PAGE_DOWN, Map.of("url", "https://app.example.com/"));
        m.put(EscalationService.TYPE_PAGE_INTEGRITY, Map.of("url", "https://app.example.com/"));
        m.put(EscalationService.TYPE_PAGESPEED_DOWN, Map.of("url", "https://app.example.com/"));
        m.put(EscalationService.TYPE_PAGESPEED_SLOW, Map.of("url", "https://app.example.com/"));
        m.put(EscalationService.TYPE_SCRIPTED_FAIL, Map.of("detail", "FAIL — 1✓/3✗"));
        m.put(EscalationService.TYPE_SCRIPTED_SLOW, Map.of("duration_ms", 20000));
        m.put(EscalationService.TYPE_DOMAIN_EXPIRY, Map.of("domain", "example.com"));
        m.put(EscalationService.TYPE_DOMAINMON_EXPIRY, Map.of("domain", "example.com", "days", 20));
        m.put(EscalationService.TYPE_DOMAINMON_UNKNOWN, Map.of("domain", "example.com"));
        m.put(EscalationService.TYPE_DOMAINMON_STATUS, Map.of("domain", "example.com", "status_codes", "clientHold"));
        m.put(EscalationService.TYPE_DOMAINMON_TRANSFER_LOCK, Map.of("domain", "example.com"));
        m.put(EscalationService.TYPE_DOMAINMON_BLACKLIST, Map.of("domain", "example.com"));
        m.put(EscalationService.TYPE_DOMAINMON_CHANGED, Map.of("domain", "example.com"));
        m.put(EscalationService.TYPE_ACCESSIBILITY, Map.of("port", 443));
        return m;
    }

    @Test
    @DisplayName("Zengin ileti: her izleme türünde seviye sözcüğü alarmın GERÇEK seviyesiyle başlar (WARNING → 'UYARI:')")
    void richMessage_levelWordFollowsAlarmLevel_everyMonitoringType() {
        List<String> wrong = new ArrayList<>();
        for (var e : richContexts().entrySet()) {
            for (String level : List.of("WARNING", "HIGH", "CRITICAL")) {
                String msg = ReflectionTestUtils.invokeMethod(service, "monitoringMessage",
                        "app.example.com", e.getKey(), level, e.getValue());
                if (msg == null || !msg.startsWith(word(level) + ": ")) wrong.add(e.getKey() + "/" + level + " → " + msg);
            }
        }
        assertThat(wrong).as("seviye sözcüğü uyuşmayan iletiler").isEmpty();
    }

    @Test
    @DisplayName("Bağlamsız yedek ileti (buildMessage — 'Tekrar bildir' yolu): izleme türlerinde seviye sözcüğü seviyeden gelir")
    void fallbackMessage_levelWordFollowsAlarmLevel_everyMonitoringType() {
        List<String> wrong = new ArrayList<>();
        for (String type : EscalationService.MONITORING_ALERT_TYPES) {
            for (String level : List.of("WARNING", "HIGH", "CRITICAL")) {
                String msg = ReflectionTestUtils.invokeMethod(service, "buildMessage",
                        "app.example.com", type, level, (Integer) null);
                if (msg == null || !msg.startsWith(word(level) + ": ")) wrong.add(type + "/" + level + " → " + msg);
            }
        }
        assertThat(wrong).as("seviye sözcüğü uyuşmayan yedek iletiler").isEmpty();
    }

    @Test
    @DisplayName("Sertifika iletileri DEĞİŞMEZ: seviye dışı etiketler (DAĞITIM EKSİK / ZİNCİR SORUNU) korunur")
    void certMessages_untouched() {
        String mismatch = ReflectionTestUtils.invokeMethod(service, "buildMessage", "app.example.com", "MISMATCH", "WARNING", (Integer) null);
        String chain = ReflectionTestUtils.invokeMethod(service, "buildMessage", "app.example.com", "CHAIN_BROKEN", "HIGH", (Integer) null);
        assertThat(mismatch).startsWith("DAĞITIM EKSİK:");
        assertThat(chain).startsWith("ZİNCİR SORUNU:");
    }

    @Test
    @DisplayName("VAKA 1 uçtan uca: WARNING seviyeli Port kesintisi 'UYARI: … 443/TCP portuna erişilemiyor' iletisiyle açılır")
    void portDownWarning_savedMessageSaysUyari() {
        when(alertEventRepo.findOpenAlerts(anyString(), anyString())).thenReturn(List.of());
        when(alertEventRepo.save(any(AlertEvent.class))).thenAnswer(i -> {
            AlertEvent a = i.getArgument(0);
            if (a.getId() == null) a.setId(77L);
            return a;
        });
        when(alertEventRepo.findById(anyLong())).thenReturn(Optional.empty());
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("port", 443);
        ctx.put("protocol", "TCP");
        ctx.put("monitor_id", 11L);
        ctx.put("team_id", 7L);
        ctx.put("standalone", true);
        ctx.put("alert_level", "WARNING");

        service.processConfirmedOutage("app.example.com", EscalationService.TYPE_PORT_DOWN, "WARNING", ctx);

        ArgumentCaptor<AlertEvent> saved = ArgumentCaptor.forClass(AlertEvent.class);
        verify(alertEventRepo, atLeastOnce()).save(saved.capture());
        AlertEvent ev = saved.getAllValues().get(0);
        assertThat(ev.getAlertLevel()).isEqualTo("WARNING");
        assertThat(ev.getMessage()).startsWith("UYARI: app.example.com üzerindeki 443/TCP portuna erişilemiyor")
                .doesNotContain("KRİTİK");
    }
}
