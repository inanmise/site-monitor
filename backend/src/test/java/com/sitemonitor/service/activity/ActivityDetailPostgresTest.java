package com.sitemonitor.service.activity;

import com.sitemonitor.it.PostgresIntegration;
import com.sitemonitor.it.PostgresIt;
import com.sitemonitor.repository.ActivityLogRepository;
import com.sitemonitor.service.schema.DeferredIndexBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Aktivite ayrıntısının PostgreSQL planları (2026-11-05, aktivite incelemesi Y1 / D8) — ÇARPIK veriyle (çok izleme, eski
 * çapa, büyük kontrol tablosu):
 * <ul>
 *   <li>önceki / sonraki kontrol sorgusu ({@link ActivityDetailService#neighbourSql}) {@code (monitor_id, checked_at)}
 *       bileşik dizinini kullanır, tabloyu taramaz (eski {@code checkedAt > :at OR (…)} koşulu dizin koşuluna dönüşmüyordu);</li>
 *   <li>alarm satırının izleme kimliği okuması ({@link ActivityLogRepository#SQL_TRIGGERED_MONITOR_ID}) kısmi
 *       {@code idx_act_alert} dizinine gider.</li>
 * </ul>
 * Sorgular gerçek sınıflardaki SABİT metinlerdir; EXPLAIN aynı metni bağlı parametrelerle koşar.
 */
@PostgresIntegration
class ActivityDetailPostgresTest {

    private static final long MON = 990_000_000L + (long) (Math.random() * 1_000_000);

    private static JdbcTemplate jdbc() {
        return PostgresIt.app().jdbc();
    }

    @BeforeAll
    static void seed() throws Exception {
        CompletableFuture<DeferredIndexBuilder.Result> run = DeferredIndexBuilder.lastRun();
        if (run != null) run.get(5, TimeUnit.MINUTES);
        for (DeferredIndexBuilder.Spec s : DeferredIndexBuilder.CATALOG) {
            if (s.name().equals("idx_act_alert")) jdbc().execute(s.postgresDdl());
        }
        // Çarpık dağılım: 300 izleme × 200 kontrol (60 000 satır) + hedef izlemenin 400 kontrolü; çapa ESKİ uçta
        Instant t0 = Instant.parse("2031-01-01T00:00:00Z");
        List<Object[]> batch = new ArrayList<>();
        for (int m = 0; m < 300; m++) {
            for (int i = 0; i < 200; i++) {
                batch.add(new Object[]{MON + 1 + m, ActivityFilter.iso(t0.plusSeconds(i * 300L + m)), i % 7 != 0});
            }
        }
        for (int i = 0; i < 400; i++) batch.add(new Object[]{MON, ActivityFilter.iso(t0.plusSeconds(i * 300L)), true});
        jdbc().batchUpdate("INSERT INTO port_checks (monitor_id, checked_at, open) VALUES (?, ?, ?)", batch);
        List<Object[]> acts = new ArrayList<>();
        for (int i = 0; i < 2000; i++) {
            acts.add(new Object[]{ActivityFilter.iso(t0.plusSeconds(i * 60L)), i < 1000 ? null : (long) (MON + i)});
        }
        jdbc().batchUpdate("INSERT INTO activity_log (activity_time, monitor_type, monitor_id, action, result_status,"
                + " alert_event_id) VALUES (?, 'CERT', 1, 'ALERT_TRIGGERED', 'ERROR', ?)", acts);
        jdbc().execute("ANALYZE port_checks");
        jdbc().execute("ANALYZE activity_log");
    }

    private static final Pattern NAMED = Pattern.compile(":([a-zA-Z][a-zA-Z0-9]*)");

    /** Adlı parametreli metni konumsala çevirip EXPLAIN eder; {@code noSeqScan}: küçük tabloda da dizin seçimi görülsün. */
    private static String explain(String sql, Map<String, Object> params, boolean noSeqScan) {
        List<Object> values = new ArrayList<>();
        Matcher m = NAMED.matcher(sql);
        StringBuilder positional = new StringBuilder();
        while (m.find()) {
            values.add(params.get(m.group(1)));
            m.appendReplacement(positional, "?");
        }
        m.appendTail(positional);
        return jdbc().execute((ConnectionCallback<String>) c -> {
            StringBuilder plan = new StringBuilder();
            if (noSeqScan) {
                try (Statement st = c.createStatement()) {
                    st.execute("SET enable_seqscan = off");
                }
            }
            try (PreparedStatement ps = c.prepareStatement("EXPLAIN " + positional)) {
                for (int i = 0; i < values.size(); i++) ps.setObject(i + 1, values.get(i));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) plan.append(rs.getString(1)).append('\n');
                }
            } finally {
                if (noSeqScan) {
                    try (Statement st = c.createStatement()) {
                        st.execute("RESET enable_seqscan");
                    }
                }
            }
            return plan.toString();
        });
    }

    @Test
    @DisplayName("Y1 EXPLAIN: önceki / sonraki kontrol (eski çapa, çarpık veri) bileşik dizinle — Seq Scan yok, OR filtresi yok")
    void neighbourQueryUsesCompositeIndex() {
        String at = ActivityFilter.iso(Instant.parse("2031-01-01T00:05:00Z"));
        for (boolean before : new boolean[]{true, false}) {
            for (boolean strict : new boolean[]{true, false}) {
                String bound = before ? "2030-12-25T00:05:00" : "2031-01-08T00:05:00~";
                String plan = explain(ActivityDetailService.neighbourSql(ActivityDetailService.CheckTable.PORT, before, strict),
                        Map.of("k1", MON, "at", at, "bound", bound), false);
                assertThat(plan).as("plan (before=%s strict=%s):%n%s", before, strict, plan)
                        .contains("idx_portc_monitor_checked").doesNotContain("Seq Scan");
            }
        }
    }

    @Test
    @DisplayName("Y1: önceki / sonraki kontrol PostgreSQL'de doğru satırı bulur (aynı saniye ayıklaması, pencere)")
    void neighbourQueryRunsOnPostgres() {
        String at = ActivityFilter.iso(Instant.parse("2031-01-01T00:05:00Z"));
        List<Map<String, Object>> next = jdbc().queryForList(ActivityDetailService.neighbourSql(
                ActivityDetailService.CheckTable.PORT, false, false).replace(":k1", "?").replace(":at", "?").replace(":bound", "?"),
                MON, at, "2031-01-08T00:05:00~");
        assertThat(next).isNotEmpty();
        assertThat(next.get(0).get("checked_at")).isEqualTo(at);   // aynı saniye: Java ayıklar
        assertThat(next.get(1).get("checked_at")).isEqualTo("2031-01-01T00:10:00");
    }

    @Test
    @DisplayName("D8 EXPLAIN: alarm satırının izleme kimliği okuması kısmi idx_act_alert dizinine gider")
    void triggeredMonitorIdUsesAlertIndex() {
        String plan = explain(ActivityLogRepository.SQL_TRIGGERED_MONITOR_ID + " LIMIT 1", Map.of("alertEventId", MON + 1500), true);
        assertThat(plan).as(plan).contains("idx_act_alert");
        ActivityLogRepository repo = PostgresIt.app().bean(ActivityLogRepository.class);
        assertThat(repo.triggeredMonitorId(MON + 1500, org.springframework.data.domain.PageRequest.of(0, 1))).containsExactly(1L);
    }
}
