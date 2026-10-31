package com.sitemonitor.service.dbquery;

import com.sitemonitor.service.SecretMask;
import com.sitemonitor.service.SsrfGuard;
import com.sitemonitor.util.CauseChain;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.PortUnreachableException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Sürücü hatalarının sınıflandırılması ve maskelenmesi (2026-10-31, sözleşme §2b). Kodlar kalıcıdır (kontrol satırı
 * {@code failure_reason}, alarm ayrıntısı, toplu kesinti kuralı): {@link #DB_UNREACHABLE}, {@link #DB_DNS},
 * {@link #DB_TIMEOUT} AĞ sınıfıdır; {@link #DB_AUTH}, {@link #DB_SQL_ERROR}, {@link #DB_DRIVER}, {@link #DB_BLOCKED}
 * yapılandırma / sorgu sınıfıdır.
 *
 * <p>Sınıflandırma neden zincirinin TAMAMINA bakar ({@link CauseChain#MAX_DEPTH} halka) ve en güçlü sinyali seçer:
 * DNS / engel &gt; zaman aşımı &gt; kimlik &gt; bağlantı &gt; SQL &gt; sürücü. SQLState sınıfları ve sürücüye özgü hata kodları
 * (MSSQL 18456 oturum açma, 1222 kilit zaman aşımı; Oracle ORA-01017, ORA-01013, ORA-125xx) birlikte kullanılır.
 */
final class DbQueryErrors {

    static final String DB_UNREACHABLE = "DB_UNREACHABLE", DB_DNS = "DB_DNS", DB_TIMEOUT = "DB_TIMEOUT",
            DB_AUTH = "DB_AUTH", DB_SQL_ERROR = "DB_SQL_ERROR", DB_DRIVER = "DB_DRIVER", DB_BLOCKED = "DB_BLOCKED";

    /** Ağ sınıfı kodlar (toplu kesinti / bastırma kuralı için). */
    static final Set<String> NETWORK_CLASS = Set.of(DB_UNREACHABLE, DB_DNS, DB_TIMEOUT);

    static final int MAX_ERROR_CHARS = 500;

    enum Phase { CONNECT, QUERY }

    private DbQueryErrors() {}

    static String classify(Throwable e, Phase phase, boolean watchdogFired, DbType type) {
        if (watchdogFired) return DB_TIMEOUT;
        int best = 0;   // 0 sürücü, 1 sql, 2 bağlantı, 3 kimlik, 4 zaman aşımı, 5 dns / engel
        String bestCode = DB_DRIVER;
        boolean sawSql = false;
        Throwable t = e;
        for (int causeDepth = 0; t != null && causeDepth < CauseChain.MAX_DEPTH; causeDepth++, t = t.getCause()) {
            String code = null;
            int rank = 0;
            if (t instanceof SsrfGuard.UnresolvableHostException || t instanceof UnknownHostException) { code = DB_DNS; rank = 5; }
            else if (t instanceof SsrfGuard.BlockedException) { code = DB_BLOCKED; rank = 5; }
            else if (t instanceof SQLTimeoutException) { code = DB_TIMEOUT; rank = 4; }
            else if (t instanceof SocketTimeoutException) {
                if (phase == Phase.QUERY) { code = DB_TIMEOUT; rank = 4; } else { code = DB_UNREACHABLE; rank = 2; }
            } else if (t instanceof ConnectException || t instanceof NoRouteToHostException
                    || t instanceof PortUnreachableException || t instanceof javax.net.ssl.SSLException) {
                code = DB_UNREACHABLE; rank = 2;
            } else if (t instanceof SQLException se) {
                sawSql = true;
                String[] c = sqlCode(se, type);
                if (c != null) { code = c[0]; rank = Integer.parseInt(c[1]); }
            }
            if (code != null && rank > best) { best = rank; bestCode = code; }
            if (t.getCause() == t) break;
        }
        if (best > 0) return bestCode;
        if (sawSql) return phase == Phase.CONNECT ? DB_UNREACHABLE : DB_SQL_ERROR;
        return DB_DRIVER;
    }

    /** [kod, sıra] ya da bilinmiyorsa null. */
    private static String[] sqlCode(SQLException se, DbType type) {
        String state = se.getSQLState() == null ? "" : se.getSQLState().toUpperCase(Locale.ROOT);
        int vendor = se.getErrorCode();
        String cls = se.getClass().getName().toLowerCase(Locale.ROOT);
        // Satıcı kodları türe göre yorumlanır (Oracle ince sürücüsü düz java.sql.SQLException fırlatabilir)
        boolean mssql = type == DbType.MSSQL || cls.contains("sqlserver");
        boolean oracle = type == DbType.ORACLE || cls.contains("oracle");
        if (state.equals("57014") || state.equals("HY008") || state.equals("HYT00") || state.equals("HYT01")
                || state.equals("55P03")) return new String[] {DB_TIMEOUT, "4"};
        if (mssql && (vendor == 1222)) return new String[] {DB_TIMEOUT, "4"};
        if (oracle && (vendor == 1013 || vendor == 30006 || vendor == 51)) return new String[] {DB_TIMEOUT, "4"};
        if (state.startsWith("28")) return new String[] {DB_AUTH, "3"};
        if (mssql && (vendor == 18456 || vendor == 18452 || vendor == 18486 || vendor == 18487 || vendor == 18488
                || vendor == 4060)) return new String[] {DB_AUTH, "3"};
        if (oracle && (vendor == 1017 || vendor == 28000 || vendor == 28001 || vendor == 1005 || vendor == 1045))
            return new String[] {DB_AUTH, "3"};
        if (state.startsWith("08")) return new String[] {DB_UNREACHABLE, "2"};
        if (oracle && (vendor == 17002 || vendor == 17410 || vendor == 12170 || (vendor >= 12500 && vendor <= 12599)))
            return new String[] {DB_UNREACHABLE, "2"};
        if (!state.isEmpty() && !state.startsWith("08")) return new String[] {DB_SQL_ERROR, "1"};
        return null;
    }

    /** Hata metni: SQL istisnasının kendi iletisi (yoksa sınıf adı); boşluklar sadeleşir. Maskelenmez — {@link #mask}. */
    static String describe(Throwable e) {
        if (e == null) return null;
        Throwable t = e;
        String msg = null;
        for (int causeDepth = 0; t != null && causeDepth < CauseChain.MAX_DEPTH; causeDepth++, t = t.getCause()) {
            String m = t.getMessage();
            if (m != null && !m.isBlank()) { msg = m; break; }
            if (t.getCause() == t) break;
        }
        if (msg == null) msg = e.getClass().getSimpleName();
        return msg;
    }

    /**
     * Maskeleme: parola (kodlama varyantlarıyla), kullanıcı adı (tam sözcük ya da tırnak içinde), {@code password=} /
     * {@code pwd=} değerleri; boşluklar sadeleşir; ≤ {@value #MAX_ERROR_CHARS} karakter. Asla fırlatmaz.
     */
    static String mask(String text, DbConnectionSpec spec) {
        if (text == null) return null;
        // Önce HAM metinde maskele (sadeleştirme parolanın içindeki boşlukları değiştirip eşleşmeyi kaçırmasın), sonra sadeleştir
        String out = text.length() > 20_000 ? text.substring(0, 20_000) : text;
        if (spec != null) {
            List<String> secrets = new ArrayList<>();
            if (spec.password() != null && !spec.password().isEmpty()) secrets.add(spec.password());
            out = SecretMask.maskValues(out, secrets);
            if (spec.username() != null && spec.username().trim().length() >= 2)
                out = replaceWord(out, spec.username().trim(), SecretMask.MASK);
        }
        out = maskKeyValue(out, "password=");
        out = maskKeyValue(out, "pwd=");
        out = collapse(out);
        if (out.length() > MAX_ERROR_CHARS) out = out.substring(0, MAX_ERROR_CHARS - 1) + "…";
        return out;
    }

    // ── Değer sızıntısı temizliği (bağımsız inceleme Y1, 2026-10-31) ─────────────────────────────────────────

    /** Temizlenmiş sürücü iletisinin en çok uzunluğu (SQLSTATE eki hariç) — tırnaksız uzun değer dizilerine karşı. */
    static final int MAX_QUERY_ERROR_CHARS = 300;

    /** İletinin ilk satırında bunlardan sonrası atılır (PG ayrıntı / ipucu / konum satırları, TR ve EN). */
    private static final List<String> DETAIL_LABELS = List.of(" detail:", " hint:", " where:", " position:", " location:",
            " internal query:", " internal position:", " konum:", " ayrıntı:", " ipucu:", " yer:", " bağlam:");

    /** Açılış → kapanış tırnakları ({@code '…'}, {@code "…"}, {@code […]}, tipografik tırnaklar). */
    private static final String QUOTE_OPEN = "'\"[“‘«`", QUOTE_CLOSE = "'\"]”’»`";

    /**
     * Kullanıcının SQL'inden ve bağlantı tanımından izinli belirteçler (BÜYÜK harf): tanımlayıcılar, tırnaklı adlar,
     * metin sabitlerinin içi, sayılar, sunucu / veritabanı / örnek adı. Hata iletisindeki tırnaklı bir parça YALNIZ bunlardan
     * biriyse (ya da noktalı parçaları bunlardansa) korunur — veritabanının kendi sözcüğü / kullanıcının yazdığı ad ya da sabit.
     */
    static Set<String> allowTokens(String sql, DbConnectionSpec spec) {
        Set<String> out = new java.util.HashSet<>();
        if (spec != null) {
            for (String v : new String[] {spec.host(), spec.database(), spec.mssqlInstance(), spec.username()})
                if (v != null && !v.isBlank()) out.add(v.trim().toUpperCase(Locale.ROOT));
        }
        if (sql == null || sql.isEmpty()) return out;
        DbType type = spec == null || spec.type() == null ? DbType.POSTGRESQL : spec.type();
        SqlLexer.Lexed lx = SqlLexer.lex(sql.length() > DbQuerySqlGuard.MAX_SQL_CHARS
                ? sql.substring(0, DbQuerySqlGuard.MAX_SQL_CHARS) : sql, type);
        if (lx.error() != null) {
            // çözümlenemeyen metin: ad karakterlerinden oluşan parçalar
            StringBuilder cur = new StringBuilder();
            for (int i = 0; i <= sql.length() && out.size() < 10_000; i++) {
                char c = i < sql.length() ? sql.charAt(i) : ' ';
                if (Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '#' || c == '@') cur.append(c);
                else if (!cur.isEmpty()) { out.add(cur.toString().toUpperCase(Locale.ROOT)); cur.setLength(0); }
            }
            return out;
        }
        for (SqlLexer.Token t : lx.tokens()) {
            if (out.size() >= 10_000) break;
            switch (t.kind()) {
                case WORD, QUOTED -> out.add(t.upper());
                case NUMBER -> out.add(t.text().toUpperCase(Locale.ROOT));
                case STRING -> out.add(stringContent(t.text()).toUpperCase(Locale.ROOT));
                default -> { }
            }
        }
        return out;
    }

    /** Metin sabitinin içi: {@code N'…'}, {@code E'…'}, {@code q'[…]'}, {@code $etiket$…$etiket$}. */
    private static String stringContent(String text) {
        int a = text.indexOf('\''), b = text.lastIndexOf('\'');
        if (a >= 0 && b > a) {
            String inner = text.substring(a + 1, b);
            if (text.charAt(0) == 'q' || text.charAt(0) == 'Q' || (text.length() > 1 && (text.charAt(1) == 'q' || text.charAt(1) == 'Q')))
                return inner.length() >= 2 ? inner.substring(1, inner.length() - 1) : inner;   // q'[…]' sınırlayıcıları
            return inner.replace("''", "'");
        }
        if (text.startsWith("$")) {
            int e = text.indexOf('$', 1);
            if (e > 0 && text.length() >= 2 * (e + 1)) return text.substring(e + 1, text.length() - e - 1);
        }
        return text;
    }

    /** Değer taşıyan MSSQL hata numaraları: tür dönüşümü, taşma, tarih / saat, JSON ve XML ayrıştırma. */
    private static final Set<Integer> MSSQL_VALUE_ERRORS = Set.of(220, 232, 236, 241, 242, 245, 248, 249, 293, 295, 296,
            517, 8114, 8115, 8116, 8169);

    /** Değer taşıyan Oracle hata numaraları (ORA-…): sayı / tarih dönüşümü, değer çok uzun, JSON / XML ayrıştırma. */
    private static final Set<Integer> ORACLE_VALUE_ERRORS = Set.of(1722, 1830, 1839, 1840, 1841, 1843, 1846, 1847, 1849,
            1850, 1851, 1852, 1853, 1857, 1858, 1861, 1862, 1864, 1866, 1878, 1882, 1898, 1438, 6502, 12899, 31011,
            40441, 40442, 40456, 40597);

    /** Bu uzunluktaki ilk satır değer taşıyor (ya da sürücü iletiyi kesmiş) sayılır — katı kip. */
    static final int LONG_FIRST_LINE = 1_000;

    /**
     * Hata DEĞER taşıyan sınıftan mı (katı kip): SQLSTATE sınıfı {@code 22} (veri hatası — tür dönüşümü, aralık, kodlama,
     * JSON …), MSSQL / Oracle'ın değer taşıyan hata numaraları, MSSQL 9400–9499 (XML ayrıştırma) ve 13600–13699 (JSON).
     */
    static boolean valueBearing(Throwable e, DbType type) {
        Throwable t = e;
        for (int causeDepth = 0; t != null && causeDepth < CauseChain.MAX_DEPTH; causeDepth++, t = t.getCause()) {
            if (t instanceof SQLException se) {
                String st = se.getSQLState();
                if (st != null && st.trim().startsWith("22")) return true;
                int code = se.getErrorCode();
                if (type == DbType.MSSQL && (MSSQL_VALUE_ERRORS.contains(code) || (code >= 9_400 && code <= 9_499)
                        || (code >= 13_600 && code <= 13_699))) return true;
                if (type == DbType.ORACLE && ORACLE_VALUE_ERRORS.contains(code)) return true;
            }
            if (t.getCause() == t) break;
        }
        return false;
    }

    /** {@link #scrub(String, Set, boolean)} katı olmayan kip. */
    static String scrub(String message, Set<String> allow) {
        return scrub(message, allow, false);
    }

    /**
     * Sürücü / veritabanı iletisinden VERİ değerlerini ayıklar — "SELECT sonucu veri çekme aracı olamaz" ilkesi:
     * {@code CAST((SELECT gizli FROM t) AS integer)} gibi bir sorgu değeri hata iletisine gömerek sızdıramaz
     * (PG {@code invalid input syntax for type integer: "DEĞER"}, MSSQL {@code converting the varchar value 'DEĞER'},
     * Oracle {@code ORA-01722 … 'DEĞER'}).
     * <ol>
     *   <li>Yalnız İLK satır kalır; ayrıntı / ipucu / konum etiketlerinden ({@code DETAIL:}, {@code HINT:}, {@code Where:},
     *       {@code Position:}, {@code Konum:} …) sonrası atılır.</li>
     *   <li><b>Katı kip</b> ({@code strict} — değer taşıyan hata sınıfı, bkz. {@link #valueBearing}, ya da çok uzun ilk satır):
     *       ilk tırnaktan (her türden) sonrası atılır, yerine {@code '…'} gelir. Değer kendi içinde tırnak taşıyıp tırnağı
     *       erken kapatsa da, sürücü iletiyi kesip kapanış tırnağını düşürse de ardındaki metin görünmez.</li>
     *   <li>Normal kip: İLK tırnaklı birim ({@code '…'}, {@code "…"}, {@code […]}, tipografik; {@code "A"."B"} zinciri tek
     *       birim) içi {@code allow} belirteçlerinden biriyse (ya da noktalı parçaları bunlardansa) KALIR — tanımlayıcı,
     *       kullanıcının yazdığı ad / sabit; değilse {@code …} olur ve iletinin gerisi atılır. Ardındaki metinde başka bir
     *       tırnak karakteri varsa (değer tırnağı erken kapatıp metin enjekte etmiş olabilir — ya da ikinci bir ad) gerisi
     *       {@code …} olur; yoksa (ör. {@code does not exist}) kalır.</li>
     *   <li>{@code 0x…} bayt dizileri (PG kodlama hataları) {@code 0x…} olur.</li>
     *   <li>≤ {@value #MAX_QUERY_ERROR_CHARS} karakter.</li>
     * </ol>
     * Doğrusal (her tırnak için tek {@code indexOf}); düzenli ifade yok. Asla fırlatmaz.
     */
    static String scrub(String message, Set<String> allow, boolean strict) {
        if (message == null) return null;
        Set<String> ok = allow == null ? Set.of() : allow;
        String s = message;
        int nl = firstLineEnd(s);
        if (nl >= 0) s = s.substring(0, nl);
        if (s.length() >= LONG_FIRST_LINE) strict = true;
        if (s.length() > 20_000) s = s.substring(0, 20_000);
        String lower = s.toLowerCase(Locale.ROOT);
        if (lower.length() == s.length()) {
            int cut = -1;
            for (String label : DETAIL_LABELS) {
                int i = lower.indexOf(label);
                if (i >= 0 && (cut < 0 || i < cut)) cut = i;
            }
            if (cut >= 0) s = s.substring(0, cut);
        }
        StringBuilder sb = new StringBuilder(Math.min(s.length(), MAX_QUERY_ERROR_CHARS + 8));
        int i = 0, n = s.length();
        boolean unitSeen = false;
        while (i < n && sb.length() <= MAX_QUERY_ERROR_CHARS) {
            char c = s.charAt(i);
            int q = QUOTE_OPEN.indexOf(c);
            boolean opens = q >= 0 && (strict || c != '\'' || i == 0 || !Character.isLetterOrDigit(s.charAt(i - 1)));
            if (opens) {
                char close = QUOTE_CLOSE.charAt(q);
                if (strict || unitSeen) {
                    sb.append(c).append('…').append(close);
                    break;
                }
                int end = quotedUnitEnd(s, i, ok);   // birimin bitişi (dahil değil); -1 = tanınmadı
                if (end < 0) {
                    sb.append(c).append('…').append(close);
                    break;
                }
                sb.append(s, i, end);
                i = end;
                unitSeen = true;
                if (hasQuoteChar(s, i)) {
                    sb.append(" …");
                    break;
                }
                continue;
            }
            if (c == '0' && i + 2 < n && (s.charAt(i + 1) == 'x' || s.charAt(i + 1) == 'X') && isHex(s.charAt(i + 2))) {
                int j = i + 2;
                while (j < n && isHex(s.charAt(j))) j++;
                sb.append("0x…");
                i = j;
                continue;
            }
            sb.append(c);
            i++;
        }
        String out = sb.toString().trim();
        if (out.length() > MAX_QUERY_ERROR_CHARS) out = out.substring(0, MAX_QUERY_ERROR_CHARS - 1) + "…";
        return out;
    }

    /**
     * {@code from} konumunda açılan tırnaklı birimin sonu (dahil değil) — {@code "A"."B"} gibi noktayla bağlı tırnaklı
     * parçalar tek birimdir; her parçanın içi izinli olmalı. Tanınmazsa (kapanmıyor, izinsiz parça) -1.
     */
    private static int quotedUnitEnd(String s, int from, Set<String> ok) {
        int i = from;
        for (int parts = 0; parts < 8; parts++) {
            char c = s.charAt(i);
            int q = QUOTE_OPEN.indexOf(c);
            if (q < 0) return -1;
            int j = s.indexOf(QUOTE_CLOSE.charAt(q), i + 1);
            if (j < 0 || !allowedInner(s.substring(i + 1, j), ok)) return -1;
            i = j + 1;
            if (i + 1 < s.length() && s.charAt(i) == '.' && QUOTE_OPEN.indexOf(s.charAt(i + 1)) >= 0) {
                i++;
                continue;
            }
            return i;
        }
        return -1;
    }

    /** {@code from}'dan sonra herhangi bir tırnak karakteri (açılış ya da kapanış) var mı. */
    private static boolean hasQuoteChar(String s, int from) {
        for (int i = from; i < s.length(); i++) {
            char c = s.charAt(i);
            if (QUOTE_OPEN.indexOf(c) >= 0 || QUOTE_CLOSE.indexOf(c) >= 0) return true;
        }
        return false;
    }

    private static int firstLineEnd(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n' || c == '\r' || c == '\u2028' || c == '\u2029') return i;
        }
        return -1;
    }

    private static boolean allowedInner(String inner, Set<String> allow) {
        String t = inner.trim();
        if (t.isEmpty()) return true;
        if (t.length() > 200) return false;
        String u = t.toUpperCase(Locale.ROOT);
        if (allow.contains(u)) return true;
        if (u.indexOf('.') < 0) return false;
        for (String part : u.split("\\.", -1)) {
            String p = part.trim();
            if (p.length() >= 2 && ((p.charAt(0) == '"' && p.endsWith("\"")) || (p.charAt(0) == '[' && p.endsWith("]"))))
                p = p.substring(1, p.length() - 1);
            if (p.isEmpty() || !allow.contains(p)) return false;
        }
        return true;
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    /** Zincirdeki ilk SQLSTATE (ve MSSQL hata numarası) — {@code " (SQLSTATE 22P02)"} ya da boş. */
    static String sqlStateSuffix(Throwable e, DbType type) {
        Throwable t = e;
        for (int causeDepth = 0; t != null && causeDepth < CauseChain.MAX_DEPTH; causeDepth++, t = t.getCause()) {
            if (t instanceof SQLException se && se.getSQLState() != null && !se.getSQLState().isBlank()) {
                String st = se.getSQLState().trim();
                if (st.length() > 10 || !st.chars().allMatch(Character::isLetterOrDigit)) return "";
                boolean vendor = type == DbType.MSSQL && se.getErrorCode() != 0;
                return " (SQLSTATE " + st + (vendor ? " · " + se.getErrorCode() : "") + ")";
            }
            if (t.getCause() == t) break;
        }
        return "";
    }

    /** Satır sonu / sekme / art arda boşluk → tek boşluk (doğrusal). */
    static String collapse(String s) {
        StringBuilder sb = new StringBuilder(Math.min(s.length(), 4_096));
        boolean space = false;
        for (int i = 0; i < s.length() && sb.length() < 4_096; i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c) || Character.isISOControl(c)) {
                if (!space && sb.length() > 0) sb.append(' ');
                space = true;
            } else {
                sb.append(c);
                space = false;
            }
        }
        int end = sb.length();
        while (end > 0 && sb.charAt(end - 1) == ' ') end--;
        return sb.substring(0, end);
    }

    /** {@code word} tam sözcük olarak (ya da tırnak içinde) geçtiği yerlerde maskelenir; büyük-küçük harf duyarsız. */
    static String replaceWord(String text, String word, String mask) {
        String lt = text.toLowerCase(Locale.ROOT), lw = word.toLowerCase(Locale.ROOT);
        if (lw.isEmpty() || lt.length() != text.length()) return text;
        StringBuilder sb = new StringBuilder(text.length());
        int from = 0;
        while (from <= text.length()) {
            int i = lt.indexOf(lw, from);
            if (i < 0) break;
            int end = i + lw.length();
            boolean leftOk = i == 0 || !isWordChar(text.charAt(i - 1));
            boolean rightOk = end >= text.length() || !isWordChar(text.charAt(end));
            sb.append(text, from, i);
            if (leftOk && rightOk) sb.append(mask);
            else sb.append(text, i, end);
            from = end;
        }
        sb.append(text.substring(Math.min(from, text.length())));
        return sb.toString();
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '#' || c == '@';
    }

    /** {@code key=değer} biçimindeki değeri ({@code ;}, {@code &}, boşluk, tırnak, parantez, virgüle dek) maskeler. */
    static String maskKeyValue(String text, String key) {
        String lt = text.toLowerCase(Locale.ROOT);
        if (lt.length() != text.length()) return text;
        StringBuilder sb = new StringBuilder(text.length());
        int from = 0;
        while (true) {
            int i = lt.indexOf(key, from);
            if (i < 0) break;
            int v = i + key.length();
            int j = v;
            while (j < text.length() && ";& \"'(),".indexOf(text.charAt(j)) < 0) j++;
            sb.append(text, from, v);
            if (j > v) sb.append(SecretMask.MASK);
            from = j;
        }
        sb.append(text.substring(from));
        return sb.toString();
    }
}
