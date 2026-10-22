package com.sitemonitor.service;

import com.sitemonitor.model.AppUser;
import com.sitemonitor.model.SystemMaintenanceSuppression;
import com.sitemonitor.model.SystemMaintenanceWindow;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.repository.SystemMaintenanceSuppressionRepository;
import com.sitemonitor.repository.SystemMaintenanceWindowRepository;
import com.sitemonitor.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sistem bakımı yan işlerinin sahiplenmeleri yarıda kalmaz (2026-10-21, çok pod R1 / MP-18). Sahiplenme işten ÖNCE
 * işlenir; eskiden iş istisnayla düşünce ya da pod ölünce damga kalıyor, telafi hiç yapılmıyordu (susturulan alarmlar
 * INITIAL'ı almıyor) ve bitiş e-postası kayboluyordu. Artık: istisnada sahiplenme bırakılır (sonraki tur yeniden dener),
 * ölü pod'un bayat telafi sahiplenmesi 10 dk sonra devralınır, telafi bitmeden pencere iş listesinden çıkmaz; sonsuz
 * yeniden deneme yok (pod başına 10 deneme, sonra vazgeçme).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SystemMaintenanceCatchUpRetryTest {

    static final Instant NOW = Instant.parse("2026-10-21T10:00:00Z");

    @Mock SystemMaintenanceWindowRepository repo;
    @Mock SystemMaintenanceSuppressionRepository suppressionRepo;
    @Mock AlertEventRepository alertEventRepo;
    @Mock AppUserRepository userRepo;
    @Mock TeamRepository teamRepo;
    @Mock EmailNotificationService emailService;
    @Mock AuditService auditService;
    @Mock SchedulerService schedulerService;
    @Mock SystemMaintenanceService maintenance;
    @Mock AppSettingsService appSettings;

    SystemMaintenanceJobService job;

    @BeforeEach
    void setUp() {
        job = new SystemMaintenanceJobService(repo, suppressionRepo, alertEventRepo, userRepo, teamRepo, emailService,
                auditService, schedulerService, maintenance, appSettings);
        when(emailService.sendHtmlBcc(any(), anyString(), anyString(), anyString())).thenReturn("SENT");
        when(repo.claimStartLog(anyLong(), anyString())).thenReturn(1);
        when(repo.claimEndLog(anyLong(), anyString())).thenReturn(1);
        when(repo.claimCatchUp(anyLong(), anyString())).thenReturn(1);
        when(repo.reclaimCatchUp(anyLong(), anyString(), anyString())).thenReturn(1);
        when(repo.claimEndMail(anyLong(), anyString())).thenReturn(1);
        when(repo.markEndMailSending(anyLong(), anyString(), anyString())).thenReturn(1);
        when(repo.markAnnounceMailSending(anyLong(), anyString(), anyString())).thenReturn(1);
        when(repo.claimAnnounceMail(anyLong(), anyString(), anyInt())).thenReturn(1);
    }

    static String iso(Instant i) { return SystemMaintenanceService.iso(i); }

    SystemMaintenanceWindow ended(boolean mute) {
        SystemMaintenanceWindow w = new SystemMaintenanceWindow();
        w.setId(5L);
        w.setStartAt(iso(NOW.minus(Duration.ofMinutes(60))));
        w.setEndAt(iso(NOW.minus(Duration.ofMinutes(1))));
        w.setPlannedStartAt(w.getStartAt());
        w.setPlannedEndAt(w.getEndAt());
        w.setWarnMinutes(10);
        w.setAnnounceHours(24);
        w.setRevision(1);
        w.setMuteNotifications(mute);
        w.setStartLoggedAt(iso(NOW.minus(Duration.ofMinutes(60))));
        when(repo.findById(5L)).thenReturn(Optional.of(w));
        return w;
    }

    @Test
    @DisplayName("telafi istisnayla düşer → sahiplenme BIRAKILIR, pencere işi bitmiş sayılmaz; sonraki tur telafiyi BİR kez yapar")
    void catchUpFailure_releasedAndRetriedOnce() {
        SystemMaintenanceWindow w = ended(true);
        when(suppressionRepo.findByWindowIdAndCaughtUpAtIsNull(5L)).thenThrow(new IllegalStateException("bağlantı koptu"));

        assertThatThrownBy(() -> job.process(w, NOW)).isInstanceOf(IllegalStateException.class);
        verify(repo).releaseCatchUp(eq(5L), eq(iso(NOW)));
        verify(repo, never()).markJobsDone(anyLong());
        verify(repo, never()).claimEndLog(anyLong(), anyString());

        // Sonraki tur: sahiplenme bırakıldı (catch_up_at NULL) → yeniden sahiplenir ve telafiyi yapar
        org.mockito.Mockito.reset(suppressionRepo);
        when(suppressionRepo.findByWindowIdAndCaughtUpAtIsNull(5L)).thenReturn(List.of(supp(11L)));
        when(alertEventRepo.findAllById(any())).thenReturn(List.of(open(11L)));
        when(alertEventRepo.clearInitialStampForCatchUp(anyCollection(), anyString())).thenReturn(1);
        job.process(ended(true), NOW.plusSeconds(30));
        verify(alertEventRepo, times(1)).clearInitialStampForCatchUp(anyCollection(), anyString());
        verify(repo).recordCaughtUp(5L, 1);
        verify(repo).markJobsDone(5L);
    }

    @Test
    @DisplayName("ölü pod'un sahiplenmesi: 2 dk önce (taze) → dokunulmaz ve pencere açık kalır; 11 dk önce (bayat) → devralınır ve telafi koşar")
    void staleClaimIsReclaimed_freshIsLeftAlone() {
        SystemMaintenanceWindow fresh = ended(true);
        fresh.setCatchUpAt(iso(NOW.minus(Duration.ofMinutes(2))));
        job.process(fresh, NOW);
        verify(repo, never()).reclaimCatchUp(anyLong(), anyString(), anyString());
        verify(suppressionRepo, never()).findByWindowIdAndCaughtUpAtIsNull(anyLong());
        verify(repo, never()).markJobsDone(anyLong());

        SystemMaintenanceWindow stale = ended(true);
        stale.setCatchUpAt(iso(NOW.minus(Duration.ofMinutes(11))));
        when(suppressionRepo.findByWindowIdAndCaughtUpAtIsNull(5L)).thenReturn(List.of());
        job.process(stale, NOW);
        verify(repo).reclaimCatchUp(eq(5L), eq(stale.getCatchUpAt()), eq(iso(NOW)));
        verify(repo).recordCaughtUp(5L, 0);
        verify(repo).markJobsDone(5L);
    }

    @Test
    @DisplayName("telafisi tamamlanmış pencere (sayaç yazılı) yeniden koşmaz; susturma kapalı pencere eskisi gibi biter")
    void doneCatchUpIsNotRepeated() {
        SystemMaintenanceWindow w = ended(true);
        w.setCatchUpAt(iso(NOW.minus(Duration.ofHours(2))));
        w.setCaughtUpCount(3);
        job.process(w, NOW);
        verify(repo, never()).claimCatchUp(anyLong(), anyString());
        verify(repo, never()).reclaimCatchUp(anyLong(), anyString(), anyString());
        verify(repo).markJobsDone(5L);

        org.mockito.Mockito.clearInvocations(repo);
        job.process(ended(false), NOW);
        verify(repo, never()).claimCatchUp(anyLong(), anyString());
        verify(repo).markJobsDone(5L);
    }

    @Test
    @DisplayName("sonsuz yeniden deneme yok: telafi 10 kez düşerse sahiplenme bırakılmaz, bayatlayınca vazgeçilir ve pencere biter")
    void catchUpGivesUpAfterMaxAttempts() {
        when(suppressionRepo.findByWindowIdAndCaughtUpAtIsNull(5L)).thenThrow(new IllegalStateException("kalıcı hata"));
        for (int i = 0; i < SystemMaintenanceJobService.MAX_JOB_ATTEMPTS; i++) {
            SystemMaintenanceWindow w = ended(true);
            assertThatThrownBy(() -> job.process(w, NOW)).isInstanceOf(IllegalStateException.class);
        }
        verify(repo, times(SystemMaintenanceJobService.MAX_JOB_ATTEMPTS - 1)).releaseCatchUp(eq(5L), anyString());

        SystemMaintenanceWindow stale = ended(true);
        stale.setCatchUpAt(iso(NOW.minus(Duration.ofMinutes(11))));
        job.process(stale, NOW);
        verify(repo, never()).reclaimCatchUp(anyLong(), anyString(), anyString());
        verify(repo).markJobsDone(5L);
    }

    @Test
    @DisplayName("bitiş e-postası hiçbir dilim gitmeden düşerse (alıcılar okunamadı) sahiplenme BIRAKILIR; sonraki tur gönderir")
    void endMailFailureReleasesClaim() {
        SystemMaintenanceWindow w = ended(false);
        w.setEmailAllUsers(true);
        when(userRepo.findByActiveTrueOrderByUsernameAsc()).thenThrow(new IllegalStateException("db"));
        assertThatThrownBy(() -> job.process(w, NOW)).isInstanceOf(IllegalStateException.class);
        verify(repo).releaseEndMail(eq(5L), eq(iso(NOW)));
        verify(repo, never()).markJobsDone(anyLong());

        org.mockito.Mockito.reset(userRepo);
        AppUser u = new AppUser();
        u.setUsername("a");
        u.setEmail("a@x.com");
        u.setActive(true);
        when(userRepo.findByActiveTrueOrderByUsernameAsc()).thenReturn(List.of(u));
        SystemMaintenanceWindow again = ended(false);
        again.setEmailAllUsers(true);
        again.setEndLoggedAt(iso(NOW));
        job.process(again, NOW.plusSeconds(30));
        verify(emailService, times(1)).sendHtmlBcc(any(), anyString(), anyString(), anyString());
        verify(repo).recordEndMail(eq(5L), anyString(), eq(1));
        verify(repo).markJobsDone(5L);
    }

    @Test
    @DisplayName("duyuru e-postası alıcılar okunamadan düşerse sahiplenme bırakılır (bakım başlamadan sonraki tur yeniden dener)")
    void announceFailureReleasesClaim() {
        SystemMaintenanceWindow w = new SystemMaintenanceWindow();
        w.setId(5L);
        w.setStartAt(iso(NOW.plus(Duration.ofHours(2))));
        w.setEndAt(iso(NOW.plus(Duration.ofHours(3))));
        w.setWarnMinutes(10);
        w.setAnnounceHours(24);
        w.setRevision(1);
        w.setEmailAllUsers(true);
        when(userRepo.findByActiveTrueOrderByUsernameAsc()).thenThrow(new IllegalStateException("db"));
        assertThatThrownBy(() -> job.process(w, NOW)).isInstanceOf(IllegalStateException.class);
        verify(repo).releaseAnnounceMail(eq(5L), eq(iso(NOW)));
    }

    @Test
    @DisplayName("bayat sahiplenme kuralı: okunamayan damga bayat sayılır; 10 dk sınırı")
    void staleClaimRule() {
        assertThat(SystemMaintenanceJobService.staleClaim(null, NOW)).isTrue();
        assertThat(SystemMaintenanceJobService.staleClaim(iso(NOW.minus(Duration.ofMinutes(9))), NOW)).isFalse();
        assertThat(SystemMaintenanceJobService.staleClaim(iso(NOW.minus(Duration.ofMinutes(10))), NOW)).isTrue();
    }

    // ── İnceleme F7 (2026-10-21): durumu yazılmamış bayat e-posta sahiplenmesi ──────────────────────────────────────────

    SystemMaintenanceWindow endedWithMailClaim(Duration claimAge, String status) {
        SystemMaintenanceWindow w = ended(false);
        w.setEmailAllUsers(true);
        w.setEndLoggedAt(iso(NOW.minus(Duration.ofMinutes(1))));
        w.setEndMailAt(iso(NOW.minus(claimAge)));
        w.setEndMailStatus(status);
        AppUser u = new AppUser();
        u.setUsername("a");
        u.setEmail("a@x.com");
        u.setActive(true);
        when(userRepo.findByActiveTrueOrderByUsernameAsc()).thenReturn(List.of(u));
        when(repo.reclaimEndMail(anyLong(), anyString(), anyString(), anyString())).thenReturn(1);
        when(repo.giveUpEndMail(anyLong(), anyString(), anyString())).thenReturn(1);
        return w;
    }

    @Test
    @DisplayName("F7: bitiş e-postası sahiplenildi ama durumu yazılmadı (pod öldü) — taze sahiplenme pencereyi AÇIK tutar, gönderilmez")
    void endMailFreshUnsettledClaimKeepsWindowOpen() {
        SystemMaintenanceWindow w = endedWithMailClaim(Duration.ofMinutes(2), null);
        job.process(w, NOW);
        verify(repo, never()).reclaimEndMail(anyLong(), anyString(), anyString(), anyString());
        verify(emailService, never()).sendHtmlBcc(any(), anyString(), anyString(), anyString());
        verify(repo, never()).markJobsDone(anyLong());
    }

    @Test
    @DisplayName("F7: bayat (11 dk) ve durumu yazılmamış bitiş e-postası BİR kez devralınıp gönderilir, sonra pencere biter")
    void endMailStaleClaimIsResentOnce() {
        SystemMaintenanceWindow w = endedWithMailClaim(Duration.ofMinutes(11), null);
        job.process(w, NOW);
        verify(repo).reclaimEndMail(eq(5L), eq(w.getEndMailAt()), eq(iso(NOW)), eq(SystemMaintenanceJobService.STATUS_RETRYING));
        verify(emailService, times(1)).sendHtmlBcc(any(), anyString(), anyString(), anyString());
        verify(repo).recordEndMail(eq(5L), anyString(), eq(1));
        verify(repo).markJobsDone(5L);
    }

    @Test
    @DisplayName("F7: devralınan yeniden gönderim de yarıda kaldıysa (durum RETRY:) VAZGEÇİLİR — sonuç bilinmiyor yazılır, ERROR + denetim, ikinci posta yok")
    void endMailSecondInterruptionGivesUp() {
        SystemMaintenanceWindow w = endedWithMailClaim(Duration.ofMinutes(11), SystemMaintenanceJobService.STATUS_RETRYING);
        job.process(w, NOW);
        verify(repo, never()).reclaimEndMail(anyLong(), anyString(), anyString(), anyString());
        verify(emailService, never()).sendHtmlBcc(any(), anyString(), anyString(), anyString());
        verify(repo).giveUpEndMail(eq(5L), eq(w.getEndMailAt()), eq(SystemMaintenanceJobService.STATUS_RESULT_UNKNOWN));
        verify(auditService).recordSystemEvent(eq("SYSTEM_MAINTENANCE_MAIL"), anyString(), eq("5"), any());
        verify(repo).markJobsDone(5L);
    }

    @Test
    @DisplayName("F7: çok eski bitiş (6 saatten önce) için geç 'bakım tamamlandı' postası gönderilmez — vazgeçilir")
    void endMailTooLateGivesUp() {
        SystemMaintenanceWindow w = endedWithMailClaim(Duration.ofHours(7), null);
        w.setEndAt(iso(NOW.minus(Duration.ofHours(7))));
        job.process(w, NOW);
        verify(emailService, never()).sendHtmlBcc(any(), anyString(), anyString(), anyString());
        verify(repo).giveUpEndMail(eq(5L), eq(w.getEndMailAt()), eq(SystemMaintenanceJobService.STATUS_RESULT_UNKNOWN));
        verify(repo).markJobsDone(5L);
    }

    @Test
    @DisplayName("F7: sonucu yazılmış bitiş e-postası (SENT) yeniden gönderilmez ve pencere biter — eskisiyle aynı")
    void endMailSettledIsNotTouched() {
        SystemMaintenanceWindow w = endedWithMailClaim(Duration.ofMinutes(11), "SENT");
        job.process(w, NOW);
        verify(repo, never()).reclaimEndMail(anyLong(), anyString(), anyString(), anyString());
        verify(repo, never()).giveUpEndMail(anyLong(), anyString(), anyString());
        verify(emailService, never()).sendHtmlBcc(any(), anyString(), anyString(), anyString());
        verify(repo).markJobsDone(5L);
    }

    SystemMaintenanceWindow plannedWithAnnounceClaim(Duration claimAge, String status, Duration startsIn) {
        SystemMaintenanceWindow w = new SystemMaintenanceWindow();
        w.setId(5L);
        w.setStartAt(iso(NOW.plus(startsIn)));
        w.setEndAt(iso(NOW.plus(startsIn).plus(Duration.ofHours(1))));
        w.setPlannedStartAt(w.getStartAt());
        w.setPlannedEndAt(w.getEndAt());
        w.setWarnMinutes(10);
        w.setAnnounceHours(24);
        w.setRevision(1);
        w.setMailedRevision(1);
        w.setEmailAllUsers(true);
        w.setAnnounceMailAt(iso(NOW.minus(claimAge)));
        w.setAnnounceMailStatus(status);
        AppUser u = new AppUser();
        u.setUsername("a");
        u.setEmail("a@x.com");
        u.setActive(true);
        when(userRepo.findByActiveTrueOrderByUsernameAsc()).thenReturn(List.of(u));
        when(repo.reclaimAnnounceMail(anyLong(), anyString(), anyString(), anyString())).thenReturn(1);
        when(repo.giveUpAnnounceMail(anyLong(), anyString(), anyString())).thenReturn(1);
        return w;
    }

    @Test
    @DisplayName("F7: bakım başlamadan önce bayat ve durumu yazılmamış duyuru BİR kez yeniden gönderilir; taze olana dokunulmaz")
    void announceStaleClaimIsResentOnceBeforeStart() {
        SystemMaintenanceWindow fresh = plannedWithAnnounceClaim(Duration.ofMinutes(2), null, Duration.ofHours(2));
        job.process(fresh, NOW);
        verify(repo, never()).reclaimAnnounceMail(anyLong(), anyString(), anyString(), anyString());
        verify(emailService, never()).sendHtmlBcc(any(), anyString(), anyString(), anyString());

        SystemMaintenanceWindow stale = plannedWithAnnounceClaim(Duration.ofMinutes(11), null, Duration.ofHours(2));
        job.process(stale, NOW);
        verify(repo).reclaimAnnounceMail(eq(5L), eq(stale.getAnnounceMailAt()), eq(iso(NOW)), eq(SystemMaintenanceJobService.STATUS_RETRYING));
        verify(emailService, times(1)).sendHtmlBcc(any(), anyString(), anyString(), anyString());
        verify(repo).recordAnnounceMail(eq(5L), anyString(), eq(1));
    }

    @Test
    @DisplayName("F7: ikinci kez yarıda kalan duyurudan vazgeçilir (sonuç bilinmiyor + denetim), yeniden gönderilmez")
    void announceSecondInterruptionGivesUp() {
        SystemMaintenanceWindow w = plannedWithAnnounceClaim(Duration.ofMinutes(11), SystemMaintenanceJobService.STATUS_RETRYING,
                Duration.ofHours(2));
        job.process(w, NOW);
        verify(emailService, never()).sendHtmlBcc(any(), anyString(), anyString(), anyString());
        verify(repo).giveUpAnnounceMail(eq(5L), eq(w.getAnnounceMailAt()), eq(SystemMaintenanceJobService.STATUS_RESULT_UNKNOWN));
        verify(auditService).recordSystemEvent(eq("SYSTEM_MAINTENANCE_MAIL"), anyString(), eq("5"), any());
    }

    @Test
    @DisplayName("N2: gönderim BAŞARILI, yalnız durum yazımı düşüyor — yazım 3 kez denenir; tutarsa tek gönderim, pencere biter")
    void endMailRecordRetriedAfterSuccessfulSend() {
        SystemMaintenanceWindow w = endedWithMailClaim(Duration.ofMinutes(0), null);
        w.setEndMailAt(null);
        job.recordBackoffMs = 1;
        when(repo.recordEndMail(anyLong(), anyString(), anyInt()))
                .thenThrow(new IllegalStateException("havuz")).thenThrow(new IllegalStateException("havuz")).thenReturn(1);
        job.process(w, NOW);
        verify(repo).markEndMailSending(eq(5L), eq(iso(NOW)), eq(SystemMaintenanceJobService.STATUS_SENDING));
        verify(emailService, times(1)).sendHtmlBcc(any(), anyString(), anyString(), anyString());
        verify(repo, times(3)).recordEndMail(eq(5L), anyString(), eq(1));
        verify(repo).markJobsDone(5L);
    }

    @Test
    @DisplayName("N2: gönderim başarılı ama durum hiç yazılamadı — 'gönderim başladı' kalır; 10 dk sonra devralma YENİDEN GÖNDERMEZ, "
            + "'başladı ama sonucu yazılamadı' ile vazgeçer")
    void endMailSentButUnrecorded_takeoverNeverResends() {
        SystemMaintenanceWindow w = endedWithMailClaim(Duration.ofMinutes(0), null);
        w.setEndMailAt(null);
        job.recordBackoffMs = 1;
        when(repo.recordEndMail(anyLong(), anyString(), anyInt())).thenThrow(new IllegalStateException("havuz"));
        assertThatThrownBy(() -> job.process(w, NOW)).isInstanceOf(IllegalStateException.class);
        verify(emailService, times(1)).sendHtmlBcc(any(), anyString(), anyString(), anyString());
        verify(repo, never()).releaseEndMail(anyLong(), anyString());
        verify(repo, never()).markJobsDone(anyLong());

        // 11 dk sonraki tur: satırda "gönderim başladı" durumu duruyor → yeniden gönderim YOK
        org.mockito.Mockito.clearInvocations(emailService);
        SystemMaintenanceWindow later = endedWithMailClaim(Duration.ofMinutes(11), SystemMaintenanceJobService.STATUS_SENDING);
        job.process(later, NOW);
        verify(repo, never()).reclaimEndMail(anyLong(), anyString(), anyString(), anyString());
        verify(emailService, never()).sendHtmlBcc(any(), anyString(), anyString(), anyString());
        verify(repo).giveUpEndMail(eq(5L), eq(later.getEndMailAt()), eq(SystemMaintenanceJobService.STATUS_SEND_UNCONFIRMED));
        verify(repo).markJobsDone(5L);
    }

    @Test
    @DisplayName("N2: 'gönderim başladı' durumu yazılamazsa posta GÖNDERİLMEZ, sahiplenme bırakılır (sonraki tur dener)")
    void markSendingFails_noSendAndReleased() {
        SystemMaintenanceWindow w = endedWithMailClaim(Duration.ofMinutes(0), null);
        w.setEndMailAt(null);
        when(repo.markEndMailSending(anyLong(), anyString(), anyString())).thenThrow(new IllegalStateException("havuz"));
        assertThatThrownBy(() -> job.process(w, NOW)).isInstanceOf(IllegalStateException.class);
        verify(emailService, never()).sendHtmlBcc(any(), anyString(), anyString(), anyString());
        verify(repo).releaseEndMail(eq(5L), eq(iso(NOW)));
    }

    @Test
    @DisplayName("N2: duyuru da aynı — gönderim başlamış (durum SENDING) bayat sahiplenme yeniden gönderilmez")
    void announceSentButUnrecorded_takeoverNeverResends() {
        SystemMaintenanceWindow w = plannedWithAnnounceClaim(Duration.ofMinutes(11), SystemMaintenanceJobService.STATUS_SENDING,
                Duration.ofHours(2));
        job.process(w, NOW);
        verify(emailService, never()).sendHtmlBcc(any(), anyString(), anyString(), anyString());
        verify(repo).giveUpAnnounceMail(eq(5L), eq(w.getAnnounceMailAt()), eq(SystemMaintenanceJobService.STATUS_SEND_UNCONFIRMED));
        assertThat(SystemMaintenanceJobService.mailUnsettled(SystemMaintenanceJobService.STATUS_SENDING)).isTrue();
        assertThat(SystemMaintenanceJobService.STATUS_SEND_UNCONFIRMED.length()).isLessThanOrEqualTo(255);
    }

    @Test
    @DisplayName("durum kuralı: NULL ve RETRY: sonuçlanmamış; SENT / FAILED / vazgeçildi sonuçlanmış")
    void mailUnsettledRule() {
        assertThat(SystemMaintenanceJobService.mailUnsettled(null)).isTrue();
        assertThat(SystemMaintenanceJobService.mailUnsettled(SystemMaintenanceJobService.STATUS_RETRYING)).isTrue();
        assertThat(SystemMaintenanceJobService.mailUnsettled("SENT")).isFalse();
        assertThat(SystemMaintenanceJobService.mailUnsettled(SystemMaintenanceJobService.STATUS_RESULT_UNKNOWN)).isFalse();
        assertThat(SystemMaintenanceJobService.STATUS_RESULT_UNKNOWN.length()).isLessThanOrEqualTo(255);
        assertThat(SystemMaintenanceJobService.STATUS_RETRYING.length()).isLessThanOrEqualTo(255);
    }

    private static SystemMaintenanceSuppression supp(long alertId) {
        SystemMaintenanceSuppression s = new SystemMaintenanceSuppression();
        s.setWindowId(5L);
        s.setAlertEventId(alertId);
        s.setOpening(true);
        return s;
    }

    private static com.sitemonitor.model.AlertEvent open(long id) {
        com.sitemonitor.model.AlertEvent e = new com.sitemonitor.model.AlertEvent();
        e.setId(id);
        e.setResolved(false);
        e.setAcknowledged(false);
        return e;
    }
}
