package com.sitemonitor.service.dbquery;

import com.sitemonitor.service.dbquery.DbQuerySqlGuard.Result;
import com.sitemonitor.service.dbquery.DbQuerySqlGuard.Violation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.sitemonitor.service.dbquery.DbType.MSSQL;
import static com.sitemonitor.service.dbquery.DbType.ORACLE;
import static com.sitemonitor.service.dbquery.DbType.POSTGRESQL;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bağımsız güvenlik incelemesinin (2026-10-31) düzeltmeleri ve önerdiği regresyon girdileri: MSSQL XML yöntemi muafiyeti
 * yalnız sorgunun tablosu / takma adıyla nitelenmiş kolonda (O3), salt-okur yerleşik tablo fonksiyonları OPENJSON /
 * STRING_SPLIT serbest, diğerleri yasak; Oracle tek parçalı fonksiyon (bilinen sınır), q-tırnak, {@code ]]} kaçışı, toplu-iş
 * ayırıcı, veri değiştiren CTE biçimleri, görünmez / boşluk karakterleri.
 */
class DbQuerySqlGuardReviewTest {

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

    // ── O3: MSSQL XML yöntemi niteleyicisi ───────────────────────────────────

    @Test
    void xmlMethodNeedsAQueryObjectQualifier() {
        // ad çakışmalı kullanıcı fonksiyonları (şema.value / şema.nodes) — REDDEDİLİR
        assertRejected(ms("SELECT dbo.value(x) FROM t WITH (NOLOCK)"), DbQuerySqlGuard.SQL_FORBIDDEN_FUNCTION);
        assertRejected(ms("SELECT app.nodes(y) FROM t WITH (NOLOCK)"), DbQuerySqlGuard.SQL_FORBIDDEN_FUNCTION);
        assertRejected(ms("SELECT app.query(y), app.exist(y) FROM t WITH (NOLOCK)"), DbQuerySqlGuard.SQL_FORBIDDEN_FUNCTION);
        // iki parçalı kolon.yöntem kolonun mu şemanın mı olduğu metinden bilinemez → tablo / takma adla yazılmalı
        Result two = ms("SELECT c.value('(/a)[1]', 'int') FROM dbo.t WITH (NOLOCK)");
        assertRejected(two, DbQuerySqlGuard.SQL_FORBIDDEN_FUNCTION);
        assertThat(two.violations().get(0).message()).contains("takma ad");
        // üç parçalı: niteleyici veritabanı / şema adıysa (sorgunun nesnesi değil) veritabanı.şema.fonksiyon olabilir
        assertRejected(ms("SELECT otherdb.dbo.value(x) FROM dbo.t WITH (NOLOCK)"), DbQuerySqlGuard.SQL_FORBIDDEN_FUNCTION);
        assertRejected(ms("SELECT dbo.x.value(1) FROM dbo.t WITH (NOLOCK)"), DbQuerySqlGuard.SQL_FORBIDDEN_FUNCTION);
        // gerçek XML yöntemleri: tablo adı, takma ad, köşeli parantezli ad, CTE
        assertOk(ms("SELECT t.c.value('(/a)[1]', 'int') FROM t WITH (NOLOCK)"));
        assertOk(ms("SELECT x.c.value('(/a)[1]', 'int'), x.c.exist('/a') FROM dbo.t x WITH (NOLOCK)"));
        assertOk(ms("SELECT [x].[c].query('/a') FROM dbo.t AS [x] WITH (NOLOCK)"));
        assertOk(ms("WITH d AS (SELECT CAST('<a>1</a>' AS xml) AS doc) SELECT d.doc.value('(/a)[1]', 'int') FROM d"));
        assertOk(ms("SELECT T.C.VALUE('(/a)[1]', 'int') FROM dbo.t WITH (NOLOCK)"));
        // XML parçalama: CROSS APPLY t.kolon.nodes(…) — niteleyici sorgunun nesnesi
        assertOk(ms("SELECT n.x.value('.', 'int') FROM dbo.t WITH (NOLOCK) CROSS APPLY t.c.nodes('/a') AS n(x)"));
        assertRejected(ms("SELECT 1 FROM dbo.t WITH (NOLOCK) CROSS APPLY dbo.c.nodes('/a') AS n(x)"),
                DbQuerySqlGuard.SQL_FORBIDDEN_FUNCTION);
    }

    // ── salt-okur yerleşik tablo fonksiyonları ───────────────────────────────

    @Test
    void openJsonAndStringSplitAreAllowed() {
        assertOk(ms("SELECT * FROM OPENJSON('[1,2]')"));
        assertOk(ms("SELECT value FROM STRING_SPLIT('a,b', ',')"));
        assertOk(ms("SELECT [key], value FROM OPENJSON(N'{\"a\":1}') AS j"));
        assertOk(ms("SELECT s.value FROM dbo.t WITH (NOLOCK) CROSS APPLY STRING_SPLIT(t.c, ',') s"));
        assertOk(ms("SELECT j.id, j.name FROM OPENJSON('[{\"id\":1}]') WITH (id int '$.id', name nvarchar(50) '$.n', "
                + "raw nvarchar(max) '$.r' AS JSON, amount decimal(10, 2)) AS j"));
        assertOk(ms("SELECT j.id FROM dbo.t WITH (NOLOCK) CROSS APPLY OPENJSON(t.payload) WITH (id int '$.id') AS j"));
        // fonksiyonun içindeki tablo okuması yine NOLOCK ister
        assertRejected(ms("SELECT * FROM OPENJSON((SELECT TOP 1 c FROM dbo.t))"), DbQuerySqlGuard.SQL_MSSQL_NOLOCK_REQUIRED);
        assertOk(ms("SELECT * FROM OPENJSON((SELECT TOP 1 c FROM dbo.t WITH (NOLOCK)))"));
        // şema bildiriminde sorgu yok
        assertThat(ms("SELECT * FROM OPENJSON('[1]') WITH (x int, y AS (SELECT 1))").ok()).isFalse();
        // nitelikli ad kullanıcı fonksiyonudur; diğer tablo fonksiyonları yasak kalır
        assertRejected(ms("SELECT * FROM dbo.OPENJSON('[1]')"), DbQuerySqlGuard.SQL_FORBIDDEN_FUNCTION);
        assertRejected(ms("SELECT * FROM dbo.t WITH (NOLOCK) CROSS APPLY dbo.fn(t.id) f"), DbQuerySqlGuard.SQL_FORBIDDEN_FUNCTION);
        assertRejected(ms("SELECT * FROM my_tvf(1)"), DbQuerySqlGuard.SQL_FORBIDDEN_FUNCTION);
        // uzak / dosya okuyan yerleşikler yasak kalır
        assertRejected(ms("SELECT * FROM OPENROWSET('SQLNCLI', 'x', 'select 1')"), DbQuerySqlGuard.SQL_FORBIDDEN_KEYWORD);
        assertRejected(ms("SELECT * FROM OPENQUERY(srv, 'select 1')"), DbQuerySqlGuard.SQL_FORBIDDEN_KEYWORD);
        assertRejected(ms("SELECT * FROM OPENDATASOURCE('SQLNCLI', 'x').db.dbo.t"), DbQuerySqlGuard.SQL_FORBIDDEN_KEYWORD);
        assertRejected(ms("SELECT * FROM OPENXML(1, '/a')"), DbQuerySqlGuard.SQL_FORBIDDEN_KEYWORD);
        // PostgreSQL / Oracle'da OPENJSON yerleşik değil: sıradan fonksiyon kuralları (MSSQL listesi uygulanmaz)
        assertOk(pg("SELECT * FROM generate_series(1, 3) g"));
    }

    // ── inceleme regresyon girdileri ─────────────────────────────────────────

    @Test
    void oracleSinglePartFunctionIsAKnownLimit() {
        // Tek parçalı kullanıcı fonksiyonu yerleşikten ayırt edilemez: bugün GEÇER (en az ayrıcalıklı hesap şart — sınıf notu)
        assertOk(ora("SELECT evil_fn() FROM dual"));
        // paket / şema nitelikli ise reddedilir
        assertRejected(ora("SELECT app.evil_fn() FROM dual"), DbQuerySqlGuard.SQL_FORBIDDEN_FUNCTION);
    }

    @Test
    void oracleQuoteLiterals() {
        // q'[ … ]' kapanmıyor (']' ardından ''' yok)
        assertRejected(ora("SELECT q'[')] ' || x FROM dual"), DbQuerySqlGuard.SQL_UNTERMINATED);
        // q'( … )' : içteki ')' ardından ''' gelmedikçe kapanmaz
        assertOk(ora("SELECT q'(a')b)' FROM dual"));
        assertOk(ora("SELECT nq'#x#' FROM dual"));
        // q-tırnak içindeki yasak sözcük metindir
        assertOk(ora("SELECT q'{DROP TABLE t; DELETE}' FROM dual"));
    }

    @Test
    void mssqlBracketEscapeIsPinnedAsRejected() {
        // ']]' kaçışlı tanımlayıcı: bugün ayrıştırılamaz → reddedilir (yanlış pozitif, güvenli yönde)
        Result r = ms("SELECT [a]]b] FROM t WITH (NOLOCK)");
        assertThat(r.ok()).isFalse();
        assertThat(codes(r)).contains(DbQuerySqlGuard.SQL_UNPARSABLE);
    }

    @Test
    void batchSeparatorThenSecondStatement() {
        assertRejected(ms("SELECT 1 FROM t WITH (NOLOCK) GO DROP TABLE t"), DbQuerySqlGuard.SQL_FORBIDDEN_KEYWORD);
        assertRejected(ms("SELECT 1 FROM t WITH (NOLOCK)\nGO\nDROP TABLE t"), DbQuerySqlGuard.SQL_FORBIDDEN_KEYWORD);
        assertRejected(ora("SELECT 1 FROM dual / DROP TABLE t"), DbQuerySqlGuard.SQL_FORBIDDEN_KEYWORD);
        assertRejected(ora("SELECT 1 FROM dual\n/\nDROP TABLE t"), DbQuerySqlGuard.SQL_FORBIDDEN_KEYWORD);
    }

    @Test
    void modifyingCteForms() {
        assertThat(pg("WITH x AS (INSERT INTO t VALUES (1) RETURNING id) SELECT * FROM x").ok()).isFalse();
        assertRejected(pg("WITH x AS (INSERT INTO t VALUES (1) RETURNING id) SELECT * FROM x"),
                DbQuerySqlGuard.SQL_FORBIDDEN_KEYWORD);
        assertRejected(pg("WITH x AS ((INSERT INTO t VALUES (1) RETURNING id)) SELECT * FROM x"),
                DbQuerySqlGuard.SQL_FORBIDDEN_KEYWORD);
        // yorumla bölünmüş sözcük iki ayrı belirteçtir (veritabanı da UPDATE okumaz) — yine reddedilir
        assertThat(pg("WITH x AS (UPD/**/ATE t SET a = 1 RETURNING id) SELECT * FROM x").ok()).isFalse();
        assertRejected(pg("WITH x AS (DELETE FROM t RETURNING id) SELECT count(*) FROM x"),
                DbQuerySqlGuard.SQL_FORBIDDEN_KEYWORD);
    }

    @Test
    void invisibleAndSpaceCharacters() {
        assertRejected(pg("SELECT 1​ FROM t"), DbQuerySqlGuard.SQL_CONTROL_CHAR);   // sıfır genişlikli boşluk
        assertRejected(pg("SELECT 1\u0000"), DbQuerySqlGuard.SQL_CONTROL_CHAR);          // NUL
        assertRejected(pg("SELECT 1 FROM t"), DbQuerySqlGuard.SQL_CONTROL_CHAR);    // satır ayırıcı
        assertRejected(pg("SELECT 1‮ FROM t"), DbQuerySqlGuard.SQL_CONTROL_CHAR);   // yön değiştirme
        assertOk(pg("SELECT 1"));                                                    // NBSP
        assertOk(pg("SELECT 1 FROM t"));                                            // ince boşluk
        assertOk(pg("SELECT 1 FROM t"));                                       // dar NBSP, şekil boşluğu
        assertOk(pg("SELECT\t1\r\nFROM t"));
    }
}
