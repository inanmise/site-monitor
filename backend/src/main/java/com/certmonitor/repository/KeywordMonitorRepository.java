package com.certmonitor.repository;

import com.certmonitor.model.KeywordMonitor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface KeywordMonitorRepository extends JpaRepository<KeywordMonitor, Long> {
    List<KeywordMonitor> findByActiveTrue();
    long countByActiveTrue();
    List<KeywordMonitor> findAllByOrderByNameAsc();
    Optional<KeywordMonitor> findFirstByUrlAndKeywordOrderByIdAsc(String url, String keyword);

    /** Aynı URL + anahtar kelime (case-insensitive) + takım için (kendisi hariç) başka bir keyword monitörü var mı.
     *  Aynılık anahtarı url+keyword+team: aynı URL'i FARKLI kelimeyle izlemek meşrudur, engellenmez
     *  (HttpMonitorRepository.existsDuplicate deseninin keyword'lü ikizi). */
    @Query("SELECT COUNT(m) > 0 FROM KeywordMonitor m WHERE LOWER(m.url) = LOWER(:url) AND "
         + "LOWER(m.keyword) = LOWER(:keyword) AND "
         + "((:teamId IS NULL AND m.teamId IS NULL) OR m.teamId = :teamId) AND "
         + "(:excludeId IS NULL OR m.id <> :excludeId)")
    boolean existsDuplicate(@Param("url") String url, @Param("keyword") String keyword,
                            @Param("teamId") Long teamId, @Param("excludeId") Long excludeId);

    /** [teamId, grup adı, sayı] — TAKIM-bazlı grup listesi (boş/null hariç); satır çekmeden DB-side GROUP BY. */
    @Query("SELECT m.teamId, m.groupName, COUNT(m) FROM KeywordMonitor m WHERE m.groupName IS NOT NULL AND m.groupName <> '' GROUP BY m.teamId, m.groupName")
    List<Object[]> groupCountsByTeam();

    /** Bir TAKIMIN grup adını yeniden adlandır (yalnız o takımın monitörleri). Caller'da @Transactional zorunlu. */
    @Modifying
    @Query("UPDATE KeywordMonitor m SET m.groupName = :newName WHERE LOWER(m.groupName) = LOWER(:oldName) AND ((:teamId IS NULL AND m.teamId IS NULL) OR m.teamId = :teamId)")
    int renameGroupForTeam(@Param("teamId") Long teamId, @Param("oldName") String oldName, @Param("newName") String newName);
}
