package com.sitemonitor.service.sqlplayground;

import com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Adlandırılmış parametre ({@code :ad}) kuralı — sunucu ve istemci için TEK doğruluk tablosu. İstemci testi bu dosyadaki
 * {@link #TABLE}'ı okur (satır başına {@code {"sql", "adlar"}}, adlar ilk görünüş sırasıyla virgülle ayrılmış, boş = yer
 * tutucu yok). Java dize kaçışları yalnız {@code \\n} ve {@code \\\\}. Sınırlar arasına satır ekleyin, biçimi bozmayın.
 */
class SqlParamsTest {

    // TABLE-BEGIN
    static final String[][] TABLE = {
        {"SELECT :a", "a"},
        {"SELECT * FROM t WHERE id = :id AND name = :name", "id,name"},
        {"SELECT :a, :a", "a"},
        {"SELECT ':a'", ""},
        {"SELECT 'it''s :a'", ""},
        {"SELECT x::text FROM t", ""},
        {"SELECT :v::int", "v"},
        {"SELECT a::timestamp::date FROM t", ""},
        {"SELECT \"col:a\" FROM t", ""},
        {"SELECT 1 -- :a", ""},
        {"SELECT 1 /* :a */", ""},
        {"SELECT 1 /* nested /* :a */ :b */ , :c", "c"},
        {"SELECT $$ :a $$", ""},
        {"SELECT $tag$ :a $tag$, :b", "b"},
        {"SELECT f(a := 1)", ""},
        {"SELECT arr[1:2] FROM t", ""},
        {"SELECT arr[lo:hi] FROM t", "hi"},
        {"SELECT :adı", ""},
        {"SELECT :a$b", ""},
        {"SELECT :1abc", ""},
        {"SELECT :_x", "_x"},
        {"SELECT :A", "A"},
        {"SELECT 'a' || :b", "b"},
        {"SELECT :x FROM t WHERE y = :x2", "x,x2"},
        {"SELECT :a\n, :b", "a,b"},
        {"SELECT E'\\' :a', :b", "b"},
        {"SELECT 'x'::text, :t", "t"},
        {"SELECT ab$1 FROM t", ""},
        {"SELECT : a", ""},
        {"SELECT :abcdefghijabcdefghijabcdefghijabcdefghij", "abcdefghijabcdefghijabcdefghijabcdefghij"},
        {"SELECT :abcdefghijabcdefghijabcdefghijabcdefghijk", ""},
        {"SELECT d FROM t WHERE d > :from_day AND d < :to_day", "from_day,to_day"},
    };
    // TABLE-END

    @Test
    @DisplayName("Doğruluk tablosu: yer tutucu adları (ilk görünüş sırası)")
    void truthTable() {
        List<String> failures = new ArrayList<>();
        for (String[] row : TABLE) {
            String got = String.join(",", SqlParams.names(row[0]));
            if (!got.equals(row[1])) failures.add(row[0] + " → beklenen [" + row[1] + "], bulunan [" + got + "]");
        }
        assertThat(failures).isEmpty();
    }

    @Test
    @DisplayName("bind: her yer tutucu ? olur, sıra korunur, aynı ad her konuma bağlanır")
    void bindOrder() {
        SqlParams.Bound b = SqlParams.bind("SELECT * FROM t WHERE a = :x OR b = :y OR c = :x", false);
        assertThat(b.jdbcSql()).isEqualTo("SELECT * FROM t WHERE a = ? OR b = ? OR c = ?");
        assertThat(b.order()).containsExactly("x", "y", "x");
        assertThat(b.names()).containsExactly("x", "y");
    }

    @Test
    @DisplayName("PostgreSQL: parametreli sorguda gerçek ? işleci ?? olur; dize / yorum içindeki ? korunur")
    void questionMarkEscape() {
        SqlParams.Bound b = SqlParams.bind("SELECT j ? 'k', '?' , j ?| :keys -- ?\nFROM t", true);
        assertThat(b.jdbcSql()).isEqualTo("SELECT j ?? 'k', '?' , j ??| ? -- ?\nFROM t");
        // Parametresiz sorgu dokunulmaz (düz Statement'la koşar)
        assertThat(SqlParams.bind("SELECT j ? 'k' FROM t", true).jdbcSql()).isEqualTo("SELECT j ? 'k' FROM t");
    }

    @Test
    @DisplayName("En çok 20 ayrı parametre")
    void tooManyParams() {
        StringBuilder sb = new StringBuilder("SELECT 1");
        for (int i = 0; i < 21; i++) sb.append(", :p").append(i);
        assertThatThrownBy(() -> SqlParams.bind(sb.toString(), false))
                .isInstanceOfSatisfying(FieldValidationException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("SQL_PARAM_INVALID");
                    assertThat(e.getFields()).containsKey("params");
                });
    }

    @Test
    @DisplayName("Eksik değer → SQL_PARAM_MISSING alan params.<ad>; tür uyuşmazlığı → SQL_PARAM_INVALID")
    void validate() {
        assertThatThrownBy(() -> SqlParams.validate(List.of("id"), Map.of()))
                .isInstanceOfSatisfying(FieldValidationException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("SQL_PARAM_MISSING");
                    assertThat(e.getFields()).containsKey("params.id");
                });
        assertThatThrownBy(() -> SqlParams.validate(List.of("n"), Map.of("n", new SqlParams.Param("abc", "number"))))
                .isInstanceOfSatisfying(FieldValidationException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("SQL_PARAM_INVALID");
                    assertThat(e.getFields()).containsKey("params.n");
                });
        assertThatThrownBy(() -> SqlParams.validate(List.of("d"), Map.of("d", new SqlParams.Param("13.11.2026", "date"))))
                .isInstanceOf(FieldValidationException.class);
        assertThatThrownBy(() -> SqlParams.validate(List.of("b"), Map.of("b", new SqlParams.Param("evet", "bool"))))
                .isInstanceOf(FieldValidationException.class);
        assertThatThrownBy(() -> SqlParams.validate(List.of("x"), Map.of("x", new SqlParams.Param("1", "uuid"))))
                .isInstanceOf(FieldValidationException.class);
        assertThatThrownBy(() -> SqlParams.validate(List.of("x"), Map.of("x", new SqlParams.Param("a".repeat(1001), "text"))))
                .isInstanceOf(FieldValidationException.class);
        // Geçerli değerler
        SqlParams.validate(List.of("a", "b", "c", "d", "e"), Map.of(
                "a", new SqlParams.Param("hello", "text"),
                "b", new SqlParams.Param("3.5", "number"),
                "c", new SqlParams.Param("2026-11-13", "date"),
                "d", new SqlParams.Param("2026-11-13T09:30", "timestamp"),
                "e", new SqlParams.Param("true", "bool")));
    }

    @Test
    @DisplayName("Tür dönüşümü: number → BigDecimal, date → LocalDate, timestamp (boşluk da) → LocalDateTime, bool")
    void convert() {
        assertThat(SqlParams.convert("n", new SqlParams.Param(" 42 ", "number"))).isEqualTo(new BigDecimal("42"));
        assertThat(SqlParams.convert("d", new SqlParams.Param("2026-11-13", "date"))).isEqualTo(LocalDate.of(2026, 11, 13));
        assertThat(SqlParams.convert("t", new SqlParams.Param("2026-11-13 09:30:15", "timestamp")))
                .isEqualTo(LocalDateTime.of(2026, 11, 13, 9, 30, 15));
        assertThat(SqlParams.convert("b", new SqlParams.Param("0", "bool"))).isEqualTo(false);
        assertThat(SqlParams.convert("s", new SqlParams.Param("", "text"))).isEqualTo("");
    }

    @Test
    @DisplayName("parse: {ad: {value, type}} ve kısa {ad: \"değer\"}; harita değilse 400")
    void parse() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("a", Map.of("value", "1", "type", "NUMBER"));
        raw.put("b", "x");
        Map<String, SqlParams.Param> p = SqlParams.parse(raw);
        assertThat(p.get("a")).isEqualTo(new SqlParams.Param("1", "number"));
        assertThat(p.get("b")).isEqualTo(new SqlParams.Param("x", "text"));
        assertThat(SqlParams.parse(null)).isEmpty();
        assertThatThrownBy(() -> SqlParams.parse(List.of("x"))).isInstanceOf(FieldValidationException.class);
    }

    @Test
    @DisplayName("typesOf: değer saklanmaz, yalnız geçerli ad + tür")
    void typesOf() {
        Map<String, SqlParams.Param> in = new LinkedHashMap<>();
        in.put("ok", new SqlParams.Param("secret-value", "date"));
        in.put("bad name", new SqlParams.Param("1", "text"));
        in.put("odd", new SqlParams.Param("1", "uuid"));
        assertThat(SqlParams.typesOf(in)).containsExactly(Map.entry("ok", "date"));
    }

    @Test
    @DisplayName("Doğrusal: çok uzun kapanmayan yorum / dize ve binlerce iki nokta sınırlı sürede biter")
    void linear() {
        String big = "SELECT /*" + "/*".repeat(50_000);
        String colons = "SELECT " + ":".repeat(100_000);
        String quotes = "SELECT '" + "''".repeat(50_000);
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), () -> {
            SqlParams.names(big);
            SqlParams.names(colons);
            SqlParams.names(quotes);
            SqlParams.bind(big, true);
        });
    }
}
