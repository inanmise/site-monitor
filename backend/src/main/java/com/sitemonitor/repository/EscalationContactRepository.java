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

    // ── Toplu işlem hedefli yazımları (2026-11-13) ─────────────────────────────────────────────
    // Bayat bir okumadan TÜM varlık yazılmaz: aynı anda yapılan tekil düzenleme (ad, e-posta, webhook, gecikme) asla
    // ezilmez. Her UPDATE yalnız kendi kolonunu yazar ve okunan değere koşulludur — 0 satır = kayıt bu arada değişti.
    // {@code teamKey} = okunan takım ya da takımsız (eski kayıt) için -1: bu arada başka takıma taşınan kayda yazılmaz.

    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query("UPDATE EscalationContact c SET c.active = :active "
            + "WHERE c.id = :id AND COALESCE(c.teamId, -1L) = :teamKey AND (c.active IS NULL OR c.active <> :active)")
    int bulkSetActive(@org.springframework.data.repository.query.Param("id") Long id,
                      @org.springframework.data.repository.query.Param("teamKey") Long teamKey,
                      @org.springframework.data.repository.query.Param("active") Boolean active);

    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query("UPDATE EscalationContact c SET c.minAlertLevel = :level "
            + "WHERE c.id = :id AND COALESCE(c.teamId, -1L) = :teamKey AND (c.minAlertLevel IS NULL OR c.minAlertLevel <> :level)")
    int bulkSetLevel(@org.springframework.data.repository.query.Param("id") Long id,
                     @org.springframework.data.repository.query.Param("teamKey") Long teamKey,
                     @org.springframework.data.repository.query.Param("level") String level);

    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query("UPDATE EscalationContact c SET c.teamId = :toTeam "
            + "WHERE c.id = :id AND COALESCE(c.teamId, -1L) = :fromKey")
    int bulkMoveTeam(@org.springframework.data.repository.query.Param("id") Long id,
                     @org.springframework.data.repository.query.Param("fromKey") Long fromKey,
                     @org.springframework.data.repository.query.Param("toTeam") Long toTeam);
}
