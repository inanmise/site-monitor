package com.sitemonitor.repository;

import com.sitemonitor.model.AlertEscalationStep;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/**
 * Zamana bağlı eskalasyon adımı kararları (2026-10-01). Yazma yalnız kalıtılan {@code saveAndFlush}/{@code save} ile
 * (SimpleJpaRepository kendi işlemini açar) — türetilmiş silme/@Modifying YOK (RepositoryWriteTransactionGuardTest).
 */
public interface AlertEscalationStepRepository extends JpaRepository<AlertEscalationStep, Long> {

    /** Adım işinin TOPLU okuması: aday alarmların tüm karar satırları (alarm başına sorgu yok). */
    List<AlertEscalationStep> findByAlertEventIdIn(Collection<Long> alertEventIds);

    /**
     * Alarmın "döngüsündeki" gecikmeli kişiler — adımı gönderilmiş ya da alarmı zaten almış ({@code SKIPPED} dışı her
     * satır). Anlık yollar (yeniden uyarı / seviye artışı / çözüm / elle gönderim) yalnız listede gecikmeli kişi varken sorar.
     */
    @Query("SELECT DISTINCT s.contactId FROM AlertEscalationStep s WHERE s.alertEventId = :id AND s.outcome <> 'SKIPPED'")
    List<Long> findNotifiedContactIds(@Param("id") Long alertEventId);
}
