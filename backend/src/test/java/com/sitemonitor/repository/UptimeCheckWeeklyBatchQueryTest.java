package com.sitemonitor.repository;

import com.sitemonitor.model.UptimeCheck;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Haftalık rapor TOPLU sorguları (BO5/O15, 2026-09-28) GERÇEK veritabanında (H2, PostgreSQL uyumluluk modu):
 * {@code findWindowForDomains} (ham satırlar, domain → port → zaman sıralı, sınırlar dahil, yalnız istenen domainler)
 * ve {@code countWindowForDomains} ((domain, port) başına toplam/up — bakım HARİÇ, up harf duyarsız). Servis testleri
 * depoyu mock'ladığı için JPQL'in kendisini (IN listesi, GROUP BY, CASE/LOWER) yalnız bu sınıf koşturur.
 * Sabit tarihler yalnız pencere sınırıdır; "şimdi"ye göre kayan bir eşik yok.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:weeklybatch;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=VALUE",
        "spring.datasource.driver-class-name=org.h2.Driver",
})
class UptimeCheckWeeklyBatchQueryTest {

    private static final String FROM = "2026-09-20T21:00:00";
    private static final String TO = "2026-09-27T20:59:59";

    @Autowired UptimeCheckRepository repo;

    private void save(String domain, int port, String status, String at, Boolean maintenance) {
        UptimeCheck c = new UptimeCheck();
        c.setDomain(domain);
        c.setPort(port);
        c.setStatus(status);
        c.setCheckedAt(at);
        c.setMaintenance(maintenance);
        repo.save(c);
    }

    @BeforeEach
    void seed() {
        repo.deleteAll();
        save("a.example.com", 443, "up", "2026-09-20T20:59:59", false);   // pencereden 1 sn önce → DIŞARIDA
        save("a.example.com", 443, "up", FROM, false);                    // alt sınır → İÇERİDE
        save("a.example.com", 443, "down", "2026-09-24T10:00:00", false);
        save("a.example.com", 443, "up", TO, false);                      // üst sınır → İÇERİDE
        save("a.example.com", 443, "up", "2026-09-27T21:00:00", false);   // 1 sn sonra → DIŞARIDA
        save("a.example.com", 8443, "up", "2026-09-23T10:00:00", false);  // aynı domain, başka port → ayrı grup
        save("b.example.com", 443, "up", "2026-09-22T10:00:00", true);    // bakım → sayımda HARİÇ
        save("b.example.com", 443, "UP", "2026-09-22T11:00:00", null);    // bakım bilgisi yok + büyük harf → up sayılır
        save("b.example.com", 443, "down", "2026-09-22T12:00:00", false);
        save("c.example.com", 443, "down", "2026-09-23T10:00:00", false); // istenmeyen domain → hiç dönmez
    }

    @Test
    @DisplayName("findWindowForDomains: yalnız istenen domainler, sınırlar dahil, domain → port → zaman sıralı")
    void windowRowsForRequestedDomains() {
        List<UptimeCheck> rows = repo.findWindowForDomains(List.of("b.example.com", "a.example.com"), FROM, TO);
        assertThat(rows).extracting(u -> u.getDomain() + ":" + u.getPort() + "@" + u.getCheckedAt()).containsExactly(
                "a.example.com:443@" + FROM,
                "a.example.com:443@2026-09-24T10:00:00",
                "a.example.com:443@" + TO,
                "a.example.com:8443@2026-09-23T10:00:00",
                "b.example.com:443@2026-09-22T10:00:00",
                "b.example.com:443@2026-09-22T11:00:00",
                "b.example.com:443@2026-09-22T12:00:00");
    }

    @Test
    @DisplayName("countWindowForDomains: (domain, port) başına [toplam, up]; bakım satırı hariç, 'UP' up sayılır, istenmeyen domain yok")
    void countsPerDomainPortExcludingMaintenance() {
        Map<String, String> got = new TreeMap<>();
        for (Object[] r : repo.countWindowForDomains(List.of("a.example.com", "b.example.com"), FROM, TO)) {
            got.put(r[0] + ":" + r[1], ((Number) r[2]).longValue() + "/" + ((Number) r[3]).longValue());
        }
        assertThat(got).containsExactly(
                Map.entry("a.example.com:443", "3/2"),
                Map.entry("a.example.com:8443", "1/1"),
                Map.entry("b.example.com:443", "2/1"));
    }
}
