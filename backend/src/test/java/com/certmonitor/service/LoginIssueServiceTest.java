package com.certmonitor.service;

import com.certmonitor.model.LoginIssueReport;
import com.certmonitor.repository.LoginIssueReportImageRepository;
import com.certmonitor.repository.LoginIssueReportRepository;
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
    LoginIssueService service;

    @BeforeEach
    void setup() {
        reportRepo = mock(LoginIssueReportRepository.class);
        imageRepo = mock(LoginIssueReportImageRepository.class);
        service = new LoginIssueService(reportRepo, imageRepo);
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
    @DisplayName("Yeniden Aç (RESOLVED→OPEN) → çözüm alanları temizlenir")
    void reopen_clearsResolutionFields() {
        LoginIssueReport r = report(5L, "RESOLVED");
        r.setResolvedBy("admin"); r.setResolvedAt("2026-07-23T11:00:00"); r.setResolutionNote("done");
        when(reportRepo.findById(5L)).thenReturn(Optional.of(r));
        when(reportRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        LoginIssueReport out = service.updateStatus(5L, "OPEN", null, "admin2");
        assertThat(out.getStatus()).isEqualTo("OPEN");
        assertThat(out.getResolvedBy()).isNull();
        assertThat(out.getResolvedAt()).isNull();
        assertThat(out.getResolutionNote()).isNull();
    }

    @Test
    @DisplayName("Geçersiz durum → IllegalArgumentException")
    void invalidStatus_throws() {
        assertThatThrownBy(() -> service.updateStatus(5L, "BOGUS", null, "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("counts: eksik durumlar 0 ile doldurulur")
    void counts_fillsMissingWithZero() {
        when(reportRepo.countByStatus(anyString(), isNull()))
                .thenReturn(List.of(new Object[]{"OPEN", 3L}, new Object[]{"RESOLVED", 5L}));
        Map<String, Long> c = service.counts();
        assertThat(c.get("OPEN")).isEqualTo(3L);
        assertThat(c.get("IN_PROGRESS")).isEqualTo(0L);
        assertThat(c.get("RESOLVED")).isEqualTo(5L);
    }

    @Test
    @DisplayName("list(null) — filtre yok — NPE atmaz, findFiltered'a null status geçer")
    void list_nullStatus_noNpe() {
        when(reportRepo.findFiltered(isNull(), any(), any(), any()))
                .thenReturn(org.springframework.data.domain.Page.empty());
        assertThat(service.list(null, 0, 20)).isNotNull();
        assertThat(service.list("BOGUS", 0, 20)).isNotNull();   // geçersiz status da null'a düşer
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
}
