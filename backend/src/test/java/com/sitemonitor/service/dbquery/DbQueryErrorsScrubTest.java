package com.sitemonitor.service.dbquery;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hata iletisinden VERİ değeri ayıklama (bağımsız inceleme Y1): bir sorgu değeri hata metnine gömerek sızdıramaz
 * ({@code CAST((SELECT gizli) AS integer)}); kullanıcının yazdığı tanımlayıcılar ({@code column "musteri_no" does not exist})
 * okunur kalır. Üç lehçenin gerçek ileti biçimleri; tırnak kaçırma ve sürücü kesmesi saldırıları.
 */
class DbQueryErrorsScrubTest {

    private static final DbConnectionSpec PG = spec(DbType.POSTGRESQL);
    private static final DbConnectionSpec MS = spec(DbType.MSSQL);
    private static final DbConnectionSpec ORA = spec(DbType.ORACLE);

    private static DbConnectionSpec spec(DbType t) {
        return new DbConnectionSpec(t, "db.example", t.defaultPort(), "app", "SERVICE", null, "appuser", "pw", null,
                false, false, 1_000);
    }

    /** Çalıştırıcının yaptığı gibi: SQL + bağlantı belirteçleri izinli, katı kip istisnadan. */
    private static String scrub(SQLException e, String sql, DbConnectionSpec spec) {
        return DbQueryErrors.scrub(DbQueryErrors.describe(e), DbQueryErrors.allowTokens(sql, spec),
                DbQueryErrors.valueBearing(e, spec.type()));
    }

    // ── PostgreSQL ───────────────────────────────────────────────────────────

    @Test
    void postgresCastOfSecretValueIsRemoved() {
        SQLException e = new SQLException("ERROR: invalid input syntax for type integer: \"TOPSECRET\"\n  Position: 8", "22P02");
        String out = scrub(e, "SELECT CAST((SELECT note FROM dbq.secret) AS integer)", PG);
        assertThat(out).doesNotContain("TOPSECRET").doesNotContain("Position")
                .isEqualTo("ERROR: invalid input syntax for type integer: \"…\"");
        // Türkçe sunucu iletisi (lc_messages = tr)
        SQLException tr = new SQLException("HATA:  integer için geçersiz giriş söz dizimi: \"TOPSECRET\"\n  KONUM: 8", "22P02");
        assertThat(scrub(tr, "SELECT CAST(x AS integer)", PG)).doesNotContain("TOPSECRET").doesNotContain("KONUM");
    }

    @Test
    void postgresIdentifiersStayReadable() {
        SQLException col = new SQLException("ERROR: column \"musteri_no\" does not exist\n  Hint: Perhaps you meant to reference "
                + "the column \"t.musteri_ad\".\n  Position: 8", "42703");
        assertThat(scrub(col, "SELECT musteri_no FROM t", PG)).isEqualTo("ERROR: column \"musteri_no\" does not exist");
        SQLException rel = new SQLException("ERROR: relation \"dbq_it.yok\" does not exist", "42P01");
        assertThat(scrub(rel, "SELECT * FROM dbq_it.yok", PG)).isEqualTo("ERROR: relation \"dbq_it.yok\" does not exist");
        SQLException syn = new SQLException("ERROR: syntax error at or near \"FROM\"", "42601");
        assertThat(scrub(syn, "SELECT a, FROM t", PG)).isEqualTo("ERROR: syntax error at or near \"FROM\"");
        // kullanıcının yazdığı metin sabiti (ör. ayar adı) izinli belirteçtir
        SQLException set = new SQLException("ERROR: unrecognized configuration parameter \"app.setting\"", "42704");
        assertThat(scrub(set, "SELECT current_setting('app.setting')", PG))
                .isEqualTo("ERROR: unrecognized configuration parameter \"app.setting\"");
        // DETAIL / HINT tek satıra sadeleşmiş iletide de atılır
        SQLException flat = new SQLException("ERROR: column \"a\" does not exist Hint: Perhaps you meant \"gizli_kolon\"", "42703");
        assertThat(scrub(flat, "SELECT a FROM t", PG)).isEqualTo("ERROR: column \"a\" does not exist");
    }

    @Test
    void postgresValueInNonDataErrorIsRemoved() {
        // 42704 veri hatası sınıfı DEĞİL: değer izinli belirteç olmadığı için yine silinir
        SQLException e = new SQLException("ERROR: unrecognized configuration parameter \"TOPSECRET\"", "42704");
        assertThat(scrub(e, "SELECT current_setting((SELECT note FROM s))", PG))
                .isEqualTo("ERROR: unrecognized configuration parameter \"…\"");
    }

    @Test
    void quoteInjectionCannotSmuggleText() {
        // değer = x" SECRET "y — kullanıcı x ve y'yi SQL'e yazıp tırnağı erken kapatır
        SQLException e = new SQLException("ERROR: unrecognized configuration parameter \"x\" SECRET \"y\"", "42704");
        String out = scrub(e, "SELECT current_setting((SELECT 'x' || note || 'y' FROM s))", PG);
        assertThat(out).doesNotContain("SECRET").isEqualTo("ERROR: unrecognized configuration parameter \"x\" …");
        // 'x'SECRET' — kapanış tırnağı değerin içinde
        SQLException ms = new SQLException("Invalid column name 'x'SECRET'.", "S0001", 207);
        assertThat(scrub(ms, "SELECT 'x' FROM t WITH (NOLOCK)", MS)).doesNotContain("SECRET");
        // kapanmayan tırnak
        SQLException open = new SQLException("ERROR: something \"TOPSE", "42000");
        assertThat(scrub(open, "SELECT 1", PG)).isEqualTo("ERROR: something \"…\"");
        // tipografik tırnak
        SQLException typo = new SQLException("ERROR: değer “TOPSECRET” okunamadı", "42000");
        assertThat(scrub(typo, "SELECT 1", PG)).doesNotContain("TOPSECRET").contains("“…”");
    }

    // ── MSSQL ────────────────────────────────────────────────────────────────

    @Test
    void mssqlConversionValueIsRemoved() {
        SQLException e = new SQLException("Conversion failed when converting the varchar value 'TOPSECRET' to data type int.",
                "S0001", 245);
        String out = scrub(e, "SELECT CAST((SELECT note FROM dbo.secret WITH (NOLOCK)) AS int)", MS);
        assertThat(out).doesNotContain("TOPSECRET").isEqualTo("Conversion failed when converting the varchar value '…'");
        // veri hatası kipinde izinli önek + tırnak kaçırma da işe yaramaz
        SQLException inj = new SQLException("Conversion failed when converting the varchar value 'x' to data type int. "
                + "SECRET 'y' to data type int.", "S0001", 245);
        assertThat(scrub(inj, "SELECT CAST('x' + note + 'y' AS int) FROM dbo.s WITH (NOLOCK)", MS)).doesNotContain("SECRET");
        SQLException json = new SQLException("JSON text is not properly formatted. Unexpected character 'S' is found at "
                + "position 0.", "S0001", 13609);
        assertThat(scrub(json, "SELECT JSON_VALUE(note, '$.a') FROM dbo.s WITH (NOLOCK)", MS)).contains("'…'");
    }

    @Test
    void driverTruncationCannotDropTheClosingQuote() {
        // sürücü uzun iletiyi keser, kapanış tırnağı düşer: ilk satır uzunsa katı kip
        String msg = "Conversion failed when converting the varchar value 'x' SECRET" + "A".repeat(1_990);
        SQLException e = new SQLException(msg, "S0001", 0);
        assertThat(scrub(e, "SELECT 'x' FROM dbo.t WITH (NOLOCK)", MS)).doesNotContain("SECRET");
    }

    @Test
    void mssqlIdentifiersStayReadable() {
        assertThat(scrub(new SQLException("Invalid column name 'musteri_no'.", "S0001", 207),
                "SELECT musteri_no FROM dbo.t WITH (NOLOCK)", MS)).isEqualTo("Invalid column name 'musteri_no'.");
        assertThat(scrub(new SQLException("Invalid object name 'dbo.yok'.", "S0002", 208),
                "SELECT * FROM dbo.yok WITH (NOLOCK)", MS)).isEqualTo("Invalid object name 'dbo.yok'.");
        // birden çok ad: ilki kalır, gerisi kısalır (kaçırma ile ayırt edilemez)
        assertThat(scrub(new SQLException("The SELECT permission was denied on the object 'secret', database 'app', schema "
                + "'dbo'.", "S0001", 229), "SELECT * FROM dbo.secret WITH (NOLOCK)", MS))
                .isEqualTo("The SELECT permission was denied on the object 'secret' …");
    }

    // ── Oracle ───────────────────────────────────────────────────────────────

    @Test
    void oracleMessages() {
        assertThat(scrub(new SQLException("ORA-00904: \"MUSTERI_NO\": invalid identifier\nhttps://docs.oracle.com/error-help/db/ora-00904/",
                "42000", 904), "SELECT musteri_no FROM t", ORA)).isEqualTo("ORA-00904: \"MUSTERI_NO\": invalid identifier");
        assertThat(scrub(new SQLException("ORA-00942: table or view \"APP\".\"YOK\" does not exist", "42000", 942),
                "SELECT * FROM app.yok", ORA)).isEqualTo("ORA-00942: table or view \"APP\".\"YOK\" does not exist");
        SQLException num = new SQLException("ORA-01722: unable to convert string value containing 'TOPSECRET' to a number: "
                + "NOTE", "42000", 1722);
        assertThat(scrub(num, "SELECT TO_NUMBER(note) FROM secret", ORA)).doesNotContain("TOPSECRET");
        // izinsiz parçalı ad zinciri
        assertThat(scrub(new SQLException("ORA-00942: table or view \"APP\".\"GIZLI\" does not exist", "42000", 942),
                "SELECT * FROM app.yok", ORA)).isEqualTo("ORA-00942: table or view \"…\"");
    }

    // ── genel ────────────────────────────────────────────────────────────────

    @Test
    void connectionMessagesKeepTheProfileNames() {
        // bağlantı denemesi (SQL yok): yalnız bağlantı tanımının adları izinli
        String db = DbQueryErrors.scrub("FATAL: database \"app\" does not exist", DbQueryErrors.allowTokens(null, PG), false);
        assertThat(db).isEqualTo("FATAL: database \"app\" does not exist");
        String role = DbQueryErrors.scrub("FATAL: role \"GIZLI\" does not exist", DbQueryErrors.allowTokens(null, PG), false);
        assertThat(role).isEqualTo("FATAL: role \"…\"");
    }

    @Test
    void bytesLengthAndEmpty() {
        assertThat(DbQueryErrors.scrub("could not convert 0xDEADBEEF here", Set.of(), false))
                .isEqualTo("could not convert 0x… here");
        assertThat(DbQueryErrors.scrub("x ".repeat(400), Set.of(), false)).hasSizeLessThanOrEqualTo(300);
        assertThat(DbQueryErrors.scrub("near \"\" here", Set.of(), false)).isEqualTo("near \"\" here");
        assertThat(DbQueryErrors.scrub(null, Set.of(), false)).isNull();
        // apostrof (sözcük içi) tırnak sayılmaz
        assertThat(DbQueryErrors.scrub("can't connect", Set.of(), false)).isEqualTo("can't connect");
        // doğrusal: büyük girdi hızlı biter
        long t0 = System.nanoTime();
        DbQueryErrors.scrub("'a' ".repeat(50_000), Set.of("A"), false);
        assertThat((System.nanoTime() - t0) / 1_000_000).isLessThan(500);
    }

    @Test
    void valueBearingClassesAndSqlState() {
        assertThat(DbQueryErrors.valueBearing(new SQLException("x", "22P02"), DbType.POSTGRESQL)).isTrue();
        assertThat(DbQueryErrors.valueBearing(new SQLException("x", "42703"), DbType.POSTGRESQL)).isFalse();
        assertThat(DbQueryErrors.valueBearing(new SQLException("x", "S0001", 245), DbType.MSSQL)).isTrue();
        assertThat(DbQueryErrors.valueBearing(new SQLException("x", "S0001", 9436), DbType.MSSQL)).isTrue();
        assertThat(DbQueryErrors.valueBearing(new SQLException("x", "S0001", 207), DbType.MSSQL)).isFalse();
        assertThat(DbQueryErrors.valueBearing(new SQLException("x", "42000", 1722), DbType.ORACLE)).isTrue();
        assertThat(DbQueryErrors.valueBearing(new RuntimeException(new SQLException("x", "22003")), DbType.ORACLE)).isTrue();
        assertThat(DbQueryErrors.sqlStateSuffix(new SQLException("x", "22P02"), DbType.POSTGRESQL)).isEqualTo(" (SQLSTATE 22P02)");
        assertThat(DbQueryErrors.sqlStateSuffix(new SQLException("x", "S0001", 245), DbType.MSSQL))
                .isEqualTo(" (SQLSTATE S0001 · 245)");
        assertThat(DbQueryErrors.sqlStateSuffix(new SQLException("x", "a b"), DbType.POSTGRESQL)).isEmpty();
        assertThat(DbQueryErrors.sqlStateSuffix(new IllegalStateException("x"), DbType.POSTGRESQL)).isEmpty();
    }
}
