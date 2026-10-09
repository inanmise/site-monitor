package com.sitemonitor.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * BİLİNMEYEN kullanıcı adının ilerleyici giriş kilidi (2026-10-09, kullanıcı adı numaralandırması kapatıldı).
 *
 * <p>Mevcut hesabın kilit durumu {@code app_users}'ta ({@code failed_block_count / lockout_until / last_lockout_at}) yaşar;
 * olmayan bir ad için o satır yoktur ve yaratılmaz. Eskiden bilinmeyen ad pod belleğinde hep 1. kademeyi taklit ediyordu:
 * 2. kademeden itibaren (mevcut hesap 120 / 600 / 1800 sn alırken bilinmeyen ad yine 30 sn) ve başka pod'da (kalan süre
 * yerine taze yanıt) hesabın varlığı yanıttan okunuyordu. Bu tablo aynı üç alanı AYNI anlamla tutar — merdiven hesabı
 * {@code LockoutLadder}'dan, okuma tüm pod'larda ortak.
 *
 * <p>Satır yalnız bilinmeyen ad kaba kuvvet eşiğine ulaşınca doğar (ilk kilit). Saklama: {@code UnknownUserLockoutService}
 * kendisi temizler ({@code updated_at} + sert satır üst sınırı; RetentionCatalog {@code login-unknown-lockouts}, EXTERNAL).
 * Parola, IP ya da istemci bilgisi YOK — yalnız kanonik ad ve kilit damgaları. Şema: {@code SchedulerService.applySchemaPatches}.
 */
@Entity
@Table(name = "login_unknown_lockouts",
        indexes = @Index(name = "idx_login_unknown_lockouts_updated", columnList = "updated_at"))
@Data
@NoArgsConstructor
public class LoginUnknownLockout {

    /** Kanonik ad: büyük harf (Locale.ROOT), denetim aktörüyle AYNI biçimde 100 karaktere kırpılmış. */
    @Id
    @Column(name = "username_key", length = 100)
    private String usernameKey;

    /** Uygulanmış kilit sayısı — {@code app_users.failed_block_count} karşılığı (gereken hata sayısını ve süreyi belirler). */
    @Column(name = "lockout_level")
    private Integer lockoutLevel;

    /** Kilidin bittiği an (UTC ISO, {@code Z}'siz) — {@code app_users.lockout_until} karşılığı. */
    @Column(name = "lockout_until", length = 30)
    private String lockoutUntil;

    /** Son kilidin uygulandığı an — kaba kuvvet sayımının başlangıcı ({@code app_users.last_lockout_at} karşılığı). */
    @Column(name = "last_lockout_at", length = 30)
    private String lastLockoutAt;

    @Column(name = "created_at", length = 30)
    private String createdAt;

    /** Son yazım — saklama ve üst sınır temizliği buna bakar. */
    @Column(name = "updated_at", length = 30)
    private String updatedAt;
}
