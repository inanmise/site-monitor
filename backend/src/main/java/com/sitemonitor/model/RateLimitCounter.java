package com.sitemonitor.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Pod'lar arası ORTAK oran sınırı sayacı (2026-10-23, çok pod Sürüm 2a — MP-31). Anahtar başına TEK satır: sabit pencere
 * (başlangıç + deneme sayısı) ve eşiğe ulaşınca blok bitişi. Eskiden giriş ucunun IP sınırı pod belleğindeydi
 * ({@code AuthController} üç harita): round-robin arkasında aynı IP'den N pod × 10 deneme geçiyordu ve bir pod'daki
 * başarılı giriş yalnız o pod'un sayacını sıfırlıyordu.
 *
 * <p>Anahtar {@code <kapsam>:<değer>} (ör. {@code login-ip:10.1.2.3}); uzun değer {@code <kapsam>:#<sha256>} olur
 * ({@code RateLimitCounterService.key}). Zamanlar epoch milisaniye (UTC). {@code blocked_until = 0} = blok yok.
 * Yalnız {@code RateLimitCounterService} yazar (tek ifadelik koşullu UPDATE / upsert — varlık kaydı YOK); satır
 * {@code expires_at}'ten sonra anlamsızdır ve saatlik temizlikte silinir (RetentionCatalog {@code rate-limit-counters},
 * EXTERNAL). Kişi verisi: istemci IP'si (anahtarda). Şema: varlık (ddl-auto) + {@code RateLimitCounterSchemaPatch}.
 */
@Entity
@Table(name = "rate_limit_counters",
        indexes = @Index(name = "idx_rlc_expires", columnList = "expires_at"))
@Data
@NoArgsConstructor
public class RateLimitCounter {

    /** {@code <kapsam>:<değer>} — en çok {@value com.sitemonitor.service.lockout.RateLimitCounterService#KEY_MAX} karakter. */
    @Id
    @Column(name = "limit_key", length = 120)
    private String limitKey;

    /** Geçerli sayım penceresinin başladığı an (epoch ms). */
    @Column(name = "window_start", nullable = false)
    private Long windowStart;

    /** Pencere içindeki deneme sayısı. */
    @Column(name = "attempts", nullable = false)
    private Integer attempts;

    /** Blok bitişi (epoch ms); 0 = blok yok. */
    @Column(name = "blocked_until", nullable = false)
    private Long blockedUntil;

    /** Bu andan sonra satır hiçbir kararı etkilemez (pencere + blok bitmiştir) — temizlik ölçütü (epoch ms). */
    @Column(name = "expires_at", nullable = false)
    private Long expiresAt;

    /** Son yazım (epoch ms). */
    @Column(name = "updated_at", nullable = false)
    private Long updatedAt;
}
