package com.sitemonitor.service.page.scan.budget;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Eğilim servisi (F2) — GERÇEK SQL (H2): pencere, yalnız DONE, eskiden yeniye ≤ 200 nokta (+ kesilme), nokta başına güncel bütçe
 * işareti, büyüme yalnız ayrıntısı saklı ve en yeni analizle AYNI algoritma sürümündeki ≤ 20 analizden, sabit sorgu sayısı
 * (analiz JSON'u okunmaz), aynı küme için bellek, arada budanan ayrıntı, eski şemada kolonsuz okuma.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ResourceTrendServiceTest {

    static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    static final long NOW = Instant.parse("2026-11-04T12:00:00Z").toEpochMilli();
    static final long DAY = 86_400_000L;

    @Autowired DataSource dataSource;

    JdbcTemplate jdbc;
    ResourceTrendService svc;

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object o) {
        return (Map<String, Object>) o;
    }

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        ResourceBudgetServiceTest.ensureDeepColumns(jdbc);
        jdbc.update("DELETE FROM page_resource_scans");
        svc = new ResourceTrendService(jdbc);
        svc.clock = () -> NOW;
    }

    static String items(long appBytes, long heroBytes) {
        return "[{\"url\":\"https://x/app.js\",\"category\":\"JS\",\"bytes\":" + appBytes + ",\"page_load\":true,\"verdict\":\"ok\"},"
                + "{\"url\":\"https://x/hero.png\",\"category\":\"IMAGE\",\"bytes\":" + heroBytes + ",\"page_load\":true,\"verdict\":\"ok\"}]";
    }

    long scan(long monitorId, String status, long daysAgo, Integer algo, String itemsJson, long pageBytes, Long js, Integer security,
              String scope) {
        String at = ISO.format(Instant.ofEpochMilli(NOW - daysAgo * DAY));
        jdbc.update("INSERT INTO page_resource_scans (monitor_id, url, status, started_at, finished_at, algorithm_version, capped, "
                        + "grade, load_ms, page_bytes, request_count, image_bytes, js_bytes, css_bytes, font_bytes, security_score, "
                        + "seo_score, a11y_score, scope, items_json, items_kept, analysis_json) "
                        + "VALUES (?, 'https://x/', ?, ?, ?, ?, FALSE, 80, 1200, ?, 30, 1000, ?, 200, 300, ?, 90, 70, ?, ?, ?, 'BOZUK')",
                monitorId, status, at, at, algo, pageBytes, js, security, scope, itemsJson, itemsJson != null);
        return jdbc.queryForObject("SELECT MAX(id) FROM page_resource_scans", Long.class);
    }

    @Test
    @DisplayName("noktalar: pencere içi DONE, eskiden yeniye; RUNNING / FAILED / pencere dışı / başka izleme yok; analiz JSON'u okunmaz")
    void pointsWindowAndOrder() {
        long old = scan(1, "DONE", 40, 3, null, 100, 10L, 50, null);
        long a = scan(1, "DONE", 20, 2, null, 200, null, null, null);
        scan(1, "FAILED", 15, 3, null, 0, null, null, null);
        scan(1, "RUNNING", 0, 3, null, 0, null, null, null);
        long b = scan(1, "DONE", 5, 3, null, 300, 30L, 77, "site");
        scan(2, "DONE", 5, 3, null, 999, 1L, 1, null);

        Map<String, Object> r = svc.trend(1, 30, ResourceBudget.parse("{\"max_page_kb\":50}"));
        assertThat(r).containsEntry("days", 30).containsEntry("truncated", false).containsEntry("max_points", 200);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> points = (List<Map<String, Object>>) r.get("points");
        assertThat(points).extracting(p -> p.get("scan_id")).containsExactly(a, b);
        assertThat(points.get(0)).containsEntry("algorithm_version", 2).containsEntry("js_bytes", null)
                .containsEntry("security_score", null).containsEntry("scope", "page").containsEntry("budget_status", "ok");
        assertThat(points.get(1)).containsEntry("js_bytes", 30L).containsEntry("security_score", 77L).containsEntry("css_bytes", 200L)
                .containsEntry("font_bytes", 300L).containsEntry("seo_score", 90L).containsEntry("a11y_score", 70L)
                .containsEntry("image_bytes", 1000L).containsEntry("request_count", 30L).containsEntry("grade", 80L)
                .containsEntry("load_ms", 1200L).containsEntry("page_bytes", 300L).containsEntry("scope", "site")
                .containsKey("finished_at").containsEntry("capped", false);
        assertThat((List<?>) svc.trend(1, 90, ResourceBudget.EMPTY).get("points")).hasSize(3);
        assertThat(map(r.get("budget"))).containsEntry("defined", true).containsEntry("max_page_kb", 50);
        assertThat(old).isPositive();
    }

    @Test
    @DisplayName("gün değeri: 30 / 90 / 365; başka / bozuk / boş → 90")
    void daysParam() {
        assertThat(ResourceTrendService.days("30")).isEqualTo(30);
        assertThat(ResourceTrendService.days("365")).isEqualTo(365);
        for (String bad : new String[]{null, "", "7", "abc", "-30", "99999999", " 90 "}) {
            assertThat(ResourceTrendService.days(bad)).as(String.valueOf(bad)).isEqualTo(90);
        }
        assertThat(svc.trend(1, 12, ResourceBudget.EMPTY)).containsEntry("days", 90);
    }

    @Test
    @DisplayName("200'den fazla nokta → en yeni 200 (eskiden yeniye) + truncated")
    void pointsCapped() {
        for (int i = 0; i < 205; i++) scan(1, "DONE", 1, 3, null, i, null, null, null);
        Map<String, Object> r = svc.trend(1, 30, ResourceBudget.EMPTY);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> points = (List<Map<String, Object>>) r.get("points");
        assertThat(points).hasSize(200);
        assertThat(r).containsEntry("truncated", true);
        assertThat(points.get(0).get("page_bytes")).isEqualTo(5L);
        assertThat(points.get(199).get("page_bytes")).isEqualTo(204L);
    }

    @Test
    @DisplayName("büyüme: yalnız ayrıntısı saklı ve en yeni analizle aynı algoritma sürümündekiler; sabit sorgu sayısı; aynı küme bellekten")
    void growthBasisAndQueries() {
        scan(1, "DONE", 10, 2, items(10_000, 999_999), 1, null, null, null);   // algoritma 2 — dayanağa girmez
        long s1 = scan(1, "DONE", 8, 3, items(100_000, 200_000), 1, null, null, null);
        scan(1, "DONE", 6, 3, null, 1, null, null, null);                         // ayrıntısı budanmış
        long s3 = scan(1, "DONE", 4, 3, items(150_000, 900_000), 1, null, null, null);

        AtomicInteger connections = new AtomicInteger();
        ResourceTrendService counting = new ResourceTrendService(new JdbcTemplate(
                ResourceBudgetServiceTest.countingConnections(dataSource, connections)));
        counting.clock = () -> NOW;
        Map<String, Object> r = counting.trend(1, 30, ResourceBudget.EMPTY);
        assertThat(connections.get()).as("noktalar + ayrıntı = 2 sorgu").isEqualTo(2);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> growth = (List<Map<String, Object>>) r.get("growth");
        assertThat(growth).extracting(g -> g.get("url")).containsExactly("https://x/hero.png", "https://x/app.js");
        assertThat(growth.get(0)).containsEntry("first_bytes", 200_000L).containsEntry("last_bytes", 900_000L)
                .containsEntry("first_scan_id", s1).containsEntry("last_scan_id", s3);
        assertThat(map(r.get("growth_basis"))).containsEntry("scans", 2).containsEntry("algorithm_version", 3)
                .containsEntry("from_scan_id", s1).containsEntry("to_scan_id", s3);

        // aynı küme → ayrıntı yeniden okunmaz
        connections.set(0);
        counting.trend(1, 30, ResourceBudget.EMPTY);
        assertThat(connections.get()).as("bellekten: yalnız noktalar").isEqualTo(1);
    }

    @Test
    @DisplayName("ayrıntısı saklı tek analiz → büyüme boş, ayrıntı sorgusu yok; arada budanan ayrıntı yok sayılır")
    void growthNeedsTwoScans() {
        long s1 = scan(1, "DONE", 8, 3, items(100_000, 200_000), 1, null, null, null);
        long s2 = scan(1, "DONE", 4, 3, items(150_000, 900_000), 1, null, null, null);
        // dayanak kümesi seçildikten SONRA budanmış gibi: items_kept TRUE ama metin NULL
        jdbc.update("UPDATE page_resource_scans SET items_json = NULL WHERE id = ?", s1);
        Map<String, Object> r = svc.trend(1, 30, ResourceBudget.EMPTY);
        assertThat((List<?>) r.get("growth")).isEmpty();
        assertThat(map(r.get("growth_basis"))).containsEntry("scans", 2).containsEntry("to_scan_id", s2);

        jdbc.update("DELETE FROM page_resource_scans");
        scan(1, "DONE", 4, 3, items(150_000, 900_000), 1, null, null, null);
        AtomicInteger connections = new AtomicInteger();
        ResourceTrendService counting = new ResourceTrendService(new JdbcTemplate(
                ResourceBudgetServiceTest.countingConnections(dataSource, connections)));
        counting.clock = () -> NOW;
        assertThat((List<?>) counting.trend(1, 30, ResourceBudget.EMPTY).get("growth")).isEmpty();
        assertThat(connections.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("büyüme en çok 20 analizden (en yeniler)")
    void growthUsesNewest20() {
        long first = 0;
        for (int i = 0; i < 25; i++) {
            long id = scan(1, "DONE", 25 - i, 3, items(1_000L + i, 1_000L), 1, null, null, null);
            if (i == 5) first = id;
        }
        Map<String, Object> r = svc.trend(1, 30, ResourceBudget.EMPTY);
        assertThat(map(r.get("growth_basis"))).containsEntry("scans", 20).containsEntry("from_scan_id", first);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> growth = (List<Map<String, Object>>) r.get("growth");
        assertThat(growth).hasSize(1);
        assertThat(growth.get(0)).containsEntry("url", "https://x/app.js").containsEntry("first_bytes", 1_005L)
                .containsEntry("last_bytes", 1_024L).containsEntry("scans", 20);
    }

    @Test
    @DisplayName("eski şema (algoritma 3 kolonları yok): noktalar kolonsuz okunur, değerler null, çökmez")
    void legacySchema() {
        DriverManagerDataSource legacy = new DriverManagerDataSource("jdbc:h2:mem:be2trendlegacy;DB_CLOSE_DELAY=-1");
        JdbcTemplate lj = new JdbcTemplate(legacy);
        lj.execute("DROP TABLE IF EXISTS page_resource_scans");
        lj.execute("CREATE TABLE page_resource_scans (id BIGINT AUTO_INCREMENT PRIMARY KEY, monitor_id BIGINT, status VARCHAR(16), "
                + "started_at VARCHAR(30), finished_at VARCHAR(30), algorithm_version INTEGER, grade INTEGER, load_ms BIGINT, "
                + "page_bytes BIGINT, request_count INTEGER, image_bytes BIGINT, capped BOOLEAN, items_kept BOOLEAN, items_json CLOB)");
        String at = ISO.format(Instant.ofEpochMilli(NOW - DAY));
        lj.update("INSERT INTO page_resource_scans (monitor_id, status, started_at, finished_at, grade, page_bytes, capped, items_kept) "
                + "VALUES (1, 'DONE', ?, ?, 70, 1000, FALSE, FALSE)", at, at);
        ResourceTrendService s = new ResourceTrendService(lj);
        s.clock = () -> NOW;
        Map<String, Object> r = s.trend(1, 30, ResourceBudget.parse("{\"max_js_kb\":10}"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> points = (List<Map<String, Object>>) r.get("points");
        assertThat(points).hasSize(1);
        assertThat(points.get(0)).containsEntry("grade", 70L).containsEntry("js_bytes", null).containsEntry("scope", "page")
                .containsEntry("algorithm_version", null).containsEntry("budget_status", "unknown");
    }
}
