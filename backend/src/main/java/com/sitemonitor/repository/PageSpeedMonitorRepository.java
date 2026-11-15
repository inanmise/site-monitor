package com.sitemonitor.repository;

import com.sitemonitor.model.PageSpeedMonitor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PageSpeedMonitorRepository extends JpaRepository<PageSpeedMonitor, Long> {

    List<PageSpeedMonitor> findByActiveTrue();

    /** Öksüz alarm temizliğinin kimlik kümesi (2026-11-06, performans PF3 #3): TÜM izlemelerin URL'leri (aktif + pasif) — tam varlık
     *  ({@code findAll}) yerine tek kolon; temizlik 5 dk'da bir koşar. */
    @Query("SELECT DISTINCT m.url FROM PageSpeedMonitor m WHERE m.url IS NOT NULL")
    List<String> findOrphanCheckKeys();

    /** Çok pod Sürüm 3-3 (paket B): dağıtık kontrol gönderiminin HAFİF okuması — aktif izlemelerin [id, ham aralık (sn) — gönderici türün sınırlarına kırpar] (betik /
     *  başlık / gizli sütunları okunmaz). Yalnız ana anahtar açıkken, tarama liderinin gönderim tıkında çağrılır.
     *  2026-11-15 (Sayfa Hızı 2. sürüm madde 14): + zamanlama kolonları [2 kip, 3 cron, 4 pencereler, 5 yasak pencereler,
     *  6 tatil atla, 7 ek tarihler, 8 sonraki plan] — sentetik izlemenin izdüşümüyle AYNI düzen (ilk iki kolonun sırası değişmez). */
    @Query("SELECT m.id, m.intervalSeconds, m.scheduleMode, m.cronExpression, m.windowsJson, m.blackoutJson, "
         + "m.skipHolidays, m.skipDatesJson, m.nextRunAt FROM PageSpeedMonitor m WHERE m.active = true")
    List<Object[]> findCheckDispatchRows();

    /**
     * Zamanlayıcının planı (2026-11-15, madde 14): bir sonraki planlı ölçüm ({@code next_run_at}) — hedefli, varlık kaydı
     * ezmez ({@code updatable = false}). {@code null} = plan sıfırlandı (sonraki tıkta yeni tanımdan kurulur).
     */
    @org.springframework.transaction.annotation.Transactional
    @Modifying
    @Query(value = "UPDATE pagespeed_monitors SET next_run_at = :next WHERE id = :id", nativeQuery = true)
    int updateNextRunAt(@Param("id") Long id, @Param("next") String next);

    /** Planı İLK kez kuran çağıran: yalnız {@code next_run_at} hâlâ NULL ise yazar (klasik gövde ile gönderim yarışında tek kazanan). */
    @org.springframework.transaction.annotation.Transactional
    @Modifying
    @Query(value = "UPDATE pagespeed_monitors SET next_run_at = :next WHERE id = :id AND next_run_at IS NULL", nativeQuery = true)
    int updateNextRunAtIfNull(@Param("id") Long id, @Param("next") String next);

    /** Planı ÖNE ÇEKEN çağıran: yalnız okunan değer hâlâ yerindeyse yazar (0 = başka çağıran değiştirdi). */
    @org.springframework.transaction.annotation.Transactional
    @Modifying
    @Query(value = "UPDATE pagespeed_monitors SET next_run_at = :next WHERE id = :id AND next_run_at = :prev", nativeQuery = true)
    int updateNextRunAtIfEquals(@Param("id") Long id, @Param("prev") String prev, @Param("next") String next);

    /** Tatil takvimi değişince planı yeniden kurulacak aktif izlemeler: [id, zamanlama kipi] (hafif okuma). */
    @Query("SELECT m.id, m.scheduleMode FROM PageSpeedMonitor m WHERE m.active = true AND m.skipHolidays = true")
    List<Object[]> findHolidayScheduledRows();
    long countByActiveTrue();
    /** Takım kapsamlı fırtına eşiği paydası (StormService, 2026-09-29) — yalnız o takımın aktif kayıtları. */
    long countByTeamIdAndActiveTrue(Long teamId);
    List<PageSpeedMonitor> findAllByOrderByNameAsc();
    /** Rehber & Notlar hedef yetkisi (MonitorTargetTeams). */
    List<PageSpeedMonitor> findByUrl(String url);
    Optional<PageSpeedMonitor> findFirstByUrlOrderByIdAsc(String url);

    /** Aynı URL (case-insensitive) + takım için (kendisi hariç) başka bir sayfa hızı monitörü var mı. */
    @Query("SELECT COUNT(m) > 0 FROM PageSpeedMonitor m WHERE LOWER(m.url) = LOWER(:url) AND "
         + "((:teamId IS NULL AND m.teamId IS NULL) OR m.teamId = :teamId) AND "
         + "(:excludeId IS NULL OR m.id <> :excludeId)")
    boolean existsDuplicate(@Param("url") String url, @Param("teamId") Long teamId, @Param("excludeId") Long excludeId);

    /** [teamId, grup adı, sayı] — TAKIM-bazlı grup listesi (boş/null hariç); satır çekmeden DB-side GROUP BY. */
    @Query("SELECT m.teamId, m.groupName, COUNT(m) FROM PageSpeedMonitor m "
         + "WHERE m.groupName IS NOT NULL AND m.groupName <> '' GROUP BY m.teamId, m.groupName")
    List<Object[]> groupCountsByTeam();

    /** Bir TAKIMIN grup adını yeniden adlandır (yalnız o takımın monitörleri). Caller'da @Transactional zorunlu. */
    @Modifying
    @Query("UPDATE PageSpeedMonitor m SET m.groupName = :newName WHERE LOWER(m.groupName) = LOWER(:oldName) "
         + "AND ((:teamId IS NULL AND m.teamId IS NULL) OR m.teamId = :teamId)")
    int renameGroupForTeam(@Param("teamId") Long teamId, @Param("oldName") String oldName, @Param("newName") String newName);

    /** Bildirim grubu KULLANIM sorgusu — grup silinmeden once "nerede kullaniliyor" ve
     *  toplu tasima icin. Talep uzerine calisir (silme/kullanim ekrani), sweep yolunda DEGIL. */
    java.util.List<PageSpeedMonitor> findByNotificationGroupId(Long notificationGroupId);

    /** Bildirim grubu → referans sayısı (toplu kullanım — kullanılmayan grup tespiti, 2026-11-12). */
    @Query("SELECT m.notificationGroupId, COUNT(m) FROM PageSpeedMonitor m WHERE m.notificationGroupId IS NOT NULL GROUP BY m.notificationGroupId")
    java.util.List<Object[]> countByNotificationGroup();

}
