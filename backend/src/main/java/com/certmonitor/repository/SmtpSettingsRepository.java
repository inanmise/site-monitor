package com.certmonitor.repository;

import com.certmonitor.model.SmtpSettings;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SmtpSettingsRepository extends JpaRepository<SmtpSettings, Long> {
}
