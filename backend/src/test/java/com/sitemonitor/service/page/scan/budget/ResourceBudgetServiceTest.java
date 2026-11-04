package com.sitemonitor.service.page.scan.budget;

import com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException;
import com.sitemonitor.model.PageMonitor;
import com.sitemonitor.repository.PageMonitorRepository;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Bütçe servisi — GERÇEK SQL (H2, varlıktan üretilen tablolar; algoritma 3 özet kolonları bu dalda varlıkta yoksa testte
 * eklenir): koşullu tek kolon yazımı, eş zamanlı iki değişikliğin birleşmesi, izleme düzenlemesinin (varlığın TAMAMI) bütçeyi
 * geri yazamaması, liste / tek analiz işaretleri ve eski şemada ({@code js_bytes} / {@code security_score} yok) çökmeden
 * "bilinmiyor".
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ResourceBudgetServiceTest {

    @Autowired DataSource dataSource;
    @Autowired PageMonitorRepository pageMonitorRepo;

    JdbcTemplate jdbc;
    ResourceBudgetService svc;

    /** Algoritma 3 özet kolonları (be1 varlığa ekler; birleşmeden önce bu dalda yoktur) — varsa dokunulmaz. */
    static void ensureDeepColumns(JdbcTemplate jdbc) {
        for (String c : List.of("js_bytes BIGINT", "css_bytes BIGINT", "font_bytes BIGINT", "security_score INTEGER",
                "seo_score INTEGER", "a11y_score INTEGER", "scope VARCHAR(10)", "pages_count INTEGER")) {
            jdbc.execute("ALTER TABLE page_resource_scans ADD COLUMN IF NOT EXISTS " + c);
        }
    }

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        ensureDeepColumns(jdbc);
        jdbc.update("DELETE FROM page_resource_scans");
        pageMonitorRepo.deleteAll();
        svc = new ResourceBudgetService(jdbc);
    }

    PageMonitor monitor() {
        PageMonitor m = new PageMonitor();
        m.setName("Bütçe testi");
        m.setUrl("https://shop.example.test/");
        m.setTeamId(5L);
        return pageMonitorRepo.save(m);
    }

    String stored(long id) {
        return jdbc.queryForObject("SELECT resource_budget_json FROM page_monitors WHERE id = ?", String.class, id);
    }

    long scan(long monitorId, String status, boolean capped, Long pageBytes, Long requests, Long js, Long images, Long loadMs,
              Integer grade, Integer security) {
        jdbc.update("INSERT INTO page_resource_scans (monitor_id, url, status, started_at, capped, page_bytes, request_count, "
                        + "js_bytes, image_bytes, load_ms, grade, security_score, items_kept) VALUES (?, 'https://x/', ?, "
                        + "'2026-11-01T10:00:00', ?, ?, ?, ?, ?, ?, ?, ?, FALSE)",
                monitorId, status, capped, pageBytes, requests, js, images, loadMs, grade, security);
        return jdbc.queryForObject("SELECT MAX(id) FROM page_resource_scans", Long.class);
    }

    static Map<String, Object> row(long id) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        return m;
    }

    // ── Yazım ─────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("ilk kayıt NULL kolona yazar; yalnız gönderilen anahtar değişir; hepsi kaldırılınca kolon NULL")
    void saveLifecycle() {
        PageMonitor m = monitor();
        ResourceBudgetService.Save r = svc.save(m.getId(), null, Map.of("max_page_kb", 800, "min_grade", 70));
        assertThat(r.outcome()).isEqualTo(ResourceBudgetService.Outcome.SAVED);
        assertThat(stored(m.getId())).isEqualTo("{\"max_page_kb\":800,\"min_grade\":70}");
        assertThat(r.before().defined()).isFalse();

        Map<String, Object> body = new HashMap<>();
        body.put("min_grade", null);
        body.put("max_requests", 90);
        r = svc.save(m.getId(), stored(m.getId()), body);
        assertThat(r.outcome()).isEqualTo(ResourceBudgetService.Outcome.SAVED);
        assertThat(stored(m.getId())).isEqualTo("{\"max_page_kb\":800,\"max_requests\":90}");

        Map<String, Object> clear = new HashMap<>();
        clear.put("max_page_kb", null);
        clear.put("max_requests", null);
        svc.save(m.getId(), stored(m.getId()), clear);
        assertThat(stored(m.getId())).isNull();
    }

    @Test
    @DisplayName("değişiklik yoksa yazılmaz (UNCHANGED); geçersiz alan hiçbir şey yazmadan alan hatası")
    void unchangedAndInvalid() {
        PageMonitor m = monitor();
        svc.save(m.getId(), null, Map.of("max_page_kb", 800));
        String before = stored(m.getId());
        assertThat(svc.save(m.getId(), before, Map.of("max_page_kb", 800)).outcome())
                .isEqualTo(ResourceBudgetService.Outcome.UNCHANGED);
        assertThat(svc.save(m.getId(), before, Map.of()).outcome()).isEqualTo(ResourceBudgetService.Outcome.UNCHANGED);
        assertThatThrownBy(() -> svc.save(m.getId(), before, Map.of("max_requests", 90, "max_js_kb", 5)))
                .isInstanceOf(FieldValidationException.class);
        assertThat(stored(m.getId())).isEqualTo(before);
    }

    @Test
    @DisplayName("eş zamanlı iki kayıt (ikisi de eski değeri okumuş): ikincisi yeniden okuyup birleştirir — iki değişiklik de kalır")
    void concurrentSavesMerge() {
        PageMonitor m = monitor();
        svc.save(m.getId(), null, Map.of("max_page_kb", 800));
        String seen = stored(m.getId());
        // A: max_requests ekler (okuduğu değerle yazar)
        assertThat(svc.save(m.getId(), seen, Map.of("max_requests", 50)).outcome()).isEqualTo(ResourceBudgetService.Outcome.SAVED);
        // B: aynı ESKİ değeri okumuştu, min_grade ekler → koşul tutmaz, güncel değere uygulanır
        assertThat(svc.save(m.getId(), seen, Map.of("min_grade", 60)).outcome()).isEqualTo(ResourceBudgetService.Outcome.SAVED);
        assertThat(ResourceBudget.parse(stored(m.getId())).toMap())
                .containsEntry("max_page_kb", 800).containsEntry("max_requests", 50).containsEntry("min_grade", 60);
        // B eski okumayla A'nın değerini GERİ ALMAYA çalışsa bile yalnız gönderdiği anahtar değişir
        assertThat(svc.save(m.getId(), seen, Map.of("max_page_kb", 900)).outcome()).isEqualTo(ResourceBudgetService.Outcome.SAVED);
        assertThat(ResourceBudget.parse(stored(m.getId())).toMap())
                .containsEntry("max_page_kb", 900).containsEntry("max_requests", 50).containsEntry("min_grade", 60);
    }

    @Test
    @DisplayName("eski okumayla gelen değişiklik güncel değerde zaten varsa yazılmaz (UNCHANGED); silinmiş izleme NOT_FOUND")
    void staleSeenAlreadyApplied_andDeleted() {
        PageMonitor m = monitor();
        svc.save(m.getId(), null, Map.of("max_page_kb", 800));
        assertThat(svc.save(m.getId(), null, Map.of("max_page_kb", 800)).outcome())
                .isEqualTo(ResourceBudgetService.Outcome.UNCHANGED);
        assertThat(svc.save(987_654L, null, Map.of("max_page_kb", 800)).outcome())
                .isEqualTo(ResourceBudgetService.Outcome.NOT_FOUND);
    }

    @Test
    @DisplayName("izleme düzenlemesi varlığın TAMAMINI kaydeder ama bütçe kolonunu geri yazamaz (updatable=false); varlık okur")
    void monitorEditNeverOverwritesBudget() {
        PageMonitor m = monitor();
        PageMonitor stale = pageMonitorRepo.findById(m.getId()).orElseThrow();   // bütçe yokken okunan kopya
        svc.save(m.getId(), null, Map.of("max_page_kb", 800));
        stale.setName("Yeniden adlandırıldı");
        stale.setResourceBudgetJson(null);
        pageMonitorRepo.save(stale);
        assertThat(stored(m.getId())).isEqualTo("{\"max_page_kb\":800}");
        PageMonitor fresh = pageMonitorRepo.findById(m.getId()).orElseThrow();
        assertThat(fresh.getName()).isEqualTo("Yeniden adlandırıldı");
        assertThat(ResourceBudgetService.budgetOf(fresh).limit("max_page_kb")).isEqualTo(800);
        // yeni izleme (kopyala) bütçe taşımaz: insertable=false
        PageMonitor copy = new PageMonitor();
        copy.setName("Kopya");
        copy.setUrl("https://shop.example.test/x");
        copy.setResourceBudgetJson("{\"max_page_kb\":800}");
        copy = pageMonitorRepo.save(copy);
        assertThat(stored(copy.getId())).isNull();
        assertThat(ResourceBudgetService.budgetOf(null).defined()).isFalse();
    }

    // ── İşaretleme ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("liste: bütçe yokken sorgu yapılmaz (none, 0); tanımlıyken tek sorgu, satır başına durum + aşım sayısı")
    void annotateRows() {
        PageMonitor m = monitor();
        long ok = scan(m.getId(), "DONE", false, 100_000L, 20L, 50_000L, 30_000L, 1500L, 90, 85);
        long over = scan(m.getId(), "DONE", false, 900_000L, 20L, 500_000L, 30_000L, 1500L, 60, 85);
        long old = scan(m.getId(), "DONE", false, 100_000L, 20L, null, 30_000L, 1500L, 90, null);   // algoritma 1/2 satırı
        long running = scan(m.getId(), "RUNNING", false, null, null, null, null, null, null, null);
        long capped = scan(m.getId(), "DONE", true, 100_000L, 20L, 50_000L, 30_000L, 1500L, 90, 85);

        AtomicInteger connections = new AtomicInteger();
        ResourceBudgetService counting = new ResourceBudgetService(new JdbcTemplate(countingConnections(dataSource, connections)));

        List<Map<String, Object>> rows = new ArrayList<>(List.of(row(ok), row(over), row(old), row(running), row(capped)));
        assertThat(counting.annotate(m.getId(), ResourceBudget.EMPTY, rows)).isTrue();
        assertThat(connections.get()).as("bütçe yok → sorgu yok").isZero();
        assertThat(rows).allSatisfy(r -> assertThat(r).containsEntry("budget_status", "none").containsEntry("budget_exceeded", 0));

        ResourceBudget b = ResourceBudget.parse("{\"max_page_kb\":500,\"max_js_kb\":200,\"min_grade\":70,\"min_security_score\":80}");
        assertThat(counting.annotate(m.getId(), b, rows)).isTrue();
        assertThat(connections.get()).as("tek sorgu").isEqualTo(1);
        assertThat(rows.get(0)).containsEntry("budget_status", "ok").containsEntry("budget_exceeded", 0);
        assertThat(rows.get(1)).containsEntry("budget_status", "exceeded").containsEntry("budget_exceeded", 3);
        assertThat(rows.get(2)).containsEntry("budget_status", "unknown").containsEntry("budget_exceeded", 0);
        assertThat(rows.get(3)).containsEntry("budget_status", "unknown");
        assertThat(rows.get(4)).containsEntry("budget_status", "unknown");   // tavan: geçen değer bilinmez
    }

    @Test
    @DisplayName("tek analiz: budget = {defined, status, exceeded, checks}; başka izlemenin analizi değerlendirilmez")
    void evaluateOne() {
        PageMonitor m = monitor();
        PageMonitor other = monitor();
        long s = scan(m.getId(), "DONE", false, 900_000L, 20L, 50_000L, 30_000L, 1500L, 90, 85);
        ResourceBudget b = ResourceBudget.parse("{\"max_page_kb\":500,\"max_requests\":30}");
        Map<String, Object> r = svc.evaluateOne(m.getId(), b, s);
        assertThat(r).containsEntry("defined", true).containsEntry("status", "exceeded").containsEntry("exceeded", 1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> checks = (List<Map<String, Object>>) r.get("checks");
        assertThat(checks).extracting(c -> c.get("key") + "=" + c.get("ok")).containsExactly("max_page_kb=false", "max_requests=true");
        // başka izlemenin kimliğiyle → analiz yok sayılır (bilinmiyor, sızmaz)
        assertThat(svc.evaluateOne(other.getId(), b, s)).containsEntry("status", "unknown");
        assertThat(svc.evaluateOne(m.getId(), ResourceBudget.EMPTY, s)).containsEntry("status", "none").containsEntry("defined", false);
    }

    @Test
    @DisplayName("tablo okunamazsa: liste satırları unknown (sayı null), tek analiz unknown + unreadable — asla 'geçti'")
    void unreadableIsUnknown() {
        ResourceBudgetService broken = new ResourceBudgetService(new JdbcTemplate(
                new DriverManagerDataSource("jdbc:h2:mem:be2budgetbroken;DB_CLOSE_DELAY=-1")));
        ResourceBudget b = ResourceBudget.parse("{\"max_page_kb\":500}");
        List<Map<String, Object>> rows = new ArrayList<>(List.of(row(1)));
        assertThat(broken.annotate(1, b, rows)).isFalse();
        assertThat(rows.get(0)).containsEntry("budget_status", "unknown").containsEntry("budget_exceeded", null);
        assertThat(broken.evaluateOne(1, b, 1)).containsEntry("status", "unknown").containsEntry("unreadable", true)
                .containsEntry("exceeded", null);
    }

    @Test
    @DisplayName("eski şema (js_bytes / security_score yok): kolonsuz sorguyla okunur, o sınırlar 'bilinmiyor', diğerleri değerlendirilir")
    void legacySchemaFallsBack() {
        DriverManagerDataSource legacy = new DriverManagerDataSource("jdbc:h2:mem:be2budgetlegacy;DB_CLOSE_DELAY=-1");
        JdbcTemplate lj = new JdbcTemplate(legacy);
        lj.execute("DROP TABLE IF EXISTS page_resource_scans");
        lj.execute("CREATE TABLE page_resource_scans (id BIGINT AUTO_INCREMENT PRIMARY KEY, monitor_id BIGINT, status VARCHAR(16), "
                + "capped BOOLEAN, page_bytes BIGINT, request_count INTEGER, image_bytes BIGINT, load_ms BIGINT, grade INTEGER)");
        lj.update("INSERT INTO page_resource_scans (monitor_id, status, capped, page_bytes, request_count, image_bytes, load_ms, grade) "
                + "VALUES (7, 'DONE', FALSE, 900000, 10, 1000, 1000, 95)");
        long id = lj.queryForObject("SELECT MAX(id) FROM page_resource_scans", Long.class);
        ResourceBudgetService s = new ResourceBudgetService(lj);
        List<Map<String, Object>> rows = new ArrayList<>(List.of(row(id)));
        assertThat(s.annotate(7, ResourceBudget.parse("{\"max_js_kb\":100,\"min_security_score\":50}"), rows)).isTrue();
        assertThat(rows.get(0)).containsEntry("budget_status", "unknown");
        assertThat(s.annotate(7, ResourceBudget.parse("{\"max_js_kb\":100,\"max_page_kb\":500}"), rows)).isTrue();
        assertThat(rows.get(0)).containsEntry("budget_status", "exceeded").containsEntry("budget_exceeded", 1);
    }

    /** Bağlantı açılışlarını sayan veri kaynağı (her ifade işlem dışında bir bağlantı ister). */
    static DataSource countingConnections(DataSource real, AtomicInteger connections) {
        return (DataSource) java.lang.reflect.Proxy.newProxyInstance(ResourceBudgetServiceTest.class.getClassLoader(),
                new Class<?>[]{DataSource.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getConnection")) connections.incrementAndGet();
                    try {
                        return method.invoke(real, args);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }
}
