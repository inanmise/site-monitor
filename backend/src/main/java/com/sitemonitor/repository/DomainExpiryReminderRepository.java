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

    /** İzlemenin verilen durumdaki EN ESKİ 20 satırı (MP-45: yarıda kalmış {@code SENDING} sahiplenmeleri) — sınırlı okuma. */
    List<DomainExpiryReminder> findTop20ByMonitorIdAndStatusOrderBySentAtAsc(Long monitorId, String status);

    /**
     * MP-45 (2026-10-23, çok pod R2a · D2): gönderim sonrası sonucu YALNIZ bu sahiplenmenin {@code SENDING} satırına yazar
     * (bayatlamış satırı yarıda kaldı işaretlemiş bir tura ya da başka yazara dokunmaz).
     */
    @Modifying
    @Transactional
    @Query("UPDATE DomainExpiryReminder r SET r.status = :status, r.recipients = :recipients, r.pushQueued = :pushQueued "
            + "WHERE r.id = :id AND r.status = :claimed")
    int finishClaim(@Param("id") Long id, @Param("claimed") String claimed, @Param("status") String status,
                    @Param("recipients") String recipients, @Param("pushQueued") Integer pushQueued);

    /** MP-45: hiçbir şey gönderilmeden düşen sahiplenmeyi geri verir — sonraki tur yeniden dener. */
    @Modifying
    @Transactional
    @Query("DELETE FROM DomainExpiryReminder r WHERE r.id = :id AND r.status = :claimed")
    int releaseClaim(@Param("id") Long id, @Param("claimed") String claimed);

    /** MP-45: yarıda kalmış sahiplenme (pod öldü) — koşullu, yeniden gönderilmez. */
    @Modifying
    @Transactional
    @Query("UPDATE DomainExpiryReminder r SET r.status = :status WHERE r.id = :id AND r.status = :claimed")
    int markIfStatus(@Param("id") Long id, @Param("claimed") String claimed, @Param("status") String status);
}
