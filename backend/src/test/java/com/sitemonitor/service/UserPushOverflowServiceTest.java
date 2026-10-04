package com.sitemonitor.service;

import com.sitemonitor.model.AppUser;
import com.sitemonitor.model.UserPushDelivery;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.repository.UserPushDeliveryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Saat tavanı ÖZETİ (2026-10-04, onaylı öneri 2): tavana takılan push'lar kişi başına tek özet push'unda toplanır —
 * zamanlama, en çok bir kez sahiplenme, tavan muafiyeti, kişisel kurallar (opt-out / pasif / bakım → iz; sessiz saat /
 * susturma → erteleme, kritik istisnası) ve dil.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserPushOverflowServiceTest {

    @Mock UserPushService push;
    @Mock UserPushDeliveryRepository repo;
    @Mock AppUserRepository userRepo;
    @Mock SchedulerService scheduler;

    private UserPushOverflowService service;
    private final List<UserPushDelivery> saved = new ArrayList<>();
    private final List<UserPushDelivery> overflow = new ArrayList<>();
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    /** 2026-10-04 11:30 UTC = 14:30 İstanbul (gündüz — sessiz saat penceresi kurulmadıkça dışında). */
    private static final Instant NOW = Instant.parse("2026-10-04T11:30:00Z");
    private static final String USER = "N00001";

    @BeforeEach
    void setUp() {
        service = new UserPushOverflowService(push, repo, userRepo, scheduler);
        service.clock = Clock.fixed(NOW, ZoneOffset.UTC);
        when(push.enabled()).thenReturn(true);
        when(push.overflowSummaryEnabled()).thenReturn(true);
        when(push.overflowSummaryMinutes()).thenReturn(15);
        when(push.maxMessageChars()).thenReturn(200);
        when(push.titleSetting(anyString())).thenReturn("Site Monitor");
        when(push.quietHoursBlock(anyString())).thenReturn(false);
        when(push.systemMaintenanceMuted()).thenReturn(false);
        long[] ids = {1000};
        when(repo.save(any())).thenAnswer(i -> {
            UserPushDelivery d = i.getArgument(0);
            if (d.getId() == null) d.setId(++ids[0]);
            if (!saved.contains(d)) saved.add(d);
            return d;
        });
        when(repo.findUnsummarizedOverflow(eq(USER), anyString(), any()))
                .thenAnswer(i -> overflow.stream().filter(d -> d.getOverflowSummaryId() == null).toList());
        when(repo.claimOverflow(any(), any())).thenAnswer(i -> {
            Collection<Long> idList = i.getArgument(0);
            Long sid = i.getArgument(1);
            int n = 0;
            for (UserPushDelivery d : overflow) {
                if (idList.contains(d.getId()) && d.getOverflowSummaryId() == null && "RATE_LIMITED".equals(d.getStatus())) {
                    d.setOverflowSummaryId(sid);
                    n++;
                }
            }
            return n;
        });
        when(repo.findByOverflowSummaryIdOrderByIdAsc(any())).thenAnswer(i -> overflow.stream()
                .filter(d -> i.getArgument(0).equals(d.getOverflowSummaryId())).toList());
        doAnswer(i -> { ((Runnable) i.getArgument(1)).run(); return null; })
                .when(scheduler).runWithSchedulerLock(eq(UserPushOverflowService.LOCK_NAME), any());
    }

    private AppUser user() {
        AppUser u = new AppUser();
        u.setId(1L);
        u.setUsername(USER);
        u.setDisplayName("Bir Kişi");
        u.setActive(true);
        when(userRepo.findByUsername(USER)).thenReturn(Optional.of(u));
        return u;
    }

    private void rateLimited(long id, String level, String name, Instant at, Long team) {
        UserPushDelivery d = new UserPushDelivery();
        d.setId(id);
        d.setUsername(USER);
        d.setStatus("RATE_LIMITED");
        d.setTrigger("OPEN");
        d.setAlertLevel(level);
        d.setMonitorName(name);
        d.setTeamId(team);
        d.setCreatedAt(ISO.format(at));
        overflow.add(d);
    }

    private void candidate(Instant firstAt) {
        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[]{USER, ISO.format(firstAt), (long) overflow.size()});
        when(repo.overflowCandidates(anyString())).thenReturn(rows);
    }

    private UserPushDelivery summary() {
        return saved.stream().filter(d -> UserPushOverflowService.TRIGGER.equals(d.getTrigger())).findFirst().orElse(null);
    }

    @Test
    @DisplayName("ilk taşmadan 15 dk sonra TEK özet: tetik OVERFLOW_SUMMARY, kırılım + son bildirim, satırlar özete bağlı (durum RATE_LIMITED kalır), outbox tetiklenir")
    void dueAfterWindow_sendsOneSummary() {
        user();
        Instant first = NOW.minusSeconds(20 * 60);
        rateLimited(1, "CRITICAL", "site-a", first, 4L);
        rateLimited(2, "WARNING", "site-b", first.plusSeconds(60), 4L);
        rateLimited(3, "CRITICAL", "site-x", NOW.minusSeconds(60), 4L);
        candidate(first);

        UserPushOverflowService.SweepResult r = service.sweep(NOW);

        assertThat(r.summaries()).isEqualTo(1);
        UserPushDelivery s = summary();
        assertThat(s).isNotNull();
        assertThat(s.getStatus()).isEqualTo("PENDING");
        assertThat(s.getNextAttemptAt()).as("metin kesinleşti → kira kalktı").isNull();
        assertThat(s.getUsername()).isEqualTo(USER);
        assertThat(s.getAlertLevel()).isEqualTo("CRITICAL");
        assertThat(s.getTeamId()).isEqualTo(4L);
        assertThat(s.getPushLang()).isEqualTo("tr");
        assertThat(s.getMessage()).startsWith("SiteMonitor: saat tavanı nedeniyle 3 bildirim gönderilmedi (2 kritik, 1 uyarı). Son: site-x - KRİTİK (")
                .endsWith("Ayrıntılar SiteMonitor'da.");
        assertThat(PushText.isChannelSafe(s.getMessage())).isTrue();
        assertThat(overflow).allSatisfy(d -> {
            assertThat(d.getOverflowSummaryId()).isEqualTo(s.getId());
            assertThat(d.getStatus()).isEqualTo("RATE_LIMITED");
        });
        verify(push).kickDrain();
        // Özet saat tavanından MUAF: tavan sayımı hiç sorulmaz (sorgu servis dışında — burada repo üzerinden).
        verify(repo, never()).countRecentForUser(anyString(), anyString());
    }

    @Test
    @DisplayName("aralık dolmadan özet YOK; bu aralıkta zaten bir özet gittiyse ikinci özet YOK")
    void notDue_orRecentSummary_noSummary() {
        user();
        rateLimited(1, "WARNING", "a", NOW.minusSeconds(5 * 60), null);
        candidate(NOW.minusSeconds(5 * 60));
        assertThat(service.sweep(NOW).summaries()).isZero();

        candidate(NOW.minusSeconds(30 * 60));
        when(repo.lastOverflowSummaryAt(USER)).thenReturn(ISO.format(NOW.minusSeconds(10 * 60)));
        assertThat(service.sweep(NOW).summaries()).isZero();
        assertThat(saved).isEmpty();
    }

    @Test
    @DisplayName("EN MOST ONCE: başka tur satırları önce sahiplendiyse özet satırı silinir, ikinci özet doğmaz")
    void claimLost_summaryDeleted() {
        user();
        rateLimited(1, "WARNING", "a", NOW.minusSeconds(30 * 60), null);
        candidate(NOW.minusSeconds(30 * 60));
        org.mockito.Mockito.doReturn(0).when(repo).claimOverflow(any(), any());

        UserPushOverflowService.SweepResult r = service.sweep(NOW);

        assertThat(r.summaries()).isZero();
        verify(repo).delete(any(UserPushDelivery.class));
        verify(push, never()).kickDrain();
    }

    @Test
    @DisplayName("kısmi sahiplenme: metin YALNIZ gerçekten bağlanan satırlarla yeniden kurulur")
    void partialClaim_rebuildsMessage() {
        user();
        rateLimited(1, "WARNING", "a", NOW.minusSeconds(30 * 60), null);
        rateLimited(2, "WARNING", "b", NOW.minusSeconds(29 * 60), null);
        candidate(NOW.minusSeconds(30 * 60));
        doAnswer(i -> {
            overflow.get(1).setOverflowSummaryId(i.getArgument(1));   // yalnız 2. satır bu özete
            return 1;
        }).when(repo).claimOverflow(any(), any());
        service.sweep(NOW);
        assertThat(summary().getMessage()).contains("1 bildirim gönderilmedi (1 uyarı)").contains("Son: b - UYARI");
    }

    @Test
    @DisplayName("opt-out / pasif hesap / sistem bakımı: özet GİTMEZ ama satırlar bağlanır ve özet satırı nedeniyle yazılır (iz)")
    void optOut_inactive_maintenance_leaveTrace() {
        AppUser u = user();
        u.setPushOptOut(true);
        rateLimited(1, "CRITICAL", "a", NOW.minusSeconds(30 * 60), null);
        candidate(NOW.minusSeconds(30 * 60));
        assertThat(service.sweep(NOW).skipped()).isEqualTo(1);
        assertThat(summary().getStatus()).isEqualTo("SKIPPED_USER_OPT_OUT");
        assertThat(overflow.get(0).getOverflowSummaryId()).isEqualTo(summary().getId());
        verify(push, never()).kickDrain();

        saved.clear(); overflow.clear();
        u.setPushOptOut(false);
        u.setActive(false);
        rateLimited(2, "CRITICAL", "a", NOW.minusSeconds(30 * 60), null);
        service.sweep(NOW);
        assertThat(summary().getStatus()).isEqualTo(UserPushRecipientResolver.SKIPPED_USER_INACTIVE);

        saved.clear(); overflow.clear();
        u.setActive(true);
        when(push.systemMaintenanceMuted()).thenReturn(true);
        rateLimited(3, "CRITICAL", "a", NOW.minusSeconds(30 * 60), null);
        service.sweep(NOW);
        assertThat(summary().getStatus()).isEqualTo(SystemMaintenanceService.PUSH_SKIPPED);
        verify(push, never()).kickDrain();
    }

    @Test
    @DisplayName("kişisel sessiz saat: UYARI özeti ERTELENİR (sahiplenme yok); içerikte KRİTİK varsa hemen gider")
    void personalQuiet_defersUnlessCritical() {
        AppUser u = user();
        u.setPushQuietStart("13:00");   // İstanbul 14:30 → pencerede
        u.setPushQuietEnd("18:00");
        rateLimited(1, "WARNING", "a", NOW.minusSeconds(30 * 60), null);
        candidate(NOW.minusSeconds(30 * 60));
        UserPushOverflowService.SweepResult r = service.sweep(NOW);
        assertThat(r.deferred()).isEqualTo(1);
        assertThat(saved).isEmpty();
        assertThat(overflow.get(0).getOverflowSummaryId()).isNull();

        rateLimited(2, "CRITICAL", "b", NOW.minusSeconds(20 * 60), null);
        assertThat(service.sweep(NOW).summaries()).isEqualTo(1);
    }

    @Test
    @DisplayName("susturma: ertelenir; 'kritikler yine gelsin' + KRİTİK içerik gider; o seçenek kapalıyken kritik de ertelenir")
    void snooze_defersUnlessCriticalAllowed() {
        AppUser u = user();
        u.setPushSnoozeUntil(ISO.format(NOW.plusSeconds(3600)));
        rateLimited(1, "CRITICAL", "a", NOW.minusSeconds(30 * 60), null);
        candidate(NOW.minusSeconds(30 * 60));
        assertThat(service.sweep(NOW).summaries()).as("kritikler yine gelsin (varsayılan)").isEqualTo(1);

        saved.clear(); overflow.clear();
        u.setPushSnoozeCritical(false);
        rateLimited(2, "CRITICAL", "a", NOW.minusSeconds(30 * 60), null);
        assertThat(service.sweep(NOW).deferred()).isEqualTo(1);
        assertThat(saved).isEmpty();

        u.setPushSnoozeCritical(true);
        overflow.clear();
        rateLimited(3, "HIGH", "a", NOW.minusSeconds(30 * 60), null);
        assertThat(service.sweep(NOW).deferred()).isEqualTo(1);
    }

    @Test
    @DisplayName("global push sessiz saati özeti de erteler")
    void globalQuiet_defers() {
        user();
        when(push.quietHoursBlock("WARNING")).thenReturn(true);
        rateLimited(1, "WARNING", "a", NOW.minusSeconds(30 * 60), null);
        candidate(NOW.minusSeconds(30 * 60));
        assertThat(service.sweep(NOW).deferred()).isEqualTo(1);
    }

    @Test
    @DisplayName("özet kişinin push dilinde (EN); karışık takımlı satırlarda özet takımsız")
    void englishSummary_mixedTeams() {
        AppUser u = user();
        u.setPushLang("en");
        rateLimited(1, "HIGH", "a", NOW.minusSeconds(30 * 60), 4L);
        rateLimited(2, "WARNING", "b", NOW.minusSeconds(29 * 60), 5L);
        candidate(NOW.minusSeconds(30 * 60));
        service.sweep(NOW);
        assertThat(summary().getMessage()).startsWith("SiteMonitor: 2 notifications were held back by the hourly limit (1 high, 1 warning). Latest: b - WARNING (");
        assertThat(summary().getPushLang()).isEqualTo("en");
        assertThat(summary().getTeamId()).isNull();
    }

    @Test
    @DisplayName("iş: kanal ya da özet kapalıyken kilit bile alınmaz; açıkken scheduler_lock 'push-overflow' altında koşar")
    void scheduledSweep_gatesAndLock() {
        when(push.overflowSummaryEnabled()).thenReturn(false);
        service.scheduledSweep();
        verify(scheduler, never()).runWithSchedulerLock(anyString(), any());

        when(push.overflowSummaryEnabled()).thenReturn(true);
        when(repo.overflowCandidates(anyString())).thenReturn(List.of());
        service.scheduledSweep();
        verify(scheduler).runWithSchedulerLock(eq("push-overflow"), any());
    }
}
