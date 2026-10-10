package com.sitemonitor.repository;

import com.sitemonitor.model.TlsGradeChange;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/** TLS notu değişim günlüğü (2026-10-10). Yazma yalnız {@code saveAll}; silme gece temizliğinde (RetentionCatalog). */
public interface TlsGradeChangeRepository extends JpaRepository<TlsGradeChange, Long> {

    /** Bir kaydın son değişimleri (sertifika penceresi not geçmişi). */
    List<TlsGradeChange> findTop20ByInventoryIdOrderByChangedAtDescIdDesc(Long inventoryId);

    /** Pencere içindeki değişimler, yeniden eskiye — "son düşüşler" (kapsam süzgeci çağıranda). */
    @Query("SELECT c FROM TlsGradeChange c WHERE c.changedAt >= :since AND c.direction = :direction "
            + "ORDER BY c.changedAt DESC, c.id DESC")
    List<TlsGradeChange> findRecent(@Param("since") String since, @Param("direction") String direction, Pageable page);

    /**
     * Yarı açık pencere {@code [from, to)} içindeki değişimler, yeniden eskiye — aylık yönetici özeti (ay sınırları
     * İstanbul takvimi, UTC damga). Sayfa sınırı çağıranda (sorgu sınırsız olmasın).
     */
    @Query("SELECT c FROM TlsGradeChange c WHERE c.changedAt >= :from AND c.changedAt < :to AND c.direction = :direction "
            + "ORDER BY c.changedAt DESC, c.id DESC")
    List<TlsGradeChange> findBetween(@Param("from") String from, @Param("to") String to,
                                     @Param("direction") String direction, Pageable page);

    /** {@link #findBetween} penceresindeki TAM sayı (liste sınırlıyken de doğru toplam). */
    @Query("SELECT COUNT(c) FROM TlsGradeChange c WHERE c.changedAt >= :from AND c.changedAt < :to AND c.direction = :direction")
    long countBetween(@Param("from") String from, @Param("to") String to, @Param("direction") String direction);
}
