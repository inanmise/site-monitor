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
     * Tek bir ESKİ sürümü kalıcı siler (2026-10-07, kullanıcı isteği) — koşullu: yalnız bu kayda ait ve GÜNCEL OLMAYAN
     * satır. Güncel sürüm asla silinmez (0 döner). Kendi işlemini taşır; çağıranın işlemi varsa ona katılır
     * ({@code ManualCertificateController#deleteVersion} → {@code ManualCertificateService#deleteVersion}).
     * Dönüş: silinen satır sayısı (0 ya da 1). Türetilmiş silme (JPQL'de {@code current} sözcüğü yok).
     */
    @Transactional
    long deleteByIdAndInventoryIdAndCurrentFalse(Long id, Long inventoryId);

    /**
     * Kayıtla birlikte sürümleri silme. Kendi işlemini taşır: çağıranın işlemi varsa ona katılır, yoksa kendi açar.
     * Dönüş: silinen satır sayısı. (2026-10-07: kalıcı envanter silmesi sürümleri PermanentDeletionService'te JDBC ile
     * aynı işlemde siler; bu metot o yolun dışında kalan çağıranlar içindir.)
     */
    @Transactional
    @Modifying
    @Query("DELETE FROM ManualCertificateVersion v WHERE v.inventoryId = :inventoryId")
    int deleteByInventoryId(@Param("inventoryId") Long inventoryId);
}
