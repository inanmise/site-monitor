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

    /**
     * Yönetim ekranı, ÇOK takımlı kapsam — TEK sorgu.
     *
     * <p>Takım başına ayrı sorgu N+1 üretiyordu; global admin/AUDIT'te kapsam TÜM takımlar
     * olduğu için bu, ekranı açan her yöneticide takım sayısı kadar sorgu demekti (tek pod).
     *
     * <p><b>Boş koleksiyonla ÇAĞIRMAYIN:</b> JPQL {@code IN ()} üretir ve sağlayıcıya bağlı
     * sözdizimi hatası verir — çağıran tarafta koru (şablon kütüphanesindeki aynı tuzak).
     */
    List<NotificationGroup> findByTeamIdInOrderByTeamIdAscNameAsc(java.util.Collection<Long> teamIds);

    List<NotificationGroup> findByTeamIdInAndActiveTrueOrderByTeamIdAscNameAsc(java.util.Collection<Long> teamIds);

    /** Zincirin ikinci halkası: takımın aktif varsayılan grubu. */
    Optional<NotificationGroup> findFirstByTeamIdAndIsDefaultTrueAndActiveTrue(Long teamId);

    /**
     * Takım içinde ad benzersizliği (kendisi hariç — güncellemede kullanılır).
     *
     * <p><b>YALNIZ AKTİF gruplara bakar.</b> Yumuşak silme satırı bırakıyor; aktiflik filtresi
     * olmadan silinmiş bir grubun adı SONSUZA DEK rezerve kalıyordu. Kullanıcı açısından o grup
     * yok — listede görünmüyor, seçilemiyor — ama aynı adı yeniden kullanmak istediğinde
     * "bu takımda zaten var" diyen, hiçbir yerde göremediği bir kayda çarpıyordu.
     *
     * <p>Rezerve tutmanın koruduğu bir şey de yok: bir grup ancak KULLANIMDA DEĞİLKEN silinebiliyor
     * ({@code NotificationGroupUsageService}) ve silindikten sonra referans kazanamıyor (seçici
     * pasif grupları sunmuyor). Yani silinmiş satır kalıcı olarak sahipsizdir.
     */
    @Query("SELECT COUNT(g) > 0 FROM NotificationGroup g WHERE g.teamId = :teamId "
         + "AND g.active = true "
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
    // clearAutomatically: toplu UPDATE satirlari dogrudan veritabaninda degistirir; kalicilik
    // baglami temizlenmezse ayni islem icinde okunan entity'ler BAYAT is_default tasir.
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE NotificationGroup g SET g.isDefault = false "
         + "WHERE g.teamId = :teamId AND g.id <> :keepId AND g.isDefault = true")
    int clearOtherDefaults(@Param("teamId") Long teamId, @Param("keepId") Long keepId);

    /** Grup kalıcı silinirse (admin) onu kullanan monitör alanları temizlensin diye sayım. */
    long countByTeamIdAndActiveTrue(Long teamId);
}
