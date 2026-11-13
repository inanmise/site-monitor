package com.sitemonitor.service.sqlplayground;

import com.sitemonitor.service.dbquery.DbType;
import com.sitemonitor.service.dbquery.SqlLexer;
import com.sitemonitor.service.dbquery.SqlLexer.Kind;
import com.sitemonitor.service.dbquery.SqlLexer.Token;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Hassas kolonların İFADE içinde kullanımını reddeden denetim (2026-11-13, SQL Playground A1).
 *
 * <p><b>Neden maskeleme yetmez.</b> Sonuç kolonunun maskesi kökene bakar (PostgreSQL {@code RowDescription}'daki taban
 * tablo / kolon). Kök yalnız ÇIPLAK kolon referansında taşınır: {@code md5(password_hash)}, {@code password_hash::text},
 * {@code UNION} ya da {@code to_jsonb(u)} kökeni kaybeder ve değer açık döner. Üstelik çıktıda görünmese bile
 * {@code WHERE password_hash LIKE '$2a$10$a%'} bir kestirme kâhinidir (karakter karakter tahmin). Bu yüzden: hassas bir
 * kolonun adı sorguda (dize / yorum DIŞINDA) yalnız EN DIŞ sorgunun seçim listesinde çıplak kolon olarak geçebilir
 * (isteğe bağlı nitelik ve takma adla); başka her yerde (WHERE, JOIN, ORDER BY, GROUP BY, fonksiyon argümanı, alt sorgu,
 * CTE, CASE …) sorgu ÇALIŞMADAN reddedilir.
 *
 * <p><b>Kurallar</b> (yalnız sorgu hassas kolonu olan bir tablonun adını taşıyorsa uygulanır; sıra = kontrol sırası):
 * <ol>
 *   <li>{@link #R_UNICODE}: {@code U&"…"} tanımlayıcı (kaçışlarla ad gizlenebilir) — her zaman.</li>
 *   <li>{@link #R_STATS}: istatistik görünümleri ({@code pg_stats}: kolonların gerçek değer örnekleri) — her zaman.</li>
 *   <li>{@link #R_DYNAMIC}: metinden sorgu çalıştıran fonksiyonlar ({@code query_to_xml}, {@code ts_stat}…) — her zaman.</li>
 *   <li>{@link #R_SET_OPERATION}: UNION / INTERSECT / EXCEPT (herhangi bir düzeyde) — küme işlemi kökeni siler.</li>
 *   <li>{@link #R_NATURAL}: NATURAL JOIN — hassas kolonlar adı yazılmadan eşleştirilebilir.</li>
 *   <li>{@link #R_ALIAS_LIST}: tablo / alt sorgu / CTE kolon takma ad listesi — hassas kolon başka adla anılabilir.</li>
 *   <li>{@link #R_EXPRESSION}: hassas kolon adı (ya da onun takma adı) çıplak dış seçim öğesi dışında.</li>
 *   <li>{@link #R_WHOLE_ROW}: tablo / takma ad / CTE adı DEĞER olarak ({@code to_jsonb(u)}, {@code u::text},
 *       {@code SELECT app_users FROM app_users}, fonksiyon içinde {@code u.*}) ve ifade konumunda {@code (SELECT * …)} /
 *       {@code (TABLE …)} (satır karşılaştırma kâhini) — {@code *} / {@code TABLE} yalnız en dış seçim listesinde,
 *       türetilmiş tabloda, CTE gövdesinde, {@code EXISTS} içinde.</li>
 *   <li>{@link #R_ORDINAL}: en dışta {@code ORDER BY 3} / {@code GROUP BY 1} hassas ya da {@code *} öğesine konumla
 *       başvuru; alt sorguda ve {@code DISTINCT ON} içinde her konum numarası.</li>
 * </ol>
 * Sözcük çözümleme {@link SqlLexer} (doğrusal, lehçe-bilinçli: dize / yorum / dolar tırnağı ayrılır); analiz belirteç
 * düzeyinde ve TEMKİNLİ — belirsiz durumda reddeder (ör. tablo takma adıyla aynı adlı kolon). Tüm geçişler doğrusaldır.
 */
public final class SqlSensitiveGuard {

    private SqlSensitiveGuard() {}

    public static final String R_UNICODE = "unicode_identifier";
    public static final String R_STATS = "stats_view";
    public static final String R_DYNAMIC = "dynamic_sql";
    public static final String R_SET_OPERATION = "set_operation";
    public static final String R_NATURAL = "natural_join";
    public static final String R_ALIAS_LIST = "column_alias_list";
    public static final String R_EXPRESSION = "expression";
    public static final String R_WHOLE_ROW = "whole_row";
    public static final String R_ORDINAL = "ordinal";
    public static final String R_UNPARSED = "unparsed";

    /** Kararlı ret nedenleri (istemci metni bunlarla çevirebilir). */
    public static final List<String> REASONS = List.of(R_UNICODE, R_STATS, R_DYNAMIC, R_SET_OPERATION, R_NATURAL,
            R_ALIAS_LIST, R_EXPRESSION, R_WHOLE_ROW, R_ORDINAL, R_UNPARSED);

    /**
     * @param ok                 sorgu çalışabilir mi
     * @param mentionsSensitive  sorgu hassas kolonu olan bir tablonun adını taşıyor mu (sonuç maskelemesi kökeni
     *                           okuyamazsa bu durumda sonucu GÖSTERMEZ — kapalı başarısızlık)
     * @param column             ret nedeni olan ad (kolon / tablo / fonksiyon), yoksa null
     * @param reason             {@link #REASONS} biri; geçişte null
     */
    public record Verdict(boolean ok, boolean mentionsSensitive, String column, String reason) {
        static Verdict pass(boolean mentions) { return new Verdict(true, mentions, null, null); }
        static Verdict reject(String column, String reason) { return new Verdict(false, true, column, reason); }
    }

    private static final Set<String> CLAUSE_END = Set.of("WHERE", "GROUP", "HAVING", "WINDOW", "ORDER", "LIMIT",
            "OFFSET", "FETCH", "FOR", "UNION", "INTERSECT", "EXCEPT", "MINUS", "INTO", "RETURNING");

    private static final Set<String> SELECT_LIST_END = Set.of("FROM", "WHERE", "GROUP", "HAVING", "WINDOW", "ORDER",
            "LIMIT", "OFFSET", "FETCH", "FOR", "INTO", "UNION", "INTERSECT", "EXCEPT", "MINUS");

    private static final Set<String> ALIAS_STOP = Set.of("WHERE", "JOIN", "INNER", "LEFT", "RIGHT", "FULL", "CROSS",
            "NATURAL", "ON", "USING", "GROUP", "ORDER", "LIMIT", "OFFSET", "FETCH", "FOR", "UNION", "INTERSECT", "EXCEPT",
            "MINUS", "HAVING", "WINDOW", "LATERAL", "TABLESAMPLE", "WITH", "OUTER", "RETURNING", "AS", "INTO", "SELECT",
            "FROM", "ONLY", "ROWS");

    private static final Set<String> SET_OPS = Set.of("UNION", "INTERSECT", "EXCEPT", "MINUS");

    /** Ayrıştırma çerçevesi — her parantez grubu (ve kök) için bir tane. */
    private static final class Frame {
        boolean hasSelect;
        boolean inFrom;
        boolean expectRef;
        boolean expectAlias;
        /** Bu grup bir tablo referansı yerinde açıldı (türetilmiş tablo / fonksiyon / iç içe birleşim) — kapanınca takma ad beklenir. */
        boolean refGroup;
        /** FROM'da bir fonksiyon adı görüldü; sıradaki parantez onun argümanlarıdır. */
        boolean functionPending;
        /** Bu grup FROM'daki bir fonksiyonun argümanları. */
        boolean functionGroup;
        /** Beklenen takma ad bir fonksiyon sonucunun (satır kaynağı değil — içeriği argümanlarıyla denetlenir). */
        boolean aliasOfFunction;
    }

    /**
     * Sorguyu denetler.
     *
     * @param sql    yorumları temizlenmiş tek ifade (bkz. {@code SqlPlaygroundService.sanitize})
     * @param schema hassas kolon şeması
     */
    public static Verdict check(String sql, SqlSensitiveColumns.Schema schema) {
        if (sql == null) return Verdict.pass(false);
        SqlLexer.Lexed lexed = SqlLexer.lex(sql, DbType.POSTGRESQL);
        if (lexed.error() != null) {
            // Çözümlenemeyen metin: bir hassas tablo / görünüm adı GEÇİYORSA reddet (kör ama kapalı), geçmiyorsa
            // veritabanının kendi hatasına bırak.
            String lower = sql.toLowerCase(Locale.ROOT);
            for (String t : schema.byTable().keySet()) if (lower.contains(t)) return Verdict.reject(t, R_UNPARSED);
            for (String t : SqlSensitiveColumns.STATS_RELATIONS) if (lower.contains(t)) return Verdict.reject(t, R_STATS);
            return Verdict.pass(false);
        }
        List<Token> tok = lexed.tokens();
        int n = tok.size();

        // ── Her durumda geçerli kurallar + hassas tablo taraması ─────────────────────────────────────────────
        Set<String> sensitiveTables = new HashSet<>();
        for (int k = 0; k < n; k++) {
            Token t = tok.get(k);
            if (!t.isName()) continue;
            if (t.kind() == Kind.QUOTED && (t.text().startsWith("U&") || t.text().startsWith("u&"))) {
                return Verdict.reject(t.text(), R_UNICODE);
            }
            String low = low(t);
            if (SqlSensitiveColumns.isStatsRelation(low)) return Verdict.reject(low, R_STATS);
            if (SqlSensitiveColumns.isDynamicSqlFunction(low) && k + 1 < n && tok.get(k + 1).isSymbol('(')) {
                return Verdict.reject(low, R_DYNAMIC);
            }
            if (schema.hasSensitiveColumns(low)) sensitiveTables.add(low);
        }
        if (sensitiveTables.isEmpty()) return Verdict.pass(false);
        String firstTable = sensitiveTables.iterator().next();

        Set<String> sensitiveCols = new HashSet<>();
        for (String t : sensitiveTables) sensitiveCols.addAll(schema.columnsOf(t));

        // ── Parantez eşleşmesi + derinlik ────────────────────────────────────────────────────────────────────
        int[] depth = new int[n];
        int[] match = new int[n];
        int[] enclosing = new int[n];   // belirteci içeren en içteki '(' (yoksa -1)
        java.util.Arrays.fill(match, -1);
        Deque<Integer> open = new ArrayDeque<>();
        for (int k = 0; k < n; k++) {
            depth[k] = open.size();
            enclosing[k] = open.isEmpty() ? -1 : open.peek();
            Token t = tok.get(k);
            if (t.isSymbol('(')) open.push(k);
            else if (t.isSymbol(')')) {
                if (open.isEmpty()) return Verdict.reject(firstTable, R_UNPARSED);
                int o = open.pop();
                match[o] = k;
                match[k] = o;
                depth[k] = open.size();
            }
        }
        if (!open.isEmpty()) return Verdict.reject(firstTable, R_UNPARSED);

        // ── Küme işlemi / doğal birleşim ─────────────────────────────────────────────────────────────────────
        for (Token t : tok) {
            if (t.kind() != Kind.WORD) continue;
            if (SET_OPS.contains(t.upper())) return Verdict.reject(firstTable, R_SET_OPERATION);
            if ("NATURAL".equals(t.upper())) return Verdict.reject(firstTable, R_NATURAL);
        }

        // ── Satır kaynakları: tablo referansları, takma adlar, CTE adları ─────────────────────────────────────
        boolean[] tableRef = new boolean[n];
        boolean[] aliasDef = new boolean[n];
        boolean[] cteDef = new boolean[n];
        boolean[] derivedOpen = new boolean[n];   // FROM'daki türetilmiş tablonun '(' (çıktısı kökenle izlenir)
        boolean[] cteBodyOpen = new boolean[n];   // CTE gövdesinin '(' (çıktısı kökenle izlenir)
        Set<String> rowSources = new HashSet<>();
        String aliasListAt = scanRowSources(tok, match, tableRef, aliasDef, cteDef, rowSources, derivedOpen, cteBodyOpen);
        if (aliasListAt != null) return Verdict.reject(aliasListAt, R_ALIAS_LIST);

        // ── Dış seçim listesi ────────────────────────────────────────────────────────────────────────────────
        int mainSelect = -1;
        for (int k = 0; k < n; k++) {
            if (depth[k] == 0 && tok.get(k).isWord("SELECT")) { mainSelect = k; break; }
        }
        if (mainSelect < 0) return Verdict.reject(firstTable, R_UNPARSED);
        int listEnd = n;
        for (int k = mainSelect + 1; k < n; k++) {
            if (depth[k] == 0 && tok.get(k).kind() == Kind.WORD && SELECT_LIST_END.contains(tok.get(k).upper())) {
                listEnd = k;
                break;
            }
        }
        boolean[] allowedSensitive = new boolean[n];
        boolean[] allowedStar = new boolean[n];
        Set<String> sensitiveAliases = new HashSet<>();
        List<Integer> sensitiveItems = new ArrayList<>();
        boolean starItem = false;
        int itemNo = 0;
        int itemStart = mainSelect + 1;
        for (int k = mainSelect + 1; k <= listEnd; k++) {
            boolean boundary = k == listEnd || (depth[k] == 0 && tok.get(k).isSymbol(','));
            if (!boundary) continue;
            itemNo++;
            Item it = classify(tok, itemStart, k, itemNo == 1);
            if (it.star) {
                starItem = true;
                for (int x = itemStart; x < k; x++) allowedStar[x] = true;
            } else if (it.columnIdx >= 0 && sensitiveCols.contains(low(tok.get(it.columnIdx)))) {
                sensitiveItems.add(itemNo);
                for (int x = itemStart; x < k; x++) allowedSensitive[x] = true;
                if (it.aliasIdx >= 0) sensitiveAliases.add(low(tok.get(it.aliasIdx)));
            }
            itemStart = k + 1;
        }

        // ── Hassas kolon (ve takma adı) yalnız çıplak dış öğede ───────────────────────────────────────────────
        for (int k = 0; k < n; k++) {
            Token t = tok.get(k);
            if (!t.isName()) continue;
            String low = low(t);
            if ((sensitiveCols.contains(low) || sensitiveAliases.contains(low)) && !allowedSensitive[k]) {
                return Verdict.reject(unquoted(t), R_EXPRESSION);
            }
        }

        // ── Satır kaynağı adı yalnız referans / tanım / nitelik yerinde ───────────────────────────────────────
        for (int k = 0; k < n; k++) {
            Token t = tok.get(k);
            if (!t.isName()) continue;
            String low = low(t);
            if (!rowSources.contains(low) && !sensitiveTables.contains(low)) continue;
            if (tableRef[k] || aliasDef[k] || cteDef[k]) continue;
            // Önünde nokta: şema nitelikli ad (public.app_users) referans zincirinin parçası olarak işaretlenir; bir
            // kolon (u.password_hash) ise hassas kolon kuralı zaten baktı. Burada yalnız değer kullanımı aranır.
            if (k > 0 && tok.get(k - 1).isSymbol('.') && !(k + 1 < n && tok.get(k + 1).isSymbol('.'))) continue;
            if (k + 1 < n && tok.get(k + 1).isSymbol('.')) {
                if (k + 2 < n && tok.get(k + 2).isSymbol('*') && !allowedStar[k]) return Verdict.reject(unquoted(t), R_WHOLE_ROW);
                continue;   // nitelik: u.username
            }
            if (k + 1 < n && tok.get(k + 1).isSymbol('(') && !sensitiveTables.contains(low)) continue;   // fonksiyon çağrısı
            return Verdict.reject(unquoted(t), R_WHOLE_ROW);
        }

        // ── * ve TABLE yalnız çıktısı kökenle izlenen yerde ───────────────────────────────────────────────────
        // İfade konumundaki (SELECT * …) / (TABLE …) bütün satırı DEĞER yapar: ROW(…) < (SELECT * FROM app_users …),
        // IN / ANY / ALL (SELECT * …), ARRAY(SELECT * …), GREATEST(ROW…, (TABLE …)) sözlük sırasıyla karşılaştırılır —
        // hiçbir hassas kolon adı geçmeden ikili aramayla değeri okuyan bir kâhin. Serbest: en dış seçim listesi, FROM'daki
        // türetilmiş tablo ve CTE gövdesi (çıktıları köken maskesiyle izlenir), EXISTS ( … ) (yalnız var / yok döner),
        // count(*) (parantez içi, seçim öğesi değil). Tek geçiş: her grup için son SELECT tutulur; tek başına bir seçim
        // öğesi olan '*' (öncesi SELECT / DISTINCT / ALL / ',' / DISTINCT ON (…), sonrası ',' / ')' / liste sonu) o
        // SELECT'e aittir — 'a * b' çarpımı değildir.
        int[] lastSelect = new int[n + 1];   // dizin: enclosing + 1
        java.util.Arrays.fill(lastSelect, -1);
        for (int k = 0; k < n; k++) {
            Token t = tok.get(k);
            int slot = enclosing[k] + 1;
            if (t.isWord("SELECT")) {
                lastSelect[slot] = k;
                continue;
            }
            if (t.isWord("TABLE") && k + 1 < n && tok.get(k + 1).isName()) {
                if (!originTracked(tok, k, enclosing, derivedOpen, cteBodyOpen)) {
                    return Verdict.reject(unquoted(tok.get(k + 1)), R_WHOLE_ROW);
                }
                continue;
            }
            if (!t.isSymbol('*') || k == 0 || lastSelect[slot] < 0) continue;
            Token prev = tok.get(k - 1);
            boolean starItemStart = prev.isWord("SELECT") || prev.isWord("DISTINCT") || prev.isWord("ALL") || prev.isSymbol(',')
                    || (prev.isSymbol(')') && match[k - 1] >= 2 && tok.get(match[k - 1] - 1).isWord("ON")
                        && tok.get(match[k - 1] - 2).isWord("DISTINCT"));
            if (!starItemStart) continue;
            boolean itemEnd = k + 1 == n || tok.get(k + 1).isSymbol(',') || tok.get(k + 1).isSymbol(')')
                    || (tok.get(k + 1).kind() == Kind.WORD && SELECT_LIST_END.contains(tok.get(k + 1).upper()));
            if (!itemEnd) continue;
            if (!originTracked(tok, lastSelect[slot], enclosing, derivedOpen, cteBodyOpen)) {
                return Verdict.reject("*", R_WHOLE_ROW);
            }
        }

        // ── ORDER BY / GROUP BY / DISTINCT ON konum başvurusu ─────────────────────────────────────────────────
        // En dış sorgunun ORDER BY / GROUP BY'ında yalnız * ya da hassas öğeye başvuran konum reddedilir; alt sorgularda
        // (türetilmiş tablo, CTE, ifade, pencere) ve DISTINCT ON içinde HER konum numarası reddedilir — iç seçim listesi
        // izlenmez ve sıralama / gruplama hassas değere göre yapılabilir (sıralama kâhini).
        for (int k = 0; k < n; k++) {
            Token t = tok.get(k);
            boolean orderGroup = (t.isWord("ORDER") || t.isWord("GROUP")) && k + 1 < n && tok.get(k + 1).isWord("BY");
            boolean distinctOn = t.isWord("DISTINCT") && k + 2 < n && tok.get(k + 1).isWord("ON")
                    && tok.get(k + 2).isSymbol('(') && match[k + 2] > k + 2;
            if (!orderGroup && !distinctOn) continue;
            boolean outerOrder = orderGroup && depth[k] == 0;
            int d = distinctOn ? depth[k] + 1 : depth[k];
            int s = distinctOn ? k + 3 : k + 2;
            int stop = distinctOn ? match[k + 2] : n;
            for (int x = s; x <= stop; x++) {
                // İç parantez grupları atlanır (her belirteç yalnız kendi derinliğindeki tarama tarafından ziyaret edilir
                // — iç içe ORDER BY'larda da doğrusal)
                if (x < stop && tok.get(x).isSymbol('(') && match[x] > x && match[x] < stop) {
                    x = match[x];
                    continue;
                }
                boolean end = x == stop || depth[x] < d || (depth[x] == d && (tok.get(x).isSymbol(',')
                        || (tok.get(x).kind() == Kind.WORD && CLAUSE_END.contains(tok.get(x).upper()))));
                if (!end) continue;
                if (x > s && tok.get(s).kind() == Kind.NUMBER && isInteger(tok.get(s).text())) {
                    int ord = parseOrdinal(tok.get(s).text());
                    if (!outerOrder || starItem || sensitiveItems.contains(ord)) {
                        return Verdict.reject(tok.get(s).text(), R_ORDINAL);
                    }
                }
                if (x == stop || depth[x] != d || !tok.get(x).isSymbol(',')) break;
                s = x + 1;
            }
        }
        return Verdict.pass(true);
    }

    // ── Satır kaynakları ──────────────────────────────────────────────────────────────────────────────────────

    /** @return kolon takma ad listesi görülen adın metni (ret), yoksa null */
    private static String scanRowSources(List<Token> tok, int[] match, boolean[] tableRef, boolean[] aliasDef,
                                         boolean[] cteDef, Set<String> rowSources, boolean[] derivedOpen,
                                         boolean[] cteBodyOpen) {
        int n = tok.size();
        // CTE tanımları: WITH [RECURSIVE] ad [ (kolonlar) ] AS [NOT] [MATERIALIZED] ( … ) [, …]
        for (int k = 0; k < n; k++) {
            if (!tok.get(k).isWord("WITH")) continue;
            int p = k + 1;
            if (p < n && tok.get(p).isWord("RECURSIVE")) p++;
            while (p < n && tok.get(p).isName()) {
                int nameIdx = p;
                int q = p + 1;
                if (q < n && tok.get(q).isSymbol('(') && match[q] > q) {
                    // CTE kolon listesi yalnız bir AS ile devam ediyorsa (WITH x(a) AS …) — WITH TIME ZONE vb. değil
                    int after = match[q] + 1;
                    if (after < n && tok.get(after).isWord("AS")) return unquoted(tok.get(nameIdx));
                    break;
                }
                if (q >= n || !tok.get(q).isWord("AS")) break;
                cteDef[nameIdx] = true;
                rowSources.add(low(tok.get(nameIdx)));
                q++;
                if (q < n && tok.get(q).isWord("NOT")) q++;
                if (q < n && tok.get(q).isWord("MATERIALIZED")) q++;
                if (q >= n || !tok.get(q).isSymbol('(') || match[q] < q) break;
                cteBodyOpen[q] = true;
                p = match[q] + 1;
                if (p < n && tok.get(p).isSymbol(',')) p++;
                else break;
            }
        }
        // TABLE ad (TABLE app_users — SELECT * FROM app_users kısaltması)
        for (int k = 0; k + 1 < n; k++) {
            if (tok.get(k).isWord("TABLE") && tok.get(k + 1).isName()) markChain(tok, k + 1, tableRef, rowSources);
        }
        // FROM listeleri (çerçeve yığınıyla)
        Deque<Frame> stack = new ArrayDeque<>();
        Frame cur = new Frame();
        for (int k = 0; k < n; k++) {
            Token t = tok.get(k);
            if (t.isSymbol('(')) {
                Frame child = new Frame();
                if (cur.inFrom && (cur.expectRef || cur.functionPending)) {
                    child.refGroup = true;
                    child.functionGroup = cur.functionPending;
                    if (!cur.functionPending) derivedOpen[k] = true;
                    // Parantezli referans: alt sorgu değilse iç içe birleşim — içi de bir FROM listesidir
                    Token first = k + 1 < tok.size() ? tok.get(k + 1) : null;
                    boolean subquery = first != null && (first.isWord("SELECT") || first.isWord("WITH")
                            || first.isWord("VALUES") || first.isWord("TABLE"));
                    if (!subquery && !cur.functionPending) {
                        child.inFrom = true;
                        child.expectRef = true;
                    }
                    cur.expectRef = false;
                    cur.functionPending = false;
                }
                stack.push(cur);
                cur = child;
                continue;
            }
            if (t.isSymbol(')')) {
                Frame closed = cur;
                cur = stack.isEmpty() ? new Frame() : stack.pop();
                if (closed.refGroup) {
                    cur.expectAlias = true;
                    cur.aliasOfFunction = closed.functionGroup;
                }
                continue;
            }
            if (t.isWord("SELECT")) {
                cur.hasSelect = true;
                cur.inFrom = false;
                cur.expectRef = false;
                cur.expectAlias = false;
                continue;
            }
            if (t.isWord("FROM")) {
                boolean afterDistinct = k > 0 && tok.get(k - 1).isWord("DISTINCT");
                if (cur.hasSelect && !afterDistinct) {
                    cur.inFrom = true;
                    cur.expectRef = true;
                    cur.expectAlias = false;
                }
                continue;
            }
            if (!cur.inFrom) continue;
            if (t.kind() == Kind.WORD && CLAUSE_END.contains(t.upper())) {
                cur.inFrom = false;
                cur.expectRef = false;
                cur.expectAlias = false;
                continue;
            }
            if (cur.expectAlias) {
                cur.expectAlias = false;
                boolean ofFunction = cur.aliasOfFunction;
                cur.aliasOfFunction = false;
                int aliasIdx = -1;
                if (t.isWord("AS") && k + 1 < n && tok.get(k + 1).isName()) aliasIdx = k + 1;
                else if (t.isName() && !(t.kind() == Kind.WORD && ALIAS_STOP.contains(t.upper()))) aliasIdx = k;
                if (aliasIdx >= 0) {
                    aliasDef[aliasIdx] = true;
                    int next = aliasIdx + 1;
                    if (!ofFunction) {
                        rowSources.add(low(tok.get(aliasIdx)));
                        if (next < n && tok.get(next).isSymbol('(')) return unquoted(tok.get(aliasIdx));
                    }
                    k = aliasIdx;
                    continue;
                }
            }
            if (t.isWord("JOIN") || t.isSymbol(',')) {
                cur.expectRef = true;
                continue;
            }
            if (t.isWord("ON") || t.isWord("USING")) {
                cur.expectRef = false;
                continue;
            }
            if (cur.expectRef) {
                if (t.isWord("ONLY") || t.isWord("LATERAL")) continue;
                if (t.isName()) {
                    int end = markChain(tok, k, tableRef, null);
                    cur.expectRef = false;
                    if (end + 1 < n && tok.get(end + 1).isSymbol('(')) {
                        cur.functionPending = true;   // FROM'da fonksiyon: parantezi kapanınca takma ad beklenir
                    } else {
                        rowSources.add(low(tok.get(end)));
                        cur.expectAlias = true;
                    }
                    k = end;
                }
            }
        }
        return null;
    }

    /** {@code ad ( . ad )*} zincirini referans olarak işaretler; son ad dizinini döner. */
    private static int markChain(List<Token> tok, int k, boolean[] tableRef, Set<String> rowSources) {
        int n = tok.size();
        int p = k;
        tableRef[p] = true;
        while (p + 2 < n && tok.get(p + 1).isSymbol('.') && tok.get(p + 2).isName()) {
            p += 2;
            tableRef[p] = true;
        }
        if (rowSources != null) rowSources.add(low(tok.get(p)));
        return p;
    }

    /**
     * {@code SELECT} / {@code TABLE} belirtecinin çıktısı köken maskesiyle izlenen bir yerde mi: en dış sorgu, FROM'daki
     * türetilmiş tablo, CTE gövdesi ya da {@code EXISTS ( … )}.
     */
    private static boolean originTracked(List<Token> tok, int k, int[] enclosing, boolean[] derivedOpen,
                                         boolean[] cteBodyOpen) {
        int p = enclosing[k];
        if (p < 0) return true;
        if (derivedOpen[p] || cteBodyOpen[p]) return true;
        return p > 0 && tok.get(p - 1).isWord("EXISTS");
    }

    // ── Seçim öğesi ───────────────────────────────────────────────────────────────────────────────────────────

    private record Item(boolean star, int columnIdx, int aliasIdx) {
        static final Item OTHER = new Item(false, -1, -1);
    }

    /** {@code [DISTINCT|ALL] ad(.ad)* [[AS] takma_ad]} ya da {@code *} / {@code ad.*}; başka her biçim OTHER. */
    private static Item classify(List<Token> tok, int s, int e, boolean first) {
        int p = s;
        if (first && p < e && (tok.get(p).isWord("DISTINCT") || tok.get(p).isWord("ALL"))) p++;
        if (p >= e) return Item.OTHER;
        if (tok.get(p).isSymbol('*')) return p + 1 == e ? new Item(true, -1, -1) : Item.OTHER;
        if (!tok.get(p).isName()) return Item.OTHER;
        p++;
        while (p + 1 < e && tok.get(p).isSymbol('.')) {
            Token nx = tok.get(p + 1);
            if (nx.isSymbol('*')) return p + 2 == e ? new Item(true, -1, -1) : Item.OTHER;
            if (!nx.isName()) return Item.OTHER;
            p += 2;
        }
        int columnIdx = p - 1;
        int aliasIdx = -1;
        if (p < e && tok.get(p).isWord("AS")) {
            if (p + 1 < e && tok.get(p + 1).isName()) {
                aliasIdx = p + 1;
                p += 2;
            } else {
                return Item.OTHER;
            }
        } else if (p < e && tok.get(p).isName()) {
            aliasIdx = p;
            p++;
        }
        return p == e ? new Item(false, columnIdx, aliasIdx) : Item.OTHER;
    }

    // ── Yardımcılar ───────────────────────────────────────────────────────────────────────────────────────────

    private static String low(Token t) {
        return t.upper() == null ? t.text().toLowerCase(Locale.ROOT) : t.upper().toLowerCase(Locale.ROOT);
    }

    private static String unquoted(Token t) {
        if (t.kind() == Kind.QUOTED) {
            String s = t.text();
            int start = s.startsWith("U&") || s.startsWith("u&") ? 3 : 1;
            return s.length() > start ? s.substring(start, s.length() - 1) : s;
        }
        return t.text();
    }

    private static boolean isInteger(String s) {
        if (s.isEmpty() || s.length() > 9) return false;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) < '0' || s.charAt(i) > '9') return false;
        return true;
    }

    private static int parseOrdinal(String s) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return -1; }
    }
}
