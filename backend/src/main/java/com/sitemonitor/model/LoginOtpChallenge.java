package com.sitemonitor.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

/**
 * Kodla giriş (push / e-posta tek kullanımlık kod) isteği — 2026-10-02, kullanıcı isteği ("LDAP sorununda kullanıcılar
 * kodla girsin"). İstek başına TEK satır; doğrulama başka pod'a düşebildiği için durum veritabanında yaşar.
 *
 * <p><b>Kodun kendisi HİÇBİR alanda yoktur.</b> {@link #codeHmac} = HMAC-SHA256(anahtar, challenge kimliği + kod) — anahtar
 * {@code SITE_MONITOR_SECRET_KEY}'den türetilir (bkz. {@code OtpCodes}); veritabanını okuyan biri kodu geri üretemez
 * (anahtar olmadan 10^6 adayı deneyemez). Kod yalnız gönderim işinin belleğinde ve kullanıcıya giden mesajda bulunur.
 *
 * <p><b>Tuzak (decoy) satırı:</b> uygun olmayan / bilinmeyen kullanıcı / sınır aşımı isteği de aynı biçimde bir satır
 * doğurur — {@link #userId} {@code null}, {@link #deliveryStatus} {@code SUPPRESSED_*}, kod hiç gönderilmez. Doğrulaması
 * gerçek yanlış kodla birebir aynı yanıtları verir (OTP_INVALID + kalan deneme → kilit / süre doldu); asla oturum açmaz.
 *
 * <p>Saklama: {@code created_at} üzerinden {@code site.monitor.login.otp.retention-days} (RetentionCatalog
 * {@code login-otp-challenges}). Şema: {@code SchedulerService.applySchemaPatches} (tablo + dizinler).
 */
@Entity
@Table(name = "login_otp_challenges")
@Data
@NoArgsConstructor
public class LoginOtpChallenge implements Persistable<String> {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_VERIFIED = "VERIFIED";
    public static final String STATUS_LOCKED = "LOCKED";
    public static final String STATUS_EXPIRED = "EXPIRED";
    /** Doğru koddan SONRA giriş reddedildi (pasif / bakım / hesap kilidi) — kod yeniden kullanılamaz. */
    public static final String STATUS_BLOCKED = "BLOCKED";

    public static final String CHANNEL_PUSH = "PUSH";
    public static final String CHANNEL_EMAIL = "EMAIL";

    public static final String DELIVERY_QUEUED = "QUEUED";
    public static final String DELIVERY_SENT = "SENT";
    /** Gönderilemedi — ardından kısa neden (HTTP kodu / istisna sınıfı); kod ya da mesaj ASLA. */
    public static final String DELIVERY_FAILED_PREFIX = "FAILED";
    /** Tuzak satırı: kod gönderilmedi (neden iç kodu ekli — yalnız denetim/ayar ekranı görür). */
    public static final String DELIVERY_SUPPRESSED_PREFIX = "SUPPRESSED_";

    /** Rastgele UUID — tarayıcı yalnız KENDİ isteğinin kimliğini bilir; kod başka kimlikle geçersizdir. */
    @Id
    @Column(name = "id", length = 36)
    private String id;

    /** Kanonik kullanıcı adı (kullanıcı varsa kayıtlı ad, yoksa büyük harfe çevrilmiş yazılan ad). */
    @Column(name = "username", length = 120, nullable = false)
    private String username;

    /** Kod GERÇEKTEN gönderildiyse kullanıcı kimliği; tuzak satırında null (asla oturum açmaz). */
    @Column(name = "user_id")
    private Long userId;

    /** {@link #CHANNEL_PUSH} / {@link #CHANNEL_EMAIL}. */
    @Column(name = "channel", length = 10, nullable = false)
    private String channel;

    /** HMAC-SHA256(anahtar, id + ":" + kod) — 64 hex. Düz kod hiçbir yerde saklanmaz. */
    @Column(name = "code_hmac", length = 64, nullable = false)
    private String codeHmac;

    @Column(name = "created_at", length = 30, nullable = false)
    private String createdAt;

    @Column(name = "expires_at", length = 30, nullable = false)
    private String expiresAt;

    /** Yanlış deneme sayısı (atomik artırım — çok pod). */
    @Column(name = "attempts")
    private Integer attempts;

    @Column(name = "max_attempts")
    private Integer maxAttempts;

    /** Son yanlış denemenin anı — kullanıcı başına başarısız doğrulama penceresi bundan sayılır. */
    @Column(name = "last_failed_at", length = 30)
    private String lastFailedAt;

    /** Tek kullanım: doğru kodla tüketildiği an. */
    @Column(name = "consumed_at", length = 30)
    private String consumedAt;

    @Column(name = "status", length = 20)
    private String status;

    /** QUEUED / SENT / FAILED: … / SUPPRESSED_… — içerik (kod, mesaj) ASLA yazılmaz. */
    @Column(name = "delivery_status", length = 60)
    private String deliveryStatus;

    @Column(name = "ip", length = 64)
    private String ip;

    @Column(name = "user_agent", length = 255)
    private String userAgent;

    /** Atanmış kimlikli varlık: yeni satırda {@code save} MERGE (ek SELECT) yerine doğrudan INSERT yapsın. */
    @Transient
    private boolean fresh = true;

    @Override
    public boolean isNew() {
        return fresh;
    }

    @PostLoad
    @PostPersist
    void markPersisted() {
        this.fresh = false;
    }

    /** Tuzak satırı mı (kod gönderilmedi → doğrulama asla başarılı olamaz). */
    public boolean decoy() {
        return userId == null || (deliveryStatus != null && deliveryStatus.startsWith(DELIVERY_SUPPRESSED_PREFIX));
    }
}
