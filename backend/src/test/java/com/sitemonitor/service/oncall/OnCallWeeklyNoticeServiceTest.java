package com.sitemonitor.service.oncall;

import com.sitemonitor.model.AppUser;
import com.sitemonitor.model.NotificationLog;
import com.sitemonitor.model.OnCallOverride;
import com.sitemonitor.model.OnCallSchedule;
import com.sitemonitor.model.OnCallWeeklyNotice;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.repository.NotificationLogRepository;
import com.sitemonitor.repository.OnCallOverrideRepository;
import com.sitemonitor.repository.OnCallScheduleRepository;
import com.sitemonitor.repository.OnCallWeeklyNoticeRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.service.EmailNotificationService;
import com.sitemonitor.service.EscalationService;
import com.sitemonitor.service.SchedulerService;
import com.sitemonitor.service.SystemMaintenanceService;
import com.sitemonitor.service.UserPushService;
import com.sitemonitor.service.mail.OnCallWeeklyNoticeMail;
import com.sitemonitor.service.schedule.SideJob;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.AdditionalAnswers;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Haftalık nöbet bildirimi işi (2026-11-15). Gerçek SQL (H2): çizelge, yer değiştirme, takım × hafta sahiplenmesi ve
 * {@code notification_logs} izi; test işlemi KAPALI ({@code NOT_SUPPORTED}) — her adım uygulamadaki gibi kendi işleminde
 * kesinleşir. İki servis örneği = iki pod (aynı veritabanı).
 */
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class OnCallWeeklyNoticeServiceTest {

    @Autowired OnCallScheduleRepository schedules;
    @Autowired OnCallOverrideRepository overrides;
    @Autowired OnCallWeeklyNoticeRepository notices;
    @Autowired NotificationLogRepository logs;
    @Autowired org.springframework.transaction.PlatformTransactionManager txm;

    private final AppUserRepository users = mock(AppUserRepository.class);
    private final TeamRepository teams = mock(TeamRepository.class);
    private final EscalationService escalation = mock(EscalationService.class);
    private final EmailNotificationService email = mock(EmailNotificationService.class);
    private final UserPushService push = mock(UserPushService.class);
    private final SystemMaintenanceService maintenance = mock(SystemMaintenanceService.class);

    /** Pazartesi 2026-11-16 08:30 İstanbul. */
    private static final Instant SLOT = Instant.parse("2026-11-16T05:30:00Z");
    private static final Instant MON_0900 = Instant.parse("2026-11-16T06:00:00Z");

    private final AtomicInteger mails = new AtomicInteger();
    private volatile String mailResult = "SENT";
    private ExecutorService pool;

    @BeforeEach
    void setUp() {
        notices.deleteAll();
        logs.deleteAll();
        overrides.deleteAll();
        schedules.deleteAll();
        mails.set(0);
        mailResult = "SENT";
        pool = Executors.newFixedThreadPool(2);
        Team t = new Team();
        t.setId(5L);
        t.setName("Ödeme Sistemleri");
        t.setActive(true);
        when(teams.findById(5L)).thenReturn(Optional.of(t));
        when(users.findAllById(any())).thenAnswer(i -> {
            List<AppUser> out = new ArrayList<>();
            for (Object id : (Iterable<?>) i.getArgument(0)) {
                long v = (Long) id;
                if (v == 99L) continue;                              // silinmiş hesap
                out.add(user(v, v != 12L));                          // 12 = pasif
            }
            return out;
        });
        when(escalation.teamEmailsForMonitor(eq(5L), any())).thenReturn(List.of("odeme@example.com"));
        when(email.getEmailFrom()).thenReturn("noreply@example.com");
        when(email.sendHtml(any(String[].class), any(), anyString(), anyString(), any(), any(), eq(false), any())).thenAnswer(i -> {
            mails.incrementAndGet();
            return mailResult;
        });
        when(push.enqueueTeamNoticeLocalized(anyLong(), anyString(), anyString(), anyString(), anyString(), any(), anyString(), any()))
                .thenReturn(Map.of("queued", 3, "skipped", 0));
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    private static AppUser user(long id, boolean active) {
        AppUser u = new AppUser();
        u.setId(id);
        u.setUsername("N" + id);
        u.setDisplayName("Kişi " + id + " (Ödeme / Uzman)");
        u.setEmail("kisi" + id + "@example.com");
        u.setPhone("+90 532 000 00 " + (10 + id));
        u.setActive(active);
        return u;
    }

    private OnCallWeeklyNoticeService pod() {
        return pod(notices);
    }

    private OnCallWeeklyNoticeService pod(OnCallWeeklyNoticeRepository repo) {
        OnCallWeeklyNoticeService s = new OnCallWeeklyNoticeService(schedules, overrides, users, repo, teams, logs);
        s.finishBackoffMs = new long[] {0, 0};
        s.setEscalation(escalation);
        s.setEmail(email);
        s.setPush(push);
        s.setSystemMaintenance(maintenance);
        s.clock = Clock.fixed(MON_0900, ZoneOffset.UTC);
        return s;
    }

    /** Takım 5: haftalık rotasyon 11 → 12, bildirim açık (gün / saat varsayılan), ayar dilimden önce yapılmış. */
    private OnCallSchedule schedule(boolean enabled, String members, Boolean mail, Boolean push) {
        OnCallSchedule s = new OnCallSchedule();
        s.setTeamId(5L);
        s.setEnabled(enabled);
        s.setRotation(OnCallSchedule.WEEKLY);
        s.setAnchor("2026-11-02T08:30");
        s.setMemberIds(members);
        s.setNotifyOncall(false);
        s.setWeeklyNoticeEnabled(true);
        s.setWeeklyNoticeMail(mail);
        s.setWeeklyNoticePush(push);
        s.setWeeklyNoticeSince("2026-11-15T00:00:00");   // önceki haftanın diliminden SONRA, bu haftanınkinden ÖNCE
        return schedules.save(s);
    }

    private List<NotificationLog> traces() {
        return logs.findAll().stream().filter(l -> OnCallWeeklyNoticeService.TRIGGER.equals(l.getTrigger())).toList();
    }

    private OnCallWeeklyNotice row(String week) {
        return notices.findByTeamIdAndWeekKey(5L, week).orElseThrow();
    }

    @Test
    @DisplayName("iki pod aynı anda: takım × hafta EN ÇOK BİR posta + BİR push + BİR iz; sonraki turlar göndermez")
    void twoPodsSendOnce() throws Exception {
        schedule(true, "11,12", null, null);
        for (int round = 0; round < 10; round++) {
            OnCallWeeklyNoticeService a = pod();
            OnCallWeeklyNoticeService b = pod();
            CyclicBarrier go = new CyclicBarrier(2);
            Future<?> fa = pool.submit(() -> { await(go); return a.tick(MON_0900); });
            Future<?> fb = pool.submit(() -> { await(go); return b.tick(MON_0900); });
            fa.get(30, TimeUnit.SECONDS);
            fb.get(30, TimeUnit.SECONDS);
        }
        assertThat(mails.get()).isEqualTo(1);
        verify(push, times(1)).enqueueTeamNoticeLocalized(eq(5L), eq("ONCALL_WEEKLY"), eq("WARNING"), eq("ONCALL"),
                eq("Ödeme Sistemleri"), any(), eq("oncall-week:5:2026-11-16"), any());
        assertThat(traces()).hasSize(1);
        OnCallWeeklyNotice r = row("2026-11-16");
        assertThat(r.getStatus()).isEqualTo(OnCallWeeklyNotice.SENT);
        assertThat(r.getPushCount()).isEqualTo(3);
        assertThat(r.getSlotAt()).isEqualTo("2026-11-16T05:30:00");
        assertThat(traces().get(0).getEmailStatus()).isEqualTo("SENT · push 3");
    }

    private static void await(CyclicBarrier b) {
        try { b.await(10, TimeUnit.SECONDS); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    @Test
    @DisplayName("içerik: e-postada aktif nöbetçinin telefonu, pasif nöbetçi 'aranmaz'; push devri adıyla anar")
    void contentPhoneAndPassive() {
        schedule(true, "11,12", null, null);
        // hafta ortasında 12 (pasif) yer değiştirmeyle nöbette
        OnCallOverride o = new OnCallOverride();
        o.setTeamId(5L); o.setUserId(12L); o.setStartsAt("2026-11-18T06:00:00"); o.setEndsAt("2026-11-19T06:00:00");
        overrides.save(o);
        pod().tick(MON_0900);
        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        verify(email).sendHtml(any(String[].class), any(), subject.capture(), html.capture(), any(), any(), eq(false), any());
        assertThat(subject.getValue()).isEqualTo("[Site Monitor] Haftalık nöbet: Ödeme Sistemleri · 16.11.2026 haftası");
        assertThat(html.getValue()).contains("Kişi 11 (Ödeme / Uzman)").contains("+90 532 000 00 21")
                .contains("Pasif hesap — aranmaz").doesNotContain("+90 532 000 00 22");
        ArgumentCaptor<UserPushService.LocalizedText> text = ArgumentCaptor.forClass(UserPushService.LocalizedText.class);
        verify(push).enqueueTeamNoticeLocalized(eq(5L), anyString(), anyString(), anyString(), anyString(), text.capture(), anyString(), any());
        assertThat(text.getValue().tr()).isEqualTo("Ödeme Sistemleri - bu hafta nöbet: Kişi 11 (Ödeme / Uzman); "
                + "Çar 09:00 devir: Kişi 12 (Ödeme / Uzman) (pasif); Per 09:00 devir: Kişi 11 (Ödeme / Uzman)");
        assertThat(text.getValue().en()).startsWith("Ödeme Sistemleri - on call this week: Kişi 11").contains("handover Wed 09:00");
    }

    @Test
    @DisplayName("nöbet bilgisi yok: çizelge kapalı → hiçbir şey gönderilmez, haftada BİR 'nöbet bilgisi yok' izi")
    void noOncallInformation() {
        schedule(false, "11", null, null);
        pod().tick(MON_0900);
        pod().tick(MON_0900.plusSeconds(600));
        assertThat(mails.get()).isZero();
        verify(push, never()).enqueueTeamNoticeLocalized(anyLong(), anyString(), anyString(), anyString(), anyString(), any(), anyString(), any());
        assertThat(traces()).singleElement().satisfies(l -> assertThat(l.getEmailStatus()).isEqualTo("SKIPPED: nöbet bilgisi yok"));
        assertThat(row("2026-11-16")).satisfies(r -> {
            assertThat(r.getStatus()).isEqualTo(OnCallWeeklyNotice.SKIPPED);
            assertThat(r.getReason()).isEqualTo("NO_ONCALL");
        });
    }

    @Test
    @DisplayName("nöbet bilgisi yok: sıradaki tek kişinin hesabı silinmiş → gönderilmez")
    void onlyDeletedMember() {
        schedule(true, "99", null, null);
        pod().tick(MON_0900);
        assertThat(mails.get()).isZero();
        assertThat(row("2026-11-16").getReason()).isEqualTo("NO_ONCALL");
    }

    @Test
    @DisplayName("sistem bakımı (08:00–09:30): sahiplenme yok, satır / iz yok; bakım bitince AYNI GÜN gönderilir (hafta yanmaz)")
    void maintenanceMuteWaitsWithinWindow() {
        schedule(true, "11", null, null);
        when(maintenance.notificationsMuted()).thenReturn(true);
        OnCallWeeklyNoticeService p = pod();
        p.tick(MON_0900);
        assertThat(mails.get()).isZero();
        assertThat(notices.findAll()).isEmpty();
        assertThat(traces()).isEmpty();
        when(maintenance.notificationsMuted()).thenReturn(false);
        Instant later = MON_0900.plusSeconds(1800);
        p.clock = Clock.fixed(later, ZoneOffset.UTC);
        p.tick(later);
        assertThat(mails.get()).isEqualTo(1);
        assertThat(row("2026-11-16").getStatus()).isEqualTo(OnCallWeeklyNotice.SENT);
        assertThat(traces()).singleElement().satisfies(l -> assertThat(l.getEmailStatus()).isEqualTo("SENT · push 3"));
    }

    @Test
    @DisplayName("bakım telafi penceresi boyunca sürdü: pencere kapanınca TEK 'SKIPPED: sistem bakımı' satırı + izi (kaçırıldı değil)")
    void maintenanceThroughWindowEnd() {
        schedule(true, "11", null, null);
        when(maintenance.notificationsMuted()).thenReturn(true);
        OnCallWeeklyNoticeService p = pod();
        p.tick(MON_0900);
        Instant tue0005 = Instant.parse("2026-11-16T21:05:00Z");          // Salı 00:05 İstanbul — pencere kapandı
        p.clock = Clock.fixed(tue0005, ZoneOffset.UTC);
        p.tick(tue0005);
        p.tick(tue0005.plusSeconds(600));
        assertThat(mails.get()).isZero();
        assertThat(row("2026-11-16")).satisfies(r -> {
            assertThat(r.getStatus()).isEqualTo(OnCallWeeklyNotice.SKIPPED);
            assertThat(r.getReason()).isEqualTo("MAINTENANCE");
        });
        assertThat(traces()).singleElement().satisfies(l -> assertThat(l.getEmailStatus()).isEqualTo("SKIPPED: sistem bakımı"));
        // bakım bittikten sonra pencere kapandıysa (pod belleğinde işaret var) yine MAINTENANCE — kaçırıldı değil
        notices.deleteAll();
        logs.deleteAll();
        when(maintenance.notificationsMuted()).thenReturn(true);
        OnCallWeeklyNoticeService q = pod();
        q.tick(MON_0900);                                                 // bakım: işaret bırakır
        when(maintenance.notificationsMuted()).thenReturn(false);
        q.clock = Clock.fixed(tue0005, ZoneOffset.UTC);
        q.tick(tue0005);
        assertThat(row("2026-11-16").getReason()).isEqualTo("MAINTENANCE");
    }

    @Test
    @DisplayName("bakım tam gönderimden önce başladı: sahiplenme BIRAKILIR (satır silinir), bakım bitince gönderilir")
    void maintenanceStartsAfterClaim_claimReleased() {
        schedule(true, "11", null, null);
        when(maintenance.notificationsMuted()).thenReturn(false, true, false);
        OnCallWeeklyNoticeService p = pod();
        p.tick(MON_0900);
        assertThat(mails.get()).isZero();
        assertThat(notices.findAll()).isEmpty();
        assertThat(traces()).isEmpty();
        p.tick(MON_0900.plusSeconds(60));
        assertThat(mails.get()).isEqualTo(1);
        assertThat(row("2026-11-16").getStatus()).isEqualTo(OnCallWeeklyNotice.SENT);
    }

    @Test
    @DisplayName("kanallar: e-posta kapalı → yalnız push, iz 'e-posta kanalı kapalı · push 3', durum SENT")
    void mailOffPushOnly() {
        schedule(true, "11", false, null);
        pod().tick(MON_0900);
        assertThat(mails.get()).isZero();
        assertThat(traces()).singleElement().satisfies(l ->
                assertThat(l.getEmailStatus()).isEqualTo("SKIPPED: e-posta kanalı kapalı (takım ayarı) · push 3"));
        assertThat(row("2026-11-16").getStatus()).isEqualTo(OnCallWeeklyNotice.SENT);
        assertThat(row("2026-11-16").getMailStatus()).isEqualTo(OnCallWeeklyNoticeService.MAIL_OFF);
    }

    @Test
    @DisplayName("kanallar: push kapalı + pasif-yalnız adres → hiçbiri ulaşmadı, SKIPPED (alıcı yok), yeniden denenmez")
    void passiveOnlyAddress() {
        schedule(true, "11", null, false);
        mailResult = "SKIPPED: pasif kullanıcı";
        pod().tick(MON_0900);
        pod().tick(MON_0900.plusSeconds(3600));
        assertThat(mails.get()).isEqualTo(1);
        verifyNoInteractions(push);
        assertThat(row("2026-11-16")).satisfies(r -> {
            assertThat(r.getStatus()).isEqualTo(OnCallWeeklyNotice.SKIPPED);
            assertThat(r.getReason()).isEqualTo("NO_RECIPIENT");
        });
        assertThat(traces()).singleElement().satisfies(l ->
                assertThat(l.getEmailStatus()).isEqualTo("SKIPPED: pasif kullanıcı · push: kapalı (takım ayarı)"));
    }

    @Test
    @DisplayName("e-posta hatası + push ulaştı → PARTIAL; pencere içinde YALNIZ e-posta yeniden denenir (push bir kez), "
            + "ulaşınca SENT; takım adresi yok + push ulaştı → PARTIAL, yeniden denenmez")
    void partial() {
        schedule(true, "11", null, null);
        mailResult = "FAILED: SMTP 550";
        OnCallWeeklyNoticeService p = pod();
        p.tick(MON_0900);
        p.tick(MON_0900.plusSeconds(300));                                   // 10 dk dolmadı
        assertThat(mails.get()).isEqualTo(1);
        assertThat(row("2026-11-16").getStatus()).isEqualTo(OnCallWeeklyNotice.PARTIAL);
        assertThat(row("2026-11-16").getPushStatus()).isEqualTo("delivered");
        p.clock = Clock.fixed(MON_0900.plusSeconds(700), ZoneOffset.UTC);
        p.tick(MON_0900.plusSeconds(700));
        assertThat(mails.get()).isEqualTo(2);
        assertThat(row("2026-11-16")).satisfies(r -> {
            assertThat(r.getStatus()).isEqualTo(OnCallWeeklyNotice.PARTIAL);
            assertThat(r.getAttempts()).isEqualTo(2);
        });
        mailResult = "SENT";
        p.clock = Clock.fixed(MON_0900.plusSeconds(1400), ZoneOffset.UTC);
        p.tick(MON_0900.plusSeconds(1400));
        assertThat(mails.get()).isEqualTo(3);
        assertThat(row("2026-11-16")).satisfies(r -> {
            assertThat(r.getStatus()).isEqualTo(OnCallWeeklyNotice.SENT);
            assertThat(r.getPushCount()).isEqualTo(3);
            assertThat(r.getAttempts()).isEqualTo(3);
        });
        verify(push, times(1)).enqueueTeamNoticeLocalized(anyLong(), anyString(), anyString(), anyString(), anyString(), any(),
                anyString(), any());
        assertThat(traces()).hasSize(3).last().satisfies(l ->
                assertThat(l.getEmailStatus()).isEqualTo("SENT · push: önceki denemede 3 kişi (yeniden gönderilmedi)"));
        mails.set(0);
        p.clock = Clock.fixed(MON_0900.plusSeconds(3600), ZoneOffset.UTC);
        p.tick(MON_0900.plusSeconds(3600));                                  // SENT kesin
        assertThat(mails.get()).isZero();
        // takım adresi yok + push ulaştı → yine PARTIAL (açık bir kanal ulaşmadı)
        notices.deleteAll();
        logs.deleteAll();
        when(escalation.teamEmailsForMonitor(eq(5L), any())).thenReturn(List.of());
        pod().tick(MON_0900);
        pod().tick(MON_0900.plusSeconds(3600));
        assertThat(mails.get()).isZero();
        assertThat(row("2026-11-16")).satisfies(r -> {
            assertThat(r.getStatus()).isEqualTo(OnCallWeeklyNotice.PARTIAL);
            assertThat(r.getAttempts()).isEqualTo(1);
        });
        assertThat(traces()).singleElement().satisfies(l ->
                assertThat(l.getEmailStatus()).isEqualTo("SKIPPED: takımın e-posta adresi yok · push 3"));
    }

    @Test
    @DisplayName("hiçbir kanal ulaşmadı (e-posta hatası, push kapalı) → FAILED; 10 dk sonra yeniden, en çok 3 deneme")
    void failedRetriedThreeTimes() {
        schedule(true, "11", null, false);
        mailResult = "FAILED: bağlantı reddedildi";
        OnCallWeeklyNoticeService p = pod();
        p.tick(MON_0900);
        p.tick(MON_0900.plusSeconds(300));                                  // 10 dk dolmadı
        assertThat(mails.get()).isEqualTo(1);
        p.clock = Clock.fixed(MON_0900.plusSeconds(700), ZoneOffset.UTC);
        p.tick(MON_0900.plusSeconds(700));
        p.clock = Clock.fixed(MON_0900.plusSeconds(1400), ZoneOffset.UTC);
        p.tick(MON_0900.plusSeconds(1400));
        p.clock = Clock.fixed(MON_0900.plusSeconds(2100), ZoneOffset.UTC);
        p.tick(MON_0900.plusSeconds(2100));
        assertThat(mails.get()).isEqualTo(3);
        assertThat(row("2026-11-16")).satisfies(r -> {
            assertThat(r.getStatus()).isEqualTo(OnCallWeeklyNotice.FAILED);
            assertThat(r.getAttempts()).isEqualTo(3);
        });
        assertThat(traces()).hasSize(3);
    }

    @Test
    @DisplayName("kuyruktaki (421) e-posta: sonuç gelene dek 'kuyrukta' kalır, yeniden gönderilmez; günlüğe 'iletilemedi' yazılınca PARTIAL olur ve pencere içinde YALNIZ e-posta yeniden denenir")
    void queuedMailFailureIsSettledAndRetriedMailOnly() {
        schedule(true, "11", null, null);
        mailResult = "QUEUED_RETRY (421 4.7.0 try later)";
        OnCallWeeklyNoticeService p = pod();
        p.tick(MON_0900);
        OnCallWeeklyNotice first = row("2026-11-16");
        assertThat(first.getStatus()).isEqualTo(OnCallWeeklyNotice.SENT);
        assertThat(OnCallService.mailOutcome(first.getMailStatus())).isEqualTo("queued");
        assertThat(first.getMailLogId()).isNotNull();
        // sonuç henüz yok: tur satırı değiştirmez, yeniden göndermez
        p.clock = Clock.fixed(MON_0900.plusSeconds(720), ZoneOffset.UTC);
        p.tick(MON_0900.plusSeconds(720));
        assertThat(mails.get()).isEqualTo(1);
        assertThat(OnCallService.mailOutcome(row("2026-11-16").getMailStatus())).isEqualTo("queued");
        // posta servisi 421 yeniden denemesinin sonucunu gönderimin günlük satırına yazdı: iletilemedi
        NotificationLog l = logs.findById(first.getMailLogId()).orElseThrow();
        l.setEmailStatus("FAILED: 550 5.1.1 mailbox unavailable");
        logs.save(l);
        mailResult = "SENT";
        p.clock = Clock.fixed(MON_0900.plusSeconds(780), ZoneOffset.UTC);
        p.tick(MON_0900.plusSeconds(780));
        assertThat(mails.get()).isEqualTo(2);                                   // yalnız e-posta yeniden gitti
        verify(push, times(1)).enqueueTeamNoticeLocalized(anyLong(), anyString(), anyString(), anyString(), anyString(), any(),
                anyString(), any());
        assertThat(row("2026-11-16")).satisfies(r -> {
            assertThat(r.getStatus()).isEqualTo(OnCallWeeklyNotice.SENT);
            assertThat(r.getAttempts()).isEqualTo(2);
            assertThat(OnCallService.mailOutcome(r.getMailStatus())).isEqualTo("delivered");
        });
    }

    @Test
    @DisplayName("kuyruktaki (421) e-posta iletildi: görünüm 'iletildi' der (eskiden sonsuza dek 'kuyrukta'); okunamayan günlük satırı 'kuyrukta' bırakır")
    @SuppressWarnings("unchecked")
    void queuedMailDeliveredShowsInView() {
        schedule(true, "11", null, null);
        mailResult = "QUEUED_RETRY (421 4.7.0 try later)";
        pod().tick(MON_0900);
        OnCallWeeklyNotice first = row("2026-11-16");
        OnCallService svc = new OnCallService(schedules, overrides, users);
        svc.setWeeklyNotices(notices);
        svc.setNotificationLogs(logs);
        Instant later = MON_0900.plusSeconds(900);
        assertThat((Map<String, Object>) svc.view(5L, later).get("weekly_notice_last")).containsEntry("mail", "queued");
        NotificationLog l = logs.findById(first.getMailLogId()).orElseThrow();
        l.setEmailStatus("SENT");
        logs.save(l);
        assertThat((Map<String, Object>) svc.view(5L, later).get("weekly_notice_last"))
                .containsEntry("mail", "delivered").containsEntry("status", OnCallWeeklyNotice.SENT);
        assertThat(row("2026-11-16").getMailStatus()).isEqualTo("SENT");
        assertThat(svc.view(5L, later).get("weekly_notice_next")).isEqualTo("2026-11-23T05:30:00");
        // günlük kimliği olmayan (eski) satır uzlaştırılamaz: dokunulmaz
        OnCallWeeklyNotice legacy = new OnCallWeeklyNotice();
        legacy.setId(-1L);
        legacy.setStatus(OnCallWeeklyNotice.SENT);
        legacy.setMailStatus("QUEUED_RETRY (421)");
        assertThat(OnCallWeeklyNoticeService.settleQueuedMail(notices, logs, legacy).getMailStatus()).isEqualTo("QUEUED_RETRY (421)");
    }

    @Test
    @DisplayName("telafi: dilim geçti ama gün bitmedi → gönderilir; gün bitti → 'zamanı kaçırıldı' izi, gönderilmez")
    void catchUpAndMissed() {
        schedule(true, "11", null, null);
        Instant tue = Instant.parse("2026-11-17T06:00:00Z");
        OnCallWeeklyNoticeService p = pod();
        p.clock = Clock.fixed(tue, ZoneOffset.UTC);
        p.tick(tue);
        assertThat(mails.get()).isZero();
        assertThat(row("2026-11-16")).satisfies(r -> {
            assertThat(r.getStatus()).isEqualTo(OnCallWeeklyNotice.SKIPPED);
            assertThat(r.getReason()).isEqualTo("MISSED");
        });
        assertThat(traces()).singleElement().satisfies(l -> assertThat(l.getEmailStatus()).startsWith("SKIPPED: zamanı kaçırıldı"));
        // aynı gün içinde (23:50 İstanbul) yeniden başlayan pod gönderir
        notices.deleteAll();
        logs.deleteAll();
        Instant late = Instant.parse("2026-11-16T20:50:00Z");
        pod().tick(late);
        assertThat(mails.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("ayar dilimden SONRA yapıldıysa geçmiş dilim 'kaçırıldı' sayılmaz (iz yok)")
    void configuredAfterSlotNoMissedTrace() {
        schedule(true, "11", null, null);
        schedules.setWeeklyNoticeSince(5L, "2026-11-17T05:00:00");     // bildirim kolonları yalnız hedefli UPDATE ile yazılır
        pod().tick(Instant.parse("2026-11-17T06:00:00Z"));
        assertThat(traces()).isEmpty();
        assertThat(notices.findAll()).isEmpty();
    }

    @Test
    @DisplayName("Pazar 23:30 dilimi Pazartesi 00:10'da (telafi) önceki haftanın anahtarıyla gönderilir")
    void sundayNight() {
        schedule(true, "11", null, null);
        schedules.setWeeklyNoticeDay(5L, 7);
        schedules.setWeeklyNoticeTime(5L, "23:30");
        pod().tick(Instant.parse("2026-11-22T21:10:00Z"));
        assertThat(mails.get()).isEqualTo(1);
        assertThat(row("2026-11-16").getSlotAt()).isEqualTo("2026-11-22T20:30:00");
    }

    @Test
    @DisplayName("yarıda kalan SENDING (pod öldü) → UNKNOWN + iz, YENİDEN GÖNDERİLMEZ")
    void staleSendingBecomesUnknown() {
        schedule(true, "11", null, null);
        OnCallWeeklyNotice n = new OnCallWeeklyNotice();
        n.setTeamId(5L); n.setWeekKey("2026-11-16"); n.setSlotAt("2026-11-16T05:30:00"); n.setStatus(OnCallWeeklyNotice.SENDING);
        n.setAttempts(1); n.setClaimedAt("2026-11-16T05:30:10"); n.setCreatedAt("2026-11-16T05:30:10");
        notices.save(n);
        OnCallWeeklyNoticeService.TickResult r = pod().tick(MON_0900);
        assertThat(r.unknown()).isEqualTo(1);
        assertThat(mails.get()).isZero();
        assertThat(row("2026-11-16")).satisfies(x -> {
            assertThat(x.getStatus()).isEqualTo(OnCallWeeklyNotice.UNKNOWN);
            assertThat(x.getReason()).isEqualTo("INTERRUPTED");
        });
        assertThat(traces()).singleElement().satisfies(l -> assertThat(l.getEmailStatus()).startsWith("FAILED: sonuç bilinmiyor"));
        pod().tick(MON_0900.plusSeconds(120));
        assertThat(mails.get()).isZero();
        assertThat(traces()).hasSize(1);
    }

    @Test
    @DisplayName("hiçbir takım açmadıysa zamanlanmış tur kilit almaz; kapanış başladıysa hiçbir şey yapmaz")
    void cheapGateAndShutdown() {
        OnCallWeeklyNoticeService p = pod();
        SchedulerService scheduler = mock(SchedulerService.class);
        p.setScheduler(scheduler);
        p.scheduledTick();
        verifyNoInteractions(scheduler);
        schedule(true, "11", null, null);
        p.onContextClosed();
        p.scheduledTick();
        verifyNoInteractions(scheduler);
        assertThat(mails.get()).isZero();
    }

    @Test
    @DisplayName("gövde yan iş koşucusunda (sabit gecikmeli, kilit içinde); koşucu üstlenirse zamanlayıcı iş parçacığında koşmaz")
    void sideJobOffload() {
        schedule(true, "11", null, null);
        OnCallWeeklyNoticeService p = pod();
        SchedulerService scheduler = mock(SchedulerService.class);
        doAnswer(i -> {
            ((Runnable) i.getArgument(1)).run();
            return null;
        }).when(scheduler).runWithSchedulerLock(eq(OnCallWeeklyNoticeService.LOCK_NAME), any());
        p.setScheduler(scheduler);
        p.scheduledTick();                                                    // sahte koşucu üstlenmez → satır içi
        ArgumentCaptor<SideJob> job = ArgumentCaptor.forClass(SideJob.class);
        verify(scheduler).offloadSideJob(job.capture());
        assertThat(job.getValue().name()).isEqualTo(OnCallWeeklyNoticeService.LOCK_NAME);
        assertThat(job.getValue().heavy()).isTrue();
        assertThat(job.getValue().fixedDelayMs()).isEqualTo(60_000L);
        verify(scheduler).runWithSchedulerLock(eq(OnCallWeeklyNoticeService.LOCK_NAME), any());
        assertThat(mails.get()).isEqualTo(1);
        when(scheduler.offloadSideJob(any())).thenReturn(true);              // koşucu aldı: gövde burada koşmaz
        p.scheduledTick();
        verify(scheduler, times(1)).runWithSchedulerLock(eq(OnCallWeeklyNoticeService.LOCK_NAME), any());
    }

    @Test
    @DisplayName("kimse bildirimi açık tutmasa da yarıda kalmış SENDING süpürülür (ucuz kapı: açık takım YA DA bayat satır)")
    void staleSweepWithoutEnabledTeam() {
        OnCallWeeklyNotice n = new OnCallWeeklyNotice();
        n.setTeamId(5L); n.setWeekKey("2026-11-16"); n.setSlotAt("2026-11-16T05:30:00"); n.setStatus(OnCallWeeklyNotice.SENDING);
        n.setAttempts(1); n.setClaimedAt("2026-11-16T05:30:10"); n.setCreatedAt("2026-11-16T05:30:10");
        notices.save(n);
        OnCallWeeklyNoticeService p = pod();
        assertThat(p.anyEnabled()).isFalse();
        assertThat(p.anyWork(MON_0900)).isTrue();
        assertThat(p.anyWork(Instant.parse("2026-11-16T05:35:00Z"))).isFalse();     // henüz bayat değil
        p.scheduledTick();
        assertThat(row("2026-11-16").getStatus()).isEqualTo(OnCallWeeklyNotice.UNKNOWN);
        assertThat(p.anyWork(MON_0900)).isFalse();
    }

    @Test
    @DisplayName("son durum yazımı geçici hatada yeniden denenir: iki hata, üçüncüde SENT; tek iz")
    void finishIsRetried() {
        schedule(true, "11", null, null);
        OnCallWeeklyNoticeRepository flaky = mock(OnCallWeeklyNoticeRepository.class, AdditionalAnswers.delegatesTo(notices));
        doThrow(new DataAccessResourceFailureException("db")).doThrow(new DataAccessResourceFailureException("db"))
                .doAnswer(AdditionalAnswers.delegatesTo(notices))
                .when(flaky).finish(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        pod(flaky).tick(MON_0900);
        assertThat(row("2026-11-16").getStatus()).isEqualTo(OnCallWeeklyNotice.SENT);
        assertThat(traces()).hasSize(1);
        verify(flaky, times(3)).finish(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("son durum hiç yazılamadı: satır SENDING kalır; süpürme UNKNOWN yapar ama İKİNCİ iz yazmaz, yeniden göndermez")
    void finishNeverWritten_sweepWritesNoSecondTrace() {
        schedule(true, "11", null, null);
        OnCallWeeklyNoticeRepository broken = mock(OnCallWeeklyNoticeRepository.class, AdditionalAnswers.delegatesTo(notices));
        doThrow(new DataAccessResourceFailureException("db"))
                .when(broken).finish(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        pod(broken).tick(MON_0900);
        assertThat(row("2026-11-16").getStatus()).isEqualTo(OnCallWeeklyNotice.SENDING);
        assertThat(traces()).singleElement().satisfies(l -> assertThat(l.getEmailStatus()).isEqualTo("SENT · push 3"));
        Instant later = MON_0900.plusSeconds(16 * 60);
        OnCallWeeklyNoticeService p = pod();
        p.clock = Clock.fixed(later, ZoneOffset.UTC);
        assertThat(p.tick(later).unknown()).isEqualTo(1);
        assertThat(row("2026-11-16").getStatus()).isEqualTo(OnCallWeeklyNotice.UNKNOWN);
        assertThat(traces()).hasSize(1);
        assertThat(mails.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("bildirim kolonları: eşzamanlı iki yönetici (gün / saat) birbirini ezmez; bayat varlıkla tam çizelge kaydı kolonlara dokunmaz")
    void noticeColumnsTargeted() {
        schedule(true, "11", null, null);
        OnCallSchedule snapshot = schedules.findByTeamId(5L).orElseThrow();
        OnCallScheduleRepository staleRepo = mock(OnCallScheduleRepository.class, AdditionalAnswers.delegatesTo(schedules));
        doAnswer(i -> Optional.of(copy(snapshot))).when(staleRepo).findByTeamId(5L);   // ikisi de aynı (bayat) satırı okur
        OnCallService a = new OnCallService(staleRepo, overrides, users);
        OnCallService b = new OnCallService(staleRepo, overrides, users);
        a.saveWeeklyNotice(5L, new OnCallService.WeeklyNoticePatch(null, null, null, 3, null), "a");
        b.saveWeeklyNotice(5L, new OnCallService.WeeklyNoticePatch(null, null, null, null, "09:15"), "b");
        OnCallSchedule now = schedules.findByTeamId(5L).orElseThrow();
        assertThat(now.getWeeklyNoticeDay()).isEqualTo(3);
        assertThat(now.getWeeklyNoticeTime()).isEqualTo("09:15");
        // bayat varlıkla eski 7 değişkenli çizelge kaydı: bildirim kolonları geri yazılmaz
        OnCallService c = new OnCallService(staleRepo, overrides, users);
        c.save(5L, true, "WEEKLY", "2026-11-02T08:30", List.of(11L), true, "c");
        OnCallSchedule after = schedules.findByTeamId(5L).orElseThrow();
        assertThat(after.getNotifyOncall()).isTrue();
        assertThat(after.getWeeklyNoticeDay()).isEqualTo(3);
        assertThat(after.getWeeklyNoticeTime()).isEqualTo("09:15");
        assertThat(after.getWeeklyNoticeEnabled()).isTrue();
        // çizelge + bildirim birlikte (8 değişkenli): yalnız gönderilen bildirim kolonu yazılır
        c.save(5L, true, "WEEKLY", "2026-11-02T08:30", List.of(11L), true,
                new OnCallService.WeeklyNoticePatch(null, false, null, null, null), "c");
        OnCallSchedule last = schedules.findByTeamId(5L).orElseThrow();
        assertThat(last.getWeeklyNoticeMail()).isFalse();
        assertThat(last.getWeeklyNoticeDay()).isEqualTo(3);
        assertThat(last.getWeeklyNoticeTime()).isEqualTo("09:15");
    }

    @Test
    @DisplayName("yalnız-bildirim kaydı İŞLEM İÇİNDE: okuma ile kesinleşme arasında kaydedilen çizelge değişikliği (üyeler / ilk devir / rotasyon / nöbetçiye bildirim) geri yazılmaz")
    void noticeOnlySaveKeepsConcurrentScheduleEdit() throws Exception {
        schedule(true, "11", null, null);
        OnCallScheduleRepository racing = mock(OnCallScheduleRepository.class, AdditionalAnswers.delegatesTo(schedules));
        doAnswer(i -> {
            Optional<OnCallSchedule> read = schedules.findByTeamId(5L);   // işlemin kalıcılık bağlamında YÖNETİLEN satır
            // okumadan sonra, bildirim kolonları yazılmadan önce başka bir yönetici çizelgeyi değiştirip kaydeder (kendi işlemi)
            pool.submit(() -> {
                OnCallSchedule other = schedules.findByTeamId(5L).orElseThrow();
                other.setMemberIds("11,12");
                other.setAnchor("2026-11-09T09:00");
                other.setRotation(OnCallSchedule.DAILY);
                other.setNotifyOncall(true);
                other.setUpdatedBy("diger");
                schedules.save(other);
            }).get(30, TimeUnit.SECONDS);
            return read;
        }).when(racing).findByTeamId(5L);
        OnCallService svc = new OnCallService(racing, overrides, users);
        OnCallService.WeeklyNoticeSave r = new org.springframework.transaction.support.TransactionTemplate(txm).execute(st ->
                svc.saveWeeklyNotice(5L, new OnCallService.WeeklyNoticePatch(null, null, null, 3, null), "a"));
        assertThat(r.changed()).isTrue();
        assertThat(r.schedule().getWeeklyNoticeDay()).isEqualTo(3);               // yanıt kopyası yeni ayarı taşır
        OnCallSchedule after = schedules.findByTeamId(5L).orElseThrow();
        assertThat(after.getMemberIds()).isEqualTo("11,12");
        assertThat(after.getAnchor()).isEqualTo("2026-11-09T09:00");
        assertThat(after.getRotation()).isEqualTo(OnCallSchedule.DAILY);
        assertThat(after.getNotifyOncall()).isTrue();
        assertThat(after.getWeeklyNoticeDay()).isEqualTo(3);                      // hedefli UPDATE yazıldı
        assertThat(after.getUpdatedBy()).isEqualTo("a");                          // touchUpdated (hedefli) — tam satır değil
    }

    private static OnCallSchedule copy(OnCallSchedule s) {
        OnCallSchedule c = new OnCallSchedule();
        c.setId(s.getId());
        c.setTeamId(s.getTeamId());
        c.setEnabled(s.getEnabled());
        c.setRotation(s.getRotation());
        c.setAnchor(s.getAnchor());
        c.setMemberIds(s.getMemberIds());
        c.setNotifyOncall(s.getNotifyOncall());
        c.setWeeklyNoticeEnabled(s.getWeeklyNoticeEnabled());
        c.setWeeklyNoticeMail(s.getWeeklyNoticeMail());
        c.setWeeklyNoticePush(s.getWeeklyNoticePush());
        c.setWeeklyNoticeDay(s.getWeeklyNoticeDay());
        c.setWeeklyNoticeTime(s.getWeeklyNoticeTime());
        c.setWeeklyNoticeSince(s.getWeeklyNoticeSince());
        c.setUpdatedAt(s.getUpdatedAt());
        c.setUpdatedBy(s.getUpdatedBy());
        return c;
    }

    @Test
    @DisplayName("çok devirli hafta: listeye sığmayan devirler sessizce düşmez — e-posta ve push '+N devir daha' der")
    void omittedHandoversAreSaid() {
        Instant to = SLOT.plus(OnCallWeeklyNoticeSlots.WEEK);
        List<OnCallWeeklyNoticeMail.Segment> segs = List.of(
                new OnCallWeeklyNoticeMail.Segment(SLOT, SLOT.plusSeconds(3600),
                        new OnCallWeeklyNoticeMail.Person("Kişi 11", null, null, true, false), false));
        OnCallWeeklyNoticeMail.Info info = new OnCallWeeklyNoticeMail.Info("Ödeme Sistemleri", SLOT, to, segs, "", 7);
        assertThat(OnCallWeeklyNoticeMail.pushText(info, false)).endsWith("(+7 devir daha gösterilmiyor)");
        assertThat(OnCallWeeklyNoticeMail.pushText(info, true)).endsWith("(+7 more handovers not shown)");
        assertThat(OnCallWeeklyNoticeMail.build(info, false).html()).contains("+7 devir daha gösterilmiyor");
        assertThat(OnCallWeeklyNoticeMail.build(info, false).text()).contains("+7 devir daha gösterilmiyor");
        OnCallWeeklyNoticeMail.Info full = new OnCallWeeklyNoticeMail.Info("Ödeme Sistemleri", SLOT, to, segs, "");
        assertThat(OnCallWeeklyNoticeMail.pushText(full, false)).doesNotContain("devir daha");
        assertThat(OnCallWeeklyNoticeMail.build(full, false).html()).doesNotContain("devir daha");
    }

    @Test
    @DisplayName("takım silinince gönderim kayıtları da silinir")
    void deleteForTeam() {
        schedule(true, "11", null, null);
        pod().tick(MON_0900);
        assertThat(notices.findAll()).hasSize(1);
        OnCallService svc = new OnCallService(schedules, overrides, users);
        svc.setWeeklyNotices(notices);
        svc.deleteForTeam(5L);
        assertThat(notices.findAll()).isEmpty();
        assertThat(schedules.findByTeamId(5L)).isEmpty();
    }

    @Test
    @DisplayName("son durum kuralı: açık her kanal ulaştı = SENT; biri ulaştı = PARTIAL; hata = FAILED; hiçbiri / hatasız = SKIPPED")
    void finalStatusRule() {
        var D = OnCallWeeklyNoticeService.Outcome.DELIVERED;
        var S = OnCallWeeklyNoticeService.Outcome.SKIPPED;
        var F = OnCallWeeklyNoticeService.Outcome.FAILED;
        var O = OnCallWeeklyNoticeService.Outcome.OFF;
        assertThat(OnCallWeeklyNoticeService.finalStatus(D, D)).isEqualTo("SENT");
        assertThat(OnCallWeeklyNoticeService.finalStatus(D, O)).isEqualTo("SENT");
        assertThat(OnCallWeeklyNoticeService.finalStatus(O, D)).isEqualTo("SENT");
        assertThat(OnCallWeeklyNoticeService.finalStatus(D, S)).isEqualTo("PARTIAL");
        assertThat(OnCallWeeklyNoticeService.finalStatus(F, D)).isEqualTo("PARTIAL");
        assertThat(OnCallWeeklyNoticeService.finalStatus(F, O)).isEqualTo("FAILED");
        assertThat(OnCallWeeklyNoticeService.finalStatus(S, F)).isEqualTo("FAILED");
        assertThat(OnCallWeeklyNoticeService.finalStatus(S, S)).isEqualTo("SKIPPED");
        assertThat(OnCallWeeklyNoticeService.finalStatus(S, O)).isEqualTo("SKIPPED");
    }
}
