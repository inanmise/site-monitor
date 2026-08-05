package com.sitemonitor.repository;

import com.sitemonitor.model.LoginAnomalyIncident;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface LoginAnomalyIncidentRepository extends JpaRepository<LoginAnomalyIncident, Long> {

    /** Açık (çözülmemiş) tek incident — cooldown/escalation/resolve kararları için. */
    Optional<LoginAnomalyIncident> findFirstByResolvedFalseOrderByOpenedAtDesc();

    /** Son uyarılar listesi (admin ekranı), en yeni üstte. */
    Page<LoginAnomalyIncident> findAllByOrderByOpenedAtDesc(Pageable pageable);
}
