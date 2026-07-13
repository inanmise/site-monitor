package com.certmonitor.repository;

import com.certmonitor.model.MonitoringGroup;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface MonitoringGroupRepository extends JpaRepository<MonitoringGroup, Long> {

    /** get-or-create + benzersizlik — (takım, izleme türü) içinde case-insensitive. */
    Optional<MonitoringGroup> findByTeamIdAndTypeAndNameLower(Long teamId, String type, String nameLower);

    /** Form autocomplete — bir takımın belirli türdeki grupları. */
    List<MonitoringGroup> findByTeamIdAndTypeOrderByNameAsc(Long teamId, String type);

    /** Bir takımın tüm türlerdeki grupları. */
    List<MonitoringGroup> findByTeamIdOrderByTypeAscNameAsc(Long teamId);

    /** Kapsam-filtreli (kullanıcının görebildiği takımlar). */
    List<MonitoringGroup> findByTeamIdInOrderByTeamIdAscTypeAscNameAsc(Collection<Long> teamIds);

    /** Global admin — tüm takımların grupları. */
    List<MonitoringGroup> findAllByOrderByTeamIdAscTypeAscNameAsc();
}
