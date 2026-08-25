package com.sitemonitor.repository;

import com.sitemonitor.model.AppUser;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {
    // Username eşleştirmesi CASE-INSENSITIVE (DB UPPER): canonical saklama BÜYÜK harf olsa da yazılan/eski
    // satırların case'i ne olursa olsun aynı kullanıcıya çözülür (Melih "N12345"/"n12345" tek satır) +
    // LDAP re-provision eski satırı bulur (dup yaratmaz). DB UPPER iki tarafta → collation-tutarlı.
    @Query("SELECT u FROM AppUser u WHERE UPPER(u.username) = UPPER(:username)")
    Optional<AppUser> findByUsername(@Param("username") String username);
    @Query("SELECT u FROM AppUser u WHERE UPPER(u.username) = UPPER(:username) AND u.active = true")
    Optional<AppUser> findByUsernameAndActiveTrue(@Param("username") String username);

    /** SICAK YOL (her /api/** isteği): tek-oturum supersede kontrolü için YALNIZ activeSessionId
     *  kolonunu çeker — tam AppUser entity'sini hidrate etmez ve EAGER teamIds (app_user_teams)
     *  join'ini tetiklemez. Eski findByUsername iki SELECT'e mal oluyordu; bu tek hafif indexli okuma. */
    @Query("SELECT u.activeSessionId FROM AppUser u WHERE UPPER(u.username) = UPPER(:username)")
    Optional<String> findActiveSessionIdByUsername(@Param("username") String username);
    Optional<AppUser> findByEmployeeId(String employeeId);     // sicil (AD cn)
    List<AppUser> findByTeamIdOrderByUsernameAsc(Long teamId);
    List<AppUser> findAllByOrderByUsernameAsc();
    @Query("SELECT COUNT(u) > 0 FROM AppUser u WHERE UPPER(u.username) = UPPER(:username)")
    boolean existsByUsername(@Param("username") String username);
    boolean existsByTeamId(Long teamId);

    // ── Çoklu takım üyeliği (app_user_teams) ──
    /** Belirli bir takıma ÜYE (birincil veya ek) tüm kullanıcılar. */
    @Query("SELECT DISTINCT u FROM AppUser u JOIN u.teamIds tid WHERE tid = :teamId ORDER BY u.username ASC")
    List<AppUser> findByMembershipTeamId(@Param("teamId") Long teamId);

    /** Verilen takım kümesinden HERHANGİ birine üye kullanıcılar (scope filtresi). */
    @Query("SELECT DISTINCT u FROM AppUser u JOIN u.teamIds tid WHERE tid IN :teamIds ORDER BY u.username ASC")
    List<AppUser> findByAnyTeamId(@Param("teamIds") Collection<Long> teamIds);

    /** Takım silme guard'ı: takıma üye (birincil veya ek) kullanıcı var mı. */
    @Query("SELECT COUNT(u) > 0 FROM AppUser u JOIN u.teamIds tid WHERE tid = :teamId")
    boolean existsByMembershipTeamId(@Param("teamId") Long teamId);
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
    @Query("UPDATE AppUser u SET u.lastSeenAt = :ts WHERE UPPER(u.username) = UPPER(:username) AND u.activeSessionId = :sid")
    int touchLastSeen(@Param("username") String username, @Param("sid") String sid, @Param("ts") String ts);

    /**
     * Başarısız giriş damgası — TEK atomik statement (entity yükle-kaydet DEĞİL).
     *
     * <p>Gerekçe: brute-force sırasında aynı kullanıcıya paralel denemeler gelir; oku-artır-kaydet
     * yapılsaydı iki denemeden biri diğerinin sayacını ezerdi (lost update) ve sayaç gerçek deneme
     * sayısının altında kalırdı. {@code COALESCE} şart: kolon mevcut satırlarda NULL olabilir
     * (DEFAULT yalnız yeni satırlara uygulanır).
     */
    @Modifying
    @Query("UPDATE AppUser u SET u.failedSinceLogin = COALESCE(u.failedSinceLogin, 0) + 1, "
        + "u.lastFailedLoginAt = :ts, u.lastFailedLoginIp = :ip, u.lastFailedLoginReason = :reason "
        + "WHERE UPPER(u.username) = UPPER(:username)")
    int bumpFailedLogin(@Param("username") String username, @Param("ts") String ts,
                        @Param("ip") String ip, @Param("reason") String reason);

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
        + "(:teamId IS NULL OR u.teamId = :teamId OR :teamId IN (SELECT tid FROM u.teamIds tid)) "
        + "ORDER BY u.username ASC")
    Page<AppUser> findFiltered(@Param("q") String q,
                               @Param("systemRole") String systemRole,
                               @Param("orgRole") String orgRole,
                               @Param("teamId") Long teamId,
                               Pageable pageable);
}
