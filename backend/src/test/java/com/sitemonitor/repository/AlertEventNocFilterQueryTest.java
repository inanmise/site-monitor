package com.sitemonitor.repository;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.NocCallLog;
import com.sitemonitor.model.NocDelivery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.TestPropertySource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "7/24'e gidenler" süzgeci + 7/24 konsolu sorguları (2026-10-04) — GERÇEK SQL (H2, PostgreSQL uyumluluk modu).
 *
 * <ul>
 *   <li>…Noc ikizleri YALNIZ 7/24 AÇILIŞ teslimi gitmiş (SENT / SENT_VIA_STORM / QUEUED_RETRY…) alarmları döndürür;
 *       FAILED / SKIPPED / SENDING ve yalnız ÇÖZÜM satırı olan alarm girmez.</li>
 *   <li>İkizlerin geri kalan süzgeçleri asıllarıyla AYNI: aynı parametrelerle asıl sorgunun kümesi ⊇ ikizinki ve fark
 *       tam olarak 7/24'e gitmeyenlerdir; sayaçlar listeyle aynı kümeyi sayar; takım kapsamı aynen uygulanır.</li>
 *   <li>Asılların gövdesi sabite taşındı — metin BİREBİR aynı (yalnız ikiz EXISTS ekler).</li>
 *   <li>Konsol: açık + pencerede açılanlar; son arama satırı ve arama sayıları tek sorguda.</li>
 * </ul>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:alertnocfilter;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=VALUE",
        "spring.datasource.driver-class-name=org.h2.Driver",
})
class AlertEventNocFilterQueryTest {

    private static final long OWN = 5L, FOREIGN = 9L;
    private static final List<Long> NONE = List.of(-1L);
    private static final List<String> NO_TYPES = AlertEventRepository.NO_TYPE_SCOPE;
    private static final Pageable PAGE = PageRequest.of(0, 100, Sort.by("domain"));

    @Autowired AlertEventRepository alerts;
    @Autowired NocDeliveryRepository deliveries;
    @Autowired NocCallLogRepository calls;

    private final Map<String, Long> ids = new HashMap<>();

    private Long alert(String domain, Long teamId, String level, String createdAt, boolean resolved) {
        AlertEvent e = new AlertEvent();
        e.setDomain(domain);
        e.setTeamId(teamId);
        e.setAlertType("PING_DOWN");
        e.setAlertLevel(level);
        e.setAcknowledged(false);
        e.setCreatedAt(createdAt);
        e.setResolved(resolved);
        e.setResolvedAt(resolved ? "2026-10-04T10:00:00" : null);
        Long id = alerts.save(e).getId();
        ids.put(domain, id);
        return id;
    }

    private void delivery(Long alertId, String phase, String status) {
        NocDelivery d = new NocDelivery();
        d.setDedupeKey("alert:" + alertId + ":" + phase);
        d.setAlertEventId(alertId);
        d.setPhase(phase);
        d.setStatus(status);
        d.setCreatedAt("2026-10-04T08:00:00");
        d.setUpdatedAt("2026-10-04T08:00:05");
        deliveries.save(d);
    }

    private void call(Long alertId, String at, String who) {
        NocCallLog c = new NocCallLog();
        c.setAlertId(alertId);
        c.setContactedName(who);
        c.setContactedAt(at);
        c.setOutcome("REACHED");
        c.setChannel("PHONE");
        c.setCreatedByName("Operatör");
        c.setCreatedAt(at);
        calls.save(c);
    }

    @BeforeEach
    void seed() {
        delivery(alert("sent.example.com", OWN, "CRITICAL", "2026-10-04T08:00:00", false), "OPEN", "SENT");
        delivery(alert("storm.example.com", OWN, "CRITICAL", "2026-10-04T08:01:00", false), "OPEN", "SENT_VIA_STORM");
        delivery(alert("retry.example.com", FOREIGN, "HIGH", "2026-10-04T08:02:00", true), "OPEN", "QUEUED_RETRY: 421");
        delivery(alert("failed.example.com", OWN, "CRITICAL", "2026-10-04T08:03:00", false), "OPEN", "FAILED: smtp");
        delivery(alert("skipped.example.com", OWN, "CRITICAL", "2026-10-04T08:04:00", false), "OPEN", "SKIPPED_DISABLED");
        delivery(alert("sending.example.com", OWN, "CRITICAL", "2026-10-04T08:05:00", false), "OPEN", "SENDING");
        delivery(alert("resolveonly.example.com", OWN, "CRITICAL", "2026-10-04T08:06:00", false), "RESOLVE", "SENT");
        alert("none.example.com", OWN, "WARNING", "2026-10-04T08:07:00", false);
        alert("old.example.com", FOREIGN, "WARNING", "2026-09-01T08:00:00", true);
    }

    private Set<String> domains(List<AlertEvent> rows) {
        return rows.stream().map(AlertEvent::getDomain).collect(Collectors.toSet());
    }

    @Test
    @DisplayName("…Noc ikizi yalnız 7/24'e GİTMİŞ alarmları döndürür; asıl sorgu tümünü (fark = gitmeyenler)")
    void nocVariantFiltersSentOnly() {
        var noc = alerts.findFilteredNoc(null, null, null, null, null, null, null, null, false, NO_TYPES,
                null, null, null, null, false, NONE, PAGE).getContent();
        assertThat(domains(noc)).containsExactlyInAnyOrder("sent.example.com", "storm.example.com", "retry.example.com");
        var all = alerts.findFiltered(null, null, null, null, null, null, null, null, false, NO_TYPES,
                null, null, null, null, false, NONE, PAGE).getContent();
        assertThat(all).hasSize(9);
        assertThat(domains(all)).containsAll(domains(noc));
    }

    @Test
    @DisplayName("…Noc ikizlerinde diğer süzgeçler ve takım kapsamı asıllarıyla AYNI; sayaçlar listeyle aynı kümeyi sayar")
    void nocVariantKeepsOtherFiltersAndScope() {
        // Takım kapsamı: yalnız OWN → retry (FOREIGN) düşer
        var scoped = alerts.findFilteredNoc(null, null, null, null, null, null, null, null, false, NO_TYPES,
                null, null, null, null, true, List.of(OWN), PAGE).getContent();
        assertThat(domains(scoped)).containsExactlyInAnyOrder("sent.example.com", "storm.example.com");
        // Açık süzgeci + seviye
        var openHigh = alerts.findFilteredNoc(false, null, null, null, null, null, null, null, false, NO_TYPES,
                null, "HIGH", null, null, false, NONE, PAGE).getContent();
        assertThat(openHigh).isEmpty();   // retry HIGH ama çözülmüş
        // Arama (q) — kaçışlı LIKE
        var q = alerts.findFilteredNoc(null, null, null, null, null, null, null, null, false, NO_TYPES,
                "%storm%", null, null, null, false, NONE, PAGE).getContent();
        assertThat(domains(q)).containsExactly("storm.example.com");

        long typeTotal = alerts.countFilteredByTypeNoc(null, null, null, null, null, null, null, false, NO_TYPES,
                null, null, null, null, false, NONE).stream().mapToLong(r -> ((Number) r[1]).longValue()).sum();
        assertThat(typeTotal).isEqualTo(3);
        long facetTotal = alerts.countFacetsNoc(null, null, null, null, null, null, null, null, false, NO_TYPES,
                null, null, false, NONE).stream().mapToLong(r -> ((Number) r[2]).longValue()).sum();
        assertThat(facetTotal).isEqualTo(3);
        long stale = alerts.countStaleNoc(null, "2026-10-04T08:01:30", null, null, false, NO_TYPES, null, null, false, NONE);
        assertThat(stale).isEqualTo(2);   // sent (08:00) + storm (08:01)
        long staleAll = alerts.countStale(null, "2026-10-04T08:01:30", null, null, false, NO_TYPES, null, null, false, NONE);
        assertThat(staleAll).isEqualTo(3);   // + old
    }

    @Test
    @DisplayName("asıl sorgular sabite taşındı: ikiz = asıl gövde + yalnız 7/24 EXISTS")
    void variantsShareTheBody() {
        assertThat(AlertEventRepository.NOC_SENT_FILTER).contains("NocDelivery").contains("'OPEN'")
                .contains("LIKE 'SENT%'").contains("LIKE 'QUEUED_RETRY%'");
        for (String body : List.of(AlertEventRepository.ALERT_LIST_FIND, AlertEventRepository.ALERT_LIST_TYPE_COUNT,
                AlertEventRepository.ALERT_LIST_FACETS, AlertEventRepository.ALERT_LIST_STALE)) {
            assertThat(body).contains(":scoped = FALSE OR e.teamId IN :scope").doesNotContain("GROUP BY");
        }
    }

    @Test
    @DisplayName("konsol: açık alarmlar + pencerede açılanlar (en yeni önce, tavan); son arama + sayı tek sorguda; son 1 saat sayımı")
    void consoleQueries() {
        var rows = alerts.findOpenOrCreatedSince("2026-10-04T00:00:00", PageRequest.of(0, 50));
        assertThat(domains(rows)).doesNotContain("old.example.com").contains("retry.example.com", "none.example.com");
        assertThat(rows.get(0).getDomain()).isEqualTo("none.example.com");   // en yeni önce
        assertThat(alerts.findOpenOrCreatedSince("2026-10-04T00:00:00", PageRequest.of(0, 2))).hasSize(2);

        Long sent = ids.get("sent.example.com");
        call(sent, "2026-10-04T08:10:00", "Kişi A");
        call(sent, "2026-10-04T08:20:00", "Kişi B");
        call(ids.get("storm.example.com"), "2026-10-04T08:15:00", "Kişi C");
        var latest = calls.findLatestByAlertIds(List.of(sent, ids.get("storm.example.com"), ids.get("none.example.com")));
        assertThat(latest).extracting(NocCallLog::getContactedName).containsExactlyInAnyOrder("Kişi B", "Kişi C");
        Map<Long, Long> counts = new HashMap<>();
        for (Object[] r : calls.countGroupedByAlertIds(List.of(sent, ids.get("storm.example.com"))))
            counts.put(((Number) r[0]).longValue(), ((Number) r[1]).longValue());
        assertThat(counts).containsEntry(sent, 2L).containsEntry(ids.get("storm.example.com"), 1L);
        assertThat(calls.countByContactedAtGreaterThanEqual("2026-10-04T08:15:00")).isEqualTo(2);

        // 7/24 teslim satırları toplu okunur (konsolun / Alarm Geçmişi rozetinin kaynağı)
        var keys = List.of("alert:" + sent + ":OPEN", "alert:" + ids.get("failed.example.com") + ":OPEN", "alert:999999:OPEN");
        assertThat(deliveries.findByDedupeKeyIn(keys)).extracting(NocDelivery::getStatus)
                .containsExactlyInAnyOrder("SENT", "FAILED: smtp");
    }
}
