package com.certmonitor.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Tanılama çalıştırma kaydı — kim, ne zaman, nereden, hangi domain için hangi
 * tür tanılama çalıştırdı ve sonucu neydi. Hem bağlantı matrisi (CONNECTION)
 * hem openssl derin taraması (OPENSSL) buraya yazılır; geçmiş modal'ı bundan
 * beslenir ve resultJson ile sonuç birebir yeniden gösterilir.
 */
@Entity
@Table(name = "diagnostic_runs",
    indexes = {
        @Index(name = "idx_diag_domain", columnList = "domain"),
        @Index(name = "idx_diag_executed_at", columnList = "executedAt")
    })
@Data
@NoArgsConstructor
public class DiagnosticRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 255)
    private String domain;

    private Integer port;

    /** CONNECTION | OPENSSL */
    @Column(name = "run_type", nullable = false, length = 20)
    private String runType;

    @Column(length = 100)
    private String executedBy;

    private Long executedById;
    private Long executedByTeamId;

    @Column(nullable = false, length = 30)
    private String executedAt;

    /** İsteğin geldiği IP — "nereden çalıştırıldı". */
    @Column(name = "source_ip", length = 60)
    private String sourceIp;

    @Column(nullable = false)
    private Boolean success;

    /** Kısa özet — liste rozetinde gösterilir. */
    @Column(columnDefinition = "TEXT")
    private String summary;

    /** Tam sonuç JSON'u — geçmişten yeniden render için. */
    @Column(name = "result_json", columnDefinition = "TEXT")
    private String resultJson;
}
