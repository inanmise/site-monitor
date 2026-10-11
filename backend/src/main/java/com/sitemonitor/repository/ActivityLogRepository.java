package com.sitemonitor.repository;

import com.sitemonitor.model.ActivityLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * Birleşik aktivite akışı sorguları. Takım-izolasyonu {@code (:scoped = FALSE OR a.teamId IN :scope)}
 * deseniyle SORGUDA zorlanır (AlertEventRepository ile aynı desen) — çağıran scope'u SESSION'dan türetir,
 * asla istemci parametresinden. scoped=false → global görücü (admin/AUDIT) tümünü görür.
 */
public interface ActivityLogRepository extends JpaRepository<ActivityLog, Long> {

    /**
     * Filtreli + sayfalı akış. Boş bırakılan filtreler null olarak geçilir (null-guard ile atlanır).
     * {@code types} null ise tür filtresi yok. {@code q} '%aranan%' (lower) biçiminde geçilmeli.
     */
    @Query("""
            SELECT a FROM ActivityLog a
            WHERE (:scoped = FALSE OR a.teamId IN :scope)
              AND (:typeFilter = FALSE OR a.monitorType IN :types)
              AND (:monitorId IS NULL OR a.monitorId = :monitorId)
              AND (:status IS NULL OR a.resultStatus = :status)
              AND (:from IS NULL OR a.activityTime >= :from)
              AND (:to IS NULL OR a.activityTime <= :to)
              AND (:q IS NULL OR LOWER(a.target) LIKE :q OR LOWER(a.monitorName) LIKE :q OR LOWER(a.errorMessage) LIKE :q)
            ORDER BY a.activityTime DESC, a.id DESC
            """)
    Page<ActivityLog> findFiltered(
            @Param("scoped") boolean scoped,
            @Param("scope") List<Long> scope,
            @Param("typeFilter") boolean typeFilter,
            @Param("types") List<String> types,
            @Param("monitorId") Long monitorId,
            @Param("status") String status,
            @Param("from") String from,
            @Param("to") String to,
            @Param("q") String q,
            Pageable pageable);

    /** Özet şerit — aynı filtre/scope ile durum bazlı sayımlar. Java'da toplanır. */
    @Query("""
            SELECT a.resultStatus, COUNT(a) FROM ActivityLog a
            WHERE (:scoped = FALSE OR a.teamId IN :scope)
              AND (:typeFilter = FALSE OR a.monitorType IN :types)
              AND (:monitorId IS NULL OR a.monitorId = :monitorId)
              AND (:from IS NULL OR a.activityTime >= :from)
              AND (:to IS NULL OR a.activityTime <= :to)
              AND (:q IS NULL OR LOWER(a.target) LIKE :q OR LOWER(a.monitorName) LIKE :q OR LOWER(a.errorMessage) LIKE :q)
            GROUP BY a.resultStatus
            """)
    List<Object[]> countByStatusFiltered(
            @Param("scoped") boolean scoped,
            @Param("scope") List<Long> scope,
            @Param("typeFilter") boolean typeFilter,
            @Param("types") List<String> types,
            @Param("monitorId") Long monitorId,
            @Param("from") String from,
            @Param("to") String to,
            @Param("q") String q);

    /** En son aktivite zamanı (özet şerit) — aynı scope/filtre. */
    @Query("""
            SELECT MAX(a.activityTime) FROM ActivityLog a
            WHERE (:scoped = FALSE OR a.teamId IN :scope)
              AND (:typeFilter = FALSE OR a.monitorType IN :types)
              AND (:monitorId IS NULL OR a.monitorId = :monitorId)
              AND (:from IS NULL OR a.activityTime >= :from)
              AND (:to IS NULL OR a.activityTime <= :to)
              AND (:q IS NULL OR LOWER(a.target) LIKE :q OR LOWER(a.monitorName) LIKE :q OR LOWER(a.errorMessage) LIKE :q)
            """)
    String maxActivityTimeFiltered(
            @Param("scoped") boolean scoped,
            @Param("scope") List<Long> scope,
            @Param("typeFilter") boolean typeFilter,
            @Param("types") List<String> types,
            @Param("monitorId") Long monitorId,
            @Param("from") String from,
            @Param("to") String to,
            @Param("q") String q);

    /**
     * Tür çipleri sayımı (2026-10-11, aktivite sayfası yeniden tasarımı): aynı kapsam/süzgeç + durum, TÜR SÜZGECİ HARİÇ
     * (her çip seçili olmasa da kendi sayısını gösterir).
     */
    @Query("""
            SELECT a.monitorType, COUNT(a) FROM ActivityLog a
            WHERE (:scoped = FALSE OR a.teamId IN :scope)
              AND (:monitorId IS NULL OR a.monitorId = :monitorId)
              AND (:status IS NULL OR a.resultStatus = :status)
              AND (:from IS NULL OR a.activityTime >= :from)
              AND (:to IS NULL OR a.activityTime <= :to)
              AND (:q IS NULL OR LOWER(a.target) LIKE :q OR LOWER(a.monitorName) LIKE :q OR LOWER(a.errorMessage) LIKE :q)
            GROUP BY a.monitorType
            """)
    List<Object[]> countByTypeFiltered(
            @Param("scoped") boolean scoped,
            @Param("scope") List<Long> scope,
            @Param("monitorId") Long monitorId,
            @Param("status") String status,
            @Param("from") String from,
            @Param("to") String to,
            @Param("q") String q);

    /**
     * Zaman çizelgesi (2026-10-11): UTC saat ({@code yyyy-MM-ddTHH}) × durum sayımı; KPI şeridiyle aynı süzgeç (durum
     * hariç — çizelge dağılımı gösterir). Yerel SQL: {@code GROUP BY 1, 2} hem PostgreSQL hem H2'de ifadeyi yeniden
     * yazmadan gruplar (JPQL'de SUBSTRING bağlı parametreye dönüşürse PostgreSQL GROUP BY eşleşmesini reddeder).
     * {@code from} ZORUNLU (çağıran sınırlar — en çok 31 gün, idx_act_time ile kesilir).
     */
    @Query(value = """
            SELECT SUBSTRING(activity_time, 1, 13) AS hr, result_status, COUNT(*) AS c FROM activity_log
            WHERE (:scoped = FALSE OR team_id IN (:scope))
              AND (:typeFilter = FALSE OR monitor_type IN (:types))
              AND (CAST(:monitorId AS BIGINT) IS NULL OR monitor_id = :monitorId)
              AND activity_time >= :from
              AND (CAST(:to AS VARCHAR) IS NULL OR activity_time <= :to)
              AND (CAST(:q AS VARCHAR) IS NULL OR LOWER(target) LIKE :q OR LOWER(monitor_name) LIKE :q OR LOWER(error_message) LIKE :q)
            GROUP BY 1, 2
            """, nativeQuery = true)
    List<Object[]> countByHourAndStatus(
            @Param("scoped") boolean scoped,
            @Param("scope") List<Long> scope,
            @Param("typeFilter") boolean typeFilter,
            @Param("types") List<String> types,
            @Param("monitorId") Long monitorId,
            @Param("from") String from,
            @Param("to") String to,
            @Param("q") String q);

    /** Detay panelindeki mini-geçmiş: aynı monitörün (tür + id) son N kaydı, en yeni üstte. */
    @Query("""
            SELECT a FROM ActivityLog a
            WHERE a.monitorType = :type AND a.monitorId = :monitorId AND a.action IN ('SCHEDULED_CHECK','MANUAL_CHECK')
            ORDER BY a.activityTime DESC, a.id DESC
            """)
    List<ActivityLog> findRecentForMonitor(@Param("type") String type,
                                           @Param("monitorId") Long monitorId,
                                           Pageable pageable);

    // ── "Sizin için — bugün" izleme kartları (2026-09-19; TodayMonitorInsightsService) ──────────
    // Üçü de monitörlü satırlarla (monitor_id dolu → 9 izleme türü) sınırlı; CERT/UPTIME envanter
    // satırları (monitor_id NULL) kendi kartlarında. Zaman aralığı idx_act_time ile kesilir.

    /** Yanıt süresi ortalaması (tür + monitör) — yalnız BAŞARILI ve ölçümlü kontroller (hata süresi tabana karışmasın). */
    @Query("""
            SELECT a.monitorType, a.monitorId, AVG(a.responseMs), COUNT(a) FROM ActivityLog a
            WHERE a.monitorId IS NOT NULL AND a.responseMs IS NOT NULL AND a.resultStatus = 'SUCCESS'
              AND a.action IN ('SCHEDULED_CHECK','MANUAL_CHECK')
              AND a.activityTime >= :from AND a.activityTime < :to
            GROUP BY a.monitorType, a.monitorId
            """)
    List<Object[]> avgResponseByMonitor(@Param("from") String from, @Param("to") String to);

    /** Son kontrol zamanı (tür + monitör) — bayat izleme tespiti; {@code since} öncesi hiç görünmeyen zaten bayattır. */
    @Query("""
            SELECT a.monitorType, a.monitorId, MAX(a.activityTime) FROM ActivityLog a
            WHERE a.monitorId IS NOT NULL AND a.action IN ('SCHEDULED_CHECK','MANUAL_CHECK') AND a.activityTime >= :since
            GROUP BY a.monitorType, a.monitorId
            """)
    List<Object[]> lastCheckByMonitor(@Param("since") String since);

    /** Aralıkta en az bir ARIZA (ERROR/TIMEOUT) yazan monitörler — kararsızlık dizisi yalnız bunlar için çekilir. */
    @Query("""
            SELECT DISTINCT a.monitorType, a.monitorId FROM ActivityLog a
            WHERE a.monitorId IS NOT NULL AND a.action IN ('SCHEDULED_CHECK','MANUAL_CHECK')
              AND a.activityTime >= :since AND a.resultStatus IN ('ERROR','TIMEOUT')
            """)
    List<Object[]> monitorsWithFailureSince(@Param("since") String since);

    /** Bir türün seçili monitörleri için zaman sıralı durum dizisi (monitör, zaman, durum) — geçiş sayımı Java'da. */
    @Query("""
            SELECT a.monitorId, a.activityTime, a.resultStatus FROM ActivityLog a
            WHERE a.monitorType = :type AND a.monitorId IN :ids AND a.action IN ('SCHEDULED_CHECK','MANUAL_CHECK')
              AND a.activityTime >= :since AND a.resultStatus IN ('SUCCESS','WARNING','ERROR','TIMEOUT')
            ORDER BY a.monitorId, a.activityTime, a.id
            """)
    List<Object[]> statusSequence(@Param("type") String type, @Param("ids") List<Long> ids, @Param("since") String since);
}
