package com.sitemonitor.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(
    name = "latest_checks",
    indexes = {
        @Index(name = "idx_lc_warning",    columnList = "warning"),
        @Index(name = "idx_lc_status",     columnList = "status"),
        @Index(name = "idx_lc_checked_at", columnList = "checkedAt")
    }
)
@Data
@NoArgsConstructor
public class LatestCheck {

    @Id
    private String domain;

    private String subject;
    private String issuer;
    private String issuerCn;
    private String notBefore;
    private String notAfter;
    private Integer daysRemaining;
    private Boolean warning;
    private String status;
    private String error;

    @Column(columnDefinition = "TEXT")
    private String san;

    private String fingerprint;
    private String chainStatus;
    private String deploymentStatus;
    private String intermediateExpiry;
    private Integer intermediateDaysRemaining;
    private String revocationStatus;
    /** TRUSTED / UNTRUSTED / UNKNOWN — zincir cacerts veya admin CA paketiyle güven köküne bağlanıyor mu. */
    private String trustStatus;

    @Column(columnDefinition = "TEXT")
    private String chainDetails;

    // Extended certificate metadata
    private String serialNumber;
    private String signatureAlgorithm;
    private String publicKeyAlgorithm;
    private Integer publicKeySize;

    @Column(columnDefinition = "TEXT")
    private String subjectDn;

    @Column(columnDefinition = "TEXT")
    private String issuerDn;

    @Column(columnDefinition = "TEXT")
    private String keyUsage;

    @Column(columnDefinition = "TEXT")
    private String extKeyUsage;

    private Boolean isCa;
    private String ocspUrl;
    private String crlUrl;

    /** Check transport metadata — path ("proxy"/"direct") and TLS mode the
     *  final attempt used. Null on rows written before v18.53. */
    private String via;
    private String tlsModeUsed;

    /** Anlaşılan TLS sürümü ve cipher suite — checker bunları zaten üretiyordu ama HİÇBİR yerde
     *  saklanmıyordu, dolayısıyla süpürme verisinden protokol/şifreleme sağlığı okunamıyordu.
     *  Eski satırlarda null kalır; bir sonraki saatlik süpürme doldurur (backfill gerekmez). */
    @Column(length = 20)
    private String tlsVersion;

    @Column(length = 100)
    private String cipherSuite;

    /** Uygulama katmanı sağlık kontrolleri — modal açılışında DEĞİL, kullanıcı isteğiyle koşar
     *  (K3/K4). Durum kısa bir etiket ("CLEAN"/"MIXED"/"ENABLED"/"MISSING"/"UNKNOWN"), yanında
     *  ne zaman bakıldığı; kanıt detayı gerekmiyor — satır tıklanınca canlı bakılabilir. */
    @Column(length = 20)
    private String mixedContentStatus;
    private String mixedContentAt;

    /** UNKNOWN'un gerekçesi — hstsNote ile aynı sözleşme. Bu alan EKSİKTİ: karışık içerik
     *  kontrolü koşup sonuç belirlenemediğinde ("ana sayfa HTTP 404" gibi) arayüz hem sebebi
     *  gösteremiyor hem de satırı "Kontrol edilmedi" diye etiketliyordu — kullanıcı "Şimdi
     *  kontrol et"e basıyor, kontrol GERÇEKTEN koşuyor, ekran değişmiyordu.
     *  Nullable: dolu tabloya NOT NULL kolon eklemek sessizce düşer. */
    private String mixedContentNote;

    @Column(length = 20)
    private String hstsStatus;
    private String hstsAt;

    /** UNKNOWN'un gerekçesi (bağlantı hatası + vekil kararı); ENABLED/MISSING'de null.
     *  Nullable: dolu tabloya NOT NULL kolon eklemek sessizce düşer ve her sorgu 500 verirdi. */
    private String hstsNote;

    /**
     * OTOMATİK parmak izi pini (TOFU — ilk görüşte güven). Kullanıcıdan hiçbir aksiyon istenmez:
     * sertifika ilk görüldüğünde sabitlenir, her kontrolde sunulanla karşılaştırılır ve DEĞİŞTİĞİ
     * anda yenisi sabitlenip değişim tarihiyle birlikte kaydedilir.
     *
     * <p>Envanterdeki {@code expectedFingerprint} ile KARIŞTIRILMAMALI: orası kullanıcının elle
     * girdiği alandır ve ona sessizce yazmak, kullanıcının yazmadığı bir değeri kendi girmiş gibi
     * görmesine yol açardı.
     */
    @Column(length = 200)
    private String pinnedFingerprint;
    private String pinnedAt;

    /** Değişimden ÖNCEKİ parmak izi ve değişimin görüldüğü an — "ne zaman, neyden neye" sorusu. */
    @Column(length = 200)
    private String previousFingerprint;
    private String fingerprintChangedAt;

    private String checkedAt;
    private String updatedAt;
}
