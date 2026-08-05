package com.sitemonitor.repository;

import com.sitemonitor.model.MaintenanceWindow;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MaintenanceWindowRepository extends JpaRepository<MaintenanceWindow, Long> {

    List<MaintenanceWindow> findAllByOrderByStartAtDesc();

    /** Etkin (duraklatılmamış) pencereler — cache refresh'i için. */
    List<MaintenanceWindow> findByActiveTrue();
}
