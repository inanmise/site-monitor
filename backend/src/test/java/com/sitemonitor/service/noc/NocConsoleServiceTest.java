package com.sitemonitor.service.noc;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.NocCallLog;
import com.sitemonitor.model.NocDelivery;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.NocCallLogRepository;
import com.sitemonitor.repository.NocDeliveryRepository;
import com.sitemonitor.repository.NotificationLogRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.repository.UserPushDeliveryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 7/24 konsolu (2026-10-04): süzgeçler, KPI'lar, 7/24'e iletim bayrağı, son arama, sahibi takım çözümü, öncelik sırası,
 * TOPLU okuma (kaynak başına tek sorgu — satır başına sorgu yok) ve kısa ömürlü bellek (fresh en çok 5 sn'de bir).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NocConsoleServiceTest {

    private static final long TEAM_A = 1L, TEAM_B = 2L, TEAM_UG = 3L;

    @Mock AlertEventRepository alertRepo;
    @Mock NocDeliveryRepository deliveryRepo;
    @Mock NocCallLogRepository callRepo;
    @Mock NotificationLogRepository notificationLogRepo;
    @Mock UserPushDeliveryRepository pushRepo;
    @Mock CertificateInventoryRepository inventoryRepo;
    @Mock TeamRepository teamRepo;
    @Mock NocMonitorDirectory directory;
    @Mock NocMonitorDirectory.Snapshot snapshot;

    private NocConsoleService svc;
    private final AtomicLong now = new AtomicLong(java.time.Instant.parse("2026-10-04T12:00:00Z").toEpochMilli());
    private final List<AlertEvent> events = new ArrayList<>();

    private AlertEvent ev(long id, String domain, String type, String level, Long teamId, String createdAt, boolean resolved) {
        AlertEvent e = new AlertEvent();
        e.setId(id); e.setDomain(domain); e.setAlertType(type); e.setAlertLevel(level); e.setTeamId(teamId);
        e.setCreatedAt(createdAt); e.setResolved(resolved); e.setAcknowledged(false);
        events.add(e);
        return e;
    }

    private static NocDelivery delivery(long alertId, String status) {
        NocDelivery d = new NocDelivery();
        d.setDedupeKey("alert:" + alertId + ":OPEN"); d.setAlertEventId(alertId); d.setPhase("OPEN"); d.setStatus(status);
        d.setCreatedAt("2026-10-04T11:00:00"); d.setUpdatedAt("2026-10-04T11:00:04"); d.setGroupNames("NOC Ana");
        return d;
    }

    private static NocCallLog call(long alertId, String who, String at) {
        NocCallLog c = new NocCallLog();
        c.setAlertId(alertId); c.setContactedName(who); c.setContactedAt(at); c.setOutcome("NO_ANSWER");
        c.setChannel("PHONE"); c.setCreatedByName("Operatör A");
        return c;
    }

    @BeforeEach
    void setUp() {
        svc = new NocConsoleService(alertRepo, new NocAlertFacts(deliveryRepo), callRepo, notificationLogRepo, pushRepo,
                inventoryRepo, teamRepo, directory);
        svc.clock = now::get;
        svc.cacheMs = 15_000;
        // 10: açık, A, 7/24'e gitti, aranmadı · 11: açık, B, 7/24'e gitti, arandı · 12: açık, sertifika (takım envanterden), gitmedi
        // 13: kapalı, A, fırtına ile gitti · 14: açık, A, uyarı, 7/24 FAILED (gitmemiş sayılır)
        ev(10, "a.example.com", "PING_DOWN", "CRITICAL", TEAM_A, "2026-10-04T11:00:00", false);
        ev(11, "b.example.com", "HTTP_DOWN", "CRITICAL", TEAM_B, "2026-10-04T10:00:00", false);
        ev(12, "cert.example.com", "EXPIRY", "HIGH", null, "2026-10-04T09:00:00", false);
        ev(13, "c.example.com", "PING_DOWN", "CRITICAL", TEAM_A, "2026-10-04T08:00:00", true);
        ev(14, "d.example.com", "DNS_FAILURE", "WARNING", TEAM_A, "2026-10-04T11:30:00", false);
        when(alertRepo.findOpenOrCreatedSince(anyString(), any())).thenAnswer(i -> new ArrayList<>(events));
        when(deliveryRepo.findByDedupeKeyIn(anyCollection())).thenReturn(List.of(
                delivery(10, "SENT"), delivery(11, "SENT"), delivery(13, "SENT_VIA_STORM"), delivery(14, "FAILED: smtp")));
        when(callRepo.findLatestByAlertIds(anyCollection())).thenReturn(List.of(call(11, "Kişi B", "2026-10-04T10:05:00")));
        when(callRepo.countGroupedByAlertIds(anyCollection())).thenReturn(List.<Object[]>of(new Object[]{11L, 2L}));
        when(callRepo.countByContactedAtGreaterThanEqual(anyString())).thenReturn(4L);
        when(notificationLogRepo.countByAlertIds(anyCollection())).thenReturn(List.<Object[]>of(new Object[]{10L, 3L, 1L, 2L}));
        when(pushRepo.countByAlertEventIdInGroupByStatus(anyCollection())).thenReturn(List.<Object[]>of(
                new Object[]{10L, "SENT", 5L}, new Object[]{10L, "FAILED", 1L}, new Object[]{10L, "SKIPPED_USER_OPT_OUT", 2L}));
        CertificateInventory inv = new CertificateInventory();
        inv.setDomain("cert.example.com"); inv.setTeamId(TEAM_B); inv.setUgTeamId(TEAM_UG);
        when(inventoryRepo.findByDomainIn(anyCollection())).thenReturn(List.of(inv));
        Team a = new Team(); a.setId(TEAM_A); a.setName("Takım A");
        Team b = new Team(); b.setId(TEAM_B); b.setName("Takım B");
        Team ug = new Team(); ug.setId(TEAM_UG); ug.setName("Takım UG");
        when(teamRepo.findAll()).thenReturn(List.of(a, b, ug));
        when(directory.snapshot()).thenReturn(snapshot);
        when(snapshot.forAlert(eq(NocType.PING), eq("a.example.com"), any())).thenReturn(
                new NocMonitorDirectory.Row(NocType.PING, 77L, "A ping", "a.example.com", TEAM_A, null, true, true, null, false));
    }

    private static NocConsoleService.Query q() {
        return new NocConsoleService.Query(null, null, null, null, null, null, null, null, 0, 25, false);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> r) {
        return (List<Map<String, Object>>) r.get("items");
    }

    @Test
    @DisplayName("KPI'lar kurum genelinden: açık, 7/24'e giden, aranmamış (açık + gitmiş + arama yok), son bir saatte arama")
    void kpis() {
        Map<String, Object> r = svc.console(q(), true);
        @SuppressWarnings("unchecked") Map<String, Object> k = (Map<String, Object>) r.get("kpis");
        assertThat(k.get("open")).isEqualTo(4L);
        assertThat(k.get("open_critical")).isEqualTo(2L);
        assertThat(k.get("noc_sent")).isEqualTo(3L);          // 10, 11, 13 (fırtına) — FAILED sayılmaz
        assertThat(k.get("not_called")).isEqualTo(1L);        // yalnız 10
        assertThat(k.get("called_last_hour")).isEqualTo(4L);
        assertThat(r.get("window")).isEqualTo("24h");
        assertThat(r.get("total")).isEqualTo(5);
        assertThat(r.get("can_write")).isEqualTo(true);
    }

    @Test
    @DisplayName("satır: kanallar, 7/24 iletim anı/fırtına, son arama (kim/ne zaman/sonuç/kim girdi), izleme adı + derin bağlantı parçaları")
    void rowShape() {
        List<Map<String, Object>> rows = items(svc.console(q(), false));
        Map<String, Object> r10 = rows.stream().filter(m -> Long.valueOf(10L).equals(m.get("id"))).findFirst().orElseThrow();
        @SuppressWarnings("unchecked") Map<String, Object> ch = (Map<String, Object>) r10.get("channels");
        assertThat(ch).containsEntry("email_sent", 3L).containsEntry("email_failed", 1L).containsEntry("webhook_sent", 2L)
                .containsEntry("push_sent", 5L).containsEntry("push_failed", 1L).containsEntry("noc", true);
        @SuppressWarnings("unchecked") Map<String, Object> noc = (Map<String, Object>) r10.get("noc");
        assertThat(noc).containsEntry("sent_at", "2026-10-04T11:00:04").containsEntry("via_storm", false);
        @SuppressWarnings("unchecked") Map<String, Object> mon = (Map<String, Object>) r10.get("monitor");
        assertThat(mon).containsEntry("name", "A ping").containsEntry("id", 77L).containsEntry("tab", "ping");
        assertThat(r10.get("team_name")).isEqualTo("Takım A");
        assertThat(r10.get("can_call")).isEqualTo(false);
        assertThat(r10.get("last_call")).isNull();

        Map<String, Object> r11 = rows.stream().filter(m -> Long.valueOf(11L).equals(m.get("id"))).findFirst().orElseThrow();
        @SuppressWarnings("unchecked") Map<String, Object> last = (Map<String, Object>) r11.get("last_call");
        assertThat(last).containsEntry("contacted_name", "Kişi B").containsEntry("outcome", "NO_ANSWER")
                .containsEntry("created_by_name", "Operatör A");
        assertThat(r11.get("call_count")).isEqualTo(2L);

        // Sertifika alarmı: takım damgası yok → envanter SY; UG ayrıca
        Map<String, Object> r12 = rows.stream().filter(m -> Long.valueOf(12L).equals(m.get("id"))).findFirst().orElseThrow();
        assertThat(r12.get("team_id")).isEqualTo(TEAM_B);
        assertThat(r12.get("ug_team_name")).isEqualTo("Takım UG");
        assertThat(r12.get("family")).isEqualTo("cert");
        assertThat(r12.get("noc")).isNull();

        Map<String, Object> r13 = rows.stream().filter(m -> Long.valueOf(13L).equals(m.get("id"))).findFirst().orElseThrow();
        @SuppressWarnings("unchecked") Map<String, Object> noc13 = (Map<String, Object>) r13.get("noc");
        assertThat(noc13.get("via_storm")).isEqualTo(true);
    }

    @Test
    @DisplayName("öncelik: açık + 7/24'e gitmiş + aranmamış en üstte; kapalı en altta")
    void priorityOrder() {
        List<Map<String, Object>> rows = items(svc.console(q(), true));
        assertThat(rows.get(0).get("id")).isEqualTo(10L);
        assertThat(rows.get(rows.size() - 1).get("id")).isEqualTo(13L);
    }

    @Test
    @DisplayName("süzgeçler: takım (SY ya da UG), seviye, tür, 7/24'e gidenler/gitmeyenler, arandı/aranmadı, durum, arama")
    void filters() {
        assertThat(ids(new NocConsoleService.Query(null, TEAM_A, null, null, null, null, null, null, 0, 25, false))).containsExactlyInAnyOrder(10L, 13L, 14L);
        assertThat(ids(new NocConsoleService.Query(null, TEAM_UG, null, null, null, null, null, null, 0, 25, false))).containsExactly(12L);
        assertThat(ids(new NocConsoleService.Query(null, null, "warning", null, null, null, null, null, 0, 25, false))).containsExactly(14L);
        assertThat(ids(new NocConsoleService.Query(null, null, null, "ping", null, null, null, null, 0, 25, false))).containsExactlyInAnyOrder(10L, 13L);
        assertThat(ids(new NocConsoleService.Query(null, null, null, null, "sent", null, null, null, 0, 25, false))).containsExactlyInAnyOrder(10L, 11L, 13L);
        assertThat(ids(new NocConsoleService.Query(null, null, null, null, "not_sent", null, null, null, 0, 25, false))).containsExactlyInAnyOrder(12L, 14L);
        assertThat(ids(new NocConsoleService.Query(null, null, null, null, null, "yes", null, null, 0, 25, false))).containsExactly(11L);
        assertThat(ids(new NocConsoleService.Query(null, null, null, null, null, "no", "open", null, 0, 25, false))).containsExactlyInAnyOrder(10L, 12L, 14L);
        assertThat(ids(new NocConsoleService.Query(null, null, null, null, null, null, "resolved", null, 0, 25, false))).containsExactly(13L);
        assertThat(ids(new NocConsoleService.Query(null, null, null, null, null, null, null, "TAKIM b", 0, 25, false))).containsExactlyInAnyOrder(11L, 12L);
        assertThat(ids(new NocConsoleService.Query(null, null, null, null, null, null, null, "a ping", 0, 25, false))).containsExactly(10L);
    }

    @Test
    @DisplayName("sayfalama + yüzeyler (takım/tür/seviye sayıları)")
    void paginationAndFacets() {
        Map<String, Object> r = svc.console(new NocConsoleService.Query("1h", null, null, null, null, null, null, null, 1, 2, false), true);
        assertThat(r.get("window")).isEqualTo("1h");
        assertThat(r.get("page")).isEqualTo(1);
        assertThat(items(r)).hasSize(2);
        @SuppressWarnings("unchecked") Map<String, Object> f = (Map<String, Object>) r.get("facets");
        @SuppressWarnings("unchecked") List<Map<String, Object>> teams = (List<Map<String, Object>>) f.get("teams");
        assertThat(teams).extracting(t -> t.get("name")).containsExactly("Takım A", "Takım B");
        @SuppressWarnings("unchecked") Map<String, Long> levels = (Map<String, Long>) f.get("levels");
        assertThat(levels).containsEntry("CRITICAL", 3L).containsEntry("HIGH", 1L).containsEntry("WARNING", 1L);
        // bilinmeyen pencere varsayılana, aşırı boyut tavana düşer
        Map<String, Object> r2 = svc.console(new NocConsoleService.Query("99y", null, null, null, null, null, null, null, 0, 10_000, false), true);
        assertThat(r2.get("window")).isEqualTo("24h");
        assertThat(r2.get("size")).isEqualTo(NocConsoleService.MAX_SIZE);
    }

    @Test
    @DisplayName("TOPLU okuma: her kaynak tek sorgu (satır başına sorgu yok); bellek TTL içinde DB'ye dönmez; fresh 5 sn'de bir")
    void batchAndMemo() {
        svc.console(q(), true);
        svc.console(new NocConsoleService.Query(null, TEAM_A, null, null, null, null, null, null, 0, 25, false), true);
        verify(alertRepo, times(1)).findOpenOrCreatedSince(anyString(), any());
        verify(deliveryRepo, times(1)).findByDedupeKeyIn(anyCollection());
        verify(callRepo, times(1)).findLatestByAlertIds(anyCollection());
        verify(callRepo, times(1)).countGroupedByAlertIds(anyCollection());
        verify(notificationLogRepo, times(1)).countByAlertIds(anyCollection());
        verify(pushRepo, times(1)).countByAlertEventIdInGroupByStatus(anyCollection());
        verify(inventoryRepo, times(1)).findByDomainIn(anyCollection());
        verify(teamRepo, times(1)).findAll();
        verify(directory, times(1)).snapshot();

        svc.console(new NocConsoleService.Query(null, null, null, null, null, null, null, null, 0, 25, true), true);
        verify(alertRepo, times(2)).findOpenOrCreatedSince(anyString(), any());   // fresh → yeniden hesap
        now.addAndGet(1_000);
        svc.console(new NocConsoleService.Query(null, null, null, null, null, null, null, null, 0, 25, true), true);
        verify(alertRepo, times(2)).findOpenOrCreatedSince(anyString(), any());   // 5 sn dolmadan ikinci fresh → bellek
        now.addAndGet(20_000);
        svc.console(q(), true);
        verify(alertRepo, times(3)).findOpenOrCreatedSince(anyString(), any());   // TTL doldu
    }

    @Test
    @DisplayName("kaynak düşerse konsol yine döner (alan boş) — 7/24 teslim okunamazsa satırlar 'gitmedi' görünür")
    void sourceFailureDegrades() {
        when(deliveryRepo.findByDedupeKeyIn(anyCollection())).thenThrow(new RuntimeException("db"));
        when(callRepo.findLatestByAlertIds(anyCollection())).thenThrow(new RuntimeException("db"));
        Map<String, Object> r = svc.console(q(), true);
        assertThat(r.get("total")).isEqualTo(5);
        @SuppressWarnings("unchecked") Map<String, Object> k = (Map<String, Object>) r.get("kpis");
        assertThat(k.get("noc_sent")).isEqualTo(0L);
    }

    @Test
    @DisplayName("tavan: MAX_ROWS'u aşan küme kırpılır ve 'truncated' bildirilir")
    void truncation() {
        List<AlertEvent> many = new ArrayList<>();
        for (int i = 0; i < NocConsoleService.MAX_ROWS + 1; i++) {
            AlertEvent e = new AlertEvent();
            e.setId(1000L + i); e.setDomain("h" + i + ".example.com"); e.setAlertType("PING_DOWN"); e.setAlertLevel("CRITICAL");
            e.setTeamId(TEAM_A); e.setCreatedAt("2026-10-04T11:00:00"); e.setResolved(false);
            many.add(e);
        }
        when(alertRepo.findOpenOrCreatedSince(anyString(), any())).thenReturn(many);
        Map<String, Object> r = svc.console(q(), true);
        assertThat(r.get("truncated")).isEqualTo(true);
        assertThat(r.get("total")).isEqualTo(NocConsoleService.MAX_ROWS);
        verify(deliveryRepo, times(2)).findByDedupeKeyIn(anyCollection());   // 1000 satır → 500'lük iki parça
    }

    private List<Long> ids(NocConsoleService.Query query) {
        List<Long> out = new ArrayList<>();
        for (Map<String, Object> m : items(svc.console(query, true))) out.add((Long) m.get("id"));
        return out;
    }

}
