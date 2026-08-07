package com.sitemonitor.repository;

import com.sitemonitor.model.LoginIssueReport;
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
               AND (:source   IS NULL OR r.source = :source)
               AND (:category IS NULL OR r.category = :category)
               AND (:since  IS NULL OR r.reportedAt >= :since)
               AND (:until  IS NULL OR r.reportedAt <= :until)
               AND (:q IS NULL OR LOWER(r.message) LIKE :q OR LOWER(r.errorText) LIKE :q OR LOWER(r.username) LIKE :q)
             ORDER BY r.reportedAt DESC
            """)
    Page<LoginIssueReport> findFiltered(@Param("status") String status,
                                        @Param("source") String source,
                                        @Param("category") String category,
                                        @Param("q") String q,
                                        @Param("since") String since,
                                        @Param("until") String until,
                                        Pageable pageable);

    /** Günlük özet (digest) — verilen andan beri gelen kaynak bazlı bildirimler, en yeni önce. */
    List<LoginIssueReport> findBySourceAndReportedAtGreaterThanEqualOrderByReportedAtDesc(String source, String since);

    /** Durum kırılımı (opsiyonel tarih aralığı) — sayaç kartları için. */
    @Query("""
            SELECT r.status, COUNT(r) FROM LoginIssueReport r
             WHERE (:since IS NULL OR r.reportedAt >= :since)
               AND (:until IS NULL OR r.reportedAt <= :until)
             GROUP BY r.status
            """)
    List<Object[]> countByStatus(@Param("since") String since, @Param("until") String until);
}
