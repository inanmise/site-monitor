package com.sitemonitor.repository;

import com.sitemonitor.model.MonitorBaselineConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/** Dinamik taban çizgisi izleme-başına yapılandırması. */
public interface MonitorBaselineConfigRepository extends JpaRepository<MonitorBaselineConfig, Long> {

    Optional<MonitorBaselineConfig> findByMonitorTypeAndMonitorId(String monitorType, Long monitorId);

    /** Tarama lideri açık izlemeleri 30 sn'de bir anlık görüntüler (bellek) — yalnız açık satırlar. */
    List<MonitorBaselineConfig> findByEnabledTrue();

    /** İzleme silinince / türü değişince yapılandırmayı temizler. */
    @Modifying
    @Transactional
    void deleteByMonitorTypeAndMonitorId(String monitorType, Long monitorId);

    @Query("select count(c) from MonitorBaselineConfig c where c.enabled = true")
    long countEnabled();

    /** Açık izleme sayısı türe göre — Grafana benimseme göstergesi (satır: [monitor_type, count]). */
    @Query("select c.monitorType, count(c) from MonitorBaselineConfig c where c.enabled = true group by c.monitorType")
    List<Object[]> countEnabledByType();
}
