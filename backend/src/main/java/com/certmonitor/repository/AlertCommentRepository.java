package com.certmonitor.repository;

import com.certmonitor.model.AlertComment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface AlertCommentRepository extends JpaRepository<AlertComment, Long> {

    /** Bir incident'in silinmemiş yorumları — eskiden yeniye. */
    List<AlertComment> findByAlertEventIdAndDeletedAtIsNullOrderByCreatedAtAsc(Long alertEventId);

    /** Tek incident için silinmemiş yorum sayısı. */
    long countByAlertEventIdAndDeletedAtIsNull(Long alertEventId);

    /** Batch: verilen incident id'leri için [alert_event_id, count] — liste sütununda N+1'siz sayaç. */
    @Query("SELECT c.alertEventId, COUNT(c) FROM AlertComment c " +
           "WHERE c.alertEventId IN :ids AND c.deletedAt IS NULL GROUP BY c.alertEventId")
    List<Object[]> countByAlertIds(@Param("ids") Collection<Long> ids);
}
