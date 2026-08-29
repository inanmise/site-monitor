package com.sitemonitor.service;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CertificateCheckerServiceTest {

    @Mock
    private ChainValidationService chainValidationService;

    @Mock
    private DnsCheckerService dnsCheckerService;

    @Mock
    private TrustEvaluator trustEvaluator;

    private CertificateCheckerService service;

    @BeforeEach
    void setUp() {
        // SsrfGuard'ı MOCK'la (validate no-op) — bu test'ler sahte/çözülmeyen domain'lerle çalışır; gerçek guard
        // DNS'e sokup bloklardı. SsrfGuard'ın kendisi SsrfGuardTest'te doğrulanır.
        service = new CertificateCheckerService(chainValidationService, dnsCheckerService, new ObjectMapper(),
                trustEvaluator, mock(SsrfGuard.class), new ProxySettings());
    }

    // ── Kayıt bazlı zaman aşımı ────────────────────────────────────────────────

    /**
     * Zaman aşımı KAYIT BAZLI olmalı.
     *
     * <p>Global {@code check-timeout-seconds} 6 sn. Yavaş ama ÇALIŞAN bir iç hedef bu sürede
     * yetişemiyordu ve kullanıcının tek çaresi TÜM envanteri yavaşlatan global ayarı
     * büyütmekti — tek kayıt için süre veremiyordu.
     */
    @Test
    @DisplayName("Kayıt zaman aşımı verirse O kullanılır; vermezse global ayar")
    void recordTimeoutOverridesGlobal() {
        ReflectionTestUtils.setField(service, "timeoutSeconds", 6);
        ReflectionTestUtils.setField(service, "tlsMode", "browser");

        assertThat(service.resolveOptions(false, null, "a.example.com", 25).timeoutSeconds()).isEqualTo(25);
        assertThat(service.resolveOptions(false, null, "a.example.com", null).timeoutSeconds()).isEqualTo(6);
    }

    @Test
    @DisplayName("0/negatif 'sınırsız' DEĞİL geçersizdir → global ayara düşer")
    void nonPositiveTimeoutFallsBackToGlobal() {
        ReflectionTestUtils.setField(service, "timeoutSeconds", 6);
        ReflectionTestUtils.setField(service, "tlsMode", "browser");

        // Sıfır zaman aşımı her kontrolü ANINDA düşürürdü; "sınırsız" diye okumak da
        // süpürmeyi tek bir yanıt vermeyen hedefte kilitlerdi.
        assertThat(service.resolveOptions(false, null, "a.example.com", 0).timeoutSeconds()).isEqualTo(6);
        assertThat(service.resolveOptions(false, null, "a.example.com", -5).timeoutSeconds()).isEqualTo(6);
    }

    @Test
    @DisplayName("Kayıt zaman aşımı TAVANLA sınırlı — tek kayıt süpürmeyi kilitlemesin")
    void recordTimeoutIsCapped() {
        ReflectionTestUtils.setField(service, "timeoutSeconds", 6);
        ReflectionTestUtils.setField(service, "tlsMode", "browser");

        assertThat(service.resolveOptions(false, null, "a.example.com", 9999).timeoutSeconds())
                .isEqualTo(CertificateCheckerService.MAX_TIMEOUT_SECONDS);
    }

    // ── SAN serialization ──────────────────────────────────────────────────────

    @Test
    @DisplayName("serializeSan and deserializeSan round-trip")
    void serializeSan_deserializeSan_roundTrip() {
        List<String> san = List.of("example.com", "www.example.com", "*.example.com");
        String json = service.serializeSan(san);
        List<String> result = service.deserializeSan(json);
        assertThat(result).containsExactlyElementsOf(san);
    }

    @Test
    @DisplayName("serializeSan with empty list returns '[]'")
    void serializeSan_emptyList_returnsEmptyJson() {
        String json = service.serializeSan(List.of());
        assertThat(json).isEqualTo("[]");
    }

    @Test
    @DisplayName("deserializeSan with null input returns empty list")
    void deserializeSan_nullInput_returnsEmpty() {
        assertThat(service.deserializeSan(null)).isEmpty();
    }

    @Test
    @DisplayName("deserializeSan with blank input returns empty list")
    void deserializeSan_blankInput_returnsEmpty() {
        assertThat(service.deserializeSan("   ")).isEmpty();
    }

    @Test
    @DisplayName("deserializeSan with invalid JSON returns empty list")
    void deserializeSan_invalidJson_returnsEmpty() {
        assertThat(service.deserializeSan("{not-valid-json}")).isEmpty();
    }

    @Test
    @DisplayName("deserializeSan with single-entry JSON list")
    void deserializeSan_singleEntry_returnsList() {
        List<String> result = service.deserializeSan("[\"example.com\"]");
        assertThat(result).containsExactly("example.com");
    }

    // ── Error check path ──────────────────────────────────────────────────────

    @Test
    @DisplayName("check with unreachable domain returns error map with required keys")
    void check_unreachableDomain_returnsErrorMap() {
        // Kapalı localhost portu → deterministik connection-refused (NETWORK error),
        // DNS'ten bağımsız. (Bazı kurumsal resolver'lar .invalid'i wildcard'a çözer;
        // trust-all çekimle ÇÖZÜLEBİLEN bir host artık cert okuyup "valid" döner — bu
        // yüzden kesin bir başarısızlık hedefi gerekir.)
        Map<String, Object> result = service.check("localhost", 1);

        assertThat(result.get("status")).isEqualTo("error");
        assertThat(result.get("domain")).isEqualTo("localhost");
        assertThat(result.get("error")).isNotNull();
        assertThat(result.get("warning")).isEqualTo(true);
        assertThat(result.get("chain_status")).isEqualTo("UNKNOWN");
        assertThat(result.get("revocation_status")).isEqualTo("UNKNOWN");
        assertThat(result.get("deployment_status")).isEqualTo("UNKNOWN");
        assertThat(result.get("fingerprint")).isNull();
        assertThat(result.get("checked_at")).isNotNull();
    }

    @Test
    @DisplayName("check always returns san key (empty list on error)")
    void check_error_sanIsEmptyList() {
        Map<String, Object> result = service.check("localhost", 1);
        Object san = result.get("san");
        assertThat(san).isNotNull().isInstanceOf(List.class);
    }

    @Test
    @DisplayName("check with connection refused returns error map")
    void check_connectionRefused_returnsErrorMap() {
        // Port 1 is almost certainly not open on localhost
        Map<String, Object> result = service.check("localhost", 1);
        assertThat(result.get("status")).isEqualTo("error");
        assertThat((String) result.get("error")).isNotBlank();
    }

    // ── Retry-on-transient ─────────────────────────────────────────────────────

    /** Helper: spy on service with retry enabled and stub tryCheckOnce. */
    private CertificateCheckerService spyWithRetry(boolean enabled) {
        CertificateCheckerService spy = spy(service);
        ReflectionTestUtils.setField(spy, "retryOnTransient", enabled);
        ReflectionTestUtils.setField(spy, "maxAttempts", 2);
        ReflectionTestUtils.setField(spy, "retryDelayMs", 5L);
        ReflectionTestUtils.setField(spy, "tlsMode", "browser");
        return spy;
    }

    private static CertificateCheckerService.CheckOptions anyOpts() {
        return any(CertificateCheckerService.CheckOptions.class);
    }

    private static Map<String, Object> err(String cls, String msg) {
        Map<String, Object> m = new HashMap<>();
        m.put("status", "error");
        m.put("error_class", cls);
        m.put("error", msg);
        m.put("domain", "test.example.com");
        return m;
    }

    private static Map<String, Object> ok() {
        Map<String, Object> m = new HashMap<>();
        m.put("status", "ok");
        m.put("domain", "test.example.com");
        m.put("days_remaining", 100);
        return m;
    }

    @Test
    @DisplayName("check retries transient NETWORK error and reports recovery on success")
    void check_transientNetworkError_retriesAndRecovers() {
        CertificateCheckerService spy = spyWithRetry(true);
        doReturn(err("NETWORK", "Socket error: Connection reset"))
                .doReturn(ok())
                .when(spy).tryCheckOnce(eq("test.example.com"), eq(443), anyOpts());

        Map<String, Object> result = spy.check("test.example.com", 443);

        verify(spy, times(2)).tryCheckOnce(eq("test.example.com"), eq(443), anyOpts());
        assertThat(result.get("status")).isEqualTo("ok");
        assertThat(result.get("retry_recovered")).isEqualTo(true);
    }

    @Test
    @DisplayName("check retries persistent NETWORK error and marks retry_attempted")
    void check_persistentNetworkError_retriesThenFails() {
        CertificateCheckerService spy = spyWithRetry(true);
        doReturn(err("NETWORK", "Socket error: Connection reset"))
                .when(spy).tryCheckOnce(eq("test.example.com"), eq(443), anyOpts());

        Map<String, Object> result = spy.check("test.example.com", 443);

        verify(spy, times(2)).tryCheckOnce(eq("test.example.com"), eq(443), anyOpts());
        assertThat(result.get("status")).isEqualTo("error");
        assertThat(result.get("retry_attempted")).isEqualTo(true);
    }

    /**
     * {@code max-attempts} ARTIK GERÇEKTEN uygulanıyor.
     *
     * <p>Eskiden burada döngü yoktu: ayar yalnızca "yeniden deneme açık mı" kapısıydı
     * ({@code maxAttempts < 2}) ve 2'den büyük her değer SESSİZCE yok sayılıyordu — 3 yazan da
     * 10 yazan da 2 deneme alıyordu. Ayarın adı ile davranışı ayrışmıştı; kullanıcı hata
     * mesajındaki deneme sayısını sorunca ortaya çıktı.
     */
    @Test
    @DisplayName("max-attempts KAÇ diyorsa o kadar denenir (3 → 3 deneme)")
    void maxAttemptsIsHonoured() {
        CertificateCheckerService spy = spyWithRetry(true);
        ReflectionTestUtils.setField(spy, "maxAttempts", 3);
        doReturn(err("NETWORK", "Socket error: Connection reset"))
                .when(spy).tryCheckOnce(eq("test.example.com"), eq(443), anyOpts());

        Map<String, Object> result = spy.check("test.example.com", 443);

        verify(spy, times(3)).tryCheckOnce(eq("test.example.com"), eq(443), anyOpts());
        assertThat(result.get("attempts_total")).isEqualTo(3);
        // Mesajdaki rakam SAYILAN denemeden gelir, sabit bir literalden değil.
        assertThat((String) result.get("error")).contains("3 attempts");
    }

    @Test
    @DisplayName("Ara denemede düzelirse KALAN denemeler koşmaz")
    void recoversMidChainAndStops() {
        CertificateCheckerService spy = spyWithRetry(true);
        ReflectionTestUtils.setField(spy, "maxAttempts", 4);
        doReturn(err("NETWORK", "Socket error: Connection reset"))
                .doReturn(ok())
                .when(spy).tryCheckOnce(eq("test.example.com"), eq(443), anyOpts());

        Map<String, Object> result = spy.check("test.example.com", 443);

        verify(spy, times(2)).tryCheckOnce(eq("test.example.com"), eq(443), anyOpts());
        assertThat(result.get("retry_recovered")).isEqualTo(true);
        assertThat(result.get("attempts_total")).isEqualTo(2);
    }

    @Test
    @DisplayName("Deneme sayısı TAVANLA sınırlı — tek hedef süpürmeyi dakikalarca kilitlemesin")
    void attemptsAreCapped() {
        CertificateCheckerService spy = spyWithRetry(true);
        ReflectionTestUtils.setField(spy, "maxAttempts", 99);
        doReturn(err("NETWORK", "Socket error: Connection reset"))
                .when(spy).tryCheckOnce(eq("test.example.com"), eq(443), anyOpts());

        Map<String, Object> result = spy.check("test.example.com", 443);

        verify(spy, times(CertificateCheckerService.MAX_ATTEMPTS_CAP))
                .tryCheckOnce(eq("test.example.com"), eq(443), anyOpts());
        assertThat(result.get("attempts_total")).isEqualTo(CertificateCheckerService.MAX_ATTEMPTS_CAP);
    }

    @Test
    @DisplayName("check does NOT retry SSL handshake errors (real cert problem)")
    void check_sslHandshakeError_noRetry() {
        CertificateCheckerService spy = spyWithRetry(true);
        doReturn(err("SSL", "SSL handshake: unable to find valid certification path"))
                .when(spy).tryCheckOnce(eq("test.example.com"), eq(443), anyOpts());

        Map<String, Object> result = spy.check("test.example.com", 443);

        verify(spy, times(1)).tryCheckOnce(eq("test.example.com"), eq(443), anyOpts());
        assertThat(result.get("status")).isEqualTo("error");
        assertThat(result).doesNotContainKey("retry_attempted");
        assertThat(result).doesNotContainKey("retry_recovered");
    }

    @Test
    @DisplayName("check does NOT retry DNS errors (won't fix in 1s)")
    void check_dnsError_noRetry() {
        CertificateCheckerService spy = spyWithRetry(true);
        doReturn(err("DNS", "Domain resolution failed"))
                .when(spy).tryCheckOnce(eq("test.example.com"), eq(443), anyOpts());

        Map<String, Object> result = spy.check("test.example.com", 443);

        verify(spy, times(1)).tryCheckOnce(eq("test.example.com"), eq(443), anyOpts());
        assertThat(result.get("status")).isEqualTo("error");
    }

    @Test
    @DisplayName("check with retry disabled via config makes only one attempt")
    void check_retryDisabled_singleAttempt() {
        CertificateCheckerService spy = spyWithRetry(false);
        doReturn(err("NETWORK", "Socket error: Connection reset"))
                .when(spy).tryCheckOnce(eq("test.example.com"), eq(443), anyOpts());

        Map<String, Object> result = spy.check("test.example.com", 443);

        verify(spy, times(1)).tryCheckOnce(eq("test.example.com"), eq(443), anyOpts());
        assertThat(result.get("status")).isEqualTo("error");
        assertThat(result).doesNotContainKey("retry_attempted");
    }

    // ── Fallback retry (alternate combo) ───────────────────────────────────────

    private static Map<String, Object> errAtStage(String cls, String msg, String stage) {
        Map<String, Object> m = err(cls, msg);
        m.put("error_stage", stage);
        return m;
    }

    @Test
    @DisplayName("fallback: tls-handshake stall flips TLS mode on retry, same path")
    void fallback_handshakeStall_flipsTlsMode() {
        CertificateCheckerService spy = spyWithRetry(true);
        ReflectionTestUtils.setField(spy, "retryFallback", true);
        doReturn(errAtStage("NETWORK", "Connection timeout after 6s", "tls-handshake"))
                .when(spy).tryCheckOnce(eq("test.example.com"), eq(443), anyOpts());

        Map<String, Object> result = spy.check("test.example.com", 443);

        ArgumentCaptor<CertificateCheckerService.CheckOptions> captor =
                ArgumentCaptor.forClass(CertificateCheckerService.CheckOptions.class);
        verify(spy, times(2)).tryCheckOnce(eq("test.example.com"), eq(443), captor.capture());
        assertThat(captor.getAllValues().get(0).tlsMode()).isEqualTo("browser");
        assertThat(captor.getAllValues().get(1).tlsMode()).isEqualTo("default");
        assertThat(captor.getAllValues().get(1).viaProxy()).isFalse();
        assertThat(result.get("retry_fallback")).isEqualTo("direct/browser→direct/default");
    }

    @Test
    @DisplayName("fallback: direct TCP reset switches to proxy when proxy is configured")
    void fallback_directTcpReset_switchesToProxy() {
        CertificateCheckerService spy = spyWithRetry(true);
        ReflectionTestUtils.setField(spy, "retryFallback", true);
        ReflectionTestUtils.setField(spy, "proxyHost", "proxy.local");
        ReflectionTestUtils.setField(spy, "proxyPort", 8080);
        ReflectionTestUtils.setField(spy, "autoProxyFallback", true);   // opt-in özellik (varsayılan KAPALI)
        doReturn(errAtStage("NETWORK", "Socket error: Connection reset", "tcp-connect"))
                .doReturn(ok())
                .when(spy).tryCheckOnce(eq("test.example.com"), eq(443), anyOpts());

        Map<String, Object> result = spy.check("test.example.com", 443);

        ArgumentCaptor<CertificateCheckerService.CheckOptions> captor =
                ArgumentCaptor.forClass(CertificateCheckerService.CheckOptions.class);
        verify(spy, times(2)).tryCheckOnce(eq("test.example.com"), eq(443), captor.capture());
        assertThat(captor.getAllValues().get(0).viaProxy()).isFalse();
        assertThat(captor.getAllValues().get(1).viaProxy()).isTrue();
        assertThat(captor.getAllValues().get(1).tlsMode()).isEqualTo("browser");
        assertThat(result.get("retry_recovered")).isEqualTo(true);
        assertThat(result.get("retry_fallback")).isEqualTo("direct/browser→proxy/browser");
    }

    @Test
    @DisplayName("fallback: auto-fallback KAPALI (varsayılan) → direct TCP timeout proxy'ye DÜŞMEZ (use_proxy=false onurlanır)")
    void fallback_autoFallbackOff_staysDirect() {
        CertificateCheckerService spy = spyWithRetry(true);
        ReflectionTestUtils.setField(spy, "retryFallback", true);
        ReflectionTestUtils.setField(spy, "proxyHost", "proxy.local");
        ReflectionTestUtils.setField(spy, "proxyPort", 8080);
        // autoProxyFallback VARSAYILAN false → direct timeout'ta proxy'ye düşülmez.
        doReturn(errAtStage("NETWORK", "Connection timeout after 6s", "tcp-connect"))
                .when(spy).tryCheckOnce(eq("test.example.com"), eq(443), anyOpts());

        spy.check("test.example.com", 443);

        ArgumentCaptor<CertificateCheckerService.CheckOptions> captor =
                ArgumentCaptor.forClass(CertificateCheckerService.CheckOptions.class);
        verify(spy, times(2)).tryCheckOnce(eq("test.example.com"), eq(443), captor.capture());
        assertThat(captor.getAllValues().get(0).viaProxy()).isFalse();
        assertThat(captor.getAllValues().get(1).viaProxy()).isFalse();   // proxy'ye DÜŞMEDİ
    }

    @Test
    @DisplayName("fallback: proxy tunnel failure switches to direct")
    void fallback_proxyTunnelFailure_switchesToDirect() {
        CertificateCheckerService spy = spyWithRetry(true);
        ReflectionTestUtils.setField(spy, "retryFallback", true);
        ReflectionTestUtils.setField(spy, "proxyHost", "proxy.local");
        ReflectionTestUtils.setField(spy, "proxyPort", 8080);
        doReturn(errAtStage("NETWORK",
                        "I/O error: Proxy TCP connect failed: proxy.local:8080 — Connection refused",
                        "proxy-connect"))
                .doReturn(ok())
                .when(spy).tryCheckOnce(eq("test.example.com"), eq(443), anyOpts());

        Map<String, Object> result = spy.check("test.example.com", 443, true);

        ArgumentCaptor<CertificateCheckerService.CheckOptions> captor =
                ArgumentCaptor.forClass(CertificateCheckerService.CheckOptions.class);
        verify(spy, times(2)).tryCheckOnce(eq("test.example.com"), eq(443), captor.capture());
        assertThat(captor.getAllValues().get(0).viaProxy()).isTrue();
        assertThat(captor.getAllValues().get(1).viaProxy()).isFalse();
        assertThat(result.get("retry_recovered")).isEqualTo(true);
    }

    @Test
    @DisplayName("fallback disabled: retry repeats identical parameters")
    void fallback_disabled_identicalRetry() {
        CertificateCheckerService spy = spyWithRetry(true);
        ReflectionTestUtils.setField(spy, "retryFallback", false);
        doReturn(errAtStage("NETWORK", "Connection timeout after 6s", "tls-handshake"))
                .when(spy).tryCheckOnce(eq("test.example.com"), eq(443), anyOpts());

        Map<String, Object> result = spy.check("test.example.com", 443);

        ArgumentCaptor<CertificateCheckerService.CheckOptions> captor =
                ArgumentCaptor.forClass(CertificateCheckerService.CheckOptions.class);
        verify(spy, times(2)).tryCheckOnce(eq("test.example.com"), eq(443), captor.capture());
        assertThat(captor.getAllValues().get(0)).isEqualTo(captor.getAllValues().get(1));
        assertThat(result).doesNotContainKey("retry_fallback");
    }

    @Test
    @DisplayName("per-domain tlsModeOverride reaches the attempt options")
    void check_tlsModeOverride_appliesToOptions() {
        CertificateCheckerService spy = spyWithRetry(true);
        doReturn(ok()).when(spy).tryCheckOnce(eq("test.example.com"), eq(443), anyOpts());

        spy.check("test.example.com", 443, false, "default");

        ArgumentCaptor<CertificateCheckerService.CheckOptions> captor =
                ArgumentCaptor.forClass(CertificateCheckerService.CheckOptions.class);
        verify(spy).tryCheckOnce(eq("test.example.com"), eq(443), captor.capture());
        assertThat(captor.getValue().tlsMode()).isEqualTo("default");
    }

    @Test
    @DisplayName("real error result carries via, tls_mode_used, error_stage and resolved_ips")
    void tryCheckOnce_errorResult_carriesDiagnosticKeys() {
        ReflectionTestUtils.setField(service, "tlsMode", "browser");
        ReflectionTestUtils.setField(service, "timeoutSeconds", 2);

        Map<String, Object> result = service.check("localhost", 1);

        assertThat(result.get("status")).isEqualTo("error");
        assertThat(result.get("via")).isEqualTo("direct");
        assertThat(result.get("tls_mode_used")).isEqualTo("browser");
        assertThat(result.get("error_stage")).isEqualTo("tcp-connect");
        assertThat(result).containsKeys("resolved_ips", "elapsed_ms",
                "source_ip", "source_port", "peer_ip", "peer_port");
    }

    @Test
    @DisplayName("chooseFallback decision table covers all rules")
    void chooseFallback_decisionTable() {
        ReflectionTestUtils.setField(service, "proxyHost", "proxy.local");
        ReflectionTestUtils.setField(service, "proxyPort", 8080);
        ReflectionTestUtils.setField(service, "autoProxyFallback", true);   // rule #2 için (varsayılan KAPALI)
        var direct = new CertificateCheckerService.CheckOptions(false, "browser", 6, false);
        var viaProxy = new CertificateCheckerService.CheckOptions(true, "default", 6, false);

        // Rule 1: handshake stall → flip TLS mode
        assertThat(service.chooseFallback(direct,
                errAtStage("NETWORK", "Connection timeout after 6s", "tls-handshake"), "x.com"))
                .isEqualTo(direct.withTlsMode("default"));
        assertThat(service.chooseFallback(viaProxy,
                errAtStage("NETWORK", "Socket error: Connection reset", "tls-handshake"), "x.com"))
                .isEqualTo(viaProxy.withTlsMode("browser"));

        // Rule 2: direct TCP block + proxy configured → via proxy
        assertThat(service.chooseFallback(direct,
                errAtStage("NETWORK", "Connection refused/unreachable: connect", "tcp-connect"), "x.com"))
                .isEqualTo(direct.withViaProxy(true));

        // Rule 3: proxy tunnel failure → direct
        assertThat(service.chooseFallback(viaProxy,
                errAtStage("NETWORK", "I/O error: Proxy CONNECT read failed: Read timed out", "proxy-connect"), "x.com"))
                .isEqualTo(viaProxy.withViaProxy(false));

        // Rule 4: anything else → unchanged
        assertThat(service.chooseFallback(direct,
                err("NETWORK", "Socket error: Connection reset"), "x.com"))
                .isEqualTo(direct);

        // Auto-fallback KAPALI (varsayılan): direct TCP timeout'ta bile proxy'ye DÜŞMEZ →
        // per-domain "Proxy Üzerinden Kontrol Et = Hayır" tercihi kesin onurlanır.
        ReflectionTestUtils.setField(service, "autoProxyFallback", false);
        assertThat(service.chooseFallback(direct,
                errAtStage("NETWORK", "Connection timeout after 6s", "tcp-connect"), "x.com"))
                .isEqualTo(direct);
    }

    @Test
    @DisplayName("isTransientError flags Connection reset / timeout / refused, ignores SSL+DNS")
    void isTransientError_classifiesCorrectly() {
        assertThat(service.isTransientError(err("NETWORK", "Socket error: Connection reset"))).isTrue();
        assertThat(service.isTransientError(err("NETWORK", "Connection timeout after 6s"))).isTrue();
        assertThat(service.isTransientError(err("NETWORK", "Connection refused/unreachable"))).isTrue();
        assertThat(service.isTransientError(err("NETWORK", "No route to host"))).isTrue();

        assertThat(service.isTransientError(err("SSL", "SSL handshake: anything"))).isFalse();
        assertThat(service.isTransientError(err("DNS", "Domain resolution failed"))).isFalse();
        assertThat(service.isTransientError(err("UNKNOWN", "Some weird failure"))).isFalse();

        Map<String, Object> okResult = ok();
        assertThat(service.isTransientError(okResult)).isFalse();
    }

    // ── Sertifika süre-bitişi matematiği (parseLeafCert: days_remaining + warning eşiği) ──────────
    // Çekirdek izleme kararı: kaç gün kaldı ve uyarı eşiğinde mi. notAfter'ı runtime'da üretilen
    // (BouncyCastle) gerçek X509 üstünde kesin ofsetlere koyup private parseLeafCert'i reflection'la
    // çağırır — ağ/handshake yok, deterministik. warningDays test içinde 30'a sabitlenir.

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseLeaf(X509Certificate cert) {
        ReflectionTestUtils.setField(service, "warningDays", 30);
        return (Map<String, Object>) ReflectionTestUtils.invokeMethod(service, "parseLeafCert", cert, "leaf.example.com");
    }

    @Test
    @DisplayName("parseLeafCert: bitişe çok var (warningDays+5) → uyarı yok, status=valid")
    void parseLeafCert_farFuture_noWarning() {
        Map<String, Object> r = parseLeaf(certExpiringInHours(35L * 24 + 12));   // 35 gün
        assertThat(r.get("days_remaining")).isEqualTo(35);
        assertThat(r.get("warning")).isEqualTo(false);
        assertThat(r.get("status")).isEqualTo("valid");
    }

    @Test
    @DisplayName("parseLeafCert: gün == warningDays+1 → hâlâ valid (eşik <= sınırının dışı)")
    void parseLeafCert_daysEqualsWarningDaysPlusOne_isValid() {
        Map<String, Object> r = parseLeaf(certExpiringInHours(31L * 24 + 12));   // 31 gün
        assertThat(r.get("days_remaining")).isEqualTo(31);
        assertThat(r.get("warning")).isEqualTo(false);
        assertThat(r.get("status")).isEqualTo("valid");
    }

    @Test
    @DisplayName("parseLeafCert: gün == warningDays → uyarı (days<=warningDays kenarı)")
    void parseLeafCert_daysEqualsWarningDays_isWarning() {
        Map<String, Object> r = parseLeaf(certExpiringInHours(30L * 24 + 12));   // 30 gün
        assertThat(r.get("days_remaining")).isEqualTo(30);
        assertThat(r.get("warning")).isEqualTo(true);
        assertThat(r.get("status")).isEqualTo("warning");
    }

    @Test
    @DisplayName("parseLeafCert: bugün doluyor (24 saatten az kaldı) → days=0 + uyarı")
    void parseLeafCert_expiresToday_zeroDaysWarning() {
        Map<String, Object> r = parseLeaf(certExpiringInHours(12));             // ~12 saat
        assertThat(r.get("days_remaining")).isEqualTo(0);
        assertThat(r.get("warning")).isEqualTo(true);
        assertThat(r.get("status")).isEqualTo("warning");
    }

    @Test
    @DisplayName("parseLeafCert: zaten süresi geçmiş → days negatif + uyarı")
    void parseLeafCert_alreadyExpired_negativeDaysWarning() {
        Map<String, Object> r = parseLeaf(certExpiringInHours(-5L * 24));       // 5 gün önce doldu
        assertThat((Integer) r.get("days_remaining")).isNegative();
        assertThat(r.get("warning")).isEqualTo(true);
        assertThat(r.get("status")).isEqualTo("warning");
    }

    /** notAfter = now + {hoursFromNow} saat olan, runtime'da üretilmiş self-signed X509 (SAN=leaf.example.com). */
    private static X509Certificate certExpiringInHours(long hoursFromNow) {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(2048);
            KeyPair kp = kpg.generateKeyPair();
            X500Name name = new X500Name("CN=leaf.example.com, O=SiteMonitor Test");
            Instant now = Instant.now();
            JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                    name, BigInteger.valueOf(System.nanoTime()),
                    Date.from(now.minus(365, ChronoUnit.DAYS)), Date.from(now.plus(hoursFromNow, ChronoUnit.HOURS)),
                    name, kp.getPublic());
            builder.addExtension(Extension.subjectAlternativeName, false,
                    new GeneralNames(new GeneralName(GeneralName.dNSName, "leaf.example.com")));
            ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(kp.getPrivate());
            return new JcaX509CertificateConverter().getCertificate(builder.build(signer));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // -- Soket sizintisi simetrisi (D14) --------------------------------------

    @Test
    @DisplayName("connectFirstReachable: TEK-A/cozumlenmemis dalda baglanti hatasinda soket KAPATILIR")
    void connectFirstReachable_singleAddressPath_closesSocketOnFailure() throws Exception {
        // Cok-A dali hatada soketi kapatiyordu, bu dal kapatmiyordu. Asimetri gercek bir sizintiydi:
        // ulasilamayan her hedef bir soket + FD birakiyordu ve bu yol tam da SUREKLI hata veren
        // (cozumlenemeyen / tek-A) hedeflerin yolu. Tek pod'da FD tukenmesi gercek bir risk.
        javax.net.ssl.SSLSocket sock = mock(javax.net.ssl.SSLSocket.class);
        org.mockito.Mockito.doThrow(new java.io.IOException("Connection refused"))
                .when(sock).connect(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt());
        javax.net.ssl.SSLSocketFactory factory = mock(javax.net.ssl.SSLSocketFactory.class);
        when(factory.createSocket()).thenReturn(sock);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                        service, "connectFirstReachable", factory, "example.com", 443, 1, null))
                .hasRootCauseInstanceOf(java.io.IOException.class);   // ReflectionTestUtils kontrollu istisnayi sarar

        verify(sock).close();
    }

    @Test
    @DisplayName("connectFirstReachable: COK-A dalinda da her basarisiz deneme soketi kapatir")
    void connectFirstReachable_multiAddressPath_closesEachSocket() throws Exception {
        javax.net.ssl.SSLSocket s1 = mock(javax.net.ssl.SSLSocket.class);
        javax.net.ssl.SSLSocket s2 = mock(javax.net.ssl.SSLSocket.class);
        for (javax.net.ssl.SSLSocket s : new javax.net.ssl.SSLSocket[]{s1, s2}) {
            org.mockito.Mockito.doThrow(new java.io.IOException("Connection refused"))
                    .when(s).connect(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt());
        }
        javax.net.ssl.SSLSocketFactory factory = mock(javax.net.ssl.SSLSocketFactory.class);
        when(factory.createSocket()).thenReturn(s1, s2);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                        service, "connectFirstReachable", factory, "example.com", 443, 1,
                        java.util.List.of("192.0.2.1", "192.0.2.2")))
                .hasRootCauseInstanceOf(java.io.IOException.class);   // ReflectionTestUtils kontrollu istisnayi sarar

        verify(s1).close();
        verify(s2).close();
    }
}
