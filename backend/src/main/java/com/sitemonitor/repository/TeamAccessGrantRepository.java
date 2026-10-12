package com.sitemonitor.repository;

import com.sitemonitor.model.TeamAccessGrant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Takım erişim paylaşımları (2026-10-12) — küçük tablo; anlık görüntü tümünü okur. */
public interface TeamAccessGrantRepository extends JpaRepository<TeamAccessGrant, Long> {

    List<TeamAccessGrant> findByOwnerTeamIdInOrderByIdAsc(Collection<Long> ownerTeamIds);

    List<TeamAccessGrant> findAllByOrderByIdAsc();

    Optional<TeamAccessGrant> findByOwnerTeamIdAndGranteeKey(Long ownerTeamId, String granteeKey);

    long countByOwnerTeamId(Long ownerTeamId);

    /** Takım silinince: sahibi ya da alıcısı o takım olan paylaşımlar gider. */
    @Transactional
    @Modifying
    @Query("DELETE FROM TeamAccessGrant g WHERE g.ownerTeamId = :teamId OR g.granteeTeamId = :teamId")
    int deleteByTeam(@Param("teamId") Long teamId);

    /** Kullanıcı silinince: o kişiye verilen paylaşımlar gider. */
    @Transactional
    @Modifying
    @Query("DELETE FROM TeamAccessGrant g WHERE g.granteeUserId = :userId")
    int deleteByGranteeUser(@Param("userId") Long userId);
}
