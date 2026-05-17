package com.certmonitor.repository;

import com.certmonitor.model.RememberMeToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

public interface RememberMeTokenRepository extends JpaRepository<RememberMeToken, Long> {

    Optional<RememberMeToken> findByToken(String token);

    @Transactional
    void deleteByToken(String token);

    @Modifying
    @Transactional
    @Query("DELETE FROM RememberMeToken t WHERE t.expiresAt < :now")
    void deleteExpired(long now);
}
