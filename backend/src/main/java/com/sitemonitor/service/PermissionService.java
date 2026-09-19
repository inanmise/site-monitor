package com.sitemonitor.service;

import com.sitemonitor.model.PermissionGrant;
import com.sitemonitor.repository.PermissionGrantRepository;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Yetki kontrolü için tek nokta. DB'den okunan grants'i memory cache'te tutar.
 * Permission değişikliğinde {@link #rebuildCache()} çağrılmalı.
 *
 * Fail-closed: cache'te kayıp bir grant varsa default false döner.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PermissionService {

    private final PermissionGrantRepository repo;

    /** role → resource_key → action → allowed */
    private volatile Map<String, Map<String, Map<String, Boolean>>> cache = Map.of();

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    @PostConstruct
    void load() {
        rebuildCache();
    }

    private Map<String, Map<String, Map<String, Boolean>>> buildCache() {
        Map<String, Map<String, Map<String, Boolean>>> next = new HashMap<>();
        for (PermissionGrant g : repo.findAll()) {
            next.computeIfAbsent(g.getRole(), r -> new HashMap<>())
                .computeIfAbsent(g.getResourceKey(), rk -> new HashMap<>())
                .put(g.getAction(), Boolean.TRUE.equals(g.getAllowed()));
        }
        return next;
    }

    public synchronized void rebuildCache() {
        this.cache = buildCache();
        log.info("Permission cache rebuilt: {} roles", cache.size());
    }

    /** Çok-pod tutarlılığı: başka bir instance grant değiştirdiyse yetki matrisini DB'den tazele.
     *  Aynı pod değişikliğinde no-op (rebuildCache zaten güncelledi). */
    @Scheduled(fixedDelayString = "${site.monitor.settings.refresh-ms:10000}", initialDelayString = "15000")
    synchronized void refreshFromDb() {
        try {
            Map<String, Map<String, Map<String, Boolean>>> next = buildCache();
            if (!next.equals(cache)) {
                this.cache = next;
                log.info("Permission cache refreshed from DB (updated by another instance): {} roles", next.size());
            }
        } catch (Exception e) {
            log.debug("Permission refresh skipped: {}", e.getMessage());
        }
    }

    public boolean allows(String role, String resourceKey, String action) {
        if (role == null) return false;
        Map<String, Map<String, Boolean>> roleMap = cache.get(role);
        if (roleMap == null) return false;
        Map<String, Boolean> resMap = roleMap.get(resourceKey);
        if (resMap == null) return false;
        return Boolean.TRUE.equals(resMap.get(action));
    }

    public boolean allows(HttpSession session, String resourceKey, String action) {
        return allows((String) session.getAttribute("systemRole"), resourceKey, action);
    }

    /** Yetki kapısı: rolün bu (resource, action) iznine sahip olmaması 403 (SecurityException) üretir.
     *  Takım-scope kontrolleri AYRI yapılır; bu yalnız "rol bu işlemi yapabilir mi" sorusudur. */
    public void require(HttpSession session, String resourceKey, String action) {
        require((String) session.getAttribute("systemRole"), resourceKey, action);
    }

    public void require(String role, String resourceKey, String action) {
        if (!allows(role, resourceKey, action)) {
            throw new SecurityException("Bu işlem için yetkiniz yok: " + resourceKey + "/" + action);
        }
    }

    /** Role bazlı snapshot — frontend için ön-derlenmiş map. */
    public Map<String, Map<String, Boolean>> snapshotForRole(String role) {
        Map<String, Map<String, Boolean>> src = cache.getOrDefault(role, Map.of());
        Map<String, Map<String, Boolean>> copy = new LinkedHashMap<>();
        src.forEach((k, v) -> copy.put(k, new LinkedHashMap<>(v)));
        return copy;
    }

    /**
     * Bootstrap: ilk başlangıçta tablo boşsa default seed'i uygula.
     * Idempotent — sonradan DB'de değişiklik olursa yeniden seed olmaz.
     */
    @Transactional
    public void seedDefaultsIfEmpty() {
        if (repo.count() > 0) return;
        seedDefaults();
        log.info("Permission table seeded with defaults.");
    }

    /** "Reset to defaults" endpoint'i tarafından çağrılır. */
    @Transactional
    public void seedDefaults() {
        repo.deleteAll();
        String now = ISO.format(Instant.now());
        for (String role : new String[] {"ADMIN", "TEAM_ADMIN", "USER", "AUDIT"}) {
            Map<String, Map<String, Boolean>> defaults = PermissionCatalog.defaultsFor(role);
            defaults.forEach((resourceKey, actions) -> actions.forEach((action, allowed) -> {
                PermissionGrant g = new PermissionGrant();
                g.setRole(role);
                g.setResourceKey(resourceKey);
                g.setAction(action);
                g.setAllowed(allowed);
                g.setUpdatedAt(now);
                g.setUpdatedBy("system");
                repo.save(g);
            }));
        }
        rebuildCache();
    }

    /**
     * Katalogda yeni eklenen (role, resourceKey, action) default'larını DB'ye EKLER —
     * mevcut satırlara DOKUNMAZ (admin özelleştirmeleri korunur). Dolu tablolarda
     * {@link #seedDefaultsIfEmpty()} çalışmadığından yeni modüller (örn. weekly_reports,
     * diagnostics) için gereklidir. Idempotent: eksik yoksa hiçbir şey yapmaz.
     */
    @Transactional
    public void seedMissingDefaults() {
        String now = ISO.format(Instant.now());
        int added = 0;
        for (String role : new String[] {"ADMIN", "TEAM_ADMIN", "USER", "AUDIT"}) {
            Map<String, Map<String, Boolean>> defaults = PermissionCatalog.defaultsFor(role);
            for (Map.Entry<String, Map<String, Boolean>> res : defaults.entrySet()) {
                for (Map.Entry<String, Boolean> act : res.getValue().entrySet()) {
                    if (repo.findByRoleAndResourceKeyAndAction(role, res.getKey(), act.getKey()).isEmpty()) {
                        PermissionGrant g = new PermissionGrant();
                        g.setRole(role);
                        g.setResourceKey(res.getKey());
                        g.setAction(act.getKey());
                        g.setAllowed(act.getValue());
                        g.setUpdatedAt(now);
                        g.setUpdatedBy("system");
                        repo.save(g);
                        added++;
                    }
                }
            }
        }
        if (added > 0) {
            log.info("Permission backfill: {} eksik default grant eklendi (yeni modüller).", added);
            rebuildCache();
        }
    }

    /**
     * Politika yükseltmesi: katalog varsayılanı SONRADAN açılan bir yetkiyi, mevcut kurulumlarda da
     * açar. {@link #seedMissingDefaults()} yetmez — o yalnız EKSİK satırı ekler, var olan
     * {@code allowed=false} satırını çevirmez; dolayısıyla katalog değişikliği yalnız SIFIRDAN
     * kurulumları etkilerdi.
     *
     * <p><b>Yalnız insan eli değmemiş satırlar çevrilir</b> ({@code updated_by = 'system'}).
     * Bunun üç sonucu var ve üçü de bilinçli:
     * <ul>
     *   <li>Bir yönetici bu yetkiyi BİLİNÇLİ kapattıysa ({@code updated_by} = kullanıcı adı)
     *       satıra DOKUNULMAZ — kararı ezilmez.</li>
     *   <li>Doğal olarak idempotenttir: değer zaten true ise yazma yapılmaz.</li>
     *   <li>Kendini sınırlar: yükseltmeden sonra yönetici kapatırsa {@code updated_by} artık
     *       o yönetici olur ve bir daha ASLA geri açılmaz. Aksi halde her açılışta yöneticiyle
     *       kavga eden bir migration olurdu.</li>
     * </ul>
     */
    @Transactional
    public void applyPolicyUpgrades() {
        int flipped = 0;
        for (PolicyUpgrade up : POLICY_UPGRADES) {
            for (String action : up.actions()) {
                var existing = repo.findByRoleAndResourceKeyAndAction(up.role(), up.resourceKey(), action);
                if (existing.isEmpty()) continue;                       // seedMissingDefaults ekler
                PermissionGrant g = existing.get();
                if (Boolean.TRUE.equals(g.getAllowed())) continue;      // zaten açık
                if (!"system".equals(g.getUpdatedBy())) continue;       // İNSAN kararı — dokunma
                g.setAllowed(true);
                g.setUpdatedAt(ISO.format(Instant.now()));
                repo.save(g);
                flipped++;
                log.warn("Yetki politikası yükseltmesi: {} → {}/{} AÇILDI ({})",
                        up.role(), up.resourceKey(), action, up.reason());
            }
        }
        if (flipped > 0) rebuildCache();
    }

    private record PolicyUpgrade(String role, String resourceKey, List<String> actions, String reason) {}

    /**
     * Uygulanacak politika yükseltmeleri. Buraya satır EKLEMEK geri alınamaz bir güvenlik kararıdır:
     * yalnız varsayılanı gerçekten gevşettiğimizde ve gerekçesi yazılıyken eklenir.
     */
    private static final List<PolicyUpgrade> POLICY_UPGRADES = List.of(
            new PolicyUpgrade("USER", "monitoring.scripted", List.of("edit", "execute"),
                    "USER kendi takımının sentetik monitörünü yazamıyordu (2026-08-24); "
                  + "takım izolasyonu canOperateTeam ile ayrıca korunuyor, silme TEAM_ADMIN'de kalıyor"),
            new PolicyUpgrade("TEAM_ADMIN", "diagnostics.run", List.of("execute"),
                    "Takım yöneticisi kendi takımının izlediği alan adları için tanılama koşturabilmeli (2026-09-11); "
                  + "uç requireAdminOrMonitoredDomain ile takım kapsamına bağlı"),
            new PolicyUpgrade("USER", "diagnostics.run", List.of("execute"),
                    "USER kendi takımının izlediği alan adları için tanılama koşturabilmeli (2026-09-11); "
                  + "uç takım kapsamını doğrular, proxy-ca-chain admin'de kalır"),
            new PolicyUpgrade("USER", "inventory.crud", List.of("edit"),
                    "\"Domain Ekle\" her kullanıcı seviyesinde (2026-09-18); uç üyelik doğrular (requireInventoryWriter), "
                  + "düzenleme/silme/aktarma yönetici kapılarında kalır"),
            new PolicyUpgrade("USER", "release_history.read", List.of("view"),
                    "Sistem Sağlığı'ndaki her bölüm her kademeye açık (2026-09-19): Sürüm & Dağıtım okunur, "
                  + "yazma release_history.edit'te kalır"),
            new PolicyUpgrade("TEAM_ADMIN", "release_history.read", List.of("view"),
                    "Sistem Sağlığı'ndaki her bölüm her kademeye açık (2026-09-19): Sürüm & Dağıtım okunur, "
                  + "yazma release_history.edit'te kalır")
    );

    /** Update single grant. ADMIN row'ları her zaman true; bypass yok. */
    @Transactional
    public PermissionGrant upsertGrant(String role, String resourceKey, String action, boolean allowed, String updatedBy) {
        if ("ADMIN".equals(role) && !allowed) {
            throw new SecurityException("ADMIN permissions cannot be revoked");
        }
        PermissionGrant g = repo.findByRoleAndResourceKeyAndAction(role, resourceKey, action)
                                .orElseGet(PermissionGrant::new);
        g.setRole(role);
        g.setResourceKey(resourceKey);
        g.setAction(action);
        g.setAllowed(allowed);
        g.setUpdatedAt(ISO.format(Instant.now()));
        g.setUpdatedBy(updatedBy);
        PermissionGrant saved = repo.save(g);
        rebuildCache();
        return saved;
    }

    public Optional<PermissionGrant> find(String role, String resourceKey, String action) {
        return repo.findByRoleAndResourceKeyAndAction(role, resourceKey, action);
    }
}
