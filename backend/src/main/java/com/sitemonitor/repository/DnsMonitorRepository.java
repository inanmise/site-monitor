package com.sitemonitor.repository;

import com.sitemonitor.model.DnsMonitor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface DnsMonitorRepository extends JpaRepository<DnsMonitor, Long> {
    List<DnsMonitor> findByActiveTrue();
    List<DnsMonitor> findAllByOrderByNameAsc();
    /** Rehber & Notlar hedef yetkisi (MonitorTargetTeams): alan adının TÜM DNS satırları (kayıt tipleri, envanter-türevi + standalone). */
    List<DnsMonitor> findByDomain(String domain);
    /** Storm denominatörü — cert-türevi (envanter) satırları çift saymamak için yalnız standalone aktifler. */
    long countByStandaloneTrueAndActiveTrue();
    /** Takım kapsamlı fırtına eşiği paydası (StormService, 2026-09-29) — yalnız o takımın aktif kayıtları. */
    long countByStandaloneTrueAndActiveTrueAndTeamId(Long teamId);
    Optional<DnsMonitor> findFirstByDomainOrderByIdAsc(String domain);
    /** Liste ucu (2026-09-27): SİLİNMEMİŞ standalone satırlar — duraklatılmışlar DÂHİL (bkz. PortMonitorRepository). */
    List<DnsMonitor> findByStandaloneTrueAndDeletedAtIsNull();
    /** Eski sürümden kalmış yumuşak silinmiş satırlar — yalnız tek seferlik kalıcı temizlik okur (DeletedRecordsPurge, 2026-10-07). */
    List<DnsMonitor> findByDeletedAtIsNotNullOrderByIdAsc();

    /** Mükerrer guard'ı: aynı (domain, tip) için CANLI (aktif ya da duraklatılmış, silinmemiş) standalone satır. */
    Optional<DnsMonitor> findFirstByDomainAndRecordTypeAndStandaloneTrueAndDeletedAtIsNull(String domain, String recordType);
    // 2026-10-07: silinmiş satırı canlandırma sorgusu KALDIRILDI — bağımsız DNS silmesi kalıcı, yeniden ekleme yeni satır açar.

    /** [teamId, grup adı, sayı] — TAKIM-bazlı grup listesi (boş/null hariç); satır çekmeden DB-side GROUP BY. */
    // Silinmiş standalone satır grup sayısına girmez (2026-09-27).
    @Query("SELECT m.teamId, m.groupName, COUNT(m) FROM DnsMonitor m WHERE m.groupName IS NOT NULL AND m.groupName <> '' AND m.deletedAt IS NULL GROUP BY m.teamId, m.groupName")
    List<Object[]> groupCountsByTeam();

    /** Bir TAKIMIN grup adını yeniden adlandır (yalnız o takımın monitörleri). Caller'da @Transactional zorunlu. */
    @Modifying
    @Query("UPDATE DnsMonitor m SET m.groupName = :newName WHERE LOWER(m.groupName) = LOWER(:oldName) AND ((:teamId IS NULL AND m.teamId IS NULL) OR m.teamId = :teamId)")
    int renameGroupForTeam(@Param("teamId") Long teamId, @Param("oldName") String oldName, @Param("newName") String newName);

    /** Bildirim grubu KULLANIM sorgusu — grup silinmeden once "nerede kullaniliyor" ve
     *  toplu tasima icin. Talep uzerine calisir (silme/kullanim ekrani), sweep yolunda DEGIL. */
    java.util.List<DnsMonitor> findByNotificationGroupId(Long notificationGroupId);

}
