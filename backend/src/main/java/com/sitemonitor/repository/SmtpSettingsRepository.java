package com.sitemonitor.repository;

import com.sitemonitor.model.SmtpSettings;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SmtpSettingsRepository extends JpaRepository<SmtpSettings, Long> {
}
