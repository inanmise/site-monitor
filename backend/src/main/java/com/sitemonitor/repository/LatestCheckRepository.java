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

    /** Aynı sertifikayı taşıyan alanlar (paylaşılan sertifika penceresi) — tüm tabloyu çekip elemenin yerine (2026-09-22). */
    List<LatestCheck> findByFingerprintIgnoreCase(String fingerprint);

    /** Toplu parmak izi eşleşmesi (manuel sertifika analizi, 2026-10-06) — parmak izleri büyük harf saklanır. */
    List<LatestCheck> findByFingerprintIn(java.util.Collection<String> fingerprints);

    /**
     * Zayıf algoritma adaylarını DB'de filtreler (tüm tabloyu çekmek yerine):
     * eski hash (MD2/MD5/SHA1) veya kısa anahtar (RSA/DSA < 2048, EC < 256; boyu okunamayan / EdDSA / PQC anahtar
     * kısa SAYILMAZ — DATA-13). classifyWeakness ile birebir aynı koşullar — sonuç kümesi değişmez (WeakKeySqlParityTest).
     */
    @Query("""
        SELECT c FROM LatestCheck c
        WHERE upper(c.signatureAlgorithm) LIKE '%MD2%'
           OR upper(c.signatureAlgorithm) LIKE '%MD5%'
           OR upper(c.signatureAlgorithm) LIKE '%SHA1%'
           OR upper(c.signatureAlgorithm) LIKE '%SHA-1%'
           OR (c.publicKeySize > 0 AND c.publicKeySize < 2048
               AND upper(c.publicKeyAlgorithm) NOT LIKE 'EC%'
               AND upper(c.publicKeyAlgorithm) NOT LIKE '%EDDSA%' AND upper(c.publicKeyAlgorithm) NOT LIKE '%ED25519%'
               AND upper(c.publicKeyAlgorithm) NOT LIKE '%ED448%' AND upper(c.publicKeyAlgorithm) NOT LIKE '%ML-DSA%'
               AND upper(c.publicKeyAlgorithm) NOT LIKE '%MLDSA%' AND upper(c.publicKeyAlgorithm) NOT LIKE '%SLH-DSA%'
               AND upper(c.publicKeyAlgorithm) NOT LIKE '%SLHDSA%' AND upper(c.publicKeyAlgorithm) NOT LIKE '%FN-DSA%'
               AND upper(c.publicKeyAlgorithm) NOT LIKE '%FNDSA%'
               AND (upper(c.publicKeyAlgorithm) LIKE '%RSA%' OR upper(c.publicKeyAlgorithm) LIKE '%DSA%'))
           OR (c.publicKeySize > 0 AND upper(c.publicKeyAlgorithm) LIKE 'EC%' AND c.publicKeySize < 256)
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
