package com.sitemonitor.service.keyword;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Anahtar kelime alarm postası metinleri (kw sözleşmesi §8.1, E1 + B1 + B12). Başlıklar arayüzdekilerle BİREBİR aynı olmalı —
 * kapı {@code frontend/src/i18n/tr.js} dosyasını okur ({@code kwhint.<KOD>.title}, {@code kwfail.<KOD>.short}).
 */
class KeywordMailTextTest {

    private static final Path TR = Path.of("../frontend/src/i18n/tr.js");

    /** {@code 'anahtar': 'değer'} ya da {@code 'anahtar': "değer"} satırının değeri (tek satır sözlük biçimi). */
    private static String trValue(String src, String key) {
        Matcher m = Pattern.compile("'" + Pattern.quote(key) + "':\\s*(['\"])(.*?)\\1,\\s*$", Pattern.MULTILINE).matcher(src);
        return m.find() ? m.group(2).replace("\\'", "'") : null;
    }

    @Test
    @DisplayName("ipucu başlıkları tr.js kwhint.<KOD>.title ile AYNI; her ipucunun tek satır nedeni var")
    void hintTitlesMatchUi() throws IOException {
        String src = Files.readString(TR, StandardCharsets.UTF_8);
        List<String> diff = new ArrayList<>();
        for (String code : KeywordBodyAnalyzer.HINT_CODES) {
            String ui = trValue(src, "kwhint." + code + ".title");
            if (!KeywordMailText.HINT_TITLES.get(code).equals(ui)) diff.add(code + ": posta=" + KeywordMailText.HINT_TITLES.get(code) + " arayüz=" + ui);
            assertThat(KeywordMailText.HINT_CAUSES.get(code)).as(code).isNotBlank();
        }
        assertThat(diff).isEmpty();
        assertThat(KeywordMailText.HINT_TITLES).hasSameSizeAs(KeywordBodyAnalyzer.HINT_CODES);
    }

    @Test
    @DisplayName("kısa nedenler tr.js kwfail.<KOD>.short ile AYNI ve her neden kodunu kapsar")
    void shortReasonsMatchUi() throws IOException {
        String src = Files.readString(TR, StandardCharsets.UTF_8);
        List<String> diff = new ArrayList<>();
        for (String code : KeywordFailureClassifier.CODES) {
            String ui = trValue(src, "kwfail." + code + ".short");
            String mail = KeywordMailText.SHORT_REASONS.get(code);
            if (mail == null || !mail.equals(ui)) diff.add(code + ": posta=" + mail + " arayüz=" + ui);
        }
        assertThat(diff).isEmpty();
        assertThat(KeywordMailText.shortReason(KeywordFailureClassifier.HTTP_STATUS, 503)).isEqualTo("HTTP 503 döndü");
        assertThat(KeywordMailText.shortReason(null, null)).isNull();
        assertThat(KeywordMailText.shortReason("YOK", null)).isNull();
    }

    @Test
    @DisplayName("B1 başlık gözlemden: istek tamamlanmadı → 'Sayfa Açılamadı' (içermemeli kuralında da); nedene göre diğerleri")
    void headline() {
        // içermemeli kuralı + DNS hatası — eskiden "İstenmeyen İfade Bulundu" yazıyordu
        assertThat(KeywordMailText.headline("DNS", "UnknownHostException", "LTE", 0, 0)).isEqualTo("Sayfa Açılamadı");
        assertThat(KeywordMailText.headline(null, "connect timed out", "LTE", 0, 0)).isEqualTo("Sayfa Açılamadı");
        assertThat(KeywordMailText.headline("HTTP_STATUS", null, "GTE", 1, 2)).isEqualTo("Beklenmeyen HTTP Durumu");
        assertThat(KeywordMailText.headline("FINAL_URL_MISMATCH", null, "GTE", 1, 2)).isEqualTo("Beklenmeyen Adrese Yönlendirildi");
        assertThat(KeywordMailText.headline("BODY_TOO_SMALL", null, "GTE", 1, 0)).isEqualTo("Sayfa Boyutu Beklenen Aralıkta Değil");
        assertThat(KeywordMailText.headline("KEYWORD_FOUND_FORBIDDEN", null, "LTE", 0, 3)).isEqualTo("İstenmeyen İfade Bulundu");
        assertThat(KeywordMailText.headline("RULES_NOT_MET", null, "GTE", 1, 0)).isEqualTo("İçerik Kuralları Sağlanmadı");
        assertThat(KeywordMailText.headline("SCOPE_NOT_FOUND", null, "GTE", 1, 0)).isEqualTo("İçerik Bölgesi Bulunamadı");
        assertThat(KeywordMailText.headline("KEYWORD_NOT_FOUND", null, "GTE", 1, 0)).isEqualTo("Koşul Sağlanmadı");
        // nedeni olmayan eski bağlam: "hiç geçmemeli" tespiti tek kuraldan (B12 — LT 1 dâhil) ve GÖZLEME bağlı
        assertThat(KeywordMailText.headline(null, null, "LT", 1, 2)).isEqualTo("İstenmeyen İfade Bulundu");
        assertThat(KeywordMailText.headline(null, null, "LT", 1, 0)).isEqualTo("Koşul Sağlanmadı");
        assertThat(KeywordMailText.headline(null, "null", "GTE", 1, 0)).isEqualTo("Koşul Sağlanmadı");
    }

    @Test
    @DisplayName("ipucu satırları 'Başlık — neden', bilinmeyen kod atlanır, en çok 5; alıntı ≤ 300; JSON dizi metni de okunur")
    void hintLinesAndExcerpt() {
        List<String> lines = KeywordMailText.hintLines(List.of("WAF_OR_BLOCK_PAGE", "YOK", "LOGIN_PAGE"));
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0)).startsWith("WAF / engelleme sayfası geldi — ");
        assertThat(KeywordMailText.hintLines(KeywordBodyAnalyzer.HINT_CODES)).hasSize(KeywordMailText.MAX_HINT_LINES);
        assertThat(KeywordMailText.hintCodes("[\"CHARSET\",\"JS_RENDERED\"]")).containsExactly("CHARSET", "JS_RENDERED");
        assertThat(KeywordMailText.hintCodes(null)).isEmpty();
        assertThat(KeywordMailText.mailExcerpt("a".repeat(400))).hasSize(KeywordMailText.MAIL_EXCERPT_MAX).endsWith("…");
        assertThat(KeywordMailText.mailExcerpt("  ")).isNull();
        assertThat(KeywordMailText.mailExcerpt(null)).isNull();
    }

    @Test
    @DisplayName("E3 konu şablonu: Türkçe / İngilizce yer tutucular, açılmayan sayfada adet boş, bilinmeyen yer tutucu olduğu gibi")
    void subjectTemplate() {
        Map<String, Object> ctx = new java.util.HashMap<>(Map.of("monitor_name", "Ana sayfa", "url", "https://x.example/",
                "keyword", "Sepete ekle", "occurrences", 0, "failure_reason", "KEYWORD_NOT_FOUND", "http_status", 200));
        Map<String, String> v = KeywordMailSubject.values(ctx, "KRİTİK", "05.11.2026 10:00");
        assertThat(KeywordMailSubject.render("[{seviye}] {ad} · {kelime} {adet} kez · {neden} · {durum} · {zaman} · {x}", v))
                .isEqualTo("[KRİTİK] Ana sayfa · Sepete ekle 0 kez · Kelime bulunamadı · 200 · 05.11.2026 10:00 · {x}");
        assertThat(KeywordMailSubject.render("{name} {url} {keyword} {count} {reason} {level} {status} {time}", v))
                .isEqualTo("Ana sayfa https://x.example/ Sepete ekle 0 Kelime bulunamadı KRİTİK 200 05.11.2026 10:00");
        ctx.put("failure_reason", "DNS");
        ctx.remove("http_status");
        assertThat(KeywordMailSubject.render("{ad}: {adet} · {neden}", KeywordMailSubject.values(ctx, "UYARI", null)))
                .isEqualTo("Ana sayfa: · DNS çözümlenemedi");
        assertThat(KeywordMailSubject.render("   ", v)).isNull();
        assertThat(KeywordMailSubject.render("{adet}", Map.of())).isNull();   // boş sonuç → standart konu
        assertThat(KeywordMailSubject.render("x".repeat(400), v)).hasSize(KeywordMailSubject.MAX_LENGTH).endsWith("…");
        assertThat(KeywordMailSubject.render("a\nb\tc", v)).isEqualTo("a b c");
    }
}
