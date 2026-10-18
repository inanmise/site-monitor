package com.sitemonitor.repository;

import com.sitemonitor.model.TechErrorReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Kanal teknik hata raporları (2026-10-13). Liste uçları {@link Row} izdüşümünü okur — şablon ve değer metni (TEXT)
 * yalnız tek rapor açılınca yüklenir.
 *
 * <p>Gönderim talebi / sonucu ve "içerik değişti" damgası TOPLU JPQL ile yazılır: {@code @Version} ARTMAZ, açık
 * düzenleyici bir görsel yüklendi ya da rapor gönderildi diye çakışmaya düşmez.
 */
public interface TechErrorReportRepository extends JpaRepository<TechErrorReport, Long> {

    /** Liste satırı — büyük metin kolonları olmadan. */
    interface Row {
        Long getId();
        Long getChannelId();
        Long getTeamId();
        Integer getReportYear();
        Integer getReportMonth();
        String getTitlePattern();
        String getTemplateLang();
        Instant getCreatedAt();
        Instant getUpdatedAt();
        Long getUpdatedById();
        Instant getContentChangedAt();
        Long getContentRev();
        Long getSentRev();
        String getSendState();
        Instant getSendClaimedAt();
        Instant getLastSentAt();
        Long getLastSentById();
        String getLastSentTo();
        Instant getLastAttemptAt();
        String getLastSendStatus();
        int getSendCount();
        String getApprovalState();
        Long getApprovalRev();
        Instant getApprovalRequestedAt();
        Long getApprovalRequestedById();
        Instant getApprovalDecidedAt();
        Long getApprovalDecidedById();
        String getApprovalNote();
    }

    Optional<TechErrorReport> findByChannelIdAndReportYearAndReportMonth(Long channelId, Integer year, Integer month);

    boolean existsByChannelIdAndReportYearAndReportMonth(Long channelId, Integer year, Integer month);

    List<Row> findByChannelIdOrderByReportYearDescReportMonthDesc(Long channelId);

    List<Row> findByChannelIdInAndReportYearAndReportMonth(Collection<Long> channelIds, Integer year, Integer month);

    /** Onay hatırlatması adayları (12. sürüm, C10): bekleyen ve en geç {@code before}'da istenmiş onaylar (eskime çağıranda). */
    List<Row> findByApprovalStateAndApprovalRequestedAtLessThanEqual(String approvalState, Instant before);

    @Query("SELECT r.channelId, COUNT(r) FROM TechErrorReport r WHERE r.channelId IN :ids GROUP BY r.channelId")
    List<Object[]> countByChannel(@Param("ids") Collection<Long> channelIds);

    /**
     * Kanallar arası analiz satırı (2026-10-18, R22): raporun KENDİ kopyalanmış şablonu + değerleri + gönderim durumu. Liste
     * satırının ({@link Row}) aksine metin kolonlarını taşır — yalnız analiz penceresindeki (ve hesaplanan değişim oranları
     * için ondan bir önceki aydaki) raporlar okunur.
     */
    interface AnalyticsRow {
        Long getId();
        Long getChannelId();
        Integer getReportYear();
        Integer getReportMonth();
        String getTemplateJson();
        String getValuesJson();
        Instant getLastSentAt();
        String getSendState();
    }

    /**
     * Analiz penceresindeki raporlar, kimlik sırasıyla DİLİM DİLİM (anahtar kümesi: {@code id > after}, dilim boyu
     * {@code page}; sayım sorgusu yok) — kanal sayısından bağımsız. Dönem {@code yıl × 12 + ay} olarak {@code [from, to]}
     * aralığında (iki uç dahil). Bellekte aynı anda en çok bir dilimin metin kolonları durur (inceleme 2026-10-18).
     */
    @Query("SELECT r.id AS id, r.channelId AS channelId, r.reportYear AS reportYear, r.reportMonth AS reportMonth, "
            + "r.templateJson AS templateJson, r.valuesJson AS valuesJson, r.lastSentAt AS lastSentAt, r.sendState AS sendState "
            + "FROM TechErrorReport r WHERE r.channelId IN :ids "
            + "AND (r.reportYear * 12 + r.reportMonth) >= :from AND (r.reportYear * 12 + r.reportMonth) <= :to "
            + "AND r.id > :after ORDER BY r.id")
    List<AnalyticsRow> findAnalyticsRows(@Param("ids") Collection<Long> channelIds, @Param("from") int fromPeriod,
                                         @Param("to") int toPeriod, @Param("after") long afterId,
                                         org.springframework.data.domain.Pageable page);

    @Query("SELECT r.id FROM TechErrorReport r WHERE r.channelId = :channelId")
    List<Long> idsForChannel(@Param("channelId") Long channelId);

    /** İçerik değişti: damga + sayaç +1 (atomik) — @Version artmaz. Her içerik yazımı (değer, görsel, şablon) çağırır. */
    @Modifying
    @Transactional
    @Query("UPDATE TechErrorReport r SET r.contentChangedAt = :at, r.contentRev = COALESCE(r.contentRev, 0) + 1 WHERE r.id = :id")
    int touchContent(@Param("id") Long id, @Param("at") Instant at);

    /** Gönderilmekte olan rapor içeren kanal var mı (taze talep)? Kanal silme bu sırada reddedilir. */
    @Query("SELECT COUNT(r) FROM TechErrorReport r WHERE r.channelId = :channelId AND r.sendState = 'SENDING' "
            + "AND r.sendClaimedAt IS NOT NULL AND r.sendClaimedAt >= :stale")
    long countSendingForChannel(@Param("channelId") Long channelId, @Param("stale") Instant stale);

    /**
     * Gönderim TALEBİ (at-most-once): satır başka bir gönderimde değilse ya da o talep {@code stale}'den eskiyse
     * SENDING yapılır. 0 satır = başka bir sekme / pod şu an gönderiyor → çağıran 409 döner.
     */
    @Modifying
    @Transactional
    @Query("UPDATE TechErrorReport r SET r.sendState = 'SENDING', r.sendClaimedAt = :now WHERE r.id = :id "
            + "AND (r.sendState IS NULL OR r.sendState <> 'SENDING' OR r.sendClaimedAt IS NULL OR r.sendClaimedAt < :stale)")
    int claimSend(@Param("id") Long id, @Param("now") Instant now, @Param("stale") Instant stale);

    /**
     * Başarılı gönderim SONUCU — yalnız bu talebin satırına ({@code claimedAt} eşleşirse); sayaç + son gönderim.
     * {@code lastSentAt} = talep anı (gösterim); {@code sentRev} = PDF'in okunduğu andaki içerik sayacı — "gönderimden
     * sonra değişti" bu sayaçla karar verir. Posta servisinin kuyruğa aldığı (QUEUED) gönderim burada YAZILMAZ: teslim
     * edilip edilmediği bilinmiyor (inceleme 2026-10-13, 2. tur) — {@link #finishNotSent}.
     */
    @Modifying
    @Transactional
    @Query("UPDATE TechErrorReport r SET r.sendState = 'SENT', r.lastAttemptAt = :at, r.lastSendStatus = :status, "
            + "r.lastSentAt = :claimedAt, r.lastSentById = :by, r.lastSentTo = :to, r.sendCount = r.sendCount + 1, "
            + "r.sentRev = :rev WHERE r.id = :id AND r.sendState = 'SENDING' AND r.sendClaimedAt = :claimedAt")
    int finishSent(@Param("id") Long id, @Param("claimedAt") Instant claimedAt, @Param("status") String status,
                   @Param("at") Instant at, @Param("by") Long by, @Param("to") String to, @Param("rev") Long rev);

    /**
     * Hiçbir şey denenmeden (ör. PDF sırası dolu) talebi bırakır. Önceki durum satırın KENDİ son sonucundan türetilir
     * (çağıranın talepten önceki okuması bayat olabilir — arada başka bir pod gönderimi bitirmiş olabilir).
     */
    @Modifying
    @Transactional
    @Query("UPDATE TechErrorReport r SET r.sendState = CASE WHEN r.lastSendStatus IS NULL THEN NULL "
            + "WHEN r.lastSendStatus = 'SENT' THEN 'SENT' WHEN r.lastSendStatus LIKE 'QUEUED_RETRY%' THEN 'QUEUED' "
            + "WHEN r.lastSendStatus LIKE 'SKIPPED%' THEN 'SKIPPED' ELSE 'FAILED' END, r.sendClaimedAt = :previous "
            + "WHERE r.id = :id AND r.sendState = 'SENDING' AND r.sendClaimedAt = :claimedAt")
    int releaseClaim(@Param("id") Long id, @Param("claimedAt") Instant claimedAt, @Param("previous") Instant previous);

    /**
     * Koşullu silme (inceleme 2026-10-13): okunan satır üzerinden karar verip silmek, arada biten bir gönderimi göremezdi.
     * {@code onlyUnsent}: yalnız hiç gönderilmemiş taslak (üye). Her iki kipte de taze bir gönderim talebi varken silinmez.
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM TechErrorReport r WHERE r.id = :id AND (:onlyUnsent = false OR (r.lastSentAt IS NULL "
            + "AND (r.sendState IS NULL OR r.sendState <> 'QUEUED'))) "
            + "AND (r.sendState IS NULL OR r.sendState <> 'SENDING' OR r.sendClaimedAt IS NULL OR r.sendClaimedAt < :stale)")
    int deleteIfAllowed(@Param("id") Long id, @Param("onlyUnsent") boolean onlyUnsent, @Param("stale") Instant stale);

    /**
     * Kuyruğa alınmış gönderim (teslim bilinmiyor): son gönderim alanlarına DOKUNMAZ; teslim edilirse yazılacak
     * değerler ve günlük satırı bekleyen alanlara yazılır.
     */
    @Modifying
    @Transactional
    @Query("UPDATE TechErrorReport r SET r.sendState = 'QUEUED', r.lastAttemptAt = :at, r.lastSendStatus = :status, "
            + "r.sendLogId = :logId, r.queuedTo = :to, r.queuedById = :by, r.queuedRev = :rev "
            + "WHERE r.id = :id AND r.sendState = 'SENDING' AND r.sendClaimedAt = :claimedAt")
    int finishQueued(@Param("id") Long id, @Param("claimedAt") Instant claimedAt, @Param("status") String status,
                     @Param("at") Instant at, @Param("logId") Long logId, @Param("by") Long by, @Param("to") String to,
                     @Param("rev") Long rev);

    /** Kuyruktaki gönderim teslim edildi (günlük satırı SENT): son gönderim bekleyen alanlardan yazılır. */
    @Modifying
    @Transactional
    @Query("UPDATE TechErrorReport r SET r.sendState = 'SENT', r.lastSendStatus = 'SENT', r.lastSentAt = r.sendClaimedAt, "
            + "r.lastSentTo = r.queuedTo, r.lastSentById = r.queuedById, r.sentRev = r.queuedRev, r.sendCount = r.sendCount + 1 "
            + "WHERE r.id = :id AND r.sendState = 'QUEUED' AND r.sendLogId = :logId")
    int confirmQueued(@Param("id") Long id, @Param("logId") Long logId);

    /** Kuyruktaki gönderim sonunda başarısız (günlük satırı FAILED): son gönderim alanlarına dokunmaz. */
    @Modifying
    @Transactional
    @Query("UPDATE TechErrorReport r SET r.sendState = 'FAILED', r.lastSendStatus = :status "
            + "WHERE r.id = :id AND r.sendState = 'QUEUED' AND r.sendLogId = :logId")
    int failQueued(@Param("id") Long id, @Param("logId") Long logId, @Param("status") String status);

    /** Kanalların sonuçlanmamış kuyruk gönderimleri: [rapor id, günlük satırı id]. */
    @Query("SELECT r.id, r.sendLogId FROM TechErrorReport r WHERE r.channelId IN :ids AND r.sendState = 'QUEUED' "
            + "AND r.sendLogId IS NOT NULL")
    List<Object[]> queuedForChannels(@Param("ids") Collection<Long> channelIds);

    /**
     * Kanalın raporlarını siler — gönderim talebi taze olan rapor HARİÇ (kontrol-sonra-sil yarışı olmasın: karar
     * silme anında; eksik silinen varsa çağıran işlemi geri alır).
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM TechErrorReport r WHERE r.channelId = :channelId AND (r.sendState IS NULL OR r.sendState <> 'SENDING' "
            + "OR r.sendClaimedAt IS NULL OR r.sendClaimedAt < :stale)")
    int deleteAllForChannelUnlessSending(@Param("channelId") Long channelId, @Param("stale") Instant stale);

    /** Başarısız / atlanmış gönderim SONUCU — son gönderim alanlarına dokunmaz. */
    @Modifying
    @Transactional
    @Query("UPDATE TechErrorReport r SET r.sendState = :state, r.lastAttemptAt = :at, r.lastSendStatus = :status "
            + "WHERE r.id = :id AND r.sendState = 'SENDING' AND r.sendClaimedAt = :claimedAt")
    int finishNotSent(@Param("id") Long id, @Param("claimedAt") Instant claimedAt, @Param("state") String state,
                      @Param("status") String status, @Param("at") Instant at);

    @Modifying
    @Transactional
    @Query("DELETE FROM TechErrorReport r WHERE r.channelId = :channelId")
    int deleteAllForChannel(@Param("channelId") Long channelId);

    // ── PO onayı (2026-10-17, S6) ─────────────────────────────────────────────────────────────────

    /**
     * Onay İSTEĞİ: raporun içeriği okunduğu sayaçta ({@code rev}) duruyorsa PENDING yapılır; aynı içerik için zaten bekleyen
     * bir istek varsa 0 (çağıran 409) — ancak istek {@code resendBefore}'dan eskiyse yeniden istenebilir (hatırlatma; yarıda
     * kalmış bir istek — pod öldü, belirteç / posta yazılamadı — böyle kurtarılır). İçerik arada değiştiyse 0 — çağıran taze
     * okuyup yeniden dener. {@code content_rev} sayaçtan önceki satırlarda NULL olabilir: COALESCE (inceleme 2026-10-17).
     */
    @Modifying
    @Transactional
    @Query("UPDATE TechErrorReport r SET r.approvalState = 'PENDING', r.approvalRev = :rev, r.approvalRequestedAt = :at, "
            + "r.approvalRequestedById = :by, r.approvalDecidedAt = NULL, r.approvalDecidedById = NULL, r.approvalNote = NULL "
            + "WHERE r.id = :id AND COALESCE(r.contentRev, 0) = :rev "
            + "AND (r.approvalState IS NULL OR r.approvalState <> 'PENDING' OR r.approvalRev IS NULL OR r.approvalRev <> :rev "
            + "OR r.approvalRequestedAt IS NULL OR r.approvalRequestedAt < :resendBefore)")
    int requestApproval(@Param("id") Long id, @Param("rev") Long rev, @Param("at") Instant at, @Param("by") Long by,
                        @Param("resendBefore") Instant resendBefore);

    /**
     * ONAY: yalnız bekleyen istek VE içerik hâlâ istekteki sayaçta ise (arada değişen içerik onaylanmaz — eskimiş istek 0
     * döner, çağıran 409 {@code APPROVAL_STALE}).
     */
    @Modifying
    @Transactional
    @Query("UPDATE TechErrorReport r SET r.approvalState = 'APPROVED', r.approvalDecidedAt = :at, r.approvalDecidedById = :by, "
            + "r.approvalNote = :note WHERE r.id = :id AND r.approvalState = 'PENDING' AND r.approvalRev = :rev AND COALESCE(r.contentRev, 0) = :rev")
    int approve(@Param("id") Long id, @Param("rev") Long rev, @Param("at") Instant at, @Param("by") Long by, @Param("note") String note);

    /** RED: bekleyen istek (içerik değişmiş olsa da) reddedilir; not zorunlu, rapor taslağa döner. */
    @Modifying
    @Transactional
    @Query("UPDATE TechErrorReport r SET r.approvalState = 'REJECTED', r.approvalDecidedAt = :at, r.approvalDecidedById = :by, "
            + "r.approvalNote = :note WHERE r.id = :id AND r.approvalState = 'PENDING' AND r.approvalRev = :rev")
    int reject(@Param("id") Long id, @Param("rev") Long rev, @Param("at") Instant at, @Param("by") Long by, @Param("note") String note);
}
