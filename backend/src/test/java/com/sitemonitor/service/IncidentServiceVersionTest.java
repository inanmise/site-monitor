package com.sitemonitor.service;

import com.sitemonitor.config.GlobalExceptionHandler.CodedConflictException;
import com.sitemonitor.model.IncidentRecord;
import com.sitemonitor.repository.IncidentRecordRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * "Sağlam Kayıt" paketi (2026-11-20) — iyimser kilit GERÇEK işlem sınırlarıyla ({@code NOT_SUPPORTED}: uygulamadaki gibi
 * işlemsiz çağıran, her servis çağrısı kendi işlemini açar ve işler). Sürüm çakışması 409, sürümsüz PUT bugünkü davranış,
 * toplu aktarım sürümü artırır, kolonu bilmeyen eski pod'un INSERT'i 0 alır, bayat ayrık varlık birleştirmesi reddedilir.
 */
@DataJpaTest
@Import(IncidentService.class)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class IncidentServiceVersionTest {

    @Autowired IncidentService service;
    @Autowired IncidentRecordRepository repo;
    @Autowired DataSource dataSource;

    @BeforeEach
    void clean() {
        repo.deleteAll();
    }

    /**
     * Bu sınıf işlemsiz yazar (satırlar KALICI); aynı Spring bağlamını (aynı gömülü H2) paylaşan IncidentServiceTest gibi
     * sınıflar sonradan koşunca bu satırları görür — her testten sonra da temizlenir.
     */
    @AfterEach
    void cleanAfter() {
        repo.deleteAll();
    }

    private IncidentRecord create(String title) {
        Map<String, Object> m = new HashMap<>();
        m.put("title", title);
        m.put("occurred_at", "2026-11-18T10:00:00");
        m.put("severity", "HIGH");
        m.put("status", "OPEN");
        m.put("category", "APPLICATION");
        return service.create(m, "sre1", 1L, 1L);
    }

    private static Map<String, Object> body(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    @Test
    @DisplayName("yeni kayıt sürüm 0; doğru sürümle güncelleme yanıtta YENİ sürümü taşır; eski sürümle ikinci yazım 409 ve hiçbir alan değişmez")
    void staleVersionIsRejected() {
        IncidentRecord e = create("Ödeme");
        assertThat(e.getVersion()).isZero();

        IncidentRecord a = service.update(e.getId(), body("title", "Ayşe'nin başlığı", "version", 0), "ayse");
        assertThat(a.getVersion()).as("yanıt yeni sürümü taşır (saveAndFlush)").isEqualTo(1L);

        assertThatThrownBy(() -> service.update(e.getId(), body("title", "Mehmet'in başlığı", "status", "RESOLVED", "version", 0), "mehmet"))
                .isInstanceOfSatisfying(CodedConflictException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo("CONCURRENT_MODIFICATION");
                    assertThat(ex.getExtra()).containsEntry("current_version", 1L).containsEntry("updated_by", "ayse");
                    assertThat(ex.getExtra().get("updated_at")).isNotNull();
                });
        IncidentRecord after = repo.findById(e.getId()).orElseThrow();
        assertThat(after.getTitle()).isEqualTo("Ayşe'nin başlığı");
        assertThat(after.getStatus()).isEqualTo("OPEN");
        assertThat(after.getVersion()).isEqualTo(1L);
    }

    @Test
    @DisplayName("sürümsüz PUT bugünkü davranış: denetim yok, son yazan kazanır, sürüm yine artar")
    void putWithoutVersionKeepsTodaysBehaviour() {
        IncidentRecord e = create("Ödeme");
        service.update(e.getId(), body("title", "bir", "version", 0), "ayse");
        IncidentRecord b = service.update(e.getId(), body("title", "iki"), "eski-istemci");
        assertThat(b.getTitle()).isEqualTo("iki");
        assertThat(b.getVersion()).isEqualTo(2L);
    }

    @Test
    @DisplayName("toplu takım aktarımı sürümü artırır: o sırada açık düzenleme formu kaydederken 409 alır")
    void transferBumpsVersion() {
        IncidentRecord e = create("Ödeme");
        service.transfer(List.of(e.getId()), 77L, "Kart", "admin");
        assertThat(repo.findById(e.getId()).orElseThrow().getVersion()).isEqualTo(1L);
        assertThatThrownBy(() -> service.update(e.getId(), body("title", "eski formdan", "version", 0), "sre1"))
                .isInstanceOf(CodedConflictException.class);
    }

    @Test
    @DisplayName("kademeli dağıtım: version kolonunu YAZMAYAN eski pod'un INSERT'i 0 alır (NULL değil) ve kayıt normal düzenlenir")
    void oldPodInsertGetsZeroVersion() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("INSERT INTO incident_records (title, occurred_at, severity, status, category, sla_breached) "
                + "VALUES ('Eski pod kaydı', '2026-11-18T10:00:00', 'LOW', 'OPEN', 'OTHER', FALSE)");
        Long id = jdbc.queryForObject("SELECT id FROM incident_records WHERE title = 'Eski pod kaydı'", Long.class);
        assertThat(jdbc.queryForObject("SELECT version FROM incident_records WHERE id = ?", Long.class, id))
                .as("veritabanı varsayılanı").isZero();
        IncidentRecord read = repo.findById(id).orElseThrow();
        assertThat(read.getVersion()).isZero();
        IncidentRecord saved = service.update(id, body("title", "Düzenlendi", "version", 0), "sre1");
        assertThat(saved.getVersion()).isEqualTo(1L);
    }

    @Test
    @DisplayName("eski pod'un UPDATE'i sürümü artırmaz (bilinen artık risk): yeni pod'un açık formu o yazımı göremez, kayıt yine geçer")
    void oldPodUpdateDoesNotBumpVersion() {
        IncidentRecord e = create("Ödeme");
        new JdbcTemplate(dataSource).update("UPDATE incident_records SET title = 'eski pod yazdı' WHERE id = ?", e.getId());
        IncidentRecord saved = service.update(e.getId(), body("status", "MITIGATED", "version", 0), "sre1");
        assertThat(saved.getVersion()).isEqualTo(1L);
        assertThat(saved.getTitle()).isEqualTo("eski pod yazdı");   // gönderilmeyen alan dokunulmadı
    }

    @Test
    @DisplayName("bayat ayrık varlığın tam kaydı (merge) Hibernate sürüm denetimine takılır — sessizce ezmez")
    void staleDetachedMergeFails() {
        IncidentRecord e = create("Ödeme");
        IncidentRecord stale = repo.findById(e.getId()).orElseThrow();
        service.update(e.getId(), body("title", "yeni", "version", 0), "ayse");
        stale.setStatus("RESOLVED");
        assertThatThrownBy(() -> repo.saveAndFlush(stale)).isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(repo.findById(e.getId()).orElseThrow().getStatus()).isEqualTo("OPEN");
    }
}
