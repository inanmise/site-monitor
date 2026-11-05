package com.sitemonitor.service;

import com.sitemonitor.service.mail.MailDoc;
import com.sitemonitor.service.mail.MailKit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Alarm iletimi bandı (2026-11-05, kullanıcı kararı 6): iletilen ayrı postanın gövdesinin BAŞINDA (kartın içinde, ilk blok) nötr
 * bilgi bandı, düz metinde ilk satırlar. Bant yoksa posta bayt bayt aynıdır.
 */
class ForwardBandEmailTest {

    private final EmailNotificationService svc = new EmailSamples().svc;

    private static final String OWNER = "Dijital Kanallar Uygulama Geliştirme Takımı (Ödeme / Kart İşlemleri)";
    private static final String RECIPIENT = "Ağ Operasyon Merkezi — 7/24 İzleme";

    private static Map<String, Object> ctx() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("url", "https://portal.example.com/odeme");
        m.put("http_status", 503);
        m.put("monitor_id", 44);
        m.put("checked_at", "2026-09-26T07:00:00");
        m.put("team_name", "Takım A");
        m.put("alert_event_id", 95L);
        return m;
    }

    private static Map<String, Object> withNote(Map<String, Object> ctx, boolean plural) {
        Map<String, Object> m = new LinkedHashMap<>(ctx);
        m.put(EmailTemplateBuilder.CTX_FORWARD_NOTE, EmailTemplateBuilder.forwardNote(List.of(OWNER), RECIPIENT, plural));
        return m;
    }

    @Test
    @DisplayName("bant cümlesi: tek / çok sahip, çoğul, sahipsiz, alıcısız — kullanıcı kararındaki metin")
    void sentence() {
        assertThat(EmailTemplateBuilder.forwardBandText(EmailTemplateBuilder.forwardNote(List.of("Takım A"), "Takım B", false)))
                .isEqualTo("Bu alarm Takım A takımının izlemesine ait; size Takım B takımına tanımlı alarm iletimiyle gönderildi. "
                        + "Site Monitor'de görüntüleme erişiminiz yoksa ayrıntı için Takım A takımına başvurun.");
        assertThat(EmailTemplateBuilder.forwardBandText(
                EmailTemplateBuilder.forwardNote(List.of("Takım A", "Takım C", " Takım A "), "Takım B", true)))
                .isEqualTo("Bu alarmlar Takım A ve Takım C takımlarının izlemelerine ait; size Takım B takımına tanımlı alarm "
                        + "iletimiyle gönderildi. Site Monitor'de görüntüleme erişiminiz yoksa ayrıntı için Takım A ve Takım C "
                        + "takımlarına başvurun.");
        assertThat(EmailTemplateBuilder.forwardBandText(EmailTemplateBuilder.forwardNote(List.of(), "", false)))
                .startsWith("Bu alarm başka bir takımın izlemesine ait; size tanımlı bir alarm iletimiyle gönderildi.")
                .endsWith("izlemenin sahibi takıma başvurun.");
        assertThat(EmailTemplateBuilder.forwardBandText(null)).isNull();
    }

    @Test
    @DisplayName("bant yoksa posta AYNI nesne; varsa gövde açılışının hemen ardında ve düz metnin başında — başka hiçbir şey değişmez")
    void alertMailBand() {
        MailDoc.Mail base = svc.alertMail("[Site Monitor] YÜKSEK · Ödeme", "Ödeme portalı erişilemez.",
                "https://portal.example.com/odeme", "HIGH", "HTTP_DOWN", null, ctx());
        assertThat(EmailTemplateBuilder.withForwardBand(base, ctx())).isSameAs(base);
        assertThat(EmailTemplateBuilder.withForwardBand(base, null)).isSameAs(base);
        assertThat(base.html()).doesNotContain(EmailTemplateBuilder.FORWARD_BAND_TITLE);

        MailDoc.Mail fwd = svc.alertMail("[Site Monitor] YÜKSEK · Ödeme", "Ödeme portalı erişilemez.",
                "https://portal.example.com/odeme", "HIGH", "HTTP_DOWN", null, withNote(ctx(), false));
        String open = MailKit.bodyOpen();
        int at = fwd.html().indexOf(open) + open.length();
        String after = fwd.html().substring(at);
        assertThat(after).startsWith("<table role=\"presentation\"");
        assertThat(after.substring(0, after.indexOf(MailKit.esc(EmailTemplateBuilder.forwardBandText(
                EmailTemplateBuilder.forwardNote(List.of(OWNER), RECIPIENT, false)))))).contains(EmailTemplateBuilder.FORWARD_BAND_TITLE);
        // Bant bloğu çıkarılınca posta sahip postasıyla bayt bayt aynı
        String block = MailKit.space(MailKit.alert(com.sitemonitor.service.mail.MailTokens.Tone.NEUTRAL,
                MailKit.esc(EmailTemplateBuilder.FORWARD_BAND_TITLE),
                MailKit.esc(EmailTemplateBuilder.forwardBandText(EmailTemplateBuilder.forwardNote(List.of(OWNER), RECIPIENT, false)))), 16);
        assertThat(fwd.html().replace(block, "")).isEqualTo(base.html());
        assertThat(fwd.text()).startsWith("(i) " + EmailTemplateBuilder.FORWARD_BAND_TITLE + "\nBu alarm " + OWNER + " takımının");
        assertThat(fwd.text()).endsWith(base.text());
        // yalnız HTML dönen kurucu da aynı bandı taşır (günlük satırı gövdesi)
        assertThat(svc.buildAlertEmailHtml("[Site Monitor] YÜKSEK · Ödeme", "Ödeme portalı erişilemez.",
                "https://portal.example.com/odeme", "HIGH", "HTTP_DOWN", null, withNote(ctx(), false))).isEqualTo(fwd.html());
    }

    @Test
    @DisplayName("tür-özel şablonlar (erişim kesintisi, anahtar kelime) ve çözüm postası da bandı taşır")
    void otherTemplates() {
        for (String type : List.of("ACCESSIBILITY", "KEYWORD", "PING_DOWN", "DNS_CHANGED", "EXPIRY")) {
            String html = svc.buildAlertEmailHtml("[Site Monitor] konu", "ileti", "a.example.com", "HIGH", type, 5, withNote(ctx(), false));
            assertThat(html).as(type).contains(EmailTemplateBuilder.FORWARD_BAND_TITLE);
        }
        String resolved = svc.buildResolutionEmailHtml("a.example.com", "HTTP_DOWN", "HIGH", null, "Sistem (otomatik)",
                "2026-09-26T08:00:00", "2026-09-26T07:00:00", withNote(ctx(), false), OWNER, null);
        assertThat(resolved).contains(EmailTemplateBuilder.FORWARD_BAND_TITLE).contains(MailKit.esc(RECIPIENT));
        String resolvedPlain = svc.buildResolutionEmailHtml("a.example.com", "HTTP_DOWN", "HIGH", null, "Sistem (otomatik)",
                "2026-09-26T08:00:00", "2026-09-26T07:00:00", ctx(), OWNER, null);
        assertThat(resolvedPlain).doesNotContain(EmailTemplateBuilder.FORWARD_BAND_TITLE);
    }

    @Test
    @DisplayName("hazır postaya (fırtına / sessiz saat özeti) çoğul bant; gövde açılışı yoksa yalnız düz metin değişir")
    void readyMail() {
        String html = svc.buildQuietDigestHtml("Takım A", "01.10.2026 22:00–07:00", List.of());
        MailDoc.Mail m = EmailTemplateBuilder.withForwardNote(new MailDoc.Mail(html, MailKit.plainTextFor(html)),
                EmailTemplateBuilder.forwardNote(List.of("Takım A"), "Takım B", true));
        assertThat(m.html()).contains("Bu alarmlar Takım A takımının izlemelerine ait");
        assertThat(m.text()).startsWith("(i) İletilen alarm\nBu alarmlar Takım A");
        MailDoc.Mail raw = EmailTemplateBuilder.withForwardNote(new MailDoc.Mail("<p>yabancı</p>", "metin"),
                EmailTemplateBuilder.forwardNote(List.of("Takım A"), "Takım B", false));
        assertThat(raw.html()).isEqualTo("<p>yabancı</p>");
        assertThat(raw.text()).endsWith("\n\nmetin");
    }
}
