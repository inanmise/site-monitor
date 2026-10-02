package com.sitemonitor.service;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.AppUser;
import com.sitemonitor.model.SystemMaintenanceSuppression;
import com.sitemonitor.model.SystemMaintenanceWindow;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.repository.SystemMaintenanceSuppressionRepository;
import com.sitemonitor.repository.SystemMaintenanceWindowRepository;
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

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sistem bakımı yan işleri (2026-10-02): duyuru / düzeltme e-postası (scheduler_lock, tam bir kez, gizli alıcı, pasif
 * hariç), başlangıç/bitiş denetimi ve bitişte bildirim telafisi.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SystemMaintenanceJobServiceTest {

    static final Instant NOW = Instant.parse("2026-10-02T10:00:00Z");

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
        when(appSettings.getString(eq("site.monitor.app.base-url"), any())).thenReturn("https://sm.example.com");
        when(emailService.sendHtmlBcc(any(), anyString(), anyString(), anyString())).thenReturn("SENT");
        when(repo.claimAnnounceMail(anyLong(), anyString(), anyInt())).thenReturn(1);
        when(repo.claimCorrectionMail(anyLong(), anyString(), anyInt())).thenReturn(1);
        when(repo.claimStartLog(anyLong(), anyString())).thenReturn(1);
        when(repo.claimEndLog(anyLong(), anyString())).thenReturn(1);
        when(repo.claimCatchUp(anyLong(), anyString())).thenReturn(1);
        when(repo.claimEndMail(anyLong(), anyString())).thenReturn(1);
    }

    static String iso(Instant i) { return SystemMaintenanceService.iso(i); }

    SystemMaintenanceWindow win(long startMin, long endMin) {
        SystemMaintenanceWindow w = new SystemMaintenanceWindow();
        w.setId(5L);
        w.setStartAt(iso(NOW.plus(Duration.ofMinutes(startMin))));
        w.setEndAt(iso(NOW.plus(Duration.ofMinutes(endMin))));
        w.setPlannedStartAt(w.getStartAt());
        w.setPlannedEndAt(w.getEndAt());
        w.setWarnMinutes(10);
        w.setAnnounceHours(24);
        w.setRevision(1);
        w.setEmailCorrections(true);
        w.setMuteNotifications(false);
        when(repo.findById(5L)).thenReturn(Optional.of(w));
        return w;
    }

    static AppUser user(String email, boolean active) {
        AppUser u = new AppUser();
        u.setUsername(email);
        u.setEmail(email);
        u.setActive(active);
        return u;
    }

    @Test
    @DisplayName("iş yoksa kilit alınmaz; iş varsa scheduler_lock altında koşar")
    void scheduledRun_lockGate() {
        when(repo.existsPendingJobs()).thenReturn(false);
        job.scheduledRun();
        verify(schedulerService, never()).runWithSchedulerLock(anyString(), any());
        when(repo.existsPendingJobs()).thenReturn(true);
        job.scheduledRun();
        verify(schedulerService).runWithSchedulerLock(eq(SystemMaintenanceJobService.LOCK_NAME), any());
    }

    @Test
    @DisplayName("duyuru e-postası: planlamada BİR kez, gizli alıcı (BCC), aktif kullanıcılar + takım adresi, tekilleşmiş, pasif hariç")
    void announcement_once_bcc_noPassive() {
        SystemMaintenanceWindow w = win(120, 180);
        w.setEmailAllUsers(true);
        w.setEmailTeamIds("3");
        when(userRepo.findByActiveTrueOrderByUsernameAsc()).thenReturn(List.of(
                user("a@x.com", true), user("A@X.com", true), user("pasif@x.com", false), user(null, true)));
        Team t = new Team();
        t.setId(3L);
        t.setActive(true);
        t.setEmail("takim@x.com; a@x.com");
        when(teamRepo.findAllById(any())).thenReturn(List.of(t));

        job.process(w, NOW);

        ArgumentCaptor<String[]> bcc = ArgumentCaptor.forClass(String[].class);
        verify(emailService).sendHtmlBcc(bcc.capture(), anyString(), anyString(), anyString());
        assertThat(bcc.getValue()).containsExactly("a@x.com", "takim@x.com");
        verify(repo).claimAnnounceMail(eq(5L), anyString(), eq(1));
        verify(repo).recordAnnounceMail(eq(5L), anyString(), eq(2));
        verify(auditService).recordSystemEvent(eq("SYSTEM_MAINTENANCE_MAIL"), eq("SYSTEM_MAINTENANCE"), eq("5"), anyString());
        verify(repo, never()).markJobsDone(anyLong());   // bakım daha bitmedi
    }

    @Test
    @DisplayName("duyuru: sahiplenme kaybedildiyse (başka pod) e-posta GİTMEZ")
    void announcement_claimLost() {
        SystemMaintenanceWindow w = win(120, 180);
        w.setEmailAllUsers(true);
        when(repo.claimAnnounceMail(anyLong(), anyString(), anyInt())).thenReturn(0);
        job.process(w, NOW);
        verify(emailService, never()).sendHtmlBcc(any(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("e-posta seçilmediyse hiçbir e-posta gönderilmez")
    void noMailWanted() {
        SystemMaintenanceWindow w = win(120, 180);
        job.process(w, NOW);
        verify(emailService, never()).sendHtmlBcc(any(), anyString(), anyString(), anyString());
        verify(repo, never()).claimAnnounceMail(anyLong(), anyString(), anyInt());
    }

    @Test
    @DisplayName("saat değişti (sürüm 2 > gönderilen 1) → düzeltme e-postası; düzeltme kapalıysa gitmez")
    void correction_onTimeChange() {
        SystemMaintenanceWindow w = win(120, 180);
        w.setEmailAllUsers(true);
        w.setAnnounceMailAt(iso(NOW.minusSeconds(600)));
        w.setMailedRevision(1);
        w.setRevision(2);
        when(userRepo.findByActiveTrueOrderByUsernameAsc()).thenReturn(List.of(user("a@x.com", true)));
        job.process(w, NOW);
        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendHtmlBcc(any(), subject.capture(), anyString(), anyString());
        assertThat(subject.getValue()).contains("güncellendi");
        verify(repo).claimCorrectionMail(eq(5L), anyString(), eq(2));
        verify(repo).incrementCorrectionMails(5L);

        SystemMaintenanceWindow w2 = win(120, 180);
        w2.setEmailAllUsers(true);
        w2.setAnnounceMailAt(iso(NOW.minusSeconds(600)));
        w2.setMailedRevision(1);
        w2.setRevision(2);
        w2.setEmailCorrections(false);
        job.process(w2, NOW);
        verify(emailService, times(1)).sendHtmlBcc(any(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("iptal edildi → 'iptal' düzeltmesi + iş tamam")
    void cancelled_correction() {
        SystemMaintenanceWindow w = win(120, 180);
        w.setEmailAllUsers(true);
        w.setAnnounceMailAt(iso(NOW.minusSeconds(600)));
        w.setMailedRevision(1);
        w.setRevision(2);
        w.setCancelledAt(iso(NOW.minusSeconds(5)));
        when(userRepo.findByActiveTrueOrderByUsernameAsc()).thenReturn(List.of(user("a@x.com", true)));
        job.process(w, NOW);
        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendHtmlBcc(any(), subject.capture(), anyString(), anyString());
        assertThat(subject.getValue()).contains("iptal");
        verify(repo).markJobsDone(5L);
    }

    @Test
    @DisplayName("başladı → SYSTEM_MAINTENANCE_STARTED bir kez; sahiplenme kaybı → denetim yok")
    void startAudit_once() {
        SystemMaintenanceWindow w = win(-1, 60);
        job.process(w, NOW);
        verify(auditService).recordSystemEvent(eq("SYSTEM_MAINTENANCE_STARTED"), eq("SYSTEM_MAINTENANCE"), eq("5"), anyString());
        when(repo.claimStartLog(anyLong(), anyString())).thenReturn(0);
        job.process(win(-1, 60), NOW);
        verify(auditService, times(1)).recordSystemEvent(eq("SYSTEM_MAINTENANCE_STARTED"), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("bitti + susturma açık → telafi: yalnız AÇIK + onaysız + açılışı susturulan alarm sıfırlanır; ENDED denetimi; iş tamam")
    void end_catchUp() {
        SystemMaintenanceWindow w = win(-60, -1);
        w.setMuteNotifications(true);
        w.setStartLoggedAt(iso(NOW.minusSeconds(3600)));
        when(suppressionRepo.findByWindowIdAndCaughtUpAtIsNull(5L)).thenReturn(List.of(
                supp(11L, true), supp(12L, true), supp(13L, true), supp(14L, false)));
        when(alertEventRepo.findAllById(any())).thenReturn(List.of(
                event(11L, false, false), event(12L, true, false), event(13L, false, true)));
        when(alertEventRepo.clearInitialStampForCatchUp(anyCollection(), anyString())).thenReturn(1);

        job.process(w, NOW);

        ArgumentCaptor<Collection<Long>> ids = ArgumentCaptor.forClass(Collection.class);
        verify(alertEventRepo).clearInitialStampForCatchUp(ids.capture(), eq(w.getEndAt()));
        assertThat(ids.getValue()).containsExactly(11L);
        verify(suppressionRepo).markOutcome(eq(5L), eq(List.of(11L)), eq(SystemMaintenanceSuppression.OUTCOME_CAUGHT_UP), anyString());
        verify(suppressionRepo).markOutcome(eq(5L), eq(List.of(12L)), eq(SystemMaintenanceSuppression.OUTCOME_RESOLVED), anyString());
        verify(suppressionRepo).markOutcome(eq(5L), eq(List.of(13L)), eq(SystemMaintenanceSuppression.OUTCOME_ACKNOWLEDGED), anyString());
        verify(suppressionRepo).markOutcome(eq(5L), eq(List.of(14L)), eq(SystemMaintenanceSuppression.OUTCOME_NOT_OPENING), anyString());
        verify(repo).recordCaughtUp(5L, 1);
        verify(auditService).recordSystemEvent(eq("SYSTEM_MAINTENANCE_ENDED"), eq("SYSTEM_MAINTENANCE"), eq("5"), anyString());
        verify(auditService, never()).recordSystemEvent(eq("SYSTEM_MAINTENANCE_STARTED"), anyString(), anyString(), anyString());
        verify(repo).markJobsDone(5L);
    }

    @Test
    @DisplayName("bitti + susturma KAPALI → telafi yok (alarm damgalarına dokunulmaz)")
    void end_noMute_noCatchUp() {
        SystemMaintenanceWindow w = win(-60, -1);
        job.process(w, NOW);
        verify(repo, never()).claimCatchUp(anyLong(), anyString());
        verify(alertEventRepo, never()).clearInitialStampForCatchUp(anyCollection(), anyString());
        verify(repo).markJobsDone(5L);
    }

    @Test
    @DisplayName("tur: bir pencerenin hatası ötekini durdurmaz")
    void run_isolatesFailures() {
        SystemMaintenanceWindow bad = win(-60, -1);
        bad.setId(6L);
        SystemMaintenanceWindow ok = win(-60, -1);
        when(repo.findPendingJobs()).thenReturn(List.of(bad, ok));
        doAnswer(inv -> { if (Long.valueOf(6L).equals(inv.getArgument(0))) throw new RuntimeException("x"); return 1; })
                .when(repo).claimStartLog(anyLong(), anyString());
        SystemMaintenanceJobService.RunResult r = job.run(NOW);
        assertThat(r.windows()).isEqualTo(2);
        verify(repo).markJobsDone(5L);
    }

    @Test
    @DisplayName("süre metni")
    void durationText() {
        assertThat(SystemMaintenanceJobService.durationText("2026-10-02T10:00:00", "2026-10-02T11:30:00")).isEqualTo("1 sa 30 dk");
        assertThat(SystemMaintenanceJobService.durationText("2026-10-02T10:00:00", "2026-10-02T10:45:00")).isEqualTo("45 dk");
        assertThat(SystemMaintenanceJobService.durationText("2026-10-02T10:00:00", "2026-10-02T12:00:00")).isEqualTo("2 sa");
    }

    // ── "Bakım tamamlandı" e-postası (2026-10-02, kullanıcı isteği) ────────────────────────────────

    /** Bitmiş pencere: planlı 60 dk, 1 dk önce bitti; başlangıç denetimi yazılmış. */
    SystemMaintenanceWindow endedWin() {
        SystemMaintenanceWindow w = win(-61, -1);
        w.setStartLoggedAt(iso(NOW.minusSeconds(3600)));
        return w;
    }

    @Test
    @DisplayName("bitiş e-postası: bitince BİR kez, gizli alıcı, pasif hariç, takım adresleri ayrılır; denetim kind=ENDED; iş tamam")
    void endedMail_once_bcc_noPassive() {
        SystemMaintenanceWindow w = endedWin();
        w.setEmailAllUsers(true);
        w.setEmailTeamIds("3");
        when(userRepo.findByActiveTrueOrderByUsernameAsc()).thenReturn(List.of(
                user("a@x.com", true), user("pasif@x.com", false), user("B@x.com", true)));
        Team t = new Team();
        t.setId(3L);
        t.setActive(true);
        t.setEmail("takim@x.com, b@X.com;ops@x.com");
        when(teamRepo.findAllById(any())).thenReturn(List.of(t));

        job.process(w, NOW);

        ArgumentCaptor<String[]> bcc = ArgumentCaptor.forClass(String[].class);
        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendHtmlBcc(bcc.capture(), subject.capture(), anyString(), anyString());
        assertThat(bcc.getValue()).containsExactly("a@x.com", "B@x.com", "takim@x.com", "ops@x.com");
        assertThat(subject.getValue()).contains("tamamlandı");
        verify(repo).claimEndMail(eq(5L), anyString());
        verify(repo).recordEndMail(eq(5L), anyString(), eq(4));
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(auditService).recordSystemEvent(eq("SYSTEM_MAINTENANCE_MAIL"), eq("SYSTEM_MAINTENANCE"), eq("5"), detail.capture());
        assertThat(detail.getValue()).contains("\"kind\":\"ENDED\"").contains("\"recipients\":4");
        verify(repo, never()).claimAnnounceMail(anyLong(), anyString(), anyInt());   // bitmiş bakıma duyuru gitmez
        verify(repo).markJobsDone(5L);
    }

    @Test
    @DisplayName("bitiş e-postası: ikinci tur (sahiplenme kaybı ya da damga dolu) ikinci e-posta GÖNDERMEZ")
    void endedMail_secondTick_noop() {
        SystemMaintenanceWindow w = endedWin();
        w.setEmailAllUsers(true);
        when(userRepo.findByActiveTrueOrderByUsernameAsc()).thenReturn(List.of(user("a@x.com", true)));
        job.process(w, NOW);
        verify(emailService, times(1)).sendHtmlBcc(any(), anyString(), anyString(), anyString());

        // öteki pod aynı anda: sahiplenme 0 → gönderim yok
        when(repo.claimEndMail(anyLong(), anyString())).thenReturn(0);
        SystemMaintenanceWindow again = endedWin();
        again.setEmailAllUsers(true);
        job.process(again, NOW.plusSeconds(30));
        // sonraki tur: damga dolu → sahiplenme bile denenmez
        SystemMaintenanceWindow stamped = endedWin();
        stamped.setEmailAllUsers(true);
        stamped.setEndMailAt(iso(NOW));
        job.process(stamped, NOW.plusSeconds(60));
        verify(emailService, times(1)).sendHtmlBcc(any(), anyString(), anyString(), anyString());
        verify(repo, times(2)).claimEndMail(eq(5L), anyString());
    }

    @Test
    @DisplayName("bitiş e-postası: 'Bakım bitince de e-posta gönder' kapalıysa, alıcı seçilmediyse ya da iptal edildiyse GİTMEZ")
    void endedMail_notSent() {
        SystemMaintenanceWindow off = endedWin();
        off.setEmailAllUsers(true);
        off.setEmailOnEnd(false);
        when(userRepo.findByActiveTrueOrderByUsernameAsc()).thenReturn(List.of(user("a@x.com", true)));
        job.process(off, NOW);

        SystemMaintenanceWindow noRecipients = endedWin();
        job.process(noRecipients, NOW);

        SystemMaintenanceWindow cancelled = win(120, 180);
        cancelled.setEmailAllUsers(true);
        cancelled.setCancelledAt(iso(NOW.minusSeconds(5)));
        job.process(cancelled, NOW);
        SystemMaintenanceWindow cancelledLate = win(-60, -1);   // geçmişte iptal edilmiş (saat geçmiş olsa da) → CANCELLED
        cancelledLate.setEmailAllUsers(true);
        cancelledLate.setCancelledAt(iso(NOW.minusSeconds(7200)));
        job.process(cancelledLate, NOW);

        verify(repo, never()).claimEndMail(anyLong(), anyString());
        verify(emailService, never()).sendHtmlBcc(any(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("bitiş e-postası: aktif bakımda henüz gitmez; null (yama öncesi satır) = açık")
    void endedMail_onlyAfterEnd_nullIsOn() {
        SystemMaintenanceWindow running = win(-10, 50);
        running.setEmailAllUsers(true);
        running.setAnnounceMailAt(iso(NOW.minusSeconds(86_400)));
        running.setMailedRevision(1);
        when(userRepo.findByActiveTrueOrderByUsernameAsc()).thenReturn(List.of(user("a@x.com", true)));
        job.process(running, NOW);
        verify(repo, never()).claimEndMail(anyLong(), anyString());

        SystemMaintenanceWindow legacy = endedWin();
        legacy.setEmailAllUsers(true);
        legacy.setEmailOnEnd(null);
        job.process(legacy, NOW);
        verify(repo).claimEndMail(eq(5L), anyString());
    }

    @Test
    @DisplayName("bitiş e-postası: 150 alıcı → 100 + 50 gizli alıcılı iki gönderim; durum özeti kaydedilir")
    void endedMail_chunks() {
        SystemMaintenanceWindow w = endedWin();
        w.setEmailAllUsers(true);
        List<AppUser> many = new java.util.ArrayList<>();
        for (int i = 0; i < 150; i++) many.add(user("u" + i + "@x.com", true));
        when(userRepo.findByActiveTrueOrderByUsernameAsc()).thenReturn(many);
        job.process(w, NOW);
        ArgumentCaptor<String[]> bcc = ArgumentCaptor.forClass(String[].class);
        verify(emailService, times(2)).sendHtmlBcc(bcc.capture(), anyString(), anyString(), anyString());
        assertThat(bcc.getAllValues()).extracting(a -> a.length).containsExactly(100, 50);
        verify(repo).recordEndMail(eq(5L), eq("SENT ×150"), eq(150));
    }

    @Test
    @DisplayName("hemen bakıma al: seçilen alıcılara duyuru GİTMEZ, yalnız bitişte 'tamamlandı' e-postası")
    void immediate_onlyEndMail() {
        SystemMaintenanceWindow before = win(5, 65);
        before.setImmediate(true);
        before.setAnnounceHours(0);
        before.setEmailAllUsers(true);
        when(userRepo.findByActiveTrueOrderByUsernameAsc()).thenReturn(List.of(user("a@x.com", true)));
        job.process(before, NOW);
        verify(repo, never()).claimAnnounceMail(anyLong(), anyString(), anyInt());
        verify(emailService, never()).sendHtmlBcc(any(), anyString(), anyString(), anyString());

        SystemMaintenanceWindow after = endedWin();
        after.setImmediate(true);
        after.setEmailAllUsers(true);
        job.process(after, NOW);
        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendHtmlBcc(any(), subject.capture(), anyString(), anyString());
        assertThat(subject.getValue()).contains("tamamlandı");
    }

    @Test
    @DisplayName("içerik: plan ve gerçekleşen pencere, erken bitiş notu, 'yeniden kullanılabilir', uygulama bağlantısı")
    void endedMail_content_earlyEnd() {
        SystemMaintenanceWindow w = endedWin();             // gerçek: −61 dk → −1 dk
        w.setPlannedEndAt(iso(NOW.plus(Duration.ofMinutes(29))));   // plan +29 dk → "Hemen bitir" ile erken
        w.setEndedBy("admin");
        w.setEmailAllUsers(true);
        when(userRepo.findByActiveTrueOrderByUsernameAsc()).thenReturn(List.of(user("a@x.com", true)));
        job.process(w, NOW);
        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendHtmlBcc(any(), anyString(), html.capture(), text.capture());
        assertThat(html.getValue()).contains("Planlı bakım tamamlandı").contains("Planlanan").contains("Gerçekleşen")
                .contains("planlanandan erken bitirildi").contains("yeniden kullanılabilir")
                .contains("https://sm.example.com").contains("It ended earlier than planned")
                .doesNotContain("SiteMonitor");
        assertThat(text.getValue()).contains("Planlı bakım tamamlandı");
    }

    @Test
    @DisplayName("bitişin plana göre yeri: erken / uzatıldı / zamanında (1 dk tolerans)")
    void endShift() {
        SystemMaintenanceWindow w = win(-60, 0);
        assertThat(SystemMaintenanceJobService.endShift(w)).isEqualTo(com.sitemonitor.service.mail.SystemMaintenanceMail.EndShift.ON_TIME);
        w.setPlannedEndAt(iso(NOW.plusSeconds(30)));
        assertThat(SystemMaintenanceJobService.endShift(w)).isEqualTo(com.sitemonitor.service.mail.SystemMaintenanceMail.EndShift.ON_TIME);
        w.setPlannedEndAt(iso(NOW.plus(Duration.ofMinutes(15))));
        assertThat(SystemMaintenanceJobService.endShift(w)).isEqualTo(com.sitemonitor.service.mail.SystemMaintenanceMail.EndShift.EARLY);
        w.setPlannedEndAt(iso(NOW.minus(Duration.ofMinutes(30))));
        assertThat(SystemMaintenanceJobService.endShift(w)).isEqualTo(com.sitemonitor.service.mail.SystemMaintenanceMail.EndShift.EXTENDED);
    }

    private static SystemMaintenanceSuppression supp(long alertId, boolean opening) {
        SystemMaintenanceSuppression s = new SystemMaintenanceSuppression();
        s.setWindowId(5L);
        s.setAlertEventId(alertId);
        s.setOpening(opening);
        return s;
    }

    private static AlertEvent event(long id, boolean resolved, boolean acked) {
        AlertEvent e = new AlertEvent();
        e.setId(id);
        e.setResolved(resolved);
        e.setAcknowledged(acked);
        return e;
    }
}
