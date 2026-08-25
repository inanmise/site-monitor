package com.sitemonitor.repository;

import com.sitemonitor.model.PortMonitor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PortMonitorRepository extends JpaRepository<PortMonitor, Long> {
    List<PortMonitor> findByActiveTrue();
    List<PortMonitor> findByStandaloneTrueAndActiveTrue();
    /** Storm denominatörü — cert-türevi (envanter) satırları çift saymamak için yalnız standalone aktifler. */
    long countByStandaloneTrueAndActiveTrue();
    List<PortMonitor> findAllByOrderByNameAsc();
    Optional<PortMonitor> findFirstByHostAndPortOrderByIdAsc(String host, int port);

    /** [teamId, grup adı, sayı] — TAKIM-bazlı grup listesi (boş/null hariç); satır çekmeden DB-side GROUP BY. */
    @Query("SELECT m.teamId, m.groupName, COUNT(m) FROM PortMonitor m WHERE m.groupName IS NOT NULL AND m.groupName <> '' GROUP BY m.teamId, m.groupName")
    List<Object[]> groupCountsByTeam();

    /** Bir TAKIMIN grup adını yeniden adlandır (yalnız o takımın monitörleri). Caller'da @Transactional zorunlu. */
    @Modifying
    @Query("UPDATE PortMonitor m SET m.groupName = :newName WHERE LOWER(m.groupName) = LOWER(:oldName) AND ((:teamId IS NULL AND m.teamId IS NULL) OR m.teamId = :teamId)")
    int renameGroupForTeam(@Param("teamId") Long teamId, @Param("oldName") String oldName, @Param("newName") String newName);

    /** Bildirim grubu KULLANIM sorgusu — grup silinmeden once "nerede kullaniliyor" ve
     *  toplu tasima icin. Talep uzerine calisir (silme/kullanim ekrani), sweep yolunda DEGIL. */
    java.util.List<PortMonitor> findByNotificationGroupId(Long notificationGroupId);

}
