package com.sitemonitor.repository;

import com.sitemonitor.model.K6CompatResult;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/** k6 uyumluluk taraması sonuçları (2026-10-29) — tarama başına izleme sayısı kadar satır. */
public interface K6CompatResultRepository extends JpaRepository<K6CompatResult, Long> {

    @Query("SELECT r FROM K6CompatResult r WHERE r.scanId = :scanId ORDER BY r.id ASC")
    List<K6CompatResult> findForScan(@Param("scanId") Long scanId, Pageable page);
}
