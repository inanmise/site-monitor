package com.certmonitor.repository;

import com.certmonitor.model.LatestCheck;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Set;

@Repository
public interface LatestCheckRepository extends JpaRepository<LatestCheck, String> {

    List<LatestCheck> findAllByOrderByDomainAsc();

    List<LatestCheck> findByWarningTrueOrStatus(String status);

    /** Returns domain names whose last check timestamp is newer than the given ISO cutoff string. */
    Set<LatestCheck> findByCheckedAtGreaterThanEqual(String cutoff);
}
