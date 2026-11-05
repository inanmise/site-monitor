package com.sitemonitor.service.activity;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;

/**
 * Aktivite testlerinin H2 yardımcısı: satırı DOĞRUDAN SQL ile yazar (zenginleştirmenin yeni kolonları dahil) ve tabloyu
 * zenginleştirmenin kolonlarıyla genişletir. Kolonlar (failure_reason, http_status, alert_event_id, request_id) yazıcı
 * kısmının varlığa eklediği kolonlardır; burada {@code ADD COLUMN IF NOT EXISTS} — varlık onları taşıdığında etkisiz.
 */
final class ActivityRows {

    private ActivityRows() {}

    /** Zenginleştirme kolonlarını ekler (varsa dokunmaz). */
    static void extendSchema(JdbcTemplate jdbc) {
        jdbc.execute("ALTER TABLE activity_log ADD COLUMN IF NOT EXISTS failure_reason VARCHAR(40)");
        jdbc.execute("ALTER TABLE activity_log ADD COLUMN IF NOT EXISTS http_status INTEGER");
        jdbc.execute("ALTER TABLE activity_log ADD COLUMN IF NOT EXISTS alert_event_id BIGINT");
        jdbc.execute("ALTER TABLE activity_log ADD COLUMN IF NOT EXISTS request_id VARCHAR(64)");
    }

    static Row row(String time, String type, Long monitorId, String status) {
        return new Row(time, type, monitorId, status);
    }

    /** Akıcı satır kurucusu. */
    static final class Row {
        String time, type, name, target, action = "SCHEDULED_CHECK", status, summary, error, errorClass, actor = "scheduler";
        String reason, requestId;
        Long monitorId, team, ms, alertId;
        Integer http;

        Row(String time, String type, Long monitorId, String status) {
            this.time = time;
            this.type = type;
            this.monitorId = monitorId;
            this.status = status;
            this.name = type + "-" + monitorId;
            this.target = "t" + monitorId;
        }

        Row team(Long t) { team = t; return this; }
        Row target(String t) { target = t; return this; }
        Row name(String n) { name = n; return this; }
        Row action(String a) { action = a; return this; }
        Row reason(String r) { reason = r; return this; }
        Row errorClass(String c) { errorClass = c; return this; }
        Row error(String e) { error = e; return this; }
        Row actor(String a) { actor = a; return this; }
        Row ms(long m) { ms = m; return this; }
        Row http(int h) { http = h; return this; }
        Row alert(long id) { alertId = id; return this; }
        Row request(String r) { requestId = r; return this; }
        Row summary(String s) { summary = s; return this; }

        /** Yazar ve kimliği döner. */
        long save(JdbcTemplate jdbc) {
            return jdbc.execute((Connection c) -> {
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO activity_log (activity_time, monitor_type, monitor_id,"
                        + " monitor_name, target, action, result_status, result_summary, error_message, error_class, actor, team_id,"
                        + " response_ms, failure_reason, http_status, alert_event_id, request_id)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
                    Object[] v = {time, type, monitorId, name, target, action, status, summary, error, errorClass, actor, team, ms,
                            reason, http, alertId, requestId};
                    for (int i = 0; i < v.length; i++) ps.setObject(i + 1, v[i]);
                    ps.executeUpdate();
                    try (var keys = ps.getGeneratedKeys()) {
                        keys.next();
                        return keys.getLong("id");   // PostgreSQL tüm kolonları döndürür — adla
                    }
                }
            });
        }
    }
}
