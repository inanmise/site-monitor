package com.sitemonitor.repository;

import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.LatestCheck;
import com.sitemonitor.model.ManualCertificateVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Elle yüklenen sertifika sorguları (2026-10-06, H2) — JPQL/türetilmiş metot adları gerçekten çalıştırılır; silme yolu
 * dışarıda işlem YOKKEN de çalışır (RepositoryWriteTransactionGuardTest'in davranış eşi).
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class ManualCertificateRepositoriesTest {

    @Autowired ManualCertificateVersionRepository versionRepo;
    @Autowired CertificateInventoryRepository inventoryRepo;
    @Autowired LatestCheckRepository latestRepo;

    private CertificateInventory inv(String domain, String source, boolean active, String deletedAt) {
        CertificateInventory i = new CertificateInventory();
        i.setDomain(domain);
        i.setPort(443);
        i.setActive(active);
        i.setCertSource(source);
        i.setDeletedAt(deletedAt);
        i.setTeamId(1L);
        return inventoryRepo.save(i);
    }

    private ManualCertificateVersion version(Long invId, int v, boolean current, String fp, String dn) {
        ManualCertificateVersion mv = new ManualCertificateVersion();
        mv.setInventoryId(invId);
        mv.setVersion(v);
        mv.setCurrent(current);
        mv.setFingerprint(fp);
        mv.setSubjectDn(dn);
        mv.setChainPem("-----BEGIN CERTIFICATE-----\nAA==\n-----END CERTIFICATE-----\n");
        mv.setUploadedAt("2026-10-0" + v + "T10:00:00");
        return versionRepo.save(mv);
    }

    @Test
    @DisplayName("geçerli sürüm, sürüm listesi (yeniden eskiye), en büyük numara ve gruplu sayım")
    void versionQueries() {
        Long a = inv("a-takip", "MANUAL", true, null).getId();
        Long b = inv("b-takip", "MANUAL", true, null).getId();
        version(a, 1, false, "F1", "CN=a");
        version(a, 2, true, "F2", "CN=a");
        version(b, 1, true, "F3", "CN=b");

        assertThat(versionRepo.findFirstByInventoryIdAndCurrentTrueOrderByVersionDesc(a)).get()
                .extracting(ManualCertificateVersion::getVersion).isEqualTo(2);
        assertThat(versionRepo.findByInventoryIdOrderByVersionDesc(a)).extracting(ManualCertificateVersion::getVersion)
                .containsExactly(2, 1);
        assertThat(versionRepo.findMaxVersion(a)).isEqualTo(2);
        assertThat(versionRepo.findMaxVersion(999L)).isNull();
        assertThat(versionRepo.findByInventoryIdInAndCurrentTrue(List.of(a, b))).extracting(ManualCertificateVersion::getFingerprint)
                .containsExactlyInAnyOrder("F2", "F3");
        assertThat(versionRepo.findByCurrentTrueAndFingerprintIn(Set.of("F1", "F2"))).extracting(ManualCertificateVersion::getFingerprint)
                .containsExactly("F2");   // eski sürüm "izleniyor" sayılmaz
        assertThat(versionRepo.findByCurrentTrueAndSubjectDnIn(Set.of("CN=a"))).hasSize(1);
        List<Object[]> counts = versionRepo.countByInventoryIds(List.of(a, b));
        assertThat(counts).anySatisfy(r -> {
            assertThat(((Number) r[0]).longValue()).isEqualTo(a);
            assertThat(((Number) r[1]).longValue()).isEqualTo(2L);
        });
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("deleteByInventoryId: dışarıda transaction YOKKEN gerçekten siler (yalnız o kaydın sürümleri)")
    void deleteWithoutAmbientTransaction() {
        Long a = inv("sil-a", "MANUAL", true, null).getId();
        Long b = inv("sil-b", "MANUAL", true, null).getId();
        version(a, 1, false, "S1", "CN=a");
        version(a, 2, true, "S2", "CN=a");
        version(b, 1, true, "S3", "CN=b");
        try {
            assertThat(versionRepo.deleteByInventoryId(a)).isEqualTo(2);
            assertThat(versionRepo.findByInventoryIdOrderByVersionDesc(a)).isEmpty();
            assertThat(versionRepo.findByInventoryIdOrderByVersionDesc(b)).hasSize(1);
        } finally {
            versionRepo.deleteAll();     // NOT_SUPPORTED: yazımlar commit edildi — sınıfı kirletmesin
            inventoryRepo.deleteAll();
        }
    }

    @Test
    @DisplayName("envanter: kaynağa göre listeler; çakışma denetimi harf duyarsız, SİLİNMİŞ satır ad tutmaz (2026-10-07); pano kuralı manuel hariç")
    void inventoryQueries() {
        inv("net.example.test", null, true, null);
        inv("api-takip", "MANUAL", true, null);
        inv("pasif-takip", "MANUAL", false, null);
        inv("silinmis-takip", "MANUAL", false, "2026-10-01T00:00:00");

        assertThat(inventoryRepo.findByCertSourceAndActiveTrueOrderByDomainAsc("MANUAL"))
                .extracting(CertificateInventory::getDomain).containsExactly("api-takip");
        assertThat(inventoryRepo.findByCertSourceAndDeletedAtIsNullOrderByDomainAsc("MANUAL"))
                .extracting(CertificateInventory::getDomain).containsExactly("api-takip", "pasif-takip");
        // Silme KALICI (2026-10-07): eski sürümden kalmış çöp satırı (silinmis-takip) önerilen takip adını tutmaz.
        assertThat(inventoryRepo.findExistingDomainsLower(List.of("api-takip", "silinmis-takip", "yok-takip", "net.example.test")))
                .containsExactlyInAnyOrder("api-takip", "net.example.test");
        // İzleme Panosu envanter-pasif kuralı süpürmeyi yansıtır: manuel kayıt ağ hedefi değil.
        assertThat(inventoryRepo.findActiveDomains()).containsExactly("net.example.test");
        assertThat(inventoryRepo.findByActiveTrueOrderByDomainAsc()).extracting(CertificateInventory::isManual)
                .containsExactly(true, false);   // api-takip, net.example.test — yükleme yerleri kendileri süzer
    }

    @Test
    @DisplayName("latest_checks parmak izi toplu eşleşmesi")
    void latestByFingerprint() {
        LatestCheck lc = new LatestCheck();
        lc.setDomain("net.example.test");
        lc.setFingerprint("ABCD");
        latestRepo.save(lc);
        assertThat(latestRepo.findByFingerprintIn(Set.of("ABCD", "EF"))).extracting(LatestCheck::getDomain)
                .containsExactly("net.example.test");
    }
}
