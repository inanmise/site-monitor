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
    LoginIssueService service;

    @BeforeEach
    void setup() {
        reportRepo = mock(LoginIssueReportRepository.class);
        imageRepo = mock(LoginIssueReportImageRepository.class);
        mailLogRepo = mock(com.sitemonitor.repository.LoginIssueMailLogRepository.class);
        service = new LoginIssueService(reportRepo, imageRepo, mailLogRepo);
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
        when(reportRepo.findFiltered(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(org.springframework.data.domain.Page.empty());
        assertThat(service.list(null, null, null, null, null, null, 0, 20)).isNotNull();
        // geçersiz status + geçersiz source/category + boş q → hepsi null'a düşer
        assertThat(service.list("BOGUS", "HACK", "WRONG", "  ", null, null, 0, 20)).isNotNull();
        service.list("OPEN", "USER_REPORT", "BLOCKER", "  Locked ", "2026-07-01T00:00:00", "2026-07-31T23:59:59", 1, 50);
        // status/source/category geçer; q → "%locked%"; since/until iletilir
        verify(reportRepo).findFiltered(eq("OPEN"), eq("USER_REPORT"), eq("BLOCKER"), eq("%locked%"),
                eq("2026-07-01T00:00:00"), eq("2026-07-31T23:59:59"), any());
        // İlk iki çağrı tüm filtreleri null'a düşürür → NPE atmadan çalıştı (isNotNull).
        verify(reportRepo, times(2)).findFiltered(isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), any());
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
}
