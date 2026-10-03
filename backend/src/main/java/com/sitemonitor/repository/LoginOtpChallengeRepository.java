package com.sitemonitor.repository;

import com.sitemonitor.model.LoginOtpChallenge;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Kodla giriş istekleri ({@code login_otp_challenges}, 2026-10-02). Bütün sayımlar TEK satırlık, dizinli sorgulardır
 * ({@code idx_otp_ip_created}, {@code idx_otp_user_created}, {@code idx_otp_user_failed}); durum geçişleri koşullu
 * {@code UPDATE} — iki pod aynı isteği aynı anda doğrulasa bile deneme sayacı kaybolmaz, kod iki kez tüketilmez.
 */
public interface LoginOtpChallengeRepository extends JpaRepository<LoginOtpChallenge, String> {

    /** IP başına istek sınırı — TÜM satırlar (tuzak dahil) sayılır: bilinmeyen ad da aynı kotayı tüketir. */
    long countByIpAndCreatedAtGreaterThanEqual(String ip, String since);

    /** Kullanıcı başına istek sınırı — yalnız kodu GERÇEKTEN gönderilen satırlar (saldırganın sürekli isteği bloğu uzatmasın). */
    long countByUsernameAndUserIdIsNotNullAndCreatedAtGreaterThanEqual(String username, String since);

    /**
     * Eşleşmeyen kişi bilgisi sayacı (2026-10-03): kullanıcı adı + gönderim durumu ({@code SUPPRESSED_CONTACT_MISMATCH})
     * + pencere — {@code idx_otp_user_created} üstünden. Kilit süresince yapılan istekler ({@code SUPPRESSED_CONTACT_LOCK})
     * sayılmaz (şifre kilidindeki "kilitliyken deneme sayılmaz" kuralıyla aynı).
     */
    long countByUsernameAndDeliveryStatusAndCreatedAtGreaterThanEqual(String username, String deliveryStatus, String since);

    /** Yeniden gönderme bekleme süresi — kullanıcı + kanal için son GERÇEK gönderim. */
    Optional<LoginOtpChallenge> findTopByUsernameAndChannelAndUserIdIsNotNullOrderByCreatedAtDesc(String username, String channel);

    /** Kullanıcı başına başarısız doğrulama (kayan pencere) — tuzak satırları da sayılır (bilinmeyen adla aynı davranış). */
    @Query("SELECT COALESCE(SUM(c.attempts), 0) FROM LoginOtpChallenge c WHERE c.username = :username AND c.lastFailedAt >= :since")
    long sumFailuresSince(@Param("username") String username, @Param("since") String since);

    /**
     * Yanlış deneme: sayaç +1, son hata anı; sınıra ulaşınca durum LOCKED. Yalnız PENDING satırda (eşzamanlı ikinci
     * yanlış deneme de sayılır — artırım veritabanında, okunan değere dayanmaz).
     */
    @Modifying
    @Transactional
    @Query("UPDATE LoginOtpChallenge c SET c.attempts = COALESCE(c.attempts, 0) + 1, c.lastFailedAt = :at, "
         + "c.status = CASE WHEN COALESCE(c.attempts, 0) + 1 >= c.maxAttempts THEN 'LOCKED' ELSE c.status END "
         + "WHERE c.id = :id AND c.status = 'PENDING'")
    int recordFailure(@Param("id") String id, @Param("at") String at);

    /** Tek kullanım: yalnız PENDING + süresi dolmamış + deneme hakkı kalmış satır tüketilir (1 = bu istek kazandı). */
    @Modifying
    @Transactional
    @Query("UPDATE LoginOtpChallenge c SET c.status = 'VERIFIED', c.consumedAt = :at "
         + "WHERE c.id = :id AND c.status = 'PENDING' AND c.expiresAt >= :at AND COALESCE(c.attempts, 0) < c.maxAttempts")
    int consume(@Param("id") String id, @Param("at") String at);

    /** PENDING satırın durumunu değiştirir (EXPIRED / BLOCKED). */
    @Modifying
    @Transactional
    @Query("UPDATE LoginOtpChallenge c SET c.status = :status, c.consumedAt = :at WHERE c.id = :id AND c.status = 'PENDING'")
    int closePending(@Param("id") String id, @Param("status") String status, @Param("at") String at);

    /** Doğru kod + "başka yerde oturum var" onayı: onay penceresi süresince istek canlı kalsın (süre yalnız UZAR). */
    @Modifying
    @Transactional
    @Query("UPDATE LoginOtpChallenge c SET c.expiresAt = :expiresAt WHERE c.id = :id AND c.status = 'PENDING' AND c.expiresAt < :expiresAt")
    int extendExpiry(@Param("id") String id, @Param("expiresAt") String expiresAt);

    /** Gönderim sonucu (SENT / FAILED: kısa neden) — içerik yazılmaz. */
    @Modifying
    @Transactional
    @Query("UPDATE LoginOtpChallenge c SET c.deliveryStatus = :status WHERE c.id = :id")
    int setDeliveryStatus(@Param("id") String id, @Param("status") String status);
}
