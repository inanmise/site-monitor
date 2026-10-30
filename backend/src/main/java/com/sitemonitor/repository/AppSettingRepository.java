package com.sitemonitor.repository;

import com.sitemonitor.model.AppSetting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

public interface AppSettingRepository extends JpaRepository<AppSetting, Long> {

    Optional<AppSetting> findBySettingKey(String settingKey);

    /**
     * Geçersiz kılmayı KOŞULLU siler (2026-10-30, sistem işi): okunduğundan beri satır değişmediyse ({@code updated_at}
     * aynı; boş = '') değer NULL olur. Değer kolonu TEXT olduğu için eşitlik değer üzerinden değil damga üzerinden kurulur.
     * Döner: değişen satır (0 = arada biri kaydetti, dokunulmadı).
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE AppSetting a SET a.value = NULL, a.updatedAt = :at, a.updatedBy = :by "
            + "WHERE a.settingKey = :key AND COALESCE(a.updatedAt, '') = :seenAt")
    int clearIfUnchanged(@Param("key") String key, @Param("seenAt") String seenAt,
                         @Param("at") String at, @Param("by") String by);
}
