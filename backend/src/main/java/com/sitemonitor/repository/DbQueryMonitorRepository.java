package com.sitemonitor.repository;

import com.sitemonitor.model.DbQueryMonitor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface DbQueryMonitorRepository extends JpaRepository<DbQueryMonitor, Long> {

    List<DbQueryMonitor> findByActiveTrue();
    long countByActiveTrue();
    /** Takım kapsamlı fırtına eşiği paydası (StormService). */
    long countByTeamIdAndActiveTrue(Long teamId);
    List<DbQueryMonitor> findAllByOrderByNameAsc();
    List<DbQueryMonitor> findByTeamId(Long teamId);
    /** Rehber ve Notlar hedef yetkisi (MonitorTargetTeams) — hedef = izleme adı. */
    List<DbQueryMonitor> findByName(String name);
    Optional<DbQueryMonitor> findFirstByNameOrderByIdAsc(String name);

    /** Bağlantıyı kullanan izlemeler (silme reddi, sağlık paneli "kullanan izlemeler"). */
    List<DbQueryMonitor> findByProfileId(Long profileId);
    long countByProfileId(Long profileId);

    /**
     * Aynı ad (büyük-küçük harf duyarsız, kırpılmış) ile (kendisi hariç) başka izleme var mı — TÜM takımlarda (inceleme
     * 2026-10-31, K1): ad alarm anahtarıdır; alarm geçmişi / rehber / bastırma adla eşlenir. PostgreSQL'de yarışı
     * {@code ux_dbqm_name_lower} tekil indeksi keser ({@code DbQueryMonitorSchemaPatch}).
     */
    @Query("SELECT COUNT(m) > 0 FROM DbQueryMonitor m WHERE LOWER(TRIM(m.name)) = LOWER(TRIM(:name)) AND "
         + "(:excludeId IS NULL OR m.id <> :excludeId)")
    boolean existsDuplicateName(@Param("name") String name, @Param("excludeId") Long excludeId);

    /** [teamId, grup adı, sayı] — takım bazlı grup listesi (boş / null hariç). */
    @Query("SELECT m.teamId, m.groupName, COUNT(m) FROM DbQueryMonitor m "
         + "WHERE m.groupName IS NOT NULL AND m.groupName <> '' GROUP BY m.teamId, m.groupName")
    List<Object[]> groupCountsByTeam();

    /** Bir TAKIMIN grup adını yeniden adlandır. Çağıranda @Transactional zorunlu. */
    @Modifying
    @Query("UPDATE DbQueryMonitor m SET m.groupName = :newName WHERE LOWER(m.groupName) = LOWER(:oldName) "
         + "AND ((:teamId IS NULL AND m.teamId IS NULL) OR m.teamId = :teamId)")
    int renameGroupForTeam(@Param("teamId") Long teamId, @Param("oldName") String oldName, @Param("newName") String newName);

    /** Bildirim grubu kullanım sorgusu (silme / taşıma ekranı) — tarama yolunda değil. */
    List<DbQueryMonitor> findByNotificationGroupId(Long notificationGroupId);

    /** Zamanlayıcının bir sonraki planlı çalışmayı yazması — hedefli (varlık kaydı eş zamanlı düzenlemeyi ezmesin). */
    @Transactional
    @Modifying
    @Query("UPDATE DbQueryMonitor m SET m.nextRunAt = :next WHERE m.id = :id")
    int updateNextRunAt(@Param("id") Long id, @Param("next") String next);
}
