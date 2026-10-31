package com.sitemonitor.repository;

import com.sitemonitor.model.DbConnectionProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

public interface DbConnectionProfileRepository extends JpaRepository<DbConnectionProfile, Long> {

    List<DbConnectionProfile> findByTeamIdOrderByNameAsc(Long teamId);
    List<DbConnectionProfile> findByTeamIdInOrderByNameAsc(Collection<Long> teamIds);
    List<DbConnectionProfile> findAllByOrderByNameAsc();
    long countByTeamId(Long teamId);

    @Query("SELECT COUNT(p) > 0 FROM DbConnectionProfile p WHERE LOWER(p.name) = LOWER(:name) AND p.teamId = :teamId "
         + "AND (:excludeId IS NULL OR p.id <> :excludeId)")
    boolean existsDuplicate(@Param("name") String name, @Param("teamId") Long teamId, @Param("excludeId") Long excludeId);

    /** "Bağlantıyı dene" sonucu — yalnız sağlık kolonları; sürüm yalnız bilindiğinde güncellenir. */
    @Transactional
    @Modifying
    @Query("UPDATE DbConnectionProfile p SET p.lastTestAt = :at, p.lastTestOk = :ok, p.lastTestMs = :ms, "
         + "p.lastTestError = :error, p.serverVersion = COALESCE(:version, p.serverVersion) WHERE p.id = :id")
    int recordTest(@Param("id") Long id, @Param("at") String at, @Param("ok") Boolean ok, @Param("ms") Integer ms,
                   @Param("error") String error, @Param("version") String version);

    /** İzleme çalıştırması bağlantıyı kullandı (başarılı). */
    @Transactional
    @Modifying
    @Query("UPDATE DbConnectionProfile p SET p.lastUsedAt = :at, p.lastDurationMs = :ms, p.lastOkAt = :at WHERE p.id = :id")
    int recordUseOk(@Param("id") Long id, @Param("at") String at, @Param("ms") Integer ms);

    /** İzleme çalıştırması bağlantıyı kullandı (hata — maskeli metin). */
    @Transactional
    @Modifying
    @Query("UPDATE DbConnectionProfile p SET p.lastUsedAt = :at, p.lastDurationMs = :ms, p.lastErrorAt = :at, "
         + "p.lastError = :error WHERE p.id = :id")
    int recordUseError(@Param("id") Long id, @Param("at") String at, @Param("ms") Integer ms, @Param("error") String error);

    /** Takım taşıma (TeamAdminService.moveAll) — bağlantılar izlemelerle birlikte taşınır. Çağıranda @Transactional. */
    @Modifying
    @Query("UPDATE DbConnectionProfile p SET p.teamId = :to WHERE p.teamId = :from")
    int moveTeam(@Param("from") Long from, @Param("to") Long to);
}
