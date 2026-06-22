package com.certmonitor.repository;

import com.certmonitor.model.WeeklyAvailabilityLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WeeklyAvailabilityLogRepository extends JpaRepository<WeeklyAvailabilityLog, Long> {
    Optional<WeeklyAvailabilityLog> findByTeamIdAndReportYearAndWeekNo(Long teamId, Integer reportYear, Integer weekNo);

    /** En son zamanlanmış gönderim kaydı (scheduler kartı "son çalışma" için). sentAt UTC ISO → leksikografik sıralanabilir. */
    Optional<WeeklyAvailabilityLog> findTopByOrderBySentAtDesc();

    /** Belirli (yıl, hafta) için tüm takım kayıtları — son çalışmanın özetini (gönderilen/hatalı) çıkarmak için. */
    List<WeeklyAvailabilityLog> findByReportYearAndWeekNo(Integer reportYear, Integer weekNo);
}
