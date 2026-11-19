package com.sitemonitor.controller;

import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.monitorcsv.MonitorCsvLimiter;
import com.sitemonitor.service.monitorcsv.MonitorCsvService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * İzleme CSV ucu (2026-11-19): tür doğrulaması, gövde sınırı (413), kişi başına tek içe aktarım ve dakikalık dışa aktarım
 * sınırı (429), başlık hatası 400 {@code VALIDATION_FAILED} alan {@code csv}, önizleme denetim yazmaz / gerçek koşum yalnız
 * sayılarla denetim yazar.
 */
class MonitorCsvControllerTest {

    MonitorCsvService service;
    PermissionService permissions;
    AuditService audit;
    MonitorCsvController controller;
    MockHttpSession session;

    @BeforeEach
    void setUp() {
        service = mock(MonitorCsvService.class);
        permissions = mock(PermissionService.class);
        audit = mock(AuditService.class);
        controller = new MonitorCsvController(service, permissions, audit, new MonitorCsvLimiter());
        session = new MockHttpSession();
        session.setAttribute("username", "csv-ctl");
        session.setAttribute("systemRole", "ADMIN");
    }

    private static MockHttpServletRequest body(String text) {
        MockHttpServletRequest r = new MockHttpServletRequest("POST", "/api/monitoring/csv/http/import");
        r.setContentType("text/csv");
        r.setContent(text.getBytes(StandardCharsets.UTF_8));
        return r;
    }

    private static MonitorCsvService.ImportResult result(boolean dryRun) {
        MonitorCsvService.RowResult row = new MonitorCsvService.RowResult(2, "A", "T", MonitorCsvService.A_CREATE, List.of(),
                List.of("basic_auth_pass"), dryRun ? null : MonitorCsvService.S_DONE, null, null);
        return new MonitorCsvService.ImportResult("http", dryRun, List.of(row), Map.of("create", 1), List.of("name", "team"), false);
    }

    @Test
    @DisplayName("bilinmeyen tür: dışa ve içe aktarımda 400 VALIDATION_FAILED alan type; servise gidilmez")
    void unknownType() {
        ResponseEntity<?> ex = controller.export("ftp", null, null, session);
        assertThat(ex.getStatusCode().value()).isEqualTo(400);
        assertThat(((Map<?, ?>) ex.getBody()).get("field")).isEqualTo("type");
        ResponseEntity<Map<String, Object>> im = controller.importCsv("ftp", true, body("name,team\n"), session);
        assertThat(im.getStatusCode().value()).isEqualTo(400);
        assertThat(im.getBody()).containsEntry("field", "type").containsEntry("code", "VALIDATION_FAILED");
        verify(service, never()).importCsv(anyString(), anyString(), anyBoolean(), any());
    }

    @Test
    @DisplayName("gövde sınırı: 5 MB'ı aşan bildirilen uzunluk ve uzunluksuz (akan) gövde 413 PAYLOAD_TOO_LARGE")
    void bodyLimit() {
        MockHttpServletRequest big = new MockHttpServletRequest("POST", "/x") {
            @Override public long getContentLengthLong() { return -1; }
        };
        big.setContent(new byte[MonitorCsvController.MAX_BODY_BYTES + 10]);
        ResponseEntity<Map<String, Object>> res = controller.importCsv("http", true, big, session);
        assertThat(res.getStatusCode().value()).isEqualTo(413);
        assertThat(res.getBody()).containsEntry("code", "PAYLOAD_TOO_LARGE");
        MockHttpServletRequest overDeclared = new MockHttpServletRequest("POST", "/x") {
            @Override public long getContentLengthLong() { return MonitorCsvController.MAX_BODY_BYTES + 1L; }
        };
        assertThat(controller.importCsv("http", true, overDeclared, session).getStatusCode().value()).isEqualTo(413);
        verify(service, never()).importCsv(anyString(), anyString(), anyBoolean(), any());
    }

    @Test
    @DisplayName("başlık hatası: 400 VALIDATION_FAILED alan csv + reason / column / line")
    void headerErrorIs400() {
        when(service.importCsv(eq("http"), anyString(), eq(true), any())).thenThrow(
                new MonitorCsvService.CsvRequestException("unknown_column", "password", 1, "Bilinmeyen sütun: password"));
        ResponseEntity<Map<String, Object>> res = controller.importCsv("http", true, body("name,team,password\n"), session);
        assertThat(res.getStatusCode().value()).isEqualTo(400);
        assertThat(res.getBody()).containsEntry("field", "csv").containsEntry("reason", "unknown_column")
                .containsEntry("column", "password").containsEntry("line", 1);
    }

    @Test
    @DisplayName("önizleme denetim yazmaz; gerçek koşum MONITOR_CSV_IMPORT'u yalnız sayılarla yazar (hücre değeri yok)")
    void auditOnlyOnRealRun() {
        when(service.importCsv(eq("http"), anyString(), eq(true), any())).thenReturn(result(true));
        when(service.importCsv(eq("http"), anyString(), eq(false), any())).thenReturn(result(false));
        ResponseEntity<Map<String, Object>> dry = controller.importCsv("http", true, body("name,team\nA,T\n"), session);
        assertThat(dry.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked") Map<String, Object> data = (Map<String, Object>) dry.getBody().get("data");
        assertThat(data).containsEntry("dry_run", true).containsKeys("rows", "counts", "columns", "limits");
        verify(audit, never()).recordAction(anyString(), any(jakarta.servlet.http.HttpSession.class), anyString(), anyString(), anyString(), isNull());

        controller.importCsv("http", false, body("name,team\nA,T\n"), session);
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(audit).recordAction(eq("MONITOR_CSV_IMPORT"), any(jakarta.servlet.http.HttpSession.class), eq("MONITOR_CSV"), eq("http"),
                detail.capture(), isNull());
        assertThat(detail.getValue()).contains("\"created\":1").doesNotContain("\"A\"").doesNotContain("basic_auth_pass");
    }

    @Test
    @DisplayName("kişi başına aynı anda tek içe aktarım: süren koşum varken ikincisi 429 RATE_LIMITED; bitince yeniden açılır")
    void oneImportAtATime() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(service.importCsv(eq("http"), anyString(), eq(true), any())).thenAnswer(inv -> {
            inside.countDown();
            release.await(10, TimeUnit.SECONDS);
            return result(true);
        });
        AtomicReference<ResponseEntity<Map<String, Object>>> first = new AtomicReference<>();
        Thread t = new Thread(() -> first.set(controller.importCsv("http", true, body("name,team\nA,T\n"), session)));
        t.start();
        assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();
        ResponseEntity<Map<String, Object>> second = controller.importCsv("http", true, body("name,team\nA,T\n"), session);
        assertThat(second.getStatusCode().value()).isEqualTo(429);
        assertThat(second.getBody()).containsEntry("code", "RATE_LIMITED");
        assertThat(second.getHeaders().getFirst("Retry-After")).isEqualTo("5");
        release.countDown();
        t.join(10_000);
        assertThat(first.get().getStatusCode().value()).isEqualTo(200);
        assertThat(controller.importCsv("http", true, body("name,team\nA,T\n"), session).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    @DisplayName("dışa aktarım: dakikada 6, yedincisi 429 + Retry-After; reddedilen sayılmaz; yanıt no-store ve satır başlıkları")
    void exportRateAndHeaders() {
        when(service.export(eq("ping"), any(), isNull(), isNull()))
                .thenReturn(new MonitorCsvService.Export("ping-izlemeler-20261119.csv", "﻿name,team\r\n", 0, false));
        for (int i = 0; i < MonitorCsvLimiter.EXPORTS_PER_MINUTE; i++) {
            ResponseEntity<?> ok = controller.export("ping", null, null, session);
            assertThat(ok.getStatusCode().value()).isEqualTo(200);
            assertThat(ok.getHeaders().getFirst("Cache-Control")).isEqualTo("no-store");
            assertThat(ok.getHeaders().getFirst("X-Export-Rows")).isEqualTo("0");
            assertThat(ok.getHeaders().getFirst("Content-Disposition")).contains("ping-izlemeler-20261119.csv");
        }
        ResponseEntity<?> limited = controller.export("ping", null, null, session);
        assertThat(limited.getStatusCode().value()).isEqualTo(429);
        assertThat(Long.parseLong(limited.getHeaders().getFirst("Retry-After"))).isBetween(1L, 60L);
        verify(audit, org.mockito.Mockito.times(MonitorCsvLimiter.EXPORTS_PER_MINUTE)).recordAction(eq("MONITOR_CSV_EXPORT"),
                any(jakarta.servlet.http.HttpSession.class), eq("MONITOR_CSV"), eq("ping"), anyString(), isNull());
    }

    @Test
    @DisplayName("kimlik listesi geçersiz → 400 alan ids")
    void badIds() {
        ResponseEntity<?> res = controller.export("ping", null, "1,abc", session);
        assertThat(res.getStatusCode().value()).isEqualTo(400);
        assertThat(((Map<?, ?>) res.getBody()).get("field")).isEqualTo("ids");
    }
}
