package com.certmonitor.repository;

import com.certmonitor.model.AlertEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AlertEventRepository extends JpaRepository<AlertEvent, Long> {

    @Query("SELECT e FROM AlertEvent e WHERE e.domain = :domain AND e.alertType = :alertType AND e.resolved = false ORDER BY e.createdAt DESC")
    Optional<AlertEvent> findOpenAlert(String domain, String alertType);

    /** Batch lookup — sweep'te N domain için N query yerine tek sorgu. */
    @Query("SELECT e FROM AlertEvent e WHERE e.resolved = false AND e.domain IN :domains")
    List<AlertEvent> findOpenByDomainIn(@Param("domains") Collection<String> domains);

    List<AlertEvent> findByResolvedFalseAndAcknowledgedFalseOrderByCreatedAtDesc();

    List<AlertEvent> findAllByOrderByCreatedAtDesc();

    @Query("SELECT e FROM AlertEvent e WHERE e.resolved = false ORDER BY e.alertLevel DESC, e.createdAt DESC")
    List<AlertEvent> findAllOpenOrderBySeverity();

    List<AlertEvent> findByDomainOrderByCreatedAtDesc(String domain);

    List<AlertEvent> findByDomainAndResolvedFalse(String domain);

    /** Tip-kapsamlı açık alarm sorgusu — cert sweep'i sadece cert tiplerini,
     *  uptime recovery sadece ACCESSIBILITY'yi kapatabilsin diye. */
    List<AlertEvent> findByDomainAndAlertTypeInAndResolvedFalse(String domain, Collection<String> alertTypes);

    // ── Alarm fırtınası (storm) sorguları ──────────────────────────────────────
    /** Pencere-içi açık DOWN incident'ler (account-wide scope) — terfi eşiği sayımı + üye geri-bağlama.
     *  idx_ae_storm_scan(resolved, alert_type, created_at) tarafından beslenir; created_at sabit-genişlik ISO → sözlüksel aralık. */
    @Query("SELECT e FROM AlertEvent e WHERE e.resolved = false AND e.alertType IN :types AND e.createdAt >= :since")
    List<AlertEvent> findOpenDownSince(@Param("types") Collection<String> types, @Param("since") String since);

    /** Pencere-içi açık DOWN incident'ler — per-group scope (yalnız verilen grup). */
    @Query("SELECT e FROM AlertEvent e WHERE e.resolved = false AND e.alertType IN :types AND e.createdAt >= :since AND e.groupName = :group")
    List<AlertEvent> findOpenDownSinceInGroup(@Param("types") Collection<String> types, @Param("since") String since, @Param("group") String group);

    /** Storm üyeleri (çözülmüş+açık) — toplu recovery e-postasında "hangi monitörler" listesi için. */
    List<AlertEvent> findByStormId(Long stormId);

    /** Hâlâ down (açık) storm üyeleri — çözülme/histerezis değerlendirmesi + toggle-off geri-bağlama için. */
    List<AlertEvent> findByStormIdAndResolvedFalse(Long stormId);

    @Query("""
            SELECT a FROM AlertEvent a
             WHERE a.resolved = false
               AND EXISTS (
                   SELECT 1 FROM CertificateInventory i
                    WHERE i.domain = a.domain
                      AND i.deletedAt IS NOT NULL
               )
            """)
    List<AlertEvent> findOpenAlertsOnSoftDeletedDomains();

    @Query("SELECT DISTINCT e.domain FROM AlertEvent e WHERE e.resolved = false AND NOT EXISTS (SELECT n FROM NotificationLog n WHERE n.alertEventId = e.id AND n.emailStatus = 'SENT')")
    List<String> findDomainsWithUnnotifiedOpenAlerts();

    @Query("""
            SELECT e FROM AlertEvent e
            WHERE (:resolved IS NULL OR e.resolved = :resolved)
              AND (:since IS NULL OR e.createdAt >= :since)
              AND (:until IS NULL OR e.createdAt <= :until)
              AND (:resolvedSince IS NULL OR e.resolvedAt >= :resolvedSince)
              AND (:resolvedUntil IS NULL OR e.resolvedAt <= :resolvedUntil)
              AND (:domain IS NULL OR e.domain = :domain)
              AND (:alertType IS NULL OR e.alertType = :alertType)
              AND (:scoped = FALSE OR e.teamId IN :scope OR EXISTS (
                      SELECT 1 FROM CertificateInventory i
                       WHERE i.domain = e.domain
                         AND (i.teamId IN :scope OR i.ugTeamId IN :scope)))
            """)
    Page<AlertEvent> findFiltered(
            @Param("resolved") Boolean resolved,
            @Param("since") String since,
            @Param("until") String until,
            @Param("resolvedSince") String resolvedSince,
            @Param("resolvedUntil") String resolvedUntil,
            @Param("domain") String domain,
            @Param("alertType") String alertType,
            @Param("scoped") boolean scoped,
            @Param("scope") List<Long> scope,
            Pageable pageable);

    /** Tip filtre pill'lerinin canlı sayıları — findFiltered ile aynı filtreler,
     *  alertType HARİÇ (sayılar her zaman tüm tipleri gösterir). */
    @Query("""
            SELECT e.alertType, COUNT(e) FROM AlertEvent e
            WHERE (:resolved IS NULL OR e.resolved = :resolved)
              AND (:since IS NULL OR e.createdAt >= :since)
              AND (:until IS NULL OR e.createdAt <= :until)
              AND (:resolvedSince IS NULL OR e.resolvedAt >= :resolvedSince)
              AND (:resolvedUntil IS NULL OR e.resolvedAt <= :resolvedUntil)
              AND (:domain IS NULL OR e.domain = :domain)
              AND (:scoped = FALSE OR e.teamId IN :scope OR EXISTS (
                      SELECT 1 FROM CertificateInventory i
                       WHERE i.domain = e.domain
                         AND (i.teamId IN :scope OR i.ugTeamId IN :scope)))
            GROUP BY e.alertType
            """)
    List<Object[]> countFilteredByType(
            @Param("resolved") Boolean resolved,
            @Param("since") String since,
            @Param("until") String until,
            @Param("resolvedSince") String resolvedSince,
            @Param("resolvedUntil") String resolvedUntil,
            @Param("domain") String domain,
            @Param("scoped") boolean scoped,
            @Param("scope") List<Long> scope);

    /** Domain rename: alarm geçmişini yeni domain'e taşı.
     *  Caller'da @Transactional zorunlu. */
    @Modifying
    @Query("UPDATE AlertEvent e SET e.domain = :newDomain WHERE e.domain = :oldDomain")
    int renameDomain(@Param("oldDomain") String oldDomain, @Param("newDomain") String newDomain);

    // ── Incidents Overview ekranı — findFiltered'dan AYRI: q, domain üzerinde LIKE (monitör adı/host araması) ──
    @Query("""
            SELECT e FROM AlertEvent e
             WHERE (:resolved IS NULL OR e.resolved = :resolved)
               AND (:since IS NULL OR e.createdAt >= :since)
               AND (:until IS NULL OR e.createdAt <= :until)
               AND (:alertType IS NULL OR e.alertType = :alertType)
               AND (:q IS NULL OR LOWER(e.domain) LIKE LOWER(CONCAT('%', :q, '%')))
               AND (:scoped = FALSE OR e.teamId IN :scope OR EXISTS (
                       SELECT 1 FROM CertificateInventory i
                        WHERE i.domain = e.domain
                          AND (i.teamId IN :scope OR i.ugTeamId IN :scope)))
            """)
    Page<AlertEvent> findIncidents(
            @Param("resolved") Boolean resolved,
            @Param("since") String since,
            @Param("until") String until,
            @Param("alertType") String alertType,
            @Param("q") String q,
            @Param("scoped") boolean scoped,
            @Param("scope") List<Long> scope,
            Pageable pageable);

    /** Root-cause (alertType) pill sayaçları — alertType HARİÇ aynı incident filtreleri. */
    @Query("""
            SELECT e.alertType, COUNT(e) FROM AlertEvent e
             WHERE (:resolved IS NULL OR e.resolved = :resolved)
               AND (:since IS NULL OR e.createdAt >= :since)
               AND (:until IS NULL OR e.createdAt <= :until)
               AND (:q IS NULL OR LOWER(e.domain) LIKE LOWER(CONCAT('%', :q, '%')))
               AND (:scoped = FALSE OR e.teamId IN :scope OR EXISTS (
                       SELECT 1 FROM CertificateInventory i
                        WHERE i.domain = e.domain
                          AND (i.teamId IN :scope OR i.ugTeamId IN :scope)))
            GROUP BY e.alertType
            """)
    List<Object[]> countIncidentsByType(
            @Param("resolved") Boolean resolved,
            @Param("since") String since,
            @Param("until") String until,
            @Param("q") String q,
            @Param("scoped") boolean scoped,
            @Param("scope") List<Long> scope);
}
