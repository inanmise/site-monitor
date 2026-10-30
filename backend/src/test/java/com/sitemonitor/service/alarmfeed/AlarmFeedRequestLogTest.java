package com.sitemonitor.service.alarmfeed;

import com.sitemonitor.model.AlarmFeedRequest;
import com.sitemonitor.repository.AlarmFeedCallerRepository;
import com.sitemonitor.repository.AlarmFeedRequestRepository;
import com.sitemonitor.service.AuditService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** Alarm akışı çağrı günlüğü — GERÇEK SQL (H2): gövde tekilleşmesi, çağıran kaydı, denetim kısıtları, hata yutma. */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AlarmFeedRequestLogTest {

    @Autowired AlarmFeedRequestRepository requests;
    @Autowired AlarmFeedCallerRepository callers;
    @Autowired DataSource dataSource;

    JdbcTemplate jdbc;
    AlarmFeedRequestLog log;
    AuditService audit;
    long now = 1_800_000_000_000L;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        log = new AlarmFeedRequestLog(requests, jdbc);
        audit = mock(AuditService.class);
        log.setAuditService(audit);
        log.clock = () -> now;
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM alarm_feed_requests");
        jdbc.update("DELETE FROM alarm_feed_responses");
        jdbc.update("DELETE FROM alarm_feed_callers");
    }

    AlarmFeedRequestLog.Call call(String ip) {
        return new AlarmFeedRequestLog.Call(now - 40, ip, "10.0.0.1", ip + ", 10.0.0.1", "python-requests/2.31",
                "GET", "format=json", "req-1", "pod-a");
    }

    static AlarmFeedRequestLog.Result ok(String body) {
        return new AlarmFeedRequestLog.Result(200, AlarmFeedRequestLog.OUTCOME_OK, body, "application/json", 2, 1, 1, false,
                "json", null);
    }

    @Test
    @DisplayName("Her çağrı bir satır; aynı gövde BİR kez saklanır (gzip) ve birebir geri okunur")
    void bodyStoredOnceByHash() {
        String body = "[{\"CREATEDTIME\":\"2026-10-30 13:55:00\",\"TEXT\":\"(api) Ş ğ ü\"}]";
        log.record(call("172.16.5.5"), ok(body), true, null);
        now += 60_000;
        log.record(call("172.16.5.5"), ok(body), true, null);
        assertThat(requests.count()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alarm_feed_responses", Long.class)).isEqualTo(1L);
        AlarmFeedRequest r = requests.findTopByOrderByRequestedAtDescIdDesc();
        assertThat(r.getResponseHash()).hasSize(64);
        assertThat(r.getDurationMs()).isEqualTo(40L);
        assertThat(r.getForwardedFor()).isEqualTo("172.16.5.5, 10.0.0.1");
        assertThat(log.body(r.getResponseHash())).isEqualTo(body);
        assertThat(log.contentType(r.getResponseHash())).isEqualTo("application/json");
        // Başka bir gövde → ikinci satır
        log.record(call("172.16.5.5"), ok("[]"), true, null);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alarm_feed_responses", Long.class)).isEqualTo(2L);
    }

    @Test
    @DisplayName("Gövde saklama kapalı: özet yazılır, gövde yazılmaz")
    void bodyNotStoredWhenOff() {
        log.record(call("172.16.5.5"), ok("[1]"), false, null);
        AlarmFeedRequest r = requests.findTopByOrderByRequestedAtDescIdDesc();
        assertThat(r.getResponseHash()).isNotNull();
        assertThat(log.body(r.getResponseHash())).isNull();
    }

    @Test
    @DisplayName("Yeni IP bir kez denetlenir (ALARM_FEED_NEW_CALLER); sonraki çağrılar sayacı artırır")
    void newCallerAuditedOnce() {
        log.record(call("172.16.5.5"), ok("[]"), true, null);
        log.record(call("172.16.5.5"), ok("[]"), true, null);
        log.record(call("172.16.5.6"), ok("[]"), true, null);
        verify(audit, times(2)).recordAction(eq("ALARM_FEED_NEW_CALLER"), anyString(), isNull(), isNull(), eq("EXTERNAL"),
                eq("ALARM_FEED"), anyString(), anyString(), anyString(), anyString(), isNull());
        assertThat(callers.findById("172.16.5.5").orElseThrow().getCallCount()).isEqualTo(2L);
        assertThat(callers.findTop50ByOrderByLastSeenAtDesc()).hasSize(2);
    }

    @Test
    @DisplayName("Önce reddedilen, sonra izin listesine eklenen IP: İLK KABUL edilen çağrıda bir kez denetlenir (başka pod'da da)")
    void firstAcceptedCallIsAuditedAfterRefusals() {
        AlarmFeedRequestLog.Result denied = new AlarmFeedRequestLog.Result(403, AlarmFeedRequestLog.OUTCOME_IP_DENIED,
                "{\"success\":false}", "application/json", null, null, null, null, "json", null);
        log.record(call("172.20.1.1"), denied, true, null);
        assertThat(callers.findById("172.20.1.1").orElseThrow().getFirstOkAt()).isNull();
        now += 60_000;
        log.record(call("172.20.1.1"), ok("[]"), true, null);
        now += 60_000;
        log.record(call("172.20.1.1"), ok("[]"), true, null);
        AlarmFeedRequestLog other = new AlarmFeedRequestLog(requests, jdbc);   // ikinci pod
        other.setAuditService(audit);
        other.clock = () -> now;
        other.record(call("172.20.1.1"), ok("[]"), true, null);
        verify(audit, times(1)).recordAction(eq("ALARM_FEED_NEW_CALLER"), anyString(), isNull(), isNull(), eq("EXTERNAL"),
                eq("ALARM_FEED"), eq("172.20.1.1"), anyString(), anyString(), anyString(), isNull());
        assertThat(callers.findById("172.20.1.1").orElseThrow().getFirstOkAt()).isNotNull();
        assertThat(callers.findById("172.20.1.1").orElseThrow().getCallCount()).isEqualTo(4L);
    }

    @Test
    @DisplayName("Engelli IP: engelin yazıldığı çağrı her zaman satır, sonra IP başına dakikada bir; ayrıca ret denetimi yazılmaz")
    void blockedCallsAreThrottled() {
        AlarmFeedRequestLog.Result placed = new AlarmFeedRequestLog.Result(429, AlarmFeedRequestLog.OUTCOME_BLOCKED,
                "{\"code\":\"ALARM_FEED_IP_BLOCKED\"}", "application/json", null, null, null, null, "json", null, true);
        AlarmFeedRequestLog.Result blocked = new AlarmFeedRequestLog.Result(429, AlarmFeedRequestLog.OUTCOME_BLOCKED,
                "{\"code\":\"ALARM_FEED_IP_BLOCKED\"}", "application/json", null, null, null, null, "json", null);
        log.record(call("6.6.6.6"), blocked, true, null);   // dakikanın ilk BLOCKED satırı
        log.record(call("6.6.6.6"), placed, true, null);    // engelin yazıldığı çağrı — yine yazılır
        for (int i = 0; i < 50; i++) log.record(call("6.6.6.6"), blocked, true, null);
        assertThat(requests.count()).isEqualTo(2);
        assertThat(log.suppressedRefusals()).isEqualTo(50);
        now += 61_000;
        log.record(call("6.6.6.6"), blocked, true, null);
        assertThat(requests.count()).isEqualTo(3);
        verify(audit, never()).recordAction(eq("ALARM_FEED_ACCESS_DENIED"), anyString(), any(), any(), anyString(),
                anyString(), anyString(), anyString(), anyString(), anyString(), any());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alarm_feed_responses", Long.class)).isZero();
    }

    @Test
    @DisplayName("Reddedilen çağrı: her biri günlükte; denetim IP + neden başına 10 dk'da bir; oran sınırı satırı dakikada bir")
    void deniedThrottles() {
        AlarmFeedRequestLog.Result denied = new AlarmFeedRequestLog.Result(403, AlarmFeedRequestLog.OUTCOME_IP_DENIED,
                "{\"success\":false}", "application/json", null, null, null, null, "json", null);
        log.record(call("8.8.8.8"), denied, true, null);
        log.record(call("8.8.8.8"), denied, true, null);
        assertThat(requests.count()).isEqualTo(2);
        verify(audit, times(1)).recordAction(eq("ALARM_FEED_ACCESS_DENIED"), anyString(), any(), any(), anyString(),
                anyString(), anyString(), anyString(), anyString(), anyString(), any());
        now += 11 * 60_000;
        log.record(call("8.8.8.8"), denied, true, null);
        verify(audit, times(2)).recordAction(eq("ALARM_FEED_ACCESS_DENIED"), anyString(), any(), any(), anyString(),
                anyString(), anyString(), anyString(), anyString(), anyString(), any());

        AlarmFeedRequestLog.Result rl = new AlarmFeedRequestLog.Result(429, AlarmFeedRequestLog.OUTCOME_RATE_LIMITED,
                "{}", "application/json", null, null, null, null, "json", null);
        long before = requests.count();
        for (int i = 0; i < 20; i++) log.record(call("9.9.9.9"), rl, true, null);
        assertThat(requests.count()).isEqualTo(before + 1);
        now += 61_000;
        log.record(call("9.9.9.9"), rl, true, null);
        assertThat(requests.count()).isEqualTo(before + 2);
        // Başarılı çağrı reddedilmiş denetim yazmaz
        log.record(call("172.16.1.1"), ok("[]"), true, null);
        verify(audit, never()).recordSecurityEvent(anyString(), any(), any(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("Günlük yazılamazsa istisna atmaz, hata sayılır")
    void writeFailureIsCountedNotThrown() {
        AlarmFeedRequestRepository broken = mock(AlarmFeedRequestRepository.class);
        org.mockito.Mockito.when(broken.save(any())).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("db"));
        JdbcTemplate badJdbc = mock(JdbcTemplate.class, inv -> {
            throw new org.springframework.dao.DataAccessResourceFailureException("db");
        });
        AlarmFeedRequestLog l = new AlarmFeedRequestLog(broken, badJdbc);
        l.record(call("1.1.1.1"), ok("[]"), true, null);
        // gövde + çağrı satırı + çağıran kaydı: üçü de düştü, hiçbiri istisna atmadı
        assertThat(l.writeFailures()).isEqualTo(3);
    }

    @Test
    @DisplayName("Reddedilen çağrının gövdesi saklanmaz; pod başına dakikada en çok 60 ret satırı; yeni IP denetimi yalnız kabulde")
    void refusedCallsAreBounded() {
        for (int i = 0; i < 80; i++) {
            AlarmFeedRequestLog.Result denied = new AlarmFeedRequestLog.Result(404, AlarmFeedRequestLog.OUTCOME_DISABLED,
                    "{\"success\":false,\"request_id\":\"r" + i + "\"}", "application/json", null, null, null, null, "json", null);
            log.record(call("10.9." + (i / 250) + "." + (i % 250)), denied, true, null);
        }
        assertThat(requests.count()).isEqualTo(AlarmFeedRequestLog.REFUSED_LOG_PER_MINUTE);
        assertThat(log.suppressedRefusals()).isEqualTo(80 - AlarmFeedRequestLog.REFUSED_LOG_PER_MINUTE);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alarm_feed_responses", Long.class)).isZero();
        verify(audit, never()).recordAction(eq("ALARM_FEED_NEW_CALLER"), anyString(), any(), any(), anyString(),
                anyString(), anyString(), anyString(), anyString(), anyString(), any());
        verify(audit, times(AlarmFeedRequestLog.DENIED_AUDIT_PER_MINUTE)).recordAction(eq("ALARM_FEED_ACCESS_DENIED"),
                anyString(), any(), any(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), any());
        now += 61_000;
        log.record(call("10.9.9.9"), ok("[]"), true, null);
        assertThat(requests.count()).isEqualTo(AlarmFeedRequestLog.REFUSED_LOG_PER_MINUTE + 1L);
    }

    @Test
    @DisplayName("Aynı gövde yeniden dönünce son kullanım tazelenir (saklama süresi buna göre)")
    void bodyLastUsedIsRefreshed() {
        log.record(call("172.16.5.5"), ok("[7]"), true, null);
        String first = jdbc.queryForObject("SELECT last_used_at FROM alarm_feed_responses", String.class);
        now += 11 * 60_000;
        log.record(call("172.16.5.5"), ok("[7]"), true, null);
        String second = jdbc.queryForObject("SELECT last_used_at FROM alarm_feed_responses", String.class);
        assertThat(second).isGreaterThan(first);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alarm_feed_responses", Long.class)).isEqualTo(1L);
    }

    @Test
    @DisplayName("Uzun başlıklar sütuna sığdırılır")
    void longValuesFit() {
        AlarmFeedRequestLog.Call c = new AlarmFeedRequestLog.Call(now, "1.2.3.4", "10.0.0.1", "x".repeat(900),
                "u".repeat(900), "GET", "q".repeat(3000), "id", "pod");
        log.record(c, ok("[]"), true, null);
        AlarmFeedRequest r = requests.findTopByOrderByRequestedAtDescIdDesc();
        assertThat(r.getForwardedFor()).hasSize(AlarmFeedRequest.FORWARDED_MAX);
        assertThat(r.getUserAgent()).hasSize(AlarmFeedRequest.UA_MAX);
        assertThat(r.getQuery()).hasSize(AlarmFeedRequest.QUERY_MAX);
        assertThat(List.of(r.getOutcome())).containsExactly("OK");
    }
}
