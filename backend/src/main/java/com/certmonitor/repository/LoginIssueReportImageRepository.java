package com.certmonitor.repository;

import com.certmonitor.model.LoginIssueReportImage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LoginIssueReportImageRepository extends JpaRepository<LoginIssueReportImage, Long> {

    List<LoginIssueReportImage> findByReportIdOrderByIdAsc(Long reportId);

    void deleteByReportId(Long reportId);
}
