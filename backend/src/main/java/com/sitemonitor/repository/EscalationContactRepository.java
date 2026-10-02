package com.sitemonitor.repository;

import com.sitemonitor.model.EscalationContact;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EscalationContactRepository extends JpaRepository<EscalationContact, Long> {
    /**
     * TÜM takımların etkin kontakları — YALNIZ yönetim listesi ({@code AdminController.listContacts}) içindir.
     * Alarm alıcısı çözümünde KULLANILMAZ: takım süzgeci yoktur, başka takımların müdürlerini getirir
     * (2026-09-28 prod hatası). Alıcı çözümü {@code EscalationContactScope.forLevel} üzerinden yapılır;
     * kapılar {@code EscalationContactScopeTest.repository_unscopedReadsAreWhitelisted} (depoda süzgeçsiz okuma
     * yalnız izinli listede) ve {@code EscalationContactScopeTest.source_recipientQueriesOnlyThroughScope} (bu sorgu
     * yalnız yönetim listesinde; seviye eşikli seçim yalnız kapsam sınıfında).
     */
    List<EscalationContact> findByActiveTrueOrderByRoleAsc();

    // Team-scoped variants — alarm alıcısı çözümü YALNIZ bunları kullanır (takımsız kontak hiçbir yolda alıcı değil).
    List<EscalationContact> findByTeamIdAndActiveTrueOrderByRoleAsc(Long teamId);
    List<EscalationContact> findByTeamIdAndMinAlertLevelInAndActiveTrue(Long teamId, List<String> levels);
    List<EscalationContact> findByTeamIdAndMinAlertLevelAndActiveTrue(Long teamId, String level);
    List<EscalationContact> findByTeamIdOrderByRoleAsc(Long teamId);
    // Faz 3b — çok-takım kapsamı
    List<EscalationContact> findByTeamIdInAndActiveTrueOrderByRoleAsc(java.util.Collection<Long> teamIds);
    List<EscalationContact> findByTeamIdInOrderByRoleAsc(java.util.Collection<Long> teamIds);
    List<EscalationContact> findByUserId(Long userId);
    boolean existsByTeamIdAndActiveTrue(Long teamId);

    /** Haftalık rapor akışı: takımın PO / MANAGER kontaklarını çözer. */
    List<EscalationContact> findByTeamIdAndRoleAndActiveTrue(Long teamId, String role);

    /**
     * Zamana bağlı eskalasyon adımı işinin UCUZ ön kapısı (2026-10-01): herhangi bir etkin kişide gecikme tanımlı mı?
     * Takım süzgeci BİLİNÇLİ yok — yalnız boolean döner, alıcı DÖNDÜRMEZ (gecikme tanımsız kurulumda iş kilit almadan tek
     * sorguyla biter). Alıcılar yine alarmın sahip takımlarından ({@code findByTeamIdInAndActiveTrueOrderByRoleAsc} +
     * {@code EscalationContactScope}) çözülür. {@code EscalationContactScopeTest.UNSCOPED_READS_ALLOWED} listesinde.
     */
    boolean existsByActiveTrueAndDelayMinutesGreaterThan(Integer minutes);
}
