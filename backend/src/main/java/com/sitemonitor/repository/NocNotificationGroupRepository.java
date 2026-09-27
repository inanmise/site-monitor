package com.sitemonitor.repository;

import com.sitemonitor.model.NocNotificationGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface NocNotificationGroupRepository extends JpaRepository<NocNotificationGroup, Long> {

    List<NocNotificationGroup> findAllByOrderByNameAsc();

    /** Ad benzersizliği (harf duyarsız; güncellemede kendi satırı hariç). */
    @Query("SELECT COUNT(g) > 0 FROM NocNotificationGroup g WHERE LOWER(g.name) = LOWER(:name) "
         + "AND (:excludeId IS NULL OR g.id <> :excludeId)")
    boolean existsByNameIgnoreCase(@Param("name") String name, @Param("excludeId") Long excludeId);
}
