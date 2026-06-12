package com.certmonitor.repository;

import com.certmonitor.model.WeeklyReportMail;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface WeeklyReportMailRepository extends JpaRepository<WeeklyReportMail, Long> {

    List<WeeklyReportMail> findByReportIdOrderByIdDesc(Long reportId);

    /** Liste rozetleri — tek sorguda tüm raporların kayıtları. */
    List<WeeklyReportMail> findByReportIdIn(Collection<Long> reportIds);

    void deleteByReportId(Long reportId);
}
