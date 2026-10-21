package com.sitemonitor.service;

import com.sitemonitor.repository.WeeklyReportRepository;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

/**
 * Mock depolu haftalık rapor testleri için (2026-10-21, çok pod MP-39): durum geçişleri artık koşullu UPDATE'lerle
 * ({@code claim*}, {@code updateContent}, kilit sorguları) yazılıyor; Mockito'nun varsayılan cevabı 0 = "yarışı
 * kaybetti" olurdu. Tek-istek senaryolarında her sahiplenme KAZANIR (1). Gerçek SQL ve yarış davranışı
 * {@code WeeklyReportTransitionRaceTest}'te (H2) sınanır.
 */
final class WeeklyReportClaimStubs {

    private WeeklyReportClaimStubs() { }

    static void allWin(WeeklyReportRepository repo) {
        when(repo.claimSubmit(any(), any(), any(), any(), any(), any())).thenReturn(1);
        when(repo.claimApprove(any(), any(), any())).thenReturn(1);
        when(repo.claimApproveByToken(any(), any(), any(), any())).thenReturn(1);
        when(repo.claimReject(any(), any(), any(), any())).thenReturn(1);
        when(repo.claimRejectByToken(any(), any(), any(), any(), any())).thenReturn(1);
        when(repo.claimResend(any(), anyInt(), any(), any())).thenReturn(1);
        when(repo.claimReopen(any(), any(), any())).thenReturn(1);
        when(repo.markSent(any(), any(), any())).thenReturn(1);
        when(repo.updateContent(any(), anyInt(), any(), any(), any(), any())).thenReturn(1);
        when(repo.setEditingLock(any(), any(), any(), any())).thenReturn(1);
        when(repo.touchEditingLock(any(), any(), any())).thenReturn(1);
        when(repo.releaseEditingLock(any(), any())).thenReturn(1);
    }
}
