package com.sitemonitor.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Kullanıcının KİŞİSEL arayüz tercihleri (2026-10-02, onaylı öneri 23) — kullanıcı başına TEK satır, tek JSON belgesi.
 *
 * <p>Belge anahtarları beyaz listeli ({@code UserPreferencesService.TOP_KEYS}): favori izlemeler, açılış sekmesi,
 * liste başına kayıtlı görünümler ve tarayıcıdaki beyaz listeli localStorage tercihlerinin aynası ({@code local}).
 * Yalnız sahibi okur/yazar ({@code /api/me/preferences}); kimlik oturumdan gelir, istekte kullanıcı parametresi yoktur.
 *
 * <p>Kullanıcı silinince satır öksüz kalır ve gece temizliğinde {@code user-preferences-orphan} kuralıyla silinir.
 * Tablo {@code SchedulerService.applySchemaPatches} içinde de açıkça kurulur (ddl-auto'ya tek başına güvenilmez).
 */
@Entity
@Table(name = "user_preferences")
@Data
@NoArgsConstructor
public class UserPreference {

    /** {@code app_users.id} — birincil anahtar (kullanıcı başına tek belge). */
    @Id
    @Column(name = "user_id")
    private Long userId;

    /** Tercih belgesi (JSON nesnesi). Boyut sunucuda sınırlı ({@code UserPreferencesService.MAX_BYTES}). */
    @Column(name = "prefs", columnDefinition = "TEXT")
    private String prefs;

    /** Son yazım anı (UTC, ISO-8601 — {@code yyyy-MM-dd'T'HH:mm:ss'Z'}). */
    @Column(name = "updated_at", length = 40)
    private String updatedAt;
}
