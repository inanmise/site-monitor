package com.sitemonitor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Geriye dönük fırtına devri izi — gerçek SQL, H2 bellek-içi (JdbcTemplate mock'u SQL'in çalıştığını kanıtlamaz).
 * Yalnız {@code storm_id}'li VE hiç bildirim satırı olmayan olaylara yazar; ikinci koşuda hiçbir şey yazmaz.
 */
class StormSuppressionBackfillTest {

    private JdbcTemplate jdbc;
    private StormSuppressionBackfill backfill;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:stormbackfill" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE teams (id BIGINT PRIMARY KEY, name VARCHAR(200), email VARCHAR(300))");
        jdbc.execute("CREATE TABLE alert_events (id BIGINT PRIMARY KEY, alert_type VARCHAR(60), domain VARCHAR(300), team_id BIGINT, "
                + "alert_level VARCHAR(20), storm_id BIGINT, created_at VARCHAR(30), message CLOB)");
        jdbc.execute("CREATE TABLE notification_logs (id BIGINT AUTO_INCREMENT PRIMARY KEY, alert_event_id BIGINT, sent_at VARCHAR(30), "
                + "recipient_name VARCHAR(200), recipient_email VARCHAR(600), recipient_role VARCHAR(40), subject VARCHAR(600), "
                + "message CLOB, email_status VARCHAR(300), webhook_status VARCHAR(300), trigger VARCHAR(40), email_from VARCHAR(200), cc VARCHAR(600))");
        jdbc.execute("CREATE TABLE user_push_deliveries (id BIGINT AUTO_INCREMENT PRIMARY KEY, alert_event_id BIGINT, push_trigger VARCHAR(20), "
                + "dedupe_key VARCHAR(60), monitor_type VARCHAR(20), monitor_id BIGINT, monitor_name VARCHAR(300), team_id BIGINT, "
                + "alert_level VARCHAR(20), username VARCHAR(100), display_name VARCHAR(200), title VARCHAR(200), message CLOB, "
                + "status VARCHAR(40), http_status INT, error CLOB, attempts INT, created_at VARCHAR(30), sent_at VARCHAR(30))");
        jdbc.update("INSERT INTO teams VALUES (14, 'SY-Kurumsal Mimari', 'sy@example.com')");
        // 414: fırtınaya sessizce bağlanmış, hiç bildirim satırı yok → iz yazılır
        jdbc.update("INSERT INTO alert_events VALUES (414, 'SCRIPTED_FAIL', 'OCPA - Response Time Anomalisi', 14, 'WARNING', 7, '2026-09-30T13:45:29', 'KRITIK: ...')");
        // 392: fırtına üyesi ama bir bildirim satırı VAR (elle çözüm maili) → dokunulmaz
        jdbc.update("INSERT INTO alert_events VALUES (392, 'SCRIPTED_FAIL', 'OCPA - Response Time Anomalisi', 14, 'WARNING', 7, '2026-09-29T13:40:09', 'x')");
        jdbc.update("INSERT INTO notification_logs (alert_event_id, sent_at, trigger, email_status) VALUES (392, '2026-09-30T05:09:08', 'RESOLUTION', 'SENT')");
        // 300: fırtınasız normal alarm → dokunulmaz
        jdbc.update("INSERT INTO alert_events VALUES (300, 'HTTP_DOWN', 'https://a', 14, 'WARNING', NULL, '2026-09-24T14:21:00', 'y')");
        backfill = new StormSuppressionBackfill(jdbc);
    }

    @Test
    @DisplayName("Yalnız storm_id'li ve bildirimsiz olaya STORM satırı + SKIPPED_STORM push kararı yazılır; ikinci koşu -1 ve satır eklemez")
    void applyOnce_writesTraceOnceForSilentStormMembers() {
        assertThat(backfill.applyOnce()).isEqualTo(1);

        List<Map<String, Object>> logs = jdbc.queryForList("SELECT * FROM notification_logs WHERE alert_event_id = 414");
        assertThat(logs).hasSize(1);
        Map<String, Object> l = logs.get(0);
        assertThat(String.valueOf(l.get("TRIGGER"))).isEqualTo("STORM");
        assertThat(String.valueOf(l.get("EMAIL_STATUS"))).startsWith("SKIPPED: fırtına #7").contains("geriye dönük");
        assertThat(String.valueOf(l.get("SENT_AT"))).isEqualTo("2026-09-30T13:45:29");   // açılış anı damgası
        assertThat(String.valueOf(l.get("RECIPIENT_NAME"))).isEqualTo("SY-Kurumsal Mimari");

        List<Map<String, Object>> push = jdbc.queryForList("SELECT * FROM user_push_deliveries WHERE alert_event_id = 414");
        assertThat(push).hasSize(1);
        assertThat(String.valueOf(push.get(0).get("STATUS"))).isEqualTo("SKIPPED_STORM");
        assertThat(String.valueOf(push.get(0).get("DEDUPE_KEY"))).isEqualTo("OPEN");
        assertThat(String.valueOf(push.get(0).get("MONITOR_TYPE"))).isEqualTo("scripted");

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_logs WHERE alert_event_id IN (392, 300)", Integer.class)).isEqualTo(1);

        assertThat(backfill.applyOnce()).isEqualTo(-1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_logs", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM schema_patch_markers WHERE patch_key = ?", Integer.class,
                StormSuppressionBackfill.KEY)).isEqualTo(1);
    }
}
