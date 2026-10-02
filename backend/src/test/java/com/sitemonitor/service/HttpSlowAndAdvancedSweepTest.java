package com.sitemonitor.service;

import com.sitemonitor.model.HttpMonitor;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.HttpCheckRepository;
import com.sitemonitor.repository.HttpMonitorRepository;
import com.sitemonitor.service.http.HttpRequestOptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * HTTP izlemesi — HTTP_SLOW ve gelişmiş istek alanlarının SWEEP kablolaması (2026-10-01, onaylı öneri 9).
 *
 * <p>Ana kilit GERİYE UYUM: yavaşlık alarmı KAPALI ve gelişmiş alanı olmayan izlemede sweep bugünküyle birebir aynıdır —
 * denetleyicinin ESKİ 7 argümanlı girişi çağrılır, HTTP_SLOW için {@code handleSweepResults} HİÇ çağrılmaz.
 * Yavaşlık yalnız AÇIKÇA açıldığında ve ölçüm varken (D-7) kalem üretir.
 */
class HttpSlowAndAdvancedSweepTest {

    private MonitoringOutageService outage;
    private HttpMonitorRepository httpRepo;
    private HttpCheckerService checker;
    private SchedulerService scheduler;

    @SuppressWarnings("unchecked")
    private static <T> T build(Class<T> type, Map<Class<?>, Object> provided) throws Exception {
        Constructor<?> c = java.util.Arrays.stream(type.getConstructors())
                .max(Comparator.comparingInt(Constructor::getParameterCount)).orElseThrow();
        Class<?>[] pt = c.getParameterTypes();
        Object[] args = new Object[pt.length];
        for (int i = 0; i < pt.length; i++) args[i] = provided.containsKey(pt[i]) ? provided.get(pt[i]) : mock(pt[i]);
        return (T) c.newInstance(args);
    }

    @BeforeEach
    void setUp() throws Exception {
        outage = mock(MonitoringOutageService.class);
        httpRepo = mock(HttpMonitorRepository.class);
        checker = mock(HttpCheckerService.class);
        AppSettingsService appSettings = mock(AppSettingsService.class);
        lenient().when(appSettings.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> i.getArgument(1));
        lenient().when(appSettings.getInt(anyString(), anyInt())).thenAnswer(i -> i.getArgument(1));
        lenient().when(appSettings.getDouble(anyString(), anyDouble())).thenAnswer(i -> i.getArgument(1));
        lenient().when(appSettings.getString(anyString(), any())).thenAnswer(i -> i.getArgument(1));
        Map<Class<?>, Object> provided = new HashMap<>();
        provided.put(MonitoringOutageService.class, outage);
        provided.put(AppSettingsService.class, appSettings);
        provided.put(HttpMonitorRepository.class, httpRepo);
        provided.put(HttpCheckerService.class, checker);
        provided.put(HttpCheckRepository.class, mock(HttpCheckRepository.class));
        provided.put(CertificateInventoryRepository.class, mock(CertificateInventoryRepository.class));
        scheduler = build(SchedulerService.class, provided);
        ReflectionTestUtils.setField(scheduler, "orphanCleanupIntervalMs", 300_000L);
    }

    private static Map<String, Object> raw(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    private static HttpMonitor monitor(boolean slowEnabled) {
        HttpMonitor m = new HttpMonitor();
        m.setId(41L); m.setName("Ödeme API"); m.setUrl("https://api.example.com/health"); m.setMethod("GET");
        m.setTeamId(7L); m.setActive(true);
        m.setSlowResponseEnabled(slowEnabled); m.setSlowThresholdMs(3000);
        return m;
    }

    private void legacyCheckReturns(Map<String, Object> r) {
        when(checker.check(anyString(), any(), any(), anyInt(), anyBoolean(), anyBoolean(), anyBoolean())).thenReturn(r);
    }

    private void runSweep(HttpMonitor m) {
        when(httpRepo.findByActiveTrue()).thenReturn(List.of(m));
        when(httpRepo.findAll()).thenReturn(List.of(m));
        ReflectionTestUtils.invokeMethod(scheduler, "runHttpChecksLocked");
    }

    @SuppressWarnings("unchecked")
    private List<MonitoringOutageService.SweepItem> sweepItemsFor(String type) {
        ArgumentCaptor<List<MonitoringOutageService.SweepItem>> c = ArgumentCaptor.forClass(List.class);
        verify(outage, atLeastOnce()).handleSweepResults(eq(type), c.capture());
        return c.getValue();
    }

    // ── Geriye uyum ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("eklentisiz + yavaşlık KAPALI: eski 7 argümanlı giriş çağrılır, HTTP_SLOW hattına HİÇ girilmez")
    void plainMonitor_sweepUnchanged() {
        legacyCheckReturns(raw("ok", true, "http_status", 200, "response_ms", 9000L));
        runSweep(monitor(false));

        assertThat(sweepItemsFor(EscalationService.TYPE_HTTP_DOWN)).hasSize(1);
        verify(outage, never()).handleSweepResults(eq(EscalationService.TYPE_HTTP_SLOW), anyList());
        verify(outage, never()).handleSweepResults(eq(EscalationService.TYPE_HTTP_SLOW), anyList(), anyBoolean());
        verify(checker, never()).check(anyString(), any(), any(), anyInt(), anyBoolean(), anyBoolean(), anyBoolean(),
                any(HttpRequestOptions.class));
    }

    @Test
    @DisplayName("elle kontrol (evaluateHttpNow): yavaşlık kapalıyken yalnız HTTP_DOWN kapanış uzlaştırması")
    void manualEvaluate_slowOff_onlyDown() {
        scheduler.evaluateHttpNow(monitor(false), raw("ok", true, "http_status", 200, "response_ms", 9000L));
        verify(outage).handleSweepResults(eq(EscalationService.TYPE_HTTP_DOWN), anyList(), eq(true));
        verify(outage, never()).handleSweepResults(eq(EscalationService.TYPE_HTTP_SLOW), anyList(), anyBoolean());
    }

    // ── HTTP_SLOW ────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("HTTP_SLOW: yavaşlık AÇIK ve süre eşiğin üstünde → DOWN kalem (sabit detail, eşik + süre bağlamda)")
    void slowOn_overThreshold_down() {
        legacyCheckReturns(raw("ok", true, "http_status", 200, "response_ms", 4200L));
        runSweep(monitor(true));

        List<MonitoringOutageService.SweepItem> slow = sweepItemsFor(EscalationService.TYPE_HTTP_SLOW);
        assertThat(slow).hasSize(1);
        MonitoringOutageService.SweepItem it = slow.get(0);
        assertThat(it.up()).isFalse();
        assertThat(it.domain()).isEqualTo("https://api.example.com/health");
        assertThat(it.detail()).as("teyit anahtarı her turda değişmesin").isEqualTo("> 3000 ms");
        assertThat(it.ctxExtra()).containsEntry("threshold_ms", 3000).containsEntry("response_ms", 4200L)
                .containsEntry("monitor_id", 41L).containsEntry("team_id", 7L);

        // yeniden ölçüm: taze istek — hızlandıysa up, hata varsa skipped (D-7)
        legacyCheckReturns(raw("ok", true, "http_status", 200, "response_ms", 800L));
        assertThat(it.recheck().get().get("status")).isEqualTo("up");
        legacyCheckReturns(raw("ok", false, "error", "Connect timed out", "response_ms", 5000L));
        assertThat(it.recheck().get().get("status")).isEqualTo("skipped");
    }

    @Test
    @DisplayName("HTTP_SLOW: yavaşlık AÇIK ve süre eşiğin altında → UP kalem")
    void slowOn_underThreshold_up() {
        legacyCheckReturns(raw("ok", true, "http_status", 200, "response_ms", 2999L));
        runSweep(monitor(true));
        List<MonitoringOutageService.SweepItem> slow = sweepItemsFor(EscalationService.TYPE_HTTP_SLOW);
        assertThat(slow).hasSize(1);
        assertThat(slow.get(0).up()).isTrue();
    }

    @Test
    @DisplayName("HTTP_SLOW (D-7): istek hatası / süre yok → ölçülemedi, kalem YOK (açık alarm kanıtsız kapanmaz)")
    void slowOn_error_noItem() {
        legacyCheckReturns(raw("ok", false, "error", "Connect timed out", "response_ms", 10_000L));
        runSweep(monitor(true));
        verify(outage, never()).handleSweepResults(eq(EscalationService.TYPE_HTTP_SLOW), anyList());
        assertThat(sweepItemsFor(EscalationService.TYPE_HTTP_DOWN).get(0).up()).isFalse();
    }

    @Test
    @DisplayName("elle kontrol: yavaşlık açıkken HTTP_SLOW yalnız manual=true (kapanış) ile değerlendirilir")
    void manualEvaluate_slowOn_manualOnly() {
        scheduler.evaluateHttpNow(monitor(true), raw("ok", true, "http_status", 200, "response_ms", 100L));
        verify(outage).handleSweepResults(eq(EscalationService.TYPE_HTTP_SLOW), anyList(), eq(true));
        verify(outage, never()).handleSweepResults(eq(EscalationService.TYPE_HTTP_SLOW), anyList());
    }

    // ── Gelişmiş istek ───────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("gelişmiş alanlı izleme: YENİ giriş, sırlar çözülmüş seçeneklerle; JSON hatası HTTP_DOWN bağlamına işaretlenir")
    void advancedMonitor_usesOptions_andFlagsJsonFailure() {
        SecretCipher cipher = mock(SecretCipher.class);
        when(cipher.decrypt("ENC-H")).thenReturn("X-Api-Key: k-1");
        when(cipher.decrypt("ENC-P")).thenReturn("p@ss");
        ReflectionTestUtils.setField(scheduler, "secretCipher", cipher);
        HttpMonitor m = monitor(false);
        m.setMethod("POST");
        m.setCustomHeadersEnc("ENC-H");
        m.setBasicAuthUser("izleme");
        m.setBasicAuthPassEnc("ENC-P");
        m.setRequestBody("{\"probe\":true}");
        m.setJsonPath("$.status");
        m.setJsonExpected("ok");
        when(checker.check(anyString(), any(), any(), anyInt(), anyBoolean(), anyBoolean(), anyBoolean(), any(HttpRequestOptions.class)))
                .thenReturn(raw("ok", false, "http_status", 200, "response_ms", 120L,
                        "error", "JSON doğrulaması başarısız: $.status = \"degraded\" (beklenen \"ok\")",
                        "json_assertion_failed", true));

        runSweep(m);

        ArgumentCaptor<HttpRequestOptions> opts = ArgumentCaptor.forClass(HttpRequestOptions.class);
        verify(checker).check(eq("https://api.example.com/health"), eq("POST"), any(), anyInt(), anyBoolean(), anyBoolean(),
                anyBoolean(), opts.capture());
        assertThat(opts.getValue().headers()).isEqualTo("X-Api-Key: k-1");
        assertThat(opts.getValue().basicAuthUser()).isEqualTo("izleme");
        assertThat(opts.getValue().basicAuthPass()).isEqualTo("p@ss");
        assertThat(opts.getValue().body()).isEqualTo("{\"probe\":true}");
        assertThat(opts.getValue().jsonPath()).isEqualTo("$.status");
        verify(checker, never()).check(anyString(), any(), any(), anyInt(), anyBoolean(), anyBoolean(), anyBoolean());

        MonitoringOutageService.SweepItem down = sweepItemsFor(EscalationService.TYPE_HTTP_DOWN).get(0);
        assertThat(down.up()).isFalse();
        assertThat(down.error()).startsWith("JSON doğrulaması başarısız");
        assertThat(down.ctxExtra()).containsEntry("json_assertion_failed", true).containsEntry("http_status", 200);
    }

    @Test
    @DisplayName("JSON hatası yavaşlık ölçümü sayılmaz (error var) — HTTP_SLOW kalemi üretmez")
    void jsonFailure_notASlowMeasurement() {
        HttpMonitor m = monitor(true);
        m.setJsonPath("$.status");
        when(checker.check(anyString(), any(), any(), anyInt(), anyBoolean(), anyBoolean(), anyBoolean(), any(HttpRequestOptions.class)))
                .thenReturn(raw("ok", false, "http_status", 200, "response_ms", 9000L,
                        "error", "JSON doğrulaması başarısız: $.status bulunamadı", "json_assertion_failed", true));
        runSweep(m);
        verify(outage, never()).handleSweepResults(eq(EscalationService.TYPE_HTTP_SLOW), anyList());
    }
}
