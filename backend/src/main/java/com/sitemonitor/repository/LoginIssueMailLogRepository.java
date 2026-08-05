package com.sitemonitor.repository;

import com.sitemonitor.model.LoginIssueMailLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LoginIssueMailLogRepository extends JpaRepository<LoginIssueMailLog, Long> {

    /** Bir bildirime ait mail gönderimleri, en eskiden yeniye (gönderim sırası). */
    List<LoginIssueMailLog> findByReportIdOrderByIdAsc(Long reportId);
}
