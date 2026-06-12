package com.certmonitor.repository;

import com.certmonitor.model.EscalationContact;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EscalationContactRepository extends JpaRepository<EscalationContact, Long> {
    List<EscalationContact> findByActiveTrueOrderByRoleAsc();
    List<EscalationContact> findByMinAlertLevelInAndActiveTrue(List<String> levels);
    List<EscalationContact> findByMinAlertLevelAndActiveTrue(String level);

    // Team-scoped variants
    List<EscalationContact> findByTeamIdAndActiveTrueOrderByRoleAsc(Long teamId);
    List<EscalationContact> findByTeamIdAndMinAlertLevelInAndActiveTrue(Long teamId, List<String> levels);
    List<EscalationContact> findByTeamIdAndMinAlertLevelAndActiveTrue(Long teamId, String level);
    List<EscalationContact> findByTeamIdOrderByRoleAsc(Long teamId);
    List<EscalationContact> findByUserId(Long userId);
    boolean existsByTeamIdAndActiveTrue(Long teamId);

    /** Haftalık rapor akışı: takımın PO / MANAGER kontaklarını çözer. */
    List<EscalationContact> findByTeamIdAndRoleAndActiveTrue(Long teamId, String role);
}
