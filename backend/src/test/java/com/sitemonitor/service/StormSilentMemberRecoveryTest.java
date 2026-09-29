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
