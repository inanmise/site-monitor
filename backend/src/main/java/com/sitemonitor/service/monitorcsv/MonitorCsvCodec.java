package com.sitemonitor.service.monitorcsv;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.sitemonitor.service.monitorcsv.MonitorCsvSpec.Kind;
import com.sitemonitor.util.Csv;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * İzleme CSV'sinin hücre kuralları (2026-11-19) — dışa aktarım biçimi, içe aktarım ayrıştırması ve "değişti mi" karşılaştırması
 * TEK yerde, saf işlevler.
 *
 * <p><b>Formül koruması ve geri dönüş.</b> Dışa aktarım {@link Csv#cell} kuralını uygular ({@code = + - @} ile başlayan hücrenin
 * başına {@code '}). İçe aktarım bu işareti geri alır: hücre {@code '} ile başlıyor ve ardından bu karakterlerden biri (ya da
 * ikinci bir {@code '}) geliyorsa baştaki TEK {@code '} atılır. Gerçekten {@code '} ile başlayan değer dışa aktarımda bir
 * {@code '} daha alır — dışa aktarılan her değer aynen geri gelir.
 */
public final class MonitorCsvCodec {

    private MonitorCsvCodec() { }

    /** Sıralı anahtarlı kanonik JSON (karşılaştırma için). */
    private static final ObjectMapper CANON = new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    private static final ObjectMapper PLAIN = new ObjectMapper();

    /** Ayrıştırma hatası — {@code code} kalıcı ({@code mcsv.cell.<code>}). */
    public static final class CellException extends Exception {
        private final String code;

        public CellException(String code) {
            super(code);
            this.code = code;
        }

        public String code() { return code; }
    }

    public static final String C_NOT_INT = "not_int";
    public static final String C_NOT_BOOL = "not_bool";
    public static final String C_NOT_JSON = "not_json";
    public static final String C_NOT_LIST = "not_list";

    // ── Dışa aktarım ──────────────────────────────────────────────────────────────────────────────────────────────

    /** Gövde biçimindeki değerin hücre metni (koruma öncesi). Boş liste / nesne → boş hücre. */
    public static String text(Kind kind, Object v) {
        if (v == null) return "";
        switch (kind) {
            case BOOL, INFO:
                return v instanceof Boolean b ? (b ? "true" : "false") : String.valueOf(v);
            case INT:
                return v instanceof Number n ? String.valueOf(n.longValue()) : String.valueOf(v);
            case JSON, ENV:
                if (v instanceof Collection<?> c && c.isEmpty()) return "";
                if (v instanceof Map<?, ?> m && m.isEmpty()) return "";
                try {
                    return PLAIN.writeValueAsString(v);
                } catch (Exception e) {
                    return "";
                }
            default:
                return String.valueOf(v);
        }
    }

    /** CSV hücresi: baştaki {@code '} ikilenir, sonra ortak formül koruması + tırnaklama ({@link Csv#cell}). */
    public static String cell(String s) {
        if (s == null || s.isEmpty()) return "";
        if (s.charAt(0) == '\'') s = "'" + s;
        return Csv.cell(s);
    }

    /** CSV satırı (CRLF ile biter). */
    public static String row(List<String> texts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < texts.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(cell(texts.get(i)));
        }
        return sb.append("\r\n").toString();
    }

    // ── İçe aktarım ───────────────────────────────────────────────────────────────────────────────────────────────

    /** Formül işaretini geri alır (bkz. sınıf belgesi). */
    public static String unguard(String raw) {
        if (raw == null || raw.length() < 2 || raw.charAt(0) != '\'') return raw;
        char c = raw.charAt(1);
        if (c == '=' || c == '+' || c == '-' || c == '@' || c == '\t' || c == '\r' || c == '\'') return raw.substring(1);
        return raw;
    }

    /**
     * Hücre → gövde değeri. Başvuru türleri (grup / bağlantı) ADI döner (çağıran kimliğe çevirir); 7/24 grupları ad listesi.
     * Çağıran boş hücreyi buraya göndermez (boş = "değiştirme").
     */
    public static Object parse(Kind kind, String raw) throws CellException {
        String s = raw == null ? "" : raw;
        switch (kind) {
            case TEXT:
                return s.trim();
            case LONG_TEXT:
                return s.replace("\r\n", "\n");
            case INT: {
                String t = s.trim();
                if (t.length() > 11) throw new CellException(C_NOT_INT);
                try {
                    return Integer.valueOf(t);
                } catch (NumberFormatException e) {
                    throw new CellException(C_NOT_INT);
                }
            }
            case BOOL, INFO: {
                Boolean b = bool(s);
                if (b == null) throw new CellException(C_NOT_BOOL);
                return b;
            }
            case JSON: {
                try {
                    return PLAIN.readValue(s.trim(), Object.class);
                } catch (Exception e) {
                    throw new CellException(C_NOT_JSON);
                }
            }
            case ENV: {
                Object v;
                try {
                    v = PLAIN.readValue(s.trim(), Object.class);
                } catch (Exception e) {
                    throw new CellException(C_NOT_JSON);
                }
                if (!(v instanceof List<?> list)) throw new CellException(C_NOT_LIST);
                List<Map<String, Object>> out = new ArrayList<>();
                for (Object o : list) {
                    if (!(o instanceof Map<?, ?> m)) throw new CellException(C_NOT_LIST);
                    Object nm = m.get("name");
                    if (nm == null || nm.toString().isBlank()) continue;
                    boolean secret = Boolean.TRUE.equals(m.get("secret")) || "true".equalsIgnoreCase(String.valueOf(m.get("secret")));
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("name", nm.toString().trim());
                    // Gizli değişkenin değeri dosyadan ALINMAZ (sır CSV'de dolaşmaz) — içe aktarımdan sonra arayüzde girilir
                    row.put("value", secret ? "" : (m.get("value") == null ? "" : m.get("value").toString()));
                    row.put("secret", secret);
                    out.add(row);
                }
                return out;
            }
            case NOC_GROUPS: {
                List<String> names = new ArrayList<>();
                int start = 0;
                for (int i = 0; i <= s.length(); i++) {
                    if (i == s.length() || s.charAt(i) == ';') {
                        String part = s.substring(start, i).trim();
                        if (!part.isEmpty() && names.size() < 100) names.add(part);
                        start = i + 1;
                    }
                }
                return names;
            }
            default:
                return s.trim();
        }
    }

    /** true / false, 1 / 0, yes / no, evet / hayır (büyük / küçük harf duyarsız); başka → null. */
    public static Boolean bool(String raw) {
        if (raw == null) return null;
        String t = raw.trim().toLowerCase(Locale.forLanguageTag("tr"));
        return switch (t) {
            case "true", "1", "yes", "evet", "e" -> Boolean.TRUE;
            case "false", "0", "no", "hayır", "hayir", "h" -> Boolean.FALSE;
            default -> null;
        };
    }

    // ── Karşılaştırma ─────────────────────────────────────────────────────────────────────────────────────────────

    /** Kanonik metin: iki değer "aynı" ⇔ kanonik metinleri eşit. null → {@code nullAs} (yoksa boş). */
    public static String canon(Kind kind, Object v, String nullAs, boolean upper) {
        String s;
        if (v == null) {
            s = nullAs == null ? "" : nullAs;
        } else {
            switch (kind) {
                case BOOL, INFO -> s = v instanceof Boolean b ? (b ? "true" : "false") : String.valueOf(v).trim().toLowerCase(Locale.ROOT);
                case INT -> s = v instanceof Number n ? String.valueOf(n.longValue()) : String.valueOf(v).trim();
                case JSON, ENV -> {
                    if ((v instanceof Collection<?> c && c.isEmpty()) || (v instanceof Map<?, ?> m && m.isEmpty())) s = "";
                    else {
                        try {
                            s = CANON.writeValueAsString(CANON.readValue(PLAIN.writeValueAsString(v), Object.class));
                        } catch (Exception e) {
                            s = String.valueOf(v);
                        }
                    }
                }
                case LONG_TEXT -> s = String.valueOf(v).replace("\r\n", "\n");
                case NOC_GROUPS -> {
                    List<String> ids = new ArrayList<>();
                    if (v instanceof Collection<?> c) for (Object o : c) if (o != null) ids.add(String.valueOf(o));
                    ids.sort(null);
                    s = String.join(",", ids);
                }
                default -> s = String.valueOf(v).trim();
            }
        }
        return upper ? s.toUpperCase(Locale.ROOT) : s;
    }
}
