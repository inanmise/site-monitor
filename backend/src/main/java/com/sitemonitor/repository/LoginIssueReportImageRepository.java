package com.sitemonitor.repository;

import com.sitemonitor.model.LoginIssueReportImage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface LoginIssueReportImageRepository extends JpaRepository<LoginIssueReportImage, Long> {

    List<LoginIssueReportImage> findByReportIdOrderByIdAsc(Long reportId);

    /**
     * Rapor kalici silinince resimleri de gider.
     *
     * <p>{@code @Transactional} + {@code int} SART: turetilmis silme sorgularina Spring Data
     * kendiliginden transaction SARMAZ ve {@code open-in-view=false} oldugu icin cagri tx'siz
     * duserdi ({@code RepositoryWriteTransactionGuardTest} bunun kapisi).
     */
    @Transactional
    int deleteByReportId(Long reportId);
}
