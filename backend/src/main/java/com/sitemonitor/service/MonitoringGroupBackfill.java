package com.sitemonitor.service;

import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.DnsMonitor;
import com.sitemonitor.model.DomainMonitor;
import com.sitemonitor.model.HttpMonitor;
import com.sitemonitor.model.KeywordMonitor;
import com.sitemonitor.model.PingMonitor;
import com.sitemonitor.model.PortMonitor;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.DnsMonitorRepository;
import com.sitemonitor.repository.DomainMonitorRepository;
import com.sitemonitor.repository.HttpMonitorRepository;
import com.sitemonitor.repository.KeywordMonitorRepository;
import com.sitemonitor.repository.PageMonitorRepository;
import com.sitemonitor.repository.PingMonitorRepository;
import com.sitemonitor.repository.PortMonitorRepository;
import com.sitemonitor.repository.PageSpeedMonitorRepository;
import com.sitemonitor.repository.ScriptedMonitorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Tek-seferlik, idempotent açılış backfill'i (İzleme Grupları takım-bazlı geçişi):
 *  1) team_id'si NULL olan monitörleri cert envanterinden (domain eşleşmesi) doldur; türetilemeyeni LOGLA (tahminle atama YOK).
 *  2) 9 türdeki mevcut (team, group_name) çiftlerini monitoring_groups registry'sine seed et (get-or-create).
 * (1) YALNIZ takım geçişi öncesinden kalan türler için çalışır; page/scripted takım zorunluyken eklendi, team_id'leri
 * doğuştan dolu — bu yüzden yalnız (2)'ye dahiller.
 * Açılıştan sonra hepsi yerinde → tekrar çalışınca no-op.
 *
 * <p><b>Çok pod (2026-10-23, R2a · D2, MP-46).</b> (1) eskiden HER pod'un HER açılışında altı tablonun tamamını okuyup
 * takımsız izlemeyi bütün varlık olarak kaydediyordu ({@code save}; izleme varlıklarında {@code @DynamicUpdate} yok): açılan pod
 * okuduğu ESKİ satırı yazınca o arada kullanıcının yaptığı düzenleme ya da liderin duraklatması geri alınabiliyordu. Artık
 * (1) koşullu UPDATE'tir ({@code SET team_id = ? WHERE id = ? AND team_id IS NULL} — yalnız takım kolonu, yalnız hâlâ takımsız
 * satır), tek pod'da koşar ({@code scheduler_lock} {@value #LOCK_NAME}) ve TAM başarıdan sonra yazılan sürümlü
 * {@code schema_patch_markers} nişanıyla ({@value #MARKER}, kilit İÇİNDE yeniden okunur) bir daha koşmaz. (2) registry
 * tohumlaması zaten yarışa dayanıklı ({@code MonitoringGroupService.getOrCreate} ayrı işlem) ve her açılışta koşmaya devam eder.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MonitoringGroupBackfill {

    private final CertificateInventoryRepository inventoryRepo;
    private final HttpMonitorRepository httpRepo;
    private final PingMonitorRepository pingRepo;
    private final PortMonitorRepository portRepo;
    private final DnsMonitorRepository dnsRepo;
    private final KeywordMonitorRepository keywordRepo;
    private final DomainMonitorRepository domainRepo;
    private final PageMonitorRepository pageRepo;
    private final ScriptedMonitorRepository scriptedRepo;
    private final PageSpeedMonitorRepository pageSpeedRepo;
    private final MonitoringGroupService groupService;

    /** Kilit ve nişan adları — KALICI (değişirse tek seferlik doldurma yeniden koşar). */
    static final String LOCK_NAME = "monitoring-group-team-backfill";
    static final String MARKER = "2026-10-23-monitoring-group-team-backfill-v2";
    /** Takımsız izlemelerin tablosu → hedef kolonu (host / URL / alan adı). */
    static final Map<String, String> NULL_TEAM_TABLES = new java.util.LinkedHashMap<>();
    static {
        NULL_TEAM_TABLES.put("http_monitors", "url");
        NULL_TEAM_TABLES.put("keyword_monitors", "url");
        NULL_TEAM_TABLES.put("ping_monitors", "host");
        NULL_TEAM_TABLES.put("port_monitors", "host");
        NULL_TEAM_TABLES.put("dns_monitors", "domain");
        NULL_TEAM_TABLES.put("domain_monitors", "domain");
    }

    /** Koşullu yazım yolu; yoksa (elle kurulan testler) eski varlık yolu. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.springframework.jdbc.core.JdbcTemplate jdbc;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.sitemonitor.service.schedule.ClusterLockService lockService;

    void setJdbc(org.springframework.jdbc.core.JdbcTemplate j) { this.jdbc = j; }
    void setLockService(com.sitemonitor.service.schedule.ClusterLockService l) { this.lockService = l; }

    @EventListener(ApplicationReadyEvent.class)
    public void run() {
        try {
            if (jdbc != null) fillNullTeamsOnce();
            else fillNullTeamsLegacy();

            // 2) registry seed — 7 türün mevcut (team, group) çiftleri
            int seeded = 0;
            seeded += seed("cert",    inventoryRepo.groupCountsByTeam());
            seeded += seed("http",    httpRepo.groupCountsByTeam());
            seeded += seed("ping",    pingRepo.groupCountsByTeam());
            seeded += seed("port",    portRepo.groupCountsByTeam());
            seeded += seed("dns",     dnsRepo.groupCountsByTeam());
            seeded += seed("keyword", keywordRepo.groupCountsByTeam());
            seeded += seed("domain",  domainRepo.groupCountsByTeam());
            // page/scripted sonradan grup taşımaya başladı ama seed listesine girmemişti: bu türlerin MEVCUT
            // grupları registry'de hiç oluşmuyordu → İzleme Grupları ekranında görünmüyor, yeniden adlandırılamıyordu.
            seeded += seed("page",     pageRepo.groupCountsByTeam());
            seeded += seed("scripted", scriptedRepo.groupCountsByTeam());
            seeded += seed("pagespeed", pageSpeedRepo.groupCountsByTeam());
            if (seeded > 0) log.info("Monitoring group registry seed: {} (team,type,name) grubu tarandı", seeded);
        } catch (Exception e) {
            log.warn("Monitoring group backfill failed: {}", e.getMessage());
        }
    }

    /**
     * (1) koşullu UPDATE ile, tek pod'da, nişan yazılana dek. Dönüş: bu çağrıda doldurulan satır; nişan zaten varsa / kilit
     * başka pod'daysa -1.
     */
    int fillNullTeamsOnce() {
        try {
            jdbc.execute(StandaloneMonitorDeletionBackfill.MARKERS_DDL);
            if (markerExists()) return -1;
        } catch (Exception e) {
            log.warn("Monitoring group backfill: nişan okunamadı — bu açılışta atlandı: {}", e.getMessage());
            return -1;
        }
        com.sitemonitor.service.schedule.ClusterLockService.Hold hold = null;
        if (lockService != null) {
            hold = lockService.tryAcquire(LOCK_NAME).orElse(null);
            if (hold == null) {
                log.debug("Monitoring group backfill: kilit başka pod'da — bu pod atlıyor");
                return -1;
            }
        }
        try {
            if (markerExists()) return -1;   // kilidi bekleyen pod: diğeri bitirip nişanı yazmış olabilir
            Map<String, Long> domainTeam = new HashMap<>();
            for (Map<String, Object> r : jdbc.queryForList(
                    "SELECT domain, team_id FROM certificate_inventory WHERE domain IS NOT NULL AND team_id IS NOT NULL ORDER BY id")) {
                Object d = r.get("domain"), t = r.get("team_id");
                if (d != null && t instanceof Number n) domainTeam.putIfAbsent(d.toString().toLowerCase(Locale.ROOT), n.longValue());
            }
            int filled = 0, orphan = 0, lost = 0;
            for (Map.Entry<String, String> t : NULL_TEAM_TABLES.entrySet()) {
                String table = t.getKey(), col = t.getValue();
                for (Map<String, Object> row : jdbc.queryForList(
                        "SELECT id, " + col + " AS target, group_name FROM " + table + " WHERE team_id IS NULL")) {
                    if (!(row.get("id") instanceof Number id)) continue;
                    Object target = row.get("target"), group = row.get("group_name");
                    java.util.concurrent.atomic.AtomicReference<Long> team = new java.util.concurrent.atomic.AtomicReference<>();
                    int r = fill(null, host(target == null ? null : target.toString()), domainTeam, team::set,
                            group == null ? null : group.toString());
                    if (r < 0) { orphan++; continue; }
                    if (r == 0) continue;
                    int n = jdbc.update("UPDATE " + table + " SET team_id = ? WHERE id = ? AND team_id IS NULL", team.get(), id.longValue());
                    if (n == 1) filled++; else lost++;   // 0 = o arada kullanıcı takım atadı — ezilmez
                }
            }
            jdbc.update("INSERT INTO schema_patch_markers (patch_key, applied_at, rows_affected) VALUES (?, ?, ?)",
                    MARKER, java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
                            .withZone(java.time.ZoneOffset.UTC).format(java.time.Instant.now()), filled);
            if (filled > 0 || orphan > 0 || lost > 0)
                log.info("Monitoring group backfill: {} monitör takımı envanterden dolduruldu; {} türetilemedi (grubu var, teamId=null kaldı); "
                        + "{} satıra o arada takım atanmıştı (dokunulmadı)", filled, orphan, lost);
            return filled;
        } catch (Exception e) {
            // Nişan YAZILMADI: sonraki açılış yeniden dener (koşullu yazım tekrar güvenli).
            log.warn("Monitoring group backfill (takımsız izlemeler) tamamlanamadı — sonraki açılış yeniden dener: {}", e.getMessage());
            return -1;
        } finally {
            if (hold != null) lockService.release(hold);
        }
    }

    private boolean markerExists() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM schema_patch_markers WHERE patch_key = ?", Integer.class, MARKER);
        return n != null && n > 0;
    }

    /** Eski yol (JdbcTemplate'siz elle kurulan testler) — varlık kaydı. Üretimde {@link #fillNullTeamsOnce}. */
    private void fillNullTeamsLegacy() {
        Map<String, Long> domainTeam = new HashMap<>();
        for (CertificateInventory inv : inventoryRepo.findAll())
            if (inv.getDomain() != null && inv.getTeamId() != null)
                domainTeam.putIfAbsent(inv.getDomain().toLowerCase(Locale.ROOT), inv.getTeamId());

        int filled = 0, orphan = 0;
        for (HttpMonitor m : httpRepo.findAll())    { int r = fill(m.getTeamId(), host(m.getUrl()), domainTeam, m::setTeamId, m.getGroupName()); filled += pos(r); orphan += neg(r); if (r > 0) httpRepo.save(m); }
        for (KeywordMonitor m : keywordRepo.findAll()) { int r = fill(m.getTeamId(), host(m.getUrl()), domainTeam, m::setTeamId, m.getGroupName()); filled += pos(r); orphan += neg(r); if (r > 0) keywordRepo.save(m); }
        for (PingMonitor m : pingRepo.findAll())    { int r = fill(m.getTeamId(), host(m.getHost()), domainTeam, m::setTeamId, m.getGroupName()); filled += pos(r); orphan += neg(r); if (r > 0) pingRepo.save(m); }
        for (PortMonitor m : portRepo.findAll())    { int r = fill(m.getTeamId(), host(m.getHost()), domainTeam, m::setTeamId, m.getGroupName()); filled += pos(r); orphan += neg(r); if (r > 0) portRepo.save(m); }
        for (DnsMonitor m : dnsRepo.findAll())      { int r = fill(m.getTeamId(), host(m.getDomain()), domainTeam, m::setTeamId, m.getGroupName()); filled += pos(r); orphan += neg(r); if (r > 0) dnsRepo.save(m); }
        for (DomainMonitor m : domainRepo.findAll()) { int r = fill(m.getTeamId(), host(m.getDomain()), domainTeam, m::setTeamId, m.getGroupName()); filled += pos(r); orphan += neg(r); if (r > 0) domainRepo.save(m); }
        if (filled > 0 || orphan > 0)
            log.info("Monitoring group backfill: {} monitör takımı envanterden dolduruldu; {} türetilemedi (grubu var, teamId=null kaldı)", filled, orphan);
    }

    /** Dönüş: 1=dolduruldu, -1=grubu var ama türetilemedi (orphan), 0=değişiklik yok. */
    static int fill(Long teamId, String domain, Map<String, Long> domainTeam, java.util.function.Consumer<Long> setTeam, String group) {
        if (teamId != null) return 0;
        if (domain != null) {
            Long t = domainTeam.get(domain.toLowerCase(Locale.ROOT));
            if (t != null) { setTeam.accept(t); return 1; }
        }
        return (group != null && !group.isBlank()) ? -1 : 0;   // grubu olan ama türetilemeyen → orphan raporla
    }

    private static int pos(int r) { return r > 0 ? 1 : 0; }
    private static int neg(int r) { return r < 0 ? 1 : 0; }

    private int seed(String type, List<Object[]> rows) {
        int n = 0;
        for (Object[] r : rows) {
            Long teamId = r[0] instanceof Number num ? num.longValue() : null;
            String name = r[1] == null ? null : r[1].toString();
            if (teamId == null || name == null || name.isBlank()) continue;
            String canonical = groupService.getOrCreate(teamId, type, name, "backfill");
            // Casing varyantlarını kanonik ada eşitle ("prod" → "Prod"): registry case-insensitive tekilleştirdiği
            // için farklı casing'li monitör satırları count/rename dışında görünmez kalırdı.
            if (canonical != null && !canonical.equals(name)) {
                int fixed = renameRows(type, teamId, name, canonical);
                if (fixed > 0) log.info("Monitoring group backfill: team={} type={} '{}' → '{}' ({} satır kanonik casing'e çekildi)",
                        teamId, type, name, canonical, fixed);
            }
            n++;
        }
        return n;
    }

    /** YALNIZ verilen türün tablosunda grup adını kanonik casing'e çeker (rename sorguları case-insensitive eşleşir). */
    private int renameRows(String type, Long teamId, String oldName, String newName) {
        return switch (type) {
            case "cert"    -> inventoryRepo.renameGroupForTeam(teamId, oldName, newName);
            case "http"    -> httpRepo.renameGroupForTeam(teamId, oldName, newName);
            case "ping"    -> pingRepo.renameGroupForTeam(teamId, oldName, newName);
            case "port"    -> portRepo.renameGroupForTeam(teamId, oldName, newName);
            case "dns"     -> dnsRepo.renameGroupForTeam(teamId, oldName, newName);
            case "keyword" -> keywordRepo.renameGroupForTeam(teamId, oldName, newName);
            case "domain"  -> domainRepo.renameGroupForTeam(teamId, oldName, newName);
            case "page"    -> pageRepo.renameGroupForTeam(teamId, oldName, newName);
            case "scripted"-> scriptedRepo.renameGroupForTeam(teamId, oldName, newName);
            case "pagespeed"-> pageSpeedRepo.renameGroupForTeam(teamId, oldName, newName);
            default        -> 0;
        };
    }

    /** URL/host'tan ana makineyi çıkarır (şema/port/path olmadan). Paket görünür: yanlış ayrıştırma
     *  monitörü YANLIŞ TAKIMA düşürür (o takım başkasının monitörünü görür) — testle kilitli. */
    static String host(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        int sc = s.indexOf("://");
        if (sc >= 0) s = s.substring(sc + 3);
        int slash = s.indexOf('/');
        if (slash >= 0) s = s.substring(0, slash);
        int colon = s.indexOf(':');
        if (colon >= 0) s = s.substring(0, colon);
        return s.isBlank() ? null : s;
    }
}
