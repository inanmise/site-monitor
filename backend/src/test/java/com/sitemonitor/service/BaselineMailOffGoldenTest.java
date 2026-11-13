package com.sitemonitor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * KAPI (2026-11-13, dinamik taban hata taraması #9): taban çizgisi KAPALI izlemenin yavaşlık alarmı postası (ileti + alarm
 * postası HTML / düz metin + çözüm postası HTML / düz metin) bant postası eklenmeden ÖNCEKİ çıktıyla BİREBİR aynıdır. Özetler
 * düzeltmeden önceki koddan kaydedildi; bilinçli bir posta değişikliği olmadıkça yeniden kaydedilmez.
 */
class BaselineMailOffGoldenTest {

    /** Tür → (ileti + alarm HTML + alarm metin + çözüm HTML + çözüm metin) SHA-256 — taban bandı eklenmeden önceki koddan. */
    private static final Map<String, String> GOLDEN = Map.of(
            "HTTP_SLOW", "ac351e800d175f11ded0cb5b45f78de0b0aadb31f847f3dc5b0d00bd7116e0db",
            "KEYWORD_SLOW", "26b252d15078684179a4f139d7bc065e180e10ea1f5afe6aa668f149c20c8b47",
            "PORT_SLOW", "858d7f916c2daefbcf6349b82bf88be905eb128c37e189faea04519e0293f607",
            "PING_SLOW", "991879fcf3fd2463b631d2b0b65c67cdc365d97949603b5e8b31f8a98ecfcae2",
            "PAGESPEED_SLOW", "a19be6ad7da06804fe4bd5ec3d4ea402fe0ad5676513e67e1e15b61ffe58822d",
            "SCRIPTED_SLOW", "ce107ddc179fa083c00af3a1fca83053cf801294de2f4d7af24864fe837373a8",
            "DNS_SLOW", "968720fc5d63b659470c913111117077d60bb68d5cafcdc0764352dfbaa774c0");

    private EscalationService escalation;
    private EmailNotificationService email;

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
        AppSettingsService appSettings = mock(AppSettingsService.class);
        lenient().when(appSettings.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> i.getArgument(1));
        lenient().when(appSettings.getInt(anyString(), anyInt())).thenAnswer(i -> i.getArgument(1));
        lenient().when(appSettings.getString(anyString(), any())).thenAnswer(i -> i.getArgument(1));
        Map<Class<?>, Object> provided = new HashMap<>();
        provided.put(AppSettingsService.class, appSettings);
        EmailTemplateBuilder tb = new EmailTemplateBuilder(appSettings);
        ReflectionTestUtils.setField(tb, "appBaseUrl", "http://localhost:5173");
        provided.put(EmailTemplateBuilder.class, tb);
        escalation = build(EscalationService.class, provided);
        email = build(EmailNotificationService.class, provided);
    }

    /** Sabit eşik yolunun bugün yazdığı bağlam (taban anahtarı YOK). */
    static Map<String, Object> fixedCtx(String type) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("monitor_id", 44L);
        m.put("monitor_name", "Ödeme portalı");
        m.put("team_id", 7L);
        m.put("checked_at", "2026-09-26T07:00:00");
        m.put("first_failure_at", "2026-09-26T06:55:00");
        m.put("alert_event_id", 94L);
        switch (type) {
            case "HTTP_SLOW" -> { m.put("url", "https://pay.example.com/"); m.put("http_status", 200);
                m.put("response_ms", 4200); m.put("threshold_ms", 2000); }
            case "KEYWORD_SLOW" -> { m.put("url", "https://pay.example.com/"); m.put("response_ms", 3500); m.put("threshold_ms", 3000); }
            case "PORT_SLOW" -> { m.put("port", 443); m.put("protocol", "TCP"); m.put("response_ms", 3500); m.put("threshold_ms", 3000); }
            case "PING_SLOW" -> { m.put("host", "gw.example.com"); m.put("rtt_ms", 340); m.put("baseline_ms", 40);
                m.put("threshold_percent", 50); m.put("baseline_window_minutes", 10); }
            case "PAGESPEED_SLOW" -> { m.put("url", "https://pay.example.com/"); m.put("response_ms", 9000);
                m.put("detail", "yükleme 9000 ms eşiği aştı"); }
            case "SCRIPTED_SLOW" -> { m.put("name", "Ödeme akışı"); m.put("duration_ms", 8120); m.put("threshold_ms", 5000); }
            case "DNS_SLOW" -> { m.put("record_type", "A"); m.put("response_ms", 2500); m.put("slow_threshold_ms", 1000); }
            default -> throw new IllegalArgumentException(type);
        }
        return m;
    }

    static String domainOf(String type) {
        return switch (type) {
            case "HTTP_SLOW", "KEYWORD_SLOW", "PAGESPEED_SLOW" -> "https://pay.example.com/";
            case "SCRIPTED_SLOW" -> "Ödeme akışı";
            default -> "gw.example.com";
        };
    }

    /** Bir türün tüm posta yüzeyleri tek metin olarak. */
    static String render(EscalationService escalation, EmailNotificationService email, String type, Map<String, Object> ctx) {
        String domain = domainOf(type);
        String msg = ReflectionTestUtils.invokeMethod(escalation, "monitoringMessage", domain, type, "HIGH", ctx);
        String subject = "[Site Monitor] YÜKSEK · " + domain;
        String html = email.buildAlertEmailHtml(subject, msg, domain, "HIGH", type, null, ctx);
        String text = email.buildAlertEmailText(subject, msg, domain, "HIGH", type, null, ctx);
        var res = email.resolutionMail(domain, type, "HIGH", null, "Sistem (otomatik)",
                "2026-09-26T08:00:00", "2026-09-26T07:00:00", ctx, "Takım A", null);
        return msg + "\n--\n" + html + "\n--\n" + text + "\n--\n" + res.html() + "\n--\n" + res.text();
    }

    static String sha(String s) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("taban KAPALI: 7 yavaşlık türünün ileti + alarm + çözüm postası değişmedi (özet kapısı)")
    void offOutputIsByteIdentical() throws Exception {
        Map<String, String> actual = new TreeMap<>();
        for (String type : GOLDEN.keySet()) actual.put(type, sha(render(escalation, email, type, fixedCtx(type))));
        assertThat(actual).isEqualTo(new TreeMap<>(GOLDEN));
    }
}
