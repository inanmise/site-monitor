package com.sitemonitor.repository;

import com.sitemonitor.model.PageMonitor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PageMonitorRepository extends JpaRepository<PageMonitor, Long> {
    List<PageMonitor> findByActiveTrue();

    /** Çok pod Sürüm 3-3 (paket B): dağıtık kontrol gönderiminin HAFİF okuması — aktif izlemelerin [id, aralık (sn)] (betik /
     *  başlık / gizli sütunları okunmaz). Yalnız ana anahtar açıkken, tarama liderinin gönderim tıkında çağrılır. */
    @Query("SELECT m.id, m.intervalSeconds FROM PageMonitor m WHERE m.active = true")
    List<Object[]> findCheckDispatchRows();
    long countByActiveTrue();
    /** Takım kapsamlı fırtına eşiği paydası (StormService, 2026-09-29) — yalnız o takımın aktif kayıtları. */
    long countByTeamIdAndActiveTrue(Long teamId);

    /**
     * Bu domain için AKTİF bir sayfa izlemesi var mı — sertifika sağlığındaki "karışık içerik"
     * satırı, varsa o izlemenin sonucundan beslenir (K3), yoksa istemli kontrol önerilir.
     * URL şema/port/yol taşıdığı için içerik araması yapılır (host tam eşleşmesi kaçırırdı).
     */
    boolean existsByUrlContainingIgnoreCaseAndActiveTrue(String domain);

    /** Aynı domain için AKTİF sayfa izlemeleri — karışık içerik satırı bunların sonucundan beslenir. */
    java.util.List<PageMonitor> findByUrlContainingIgnoreCaseAndActiveTrue(String domain);

    /**
     * Host'u TAM OLARAK bu alan adı olan aktif sayfa izlemeleri (DATA-9, 2026-10-22). {@code LIKE %domain%} yalnız ön
     * süzgeçtir: {@code example.com} araması {@code https://www.example.com/}'u, {@code bank.com} araması
     * {@code internetbank.com.tr}'yi de buluyordu — sertifika sağlığının "karışık içerik" satırı BAŞKA bir host'un (çoğu
     * kez başka takımın) sayfasından besleniyordu. Eşleşme {@code URI.getHost()} ile, büyük/küçük harf duyarsız, sondaki
     * nokta yok sayılır; ayrıştırılamayan URL eşleşmez.
     */
    default java.util.List<PageMonitor> findActiveForHost(String domain) {
        if (domain == null || domain.isBlank()) return List.of();
        java.util.List<PageMonitor> out = new java.util.ArrayList<>();
        for (PageMonitor m : findByUrlContainingIgnoreCaseAndActiveTrue(domain.trim())) {
            if (m != null && urlHostMatches(m.getUrl(), domain)) out.add(m);
        }
        return out;
    }

    /** {@link #findActiveForHost} boş değil mi — sağlık listesinin "sayfa izlemesi var" bayrağı için. */
    default boolean existsActiveForHost(String domain) {
        return !findActiveForHost(domain).isEmpty();
    }

    /** URL'nin host'u {@code domain} mi (büyük/küçük harf duyarsız, sondaki nokta yok sayılır). */
    static boolean urlHostMatches(String url, String domain) {
        if (url == null || domain == null) return false;
        String host;
        try {
            host = java.net.URI.create(url.trim()).getHost();
        } catch (RuntimeException e) {
            return false;
        }
        if (host == null) return false;
        String a = host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
        String d = domain.trim();
        if (d.endsWith(".")) d = d.substring(0, d.length() - 1);
        return a.equalsIgnoreCase(d);
    }
    List<PageMonitor> findAllByOrderByNameAsc();
    /** Rehber & Notlar hedef yetkisi (MonitorTargetTeams). */
    List<PageMonitor> findByUrl(String url);
    Optional<PageMonitor> findFirstByUrlOrderByIdAsc(String url);

    /** Aynı URL (case-insensitive) + takım için (kendisi hariç) başka bir sayfa monitörü var mı. */
    @Query("SELECT COUNT(m) > 0 FROM PageMonitor m WHERE LOWER(m.url) = LOWER(:url) AND "
         + "((:teamId IS NULL AND m.teamId IS NULL) OR m.teamId = :teamId) AND "
         + "(:excludeId IS NULL OR m.id <> :excludeId)")
    boolean existsDuplicate(@Param("url") String url, @Param("teamId") Long teamId, @Param("excludeId") Long excludeId);

    /** [teamId, grup adı, sayı] — TAKIM-bazlı grup listesi (boş/null hariç); satır çekmeden DB-side GROUP BY. */
    @Query("SELECT m.teamId, m.groupName, COUNT(m) FROM PageMonitor m WHERE m.groupName IS NOT NULL AND m.groupName <> '' GROUP BY m.teamId, m.groupName")
    List<Object[]> groupCountsByTeam();

    /** Bir TAKIMIN grup adını yeniden adlandır (yalnız o takımın monitörleri). Caller'da @Transactional zorunlu. */
    @Modifying
    @Query("UPDATE PageMonitor m SET m.groupName = :newName WHERE LOWER(m.groupName) = LOWER(:oldName) AND ((:teamId IS NULL AND m.teamId IS NULL) OR m.teamId = :teamId)")
    int renameGroupForTeam(@Param("teamId") Long teamId, @Param("oldName") String oldName, @Param("newName") String newName);

    /** Bildirim grubu KULLANIM sorgusu — grup silinmeden once "nerede kullaniliyor" ve
     *  toplu tasima icin. Talep uzerine calisir (silme/kullanim ekrani), sweep yolunda DEGIL. */
    java.util.List<PageMonitor> findByNotificationGroupId(Long notificationGroupId);

}
