package com.sitemonitor.repository;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.PortMonitor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Zamanlanmış işlerin hafif okumaları (2026-11-06, performans PF3 #2 / #3 / #4) — gerçek SQL (H2). Tam varlık okuyup Java'da
 * süzen eski yolların SONUÇ KÜMESİYLE birebir aynı olduklarını sabitler. {@code NOT_SUPPORTED}: @DataJpaTest'in test başına
 * işlemi kapalı — hedefli {@code updateDomainExpiry} üretimdeki gibi çağıransız (kendi işlemiyle) koşar.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PerfScheduledProjectionQueriesTest {

    @Autowired CertificateInventoryRepository inventory;
    @Autowired AlertEventRepository alerts;
    @Autowired PortMonitorRepository ports;

    @BeforeEach
    void clean() {
        alerts.deleteAll();
        ports.deleteAll();
        inventory.deleteAll();
    }

    private CertificateInventory inv(String domain, boolean active, String source, String deletedAt) {
        CertificateInventory i = new CertificateInventory();
        i.setDomain(domain);
        i.setPort(443);
        i.setActive(active);
        i.setCertSource(source);
        i.setDeletedAt(deletedAt);
        return inventory.save(i);
    }

    @Test
    @DisplayName("PF3 #2: findActiveDomains = eski Java süzgeci (findByActiveTrueOrderByDomainAsc → !isManual → domain)")
    void activeDomainsEqualOldJavaFilter() {
        inv("a.example.test", true, null, null);
        inv("b.example.test", true, "", null);                                          // boş kaynak = ağ kaydı
        inv("manuel-anahtar", true, CertificateInventory.SOURCE_MANUAL, null);
        inv("c.example.test", false, null, null);                                       // pasif

        Set<String> old = inventory.findByActiveTrueOrderByDomainAsc().stream()
                .filter(i -> !i.isManual()).map(CertificateInventory::getDomain).collect(Collectors.toSet());
        assertThat(new HashSet<>(inventory.findActiveDomains())).isEqualTo(old)
                .containsExactlyInAnyOrder("a.example.test", "b.example.test");
    }

    @Test
    @DisplayName("PF3 #4: findDomainExpiryRows yalnız aktif + silinmemiş + ağ kaydı, alan adı sırasıyla [id, domain, expiry, checkedAt]")
    void domainExpiryRowsFilterAndOrder() {
        CertificateInventory b = inv("b.example.test", true, null, null);
        b.setDomainExpiry("2027-01-01");
        b.setDomainExpiryCheckedAt("2026-11-01T10:00:00");
        b = inventory.save(b);
        CertificateInventory a = inv("a.example.test", true, null, null);
        inv("silinmis.example.test", true, null, "2026-10-01T00:00:00");
        inv("manuel-anahtar", true, CertificateInventory.SOURCE_MANUAL, null);
        inv("pasif.example.test", false, null, null);

        List<Object[]> rows = inventory.findDomainExpiryRows();
        assertThat(rows).extracting(r -> (String) r[1]).containsExactly("a.example.test", "b.example.test");
        assertThat(((Number) rows.get(0)[0]).longValue()).isEqualTo(a.getId());
        assertThat(rows.get(1)[2]).isEqualTo("2027-01-01");
        assertThat(rows.get(1)[3]).isEqualTo("2026-11-01T10:00:00");
        assertThat(((Number) rows.get(1)[0]).longValue()).isEqualTo(b.getId());
    }

    @Test
    @DisplayName("PF3 #4: updateDomainExpiry çağıran işlemi olmadan yazar ve YALNIZ üç kolonu değiştirir (eşzamanlı düzenleme korunur)")
    void updateDomainExpiryTouchesOnlyItsColumns() {
        CertificateInventory a = inv("a.example.test", true, null, null);
        CertificateInventory other = inv("b.example.test", true, null, null);
        // Eşzamanlı bir yönetici düzenlemesi (tazeleme okuması ile yazımı arasında): grup adı + takım değişti.
        CertificateInventory edited = inventory.findById(a.getId()).orElseThrow();
        edited.setGroupName("Yeni grup");
        edited.setTeamId(42L);
        inventory.save(edited);

        int n = inventory.updateDomainExpiry(List.of(a.getId()), "2027-08-06", "GoDaddy", "2026-11-06T09:00:00");

        assertThat(n).isEqualTo(1);
        CertificateInventory after = inventory.findById(a.getId()).orElseThrow();
        assertThat(after.getDomainExpiry()).isEqualTo("2027-08-06");
        assertThat(after.getDomainRegistrar()).isEqualTo("GoDaddy");
        assertThat(after.getDomainExpiryCheckedAt()).isEqualTo("2026-11-06T09:00:00");
        assertThat(after.getGroupName()).isEqualTo("Yeni grup");    // bayat tam varlık kaydı bunu geri yazardı
        assertThat(after.getTeamId()).isEqualTo(42L);
        assertThat(inventory.findById(other.getId()).orElseThrow().getDomainExpiry()).isNull();

        // Tur sırasında duraklatılan satıra yazılmaz (son inceleme 2026-11-06 — eski yol her yazımdan önce etkin envanteri okurdu)
        CertificateInventory paused = inventory.findById(other.getId()).orElseThrow();
        paused.setActive(false);
        inventory.save(paused);
        assertThat(inventory.updateDomainExpiry(List.of(other.getId()), "2027-09-01", "X", "2026-11-06T09:05:00")).isZero();
        assertThat(inventory.findById(other.getId()).orElseThrow().getDomainExpiry()).isNull();
    }

    private AlertEvent alert(String domain, String type, boolean resolved) {
        AlertEvent e = new AlertEvent();
        e.setDomain(domain);
        e.setAlertType(type);
        e.setAlertLevel("CRITICAL");
        e.setAcknowledged(false);
        e.setResolved(resolved);
        e.setCreatedAt("2026-11-06T08:00:00");
        return alerts.save(e);
    }

    @Test
    @DisplayName("PF3 #3: findOpenDomainsByAlertTypes = eski süzgeç (açık + tür kümesinde + alan dolu), tekil")
    void openDomainsByTypesEqualOldFilter() {
        alert("h1", "PORT_DOWN", false);
        alert("h1", "PORT_SLOW", false);        // aynı alan iki tür → tek kez
        alert("h2", "PORT_SLOW", false);
        alert("h3", "PORT_DOWN", true);         // çözülmüş
        alert("h4", "PING_DOWN", false);        // başka tür
        Set<String> types = Set.of("PORT_DOWN", "PORT_SLOW");

        Set<String> old = new HashSet<>();
        for (AlertEvent e : alerts.findAllOpenOrderBySeverity()) {
            if (types.contains(e.getAlertType()) && e.getDomain() != null) old.add(e.getDomain());
        }
        List<String> now = alerts.findOpenDomainsByAlertTypes(types);
        assertThat(now).doesNotHaveDuplicates();
        assertThat(new HashSet<>(now)).isEqualTo(old).containsExactlyInAnyOrder("h1", "h2");
    }

    private PortMonitor port(String host, int p, boolean active, String deletedAt) {
        PortMonitor m = new PortMonitor();
        m.setName(host);
        m.setHost(host);
        m.setPort(p);
        m.setActive(active);
        m.setDeletedAt(deletedAt);
        return ports.save(m);
    }

    @Test
    @DisplayName("PF3 #3: PortMonitor.findOrphanCheckKeys = eski süzgeç (TÜM satırlar, silinmiş hariç, host tekil) — pasif dahil")
    void portOrphanKeysEqualOldFilter() {
        port("h1", 443, true, null);
        port("h1", 8443, true, null);           // aynı host iki port → tek kez
        port("h2", 22, false, null);            // pasif → hâlâ "izleniyor"
        port("silinen", 80, false, "2026-10-01T00:00:00");

        Set<String> old = ports.findAll().stream().filter(m -> m.getDeletedAt() == null)
                .map(PortMonitor::getHost).filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        List<String> now = ports.findOrphanCheckKeys();
        assertThat(now).doesNotHaveDuplicates();
        assertThat(new HashSet<>(now)).isEqualTo(old).containsExactlyInAnyOrder("h1", "h2");
    }
}
