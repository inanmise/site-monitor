package com.sitemonitor.controller;

import com.sitemonitor.service.ClientIpResolver;
import com.sitemonitor.service.alarmfeed.AlarmFeedRequestLog;
import com.sitemonitor.service.alarmfeed.AlarmFeedService;
import com.sitemonitor.service.alarmfeed.AlarmFeedSettings;
import com.sitemonitor.service.lockout.RateLimitCounterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Herkese açık alarm akışı ucu: kapı sırası, hata gövdeleri, biçimler, her çağrının günlüğü. */
class AlarmFeedControllerTest {

    AlarmFeedService feed;
    AlarmFeedRequestLog requestLog;
    ClientIpResolver resolver;
    AlarmFeedController controller;

    static AlarmFeedSettings.Config cfg(boolean enabled, List<String> ips, int rate) {
        return new AlarmFeedSettings.Config(enabled, "WARNING", Set.of(), Set.of(), 15, "status", "Clear", "Critical",
                "Major", "Warning", "target_type", "SiteMonitor", "", "semicolon", ips, rate, 2000, true);
    }

    static final AlarmFeedService.Feed FEED = new AlarmFeedService.Feed(
            List.of(Map.of("ALERTID", 1L), Map.of("ALERTID", 2L)), 1, 1, false, "2026-10-30T10:00:00");

    @BeforeEach
    void setUp() {
        feed = mock(AlarmFeedService.class);
        requestLog = mock(AlarmFeedRequestLog.class);
        resolver = mock(ClientIpResolver.class);
        when(resolver.resolve(any())).thenReturn("10.1.2.3");
        controller = new AlarmFeedController(feed, requestLog, resolver);
        controller.setLimiter(RateLimitCounterService.memoryOnly());
        when(feed.feed(any())).thenReturn(FEED);
        when(feed.toJson(any())).thenReturn("[{\"ALERTID\":1},{\"ALERTID\":2}]");
    }

    static MockHttpServletRequest req() {
        MockHttpServletRequest r = new MockHttpServletRequest("GET", AlarmFeedController.PATH);
        r.setRemoteAddr("10.0.0.1");
        r.addHeader("X-Forwarded-For", "10.1.2.3, 10.0.0.1");
        r.addHeader("User-Agent", "python-requests/2.31");
        return r;
    }

    AlarmFeedRequestLog.Result logged() {
        ArgumentCaptor<AlarmFeedRequestLog.Result> c = ArgumentCaptor.forClass(AlarmFeedRequestLog.Result.class);
        verify(requestLog).record(any(), c.capture(), anyBoolean(), any());
        return c.getValue();
    }

    @Test
    @DisplayName("Kapalı → 404 ALARM_FEED_DISABLED, veri okunmaz, çağrı günlüğe DISABLED yazılır")
    void disabled() {
        when(feed.config()).thenReturn(cfg(false, List.of(), 30));
        ResponseEntity<String> r = controller.alarms(null, req());
        assertThat(r.getStatusCode().value()).isEqualTo(404);
        assertThat(r.getBody()).contains("\"code\":\"ALARM_FEED_DISABLED\"");
        verify(feed, never()).feed(any());
        assertThat(logged().outcome()).isEqualTo("DISABLED");
    }

    @Test
    @DisplayName("IP izin listesinde değil → 403; listedeyse 200")
    void ipAllowList() {
        when(feed.config()).thenReturn(cfg(true, List.of("192.168.0.0/16"), 30));
        ResponseEntity<String> r = controller.alarms(null, req());
        assertThat(r.getStatusCode().value()).isEqualTo(403);
        assertThat(r.getBody()).contains("ALARM_FEED_IP_DENIED").contains("10.1.2.3");
        assertThat(logged().outcome()).isEqualTo("IP_DENIED");

        when(feed.config()).thenReturn(cfg(true, List.of("10.0.0.0/8"), 30));
        assertThat(controller.alarms(null, req()).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    @DisplayName("JSON: 200, dizi gövde, başlıklarda sayılar; çağrı günlüğe gövdesiyle OK yazılır")
    void okJson() {
        when(feed.config()).thenReturn(cfg(true, List.of(), 30));
        ResponseEntity<String> r = controller.alarms(null, req());
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(r.getBody()).startsWith("[");
        assertThat(r.getHeaders().getContentType().toString()).startsWith("application/json");
        assertThat(r.getHeaders().getFirst("X-Feed-Rows")).isEqualTo("2");
        assertThat(r.getHeaders().getFirst("X-Feed-Open")).isEqualTo("1");
        assertThat(r.getHeaders().getFirst("X-Feed-Resolved")).isEqualTo("1");
        AlarmFeedRequestLog.Result res = logged();
        assertThat(res.outcome()).isEqualTo("OK");
        assertThat(res.body()).isEqualTo(r.getBody());
        assertThat(res.rowCount()).isEqualTo(2);
        ArgumentCaptor<AlarmFeedRequestLog.Call> call = ArgumentCaptor.forClass(AlarmFeedRequestLog.Call.class);
        verify(requestLog).record(call.capture(), any(), eq(true), any());
        assertThat(call.getValue().clientIp()).isEqualTo("10.1.2.3");
        assertThat(call.getValue().remoteAddr()).isEqualTo("10.0.0.1");
        assertThat(call.getValue().forwardedFor()).isEqualTo("10.1.2.3, 10.0.0.1");
        assertThat(call.getValue().userAgent()).isEqualTo("python-requests/2.31");
    }

    @Test
    @DisplayName("format=csv → text/csv; bilinmeyen biçim JSON'a düşer")
    void csvFormat() {
        when(feed.config()).thenReturn(cfg(true, List.of(), 30));
        ResponseEntity<String> r = controller.alarms("CSV", req());
        assertThat(r.getHeaders().getContentType().toString()).startsWith("text/csv");
        assertThat(r.getBody()).startsWith("CREATEDTIME,SEVERITY,RCI");
        ResponseEntity<String> j = controller.alarms("xml", req());
        assertThat(j.getHeaders().getContentType().toString()).startsWith("application/json");
    }

    @Test
    @DisplayName("Veri okunamazsa 503 ALARM_FEED_UNAVAILABLE — asla boş dizi")
    void dataFailureIs503() {
        when(feed.config()).thenReturn(cfg(true, List.of(), 30));
        when(feed.feed(any())).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("db"));
        ResponseEntity<String> r = controller.alarms(null, req());
        assertThat(r.getStatusCode().value()).isEqualTo(503);
        assertThat(r.getBody()).contains("ALARM_FEED_UNAVAILABLE").doesNotStartWith("[");
        AlarmFeedRequestLog.Result res = logged();
        assertThat(res.outcome()).isEqualTo("ERROR");
        assertThat(res.error()).isEqualTo("DataAccessResourceFailureException");
    }

    @Test
    @DisplayName("Dakikalık sınır aşılınca 429 + Retry-After; veri okunmaz")
    void rateLimit() {
        when(feed.config()).thenReturn(cfg(true, List.of(), 2));
        assertThat(controller.alarms(null, req()).getStatusCode().value()).isEqualTo(200);
        assertThat(controller.alarms(null, req()).getStatusCode().value()).isEqualTo(200);
        ResponseEntity<String> r = controller.alarms(null, req());
        assertThat(r.getStatusCode().value()).isEqualTo(429);
        assertThat(Long.parseLong(r.getHeaders().getFirst("Retry-After"))).isBetween(1L, 60L);
        assertThat(r.getBody()).contains("RATE_LIMITED");
        verify(feed, times(2)).feed(any());
    }

    @Test
    @DisplayName("Uç oturumsuz açık: AuthInterceptor PUBLIC listesinde")
    void pathIsPublic() throws Exception {
        java.lang.reflect.Field f = com.sitemonitor.config.AuthInterceptor.class.getDeclaredField("PUBLIC");
        f.setAccessible(true);
        @SuppressWarnings("unchecked") Set<String> pub = (Set<String>) f.get(null);
        assertThat(pub).contains(AlarmFeedController.PATH);
        assertThat(AlarmFeedController.PATH).doesNotContain("status");
    }
}
