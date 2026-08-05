package com.sitemonitor.repository;

import com.sitemonitor.model.RememberMeToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

public interface RememberMeTokenRepository extends JpaRepository<RememberMeToken, Long> {

    Optional<RememberMeToken> findByToken(String token);

    @Transactional
    void deleteByToken(String token);

    /** Tek aktif oturum: yeni login'de kullanıcının TÜM remember-me token'larını iptal et
     *  (eski tarayıcı cookie ile sessizce geri dönemesin). */
    @Transactional
    void deleteByUsername(String username);

    @Modifying
    @Transactional
    @Query("DELETE FROM RememberMeToken t WHERE t.expiresAt < :now")
    void deleteExpired(long now);
}
