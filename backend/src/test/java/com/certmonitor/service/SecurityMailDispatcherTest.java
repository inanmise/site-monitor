package com.certmonitor.service;

import com.certmonitor.model.LoginAnomalyIncident;
import com.certmonitor.model.NotificationLog;
import com.certmonitor.repository.LoginAnomalyIncidentRepository;
import com.certmonitor.repository.NotificationLogRepository;
import com.certmonitor.service.FailedLoginAnomalyService.AnomalyReport;
import com.certmonitor.service.FailedLoginAnomalyService.RuleHit;
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

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Async dispatcher: SMTP down'da hayatta kalır (fırlatmaz), notiflog yazar, başarıda lastAlertAt damgalar. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SecurityMailDispatcherTest {

    @Mock EmailNotificationService emailService;
    @Mock LoginAnomalyIncidentRepository incidentRepo;
    @Mock NotificationLogRepository notificationLogRepo;
    @Mock AuditService auditService;
    @InjectMocks SecurityMailDispatcher dispatcher;

    private AnomalyReport report() {
        return new AnomalyReport("2026-07-28T10:00:00", "2026-07-28T10:10:00", 10, 20, 0,
                List.of(new RuleHit("GLOBAL_VOLUME", 20, 20, "")), List.of(), List.of(), List.of(), List.of(), Map.of());
    }

    @BeforeEach
    void setup() {
        when(emailService.senderAddress()).thenReturn("from@x");
    }

    @Test
    @DisplayName("SMTP erişilemez (email fırlatır) → dispatch fırlatmaz, FAILED notiflog + hata sayacı artar")
    void dispatchAlert_emailThrows_survives() {
        when(emailService.sendSystemAdminLoginAnomalyAlert(any(), any(), any()))
                .thenThrow(new RuntimeException("connection refused"));

        // Fırlatmamalı (scheduler yaşamalı)
        dispatcher.dispatchAlert(new String[]{"ops@x"}, report(), "INITIAL", 1L);

        ArgumentCaptor<NotificationLog> cap = ArgumentCaptor.forClass(NotificationLog.class);
        verify(notificationLogRepo).save(cap.capture());
        assertThat(cap.getValue().getEmailStatus()).startsWith("FAILED");
        assertThat(cap.getValue().getTrigger()).isEqualTo("LOGIN_ANOMALY_INITIAL");
        assertThat(dispatcher.consecutiveFailures()).isEqualTo(1);
        // başarısızlıkta lastAlertAt damgalanmaz
        verify(incidentRepo, never()).save(any());
    }

    @Test
    @DisplayName("gönderim başarılı → SENT notiflog, lastAlertAt damgalanır, hata sayacı sıfırlanır")
    void dispatchAlert_sent_stampsAndResets() {
        when(emailService.sendSystemAdminLoginAnomalyAlert(any(), any(), any())).thenReturn("SENT");
        LoginAnomalyIncident inc = new LoginAnomalyIncident();
        inc.setId(1L);
        when(incidentRepo.findById(1L)).thenReturn(Optional.of(inc));

        dispatcher.dispatchAlert(new String[]{"ops@x"}, report(), "ESCALATION", 1L);

        ArgumentCaptor<NotificationLog> cap = ArgumentCaptor.forClass(NotificationLog.class);
        verify(notificationLogRepo).save(cap.capture());
        assertThat(cap.getValue().getEmailStatus()).isEqualTo("SENT");
        assertThat(cap.getValue().getTrigger()).isEqualTo("LOGIN_ANOMALY_ESCALATION");
        verify(incidentRepo).save(argThat(i -> i.getLastAlertAt() != null));
        assertThat(dispatcher.consecutiveFailures()).isZero();
        verify(auditService).recordSystemEvent(eq("LOGIN_ANOMALY_ALERT"), any(), any(), any());
    }

    @Test
    @DisplayName("resolved gönderimi → RESOLUTION notiflog + audit")
    void dispatchResolved_writesLog() {
        when(emailService.sendSystemAdminLoginAnomalyResolved(any(), any(), any(), anyLong())).thenReturn("SENT");

        dispatcher.dispatchResolved(new String[]{"ops@x"}, "2026-07-28T09:00:00", "2026-07-28T10:30:00", 47L, 1L);

        ArgumentCaptor<NotificationLog> cap = ArgumentCaptor.forClass(NotificationLog.class);
        verify(notificationLogRepo).save(cap.capture());
        assertThat(cap.getValue().getTrigger()).isEqualTo("LOGIN_ANOMALY_RESOLUTION");
        verify(auditService).recordSystemEvent(eq("LOGIN_ANOMALY_RESOLVED"), any(), any(), any());
    }
}
