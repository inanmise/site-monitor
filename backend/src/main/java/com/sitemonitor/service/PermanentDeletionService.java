package com.sitemonitor.service;

import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.DnsMonitor;
import com.sitemonitor.model.PortMonitor;
import com.sitemonitor.repository.CertificateInventoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * KALICI silme (2026-10-07, kullanıcı kararı: "silme işlemi her şekilde kalıcı olsun"). Envanter kaydı (ağ + elle yüklenen
 * sertifika) ve bağımsız (standalone) Port/DNS izlemesi artık çöp kutusuna DÜŞMEZ: kayıt ve ona ait veri tek işlemde
 * gider. Çöp kutusu yoktu ama görünmüyordu da — silinen ad yeniden eklenirken "zaten var" diye engelliyordu.
 *
 * <p><b>Ne gider</b> (envanter): açık alarmlar ÖNCE sessizce kapanır ({@link EscalationService#closeAlertsOnInventoryDelete},
 * yalnız envanterin KENDİ sahip anahtarı — başka takımın bağımsız izlemesinin olayı açık kalır); ardından kontrol geçmişi
 * ({@code certificate_checks}, {@code latest_checks}), sertifika notları + revizyonları, elle yüklenen sürümler, envanter
 * TÜREVİ Port/DNS izlemeleri ve onların serileri / tanılamaları / özetleri, erişilebilirlik serisi ({@code uptime_checks} +
 * {@code UPTIME} özeti), zayıf algoritma istisnası, sertifika kartı tanılamaları ve en son envanter satırı.
 *
 * <p><b>Ne KALIR</b>: kapanmış alarm geçmişi ({@code alert_events}), denetim kaydı, izleme değişiklik geçmişi
 * ({@code monitor_change_log}), etkinlik akışı, bildirim / push günlükleri — "kim neyi ne zaman sildi" izi.
 * Pinlenmiş CA'lar ({@code pinned_cas}) da KALIR: onları sertifika envanteri değil HTTP izlemeleri ve RDAP çıkışı oluşturur
 * ({@link CaAutoPinService}); host:port paylaşılır, silmek ilgisiz bir HTTP izlemesini TOFU'ya yeniden düşürürdü.
 * Alan adı tanılaması ({@code DOMAIN_EXPIRY}) ve vekil CA zinciri tanılaması da alan adı izlemesiyle paylaşıldığı için kalır.
 *
 * <p><b>İşlem.</b> Her genel metot {@code @Transactional} (çağıran işlemine katılır — toplu silme tek işlemdir). Çocuklar
 * JDBC ile silinir (satır başına varlık yüklemek yok); envanter satırı JPA ile ({@code inventoryRepo.delete}) — çağıranın
 * kalıcılık bağlamındaki yönetilen varlık tutarlı kalır. Rehber / not satırları ({@code monitor_guide} /
 * {@code monitor_notes}) (tür, hedef) başına paylaşıldığından YALNIZ hedefi izleyen başka satır kalmadıysa silinir.
 * Öz-çağrı YOK (TransactionalSelfInvocationGuardTest): genel metotlar yalnız özel yardımcıları çağırır.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PermanentDeletionService {

    /** Sertifika kartının tanılama türleri (AdminController tanılama uçları). DOMAIN_EXPIRY / PROXY_CA_CHAIN paylaşılır, silinmez. */
    static final List<String> CERT_DIAG_RUN_TYPES = List.of("CONNECTION", "OPENSSL", "NETWORK", "HSTS");

    static final Set<String> PORT_ALERT_TYPES = MonitorTypeCatalog.ALERT_TYPES.get("port");
    static final Set<String> DNS_ALERT_TYPES = MonitorTypeCatalog.ALERT_TYPES.get("dns");

    /** Silme kaynaklı sessiz kapanışın çözen etiketi — diğer yedi izleme türünün silme yoluyla AYNI metin. */
    static final String RESOLVED_BY_DELETE = "Sistem (izleme silindi)";
    /** Envanter kaydı olmayan türev izleme kalıntısı temizlendi (2026-11-19). */
    static final String RESOLVED_BY_ORPHAN = "Sistem (envanter kaydı yok — kalıntı temizlendi)";

    /**
     * Envanterde bu alan adı için AĞ kaydı (etkin ya da duraklatılmış) var mı — türev Port / DNS satırının bağlandığı kayıt;
     * yoksa satır sahipsiz kalıntıdır (İzleme Panosu ile aynı kural). Elle yüklenen sertifika (MANUAL) ağ hedefi değildir.
     * Port bilerek bakılmaz: türev Port satırının portunu yönetici düzenleyebilir — o satır taranmaya devam eder.
     */
    static final String SQL_INV_HAS_DOMAIN = "SELECT COUNT(*) FROM certificate_inventory WHERE domain = ? "
            + "AND (cert_source IS NULL OR cert_source <> 'MANUAL') AND deleted_at IS NULL";

    private final JdbcTemplate jdbc;
    private final CertificateInventoryRepository inventoryRepo;
    private final EscalationService escalationService;

    /**
     * Dinamik taban çizgisi yapılandırması + örnekleri (hata taraması 2026-11-13 #7): türev / bağımsız Port-DNS kalıcı silmesinde
     * de silinir — eskiden kalıyordu (etkin satır, Grafana benimseme sayacı silinmiş izlemeyi sayıyordu). İsteğe bağlı: elle
     * kurulan testlerde yoktur. Temizlik commit SONRASI kendi işleminde koşar ({@link #removeBaseline}) — bu işlemi bozamaz.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.sitemonitor.service.baseline.BaselineConfigService baselineConfigService;

    /**
     * Envanter silmesinin sonucu — denetim ayrıntısı ve yanıt için.
     *
     * @param rows tablo → silinen satır sayısı (yalnız sıfırdan büyükler)
     */
    public record InventoryDeletion(Long id, String domain, Long teamId, int alertsClosed, int derivedMonitors,
                                    Map<String, Integer> rows) {
        public int checksDeleted() { return rows.getOrDefault("certificate_checks", 0); }
    }

    /**
     * Envanter kaydını ve ona ait her şeyi KALICI siler (yukarıdaki liste). Yetki / geçmiş / denetim ÇAĞIRANIN işidir
     * (tekil + toplu uç, tek seferlik çöp kutusu temizliği, aynı adlı eski çöp satırının temizliği).
     */
    @Transactional
    public InventoryDeletion deleteInventory(CertificateInventory inv) {
        return purgeInventory(inv);
    }

    /**
     * Aynı adı (harf duyarsız) taşıyan, ESKİ sürümden kalmış yumuşak silinmiş envanter satırlarını kalıcı siler — yeni
     * kayıt / yeniden adlandırma / içe aktarma o adı kullanabilsin (DB'deki harf-duyarlı UNIQUE dâhil). Tek seferlik
     * temizlik ({@link DeletedRecordsPurge}) normalde hepsini almıştır; bu, onun koşamadığı bir açılışın emniyet ağıdır.
     *
     * @return silinen satır sayısı
     */
    @Transactional
    public int purgeLegacyBinRows(String domain) {
        if (domain == null || domain.isBlank()) return 0;
        int n = 0;
        for (CertificateInventory old : inventoryRepo.findByDomainIgnoreCaseAndDeletedAtIsNotNull(domain.trim())) {
            purgeInventory(old);
            n++;
            log.info("Eski çöp kutusu satırı kalıcı silindi (aynı ad yeniden kullanılıyor): {} #{}", old.getDomain(), old.getId());
        }
        return n;
    }

    /**
     * Bağımsız Port izlemesini KALICI siler: açık alarmları (yalnız BU izlemenin, D-b1 sahiplik kuralı) sessizce kapanır,
     * kontrol serisi / özetleri / tanılamaları ve satır gider. Envanter TÜREVİ satır buraya gelmez (onun "silmesi"
     * duraklatmadır — envanterle yaşar).
     */
    @Transactional
    public void deleteStandalonePort(PortMonitor m) {
        if (m == null || m.getId() == null) return;
        escalationService.resolveOpenAlertsSilently(m.getHost(), PORT_ALERT_TYPES, RESOLVED_BY_DELETE,
                ownerCtx(m.getId(), m.getTeamId()));
        purgePortMonitors(List.of(m.getId()), new LinkedHashMap<>());
    }

    /** {@link #deleteStandalonePort} DNS ikizi. */
    @Transactional
    public void deleteStandaloneDns(DnsMonitor m) {
        if (m == null || m.getId() == null) return;
        escalationService.resolveOpenAlertsSilently(m.getDomain(), DNS_ALERT_TYPES, RESOLVED_BY_DELETE,
                ownerCtx(m.getId(), m.getTeamId()));
        purgeDnsMonitors(List.of(m.getId()), new LinkedHashMap<>());
    }

    /**
     * Envanter kaydı yeniden adlandırıldı / portu değişti (2026-11-19, kullanıcı onayı): o kayıttan türeyen Port ve DNS
     * izlemeleri YENİ ada / porta TAŞINIR (ayarları ve Port kontrol geçmişi korunur). Eskiden eski adla kalıyor, taramanın
     * atladığı "envanter dışı" kalıntıya dönüşüyor, yeni ad için de sayfa açılınca sıfırdan izleme oluşuyordu. DNS kaydının
     * değişiklik tabanı başka alan adına ait olduğu için ilk taramada kıyaslanmaz ({@code dns_records.checked_domain}) —
     * sahte DNS_CHANGED açılmaz. Hedefte BAYAT bir türev satır (eski bir yeniden adlandırmanın kalıntısı) varsa önce o
     * kalıcı silinir (açık alarmları sessizce kapanır) — benzersizlik ({@code uq_pm_host_port}, {@code uq_dnsm_domain})
     * yalnız türev satırlarda. Çağıran envanter kaydını henüz kaydetmemiştir (aynı işlem). Bağımsız satırlara dokunulmaz.
     *
     * @return taşınan satır sayısı
     */
    @Transactional
    public int retargetDerivedMonitors(String oldDomain, Integer oldPort, String newDomain, Integer newPort) {
        if (oldDomain == null || newDomain == null || oldDomain.isBlank() || newDomain.isBlank()) return 0;
        int op = oldPort != null ? oldPort : 443, np = newPort != null ? newPort : 443;
        boolean hostChanged = !oldDomain.equals(newDomain);
        if (!hostChanged && op == np) return 0;
        String now = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
                .withZone(java.time.ZoneOffset.UTC).format(java.time.Instant.now());
        int moved = 0;
        Map<String, Integer> rows = new LinkedHashMap<>();
        // Korunan satır: adı BİREBİR eşleşen (canlı) satır önce, sonra en eski — harf farkıyla kalmış bayat kalıntı tutulup
        // canlı satır (ve geçmişi) silinmesin.
        List<Long> ports = jdbc.queryForList("SELECT id FROM port_monitors WHERE LOWER(host) = LOWER(?) AND port = ? "
                + "AND (standalone IS NULL OR standalone = FALSE) ORDER BY CASE WHEN host = ? THEN 0 ELSE 1 END, id",
                Long.class, oldDomain, op, oldDomain);
        if (!ports.isEmpty()) {
            Long keep = ports.get(0);
            List<Long> stale = new ArrayList<>(jdbc.queryForList("SELECT id FROM port_monitors WHERE host = ? AND port = ? "
                    + "AND (standalone IS NULL OR standalone = FALSE) AND id <> ?", Long.class, newDomain, np, keep));
            stale.addAll(ports.subList(1, ports.size()));   // harf farkıyla çift kalmış eski satır (benzersizlik yalnız birebir)
            for (Long id : stale) closeDerivedAlarms(id, newDomain, PORT_ALERT_TYPES);
            purgePortMonitors(stale, rows);
            moved += jdbc.update("UPDATE port_monitors SET host = ?, port = ?, name = CASE WHEN name = ? THEN ? ELSE name END, "
                    + "updated_at = ? WHERE id = ?", newDomain, np, oldDomain, newDomain, now, keep);
        }
        if (hostChanged) {
            List<Long> dns = jdbc.queryForList("SELECT id FROM dns_monitors WHERE LOWER(domain) = LOWER(?) "
                    + "AND (standalone IS NULL OR standalone = FALSE) ORDER BY CASE WHEN domain = ? THEN 0 ELSE 1 END, id",
                    Long.class, oldDomain, oldDomain);
            if (!dns.isEmpty()) {
                Long keep = dns.get(0);
                List<Long> stale = new ArrayList<>(jdbc.queryForList("SELECT id FROM dns_monitors WHERE domain = ? "
                        + "AND (standalone IS NULL OR standalone = FALSE) AND id <> ?", Long.class, newDomain, keep));
                stale.addAll(dns.subList(1, dns.size()));
                for (Long id : stale) closeDerivedAlarms(id, newDomain, DNS_ALERT_TYPES);
                purgeDnsMonitors(stale, rows);
                moved += jdbc.update("UPDATE dns_monitors SET domain = ?, name = CASE WHEN name = ? THEN ? ELSE name END, "
                        + "updated_at = ? WHERE id = ?", newDomain, oldDomain, newDomain, now, keep);
            }
        }
        if (moved > 0 || !rows.isEmpty()) {
            log.info("Envanter yeniden adlandırma / port değişimi: {}:{} → {}:{} — {} türev izleme taşındı (bayat satır silindi: {})",
                    oldDomain, op, newDomain, np, moved, rows);
        }
        return moved;
    }

    /**
     * Envanter kaydı OLMAYAN türev Port satırını kalıcı siler (tek seferlik kalıntı temizliği, 2026-11-19). Koşul İŞLEM
     * İÇİNDE yeniden okunur: satır hâlâ türev mi ve envanterde bu alan adı için ağ kaydı hâlâ yok mu — arada
     * envantere eklenen ad silinmez. Açık alarmları (yalnız BU izlemenin) sessizce kapanır.
     *
     * @return silindiyse {@code true}
     */
    @Transactional
    public boolean deleteOrphanDerivedPort(long id) {
        List<Map<String, Object>> r = jdbc.queryForList("SELECT host FROM port_monitors WHERE id = ? "
                + "AND (standalone IS NULL OR standalone = FALSE)", id);
        if (r.isEmpty()) return false;
        String host = (String) r.get(0).get("host");
        if (host != null) {
            Integer has = jdbc.queryForObject(SQL_INV_HAS_DOMAIN, Integer.class, host);
            if (has != null && has > 0) return false;
        }
        closeDerivedAlarms(id, host, PORT_ALERT_TYPES);
        purgePortMonitors(List.of(id), new LinkedHashMap<>());
        return true;
    }

    /** {@link #deleteOrphanDerivedPort} DNS ikizi (envanterde bu alan adı için ağ kaydı yoksa). */
    @Transactional
    public boolean deleteOrphanDerivedDns(long id) {
        List<Map<String, Object>> r = jdbc.queryForList("SELECT domain FROM dns_monitors WHERE id = ? "
                + "AND (standalone IS NULL OR standalone = FALSE)", id);
        if (r.isEmpty()) return false;
        String domain = (String) r.get(0).get("domain");
        if (domain != null) {
            Integer has = jdbc.queryForObject(SQL_INV_HAS_DOMAIN, Integer.class, domain);
            if (has != null && has > 0) return false;
        }
        closeDerivedAlarms(id, domain, DNS_ALERT_TYPES);
        purgeDnsMonitors(List.of(id), new LinkedHashMap<>());
        return true;
    }

    /** Türev izlemenin YALNIZ kendi açık alarmları — sahip bağlamı {@code MonitoringController.ownerCtxDual}'ın türev dalı. */
    private void closeDerivedAlarms(Long id, String key, Set<String> types) {
        if (id == null || key == null) return;
        Map<String, Object> c = new HashMap<>();
        c.put("monitor_id", id);
        escalationService.resolveOpenAlertsSilently(key, types, RESOLVED_BY_ORPHAN, c);
    }

    /** Bağımsız izlemenin sahiplik bağlamı — MonitoringController.ownerCtxDual'ın standalone dalıyla AYNI anahtarlar. */
    static Map<String, Object> ownerCtx(Long monitorId, Long teamId) {
        Map<String, Object> c = new HashMap<>();
        if (monitorId != null) c.put("monitor_id", monitorId);
        if (teamId != null) c.put("team_id", teamId);
        c.put("standalone", true);
        return c;
    }

    // ── Özel yardımcılar (işlem çağıranın) ─────────────────────────────────────────────────────────────────────

    private InventoryDeletion purgeInventory(CertificateInventory inv) {
        String domain = inv.getDomain();
        Map<String, Integer> rows = new LinkedHashMap<>();
        int alertsClosed = domain == null ? 0 : escalationService.closeAlertsOnInventoryDelete(domain);
        int derived = 0;
        if (domain != null) {
            // Envanter türevi Port/DNS: standalone OLMAYAN satırlar, anahtar = envanter alan adı (birebir; envanterde UNIQUE).
            List<Long> ports = jdbc.queryForList(
                    "SELECT id FROM port_monitors WHERE LOWER(host) = LOWER(?) AND (standalone IS NULL OR standalone = FALSE)",
                    Long.class, domain);
            // Büyük/küçük harf duyarsız (2026-11-19): harf farkıyla kalmış eski türev satır da envanterle gider
            List<Long> dns = jdbc.queryForList(
                    "SELECT id FROM dns_monitors WHERE LOWER(domain) = LOWER(?) AND (standalone IS NULL OR standalone = FALSE)",
                    Long.class, domain);
            purgePortMonitors(ports, rows);
            purgeDnsMonitors(dns, rows);
            derived = ports.size() + dns.size();

            count(rows, "uptime_checks", jdbc.update("DELETE FROM uptime_checks WHERE domain = ?", domain));
            count(rows, "monitor_check_daily", jdbc.update(
                    "DELETE FROM monitor_check_daily WHERE monitor_type = 'UPTIME' AND monitor_key = ?", domain));
            count(rows, "monitor_check_hourly", jdbc.update(
                    "DELETE FROM monitor_check_hourly WHERE monitor_type = 'UPTIME' AND monitor_key = ?", domain));
            count(rows, "certificate_checks", jdbc.update("DELETE FROM certificate_checks WHERE domain = ?", domain));
            count(rows, "latest_checks", jdbc.update("DELETE FROM latest_checks WHERE domain = ?", domain));
            count(rows, "certificate_note_revisions", jdbc.update(
                    "DELETE FROM certificate_note_revisions WHERE note_id IN (SELECT id FROM certificate_notes WHERE domain = ?)", domain));
            count(rows, "certificate_notes", jdbc.update("DELETE FROM certificate_notes WHERE domain = ?", domain));
            count(rows, "weak_algo_exception", jdbc.update("DELETE FROM weak_algo_exception WHERE domain = ?", domain));
            count(rows, "diagnostic_runs", jdbc.update(
                    "DELETE FROM diagnostic_runs WHERE domain = ? AND run_type IN (?, ?, ?, ?)",
                    domain, CERT_DIAG_RUN_TYPES.get(0), CERT_DIAG_RUN_TYPES.get(1), CERT_DIAG_RUN_TYPES.get(2),
                    CERT_DIAG_RUN_TYPES.get(3)));
        }
        if (inv.getId() != null) {
            count(rows, "manual_certificate_versions",
                    jdbc.update("DELETE FROM manual_certificate_versions WHERE inventory_id = ?", inv.getId()));
            inventoryRepo.delete(inv);
            // HEMEN yazılır: Hibernate silmeyi işlem sonuna (güncellemelerden SONRA) bırakır — aynı işlemde bu adı alan
            // bir yeniden adlandırma ya da kayıt, DB'deki UNIQUE(domain) kısıtına eski satır hâlâ dururken çarpardı.
            inventoryRepo.flush();
            count(rows, "certificate_inventory", 1);
        }
        log.info("Envanter kaydı KALICI silindi: {} #{} (alarm kapandı={}, türev izleme={}, satırlar={})",
                domain, inv.getId(), alertsClosed, derived, rows);
        return new InventoryDeletion(inv.getId(), domain, inv.getTeamId(), alertsClosed, derived, rows);
    }

    private void purgePortMonitors(List<Long> ids, Map<String, Integer> rows) {
        if (ids == null || ids.isEmpty()) return;
        // Hedef anahtarları (host:port) satır silinmeden ÖNCE okunur — rehber/not yalnız hedef öksüz kaldıysa gider.
        List<String> targets = new ArrayList<>();
        for (Long id : ids) {
            jdbc.query("SELECT host, port FROM port_monitors WHERE id = ?",
                    rs -> { targets.add(rs.getString(1) + ":" + rs.getInt(2)); }, id);
            String key = String.valueOf(id);
            count(rows, "port_checks", jdbc.update("DELETE FROM port_checks WHERE monitor_id = ?", id));
            count(rows, "monitor_check_daily", jdbc.update(
                    "DELETE FROM monitor_check_daily WHERE monitor_type = 'PORT' AND monitor_key = ?", key));
            count(rows, "monitor_check_hourly", jdbc.update(
                    "DELETE FROM monitor_check_hourly WHERE monitor_type = 'PORT' AND monitor_key = ?", key));
            count(rows, "diagnostic_runs", jdbc.update("DELETE FROM diagnostic_runs WHERE domain = ?", "port-monitor:" + id));
            count(rows, "port_monitors", jdbc.update("DELETE FROM port_monitors WHERE id = ?", id));
            removeBaseline(com.sitemonitor.service.baseline.BaselineTypes.PORT, id);
        }
        for (String target : targets) {
            int cut = target.lastIndexOf(':');
            Integer left = jdbc.queryForObject("SELECT COUNT(*) FROM port_monitors WHERE host = ? AND port = ?",
                    Integer.class, target.substring(0, cut), Integer.parseInt(target.substring(cut + 1)));
            if (left == null || left == 0) purgeNotesAndGuide("PORT", target, rows);
        }
    }

    private void purgeDnsMonitors(List<Long> ids, Map<String, Integer> rows) {
        if (ids == null || ids.isEmpty()) return;
        List<String> targets = new ArrayList<>();
        for (Long id : ids) {
            jdbc.query("SELECT domain FROM dns_monitors WHERE id = ?", rs -> { targets.add(rs.getString(1)); }, id);
            count(rows, "dns_records", jdbc.update("DELETE FROM dns_records WHERE monitor_id = ?", id));
            count(rows, "diagnostic_runs", jdbc.update("DELETE FROM diagnostic_runs WHERE domain = ?", "dns-monitor:" + id));
            count(rows, "dns_monitors", jdbc.update("DELETE FROM dns_monitors WHERE id = ?", id));
            removeBaseline(com.sitemonitor.service.baseline.BaselineTypes.DNS, id);
        }
        for (String target : new java.util.LinkedHashSet<>(targets)) {
            Integer left = jdbc.queryForObject("SELECT COUNT(*) FROM dns_monitors WHERE domain = ?", Integer.class, target);
            if (left == null || left == 0) purgeNotesAndGuide("DNS", target, rows);
        }
    }

    /**
     * Silinen izlemenin taban çizgisi yapılandırması + örnekleri; bean yoksa (testler) atlanır. Gerileme taraması 2026-11-13
     * (bulgu 3): temizlik silme işlemine KATILMAZ — işlem commit olduktan SONRA kendi işleminde koşar. Eskiden çağıranın
     * işlemine katılan {@code remove} bir hatada (sorgu zaman aşımı, bağlantı) dış işlemi rollback-only işaretliyor, buradaki
     * catch WARN yazıp devam etse de commit {@code UnexpectedRollbackException} ile düşüyordu (silme 500; örnekler zaten
     * kendi bağlantısında silinmişti). Geri alınan silmede taban da durur (izleme hâlâ var). İşlem yoksa hemen koşar.
     */
    private void removeBaseline(String type, Long id) {
        if (baselineConfigService == null || id == null) return;
        Runnable cleanup = () -> {
            try {
                baselineConfigService.removeInOwnTransaction(type, id);
            } catch (RuntimeException e) {
                log.warn("Silinen izlemenin taban çizgisi kayıtları silinemedi ({}#{}): {}", type, id, e.getMessage());
            }
        };
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            cleanup.run();
                        }
                    });
        } else {
            cleanup.run();
        }
    }

    private void purgeNotesAndGuide(String type, String target, Map<String, Integer> rows) {
        if (target == null) return;
        count(rows, "monitor_notes", jdbc.update(
                "DELETE FROM monitor_notes WHERE monitor_type = ? AND target = ?", type, target));
        count(rows, "monitor_guide", jdbc.update(
                "DELETE FROM monitor_guide WHERE monitor_type = ? AND target = ?", type, target));
    }

    private static void count(Map<String, Integer> rows, String table, int n) {
        if (n > 0) rows.merge(table, n, Integer::sum);
    }
}
