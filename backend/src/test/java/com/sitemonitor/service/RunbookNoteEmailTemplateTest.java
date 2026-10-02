package com.sitemonitor.service;

import com.sitemonitor.service.mail.RunbookNote;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runbook notu — e-posta şablonlarının HER ailesinde (2026-10-01): tür-özel MailDoc belgeleri (uptime/port/dns kesintisi,
 * içerik doğrulama, ping, DNS değişikliği) ve EmailTemplateBuilder ailesi (HTTP, alan adı, sayfa, sentetik, sayfa hızı).
 * Rehber anahtarı yoksa çıktı aynı; varsa HTML ve düz metin TEK bitişik ekleme alır (mevcut bayt değişmez). Çözüm
 * şablonları anahtarı görse bile notu ÇİZMEZ.
 */
class RunbookNoteEmailTemplateTest {

    static final String TS = "\\d{2}\\.\\d{2}\\.\\d{4} \\d{2}:\\d{2}(:\\d{2})?";
    static final String GUIDE = "Servisi yeniden başlat <b>hemen</b> & 5 dk bekle.\nSonra nöbetçiyi ara.";

    private final EmailNotificationService svc = new EmailSamples().svc;

    private static Map<String, Object> ctx(String alertType) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("team_name", "Takım A");
        m.put("alert_event_id", 4242L);
        m.put("monitor_id", 7);
        m.put("first_failure_at", "2026-10-01T06:00:00");
        if (alertType.startsWith("PORT")) { m.put("port", 5432); m.put("protocol", "TCP"); }
        if (alertType.startsWith("DNS")) { m.put("record_type", "A"); m.put("old_values", "1.1.1.1"); m.put("new_values", "2.2.2.2"); }
        if (alertType.startsWith("KEYWORD")) { m.put("keyword", "Ödeme"); m.put("operator", "GTE"); m.put("match_count", 1); m.put("occurrences", 0); }
        return m;
    }

    private static Map<String, Object> withGuide(Map<String, Object> c) {
        Map<String, Object> m = new LinkedHashMap<>(c);
        m.put(RunbookNote.CTX_KEY, GUIDE);
        return m;
    }

    private static String norm(String s) { return s == null ? "" : s.replaceAll(TS, "<TS>"); }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "ACCESSIBILITY,   www.example.com",
            "PORT_DOWN,       db.example.com",
            "DNS_FAILURE,     example.com",
            "DNS_CHANGED,     example.com",
            "KEYWORD,         https://kw.example.com/",
            "PING_DOWN,       db.example.com",
            "HTTP_DOWN,       https://www.example.com/",
            "DOMAINMON_EXPIRY,example.com",
            "PAGE_INTEGRITY,  https://www.example.com/",
            "SCRIPTED_FAIL,   Ödeme akışı",
            "PAGESPEED_SLOW,  https://www.example.com/",
    })
    @DisplayName("Rehber anahtarı → HTML + düz metin TEK bitişik ekleme (kaçırılmış); anahtarsız çıktı değişmez")
    void everyAlertFamily_pureInsertion(String type, String domain) {
        Map<String, Object> base = ctx(type);
        String msg = "KRİTİK: " + domain + " sorunlu";
        String offHtml = norm(svc.buildAlertEmailHtml("s", msg, domain, "CRITICAL", type, null, base));
        String offText = norm(svc.buildAlertEmailText("s", msg, domain, "CRITICAL", type, null, base));
        String onHtml = norm(svc.buildAlertEmailHtml("s", msg, domain, "CRITICAL", type, null, withGuide(base)));
        String onText = norm(svc.buildAlertEmailText("s", msg, domain, "CRITICAL", type, null, withGuide(base)));

        String insHtml = RunbookNoteNotificationTest.pureInsertion(offHtml, onHtml);
        assertThat(insHtml).contains(RunbookNote.TITLE)
                .contains("Servisi yeniden başlat &lt;b&gt;hemen&lt;/b&gt; &amp; 5 dk bekle.<br>Sonra nöbetçiyi ara.")
                .doesNotContain("<b>hemen");
        assertThat(onHtml.indexOf(RunbookNote.TITLE)).isLessThan(onHtml.indexOf("Site Monitor otomatik bir izleme sistemidir"));
        String insText = RunbookNoteNotificationTest.pureInsertion(offText, onText);
        assertThat(insText).contains("Servisi yeniden başlat <b>hemen</b> & 5 dk bekle.").contains(RunbookNote.HINT);

        // Anahtarsız iki çizim aynı (rehber yok = bugünkü şablon)
        assertThat(norm(svc.buildAlertEmailHtml("s", msg, domain, "CRITICAL", type, null, new LinkedHashMap<>(base))))
                .isEqualTo(offHtml);
    }

    @Test
    @DisplayName("Çözüm e-postaları anahtarı görse bile notu ÇİZMEZ (yalnız alarm/re-alert maillerine eklenir)")
    void resolutionTemplates_ignoreRunbook() {
        for (String[] c : new String[][]{
                {"KEYWORD", "https://kw.example.com/"}, {"PING_DOWN", "db.example.com"}, {"PORT_DOWN", "db.example.com"},
                {"DNS_CHANGED", "example.com"}, {"HTTP_DOWN", "https://www.example.com/"}, {"DOMAINMON_EXPIRY", "example.com"}}) {
            Map<String, Object> base = ctx(c[0]);
            String off = norm(svc.buildResolutionEmailHtml(c[1], c[0], "CRITICAL", null, "system", "2026-10-01T07:00:00",
                    "2026-10-01T06:00:00", base, "Takım A", null));
            String on = norm(svc.buildResolutionEmailHtml(c[1], c[0], "CRITICAL", null, "system", "2026-10-01T07:00:00",
                    "2026-10-01T06:00:00", withGuide(base), "Takım A", null));
            assertThat(on).as(c[0]).isEqualTo(off).doesNotContain(RunbookNote.TITLE);
        }
    }
}
