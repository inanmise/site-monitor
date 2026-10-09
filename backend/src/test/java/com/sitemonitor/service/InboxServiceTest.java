package com.sitemonitor.service;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.MaintenanceWindow;
import com.sitemonitor.model.WeakAlgorithmException;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.MaintenanceWindowRepository;
import com.sitemonitor.repository.WeakAlgorithmExceptionRepository;
import com.sitemonitor.repository.WeeklyReportRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/** Bildirim kutusu (2026-09-12, #2): tür karışımı, kapsam, sıralama (kritik açık alarm üstte), 24 saat/24 saat eşikleri. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InboxServiceTest {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    @Mock AlertEventRepository alertEventRepo;
    @Mock CertificateInventoryRepository inventoryRepo;
    @Mock MaintenanceWindowRepository maintenanceRepo;
    @Mock MaintenanceService maintenanceService;
    @Mock WeakAlgorithmExceptionRepository exceptionRepo;
    @Mock WeeklyReportRepository weeklyReportRepo;
    @Mock AppSettingsService appSettings;
    InboxService svc;

    private static AlertEvent ev(long id, String domain, Long team, String level, boolean resolved, int resolvedHoursAgo) {
        AlertEvent e = new AlertEvent(); e.setId(id); e.setDomain(domain); e.setTeamId(team); e.setAlertLevel(level); e.setAlertType("HTTP_DOWN");
        e.setCreatedAt(ISO.format(Instant.now().minus(2, ChronoUnit.DAYS))); e.setResolved(resolved);
        if (resolved) e.setResolvedAt(ISO.format(Instant.now().minus(resolvedHoursAgo, ChronoUnit.HOURS)));
        return e;
    }

    @BeforeEach
    void setUp() {
        svc = new InboxService(alertEventRepo, inventoryRepo, maintenanceRepo, maintenanceService, exceptionRepo, weeklyReportRepo, appSettings);
        CertificateInventory a = new CertificateInventory(); a.setDomain("a.example.com"); a.setTeamId(1L); a.setActive(true);
        CertificateInventory b = new CertificateInventory(); b.setDomain("b.example.com"); b.setTeamId(2L); b.setActive(true);
        when(inventoryRepo.findByActiveTrueOrderByDomainAsc()).thenReturn(List.of(a, b));
        when(alertEventRepo.findAllOpenOrderBySeverity()).thenReturn(List.of(ev(1, "a.example.com", 1L, "WARNING", false, 0), ev(2, "a.example.com", 1L, "CRITICAL", false, 0), ev(3, "b.example.com", 2L, "CRITICAL", false, 0)));
        when(alertEventRepo.findResolvedSinceCreatedSince(anyString(), anyString())).thenReturn(List.of(ev(4, "a.example.com", 1L, "WARNING", true, 3), ev(5, "a.example.com", 1L, "WARNING", true, 30)));
        // Gelen kutusu GEÇMİŞİ hâlâ 30 günlük sorguyu kullanır (yalnız 24 saatlik "çözülenler" daraldı)
        when(alertEventRepo.findByCreatedAtGreaterThanEqualOrderByCreatedAtDesc(anyString())).thenReturn(List.of(ev(4, "a.example.com", 1L, "WARNING", true, 3), ev(5, "a.example.com", 1L, "WARNING", true, 30)));
        MaintenanceWindow active = new MaintenanceWindow(); active.setId(7L); active.setName("Gece bakımı"); active.setTeamId(1L); active.setActive(true); active.setStartAt(ISO.format(Instant.now().minus(1, ChronoUnit.HOURS)));
        MaintenanceWindow soon = new MaintenanceWindow(); soon.setId(8L); soon.setName("Yarın"); soon.setTeamId(null); soon.setActive(true); soon.setStartAt(ISO.format(Instant.now().plus(5, ChronoUnit.HOURS)));
        MaintenanceWindow far = new MaintenanceWindow(); far.setId(9L); far.setName("Uzak"); far.setTeamId(null); far.setActive(true); far.setStartAt(ISO.format(Instant.now().plus(5, ChronoUnit.DAYS)));
        when(maintenanceRepo.findByActiveTrue()).thenReturn(List.of(active, soon, far));
        when(maintenanceService.isActiveAt(eq(active), any())).thenReturn(true);
        when(maintenanceService.nextOccurrence(eq(soon), any())).thenReturn(ISO.format(Instant.now().plus(5, ChronoUnit.HOURS)));
        when(maintenanceService.nextOccurrence(eq(far), any())).thenReturn(ISO.format(Instant.now().plus(5, ChronoUnit.DAYS)));
        // son giriş günü = BUGÜN, kendi takımının raporu yok
        String todayCode = LocalDate.now(ZoneId.of("Europe/Istanbul")).getDayOfWeek().name().substring(0, 3);
        when(appSettings.getString(eq(WeeklyReportDeadline.KEY_DAY), any())).thenReturn(todayCode);
        when(appSettings.getString(eq(WeeklyReportDeadline.KEY_TIME), any())).thenReturn("15:00");
        when(weeklyReportRepo.findByTeamIdAndReportYearAndWeekNo(anyLong(), anyInt(), anyInt())).thenReturn(Optional.empty());
        WeakAlgorithmException ex = new WeakAlgorithmException(); ex.setDomain("a.example.com"); ex.setUntil(LocalDate.now(ZoneOffset.UTC).minusDays(1).toString()); ex.setReason("plan");
        when(exceptionRepo.findAll()).thenReturn(List.of(ex));
    }

    @Test
    @DisplayName("takım 1: 2 açık alarm (kritik üstte), 1 çözülen (30 sa önceki dışarıda), aktif bakım + yaklaşan (5 gün sonraki dışarıda), haftalık son giriş, dolan istisna; takım 2'ninki yok")
    void scopedInbox() {
        List<InboxService.Item> items = svc.build(t -> t != null && t == 1L, List.of(1L));
        assertThat(items).extracting(InboxService.Item::kind)
                .containsExactly("alert_open", "alert_open", "weekly_due", "maintenance_active", "exception_expired", "maintenance_soon", "alert_resolved");
        assertThat(items.get(0).level()).isEqualTo("CRITICAL");
        assertThat(items.get(0).key()).isEqualTo("alert:2");
        assertThat(items).extracting(InboxService.Item::key).doesNotContain("alert:3", "resolved:5");
        assertThat(items.stream().filter(i -> i.kind().equals("maintenance_soon")).findFirst().orElseThrow().params()).containsEntry("window", 8L);
        assertThat(items.stream().filter(i -> i.kind().equals("weekly_due")).findFirst().orElseThrow().tab()).isEqualTo("weeklyreports");

        // Açık alarm → ALARM GEÇMİŞİ (2026-09-16 kullanıcı bildirimi): "Uyarılar" sertifika uyarıları
        // sayfasıdır, alarm olayını tanımaz; tıklayan kişi alarmı bulamıyordu. Artık tip süzgeci +
        // alan adı araması + olay kimliği (kart vurgusu) ile doğru yere gider.
        InboxService.Item open = items.get(0);
        assertThat(open.tab()).isEqualTo("alerthistory");
        assertThat(open.params()).containsEntry("alert", 2L).containsEntry("type", "HTTP_DOWN");
        assertThat(open.params()).doesNotContainKey("view");   // açık sekmesi varsayılan

        InboxService.Item resolved = items.stream().filter(i -> i.kind().equals("alert_resolved")).findFirst().orElseThrow();
        assertThat(resolved.tab()).isEqualTo("alerthistory");
        assertThat(resolved.params()).containsEntry("view", "closed");
        assertThat(resolved.params()).containsKey("alert");
    }

    @Test
    @DisplayName("bir blok düşerse diğerleri kalır; kendi takımı olmayan global görüntüleyicide haftalık öğe yok")
    void degrades() {
        when(alertEventRepo.findAllOpenOrderBySeverity()).thenThrow(new IllegalStateException("db"));
        List<InboxService.Item> items = svc.build(t -> true, List.of());
        assertThat(items).extracting(InboxService.Item::kind).doesNotContain("alert_open", "weekly_due").contains("alert_resolved", "maintenance_active");
    }
    // ── 2026-09-20 zenginleştirme: takım adı, başlangıç/bitiş, izlemeye git, geçmiş sayfası ──

    @Test
    @DisplayName("Alarm satırı takım adı, started/ended ve izleme derin bağlantısı taşır (teamRepo/resolver alan enjeksiyonu)")
    void enrichedAlertItems() {
        com.sitemonitor.repository.TeamRepository teamRepo = org.mockito.Mockito.mock(com.sitemonitor.repository.TeamRepository.class);
        com.sitemonitor.model.Team t1 = new com.sitemonitor.model.Team(); t1.setId(1L); t1.setName("Takim A");
        when(teamRepo.findAll()).thenReturn(List.of(t1));
        MonitorRefResolver resolver = org.mockito.Mockito.mock(MonitorRefResolver.class);
        when(resolver.resolve(any())).thenAnswer(inv -> {
            java.util.Map<Long, MonitorRefResolver.Ref> m = new java.util.HashMap<>();
            for (AlertEvent e : (List<AlertEvent>) inv.getArgument(0)) m.put(e.getId(), new MonitorRefResolver.Ref("Site", "http", "http", 77L));
            return m;
        });
        org.springframework.test.util.ReflectionTestUtils.setField(svc, "teamRepo", teamRepo);
        org.springframework.test.util.ReflectionTestUtils.setField(svc, "monitorRefResolver", resolver);

        List<InboxService.Item> items = svc.build(t -> t != null && t == 1L, List.of(1L));
        InboxService.Item open = items.get(0);
        assertThat(open.teamId()).isEqualTo(1L);
        assertThat(open.teamName()).isEqualTo("Takim A");
        assertThat(open.startedAt()).isNotBlank();
        assertThat(open.endedAt()).isNull();
        assertThat(open.monitorTab()).isEqualTo("http");
        assertThat(open.monitorParams()).containsEntry("monitor", 77L);
        assertThat(open.monitorName()).isEqualTo("Site");
        InboxService.Item resolved = items.stream().filter(i -> i.kind().equals("alert_resolved")).findFirst().orElseThrow();
        assertThat(resolved.endedAt()).isNotBlank();
        // bakım satırı da takım adı taşır
        InboxService.Item maint = items.stream().filter(i -> i.kind().equals("maintenance_active")).findFirst().orElseThrow();
        assertThat(maint.teamName()).isEqualTo("Takim A");
    }

    @Test
    @DisplayName("Geçmiş: 30 günde çözülenler (24 saat sınırı YOK), çözülme zamanına göre, sayfalı; kapsam dışı takım yok")
    void historyPaged() {
        InboxService.HistoryPage h = svc.history(t -> t != null && t == 1L, 0, 1);
        assertThat(h.total()).isEqualTo(2);           // id 4 (3 sa) + id 5 (30 sa) — güncel listede 5 yoktu
        assertThat(h.totalPages()).isEqualTo(2);
        assertThat(h.items()).extracting(InboxService.Item::key).containsExactly("resolved:4:" + h.items().get(0).endedAt());
        InboxService.HistoryPage p2 = svc.history(t -> t != null && t == 1L, 1, 1);
        assertThat(p2.items().get(0).key()).startsWith("resolved:5:");
        assertThat(p2.items().get(0).kind()).isEqualTo("alert_resolved");
        assertThat(svc.history(t -> t != null && t == 2L, 0, 10).total()).isZero();
    }


    @Test
    @DisplayName("QA ISSUE-001: sertifika izleme bağlantısı Genel Bakış'a `domain` paramıyla gider (`q` yalnız ilk yüklemede okunur)")
    void certRefUsesDomainParam() {
        MonitorRefResolver.Ref ref = new MonitorRefResolver.Ref("a.example.com", "cert", "dashboard", null);
        assertThat(MonitorRefResolver.paramsFor(ref, "a.example.com")).containsEntry("domain", "a.example.com").doesNotContainKey("q");
        assertThat(MonitorRefResolver.paramsFor(ref, null)).isEmpty();
    }

    // ── Sorun bildirimi haberleri (2026-09-26) ───────────────────────────────

    private static com.sitemonitor.model.IssueReportComment row(long id, String kind, boolean byReporter, String body) {
        com.sitemonitor.model.IssueReportComment c = new com.sitemonitor.model.IssueReportComment();
        c.setId(id); c.setReportId(42L); c.setKind(kind); c.setByReporter(byReporter); c.setInternal(false); c.setBody(body);
        c.setAuthorUsername(byReporter ? "kullanici.x" : "someadmin"); c.setCreatedAt("2026-09-25T10:0" + id + ":00");
        return c;
    }

    @Test
    @DisplayName("sorun bildirimi haberleri: her herkese-açık yönetici hareketi TAM BİR satır (anahtar = satır kimliği), derin bağlantı raporu açar; yeniden açma YALNIZ global yöneticiye")
    void issueItems_oncePerAdminAction_reopenOnlyForGlobalAdmin() {
        var repo = org.mockito.Mockito.mock(com.sitemonitor.repository.IssueReportCommentRepository.class);
        org.springframework.test.util.ReflectionTestUtils.setField(svc, "issueCommentRepo", repo);
        com.sitemonitor.model.LoginIssueReport r = new com.sitemonitor.model.LoginIssueReport();
        r.setId(42L); r.setReportedAt("2026-09-20T10:00:00"); r.setUsername("kullanici.x"); r.setResolutionNote("çözüldü notu");
        var reply = row(1L, "COMMENT", false, "Merhaba, kaydınızı inceledik");
        var resolved = row(2L, "STATUS", false, "RESOLVED");
        var reopen = row(3L, "STATUS", true, "IN_PROGRESS");
        // Sorgu zaten byReporter=false & internal=false süzer (JPQL); servis her satırı BİR habere çevirir.
        when(repo.findAdminActivityForReporter(eq("kullanici.x"), anyString()))
                .thenReturn(List.of(new Object[]{resolved, r}, new Object[]{reply, r}));
        when(repo.findReopensSince(anyString())).thenReturn(List.<Object[]>of(new Object[]{reopen, r}));

        // Bildiren (USER): iki haber — durum + yanıt; yeniden açma haberi YOK; kullanıcı adı küçük harfe normalize
        List<InboxService.Item> user = svc.build(t -> true, List.of(1L), "Kullanici.X", false);
        var issue = user.stream().filter(i -> i.kind().startsWith("issue_")).toList();
        assertThat(issue).extracting(InboxService.Item::kind).containsExactly("issue_status_resolved", "issue_reply");
        assertThat(issue).extracting(InboxService.Item::key).containsExactly("issue:2", "issue:1");
        assertThat(issue.get(0).tab()).isEqualTo("login-issues");
        assertThat(issue.get(0).params()).containsEntry("ir_id", 42L);
        assertThat(issue.get(0).title()).isEqualTo("LIR-2026-000042");
        assertThat(issue.get(0).sub()).isEqualTo("çözüldü notu");
        assertThat(issue.get(0).level()).isEqualTo("OK");
        assertThat(issue.get(1).sub()).isEqualTo("Merhaba, kaydınızı inceledik");
        org.mockito.Mockito.verify(repo).findAdminActivityForReporter(eq("kullanici.x"), anyString());
        org.mockito.Mockito.verify(repo, org.mockito.Mockito.never()).findReopensSince(anyString());

        // Global yönetici: yeniden açma haberi (bildiren adı alt satırda, yönetici sekmesine derin bağlantı)
        when(repo.findAdminActivityForReporter(eq("someadmin"), anyString())).thenReturn(List.of());
        List<InboxService.Item> admin = svc.build(t -> true, List.of(1L), "someadmin", true);
        var re = admin.stream().filter(i -> i.kind().startsWith("issue_")).toList();
        assertThat(re).hasSize(1);
        assertThat(re.get(0).kind()).isEqualTo("issue_reopened");
        assertThat(re.get(0).key()).isEqualTo("issue:3");
        assertThat(re.get(0).sub()).isEqualTo("kullanici.x");
        assertThat(re.get(0).params()).containsEntry("ir_id", 42L).containsEntry("ir_view", "all");

        // Kapsamlı müdür (rol ADMIN ama global değil) ve eski iki-arg build: sorun haberi YOK
        assertThat(svc.build(t -> true, List.of(1L), "someadmin", false)).noneMatch(i -> i.kind().startsWith("issue_"));
        assertThat(svc.build(t -> true, List.of(1L))).noneMatch(i -> i.kind().startsWith("issue_"));
    }

    @Test
    @DisplayName("sorun bildirimi deposu yoksa (eski yapıcı / test bağlamı) haber üretilmez, kutu çökmez")
    void issueItems_withoutRepositoryIsEmpty() {
        assertThat(svc.build(t -> true, List.of(1L), "kullanici.x", true)).noneMatch(i -> i.kind().startsWith("issue_"));
    }
}
