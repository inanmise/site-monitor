package com.certmonitor.repository;

import com.certmonitor.model.NetworkOutageEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface NetworkOutageEventRepository extends JpaRepository<NetworkOutageEvent, Long> {

    Optional<NetworkOutageEvent> findFirstByStatusOrderByIdDesc(String status);

    @Query("SELECT e FROM NetworkOutageEvent e ORDER BY e.id DESC")
    List<NetworkOutageEvent> findRecent(Pageable pageable);
}
