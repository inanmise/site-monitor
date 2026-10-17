package com.sitemonitor.repository;

import com.sitemonitor.model.TechErrorReport;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * Kanal teknik hata raporu DEĞER dışa aktarımı (E23, 2026-10-18 — BI için salt okunur). Ayrı depo: yalnız okur, tek
 * sorgu. İzdüşüm kişi verisi taşımaz — alıcı listesi, gönderen / onaylayan / düzenleyen kimlikleri ve onay notu SEÇİLMEZ;
 * yalnız durum hesabı ({@code TechErrorReportService.reportStatus}) ve onay durumu için gereken sütunlar + raporun KENDİ
 * kopyalanmış şablonu ve değerleri.
 */
public interface TechErrorReportExportRepository extends Repository<TechErrorReport, Long> {

    /** Dışa aktarım satırı (metin sütunları dahil; kişi sütunları yok). */
    interface ExportRow {
        Long getId();
        Long getChannelId();
        Long getTeamId();
        Integer getReportYear();
        Integer getReportMonth();
        String getTemplateJson();
        String getValuesJson();
        Instant getContentChangedAt();
        Long getContentRev();
        Long getSentRev();
        String getSendState();
        Instant getSendClaimedAt();
        Instant getLastSentAt();
        String getApprovalState();
        Long getApprovalRev();
    }

    /**
     * Kanalların raporları, dönem aralığında; {@code sentOnly} = yalnız en az bir kez gönderilmiş ({@code last_sent_at}
     * dolu). Boş-olabilir parametre yok (PostgreSQL'de tür çıkarımı sorunu olmasın): "tüm yıllar / tüm aylar" çağıranın
     * verdiği geniş aralıktır. {@code limit}: çağıran tavanın bir fazlasını ister — tavan aşıldı mı tek sorguda anlaşılır.
     */
    @Query("SELECT r.id AS id, r.channelId AS channelId, r.teamId AS teamId, r.reportYear AS reportYear, "
            + "r.reportMonth AS reportMonth, r.templateJson AS templateJson, r.valuesJson AS valuesJson, "
            + "r.contentChangedAt AS contentChangedAt, r.contentRev AS contentRev, r.sentRev AS sentRev, "
            + "r.sendState AS sendState, r.sendClaimedAt AS sendClaimedAt, r.lastSentAt AS lastSentAt, "
            + "r.approvalState AS approvalState, r.approvalRev AS approvalRev "
            + "FROM TechErrorReport r WHERE r.channelId IN :channelIds "
            + "AND r.reportYear BETWEEN :yearFrom AND :yearTo AND r.reportMonth BETWEEN :monthFrom AND :monthTo "
            + "AND (:sentOnly = false OR r.lastSentAt IS NOT NULL) "
            + "ORDER BY r.reportYear ASC, r.reportMonth ASC, r.id ASC")
    List<ExportRow> findForExport(@Param("channelIds") Collection<Long> channelIds,
                                  @Param("yearFrom") int yearFrom, @Param("yearTo") int yearTo,
                                  @Param("monthFrom") int monthFrom, @Param("monthTo") int monthTo,
                                  @Param("sentOnly") boolean sentOnly, Limit limit);
}
