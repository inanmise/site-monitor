package com.sitemonitor.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TEK SEFERLİK onarım (2026-11-14, kullanıcı kararı): "AD'den ekle" ile ORG ROLÜ SEÇİLMEDEN eklenen kişilerin boş + KİLİTLİ
 * kalan org rolü kilidi açılır.
 *
 * <p>2026-11-11 – 2026-11-13 arasındaki sürümlerde {@link UserService#provisionAdUser} org rolü seçilmese de
 * {@code org_role_locked = TRUE} yazıyordu; {@link LdapProvisioningService} kilitli alana dokunmadığı için AD'de PO / müdür
 * olan kişi girişte de rolünü alamıyor, PO onayı ve org rolüne bağlı push grupları onu atlıyordu. Düzeltme (v20.196.2)
 * yalnız YENİ eklemeleri kapsıyordu; bu iş eski satırları onarır: kilit açılır, rol kişinin sonraki girişinde (ya da
 * "AD'den yeniden eşitle" ile) AD'den türetilir. Bu iş AD'ye BAĞLANMAZ.
 *
 * <p><b>Hangi satırlar</b> (hepsi birlikte): LDAP hesabı; org rolü boş; kilit açık değil; kişi "AD'den ekle" ile eklenmiş
 * ({@code USER_PROVISION_FROM_AD} denetim kaydı, kaynak = kullanıcı adı); ve eklendikten sonra bir yönetici org rolünü
 * elle DEĞİŞTİRMEMİŞ ({@code USER_UPDATE} kaydında {@code orgRole} farkı yok — rolü bilerek boşaltan yöneticinin kilidi
 * korunur). Yazma koşulludur ({@code WHERE org_role_locked = TRUE AND org_role boş}): arada yapılan bir düzenleme ezilmez.
 *
 * <p><b>Ne zaman:</b> açılış yolunda değil — açılıştan 5 dk sonra, 10 dk'da bir, kendi {@code scheduler_lock} kilidi altında;
 * nişan kilit İÇİNDE yeniden okunur ve yalnız iş hatasız bitince yazılır. Başka sürüm koşan canlı pod varken (eski pod hâlâ
 * hatalı satır üretebilir) ya da pod sürümleri okunamazken tur atlanır. Her açılan kilit için bir
 * {@code USER_ORG_ROLE_UNLOCK} denetim kaydı (aktör SYSTEM) yazılır.
 */
@Slf4j
@Service
public class AdOrgRoleLockRepair {

    /** Nişan anahtarı — yayından sonra DEĞİŞTİRİLMEZ. */
    static final String KEY = "2026-11-14-ad-org-role-lock-repair";
    static final String LOCK = "ad-org-role-lock-repair";
    /** Hata sayacı (bekleme turları sayılmaz): 10 dk × 36 = 6 saat. */
    static final int MAX_ATTEMPTS = 36;
    /** Tek turda en çok bu kadar satır; fazlası varsa nişan yazılmaz, sonraki tur sürdürür. */
    static final int BATCH = 500;
    static final String REASON = "ad_provision_lock_repair";

    /**
     * Aday satırlar. {@code LIKE} yalnız bu kişinin kendi güncelleme kayıtlarında koşar ({@code idx_audit_resource}); anahtar
     * {@code AuditDiff.diff}'in yazdığı alan adıdır ({@code "orgRole":{"from":…,"to":…}}).
     */
    static final String SQL_CANDIDATES =
            "SELECT u.id, u.username FROM app_users u "
            + "WHERE u.auth_source = 'LDAP' AND u.org_role_locked = TRUE AND (u.org_role IS NULL OR u.org_role = '') "
            + "AND EXISTS (SELECT 1 FROM audit_log a WHERE a.event_type = 'USER_PROVISION_FROM_AD' "
            + "            AND a.resource_type = 'USER' AND a.resource_id = u.username) "
            + "AND NOT EXISTS (SELECT 1 FROM audit_log b WHERE b.event_type = 'USER_UPDATE' AND b.resource_type = 'USER' "
            + "            AND b.resource_id = CAST(u.id AS VARCHAR(40)) AND b.changes LIKE '%\"orgRole\"%') "
            + "ORDER BY u.id FETCH FIRST " + (BATCH + 1) + " ROWS ONLY";

    static final String SQL_UNLOCK =
            "UPDATE app_users SET org_role_locked = FALSE "
            + "WHERE id = ? AND org_role_locked = TRUE AND (org_role IS NULL OR org_role = '')";

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private final JdbcTemplate jdbc;
    private final ObjectProvider<SchedulerService> scheduler;
    private final ObjectProvider<DeploymentHistoryService> history;
    private final ObjectProvider<AuditService> auditService;
    private final AtomicInteger failures = new AtomicInteger();
    /** Nişan bu pod'da bir kez görüldü mü (bellek) — yazıldıktan sonra her turda sorgu koşmasın. Okuma hatası hatırlanmaz. */
    private volatile boolean markerSeen;

    public AdOrgRoleLockRepair(JdbcTemplate jdbc, ObjectProvider<SchedulerService> scheduler,
                               ObjectProvider<DeploymentHistoryService> history, ObjectProvider<AuditService> auditService) {
        this.jdbc = jdbc;
        this.scheduler = scheduler;
        this.history = history;
        this.auditService = auditService;
    }

    @Scheduled(initialDelayString = "${site.monitor.ldap.org-role-lock-repair.initial-delay-ms:300000}", fixedDelay = 600_000L)
    public void run() {
        if (failures.get() >= MAX_ATTEMPTS) return;          // bu pod denemeyi bıraktı (bir kez ERROR yazıldı)
        if (done()) return;
        if (!singleVersion()) return;                        // dağıtım sürüyor ya da okunamadı: sonraki tur
        SchedulerService s = scheduler.getIfAvailable();
        if (s == null) return;
        try {
            if (s.callWithSchedulerLock(LOCK, this::repairLocked).isPresent()) failures.set(0);
        } catch (RuntimeException e) {
            int n = failures.incrementAndGet();
            if (n >= MAX_ATTEMPTS) {
                log.error("[LDAP] Org rolü kilit onarımı {} denemede tamamlanamadı — bu pod denemeyi bırakıyor (nişan yazılmadı)", n, e);
            } else {
                log.warn("[LDAP] Org rolü kilit onarımı başarısız (10 dk sonra yeniden denenecek): {}", e.toString());
            }
        }
    }

    /** Başka sürüm koşan canlı pod yok mu; okunamazsa "hayır" (bekle). Geçmiş servisi yoksa (dar bağlam) "evet". */
    boolean singleVersion() {
        DeploymentHistoryService h = history.getIfAvailable();
        if (h == null) return true;
        List<Map<String, String>> others = h.liveOtherVersions();
        if (others == null) {
            log.debug("[LDAP] Org rolü kilit onarımı bekliyor: pod sürümleri okunamadı");
            return false;
        }
        if (!others.isEmpty()) {
            log.debug("[LDAP] Org rolü kilit onarımı bekliyor: başka sürüm koşan pod var {}", others);
            return false;
        }
        return true;
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
            log.debug("[LDAP] Org rolü kilit onarımı nişanı okunamadı: {}", e.getMessage());
            return true;
        }
    }

    /**
     * Kilit altında: aday satırların kilidini koşullu açar, her açılan için denetim kaydı yazar; aday kalmayınca nişanı
     * yazar. Dönüş: bu turda açılan kilit sayısı. Bir tur {@link #BATCH} satırla sınırlıdır — fazlası varsa nişan yazılmaz.
     */
    Integer repairLocked() {
        if (done()) return 0;
        List<Map<String, Object>> rows = jdbc.queryForList(SQL_CANDIDATES);
        boolean more = rows.size() > BATCH;
        int opened = 0;
        for (Map<String, Object> row : more ? rows.subList(0, BATCH) : rows) {
            long id = ((Number) row.get("id")).longValue();
            String username = String.valueOf(row.get("username"));
            if (jdbc.update(SQL_UNLOCK, id) != 1) continue;   // arada düzenlendi (rol atandı / kilit açıldı) — dokunma
            opened++;
            audit(id, username);
        }
        if (more) {
            log.info("[LDAP] Org rolü kilit onarımı: bu turda {} kilit açıldı, kalanlar sonraki turda", opened);
            return opened;
        }
        jdbc.update("INSERT INTO schema_patch_markers (patch_key, applied_at, rows_affected) VALUES (?, ?, ?)",
                KEY, ISO.format(Instant.now()), opened);
        markerSeen = true;
        if (opened == 0) {
            log.info("[LDAP] Org rolü kilit onarımı: AD'den eklenmiş, rolü boş + kilitli kalan kişi yok");
        } else {
            log.info("[LDAP] Org rolü kilit onarımı tamamlandı — {} kişinin org rolü kilidi açıldı; rol sonraki girişte "
                    + "AD'den türetilir", opened);
        }
        return opened;
    }

    private void audit(long id, String username) {
        AuditService a = auditService.getIfAvailable();
        if (a == null) return;
        try {
            a.recordSystemEvent("USER_ORG_ROLE_UNLOCK", "USER", String.valueOf(id),
                    AuditDetail.of("username", username, "reason", REASON));
        } catch (RuntimeException ex) {
            log.warn("[LDAP] Org rolü kilit onarımı denetim kaydı yazılamadı (kullanıcı #{}): {}", id, ex.toString());
        }
    }
}
