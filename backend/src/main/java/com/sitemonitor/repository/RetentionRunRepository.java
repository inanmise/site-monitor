package com.sitemonitor.repository;

import com.sitemonitor.model.RetentionRun;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RetentionRunRepository extends JpaRepository<RetentionRun, Long> {

    /** Sağlık sinyali: en son GERÇEK (dry-run olmayan) çalışma. */
    Optional<RetentionRun> findFirstByDryRunFalseOrderByStartedAtDesc();

    /** Ekrandaki çalışma geçmişi (dry-run'lar dahil). */
    Page<RetentionRun> findAllByOrderByStartedAtDesc(Pageable pageable);
}
