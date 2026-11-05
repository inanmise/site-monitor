package com.sitemonitor.service.page.scan.budget;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException;
import com.sitemonitor.util.Msg;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sayfa Bütünlüğü kaynak analizi BÜTÇESİ (2026-11, F1) — izleme başına isteğe bağlı sınırlar. Değerlendirme okuma anındadır
 * ({@link ResourceBudgetEvaluator}); bütçe analizi, alarmı ya da bildirimi DEĞİŞTİRMEZ.
 *
 * <p>Saklama biçimi {@code page_monitors.resource_budget_json}: yalnız TANIMLI sınırlar, sabit sırayla
 * ({@code {"max_page_kb":500,"min_grade":80}}); hiç sınır yoksa kolon NULL. Okuma GEVŞEK ({@link #parse}: bozuk JSON, tam sayı
 * olmayan ya da aralık dışı değer o sınırı "tanımsız" yapar — elle bozulmuş satır analizi kırmaz), yazma KATI
 * ({@link #withChanges}: alan hatası 400 {@code VALIDATION_FAILED} + {@code field}).
 *
 * <p>Sınırlar (sözleşme F1, tek yer — {@link #FIELDS}): {@code max_page_kb} 50–51200, {@code max_requests} 5–2000,
 * {@code max_js_kb} 10–20480, {@code max_image_kb} 10–51200, {@code max_load_ms} 200–60000, {@code min_grade} 0–100,
 * {@code min_security_score} 0–100.
 */
public final class ResourceBudget {

    /** Sınırın yönü: MAX = ölçülen değer sınırı AŞMAMALI; MIN = ölçülen değer sınırın ALTINA inmemeli. */
    public enum Kind { MAX, MIN }

    /**
     * Bir bütçe alanı.
     *
     * @param key    API / JSON anahtarı (snake_case, kalıcı)
     * @param min    izin verilen en küçük sınır
     * @param max    izin verilen en büyük sınır
     * @param kind   yön
     * @param unit   sınırın birimi: {@code kb} | {@code count} | {@code ms} | {@code score}
     * @param column ölçülen değerin özet kolonu ({@code page_resource_scans})
     */
    public record Field(String key, int min, int max, Kind kind, String unit, String column) {}

    public static final String MAX_PAGE_KB = "max_page_kb";
    public static final String MAX_REQUESTS = "max_requests";
    public static final String MAX_JS_KB = "max_js_kb";
    public static final String MAX_IMAGE_KB = "max_image_kb";
    public static final String MAX_LOAD_MS = "max_load_ms";
    public static final String MIN_GRADE = "min_grade";
    public static final String MIN_SECURITY_SCORE = "min_security_score";

    /** Alanların TEK kaynağı (sıra = arayüz ve denetim sırası). */
    public static final List<Field> FIELDS = List.of(
            new Field(MAX_PAGE_KB, 50, 51_200, Kind.MAX, "kb", "page_bytes"),
            new Field(MAX_REQUESTS, 5, 2_000, Kind.MAX, "count", "request_count"),
            new Field(MAX_JS_KB, 10, 20_480, Kind.MAX, "kb", "js_bytes"),
            new Field(MAX_IMAGE_KB, 10, 51_200, Kind.MAX, "kb", "image_bytes"),
            new Field(MAX_LOAD_MS, 200, 60_000, Kind.MAX, "ms", "load_ms"),
            new Field(MIN_GRADE, 0, 100, Kind.MIN, "score", "grade"),
            new Field(MIN_SECURITY_SCORE, 0, 100, Kind.MIN, "score", "security_score"));

    /** Saklanan JSON'un üst sınırı (karakter) — gevşek okuma bundan uzun metni ayrıştırmaz. */
    static final int MAX_STORED_CHARS = 2_000;

    public static final ResourceBudget EMPTY = new ResourceBudget(Map.of());

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Tanımlı sınırlar (alan sırasıyla; yalnız tanımlılar). */
    private final Map<String, Integer> limits;

    private ResourceBudget(Map<String, Integer> limits) {
        Map<String, Integer> ordered = new LinkedHashMap<>();
        for (Field f : FIELDS) {
            Integer v = limits.get(f.key());
            if (v != null) ordered.put(f.key(), v);
        }
        this.limits = Collections.unmodifiableMap(ordered);
    }

    /** Alan tanımı; bilinmeyen anahtar → null. */
    public static Field field(String key) {
        for (Field f : FIELDS) if (f.key().equals(key)) return f;
        return null;
    }

    /** Sınır; tanımsız → null. */
    public Integer limit(String key) {
        return limits.get(key);
    }

    /** En az bir sınır tanımlı mı. */
    public boolean defined() {
        return !limits.isEmpty();
    }

    /** Yedi anahtarın tamamı (tanımsız = null), alan sırasıyla — API yanıtı ve denetim farkı bunu kullanır. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        for (Field f : FIELDS) m.put(f.key(), limits.get(f.key()));
        return m;
    }

    /** Saklanacak JSON; hiç sınır yoksa null (kolon NULL = bütçe yok). */
    public String toJson() {
        if (limits.isEmpty()) return null;
        try {
            return JSON.writeValueAsString(limits);
        } catch (Exception e) {
            throw new IllegalStateException("bütçe JSON'a yazılamadı", e);
        }
    }

    /**
     * Saklanan değeri GEVŞEK okur: null / boş / bozuk / çok uzun → boş bütçe; tam sayı olmayan, aralık dışı ya da bilinmeyen
     * anahtar o sınırı tanımsız bırakır. Asla fırlatmaz.
     */
    public static ResourceBudget parse(String json) {
        if (json == null || json.isBlank() || json.length() > MAX_STORED_CHARS) return EMPTY;
        Map<String, Object> raw;
        try {
            raw = JSON.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return EMPTY;
        }
        if (raw == null) return EMPTY;
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Field f : FIELDS) {
            Integer v = integral(raw.get(f.key()));
            if (v != null && v >= f.min() && v <= f.max()) out.put(f.key(), v);
        }
        return out.isEmpty() ? EMPTY : new ResourceBudget(out);
    }

    /**
     * İstek gövdesindeki değişiklikleri KATI uygular: yalnız gövdede GELEN bilinen anahtarlar değişir (null = sınırı kaldır),
     * gelmeyenler aynen kalır; bilinmeyen anahtar yok sayılır. İlk geçersiz alan (alan sırasıyla) 400 alan hatası olur.
     *
     * @throws FieldValidationException tam sayı olmayan (metin, ondalık, mantıksal) ya da aralık dışı değer
     */
    public ResourceBudget withChanges(Map<String, Object> body) {
        if (body == null || body.isEmpty()) return this;
        Map<String, Integer> next = new LinkedHashMap<>(limits);
        for (Field f : FIELDS) {
            if (!body.containsKey(f.key())) continue;
            Object v = body.get(f.key());
            if (v == null) {
                next.remove(f.key());
                continue;
            }
            Integer n = integral(v);
            if (n == null || n < f.min() || n > f.max()) {
                throw new FieldValidationException(f.key(), Msg.t(
                        label(f, false) + " " + f.min() + " ile " + f.max() + " arasında bir tam sayı olmalı; boş bırakırsanız sınır yok sayılır.",
                        label(f, true) + " must be a whole number between " + f.min() + " and " + f.max() + "; leave it empty for no limit."));
            }
            next.put(f.key(), n);
        }
        return next.isEmpty() ? EMPTY : new ResourceBudget(next);
    }

    /** Tam sayıya çevirir: yalnız tam değerli sayı (Long/Integer, ya da .0 ondalık); metin / mantıksal / kesirli → null. */
    static Integer integral(Object v) {
        if (!(v instanceof Number n)) return null;
        double d = n.doubleValue();
        if (Double.isNaN(d) || Double.isInfinite(d) || d != Math.rint(d)) return null;
        if (d < Integer.MIN_VALUE || d > Integer.MAX_VALUE) return null;
        return (int) d;
    }

    /** Alan hatası metnindeki insan dilindeki ad. */
    private static String label(Field f, boolean en) {
        return switch (f.key()) {
            case MAX_PAGE_KB -> en ? "Maximum page size (KB)" : "En büyük sayfa boyutu (KB)";
            case MAX_REQUESTS -> en ? "Maximum request count" : "En çok istek sayısı";
            case MAX_JS_KB -> en ? "Maximum JavaScript size (KB)" : "En büyük JavaScript boyutu (KB)";
            case MAX_IMAGE_KB -> en ? "Maximum image size (KB)" : "En büyük görsel boyutu (KB)";
            case MAX_LOAD_MS -> en ? "Maximum estimated load time (ms)" : "En uzun tahmini yükleme süresi (ms)";
            case MIN_GRADE -> en ? "Minimum performance grade" : "En düşük performans notu";
            case MIN_SECURITY_SCORE -> en ? "Minimum security score" : "En düşük güvenlik puanı";
            default -> f.key();
        };
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ResourceBudget b && limits.equals(b.limits);
    }

    @Override
    public int hashCode() {
        return limits.hashCode();
    }

    @Override
    public String toString() {
        return "ResourceBudget" + limits;
    }
}
