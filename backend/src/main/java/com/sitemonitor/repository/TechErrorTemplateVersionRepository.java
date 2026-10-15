package com.sitemonitor.repository;

import com.sitemonitor.model.TechErrorTemplateVersion;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Kanal şablonu sürüm geçmişi (2026-10-14). */
public interface TechErrorTemplateVersionRepository extends JpaRepository<TechErrorTemplateVersion, Long> {

    /** Liste satırı — şablon gövdesi YOK (yüz sürümlük liste megabaytlarca JSON taşımasın). */
    interface Meta {
        int getVersion();
        Instant getSavedAt();
        String getSavedByName();
        int getSectionCount();
        int getFieldCount();
        int getSlotCount();
    }

    List<Meta> findByChannelIdOrderByVersionDesc(Long channelId, Pageable page);

    Optional<TechErrorTemplateVersion> findByChannelIdAndVersion(Long channelId, int version);

    boolean existsByChannelIdAndVersion(Long channelId, int version);

    long countByChannelId(Long channelId);

    /** Budama eşiği: en yeni {@code page.offset} sürümden sonraki ilk sürüm numarası. */
    @Query("SELECT v.version FROM TechErrorTemplateVersion v WHERE v.channelId = :channelId ORDER BY v.version DESC")
    List<Integer> versionsNewestFirst(@Param("channelId") Long channelId, Pageable page);

    @Modifying
    @Transactional
    @Query("DELETE FROM TechErrorTemplateVersion v WHERE v.channelId = :channelId AND v.version <= :maxVersion")
    int deleteUpTo(@Param("channelId") Long channelId, @Param("maxVersion") int maxVersion);

    @Modifying
    @Transactional
    @Query("DELETE FROM TechErrorTemplateVersion v WHERE v.channelId = :channelId")
    int deleteAllForChannel(@Param("channelId") Long channelId);
}
