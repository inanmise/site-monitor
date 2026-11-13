package com.sitemonitor.service;

import com.sitemonitor.model.SqlQueryHistory;
import com.sitemonitor.repository.SqlQueryHistoryRepository;
import com.sitemonitor.service.dbquery.DbType;
import com.sitemonitor.service.dbquery.SqlLexer;
import com.sitemonitor.service.sqlplayground.SensitiveColumnException;
import com.sitemonitor.service.sqlplayground.SqlParams;
import com.sitemonitor.service.sqlplayground.SqlPlanSummary;
import com.sitemonitor.service.sqlplayground.SqlResultColumns;
import com.sitemonitor.service.sqlplayground.SqlRunRegistry;
import com.sitemonitor.service.sqlplayground.SqlSensitiveColumns;
import com.sitemonitor.service.sqlplayground.SqlSensitiveGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class SqlPlaygroundService {

    private static final int MAX_ROWS = 1000;
    private static final int QUERY_TIMEOUT_SEC = 30;

    /** Forbidden DML/DDL/admin keywords; matched with word boundaries anywhere in the query. */
    private static final Pattern FORBIDDEN = Pattern.compile(
        // "into": SELECT * INTO yeni_tablo FROM ... SELECT ile BASLAR, tek statement'tir ve
        // eski kara-listeye takilmazdi — yeni tablo olusturup veri kopyalayan bir DDL+DML.
        "\\b(insert|into|update|delete|drop|alter|truncate|create|grant|revoke|comment|"
      + "copy|lock|vacuum|analyze|reindex|cluster|set|reset|call|execute|do|"
      + "begin|commit|rollback|savepoint|listen|notify|prepare|deallocate|discard|"
      + "refresh|security|policy|function|procedure|trigger)\\b",
        Pattern.CASE_INSENSITIVE);

    /** Tehlikeli fonksiyonlar/objeler — SELECT ile çağrılabilir ama sunucu dosyası okuma / iç-ağ bağlantısı /
     *  large-object / backend kontrolü sağlar (CWE-89/CWE-269). SELECT-only + FORBIDDEN yakalamaz → ayrıca engelle. */
    private static final Pattern FORBIDDEN_FUNCTIONS = Pattern.compile(
        "\\b(pg_read_file|pg_read_binary_file|pg_stat_file|pg_ls_dir|pg_ls_logdir|pg_ls_waldir|pg_ls_tmpdir|"
      + "pg_ls_archive_statusdir|pg_read_server_files|lo_import|lo_export|lo_get|lo_put|lo_from_bytea|"
      + "dblink|dblink_connect|dblink_exec|dblink_send_query|postgres_fdw|pg_sleep|pg_sleep_for|"
      + "pg_terminate_backend|pg_cancel_backend|pg_reload_conf|set_config|"
        // Danışma kilitleri (2026-11-13): oturum düzeyi kilit havuza dönen bağlantıda kalır ve uygulamanın kendi
        // kilitlerini (denetim zinciri, şema yamaları) bekletir — try / xact / unlock dahil hepsi
      + "pg_advisory\\w*|pg_try_advisory\\w*)\\b",
        Pattern.CASE_INSENSITIVE);

    private static final Pattern STARTS_WITH_SELECT_OR_WITH =
        Pattern.compile("^\\s*(select|with)\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern LIMIT_CLAUSE =
        Pattern.compile("\\blimit\\s+(\\d+)", Pattern.CASE_INSENSITIVE);

    private final JdbcTemplate jdbcTemplate;

    /** Tablo kayıt defteri (oluşturma ≈ ilk görülme, son veri değişimi) — isteğe bağlı bean. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private SchemaTableRegistryService schemaRegistry;
    private final SqlQueryHistoryRepository historyRepo;

    /** Oyun alanı sorguları için ÖZEL, zaman aşımlı JdbcTemplate (tembel kurulur). */
    private volatile JdbcTemplate timedTemplate;

    /**
     * Eskiden {@code jdbcTemplate.setQueryTimeout(30)} enjekte edilen PAYLAŞILAN bean'e uygulanıyordu:
     * ilk oyun alanı sorgusundan sonra SchedulerService/RetentionService/StormService dâhil uygulamanın
     * TÜM JDBC ifadeleri kalıcı 30 sn tavan taşıyordu — büyüyen geçmiş tablosunda gece retention'ın
     * DELETE'i sessizce QueryTimeoutException ile düşüyordu. Zaman aşımı artık yalnız bu servise ait
     * ayrı bir örneğe uygulanır; paylaşılan bean'e dokunulmaz. DataSource yoksa (mock) paylaşılan
     * bean'i olduğu gibi kullanır (setter ÇAĞRILMAZ).
     */
    /**
     * Bu servise ait bağlantıları JDBC seviyesinde salt-okunur açar.
     *
     * <p>Neden: metin kara-listesi (SELECT/WITH ile başlama, {@code into}/{@code merge}, yasak
     * fonksiyonlar) TEK savunma olduğu sürece, kelime sınırlarını atlatan herhangi bir yazma
     * yolu (alıntılı tanımlayıcı, ileride eklenen bir PG sözdizimi) doğrudan yazma demekti.
     * {@code Connection.setReadOnly(true)} PostgreSQL'de {@code default_transaction_read_only}
     * uygular: yazma girişimi artık UYGULAMADA değil VERİTABANINDA reddedilir.
     *
     * <p>Sınıf javadoc'undaki "{@code @Transactional(readOnly=true)} işe yaramaz" tespiti
     * doğruydu (Hibernate yalnız flush'ı kapatır) — ama JDBC bayrağı onunla aynı şey değil ve
     * atlanmıştı. Ayrı bir salt-okunur Postgres ROLÜ hâlâ ops tarafının işi; bu, onun yerine
     * geçmez, önündeki katmandır.
     *
     * <p>Havuz (Hikari) bağlantıyı iade ederken {@code readOnly} bayrağını kendi varsayılanına
     * geri çeker; uygulamanın geri kalanı etkilenmez.
     */
    static final class ReadOnlyDataSource extends org.springframework.jdbc.datasource.DelegatingDataSource {
        ReadOnlyDataSource(javax.sql.DataSource target) { super(target); }

        @Override
        public java.sql.Connection getConnection() throws java.sql.SQLException {
            return readOnly(super.getConnection());
        }

        @Override
        public java.sql.Connection getConnection(String username, String password) throws java.sql.SQLException {
            return readOnly(super.getConnection(username, password));
        }

        private java.sql.Connection readOnly(java.sql.Connection con) {
            // Sürücü/havuz bayrağı reddederse (bazı sürücüler aktif tx'te atar) metin kapıları
            // yerinde kalır — sorguyu düşürmektense savunmanın bir katmanını kaybederiz.
            try { con.setReadOnly(true); } catch (java.sql.SQLException e) {
                log.warn("SQL Playground bağlantısı salt-okunur yapılamadı: {}", e.toString());
            }
            return con;
        }
    }

    JdbcTemplate playgroundTemplate() {
        JdbcTemplate t = timedTemplate;
        if (t == null) {
            synchronized (this) {
                t = timedTemplate;
                if (t == null) {
                    javax.sql.DataSource ds = jdbcTemplate.getDataSource();
                    if (ds != null) {
                        t = new JdbcTemplate(new ReadOnlyDataSource(ds));
                        t.setQueryTimeout(QUERY_TIMEOUT_SEC);
                    } else {
                        t = jdbcTemplate;
                    }
                    timedTemplate = t;
                }
            }
        }
        return t;
    }

    public List<Map<String, Object>> listTables() {
        List<Map<String, Object>> tables = jdbcTemplate.queryForList(
            "SELECT table_name FROM information_schema.tables "
          + "WHERE table_schema = 'public' ORDER BY table_name");
        // 2026-09-11: "ne zaman oluştu / en son ne zaman değişti" — kayıt defteri + pg_stat (ikisi de
        // isteğe bağlı; yoksa liste eskisi gibi çıplak döner, çağrı patlamaz).
        Map<String, Map<String, Object>> reg = schemaRegistry == null ? Map.of() : schemaRegistry.all();
        Map<String, Map<String, Object>> stats = tableStats();
        List<Map<String, Object>> out = new ArrayList<>(tables.size());
        for (Map<String, Object> t : tables) {
            Map<String, Object> m = new LinkedHashMap<>(t);
            String name = String.valueOf(t.get("table_name"));
            Map<String, Object> r = reg.get(name);
            if (r != null) m.putAll(r);
            Map<String, Object> s = stats.get(name);
            if (s != null) m.putAll(s);
            out.add(m);
        }
        return out;
    }

    /** pg_stat_user_tables özeti: canlı satır tahmini, sayaçlar, son analyze/vacuum anı (ISO). Katalog yoksa boş. */
    Map<String, Map<String, Object>> tableStats() {
        Map<String, Map<String, Object>> out = new HashMap<>();
        for (Map<String, Object> r : safeQuery(
                "SELECT relname, n_live_tup, n_tup_ins, n_tup_upd, n_tup_del, "
              + "GREATEST(last_analyze, last_autoanalyze, last_vacuum, last_autovacuum) AS last_maint "
              + "FROM pg_stat_user_tables WHERE schemaname = 'public'")) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("live_rows", r.get("n_live_tup"));
            m.put("tup_ins", r.get("n_tup_ins"));
            m.put("tup_upd", r.get("n_tup_upd"));
            m.put("tup_del", r.get("n_tup_del"));
            m.put("last_maintenance_at", SchemaTableRegistryService.iso(r.get("last_maint")));
            out.put(String.valueOf(r.get("relname")), m);
        }
        return out;
    }

    /**
     * Tek tablo için zaman & aktivite: defter (first_seen / last_change) + pg_stat + boyut + zaman-damgası
     * kolonlarından MAX ("en son kayıt"). MAX sorguları 5 sn sorgu zaman aşımıyla koşar — milyon satırlık
     * seri tablosunda indekssiz kolon taraması modalı asılı bırakmasın; aşarsa alan boş kalır.
     */
    Map<String, Object> tableActivity(String tableName) {
        Map<String, Object> a = new LinkedHashMap<>();
        Map<String, Object> reg = schemaRegistry == null ? null : schemaRegistry.get(tableName);
        if (reg != null) a.putAll(reg);
        Map<String, Object> st = tableStats().get(tableName);
        if (st != null) a.putAll(st);
        List<Map<String, Object>> size = safeQuery(
                "SELECT pg_size_pretty(pg_total_relation_size(c.oid)) AS total, pg_size_pretty(pg_relation_size(c.oid)) AS data "
              + "FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'public' AND c.relname = ?", tableName);
        if (!size.isEmpty()) { a.put("size_total", size.get(0).get("total")); a.put("size_data", size.get(0).get("data")); }
        // Zaman-damgası adayları: timestamp tipli ya da *_at / *_time / *_ts adlı kolonlar (ISO string de MAX ile sıralanır).
        List<Map<String, Object>> cols = safeQuery(
                "SELECT column_name, data_type FROM information_schema.columns WHERE table_schema = 'public' AND table_name = ? "
              + "ORDER BY ordinal_position", tableName);
        String bestCol = null, bestVal = null;
        int tried = 0;
        JdbcTemplate quick = new JdbcTemplate(jdbcTemplate.getDataSource());
        quick.setQueryTimeout(5);
        for (Map<String, Object> c : cols) {
            String col = String.valueOf(c.get("column_name"));
            String type = String.valueOf(c.get("data_type")).toLowerCase(Locale.ROOT);
            boolean tsType = type.startsWith("timestamp");
            boolean tsName = col.endsWith("_at") || col.endsWith("_time") || col.endsWith("_ts") || col.equals("timestamp") || col.equals("event_time");
            if (!tsType && !tsName) continue;
            if (tried++ >= 4) break;
            try {
                validateIdentifier(col);
                Object v = quick.queryForObject("SELECT MAX(" + col + ") FROM " + tableName, Object.class);
                String iso = SchemaTableRegistryService.iso(v);
                if (iso != null && (bestVal == null || iso.compareTo(bestVal) > 0)) { bestVal = iso; bestCol = col; }
            } catch (Exception e) {
                /* zaman aşımı / tip uyuşmazlığı → bu kolon atlanır */
            }
        }
        a.put("last_record_at", bestVal);
        a.put("last_record_column", bestCol);
        return a;
    }

    public List<Map<String, Object>> listColumns(String tableName) {
        validateIdentifier(tableName);
        return jdbcTemplate.queryForList(
            "SELECT column_name, data_type, is_nullable "
          + "FROM information_schema.columns "
          + "WHERE table_schema = 'public' AND table_name = ? "
          + "ORDER BY ordinal_position",
            tableName);
    }

    /**
     * Sorguyu kosturur.
     *
     * <p><b>Salt-okunurluk savunma DERINLIGIYLE saglanir.</b> Metin kontrolleri (SELECT/WITH ile
     * baslama, tek statement, kelime + fonksiyon kara-listesi) ilk kapidir;
     * {@code SELECT * INTO yeni_tablo FROM x} bu kapiyi geciyordu (SELECT ile baslar, tek
     * statement'tir) ve TABLO OLUSTURUYORDU — {@code into} artik kara-listede.
     *
     * <p><b>Ikinci kapi.</b> Bu servisin baglantilari JDBC seviyesinde salt-okunur aciliyor
     * ({@link ReadOnlyDataSource}). 2026-11-13: pgjdbc'nin varsayilan {@code readOnlyMode=transaction} kipinde bu bayrak
     * YALNIZ otomatik commit KAPALIYKEN uygulanir ({@code BEGIN READ ONLY}) — eski {@code queryForList} yolu otomatik
     * commit acikti, yani DB katmani fiilen devrede degildi. Artik her oyun alani deyimi otomatik commit KAPALI bir
     * islemde kosar, PostgreSQL'de ayrica {@code set_config('transaction_read_only','on',true)} kurulur ve islem her
     * zaman geri alinir: kara-listeyi atlatan bir yazma girisimini DB reddeder.
     *
     * <p><b>Ucuncu kapi (hassas kolonlar, A1).</b> {@link SqlSensitiveGuard} ifade icindeki hassas kolon kullanimini
     * calistirmadan reddeder; sonuc kolonlari kokene gore maskelenir ({@link SqlResultColumns}).
     *
     * <p><b>Kalan is (ops).</b> Kesin guvence yine de ayri bir salt-okunur Postgres ROLUDUR.
     */
    /**
     * Kullanıcıya gösterilecek hata metni: EN ÖZGÜL neden (PostgreSQL'in kendi mesajı — "ERROR: column … does not
     * exist  Position: 8"). Spring 6'dan beri {@code DataAccessException#getMessage()} nedeni EKLEMİYOR; yalnız
     * "StatementCallback; bad SQL grammar [...]" dönüyordu ve arayüz ne hangi sütunun ne hangi konumun hatalı
     * olduğunu gösterebiliyordu (2026-09-27). Neden yoksa istisnanın kendi mesajı.
     */
    static String errorMessage(Exception e) {
        Throwable specific = org.springframework.core.NestedExceptionUtils.getMostSpecificCause(e);
        String msg = specific != null ? specific.getMessage() : null;
        return (msg == null || msg.isBlank()) ? e.getMessage() : msg;
    }

    /** SQL metninin üst sınırı (kayıtlı sorguyla aynı) — çözümleme ve kara liste taraması bu uzunlukla sınırlı. */
    public static final int MAX_SQL_CHARS = 100_000;
    /** Dışa aktarım tavanları (C3). */
    public static final int EXPORT_CSV_MAX_ROWS = 50_000;
    public static final int EXPORT_JSON_MAX_ROWS = 20_000;
    static final int EXPORT_TIMEOUT_SEC = 120;
    static final int EXPORT_FETCH_SIZE = 500;
    /** Hassas kolon şeması önbelleği (canlı information_schema taraması). */
    static final long SENSITIVE_SCHEMA_TTL_MS = 300_000L;
    static final long SENSITIVE_SCHEMA_RETRY_MS = 30_000L;

    /** Çalıştırma isteği: SQL + adlandırılmış parametreler (B4) + istemcinin çalıştırma kimliği (A2). */
    public record QueryRequest(String sql, Map<String, SqlParams.Param> params, String runId) {
        public QueryRequest {
            params = params == null ? Map.of() : params;
        }
    }

    /** Doğrulanmış sorgu: yorumsuz metin, hassas kolon kararı, parametre adları. */
    public record Prepared(String sanitized, SqlSensitiveGuard.Verdict verdict, List<String> paramNames) {}

    /** Satır alıcısı (dışa aktarım). {@code row} false dönerse okuma durur. */
    public interface RowSink {
        void columns(String[] labels) throws java.io.IOException;

        /** Tekilleştirmede yeniden adlandırılan kolonlar (yalnız bilgi; varsayılan yok sayar). */
        default void renamedColumns(List<Map<String, String>> renamed) { }
        boolean row(Object[] values) throws java.io.IOException;
    }

    /** Sorgu sonucu (dışa aktarım / iç kullanım). */
    public record RunOutcome(boolean ok, boolean cancelled, String error, String[] labels, List<String> maskedColumns,
                             int rows, boolean truncated, long durationMs, String runId) {}

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private SqlRunRegistry runRegistry;
    private volatile SqlRunRegistry fallbackRegistry;

    private volatile SqlSensitiveColumns.Schema sensitiveSchema;
    private volatile long sensitiveSchemaAt;

    SqlRunRegistry registry() {
        SqlRunRegistry r = runRegistry;
        if (r != null) return r;
        r = fallbackRegistry;
        if (r == null) {
            synchronized (this) {
                if (fallbackRegistry == null) fallbackRegistry = new SqlRunRegistry(jdbcTemplate);
                r = fallbackRegistry;
            }
        }
        return r;
    }

    /** Testler / çok pod senaryosu: kayıt defterini değiştirir. */
    void setRunRegistry(SqlRunRegistry r) { this.runRegistry = r; }

    /**
     * Hassas kolon şeması: statik katalog + canlı şemada desene uyan kolonlar ({@code information_schema.columns},
     * 5 dk önbellek; okunamazsa statik katalog, 30 sn sonra yeniden denenir).
     */
    public SqlSensitiveColumns.Schema sensitiveSchema() {
        long now = System.currentTimeMillis();
        SqlSensitiveColumns.Schema s = sensitiveSchema;
        if (s != null && now - sensitiveSchemaAt < SENSITIVE_SCHEMA_TTL_MS) return s;
        Map<String, List<String>> cols = new LinkedHashMap<>();
        boolean readOk = true;
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT table_name, column_name FROM information_schema.columns WHERE LOWER(table_schema) = 'public'");
            if (rows != null) {
                for (Map<String, Object> r : rows) {
                    String t = str(r.get("table_name")), c = str(r.get("column_name"));
                    if (t == null || c == null) continue;
                    cols.computeIfAbsent(t.toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(c.toLowerCase(Locale.ROOT));
                }
            }
        } catch (Exception e) {
            readOk = false;
            log.debug("SQL Playground: hassas kolon şeması okunamadı (statik katalog kullanılıyor): {}", e.toString());
        }
        s = SqlSensitiveColumns.schemaOf(cols);
        sensitiveSchema = s;
        sensitiveSchemaAt = readOk ? now : now - SENSITIVE_SCHEMA_TTL_MS + SENSITIVE_SCHEMA_RETRY_MS;
        return s;
    }

    /** Eski imza (testler, örnek sorgu denetimleri): parametresiz, istemci kimliği yok. */
    public Map<String, Object> execute(String rawSql, String executedBy) {
        return execute(new QueryRequest(rawSql, Map.of(), null), executedBy);
    }

    /**
     * Ekran sorgusu: ≤ {@value #MAX_ROWS} satır, {@value #QUERY_TIMEOUT_SEC} sn. Yanıt anahtarları eskisiyle aynı
     * ({@code ok, rows, rowCount, durationMs, executedSql, error}) + {@code run_id}, {@code masked_columns},
     * {@code cancelled}. Hassas kolon reddi geçmişe başarısız satır yazar ve {@link SensitiveColumnException} fırlatır.
     */
    public Map<String, Object> execute(QueryRequest req, String executedBy) {
        String rawSql = req.sql();
        Prepared p = prepareOrRecord(req, executedBy);
        String capped = enforceLimit(p.sanitized(), MAX_ROWS);
        List<Map<String, Object>> rows = new ArrayList<>();
        List<List<Map<String, String>>> renamedOut = new ArrayList<>(1);
        RunOutcome o = run(capped, req.params(), req.runId(), executedBy, "execute", QUERY_TIMEOUT_SEC, 0,
                p.verdict().mentionsSensitive(), new RowSink() {
                    String[] labels;
                    @Override public void columns(String[] l) { labels = l; }
                    @Override public void renamedColumns(List<Map<String, String>> r) { renamedOut.add(r); }
                    @Override public boolean row(Object[] values) {
                        Map<String, Object> m = new org.springframework.util.LinkedCaseInsensitiveMap<>(values.length);
                        for (int i = 0; i < values.length; i++) m.putIfAbsent(labels[i], values[i]);
                        rows.add(m);
                        return rows.size() < MAX_ROWS;
                    }
                });
        String storedError = maskParamValues(o.error(), req.params());
        if (!o.ok()) {
            rows.clear();
            log.warn("SQL Playground query failed: user={} cancelled={} err={}", executedBy, o.cancelled(), storedError);
        }
        saveHistory(rawSql, executedBy, o.ok(), o.ok() ? rows.size() : null, o.durationMs(), storedError, o.cancelled());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok",          o.ok());
        result.put("rows",        rows);
        result.put("rowCount",    o.ok() ? rows.size() : 0);
        result.put("durationMs",  o.durationMs());
        result.put("executedSql", capped);
        if (!o.ok()) result.put("error", o.error());
        result.put("run_id",         o.runId());
        result.put("masked_columns", o.maskedColumns());
        result.put("cancelled",      o.cancelled());
        result.put("params",         p.paramNames().size());
        // Sıralı, TEKİL kolon adları (satır anahtarları bunlar) + yeniden adlandırılanlar (aynı adlı kolonlar)
        result.put("columns",         o.labels() == null ? List.of() : List.of(o.labels()));
        result.put("renamed_columns", renamedOut.isEmpty() ? List.of() : renamedOut.get(0));
        return result;
    }

    /**
     * Sorgu planı (C1): {@code EXPLAIN (FORMAT JSON[, ANALYZE, BUFFERS])}. Aynı doğrulama + hassas kolon reddi; yalnız
     * PostgreSQL (aksi 409 {@code SQL_EXPLAIN_UNSUPPORTED}). ANALYZE sorguyu ÇALIŞTIRIR (satır döndürmez) — salt-okunur
     * işlemde, {@value #QUERY_TIMEOUT_SEC} sn, durdurulabilir. Yanıt: {@code ok, plan, summary, analyzed, run_id} ya da
     * hata durumunda {@code ok:false, error, cancelled}.
     */
    public Map<String, Object> explain(QueryRequest req, boolean analyze, String user) {
        Prepared p = prepareOrRecord(req, user);
        String inner = p.sanitized().endsWith(";") ? p.sanitized().substring(0, p.sanitized().length() - 1) : p.sanitized();
        String sql = "EXPLAIN (FORMAT JSON" + (analyze ? ", ANALYZE, BUFFERS" : "") + ") " + inner;
        String[] json = new String[1];
        RunOutcome o = run(sql, req.params(), req.runId(), user, "explain", QUERY_TIMEOUT_SEC, 0, false, new RowSink() {
            @Override public void columns(String[] l) { }
            @Override public boolean row(Object[] values) {
                json[0] = values.length == 0 || values[0] == null ? null : String.valueOf(values[0]);
                return false;
            }
        }, true, false);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", o.ok());
        out.put("analyzed", analyze);
        out.put("run_id", o.runId());
        out.put("duration_ms", o.durationMs());
        if (!o.ok()) {
            out.put("error", o.error());
            out.put("cancelled", o.cancelled());
            return out;
        }
        Object parsed;
        try {
            parsed = new com.fasterxml.jackson.databind.ObjectMapper().readValue(json[0] == null ? "[]" : json[0], Object.class);
        } catch (Exception e) {
            parsed = List.of();
        }
        out.put("plan", parsed);
        out.put("summary", SqlPlanSummary.summarize(parsed, analyze));
        out.put("cancelled", false);
        return out;
    }

    /**
     * Büyük dışa aktarım (C3): aynı doğrulama + maskeleme, {@code cap + 1} satırlık dış tavan (kesildi mi anlaşılsın),
     * {@value #EXPORT_TIMEOUT_SEC} sn, sürücü imleciyle akış ({@code fetchSize}, otomatik commit kapalı) — satırlar
     * {@code sink}'e tek tek verilir, bellekte biriktirilmez.
     */
    public RunOutcome export(QueryRequest req, int cap, String user, RowSink sink) {
        Prepared p = prepareOrRecord(req, user);
        String capped = enforceLimit(p.sanitized(), cap + 1);
        int[] written = {0};
        boolean[] truncated = {false};
        RunOutcome o = run(capped, req.params(), req.runId(), user, "export", EXPORT_TIMEOUT_SEC, EXPORT_FETCH_SIZE,
                p.verdict().mentionsSensitive(), new RowSink() {
                    @Override public void columns(String[] l) throws java.io.IOException { sink.columns(l); }
                    @Override public boolean row(Object[] values) throws java.io.IOException {
                        if (written[0] >= cap) { truncated[0] = true; return false; }
                        written[0]++;
                        return sink.row(values);
                    }
                }, false, true);
        return new RunOutcome(o.ok(), o.cancelled(), o.error(), o.labels(), o.maskedColumns(), written[0], truncated[0],
                o.durationMs(), o.runId());
    }

    /**
     * {@link #prepare} — hassas kolon reddi çalıştırma, plan ve dışa aktarımda AYNI iz bırakır: geçmişe başarısız satır
     * (sorgu çalıştırılmadı) + {@link SensitiveColumnException} yeniden fırlatılır.
     */
    private Prepared prepareOrRecord(QueryRequest req, String user) {
        try {
            return prepare(req.sql(), req.params(), true);
        } catch (SensitiveColumnException e) {
            saveHistory(req.sql(), user, false, null, 0, e.getMessage(), false);
            throw e;
        }
    }

    /** Parametre değerinin maskesi. */
    static final String PARAM_MASK = "***";

    /**
     * Saklanan / günlüğe yazılan hata metninden parametre DEĞERLERİNİ çıkarır (sözleşme: değerler geçmişe yazılmaz).
     * PostgreSQL / H2 dönüşüm hataları değeri alıntılar ({@code invalid input syntax for type integer: "abc"}): tırnaklı
     * biçimler her uzunlukta, çıplak geçişler ≥ 3 karakterde maskelenir (kısa değer satır / konum numarasını bozmasın).
     * İstemciye dönen yanıt değeri taşıyabilir (kullanıcının kendi girdisi). Sınırlı: ≤ 20 parametre × metin uzunluğu.
     */
    static String maskParamValues(String error, Map<String, SqlParams.Param> params) {
        if (error == null || error.isEmpty() || params == null || params.isEmpty()) return error;
        String out = error;
        List<String> values = new ArrayList<>();
        for (SqlParams.Param prm : params.values()) {
            if (prm == null || prm.value() == null || prm.value().isEmpty()) continue;
            values.add(prm.value());
        }
        // Uzun değer önce — kısa bir değer uzun olanın parçasıysa maskesi yarım kalmasın
        values.sort((x, y) -> Integer.compare(y.length(), x.length()));
        for (String v : values) {
            out = out.replace("\"" + v + "\"", "\"" + PARAM_MASK + "\"").replace("'" + v + "'", "'" + PARAM_MASK + "'");
            if (v.trim().length() >= 3) out = out.replace(v, PARAM_MASK);
        }
        return out;
    }

    /** A2: yalnız sorguyu başlatan kullanıcı durdurabilir. */
    public SqlRunRegistry.CancelResult cancel(String runId, String user) {
        return registry().cancel(runId, user);
    }

    /**
     * Doğrulama hattı (çalıştırma, plan, dışa aktarım ve kayıtlı sorgu için ORTAK): yorum temizliği + salt-okunur metin
     * kapıları + hassas kolon reddi ({@link SensitiveColumnException}) + parametre değerleri ({@code checkParams}).
     */
    public Prepared prepare(String rawSql, Map<String, SqlParams.Param> params, boolean checkParams) {
        String sanitized = sanitize(rawSql);
        validateReadOnly(sanitized);
        SqlSensitiveGuard.Verdict v = SqlSensitiveGuard.check(sanitized, sensitiveSchema());
        if (!v.ok()) throw new SensitiveColumnException(v);
        List<String> names = SqlParams.names(sanitized);
        if (names.size() > SqlParams.MAX_PARAMS) SqlParams.bind(sanitized, false);   // aynı 400 iletisi
        if (checkParams) SqlParams.validate(names, params == null ? Map.of() : params);
        return new Prepared(sanitized, v, names);
    }

    private RunOutcome run(String sql, Map<String, SqlParams.Param> params, String runId, String user, String kind,
                           int timeoutSec, int fetchSize, boolean failClosed, RowSink sink) {
        return run(sql, params, runId, user, kind, timeoutSec, fetchSize, failClosed, sink, false, false);
    }

    /**
     * Ortak çalıştırıcı: salt-okunur, otomatik commit KAPALI işlem (her zaman geri alınır); PostgreSQL'de işlem başında
     * {@code transaction_read_only} + {@code standard_conforming_strings} + çalıştırma etiketi ({@code application_name},
     * hepsi {@code SET LOCAL}) + arka uç pid'i; çalışan ifade kayıt defterine bağlanır (durdurma + süre bekçisi); okuma
     * döngüsü her satırda durdurma / süre denetler; sonuç kolonları maskelenir. {@code postgresOnly} (EXPLAIN) →
     * PostgreSQL değilse 409 {@code SQL_EXPLAIN_UNSUPPORTED}. {@code offPool} (dışa aktarım): havuz DIŞI tek kullanımlık
     * bağlantı — uzun okuma havuzdan bağlantı tutmaz (Hikari sızıntı uyarısı yok) ve kapanınca oturum durumu da biter.
     */
    private RunOutcome run(String sql, Map<String, SqlParams.Param> params, String runId, String user, String kind,
                           int timeoutSec, int fetchSize, boolean failClosed, RowSink sink, boolean postgresOnly,
                           boolean offPool) {
        SqlRunRegistry reg = registry();
        SqlRunRegistry.Run run = reg.start(runId, user, kind, fingerprint(sql));
        long t0 = System.currentTimeMillis();
        long deadline = t0 + timeoutSec * 1000L;
        SqlSensitiveColumns.Schema schema = sensitiveSchema();
        String[][] labelsOut = {new String[0]};
        List<String> masked = new ArrayList<>();
        Body body = con -> runOn(con, sql, params, run, reg, timeoutSec, fetchSize, failClosed, sink, postgresOnly,
                deadline, schema, labelsOut, masked);
        try {
            Object res;
            java.sql.Connection own = offPool ? openOffPool() : null;
            if (own != null) {
                try (java.sql.Connection con = own) {
                    res = body.apply(con);
                }
            } else {
                res = playgroundTemplate().execute((org.springframework.jdbc.core.ConnectionCallback<Object>) body::apply);
            }
            long dur = System.currentTimeMillis() - t0;
            if (res == null) {
                return new RunOutcome(false, false, com.sitemonitor.util.Msg.t(
                        "Veritabanı bağlantısı yok; sorgu çalıştırılamadı.", "No database connection; the query couldn't run."),
                        labelsOut[0], masked, 0, false, dur, run.runId());
            }
            return new RunOutcome(true, false, null, labelsOut[0], masked, 0, false, dur, run.runId());
        } catch (SqlRunRegistry.CancelledException e) {
            return cancelledOutcome(run, labelsOut[0], masked, t0);
        } catch (SqlRunRegistry.TimedOutException e) {
            return timedOutOutcome(run, timeoutSec, labelsOut[0], masked, t0);
        } catch (SqlResultColumns.OriginUnavailableException e) {
            log.warn("SQL Playground: kolon kökeni okunamadı, hassas tablo içeren sonuç gösterilmedi (user={}): {}", user,
                    e.getCause() == null ? e.toString() : e.getCause().toString());
            return new RunOutcome(false, false, com.sitemonitor.util.Msg.t(
                    "Sonuç kolonlarının kaynağı okunamadığı için hassas kolon maskesi doğrulanamadı; sorgu hassas bir tablo "
                            + "içerdiğinden sonuç gösterilmedi. Kolonları açıkça seçip yeniden deneyin.",
                    "The result columns' origin couldn't be read, so the sensitive column mask couldn't be verified; the "
                            + "query involves a sensitive table, so the result wasn't shown. Select the columns explicitly "
                            + "and try again."),
                    labelsOut[0], masked, 0, false, System.currentTimeMillis() - t0, run.runId());
        } catch (com.sitemonitor.config.GlobalExceptionHandler.CodedConflictException
                 | com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException e) {
            throw e;
        } catch (java.io.UncheckedIOException e) {
            throw e;
        } catch (Exception e) {
            if (isCancellation(e) && reg.cancelRequested(run)) return cancelledOutcome(run, labelsOut[0], masked, t0);
            if (isCancellation(e) || run.timedOut()) return timedOutOutcome(run, timeoutSec, labelsOut[0], masked, t0);
            return new RunOutcome(false, false, errorMessage(e), labelsOut[0], masked, 0, false,
                    System.currentTimeMillis() - t0, run.runId());
        } finally {
            reg.finish(run);
        }
    }

    /** Bağlantı üzerinde çalıştırma gövdesi (havuz içi {@code ConnectionCallback} ya da havuz dışı bağlantı). */
    @FunctionalInterface
    private interface Body {
        Object apply(java.sql.Connection con) throws java.sql.SQLException;
    }

    private Object runOn(java.sql.Connection con, String sql, Map<String, SqlParams.Param> params, SqlRunRegistry.Run run,
                         SqlRunRegistry reg, int timeoutSec, int fetchSize, boolean failClosed, RowSink sink,
                         boolean postgresOnly, long deadline, SqlSensitiveColumns.Schema schema, String[][] labelsOut,
                         List<String> masked) throws java.sql.SQLException {
        boolean pg = isPostgres(con);
        if (postgresOnly && !pg) {
            throw new com.sitemonitor.config.GlobalExceptionHandler.CodedConflictException("SQL_EXPLAIN_UNSUPPORTED",
                    com.sitemonitor.util.Msg.t(
                            "Sorgu planı yalnız PostgreSQL veritabanında gösterilebilir; bu ortamın veritabanı PostgreSQL değil.",
                            "The query plan can only be shown on a PostgreSQL database; this environment's database isn't PostgreSQL."));
        }
        SqlParams.Bound bound = SqlParams.bind(sql, pg);
        boolean prevAuto = con.getAutoCommit();
        java.sql.Statement st = null;
        java.sql.ResultSet rs = null;
        try {
            con.setAutoCommit(false);
            Integer pid = null;
            if (pg) {
                // standard_conforming_strings: sözcük çözümleyici '\' karakterini dize içinde kaçış saymaz (PostgreSQL
                // varsayılanı); kapalı bir veritabanında bekçi atlatılamasın diye işlem boyunca açık.
                try (java.sql.PreparedStatement ps = con.prepareStatement(
                        "SELECT pg_backend_pid(), set_config('transaction_read_only', 'on', true), "
                                + "set_config('standard_conforming_strings', 'on', true), "
                                + "set_config('application_name', ?, true)")) {
                    ps.setString(1, run.appName());
                    try (java.sql.ResultSet r0 = ps.executeQuery()) {
                        if (r0.next()) pid = r0.getInt(1);
                    }
                }
            }
            if (bound.isEmpty()) {
                st = con.createStatement(java.sql.ResultSet.TYPE_FORWARD_ONLY, java.sql.ResultSet.CONCUR_READ_ONLY);
            } else {
                java.sql.PreparedStatement ps = con.prepareStatement(bound.jdbcSql(),
                        java.sql.ResultSet.TYPE_FORWARD_ONLY, java.sql.ResultSet.CONCUR_READ_ONLY);
                SqlParams.apply(ps, bound, params);
                st = ps;
            }
            st.setQueryTimeout(timeoutSec);
            if (fetchSize > 0) st.setFetchSize(fetchSize);
            reg.attach(run, st, pid, timeoutSec);
            rs = bound.isEmpty() ? st.executeQuery(sql) : ((java.sql.PreparedStatement) st).executeQuery();
            SqlResultColumns cols = SqlResultColumns.of(rs.getMetaData(), schema, failClosed);
            labelsOut[0] = cols.labels();
            try {
                sink.columns(cols.labels());
                sink.renamedColumns(cols.renamed());
                int n = cols.size();
                while (true) {
                    reg.checkContinue(run, deadline);
                    if (!rs.next()) break;
                    Object[] vals = new Object[n];
                    for (int i = 0; i < n; i++) vals[i] = cols.cell(rs, i);
                    if (!sink.row(vals)) break;
                }
            } catch (java.io.IOException io) {
                throw new java.io.UncheckedIOException(io);
            }
            masked.addAll(cols.maskedLabels());
            return Boolean.TRUE;
        } finally {
            // Önce bekçi durur ve ifade çözülür — bırakılan bağlantıya iptal gitmez
            reg.detach(run);
            if (rs != null) try { rs.close(); } catch (Exception ignored) { }
            if (st != null) try { st.close(); } catch (Exception ignored) { }
            try { con.rollback(); } catch (Exception ignored) { }
            if (pg) {
                // Oturum düzeyi danışma kilidi havuza dönen bağlantıda kalmasın (metin kapısı engeller; ikinci kat)
                try (java.sql.Statement u = con.createStatement()) {
                    u.execute("SELECT pg_advisory_unlock_all()");
                } catch (Exception e) {
                    log.debug("SQL Playground: danışma kilitleri bırakılamadı: {}", e.toString());
                }
                try { con.rollback(); } catch (Exception ignored) { }
            }
            try { con.setAutoCommit(prevAuto); } catch (Exception ignored) { }
        }
    }

    /**
     * Havuz dışı tek kullanımlık bağlantı (dışa aktarım) — Hikari yapılandırmasıyla aynı adres / kullanıcı / sürücü
     * özellikleri (soket zaman aşımı dahil), salt-okunur. Açılamazsa null (çağıran havuzu kullanır).
     */
    private java.sql.Connection openOffPool() {
        try {
            javax.sql.DataSource ds = jdbcTemplate.getDataSource();
            if (ds == null || !ds.isWrapperFor(com.zaxxer.hikari.HikariDataSource.class)) return null;
            com.zaxxer.hikari.HikariDataSource h = ds.unwrap(com.zaxxer.hikari.HikariDataSource.class);
            if (h.getJdbcUrl() == null) return null;
            java.util.Properties p = new java.util.Properties();
            p.putAll(h.getDataSourceProperties());
            if (h.getUsername() != null) p.setProperty("user", h.getUsername());
            if (h.getPassword() != null) p.setProperty("password", h.getPassword());
            p.putIfAbsent("ApplicationName", "sitemonitor-sqlpg-export");
            java.sql.Connection c = java.sql.DriverManager.getConnection(h.getJdbcUrl(), p);
            try {
                c.setReadOnly(true);
            } catch (java.sql.SQLException e) {
                log.debug("SQL Playground: havuz dışı bağlantı salt-okunur yapılamadı: {}", e.toString());
            }
            return c;
        } catch (Exception e) {
            log.debug("SQL Playground: havuz dışı bağlantı açılamadı, havuz kullanılıyor: {}", e.toString());
            return null;
        }
    }

    private static RunOutcome timedOutOutcome(SqlRunRegistry.Run run, int timeoutSec, String[] labels, List<String> masked,
                                              long t0) {
        return new RunOutcome(false, false, com.sitemonitor.util.Msg.t(
                "Sorgu zaman aşımına uğradı (en çok " + timeoutSec + " sn) ve durduruldu. Sorguyu daraltın (WHERE, LIMIT) ya da planına bakın.",
                "The query timed out (at most " + timeoutSec + " s) and was stopped. Narrow the query (WHERE, LIMIT) or check its plan."),
                labels, masked, 0, false, System.currentTimeMillis() - t0, run.runId());
    }

    private static RunOutcome cancelledOutcome(SqlRunRegistry.Run run, String[] labels, List<String> masked, long t0) {
        return new RunOutcome(false, true, com.sitemonitor.util.Msg.t("Sorgu durduruldu.", "The query was stopped."),
                labels, masked, 0, false, System.currentTimeMillis() - t0, run.runId());
    }

    /** İptal / zaman aşımı SQLSTATE'i (PostgreSQL ve H2: 57014). */
    static boolean isCancellation(Throwable e) {
        Throwable t = e;
        for (int depth = 0; t != null && depth < com.sitemonitor.util.CauseChain.MAX_DEPTH; depth++) {
            if (t instanceof java.sql.SQLException s && "57014".equals(s.getSQLState())) return true;
            if (t instanceof java.sql.SQLTimeoutException) return true;
            if (t instanceof org.springframework.dao.QueryTimeoutException) return true;
            t = t.getCause();
        }
        return false;
    }

    private static boolean isPostgres(java.sql.Connection con) {
        try {
            String p = con.getMetaData().getDatabaseProductName();
            return p != null && p.toLowerCase(Locale.ROOT).contains("postgres");
        } catch (Exception e) {
            return false;
        }
    }

    /** Sorgunun kararlı kimliği: boşluk/satır sonu normalize edilir, SHA-256'nın ilk 16 hanesi. */
    public static String fingerprint(String sql) {
        String norm = (sql == null ? "" : sql).trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(norm.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (Exception e) {
            return "unknown";
        }
    }

    private void saveHistory(String rawSql, String user, boolean ok, Integer rows, long dur, String error, boolean cancelled) {
        SqlQueryHistory h = new SqlQueryHistory();
        h.setExecutedBy(user);
        h.setSqlText(rawSql == null ? "" : rawSql);
        h.setRowCount(rows);
        h.setDurationMs(dur);
        h.setSuccess(ok);
        h.setErrorMessage(error);
        h.setCancelled(cancelled);
        h.setExecutedAt(LocalDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        historyRepo.save(h);
    }

    public List<SqlQueryHistory> recentHistory(String executedBy) {
        return historyRepo.findTop50ByExecutedByOrderByExecutedAtDesc(executedBy);
    }

    // ── Geçmiş: sayfalı mod (A4) ─────────────────────────────────────────────────────────────────────────────

    /** "Yavaş" eşiği (ms). */
    public static final long SLOW_MS = 5_000L;
    public static final int HISTORY_MAX_SIZE = 100;
    static final java.time.ZoneId TZ = java.time.ZoneId.of("Europe/Istanbul");

    /**
     * Sayfalı geçmiş: {@code q} (≤ 200, büyük/küçük harf duyarsız, LIKE kaçışlı), {@code status} all|ok|error|slow,
     * {@code scope} mine|all, {@code from}/{@code to} yyyy-MM-dd (İstanbul günü, ikisi de dahil), sıralama
     * {@code executed_at DESC, id DESC}. Ters tarih → 400 alan {@code from}.
     */
    public Map<String, Object> historyPage(String user, String q, String status, String scope, String from, String to,
                                           int page, int size) {
        String query = q == null ? "" : q.trim();
        if (query.length() > 200) {
            throw new com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException("q", com.sitemonitor.util.Msg.t(
                    "Arama metni en çok 200 karakter olabilir.", "The search text can be at most 200 characters."));
        }
        int st = switch (status == null ? "all" : status.trim().toLowerCase(Locale.ROOT)) {
            case "", "all" -> 0;
            case "ok" -> 1;
            case "error" -> 2;
            case "slow" -> 3;
            default -> throw new com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException("status",
                    com.sitemonitor.util.Msg.t("Durum all, ok, error ya da slow olmalı.", "The status must be all, ok, error or slow."));
        };
        boolean all = switch (scope == null ? "mine" : scope.trim().toLowerCase(Locale.ROOT)) {
            case "", "mine" -> false;
            case "all" -> true;
            default -> throw new com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException("scope",
                    com.sitemonitor.util.Msg.t("Kapsam mine ya da all olmalı.", "The scope must be mine or all."));
        };
        java.time.LocalDate fromDay = parseDay(from, "from");
        java.time.LocalDate toDay = parseDay(to, "to");
        if (fromDay != null && toDay != null && fromDay.isAfter(toDay)) {
            throw new com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException("from", com.sitemonitor.util.Msg.t(
                    "Başlangıç günü bitiş gününden sonra olamaz.", "The start day can't be after the end day."));
        }
        String fromIso = fromDay == null ? "0000" : utcIso(fromDay);
        String toIso = toDay == null ? "9999" : utcIso(toDay.plusDays(1));
        int sz = Math.max(1, Math.min(HISTORY_MAX_SIZE, size <= 0 ? 25 : size));
        int pg = Math.max(0, page);
        String like = "%" + likeEscape(query.toLowerCase(Locale.ROOT)) + "%";
        org.springframework.data.domain.PageRequest pr = org.springframework.data.domain.PageRequest.of(pg, sz,
                org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Order.desc("executedAt"),
                        org.springframework.data.domain.Sort.Order.desc("id")));
        org.springframework.data.domain.Page<SqlQueryHistory> p =
                historyRepo.search(all, user, like, fromIso, toIso, st, SLOW_MS, pr);
        List<Map<String, Object>> data = new ArrayList<>();
        for (SqlQueryHistory h : p.getContent()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", h.getId());
            m.put("executed_by", h.getExecutedBy());
            m.put("sql_text", h.getSqlText());
            m.put("row_count", h.getRowCount());
            m.put("duration_ms", h.getDurationMs());
            m.put("success", h.getSuccess());
            m.put("error_message", h.getErrorMessage());
            m.put("executed_at", h.getExecutedAt());
            m.put("cancelled", Boolean.TRUE.equals(h.getCancelled()));
            data.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("data", data);
        out.put("total", p.getTotalElements());
        out.put("page", pg);
        out.put("size", sz);
        out.put("slow_ms", SLOW_MS);
        return out;
    }

    private static java.time.LocalDate parseDay(String s, String field) {
        if (s == null || s.isBlank()) return null;
        try {
            return java.time.LocalDate.parse(s.trim());
        } catch (java.time.format.DateTimeParseException e) {
            throw new com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException(field, com.sitemonitor.util.Msg.t(
                    "Gün YYYY-AA-GG biçiminde olmalı (ör. 2026-11-13).", "The day must be in YYYY-MM-DD form (e.g. 2026-11-13)."));
        }
    }

    /** İstanbul gün başının UTC ISO karşılığı (geçmiş satırları UTC ISO dizesi olarak tutulur). */
    private static String utcIso(java.time.LocalDate day) {
        return day.atStartOfDay(TZ).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

    /** LIKE kaçışı ({@code !} kaçış karakteri). */
    public static String likeEscape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '!' || c == '%' || c == '_') sb.append('!');
            sb.append(c);
        }
        return sb.toString();
    }

    /**
     * Yorumları temizler: dize / tırnaklı ad / dolar tırnağı içindeki {@code --} ve {@code /*} korunur (eski düzenli
     * ifade onları da siliyordu), konumlar kaymaz (yorum karakterleri boşluk olur). Doğrusal ({@link SqlLexer}). Kapanmayan
     * dize / yorum ve görünmez denetim karakterleri açık bir 400 ile reddedilir.
     */
    static String sanitize(String sql) {
        if (sql == null) throw new IllegalArgumentException(com.sitemonitor.util.Msg.t("SQL boş olamaz", "The SQL can't be empty"));
        if (sql.length() > MAX_SQL_CHARS) {
            throw new IllegalArgumentException(com.sitemonitor.util.Msg.t(
                    "SQL en çok " + MAX_SQL_CHARS + " karakter olabilir (" + sql.length() + " karakter gönderildi).",
                    "The SQL can be at most " + MAX_SQL_CHARS + " characters (" + sql.length() + " were sent)."));
        }
        SqlLexer.Lexed lx = SqlLexer.lex(sql, DbType.POSTGRESQL);
        if (lx.error() != null) {
            int line = lx.line(Math.max(0, lx.errorPos())), col = lx.column(Math.max(0, lx.errorPos()));
            String what = switch (lx.errorDetail() == null ? "" : lx.errorDetail()) {
                case "comment" -> com.sitemonitor.util.Msg.t("kapanmamış yorum", "an unterminated comment");
                case "identifier" -> com.sitemonitor.util.Msg.t("kapanmamış tırnaklı ad", "an unterminated quoted name");
                case "string" -> com.sitemonitor.util.Msg.t("kapanmamış dize", "an unterminated string");
                default -> com.sitemonitor.util.Msg.t("görünmez bir denetim / biçim karakteri", "an invisible control / format character");
            };
            throw new IllegalArgumentException(com.sitemonitor.util.Msg.t(
                    "SQL çözümlenemedi: satır " + line + ", sütun " + col + " civarında " + what + " var. Düzeltip yeniden deneyin.",
                    "The SQL couldn't be read: there is " + what + " near line " + line + ", column " + col + ". Fix it and try again."));
        }
        char[] out = lx.neutral().clone();
        for (SqlLexer.Token t : lx.tokens()) {
            for (int i = t.start(); i < t.end(); i++) out[i] = sql.charAt(i);
        }
        String s = new String(out).trim();
        if (s.isEmpty()) throw new IllegalArgumentException(com.sitemonitor.util.Msg.t("SQL boş olamaz", "The SQL can't be empty"));
        return s;
    }

    private void validateReadOnly(String sql) {
        if (!STARTS_WITH_SELECT_OR_WITH.matcher(sql).find()) {
            throw new IllegalArgumentException(com.sitemonitor.util.Msg.t(
                "Sadece SELECT veya WITH ile başlayan sorgular çalıştırılabilir",
                "Only queries that start with SELECT or WITH can be run"));
        }
        String trimmed = sql.endsWith(";") ? sql.substring(0, sql.length() - 1) : sql;
        if (trimmed.contains(";")) {
            throw new IllegalArgumentException(com.sitemonitor.util.Msg.t(
                "Birden fazla statement çalıştırılamaz", "More than one statement can't be run"));
        }
        if (FORBIDDEN.matcher(sql).find()) {
            throw new IllegalArgumentException(com.sitemonitor.util.Msg.t(
                "Yazma/DDL ifadeleri yasak (sadece okuma)", "Write / DDL statements are not allowed (read only)"));
        }
        if (FORBIDDEN_FUNCTIONS.matcher(sql).find()) {
            throw new IllegalArgumentException(com.sitemonitor.util.Msg.t(
                "Sunucu dosyası/iç-ağ/large-object/backend fonksiyonları yasak",
                "Server file / internal network / large object / backend functions are not allowed"));
        }
    }

    /**
     * Satir tavanini EN DIS sorguya uygular.
     *
     * <p>Eski surum yalnizca ilk {@code LIMIT}'e bakiyordu; deger tavanin altindaysa disariya
     * hicbir sinir EKLEMIYORDU. Oysa o LIMIT bir ALT sorguda olabilir:
     * {@code SELECT * FROM buyuk a, buyuk b WHERE a.id IN (SELECT id FROM buyuk LIMIT 5)}
     * — dis kartezyen carpim milyonlarca satir dondurur ve {@code queryForList} hepsini
     * bellege alir; yalniz 30 sn'lik timeout sinirlar, o sure boyunca heap dolar.
     *
     * <p>Sarmalamak guvenli: giris tek statement ve salt-okunurdur (bkz. validateReadOnly +
     * execute'un salt-okunur islemi), dolayisiyla alt sorgu olarak kosmasi anlami degistirmez.
     */
    private String enforceLimit(String sql, int cap) {
        String inner = sql.endsWith(";") ? sql.substring(0, sql.length() - 1) : sql;
        // 1) Tavani ASAN acik bir LIMIT varsa dusurulur — DB'nin bosuna 5000 satir uretmesini onler.
        Matcher m = LIMIT_CLAUSE.matcher(inner);
        if (m.find() && parseLimit(m.group(1)) > cap) {
            inner = inner.substring(0, m.start()) + "LIMIT " + cap + inner.substring(m.end());
        }
        // 2) DIS tavan HER KOSULDA eklenir. Eskiden ic LIMIT tavanin altindaysa hicbir sinir
        //    konmuyordu; oysa o LIMIT bir ALT sorguda olabilir ve dis kartezyen carpim milyonlarca
        //    satir dondurup queryForList ile hepsini bellege alabilir (yalniz 30 sn timeout sinirlar).
        return "SELECT * FROM (" + inner + ") AS _capped LIMIT " + cap;
    }

    private static long parseLimit(String digits) {
        if (digits.length() > 18) return Long.MAX_VALUE;
        try { return Long.parseLong(digits); } catch (NumberFormatException e) { return Long.MAX_VALUE; }
    }

    private void validateIdentifier(String name) {
        if (name == null || !name.matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
            throw new IllegalArgumentException("Geçersiz tablo adı: " + name);
        }
    }

    // ── Şema keşfi: tablo detayları + ilişki (hiyerarşi) grafiği ──────────────────

    /**
     * Tablo şema detayları: kolonlar (+ tip sınırları), constraint'ler (data integrity),
     * index'ler ve trigger'lar. Postgres katalogundan okunur (prod). Her bölüm kendi try/catch'inde
     * — katalogu olmayan ortamda (H2/test) o bölüm boş döner, çağrı patlamaz. Tablo adı katı regex'le
     * doğrulanır (validateIdentifier) — information_schema sorgularında ayrıca ? ile parametreli.
     */
    public Map<String, Object> tableDetails(String tableName) {
        validateIdentifier(tableName);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("table", tableName);

        List<Map<String, Object>> cols;
        try {
            cols = jdbcTemplate.queryForList(
                "SELECT column_name, data_type, udt_name, is_nullable, column_default, "
              + "character_maximum_length, numeric_precision, numeric_scale "
              + "FROM information_schema.columns WHERE table_schema='public' AND table_name = ? "
              + "ORDER BY ordinal_position", tableName);
            for (Map<String, Object> c : cols) {
                c.put("bounds", typeBounds(
                        str(c.get("udt_name")), str(c.get("data_type")),
                        num(c.get("character_maximum_length")),
                        num(c.get("numeric_precision")), num(c.get("numeric_scale"))));
            }
        } catch (Exception e) {
            cols = List.of();
        }
        out.put("columns", cols);

        out.put("constraints", safeQuery(
            "SELECT con.conname AS name, "
          + "CASE con.contype WHEN 'p' THEN 'PRIMARY KEY' WHEN 'f' THEN 'FOREIGN KEY' "
          + "WHEN 'u' THEN 'UNIQUE' WHEN 'c' THEN 'CHECK' WHEN 'x' THEN 'EXCLUDE' "
          + "ELSE con.contype::text END AS type, pg_get_constraintdef(con.oid) AS definition "
          + "FROM pg_constraint con JOIN pg_class rel ON rel.oid = con.conrelid "
          + "JOIN pg_namespace ns ON ns.oid = rel.relnamespace "
          + "WHERE ns.nspname='public' AND rel.relname = ? ORDER BY con.contype, con.conname", tableName));

        out.put("indexes", safeQuery(
            "SELECT indexname AS name, indexdef AS definition, "
          + "(indexdef ILIKE 'CREATE UNIQUE%') AS is_unique "
          + "FROM pg_indexes WHERE schemaname='public' AND tablename = ? ORDER BY indexname", tableName));

        out.put("triggers", safeQuery(
            "SELECT trigger_name AS name, action_timing AS timing, event_manipulation AS event "
          + "FROM information_schema.triggers WHERE trigger_schema='public' AND event_object_table = ? "
          + "ORDER BY trigger_name, event_manipulation", tableName));

        try { out.put("activity", tableActivity(tableName)); } catch (Exception e) { out.put("activity", Map.of()); }

        enrichTableDetails(tableName, out, cols);
        return out;
    }

    /**
     * Şema detayı zenginleştirmesi (2026-09-20, kullanıcı bildirimi: "sayfa çok basic"): tablo/kolon
     * açıklamaları, kolon istatistikleri (pg_stats: NULL oranı, ayrık değer, ort. genişlik), kısıtların
     * YAPISAL hali (kolonlar, hedef tablo/kolon, ON DELETE/UPDATE), bu tabloya bakan FK'ler, çıkarım
     * ilişkileri (*_id kolonları — {@link #relationships()} ile aynı kural), indeks kullanım/boyut
     * (pg_stat_user_indexes), tarama sayaçları (seq/idx scan, ölü satır). Her sorgu {@code safeQuery}:
     * katalog izni yoksa ilgili alan boş kalır, ekran düşmez.
     */
    @SuppressWarnings("unchecked")
    private void enrichTableDetails(String tableName, Map<String, Object> out, List<Map<String, Object>> cols) {
        List<Map<String, Object>> tc = safeQuery(
            "SELECT obj_description(c.oid, 'pg_class') AS comment, c.relkind::text AS relkind, "
          + "c.reltuples::bigint AS reltuples, c.relhasindex AS has_index "
          + "FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname='public' AND c.relname = ?", tableName);
        out.put("comment", tc.isEmpty() ? null : tc.get(0).get("comment"));

        // kolon açıklamaları + istatistikleri → columns satırlarına birleşir
        Map<String, Map<String, Object>> byCol = new LinkedHashMap<>();
        for (Map<String, Object> c : cols) byCol.put(str(c.get("column_name")), c);
        for (Map<String, Object> r : safeQuery(
            "SELECT a.attname AS column_name, col_description(a.attrelid, a.attnum) AS comment, a.attidentity::text AS identity "
          + "FROM pg_attribute a JOIN pg_class c ON c.oid = a.attrelid JOIN pg_namespace n ON n.oid = c.relnamespace "
          + "WHERE n.nspname='public' AND c.relname = ? AND a.attnum > 0 AND NOT a.attisdropped", tableName)) {
            Map<String, Object> c = byCol.get(str(r.get("column_name")));
            if (c == null) continue;
            c.put("comment", r.get("comment"));
            String ident = str(r.get("identity"));
            c.put("is_identity", ident != null && !ident.isBlank());
        }
        for (Map<String, Object> r : safeQuery(
            "SELECT attname AS column_name, null_frac, n_distinct, avg_width FROM pg_stats WHERE schemaname='public' AND tablename = ?", tableName)) {
            Map<String, Object> c = byCol.get(str(r.get("column_name")));
            if (c == null) continue;
            c.put("null_frac", r.get("null_frac"));
            c.put("n_distinct", r.get("n_distinct"));
            c.put("avg_width", r.get("avg_width"));
        }

        // kısıtlar (yapısal): kolonlar, hedef tablo/kolon, eylemler
        List<Map<String, Object>> constraints = new ArrayList<>();
        Set<String> pk = new LinkedHashSet<>(), uq = new LinkedHashSet<>(), fkCols = new LinkedHashSet<>();
        for (Map<String, Object> r : safeQuery(
            "SELECT con.conname AS name, con.contype::text AS contype, pg_get_constraintdef(con.oid) AS definition, "
          + "array_to_string(ARRAY(SELECT a.attname FROM unnest(con.conkey) k JOIN pg_attribute a ON a.attrelid = con.conrelid AND a.attnum = k), ',') AS columns, "
          + "fr.relname AS ref_table, "
          + "array_to_string(ARRAY(SELECT a.attname FROM unnest(con.confkey) k JOIN pg_attribute a ON a.attrelid = con.confrelid AND a.attnum = k), ',') AS ref_columns, "
          + "con.confdeltype::text AS on_delete, con.confupdtype::text AS on_update "
          + "FROM pg_constraint con JOIN pg_class rel ON rel.oid = con.conrelid JOIN pg_namespace ns ON ns.oid = rel.relnamespace "
          + "LEFT JOIN pg_class fr ON fr.oid = con.confrelid "
          + "WHERE ns.nspname='public' AND rel.relname = ? ORDER BY con.contype, con.conname", tableName)) {
            String type = switch (String.valueOf(r.get("contype"))) {
                case "p" -> "PRIMARY KEY"; case "f" -> "FOREIGN KEY"; case "u" -> "UNIQUE"; case "c" -> "CHECK"; case "x" -> "EXCLUDE";
                default -> String.valueOf(r.get("contype"));
            };
            List<String> columns = splitCsv(str(r.get("columns")));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", r.get("name")); m.put("type", type); m.put("definition", r.get("definition"));
            m.put("columns", columns);
            if ("FOREIGN KEY".equals(type)) {
                m.put("ref_table", r.get("ref_table"));
                m.put("ref_columns", splitCsv(str(r.get("ref_columns"))));
                m.put("on_delete", fkAction(str(r.get("on_delete"))));
                m.put("on_update", fkAction(str(r.get("on_update"))));
                fkCols.addAll(columns);
            }
            if ("PRIMARY KEY".equals(type)) pk.addAll(columns);
            if ("UNIQUE".equals(type)) uq.addAll(columns);
            constraints.add(m);
        }
        if (!constraints.isEmpty()) out.put("constraints", constraints);   // eski liste aynı anahtar — zenginleşmiş hali

        // bu tabloya bakan FK'ler
        out.put("referenced_by", safeQuery(
            "SELECT con.conname AS name, rel.relname AS from_table, "
          + "array_to_string(ARRAY(SELECT a.attname FROM unnest(con.conkey) k JOIN pg_attribute a ON a.attrelid = con.conrelid AND a.attnum = k), ',') AS from_columns, "
          + "con.confdeltype::text AS on_delete "
          + "FROM pg_constraint con JOIN pg_class rel ON rel.oid = con.conrelid JOIN pg_class fr ON fr.oid = con.confrelid "
          + "JOIN pg_namespace ns ON ns.oid = fr.relnamespace WHERE con.contype = 'f' AND ns.nspname='public' AND fr.relname = ? "
          + "ORDER BY rel.relname", tableName).stream().map(r -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", r.get("name")); m.put("from_table", r.get("from_table"));
                m.put("from_columns", splitCsv(str(r.get("from_columns")))); m.put("on_delete", fkAction(str(r.get("on_delete"))));
                return m;
            }).toList());

        // çıkarım ilişkileri (şemada gerçek FK az; *_id kolon adından)
        List<Map<String, Object>> inferred = new ArrayList<>();
        try {
            for (Map<String, Object> e : (List<Map<String, Object>>) relationships().get("edges")) {
                if (!Boolean.TRUE.equals(e.get("inferred"))) continue;
                if (tableName.equals(e.get("from")) || tableName.equals(e.get("to"))) inferred.add(e);
            }
        } catch (Exception ignored) { /* ilişkisiz devam */ }
        out.put("inferred_relations", inferred);

        // indeks kullanım + boyut → indexes satırlarına birleşir; kolon rozetleri için indeks kolonları
        Map<String, Map<String, Object>> ixStats = new LinkedHashMap<>();
        for (Map<String, Object> r : safeQuery(
            "SELECT i.indexrelname AS name, i.idx_scan, i.idx_tup_read, pg_size_pretty(pg_relation_size(i.indexrelid)) AS size, "
          + "pg_relation_size(i.indexrelid) AS size_bytes, "
          + "array_to_string(ARRAY(SELECT a.attname FROM unnest(x.indkey) WITH ORDINALITY AS k(attnum, ord) "
          + "JOIN pg_attribute a ON a.attrelid = x.indrelid AND a.attnum = k.attnum ORDER BY k.ord), ',') AS columns, x.indisprimary AS is_primary "
          + "FROM pg_stat_user_indexes i JOIN pg_index x ON x.indexrelid = i.indexrelid WHERE i.schemaname='public' AND i.relname = ?", tableName)) {
            ixStats.put(str(r.get("name")), r);
        }
        Set<String> indexedCols = new LinkedHashSet<>();
        List<Map<String, Object>> ixs = (List<Map<String, Object>>) out.get("indexes");
        if (ixs != null) for (Map<String, Object> ix : ixs) {
            Map<String, Object> s = ixStats.get(str(ix.get("name")));
            if (s == null) continue;
            ix.put("scans", s.get("idx_scan")); ix.put("tup_read", s.get("idx_tup_read"));
            ix.put("size", s.get("size")); ix.put("size_bytes", s.get("size_bytes"));
            ix.put("is_primary", Boolean.TRUE.equals(s.get("is_primary")));
            List<String> icols = splitCsv(str(s.get("columns")));
            ix.put("columns", icols);
            if (!icols.isEmpty()) indexedCols.add(icols.get(0));   // yalnız öncü kolon "indeksli" sayılır
        }
        for (Map<String, Object> c : cols) {
            String n = str(c.get("column_name"));
            c.put("is_pk", pk.contains(n)); c.put("is_fk", fkCols.contains(n)); c.put("is_unique", uq.contains(n)); c.put("is_indexed", indexedCols.contains(n) || pk.contains(n));
        }

        // tarama sayaçları
        List<Map<String, Object>> scan = safeQuery(
            "SELECT seq_scan, seq_tup_read, idx_scan, idx_tup_fetch, n_dead_tup, n_mod_since_analyze, "
          + "last_vacuum, last_autovacuum, last_analyze, last_autoanalyze "
          + "FROM pg_stat_user_tables WHERE schemaname='public' AND relname = ?", tableName);
        if (!scan.isEmpty()) {
            Map<String, Object> s = scan.get(0);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("seq_scan", s.get("seq_scan")); m.put("seq_tup_read", s.get("seq_tup_read"));
            m.put("idx_scan", s.get("idx_scan")); m.put("idx_tup_fetch", s.get("idx_tup_fetch"));
            m.put("dead_rows", s.get("n_dead_tup")); m.put("mod_since_analyze", s.get("n_mod_since_analyze"));
            m.put("last_vacuum", SchemaTableRegistryService.iso(s.get("last_vacuum")));
            m.put("last_autovacuum", SchemaTableRegistryService.iso(s.get("last_autovacuum")));
            m.put("last_analyze", SchemaTableRegistryService.iso(s.get("last_analyze")));
            m.put("last_autoanalyze", SchemaTableRegistryService.iso(s.get("last_autoanalyze")));
            out.put("stats", m);
        }
    }

    private static List<String> splitCsv(String s) {
        if (s == null || s.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String p : s.split(",")) if (!p.isBlank()) out.add(p.trim());
        return out;
    }

    /** pg_constraint confdeltype/confupdtype harfi → okunur eylem. */
    static String fkAction(String code) {
        if (code == null) return null;
        return switch (code) {
            case "a" -> "NO ACTION"; case "r" -> "RESTRICT"; case "c" -> "CASCADE"; case "n" -> "SET NULL"; case "d" -> "SET DEFAULT";
            default -> code;
        };
    }

    /**
     * Tablolar arası İLİŞKİ grafiği (hiyerarşi diyagramı için). Şemada gerçek FK constraint'i
     * neredeyse yok (entity'ler düz {@code *_id} kolonları kullanıyor, JPA @ManyToOne ilişkisi yok)
     * → ilişkiler önce gerçek FK'lerden, kalanı {@code *_id} kolon isminden ÇIKARIM (inferred) ile
     * bulunur. Her kenar {@code inferred} bayrağı taşır (UI kesik çizgi ile gösterir).
     */
    public Map<String, Object> relationships() {
        List<String> tables;
        try {
            tables = jdbcTemplate.query(
                "SELECT table_name FROM information_schema.tables "
              + "WHERE table_schema='public' AND table_type='BASE TABLE' ORDER BY table_name",
                (rs, i) -> rs.getString(1));
        } catch (Exception e) {
            tables = List.of();
        }
        Set<String> tableSet = new LinkedHashSet<>(tables);

        List<Map<String, Object>> edges = new ArrayList<>();
        Set<String> realPairs = new LinkedHashSet<>();

        // 1) Gerçek FK'ler (varsa) — inferred=false
        for (Map<String, Object> fk : safeQuery(
                "SELECT kcu.table_name AS from_table, kcu.column_name AS from_column, ccu.table_name AS to_table "
              + "FROM information_schema.table_constraints tc "
              + "JOIN information_schema.key_column_usage kcu "
              + "  ON tc.constraint_name = kcu.constraint_name AND tc.table_schema = kcu.table_schema "
              + "JOIN information_schema.constraint_column_usage ccu "
              + "  ON tc.constraint_name = ccu.constraint_name AND tc.table_schema = ccu.table_schema "
              + "WHERE tc.constraint_type = 'FOREIGN KEY' AND tc.table_schema = 'public'")) {
            String fromT = str(fk.get("from_table")), col = str(fk.get("from_column")), toT = str(fk.get("to_table"));
            if (fromT == null || toT == null) continue;
            edges.add(edge(fromT, col, toT, false));
            realPairs.add(fromT + "." + col);
        }

        // 2) Çıkarım — *_id kolonları → hedef tablo
        for (Map<String, Object> c : safeQuery(
                "SELECT table_name, column_name FROM information_schema.columns "
              + "WHERE table_schema='public' AND column_name LIKE '%\\_id' AND column_name <> 'id' "
              + "ORDER BY table_name, column_name")) {
            String fromT = str(c.get("table_name")), col = str(c.get("column_name"));
            if (fromT == null || col == null || realPairs.contains(fromT + "." + col)) continue;
            String target = resolveTarget(col, fromT, tableSet);
            if (target != null) edges.add(edge(fromT, col, target, true));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tables", tables);
        out.put("edges", edges);
        return out;
    }

    private List<Map<String, Object>> safeQuery(String sql, Object... args) {
        try {
            return jdbcTemplate.queryForList(sql, args);
        } catch (Exception e) {
            return List.of();
        }
    }

    private static Map<String, Object> edge(String from, String col, String to, boolean inferred) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("from", from);
        e.put("column", col);
        e.put("to", to);
        e.put("inferred", inferred);
        return e;
    }

    /** `<base>_id` kolonunu mevcut bir tabloya eşler (çıkarım). Eşleşme yoksa null (ör. mudurluk_id). */
    private static String resolveTarget(String col, String fromTable, Set<String> tables) {
        String base = col.substring(0, col.length() - 3); // "_id" ekini at
        switch (base) {
            case "user": case "manager": case "created_by": case "updated_by": case "approved_by":
                if (tables.contains("app_users")) return "app_users";
                break;
            case "team": case "sy_team": case "ug_team": case "target_team": case "old_team": case "new_team":
                if (tables.contains("teams")) return "teams";
                break;
            case "incident":
                if (tables.contains("incident_records")) return "incident_records";
                break;
            case "note":
                if (tables.contains("certificate_notes")) return "certificate_notes";
                break;
            case "monitor": {
                String pfx = fromTable.contains("_") ? fromTable.substring(0, fromTable.indexOf('_')) : fromTable;
                String cand = pfx + "_monitors";
                if (tables.contains(cand)) return cand;
                break;
            }
            default:
                break;
        }
        for (String cand : new String[]{base, base + "s", base + "es"}) {
            if (tables.contains(cand)) return cand;
        }
        return null;
    }

    /** İnsan-okur tip sınırı (taşma/uzunluk farkındalığı için). */
    private static String typeBounds(String udt, String dataType, Integer charLen, Integer numPrec, Integer numScale) {
        String t = udt != null ? udt : (dataType != null ? dataType : "");
        switch (t) {
            case "int2": case "smallint":            return "−32.768 … 32.767 (2 bayt)";
            case "int4": case "integer":             return "−2.147.483.648 … 2.147.483.647 (4 bayt)";
            case "int8": case "bigint":              return "≈ ±9,22×10¹⁸ (8 bayt)";
            case "float4": case "real":              return "≈ 6 anlamlı basamak (4 bayt)";
            case "float8": case "double precision":  return "≈ 15 anlamlı basamak (8 bayt)";
            case "bool": case "boolean":             return "true / false";
            case "uuid":                             return "128-bit UUID";
            case "date":                             return "tarih";
            case "timestamp": case "timestamptz":    return "zaman damgası";
            default:
                if (charLen != null) return "≤ " + charLen + " karakter";
                if (numPrec != null) return numPrec + " basamak"
                        + (numScale != null && numScale > 0 ? " (" + numScale + " ondalık)" : "");
                if ("text".equals(t)) return "sınırsız metin";
                return "";
        }
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static Integer num(Object o) {
        return o instanceof Number n ? n.intValue() : null;
    }
}
