package com.sitemonitor.repository;

import com.sitemonitor.model.WatchdogState;
import org.springframework.data.jpa.repository.JpaRepository;

/** Kendi kendini izleme durum satırları (2026-10-11) — kontrol başına bir satır; yazma yalnız {@code save} (tek pod, kilit altında). */
public interface WatchdogStateRepository extends JpaRepository<WatchdogState, String> {
}
