package com.sitemonitor.repository;

import com.sitemonitor.model.DbQueryAlertRows;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

public interface DbQueryAlertRowsRepository extends JpaRepository<DbQueryAlertRows, Long> {

    Optional<DbQueryAlertRows> findByAlertEventId(Long alertEventId);

    @Transactional
    @Modifying
    int deleteByMonitorId(Long monitorId);
}
