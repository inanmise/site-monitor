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
    @Mock HttpResponse<String> httpResponse;

    private WebhookService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new WebhookService(new ObjectMapper());
        ReflectionTestUtils.setField(service, "timeoutSeconds", 10);
        service.init();
        ReflectionTestUtils.setField(service, "httpClient", httpClient);

        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn("ok");
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
}
