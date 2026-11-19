package com.sitemonitor.repository;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.HttpMonitor;
import com.sitemonitor.service.MonitorRefResolver;
import com.sitemonitor.service.MonitorTypeCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * İzleme sayfasının Alarmlar görünümü (2026-11-19) — gerçek JPQL (H2):
 * <ul>
 *   <li>"En çok alarm üreten izlemeler": pencere, tip süzgeci, takım kapsamı (damga ya da envanter SY/UG), adede göre sıra,
 *       açık adet ve temsil (en yeni) kimlik;</li>
 *   <li>{@link MonitorRefResolver#pick}: Olaylar ile ORTAK takım duyarlı seçim — damgalı olay başka takımın aynı URL'li
 *       izlemesini ASLA almaz; bağlamdaki {@code monitor_id} kesin eşleşme; sertifika ailesi çözülmez.</li>
 * </ul>
 */
@DataJpaTest
@Import(MonitorRefResolver.class)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class MonitorTypeAlarmsQueriesTest {

    @Autowired AlertEventRepository repo;
    @Autowired CertificateInventoryRepository invRepo;
    @Autowired HttpMonitorRepository httpRepo;
    @Autowired MonitorRefResolver resolver;

    private AlertEvent alert(String domain, String type, String createdAt, boolean open, Long teamId) {
        AlertEvent e = new AlertEvent();
        e.setDomain(domain);
        e.setAlertType(type);
        e.setAlertLevel("CRITICAL");
        e.setAcknowledged(false);
        e.setResolved(!open);
        e.setResolvedAt(open ? null : "2026-11-18T12:00:00");
        e.setCreatedAt(createdAt);
        e.setTeamId(teamId);
        return repo.save(e);
    }

    private HttpMonitor http(String name, String url, Long teamId) {
        HttpMonitor m = new HttpMonitor();
        m.setName(name);
        m.setUrl(url);
        m.setTeamId(teamId);
        m.setActive(true);
        return httpRepo.save(m);
    }

    @Test
    @DisplayName("topDomainsByAlertCount: pencere + tip süzgeci, adede göre azalan, açık adet ve en yeni kimlik; pencereden önceki ve başka tip sayılmaz")
    void topDomainsWindowTypesAndOrder() {
        String since = "2026-11-12T00:00:00";
        alert("https://a.example.com", "HTTP_DOWN", "2026-11-13T00:00:00", false, 5L);
        alert("https://a.example.com", "HTTP_SLOW", "2026-11-14T00:00:00", false, 5L);
        AlertEvent aLast = alert("https://a.example.com", "HTTP_DOWN", "2026-11-18T00:00:00", true, 5L);
        AlertEvent b = alert("https://b.example.com", "HTTP_DOWN", "2026-11-17T00:00:00", false, 5L);
        alert("https://a.example.com", "HTTP_DOWN", "2026-11-01T00:00:00", false, 5L);       // pencereden önce
        alert("https://b.example.com", "ACCESSIBILITY", "2026-11-17T00:00:00", false, null); // başka tip (sayfa dışı)

        List<Object[]> rows = repo.topDomainsByAlertCount(MonitorTypeCatalog.pageAlertTypes("http"), since, false, List.of(-1L),
                PageRequest.of(0, 15));
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)[0]).isEqualTo("https://a.example.com");
        assertThat(((Number) rows.get(0)[2]).longValue()).isEqualTo(3L);
        assertThat(((Number) rows.get(0)[3]).longValue()).isEqualTo(aLast.getId());
        assertThat(((Number) rows.get(0)[4]).longValue()).isEqualTo(1L);   // açık adet
        assertThat(rows.get(1)[0]).isEqualTo("https://b.example.com");
        assertThat(((Number) rows.get(1)[3]).longValue()).isEqualTo(b.getId());
        // tavan sayfası uygulanır
        assertThat(repo.topDomainsByAlertCount(Set.of("HTTP_DOWN", "HTTP_SLOW"), since, false, List.of(-1L), PageRequest.of(0, 1)))
                .hasSize(1);
    }

    @Test
    @DisplayName("topDomainsByAlertCount takım kapsamı: damgalı takım ya da envanter SY / UG; kapsam dışı takımın alarmı gelmez")
    void topDomainsTeamScope() {
        String since = "2026-11-12T00:00:00";
        alert("https://own.example.com", "HTTP_DOWN", "2026-11-13T00:00:00", true, 2L);
        alert("https://other.example.com", "HTTP_DOWN", "2026-11-13T00:00:00", true, 9L);
        CertificateInventory inv = new CertificateInventory();
        inv.setDomain("ug.example.com");
        inv.setTeamId(9L);
        inv.setUgTeamId(2L);
        invRepo.save(inv);
        alert("ug.example.com", "ACCESSIBILITY", "2026-11-13T00:00:00", true, null);

        List<Object[]> http = repo.topDomainsByAlertCount(Set.of("HTTP_DOWN"), since, true, List.of(2L), PageRequest.of(0, 15));
        assertThat(http).extracting(r -> r[0]).containsExactly("https://own.example.com");
        List<Object[]> uptime = repo.topDomainsByAlertCount(MonitorTypeCatalog.pageAlertTypes("uptime"), since, true, List.of(2L),
                PageRequest.of(0, 15));
        assertThat(uptime).extracting(r -> r[0]).containsExactly("ug.example.com");
    }

    @Test
    @DisplayName("pick: damgalı olay KENDİ takımının izlemesini alır (aynı URL başka takımda da var); bağlamdaki monitor_id kesin; sertifika çözülmez")
    void pickIsTeamAware() {
        HttpMonitor other = http("Başka takımın kopyası", "https://shared.example.com", 9L);
        HttpMonitor own = http("Bizim site", "https://shared.example.com", 2L);
        AlertEvent stamped = alert("https://shared.example.com", "HTTP_DOWN", "2026-11-13T00:00:00", true, 2L);
        AlertEvent foreignStamp = alert("https://shared.example.com", "HTTP_DOWN", "2026-11-13T00:00:00", true, 4L);
        AlertEvent withCtx = alert("https://shared.example.com", "HTTP_DOWN", "2026-11-13T00:00:00", true, 2L);
        withCtx.setContextJson("{\"monitor_id\":" + other.getId() + "}");
        repo.save(withCtx);
        AlertEvent cert = alert("shared.example.com", "EXPIRY", "2026-11-13T00:00:00", true, null);

        Map<Long, MonitorRefResolver.Pick> picks = resolver.pick(List.of(stamped, foreignStamp, withCtx, cert), d -> Set.of());
        assertThat(picks.get(stamped.getId()).monitorId()).isEqualTo(own.getId());
        assertThat(picks.get(stamped.getId()).name()).isEqualTo("Bizim site");
        assertThat(picks.get(stamped.getId()).teamId()).isEqualTo(2L);
        // damgalı takımın izlemesi yok → başka takımınki SEÇİLMEZ: ad = alan adı, kimlik yok
        assertThat(picks.get(foreignStamp.getId()).monitorId()).isNull();
        assertThat(picks.get(foreignStamp.getId()).name()).isEqualTo("https://shared.example.com");
        assertThat(picks.get(withCtx.getId()).monitorId()).isEqualTo(other.getId());
        assertThat(picks.get(cert.getId()).family()).isEqualTo("cert");
        assertThat(picks.get(cert.getId()).monitorId()).isNull();
    }

    @Test
    @DisplayName("contextMonitorId: sayı / sayısal metin; bozuk, boş, yabancı rakamlı ve aşırı uzun değer null")
    void contextMonitorIdRules() {
        assertThat(MonitorRefResolver.contextMonitorId("{\"monitor_id\":42}")).isEqualTo(42L);
        assertThat(MonitorRefResolver.contextMonitorId("{\"monitor_id\":\"17\"}")).isEqualTo(17L);
        assertThat(MonitorRefResolver.contextMonitorId("{\"monitor_id\":\"1234567890123456789\"}")).isNull();
        assertThat(MonitorRefResolver.contextMonitorId("{\"monitor_id\":\"١٢\"}")).isNull();
        assertThat(MonitorRefResolver.contextMonitorId("{\"monitor_id\":null}")).isNull();
        assertThat(MonitorRefResolver.contextMonitorId("{bozuk monitor_id")).isNull();
        assertThat(MonitorRefResolver.contextMonitorId(null)).isNull();
        assertThat(MonitorRefResolver.contextMonitorId("{\"x\":1}")).isNull();
    }
}
