package com.sitemonitor.repository;

import com.sitemonitor.model.AlarmFeedCaller;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** Alarm akışını çağıran IP'ler. Yazım {@code JdbcTemplate} ile (koşullu güncelle → yoksa ekle). */
public interface AlarmFeedCallerRepository extends JpaRepository<AlarmFeedCaller, String> {

    List<AlarmFeedCaller> findTop50ByOrderByLastSeenAtDesc();
}
