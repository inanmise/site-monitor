package com.certmonitor.service;

import com.certmonitor.model.IncidentRecord;
import com.certmonitor.repository.IncidentRecordRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestPropertySource;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * IncidentService — H2'de gerçek filtre/trend sorguları (JPQL SUBSTRING gün gruplaması,
 * LOWER LIKE arama) + validation. @DataJpaTest + servisi @Import ile yükler.
 */
@DataJpaTest
@Import(IncidentService.class)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class IncidentServiceTest {

    @Autowired IncidentService service;
    @Autowired IncidentRecordRepository repo;

    private Map<String, Object> body(String title, String occurredAt, String sev, String cat) {
        Map<String, Object> m = new HashMap<>();
        m.put("title", title);
        m.put("occurred_at", occurredAt);
        m.put("severity", sev);
        m.put("status", "RESOLVED");
        m.put("category", cat);
        return m;
    }

    @Test
    @DisplayName("create: zorunlu alan eksikse IllegalArgumentException")
    void create_missingRequired_throws() {
        Map<String, Object> b = new HashMap<>();
        b.put("title", "x"); // occurred_at/severity/status/category yok
        assertThatThrownBy(() -> service.create(b, "admin", 1L, 1L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("create: geçersiz severity reddedilir")
    void create_invalidSeverity_throws() {
        Map<String, Object> b = body("t", "2026-06-14T10:00:00", "URGENT", "DATABASE");
        assertThatThrownBy(() -> service.create(b, "admin", 1L, 1L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("create + filtre: severity / keyword / tarih aralığı")
    void create_and_filter() {
        service.create(body("DB pool tükendi", "2026-06-14T10:00:00", "CRITICAL", "DATABASE"), "admin", 1L, 1L);
        service.create(body("DNS gecikmesi", "2026-06-15T09:00:00", "HIGH", "NETWORK"), "admin", 1L, 1L);
        service.create(body("Sertifika uyarısı", "2026-06-16T08:00:00", "MEDIUM", "CERTIFICATE"), "admin", 1L, 1L);

        // severity filtresi
        assertThat(service.list(null, "CRITICAL", null, null, null, null, null, null, null, PageRequest.of(0, 20))
                .getTotalElements()).isEqualTo(1);
        // keyword (LOWER LIKE — başlık)
        assertThat(service.list("pool", null, null, null, null, null, null, null, null, PageRequest.of(0, 20))
                .getTotalElements()).isEqualTo(1);
        // tarih aralığı (ISO string >= / <=)
        assertThat(service.list(null, null, null, null, null, null, "2026-06-15T00:00:00", "2026-06-16T23:59:59", null,
                PageRequest.of(0, 20)).getTotalElements()).isEqualTo(2);
        // sla_breached = false (varsayılan) → hepsi
        assertThat(service.list(null, null, null, null, null, null, null, null, Boolean.FALSE, PageRequest.of(0, 20))
                .getTotalElements()).isEqualTo(3);
    }

    @Test
    @DisplayName("kanal: filtre + options union (kayıtlı + kullanılan) + addOption")
    void channel_filter_and_options() {
        Map<String, Object> b1 = body("Bireysel giriş hatası", "2026-06-14T10:00:00", "HIGH", "APPLICATION");
        b1.put("channel", "Bireysel İnternet Şubesi");
        b1.put("service", "ib-bireysel");
        service.create(b1, "admin", 1L, 1L);
        Map<String, Object> b2 = body("IVR menü", "2026-06-15T09:00:00", "LOW", "APPLICATION");
        b2.put("channel", "IVR");
        service.create(b2, "admin", 1L, 1L);

        // kanal filtresi
        assertThat(service.list(null, null, null, null, null, "IVR", null, null, null, PageRequest.of(0, 20))
                .getTotalElements()).isEqualTo(1);

        // addOption + listOptions union: eklenen + olaylarda kullanılan kanallar
        service.addOption("CHANNEL", "ATM", "admin");
        var channels = service.listOptions("CHANNEL");
        assertThat(channels).contains("ATM", "IVR", "Bireysel İnternet Şubesi");

        // DOMAIN union: kullanılan service değerleri
        assertThat(service.listOptions("DOMAIN")).contains("ib-bireysel");

        // trend by_channel
        @SuppressWarnings("unchecked")
        var byCh = (Map<String, Long>) service.trends(null, null).get("by_channel");
        assertThat(byCh.get("IVR")).isEqualTo(1L);
    }

    @Test
    @DisplayName("addOption: geçersiz tip reddedilir, boş değer reddedilir")
    void addOption_validation() {
        assertThatThrownBy(() -> service.addOption("WAT", "x", "admin"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.addOption("CHANNEL", "  ", "admin"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("trends: günlük seri + severity kırılımı + özet")
    void trends_aggregates() {
        service.create(body("a", "2026-06-14T10:00:00", "CRITICAL", "DATABASE"), "admin", 1L, 1L);
        service.create(body("b", "2026-06-14T12:00:00", "HIGH", "NETWORK"), "admin", 1L, 1L);
        service.create(body("c", "2026-06-15T09:00:00", "CRITICAL", "DATABASE"), "admin", 1L, 1L);

        Map<String, Object> tr = service.trends(null, null);

        @SuppressWarnings("unchecked")
        var daily = (java.util.List<Map<String, Object>>) tr.get("daily");
        assertThat(daily).hasSize(2); // 06-14 ve 06-15

        @SuppressWarnings("unchecked")
        var bySev = (Map<String, Long>) tr.get("by_severity");
        assertThat(bySev.get("CRITICAL")).isEqualTo(2L);
        assertThat(bySev.get("HIGH")).isEqualTo(1L);

        @SuppressWarnings("unchecked")
        var summary = (Map<String, Object>) tr.get("summary");
        assertThat(((Number) summary.get("total")).longValue()).isEqualTo(3L);
        assertThat(((Number) summary.get("critical")).longValue()).isEqualTo(2L);
    }

    @Test
    @DisplayName("update: kısmi alan günceller, geçersiz status reddeder")
    void update_partial() {
        IncidentRecord e = service.create(body("a", "2026-06-14T10:00:00", "LOW", "OTHER"), "admin", 1L, 1L);
        Map<String, Object> upd = new HashMap<>();
        upd.put("status", "MITIGATED");
        upd.put("rca_summary", "Bağlantı havuzu büyütüldü");
        IncidentRecord saved = service.update(e.getId(), upd, "sre1");
        assertThat(saved.getStatus()).isEqualTo("MITIGATED");
        assertThat(saved.getRcaSummary()).isEqualTo("Bağlantı havuzu büyütüldü");
        assertThat(saved.getSeverity()).isEqualTo("LOW"); // dokunulmadı

        Map<String, Object> bad = new HashMap<>();
        bad.put("status", "WAT");
        assertThatThrownBy(() -> service.update(e.getId(), bad, "sre1"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
