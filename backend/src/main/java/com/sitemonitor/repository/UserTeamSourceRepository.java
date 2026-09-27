package com.sitemonitor.repository;

import com.sitemonitor.model.UserTeamSource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

/** Takım üyeliği kaynak kayıtları (bkz. {@link UserTeamSource}). */
public interface UserTeamSourceRepository extends JpaRepository<UserTeamSource, UserTeamSource.Key> {

    List<UserTeamSource> findByUserId(Long userId);

    List<UserTeamSource> findByTeamId(Long teamId);

    List<UserTeamSource> findByUserIdIn(Collection<Long> userIds);

    /** Kullanıcı silinince kaynak izleri de gider. Türetilmiş silme → açık transaction ŞART. */
    @Transactional
    void deleteByUserId(Long userId);

    /** Artık üye olunmayan takımların kaynak satırları. Türetilmiş silme → açık transaction ŞART. */
    @Transactional
    void deleteByUserIdAndTeamIdIn(Long userId, Collection<Long> teamIds);
}
