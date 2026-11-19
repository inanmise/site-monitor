package com.sitemonitor.repository;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.service.OutageCalendar;
import com.sitemonitor.service.OutageCalendarService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Takım kesinti takvimi (2026-11-19) — dar alarm sorgusunun ve envanter parçalı okumasının H2 (gerçek JPQL) üstünde kanıtı +
 * servisin uçtan uca sahiplik kuralı ({@code AlertOwnership}): bağımsız izleme alarmı yalnız damgalı takımın, envanter
 * gibi yönlenen alarm damga + envanter SY / UG takımının; sahipsiz alarm hiç kimsenin. Damgalar UTC ISO metni.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class OutageCalendarQueriesTest {

    @Autowired AlertEventRepository repo;
    @Autowired CertificateInventoryRepository invRepo;

    private OutageCalendarService service;
    private static final YearMonth NOV = YearMonth.of(2026, 11);
    /** Ay bittikten sonra — tüm günler geçmiş. */
    private static final long AFTER = LocalDateTime.parse("2026-12-10T00:00").toInstant(ZoneOffset.UTC).toEpochMilli();

    @BeforeEach
    void setUp() {
        service = new OutageCalendarService(repo, invRepo);
        ReflectionTestUtils.setField(service, "cacheMs", 0L);   // bellek kapalı — her çağrı okur
    }

    private AlertEvent alert(String domain, String type, String level, Long teamId, String createdUtc, String resolvedUtc,
                             String contextJson) {
        AlertEvent e = new AlertEvent();
        e.setDomain(domain);
        e.setAlertType(type);
        e.setAlertLevel(level);
        e.setAcknowledged(false);
        e.setResolved(resolvedUtc != null);
        e.setResolvedAt(resolvedUtc);
        e.setCreatedAt(createdUtc);
        e.setTeamId(teamId);
        e.setContextJson(contextJson);
        return repo.save(e);
    }

    private void inventory(String domain, Long sy, Long ug) {
        CertificateInventory c = new CertificateInventory();
        c.setDomain(domain);
        c.setPort(443);
        c.setTeamId(sy);
        c.setUgTeamId(ug);
        c.setActive(true);
        invRepo.save(c);
    }

    private static int minutes(OutageCalendarService.TeamMonth tm, int day) {
        Integer m = tm.result().days().get(day - 1).outageMinutes();
        return m == null ? -1 : m;
    }

    @Test
    @DisplayName("sorgu: yalnız kesinti ailesi + ayın penceresiyle KESİŞENLER; bağlam yalnız bağlam türlerinde okunur")
    void queryWindowAndColumns() {
        // Kasım penceresi İstanbul: 2026-10-31T21:00:00Z – 2026-11-30T21:00:00Z
        alert("a.example.com", "HTTP_DOWN", "HIGH", 5L, "2026-11-10T07:00:00", "2026-11-10T08:00:00", "{\"team_id\":5}");   // içerde
        alert("a.example.com", "HTTP_SLOW", "WARNING", 5L, "2026-11-10T07:00:00", "2026-11-10T08:00:00", null);         // kesinti değil
        alert("a.example.com", "HTTP_DOWN", "HIGH", 5L, "2026-10-20T07:00:00", "2026-10-31T20:59:59", null);            // pencereden önce kapandı
        alert("a.example.com", "HTTP_DOWN", "HIGH", 5L, "2026-10-20T07:00:00", null, null);                              // önceden açık, hâlâ açık
        alert("a.example.com", "HTTP_DOWN", "HIGH", 5L, "2026-10-20T07:00:00", "2026-11-02T00:00:00", null);            // önceden açık, pencerede kapandı
        alert("a.example.com", "HTTP_DOWN", "HIGH", 5L, "2026-11-30T21:00:00", null, null);                              // pencere bitince açıldı
        alert("p.example.com", "PORT_DOWN", "CRITICAL", null, "2026-11-11T07:00:00", null, "{\"x\":1}");                  // bağlam türü

        List<Object[]> rows = repo.findOutageCalendarRows(
                List.of("HTTP_DOWN", "PORT_DOWN"), List.of("PORT_DOWN"),
                "2026-10-31T21:00:00", "2026-11-30T21:00:00", PageRequest.of(0, 100));
        assertThat(rows).hasSize(4);
        for (Object[] r : rows) {
            assertThat(r).hasSize(9);
            if ("HTTP_DOWN".equals(r[2])) assertThat(r[8]).as("bağlam yalnız bağlam türünde").isNull();
            else assertThat(r[8]).isEqualTo("{\"x\":1}");
        }
    }

    @Test
    @DisplayName("envanter parçalı okuma: yalnız istenen alan adları, pasif kayıt dahil")
    void inventoryInQuery() {
        inventory("a.example.com", 7L, 8L);
        inventory("b.example.com", 9L, null);
        CertificateInventory passive = new CertificateInventory();
        passive.setDomain("c.example.com");
        passive.setPort(443);
        passive.setTeamId(4L);
        passive.setActive(false);
        invRepo.save(passive);
        List<Object[]> rows = invRepo.findDomainOwnersIn(List.of("a.example.com", "c.example.com", "z.example.com"));
        assertThat(rows).hasSize(2);
        assertThat(rows.stream().map(r -> String.valueOf(r[0])).toList()).containsExactlyInAnyOrder("a.example.com", "c.example.com");
    }

    @Test
    @DisplayName("sahiplik: bağımsız izleme yalnız damgalı takımın; envanter türevi Port damga + SY + UG; sahipsiz hiç kimsenin")
    void ownershipRule() {
        inventory("shared.example.com", 7L, 8L);
        // Takım 5'in bağımsız HTTP izlemesi — host takım 7'nin envanterinde ama alarm YALNIZ 5'in
        alert("shared.example.com", "HTTP_DOWN", "HIGH", 5L, "2026-11-10T07:00:00", "2026-11-10T08:00:00", "{\"team_id\":5}");
        // Envanter türevi Port (damgasız bağlam) → SY 7 + UG 8
        alert("shared.example.com", "PORT_DOWN", "CRITICAL", null, "2026-11-11T07:00:00", "2026-11-11T07:30:00", "{}");
        // Bağımsız Port (bağlamda team_id 9) → yalnız 9, envanter okunmaz
        alert("shared.example.com", "PORT_DOWN", "WARNING", 9L, "2026-11-12T07:00:00", "2026-11-12T07:20:00", "{\"team_id\":9}");
        // Sahipsiz bağımsız HTTP alarmı → hiç kimsenin
        alert("nobody.example.com", "HTTP_DOWN", "HIGH", null, "2026-11-13T07:00:00", "2026-11-13T09:00:00", null);

        OutageCalendarService.TeamMonth t5 = service.forTeam(NOV, 5L, AFTER);
        assertThat(minutes(t5, 10)).isEqualTo(60);
        assertThat(t5.result().totalMinutes()).isEqualTo(60);

        OutageCalendarService.TeamMonth t7 = service.forTeam(NOV, 7L, AFTER);
        assertThat(minutes(t7, 10)).as("başka takımın bağımsız izlemesi sayılmaz").isZero();
        assertThat(minutes(t7, 11)).isEqualTo(30);
        assertThat(t7.result().days().get(10).worstLevel()).isEqualTo("CRITICAL");
        assertThat(minutes(t7, 12)).isZero();
        assertThat(service.forTeam(NOV, 8L, AFTER).result().totalMinutes()).isEqualTo(30);
        assertThat(service.forTeam(NOV, 9L, AFTER).result().totalMinutes()).isEqualTo(20);

        OutageCalendarService.MonthData data = service.monthData(NOV);
        assertThat(data.intervals()).hasSize(3);   // sahipsiz alarm düşer
        assertThat(data.intervals().stream().map(OutageCalendarService.OwnedInterval::owners).toList())
                .containsExactlyInAnyOrder(Set.of(5L), Set.of(7L, 8L), Set.of(9L));
        assertThat(data.truncated()).isFalse();
    }

    @Test
    @DisplayName("İstanbul gün sınırı: UTC 20:30–21:30 alarmı iki güne 30'ar dk; açık alarm şimdiye kadar")
    void istanbulDaysAndOpenAlarm() {
        alert("a.example.com", "HTTP_DOWN", "HIGH", 5L, "2026-11-10T20:30:00", "2026-11-10T21:30:00", null);
        alert("a.example.com", "HTTP_DOWN", "CRITICAL", 5L, "2026-11-19T05:00:00", null, null);   // İstanbul 08:00, açık
        long now = LocalDateTime.parse("2026-11-19T09:00").toInstant(ZoneOffset.UTC).toEpochMilli();   // İstanbul 12:00
        OutageCalendarService.TeamMonth tm = service.forTeam(NOV, 5L, now);
        assertThat(minutes(tm, 10)).isEqualTo(30);
        assertThat(minutes(tm, 11)).isEqualTo(30);
        assertThat(minutes(tm, 19)).isEqualTo(240);
        assertThat(tm.result().days().get(19).outageMinutes()).as("gelecek gün").isNull();
    }

    @Test
    @DisplayName("satır tavanı aşılırsa truncated: true ve en eski satırlar hesaplanır")
    void truncated() {
        ReflectionTestUtils.setField(service, "maxRows", 2);
        alert("a.example.com", "HTTP_DOWN", "HIGH", 5L, "2026-11-10T07:00:00", "2026-11-10T08:00:00", null);
        alert("a.example.com", "HTTP_DOWN", "HIGH", 5L, "2026-11-11T07:00:00", "2026-11-11T08:00:00", null);
        alert("a.example.com", "HTTP_DOWN", "HIGH", 5L, "2026-11-12T07:00:00", "2026-11-12T08:00:00", null);
        OutageCalendarService.TeamMonth tm = service.forTeam(NOV, 5L, AFTER);
        assertThat(tm.truncated()).isTrue();
        assertThat(tm.result().totalMinutes()).isEqualTo(120);
    }

    @Test
    @DisplayName("bellek: ay verisi 60 sn paylaşılır (takım süzgeci her çağrıda)")
    void memoised() {
        ReflectionTestUtils.setField(service, "cacheMs", 60_000L);
        alert("a.example.com", "HTTP_DOWN", "HIGH", 5L, "2026-11-10T07:00:00", "2026-11-10T08:00:00", null);
        assertThat(service.forTeam(NOV, 5L, AFTER).result().totalMinutes()).isEqualTo(60);
        alert("a.example.com", "HTTP_DOWN", "HIGH", 6L, "2026-11-12T07:00:00", "2026-11-12T08:00:00", null);
        assertThat(service.forTeam(NOV, 6L, AFTER).result().totalMinutes()).as("bellekten — yeni satır henüz yok").isZero();
        ReflectionTestUtils.setField(service, "cacheMs", 0L);
        assertThat(service.forTeam(NOV, 6L, AFTER).result().totalMinutes()).isEqualTo(60);
    }

    @Test
    @DisplayName("okuma hatası UnavailableException — 'kesinti yok' sayılmaz, saklanmaz")
    void readFailure() {
        AlertEventRepository broken = mock(AlertEventRepository.class);
        when(broken.findOutageCalendarRows(any(), any(), anyString(), anyString(), any()))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("db down"));
        OutageCalendarService s = new OutageCalendarService(broken, invRepo);
        assertThatThrownBy(() -> s.forTeam(NOV, 5L, AFTER)).isInstanceOf(OutageCalendarService.UnavailableException.class);
        assertThat(OutageCalendar.ISTANBUL.getTotalSeconds()).isEqualTo(3 * 3600);
    }
}
