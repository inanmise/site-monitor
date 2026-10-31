package com.sitemonitor.service.dbquery;

import com.sitemonitor.service.dbquery.DbQuerySqlGuard.Result;
import com.sitemonitor.service.dbquery.DbQuerySqlGuard.Violation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static com.sitemonitor.service.dbquery.DbType.MSSQL;
import static com.sitemonitor.service.dbquery.DbType.ORACLE;
import static com.sitemonitor.service.dbquery.DbType.POSTGRESQL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * Sorgu güvenlik denetimi (kullanıcının KESİN isteği): yalnız SELECT, DDL / DML / DCL / TCL yok, kilit yok, MSSQL'de her
 * tabloda NOLOCK, zararlı fonksiyon yok; atlatma denemeleri (yorumla bölme, büyük-küçük harf, Unicode boşluk, iç içe yorum,
 * metin içinde / dışında noktalı virgül, lehçeye özgü tırnaklar) ve yanlış pozitifler (CASE … END, updated_at, metinde DROP).
 */
class DbQuerySqlGuardTest {

    @BeforeEach
    void reset() {
        DbQuerySqlGuard.clearCache();
    }

    private static Result pg(String sql) { return DbQuerySqlGuard.check(sql, POSTGRESQL); }
    private static Result ms(String sql) { return DbQuerySqlGuard.check(sql, MSSQL); }
    private static Result ora(String sql) { return DbQuerySqlGuard.check(sql, ORACLE); }

    private static List<String> codes(Result r) {
        return r.violations().stream().map(Violation::code).toList();
    }

    private static void assertOk(Result r) {
        assertThat(r.violations()).as("ihlal beklenmiyordu").isEmpty();
        assertThat(r.ok()).isTrue();
    }

    private static void assertRejected(Result r, String code) {
        assertThat(r.ok()).isFalse();
        assertThat(codes(r)).contains(code);
    }

    // ── geçerli sorgular / yanlış pozitif olmamalı ───────────────────────────

    @Test
    void plainSelectsPass() {
        assertOk(pg("SELECT 1"));
        assertOk(pg("select count(*) from orders where status = 'NEW'"));
        assertOk(pg("SELECT 1;"));
        assertOk(pg("  SELECT 1 ;  -- son"));
        assertOk(pg("(SELECT a FROM t) UNION (SELECT a FROM u)"));
        assertOk(pg("WITH c AS (SELECT a FROM t) SELECT count(*) FROM c"));
        assertOk(ora("SELECT COUNT(*) FROM orders WHERE created > SYSDATE - 1"));
        assertOk(ms("SELECT COUNT(*) FROM dbo.orders WITH (NOLOCK)"));
    }

    @Test
    void noFalsePositives() {
        assertOk(pg("SELECT CASE WHEN a > 1 THEN 'x' ELSE 'y' END AS c FROM t"));
        assertOk(pg("SELECT updated_at, delete_flag, insert_count, created_by FROM t"));
        assertOk(pg("SELECT 'DROP TABLE x; DELETE FROM y' AS txt FROM t"));
        assertOk(pg("SELECT a FROM t -- DELETE FROM t\nWHERE b = 1"));
        assertOk(pg("SELECT a /* DROP TABLE t; */ FROM t"));
        assertOk(pg("SELECT \"delete\", \"update\" FROM t"));
        assertOk(pg("SELECT t.comment, comment FROM t WHERE comment LIKE 'x%' ORDER BY comment"));
        assertOk(pg("SELECT REPLACE(name, 'a', 'b') FROM t"));
        assertOk(pg("SELECT a FROM t WHERE label = 'x' AND copy = 1"));
        assertOk(pg("SELECT 'it''s; fine' FROM t"));
        assertOk(pg("SELECT E'a\\'b; DROP' FROM t"));
        assertOk(pg("SELECT $$ DELETE FROM t; $$ AS x"));
        assertOk(pg("SELECT $tag$ ; DROP $tag$ FROM t"));
        assertOk(ora("SELECT q'[it's; DROP TABLE x]' FROM dual"));
        assertOk(ms("SELECT [delete], [Order Date] FROM dbo.t WITH (NOLOCK)"));
        assertOk(pg("SELECT now() - interval '1 hour', a::int FROM t WHERE b ~ '^x' LIMIT 5"));
        assertOk(pg("SELECT müşteri_adı, şehir FROM sipariş WHERE tutar > 0"));
    }

    // ── tek ifade / ilk sözcük ───────────────────────────────────────────────

    @Test
    void multipleStatementsRejected() {
        assertRejected(pg("SELECT 1; DROP TABLE t"), DbQuerySqlGuard.SQL_MULTIPLE_STATEMENTS);
        assertRejected(pg("SELECT 1; SELECT 2"), DbQuerySqlGuard.SQL_MULTIPLE_STATEMENTS);
        assertRejected(pg("SELECT 1;;"), DbQuerySqlGuard.SQL_MULTIPLE_STATEMENTS);
        assertOk(pg("SELECT ';' FROM t;"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"DELETE FROM t", "UPDATE t SET a = 1", "INSERT INTO t VALUES (1)", "DROP TABLE t",
            "TRUNCATE t", "EXEC sp_who", "CALL p()", "COPY t TO '/tmp/x'", "BEGIN", "VALUES (1)", "EXPLAIN ANALYZE SELECT 1",
            "GRANT ALL ON t TO u", "COMMIT", "SET x = 1", "DO $$ BEGIN END $$"})
    void nonSelectRejected(String sql) {
        assertRejected(pg(sql), DbQuerySqlGuard.SQL_NOT_SELECT);
    }

    // ── yasak sözcükler ve atlatma denemeleri ────────────────────────────────

    @Test
    void forbiddenKeywordsAnywhere() {
        assertRejected(ms("SELECT 1 DELETE FROM t"), DbQuerySqlGuard.SQL_FORBIDDEN_KEYWORD);   // MSSQL toplu iş
        assertRejected(pg("SELECT 1 FROM t WHERE 1 = 1 UNION SELECT 1 FROM (DELETE FROM t RETURNING 1) x"),
                DbQuerySqlGuard.SQL_FORBIDDEN_KEYWORD);
        assertRejected(pg("WITH x AS (DELETE FROM t RETURNING *) SELECT * FROM x"), DbQuerySqlGuard.SQL_FORBIDDEN_KEYWORD);
        assertRejected(pg("WITH x AS (DELETE FROM t RETURNING *) SELECT * FROM x"), DbQuerySqlGuard.SQL_NOT_SELECT);
        assertRejected(pg("SELECT 1 FROM t WHERE x = 1 AND ExEcUtE('x') = 1"), DbQuerySqlGuard.SQL_FORBIDDEN_KEYWORD);
        assertRejected(pg("SeLeCt 1 FrOm t; DrOp TaBlE t"), DbQuerySqlGuard.SQL_FORBIDDEN_KEYWORD);
        assertRejected(ms("SELECT 1 SET NOCOUNT ON"), DbQuerySqlGuard.SQL_FORBIDDEN_KEYWORD);
        assertRejected(ms("SELECT 1 BACKUP DATABASE x TO DISK = 'c:\\x'"), DbQuerySqlGuard.SQL_FORBIDDEN_KEYWORD);
    }

    @Test
    void commentSplitWordsDoNotHideKeywords() {
        // Yorum bir sözcüğü bölemez — DE/**/LETE iki ayrı sözcüktür (DE, LETE); ama yorum, sözcükleri ayırır:
        assertRejected(pg("SELECT 1/**/;/**/DELETE FROM t"), DbQuerySqlGuard.SQL_FORBIDDEN_KEYWORD);
        assertRejected(pg("SELECT 1 --x\nDELETE FROM t"), DbQuerySqlGuard.SQL_FORBIDDEN_KEYWORD);
        // iç içe yorum (PostgreSQL / MSSQL): dıştaki yorum kapanmadan kod görünmez, kapanınca görünür
        assertRejected(pg("SELECT 1 /* a /* b */ c */ DELETE FROM t"), DbQuerySqlGuard.SQL_FORBIDDEN_KEYWORD);
        assertOk(pg("SELECT 1 /* a /* DELETE */ c */ FROM t"));
        // Oracle iç içe yorumu desteklemez: içteki */ yorumu bitirir → DELETE kod olur
        assertRejected(ora("SELECT 1 FROM dual /* a /* b */ DELETE FROM t */"), DbQuerySqlGuard.SQL_FORBIDDEN_KEYWORD);
    }

    @Test
    void unicodeSpacesSeparateWords() {
        assertRejected(pg("SELECT 1 FROM t\u00A0WHERE 1=1 OR EXISTS(SELECT 1)\u2007DELETE FROM t"),
                DbQuerySqlGuard.SQL_FORBIDDEN_KEYWORD);
        assertRejected(pg("SELECT 1\u202FFROM t\u3000FOR\u00A0UPDATE"), DbQuerySqlGuard.SQL_LOCKING_CLAUSE);
    }

    @Test
    void invisibleCharactersRejected() {
        assertRejected(pg("SELECT 1 FROM t\u200BWHERE 1=1"), DbQuerySqlGuard.SQL_CONTROL_CHAR);
        assertRejected(pg("SELECT 1 FROM t WHERE a = '\u202Eabc'"), DbQuerySqlGuard.SQL_CONTROL_CHAR);
        assertRejected(pg("SELECT 1 --x\u2028DELETE FROM t"), DbQuerySqlGuard.SQL_CONTROL_CHAR);
        assertRejected(pg("SELECT 1\u0000"), DbQuerySqlGuard.SQL_CONTROL_CHAR);
        assertRejected(pg("\uFEFFSELECT 1"), DbQuerySqlGuard.SQL_CONTROL_CHAR);
        assertOk(pg("SELECT\t1\r\nFROM t"));
    }

    @Test
    void unterminated() {
        assertRejected(pg("SELECT 'abc FROM t"), DbQuerySqlGuard.SQL_UNTERMINATED);
        assertRejected(pg("SELECT 1 /* yorum"), DbQuerySqlGuard.SQL_UNTERMINATED);
        assertRejected(pg("SELECT 1 /* a /* b */ "), DbQuerySqlGuard.SQL_UNTERMINATED);
        assertRejected(pg("SELECT \"abc FROM t"), DbQuerySqlGuard.SQL_UNTERMINATED);
        assertRejected(pg("SELECT $$ abc"), DbQuerySqlGuard.SQL_UNTERMINATED);
        assertRejected(pg("SELECT E'abc\\' FROM t"), DbQuerySqlGuard.SQL_UNTERMINATED);
        assertRejected(ms("SELECT [abc FROM t"), DbQuerySqlGuard.SQL_UNTERMINATED);
        assertRejected(ora("SELECT q'[abc' FROM dual"), DbQuerySqlGuard.SQL_UNTERMINATED);
    }

    @Test
    void escapeStringBackslashQuoteDoesNotEndString() {
        // PostgreSQL E'…' içinde \' kaçıştır: veritabanı metni ' DROP …' sonrasına kadar okur → bizim de öyle okumamız
        // gerekir; aksi halde metnin içini kod sanıp (aşırı katı) ya da kodu metin sanıp (TEHLİKELİ) yanılırdık.
        assertOk(pg("SELECT E'\\'' AS q, 1 FROM t"));
        assertRejected(pg("SELECT E'\\'' AS q FROM t; DELETE FROM t"), DbQuerySqlGuard.SQL_MULTIPLE_STATEMENTS);
    }

    @Test
    void lockingAndInto() {
        assertRejected(pg("SELECT * FROM t FOR UPDATE"), DbQuerySqlGuard.SQL_LOCKING_CLAUSE);
        assertRejected(pg("SELECT * FROM t FOR NO KEY UPDATE SKIP LOCKED"), DbQuerySqlGuard.SQL_LOCKING_CLAUSE);
        assertRejected(pg("SELECT * FROM t FOR SHARE"), DbQuerySqlGuard.SQL_LOCKING_CLAUSE);
        assertRejected(ora("SELECT * FROM t FOR UPDATE NOWAIT"), DbQuerySqlGuard.SQL_LOCKING_CLAUSE);
        assertRejected(pg("SELECT * INTO x FROM y"), DbQuerySqlGuard.SQL_INTO);
        assertRejected(ms("SELECT * INTO #tmp FROM t WITH (NOLOCK)"), DbQuerySqlGuard.SQL_INTO);
        assertRejected(pg("SELECT a FROM t INTO OUTFILE '/tmp/x'"), DbQuerySqlGuard.SQL_INTO);
        // FOR XML / FOR JSON (MSSQL) kilit değildir
        assertOk(ms("SELECT a FROM t WITH (NOLOCK) FOR XML PATH('r')"));
    }

    // ── yasak fonksiyonlar ───────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"SELECT pg_sleep(10)", "SELECT 1 UNION SELECT pg_sleep(5)",
            "SELECT * FROM t WHERE a IN (SELECT pg_sleep_for('5 minutes'))", "SELECT pg_catalog.pg_sleep(1)",
            "SELECT \"pg_sleep\"(1)", "SELECT pg_read_file('/etc/passwd')", "SELECT lo_import('/etc/passwd')",
            "SELECT dblink('host=x', 'select 1')", "SELECT pg_terminate_backend(123)", "SELECT set_config('a','b',false)",
            "SELECT nextval('s')", "SELECT setval('s', 1)", "SELECT pg_advisory_lock(1)", "SELECT txid_current()",
            "SELECT query_to_xml('select 1', true, true, '')", "SELECT * FROM pg_ls_dir('.')"})
    void forbiddenPostgresFunctions(String sql) {
        assertRejected(pg(sql), DbQuerySqlGuard.SQL_FORBIDDEN_FUNCTION);
    }

    @Test
    void forbiddenOracleAndMssqlFunctions() {
        assertRejected(ora("SELECT DBMS_LOCK.SLEEP(10) FROM dual"), DbQuerySqlGuard.SQL_FORBIDDEN_FUNCTION);
        assertRejected(ora("SELECT SYS.DBMS_SESSION.SLEEP(1) FROM dual"), DbQuerySqlGuard.SQL_FORBIDDEN_FUNCTION);
        assertRejected(ora("SELECT UTL_HTTP.REQUEST('http://x') FROM dual"), DbQuerySqlGuard.SQL_FORBIDDEN_FUNCTION);
        assertRejected(ora("SELECT HTTPURITYPE('http://x').getclob() FROM dual"), DbQuerySqlGuard.SQL_FORBIDDEN_FUNCTION);
        assertRejected(ora("SELECT my_pkg.do_it(1) FROM dual"), DbQuerySqlGuard.SQL_FORBIDDEN_FUNCTION);
        assertRejected(ora("SELECT my_seq.NEXTVAL FROM dual"), DbQuerySqlGuard.SQL_FORBIDDEN_FUNCTION);
        assertRejected(ms("SELECT dbo.fn_calc(a) FROM dbo.t WITH (NOLOCK)"), DbQuerySqlGuard.SQL_FORBIDDEN_FUNCTION);
        assertRejected(ms("SELECT NEXT VALUE FOR dbo.s"), DbQuerySqlGuard.SQL_FORBIDDEN_FUNCTION);
        assertRejected(ms("SELECT * FROM OPENROWSET('SQLNCLI', 'x', 'select 1')"), DbQuerySqlGuard.SQL_FORBIDDEN_KEYWORD);
        assertRejected(ms("SELECT * FROM OPENQUERY(srv, 'select 1')"), DbQuerySqlGuard.SQL_FORBIDDEN_KEYWORD);
        assertRejected(ms("SELECT 1 WAITFOR DELAY '00:00:10'"), DbQuerySqlGuard.SQL_FORBIDDEN_KEYWORD);
        assertRejected(ms("SELECT xp_cmdshell('dir')"), DbQuerySqlGuard.SQL_FORBIDDEN_FUNCTION);
        assertRejected(pg("SELECT benchmark(1000000, md5('x'))"), DbQuerySqlGuard.SQL_FORBIDDEN_FUNCTION);
        // serbest: yerleşik fonksiyonlar, XML veri türü yöntemleri
        assertOk(ms("SELECT ISNULL(a, 0), DATEADD(day, -1, GETDATE()), x.value('(/a)[1]', 'int') FROM dbo.t WITH (NOLOCK)"));
        assertOk(ora("SELECT NVL(a, 0), TO_CHAR(SYSDATE, 'YYYY'), SYS_CONTEXT('USERENV', 'DB_NAME') FROM dual"));
        assertOk(pg("SELECT lower(a), coalesce(b, 0), pg_catalog.lower(c), length(d) FROM t"));
    }

    @Test
    void jdbcEscapeSyntaxRejected() {
        assertRejected(pg("SELECT {fn now()}"), DbQuerySqlGuard.SQL_FORBIDDEN_KEYWORD);
        assertRejected(ms("SELECT 1 FROM t WITH (NOLOCK) WHERE a = {call x}"), DbQuerySqlGuard.SQL_FORBIDDEN_KEYWORD);
        assertOk(pg("SELECT '{1,2}'::int[] FROM t"));
    }

    @Test
    void oracleDbLinkRejected() {
        assertRejected(ora("SELECT * FROM orders@remote_db"), DbQuerySqlGuard.SQL_REMOTE_OBJECT);
        assertOk(ora("SELECT \"a@b\" FROM t"));
    }

    // ── MSSQL NOLOCK ─────────────────────────────────────────────────────────

    @Test
    void mssqlEveryTableNeedsNolock() {
        assertRejected(ms("SELECT * FROM dbo.orders"), DbQuerySqlGuard.SQL_MSSQL_NOLOCK_REQUIRED);
        Result join = ms("SELECT * FROM dbo.orders o WITH (NOLOCK) JOIN dbo.items i ON i.oid = o.id");
        assertRejected(join, DbQuerySqlGuard.SQL_MSSQL_NOLOCK_REQUIRED);
        assertThat(join.violations()).extracting(Violation::token).containsExactly("dbo.items");
        assertRejected(ms("SELECT * FROM t WITH (NOLOCK) WHERE x IN (SELECT y FROM u)"),
                DbQuerySqlGuard.SQL_MSSQL_NOLOCK_REQUIRED);
        assertRejected(ms("SELECT (SELECT max(b) FROM u) FROM t WITH (NOLOCK)"), DbQuerySqlGuard.SQL_MSSQL_NOLOCK_REQUIRED);
        assertRejected(ms("SELECT * FROM t WITH (NOLOCK) OUTER APPLY (SELECT TOP 1 * FROM u WHERE u.a = t.a) z"),
                DbQuerySqlGuard.SQL_MSSQL_NOLOCK_REQUIRED);
        assertRejected(ms("SELECT a FROM t WITH (NOLOCK) UNION ALL SELECT a FROM u"), DbQuerySqlGuard.SQL_MSSQL_NOLOCK_REQUIRED);
        assertOk(ms("SELECT COUNT(*) AS n FROM dbo.orders o WITH (NOLOCK) INNER JOIN dbo.items i WITH (NOLOCK) "
                + "ON i.oid = o.id LEFT JOIN dbo.x WITH (NOLOCK) ON 1 = 1"));
        assertOk(ms("SELECT * FROM t WITH (NOLOCK) OUTER APPLY (SELECT TOP 1 * FROM u WITH (NOLOCK) WHERE u.a = t.a) z"));
        assertOk(ms("SELECT * FROM (SELECT * FROM t WITH (NOLOCK)) d"));
    }

    @Test
    void mssqlHintForms() {
        assertOk(ms("SELECT * FROM dbo.t (NOLOCK)"));                       // eski biçim
        assertOk(ms("SELECT * FROM dbo.t AS a WITH (READUNCOMMITTED)"));
        assertOk(ms("SELECT * FROM dbo.t a WITH (NOLOCK, READUNCOMMITTED)"));
        assertOk(ms("SELECT * FROM dbo.t with ( nolock )"));
        assertRejected(ms("SELECT * FROM dbo.t WITH (NOLOCK, UPDLOCK)"), DbQuerySqlGuard.SQL_LOCK_HINT);
        assertRejected(ms("SELECT * FROM dbo.t WITH (UPDLOCK)"), DbQuerySqlGuard.SQL_LOCK_HINT);
        assertRejected(ms("SELECT * FROM dbo.t (TABLOCKX)"), DbQuerySqlGuard.SQL_LOCK_HINT);
        assertRejected(ms("SELECT * FROM dbo.t WITH (NOLOCK, INDEX(ix_a))"), DbQuerySqlGuard.SQL_LOCK_HINT);
        assertRejected(ms("SELECT * FROM dbo.t WITH (HOLDLOCK)"), DbQuerySqlGuard.SQL_LOCK_HINT);
        assertRejected(ms("SELECT * FROM dbo.t WITH (NOLOCK) OPTION (TABLE HINT(t, XLOCK))"), DbQuerySqlGuard.SQL_LOCK_HINT);
    }

    @Test
    void mssqlCteScope() {
        assertOk(ms("WITH c AS (SELECT a FROM dbo.t WITH (NOLOCK)) SELECT a FROM c"));
        assertOk(ms("WITH c AS (SELECT a FROM dbo.t WITH (NOLOCK)), d AS (SELECT a FROM c) SELECT * FROM d JOIN c ON 1=1"));
        assertRejected(ms("WITH c AS (SELECT a FROM dbo.t) SELECT a FROM c"), DbQuerySqlGuard.SQL_MSSQL_NOLOCK_REQUIRED);
        // CTE adı yalnız tanımlandığı kapsamda muaf: dıştaki x GERÇEK tablodur
        Result outer = ms("SELECT * FROM (WITH x AS (SELECT 1 a) SELECT * FROM x) q CROSS JOIN x");
        assertRejected(outer, DbQuerySqlGuard.SQL_MSSQL_NOLOCK_REQUIRED);
        // ileriye başvuru: a'nın gövdesindeki b henüz tanımsız → gerçek tablo
        assertRejected(ms("WITH a AS (SELECT * FROM b), b AS (SELECT 1 x) SELECT * FROM a"),
                DbQuerySqlGuard.SQL_MSSQL_NOLOCK_REQUIRED);
        // nitelikli ad CTE olamaz
        assertRejected(ms("WITH c AS (SELECT 1 a) SELECT * FROM dbo.c"), DbQuerySqlGuard.SQL_MSSQL_NOLOCK_REQUIRED);
    }

    @Test
    void mssqlTableFunctionsAndLinkedServers() {
        assertRejected(ms("SELECT * FROM dbo.t WITH (NOLOCK) CROSS APPLY dbo.fn(t.id) f"),
                DbQuerySqlGuard.SQL_FORBIDDEN_FUNCTION);
        assertRejected(ms("SELECT * FROM STRING_SPLIT('a,b', ',')"), DbQuerySqlGuard.SQL_FORBIDDEN_FUNCTION);
        assertRejected(ms("SELECT * FROM srv.db.dbo.t WITH (NOLOCK)"), DbQuerySqlGuard.SQL_REMOTE_OBJECT);
        assertRejected(ms("SELECT * FROM [srv].[db].[dbo].[t] WITH (NOLOCK)"), DbQuerySqlGuard.SQL_REMOTE_OBJECT);
        assertOk(ms("SELECT * FROM db.dbo.t WITH (NOLOCK)"));
        // PostgreSQL'de tablo fonksiyonu serbest (yasak listesindeki adlar hariç)
        assertOk(pg("SELECT * FROM generate_series(1, 10) g"));
    }

    // ── ayrıştırma ve tablo listesi ──────────────────────────────────────────

    @Test
    void unparsableIsRejectedWithPosition() {
        Result r = pg("SELECT a FROM t WHERE");
        assertRejected(r, DbQuerySqlGuard.SQL_UNPARSABLE);
        Result r2 = pg("SELECT a\n/* yorum */ FROM t\nWHERE b ==== 1");
        assertRejected(r2, DbQuerySqlGuard.SQL_UNPARSABLE);
        assertThat(r2.violations().get(0).line()).isEqualTo(3);
        assertThat(r2.violations().get(0).column()).isEqualTo(10);
        // MSSQL ipucu dönüşümü konumu kaydırmaz: hata kaynak metindeki sütunda raporlanır
        Result r3 = ms("SELECT a FROM t (NOLOCK) WHERE b ==== 1");
        assertRejected(r3, DbQuerySqlGuard.SQL_UNPARSABLE);
        assertThat(r3.violations().get(0).line()).isEqualTo(1);
        assertThat(r3.violations().get(0).column()).isGreaterThanOrEqualTo(34);
    }

    @Test
    void tablesAreCollected() {
        Result r = pg("WITH c AS (SELECT a FROM Sales.Orders) SELECT * FROM c JOIN public.\"Items\" i ON 1=1 "
                + "WHERE EXISTS (SELECT 1 FROM audit_log)");
        assertOk(r);
        assertThat(r.tables()).containsExactlyInAnyOrder("sales.orders", "public.items", "audit_log");
        Result m = ms("SELECT * FROM [dbo].[Order Lines] WITH (NOLOCK)");
        assertThat(m.tables()).containsExactly("dbo.order lines");
    }

    @Test
    void violationCarriesPositionAndMessage() {
        Result r = pg("SELECT 1\nFROM t\nWHERE x = 1 OR DELETE");
        Violation v = r.violations().stream().filter(x -> x.code().equals(DbQuerySqlGuard.SQL_FORBIDDEN_KEYWORD))
                .findFirst().orElseThrow();
        assertThat(v.token()).isEqualTo("DELETE");
        assertThat(v.line()).isEqualTo(3);
        assertThat(v.column()).isEqualTo(16);
        assertThat(v.message()).contains("DELETE").contains("satır 3");
    }

    @Test
    void lengthAndEmpty() {
        assertRejected(pg(""), DbQuerySqlGuard.SQL_EMPTY);
        assertRejected(pg("   -- yalnız yorum\n /* x */ "), DbQuerySqlGuard.SQL_EMPTY);
        assertRejected(pg(null), DbQuerySqlGuard.SQL_EMPTY);
        assertRejected(pg("SELECT 1 " + "x".repeat(DbQuerySqlGuard.MAX_SQL_CHARS)), DbQuerySqlGuard.SQL_TOO_LONG);
    }

    @Test
    void tooDeepNesting() {
        String sql = "SELECT " + "(".repeat(60) + "1" + ")".repeat(60);
        assertRejected(pg(sql), DbQuerySqlGuard.SQL_UNPARSABLE);
    }

    @Test
    void maliciousLongInputIsFast() {
        // Doğrusal zaman: 10 000 karakterlik kötü niyetli girdiler (çok tırnak, çok yorum, çok parantez, çok sözcük)
        String[] inputs = {
                "SELECT " + "'a'||".repeat(1_990) + "'a'",
                "SELECT 1 " + "/* x */".repeat(1_400),
                "SELECT " + "a,".repeat(4_990) + "a",
                "SELECT 1 FROM t WHERE " + "x = 1 OR ".repeat(1_100) + "1 = 1",
                "SELECT " + "-".repeat(9_990),
                "SELECT $$" + "$".repeat(9_990),
        };
        for (String s : inputs) {
            String sql = s.length() > DbQuerySqlGuard.MAX_SQL_CHARS ? s.substring(0, DbQuerySqlGuard.MAX_SQL_CHARS) : s;
            assertTimeoutPreemptively(java.time.Duration.ofSeconds(5), () -> {
                long t0 = System.nanoTime();
                DbQuerySqlGuard.check(sql, POSTGRESQL);
                DbQuerySqlGuard.check(sql, MSSQL);
                DbQuerySqlGuard.check(sql, ORACLE);
                return (System.nanoTime() - t0) / 1_000_000;
            });
        }
        // sözcük katmanı tek başına < 200 ms
        String worst = "SELECT 1 FROM t WHERE " + "/* a */ 'b' \"c\" -- d\n".repeat(400);
        String w = worst.substring(0, Math.min(worst.length(), DbQuerySqlGuard.MAX_SQL_CHARS));
        long t0 = System.nanoTime();
        for (int i = 0; i < 10; i++) SqlLexer.lex(w, POSTGRESQL);
        assertThat((System.nanoTime() - t0) / 1_000_000 / 10).isLessThan(200);
    }

    @Test
    void executableSqlStripsTrailingSemicolon() {
        assertThat(DbQuerySqlGuard.executableSql("SELECT 1 FROM dual;", ORACLE)).isEqualTo("SELECT 1 FROM dual");
        assertThat(DbQuerySqlGuard.executableSql("SELECT ';' FROM dual ; -- x", ORACLE)).isEqualTo("SELECT ';' FROM dual  -- x");
        assertThat(DbQuerySqlGuard.executableSql("SELECT 1", ORACLE)).isEqualTo("SELECT 1");
    }

    @Test
    void englishMessages() {
        org.springframework.mock.web.MockHttpServletRequest req = new org.springframework.mock.web.MockHttpServletRequest();
        req.addHeader("X-Lang", "en");
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                new org.springframework.web.context.request.ServletRequestAttributes(req));
        try {
            Result r = pg("SELECT 1; DROP TABLE t");
            assertThat(r.violations()).allSatisfy(v -> assertThat(v.message()).doesNotContain("satır"));
            assertThat(r.violations().get(0).message()).contains("line 1");
        } finally {
            org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
        }
    }
}
