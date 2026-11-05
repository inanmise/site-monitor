package com.sitemonitor.service;

import com.sitemonitor.model.KeywordMonitor;
import com.sitemonitor.repository.KeywordMonitorRepository;
import com.sitemonitor.service.keyword.KeywordMailText;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Anahtar kelime alarm iletisi ve postası (kw sözleşmesi §8.1 E1 + B1, §8.3 E3): ileti ve başlık GÖZLEMDEN (sayfa açılamadıysa
 * "0 kez bulundu" / "İstenmeyen İfade Bulundu" yazılmaz), posta neden + ipuçları + sayfadan alıntı + son adres taşır; özel
 * konu şablonu izlemeden canlı okunur, yoksa standart konu.
 */
class KeywordAlertMailTest {

    private EscalationService escalation;
    private EmailNotificationService email;
    private KeywordMonitorRepository keywordRepo;

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
        keywordRepo = mock(KeywordMonitorRepository.class);
        ReflectionTestUtils.setField(escalation, "keywordMonitorRepo", keywordRepo);
    }

    private static Map<String, Object> ctx(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("monitor_id", 11L);
        m.put("monitor_name", "Ana sayfa");
        m.put("url", "https://shop.example.com/");
        m.put("keyword", "Sepete ekle");
        m.put("operator", "GTE");
        m.put("match_count", 1);
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    private String message(Map<String, Object> c) {
        return ReflectionTestUtils.invokeMethod(escalation, "monitoringMessage", "https://shop.example.com/",
                EscalationService.TYPE_KEYWORD, "CRITICAL", c);
    }

    @Test
    @DisplayName("B1 ileti: istek tamamlanmadı → 'sayfası açılamadı — <ayrıntı>', adet YOK; içermemeli kuralında da")
    void messageRequestFailed() {
        String msg = message(ctx("failure_reason", "DNS", "failure_detail", "shop.example.com çözümlenemedi.",
                "error", "UnknownHostException", "occurrences", 0, "operator", "LTE", "match_count", 0));
        assertThat(msg).contains("sayfası açılamadı").contains("çözümlenemedi").doesNotContain("0 kez bulundu")
                .doesNotContain("koşul sağlanmıyor");
        // nedeni olmayan eski bağlam (eski pod): hata metninden
        assertThat(message(ctx("error", "connect timed out"))).contains("sayfası açılamadı").contains("connect timed out");
    }

    @Test
    @DisplayName("B1 ileti: durum kodu / son URL nedeni → 'içerik doğrulaması başarısız'; koşul nedeni → eski metin + 'Neden:'")
    void messageByReason() {
        assertThat(message(ctx("failure_reason", "HTTP_STATUS", "failure_detail", "Kabul edilen: 2xx; gelen: 503.",
                "http_status", 503, "occurrences", 2)))
                .contains("içerik doğrulaması başarısız — Kabul edilen: 2xx; gelen: 503.")
                .doesNotContain("koşul sağlanmıyor");
        assertThat(message(ctx("failure_reason", "KEYWORD_NOT_FOUND", "failure_detail", "Sayfa yüklendi ama kelime yok.",
                "http_status", 200, "occurrences", 0)))
                .contains("en az 1 kez bulunmalı; koşul sağlanmıyor. Şu an 0 kez bulundu.")
                .contains("Neden: Sayfa yüklendi ama kelime yok.");
        // eski bağlam (neden yok) bugünkü metin
        assertThat(message(ctx("http_status", 200, "occurrences", 0)))
                .contains("koşul sağlanmıyor. Şu an 0 kez bulundu.").doesNotContain("Neden:");
    }

    @Test
    @DisplayName("E1 posta: açılmayan sayfa → başlık 'Sayfa Açılamadı', 'Bulunan adet' satırı yok; ipuçları, alıntı ve son adres")
    void alertMailE1() {
        Map<String, Object> failed = ctx("failure_reason", "CONNECT_TIMEOUT", "failure_detail", "Bağlantı 10 sn içinde kurulamadı.",
                "last_error", "connect timed out", "occurrences", 0, "operator", "LTE", "match_count", 0);
        String html = email.buildAlertEmailHtml("[Site Monitor] KRİTİK · x", message(failed), "https://shop.example.com/",
                "CRITICAL", EscalationService.TYPE_KEYWORD, null, failed);
        String text = email.buildAlertEmailText("[Site Monitor] KRİTİK · x", message(failed), "https://shop.example.com/",
                "CRITICAL", EscalationService.TYPE_KEYWORD, null, failed);
        for (String s : List.of(html, text)) {
            assertThat(s).contains("Sayfa Açılamadı").contains("Bağlantı 10 sn içinde kurulamadı").contains("Sayfa okunamadı")
                    .doesNotContain("Bulunan adet").doesNotContain("İstenmeyen İfade Bulundu");
        }

        String code = KeywordBodyAnalyzerHints.first();
        Map<String, Object> notFound = ctx("failure_reason", "KEYWORD_NOT_FOUND", "failure_detail", "Sayfa yüklendi ama kelime yok.",
                "http_status", 200, "occurrences", 0, "hints", List.of(code), "excerpt", "Oturum açın Kullanıcı adı",
                "final_url", "https://sso.example.com/giris");
        String t2 = email.buildAlertEmailText("[Site Monitor] KRİTİK · x", message(notFound), "https://shop.example.com/",
                "CRITICAL", EscalationService.TYPE_KEYWORD, null, notFound);
        assertThat(t2).contains("Koşul Sağlanmadı").contains("Bulunan adet").contains("0 kez")
                .contains("OLASI NEDENLER").contains(KeywordMailText.HINT_TITLES.get(code))
                .contains("SAYFADAN ALINTI").contains("Oturum açın Kullanıcı adı")
                .contains("Son adres").contains("https://sso.example.com/giris");

        // eski bağlam (E1 anahtarı yok): ipucu / alıntı bölümü yok
        Map<String, Object> legacy = ctx("http_status", 200, "occurrences", 0);
        String t3 = email.buildAlertEmailText("[Site Monitor] KRİTİK · x", message(legacy), "https://shop.example.com/",
                "CRITICAL", EscalationService.TYPE_KEYWORD, null, legacy);
        assertThat(t3).doesNotContain("OLASI NEDENLER").doesNotContain("SAYFADAN ALINTI").contains("Bulunan adet");
    }

    @Test
    @DisplayName("çözüm postası: alarm anı neden (anlık görüntü); açılmayan sayfada 'Alarm anı bulunan' yok; eski bağlamda bugünkü satır")
    void resolvedMail() {
        Map<String, Object> failed = ctx("failure_reason", "DNS", "failure_detail", "shop.example.com çözümlenemedi.",
                "occurrences", 0);
        String t = email.resolutionMail("https://shop.example.com/", EscalationService.TYPE_KEYWORD, "CRITICAL", null,
                "Sistem (otomatik)", "2026-11-05T08:00:00", "2026-11-05T07:00:00", failed, "Takım A", null).text();
        assertThat(t).contains("Alarm anı neden").contains("çözümlenemedi").doesNotContain("Alarm anı bulunan");
        String legacy = email.resolutionMail("https://shop.example.com/", EscalationService.TYPE_KEYWORD, "CRITICAL", null,
                "Sistem (otomatik)", "2026-11-05T08:00:00", "2026-11-05T07:00:00", ctx("occurrences", 0), "Takım A", null).text();
        assertThat(legacy).contains("Alarm anı bulunan").doesNotContain("Alarm anı neden");
    }

    @Test
    @DisplayName("E3 konu: şablon izlemeden CANLI; yoksa / izleme yoksa / okunamazsa null (standart konu); açılmayan sayfada {adet} boş")
    void mailSubjectTemplate() {
        KeywordMonitor m = new KeywordMonitor();
        m.setId(11L);
        m.setMailSubjectTemplate("[{seviye}] {ad} · {neden} · {adet}");
        when(keywordRepo.findById(11L)).thenReturn(Optional.of(m));
        assertThat(escalation.keywordMailSubject(ctx("failure_reason", "KEYWORD_NOT_FOUND", "occurrences", 0), "CRITICAL"))
                .isEqualTo("[KRİTİK] Ana sayfa · Kelime bulunamadı · 0");
        assertThat(escalation.keywordMailSubject(ctx("failure_reason", "DNS", "occurrences", 0), "WARNING"))
                .isEqualTo("[UYARI] Ana sayfa · DNS çözümlenemedi ·");

        m.setMailSubjectTemplate("  ");
        assertThat(escalation.keywordMailSubject(ctx(), "CRITICAL")).isNull();
        when(keywordRepo.findById(11L)).thenReturn(Optional.empty());
        assertThat(escalation.keywordMailSubject(ctx(), "CRITICAL")).isNull();
        when(keywordRepo.findById(11L)).thenThrow(new RuntimeException("db"));
        assertThat(escalation.keywordMailSubject(ctx(), "CRITICAL")).isNull();
        Map<String, Object> noId = ctx();
        noId.remove("monitor_id");
        assertThat(escalation.keywordMailSubject(noId, "CRITICAL")).isNull();
        ReflectionTestUtils.setField(escalation, "keywordMonitorRepo", null);
        assertThat(escalation.keywordMailSubject(ctx(), "CRITICAL")).isNull();
    }

    /** İlk ipucu kodu (katalog sırası) — test metnini koda bağlamadan. */
    private static final class KeywordBodyAnalyzerHints {
        static String first() {
            return com.sitemonitor.service.keyword.KeywordBodyAnalyzer.HINT_CODES.get(0);
        }
    }
}
