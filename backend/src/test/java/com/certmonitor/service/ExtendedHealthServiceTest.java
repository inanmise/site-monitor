package com.certmonitor.service;

import com.certmonitor.model.NotificationLog;
import com.certmonitor.model.SystemHeartbeat;
import com.certmonitor.repository.NotificationLogRepository;
import com.certmonitor.repository.SystemHeartbeatRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ExtendedHealthServiceTest {

    @Mock NotificationLogRepository notificationLogRepo;
    @Mock SystemHeartbeatRepository heartbeatRepo;
    @Mock JdbcTemplate jdbcTemplate;

    private ExtendedHealthService service;

    @BeforeEach
    void setUp() {
        service = new ExtendedHealthService(notificationLogRepo, heartbeatRepo, jdbcTemplate);
    }

    // ── getHeartbeatStatus ────────────────────────────────────────────────────

    @Test
    @DisplayName("no heartbeat records → alarm=true, minutes_since=-1")
    void getHeartbeatStatus_noRecords_alarm() {
        when(heartbeatRepo.findTop5ByOrderByRecordedAtDesc()).thenReturn(List.of());

        Map<String, Object> result = service.getHeartbeatStatus();

        assertThat(result.get("alarm")).isEqualTo(true);
        assertThat(result.get("minutes_since")).isEqualTo(-1);
        assertThat(result.get("last_heartbeat")).isNull();
    }

    @Test
    @DisplayName("recent heartbeat (2 min ago) → alarm=false")
    void getHeartbeatStatus_recentHeartbeat_noAlarm() {
        SystemHeartbeat hb = new SystemHeartbeat(null, LocalDateTime.now(java.time.ZoneOffset.UTC).minusMinutes(2));
        when(heartbeatRepo.findTop5ByOrderByRecordedAtDesc()).thenReturn(List.of(hb));

        Map<String, Object> result = service.getHeartbeatStatus();

        assertThat(result.get("alarm")).isEqualTo(false);
        assertThat((Long) result.get("minutes_since")).isBetween(1L, 3L);
    }

    @Test
    @DisplayName("old heartbeat (20 min ago) → alarm=true, minutes_since>=15")
    void getHeartbeatStatus_oldHeartbeat_alarm() {
        SystemHeartbeat hb = new SystemHeartbeat(null, LocalDateTime.now(java.time.ZoneOffset.UTC).minusMinutes(20));
        when(heartbeatRepo.findTop5ByOrderByRecordedAtDesc()).thenReturn(List.of(hb));

        Map<String, Object> result = service.getHeartbeatStatus();

        assertThat(result.get("alarm")).isEqualTo(true);
        assertThat((Long) result.get("minutes_since")).isGreaterThanOrEqualTo(15L);
    }

    // ── getSmtpStats ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("no records → rate=100, alarm=false")
    void getSmtpStats_noRecords_rate100_noAlarm() {
        when(notificationLogRepo.countAttemptedSince(any())).thenReturn(0L);
        when(notificationLogRepo.countSentSince(any())).thenReturn(0L);
        when(notificationLogRepo.countAllSince(any())).thenReturn(0L);

        Map<String, Object> result = service.getSmtpStats();

        assertThat(result.get("alarm")).isEqualTo(false);
        assertThat((Long) result.get("rate")).isEqualTo(100L);
    }

    @Test
    @DisplayName("all sent → rate=100, alarm=false")
    void getSmtpStats_allSent_rate100() {
        when(notificationLogRepo.countAttemptedSince(any())).thenReturn(10L);
        when(notificationLogRepo.countSentSince(any())).thenReturn(10L);
        when(notificationLogRepo.countAllSince(any())).thenReturn(10L);

        Map<String, Object> result = service.getSmtpStats();

        assertThat(result.get("alarm")).isEqualTo(false);
        assertThat((Long) result.get("rate")).isEqualTo(100L);
    }

    @Test
    @DisplayName("90% sent (1 of 10 failed) → alarm=true (rate < 95%)")
    void getSmtpStats_someFailed_alarm() {
        when(notificationLogRepo.countAttemptedSince(any())).thenReturn(10L);
        when(notificationLogRepo.countSentSince(any())).thenReturn(9L);
        when(notificationLogRepo.countAllSince(any())).thenReturn(10L);

        Map<String, Object> result = service.getSmtpStats();

        assertThat(result.get("alarm")).isEqualTo(true);
    }

    @Test
    @DisplayName("96% sent → alarm=false (within ≥95% tolerance)")
    void getSmtpStats_rate96_noAlarm() {
        when(notificationLogRepo.countAttemptedSince(any())).thenReturn(100L);
        when(notificationLogRepo.countSentSince(any())).thenReturn(96L);
        when(notificationLogRepo.countAllSince(any())).thenReturn(100L);

        Map<String, Object> result = service.getSmtpStats();

        assertThat(result.get("alarm")).isEqualTo(false);
        assertThat((Long) result.get("rate")).isEqualTo(96L);
    }

    @Test
    @DisplayName("94% sent → alarm=true (below 95% threshold)")
    void getSmtpStats_rate94_alarm() {
        when(notificationLogRepo.countAttemptedSince(any())).thenReturn(100L);
        when(notificationLogRepo.countSentSince(any())).thenReturn(94L);
        when(notificationLogRepo.countAllSince(any())).thenReturn(100L);

        Map<String, Object> result = service.getSmtpStats();

        assertThat(result.get("alarm")).isEqualTo(true);
        assertThat((Long) result.get("rate")).isEqualTo(94L);
    }

    @Test
    @DisplayName("SKIPPED excluded from denominator → rate computed correctly")
    void getSmtpStats_skippedExcluded() {
        // attempted=5 (SENT+FAILED only), sent=5 → rate=100 even though total=10
        when(notificationLogRepo.countAttemptedSince(any())).thenReturn(5L);
        when(notificationLogRepo.countSentSince(any())).thenReturn(5L);
        when(notificationLogRepo.countAllSince(any())).thenReturn(10L);

        Map<String, Object> result = service.getSmtpStats();

        assertThat(result.get("alarm")).isEqualTo(false);
        assertThat((Long) result.get("rate")).isEqualTo(100L);
        assertThat(result.get("total")).isEqualTo(10L);
        assertThat(result.get("attempted")).isEqualTo(5L);
    }

    // ── measureDbResponseMs ───────────────────────────────────────────────────

    @Test
    @DisplayName("successful SELECT 1 → returns non-negative value")
    void measureDbResponseMs_success_returnsNonNegative() {
        when(jdbcTemplate.queryForObject("SELECT 1", Integer.class)).thenReturn(1);

        long result = service.measureDbResponseMs();

        assertThat(result).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("jdbcTemplate throws → returns -1")
    void measureDbResponseMs_jdbcThrows_returnsMinusOne() {
        when(jdbcTemplate.queryForObject("SELECT 1", Integer.class))
                .thenThrow(new RuntimeException("connection refused"));

        long result = service.measureDbResponseMs();

        assertThat(result).isEqualTo(-1L);
    }

    // ── getSmtpFailures ───────────────────────────────────────────────────────

    @Test
    @DisplayName("FAILED status → kind=FAILED, error extracted")
    void getSmtpFailures_failedStatus_mapsKindAndError() {
        NotificationLog log = new NotificationLog();
        log.setId(1L);
        log.setAlertEventId(10L);
        log.setEmailStatus("FAILED: conn refused");
        when(notificationLogRepo.findAllSince(any())).thenReturn(List.of(log));

        List<Map<String, Object>> result = service.getSmtpFailures();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).get("kind")).isEqualTo("FAILED");
        assertThat(result.get(0).get("error").toString()).contains("conn refused");
    }

    @Test
    @DisplayName("SKIPPED_DISABLED status → kind=SKIPPED")
    void getSmtpFailures_skippedStatus_hasKindSkipped() {
        NotificationLog log = new NotificationLog();
        log.setId(2L);
        log.setAlertEventId(11L);
        log.setEmailStatus("SKIPPED_DISABLED");
        when(notificationLogRepo.findAllSince(any())).thenReturn(List.of(log));

        List<Map<String, Object>> result = service.getSmtpFailures();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).get("kind")).isEqualTo("SKIPPED");
    }
}
