package com.sitemonitor.util;

import java.util.List;
import java.util.Map;

/** Curated sample queries surfaced in the SQL Playground UI. Read-only. */
public final class SqlSamples {

    private SqlSamples() {}

    public static List<Map<String, String>> list() {
        return List.of(
            s("Yaklaşan sertifikalar (30 gün)",
              "SELECT domain, days_remaining, not_after AS expires_at FROM latest_checks "
            + "WHERE days_remaining BETWEEN 0 AND 30 ORDER BY days_remaining ASC"),

            s("Açık alarmlar (severity'ye göre)",
              "SELECT id, domain, alert_type, alert_level, created_at "
            + "FROM alert_events WHERE resolved = false "
            + "ORDER BY CASE alert_level WHEN 'CRITICAL' THEN 1 WHEN 'HIGH' THEN 2 ELSE 3 END, "
            + "created_at DESC"),

            s("Son 24 saat SMTP hataları",
              "SELECT sent_at, recipient_email, subject, email_status FROM notification_logs "
            + "WHERE email_status LIKE 'FAILED%' "
            + "AND sent_at > to_char(now() - interval '1 day','YYYY-MM-DD\"T\"HH24:MI:SS') "
            + "ORDER BY sent_at DESC"),

            s("Takım başına aktif sertifika sayısı",
              "SELECT t.name AS team, COUNT(c.id) AS cert_count "
            + "FROM teams t LEFT JOIN certificate_inventory c "
            + "ON c.team_id = t.id AND c.deleted_at IS NULL AND c.active = true "
            + "GROUP BY t.name ORDER BY cert_count DESC"),

            s("Silinmiş ama açık alarmı olan domainler",
              "SELECT DISTINCT a.domain FROM alert_events a "
            + "JOIN certificate_inventory i ON i.domain = a.domain "
            + "WHERE a.resolved = false AND i.deleted_at IS NOT NULL"),

            s("Bugünkü audit log özeti (event_type bazında)",
              "SELECT event_type, COUNT(*) AS n FROM audit_log "
            + "WHERE event_time LIKE to_char(now(),'YYYY-MM-DD') || '%' "
            + "GROUP BY event_type ORDER BY n DESC"),

            s("Son 7 gün kullanıcı aktivitesi (top 10)",
              "SELECT actor, COUNT(*) AS actions FROM audit_log "
            + "WHERE event_time > to_char(now() - interval '7 days','YYYY-MM-DD') "
            + "GROUP BY actor ORDER BY actions DESC LIMIT 10"),

            s("En büyük tablolar (storage)",
              "SELECT table_name, pg_size_pretty(pg_total_relation_size(quote_ident(table_name)::text)) AS size "
            + "FROM information_schema.tables WHERE table_schema='public' "
            + "ORDER BY pg_total_relation_size(quote_ident(table_name)::text) DESC LIMIT 10"),

            s("Açık alarmlar (en eski 50)",
              "SELECT domain, alert_type, alert_level, last_re_alert_at FROM alert_events "
            + "WHERE resolved = false ORDER BY created_at ASC LIMIT 50"),

            s("Bildirim almamış açık alarmlar",
              "SELECT a.id, a.domain, a.alert_type, a.created_at FROM alert_events a "
            + "WHERE a.resolved = false AND NOT EXISTS ("
            + "  SELECT 1 FROM notification_logs n WHERE n.alert_event_id = a.id AND n.email_status = 'SENT')"),

            s("Son 24 saat SQL Playground aktivitesi",
              "SELECT executed_by, COUNT(*) AS queries, AVG(duration_ms)::int AS avg_ms "
            + "FROM sql_query_history "
            + "WHERE executed_at > to_char(now() - interval '1 day','YYYY-MM-DD\"T\"HH24:MI:SS') "
            + "GROUP BY executed_by ORDER BY queries DESC"),

            s("DNS monitör başına kayıt sayısı",
              "SELECT m.domain, COUNT(r.id) AS records "
            + "FROM dns_monitors m LEFT JOIN dns_records r ON r.monitor_id = m.id "
            + "GROUP BY m.domain ORDER BY records DESC LIMIT 20")
        );
    }

    private static Map<String, String> s(String label, String sql) {
        return Map.of("label", label, "sql", sql);
    }
}
