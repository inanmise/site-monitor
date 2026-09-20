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

    /**
     * Istemciyi kapat — UserPushService.shutdown() ile simetrik.
     *
     * <p>Tek pod omru boyunca zararsizdi ama context refresh ve testlerde her yeniden kurulum bir
     * SelectorManager thread'i + FD sizdiriyordu. Kardes servis kapatiyor, bu kapatmiyordu.
     */
    @jakarta.annotation.PreDestroy
    public void shutdown() {
        try { if (httpClient != null) httpClient.close(); } catch (Exception ignored) { }
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

    /**
     * Teslim edilemeyen webhook — çağırana YAYILIR.
     *
     * <p>Eskiden {@code sendTeams}/{@code sendSlack} her istisnayı içeride yutup {@code void}
     * dönüyordu. Sonuç sessiz bir yalandı: {@code EscalationService}'teki
     * {@code catch { webhookStatus = "FAILED" }} bloğuna ASLA girilemiyor, dolayısıyla
     * {@code notification_log.webhook_status} teslim edilmemiş alarmlar için de "SENT" yazıyordu.
     * Bildirim Geçmişi ekranı bunu yeşil "Gönderildi" rozetiyle gösteriyordu — bir izleme
     * ürününde yanlış yeşil, kırmızıdan tehlikelidir.
     *
     * <p>Unchecked seçildi ki iki çağrının imzası değişmesin; ikisinin de {@code catch (Exception)}
     * bloğu zaten var ve artık gerçekten çalışıyor.
     */
    public static class WebhookDeliveryException extends RuntimeException {
        WebhookDeliveryException(String message, Throwable cause) { super(message, cause); }
        WebhookDeliveryException(String message) { super(message); }
    }

    public void sendTeams(String webhookUrl, String title, String message, String color) {
        dispatch("Teams", webhookUrl, buildTeamsPayload(title, message, color));
    }

    public void sendSlack(String webhookUrl, String title, String message, String color) {
        dispatch("Slack", webhookUrl, buildSlackPayload(title, message, color));
    }

    private void dispatch(String kind, String webhookUrl, Object payload) {
        try {
            post(webhookUrl, payload);
        } catch (WebhookDeliveryException e) {
            log.warn("{} webhook başarısız [{}]: {}", kind, maskUrl(webhookUrl), e.getMessage());
            throw e;
        } catch (Exception e) {
            log.warn("{} webhook başarısız [{}]: {}", kind, maskUrl(webhookUrl), e.getMessage());
            throw new WebhookDeliveryException(e.getMessage(), e);
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

    /**
     * Günlüğe basılabilir webhook kimliği: {@code host/…<son 6 karakter>}.
     *
     * <p>Slack/Teams incoming-webhook ADRESİNİN KENDİSİ bir kimlik bilgisidir
     * ({@code https://hooks.slack.com/services/T…/B…/<secret>}); tam URL WARN seviyesinde
     * basılıyordu ve WARN prod'da açık, günlükler 30 gün saklanıyor. Log okuyabilen herkes o
     * kanala mesaj atabilirdi. Sorunu ayıklamak için host + kısa kuyruk yeter.
     */
    public static String maskUrl(String url) {
        if (url == null || url.isBlank()) return "-";
        try {
            URI u = URI.create(url);
            String host = u.getHost() == null ? "?" : u.getHost();
            String path = u.getPath() == null ? "" : u.getPath();
            String tail = path.length() <= 6 ? "" : path.substring(path.length() - 6);
            return host + "/…" + tail;
        } catch (Exception e) {
            return "?";
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
        int status = response.statusCode();
        log.debug("Webhook response {}: {}", status, preview);
        // DURUM KODU KONTROLÜ: eskiden yalnız debug'a yazılıyordu, dolayısıyla silinmiş bir
        // webhook'un 404'ü ya da 403 de "başarı" sayılıyordu. Kardeş kanal UserPushService.sendBatch
        // bu aralığı zaten doğru kontrol ediyor — burada aynı kural.
        if (status < 200 || status >= 300) {
            throw new WebhookDeliveryException("HTTP " + status
                    + (preview.isBlank() ? "" : ": " + preview.strip()));
        }
    }

    static String levelToColor(String level) {
        return switch (level) {
            case "CRITICAL" -> "FF0000";
            case "HIGH" -> "FF8C00";
            default -> "FFC107";
        };
    }
}
