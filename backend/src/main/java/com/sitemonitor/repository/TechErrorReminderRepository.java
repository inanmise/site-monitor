package com.sitemonitor.repository;

import com.sitemonitor.model.TechErrorReminder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Teknik hata raporu hatırlatma talepleri (2026-10-17, 12. sürüm). Yazan metotlar kendi işlemlerini açar; iş işlemsiz
 * koşar. İlk talep {@code saveAndFlush} (benzersizlik ihlali = başka pod sahiplendi; kendi işleminde geri alınır —
 * PostgreSQL'de dış işlemi zehirlemez, H2'de de aynı), sonrakiler {@code claim_rev} eşleşmeli UPDATE.
 */
public interface TechErrorReminderRepository extends JpaRepository<TechErrorReminder, Long> {

    List<TechErrorReminder> findByKindAndReportYearAndReportMonthAndChannelIdIn(String kind, Integer year, Integer month,
                                                                                Collection<Long> channelIds);

    Optional<TechErrorReminder> findByChannelIdAndReportYearAndReportMonthAndKind(Long channelId, Integer year, Integer month,
                                                                                  String kind);

    /** Sonraki talep: okunan sürüm ({@code rev}) hâlâ duruyorsa bugüne yazılır; 0 = başka pod önce sahiplendi. */
    @Modifying
    @Transactional
    @Query("UPDATE TechErrorReminder r SET r.lastSentOn = :today, r.refKey = :ref, r.sendCount = r.sendCount + 1, "
            + "r.claimRev = r.claimRev + 1, r.updatedAt = :now WHERE r.id = :id AND r.claimRev = :rev")
    int claim(@Param("id") Long id, @Param("rev") int rev, @Param("today") String today, @Param("ref") String ref,
              @Param("now") String now);

    /**
     * Talebi geri bırakır (hatırlatma hiçbir kanaldan ulaşmadı → ertesi koşu yeniden dener): önceki gün ve istek anahtarı
     * geri yazılır, yalnız bu talebin sürümü ({@code rev}) duruyorsa.
     */
    @Modifying
    @Transactional
    @Query("UPDATE TechErrorReminder r SET r.lastSentOn = :prevDay, r.refKey = :prevRef, r.sendCount = r.sendCount - 1, "
            + "r.claimRev = r.claimRev + 1, r.updatedAt = :now WHERE r.id = :id AND r.claimRev = :rev")
    int release(@Param("id") Long id, @Param("rev") int rev, @Param("prevDay") String prevDay, @Param("prevRef") String prevRef,
                @Param("now") String now);

    /** 6 aydan eski dönemlerin satırları (yıl × 12 + ay kesimi) — iş her koşuda budar. */
    @Modifying
    @Transactional
    @Query("DELETE FROM TechErrorReminder r WHERE (r.reportYear * 12 + r.reportMonth) < :cutoff")
    int deleteOlderThan(@Param("cutoff") int cutoff);

    @Modifying
    @Transactional
    @Query("DELETE FROM TechErrorReminder r WHERE r.channelId = :channelId")
    int deleteByChannel(@Param("channelId") Long channelId);
}
