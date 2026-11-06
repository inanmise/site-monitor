package com.sitemonitor.repository;

import com.sitemonitor.model.PageResourceIssue;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PageResourceIssueRepository extends JpaRepository<PageResourceIssue, Long> {

    /** Bir kontrolün tüm sorunlu kaynakları. */
    List<PageResourceIssue> findByCheckIdOrderByIdAsc(Long checkId);

    /** Bir monitörün EN SON kontrolünün sorun listesi (detay "Sorunlar" tab'ı). */
    List<PageResourceIssue> findByMonitorIdOrderByCheckedAtDescIdAsc(Long monitorId);

    /** Sorun listesi — SQL-LIMIT'li, opsiyonel issueType filtresi (null = hepsi). */
    // Performans 2026-11-06: IN (:id, :id) + ORDER BY monitorId biçimi (MonitorIndexSort) — bileşik idx_pri_monitor_checked geriye taranır.
    @Query("SELECT r FROM PageResourceIssue r WHERE r.monitorId IN (:id, :id) "
         + "AND (:issueType IS NULL OR r.issueType = :issueType) "
         + "AND (:since IS NULL OR r.checkedAt >= :since) "
         + "ORDER BY r.monitorId DESC, r.checkedAt DESC, r.id ASC LIMIT :limit")
    List<PageResourceIssue> findFiltered(@Param("id") Long id, @Param("issueType") String issueType,
                                         @Param("since") String since, @Param("limit") int limit);

    long countByMonitorId(Long monitorId);
}
