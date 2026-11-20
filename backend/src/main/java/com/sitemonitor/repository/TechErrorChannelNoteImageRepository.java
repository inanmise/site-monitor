package com.sitemonitor.repository;

import com.sitemonitor.model.TechErrorChannelNoteImage;
import org.springframework.data.domain.Pageable;
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
 * Kanal notu görselleri (2026-11-20). Bayt YALNIZ tek görsel sunulurken okunur ({@code findById}); liste, sayım, bağ
 * bayrağı ve silme yolları baytsız izdüşüm / toplu cümle kullanır (satır başına 5 MB'a kadar BYTEA).
 */
public interface TechErrorChannelNoteImageRepository extends JpaRepository<TechErrorChannelNoteImage, Long> {

    /** Baytsız satır. */
    interface Meta {
        Long getId();
        Long getChannelId();
        String getContentType();
        String getFileName();
        int getWidth();
        int getHeight();
        long getSizeBytes();
        Instant getUploadedAt();
        Boolean getLinked();
    }

    /** Kanalın görselleri, en yeni önce; {@code page} üst sınırı verir (liste sınırsız büyümesin). */
    List<Meta> findByChannelIdOrderByIdDesc(Long channelId, Pageable page);

    /** Tek görselin baytsız satırı — kapsam denetimi bayt yüklenmeden yapılır. */
    Optional<Meta> findMetaById(Long id);

    /** Bağ bayrağı hesabı: [kimlik, bağlı mı] — bayt yok. */
    @Query("SELECT i.id, i.linked FROM TechErrorChannelNoteImage i WHERE i.channelId = :channelId")
    List<Object[]> linkRows(@Param("channelId") Long channelId);

    /**
     * Sınıra SAYILAN satırlar: bağlı olanlar + {@code since}'ten sonra yüklenenler (kaydedilmemiş taslaktaki yüklemeler).
     * Satır: [adet, bayt].
     */
    @Query("SELECT COUNT(i), COALESCE(SUM(i.sizeBytes), 0) FROM TechErrorChannelNoteImage i "
            + "WHERE i.channelId = :channelId AND (i.linked = TRUE OR i.uploadedAt > :since)")
    List<Object[]> countedFor(@Param("channelId") Long channelId, @Param("since") Instant since);

    /** Kanalın BÜTÜN satırlarının baytı (bağsız ve eski olanlar dahil) — üst emniyet sınırı için. */
    @Query("SELECT COALESCE(SUM(i.sizeBytes), 0) FROM TechErrorChannelNoteImage i WHERE i.channelId = :channelId")
    long totalBytes(@Param("channelId") Long channelId);

    /**
     * Bağ bayrağını YALNIZ değişen satırlarda çevirir ve o satırların {@code link_changed_at} damgasını vurur (bayrağı
     * zaten doğru olan satıra dokunulmaz — saklama süresi yeniden başlamaz). {@code ids} boş olmamalı (çağıran ayırır).
     */
    @Modifying
    @Transactional
    @Query("UPDATE TechErrorChannelNoteImage i SET i.linked = :linked, i.linkChangedAt = :at "
            + "WHERE i.channelId = :channelId AND i.linked <> :linked AND i.id IN :ids")
    int setLinked(@Param("channelId") Long channelId, @Param("ids") Collection<Long> ids, @Param("linked") boolean linked,
                  @Param("at") Instant at);

    /** Tek görseli siler — yalnız bu kanalınsa (başka kanalın kimliği 0 satır). Toplu cümle: bayt yüklenmez. */
    @Modifying
    @Transactional
    @Query("DELETE FROM TechErrorChannelNoteImage i WHERE i.id = :id AND i.channelId = :channelId")
    int deleteOne(@Param("id") Long id, @Param("channelId") Long channelId);

    /** Kanal silinirken (aynı işlemde) — toplu, varlık yüklemez. */
    @Modifying
    @Transactional
    @Query("DELETE FROM TechErrorChannelNoteImage i WHERE i.channelId = :channelId")
    int deleteAllForChannel(@Param("channelId") Long channelId);
}
