package com.sitemonitor.service.retention;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DAVRANIŞ-NÖTRLÜK KANITI. Gece temizliği ~150 satır elle yazılmış DELETE bloğundan bildirimsel
 * {@link RetentionCatalog}'a taşındı. Bu test, kataloğun ürettiği her cümlenin dönüşüm ÖNCESİ
 * {@code SchedulerService.cleanupOldLogs} gövdesindeki SQL ile aynı olduğunu doğrular.
 *
 * <p>Beklenen metinler koddan birebir kopyalanmıştır (git geçmişi: v20.4.1 öncesi). Karşılaştırma
 * boşluklara duyarsızdır (eski kodda "sql_query_history  WHERE" gibi çift boşluklar vardı), ama
 * tablo/kolon/guard/parametre yapısına duyarlıdır — yani "aynı satırlar mı siliniyor" sorusunu
 * belirsizlik bırakmadan yanıtlar.
 */
class RetentionSqlIdentityTest {

    /** politika id → dönüşüm ÖNCESİ üretilen DELETE cümlesi. */
    private static Map<String, String> legacySql() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("rollup-daily", "DELETE FROM monitor_check_daily WHERE day < ?");
        m.put("audit-log", "DELETE FROM audit_log WHERE event_time < ?");
        m.put("notification-logs", "DELETE FROM notification_logs WHERE sent_at < ?");
        m.put("sql-query-history", "DELETE FROM sql_query_history  WHERE executed_at < ?");
        m.put("series-uptime", "DELETE FROM uptime_checks WHERE checked_at < ?");
        m.put("series-certificate", "DELETE FROM certificate_checks WHERE checked_at < ?");
        m.put("series-port", "DELETE FROM port_checks WHERE checked_at < ?");
        m.put("series-keyword", "DELETE FROM keyword_results WHERE checked_at < ?");
        m.put("series-ping", "DELETE FROM ping_checks WHERE checked_at < ?");
        m.put("series-dns", "DELETE FROM dns_records WHERE checked_at < ? "
                + "AND id NOT IN (SELECT MAX(id) FROM dns_records GROUP BY monitor_id)");
        m.put("http-metric-minute", "DELETE FROM http_metric_minute WHERE bucket_minute < ?");
        m.put("login-anomaly", "DELETE FROM login_anomaly_incident WHERE resolved = true AND opened_at < ?");
        m.put("series-http", "DELETE FROM http_checks WHERE checked_at < ?");
        m.put("page-resource-issues", "DELETE FROM page_resource_issues WHERE checked_at < ?");
        m.put("page-checks", "DELETE FROM page_checks WHERE checked_at < ?");
        m.put("series-scripted", "DELETE FROM scripted_checks WHERE checked_at < ?");
        m.put("system-heartbeat", "DELETE FROM system_heartbeat WHERE recorded_at < CAST(? AS timestamp)");
        m.put("series-domain", "DELETE FROM domain_checks WHERE checked_at < ? "
                + "AND id NOT IN (SELECT MAX(id) FROM domain_checks GROUP BY monitor_id) "
                + "AND id NOT IN (SELECT MAX(id) FROM domain_checks WHERE source <> 'NONE' GROUP BY monitor_id)");
        m.put("diagnostic-runs", "DELETE FROM diagnostic_runs WHERE executed_at < ?");
        m.put("alert-events", "DELETE FROM alert_events WHERE resolved = true AND resolved_at < ?");
        m.put("login-issue-images", "DELETE FROM login_issue_report_images WHERE report_id IN "
                + "(SELECT id FROM login_issue_reports WHERE status = 'RESOLVED' AND resolved_at < ?)");
        m.put("login-issue-reports", "DELETE FROM login_issue_reports WHERE status = 'RESOLVED' AND resolved_at < ?");
        m.put("login-issue-mail-logs", "DELETE FROM login_issue_mail_logs WHERE report_id NOT IN (SELECT id FROM login_issue_reports)");
        m.put("weekly-report-images", "DELETE FROM weekly_report_images WHERE created_at < ?");
        m.put("activity-log", "DELETE FROM activity_log WHERE activity_time < ?");
        m.put("network-outage", "DELETE FROM network_outage_events WHERE detected_at < ?");
        m.put("incident-records", "DELETE FROM incident_records WHERE status = 'RESOLVED' AND occurred_at < ?");
        return m;
    }

    private static String norm(String sql) {
        return sql.replaceAll("\\s+", " ").trim();
    }

    @Test
    @DisplayName("Katalog, dönüşüm ÖNCESİ 27 DELETE cümlesinin AYNISINI üretir (davranış-nötrlük)")
    void catalogReproducesLegacySql() {
        Map<String, String> legacy = legacySql();
        for (Map.Entry<String, String> e : legacy.entrySet()) {
            RetentionPolicy p = RetentionCatalog.byId(e.getKey())
                    .orElseThrow(() -> new AssertionError("Politika kayıp: " + e.getKey()));
            assertThat(norm(p.deleteSql()))
                    .as("SQL kimliği: %s", e.getKey())
                    .isEqualTo(norm(e.getValue()));
        }
        assertThat(legacy).as("Eski kuralların hepsi kapsandı").hasSize(27);
    }

    @Test
    @DisplayName("Batch'li olan tablolar dönüşüm öncesiyle bire bir aynı (12 tablo)")
    void batchedFlagsMatchLegacy() {
        // Eskiden safeDeleteBatched ile silinenler — id kolonu ister, ANALYZE ile biter.
        assertThat(RetentionCatalog.ALL.stream().filter(RetentionPolicy::batched).map(RetentionPolicy::id))
                .containsExactlyInAnyOrder("audit-log", "notification-logs", "series-uptime",
                        "series-certificate", "series-port", "series-keyword", "series-ping",
                        "series-http", "page-resource-issues", "page-checks", "series-scripted",
                        "activity-log");
    }

    @Test
    @DisplayName("Çalıştırma sırası korunur: çocuk tablolar ebeveynden ÖNCE, öksüz temizliği SONRA")
    void executionOrderPreservesFkConventions() {
        var ids = RetentionCatalog.executable().stream().map(RetentionPolicy::id).toList();
        assertThat(ids.indexOf("page-resource-issues")).isLessThan(ids.indexOf("page-checks"));
        assertThat(ids.indexOf("login-issue-images")).isLessThan(ids.indexOf("login-issue-reports"));
        assertThat(ids.indexOf("login-issue-reports")).isLessThan(ids.indexOf("login-issue-mail-logs"));
        assertThat(ids.indexOf("retention-run-items")).isLessThan(ids.indexOf("retention-runs"));
    }

    @Test
    @DisplayName("Dry-run sayımı, silme ile AYNI WHERE'i kullanır ve DELETE içermez")
    void countSqlMirrorsDeleteWithoutDeleting() {
        for (RetentionPolicy p : RetentionCatalog.executable()) {
            assertThat(p.countSql()).as(p.id()).startsWith("SELECT COUNT(*) FROM ").doesNotContain("DELETE");
            assertThat(norm(p.countSql()).substring(norm(p.countSql()).indexOf(" WHERE ")))
                    .as("aynı WHERE: %s", p.id())
                    .isEqualTo(norm(p.deleteSql()).substring(norm(p.deleteSql()).indexOf(" WHERE ")));
        }
    }

    @Test
    @DisplayName("Parametre sayısı: öksüz kurallar 0, diğerleri tam 1 (cutoff)")
    void paramCounts() {
        for (RetentionPolicy p : RetentionCatalog.executable()) {
            int expected = p.mode() == RetentionPolicy.Mode.ORPHAN_ONLY ? 0 : 1;
            assertThat(p.paramCount()).as(p.id()).isEqualTo(expected);
        }
    }
}
