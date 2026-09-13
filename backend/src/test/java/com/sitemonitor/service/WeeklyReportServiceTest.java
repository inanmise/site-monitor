package com.sitemonitor.service;

import com.sitemonitor.model.AppUser;
import com.sitemonitor.model.EscalationContact;
import com.sitemonitor.model.Team;
import com.sitemonitor.model.WeeklyReport;
import com.sitemonitor.model.WeeklyReportMail;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.repository.EscalationContactRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.repository.WeeklyReportImageRepository;
import com.sitemonitor.repository.WeeklyReportRepository;
import com.sitemonitor.service.WeeklyReportService.Actor;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.temporal.WeekFields;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WeeklyReportServiceTest {

    @Mock WeeklyReportRepository reportRepo;
    @Mock WeeklyReportImageRepository imageRepo;
    @Mock com.sitemonitor.repository.WeeklyReportMailRepository mailRepo;
    @Mock TeamRepository teamRepo;
    @Mock AppUserRepository userRepo;
    @Mock EscalationContactRepository contactRepo;
    @Mock EmailNotificationService emailService;
    @Mock AppSettingsService appSettings;
    @Mock PermissionService permissionService;
    @Mock WeeklyReportKpiService kpiService;
    @Mock MonitoringWeeklyStatsService monitoringStatsService;

    private WeeklyReportService service;

    private static final Actor ADMIN      = new Actor(1L, "admin", "Admin", null, "ADMIN");
    private static final Actor USER_T2    = new Actor(10L, "user2", "Kullanıcı İki", 2L, "USER");
    private static final Actor USER_T7    = new Actor(11L, "user7", "Kullanıcı Yedi", 7L, "USER");
    private static final Actor TADMIN_T2  = new Actor(12L, "tadmin", "Takım Admin", 2L, "TEAM_ADMIN");
    private static final Actor PO_T2      = new Actor(13L, "po2", "PO İki", 2L, "USER");

    @BeforeEach
    void setUp() {
        service = new WeeklyReportService(reportRepo, imageRepo, mailRepo, teamRepo, userRepo,
                contactRepo, emailService, new ObjectMapper(), appSettings, permissionService, kpiService, monitoringStatsService,
                // Alan adi koruma ozeti: bu testlerin hicbiri alan adi izlemesi kurmuyor -> bolum cizilmez.
                org.mockito.Mockito.mock(com.sitemonitor.repository.DomainMonitorRepository.class),
                org.mockito.Mockito.mock(com.sitemonitor.repository.DomainCheckRepository.class));
        ReflectionTestUtils.setField(service, "imageMaxBytes", 2L * 1024 * 1024);
        // TEAM_ADMIN'in haftalık rapor onay yetkisi artık matris üzerinden (varsayılan true).
        when(permissionService.allows("TEAM_ADMIN", "weekly_reports.approve", "execute")).thenReturn(true);
        when(appSettings.getString(anyString(), any())).thenAnswer(i -> i.getArgument(1));
        when(reportRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(mailRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(emailService.fromAddress()).thenReturn("sitemonitor@test");
        when(teamRepo.findById(2L)).thenReturn(Optional.of(team(2L, "TakimA", "takim@test.com")));
        when(emailService.sendHtml(any(), any(), anyString(), anyString(), any())).thenReturn("SENT");
        when(emailService.buildWeeklyReportSubmittedHtml(any(), any(), any(), any())).thenReturn("<html/>");
        when(emailService.buildWeeklyReportRejectedHtml(any(), any(), any(), any())).thenReturn("<html/>");
        when(emailService.buildWeeklyReportHtml(any(), any(), any(), any(), anyBoolean())).thenReturn("<html/>");
        when(emailService.buildWeeklyReportHtml(any(), any(), any(), any(), anyBoolean(), any())).thenReturn("<html/>");
        when(emailService.buildWeeklyReportHtml(any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any())).thenReturn("<html/>");
        when(emailService.buildWeeklyReportHtml(any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any())).thenReturn("<html/>");
        when(emailService.buildWeeklyReportHtml(any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any(), any())).thenReturn("<html/>");
        when(imageRepo.findByReportIdOrderByIdAsc(anyLong())).thenReturn(List.of());
        // PO yetki kontrolü DB'den okur
        AppUser po = new AppUser();
        po.setId(13L); po.setTeamId(2L); po.setOrgRole("PO");
        when(userRepo.findById(13L)).thenReturn(Optional.of(po));
        AppUser plain = new AppUser();
        plain.setId(10L); plain.setTeamId(2L); plain.setOrgRole("TECH");
        when(userRepo.findById(10L)).thenReturn(Optional.of(plain));
    }

    private static Team team(Long id, String name, String email) {
        Team t = new Team();
        t.setId(id); t.setName(name); t.setEmail(email);
        return t;
    }

    private static EscalationContact contact(String role, String name, String email) {
        EscalationContact c = new EscalationContact();
        c.setRole(role); c.setName(name); c.setEmail(email); c.setActive(true);
        return c;
    }

    /** Belirtilen tarihin ISO haftasına ait rapor — düzenleme penceresi
     *  (mevcut + önceki hafta) kuralı tarihten bağımsız test edilebilsin. */
    private static WeeklyReport reportAtWeek(Long id, Long teamId, String status, LocalDate ref) {
        WeeklyReport r = new WeeklyReport();
        r.setId(id); r.setTeamId(teamId);
        r.setReportYear(ref.get(WeekFields.ISO.weekBasedYear()));
        r.setWeekNo(ref.get(WeekFields.ISO.weekOfWeekBasedYear()));
        r.setWeekLabel("2026-W24 (8–12 Haziran 2026)");
        r.setStatus(status);
        r.setContentJson(WeeklyReportService.DEFAULT_TEMPLATE_JSON);
        return r;
    }

    /** İçinde bulunulan haftanın raporu (USER için düzenlenebilir pencerede). */
    private static WeeklyReport report(Long id, Long teamId, String status) {
        return reportAtWeek(id, teamId, status, WeeklyReportService.today());
    }

    // ── Şablon / oluşturma ────────────────────────────────────────────────────

    @Test
    @DisplayName("create: önceki rapordan şablon kopyalar — sayılar sıfır, kanal+URL korunur")
    void create_copiesTemplateFromPrevious() {
        String prev = """
            {"version":1,
             "item1":{"total":12,"urgent":2,"high":3,"medium":4,"low":3,"status_text":"Çalışılıyor","tracking_url":"https://jira/x","notes_md":"eski not"},
             "item2":{"open_incidents":1,"problem_records":2,"postmortems":1,"tracking_url":"https://jira/y","notes_md":"n"},
             "item3":{"notes_md":"çalışmalar"},
             "item4":{"channels":[{"id":"c-9","name":"İnternet","notes_md":"detay"}]}}
            """;
        WeeklyReport prevReport = report(1L, 2L, "APPROVED");
        prevReport.setContentJson(prev);
        when(reportRepo.findFirstByTeamIdOrderByReportYearDescWeekNoDesc(2L))
                .thenReturn(Optional.of(prevReport));
        when(reportRepo.findByTeamIdAndReportYearAndWeekNo(2L, 2026, 25)).thenReturn(Optional.empty());

        WeeklyReport created = service.create(null, 2026, 25, USER_T2);

        assertThat(created.getStatus()).isEqualTo("DRAFT");
        assertThat(created.getContentJson()).contains("\"total\":0").contains("\"urgent\":0");
        assertThat(created.getContentJson()).contains("https://jira/x").contains("https://jira/y");
        assertThat(created.getContentJson()).contains("İnternet");
        assertThat(created.getContentJson()).doesNotContain("eski not").doesNotContain("detay");
        assertThat(created.getWeekLabel()).startsWith("2026-W25");
    }

    @Test
    @DisplayName("create: önceki rapor yoksa default şablon; aynı hafta varsa 409")
    void create_defaultTemplate_andDuplicate() {
        when(reportRepo.findFirstByTeamIdOrderByReportYearDescWeekNoDesc(2L)).thenReturn(Optional.empty());
        when(reportRepo.findByTeamIdAndReportYearAndWeekNo(2L, 2026, 24)).thenReturn(Optional.empty());

        WeeklyReport created = service.create(null, 2026, 24, USER_T2);
        // Tohum sablonundaki kanal adi URUN VERISI (WeeklyReportService:136) ve bilincli olarak
        // supurulmedi; test onu AYNEN yansitmak zorunda (bkz. kimlik-tarama muafiyet listesi).
        assertThat(created.getContentJson()).contains("Çağrı Merkezi").contains("Web Kanalı");

        when(reportRepo.findByTeamIdAndReportYearAndWeekNo(2L, 2026, 24))
                .thenReturn(Optional.of(created));
        assertThatThrownBy(() -> service.create(null, 2026, 24, USER_T2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DUPLICATE_WEEK");
    }

    @Test
    @DisplayName("computeWeekLabel: ISO hafta Pzt–Paz (tam hafta) aralığı üretir")
    void computeWeekLabel_isoWeek() {
        // 2026-W24: 8–14 Haziran 2026 (Pzt 8 – Paz 14 Haziran)
        assertThat(WeeklyReportService.computeWeekLabel(2026, 24))
                .isEqualTo("2026-W24 (8–14 Haziran 2026)");
    }

    @Test
    @DisplayName("computeWeekLabel: ay geçişinde tek yıl, yıl geçişinde iki yıl da yazılır (frontend formatWeekRange ile aynı)")
    void computeWeekLabel_monthAndYearBoundary() {
        // Aynı yıl, ay geçişi: yalnız bitiş yılı
        assertThat(WeeklyReportService.computeWeekLabel(2026, 27))
                .isEqualTo("2026-W27 (29 Haziran – 5 Temmuz 2026)");
        // Yıl geçişi (2026-W01 Pzt'si 29 Aralık 2025): başlangıç günü kendi yılını da taşımalı
        assertThat(WeeklyReportService.computeWeekLabel(2026, 1))
                .isEqualTo("2026-W01 (29 Aralık 2025 – 4 Ocak 2026)");
    }

    @Test
    @DisplayName("years: USER kendi takımına zorlanır; içinde bulunulan yıl listede yoksa eklenir")
    void years_scopingAndCurrentYear() {
        int current = WeeklyReportService.today().get(WeekFields.ISO.weekBasedYear());

        when(reportRepo.findDistinctYears(2L)).thenReturn(List.of(2025, 2024));
        List<Integer> result = service.years(7L, USER_T2); // 7 istese de kendi takımı (2) sorgulanır
        assertThat(result).startsWith(current).contains(2025, 2024);
        verify(reportRepo).findDistinctYears(2L);

        when(reportRepo.findDistinctYears(null)).thenReturn(List.of(current, 2025));
        assertThat(service.years(null, ADMIN)).containsExactly(current, 2025);
    }

    // ── Düzenleme yetkileri ───────────────────────────────────────────────────

    @Test
    @DisplayName("USER kendi takımının DRAFT raporunu düzenler; başka takım 403")
    void saveContent_teamScoping() {
        WeeklyReport r = report(5L, 2L, "DRAFT");
        when(reportRepo.findById(5L)).thenReturn(Optional.of(r));

        WeeklyReport saved = service.saveContent(5L, WeeklyReportService.DEFAULT_TEMPLATE_JSON, null, USER_T2);
        assertThat(saved.getUpdatedBy()).isEqualTo("Kullanıcı İki");

        assertThatThrownBy(() -> service.saveContent(5L, WeeklyReportService.DEFAULT_TEMPLATE_JSON, null, USER_T7))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    @DisplayName("REJECTED rapor kaydedilince DRAFT'a döner, rejectNote korunur")
    void saveContent_rejectedBecomesDraft() {
        WeeklyReport r = report(5L, 2L, "REJECTED");
        r.setRejectNote("Madde 2 eksik");
        when(reportRepo.findById(5L)).thenReturn(Optional.of(r));

        WeeklyReport saved = service.saveContent(5L, WeeklyReportService.DEFAULT_TEMPLATE_JSON, null, USER_T2);

        assertThat(saved.getStatus()).isEqualTo("DRAFT");
        assertThat(saved.getRejectNote()).isEqualTo("Madde 2 eksik");
    }

    @Test
    @DisplayName("APPROVED/PENDING rapor USER tarafından düzenlenemez (409)")
    void saveContent_wrongStatus() {
        WeeklyReport r = report(5L, 2L, "APPROVED");
        when(reportRepo.findById(5L)).thenReturn(Optional.of(r));
        assertThatThrownBy(() -> service.saveContent(5L, WeeklyReportService.DEFAULT_TEMPLATE_JSON, null, USER_T2))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("hafta penceresi: USER önceki haftayı düzenler, 2 hafta öncesi 403; ADMIN sınırsız")
    void saveContent_weekWindow() {
        WeeklyReport lastWeek = reportAtWeek(5L, 2L, "REJECTED", WeeklyReportService.today().minusWeeks(1));
        when(reportRepo.findById(5L)).thenReturn(Optional.of(lastWeek));
        assertThat(service.saveContent(5L, WeeklyReportService.DEFAULT_TEMPLATE_JSON, null, USER_T2)
                .getStatus()).isEqualTo("DRAFT"); // iade edilen geçen hafta raporu düzeltilebilir

        WeeklyReport old = reportAtWeek(6L, 2L, "DRAFT", WeeklyReportService.today().minusWeeks(2));
        when(reportRepo.findById(6L)).thenReturn(Optional.of(old));
        assertThatThrownBy(() -> service.saveContent(6L, WeeklyReportService.DEFAULT_TEMPLATE_JSON, null, USER_T2))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("önceki haftanın");

        // ADMIN için hafta penceresi yok
        assertThat(service.saveContent(6L, WeeklyReportService.DEFAULT_TEMPLATE_JSON, null, ADMIN)
                .getUpdatedBy()).isEqualTo("Admin");
    }

    @Test
    @DisplayName("ADMIN onaylanmış (müdüre gönderilmiş) raporu düzeltebilir — durum APPROVED kalır")
    void saveContent_adminEditsApproved() {
        WeeklyReport r = reportAtWeek(5L, 2L, "APPROVED", WeeklyReportService.today().minusWeeks(3));
        when(reportRepo.findById(5L)).thenReturn(Optional.of(r));

        WeeklyReport saved = service.saveContent(5L, WeeklyReportService.DEFAULT_TEMPLATE_JSON, null, ADMIN);

        assertThat(saved.getStatus()).isEqualTo("APPROVED");
        assertThat(saved.getUpdatedBy()).isEqualTo("Admin");
    }

    @Test
    @DisplayName("inEditWindow: mevcut + önceki hafta true; 2 hafta önce ve gelecek hafta false")
    void inEditWindow_bounds() {
        WeekFields wf = WeekFields.ISO;
        for (LocalDate d : List.of(WeeklyReportService.today(), WeeklyReportService.today().minusWeeks(1))) {
            assertThat(WeeklyReportService.inEditWindow(
                    d.get(wf.weekBasedYear()), d.get(wf.weekOfWeekBasedYear()))).isTrue();
        }
        for (LocalDate d : List.of(WeeklyReportService.today().minusWeeks(2), WeeklyReportService.today().plusWeeks(1))) {
            assertThat(WeeklyReportService.inEditWindow(
                    d.get(wf.weekBasedYear()), d.get(wf.weekOfWeekBasedYear()))).isFalse();
        }
    }

    // ── Düzenleme kilidi + sürüm ──────────────────────────────────────────────

    /** Servisin ISO formatıyla 'seconds' saniye önceki damga. */
    private static String isoAgo(long seconds) {
        return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
                .withZone(java.time.ZoneOffset.UTC)
                .format(java.time.Instant.now().minusSeconds(seconds));
    }

    @Test
    @DisplayName("acquireLock: boş alınır; başkasında+taze alınamaz; bayat alınır; ADMIN force devralır")
    void acquireLock_flows() {
        WeeklyReport r = report(5L, 2L, "DRAFT");
        when(reportRepo.findById(5L)).thenReturn(Optional.of(r));

        assertThat(service.acquireLock(5L, false, USER_T2).get("acquired")).isEqualTo(true);
        assertThat(r.getEditingUserId()).isEqualTo(10L);

        // Taze kilit başkasına verilmez — sahibi bildirilir
        Map<String, Object> denied = service.acquireLock(5L, false, PO_T2);
        assertThat(denied.get("acquired")).isEqualTo(false);
        assertThat(denied.get("editing_by")).isEqualTo("Kullanıcı İki");

        // Bayat kilit (10 dk önce) → serbest sayılır
        r.setEditingHeartbeat(isoAgo(600));
        assertThat(service.acquireLock(5L, false, PO_T2).get("acquired")).isEqualTo(true);
        assertThat(r.getEditingUserId()).isEqualTo(13L);

        // Taze kilide ADMIN: force'suz alamaz, force ile devralır
        assertThat(service.acquireLock(5L, false, ADMIN).get("acquired")).isEqualTo(false);
        assertThat(service.acquireLock(5L, true, ADMIN).get("acquired")).isEqualTo(true);
        assertThat(r.getEditingUserId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("releaseLock: yalnız kendi kilidini bırakır, başkasınınkine dokunmaz")
    void releaseLock_ownOnly() {
        WeeklyReport r = report(5L, 2L, "DRAFT");
        r.setEditingUserId(10L); r.setEditingBy("Kullanıcı İki"); r.setEditingHeartbeat(isoAgo(0));
        when(reportRepo.findById(5L)).thenReturn(Optional.of(r));

        service.releaseLock(5L, PO_T2);
        assertThat(r.getEditingUserId()).isEqualTo(10L);

        service.releaseLock(5L, USER_T2);
        assertThat(r.getEditingUserId()).isNull();
        assertThat(r.getEditingBy()).isNull();
    }

    @Test
    @DisplayName("saveContent: eski sürümle kayıt VERSION_CONFLICT (409); doğru sürüm artar")
    void saveContent_versionConflict() {
        WeeklyReport r = report(5L, 2L, "DRAFT");
        r.setVersion(3);
        when(reportRepo.findById(5L)).thenReturn(Optional.of(r));

        assertThatThrownBy(() -> service.saveContent(5L, WeeklyReportService.DEFAULT_TEMPLATE_JSON, 2L, USER_T2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("VERSION_CONFLICT");

        WeeklyReport saved = service.saveContent(5L, WeeklyReportService.DEFAULT_TEMPLATE_JSON, 3L, USER_T2);
        assertThat(saved.getVersion()).isEqualTo(4);
    }

    @Test
    @DisplayName("submit: kilidi temizler ve sürümü artırır")
    void submit_clearsLockAndBumpsVersion() {
        WeeklyReport r = report(5L, 2L, "DRAFT");
        r.setVersion(1); r.setEditingUserId(10L); r.setEditingBy("Kullanıcı İki"); r.setEditingHeartbeat(isoAgo(0));
        when(reportRepo.findById(5L)).thenReturn(Optional.of(r));
        when(contactRepo.findByTeamIdAndRoleAndActiveTrue(2L, "PO")).thenReturn(List.of());
        when(userRepo.findByTeamIdAndOrgRoleAndActiveTrue(2L, "PO")).thenReturn(List.of());

        service.submit(5L, USER_T2);

        assertThat(r.getVersion()).isEqualTo(2);
        assertThat(r.getEditingUserId()).isNull();
        assertThat(r.getEditingHeartbeat()).isNull();
    }

    // ── Mail gönderim geçmişi ─────────────────────────────────────────────────

    @Test
    @DisplayName("submit: SUBMIT_PO gönderim kaydı atılır (to + status + from)")
    void submit_recordsMail() {
        WeeklyReport r = report(5L, 2L, "DRAFT");
        when(reportRepo.findById(5L)).thenReturn(Optional.of(r));
        when(contactRepo.findByTeamIdAndRoleAndActiveTrue(2L, "PO"))
                .thenReturn(List.of(contact("PO", "PO İki", "po@test.com")));

        service.submit(5L, USER_T2);

        ArgumentCaptor<WeeklyReportMail> cap = ArgumentCaptor.forClass(WeeklyReportMail.class);
        verify(mailRepo).save(cap.capture());
        WeeklyReportMail m = cap.getValue();
        assertThat(m.getMailType()).isEqualTo("SUBMIT_PO");
        assertThat(m.getToAddresses()).isEqualTo("po@test.com");
        assertThat(m.getStatus()).isEqualTo("SENT");
        assertThat(m.getFromAddress()).isEqualTo("sitemonitor@test");
        assertThat(m.getSubject()).contains("onayınızı bekliyor");
    }

    @Test
    @DisplayName("submit: PO kontağı yoksa SKIPPED_NO_CONTACT kaydı (to boş)")
    void submit_recordsSkippedMail() {
        WeeklyReport r = report(5L, 2L, "DRAFT");
        when(reportRepo.findById(5L)).thenReturn(Optional.of(r));
        when(contactRepo.findByTeamIdAndRoleAndActiveTrue(2L, "PO")).thenReturn(List.of());
        when(userRepo.findByTeamIdAndOrgRoleAndActiveTrue(2L, "PO")).thenReturn(List.of());

        service.submit(5L, USER_T2);

        ArgumentCaptor<WeeklyReportMail> cap = ArgumentCaptor.forClass(WeeklyReportMail.class);
        verify(mailRepo).save(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo("SKIPPED_NO_CONTACT");
        assertThat(cap.getValue().getToAddresses()).isEmpty();
    }

    @Test
    @DisplayName("approve: APPROVE_MANAGER kaydı (to=müdür, cc=takım); FAILED dönüşte status FAILED")
    void approve_recordsMail() {
        WeeklyReport r = report(5L, 2L, "PENDING_APPROVAL");
        when(reportRepo.findById(5L)).thenReturn(Optional.of(r));
        when(contactRepo.findByTeamIdAndRoleAndActiveTrue(2L, "MANAGER"))
                .thenReturn(List.of(contact("MANAGER", "Ali Müdür", "mudur@test.com")));

        service.approve(5L, PO_T2);

        ArgumentCaptor<WeeklyReportMail> cap = ArgumentCaptor.forClass(WeeklyReportMail.class);
        verify(mailRepo).save(cap.capture());
        WeeklyReportMail m = cap.getValue();
        assertThat(m.getMailType()).isEqualTo("APPROVE_MANAGER");
        assertThat(m.getToAddresses()).isEqualTo("mudur@test.com");
        assertThat(m.getCcAddresses()).isEqualTo("takim@test.com");
        assertThat(m.getStatus()).isEqualTo("SENT");

        // Mail FAILED dönerse kayıt FAILED, rapor yine APPROVED ama sentAt boş
        WeeklyReport r2 = report(6L, 2L, "PENDING_APPROVAL");
        when(reportRepo.findById(6L)).thenReturn(Optional.of(r2));
        when(emailService.sendHtml(any(), any(), anyString(), anyString(), any()))
                .thenReturn("FAILED: smtp down");

        service.approve(6L, PO_T2);

        ArgumentCaptor<WeeklyReportMail> cap2 = ArgumentCaptor.forClass(WeeklyReportMail.class);
        verify(mailRepo, times(2)).save(cap2.capture());
        assertThat(cap2.getValue().getStatus()).startsWith("FAILED");
        assertThat(r2.getStatus()).isEqualTo("APPROVED");
        assertThat(r2.getSentAt()).isNull();
    }

    @Test
    @DisplayName("mails: okuma yetkisiyle döner, cid referansları /api'ye çevrilir")
    void mails_rewritesCid() {
        WeeklyReport r = report(5L, 2L, "APPROVED");
        when(reportRepo.findById(5L)).thenReturn(Optional.of(r));
        WeeklyReportMail m = new WeeklyReportMail();
        m.setId(9L); m.setReportId(5L); m.setBodyHtml("<img src=\"cid:img7\">");
        when(mailRepo.findByReportIdOrderByIdDesc(5L)).thenReturn(List.of(m));

        List<WeeklyReportMail> out = service.mails(5L, USER_T2);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).getBodyHtml()).contains("/api/weekly-reports/images/7")
                .doesNotContain("cid:img7");

        assertThatThrownBy(() -> service.mails(5L, USER_T7))
                .isInstanceOf(SecurityException.class); // başka takım okuyamaz
    }

    @Test
    @DisplayName("lastMailStatuses: rapor başına en son kaydın status'u")
    void lastMailStatuses_picksLatest() {
        WeeklyReportMail old = new WeeklyReportMail();
        old.setId(1L); old.setReportId(5L); old.setStatus("SENT");
        WeeklyReportMail newer = new WeeklyReportMail();
        newer.setId(2L); newer.setReportId(5L); newer.setStatus("FAILED: smtp");
        when(mailRepo.findByReportIdIn(List.of(5L))).thenReturn(List.of(old, newer));

        Map<Long, String> out = service.lastMailStatuses(List.of(5L));

        assertThat(out).containsEntry(5L, "FAILED: smtp");
    }

    // ── Silme ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("delete: salt USER kendi takımının DRAFT'ını dahi SİLEMEZ (403) — yalnız yönetici siler")
    void delete_userCannotDelete() {
        WeeklyReport r = report(5L, 2L, "DRAFT");
        when(reportRepo.findById(5L)).thenReturn(Optional.of(r));

        assertThatThrownBy(() -> service.delete(5L, USER_T2))
                .isInstanceOf(SecurityException.class);

        verify(reportRepo, never()).delete(any(WeeklyReport.class));
        verify(imageRepo, never()).deleteByReportId(anyLong());
    }

    @Test
    @DisplayName("delete: takımın TEAM_ADMIN'i kendi pencere içi DRAFT'ını siler")
    void delete_teamAdminOwnDraft() {
        WeeklyReport r = report(5L, 2L, "DRAFT");
        when(reportRepo.findById(5L)).thenReturn(Optional.of(r));

        service.delete(5L, TADMIN_T2);

        verify(imageRepo).deleteByReportId(5L);
        verify(mailRepo).deleteByReportId(5L);
        verify(reportRepo).delete(r);
    }

    @Test
    @DisplayName("delete: PO (systemRole=USER) raporu SİLEMEZ (403) — onaylayabilir ama silemez")
    void delete_poCannotDelete() {
        WeeklyReport r = report(5L, 2L, "DRAFT");
        when(reportRepo.findById(5L)).thenReturn(Optional.of(r));

        assertThatThrownBy(() -> service.delete(5L, PO_T2))
                .isInstanceOf(SecurityException.class);

        verify(reportRepo, never()).delete(any(WeeklyReport.class));
    }

    @Test
    @DisplayName("delete: ADMIN eski haftanın APPROVED raporunu silebilir")
    void delete_adminApprovedOldWeek() {
        WeeklyReport r = reportAtWeek(5L, 2L, "APPROVED", WeeklyReportService.today().minusWeeks(5));
        when(reportRepo.findById(5L)).thenReturn(Optional.of(r));

        service.delete(5L, ADMIN);

        verify(imageRepo).deleteByReportId(5L);
        verify(reportRepo).delete(r);
    }

    @Test
    @DisplayName("delete: yönetici bile APPROVED'ı silemez (409); USER her durumda 403; başka takım 403")
    void delete_forbidden() {
        // Salt USER kendi takımının APPROVED'ını → rol kapısı önce → 403 (SecurityException)
        WeeklyReport approved = report(5L, 2L, "APPROVED");
        when(reportRepo.findById(5L)).thenReturn(Optional.of(approved));
        assertThatThrownBy(() -> service.delete(5L, USER_T2))
                .isInstanceOf(SecurityException.class);

        // TEAM_ADMIN yönetici ama APPROVED durumu engeller → 409 (IllegalStateException)
        WeeklyReport approved2 = report(7L, 2L, "APPROVED");
        when(reportRepo.findById(7L)).thenReturn(Optional.of(approved2));
        assertThatThrownBy(() -> service.delete(7L, TADMIN_T2))
                .isInstanceOf(IllegalStateException.class);

        // Başka takımın kullanıcısı → 403
        WeeklyReport draft = report(6L, 2L, "DRAFT");
        when(reportRepo.findById(6L)).thenReturn(Optional.of(draft));
        assertThatThrownBy(() -> service.delete(6L, USER_T7))
                .isInstanceOf(SecurityException.class);

        verify(reportRepo, never()).delete(any(WeeklyReport.class));
        verify(imageRepo, never()).deleteByReportId(anyLong());
    }

    // ── Durum makinesi + mailler ──────────────────────────────────────────────

    @Test
    @DisplayName("submit: DRAFT→PENDING + PO kontaklarına bilgilendirme maili")
    void submit_sendsPoMail() {
        WeeklyReport r = report(5L, 2L, "DRAFT");
        when(reportRepo.findById(5L)).thenReturn(Optional.of(r));
        when(contactRepo.findByTeamIdAndRoleAndActiveTrue(2L, "PO"))
                .thenReturn(List.of(contact("PO", "PO İki", "po@test.com")));

        Map<String, Object> out = service.submit(5L, USER_T2);

        assertThat(((WeeklyReport) out.get("data")).getStatus()).isEqualTo("PENDING_APPROVAL");
        assertThat(out.get("po_mail")).isEqualTo("SENT");
        verify(emailService).sendHtml(eq(new String[]{"po@test.com"}), isNull(),
                contains("onayınızı bekliyor"), anyString(), any());
    }

    @Test
    @DisplayName("submit: PO kontağı yoksa SKIPPED_NO_CONTACT (mail yok, akış sürer)")
    void submit_noPoContact_skips() {
        WeeklyReport r = report(5L, 2L, "DRAFT");
        when(reportRepo.findById(5L)).thenReturn(Optional.of(r));
        when(contactRepo.findByTeamIdAndRoleAndActiveTrue(2L, "PO")).thenReturn(List.of());
        when(userRepo.findByTeamIdAndOrgRoleAndActiveTrue(2L, "PO")).thenReturn(List.of());

        Map<String, Object> out = service.submit(5L, USER_T2);

        assertThat(out.get("po_mail")).isEqualTo("SKIPPED_NO_CONTACT");
        verify(emailService, never()).sendHtml(any(), any(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("approve (PO): müdüre mail To=MANAGER CC=Team.email, subject takım+hafta")
    void approve_sendsManagerMail() {
        WeeklyReport r = report(5L, 2L, "PENDING_APPROVAL");
        r.setRejectNote("eski not");
        when(reportRepo.findById(5L)).thenReturn(Optional.of(r));
        when(contactRepo.findByTeamIdAndRoleAndActiveTrue(2L, "MANAGER"))
                .thenReturn(List.of(contact("MANAGER", "Ali Müdür", "mudur@test.com")));

        Map<String, Object> out = service.approve(5L, PO_T2);

        WeeklyReport approved = (WeeklyReport) out.get("data");
        assertThat(approved.getStatus()).isEqualTo("APPROVED");
        assertThat(approved.getSentAt()).isNotNull();
        assertThat(approved.getRejectNote()).isNull();
        ArgumentCaptor<String[]> to = ArgumentCaptor.forClass(String[].class);
        ArgumentCaptor<String[]> cc = ArgumentCaptor.forClass(String[].class);
        verify(emailService).sendHtml(to.capture(), cc.capture(),
                eq("[TakimA] Haftalık Rapor — 2026-W24 (8–12 Haziran 2026)"),
                anyString(), any());
        assertThat(to.getValue()).containsExactly("mudur@test.com");
        assertThat(cc.getValue()).containsExactly("takim@test.com");
        verify(emailService).buildWeeklyReportHtml(eq("TakimA"), anyString(),
                eq("Ali Müdür"), anyString(), eq(true), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("approve: MANAGER kontağı yoksa 409, mail gönderilmez, durum değişmez")
    void approve_managerMissing_409() {
        WeeklyReport r = report(5L, 2L, "PENDING_APPROVAL");
        when(reportRepo.findById(5L)).thenReturn(Optional.of(r));
        when(contactRepo.findByTeamIdAndRoleAndActiveTrue(2L, "MANAGER")).thenReturn(List.of());

        assertThatThrownBy(() -> service.approve(5L, PO_T2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MANAGER_CONTACT_MISSING");
        assertThat(r.getStatus()).isEqualTo("PENDING_APPROVAL");
        verify(emailService, never()).sendHtml(any(), any(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("approve yetkisi: PO/TEAM_ADMIN/ADMIN onaylar; düz USER ve başka takım 403")
    void approve_permissions() {
        when(contactRepo.findByTeamIdAndRoleAndActiveTrue(2L, "MANAGER"))
                .thenReturn(List.of(contact("MANAGER", "Ali Müdür", "mudur@test.com")));

        for (Actor allowed : List.of(PO_T2, TADMIN_T2, ADMIN)) {
            WeeklyReport r = report(5L, 2L, "PENDING_APPROVAL");
            when(reportRepo.findById(5L)).thenReturn(Optional.of(r));
            service.approve(5L, allowed);
            assertThat(r.getStatus()).isEqualTo("APPROVED");
        }

        WeeklyReport r = report(5L, 2L, "PENDING_APPROVAL");
        when(reportRepo.findById(5L)).thenReturn(Optional.of(r));
        assertThatThrownBy(() -> service.approve(5L, USER_T2))
                .isInstanceOf(SecurityException.class); // orgRole=TECH
        assertThatThrownBy(() -> service.approve(5L, USER_T7))
                .isInstanceOf(SecurityException.class); // başka takım (okuma da yasak)
    }

    @Test
    @DisplayName("reject: not zorunlu; PENDING→REJECTED + Team.email'e düzeltme maili")
    void reject_sendsTeamMail() {
        WeeklyReport r = report(5L, 2L, "PENDING_APPROVAL");
        when(reportRepo.findById(5L)).thenReturn(Optional.of(r));

        assertThatThrownBy(() -> service.reject(5L, "  ", PO_T2))
                .isInstanceOf(IllegalArgumentException.class);

        WeeklyReport rejected = service.reject(5L, "Madde 4'te internet kanalı eksik", PO_T2);

        assertThat(rejected.getStatus()).isEqualTo("REJECTED");
        assertThat(rejected.getRejectNote()).contains("internet kanalı eksik");
        verify(emailService).sendHtml(eq(new String[]{"takim@test.com"}), isNull(),
                contains("iade edildi"), anyString(), isNull());
        verify(emailService).buildWeeklyReportRejectedHtml(eq("TakimA"), anyString(),
                contains("internet kanalı eksik"), eq("PO İki"));
    }

    // ── Tekrar işleme: revize (reopen) + tekrar gönder (resend) ───────────────

    @Test
    @DisplayName("reopen: USER kendi takımının pencere içi APPROVED'ını DRAFT'a çevirir — onay/gönderim sıfırlanır")
    void reopen_userOwnTeamInWindow() {
        WeeklyReport r = report(5L, 2L, "APPROVED");
        r.setApprovedBy("PO İki"); r.setApprovedAt("2026-06-10T09:00:00Z"); r.setSentAt("2026-06-10T09:00:00Z");
        int v = r.getVersion();
        when(reportRepo.findById(5L)).thenReturn(Optional.of(r));

        WeeklyReport out = service.reopen(5L, USER_T2);

        assertThat(out.getStatus()).isEqualTo("DRAFT");
        assertThat(out.getVersion()).isEqualTo(v + 1);
        assertThat(out.getSentAt()).isNull();
        assertThat(out.getApprovedBy()).isNull();
        assertThat(out.getApprovedAt()).isNull();
        verify(emailService, never()).sendHtml(any(), any(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("reopen: ADMIN eski haftanın APPROVED raporunu da revize edebilir")
    void reopen_adminOldWeek() {
        WeeklyReport r = reportAtWeek(6L, 2L, "APPROVED", WeeklyReportService.today().minusWeeks(5));
        when(reportRepo.findById(6L)).thenReturn(Optional.of(r));

        assertThat(service.reopen(6L, ADMIN).getStatus()).isEqualTo("DRAFT");
    }

    @Test
    @DisplayName("reopen: non-admin pencere dışı → 403; AUDIT → 403; APPROVED değilse → 409")
    void reopen_forbiddenAndStateGuards() {
        WeeklyReport oldR = reportAtWeek(6L, 2L, "APPROVED", WeeklyReportService.today().minusWeeks(5));
        when(reportRepo.findById(6L)).thenReturn(Optional.of(oldR));
        assertThatThrownBy(() -> service.reopen(6L, USER_T2))
                .isInstanceOf(SecurityException.class); // pencere dışı (yalnız ADMIN)

        WeeklyReport r = report(5L, 2L, "APPROVED");
        when(reportRepo.findById(5L)).thenReturn(Optional.of(r));
        assertThatThrownBy(() -> service.reopen(5L, new Actor(99L, "audit", "Denetçi", null, "AUDIT")))
                .isInstanceOf(SecurityException.class); // AUDIT yasak

        WeeklyReport draft = report(7L, 2L, "DRAFT");
        when(reportRepo.findById(7L)).thenReturn(Optional.of(draft));
        assertThatThrownBy(() -> service.reopen(7L, ADMIN))
                .isInstanceOf(IllegalStateException.class); // APPROVED değil
    }

    @Test
    @DisplayName("resend: onaylı raporu yeniden onaysız müdüre tekrar gönderir — APPROVE_MANAGER kaydı + sentAt + version++")
    void resend_sendsManagerMailAgain() {
        WeeklyReport r = report(5L, 2L, "APPROVED");
        int v = r.getVersion();
        when(reportRepo.findById(5L)).thenReturn(Optional.of(r));
        when(contactRepo.findByTeamIdAndRoleAndActiveTrue(2L, "MANAGER"))
                .thenReturn(List.of(contact("MANAGER", "Ali Müdür", "mudur@test.com")));

        Map<String, Object> out = service.resend(5L, PO_T2);

        WeeklyReport data = (WeeklyReport) out.get("data");
        assertThat(data.getStatus()).isEqualTo("APPROVED"); // onay durumu korunur
        assertThat(data.getVersion()).isEqualTo(v + 1);
        assertThat(data.getSentAt()).isNotNull();
        assertThat(out.get("mail_status")).isEqualTo("SENT");
        ArgumentCaptor<WeeklyReportMail> mail = ArgumentCaptor.forClass(WeeklyReportMail.class);
        verify(mailRepo).save(mail.capture());
        assertThat(mail.getValue().getMailType()).isEqualTo("APPROVE_MANAGER");
        verify(emailService).sendHtml(eq(new String[]{"mudur@test.com"}),
                eq(new String[]{"takim@test.com"}), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("resend: MANAGER yoksa 409; APPROVED değilse 409; yetkisiz USER 403")
    void resend_guards() {
        WeeklyReport r = report(5L, 2L, "APPROVED");
        when(reportRepo.findById(5L)).thenReturn(Optional.of(r));
        when(contactRepo.findByTeamIdAndRoleAndActiveTrue(2L, "MANAGER")).thenReturn(List.of());
        assertThatThrownBy(() -> service.resend(5L, PO_T2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MANAGER_CONTACT_MISSING");

        WeeklyReport draft = report(7L, 2L, "DRAFT");
        when(reportRepo.findById(7L)).thenReturn(Optional.of(draft));
        assertThatThrownBy(() -> service.resend(7L, PO_T2))
                .isInstanceOf(IllegalStateException.class); // APPROVED değil

        WeeklyReport r2 = report(8L, 2L, "APPROVED");
        when(reportRepo.findById(8L)).thenReturn(Optional.of(r2));
        assertThatThrownBy(() -> service.resend(8L, USER_T2))
                .isInstanceOf(SecurityException.class); // orgRole=TECH, onay yetkisi yok
    }

    // ── İçerik / görsel doğrulama ─────────────────────────────────────────────

    @Test
    @DisplayName("validateAndNormalizeContent: bozuk JSON ve boş kanal adı reddedilir")
    void contentValidation() {
        assertThatThrownBy(() -> service.validateAndNormalizeContent("{bozuk"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.validateAndNormalizeContent(
                "{\"item4\":{\"channels\":[{\"name\":\"  \"}]}}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(service.validateAndNormalizeContent(WeeklyReportService.DEFAULT_TEMPLATE_JSON))
                .contains("İnternet");

        // Madde 1 Toplam türetilir — istemcinin gönderdiği 99 yok sayılır, 2+3+4+3=12 yazılır
        String normalized = service.validateAndNormalizeContent(
                "{\"item1\":{\"total\":99,\"urgent\":2,\"high\":3,\"medium\":4,\"low\":3}}");
        assertThat(normalized).contains("\"total\":12");
    }

    @Test
    @DisplayName("storeImage: limit aşımı ve desteklenmeyen tip 400; geçerli png kaydedilir")
    void storeImage_validation() {
        WeeklyReport r = report(5L, 2L, "DRAFT");
        when(reportRepo.findById(5L)).thenReturn(Optional.of(r));
        when(imageRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        MockMultipartFile tooBig = new MockMultipartFile("file", "big.png", "image/png",
                new byte[3 * 1024 * 1024]);
        assertThatThrownBy(() -> service.storeImage(5L, "c", tooBig, USER_T2))
                .isInstanceOf(IllegalArgumentException.class);

        MockMultipartFile wrongType = new MockMultipartFile("file", "x.pdf", "application/pdf",
                new byte[]{1, 2, 3});
        assertThatThrownBy(() -> service.storeImage(5L, "c", wrongType, USER_T2))
                .isInstanceOf(IllegalArgumentException.class);

        MockMultipartFile okFile = new MockMultipartFile("file", "ok.png", "image/png",
                new byte[]{1, 2, 3});
        var img = service.storeImage(5L, "Grafik", okFile, USER_T2);
        assertThat(img.getCaption()).isEqualTo("Grafik");
        assertThat(img.getContentType()).isEqualTo("image/png");
        assertThat(img.getTeamId()).isEqualTo(2L); // açık takım izolasyonu (raporun takımı)
    }

    // ── Takım transferi (toplu / tekil) ──────────────────────────────────────

    @Test
    @DisplayName("transfer: ADMIN değilse SecurityException")
    void transfer_nonAdmin_throws() {
        assertThatThrownBy(() -> service.transfer(List.of(5L), 7L, USER_T2))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    @DisplayName("transfer: raporu hedef takıma taşır + görsellerin takım izolasyonunu günceller")
    void transfer_movesTeamAndImages() {
        WeeklyReport r = report(5L, 2L, "DRAFT");
        when(teamRepo.existsById(7L)).thenReturn(true);
        when(reportRepo.findById(5L)).thenReturn(Optional.of(r));
        when(reportRepo.findByTeamIdAndReportYearAndWeekNo(eq(7L), anyInt(), anyInt())).thenReturn(Optional.empty());
        com.sitemonitor.model.WeeklyReportImage img = new com.sitemonitor.model.WeeklyReportImage();
        img.setId(99L); img.setReportId(5L); img.setTeamId(2L);
        when(imageRepo.findByReportIdOrderByIdAsc(5L)).thenReturn(List.of(img));

        Map<String, Object> res = service.transfer(List.of(5L), 7L, ADMIN);

        assertThat(res.get("transferred")).isEqualTo(1);
        assertThat(r.getTeamId()).isEqualTo(7L);
        assertThat(img.getTeamId()).isEqualTo(7L);
        verify(imageRepo).save(img);
    }

    @Test
    @DisplayName("transfer: hedef takımda aynı hafta raporu varsa atlanır (çakışma)")
    void transfer_conflict_skips() {
        WeeklyReport r = report(5L, 2L, "DRAFT");
        when(teamRepo.existsById(7L)).thenReturn(true);
        when(reportRepo.findById(5L)).thenReturn(Optional.of(r));
        when(reportRepo.findByTeamIdAndReportYearAndWeekNo(eq(7L), anyInt(), anyInt()))
                .thenReturn(Optional.of(report(88L, 7L, "DRAFT")));

        Map<String, Object> res = service.transfer(List.of(5L), 7L, ADMIN);

        assertThat(res.get("transferred")).isEqualTo(0);
        assertThat((List<?>) res.get("skipped")).hasSize(1);
        assertThat(r.getTeamId()).isEqualTo(2L); // değişmedi
    }

    // ── E-posta ile hızlı onay (token) ───────────────────────────────────────

    private static String tokenTime(long secondsFromNow) {
        return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
                .withZone(java.time.ZoneOffset.UTC)
                .format(java.time.Instant.now().plusSeconds(secondsFromNow));
    }

    @Test
    @DisplayName("approveViaToken: geçerli token PENDING raporu onaylar + token temizlenir")
    void approveViaToken_valid_approves() {
        WeeklyReport r = report(5L, 2L, "PENDING_APPROVAL");
        r.setApprovalToken("T1"); r.setApprovalTokenExpiresAt(tokenTime(86400));
        when(reportRepo.findByApprovalToken("T1")).thenReturn(Optional.of(r));
        when(contactRepo.findByTeamIdAndRoleAndActiveTrue(2L, "MANAGER"))
                .thenReturn(List.of(contact("MANAGER", "Ali Müdür", "mudur@test.com")));

        Map<String, Object> out = service.approveViaToken("T1");

        assertThat(r.getStatus()).isEqualTo("APPROVED");
        assertThat(r.getApprovalToken()).isNull();
        assertThat(out.get("mail_status")).isNotNull();
    }

    @Test
    @DisplayName("approveViaToken: süresi geçmiş token reddedilir, durum değişmez")
    void approveViaToken_expired_throws() {
        WeeklyReport r = report(5L, 2L, "PENDING_APPROVAL");
        r.setApprovalToken("T1"); r.setApprovalTokenExpiresAt(tokenTime(-86400));
        when(reportRepo.findByApprovalToken("T1")).thenReturn(Optional.of(r));
        assertThatThrownBy(() -> service.approveViaToken("T1"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(r.getStatus()).isEqualTo("PENDING_APPROVAL");
    }

    @Test
    @DisplayName("approveViaToken: bilinmeyen token reddedilir")
    void approveViaToken_unknown_throws() {
        when(reportRepo.findByApprovalToken("X")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.approveViaToken("X"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("approveViaToken: zaten onaylanmış raporda hata")
    void approveViaToken_alreadyApproved_throws() {
        WeeklyReport r = report(5L, 2L, "APPROVED");
        r.setApprovalToken("T1"); r.setApprovalTokenExpiresAt(tokenTime(86400));
        when(reportRepo.findByApprovalToken("T1")).thenReturn(Optional.of(r));
        assertThatThrownBy(() -> service.approveViaToken("T1"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("rejectViaToken: geçerli token PENDING raporu iade eder + not + iade-maili + token temizlenir")
    void rejectViaToken_valid_rejects() {
        WeeklyReport r = report(5L, 2L, "PENDING_APPROVAL");
        r.setApprovalToken("T1"); r.setApprovalTokenExpiresAt(tokenTime(86400));
        when(reportRepo.findByApprovalToken("T1")).thenReturn(Optional.of(r));

        Map<String, Object> out = service.rejectViaToken("T1", "Madde 4 eksik");

        assertThat(r.getStatus()).isEqualTo("REJECTED");
        assertThat(r.getRejectNote()).contains("Madde 4 eksik");
        assertThat(r.getApprovalToken()).isNull();
        assertThat(out.get("mail_status")).isNotNull();
        verify(emailService).sendHtml(eq(new String[]{"takim@test.com"}), isNull(),
                contains("iade edildi"), anyString(), isNull());
        verify(emailService).buildWeeklyReportRejectedHtml(eq("TakimA"), anyString(),
                contains("Madde 4 eksik"), anyString());
    }

    @Test
    @DisplayName("rejectViaToken: açıklama OPSİYONEL — boş not → varsayılan iade notu")
    void rejectViaToken_blankNote_usesDefault() {
        WeeklyReport r = report(5L, 2L, "PENDING_APPROVAL");
        r.setApprovalToken("T1"); r.setApprovalTokenExpiresAt(tokenTime(86400));
        when(reportRepo.findByApprovalToken("T1")).thenReturn(Optional.of(r));

        service.rejectViaToken("T1", "   ");

        assertThat(r.getStatus()).isEqualTo("REJECTED");
        assertThat(r.getRejectNote()).contains("neden belirtilmedi");
    }

    @Test
    @DisplayName("rejectViaToken: süresi geçmiş token reddedilir, durum değişmez")
    void rejectViaToken_expired_throws() {
        WeeklyReport r = report(5L, 2L, "PENDING_APPROVAL");
        r.setApprovalToken("T1"); r.setApprovalTokenExpiresAt(tokenTime(-86400));
        when(reportRepo.findByApprovalToken("T1")).thenReturn(Optional.of(r));
        assertThatThrownBy(() -> service.rejectViaToken("T1", "x"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(r.getStatus()).isEqualTo("PENDING_APPROVAL");
    }

    @Test
    @DisplayName("rejectViaToken: bilinmeyen token reddedilir")
    void rejectViaToken_unknown_throws() {
        when(reportRepo.findByApprovalToken("X")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.rejectViaToken("X", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("rejectViaToken: zaten onaylanmış raporda hata")
    void rejectViaToken_alreadyApproved_throws() {
        WeeklyReport r = report(5L, 2L, "APPROVED");
        r.setApprovalToken("T1"); r.setApprovalTokenExpiresAt(tokenTime(86400));
        when(reportRepo.findByApprovalToken("T1")).thenReturn(Optional.of(r));
        assertThatThrownBy(() -> service.rejectViaToken("T1", "x"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("approvalTokenStatus: geçerli PENDING → valid=true + özet")
    void approvalTokenStatus_valid() {
        WeeklyReport r = report(5L, 2L, "PENDING_APPROVAL");
        r.setApprovalToken("T1"); r.setApprovalTokenExpiresAt(tokenTime(86400));
        when(reportRepo.findByApprovalToken("T1")).thenReturn(Optional.of(r));
        Map<String, Object> st = service.approvalTokenStatus("T1");
        assertThat(st.get("valid")).isEqualTo(true);
        assertThat(st.get("week_label")).isNotNull();
    }

    @Test
    @DisplayName("approvalTokenStatus: bilinmeyen token → valid=false (not_found)")
    void approvalTokenStatus_unknown() {
        when(reportRepo.findByApprovalToken("X")).thenReturn(Optional.empty());
        Map<String, Object> st = service.approvalTokenStatus("X");
        assertThat(st.get("valid")).isEqualTo(false);
        assertThat(st.get("reason")).isEqualTo("not_found");
    }

    @Test
    @DisplayName("approve: MANAGER contact yoksa AD müdürüne düşer (manager_id → e-posta)")
    void approve_fallsBackToAdManager() {
        when(reportRepo.findById(5L)).thenReturn(Optional.of(report(5L, 2L, "PENDING_APPROVAL")));
        when(contactRepo.findByTeamIdAndRoleAndActiveTrue(2L, "MANAGER")).thenReturn(List.of());
        AppUser member = new AppUser();
        member.setId(50L); member.setTeamId(2L); member.setActive(true); member.setManagerId(70L);
        when(userRepo.findByTeamIdOrderByUsernameAsc(2L)).thenReturn(List.of(member));
        AppUser mgr = new AppUser();
        mgr.setId(70L); mgr.setActive(true); mgr.setEmail("mudur@example.com"); mgr.setDisplayName("Ali Müdür");
        when(userRepo.findById(70L)).thenReturn(Optional.of(mgr));

        Map<String, Object> out = service.approve(5L, PO_T2);

        assertThat(((WeeklyReport) out.get("data")).getStatus()).isEqualTo("APPROVED");
        ArgumentCaptor<String[]> toCap = ArgumentCaptor.forClass(String[].class);
        verify(emailService).sendHtml(toCap.capture(), any(), anyString(), anyString(), any());
        assertThat(toCap.getValue()).contains("mudur@example.com");
    }

    @Test
    @DisplayName("approve: MANAGER contact da AD müdürü de yoksa 409")
    void approve_noManagerAnywhere_throws() {
        when(reportRepo.findById(5L)).thenReturn(Optional.of(report(5L, 2L, "PENDING_APPROVAL")));
        when(contactRepo.findByTeamIdAndRoleAndActiveTrue(2L, "MANAGER")).thenReturn(List.of());
        when(userRepo.findByTeamIdOrderByUsernameAsc(2L)).thenReturn(List.of());
        assertThatThrownBy(() -> service.approve(5L, PO_T2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MANAGER_CONTACT_MISSING");
    }

    @Test
    @DisplayName("releaseLocksForUser: kullanıcının düzenleme kilitlerini repo üzerinden bırakır")
    void releaseLocksForUser_clears() {
        when(reportRepo.clearLocksByUser(10L)).thenReturn(2);
        service.releaseLocksForUser(10L);
        verify(reportRepo).clearLocksByUser(10L);
    }

    @Test
    @DisplayName("releaseLocksForUser: null userId → no-op")
    void releaseLocksForUser_nullNoop() {
        service.releaseLocksForUser(null);
        verify(reportRepo, never()).clearLocksByUser(anyLong());
    }

    // ── Kod incelemesi 2026-09-09: kapsamlı müdür global ADMIN değildir ───────

    @Test
    @DisplayName("Kapsamlı müdür (ADMIN rolü, globalAdmin=false) başka takımın raporunu okuyamaz / transfer edemez")
    void scopedAdmin_isNotGlobal_forWeeklyReports() {
        Actor mudur = new Actor(20L, "mudur", "Müdür", 2L, "ADMIN", false);
        WeeklyReport other = report(5L, 7L, "APPROVED");
        when(reportRepo.findById(5L)).thenReturn(Optional.of(other));

        assertThat(mudur.isAdmin()).isFalse();
        assertThat(ADMIN.isAdmin()).as("5-arg kurucu: rol ADMIN → global (geriye uyum)").isTrue();
        assertThatThrownBy(() -> service.get(5L, mudur)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> service.transfer(List.of(5L), 2L, mudur)).isInstanceOf(SecurityException.class);
        assertThat(service.get(5L, ADMIN)).isNotNull();
    }
    // ── Takım tamamlama panosu (2026-09-12, #21) ─────────────────────────────────────────────

    @Test
    @DisplayName("completion: geçen yıl 52/53 hafta; hatırlatması kapalı ve raporsuz takım listelenmez; en eksik takım üstte; USER boş liste")
    void completion_matrix() {
        int lastYear = WeeklyReportService.today().getYear() - 1;
        int weeksInYear = java.time.LocalDate.of(lastYear, 12, 28).get(java.time.temporal.WeekFields.ISO.weekOfWeekBasedYear());
        Team a = new Team(); a.setId(1L); a.setName("Takım A"); a.setActive(true); a.setWeeklyReminderEnabled(true);
        Team b = new Team(); b.setId(2L); b.setName("Takım B"); b.setActive(true); b.setWeeklyReminderEnabled(true);
        Team c = new Team(); c.setId(3L); c.setName("Takım C"); c.setActive(true); c.setWeeklyReminderEnabled(false);   // raporsuz + hatırlatma kapalı → yok
        when(teamRepo.findByActiveTrueOrderByNameAsc()).thenReturn(java.util.List.of(a, b, c));
        WeeklyReport r1 = new WeeklyReport(); r1.setId(11L); r1.setTeamId(1L); r1.setReportYear(lastYear); r1.setWeekNo(1); r1.setStatus("APPROVED");
        WeeklyReport r2 = new WeeklyReport(); r2.setId(12L); r2.setTeamId(1L); r2.setReportYear(lastYear); r2.setWeekNo(2); r2.setStatus("DRAFT");
        WeeklyReport r3 = new WeeklyReport(); r3.setId(13L); r3.setTeamId(2L); r3.setReportYear(lastYear); r3.setWeekNo(1); r3.setStatus("PENDING_APPROVAL");
        when(reportRepo.findByReportYearOrderByTeamIdAscWeekNoDesc(lastYear)).thenReturn(java.util.List.of(r1, r2, r3));

        java.util.Map<String, Object> out = service.completion(lastYear, ADMIN);
        assertThat(out).containsEntry("year", lastYear).containsEntry("weeks", weeksInYear);
        assertThat(out.get("current_week")).isNull();
        @SuppressWarnings("unchecked") java.util.List<java.util.Map<String, Object>> teams = (java.util.List<java.util.Map<String, Object>>) out.get("teams");
        assertThat(teams).hasSize(2);
        // A: 1 onaylı, eksik = (weeks-1); B: 0 onaylı, eksik = weeks-1 (1. hafta onay bekliyor eksik sayılmaz) → eşit eksik; sıra korunur (A, B)
        assertThat(teams.get(0)).containsEntry("team_name", "Takım A").containsEntry("approved", 1).containsEntry("missing", weeksInYear - 1);
        @SuppressWarnings("unchecked") java.util.List<java.util.Map<String, Object>> cellsA = (java.util.List<java.util.Map<String, Object>>) teams.get(0).get("cells");
        assertThat(cellsA).hasSize(weeksInYear);
        assertThat(cellsA.get(0)).containsEntry("status", "APPROVED").containsEntry("report_id", 11L);
        assertThat(cellsA.get(1)).containsEntry("status", "DRAFT");
        assertThat(cellsA.get(2)).containsEntry("status", "MISSING");
        assertThat(out.get("total_missing")).isEqualTo(2 * (weeksInYear - 1));

        @SuppressWarnings("unchecked") java.util.List<?> userTeams = (java.util.List<?>) service.completion(lastYear, USER_T2).get("teams");
        assertThat(userTeams).isEmpty();
    }
    // ── Haftalık Raporlar zenginleştirmesi (2026-09-13) ─────────────────────────

    @Test
    @DisplayName("carryTemplate: sayılar sıfır, dolu notlar 'Geçen haftadan devam' başlığıyla taşınır, boş not boş kalır, çift başlık yok")
    void carryTemplate_carriesNotesResetsCounts() throws Exception {
        String prev = "{\"version\":1,\"item1\":{\"total\":5,\"urgent\":1,\"high\":2,\"medium\":1,\"low\":1,\"status_text\":\"Planlandı\",\"tracking_url\":\"https://x.example.com\",\"notes_md\":\"Sürüm 2 devam\"},"
                + "\"item2\":{\"open_incidents\":3,\"problem_records\":1,\"postmortems\":0,\"notes_md\":\"\"},\"item3\":{\"notes_md\":\"**Geçen haftadan devam (W35)**\\n\\nEski\"},"
                + "\"item4\":{\"channels\":[{\"id\":\"c-1\",\"name\":\"Kanal A\",\"notes_md\":\"kanal notu\"}]}}";
        String out = WeeklyReportService.carryTemplate(prev, "2026-W36");
        var root = new ObjectMapper().readTree(out);
        assertThat(root.path("item1").path("total").asInt()).isZero();
        assertThat(root.path("item1").path("urgent").asInt()).isZero();
        assertThat(root.path("item1").path("tracking_url").asText()).isEqualTo("https://x.example.com");   // yapı korunur
        assertThat(root.path("item1").path("notes_md").asText()).startsWith("**Geçen haftadan devam (2026-W36)**").contains("Sürüm 2 devam");
        assertThat(root.path("item2").path("open_incidents").asInt()).isZero();
        assertThat(root.path("item2").path("notes_md").asText()).isEmpty();
        assertThat(root.path("item3").path("notes_md").asText()).isEqualTo("**Geçen haftadan devam (W35)**\n\nEski");   // zaten başlıklı → çiftlenmez
        assertThat(root.path("item4").path("channels").get(0).path("notes_md").asText()).contains("kanal notu");
        // Bozuk JSON → resetTemplate'e düşer (istisna yok)
        assertThat(WeeklyReportService.carryTemplate("{bozuk", "x")).isNotNull();
    }

    @Test
    @DisplayName("create(carryNotes=true) önceki rapordan carryTemplate ile, false ise resetTemplate ile başlar")
    void create_carryFlagSelectsTemplate() {
        WeeklyReport prev = report(40L, 2L, "APPROVED");
        prev.setContentJson("{\"version\":1,\"item1\":{\"total\":2,\"notes_md\":\"not\"},\"item2\":{},\"item3\":{\"notes_md\":\"\"},\"item4\":{\"channels\":[]}}");
        when(reportRepo.findFirstByTeamIdOrderByReportYearDescWeekNoDesc(2L)).thenReturn(Optional.of(prev));
        when(reportRepo.findByTeamIdAndReportYearAndWeekNo(eq(2L), anyInt(), anyInt())).thenReturn(Optional.empty());
        LocalDate today = WeeklyReportService.today();
        int y = today.get(WeekFields.ISO.weekBasedYear()), w = today.get(WeekFields.ISO.weekOfWeekBasedYear());
        WeeklyReport carried = service.create(2L, y, w, USER_T2, true);
        assertThat(carried.getContentJson()).contains("Geçen haftadan devam").contains("\"total\":0");
        WeeklyReport plain = service.create(2L, y, w, USER_T2, false);
        assertThat(plain.getContentJson()).doesNotContain("Geçen haftadan devam").doesNotContain("\"notes_md\":\"not\"");
    }

    @Test
    @DisplayName("previous: aynı takım bir önceki hafta; 1. haftada önceki yılın son ISO haftasına geçer; yoksa null")
    void previous_crossesYearBoundary() {
        WeeklyReport cur = report(5L, 2L, "DRAFT"); cur.setReportYear(2026); cur.setWeekNo(10);
        WeeklyReport prev = report(4L, 2L, "APPROVED"); prev.setReportYear(2026); prev.setWeekNo(9);
        when(reportRepo.findByTeamIdAndReportYearAndWeekNo(2L, 2026, 9)).thenReturn(Optional.of(prev));
        assertThat(service.previous(cur)).isSameAs(prev);
        WeeklyReport first = report(6L, 2L, "DRAFT"); first.setReportYear(2026); first.setWeekNo(1);
        when(reportRepo.findByTeamIdAndReportYearAndWeekNo(2L, 2025, 52)).thenReturn(Optional.empty());
        assertThat(service.previous(first)).isNull();
        org.mockito.Mockito.verify(reportRepo).findByTeamIdAndReportYearAndWeekNo(2L, 2025, 52);   // 2025'in son ISO haftası 52
    }

    @Test
    @DisplayName("thisWeek: kullanıcı kendi takımını görür (MISSING/DRAFT), admin hatırlatması açık tüm takımları; due_at UTC ISO ve is_past sunucuda")
    void thisWeek_scopesTeams() {
        Team t2 = team(2L, "TakimA", "a@test"); t2.setWeeklyReminderEnabled(true); t2.setActive(true);
        Team t7 = team(7L, "TakimB", "b@test"); t7.setWeeklyReminderEnabled(false); t7.setActive(true);
        when(teamRepo.findByActiveTrueOrderByNameAsc()).thenReturn(List.of(t2, t7));
        LocalDate today = WeeklyReportService.today();
        int y = today.get(WeekFields.ISO.weekBasedYear()), w = today.get(WeekFields.ISO.weekOfWeekBasedYear());
        when(reportRepo.findByTeamIdAndReportYearAndWeekNo(2L, y, w)).thenReturn(Optional.of(report(5L, 2L, "DRAFT")));
        when(reportRepo.findByTeamIdAndReportYearAndWeekNo(7L, y, w)).thenReturn(Optional.empty());

        @SuppressWarnings("unchecked") var mine = (java.util.List<java.util.Map<String, Object>>) service.thisWeek(USER_T2).get("teams");
        assertThat(mine).hasSize(1);
        assertThat(mine.get(0)).containsEntry("team_id", 2L).containsEntry("status", "DRAFT").containsEntry("report_id", 5L);

        java.util.Map<String, Object> adminView = service.thisWeek(ADMIN);
        @SuppressWarnings("unchecked") var all = (java.util.List<java.util.Map<String, Object>>) adminView.get("teams");
        assertThat(all).extracting(m -> m.get("team_id")).containsExactly(2L);   // hatırlatması kapalı takım sayılmaz
        assertThat(adminView.get("week")).isEqualTo(w);
        assertThat(String.valueOf(adminView.get("due_at"))).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}");
        assertThat(adminView.get("is_past")).isInstanceOf(Boolean.class);
        assertThat(adminView.get("deadline_day")).isEqualTo("FRI");
    }

    @Test
    @DisplayName("submit: gönderim anında skor KPI'dan yazılır; KPI hatası gönderimi durdurmaz")
    void submit_storesScoreSnapshot() {
        WeeklyReport r = report(5L, 2L, "DRAFT");
        when(reportRepo.findById(5L)).thenReturn(Optional.of(r));
        var score = new WeeklyReportKpiService.ScoreBlock(77, null, "amber");
        var summary = new WeeklyReportKpiService.SummaryBlock(java.util.Map.of(), score, java.util.List.of(), java.util.List.of());
        when(kpiService.compute(2L, r.getReportYear(), r.getWeekNo())).thenReturn(new WeeklyReportKpiService.WeeklyReportKpis(null, null, java.util.List.of(), summary));
        service.submit(5L, USER_T2);
        assertThat(r.getScore()).isEqualTo(77);
        assertThat(r.getStatus()).isEqualTo("PENDING_APPROVAL");

        WeeklyReport r2 = report(6L, 2L, "DRAFT");
        when(reportRepo.findById(6L)).thenReturn(Optional.of(r2));
        when(kpiService.compute(2L, r2.getReportYear(), r2.getWeekNo())).thenThrow(new RuntimeException("kpi down"));
        service.submit(6L, USER_T2);
        assertThat(r2.getScore()).isNull();
        assertThat(r2.getStatus()).isEqualTo("PENDING_APPROVAL");
    }
    // ── İkinci tur (2026-09-13): yorum dizisi, takım kanal şablonu, hatırlatma görünürlüğü ──

    @Test
    @DisplayName("yorum dizisi: durum geçişleri sistem yorumu düşer (SUBMIT/REJECT), serbest yorum doğrulanır, AUDIT yazamaz, silme kaskadı")
    void comments_systemAndFree() {
        var commentRepo = org.mockito.Mockito.mock(com.sitemonitor.repository.WeeklyReportCommentRepository.class);
        when(commentRepo.save(any())).thenAnswer(i -> { var c = (com.sitemonitor.model.WeeklyReportComment) i.getArgument(0); c.setId(1L); return c; });
        ReflectionTestUtils.setField(service, "commentRepo", commentRepo);
        WeeklyReport r = report(5L, 2L, "DRAFT");
        when(reportRepo.findById(5L)).thenReturn(Optional.of(r));
        when(contactRepo.findByTeamIdAndRoleAndActiveTrue(2L, "PO")).thenReturn(List.of());
        when(userRepo.findByTeamIdAndOrgRoleAndActiveTrue(2L, "PO")).thenReturn(List.of());
        service.submit(5L, USER_T2);
        var cap = org.mockito.ArgumentCaptor.forClass(com.sitemonitor.model.WeeklyReportComment.class);
        org.mockito.Mockito.verify(commentRepo).save(cap.capture());
        assertThat(cap.getValue().getKind()).isEqualTo("SUBMIT");
        assertThat(cap.getValue().getReportId()).isEqualTo(5L);
        service.reject(5L, "  eksik veri ", ADMIN);
        org.mockito.Mockito.verify(commentRepo, org.mockito.Mockito.times(2)).save(cap.capture());
        assertThat(cap.getValue().getKind()).isEqualTo("REJECT");
        assertThat(cap.getValue().getText()).isEqualTo("eksik veri");
        var free = service.addComment(5L, "  Not düştüm ", USER_T2);
        assertThat(free.getKind()).isEqualTo("COMMENT");
        assertThat(free.getText()).isEqualTo("Not düştüm");
        assertThatThrownBy(() -> service.addComment(5L, "   ", USER_T2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.addComment(5L, "x".repeat(WeeklyReportService.COMMENT_MAX + 1), USER_T2)).isInstanceOf(IllegalArgumentException.class);
        Actor audit = new Actor(99L, "audit", "Denetçi", null, "AUDIT");
        assertThatThrownBy(() -> service.addComment(5L, "yorum", audit)).isInstanceOf(SecurityException.class);
        when(commentRepo.countByReportIds(any())).thenReturn(List.<Object[]>of(new Object[]{5L, 3L}));
        assertThat(service.commentCounts(List.of(5L))).containsEntry(5L, 3L);
        // silme kaskadı
        WeeklyReport d = report(6L, 2L, "DRAFT");
        when(reportRepo.findById(6L)).thenReturn(Optional.of(d));
        service.delete(6L, ADMIN);
        org.mockito.Mockito.verify(commentRepo).deleteByReportId(6L);
    }

    @Test
    @DisplayName("takım kanal şablonu: JSON dizi temizlenir (boş/tekrar/uzun), ilk rapor şablonla başlar; şablon yoksa varsayılan (Web Kanalı)")
    void teamChannelTemplate() {
        assertThat(WeeklyReportService.channelTemplate(null)).isEmpty();
        assertThat(WeeklyReportService.channelTemplate("{bozuk")).isEmpty();
        assertThat(WeeklyReportService.channelTemplate("[\"A\",\" \",\"A\",\"B\"]")).containsExactly("A", "B");
        assertThat(WeeklyReportService.channelTemplate("[\"" + "x".repeat(80) + "\"]").get(0)).hasSize(60);
        Team t = team(2L, "TakimA", "a@test"); t.setWeeklyChannels("[\"Mobil\",\"Şube\"]");
        String tpl = WeeklyReportService.templateForTeam(t);
        assertThat(tpl).contains("\"name\":\"Mobil\"").contains("\"name\":\"Şube\"").doesNotContain("Çağrı Merkezi");
        assertThat(WeeklyReportService.templateForTeam(team(3L, "B", "b@test"))).contains("Web Kanalı").doesNotContain("Akbank");
        // create: önceki rapor yok → takım şablonu
        when(teamRepo.findById(2L)).thenReturn(Optional.of(t));
        when(reportRepo.findFirstByTeamIdOrderByReportYearDescWeekNoDesc(2L)).thenReturn(Optional.empty());
        when(reportRepo.findByTeamIdAndReportYearAndWeekNo(eq(2L), anyInt(), anyInt())).thenReturn(Optional.empty());
        LocalDate today = WeeklyReportService.today();
        WeeklyReport created = service.create(2L, today.get(WeekFields.ISO.weekBasedYear()), today.get(WeekFields.ISO.weekOfWeekBasedYear()), USER_T2);
        assertThat(created.getContentJson()).contains("Mobil").contains("Şube");
    }

    @Test
    @DisplayName("hatırlatma görünürlüğü: sonraki koşu son giriş gününde 09:00 (gelecekte), opt-in/e-postasız/girmiş sayaçları")
    void reminderStatus() {
        Team a = team(2L, "TakimA", "a@test"); a.setWeeklyReminderEnabled(true);
        Team b = team(7L, "TakimB", ""); b.setWeeklyReminderEnabled(true);
        Team c = team(8L, "TakimC", "c@test"); c.setWeeklyReminderEnabled(false);
        when(teamRepo.findByActiveTrueOrderByNameAsc()).thenReturn(List.of(a, b, c));
        // Sayaçlar sonraki koşunun (ilk gelecek Cuma 09:00 IST) haftasına göre — QA ISSUE-002
        java.time.ZonedDateTime nowIst = java.time.ZonedDateTime.now(java.time.ZoneId.of("Europe/Istanbul"));
        java.time.ZonedDateTime run = nowIst.toLocalDate().atTime(9, 0).atZone(java.time.ZoneId.of("Europe/Istanbul"));
        while (run.getDayOfWeek() != java.time.DayOfWeek.FRIDAY || !run.isAfter(nowIst)) run = run.plusDays(1);
        LocalDate runDay = run.toLocalDate();
        int y = runDay.get(WeekFields.ISO.weekBasedYear()), w = runDay.get(WeekFields.ISO.weekOfWeekBasedYear());
        when(reportRepo.findByTeamIdAndReportYearAndWeekNo(2L, y, w)).thenReturn(Optional.of(report(5L, 2L, "APPROVED")));
        when(reportRepo.findByTeamIdAndReportYearAndWeekNo(7L, y, w)).thenReturn(Optional.empty());
        java.util.Map<String, Object> st = service.reminderStatus(true);
        assertThat(st).containsEntry("enabled", true).containsEntry("opt_in_teams", 2).containsEntry("no_email", 1)
                .containsEntry("already_done", 1).containsEntry("will_send", 0).containsEntry("deadline_day", "FRI")
                .containsEntry("run_week", String.format("%d-W%02d", y, w));
        String next = String.valueOf(st.get("next_run_at"));
        assertThat(next).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}");
        java.time.ZonedDateTime n = java.time.LocalDateTime.parse(next).atZone(java.time.ZoneOffset.UTC).withZoneSameInstant(java.time.ZoneId.of("Europe/Istanbul"));
        assertThat(n.getDayOfWeek()).isEqualTo(java.time.DayOfWeek.FRIDAY);
        assertThat(n.getHour()).isEqualTo(9);
        assertThat(n.toInstant()).isAfter(java.time.Instant.now());
    }
    @Test
    @DisplayName("onay → takıma push (2026-09-13): WEEKLY_REPORT tetiği, WARNING, 'onaylandı ve müdüre gönderildi'; mail FAILED → metin BAŞARISIZ; yeniden gönderim ayrı anahtar; push hatası onayı durdurmaz")
    void approve_pushesTeam() {
        UserPushService push = mock(UserPushService.class);
        when(push.weeklyTeamEnabled()).thenReturn(true);
        when(push.weeklyManagerEnabled()).thenReturn(true);
        when(push.enqueueTeamNotice(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(Map.of("queued", 1));
        when(push.enqueueDirect(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(Map.of("queued", 1));
        ReflectionTestUtils.setField(service, "userPushService", push);
        when(contactRepo.findByTeamIdAndRoleAndActiveTrue(2L, "MANAGER"))
                .thenReturn(List.of(contact("MANAGER", "Ali Müdür", "mudur@test.com")));
        // Müdür: takımda elle atanmış müdür YOK → MANAGER kontağının e-postasıyla eşleşen kullanıcı
        AppUser mgr = new AppUser(); mgr.setId(50L); mgr.setUsername("M00050"); mgr.setDisplayName("Ali Müdür");
        mgr.setEmail("mudur@test.com"); mgr.setActive(true); mgr.setPushOptOut(false);
        when(userRepo.findActiveByEmailsLower(any())).thenReturn(List.of(mgr));

        WeeklyReport r = report(5L, 2L, "PENDING_APPROVAL");
        when(reportRepo.findById(5L)).thenReturn(Optional.of(r));
        service.approve(5L, PO_T2);
        ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(push).enqueueTeamNotice(eq(2L), eq("WEEKLY_REPORT"), eq("WARNING"), eq("WEEKLY_REPORT"),
                eq("TakimA " + r.getWeekLabel()), msg.capture(), key.capture(), any());
        assertThat(msg.getValue()).startsWith("[Haftalık rapor] TakimA " + r.getWeekLabel())
                .contains("onaylandı ve müdüre gönderildi").contains("Onaylayan: PO İki");
        assertThat(key.getValue()).isEqualTo("WR_APPROVED:5:" + r.getVersion());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<UserPushService.DirectRecipient>> mgrs = ArgumentCaptor.forClass(List.class);
        verify(push).enqueueDirect(mgrs.capture(), eq(2L), eq("WEEKLY_REPORT"), eq("WARNING"), eq("WEEKLY_REPORT"),
                eq("TakimA " + r.getWeekLabel()), contains("onaylandı; rapor e-postanıza gönderildi"), eq("WR_APPROVED:5:" + r.getVersion() + ":MGR"));
        assertThat(mgrs.getValue()).extracting(UserPushService.DirectRecipient::username).containsExactly("M00050");

        // mail FAILED → takım "gönderildi" sanmasın
        WeeklyReport r2 = report(6L, 2L, "PENDING_APPROVAL");
        when(reportRepo.findById(6L)).thenReturn(Optional.of(r2));
        when(emailService.sendHtml(any(), any(), anyString(), anyString(), any())).thenReturn("FAILED: smtp down");
        service.approve(6L, PO_T2);
        verify(push).enqueueTeamNotice(eq(2L), eq("WEEKLY_REPORT"), eq("WARNING"), eq("WEEKLY_REPORT"),
                anyString(), contains("BAŞARISIZ (FAILED: smtp down)"), eq("WR_APPROVED:6:" + r2.getVersion()), any());
        assertThat(r2.getStatus()).isEqualTo("APPROVED");
        verify(push).enqueueDirect(any(), eq(2L), eq("WEEKLY_REPORT"), eq("WARNING"), eq("WEEKLY_REPORT"),
                anyString(), contains("Raporu uygulamadan görüntüleyin"), eq("WR_APPROVED:6:" + r2.getVersion() + ":MGR"));

        // yeniden gönderim: ayrı anahtar + "yeniden gönderildi"
        when(emailService.sendHtml(any(), any(), anyString(), anyString(), any())).thenReturn("SENT");
        service.resend(5L, ADMIN);
        verify(push).enqueueTeamNotice(eq(2L), eq("WEEKLY_REPORT"), eq("WARNING"), eq("WEEKLY_REPORT"),
                anyString(), contains("müdüre yeniden gönderildi"), eq("WR_RESENT:5:" + r.getVersion()), any());

        // ayarlar kapalı → hiçbir kanal çağrılmaz
        when(push.weeklyTeamEnabled()).thenReturn(false);
        when(push.weeklyManagerEnabled()).thenReturn(false);
        WeeklyReport r9 = report(9L, 2L, "PENDING_APPROVAL");
        when(reportRepo.findById(9L)).thenReturn(Optional.of(r9));
        service.approve(9L, PO_T2);
        org.mockito.Mockito.verify(push, org.mockito.Mockito.times(3)).enqueueTeamNotice(any(), any(), any(), any(), any(), any(), any(), any());
        org.mockito.Mockito.verify(push, org.mockito.Mockito.times(3)).enqueueDirect(any(), any(), any(), any(), any(), any(), any(), any());
        when(push.weeklyTeamEnabled()).thenReturn(true);
        when(push.weeklyManagerEnabled()).thenReturn(true);

        // push patlarsa onay yine olur
        WeeklyReport r3 = report(7L, 2L, "PENDING_APPROVAL");
        when(reportRepo.findById(7L)).thenReturn(Optional.of(r3));
        when(push.enqueueTeamNotice(any(), any(), any(), any(), any(), any(), any(), any())).thenThrow(new RuntimeException("push down"));
        assertThat(service.approve(7L, PO_T2)).containsKey("data");
        assertThat(r3.getStatus()).isEqualTo("APPROVED");

        // servis yoksa (eski kurulum) sessiz
        ReflectionTestUtils.setField(service, "userPushService", null);
        WeeklyReport r4 = report(8L, 2L, "PENDING_APPROVAL");
        when(reportRepo.findById(8L)).thenReturn(Optional.of(r4));
        assertThat(service.approve(8L, PO_T2)).containsKey("data");
    }
    @Test
    @DisplayName("müdür push alıcıları (2026-09-13): elle atanmış müdür > MANAGER kontağı e-postası > AD zinciri; pasif/opt-out işaretli; hiçbiri yoksa boş")
    void resolveManagerPushRecipients_order() {
        Team team = team(2L, "TakimA", "a@test");
        AppUser manual = new AppUser(); manual.setId(60L); manual.setUsername("M00060"); manual.setDisplayName("Elle Müdür"); manual.setActive(true); manual.setPushOptOut(true);
        AppUser byMail = new AppUser(); byMail.setId(61L); byMail.setUsername("M00061"); byMail.setDisplayName("Kontak Müdür"); byMail.setActive(true);
        AppUser ad = new AppUser(); ad.setId(62L); ad.setUsername("M00062"); ad.setDisplayName("AD Müdür"); ad.setActive(true); ad.setEmail("ad@test");
        AppUser member = new AppUser(); member.setId(70L); member.setUsername("U00070"); member.setActive(true); member.setManagerId(62L);

        // 1) elle atanmış müdür — opt-out bayrağı taşınır
        team.setManagerId(60L);
        when(userRepo.findById(60L)).thenReturn(Optional.of(manual));
        var r1 = service.resolveManagerPushRecipients(team, 2L);
        assertThat(r1).hasSize(1);
        assertThat(r1.get(0).username()).isEqualTo("M00060");
        assertThat(r1.get(0).optOut()).isTrue();

        // 2) elle müdür pasif → MANAGER kontağı e-postası
        manual.setActive(false);
        when(contactRepo.findByTeamIdAndRoleAndActiveTrue(2L, "MANAGER")).thenReturn(List.of(contact("MANAGER", "Ali", "Mudur@Test.com")));
        when(userRepo.findActiveByEmailsLower(java.util.Set.of("mudur@test.com"))).thenReturn(List.of(byMail));
        var r2 = service.resolveManagerPushRecipients(team, 2L);
        assertThat(r2).extracting(UserPushService.DirectRecipient::username).containsExactly("M00061");

        // 3) kontak eşleşmedi → AD zinciri (üyelerin manager_id'si, e-postası olan aktif kullanıcı)
        when(userRepo.findActiveByEmailsLower(any())).thenReturn(List.of());
        when(userRepo.findByTeamIdOrderByUsernameAsc(2L)).thenReturn(List.of(member));
        when(userRepo.findById(62L)).thenReturn(Optional.of(ad));
        var r3 = service.resolveManagerPushRecipients(team, 2L);
        assertThat(r3).extracting(UserPushService.DirectRecipient::username).containsExactly("M00062");
        assertThat(r3.get(0).displayName()).isEqualTo("AD Müdür");

        // 4) hiçbiri → boş
        when(userRepo.findByTeamIdOrderByUsernameAsc(2L)).thenReturn(List.of());
        assertThat(service.resolveManagerPushRecipients(team(3L, "B", null), 3L)).isEmpty();
    }
}
