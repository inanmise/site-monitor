package com.sitemonitor.repository;

import com.sitemonitor.model.TechErrorChannelNote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/** Kanal notları (2026-11-20): kanal başına tek satır. Özet sorgusu metni SEÇMEZ. */
public interface TechErrorChannelNoteRepository extends JpaRepository<TechErrorChannelNote, Long> {

    Optional<TechErrorChannelNote> findByChannelId(Long channelId);

    /**
     * Hafif özet (rapor ekranı / rapor listesi {@code channel_notes} bloğu): TEK sorgu, metin ve bayt seçilmez. Satır:
     * [sürüm, güncellenme, güncelleyenin adı, metin uzunluğu, bağlı görsel sayısı]. Not yoksa boş liste. Yalnız boşluktan
     * oluşan gövde boş metin olarak saklandığı için "boş" = uzunluk 0.
     */
    @Query("SELECT n.version, n.updatedAt, n.updatedByName, LENGTH(n.bodyMd), "
            + "(SELECT COUNT(i) FROM TechErrorChannelNoteImage i WHERE i.channelId = n.channelId AND i.linked = TRUE) "
            + "FROM TechErrorChannelNote n WHERE n.channelId = :channelId")
    List<Object[]> summaryRows(@Param("channelId") Long channelId);

    /** Kanal silinirken (aynı işlemde) — toplu, varlık yüklemez. */
    @Modifying
    @Transactional
    @Query("DELETE FROM TechErrorChannelNote n WHERE n.channelId = :channelId")
    int deleteAllForChannel(@Param("channelId") Long channelId);
}
