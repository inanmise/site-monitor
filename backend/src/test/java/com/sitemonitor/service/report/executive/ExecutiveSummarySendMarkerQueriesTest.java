package com.sitemonitor.service.report.executive;

import com.sitemonitor.model.ExecutiveSummaryReport;
import com.sitemonitor.model.ExecutiveSummaryTeamReport;
import com.sitemonitor.repository.ExecutiveSummaryReportRepository;
import com.sitemonitor.repository.ExecutiveSummaryTeamReportRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Yönetici özeti "gönderim başladı" işareti (R2a inceleme REP-1, 2026-10-23) — gerçek JPQL (H2, {@code NOT_SUPPORTED}: her
 * yazım gerçekten COMMIT olur): işaret yalnız BU talebin SENDING satırına yazılır, varlık kaydı işareti silmez, yeniden
 * talep işareti sıfırlar.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ExecutiveSummarySendMarkerQueriesTest {

    @Autowired ExecutiveSummaryReportRepository repo;
    @Autowired ExecutiveSummaryTeamReportRepository teamRepo;

    private static final String CLAIM = "2026-10-01T06:00:00";

    @BeforeEach
    void clean() {
        repo.deleteAll();
        teamRepo.deleteAll();
    }

    @Test
    @DisplayName("kurum kaydı: başka talebe işaret yazılmaz; kendi talebine işaret + iz adı + plan; varlık kaydı işareti silmez; yeniden talep sıfırlar")
    void orgMarker() {
        ExecutiveSummaryReport r = new ExecutiveSummaryReport();
        r.setReportYear(2026);
        r.setReportMonth(9);
        r.setStatus(ExecutiveSummaryReport.SENDING);
        r.setAttempts(1);
        r.setClaimedAt(CLAIM);
        Long id = repo.saveAndFlush(r).getId();

        assertThat(repo.markSendStarted(id, "2026-10-01T05:00:00", "Yönetici Özeti", 3, 1)).isZero();
        assertThat(repo.markSendStarted(id, CLAIM, "Yönetici Özeti", 250, 3)).isEqualTo(1);
        ExecutiveSummaryReport back = repo.findById(id).orElseThrow();
        assertThat(back.getSendStartedClaim()).isEqualTo(CLAIM);
        assertThat(back.getSendTraceName()).isEqualTo("Yönetici Özeti");
        assertThat(back.getRecipientCount()).isEqualTo(250);
        assertThat(back.getChunkCount()).isEqualTo(3);

        back.setDetail("varlık kaydı");
        repo.saveAndFlush(back);
        assertThat(repo.findById(id).orElseThrow().getSendStartedClaim()).as("insertable/updatable=false").isEqualTo(CLAIM);

        assertThat(repo.finishIfClaimed(id, CLAIM, ExecutiveSummaryReport.FAILED, 0, 0, "x", null, null, null, CLAIM)).isEqualTo(1);
        assertThat(repo.markSendStarted(id, CLAIM, "Yönetici Özeti", 1, 1)).as("SENDING değil").isZero();
        assertThat(repo.reclaim(id, ExecutiveSummaryReport.FAILED, 1, 2, "2026-10-01T08:00:00", "CATCH_UP", "system")).isEqualTo(1);
        ExecutiveSummaryReport reclaimed = repo.findById(id).orElseThrow();
        assertThat(reclaimed.getSendStartedClaim()).isNull();
        assertThat(reclaimed.getSendTraceName()).isNull();
    }

    @Test
    @DisplayName("takım kaydı: aynı sözleşme")
    void teamMarker() {
        ExecutiveSummaryTeamReport r = new ExecutiveSummaryTeamReport();
        r.setTeamId(5L);
        r.setReportYear(2026);
        r.setReportMonth(9);
        r.setStatus(ExecutiveSummaryTeamReport.SENDING);
        r.setAttempts(1);
        r.setClaimedAt(CLAIM);
        Long id = teamRepo.saveAndFlush(r).getId();
        assertThat(teamRepo.markSendStarted(id, CLAIM, "Yönetici Özeti · Ödeme", 2, 1)).isEqualTo(1);
        assertThat(teamRepo.findById(id).orElseThrow().getSendTraceName()).isEqualTo("Yönetici Özeti · Ödeme");
        assertThat(teamRepo.finishIfClaimed(id, CLAIM, ExecutiveSummaryTeamReport.FAILED, 0, 0, "x", null, null, null, CLAIM)).isEqualTo(1);
        assertThat(teamRepo.reclaim(id, ExecutiveSummaryTeamReport.FAILED, 1, 2, "2026-10-01T08:00:00", "CATCH_UP", "system")).isEqualTo(1);
        assertThat(teamRepo.findById(id).orElseThrow().getSendStartedClaim()).isNull();
    }
}
