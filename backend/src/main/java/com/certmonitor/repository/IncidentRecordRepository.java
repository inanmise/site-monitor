package com.certmonitor.repository;

import com.certmonitor.model.IncidentRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface IncidentRecordRepository extends JpaRepository<IncidentRecord, Long> {

    /**
     * Filtreli + sayfalı arama. Tüm filtreler opsiyonel (:p IS NULL OR ...).
     * q: serviste önceden lower-case + %..% sarılmış anahtar kelime (title/service/rca/description'da arar).
     * Tarihler ISO-String → sıralanabilir, >= / <= güvenli.
     */
    @Query("""
            SELECT i FROM IncidentRecord i
             WHERE (:q IS NULL OR LOWER(i.title) LIKE :q OR LOWER(i.service) LIKE :q
                                OR LOWER(i.rcaSummary) LIKE :q OR LOWER(i.description) LIKE :q)
               AND (:severity IS NULL OR i.severity = :severity)
               AND (:category IS NULL OR i.category = :category)
               AND (:status   IS NULL OR i.status   = :status)
               AND (:service  IS NULL OR LOWER(i.service) LIKE :service)
               AND (:channel  IS NULL OR i.channel = :channel)
               AND (:since    IS NULL OR i.occurredAt >= :since)
               AND (:until    IS NULL OR i.occurredAt <= :until)
               AND (:slaBreached IS NULL OR i.slaBreached = :slaBreached)
            """)
    Page<IncidentRecord> findFiltered(@Param("q") String q,
                                      @Param("severity") String severity,
                                      @Param("category") String category,
                                      @Param("status") String status,
                                      @Param("service") String service,
                                      @Param("channel") String channel,
                                      @Param("since") String since,
                                      @Param("until") String until,
                                      @Param("slaBreached") Boolean slaBreached,
                                      Pageable pageable);

    /** Günlük olay sayısı (trend) — ISO string'in ilk 10 hanesi = gün. */
    @Query("""
            SELECT SUBSTRING(i.occurredAt, 1, 10), COUNT(i)
              FROM IncidentRecord i
             WHERE (:since IS NULL OR i.occurredAt >= :since)
               AND (:until IS NULL OR i.occurredAt <= :until)
             GROUP BY SUBSTRING(i.occurredAt, 1, 10)
             ORDER BY SUBSTRING(i.occurredAt, 1, 10)
            """)
    List<Object[]> countByDay(@Param("since") String since, @Param("until") String until);

    @Query("""
            SELECT i.severity, COUNT(i) FROM IncidentRecord i
             WHERE (:since IS NULL OR i.occurredAt >= :since)
               AND (:until IS NULL OR i.occurredAt <= :until)
             GROUP BY i.severity
            """)
    List<Object[]> countBySeverity(@Param("since") String since, @Param("until") String until);

    @Query("""
            SELECT i.category, COUNT(i) FROM IncidentRecord i
             WHERE (:since IS NULL OR i.occurredAt >= :since)
               AND (:until IS NULL OR i.occurredAt <= :until)
             GROUP BY i.category
            """)
    List<Object[]> countByCategory(@Param("since") String since, @Param("until") String until);

    /** Kanal kırılımı (NULL kanallar hariç) — kanal bazlı segmentasyon trendi. */
    @Query("""
            SELECT i.channel, COUNT(i) FROM IncidentRecord i
             WHERE i.channel IS NOT NULL
               AND (:since IS NULL OR i.occurredAt >= :since)
               AND (:until IS NULL OR i.occurredAt <= :until)
             GROUP BY i.channel
            """)
    List<Object[]> countByChannel(@Param("since") String since, @Param("until") String until);

    @Query("""
            SELECT COUNT(i) FROM IncidentRecord i
             WHERE (:since IS NULL OR i.occurredAt >= :since)
               AND (:until IS NULL OR i.occurredAt <= :until)
            """)
    long countRange(@Param("since") String since, @Param("until") String until);

    /** Olaylarda fiilen kullanılan farklı kanal/domain değerleri — dropdown union'ı için. */
    @Query("SELECT DISTINCT i.channel FROM IncidentRecord i WHERE i.channel IS NOT NULL AND i.channel <> ''")
    List<String> distinctChannels();

    @Query("SELECT DISTINCT i.service FROM IncidentRecord i WHERE i.service IS NOT NULL AND i.service <> ''")
    List<String> distinctServices();

    @Query("""
            SELECT COUNT(i) FROM IncidentRecord i
             WHERE i.slaBreached = true
               AND (:since IS NULL OR i.occurredAt >= :since)
               AND (:until IS NULL OR i.occurredAt <= :until)
            """)
    long countSlaBreached(@Param("since") String since, @Param("until") String until);

    @Query("""
            SELECT COUNT(i) FROM IncidentRecord i
             WHERE i.status <> 'RESOLVED'
               AND (:since IS NULL OR i.occurredAt >= :since)
               AND (:until IS NULL OR i.occurredAt <= :until)
            """)
    long countOpen(@Param("since") String since, @Param("until") String until);
}
