package com.sitemonitor.service.sqlplayground;

import com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException;
import com.sitemonitor.util.Msg;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * SQL Playground adlandırılmış parametreleri (2026-11-13, B4) — {@code :ad} yer tutucuları.
 *
 * <p><b>Tanım (istemciyle AYNI — doğruluk tablosu {@code SqlParamsTest.TABLE}).</b> Yer tutucu: tek {@code :} ve
 * hemen ardından gelen tanımlayıcı karakter dizisi (Unicode harf / rakam, {@code _}, {@code $} — en uzun dizi). Dizi
 * {@code [A-Za-z_][A-Za-z0-9_]{0,39}} kalıbına TAMAMEN uyuyorsa parametre adıdır, uymuyorsa (ör. {@code :adı},
 * {@code :a$b}, 41 karakter, rakamla başlama) yer tutucu değildir ve metin olduğu gibi kalır. Önünde ya da arkasında
 * başka bir {@code :} olan iki nokta ({@code ::tip} dönüşümü, {@code :=}) yer tutucu değildir. Dize ({@code '…'},
 * {@code E'…'}, {@code $$…$$}), tırnaklı tanımlayıcı ({@code "…"}) ve yorum İÇİNDEKİ {@code :ad} sayılmaz.
 *
 * <p><b>Bağlama.</b> Her yer tutucu JDBC {@code ?} olur ve türüyle bağlanır — ASLA metin birleştirme yok. Aynı ad birden
 * çok kez geçerse aynı değer her konuma bağlanır. PostgreSQL'de parametreli sorgudaki gerçek {@code ?} işleci (jsonb
 * {@code ?}, {@code ?|}, {@code ?&}) JDBC kaçışı {@code ??} olur. Değerler geçmişe / denetime / günlüğe yazılmaz.
 *
 * <p>Sınırlar: en çok {@value #MAX_PARAMS} ayrı ad, değer en çok {@value #MAX_VALUE_CHARS} karakter.
 */
public final class SqlParams {

    private SqlParams() {}

    public static final int MAX_PARAMS = 20;
    public static final int MAX_VALUE_CHARS = 1000;
    public static final int MAX_NAME_CHARS = 40;

    /** Desteklenen türler. */
    public static final List<String> TYPES = List.of("text", "number", "date", "timestamp", "bool");

    /** Bir yer tutucunun metindeki konumu ({@code start} = iki nokta, {@code end} = adın bitişi, hariç). */
    public record Placeholder(String name, int start, int end) {}

    /** İstemcinin gönderdiği değer + tür. */
    public record Param(String value, String type) {}

    /** Çözümlenmiş sorgu: JDBC metni, sıradaki bağlanacak adlar, ayrı adlar (görünüş sırasıyla). */
    public record Bound(String jdbcSql, List<String> order, List<String> names) {
        public boolean isEmpty() { return order.isEmpty(); }
    }

    // ── Yer tutucu bulma (doğrusal) ───────────────────────────────────────────────────────────────────────────

    /** Metindeki yer tutucular (sırasıyla). Dize / tanımlayıcı / yorum içleri atlanır. */
    public static List<Placeholder> find(String sql) {
        List<Placeholder> out = new ArrayList<>();
        if (sql == null) return out;
        int n = sql.length();
        int i = 0;
        while (i < n) {
            char c = sql.charAt(i);
            char c1 = i + 1 < n ? sql.charAt(i + 1) : 0;
            if (c == '-' && c1 == '-') { i = lineEnd(sql, i); continue; }
            if (c == '/' && c1 == '*') { i = blockCommentEnd(sql, i); continue; }
            if (c == '\'') {
                boolean backslash = i > 0 && (sql.charAt(i - 1) == 'E' || sql.charAt(i - 1) == 'e')
                        && (i < 2 || !isIdentChar(sql.charAt(i - 2)));
                i = quotedEnd(sql, i + 1, '\'', backslash);
                continue;
            }
            if (c == '"') { i = quotedEnd(sql, i + 1, '"', false); continue; }
            if (c == '$') {
                int tagEnd = dollarTagEnd(sql, i);
                if (tagEnd > 0 && (i == 0 || !isIdentChar(sql.charAt(i - 1)))) {
                    String tag = sql.substring(i, tagEnd + 1);
                    int close = sql.indexOf(tag, tagEnd + 1);
                    i = close < 0 ? n : close + tag.length();
                    continue;
                }
                i++;
                continue;
            }
            if (c == ':') {
                boolean prevColon = i > 0 && sql.charAt(i - 1) == ':';
                if (c1 == ':' || prevColon) { i++; continue; }
                int j = i + 1;
                while (j < n && isIdentChar(sql.charAt(j))) j++;
                if (j > i + 1 && isValidName(sql, i + 1, j)) out.add(new Placeholder(sql.substring(i + 1, j), i, j));
                i = Math.max(j, i + 1);
                continue;
            }
            if (isIdentChar(c)) {   // tanımlayıcı içindeki '$' dolar tırnağı değildir (ab$1)
                int j = i + 1;
                while (j < n && isIdentChar(sql.charAt(j))) j++;
                i = j;
                continue;
            }
            i++;
        }
        return out;
    }

    /** Ayrı adlar, ilk görünüş sırasıyla. */
    public static List<String> names(String sql) {
        Set<String> s = new LinkedHashSet<>();
        for (Placeholder p : find(sql)) s.add(p.name());
        return List.copyOf(s);
    }

    /**
     * Yer tutucuları {@code ?} yapar. {@code escapeQuestionMarks} (PostgreSQL + en az bir parametre) gerçek {@code ?}
     * karakterlerini (dize / yorum / tanımlayıcı DIŞINDA) {@code ??} yapar.
     */
    public static Bound bind(String sql, boolean escapeQuestionMarks) {
        List<Placeholder> ph = find(sql);
        if (ph.isEmpty()) return new Bound(sql, List.of(), List.of());
        Set<String> distinct = new LinkedHashSet<>();
        for (Placeholder p : ph) distinct.add(p.name());
        if (distinct.size() > MAX_PARAMS) {
            throw new FieldValidationException("SQL_PARAM_INVALID", "params", Msg.t(
                    "Sorguda en çok " + MAX_PARAMS + " ayrı parametre olabilir (" + distinct.size() + " bulundu). Bazılarını sabit değere çevirin.",
                    "A query can have at most " + MAX_PARAMS + " distinct parameters (" + distinct.size() + " found). Turn some of them into fixed values."));
        }
        boolean[] code = escapeQuestionMarks ? codeMask(sql) : null;
        StringBuilder sb = new StringBuilder(sql.length() + 8);
        List<String> order = new ArrayList<>(ph.size());
        int pos = 0;
        for (Placeholder p : ph) {
            appendEscaped(sb, sql, pos, p.start(), code);
            sb.append('?');
            order.add(p.name());
            pos = p.end();
        }
        appendEscaped(sb, sql, pos, sql.length(), code);
        return new Bound(sb.toString(), List.copyOf(order), List.copyOf(distinct));
    }

    private static void appendEscaped(StringBuilder sb, String sql, int from, int to, boolean[] code) {
        for (int i = from; i < to; i++) {
            char c = sql.charAt(i);
            sb.append(c);
            if (c == '?' && code != null && code[i]) sb.append('?');
        }
    }

    /** Hangi karakterlerin "kod" olduğu (dize / yorum / tanımlayıcı dışı) — {@link #find} ile aynı kurallar. */
    private static boolean[] codeMask(String sql) {
        int n = sql.length();
        boolean[] code = new boolean[n];
        int i = 0;
        while (i < n) {
            char c = sql.charAt(i);
            char c1 = i + 1 < n ? sql.charAt(i + 1) : 0;
            int skipTo = -1;
            if (c == '-' && c1 == '-') skipTo = lineEnd(sql, i);
            else if (c == '/' && c1 == '*') skipTo = blockCommentEnd(sql, i);
            else if (c == '\'') {
                boolean backslash = i > 0 && (sql.charAt(i - 1) == 'E' || sql.charAt(i - 1) == 'e')
                        && (i < 2 || !isIdentChar(sql.charAt(i - 2)));
                skipTo = quotedEnd(sql, i + 1, '\'', backslash);
            } else if (c == '"') skipTo = quotedEnd(sql, i + 1, '"', false);
            else if (c == '$' && (i == 0 || !isIdentChar(sql.charAt(i - 1)))) {
                int tagEnd = dollarTagEnd(sql, i);
                if (tagEnd > 0) {
                    String tag = sql.substring(i, tagEnd + 1);
                    int close = sql.indexOf(tag, tagEnd + 1);
                    skipTo = close < 0 ? n : close + tag.length();
                }
            }
            if (skipTo > i) { i = skipTo; continue; }
            code[i] = true;
            i++;
        }
        return code;
    }

    // ── Değerler ──────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * İstek gövdesindeki {@code params} alanını okur: {@code {ad: {value, type}}} ya da kısaca {@code {ad: "değer"}}
     * (metin). Bilinmeyen biçim → 400 {@code SQL_PARAM_INVALID}.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Param> parse(Object raw) {
        if (raw == null) return Map.of();
        if (!(raw instanceof Map<?, ?> m)) {
            throw new FieldValidationException("SQL_PARAM_INVALID", "params", Msg.t(
                    "Parametreler ad → {değer, tür} biçiminde gönderilmeli.",
                    "Parameters must be sent as name → {value, type}."));
        }
        Map<String, Param> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) {
            String name = String.valueOf(e.getKey());
            if (out.size() >= MAX_PARAMS * 2) break;   // gövde sınırı: gereksiz büyük haritayı okumayı kes
            Object v = e.getValue();
            if (v instanceof Map<?, ?> pm) {
                Object value = ((Map<String, Object>) pm).get("value");
                Object type = ((Map<String, Object>) pm).get("type");
                out.put(name, new Param(value == null ? null : String.valueOf(value),
                        type == null ? "text" : String.valueOf(type).trim().toLowerCase(Locale.ROOT)));
            } else if (v == null) {
                out.put(name, new Param(null, "text"));
            } else {
                out.put(name, new Param(String.valueOf(v), "text"));
            }
        }
        return Collections.unmodifiableMap(out);
    }

    /** Sorgudaki her yer tutucunun değeri var ve türüne uyuyor mu — değilse alan hatası ({@code params.<ad>}). */
    public static void validate(List<String> names, Map<String, Param> params) {
        for (String name : names) {
            Param p = params.get(name);
            if (p == null || p.value() == null) {
                throw new FieldValidationException("SQL_PARAM_MISSING", "params." + name, Msg.t(
                        "\":" + name + "\" parametresinin değeri girilmedi. Değeri girip sorguyu yeniden çalıştırın.",
                        "No value was entered for the \":" + name + "\" parameter. Enter it and run the query again."));
            }
            convert(name, p);
        }
    }

    /** Değeri türüne çevirir; geçersizse 400 {@code SQL_PARAM_INVALID} alan {@code params.<ad>}. */
    static Object convert(String name, Param p) {
        String v = p.value();
        String type = p.type() == null ? "text" : p.type();
        if (v != null && v.length() > MAX_VALUE_CHARS) {
            throw invalid(name, Msg.t("en çok " + MAX_VALUE_CHARS + " karakter olabilir",
                    "can be at most " + MAX_VALUE_CHARS + " characters"));
        }
        switch (type) {
            case "text":
                return v;
            case "number":
                try {
                    String t = v.trim();
                    if (t.isEmpty()) throw new NumberFormatException();
                    return new BigDecimal(t);
                } catch (NumberFormatException e) {
                    throw invalid(name, Msg.t("sayı olmalı (ör. 42 ya da 3.5)", "must be a number (e.g. 42 or 3.5)"));
                }
            case "date":
                try {
                    return LocalDate.parse(v.trim());
                } catch (DateTimeParseException e) {
                    throw invalid(name, Msg.t("tarih YYYY-AA-GG biçiminde olmalı (ör. 2026-11-13)",
                            "must be a date in YYYY-MM-DD form (e.g. 2026-11-13)"));
                }
            case "timestamp":
                try {
                    return LocalDateTime.parse(v.trim().replace(' ', 'T'));
                } catch (DateTimeParseException e) {
                    throw invalid(name, Msg.t("zaman YYYY-AA-GGTSS:DD[:ss] biçiminde olmalı (ör. 2026-11-13T09:30)",
                            "must be a timestamp in YYYY-MM-DDTHH:MM[:ss] form (e.g. 2026-11-13T09:30)"));
                }
            case "bool":
                String b = v.trim().toLowerCase(Locale.ROOT);
                if (b.equals("true") || b.equals("1")) return Boolean.TRUE;
                if (b.equals("false") || b.equals("0")) return Boolean.FALSE;
                throw invalid(name, Msg.t("true ya da false olmalı", "must be true or false"));
            default:
                throw invalid(name, Msg.t("türü tanınmadı (text, number, date, timestamp, bool)",
                        "has an unknown type (text, number, date, timestamp, bool)"));
        }
    }

    private static FieldValidationException invalid(String name, String why) {
        return new FieldValidationException("SQL_PARAM_INVALID", "params." + name,
                Msg.t("\":" + name + "\" parametresi " + why + ".", "The \":" + name + "\" parameter " + why + "."));
    }

    /** Sıradaki her {@code ?} konumuna değeri türüyle bağlar. */
    public static void apply(PreparedStatement ps, Bound bound, Map<String, Param> params) throws SQLException {
        int i = 1;
        for (String name : bound.order()) {
            Object v = convert(name, params.get(name));
            if (v instanceof String s) ps.setString(i, s);
            else if (v instanceof BigDecimal d) ps.setBigDecimal(i, d);
            else if (v instanceof LocalDate d) ps.setObject(i, d);
            else if (v instanceof LocalDateTime d) ps.setObject(i, d);
            else if (v instanceof Boolean b) ps.setBoolean(i, b);
            else ps.setObject(i, v);
            i++;
        }
    }

    /** Kayıtlı sorguda saklanacak tür haritası (DEĞER YOK): yalnız geçerli adlar ve türler. */
    public static Map<String, String> typesOf(Map<String, Param> params) {
        Map<String, String> out = new LinkedHashMap<>();
        if (params == null) return out;
        for (Map.Entry<String, Param> e : params.entrySet()) {
            String name = e.getKey();
            if (out.size() >= MAX_PARAMS) break;
            if (!isValidName(name, 0, name.length())) continue;
            String type = e.getValue() == null ? "text" : e.getValue().type();
            if (TYPES.contains(type)) out.put(name, type);
        }
        return out;
    }

    // ── Karakter yardımcıları ─────────────────────────────────────────────────────────────────────────────────

    static boolean isIdentChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    static boolean isValidName(String s, int from, int to) {
        int len = to - from;
        if (len < 1 || len > MAX_NAME_CHARS) return false;
        char f = s.charAt(from);
        if (!(asciiLetter(f) || f == '_')) return false;
        for (int i = from + 1; i < to; i++) {
            char c = s.charAt(i);
            if (!(asciiLetter(c) || (c >= '0' && c <= '9') || c == '_')) return false;
        }
        return true;
    }

    private static boolean asciiLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private static int lineEnd(String s, int i) {
        int n = s.length();
        while (i < n && s.charAt(i) != '\n' && s.charAt(i) != '\r') i++;
        return i;
    }

    /** İç içe blok yorum (PostgreSQL); kapanmazsa metnin sonu. */
    private static int blockCommentEnd(String s, int start) {
        int n = s.length();
        int depth = 0;
        int i = start;
        while (i < n) {
            char c = s.charAt(i);
            char c1 = i + 1 < n ? s.charAt(i + 1) : 0;
            if (c == '/' && c1 == '*') { depth++; i += 2; continue; }
            if (c == '*' && c1 == '/') { depth--; i += 2; if (depth == 0) return i; continue; }
            i++;
        }
        return n;
    }

    /** Kapanış karakterinin ikilenmesi kaçıştır; {@code backslash} → ters bölü sonraki karakteri kaçırır. Kapanmazsa sonu. */
    private static int quotedEnd(String s, int from, char close, boolean backslash) {
        int n = s.length();
        int i = from;
        while (i < n) {
            char c = s.charAt(i);
            if (backslash && c == '\\') { i += 2; continue; }
            if (c == close) {
                if (i + 1 < n && s.charAt(i + 1) == close) { i += 2; continue; }
                return i + 1;
            }
            i++;
        }
        return n;
    }

    /** {@code $$} ya da {@code $etiket$} açılışı: kapanış {@code $} dizini, değilse -1. */
    private static int dollarTagEnd(String s, int i) {
        int n = s.length();
        int j = i + 1;
        if (j < n && s.charAt(j) == '$') return j;
        if (j >= n || !(Character.isLetter(s.charAt(j)) || s.charAt(j) == '_')) return -1;
        while (j < n && (Character.isLetterOrDigit(s.charAt(j)) || s.charAt(j) == '_')) j++;
        return j < n && s.charAt(j) == '$' ? j : -1;
    }
}
