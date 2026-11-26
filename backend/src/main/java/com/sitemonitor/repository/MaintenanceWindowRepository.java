package com.sitemonitor.repository;

import com.sitemonitor.model.MaintenanceWindow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface MaintenanceWindowRepository extends JpaRepository<MaintenanceWindow, Long> {

    List<MaintenanceWindow> findAllByOrderByStartAtDesc();

    /** Etkin (duraklatılmamış) pencereler — cache refresh'i için. */
    List<MaintenanceWindow> findByActiveTrue();

    /**
     * Duraklat / sürdür (H paketi 2026-11-26): YALNIZ {@code active} + {@code updated_at} — okunan varlığın bütünü
     * kaydedilmez (eşzamanlı düzenleme geri yazılmasın). Durum zaten istenen değerdeyse 0 satır (iz / denetim yok).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE MaintenanceWindow w SET w.active = :active, w.updatedAt = :at "
            + "WHERE w.id = :id AND (w.active IS NULL OR w.active <> :active)")
    int setActiveIfChanged(@Param("id") Long id, @Param("active") boolean active, @Param("at") String at);
}
