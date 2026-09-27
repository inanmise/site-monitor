package com.sitemonitor.repository;

import com.sitemonitor.model.NocSettings;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NocSettingsRepository extends JpaRepository<NocSettings, Long> {
}
