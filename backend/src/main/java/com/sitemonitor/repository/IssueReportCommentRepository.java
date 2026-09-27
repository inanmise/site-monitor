package com.sitemonitor.repository;

import com.sitemonitor.model.IssueReportComment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

public interface IssueReportCommentRepository extends JpaRepository<IssueReportComment, Long> {

    /** Bir raporun tüm satırları (yorum + durum geçişi), yazım sırasıyla. İç notların süzülmesi çağıranın işi. */
    List<IssueReportComment> findByReportIdOrderByIdAsc(Long reportId);

    /** Liste rozeti: rapor → HERKESE AÇIK yorum sayısı (tek sorgu; iç not ve durum satırı sayılmaz). */
    @Query("""
            SELECT c.reportId, COUNT(c) FROM IssueReportComment c
             WHERE c.reportId IN :ids AND c.internal = false AND c.kind = 'COMMENT'
             GROUP BY c.reportId
            """)
    List<Object[]> countPublicByReportIds(@Param("ids") Collection<Long> ids);

    /**
     * Bildirim kutusu (bildiren tarafı): kullanıcının raporlarına yönetici tarafından yazılan HERKESE AÇIK
     * satırlar (yanıt ya da durum geçişi) — en yeni önce. Kullanıcı adı büyük/küçük harf duyarsız eşlenir.
     * Satır + ebeveyn rapor birlikte döner (referans kodu ve bağlantı için).
     */
    @Query("""
            SELECT c, r FROM IssueReportComment c, LoginIssueReport r
             WHERE c.reportId = r.id
               AND LOWER(r.username) = :username
               AND c.byReporter = false AND c.internal = false
               AND c.createdAt >= :since
             ORDER BY c.id DESC
            """)
    List<Object[]> findAdminActivityForReporter(@Param("username") String usernameLower, @Param("since") String since);

    /**
     * Bildirim kutusu (yönetici tarafı): bildirenin yorumuyla YENİDEN AÇILAN raporlar — STATUS satırı,
     * {@code byReporter=true}. Yalnız global yönetici görür (TodayPanelController).
     */
    @Query("""
            SELECT c, r FROM IssueReportComment c, LoginIssueReport r
             WHERE c.reportId = r.id
               AND c.kind = 'STATUS' AND c.byReporter = true
               AND c.createdAt >= :since
             ORDER BY c.id DESC
            """)
    List<Object[]> findReopensSince(@Param("since") String since);

    /**
     * Rapor kalıcı silinince yorumlar da gider.
     *
     * <p>{@code @Transactional} + {@code int} ŞART: türetilmiş silme sorgularına Spring Data
     * kendiliğinden transaction SARMAZ ve {@code open-in-view=false} olduğu için çağrı tx'siz
     * düşerdi ({@code RepositoryWriteTransactionGuardTest} bunun kapısı).
     */
    @Transactional
    int deleteByReportId(Long reportId);
}
