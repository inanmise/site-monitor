package com.sitemonitor.repository;

import com.sitemonitor.model.UserPreference;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Kişisel tercih belgeleri ({@code user_preferences}) — yalnız kalıtılan {@code findById} / {@code save} kullanılır.
 * Yazma {@code UserPreferencesService.merge} içinde ({@code @Transactional}); türetilmiş silme yöntemi YOK
 * (öksüz satırları gece temizliği siler — RetentionCatalog {@code user-preferences-orphan}).
 */
public interface UserPreferenceRepository extends JpaRepository<UserPreference, Long> {
}
