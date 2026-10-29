package com.sitemonitor.service.checks;

import com.sitemonitor.service.monitoring.ManualEvalInbox;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Şekilli kontrol sonucunun JSON kodlaması (2026-10-29, çok pod Sürüm 3-3 P0, tasarım R3-3 §3.1 {@code result_json} /
 * §4.2) — işçi (A) {@link #encode}, değerlendirici (B / C) {@link #decode}; TEK kodlayıcı, elle kontrol gelen kutusuyla AYNI
 * Jackson kuralı ({@link ManualEvalInbox#toJson} / {@link ManualEvalInbox#parse} — {@code ManualEvalRoundTripTest}'in
 * kanıtladığı gidiş-dönüş: sayılar Integer / Long / Double, listeler ArrayList, haritalar LinkedHashMap; tanınmayan değer düz
 * türe indirgenir).
 *
 * <p><b>Boyut.</b> {@link #MAX_RESULT_CHARS} (256 K karakter) sınırı: aşan sonuçta {@link #CLIP_CHARS}'tan uzun her metin
 * değeri kırpılır ve {@code truncated = true}; yine sığmazsa {@code {"failed":"ResultTooLarge"}} (kalem üretilmez — bugünkü
 * "kontrol düştü" ile aynı sonuç). Gerçek sonuçlar (çıktı kuyruğu, sorunlu kaynak satırları ≤ 10) bu sınıra hiç yaklaşmaz.
 * Asla fırlatmaz; sonuç metni asla günlüğe yazılmaz.
 */
public final class CheckResultCodec {

    private CheckResultCodec() {
    }

    /** {@code result_json} üst sınırı (karakter). */
    public static final int MAX_RESULT_CHARS = 256 * 1024;
    /** Sığmayan sonuçta metin değerlerinin kırpılacağı uzunluk (karakter). */
    public static final int CLIP_CHARS = 4_096;
    /** Kodlanamayan / sığmayan sonucun işareti ({@link CheckJobRunner#KEY_FAILED} değeri). */
    public static final String TOO_LARGE = "ResultTooLarge";
    /** Çözülemeyen JSON'un işareti ({@link CheckJobRunner#KEY_FAILED} değeri). */
    public static final String UNREADABLE = "ResultUnreadable";

    /** Kodlanmış sonuç. */
    public record Encoded(String json, boolean truncated) {
        @Override
        public String toString() {
            return "Encoded[chars=" + (json == null ? 0 : json.length()) + ", truncated=" + truncated + "]";
        }
    }

    /** Sonucu JSON'a çevirir (null → {@link CheckJobRunner#failed(Throwable)} işareti, sınıf "NullResult"). */
    public static Encoded encode(Map<String, Object> result) {
        if (result == null) return new Encoded(failedJson("NullResult"), false);
        String json;
        try {
            json = ManualEvalInbox.toJson(result);
        } catch (RuntimeException e) {
            return new Encoded(failedJson(e.getClass().getSimpleName()), false);
        }
        if (json.length() <= MAX_RESULT_CHARS) return new Encoded(json, false);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> clipped = (Map<String, Object>) clip(result, 0);
            String c = ManualEvalInbox.toJson(clipped);
            if (c.length() <= MAX_RESULT_CHARS) return new Encoded(c, true);
        } catch (RuntimeException ignore) {
            // aşağıda işaret
        }
        return new Encoded(failedJson(TOO_LARGE), true);
    }

    /**
     * JSON'u değiştirilebilir haritaya çözer: null / boş → boş harita (kalem üretmez, {@link CheckJobRunner#carriesResult}
     * false); bozuk JSON → {@code {"failed":"ResultUnreadable"}}. Asla fırlatmaz.
     */
    public static Map<String, Object> decode(String json) {
        if (json == null || json.isBlank()) return new LinkedHashMap<>();
        try {
            Map<String, Object> m = ManualEvalInbox.parse(json);
            return m == null ? new LinkedHashMap<>() : m;
        } catch (RuntimeException e) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put(CheckJobRunner.KEY_FAILED, UNREADABLE);
            return m;
        }
    }

    private static String failedJson(String cls) {
        return "{\"" + CheckJobRunner.KEY_FAILED + "\":\"" + cls + "\"}";
    }

    /** Uzun metinleri kırpılmış düz kopya (derinlik tavanlı; vekil çiftini bölmez). */
    private static Object clip(Object v, int depth) {
        if (v == null || v instanceof Number || v instanceof Boolean) return v;
        if (v instanceof CharSequence cs) {
            String s = cs.toString();
            if (s.length() <= CLIP_CHARS) return s;
            int end = CLIP_CHARS;
            if (Character.isHighSurrogate(s.charAt(end - 1))) end--;
            return s.substring(0, end) + "…";
        }
        if (depth > 32) return "…";
        if (v instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) out.put(String.valueOf(e.getKey()), clip(e.getValue(), depth + 1));
            return out;
        }
        if (v instanceof Collection<?> c) {
            List<Object> out = new ArrayList<>(c.size());
            for (Object o : c) out.add(clip(o, depth + 1));
            return out;
        }
        if (v.getClass().isArray()) {
            int len = java.lang.reflect.Array.getLength(v);
            List<Object> out = new ArrayList<>(len);
            for (int i = 0; i < len; i++) out.add(clip(java.lang.reflect.Array.get(v, i), depth + 1));
            return out;
        }
        if (v instanceof Enum<?> en) return en.name();
        return clip(String.valueOf(v), depth + 1);
    }
}
