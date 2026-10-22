package com.sitemonitor.service;

import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.DnsMonitorRepository;
import com.sitemonitor.repository.DnsRecordRepository;
import com.sitemonitor.repository.NetworkOutageEventRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * İnceleme J-2 (2026-10-23, çok pod R2a · D2): teyit zinciri sürerken türün alarm bildirimleri KAPATILIRSA zincirin son adımı
 * yeni alarm AÇMAZ ({@code finishConfirmed} anahtarı yeniden okur). Eskiden kaydı yapan pod'un uzlaştırma turundan sonra biten
 * lider zinciri alarm açıp INITIAL gönderiyordu. Anahtar açık kalırsa davranış aynı.
 */
class DisabledTypeConfirmationTest {

    private static final String PORT = EscalationService.TYPE_PORT_DOWN;

    private MonitoringOutageService mos(EscalationService esc, AtomicBoolean portAlerts, MonitoringMultiPodKit.Manual chain) {
        AppSettingsService settings = mock(AppSettingsService.class);
        when(settings.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> i.getArgument(1));
        when(settings.getBoolean(eq("site.monitor.port.alert-enabled"), anyBoolean())).thenAnswer(i -> portAlerts.get());
        when(settings.getInt(anyString(), anyInt())).thenAnswer(i -> i.getArgument(1));
        AlertEventRepository alerts = mock(AlertEventRepository.class);
        when(alerts.findOpenByDomainIn(anyCollection())).thenReturn(List.of());
        MonitoringOutageService s = new MonitoringOutageService(alerts, esc, mock(JdbcTemplate.class), mock(DnsRecordRepository.class),
                mock(DnsMonitorRepository.class), settings, mock(NetworkOutageEventRepository.class));
        for (String f : List.of("uptimeAlertEnabled", "portAlertEnabled", "dnsAlertEnabled", "keywordAlertEnabled", "pingAlertEnabled"))
            ReflectionTestUtils.setField(s, f, true);
        ReflectionTestUtils.setField(s, "confirmAttempts", 2);
        ReflectionTestUtils.setField(s, "confirmDelayMs", 1L);
        ReflectionTestUtils.setField(s, "bulkRateThreshold", 0.50);
        ReflectionTestUtils.setField(s, "bulkMinErrors", 3);
        ReflectionTestUtils.setField(s, "confirmExecutor", chain);
        ReflectionTestUtils.setField(s, "recoveryExecutor", MonitoringMultiPodKit.immediate());
        return s;
    }

    private static MonitoringOutageService.SweepItem down() {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("monitor_id", 1L);
        ctx.put("monitor_confirm_attempts", 2);
        ctx.put("monitor_confirm_interval_ms", 1L);
        ctx.put("team_id", 7L);
        ctx.put("standalone", true);
        ctx.put("alert_level", "WARNING");
        Map<String, Object> st = new HashMap<>();
        st.put("status", "down");
        st.put("error", "Connect timed out");
        return new MonitoringOutageService.SweepItem(PORT, "app.example.com", "443/TCP", false, "Connect timed out", ctx, () -> st);
    }

    @Test
    @DisplayName("zincir sürerken tür kapatıldı → son adım alarm açmaz; açık kalırsa açar (kontrol)")
    void disabledMidChain_opensNoAlarm() {
        for (boolean disableMidChain : new boolean[]{true, false}) {
            EscalationService esc = mock(EscalationService.class);
            AtomicBoolean portAlerts = new AtomicBoolean(true);
            MonitoringMultiPodKit.Manual chain = new MonitoringMultiPodKit.Manual();
            MonitoringOutageService s = mos(esc, portAlerts, chain);

            s.handleSweepResults(PORT, List.of(down()));
            if (disableMidChain) portAlerts.set(false);   // yönetici kesinti sırasında bildirimleri kapattı
            chain.drain();

            if (disableMidChain) {
                verify(esc, never()).processConfirmedOutage(anyString(), anyString(), anyString(), any());
            } else {
                verify(esc, times(1)).processConfirmedOutage(eq("app.example.com"), eq(PORT), anyString(), any());
            }
        }
    }
}
