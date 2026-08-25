package com.sitemonitor.repository;

import com.sitemonitor.model.CertificateInventory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CertificateInventoryRepository extends JpaRepository<CertificateInventory, Long> {
    List<CertificateInventory> findByDomainIn(Collection<String> domains);

    List<CertificateInventory> findByActiveTrueOrderByDomainAsc();
    List<CertificateInventory> findByTeamIdAndActiveTrueOrderByDomainAsc(Long teamId);
    List<CertificateInventory> findByTeamIdOrderByDomainAsc(Long teamId);
    Optional<CertificateInventory> findByDomain(String domain);
    boolean existsByDomain(String domain);
    boolean existsByTeamIdAndActiveTrue(Long teamId);
    long countByActiveTrue();

    /** Aylık envanter raporunun "Silinmiş" KPI'ı — soft-delete edilmiş kayıt sayısı. */
    long countByDeletedAtIsNotNull();
    List<CertificateInventory> findByUgTeamIdAndActiveTrueOrderByDomainAsc(Long ugTeamId);

    // Faz 3b — çok-takım kapsamı (müdür/PO): teamId VEYA ugTeamId ∈ ids
    List<CertificateInventory> findByTeamIdInAndActiveTrueOrderByDomainAsc(Collection<Long> teamIds);
    List<CertificateInventory> findByUgTeamIdInAndActiveTrueOrderByDomainAsc(Collection<Long> ugTeamIds);
    List<CertificateInventory> findByTeamIdInAndDeletedAtIsNullOrderByDomainAsc(Collection<Long> teamIds);
    List<CertificateInventory> findByUgTeamIdInAndDeletedAtIsNullOrderByDomainAsc(Collection<Long> ugTeamIds);

    // Soft-delete aware
    List<CertificateInventory> findAllByOrderByDomainAsc();
    List<CertificateInventory> findByDeletedAtIsNullOrderByDomainAsc();
    List<CertificateInventory> findByDeletedAtIsNotNullOrderByDomainAsc();
    List<CertificateInventory> findByTeamIdAndDeletedAtIsNullOrderByDomainAsc(Long teamId);
    List<CertificateInventory> findByUgTeamIdAndDeletedAtIsNullOrderByDomainAsc(Long ugTeamId);
    /** Haftalık erişilebilirlik raporu — takımın aktif + silinmemiş domainleri. */
    List<CertificateInventory> findByTeamIdAndActiveTrueAndDeletedAtIsNullOrderByDomainAsc(Long teamId);
    boolean existsByTeamIdAndActiveTrueAndDeletedAtIsNull(Long teamId);
    boolean existsByUgTeamIdAndActiveTrueAndDeletedAtIsNull(Long ugTeamId);

    // ── İzleme grupları (cert = 7. tür) ────────────────────────────────────────
    /** [teamId, grup adı, sayı] — takım-bazlı; silinmemiş kayıtlar; DB-side GROUP BY. */
    @Query("SELECT c.teamId, c.groupName, COUNT(c) FROM CertificateInventory c WHERE c.groupName IS NOT NULL AND c.groupName <> '' AND c.deletedAt IS NULL GROUP BY c.teamId, c.groupName")
    List<Object[]> groupCountsByTeam();

    /** Bir TAKIMIN grup adını yeniden adlandır. Caller'da @Transactional zorunlu. */
    @Modifying
    @Query("UPDATE CertificateInventory c SET c.groupName = :newName WHERE LOWER(c.groupName) = LOWER(:oldName) AND ((:teamId IS NULL AND c.teamId IS NULL) OR c.teamId = :teamId)")
    int renameGroupForTeam(@Param("teamId") Long teamId, @Param("oldName") String oldName, @Param("newName") String newName);

    /** Bildirim grubu KULLANIM sorgusu — grup silinmeden once "nerede kullaniliyor" ve
     *  toplu tasima icin. Talep uzerine calisir (silme/kullanim ekrani), sweep yolunda DEGIL. */
    java.util.List<CertificateInventory> findByNotificationGroupId(Long notificationGroupId);

}
