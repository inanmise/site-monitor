package com.sitemonitor.repository;

import com.sitemonitor.model.AlarmFeedResponse;
import org.springframework.data.jpa.repository.JpaRepository;

/** Alarm akışı yanıt gövdeleri (özetle tekil, gzip'li). Yazım {@code JdbcTemplate} ile (çakışma = zaten var). */
public interface AlarmFeedResponseRepository extends JpaRepository<AlarmFeedResponse, String> {
}
