package com.sitemonitor.service;

import com.sitemonitor.model.AlertEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * KAPI (O-b1 / D-2 / D-b14, 2026-09-29): bir izleme alarmının SEVİYE SÖZCÜĞÜ her kanalda olayın seviyesiyle aynıdır —
 * ileti gövdesi, alarm e-postası (rozet + "Seviye" satırı + düz metin), çözüm e-postası ve push. Tek sözlük
 * {@link EscalationService#levelWordTr} (WARNING → "UYARI", HIGH → "YÜKSEK", CRITICAL → "KRİTİK").
 *
 * <p>Neden: prod olayı "UYARI rozeti ↔ KRİTİK metin" idi. İlk düzeltme gövdeyi ve konuyu hizaladı ama tür-özel e-posta
 * belgeleri (erişim / port / DNS / içerik / ping / DNS değişikliği ve çözüm belgeleri) rozeti ve "Seviye" satırını hâlâ
 * SABİT "KRİTİK" yazıyordu — aynı e-postanın içinde çelişki. Bu kapı İZLEME türlerinin TAMAMINI ve üç seviyeyi gezer;
 * yeni bir tür ya da belge sabit bir seviye sözcüğü yazarsa adıyla kızarır.
 */
class AlertLevelWordConsistencyGateTest {

    private static final List<String> LEVELS = List.of("WARNING", "HIGH", "CRITICAL");
    private static final List<String> WORDS = List.of("UYARI", "YÜKSEK", "KRİTİK");

    private EscalationService escalation;
    private EmailNotificationService email;
    private UserPushService push;

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
        push = build(UserPushService.class, provided);
    }

    /** Her türün zengin ileti + e-posta dalını tetikleyen gerçek biçimli bağlam. */
    private static Map<String, Object> ctxFor(String type) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("monitor_id", 11L);
        m.put("monitor_name", "İzleme A");
        m.put("port", 443);
        m.put("protocol", "TCP");
        m.put("record_type", "A");
        m.put("url", "https://app.example.com/");
        m.put("keyword", "Giriş");
        m.put("host", "app.example.com");
        m.put("domain", "example.com");
        m.put("detail", "FAIL — 1✓/3✗");
        m.put("old_values", List.of("192.0.2.1"));
        m.put("new_values", List.of("192.0.2.2"));
        m.put("unexpected_values", List.of("192.0.2.9"));
        m.put("first_failure_at", "2026-09-28T22:22:00");
        m.put("last_error", "Connect timed out");
        m.put("alert_event_id", 900L);
        return m;
    }

    private static String word(String level) {
        return EscalationService.levelWordTr(level);
    }

    /** Metinde bu seviyenin sözcüğü VAR ve öteki iki seviye sözcüğü YOK mu? Değilse açıklama döner. */
    private static String mismatch(String channel, String type, String level, String text) {
        String w = word(level);
        if (text == null || !text.contains(w)) return channel + " " + type + "/" + level + ": '" + w + "' yok";
        for (String other : WORDS) {
            if (!other.equals(w) && text.contains(other)) return channel + " " + type + "/" + level + ": '" + other + "' geçiyor";
        }
        return null;
    }

    private static AlertEvent event(String type, String level, String message) {
        AlertEvent e = new AlertEvent();
        e.setId(900L);
        e.setDomain("app.example.com");
        e.setAlertType(type);
        e.setAlertLevel(level);
        e.setMessage(message);
        e.setCreatedAt("2026-09-28T22:22:00");
        return e;
    }

    @Test
    @DisplayName("KAPI: her izleme türü × seviye — ileti, alarm e-postası (HTML+metin), çözüm e-postası ve push AYNI seviye sözcüğü")
    void levelWordIsConsistentAcrossChannels_everyMonitoringType() {
        List<String> wrong = new ArrayList<>();
        for (String type : EscalationService.MONITORING_ALERT_TYPES) {
            Map<String, Object> ctx = ctxFor(type);
            for (String level : LEVELS) {
                String msg = ReflectionTestUtils.invokeMethod(escalation, "monitoringMessage",
                        "app.example.com", type, level, ctx);
                String bad = mismatch("ileti", type, level, msg);
                if (bad != null) { wrong.add(bad); continue; }

                String subject = "[Site Monitor] " + word(level) + " · app.example.com";
                String text = email.buildAlertEmailText(subject, msg, "app.example.com", level, type, null, ctx);
                bad = mismatch("e-posta(metin)", type, level, text);
                if (bad != null) wrong.add(bad);
                String html = email.buildAlertEmailHtml(subject, msg, "app.example.com", level, type, null, ctx);
                bad = mismatch("e-posta(html)", type, level, html);
                if (bad != null) wrong.add(bad);

                String resolved = email.resolutionMail("app.example.com", type, level, null, "Sistem (otomatik)",
                        "2026-09-29T08:00:00", "2026-09-28T22:22:00", ctx, "Takım A", null).text();
                if (resolved != null && WORDS.stream().anyMatch(resolved::contains)) {
                    bad = mismatch("çözüm e-postası", type, level, resolved);
                    if (bad != null) wrong.add(bad);
                }

                String pm = push.buildMessage(event(type, level, msg), "OPEN", ctx);
                bad = mismatch("push", type, level, pm);
                if (bad != null) wrong.add(bad);
            }
        }
        assertThat(wrong).as("seviye sözcüğü tutarsız kanal/tür/seviye").isEmpty();
    }

    @Test
    @DisplayName("KAPI (D-c7): FIRTINA kanalları — takım e-postası (HTML+metin) ve 7/24 fırtına / güncelleme postası (konu+gövde) "
            + "üyelerin en yüksek seviyesini (push ile aynı) tek sözlükle yazar")
    void stormChannels_useStormLevelWord() {
        List<String> wrong = new ArrayList<>();
        for (String level : LEVELS) {
            AlertEvent m = event(EscalationService.TYPE_HTTP_DOWN, "WARNING", "x");
            AlertEvent top = event(EscalationService.TYPE_HTTP_DOWN, level, "x");
            String stormLevel = StormService.stormPushLevel(List.of(m, top));
            if (!level.equals(stormLevel)) wrong.add("stormPushLevel " + level + " → " + stormLevel);

            List<String> targets = List.of("app.example.com", "api.example.com");
            String html = email.buildStormAlertHtml(3, "Takım A", "Ortak alt ağ", "2026-09-28T22:22:00", targets, 1, stormLevel);
            String text = email.buildStormAlertText(3, "Takım A", "Ortak alt ağ", "2026-09-28T22:22:00", targets, 1, stormLevel);
            String bad = mismatch("fırtına e-postası(html)", "STORM", level, html);
            if (bad != null) wrong.add(bad);
            bad = mismatch("fırtına e-postası(metin)", "STORM", level, text);
            if (bad != null) wrong.add(bad);

            var noc = com.sitemonitor.service.noc.NocMailComposer.storm(3, "Takım A", "Port Kesintisi",
                    "2026-09-28T22:22:00", List.of(), List.of(), null, null, List.of(), stormLevel);
            bad = mismatch("7/24 fırtına(konu)", "STORM", level, com.sitemonitor.service.noc.NocMailComposer.stormSubject(3, stormLevel));
            if (bad != null) wrong.add(bad);
            bad = mismatch("7/24 fırtına(html)", "STORM", level, noc.html());
            if (bad != null) wrong.add(bad);
            var upd = com.sitemonitor.service.noc.NocMailComposer.stormUpdate(3, "Takım A", "2026-09-28T22:22:00",
                    List.of(), List.of(), null, null, List.of(), stormLevel);
            bad = mismatch("7/24 fırtına güncellemesi(html)", "STORM", level, upd.html());
            if (bad != null) wrong.add(bad);
        }
        assertThat(wrong).as("fırtına kanallarında seviye sözcüğü tutarsız").isEmpty();
    }
}
