package com.sitemonitor.repository;

import com.sitemonitor.model.DomainMonitor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface DomainMonitorRepository extends JpaRepository<DomainMonitor, Long> {
    List<DomainMonitor> findByActiveTrue();

    /** Öksüz alarm temizliğinin kimlik kümesi (2026-11-06, performans PF3 #3): TÜM izlemelerin alan adları (aktif + pasif) — tam varlık
     *  ({@code findAll}) yerine tek kolon; temizlik 5 dk'da bir koşar. */
    @Query("SELECT DISTINCT m.domain FROM DomainMonitor m WHERE m.domain IS NOT NULL")
    List<String> findOrphanCheckKeys();
    long countByActiveTrue();
    /** Takım kapsamlı fırtına eşiği paydası (StormService, 2026-09-29) — yalnız o takımın aktif kayıtları. */
    long countByTeamIdAndActiveTrue(Long teamId);
    List<DomainMonitor> findAllByOrderByNameAsc();
    /** Rehber & Notlar hedef yetkisi (MonitorTargetTeams). */
    List<DomainMonitor> findByDomain(String domain);
    Optional<DomainMonitor> findFirstByDomainOrderByIdAsc(String domain);

    /** Aynı domain (case-insensitive) + takım için (kendisi hariç) başka bir domain monitörü var mı. */
    @Query("SELECT COUNT(m) > 0 FROM DomainMonitor m WHERE LOWER(m.domain) = LOWER(:domain) AND "
         + "((:teamId IS NULL AND m.teamId IS NULL) OR m.teamId = :teamId) AND "
         + "(:excludeId IS NULL OR m.id <> :excludeId)")
    boolean existsDuplicate(@Param("domain") String domain, @Param("teamId") Long teamId, @Param("excludeId") Long excludeId);

    /** [teamId, grup adı, sayı] — TAKIM-bazlı grup listesi (boş/null hariç); satır çekmeden DB-side GROUP BY. */
    @Query("SELECT m.teamId, m.groupName, COUNT(m) FROM DomainMonitor m WHERE m.groupName IS NOT NULL AND m.groupName <> '' GROUP BY m.teamId, m.groupName")
    List<Object[]> groupCountsByTeam();

    /** Bir TAKIMIN grup adını yeniden adlandır (yalnız o takımın monitörleri). Caller'da @Transactional zorunlu. */
    @Modifying
    @Query("UPDATE DomainMonitor m SET m.groupName = :newName WHERE LOWER(m.groupName) = LOWER(:oldName) AND ((:teamId IS NULL AND m.teamId IS NULL) OR m.teamId = :teamId)")
    int renameGroupForTeam(@Param("teamId") Long teamId, @Param("oldName") String oldName, @Param("newName") String newName);

    /** Bildirim grubu KULLANIM sorgusu — grup silinmeden once "nerede kullaniliyor" ve
     *  toplu tasima icin. Talep uzerine calisir (silme/kullanim ekrani), sweep yolunda DEGIL. */
    java.util.List<DomainMonitor> findByNotificationGroupId(Long notificationGroupId);

    /** Bildirim grubu → referans sayısı (toplu kullanım — kullanılmayan grup tespiti, 2026-11-12). */
    @Query("SELECT m.notificationGroupId, COUNT(m) FROM DomainMonitor m WHERE m.notificationGroupId IS NOT NULL GROUP BY m.notificationGroupId")
    java.util.List<Object[]> countByNotificationGroup();

}
