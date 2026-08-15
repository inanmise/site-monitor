package com.sitemonitor.repository;

import com.sitemonitor.model.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.TestPropertySource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kontrol Geçmişi v2 sorgularının GERÇEK bir veritabanında (H2, PostgreSQL uyumluluk modu)
 * koştuğunu kanıtlar. 2026-08 test-ortamı kaçağı: {@code historyHistogram} JPQL'inde
 * {@code SUBSTRING(...,:len)} SELECT/GROUP BY/ORDER BY'da AYRI placeholder'lara bağlanıyordu;
 * PostgreSQL ifade eşitliğini kanıtlayamayıp <b>42803</b> ("must appear in the GROUP BY clause")
 * atıyor, bu da tüm geçmiş yanıtını düşürüyordu. Controller testleri repo'yu MOCK'ladığı için
 * hiçbir test SQL'i çalıştırmamıştı — bu sınıf o boşluğu kapatır.
 *
 * Yeni bir izleme türü eklenince buraya da bir vaka EKLENMELİDİR.
 */
@DataJpaTest
// replace=NONE: aşağıdaki URL gerçekten kullanılsın (varsayılanda DataJpaTest datasource'u kendi
// gömülü H2'siyle DEĞİŞTİRİR ve URL yok sayılır).
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        // Kendi izole H2 örneği + NON_KEYWORDS=VALUE: onsuz dns_records DDL'i patlıyor ("value"
        // H2'de rezerve kelime) ve DNS sorguları hiç test edilemiyordu.
        "spring.datasource.url=jdbc:h2:mem:historygrammar;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;"
                + "DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=VALUE",
        "spring.datasource.driver-class-name=org.h2.Driver",
})
class HistoryQueryGrammarTest {

    private static final String FROM = "2026-08-01T00:00:00";
    private static final String TO   = "2026-08-01T23:59:59";
    private static final String T10  = "2026-08-01T10:00:00";   // sağlıklı
    private static final String T10b = "2026-08-01T10:30:00";   // HATALI (aynı saat kovası)
    private static final String T11  = "2026-08-01T11:00:00";   // sağlıklı
    private static final int HOUR_BUCKET = 13;                  // "yyyy-MM-ddTHH"

    @Autowired PingCheckRepository pingRepo;
    @Autowired PortCheckRepository portRepo;
    @Autowired KeywordResultRepository keywordRepo;
    @Autowired HttpCheckRepository httpRepo;
    @Autowired PageCheckRepository pageRepo;
    @Autowired ScriptedCheckRepository scriptedRepo;
    @Autowired DomainCheckRepository domainRepo;
    @Autowired DnsRecordRepository dnsRepo;
    @Autowired UptimeCheckRepository uptimeRepo;
    @Autowired CertificateCheckRepository certRepo;

    /** [bucket → (toplam, hata)] — native sorgu Long/BigInteger/BigDecimal dönebilir, Number'a indirger. */
    private static Map<String, long[]> asBuckets(List<Object[]> rows) {
        Map<String, long[]> out = new LinkedHashMap<>();
        for (Object[] r : rows) {
            out.put(String.valueOf(r[0]),
                    new long[]{ ((Number) r[1]).longValue(), r[2] == null ? 0L : ((Number) r[2]).longValue() });
        }
        return out;
    }

    /** Her tür için ortak beklenti: 10. saatte 2 kontrol (1 hatalı), 11. saatte 1 kontrol (0 hatalı). */
    private static void assertTwoHourBuckets(List<Object[]> rows) {
        Map<String, long[]> b = asBuckets(rows);
        assertThat(b.keySet()).containsExactly("2026-08-01T10", "2026-08-01T11");   // ORDER BY bucket
        assertThat(b.get("2026-08-01T10")).containsExactly(2L, 1L);
        assertThat(b.get("2026-08-01T11")).containsExactly(1L, 0L);
    }

    private static <T> List<T> rows(Function<String, T> ok, Function<String, T> fail) {
        return List.of(ok.apply(T10), fail.apply(T10b), ok.apply(T11));
    }

    private static PageRequest firstPage() {
        return PageRequest.of(0, 50, Sort.by(Sort.Direction.DESC, "checkedAt"));
    }

    // ── Ping ────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("ping: histogram + sayfalı aralık + hata filtresi GERÇEK DB'de koşar (42803 regresyon kilidi)")
    void ping() {
        pingRepo.saveAll(rows(
                ts -> { PingCheck c = new PingCheck(); c.setMonitorId(1L); c.setUp(true);  c.setCheckedAt(ts); return c; },
                ts -> { PingCheck c = new PingCheck(); c.setMonitorId(1L); c.setUp(false); c.setCheckedAt(ts); return c; }));

        assertTwoHourBuckets(pingRepo.historyHistogram(1L, FROM, TO, HOUR_BUCKET));
        assertThat(pingRepo.findByMonitorIdAndCheckedAtBetween(1L, FROM, TO, firstPage()).getTotalElements()).isEqualTo(3);
        assertThat(pingRepo.findByMonitorIdAndUpFalseAndCheckedAtBetween(1L, FROM, TO, firstPage()).getTotalElements()).isEqualTo(1);
        assertThat(pingRepo.countByMonitorIdAndCheckedAtBetween(1L, FROM, TO)).isEqualTo(3);
        assertThat(pingRepo.countByMonitorIdAndUpFalseAndCheckedAtBetween(1L, FROM, TO)).isEqualTo(1);
    }

    // ── Port ────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("port: histogram + sayfalı aralık + hata filtresi")
    void port() {
        portRepo.saveAll(rows(
                ts -> { PortCheck c = new PortCheck(); c.setMonitorId(1L); c.setOpen(true);  c.setCheckedAt(ts); return c; },
                ts -> { PortCheck c = new PortCheck(); c.setMonitorId(1L); c.setOpen(false); c.setCheckedAt(ts); return c; }));

        assertTwoHourBuckets(portRepo.historyHistogram(1L, FROM, TO, HOUR_BUCKET));
        assertThat(portRepo.findByMonitorIdAndOpenFalseAndCheckedAtBetween(1L, FROM, TO, firstPage()).getTotalElements()).isEqualTo(1);
        assertThat(portRepo.countByMonitorIdAndCheckedAtBetween(1L, FROM, TO)).isEqualTo(3);
    }

    // ── Keyword ─────────────────────────────────────────────────────────────
    @Test
    @DisplayName("keyword: histogram + sayfalı aralık + hata filtresi")
    void keyword() {
        keywordRepo.saveAll(rows(
                ts -> { KeywordResult c = new KeywordResult(); c.setMonitorId(1L); c.setOk(true);  c.setCheckedAt(ts); return c; },
                ts -> { KeywordResult c = new KeywordResult(); c.setMonitorId(1L); c.setOk(false); c.setCheckedAt(ts); return c; }));

        assertTwoHourBuckets(keywordRepo.historyHistogram(1L, FROM, TO, HOUR_BUCKET));
        assertThat(keywordRepo.findByMonitorIdAndOkFalseAndCheckedAtBetween(1L, FROM, TO, firstPage()).getTotalElements()).isEqualTo(1);
        assertThat(keywordRepo.countByMonitorIdAndCheckedAtBetween(1L, FROM, TO)).isEqualTo(3);
    }

    // ── HTTP ────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("http: histogram + sayfalı aralık + hata filtresi")
    void http() {
        httpRepo.saveAll(rows(
                ts -> { HttpCheck c = new HttpCheck(); c.setMonitorId(1L); c.setOk(true);  c.setCheckedAt(ts); return c; },
                ts -> { HttpCheck c = new HttpCheck(); c.setMonitorId(1L); c.setOk(false); c.setCheckedAt(ts); return c; }));

        assertTwoHourBuckets(httpRepo.historyHistogram(1L, FROM, TO, HOUR_BUCKET));
        assertThat(httpRepo.findByMonitorIdAndOkFalseAndCheckedAtBetween(1L, FROM, TO, firstPage()).getTotalElements()).isEqualTo(1);
        assertThat(httpRepo.countByMonitorIdAndCheckedAtBetween(1L, FROM, TO)).isEqualTo(3);
    }

    // ── Page ────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("page: histogram + sayfalı aralık + hata filtresi")
    void page() {
        pageRepo.saveAll(rows(
                ts -> { PageCheck c = new PageCheck(); c.setMonitorId(1L); c.setOk(true);  c.setStatus("OK");   c.setCheckedAt(ts); return c; },
                ts -> { PageCheck c = new PageCheck(); c.setMonitorId(1L); c.setOk(false); c.setStatus("DOWN"); c.setCheckedAt(ts); return c; }));

        assertTwoHourBuckets(pageRepo.historyHistogram(1L, FROM, TO, HOUR_BUCKET));
        assertThat(pageRepo.findByMonitorIdAndOkFalseAndCheckedAtBetween(1L, FROM, TO, firstPage()).getTotalElements()).isEqualTo(1);
        assertThat(pageRepo.countByMonitorIdAndCheckedAtBetween(1L, FROM, TO)).isEqualTo(3);
    }

    // ── Scripted ────────────────────────────────────────────────────────────
    @Test
    @DisplayName("scripted: histogram + sayfalı aralık + hata filtresi")
    void scripted() {
        scriptedRepo.saveAll(rows(
                ts -> { ScriptedCheck c = new ScriptedCheck(); c.setMonitorId(1L); c.setOk(true);  c.setStatus("PASS");  c.setCheckedAt(ts); return c; },
                ts -> { ScriptedCheck c = new ScriptedCheck(); c.setMonitorId(1L); c.setOk(false); c.setStatus("ERROR"); c.setCheckedAt(ts); return c; }));

        assertTwoHourBuckets(scriptedRepo.historyHistogram(1L, FROM, TO, HOUR_BUCKET));
        assertThat(scriptedRepo.findByMonitorIdAndOkFalseAndCheckedAtBetween(1L, FROM, TO, firstPage()).getTotalElements()).isEqualTo(1);
        assertThat(scriptedRepo.countByMonitorIdAndCheckedAtBetween(1L, FROM, TO)).isEqualTo(3);
    }

    // ── Domain (hata = status <> 'OK') ──────────────────────────────────────
    @Test
    @DisplayName("domain: histogram + status<>'OK' filtresi (down sayacı DB'den, kesik listeden DEĞİL)")
    void domain() {
        domainRepo.saveAll(rows(
                ts -> { DomainCheck c = new DomainCheck(); c.setMonitorId(1L); c.setStatus("OK");      c.setCheckedAt(ts); return c; },
                ts -> { DomainCheck c = new DomainCheck(); c.setMonitorId(1L); c.setStatus("CRITICAL"); c.setCheckedAt(ts); return c; }));

        assertTwoHourBuckets(domainRepo.historyHistogram(1L, FROM, TO, HOUR_BUCKET));
        assertThat(domainRepo.findByMonitorIdAndStatusNotAndCheckedAtBetween(1L, "OK", FROM, TO, firstPage()).getTotalElements()).isEqualTo(1);
        assertThat(domainRepo.countByMonitorIdAndStatusNotAndCheckedAtBetween(1L, "OK", FROM, TO)).isEqualTo(1);
    }

    // ── DNS (fail boyutu = changed VEYA rotated) ────────────────────────────
    @Test
    @DisplayName("dns: histogram 'değişen' boyutunu sayar + changed/rotated sayfalı sorgusu")
    void dns() {
        dnsRepo.saveAll(rows(
                ts -> { DnsRecord r = new DnsRecord(); r.setMonitorId(1L); r.setRecordType("A"); r.setValue("1.2.3.4");
                        r.setChanged(false); r.setRotated(false); r.setCheckedAt(ts); return r; },
                ts -> { DnsRecord r = new DnsRecord(); r.setMonitorId(1L); r.setRecordType("A"); r.setValue("5.6.7.8");
                        r.setChanged(true);  r.setRotated(false); r.setCheckedAt(ts); return r; }));

        assertTwoHourBuckets(dnsRepo.historyHistogram(1L, FROM, TO, HOUR_BUCKET));
        assertThat(dnsRepo.findChangedByMonitorIdBetween(1L, FROM, TO, firstPage()).getTotalElements()).isEqualTo(1);
        assertThat(dnsRepo.countChangedByMonitorIdBetween(1L, FROM, TO)).isEqualTo(1);
        assertThat(dnsRepo.countByMonitorIdAndCheckedAtBetween(1L, FROM, TO)).isEqualTo(3);
    }

    // ── Uptime (domain+port anahtarlı) ──────────────────────────────────────
    @Test
    @DisplayName("uptime: domain+port anahtarlı histogram + status<>'up' filtresi")
    void uptime() {
        uptimeRepo.saveAll(rows(
                ts -> { UptimeCheck u = new UptimeCheck(); u.setDomain("a.example.com"); u.setPort(443); u.setStatus("up");   u.setCheckedAt(ts); return u; },
                ts -> { UptimeCheck u = new UptimeCheck(); u.setDomain("a.example.com"); u.setPort(443); u.setStatus("down"); u.setCheckedAt(ts); return u; }));

        assertTwoHourBuckets(uptimeRepo.historyHistogram("a.example.com", 443, FROM, TO, HOUR_BUCKET));
        assertThat(uptimeRepo.findByDomainAndPortAndStatusNotAndCheckedAtBetween("a.example.com", 443, "up", FROM, TO, firstPage())
                .getTotalElements()).isEqualTo(1);
        assertThat(uptimeRepo.countByDomainAndPortAndCheckedAtBetween("a.example.com", 443, FROM, TO)).isEqualTo(3);
    }

    // ── Certificate / SSL (domain anahtarlı, hata = status 'error') ─────────
    @Test
    @DisplayName("ssl: domain anahtarlı histogram + status='error' filtresi")
    void certificate() {
        certRepo.saveAll(rows(
                ts -> { CertificateCheck c = new CertificateCheck(); c.setDomain("a.example.com"); c.setStatus("valid"); c.setCheckedAt(ts); return c; },
                ts -> { CertificateCheck c = new CertificateCheck(); c.setDomain("a.example.com"); c.setStatus("error"); c.setCheckedAt(ts); return c; }));

        assertTwoHourBuckets(certRepo.historyHistogram("a.example.com", FROM, TO, HOUR_BUCKET));
        assertThat(certRepo.findByDomainAndStatusAndCheckedAtBetween("a.example.com", "error", FROM, TO, firstPage())
                .getTotalElements()).isEqualTo(1);
        assertThat(certRepo.countByDomainAndCheckedAtBetween("a.example.com", FROM, TO)).isEqualTo(3);

        // Grafik serisi: [checkedAt, responseMs, up, daysRemaining]. Sertifikada doğal bir "up" bool'u
        // olmadığı için CASE WHEN ile türetiliyor — bu sınıf kaçak tam olarak burada yakalanır.
        List<Object[]> series = certRepo.responseSeriesRaw("a.example.com", FROM, TO, 100);
        assertThat(series).hasSize(3);
        assertThat(series.get(0)).hasSize(4);
        // 3 kayıttan 1'i status='error' → türetilmiş up=false olan tam 1 satır olmalı.
        assertThat(series.stream().filter(r -> Boolean.FALSE.equals(r[2])).count()).isEqualTo(1);
    }

    // ── Kova genişliği: gün(10) / dakika(16) prefix'i de aynı SQL ile çalışır ──
    @Test
    @DisplayName("kova genişliği parametresi: gün(10) tek kovaya, dakika(16) ayrı kovalara böler")
    void bucketWidths() {
        pingRepo.saveAll(rows(
                ts -> { PingCheck c = new PingCheck(); c.setMonitorId(9L); c.setUp(true);  c.setCheckedAt(ts); return c; },
                ts -> { PingCheck c = new PingCheck(); c.setMonitorId(9L); c.setUp(false); c.setCheckedAt(ts); return c; }));

        Map<String, long[]> daily = asBuckets(pingRepo.historyHistogram(9L, FROM, TO, 10));
        assertThat(daily.keySet()).containsExactly("2026-08-01");
        assertThat(daily.get("2026-08-01")).containsExactly(3L, 1L);

        Map<String, long[]> minutely = asBuckets(pingRepo.historyHistogram(9L, FROM, TO, 16));
        assertThat(minutely.keySet()).containsExactly("2026-08-01T10:00", "2026-08-01T10:30", "2026-08-01T11:00");
    }
}
