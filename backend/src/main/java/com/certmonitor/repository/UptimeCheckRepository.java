package com.certmonitor.repository;

import com.certmonitor.model.UptimeCheck;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UptimeCheckRepository extends JpaRepository<UptimeCheck, Long> {
    Optional<UptimeCheck> findTopByDomainAndPortOrderByIdDesc(String domain, int port);

    @Query("SELECT u FROM UptimeCheck u WHERE u.domain = :domain AND u.port = :port AND u.checkedAt >= :from AND u.checkedAt <= :to ORDER BY u.checkedAt DESC LIMIT :limit")
    List<UptimeCheck> findByDomainAndPortAndDateRange(
        @Param("domain") String domain,
        @Param("port")   int    port,
        @Param("from")   String from,
        @Param("to")     String to,
        @Param("limit")  int    limit
    );
}
