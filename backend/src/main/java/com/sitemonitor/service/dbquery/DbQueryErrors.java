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
        for (int depth = 0; t != null && depth < CauseChain.MAX_DEPTH; depth++, t = t.getCause()) {
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
        for (int depth = 0; t != null && depth < CauseChain.MAX_DEPTH; depth++, t = t.getCause()) {
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
