package com.sitemonitor.service;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.AlertEventSilentCloseRepository;
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
import com.sitemonitor.service.noc.NocNotificationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.Invocation;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Alarm olaylarının aktivite satırları (2026-11-05, aktivite zenginleştirmesi B) GERÇEK H2 üstünde — çok pod: satırı yalnız
 * kapanışı / onayı GERÇEKTEN yazan çağıran (CAS / koşullu güncelleme kazananı) üretir, tam bir kez. Sessiz kapanışın tek
 * yazım noktası {@link SilentAlarmCloser}; elle / otomatik kapanış {@code markResolvedIfOpenNotify}; açılış yeni olay
 * kaydından sonra. {@code NOT_SUPPORTED}: test işlemi yarışı gizlerdi.
 */
@DataJpaTest
@Import(AlertEventFieldWriter.class)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class EscalationActivityEventsTest {

    private static final String HTTP = EscalationService.TYPE_HTTP_DOWN;
    private static final String HTTP_SLOW = EscalationService.TYPE_HTTP_SLOW;
    private static final String CTX = "{\"monitor_id\":31,\"team_id\":7,\"standalone\":true}";

    @Autowired DataSource dataSource;
    @Autowired AlertEventRepository realRepo;
    @Autowired AlertEventSilentCloseRepository closeRepo;
    @Autowired CertificateInventoryRepository inventoryRepo;
    @Autowired AlertEventFieldWriter writer;

    JdbcTemplate jdbc;
    UserPushService push;
    StormService storm;
    MaintenanceService maintenance;
    ActivityLogService activity;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        realRepo.deleteAll();
        push = mock(UserPushService.class);
        storm = mock(StormService.class);
        maintenance = mock(MaintenanceService.class);
        activity = mock(ActivityLogService.class);
    }

    @AfterEach
    void tearDown() {
        realRepo.deleteAll();
    }

    @SuppressWarnings("unchecked")
    private SilentAlarmCloser closer() {
        ObjectProvider<UserPushService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(push);
        SilentAlarmCloser c = new SilentAlarmCloser(closeRepo, realRepo, inventoryRepo, provider);
        c.setActivityLog(activity);
        return c;
    }

    private EscalationService pod() {
        return pod(mock(EscalationContactRepository.class));
    }

    private EscalationService pod(EscalationContactRepository contacts) {
        AppSettingsService settings = mock(AppSettingsService.class);
        when(settings.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> i.getArgument(1));
        EscalationService s = new EscalationService(realRepo, mock(AlertThresholdRepository.class),
                contacts, inventoryRepo, mock(EmailNotificationService.class),
                mock(WeeklyAvailabilityReportService.class), mock(WebhookService.class), new ObjectMapper(),
                mock(NotificationLogRepository.class), mock(LatestCheckRepository.class), mock(TeamRepository.class),
                mock(SmtpSettingsService.class), maintenance, storm, push, mock(DomainMonitorRepository.class),
                mock(DomainCheckRepository.class), mock(DnsRecordRepository.class), mock(PageCheckRepository.class),
                mock(NotificationGroupService.class), settings);
        ReflectionTestUtils.setField(s, "self", mock(EscalationService.class));
        ReflectionTestUtils.setField(s, "nocNotifications", mock(NocNotificationService.class));
        s.setJdbcTemplate(jdbc);
        s.setSilentAlarmCloser(closer());
        s.setFieldWriter(writer);
        s.setActivityLog(activity);
        return s;
    }

    private AlertEvent open(String domain, String type) {
        AlertEvent e = new AlertEvent();
        e.setDomain(domain);
        e.setAlertType(type);
        e.setAlertLevel("HIGH");
        e.setTeamId(7L);
        e.setResolved(false);
        e.setAcknowledged(false);
        e.setCreatedAt("2026-11-05T08:00:00");
        e.setLastReAlertAt("2026-11-05T08:00:00");
        e.setContextJson(CTX);
        return realRepo.save(e);
    }

    /** {@code recordAlert(event, action, …)} çağrıları — {action → alarm kimliği}. */
    private List<Invocation> alertCalls(String action) {
        return mockingDetails(activity).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("recordAlert"))
                .filter(i -> action.equals(i.getArgument(1)))
                .toList();
    }

    private static Long alarmIdOf(Invocation i) {
        return ((AlertEvent) i.getArgument(0)).getId();
    }

    @Test
    @DisplayName("Elle kapanış: CAS kazananı TEK ALERT_RESOLVED ('elle <ad>'); zaten kapalı alarm yeniden çözülünce satır yok")
    void manualResolve_onceAndIdempotent() {
        AlertEvent a = open("https://svc.example.com/", HTTP);
        EscalationService s = pod();
        s.resolve(a.getId(), "Ayşe Yılmaz", "kök neden giderildi");
        s.resolve(a.getId(), "Ayşe Yılmaz", "ikinci tık");

        List<Invocation> calls = alertCalls(ActivityLogService.ACTION_ALERT_RESOLVED);
        assertThat(calls).hasSize(1);
        assertThat(alarmIdOf(calls.get(0))).isEqualTo(a.getId());
        assertThat((String) calls.get(0).getArgument(5)).isEqualTo("elle Ayşe Yılmaz");
        assertThat((ActivityLogService.AlertInfo) calls.get(0).getArgument(6))
                .isEqualTo(ActivityLogService.AlertInfo.closed(ActivityLogService.CLOSED_MANUAL, "Ayşe Yılmaz"));
    }

    @Test
    @DisplayName("İki pod aynı alarmı aynı anda elle çözer: ALERT_RESOLVED TAM bir kez")
    void twoPodsManualResolve_exactlyOnce() throws Exception {
        AlertEvent a = open("https://race.example.com/", HTTP);
        EscalationService p1 = pod(), p2 = pod();
        CyclicBarrier go = new CyclicBarrier(2);
        ExecutorService ex = Executors.newFixedThreadPool(2);
        try {
            Future<?> f1 = ex.submit(() -> { go.await(10, TimeUnit.SECONDS); return p1.resolve(a.getId(), "A", "n1"); });
            Future<?> f2 = ex.submit(() -> { go.await(10, TimeUnit.SECONDS); return p2.resolve(a.getId(), "B", "n2"); });
            f1.get(30, TimeUnit.SECONDS);
            f2.get(30, TimeUnit.SECONDS);
        } finally {
            ex.shutdownNow();
        }
        assertThat(alertCalls(ActivityLogService.ACTION_ALERT_RESOLVED)).hasSize(1);
    }

    @Test
    @DisplayName("İki pod aynı sessiz kapanışı koşar: her alarm için TEK ALERT_RESOLVED ('sessiz · <neden>'), kapatmayan yazmaz")
    void twoPodsSilentClose_oncePerAlarm() throws Exception {
        String domain = "https://flow.example.com/";
        Long a = open(domain, HTTP).getId();
        Long b = open(domain, HTTP_SLOW).getId();
        EscalationService p1 = pod(), p2 = pod();
        CyclicBarrier go = new CyclicBarrier(2);
        ExecutorService ex = Executors.newFixedThreadPool(2);
        try {
            Future<?> f1 = ex.submit(() -> { go.await(10, TimeUnit.SECONDS);
                p1.resolveOpenAlertsSilently(domain, Set.of(HTTP, HTTP_SLOW), "Sistem (izleme silindi)"); return null; });
            Future<?> f2 = ex.submit(() -> { go.await(10, TimeUnit.SECONDS);
                p2.resolveOpenAlertsSilently(domain, Set.of(HTTP, HTTP_SLOW), "Sistem (izleme duraklatıldı)"); return null; });
            f1.get(30, TimeUnit.SECONDS);
            f2.get(30, TimeUnit.SECONDS);
        } finally {
            ex.shutdownNow();
        }
        List<Invocation> calls = alertCalls(ActivityLogService.ACTION_ALERT_RESOLVED);
        assertThat(calls).extracting(EscalationActivityEventsTest::alarmIdOf).containsExactlyInAnyOrder(a, b);
        assertThat(calls).allSatisfy(i -> assertThat((String) i.getArgument(5)).startsWith("sessiz · Sistem ("));
    }

    @Test
    @DisplayName("Onay: yazılan onay ALERT_ACKNOWLEDGED (aktör = verilen ad, oturum yok); kapanmış alarmın onayı satır üretmez")
    void acknowledge_recordedOnlyWhenWritten() {
        AlertEvent a = open("https://ack.example.com/", HTTP);
        EscalationService s = pod();
        s.acknowledge(a.getId(), "Mehmet Demir", "inceleniyor");
        List<Invocation> calls = alertCalls(ActivityLogService.ACTION_ALERT_ACKNOWLEDGED);
        assertThat(calls).hasSize(1);
        assertThat((String) calls.get(0).getArgument(4)).isEqualTo("Mehmet Demir");

        AlertEvent closed = open("https://ack2.example.com/", HTTP);
        jdbc.update("UPDATE alert_events SET resolved = TRUE, resolved_by = 'x', resolved_at = '2026-11-05T09:00:00' WHERE id = ?",
                closed.getId());
        assertThatThrownBy(() -> s.acknowledge(closed.getId(), "Mehmet Demir", "geç kaldı"))
                .isInstanceOf(EscalationService.AlertClosedMeanwhileException.class);
        assertThat(alertCalls(ActivityLogService.ACTION_ALERT_ACKNOWLEDGED)).hasSize(1);
    }

    @Test
    @DisplayName("Aktivite servisi patlasa da kapanış / onay sürer (yazım alarm yolunu ASLA bozmaz)")
    void activityFailure_neverBreaksAlarmPath() {
        doThrow(new RuntimeException("db down")).when(activity).recordAlert(any(), anyString(), any(), any(), any(), any(), any());
        AlertEvent a = open("https://boom.example.com/", HTTP);
        EscalationService s = pod();
        s.acknowledge(a.getId(), "ops", "x y z");
        AlertEvent closed = s.resolve(a.getId(), "ops", "x y z");
        assertThat(closed.getResolved()).isTrue();
        assertThat(realRepo.findById(a.getId()).orElseThrow().getResolved()).isTrue();
    }

    @Test
    @DisplayName("İzleme alarmı açılışı: ALERT_TRIGGERED tam bir kez, izleme kimliği tarama bağlamından; ikinci tur (açık alarm) yazmaz")
    void monitoringOpen_triggeredOnce() {
        when(maintenance.isUnderMaintenance(anyString())).thenReturn(false);
        when(storm.evaluate(any(), any())).thenReturn(StormService.StormAction.SEND_INDIVIDUAL);
        EscalationService s = pod();
        Map<String, Object> ctx = new java.util.LinkedHashMap<>(Map.of("monitor_id", 31, "team_id", 7, "standalone", true));
        try {
            s.processConfirmedOutage("https://open.example.com/", HTTP, "HIGH", ctx);
        } catch (RuntimeException ignore) {
            // bildirim adımı sahte bağımlılıklarla yarıda kalabilir — açılış satırı kayıttan HEMEN sonra yazılmıştır
        }
        try {
            s.processConfirmedOutage("https://open.example.com/", HTTP, "HIGH", ctx);
        } catch (RuntimeException ignore) {
            // aynı
        }
        List<Invocation> calls = alertCalls(ActivityLogService.ACTION_ALERT_TRIGGERED);
        assertThat(calls).hasSize(1);
        assertThat((Long) calls.get(0).getArgument(3)).isEqualTo(31L);
        assertThat((String) calls.get(0).getArgument(2)).isEqualTo("HIGH");
    }

    // ── İnceleme düzeltmeleri (2026-11-05) ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("D6: geri alınan terfi (alıcı sorgusu düştü) ALERT_ESCALATED yazmaz; sonraki turun başarılı terfisi TEK satır")
    void revertedEscalationWritesNoRow() {
        when(maintenance.isUnderMaintenance(anyString())).thenReturn(false);
        String domain = "https://esc.example.com/";
        AlertEvent a = open(domain, HTTP);   // HIGH
        Map<String, Object> ctx = new java.util.LinkedHashMap<>(Map.of("monitor_id", 31, "team_id", 7, "standalone", true));
        EscalationContactRepository failing = mock(EscalationContactRepository.class, inv -> {
            throw new IllegalStateException("havuz dolu");
        });
        EscalationService broken = pod(failing);
        assertThatThrownBy(() -> broken.processConfirmedOutage(domain, HTTP, "CRITICAL", ctx))
                .isInstanceOf(IllegalStateException.class);
        assertThat(alertCalls(ActivityLogService.ACTION_ALERT_ESCALATED)).isEmpty();
        assertThat(realRepo.findById(a.getId()).orElseThrow().getAlertLevel()).as("terfi geri alındı").isEqualTo("HIGH");

        EscalationService ok = pod();
        ok.processConfirmedOutage(domain, HTTP, "CRITICAL", ctx);
        List<Invocation> calls = alertCalls(ActivityLogService.ACTION_ALERT_ESCALATED);
        assertThat(calls).hasSize(1);
        assertThat((String) calls.get(0).getArgument(2)).isEqualTo("CRITICAL");
        assertThat((String) calls.get(0).getArgument(5)).isEqualTo("HIGH → CRITICAL");
        assertThat(((ActivityLogService.AlertInfo) calls.get(0).getArgument(6)).fromLevel()).isEqualTo("HIGH");
        assertThat(realRepo.findById(a.getId()).orElseThrow().getAlertLevel()).isEqualTo("CRITICAL");
    }

    @Test
    @DisplayName("D7 / D1: fırtınaya bağlanan alarmın ALERT_TRIGGERED satırı fırtına kararından SONRA yazılır ve fırtına kimliğini taşır")
    void stormMemberTriggeredCarriesStormId() {
        when(maintenance.isUnderMaintenance(anyString())).thenReturn(false);
        when(storm.evaluate(any(), any())).thenAnswer(inv -> {
            ((AlertEvent) inv.getArgument(0)).setStormId(55L);
            return StormService.StormAction.SUPPRESSED;
        });
        EscalationService s = pod();
        Map<String, Object> ctx = new java.util.LinkedHashMap<>(Map.of("monitor_id", 31, "team_id", 7, "standalone", true));
        try {
            s.processConfirmedOutage("https://storm.example.com/", HTTP, "HIGH", ctx);
        } catch (RuntimeException ignore) {
            // fırtına devri adımı sahte bağımlılıklarla yarıda kalabilir — açılış satırı yine yazılır (finally)
        }
        List<Invocation> calls = alertCalls(ActivityLogService.ACTION_ALERT_TRIGGERED);
        assertThat(calls).hasSize(1);
        assertThat(((AlertEvent) calls.get(0).getArgument(0)).getStormId()).isEqualTo(55L);
        org.mockito.InOrder order = inOrder(storm, activity);
        order.verify(storm).evaluate(any(), any());
        order.verify(activity).recordAlert(any(), eq(ActivityLogService.ACTION_ALERT_TRIGGERED), any(), any(), any(), any(), any());
    }
}
