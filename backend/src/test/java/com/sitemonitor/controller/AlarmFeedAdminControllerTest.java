package com.sitemonitor.controller;

import com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException;
import com.sitemonitor.model.AlarmFeedRequest;
import com.sitemonitor.repository.AlarmFeedCallerRepository;
import com.sitemonitor.repository.AlarmFeedRequestRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.alarmfeed.AlarmFeedRequestLog;
import com.sitemonitor.service.alarmfeed.AlarmFeedService;
import com.sitemonitor.service.alarmfeed.AlarmFeedSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Ayarlar → Alarm akışı yönetim ucu: yalnız global yönetici, değişen alanlar, doğrulama, önizleme, çağrı günlüğü. */
class AlarmFeedAdminControllerTest {

    AlarmFeedService feed;
    AlarmFeedRequestLog requestLog;
    AlarmFeedRequestRepository requests;
    AlarmFeedCallerRepository callers;
    TeamRepository teams;
    AppSettingsService settings;
    AuditService audit;
    AlarmFeedAdminController controller;

    @BeforeEach
    void setUp() {
        feed = mock(AlarmFeedService.class);
        requestLog = mock(AlarmFeedRequestLog.class);
        requests = mock(AlarmFeedRequestRepository.class);
        callers = mock(AlarmFeedCallerRepository.class);
        teams = mock(TeamRepository.class);
        settings = mock(AppSettingsService.class);
        audit = mock(AuditService.class);
        when(feed.config()).thenReturn(AlarmFeedSettings.defaults());
        when(settings.getInt(anyString(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(90);
        when(requests.outcomeStatsSince(anyString())).thenReturn(List.of());
        controller = new AlarmFeedAdminController(feed, requestLog, requests, callers, teams, settings, audit);
    }

    static MockHttpSession session(String role, boolean scoped) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("username", "root");
        s.setAttribute("systemRole", role);
        if (scoped) s.setAttribute("viewTeamIds", new ArrayList<>(List.of(7L)));
        return s;
    }

    static final MockHttpSession ADMIN = session("ADMIN", false);

    @Test
    @DisplayName("Yalnız global yönetici: kapsamlı yönetici, AUDIT ve USER 403")
    void globalAdminOnly() {
        for (MockHttpSession s : List.of(session("ADMIN", true), session("AUDIT", false), session("USER", false))) {
            assertThatThrownBy(() -> controller.view(s)).isInstanceOf(SecurityException.class);
            assertThatThrownBy(() -> controller.save(Map.of("enabled", true), s, new MockHttpServletRequest()))
                    .isInstanceOf(SecurityException.class);
            assertThatThrownBy(() -> controller.preview(s)).isInstanceOf(SecurityException.class);
            assertThatThrownBy(() -> controller.requests(0, 25, null, null, null, null, s)).isInstanceOf(SecurityException.class);
        }
    }

    @Test
    @DisplayName("Görünüm: uç, ayarlar, sınırlar, kolonlar; okunamayan özet null (asla 0)")
    @SuppressWarnings("unchecked")
    void view() {
        when(requests.findTopByOrderByRequestedAtDescIdDesc()).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("db"));
        ResponseEntity<Map<String, Object>> r = controller.view(ADMIN);
        Map<String, Object> d = (Map<String, Object>) r.getBody().get("data");
        assertThat(d.get("endpoint")).isEqualTo("/api/public/alarm-feed");
        assertThat((Map<String, Object>) d.get("settings")).containsEntry("enabled", false).containsEntry("severity_high", "Major");
        Map<String, Object> st = (Map<String, Object>) d.get("status");
        assertThat(st.get("calls_24h")).isNull();
        assertThat(st.get("error")).isEqualTo("DataAccessResourceFailureException");
        assertThat((List<String>) d.get("columns")).startsWith("CREATEDTIME", "SEVERITY");
    }

    @Test
    @DisplayName("Kayıt: yalnız DEĞİŞEN alanlar yazılır ve denetlenir; değişiklik yoksa yazım / denetim yok")
    @SuppressWarnings("unchecked")
    void saveOnlyChanged() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("enabled", true);
        body.put("severity_high", "Major");          // aynı → yazılmaz
        body.put("allowed_ips", List.of("10.0.0.0/8", "192.168.1.20"));
        body.put("types", List.of("http", "cert"));
        controller.save(body, ADMIN, new MockHttpServletRequest());
        ArgumentCaptor<Map<String, Object>> c = ArgumentCaptor.forClass(Map.class);
        verify(settings).save(c.capture(), eq("root"));
        Map<String, Object> values = (Map<String, Object>) c.getValue().get("values");
        assertThat(values).containsOnlyKeys(AlarmFeedSettings.KEY_ENABLED, AlarmFeedSettings.KEY_ALLOWED_IPS,
                AlarmFeedSettings.KEY_TYPES);
        assertThat(values.get(AlarmFeedSettings.KEY_TYPES)).isEqualTo("cert,http");   // katalog sırası
        assertThat(values.get(AlarmFeedSettings.KEY_ALLOWED_IPS)).isEqualTo("10.0.0.0/8,192.168.1.20");
        verify(audit).recordAction(eq("ALARM_FEED_SETTINGS_SAVE"), eq(ADMIN), any(), eq("SETTINGS"), eq("alarm-feed"),
                anyString(), anyString());
        verify(feed).invalidate();

        AlarmFeedAdminControllerTest fresh = new AlarmFeedAdminControllerTest();
        fresh.setUp();
        fresh.controller.save(Map.of("severity_high", "Major", "enabled", false), ADMIN, new MockHttpServletRequest());
        verify(fresh.settings, never()).save(any(), anyString());
        verify(fresh.audit, never()).recordAction(anyString(), any(org.springframework.mock.web.MockHttpSession.class),
                any(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("Doğrulama: alan adıyla 400 (geçersiz IP, aralık dışı sayı, boş metin, bilinmeyen tür, kontrol karakteri)")
    void validation() {
        Map<String, Object> cases = new LinkedHashMap<>();
        cases.put("allowed_ips", List.of("intranet.local"));
        cases.put("resolved_minutes", 0);
        cases.put("max_rows", 50_000);
        cases.put("severity_critical", "  ");
        cases.put("types", List.of("ftp"));
        cases.put("source", "a\nb");
        cases.put("min_level", "INFO");
        cases.put("resolved_mode", "delete");
        cases.put("team_ids", List.of("x"));
        for (Map.Entry<String, Object> e : cases.entrySet()) {
            assertThatThrownBy(() -> controller.save(Map.of(e.getKey(), e.getValue()), ADMIN, new MockHttpServletRequest()))
                    .as(e.getKey())
                    .isInstanceOfSatisfying(FieldValidationException.class, ex -> assertThat(ex.getMessage()).isNotBlank());
        }
        verify(settings, never()).save(any(), anyString());
    }

    @Test
    @DisplayName("Önizleme: kolonlar + satırlar; veri okunamazsa 503")
    @SuppressWarnings("unchecked")
    void preview() {
        when(feed.preview(any())).thenReturn(new AlarmFeedService.Feed(List.of(Map.of("ALERTID", 1L)), 1, 0, false,
                "2026-10-30T10:00:00"));
        Map<String, Object> d = (Map<String, Object>) controller.preview(ADMIN).getBody().get("data");
        assertThat(d.get("count")).isEqualTo(1);
        assertThat((List<?>) d.get("rows")).hasSize(1);
        when(feed.preview(any())).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("db"));
        ResponseEntity<Map<String, Object>> r = controller.preview(ADMIN);
        assertThat(r.getStatusCode().value()).isEqualTo(503);
        assertThat(r.getBody().get("code")).isEqualTo("ALARM_FEED_UNAVAILABLE");
    }

    static AlarmFeedRequest row(long id) {
        AlarmFeedRequest r = new AlarmFeedRequest();
        r.setId(id);
        r.setRequestedAt("2026-10-30T10:00:00.123");
        r.setClientIp("10.1.2.3");
        r.setOutcome("OK");
        r.setStatus(200);
        r.setResponseHash("abc");
        return r;
    }

    @Test
    @DisplayName("Çağrı günlüğü: süzgeç İstanbul günü → UTC; IP önek; son sayfaya kıstırma; ayrıntıda birebir gövde")
    @SuppressWarnings("unchecked")
    void requestLog() {
        when(requests.search(eq("OK"), eq("10.1.%"), eq("2026-10-29T21:00:00"), eq("2026-10-30T21:00:00"), any()))
                .thenReturn(new PageImpl<>(List.of(row(5)), PageRequest.of(0, 25), 1));
        Map<String, Object> d = (Map<String, Object>) controller.requests(0, 25, "ok", "10.1.", "2026-10-30", "2026-10-30", ADMIN)
                .getBody().get("data");
        assertThat(d.get("total")).isEqualTo(1L);
        Map<String, Object> item = ((List<Map<String, Object>>) d.get("items")).get(0);
        assertThat(item.get("requested_at")).isEqualTo("2026-10-30T10:00:00");
        assertThatThrownBy(() -> controller.requests(0, 25, "BOGUS", null, null, null, ADMIN))
                .isInstanceOf(FieldValidationException.class);
        assertThatThrownBy(() -> controller.requests(0, 25, null, "1.2.3.4' OR 1=1", null, null, ADMIN))
                .isInstanceOf(FieldValidationException.class);
        assertThatThrownBy(() -> controller.requests(0, 25, null, null, "30.10.2026", null, ADMIN))
                .isInstanceOf(FieldValidationException.class);

        when(requests.findById(5L)).thenReturn(Optional.of(row(5)));
        when(requestLog.body("abc")).thenReturn("[{\"A\":1}]");
        when(requestLog.contentType("abc")).thenReturn("application/json");
        Map<String, Object> det = (Map<String, Object>) controller.request(5L, ADMIN).getBody().get("data");
        assertThat(det.get("body")).isEqualTo("[{\"A\":1}]");
        assertThat(det.get("body_available")).isEqualTo(true);
        when(requests.findById(6L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> controller.request(6L, ADMIN)).isInstanceOf(java.util.NoSuchElementException.class);
    }

    @Test
    @DisplayName("CSV dışa aktarma: BOM + başlık + satır; denetim ALARM_FEED_LOG_EXPORT")
    void export() {
        when(requests.search(isNull(), isNull(), isNull(), isNull(), any()))
                .thenReturn(new PageImpl<>(List.of(row(1), row(2)), PageRequest.of(0, 500), 2));
        ResponseEntity<byte[]> r = controller.export(null, null, null, null, ADMIN, new MockHttpServletRequest());
        String csv = new String(r.getBody(), StandardCharsets.UTF_8);
        assertThat(csv).startsWith("﻿requested_at_utc,client_ip");
        assertThat(csv.split("\r\n")).hasSize(3);
        assertThat(r.getHeaders().getFirst("Content-Disposition")).contains("alarm-akisi-cagri-gunlugu-");
        verify(audit).recordAction(eq("ALARM_FEED_LOG_EXPORT"), eq(ADMIN), any(jakarta.servlet.http.HttpServletRequest.class),
                eq("ALARM_FEED"), eq("requests"), anyString());
        assertThat(Set.of(1)).hasSize(1);
    }
}
