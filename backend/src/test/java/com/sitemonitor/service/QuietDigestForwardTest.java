package com.sitemonitor.service;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.NotificationLog;
import com.sitemonitor.model.QuietDigestItem;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.NotificationLogRepository;
import com.sitemonitor.repository.QuietDigestItemRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.service.mail.MailKit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Sessiz saat özetinin iletim kopyaları (alarm iletimi, 2026-11-05): sahip takımın özeti giderken alarmların iletim
 * alıcılarına AYRI özet — yalnız kendilerine iletilen alarmlarla, başta (çoğul) bant; satır QUIET_DIGEST / FORWARD; teslim
 * edilen ve hâlâ açık alarmlar iletim damgasına yazılır; sahip özetinin alıcısı ve iletimle zaten bildirilmiş adres tekrar
 * almaz. Yönlendirme yokken özet bugünkü gibi.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class QuietDigestForwardTest {

    @Mock QuietDigestItemRepository itemRepo;
    @Mock AlertEventRepository alertEventRepo;
    @Mock TeamRepository teamRepo;
    @Mock NotificationLogRepository notificationLogRepo;
    @Mock EmailNotificationService emailService;
    @Mock EscalationService escalationService;
    @Mock SchedulerService schedulerService;

    static final long TEAM = 42L, RT_B = 7L, RT_C = 8L;
    static final String B_NAME = "Ağ Operasyon Merkezi — 7/24 İzleme";
    static final Instant AFTER = QuietHoursTest.ist("2026-10-02T07:01:00");

    QuietDigestService service;
    final Map<Long, QuietDigestItem> db = new LinkedHashMap<>();
    final Map<Long, AlertEvent> events = new HashMap<>();
    final List<NotificationLog> logs = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new QuietDigestService(itemRepo, alertEventRepo, teamRepo, notificationLogRepo, emailService,
                escalationService, schedulerService);
        service.clock = Clock.fixed(AFTER, ZoneOffset.UTC);
        Team t = new Team();
        t.setId(TEAM);
        t.setName("Ödeme");
        when(teamRepo.findAllById(any())).thenReturn(List.of(t));
        when(alertEventRepo.setForwardEmailsIfNull(any(), any())).thenReturn(1);
        when(alertEventRepo.setForwardEmailsIfUnchanged(any(), any(), any())).thenReturn(1);
        when(alertEventRepo.findAllById(any())).thenAnswer(i -> {
            List<AlertEvent> out = new ArrayList<>();
            for (Object id : (Iterable<?>) i.getArgument(0)) if (events.containsKey(id)) out.add(events.get(id));
            return out;
        });
        when(escalationService.teamEmailsForMonitor(TEAM, null)).thenReturn(List.of("team@x.com"));
        when(emailService.buildQuietDigestHtml(anyString(), anyString(), anyList()))
                .thenAnswer(i -> "<html>" + MailKit.bodyOpen() + "özet " + ((List<?>) i.getArgument(2)).size() + "</td></tr></html>");
        when(emailService.sendHtml(any(String[].class), any(), anyString(), anyString(), any())).thenReturn("SENT");
        when(emailService.sendHtml(any(String[].class), any(), anyString(), any(), any(), anyList(), anyBoolean(), any()))
                .thenReturn("SENT");
        when(itemRepo.findDue(anyString())).thenAnswer(i -> db.values().stream()
                .filter(x -> x.getDigestSentAt() == null && x.getWindowEnd().compareTo(i.getArgument(0)) <= 0).toList());
        when(itemRepo.claim(anyCollection(), anyString(), anyString())).thenAnswer(i -> {
            int n = 0;
            for (Object id : (Collection<?>) i.getArgument(0)) {
                QuietDigestItem x = db.get(id);
                if (x != null && x.getDigestSentAt() == null) {
                    x.setDigestSentAt(i.getArgument(1));
                    x.setDigestStatus(i.getArgument(2));
                    n++;
                }
            }
            return n;
        });
        when(itemRepo.findByDigestStatus(anyString())).thenAnswer(i -> db.values().stream()
                .filter(x -> i.getArgument(0).equals(x.getDigestStatus())).toList());
        when(itemRepo.markStatus(anyCollection(), anyString())).thenAnswer(i -> {
            for (Object id : (Collection<?>) i.getArgument(0)) db.get(id).setDigestStatus(i.getArgument(1));
            return 1;
        });
        when(notificationLogRepo.saveAll(anyList())).thenAnswer(i -> { logs.addAll(i.getArgument(0)); return i.getArgument(0); });
        when(notificationLogRepo.save(any())).thenAnswer(i -> { logs.add(i.getArgument(0)); return i.getArgument(0); });
    }

    private AlertEvent event(long id, boolean resolved) {
        AlertEvent e = new AlertEvent();
        e.setId(id);
        e.setDomain("svc" + id + ".example.com");
        e.setAlertType("HTTP_DOWN");
        e.setAlertLevel("WARNING");
        e.setTeamId(TEAM);
        e.setContextJson("{\"monitor_id\":" + (100 + id) + "}");
        e.setCreatedAt("2026-10-01T20:0" + id + ":00");
        e.setResolved(resolved);
        if (resolved) e.setResolvedAt("2026-10-01T21:00:00");
        events.put(id, e);
        return e;
    }

    private void item(long id, long eventId) {
        QuietDigestItem i = new QuietDigestItem();
        i.setId(id);
        i.setAlertEventId(eventId);
        i.setTeamId(TEAM);
        i.setWindowKey("2026-10-01T22:00");
        i.setWindowEnd("2026-10-02T04:00:00");
        i.setDeferredAt("2026-10-01T20:00:00");
        i.setOpeningDeferred(true);
        db.put(id, i);
    }

    /** Sahte yönlendirme: B tüm alarmları, C yalnız 2 numaralı alarmı (izleme 102) alır. */
    private ForwardFakes.Routing routing() {
        ForwardFakes.Routing r = new ForwardFakes.Routing(Map.of(TEAM, List.of(
                ForwardFakes.target(RT_B, B_NAME, List.of("Ödeme"), List.of("b@x.com", "TEAM@x.com")),
                ForwardFakes.target(RT_C, "Takım C", List.of("Ödeme"), List.of("c@x.com")))));
        r.filter = (ref, t) -> t.recipientTeamId() != RT_C || Long.valueOf(102L).equals(ref.monitorId());
        service.setForwardRouting(r);
        return r;
    }

    private List<NotificationLog> forwardRows() {
        return logs.stream().filter(l -> EscalationService.ROLE_FORWARD.equals(l.getRecipientRole())).toList();
    }

    @Test
    @DisplayName("yönlendirme yok → özet bugünkü gibi (tek posta, iletim satırı yok)")
    void noRouting() {
        event(1, false);
        item(10, 1);
        service.run(AFTER);
        verify(emailService).sendHtml(any(String[].class), any(), anyString(), anyString(), any());
        verify(emailService, never()).sendHtml(any(String[].class), any(), anyString(), any(), any(), anyList(), anyBoolean(), any());
        assertThat(forwardRows()).isEmpty();
        verify(alertEventRepo, never()).setForwardEmailsIfNull(any(), any());
    }

    @Test
    @DisplayName("iletim kopyası: alıcı takım başına ayrı özet, yalnız kendi alarmları; bant; sahip adresi düşer; açık alarm damgalanır")
    void forwardCopies() {
        event(1, false);
        event(2, true);
        item(10, 1);
        item(11, 2);
        routing();

        service.run(AFTER);

        ArgumentCaptor<String[]> to = ArgumentCaptor.forClass(String[].class);
        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(emailService, times(2)).sendHtml(to.capture(), any(), anyString(), html.capture(), text.capture(), anyList(),
                anyBoolean(), any());
        assertThat(to.getAllValues()).extracting(a -> List.of(a)).containsExactly(List.of("b@x.com"), List.of("c@x.com"));
        assertThat(html.getAllValues().get(0)).contains("İletilen alarm").contains("özet 2");
        assertThat(html.getAllValues().get(1)).contains("özet 1");
        assertThat(text.getAllValues().get(0)).startsWith("(i) İletilen alarm\nBu alarmlar Ödeme takımının izlemelerine ait");

        List<NotificationLog> rows = forwardRows();
        assertThat(rows).extracting(NotificationLog::getAlertEventId).containsExactly(1L, 2L, 2L);
        assertThat(rows).extracting(NotificationLog::getRecipientName).containsExactly(B_NAME, B_NAME, "Takım C");
        assertThat(rows).extracting(NotificationLog::getTrigger).containsOnly(EscalationService.TRIGGER_QUIET_DIGEST);
        assertThat(rows).extracting(NotificationLog::getEmailStatus).containsOnly("SENT");
        // yalnız AÇIK alarm damgalanır (çözülmüş 2 numara hayır)
        verify(alertEventRepo).setForwardEmailsIfNull(1L, "{\"7\":[\"b@x.com\"]}");
        verify(alertEventRepo, never()).setForwardEmailsIfNull(eq(2L), any());
    }

    @Test
    @DisplayName("iletimle zaten bildirilmiş adres tekrar almaz (karar satırı); e-postası kapalı alarm iletim özetine girmez")
    void alreadyStampedAndMailOff() {
        AlertEvent e1 = event(1, false);
        e1.setForwardEmails("{\"7\":[\"B@x.com\"]}");
        AlertEvent e3 = event(3, false);
        e3.setContextJson("{\"monitor_id\":103,\"mail_disabled\":true}");
        item(10, 1);
        item(12, 3);
        service.setForwardRouting(new ForwardFakes.Routing(Map.of(TEAM, List.of(
                ForwardFakes.target(RT_B, B_NAME, List.of("Ödeme"), List.of("b@x.com"))))));
        service.run(AFTER);
        verify(emailService, never()).sendHtml(any(String[].class), any(), anyString(), any(), any(), anyList(), anyBoolean(), any());
        assertThat(forwardRows()).singleElement().satisfies(l -> {
            assertThat(l.getAlertEventId()).isEqualTo(1L);
            assertThat(l.getEmailStatus()).isEqualTo(EscalationService.STATUS_FORWARD_ALREADY_MAILED);
        });
    }

    @Test
    @DisplayName("sahipler = damga + envanter SY / UG: alıcı takımı UG olan iletim kopyası ATLANIR (UG alarmı kendi postasıyla alıyor)")
    void inventoryOwnersSkipped() {
        AlertEvent e = event(1, false);
        e.setAlertType("EXPIRY");
        e.setDomain("shop.example.com");
        e.setContextJson("{}");
        item(10, 1);
        com.sitemonitor.repository.CertificateInventoryRepository inv = mock(com.sitemonitor.repository.CertificateInventoryRepository.class);
        com.sitemonitor.model.CertificateInventory row = new com.sitemonitor.model.CertificateInventory();
        row.setDomain("shop.example.com");
        row.setTeamId(TEAM);
        row.setUgTeamId(RT_B);
        when(inv.findByDomain("shop.example.com")).thenReturn(Optional.of(row));
        service.setInventoryRepo(inv);
        service.setForwardRouting(new ForwardFakes.Routing(Map.of(TEAM, List.of(
                ForwardFakes.target(RT_B, B_NAME, List.of("Ödeme"), List.of("b@x.com")),
                ForwardFakes.target(RT_C, "Takım C", List.of("Ödeme"), List.of("c@x.com"))))));

        service.run(AFTER);

        ArgumentCaptor<String[]> to = ArgumentCaptor.forClass(String[].class);
        verify(emailService, times(1)).sendHtml(to.capture(), any(), anyString(), any(), any(), anyList(), anyBoolean(), any());
        assertThat(to.getValue()).containsExactly("c@x.com");
        assertThat(forwardRows()).extracting(NotificationLog::getRecipientName).containsExactly("Takım C");
        verify(alertEventRepo, never()).setForwardEmailsIfNull(eq(1L), argThat(j -> j != null && j.contains("\"7\"")));

        // bağımsız alarmda envanter OKUNMAZ (sahip yalnız damga takımı)
        clearInvocations(inv);
        db.clear();
        logs.clear();
        AlertEvent s = event(2, false);
        item(11, 2);
        service.run(AFTER);
        verify(inv, never()).findByDomain(anyString());
        assertThat(s.getTeamId()).isEqualTo(TEAM);
    }

    // ── hata avı 2026-11-06 ─────────────────────────────────────────────────────────────────────────

    static final long UG = 43L;

    private void ugTeam() {
        Team t = new Team();
        t.setId(TEAM);
        t.setName("Ödeme");
        Team u = new Team();
        u.setId(UG);
        u.setName("Mobil Bankacılık (Dijital Kanallar / Uygulama Geliştirme)");
        when(teamRepo.findAllById(any())).thenReturn(List.of(t, u));
        when(escalationService.teamEmailsForMonitor(UG, null)).thenReturn(List.of("ug@x.com"));
    }

    private void itemFor(long id, long eventId, long team, boolean openingDeferred) {
        item(id, eventId);
        db.get(id).setTeamId(team);
        db.get(id).setOpeningDeferred(openingDeferred);
    }

    @Test
    @DisplayName("H1#1: SY ve UG özetleri aynı turda, ikisi de X'e iletiyor → X TEK iletim özeti alır (bant iki sahibi de söyler)")
    void twoOwnersSameRoundSingleForwardDigest() {
        ugTeam();
        event(1, false);
        itemFor(10, 1, TEAM, true);
        itemFor(11, 1, UG, true);
        service.setForwardRouting(new ForwardFakes.Routing(Map.of(
                TEAM, List.of(ForwardFakes.target(RT_B, B_NAME, List.of("Ödeme"), List.of("b@x.com"))),
                UG, List.of(ForwardFakes.target(RT_B, B_NAME, List.of("Mobil"), List.of("b@x.com"))))));

        service.run(AFTER);

        ArgumentCaptor<String[]> to = ArgumentCaptor.forClass(String[].class);
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(emailService, times(1)).sendHtml(to.capture(), any(), anyString(), any(), text.capture(), anyList(),
                anyBoolean(), any());
        assertThat(to.getValue()).containsExactly("b@x.com");
        assertThat(text.getValue()).contains("Ödeme").contains("Mobil Bankacılık (Dijital Kanallar / Uygulama Geliştirme)");
        assertThat(forwardRows()).singleElement().satisfies(l -> {
            assertThat(l.getAlertEventId()).isEqualTo(1L);
            assertThat(l.getRecipientName()).isEqualTo(B_NAME);
            assertThat(l.getEmailStatus()).isEqualTo("SENT");
        });
        verify(alertEventRepo, times(1)).setForwardEmailsIfNull(1L, "{\"7\":[\"b@x.com\"]}");
    }

    @Test
    @DisplayName("H1#3: iki alıcı takımda ORTAK adres alarmı bir kez alır (hedefler arası ortak küme); sahip adresi zaten düşer")
    void sharedAddressAcrossTargetsOnce() {
        event(1, false);
        item(10, 1);
        service.setForwardRouting(new ForwardFakes.Routing(Map.of(TEAM, List.of(
                ForwardFakes.target(RT_B, B_NAME, List.of("Ödeme"), List.of("ortak@x.com", "b@x.com")),
                ForwardFakes.target(RT_C, "Takım C", List.of("Ödeme"), List.of("ORTAK@x.com", "c@x.com", "team@x.com"))))));
        service.run(AFTER);
        ArgumentCaptor<String[]> to = ArgumentCaptor.forClass(String[].class);
        verify(emailService, times(2)).sendHtml(to.capture(), any(), anyString(), any(), any(), anyList(), anyBoolean(), any());
        assertThat(to.getAllValues()).extracting(a -> List.of(a))
                .containsExactly(List.of("ortak@x.com", "b@x.com"), List.of("c@x.com"));
    }

    @Test
    @DisplayName("H1#4: damga tekilleştirmesi yalnız açılışı ertelenen kayıtta — ertelenen günlük hatırlatma damgalı alıcıya da gider")
    void deferredReAlertReachesStampedRecipient() {
        AlertEvent e1 = event(1, false);
        e1.setForwardEmails("{\"7\":[\"b@x.com\"]}");
        item(10, 1);
        db.get(10L).setOpeningDeferred(false);
        db.get(10L).setFirstTrigger("DAILY_REALERT");
        service.setForwardRouting(new ForwardFakes.Routing(Map.of(TEAM, List.of(
                ForwardFakes.target(RT_B, B_NAME, List.of("Ödeme"), List.of("b@x.com"))))));
        service.run(AFTER);
        ArgumentCaptor<String[]> to = ArgumentCaptor.forClass(String[].class);
        verify(emailService).sendHtml(to.capture(), any(), anyString(), any(), any(), anyList(), anyBoolean(), any());
        assertThat(to.getValue()).containsExactly("b@x.com");
        assertThat(forwardRows()).singleElement().satisfies(l -> assertThat(l.getEmailStatus()).isEqualTo("SENT"));
    }
}
