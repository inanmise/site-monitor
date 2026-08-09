package com.sitemonitor.repository;

import com.sitemonitor.model.CertInventoryReportLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CertInventoryReportLogRepository extends JpaRepository<CertInventoryReportLog, Long> {

    /** İdempotens kontrolü — bu ay zaten gönderildi mi? */
    Optional<CertInventoryReportLog> findByReportYearAndMonthNo(Integer reportYear, Integer monthNo);

    /** Ayarlar sayfasındaki gönderim arşivi (en yeni önce). */
    List<CertInventoryReportLog> findAllByOrderBySentAtDesc(Pageable pageable);

    Optional<CertInventoryReportLog> findFirstByStatusOrderBySentAtDesc(String status);
}
