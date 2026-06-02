package com.certmonitor.service;

import com.certmonitor.model.SqlQueryHistory;
import com.certmonitor.repository.SqlQueryHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
}
