package com.sitemonitor.service;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.MimeMessageHelper;

import java.io.ByteArrayOutputStream;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E-posta marka logosu — TEK yardımcı ({@link BrandMailAssets}) davranışları (BRAND.md §5.1):
 * severity→varyant eşlemesi, classpath varlıkları, Outlook-güvenli header lockup'ı ve
 * multipart/related + Content-ID yapısı (gerçek SMTP yok — MimeMessage bellekte).
 */
class BrandMailAssetsTest {

    @Test
    @DisplayName("variantForLevel: yalnız CRITICAL → critical; WARNING/HIGH/MEDIUM/LOW/INFO/null → warning")
    void variantMapping() {
        assertThat(BrandMailAssets.variantForLevel("CRITICAL")).isEqualTo("critical");
        assertThat(BrandMailAssets.variantForLevel("critical")).isEqualTo("critical");
        assertThat(BrandMailAssets.variantForLevel("WARNING")).isEqualTo("warning");
        assertThat(BrandMailAssets.variantForLevel("HIGH")).isEqualTo("warning");
        assertThat(BrandMailAssets.variantForLevel("MEDIUM")).isEqualTo("warning");
        assertThat(BrandMailAssets.variantForLevel("LOW")).isEqualTo("warning");
        assertThat(BrandMailAssets.variantForLevel("INFO")).isEqualTo("warning");
        assertThat(BrandMailAssets.variantForLevel(null)).isEqualTo("warning");
        // Eşlenmemiş değer sessiz düşmez: warning'e düşer (WARN loglanır — davranış aynı kalır)
        assertThat(BrandMailAssets.variantForLevel("BILINMEYEN")).isEqualTo("warning");
    }

    @Test
    @DisplayName("classpath: email-{ok,warning,critical}.png mevcut ve FİZİKSEL 32px — attribute silinse bile doğal boyut = hedef boyut (saha bulgusu #3)")
    void classpathAssetsPresent() {
        for (String v : new String[] { "ok", "warning", "critical" }) {
            byte[] b = BrandMailAssets.logoBytes(v);
            assertThat(b).as("email-assets/email-%s.png", v).isNotNull();
            // 64/192/320px'lik varlık regresyonunu boyutla yakala: 32px set ~2.2KB
            assertThat(b.length).as("email-%s.png boyutu", v).isBetween(500, 10_000);
            // PNG IHDR genişliği gerçekten 32 mi (bayt 16-19 big-endian)
            int width = ((b[16] & 0xFF) << 24) | ((b[17] & 0xFF) << 16) | ((b[18] & 0xFF) << 8) | (b[19] & 0xFF);
            assertThat(width).as("email-%s.png fiziksel genişlik", v).isEqualTo(32);
        }
    }

    @Test
    @DisplayName("headerLockup: TABLO-hücreli dizilim (forward zinciri kıramaz) + 32px attribute + cid + 'Site Monitor'")
    void headerLockupOutlookSafe() {
        String h = BrandMailAssets.headerLockup();
        assertThat(h).startsWith("<table");                                  // hücre tabanlı — inline img+span değil
        assertThat(h).contains("src=\"cid:brand-logo\"");
        assertThat(h).contains("width=\"32\"").contains("height=\"32\"");   // attribute — CSS'e güvenilmez
        assertThat(h).contains("white-space:nowrap");                       // yazı alt satıra düşemez
        assertThat(h).contains(">Site Monitor</td>");
        assertThat(h).doesNotContain("SiteMonitor");   // bitişik yazım maillerde yasak
    }

    @Test
    @DisplayName("addInline: cid'li HTML'de multipart/related + Content-ID oluşur; cid'siz HTML'de eklenmez")
    void mimeStructure() throws Exception {
        Session session = Session.getInstance(new Properties());
        MimeMessage msg = new MimeMessage(session);
        MimeMessageHelper helper = new MimeMessageHelper(msg, true, "UTF-8");
        helper.setTo("ops@example.com");
        helper.setFrom("noreply@sitemonitor");
        helper.setSubject("x");
        String html = "<html><body><table><tr><td>" + BrandMailAssets.headerLockup() + "</td></tr></table></body></html>";
        helper.setText("alarm", html);
        BrandMailAssets.addInline(helper, html, "critical");
        msg.saveChanges();
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        msg.writeTo(raw);
        String mime = raw.toString("ISO-8859-1");
        assertThat(mime).contains("Content-ID: <brand-logo>");
        assertThat(mime).contains("multipart/related");

        MimeMessage msg2 = new MimeMessage(session);
        MimeMessageHelper h2 = new MimeMessageHelper(msg2, true, "UTF-8");
        h2.setTo("ops@example.com");
        h2.setSubject("y");
        h2.setText("t", "<p>logosuz</p>");
        BrandMailAssets.addInline(h2, "<p>logosuz</p>", "ok");
        msg2.saveChanges();
        ByteArrayOutputStream raw2 = new ByteArrayOutputStream();
        msg2.writeTo(raw2);
        assertThat(raw2.toString("ISO-8859-1")).doesNotContain("Content-ID: <brand-logo>");
    }
}
