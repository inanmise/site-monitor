package com.sitemonitor.repository;

import com.sitemonitor.model.WeeklyReportComment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface WeeklyReportCommentRepository extends JpaRepository<WeeklyReportComment, Long> {
    List<WeeklyReportComment> findByReportIdOrderByCreatedAtAsc(Long reportId);

    /** Liste rozeti: rapor → yorum sayısı (tek sorgu). */
    @Query("SELECT c.reportId, COUNT(c) FROM WeeklyReportComment c WHERE c.reportId IN :ids GROUP BY c.reportId")
    List<Object[]> countByReportIds(@Param("ids") Collection<Long> ids);

    /** Yalnız WeeklyReportService.delete (@Transactional) çağırır — RepositoryWriteTransactionGuardTest. */
    void deleteByReportId(Long reportId);
}
