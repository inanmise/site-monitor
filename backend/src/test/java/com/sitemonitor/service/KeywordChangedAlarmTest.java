package com.sitemonitor.service;

import com.sitemonitor.service.noc.NocMailComposer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * KEYWORD_CHANGED tesisatı (kw sözleşmesi §11.1, 2026-11-05) — "KEYWORD_SLOW / HTTP_SLOW geçen her yere" kuralı: katalog, kümeler
 * (kesinti DEĞİL), ileti (bağlamlı + bağlamsız, seviye sözcüğü), e-posta (açılış + çözüm), push şablonu, 7/24 etiketi.
 */
class KeywordChangedAlarmTest {

    static final String URL = "https://kampanya.example.com/kosullar";

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
        ReflectionTestUtils.setField(email, "appBaseUrl", "http://localhost:5173");
        push = build(UserPushService.class, provided);
    }

    static Map<String, Object> ctx() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("lines_before", 3);
        summary.put("lines_after", 3);
        summary.put("removed", List.of("Kampanya #.# tarihine kadar geçerlidir"));
        summary.put("added", List.of("Kampanya sona erdi", "Yeni koşullar yakında"));
        summary.put("removed_total", 1);
        summary.put("added_total", 2);
        summary.put("truncated", false);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("url", URL);
        m.put("monitor_id", 41L);
        m.put("monitor_name", "Kampanya koşulları");
        m.put("change_summary", summary);
        m.put("baseline_taken_at", "2026-11-01T06:00:00");
        m.put("baseline_source", "AUTO");
        m.put("change_mask", List.of("DIGITS"));
        m.put("change_selector", "#kosullar");
        m.put("checked_at", "2026-11-05T09:00:00");
        m.put("alert_event_id", 900L);
        return m;
    }

    @Test
    @DisplayName("Katalog + kümeler: keyword türüne bağlı, izleme alarm türü, keyword yan türü (bağımsız), kesinti DEĞİL")
    void catalogueAndSets() {
        assertThat(EscalationService.TYPE_KEYWORD_CHANGED).isEqualTo("KEYWORD_CHANGED");
        assertThat(MonitorTypeCatalog.typeOfAlert("KEYWORD_CHANGED")).isEqualTo("keyword");
        assertThat(EscalationService.MONITORING_ALERT_TYPES).contains("KEYWORD_CHANGED");
        assertThat(EscalationService.isKeywordAux("KEYWORD_CHANGED")).isTrue();
        assertThat(EscalationService.DOWN_ALERT_TYPES).doesNotContain("KEYWORD_CHANGED");
        assertThat(EscalationService.EXPIRY_MONITORING_TYPES).doesNotContain("KEYWORD_CHANGED");
        assertThat(EscalationService.isDurationAlert("KEYWORD_CHANGED")).isFalse();
        assertThat(com.sitemonitor.service.evalstate.RecheckRefs.monitorType("KEYWORD_CHANGED")).isEqualTo("keyword");
        assertThat(ActivityLogService.alertActivityType("KEYWORD_CHANGED")).isEqualTo("KEYWORD");
    }

    @Test
    @DisplayName("İleti (bağlamlı): '<url> sayfasının içeriği temel alınan sürümden farklı (N satır değişti)'; seviye sözcüğü seviyeden")
    void messageWithContext() {
        for (String level : List.of("WARNING", "HIGH", "CRITICAL")) {
            String msg = ReflectionTestUtils.invokeMethod(escalation, "monitoringMessage", URL, "KEYWORD_CHANGED", level, ctx());
            assertThat(msg).startsWith(EscalationService.levelWordTr(level) + ": " + URL)
                    .contains("temel alınan sürümden farklı (2 satır değişti)")
                    .contains("'Yeni temel al'")
                    .contains("kendiliğinden kapanır");
        }
        Map<String, Object> c = ctx();
        c.put("changed_lines", 7);
        assertThat((String) ReflectionTestUtils.invokeMethod(escalation, "monitoringMessage", URL, "KEYWORD_CHANGED", "HIGH", c))
                .contains("(7 satır değişti)");
        Map<String, Object> json = ctx();
        json.put("change_summary", "{\"removed_total\":4,\"added_total\":1}");
        assertThat((String) ReflectionTestUtils.invokeMethod(escalation, "monitoringMessage", URL, "KEYWORD_CHANGED", "HIGH", json))
                .contains("(4 satır değişti)");
    }

    @Test
    @DisplayName("İleti (bağlamsız — 'Tekrar bildir'): sertifika metnine düşmez, satır sayısı yok")
    void messageWithoutContext() {
        String msg = ReflectionTestUtils.invokeMethod(escalation, "buildMessage", URL, "KEYWORD_CHANGED", "WARNING", (Integer) null);
        assertThat(msg).startsWith("UYARI: " + URL).contains("temel alınan sürümden farklı.").doesNotContain("sertifika")
                .doesNotContain("satır değişti");
    }

    @Test
    @DisplayName("Alarm e-postası: kendi belgesi — kaldırılan / eklenen satırlar, temel zamanı, bölge, maske, 'Yeni temel al' bağlantısı")
    void alertMail() {
        String msg = ReflectionTestUtils.invokeMethod(escalation, "monitoringMessage", URL, "KEYWORD_CHANGED", "HIGH", ctx());
        String html = email.buildAlertEmailHtml("konu", msg, URL, "HIGH", "KEYWORD_CHANGED", null, ctx());
        String text = email.buildAlertEmailText("konu", msg, URL, "HIGH", "KEYWORD_CHANGED", null, ctx());
        for (String s : List.of(html, text)) {
            assertThat(s).contains("İçerik Temelden Farklı").contains("İçerik Değişikliği")
                    .contains("Kampanya sona erdi").contains("Yeni koşullar yakında")
                    .contains("CSS seçici: #kosullar").contains("Rakamlar").contains("otomatik alındı")
                    .contains("2 satır").contains("Yeni temel al")
                    .doesNotContain("Sertifika İzleme").doesNotContain("KRİTİK").doesNotContain("UYARI");
        }
        assertThat(html).contains("tab=keyword&amp;monitor=41&amp;mtab=content");
    }

    @Test
    @DisplayName("Çözüm e-postası: doğrulanmış kapanış 'Sayfa İçeriği Temele Döndü', elle 'Elle Kapatıldı'; 'kesinti' denmez")
    void resolutionMail() {
        String auto = email.buildResolutionEmailHtml(URL, "KEYWORD_CHANGED", "HIGH", null, "Sistem (otomatik)",
                "2026-11-05T10:00:00", "2026-11-05T09:00:00", ctx(), "Takım A", null);
        assertThat(auto).contains("Sayfa İçeriği Temele Döndü").contains("Alarm Süresi").doesNotContain("kesinti süresi")
                .contains("Alarm anı değişen satır");
        String manual = email.buildResolutionEmailText(URL, "KEYWORD_CHANGED", "ayse", "2026-11-05T10:00:00",
                "2026-11-05T09:00:00", ctx());
        assertThat(manual).contains("Elle Kapatıldı").contains("ayse");
        // bağlamsız çözüm (eski olay) da kırılmaz
        assertThat(email.buildResolutionEmailText(URL, "KEYWORD_CHANGED", null, "2026-11-05T10:00:00")).contains("ÇÖZÜLDÜ");
    }

    @Test
    @DisplayName("Şablon başlığı / çözüm cümlesi (EmailTemplateBuilder), push şablonu 'changed', 7/24 etiketi 'İçerik değişti'")
    void labels() {
        assertThat(EmailTemplateBuilder.resolvedHeadline("KEYWORD_CHANGED", false, "Sistem (otomatik)"))
                .isEqualTo("Sayfa içeriği temel alınan sürüme döndü — alarm otomatik olarak kapandı.");
        assertThat(EmailTemplateBuilder.resolvedHeadline("KEYWORD_CHANGED", false, "ayse")).isEqualTo("Alarm elle kapatıldı.");
        assertThat(UserPushService.templateKeyFor("KEYWORD_CHANGED", "OPEN")).isEqualTo("changed");
        assertThat(UserPushService.templateKeyFor("KEYWORD_CHANGED", "RESOLVE")).isEqualTo("resolved");
        assertThat(NocMailComposer.problemLabel("KEYWORD_CHANGED", null)).isEqualTo("İçerik değişti");
        assertThat(EscalationService.resolvedTypeLabel("KEYWORD_CHANGED")).isEqualTo("İçerik Değişikliği");
    }
}
