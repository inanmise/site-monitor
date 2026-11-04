package com.sitemonitor.service.dbquery;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tek tıkla güvenli düzeltme (2026-11-05, dbq2 B2): ADD_NOLOCK (MSSQL, yalnız gerçek tablo başvurularına, takma addan sonra)
 * ve ADD_ROW_LIMIT (PG LIMIT / Oracle FETCH FIRST / MSSQL TOP). Değişmezler: önerilen HER sorgu gerçek güvenlik denetiminden
 * GEÇER; yorum / metin / tırnaklı ad içine ekleme yapılmaz; CTE adı ve türetilmiş tablo dokunulmaz; geçmeyen öneri dönmez.
 */
class DbQuerySqlFixTest {

    /** Öneri (yoksa null); varsa eklemeler geri alınınca ÖZGÜN sorgu çıkmalı (konumlar özgün metne göre). */
    private static DbQuerySqlFix.Fix fix(String sql, DbType type, String code) {
        DbQuerySqlFix.Fix f = DbQuerySqlFix.fixes(sql, type, 1000).fixes().stream().filter(x -> code.equals(x.code()))
                .findFirst().orElse(null);
        if (f != null) assertThat(originalOf(f)).isEqualTo(sql);
        return f;
    }

    private static DbQuerySqlFix.Skipped skipped(String sql, DbType type, String code) {
        return DbQuerySqlFix.fixes(sql, type, 1000).skipped().stream().filter(f -> code.equals(f.code())).findFirst().orElse(null);
    }

    private static void passesGuard(DbQuerySqlFix.Fix f, DbType type) {
        assertThat(f).isNotNull();
        DbQuerySqlGuard.Result g = DbQuerySqlGuard.check(f.sql(), type);
        assertThat(g.ok()).as(f.sql() + " → " + g.violations()).isTrue();
        assertThat(DbQuerySqlFix.apply(originalOf(f), f.edits())).isEqualTo(f.sql());
        assertThat(f.note()).isNotBlank();
        assertThat(f.changed()).isNotEmpty();
    }

    /** Eklemeleri geri alarak özgün sorgu (kenar testi: edits gerçekten özgün konumlarda). */
    private static String originalOf(DbQuerySqlFix.Fix f) {
        String s = f.sql();
        List<DbQuerySqlFix.Edit> edits = new java.util.ArrayList<>(f.edits());
        edits.sort(java.util.Comparator.comparingInt(DbQuerySqlFix.Edit::at));
        // Artan sırada: önceki eklemeler silindikçe ön ek özgünle aynı olur — sıradaki ekleme özgün konumundadır
        StringBuilder sb = new StringBuilder(s);
        for (DbQuerySqlFix.Edit e : edits) sb.delete(e.at(), e.at() + e.text().length());
        return sb.toString();
    }

    @Test
    @DisplayName("NOLOCK: tek tablo, şema + köşeli parantez, takma ad (AS'li / AS'siz), JOIN ve FROM listesi — ipucu takma addan SONRA")
    void nolockBasics() {
        DbQuerySqlFix.Fix f = fix("SELECT COUNT(*) FROM dbo.siparis WHERE durum = 'BEKLIYOR'", DbType.MSSQL, "ADD_NOLOCK");
        passesGuard(f, DbType.MSSQL);
        assertThat(f.sql()).isEqualTo("SELECT COUNT(*) FROM dbo.siparis WITH (NOLOCK) WHERE durum = 'BEKLIYOR'");
        assertThat(f.changed()).containsExactly("dbo.siparis");
        assertThat(f.edits()).singleElement().satisfies(e -> {
            assertThat(e.at()).isEqualTo("SELECT COUNT(*) FROM dbo.siparis".length());
            assertThat(e.line()).isEqualTo(1);
            assertThat(e.column()).isEqualTo("SELECT COUNT(*) FROM dbo.siparis".length() + 1);
            assertThat(e.text()).isEqualTo(" WITH (NOLOCK)");
        });

        DbQuerySqlFix.Fix join = fix("SELECT s.id FROM [dbo].[Siparis] AS s\n  JOIN dbo.musteri m ON m.id = s.musteri_id,\n"
                + "  dbo.bolge b WHERE b.id = m.bolge_id", DbType.MSSQL, "ADD_NOLOCK");
        passesGuard(join, DbType.MSSQL);
        assertThat(join.sql()).contains("[dbo].[Siparis] AS s WITH (NOLOCK)").contains("dbo.musteri m WITH (NOLOCK) ON")
                .contains("dbo.bolge b WITH (NOLOCK) WHERE");   // ON'dan sonraki virgüllü tablo da FROM listesindedir
        assertThat(join.changed()).containsExactly("[dbo].[Siparis]", "dbo.musteri", "dbo.bolge");
    }

    @Test
    @DisplayName("NOLOCK: virgüllü FROM listesi ve alt sorgulardaki tablolar; zaten NOLOCK'lu başvuru tekrar edilmez")
    void nolockListAndSubqueries() {
        DbQuerySqlFix.Fix f = fix("SELECT a.x FROM dbo.a a, dbo.b WITH (NOLOCK), dbo.c c "
                + "WHERE a.id IN (SELECT id FROM dbo.d WHERE k = 1) AND EXISTS (SELECT 1 FROM dbo.e e WHERE e.a = a.id)",
                DbType.MSSQL, "ADD_NOLOCK");
        passesGuard(f, DbType.MSSQL);
        assertThat(f.changed()).containsExactly("dbo.a", "dbo.c", "dbo.d", "dbo.e");
        assertThat(f.sql()).contains("dbo.b WITH (NOLOCK),").doesNotContain("WITH (NOLOCK) WITH (NOLOCK)");
    }

    @Test
    @DisplayName("NOLOCK: CTE adı ve türetilmiş tablo dokunulmaz; CTE gövdesindeki gerçek tablo düzeltilir")
    void nolockCteAndDerived() {
        DbQuerySqlFix.Fix f = fix("WITH son AS (SELECT id, tutar FROM dbo.siparis WHERE tarih > '2026-01-01')\n"
                + "SELECT COUNT(*) FROM son JOIN (SELECT id FROM dbo.iade) i ON i.id = son.id", DbType.MSSQL, "ADD_NOLOCK");
        passesGuard(f, DbType.MSSQL);
        assertThat(f.changed()).containsExactly("dbo.siparis", "dbo.iade");
        assertThat(f.sql()).contains("FROM son JOIN").doesNotContain("son WITH (NOLOCK)");
    }

    @Test
    @DisplayName("NOLOCK: yorum, metin sabiti ve tırnaklı ad içindeki FROM'a dokunulmaz")
    void nolockIgnoresCommentsAndStrings() {
        String sql = "-- FROM dbo.yorum\nSELECT 'FROM dbo.metin' AS [FROM x], id /* FROM dbo.blok */ FROM dbo.gercek";
        DbQuerySqlFix.Fix f = fix(sql, DbType.MSSQL, "ADD_NOLOCK");
        passesGuard(f, DbType.MSSQL);
        assertThat(f.changed()).containsExactly("dbo.gercek");
        assertThat(f.sql()).isEqualTo(sql + " WITH (NOLOCK)");
    }

    @Test
    @DisplayName("NOLOCK: NOLOCK dışı ipucu (INDEX) → öneri yok, neden OTHER_HINT; zaten tamam → NOTHING_TO_FIX; PG → NOT_MSSQL")
    void nolockSkips() {
        DbQuerySqlFix.Skipped other = skipped("SELECT id FROM dbo.a WITH (INDEX(ix_a)) WHERE x = 1", DbType.MSSQL, "ADD_NOLOCK");
        assertThat(other.reason()).isIn("OTHER_HINT", "GUARD_STILL_FAILS");
        assertThat(fix("SELECT id FROM dbo.a WITH (INDEX(ix_a)) WHERE x = 1", DbType.MSSQL, "ADD_NOLOCK")).isNull();
        assertThat(skipped("SELECT id FROM dbo.a WITH (NOLOCK)", DbType.MSSQL, "ADD_NOLOCK").reason()).isEqualTo("NOTHING_TO_FIX");
        assertThat(skipped("SELECT id FROM a", DbType.POSTGRESQL, "ADD_NOLOCK").reason()).isEqualTo("NOT_MSSQL");
        // başka bir ihlal (yasak fonksiyon) varken NOLOCK eklense de denetimi geçmez → öneri yok
        String other2 = "SELECT id FROM dbo.a WHERE x = xp_cmdshell('dir')";
        assertThat(fix(other2, DbType.MSSQL, "ADD_NOLOCK")).isNull();
        assertThat(skipped(other2, DbType.MSSQL, "ADD_NOLOCK").reason()).isEqualTo("GUARD_STILL_FAILS");
    }

    @Test
    @DisplayName("NOLOCK: FOR XML / JSON'dan önce eklenir; FOR SYSTEM_TIME (zamansal) başvurusuna dokunulmaz")
    void nolockForClauses() {
        DbQuerySqlFix.Fix xml = fix("SELECT kod FROM dbo.t FOR XML PATH('k')", DbType.MSSQL, "ADD_NOLOCK");
        passesGuard(xml, DbType.MSSQL);
        assertThat(xml.sql()).isEqualTo("SELECT kod FROM dbo.t WITH (NOLOCK) FOR XML PATH('k')");
        assertThat(fix("SELECT kod FROM dbo.t FOR SYSTEM_TIME AS OF '2026-01-01'", DbType.MSSQL, "ADD_NOLOCK")).isNull();
    }

    @Test
    @DisplayName("NOLOCK: 25'ten çok tablo (denetim ihlal listesi kesilir) — yeniden denetimle tümü düzeltilir")
    void nolockManyTables() {
        StringBuilder sb = new StringBuilder("SELECT 1 FROM dbo.t0");
        for (int i = 1; i < 40; i++) sb.append(" JOIN dbo.t").append(i).append(" ON 1 = 1");
        DbQuerySqlFix.Fix f = fix(sb.toString(), DbType.MSSQL, "ADD_NOLOCK");
        passesGuard(f, DbType.MSSQL);
        assertThat(f.changed()).hasSize(40);
    }

    @Test
    @DisplayName("satır sınırı: PG LIMIT ve Oracle FETCH FIRST sona (noktalı virgül / yorumdan önce); MSSQL TOP ana SELECT'e (DISTINCT'ten sonra)")
    void rowLimit() {
        DbQuerySqlFix.Fix pg = fix("SELECT id FROM t ORDER BY id; -- son", DbType.POSTGRESQL, "ADD_ROW_LIMIT");
        passesGuard(pg, DbType.POSTGRESQL);
        assertThat(pg.sql()).isEqualTo("SELECT id FROM t ORDER BY id LIMIT 1000; -- son");
        assertThat(pg.changed()).containsExactly("LIMIT 1000");
        assertThat(pg.note()).contains("1000");

        DbQuerySqlFix.Fix ora = fix("SELECT id FROM t\nORDER BY id\n-- yorum", DbType.ORACLE, "ADD_ROW_LIMIT");
        passesGuard(ora, DbType.ORACLE);
        assertThat(ora.sql()).isEqualTo("SELECT id FROM t\nORDER BY id FETCH FIRST 1000 ROWS ONLY\n-- yorum");
        assertThat(ora.edits().get(0).line()).isEqualTo(2);

        DbQuerySqlFix.Fix ms = fix("SELECT DISTINCT kod FROM dbo.t WITH (NOLOCK)", DbType.MSSQL, "ADD_ROW_LIMIT");
        passesGuard(ms, DbType.MSSQL);
        assertThat(ms.sql()).isEqualTo("SELECT DISTINCT TOP (1000) kod FROM dbo.t WITH (NOLOCK)");

        DbQuerySqlFix.Fix cte = fix("WITH x AS (SELECT id FROM t LIMIT 5) SELECT * FROM x", DbType.POSTGRESQL, "ADD_ROW_LIMIT");
        passesGuard(cte, DbType.POSTGRESQL);
        assertThat(cte.sql()).endsWith("FROM x LIMIT 1000");   // CTE gövdesindeki LIMIT dış sorguyu sınırlamaz

        DbQuerySqlFix.Fix msCte = fix("WITH x AS (SELECT TOP (5) id FROM dbo.t WITH (NOLOCK)) SELECT id FROM x", DbType.MSSQL,
                "ADD_ROW_LIMIT");
        passesGuard(msCte, DbType.MSSQL);
        assertThat(msCte.sql()).contains(") SELECT TOP (1000) id FROM x");
    }

    @Test
    @DisplayName("satır sınırı: zaten sınırlı (LIMIT / FETCH / OFFSET / TOP / ROWNUM) → ALREADY_LIMITED; MSSQL birleşimi / parantezli → NOT_LIMITABLE")
    void rowLimitSkips() {
        assertThat(skipped("SELECT id FROM t LIMIT 10", DbType.POSTGRESQL, "ADD_ROW_LIMIT").reason()).isEqualTo("ALREADY_LIMITED");
        assertThat(skipped("SELECT id FROM t FETCH FIRST 5 ROWS ONLY", DbType.ORACLE, "ADD_ROW_LIMIT").reason()).isEqualTo("ALREADY_LIMITED");
        assertThat(skipped("SELECT id FROM t WHERE ROWNUM <= 5", DbType.ORACLE, "ADD_ROW_LIMIT").reason()).isEqualTo("ALREADY_LIMITED");
        assertThat(skipped("SELECT TOP 10 PERCENT id FROM dbo.t WITH (NOLOCK)", DbType.MSSQL, "ADD_ROW_LIMIT").reason())
                .isEqualTo("ALREADY_LIMITED");
        assertThat(skipped("SELECT id FROM dbo.t WITH (NOLOCK) ORDER BY id OFFSET 0 ROWS", DbType.MSSQL, "ADD_ROW_LIMIT").reason())
                .isEqualTo("ALREADY_LIMITED");
        assertThat(skipped("SELECT id FROM dbo.a WITH (NOLOCK) UNION SELECT id FROM dbo.b WITH (NOLOCK)", DbType.MSSQL,
                "ADD_ROW_LIMIT").reason()).isEqualTo("NOT_LIMITABLE");
        // PG'de birleşimin sonuna eklenen LIMIT tüm sonucu keser — önerilir
        DbQuerySqlFix.Fix union = fix("SELECT id FROM a UNION SELECT id FROM b", DbType.POSTGRESQL, "ADD_ROW_LIMIT");
        passesGuard(union, DbType.POSTGRESQL);
        // MSSQL'de NOLOCK eksikken satır sınırı tek başına denetimi geçemez → önerilmez (her düzeltme bağımsız)
        assertThat(skipped("SELECT id FROM dbo.t", DbType.MSSQL, "ADD_ROW_LIMIT").reason()).isEqualTo("GUARD_STILL_FAILS");
    }

    @Test
    @DisplayName("çözümlenemeyen / boş / çok uzun sorgu: iki düzeltme de UNPARSABLE; satır sınırı girdisi aralık denetimi")
    void unparsableAndLimits() {
        assertThat(DbQuerySqlFix.fixes("SELECT 'açık", DbType.POSTGRESQL, 10).skipped())
                .extracting(DbQuerySqlFix.Skipped::reason).containsOnly("UNPARSABLE");
        assertThat(DbQuerySqlFix.fixes("   ", DbType.MSSQL, 10).fixes()).isEmpty();
        assertThat(DbQuerySqlFix.fixes("SELECT 1 " + "x".repeat(DbQuerySqlGuard.MAX_SQL_CHARS), DbType.MSSQL, 10).fixes()).isEmpty();
        assertThat(DbQuerySqlFix.rowLimitOrNull(null)).isEqualTo(1000);
        assertThat(DbQuerySqlFix.rowLimitOrNull("")).isEqualTo(1000);
        assertThat(DbQuerySqlFix.rowLimitOrNull(50)).isEqualTo(50);
        assertThat(DbQuerySqlFix.rowLimitOrNull("250")).isEqualTo(250);
        assertThat(DbQuerySqlFix.rowLimitOrNull(0)).isNull();
        assertThat(DbQuerySqlFix.rowLimitOrNull(1_000_001)).isNull();
        assertThat(DbQuerySqlFix.rowLimitOrNull("çok")).isNull();
        DbQuerySqlFix.Fix five = DbQuerySqlFix.fixes("SELECT id FROM t", DbType.POSTGRESQL, 5).fixes().get(0);
        assertThat(five.sql()).isEqualTo("SELECT id FROM t LIMIT 5");
    }

    @Test
    @DisplayName("ad normalleştirme: köşeli / çift tırnak ve kaçışları, büyük-küçük harf")
    void normalize() {
        assertThat(DbQuerySqlFix.normalize("[dbo].[Siparis]")).isEqualTo("dbo.siparis");
        assertThat(DbQuerySqlFix.normalize("\"dbo\".\"Siparis\"")).isEqualTo("dbo.siparis");
        assertThat(DbQuerySqlFix.normalize("DBO.SIPARIS")).isEqualTo("dbo.siparis");
        assertThat(DbQuerySqlFix.normalize("[a]]b]")).isEqualTo("a]b");
        assertThat(DbQuerySqlFix.normalize(null)).isEmpty();
    }

    @Test
    @DisplayName("sınırlı süre: kötü niyetli uzun girdi (çok virgül / parantez) doğrusal biter")
    void bounded() {
        StringBuilder sb = new StringBuilder("SELECT 1 FROM dbo.a");
        while (sb.length() < DbQuerySqlGuard.MAX_SQL_CHARS - 20) sb.append(", dbo.a");
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(20),
                () -> DbQuerySqlFix.fixes(sb.toString(), DbType.MSSQL, 10));
        StringBuilder deep = new StringBuilder("SELECT ");
        for (int i = 0; i < 2000; i++) deep.append('(');
        deep.append('1');
        for (int i = 0; i < 2000; i++) deep.append(')');
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(20),
                () -> DbQuerySqlFix.fixes(deep.toString(), DbType.MSSQL, 10));
    }
}
