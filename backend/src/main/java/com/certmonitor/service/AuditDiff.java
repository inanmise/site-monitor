package com.certmonitor.service;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Denetim before/after diff üretici + hassas-alan maskeleyici. Çıktı: {@code {"alan":{"from":x,"to":y}}} JSON.
 * Yalnız DEĞİŞEN alanlar yazılır; fark yoksa {@code null}. Parola/token/secret/apiKey içeren anahtarların
 * from/to değerleri ASLA düz metin taşımaz ({@code ***} maskelenir) — kurcalanamazlık + gizlilik.
 */
public final class AuditDiff {

    private AuditDiff() {}

    public static final String MASK = "***";

    /** İki durum haritasını karşılaştırır; yalnız değişen alanları JSON diff olarak döner. Fark yoksa {@code null}. */
    public static String diff(Map<String, Object> before, Map<String, Object> after) {
        Map<String, Object> b = before != null ? before : Map.of();
        Map<String, Object> a = after != null ? after : Map.of();
        Set<String> keys = new LinkedHashSet<>();
        keys.addAll(b.keySet());
        keys.addAll(a.keySet());

        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (String k : keys) {
            Object from = b.get(k), to = a.get(k);
            if (Objects.equals(norm(from), norm(to))) continue;   // değişmemiş → atla
            boolean sensitive = isSensitive(k);
            if (!first) sb.append(",");
            first = false;
            sb.append(jsonStr(k)).append(":{\"from\":")
              .append(sensitive ? jsonStr(MASK) : jsonVal(from))
              .append(",\"to\":")
              .append(sensitive ? jsonStr(MASK) : jsonVal(to))
              .append("}");
        }
        sb.append("}");
        return first ? null : sb.toString();
    }

    /** Hassaslık kararı merkezî {@link SecretMask}'ten alınır (tek kara-liste); audit çıktısı {@link #MASK} kullanır. */
    public static boolean isSensitive(String key) {
        return SecretMask.isSensitive(key);
    }

    /** Bir entity'nin verilen alanlarını (getter/isX ile) haritaya çeker — güncelleme öncesi/sonrası snapshot. */
    public static java.util.Map<String, Object> snapshot(Object bean, String... fields) {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        if (bean == null) return m;
        for (String f : fields) {
            String cap = Character.toUpperCase(f.charAt(0)) + f.substring(1);
            Object v = invokeGetter(bean, "get" + cap);
            if (v == null) v = invokeGetter(bean, "is" + cap);
            // Koleksiyonu KOPYALA — before snapshot sonradan yerinde mutasyona uğramasın (üyelik değişimi kaçmasın).
            if (v instanceof java.util.Collection<?> c) v = new java.util.ArrayList<>(c);
            m.put(f, v);
        }
        return m;
    }

    private static Object invokeGetter(Object bean, String getter) {
        try {
            return bean.getClass().getMethod(getter).invoke(bean);
        } catch (Exception e) {
            return null;
        }
    }

    /** Tek bir değeri anahtar adına göre maskeler (serbest detay üretiminde). */
    public static String maskValue(String key, Object value) {
        return isSensitive(key) ? MASK : String.valueOf(value);
    }

    private static String norm(Object o) { return o == null ? null : String.valueOf(o); }

    private static String jsonVal(Object o) {
        if (o == null) return "null";
        if (o instanceof Number || o instanceof Boolean) return o.toString();
        return jsonStr(String.valueOf(o));
    }

    private static String jsonStr(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default   -> { if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c); }
            }
        }
        return b.append('"').toString();
    }
}
