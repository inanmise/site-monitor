package com.sitemonitor.service.otp;

import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.UserPushDeliveryRepository;
import com.sitemonitor.repository.UserPushScopeRepository;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.CaAutoPinService;
import com.sitemonitor.service.PushText;
import com.sitemonitor.service.SecretCipher;
import com.sitemonitor.service.TrustEvaluator;
import com.sitemonitor.service.UserPushRecipientResolver;
import com.sitemonitor.service.UserPushService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * 2026-10-03 kullanıcı bildirimi: "push ile kod gönderiminde koddan sonra ? geliyor". Kök neden: alıcı push kanalı
 * ISO-8859-9 taşır ({@link PushText}); alarm hattı metni {@link PushText#pushSafe} ile süzüyordu ama kodla giriş için
 * eklenen {@link UserPushService#sendDirect} süzmüyordu → "123456 — 45 sn" içindeki em-dash telefonda "?" oldu.
 * Bu test ağ geçidine GİDEN gövdeyi okur: kanalın taşıyamadığı tek bir karakter bile kalmamalı.
 */
class LoginOtpPushSafeTextTest {

    private static final Charset CHANNEL = Charset.forName("ISO-8859-9");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private UserPushService push;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/push", ex -> {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            ex.getRequestBody().transferTo(b);
            bodies.add(b.toString(StandardCharsets.UTF_8));
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/push";
        AppSettingsService appSettings = mock(AppSettingsService.class);
        lenient().when(appSettings.getString(anyString(), any())).thenAnswer(inv -> inv.getArgument(1));
        lenient().when(appSettings.getString(eq("site.monitor.userpush.url"), any())).thenReturn(url);
        lenient().when(appSettings.getInt(anyString(), anyInt())).thenAnswer(inv -> inv.getArgument(1));
        lenient().when(appSettings.getBoolean(anyString(), anyBoolean())).thenAnswer(inv -> inv.getArgument(1));
        TrustEvaluator trust = mock(TrustEvaluator.class);
        lenient().when(trust.pinAwareOutboundSslContext(any(), any())).thenReturn(null);
        push = new UserPushService(appSettings, mock(UserPushDeliveryRepository.class), mock(UserPushScopeRepository.class),
                mock(UserPushRecipientResolver.class), mock(AlertEventRepository.class), mock(SecretCipher.class), trust,
                mock(CaAutoPinService.class));
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    private JsonNode sent() throws Exception {
        assertThat(bodies).hasSize(1);
        return MAPPER.readTree(bodies.get(0));
    }

    private static void assertChannelSafe(String s) {
        assertThat(CHANNEL.newEncoder().canEncode(s)).as("kanalın taşıyamadığı karakter kaldı: %s", s).isTrue();
    }

    @Test
    void loginCodeMessages_reachGatewayWithoutUnencodableCharacters_bothLanguages() throws Exception {
        for (boolean english : new boolean[] {false, true}) {
            bodies.clear();
            String title = LoginOtpDeliveryService.pushTitle(english);
            String message = LoginOtpDeliveryService.pushMessage("123456", 45, english);
            assertThat(push.sendDirect("ALICE", title, message).ok()).isTrue();
            JsonNode body = sent();
            String m = body.get("message").asText();
            assertChannelSafe(body.get("title").asText());
            assertChannelSafe(m);
            // Kodun hemen ardında "?" yerine okunur bir ayraç: "123456 - 45 sn" / "123456 - valid for 45 s"
            assertThat(m).contains("123456 - ").doesNotContain("?").doesNotContain("—");
        }
    }

    @Test
    void turkishLettersSurvive_typographyIsTranslated_unknownSymbolsDropped() throws Exception {
        assertThat(push.sendDirect("ALICE", "Giriş — kodu ✓", "Kodunuz: 654321 — ğüşıöç İ “tırnak” … ✓ ⚡ son").ok()).isTrue();
        JsonNode body = sent();
        assertThat(body.get("title").asText()).isEqualTo("Giriş - kodu OK");
        assertThat(body.get("message").asText()).isEqualTo("Kodunuz: 654321 - ğüşıöç İ \"tırnak\" ... OK son");
    }

    @Test
    void blankTitle_fallsBackToSetting_stillSafe() throws Exception {
        assertThat(push.sendDirect("ALICE", " ", "Kod: 111222").ok()).isTrue();
        assertChannelSafe(sent().get("title").asText());
    }
}
