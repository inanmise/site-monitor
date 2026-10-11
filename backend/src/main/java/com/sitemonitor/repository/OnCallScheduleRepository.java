package com.sitemonitor.repository;

import com.sitemonitor.model.OnCallSchedule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Takım nöbet çizelgeleri (2026-10-11) — takım başına bir satır. Yazma {@code save} / {@code delete} ile. */
public interface OnCallScheduleRepository extends JpaRepository<OnCallSchedule, Long> {

    Optional<OnCallSchedule> findByTeamId(Long teamId);

    List<OnCallSchedule> findByTeamIdIn(Collection<Long> teamIds);

    List<OnCallSchedule> findByEnabledTrue();
}
