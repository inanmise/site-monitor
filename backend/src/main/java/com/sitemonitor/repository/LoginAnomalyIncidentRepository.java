package com.sitemonitor.repository;

import com.sitemonitor.model.LoginAnomalyIncident;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

public interface LoginAnomalyIncidentRepository extends JpaRepository<LoginAnomalyIncident, Long> {

    /** Açık (çözülmemiş) tek incident — cooldown/escalation/resolve kararları için. */
    Optional<LoginAnomalyIncident> findFirstByResolvedFalseOrderByOpenedAtDesc();

    /** Son uyarılar listesi (admin ekranı), en yeni üstte. */
    Page<LoginAnomalyIncident> findAllByOrderByOpenedAtDesc(Pageable pageable);

    /**
     * Başarılı uyarı gönderiminin damgası (2026-10-21, çok pod R1 / MP-10) — YALNIZ bu kolon. Dağıtıcı eskiden yüklediği
     * varlığı bütünüyle kaydediyordu ({@code @Version} yok): gönderim sürerken başka taramanın yazdığı {@code resolved=true}
     * geri alınabiliyordu (çözülen anomali yeniden açık, ikinci bir "normale döndü" postası). Satır yoksa 0.
     */
    @Modifying
    @Transactional
    @Query(value = "UPDATE login_anomaly_incident SET last_alert_at = :at WHERE id = :id", nativeQuery = true)
    int stampLastAlertAt(@Param("id") Long id, @Param("at") String at);
}
