package com.sitemonitor.service;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.AlertStorm;
import com.sitemonitor.model.NotificationLog;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.NotificationLogRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.service.forward.ForwardTarget;
import com.sitemonitor.service.mail.MailKit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Fırtına postalarının iletim eşleniği (alarm iletimi, 2026-11-05): sahip takımın iletim alıcısına AYRI fırtına postası —
 * YALNIZ ona iletilen üyelerle (takım yalıtımı), başta bant; üye başına FORWARD satırı; açılışta üyelere iletim damgası;
 * çözüm damgalı adreslere; push varsayılan kipte alıcı takıma tek toplu bildirim, bireysel kipte üye başına. Yönlendirme yokken
 * fırtına yolu bugünkü gibi.
 */
class StormForwardTest {

    static final long OWNER = 7L, OTHER = 10L, RT_B = 8L, RT_C = 9L;
    static final String OWNER_MAIL = "takim7@example.com", B_MAIL = "b@example.com", C_MAIL = "c@example.com";

    private StormService storm;
    private EmailNotificationService mail;
    private AlertEventRepository alertRepo;
    private NotificationLogRepository logRepo;
    private AppSettingsService appSettings;
    private final List<NotificationLog> logs = new ArrayList<>();
    private ForwardFakes.Push push;

    @SuppressWarnings("unchecked")
    private static <T> T build(Class<T> type, Map<Class<?>, Object> provided) throws Exception {
        Constructor<?> c = Arrays.stream(type.getConstructors())
                .max(Comparator.comparingInt(Constructor::getParameterCount)).orElseThrow();
        Class<?>[] pt = c.getParameterTypes();
        Object[] args = new Object[pt.length];
        for (int i = 0; i < pt.length; i++) args[i] = provided.containsKey(pt[i]) ? provided.get(pt[i]) : mock(pt[i]);
        return (T) c.newInstance(args);
    }

    @BeforeEach
    void setUp() throws Exception {
        appSettings = mock(AppSettingsService.class);
        lenient().when(appSettings.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> i.getArgument(1));
        lenient().when(appSettings.getInt(anyString(), anyInt())).thenAnswer(i -> i.getArgument(1));
        lenient().when(appSettings.getString(anyString(), any())).thenAnswer(i -> i.getArgument(1));
        TeamRepository teams = mock(TeamRepository.class);
        team(teams, OWNER, "Takım 7", OWNER_MAIL);
        team(teams, OTHER, "Takım 10", "takim10@example.com");
        team(teams, RT_B, "Ağ Operasyon Merkezi — 7/24 İzleme", B_MAIL);
        team(teams, RT_C, "Takım C", C_MAIL);
        mail = mock(EmailNotificationService.class);
        lenient().when(mail.buildStormAlertHtml(anyInt(), any(), any(), any(), anyList(), anyInt(), any()))
                .thenAnswer(i -> "<html>" + MailKit.bodyOpen() + "fırtına " + i.getArgument(0) + "</td></tr></html>");
        lenient().when(mail.buildStormAlertText(anyInt(), any(), any(), any(), anyList(), anyInt(), any())).thenReturn("metin");
        lenient().when(mail.buildStormRecoveryHtml(anyInt(), anyInt(), any(), any(), any(), anyList(), anyInt(), anyList()))
                .thenAnswer(i -> "<html>" + MailKit.bodyOpen() + "çözüm " + i.getArgument(0) + "/" + i.getArgument(1) + "</td></tr></html>");
        lenient().when(mail.buildStormRecoveryText(anyInt(), anyInt(), any(), any(), any(), anyList(), anyInt(), anyList()))
                .thenReturn("metin");
        lenient().when(mail.sendHtml(any(String[].class), any(), anyString(), any(), any(), anyList(), anyBoolean(), any()))
                .thenReturn("SENT");
        alertRepo = mock(AlertEventRepository.class);
        lenient().when(alertRepo.setForwardEmailsIfNull(any(), any())).thenReturn(1);
        lenient().when(alertRepo.setForwardEmailsIfUnchanged(any(), any(), any())).thenReturn(1);
        Map<Class<?>, Object> provided = new HashMap<>();
        provided.put(AppSettingsService.class, appSettings);
        provided.put(TeamRepository.class, teams);
        provided.put(EmailNotificationService.class, mail);
        provided.put(AlertEventRepository.class, alertRepo);
        storm = build(StormService.class, provided);
        logRepo = mock(NotificationLogRepository.class);
        lenient().when(logRepo.save(any())).thenAnswer(i -> { logs.add(i.getArgument(0)); return i.getArgument(0); });
        ReflectionTestUtils.setField(storm, "notificationLogRepo", logRepo);
        ReflectionTestUtils.setField(storm, "objectMapper", new com.fasterxml.jackson.databind.ObjectMapper());
        push = new ForwardFakes.Push();
    }

    private static void team(TeamRepository repo, long id, String name, String email) {
        Team t = new Team();
        t.setId(id); t.setName(name); t.setEmail(email);
        lenient().when(repo.findById(id)).thenReturn(Optional.of(t));
    }

    private static AlertEvent member(long id, long team, String ctx) {
        AlertEvent e = new AlertEvent();
        e.setId(id);
        e.setDomain("https://svc" + id + ".example.com");
        e.setAlertType(EscalationService.TYPE_HTTP_DOWN);
        e.setAlertLevel(id == 2 ? "CRITICAL" : "WARNING");
        e.setTeamId(team);
        e.setContextJson(ctx != null ? ctx : "{\"monitor_id\":" + (100 + id) + "}");
        e.setCreatedAt("2026-10-29T08:0" + id + ":00");
        e.setResolved(false);
        e.setAcknowledged(false);
        e.setStormId(55L);
        return e;
    }

    private static AlertStorm stormRow() {
        AlertStorm s = new AlertStorm();
        s.setId(55L);
        s.setScopeKey("TEAM:7");
        s.setScopeType("TEAM");
        s.setCreatedAt("2026-10-29T08:00:00");
        s.setResolvedAt("2026-10-29T08:30:00");
        return s;
    }

    /** B: üye 1 ve 2; C: yalnız üye 2 (izleme 102). */
    private ForwardFakes.Routing routing() {
        ForwardFakes.Routing r = new ForwardFakes.Routing(Map.of(OWNER, List.of(
                ForwardFakes.target(RT_B, "Ağ Operasyon Merkezi — 7/24 İzleme", List.of("Takım 7"), List.of(B_MAIL)),
                ForwardFakes.target(RT_C, "Takım C", List.of("Takım 7"), List.of(C_MAIL, OWNER_MAIL)))));
        r.filter = (ref, t) -> t.recipientTeamId() != RT_C || Long.valueOf(102L).equals(ref.monitorId());
        storm.setForwarding(r, push);
        return r;
    }

    private List<NotificationLog> forwardRows() {
        return logs.stream().filter(l -> EscalationService.ROLE_FORWARD.equals(l.getRecipientRole())).toList();
    }

    private List<List<String>> sentTo() {
        ArgumentCaptor<String[]> to = ArgumentCaptor.forClass(String[].class);
        verify(mail, atLeastOnce()).sendHtml(to.capture(), any(), anyString(), any(), any(), anyList(), anyBoolean(), any());
        return to.getAllValues().stream().map(List::of).toList();
    }

    @Test
    @DisplayName("yönlendirme yok → yalnız sahip fırtına postası (bugünkü), iletim satırı / damga yok")
    void noRouting() {
        ReflectionTestUtils.invokeMethod(storm, "sendStormAlert", stormRow(), List.of(member(1, OWNER, null)), "INITIAL");
        assertThat(sentTo()).containsExactly(List.of(OWNER_MAIL));
        assertThat(forwardRows()).isEmpty();
        verify(alertRepo, never()).setForwardEmailsIfNull(any(), any());
    }

    @Test
    @DisplayName("açılış: alıcı takım başına ayrı posta YALNIZ kendi üyeleriyle; bant; üye başına satır; damga; toplu push")
    void openingIsolatedPerRecipient() {
        routing();
        AlertEvent m1 = member(1, OWNER, null), m2 = member(2, OWNER, null), other = member(3, OTHER, null);
        ReflectionTestUtils.invokeMethod(storm, "sendStormAlert", stormRow(), List.of(m1, m2, other), "INITIAL");

        assertThat(sentTo()).containsExactly(List.of(OWNER_MAIL), List.of("takim10@example.com"), List.of(B_MAIL), List.of(C_MAIL));
        ArgumentCaptor<Integer> count = ArgumentCaptor.forClass(Integer.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> targets = ArgumentCaptor.forClass(List.class);
        verify(mail, times(4)).buildStormAlertHtml(count.capture(), any(), any(), any(), targets.capture(), anyInt(), any());
        // B iki üyeyi (7 takımının), C yalnız 2 numaralı üyeyi görür — diğer takımın üyesi (3) hiçbir iletimde YOK
        assertThat(count.getAllValues().subList(2, 4)).containsExactly(2, 1);
        assertThat(targets.getAllValues().get(2)).containsExactly(m1.getDomain(), m2.getDomain());
        assertThat(targets.getAllValues().get(3)).containsExactly(m2.getDomain());
        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        verify(mail, times(4)).sendHtml(any(String[].class), any(), anyString(), html.capture(), any(), anyList(), anyBoolean(), any());
        assertThat(html.getAllValues().get(0)).doesNotContain("İletilen alarm");
        assertThat(html.getAllValues().get(2)).contains("İletilen alarm").contains("Bu alarmlar Takım 7 takımının izlemelerine ait");

        List<NotificationLog> rows = forwardRows();
        assertThat(rows).extracting(NotificationLog::getAlertEventId).containsExactly(1L, 2L, 2L);
        assertThat(rows).extracting(NotificationLog::getRecipientName)
                .containsExactly("Ağ Operasyon Merkezi — 7/24 İzleme", "Ağ Operasyon Merkezi — 7/24 İzleme", "Takım C");
        assertThat(rows).extracting(NotificationLog::getTrigger).containsOnly(StormService.TRIGGER_STORM_INITIAL);
        verify(alertRepo).setForwardEmailsIfNull(1L, "{\"8\":[\"" + B_MAIL + "\"]}");
        verify(alertRepo).setForwardEmailsIfNull(2L, "{\"8\":[\"" + B_MAIL + "\"],\"9\":[\"" + C_MAIL + "\"]}");
        verify(alertRepo, never()).setForwardEmailsIfNull(eq(3L), any());

        assertThat(push.stormCalls).hasSize(2);
        assertThat(push.stormCalls.get(0)).satisfies(c -> {
            assertThat(c.target().recipientTeamId()).isEqualTo(RT_B);
            assertThat(c.memberCount()).isEqualTo(2);
            assertThat(c.level()).isEqualTo("CRITICAL");
            assertThat(c.trigger()).isEqualTo("INITIAL");
            assertThat(c.owner()).isEqualTo("Takım 7");
        });
        assertThat(push.stormCalls.get(1).memberCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("e-postası kapalı üyeler: iletim postası yok, 'kanal kapalı' satırı, damga yok; push yine")
    void mailDisabledMembers() {
        routing();
        AlertEvent m1 = member(1, OWNER, "{\"monitor_id\":101,\"mail_disabled\":true}");
        ReflectionTestUtils.invokeMethod(storm, "sendStormAlert", stormRow(), List.of(m1), "INITIAL");
        verify(mail, never()).sendHtml(any(String[].class), any(), anyString(), any(), any(), anyList(), anyBoolean(), any());
        assertThat(forwardRows()).singleElement().satisfies(l ->
                assertThat(l.getEmailStatus()).isEqualTo("SKIPPED: e-posta kanalı kapalı"));
        verify(alertRepo, never()).setForwardEmailsIfNull(any(), any());
        assertThat(push.stormCalls).hasSize(1);
    }

    @Test
    @DisplayName("bireysel push kipi: açılışta toplu iletim push'u yok; günlük tekrarda açık + onaysız üye başına iletim push'u")
    void individualPushMode() {
        when(appSettings.getBoolean(eq(StormService.KEY_PUSH_INDIVIDUAL), anyBoolean())).thenReturn(true);
        routing();
        AlertEvent m1 = member(1, OWNER, null), m2 = member(2, OWNER, null);
        m2.setAcknowledged(true);
        ReflectionTestUtils.invokeMethod(storm, "sendStormAlert", stormRow(), List.of(m1, m2), "INITIAL");
        assertThat(push.stormCalls).isEmpty();
        assertThat(push.calls).isEmpty();
        ReflectionTestUtils.invokeMethod(storm, "sendStormAlert", stormRow(), List.of(m1, m2), "DAILY_REALERT");
        assertThat(push.stormCalls).isEmpty();
        assertThat(push.calls).singleElement().satisfies(c -> {
            assertThat(c.alertEventId()).isEqualTo(1L);
            assertThat(c.trigger()).isEqualTo("DAILY_REALERT");
            assertThat(c.targets()).extracting(ForwardTarget::recipientTeamId).containsExactly(RT_B);
        });
        assertThat(forwardRows()).extracting(NotificationLog::getTrigger).contains(StormService.TRIGGER_STORM_REALERT);
    }

    @Test
    @DisplayName("çözüm: damgalı adreslere — adres başına yalnız kendi kurtulan / hâlâ-down üyeleri; sahip adresi tekrar almaz")
    void recoveryFromStamps() {
        routing();
        AlertEvent m1 = member(1, OWNER, null), m2 = member(2, OWNER, null), down = member(4, OWNER, null);
        m1.setResolved(true);
        m2.setResolved(true);
        m1.setForwardEmails("{\"8\":[\"" + B_MAIL + "\"]}");
        m2.setForwardEmails("{\"8\":[\"" + B_MAIL + "\"],\"9\":[\"" + C_MAIL + "\",\"" + OWNER_MAIL + "\"]}");
        down.setForwardEmails("{\"8\":[\"" + B_MAIL + "\"]}");
        ReflectionTestUtils.invokeMethod(storm, "sendStormRecovery", stormRow(), List.of(m1, m2), List.of(down));

        assertThat(sentTo()).containsExactly(List.of(OWNER_MAIL), List.of(B_MAIL), List.of(C_MAIL));
        ArgumentCaptor<Integer> rec = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> stillDown = ArgumentCaptor.forClass(Integer.class);
        verify(mail, times(3)).buildStormRecoveryHtml(rec.capture(), stillDown.capture(), any(), any(), any(), anyList(), anyInt(),
                anyList());
        assertThat(rec.getAllValues()).containsExactly(2, 2, 1);
        assertThat(stillDown.getAllValues()).containsExactly(1, 1, 0);
        List<NotificationLog> rows = forwardRows();
        assertThat(rows).extracting(NotificationLog::getAlertEventId).containsExactly(1L, 2L, 2L);
        assertThat(rows).extracting(NotificationLog::getTrigger).containsOnly(StormService.TRIGGER_STORM_RESOLVE);
        assertThat(rows.get(2).getRecipientName()).isEqualTo("Takım C");
        // push: kurtulan üyelerin canlı iletim hedefleri → alıcı takım başına toplu çözüm bildirimi
        assertThat(push.stormCalls).extracting(ForwardFakes.Push.StormCall::trigger).containsOnly("RESOLVE");
        assertThat(push.stormCalls).extracting(c -> c.target().recipientTeamId()).containsExactly(RT_B, RT_C);
    }

    // ── 2026-11-06 inceleme düzeltmeleri ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("çözüm push'u: iletim fırtına sürerken kaldırıldıysa da açılış push'unu almış alıcı takıma 'fırtına bitti' gider")
    void recoveryPushReachesPriorRecipients() {
        // canlı yönlendirmede OWNER'ın iletimi YOK (kaldırıldı); bu fırtınada RT_B'ye iletim push'u gönderilmişti
        storm.setForwarding(new ForwardFakes.Routing(Map.of()), push);
        push.priorStormTeams.put(55L, new LinkedHashSet<>(List.of(RT_B, OWNER)));
        AlertEvent m1 = member(1, OWNER, null);
        m1.setResolved(true);
        ReflectionTestUtils.invokeMethod(storm, "sendStormRecovery", stormRow(), List.of(m1), List.of());
        assertThat(push.stormCalls).singleElement().satisfies(c -> {
            assertThat(c.trigger()).isEqualTo("RESOLVE");
            assertThat(c.target().recipientTeamId()).isEqualTo(RT_B);
            assertThat(c.target().recipientTeamName()).isEqualTo("Ağ Operasyon Merkezi — 7/24 İzleme");
            assertThat(c.target().ownerTeamIds()).containsExactly(OWNER);
            assertThat(c.ownerTeamId()).isEqualTo(OWNER);
            assertThat(c.owner()).isEqualTo("Takım 7");
        });

        // canlı hedef varsa önceki alıcı İKİNCİ kez eklenmez
        push.stormCalls.clear();
        routing();
        push.priorStormTeams.put(55L, Set.of(RT_B));
        AlertEvent m2 = member(2, OWNER, null);
        m2.setResolved(true);
        ReflectionTestUtils.invokeMethod(storm, "sendStormRecovery", stormRow(), List.of(m1, m2), List.of());
        assertThat(push.stormCalls).extracting(c -> c.target().recipientTeamId()).containsExactly(RT_B, RT_C);
    }

    @Test
    @DisplayName("toplu fırtına push'u sahip takımı söyler (tek dağıtım → o takım) — sahip kararı yalnız onun satırından okunur")
    void stormNoticeCarriesOwnerTeam() {
        routing();
        ReflectionTestUtils.invokeMethod(storm, "sendStormAlert", stormRow(), List.of(member(1, OWNER, null)), "INITIAL");
        assertThat(push.stormCalls).singleElement().satisfies(c -> assertThat(c.ownerTeamId()).isEqualTo(OWNER));
    }

    @Test
    @DisplayName("bireysel kip: üyenin SY ve UG dağıtımları aynı alıcıya iletiyorsa hedefler BİRLEŞİR (UG'nin seçili üyeleri düşmez)")
    void individualModeMergesSameRecipient() {
        when(appSettings.getBoolean(eq(StormService.KEY_PUSH_INDIVIDUAL), anyBoolean())).thenReturn(true);
        com.sitemonitor.repository.CertificateInventoryRepository inv =
                (com.sitemonitor.repository.CertificateInventoryRepository) ReflectionTestUtils.getField(storm, "inventoryRepo");
        com.sitemonitor.model.CertificateInventory row = new com.sitemonitor.model.CertificateInventory();
        row.setDomain("shop.example.com");
        row.setTeamId(OWNER);
        row.setUgTeamId(OTHER);
        when(inv.findByDomain("shop.example.com")).thenReturn(Optional.of(row));
        storm.setForwarding(new ForwardFakes.Routing(Map.of(
                OWNER, List.of(ForwardFakes.target(RT_B, "Ağ Operasyon", List.of("Takım 7"), List.of(B_MAIL))),
                OTHER, List.of(new ForwardTarget(RT_B, "Ağ Operasyon", List.of(OTHER), List.of("Takım 10"), List.of(2L),
                        List.of("ayse@example.com"), List.of(50L), false)))), push);
        AlertEvent cert = new AlertEvent();
        cert.setId(5L);
        cert.setDomain("shop.example.com");
        cert.setAlertType("EXPIRY");
        cert.setAlertLevel("HIGH");
        cert.setContextJson("{}");
        cert.setCreatedAt("2026-10-29T08:05:00");
        cert.setResolved(false);
        cert.setAcknowledged(false);
        cert.setStormId(55L);
        ReflectionTestUtils.invokeMethod(storm, "sendStormAlert", stormRow(), List.of(cert), "DAILY_REALERT");
        assertThat(push.calls).singleElement().satisfies(c -> {
            assertThat(c.alertEventId()).isEqualTo(5L);
            assertThat(c.targets()).singleElement().satisfies(t -> {
                assertThat(t.recipientTeamId()).isEqualTo(RT_B);
                assertThat(t.wholeTeam()).isTrue();
                assertThat(t.memberUserIds()).containsExactly(50L);
                assertThat(t.ownerTeamIds()).containsExactly(OTHER);
            });
        });
    }
}
