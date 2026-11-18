package com.sitemonitor.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TEK SEFERLİK temizlik (2026-11-19, kullanıcı kararı): envanter kaydı OLMAYAN türev Port / DNS izlemeleri kalıcı silinir.
 *
 * <p>Türev satır envanter kaydından doğar ({@code MonitoringController.listPort / listDns} lazy-provision); envanter yeniden
 * adlandırılınca (bu sürüme dek taşınmıyordu — artık {@link PermanentDeletionService#retargetDerivedMonitors}), harf farkıyla
 * ya da kalıcı silme öncesi silinen kayıtla eski satır sahipsiz kalıyordu: tarama atlıyor, Port / DNS sayfası listelemiyor,
 * İzleme Panosu ise "Duraklatılmış · envanter dışı" diye sayıyordu ve "Aç" hiçbir şey açmıyordu. Envanter kaydı DURAKLATILMIŞ
 * satırlara DOKUNULMAZ (kayıt sürdürülünce izleme de sürer). Bağımsız (standalone) satırlar kapsam dışıdır.
 *
 * <p><b>Ne zaman:</b> açılış yolunda değil — açılıştan 6 dk sonra, 10 dk'da bir, kendi {@code scheduler_lock} kilidi altında;
 * nişan kilit İÇİNDE yeniden okunur ve yalnız iş hatasız bitince yazılır. Başka sürüm koşan canlı pod varken (eski pod
 * yeniden adlandırmada hâlâ kalıntı üretir) ya da pod sürümleri okunamazken tur atlanır. Her satır kendi işleminde,
 * koşul yeniden okunarak silinir ({@link PermanentDeletionService#deleteOrphanDerivedPort}); açık alarmları sessizce kapanır.
 * Bir tur en çok {@link #BATCH} satır; silinenler tek bir {@code SYSTEM_DERIVED_ORPHAN_CLEANUP} denetim kaydında listelenir.
 */
@Slf4j
@Service
public class DerivedMonitorOrphanCleanup {

    /** Nişan anahtarı — yayından sonra DEĞİŞTİRİLMEZ. */
    static final String KEY = "2026-11-19-derived-monitor-orphan-cleanup";
    static final String LOCK = "derived-monitor-orphan-cleanup";
    /** Hata sayacı (bekleme turları sayılmaz): 10 dk × 36 = 6 saat. */
    static final int MAX_ATTEMPTS = 36;
    static final int BATCH = 500;
    /** Denetim kaydında listelenen en çok ad (gerisi sayı olarak). */
    static final int AUDIT_NAMES_MAX = 50;

    /** Türev Port satırı, host'u için envanterde ağ kaydı (etkin ya da duraklatılmış) YOK. */
    static final String SQL_PORT_ORPHANS =
            "SELECT p.id, p.host, p.port FROM port_monitors p "
            + "WHERE (p.standalone IS NULL OR p.standalone = FALSE) "
            + "AND NOT EXISTS (SELECT 1 FROM certificate_inventory i WHERE i.domain = p.host "
            + "                AND (i.cert_source IS NULL OR i.cert_source <> 'MANUAL') AND i.deleted_at IS NULL) "
            + "ORDER BY p.id FETCH FIRST " + (BATCH + 1) + " ROWS ONLY";
    static final String SQL_DNS_ORPHANS =
            "SELECT d.id, d.domain FROM dns_monitors d "
            + "WHERE (d.standalone IS NULL OR d.standalone = FALSE) "
            + "AND NOT EXISTS (SELECT 1 FROM certificate_inventory i WHERE i.domain = d.domain "
            + "                AND (i.cert_source IS NULL OR i.cert_source <> 'MANUAL') AND i.deleted_at IS NULL) "
            + "ORDER BY d.id FETCH FIRST " + (BATCH + 1) + " ROWS ONLY";

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private final JdbcTemplate jdbc;
    private final ObjectProvider<SchedulerService> scheduler;
    private final ObjectProvider<DeploymentHistoryService> history;
    private final ObjectProvider<AuditService> auditService;
    private final ObjectProvider<PermanentDeletionService> deletion;
    private final AtomicInteger failures = new AtomicInteger();
    /** Nişan bu pod'da bir kez görüldü mü (bellek). Okuma hatası hatırlanmaz. */
    private volatile boolean markerSeen;

    public DerivedMonitorOrphanCleanup(JdbcTemplate jdbc, ObjectProvider<SchedulerService> scheduler,
                                       ObjectProvider<DeploymentHistoryService> history, ObjectProvider<AuditService> auditService,
                                       ObjectProvider<PermanentDeletionService> deletion) {
        this.jdbc = jdbc;
        this.scheduler = scheduler;
        this.history = history;
        this.auditService = auditService;
        this.deletion = deletion;
    }

    @Scheduled(initialDelayString = "${site.monitor.derived-orphan-cleanup.initial-delay-ms:360000}", fixedDelay = 600_000L)
    public void run() {
        if (failures.get() >= MAX_ATTEMPTS) return;
        if (done()) return;
        if (!singleVersion()) return;
        SchedulerService s = scheduler.getIfAvailable();
        if (s == null || deletion.getIfAvailable() == null) return;
        try {
            if (s.callWithSchedulerLock(LOCK, this::cleanLocked).isPresent()) failures.set(0);
        } catch (RuntimeException e) {
            int n = failures.incrementAndGet();
            if (n >= MAX_ATTEMPTS) {
                log.error("Türev izleme kalıntı temizliği {} denemede tamamlanamadı — bu pod denemeyi bırakıyor (nişan yazılmadı)", n, e);
            } else {
                log.warn("Türev izleme kalıntı temizliği başarısız (10 dk sonra yeniden denenecek): {}", e.toString());
            }
        }
    }

    /** Başka sürüm koşan canlı pod yok mu; okunamazsa "hayır" (bekle). Geçmiş servisi yoksa (dar bağlam) "evet". */
    boolean singleVersion() {
        DeploymentHistoryService h = history.getIfAvailable();
        if (h == null) return true;
        List<Map<String, String>> others = h.liveOtherVersions();
        if (others == null) return false;
        return others.isEmpty();
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
            log.debug("Türev izleme kalıntı temizliği nişanı okunamadı: {}", e.getMessage());
            return true;
        }
    }

    /**
     * Kilit altında: kalıntıları satır satır (her biri kendi işleminde, koşul yeniden okunarak) siler; aday kalmayınca nişanı
     * yazar. Dönüş: silinen satır sayısı. Bir tur her türde {@link #BATCH} satırla sınırlıdır — fazlası varsa nişan yazılmaz.
     */
    Integer cleanLocked() {
        if (done()) return 0;
        PermanentDeletionService d = deletion.getObject();
        List<Map<String, Object>> ports = jdbc.queryForList(SQL_PORT_ORPHANS);
        List<Map<String, Object>> dns = jdbc.queryForList(SQL_DNS_ORPHANS);
        boolean more = ports.size() > BATCH || dns.size() > BATCH;
        List<String> names = new ArrayList<>();
        int deleted = 0;
        for (Map<String, Object> row : ports.size() > BATCH ? ports.subList(0, BATCH) : ports) {
            long id = ((Number) row.get("id")).longValue();
            if (!d.deleteOrphanDerivedPort(id)) continue;   // arada envantere eklendi / bağımsız oldu — dokunma
            deleted++;
            if (names.size() < AUDIT_NAMES_MAX) names.add("port " + row.get("host") + ":" + row.get("port") + " #" + id);
        }
        for (Map<String, Object> row : dns.size() > BATCH ? dns.subList(0, BATCH) : dns) {
            long id = ((Number) row.get("id")).longValue();
            if (!d.deleteOrphanDerivedDns(id)) continue;
            deleted++;
            if (names.size() < AUDIT_NAMES_MAX) names.add("dns " + row.get("domain") + " #" + id);
        }
        if (deleted > 0) audit(deleted, names);
        if (more) {
            log.info("Türev izleme kalıntı temizliği: bu turda {} satır silindi, kalanlar sonraki turda", deleted);
            return deleted;
        }
        jdbc.update("INSERT INTO schema_patch_markers (patch_key, applied_at, rows_affected) VALUES (?, ?, ?)",
                KEY, ISO.format(Instant.now()), deleted);
        markerSeen = true;
        log.info("Türev izleme kalıntı temizliği tamamlandı — envanter kaydı olmayan {} türev Port/DNS izlemesi kalıcı silindi", deleted);
        return deleted;
    }

    private void audit(int deleted, List<String> names) {
        AuditService a = auditService.getIfAvailable();
        if (a == null) return;
        try {
            a.recordSystemEvent("SYSTEM_DERIVED_ORPHAN_CLEANUP", "MONITOR", "derived-orphans",
                    AuditDetail.of("deleted", deleted, "monitors", String.join(", ", names),
                            "listed", names.size(), "reason", "no_inventory_record"));
        } catch (RuntimeException ex) {
            log.warn("Türev izleme kalıntı temizliği denetim kaydı yazılamadı: {}", ex.toString());
        }
    }
}
