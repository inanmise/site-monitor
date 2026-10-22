package com.sitemonitor.repository;

import com.sitemonitor.model.IncidentActionItem;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

public interface IncidentActionItemRepository extends JpaRepository<IncidentActionItem, Long> {

    List<IncidentActionItem> findByIncidentIdOrderByIdAsc(Long incidentId);

    long countByIncidentId(Long incidentId);

    /**
     * Liste rozetleri — sayfadaki olaylar için TEK gruplu sorgu: [incident_id, açık, gecikmiş, biten, toplam].
     * Açık = OPEN | IN_PROGRESS; gecikmiş = açık ve son tarihi bugünden önce.
     */
    @Query("SELECT a.incidentId,"
            + " SUM(CASE WHEN a.status IN ('OPEN','IN_PROGRESS') THEN 1 ELSE 0 END),"
            + " SUM(CASE WHEN a.status IN ('OPEN','IN_PROGRESS') AND a.dueDate IS NOT NULL AND a.dueDate < :today THEN 1 ELSE 0 END),"
            + " SUM(CASE WHEN a.status = 'DONE' THEN 1 ELSE 0 END),"
            + " COUNT(a)"
            + " FROM IncidentActionItem a WHERE a.incidentId IN :ids GROUP BY a.incidentId")
    List<Object[]> countsFor(@Param("ids") Collection<Long> ids, @Param("today") String today);

    String SCOPE = " AND (:scoped = false OR r.teamId IN :scope OR r.createdByTeamId IN :scope OR a.ownerUserId = :me)";

    /**
     * Olaylar arası aksiyon listesi: kapsam = olay kaydı okuma kapsamı (takım ya da giren takım) VEYA aksiyonun sahibi
     * olmak (başka takımın olayından size atanan iş de listenizde). Sıra: son tarihi olanlar önce (yakın tarih üstte),
     * sonra yeni eklenen. Satır: [aksiyon, olay başlığı, olay takım adı, olay önem derecesi, olay takımı, giren takım].
     */
    @Query(value = "SELECT a, r.title, r.teamName, r.severity, r.teamId, r.createdByTeamId FROM IncidentActionItem a, IncidentRecord r WHERE a.incidentId = r.id"
            + SCOPE
            + " AND (:mineOnly = false OR a.ownerUserId = :me)"
            + " AND a.status IN :statuses"
            + " AND (:overdueOnly = false OR (a.dueDate IS NOT NULL AND a.dueDate < :today))"
            + " AND (:q = '' OR LOWER(a.title) LIKE :q OR LOWER(r.title) LIKE :q OR LOWER(a.ownerName) LIKE :q)"
            + " ORDER BY CASE WHEN a.dueDate IS NULL THEN 1 ELSE 0 END, a.dueDate ASC, a.id DESC",
            countQuery = "SELECT COUNT(a) FROM IncidentActionItem a, IncidentRecord r WHERE a.incidentId = r.id"
            + SCOPE
            + " AND (:mineOnly = false OR a.ownerUserId = :me)"
            + " AND a.status IN :statuses"
            + " AND (:overdueOnly = false OR (a.dueDate IS NOT NULL AND a.dueDate < :today))"
            + " AND (:q = '' OR LOWER(a.title) LIKE :q OR LOWER(r.title) LIKE :q OR LOWER(a.ownerName) LIKE :q)")
    Page<Object[]> search(@Param("scoped") boolean scoped, @Param("scope") Collection<Long> scope, @Param("me") Long me,
                          @Param("mineOnly") boolean mineOnly, @Param("statuses") Collection<String> statuses,
                          @Param("overdueOnly") boolean overdueOnly, @Param("today") String today, @Param("q") String q,
                          Pageable pageable);

    /** Aksiyon görünümü sayaçları (kapsam içinde): [açık, gecikmiş, bana açık]. */
    @Query("SELECT"
            + " SUM(CASE WHEN a.status IN ('OPEN','IN_PROGRESS') THEN 1 ELSE 0 END),"
            + " SUM(CASE WHEN a.status IN ('OPEN','IN_PROGRESS') AND a.dueDate IS NOT NULL AND a.dueDate < :today THEN 1 ELSE 0 END),"
            + " SUM(CASE WHEN a.status IN ('OPEN','IN_PROGRESS') AND a.ownerUserId = :me THEN 1 ELSE 0 END)"
            + " FROM IncidentActionItem a, IncidentRecord r WHERE a.incidentId = r.id" + SCOPE)
    List<Object[]> totals(@Param("scoped") boolean scoped, @Param("scope") Collection<Long> scope, @Param("me") Long me,
                          @Param("today") String today);

    /** Hatırlatma adayları: açık, sahipli, son tarihi {@code maxDue} ya da öncesi. */
    @Query("SELECT a FROM IncidentActionItem a WHERE a.status IN ('OPEN','IN_PROGRESS') AND a.ownerUserId IS NOT NULL"
            + " AND a.dueDate IS NOT NULL AND a.dueDate <= :maxDue ORDER BY a.dueDate ASC, a.id ASC")
    List<IncidentActionItem> reminderCandidates(@Param("maxDue") String maxDue);

    /** Hatırlatma sahiplenmesi (ilk kez): yalnız hâlâ hatırlatılmamışsa yazar → 1; başka pod önce davrandıysa 0. */
    @Modifying
    @Transactional
    @Query("UPDATE IncidentActionItem a SET a.remindedOn = :today WHERE a.id = :id AND a.remindedOn IS NULL")
    int claimFirstReminder(@Param("id") Long id, @Param("today") String today);

    /** Hatırlatma sahiplenmesi (tekrar): son hatırlatma hâlâ {@code prev} ise yazar. */
    @Modifying
    @Transactional
    @Query("UPDATE IncidentActionItem a SET a.remindedOn = :today WHERE a.id = :id AND a.remindedOn = :prev")
    int claimNextReminder(@Param("id") Long id, @Param("prev") String prev, @Param("today") String today);

    /** Son tarih değişti → hatırlatma baştan (2026-10-22, IN-1): hedefli UPDATE — varlık kaydı {@code reminded_on}'u yazmaz. */
    @Modifying
    @Transactional
    @Query("UPDATE IncidentActionItem a SET a.remindedOn = NULL WHERE a.id = :id")
    int resetReminder(@Param("id") Long id);

    @Modifying
    @Transactional
    @Query("DELETE FROM IncidentActionItem a WHERE a.incidentId = :incidentId")
    int deleteForIncident(@Param("incidentId") Long incidentId);
}
