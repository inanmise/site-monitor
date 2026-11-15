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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
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
        OnCallWeeklyNoticeService s = new OnCallWeeklyNoticeService(schedules, overrides, users, notices, teams, logs);
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
    @DisplayName("sistem bakımı susturması: gönderilmez, 'SKIPPED: sistem bakımı' izi")
    void maintenanceMute() {
        schedule(true, "11", null, null);
        when(maintenance.notificationsMuted()).thenReturn(true);
        pod().tick(MON_0900);
        assertThat(mails.get()).isZero();
        assertThat(traces()).singleElement().satisfies(l -> assertThat(l.getEmailStatus()).isEqualTo("SKIPPED: sistem bakımı"));
        assertThat(row("2026-11-16").getReason()).isEqualTo("MAINTENANCE");
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
    @DisplayName("e-posta hatası + push ulaştı → PARTIAL (yeniden denenmez); takım adresi yok + push ulaştı → PARTIAL")
    void partial() {
        schedule(true, "11", null, null);
        mailResult = "FAILED: SMTP 550";
        pod().tick(MON_0900);
        pod().tick(MON_0900.plusSeconds(3600));
        assertThat(mails.get()).isEqualTo(1);
        assertThat(row("2026-11-16").getStatus()).isEqualTo(OnCallWeeklyNotice.PARTIAL);
        // takım adresi yok + push ulaştı → yine PARTIAL (açık bir kanal ulaşmadı)
        notices.deleteAll();
        logs.deleteAll();
        when(escalation.teamEmailsForMonitor(eq(5L), any())).thenReturn(List.of());
        pod().tick(MON_0900);
        assertThat(mails.get()).isEqualTo(1);
        assertThat(row("2026-11-16").getStatus()).isEqualTo(OnCallWeeklyNotice.PARTIAL);
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
        OnCallSchedule s = schedule(true, "11", null, null);
        s.setWeeklyNoticeSince("2026-11-17T05:00:00");
        schedules.save(s);
        pod().tick(Instant.parse("2026-11-17T06:00:00Z"));
        assertThat(traces()).isEmpty();
        assertThat(notices.findAll()).isEmpty();
    }

    @Test
    @DisplayName("Pazar 23:30 dilimi Pazartesi 00:10'da (telafi) önceki haftanın anahtarıyla gönderilir")
    void sundayNight() {
        OnCallSchedule s = schedule(true, "11", null, null);
        s.setWeeklyNoticeDay(7);
        s.setWeeklyNoticeTime("23:30");
        schedules.save(s);
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
