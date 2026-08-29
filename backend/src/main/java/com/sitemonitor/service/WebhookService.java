package com.sitemonitor.service;

import tools.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.net.ssl.SSLContext;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookService {

    /** Yanıt gövdesinden okunacak tavan — yanıt yalnız günlüğe yazılıyor, sınırsız okumaya gerek yok. */
    private static final int MAX_RESPONSE_BYTES = 8192;

    private final ObjectMapper objectMapper;
    private final SsrfGuard ssrfGuard;
    private final TrustEvaluator trustEvaluator;
    private final CaAutoPinService caAutoPinService;

    @Value("${site.monitor.webhook.timeout-seconds:10}")
    private int timeoutSeconds;

    private HttpClient httpClient;

    @PostConstruct
    public void init() {
        HttpClient.Builder b = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(timeoutSeconds));
        // Kurumsal TLS güveni: cacerts → admin'in yapıştırdığı CA paketi → host'un otomatik pinlenmiş
        // CA'sı. İÇ ağdaki bir webhook alıcısı kurumsal CA ile imzalıysa düz istemci PKIX ile düşerdi
        // (kişi-webhook kanalında aynısı prod'da yaşandı). null → varsayılan güvene düş.
        SSLContext ssl = trustEvaluator.pinAwareOutboundSslContext(
                caAutoPinService::trustManagerForHost, caAutoPinService::recordTrustFailure);
        if (ssl != null) b.sslContext(ssl);
        httpClient = b.build();
    }

    /** Teams MessageCard gövdesi. AYRI metot: gövde post() içinde serileştirildiği için testten
     *  okunamıyordu ve "renk/format" iddia eden testler aslında yalnız URI'yi doğruluyordu. */
    static Map<String, Object> buildTeamsPayload(String title, String message, String color) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("@type", "MessageCard");
        payload.put("@context", "http://schema.org/extensions");
        payload.put("themeColor", color);
        payload.put("summary", title);
        payload.put("title", title);
        payload.put("text", message);
        return payload;
    }

    /** Slack attachment gövdesi (Teams'ten FARKLI şekil — karışırsa mesaj sessizce bozuk gider). */
    static Map<String, Object> buildSlackPayload(String title, String message, String color) {
        Map<String, Object> attachment = new LinkedHashMap<>();
        attachment.put("color", color);
        attachment.put("title", title);
        attachment.put("text", message);
        attachment.put("footer", "SiteMonitor Enterprise");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("attachments", List.of(attachment));
        return payload;
    }

    public void sendTeams(String webhookUrl, String title, String message, String color) {
        try {
            post(webhookUrl, buildTeamsPayload(title, message, color));
        } catch (Exception e) {
            log.warn("Teams webhook failed {}: {}", webhookUrl, e.getMessage());
        }
    }

    public void sendSlack(String webhookUrl, String title, String message, String color) {
        try {
            post(webhookUrl, buildSlackPayload(title, message, color));
        } catch (Exception e) {
            log.warn("Slack webhook failed {}: {}", webhookUrl, e.getMessage());
        }
    }

    public void send(String type, String webhookUrl, String title, String message, String alertLevel) {
        if (webhookUrl == null || webhookUrl.isBlank()) return;
        String color = levelToColor(alertLevel);
        if ("SLACK".equalsIgnoreCase(type)) {
            sendSlack(webhookUrl, title, message, color);
        } else {
            sendTeams(webhookUrl, title, message, color);
        }
    }

    private void post(String url, Object payload) throws Exception {
        String body = objectMapper.writeValueAsString(payload);
        URI uri = URI.create(url);
        // SSRF: webhook adresi kullanıcı/yönetici girdisidir ve gövdesi ALARM METNİ taşır — doğrulanmamış
        // bir hedef, iç ağdaki bir uca alarm içeriğini POST etmenin yolu olurdu. Politika reddi
        // BlockedException fırlatır; çağıran sendTeams/sendSlack zaten yakalayıp uyarı olarak günlüğe yazar.
        ssrfGuard.validate(uri.getHost());
        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        // Yanıt gövdesi TAVANLI okunur: yalnız günlüğe yazılacak bir metin için hedefin gönderdiği
        // her şeyi belleğe almak gereksiz (tek pod; OOM = kesinti).
        HttpResponse<java.io.InputStream> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        String preview;
        try (java.io.InputStream is = response.body()) {
            preview = new String(is.readNBytes(MAX_RESPONSE_BYTES), java.nio.charset.StandardCharsets.UTF_8);
        }
        log.debug("Webhook response {}: {}", response.statusCode(), preview);
    }

    static String levelToColor(String level) {
        return switch (level) {
            case "CRITICAL" -> "FF0000";
            case "HIGH" -> "FF8C00";
            default -> "FFC107";
        };
    }
}
