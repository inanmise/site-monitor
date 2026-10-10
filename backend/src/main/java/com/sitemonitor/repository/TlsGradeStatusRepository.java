package com.sitemonitor.repository;

import com.sitemonitor.model.TlsGradeStatus;
import org.springframework.data.jpa.repository.JpaRepository;

/** Son bilinen TLS notu (2026-10-10) — envanter kimliği başına tek satır. Yazma yalnız {@code save}/{@code saveAll}. */
public interface TlsGradeStatusRepository extends JpaRepository<TlsGradeStatus, Long> {
}
