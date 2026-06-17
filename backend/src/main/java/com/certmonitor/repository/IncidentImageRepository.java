package com.certmonitor.repository;

import com.certmonitor.model.IncidentImage;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IncidentImageRepository extends JpaRepository<IncidentImage, Long> {
}
