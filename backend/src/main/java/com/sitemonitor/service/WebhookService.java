package com.sitemonitor.service;

import tools.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

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

    private final ObjectMapper objectMapper;

    @Value("${site.monitor.webhook.timeout-seconds:10}")
    private int timeoutSeconds;

    private HttpClient httpClient;

    @PostConstruct
    public void init() {
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                .build();
    }

    public void sendTeams(String webhookUrl, String title, String message, String color) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("@type", "MessageCard");
            payload.put("@context", "http://schema.org/extensions");
            payload.put("themeColor", color);
            payload.put("summary", title);
            payload.put("title", title);
            payload.put("text", message);

            post(webhookUrl, payload);
        } catch (Exception e) {
            log.warn("Teams webhook failed {}: {}", webhookUrl, e.getMessage());
        }
    }

    public void sendSlack(String webhookUrl, String title, String message, String color) {
        try {
            Map<String, Object> attachment = new LinkedHashMap<>();
            attachment.put("color", color);
            attachment.put("title", title);
            attachment.put("text", message);
            attachment.put("footer", "SiteMonitor Enterprise");

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("attachments", List.of(attachment));

            post(webhookUrl, payload);
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
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        log.debug("Webhook response {}: {}", response.statusCode(), response.body());
    }

    private String levelToColor(String level) {
        return switch (level) {
            case "CRITICAL" -> "FF0000";
            case "HIGH" -> "FF8C00";
            default -> "FFC107";
        };
    }
}
