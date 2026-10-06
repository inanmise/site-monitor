package com.sitemonitor.repository;

import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.service.StormService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fırtına paydası dosyadan yüklenen sertifikaları saymaz (2026-10-06): {@code countNetworkActive} /
 * {@code countNetworkActiveByTeam} ve {@link StormService#SQL_ACTIVE_BY_TEAM} gerçek H2 üstünde. Manuel satır yokken
 * sayılar eski {@code countByActiveTrue} / {@code countByTeamIdAndActiveTrue} ile birebir aynıdır.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:invnetcount;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=VALUE",
        "spring.datasource.driver-class-name=org.h2.Driver"})
class InventoryNetworkCountQueriesTest {

    @Autowired CertificateInventoryRepository inventory;
    @Autowired DataSource dataSource;

    private CertificateInventory row(String domain, Long teamId, boolean active, String source) {
        CertificateInventory i = new CertificateInventory();
        i.setDomain(domain);
        i.setPort(443);
        i.setActive(active);
        i.setTeamId(teamId);
        i.setCertSource(source);
        return inventory.save(i);
    }

    @Test
    @DisplayName("Ağ satırları sayılır; MANUAL ve pasif satırlar sayılmaz — takım kapsamı ve tek sorgu da aynı kuralda")
    void manualRowsExcludedFromStormDenominator() {
        row("a.example.test", 7L, true, null);
        row("b.example.test", 7L, true, null);
        row("c.example.test", 8L, true, null);
        row("client-cert-manuel", 7L, true, CertificateInventory.SOURCE_MANUAL);
        row("ca-root-manuel", 8L, true, CertificateInventory.SOURCE_MANUAL);
        row("d.example.test", 7L, false, null);   // pasif

        assertThat(inventory.countByActiveTrue()).isEqualTo(5L);          // eski sayım: manueller dahil
        assertThat(inventory.countNetworkActive()).isEqualTo(3L);
        assertThat(inventory.countNetworkActiveByTeam(7L)).isEqualTo(2L);
        assertThat(inventory.countNetworkActiveByTeam(8L)).isEqualTo(1L);

        Map<Long, Long> byTeam = new HashMap<>();
        new JdbcTemplate(dataSource).query(StormService.SQL_ACTIVE_BY_TEAM, rs -> { byTeam.put(rs.getLong(1), rs.getLong(2)); });
        assertThat(byTeam).containsEntry(7L, 2L).containsEntry(8L, 1L);
    }

    @Test
    @DisplayName("Manuel satır yokken yeni sayımlar eskileriyle aynı (mevcut fırtına davranışı değişmez)")
    void withoutManualRows_sameAsLegacyCounts() {
        row("e.example.test", 9L, true, null);
        row("f.example.test", 9L, true, null);
        row("g.example.test", null, true, null);

        assertThat(inventory.countNetworkActive()).isEqualTo(inventory.countByActiveTrue());
        assertThat(inventory.countNetworkActiveByTeam(9L)).isEqualTo(inventory.countByTeamIdAndActiveTrue(9L));
    }
}
