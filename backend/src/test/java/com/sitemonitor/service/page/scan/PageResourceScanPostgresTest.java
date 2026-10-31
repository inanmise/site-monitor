package com.sitemonitor.service.page.scan;

import com.sitemonitor.it.PostgresIntegration;
import com.sitemonitor.it.PostgresIt;
import com.sitemonitor.model.PageMonitor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kaynak analizi tablosu GERÇEK PostgreSQL'de (2026-10-31): varlıktan üretilen tablo (TEXT / BOOLEAN / BIGINT), üretilen
 * anahtarla INSERT, {@code LIMIT ?} parametresi, koşullu bitiş / eskime UPDATE'leri, ayrıntı budama ve izleme silme.
 */
@PostgresIntegration
class PageResourceScanPostgresTest {

    @SuppressWarnings("unchecked")
    static <T> T get(Map<String, Object> m, String k) {
        return (T) m.get(k);
    }

    @Test
    @DisplayName("başlat (güvenlik engeli → FAILED), eskiyen satır, DONE yazımı, son 20 ayrıntı, silme")
    void lifecycleOnPostgres() throws Exception {
        PageResourceScanService svc = PostgresIt.app().bean(PageResourceScanService.class);
        JdbcTemplate jdbc = PostgresIt.app().jdbc();
        long mon = 9_870_001L;
        jdbc.update("DELETE FROM page_resource_scans WHERE monitor_id BETWEEN ? AND ?", mon, mon + 10);

        // 1) Döngü adresi varsayılan ayarda güvenlik kuralına takılır → hiç ağ işi olmadan FAILED / PAGE_BLOCKED
        PageMonitor m = new PageMonitor();
        m.setId(mon);
        m.setTeamId(null);
        m.setUrl("http://127.0.0.1:1/");
        PageResourceScanService.Start st = svc.start(m, "u:it", "PG Test");
        assertThat(st.outcome()).isEqualTo(PageResourceScanService.Outcome.STARTED);
        long id = ((Number) st.scan().get("id")).longValue();
        Map<String, Object> scan = null;
        for (int i = 0; i < 200; i++) {
            scan = get(svc.one(id, mon), "scan");
            if (!"RUNNING".equals(scan.get("status"))) break;
            Thread.sleep(50);
        }
        assertThat(scan.get("status")).isEqualTo("FAILED");
        // varsayılan ayarda döngü adresi engelli (PAGE_BLOCKED); ortam izin veriyorsa kapalı porta bağlanamaz (PAGE_FAILED)
        assertThat(scan.get("error_code")).isIn("PAGE_BLOCKED", "PAGE_FAILED");
        assertThat(scan.get("requested_by")).isEqualTo("PG Test");

        // 2) Eskiyen RUNNING satırı → INTERRUPTED
        long old = System.currentTimeMillis() - PageResourceScanService.STALE_MS - 1_000;
        jdbc.update("INSERT INTO page_resource_scans (monitor_id, url, status, started_at, started_ms, heartbeat_ms, "
                + "progress_done, progress_total, capped, items_kept) VALUES (?, 'x', 'RUNNING', ?, ?, ?, 0, 0, FALSE, FALSE)",
                mon + 1, PageResourceScanService.iso(old), old, old);
        Map<String, Object> list = svc.list(mon + 1, 5);
        Map<String, Object> first = ((List<Map<String, Object>>) list.get("items")).get(0);
        assertThat(first.get("status")).isEqualTo("INTERRUPTED");

        // 3) DONE yazımı gerçek tiplerle (boolean / text / bigint)
        long now = System.currentTimeMillis();
        jdbc.update("INSERT INTO page_resource_scans (monitor_id, url, status, started_at, started_ms, heartbeat_ms, "
                + "progress_done, progress_total, capped, items_kept) VALUES (?, 'x', 'RUNNING', ?, ?, ?, 0, 0, FALSE, FALSE)",
                mon + 2, PageResourceScanService.iso(now), now, now);
        long runId = jdbc.queryForObject("SELECT MAX(id) FROM page_resource_scans WHERE monitor_id = ?", Long.class, mon + 2);
        int n = jdbc.update(PageResourceScanService.SQL_DONE, PageResourceScanService.iso(now), now, 5, 5, 87, "B", 2400L,
                180L, 420L, 1_500_000L, 4, 900_000L, 2, 1, 3_000L, 1, 20_000L, 0, true,
                "{\"scores\":[],\"notes\":[{\"code\":\"JS_NOT_EXECUTED\",\"params\":{}}]}",
                "[{\"url\":\"https://a.com/\",\"category\":\"HTML\",\"start_ms\":null}]", runId);
        assertThat(n).isEqualTo(1);
        Map<String, Object> done = svc.one(runId, mon + 2);
        assertThat(get(done, "scan").toString()).contains("grade=87", "capped=true");
        assertThat((List<?>) done.get("items")).hasSize(1);
        assertThat(get(done, "analysis").toString()).contains("JS_NOT_EXECUTED");
        // ikinci bitiş yazımı (artık RUNNING değil) hiçbir şey yazmaz
        assertThat(jdbc.update(PageResourceScanService.SQL_END, "FAILED", PageResourceScanService.iso(now), now, "X", null,
                null, runId)).isZero();

        // 4) Son 20 ayrıntı
        for (int k = 0; k < 22; k++) {
            jdbc.update("INSERT INTO page_resource_scans (monitor_id, url, status, started_at, started_ms, heartbeat_ms, "
                    + "progress_done, progress_total, capped, items_kept, items_json) VALUES (?, 'x', 'DONE', ?, 0, 0, 0, 0, "
                    + "FALSE, TRUE, '[]')", mon + 3, PageResourceScanService.iso(now));
        }
        svc.pruneItems(mon + 3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM page_resource_scans WHERE monitor_id = ? AND items_json IS NOT NULL",
                Integer.class, mon + 3)).isEqualTo(20);
        assertThat((List<?>) svc.list(mon + 3, 200).get("items")).hasSize(22);

        for (long k = 0; k <= 3; k++) svc.deleteForMonitor(mon + k);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM page_resource_scans WHERE monitor_id BETWEEN ? AND ?",
                Integer.class, mon, mon + 3)).isZero();
    }
}
