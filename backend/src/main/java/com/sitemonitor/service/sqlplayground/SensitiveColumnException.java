package com.sitemonitor.service.sqlplayground;

import com.sitemonitor.config.GlobalExceptionHandler.CodedBadRequestException;
import com.sitemonitor.util.Msg;

/**
 * Hassas kolon kullanımı reddi (2026-11-13, A1) — 400 {@code SQL_SENSITIVE_COLUMN}. İleti {@link Msg#t} ile istek dilinde
 * ve sebebi (hangi kolon / tablo / fonksiyon, neden) söyler; sorgu ÇALIŞTIRILMAMIŞTIR.
 */
public class SensitiveColumnException extends CodedBadRequestException {

    public static final String CODE = "SQL_SENSITIVE_COLUMN";

    private final String column;
    private final String reason;

    public SensitiveColumnException(SqlSensitiveGuard.Verdict v) {
        super(CODE, message(v));
        this.column = v.column();
        this.reason = v.reason();
    }

    public String getColumn() { return column; }

    public String getReason() { return reason; }

    static String message(SqlSensitiveGuard.Verdict v) {
        String c = v.column() == null ? "?" : v.column();
        String q = "\"" + c + "\"";
        String r = v.reason() == null ? "" : v.reason();
        return switch (r) {
            case SqlSensitiveGuard.R_EXPRESSION -> Msg.t(
                    q + " hassas bir kolon: yalnız sonuç listesinde tek başına seçilebilir (değeri maskelenir). WHERE, JOIN, "
                            + "ORDER BY, GROUP BY, fonksiyon, dönüşüm, alt sorgu ya da CTE içinde kullanılamaz — sorgu çalıştırılmadı.",
                    q + " is a sensitive column: it can only be selected on its own in the result list (its value is masked). "
                            + "It can't be used in WHERE, JOIN, ORDER BY, GROUP BY, a function, a cast, a subquery or a CTE — the query wasn't run.");
            case SqlSensitiveGuard.R_WHOLE_ROW -> Msg.t(
                    q + " satırının tamamı değer olarak kullanılamaz (hassas kolon içeriyor; ör. to_jsonb(u), u::text, "
                            + "fonksiyon içinde u.*) — sorgu çalıştırılmadı. Kolonları adıyla seçin.",
                    "The whole row of " + q + " can't be used as a value (it contains a sensitive column; e.g. to_jsonb(u), "
                            + "u::text, u.* inside a function) — the query wasn't run. Select the columns by name.");
            case SqlSensitiveGuard.R_SET_OPERATION -> Msg.t(
                    "Hassas kolonu olan bir tabloyla (" + q + ") UNION / INTERSECT / EXCEPT kullanılamaz: küme işlemi kolonun "
                            + "kaynağını siler ve maske uygulanamaz. Sorguları ayrı ayrı çalıştırın.",
                    "UNION / INTERSECT / EXCEPT can't be used with a table that has a sensitive column (" + q + "): a set "
                            + "operation drops the column's origin, so the mask can't be applied. Run the queries separately.");
            case SqlSensitiveGuard.R_NATURAL -> Msg.t(
                    "Hassas kolonu olan bir tabloyla (" + q + ") NATURAL JOIN kullanılamaz: hassas kolonlar adı yazılmadan "
                            + "eşleşebilir. JOIN … ON ile açık bir koşul yazın.",
                    "NATURAL JOIN can't be used with a table that has a sensitive column (" + q + "): sensitive columns could "
                            + "be matched without being named. Write an explicit JOIN … ON condition.");
            case SqlSensitiveGuard.R_ALIAS_LIST -> Msg.t(
                    q + " için kolon takma ad listesi (ör. AS t(a, b)) hassas tablo içeren bir sorguda kullanılamaz: hassas "
                            + "kolon başka bir adla anılabilir. Kolonları seçim listesinde AS ile adlandırın.",
                    "A column alias list for " + q + " (e.g. AS t(a, b)) can't be used in a query on a sensitive table: a "
                            + "sensitive column could be referred to by another name. Name the columns with AS in the select list.");
            case SqlSensitiveGuard.R_ORDINAL -> Msg.t(
                    "ORDER BY / GROUP BY konum numarası (" + c + ") hassas bir kolona ya da * öğesine başvuruyor olabilir; "
                            + "sıralama / gruplama kolonunu adıyla yazın.",
                    "The ORDER BY / GROUP BY position (" + c + ") may refer to a sensitive column or a * item; write the "
                            + "column name instead.");
            case SqlSensitiveGuard.R_TABLE_REF -> Msg.t(
                    "Hassas kolonu olan bir tablo içeren sorguda FROM öğesi bu biçimde (" + q + ") kullanılamaz: ONLY, "
                            + "miras yıldızı (tablo *), TABLESAMPLE, ROWS FROM, WITH ORDINALITY ya da takma addan sonra "
                            + "beklenmeyen bir belirteç, satır kaynağının adını belirsizleştirir — sorgu çalıştırılmadı. "
                            + "Tabloyu düz yazın: FROM tablo [AS] takma_ad.",
                    "In a query on a table with a sensitive column, a FROM item can't be written this way (" + q + "): "
                            + "ONLY, the inheritance star (table *), TABLESAMPLE, ROWS FROM, WITH ORDINALITY or an "
                            + "unexpected token after the alias makes the row source's name ambiguous — the query wasn't "
                            + "run. Write the table plainly: FROM table [AS] alias.");
            case SqlSensitiveGuard.R_ATTRIBUTE_FUNCTION -> Msg.t(
                    q + " kullanılamaz: PostgreSQL bu yazımı, nitelikteki satırın böyle bir kolonu yoksa satırın TAMAMIYLA bir "
                            + "fonksiyon çağrısı yapar (ör. u.to_jsonb = to_jsonb(u)) ve hassas kolonlar açığa çıkar — sorgu "
                            + "çalıştırılmadı. Gerçek bir kolonsa niteleyicisiz yazın ya da alt sorguda / CTE'de farklı bir "
                            + "adla (AS ad) adlandırın.",
                    q + " can't be used: if the qualifying row has no such column, PostgreSQL turns this into a function call "
                            + "on the WHOLE row (e.g. u.to_jsonb = to_jsonb(u)) and exposes sensitive columns — the query "
                            + "wasn't run. If it is a real column, write it without the qualifier or give it another name "
                            + "(AS name) in the subquery / CTE.");
            case SqlSensitiveGuard.R_STATS -> Msg.t(
                    q + " istatistik görünümü kolonların gerçek değerlerinden örnek taşır (hassas kolonlar dahil); SQL "
                            + "Playground'da sorgulanamaz.",
                    "The " + q + " statistics view carries samples of real column values (sensitive columns included); it "
                            + "can't be queried in the SQL Playground.");
            case SqlSensitiveGuard.R_DYNAMIC -> Msg.t(
                    q + " metinden sorgu çalıştırır ya da tabloyu adıyla okur; hassas kolon koruması atlatılabileceği için SQL "
                            + "Playground'da kullanılamaz.",
                    q + " runs a query from text or reads a table by name; it can't be used in the SQL Playground because it "
                            + "could bypass the sensitive column protection.");
            case SqlSensitiveGuard.R_UNICODE -> Msg.t(
                    "U&\"…\" kaçışlı tanımlayıcı desteklenmez (adı gizleyebilir). Tanımlayıcıyı düz yazın.",
                    "U&\"…\" escaped identifiers aren't supported (they can hide a name). Write the identifier plainly.");
            default -> Msg.t(
                    "Sorgu hassas kolonu olan bir tablo (" + q + ") içeriyor ama yapısı çözümlenemedi; güvenlik için "
                            + "çalıştırılmadı. Sorguyu sadeleştirip yeniden deneyin.",
                    "The query involves a table with a sensitive column (" + q + ") but its structure couldn't be analysed; "
                            + "it wasn't run for safety. Simplify the query and try again.");
        };
    }
}
