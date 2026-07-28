package com.certmonitor.service;

import com.certmonitor.model.ActivityLog;
import com.certmonitor.repository.ActivityLogRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.*;

/**
 * {@link ActivityLogService} — checker Map'ini doğru result_status/summary/team_id'ye eşler,
 * ve BEST-EFFORT: repo.save patlarsa yutup sessizce döner (kontrol akışı kırılmaz).
 */
@ExtendWith(MockitoExtension.class)
class ActivityLogServiceTest {

    @Mock ActivityLogRepository repo;
    @InjectMocks ActivityLogService svc;

    private ActivityLog captureSaved() {
        ArgumentCaptor<ActivityLog> cap = ArgumentCaptor.forClass(ActivityLog.class);
        verify(repo).save(cap.capture());
        return cap.getValue();
    }

    @Test
    @DisplayName("recordCheck HTTP: ok+200+340ms → SUCCESS, özet '200 · 340ms', teamId/type/action doğru")
    void recordCheck_http_mapsSuccess() {
        Map<String, Object> r = new HashMap<>(Map.of("ok", true, "http_status", 200, "response_ms", 340));
        svc.recordCheck(ActivityLogService.HTTP, 7L, "web", "https://x.com", 5L, false, "scheduler", r);

        ActivityLog a = captureSaved();
        assertThat(a.getMonitorType()).isEqualTo("HTTP");
        assertThat(a.getAction()).isEqualTo("SCHEDULED_CHECK");
        assertThat(a.getResultStatus()).isEqualTo("SUCCESS");
        assertThat(a.getResultSummary()).contains("200").contains("340");
        assertThat(a.getTeamId()).isEqualTo(5L);
        assertThat(a.getResponseMs()).isEqualTo(340L);
        assertThat(a.getActivityTime()).isNotBlank();
    }

    @Test
    @DisplayName("recordCheck CERT: status=warning + 12 gün → WARNING + daysRemaining=12")
    void recordCheck_certWarning_mapsWarning() {
        Map<String, Object> r = new HashMap<>(Map.of("status", "warning", "days_remaining", 12));
        svc.recordCheck(ActivityLogService.CERT, null, "a.com", "a.com", 3L, false, "scheduler", r);

        ActivityLog a = captureSaved();
        assertThat(a.getResultStatus()).isEqualTo("WARNING");
        assertThat(a.getDaysRemaining()).isEqualTo(12);
    }

    @Test
    @DisplayName("recordCheck PING: na=true (ICMP yok) → UNKNOWN")
    void recordCheck_pingNa_unknown() {
        Map<String, Object> r = new HashMap<>(Map.of("up", false, "na", true, "error", "ICMP yok"));
        svc.recordCheck(ActivityLogService.PING, 1L, "h", "host", 2L, false, "scheduler", r);
        assertThat(captureSaved().getResultStatus()).isEqualTo("UNKNOWN");
    }

    @Test
    @DisplayName("recordCheck PORT: open=false + timeout hata sınıfı → TIMEOUT")
    void recordCheck_portTimeout_mapsTimeout() {
        Map<String, Object> r = new HashMap<>(Map.of("open", false, "error", "timed out", "error_class", "TIMEOUT"));
        svc.recordCheck(ActivityLogService.PORT, 1L, "p", "h:443", 2L, false, "scheduler", r);
        assertThat(captureSaved().getResultStatus()).isEqualTo("TIMEOUT");
    }

    @Test
    @DisplayName("recordCheck manual=true → MANUAL_CHECK aksiyonu")
    void recordCheck_manual_setsManualAction() {
        svc.recordCheck(ActivityLogService.HTTP, 1L, "web", "u", 1L, true, "alice",
                new HashMap<>(Map.of("ok", true, "http_status", 200)));
        ActivityLog a = captureSaved();
        assertThat(a.getAction()).isEqualTo("MANUAL_CHECK");
        assertThat(a.getActor()).isEqualTo("alice");
    }

    @Test
    @DisplayName("recordLifecycle: CREATED kaydı SUCCESS durumuyla yazılır")
    void recordLifecycle_created() {
        svc.recordLifecycle(ActivityLogService.DNS, 9L, "dns-mon", "example.com A", 4L, "CREATED", "bob");
        ActivityLog a = captureSaved();
        assertThat(a.getAction()).isEqualTo("CREATED");
        assertThat(a.getResultStatus()).isEqualTo("SUCCESS");
        assertThat(a.getActor()).isEqualTo("bob");
        assertThat(a.getTeamId()).isEqualTo(4L);
    }

    @Test
    @DisplayName("best-effort: repo.save exception fırlatınca recordCheck YUTAR (kontrol akışı kırılmaz)")
    void recordCheck_repoThrows_swallowed() {
        when(repo.save(any())).thenThrow(new RuntimeException("db down"));
        assertThatCode(() -> svc.recordCheck(ActivityLogService.CERT, null, "d", "d", 1L, false, "scheduler",
                new HashMap<>(Map.of("status", "valid", "days_remaining", 42)))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("best-effort: recordLifecycle de exception yutar")
    void recordLifecycle_repoThrows_swallowed() {
        when(repo.save(any())).thenThrow(new RuntimeException("db down"));
        assertThatCode(() -> svc.recordLifecycle(ActivityLogService.PORT, 1L, "p", "h:1", 1L, "DELETED", "x"))
                .doesNotThrowAnyException();
    }
}
