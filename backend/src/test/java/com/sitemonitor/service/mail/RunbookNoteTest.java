package com.sitemonitor.service.mail;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runbook notu saf dönüşümleri (2026-10-01): markdown → güvenli düz metin, kırpma, e-posta bölümü (kaçırılmış HTML,
 * düz metin paritesi) ve webhook eki. Rehber yokken HER yardımcının girdisini AYNEN bıraktığı da burada pinlenir.
 */
class RunbookNoteTest {

    @Test
    @DisplayName("toPlainText: başlık/kalın/italik/kod/alıntı/çit/çizgi/tablo ayracı işaretleri atılır, metin kalır")
    void toPlainText_stripsMarkdownFormatting() {
        String md = """
                # Alarm gelince
                **Önce** _servisi_ kontrol et: `systemctl status app`
                > Not: ~~eski~~ yeni prosedür
                ---
                ```
                kubectl rollout restart deploy/app
                ```
                - [ ] Log'a bak
                * Bekle
                1. Ara
                | a | b |
                |---|---|
                | 1 | 2 |
                snake_case_degisken kalmalı
                """;
        String plain = RunbookNote.toPlainText(md);
        assertThat(plain).startsWith("Alarm gelince\nÖnce servisi kontrol et: systemctl status app\nNot: eski yeni prosedür");
        assertThat(plain).contains("kubectl rollout restart deploy/app")
                .contains("• Log'a bak").contains("• Bekle").contains("1. Ara")
                .contains("| a | b |").contains("| 1 | 2 |")
                .contains("snake_case_degisken kalmalı");
        assertThat(plain).doesNotContain("**").doesNotContain("```").doesNotContain("# ").doesNotContain("---")
                .doesNotContain("~~").doesNotContain("`").doesNotContain("> ");
    }

    @Test
    @DisplayName("toPlainText: HTML etiketleri ve görseller atılır, bağlantı 'metin (adres)' olur, varlıklar çözülür")
    void toPlainText_stripsHtmlImagesKeepsLinkTargets() {
        String md = "Bkz. [Wiki sayfası](https://wiki.example.com/runbook) <b>kalın</b><script>alert(1)</script>"
                + " ![ekran](/api/incidents/images/5) &amp; &lt;tamam&gt; <https://x.example.com/a> <!-- gizli -->eşik < 5 ve > 3";
        String plain = RunbookNote.toPlainText(md);
        assertThat(plain).isEqualTo("Bkz. Wiki sayfası (https://wiki.example.com/runbook) kalınalert(1)"
                + " ekran & <tamam> https://x.example.com/a eşik < 5 ve > 3");
        assertThat(plain).doesNotContain("<b>").doesNotContain("<script>");
    }

    @Test
    @DisplayName("toPlainText: null/boş → \"\"; 3+ boş satır ikiye iner, CRLF normalize olur")
    void toPlainText_blankAndWhitespace() {
        assertThat(RunbookNote.toPlainText(null)).isEmpty();
        assertThat(RunbookNote.toPlainText("   \n  ")).isEmpty();
        assertThat(RunbookNote.toPlainText("a\r\n\r\n\r\n\r\nb  \r\nc")).isEqualTo("a\n\nb\nc");
    }

    @Test
    @DisplayName("truncate: tavanı aşan metin ≤ max karakter + '…'; kısa metin aynen; vekil çifti bölünmez")
    void truncate_boundsAndEllipsis() {
        String longText = "kelime ".repeat(400);
        String t = RunbookNote.truncate(longText, RunbookNote.EMAIL_MAX);
        assertThat(t.length()).isLessThanOrEqualTo(RunbookNote.EMAIL_MAX);
        assertThat(t).endsWith("…").doesNotEndWith(" …");
        assertThat(RunbookNote.truncate("kısa", 300)).isEqualTo("kısa");
        assertThat(RunbookNote.truncate(null, 10)).isEmpty();

        String emoji = "a".repeat(8) + "😀😀";          // 8 + 4 UTF-16 birimi
        String e = RunbookNote.truncate(emoji, 10);    // kesim emojinin ortasına düşerdi
        assertThat(e).endsWith("…");
        assertThat(Character.isHighSurrogate(e.charAt(e.length() - 2))).isFalse();
    }

    @Test
    @DisplayName("appendTo / textBlock / webhookMessage: bağlamda rehber YOKSA hiçbir şey eklenmez (bayt-özdeşlik)")
    void noGuide_isNoOp() {
        MailDoc a = MailDoc.create("t").title("h", "lead").footerWhy("Takım A");
        MailDoc b = MailDoc.create("t").title("h", "lead").footerWhy("Takım A");
        Map<String, Object> ctx = new LinkedHashMap<>(Map.of("team_name", "Takım A"));
        RunbookNote.appendTo(b, ctx);
        RunbookNote.appendTo(b, null);
        RunbookNote.appendTo(b, Map.of(RunbookNote.CTX_KEY, "   "));
        assertThat(b.html()).isEqualTo(a.html());
        assertThat(b.text()).isEqualTo(a.text());
        assertThat(RunbookNote.textBlock(ctx)).isEmpty();
        assertThat(RunbookNote.textBlock(null)).isEmpty();
        String msg = "KRİTİK: x erişilemiyor";
        assertThat(RunbookNote.webhookMessage(msg, null)).isSameAs(msg);
        assertThat(RunbookNote.webhookMessage(msg, "  \n ")).isSameAs(msg);
    }

    @Test
    @DisplayName("appendTo: 'Ne yapılmalı' kartı gövdenin SONUNDA (alt bilgiden önce), metin KAÇIRILMIŞ, düz metin pariteli")
    void appendTo_escapedCardAtEndOfBody() {
        MailDoc d = MailDoc.create("t").title("h", "lead").paragraph("son gövde bloğu").footerWhy("Takım A");
        RunbookNote.appendTo(d, Map.of(RunbookNote.CTX_KEY, "Servisi yeniden başlat <script>alert(1)</script>\nSonra ara & bekle"));
        String html = d.html();
        assertThat(html).contains("Ne yapılmalı").contains("İzlemenin rehberinden")
                .contains("&lt;script&gt;alert(1)&lt;/script&gt;").doesNotContain("<script>")
                .contains("Sonra ara &amp; bekle").contains("Rehber &amp; Notlar");
        int body = html.indexOf("son gövde bloğu"), note = html.indexOf("Ne yapılmalı"), footer = html.indexOf("Takım A");
        assertThat(body).isLessThan(note);
        assertThat(note).isLessThan(footer);
        assertThat(d.text()).contains("NE YAPILMALI").contains("Servisi yeniden başlat <script>alert(1)</script>")
                .contains(RunbookNote.HINT);
    }

    @Test
    @DisplayName("textBlock: başlık + kırpılmış metin + ipucu")
    void textBlock_truncatedWithHint() {
        String block = RunbookNote.textBlock(Map.of(RunbookNote.CTX_KEY, "x".repeat(5000)));
        assertThat(block).startsWith("\nNe yapılmalı:\n").contains("…").endsWith(RunbookNote.HINT + "\n");
        assertThat(block.length()).isLessThan(RunbookNote.EMAIL_MAX + 200);
    }

    @Test
    @DisplayName("webhookMessage: mesaj AYNEN + sonda tek satırlık rehber (≤300) + ipucu")
    void webhookMessage_appendsFlattenedTruncatedGuide() {
        String msg = "KRİTİK: https://kw.example.com/ içerik doğrulaması başarısız";
        String out = RunbookNote.webhookMessage(msg, "Adım 1: servisi kontrol et\n\nAdım 2: " + "uzun ".repeat(200));
        assertThat(out).startsWith(msg + "\n\nNe yapılmalı: Adım 1: servisi kontrol et Adım 2: ");
        assertThat(out).endsWith("\n\n" + RunbookNote.HINT);
        String guidePart = out.substring((msg + "\n\nNe yapılmalı: ").length(), out.length() - ("\n\n" + RunbookNote.HINT).length());
        assertThat(guidePart.length()).isLessThanOrEqualTo(RunbookNote.WEBHOOK_MAX);
        assertThat(guidePart).endsWith("…").doesNotContain("\n");
    }
}
