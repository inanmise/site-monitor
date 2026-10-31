package com.sitemonitor.repository;

import com.sitemonitor.model.DbQueryMonitor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface DbQueryMonitorRepository extends JpaRepository<DbQueryMonitor, Long> {

    List<DbQueryMonitor> findByActiveTrue();
    long countByActiveTrue();
    /** Takım kapsamlı fırtına eşiği paydası (StormService). */
    long countByTeamIdAndActiveTrue(Long teamId);
    List<DbQueryMonitor> findAllByOrderByNameAsc();
    List<DbQueryMonitor> findByTeamId(Long teamId);
    /** Rehber ve Notlar hedef yetkisi (MonitorTargetTeams) — hedef = izleme adı. */
    List<DbQueryMonitor> findByName(String name);
    Optional<DbQueryMonitor> findFirstByNameOrderByIdAsc(String name);

    /** Bağlantıyı kullanan izlemeler (silme reddi, sağlık paneli "kullanan izlemeler"). */
    List<DbQueryMonitor> findByProfileId(Long profileId);
    long countByProfileId(Long profileId);

    /**
     * Aynı ad (büyük-küçük harf duyarsız, kırpılmış) ile (kendisi hariç) başka izleme var mı — TÜM takımlarda (inceleme
     * 2026-10-31, K1): ad alarm anahtarıdır; alarm geçmişi / rehber / bastırma adla eşlenir. PostgreSQL'de yarışı
     * {@code ux_dbqm_name_lower} tekil indeksi keser ({@code DbQueryMonitorSchemaPatch}).
     */
    @Query("SELECT COUNT(m) > 0 FROM DbQueryMonitor m WHERE LOWER(TRIM(m.name)) = LOWER(TRIM(:name)) AND "
         + "(:excludeId IS NULL OR m.id <> :excludeId)")
    boolean existsDuplicateName(@Param("name") String name, @Param("excludeId") Long excludeId);

    /** [teamId, grup adı, sayı] — takım bazlı grup listesi (boş / null hariç). */
    @Query("SELECT m.teamId, m.groupName, COUNT(m) FROM DbQueryMonitor m "
         + "WHERE m.groupName IS NOT NULL AND m.groupName <> '' GROUP BY m.teamId, m.groupName")
    List<Object[]> groupCountsByTeam();

    /** Bir TAKIMIN grup adını yeniden adlandır. Çağıranda @Transactional zorunlu. */
    @Modifying
    @Query("UPDATE DbQueryMonitor m SET m.groupName = :newName WHERE LOWER(m.groupName) = LOWER(:oldName) "
         + "AND ((:teamId IS NULL AND m.teamId IS NULL) OR m.teamId = :teamId)")
    int renameGroupForTeam(@Param("teamId") Long teamId, @Param("oldName") String oldName, @Param("newName") String newName);

    /** Bildirim grubu kullanım sorgusu (silme / taşıma ekranı) — tarama yolunda değil. */
    List<DbQueryMonitor> findByNotificationGroupId(Long notificationGroupId);

    /**
     * Zamanlayıcının bir sonraki planlı çalışmayı yazması — hedefli (varlık kaydı eş zamanlı düzenlemeyi ezmesin). Kolon
     * varlıkta {@code updatable = false} — yerel SQL (2026-10-31 inceleme D4 / K6; {@code AlertEventRepository} emsali).
     */
    @Transactional
    @Modifying
    @Query(value = "UPDATE dbquery_monitors SET next_run_at = :next WHERE id = :id", nativeQuery = true)
    int updateNextRunAt(@Param("id") Long id, @Param("next") String next);

    // ── Zamanlayıcı (run ajanı, 2026-10-31 inceleme O5): ardışık atlama — hedefli, varlık kaydı ezmez ──────────────────

    /** Bir çalıştırma ATLANDI (izin yok / iç hata): sayaç + 1, ilk atlanma zamanı (yoksa) {@code now}. */
    @Transactional
    @Modifying
    @Query(value = "UPDATE dbquery_monitors SET skipped_runs = COALESCE(skipped_runs, 0) + 1, "
         + "skipped_since = COALESCE(skipped_since, :now) WHERE id = :id", nativeQuery = true)
    int markDbQuerySkipped(@Param("id") Long id, @Param("now") String now);

    // ── Ardışık değerlendirilemeyen çalıştırma (r177, H-2) — hedefli, varlık kaydı ezmez ────────────────────────────

    /** Zamanlanmış çalıştırma yapılandırma sorunuyla değerlendirilemedi: sayaç + 1. */
    @Transactional
    @Modifying
    @Query(value = "UPDATE dbquery_monitors SET unknown_runs = COALESCE(unknown_runs, 0) + 1 WHERE id = :id", nativeQuery = true)
    int markDbQueryUnknown(@Param("id") Long id);

    /** Değerlendirilebilen çalıştırma (ya da tanım değişti): sayaç sıfırlanır. */
    @Transactional
    @Modifying
    @Query(value = "UPDATE dbquery_monitors SET unknown_runs = 0 WHERE id = :id AND unknown_runs IS NOT NULL AND unknown_runs <> 0",
           nativeQuery = true)
    int clearDbQueryUnknown(@Param("id") Long id);

    /** Bir çalıştırma gerçekleşti: ardışık atlama sıfırlanır. */
    @Transactional
    @Modifying
    @Query(value = "UPDATE dbquery_monitors SET skipped_runs = 0, skipped_since = NULL WHERE id = :id", nativeQuery = true)
    int clearDbQuerySkipped(@Param("id") Long id);
}
