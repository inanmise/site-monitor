package com.sitemonitor.service.lockout;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.sitemonitor.model.LoginUnknownLockout;
import com.sitemonitor.repository.LoginUnknownLockoutRepository;
import com.sitemonitor.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * BİLİNMEYEN kullanıcı adının ilerleyici giriş kilidi — mevcut ETKİN hesapla her kademede ayırt edilemez (2026-10-09).
 *
 * <p><b>Neden.</b> Giriş ucu olmayan bir ad için {@code app_users} satırı yaratmaz (yaratmamalı). Eskiden bilinmeyen ad pod
 * belleğinde yalnız 1. kademeyi taklit ediyordu: sahte kilit dolunca bir sonraki eşikte yine 30 sn, oysa mevcut hesap
 * 120 / 600 / 1800 sn alıyor ve kilit sonrası daha az hatayla (5 → 3 → 2 → 1) yeniden kilitleniyordu; üstelik başka pod'a
 * düşen istek kalan süre yerine taze yanıt görüyordu. İkisi de "bu hesap var mı" sorusunu yanıtlıyordu.
 *
 * <p><b>Nasıl.</b> Durum {@code login_unknown_lockouts}'ta, {@code app_users}'ın üç alanıyla AYNI anlamda (kademe, kilit
 * bitişi, son kilit anı); hesap {@link LockoutLadder}'dan ve yapılandırma {@code UserService.lockoutLadder()}'dan —
 * mevcut hesapla tek kaynak. Okuma her istekte veritabanından (tüm pod'lar aynı kalan süreyi görür). Satır yalnız kaba
 * kuvvet eşiğinde (ilk kilit) yaratılır; {@code ACCOUNT_LOCKED} denetimi yazılmaz (olmayan hesap kilitlenemez).
 *
 * <p><b>Veritabanı hatası</b> girişi asla düşürmez: okuma hatası "kilit yok / 0. kademe", yazma hatası bellek içi 1. kademe
 * kilidi (bu pod, 2026-10-09 öncesi davranış). Bellek kaydı yalnız böyle bir hatada doğar ve kalan süre ikisinin büyüğüdür.
 *
 * <p><b>Sınırlı büyüme.</b> {@link #purge()} saatlik: son yazımı {@code unknown-retention-days}'ten eski satırlar ve
 * {@code unknown-max-rows} üstündeki en eski satırlar silinir. Mevcut hesabın kademesi yalnız başarılı giriş / yönetici
 * kilidi açmasıyla sıfırlandığından saklama süresi bilinçli olarak uzundur (denetim kaydıyla aynı 365 gün).
 */
@Slf4j
@Service
public class UnknownUserLockoutService {

    /** Bellek yedeğinin sert üst sınırı (yalnız veritabanı yazılamadığında dolar). */
    static final int MEMORY_MAX = 10_000;

    /** {@code null} = yalnız bellek (veritabanı yok — bean'siz dilimli test yedeği). */
    private final LoginUnknownLockoutRepository repo;
    private final Supplier<LockoutLadder> ladder;
    private Clock clock = Clock.systemUTC();

    @Value("${site.monitor.lockout.unknown-retention-days:365}")
    private int retentionDays = 365;

    @Value("${site.monitor.lockout.unknown-max-rows:50000}")
    private int maxRows = 50_000;

    private volatile Cache<String, Instant> memoryLocks;

    @Autowired
    public UnknownUserLockoutService(LoginUnknownLockoutRepository repo, UserService users) {
        this(repo, users::lockoutLadder);
    }

    UnknownUserLockoutService(LoginUnknownLockoutRepository repo, Supplier<LockoutLadder> ladder) {
        this.repo = repo;
        this.ladder = ladder;
    }

    /** Veritabanısız örnek: eski bellek içi 1. kademe davranışı (servis bean'i olmayan bağlam için). */
    public static UnknownUserLockoutService memoryOnly(Supplier<LockoutLadder> ladder) {
        return new UnknownUserLockoutService(null, ladder);
    }

    /** Test kancası. */
    void setClock(Clock clock) { this.clock = clock; }

    /** Test kancası. */
    void setLimits(int retentionDays, int maxRows) {
        this.retentionDays = retentionDays;
        this.maxRows = maxRows;
    }

    /**
     * Başarısız denemenin kilit bağlamı — mevcut hesapta {@code last_lockout_at} + {@code failuresNeededForLevel(kademe)}
     * karşılığı: kaba kuvvet sayımı son kilitten başlar, eşik kademeye göre daralır.
     */
    public record Context(String lastLockoutAt, int failuresNeeded) {}

    /** Kanonik anahtar sütuna sığar (denetim aktörüyle aynı 100 karakter). */
    static String key(String usernameKey) {
        if (usernameKey == null) return "";
        return usernameKey.length() <= 100 ? usernameKey : usernameKey.substring(0, 100);
    }

    /** Kilit sürüyorsa kalan saniye ({@code UserService.checkLockout} ile aynı hesap), yoksa 0. Asla kalıcı değil. */
    public UserService.LockoutStatus status(String usernameKey) {
        String k = key(usernameKey);
        Instant now = clock.instant();
        long remaining = 0L;
        if (repo != null) {
            try {
                remaining = repo.findById(k)
                        .map(r -> LockoutLadder.remainingSeconds(r.getLockoutUntil(), now))
                        .orElse(0L);
            } catch (Exception e) {
                log.warn("Bilinmeyen ad kilit durumu okunamadı — bellek yedeği: {}", e.getMessage());
            }
        }
        return new UserService.LockoutStatus(false, Math.max(remaining, memoryRemaining(k, now)));
    }

    /** {@link Context} — satır yoksa (ya da okunamazsa) 0. kademe: sayım penceresi başı yok, eşik ilk kademe. */
    public Context context(String usernameKey) {
        LockoutLadder l = ladder();
        if (repo != null) {
            try {
                Optional<LoginUnknownLockout> row = repo.findById(key(usernameKey));
                if (row.isPresent()) {
                    return new Context(row.get().getLastLockoutAt(), l.failuresNeeded(row.get().getLockoutLevel()));
                }
            } catch (Exception e) {
                log.warn("Bilinmeyen ad kilit bağlamı okunamadı — 0. kademe: {}", e.getMessage());
            }
        }
        return new Context(null, l.failuresNeeded(0));
    }

    /**
     * Kaba kuvvet eşiği: bir sonraki kademe — mevcut hesabın {@code applyProgressiveLockout}'u gibi oku → hesapla → yaz
     * (son yazan kazanır). Dönen süre o kademenin TAM süresi (mevcut hesabın 423 yanıtıyla aynı {@code wait_seconds}).
     */
    public UserService.LockoutStatus escalate(String usernameKey) {
        String k = key(usernameKey);
        LockoutLadder l = ladder();
        Instant now = clock.instant();
        if (repo != null) {
            try {
                Optional<LoginUnknownLockout> row = repo.findById(k);
                LockoutLadder.Step step = l.escalate(row.map(LoginUnknownLockout::getLockoutLevel).orElse(0), now);
                write(k, step, row.isPresent(), LockoutLadder.ISO.format(now));
                log.warn("Bilinmeyen kullanıcı adı kilitlendi kademe={}/{} {} sn: ad='{}'",
                        step.level(), l.levels(), step.seconds(), k);
                return new UserService.LockoutStatus(false, step.seconds());
            } catch (Exception e) {
                log.warn("Bilinmeyen ad kilidi yazılamadı — bellek içi 1. kademe: {}", e.getMessage());
            }
        }
        long secs = l.durationSeconds(1);
        // UserService gibi saniyeye kırpılmış bitiş → kalan süre aynı yuvarlamayla yürür.
        memoryLocks().put(k, now.plusSeconds(secs).truncatedTo(ChronoUnit.SECONDS));
        return new UserService.LockoutStatus(false, secs);
    }

    /** Satır varsa güncelle, yoksa yarat; yarış kaybedilirse (satır arada doğdu / silindi) diğer yolu bir kez dene. */
    private void write(String k, LockoutLadder.Step s, boolean exists, String now) {
        if (exists) {
            if (repo.updateStep(k, s.level(), s.lockoutUntil(), s.lockedAt(), now) == 1) return;
            if (repo.insertStepIfAbsent(k, s.level(), s.lockoutUntil(), s.lockedAt(), now) == 1) return;
        } else {
            if (repo.insertStepIfAbsent(k, s.level(), s.lockoutUntil(), s.lockedAt(), now) == 1) return;
            if (repo.updateStep(k, s.level(), s.lockoutUntil(), s.lockedAt(), now) == 1) return;
        }
        throw new IllegalStateException("kilit satırı yazılamadı");
    }

    /**
     * Saklama + sert üst sınır (saatlik; her pod koşar, silmeler idempotent). Hata yutulur — giriş yoluna dokunmaz.
     *
     * @return silinen satır sayısı
     */
    @Scheduled(fixedDelayString = "${site.monitor.lockout.unknown-cleanup-interval-ms:3600000}",
               initialDelayString = "${site.monitor.lockout.unknown-cleanup-initial-delay-ms:600000}")
    public int purge() {
        if (repo == null) return 0;
        try {
            String cutoff = LockoutLadder.ISO.format(clock.instant().minus(Duration.ofDays(Math.max(1, retentionDays))));
            int aged = repo.deleteUpdatedBefore(cutoff);
            int capped = 0;
            long count = repo.count();
            int cap = Math.max(1, maxRows);
            if (count > cap) capped = repo.deleteOldest((int) Math.min(Integer.MAX_VALUE, count - cap));
            if (aged + capped > 0) {
                log.info("Bilinmeyen ad kilit kayıtları temizlendi: süresi geçen {}, üst sınır ({}) {}", aged, cap, capped);
            }
            return aged + capped;
        } catch (Exception e) {
            log.warn("Bilinmeyen ad kilit kayıtları temizlenemedi: {}", e.getMessage());
            return 0;
        }
    }

    private LockoutLadder ladder() {
        LockoutLadder l = ladder == null ? null : ladder.get();
        return l != null ? l : new LockoutLadder(null, null);
    }

    private long memoryRemaining(String k, Instant now) {
        Cache<String, Instant> c = memoryLocks;
        if (c == null) return 0L;
        Instant until = c.getIfPresent(k);
        if (until == null) return 0L;
        return Math.max(0L, Duration.between(now, until).getSeconds());
    }

    private Cache<String, Instant> memoryLocks() {
        Cache<String, Instant> c = memoryLocks;
        if (c == null) {
            synchronized (this) {
                c = memoryLocks;
                if (c == null) {
                    c = Caffeine.newBuilder()
                            .maximumSize(MEMORY_MAX)
                            .expireAfterWrite(Duration.ofSeconds(Math.max(1L, ladder().durationSeconds(1))))
                            .<String, Instant>build();
                    memoryLocks = c;
                }
            }
        }
        return c;
    }
}
