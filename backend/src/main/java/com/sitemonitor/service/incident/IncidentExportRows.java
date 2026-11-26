package com.sitemonitor.service.incident;

import com.sitemonitor.model.IncidentRecord;
import com.sitemonitor.service.techerror.TechErrorIncidentText;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Olay ve Hata Geçmişi listesinin dışa aktarım satırı (2026-11-26, "Bul ve Raporla" — Paket 3). Sütunlar sabit sırada
 * ({@link #COLUMNS}); CSV başlığı ve JSON anahtarları aynı. Zamanlar İstanbul yerel ({@code yyyy-MM-dd HH:mm:ss}); kişiler
 * yalnız GÖRÜNEN AD (kullanıcı adı / kimlik değil — ad bulunamazsa kayıttaki ad); markdown metinleri düz metin (görseller
 * atılır, bağlantı "metin (adres)"), alan başına {@value #MAX_TEXT_CHARS} karakter.
 */
public final class IncidentExportRows {

    private IncidentExportRows() {}

    /** Sütunlar — CSV başlığı ve JSON satır anahtarları (sıra sabit; istemci Excel'i aynı sırayla kurar). */
    public static final List<String> COLUMNS = List.of(
            "id", "title", "severity", "status", "category", "occurred_at", "detected_at", "resolved_at", "duration_minutes",
            "team", "channel", "service", "problem_types", "error_code", "function_code", "channel_code",
            "affected_services", "affected_app", "affected_systems", "affected_customers", "affected_transactions",
            "tech_error_count", "outage_related", "outage_id", "sla_breached", "error_budget_burn_pct", "tags", "runbook_url",
            "alert_event_id", "postmortem", "created_by", "created_at", "updated_by", "updated_at",
            "rca_summary", "description", "resolution_steps", "business_impact");

    /** Düz metne çevrilmiş markdown alanının üst sınırı (karakter); fazlası "…" ile kesilir. */
    public static final int MAX_TEXT_CHARS = 4_000;

    private static final ZoneId IST = ZoneId.of("Europe/Istanbul");
    private static final DateTimeFormatter LOCAL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** Satır sonucu + markdown metinlerinden kaçının kesildiği. */
    public record Row(Map<String, Object> values, int textClipped) { }

    /**
     * Kayıt → dışa aktarım satırı. {@code names}: kullanıcı adı → görünen ad (bilinmiyorsa null); {@code postmortem}: durum
     * kodu (yoksa null); {@code withText}: false → dört markdown alanı boş (toplam metin bütçesi doldu).
     */
    public static Row row(IncidentRecord e, Function<String, String> names, String postmortem, boolean withText) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("title", e.getTitle());
        m.put("severity", e.getSeverity());
        m.put("status", e.getStatus());
        m.put("category", e.getCategory());
        m.put("occurred_at", local(e.getOccurredAt()));
        m.put("detected_at", local(e.getDetectedAt()));
        m.put("resolved_at", local(e.getResolvedAt()));
        m.put("duration_minutes", e.getDurationMinutes());
        m.put("team", e.getTeamName());
        m.put("channel", e.getChannel());
        m.put("service", e.getService());
        m.put("problem_types", e.getProblemTypes());
        m.put("error_code", e.getErrorCode());
        m.put("function_code", e.getFunctionCode());
        m.put("channel_code", e.getChannelCode());
        m.put("affected_services", e.getAffectedServices());
        m.put("affected_app", e.getAffectedApp());
        m.put("affected_systems", e.getAffectedSystems());
        m.put("affected_customers", e.getAffectedCustomers());
        m.put("affected_transactions", e.getAffectedTransactions());
        m.put("tech_error_count", e.getTechErrorCount());
        m.put("outage_related", e.getOutageRelated());
        m.put("outage_id", e.getOutageId());
        m.put("sla_breached", e.getSlaBreached());
        m.put("error_budget_burn_pct", e.getErrorBudgetBurnPct());
        m.put("tags", e.getTags());
        m.put("runbook_url", e.getRunbookUrl());
        m.put("alert_event_id", e.getAlertEventId());
        m.put("postmortem", postmortem);
        m.put("created_by", person(e.getCreatedBy(), names));
        m.put("created_at", local(e.getCreatedAt()));
        m.put("updated_by", person(e.getUpdatedBy(), names));
        m.put("updated_at", local(e.getUpdatedAt()));
        int clipped = 0;
        String[] md = {e.getRcaSummary(), e.getDescription(), e.getResolutionSteps(), e.getBusinessImpact()};
        String[] keys = {"rca_summary", "description", "resolution_steps", "business_impact"};
        for (int k = 0; k < md.length; k++) {
            String text = withText ? plainText(md[k]) : null;
            if (text != null && text.length() > MAX_TEXT_CHARS) {
                int cut = MAX_TEXT_CHARS;
                if (Character.isHighSurrogate(text.charAt(cut - 1))) cut--;
                text = text.substring(0, cut) + "…";
                clipped++;
            }
            m.put(keys[k], text);
        }
        return new Row(m, clipped);
    }

    /**
     * Markdown → düz metin: {@link TechErrorIncidentText} (görsel atılır, bağlantı "metin (adres)", başlık / liste korunur;
     * iç içelik ve uzunluk sınırlı, düzenli ifade yok) + kalın işaretleri ({@code **}) kaldırılır. Boş → null.
     */
    public static String plainText(String markdown) {
        if (markdown == null || markdown.isBlank()) return null;
        String t = TechErrorIncidentText.convert(markdown).text().replace("**", "");
        return t.isBlank() ? null : t;
    }

    /** Kişi: görünen ad, yoksa kayıttaki ad (kullanıcı adı); boş → null. */
    static String person(String username, Function<String, String> names) {
        if (username == null || username.isBlank()) return null;
        String n = names == null ? null : names.apply(username);
        return n != null && !n.isBlank() ? n : username;
    }

    /** UTC {@code yyyy-MM-ddTHH:mm:ss} → İstanbul yerel {@code yyyy-MM-dd HH:mm:ss}; okunamayan değer olduğu gibi. */
    public static String local(String utc) {
        if (utc == null || utc.isBlank()) return null;
        String s = utc.trim();
        if (s.length() >= 19) s = s.substring(0, 19);
        else if (s.length() == 16) s = s + ":00";          // saniyesiz eski kayıt
        else return utc;
        try {
            return LOCAL.format(LocalDateTime.parse(s).atOffset(ZoneOffset.UTC).atZoneSameInstant(IST));
        } catch (java.time.format.DateTimeParseException e) {
            return utc;
        }
    }

    /** CSV satırı — {@link #COLUMNS} sırasıyla, formül korumalı ({@link com.sitemonitor.util.Csv#row}). */
    public static String csvRow(Map<String, Object> values) {
        Object[] cells = new Object[COLUMNS.size()];
        for (int k = 0; k < cells.length; k++) cells[k] = values.get(COLUMNS.get(k));
        return com.sitemonitor.util.Csv.row(cells);
    }
}
