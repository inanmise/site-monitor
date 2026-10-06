package com.sitemonitor.repository;

import com.sitemonitor.model.ManualCertificateVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Elle yüklenen sertifika sürümleri (2026-10-06). Liste/özet okumaları TOPLUdur — satır başına sorgu yok. */
public interface ManualCertificateVersionRepository extends JpaRepository<ManualCertificateVersion, Long> {

    /** Kaydın tüm sürümleri, en yeni üstte (detay ekranı). */
    List<ManualCertificateVersion> findByInventoryIdOrderByVersionDesc(Long inventoryId);

    /** Kaydın izlenen (geçerli) sürümü. */
    Optional<ManualCertificateVersion> findFirstByInventoryIdAndCurrentTrueOrderByVersionDesc(Long inventoryId);

    /** Birden çok kaydın geçerli sürümleri — liste/pano/zamanlayıcı için TEK sorgu. */
    List<ManualCertificateVersion> findByInventoryIdInAndCurrentTrue(Collection<Long> inventoryIds);

    /** Aynı parmak izi zaten izleniyor mu (analiz eşleşmesi + ALREADY_TRACKED). */
    List<ManualCertificateVersion> findByCurrentTrueAndFingerprintIn(Collection<String> fingerprints);

    /** Aynı konu (subject DN) — yenileme adayı eşleşmesi. */
    List<ManualCertificateVersion> findByCurrentTrueAndSubjectDnIn(Collection<String> subjectDns);

    /** Kaydın en büyük sürüm numarası (yoksa null). */
    @Query("SELECT MAX(v.version) FROM ManualCertificateVersion v WHERE v.inventoryId = :inventoryId")
    Integer findMaxVersion(@Param("inventoryId") Long inventoryId);

    /** [inventoryId, sürüm sayısı] — liste ekranı için TEK gruplu sorgu. */
    @Query("SELECT v.inventoryId, COUNT(v) FROM ManualCertificateVersion v WHERE v.inventoryId IN :ids GROUP BY v.inventoryId")
    List<Object[]> countByInventoryIds(@Param("ids") Collection<Long> inventoryIds);

    /**
     * Kalıcı silme (purge) — envanter satırıyla birlikte sürümleri de gider. Kendi işlemini taşır: çağıranın
     * işlemi varsa ona katılır (AdminController#purgeInventory / #purgeAllDeleted, InventoryAutoPurgeService — hepsi
     * {@code @Transactional}), yoksa kendi açar. Dönüş: silinen satır sayısı.
     */
    @Transactional
    @Modifying
    @Query("DELETE FROM ManualCertificateVersion v WHERE v.inventoryId = :inventoryId")
    int deleteByInventoryId(@Param("inventoryId") Long inventoryId);
}
