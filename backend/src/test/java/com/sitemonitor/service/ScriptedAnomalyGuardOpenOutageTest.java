package com.sitemonitor.service;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.ScriptedCheck;
import com.sitemonitor.model.ScriptedMonitor;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.ScriptedCheckRepository;
import com.sitemonitor.repository.ScriptedMonitorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * k6r B2 (2026-10-31): anomali guard'ının zaman aşımı serisi, hedef GERÇEKTEN asılıyken (izlemenin açık {@code SCRIPTED_FAIL}
 * alarmı var) izlemeyi kapatmaz, alarmı kapatmaz ve bildirim göndermez — kesinti sürerken izleme körleşmez, nöbetçiye
 * "DÜZELDİ" gitmez. Alarm okunamazsa da kapatmaz (bilinmeyen = dokunma). Alarm yokken davranış bugünkü gibi; istek tavanı
 * tetikleyicisi değişmez.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ScriptedAnomalyGuardOpenOutageTest {

    @Mock ScriptedMonitorRepository monitorRepo;
    @Mock ScriptedCheckRepository checkRepo;
    @Mock ScriptedCheckerService scriptedChecker;
    @Mock ActivityLogService activityLog;
    @Mock EscalationService escalationService;
    @Mock EmailNotificationService emailService;
    @Mock UserPushService userPushService;
    @Mock AppSettingsService appSettings;
    @Mock AlertEventRepository alertRepo;

    @InjectMocks ScriptedAnomalyGuard guard;

    private ScriptedMonitor monitor;

    @BeforeEach
    void setUp() {
        monitor = new ScriptedMonitor();
        monitor.setId(7L);
        monitor.setName("Ödeme akışı");
        monitor.setTeamId(5L);
        monitor.setActive(true);
        monitor.setNotifyEmail(true);
        monitor.setTimeoutSeconds(60);
        guard.setAlertEventRepository(alertRepo);

        when(appSettings.getBoolean(eq("site.monitor.scripted.anomaly.enabled"), anyBoolean())).thenReturn(true);
        when(appSettings.getInt(eq("site.monitor.scripted.anomaly.timeout-streak"), anyInt())).thenReturn(5);
        when(scriptedChecker.maxRequestsPerRun()).thenReturn(200);
        when(escalationService.teamEmailsForMonitor(anyLong(), any())).thenReturn(List.of("takim@example.com"));
        when(monitorRepo.disableIfActive(anyLong(), anyString(), anyString())).thenReturn(1);
        when(checkRepo.findRecentScheduledByMonitorId(7L, 5))
                .thenReturn(checks("TIMEOUT", "TIMEOUT", "TIMEOUT", "TIMEOUT", "TIMEOUT"));
    }

    private static ScriptedCheckerService.ScriptedResult result(String status, Long httpReqs) {
        var phases = new ScriptedCheckerService.Phases(null, null, null, null, null, null, null, null, null, httpReqs);
        return new ScriptedCheckerService.ScriptedResult(status, "PASS".equals(status), 1000L, 0,
                1, 0, null, null, null, null, null, null, false, phases);
    }

    private static List<ScriptedCheck> checks(String... statuses) {
        List<ScriptedCheck> out = new ArrayList<>();
        for (String s : statuses) {
            ScriptedCheck c = new ScriptedCheck();
            c.setStatus(s);
            out.add(c);
        }
        return out;
    }

    /** Bu izlemenin (bağlamında monitor_id = 7) açık kesinti alarmı. */
    private static AlertEvent openFail(long monitorId, Long teamId) {
        AlertEvent a = new AlertEvent();
        a.setId(100L + monitorId);
        a.setDomain("Ödeme akışı");
        a.setAlertType(EscalationService.TYPE_SCRIPTED_FAIL);
        a.setResolved(false);
        a.setTeamId(teamId);
        a.setContextJson("{\"monitor_id\":" + monitorId + ",\"team_id\":" + teamId + "}");
        return a;
    }

    @Test
    @DisplayName("açık SCRIPTED_FAIL alarmı + 5 zaman aşımı → izleme AKTİF kalır, alarm açık, bildirim yok")
    void openOutage_timeoutStreakDoesNotDisable() {
        when(alertRepo.findOpenAlerts("Ödeme akışı", EscalationService.TYPE_SCRIPTED_FAIL)).thenReturn(List.of(openFail(7L, 5L)));

        assertThat(guard.evaluate(monitor, result("TIMEOUT", 1L))).isNull();

        verify(monitorRepo, never()).disableIfActive(anyLong(), anyString(), anyString());
        verify(escalationService, never()).resolveOpenAlertsSilently(anyString(), any(), anyString(), any());
        verifyNoInteractions(emailService, userPushService, activityLog);
        assertThat(monitor.getActive()).isTrue();
        assertThat(monitor.getDisabledReason()).isNull();
    }

    @Test
    @DisplayName("alarm yok + 5 zaman aşımı → bugünkü gibi kapatılır (bitmeyen betik koruması sürer)")
    void noAlarm_timeoutStreakStillDisables() {
        when(alertRepo.findOpenAlerts("Ödeme akışı", EscalationService.TYPE_SCRIPTED_FAIL)).thenReturn(List.of());

        String reason = guard.evaluate(monitor, result("TIMEOUT", 1L));

        assertThat(reason).contains("süre aşımı");
        verify(monitorRepo).disableIfActive(eq(7L), eq(reason), anyString());
    }

    @Test
    @DisplayName("alarm okunamadı → kapatılmaz (bilinmeyen = dokunma)")
    void alarmReadFailure_doesNotDisable() {
        when(alertRepo.findOpenAlerts(anyString(), anyString())).thenThrow(new org.springframework.dao.QueryTimeoutException("x"));

        assertThat(guard.evaluate(monitor, result("TIMEOUT", 1L))).isNull();

        verify(monitorRepo, never()).disableIfActive(anyLong(), anyString(), anyString());
        verifyNoInteractions(emailService, userPushService, activityLog);
    }

    @Test
    @DisplayName("aynı adlı BAŞKA takımın senaryosunun açık alarmı bu izlemeyi korumaz (ad yalnız takım içinde tekil)")
    void otherMonitorsAlarm_doesNotProtect() {
        when(alertRepo.findOpenAlerts("Ödeme akışı", EscalationService.TYPE_SCRIPTED_FAIL)).thenReturn(List.of(openFail(99L, 8L)));

        assertThat(guard.evaluate(monitor, result("TIMEOUT", 1L))).contains("süre aşımı");
        verify(monitorRepo).disableIfActive(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("istek tavanı aşımı açık alarm varken de TEK koşumda kapatır (tetikleyici değişmedi)")
    void requestCapBreach_disablesEvenWithOpenAlarm() {
        when(alertRepo.findOpenAlerts(anyString(), anyString())).thenReturn(List.of(openFail(7L, 5L)));

        assertThat(guard.evaluate(monitor, result("PASS", 5000L))).contains("5000");
        verify(monitorRepo).disableIfActive(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("seri dolmadan alarm sorgulanmaz (her zaman aşımında fazladan sorgu yok)")
    void alarmQueriedOnlyWhenStreakIsFull() {
        when(checkRepo.findRecentScheduledByMonitorId(7L, 5)).thenReturn(checks("TIMEOUT", "TIMEOUT", "PASS", "TIMEOUT", "TIMEOUT"));

        assertThat(guard.evaluate(monitor, result("TIMEOUT", 1L))).isNull();
        verifyNoInteractions(alertRepo);
    }

    @Test
    @DisplayName("açık alarm her turda yine korur — art arda değerlendirmede de kapatılmaz")
    void openOutage_repeatedEvaluationsStayActive() {
        when(alertRepo.findOpenAlerts("Ödeme akışı", EscalationService.TYPE_SCRIPTED_FAIL)).thenReturn(List.of(openFail(7L, 5L)));
        for (int i = 0; i < 3; i++) assertThat(guard.evaluate(monitor, result("TIMEOUT", 1L))).isNull();
        verify(monitorRepo, never()).disableIfActive(anyLong(), anyString(), anyString());
    }
}
