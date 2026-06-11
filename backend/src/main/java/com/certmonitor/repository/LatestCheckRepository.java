package com.certmonitor.repository;

import com.certmonitor.model.LatestCheck;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Set;

@Repository
public interface LatestCheckRepository extends JpaRepository<LatestCheck, String> {

    List<LatestCheck> findAllByOrderByDomainAsc();

    List<LatestCheck> findByWarningTrueOrStatus(String status);

    List<LatestCheck> findByDomainIn(java.util.Collection<String> domains);

    /** Returns domain names whose last check timestamp is newer than the given ISO cutoff string. */
    Set<LatestCheck> findByCheckedAtGreaterThanEqual(String cutoff);

    /** Domain rename: envanterde bir kayıt domain alanı değişince çağrılır
     *  — eski domain'in geçmiş kontrol kaydı yeni domain'e taşınır.
     *  Native query çünkü domain @Id ve JPA cache'ini doğrudan bypass etmek
     *  istiyoruz. Caller'da @Transactional zorunlu. */
    @Modifying
    @Query(value = "UPDATE latest_checks SET domain = :newDomain WHERE domain = :oldDomain",
            nativeQuery = true)
    int renameDomain(@Param("oldDomain") String oldDomain, @Param("newDomain") String newDomain);
}
