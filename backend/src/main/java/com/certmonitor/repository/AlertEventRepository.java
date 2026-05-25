package com.certmonitor.repository;

import com.certmonitor.model.AlertEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface AlertEventRepository extends JpaRepository<AlertEvent, Long> {

    @Query("SELECT e FROM AlertEvent e WHERE e.domain = :domain AND e.alertType = :alertType AND e.resolved = false ORDER BY e.createdAt DESC")
    Optional<AlertEvent> findOpenAlert(String domain, String alertType);

    List<AlertEvent> findByResolvedFalseAndAcknowledgedFalseOrderByCreatedAtDesc();

    List<AlertEvent> findAllByOrderByCreatedAtDesc();

    @Query("SELECT e FROM AlertEvent e WHERE e.resolved = false ORDER BY e.alertLevel DESC, e.createdAt DESC")
    List<AlertEvent> findAllOpenOrderBySeverity();

    List<AlertEvent> findByDomainOrderByCreatedAtDesc(String domain);

    @Query("SELECT DISTINCT e.domain FROM AlertEvent e WHERE e.resolved = false AND NOT EXISTS (SELECT n FROM NotificationLog n WHERE n.alertEventId = e.id AND n.emailStatus = 'SENT')")
    List<String> findDomainsWithUnnotifiedOpenAlerts();
}
