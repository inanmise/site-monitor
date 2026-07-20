package com.certmonitor.service;

import com.certmonitor.model.CertificateInventory;
import com.certmonitor.model.DnsMonitor;
import com.certmonitor.model.DomainMonitor;
import com.certmonitor.model.HttpMonitor;
import com.certmonitor.model.KeywordMonitor;
import com.certmonitor.model.PingMonitor;
import com.certmonitor.model.PortMonitor;
import com.certmonitor.repository.CertificateInventoryRepository;
import com.certmonitor.repository.DnsMonitorRepository;
import com.certmonitor.repository.DomainMonitorRepository;
import com.certmonitor.repository.HttpMonitorRepository;
import com.certmonitor.repository.KeywordMonitorRepository;
import com.certmonitor.repository.PingMonitorRepository;
import com.certmonitor.repository.PortMonitorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Tek-seferlik, idempotent açılış backfill'i (İzleme Grupları takım-bazlı geçişi):
 *  1) team_id'si NULL olan monitörleri cert envanterinden (domain eşleşmesi) doldur; türetilemeyeni LOGLA (tahminle atama YOK).
 *  2) 7 türdeki mevcut (team, group_name) çiftlerini monitoring_groups registry'sine seed et (get-or-create).
 * Açılıştan sonra hepsi yerinde → tekrar çalışınca no-op.
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
    private final MonitoringGroupService groupService;

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void run() {
        try {
            Map<String, Long> domainTeam = new HashMap<>();
            for (CertificateInventory inv : inventoryRepo.findAll())
                if (inv.getDomain() != null && inv.getTeamId() != null)
                    domainTeam.putIfAbsent(inv.getDomain().toLowerCase(Locale.ROOT), inv.getTeamId());

            int filled = 0, orphan = 0;
            // 1) null-team fix — envanter domain eşleşmesinden
            for (HttpMonitor m : httpRepo.findAll())    { int r = fill(m.getTeamId(), host(m.getUrl()), domainTeam, m::setTeamId, m.getGroupName()); filled += pos(r); orphan += neg(r); if (r > 0) httpRepo.save(m); }
            for (KeywordMonitor m : keywordRepo.findAll()) { int r = fill(m.getTeamId(), host(m.getUrl()), domainTeam, m::setTeamId, m.getGroupName()); filled += pos(r); orphan += neg(r); if (r > 0) keywordRepo.save(m); }
            for (PingMonitor m : pingRepo.findAll())    { int r = fill(m.getTeamId(), host(m.getHost()), domainTeam, m::setTeamId, m.getGroupName()); filled += pos(r); orphan += neg(r); if (r > 0) pingRepo.save(m); }
            for (PortMonitor m : portRepo.findAll())    { int r = fill(m.getTeamId(), host(m.getHost()), domainTeam, m::setTeamId, m.getGroupName()); filled += pos(r); orphan += neg(r); if (r > 0) portRepo.save(m); }
            for (DnsMonitor m : dnsRepo.findAll())      { int r = fill(m.getTeamId(), host(m.getDomain()), domainTeam, m::setTeamId, m.getGroupName()); filled += pos(r); orphan += neg(r); if (r > 0) dnsRepo.save(m); }
            for (DomainMonitor m : domainRepo.findAll()) { int r = fill(m.getTeamId(), host(m.getDomain()), domainTeam, m::setTeamId, m.getGroupName()); filled += pos(r); orphan += neg(r); if (r > 0) domainRepo.save(m); }
            if (filled > 0 || orphan > 0)
                log.info("Monitoring group backfill: {} monitör takımı envanterden dolduruldu; {} türetilemedi (grubu var, teamId=null kaldı)", filled, orphan);

            // 2) registry seed — 7 türün mevcut (team, group) çiftleri
            int seeded = 0;
            seeded += seed("cert",    inventoryRepo.groupCountsByTeam());
            seeded += seed("http",    httpRepo.groupCountsByTeam());
            seeded += seed("ping",    pingRepo.groupCountsByTeam());
            seeded += seed("port",    portRepo.groupCountsByTeam());
            seeded += seed("dns",     dnsRepo.groupCountsByTeam());
            seeded += seed("keyword", keywordRepo.groupCountsByTeam());
            seeded += seed("domain",  domainRepo.groupCountsByTeam());
            if (seeded > 0) log.info("Monitoring group registry seed: {} (team,type,name) grubu tarandı", seeded);
        } catch (Exception e) {
            log.warn("Monitoring group backfill failed: {}", e.getMessage());
        }
    }

    /** Dönüş: 1=dolduruldu, -1=grubu var ama türetilemedi (orphan), 0=değişiklik yok. */
    private static int fill(Long teamId, String domain, Map<String, Long> domainTeam, java.util.function.Consumer<Long> setTeam, String group) {
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
            default        -> 0;
        };
    }

    /** URL/host'tan ana makineyi çıkarır (şema/port/path olmadan). */
    private static String host(String raw) {
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
