package com.certmonitor.repository;

import com.certmonitor.model.DnsMonitor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface DnsMonitorRepository extends JpaRepository<DnsMonitor, Long> {
    List<DnsMonitor> findByActiveTrue();
    List<DnsMonitor> findAllByOrderByNameAsc();
    /** Storm denominatörü — cert-türevi (envanter) satırları çift saymamak için yalnız standalone aktifler. */
    long countByStandaloneTrueAndActiveTrue();
    Optional<DnsMonitor> findFirstByDomainOrderByIdAsc(String domain);
    List<DnsMonitor> findByStandaloneTrueAndActiveTrue();
    Optional<DnsMonitor> findFirstByDomainAndRecordTypeAndStandaloneTrue(String domain, String recordType);

    /** [teamId, grup adı, sayı] — TAKIM-bazlı grup listesi (boş/null hariç); satır çekmeden DB-side GROUP BY. */
    @Query("SELECT m.teamId, m.groupName, COUNT(m) FROM DnsMonitor m WHERE m.groupName IS NOT NULL AND m.groupName <> '' GROUP BY m.teamId, m.groupName")
    List<Object[]> groupCountsByTeam();

    /** Bir TAKIMIN grup adını yeniden adlandır (yalnız o takımın monitörleri). Caller'da @Transactional zorunlu. */
    @Modifying
    @Query("UPDATE DnsMonitor m SET m.groupName = :newName WHERE m.groupName = :oldName AND ((:teamId IS NULL AND m.teamId IS NULL) OR m.teamId = :teamId)")
    int renameGroupForTeam(@Param("teamId") Long teamId, @Param("oldName") String oldName, @Param("newName") String newName);
}
