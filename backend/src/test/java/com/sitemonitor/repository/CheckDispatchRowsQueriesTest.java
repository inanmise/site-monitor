package com.sitemonitor.repository;

import com.sitemonitor.model.DnsMonitor;
import com.sitemonitor.model.HttpMonitor;
import com.sitemonitor.model.KeywordMonitor;
import com.sitemonitor.model.PageMonitor;
import com.sitemonitor.model.PageSpeedMonitor;
import com.sitemonitor.model.PingMonitor;
import com.sitemonitor.model.PortMonitor;
import com.sitemonitor.model.ScriptedMonitor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Çok pod Sürüm 3-3 paket B — dağıtık kontrol gönderiminin İZDÜŞÜM sorguları ({@code findCheckDispatchRows}) gerçek JPQL ile, H2
 * üstünde: yalnız AKTİF izlemeler; Port / DNS satırı {@code [id, aralık, bağımsız, host / alan adı]}, diğerleri {@code [id, aralık]}
 * (sayfa hızında HAM aralık — kırpma gönderici tarafında).
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class CheckDispatchRowsQueriesTest {

    @Autowired PortMonitorRepository portRepo;
    @Autowired DnsMonitorRepository dnsRepo;
    @Autowired PingMonitorRepository pingRepo;
    @Autowired HttpMonitorRepository httpRepo;
    @Autowired KeywordMonitorRepository keywordRepo;
    @Autowired PageMonitorRepository pageRepo;
    @Autowired PageSpeedMonitorRepository pageSpeedRepo;
    @Autowired ScriptedMonitorRepository scriptedRepo;

    private static Object[] only(List<Object[]> rows) {
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    @Test
    @DisplayName("port / dns: aktif satırlar [id, aralık, bağımsız, hedef]; pasif satır gelmez")
    void portAndDns() {
        PortMonitor p = new PortMonitor();
        p.setName("p");
        p.setHost("a.example.com");
        p.setPort(443);
        p.setIntervalSeconds(45);
        p.setStandalone(true);
        p = portRepo.save(p);
        PortMonitor paused = new PortMonitor();
        paused.setName("q");
        paused.setHost("b.example.com");
        paused.setPort(22);
        paused.setActive(false);
        portRepo.save(paused);
        Object[] row = only(portRepo.findCheckDispatchRows());
        assertThat(((Number) row[0]).longValue()).isEqualTo(p.getId());
        assertThat(((Number) row[1]).intValue()).isEqualTo(45);
        assertThat(row[2]).isEqualTo(Boolean.TRUE);
        assertThat(row[3]).isEqualTo("a.example.com");

        DnsMonitor d = new DnsMonitor();
        d.setName("d");
        d.setDomain("x.example.com");
        d.setRecordType("A");
        d.setIntervalSeconds(300);
        d = dnsRepo.save(d);
        DnsMonitor dPaused = new DnsMonitor();
        dPaused.setName("e");
        dPaused.setDomain("y.example.com");
        dPaused.setRecordType("MX");
        dPaused.setActive(false);
        dnsRepo.save(dPaused);
        Object[] drow = only(dnsRepo.findCheckDispatchRows());
        assertThat(((Number) drow[0]).longValue()).isEqualTo(d.getId());
        assertThat(drow[2]).isEqualTo(Boolean.FALSE);
        assertThat(drow[3]).isEqualTo("x.example.com");
    }

    @Test
    @DisplayName("ping / http / keyword / sayfa / sayfa hızı / k6: aktif satırlar [id, aralık]")
    void simpleTypes() {
        PingMonitor ping = new PingMonitor();
        ping.setName("ping");
        ping.setHost("10.0.0.1");
        ping.setIntervalSeconds(30);
        ping = pingRepo.save(ping);
        HttpMonitor http = new HttpMonitor();
        http.setName("http");
        http.setUrl("https://a.example.com/");
        http.setIntervalSeconds(60);
        http = httpRepo.save(http);
        KeywordMonitor kw = new KeywordMonitor();
        kw.setName("kw");
        kw.setUrl("https://a.example.com/");
        kw.setKeyword("x");
        kw.setIntervalSeconds(90);
        kw = keywordRepo.save(kw);
        PageMonitor page = new PageMonitor();
        page.setName("page");
        page.setUrl("https://a.example.com/");
        page.setIntervalSeconds(120);
        page = pageRepo.save(page);
        PageSpeedMonitor speed = new PageSpeedMonitor();
        speed.setName("speed");
        speed.setUrl("https://a.example.com/");
        speed.setIntervalSeconds(60);
        speed = pageSpeedRepo.save(speed);
        ScriptedMonitor k6 = new ScriptedMonitor();
        k6.setName("k6");
        k6.setIntervalSeconds(300);
        k6 = scriptedRepo.save(k6);
        ScriptedMonitor k6Paused = new ScriptedMonitor();
        k6Paused.setName("k6-paused");
        k6Paused.setActive(false);
        scriptedRepo.save(k6Paused);

        assertThat(((Number) only(pingRepo.findCheckDispatchRows())[0]).longValue()).isEqualTo(ping.getId());
        assertThat(((Number) only(httpRepo.findCheckDispatchRows())[1]).intValue()).isEqualTo(60);
        assertThat(((Number) only(keywordRepo.findCheckDispatchRows())[1]).intValue()).isEqualTo(90);
        assertThat(((Number) only(pageRepo.findCheckDispatchRows())[0]).longValue()).isEqualTo(page.getId());
        Object[] s = only(pageSpeedRepo.findCheckDispatchRows());
        assertThat(((Number) s[1]).intValue()).as("ham aralık (kırpma gönderici tarafında)").isEqualTo(60);
        assertThat(((Number) only(scriptedRepo.findCheckDispatchRows())[0]).longValue()).isEqualTo(k6.getId());
        assertThat(http.getId()).isNotNull();
        assertThat(kw.getId()).isNotNull();
        assertThat(speed.getId()).isNotNull();
    }
}
