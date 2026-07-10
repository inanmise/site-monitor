package com.certmonitor.repository;

import com.certmonitor.model.AlertStorm;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AlertStormRepository extends JpaRepository<AlertStorm, Long> {

    /** Yaşam döngüsü sweep'i — yönetilecek aktif (açık) storm'lar. */
    List<AlertStorm> findByResolvedFalse();

    /** Bir scope için aktif storm (kısmi UNIQUE indeks → en fazla bir tane). */
    Optional<AlertStorm> findByScopeKeyAndResolvedFalse(String scopeKey);
}
