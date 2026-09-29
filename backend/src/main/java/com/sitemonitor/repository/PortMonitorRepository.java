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
    /**
     * Liste ucu (2026-09-27): SİLİNMEMİŞ standalone satırlar — duraklatılmışlar ({@code active=false}) DÂHİL.
     * Yerini aldığı {@code findByStandaloneTrueAndActiveTrue} duraklatılanı da gizliyordu (silme ile aynı durum).
     */
    List<PortMonitor> findByStandaloneTrueAndDeletedAtIsNull();

    /** Mükerrer guard'ı: aynı host:port için DURAKLATILMIŞ (silinmemiş) standalone satır var mı? Artık listede görünür. */
    boolean existsByHostAndPortAndStandaloneTrueAndActiveFalseAndDeletedAtIsNull(String host, int port);
    boolean existsByHostAndPortAndStandaloneTrueAndActiveFalseAndDeletedAtIsNullAndIdNot(String host, int port, Long id);
    /** Storm denominatörü — cert-türevi (envanter) satırları çift saymamak için yalnız standalone aktifler. */
    long countByStandaloneTrueAndActiveTrue();
    /** Takım kapsamlı fırtına eşiği paydası (StormService, 2026-09-29) — yalnız o takımın aktif kayıtları. */
    long countByStandaloneTrueAndActiveTrueAndTeamId(Long teamId);
    List<PortMonitor> findAllByOrderByNameAsc();
    Optional<PortMonitor> findFirstByHostAndPortOrderByIdAsc(String host, int port);
    /** Mükerrer guard'ı: HERHANGİ bir aktif satır var mı? (findFirst…ByIdAsc en ESKİ satırı döndürüyor;
     *  o satır soft-delete ise daha yeni aktif kopya görünmez kalıyor, üçüncü kopya oluşuyordu.) */
    boolean existsByHostAndPortAndActiveTrue(String host, int port);
    boolean existsByHostAndPortAndActiveTrueAndIdNot(String host, int port, Long id);
    /**
     * Mükerrer İLETİSİ (2026-09-28): host:port kurulum genelinde tekil (takımlar arası) — engelleyen satırın SAHİBİ
     * takımı iletide adıyla söylenir. KARAR yukarıdaki exists* sorgularında kalır; bunlar yalnız sahibi bulur.
     */
    List<PortMonitor> findByHostAndPortAndActiveTrue(String host, int port);
    List<PortMonitor> findByHostAndPortAndStandaloneTrueAndActiveFalseAndDeletedAtIsNull(String host, int port);

    /** [teamId, grup adı, sayı] — TAKIM-bazlı grup listesi (boş/null hariç); satır çekmeden DB-side GROUP BY. */
    // Silinmiş standalone satır grup sayısına girmez (2026-09-27) — yoksa görünmeyen izleme grubu "dolu" gösterirdi.
    @Query("SELECT m.teamId, m.groupName, COUNT(m) FROM PortMonitor m WHERE m.groupName IS NOT NULL AND m.groupName <> '' AND m.deletedAt IS NULL GROUP BY m.teamId, m.groupName")
    List<Object[]> groupCountsByTeam();

    /** Bir TAKIMIN grup adını yeniden adlandır (yalnız o takımın monitörleri). Caller'da @Transactional zorunlu. */
    @Modifying
    @Query("UPDATE PortMonitor m SET m.groupName = :newName WHERE LOWER(m.groupName) = LOWER(:oldName) AND ((:teamId IS NULL AND m.teamId IS NULL) OR m.teamId = :teamId)")
    int renameGroupForTeam(@Param("teamId") Long teamId, @Param("oldName") String oldName, @Param("newName") String newName);

    /** Bildirim grubu KULLANIM sorgusu — grup silinmeden once "nerede kullaniliyor" ve
     *  toplu tasima icin. Talep uzerine calisir (silme/kullanim ekrani), sweep yolunda DEGIL. */
    java.util.List<PortMonitor> findByNotificationGroupId(Long notificationGroupId);

}
