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
 * Kanal teknik hata raporu DEĞER dışa aktarımı (E23, 2026-10-18 — BI için salt okunur). Ayrı depo: yalnız okur, iki
 * sorgu (sayım + yükleme). İzdüşüm kişi verisi taşımaz — alıcı listesi, gönderen / onaylayan / düzenleyen kimlikleri ve onay notu SEÇİLMEZ;
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
     * Dışa aktarılacak rapor SAYISI — yükleme sorgusuyla AYNI süzgeç (kanallar, dönem, {@code sentOnly} = yalnız en az bir kez
     * gönderilmiş). Tavan aşıldıysa JSON metinleri hiç okunmadan reddedilir. Dönem = {@code yıl × 12 + ay} (tek sayı: yıl
     * sınırını aşan "bir ay geri" aralığı da düz bir BETWEEN'dir). Boş-olabilir parametre yok (PostgreSQL'de tür çıkarımı
     * sorunu olmasın): "tüm yıllar" çağıranın verdiği geniş aralıktır.
     */
    @Query("SELECT COUNT(r) FROM TechErrorReport r WHERE r.channelId IN :channelIds "
            + "AND (r.reportYear * 12 + r.reportMonth) BETWEEN :periodFrom AND :periodTo "
            + "AND (:sentOnly = false OR r.lastSentAt IS NOT NULL)")
    long countForExport(@Param("channelIds") Collection<Long> channelIds,
                        @Param("periodFrom") int periodFrom, @Param("periodTo") int periodTo,
                        @Param("sentOnly") boolean sentOnly);

    /**
     * Dışa aktarılacak raporlar + her birinin aynı kanaldaki BİR ÖNCEKİ takvim ayının raporu (durumu ne olursa olsun —
     * PDF'in canlı hesabı gibi: {@code TechErrorReportService.pdfInput} → {@code previousReport}). Tek sorgu: dönem aralığı
     * bir ay geriden ({@code supportFrom} = {@code periodFrom − 1}) başlar; bir satır ya kendisi dışa aktarılır (aralıkta +
     * durum süzgeci) ya da bir sonraki ayı dışa aktarılan bir raporun önceki ayıdır ({@code EXISTS}). Böylece yüklenen satır
     * en çok dışa aktarılanın iki katıdır (taslaklar süzgeçteyken bile sınırlı). {@code limit}: çağıranın güvenlik tavanı.
     */
    @Query("SELECT r.id AS id, r.channelId AS channelId, r.teamId AS teamId, r.reportYear AS reportYear, "
            + "r.reportMonth AS reportMonth, r.templateJson AS templateJson, r.valuesJson AS valuesJson, "
            + "r.contentChangedAt AS contentChangedAt, r.contentRev AS contentRev, r.sentRev AS sentRev, "
            + "r.sendState AS sendState, r.sendClaimedAt AS sendClaimedAt, r.lastSentAt AS lastSentAt, "
            + "r.approvalState AS approvalState, r.approvalRev AS approvalRev "
            + "FROM TechErrorReport r WHERE r.channelId IN :channelIds "
            + "AND (r.reportYear * 12 + r.reportMonth) BETWEEN :supportFrom AND :periodTo "
            + "AND (((r.reportYear * 12 + r.reportMonth) >= :periodFrom AND (:sentOnly = false OR r.lastSentAt IS NOT NULL)) "
            + "OR EXISTS (SELECT n.id FROM TechErrorReport n WHERE n.channelId = r.channelId "
            + "AND (n.reportYear * 12 + n.reportMonth) = (r.reportYear * 12 + r.reportMonth + 1) "
            + "AND (n.reportYear * 12 + n.reportMonth) <= :periodTo "
            + "AND (:sentOnly = false OR n.lastSentAt IS NOT NULL))) "
            + "ORDER BY r.reportYear ASC, r.reportMonth ASC, r.id ASC")
    List<ExportRow> findForExport(@Param("channelIds") Collection<Long> channelIds,
                                  @Param("supportFrom") int supportFrom, @Param("periodFrom") int periodFrom,
                                  @Param("periodTo") int periodTo, @Param("sentOnly") boolean sentOnly, Limit limit);
}
