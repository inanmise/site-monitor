package com.sitemonitor.repository;

import com.sitemonitor.model.ScriptedMonitor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ScriptedMonitorRepository extends JpaRepository<ScriptedMonitor, Long> {
    List<ScriptedMonitor> findByActiveTrue();

    /** Çok pod Sürüm 3-3 (paket B): dağıtık kontrol gönderiminin HAFİF okuması — aktif izlemelerin [id, aralık (sn)] (betik /
     *  başlık / gizli sütunları okunmaz). Yalnız ana anahtar açıkken, tarama liderinin gönderim tıkında çağrılır.
     *  2026-10-31 (k6r D2): + zamanlama kolonları [2 kip, 3 cron, 4 pencereler, 5 yasak pencereler, 6 tatil atla, 7 ek tarihler,
     *  8 sonraki plan] — zamanlamalı izleme gönderimde de plana uyar (ilk iki kolonun sırası değişmez). */
    @Query("SELECT m.id, m.intervalSeconds, m.scheduleMode, m.cronExpression, m.windowsJson, m.blackoutJson, "
         + "m.skipHolidays, m.skipDatesJson, m.nextRunAt FROM ScriptedMonitor m WHERE m.active = true")
    List<Object[]> findCheckDispatchRows();

    /**
     * Zamanlayıcının planı (k6r D2): bir sonraki planlı koşum ({@code next_run_at}) — hedefli, varlık kaydı ezmez
     * ({@code updatable = false}). {@code null} = plan sıfırlandı (sonraki tıkta yeni tanımdan kurulur).
     */
    @org.springframework.transaction.annotation.Transactional
    @Modifying
    @Query(value = "UPDATE scripted_monitors SET next_run_at = :next WHERE id = :id", nativeQuery = true)
    int updateNextRunAt(@Param("id") Long id, @Param("next") String next);

    /** Tatil takvimi değişince planı yeniden kurulacak aktif izlemeler: [id, zamanlama kipi] (k6r D2 — hafif okuma). */
    @Query("SELECT m.id, m.scheduleMode FROM ScriptedMonitor m WHERE m.active = true AND m.skipHolidays = true")
    List<Object[]> findHolidayScheduledRows();
    long countByActiveTrue();
    /** Takım kapsamlı fırtına eşiği paydası (StormService, 2026-09-29) — yalnız o takımın aktif kayıtları. */
    long countByTeamIdAndActiveTrue(Long teamId);
    List<ScriptedMonitor> findAllByOrderByNameAsc();
    /** Rehber & Notlar hedef yetkisi (MonitorTargetTeams) — Notlar sekmesinin hedef anahtarı izleme ADI. */
    List<ScriptedMonitor> findByName(String name);
    Optional<ScriptedMonitor> findFirstByNameOrderByIdAsc(String name);

    /** Aynı ad (case-insensitive) + takım için (kendisi hariç) başka bir senaryo var mı. */
    @Query("SELECT COUNT(m) > 0 FROM ScriptedMonitor m WHERE LOWER(m.name) = LOWER(:name) AND "
         + "((:teamId IS NULL AND m.teamId IS NULL) OR m.teamId = :teamId) AND "
         + "(:excludeId IS NULL OR m.id <> :excludeId)")
    boolean existsDuplicate(@Param("name") String name, @Param("teamId") Long teamId, @Param("excludeId") Long excludeId);

    @Query("SELECT m.teamId, m.groupName, COUNT(m) FROM ScriptedMonitor m WHERE m.groupName IS NOT NULL AND m.groupName <> '' GROUP BY m.teamId, m.groupName")
    List<Object[]> groupCountsByTeam();

    @Modifying
    @Query("UPDATE ScriptedMonitor m SET m.groupName = :newName WHERE LOWER(m.groupName) = LOWER(:oldName) AND ((:teamId IS NULL AND m.teamId IS NULL) OR m.teamId = :teamId)")
    int renameGroupForTeam(@Param("teamId") Long teamId, @Param("oldName") String oldName, @Param("newName") String newName);

    /** Bildirim grubu KULLANIM sorgusu — grup silinmeden once "nerede kullaniliyor" ve
     *  toplu tasima icin. Talep uzerine calisir (silme/kullanim ekrani), sweep yolunda DEGIL. */
    java.util.List<ScriptedMonitor> findByNotificationGroupId(Long notificationGroupId);

    /**
     * Anomali guard'ının OTOMATİK kapatması (CHECKS-4, 2026-10-22) — yalnız dört alan, yalnız hâlâ AÇIK satırda. Guard,
     * sweep'in başında okunmuş (dakikalarca eski) varlığı {@code save} ediyordu; {@code ScriptedMonitor}'da sürüm alanı
     * olmadığından birleştirme TÜM kolonları eski hâliyle yazıyor, kullanıcının o arada kaydettiği script / env / yönlendirme
     * değişikliğini iz bırakmadan geri alıyordu. 0 = kullanıcı zaten kapattı (ya da satır silindi) → bildirim yok.
     */
    @org.springframework.transaction.annotation.Transactional
    @Modifying
    @Query("UPDATE ScriptedMonitor m SET m.active = false, m.disabledReason = :reason, m.disabledAt = :at, "
         + "m.updatedAt = :at WHERE m.id = :id AND m.active = true")
    int disableIfActive(@Param("id") Long id, @Param("reason") String reason, @Param("at") String at);

}
