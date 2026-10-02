package com.sitemonitor.repository;

import com.sitemonitor.model.WeeklyReportMail;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface WeeklyReportMailRepository extends JpaRepository<WeeklyReportMail, Long> {

    List<WeeklyReportMail> findByReportIdOrderByIdDesc(Long reportId);

    /** Liste rozetleri — tek sorguda tüm raporların kayıtları. */
    List<WeeklyReportMail> findByReportIdIn(Collection<Long> reportIds);

    /**
     * Raporun belirli türdeki postalarının YALNIZ alıcı satırı (yeni → eski) — gövde (tam HTML) taşınmaz.
     * E-posta onay bağlantısının kime gittiğini çözer (2026-10-02, pasif onaylayan kapısı: {@code SUBMIT_PO}).
     */
    @Query("SELECT m.toAddresses FROM WeeklyReportMail m WHERE m.reportId = :reportId AND m.mailType = :type ORDER BY m.id DESC")
    List<String> findToAddressesByReportIdAndType(@Param("reportId") Long reportId, @Param("type") String type);

    void deleteByReportId(Long reportId);
}
