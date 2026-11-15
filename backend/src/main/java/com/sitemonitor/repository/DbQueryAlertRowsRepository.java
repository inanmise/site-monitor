package com.sitemonitor.repository;

import com.sitemonitor.model.DbQueryAlertRows;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

public interface DbQueryAlertRowsRepository extends JpaRepository<DbQueryAlertRows, Long> {

    Optional<DbQueryAlertRows> findByAlertEventId(Long alertEventId);

    /** İzleme silinince satır kopyaları da gider — toplu tek {@code DELETE} (varlık yüklemez). */
    @Transactional
    @Modifying
    @Query("DELETE FROM DbQueryAlertRows r WHERE r.monitorId = :id")
    int deleteByMonitorId(@Param("id") Long monitorId);
}
