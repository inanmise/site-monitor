package com.certmonitor.repository;

import com.certmonitor.model.UptimeCheck;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UptimeCheckRepository extends JpaRepository<UptimeCheck, Long> {
    Optional<UptimeCheck> findTopByDomainAndPortOrderByIdDesc(String domain, int port);

    /** Tek toplu sorgu — son N saatteki tüm HTTP kontrolleri (uptime overview http_ok hesabı için). */
    List<UptimeCheck> findByCheckedAtGreaterThanEqual(String since);

    /** Uptime overview http_ok: 24h TÜM satırları JVM'e yüklemek (500 domain'de ~144k satır) yerine
     *  domain başına [total, upCount] agregasyonu — httpOk = upCount == total. GROUP BY yalnız ≥1
     *  satırlı domain'i döner. idx_uc_domain_port_checked üzerinden. */
    @Query("SELECT u.domain, COUNT(u), SUM(CASE WHEN u.status = 'up' THEN 1 ELSE 0 END) "
         + "FROM UptimeCheck u WHERE u.checkedAt >= :since GROUP BY u.domain")
    List<Object[]> aggregateHttpOkSince(@Param("since") String since);

    /** Her (domain,port) için en güncel uptime kontrolü — overview'da domain başına
     *  findTopByDomainAndPort... sorgusu yerine tek toplu sorgu (N+1 giderme). */
    @Query("SELECT u FROM UptimeCheck u WHERE u.id IN "
         + "(SELECT MAX(u2.id) FROM UptimeCheck u2 GROUP BY u2.domain, u2.port)")
    List<UptimeCheck> findLatestPerDomainPort();

    @Query("SELECT u FROM UptimeCheck u WHERE u.domain = :domain AND u.port = :port AND u.checkedAt >= :from AND u.checkedAt <= :to ORDER BY u.checkedAt DESC LIMIT :limit")
    List<UptimeCheck> findByDomainAndPortAndDateRange(
        @Param("domain") String domain,
        @Param("port")   int    port,
        @Param("from")   String from,
        @Param("to")     String to,
        @Param("limit")  int    limit
    );

    /** Haftalık erişilebilirlik raporu — pencere içi (UTC ISO) kontroller, kronolojik sıralı
     *  (up→down geçiş/kesinti tespiti için). checkedAt UTC ISO string. */
    List<UptimeCheck> findByDomainAndPortAndCheckedAtBetweenOrderByCheckedAtAsc(
        String domain, Integer port, String checkedAtStart, String checkedAtEnd);
}
