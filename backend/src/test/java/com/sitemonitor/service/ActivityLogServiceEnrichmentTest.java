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
    @DisplayName("Hata avı Y1: Keyword kelime BULUNDU ama adet koşulu sağlanmadı (ok=false) → WARNING (asla SUCCESS); "
            + "ok yoksa eski yol (bulundu + hatasız = başarılı)")
    void keywordConditionFailure_isNotSuccess() {
        // "içermemeli" (LT 1): kelime 1 kez bulundu, koşul sağlanmadı — HTTP 200, hata yok
        svc.recordCheck(ActivityLogService.KEYWORD, 7L, "kw", "https://x", 5L, false, "scheduler",
                new HashMap<>(Map.of("found", true, "ok", false, "count", 1, "http_status", 200, "response_ms", 40)),
                ActivityLogService.Refs.check(41L, 200, "KEYWORD_FOUND_FORBIDDEN", null));
        // "en az 3 kez" (GTE 3): 2 kez bulundu
        svc.recordCheck(ActivityLogService.KEYWORD, 7L, "kw", "https://x", 5L, false, "scheduler",
                new HashMap<>(Map.of("found", true, "ok", false, "count", 2, "http_status", 200)));
        // koşul sağlandı
        svc.recordCheck(ActivityLogService.KEYWORD, 7L, "kw", "https://x", 5L, false, "scheduler",
                new HashMap<>(Map.of("found", true, "ok", true, "count", 3, "http_status", 200)));
        // eski yol: ok anahtarı yok → found + hatasız = başarılı; HTTP'de ok yoksa başarısız (değişmedi)
        svc.recordCheck(ActivityLogService.KEYWORD, 7L, "kw", "https://x", 5L, false, "scheduler",
                new HashMap<>(Map.of("found", true, "http_status", 200)));
        svc.recordCheck(ActivityLogService.HTTP, 8L, "web", "https://y", 5L, false, "scheduler",
                new HashMap<>(Map.of("http_status", 200)));
        // ok=false + HTTP kodu yok + hata yok → ERROR (HTTP / KEYWORD ortak kural)
        svc.recordCheck(ActivityLogService.KEYWORD, 7L, "kw", "https://x", 5L, false, "scheduler",
                new HashMap<>(Map.of("found", true, "ok", false)));

        List<ActivityLog> rows = saved(6);
        assertThat(rows.get(0).getResultStatus()).isEqualTo("WARNING");
        assertThat(rows.get(0).getFailureReason()).isEqualTo("KEYWORD_FOUND_FORBIDDEN");
        assertThat(rows.get(1).getResultStatus()).isEqualTo("WARNING");
        assertThat(rows.get(2).getResultStatus()).isEqualTo("SUCCESS");
        assertThat(rows.get(3).getResultStatus()).isEqualTo("SUCCESS");
        assertThat(rows.get(4).getResultStatus()).isEqualTo("WARNING");
        assertThat(rows.get(5).getResultStatus()).isEqualTo("ERROR");
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

    // ── İnceleme düzeltmeleri (2026-11-05) ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("D5 fitWords: sığan metin aynen; uzun metin ≤ sınır + '…' ve kelime sınırı; boşluksuz metin kesilir; vekil çifti bölünmez")
    void fitWords_rules() {
        assertThat(ActivityLogService.fitWords("kısa", 300)).isEqualTo("kısa");
        assertThat(ActivityLogService.fitWords("x".repeat(300), 300)).hasSize(300);
        String words = "kelime ".repeat(80);   // 560
        String w = ActivityLogService.fitWords(words, 300);
        assertThat(w).hasSizeLessThanOrEqualTo(300).endsWith("…");
        assertThat(w.substring(0, w.length() - 1)).endsWith("kelime");
        String solid = "y".repeat(500);
        assertThat(ActivityLogService.fitWords(solid, 300)).hasSize(300).endsWith("…");
        String emoji = "a".repeat(298) + "😀" + "b".repeat(10);   // 299. konumda vekil çifti
        String e = ActivityLogService.fitWords(emoji, 300);
        assertThat(e).hasSizeLessThanOrEqualTo(300);
        assertThat(Character.isHighSurrogate(e.charAt(e.length() - 2))).isFalse();
    }

    @Test
    @DisplayName("D5: yaşam döngüsü özeti 500'e değil kolon boyuna (300) sığar")
    void lifecycleSummaryFitsColumn() {
        svc.recordLifecycle(ActivityLogService.HTTP, 1L, "n", "u", 5L, "CONFIG_CHANGED", "ops", "alan, ".repeat(100));
        assertThat(savedOne().getResultSummary()).hasSizeLessThanOrEqualTo(300).endsWith("…");
    }

    @Test
    @DisplayName("O2: erişilebilirlik alarm satırı hedefi alan:port (bağlamdaki port); diğer türler alarm anahtarı")
    void alertTarget_accessibilityPort() {
        assertThat(ActivityLogService.alertTarget("UPTIME", "x.com", "{\"port\":8443}")).isEqualTo("x.com:8443");
        assertThat(ActivityLogService.alertTarget("UPTIME", "x.com", "{\"port\": \"443\", \"x\":1}")).isEqualTo("x.com:443");
        assertThat(ActivityLogService.alertTarget("UPTIME", "x.com", null)).isEqualTo("x.com");
        assertThat(ActivityLogService.alertTarget("UPTIME", "x.com", "{\"port\":99999}")).isEqualTo("x.com");
        assertThat(ActivityLogService.alertTarget("HTTP", "https://x.com/", "{\"port\":8443}")).isEqualTo("https://x.com/");
    }

    @Test
    @DisplayName("O2: açılış satırından kimlik okuması yalnız sertifika / erişilebilirlik onay-kapanış satırında (izleme alarmında sorgu yok)")
    void triggeredLookupOnlyForCertAndUptime() {
        when(repo.triggeredMonitorId(eq(50L), any())).thenReturn(List.of(42L));
        AlertEvent cert = new AlertEvent();
        cert.setId(50L);
        cert.setDomain("c.example.com");
        cert.setAlertType("EXPIRY");
        cert.setAlertLevel("HIGH");
        svc.recordAlert(cert, ActivityLogService.ACTION_ALERT_ACKNOWLEDGED, null, null, "ayse", null);

        AlertEvent http = new AlertEvent();
        http.setId(51L);
        http.setDomain("https://h.example.com/");
        http.setAlertType(EscalationService.TYPE_HTTP_DOWN);
        http.setAlertLevel("HIGH");
        http.setContextJson("{\"team_id\":7}");
        svc.recordAlert(http, ActivityLogService.ACTION_ALERT_RESOLVED, null, null, "system", null);
        svc.recordAlert(cert, ActivityLogService.ACTION_ALERT_TRIGGERED, null, null, "system", null);

        List<ActivityLog> rows = saved(3);
        assertThat(rows.get(0).getMonitorId()).isEqualTo(42L);
        assertThat(rows.get(1).getMonitorId()).isNull();
        verify(repo, times(1)).triggeredMonitorId(any(), any());   // HTTP ve açılış satırı sorgulamaz
    }

    @Test
    @DisplayName("Sözleşme Y1: HTTP neden kodu tanıdan (STATUS_MISMATCH → HTTP_STATUS, BODY_ASSERTION), error yoksa HTTP kodundan; sağlıklı satırda yok")
    void httpReasonFromDiagnosis() {
        assertThat(ActivityLogService.httpReason(false, null, 503, "{\"kind\":\"STATUS_MISMATCH\",\"http_status\":503}"))
                .isEqualTo("HTTP_STATUS");
        assertThat(ActivityLogService.httpReason(false, "JSON doğrulaması başarısız: $.status beklenen UP", 200,
                "{\"kind\":\"BODY_ASSERTION\"}")).isEqualTo("BODY_ASSERTION");
        assertThat(ActivityLogService.httpReason(false, null, 404, null)).isEqualTo("HTTP_STATUS");
        assertThat(ActivityLogService.httpReason(false, "java.net.UnknownHostException: x.invalid", null, null))
                .isEqualTo("DNS_RESOLVE");
        assertThat(ActivityLogService.httpReason(true, null, 200, null)).isNull();
        HttpCheck c = new HttpCheck();
        c.setOk(false);
        c.setHttpStatus(503);
        c.setErrorDetail("{\"kind\":\"STATUS_MISMATCH\"}");
        assertThat(ActivityLogService.refs(c).failureReason()).isEqualTo("HTTP_STATUS");
    }

    @Test
    @DisplayName("FR D5: alarm satırı result_detail'e makinece okunur ayrıntı yazar (tür, seviye, önceki seviye, kapanış, kod)")
    void alertInfoJsonWritten() {
        AlertEvent e = alarm(60L, EscalationService.TYPE_PORT_DOWN, "CRITICAL", null);
        svc.recordAlert(e, ActivityLogService.ACTION_ALERT_ESCALATED, "CRITICAL", 9L, "system", "HIGH → CRITICAL",
                ActivityLogService.AlertInfo.escalated("HIGH"));
        svc.recordAlert(e, ActivityLogService.ACTION_ALERT_RESOLVED, null, 9L, "ayse", "elle Ayşe",
                ActivityLogService.AlertInfo.closed(ActivityLogService.CLOSED_MANUAL, "Ayşe"));
        svc.recordAlert(e, ActivityLogService.ACTION_ALERT_ACKNOWLEDGED, null, 9L, "ayse", null);
        List<ActivityLog> rows = saved(3);
        assertThat(rows.get(0).getResultDetail())
                .isEqualTo("{\"alert_type\":\"PORT_DOWN\",\"level\":\"CRITICAL\",\"from_level\":\"HIGH\"}");
        assertThat(rows.get(1).getResultDetail())
                .isEqualTo("{\"alert_type\":\"PORT_DOWN\",\"level\":\"CRITICAL\",\"closed_by\":\"manual\",\"closer\":\"Ayşe\"}");
        assertThat(rows.get(2).getResultDetail()).isEqualTo("{\"alert_type\":\"PORT_DOWN\",\"level\":\"CRITICAL\"}");
        assertThat(rows.get(0).getResultSummary()).as("Türkçe özet aynen kalır (CSV, eski istemci)").contains("HIGH → CRITICAL");
    }
}
