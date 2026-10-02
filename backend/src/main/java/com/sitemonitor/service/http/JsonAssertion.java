package com.sitemonitor.service.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * HTTP izlemesinin JSON yanıt doğrulaması (2026-10-01, onaylı öneri 9) — yeni bağımlılık YOK, Jackson ağacı +
 * küçük bir yol çözücü.
 *
 * <p><b>Yol sözdizimi</b> (JSONPath'in kasıtlı olarak dar bir alt kümesi — joker, süzgeç, dilim YOK):
 * <ul>
 *   <li>{@code $.a.b[0].c} ya da başta {@code $} olmadan {@code a.b[0].c}</li>
 *   <li>{@code [n]} — sıfır tabanlı dizi indeksi</li>
 *   <li>{@code ['ad.nokta']} / {@code ["ad"]} — nokta ya da boşluk içeren alan adı</li>
 *   <li>yalnız {@code $} — kök düğüm</li>
 * </ul>
 *
 * <p><b>Karar.</b> Beklenen değer boşsa yol VAR ve {@code null} DEĞİL olmalı; doluysa düğümün metin değeri (sayı ve
 * boolean {@code asText} ile; nesne/dizi sıkıştırılmış JSON olarak) beklenen metinle BİREBİR eşit olmalı.
 * Başarısızlık mesajı Türkçedir (kontrol kaydına yazılır — sistem dili, istek dili değil).
 */
public final class JsonAssertion {

    private JsonAssertion() {}

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Mesajda gösterilen değer tavanı (kontrol kaydı ve e-posta şişmesin). */
    static final int SHOW_MAX = 100;

    /** Hata mesajlarının ortak öneki — kart/geçmiş bu önekle "JSON doğrulaması" nedenini tanır. */
    public static final String FAIL_PREFIX = "JSON doğrulaması başarısız: ";

    /** Sözdizimi hatası — konum + iki dilde neden (kayıt anında 400 iletisi bundan kurulur). */
    public static final class JsonPathSyntaxException extends IllegalArgumentException {
        private final int position;
        private final String reasonTr;
        private final String reasonEn;

        JsonPathSyntaxException(int position, String reasonTr, String reasonEn) {
            super(reasonTr + " (" + (position + 1) + ". karakter)");
            this.position = position;
            this.reasonTr = reasonTr;
            this.reasonEn = reasonEn;
        }

        /** Sıfır tabanlı konum. */
        public int position() { return position; }
        public String reasonTr() { return reasonTr; }
        public String reasonEn() { return reasonEn; }
    }

    /** Yol parçası: alan adı ({@link String}) ya da dizi indeksi ({@link Integer}). */
    public static List<Object> parse(String path) {
        if (path == null) throw new JsonPathSyntaxException(0, "yol boş", "path is empty");
        String s = path.trim();
        if (s.isEmpty()) throw new JsonPathSyntaxException(0, "yol boş", "path is empty");
        List<Object> segs = new ArrayList<>();
        int i = 0;
        int n = s.length();
        boolean rooted = s.charAt(0) == '$';
        if (rooted) i = 1;
        boolean first = true;
        while (i < n) {
            char c = s.charAt(i);
            if (c == '.') {
                if (first && !rooted) {
                    throw new JsonPathSyntaxException(i, "yol $ ya da bir alan adıyla başlamalı",
                            "the path must start with $ or a field name");
                }
                i++;
                int start = i;
                while (i < n && isNameChar(s.charAt(i))) i++;
                if (i == start) {
                    throw new JsonPathSyntaxException(start, "noktadan sonra alan adı bekleniyor",
                            "a field name is expected after the dot");
                }
                segs.add(s.substring(start, i));
            } else if (c == '[') {
                i++;
                if (i >= n) throw new JsonPathSyntaxException(i, "köşeli parantez kapanmamış", "unclosed bracket");
                char q = s.charAt(i);
                if (q == '\'' || q == '"') {
                    int start = ++i;
                    while (i < n && s.charAt(i) != q) i++;
                    if (i >= n) throw new JsonPathSyntaxException(start - 1, "tırnak kapanmamış", "unclosed quote");
                    if (i == start) throw new JsonPathSyntaxException(start, "tırnak içindeki alan adı boş",
                            "the quoted field name is empty");
                    String name = s.substring(start, i);
                    i++;   // kapanış tırnağı
                    if (i >= n || s.charAt(i) != ']') {
                        throw new JsonPathSyntaxException(i, "']' bekleniyor", "']' is expected");
                    }
                    i++;
                    segs.add(name);
                } else {
                    int start = i;
                    while (i < n && Character.isDigit(s.charAt(i))) i++;
                    if (i == start) {
                        throw new JsonPathSyntaxException(start, "dizi indeksi sıfır ya da pozitif bir tam sayı olmalı",
                                "the array index must be zero or a positive whole number");
                    }
                    if (i - start > 9) {
                        throw new JsonPathSyntaxException(start, "dizi indeksi çok büyük", "the array index is too large");
                    }
                    if (i >= n || s.charAt(i) != ']') {
                        throw new JsonPathSyntaxException(i, "']' bekleniyor", "']' is expected");
                    }
                    segs.add(Integer.parseInt(s.substring(start, i)));
                    i++;
                }
            } else if (first && !rooted && isNameChar(c)) {
                int start = i;
                while (i < n && isNameChar(s.charAt(i))) i++;
                segs.add(s.substring(start, i));
            } else {
                throw new JsonPathSyntaxException(i, "beklenmeyen karakter '" + c + "'",
                        "unexpected character '" + c + "'");
            }
            first = false;
        }
        return segs;
    }

    /** Çıplak alan adında izin verilen karakter: ayırıcılar, tırnak ve boşluk dışındaki her şey. */
    private static boolean isNameChar(char c) {
        return c != '.' && c != '[' && c != ']' && c != '\'' && c != '"' && c != '$' && !Character.isWhitespace(c)
                && !Character.isISOControl(c);
    }

    /** Yolu ağaçta çözer; yol yoksa {@code null} (JSON {@code null} değeri için NullNode döner). */
    public static JsonNode resolve(JsonNode root, List<Object> segs) {
        JsonNode cur = root;
        for (Object seg : segs) {
            if (cur == null || cur.isMissingNode()) return null;
            if (seg instanceof Integer idx) {
                if (!cur.isArray() || idx >= cur.size()) return null;
                cur = cur.get(idx);
            } else {
                if (!cur.isObject() || !cur.has((String) seg)) return null;
                cur = cur.get((String) seg);
            }
        }
        return cur == null || cur.isMissingNode() ? null : cur;
    }

    /**
     * Gövdeyi doğrular.
     *
     * @param body      okunan yanıt gövdesi (null = okunamadı)
     * @param truncated gövde okuma tavanını aştı mı
     * @param path      kayıtta doğrulanmış yol
     * @param expected  beklenen metin; boş/null = "var ve null değil"
     * @return {@code null} → BAŞARILI; aksi hâlde kontrol kaydına yazılacak Türkçe neden
     */
    public static String evaluate(byte[] body, boolean truncated, String path, String expected) {
        String shownPath = path == null ? "" : path.trim();
        List<Object> segs;
        try {
            segs = parse(path);
        } catch (JsonPathSyntaxException e) {
            return FAIL_PREFIX + "geçersiz JSON yolu " + shownPath + " — " + e.getMessage();
        }
        if (truncated) {
            return FAIL_PREFIX + "yanıt gövdesi okuma sınırını aşıyor (" + (HttpRequestRules.MAX_RESPONSE_BYTES / 1_000_000)
                    + " MB) — JSON ayrıştırılmadı";
        }
        if (body == null || body.length == 0) return FAIL_PREFIX + "yanıt gövdesi boş";
        JsonNode root;
        try {
            root = JSON.readTree(body);
        } catch (Exception e) {
            return FAIL_PREFIX + "yanıt gövdesi geçerli JSON değil";
        }
        if (root == null || root.isMissingNode()) return FAIL_PREFIX + "yanıt gövdesi boş";
        JsonNode node = resolve(root, segs);
        if (node == null) return FAIL_PREFIX + shownPath + " bulunamadı";
        boolean wantsValue = expected != null && !expected.isEmpty();
        if (!wantsValue) {
            return node.isNull() ? FAIL_PREFIX + shownPath + " değeri null" : null;
        }
        String actual = textOf(node);
        if (actual.equals(expected)) return null;
        return FAIL_PREFIX + shownPath + " = \"" + clip(actual) + "\" (beklenen \"" + clip(expected) + "\")";
    }

    /** Karşılaştırılan metin: değer düğümünde asText (sayı/boolean/null dahil), nesne/dizide sıkıştırılmış JSON. */
    static String textOf(JsonNode node) {
        return node.isValueNode() ? node.asText() : node.toString();
    }

    private static String clip(String s) {
        if (s == null) return "";
        String one = s.replaceAll("[\\r\\n\\t]+", " ");
        return one.length() > SHOW_MAX ? one.substring(0, SHOW_MAX) + "…" : one;
    }
}
