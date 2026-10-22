package com.sitemonitor.service;

import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.NotificationLog;
import com.sitemonitor.model.Team;
import com.sitemonitor.model.UptimeCheck;
import com.sitemonitor.model.WeeklyAvailabilityLog;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.EscalationContactRepository;
import com.sitemonitor.repository.LatestCheckRepository;
import com.sitemonitor.repository.NotificationLogRepository;
import com.sitemonitor.repository.PageSpeedCheckRepository;
import com.sitemonitor.repository.PageSpeedMonitorRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.repository.UptimeCheckRepository;
import com.sitemonitor.repository.WeeklyAvailabilityLogRepository;
import com.sitemonitor.service.report.WeeklyOutageReportService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Haftalık erişilebilirlik postası — takım × hafta EN ÇOK BİR KEZ (2026-10-21, çok pod MP-40). Gerçek SQL (H2):
 * {@code weekly_availability_log} sahiplenmesi + {@code notification_logs} izi; test işlemi KAPALI
 * ({@code NOT_SUPPORTED}) — uygulamadaki gibi her adım kendi işleminde kesinleşir. İki servis örneği = iki pod.
 */
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class WeeklyAvailabilityClaimTest {

    @Autowired WeeklyAvailabilityLogRepository walRepo;
    @Autowired NotificationLogRepository notificationLogRepo;

    private final TeamRepository teamRepo = mock(TeamRepository.class);
    private final CertificateInventoryRepository inventoryRepo = mock(CertificateInventoryRepository.class);
    private final UptimeCheckRepository uptimeRepo = mock(UptimeCheckRepository.class);
    private final LatestCheckRepository latestRepo = mock(LatestCheckRepository.class);
    private final EscalationContactRepository contactRepo = mock(EscalationContactRepository.class);
    private final AppUserRepository userRepo = mock(AppUserRepository.class);
    private final EmailNotificationService email = mock(EmailNotificationService.class);
    private final AppSettingsService appSettings = mock(AppSettingsService.class);
    private final WeeklyOutageReportService outage = mock(WeeklyOutageReportService.class);
    private final DeploymentHistoryService deployments = mock(DeploymentHistoryService.class);

    private static final Instant NOW = Instant.parse("2026-10-19T07:05:00Z");   // Pazartesi 10:05 İstanbul
    private static final DateTimeFormatter UTC = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    /** Takım adı → gönderilen posta sayısı. */
    private final Map<String, AtomicInteger> mailsByTeam = new ConcurrentHashMap<>();
    /** Bu takımın postası istisna fırlatır (pod / SMTP hatası). */
    private volatile String failingTeam;
    private ExecutorService pool;

    @BeforeEach
    void setUp() {
        walRepo.deleteAll();
        notificationLogRepo.deleteAll();
        mailsByTeam.clear();
        failingTeam = null;
        pool = Executors.newFixedThreadPool(2);

        List<Team> teams = List.of(team(1L, "Alfa"), team(2L, "Beta"), team(3L, "Gama"));
        when(teamRepo.findByActiveTrueOrderByNameAsc()).thenReturn(teams);
        when(inventoryRepo.findByTeamIdAndActiveTrueAndDeletedAtIsNullOrderByDomainAsc(anyLong()))
                .thenAnswer(inv -> List.of(inv("t" + inv.getArgument(0) + ".example.com")));
        when(uptimeRepo.findWindowForDomains(any(), any(), any())).thenAnswer(inv -> {
            Collection<String> ds = inv.getArgument(0);
            List<UptimeCheck> out = new ArrayList<>();
            for (String d : ds) {
                UptimeCheck c = new UptimeCheck();
                c.setDomain(d); c.setPort(443); c.setStatus("up"); c.setResponseMs(100L); c.setCheckedAt("2026-10-14T00:00:00");
                out.add(c);
            }
            return out;
        });
        when(latestRepo.findById(anyString())).thenReturn(Optional.empty());
        when(appSettings.getBoolean(eq("site.monitor.weekly-availability.enabled"), anyBoolean())).thenReturn(true);
        when(email.getEmailFrom()).thenReturn("noreply@sitemonitor");
        when(email.buildWeeklyAvailabilityHtml(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn("<html>rapor</html>");
        when(email.sendHtmlWithAttachments(any(), any(), anyString(), anyString(), any(), any())).thenAnswer(inv -> {
            String subject = inv.getArgument(2);
            String team = subject.replaceAll(".*\\] (\\S+) — .*", "$1");
            if (team.equals(failingTeam)) throw new IllegalStateException("posta katmanı patladı");
            mailsByTeam.computeIfAbsent(team, k -> new AtomicInteger()).incrementAndGet();
            return "SENT";
        });
        when(outage.collect(any(), any(), any())).thenReturn(null);
        when(deployments.currentEnvironment()).thenReturn("prod");
        when(deployments.derivedFor(any())).thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    private WeeklyAvailabilityReportService pod() {
        WeeklyAvailabilityReportService s = new WeeklyAvailabilityReportService(teamRepo, inventoryRepo, uptimeRepo,
                latestRepo, contactRepo, userRepo, email, notificationLogRepo, walRepo, appSettings, outage,
                mock(PageSpeedMonitorRepository.class), mock(PageSpeedCheckRepository.class), deployments);
        s.useClock(Clock.fixed(NOW, ZoneOffset.UTC));
        return s;
    }

    private static Team team(Long id, String name) {
        Team t = new Team();
        t.setId(id); t.setName(name); t.setEmail(name.toLowerCase() + "@x.com"); t.setActive(true);
        t.setWeeklyAvailabilityEnabled(true);
        return t;
    }

    private static CertificateInventory inv(String domain) {
        CertificateInventory c = new CertificateInventory();
        c.setDomain(domain); c.setPort(443);
        return c;
    }

    private int mails(String team) {
        AtomicInteger n = mailsByTeam.get(team);
        return n == null ? 0 : n.get();
    }

    private Map<Long, String> rowStatuses(WeeklyAvailabilityReportService.Window w) {
        Map<Long, String> m = new java.util.TreeMap<>();
        for (WeeklyAvailabilityLog l : walRepo.findByReportYearAndWeekNo(w.year(), w.week())) m.put(l.getTeamId(), l.getStatus());
        return m;
    }

    private List<NotificationLog> logs() {
        return notificationLogRepo.findAll();
    }

    private void seed(Long teamId, WeeklyAvailabilityReportService.Window w, String status, Instant stamp) {
        WeeklyAvailabilityLog l = new WeeklyAvailabilityLog();
        l.setTeamId(teamId); l.setReportYear(w.year()); l.setWeekNo(w.week());
        l.setStatus(status); l.setSentAt(UTC.format(stamp)); l.setCreatedAt(UTC.format(stamp));
        walRepo.save(l);
    }

    @Test
    @DisplayName("bir takımın postası patlar → diğer takımlar gönderilir ve KAYDEDİLİR; patlayan FAILED + notification_logs izi")
    void oneFailingTeamDoesNotStopTheOthers() {
        failingTeam = "Beta";
        WeeklyAvailabilityReportService s = pod();

        var result = s.sendWeeklyReports(false);

        assertThat(result.sent()).isEqualTo(2);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(mails("Alfa")).isEqualTo(1);
        assertThat(mails("Gama")).isEqualTo(1);
        var w = s.lastFullWeekWindow();
        assertThat(rowStatuses(w)).hasSize(3)
                .containsEntry(1L, "SENT").containsEntry(2L, "FAILED").containsEntry(3L, "SENT");
        assertThat(logs()).hasSize(3);
        assertThat(logs()).filteredOn(n -> "Beta".equals(n.getRecipientName()))
                .singleElement().satisfies(n -> {
                    assertThat(n.getEmailStatus()).startsWith("FAILED");
                    assertThat(n.getTrigger()).isEqualTo("WEEKLY_AVAILABILITY");
                });

        // Yeniden koşu: SENT takımlara İKİNCİ posta yok; FAILED (posta gitmemişti) yeniden denenir
        failingTeam = null;
        var again = s.sendWeeklyReports(false);
        assertThat(again.skippedDone()).isEqualTo(2);
        assertThat(again.sent()).isEqualTo(1);
        assertThat(mails("Alfa")).isEqualTo(1);
        assertThat(mails("Beta")).isEqualTo(1);
        assertThat(mails("Gama")).isEqualTo(1);
        assertThat(rowStatuses(w).values()).containsOnly("SENT");
    }

    @Test
    @DisplayName("çökme sonrası yeniden koşu: gönderilmiş takıma ikinci posta yok; yarıda kalan yalnız ELLE devralınır, bir kez")
    void rerunAfterCrashSendsNothingTwice() {
        WeeklyAvailabilityReportService s = pod();
        var w = s.lastFullWeekWindow();
        // Pod, Alfa'yı gönderip Beta'yı gönderirken öldü; Gama'ya hiç gelmedi.
        seed(1L, w, "SENT", NOW.minus(Duration.ofMinutes(5)));
        seed(2L, w, "SENDING", NOW.minus(Duration.ofMinutes(5)));

        var r1 = s.sendWeeklyReports(false);          // hemen yeniden koşu (ör. tur yeniden alındı)
        assertThat(r1.sent()).isEqualTo(1);           // yalnız Gama
        assertThat(r1.skippedDone()).isEqualTo(1);
        assertThat(r1.skippedInProgress()).isEqualTo(1);   // Beta taze SENDING — başka koşu gönderiyor olabilir
        assertThat(mails("Alfa")).isZero();
        assertThat(mails("Beta")).isZero();
        assertThat(mails("Gama")).isEqualTo(1);

        // 40 dk sonra Beta hâlâ SENDING → yarıda kalmış. Zamanlanmış koşu DEVRALMAZ (posta gitmiş olabilir).
        WeeklyAvailabilityReportService later = pod();
        later.useClock(Clock.fixed(NOW.plus(Duration.ofMinutes(40)), ZoneOffset.UTC));
        var r2 = later.sendWeeklyReports(false);
        assertThat(r2.interrupted()).isEqualTo(1);
        assertThat(r2.sent()).isZero();
        assertThat(mails("Beta")).isZero();
        assertThat(later.displayStatus(walRepo.findByTeamIdAndReportYearAndWeekNo(2L, w.year(), w.week()).orElseThrow()))
                .isEqualTo("INTERRUPTED");

        // Elle koşu yarıda kalanı devralır — yalnız Beta, bir kez.
        var r3 = later.sendWeeklyReports(true);
        assertThat(r3.sent()).isEqualTo(1);
        assertThat(r3.skippedDone()).isEqualTo(2);
        assertThat(mails("Beta")).isEqualTo(1);

        // İkinci elle koşu: hiçbir şey gönderilmez.
        var r4 = later.sendWeeklyReports(true);
        assertThat(r4.sent()).isZero();
        assertThat(r4.skippedDone()).isEqualTo(3);
        assertThat(mailsByTeam.values().stream().mapToInt(AtomicInteger::get).sum()).isEqualTo(2);
        assertThat(rowStatuses(w).values()).containsOnly("SENT");
    }

    @Test
    @DisplayName("iki pod aynı anda koşar → her takıma TEK posta, her takımın TEK satırı ve TEK izi")
    void twoPodsRace_onePerTeam() throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicInteger reads = new AtomicInteger();
        // Her iki pod da her takımın envanterini okuduktan sonra sahiplenmeye uzansın (en kötü yarış).
        when(inventoryRepo.findByTeamIdAndActiveTrueAndDeletedAtIsNullOrderByDomainAsc(anyLong())).thenAnswer(inv -> {
            if (reads.incrementAndGet() <= 6) {
                try { barrier.await(10, TimeUnit.SECONDS); } catch (Exception e) { throw new IllegalStateException(e); }
            }
            return List.of(inv("t" + inv.getArgument(0) + ".example.com"));
        });
        WeeklyAvailabilityReportService p1 = pod();
        WeeklyAvailabilityReportService p2 = pod();

        Future<WeeklyAvailabilityReportService.SendResult> f1 = pool.submit(() -> p1.sendWeeklyReports(false));
        Future<WeeklyAvailabilityReportService.SendResult> f2 = pool.submit(() -> p2.sendWeeklyReports(true));
        var a = f1.get(60, TimeUnit.SECONDS);
        var b = f2.get(60, TimeUnit.SECONDS);

        assertThat(a.sent() + b.sent()).isEqualTo(3);
        assertThat(a.failed() + b.failed()).isZero();
        assertThat(mails("Alfa")).isEqualTo(1);
        assertThat(mails("Beta")).isEqualTo(1);
        assertThat(mails("Gama")).isEqualTo(1);
        var w = p1.lastFullWeekWindow();
        assertThat(walRepo.findByReportYearAndWeekNo(w.year(), w.week())).hasSize(3)
                .extracting(WeeklyAvailabilityLog::getStatus).containsOnly("SENT");
        assertThat(logs()).hasSize(3);
    }

    @Test
    @DisplayName("alıcısız takım: NO_RECIPIENT satırı + 'SKIPPED: alıcı yok' izi; posta kapalı → SKIPPED (SENT değil), sonra yeniden denenir")
    void noRecipientAndMailOffAreRecordedAndRetried() {
        Team noMail = team(4L, "Delta");
        noMail.setEmail(null);
        when(teamRepo.findByActiveTrueOrderByNameAsc()).thenReturn(List.of(noMail, team(1L, "Alfa")));
        when(email.sendHtmlWithAttachments(any(), any(), anyString(), anyString(), any(), any())).thenReturn("SKIPPED_DISABLED");
        WeeklyAvailabilityReportService s = pod();
        var w = s.lastFullWeekWindow();

        var r = s.sendWeeklyReports(false);

        assertThat(r.skippedNoRecipient()).isEqualTo(1);
        assertThat(r.skippedMailOff()).isEqualTo(1);
        assertThat(r.sent()).isZero();
        assertThat(rowStatuses(w)).containsEntry(4L, "NO_RECIPIENT").containsEntry(1L, "SKIPPED");
        assertThat(logs()).extracting(NotificationLog::getEmailStatus)
                .containsExactlyInAnyOrder("SKIPPED: alıcı yok", "SKIPPED_DISABLED");

        // Posta kanalı açıldı → elle koşu, kapalıyken atlanan takımı gönderir (eskiden SENT sayılıp hiç gitmiyordu).
        when(email.sendHtmlWithAttachments(any(), any(), anyString(), anyString(), any(), any())).thenReturn("SENT");
        var again = s.sendWeeklyReports(true);
        assertThat(again.sent()).isEqualTo(1);
        assertThat(rowStatuses(w)).containsEntry(1L, "SENT");
    }

    @Test
    @DisplayName("2026-10-22 (çok pod inceleme CORE-2 / F5): yakalama yalnız bu haftanın SATIRI OLMAYAN takımlarına gönderir; "
            + "SENT / FAILED / yarıda kalmış satıra dokunmaz; iki pod aynı anda yakalarsa yine tek posta")
    void catchUpSendsOnlyToTeamsWithoutARow() throws Exception {
        WeeklyAvailabilityReportService p1 = pod();
        WeeklyAvailabilityReportService p2 = pod();
        var w = p1.lastFullWeekWindow();
        // Pazartesi koşusu Alfa'yı gönderdi, Beta'da patladı, sonra pod öldü — Gama hiç sahiplenilmedi (satırsız, izsiz).
        seed(1L, w, "SENT", NOW.minus(Duration.ofMinutes(4)));
        seed(2L, w, "FAILED", NOW.minus(Duration.ofMinutes(3)));

        CyclicBarrier start = new CyclicBarrier(2);
        Future<WeeklyAvailabilityReportService.SendResult> a = pool.submit(() -> { start.await(10, TimeUnit.SECONDS); return p1.sendMissingWeeklyReports(); });
        Future<WeeklyAvailabilityReportService.SendResult> b = pool.submit(() -> { start.await(10, TimeUnit.SECONDS); return p2.sendMissingWeeklyReports(); });
        int sent = a.get(30, TimeUnit.SECONDS).sent() + b.get(30, TimeUnit.SECONDS).sent();

        assertThat(sent).isEqualTo(1);
        assertThat(mails("Gama")).isEqualTo(1);
        assertThat(mails("Alfa")).as("SENT satırı").isZero();
        assertThat(mails("Beta")).as("FAILED satırı yakalamada yeniden denenmez (elle koşu)").isZero();
        assertThat(rowStatuses(w)).containsEntry(1L, "SENT").containsEntry(2L, "FAILED").containsEntry(3L, "SENT");
        assertThat(p1.sendMissingWeeklyReports().sent()).as("sonraki saatlik tik").isZero();
        assertThat(mails("Gama")).isEqualTo(1);
    }
}
