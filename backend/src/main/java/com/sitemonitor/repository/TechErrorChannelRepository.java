package com.sitemonitor.repository;

import com.sitemonitor.model.TechErrorChannel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/** Kanal teknik hata raporu kanalları (2026-10-13). */
public interface TechErrorChannelRepository extends JpaRepository<TechErrorChannel, Long> {

    List<TechErrorChannel> findByTeamIdIn(Collection<Long> teamIds);

    boolean existsByTeamIdAndNameKey(Long teamId, String nameKey);

    boolean existsByTeamIdAndNameKeyAndIdNot(Long teamId, String nameKey, Long id);

    long countByTeamId(Long teamId);

    /**
     * Şablonu YALNIZ okunan sürüm hâlâ güncelse yazar (iyimser kilit, koşullu UPDATE). 0 satır = arada başkası kaydetti
     * (ya da kanal silindi) → çağıran 409 döner; hiçbir değişiklik sessizce ezilmez.
     */
    @Modifying
    @Transactional
    @Query("UPDATE TechErrorChannel c SET c.templateJson = :json, c.templateVersion = c.templateVersion + 1, "
            + "c.templateUpdatedAt = :at, c.templateUpdatedById = :by WHERE c.id = :id AND c.templateVersion = :expected")
    int saveTemplate(@Param("id") Long id, @Param("expected") int expected, @Param("json") String json,
                     @Param("at") Instant at, @Param("by") Long by);
}
