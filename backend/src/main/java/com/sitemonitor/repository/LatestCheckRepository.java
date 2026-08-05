package com.sitemonitor.repository;

import com.sitemonitor.model.LatestCheck;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Set;

public interface LatestCheckRepository extends JpaRepository<LatestCheck, String> {

    List<LatestCheck> findAllByOrderByDomainAsc();

    List<LatestCheck> findByWarningTrueOrStatus(String status);

    List<LatestCheck> findByDomainIn(java.util.Collection<String> domains);

    /**
     * Zayıf algoritma adaylarını DB'de filtreler (tüm tabloyu çekmek yerine):
     * eski hash (MD2/MD5/SHA1) veya kısa anahtar (RSA/DSA < 2048, EC < 256).
     * classifyWeakness ile birebir aynı koşullar — sonuç kümesi değişmez.
     */
    @Query("""
        SELECT c FROM LatestCheck c
        WHERE upper(c.signatureAlgorithm) LIKE '%MD2%'
           OR upper(c.signatureAlgorithm) LIKE '%MD5%'
           OR upper(c.signatureAlgorithm) LIKE '%SHA1%'
           OR upper(c.signatureAlgorithm) LIKE '%SHA-1%'
           OR ((upper(c.publicKeyAlgorithm) LIKE '%RSA%' OR upper(c.publicKeyAlgorithm) LIKE '%DSA%') AND c.publicKeySize < 2048)
           OR (upper(c.publicKeyAlgorithm) LIKE '%EC%' AND c.publicKeySize < 256)
        """)
    List<LatestCheck> findWeakAlgorithmCandidates();

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
