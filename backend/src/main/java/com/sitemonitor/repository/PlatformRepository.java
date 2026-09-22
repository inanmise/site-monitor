package com.sitemonitor.repository;

import com.sitemonitor.model.Platform;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PlatformRepository extends JpaRepository<Platform, Long> {
    List<Platform> findAllByOrderBySortOrderAscNameAsc();
    List<Platform> findByActiveTrueOrderBySortOrderAscNameAsc();
    Optional<Platform> findByCodeIgnoreCase(String code);
    boolean existsByCodeIgnoreCase(String code);
}
