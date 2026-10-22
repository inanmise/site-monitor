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

    /*
     * Yönetici e-postalarının KÜME çapında durumu (2026-10-21, çok pod — MP-01). Eskiden "gönderilemedi, sonraki turda yeniden
     * dene" bayrakları sertifika taramasını koşan pod'un BELLEĞİNDEYDİ; tur başka pod'a düşünce yeniden deneme kayboluyordu.
     * Değerler: PENDING / RETRY (gönderilecek), SENDING (bir pod sahiplendi — damga {@code *_claimed_ms}; 10 dk'dan eskisi
     * yeniden sahiplenilebilir), SUPERSEDED (açılış postası gitmeden kesinti çözüldü — çözüm postası zaman çizelgesini taşır),
     * FAILED (deneme tavanı doldu) ya da e-posta servisinin kesin sonucu (SENT, SKIPPED_…, QUEUED_RETRY…). NULL = bu
     * sütunlardan ÖNCE yazılmış satır → GÖNDERİLMİŞ sayılır (eski kesintiler yeniden postalanmaz). Yazan tek yer
     * {@code service/outage/CertNetworkOutageService} (koşullu UPDATE'ler); varlık kaydı bu sütunlara dokunmaz.
     */
    @Column(name = "alert_mail_status", length = 255, insertable = false, updatable = false)
    private String alertMailStatus;

    @Column(name = "alert_mail_claimed_ms", insertable = false, updatable = false)
    private Long alertMailClaimedMs;

    @Column(name = "alert_mail_attempts", insertable = false, updatable = false)
    private Integer alertMailAttempts;

    @Column(name = "resolved_mail_status", length = 255, insertable = false, updatable = false)
    private String resolvedMailStatus;

    @Column(name = "resolved_mail_claimed_ms", insertable = false, updatable = false)
    private Long resolvedMailClaimedMs;

    @Column(name = "resolved_mail_attempts", insertable = false, updatable = false)
    private Integer resolvedMailAttempts;

    /**
     * Kesintinin son DOĞRULANMA anı (2026-10-21, çok pod — ikinci inceleme N5): tespitte ve her şüpheli turda yazılır; 24 sa
     * güvenlik kapanışı bundan sayılır (tespit anından değil). NULL = sütundan önceki satır (tespit anı kullanılır). Yazan tek
     * yer {@code CertNetworkOutageService}.
     */
    @Column(name = "last_confirmed_at", length = 30, insertable = false, updatable = false)
    private String lastConfirmedAt;
}
