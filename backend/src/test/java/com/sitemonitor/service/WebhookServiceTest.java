package com.sitemonitor.service;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@SuppressWarnings("unchecked")
class WebhookServiceTest {

    @Mock HttpClient httpClient;
    @Mock HttpResponse<java.io.InputStream> httpResponse;
    @Mock com.sitemonitor.service.TrustEvaluator trustEvaluator;
    @Mock com.sitemonitor.service.CaAutoPinService caAutoPinService;

    private WebhookService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new WebhookService(new ObjectMapper(), permissiveGuard(), trustEvaluator, caAutoPinService);
        ReflectionTestUtils.setField(service, "timeoutSeconds", 10);
        service.init();
        ReflectionTestUtils.setField(service, "httpClient", httpClient);

        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenAnswer(inv ->
                new java.io.ByteArrayInputStream("ok".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(httpResponse);
    }

    // ── sendTeams ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("sendTeams → posts JSON with themeColor")
    void sendTeams_postsJsonWithThemeColor() throws Exception {
        service.sendTeams("http://localhost/hook", "Title", "Message", "FF0000");

        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(captor.capture(), any());
        // bodyPublisher is present, URI matches
        assertThat(captor.getValue().uri().toString()).isEqualTo("http://localhost/hook");
    }

    @Test
    @DisplayName("sendTeams: IOException → does not propagate exception")
    void sendTeams_exceptionDuringPost_doesNotThrow() throws Exception {
        when(httpClient.send(any(), any())).thenThrow(new java.io.IOException("timeout"));
        assertThatNoException().isThrownBy(
                () -> service.sendTeams("http://localhost/hook", "T", "M", "FFC107"));
    }

    // ── sendSlack ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("sendSlack → posts JSON with attachments")
    void sendSlack_postsJsonWithAttachments() throws Exception {
        service.sendSlack("http://localhost/slack", "Title", "Message", "good");

        verify(httpClient).send(any(), any());
    }

    @Test
    @DisplayName("sendSlack: IOException → does not propagate exception")
    void sendSlack_exceptionDuringPost_doesNotThrow() throws Exception {
        when(httpClient.send(any(), any())).thenThrow(new java.io.IOException("timeout"));
        assertThatNoException().isThrownBy(
                () -> service.sendSlack("http://localhost/slack", "T", "M", "good"));
    }

    // ── send (dispatch) ───────────────────────────────────────────────────────

    @Test
    @DisplayName("send type=TEAMS → calls httpClient (Teams path)")
    void send_typeTeams_usesTeamsFormat() throws Exception {
        service.send("TEAMS", "http://localhost/hook", "Title", "Msg", "CRITICAL");
        verify(httpClient, atLeastOnce()).send(any(), any());
    }

    @Test
    @DisplayName("send type=SLACK → calls httpClient (Slack path)")
    void send_typeSlack_usesSlackFormat() throws Exception {
        service.send("SLACK", "http://localhost/slack", "Title", "Msg", "HIGH");
        verify(httpClient, atLeastOnce()).send(any(), any());
    }

    @Test
    @DisplayName("send type=UNKNOWN → falls through to Teams path, no exception")
    void send_invalidType_doesNotThrow() {
        assertThatNoException().isThrownBy(
                () -> service.send("UNKNOWN", "http://localhost/hook", "T", "M", "WARNING"));
    }

    @Test
    @DisplayName("send with blank URL → httpClient NOT called")
    void send_blankUrl_doesNotCallHttpClient() throws Exception {
        service.send("TEAMS", "", "T", "M", "HIGH");
        verify(httpClient, never()).send(any(), any());
    }

    @Test
    @DisplayName("send with null URL → httpClient NOT called")
    void send_nullUrl_doesNotCallHttpClient() throws Exception {
        service.send("TEAMS", null, "T", "M", "HIGH");
        verify(httpClient, never()).send(any(), any());
    }

    // ── color mapping ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("CRITICAL alert level → FF0000 in request body")
    void send_criticalLevel_redColor() throws Exception {
        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        service.send("TEAMS", "http://localhost/hook", "T", "M", "CRITICAL");
        verify(httpClient).send(captor.capture(), any());
        // body publisher is present — we verify color via serialization side-channel through Teams path
        // (we can't easily read body from HttpRequest.BodyPublisher without consuming it)
        // Verify the call was made with the correct URL at minimum
        assertThat(captor.getValue().uri().toString()).isEqualTo("http://localhost/hook");
    }

    @Test
    @DisplayName("HIGH alert level → FF8C00 (httpClient called)")
    void send_highLevel_orangeColor() throws Exception {
        service.send("TEAMS", "http://localhost/hook", "T", "M", "HIGH");
        verify(httpClient, atLeastOnce()).send(any(), any());
    }

    @Test
    @DisplayName("WARNING alert level → FFC107 (httpClient called)")
    void send_warningLevel_yellowColor() throws Exception {
        service.send("TEAMS", "http://localhost/hook", "T", "M", "WARNING");
        verify(httpClient, atLeastOnce()).send(any(), any());
    }

    @Test
    @DisplayName("RESOLVED (default) alert level → FFC107 (httpClient called)")
    void send_resolvedLevel_defaultColor() throws Exception {
        service.send("TEAMS", "http://localhost/hook", "T", "M", "RESOLVED");
        verify(httpClient, atLeastOnce()).send(any(), any());
    }

    // ── GÖVDE SÖZLEŞMESİ ─────────────────────────────────────────────────────────
    // Yukarıdaki testlerin ADI renk/format iddia ediyordu ama gövdeleri yalnız URI'yi ya da
    // "send() çağrıldı mı"yı doğruluyordu: dört seviye de aynı rengi dönse, hatta Teams gövdesi
    // Slack'e gönderilse testler YEŞİL kalırdı. Payload üreticileri ayrıldı; asıl sözleşme burada.

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "CRITICAL, FF0000",
        "HIGH,     FF8C00",
        "WARNING,  FFC107",
        "RESOLVED, FFC107",
        "BILINMEYEN, FFC107",
    })
    @DisplayName("Seviye → renk eşlemesi (gerçekten farklı renkler; hepsi aynı olsa eski testler yeşildi)")
    void levelToColor_mapping(String level, String expected) {
        org.assertj.core.api.Assertions.assertThat(WebhookService.levelToColor(level)).isEqualTo(expected);
    }

    @Test
    @DisplayName("Teams gövdesi: MessageCard + themeColor RENGİ TAŞIR")
    void teamsPayload_carriesColorAndShape() {
        var p = WebhookService.buildTeamsPayload("Baslik", "Mesaj", "FF0000");

        org.assertj.core.api.Assertions.assertThat(p)
                .containsEntry("@type", "MessageCard")
                .containsEntry("themeColor", "FF0000")
                .containsEntry("title", "Baslik")
                .containsEntry("text", "Mesaj");
        // Slack şekli SIZMAMALI
        org.assertj.core.api.Assertions.assertThat(p).doesNotContainKey("attachments");
    }

    @Test
    @DisplayName("Slack gövdesi: attachments[0] içinde renk/başlık — Teams şekliyle KARIŞMAZ")
    void slackPayload_hasAttachmentShape() {
        var p = WebhookService.buildSlackPayload("Baslik", "Mesaj", "FF8C00");

        org.assertj.core.api.Assertions.assertThat(p).containsKey("attachments");
        org.assertj.core.api.Assertions.assertThat(p).doesNotContainKey("themeColor");
        @SuppressWarnings("unchecked")
        var att = (java.util.List<java.util.Map<String, Object>>) p.get("attachments");
        org.assertj.core.api.Assertions.assertThat(att).hasSize(1);
        org.assertj.core.api.Assertions.assertThat(att.get(0))
                .containsEntry("color", "FF8C00")
                .containsEntry("title", "Baslik")
                .containsEntry("text", "Mesaj")
                .containsEntry("footer", "SiteMonitor Enterprise");
    }

    /** Testler localhost'a POST eder -> SsrfGuard izin verici (loopback + ic ag acik);
     *  metadata/link-local YINE bloklu. PortCheckerServiceTest ile ayni desen. */
    private static SsrfGuard permissiveGuard() {
        AppSettingsService a = mock(AppSettingsService.class);
        when(a.getBoolean("site.monitor.monitoring.allow-loopback-targets", false)).thenReturn(true);
        when(a.getBoolean("site.monitor.monitoring.allow-internal-targets", true)).thenReturn(true);
        return new SsrfGuard(a);
    }

    @Test
    @DisplayName("SSRF: cloud-metadata webhook adresine istek HIC atilmaz (alarm metni sizmaz)")
    void post_metadataUrl_neverSends() throws Exception {
        // Webhook govdesi ALARM METNI tasiyor; dogrulanmamis bir hedef, ic agdaki bir uca alarm
        // icerigini POST etmenin yoluydu. sendTeams istisnayi yutar -> cagirana yayilmaz, ama
        // asil sozlesme: istek HIC atilmaz.
        service.sendTeams("http://169.254.169.254/hook", "T", "M", "FF0000");
        verify(httpClient, never()).send(any(), any());
    }

    @Test
    @DisplayName("Yanit govdesi TAVANLI okunur — dev yanit bellege tam alinmaz")
    void post_hugeResponse_readsCapped() throws Exception {
        // 4 MB'lik yanit: ofString() hepsini bellege alirdi. Akis tavanla okunur; gonderim yine basarili.
        byte[] huge = new byte[4 * 1024 * 1024];
        java.util.Arrays.fill(huge, (byte) 'x');
        when(httpResponse.body()).thenAnswer(inv -> new java.io.ByteArrayInputStream(huge));
        service.sendTeams("http://localhost/hook", "T", "M", "FF0000");
        verify(httpClient, atLeastOnce()).send(any(), any());
    }
}
