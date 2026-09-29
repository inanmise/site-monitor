package com.sitemonitor.repository;

import com.sitemonitor.model.DnsRecord;
import com.sitemonitor.model.ScriptedCheck;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ELLE KONTROL İŞARETİ — zamanlanmış kararların kaynağı olan iki sorgu (H2, gerçek JPQL; 2026-09-29).
 *
 * <p>Ürün kuralı: elle kontrol ("Şimdi kontrol et") yalnız gözlemdir. Sonucu kaydedilir ve geçmişte görünür, ama
 * zamanlanmış hattın iki kararına KARIŞMAZ:
 * <ul>
 *   <li>DNS değişiklik TABANI — elle kayıt yeni değeri yazınca sweep değişikliği "zaten bilinen" sayıyor ve
 *       DNS_CHANGED alarmı KALICI olarak yutuluyordu;</li>
 *   <li>sentetik anomali guard'ının ARDIŞIK ZAMAN AŞIMI serisi — toplu elle kontrolde sıkışan k6 havuzunun zaman
 *       aşımları sağlıklı izlemeyi kapatıp takıma KRİTİK bildirim atabiliyordu.</li>
 * </ul>
 * Eski satırlar ({@code manual} NULL) zamanlanmış sayılır.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        // Kendi izole H2 örneği + NON_KEYWORDS=VALUE: onsuz dns_records DDL'i ("value" kolonu) H2'de kurulmaz.
        "spring.datasource.url=jdbc:h2:mem:manualmarker;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=VALUE",
        "spring.datasource.driver-class-name=org.h2.Driver"})
class ManualCheckMarkerQueriesTest {

    @Autowired DnsRecordRepository dnsRepo;
    @Autowired ScriptedCheckRepository scriptedRepo;
    @Autowired DomainCheckRepository domainRepo;

    private DnsRecord dns(long monitorId, String value, Boolean manual, String at) {
        DnsRecord r = new DnsRecord();
        r.setMonitorId(monitorId);
        r.setRecordType("A");
        r.setValue(value);
        r.setManual(manual);
        r.setCheckedAt(at);
        return r;
    }

    private ScriptedCheck run(long monitorId, String status, Boolean manual, String at) {
        ScriptedCheck c = new ScriptedCheck();
        c.setMonitorId(monitorId);
        c.setStatus(status);
        c.setOk("PASS".equals(status));
        c.setManual(manual);
        c.setCheckedAt(at);
        return c;
    }

    @Test
    @DisplayName("DNS tabanı: en yeni ZAMANLANMIŞ başarılı kayıt — elle kayıt (yeni değer) ve başarısız ('' değer) atlanır; NULL işaret zamanlanmıştır")
    void dnsBaseline_skipsManualAndFailedRecords() {
        dnsRepo.save(dns(7L, "5.6.7.8", null, "2026-09-29T08:00:00"));   // eski satır (işaret yok) → zamanlanmış
        dnsRepo.save(dns(7L, "5.6.7.8", false, "2026-09-29T08:05:00"));  // zamanlanmış
        dnsRepo.save(dns(7L, "", false, "2026-09-29T08:10:00"));         // başarısız sorgu
        dnsRepo.save(dns(7L, "1.2.3.4", true, "2026-09-29T08:12:00"));   // ELLE kontrol yeni değeri gördü
        dnsRepo.save(dns(8L, "9.9.9.9", false, "2026-09-29T09:00:00"));  // başka monitör

        assertThat(dnsRepo.findLatestScheduledSuccessful(7L)).get()
                .satisfies(r -> {
                    assertThat(r.getValue()).isEqualTo("5.6.7.8");
                    assertThat(r.getCheckedAt()).isEqualTo("2026-09-29T08:05:00");
                });
        // Elle kaydın görünürlüğü korunur: genel "son başarılı" sorgusu onu döndürür (geçmiş/ekran).
        assertThat(dnsRepo.findTopByMonitorIdAndValueNotOrderByCheckedAtDesc(7L, "")).get()
                .extracting(DnsRecord::getValue).isEqualTo("1.2.3.4");
        assertThat(dnsRepo.findLatestScheduledSuccessful(99L)).isEmpty();
    }

    @Test
    @DisplayName("Anomali serisi: yalnız ZAMANLANMIŞ koşumlar, en yeni önce — araya giren elle zaman aşımları seriyi uzatmaz")
    void scriptedStreak_countsScheduledRunsOnly() {
        scriptedRepo.save(run(5L, "PASS", null, "2026-09-29T08:00:00"));
        scriptedRepo.save(run(5L, "TIMEOUT", false, "2026-09-29T08:05:00"));
        scriptedRepo.save(run(5L, "TIMEOUT", true, "2026-09-29T08:06:00"));   // elle
        scriptedRepo.save(run(5L, "TIMEOUT", true, "2026-09-29T08:07:00"));   // elle
        scriptedRepo.save(run(5L, "TIMEOUT", false, "2026-09-29T08:10:00"));

        assertThat(scriptedRepo.findRecentScheduledByMonitorId(5L, 3))
                .extracting(ScriptedCheck::getCheckedAt)
                .containsExactly("2026-09-29T08:10:00", "2026-09-29T08:05:00", "2026-09-29T08:00:00");
        // Geçmiş (tüm koşumlar) değişmedi: elle koşumlar görünür.
        assertThat(scriptedRepo.findRecentByMonitorId(5L, 5)).hasSize(5);
    }

    private com.sitemonitor.model.DomainCheck domain(long monitorId, String source, String ns, Boolean manual, String at) {
        com.sitemonitor.model.DomainCheck d = new com.sitemonitor.model.DomainCheck();
        d.setMonitorId(monitorId);
        d.setSource(source);
        d.setNameservers(ns);
        d.setManual(manual);
        d.setCheckedAt(at);
        return d;
    }

    @Test
    @DisplayName("K-1: alan adı tabanı en yeni ZAMANLANMIŞ veri satırı — elle / canlı sorgu satırı ve veri yok ('NONE') atlanır; NULL işaret zamanlanmıştır")
    void domainBaseline_skipsManualAndNoDataRows() {
        domainRepo.save(domain(3L, "RDAP", "ns1.example.com", null, "2026-09-29T06:00:00"));
        domainRepo.save(domain(3L, "RDAP", "ns1.example.com", false, "2026-09-29T07:00:00"));
        domainRepo.save(domain(3L, "NONE", null, false, "2026-09-29T08:00:00"));             // sorgu başarısız
        domainRepo.save(domain(3L, "RDAP", "ns9.example.org", true, "2026-09-29T09:00:00"));  // ELLE / Kayıt sekmesi
        domainRepo.save(domain(4L, "RDAP", "ns2.example.com", false, "2026-09-29T10:00:00")); // başka izleme

        assertThat(domainRepo.findLatestScheduledWithData(3L)).get()
                .satisfies(d -> {
                    assertThat(d.getNameservers()).isEqualTo("ns1.example.com");
                    assertThat(d.getCheckedAt()).isEqualTo("2026-09-29T07:00:00");
                });
        // Elle satırın görünürlüğü korunur (gösterim / geçmiş).
        assertThat(domainRepo.findTopByMonitorIdAndSourceNotOrderByCheckedAtDesc(3L, "NONE")).get()
                .extracting(com.sitemonitor.model.DomainCheck::getNameservers).isEqualTo("ns9.example.org");
        assertThat(domainRepo.findLatestScheduledWithData(99L)).isEmpty();
    }
}
