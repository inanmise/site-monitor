package com.sitemonitor.repository;

import com.sitemonitor.model.DiagnosticRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DiagnosticRunRepository extends JpaRepository<DiagnosticRun, Long> {

    /** Domain başına tanılama geçmişi (yeni → eski), son 100 kayıt. */
    List<DiagnosticRun> findTop100ByDomainOrderByIdDesc(String domain);
}
