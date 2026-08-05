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
        "\\b(insert|update|delete|drop|alter|truncate|create|grant|revoke|comment|"
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
    private final SqlQueryHistoryRepository historyRepo;

    public List<Map<String, Object>> listTables() {
        return jdbcTemplate.queryForList(
            "SELECT table_name FROM information_schema.tables "
          + "WHERE table_schema = 'public' ORDER BY table_name");
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

    public Map<String, Object> execute(String rawSql, String executedBy) {
        String sanitized = sanitize(rawSql);
        validateReadOnly(sanitized);
        String capped = enforceLimit(sanitized);

        long t0 = System.currentTimeMillis();
        List<Map<String, Object>> rows;
        String error = null;
        boolean ok = true;
        try {
            jdbcTemplate.setQueryTimeout(QUERY_TIMEOUT_SEC);
            rows = jdbcTemplate.queryForList(capped);
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

    private String enforceLimit(String sql) {
        String noTrailingSemi = sql.endsWith(";") ? sql.substring(0, sql.length() - 1) : sql;
        Matcher m = LIMIT_CLAUSE.matcher(noTrailingSemi);
        if (m.find()) {
            int n = Integer.parseInt(m.group(1));
            if (n > MAX_ROWS) {
                return noTrailingSemi.substring(0, m.start())
                     + "LIMIT " + MAX_ROWS
                     + noTrailingSemi.substring(m.end());
            }
            return noTrailingSemi;
        }
        return noTrailingSemi + " LIMIT " + MAX_ROWS;
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

        return out;
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
