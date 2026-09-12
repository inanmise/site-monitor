package com.sitemonitor.repository;

import com.sitemonitor.model.UserPushDelivery;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface UserPushDeliveryRepository extends JpaRepository<UserPushDelivery, Long> {

    /** Outbox: worker'ın alacağı satırlar (yaş sırasıyla — açlık olmasın). */
    List<UserPushDelivery> findTop50ByStatusOrderByIdAsc(String status);

    /** Dedupe ön-kontrolü (yarışta son söz UNIQUE kısıtın — bu yalnız gürültüsüz erken çıkış). */
    boolean existsByAlertEventIdAndDedupeKeyAndUsername(Long alertEventId, String dedupeKey, String username);
    /** Olaysız takım bildirimi (Zayıf Algoritma Raporu) dedupe'u — alertEventId yok. */
    boolean existsByDedupeKeyAndUsername(String dedupeKey, String username);

    /** Alarm listesi "kanal durumu" çipi (2026-09-12, #16): sayfadaki alarmlar için durum × adet, tek sorgu. */
    @Query("SELECT d.alertEventId, d.status, COUNT(d) FROM UserPushDelivery d WHERE d.alertEventId IN :ids GROUP BY d.alertEventId, d.status")
    List<Object[]> countByAlertEventIdInGroupByStatus(@Param("ids") java.util.Collection<Long> ids);

    /** Saat tavanı: kullanıcı başına son bir saatte yazılmış GÖNDERİLEBİLİR satır sayısı. */
    @Query("""
           SELECT COUNT(d) FROM UserPushDelivery d
           WHERE d.username = :username AND d.createdAt >= :since
             AND d.status NOT IN ('SKIPPED_NO_ID','SKIPPED_USER_OPT_OUT')
           """)
    long countRecentForUser(@Param("username") String username, @Param("since") String since);

    /** Çözüm simetrisi: olaya daha önce gerçekten push GİTTİ Mİ? */
    boolean existsByAlertEventIdAndStatus(Long alertEventId, String status);

    /** Alarm modalı: olayın webhook teslimatları (e-posta satırlarının kanal-ayrımlı eşleniği). */
    List<UserPushDelivery> findByAlertEventIdOrderByIdAsc(Long alertEventId);

    /**
     * Teslimat günlüğü — K11 süzgeçleri. CAST kuralı {@code MonitorChangeLogRepository.search}
     * ile aynı: Postgres null metin parametresini tip bilgisi olmadan bytea bağlar ve LOWER(bytea)
     * yoktur → sorgu 500 verir. Yalnız null geçebilen metin parametreleri sarılır.
     */
    @Query("""
           SELECT d FROM UserPushDelivery d
           WHERE (:username IS NULL OR LOWER(d.username) = LOWER(CAST(:username AS string)))
             AND (:teamId IS NULL OR d.teamId = :teamId)
             AND (:monitorType IS NULL OR d.monitorType = :monitorType)
             AND (:level IS NULL OR d.alertLevel = :level)
             AND (:status IS NULL OR d.status = :status)
             AND (:trigger IS NULL OR d.trigger = :trigger)
             AND (:notificationId IS NULL OR d.notificationId = :notificationId)
             AND (:q IS NULL OR LOWER(d.monitorName) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%')))
             AND (:from IS NULL OR d.createdAt >= :from)
             AND (:to IS NULL OR d.createdAt <= :to)
           ORDER BY d.id DESC
           """)
    Page<UserPushDelivery> search(@Param("username") String username,
                                  @Param("teamId") Long teamId,
                                  @Param("monitorType") String monitorType,
                                  @Param("level") String level,
                                  @Param("status") String status,
                                  @Param("trigger") String trigger,
                                  @Param("notificationId") String notificationId,
                                  @Param("q") String q,
                                  @Param("from") String from,
                                  @Param("to") String to,
                                  Pageable pageable);

    /** E3 istatistik şeridi: pencere içi durum dağılımı. */
    @Query("""
           SELECT d.status, COUNT(d) FROM UserPushDelivery d
           WHERE d.createdAt >= :since GROUP BY d.status
           """)
    List<Object[]> countByStatusSince(@Param("since") String since);

    /** KPI kartları takım kırılımı (2026-09-12): pencere içi durum × takım sayıları. teamId null = alarmın takımı yok (test/sistem). */
    @Query("""
           SELECT d.teamId, d.status, COUNT(d) FROM UserPushDelivery d
           WHERE d.createdAt >= :since GROUP BY d.teamId, d.status
           """)
    List<Object[]> countByTeamAndStatusSince(@Param("since") String since);

    /** E4 devre-kesici sağlık sinyali: pencere içi ardışık olmayan toplam FAILED. */
    long countByStatusAndCreatedAtGreaterThanEqual(String status, String since);

    /** Test tavanı: son bir dakikadaki TEST satırları. */
    long countByTriggerAndCreatedAtGreaterThanEqual(String trigger, String since);

    /**
     * Retention temizliği RetentionCatalog motorundan koşar (doğrudan SQL); bu metot test/elle
     * temizlik içindir. Tx kuralı: deleteBy… @Transactional + int (open-in-view=false tuzağı).
     */
    @Modifying
    @Transactional
    int deleteByCreatedAtBefore(String cutoff);
}
