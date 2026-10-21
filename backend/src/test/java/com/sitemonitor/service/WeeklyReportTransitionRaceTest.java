package com.sitemonitor.service;

import com.sitemonitor.model.EscalationContact;
import com.sitemonitor.model.Team;
import com.sitemonitor.model.WeeklyReport;
import com.sitemonitor.model.WeeklyReportMail;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.repository.EscalationContactRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.repository.WeeklyReportCommentRepository;
import com.sitemonitor.repository.WeeklyReportImageRepository;
import com.sitemonitor.repository.WeeklyReportMailRepository;
import com.sitemonitor.repository.WeeklyReportRepository;
import com.sitemonitor.service.WeeklyReportService.Actor;
import com.sitemonitor.service.WeeklyReportService.StateChangedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.Invocation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

/**
 * Haftalık rapor durum geçişleri İKİ POD / ÇİFT TIK yarışında (2026-10-21, çok pod MP-39) — gerçek SQL (H2), test
 * işlemi KAPALI ({@code NOT_SUPPORTED}): her sahiplenme kendi işleminde kesinleşir, uygulamadaki gibi.
 *
 * <p>İki {@link WeeklyReportService} örneği = iki pod (aynı veritabanı, aynı posta / push sahtesi). Bir engel
 * ({@link CyclicBarrier}) iki isteği de durum denetiminden SONRA, sahiplenmeden ÖNCE bekletir — yani ikisi de raporu
 * "onay bekliyor" okumuştur; eskiden ikisi de müdüre posta + push gönderiyordu. Artık yalnız kazanan gönderir, kaybeden
 * 409 {@link StateChangedException} alır.
 */
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class WeeklyReportTransitionRaceTest {

    @Autowired WeeklyReportRepository reportRepo;
    @Autowired WeeklyReportMailRepository mailRepo;
    @Autowired WeeklyReportImageRepository imageRepo;
    @Autowired WeeklyReportCommentRepository commentRepo;

    private final TeamRepository teamRepo = mock(TeamRepository.class);
    private final AppUserRepository userRepo = mock(AppUserRepository.class);
    private final EscalationContactRepository contactRepo = mock(EscalationContactRepository.class);
    private final EmailNotificationService email = mock(EmailNotificationService.class);
    private final AppSettingsService appSettings = mock(AppSettingsService.class);
    private final PermissionService permissions = mock(PermissionService.class);
    private final WeeklyReportKpiService kpi = mock(WeeklyReportKpiService.class);
    private final UserPushService push = mock(UserPushService.class);

    private WeeklyReportService pod1;
    private WeeklyReportService pod2;
    private ExecutorService pool;
    /** İlk iki MANAGER / KPI okuması engelde buluşur (iki istek de denetimi geçmiş, sahiplenmeye uzanıyor). */
    private CyclicBarrier barrier;
    private final AtomicInteger managerReads = new AtomicInteger();
    private final AtomicInteger kpiReads = new AtomicInteger();

    private static final Actor ADMIN_A = new Actor(1L, "admin", "Admin A", null, "ADMIN");
    private static final Actor ADMIN_B = new Actor(2L, "admin2", "Admin B", null, "ADMIN");

    @BeforeEach
    void setUp() {
        commentRepo.deleteAll();
        mailRepo.deleteAll();
        imageRepo.deleteAll();
        reportRepo.deleteAll();
        pod1 = service();
        pod2 = service();
        pool = Executors.newFixedThreadPool(2);
        barrier = new CyclicBarrier(2);
        managerReads.set(0);
        kpiReads.set(0);

        Team t = new Team();
        t.setId(2L); t.setName("TakimA"); t.setEmail("takim@test.com"); t.setWeeklyReportsEnabled(true);
        when(teamRepo.findById(2L)).thenReturn(Optional.of(t));
        when(contactRepo.findByTeamIdAndRoleAndActiveTrue(2L, "MANAGER")).thenAnswer(inv -> {
            if (managerReads.incrementAndGet() <= 2) awaitBarrier();
            return List.of(contact("MANAGER", "Ali Müdür", "mudur@test.com"));
        });
        when(contactRepo.findByTeamIdAndRoleAndActiveTrue(2L, "PO")).thenReturn(List.of(contact("PO", "PO", "po@test.com")));
        when(userRepo.findByTeamIdAndOrgRoleAndActiveTrue(anyLong(), anyString())).thenReturn(List.of());
        when(appSettings.getString(anyString(), any())).thenAnswer(i -> i.getArgument(1));
        when(email.fromAddress()).thenReturn("sitemonitor@test");
        when(email.sendHtml(any(), any(), anyString(), anyString(), any())).thenReturn("SENT");
        when(email.buildWeeklyReportRejectedHtml(any(), any(), any(), any())).thenReturn("<html>iade</html>");
        when(email.buildWeeklyReportHtml(any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any(), any()))
                .thenReturn("<html>rapor</html>");
        when(push.weeklyManagerEnabled()).thenReturn(true);
        when(push.weeklyTeamEnabled()).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    private WeeklyReportService service() {
        WeeklyReportService s = new WeeklyReportService(reportRepo, imageRepo, mailRepo, teamRepo, userRepo, contactRepo,
                email, new ObjectMapper(), appSettings, permissions, kpi, mock(MonitoringWeeklyStatsService.class),
                mock(com.sitemonitor.repository.DomainMonitorRepository.class),
                mock(com.sitemonitor.repository.DomainCheckRepository.class));
        ReflectionTestUtils.setField(s, "imageMaxBytes", 2L * 1024 * 1024);
        ReflectionTestUtils.setField(s, "commentRepo", commentRepo);
        ReflectionTestUtils.setField(s, "userPushService", push);
        return s;
    }

    private void awaitBarrier() {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("engel beklenemedi", e);
        }
    }

    private static EscalationContact contact(String role, String name, String mail) {
        EscalationContact c = new EscalationContact();
        c.setRole(role); c.setName(name); c.setEmail(mail); c.setActive(true); c.setTeamId(2L);
        return c;
    }

    private static String inSeconds(long s) {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC).format(Instant.now().plusSeconds(s));
    }

    private Long report(String status, String token) {
        WeeklyReport r = new WeeklyReport();
        r.setTeamId(2L);
        r.setReportYear(2026);
        r.setWeekNo(42);
        r.setWeekLabel("2026-W42 (12–18 Ekim 2026)");
        r.setStatus(status);
        r.setContentJson(WeeklyReportService.DEFAULT_TEMPLATE_JSON);
        r.setVersion(3);
        r.setApprovalToken(token);
        r.setApprovalTokenExpiresAt(token == null ? null : inSeconds(86_400));
        return reportRepo.save(r).getId();
    }

    /** İki görevi AYNI ANDA başlatır; her birinin sonucu ya dönüş değeri ya da fırlattığı istisnadır. */
    private List<Object> race(Callable<Object> a, Callable<Object> b) throws Exception {
        List<Future<Object>> fs = new ArrayList<>();
        for (Callable<Object> c : List.of(a, b)) {
            fs.add(pool.submit(() -> {
                try {
                    return c.call();
                } catch (Exception e) {
                    return e;
                }
            }));
        }
        List<Object> out = new ArrayList<>();
        for (Future<Object> f : fs) out.add(f.get(30, TimeUnit.SECONDS));
        return out;
    }

    private long mailsWithSubject(String part) {
        return mockingDetails(email).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("sendHtml"))
                .filter(i -> String.valueOf((Object) i.getArgument(2)).contains(part))
                .count();
    }

    private long pushCalls(String method) {
        return mockingDetails(push).getInvocations().stream().map(Invocation::getMethod)
                .filter(m -> m.getName().equals(method)).count();
    }

    private static List<StateChangedException> losers(List<Object> results) {
        return results.stream().filter(StateChangedException.class::isInstance).map(StateChangedException.class::cast).toList();
    }

    private static void noUnexpectedErrors(List<Object> results) {
        for (Object o : results) {
            if (o instanceof Exception e && !(e instanceof StateChangedException)) {
                throw new AssertionError("beklenmeyen hata: " + e, e);
            }
        }
    }

    private long mailRows(Long id, String type) {
        return mailRepo.findByReportIdOrderByIdDesc(id).stream().filter(m -> type.equals(m.getMailType())).count();
    }

    @Test
    @DisplayName("iki pod aynı raporu aynı anda onaylar → müdüre TEK posta, TEK müdür push'u, TEK takım push'u; kaybeden 409")
    void twoPodsApprove_oneMailOnePush() throws Exception {
        Long id = report("PENDING_APPROVAL", "TOK1");

        List<Object> res = race(() -> pod1.approve(id, ADMIN_A), () -> pod2.approve(id, ADMIN_B));

        noUnexpectedErrors(res);
        assertThat(losers(res)).hasSize(1);
        assertThat(losers(res).get(0).currentStatus()).isEqualTo("APPROVED");
        assertThat(losers(res).get(0).getCode()).isEqualTo(StateChangedException.CODE);
        assertThat(mailsWithSubject("Haftalık Rapor —")).isEqualTo(1);
        assertThat(pushCalls("enqueueDirectLocalized")).isEqualTo(1);
        assertThat(pushCalls("enqueueTeamNoticeLocalized")).isEqualTo(1);
        assertThat(mailRows(id, "APPROVE_MANAGER")).isEqualTo(1);
        assertThat(commentRepo.findByReportIdOrderByCreatedAtAsc(id)).extracting("kind").containsExactly("APPROVE");

        WeeklyReport db = reportRepo.findById(id).orElseThrow();
        assertThat(db.getStatus()).isEqualTo("APPROVED");
        assertThat(db.getVersion()).isEqualTo(4);            // tek geçiş: 3 → 4
        assertThat(db.getApprovalToken()).isNull();
        assertThat(db.getSentAt()).isNotNull();              // kazananın gönderimi aynı onay döngüsüne yazıldı
    }

    @Test
    @DisplayName("e-posta bağlantısı ∥ uygulama içi onay → müdüre TEK posta; push yalnız uygulama içi kazandıysa (bağlantı push atmaz)")
    void linkAndInAppRace_oneMail() throws Exception {
        Long id = report("PENDING_APPROVAL", "TOK2");

        List<Object> res = race(() -> pod1.approveViaToken("TOK2"), () -> pod2.approve(id, ADMIN_B));

        noUnexpectedErrors(res);
        assertThat(losers(res)).hasSize(1);
        boolean inAppWon = !(res.get(1) instanceof Exception);
        assertThat(mailsWithSubject("Haftalık Rapor —")).isEqualTo(1);
        assertThat(mailRows(id, "APPROVE_MANAGER")).isEqualTo(1);
        assertThat(pushCalls("enqueueTeamNoticeLocalized")).isEqualTo(inAppWon ? 1 : 0);
        WeeklyReport db = reportRepo.findById(id).orElseThrow();
        assertThat(db.getStatus()).isEqualTo("APPROVED");
        assertThat(db.getVersion()).isEqualTo(4);
        assertThat(db.getApprovedBy()).isEqualTo(inAppWon ? "Admin B" : "PO (e-posta onayı)");
    }

    @Test
    @DisplayName("onay ∥ iade → yalnız BİR karar: ya müdür postası ya iade postası, ikisi birden asla")
    void approveVersusReject_exactlyOneDecision() throws Exception {
        Long id = report("PENDING_APPROVAL", "TOK3");
        // İade yolunda MANAGER okunmaz — engeli PO adı okuması (iade) ve MANAGER okuması (onay) paylaşsın.
        AtomicInteger poReads = new AtomicInteger();
        when(userRepo.findByTeamIdAndOrgRoleAndActiveTrue(anyLong(), anyString())).thenAnswer(inv -> {
            if (poReads.incrementAndGet() <= 2) awaitBarrier();
            return List.of();
        });
        managerReads.set(2);   // onay yolu MANAGER okumasında beklemesin (engel PO okumasında)

        List<Object> res = race(() -> pod1.rejectViaToken("TOK3", "eksik"), () -> pod2.approveViaToken("TOK3"));

        noUnexpectedErrors(res);
        assertThat(losers(res)).hasSize(1);
        long managerMails = mailsWithSubject("Haftalık Rapor —");
        long rejectMails = mailsWithSubject("iade edildi");
        assertThat(managerMails + rejectMails).isEqualTo(1);
        WeeklyReport db = reportRepo.findById(id).orElseThrow();
        assertThat(db.getStatus()).isEqualTo(managerMails == 1 ? "APPROVED" : "REJECTED");
        assertThat(db.getVersion()).isEqualTo(4);
    }

    @Test
    @DisplayName("çift 'onaya gönder' → PO'ya TEK posta; veritabanındaki belirteç postadaki bağlantıyla AYNI (ikincisi ezmez)")
    void doubleSubmit_onePoMailTokenIntact() throws Exception {
        Long id = report("DRAFT", null);
        when(kpi.compute(anyLong(), anyInt(), anyInt())).thenAnswer(inv -> {
            if (kpiReads.incrementAndGet() <= 2) awaitBarrier();
            return null;
        });
        List<String> approveUrls = new java.util.concurrent.CopyOnWriteArrayList<>();
        when(email.buildWeeklyReportHtml(any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    Object url = inv.getArgument(9);
                    if (url != null) approveUrls.add(url.toString());
                    return "<html>rapor</html>";
                });

        List<Object> res = race(() -> pod1.submit(id, ADMIN_A), () -> pod2.submit(id, ADMIN_B));

        noUnexpectedErrors(res);
        assertThat(losers(res)).hasSize(1);
        assertThat(losers(res).get(0).currentStatus()).isEqualTo("PENDING_APPROVAL");
        assertThat(mailsWithSubject("onayınızı bekliyor")).isEqualTo(1);
        assertThat(mailRows(id, "SUBMIT_PO")).isEqualTo(1);
        WeeklyReport db = reportRepo.findById(id).orElseThrow();
        assertThat(db.getStatus()).isEqualTo("PENDING_APPROVAL");
        assertThat(db.getVersion()).isEqualTo(4);
        assertThat(approveUrls).hasSize(1);
        assertThat(approveUrls.get(0)).endsWith("token=" + db.getApprovalToken());   // PO'nun bağlantısı geçerli
    }

    @Test
    @DisplayName("müdür postası geçişten SONRA düşerse rapor ONAYLI kalır, kayıt FAILED, push 'başarısız' der, ikinci onay 409")
    void mailFailureAfterClaimKeepsApproved() {
        Long id = report("PENDING_APPROVAL", "TOK4");
        managerReads.set(2);
        when(email.sendHtml(any(), any(), anyString(), anyString(), any())).thenThrow(new RuntimeException("smtp patladı"));

        var out = pod1.approve(id, ADMIN_A);

        assertThat(String.valueOf(out.get("mail_status"))).startsWith("FAILED");
        WeeklyReport db = reportRepo.findById(id).orElseThrow();
        assertThat(db.getStatus()).isEqualTo("APPROVED");
        assertThat(db.getSentAt()).isNull();
        WeeklyReportMail row = mailRepo.findByReportIdOrderByIdDesc(id).get(0);
        assertThat(row.getMailType()).isEqualTo("APPROVE_MANAGER");
        assertThat(row.getStatus()).startsWith("FAILED");
        assertThat(pushCalls("enqueueTeamNoticeLocalized")).isEqualTo(1);
        // ardışık ikinci onay: durum değişti → 409, ikinci posta denemesi yok
        assertThatThrownBy(() -> pod2.approve(id, ADMIN_B)).isInstanceOf(IllegalStateException.class);
        assertThat(mockingDetails(email).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("sendHtml")).count()).isEqualTo(1);
    }

    @Test
    @DisplayName("bayat içerik kaydı / kalp atışı onaya gönderilmiş raporu taslağa çeviremez, PO bağlantısını silemez")
    void staleSaveCannotRevertSubmit() {
        Long id = report("DRAFT", null);
        kpiReads.set(2);
        WeeklyReport stale = reportRepo.findById(id).orElseThrow();   // istemcinin yüklediği sürüm 3
        pod1.submit(id, ADMIN_A);
        String token = reportRepo.findById(id).orElseThrow().getApprovalToken();
        assertThat(token).isNotBlank();

        // Bayat otomatik kayıt (istemci sürümü 3) → 409, rapor değişmez
        assertThatThrownBy(() -> pod2.saveContent(id, WeeklyReportService.DEFAULT_TEMPLATE_JSON, (long) stale.getVersion(), ADMIN_B))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("VERSION_CONFLICT");
        // Sunucu tarafı yarış: kayıt satırı "onaya gönder"den ÖNCE okumuştu (sürüm 3) → koşullu yazım 0 satır
        assertThat(reportRepo.updateContent(id, stale.getVersion(), WeeklyReportService.DEFAULT_TEMPLATE_JSON, "DRAFT",
                "Admin B", inSeconds(0))).isZero();
        // Kalp atışı yalnız kilit kolonlarını yazar
        reportRepo.setEditingLock(id, 2L, "Admin B", inSeconds(0));

        WeeklyReport db = reportRepo.findById(id).orElseThrow();
        assertThat(db.getStatus()).isEqualTo("PENDING_APPROVAL");
        assertThat(db.getApprovalToken()).isEqualTo(token);
        assertThat(db.getVersion()).isEqualTo(4);
    }

    @Test
    @DisplayName("çift 'tekrar gönder' → müdüre TEK posta; ardışık tekrar gönderim yine çalışır")
    void doubleResend_oneMail() throws Exception {
        Long id = report("APPROVED", null);
        WeeklyReport r = reportRepo.findById(id).orElseThrow();
        r.setApprovedBy("Admin A"); r.setApprovedAt(inSeconds(-60));
        reportRepo.save(r);

        List<Object> res = race(() -> pod1.resend(id, ADMIN_A), () -> pod2.resend(id, ADMIN_B));

        noUnexpectedErrors(res);
        assertThat(losers(res)).hasSize(1);
        assertThat(mailsWithSubject("Haftalık Rapor —")).isEqualTo(1);
        assertThat(reportRepo.findById(id).orElseThrow().getVersion()).isEqualTo(4);

        pod1.resend(id, ADMIN_A);   // bilinçli, ardışık yeniden gönderim engellenmez
        assertThat(mailsWithSubject("Haftalık Rapor —")).isEqualTo(2);
    }

    @Test
    @DisplayName("eski bağlantı: rapor iade + yeniden onaya gönderildiyse eski belirteçle karar verilemez, posta yok")
    void oldLinkAfterResubmitDoesNothing() {
        Long id = report("PENDING_APPROVAL", "OLD");
        managerReads.set(2);
        String at = inSeconds(0);
        assertThat(reportRepo.claimReject(id, "eksik", "PO", at)).isEqualTo(1);
        assertThat(reportRepo.updateContent(id, 4, WeeklyReportService.DEFAULT_TEMPLATE_JSON, "DRAFT", "Ekip", at)).isEqualTo(1);
        assertThat(reportRepo.claimSubmit(id, "Ekip", at, "NEW", inSeconds(86_400), null)).isEqualTo(1);

        assertThatThrownBy(() -> pod1.approveViaToken("OLD")).isInstanceOf(IllegalArgumentException.class);
        // Bağlantı yeniden gönderimden ÖNCE okunmuş olsaydı: belirteç koşulu sahiplenmeyi düşürür
        assertThat(reportRepo.claimApproveByToken(id, "OLD", "PO", at)).isZero();
        assertThat(WeeklyReportService.lostMessage("approve_link", "PENDING_APPROVAL")).contains("artık geçerli değil");
        assertThat(mailsWithSubject("Haftalık Rapor —")).isZero();
        assertThat(reportRepo.findById(id).orElseThrow().getApprovalToken()).isEqualTo("NEW");
    }
}
