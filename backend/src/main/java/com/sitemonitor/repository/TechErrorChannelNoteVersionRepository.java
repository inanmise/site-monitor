package com.sitemonitor.repository;

import com.sitemonitor.model.TechErrorChannelNoteVersion;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Kanal notu sürüm geçmişi (2026-11-20): kanal başına en yeni 20 sürüm. Liste satırı metni taşımaz. */
public interface TechErrorChannelNoteVersionRepository extends JpaRepository<TechErrorChannelNoteVersion, Long> {

    /** Liste satırı — metin YOK. */
    interface Meta {
        long getVersion();
        Instant getSavedAt();
        String getSavedByName();
        int getCharCount();
        int getImageCount();
    }

    List<Meta> findByChannelIdOrderByVersionDesc(Long channelId, Pageable page);

    Optional<TechErrorChannelNoteVersion> findByChannelIdAndVersion(Long channelId, long version);

    long countByChannelId(Long channelId);

    /** Budama eşiği: en yeni {@code page.offset} sürümden sonraki ilk sürüm numarası. */
    @Query("SELECT v.version FROM TechErrorChannelNoteVersion v WHERE v.channelId = :channelId ORDER BY v.version DESC")
    List<Long> versionsNewestFirst(@Param("channelId") Long channelId, Pageable page);

    @Modifying
    @Transactional
    @Query("DELETE FROM TechErrorChannelNoteVersion v WHERE v.channelId = :channelId AND v.version <= :maxVersion")
    int deleteUpTo(@Param("channelId") Long channelId, @Param("maxVersion") long maxVersion);

    @Modifying
    @Transactional
    @Query("DELETE FROM TechErrorChannelNoteVersion v WHERE v.channelId = :channelId")
    int deleteAllForChannel(@Param("channelId") Long channelId);
}
