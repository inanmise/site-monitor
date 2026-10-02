package com.sitemonitor.repository;

import com.sitemonitor.model.DiagnosticRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DiagnosticRunRepository extends JpaRepository<DiagnosticRun, Long> {

    /** Domain başına tanılama geçmişi (yeni → eski), son 100 kayıt. */
    List<DiagnosticRun> findTop100ByDomainOrderByIdDesc(String domain);

    /**
     * Anahtar + tür başına son 20 kayıt (yeni → eski) — HTTP uçtan uca tanılaması (2026-10-02) izleme anahtarıyla
     * ({@code http-monitor:<id>}) okur; gerçek alan adı olmayan anahtar mevcut domain geçmişine hiç karışmaz.
     */
    List<DiagnosticRun> findTop20ByDomainAndRunTypeOrderByIdDesc(String domain, String runType);
}
