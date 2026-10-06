package com.sitemonitor.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Elle yüklenen sertifikanın SÜRÜMÜ (2026-10-06, kullanıcı isteği: "yenileme yeni sürüm yükler, eskisi silinmez").
 *
 * <p>Bir envanter satırına ({@code cert_source = MANUAL}) bağlıdır; her yenileme yeni satır ekler, önceki satır
 * {@code current=false} + {@code superseded_*} damgasıyla KALIR (geçmişte görüntülenir). Envanter satırı başına
 * TAM OLARAK BİR {@code current=true} satır vardır (PostgreSQL'de kısmi tekil indeks de bunu korur).
 *
 * <p><b>Gizli malzeme YOK:</b> {@link #chainPem} yalnız AÇIK zinciri (yaprak + dosyada bulunan ara/kök sertifikalar)
 * taşır. Özel anahtar ve dosya parolası hiçbir kolona, log'a ya da denetim kaydına yazılmaz.
 *
 * <p>Saklama: envanter satırı yaşadıkça durur; kalıcı silme (purge) sürümleri de siler, yetim kalan satırlar
 * {@code manual-cert-versions-orphan} saklama kuralıyla temizlenir.
 */
@Entity
@Table(
    name = "manual_certificate_versions",
    indexes = {
        @Index(name = "idx_mcv_inventory_current", columnList = "inventory_id,is_current"),
        @Index(name = "idx_mcv_fingerprint",       columnList = "fingerprint")
    }
)
@Data
@NoArgsConstructor
public class ManualCertificateVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "inventory_id", nullable = false)
    private Long inventoryId;

    /** 1, 2, 3 … — envanter satırı içinde artan sürüm numarası. */
    @Column(nullable = false)
    private Integer version;

    /** İzlenen (geçerli) sürüm mü. Kolon adı {@code is_current} (CURRENT SQL'de anahtar sözcük). */
    @Column(name = "is_current", nullable = false)
    private Boolean current;

    private String supersededAt;

    @Column(length = 100)
    private String supersededBy;

    /** SHA-256 (büyük harf onaltılık) — {@code ChainValidationService.calculateFingerprint} ile aynı biçim. */
    @Column(length = 64, nullable = false)
    private String fingerprint;

    @Column(columnDefinition = "TEXT")
    private String subject;

    @Column(columnDefinition = "TEXT")
    private String subjectDn;

    @Column(columnDefinition = "TEXT")
    private String issuer;

    @Column(columnDefinition = "TEXT")
    private String issuerDn;

    @Column(length = 100)
    private String serialNumber;

    private String notBefore;
    private String notAfter;

    /** SAN listesi (JSON dizi). */
    @Column(columnDefinition = "TEXT")
    private String san;

    @Column(length = 40)
    private String keyAlg;

    private Integer keySize;

    @Column(length = 100)
    private String signatureAlgorithm;

    /** Açık anahtarın (SPKI) SHA-256'sı — yenilemede "anahtar değişti mi" sorusu için. */
    @Column(length = 64)
    private String publicKeySha256;

    /** AÇIK zincir (PEM): yaprak önce, ardından dosyada bulunan ara/kök sertifikalar. Özel anahtar ASLA. */
    @Column(columnDefinition = "TEXT", nullable = false)
    private String chainPem;

    private Integer chainCount;

    /** Temizlenmiş dosya adı (yol parçası yok, ≤ 255). Yapıştırılan metinde null. */
    @Column(length = 255)
    private String fileName;

    /** PEM | DER | PKCS7 | PKCS12 | JKS | JCEKS | BKS | ZIP | TEXT */
    @Column(length = 16)
    private String fileFormat;

    /** Anahtar deposu girdisinin takma adı (alias) — yalnız JKS/PKCS12/… dosyalarında. */
    @Column(length = 255)
    private String sourceAlias;

    @Column(length = 100)
    private String uploadedBy;

    private String uploadedByName;

    private String uploadedAt;

    @Column(length = 500)
    private String note;
}
