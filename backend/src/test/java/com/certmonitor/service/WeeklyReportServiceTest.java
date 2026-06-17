package com.certmonitor.service;

import com.certmonitor.model.AppUser;
import com.certmonitor.model.EscalationContact;
import com.certmonitor.model.Team;
import com.certmonitor.model.WeeklyReport;
import com.certmonitor.model.WeeklyReportMail;
import com.certmonitor.repository.AppUserRepository;
import com.certmonitor.repository.EscalationContactRepository;
import com.certmonitor.repository.TeamRepository;
import com.certmonitor.repository.WeeklyReportImageRepository;
import com.certmonitor.repository.WeeklyReportRepository;
import com.certmonitor.service.WeeklyReportService.Actor;
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
    @Mock com.certmonitor.repository.WeeklyReportMailRepository mailRepo;
    @Mock TeamRepository teamRepo;
    @Mock AppUserRepository userRepo;
    @Mock EscalationContactRepository contactRepo;
    @Mock EmailNotificationService emailService;
    @Mock AppSettingsService appSettings;

    private WeeklyReportService service;

    private static final Actor ADMIN      = new Actor(1L, "admin", "Admin", null, "ADMIN");
    private static final Actor USER_T2    = new Actor(10L, "user2", "Kullanıcı İki", 2L, "USER");
    private static final Actor USER_T7    = new Actor(11L, "user7", "Kullanıcı Yedi", 7L, "USER");
    private static final Actor TADMIN_T2  = new Actor(12L, "tadmin", "Takım Admin", 2L, "TEAM_ADMIN");
    private static final Actor PO_T2      = new Actor(13L, "po2", "PO İki", 2L, "USER");

    @BeforeEach
    void setUp() {
        service = new WeeklyReportService(reportRepo, imageRepo, mailRepo, teamRepo, userRepo,
                contactRepo, emailService, new ObjectMapper(), appSettings);
        ReflectionTestUtils.setField(service, "imageMaxBytes", 2L * 1024 * 1024);
        when(appSettings.getString(anyString(), any())).thenAnswer(i -> i.getArgument(1));
        when(reportRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(mailRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(emailService.fromAddress()).thenReturn("certmonitor@test");
        when(teamRepo.findById(2L)).thenReturn(Optional.of(team(2L, "DijitalSY", "takim@test.com")));
        when(emailService.sendHtml(any(), any(), anyString(), anyString(), any())).thenReturn("SENT");
        when(emailService.buildWeeklyReportSubmittedHtml(any(), any(), any(), any())).thenReturn("<html/>");
        when(emailService.buildWeeklyReportRejectedHtml(any(), any(), any(), any())).thenReturn("<html/>");
        when(emailService.buildWeeklyReportHtml(any(), any(), any(), any(), anyBoolean())).thenReturn("<html/>");
        when(emailService.buildWeeklyReportHtml(any(), any(), any(), any(), anyBoolean(), any())).thenReturn("<html/>");
        when(emailService.buildWeeklyReportHtml(any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any())).thenReturn("<html/>");
        when(emailService.buildWeeklyReportHtml(any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any())).thenReturn("<html/>");
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
        return reportAtWeek(id, teamId, status, LocalDate.now());
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
        assertThat(created.getContentJson()).contains("Çağrı Merkezi").contains("Akbank.com");

        when(reportRepo.findByTeamIdAndReportYearAndWeekNo(2L, 2026, 24))
                .thenReturn(Optional.of(created));
        assertThatThrownBy(() -> service.create(null, 2026, 24, USER_T2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DUPLICATE_WEEK");
    }

    @Test
    @DisplayName("computeWeekLabel: ISO hafta Pzt–Cum aralığı üretir")
    void computeWeekLabel_isoWeek() {
        // 2026-W24: 8–12 Haziran 2026 (Pzt 8 Haziran)
        assertThat(WeeklyReportService.computeWeekLabel(2026, 24))
                .isEqualTo("2026-W24 (8–12 Haziran 2026)");
    }

    @Test
    @DisplayName("years: USER kendi takımına zorlanır; içinde bulunulan yıl listede yoksa eklenir")
    void years_scopingAndCurrentYear() {
        int current = LocalDate.now().get(WeekFields.ISO.weekBasedYear());

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
        WeeklyReport lastWeek = reportAtWeek(5L, 2L, "REJECTED", LocalDate.now().minusWeeks(1));
        when(reportRepo.findById(5L)).thenReturn(Optional.of(lastWeek));
        assertThat(service.saveContent(5L, WeeklyReportService.DEFAULT_TEMPLATE_JSON, null, USER_T2)
                .getStatus()).isEqualTo("DRAFT"); // iade edilen geçen hafta raporu düzeltilebilir

        WeeklyReport old = reportAtWeek(6L, 2L, "DRAFT", LocalDate.now().minusWeeks(2));
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
        WeeklyReport r = reportAtWeek(5L, 2L, "APPROVED", LocalDate.now().minusWeeks(3));
        when(reportRepo.findById(5L)).thenReturn(Optional.of(r));

        WeeklyReport saved = service.saveContent(5L, WeeklyReportService.DEFAULT_TEMPLATE_JSON, null, ADMIN);

        assertThat(saved.getStatus()).isEqualTo("APPROVED");
        assertThat(saved.getUpdatedBy()).isEqualTo("Admin");
    }

    @Test
    @DisplayName("inEditWindow: mevcut + önceki hafta true; 2 hafta önce ve gelecek hafta false")
    void inEditWindow_bounds() {
        WeekFields wf = WeekFields.ISO;
        for (LocalDate d : List.of(LocalDate.now(), LocalDate.now().minusWeeks(1))) {
            assertThat(WeeklyReportService.inEditWindow(
                    d.get(wf.weekBasedYear()), d.get(wf.weekOfWeekBasedYear()))).isTrue();
        }
        for (LocalDate d : List.of(LocalDate.now().minusWeeks(2), LocalDate.now().plusWeeks(1))) {
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
        assertThat(m.getFromAddress()).isEqualTo("certmonitor@test");
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
    @DisplayName("delete: USER kendi pencere içi DRAFT'ını siler — görseller de gider")
    void delete_userOwnDraft() {
        WeeklyReport r = report(5L, 2L, "DRAFT");
        when(reportRepo.findById(5L)).thenReturn(Optional.of(r));

        service.delete(5L, USER_T2);

        verify(imageRepo).deleteByReportId(5L);
        verify(mailRepo).deleteByReportId(5L);
        verify(reportRepo).delete(r);
    }

    @Test
    @DisplayName("delete: ADMIN eski haftanın APPROVED raporunu silebilir")
    void delete_adminApprovedOldWeek() {
        WeeklyReport r = reportAtWeek(5L, 2L, "APPROVED", LocalDate.now().minusWeeks(5));
        when(reportRepo.findById(5L)).thenReturn(Optional.of(r));

        service.delete(5L, ADMIN);

        verify(imageRepo).deleteByReportId(5L);
        verify(reportRepo).delete(r);
    }

    @Test
    @DisplayName("delete: USER APPROVED'ı silemez (409); başka takım 403")
    void delete_forbidden() {
        WeeklyReport approved = report(5L, 2L, "APPROVED");
        when(reportRepo.findById(5L)).thenReturn(Optional.of(approved));
        assertThatThrownBy(() -> service.delete(5L, USER_T2))
                .isInstanceOf(IllegalStateException.class);

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
                eq("[DijitalSY] Haftalık Rapor — 2026-W24 (8–12 Haziran 2026)"),
                anyString(), any());
        assertThat(to.getValue()).containsExactly("mudur@test.com");
        assertThat(cc.getValue()).containsExactly("takim@test.com");
        verify(emailService).buildWeeklyReportHtml(eq("DijitalSY"), anyString(),
                eq("Ali Müdür"), anyString(), eq(true), any(), any(), any(), any());
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
        verify(emailService).buildWeeklyReportRejectedHtml(eq("DijitalSY"), anyString(),
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
        WeeklyReport r = reportAtWeek(6L, 2L, "APPROVED", LocalDate.now().minusWeeks(5));
        when(reportRepo.findById(6L)).thenReturn(Optional.of(r));

        assertThat(service.reopen(6L, ADMIN).getStatus()).isEqualTo("DRAFT");
    }

    @Test
    @DisplayName("reopen: non-admin pencere dışı → 403; AUDIT → 403; APPROVED değilse → 409")
    void reopen_forbiddenAndStateGuards() {
        WeeklyReport oldR = reportAtWeek(6L, 2L, "APPROVED", LocalDate.now().minusWeeks(5));
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
        com.certmonitor.model.WeeklyReportImage img = new com.certmonitor.model.WeeklyReportImage();
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
        mgr.setId(70L); mgr.setActive(true); mgr.setEmail("mudur@akbank.com"); mgr.setDisplayName("Ali Müdür");
        when(userRepo.findById(70L)).thenReturn(Optional.of(mgr));

        Map<String, Object> out = service.approve(5L, PO_T2);

        assertThat(((WeeklyReport) out.get("data")).getStatus()).isEqualTo("APPROVED");
        ArgumentCaptor<String[]> toCap = ArgumentCaptor.forClass(String[].class);
        verify(emailService).sendHtml(toCap.capture(), any(), anyString(), anyString(), any());
        assertThat(toCap.getValue()).contains("mudur@akbank.com");
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
}
