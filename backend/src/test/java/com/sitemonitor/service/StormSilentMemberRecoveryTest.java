package com.sitemonitor.service;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.AlertStorm;
import com.sitemonitor.service.noc.NocNotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * O-b2 (BUG_REGRESYON_2026-09-29b): SESSİZ kapanan fırtına üyesi (tür bildirimleri kapatıldı / izleme silindi /
 * duraklatıldı) KURTULMADI — fırtına çözümü onu "N monitör kurtarıldı" sayıp takıma e-posta / push / webhook / 7-24
 * "ÇÖZÜLDÜ" göndermemeli. Tüm kurtulanlar sessizse fırtına bildirimsiz kapanır.
 */
class StormSilentMemberRecoveryTest {

    private StormService storm;
    private NocNotificationService noc;
    private EscalationService escalation;

    @SuppressWarnings("unchecked")
    private static <T> T build(Class<T> type, Map<Class<?>, Object> provided) throws Exception {
        Constructor<?> c = java.util.Arrays.stream(type.getConstructors())
                .max(Comparator.comparingInt(Constructor::getParameterCount)).orElseThrow();
        Class<?>[] pt = c.getParameterTypes();
        Object[] args = new Object[pt.length];
        for (int i = 0; i < pt.length; i++) args[i] = provided.containsKey(pt[i]) ? provided.get(pt[i]) : mock(pt[i]);
        return (T) c.newInstance(args);
    }

    @BeforeEach
    void setUp() throws Exception {
        AppSettingsService appSettings = mock(AppSettingsService.class);
        lenient().when(appSettings.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> i.getArgument(1));
        lenient().when(appSettings.getInt(anyString(), anyInt())).thenAnswer(i -> i.getArgument(1));
        lenient().when(appSettings.getString(anyString(), any())).thenAnswer(i -> i.getArgument(1));
        Map<Class<?>, Object> provided = new HashMap<>();
        provided.put(AppSettingsService.class, appSettings);
        storm = build(StormService.class, provided);
        noc = mock(NocNotificationService.class);
        ReflectionTestUtils.setField(storm, "nocNotifications", noc);
        escalation = build(EscalationService.class, provided);
    }

    private static AlertEvent member(long id, boolean silent) {
        AlertEvent e = new AlertEvent();
        e.setId(id);
        e.setDomain("senaryo-" + id);
        e.setAlertType(EscalationService.TYPE_SCRIPTED_FAIL);
        e.setAlertLevel("WARNING");
        e.setTeamId(7L);
        e.setResolved(true);
        e.setResolvedSilently(silent ? Boolean.TRUE : null);
        e.setStormId(55L);
        return e;
    }

    private static AlertStorm stormRow() {
        AlertStorm s = new AlertStorm();
        s.setId(55L);
        s.setScopeKey("TEAM:7");
        s.setCreatedAt("2026-09-29T08:00:00");
        s.setResolvedAt("2026-09-29T08:30:00");
        return s;
    }

    @Test
    @DisplayName("2026-10-12: fırtına çözüm postası, kurtulan üyeyi NÖBETÇİ olarak almış adreslere de gider (tekil, takım adresiyle birleşik)")
    void recoveryMailReachesOncallWhoGotTheAlarm() {
        assertThat(StormService.mergeAddresses(List.of("takim@example.com", "TAKIM@example.com"), List.of()))
                .containsExactly("takim@example.com");
        AlertEvent m = member(3L, false);
        m.setAlertLevel("CRITICAL");
        m.setOncallEmails("nobetci@example.com,takim@example.com");
        AlertEvent n = member(4L, false);
        n.setOncallEmails(" Nobetci@Example.com ");
        // takım postası gidecek: takım adresi o postanın üyeleriyle tohumlanır → nöbetçi eki olarak bir daha eklenmez
        java.util.Map<String, java.util.Set<Long>> seeded = new java.util.HashMap<>();
        StormService.seedTeamMail(seeded, List.of("takim@example.com"), List.of(m, n));
        // nöbetçi gönderimin BÜTÜN üyelerini almış → takım postasına katılır
        StormService.OncallPlan all = StormService.planOncall(List.of(m, n), List.of(), seeded);
        assertThat(all.joinTeam()).containsExactly("nobetci@example.com");
        assertThat(all.separate()).isEmpty();
        assertThat(StormService.mergeAddresses(List.of("takim@example.com"), all.joinTeam()))
                .containsExactly("takim@example.com", "nobetci@example.com");

        EmailNotificationService mail = (EmailNotificationService) ReflectionTestUtils.getField(storm, "emailService");
        lenient().when(mail.sendHtml(any(), any(), anyString(), any(), any(), anyList(), anyBoolean(), any())).thenReturn("SENT");
        ReflectionTestUtils.invokeMethod(storm, "sendStormRecovery", stormRow(), List.of(m), List.of());
        ArgumentCaptor<String[]> to = ArgumentCaptor.forClass(String[].class);
        verify(mail).sendHtml(to.capture(), any(), anyString(), any(), any(), anyList(), anyBoolean(), any());
        assertThat(to.getValue()).contains("nobetci@example.com");
    }

    @Test
    @DisplayName("inceleme 4: nöbetçi YALNIZ açılışını aldığı üyeleri görür — kısmi nöbetçi ayrı posta; ortak üye iki gönderimde tek kez bildirilir")
    void oncallSeesOnlyTheMembersItGot() {
        // A gönderimi: a1 (yalnız A, CRITICAL — A nöbetçisi), shared (SY=A, UG=B — iki nöbetçi), a2 (WARNING — nöbetçi yok)
        AlertEvent a1 = member(11L, false);
        a1.setOncallEmails("a-nobet@example.com");
        AlertEvent shared = member(12L, false);
        shared.setOncallEmails("a-nobet@example.com,b-nobet@example.com");
        AlertEvent a2 = member(13L, false);
        java.util.Map<String, java.util.Set<Long>> reported = new java.util.HashMap<>();
        StormService.OncallPlan planA = StormService.planOncall(List.of(a1, shared, a2), List.of(), reported);
        // a-nobet a2'yi almadı → takım postasına KATILMAZ (a2 hedefini görmez), kendi iki üyesiyle ayrı posta
        assertThat(planA.joinTeam()).isEmpty();
        assertThat(planA.separate()).hasSize(2);
        StormService.OncallMail aMail = planA.separate().stream().filter(x -> x.to().contains("a-nobet@example.com")).findFirst().orElseThrow();
        assertThat(aMail.recovered()).extracting(AlertEvent::getId).containsExactly(11L, 12L);
        StormService.OncallMail bMail = planA.separate().stream().filter(x -> x.to().contains("b-nobet@example.com")).findFirst().orElseThrow();
        // B'nin nöbetçisi A'ya özel hedefleri (a1, a2) GÖRMEZ — yalnız ortak üye
        assertThat(bMail.recovered()).extracting(AlertEvent::getId).containsExactly(12L);
        // B gönderimi: ortak üye + b1 (yalnız B). b-nobet ortak üyeyi zaten aldı → yalnız b1 için bildirilir
        AlertEvent b1 = member(14L, false);
        b1.setOncallEmails("b-nobet@example.com");
        StormService.OncallPlan planB = StormService.planOncall(List.of(shared, b1), List.of(), reported);
        assertThat(planB.joinTeam()).isEmpty();
        assertThat(planB.separate()).singleElement().satisfies(x -> {
            assertThat(x.to()).containsExactly("b-nobet@example.com");
            assertThat(x.recovered()).extracting(AlertEvent::getId).containsExactly(14L);
        });
        // aynı plan tekrar: her şey bildirildi → hiçbir ek yok
        StormService.OncallPlan again = StormService.planOncall(List.of(shared, b1), List.of(), reported);
        assertThat(again.joinTeam()).isEmpty();
        assertThat(again.separate()).isEmpty();
        // hâlâ-down üye: nöbetçinin almadığı hâlâ-down üye varsa takım postasına katılmaz; aldığı hâlâ-down ayrı postada listelenir
        AlertEvent down = member(15L, false);
        down.setOncallEmails("c-nobet@example.com");
        AlertEvent rec = member(16L, false);
        rec.setOncallEmails("c-nobet@example.com");
        AlertEvent otherDown = member(17L, false);
        StormService.OncallPlan planC = StormService.planOncall(List.of(rec), List.of(down, otherDown), new java.util.HashMap<>());
        assertThat(planC.joinTeam()).isEmpty();
        assertThat(planC.separate()).singleElement().satisfies(x -> {
            assertThat(x.recovered()).extracting(AlertEvent::getId).containsExactly(16L);
            assertThat(x.stillDown()).extracting(AlertEvent::getId).containsExactly(15L);
        });
    }

    @Test
    @DisplayName("inceleme 5: bir takımın adresi BAŞKA takımın nöbetçisi de olsa o takımın üyelerinin çözümünü kaçırmaz; UG = SY çift sayılmaz")
    void teamAddressThatIsAlsoOncallStillGetsItsMembers() {
        // X: B takımının adresi; A'nın nöbetçisi olarak A'ya özel M1, M2'nin açılışını aldı
        AlertEvent m1 = member(31L, false);
        m1.setOncallEmails("x@example.com");
        AlertEvent m2 = member(32L, false);
        m2.setOncallEmails("x@example.com");
        AlertEvent bOnly = member(33L, false);
        java.util.Map<String, java.util.Set<Long>> reported = new java.util.HashMap<>();
        StormService.seedTeamMail(reported, List.of("x@example.com"), List.of(bOnly));   // B'nin postası X'e gider (yalnız B üyesi)
        StormService.OncallPlan planA = StormService.planOncall(List.of(m1, m2), List.of(), reported);
        assertThat(planA.joinTeam()).containsExactly("x@example.com");   // A'nın bütün üyelerini almış → A postasına katılır
        // B'nin hiç kurtulanı yoksa (posta gitmez → tohum yok) X yine A üyelerini öğrenir
        StormService.OncallPlan noB = StormService.planOncall(List.of(m1, m2), List.of(), new java.util.HashMap<>());
        assertThat(noB.joinTeam()).containsExactly("x@example.com");
    }

    @Test
    @DisplayName("inceleme 4: kısmi nöbetçi uçtan uca — takım postası nöbetçisiz, nöbetçiye kendi üyesiyle ayrı posta + günlük satırı")
    void partialOncallGetsSeparateMail() {
        AlertEvent got = member(21L, false);
        got.setOncallEmails("nobet@example.com");
        AlertEvent notGot = member(22L, false);
        EmailNotificationService mail = (EmailNotificationService) ReflectionTestUtils.getField(storm, "emailService");
        lenient().when(mail.sendHtml(any(), any(), anyString(), any(), any(), anyList(), anyBoolean(), any())).thenReturn("SENT");
        ReflectionTestUtils.invokeMethod(storm, "sendStormRecovery", stormRow(), List.of(got, notGot), List.of());
        ArgumentCaptor<String[]> to = ArgumentCaptor.forClass(String[].class);
        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        verify(mail, org.mockito.Mockito.atLeastOnce()).sendHtml(to.capture(), any(), subject.capture(), any(), any(), anyList(), anyBoolean(), any());
        List<String[]> calls = to.getAllValues();
        assertThat(calls).anySatisfy(arr -> assertThat(arr).containsExactly("nobet@example.com"));
        assertThat(calls).noneSatisfy(arr -> {
            assertThat(arr).contains("nobet@example.com");
            assertThat(arr.length).isGreaterThan(1);
        });
        int idx = 0;
        for (int i = 0; i < calls.size(); i++) if (java.util.Arrays.asList(calls.get(i)).contains("nobet@example.com")) idx = i;
        assertThat(subject.getAllValues().get(idx)).contains("1 monitör kurtarıldı");
    }

    @Test
    @DisplayName("O-b2: kurtulanların TAMAMI sessiz kapandı → fırtına çözüm bildirimi (7/24 dâhil) HİÇ gönderilmez")
    void allSilent_noRecoveryNotification() {
        ReflectionTestUtils.invokeMethod(storm, "sendStormRecovery", stormRow(),
                List.of(member(1L, true), member(2L, true)), List.of());
        verifyNoInteractions(noc);
    }

    @Test
    @DisplayName("O-b2: karışık — yalnız GERÇEK kurtulan sayılır, sessiz üye çözüm bildirimine girmez")
    @SuppressWarnings("unchecked")
    void mixed_onlyRealRecoveriesCounted() {
        ReflectionTestUtils.invokeMethod(storm, "sendStormRecovery", stormRow(),
                List.of(member(1L, true), member(2L, false)), List.of());
        ArgumentCaptor<List<AlertEvent>> rec = ArgumentCaptor.forClass(List.class);
        verify(noc).onStormRecovered(any(), rec.capture(), anyList());
        assertThat(rec.getValue()).extracting(AlertEvent::getId).containsExactly(2L);
    }

    @Test
    @DisplayName("O-c1: bakım penceresinde GERÇEKTEN düzelen fırtına üyesi işaretlenmez → toplu çözüm (7/24 dâhil) gider")
    @SuppressWarnings("unchecked")
    void maintenanceRecovery_isNotSilenced_stormRecoveryGoes() {
        com.sitemonitor.repository.AlertEventRepository repo =
                (com.sitemonitor.repository.AlertEventRepository) ReflectionTestUtils.getField(escalation, "alertEventRepo");
        MaintenanceService maintenance = (MaintenanceService) ReflectionTestUtils.getField(escalation, "maintenanceService");
        AlertEvent open = member(4L, false);
        open.setResolved(false);
        when(maintenance.isUnderMaintenance("senaryo-4")).thenReturn(true);
        when(repo.findByDomainAndAlertTypeInAndResolvedFalse(eq("senaryo-4"), anyCollection())).thenReturn(List.of(open));
        when(repo.save(any(AlertEvent.class))).thenAnswer(i -> i.getArgument(0));

        escalation.resolveMonitoringAlertsForDomain("senaryo-4", EscalationService.TYPE_SCRIPTED_FAIL);

        assertThat(open.getResolved()).isTrue();
        assertThat(open.getResolvedSilently()).as("bakım kurtarması susturma DEĞİL").isNotEqualTo(Boolean.TRUE);
        ReflectionTestUtils.invokeMethod(storm, "sendStormRecovery", stormRow(), List.of(open), List.of());
        ArgumentCaptor<List<AlertEvent>> rec = ArgumentCaptor.forClass(List.class);
        verify(noc).onStormRecovered(any(), rec.capture(), anyList());
        assertThat(rec.getValue()).extracting(AlertEvent::getId).containsExactly(4L);
    }

    @Test
    @DisplayName("Normal (bakım dışı) kurtarma fırtına üyesinde işaret YAZMAZ")
    void normalRecovery_doesNotMark() {
        com.sitemonitor.repository.AlertEventRepository repo =
                (com.sitemonitor.repository.AlertEventRepository) ReflectionTestUtils.getField(escalation, "alertEventRepo");
        StormService stormSvc = (StormService) ReflectionTestUtils.getField(escalation, "stormService");
        AlertEvent open = member(5L, false);
        open.setResolved(false);
        when(repo.findByDomainAndAlertTypeInAndResolvedFalse(eq("senaryo-5"), anyCollection())).thenReturn(List.of(open));
        when(repo.markResolvedIfOpen(eq(5L), anyString(), anyString())).thenReturn(1);
        when(repo.save(any(AlertEvent.class))).thenAnswer(i -> i.getArgument(0));
        when(stormSvc.isActive(55L)).thenReturn(true);   // fırtına üyesi → bireysel çözüm e-postası yok, toplu çözüm var

        escalation.resolveMonitoringAlertsForDomain("senaryo-5", EscalationService.TYPE_SCRIPTED_FAIL);

        assertThat(open.getResolved()).isTrue();
        assertThat(open.getResolvedSilently()).isNotEqualTo(Boolean.TRUE);
    }

    @Test
    @DisplayName("Susturma yolları (izleme silindi / duraklatıldı / tür kapatıldı — resolveOpenAlertsSilently) işareti yazar")
    void silentCloseMarksEvent() {
        com.sitemonitor.repository.AlertEventRepository repo =
                (com.sitemonitor.repository.AlertEventRepository) ReflectionTestUtils.getField(escalation, "alertEventRepo");
        AlertEvent open = member(3L, false);
        open.setResolved(false);
        when(repo.findByDomainAndAlertTypeInAndResolvedFalse(eq("senaryo-3"), anyCollection())).thenReturn(List.of(open));
        when(repo.save(any(AlertEvent.class))).thenAnswer(i -> i.getArgument(0));

        escalation.resolveOpenAlertsSilently("senaryo-3", List.of(EscalationService.TYPE_SCRIPTED_FAIL), "Sistem (test)");

        assertThat(open.getResolved()).isTrue();
        assertThat(open.getResolvedSilently()).isTrue();
    }
}
