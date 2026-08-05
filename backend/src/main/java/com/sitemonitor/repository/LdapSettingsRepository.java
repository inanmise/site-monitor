package com.sitemonitor.repository;

import com.sitemonitor.model.LdapSettings;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LdapSettingsRepository extends JpaRepository<LdapSettings, Long> {
}
