package com.sitemonitor.service;

import com.sitemonitor.model.ScriptedCheck;
import com.sitemonitor.model.ScriptedMonitor;
import com.sitemonitor.repository.ScriptedCheckRepository;
import com.sitemonitor.repository.ScriptedMonitorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ScriptedAnomalyGuard} — koşum sonrası anomali tespiti ve otomatik devre dışı bırakma (L3).
 *
 * <p>Buradaki en kritik testler NEGATİF olanlar: guard yanlışlıkla kapatırsa kullanıcının izlemesi
 * sessizce durur ve kimse bakmıyorsa arıza görünmez hâle gelir — yani yanlış kapatma, kaçırılan
 * anomaliden daha tehlikelidir.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ScriptedAnomalyGuardTest {

    @Mock ScriptedMonitorRepository monitorRepo;
    @Mock ScriptedCheckRepository checkRepo;
    @Mock ScriptedCheckerService scriptedChecker;
    @Mock ActivityLogService activityLog;
    @Mock EscalationService escalationService;
    @Mock EmailNotificationService emailService;
    @Mock UserPushService userPushService;
    @Mock AppSettingsService appSettings;

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

        when(appSettings.getBoolean(eq("site.monitor.scripted.anomaly.enabled"), anyBoolean())).thenReturn(true);
        when(appSettings.getInt(eq("site.monitor.scripted.anomaly.timeout-streak"), anyInt())).thenReturn(5);
        when(scriptedChecker.maxRequestsPerRun()).thenReturn(200);
        when(escalationService.teamEmailsForMonitor(anyLong(), any())).thenReturn(List.of("takim@example.com"));
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

    // ── Ağır ihlal: tek koşumda kapatır ─────────────────────────────────────────────────────

    @Test
    @DisplayName("istek tavanı aşılırsa TEK koşumda kapatır — atak imzasını bir tur daha bekletmeyiz")
    void requestCapBreachDisablesImmediately() {
        String reason = guard.evaluate(monitor, result("PASS", 5000L));

        assertThat(reason).contains("Anomali").contains("5000").contains("200");
        ArgumentCaptor<ScriptedMonitor> saved = ArgumentCaptor.forClass(ScriptedMonitor.class);
        verify(monitorRepo).save(saved.capture());
        assertThat(saved.getValue().getActive()).isFalse();
        assertThat(saved.getValue().getDisabledReason()).isEqualTo(reason);
        assertThat(saved.getValue().getDisabledAt()).isNotBlank();
    }

    @Test
    @DisplayName("kapatma AÇIK ALARMI da kapatır — kapatılan izleme sweep üretmez, alarm sonsuza asılı kalırdı")
    void disablingResolvesOpenAlerts() {
        guard.evaluate(monitor, result("PASS", 5000L));
        verify(escalationService).resolveOpenAlertsSilently(eq("Ödeme akışı"), any(), eq(ScriptedAnomalyGuard.ACTOR));
    }

    @Test
    @DisplayName("sahibine bildirim gider (standart alarm şablonu — elle HTML yok)")
    void ownersAreNotified() {
        guard.evaluate(monitor, result("PASS", 5000L));
        verify(emailService).sendAlert(any(String[].class), anyString(), anyString(),
                anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("notify_email kapalıysa e-posta gönderilmez ama izleme YİNE kapatılır")
    void notifyDisabledStillDisablesMonitor() {
        monitor.setNotifyEmail(false);
        assertThat(guard.evaluate(monitor, result("PASS", 5000L))).isNotNull();
        verify(monitorRepo).save(any());
        verify(emailService, never()).sendAlert(any(String[].class), anyString(), anyString(),
                anyString(), anyString(), anyString(), any(), any());
    }

    // ── Ardışık zaman aşımı ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("5 ardışık zaman aşımı kapatır")
    void fullTimeoutStreakDisables() {
        when(checkRepo.findRecentByMonitorId(7L, 5))
                .thenReturn(checks("TIMEOUT", "TIMEOUT", "TIMEOUT", "TIMEOUT", "TIMEOUT"));

        String reason = guard.evaluate(monitor, result("TIMEOUT", 1L));

        assertThat(reason).contains("süre aşımı");
        verify(monitorRepo).save(any());
    }

    @Test
    @DisplayName("seri kırıksa kapatmaz — geçici ağ dalgalanması izlemeyi durdurmamalı")
    void brokenStreakDoesNotDisable() {
        when(checkRepo.findRecentByMonitorId(7L, 5))
                .thenReturn(checks("TIMEOUT", "TIMEOUT", "PASS", "TIMEOUT", "TIMEOUT"));

        assertThat(guard.evaluate(monitor, result("TIMEOUT", 1L))).isNull();
        verify(monitorRepo, never()).save(any());
    }

    @Test
    @DisplayName("pencere DOLMADAN kapatmaz — 3 kaydı olan yeni monitör 5'lik eşiği geçmiş sayılmaz")
    void partialHistoryDoesNotDisable() {
        when(checkRepo.findRecentByMonitorId(7L, 5)).thenReturn(checks("TIMEOUT", "TIMEOUT", "TIMEOUT"));

        assertThat(guard.evaluate(monitor, result("TIMEOUT", 1L))).isNull();
        verify(monitorRepo, never()).save(any());
    }

    @Test
    @DisplayName("son koşum zaman aşımı DEĞİLSE geçmiş hiç sorgulanmaz (her koşumda fazladan sorgu yok)")
    void nonTimeoutRunSkipsHistoryQuery() {
        assertThat(guard.evaluate(monitor, result("PASS", 5L))).isNull();
        verify(checkRepo, never()).findRecentByMonitorId(anyLong(), anyInt());
    }

    // ── Kapatmama garantileri ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("temiz koşum hiçbir şey yapmaz")
    void cleanRunDoesNothing() {
        assertThat(guard.evaluate(monitor, result("PASS", 12L))).isNull();
        verify(monitorRepo, never()).save(any());
    }

    @Test
    @DisplayName("ZATEN kapalı izleme yeniden kapatılmaz — aynı e-posta her manuel tetikte tekrar giderdi")
    void alreadyDisabledIsNotReprocessed() {
        monitor.setActive(false);
        assertThat(guard.evaluate(monitor, result("PASS", 9999L))).isNull();
        verify(monitorRepo, never()).save(any());
    }

    @Test
    @DisplayName("guard kapalıyken hiçbir şey yapmaz (acil kaçış kapısı)")
    void disabledGuardIsInert() {
        when(appSettings.getBoolean(eq("site.monitor.scripted.anomaly.enabled"), anyBoolean())).thenReturn(false);
        assertThat(guard.evaluate(monitor, result("PASS", 9999L))).isNull();
        verify(monitorRepo, never()).save(any());
    }

    @Test
    @DisplayName("istek tavanı 0 ise o tetik kapalıdır (sınırsız)")
    void zeroCapDisablesTheTrigger() {
        when(scriptedChecker.maxRequestsPerRun()).thenReturn(0);
        assertThat(guard.evaluate(monitor, result("PASS", 999999L))).isNull();
    }

    @Test
    @DisplayName("http_reqs ölçülemediyse (özet okunamadı) kapatma YAPILMAZ — bilgisizlik kanıt değildir")
    void unknownRequestCountNeverDisables() {
        assertThat(guard.evaluate(monitor, result("PASS", null))).isNull();
        verify(monitorRepo, never()).save(any());
    }

    @Test
    @DisplayName("bildirim/alarm adımı patlasa bile kapatma tamamlanır ve çağıran istisna GÖRMEZ")
    void notificationFailureDoesNotBreakDisabling() {
        when(escalationService.teamEmailsForMonitor(anyLong(), any())).thenThrow(new IllegalStateException("smtp yok"));

        assertThat(guard.evaluate(monitor, result("PASS", 5000L))).isNotNull();
        verify(monitorRepo).save(any());
    }

    @Test
    @DisplayName("aktivite akışına PAUSED olarak yazılır — 'kim kapattı' sorusu denetimde cevaplanır")
    void lifecycleIsRecorded() {
        guard.evaluate(monitor, result("PASS", 5000L));
        verify(activityLog).recordLifecycle(eq(ActivityLogService.SCRIPTED), eq(7L), eq("Ödeme akışı"),
                eq("Ödeme akışı"), eq(5L), eq("PAUSED"), eq(ScriptedAnomalyGuard.ACTOR));
    }

    @Test
    @DisplayName("null girdilerde çökmez")
    void nullsAreSafe() {
        assertThat(guard.evaluate(null, result("PASS", 1L))).isNull();
        assertThat(guard.evaluate(monitor, null)).isNull();
    }

    @Test
    @DisplayName("e-posta gövdesi sebebi ve izleme adını TAŞIR — nöbetçi sebebi maili açınca görsün")
    void mailBodyCarriesReason() {
        guard.evaluate(monitor, result("PASS", 5000L));
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendAlert(any(String[].class), anyString(), body.capture(),
                anyString(), anyString(), anyString(), any(), any());
        assertThat(body.getValue()).contains("5000").contains("Ödeme akışı").contains("DEVRE DIŞI");
    }

    @Test
    @DisplayName("alıcı yoksa e-posta denenmez ama kapatma yine yapılır")
    void noRecipientsStillDisables() {
        when(escalationService.teamEmailsForMonitor(anyLong(), any())).thenReturn(List.of());
        assertThat(guard.evaluate(monitor, result("PASS", 5000L))).isNotNull();
        verify(emailService, never()).sendAlert(any(String[].class), anyString(), anyString(),
                anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("PUSH da gider — disable() önce 'DÜZELDİ' push'u atıyor, 'devre dışı' bildirimi olmazsa nöbetçi TERS bilgi alır")
    void ownersAreNotifiedOnPushToo() {
        // K1 (2026-09-23): resolveOpenAlertsSilently -> enqueueResolvePushQuietly telefona "normale
        // döndü" yolluyor. Push halkası bağlı değilken yalnız push kullanan kişi, OTOMATİK
        // KAPATILMIŞ bir izleme için "düzeldi" görüyor ve izlemenin durduğunu hiç öğrenmiyordu.
        guard.evaluate(monitor, result("PASS", 5000L));
        verify(userPushService).enqueueTeamNotice(eq(5L), eq("SCRIPTED_DISABLED"), eq("CRITICAL"),
                eq("SCRIPTED"), eq("Ödeme akışı"), anyString(), anyString());
    }

    @Test
    @DisplayName("notify_email kapalı olsa bile PUSH gider — kanal bağımsızlığı")
    void pushGoesEvenWhenEmailDisabled() {
        monitor.setNotifyEmail(false);
        guard.evaluate(monitor, result("PASS", 5000L));
        verify(emailService, never()).sendAlert(any(String[].class), anyString(), anyString(),
                anyString(), anyString(), anyString(), any(), any());
        verify(userPushService).enqueueTeamNotice(anyLong(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("alıcı, monitörün KENDİ bildirim grubundan çözülür — grup yok sayılırsa bildirim başka adrese gider")
    void recipientsComeFromTheMonitorsOwnGroup() {
        monitor.setNotificationGroupId(42L);
        guard.evaluate(monitor, result("PASS", 5000L));
        verify(escalationService).teamEmailsForMonitor(5L, 42L);
    }

    @Test
    @DisplayName("Map.of() bağlamı ile çağrılır — sendAlert imzası cert bağlamı bekliyor")
    void contextIsEmptyMap() {
        guard.evaluate(monitor, result("PASS", 5000L));
        ArgumentCaptor<Map<String, Object>> ctx = ArgumentCaptor.captor();
        verify(emailService).sendAlert(any(String[].class), anyString(), anyString(),
                anyString(), anyString(), anyString(), any(), ctx.capture());
        assertThat(ctx.getValue()).isEmpty();
    }
}
