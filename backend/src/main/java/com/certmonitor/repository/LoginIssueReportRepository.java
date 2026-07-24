package com.certmonitor.repository;

import com.certmonitor.model.LoginIssueReport;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface LoginIssueReportRepository extends JpaRepository<LoginIssueReport, Long> {

    /** Filtreli + sayfalı. status/tarih aralığı + serbest metin (q) opsiyonel (:p IS NULL OR ...). En yeni önce.
     *  q, çağıran tarafından "%küçükharf%" olarak sarılıp geçirilir (message + errorText + username içinde arar). */
    @Query("""
            SELECT r FROM LoginIssueReport r
             WHERE (:status IS NULL OR r.status = :status)
               AND (:since  IS NULL OR r.reportedAt >= :since)
               AND (:until  IS NULL OR r.reportedAt <= :until)
               AND (:q IS NULL OR LOWER(r.message) LIKE :q OR LOWER(r.errorText) LIKE :q OR LOWER(r.username) LIKE :q)
             ORDER BY r.reportedAt DESC
            """)
    Page<LoginIssueReport> findFiltered(@Param("status") String status,
                                        @Param("q") String q,
                                        @Param("since") String since,
                                        @Param("until") String until,
                                        Pageable pageable);

    /** Durum kırılımı (opsiyonel tarih aralığı) — sayaç kartları için. */
    @Query("""
            SELECT r.status, COUNT(r) FROM LoginIssueReport r
             WHERE (:since IS NULL OR r.reportedAt >= :since)
               AND (:until IS NULL OR r.reportedAt <= :until)
             GROUP BY r.status
            """)
    List<Object[]> countByStatus(@Param("since") String since, @Param("until") String until);
}
