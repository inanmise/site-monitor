package com.certmonitor.repository;

import com.certmonitor.model.AppUser;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {
    Optional<AppUser> findByUsername(String username);
    Optional<AppUser> findByUsernameAndActiveTrue(String username);
    Optional<AppUser> findByEmployeeId(String employeeId);     // sicil (AD cn)
    List<AppUser> findByTeamIdOrderByUsernameAsc(Long teamId);
    List<AppUser> findAllByOrderByUsernameAsc();
    boolean existsByUsername(String username);
    boolean existsByTeamId(Long teamId);
    long countBySystemRoleAndActiveTrue(String systemRole);

    /** Tek-oturum izleme: o an login (aktif oturumu olan) kullanıcılar. Admin "Sonlandır" sonrası
     *  konan sentinel ('TERMINATED:...') aktif sayılmaz, hariç tutulur. */
    @Query("SELECT u FROM AppUser u WHERE u.activeSessionId IS NOT NULL "
        + "AND u.activeSessionId NOT LIKE 'TERMINATED:%' ORDER BY u.username ASC")
    List<AppUser> findAllWithActiveSession();

    /** Açılışta stale tek-oturum işaretlerini topluca temizler — in-memory oturumlar restart'ı
     *  yaşamaz, ama DB'deki activeSessionId kalır; aksi halde restart sonrası aktif sayım şişer ve
     *  login'de yanlış "başka yerde aktif oturum" onayı çıkar. Temizlenen satır sayısını döner. */
    @Modifying
    @Query("UPDATE AppUser u SET u.activeSessionId = null WHERE u.activeSessionId IS NOT NULL")
    int clearAllActiveSessions();

    /** Oturum ping'i: yalnız kullanıcının GÜNCEL oturumu için lastSeenAt'i tazeler (tek statement). */
    @Modifying
    @Query("UPDATE AppUser u SET u.lastSeenAt = :ts WHERE u.username = :username AND u.activeSessionId = :sid")
    int touchLastSeen(@Param("username") String username, @Param("sid") String sid, @Param("ts") String ts);

    // ── Faz 3b: manager (müdür) → astları / yönettiği takımlar ──
    List<AppUser> findByManagerId(Long managerId);
    boolean existsByManagerId(Long managerId);

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
