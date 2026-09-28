package com.sitemonitor.service.mail;

import com.sitemonitor.service.mail.MailKit.Badge;
import com.sitemonitor.service.mail.MailKit.Bar;
import com.sitemonitor.service.mail.MailKit.Kpi;
import com.sitemonitor.service.mail.MailKit.RankRow;
import com.sitemonitor.service.mail.MailTokens.Tone;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Haftalık rapor yapı taşları (2026-09-28): değişim çipli KPI kutusu, çubuk listesi, sıralı liste kartı ve
 * karanlık tuval. Her biri Outlook-güvenli dilde kalmalı (td bgcolor + düz hex, yüzde genişlik, tek stil bloğu).
 */
class MailKitReportBlocksTest {

    private static final Pattern MSO = Pattern.compile("(?s)<!--\\[if mso\\]>.*?<!\\[endif\\]-->");

    @Test
    @DisplayName("kpis: etiket/değer/çip/ipucu kaçırılır; 4 kutu telefonda .tile (2×2), çip tonlu zemin + yazı")
    void kpis_escapeAndTone() {
        String h = MailKit.kpis(List.of(
                new Kpi("Ort. <b>", "99.77%", Tone.SUCCESS.strong, "▲ 0.12 puan", Tone.SUCCESS, "geçen hafta & <i>"),
                new Kpi("B", "1", null, null, null, null), new Kpi("C", "2", null, "değişmedi", null, null), new Kpi("D", "3", null, null, null, "x")));
        assertThat(h).contains("Ort. &lt;b&gt;").contains("geçen hafta &amp; &lt;i&gt;").doesNotContain("<b>").doesNotContain("<i>");
        assertThat(h.split("class=\"tile\"", -1).length - 1).isEqualTo(4);
        assertThat(h).contains("background-color:" + Tone.SUCCESS.bg).contains("color:" + Tone.SUCCESS.text).contains("▲ 0.12 puan");
        assertThat(h).contains("background-color:" + MailTokens.SECONDARY);          // ton verilmemiş çip → nötr
        assertThat(MailKit.kpis(List.of())).isEmpty();
    }

    @Test
    @DisplayName("bars: yüzde AŞAĞI yuvarlanır (99.9 → %99, kusur çubukta seçilir); pct null → çubuk yok; açıklama kaçırılır")
    void bars_floorAndOptionalBar() {
        String h = MailKit.bars(List.of(
                new Bar("Ping", "99.9%", Tone.SUCCESS.strong, 99.9, Tone.SUCCESS.strong, "6 izleme · <script>"),
                new Bar("DNS", "veri yok", MailTokens.MUTED, null, null, null)));
        assertThat(h).contains("width=\"99%\"").doesNotContain("width=\"100%\" height=\"8\"");
        assertThat(h).contains("&lt;script&gt;").doesNotContain("<script>");
        assertThat(h.split("height=\"8\"", -1).length - 1).isEqualTo(2);           // yalnız Ping'in iki hücresi
        assertThat(MailKit.bars(List.of())).isEmpty();
    }

    @Test
    @DisplayName("rankList: satır ayraçlı tek kart, boş alt satır atlanır, rozet sağa yaslı sarmalayıcıda, sabit sağ sütun < 500px")
    void rankList_layout() {
        String h = MailKit.rankList(List.of(
                new RankRow("<a href=\"https://www.example.com\">a</a>", java.util.Arrays.asList("m1", null, " "), "97.42%", Badge.tint("KESİNTİ", Tone.WARNING)),
                new RankRow("b", List.of(), "100.00%", null)));
        assertThat(h.split("<p style=\"margin:2px 0 0", -1).length - 1).isEqualTo(1);   // yalnız "m1" (tek paragraf)
        assertThat(h).contains("<span style=\"display:inline-block;background-color:" + Tone.WARNING.bg + ";border:1px solid "
                + Tone.WARNING.border).contains(">KESİNTİ</span>").doesNotContain("<table role=\"presentation\" align=\"right\"");
        assertThat(h).contains("width=\"" + MailKit.RANK_VALUE_W + "\"");
        assertThat(h.split("border-bottom:1px solid " + MailTokens.DIVIDER, -1).length - 1).isEqualTo(1);   // 2 satır → 1 ayraç
        assertThat(h).doesNotContain("border-left").doesNotContain("m-only");
    }

    @Test
    @DisplayName("open(extraCss): ek kurallar TEK stil bloğuna eklenir (ikinci <style> yok); boş ek → birebir aynı çıktı")
    void open_extraCssSingleStyle() {
        String plain = MailKit.open("t", "p", 640);
        assertThat(MailKit.open("t", "p", 640, null)).isEqualTo(plain);
        assertThat(MailKit.open("t", "p", 640, " ")).isEqualTo(plain);
        String dark = MailKit.open("t", "p", 640, MailKit.DARK_CANVAS_CSS);
        String outside = MSO.matcher(dark).replaceAll("");
        assertThat(outside.split("<style>", -1).length - 1).isEqualTo(1);
        assertThat(outside).contains(MailKit.DARK_CANVAS_CSS + "</style>").contains("@media only screen and (max-width:" + MailKit.BREAKPOINT + "px)");
        assertThat(dark).contains(MailKit.LIGHT_SCHEME_META);                       // kart kilidi kalır
    }

    @Test
    @DisplayName("karanlık tuval Gmail-güvenli: öznitelik seçicisi YOK, iç içe @ kuralı YOK, yalnız dış zemin (kart açık)")
    void darkCanvas_gmailSafe() {
        String css = MailKit.DARK_CANVAS_CSS;
        assertThat(css).startsWith("@media (prefers-color-scheme:dark){").doesNotContain("[");
        assertThat(css.indexOf('@', 1)).isEqualTo(-1);
        assertThat(css).contains("body,.outer{background-color:" + MailTokens.DARK_CANVAS + "!important}")
                .doesNotContain(".card").doesNotContain("{color:").doesNotContain(";color:");   // metin rengi çevrilmez
        String doc = MailDoc.create("t").darkCanvas().title("B", null).html();
        assertThat(doc).contains(css);
        assertThat(MailDoc.create("t").title("B", null).html()).doesNotContain("prefers-color-scheme");   // opt-in
    }
}
