package com.sitemonitor.repository;

import com.sitemonitor.model.IncidentPostmortem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface IncidentPostmortemRepository extends JpaRepository<IncidentPostmortem, Long> {

    Optional<IncidentPostmortem> findByIncidentId(Long incidentId);

    /** Liste rozetleri: [incident_id, status] — sayfadaki olaylar için TEK sorgu. */
    @Query("SELECT p.incidentId, p.status FROM IncidentPostmortem p WHERE p.incidentId IN :ids")
    List<Object[]> statusFor(@Param("ids") Collection<Long> ids);

    @Modifying
    @Transactional
    @Query("DELETE FROM IncidentPostmortem p WHERE p.incidentId = :incidentId")
    int deleteForIncident(@Param("incidentId") Long incidentId);
}
