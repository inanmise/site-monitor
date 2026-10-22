package com.sitemonitor.service;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.AlertStorm;
import com.sitemonitor.model.AlertStormMember;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.AlertStormRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.DnsMonitorRepository;
import com.sitemonitor.repository.DomainMonitorRepository;
import com.sitemonitor.repository.EscalationContactRepository;
import com.sitemonitor.repository.HttpMonitorRepository;
import com.sitemonitor.repository.KeywordMonitorRepository;
import com.sitemonitor.repository.PingMonitorRepository;
import com.sitemonitor.repository.PortMonitorRepository;
import com.sitemonitor.repository.TeamRepository;
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

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Fırtına kapanışında "duyuruldu" kararı (2026-10-22, ALERTS-4 / ALERTS-5).
 *
 * <p>ALERTS-4: açılış postası GÖNDERİLİRKEN katılan üye postada yoktu ama {@code createdAt ≤ lastReAlertAt} (damga
 * postadan SONRA yazılıyor) kuralı onu "duyuruldu" sayıyor, kapanışta bildirildi diye bırakılıyordu → hiç bildirim yok.
 * Karar artık üyelik satırından ({@code announced_at}); satırı olmayan eski olay için eski kural.
 *
 * <p>ALERTS-5: özellik kapatılınca (disband) duyurulmuş hâlâ-düşük üyeler de bağdan koparılıp {@code lastReAlertAt=null}
 * ile ikinci bir INITIAL alıyordu; kapanışla aynı kural uygulanır.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StormAnnouncedMemberTest {

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
        e.setStormId(7L);
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
        when(jdbcTemplate.update(eq(StormService.SQL_CLOSE_CLAIM), any(Object[].class))).thenReturn(1);
        when(jdbcTemplate.update(eq(StormService.SQL_RECOVERY_CLAIM), any(Object[].class))).thenReturn(1);
        when(jdbcTemplate.update(eq(StormService.SQL_CLOSE_DONE), any(Object[].class))).thenReturn(1);
    }

    /** Üyelik satırları: kimlik → announced_at (null = satır var, duyurulmadı). */
    private void memberRows(long stormId, Map<Long, String> rows) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (var e : rows.entrySet()) {
            Map<String, Object> r = new HashMap<>();
            r.put("alert_event_id", e.getKey());
            r.put("announced_at", e.getValue());
            out.add(r);
        }
        when(jdbcTemplate.queryForList(StormService.SQL_MEMBER_ANNOUNCED_READ, stormId)).thenReturn(out);
    }

    // ── ALERTS-4: kapanış ────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Açılış postası sürerken katılan üye (createdAt < lastReAlertAt, satırı duyurusuz) → bağı koparılır (bireysel İLK), bildirildi SAYILMAZ")
    void close_memberJoinedDuringOpeningMail_isUnlinked() {
        lockOk();
        String mailStamp = ago(60);
        AlertStorm s = teamStorm(7, ago(62), ago(61), mailStamp);   // mühürlü (son üye 61 dk önce, pencere 5)
        when(stormRepo.findByResolvedFalse()).thenReturn(List.of(s));
        AlertEvent announced = down(1, ago(62));
        AlertEvent duringMail = down(2, ago(61));   // posta gönderilirken katıldı: postada yok
        AlertEvent third = down(3, ago(62));
        when(alertEventRepo.findByStormId(7L)).thenReturn(List.of(announced, duringMail, third));
        Map<Long, String> rows = new HashMap<>();
        rows.put(1L, mailStamp);
        rows.put(2L, null);
        rows.put(3L, mailStamp);
        memberRows(7L, rows);
        when(alertEventRepo.releaseFromStormAsNotified(anyLong(), eq(7L), eq(mailStamp))).thenReturn(1);

        storm.lifecycleSweep();

        verify(alertEventRepo).releaseFromStormAsNotified(1L, 7L, mailStamp);
        verify(alertEventRepo).releaseFromStormAsNotified(3L, 7L, mailStamp);
        verify(alertEventRepo, never()).releaseFromStormAsNotified(eq(2L), anyLong(), anyString());
        verify(alertEventRepo).unlinkFromStorm(2L, 7L);
    }

    @Test
    @DisplayName("Üyelik satırı olmayan eski olay → eski kural (createdAt ≤ lastReAlertAt duyuruldu sayılır)")
    void close_legacyMemberWithoutRow_usesCreatedAtRule() {
        lockOk();
        String mailStamp = ago(60);
        AlertStorm s = teamStorm(7, ago(62), ago(61), mailStamp);
        when(stormRepo.findByResolvedFalse()).thenReturn(List.of(s));
        AlertEvent legacyAnnounced = down(1, ago(62));
        AlertEvent legacyLate = down(2, ago(30));
        AlertEvent rowAnnounced = down(3, ago(62));
        when(alertEventRepo.findByStormId(7L)).thenReturn(List.of(legacyAnnounced, legacyLate, rowAnnounced));
        memberRows(7L, Map.of(3L, mailStamp));
        when(alertEventRepo.releaseFromStormAsNotified(anyLong(), eq(7L), eq(mailStamp))).thenReturn(1);

        storm.lifecycleSweep();

        verify(alertEventRepo).releaseFromStormAsNotified(1L, 7L, mailStamp);
        verify(alertEventRepo).releaseFromStormAsNotified(3L, 7L, mailStamp);
        verify(alertEventRepo).unlinkFromStorm(2L, 7L);
    }

    @Test
    @DisplayName("Üyelik satırları okunamadı → kapanış düşmez, eski kurala döner")
    void close_memberRowsUnreadable_fallsBack() {
        lockOk();
        String mailStamp = ago(60);
        AlertStorm s = teamStorm(7, ago(62), ago(61), mailStamp);
        when(stormRepo.findByResolvedFalse()).thenReturn(List.of(s));
        when(alertEventRepo.findByStormId(7L)).thenReturn(List.of(down(1, ago(62)), down(2, ago(62)), down(3, ago(30))));
        when(jdbcTemplate.queryForList(StormService.SQL_MEMBER_ANNOUNCED_READ, 7L)).thenThrow(new RuntimeException("db down"));
        when(alertEventRepo.releaseFromStormAsNotified(anyLong(), eq(7L), eq(mailStamp))).thenReturn(1);

        storm.lifecycleSweep();

        assertThat(s.getResolved()).isTrue();
        verify(alertEventRepo).releaseFromStormAsNotified(1L, 7L, mailStamp);
        verify(alertEventRepo).releaseFromStormAsNotified(2L, 7L, mailStamp);
        verify(alertEventRepo).unlinkFromStorm(3L, 7L);
    }

    @Test
    @DisplayName("announcedInStorm(satırlar): satır varsa YALNIZ announced_at'e bakar; satır yoksa createdAt kuralı")
    void announcedInStorm_memberRowsRule() {
        AlertEvent early = down(1, "2026-09-30T10:00:00");
        Map<Long, String> rows = new HashMap<>();
        rows.put(1L, null);
        assertThat(StormService.announcedInStorm(early, "2026-09-30T12:00:00", rows)).isFalse();
        rows.put(1L, "2026-09-30T12:00:00");
        assertThat(StormService.announcedInStorm(early, "2026-09-30T12:00:00", rows)).isTrue();
        assertThat(StormService.announcedInStorm(early, "2026-09-30T12:00:00", Map.of())).isTrue();
        assertThat(StormService.announcedInStorm(early, "2026-09-30T12:00:00", null)).isTrue();
        assertThat(StormService.announcedInStorm(down(2, "2026-09-30T13:00:00"), "2026-09-30T12:00:00", Map.of())).isFalse();
    }

    // ── ALERTS-5: disband ────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Özellik kapatıldı: duyurulmuş hâlâ-düşük üye bildirildi sayılır (ikinci INITIAL YOK); duyurulmamış olan bağdan kopar")
    void disband_announcedMemberIsReleasedNotUnlinked() {
        lockOk();
        when(appSettings.getBoolean(eq(StormService.KEY_ENABLED), anyBoolean())).thenReturn(false);
        String mailStamp = ago(20);
        AlertStorm s = teamStorm(7, ago(30), ago(1), mailStamp);
        when(stormRepo.findByResolvedFalse()).thenReturn(List.of(s));
        AlertEvent announced = down(1, ago(30));
        AlertEvent unannounced = down(2, ago(5));
        when(alertEventRepo.findByStormIdAndResolvedFalse(7L)).thenReturn(List.of(announced, unannounced));
        when(alertEventRepo.findByStormId(7L)).thenReturn(List.of(announced, unannounced));
        Map<Long, String> rows = new HashMap<>();
        rows.put(1L, mailStamp);
        rows.put(2L, null);
        memberRows(7L, rows);
        when(alertEventRepo.releaseFromStormAsNotified(1L, 7L, mailStamp)).thenReturn(1);

        storm.lifecycleSweep();

        assertThat(s.getResolveReason()).isEqualTo(StormService.RESOLVE_DISABLED);
        verify(alertEventRepo).releaseFromStormAsNotified(1L, 7L, mailStamp);
        verify(alertEventRepo, never()).unlinkFromStorm(eq(1L), anyLong());
        verify(alertEventRepo).unlinkFromStorm(2L, 7L);
    }

    // ── L-S1: grup başına fırtına modunda grup izleme KİMLİĞİNDEN çözülür ──────────────────────────────────

    @Test
    @DisplayName("L-S1: aynı URL'i iki takım izliyor — grup bağlamdaki monitor_id'den (B takımının grubu), kuruluş geneli 'ilk kayıt' değil")
    void perGroup_resolvesGroupByMonitorId() {
        when(appSettings.getBoolean(eq(StormService.KEY_PER_GROUP), anyBoolean())).thenReturn(true);
        com.sitemonitor.model.HttpMonitor teamA = new com.sitemonitor.model.HttpMonitor();
        teamA.setId(1L); teamA.setGroupName("A-Ödeme"); teamA.setTeamId(13L);
        com.sitemonitor.model.HttpMonitor teamB = new com.sitemonitor.model.HttpMonitor();
        teamB.setId(2L); teamB.setGroupName("B-Vitrin"); teamB.setTeamId(14L);
        when(httpRepo.findFirstByUrlOrderByIdAsc("https://ortak.example.com/")).thenReturn(Optional.of(teamA));
        when(httpRepo.findById(2L)).thenReturn(Optional.of(teamB));
        when(alertEventRepo.findOpenDownSinceInGroup(anyCollection(), anyString(), anyString())).thenReturn(List.of());

        AlertEvent e = down(50, ago(0));
        e.setStormId(null);
        e.setDomain("https://ortak.example.com/");
        e.setAlertType(EscalationService.TYPE_HTTP_DOWN);
        storm.evaluate(e, Map.of("monitor_id", 2L, "team_id", 14L));

        assertThat(e.getGroupName()).isEqualTo("B-Vitrin");
        verify(stormRepo).findByScopeKeyAndResolvedFalse("TEAM:14|GROUP:B-Vitrin");

        // Kimliği olmayan (eski) bağlam ya da bulunamayan kimlik → eski arama
        AlertEvent legacy = down(51, ago(0));
        legacy.setStormId(null);
        legacy.setDomain("https://ortak.example.com/");
        legacy.setAlertType(EscalationService.TYPE_HTTP_DOWN);
        storm.evaluate(legacy, Map.of("monitor_id", 99L));
        assertThat(legacy.getGroupName()).isEqualTo("A-Ödeme");
    }

    // ── ALERTS-4: terfi yarışını kaybeden yol üyelik satırı yazar ──────────────────────────────────────────

    @Test
    @DisplayName("Terfi yarışını kaybeden (created=false) alarm da ATTACH üyelik satırı yazar — kapanışta duyurusuz görünür")
    @SuppressWarnings("unchecked")
    void promotionLoser_recordsMemberRow() {
        when(stormRepo.findByScopeKeyAndResolvedFalse("TEAM:14"))
                .thenReturn(Optional.empty()).thenReturn(Optional.of(teamStorm(9, ago(0), ago(0), null)));
        List<AlertEvent> peers = List.of(down(1, ago(1)), down(2, ago(1)), down(3, ago(1)), down(4, ago(1)), down(5, ago(1)));
        when(alertEventRepo.findOpenDownSince(anyCollection(), anyString())).thenReturn(peers);
        when(jdbcTemplate.update(startsWith("INSERT INTO alert_storms"), any(Object[].class))).thenReturn(0);   // yarışı kaybetti

        AlertEvent me = down(5, ago(1));
        me.setStormId(null);
        assertThat(storm.evaluate(me, null)).isEqualTo(StormService.StormAction.SUPPRESSED);

        ArgumentCaptor<List<Object[]>> cap = ArgumentCaptor.forClass(List.class);
        verify(jdbcTemplate).batchUpdate(eq(StormService.SQL_MEMBER_INSERT), cap.capture());
        List<Object[]> rows = cap.getValue();
        assertThat(rows).extracting(r -> r[1]).containsExactlyInAnyOrder(1L, 2L, 3L, 4L, 5L);
        assertThat(rows).allSatisfy(r -> {
            assertThat(r[0]).isEqualTo(9L);
            assertThat(r[3]).isEqualTo(AlertStormMember.JOIN_ATTACH);
        });
        verify(emailService, never()).sendHtml(any(), any(), anyString(), anyString(), anyString(), anyList(), anyBoolean(), any());
    }
}
