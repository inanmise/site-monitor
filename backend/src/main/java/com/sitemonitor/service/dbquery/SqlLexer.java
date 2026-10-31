package com.sitemonitor.service.dbquery;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Doğrusal, lehçe-bilinçli SQL sözcük çözümleyici (2026-10-31, sorgu güvenlik denetimi {@link DbQuerySqlGuard}).
 *
 * <p>Tek geçişte yorumları, tırnaklı metni ve tırnaklı tanımlayıcıları ayırır; geri kalan her şeyi sözcük / sayı / simge
 * belirtecine böler. Amaç, veritabanının "kod" saydığı her karakteri bizim de kod saymamız: lehçe farkları bu yüzden
 * birebir uygulanır —
 * <ul>
 *   <li>İç içe blok yorum: PostgreSQL ve MSSQL'de var, Oracle'da YOK.</li>
 *   <li>PostgreSQL: {@code E'…'} (ters bölü kaçışlı), {@code U&'…'} / {@code U&"…"}, dolar tırnağı {@code $etiket$…$etiket$}.
 *       Düz {@code '…'} metinde ters bölü kaçış DEĞİLDİR — çalıştırıcı oturumda {@code standard_conforming_strings = on}
 *       kurar, yani veritabanı da aynı okur.</li>
 *   <li>Oracle: {@code q'[…]'} / {@code Nq'…'} alternatif tırnak; {@code @} (veritabanı bağlantısı) bir simgedir.</li>
 *   <li>MSSQL: {@code […]} tanımlayıcı ({@code ]]} kaçışı); {@code @} ve {@code #} sözcük karakteridir.</li>
 * </ul>
 * Kapanmayan tırnak / yorum ve görünmez denetim / biçim karakterleri (sağdan sola çevirme, sıfır genişlikli boşluk …
 * "Trojan source") hata olarak döner. Ayrıca ayrıştırıcıya (JSqlParser) verilecek NÖTR metni üretir: aynı uzunlukta,
 * yorumlar boşluk, sekme / Unicode boşluk tek boşluk, lehçeye özgü metin türleri düz {@code 'xx…'} — böylece AST
 * ayrıştırıcısı bizim sözcük çözümlememizle ÇELİŞEMEZ ve konumlar birebir eşleşir.
 *
 * <p>Sınırlı: her döngü dizini ilerletir; arama yalnız {@code String.indexOf} (doğrusal). Düzenli ifade kullanılmaz.
 */
final class SqlLexer {

    enum Kind { WORD, QUOTED, STRING, NUMBER, SYMBOL }

    /**
     * @param text    kaynaktaki ham metin (tırnaklı tanımlayıcıda tırnaklar dahil)
     * @param upper   WORD için büyük harf ({@link Locale#ROOT}); QUOTED için tırnaksız içerik büyük harf; diğerleri null
     * @param start   kaynak dizini (dahil), {@code end} hariç
     */
    record Token(Kind kind, String text, String upper, int start, int end) {
        boolean isWord(String u) { return kind == Kind.WORD && u.equals(upper); }
        boolean isSymbol(char c) { return kind == Kind.SYMBOL && text.length() == 1 && text.charAt(0) == c; }
        boolean isName() { return kind == Kind.WORD || kind == Kind.QUOTED; }
    }

    /**
     * @param error       null = başarılı; aksi halde SQL_CONTROL_CHAR | SQL_UNTERMINATED
     * @param errorDetail SQL_UNTERMINATED için ne kapanmadı: string | comment | identifier
     * @param errorPos    hatanın kaynak dizini
     * @param maxDepth    en derin parantez düzeyi
     * @param neutral     ayrıştırıcıya gidecek nötr metin (kaynakla AYNI uzunlukta)
     */
    record Lexed(List<Token> tokens, String error, String errorDetail, int errorPos, int maxDepth, char[] neutral,
                 int[] lineStarts) {
        int line(int pos) { return SqlLexer.lineOf(lineStarts, pos); }
        int column(int pos) { return pos - lineStarts[line(pos) - 1] + 1; }
    }

    private SqlLexer() {}

    static Lexed lex(String s, DbType type) {
        int n = s.length();
        int[] lineStarts = lineStarts(s);
        char[] neutral = s.toCharArray();
        List<Token> out = new ArrayList<>();
        boolean nestedComments = type != DbType.ORACLE;
        int depth = 0, maxDepth = 0;
        int i = 0;
        while (i < n) {
            char c = s.charAt(i);
            if (c == '\n' || c == '\r') { i++; continue; }
            if (isSpace(c)) { neutral[i] = ' '; i++; continue; }
            if (isControlOrFormat(c)) return fail(out, "SQL_CONTROL_CHAR", null, i, maxDepth, neutral, lineStarts);
            char c1 = i + 1 < n ? s.charAt(i + 1) : 0;
            char c2 = i + 2 < n ? s.charAt(i + 2) : 0;

            // ── yorumlar ─────────────────────────────────────────────
            if (c == '-' && c1 == '-') {
                int j = i;
                while (j < n && s.charAt(j) != '\n' && s.charAt(j) != '\r') {
                    if (isControlOrFormat(s.charAt(j)) && s.charAt(j) != '\t')
                        return fail(out, "SQL_CONTROL_CHAR", null, j, maxDepth, neutral, lineStarts);
                    neutral[j] = ' ';
                    j++;
                }
                i = j;
                continue;
            }
            if (c == '/' && c1 == '*') {
                int end = blockCommentEnd(s, i, nestedComments);
                if (end < 0) return fail(out, "SQL_UNTERMINATED", "comment", i, maxDepth, neutral, lineStarts);
                for (int j = i; j < end; j++) {
                    char cj = s.charAt(j);
                    if (cj == '\n' || cj == '\r') continue;
                    if (isControlOrFormat(cj) && cj != '\t')
                        return fail(out, "SQL_CONTROL_CHAR", null, j, maxDepth, neutral, lineStarts);
                    neutral[j] = ' ';
                }
                i = end;
                continue;
            }

            // ── metin (string) türleri ───────────────────────────────
            int strEnd = -2;          // -2 = metin değil; -1 = kapanmadı
            boolean exotic = false;   // nötr metinde düz 'xx…' yapılır
            if (c == '\'') {
                strEnd = quotedEnd(s, i + 1, '\'', false);
            } else if ((c == 'N' || c == 'n') && c1 == '\'') {
                strEnd = quotedEnd(s, i + 2, '\'', false);
                exotic = true;
            } else if (type == DbType.POSTGRESQL && (c == 'E' || c == 'e') && c1 == '\'') {
                strEnd = quotedEnd(s, i + 2, '\'', true);
                exotic = true;
            } else if (type == DbType.POSTGRESQL && (c == 'B' || c == 'b' || c == 'X' || c == 'x') && c1 == '\'') {
                strEnd = quotedEnd(s, i + 2, '\'', false);
                exotic = true;
            } else if (type == DbType.POSTGRESQL && (c == 'U' || c == 'u') && c1 == '&' && c2 == '\'') {
                strEnd = quotedEnd(s, i + 3, '\'', false);
                exotic = true;
            } else if (type == DbType.ORACLE && (c == 'q' || c == 'Q') && c1 == '\'') {
                strEnd = oracleQEnd(s, i + 2);
                exotic = true;
            } else if (type == DbType.ORACLE && (c == 'N' || c == 'n') && (c1 == 'q' || c1 == 'Q') && c2 == '\'') {
                strEnd = oracleQEnd(s, i + 3);
                exotic = true;
            } else if (type == DbType.POSTGRESQL && c == '$') {
                int tagEnd = dollarTagEnd(s, i);
                if (tagEnd > 0) {
                    String tag = s.substring(i, tagEnd + 1);
                    int close = s.indexOf(tag, tagEnd + 1);
                    strEnd = close < 0 ? -1 : close + tag.length();
                    exotic = true;
                }
            }
            if (strEnd == -1) return fail(out, "SQL_UNTERMINATED", "string", i, maxDepth, neutral, lineStarts);
            if (strEnd >= 0) {
                for (int j = i; j < strEnd; j++) {
                    if (isControlOrFormat(s.charAt(j)) && !isAllowedInString(s.charAt(j)))
                        return fail(out, "SQL_CONTROL_CHAR", null, j, maxDepth, neutral, lineStarts);
                }
                if (exotic) neutralizeString(neutral, s, i, strEnd);
                out.add(new Token(Kind.STRING, s.substring(i, strEnd), null, i, strEnd));
                i = strEnd;
                continue;
            }

            // ── tırnaklı tanımlayıcılar ──────────────────────────────
            int qStart = -1, qContent = -1;
            char qClose = 0;
            if (c == '"') { qStart = i; qContent = i + 1; qClose = '"'; }
            else if (type == DbType.MSSQL && c == '[') { qStart = i; qContent = i + 1; qClose = ']'; }
            else if (type == DbType.POSTGRESQL && (c == 'U' || c == 'u') && c1 == '&' && c2 == '"') {
                qStart = i; qContent = i + 3; qClose = '"';
            }
            if (qStart >= 0) {
                int end = quotedEnd(s, qContent, qClose, false);
                if (end < 0) return fail(out, "SQL_UNTERMINATED", "identifier", i, maxDepth, neutral, lineStarts);
                for (int j = i; j < end; j++) {
                    if (isControlOrFormat(s.charAt(j)) && !isAllowedInString(s.charAt(j)))
                        return fail(out, "SQL_CONTROL_CHAR", null, j, maxDepth, neutral, lineStarts);
                }
                String inner = s.substring(qContent, end - 1);
                String dbl = String.valueOf(qClose) + qClose;
                inner = inner.replace(dbl, String.valueOf(qClose));
                if (qContent == i + 3) {   // U&"…" → ayrıştırıcı düz "…" görsün (aynı uzunluk)
                    neutral[i] = ' ';
                    neutral[i + 1] = ' ';
                }
                out.add(new Token(Kind.QUOTED, s.substring(i, end), inner.toUpperCase(Locale.ROOT), i, end));
                i = end;
                continue;
            }

            // ── sözcük ───────────────────────────────────────────────
            if (isWordStart(c, type)) {
                int j = i + 1;
                while (j < n && isWordPart(s.charAt(j), type)) j++;
                String w = s.substring(i, j);
                out.add(new Token(Kind.WORD, w, w.toUpperCase(Locale.ROOT), i, j));
                i = j;
                continue;
            }
            // ── sayı ─────────────────────────────────────────────────
            if (isDigit(c) || (c == '.' && isDigit(c1))) {
                int j = i;
                while (j < n && (isDigit(s.charAt(j)) || s.charAt(j) == '.')) j++;
                if (j < n && (s.charAt(j) == 'e' || s.charAt(j) == 'E')) {
                    int k = j + 1;
                    if (k < n && (s.charAt(k) == '+' || s.charAt(k) == '-')) k++;
                    if (k < n && isDigit(s.charAt(k))) {
                        j = k;
                        while (j < n && isDigit(s.charAt(j))) j++;
                    }
                }
                out.add(new Token(Kind.NUMBER, s.substring(i, j), null, i, j));
                i = j;
                continue;
            }
            // ── simge ────────────────────────────────────────────────
            if (c == '(') { depth++; if (depth > maxDepth) maxDepth = depth; }
            else if (c == ')') { if (depth > 0) depth--; }
            if (Character.isHighSurrogate(c) && Character.isLowSurrogate(c1)) {
                out.add(new Token(Kind.SYMBOL, s.substring(i, i + 2), null, i, i + 2));
                i += 2;
                continue;
            }
            out.add(new Token(Kind.SYMBOL, String.valueOf(c), null, i, i + 1));
            i++;
        }
        return new Lexed(List.copyOf(out), null, null, -1, maxDepth, neutral, lineStarts);
    }

    private static Lexed fail(List<Token> tokens, String code, String detail, int pos, int maxDepth, char[] neutral,
                              int[] lineStarts) {
        return new Lexed(List.copyOf(tokens), code, detail, pos, maxDepth, neutral, lineStarts);
    }

    /** Blok yorumun bittiği dizin (kapanış dahil, hariç uç); kapanmazsa -1. */
    private static int blockCommentEnd(String s, int start, boolean nested) {
        int n = s.length();
        int depth = 0;
        int i = start;
        while (i < n) {
            char c = s.charAt(i);
            char c1 = i + 1 < n ? s.charAt(i + 1) : 0;
            if (c == '/' && c1 == '*') {
                if (depth == 0 || nested) depth++;
                i += 2;
                continue;
            }
            if (c == '*' && c1 == '/') {
                depth--;
                i += 2;
                if (depth == 0) return i;
                continue;
            }
            i++;
        }
        return -1;
    }

    /**
     * {@code from} içerik başlangıcı; kapanış karakterinin İKİLENMESİ kaçıştır ({@code ''}, {@code ""}, {@code ]]}).
     * {@code backslash} = ters bölü bir sonraki karakteri kaçırır (PostgreSQL {@code E'…'}). Döner: kapanıştan sonraki
     * dizin; kapanmazsa -1.
     */
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
        return -1;
    }

    /** Oracle {@code q'X…X'} — {@code from} sınırlayıcının dizini. [ ( { < eşleşen kapanışla, diğerleri kendisiyle. */
    private static int oracleQEnd(String s, int from) {
        if (from >= s.length()) return -1;
        char open = s.charAt(from);
        if (isSpace(open) || open == '\n' || open == '\r') return -1;   // Oracle boşluk sınırlayıcıya izin vermez
        char close = switch (open) {
            case '[' -> ']';
            case '(' -> ')';
            case '{' -> '}';
            case '<' -> '>';
            default -> open;
        };
        int idx = s.indexOf(String.valueOf(close) + '\'', from + 1);
        return idx < 0 ? -1 : idx + 2;
    }

    /** PostgreSQL dolar tırnağı açılışı: {@code $$} ya da {@code $etiket$}; etiketin kapanış {@code $} dizini, değilse -1. */
    private static int dollarTagEnd(String s, int i) {
        int n = s.length();
        int j = i + 1;
        if (j < n && s.charAt(j) == '$') return j;
        if (j >= n || !(Character.isLetter(s.charAt(j)) || s.charAt(j) == '_')) return -1;
        while (j < n && (Character.isLetterOrDigit(s.charAt(j)) || s.charAt(j) == '_')) j++;
        return j < n && s.charAt(j) == '$' ? j : -1;
    }

    /** Lehçeye özgü metni ayrıştırıcı için düz {@code 'xx…'} yapar (satır sonları korunur → satır numaraları kaymaz). */
    private static void neutralizeString(char[] neutral, String s, int start, int end) {
        neutral[start] = '\'';
        neutral[end - 1] = '\'';
        for (int j = start + 1; j < end - 1; j++) {
            char c = s.charAt(j);
            neutral[j] = (c == '\n' || c == '\r') ? c : 'x';
        }
    }

    static boolean isDigit(char c) { return c >= '0' && c <= '9'; }

    /** Satır sonu dışı her boşluk (sekme, NBSP, U+2007, U+202F, diğer Zs). */
    static boolean isSpace(char c) {
        if (c == '\n' || c == '\r' || c == ' ' || c == ' ') return false;
        return c == ' ' || c == '\t' || Character.isSpaceChar(c) || (Character.isWhitespace(c) && !isControlOrFormat(c));
    }

    /** Görünmez denetim (C0 / C1, DEL, U+2028 / U+2029) ya da biçim karakteri (U+200B, U+200E/F, U+202A–E, U+2066–9, U+FEFF …). */
    static boolean isControlOrFormat(char c) {
        if (c == '\t' || c == '\n' || c == '\r') return false;
        if (c < 0x20 || c == 0x7F || (c >= 0x80 && c <= 0x9F)) return true;
        // Satır / paragraf ayırıcı: bir veritabanı bunu satır sonu sayarsa "--" yorumunun arkasına kod saklanabilirdi
        if (c == '\u2028' || c == '\u2029') return true;
        return Character.getType(c) == Character.FORMAT;
    }

    /** Metin içinde serbest olan denetim karakterleri: sekme ve satır sonu. */
    private static boolean isAllowedInString(char c) { return c == '\t' || c == '\n' || c == '\r'; }

    private static boolean isWordStart(char c, DbType type) {
        if (Character.isLetter(c) || c == '_') return true;
        return type == DbType.MSSQL && (c == '@' || c == '#');
    }

    private static boolean isWordPart(char c, DbType type) {
        if (Character.isLetterOrDigit(c) || c == '_' || c == '$') return true;
        if (c == '#') return type != DbType.POSTGRESQL;
        return c == '@' && type == DbType.MSSQL;
    }

    static int[] lineStarts(String s) {
        int[] buf = new int[16];
        int count = 0;
        buf[count++] = 0;
        int n = s.length();
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            int next = -1;
            if (c == '\n') next = i + 1;
            else if (c == '\r') next = (i + 1 < n && s.charAt(i + 1) == '\n') ? -1 : i + 1;
            if (next >= 0) {
                if (count == buf.length) buf = Arrays.copyOf(buf, count * 2);
                buf[count++] = next;
            }
        }
        return Arrays.copyOf(buf, count);
    }

    /** 1 tabanlı satır (ikili arama). */
    static int lineOf(int[] starts, int pos) {
        int lo = 0, hi = starts.length - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (starts[mid] <= pos) lo = mid; else hi = mid - 1;
        }
        return lo + 1;
    }
}
