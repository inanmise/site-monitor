package com.sitemonitor.service.techerror;

import com.sitemonitor.service.techerror.TechErrorIncidentMapper.Result;
import com.sitemonitor.service.techerror.TechErrorIncidentMapper.Source;
import com.sitemonitor.service.techerror.TechErrorTemplate.IssueOptions;
import com.sitemonitor.service.techerror.TechErrorTemplate.Section;
import com.sitemonitor.service.techerror.TechErrorTemplate.Template;
import com.sitemonitor.service.techerror.TechErrorValues.Issue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

import static com.sitemonitor.service.techerror.TechErrorIncidentLinks.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Olay kaydı → anomali konusu eşlemesinin doğruluk tablosu (2026-11-20, sözleşme {@code olay-ice-alma-api.md}). Rapor ayı
 * Kasım 2026; olay zamanları UTC metin, raporda Istanbul (UTC+3).
 */
class TechErrorIncidentMapperTest {

    private static final YearMonth NOV = YearMonth.of(2026, 11);
    private static final Template TR = TechErrorTemplate.defaults("tr");
    private static final Template EN = TechErrorTemplate.defaults("en");

    private static Section issues(Template t) {
        return t.section("issues");
    }

    private static Source src(String title, String occurred, String resolved) {
        return new Source(1L, title, occurred, resolved, null, null, null, null, null, null, null, null, "2026-11-10T08:00:00");
    }

    private static Result map(Source s) {
        return TechErrorIncidentMapper.map(s, issues(TR), TR, NOV, null, "i-x");
    }

    private static Result map(Source s, Section sec) {
        return TechErrorIncidentMapper.map(s, sec, TR, NOV, null, "i-x");
    }

    /** Eşlenen konu konunun KATI kuralından geçer (içe alma / senkron bunu yazar). */
    private static void strictValid(Result r, Section sec, Template t) {
        Issue n = TechErrorIncidentWriter.strictTopic(t, sec, NOV, r.issue());
        assertThat(n.topic()).isEqualTo(r.issue().topic());
        assertThat(n.date()).isEqualTo(r.issue().date());
        assertThat(n.start()).isEqualTo(r.issue().start());
        assertThat(n.end()).isEqualTo(r.issue().end());
    }

    // ── Konu ─────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("konu: tek satır (satır sonu / denetim karakteri boşluk, çoklu boşluk tek), 120'de kelime sınırında kırpılır")
    void topic() {
        Result r = map(src("  Ödeme\n\t servisi   yavaş\u0007 ", "2026-11-05T07:00:00", null));
        assertThat(r.issue().topic()).isEqualTo("Ödeme servisi yavaş");
        assertThat(r.notes()).doesNotContain(N_TOPIC_CLIPPED);
        assertThat(r.issue().titleAuto()).isTrue();
        assertThat(r.issue().title()).isEqualTo("Ödeme servisi yavaş: Hata Artışı");

        String longTitle = ("kelime ".repeat(30)).trim();                 // 209 karakter
        Result c = map(src(longTitle, "2026-11-05T07:00:00", null));
        assertThat(c.issue().topic().length()).isLessThanOrEqualTo(120);
        assertThat(c.issue().topic()).endsWith("…").doesNotContain("keli…").startsWith("kelime kelime");
        assertThat(c.notes()).contains(N_TOPIC_CLIPPED);
        strictValid(c, issues(TR), TR);

        Result w = map(src("x".repeat(300), "2026-11-05T07:00:00", null));     // tek uzun kelime: düz kesilir
        assertThat(w.issue().topic()).hasSize(120).endsWith("…");
        assertThat(w.notes()).contains(N_TOPIC_CLIPPED);
    }

    // ── Tarih / saat ─────────────────────────────────────────────────────────────────────────────────

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', nullValues = "-", value = {
            // ad                                       | başlangıç (UTC)       | çözüm (UTC)            | tarih       | baş   | bitiş | ertesi | not
            "UTC 31 Eki 21:30 = Istanbul 1 Kas 00:30     | 2026-10-31T21:30:00 | 2026-10-31T23:00:00 | 2026-11-01 | 00:30 | 02:00 | false | -",
            "aynı gün, bitiş başlangıçtan sonra          | 2026-11-05T07:00:00 | 2026-11-05T09:15:30 | 2026-11-05 | 10:00 | 12:15 | false | -",
            "ertesi gün, bitiş ≤ başlangıç (gece yarısı)  | 2026-11-05T19:30:00 | 2026-11-06T00:45:00 | 2026-11-05 | 22:30 | 03:45 | true  | -",
            "tam 24 saat: ertesi gün aynı saat           | 2026-11-05T07:00:00 | 2026-11-06T07:00:59 | 2026-11-05 | 10:00 | 10:00 | true  | -",
            "24 saatten uzun                             | 2026-11-05T07:00:00 | 2026-11-06T07:01:00 | 2026-11-05 | -     | -     | false | time_unknown",
            "çözülmemiş                                  | 2026-11-05T07:00:00 | -                   | 2026-11-05 | -     | -     | false | time_unknown",
            "çözüm başlangıçtan önce (ters)              | 2026-11-05T07:00:00 | 2026-11-05T06:00:00 | 2026-11-05 | -     | -     | false | time_unknown",
            "dakikaya yuvarlanınca eşit                  | 2026-11-05T07:00:10 | 2026-11-05T07:00:50 | 2026-11-05 | -     | -     | false | time_unknown",
            "16 karakterlik zaman da okunur              | 2026-11-05T07:00    | 2026-11-05T08:00    | 2026-11-05 | 10:00 | 11:00 | false | -",
            "ayın sonrası (Aralık)                       | 2026-12-02T07:00:00 | 2026-12-02T08:00:00 | -          | -     | -     | false | date_outside_month",
            "okunamayan zaman                            | dün öğlen           | -                   | -          | -     | -     | false | time_unknown",
    })
    void dateAndTime(String name, String occurred, String resolved, String date, String start, String end, boolean nextDay,
                     String note) {
        Result r = map(src("Konu", occurred, resolved));
        assertThat(r.issue().date()).as(name).isEqualTo(date);
        assertThat(r.issue().start()).as(name).isEqualTo(start);
        assertThat(r.issue().end()).as(name).isEqualTo(end);
        assertThat(r.issue().endNextDay()).as(name).isEqualTo(nextDay);
        if (note == null) {
            assertThat(r.notes()).as(name).doesNotContain(N_TIME_UNKNOWN, N_DATE_OUTSIDE_MONTH, N_STARTED_BEFORE_MONTH);
        } else {
            assertThat(r.notes()).as(name).contains(note);
        }
        strictValid(r, issues(TR), TR);
    }

    @Test
    @DisplayName("ay içinde başlamadı: tarih YAZILMAZ (sıkıştırılmaz), not + açıklamanın başına 'Başlangıç: gg.AA.yyyy SS:dd'")
    void startedBeforeMonth() {
        Source s = new Source(1L, "Konu", "2026-10-20T10:00:00", "2026-11-02T08:00:00", null, null, null, null,
                "Kök **sebep**", null, null, null, "u");
        Result r = map(s);
        assertThat(r.issue().date()).isNull();
        assertThat(r.issue().start()).isNull();
        assertThat(r.notes()).contains(N_STARTED_BEFORE_MONTH).doesNotContain(N_TIME_UNKNOWN);
        assertThat(r.dateOutsideMonth()).isTrue();
        assertThat(r.issue().description()).startsWith("Başlangıç: 20.10.2026 13:00\n\n**Kök neden**\nKök **sebep**");
        Result en = TechErrorIncidentMapper.map(s, issues(EN), EN, NOV, null, "i-x");
        assertThat(en.issue().description()).startsWith("Start: 20.10.2026 13:00\n\n**Root cause**\n");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', nullValues = "-", value = {
            // ad                                          | zaman sorusu | kök neden | açıklama başı                                    | not
            "soru var, parça var                           | true         | Kök       | Başlangıç: 20.10.2026 13:00\\n\\n**Kök neden**\\nKök | started_before_month",
            "soru YOK (eski düzen), parça var → satır yine | false        | Kök       | Başlangıç: 20.10.2026 13:00\\n\\n**Kök neden**\\nKök | -",
            "soru var, bütün parçalar boş → açıklama boş   | true         | -         | -                                                | started_before_month",
            "soru yok, bütün parçalar boş → açıklama boş   | false        | -         | -                                                | -",
    })
    @DisplayName("başlangıç satırı (inceleme 2026-11-20): olay ay öncesinde başladıysa ve açıklamada bir parça varsa bölüm zaman sorusunu"
            + " sormasa da yazılır; not yalnız sorulan soru için; yalnız bu satırdan oluşan açıklama yazılmaz")
    void startedLineRegardlessOfTimeQuestion(String name, boolean askTime, String rca, String head, String note) {
        IssueOptions d = issues(TR).opts();
        IssueOptions o = new IssueOptions(askTime, d.timeLabel(), d.askOutage(), d.outageLabel(), d.outageIdLabel(), d.askImpact(),
                d.impactLabel(), d.noImpactDisplay(), d.allowImages(), d.ratioOf());
        Section sec = issues(TR).withOptions(o);
        Source s = new Source(1L, "Konu", "2026-10-20T10:00:00", "2026-11-02T08:00:00", null, null, null, null,
                rca, null, null, null, "u");
        Result r = map(s, sec);
        String desc = r.issue().description();
        if (head == null) assertThat(desc).as(name).isEmpty();
        else assertThat(desc).as(name).isEqualTo(head.replace("\\n", "\n"));
        if (note == null) assertThat(r.notes()).as(name).doesNotContain(N_STARTED_BEFORE_MONTH);
        else assertThat(r.notes()).as(name).contains(note);
        assertThat(r.issue().date()).as(name).isNull();
    }

    // ── Hata adedi / kesinti / müşteri etkisi ────────────────────────────────────────────────────────

    @ParameterizedTest(name = "kesinti {0} + ID '{1}' → {2} / {3}")
    @CsvSource(nullValues = "-", value = {
            "true,  INC-1,  true,  INC-1, -",
            "true,  -,      -,     -,     outage_id_missing",
            "true,  '   ',  -,     -,     outage_id_missing",
            "false, INC-9,  false, -,     -",
            "-,     INC-9,  -,     -,     -",
    })
    void outage(Boolean related, String id, Boolean outage, String outageId, String note) {
        Source s = new Source(1L, "K", "2026-11-05T07:00:00", null, 12L, related, id, null, null, null, null, null, "u");
        Result r = map(s);
        assertThat(r.issue().outage()).isEqualTo(outage);
        assertThat(r.issue().outageId()).isEqualTo(outageId);
        assertThat(r.issue().errors()).isEqualTo(12L);
        if (note == null) assertThat(r.notes()).doesNotContain(N_OUTAGE_ID_MISSING);
        else assertThat(r.notes()).contains(note);
        strictValid(r, issues(TR), TR);
    }

    @Test
    @DisplayName("hata adedi: null boş kalır, sayı yazılır")
    void errors() {
        Source none = new Source(1L, "K", "2026-11-05T07:00:00", null, null, null, null, null, null, null, null, null, "u");
        assertThat(map(none).issue().errors()).isNull();
        Source some = new Source(1L, "K", "2026-11-05T07:00:00", null, 1250L, null, null, null, null, null, null, null, "u");
        assertThat(map(some).issue().errors()).isEqualTo(1250L);
    }

    @ParameterizedTest(name = "müşteri {0}, soru açık={1}, gizle={2} → etki {3}, müşteri {4}, gizli {5}")
    @CsvSource(nullValues = "-", value = {
            "-, true,  false, -,     -, false",
            "5, true,  false, true,  5, false",
            "0, true,  false, false, -, false",
            "0, true,  true,  false, -, true",
            "7, true,  true,  true,  7, false",
            "5, false, false, -,     5, false",
            "0, false, false, -,     0, false",
    })
    void impact(Integer customers, boolean asked, boolean hide, Boolean impact, Long expectedCustomers, boolean hidden) {
        IssueOptions d = issues(TR).opts();
        IssueOptions o = new IssueOptions(d.askTime(), d.timeLabel(), d.askOutage(), d.outageLabel(), d.outageIdLabel(), asked,
                d.impactLabel(), hide ? "hide_topic" : "hide_count", d.allowImages(), d.ratioOf());
        Section sec = issues(TR).withOptions(o);
        Source s = new Source(1L, "K", "2026-11-05T07:00:00", null, null, null, null, customers, null, null, null, null, "u");
        Result r = map(s, sec);
        assertThat(r.issue().impact()).isEqualTo(impact);
        assertThat(r.issue().customers() == null ? null : r.issue().customers().longValue()).isEqualTo(expectedCustomers);
        assertThat(r.hidden()).isEqualTo(hidden);
        if (hidden) assertThat(r.notes()).contains(N_WILL_BE_HIDDEN);
        else assertThat(r.notes()).doesNotContain(N_WILL_BE_HIDDEN);
    }

    @Test
    @DisplayName("eski düzen bölümü (soru yok): tarih / saat / kesinti / etki düşer, müşteri sayısı kalır; o soruların notu yazılmaz")
    void legacySection() {
        Section legacy = new Section("old", "issues", "Eski", List.of(), List.of(), "{konu}", "Açıklama", "Müşteri", "Hata", null);
        Source s = new Source(1L, "K", "2026-10-20T10:00:00", null, 3L, true, null, 4, null, null, null, null, "u");
        Result r = TechErrorIncidentMapper.map(s, legacy, TR, NOV, null, "i-x");
        assertThat(r.issue().date()).isNull();
        assertThat(r.issue().start()).isNull();
        assertThat(r.issue().outage()).isNull();
        assertThat(r.issue().impact()).isNull();
        assertThat(r.issue().customers().longValue()).isEqualTo(4L);
        assertThat(r.issue().errors()).isEqualTo(3L);
        assertThat(r.notes()).isEmpty();
        assertThat(r.issue().description()).doesNotStartWith("Başlangıç");
    }

    // ── Açıklama ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("açıklama: seçilen parçalar sabit sırayla, kalın başlıkla (şablon dili); boş parça atlanır; hepsi boşsa boş")
    void descriptionParts() {
        Source s = new Source(1L, "K", "2026-11-05T07:00:00", null, null, null, null, null,
                "Disk doldu", "Ödemeler durdu", "  ", "# Detay\nLoglar", "u");
        assertThat(map(s).issue().description())
                .isEqualTo("**Kök neden**\nDisk doldu\n\n**İş etkisi**\nÖdemeler durdu");
        Result all = TechErrorIncidentMapper.map(s, issues(TR), TR, NOV, PARTS, "i-x");
        assertThat(all.issue().description()).endsWith("**Teknik açıklama**\n**Detay**\n\nLoglar");
        Result en = TechErrorIncidentMapper.map(s, issues(EN), EN, NOV, List.of(P_TECHNICAL, P_RCA), "i-x");
        assertThat(en.issue().description())
                .isEqualTo("**Root cause**\nDisk doldu\n\n**Technical description**\n**Detay**\n\nLoglar");
        Result none = TechErrorIncidentMapper.map(s, issues(TR), TR, NOV, List.of(), "i-x");
        assertThat(none.issue().description()).isEmpty();
        Source blank = new Source(1L, "K", "2026-11-05T07:00:00", null, null, null, null, null, null, " ", "", null, "u");
        assertThat(map(blank).issue().description()).isEmpty();
    }

    @Test
    @DisplayName("açıklama 10 000'e SATIR sınırında kırpılır (description_clipped); 64 KB üstü parça da not düşer")
    void descriptionClipped() {
        List<String> lines = new ArrayList<>();
        for (int k = 0; k < 400; k++) lines.add("Satır " + k + " " + "a".repeat(40));
        Source s = new Source(1L, "K", "2026-11-05T07:00:00", null, null, null, null, null, String.join("\n\n", lines),
                null, null, null, "u");
        Result r = map(s);
        String d = r.issue().description();
        assertThat(d.length()).isLessThanOrEqualTo(TechErrorValues.MAX_LONG_TEXT).isGreaterThan(9_000);
        String last = d.substring(d.lastIndexOf('\n') + 1);
        assertThat(last).matches("Satır \\d+ a{40}");                 // son satır yarım değil
        assertThat(r.notes()).contains(N_DESCRIPTION_CLIPPED);
        strictValid(r, issues(TR), TR);

        // Tek uzun paragraf: satır sınırı çok geride → metin kaybolmasın diye düz kesilir; 64 KB üstü girdi de not düşer
        Source huge = new Source(1L, "K", "2026-11-05T07:00:00", null, null, null, null, null, "x".repeat(70_000),
                null, null, null, "u");
        Result h = map(huge);
        assertThat(h.notes()).contains(N_DESCRIPTION_CLIPPED);
        assertThat(h.issue().description()).hasSize(TechErrorValues.MAX_LONG_TEXT).startsWith("**Kök neden**\nxxx");
        strictValid(h, issues(TR), TR);

        // Vekil çifti ortadan bölünmez
        Source emoji = new Source(1L, "K", "2026-11-05T07:00:00", null, null, null, null, null, "a" + "😀".repeat(6_000),
                null, null, null, "u");
        String e = map(emoji).issue().description();
        assertThat(Character.isHighSurrogate(e.charAt(e.length() - 1))).isFalse();
    }

    @Test
    @DisplayName("not kodları kanonik sırada ve sözleşmedeki listeden")
    void notesAreCanonical() {
        Source s = new Source(1L, "x".repeat(300), "2026-12-02T07:00:00", null, null, true, null, null, null, null, null, null, "u");
        Result r = map(s);
        assertThat(r.notes()).containsExactly(N_TOPIC_CLIPPED, N_DATE_OUTSIDE_MONTH, N_OUTAGE_ID_MISSING);
        assertThat(NOTE_CODES).containsExactly("topic_clipped", "description_clipped", "started_before_month",
                "date_outside_month", "time_unknown", "outage_id_missing", "will_be_hidden", "value_refused", "image_skipped");
        assertThat(GROUPS).containsExactly("topic", "description", "date", "time", "errors", "outage", "impact");
        assertThat(PARTS).containsExactly("rca", "impact", "resolution", "technical");
    }

    @Test
    @DisplayName("önizleme özeti ≤ 600 karakter")
    void previewText() {
        assertThat(TechErrorIncidentMapper.preview("kısa")).isEqualTo("kısa");
        assertThat(TechErrorIncidentMapper.preview("a".repeat(5000))).hasSize(600).endsWith("…");
    }
}
