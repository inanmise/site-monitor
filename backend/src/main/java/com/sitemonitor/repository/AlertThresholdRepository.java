package com.sitemonitor.repository;

import com.sitemonitor.model.AlertThreshold;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AlertThresholdRepository extends JpaRepository<AlertThreshold, Long> {
    /** Aktif VARSAYILAN satırlar (tier'sız), en eski önce. */
    List<AlertThreshold> findByActiveTrueAndTierIsNullOrderByIdAsc();

    /** Aktif tier satırları (tier 1..4), en eski önce — {@code ThresholdResolution} bunları haritalar. */
    List<AlertThreshold> findByActiveTrueAndTierIsNotNullOrderByIdAsc();

    List<AlertThreshold> findByTierOrderByIdAsc(Integer tier);

    /**
     * Aktif VARSAYILAN eşik — tier satırları BİLEREK dışarıda: eskiden türetilmiş sorgu "aktif ilk satır"dı;
     * tier satırları eklenince (2026-09-20) rastgele bir tier eşiğini global sanabilirdi. Varsayılan metot:
     * mevcut tüm çağıranlar ve mock'lar aynı imzayla çalışmaya devam eder.
     */
    default Optional<AlertThreshold> findFirstByActiveTrue() {
        return findByActiveTrueAndTierIsNullOrderByIdAsc().stream().findFirst();
    }
}
