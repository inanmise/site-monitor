package com.sitemonitor.repository;

import com.sitemonitor.model.NocDelivery;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface NocDeliveryRepository extends JpaRepository<NocDelivery, Long> {

    Optional<NocDelivery> findByDedupeKey(String dedupeKey);

    List<NocDelivery> findByDedupeKeyIn(Collection<String> keys);

    /** Fırtınanın EN SON güncelleme e-postası — güncellemeler arası en az aralık (fırtına başına ~5 dk). */
    Optional<NocDelivery> findTopByStormIdAndPhaseOrderByIdDesc(Long stormId, String phase);
}
