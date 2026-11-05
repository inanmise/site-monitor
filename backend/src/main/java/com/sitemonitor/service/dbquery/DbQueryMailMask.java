package com.sitemonitor.service.dbquery;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Sorgu alarmı postasında maskeli kolon ölçüsünün gizlenmesi (dbq2 inceleme O2, 2026-11-05).
 *
 * <p>İzlemenin GÜNCEL {@code masked_columns} listesi satır tablosunda {@link DbQueryAlertRowsService#MASK} ile gizlenir; bir
 * kuralın kolonu o listedeyse aynı kolondan türeyen ölçü de postada (gönderilen kopya ve {@code notification_logs} kopyası)
 * açık yazılmamalı: "Ölçülen Değer", "Son Değerler" tablosu, kural tablosunun "Gelen" sütunu ve ihlal özeti. Değerlendirme
 * için değer veritabanında kalır (motor değişmez); yalnız postaya giden görünüm maskelenir. Karar satır verisi kuralıyla
 * aynı: alarm anının değil, gönderim anındaki izleme ayarı.</p>
 *
 * <p>Saf sınıf: kural JSON'u DOĞRULANMADAN okunur (yalnız {@code id}, {@code kind}, {@code column}) — doğrulayıcının
 * tanımadığı bir kural türü de maskelenir. Kimliksiz maskeli kural varsa hangi sonucun ondan geldiği bilinemez → ölçü
 * üretebilen her sonuç maskelenir (temkinli).</p>
 */
public final class DbQueryMailMask {

    public static final String MASK = DbQueryAlertRowsService.MASK;
    /** Sonuç eşleştirilemiyor (kimliksiz maskeli kural): ölçü üreten her kural maskeli sayılır. */
    public static final String ALL = "*";

    /** Ölçüsü kolondan türemeyen türler: satır sayısı (kolon yok) ve metin eşleşmesi (gelen = eşleşen satır SAYISI). */
    private static final Set<String> NOT_COLUMN_MEASURE = Set.of(DbQueryRules.KIND_ROW_COUNT, DbQueryRules.KIND_TEXT_MATCH);
    /** Beklenen metni yalnız yapılandırmadan gelen türler; diğer türlerde (ör. akıllı kurallar) beklenen de veriden türer. */
    private static final Set<String> CONFIG_EXPECTED = Set.of(DbQueryRules.KIND_COLUMN_VALUE, DbQueryRules.KIND_CHANGE,
            DbQueryRules.KIND_FRESHNESS);
    /** Maskeli kural sonucunda tutulan anahtarlar (geri kalanı — measure, compared, istatistikler — atılır). */
    private static final Set<String> SAFE_RESULT_KEYS = Set.of("id", "label", "kind", "level", "passed", "evaluated", "note",
            "fingerprint");

    private static final ObjectMapper JSON = new ObjectMapper();

    private DbQueryMailMask() {}

    /**
     * Maskeli kolonu ölçen kuralların kimlikleri; maske listesi boşsa ya da hiçbir ölçü kuralı maskeli kolonu okumuyorsa
     * boş küme. Kimliksiz maskeli kural varsa küme {@link #ALL} içerir.
     */
    public static Set<String> maskedRuleIds(String rulesJson, String maskedColumnsJson) {
        Set<String> masked = new LinkedHashSet<>();
        for (Object o : readList(maskedColumnsJson))
            if (o instanceof String s && !s.isBlank()) masked.add(s.trim().toLowerCase(Locale.ROOT));
        if (masked.isEmpty()) return Set.of();
        Set<String> ids = new LinkedHashSet<>();
        for (Object o : readList(rulesJson)) {
            if (!(o instanceof Map<?, ?> r)) continue;
            String kind = r.get("kind") instanceof String k ? k.trim().toUpperCase(Locale.ROOT) : null;
            if (kind == null || NOT_COLUMN_MEASURE.contains(kind)) continue;
            String col = r.get("column") instanceof String c ? c.trim().toLowerCase(Locale.ROOT) : null;
            if (col == null || !masked.contains(col)) continue;
            String id = r.get("id") instanceof String i && !i.isBlank() ? i.trim() : null;
            ids.add(id == null ? ALL : id);
        }
        return Set.copyOf(ids);
    }

    /** Bu kural sonucu maskeli mi (kimliği maskeli kümede ya da küme {@link #ALL} ve sonuç kolon ölçüsü olabilir). */
    static boolean resultMasked(Map<String, Object> r, Set<String> ids) {
        if (r == null || ids == null || ids.isEmpty()) return false;
        String kind = r.get("kind") == null ? null : String.valueOf(r.get("kind")).toUpperCase(Locale.ROOT);
        if (kind != null && NOT_COLUMN_MEASURE.contains(kind)) return false;
        Object id = r.get("id");
        return (id != null && ids.contains(String.valueOf(id))) || ids.contains(ALL);
    }

    /**
     * "Ölçülen Değer" (grafik değeri) maskeli mi: motor bu değeri sırayla ilk {@link DbQueryEvaluator#measuresMetric} sonucundan
     * alır (değerlendirilmiş YA DA akıllı kural — taban kurulurken / örnek yetersizken de değeri ölçer; karşılaştırma değeri
     * olan; metin eşleşmesi olmayan) — maske AYNI kuralla kaynağı bulur (2026-11-05 hata avı O1). Böyle kural yoksa değer
     * satır sayısıdır (maskesiz). Kural sonuçları yoksa değerin kaynağı bilinemez → maskeli kural varsa maskeli (temkinli).
     */
    public static boolean metricMasked(List<Map<String, Object>> results, Set<String> ids) {
        if (ids == null || ids.isEmpty()) return false;
        if (results == null || results.isEmpty()) return true;
        for (Map<String, Object> r : results) {
            if (r == null) continue;
            String kind = r.get("kind") == null ? null : String.valueOf(r.get("kind"));
            if (!DbQueryEvaluator.measuresMetric(kind, !Boolean.FALSE.equals(r.get("evaluated")), number(r.get("compared")))) continue;
            return resultMasked(r, ids);
        }
        return false;
    }

    /** Kural sonuçlarının postaya giden kopyası: maskeli kuralın "gelen" değeri {@link #MASK}, ölçü alanları atılır. */
    public static List<Map<String, Object>> maskResults(List<Map<String, Object>> results, Set<String> ids) {
        if (results == null) return List.of();
        List<Map<String, Object>> out = new ArrayList<>(results.size());
        for (Map<String, Object> r : results) {
            if (!resultMasked(r, ids)) { out.add(r); continue; }
            Map<String, Object> m = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : r.entrySet()) if (SAFE_RESULT_KEYS.contains(e.getKey())) m.put(e.getKey(), e.getValue());
            String kind = r.get("kind") == null ? null : String.valueOf(r.get("kind")).toUpperCase(Locale.ROOT);
            m.put("actual", MASK);
            m.put("expected", kind != null && CONFIG_EXPECTED.contains(kind) ? r.get("expected") : MASK);
            out.add(m);
        }
        return out;
    }

    /**
     * Postaya giden görünümü maskeler: {@code out}'a (bağlamın üstüne yazılan ek anahtarlar) maskeli değerleri koyar.
     * {@code view} = okunacak birleşik görünüm (bağlam + ek). Maskeli kural yoksa hiçbir şey yazmaz (çıktı bugünküyle aynı).
     *
     * @param historyKey "son değerler" listesinin anahtarı (her öğenin {@code metric_value}'su maskelenir)
     */
    @SuppressWarnings("unchecked")
    public static void apply(Map<String, Object> out, Map<String, Object> view, Set<String> ids, String historyKey) {
        if (out == null || view == null || ids == null || ids.isEmpty()) return;
        List<Map<String, Object>> results = asResults(view.get("rule_results"));
        boolean anyMasked = false;
        for (Map<String, Object> r : results) if (resultMasked(r, ids)) { anyMasked = true; break; }
        if (!results.isEmpty() && anyMasked) {
            List<Map<String, Object>> masked = maskResults(results, ids);
            out.put("rule_results", masked);
            if (view.get("rule_summary") != null) out.put("rule_summary", DbQueryAlarmText.breachSummary(masked));
        } else if (results.isEmpty() && view.get("rule_summary") != null) {
            // Özetin hangi kuraldan geldiği bilinemez: maskeli kural varsa değerler özetten çıkarılır.
            out.put("rule_summary", MASKED_SUMMARY);
        }
        if (view.get("metric_value") != null && metricMasked(results, ids)) out.put("metric_value", MASK);
        // Çözüm postasının güncel değerleri (son çalıştırmanın özeti; kural sonuçları bu öneki taşımaz → temkinli).
        if (view.get("resolved_metric_value") != null) out.put("resolved_metric_value", MASK);
        if (view.get("resolved_rule_summary") != null) out.put("resolved_rule_summary", MASKED_SUMMARY);
        Object h = historyKey == null ? null : view.get(historyKey);
        if (h instanceof List<?> list && !list.isEmpty()) {
            // Geçmiş satırları hangi kuralın ölçüsü olduğunu taşımaz → maskeli kural varsa her değer maskelenir.
            List<Map<String, Object>> copy = new ArrayList<>(list.size());
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> m)) continue;
                Map<String, Object> e = new LinkedHashMap<>((Map<String, Object>) m);
                if (e.get("metric_value") != null) e.put("metric_value", MASK);
                copy.add(e);
            }
            out.put(historyKey, copy);
        }
    }

    /**
     * Sonuçtaki karşılaştırma değeri → sayı; yoksa null. Var ama sayı olarak okunamıyorsa NaN ("değer VAR, ne olduğu
     * bilinmiyor") — kaynak seçiminde var sayılır, maske temkinli kalır.
     */
    private static Double number(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.doubleValue();
        try { return Double.valueOf(String.valueOf(v).trim()); } catch (NumberFormatException e) { return Double.NaN; }
    }

    /** Değerleri maskeli kural ihlali özeti (hangi kuralın ihlal ettiği bilinmediğinde). */
    public static final String MASKED_SUMMARY = "kural ihlali (maskelenen kolonun değeri gösterilmez)";

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asResults(Object v) {
        if (!(v instanceof List<?> l)) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : l) if (o instanceof Map<?, ?> m) out.add((Map<String, Object>) m);
        return out;
    }

    private static List<Object> readList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<Object> l = JSON.readValue(json, new TypeReference<List<Object>>() {});
            return l == null ? List.of() : l;
        } catch (Exception e) {
            return List.of();
        }
    }
}
