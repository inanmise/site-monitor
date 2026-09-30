package com.sitemonitor.service;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.AlertStorm;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.AlertStormRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.DnsMonitorRepository;
import com.sitemonitor.repository.DomainMonitorRepository;
import com.sitemonitor.repository.EscalationContactRepository;
import com.sitemonitor.repository.HttpMonitorRepository;
import com.sitemonitor.repository.KeywordMonitorRepository;
import com.sitemonitor.repository.NotificationLogRepository;
import com.sitemonitor.repository.PingMonitorRepository;
import com.sitemonitor.repository.PortMonitorRepository;
import com.sitemonitor.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
 * Fırtına ÖMÜR SINIRI (2026-09-30, prod olayı — SY takımı).
 *
 * <p>Takımın 3 kalıcı başarısız sentetik testi fırtınayı histerezis tabanının üstünde tutuyor, fırtına HİÇ kapanmıyor
 * ve {@code evaluate} takımın her yeni DOWN alarmını sessizce fırtınaya bağlıyordu: ne e-posta, ne push, ne 7/24, ne
 * kayıt. Kural: son üye katılımından {@code quiet-minutes} geçtiyse fırtına MÜHÜRLÜDÜR — yeni üye almaz (bireysel
 * gönderim) ve yaşam döngüsü onu kapatır; hâlâ-down üyelerden fırtına postasında duyurulmuş olan "bildirildi" sayılır,
 * duyurulmamış olan bireysel ilk bildirimini alır.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StormSealingTest {

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
    @Mock NotificationLogRepository notificationLogRepo;

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private StormService storm;

    @BeforeEach
    void setUp() {
        storm = new StormService(stormRepo, alertEventRepo, appSettings, emailService, webhookService,
                teamRepo, contactRepo, inventoryRepo, jdbcTemplate,
                httpRepo, portRepo, keywordRepo, pingRepo, dnsRepo, domainRepo, notificationGroups);
        ReflectionTestUtils.setField(storm, "notificationLogRepo", notificationLogRepo);
        when(appSettings.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> i.getArgument(1));
        when(appSettings.getInt(anyString(), anyInt())).thenAnswer(i -> i.getArgument(1));
        when(appSettings.getString(anyString(), anyString())).thenAnswer(i -> i.getArgument(1));
    }

    private static String ago(long minutes) { return ISO.format(Instant.now().minus(minutes, ChronoUnit.MINUTES)); }

    private static AlertEvent down(long id, Long teamId, String createdAt) {
        AlertEvent e = new AlertEvent();
        e.setId(id); e.setDomain("host" + id + ".example.com"); e.setAlertType(EscalationService.TYPE_SCRIPTED_FAIL);
        e.setAlertLevel("WARNING"); e.setTeamId(teamId); e.setResolved(false); e.setCreatedAt(createdAt);
        return e;
    }

    private static AlertStorm teamStorm(long id, String createdAt, String lastMemberAt, String lastReAlertAt) {
        AlertStorm s = new AlertStorm();
        s.setId(id); s.setScopeKey("TEAM:14"); s.setScopeType("TEAM"); s.setResolved(false);
        s.setCreatedAt(createdAt); s.setLastMemberAt(lastMemberAt); s.setLastReAlertAt(lastReAlertAt);
        return s;
    }

    // ── evaluate: mühürlü fırtına yeni üye ALMAZ ────────────────────────────────

    @Test
    @DisplayName("Son üye katılımı 31 dk önce (sessiz pencere 30) → fırtına mühürlü: SUPPRESSED DEĞİL, bireysel gönderim, bağ yok")
    void evaluate_sealedStorm_doesNotSwallow() {
        when(stormRepo.findByScopeKeyAndResolvedFalse("TEAM:14")).thenReturn(Optional.of(teamStorm(5, ago(600), ago(31), ago(600))));
        when(alertEventRepo.findOpenDownSince(anyCollection(), anyString())).thenReturn(List.of());

        AlertEvent e = down(414, 14L, ago(0));
        assertThat(storm.evaluate(e, null)).isEqualTo(StormService.StormAction.SEND_INDIVIDUAL);
        assertThat(e.getStormId()).isNull();
        verify(jdbcTemplate, never()).update(startsWith("UPDATE alert_storms SET member_count"), any(), any());
    }

    @Test
    @DisplayName("Son üye katılımı 2 dk önce → fırtına taze: SUPPRESSED, üye sayacı + last_member_at güncellenir")
    void evaluate_freshStorm_attachesAndTouchesMemberClock() {
        when(stormRepo.findByScopeKeyAndResolvedFalse("TEAM:14")).thenReturn(Optional.of(teamStorm(5, ago(600), ago(2), ago(600))));

        AlertEvent e = down(414, 14L, ago(0));
        assertThat(storm.evaluate(e, null)).isEqualTo(StormService.StormAction.SUPPRESSED);
        assertThat(e.getStormId()).isEqualTo(5L);
        verify(jdbcTemplate).update(startsWith("UPDATE alert_storms SET member_count = COALESCE(member_count, 0) + 1, last_member_at = ?"),
                anyString(), eq(5L));
    }

    @Test
    @DisplayName("last_member_at boş (eski satır) → created_at esas alınır: 10 saatlik fırtına mühürlüdür")
    void evaluate_legacyRowWithoutMemberClock_usesCreatedAt() {
        when(stormRepo.findByScopeKeyAndResolvedFalse("TEAM:14")).thenReturn(Optional.of(teamStorm(5, ago(600), null, ago(600))));
        when(alertEventRepo.findOpenDownSince(anyCollection(), anyString())).thenReturn(List.of());

        assertThat(storm.evaluate(down(1, 14L, ago(0)), null)).isEqualTo(StormService.StormAction.SEND_INDIVIDUAL);
    }

    @Test
    @DisplayName("Sessiz pencere ayarı okunur ve 5–1440 aralığına kırpılır; varsayılan 30")
    void quietMinutes_readAndClamped() {
        assertThat(storm.quietMinutes()).isEqualTo(30);
        when(appSettings.getInt(eq(StormService.KEY_QUIET), anyInt())).thenReturn(1);
        assertThat(storm.quietMinutes()).isEqualTo(5);
        when(appSettings.getInt(eq(StormService.KEY_QUIET), anyInt())).thenReturn(99999);
        assertThat(storm.quietMinutes()).isEqualTo(1440);
        when(appSettings.getInt(eq(StormService.KEY_QUIET), anyInt())).thenReturn(120);
        // 31 dk önce katılım: 30 dk'da mühürlü, 120 dk'da değil
        assertThat(storm.isSealed(teamStorm(1, ago(600), ago(31), null), ISO.format(Instant.now()))).isFalse();
    }

    @Test
    @DisplayName("Terfi INSERT'i last_member_at kolonunu da yazar (7 parametre)")
    void promotion_insertStampsMemberClock() {
        when(stormRepo.findByScopeKeyAndResolvedFalse("TEAM:14"))
                .thenReturn(Optional.empty()).thenReturn(Optional.of(teamStorm(9, ago(0), ago(0), ago(0))));
        when(alertEventRepo.findOpenDownSince(anyCollection(), anyString()))
                .thenReturn(List.of(down(1, 14L, ago(1)), down(2, 14L, ago(1)), down(3, 14L, ago(1)),
                        down(4, 14L, ago(1)), down(5, 14L, ago(1))));
        when(jdbcTemplate.update(startsWith("INSERT INTO alert_storms"), any(), any(), any(), any(), any(), any(), any())).thenReturn(1);

        assertThat(storm.evaluate(down(1, 14L, ago(1)), null)).isEqualTo(StormService.StormAction.SUPPRESSED);
        verify(jdbcTemplate).update(contains("last_member_at"), eq("TEAM:14"), eq("TEAM"), any(), any(), anyString(), anyString(), anyString());
    }

    // ── lifecycle: mühürlü fırtına, hâlâ-down üye sayısı ne olursa olsun kapanır ─────

    private void lockOk() {
        when(jdbcTemplate.update(startsWith("DELETE FROM scheduler_lock"), any(), any())).thenReturn(1);
        when(jdbcTemplate.update(startsWith("INSERT INTO scheduler_lock"), any(), any(), any())).thenReturn(1);
    }

    @Test
    @DisplayName("Mühürlü fırtına 3 hâlâ-down üyeyle (taban 3) yaşam döngüsünde KAPANIR — duyurulmuş üye 'bildirildi' sayılır, sonradan katılan bireysel ilk bildirim için çözülür")
    void lifecycle_sealedStorm_resolvesAndSplitsMembers() {
        lockOk();
        String announcedAt = ago(120);   // son toplu posta 2 saat önce
        AlertStorm s = teamStorm(7, ago(600), ago(45), announcedAt);
        when(stormRepo.findByResolvedFalse()).thenReturn(List.of(s));
        AlertEvent announced1 = down(1, 14L, ago(600)), announced2 = down(2, 14L, ago(300));
        AlertEvent late = down(414, 14L, ago(45));   // postadan SONRA katıldı — hiç duyurulmadı
        when(alertEventRepo.findByStormId(7L)).thenReturn(List.of(announced1, announced2, late));
        when(alertEventRepo.releaseFromStormAsNotified(anyLong(), eq(7L), eq(announcedAt))).thenReturn(1);

        storm.lifecycleSweep();

        assertThat(s.getResolved()).isTrue();
        verify(stormRepo).save(s);
        verify(alertEventRepo).releaseFromStormAsNotified(1L, 7L, announcedAt);
        verify(alertEventRepo).releaseFromStormAsNotified(2L, 7L, announcedAt);
        verify(alertEventRepo, never()).releaseFromStormAsNotified(eq(414L), anyLong(), anyString());
        verify(alertEventRepo).unlinkFromStorm(414L);   // lastReAlertAt=null → ilk turda bireysel INITIAL
    }

    @Test
    @DisplayName("Taze fırtına (son üye 5 dk önce) hâlâ-down üyelerle AÇIK kalır")
    void lifecycle_freshStorm_staysOpen() {
        lockOk();
        AlertStorm s = teamStorm(7, ago(600), ago(5), ago(1));
        when(stormRepo.findByResolvedFalse()).thenReturn(List.of(s));
        when(alertEventRepo.findByStormId(7L)).thenReturn(List.of(down(1, 14L, ago(600)), down(2, 14L, ago(300)), down(3, 14L, ago(5))));

        storm.lifecycleSweep();

        assertThat(s.getResolved()).isFalse();
        verify(alertEventRepo, never()).unlinkFromStorm(anyLong());
        verify(alertEventRepo, never()).releaseFromStormAsNotified(anyLong(), anyLong(), anyString());
    }

    @Test
    @DisplayName("announcedInStorm: açılışı son toplu postadan önce/aynı anda ise duyurulmuş; sonra ise değil; posta yoksa değil")
    void announcedInStorm_rule() {
        assertThat(StormService.announcedInStorm(down(1, 14L, "2026-09-30T10:00:00"), "2026-09-30T12:00:00")).isTrue();
        assertThat(StormService.announcedInStorm(down(1, 14L, "2026-09-30T12:00:00"), "2026-09-30T12:00:00")).isTrue();
        assertThat(StormService.announcedInStorm(down(1, 14L, "2026-09-30T12:00:01"), "2026-09-30T12:00:00")).isFalse();
        assertThat(StormService.announcedInStorm(down(1, 14L, "2026-09-30T10:00:00"), null)).isFalse();
    }

    // ── fırtına postası üye alarmların günlüğüne yazılır ────────────────────────

    @Test
    @DisplayName("Fırtına açılış postası her üye alarma STORM_INITIAL satırı bırakır (SMTP günlüğü + alarm penceresi görür)")
    void stormMail_isLoggedPerMember() {
        com.sitemonitor.model.Team t = new com.sitemonitor.model.Team();
        t.setId(14L); t.setName("SY-Kurumsal Mimari"); t.setEmail("sy@example.com");
        when(teamRepo.findById(14L)).thenReturn(Optional.of(t));
        when(emailService.buildStormAlertHtml(anyInt(), any(), any(), any(), any(), anyInt(), any())).thenReturn("<html/>");
        when(emailService.buildStormAlertText(anyInt(), any(), any(), any(), any(), anyInt(), any())).thenReturn("text");
        when(emailService.sendHtml(any(), any(), anyString(), anyString(), anyString(), anyList(), anyBoolean(), any())).thenReturn("SENT");
        when(stormRepo.findByScopeKeyAndResolvedFalse("TEAM:14"))
                .thenReturn(Optional.empty()).thenReturn(Optional.of(teamStorm(9, ago(0), ago(0), ago(0))));
        List<AlertEvent> peers = List.of(down(1, 14L, ago(1)), down(2, 14L, ago(1)), down(3, 14L, ago(1)),
                down(4, 14L, ago(1)), down(5, 14L, ago(1)));
        when(alertEventRepo.findOpenDownSince(anyCollection(), anyString())).thenReturn(peers);
        when(jdbcTemplate.update(startsWith("INSERT INTO alert_storms"), any(), any(), any(), any(), any(), any(), any())).thenReturn(1);

        storm.evaluate(down(1, 14L, ago(1)), null);

        org.mockito.ArgumentCaptor<com.sitemonitor.model.NotificationLog> cap =
                org.mockito.ArgumentCaptor.forClass(com.sitemonitor.model.NotificationLog.class);
        verify(notificationLogRepo, times(5)).save(cap.capture());
        assertThat(cap.getAllValues()).allSatisfy(l -> {
            assertThat(l.getTrigger()).isEqualTo(StormService.TRIGGER_STORM_INITIAL);
            assertThat(l.getEmailStatus()).isEqualTo("SENT");
            assertThat(l.getRecipientEmail()).isEqualTo("sy@example.com");
        });
        assertThat(cap.getAllValues()).extracting(com.sitemonitor.model.NotificationLog::getAlertEventId)
                .containsExactlyInAnyOrder(1L, 2L, 3L, 4L, 5L);
    }
}
