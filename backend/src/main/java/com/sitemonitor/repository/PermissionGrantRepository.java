package com.sitemonitor.repository;

import com.sitemonitor.model.PermissionGrant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PermissionGrantRepository extends JpaRepository<PermissionGrant, Long> {
    Optional<PermissionGrant> findByRoleAndResourceKeyAndAction(String role, String resourceKey, String action);
    List<PermissionGrant> findByRole(String role);
}
