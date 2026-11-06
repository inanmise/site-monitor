package com.sitemonitor.repository;

import com.sitemonitor.model.DnsMonitor;
import com.sitemonitor.model.DnsRecord;
import com.sitemonitor.model.DomainCheck;
import com.sitemonitor.model.HttpCheck;
import com.sitemonitor.model.KeywordResult;
import com.sitemonitor.model.PingCheck;
import com.sitemonitor.model.PortCheck;
import com.sitemonitor.model.UptimeCheck;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tek izleme sorgularının IN (:id, :id) biçimi (performans 2026-11-06, PF1 madde 1) SONUCU değiştirmez — H2'de (PostgreSQL modu)
 * gerçek sorgularla: başka izlemenin araya giren satırları dönmez, sıra çağıranın istediği yönde (Kontrol Geçmişi yeniden
 * eskiye, alan adı eğilimi eskiden yeniye), süzgeç / tavan / sayfa / toplam aynı. Plan kanıtı:
 * {@code SingleMonitorQueryPlanPostgresTest}; kaynak kapısı: {@code SingleMonitorQueryGateTest}.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:singlemonitorshape;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;"
                + "DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=VALUE",
        "spring.datasource.driver-class-name=org.h2.Driver",
})
class SingleMonitorQueryShapeH2Test {

    private static final String FROM = "2026-08-01T00:00:00";
    private static final String TO = "2026-08-01T23:59:59";
    /** İzleme 1'in satırları (iki tanesi hatalı) ve araya giren izleme 2 satırları — izleme 2'nin en yenisi en yeni satır. */
    private static final List<String> MINE = List.of("2026-08-01T10:00:00", "2026-08-01T11:00:00", "2026-08-01T12:00:00",
            "2026-08-01T13:00:00");
    private static final List<String> OTHER = List.of("2026-08-01T10:30:00", "2026-08-01T12:30:00", "2026-08-01T23:00:00");
    private static final String OUTSIDE = "2026-07-31T09:00:00";

    @Autowired PortCheckRepository portRepo;
    @Autowired PingCheckRepository pingRepo;
    @Autowired HttpCheckRepository httpRepo;
    @Autowired KeywordResultRepository keywordRepo;
    @Autowired KeywordContentQueryRepository keywordContent;
    @Autowired DnsRecordRepository dnsRepo;
    @Autowired DnsMonitorRepository dnsMonitorRepo;
    @Autowired DomainCheckRepository domainRepo;
    @Autowired UptimeCheckRepository uptimeRepo;

    private static PageRequest newestFirst(int page, int size) {
        return PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "checkedAt"));
    }

    /** İzleme 1: MINE (1. ve 3. hatalı) + aralık dışı eski satır; izleme 2: OTHER. */
    private static <T> List<T> seed(Function<Object[], T> make) {
        java.util.ArrayList<T> out = new java.util.ArrayList<>();
        for (int i = 0; i < MINE.size(); i++) out.add(make.apply(new Object[]{1L, MINE.get(i), i % 2 == 1}));
        out.add(make.apply(new Object[]{1L, OUTSIDE, false}));
        for (String t : OTHER) out.add(make.apply(new Object[]{2L, t, true}));
        return out;
    }

    private static List<String> times(Page<?> page, Function<Object, String> at) {
        return page.getContent().stream().map(at).toList();
    }

    @Test
    @DisplayName("port: sayfalı aralık yeniden eskiye, yalnız bu izleme; hata süzgeci; sayfa 2; findTop; yanıt serisi tavanı")
    void port() {
        portRepo.saveAll(seed(a -> { PortCheck c = new PortCheck(); c.setMonitorId((Long) a[0]); c.setCheckedAt((String) a[1]);
            c.setOpen(!(Boolean) a[2]); c.setResponseMs(10L); return c; }));

        Page<PortCheck> all = portRepo.findByMonitorIdAndCheckedAtBetween(1L, FROM, TO, newestFirst(0, 3));
        assertThat(times(all, o -> ((PortCheck) o).getCheckedAt()))
                .containsExactly("2026-08-01T13:00:00", "2026-08-01T12:00:00", "2026-08-01T11:00:00");
        assertThat(all.getTotalElements()).isEqualTo(4);
        assertThat(times(portRepo.findByMonitorIdAndCheckedAtBetween(1L, FROM, TO, newestFirst(1, 3)), o -> ((PortCheck) o).getCheckedAt()))
                .containsExactly("2026-08-01T10:00:00");
        Page<PortCheck> failed = portRepo.findByMonitorIdAndOpenFalseAndCheckedAtBetween(1L, FROM, TO, newestFirst(0, 1));
        assertThat(times(failed, o -> ((PortCheck) o).getCheckedAt())).containsExactly("2026-08-01T13:00:00");
        assertThat(failed.getTotalElements()).isEqualTo(2);

        assertThat(portRepo.findTopByMonitorIdOrderByCheckedAtDesc(1L)).get().extracting(PortCheck::getCheckedAt)
                .isEqualTo("2026-08-01T13:00:00");
        assertThat(portRepo.findTopByMonitorIdOrderByCheckedAtDesc(99L)).isEmpty();
        assertThat(portRepo.findRecentByMonitorIdSince(1L, "2026-08-01T11:00:00", 10)).extracting(PortCheck::getCheckedAt)
                .containsExactly("2026-08-01T13:00:00", "2026-08-01T12:00:00", "2026-08-01T11:00:00");
        List<Object[]> series = portRepo.responseSeriesRaw(1L, FROM, TO, 2);
        assertThat(series).extracting(r -> r[0]).containsExactly("2026-08-01T13:00:00", "2026-08-01T12:00:00");
    }

    @Test
    @DisplayName("ping / http / keyword: aynı biçim — en yeni satır ve hata sayfası yalnız bu izlemenin")
    void otherTypes() {
        pingRepo.saveAll(seed(a -> { PingCheck c = new PingCheck(); c.setMonitorId((Long) a[0]); c.setCheckedAt((String) a[1]);
            c.setUp(!(Boolean) a[2]); return c; }));
        httpRepo.saveAll(seed(a -> { HttpCheck c = new HttpCheck(); c.setMonitorId((Long) a[0]); c.setCheckedAt((String) a[1]);
            c.setOk(!(Boolean) a[2]); return c; }));
        keywordRepo.saveAll(seed(a -> { KeywordResult c = new KeywordResult(); c.setMonitorId((Long) a[0]); c.setCheckedAt((String) a[1]);
            c.setOk(!(Boolean) a[2]); return c; }));

        assertThat(pingRepo.findTopByMonitorIdOrderByCheckedAtDesc(2L)).get().extracting(PingCheck::getCheckedAt)
                .isEqualTo("2026-08-01T23:00:00");
        assertThat(times(pingRepo.findByMonitorIdAndUpFalseAndCheckedAtBetween(1L, FROM, TO, newestFirst(0, 10)),
                o -> ((PingCheck) o).getCheckedAt())).containsExactly("2026-08-01T13:00:00", "2026-08-01T11:00:00");
        assertThat(times(httpRepo.findByMonitorIdAndOkFalseAndCheckedAtBetween(1L, FROM, TO, newestFirst(0, 10)),
                o -> ((HttpCheck) o).getCheckedAt())).containsExactly("2026-08-01T13:00:00", "2026-08-01T11:00:00");
        assertThat(keywordRepo.responseSeriesRaw(1L, FROM, TO, 10)).extracting(r -> r[0])
                .containsExactly("2026-08-01T13:00:00", "2026-08-01T12:00:00", "2026-08-01T11:00:00", "2026-08-01T10:00:00");
    }

    @Test
    @DisplayName("alan adı eğilimi: artan sıralı sayfa (checkedAt ASC) artan döner; StatusNot süzgeci; findTop…SourceNot")
    void domainAscendingAndStatusNot() {
        domainRepo.saveAll(seed(a -> { DomainCheck c = new DomainCheck(); c.setMonitorId((Long) a[0]); c.setCheckedAt((String) a[1]);
            c.setStatus((Boolean) a[2] ? "CRITICAL" : "OK"); c.setSource((Boolean) a[2] ? "NONE" : "RDAP"); return c; }));

        Page<DomainCheck> asc = domainRepo.findByMonitorIdAndCheckedAtBetween(1L, FROM, TO,
                PageRequest.of(0, 5000, Sort.by("checkedAt").ascending()));
        assertThat(times(asc, o -> ((DomainCheck) o).getCheckedAt())).containsExactlyElementsOf(MINE);
        assertThat(times(domainRepo.findByMonitorIdAndStatusNotAndCheckedAtBetween(1L, "OK", FROM, TO, newestFirst(0, 10)),
                o -> ((DomainCheck) o).getCheckedAt())).containsExactly("2026-08-01T13:00:00", "2026-08-01T11:00:00");
        assertThat(domainRepo.findTopByMonitorIdAndSourceNotOrderByCheckedAtDesc(1L, "NONE")).get()
                .extracting(DomainCheck::getCheckedAt).isEqualTo("2026-08-01T12:00:00");
    }

    @Test
    @DisplayName("dns: değişen / başarısız sayfaları, son başarılı kayıt, son changed kaydı (izleme ve alan adı)")
    void dns() {
        dnsRepo.saveAll(seed(a -> { DnsRecord r = new DnsRecord(); r.setMonitorId((Long) a[0]); r.setCheckedAt((String) a[1]);
            r.setRecordType("A"); boolean bad = (Boolean) a[2];
            r.setValue(bad ? "" : "1.2.3.4"); r.setChanged(bad); r.setRotated(false); return r; }));
        DnsRecord otherChanged = new DnsRecord();
        otherChanged.setMonitorId(3L); otherChanged.setCheckedAt("2026-08-01T22:00:00"); otherChanged.setRecordType("A");
        otherChanged.setValue("9.9.9.9"); otherChanged.setChanged(true); otherChanged.setRotated(false);
        dnsRepo.save(otherChanged);

        assertThat(times(dnsRepo.findChangedByMonitorIdBetween(1L, FROM, TO, newestFirst(0, 10)), o -> ((DnsRecord) o).getCheckedAt()))
                .containsExactly("2026-08-01T13:00:00", "2026-08-01T11:00:00");
        assertThat(dnsRepo.findChangedByMonitorIdBetween(1L, FROM, TO, newestFirst(0, 1)).getTotalElements()).isEqualTo(2);
        assertThat(times(dnsRepo.findFailedByMonitorIdBetween(1L, FROM, TO, newestFirst(0, 10)), o -> ((DnsRecord) o).getCheckedAt()))
                .containsExactly("2026-08-01T13:00:00", "2026-08-01T11:00:00");
        assertThat(dnsRepo.findTopByMonitorIdAndValueNotOrderByCheckedAtDesc(1L, "")).get()
                .extracting(DnsRecord::getCheckedAt).isEqualTo("2026-08-01T12:00:00");
        assertThat(dnsRepo.findLatestScheduledSuccessful(1L)).get().extracting(DnsRecord::getCheckedAt)
                .isEqualTo("2026-08-01T12:00:00");
        assertThat(dnsRepo.findChangedByMonitorId(1L, PageRequest.of(0, 1))).extracting(DnsRecord::getCheckedAt)
                .containsExactly("2026-08-01T13:00:00");
        // izleme 2'nin satırları (OTHER) değişmiş; en yenisi 23:00 — izleme 3'ün 22:00'deki değişikliği karışmaz
        assertThat(dnsRepo.findChangedByMonitorId(2L, PageRequest.of(0, 1))).extracting(DnsRecord::getCheckedAt)
                .containsExactly("2026-08-01T23:00:00");
        assertThat(dnsRepo.findChangedByMonitorId(3L, PageRequest.of(0, 1))).extracting(DnsRecord::getCheckedAt)
                .containsExactly("2026-08-01T22:00:00");
    }

    @Test
    @DisplayName("anahtar kelime içerik sorguları: seri tavanı, son değişim satırı (content_changed dolu), son satır")
    void keywordContent() {
        keywordRepo.saveAll(seed(a -> { KeywordResult c = new KeywordResult(); c.setMonitorId((Long) a[0]); c.setCheckedAt((String) a[1]);
            c.setOk(true); c.setContentChanged("2026-08-01T11:00:00".equals(a[1]) ? Boolean.FALSE : null); return c; }));

        assertThat(keywordContent.contentSeriesRaw(1L, FROM, TO, 2)).extracting(r -> r[0])
                .containsExactly("2026-08-01T13:00:00", "2026-08-01T12:00:00");
        assertThat(keywordContent.latestChangeRow(1L)).extracting(KeywordResult::getCheckedAt).containsExactly("2026-08-01T11:00:00");
        assertThat(keywordContent.latestChangeRow(2L)).isEmpty();
        assertThat(keywordContent.latestRow(1L)).extracting(KeywordResult::getCheckedAt).containsExactly("2026-08-01T13:00:00");
    }

    @Test
    @DisplayName("erişilebilirlik (alan adı + port anahtarı): başka port / alan adının satırları dönmez; status<>'up' süzgeci")
    void uptime() {
        for (String t : MINE) {
            UptimeCheck u = new UptimeCheck(); u.setDomain("a.example.com"); u.setPort(443); u.setCheckedAt(t);
            u.setStatus(t.endsWith("11:00:00") ? "down" : "up"); uptimeRepo.save(u);
        }
        for (Object[] o : new Object[][]{{"a.example.com", 8443}, {"b.example.com", 443}}) {
            UptimeCheck u = new UptimeCheck(); u.setDomain((String) o[0]); u.setPort((Integer) o[1]);
            u.setCheckedAt("2026-08-01T23:00:00"); u.setStatus("down"); uptimeRepo.save(u);
        }

        assertThat(times(uptimeRepo.findByDomainAndPortAndCheckedAtBetween("a.example.com", 443, FROM, TO, newestFirst(0, 2)),
                o -> ((UptimeCheck) o).getCheckedAt())).containsExactly("2026-08-01T13:00:00", "2026-08-01T12:00:00");
        Page<UptimeCheck> down = uptimeRepo.findByDomainAndPortAndStatusNotAndCheckedAtBetween("a.example.com", 443, "up", FROM, TO,
                newestFirst(0, 10));
        assertThat(times(down, o -> ((UptimeCheck) o).getCheckedAt())).containsExactly("2026-08-01T11:00:00");
        assertThat(down.getTotalElements()).isEqualTo(1);
    }

    @Test
    @DisplayName("dns alan adı düzeyi son changed kaydı: izleme başına sıralı okuma + birleştirme = tek sorgunun sonucu (sıra, sayfa)")
    void dnsChangedByDomainMergesMonitors() {
        long a = dnsMonitor("shared.example.com").getId();
        long b = dnsMonitor("shared.example.com").getId();
        long other = dnsMonitor("other.example.com").getId();
        dnsRepo.saveAll(List.of(dns(a, "2026-08-01T10:00:00", true), dns(a, "2026-08-01T11:00:00", false),
                dns(b, "2026-08-01T12:00:00", true), dns(b, "2026-08-01T09:00:00", true),
                dns(other, "2026-08-01T13:00:00", true)));

        assertThat(dnsRepo.findChangedByDomain("shared.example.com", PageRequest.of(0, 1))).extracting(DnsRecord::getCheckedAt)
                .containsExactly("2026-08-01T12:00:00");
        assertThat(dnsRepo.findChangedByDomain("shared.example.com", PageRequest.of(0, 5))).extracting(DnsRecord::getCheckedAt)
                .containsExactly("2026-08-01T12:00:00", "2026-08-01T10:00:00", "2026-08-01T09:00:00");
        assertThat(dnsRepo.findChangedByDomain("shared.example.com", PageRequest.of(1, 1))).extracting(DnsRecord::getCheckedAt)
                .containsExactly("2026-08-01T10:00:00");
        assertThat(dnsRepo.findChangedByDomain("none.example.com", PageRequest.of(0, 1))).isEmpty();
    }

    private DnsMonitor dnsMonitor(String domain) {
        DnsMonitor m = new DnsMonitor();
        m.setName("dns-" + domain + "-" + System.nanoTime());
        m.setDomain(domain);
        m.setRecordType("A");
        m.setActive(true);
        return dnsMonitorRepo.save(m);
    }

    private static DnsRecord dns(long monitorId, String at, boolean changed) {
        DnsRecord r = new DnsRecord();
        r.setMonitorId(monitorId); r.setCheckedAt(at); r.setRecordType("A"); r.setValue("1.2.3.4");
        r.setChanged(changed); r.setRotated(false);
        return r;
    }
}
