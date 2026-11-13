package com.sitemonitor.service.sqlplayground;

import com.sitemonitor.service.dbquery.DbType;
import com.sitemonitor.service.dbquery.SqlLexer;
import com.sitemonitor.service.dbquery.SqlLexer.Kind;
import com.sitemonitor.service.dbquery.SqlLexer.Token;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 *   <li>{@link #R_TABLE_REF}: FROM öğesi bekçinin tam çözümleyemediği bir biçimde ({@code ONLY}, miras yıldızı
 *       {@code tablo *}, {@code TABLESAMPLE}, {@code ROWS FROM}, {@code WITH ORDINALITY}, takma addan sonra beklenmeyen
 *       belirteç …). FROM listesi bir durum makinesiyle okunur ve HER durumda yalnız bilinen belirteçler kabul edilir:
 *       takma adı belirsiz bir satır kaynağı asla serbest kalmaz (KAPALI BAŞARISIZLIK, 2026-11-13 3. tur — önceki
 *       "tanıdığını reddet" çözümleme {@code app_users * u} ve çıplak {@code rows} takma adıyla atlatıldı). Öğeden sonra
 *       gelen ve birleşim / yan tümce sözcüğü olmayan HER ad takma ad sayılır.</li>
 *   <li>{@link #R_EXPRESSION}: hassas kolon adı (ya da onun takma adı) çıplak dış seçim öğesi dışında.</li>
 *   <li>{@link #R_WHOLE_ROW}: tablo / takma ad / CTE adı DEĞER olarak ({@code to_jsonb(u)}, {@code u::text},
 *       {@code SELECT app_users FROM app_users}, fonksiyon içinde {@code u.*}) ve ifade konumunda {@code (SELECT * …)} /
 *       {@code (TABLE …)} (satır karşılaştırma kâhini) — {@code *} / {@code TABLE} yalnız en dış seçim listesinde,
 *       türetilmiş tabloda, CTE gövdesinde, {@code EXISTS} içinde.</li>
 *   <li>{@link #R_ATTRIBUTE_FUNCTION}: {@code u.to_jsonb} / {@code u.concat} / {@code app_users.json_agg} — PostgreSQL
 *       {@code u}'nun o adlı kolonu yoksa bunu {@code f(u)} çağrısı yapar ve satırın tamamı değer olur. Ad, satırı
 *       argüman alan bir fonksiyonunsa ({@link SqlSensitiveColumns.Schema#isRowFunction}: canlı {@code pg_proc} + taban
 *       liste) ve niteleyici o kolonu taşıdığı BİLİNEN bir taban tablo değilse reddedilir.</li>
 *   <li>{@link #R_ORDINAL}: en dışta {@code ORDER BY 3} / {@code GROUP BY 1} hassas ya da {@code *} öğesine konumla
 *       başvuru; alt sorguda ve {@code DISTINCT ON} içinde her konum numarası. Konum parantezler ve tekli işaretler
 *       soyularak ({@code (2)}, {@code - -2} — PostgreSQL ikisini de konum sayar) ve PostgreSQL 16 tamsayı biçimlerinde
 *       ({@code 0x2}, {@code 0o2}, {@code 0b10}, {@code 0_2}) tanınır; {@code GROUP BY ALL|DISTINCT}, {@code ROLLUP} /
 *       {@code CUBE} / {@code GROUPING SETS} ve parantezli liste içi dahil. Çözümlenemeyen sayı biçimi konum sayılır.</li>
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
    public static final String R_TABLE_REF = "table_reference";
    public static final String R_ATTRIBUTE_FUNCTION = "attribute_function";

    /** Kararlı ret nedenleri (istemci metni bunlarla çevirebilir). */
    public static final List<String> REASONS = List.of(R_UNICODE, R_STATS, R_DYNAMIC, R_SET_OPERATION, R_NATURAL,
            R_ALIAS_LIST, R_EXPRESSION, R_WHOLE_ROW, R_ORDINAL, R_UNPARSED, R_TABLE_REF, R_ATTRIBUTE_FUNCTION);

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

    /** FROM öğesinden sonra gelebilen birleşim sözcükleri (PostgreSQL'de hiçbiri takma ad olamaz). */
    private static final Set<String> JOIN_WORDS = Set.of("JOIN", "INNER", "LEFT", "RIGHT", "FULL", "CROSS", "NATURAL",
            "OUTER");

    private static final Set<String> SET_OPS = Set.of("UNION", "INTERSECT", "EXCEPT", "MINUS");

    /** Konum numarasının içine bakılan gruplama yapıları ({@code GROUP BY ROLLUP (…)} / {@code CUBE (…)}). */
    private static final Set<String> GROUPING_WORDS = Set.of("ROLLUP", "CUBE");

    /** Niteleyici hedefi: taban tablo değil (türetilmiş tablo, CTE, fonksiyon, birleşim, USING takma adı …). */
    private static final String NON_BASE = "";

    /** PostgreSQL 16+: takma adsız türetilmiş tablonun iç adı (bugün başvurulamıyor — yine de satır kaynağı sayılır). */
    static final String UNNAMED_SUBQUERY = "unnamed_subquery";

    // FROM listesi durumları — her durumda yalnız bilinen belirteçler kabul edilir, gerisi R_TABLE_REF
    private static final int F_NONE = 0;
    /** Öğe bekleniyor: FROM, ',' ya da JOIN'den sonra. */
    private static final int F_ITEM = 1;
    /** Öğe bitti; takma ad gelebilir. */
    private static final int F_AFTER_ITEM = 2;
    /** Takma ad işlendi (ya da yok); yalnız ',' / birleşim sözcüğü / ON / USING / yan tümce sonu gelebilir. */
    private static final int F_AFTER_ALIAS = 3;
    /** Birleşim türü sözcükleri (LEFT OUTER …) okunuyor; JOIN bekleniyor. */
    private static final int F_JOIN = 4;
    /** ON / USING koşulu (ifade) — JOIN, ',' ya da yan tümce sonuna dek. */
    private static final int F_COND = 5;

    /** Ayrıştırma çerçevesi — her parantez grubu (ve kök) için bir tane. */
    private static final class Frame {
        boolean hasSelect;
        boolean inFrom;
        int state = F_NONE;
        /** Bu grup bir FROM öğesi yerinde açıldı (türetilmiş tablo / fonksiyon argümanı / iç içe birleşim). */
        boolean refGroup;
        /** FROM'da bir fonksiyon adı görüldü; sıradaki parantez onun argümanlarıdır. */
        boolean functionPending;
        /** Bu grup FROM'daki bir fonksiyonun argümanları. */
        boolean functionGroup;
        /** Bu grup bir fonksiyon takma adının kolon tanım listesi ({@code AS t(a int)}). */
        boolean coldefGroup;
        /** Takma adı beklenen öğe bir fonksiyon sonucu (satır kaynağı değil — içeriği argümanlarıyla denetlenir). */
        boolean itemIsFunction;
        /** Takma adı beklenen öğe şemasız / {@code public.} bir taban tabloysa adı; değilse null. */
        String itemRelation;
        /** Fonksiyon öğesinin adı (takma ad yoksa PostgreSQL onu satır kaynağı adı yapar). */
        String itemFunction;
        /** Fonksiyon takma adından sonra kolon tanım listesi gelebilir. */
        boolean coldefAllowed;
        /** ON koşulundaki açık köşeli parantez sayısı ({@code ARRAY[1, 2]} içindeki virgül öğe ayırıcı değil). */
        int brackets;
    }

    /** {@link #scanRowSources} reddi. */
    private record Reject(String name, String reason) {}

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
        Map<String, Set<String>> qualifiers = new HashMap<>();   // ad → taban tablo(lar) ya da NON_BASE
        Reject scan = scanRowSources(tok, match, tableRef, aliasDef, cteDef, rowSources, qualifiers, derivedOpen,
                cteBodyOpen);
        if (scan != null) return Verdict.reject(scan.name(), scan.reason());
        rowSources.add(UNNAMED_SUBQUERY);

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

        // ── Alan biçiminde fonksiyon çağrısı: u.to_jsonb ≡ to_jsonb(u) ────────────────────────────────────────
        // PostgreSQL A.B'yi önce A'nın B kolonu olarak çözer; kolon yoksa B(A) çağrısı dener (satırın tamamı argüman).
        // B satır alan bir fonksiyonsa yalnız A'nın B kolonunu taşıdığı BİLİNEN taban tablo olduğu durumda serbest —
        // türetilmiş tablo / CTE / bilinmeyen niteleyici / (ifade).B hep reddedilir. B'den sonra '(' gelirse bu
        // şema nitelikli bir fonksiyon çağrısıdır (pg_catalog.to_jsonb(…)) — argümanlar yukarıda denetlendi.
        for (int k = 1; k + 1 < n; k++) {
            if (!tok.get(k).isSymbol('.') || !tok.get(k + 1).isName()) continue;
            String f = low(tok.get(k + 1));
            if (!schema.isRowFunction(f)) continue;
            if (k + 2 < n && tok.get(k + 2).isSymbol('(')) continue;
            Token q = tok.get(k - 1);
            if (q.isName() && qualifiedColumnIsReal(qualifiers.get(low(q)), pgName(tok.get(k + 1)), schema)) continue;
            String shown = q.isName() ? unquoted(q) + "." + unquoted(tok.get(k + 1)) : unquoted(tok.get(k + 1));
            return Verdict.reject(shown, R_ATTRIBUTE_FUNCTION);
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
        // izlenmez ve sıralama / gruplama hassas değere göre yapılabilir (sıralama kâhini). Her yan tümce yalnız kendi
        // derinliğini (ve öğe başındaki gruplara inişi) tarar — iç içe yan tümcelerde de doğrusal.
        for (int k = 0; k < n; k++) {
            Token t = tok.get(k);
            boolean orderGroup = (t.isWord("ORDER") || t.isWord("GROUP")) && k + 1 < n && tok.get(k + 1).isWord("BY");
            boolean distinctOn = t.isWord("DISTINCT") && k + 2 < n && tok.get(k + 1).isWord("ON")
                    && tok.get(k + 2).isSymbol('(') && match[k + 2] > k + 2;
            if (!orderGroup && !distinctOn) continue;
            boolean outerOrder = orderGroup && depth[k] == 0;
            int from, to;
            if (distinctOn) {
                from = k + 3;
                to = match[k + 2];
            } else {
                from = k + 2;
                // GROUP BY ALL | DISTINCT (PostgreSQL 14+ küme niceleyicisi)
                if (t.isWord("GROUP") && from < n && (tok.get(from).isWord("ALL") || tok.get(from).isWord("DISTINCT"))) from++;
                to = clauseEnd(tok, match, depth, from, depth[k]);
            }
            String bad = ordinalViolation(tok, match, from, to, outerOrder, starItem, sensitiveItems);
            if (bad != null) return Verdict.reject(bad, R_ORDINAL);
        }
        return Verdict.pass(true);
    }

    // ── Satır kaynakları ──────────────────────────────────────────────────────────────────────────────────────

    /** @return ret (kolon takma ad listesi / çözümlenemeyen FROM öğesi), yoksa null */
    private static Reject scanRowSources(List<Token> tok, int[] match, boolean[] tableRef, boolean[] aliasDef,
                                         boolean[] cteDef, Set<String> rowSources, Map<String, Set<String>> qualifiers,
                                         boolean[] derivedOpen, boolean[] cteBodyOpen) {
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
                    if (after < n && tok.get(after).isWord("AS")) return new Reject(unquoted(tok.get(nameIdx)), R_ALIAS_LIST);
                    break;
                }
                if (q >= n || !tok.get(q).isWord("AS")) break;
                cteDef[nameIdx] = true;
                rowSources.add(low(tok.get(nameIdx)));
                qualify(qualifiers, low(tok.get(nameIdx)), NON_BASE);
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
        // FROM listeleri (çerçeve yığını + durum makinesi)
        Deque<Frame> stack = new ArrayDeque<>();
        Frame cur = new Frame();
        for (int k = 0; k < n; k++) {
            Token t = tok.get(k);
            if (t.isSymbol('(')) {
                Frame child = new Frame();
                if (cur.inFrom) {
                    if (cur.functionPending) {
                        // FROM'daki fonksiyonun argümanları
                        child.refGroup = true;
                        child.functionGroup = true;
                        cur.functionPending = false;
                    } else if (cur.state == F_ITEM) {
                        // Türetilmiş tablo ya da parantezli birleşim — alt sorgu değilse içi de bir FROM listesidir
                        child.refGroup = true;
                        derivedOpen[k] = true;
                        Token first = k + 1 < n ? tok.get(k + 1) : null;
                        boolean subquery = first != null && (first.isWord("SELECT") || first.isWord("WITH")
                                || first.isWord("VALUES") || first.isWord("TABLE"));
                        if (!subquery) {
                            child.inFrom = true;
                            child.state = F_ITEM;
                        }
                    } else if (cur.state == F_AFTER_ALIAS && cur.coldefAllowed) {
                        child.coldefGroup = true;   // fonksiyon takma adının kolon tanımları: AS t(a int, b text)
                        cur.coldefAllowed = false;
                    } else if (cur.state != F_COND) {
                        return new Reject("(", R_TABLE_REF);   // öğe / takma addan sonra beklenmeyen parantez
                    }
                }
                stack.push(cur);
                cur = child;
                continue;
            }
            if (t.isSymbol(')')) {
                Frame closed = cur;
                cur = stack.isEmpty() ? new Frame() : stack.pop();
                if (cur.inFrom) {
                    if (closed.refGroup) {
                        cur.state = F_AFTER_ITEM;
                        cur.itemIsFunction = closed.functionGroup;
                        cur.itemRelation = null;
                        if (!closed.functionGroup) cur.itemFunction = null;
                    } else if (closed.coldefGroup) {
                        cur.state = F_AFTER_ALIAS;
                    }
                }
                continue;
            }
            if (t.isWord("SELECT")) {
                cur.hasSelect = true;
                cur.inFrom = false;
                cur.state = F_NONE;
                continue;
            }
            if (t.isWord("FROM")) {
                boolean afterDistinct = k > 0 && tok.get(k - 1).isWord("DISTINCT");
                if (cur.hasSelect && !afterDistinct) {
                    cur.inFrom = true;
                    cur.state = F_ITEM;
                }
                continue;
            }
            if (!cur.inFrom) continue;
            if ((t.kind() == Kind.WORD && CLAUSE_END.contains(t.upper())) || t.isSymbol(';')) {
                cur.inFrom = false;
                cur.state = F_NONE;
                cur.brackets = 0;
                continue;
            }
            switch (cur.state) {
                case F_ITEM -> {
                    if (t.isWord("LATERAL")) continue;
                    if (t.isWord("ONLY")) return new Reject("ONLY", R_TABLE_REF);
                    if (t.isWord("ROWS") && k + 1 < n && tok.get(k + 1).isWord("FROM")) return new Reject("ROWS FROM", R_TABLE_REF);
                    if (!t.isName()) return new Reject(t.text(), R_TABLE_REF);
                    int end = markChain(tok, k, tableRef, null);
                    String name = low(tok.get(end));
                    if (end + 1 < n && tok.get(end + 1).isSymbol('(')) {
                        cur.functionPending = true;   // FROM'da fonksiyon: parantezi kapanınca takma ad beklenir
                        cur.itemFunction = name;
                    } else {
                        rowSources.add(name);
                        boolean base = end == k || (end == k + 2 && "public".equals(low(tok.get(k))));
                        cur.itemRelation = base ? name : null;
                        cur.itemIsFunction = false;
                        cur.itemFunction = null;
                        cur.state = F_AFTER_ITEM;
                        qualify(qualifiers, name, base ? name : NON_BASE);
                    }
                    k = end;
                }
                case F_AFTER_ITEM -> {
                    int aliasIdx = -1;
                    if (t.isWord("AS")) {
                        if (k + 1 < n && tok.get(k + 1).isName()) aliasIdx = k + 1;
                        else return new Reject("AS", R_TABLE_REF);
                    } else if (t.kind() == Kind.QUOTED) {
                        aliasIdx = k;
                    } else if (t.kind() == Kind.WORD) {
                        if (t.isWord("TABLESAMPLE") || t.isWord("WITH")) return new Reject(t.text(), R_TABLE_REF);
                        if (!JOIN_WORDS.contains(t.upper()) && !t.isWord("ON") && !t.isWord("USING")) aliasIdx = k;
                    } else if (!t.isSymbol(',')) {
                        return new Reject(t.text(), R_TABLE_REF);   // ör. 'app_users * u' (miras yıldızı)
                    }
                    if (aliasIdx < 0) {
                        // Takma ad yok: fonksiyonun adı satır kaynağı adı olur; belirteç izleyici olarak yeniden işlenir
                        if (cur.itemIsFunction && cur.itemFunction != null) qualify(qualifiers, cur.itemFunction, NON_BASE);
                        cur.state = F_AFTER_ALIAS;
                        k--;
                        continue;
                    }
                    Token a = tok.get(aliasIdx);
                    String alias = low(a);
                    aliasDef[aliasIdx] = true;
                    if (!cur.itemIsFunction) {
                        rowSources.add(alias);
                        qualify(qualifiers, alias, cur.itemRelation != null ? cur.itemRelation : NON_BASE);
                        if (aliasIdx + 1 < n && tok.get(aliasIdx + 1).isSymbol('(')) return new Reject(unquoted(a), R_ALIAS_LIST);
                    } else {
                        qualify(qualifiers, alias, NON_BASE);
                        cur.coldefAllowed = aliasIdx + 1 < n && tok.get(aliasIdx + 1).isSymbol('(');
                    }
                    cur.state = F_AFTER_ALIAS;
                    k = aliasIdx;
                }
                case F_AFTER_ALIAS -> {
                    cur.coldefAllowed = false;
                    if (t.isSymbol(',') || t.isWord("JOIN")) cur.state = F_ITEM;
                    else if (t.kind() == Kind.WORD && JOIN_WORDS.contains(t.upper())) cur.state = F_JOIN;
                    else if (t.isWord("ON") || t.isWord("USING")) cur.state = F_COND;
                    else return new Reject(t.text(), R_TABLE_REF);   // ör. takma addan sonra TABLESAMPLE / ikinci ad
                }
                case F_JOIN -> {
                    if (t.isWord("JOIN")) cur.state = F_ITEM;
                    else if (!(t.kind() == Kind.WORD && JOIN_WORDS.contains(t.upper()))) return new Reject(t.text(), R_TABLE_REF);
                }
                case F_COND -> {
                    if (t.isSymbol('[')) {
                        cur.brackets++;
                    } else if (t.isSymbol(']')) {
                        if (cur.brackets > 0) cur.brackets--;
                    } else if (cur.brackets == 0 && (t.isSymbol(',') || t.isWord("JOIN"))) {
                        cur.state = F_ITEM;
                    } else if (t.isWord("AS") && k + 1 < n && tok.get(k + 1).isName()) {
                        // USING (…) AS j — birleşim takma adı (yalnız USING kolonlarını taşır; yine de satır kaynağı)
                        aliasDef[k + 1] = true;
                        rowSources.add(low(tok.get(k + 1)));
                        qualify(qualifiers, low(tok.get(k + 1)), NON_BASE);
                        k++;
                    }
                    // diğer belirteçler: koşul ifadesi
                }
                default -> { }
            }
        }
        return null;
    }

    private static void qualify(Map<String, Set<String>> qualifiers, String name, String target) {
        qualifiers.computeIfAbsent(name, x -> new HashSet<>()).add(target);
    }

    /** Niteleyicinin bütün hedefleri bu adlı kolonu taşıdığı BİLİNEN taban tablolar mı (alan biçiminde çağrı değil)? */
    private static boolean qualifiedColumnIsReal(Set<String> targets, String column, SqlSensitiveColumns.Schema schema) {
        if (targets == null || targets.isEmpty()) return false;
        for (String t : targets) {
            if (NON_BASE.equals(t) || !schema.hasColumn(t, column)) return false;
        }
        return true;
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

    // ── Konum numarası ────────────────────────────────────────────────────────────────────────────────────────

    /** Konum değil (ondalık / çok büyük sayı — PostgreSQL bunu sabit sayar). */
    static final long NOT_ORDINAL = -1;
    /** Sayı biçimi çözülemedi — temkinli: konum sayılır, hangi öğe olduğu bilinmez. */
    static final long UNKNOWN_ORDINAL = -2;

    /** {@code from} derinliğindeki yan tümcenin sonu: aynı derinlikte yan tümce sözcüğü / ';' ya da kapsayan ')'. */
    private static int clauseEnd(List<Token> tok, int[] match, int[] depth, int from, int d) {
        int n = tok.size();
        for (int x = from; x < n; x++) {
            Token t = tok.get(x);
            if (depth[x] < d) return x;
            if (t.isSymbol('(') && match[x] > x) {
                x = match[x];
                continue;
            }
            if (t.isSymbol(';') || (t.kind() == Kind.WORD && CLAUSE_END.contains(t.upper()))) return x;
        }
        return n;
    }

    /**
     * Yan tümcenin öğelerinde ([from, to), virgülle ayrılmış) konum numarası arar. Öğe başındaki parantezler ve tekli
     * işaretler soyulur; parantezli liste, {@code ROLLUP} / {@code CUBE} / {@code GROUPING SETS} içine inilir (açık iş
     * listesiyle — yığın taşmaz). Dönen: reddedilecek konumun metni, yoksa null.
     */
    private static String ordinalViolation(List<Token> tok, int[] match, int from, int to, boolean outer,
                                           boolean starItem, List<Integer> sensitiveItems) {
        Deque<int[]> work = new ArrayDeque<>();
        work.push(new int[]{from, to});
        while (!work.isEmpty()) {
            int[] r = work.pop();
            int s = r[0];
            for (int x = r[0]; x <= r[1]; x++) {
                boolean boundary = x == r[1] || tok.get(x).isSymbol(',');
                if (!boundary) {
                    if (tok.get(x).isSymbol('(') && match[x] > x && match[x] < r[1]) x = match[x];
                    continue;
                }
                String bad = itemOrdinal(tok, match, s, x, outer, starItem, sensitiveItems, work);
                if (bad != null) return bad;
                s = x + 1;
            }
        }
        return null;
    }

    private static String itemOrdinal(List<Token> tok, int[] match, int s, int e, boolean outer, boolean starItem,
                                      List<Integer> sensitiveItems, Deque<int[]> work) {
        int p = s;
        while (p < e && (tok.get(p).isSymbol('-') || tok.get(p).isSymbol('+'))) p++;
        if (p >= e) return null;
        Token t = tok.get(p);
        if (t.kind() == Kind.NUMBER) {
            StringBuilder lit = new StringBuilder(t.text());
            int q = p + 1;
            while (q < e && tok.get(q).start() == tok.get(q - 1).end()
                    && (tok.get(q).kind() == Kind.WORD || tok.get(q).kind() == Kind.NUMBER)) {
                lit.append(tok.get(q).text());
                q++;
            }
            long ord = pgIntegerLiteral(lit.toString());
            if (ord == NOT_ORDINAL) return null;
            if (!outer || starItem || ord == UNKNOWN_ORDINAL || ord > Integer.MAX_VALUE
                    || sensitiveItems.contains((int) ord)) {
                return lit.toString();
            }
            return null;
        }
        if (t.isSymbol('(') && match[p] > p && match[p] <= e) {
            work.push(new int[]{p + 1, match[p]});
        } else if (t.kind() == Kind.WORD && GROUPING_WORDS.contains(t.upper()) && p + 1 < e && tok.get(p + 1).isSymbol('(')
                && match[p + 1] > p + 1 && match[p + 1] <= e) {
            work.push(new int[]{p + 2, match[p + 1]});
        } else if (t.isWord("GROUPING") && p + 2 < e && tok.get(p + 1).isWord("SETS") && tok.get(p + 2).isSymbol('(')
                && match[p + 2] > p + 2 && match[p + 2] <= e) {
            work.push(new int[]{p + 3, match[p + 2]});
        }
        return null;
    }

    /**
     * PostgreSQL tamsayı sabiti mi (16+: {@code 0x1F}, {@code 0o17}, {@code 0b101}, {@code 1_000})? Değer (≥ 0);
     * ondalık / üslü / int4 sınırını aşan sayı {@link #NOT_ORDINAL} (PostgreSQL bunu sabit sayar, konum değil);
     * çözülemeyen biçim {@link #UNKNOWN_ORDINAL}. Sınırlı: en çok 64 karakter.
     */
    static long pgIntegerLiteral(String text) {
        if (text == null || text.isEmpty() || text.length() > 64) return UNKNOWN_ORDINAL;
        int radix = 10;
        String digits = text;
        if (text.length() > 2 && text.charAt(0) == '0') {
            char c = Character.toLowerCase(text.charAt(1));
            if (c == 'x') radix = 16;
            else if (c == 'o') radix = 8;
            else if (c == 'b') radix = 2;
            if (radix != 10) digits = text.substring(2);
        }
        StringBuilder clean = new StringBuilder(digits.length());
        boolean prevDigit = radix != 10;   // önekten hemen sonra '_' serbest (0x_1F)
        boolean floatLike = false;
        for (int i = 0; i < digits.length(); i++) {
            char c = digits.charAt(i);
            if (c == '_') {
                if (!prevDigit) return UNKNOWN_ORDINAL;
                prevDigit = false;
                continue;
            }
            if (Character.digit(c, radix) >= 0 && c < 128) {
                clean.append(c);
                prevDigit = true;
                continue;
            }
            if (radix == 10 && (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-')) {
                floatLike = true;
                prevDigit = false;
                continue;
            }
            return UNKNOWN_ORDINAL;
        }
        if (floatLike) return NOT_ORDINAL;
        if (clean.length() == 0 || !prevDigit) return UNKNOWN_ORDINAL;
        BigInteger v = new BigInteger(clean.toString(), radix);
        if (v.bitLength() > 31) return NOT_ORDINAL;   // int4 dışı: PostgreSQL sayısal sabit yapar, konum değil
        return v.longValue();
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

    /**
     * Tanımlayıcının PostgreSQL'deki adı: tırnaksız ad yalnız ASCII A–Z küçültülür (UTF-8 veritabanında), tırnaklı ad
     * aynen ({@code ""} → {@code "}). Gerçek kolon karşılaştırması büyük/küçük harf DUYARLI yapılır.
     */
    static String pgName(Token t) {
        if (t.kind() == Kind.QUOTED) return unquoted(t).replace("\"\"", "\"");
        String s = t.text();
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            b.append(c >= 'A' && c <= 'Z' ? (char) (c + 32) : c);
        }
        return b.toString();
    }

    private static String unquoted(Token t) {
        if (t.kind() == Kind.QUOTED) {
            String s = t.text();
            int start = s.startsWith("U&") || s.startsWith("u&") ? 3 : 1;
            return s.length() > start ? s.substring(start, s.length() - 1) : s;
        }
        return t.text();
    }
}
