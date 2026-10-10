package com.sitemonitor.service.report.executive;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * Aylık yönetici özetinin tamamı — ekran, e-posta ve PDF aynı nesneden beslenir.
 *
 * @param month         {@code YYYY-MM} (Europe/Istanbul takvim ayı)
 * @param monthLabel    Türkçe ay adı ("Eylül 2026") — posta/PDF; arayüz kendi dilinde biçimler
 * @param from          ayın başı (UTC ISO, dahil)
 * @param to            ayın sonu (UTC ISO, hariç)
 * @param complete      ay bitti mi (bu ay "devam ediyor" ise false)
 * @param generatedAt   üretim anı (UTC ISO)
 * @param source        {@code live} (canlı hesap) | {@code snapshot} (gönderilen raporun kaydı)
 * @param status        bölümlerin en kötü durumu ({@link SectionResult#STATUS_ORDER})
 * @param headline      üst şeridin tek satırlık hükümleri (her bölümün ilk hükmü, bölüm sırasıyla)
 * @param headlineKpis  üst şeridin gösterge kutuları (her bölümün {@code headline_kpi}'si)
 * @param sections      bölümler, {@link ExecutiveSummarySection#order()} sırasıyla
 * @param settings      hesapta kullanılan hedefler (erişilebilirlik hedefi, yenileme hedef süresi…)
 */
public record ExecutiveSummary(
        String month,
        @JsonProperty("month_label") String monthLabel,
        String from,
        String to,
        boolean complete,
        @JsonProperty("generated_at") String generatedAt,
        String source,
        String status,
        List<SectionResult.Verdict> headline,
        @JsonProperty("headline_kpis") List<HeadlineKpi> headlineKpis,
        List<SectionResult> sections,
        Map<String, Object> settings) {

    public static final String SOURCE_LIVE = "live";
    public static final String SOURCE_SNAPSHOT = "snapshot";

    public ExecutiveSummary {
        headline = headline == null ? List.of() : List.copyOf(headline);
        headlineKpis = headlineKpis == null ? List.of() : List.copyOf(headlineKpis);
        sections = sections == null ? List.of() : List.copyOf(sections);
        settings = settings == null ? Map.of() : settings;
    }

    /** Üst şerit göstergesi: hangi bölümden geldiği + göstergenin kendisi. */
    public record HeadlineKpi(String section, SectionResult.Kpi kpi) { }

    public ExecutiveSummary withSource(String s) {
        return new ExecutiveSummary(month, monthLabel, from, to, complete, generatedAt, s, status, headline,
                headlineKpis, sections, settings);
    }

    /** Anahtarıyla bölüm (yoksa null). */
    public SectionResult section(String key) {
        for (SectionResult s : sections) if (s.key().equals(key)) return s;
        return null;
    }
}
