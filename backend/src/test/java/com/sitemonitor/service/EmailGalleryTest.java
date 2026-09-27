package com.sitemonitor.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E-POSTA GALERİSİ (varsayılan KAPALI) — {@link EmailSamples}'taki HER e-postayı
 * {@code <dir>/<slug>.html} + {@code <slug>.txt} olarak yazar; {@code index.html} her maili 390px
 * (telefon) ve 640px (masaüstü) iframe'lerinde yan yana gösterir; {@code manifest.txt} Playwright
 * ekran görüntüsü testinin ({@code frontend/e2e/email-gallery.spec.js}) okuduğu slug listesidir.
 *
 * <p>Çalıştırma: {@code mvn test -Dtest=EmailGalleryTest -Demail.gallery.dir=<klasör>}.
 * Gerçek gönderimde CID olan görseller tarayıcıda çözülemez → marka logosu gerçek PNG'nin data
 * URI'sine, ekran görüntüsü/rapor görselleri yer tutucu PNG'ye çevrilir (e-posta yeniden tasarımı 2026-09-26).
 */
@EnabledIfSystemProperty(named = "email.gallery.dir", matches = ".+")
class EmailGalleryTest {

    private static final Pattern OTHER_CID = Pattern.compile("src=\"cid:(shot\\d+|img\\d+|incimg\\d+)\"");

    @Test
    void writeGallery() throws Exception {
        Path dir = Path.of(System.getProperty("email.gallery.dir"));
        Files.createDirectories(dir);
        List<EmailSamples.Sample> samples = new EmailSamples().all();
        String placeholder = "data:image/png;base64," + Base64.getEncoder().encodeToString(placeholderPng());

        StringBuilder index = new StringBuilder("<!DOCTYPE html><html lang=\"tr\"><head><meta charset=\"UTF-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>Site Monitor e-posta galerisi</title>"
                + "<style>body{margin:0;padding:24px;background:#f4f4f5;font-family:Inter,'Segoe UI',Arial,sans-serif;color:#09090b}"
                + "h1{font-size:20px;margin:0 0 4px}p{color:#71717a;margin:0 0 24px;font-size:14px}"
                + "section{margin:0 0 40px}h2{font-size:15px;margin:0 0 8px}h2 small{color:#71717a;font-weight:400}"
                + ".row{display:flex;gap:24px;align-items:flex-start;flex-wrap:wrap}"
                + "iframe{border:1px solid #e4e4e7;border-radius:10px;background:#fff}a{color:#2563eb}</style></head><body>"
                + "<h1>Site Monitor — e-posta galerisi</h1><p>" + samples.size() + " örnek · her biri 390px ve 640px genişlikte</p>");
        StringBuilder manifest = new StringBuilder();
        for (EmailSamples.Sample s : samples) {
            assertThat(s.html()).as("%s html", s.slug()).isNotBlank();
            byte[] logo = BrandMailAssets.logoBytes(s.logo());
            String html = s.html();
            if (logo != null) {
                html = html.replace("src=\"cid:" + BrandMailAssets.CID + "\"",
                        "src=\"data:image/png;base64," + Base64.getEncoder().encodeToString(logo) + "\"");
            }
            Matcher m = OTHER_CID.matcher(html);
            html = m.replaceAll(Matcher.quoteReplacement("src=\"" + placeholder + "\""));
            Files.writeString(dir.resolve(s.slug() + ".html"), html, StandardCharsets.UTF_8);
            Files.writeString(dir.resolve(s.slug() + ".txt"), s.text() == null ? "" : s.text(), StandardCharsets.UTF_8);
            manifest.append(s.slug()).append('\n');
            index.append("<section id=\"").append(s.slug()).append("\"><h2>").append(s.slug()).append(" <small>")
                 .append(s.family()).append(" · <a href=\"").append(s.slug()).append(".html\">aç</a> · <a href=\"")
                 .append(s.slug()).append(".txt\">düz metin</a></small></h2><div class=\"row\">")
                 .append("<iframe src=\"").append(s.slug()).append(".html\" width=\"390\" height=\"900\" loading=\"lazy\" title=\"")
                 .append(s.slug()).append(" 390\"></iframe>")
                 .append("<iframe src=\"").append(s.slug()).append(".html\" width=\"640\" height=\"900\" loading=\"lazy\" title=\"")
                 .append(s.slug()).append(" 640\"></iframe></div></section>");
        }
        index.append("</body></html>");
        Files.writeString(dir.resolve("index.html"), index.toString(), StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("manifest.txt"), manifest.toString(), StandardCharsets.UTF_8);
    }

    /** Ekran görüntüsü / rapor görseli yer tutucusu (1200×600). */
    private static byte[] placeholderPng() throws Exception {
        BufferedImage img = new BufferedImage(1200, 600, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setPaint(new GradientPaint(0, 0, new Color(0xEFF6FF), 1200, 600, new Color(0xF4F4F5)));
        g.fillRect(0, 0, 1200, 600);
        g.setColor(new Color(0xBFDBFE));
        for (int i = 0; i < 8; i++) g.fillRoundRect(80 + i * 130, 520 - (60 + (i * 47) % 300), 90, 60 + (i * 47) % 300, 12, 12);
        g.setColor(new Color(0x71717A));
        g.drawRect(0, 0, 1199, 599);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }
}
