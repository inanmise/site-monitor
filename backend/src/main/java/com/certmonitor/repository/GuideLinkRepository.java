package com.certmonitor.repository;

import com.certmonitor.model.GuideLink;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface GuideLinkRepository extends JpaRepository<GuideLink, Long> {
    List<GuideLink> findAllByOrderByCategoryAscSortOrderAscIdAsc();
}
