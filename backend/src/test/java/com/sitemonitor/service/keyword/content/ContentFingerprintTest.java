package com.sitemonitor.service.keyword.content;

import com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException;
import com.sitemonitor.service.keyword.content.ContentMasker.Mask;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * İçerik değişimi çekirdeği (kw sözleşmesi §7.2): maske doğruluk tablosu (doğrusal tarayıcı), parmak izi kararlılığı,
 * {@code spec_hash}, sınırlı fark ve kırpılmış temel.
 */
class ContentFingerprintTest {

    /** girdi, maske (D = rakam, T = tarih, DT = ikisi), beklenen. */
    static final String[][] MASK_TABLE = {
            {"Toplam 1.250 TL, stok 42", "D", "Toplam #.# TL, stok #"},
            {"Güncelleme: 05.11.2026 14:30", "T", "Güncelleme: @ @"},
            {"Güncelleme: 05.11.2026 14:30", "DT", "Güncelleme: @ @"},
            {"Tarih 5/1/2026 ve 2026-11-05", "T", "Tarih @ ve @"},
            {"Saat 9:05:33 itibarıyla", "T", "Saat @ itibarıyla"},
            {"Saat 9:05:33 itibarıyla", "D", "Saat #:#:# itibarıyla"},
            {"1.234.567 kişi", "T", "1.234.567 kişi"},                 // tarih değil (ikinci parça 3 hane)
            {"3.14 oranı", "T", "3.14 oranı"},
            {"Sürüm 12:345", "T", "Sürüm 12:345"},                    // saat değil
            {"20261105", "T", "20261105"},                            // ayırıcısız uzun koşu
            {"Kod 2026-11-05X", "T", "Kod @X"},                       // harf sınırı geçerli (rakam değil)
            {"Kod 2026-11-051", "T", "Kod 2026-11-051"},              // rakamla bitişik → tarih değil
            {"5 Kasım Çarşamba, MAYIS ayı", "T", "5 @ @, @ ayı"},
            {"Updated Monday, Nov 5", "T", "Updated @, @ 5"},
            {"Updated Monday, Nov 5", "DT", "Updated @, @ #"},
            {"pazartesi PAZARTESİ", "T", "@ @"},                       // katlamalı tam sözcük
            {"Kasımpaşa ekimdeki", "T", "Kasımpaşa ekimdeki"},        // sözcük parçası maskelenmez
            {"abc", "", "abc"},
    };

    private static Set<Mask> masks(String code) {
        EnumSet<Mask> s = EnumSet.noneOf(Mask.class);
        if (code.contains("D")) s.add(Mask.DIGITS);
        if (code.contains("T")) s.add(Mask.DATES);
        return s;
    }

    @Test
    @DisplayName("Maske doğruluk tablosu (tarih önce, rakam sonra; sınırlar; katlamalı ay / gün adları)")
    void maskTable() {
        for (String[] row : MASK_TABLE) {
            assertThat(ContentMasker.apply(row[0], masks(row[1]))).as(row[0] + " / " + row[1]).isEqualTo(row[2]);
        }
        assertThat(ContentMasker.apply(null, masks("DT"))).isNull();
    }

    @Test
    @DisplayName("Maske doğrusal: 2 M karakterlik rakam / ayırıcı dizisi ≤ 2 sn")
    void maskLinear() {
        String hostile = "1.1.1.1.".repeat(250_000) + "12:12:12:".repeat(10_000);
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            ContentMasker.apply(hostile, masks("DT"));
        });
    }

    @Test
    @DisplayName("Maske ayarı: liste / CSV → kanonik CSV (enum sırası); bilinmeyen → 400 changeMask; boş → null")
    void maskSetting() {
        assertThat(ContentMasker.parseRequest(List.of("dates", "DIGITS"))).isEqualTo("DIGITS,DATES");
        assertThat(ContentMasker.parseRequest("DATES, DIGITS ,")).isEqualTo("DIGITS,DATES");
        assertThat(ContentMasker.parseRequest(List.of())).isNull();
        assertThat(ContentMasker.parseRequest("")).isNull();
        assertThat(ContentMasker.parseRequest(null)).isNull();
        assertThatThrownBy(() -> ContentMasker.parseRequest(List.of("EMAILS")))
                .isInstanceOf(FieldValidationException.class)
                .satisfies(e -> assertThat(((FieldValidationException) e).getFields()).containsKey("changeMask"));
        assertThat(ContentMasker.toList("DATES,x,DIGITS")).containsExactly("DIGITS", "DATES");
        assertThat(KeywordChangeSettings.mask("digits")).isEqualTo("DIGITS");
    }

    @Test
    @DisplayName("Seçici ayarı: boş → null; geçersiz / :matches → 400 changeSelector")
    void selectorSetting() {
        assertThat(KeywordChangeSettings.selector(" ")).isNull();
        assertThat(KeywordChangeSettings.selector(" #icerik ")).isEqualTo("#icerik");
        assertThatThrownBy(() -> KeywordChangeSettings.selector("p:matches(x)"))
                .isInstanceOf(FieldValidationException.class)
                .satisfies(e -> assertThat(((FieldValidationException) e).getFields()).containsKey("changeSelector"));
    }

    @Test
    @DisplayName("Parmak izi: aynı metin aynı iz; boş / boşluk satırlar düşer; maske dinamik değeri susturur; sır maskelenir")
    void fingerprintStable() {
        var a = KeywordChangeDetector.snapshot(List.of("Fiyat", "  ", "Son: 05.11.2026 10:00"), masks("T"), List.of());
        var b = KeywordChangeDetector.snapshot(List.of(" Fiyat ", "Son: 06.11.2026 11:30"), masks("T"), List.of());
        assertThat(a.fingerprint()).isEqualTo(b.fingerprint()).hasSize(64);
        assertThat(a.lineCount()).isEqualTo(2);
        var c = KeywordChangeDetector.snapshot(List.of("Fiyat", "Son: 06.11.2026 11:30"), masks(""), List.of());
        assertThat(c.fingerprint()).isNotEqualTo(a.fingerprint());
        var s = KeywordChangeDetector.snapshot(List.of("token gizli-deger-123 burada", "{\"password\":\"abc\"}"), masks(""),
                List.of("gizli-deger-123"));
        assertThat(s.text()).doesNotContain("gizli-deger-123").doesNotContain("abc");
    }

    @Test
    @DisplayName("spec_hash: maske / seçici / sürüm özeti, 16 onaltılık; maske sırası ve boşluk farkı aynı iz")
    void specHash() {
        String h = KeywordChangeDetector.specHash("DIGITS,DATES", "#a");
        assertThat(h).hasSize(16).matches("[0-9a-f]{16}");
        assertThat(KeywordChangeDetector.specHash("DATES,DIGITS", " #a ")).isEqualTo(h);
        assertThat(KeywordChangeDetector.specHash("DATES", "#a")).isNotEqualTo(h);
        assertThat(KeywordChangeDetector.specHash(null, null)).isNotEqualTo(KeywordChangeDetector.specHash(null, "#a"));
        assertThat(KeywordChangeSettings.settingsChanged(true, "DATES", null, true, "DATES", "")).isFalse();
        assertThat(KeywordChangeSettings.settingsChanged(true, "DATES", null, true, "DIGITS", null)).isTrue();
        assertThat(KeywordChangeSettings.settingsChanged(true, null, null, false, null, null)).isTrue();
        assertThat(KeywordChangeSettings.settingsChanged(false, null, null, true, "DIGITS", null)).isFalse();
        assertThat(KeywordChangeSettings.settingsChanged(null, null, null, null, null, null)).isFalse();
    }

    @Test
    @DisplayName("Karşılaştırma: temel yok / başka spec → baseline_missing; aynı iz → false; farklı → true + özet")
    void compare() {
        var base = KeywordChangeDetector.snapshot(List.of("a", "b", "c", "d"), masks(""), List.of());
        String spec = KeywordChangeDetector.specHash(null, null);
        var view = new KeywordChangeDetector.BaselineView(base.fingerprint(), spec, base.text(), base.textTruncated());
        assertThat(KeywordChangeDetector.compare(null, base, spec)).isEqualTo(KeywordChangeDetector.Comparison.MISSING);
        assertThat(KeywordChangeDetector.compare(view, base, "baska").baselineMissing()).isTrue();
        var same = KeywordChangeDetector.compare(view, base, spec);
        assertThat(same.changed()).isFalse();
        assertThat(same.summary()).isNull();
        var cur = KeywordChangeDetector.snapshot(List.of("a", "B!", "c", "d", "e"), masks(""), List.of());
        var diff = KeywordChangeDetector.compare(view, cur, spec);
        assertThat(diff.changed()).isTrue();
        assertThat(diff.baselineMissing()).isFalse();
        var sum = diff.summary();
        // ortak önek "a"; son satırlar farklı ("d" / "e") → ortak sonek yok: aradaki her şey kaldırılmış / eklenmiş sayılır
        assertThat(sum.removed()).containsExactly("b", "c", "d");
        assertThat(sum.added()).containsExactly("B!", "c", "d", "e");
        assertThat(sum.changedLines()).isEqualTo(4);
        assertThat(sum.linesBefore()).isEqualTo(4);
        assertThat(sum.linesAfter()).isEqualTo(5);
    }

    @Test
    @DisplayName("Fark: ortak önek / sonek; kaldırılan / eklenen ≤ 20 satır, satır ≤ 200; toplamlar tam; JSON ≤ 4000 + gidiş-dönüş")
    void boundedDiff() {
        List<String> before = new ArrayList<>();
        List<String> after = new ArrayList<>();
        for (int i = 0; i < 100; i++) before.add("satır " + i);
        after.add("satır 0");
        for (int i = 0; i < 60; i++) after.add("yeni " + "x".repeat(300) + i);
        after.add("satır 99");
        var b = KeywordChangeDetector.snapshot(before, masks(""), List.of());
        var a = KeywordChangeDetector.snapshot(after, masks(""), List.of());
        var sum = KeywordChangeDetector.diff(b.text(), false, a);
        assertThat(sum.removedTotal()).isEqualTo(98);
        assertThat(sum.addedTotal()).isEqualTo(60);
        assertThat(sum.removed()).hasSize(KeywordChangeDetector.SUMMARY_LINES);
        assertThat(sum.added()).hasSize(KeywordChangeDetector.SUMMARY_LINES);
        assertThat(sum.added().get(0).length()).isLessThanOrEqualTo(KeywordChangeDetector.SUMMARY_LINE_MAX);
        assertThat(sum.changedLines()).isEqualTo(98);
        String json = sum.toJson();
        assertThat(json.length()).isLessThanOrEqualTo(KeywordChangeDetector.SUMMARY_JSON_MAX);
        Map<String, Object> back = KeywordChangeDetector.parseSummary(json);
        assertThat(back).containsEntry("removed_total", 98).containsEntry("added_total", 60).containsEntry("truncated", false)
                .containsKeys("lines_before", "lines_after", "removed", "added");
        assertThat(KeywordChangeDetector.changedLines(json)).isEqualTo(98);
        assertThat(KeywordChangeDetector.changedLines(back)).isEqualTo(98);
        assertThat(KeywordChangeDetector.changedLines(null)).isNull();
        assertThat(KeywordChangeDetector.parseSummary("bozuk")).isNull();
    }

    @Test
    @DisplayName("Kırpılmış temel (> 64 000): iz tam metinden; fark kırpılmış uzunlukta, truncated=true")
    void truncatedBaseline() {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 5_000; i++) lines.add("satır numarası " + i + " " + "y".repeat(10));
        var base = KeywordChangeDetector.snapshot(lines, masks(""), List.of());
        assertThat(base.textTruncated()).isTrue();
        assertThat(base.text().length()).isEqualTo(KeywordChangeDetector.BASELINE_TEXT_MAX);
        List<String> changedTail = new ArrayList<>(lines);
        changedTail.set(4_999, "son satır değişti");   // kırpılan bölümde
        var cur = KeywordChangeDetector.snapshot(changedTail, masks(""), List.of());
        assertThat(cur.fingerprint()).isNotEqualTo(base.fingerprint());
        String spec = KeywordChangeDetector.specHash(null, null);
        var cmp = KeywordChangeDetector.compare(new KeywordChangeDetector.BaselineView(base.fingerprint(), spec, base.text(), true),
                cur, spec);
        assertThat(cmp.changed()).isTrue();
        assertThat(cmp.summary().truncated()).isTrue();
        assertThat(cmp.summary().removed()).as("değişiklik kırpılan bölümde — özet boş").isEmpty();
        assertThat(cmp.summary().added()).isEmpty();
    }
}
