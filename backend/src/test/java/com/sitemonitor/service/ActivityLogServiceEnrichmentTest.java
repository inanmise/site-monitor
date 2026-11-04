package com.sitemonitor.service;

import com.sitemonitor.config.CorrelationIdFilter;
import com.sitemonitor.model.ActivityLog;
import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.HttpCheck;
import com.sitemonitor.model.KeywordResult;
import com.sitemonitor.model.PortCheck;
import com.sitemonitor.repository.ActivityLogRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * {@link ActivityLogService} — 2026-11-05 aktivite zenginleştirmesi (A–C): kontrol satırı bağı (kimlik, HTTP kodu, kontrol
 * satırının KENDİ neden kodu / ayrıntısı), elle kontrolün oturumdaki kullanıcısı + istek kimliği (istek iş parçacığı ve taşınan
 * bağlam), alarm olay satırları (durum, özet, bağlamdan izleme kimliği), değişiklik bağı + PAUSED / RESUMED, kodsuz
 * tabloların (HTTP, sertifika) ön yüz aynası neden kodu.
 */
@ExtendWith(MockitoExtension.class)
class ActivityLogServiceEnrichmentTest {

    @Mock ActivityLogRepository repo;
    @InjectMocks ActivityLogService svc;

    @AfterEach
    void clearContext() {
        RequestContextHolder.resetRequestAttributes();
        MDC.remove(CorrelationIdFilter.MDC_KEY);
    }

    private List<ActivityLog> saved(int n) {
        ArgumentCaptor<ActivityLog> cap = ArgumentCaptor.forClass(ActivityLog.class);
        verify(repo, times(n)).save(cap.capture());
        return cap.getAllValues();
    }

    private ActivityLog savedOne() {
        return saved(1).get(0);
    }

    private static void requestAs(String username, String requestId) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("username", username);
        req.setSession(s);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
        if (requestId != null) MDC.put(CorrelationIdFilter.MDC_KEY, requestId);
    }

    // ── Kontrol bağı ────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Kontrol bağı: check_id + satırın neden kodu / ayrıntısı + HTTP kodu; eski imza bağsız (yalnız HTTP kodu sonuçtan)")
    void checkRefs_writtenFromRowAndResult() {
        Map<String, Object> r = new HashMap<>(Map.of("ok", false, "http_status", 503, "response_ms", 40, "error", "HTTP 503"));
        svc.recordCheck(ActivityLogService.HTTP, 7L, "web", "https://x", 5L, false, "scheduler", r,
                ActivityLogService.Refs.check(91L, null, "HTTP_STATUS", "{\"phase\":\"HTTP\"}"));
        svc.recordCheck(ActivityLogService.HTTP, 7L, "web", "https://x", 5L, false, "scheduler", r);

        List<ActivityLog> rows = saved(2);
        ActivityLog a = rows.get(0);
        assertThat(a.getCheckId()).isEqualTo(91L);
        assertThat(a.getHttpStatus()).isEqualTo(503);   // bağda yok → sonuç haritasından
        assertThat(a.getFailureReason()).isEqualTo("HTTP_STATUS");
        assertThat(a.getFailureDetail()).isEqualTo("{\"phase\":\"HTTP\"}");
        assertThat(a.getAction()).isEqualTo("SCHEDULED_CHECK");
        ActivityLog legacy = rows.get(1);
        assertThat(legacy.getCheckId()).isNull();
        assertThat(legacy.getFailureReason()).isNull();
        assertThat(legacy.getHttpStatus()).isEqualTo(503);
    }

    @Test
    @DisplayName("Tavanı aşan ayrıntı (yarım JSON okunamaz) ve kolona sığmayan neden kodu YAZILMAZ; satır yine yazılır")
    void oversizedRefs_dropped() {
        svc.recordCheck(ActivityLogService.PORT, 7L, "p", "h:1", 5L, false, "scheduler", new HashMap<>(Map.of("open", false)),
                ActivityLogService.Refs.check(1L, null, "X".repeat(49), "{" + "a".repeat(5000) + "}"));
        ActivityLog a = savedOne();
        assertThat(a.getCheckId()).isEqualTo(1L);
        assertThat(a.getFailureReason()).isNull();
        assertThat(a.getFailureDetail()).isNull();
    }

    @Test
    @DisplayName("refs(varlık): kimlik + satırın kendi değerleri; HTTP'de neden kodu hata metninden (ön yüz codeFromText aynası)")
    void refsFromEntities() {
        PortCheck pc = new PortCheck();
        pc.setId(3L);
        pc.setFailureReason("CONNECT_REFUSED");
        pc.setFailureDetail("{}");
        assertThat(ActivityLogService.refs(pc)).isEqualTo(ActivityLogService.Refs.check(3L, null, "CONNECT_REFUSED", "{}"));

        KeywordResult kr = new KeywordResult();
        kr.setId(4L);
        kr.setHttpStatus(200);
        kr.setFailureReason("KEYWORD_NOT_FOUND");
        kr.setFailureDetail("Anahtar kelime sayfada yok");
        var k = ActivityLogService.refs(kr);
        assertThat(k.httpStatus()).isEqualTo(200);
        assertThat(k.failureDetail()).isEqualTo("Anahtar kelime sayfada yok");

        HttpCheck hc = new HttpCheck();
        hc.setId(5L);
        hc.setOk(false);
        hc.setHttpStatus(500);
        hc.setError("HTTP 500");
        hc.setErrorDetail("{\"kind\":\"STATUS\"}");
        var h = ActivityLogService.refs(hc);
        assertThat(h.failureReason()).isEqualTo("HTTP_STATUS");
        assertThat(h.failureDetail()).isEqualTo("{\"kind\":\"STATUS\"}");
        hc.setOk(true);
        assertThat(ActivityLogService.refs(hc).failureReason()).isNull();

        assertThat(ActivityLogService.refs((PortCheck) null)).isNull();
    }

    @Test
    @DisplayName("Sertifika neden kodu: ön yüz checkFailureModel.certCode aynası (sınıf + ileti + aşama)")
    void certFailureReason_mirrorsFrontend() {
        assertThat(ActivityLogService.certFailureReason("DNS", "x", null)).isEqualTo("DNS_RESOLVE");
        assertThat(ActivityLogService.certFailureReason("BLOCKED", "Çözümlenemeyen host: a", null)).isEqualTo("DNS_RESOLVE");
        assertThat(ActivityLogService.certFailureReason("BLOCKED", "İzin verilmeyen hedef", null)).isEqualTo("SSRF_BLOCKED");
        assertThat(ActivityLogService.certFailureReason("SSL", "PKIX path building failed", null)).isEqualTo("TLS_TRUST");
        assertThat(ActivityLogService.certFailureReason("SSL", "No name matching x found", null)).isEqualTo("TLS_HOSTNAME");
        assertThat(ActivityLogService.certFailureReason("SSL", "handshake_failure", null)).isEqualTo("TLS_HANDSHAKE");
        assertThat(ActivityLogService.certFailureReason("NETWORK", "Connection timeout after 10s", "tls-handshake"))
                .isEqualTo("TLS_HANDSHAKE");
        assertThat(ActivityLogService.certFailureReason("NETWORK", "Connection timeout after 10s", "tcp-connect"))
                .isEqualTo("CONNECT_TIMEOUT");
        assertThat(ActivityLogService.certFailureReason("NETWORK", "x", "proxy-connect")).isEqualTo("PROXY_ERROR");
        assertThat(ActivityLogService.certFailureReason("NETWORK", "Connection refused", null)).isEqualTo("CONNECT_REFUSED");
        assertThat(ActivityLogService.certFailureReason(null, null, null)).isNull();
    }

    // ── Elle kontrolün aktörü + istek kimliği ───────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Elle kontrol: 'manual' / boş aktör → oturumdaki kullanıcı adı; istek kimliği MDC'den; gerçek ad korunur")
    void manualActor_resolvedFromSession() {
        requestAs("ayse", "rq-123");
        svc.recordCheck(ActivityLogService.PAGE, 1L, "p", "https://x", 5L, true, "manual", Map.of("status", "OK"));
        svc.recordCheck(ActivityLogService.CERT, 2L, "x.com", "x.com", 5L, true, null, Map.of("status", "valid"));
        svc.recordCheck(ActivityLogService.PORT, 3L, "p", "h:1", 5L, true, "mehmet", Map.of("open", true));

        List<ActivityLog> rows = saved(3);
        assertThat(rows).extracting(ActivityLog::getActor).containsExactly("ayse", "ayse", "mehmet");
        assertThat(rows).extracting(ActivityLog::getAction).containsOnly("MANUAL_CHECK");
        assertThat(rows).extracting(ActivityLog::getRequestId).containsOnly("rq-123");
    }

    @Test
    @DisplayName("Elle kontrol oturumsuz (eski davranış): aktör 'manual'; zamanlanmış satırda istek kimliği yok")
    void manualActor_withoutSession_keepsPlaceholder() {
        svc.recordCheck(ActivityLogService.PAGE, 1L, "p", "https://x", 5L, true, "manual", Map.of("status", "OK"));
        svc.recordCheck(ActivityLogService.PAGE, 1L, "p", "https://x", 5L, false, "scheduler", Map.of("status", "OK"));
        List<ActivityLog> rows = saved(2);
        assertThat(rows.get(0).getActor()).isEqualTo("manual");
        assertThat(rows.get(1).getActor()).isEqualTo("scheduler");
        assertThat(rows).extracting(ActivityLog::getRequestId).containsOnlyNulls();
    }

    @Test
    @DisplayName("Taşınan bağlam: istek iş parçacığında yakalanır, başka iş parçacığındaki satır kullanıcıyı + istek kimliğini taşır")
    void capturedContext_carriesActorToAnotherThread() throws Exception {
        requestAs("zeynep", "rq-9");
        ActivityLogService.Context ctx = ActivityLogService.captureContext();
        clearContext();
        var pool = Executors.newSingleThreadExecutor();
        try {
            pool.submit(() -> ctx.call(() -> {
                svc.recordCheck(ActivityLogService.SCRIPTED, 1L, "s", "s", 5L, true, "manual", Map.of("status", "PASS"));
                return null;
            })).get(10, TimeUnit.SECONDS);
            // bağlam iş bitince temizlenir: aynı iş parçacığında sonraki satır kullanıcı taşımaz
            pool.submit(() -> svc.recordCheck(ActivityLogService.SCRIPTED, 1L, "s", "s", 5L, true, "manual",
                    Map.of("status", "PASS"))).get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        List<ActivityLog> rows = saved(2);
        assertThat(rows.get(0).getActor()).isEqualTo("zeynep");
        assertThat(rows.get(0).getRequestId()).isEqualTo("rq-9");
        assertThat(rows.get(1).getActor()).isEqualTo("manual");
        assertThat(rows.get(1).getRequestId()).isNull();
    }

    // ── Alarm olayları ──────────────────────────────────────────────────────────────────────────────────────────────

    private static AlertEvent alarm(Long id, String type, String level, String ctx) {
        AlertEvent e = new AlertEvent();
        e.setId(id);
        e.setDomain("odeme.local");
        e.setAlertType(type);
        e.setAlertLevel(level);
        e.setTeamId(4L);
        e.setContextJson(ctx);
        return e;
    }

    @Test
    @DisplayName("ALERT_TRIGGERED: seviye HIGH → ERROR, WARNING → WARNING; özet tür etiketi + seviye; izleme kimliği bağlamdan")
    void alertTriggered_statusSummaryAndMonitorFromContext() {
        svc.recordAlert(alarm(50L, EscalationService.TYPE_PORT_DOWN, "HIGH", "{\"monitor_id\":17,\"monitor_name\":\"Ödeme portu\"}"),
                ActivityLogService.ACTION_ALERT_TRIGGERED, null, null, ActivityLogService.SYSTEM_ACTOR, null);
        svc.recordAlert(alarm(51L, EscalationService.TYPE_PORT_SLOW, "WARNING", null),
                ActivityLogService.ACTION_ALERT_TRIGGERED, null, null, ActivityLogService.SYSTEM_ACTOR, null);

        List<ActivityLog> rows = saved(2);
        ActivityLog a = rows.get(0);
        assertThat(a.getAction()).isEqualTo("ALERT_TRIGGERED");
        assertThat(a.getMonitorType()).isEqualTo("PORT");
        assertThat(a.getMonitorId()).isEqualTo(17L);
        assertThat(a.getMonitorName()).isEqualTo("Ödeme portu");
        assertThat(a.getTarget()).isEqualTo("odeme.local");
        assertThat(a.getTeamId()).isEqualTo(4L);
        assertThat(a.getAlertEventId()).isEqualTo(50L);
        assertThat(a.getResultStatus()).isEqualTo("ERROR");
        assertThat(a.getResultSummary()).isEqualTo("Port Kesintisi · HIGH");
        assertThat(a.getActor()).isEqualTo("system");
        ActivityLog b = rows.get(1);
        assertThat(b.getResultStatus()).isEqualTo("WARNING");
        assertThat(b.getMonitorId()).isNull();   // eşlenemedi → null, hedef alarm anahtarı
        assertThat(b.getTarget()).isEqualTo("odeme.local");
    }

    @Test
    @DisplayName("Onay / kapanış SUCCESS; kapanış notu özete; aktör oturumdaki kullanıcı; kimliksiz alarm satır üretmez")
    void alertAckAndResolve() {
        requestAs("ali", "rq-1");
        AlertEvent e = alarm(60L, EscalationService.TYPE_ACCESSIBILITY, "CRITICAL", null);
        svc.recordAlert(e, ActivityLogService.ACTION_ALERT_ACKNOWLEDGED, null, null, null, null);
        svc.recordAlert(e, ActivityLogService.ACTION_ALERT_RESOLVED, null, 33L, null, "elle Ali Veli");
        svc.recordAlert(alarm(null, EscalationService.TYPE_PORT_DOWN, "HIGH", null),
                ActivityLogService.ACTION_ALERT_TRIGGERED, null, null, null, null);

        List<ActivityLog> rows = saved(2);
        assertThat(rows).extracting(ActivityLog::getResultStatus).containsOnly("SUCCESS");
        assertThat(rows).extracting(ActivityLog::getMonitorType).containsOnly("UPTIME");   // erişilebilirlik = UPTIME
        assertThat(rows).extracting(ActivityLog::getActor).containsOnly("ali");
        assertThat(rows.get(1).getMonitorId()).isEqualTo(33L);
        assertThat(rows.get(1).getResultSummary()).endsWith("elle Ali Veli");
        assertThat(rows.get(1).getRequestId()).isEqualTo("rq-1");
    }

    @Test
    @DisplayName("Alarm tipi → aktivite türü: erişilebilirlik UPTIME, sertifika CERT, DOMAIN_EXPIRY HTTP, bilinmeyen ham (≤ 20)")
    void alertActivityType() {
        assertThat(ActivityLogService.alertActivityType("ACCESSIBILITY")).isEqualTo("UPTIME");
        assertThat(ActivityLogService.alertActivityType("EXPIRY")).isEqualTo("CERT");
        assertThat(ActivityLogService.alertActivityType("DOMAIN_EXPIRY")).isEqualTo("HTTP");
        assertThat(ActivityLogService.alertActivityType("DBQUERY_THRESHOLD")).isEqualTo("DBQUERY");
        assertThat(ActivityLogService.alertActivityType("SOMETHING_VERY_LONG_UNKNOWN_TYPE")).hasSize(20);
        assertThat(ActivityLogService.monitorIdFromContext("{\"a\":1,\"monitor_id\": \"42\"}")).isEqualTo(42L);
        assertThat(ActivityLogService.monitorIdFromContext("{\"a\":1}")).isNull();
        assertThat(ActivityLogService.monitorIdFromContext("x".repeat(20_000))).isNull();
    }

    @Test
    @DisplayName("Yazım ASLA fırlatmaz (alarm yolunu bozmaz): depo patlarsa yutulur")
    void alertWrite_neverThrows() {
        when(repo.save(any())).thenThrow(new RuntimeException("db down"));
        svc.recordAlert(alarm(70L, EscalationService.TYPE_PORT_DOWN, "HIGH", null),
                ActivityLogService.ACTION_ALERT_TRIGGERED, null, null, null, null);
        verify(repo).save(any());
    }

    // ── Değişiklik bağı + duraklatma / sürdürme ───────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Yapılandırma değişikliği: CONFIG_CHANGED (seq ile) + active true→false ise EK PAUSED satırı (aynı seq)")
    void configChange_withPause() {
        svc.recordConfigChange(ActivityLogService.PORT, 7L, "p", "h:1", 5L, "u", "active, intervalSeconds", 4,
                "{\"active\":{\"from\":true,\"to\":false},\"intervalSeconds\":{\"from\":60,\"to\":30}}");
        List<ActivityLog> rows = saved(2);
        assertThat(rows).extracting(ActivityLog::getAction).containsExactly("CONFIG_CHANGED", "PAUSED");
        assertThat(rows).extracting(ActivityLog::getChangeSeq).containsOnly(4);
        assertThat(rows.get(0).getResultSummary()).isEqualTo("active, intervalSeconds");
        assertThat(rows.get(1).getResultSummary()).isNull();
    }

    @Test
    @DisplayName("active false→true RESUMED; active değişmediyse yalnız CONFIG_CHANGED")
    void configChange_resumeAndNoActive() {
        svc.recordConfigChange(ActivityLogService.HTTP, 7L, "p", "u", 5L, "u", "active", 2,
                "{\"active\":{\"from\":false,\"to\":true}}");
        svc.recordConfigChange(ActivityLogService.HTTP, 7L, "p", "u", 5L, "u", "url", 3,
                "{\"url\":{\"from\":\"a\",\"to\":\"b\"}}");
        assertThat(saved(3)).extracting(ActivityLog::getAction).containsExactly("CONFIG_CHANGED", "RESUMED", "CONFIG_CHANGED");
    }

    @Test
    @DisplayName("activeChangedTo: yalnız gerçek boolean değişim; bozuk / eşit / alansız → null")
    void activeChangedTo_edges() {
        assertThat(ActivityLogService.activeChangedTo("{\"active\":{\"from\":true,\"to\":false}}")).isFalse();
        assertThat(ActivityLogService.activeChangedTo("{\"active\":{\"from\":null,\"to\":true}}")).isTrue();
        assertThat(ActivityLogService.activeChangedTo("{\"active\":{\"from\":true,\"to\":true}}")).isNull();
        assertThat(ActivityLogService.activeChangedTo("{\"inactive\":{\"from\":true,\"to\":false}}")).isNull();
        assertThat(ActivityLogService.activeChangedTo("{\"active\":")).isNull();
        assertThat(ActivityLogService.activeChangedTo(null)).isNull();
    }

    @Test
    @DisplayName("7/24 fırtına satırı: alarm + fırtına bağı")
    void lifecycleStormRefs() {
        svc.recordLifecycle(ActivityLogService.PING, 1L, "n", "h", 5L, "NOC_NOTIFIED", "system", "7/24",
                ActivityLogService.Refs.storm(80L, 9L));
        ActivityLog a = savedOne();
        assertThat(a.getAlertEventId()).isEqualTo(80L);
        assertThat(a.getStormId()).isEqualTo(9L);
        assertThat(a.getResultStatus()).isEqualTo("SUCCESS");
    }
}
