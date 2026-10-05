package com.sitemonitor.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "port_checks", indexes = {
    @Index(name = "idx_portc_monitor_id", columnList = "monitor_id"),
    @Index(name = "idx_portc_checked_at", columnList = "checked_at")
})
@Data
@NoArgsConstructor
public class PortCheck {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "monitor_id", nullable = false)
    private Long monitorId;

    @Column(nullable = false)
    private Boolean open = false;

    @Column(name = "response_ms")
    private Long responseMs;

    @Column(name = "checked_at")
    private String checkedAt;

    private String error;

    // ── Hata teşhisi (2026-10-05): YALNIZ kapalı/başarısız kontrolde dolu, NULL'lanabilir; açık/kapalı kararı ve alarm
    // DEĞİŞMEDİ. Ayrıntı checker'ın hesaplayıp eskiden attığı yolu (via) ve vekil reddini (proxy_refused) de taşır.

    /** Neden kodu ({@code CheckFailureReason}). */
    @Column(name = "failure_reason", length = 48)
    private String failureReason;

    /** Kompakt JSON ayrıntı (evre, hedef, yol, zaman aşımı, çözümlenen IP'ler, vekil reddi …), ≤ 4000 karakter. */
    @Column(name = "failure_detail", columnDefinition = "TEXT")
    private String failureDetail;
}
