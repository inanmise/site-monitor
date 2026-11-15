package com.sitemonitor.model;

import com.sitemonitor.controller.AdminController;
import com.sitemonitor.repository.EscalationContactRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Eskalasyon kişisinin tekil düzenlemesi eşzamanlı TOPLU yazımı geri almaz (kapı incelemesi 2026-11-15). GERÇEK H2 (PostgreSQL
 * kipi), test işlemi YOK ({@code NOT_SUPPORTED}) — üretimdeki gibi her yazım kendi işleminde.
 *
 * <p>Senaryo: tekil düzenleme kişiyi okur → başka bir istek (başka pod) toplu işlemle kişiyi pasife alır / seviyesini değiştirir /
 * takımını taşır ({@code EscalationContactRepository.bulkSet*}, hedefli UPDATE, işlenmiş) → düzenleme yalnız ADI değiştirip
 * kaydeder → toplu değer DURUR. Düzenleme {@code AdminController.updateContact}'ın yaptığı gibi TEK işlemde oku-yaz yapar;
 * {@code @DynamicUpdate} yalnız değişen kolonu yazar. {@link #control_detachedMergeRevertsBulkWrite} eski yolu (işlem dışı
 * okunan ayrık varlığın {@code save}'i) gösterir: merge satırın tüm alanlarını eski değerlerle yazar.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:contactconcurrent;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.driver-class-name=org.h2.Driver"})
class EscalationContactConcurrentWriteTest {

    @Autowired EscalationContactRepository repo;
    @Autowired DataSource dataSource;
    @Autowired PlatformTransactionManager txManager;

    Long id;

    @BeforeEach
    void setUp() {
        repo.deleteAll();
        EscalationContact c = new EscalationContact();
        c.setName("Ayşe");
        c.setEmail("ayse@example.com");
        c.setRole("TECH");
        c.setMinAlertLevel("WARNING");
        c.setTeamId(10L);
        c.setActive(true);
        id = repo.save(c).getId();
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(txManager);
    }

    /** Başka istek: kendi (yeni) işleminde hedefli toplu yazım — çağıranın işlemi askıya alınır. */
    private void otherRequest(Runnable r) {
        TransactionTemplate t = new TransactionTemplate(txManager);
        t.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        t.executeWithoutResult(s -> r.run());
    }

    private Map<String, Object> row() {
        return new JdbcTemplate(dataSource).queryForMap(
                "SELECT name, active, min_alert_level, team_id FROM escalation_contacts WHERE id = ?", id);
    }

    @Test
    @DisplayName("tek işlemde oku-yaz: toplu pasifleştirme, seviye ve takım değişikliği adı düzenlemesinden sonra da durur")
    void editInOneTransaction_keepsConcurrentBulkWrites() {
        tx().executeWithoutResult(s -> {
            EscalationContact c = repo.findById(id).orElseThrow();
            otherRequest(() -> {
                assertThat(repo.bulkSetActive(id, 10L, false)).isEqualTo(1);
                assertThat(repo.bulkSetLevel(id, 10L, "CRITICAL")).isEqualTo(1);
            });
            c.setName("Ayşe Yılmaz");
            repo.save(c);
        });
        Map<String, Object> r = row();
        assertThat(r.get("name")).isEqualTo("Ayşe Yılmaz");
        assertThat(r.get("active")).isEqualTo(false);
        assertThat(r.get("min_alert_level")).isEqualTo("CRITICAL");
        assertThat(((Number) r.get("team_id")).longValue()).isEqualTo(10L);

        tx().executeWithoutResult(s -> {
            EscalationContact c = repo.findById(id).orElseThrow();
            otherRequest(() -> assertThat(repo.bulkMoveTeam(id, 10L, 20L)).isEqualTo(1));
            c.setEmail("ayse.yilmaz@example.com");
            repo.save(c);
        });
        r = row();
        assertThat(((Number) r.get("team_id")).longValue()).isEqualTo(20L);
        assertThat(r.get("active")).isEqualTo(false);
    }

    @Test
    @DisplayName("kontrol: işlem dışı okunan ayrık varlığın save'i (eski yol) toplu yazımı geri alıyordu")
    void control_detachedMergeRevertsBulkWrite() {
        EscalationContact detached = repo.findById(id).orElseThrow();
        otherRequest(() -> assertThat(repo.bulkSetActive(id, 10L, false)).isEqualTo(1));
        detached.setName("Ayşe Yılmaz");
        repo.save(detached);
        assertThat(row().get("active")).as("merge eski değeri yazar — bu yüzden düzenleme tek işlemde yapılır").isEqualTo(true);
    }

    @Test
    @DisplayName("updateContact tek işlemde koşar ve varlık yalnız değişen kolonları yazar")
    void updateContactIsTransactionalAndEntityDynamicUpdate() throws Exception {
        var m = AdminController.class.getMethod("updateContact", Long.class, Map.class, jakarta.servlet.http.HttpSession.class);
        assertThat(m.getReturnType()).isEqualTo(ResponseEntity.class);
        assertThat(m.isAnnotationPresent(Transactional.class)).isTrue();
        assertThat(EscalationContact.class.isAnnotationPresent(org.hibernate.annotations.DynamicUpdate.class)).isTrue();
    }
}
