package com.sitemonitor.service;

import com.sitemonitor.model.SqlQueryHistory;
import com.sitemonitor.repository.SqlQueryHistoryRepository;
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
      + "pg_terminate_backend|pg_cancel_backend|pg_reload_conf|set_config)\\b",
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
    JdbcTemplate playgroundTemplate() {
        JdbcTemplate t = timedTemplate;
        if (t == null) {
            synchronized (this) {
                t = timedTemplate;
                if (t == null) {
                    javax.sql.DataSource ds = jdbcTemplate.getDataSource();
                    if (ds != null) {
                        t = new JdbcTemplate(ds);
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
     * <p><b>Kalan is (ops).</b> Kesin guvence uygulama katmaninda degil DB'dedir: bu havuzun
     * salt-okunur bir Postgres ROLU ile baglanmasi. JPA kullanildigi icin
     * {@code @Transactional(readOnly = true)} JDBC baglantisini salt-okunur YAPMAZ (Hibernate
     * yalnizca flush'i kapatir); hicbir sey yapmayan bir anotasyon yanlis guven verecegi icin
     * bilerek EKLENMEDI. Guvenlik denetiminin "SQL RO rol" maddesi bu isi izliyor.
     */
    public Map<String, Object> execute(String rawSql, String executedBy) {
        String sanitized = sanitize(rawSql);
        validateReadOnly(sanitized);
        String capped = enforceLimit(sanitized);

        long t0 = System.currentTimeMillis();
        List<Map<String, Object>> rows;
        String error = null;
        boolean ok = true;
        try {
            rows = playgroundTemplate().queryForList(capped);
        } catch (Exception e) {
            rows = List.of();
            error = e.getMessage();
            ok = false;
            log.warn("SQL Playground query failed: user={} err={}", executedBy, e.getMessage());
        }
        long dur = System.currentTimeMillis() - t0;

        SqlQueryHistory h = new SqlQueryHistory();
        h.setExecutedBy(executedBy);
        h.setSqlText(rawSql);
        h.setRowCount(ok ? rows.size() : null);
        h.setDurationMs(dur);
        h.setSuccess(ok);
        h.setErrorMessage(error);
        h.setExecutedAt(LocalDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        historyRepo.save(h);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok",          ok);
        result.put("rows",        rows);
        result.put("rowCount",    ok ? rows.size() : 0);
        result.put("durationMs",  dur);
        result.put("executedSql", capped);
        if (!ok) result.put("error", error);
        return result;
    }

    public List<SqlQueryHistory> recentHistory(String executedBy) {
        return historyRepo.findTop50ByExecutedByOrderByExecutedAtDesc(executedBy);
    }

    private String sanitize(String sql) {
        if (sql == null) throw new IllegalArgumentException("SQL boş olamaz");
        String s = sql
            .replaceAll("(?m)--.*$", "")
            .replaceAll("(?s)/\\*.*?\\*/", "")
            .trim();
        if (s.isEmpty()) throw new IllegalArgumentException("SQL boş olamaz");
        return s;
    }

    private void validateReadOnly(String sql) {
        if (!STARTS_WITH_SELECT_OR_WITH.matcher(sql).find()) {
            throw new IllegalArgumentException(
                "Sadece SELECT veya WITH ile başlayan sorgular çalıştırılabilir");
        }
        String trimmed = sql.endsWith(";") ? sql.substring(0, sql.length() - 1) : sql;
        if (trimmed.contains(";")) {
            throw new IllegalArgumentException("Birden fazla statement çalıştırılamaz");
        }
        if (FORBIDDEN.matcher(sql).find()) {
            throw new IllegalArgumentException("Yazma/DDL ifadeleri yasak (sadece okuma)");
        }
        if (FORBIDDEN_FUNCTIONS.matcher(sql).find()) {
            throw new IllegalArgumentException("Sunucu dosyası/iç-ağ/large-object/backend fonksiyonları yasak");
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
    private String enforceLimit(String sql) {
        String inner = sql.endsWith(";") ? sql.substring(0, sql.length() - 1) : sql;
        // 1) Tavani ASAN acik bir LIMIT varsa dusurulur — DB'nin bosuna 5000 satir uretmesini onler.
        Matcher m = LIMIT_CLAUSE.matcher(inner);
        if (m.find() && Integer.parseInt(m.group(1)) > MAX_ROWS) {
            inner = inner.substring(0, m.start()) + "LIMIT " + MAX_ROWS + inner.substring(m.end());
        }
        // 2) DIS tavan HER KOSULDA eklenir. Eskiden ic LIMIT tavanin altindaysa hicbir sinir
        //    konmuyordu; oysa o LIMIT bir ALT sorguda olabilir ve dis kartezyen carpim milyonlarca
        //    satir dondurup queryForList ile hepsini bellege alabilir (yalniz 30 sn timeout sinirlar).
        return "SELECT * FROM (" + inner + ") AS _capped LIMIT " + MAX_ROWS;
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
