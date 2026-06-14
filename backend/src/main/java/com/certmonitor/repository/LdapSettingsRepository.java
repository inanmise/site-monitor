package com.certmonitor.repository;

import com.certmonitor.model.LdapSettings;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LdapSettingsRepository extends JpaRepository<LdapSettings, Long> {
}
