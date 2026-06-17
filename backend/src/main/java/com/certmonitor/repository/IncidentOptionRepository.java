package com.certmonitor.repository;

import com.certmonitor.model.IncidentOption;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface IncidentOptionRepository extends JpaRepository<IncidentOption, Long> {

    List<IncidentOption> findByTypeOrderByValueAsc(String type);

    Optional<IncidentOption> findFirstByTypeAndValueIgnoreCase(String type, String value);
}
