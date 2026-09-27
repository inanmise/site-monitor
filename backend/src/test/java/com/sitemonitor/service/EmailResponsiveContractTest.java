package com.sitemonitor.service;

import com.sitemonitor.service.mail.MailDoc;
import com.sitemonitor.service.mail.MailKit;
import com.sitemonitor.service.mail.MailTokens;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E-POSTA DUYARLILIK + MARKA SÖZLEŞMESİ (e-posta yeniden tasarımı 2026-09-26; BRAND.md §5.1).
 * {@link EmailSamples}'taki HER e-posta türünü (35 tür + önem/boş/zorlayıcı varyantlar) şu
 * kurallara karşı denetler — eski {@code EmailTemplateStandardTest}'in yerini alır (onun hâlâ
 * geçerli kuralları: dış görsel yok, "Site Monitor" yazımı, tek CID logo — burada devam eder):
 *
 * <ol>
 *   <li>viewport var, {@code maximum-scale} YOK (yakınlaştırma kilidi erişilebilirlik ihlali);</li>
 *   <li>{@code [if mso]} dışında {@code width:NNNpx} ≥ 500 ya da tablo/hücre {@code width="NNN"} ≥ 500 YOK
 *       (kart akışkan: {@code width:100%;max-width:600px}, sabit genişlik yalnız Outlook hayalet tablosunda);</li>
 *   <li>{@code border-left:} YOK, 4/5px renkli şerit hücresi ({@code width="4"}) YOK — sol şerit kuralı;</li>
 *   <li>tüm {@code font-size} ≥ 12px (0 = görünmez aralayıcı);</li>
 *   <li>CTA butonları ≥ 44px dokunma hedefi (HTML: padding + line-height; Outlook: VML yüksekliği);</li>
 *   <li>tam BİR logo: {@code cid:brand-logo}, {@code width="32" height="32"} öznitelikli;</li>
 *   <li>düz metin parçası dolu ve HTML değil;</li>
 *   <li>{@code rgba(} YOK, div zemini YOK, dış URL'li görsel YOK, "SiteMonitor" bitişik yazımı YOK;</li>
 *   <li>tek duyarlı {@code <style>} bloğu (MSO dışı) ve mobil kırılımı taşıyor; mobil kopyalar Outlook'tan gizli.</li>
 * </ol>
 */
class EmailResponsiveContractTest {

    private static List<EmailSamples.Sample> samples;

    @BeforeAll
    static void render() {
        samples = new EmailSamples().all();
    }

    private static final Pattern MSO = Pattern.compile("(?s)<!--\\[if mso\\]>.*?<!\\[endif\\]-->");
    private static final Pattern CSS_WIDTH = Pattern.compile("(?i)(?<![\\w-])width\\s*:\\s*(\\d+)px");
    private static final Pattern ATTR_WIDTH = Pattern.compile("(?i)<(?:table|td)\\b[^>]*?\\swidth=[\"'](\\d+)[\"']");
    private static final Pattern STRIPE = Pattern.compile("(?i)<td\\b[^>]*?\\swidth=[\"'][1-6][\"'][^>]*bgcolor");
    private static final Pattern FONT = Pattern.compile("(?i)font-size\\s*:\\s*(\\d+(?:\\.\\d+)?)px");
    private static final Pattern BTN = Pattern.compile("<a class=\"btn-full\"[^>]*style=\"([^\"]*)\"");
    private static final Pattern VML_H = Pattern.compile("<v:roundrect[^>]*height:(\\d+)px");
    private static final Pattern PAD = Pattern.compile("padding:(\\d+)px");
    private static final Pattern LH = Pattern.compile("line-height:(\\d+)px");
    private static final Pattern DIV_BG = Pattern.compile("(?i)<div\\b[^>]*style=\"[^\"]*background");

    /** Bir e-postanın sözleşme ihlalleri (boş liste = uyumlu). */
    static List<String> violations(String html, String text) {
        List<String> v = new ArrayList<>();
        if (html == null || html.isBlank()) { v.add("boş HTML"); return v; }
        if (!html.contains("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")) v.add("viewport meta yok");
        if (html.contains("maximum-scale")) v.add("maximum-scale var");
        if (!html.contains("x-apple-disable-message-reformatting")) v.add("x-apple-disable-message-reformatting yok");
        String outside = MSO.matcher(html).replaceAll("");
        Matcher w = CSS_WIDTH.matcher(outside);
        while (w.find()) if (Integer.parseInt(w.group(1)) >= 500) v.add("sabit genişlik (MSO dışı): width:" + w.group(1) + "px");
        Matcher a = ATTR_WIDTH.matcher(outside);
        while (a.find()) if (Integer.parseInt(a.group(1)) >= 500) v.add("sabit tablo/hücre genişliği (MSO dışı): width=" + a.group(1));
        if (html.toLowerCase().contains("border-left")) v.add("border-left (sol şerit) var");
        if (STRIPE.matcher(html).find()) v.add("renkli şerit hücresi var");
        Matcher f = FONT.matcher(html);
        while (f.find()) {
            double px = Double.parseDouble(f.group(1));
            if (px != 0 && px < 12) v.add("font-size " + f.group(1) + "px < 12");
        }
        Matcher b = BTN.matcher(html);
        while (b.find()) {
            Matcher p = PAD.matcher(b.group(1));
            Matcher l = LH.matcher(b.group(1));
            int pad = p.find() ? Integer.parseInt(p.group(1)) : 0;
            int lh = l.find() ? Integer.parseInt(l.group(1)) : 0;
            if (pad * 2 + lh < 44) v.add("buton dokunma hedefi " + (pad * 2 + lh) + "px < 44");
        }
        Matcher vh = VML_H.matcher(html);
        while (vh.find()) if (Integer.parseInt(vh.group(1)) < 44) v.add("VML buton " + vh.group(1) + "px < 44");
        int logos = count(html, "src=\"cid:" + BrandMailAssets.CID + "\"");
        if (logos != 1) v.add("logo sayısı " + logos + " (tam 1 olmalı)");
        if (!html.contains("width=\"32\" height=\"32\"")) v.add("logo width/height özniteliği yok");
        if (html.contains("rgba(")) v.add("rgba( var");
        if (DIV_BG.matcher(html).find()) v.add("div zemini var");
        if (html.contains("img src=\"http") || html.contains("img src='http")) v.add("dış URL'li görsel");
        if (html.contains("SiteMonitor")) v.add("'SiteMonitor' bitişik yazım");
        if (count(outside, "<style>") != 1) v.add("MSO dışı <style> sayısı " + count(outside, "<style>") + " (tam 1)");
        if (!outside.contains("@media only screen and (max-width:" + MailKit.BREAKPOINT + "px)")) v.add("mobil medya sorgusu yok");
        if (html.contains("class=\"m-only\"") && !html.contains("<!--[if !mso]><!--><div class=\"m-only\" style=\"display:none;")) {
            v.add("mobil kopya Outlook'tan / varsayılan görünümden gizli değil");
        }
        if (text == null || text.isBlank()) v.add("düz metin parçası boş");
        else if (text.contains("<table") || text.contains("<td") || text.contains("<div") || text.contains("<p ")) v.add("düz metin HTML içeriyor");
        return v;
    }

    private static int count(String s, String needle) {
        int c = 0, i = 0;
        while ((i = s.indexOf(needle, i)) >= 0) { c++; i += needle.length(); }
        return c;
    }

    @Test
    @DisplayName("HER e-posta türü duyarlılık + marka sözleşmesine uyar")
    void everyMailConforms() {
        Map<String, List<String>> bad = new HashMap<>();
        for (EmailSamples.Sample s : samples) {
            List<String> v = violations(s.html(), s.text());
            if (!v.isEmpty()) bad.put(s.slug(), v);
        }
        assertThat(bad).as("sözleşme ihlalleri").isEmpty();
    }

    @Test
    @DisplayName("örnek listesi tüm aileleri ve 35 türün hepsini kapsar (yeni tür eklenip unutulmasın)")
    void catalogueIsComplete() {
        Set<String> families = new LinkedHashSet<>();
        for (EmailSamples.Sample s : samples) families.add(s.family());
        assertThat(families).containsExactlyInAnyOrder(
                "alert", "resolved", "storm", "admin", "issue", "smtp", "reminder", "incident", "weekly", "report",
                "noc");   // 7/24 İzleme Ekibi (2026-09-27)
        assertThat(samples).hasSizeGreaterThanOrEqualTo(55);
        assertThat(samples.stream().map(EmailSamples.Sample::slug).distinct().count()).isEqualTo(samples.size());
    }

    @Test
    @DisplayName("kart akışkan: max-width 600/640 + Outlook hayalet tablosu; rapor ailesi 640")
    void cardIsFluidWithMsoGhost() {
        for (EmailSamples.Sample s : samples) {
            boolean wide = s.html().contains("max-width:" + MailTokens.WIDTH_WIDE + "px;background-color");
            int w = wide ? MailTokens.WIDTH_WIDE : MailTokens.WIDTH;
            assertThat(s.html()).as(s.slug()).contains("class=\"card\" align=\"center\" width=\"100%\"")
                    .contains("<!--[if mso]><table role=\"presentation\" align=\"center\" width=\"" + w + "\"");
            if (s.family().equals("report") || s.slug().startsWith("weekly-report-p") || s.slug().startsWith("weekly-report-m")
                    || s.family().equals("incident")) {
                assertThat(wide).as("%s veri-yoğun → 640", s.slug()).isTrue();
            }
        }
    }

    @Test
    @DisplayName("ısırma kontrolü: bozuk HTML her kuralda yakalanır (kapı boşa geçmiyor)")
    void checkerBites() {
        String good = MailDoc.create("t").title("Başlık", "alt").button("https://example.com", "Aç").footerMeta("m").html();
        assertThat(violations(good, "metin")).isEmpty();
        assertThat(violations(good.replace("initial-scale=1\">", "initial-scale=1,maximum-scale=1\">"), "x")).anyMatch(s -> s.contains("maximum-scale"));
        assertThat(violations(good.replace("max-width:600px", "width:600px"), "x")).anyMatch(s -> s.contains("sabit genişlik"));
        assertThat(violations(good.replace("width=\"100%\" cellpadding", "width=\"600\" cellpadding"), "x")).anyMatch(s -> s.contains("tablo/hücre"));
        assertThat(violations(good.replace("<h1 ", "<td width=\"4\" bgcolor=\"#dc2626\"></td><h1 "), "x")).anyMatch(s -> s.contains("şerit"));
        assertThat(violations(good.replace("<h1 ", "<p style=\"border-left:4px solid #dc2626\"></p><h1 "), "x")).anyMatch(s -> s.contains("border-left"));
        assertThat(violations(good.replace("font-size:22px", "font-size:11px"), "x")).anyMatch(s -> s.contains("font-size 11px"));
        assertThat(violations(good.replace("padding:12px 20px", "padding:6px 20px"), "x")).anyMatch(s -> s.contains("dokunma hedefi"));
        assertThat(violations(good.replace("height:44px;v-text", "height:32px;v-text"), "x")).anyMatch(s -> s.contains("VML buton"));
        assertThat(violations(good.replace("</h1>", "</h1><img src=\"cid:brand-logo\">"), "x")).anyMatch(s -> s.contains("logo sayısı 2"));
        assertThat(violations(good.replace("color:#09090b", "color:rgba(0,0,0,.5)"), "x")).anyMatch(s -> s.contains("rgba"));
        assertThat(violations(good.replace("<h1 ", "<div style=\"background:#fef2f2\"></div><h1 "), "x")).anyMatch(s -> s.contains("div zemini"));
        assertThat(violations(good.replace("<h1 ", "<img src=\"https://example.com/x.png\"><h1 "), "x")).anyMatch(s -> s.contains("dış URL"));
        assertThat(violations(good.replace("<title>", "<title>SiteMonitor "), "x")).anyMatch(s -> s.contains("bitişik"));
        assertThat(violations(good.replace("</head>", "<style>p{}</style></head>"), "x")).anyMatch(s -> s.contains("<style> sayısı 2"));
        assertThat(violations(good, " ")).anyMatch(s -> s.contains("düz metin parçası boş"));
        assertThat(violations(good, "<table>")).anyMatch(s -> s.contains("HTML içeriyor"));
    }

    @Test
    @DisplayName("tek açık-tema meta'sı her e-postada (eski iki LIGHT_SCHEME_META varyantı birleşti)")
    void singleLightSchemeMeta() {
        for (EmailSamples.Sample s : samples) {
            assertThat(count(s.html(), MailKit.LIGHT_SCHEME_META)).as(s.slug()).isEqualTo(1);
            assertThat(s.html()).as(s.slug()).doesNotContain("content='light only'");
        }
    }

    @Test
    @DisplayName("subjectDisplayName: monitör adı > şema-soyulmuş adres; çıplak http subject'e giremez")
    void subjectDisplayName() {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("monitor_name", "Sağlık İçerik Kontrolü");
        assertThat(EscalationService.subjectDisplayName(ctx, "http://localhost:8080/health- duplicate"))
                .isEqualTo("Sağlık İçerik Kontrolü");
        assertThat(EscalationService.subjectDisplayName(Map.of(), "https://example.com/x"))
                .isEqualTo("example.com/x");
        assertThat(EscalationService.subjectDisplayName(null, "example.com")).isEqualTo("example.com");
        Map<String, Object> nullish = new HashMap<>();
        nullish.put("monitor_name", "null");
        assertThat(EscalationService.subjectDisplayName(nullish, "http://x.y")).isEqualTo("x.y");
    }
}
