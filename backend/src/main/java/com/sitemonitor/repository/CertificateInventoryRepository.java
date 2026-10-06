package com.sitemonitor.repository;

import com.sitemonitor.model.CertificateInventory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CertificateInventoryRepository extends JpaRepository<CertificateInventory, Long> {
    List<CertificateInventory> findByDomainIn(Collection<String> domains);

    List<CertificateInventory> findByActiveTrueOrderByDomainAsc();
    /** Aktif envanterin alan adları (ham; normalizasyon çağıranda) — İzleme Panosu envanter-pasif kuralı (2026-10-01,
     *  performans: tam entity yerine tek sütun). */
    // 2026-10-06: elle yüklenen sertifika kayıtları (cert_source = MANUAL) HARİÇ — envanter türevi Port/DNS süpürmesi
    // ağ hedefi olmayan bu kayıtları atlıyor (SchedulerService), pano kuralı süpürmeyi BİREBİR yansıtmalı.
    @Query("SELECT c.domain FROM CertificateInventory c WHERE c.active = true AND c.domain IS NOT NULL"
            + " AND (c.certSource IS NULL OR c.certSource <> 'MANUAL')")
    List<String> findActiveDomains();

    // ── Elle yüklenen sertifikalar (2026-10-06) ─────────────────────────────────────────────────
    /** Kaynağa göre silinmemiş kayıtlar (manuel sertifika listesi). */
    List<CertificateInventory> findByCertSourceAndDeletedAtIsNullOrderByDomainAsc(String certSource);
    /** Kaynağa göre AKTİF kayıtlar (manuel sertifika çevrim-dışı süpürmesi). */
    List<CertificateInventory> findByCertSourceAndActiveTrueOrderByDomainAsc(String certSource);
    /**
     * Verilen adlardan envanterde (silinmiş dahil) zaten bulunanlar — küçük harfle; takip adı önerisinin çakışma
     * denetimi tek sorguda yapılır (satır başına sorgu yok). Çağıran boş koleksiyonla çağırmamalı.
     */
    @Query("SELECT LOWER(c.domain) FROM CertificateInventory c WHERE LOWER(c.domain) IN :keys")
    List<String> findExistingDomainsLower(@Param("keys") Collection<String> keys);
    /** Aktif envanter (alan adı, SY takımı) çiftleri — alarm gürültü analizinin kapsam/takım eşlemesi (2026-10-01,
     *  performans: tam entity yerine iki sütun). Sütunlar: {@code [domain, teamId]}. */
    @Query("SELECT c.domain, c.teamId FROM CertificateInventory c WHERE c.active = true")
    List<Object[]> findActiveDomainTeams();
    List<CertificateInventory> findByTeamIdAndActiveTrueOrderByDomainAsc(Long teamId);
    List<CertificateInventory> findByTeamIdOrderByDomainAsc(Long teamId);
    /** Takımın UG olarak bağlı olduğu TÜM kayıtlar (pasif/silinmiş dahil) — takım taşıma/silme etkisi (2026-09-28). */
    List<CertificateInventory> findByUgTeamIdOrderByDomainAsc(Long ugTeamId);
    Optional<CertificateInventory> findByDomain(String domain);
    boolean existsByDomain(String domain);
    /** Rename çakışması: DB UNIQUE büyük/küçük harfe duyarlı, envanter ise küçük harf saklar. */
    boolean existsByDomainIgnoreCase(String domain);
    /**
     * Mükerrer alan adı (2026-09-28): çakışan kaydın KENDİSİ — 409 yanıtı sahibi takımı adıyla söyler
     * ({@code DOMAIN_EXISTS}). Harf duyarsız: eski satırlar karışık harfle kalmış olabilir; birden çoksa en eskisi.
     */
    Optional<CertificateInventory> findFirstByDomainIgnoreCaseOrderByIdAsc(String domain);
    boolean existsByTeamIdAndActiveTrue(Long teamId);
    long countByActiveTrue();
    /** Takım kapsamlı fırtına eşiği paydası (StormService, 2026-09-29) — yalnız o takımın aktif kayıtları. */
    long countByTeamIdAndActiveTrue(Long teamId);

    /**
     * Fırtına paydası (2026-10-06): AĞDAN denetlenen aktif kayıtlar — dosyadan yüklenen (cert_source = MANUAL) sertifikalar
     * erişilebilirlik / port / DNS alarmı üretmez, fırtına üyesi olamaz; paydaya girerlerse yüzde eşiği sessizce büyürdü.
     * Manuel satır yokken {@link #countByActiveTrue()} ile aynı sayı.
     */
    @Query("SELECT COUNT(i) FROM CertificateInventory i WHERE i.active = true"
            + " AND (i.certSource IS NULL OR i.certSource <> 'MANUAL')")
    long countNetworkActive();

    /** {@link #countNetworkActive()}'in takım kapsamlı hâli — manuel satır yokken {@link #countByTeamIdAndActiveTrue} ile aynı. */
    @Query("SELECT COUNT(i) FROM CertificateInventory i WHERE i.teamId = :teamId AND i.active = true"
            + " AND (i.certSource IS NULL OR i.certSource <> 'MANUAL')")
    long countNetworkActiveByTeam(@Param("teamId") Long teamId);
    /** Sahipsiz (takımsız) aktif alanlar — yapılandırma sağlığı kartı (2026-09-12). */
    long countByActiveTrueAndTeamIdIsNull();

    /** Aylık envanter raporunun "Silinmiş" KPI'ı — soft-delete edilmiş kayıt sayısı. */
    long countByDeletedAtIsNotNull();
    List<CertificateInventory> findByUgTeamIdAndActiveTrueOrderByDomainAsc(Long ugTeamId);

    /** Görüş kapsamındaki domain ADLARI — `CertificateController.requireViewableDomain` ile AYNI
     *  kural (birincil VEYA UG takımı), ama tekil değil LİSTE için ve yalnız `domain` kolonunu
     *  çeken hafif projeksiyon: rozet uçları her pano yüklemesinde çağrılıyor, tüm envanter
     *  satırlarını nesneye çevirmek gereksiz. Soft-delete SÜZÜLMEZ — tekil kapı da süzmüyor.
     *  Çağıran boş `teamIds` ile ÇAĞIRMAMALI (`IN ()` üretir): kapsamsız oturum zaten hiçbir
     *  şey göremez, orada erkenden boş liste dönülür. */
    @Query("SELECT c.domain FROM CertificateInventory c WHERE c.teamId IN :teamIds OR c.ugTeamId IN :teamIds")
    List<String> findDomainsForTeams(@Param("teamIds") Collection<Long> teamIds);

    // Faz 3b — çok-takım kapsamı (müdür/PO): teamId VEYA ugTeamId ∈ ids
    List<CertificateInventory> findByTeamIdInAndActiveTrueOrderByDomainAsc(Collection<Long> teamIds);
    List<CertificateInventory> findByUgTeamIdInAndActiveTrueOrderByDomainAsc(Collection<Long> ugTeamIds);
    List<CertificateInventory> findByTeamIdInAndDeletedAtIsNullOrderByDomainAsc(Collection<Long> teamIds);
    List<CertificateInventory> findByUgTeamIdInAndDeletedAtIsNullOrderByDomainAsc(Collection<Long> ugTeamIds);

    // Soft-delete aware
    List<CertificateInventory> findAllByOrderByDomainAsc();
    List<CertificateInventory> findByDeletedAtIsNullOrderByDomainAsc();
    List<CertificateInventory> findByDeletedAtIsNotNullOrderByDomainAsc();
    List<CertificateInventory> findByTeamIdAndDeletedAtIsNullOrderByDomainAsc(Long teamId);
    List<CertificateInventory> findByUgTeamIdAndDeletedAtIsNullOrderByDomainAsc(Long ugTeamId);
    /** Haftalık erişilebilirlik raporu — takımın aktif + silinmemiş domainleri. */
    List<CertificateInventory> findByTeamIdAndActiveTrueAndDeletedAtIsNullOrderByDomainAsc(Long teamId);
    boolean existsByTeamIdAndActiveTrueAndDeletedAtIsNull(Long teamId);
    boolean existsByUgTeamIdAndActiveTrueAndDeletedAtIsNull(Long ugTeamId);

    // ── İzleme grupları (cert = 7. tür) ────────────────────────────────────────
    /** [teamId, grup adı, sayı] — takım-bazlı; silinmemiş kayıtlar; DB-side GROUP BY. */
    @Query("SELECT c.teamId, c.groupName, COUNT(c) FROM CertificateInventory c WHERE c.groupName IS NOT NULL AND c.groupName <> '' AND c.deletedAt IS NULL GROUP BY c.teamId, c.groupName")
    List<Object[]> groupCountsByTeam();

    /** [platform kodu, sayı] — silinmemiş kayıtlar; Ayarlar → Platformlar kullanım sayacı + silme koruması (2026-09-22). */
    @Query("SELECT c.platform, COUNT(c) FROM CertificateInventory c WHERE c.platform IS NOT NULL AND c.deletedAt IS NULL GROUP BY c.platform")
    List<Object[]> platformCounts();

    /** Bir TAKIMIN grup adını yeniden adlandır. Caller'da @Transactional zorunlu. */
    @Modifying
    @Query("UPDATE CertificateInventory c SET c.groupName = :newName WHERE LOWER(c.groupName) = LOWER(:oldName) AND ((:teamId IS NULL AND c.teamId IS NULL) OR c.teamId = :teamId)")
    int renameGroupForTeam(@Param("teamId") Long teamId, @Param("oldName") String oldName, @Param("newName") String newName);

    /** Bildirim grubu KULLANIM sorgusu — grup silinmeden once "nerede kullaniliyor" ve
     *  toplu tasima icin. Talep uzerine calisir (silme/kullanim ekrani), sweep yolunda DEGIL. */
    java.util.List<CertificateInventory> findByNotificationGroupId(Long notificationGroupId);

}
