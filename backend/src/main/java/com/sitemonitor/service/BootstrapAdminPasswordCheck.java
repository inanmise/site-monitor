package com.sitemonitor.service;

import com.sitemonitor.model.AppUser;
import com.sitemonitor.repository.AppUserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TEK SEFERLİK denetim (H paketi B3, 2026-11-26): Helm {@code values.yaml} yer tutucusuyla ({@code REPLACE_ME} vb.) kurulmuş
 * ve parolası HİÇ DEĞİŞMEMİŞ önyükleme yöneticisine "ilk girişte parola değiştir" ({@code must_change_password}) konur.
 *
 * <p>Yeni kurulum bunu {@link UserService#ensureBootstrapped} içinde zaten yapar; bu iş eski kurulumlar içindir. Ölçüt:
 * {@code site.monitor.username} hesabı YEREL (AD hesabının hash'i hiçbir parolayla eşleşmez), bayrak kapalı ve parola hash'i
 * BCrypt ile bir yer tutucuyla eşleşiyor (= parola hiç değiştirilmemiş). Yazım koşullu ({@code WHERE password_hash = okunan}):
 * arada parolayı değiştiren yöneticinin yeni parolası etkilenmez. Yalnız 1 satır yazan pod bir {@code USER_UPDATE} sistem
 * denetim kaydı yazar.
 *
 * <p><b>Ne zaman:</b> açılış yolunda DEĞİL — açılıştan 3 dk sonra, 10 dk'da bir, kendi {@code scheduler_lock} kilidi altında;
 * nişan kilit İÇİNDE yeniden okunur ve iş hatasız bitince yazılır (hiçbir şey yapılacak yoksa da). Hata sayacı
 * {@value #MAX_ATTEMPTS} denemede durur — sonsuz deneme yok. AD'ye bağlanmaz; BCrypt karşılaştırması en çok
 * {@code placeholderPasswordsForCheck().size()} kez (tek hesap).
 */
@Slf4j
@Service
public class BootstrapAdminPasswordCheck {

    /** Nişan anahtarı — yayından sonra DEĞİŞTİRİLMEZ. */
    static final String KEY = "2026-11-26-bootstrap-admin-placeholder-password";
    static final String LOCK = "bootstrap-admin-password-check";
    /** Hata sayacı (bekleme turları sayılmaz): 10 dk × 12 = 2 saat. */
    static final int MAX_ATTEMPTS = 12;
    static final String REASON = "bootstrap_placeholder_password";

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private final JdbcTemplate jdbc;
    private final AppUserRepository userRepo;
    private final ObjectProvider<SchedulerService> scheduler;
    private final ObjectProvider<AuditService> auditService;
    private final String bootstrapUsername;
    private final AtomicInteger failures = new AtomicInteger();
    private volatile boolean markerSeen;

    public BootstrapAdminPasswordCheck(JdbcTemplate jdbc, AppUserRepository userRepo,
                                       ObjectProvider<SchedulerService> scheduler,
                                       ObjectProvider<AuditService> auditService,
                                       @Value("${site.monitor.username:user}") String bootstrapUsername) {
        this.jdbc = jdbc;
        this.userRepo = userRepo;
        this.scheduler = scheduler;
        this.auditService = auditService;
        this.bootstrapUsername = bootstrapUsername;
    }

    @Scheduled(initialDelayString = "${site.monitor.bootstrap-admin-check.initial-delay-ms:180000}", fixedDelay = 600_000L)
    public void run() {
        if (failures.get() >= MAX_ATTEMPTS) return;
        if (done()) return;
        SchedulerService s = scheduler.getIfAvailable();
        if (s == null) return;
        try {
            if (s.callWithSchedulerLock(LOCK, this::checkLocked).isPresent()) failures.set(0);
        } catch (RuntimeException e) {
            int n = failures.incrementAndGet();
            if (n >= MAX_ATTEMPTS) {
                log.error("Önyükleme yöneticisi parola denetimi {} denemede tamamlanamadı — bu pod denemeyi bırakıyor", n, e);
            } else {
                log.warn("Önyükleme yöneticisi parola denetimi başarısız (10 dk sonra yeniden denenecek): {}", e.toString());
            }
        }
    }

    /** Nişan yazılmış mı; okunamazsa "evet" (bu tur koşmaz, sonraki tur yeniden bakar). */
    boolean done() {
        if (markerSeen) return true;
        try {
            jdbc.execute(StandaloneMonitorDeletionBackfill.MARKERS_DDL);
            Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM schema_patch_markers WHERE patch_key = ?", Integer.class, KEY);
            boolean seen = n != null && n > 0;
            if (seen) markerSeen = true;
            return seen;
        } catch (RuntimeException e) {
            log.debug("Önyükleme yöneticisi parola denetimi nişanı okunamadı: {}", e.getMessage());
            return true;
        }
    }

    /** Kilit altında: denetim + nişan. Dönüş: bayrağı konan hesap sayısı (0 / 1). */
    Integer checkLocked() {
        if (done()) return 0;
        int flagged = 0;
        String username = UserService.normalizeUsername(bootstrapUsername);
        AppUser u = username == null || username.isBlank() ? null : userRepo.findByUsername(username).orElse(null);
        if (u != null && eligible(u)) {
            String hash = u.getPasswordHash();
            boolean placeholder = false;
            for (String p : UserService.placeholderPasswordsForCheck()) {
                if (UserService.passwordMatches(p, hash)) { placeholder = true; break; }
            }
            if (placeholder && userRepo.requirePasswordChangeIfHashUnchanged(u.getId(), hash) == 1) {
                flagged = 1;
                audit(u);
                log.warn("Önyükleme yöneticisi '{}' hâlâ yer tutucu parolayı kullanıyor — sonraki girişte parola değişikliği "
                        + "zorunlu kılındı", u.getUsername());
            }
        }
        jdbc.update("INSERT INTO schema_patch_markers (patch_key, applied_at, rows_affected) VALUES (?, ?, ?)",
                KEY, ISO.format(Instant.now()), flagged);
        markerSeen = true;
        return flagged;
    }

    /** Yerel hesap (AD değil), bayrak kapalı, hash var. */
    static boolean eligible(AppUser u) {
        if (u.getAuthSource() != null && "LDAP".equalsIgnoreCase(u.getAuthSource())) return false;
        if (Boolean.TRUE.equals(u.getMustChangePassword())) return false;
        return u.getPasswordHash() != null && !u.getPasswordHash().isBlank();
    }

    private void audit(AppUser u) {
        AuditService a = auditService.getIfAvailable();
        if (a == null) return;
        try {
            a.recordSystemEvent("USER_UPDATE", "USER", String.valueOf(u.getId()),
                    AuditDetail.of("username", u.getUsername(), "mustChangePassword", true, "reason", REASON));
        } catch (RuntimeException ex) {
            log.warn("Önyükleme yöneticisi parola denetimi denetim kaydı yazılamadı (kullanıcı #{}): {}", u.getId(), ex.toString());
        }
    }
}
