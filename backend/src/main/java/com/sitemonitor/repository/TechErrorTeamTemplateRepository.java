package com.sitemonitor.repository;

import com.sitemonitor.model.TechErrorTeamTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Takımın teknik hata raporu şablon kütüphanesi (2026-10-18, D17). Liste satırı şablon gövdesini taşımaz ({@link Meta}). */
public interface TechErrorTeamTemplateRepository extends JpaRepository<TechErrorTeamTemplate, Long> {

    /** Liste satırı — şablon JSON'u YOK (20 kayıtlık liste 20 × 64 KB taşımasın). */
    interface Meta {
        Long getId();
        Long getTeamId();
        String getName();
        Boolean getDefaultEntry();
        String getTemplateLang();
        int getSectionCount();
        int getFieldCount();
        int getSlotCount();
        Instant getCreatedAt();
        String getCreatedByName();
        Instant getUpdatedAt();
        String getUpdatedByName();
        Long getVersion();
    }

    List<Meta> findByTeamId(Long teamId);

    Optional<TechErrorTeamTemplate> findByIdAndTeamId(Long id, Long teamId);

    Optional<TechErrorTeamTemplate> findFirstByTeamIdAndDefaultEntryTrueOrderByIdAsc(Long teamId);

    long countByTeamId(Long teamId);

    boolean existsByTeamIdAndNameKey(Long teamId, String nameKey);

    boolean existsByTeamIdAndNameKeyAndIdNot(Long teamId, String nameKey, Long id);

    /**
     * Takımın kütüphane yazımlarını SIRALAR (kilit): takım satırı işlem boyunca kilitlenir — aynı takıma iki eşzamanlı
     * "varsayılan yap" / ekleme sırayla çalışır (en çok bir varsayılan, 20 kayıt sınırı ve ad tekilliği kesin). Takım
     * yoksa boş liste. Çağıranın işleminde çalışır (servis {@code @Transactional}).
     */
    @Query(value = "SELECT id FROM teams WHERE id = :teamId FOR UPDATE", nativeQuery = true)
    List<Long> lockTeamRow(@Param("teamId") Long teamId);

    /**
     * Takımın diğer varsayılanını düşürür (yeni varsayılan yazılmadan ÖNCE, aynı işlemde). Sürüm artar: o satırı eski
     * sürümle tutan pencere bir sonraki yazımda 409 alır (satır değişti).
     */
    @Modifying
    @Transactional
    @Query("UPDATE TechErrorTeamTemplate t SET t.defaultEntry = false, t.version = t.version + 1 "
            + "WHERE t.teamId = :teamId AND t.defaultEntry = true AND t.id <> :keepId")
    int clearOtherDefaults(@Param("teamId") Long teamId, @Param("keepId") Long keepId);

    /** Göç taraması (2026-10-18): yalnız kimlik + sürüm (şablon gövdesi kayıt kayıt okunur — tek seferde 64 KB × N değil). */
    interface MigrationRow {
        Long getId();
        Long getVersion();
    }

    @Query("SELECT t.id AS id, t.version AS version FROM TechErrorTeamTemplate t ORDER BY t.id")
    List<MigrationRow> findMigrationRows();

    /**
     * Tek seferlik şablon göçünün KOŞULLU yazımı (2026-10-18, anomali soruları): okunan sürüm hâlâ aynıysa şablon gövdesi
     * yazılır, sürüm artar (o satırı açık tutan pencere sonraki kaydında 409 alır), "son güncelleyen" göçün adı olur. Sayımlar
     * ve dil değişmez (göç alan / yuva / bölüm eklemez). 0 satır = arada ekip kaydetti → çağıran yeniden okur.
     */
    @Modifying
    @Transactional
    @Query("UPDATE TechErrorTeamTemplate t SET t.templateJson = :json, t.version = t.version + 1, t.updatedAt = :at, "
            + "t.updatedById = null, t.updatedByName = :actor WHERE t.id = :id AND t.version = :version")
    int migrateTemplate(@Param("id") Long id, @Param("version") Long version, @Param("json") String json,
                        @Param("at") Instant at, @Param("actor") String actor);
}
