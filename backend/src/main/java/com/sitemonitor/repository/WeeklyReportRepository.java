package com.sitemonitor.repository;

import com.sitemonitor.model.WeeklyReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface WeeklyReportRepository extends JpaRepository<WeeklyReport, Long> {

    /** Ayar ekranı: takım → rapor sayısı (modül kapatılırken "veri var mı" uyarısı için). */
    @org.springframework.data.jpa.repository.Query("SELECT r.teamId, COUNT(r) FROM WeeklyReport r GROUP BY r.teamId")
    java.util.List<Object[]> countByTeam();

    /** Yıl filtresi dropdown'ı — rapor bulunan yıllar (yeni → eski). */
    @Query("SELECT DISTINCT r.reportYear FROM WeeklyReport r"
            + " WHERE (:teamId IS NULL OR r.teamId = :teamId) ORDER BY r.reportYear DESC")
    List<Integer> findDistinctYears(@Param("teamId") Long teamId);

    List<WeeklyReport> findByTeamIdAndReportYearOrderByWeekNoDesc(Long teamId, Integer reportYear);

    /** ADMIN görünümü — tüm takımlar. */
    List<WeeklyReport> findByReportYearOrderByTeamIdAscWeekNoDesc(Integer reportYear);

    Optional<WeeklyReport> findByTeamIdAndReportYearAndWeekNo(Long teamId, Integer reportYear, Integer weekNo);

    /** E-posta ile hızlı onay — token ile rapor bulma. */
    Optional<WeeklyReport> findByApprovalToken(String approvalToken);

    /** Yeni hafta şablonunun kaynağı — takımın en güncel raporu. */
    Optional<WeeklyReport> findFirstByTeamIdOrderByReportYearDescWeekNoDesc(Long teamId);

    /**
     * Takım kümesinin her biri için EN GÜNCEL rapor (yıl/hafta) — TEK sorgu (2026-10-01, performans: takım başına
     * {@link #findFirstByTeamIdOrderByReportYearDescWeekNoDesc} yerine). Taşınabilir JPQL (PostgreSQL {@code DISTINCT ON}
     * H2'de yok): {@code (takım, yıl, hafta)} tekil olduğundan ({@code ux_weekly_report_team_week}) takım başına en çok
     * bir satır döner. {@code yıl*100 + hafta} sırası (yıl, hafta) sırasıyla aynıdır (hafta ≤ 53).
     */
    @Query("SELECT r FROM WeeklyReport r WHERE r.teamId IN :teamIds"
            + " AND (r.reportYear * 100 + r.weekNo) = (SELECT MAX(r2.reportYear * 100 + r2.weekNo)"
            + " FROM WeeklyReport r2 WHERE r2.teamId = r.teamId)")
    List<WeeklyReport> findLatestPerTeam(@Param("teamIds") java.util.Collection<Long> teamIds);

    /** Logout/oturum sonu: kullanıcının elindeki tüm düzenleme kilitlerini serbest bırak. */
    @Modifying
    @Query("UPDATE WeeklyReport r SET r.editingBy = null, r.editingUserId = null,"
            + " r.editingHeartbeat = null WHERE r.editingUserId = :userId")
    int clearLocksByUser(@Param("userId") Long userId);

    // ── Durum geçişi SAHİPLENMELERİ (2026-10-21, çok pod MP-39) ─────────────────────────────────────────────────────
    // Gönder / onayla / iade et / yeniden gönder / revize: durum ÖNCE tek koşullu UPDATE ile alınır (kendi işleminde,
    // anında kesinleşir); e-posta + push YALNIZ kazanan (dönen satır sayısı 1) tarafından gönderilir. Çift tık, iki
    // sekme, iki pod ya da uygulama içi onay ∥ e-posta bağlantısı yarışında ikinci istek 0 alır ve güncel durumu görür.
    // Durum + onay alanları TEK ifadede yazılır — tam varlık kaydı (save) yarışan bir geçişi geri alamaz.

    /** DRAFT → PENDING_APPROVAL; düzenleme kilidi biter, yeni onay belirteci yazılır. */
    @Modifying
    @Transactional
    @Query("UPDATE WeeklyReport r SET r.status = 'PENDING_APPROVAL', r.version = r.version + 1,"
            + " r.editingUserId = null, r.editingBy = null, r.editingHeartbeat = null,"
            + " r.submittedBy = :by, r.submittedAt = :at, r.updatedBy = :by, r.updatedAt = :at,"
            + " r.approvalToken = :token, r.approvalTokenExpiresAt = :expiresAt, r.score = :score"
            + " WHERE r.id = :id AND r.status = 'DRAFT'")
    int claimSubmit(@Param("id") Long id, @Param("by") String by, @Param("at") String at,
                    @Param("token") String token, @Param("expiresAt") String expiresAt, @Param("score") Integer score);

    /** PENDING_APPROVAL → APPROVED (uygulama içi onay); belirteç ve kilit temizlenir, iade notu silinir. */
    @Modifying
    @Transactional
    @Query("UPDATE WeeklyReport r SET r.status = 'APPROVED', r.version = r.version + 1,"
            + " r.editingUserId = null, r.editingBy = null, r.editingHeartbeat = null,"
            + " r.approvalToken = null, r.approvalTokenExpiresAt = null,"
            + " r.approvedBy = :by, r.approvedAt = :at, r.rejectNote = null, r.updatedBy = :by, r.updatedAt = :at"
            + " WHERE r.id = :id AND r.status = 'PENDING_APPROVAL'")
    int claimApprove(@Param("id") Long id, @Param("by") String by, @Param("at") String at);

    /** E-posta bağlantısıyla onay — {@link #claimApprove} + belirteç HÂLÂ bu belirteç (yeniden gönderimle yenilenmemiş). */
    @Modifying
    @Transactional
    @Query("UPDATE WeeklyReport r SET r.status = 'APPROVED', r.version = r.version + 1,"
            + " r.editingUserId = null, r.editingBy = null, r.editingHeartbeat = null,"
            + " r.approvalToken = null, r.approvalTokenExpiresAt = null,"
            + " r.approvedBy = :by, r.approvedAt = :at, r.rejectNote = null, r.updatedBy = :by, r.updatedAt = :at"
            + " WHERE r.id = :id AND r.status = 'PENDING_APPROVAL' AND r.approvalToken = :token")
    int claimApproveByToken(@Param("id") Long id, @Param("token") String token,
                            @Param("by") String by, @Param("at") String at);

    /** PENDING_APPROVAL → REJECTED (uygulama içi iade); belirteç temizlenir, not yazılır. */
    @Modifying
    @Transactional
    @Query("UPDATE WeeklyReport r SET r.status = 'REJECTED', r.version = r.version + 1,"
            + " r.approvalToken = null, r.approvalTokenExpiresAt = null,"
            + " r.rejectNote = :note, r.rejectedBy = :by, r.rejectedAt = :at, r.updatedBy = :by, r.updatedAt = :at"
            + " WHERE r.id = :id AND r.status = 'PENDING_APPROVAL'")
    int claimReject(@Param("id") Long id, @Param("note") String note, @Param("by") String by, @Param("at") String at);

    /** E-posta bağlantısıyla iade — {@link #claimReject} + belirteç hâlâ bu belirteç. */
    @Modifying
    @Transactional
    @Query("UPDATE WeeklyReport r SET r.status = 'REJECTED', r.version = r.version + 1,"
            + " r.approvalToken = null, r.approvalTokenExpiresAt = null,"
            + " r.rejectNote = :note, r.rejectedBy = :by, r.rejectedAt = :at, r.updatedBy = :by, r.updatedAt = :at"
            + " WHERE r.id = :id AND r.status = 'PENDING_APPROVAL' AND r.approvalToken = :token")
    int claimRejectByToken(@Param("id") Long id, @Param("token") String token, @Param("note") String note,
                           @Param("by") String by, @Param("at") String at);

    /** Onaylı raporu müdüre YENİDEN gönderme — okunan sürüm hâlâ geçerliyse sürüm +1 (eşzamanlı ikinci tık 0 alır). */
    @Modifying
    @Transactional
    @Query("UPDATE WeeklyReport r SET r.version = r.version + 1, r.updatedBy = :by, r.updatedAt = :at"
            + " WHERE r.id = :id AND r.status = 'APPROVED' AND r.version = :version")
    int claimResend(@Param("id") Long id, @Param("version") int version, @Param("by") String by, @Param("at") String at);

    /** APPROVED → DRAFT (revize); onay / gönderim alanları sıfırlanır. */
    @Modifying
    @Transactional
    @Query("UPDATE WeeklyReport r SET r.status = 'DRAFT', r.version = r.version + 1,"
            + " r.sentAt = null, r.approvedBy = null, r.approvedAt = null, r.updatedBy = :by, r.updatedAt = :at"
            + " WHERE r.id = :id AND r.status = 'APPROVED'")
    int claimReopen(@Param("id") Long id, @Param("by") String by, @Param("at") String at);

    /** Müdür postası gitti — gönderim anı, YALNIZ aynı onay döngüsündeyse (arada revize edilmemişse) yazılır.
     *  {@code approvedAt} null olan eski onaylı satırlar için "-" geçilir. */
    @Modifying
    @Transactional
    @Query("UPDATE WeeklyReport r SET r.sentAt = :at"
            + " WHERE r.id = :id AND r.status = 'APPROVED' AND COALESCE(r.approvedAt, '-') = :approvedAt")
    int markSent(@Param("id") Long id, @Param("approvedAt") String approvedAt, @Param("at") String at);

    /**
     * İçerik kaydı — okunan sürüm hâlâ geçerliyse (her durum geçişi sürümü artırır). Bayat bir kaydın onaya gönderilmiş /
     * onaylanmış raporu taslağa geri çevirmesi (ve belirteci silmesi) böylece imkânsız: 0 satır = VERSION_CONFLICT.
     */
    @Modifying
    @Transactional
    @Query("UPDATE WeeklyReport r SET r.contentJson = :content, r.status = :status, r.version = r.version + 1,"
            + " r.updatedBy = :by, r.updatedAt = :at WHERE r.id = :id AND r.version = :version")
    int updateContent(@Param("id") Long id, @Param("version") int version, @Param("content") String content,
                      @Param("status") String status, @Param("by") String by, @Param("at") String at);

    /** Düzenleme kilidini al / tazele — YALNIZ kilit kolonları (durum ve belirteç kolonlarına dokunmaz). */
    @Modifying
    @Transactional
    @Query("UPDATE WeeklyReport r SET r.editingUserId = :userId, r.editingBy = :by, r.editingHeartbeat = :at"
            + " WHERE r.id = :id")
    int setEditingLock(@Param("id") Long id, @Param("userId") Long userId, @Param("by") String by, @Param("at") String at);

    /** Kilit kalp atışı — yalnız kilit HÂLÂ bu kullanıcıdaysa. */
    @Modifying
    @Transactional
    @Query("UPDATE WeeklyReport r SET r.editingHeartbeat = :at WHERE r.id = :id AND r.editingUserId = :userId")
    int touchEditingLock(@Param("id") Long id, @Param("userId") Long userId, @Param("at") String at);

    /** Kendi kilidini bırak — başkasının kilidine dokunmaz. */
    @Modifying
    @Transactional
    @Query("UPDATE WeeklyReport r SET r.editingUserId = null, r.editingBy = null, r.editingHeartbeat = null"
            + " WHERE r.id = :id AND r.editingUserId = :userId")
    int releaseEditingLock(@Param("id") Long id, @Param("userId") Long userId);
}
