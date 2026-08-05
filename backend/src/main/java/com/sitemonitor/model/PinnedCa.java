package com.sitemonitor.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Otomatik sabitlenen (auto-pin / TOFU) CA kaydı — host:port başına, sunucunun sunduğu zincirden
 * alınan CA sertifikaları (PEM, birleştirilmiş). HTTP uptime strict TLS yolu ve RDAP çıkışı için
 * güven kaynağıdır; sertifika trust_status raporu bu pinlere BAKMAZ. Rotasyon kimliği
 * fingerprintSha256 üzerinden izlenir; süre yenilemesi notAfter (pinlenen sertifikaların MIN'i)
 * ile zamanlanır (PKIX trust anchor'ın geçerliliğini kontrol etmez → proaktif yenileme şart).
 */
@Entity
@Table(
    name = "pinned_cas",
    uniqueConstraints = @UniqueConstraint(name = "uq_pca_host_port", columnNames = {"host", "port"}),
    indexes = @Index(name = "idx_pca_not_after", columnList = "notAfter")
)
@Data
@NoArgsConstructor
public class PinnedCa {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 255)
    private String host;

    @Column(nullable = false)
    private Integer port;

    /** Pinlenen sertifikalar (CA'lar; zincir tek elemanlıysa leaf'in kendisi) — birleştirilmiş PEM. */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String pem;

    /** Pin setinin kimliği: DER'lerin sıralı birleşimi üzerinden SHA-256 (hex). */
    @Column(nullable = false, length = 64)
    private String fingerprintSha256;

    /** En üst pinlenen sertifikanın subject DN'i (görünürlük için). */
    @Column(length = 500)
    private String subject;

    /** Pinlenen sertifikaların en erken bitişi (ISO UTC) — proaktif yenileme bu alana bakar. */
    @Column(nullable = false, length = 30)
    private String notAfter;

    @Column(nullable = false, length = 30)
    private String pinnedAt;

    /** Son pin sebebi: http-check / scheduled-refresh. */
    @Column(length = 50)
    private String lastReason;
}
