package com.sitemonitor.model;

import com.sitemonitor.service.baseline.BaselineParams;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.DynamicUpdate;

/**
 * Bir izlemenin dinamik taban çizgisi (yanıt-süresi anomalisi) yapılandırması — izleme başına opt-in. 8 izleme entity'sine
 * DOKUNMAMAK için ayrı, {@code (monitor_type, monitor_id)} anahtarlı tablo (dbquery / evalstate deseni). {@code enabled}
 * tek kapıdır: açıkken o izlemenin "yavaş" (SLOW) alarmı sabit eşik yerine öğrenen bant ile verilir; kapalıyken (satır yok ya
 * da {@code enabled=false}) davranış BİREBİR eskisi gibi. Varsayılan KAPALI.
 *
 * <p>Parametreler dbquery akıllı kuralıyla aynıdır ({@link BaselineParams}); {@code window} SQL sözcüğü olduğundan sütun
 * {@code window_size}. Lenient okuma: sınır dışı değer {@link BaselineParams#normalized()} ile öntanımlıya çekilir.</p>
 */
@Entity
@Table(name = "monitor_baseline_config",
        uniqueConstraints = @UniqueConstraint(name = "ux_mbc_monitor", columnNames = {"monitor_type", "monitor_id"}),
        indexes = @Index(name = "idx_mbc_enabled", columnList = "enabled"))
@DynamicUpdate
@Getter
@Setter
public class MonitorBaselineConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** İzleme türü anahtarı ({@code BaselineTypes}). */
    @Column(name = "monitor_type", length = 16, nullable = false)
    private String monitorType;

    @Column(name = "monitor_id", nullable = false)
    private Long monitorId;

    /** Dinamik taban çizgisi bu izleme için açık mı (tek kapı). */
    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "direction", length = 8)
    private String direction;

    @Column(name = "k_warning")
    private Double kWarning;

    @Column(name = "k_critical")
    private Double kCritical;

    @Column(name = "window_size")
    private Integer windowSize;

    @Column(name = "min_samples")
    private Integer minSamples;

    @Column(name = "season", length = 8)
    private String season;

    @Column(name = "created_at", length = 30)
    private String createdAt;

    @Column(name = "updated_at", length = 30)
    private String updatedAt;

    /** Saklanan (kısmi) parametreleri normalize edilmiş {@link BaselineParams}'a çevirir — boşlar öntanımlı. */
    public BaselineParams toParams() {
        BaselineParams d = BaselineParams.defaults();
        return new BaselineParams(
                direction == null ? d.direction() : direction,
                kWarning == null ? d.kWarning() : kWarning,
                kCritical == null ? d.kCritical() : kCritical,
                windowSize == null ? d.window() : windowSize,
                minSamples == null ? d.minSamples() : minSamples,
                season == null ? d.season() : season
        ).normalized();
    }
}
