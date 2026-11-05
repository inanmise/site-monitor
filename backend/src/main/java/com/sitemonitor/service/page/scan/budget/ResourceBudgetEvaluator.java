package com.sitemonitor.service.page.scan.budget;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bütçe değerlendirmesi (2026-11, F1) — SAF; okuma anında GÜNCEL bütçeyle (geçmiş analizler de bugünkü sınırlarla işaretlenir).
 * Alarm / bildirim üretmez.
 *
 * <p><b>Bilinmeyen asla "geçti" değildir:</b>
 * <ul>
 *   <li>analiz bitmemiş (RUNNING / FAILED / INTERRUPTED) → her sınır {@code ok:null}, neden {@code not_done};</li>
 *   <li>gerekli özet değeri yok (eski algoritmanın kolonu yok, ölçülemedi) → {@code ok:null}, neden {@code missing};</li>
 *   <li>analiz tavana takıldı ({@code capped}): sayılar ALT SINIRDIR — üst sınırlı (MAX) bir değer sınırı aşıyorsa kanıtlı
 *       aşımdır ({@code ok:false}), aşmıyorsa {@code ok:null} (neden {@code capped}). Güvenlik puanı eksik nesneyle ancak
 *       DÜŞEBİLİR (fazladan çerez, eksik SRI, açıklı kütüphane, karışık içerik — yalnız ceza), yani kısmi analizin güvenlik
 *       puanı bir üst sınırdır: alt sınırın (MIN) altındaysa aşımdır, değilse bilinmez. Performans notu ise üst sınır DEĞİLDİR
 *       (sıkıştırma / önbellek bileşenleri oran — D-2): kısmi analizde not sınırın altında da olsa üstünde de olsa
 *       {@code ok:null} (neden {@code capped}); bkz. {@link #violationProvenWhenCapped}.</li>
 * </ul>
 * Durum: hiç sınır yok → {@code none}; en az bir aşım → {@code exceeded} (diğerleri bilinmese de — aşım kanıtlı);
 * aşım yok ama bilinmeyen var → {@code unknown}; hepsi geçti → {@code ok}.
 *
 * <p>KB sınırlarında karşılaştırma TAM baytladır ({@code bayt > sınır × 1024}); gösterilen {@code actual} KB bir ondalığa
 * yuvarlanır ve yuvarlama yönü sonuçla çelişmez (aşan değer yukarı, aşmayan aşağı — "500,0 KB, sınır 500, aşıldı" yazılmaz);
 * tam bayt {@code actual_bytes} olarak da döner.
 */
public final class ResourceBudgetEvaluator {

    public static final String STATUS_OK = "ok", STATUS_EXCEEDED = "exceeded", STATUS_NONE = "none", STATUS_UNKNOWN = "unknown";
    public static final String REASON_MISSING = "missing", REASON_CAPPED = "capped", REASON_NOT_DONE = "not_done";

    private ResourceBudgetEvaluator() {}

    /**
     * Bir analizin bütçeye karşı ölçülen değerleri (özet kolonları). Değer null = bilinmiyor.
     *
     * @param status        analiz durumu (yalnız {@code DONE} değerlendirilir)
     * @param capped        analiz tavana takıldı mı (sayılar alt sınır)
     */
    public record Facts(String status, boolean capped, Long pageBytes, Long requestCount, Long jsBytes, Long imageBytes,
                        Long loadMs, Long grade, Long securityScore) {

        /** Alanın ölçülen değeri (özet kolonu adıyla). */
        Long value(String column) {
            return switch (column) {
                case "page_bytes" -> pageBytes;
                case "request_count" -> requestCount;
                case "js_bytes" -> jsBytes;
                case "image_bytes" -> imageBytes;
                case "load_ms" -> loadMs;
                case "grade" -> grade;
                case "security_score" -> securityScore;
                default -> null;
            };
        }
    }

    /** Tek sınırın sonucu. {@code ok}: true geçti / false aşıldı / null bilinmiyor ({@code reason}). */
    public record Check(String key, int limit, String unit, Number actual, Long actualBytes, Boolean ok, String reason) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", key);
            m.put("limit", limit);
            m.put("unit", unit);
            m.put("actual", actual);
            if (actualBytes != null) m.put("actual_bytes", actualBytes);
            m.put("ok", ok);
            m.put("reason", reason);
            return m;
        }
    }

    /** Bütçe sonucu: {@code exceeded} = aşılan sınır sayısı. */
    public record Result(boolean defined, String status, int exceeded, boolean capped, List<Check> checks) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("defined", defined);
            m.put("status", status);
            m.put("exceeded", exceeded);
            m.put("capped", capped);
            List<Map<String, Object>> list = new ArrayList<>(checks.size());
            for (Check c : checks) list.add(c.toMap());
            m.put("checks", list);
            return m;
        }
    }

    /** Bütçe tanımsızken sonuç (sorgu yapılmadan). */
    public static Result none() {
        return new Result(false, STATUS_NONE, 0, false, List.of());
    }

    public static Result evaluate(ResourceBudget budget, Facts facts) {
        if (budget == null || !budget.defined()) return none();
        boolean done = facts != null && "DONE".equals(facts.status());
        boolean capped = done && facts.capped();
        List<Check> checks = new ArrayList<>();
        int exceeded = 0;
        boolean unknown = false;
        for (ResourceBudget.Field f : ResourceBudget.FIELDS) {
            Integer limit = budget.limit(f.key());
            if (limit == null) continue;
            Check c = check(f, limit, done ? facts.value(f.column()) : null, done, capped);
            checks.add(c);
            if (Boolean.FALSE.equals(c.ok())) exceeded++;
            else if (c.ok() == null) unknown = true;
        }
        String status = exceeded > 0 ? STATUS_EXCEEDED : unknown ? STATUS_UNKNOWN : STATUS_OK;
        return new Result(true, status, exceeded, capped, List.copyOf(checks));
    }

    static Check check(ResourceBudget.Field f, int limit, Long value, boolean done, boolean capped) {
        boolean kb = "kb".equals(f.unit());
        if (!done) return new Check(f.key(), limit, f.unit(), null, null, null, REASON_NOT_DONE);
        if (value == null) return new Check(f.key(), limit, f.unit(), null, null, null, REASON_MISSING);
        boolean violated;
        if (f.kind() == ResourceBudget.Kind.MAX) {
            long bound = kb ? limit * 1024L : limit;
            violated = value > bound;
        } else {
            violated = value < limit;
        }
        // if/else (üçlü işleç DEĞİL): double ile Long'u karıştıran ?: sayıyı double'a terfi ettirirdi — "12" yerine "12.0"
        Number actual;
        if (kb) actual = kilobytes(value, violated);
        else actual = value;
        Long bytes = kb ? value : null;
        if (violated && (!capped || violationProvenWhenCapped(f))) {
            return new Check(f.key(), limit, f.unit(), actual, bytes, Boolean.FALSE, null);
        }
        if (capped) return new Check(f.key(), limit, f.unit(), actual, bytes, null, REASON_CAPPED);
        return new Check(f.key(), limit, f.unit(), actual, bytes, Boolean.TRUE, null);
    }

    /**
     * Kısmi (capped) analizde sınırın aşımı KANITLI mı (D-2, 2026-11): üst sınırlı (MAX) değerler ölçülmeyen nesneyle ancak
     * BÜYÜR (alt sınır) → evet; güvenlik puanı yalnız ceza düşer (eksik nesne fazladan çerez / SRI / açıklı kütüphane / karışık
     * içerik getirebilir, hiçbir ceza kalkmaz) → evet. Not HAYIR: sıkıştırma ve önbellek bileşenleri ORAN'dır, ölçülmeyen iyi
     * sıkıştırılmış / önbellekli nesneler notu YÜKSELTEBİLİR — kısmi analizde düşük not "bilinmiyor" ({@code capped}).
     */
    static boolean violationProvenWhenCapped(ResourceBudget.Field f) {
        return f.kind() == ResourceBudget.Kind.MAX || ResourceBudget.MIN_SECURITY_SCORE.equals(f.key());
    }

    /** Bayt → KB, bir ondalık; aşan değer yukarı, aşmayan aşağı yuvarlanır (gösterim sonuçla çelişmez). */
    static double kilobytes(long bytes, boolean violated) {
        return BigDecimal.valueOf(bytes).divide(BigDecimal.valueOf(1024), 1,
                violated ? RoundingMode.CEILING : RoundingMode.FLOOR).doubleValue();
    }
}
