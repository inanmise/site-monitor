package com.sitemonitor.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "remember_me_tokens",
       indexes = @Index(name = "idx_rmt_token", columnList = "token", unique = true))
@Getter @Setter @NoArgsConstructor
public class RememberMeToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String token;

    @Column(nullable = false, length = 128)
    private String username;

    @Column(name = "expires_at", nullable = false)
    private long expiresAt; // Unix epoch seconds

    // ── Cihaz meta'sı (Cihaz Geçmişi ekranı) ─────────────────────────────────
    //
    // HEPSİ NULLABLE olmak ZORUNDA: dolu bir tabloya NOT NULL kolon eklemek Postgres tarafından
    // reddedilir, Hibernate hatayı yutar ve kolon HİÇ oluşmaz — sonra o tabloya giden her sorgu
    // 500 verir (projede yaşandı). Yeni satırlar dolu gelir; deploy öncesi satırlar boş kalır ve
    // arayüz onları "Oturum" genel etiketiyle çizer.
    //
    // Bunlar KİMLİK DOĞRULAMA verisi DEĞİL, yalnız kullanıcının kendi cihazını tanıması içindir;
    // token eşleştirmesi hâlâ yalnız hash üzerinden yapılır.

    /** Token'ın üretildiği an (ISO-UTC) — "ne zamandan beri hatırlanıyor". */
    @Column(name = "created_at")
    private String createdAt;

    /** Bu token'la EN SON otomatik giriş yapılan an (ISO-UTC) — kullanılmayan cihazı ayırt eder. */
    @Column(name = "last_used_at")
    private String lastUsedAt;

    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    /** {@code UserAgentSummary} çıktısının kısa gösterimi, ör. "Windows · Chrome". Ham UA SAKLANMAZ. */
    @Column(name = "ua_summary", length = 128)
    private String uaSummary;

    @Column(name = "ip_city", length = 128)
    private String ipCity;

    @Column(name = "ip_country", length = 64)
    private String ipCountry;
}
