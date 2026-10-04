package com.sitemonitor.controller;

import com.sitemonitor.model.CertificateCheck;
import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.LatestCheck;
import com.sitemonitor.repository.CertificateCheckRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.LatestCheckRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Giriş sayfası kullanım istatistiklerinin SERTİFİKA sorguları (2026-10-05, kullanıcı isteği: "ana sayfadaki koşum
 * sayısına sertifika taramalarını da ekleyelim; sertifika izlemesi yoksa onu da ekleyelim") — gerçek SQL, H2 üstünde:
 * 24 sa sertifika taraması (toplam + hatalı) ve aktif envanterin son durum dağılımı (geçerli / 30 gün / dolmuş).
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class PublicStatsCertQueriesTest {

    @Autowired DataSource dataSource;
    @Autowired CertificateInventoryRepository inventoryRepo;
    @Autowired LatestCheckRepository latestRepo;
    @Autowired CertificateCheckRepository checkRepo;

    private PublicStatsController controller() {
        return new PublicStatsController(new JdbcTemplate(dataSource), null, null, null, null, null, null, null);
    }

    private void inventory(String domain, boolean active) {
        CertificateInventory i = new CertificateInventory();
        i.setDomain(domain);
        i.setPort(443);
        i.setActive(active);
        inventoryRepo.save(i);
    }

    private void latest(String domain, String status, Integer days) {
        LatestCheck l = new LatestCheck();
        l.setDomain(domain);
        l.setStatus(status);
        l.setDaysRemaining(days);
        latestRepo.save(l);
    }

    private void scan(String domain, String status, String at) {
        CertificateCheck c = new CertificateCheck();
        c.setDomain(domain);
        c.setStatus(status);
        c.setCheckedAt(at);
        checkRepo.save(c);
    }

    @Test
    @DisplayName("certScansSince: penceredeki taramalar (sınır dahil) + hatalı olanlar; öncesi sayılmaz")
    void certScans() {
        scan("a.example.com", "valid", "2026-10-04T09:00:00");
        scan("a.example.com", "valid", "2026-10-05T08:00:00");
        scan("b.example.com", "error", "2026-10-04T10:00:00");
        scan("c.example.com", "warning", "2026-10-04T08:00:00");   // tam sınır
        scan("d.example.com", "error", "2026-10-04T07:59:59");     // pencere dışı
        long[] r = controller().certScansSince("2026-10-04T08:00:00");
        assertThat(r[0]).isEqualTo(4L);
        assertThat(r[1]).isEqualTo(1L);
        assertThat(controller().certScansSince("2026-10-06T00:00:00")).containsExactly(0L, 0L);
    }

    @Test
    @DisplayName("certificateCounts: yalnız AKTİF envanter; durum son kontrolden — geçerli / 30 gün içinde / dolmuş; kontrolsüz satır yalnız toplamda")
    void certificateCounts() {
        inventory("ok.example.com", true);        latest("ok.example.com", "valid", 200);
        inventory("soon.example.com", true);      latest("soon.example.com", "warning", 12);
        inventory("edge.example.com", true);      latest("edge.example.com", "valid", 30);     // 30 gün → "30 gün içinde"
        inventory("gone.example.com", true);      latest("gone.example.com", "expired", -3);
        inventory("err.example.com", true);       latest("err.example.com", "error", null);
        inventory("new.example.com", true);                                                     // henüz kontrol yok
        inventory("off.example.com", false);      latest("off.example.com", "valid", 300);     // pasif — sayılmaz

        long[] c = controller().certificateCounts();
        assertThat(c[0]).as("aktif sertifika").isEqualTo(6L);
        assertThat(c[1]).as("geçerli (son durum valid)").isEqualTo(2L);
        assertThat(c[2]).as("30 gün içinde dolacak (0..30)").isEqualTo(2L);
        assertThat(c[3]).as("süresi dolmuş").isEqualTo(1L);
    }

    @Test
    @DisplayName("boş veritabanı: tüm sayılar 0 (null değil)")
    void empty() {
        assertThat(controller().certificateCounts()).containsExactly(0L, 0L, 0L, 0L);
        assertThat(controller().certScansSince("2026-10-04T08:00:00")).containsExactly(0L, 0L);
    }
}
