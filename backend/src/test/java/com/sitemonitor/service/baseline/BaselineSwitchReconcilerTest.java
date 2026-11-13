package com.sitemonitor.service.baseline;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.HttpMonitor;
import com.sitemonitor.model.MonitorBaselineConfig;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.HttpMonitorRepository;
import com.sitemonitor.repository.MonitorBaselineConfigRepository;
import com.sitemonitor.service.AppSettingsChangedEvent;
import com.sitemonitor.service.EscalationService;
import com.sitemonitor.service.SilentAlarmCloser;
import com.sitemonitor.service.schedule.ClusterLockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Dinamik taban ANA ANAHTARI kapanınca askıda kalan HTTP bant alarmlarının sessiz kapanışı (hata taraması 2026-11-13 #4).
 * Sabit yavaşlığı KAPALI HTTP izlemesinde sabit yol hiç kalem üretmez — anahtar kapanınca açık bant alarmı hiçbir yolla
 * kapanmıyordu. Sabit eşik açıksa sabit yol karar verir (dokunulmaz); başka izlemenin aynı URL'deki olayı da dokunulmaz.
 */
class BaselineSwitchReconcilerTest {

    BaselineService baseline;
    MonitorBaselineConfigRepository configRepo;
    HttpMonitorRepository httpRepo;
    AlertEventRepository alerts;
    SilentAlarmCloser closer;
    BaselineSwitchReconciler r;

    static MonitorBaselineConfig cfg(String type, long id) {
        MonitorBaselineConfig c = new MonitorBaselineConfig();
        c.setMonitorType(type);
        c.setMonitorId(id);
        c.setEnabled(true);
        return c;
    }

    static HttpMonitor http(long id, String url, boolean fixedSlow) {
        HttpMonitor m = new HttpMonitor();
        m.setId(id);
        m.setUrl(url);
        m.setTeamId(4L);
        m.setSlowResponseEnabled(fixedSlow);
        return m;
    }

    static AlertEvent open(long id, String url, long monitorId) {
        AlertEvent e = new AlertEvent();
        e.setId(id);
        e.setDomain(url);
        e.setAlertType(EscalationService.TYPE_HTTP_SLOW);
        e.setTeamId(4L);
        e.setResolved(false);
        e.setContextJson("{\"monitor_id\":" + monitorId + ",\"team_id\":4,\"baseline\":true}");
        return e;
    }

    @BeforeEach
    void setup() {
        baseline = mock(BaselineService.class);
        configRepo = mock(MonitorBaselineConfigRepository.class);
        httpRepo = mock(HttpMonitorRepository.class);
        alerts = mock(AlertEventRepository.class);
        closer = mock(SilentAlarmCloser.class);
        r = new BaselineSwitchReconciler(baseline, configRepo, httpRepo, alerts, closer);
        r.executor = Runnable::run;   // eşzamanlı
        r.retryDelayMs = 0;
        when(closer.closeIfOpen(any(), anyString())).thenReturn(true);
        when(configRepo.findByEnabledTrue()).thenReturn(List.of(cfg(BaselineTypes.HTTP, 1L), cfg(BaselineTypes.HTTP, 2L),
                cfg(BaselineTypes.PING, 3L)));
        when(httpRepo.findById(1L)).thenReturn(Optional.of(http(1L, "https://a.example.com/", false)));
        when(httpRepo.findById(2L)).thenReturn(Optional.of(http(2L, "https://b.example.com/", true)));
        AlertEvent mine = open(10L, "https://a.example.com/", 1L);
        AlertEvent other = open(11L, "https://a.example.com/", 99L);   // aynı URL, BAŞKA izlemenin olayı
        when(alerts.findOpenAlerts("https://a.example.com/", EscalationService.TYPE_HTTP_SLOW)).thenReturn(List.of(mine, other));
        when(alerts.findOpenAlerts("https://b.example.com/", EscalationService.TYPE_HTTP_SLOW)).thenReturn(List.of(open(12L,
                "https://b.example.com/", 2L)));
    }

    @Test
    @DisplayName("anahtar kapandı → sabit yavaşlığı kapalı HTTP izlemesinin açık bant alarmı sessizce kapanır; sabit eşik açık ve başka izlemeninki kalır")
    void switchOffClosesHangingHttpBandAlarm() {
        when(baseline.switchOn()).thenReturn(false);
        r.onSettingsChanged(new AppSettingsChangedEvent(Set.of(BaselineService.KEY_ENABLED), "save"));
        org.mockito.ArgumentCaptor<AlertEvent> closed = org.mockito.ArgumentCaptor.forClass(AlertEvent.class);
        verify(closer, times(1)).closeIfOpen(closed.capture(), eq(BaselineSwitchReconciler.CLOSED_BY));
        assertThat(closed.getValue().getId()).as("yalnız bu izlemenin (monitor_id 1) olayı").isEqualTo(10L);
        verify(alerts, never()).findOpenAlerts(eq("https://b.example.com/"), anyString());   // sabit eşik açık → dokunulmaz
    }

    @Test
    @DisplayName("anahtar AÇIK → hiçbir sorgu / kapanış yok")
    void switchOnDoesNothing() {
        when(baseline.switchOn()).thenReturn(true);
        r.onSettingsChanged(new AppSettingsChangedEvent(Set.of(BaselineService.KEY_ENABLED), "save"));
        verify(configRepo, never()).findByEnabledTrue();
        verify(closer, never()).closeIfOpen(any(), anyString());
    }

    @Test
    @DisplayName("ilgisiz ayar kaydı → hiçbir şey; tazelemede yalnız açık → kapalı geçişi (bir kez) koşar")
    void onlyRelevantTransitions() {
        when(baseline.switchOn()).thenReturn(false);
        r.onSettingsChanged(new AppSettingsChangedEvent(Set.of("site.monitor.other"), "save"));
        verify(configRepo, never()).findByEnabledTrue();
        r.onSettingsChanged(new AppSettingsChangedEvent(Set.of(), "refresh"));   // ilk gözlem (önceki bilinmiyor) → koşar
        r.onSettingsChanged(new AppSettingsChangedEvent(Set.of(), "refresh"));   // hâlâ kapalı → yeniden sorgulamaz
        verify(configRepo, times(1)).findByEnabledTrue();
    }

    @Test
    @DisplayName("kilit başka pod'da → kayıt yolu sınırlı yeniden dener; alınınca kapatır")
    void lockBusyRetries() {
        when(baseline.switchOn()).thenReturn(false);
        ClusterLockService locks = mock(ClusterLockService.class);
        ClusterLockService.Hold hold = mock(ClusterLockService.Hold.class);
        when(locks.tryAcquire(eq(BaselineSwitchReconciler.LOCK), any(), any()))
                .thenReturn(Optional.empty()).thenReturn(Optional.of(hold));
        r.setClusterLockService(locks);
        r.onSettingsChanged(new AppSettingsChangedEvent(Set.of(BaselineService.KEY_ENABLED), "save"));
        verify(locks, times(2)).tryAcquire(eq(BaselineSwitchReconciler.LOCK), any(), any());
        verify(closer, times(1)).closeIfOpen(any(), eq(BaselineSwitchReconciler.CLOSED_BY));
        verify(locks).release(hold);
    }

    @Test
    @DisplayName("açılışta anahtar kapalıysa bir kez uzlaştırır (kayıt eski pod'da / pod kapalıyken yapıldıysa)")
    void readyReconcilesWhenOff() {
        when(baseline.switchOn()).thenReturn(false);
        r.onReady();
        verify(closer, times(1)).closeIfOpen(any(), eq(BaselineSwitchReconciler.CLOSED_BY));
    }
}
