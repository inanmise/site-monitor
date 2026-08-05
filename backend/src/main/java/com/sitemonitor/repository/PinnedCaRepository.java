package com.sitemonitor.repository;

import com.sitemonitor.model.PinnedCa;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PinnedCaRepository extends JpaRepository<PinnedCa, Long> {

    Optional<PinnedCa> findByHostAndPort(String host, Integer port);

    /** Proaktif yenileme: bitişi verilen ISO eşiğin altında/eşit olan pinler. */
    List<PinnedCa> findByNotAfterLessThanEqual(String isoThreshold);
}
