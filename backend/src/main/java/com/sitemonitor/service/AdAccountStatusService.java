package com.sitemonitor.service;

import com.sitemonitor.model.AppUser;
import com.sitemonitor.repository.AppUserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/**
 * AD hesap durumu denetimi — YALNIZ beni-hatırla ve kodla (OTP) girişte (H paketi B4, 2026-11-26, kullanıcı kararı).
 *
 * <p>Parola girişi AD bind'ı yaptığı için devre dışı AD hesabı zaten giremez; beni-hatırla çerezi ve kodla giriş ise AD'ye hiç
 * sormuyordu — AD'de kapatılan (işten ayrılan) kişi 7 günlük çerezle ya da kodla girmeye devam ediyordu. Karar:
 * <ul>
 *   <li>Yerel hesap (AD kaynaklı değil) → hiç sorulmaz, izin.</li>
 *   <li>AD: hesap etkin → izin + {@code app_users.ad_verified_at} damgası (hedefli yazım, yalnız ileri).</li>
 *   <li>AD: devre dışı ({@code userAccountControl} ACCOUNTDISABLE) ya da dizinde yok → ret ({@value #CODE_DISABLED}).</li>
 *   <li>AD'ye ulaşılamadı / süre doldu / kuyruk dolu / pod kapanıyor → son başarılı AD doğrulaması (parola bind'ı dahil) 24
 *       saat içindeyse izin, değilse ret ({@value #CODE_UNVERIFIED}).</li>
 * </ul>
 * Aranan kayıt parola girişinin doğruladığı kayıtla AYNIDIR ({@link LdapDirectoryService#lookupAccountState} — giriş filtresi,
 * ilk eşleşme). Kapanan pod ({@code ContextClosedEvent} sonrası) yeni arama başlatmaz: önbellekte kesin sonuç yoksa
 * "bilinmiyor" + 24 saat kuralı — yakın zamanda doğrulanmış kullanıcı geçer, uzun süredir doğrulanmamış olan reddedilir ve
 * çoğu kez yeni pod'a giden yeniden denemede normal karar alır.
 *
 * <p><b>Sınırlar:</b> istek iş parçacığı en çok {@link #LOOKUP_TIMEOUT_MS} bekler (ayrı zaman aşımı; JNDI'nin kendi bağlantı /
 * okuma sınırları arka planda işi bitirir). Arama kendi küçük havuzunda koşar ({@value #THREADS} iş parçacığı, kuyruk
 * {@value #QUEUE}; dolu → "bilinmiyor", bekleme yok). Aynı kullanıcı için eşzamanlı istekler TEK aramayı paylaşır; sonuç
 * bellekte tutulur (kesin sonuç {@link #CACHE_MS}, bilinmeyen {@link #UNKNOWN_CACHE_MS}; en çok {@value #CACHE_MAX} kayıt).
 * Hiçbir DB işlemi / kilidi tutulurken çağrılmaz (çağıranlar: kesici ve kodla giriş ucu, işlem dışı). Kapanışta yeni arama
 * başlamaz.
 */
@Slf4j
@Service
public class AdAccountStatusService {

    public enum Status { ACTIVE, DISABLED, NOT_FOUND, UNKNOWN }

    /** Giriş kararı: {@code allowed}; ret ise {@code code} = {@link #CODE_DISABLED} / {@link #CODE_UNVERIFIED}. */
    public record Decision(boolean allowed, String code, Status status) {
        static final Decision LOCAL = new Decision(true, null, null);
    }

    public static final String CODE_DISABLED = AuditService.REASON_AD_ACCOUNT_DISABLED;
    public static final String CODE_UNVERIFIED = AuditService.REASON_AD_UNVERIFIED;

    static final long LOOKUP_TIMEOUT_MS = 5_000L;
    static final long CACHE_MS = 300_000L;
    static final long UNKNOWN_CACHE_MS = 30_000L;
    static final long VERIFIED_WINDOW_MS = 24L * 3600_000L;
    /**
     * Damganın "gelecekte" olabileceği pay: damgayı saati ileride olan başka bir pod yazmış olabilir (hata avı 2026-11-26 —
     * 60 sn payla, saati 2 dk ileride bir pod'un yazdığı damga diğer pod'larda "doğrulanmamış" sayılıyor, AD kesintisinde
     * kullanıcı reddediliyordu). Daha ileri damga (bozuk değer) sayılmaz.
     */
    static final long FUTURE_SKEW_MS = 10L * 60_000L;
    static final int CACHE_MAX = 10_000;
    static final int THREADS = 2;
    static final int QUEUE = 32;

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private final LdapDirectoryService directory;
    private final AppUserRepository userRepo;
    private final ThreadPoolExecutor pool;
    private final ConcurrentHashMap<String, Cached> cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<Status>> inflight = new ConcurrentHashMap<>();
    private volatile boolean closing;

    /** Test kancaları. */
    LongSupplier clock = System::currentTimeMillis;
    long lookupTimeoutMs = LOOKUP_TIMEOUT_MS;

    private record Cached(Status status, long untilMs) {}

    public AdAccountStatusService(LdapDirectoryService directory, AppUserRepository userRepo) {
        this.directory = directory;
        this.userRepo = userRepo;
        AtomicInteger n = new AtomicInteger();
        this.pool = new ThreadPoolExecutor(THREADS, THREADS, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(QUEUE), r -> {
            Thread t = new Thread(r, "ad-account-check-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        }, new ThreadPoolExecutor.AbortPolicy());
        this.pool.allowCoreThreadTimeOut(true);
    }

    /** AD (LDAP) kaynaklı hesap mı — yalnız onlar sorulur. */
    public static boolean isAdAccount(AppUser u) {
        return u != null && u.getAuthSource() != null && "LDAP".equalsIgnoreCase(u.getAuthSource().strip());
    }

    /** Beni-hatırla / kodla giriş kararı (bkz. sınıf belgesi). Asla istisna fırlatmaz. */
    public Decision checkForLogin(AppUser u) {
        if (!isAdAccount(u)) return Decision.LOCAL;
        Status st = status(u.getId(), u.getUsername());
        return switch (st) {
            case ACTIVE -> new Decision(true, null, st);
            case DISABLED, NOT_FOUND -> new Decision(false, CODE_DISABLED, st);
            case UNKNOWN -> recentlyVerified(u.getAdVerifiedAt(), clock.getAsLong())
                    ? new Decision(true, null, st)
                    : new Decision(false, CODE_UNVERIFIED, st);
        };
    }

    /** Son başarılı AD doğrulaması (ISO-UTC) son 24 saat içinde mi; boş / okunamaz → hayır. */
    static boolean recentlyVerified(String adVerifiedAt, long nowMs) {
        if (adVerifiedAt == null || adVerifiedAt.isBlank()) return false;
        try {
            long at = LocalDateTime.parse(adVerifiedAt.strip().length() > 19 ? adVerifiedAt.strip().substring(0, 19)
                    : adVerifiedAt.strip()).toInstant(ZoneOffset.UTC).toEpochMilli();
            return at <= nowMs + FUTURE_SKEW_MS && nowMs - at <= VERIFIED_WINDOW_MS;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Parola bind'ı başarılı AD girişinden sonra (ve etkin hesap aramasından sonra) damga — hedefli, hata yutulur. */
    public void markVerified(Long userId) {
        if (userId == null) return;
        try {
            userRepo.markAdVerified(userId, ISO.format(Instant.ofEpochMilli(clock.getAsLong())));
        } catch (RuntimeException e) {
            log.debug("AD doğrulama damgası yazılamadı (kullanıcı #{}): {}", userId, e.toString());
        }
    }

    Status status(Long userId, String username) {
        if (username == null || username.isBlank()) return Status.UNKNOWN;
        String key = username.strip().toLowerCase(Locale.ROOT);
        long now = clock.getAsLong();
        Cached c = cache.get(key);
        if (c != null && c.untilMs() > now) return c.status();
        if (closing) return Status.UNKNOWN;
        CompletableFuture<Status> mine = new CompletableFuture<>();
        CompletableFuture<Status> existing = inflight.putIfAbsent(key, mine);
        CompletableFuture<Status> f = existing != null ? existing : mine;
        if (existing == null) {
            try {
                pool.execute(() -> runLookup(key, userId, username, mine));
            } catch (RejectedExecutionException full) {
                inflight.remove(key, mine);
                mine.complete(Status.UNKNOWN);
                log.debug("AD hesap durumu araması kuyruğu dolu — bilinmiyor sayıldı (kullanıcı {})", username);
            }
        }
        try {
            return f.get(lookupTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            log.info("AD hesap durumu {} ms içinde alınamadı — bilinmiyor sayıldı (kullanıcı {})", lookupTimeoutMs, username);
            return Status.UNKNOWN;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return Status.UNKNOWN;
        } catch (ExecutionException | RuntimeException e) {
            return Status.UNKNOWN;
        }
    }

    private void runLookup(String key, Long userId, String username, CompletableFuture<Status> f) {
        Status st = Status.UNKNOWN;
        try {
            st = lookup(username);
            if (st == Status.ACTIVE) markVerified(userId);
        } catch (Throwable t) {
            log.debug("AD hesap durumu araması başarısız (kullanıcı {}): {}", username, t.toString());
            st = Status.UNKNOWN;
        } finally {
            remember(key, st);
            inflight.remove(key, f);
            f.complete(st);
        }
    }

    private Status lookup(String username) {
        try {
            LdapDirectoryService.AccountLookup a = directory.lookupAccountState(username);
            if (a == null) return Status.UNKNOWN;
            // Kayıt = parola girişinin doğruladığı İLK kayıt (LdapDirectoryService.loginEntry, tek kural) — 2+ eşleşme artık
            // "belirsiz" değil (bağımsız inceleme 2026-11-26: parola girişi çalışırken 24 sa sonra beni-hatırla / kod reddediliyordu).
            return switch (a.presence()) {
                case NOT_FOUND -> Status.NOT_FOUND;
                case FOUND -> a.disabled() ? Status.DISABLED : Status.ACTIVE;
            };
        } catch (LdapDirectoryService.DirectoryUnavailableException e) {
            log.debug("AD'ye ulaşılamadı (kullanıcı {}): {}", username, e.getMessage());
            return Status.UNKNOWN;
        }
    }

    private void remember(String key, Status st) {
        long ttl = st == Status.UNKNOWN ? UNKNOWN_CACHE_MS : CACHE_MS;
        if (cache.size() >= CACHE_MAX) {
            long now = clock.getAsLong();
            cache.entrySet().removeIf(e -> e.getValue().untilMs() <= now);
            if (cache.size() >= CACHE_MAX) cache.clear();
        }
        cache.put(key, new Cached(st, clock.getAsLong() + ttl));
    }

    /** Ret gövdesi (kodla giriş 403; beni-hatırla 401): kararlı kod + istek dilinde ne yapılacağı. */
    public static Map<String, Object> refusalBody(String code) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("code", code);
        body.put("error_code", code);
        body.put("error", CODE_UNVERIFIED.equals(code)
                ? com.sitemonitor.util.Msg.t(
                        "Hesabınızın Active Directory durumu şu an doğrulanamadı. Kullanıcı adı ve parolanızla giriş yapın; olmazsa bir süre sonra yeniden deneyin.",
                        "Your Active Directory account status could not be verified right now. Sign in with your username and password; if that fails, try again later.")
                : com.sitemonitor.util.Msg.t(
                        "Active Directory hesabınız devre dışı ya da dizinde bulunamadı; bu yolla giriş yapılamaz. BT ekibinize başvurun.",
                        "Your Active Directory account is disabled or was not found in the directory, so you cannot sign in this way. Contact your IT team."));
        return body;
    }

    /** Kapanışta yeni arama başlamaz (bayrak; bekleme yok). */
    @EventListener(ContextClosedEvent.class)
    void onClosing() {
        closing = true;
    }

    /** Havuz kapanır — BEKLEME YOK (iş parçacıkları daemon; JNDI kendi sınırlarıyla biter). */
    @jakarta.annotation.PreDestroy
    void shutdown() {
        closing = true;
        pool.shutdownNow();
    }
}
