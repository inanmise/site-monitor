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

    private final JdbcTemplate jdbc;
    private final CertificateInventoryRepository inventoryRepo;
    private final EscalationService escalationService;

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
                    "SELECT id FROM port_monitors WHERE host = ? AND (standalone IS NULL OR standalone = FALSE)", Long.class, domain);
            List<Long> dns = jdbc.queryForList(
                    "SELECT id FROM dns_monitors WHERE domain = ? AND (standalone IS NULL OR standalone = FALSE)", Long.class, domain);
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
        }
        for (String target : new java.util.LinkedHashSet<>(targets)) {
            Integer left = jdbc.queryForObject("SELECT COUNT(*) FROM dns_monitors WHERE domain = ?", Integer.class, target);
            if (left == null || left == 0) purgeNotesAndGuide("DNS", target, rows);
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
