package com.sitemonitor.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(
    name = "network_outage_events",
    indexes = {
        @Index(name = "idx_noe_detected",  columnList = "detectedAt"),
        @Index(name = "idx_noe_status",    columnList = "status")
    }
)
@Data
@NoArgsConstructor
public class NetworkOutageEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String detectedAt;

    private String resolvedAt;

    private Integer networkErrors;
    private Integer totalChecks;
    private Double  errorRate;
    private Double  threshold;
    private Long    durationMs;

    /** ONGOING or RESOLVED */
    @Column(nullable = false, length = 16)
    private String status;

    /**
     * Olayı ÜRETEN sweep: {@code null} ⇒ sertifika sweep'i (eski kayıtlar ve bugünkü davranış),
     * aksi halde izleme tipi ({@code ACCESSIBILITY}, {@code KEYWORD_FAIL}, {@code PORT_DOWN} …).
     *
     * <p>İzleme sweep'lerinin bastırması 2026-08'e kadar HİÇ kaydedilmiyordu; tek izi log
     * dosyasında tekrar eden bir WARN satırıydı. Aynı tabloya yazılıyorlar, bu yüzden kaynağın
     * ayrılabilmesi şart — yoksa "ağ kesintisi geçmişi" iki farklı olguyu tek listede karıştırır.
     */
    @Column(length = 32)
    private String source;
}
