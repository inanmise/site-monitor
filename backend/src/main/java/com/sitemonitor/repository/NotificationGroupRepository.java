package com.sitemonitor.repository;

import com.sitemonitor.model.NotificationGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface NotificationGroupRepository extends JpaRepository<NotificationGroup, Long> {

    /** Yönetim ekranı: bir takımın grupları (pasifler dahil — "silinmiş" rozetiyle gösterilir). */
    List<NotificationGroup> findByTeamIdOrderByNameAsc(Long teamId);

    /** Seçici ve çözümleme: yalnız AKTİF gruplar. */
    List<NotificationGroup> findByTeamIdAndActiveTrueOrderByNameAsc(Long teamId);

    /** Zincirin ikinci halkası: takımın aktif varsayılan grubu. */
    Optional<NotificationGroup> findFirstByTeamIdAndIsDefaultTrueAndActiveTrue(Long teamId);

    /** Takım içinde ad benzersizliği (kendisi hariç — güncellemede kullanılır). */
    @Query("SELECT COUNT(g) > 0 FROM NotificationGroup g WHERE g.teamId = :teamId "
         + "AND LOWER(g.name) = LOWER(:name) AND (:excludeId IS NULL OR g.id <> :excludeId)")
    boolean existsByTeamAndName(@Param("teamId") Long teamId, @Param("name") String name,
                                @Param("excludeId") Long excludeId);

    /**
     * Sweep performansı: bir turda geçen TÜM takımların varsayılan gruplarını TEK sorguda getirir.
     * Takım başına ayrı sorgu N+1 üretirdi (teamNameMap deseniyle aynı gerekçe).
     */
    @Query("SELECT g FROM NotificationGroup g WHERE g.teamId IN :teamIds "
         + "AND g.isDefault = true AND g.active = true")
    List<NotificationGroup> findDefaultsForTeams(@Param("teamIds") java.util.Collection<Long> teamIds);

    /** Bir takımın diğer varsayılanlarını indirir — "en fazla bir varsayılan" kısıtının uygulaması. */
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Modifying
    @Query("UPDATE NotificationGroup g SET g.isDefault = false "
         + "WHERE g.teamId = :teamId AND g.id <> :keepId AND g.isDefault = true")
    int clearOtherDefaults(@Param("teamId") Long teamId, @Param("keepId") Long keepId);

    /** Grup kalıcı silinirse (admin) onu kullanan monitör alanları temizlensin diye sayım. */
    long countByTeamIdAndActiveTrue(Long teamId);
}
