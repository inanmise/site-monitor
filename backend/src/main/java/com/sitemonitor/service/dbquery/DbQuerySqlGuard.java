package com.sitemonitor.service.dbquery;

import com.sitemonitor.util.Msg;
import net.sf.jsqlparser.expression.SQLServerHints;
import net.sf.jsqlparser.parser.CCJSqlParser;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.parser.ParseException;
import net.sf.jsqlparser.parser.Token;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.LateralSubSelect;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.TableFunction;
import net.sf.jsqlparser.statement.select.WithItem;
import net.sf.jsqlparser.util.TablesNamesFinder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Sorgu güvenlik denetimi (KATI; sözleşme dbquery-contract.md §2a). Saf, yan etkisiz, sınırlı süreli; kayıtta, denemede ve
 * HER çalıştırmadan önce çağrılır. {@code ok == violations.isEmpty()}.
 *
 * <p>Kullanıcının kesin isteği (2026-10-31): yalnız SELECT; DDL / DML / DCL / TCL yok; kilit yaratan sorgu yok; MSSQL'de
 * her tablo başvurusu {@code WITH (NOLOCK)} taşır; veritabanına zarar verebilecek her şey engellenir. İki katman:
 * <ol>
 *   <li><b>Sözcük katmanı</b> ({@link SqlLexer}, doğrusal): uzunluk, denetim karakteri, kapanmayan tırnak / yorum, tek ifade,
 *       ilk sözcük, yasak sözcükler (metin / yorum / tırnaklı tanımlayıcı DIŞINDA), kilitleme yan tümcesi, {@code INTO},
 *       yasak fonksiyonlar, MSSQL kilit ipuçları, uzak nesneler (Oracle {@code @bağlantı}), JDBC kaçış sözdizimi.
 *       MSSQL'de {@code SELECT 1 DELETE FROM t} TEK ifade gibi ayrıştırılır ({@code DELETE} takma ad sanılır) — bu katman
 *       olmasa AST bunu kaçırırdı.</li>
 *   <li><b>AST katmanı</b> (JSqlParser): ifade {@code Select} olmalı, ayrıştırılamayan sorgu REDDEDİLİR; veri değiştiren CTE,
 *       MSSQL'de her gerçek tablonun NOLOCK'u (CTE adları kapsamına göre muaf, türetilmiş tablolar muaf), MSSQL tablo değerli
 *       fonksiyonları, dört parçalı (bağlı sunucu) adlar; okunan tabloların listesi.</li>
 * </ol>
 * Ayrıştırıcıya sözcük katmanının ürettiği NÖTR metin verilir (yorumlar boşluk, lehçeye özgü metin düz {@code 'x…'}, kabul
 * edilen MSSQL ipucu grupları {@code WITH (NOLOCK)}) — iki katman aynı belirteçleri görür. Ayrıştırma ayrı bir sanal iş
 * parçacığında zaman kutusundadır ({@value #PARSE_TIMEOUT_MS} ms); süre aşımı {@link #TOKEN_TIMEOUT} işaretli
 * {@code SQL_UNPARSABLE} döner ve önbelleğe girmez (çalıştırıcı bunu "şimdilik atla" sayar, alarm açmaz).
 *
 * <p>Bilinçli yumuşatma: {@code COMMENT}, {@code COPY}, {@code ANALYZE}, {@code LOAD}, {@code CLUSTER} … gibi sık kolon adı
 * olan sözcükler yalnız KOMUT biçiminde ({@code COMMENT ON}, {@code COPY t TO}, {@code ANALYZE tablo}) reddedilir; tek ifade +
 * SELECT ile başlama + AST zaten komut olarak kullanılmalarını engeller. Diğer sözcükler (INSERT, DELETE, SET, LOCK …) her
 * yerde reddedilir — kolon adıysa tırnak içinde yazılmalıdır.
 */
public final class DbQuerySqlGuard {

    public static final int MAX_SQL_CHARS = 10_000;

    public static final String SQL_EMPTY = "SQL_EMPTY", SQL_TOO_LONG = "SQL_TOO_LONG",
            SQL_CONTROL_CHAR = "SQL_CONTROL_CHAR", SQL_UNTERMINATED = "SQL_UNTERMINATED",
            SQL_MULTIPLE_STATEMENTS = "SQL_MULTIPLE_STATEMENTS", SQL_NOT_SELECT = "SQL_NOT_SELECT",
            SQL_FORBIDDEN_KEYWORD = "SQL_FORBIDDEN_KEYWORD", SQL_LOCKING_CLAUSE = "SQL_LOCKING_CLAUSE",
            SQL_INTO = "SQL_INTO", SQL_FORBIDDEN_FUNCTION = "SQL_FORBIDDEN_FUNCTION", SQL_LOCK_HINT = "SQL_LOCK_HINT",
            SQL_MSSQL_NOLOCK_REQUIRED = "SQL_MSSQL_NOLOCK_REQUIRED", SQL_UNPARSABLE = "SQL_UNPARSABLE",
            /** Oracle {@code tablo@bağlantı}, MSSQL dört parçalı ad (bağlı sunucu) — uzak okuma denetlenemez. */
            SQL_REMOTE_OBJECT = "SQL_REMOTE_OBJECT";

    /** Tüm kodlar (ön yüz çeviri anahtarları {@code dbq.guard.<CODE>} bununla eşleşir). */
    public static final List<String> CODES = List.of(SQL_EMPTY, SQL_TOO_LONG, SQL_CONTROL_CHAR, SQL_UNTERMINATED,
            SQL_MULTIPLE_STATEMENTS, SQL_NOT_SELECT, SQL_FORBIDDEN_KEYWORD, SQL_LOCKING_CLAUSE, SQL_INTO,
            SQL_FORBIDDEN_FUNCTION, SQL_LOCK_HINT, SQL_MSSQL_NOLOCK_REQUIRED, SQL_UNPARSABLE, SQL_REMOTE_OBJECT);

    /** {@code SQL_UNPARSABLE} belirteci: ayrıştırma zaman / izin sınırına takıldı (sorgu kötü değil — "şimdilik atla"). */
    public static final String TOKEN_TIMEOUT = "TIMEOUT";

    static final int MAX_DEPTH = 40;
    static final int MAX_VIOLATIONS = 25;
    static final int MAX_TABLES = 100;
    static final long PARSE_TIMEOUT_MS = 3_000;
    static final int CACHE_SIZE = 256;

    /**
     * @param code    kalıcı kod (SQL_EMPTY, SQL_TOO_LONG, SQL_CONTROL_CHAR, SQL_UNTERMINATED, SQL_MULTIPLE_STATEMENTS,
     *                SQL_NOT_SELECT, SQL_FORBIDDEN_KEYWORD, SQL_LOCKING_CLAUSE, SQL_INTO, SQL_FORBIDDEN_FUNCTION,
     *                SQL_LOCK_HINT, SQL_MSSQL_NOLOCK_REQUIRED, SQL_UNPARSABLE, SQL_REMOTE_OBJECT)
     * @param token   ihlale yol açan sözcük / fonksiyon / tablo (yoksa null)
     * @param message kullanıcıya gösterilecek açıklama (Msg.t — istek dilinde)
     * @param line    1 tabanlı satır (bilinmiyorsa null)
     * @param column  1 tabanlı sütun (bilinmiyorsa null)
     */
    public record Violation(String code, String token, String message, Integer line, Integer column) {}

    /** @param tables sorgunun okuduğu tablolar (şema dahil, küçük harf; ayrıştırılamazsa boş). */
    public record Result(boolean ok, List<Violation> violations, List<String> tables) {}

    private DbQuerySqlGuard() {}

    // ── Sözcük kümeleri ──────────────────────────────────────────────────────

    /** Her yerde reddedilen sözcükler (veri / yapı / yetki / işlem / oturum değiştiren komutlar). */
    static final Set<String> HARD = Set.of(
            "INSERT", "UPDATE", "DELETE", "MERGE", "UPSERT", "REPLACE", "DROP", "CREATE", "ALTER", "TRUNCATE", "RENAME",
            "GRANT", "REVOKE", "DENY", "EXEC", "EXECUTE", "CALL", "DECLARE", "BEGIN", "COMMIT", "ROLLBACK", "SAVEPOINT",
            "SET", "LOCK", "UNLOCK", "OPENROWSET", "OPENQUERY", "OPENDATASOURCE", "OPENXML", "BULK", "SHUTDOWN", "KILL",
            "DBCC", "RECONFIGURE", "WAITFOR", "VACUUM", "REINDEX", "PREPARE", "DEALLOCATE", "DISCARD", "UNLISTEN",
            "CHECKPOINT", "PURGE", "FLASHBACK");

    /**
     * Sık kolon adı olan ama bir komutun ilk sözcüğü de olabilen sözcükler: yalnız KOMUT biçiminde reddedilir (ardından bir
     * ad / metin gelirse — {@code COMMENT ON}, {@code COPY t TO}, {@code DO $$…$$}, {@code ANALYZE tablo}).
     */
    static final Set<String> SOFT = Set.of(
            "COMMENT", "SECURITY", "LABEL", "COPY", "DO", "LOAD", "IMPORT", "CLUSTER", "REFRESH", "ANALYZE", "RESET",
            "LISTEN", "NOTIFY", "HANDLER", "BACKUP", "RESTORE", "USE", "AUDIT", "NOAUDIT", "EXPLAIN");

    /** Ardından parantez gelince de komut sayılan yumuşak sözcükler ({@code COPY (SELECT …) TO}, {@code ANALYZE (…)}). */
    private static final Set<String> SOFT_PAREN = Set.of("COPY", "ANALYZE", "CLUSTER", "EXPLAIN");

    /** Bir kolon / takma addan sonra gelebilen sözcükler — yumuşak sözcüğü "ad" olarak bırakır. */
    private static final Set<String> FOLLOWERS = Set.of(
            "FROM", "WHERE", "GROUP", "ORDER", "HAVING", "UNION", "EXCEPT", "INTERSECT", "MINUS", "AS", "ON", "AND",
            "OR", "NOT", "IS", "IN", "LIKE", "ILIKE", "SIMILAR", "BETWEEN", "JOIN", "INNER", "LEFT", "RIGHT", "FULL",
            "CROSS", "OUTER", "NATURAL", "LIMIT", "OFFSET", "FETCH", "WINDOW", "THEN", "WHEN", "ELSE", "END", "ASC",
            "DESC", "NULLS", "OVER", "PARTITION", "ESCAPE", "COLLATE", "USING", "LATERAL", "APPLY", "PIVOT", "UNPIVOT",
            "FOR", "WITH", "CONNECT", "START", "QUALIFY", "TABLESAMPLE", "OPTION", "ROWS", "RANGE", "GROUPS", "FILTER",
            "WITHIN", "KEEP", "AT", "IGNORE", "RESPECT", "OVERLAPS", "ISNULL", "NOTNULL", "SAMPLE", "MODEL", "BY");

    /** MSSQL tablo ipuçlarında kabul edilen YALNIZ iki kilitsiz okuma ipucu. */
    private static final Set<String> NOLOCK_HINTS = Set.of("NOLOCK", "READUNCOMMITTED");

    /** Kilit alan / kilit bekleyen MSSQL ipuçları — her yerde reddedilir (OPTION (TABLE HINT(…)) dahil). */
    static final Set<String> LOCK_HINTS = Set.of(
            "HOLDLOCK", "PAGLOCK", "READCOMMITTEDLOCK", "READPAST", "REPEATABLEREAD", "ROWLOCK", "SERIALIZABLE",
            "TABLOCK", "TABLOCKX", "UPDLOCK", "XLOCK");

    /** İpucu grubu içinde görülebilen diğer MSSQL ipuçları (kilit değil ama izin de yok — yalnız NOLOCK kabul). */
    private static final Set<String> OTHER_HINTS = Set.of(
            "READCOMMITTED", "SNAPSHOT", "NOWAIT", "INDEX", "FORCESEEK", "FORCESCAN", "NOEXPAND", "KEEPIDENTITY",
            "KEEPDEFAULTS", "IGNORE_CONSTRAINTS", "IGNORE_TRIGGERS", "SPATIAL_WINDOW_MAX_CELLS", "FASTFIRSTROW");

    /**
     * Yasak fonksiyon ÖNEKLERİ (büyük harf; çağrı biçiminde, şema önekinden bağımsız). Bekletme, dosya / sunucu okuma,
     * oturum / sunucu durumu değiştirme, kilit, sıra (sequence) ilerletme, keyfi SQL çalıştırma (query_to_xml, dbms_*),
     * uzak bağlantı (dblink, utl_http, httpuritype) ve yük oluşturma yasak.
     */
    static final List<String> FORBIDDEN_FN_PREFIXES = List.of(
            // PostgreSQL
            "PG_SLEEP", "PG_READ_", "PG_LS_", "PG_STAT_FILE", "PG_STAT_RESET", "PG_TERMINATE_BACKEND",
            "PG_CANCEL_BACKEND", "PG_RELOAD_CONF", "PG_ROTATE_LOGFILE", "PG_PROMOTE", "PG_SWITCH_", "PG_START_",
            "PG_STOP_", "PG_BACKUP_", "PG_CREATE_", "PG_DROP_", "PG_IMPORT_", "PG_FILE_", "PG_LOGICAL_",
            "PG_REPLICATION_", "PG_WAL_REPLAY_", "PG_ADVISORY", "PG_TRY_ADVISORY", "PG_NOTIFY",
            "PG_LOG_BACKEND_MEMORY_CONTEXTS", "PG_CURRENT_XACT_ID", "PG_EXPORT_SNAPSHOT", "LO_", "DBLINK",
            "SET_CONFIG", "NEXTVAL", "SETVAL", "TXID_", "QUERY_TO_XML", "DATABASE_TO_XML", "SCHEMA_TO_XML",
            "CURSOR_TO_XML",
            // MSSQL
            "XP_", "SP_", "FN_DBLOG", "FN_DUMP_DBLOG", "FN_TRACE_GETTABLE", "FN_XE_FILE_TARGET_READ_FILE",
            "FN_GET_AUDIT_FILE",
            // Oracle
            "DBMS_", "UTL_", "OWA_", "HTTPURITYPE", "DBURITYPE", "XDBURITYPE");

    /** Tam ad eşleşmesiyle yasak kısa / genel fonksiyon adları (önek olsalar yanlış pozitif verirdi). */
    static final Set<String> FORBIDDEN_FN_EXACT = Set.of(
            "SLEEP", "BENCHMARK", "GET_LOCK", "RELEASE_LOCK", "LOAD_FILE", "URITYPE", "HTP", "HTF");

    /** Nitelikli adın HERHANGİ bir parçasında yasak paket önekleri ({@code SYS.DBMS_LOCK.SLEEP}). */
    private static final List<String> FORBIDDEN_PACKAGE_PREFIXES = List.of("DBMS_", "UTL_", "OWA_");

    /** MSSQL'de nitelikli çağrı olarak serbest XML veri türü yöntemleri ({@code col.value('…', 'int')}). */
    private static final Set<String> MSSQL_XML_METHODS = Set.of("VALUE", "QUERY", "EXIST", "NODES");

    // ── Önbellek / ayrıştırıcı yürütücüsü ────────────────────────────────────

    private static final Map<String, Result> CACHE = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Result> e) { return size() > CACHE_SIZE; }
    };

    /** Ayrıştırma sanal iş parçacığında; eşzamanlı ayrıştırma sayısı sınırlı (kötü niyetli girdi selinde CPU savunması). */
    private static final ExecutorService PARSER = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("dbquery-sql-parse-", 0).factory());
    private static final Semaphore PARSE_PERMITS =
            new Semaphore(Math.max(2, Runtime.getRuntime().availableProcessors()));

    /** Önbelleği boşaltır (testler). */
    static void clearCache() {
        synchronized (CACHE) { CACHE.clear(); }
    }

    // ── Giriş ────────────────────────────────────────────────────────────────

    public static Result check(String sql, DbType type) {
        DbType t = type == null ? DbType.POSTGRESQL : type;
        if (sql == null || sql.isBlank()) return single(SQL_EMPTY, null, msgEmpty(), null, null);
        if (sql.length() > MAX_SQL_CHARS) return single(SQL_TOO_LONG, null, msgTooLong(sql.length()), null, null);
        String key = t.name() + '|' + Msg.lang() + '|' + sha256(sql);
        synchronized (CACHE) {
            Result hit = CACHE.get(key);
            if (hit != null) return hit;
        }
        Result r = new Run(sql, t).execute();
        if (!isTimeout(r)) {
            synchronized (CACHE) { CACHE.put(key, r); }
        }
        return r;
    }

    /**
     * Ayrıştırma zaman / izin sınırına takıldı mı (sorgu reddedilmedi; "şimdilik denetlenemedi"). Çalıştırıcı bunu FAIL değil
     * ATLAMA sayar — yük altında bir izleme sahte alarm açmasın.
     */
    public static boolean isTimeout(Result r) {
        return r != null && !r.ok() && r.violations().size() == 1
                && SQL_UNPARSABLE.equals(r.violations().get(0).code())
                && TOKEN_TIMEOUT.equals(r.violations().get(0).token());
    }

    /**
     * Veritabanına gönderilecek metin: denetimin serbest bıraktığı SONDAKİ tek {@code ;} silinir (Oracle JDBC sondaki
     * noktalı virgülü ORA-00911 ile reddeder). Sözcük çözümlenemezse metin olduğu gibi döner (denetim zaten reddeder).
     */
    public static String executableSql(String sql, DbType type) {
        if (sql == null) return null;
        SqlLexer.Lexed lx = SqlLexer.lex(sql, type == null ? DbType.POSTGRESQL : type);
        if (lx.error() != null || lx.tokens().isEmpty()) return sql;
        SqlLexer.Token last = lx.tokens().get(lx.tokens().size() - 1);
        if (!last.isSymbol(';')) return sql;
        return sql.substring(0, last.start()) + sql.substring(last.end());
    }

    private static Result single(String code, String token, String message, Integer line, Integer column) {
        return new Result(false, List.of(new Violation(code, token, message, line, column)), List.of());
    }

    // ── Tek denetim koşusu ───────────────────────────────────────────────────

    private static final class Run {
        final String sql;
        final DbType type;
        final List<Violation> violations = new ArrayList<>();
        final Set<String> seen = new LinkedHashSet<>();
        SqlLexer.Lexed lx;
        List<SqlLexer.Token> tk;
        boolean[] consumed;
        /** Kabul edilen MSSQL ipucu grupları: [başlangıç kaynak dizini, bitiş, eski biçim mi (1/0)]. */
        final List<int[]> hintRewrites = new ArrayList<>();
        final Set<String> tables = new LinkedHashSet<>();
        int lexicalViolations;

        Run(String sql, DbType type) {
            this.sql = sql;
            this.type = type;
        }

        Result execute() {
            lx = SqlLexer.lex(sql, type);
            if (SQL_CONTROL_CHAR.equals(lx.error()))
                return single(SQL_CONTROL_CHAR, null, msgControl(pos(lx.errorPos())), line(lx.errorPos()), col(lx.errorPos()));
            if (SQL_UNTERMINATED.equals(lx.error()))
                return single(SQL_UNTERMINATED, null, msgUnterminated(lx.errorDetail(), pos(lx.errorPos())),
                        line(lx.errorPos()), col(lx.errorPos()));
            tk = lx.tokens();
            if (tk.isEmpty()) return single(SQL_EMPTY, null, msgEmpty(), null, null);
            consumed = new boolean[tk.size()];

            tokenRules();
            lexicalViolations = violations.size();
            if (lx.maxDepth() > MAX_DEPTH) {
                add(SQL_UNPARSABLE, null, msgTooDeep(), null);
            } else {
                astRules();
            }
            List<Violation> out = List.copyOf(violations);
            return new Result(out.isEmpty(), out, out.isEmpty() || !tables.isEmpty() ? List.copyOf(tables) : List.of());
        }

        // ── sözcük kuralları ─────────────────────────────────────

        void tokenRules() {
            int n = tk.size();
            // İlk anlamlı sözcük: SELECT ya da WITH (önünde açılan parantezler serbest)
            int k = 0;
            while (k < n && tk.get(k).isSymbol('(')) k++;
            if (k >= n || !(tk.get(k).isWord("SELECT") || tk.get(k).isWord("WITH"))) {
                SqlLexer.Token first = tk.get(Math.min(k, n - 1));
                add(SQL_NOT_SELECT, first.text(), msgNotSelect(first.text(), pos(first.start())), first.start());
            }
            // Tek ifade: yalnız SONDA tek ';'
            for (int i = 0; i < n; i++) {
                SqlLexer.Token t = tk.get(i);
                if (t.isSymbol(';') && i != n - 1) {
                    add(SQL_MULTIPLE_STATEMENTS, ";", msgMultiple(pos(t.start())), t.start());
                    break;
                }
            }
            // JDBC kaçış sözdizimi ({call …}, {fn …}) — sürücü bunu SQL'e ÇEVİRİR
            for (SqlLexer.Token t : tk) {
                if (t.isSymbol('{') || t.isSymbol('}')) {
                    add(SQL_FORBIDDEN_KEYWORD, t.text(), msgJdbcEscape(pos(t.start())), t.start());
                    break;
                }
            }
            if (type == DbType.MSSQL) mssqlHintGroups();

            for (int i = 0; i < n; i++) {
                if (consumed[i]) continue;
                SqlLexer.Token t = tk.get(i);
                if (t.kind() == SqlLexer.Kind.SYMBOL) {
                    if (type == DbType.ORACLE && t.isSymbol('@')) {
                        String name = (i > 0 && tk.get(i - 1).isName() ? tk.get(i - 1).text() : "")
                                + "@" + (i + 1 < n && tk.get(i + 1).isName() ? tk.get(i + 1).text() : "");
                        add(SQL_REMOTE_OBJECT, name, msgRemote(name, pos(t.start())), t.start());
                    }
                    continue;
                }
                boolean oracleOuterJoin = type == DbType.ORACLE && i + 3 < n && tk.get(i + 2).isSymbol('+')
                        && tk.get(i + 3).isSymbol(')');   // d.id(+) — eski Oracle dış birleşim işareti, çağrı değil
                if (t.isName() && i + 1 < n && tk.get(i + 1).isSymbol('(') && !consumed[i + 1] && !oracleOuterJoin)
                    checkFunction(i);
                if (t.kind() != SqlLexer.Kind.WORD) continue;
                String u = t.upper();
                boolean dotBefore = i > 0 && tk.get(i - 1).isSymbol('.');
                boolean dotAfter = i + 1 < n && tk.get(i + 1).isSymbol('.');
                if (dotBefore && "NEXTVAL".equals(u)) {
                    consumed[i] = true;
                    add(SQL_FORBIDDEN_FUNCTION, t.text(), msgSequence(t.text(), pos(t.start())), t.start());
                    continue;
                }
                if (dotBefore || dotAfter) continue;   // nitelikli ad parçası (t.comment, comment.x) — sözcük değil ad
                if ("FOR".equals(u) && i + 1 < n && tk.get(i + 1).kind() == SqlLexer.Kind.WORD) {
                    String nx = tk.get(i + 1).upper();
                    if (nx.equals("UPDATE") || nx.equals("SHARE") || nx.equals("NO") || nx.equals("KEY")) {
                        int end = i + 1;
                        while (end + 1 < n && end < i + 3 && tk.get(end + 1).kind() == SqlLexer.Kind.WORD
                                && Set.of("KEY", "UPDATE", "SHARE").contains(tk.get(end + 1).upper())) end++;
                        String clause = joinWords(i, end);
                        for (int j = i; j <= end; j++) consumed[j] = true;
                        add(SQL_LOCKING_CLAUSE, clause, msgLocking(clause, pos(t.start())), t.start());
                        continue;
                    }
                }
                if ("NEXT".equals(u) && i + 2 < n && tk.get(i + 1).isWord("VALUE") && tk.get(i + 2).isWord("FOR")) {
                    consumed[i + 1] = consumed[i + 2] = true;
                    add(SQL_FORBIDDEN_FUNCTION, "NEXT VALUE FOR", msgSequence("NEXT VALUE FOR", pos(t.start())), t.start());
                    continue;
                }
                if ("INTO".equals(u)) {
                    String prev = prevWord(i);
                    if (!"INSERT".equals(prev) && !"MERGE".equals(prev) && !"REPLACE".equals(prev))
                        add(SQL_INTO, t.text(), msgInto(pos(t.start())), t.start());
                    continue;
                }
                if (HARD.contains(u)) {
                    boolean call = i + 1 < n && tk.get(i + 1).isSymbol('(');
                    if ("REPLACE".equals(u) && call) continue;                 // REPLACE(metin, 'a', 'b') fonksiyonu
                    if ("SET".equals(u) && (call || "CHARACTER".equals(prevWord(i)))) continue;   // CHARACTER SET, SET(koleksiyon)
                    add(SQL_FORBIDDEN_KEYWORD, u, msgKeyword(u, pos(t.start())), t.start());
                    continue;
                }
                if (SOFT.contains(u) && softTriggered(i)) {
                    add(SQL_FORBIDDEN_KEYWORD, u, msgSoftKeyword(u, pos(t.start())), t.start());
                    continue;
                }
                if (type == DbType.MSSQL && LOCK_HINTS.contains(u))
                    add(SQL_LOCK_HINT, u, msgLockHint(u, pos(t.start())), t.start());
            }
        }

        /** Yumuşak sözcük komut biçiminde mi: ardından bir ad / metin (ya da bazıları için parantez) geliyor. */
        boolean softTriggered(int i) {
            if (i + 1 >= tk.size()) return false;
            SqlLexer.Token next = tk.get(i + 1);
            return switch (next.kind()) {
                case STRING, QUOTED -> true;
                case WORD -> !FOLLOWERS.contains(next.upper());
                case SYMBOL -> next.isSymbol('(') && SOFT_PAREN.contains(tk.get(i).upper());
                default -> false;
            };
        }

        String prevWord(int i) {
            return i > 0 && tk.get(i - 1).kind() == SqlLexer.Kind.WORD ? tk.get(i - 1).upper() : null;
        }

        String joinWords(int from, int to) {
            StringBuilder sb = new StringBuilder();
            for (int j = from; j <= to; j++) {
                if (j > from) sb.append(' ');
                sb.append(tk.get(j).upper());
            }
            return sb.toString();
        }

        /** Çağrı biçimindeki ad ({@code ad(}): yasak fonksiyon, MSSQL kullanıcı fonksiyonu, Oracle paket fonksiyonu. */
        void checkFunction(int i) {
            SqlLexer.Token t = tk.get(i);
            List<String> parts = new ArrayList<>();
            parts.add(t.upper());
            int j = i;
            while (j - 2 >= 0 && tk.get(j - 1).isSymbol('.') && tk.get(j - 2).isName()) {
                parts.add(0, tk.get(j - 2).upper());
                j -= 2;
            }
            String last = t.upper();
            String shown = sql.substring(tk.get(j).start(), t.end());
            boolean forbidden = FORBIDDEN_FN_EXACT.contains(last);
            for (String p : FORBIDDEN_FN_PREFIXES) if (last.startsWith(p)) { forbidden = true; break; }
            for (String part : parts)
                for (String p : FORBIDDEN_PACKAGE_PREFIXES) if (part.startsWith(p)) { forbidden = true; break; }
            if (forbidden) {
                add(SQL_FORBIDDEN_FUNCTION, shown, msgFunction(shown, pos(tk.get(j).start())), tk.get(j).start());
                return;
            }
            if (parts.size() >= 2 && type == DbType.MSSQL && !MSSQL_XML_METHODS.contains(last)) {
                add(SQL_FORBIDDEN_FUNCTION, shown, msgMssqlUdf(shown, pos(tk.get(j).start())), tk.get(j).start());
            } else if (parts.size() >= 2 && type == DbType.ORACLE) {
                add(SQL_FORBIDDEN_FUNCTION, shown, msgOraclePackage(shown, pos(tk.get(j).start())), tk.get(j).start());
            }
        }

        /**
         * MSSQL tablo ipucu grupları: {@code WITH ( … )} ve eski biçim {@code ad ( NOLOCK )}. Grup YALNIZ NOLOCK /
         * READUNCOMMITTED içeriyorsa kabul edilir ve ayrıştırıcı için {@code WITH (NOLOCK)} biçimine çevrilir; kilit ipucu
         * ya da başka ipucu → {@code SQL_LOCK_HINT}.
         */
        void mssqlHintGroups() {
            int n = tk.size();
            for (int i = 0; i < n; i++) {
                if (consumed[i]) continue;
                boolean modern = tk.get(i).isWord("WITH") && i > 0 && i + 1 < n && tk.get(i + 1).isSymbol('(');
                boolean legacy = !modern && tk.get(i).isSymbol('(') && i > 0
                        && (tk.get(i - 1).isName() && !tk.get(i - 1).isWord("WITH")) && tableReferenceBefore(i);
                if (!modern && !legacy) continue;
                int open = modern ? i + 1 : i;
                int close = -1, depth = 0;
                boolean nested = false, onlyWordsAndCommas = true;
                List<SqlLexer.Token> words = new ArrayList<>();
                for (int j = open; j < n; j++) {
                    SqlLexer.Token x = tk.get(j);
                    if (x.isSymbol('(')) { depth++; if (depth > 1) nested = true; continue; }
                    if (x.isSymbol(')')) { depth--; if (depth == 0) { close = j; break; } continue; }
                    if (depth == 1) {
                        if (x.kind() == SqlLexer.Kind.WORD) words.add(x);
                        else if (!x.isSymbol(',')) onlyWordsAndCommas = false;
                    }
                }
                if (close < 0 || words.isEmpty()) continue;
                if (legacy) {
                    // Eski biçim yalnız ipucu sözcükleriyse ipucudur; değilse sıradan bir parantez (fonksiyon çağrısı …)
                    boolean allHints = !nested && onlyWordsAndCommas;
                    for (SqlLexer.Token w : words)
                        if (!NOLOCK_HINTS.contains(w.upper()) && !LOCK_HINTS.contains(w.upper())
                                && !OTHER_HINTS.contains(w.upper())) { allHints = false; break; }
                    if (!allHints) continue;
                }
                boolean accepted = !nested && onlyWordsAndCommas;
                for (SqlLexer.Token w : words) if (!NOLOCK_HINTS.contains(w.upper())) { accepted = false; break; }
                for (int j = i; j <= close; j++) consumed[j] = true;
                if (accepted) {
                    hintRewrites.add(new int[] {tk.get(i).start(), tk.get(close).end(), legacy ? 1 : 0});
                    continue;
                }
                for (SqlLexer.Token w : words) {
                    String u = w.upper();
                    if (NOLOCK_HINTS.contains(u)) continue;
                    add(SQL_LOCK_HINT, u, LOCK_HINTS.contains(u) || "READCOMMITTED".equals(u)
                            ? msgLockHint(u, pos(w.start())) : msgOtherHint(u, pos(w.start())), w.start());
                }
                if (nested && words.stream().allMatch(w -> NOLOCK_HINTS.contains(w.upper()))) {
                    SqlLexer.Token first = tk.get(open);
                    add(SQL_LOCK_HINT, "WITH", msgOtherHint("WITH ( … )", pos(first.start())), first.start());
                }
            }
        }

        /**
         * Eski biçim {@code ( NOLOCK )} yalnız bir TABLO BAŞVURUSUNDAN sonra ipucudur: önündeki ad zinciri (isteğe bağlı takma
         * adla — {@code t x}, {@code t AS x}) FROM / JOIN / APPLY ya da virgülden sonra gelir. Seçim listesinde, WHERE'de
         * {@code dbo.f(NOLOCK)} bir fonksiyon çağrısıdır — ipucu sanılıp kullanıcı fonksiyonu denetimini atlatamaz.
         */
        boolean tableReferenceBefore(int openParen) {
            int b = chainStart(openParen - 1) - 1;
            if (b >= 0 && isTableIntro(b)) return true;
            if (b >= 0 && tk.get(b).isWord("AS")) {
                b--;
                if (b < 0 || !tk.get(b).isName()) return false;
                b = chainStart(b) - 1;
            } else if (b >= 0 && tk.get(b).isName()) {
                b = chainStart(b) - 1;              // takma ad AS'siz: önceki zincir tablo adıdır
            } else {
                return false;
            }
            return b >= 0 && isTableIntro(b);
        }

        private boolean isTableIntro(int idx) {
            SqlLexer.Token t = tk.get(idx);
            if (t.isWord("FROM") || t.isWord("JOIN") || t.isWord("APPLY")) return true;
            return t.isSymbol(',') && commaInFromList(idx);
        }

        /** Virgül bir FROM listesinde mi (aynı parantez düzeyinde geriye doğru ilk yan tümce sözcüğü FROM). */
        private boolean commaInFromList(int comma) {
            int depth = 0;
            for (int j = comma - 1; j >= 0; j--) {
                SqlLexer.Token x = tk.get(j);
                if (x.isSymbol(')')) { depth++; continue; }
                if (x.isSymbol('(')) { if (depth == 0) return false; depth--; continue; }
                if (depth > 0 || x.kind() != SqlLexer.Kind.WORD) continue;
                switch (x.upper()) {
                    case "FROM": return true;
                    case "SELECT", "WHERE", "GROUP", "ORDER", "HAVING", "ON", "BY", "SET", "VALUES", "WITH", "OPTION":
                        return false;
                    default: break;
                }
            }
            return false;
        }

        /** {@code end} dizininde biten {@code ad.ad.ad} zincirinin ilk belirteci. */
        int chainStart(int end) {
            int s = end;
            while (s - 2 >= 0 && tk.get(s - 1).isSymbol('.') && tk.get(s - 2).isName()) s -= 2;
            return s;
        }

        // ── AST kuralları ────────────────────────────────────────

        void astRules() {
            String text = parserText();
            ParseOutcome po = parse(text, type);
            if (po.timeout()) {
                if (violations.isEmpty()) add(SQL_UNPARSABLE, TOKEN_TIMEOUT, msgParseTimeout(), null);
                return;
            }
            if (po.statement() == null) {
                if (violations.isEmpty()) {
                    Integer src = po.offset() >= 0 ? mapToSource(po.offset()) : null;
                    add(SQL_UNPARSABLE, po.near(), msgUnparsable(src == null ? "" : pos(src), po.near()), src);
                }
                return;
            }
            Statement st = po.statement();
            if (!(st instanceof Select sel)) {
                add(SQL_NOT_SELECT, st.getClass().getSimpleName(), msgNotSelect(firstWord(), ""), null);
                return;
            }
            Walker w = new Walker();
            try {
                w.getTables((Statement) sel);
            } catch (RuntimeException | StackOverflowError e) {
                if (violations.isEmpty()) add(SQL_UNPARSABLE, null, msgUnparsable("", null), null);
                return;
            }
            int nameSearchFrom = 0;
            for (Walker.Ref ref : w.refs) {
                String normalized = normalizedName(ref.table().getFullyQualifiedName());
                List<String> parts = splitName(ref.table().getFullyQualifiedName());
                if (ref.cte()) continue;
                if (tables.size() < MAX_TABLES && normalized != null && !normalized.isEmpty()) tables.add(normalized);
                if (type != DbType.MSSQL) continue;
                String shown = ref.table().getFullyQualifiedName();
                int p = positionOfName(parts.isEmpty() ? shown : parts.get(parts.size() - 1), nameSearchFrom);
                if (p >= 0) nameSearchFrom = p + 1;
                Integer src = p >= 0 ? tk.get(p).start() : null;
                if (parts.size() >= 4) {
                    add(SQL_REMOTE_OBJECT, shown, msgRemote(shown, src == null ? "" : pos(src)), src);
                    continue;
                }
                SQLServerHints h = ref.table().getSqlServerHints();
                if (h == null || !Boolean.TRUE.equals(h.getNoLock()))
                    add(SQL_MSSQL_NOLOCK_REQUIRED, shown, msgNolock(shown, src == null ? "" : pos(src)), src);
            }
            if (type == DbType.MSSQL) {
                for (TableFunction tf : w.functions) {
                    String name = tf.getFunction() == null ? String.valueOf(tf) : tf.getFunction().getName();
                    add(SQL_FORBIDDEN_FUNCTION, name, msgTableFunction(name), null);
                }
            }
            for (String alias : w.modifyingCte) add(SQL_NOT_SELECT, alias, msgModifyingCte(alias), null);
            if (type == DbType.ORACLE) oracleQualifiedNames(w.qualifiers);
            if (w.into && lexicalViolations == 0) add(SQL_INTO, "INTO", msgInto(""), null);
            if (w.forUpdate && lexicalViolations == 0) add(SQL_LOCKING_CLAUSE, "FOR UPDATE", msgLocking("FOR UPDATE", ""), null);
        }

        /**
         * Oracle parametresiz paket fonksiyonunu PARANTEZSİZ çağırmaya izin verir ({@code SELECT pkg.zarar FROM dual}) —
         * belirteç düzeyinde {@code takma_ad.kolon}'dan ayırt edilemez. Niteleyicisi sorgunun bir tablosu / takma adı / CTE'si
         * OLMAYAN nitelikli ad reddedilir (paket ya da başka şemanın nesnesi olabilir).
         */
        void oracleQualifiedNames(Set<String> known) {
            int n = tk.size();
            for (int i = 0; i + 2 < n; i++) {
                if (!tk.get(i).isName() || !tk.get(i + 1).isSymbol('.') || !tk.get(i + 2).isName()) continue;
                if (i > 0 && tk.get(i - 1).isSymbol('.')) continue;
                int j = i;
                while (j + 2 < n && tk.get(j + 1).isSymbol('.') && tk.get(j + 2).isName()) j += 2;
                boolean call = j + 1 < n && tk.get(j + 1).isSymbol('(');
                String first = lower(tk.get(i).kind() == SqlLexer.Kind.QUOTED ? unquote(tk.get(i).text()) : tk.get(i).text());
                if (!call && !known.contains(first) && !consumed[j]) {
                    String shown = sql.substring(tk.get(i).start(), tk.get(j).end());
                    add(SQL_FORBIDDEN_FUNCTION, shown, msgOracleQualified(shown, pos(tk.get(i).start())), tk.get(i).start());
                }
                i = j;
            }
        }

        String firstWord() {
            for (SqlLexer.Token t : tk) if (t.kind() == SqlLexer.Kind.WORD) return t.text();
            return null;
        }

        /** Belirteç listesinde (ardışık) bir tablo adının konumu — ihlal satır:sütun'u için. */
        int positionOfName(String name, int from) {
            String u = unquote(name).toUpperCase(Locale.ROOT);
            for (int i = Math.max(0, from); i < tk.size(); i++) if (tk.get(i).isName() && u.equals(tk.get(i).upper())) return i;
            for (int i = 0; i < Math.min(from, tk.size()); i++) if (tk.get(i).isName() && u.equals(tk.get(i).upper())) return i;
            return -1;
        }

        // ── ayrıştırıcı metni ────────────────────────────────────

        int[] parserMap;

        /** Nötr metin + kabul edilen ipucu gruplarının {@code WITH (NOLOCK)} biçimi; konum eşlemi {@link #parserMap}. */
        String parserText() {
            char[] neutral = lx.neutral();
            StringBuilder sb = new StringBuilder(neutral.length + 16);
            int[] map = new int[neutral.length + 16 * (hintRewrites.size() + 1)];
            int mi = 0, src = 0, ri = 0;
            while (src < neutral.length) {
                int[] rw = ri < hintRewrites.size() ? hintRewrites.get(ri) : null;
                if (rw != null && rw[0] == src) {
                    String repl = (rw[2] == 1 ? " " : "") + "WITH (NOLOCK)";
                    int len = rw[1] - rw[0];
                    StringBuilder r = new StringBuilder(repl);
                    while (r.length() < len) r.append(' ');
                    for (int q = 0; q < r.length(); q++) {
                        if (mi == map.length) map = java.util.Arrays.copyOf(map, map.length * 2);
                        map[mi++] = Math.min(rw[0] + q, rw[1] - 1);
                    }
                    sb.append(r);
                    src = rw[1];
                    ri++;
                    continue;
                }
                if (mi == map.length) map = java.util.Arrays.copyOf(map, map.length * 2);
                map[mi++] = src;
                sb.append(neutral[src]);
                src++;
            }
            parserMap = java.util.Arrays.copyOf(map, mi);
            return sb.toString();
        }

        Integer mapToSource(int parserOffset) {
            if (parserMap == null || parserMap.length == 0) return null;
            int i = Math.max(0, Math.min(parserOffset, parserMap.length - 1));
            return parserMap[i];
        }

        // ── ihlal toplama ────────────────────────────────────────

        void add(String code, String token, String message, Integer srcPos) {
            if (violations.size() >= MAX_VIOLATIONS) return;
            Integer l = srcPos == null ? null : line(srcPos);
            Integer c = srcPos == null ? null : col(srcPos);
            String key = code + '|' + token + '|' + l + '|' + c;
            if (!seen.add(key)) return;
            violations.add(new Violation(code, token, message, l, c));
        }

        String pos(int srcPos) { return DbQuerySqlGuard.position(line(srcPos), col(srcPos)); }
        int line(int srcPos) { return lx.line(srcPos); }
        int col(int srcPos) { return lx.column(srcPos); }
    }

    // ── AST yürüyücü: tablolar, CTE kapsamı, tablo fonksiyonları, INTO / FOR UPDATE ─

    /**
     * {@link TablesNamesFinder} alt sınıfı: her {@link Table} düğümünü CTE kapsamıyla birlikte toplar. Bir CTE adı yalnız
     * TANIMLANDIĞI sorgu ve ondan SONRA gelen CTE gövdeleri içinde muaftır (özyineli CTE kendi adını görür); başka kapsamda
     * aynı adlı gerçek tablo NOLOCK denetimine girer.
     */
    private static final class Walker extends TablesNamesFinder<Void> {
        record Ref(Table table, boolean cte) {}

        /** @param names küçük harf CTE adları; {@code visible} = şu an görünen ilk N ad */
        private static final class Frame {
            final List<WithItem<?>> items;
            final List<String> names;
            int visible;

            Frame(List<WithItem<?>> items) {
                this.items = items;
                this.names = new ArrayList<>();
                for (WithItem<?> w : items) names.add(lower(unquote(w.getAliasName())));
            }
        }

        final List<Ref> refs = new ArrayList<>();
        final List<TableFunction> functions = new ArrayList<>();
        final List<String> modifyingCte = new ArrayList<>();
        /** Sorgudaki tablo adı parçaları, takma adlar ve CTE adları (küçük harf) — Oracle nitelikli ad denetimi için. */
        final Set<String> qualifiers = new java.util.HashSet<>();
        boolean into, forUpdate;
        private final Deque<Frame> frames = new ArrayDeque<>();

        /**
         * Aynı sorgu (aynı WITH listesi) için İKİNCİ çerçeve açılmaz: üst sınıf WITH öğelerini önce ifade düzeyinde
         * ({@code visit(Select)}), sonra yeniden sorgu düzeyinde gezer — tek çerçeve, görünürlük her geçişte baştan kurulur.
         */
        private Frame push(Select s) {
            List<WithItem<?>> items = s.getWithItemsList();
            if (items == null || items.isEmpty()) return null;
            for (Frame f : frames) if (f.items == items) return null;
            Frame f = new Frame(items);
            frames.push(f);
            return f;
        }

        private void pop(Frame f) {
            if (f != null) frames.remove(f);
        }

        private void inspect(Select s) {
            if (s.getForUpdate() != null || s.getForMode() != null) forUpdate = true;
            if (s instanceof PlainSelect ps && ps.getIntoTables() != null && !ps.getIntoTables().isEmpty()) into = true;
        }

        @Override
        public <S> Void visit(Select s, S ctx) {
            Frame f = push(s);
            try { return super.visit(s, ctx); } finally { pop(f); }
        }

        @Override
        public <S> Void visit(PlainSelect s, S ctx) {
            inspect(s);
            Frame f = push(s);
            try { return super.visit(s, ctx); } finally { pop(f); }
        }

        @Override
        public <S> Void visit(SetOperationList s, S ctx) {
            inspect(s);
            Frame f = push(s);
            try { return super.visit(s, ctx); } finally { pop(f); }
        }

        private void alias(net.sf.jsqlparser.expression.Alias a) {
            if (a != null && a.getName() != null) qualifiers.add(lower(unquote(a.getName())));
        }

        @Override
        public <S> Void visit(ParenthesedSelect s, S ctx) {
            alias(s.getAlias());
            inspect(s);
            Frame f = push(s);
            try { return super.visit(s, ctx); } finally { pop(f); }
        }

        @Override
        public <S> Void visit(LateralSubSelect s, S ctx) {
            alias(s.getAlias());
            inspect(s);
            Frame f = push(s);
            try { return super.visit(s, ctx); } finally { pop(f); }
        }

        @Override
        public <S> Void visit(WithItem<?> w, S ctx) {
            if (!(w.getParenthesedStatement() instanceof ParenthesedSelect)) modifyingCte.add(w.getAliasName());
            if (w.getAliasName() != null) qualifiers.add(lower(unquote(w.getAliasName())));
            for (Frame f : frames) {
                int idx = indexOfIdentity(f.items, w);
                if (idx >= 0) {
                    f.visible = idx + 1;          // gövde kendisini (özyineli) ve önceki CTE'leri görür
                    try { return super.visit(w, ctx); } finally { f.visible = idx + 1; }
                }
            }
            return super.visit(w, ctx);
        }

        @Override
        public <S> Void visit(Table t, S ctx) {
            boolean cte = false;
            if (splitName(t.getFullyQualifiedName()).size() == 1) {   // CTE adı hiçbir zaman nitelikli değildir
                String name = lower(unquote(t.getName()));
                for (Frame f : frames) {
                    for (int i = 0; i < f.visible && i < f.names.size(); i++)
                        if (f.names.get(i).equals(name)) { cte = true; break; }
                    if (cte) break;
                }
            }
            refs.add(new Ref(t, cte));
            for (String part : splitName(t.getFullyQualifiedName())) qualifiers.add(lower(unquote(part)));
            alias(t.getAlias());
            return super.visit(t, ctx);
        }

        @Override
        public <S> Void visit(TableFunction tf, S ctx) {
            functions.add(tf);
            alias(tf.getAlias());
            return super.visit(tf, ctx);
        }

        private static int indexOfIdentity(List<WithItem<?>> items, WithItem<?> w) {
            for (int i = 0; i < items.size(); i++) if (items.get(i) == w) return i;
            return -1;
        }
    }

    // ── Ayrıştırma (zaman kutusu) ────────────────────────────────────────────

    /** @param offset ayrıştırıcı metnindeki hata konumu (-1 = bilinmiyor); {@code near} = hatanın yanındaki belirteç */
    record ParseOutcome(Statement statement, boolean timeout, int offset, String near) {}

    static ParseOutcome parse(String text, DbType type) {
        boolean acquired;
        try {
            acquired = PARSE_PERMITS.tryAcquire(PARSE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ParseOutcome(null, true, -1, null);
        }
        if (!acquired) return new ParseOutcome(null, true, -1, null);
        Future<ParseOutcome> f;
        try {
            f = PARSER.submit(() -> {
                try {
                    return parseNow(text, type);
                } finally {
                    PARSE_PERMITS.release();   // izin, ayrıştırma GERÇEKTEN bitince bırakılır (kaçak ayrıştırma sınırlı)
                }
            });
        } catch (RuntimeException e) {
            PARSE_PERMITS.release();
            return new ParseOutcome(null, true, -1, null);
        }
        try {
            return f.get(PARSE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            f.cancel(true);
            return new ParseOutcome(null, true, -1, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ParseOutcome(null, true, -1, null);
        } catch (ExecutionException e) {
            return new ParseOutcome(null, false, -1, null);
        }
    }

    private static ParseOutcome parseNow(String text, DbType type) {
        try {
            CCJSqlParser p = CCJSqlParserUtil.newParser(text)
                    .withSquareBracketQuotation(type == DbType.MSSQL)
                    .withUnsupportedStatements(false);
            Statement st = p.Statement();
            return new ParseOutcome(st, false, -1, null);
        } catch (ParseException e) {
            Token at = e.currentToken == null ? null : (e.currentToken.next != null ? e.currentToken.next : e.currentToken);
            int off = at == null ? -1 : offsetOf(text, at.beginLine, at.beginColumn);
            String near = at == null || at.image == null || at.image.isEmpty() ? null : clip(at.image, 40);
            return new ParseOutcome(null, false, off, near);
        } catch (OutOfMemoryError e) {
            throw e;
        } catch (Throwable e) {   // TokenMgrException, StackOverflowError, sınıf dönüşümü … → ayrıştırılamadı
            return new ParseOutcome(null, false, -1, null);
        }
    }

    /** JavaCC satır / sütun (1 tabanlı; sekmeler nötr metinde zaten boşluk) → metin dizini. */
    static int offsetOf(String text, int line, int column) {
        if (line < 1 || column < 1) return -1;
        int[] starts = SqlLexer.lineStarts(text);
        if (line > starts.length) return -1;
        int off = starts[line - 1] + column - 1;
        return Math.max(0, Math.min(off, Math.max(0, text.length() - 1)));
    }

    // ── Ad yardımcıları ──────────────────────────────────────────────────────

    /** Tırnaklara saygılı nokta bölme: {@code [dbo].[a.b]} → [dbo], [a.b]. */
    static List<String> splitName(String fqn) {
        List<String> out = new ArrayList<>();
        if (fqn == null) return out;
        StringBuilder cur = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < fqn.length(); i++) {
            char c = fqn.charAt(i);
            if (quote != 0) {
                cur.append(c);
                if (c == quote) quote = 0;
                continue;
            }
            if (c == '"') { quote = '"'; cur.append(c); continue; }
            if (c == '[') { quote = ']'; cur.append(c); continue; }
            if (c == '`') { quote = '`'; cur.append(c); continue; }
            if (c == '.') { out.add(cur.toString()); cur.setLength(0); continue; }
            cur.append(c);
        }
        out.add(cur.toString());
        out.removeIf(String::isEmpty);
        return out;
    }

    static String unquote(String part) {
        if (part == null) return "";
        String s = part.trim();
        if (s.length() >= 2) {
            char a = s.charAt(0), b = s.charAt(s.length() - 1);
            if (a == '"' && b == '"') return s.substring(1, s.length() - 1).replace("\"\"", "\"");
            if (a == '[' && b == ']') return s.substring(1, s.length() - 1).replace("]]", "]");
            if (a == '`' && b == '`') return s.substring(1, s.length() - 1);
        }
        return s;
    }

    static String normalizedName(String fqn) {
        List<String> parts = splitName(fqn);
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (!sb.isEmpty()) sb.append('.');
            sb.append(lower(unquote(p)));
        }
        return sb.toString();
    }

    private static String lower(String s) { return s == null ? "" : s.toLowerCase(Locale.ROOT); }

    private static String clip(String s, int max) { return s.length() <= max ? s : s.substring(0, max) + "…"; }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode()) + ':' + s.length();
        }
    }

    // ── İletiler (ne · neden · ne yapmalı) ───────────────────────────────────

    static String position(int line, int col) {
        return Msg.t(" (satır " + line + ", sütun " + col + ")", " (line " + line + ", column " + col + ")");
    }

    private static String msgEmpty() {
        return Msg.t("Sorgu boş · İzlenecek bir SELECT sorgusu gerekli · Bir SELECT ya da WITH sorgusu yazın.",
                "The query is empty · A SELECT query is needed to monitor · Write a SELECT or WITH query.");
    }

    private static String msgTooLong(int len) {
        return Msg.t("Sorgu çok uzun (" + len + " karakter; en çok " + MAX_SQL_CHARS + ") · Bu uzunlukta bir sorgu "
                        + "güvenle denetlenemez · Sorguyu kısaltın ya da veritabanında bir görünüm (view) tanımlayıp onu okuyun.",
                "The query is too long (" + len + " characters; at most " + MAX_SQL_CHARS + ") · A query this long "
                        + "cannot be checked safely · Shorten it, or define a view in the database and read that.");
    }

    private static String msgControl(String at) {
        return Msg.t("Sorguda görünmez bir denetim ya da biçim karakteri var" + at + " · Bu karakterler sorgunun "
                        + "göründüğünden farklı çalışmasına yol açabilir · Sorguyu düz metin olarak yeniden yazın ya da yapıştırın.",
                "The query contains an invisible control or formatting character" + at + " · Such characters can make "
                        + "the query run differently from how it looks · Retype or paste the query as plain text.");
    }

    private static String msgUnterminated(String what, String at) {
        String tr = switch (what == null ? "" : what) {
            case "comment" -> "yorum";
            case "identifier" -> "tırnaklı ad";
            default -> "tırnaklı metin";
        };
        String en = switch (what == null ? "" : what) {
            case "comment" -> "comment";
            case "identifier" -> "quoted name";
            default -> "quoted text";
        };
        return Msg.t("Kapanmamış " + tr + at + " · Sorgunun nerede bittiği belirlenemiyor · Tırnağı ya da yorumu kapatın.",
                "Unterminated " + en + at + " · It cannot be determined where the query ends · Close the quote or comment.");
    }

    private static String msgMultiple(String at) {
        return Msg.t("Sorguda birden çok ifade var (';'" + at + ") · Yalnız tek bir SELECT çalıştırılabilir · "
                        + "Noktalı virgülden sonrasını silin; yalnız sonda tek bir ';' serbesttir.",
                "The query contains more than one statement (';'" + at + ") · Only a single SELECT can run · "
                        + "Delete everything after the semicolon; a single ';' is allowed only at the end.");
    }

    private static String msgNotSelect(String first, String at) {
        String w = first == null ? "" : " ('" + clip(first, 40) + "')";
        return Msg.t("Sorgu SELECT ile başlamıyor" + w + at + " · Yalnız veri OKUYAN SELECT / WITH sorguları izlenebilir · "
                        + "Sorguyu SELECT ya da WITH ile başlatın.",
                "The query does not start with SELECT" + w + at + " · Only queries that READ data (SELECT / WITH) can be "
                        + "monitored · Start the query with SELECT or WITH.");
    }

    private static String msgModifyingCte(String alias) {
        String a = alias == null ? "" : " '" + clip(alias, 60) + "'";
        return Msg.t("WITH bloğu" + a + " veri değiştiriyor (INSERT / UPDATE / DELETE) · İzleme sorgusu yalnız okumalı · "
                        + "WITH bloğunu yalnız SELECT olacak şekilde yeniden yazın.",
                "The WITH block" + a + " changes data (INSERT / UPDATE / DELETE) · A monitoring query must only read · "
                        + "Rewrite the WITH block as a SELECT only.");
    }

    private static String msgKeyword(String word, String at) {
        return Msg.t("'" + word + "' sözcüğüne izin verilmez" + at + " · Veri ya da yapı değiştiren, yetki veren, işlem ya da "
                        + "oturum denetleyen komutlar engellenir · Sözcüğü kaldırın; bir kolon adıysa tırnak içinde yazın (\""
                        + word.toLowerCase(Locale.ROOT) + "\").",
                "The word '" + word + "' is not allowed" + at + " · Commands that change data or structure, grant rights, "
                        + "or control transactions or the session are blocked · Remove it; if it is a column name, quote it (\""
                        + word.toLowerCase(Locale.ROOT) + "\").");
    }

    private static String msgSoftKeyword(String word, String at) {
        return Msg.t("'" + word + "' bir komut gibi kullanılmış" + at + " · Bu sözcük bir veritabanı komutu başlatabilir · "
                        + "Bir kolon adıysa tırnak içinde yazın (\"" + word.toLowerCase(Locale.ROOT)
                        + "\") ya da takma adı AS ile verin.",
                "'" + word + "' is used like a command" + at + " · This word can start a database command · "
                        + "If it is a column name, quote it (\"" + word.toLowerCase(Locale.ROOT)
                        + "\") or give the alias with AS.");
    }

    private static String msgJdbcEscape(String at) {
        return Msg.t("Süslü parantez kullanılamaz" + at + " · JDBC kaçış sözdizimi ({call …}, {fn …}) sürücüde başka bir "
                        + "komuta çevrilir · Süslü parantezi kaldırın; gerekiyorsa tırnaklı metin içinde yazın.",
                "Curly braces are not allowed" + at + " · JDBC escape syntax ({call …}, {fn …}) is translated by the driver "
                        + "into another command · Remove the braces; put them inside quoted text if needed.");
    }

    private static String msgLocking(String clause, String at) {
        return Msg.t("'" + clause + "' satırları kilitler" + at + " · İzleme sorgusu hiçbir satırı kilitlememeli; canlı "
                        + "işlemler bekleyebilir · Kilitleme yan tümcesini kaldırın.",
                "'" + clause + "' locks rows" + at + " · A monitoring query must not lock any row; live transactions could "
                        + "wait · Remove the locking clause.");
    }

    private static String msgInto(String at) {
        return Msg.t("SELECT … INTO kullanılamaz" + at + " · INTO yeni bir tablo oluşturur ya da bir değişkene / dosyaya "
                        + "yazar; izleme sorgusu yalnız okumalı · INTO yan tümcesini kaldırın.",
                "SELECT … INTO is not allowed" + at + " · INTO creates a table or writes to a variable or file; a monitoring "
                        + "query must only read · Remove the INTO clause.");
    }

    private static String msgFunction(String fn, String at) {
        return Msg.t("'" + clip(fn, 80) + "' fonksiyonuna izin verilmez" + at + " · Bekleten, dosya ya da sunucu okuyan, "
                        + "oturum / sunucu durumunu değiştiren, kilit alan ya da başka bağlantıya giden fonksiyonlar engellenir · "
                        + "Fonksiyonu kaldırın.",
                "The function '" + clip(fn, 80) + "' is not allowed" + at + " · Functions that wait, read files or the "
                        + "server, change session or server state, take locks or reach other connections are blocked · "
                        + "Remove the function.");
    }

    private static String msgSequence(String what, String at) {
        return Msg.t("'" + what + "' bir sırayı (sequence) ilerletir" + at + " · Sıra değeri almak veritabanına yazar · "
                        + "Sıra ifadesini kaldırın.",
                "'" + what + "' advances a sequence" + at + " · Taking a sequence value writes to the database · "
                        + "Remove the sequence expression.");
    }

    private static String msgMssqlUdf(String fn, String at) {
        return Msg.t("'" + clip(fn, 80) + "' kullanıcı tanımlı bir fonksiyon" + at + " · İçindeki tablo okumaları NOLOCK "
                        + "denetiminden geçemez · Fonksiyonun sorgusunu doğrudan yazın ve tablolarına WITH (NOLOCK) ekleyin.",
                "'" + clip(fn, 80) + "' is a user-defined function" + at + " · Table reads inside it cannot be checked for "
                        + "NOLOCK · Write the function's query directly and add WITH (NOLOCK) to its tables.");
    }

    private static String msgOraclePackage(String fn, String at) {
        return Msg.t("'" + clip(fn, 80) + "' bir paket ya da nesne fonksiyonu" + at + " · Paket fonksiyonları kendi "
                        + "(otonom) işlemleriyle veri değiştirebilir; içleri denetlenemez · Fonksiyonu kaldırın ya da sorgusunu "
                        + "doğrudan yazın.",
                "'" + clip(fn, 80) + "' is a package or object function" + at + " · Package functions can change data in "
                        + "their own (autonomous) transaction and cannot be checked · Remove it or write its query directly.");
    }

    private static String msgOracleQualified(String name, String at) {
        return Msg.t("'" + clip(name, 80) + "' sorgudaki bir tabloya ya da takma ada bağlı değil" + at + " · Oracle'da bu "
                        + "biçim bir paket fonksiyonunu parantezsiz çağırabilir; paket fonksiyonları denetlenemez · Kolonu "
                        + "tablonun takma adıyla yazın (ör. FROM siparis s … s.kolon) ya da fonksiyonu kaldırın.",
                "'" + clip(name, 80) + "' is not tied to a table or alias of the query" + at + " · In Oracle this form can "
                        + "call a package function without parentheses, and package functions cannot be checked · Write the "
                        + "column with the table's alias (e.g. FROM orders o … o.col) or remove the function.");
    }

    private static String msgTableFunction(String fn) {
        return Msg.t("'" + clip(fn, 80) + "' tablo değerli bir fonksiyon · İçindeki tablo okumaları NOLOCK denetiminden "
                        + "geçemez · Fonksiyonun sorgusunu doğrudan yazın ve tablolarına WITH (NOLOCK) ekleyin.",
                "'" + clip(fn, 80) + "' is a table-valued function · Table reads inside it cannot be checked for NOLOCK · "
                        + "Write its query directly and add WITH (NOLOCK) to its tables.");
    }

    private static String msgLockHint(String hint, String at) {
        return Msg.t("'" + hint + "' kilit ipucu kullanılamaz" + at + " · Satırları ya da tabloyu kilitler, canlı işlemleri "
                        + "bekletebilir · Yalnız WITH (NOLOCK) ya da WITH (READUNCOMMITTED) kullanın.",
                "The lock hint '" + hint + "' is not allowed" + at + " · It locks rows or the table and can make live "
                        + "transactions wait · Use only WITH (NOLOCK) or WITH (READUNCOMMITTED).");
    }

    private static String msgOtherHint(String hint, String at) {
        return Msg.t("'" + hint + "' tablo ipucuna izin verilmez" + at + " · Güvenlik denetimi yalnız kilitsiz okuma "
                        + "ipuçlarını kabul eder · İpucunu kaldırın; yalnız WITH (NOLOCK) ya da WITH (READUNCOMMITTED) kullanın.",
                "The table hint '" + hint + "' is not allowed" + at + " · The safety check accepts only no-lock read hints · "
                        + "Remove it; use only WITH (NOLOCK) or WITH (READUNCOMMITTED).");
    }

    private static String msgNolock(String table, String at) {
        return Msg.t("'" + clip(table, 120) + "' tablosu WITH (NOLOCK) olmadan okunuyor" + at + " · MSSQL'de kilitsiz okuma "
                        + "olmadan sorgu canlı işlemleri bekletebilir ya da onları bekleyebilir · Tablo adının (ve varsa takma "
                        + "adının) arkasına WITH (NOLOCK) ekleyin: FROM " + clip(table, 120) + " WITH (NOLOCK).",
                "The table '" + clip(table, 120) + "' is read without WITH (NOLOCK)" + at + " · In MSSQL a query without "
                        + "no-lock reads can block live transactions or wait for them · Add WITH (NOLOCK) after the table name "
                        + "(and its alias): FROM " + clip(table, 120) + " WITH (NOLOCK).");
    }

    private static String msgRemote(String name, String at) {
        return Msg.t("'" + clip(name, 120) + "' başka bir veritabanına / sunucuya gidiyor" + at + " · Uzak sunucudaki okumalar "
                        + "denetlenemez · Tabloyu bu bağlantının veritabanından doğrudan okuyun ya da uzak veritabanı için ayrı "
                        + "bir bağlantı tanımlayın.",
                "'" + clip(name, 120) + "' goes to another database or server" + at + " · Reads on a remote server cannot be "
                        + "checked · Read the table directly from this connection's database, or define a separate connection "
                        + "for the remote database.");
    }

    private static String msgTooDeep() {
        return Msg.t("Sorgu çok derin iç içe parantez içeriyor (en çok " + MAX_DEPTH + " düzey) · Bu derinlikte bir sorgu "
                        + "güvenle çözümlenemez · Alt sorguları sadeleştirin ya da WITH ile adlandırın.",
                "The query nests parentheses too deeply (at most " + MAX_DEPTH + " levels) · A query this deep cannot be "
                        + "analysed safely · Simplify the subqueries or name them with WITH.");
    }

    private static String msgUnparsable(String at, String near) {
        String nr = near == null ? "" : Msg.t(" — '" + near + "' yakınında", " — near '" + near + "'");
        return Msg.t("Sorgu çözümlenemedi" + at + nr + " · Güvenlik denetimi yalnız tam çözümlenebilen sorgulara izin verir · "
                        + "Yazımı denetleyin; veritabanına özgü ileri sözdizimini sadeleştirin.",
                "The query could not be parsed" + at + nr + " · The safety check allows only queries it can fully parse · "
                        + "Check the syntax; simplify database-specific advanced syntax.");
    }

    private static String msgParseTimeout() {
        return Msg.t("Sorgu şu an denetlenemedi (çözümleme süresi doldu) · Sunucu yoğun ya da sorgu çok karmaşık · "
                        + "Birkaç saniye sonra yeniden deneyin; sürerse sorguyu sadeleştirin.",
                "The query could not be checked right now (analysis timed out) · The server is busy or the query is very "
                        + "complex · Try again in a few seconds; if it persists, simplify the query.");
    }
}
