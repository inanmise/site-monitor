package com.sitemonitor.repository;

import com.sitemonitor.model.TechErrorApprovalToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** PO onayı e-posta belirteçleri (2026-10-17, S6). Yazan metotlar kendi işlemlerini açar (kurallı). */
public interface TechErrorApprovalTokenRepository extends JpaRepository<TechErrorApprovalToken, Long> {

    Optional<TechErrorApprovalToken> findByTokenHash(String tokenHash);

    List<TechErrorApprovalToken> findByReportIdOrderByIdAsc(Long reportId);

    @Modifying
    @Transactional
    @Query("DELETE FROM TechErrorApprovalToken t WHERE t.reportId = :reportId")
    int deleteByReportId(@Param("reportId") Long reportId);

    /** Tek kullanım (at-most-once): yalnız kullanılmamış satır damgalanır; 0 = başka bir istek önce kullandı. */
    @Modifying
    @Transactional
    @Query("UPDATE TechErrorApprovalToken t SET t.usedAt = :at WHERE t.id = :id AND t.usedAt IS NULL")
    int markUsed(@Param("id") Long id, @Param("at") Instant at);

    /** Karar yazılamadıysa (beklenmeyen hata) damga geri alınır: bağlantı kullanılabilir kalır (inceleme 2026-10-17). */
    @Modifying
    @Transactional
    @Query("UPDATE TechErrorApprovalToken t SET t.usedAt = NULL WHERE t.id = :id AND t.usedAt = :at")
    int restoreUnused(@Param("id") Long id, @Param("at") Instant at);

    /** Karar verilince raporun diğer bağlantıları da kapanır (kullanılmamış olanlar damgalanır). */
    @Modifying
    @Transactional
    @Query("UPDATE TechErrorApprovalToken t SET t.usedAt = :at WHERE t.reportId = :reportId AND t.usedAt IS NULL")
    int closeAllForReport(@Param("reportId") Long reportId, @Param("at") Instant at);

    @Modifying
    @Transactional
    @Query("DELETE FROM TechErrorApprovalToken t WHERE t.expiresAt < :before")
    int deleteExpiredBefore(@Param("before") Instant before);
}
