package com.sitemonitor.repository;

import com.sitemonitor.model.PageMonitor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PageMonitorRepository extends JpaRepository<PageMonitor, Long> {
    List<PageMonitor> findByActiveTrue();
    long countByActiveTrue();

    /**
     * Bu domain için AKTİF bir sayfa izlemesi var mı — sertifika sağlığındaki "karışık içerik"
     * satırı, varsa o izlemenin sonucundan beslenir (K3), yoksa istemli kontrol önerilir.
     * URL şema/port/yol taşıdığı için içerik araması yapılır (host tam eşleşmesi kaçırırdı).
     */
    boolean existsByUrlContainingIgnoreCaseAndActiveTrue(String domain);

    /** Aynı domain için AKTİF sayfa izlemeleri — karışık içerik satırı bunların sonucundan beslenir. */
    java.util.List<PageMonitor> findByUrlContainingIgnoreCaseAndActiveTrue(String domain);
    List<PageMonitor> findAllByOrderByNameAsc();
    Optional<PageMonitor> findFirstByUrlOrderByIdAsc(String url);

    /** Aynı URL (case-insensitive) + takım için (kendisi hariç) başka bir sayfa monitörü var mı. */
    @Query("SELECT COUNT(m) > 0 FROM PageMonitor m WHERE LOWER(m.url) = LOWER(:url) AND "
         + "((:teamId IS NULL AND m.teamId IS NULL) OR m.teamId = :teamId) AND "
         + "(:excludeId IS NULL OR m.id <> :excludeId)")
    boolean existsDuplicate(@Param("url") String url, @Param("teamId") Long teamId, @Param("excludeId") Long excludeId);

    /** [teamId, grup adı, sayı] — TAKIM-bazlı grup listesi (boş/null hariç); satır çekmeden DB-side GROUP BY. */
    @Query("SELECT m.teamId, m.groupName, COUNT(m) FROM PageMonitor m WHERE m.groupName IS NOT NULL AND m.groupName <> '' GROUP BY m.teamId, m.groupName")
    List<Object[]> groupCountsByTeam();

    /** Bir TAKIMIN grup adını yeniden adlandır (yalnız o takımın monitörleri). Caller'da @Transactional zorunlu. */
    @Modifying
    @Query("UPDATE PageMonitor m SET m.groupName = :newName WHERE LOWER(m.groupName) = LOWER(:oldName) AND ((:teamId IS NULL AND m.teamId IS NULL) OR m.teamId = :teamId)")
    int renameGroupForTeam(@Param("teamId") Long teamId, @Param("oldName") String oldName, @Param("newName") String newName);

    /** Bildirim grubu KULLANIM sorgusu — grup silinmeden once "nerede kullaniliyor" ve
     *  toplu tasima icin. Talep uzerine calisir (silme/kullanim ekrani), sweep yolunda DEGIL. */
    java.util.List<PageMonitor> findByNotificationGroupId(Long notificationGroupId);

}
