package com.sitemonitor.repository;

import com.sitemonitor.model.LoginUnknownLockout;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bilinmeyen kullanıcı adının kilit durumu (2026-10-09) — {@code UnknownUserLockoutService}'in deposu.
 *
 * <p>Yazım tek satırlık, koşullu ve taşınabilir (PostgreSQL + H2 PostgreSQL kipi): servis güncel kademeyi okur, merdivenden
 * bir sonraki ihlali hesaplar, sonra satır varsa {@link #updateStep}, yoksa {@link #insertStepIfAbsent} ({@code ON CONFLICT
 * DO NOTHING} — iki pod aynı anda ilk kilidi yazarsa benzersizlik hatası yerine 0 döner, servis güncellemeye geçer). Sonuç
 * mevcut hesabın {@code UserService.applyProgressiveLockout} (oku → hesapla → yaz) anlamıyla birebir: son yazan kazanır.
 */
public interface LoginUnknownLockoutRepository extends JpaRepository<LoginUnknownLockout, String> {

    /** Var olan satıra ihlali yazar; satır yoksa 0. */
    @Modifying
    @Transactional
    @Query(value = "UPDATE login_unknown_lockouts SET lockout_level = :level, lockout_until = :until, "
            + "last_lockout_at = :lockedAt, updated_at = :now WHERE username_key = :key", nativeQuery = true)
    int updateStep(@Param("key") String key, @Param("level") int level, @Param("until") String until,
                   @Param("lockedAt") String lockedAt, @Param("now") String now);

    /** İlk ihlal: satırı yaratır; aynı anahtar zaten varsa (eşzamanlı pod) hiçbir şey yapmaz ve 0 döner. */
    @Modifying
    @Transactional
    @Query(value = "INSERT INTO login_unknown_lockouts (username_key, lockout_level, lockout_until, last_lockout_at, "
            + "created_at, updated_at) VALUES (:key, :level, :until, :lockedAt, :now, :now) ON CONFLICT DO NOTHING",
            nativeQuery = true)
    int insertStepIfAbsent(@Param("key") String key, @Param("level") int level, @Param("until") String until,
                           @Param("lockedAt") String lockedAt, @Param("now") String now);

    /** Saklama: son yazımı {@code cutoff}'tan eski satırlar. */
    @Modifying
    @Transactional
    @Query(value = "DELETE FROM login_unknown_lockouts WHERE updated_at < :cutoff", nativeQuery = true)
    int deleteUpdatedBefore(@Param("cutoff") String cutoff);

    /** Sert üst sınır: en eski {@code excess} satırı siler (son yazım, eşitlikte anahtar sırası). */
    @Modifying
    @Transactional
    @Query(value = "DELETE FROM login_unknown_lockouts WHERE username_key IN (SELECT username_key FROM "
            + "login_unknown_lockouts ORDER BY updated_at ASC, username_key ASC LIMIT :excess)", nativeQuery = true)
    int deleteOldest(@Param("excess") int excess);
}
