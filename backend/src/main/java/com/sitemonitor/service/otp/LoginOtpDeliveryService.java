package com.sitemonitor.service.otp;

import com.sitemonitor.model.LoginOtpChallenge;
import com.sitemonitor.repository.LoginOtpChallengeRepository;
import com.sitemonitor.service.AuditDetail;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.EmailNotificationService;
import com.sitemonitor.service.UserAgentSummary;
import com.sitemonitor.service.UserPushService;
import com.sitemonitor.service.mail.LoginCodeMail;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Kodla giriş kodunun TESLİMİ (2026-10-02) — isteği izleyen eşzamansız iş: yanıt süresi gönderimin (SMTP / push ağ
 * geçidi) süresini taşımasın, "uygun kullanıcı" ile "bilinmeyen kullanıcı" yanıt süresinden ayırt edilemesin.
 *
 * <p><b>Kendi küçük havuzu</b> (2 iş parçacığı, 200'lük kuyruk): kontrol havuzu ({@code certCheckExecutor}) taşmada
 * çağıranın iş parçacığında koşardı — o zaman gönderim yine istek süresine binerdi. Kuyruk doluysa gönderim yapılmaz,
 * satır {@code FAILED: QUEUE_FULL} olur ve denetime {@code LOGIN_OTP_DELIVERY_FAILED} düşer.
 *
 * <p><b>Gizlilik:</b> kod yalnız bu işin kapanışında ve giden mesajda yaşar — hiçbir log satırına, satır alanına,
 * denetim ayrıntısına ya da hata metnine yazılmaz. Push teslimat günlüğüne ({@code user_push_deliveries}) ve
 * {@code notification_logs}'a SATIR YAZILMAZ. Sonuç satıra yalnız {@code SENT} / {@code FAILED: <HTTP kodu | istisna sınıfı>}.
 */
@Slf4j
@Service
public class LoginOtpDeliveryService {

    private static final ZoneId IST = ZoneId.of("Europe/Istanbul");
    private static final DateTimeFormatter TR_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss").withZone(IST);

    private final LoginOtpChallengeRepository repo;
    private final UserPushService userPushService;
    private final EmailNotificationService emailService;
    private final AuditService auditService;

    private final ThreadPoolExecutor executor;
    /**
     * Push metni şablonu kaynağı (2026-10-03, Ayarlar → Giriş Yöntemleri) — yoksa (dilim testleri) yerleşik varsayılan.
     */
    private final LoginMethodsService methods;

    public LoginOtpDeliveryService(LoginOtpChallengeRepository repo, UserPushService userPushService,
                                   EmailNotificationService emailService, AuditService auditService) {
        this(repo, userPushService, emailService, auditService, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public LoginOtpDeliveryService(LoginOtpChallengeRepository repo, UserPushService userPushService,
                                   EmailNotificationService emailService, AuditService auditService,
                                   LoginMethodsService methods) {
        this.repo = repo;
        this.userPushService = userPushService;
        this.emailService = emailService;
        this.auditService = auditService;
        this.methods = methods;
        AtomicInteger n = new AtomicInteger();
        this.executor = new ThreadPoolExecutor(2, 2, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(200), r -> {
            Thread t = new Thread(r, "login-otp-delivery-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        }, new ThreadPoolExecutor.AbortPolicy());
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    /** Teslim edilecek iş — kullanıcı anlık görüntüsü (oturumsuz iş parçacığında varlık yüklemesi yok). */
    public record Job(String challengeId, LoginOtpService.Channel channel, String username, Long userId, Long teamId,
                      String role, String displayName, String email, int ttlSeconds, Instant requestedAt, String ip,
                      String userAgent, boolean english) { }

    /**
     * İşi kuyruğa alır; HEMEN döner. {@code code} yalnız kuyruktaki işin kapanışında yaşar.
     */
    public void dispatch(Job job, String code) {
        try {
            executor.execute(() -> deliver(job, code));
        } catch (RejectedExecutionException e) {
            finish(job, false, "QUEUE_FULL");
        }
    }

    /** Teslimi şimdi (çağıran iş parçacığında) yapar — test ve kuyruk işi ortak gövde. */
    void deliver(Job job, String code) {
        boolean ok;
        String why;
        try {
            if (job.channel() == LoginOtpService.Channel.PUSH) {
                UserPushService.DirectResult r = userPushService.sendDirect(job.username(), configuredTitle(job.english()),
                        configuredMessage(code, job.ttlSeconds(), job.english(), job.requestedAt()));
                ok = r != null && r.ok();
                why = r == null ? "NO_RESULT" : r.error();
            } else {
                String st = emailService.sendLoginCode(job.email(), new LoginCodeMail.Info(job.displayName(), code,
                        job.ttlSeconds(), TR_TIME.format(job.requestedAt()), job.ip(), UserAgentSummary.labelOf(job.userAgent())));
                // 421 sonrası yeniden deneme (≥ 90 sn sonra) kodun ömründen uzun — "gönderilemedi" sayılır (dürüst).
                ok = "SENT".equals(st);
                why = ok ? null : shortStatus(st);
            }
        } catch (Exception e) {
            ok = false;
            why = e.getClass().getSimpleName();
        }
        finish(job, ok, why);
    }

    private void finish(Job job, boolean ok, String why) {
        String status = ok ? LoginOtpChallenge.DELIVERY_SENT
                : LoginOtpChallenge.DELIVERY_FAILED_PREFIX + (why == null || why.isBlank() ? "" : ": " + clip(why, 50));
        try {
            repo.setDeliveryStatus(job.challengeId(), status);
        } catch (Exception e) {
            log.debug("Kodla giriş teslim durumu yazılamadı: {}", e.getClass().getSimpleName());
        }
        String shortId = job.challengeId() == null ? null : job.challengeId().substring(0, Math.min(8, job.challengeId().length()));
        if (ok) {
            log.info("Kodla giriş kodu gönderildi: user={} kanal={} istek={}", job.username(), job.channel(), shortId);
            return;
        }
        log.warn("Kodla giriş kodu GÖNDERİLEMEDİ: user={} kanal={} istek={} neden={}", job.username(), job.channel(), shortId, clip(why, 50));
        try {
            auditService.recordOtp("LOGIN_OTP_DELIVERY_FAILED", job.username(), job.userId(), job.teamId(), job.role(),
                    "FAILURE", "DELIVERY_FAILED: " + clip(why, 80),
                    AuditDetail.of("channel", job.channel().name(), "challenge", shortId, "reason", clip(why, 80)),
                    job.ip(), job.userAgent());
        } catch (Exception e) {
            log.debug("Kodla giriş teslim hatası denetime yazılamadı: {}", e.getClass().getSimpleName());
        }
    }

    /** Push başlığı (YERLEŞİK varsayılan) — marka tek kelime ("SiteMonitor", BRAND.md) push/arayüz metinlerinde. */
    static String pushTitle(boolean english) {
        return OtpPushTemplate.defaultTitle(english);
    }

    /** Push metni (YERLEŞİK varsayılan) — kod + geçerlilik + "siz değilseniz"; kanal-güvenli ayraç "-" (em-dash değil). */
    static String pushMessage(String code, int ttl, boolean english) {
        return OtpPushTemplate.fill(OtpPushTemplate.defaultMessage(english), code, String.valueOf(ttl), null);
    }

    /** Ayarlardaki başlık (2026-10-03): kayıtlı ve geçerliyse o, değilse yerleşik varsayılan. */
    String configuredTitle(boolean english) {
        String stored = null;
        try {
            stored = methods == null ? null : methods.pushTitleTemplate(english);
        } catch (Exception e) {
            log.debug("Kodla giriş push başlığı okunamadı: {}", e.getClass().getSimpleName());
        }
        if (stored != null && !stored.isBlank() && OtpPushTemplate.validateTitle(stored) != null) {
            log.warn("Kodla giriş push başlığı geçersiz ({}), yerleşik varsayılan kullanıldı", english ? "EN" : "TR");
        }
        return OtpPushTemplate.effectiveTitle(stored, english);
    }

    /**
     * Ayarlardaki mesaj şablonu dolu hâliyle (2026-10-03) — kayıtlı şablon bu tavanla geçersizse (tavan sonradan düşürüldü,
     * şablon başka yoldan yazıldı) yerleşik varsayılana düşülür: KOD HER ZAMAN TAM gider. Dolu metin KOD içerir — loglanmaz.
     */
    String configuredMessage(String code, int ttl, boolean english, java.time.Instant requestedAt) {
        String stored = null;
        int limit = 200;
        try {
            if (methods != null) {
                stored = methods.pushMessageTemplate(english);
                limit = methods.pushMessageLimit();
            }
        } catch (Exception e) {
            log.debug("Kodla giriş push şablonu okunamadı: {}", e.getClass().getSimpleName());
        }
        if (stored != null && !stored.isBlank() && OtpPushTemplate.validateMessage(stored, limit) != null) {
            // İleti yalnız ŞABLONDAN üretilir (kod yok) — ama içerik yine de loga yazılmaz, yalnız dil.
            log.warn("Kodla giriş push mesaj şablonu geçersiz ({}), yerleşik varsayılan kullanıldı", english ? "EN" : "TR");
        }
        return OtpPushTemplate.render(stored, english, code, ttl, requestedAt, limit);
    }

    /** E-posta hunisinin durum metninden KISA neden ("FAILED: <sunucu iletisi>" → yalnız ilk sözcük öbeği). */
    static String shortStatus(String st) {
        if (st == null || st.isBlank()) return "UNKNOWN";
        if (st.startsWith("QUEUED_RETRY")) return "SMTP_421_RETRY";
        if (st.startsWith("SKIPPED")) return st.length() > 40 ? st.substring(0, 40) : st;
        // Sunucu iletisi isteği yankılamaz (kod gövdede, konu kodsuz) ama yine de kısa tutulur — ayrıntı e-posta logunda.
        return "SMTP";
    }

    private static String clip(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }

    /** Kuyruktaki bekleyen iş sayısı (sağlık / test). */
    int queued() {
        return executor.getQueue().size();
    }
}
