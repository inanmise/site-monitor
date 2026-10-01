package com.sitemonitor.service;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.AlertStorm;
import com.sitemonitor.model.AlertStormMember;
import com.sitemonitor.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Fırtına GÖZLEM KAYDI (2026-09-30, takım bazlı fırtına ekranı): açılışta anlık görüntü (takım, eşik, pencere, hedef
 * sayısı, tetikleyen alarm) terfi INSERT'inde ve üyelik satırları TEK JDBC batch'le yazılır; katılım ATTACH satırı bırakır;
 * toplu posta üyeleri tek UPDATE ile "duyuruldu" damgalar; kapanışta neden kodu + üye ayrılış türleri (RECOVERED / NOTIFIED /
 * UNLINKED) tek batch'le yazılır (2026-10-01, performans). Kayıt yazımı hata verirse karar mantığı aynen çalışır.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StormObservabilityRecordTest {

    @Mock AlertStormRepository stormRepo;
    @Mock AlertEventRepository alertEventRepo;
    @Mock AppSettingsService appSettings;
    @Mock EmailNotificationService emailService;
    @Mock WebhookService webhookService;
    @Mock TeamRepository teamRepo;
    @Mock EscalationContactRepository contactRepo;
    @Mock CertificateInventoryRepository inventoryRepo;
    @Mock JdbcTemplate jdbcTemplate;
    @Mock HttpMonitorRepository httpRepo;
    @Mock PortMonitorRepository portRepo;
    @Mock KeywordMonitorRepository keywordRepo;
    @Mock PingMonitorRepository pingRepo;
    @Mock DnsMonitorRepository dnsRepo;
    @Mock DomainMonitorRepository domainRepo;
    @Mock NotificationGroupService notificationGroups;

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    private StormService storm;

    @BeforeEach
    void setUp() {
        storm = new StormService(stormRepo, alertEventRepo, appSettings, emailService, webhookService,
                teamRepo, contactRepo, inventoryRepo, jdbcTemplate,
                httpRepo, portRepo, keywordRepo, pingRepo, dnsRepo, domainRepo, notificationGroups);
        when(appSettings.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> i.getArgument(1));
        when(appSettings.getInt(anyString(), anyInt())).thenAnswer(i -> i.getArgument(1));
        when(appSettings.getString(anyString(), anyString())).thenAnswer(i -> i.getArgument(1));
    }

    private static String ago(long minutes) { return ISO.format(Instant.now().minus(minutes, ChronoUnit.MINUTES)); }

    private static AlertEvent down(long id, String createdAt) {
        AlertEvent e = new AlertEvent();
        e.setId(id); e.setDomain("host" + id + ".example.com"); e.setAlertType(EscalationService.TYPE_SCRIPTED_FAIL);
        e.setAlertLevel("WARNING"); e.setTeamId(14L); e.setResolved(false); e.setCreatedAt(createdAt);
        return e;
    }

    private static AlertStorm teamStorm(long id, String createdAt, String lastMemberAt, String lastReAlertAt) {
        AlertStorm s = new AlertStorm();
        s.setId(id); s.setScopeKey("TEAM:14"); s.setScopeType("TEAM"); s.setResolved(false);
        s.setCreatedAt(createdAt); s.setLastMemberAt(lastMemberAt); s.setLastReAlertAt(lastReAlertAt);
        return s;
    }

    private void lockOk() {
        when(jdbcTemplate.update(startsWith("DELETE FROM scheduler_lock"), any(), any())).thenReturn(1);
        when(jdbcTemplate.update(startsWith("INSERT INTO scheduler_lock"), any(), any(), any())).thenReturn(1);
    }

    @Test
    @DisplayName("Terfi: anlık görüntü (takım 14, eşik 5, 5 hedef, tetikleyen #5) INSERT'te; üyeler TEK batch'le TRIGGER + PEER; açılış postası tek UPDATE ile duyurur")
    @SuppressWarnings("unchecked")
    void promotion_stampsSnapshotAndRecordsMembers() {
        AlertStorm created = teamStorm(9, ago(0), ago(0), ago(0));
        when(stormRepo.findByScopeKeyAndResolvedFalse("TEAM:14")).thenReturn(Optional.empty()).thenReturn(Optional.of(created));
        when(alertEventRepo.findOpenDownSince(anyCollection(), anyString()))
                .thenReturn(List.of(down(1, ago(1)), down(2, ago(1)), down(3, ago(1)), down(4, ago(1))));
        when(jdbcTemplate.update(startsWith("INSERT INTO alert_storms"), any(Object[].class))).thenReturn(1);

        assertThat(storm.evaluate(down(5, ago(0)), null)).isEqualTo(StormService.StormAction.SUPPRESSED);

        // anlık görüntü INSERT'in 8.–17. bağ değişkenleri: team, group, unit, value, effective, window, quiet, targets, peak, trigger
        verify(jdbcTemplate).update(startsWith("INSERT INTO alert_storms"), eq("TEAM:14"), eq("TEAM"), any(), any(), anyString(), anyString(), anyString(),
                eq(14L), isNull(), eq("COUNT"), eq(5), eq(5), eq(5), eq(5), eq(5), eq(5), eq(5L));

        ArgumentCaptor<List<Object[]>> cap = ArgumentCaptor.forClass(List.class);
        verify(jdbcTemplate, times(1)).batchUpdate(eq(StormService.SQL_MEMBER_INSERT), cap.capture());
        List<Object[]> rows = cap.getValue();
        assertThat(rows).hasSize(5).allMatch(r -> r[0].equals(9L));
        assertThat(rows.stream().filter(r -> AlertStormMember.JOIN_TRIGGER.equals(r[3])).map(r -> r[1])).containsExactly(5L);
        assertThat(rows.stream().filter(r -> AlertStormMember.JOIN_PEER.equals(r[3])).count()).isEqualTo(4);
        // açılış postası: tek UPDATE (IN 5 kimlik), üye başına sorgu yok
        verify(jdbcTemplate, times(1)).update(startsWith(StormService.SQL_MEMBER_ANNOUNCED_PREFIX), any(Object[].class));
    }

    @Test
    @DisplayName("Aktif fırtınaya katılım ATTACH üyelik satırı bırakır — TEK cümle (exists sorgusu yok; çakışma DB'de yutulur)")
    void attach_recordsMemberOnce() {
        when(stormRepo.findByScopeKeyAndResolvedFalse("TEAM:14")).thenReturn(Optional.of(teamStorm(5, ago(3), ago(1), ago(3))));

        assertThat(storm.evaluate(down(77, ago(0)), null)).isEqualTo(StormService.StormAction.SUPPRESSED);

        verify(jdbcTemplate).update(eq(StormService.SQL_MEMBER_INSERT), eq(5L), eq(77L), anyString(), eq(AlertStormMember.JOIN_ATTACH));
        verify(jdbcTemplate, never()).batchUpdate(anyString(), anyList());
    }

    @Test
    @DisplayName("Mühürle kapanış: resolve_reason=SEALED; kurtulan RECOVERED, duyurulmuş hâlâ-down NOTIFIED, duyurulmamış UNLINKED; tepe hedef sayısı yazılır")
    @SuppressWarnings("unchecked")
    void sealedResolve_writesReasonAndLeaveKinds() {
        lockOk();
        String announcedAt = ago(120);
        AlertStorm s = teamStorm(7, ago(600), ago(45), announcedAt);
        AlertEvent recovered = down(1, ago(600)); recovered.setResolved(true); recovered.setResolvedAt(ago(10));
        AlertEvent announced = down(2, ago(300));
        AlertEvent announced2 = down(3, ago(290));   // 3 hâlâ-down hedef: histerezis tabanı (3) DEĞİL, mühür kapatır
        AlertEvent late = down(414, ago(45));
        when(stormRepo.findByResolvedFalse()).thenReturn(List.of(s));
        when(alertEventRepo.findByStormId(7L)).thenReturn(List.of(recovered, announced, announced2, late));
        when(alertEventRepo.releaseFromStormAsNotified(anyLong(), eq(7L), eq(announcedAt))).thenReturn(1);

        storm.lifecycleSweep();

        assertThat(s.getResolved()).isTrue();
        assertThat(s.getResolveReason()).isEqualTo(StormService.RESOLVE_SEALED);
        assertThat(s.getPeakTargets()).isEqualTo(3);
        ArgumentCaptor<List<Object[]>> cap = ArgumentCaptor.forClass(List.class);
        verify(jdbcTemplate, times(1)).batchUpdate(eq(StormService.SQL_MEMBER_LEFT), cap.capture());   // TEK batch
        java.util.Map<Long, Object[]> byEvent = new java.util.HashMap<>();
        for (Object[] r : cap.getValue()) byEvent.put((Long) r[3], r);   // [left_at, kind, storm, event]
        assertThat(byEvent).hasSize(4);
        assertThat(byEvent.get(1L)).containsExactly(recovered.getResolvedAt(), AlertStormMember.LEAVE_RECOVERED, 7L, 1L);
        assertThat(byEvent.get(2L)[1]).isEqualTo(AlertStormMember.LEAVE_NOTIFIED);
        assertThat(byEvent.get(3L)[1]).isEqualTo(AlertStormMember.LEAVE_NOTIFIED);
        assertThat(byEvent.get(414L)[1]).isEqualTo(AlertStormMember.LEAVE_UNLINKED);
    }

    @Test
    @DisplayName("Histerezis tabanı kapanışı resolve_reason=FLOOR yazar")
    void floorResolve_writesReason() {
        lockOk();
        AlertStorm s = teamStorm(8, ago(30), ago(1), ago(30));
        AlertEvent a = down(1, ago(30)); a.setResolved(true); a.setResolvedAt(ago(2));
        AlertEvent b = down(2, ago(30)); b.setResolved(true); b.setResolvedAt(ago(2));
        when(stormRepo.findByResolvedFalse()).thenReturn(List.of(s));
        when(alertEventRepo.findByStormId(8L)).thenReturn(List.of(a, b, down(3, ago(30))));

        storm.lifecycleSweep();

        assertThat(s.getResolved()).isTrue();
        assertThat(s.getResolveReason()).isEqualTo(StormService.RESOLVE_FLOOR);
    }

    @Test
    @DisplayName("Üyelik yazımı hata verse de terfi ve katılım aynen çalışır — gözlem asla karar mantığını düşürmez")
    void recordingFailure_stillWorks() {
        when(jdbcTemplate.update(eq(StormService.SQL_MEMBER_INSERT), any(Object[].class))).thenThrow(new RuntimeException("tablo yok"));
        when(stormRepo.findByScopeKeyAndResolvedFalse("TEAM:14")).thenReturn(Optional.of(teamStorm(5, ago(3), ago(1), ago(3))));
        assertThat(storm.evaluate(down(77, ago(0)), null)).isEqualTo(StormService.StormAction.SUPPRESSED);
        verify(jdbcTemplate).update(startsWith("UPDATE alert_storms SET member_count"), anyString(), eq(5L));
    }

    @Test
    @DisplayName("Yaşam döngüsü tazelemesi varlığı save ETMEZ: hedefli UPDATE (member_count + tepe GREATEST) — katılımın damgası geri sarılmaz")
    void lifecycleRefresh_targetedUpdate() {
        lockOk();
        AlertStorm s = teamStorm(8, ago(30), ago(1), ago(1));
        when(stormRepo.findByResolvedFalse()).thenReturn(List.of(s));
        when(alertEventRepo.findByStormId(8L)).thenReturn(List.of(down(1, ago(30)), down(2, ago(30)), down(3, ago(30)), down(4, ago(20))));

        storm.lifecycleSweep();

        assertThat(s.getResolved()).isFalse();
        verify(stormRepo, never()).save(s);
        verify(jdbcTemplate).update(startsWith("UPDATE alert_storms SET member_count = ?, peak_targets = GREATEST"), eq(4), eq(4), eq(8L));
    }
}
