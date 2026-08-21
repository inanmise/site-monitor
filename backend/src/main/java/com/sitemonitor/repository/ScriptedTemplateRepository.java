package com.sitemonitor.repository;

import com.sitemonitor.model.ScriptedTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Şablon kütüphanesi sorguları.
 *
 * <p>Türetilmiş uzun metot adları yerine açık {@code @Query}: kapsam kuralı (null = Genel)
 * metot adına sığmıyor ve {@code findByTeamIdIsNullOrTeamIdIn…} gibi bir ad okunduğunda
 * "hangisi Genel?" sorusunu cevaplamıyor.
 */
public interface ScriptedTemplateRepository extends JpaRepository<ScriptedTemplate, Long> {

    /** GENEL şablonlar — {@code teamId} null olanlar; her yetkili kullanıcı görür. */
    @Query("select t from ScriptedTemplate t where t.active = true and t.teamId is null order by t.name asc")
    List<ScriptedTemplate> findActiveGeneral();

    /**
     * Verilen takımların şablonları.
     *
     * <p><b>TUZAK:</b> boş koleksiyonla ÇAĞIRMA — JPQL {@code in ()} sağlayıcıya bağlı bir
     * sözdizimi hatasıdır. Takımsız kullanıcıda çağıran taraf bu sorguyu atlamalı.
     */
    @Query("select t from ScriptedTemplate t where t.active = true and t.teamId in :teamIds order by t.name asc")
    List<ScriptedTemplate> findActiveByTeams(@Param("teamIds") Collection<Long> teamIds);

    /** Global görüntüleyici (admin/AUDIT) için tümü. */
    @Query("select t from ScriptedTemplate t where t.active = true order by t.name asc")
    List<ScriptedTemplate> findAllActive();

    /** Çöp kutusu — yalnız admin. En son silinen üstte. */
    @Query("select t from ScriptedTemplate t where t.active = false order by t.deletedAt desc")
    List<ScriptedTemplate> findTrash();

    /** Seeder idempotensi + eski {@code tpl:<builtinKey>} değerlerinin çözümü. */
    Optional<ScriptedTemplate> findByBuiltinKey(String builtinKey);

    /**
     * Aynı KAPSAM içinde ad çakışması. Genel (teamId null) ve her takım ayrı ad uzayıdır:
     * iki farklı takımın "Login akışı" adlı şablonu olması meşrudur.
     */
    @Query("""
           select count(t) from ScriptedTemplate t
            where t.active = true
              and lower(t.name) = lower(:name)
              and ((:teamId is null and t.teamId is null) or t.teamId = :teamId)
              and (:excludeId is null or t.id <> :excludeId)
           """)
    long countDuplicateInScope(@Param("name") String name,
                               @Param("teamId") Long teamId,
                               @Param("excludeId") Long excludeId);
}
