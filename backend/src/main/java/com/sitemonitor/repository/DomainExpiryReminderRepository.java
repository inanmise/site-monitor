package com.sitemonitor.repository;

import com.sitemonitor.model.DomainExpiryReminder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface DomainExpiryReminderRepository extends JpaRepository<DomainExpiryReminder, Long> {
    boolean existsByMonitorIdAndExpiryDateAndThresholdDays(Long monitorId, String expiryDate, Integer thresholdDays);
    List<DomainExpiryReminder> findTop50ByMonitorIdOrderBySentAtDesc(Long monitorId);

    /** İzleme silinince izleri de gider (yetim satır kalmasın). Yazan türetilmiş sorgu → @Transactional şart. */
    @Modifying
    @Transactional
    @Query("DELETE FROM DomainExpiryReminder r WHERE r.monitorId = :monitorId")
    int deleteByMonitorId(@Param("monitorId") Long monitorId);
}
