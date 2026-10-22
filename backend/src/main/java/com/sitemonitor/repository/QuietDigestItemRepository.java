package com.sitemonitor.repository;

import com.sitemonitor.model.QuietDigestItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

/**
 * Sessiz saat özeti kayıtları (2026-10-01). Ekleme {@code saveAndFlush} + UNIQUE kısıtla (yarışı kaybeden
 * {@code DataIntegrityViolationException} alır); toplu güncellemeler koşullu ve {@code @Transactional}
 * (RepositoryWriteTransactionGuardTest). Hepsi TOPLU — alarm/alıcı başına sorgu yok.
 */
public interface QuietDigestItemRepository extends JpaRepository<QuietDigestItem, Long> {

    /** Dakikalık işin ön kapısı: vakti gelmiş bekleyen kayıt var mı (tek boolean sorgu, kilit almadan). */
    boolean existsByDigestSentAtIsNullAndWindowEndLessThanEqual(String now);

    /** Vakti gelmiş (pencere bitmiş) bekleyen kayıtlar. */
    @Query("SELECT i FROM QuietDigestItem i WHERE i.digestSentAt IS NULL AND i.windowEnd <= :now "
            + "ORDER BY i.teamId ASC, i.deferredAt ASC")
    List<QuietDigestItem> findDue(@Param("now") String now);

    /**
     * Sahiplenme: yalnız HÂLÂ bekleyen kayıtlara belirteç yazar. İki pod (kilit süresi aşımı) aynı kaydı sahiplenemez;
     * sahiplenilenler {@link #findByDigestStatus} ile belirteçten okunur.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE QuietDigestItem i SET i.digestSentAt = :now, i.digestStatus = :token "
            + "WHERE i.id IN :ids AND i.digestSentAt IS NULL")
    int claim(@Param("ids") Collection<Long> ids, @Param("now") String now, @Param("token") String token);

    List<QuietDigestItem> findByDigestStatus(String digestStatus);

    /** Özet sonucunu (SENT / FAILED: … / SKIPPED: …) sahiplenilmiş kayıtlara yazar. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE QuietDigestItem i SET i.digestStatus = :status WHERE i.id IN :ids")
    int markStatus(@Param("ids") Collection<Long> ids, @Param("status") String status);

    /**
     * Sahiplenmenin (belirteç {@code SENDING:<…>}) son durumu — YALNIZ hâlâ o belirteci taşıyan satırlarda (2026-10-23, çok pod
     * R2a · D2, MP-46): yarıda kalan tur ya da ölen pod'un sahiplenmesi kapatılır; o arada sonucu yazılmış satıra dokunulmaz.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE QuietDigestItem i SET i.digestStatus = :status WHERE i.digestStatus = :token")
    int markStatusIfToken(@Param("token") String token, @Param("status") String status);

    /**
     * MP-46: {@code [since, cutoff)} aralığında sahiplenilmiş ve hâlâ {@code SENDING:} belirteci taşıyan (yarıda kalmış) kayıtlar.
     * Alt sınır aralığı {@code idx_qdi_pending}'in ({@code digest_sent_at, window_end}) kısa bir dilimine indirir — geçmişin
     * tamamı taranmaz.
     */
    @Query("SELECT i FROM QuietDigestItem i WHERE i.digestSentAt >= :since AND i.digestSentAt < :cutoff "
            + "AND i.digestStatus LIKE 'SENDING:%' ORDER BY i.digestStatus ASC, i.id ASC")
    List<QuietDigestItem> findStaleClaims(@Param("since") String since, @Param("cutoff") String cutoff);

    /** Dakikalık ön kapının ikinci sorgusu (inceleme J-1): yarıda kalmış sahiplenme var mı — aynı kısa aralık, kilit almadan. */
    @Query("SELECT COUNT(i) FROM QuietDigestItem i WHERE i.digestSentAt >= :since AND i.digestSentAt < :cutoff "
            + "AND i.digestStatus LIKE 'SENDING:%'")
    long countStaleClaims(@Param("since") String since, @Param("cutoff") String cutoff);

    /** Pencere içinde ertelenmeyen bildirim → bekleyen kayıt özette tekrar edilmez. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE QuietDigestItem i SET i.supersededAt = :now WHERE i.alertEventId = :eventId AND i.teamId IN :teams "
            + "AND i.digestSentAt IS NULL AND i.supersededAt IS NULL")
    int supersede(@Param("eventId") Long alertEventId, @Param("teams") Collection<Long> teamIds, @Param("now") String now);

    /** Bir alarmın bekleyen (özeti gitmemiş, geçersizleşmemiş) kayıtları — çözüm postasının katlama kararı. */
    @Query("SELECT i FROM QuietDigestItem i WHERE i.alertEventId = :eventId AND i.digestSentAt IS NULL "
            + "AND i.supersededAt IS NULL")
    List<QuietDigestItem> findPendingByAlertEventId(@Param("eventId") Long alertEventId);

    /** Eskalasyon adımı işinin TOPLU okuması: aday alarmların bekleyen kayıtları. */
    @Query("SELECT i FROM QuietDigestItem i WHERE i.alertEventId IN :ids AND i.digestSentAt IS NULL "
            + "AND i.supersededAt IS NULL")
    List<QuietDigestItem> findPendingByAlertEventIdIn(@Param("ids") Collection<Long> alertEventIds);
}
