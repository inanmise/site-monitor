package com.certmonitor.repository;

import com.certmonitor.model.EscalationContact;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EscalationContactRepository extends JpaRepository<EscalationContact, Long> {
    List<EscalationContact> findByActiveTrueOrderByRoleAsc();
    List<EscalationContact> findByMinAlertLevelInAndActiveTrue(List<String> levels);
    List<EscalationContact> findByMinAlertLevelAndActiveTrue(String level);
}
