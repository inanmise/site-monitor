package com.certmonitor.repository;

import com.certmonitor.model.AppUser;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {
    Optional<AppUser> findByUsername(String username);
    Optional<AppUser> findByUsernameAndActiveTrue(String username);
    List<AppUser> findByTeamIdOrderByUsernameAsc(Long teamId);
    List<AppUser> findAllByOrderByUsernameAsc();
    boolean existsByUsername(String username);
    boolean existsByTeamId(Long teamId);
    long countBySystemRoleAndActiveTrue(String systemRole);

    /** Haftalık rapor PO bildirimi — kontağı olmayan takımlar için fallback. */
    List<AppUser> findByTeamIdAndOrgRoleAndActiveTrue(Long teamId, String orgRole);

    /**
     * Admin Users ekranı için filtreli + sayfalı arama. q (lowercased "%..%")
     * username/displayName/email/employeeId üzerinde OR arar; systemRole/orgRole/teamId
     * eşitlik filtresi. null parametreler ilgili koşulu atlar (AuditLog.findFiltered deseni).
     */
    @Query("SELECT u FROM AppUser u WHERE "
        + "(:q IS NULL OR LOWER(u.username) LIKE :q OR LOWER(u.displayName) LIKE :q "
        +              "OR LOWER(u.email) LIKE :q OR LOWER(u.employeeId) LIKE :q) AND "
        + "(:systemRole IS NULL OR u.systemRole = :systemRole) AND "
        + "(:orgRole IS NULL OR u.orgRole = :orgRole) AND "
        + "(:teamId IS NULL OR u.teamId = :teamId) "
        + "ORDER BY u.username ASC")
    Page<AppUser> findFiltered(@Param("q") String q,
                               @Param("systemRole") String systemRole,
                               @Param("orgRole") String orgRole,
                               @Param("teamId") Long teamId,
                               Pageable pageable);
}
