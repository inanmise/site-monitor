package com.sitemonitor.service.dbquery;

import com.sitemonitor.util.Msg;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Tek tıkla GÜVENLİ sorgu düzeltmeleri (2026-11-05, dbq2 B2) — saf, yan etkisiz, veritabanına GİTMEZ. İki düzeltme:
 * <ul>
 *   <li>{@link #ADD_NOLOCK} (yalnız MSSQL): güvenlik denetiminin {@code SQL_MSSQL_NOLOCK_REQUIRED} dediği gerçek tablo
 *       başvurularının (takma adından sonra) arkasına {@code WITH (NOLOCK)} ekler. CTE adları ve türetilmiş tablolar
 *       dokunulmaz; başka ipucu taşıyan başvuruya dokunulmaz (neden {@link #R_OTHER_HINT}).</li>
 *   <li>{@link #ADD_ROW_LIMIT}: dış sorguda sınır yoksa PostgreSQL {@code LIMIT n}, Oracle {@code FETCH FIRST n ROWS ONLY}
 *       (sona), MSSQL {@code TOP (n)} (ana SELECT'in ardına) ekler.</li>
 * </ul>
 * Yalnız {@link SqlLexer} belirteçleriyle çalışır — yorum, metin sabiti, tırnaklı / köşeli parantezli ad içine HİÇ ekleme
 * yapılmaz; her döngü sınırlıdır (belirteç sayısı), düzenli ifade yok. Önerilen sorgu {@link DbQuerySqlGuard}'dan
 * GEÇMEZSE öneri dönmez ({@link #R_GUARD_STILL_FAILS}). {@link DbQuerySqlGuard} ve {@link SqlLexer} davranışı değişmez —
 * yalnız okunur.
 */
public final class DbQuerySqlFix {

    public static final String ADD_NOLOCK = "ADD_NOLOCK", ADD_ROW_LIMIT = "ADD_ROW_LIMIT";
    /** Düzeltme kodları (kalıcı — ön yüz {@code dbq.fix.<KOD>}). */
    public static final List<String> CODES = List.of(ADD_NOLOCK, ADD_ROW_LIMIT);

    /** Önerilmeme nedenleri (kalıcı). */
    public static final String R_NOT_MSSQL = "NOT_MSSQL", R_NOTHING_TO_FIX = "NOTHING_TO_FIX", R_OTHER_HINT = "OTHER_HINT",
            R_GUARD_STILL_FAILS = "GUARD_STILL_FAILS", R_ALREADY_LIMITED = "ALREADY_LIMITED", R_NOT_LIMITABLE = "NOT_LIMITABLE",
            R_UNPARSABLE = "UNPARSABLE",
            /** Güvenlik denetiminin zaman / izin sınırı doldu — sorgu reddedilmedi, "şimdilik denetlenemedi" (inceleme D10). */
            R_CHECK_TIMEOUT = "CHECK_TIMEOUT",
            /** Dış sorgu GROUP BY'sız toplama (COUNT / SUM …) — her zaman tek satır döner, satır sınırı gereksiz (D10). */
            R_SINGLE_ROW = "SINGLE_ROW";
    public static final List<String> REASONS = List.of(R_NOT_MSSQL, R_NOTHING_TO_FIX, R_OTHER_HINT, R_GUARD_STILL_FAILS,
            R_ALREADY_LIMITED, R_NOT_LIMITABLE, R_UNPARSABLE, R_CHECK_TIMEOUT, R_SINGLE_ROW);

    public static final int DEFAULT_ROW_LIMIT = 1_000, MIN_ROW_LIMIT = 1, MAX_ROW_LIMIT = 1_000_000;

    /** Tablo başvurusundan sonra takma ad OLAMAYAN sözcükler (yan tümce / birleşim sözcükleri). */
    private static final Set<String> NOT_ALIAS = Set.of("WHERE", "GROUP", "ORDER", "HAVING", "JOIN", "INNER", "LEFT", "RIGHT",
            "FULL", "CROSS", "OUTER", "ON", "UNION", "EXCEPT", "INTERSECT", "MINUS", "WITH", "OPTION", "FOR", "PIVOT",
            "UNPIVOT", "APPLY", "TABLESAMPLE", "WINDOW", "OFFSET", "FETCH", "AS", "USING", "NATURAL", "SELECT", "FROM", "INTO",
            "WHEN", "THEN", "ELSE", "END", "AND", "OR", "NOT", "LIMIT", "QUALIFY", "CONNECT", "START", "RETURNING", "SET",
            "VALUES", "BY", "IS", "IN", "LIKE", "BETWEEN", "EXISTS", "ASC", "DESC", "GO", "LATERAL");

    /**
     * FROM listesindeki virgülü belirleyen yan tümce sözcükleri (güvenlik denetiminin {@code commaInFromList} kümesi + dış
     * yan tümceler). Tablo ipucu {@code WITH (} yan tümce DEĞİLDİR.
     */
    private static final Set<String> CLAUSES = Set.of("FROM", "SELECT", "WHERE", "GROUP", "ORDER", "HAVING", "ON", "BY",
            "SET", "VALUES", "WITH", "OPTION", "WINDOW", "UNION", "EXCEPT", "INTERSECT", "MINUS", "LIMIT", "FETCH", "OFFSET",
            "FOR");

    private static final Set<String> SET_OPS = Set.of("UNION", "EXCEPT", "INTERSECT", "MINUS");

    /**
     * @param at     ÖZGÜN sorgudaki ekleme noktası (0 tabanlı karakter)
     * @param line   1 tabanlı satır
     * @param column 1 tabanlı sütun
     * @param text   eklenen metin
     */
    public record Edit(int at, int line, int column, String text) {}

    /** @param changed ADD_NOLOCK: ipucu eklenen tablolar (yazıldığı gibi, tekil); ADD_ROW_LIMIT: eklenen yan tümce */
    public record Fix(String code, String sql, List<String> changed, String note, List<Edit> edits) {}

    public record Skipped(String code, String reason, String note) {}

    public record Result(List<Fix> fixes, List<Skipped> skipped) {}

    private DbQuerySqlFix() {}

    /** Satır sınırı girdisi: null → varsayılan; aralık dışı → null (çağıran 400 der). */
    public static Integer rowLimitOrNull(Object raw) {
        if (raw == null || raw.toString().isBlank()) return DEFAULT_ROW_LIMIT;
        long v;
        try {
            v = raw instanceof Number n ? n.longValue() : Long.parseLong(raw.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
        return v < MIN_ROW_LIMIT || v > MAX_ROW_LIMIT ? null : (int) v;
    }

    /**
     * Sorgu için önerilebilen düzeltmeler (her biri özgün sorgu + TEK değişiklik; birbirine bağlı değil). Tür ZORUNLU (uç 400
     * {@code db_type} der); null yalnız eski çağıranlar için PostgreSQL sayılır.
     */
    public static Result fixes(String sql, DbType type, int rowLimit) {
        DbType t = type == null ? DbType.POSTGRESQL : type;
        int limit = Math.max(MIN_ROW_LIMIT, Math.min(MAX_ROW_LIMIT, rowLimit));
        List<Fix> fixes = new ArrayList<>();
        List<Skipped> skipped = new ArrayList<>();
        SqlLexer.Lexed lx = sql == null || sql.isBlank() || sql.length() > DbQuerySqlGuard.MAX_SQL_CHARS ? null : SqlLexer.lex(sql, t);
        if (lx == null || lx.error() != null || lx.tokens().isEmpty()) {
            for (String code : CODES) skipped.add(new Skipped(code, R_UNPARSABLE, msgUnparsable()));
            return new Result(List.of(), List.copyOf(skipped));
        }
        Object nolock = t == DbType.MSSQL ? nolock(sql, lx) : new Skipped(ADD_NOLOCK, R_NOT_MSSQL, msgNotMssql());
        if (nolock instanceof Fix f) fixes.add(f);
        else skipped.add((Skipped) nolock);
        Object rows = rowLimit(sql, t, lx, limit);
        if (rows instanceof Fix f) fixes.add(f);
        else skipped.add((Skipped) rows);
        return new Result(List.copyOf(fixes), List.copyOf(skipped));
    }

    // ── ADD_NOLOCK ───────────────────────────────────────────────────────────

    /** Denetim ihlal listesi en çok 25 öğedir: düzeltilen sorgu yeniden denetlenip yeni adlarla en çok bu kadar tur. */
    static final int NOLOCK_ROUNDS = 4;

    private static Object nolock(String sql, SqlLexer.Lexed lx) {
        DbQuerySqlGuard.Result guard = DbQuerySqlGuard.check(sql, DbType.MSSQL);
        if (DbQuerySqlGuard.isTimeout(guard)) return new Skipped(ADD_NOLOCK, R_CHECK_TIMEOUT, msgCheckTimeout());
        Set<String> required = nolockRequired(guard);
        if (required.isEmpty()) return new Skipped(ADD_NOLOCK, R_NOTHING_TO_FIX, msgNothingToFix());
        Object last = null;
        for (int round = 0; round < NOLOCK_ROUNDS; round++) {
            Object r = nolockRound(sql, lx, required);
            if (r instanceof Fix) return r;
            last = r;
            if (!(r instanceof Retry retry)) return r;
            if (required.containsAll(retry.more())) break;   // ilerleme yok
            required.addAll(retry.more());
            last = retry.skipped();
        }
        return last instanceof Retry retry ? retry.skipped() : last;
    }

    /** Denetimin "NOLOCK gerekli" dediği tablolar (normalleştirilmiş). */
    private static Set<String> nolockRequired(DbQuerySqlGuard.Result guard) {
        Set<String> out = new LinkedHashSet<>();
        for (DbQuerySqlGuard.Violation v : guard.violations())
            if (DbQuerySqlGuard.SQL_MSSQL_NOLOCK_REQUIRED.equals(v.code()) && v.token() != null) out.add(normalize(v.token()));
        return out;
    }

    /** Bir turun sonucu yeni "NOLOCK gerekli" adları gösteriyorsa (ihlal listesi kesilmişti) bir tur daha. */
    private record Retry(Set<String> more, Skipped skipped) {}

    private static Object nolockRound(String sql, SqlLexer.Lexed lx, Set<String> required) {
        List<SqlLexer.Token> tk = lx.tokens();
        int n = tk.size();
        Set<String> cteNames = cteNames(tk);
        boolean[] fromComma = fromListCommas(tk);
        List<Edit> edits = new ArrayList<>();
        Set<String> changed = new LinkedHashSet<>();
        boolean otherHint = false;
        for (int i = 0; i < n; i++) {
            SqlLexer.Token intro = tk.get(i);
            boolean isIntro = intro.isWord("FROM") || intro.isWord("JOIN") || (intro.isSymbol(',') && fromComma[i]);
            if (!isIntro || i + 1 >= n || !tk.get(i + 1).isName()) continue;
            int j = i + 1, k = j;
            while (k + 2 < n && tk.get(k + 1).isSymbol('.') && tk.get(k + 2).isName()) k += 2;
            if (k + 1 < n && tk.get(k + 1).isSymbol('(')) continue;               // tablo değerli fonksiyon
            String shown = sql.substring(tk.get(j).start(), tk.get(k).end());
            String name = normalize(shown);
            if (!required.contains(name)) continue;
            if (k == j && cteNames.contains(name)) continue;                      // CTE başvurusu
            int end = k;
            int m = k + 1;
            if (m + 1 < n && tk.get(m).isWord("AS") && tk.get(m + 1).isName()) end = m + 1;
            else if (m < n && aliasToken(tk.get(m))) end = m;
            int after = end + 1;
            if (after < n && ((tk.get(after).isWord("WITH") && after + 1 < n && tk.get(after + 1).isSymbol('('))
                    || tk.get(after).isSymbol('('))) {
                otherHint = true;                                                 // NOLOCK dışı ipucu — dokunulmaz
                continue;
            }
            // TABLESAMPLE / FOR SYSTEM_TIME ipucundan ÖNCE gelir — ipucu yeri belirsiz, dokunulmaz (FOR XML / JSON serbest)
            if (after < n && (tk.get(after).isWord("TABLESAMPLE")
                    || (tk.get(after).isWord("FOR") && after + 1 < n && tk.get(after + 1).isWord("SYSTEM_TIME")))) continue;
            int at = tk.get(end).end();
            edits.add(new Edit(at, lx.line(at), lx.column(at), " WITH (NOLOCK)"));
            changed.add(shown);
        }
        if (edits.isEmpty())
            return otherHint ? new Skipped(ADD_NOLOCK, R_OTHER_HINT, msgOtherHint())
                    : new Skipped(ADD_NOLOCK, R_GUARD_STILL_FAILS, msgGuardStillFails());
        String fixed = apply(sql, edits);
        DbQuerySqlGuard.Result after = DbQuerySqlGuard.check(fixed, DbType.MSSQL);
        if (DbQuerySqlGuard.isTimeout(after)) return new Skipped(ADD_NOLOCK, R_CHECK_TIMEOUT, msgCheckTimeout());
        if (!after.ok()) {
            Skipped s = otherHint ? new Skipped(ADD_NOLOCK, R_OTHER_HINT, msgOtherHint())
                    : new Skipped(ADD_NOLOCK, R_GUARD_STILL_FAILS, msgGuardStillFails());
            Set<String> more = nolockRequired(after);
            return more.isEmpty() ? s : new Retry(more, s);
        }
        List<String> names = List.copyOf(changed);
        return new Fix(ADD_NOLOCK, fixed, names, msgNolockNote(names), List.copyOf(edits));
    }

    /** Takma ad olabilecek belirteç: tırnaklı ad ya da yan tümce sözcüğü olmayan sözcük. */
    private static boolean aliasToken(SqlLexer.Token t) {
        if (t.kind() == SqlLexer.Kind.QUOTED) return true;
        return t.kind() == SqlLexer.Kind.WORD && !NOT_ALIAS.contains(t.upper());
    }

    /**
     * FROM listesindeki virgüller: tek ileri geçişte her parantez düzeyinin son yan tümce sözcüğü izlenir; virgül, düzeyinin
     * son yan tümcesi FROM ya da ON ise listededir ({@code FROM a JOIN b ON … , c} — ON koşulunun kendi düzeyinde virgül
     * olamaz, parantez içindekiler bir alt düzeydedir). JOIN durdurmaz; tablo ipucu {@code WITH (} yan tümceyi değiştirmez.
     */
    private static boolean[] fromListCommas(List<SqlLexer.Token> tk) {
        int n = tk.size();
        boolean[] out = new boolean[n];
        String[] clause = new String[n + 2];
        int depth = 0;
        for (int i = 0; i < n; i++) {
            SqlLexer.Token x = tk.get(i);
            if (x.isSymbol('(')) {
                depth = Math.min(depth + 1, n);
                clause[depth] = null;
            } else if (x.isSymbol(')')) {
                depth = Math.max(0, depth - 1);
            } else if (x.kind() == SqlLexer.Kind.WORD && CLAUSES.contains(x.upper())) {
                if (x.isWord("WITH") && i + 1 < n && tk.get(i + 1).isSymbol('(')) continue;   // tablo ipucu
                clause[depth] = x.upper();
            } else if (x.isSymbol(',')) {
                out[i] = "FROM".equals(clause[depth]) || "ON".equals(clause[depth]);
            }
        }
        return out;
    }

    /**
     * Sorgudaki CTE adları (küçük harf): {@code WITH ad [ ( kolonlar ) ] AS ( … ) [, ad …]} — ipucu grubu ({@code WITH (})
     * değil. Parantez eşleşmesi tek geçişte; her adım ilerler.
     */
    private static Set<String> cteNames(List<SqlLexer.Token> tk) {
        int n = tk.size();
        int[] closeOf = new int[n];
        int[] stack = new int[n];
        int sp = 0;
        for (int i = 0; i < n; i++) {
            closeOf[i] = -1;
            if (tk.get(i).isSymbol('(')) stack[sp++] = i;
            else if (tk.get(i).isSymbol(')') && sp > 0) closeOf[stack[--sp]] = i;
        }
        Set<String> out = new LinkedHashSet<>();
        for (int i = 0; i < n; i++) {
            if (!tk.get(i).isWord("WITH") || i + 1 >= n || !tk.get(i + 1).isName()) continue;
            int p = i + 1;
            while (p < n && tk.get(p).isName()) {
                String name = normalize(tk.get(p).text());
                int q = p + 1;
                if (q < n && tk.get(q).isSymbol('(')) {                           // kolon listesi
                    if (closeOf[q] < 0) break;
                    q = closeOf[q] + 1;
                }
                if (q + 1 >= n || !tk.get(q).isWord("AS") || !tk.get(q + 1).isSymbol('(') || closeOf[q + 1] < 0) break;
                out.add(name);
                int next = closeOf[q + 1] + 1;
                if (next < n && tk.get(next).isSymbol(',') && next + 1 < n && tk.get(next + 1).isName()) p = next + 1;
                else break;
            }
        }
        return out;
    }

    // ── ADD_ROW_LIMIT ────────────────────────────────────────────────────────

    private static Object rowLimit(String sql, DbType type, SqlLexer.Lexed lx, int limit) {
        List<SqlLexer.Token> tk = lx.tokens();
        int n = tk.size();
        int depth = 0, mainSelect = -1;
        boolean limited = false, setOp = false;
        for (int i = 0; i < n; i++) {
            SqlLexer.Token x = tk.get(i);
            if (x.isSymbol('(')) { depth++; continue; }
            if (x.isSymbol(')')) { depth = Math.max(0, depth - 1); continue; }
            if (depth != 0 || x.kind() != SqlLexer.Kind.WORD) continue;
            String u = x.upper();
            if (mainSelect < 0 && "SELECT".equals(u)) mainSelect = i;
            if (SET_OPS.contains(u)) setOp = true;
            if ("FETCH".equals(u) || "OFFSET".equals(u)) limited = true;
            if (type == DbType.POSTGRESQL && "LIMIT".equals(u)) limited = true;
            if (type == DbType.MSSQL && "TOP".equals(u)) limited = true;
            if (type == DbType.ORACLE && "ROWNUM".equals(u)) limited = true;
        }
        if (limited) return new Skipped(ADD_ROW_LIMIT, R_ALREADY_LIMITED, msgAlreadyLimited());
        // Tek satır döndüren toplama (GROUP BY'sız COUNT / SUM …): sınır gereksiz ve kural tanımını değiştirmez — önerilmez.
        // Biçim güvenlik denetiminin AST'sinden (DbQuerySqlGuard.Shape); denetim zaman aşımına uğradıysa öneri yok.
        DbQuerySqlGuard.Result original = DbQuerySqlGuard.check(sql, type);
        if (DbQuerySqlGuard.isTimeout(original)) return new Skipped(ADD_ROW_LIMIT, R_CHECK_TIMEOUT, msgCheckTimeout());
        if (!setOp && original.shape() != null && original.shape().singleRowAggregate())
            return new Skipped(ADD_ROW_LIMIT, R_SINGLE_ROW, msgSingleRow());
        String clause;
        int at;
        if (type == DbType.MSSQL) {
            // TOP yalnız İLK dalı keser — birleşimde (UNION …) ya da parantezle başlayan sorguda önerilmez
            if (setOp || mainSelect < 0) return new Skipped(ADD_ROW_LIMIT, R_NOT_LIMITABLE, msgNotLimitable());
            int after = mainSelect;
            if (after + 1 < n && (tk.get(after + 1).isWord("DISTINCT") || tk.get(after + 1).isWord("ALL"))) after++;
            clause = "TOP (" + limit + ")";
            at = tk.get(after).end();
        } else {
            int last = n - 1;
            if (tk.get(last).isSymbol(';')) last--;
            if (last < 0) return new Skipped(ADD_ROW_LIMIT, R_UNPARSABLE, msgUnparsable());
            clause = type == DbType.ORACLE ? "FETCH FIRST " + limit + " ROWS ONLY" : "LIMIT " + limit;
            at = tk.get(last).end();
        }
        List<Edit> edits = List.of(new Edit(at, lx.line(at), lx.column(at), " " + clause));
        String fixed = apply(sql, edits);
        DbQuerySqlGuard.Result after = DbQuerySqlGuard.check(fixed, type);
        if (DbQuerySqlGuard.isTimeout(after)) return new Skipped(ADD_ROW_LIMIT, R_CHECK_TIMEOUT, msgCheckTimeout());
        if (!after.ok())
            return new Skipped(ADD_ROW_LIMIT, R_GUARD_STILL_FAILS, msgGuardStillFails());
        return new Fix(ADD_ROW_LIMIT, fixed, List.of(clause), msgRowLimitNote(clause, limit, type), edits);
    }

    // ── Ortak ────────────────────────────────────────────────────────────────

    /** Eklemeleri konum sırasıyla uygular (aynı konumdakiler sırayla). */
    static String apply(String sql, List<Edit> edits) {
        List<Edit> sorted = new ArrayList<>(edits);
        sorted.sort(java.util.Comparator.comparingInt(Edit::at));
        StringBuilder sb = new StringBuilder(sql.length() + 16 * sorted.size());
        int from = 0;
        for (Edit e : sorted) {
            int at = Math.max(from, Math.min(sql.length(), e.at()));
            sb.append(sql, from, at).append(e.text());
            from = at;
        }
        sb.append(sql, from, sql.length());
        return sb.toString();
    }

    /** {@code [dbo].[Siparis]} / {@code "dbo"."x"} / {@code dbo.x} → {@code dbo.siparis} (tırnaksız, küçük harf). */
    static String normalize(String name) {
        if (name == null) return "";
        StringBuilder out = new StringBuilder(name.length());
        StringBuilder part = new StringBuilder();
        char close = 0;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (close != 0) {
                if (c == close) {
                    if (i + 1 < name.length() && name.charAt(i + 1) == close) { part.append(c); i++; }   // ]] / "" kaçışı
                    else close = 0;
                } else {
                    part.append(c);
                }
                continue;
            }
            if (c == '[') { close = ']'; continue; }
            if (c == '"') { close = '"'; continue; }
            if (c == '.') {
                out.append(part.toString().trim().toLowerCase(Locale.ROOT)).append('.');
                part.setLength(0);
                continue;
            }
            if (!Character.isWhitespace(c)) part.append(c);
        }
        out.append(part.toString().trim().toLowerCase(Locale.ROOT));
        return out.toString();
    }

    // ── Metinler (Msg.t — istek dilinde) ─────────────────────────────────────

    private static String msgNolockNote(List<String> names) {
        String list = String.join(", ", names.size() > 10 ? names.subList(0, 10) : names) + (names.size() > 10 ? " …" : "");
        return Msg.t(names.size() + " tablo başvurusuna WITH (NOLOCK) eklendi: " + list + " · MSSQL'de kilitsiz okuma canlı "
                        + "işlemleri bekletmez; sorgu henüz kaydedilmemiş (kirli) satırları da görebilir.",
                "WITH (NOLOCK) was added to " + names.size() + " table reference(s): " + list + " · In MSSQL a no-lock read "
                        + "does not block live transactions; the query may also see uncommitted (dirty) rows.");
    }

    private static String msgRowLimitNote(String clause, int limit, DbType type) {
        String ora = type == DbType.ORACLE ? Msg.t(" (Oracle 12c ve sonrası)", " (Oracle 12c and later)") : "";
        return Msg.t("Sorguya satır sınırı eklendi: " + clause + ora + " · Veritabanı en çok " + limit + " satır döndürür; "
                        + "kurallar bu kesilmiş kümeden hesaplanır — satır sayısı bu sınırda durur, kolon değeri kuralları "
                        + "(toplam / ortalama / en büyük, anomali, donma) yalnız bu satırları görür.",
                "A row limit was added to the query: " + clause + ora + " · The database returns at most " + limit
                        + " rows; rules are computed from this cut set — a row count stops at this limit and column value "
                        + "rules (sum / average / maximum, anomaly, frozen) see only these rows.");
    }

    private static String msgUnparsable() {
        return Msg.t("Sorgu çözümlenemedi · Önce yazım hatasını giderin (kapanmayan tırnak / yorum).",
                "The query could not be read · Fix the syntax first (an unclosed quote / comment).");
    }

    private static String msgCheckTimeout() {
        return Msg.t("Güvenlik denetimi şu an tamamlanamadı (yoğunluk) · Sorgu reddedilmedi; birkaç saniye sonra yeniden deneyin.",
                "The safety check could not finish right now (busy) · The query was not rejected; try again in a few seconds.");
    }

    private static String msgSingleRow() {
        return Msg.t("Sorgu GROUP BY'sız bir toplama (COUNT / SUM …) — her zaman tek satır döner; satır sınırı gerekmez.",
                "The query is an aggregate without GROUP BY (COUNT / SUM …) — it always returns one row; no row limit is needed.");
    }

    private static String msgNotMssql() {
        return Msg.t("WITH (NOLOCK) yalnız MSSQL sorgularında gerekir.", "WITH (NOLOCK) is needed only for MSSQL queries.");
    }

    private static String msgNothingToFix() {
        return Msg.t("Tüm tablo başvurularında WITH (NOLOCK) zaten var.", "Every table reference already has WITH (NOLOCK).");
    }

    private static String msgOtherHint() {
        return Msg.t("Bazı tablolarda NOLOCK dışında bir ipucu var; bunlara dokunulmadı · İpucunu kaldırıp yalnız WITH (NOLOCK) "
                        + "yazın.", "Some tables carry a hint other than NOLOCK; they were not changed · Remove the hint and "
                        + "write only WITH (NOLOCK).");
    }

    private static String msgGuardStillFails() {
        return Msg.t("Düzeltilmiş sorgu başka bir nedenle güvenlik denetiminden geçmiyor; öneri sunulmadı · Önce diğer "
                        + "uyarıları giderin.", "The corrected query still fails the safety check for another reason, so no "
                        + "fix is offered · Fix the other issues first.");
    }

    private static String msgAlreadyLimited() {
        return Msg.t("Sorguda satır sınırı zaten var (LIMIT / TOP / FETCH / OFFSET / ROWNUM).",
                "The query already limits its rows (LIMIT / TOP / FETCH / OFFSET / ROWNUM).");
    }

    private static String msgNotLimitable() {
        return Msg.t("Bu sorguya otomatik satır sınırı eklenemiyor · MSSQL'de TOP birleşimin (UNION …) yalnız ilk dalını keser · "
                        + "Sorguyu bir dış SELECT TOP (n) * FROM ( … ) x içine alın.",
                "A row limit can't be added to this query automatically · In MSSQL TOP limits only the first branch of a "
                        + "UNION · Wrap the query in an outer SELECT TOP (n) * FROM ( … ) x.");
    }
}
