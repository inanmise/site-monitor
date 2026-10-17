package com.sitemonitor.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Teknik hata raporu PO onayı için e-posta bağlantı belirteci (2026-10-17, S6: "PO e-postadaki bağlantıdan ya da Site
 * Monitor'den onaylar"). Her onay isteğinde onaylayıcı BAŞINA bir satır: bağlantıdan gelen karar kimin olduğu bilinir.
 * Veritabanında yalnız SHA-256 özeti durur; ham belirteç yalnız postadaki bağlantıda. Tek kullanımlık ({@code used_at}),
 * süreli ({@code expires_at}); yeni bir istek eski satırları SİLMEZ, "yerine geçti" damgalar ({@code superseded_at},
 * inceleme 2026-10-17 A2: silinen belirteç eski postadaki bağlantıyı "geçersiz bağlantı" diye gösteriyordu — artık sayfa
 * "daha yeni bir istek gönderildi, en son postayı kullanın" der; yerine geçilmiş satır hiçbir zaman karar vermez). Süresi
 * de dolmuş yerine geçilmiş satırlar bir sonraki istekte budanır. Rapor silinince aynı işlemde silinir; öksüz satırı saklama
 * kuralı temizler. Eski sürümden kalan satırda {@code superseded_at} NULL = yerine geçilmemiş.
 */
@Entity
@Table(name = "tech_error_approval_tokens",
        uniqueConstraints = @UniqueConstraint(name = "uk_tea_token_hash", columnNames = {"token_hash"}),
        indexes = @Index(name = "idx_tea_report", columnList = "report_id"))
@Data
@NoArgsConstructor
public class TechErrorApprovalToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "report_id", nullable = false)
    private Long reportId;

    /** Bağlantının sahibi onaylayıcı (opak kimlik kuralı: yanıta yazılmaz). */
    @JsonIgnore
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @JsonIgnore
    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    /** İsteğin içerik sayacı: bağlantı yalnız bu içeriğin onayı için geçerli. */
    @Column(name = "approval_rev")
    private Long approvalRev;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    /** Yeni bir onay isteği bu satırın yerine geçti (A2); NULL = geçerli istek. Yerine geçilmiş belirteç karar veremez. */
    @Column(name = "superseded_at")
    private Instant supersededAt;
}
