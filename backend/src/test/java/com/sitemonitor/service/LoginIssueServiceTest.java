package com.sitemonitor.service;

import com.sitemonitor.model.LoginIssueReport;
import com.sitemonitor.repository.LoginIssueReportImageRepository;
import com.sitemonitor.repository.LoginIssueReportRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** LoginIssueService — kalıcılık + durum akışı iş kuralları (mock repolar). */
class LoginIssueServiceTest {

    LoginIssueReportRepository reportRepo;
    LoginIssueReportImageRepository imageRepo;
    com.sitemonitor.repository.LoginIssueMailLogRepository mailLogRepo;
    com.sitemonitor.repository.IssueReportCommentRepository commentRepo;
    LoginIssueService service;

    @BeforeEach
    void setup() {
        reportRepo = mock(LoginIssueReportRepository.class);
        imageRepo = mock(LoginIssueReportImageRepository.class);
        mailLogRepo = mock(com.sitemonitor.repository.LoginIssueMailLogRepository.class);
        commentRepo = mock(com.sitemonitor.repository.IssueReportCommentRepository.class);
        service = new LoginIssueService(reportRepo, imageRepo, mailLogRepo, commentRepo);
    }

    @Test
    @DisplayName("save: OPEN durumlu kayıt + imageCount + her görsel için satır")
    void save_persistsOpenReportWithImages() {
        when(reportRepo.save(any())).thenAnswer(inv -> { LoginIssueReport r = inv.getArgument(0); r.setId(7L); return r; });
        LoginIssueReport saved = service.save("N1", "user@example.com", "err", "msg",
                List.of(new LoginIssueService.ParsedImage("image/png", "AAAA"),
                        new LoginIssueService.ParsedImage("image/jpeg", "BBBB")),
                "1.2.3.4", "UA", "2026-07-23T10:00:00");
        assertThat(saved.getId()).isEqualTo(7L);
        assertThat(saved.getReporterEmail()).isEqualTo("user@example.com");
        ArgumentCaptor<LoginIssueReport> cap = ArgumentCaptor.forClass(LoginIssueReport.class);
        verify(reportRepo).save(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo("OPEN");
        assertThat(cap.getValue().getImageCount()).isEqualTo(2);
        verify(imageRepo, times(2)).save(any());
    }

    @Test
    @DisplayName("RESOLVED'a çekerken çözüm notu zorunlu → IllegalArgumentException, save YOK")
    void resolve_requiresNote() {
        when(reportRepo.findById(5L)).thenReturn(Optional.of(report(5L, "OPEN")));
        assertThatThrownBy(() -> service.updateStatus(5L, "RESOLVED", "  ", "admin"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(reportRepo, never()).save(any());
    }

    @Test
    @DisplayName("RESOLVED + not → resolvedBy/resolvedAt/note set")
    void resolve_setsResolutionFields() {
        when(reportRepo.findById(5L)).thenReturn(Optional.of(report(5L, "IN_PROGRESS")));
        when(reportRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        LoginIssueReport out = service.updateStatus(5L, "RESOLVED", "fixed it", "admin");
        assertThat(out.getStatus()).isEqualTo("RESOLVED");
        assertThat(out.getResolvedBy()).isEqualTo("admin");
        assertThat(out.getResolvedAt()).isNotBlank();
        assertThat(out.getResolutionNote()).isEqualTo("fixed it");
    }

    @Test
    @DisplayName("Yeniden Aç (RESOLVED→OPEN): çözüm sahipliği (resolvedBy/At) temizlenir, not KORUNUR (çalışma notu)")
    void reopen_clearsOwnershipKeepsNote() {
        LoginIssueReport r = report(5L, "RESOLVED");
        r.setResolvedBy("admin"); r.setResolvedAt("2026-07-23T11:00:00"); r.setResolutionNote("done");
        when(reportRepo.findById(5L)).thenReturn(Optional.of(r));
        when(reportRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        LoginIssueReport out = service.updateStatus(5L, "OPEN", null, "admin2");
        assertThat(out.getStatus()).isEqualTo("OPEN");
        assertThat(out.getResolvedBy()).isNull();
        assertThat(out.getResolvedAt()).isNull();
        assertThat(out.getResolutionNote()).isEqualTo("done");   // not kaybolmaz (kalıcı çalışma notu)
    }

    @Test
    @DisplayName("İşleme Al (→IN_PROGRESS): not KALICI — verilmezse mevcut korunur, verilirse güncellenir; resolvedBy/At temizlenir")
    void inProgress_keepsOrUpdatesNote() {
        LoginIssueReport r = report(5L, "OPEN");
        r.setResolutionNote("triyaj notu");
        when(reportRepo.findById(5L)).thenReturn(Optional.of(r));
        when(reportRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        // (a) not verilmeden İşleme Al → mevcut not kaybolmaz (kullanıcının yazdığı not korunur)
        LoginIssueReport out = service.updateStatus(5L, "IN_PROGRESS", null, "admin2");
        assertThat(out.getStatus()).isEqualTo("IN_PROGRESS");
        assertThat(out.getResolutionNote()).isEqualTo("triyaj notu");
        assertThat(out.getResolvedBy()).isNull();
        assertThat(out.getResolvedAt()).isNull();
        // (b) yeni (boş olmayan) not verilerek → not güncellenir
        LoginIssueReport out2 = service.updateStatus(5L, "IN_PROGRESS", "yeni çalışma notu", "admin2");
        assertThat(out2.getResolutionNote()).isEqualTo("yeni çalışma notu");
    }

    @Test
    @DisplayName("Geçersiz durum → IllegalArgumentException")
    void invalidStatus_throws() {
        assertThatThrownBy(() -> service.updateStatus(5L, "BOGUS", null, "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("counts: eksik durumlar 0 ile doldurulur; since/until geçirilir")
    void counts_fillsMissingWithZero() {
        when(reportRepo.countByStatus(any(), any()))
                .thenReturn(List.of(new Object[]{"OPEN", 3L}, new Object[]{"RESOLVED", 5L}));
        Map<String, Long> c = service.counts(null, null);
        assertThat(c.get("OPEN")).isEqualTo(3L);
        assertThat(c.get("IN_PROGRESS")).isEqualTo(0L);
        assertThat(c.get("RESOLVED")).isEqualTo(5L);
    }

    @Test
    @DisplayName("list: q '%küçükharf%'e sarılır; status/source/category geçersizse null'a düşer; NPE yok")
    void list_wrapsQueryAndNullStatus() {
        when(reportRepo.findFiltered(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(org.springframework.data.domain.Page.empty());
        assertThat(service.list(null, null, null, null, null, null, 0, 20)).isNotNull();
        // geçersiz status + geçersiz source/category + boş q → hepsi null'a düşer
        assertThat(service.list("BOGUS", "HACK", "WRONG", "  ", null, null, 0, 20)).isNotNull();
        service.list("OPEN", "USER_REPORT", "BLOCKER", "  Locked ", "2026-07-01T00:00:00", "2026-07-31T23:59:59", 1, 50);
        // status/source/category geçer; q → "%locked%"; since/until iletilir
        verify(reportRepo).findFiltered(eq("OPEN"), eq("USER_REPORT"), eq("BLOCKER"), isNull(), eq("%locked%"),
                eq("2026-07-01T00:00:00"), eq("2026-07-31T23:59:59"), any());
        // Alan adı aktarım talepleri (2026-09-28) yöneticinin süzebildiği bir tür
        service.list(null, null, "DOMAIN_TRANSFER", null, null, null, 0, 20);
        verify(reportRepo).findFiltered(isNull(), isNull(), eq("DOMAIN_TRANSFER"), isNull(), isNull(), isNull(), isNull(), any());
        // Etki süzgeci (2026-09-28): tek kod → ",KOD," deseni (tam kod eşleşmesi); bilinmeyen kod yok sayılır
        service.list(null, null, null, "SLOW", null, null, null, 0, 20);
        verify(reportRepo).findFiltered(isNull(), isNull(), isNull(), eq("%,SLOW,%"), isNull(), isNull(), isNull(), any());
        service.list(null, null, null, "SLOW%' OR 1=1", null, null, null, 0, 20);
        // İlk iki çağrı tüm filtreleri null'a düşürür → NPE atmadan çalıştı (isNotNull).
        verify(reportRepo, times(3)).findFiltered(isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), any());
    }

    @Test
    @DisplayName("save(meta): source/category/otomatik bağlam alanları + linkedReference kaydedilir")
    void save_withMeta_persistsSourceAndContext() {
        when(reportRepo.save(any())).thenAnswer(inv -> { LoginIssueReport r = inv.getArgument(0); r.setId(9L); return r; });
        LoginIssueService.ReportMeta meta = new LoginIssueService.ReportMeta(
                "USER_REPORT", "BLOCKER", "20.1.0", "1920x1080", "scripted",
                "{\"theme\":\"dark\"}", "LIR-2026-000077");
        LoginIssueReport saved = service.save("N1", "u@x.com", "err", "msg", List.of(),
                "10.0.0.1", "UA", "2026-08-06T20:00:00", meta);
        assertThat(saved.getSource()).isEqualTo("USER_REPORT");
        assertThat(saved.getCategory()).isEqualTo("BLOCKER");
        assertThat(saved.getAppVersion()).isEqualTo("20.1.0");
        assertThat(saved.getScreenSize()).isEqualTo("1920x1080");
        assertThat(saved.getTabKey()).isEqualTo("scripted");
        assertThat(saved.getAutoContextJson()).contains("dark");
        assertThat(saved.getLinkedReference()).isEqualTo("LIR-2026-000077");
    }

    @Test
    @DisplayName("save(meta + etkiler, 2026-09-28): impacts ve impactOther kaydedilir; etkisiz meta'da ikisi de null")
    void save_withImpacts_persists() {
        when(reportRepo.save(any())).thenAnswer(inv -> { LoginIssueReport r = inv.getArgument(0); r.setId(9L); return r; });
        LoginIssueReport saved = service.save("N1", "u@x.com", null, "msg", List.of(), "10.0.0.1", "UA", "2026-09-28T10:00:00",
                new LoginIssueService.ReportMeta("USER_REPORT", "ANNOYANCE", null, null, "dashboard", null, null,
                        "LOGIN,SLOW,OTHER", "VPN kapalıyken açılıyor"));
        assertThat(saved.getImpacts()).isEqualTo("LOGIN,SLOW,OTHER");
        assertThat(saved.getImpactOther()).isEqualTo("VPN kapalıyken açılıyor");
        LoginIssueReport plain = service.save("N1", "u@x.com", null, "msg", List.of(), "10.0.0.1", "UA", "2026-09-28T10:00:00",
                new LoginIssueService.ReportMeta("USER_REPORT", "BLOCKER", null, null, null, null, null));
        assertThat(plain.getImpacts()).isNull();
        assertThat(plain.getImpactOther()).isNull();
    }

    @Test
    @DisplayName("normalizeImpacts: izin listesi, tekilleştirme, KANONİK sıra, küçük harf kabul; bilinmeyen / liste olmayan → 400")
    void normalizeImpacts_allowlistDedupeOrder() {
        assertThat(LoginIssueService.normalizeImpacts(List.of("slow", "LOGIN", "SLOW", " other "))).isEqualTo("LOGIN,SLOW,OTHER");
        assertThat(LoginIssueService.normalizeImpacts(List.of())).isNull();
        assertThat(LoginIssueService.normalizeImpacts(null)).isNull();
        assertThat(LoginIssueService.normalizeImpacts(LoginIssueService.IMPACTS)).isEqualTo(String.join(",", LoginIssueService.IMPACTS));
        assertThat(LoginIssueService.IMPACTS).hasSize(12);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> LoginIssueService.normalizeImpacts(List.of("LOGIN", "HACK")))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> LoginIssueService.normalizeImpacts("LOGIN,SLOW"))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> LoginIssueService.normalizeImpacts(java.util.Collections.nCopies(25, "SLOW")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("sanitizeImpactOther: yalnız OTHER seçiliyse; kontrol/biçim karakterleri ayıklanır; 200'ü aşan → 400")
    void sanitizeImpactOther_rules() {
        String nl = Character.toString(10), rlo = Character.toString(0x202E), nul = Character.toString(0);
        assertThat(LoginIssueService.sanitizeImpactOther("serbest", "LOGIN,SLOW")).isNull();   // OTHER yok → saklanmaz
        assertThat(LoginIssueService.sanitizeImpactOther("  a" + nl + nl + "b" + rlo + "c" + nul + "  ", "OTHER")).isEqualTo("a b c");
        assertThat(LoginIssueService.sanitizeImpactOther("   ", "OTHER")).isNull();
        assertThat(LoginIssueService.sanitizeImpactOther("x".repeat(200), "LOGIN,OTHER")).hasSize(200);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> LoginIssueService.sanitizeImpactOther("x".repeat(201), "OTHER"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(LoginIssueService.impactList("LOGIN,BOGUS,SLOW")).containsExactly("LOGIN", "SLOW");
        assertThat(LoginIssueService.impactList(null)).isEmpty();
    }

    @Test
    @DisplayName("save (eski imza): kaynak varsayılanı LOGIN — login-help akışı meta bilmez")
    void save_legacySignature_defaultsToLoginSource() {
        when(reportRepo.save(any())).thenAnswer(inv -> { LoginIssueReport r = inv.getArgument(0); r.setId(9L); return r; });
        LoginIssueReport saved = service.save("N1", "u@x.com", null, "msg", List.of(),
                "10.0.0.1", "UA", "2026-08-06T20:00:00");
        assertThat(saved.getSource()).isEqualTo("LOGIN");
    }

    @Test
    @DisplayName("refCode: LIR-<yıl>-<6 hane id>")
    void refCode_format() {
        LoginIssueReport r = new LoginIssueReport();
        r.setId(123L); r.setReportedAt("2026-07-23T10:00:00");
        assertThat(LoginIssueService.refCode(r)).isEqualTo("LIR-2026-000123");
    }

    private static LoginIssueReport report(Long id, String status) {
        LoginIssueReport r = new LoginIssueReport();
        r.setId(id); r.setStatus(status); r.setReportedAt("2026-07-23T10:00:00"); r.setMessage("m");
        return r;
    }

    // ── Kalici silme ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("purge: rapor + RESIMLERI + GIDEN MAIL kopyalari BIRLIKTE silinir")
    void purge_removesReportImagesAndMailCopies() {
        // Baglar duz reportId FK'sidir (@ManyToOne yok): yalniz raporu silmek otekileri OKSUZ
        // birakir — hicbir ekranda gorunmeyen ama sonsuza dek buyuyen satirlar. Mail gunlugu
        // giden mailin TAM govdesini sakliyor, dolayisiyla silinmesi bir gizlilik gereginin de
        // karsiligi.
        LoginIssueReport r = new LoginIssueReport();
        r.setId(7L);
        r.setReportedAt("2026-08-24T10:00:00");
        when(reportRepo.findById(7L)).thenReturn(java.util.Optional.of(r));

        LoginIssueReport out = service.purge(7L);

        assertThat(out).isSameAs(r);
        verify(imageRepo).deleteByReportId(7L);
        verify(mailLogRepo).deleteByReportId(7L);
        verify(reportRepo).delete(r);
    }

    @Test
    @DisplayName("purge: OLMAYAN kayitta hicbir silme yapilmaz ve null doner")
    void purge_missingRowDeletesNothing() {
        when(reportRepo.findById(99L)).thenReturn(java.util.Optional.empty());

        assertThat(service.purge(99L)).isNull();

        verify(imageRepo, never()).deleteByReportId(any());
        verify(mailLogRepo, never()).deleteByReportId(any());
        verify(reportRepo, never()).delete(any());
    }

    // ── Konuşma dizisi + "Bildirimlerim" (2026-09-26) ────────────────────────

    private LoginIssueReport report(long id, String status, String username) {
        LoginIssueReport r = new LoginIssueReport();
        r.setId(id); r.setStatus(status); r.setUsername(username); r.setReportedAt("2026-09-20T10:00:00");
        if ("RESOLVED".equals(status)) { r.setResolutionNote("eski çözüm notu"); r.setResolvedBy("someadmin"); r.setResolvedAt("2026-09-21T10:00:00"); }
        when(reportRepo.findById(id)).thenReturn(java.util.Optional.of(r));
        when(reportRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(commentRepo.save(any())).thenAnswer(inv -> {
            com.sitemonitor.model.IssueReportComment c = inv.getArgument(0);
            if (c.getId() == null) c.setId(100L);
            return c;
        });
        return r;
    }

    private ArgumentCaptor<com.sitemonitor.model.IssueReportComment> commentCaptor(int times) {
        ArgumentCaptor<com.sitemonitor.model.IssueReportComment> cap = ArgumentCaptor.forClass(com.sitemonitor.model.IssueReportComment.class);
        verify(commentRepo, times(times)).save(cap.capture());
        return cap;
    }

    @Test
    @DisplayName("addComment (bildiren, RESOLVED): rapor IN_PROGRESS'e döner, çözüm sahipliği temizlenir, çözüm NOTU kalır, STATUS satırı düşer")
    void reporterCommentOnResolvedReopens() {
        LoginIssueReport r = report(5L, "RESOLVED", "Kullanici.X");

        LoginIssueService.CommentResult res = service.addComment(5L, "kullanici.x", "USER", true, true, "Sorun devam ediyor");

        assertThat(res.reopened()).isTrue();
        assertThat(r.getStatus()).isEqualTo("IN_PROGRESS");
        assertThat(r.getResolvedBy()).isNull();
        assertThat(r.getResolvedAt()).isNull();
        assertThat(r.getResolutionNote()).isEqualTo("eski çözüm notu");   // geçmiş olarak korunur
        assertThat(r.getLastActivityAt()).isNotBlank();
        assertThat(r.getLastAdminActivityAt()).isNull();                   // bildirenin KENDİ yorumu göstergeyi damgalamaz
        assertThat(r.hasUnreadForReporter()).isFalse();
        var rows = commentCaptor(2).getAllValues();
        assertThat(rows).extracting(com.sitemonitor.model.IssueReportComment::getKind).containsExactly("COMMENT", "STATUS");
        assertThat(rows.get(0).isInternal()).isFalse();                    // bildiren iç not YAZAMAZ (bayrak yok sayılır)
        assertThat(rows.get(0).isByReporter()).isTrue();
        assertThat(rows.get(1).getBody()).isEqualTo("IN_PROGRESS");
        assertThat(rows.get(1).isByReporter()).isTrue();
        assertThat(rows.get(1).isInternal()).isFalse();
    }

    @Test
    @DisplayName("addComment (bildiren, OPEN): yeniden açma yok, tek COMMENT satırı")
    void reporterCommentOnOpenDoesNotReopen() {
        LoginIssueReport r = report(6L, "OPEN", "u");
        LoginIssueService.CommentResult res = service.addComment(6L, "u", "USER", false, true, "ek bilgi");
        assertThat(res.reopened()).isFalse();
        assertThat(r.getStatus()).isEqualTo("OPEN");
        assertThat(commentCaptor(1).getValue().getKind()).isEqualTo("COMMENT");
    }

    @Test
    @DisplayName("addComment (yönetici, herkese açık): lastAdminActivityAt damgalanır → bildirende okunmamış; açılınca söner")
    void adminPublicReplyStampsUnread() {
        LoginIssueReport r = report(7L, "OPEN", "u");
        r.setReporterSeenAt("2026-09-20T11:00:00");

        service.addComment(7L, "someadmin", "ADMIN", false, false, "yanıt");

        assertThat(r.getLastAdminActivityAt()).isNotBlank();
        assertThat(r.hasUnreadForReporter()).isTrue();
        service.markSeenByReporter(7L);
        assertThat(r.hasUnreadForReporter()).isFalse();
    }

    @Test
    @DisplayName("addComment (yönetici, İÇ not): bildirenin göstergesine dokunmaz, durum değişmez, satır internal=true")
    void adminInternalNoteDoesNotStampOrReopen() {
        LoginIssueReport r = report(8L, "RESOLVED", "u");

        LoginIssueService.CommentResult res = service.addComment(8L, "someadmin", "ADMIN", true, false, "iç not");

        assertThat(res.comment().isInternal()).isTrue();
        assertThat(res.reopened()).isFalse();
        assertThat(r.getStatus()).isEqualTo("RESOLVED");
        assertThat(r.getLastAdminActivityAt()).isNull();
        assertThat(r.hasUnreadForReporter()).isFalse();
        assertThat(r.getLastActivityAt()).isNotBlank();   // "son etkinlik" yine de ilerler (yönetici listesi)
        commentCaptor(1);
    }

    @Test
    @DisplayName("addComment: boş ve 4001 karakter reddedilir (hiçbir satır yazılmaz); 4000 kabul")
    void commentLengthLimits() {
        report(9L, "OPEN", "u");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.addComment(9L, "u", "USER", false, true, "   "))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.addComment(9L, "u", "USER", false, true, "x".repeat(4001)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(commentRepo, never()).save(any());
        service.addComment(9L, "u", "USER", false, true, "x".repeat(4000));
        commentCaptor(1);
    }

    @Test
    @DisplayName("updateStatus: gerçek geçişte STATUS satırı + lastAdminActivityAt; aynı durumu tekrar kaydetmek satır üretmez")
    void updateStatusWritesTimelineRowOnlyOnRealChange() {
        LoginIssueReport r = report(10L, "OPEN", "u");

        service.updateStatus(10L, "IN_PROGRESS", "not", "someadmin", "ADMIN");

        assertThat(r.getLastAdminActivityAt()).isNotBlank();
        var row = commentCaptor(1).getValue();
        assertThat(row.getKind()).isEqualTo("STATUS");
        assertThat(row.getBody()).isEqualTo("IN_PROGRESS");
        assertThat(row.isByReporter()).isFalse();
        assertThat(row.getAuthorUsername()).isEqualTo("someadmin");

        service.updateStatus(10L, "IN_PROGRESS", "yalnız not güncellendi", "someadmin", "ADMIN");
        verify(commentRepo, times(1)).save(any());   // hâlâ 1
        assertThat(r.getResolutionNote()).isEqualTo("yalnız not güncellendi");
    }

    @Test
    @DisplayName("publicComments iç notları SÜZER; comments (yönetici) hepsini döner")
    void publicCommentsFilterInternalNotes() {
        com.sitemonitor.model.IssueReportComment a = new com.sitemonitor.model.IssueReportComment(); a.setId(1L); a.setInternal(true); a.setBody("gizli");
        com.sitemonitor.model.IssueReportComment b = new com.sitemonitor.model.IssueReportComment(); b.setId(2L); b.setInternal(false); b.setBody("açık");
        when(commentRepo.findByReportIdOrderByIdAsc(3L)).thenReturn(List.of(a, b));
        assertThat(service.publicComments(3L)).containsExactly(b);
        assertThat(service.comments(3L)).containsExactly(a, b);
    }

    @Test
    @DisplayName("getMine / ownedBy: sahiplik büyük/küçük harf duyarsız; başkasının ve boş kullanıcı adının kaydı yok")
    void getMineOwnershipIsCaseInsensitive() {
        LoginIssueReport r = report(11L, "OPEN", "Kullanici.X");
        assertThat(service.getMine(11L, "KULLANICI.x")).isPresent();
        assertThat(service.getMine(11L, "baskasi")).isEmpty();
        assertThat(service.getMine(11L, "")).isEmpty();
        assertThat(LoginIssueService.ownedBy(r, null)).isFalse();
    }

    @Test
    @DisplayName("purge: konuşma dizisi de silinir")
    void purgeDeletesComments() {
        report(12L, "RESOLVED", "u");
        service.purge(12L);
        verify(commentRepo).deleteByReportId(12L);
    }
}
