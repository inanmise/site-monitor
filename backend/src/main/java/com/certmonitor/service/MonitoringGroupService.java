package com.certmonitor.service;

import com.certmonitor.controller.SessionScope;
import com.certmonitor.model.MonitoringGroup;
import com.certmonitor.model.Team;
import com.certmonitor.repository.AlertEventRepository;
import com.certmonitor.repository.CertificateInventoryRepository;
import com.certmonitor.repository.DnsMonitorRepository;
import com.certmonitor.repository.DomainMonitorRepository;
import com.certmonitor.repository.HttpMonitorRepository;
import com.certmonitor.repository.KeywordMonitorRepository;
import com.certmonitor.repository.MonitoringGroupRepository;
import com.certmonitor.repository.PingMonitorRepository;
import com.certmonitor.repository.PortMonitorRepository;
import com.certmonitor.repository.TeamRepository;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

/**
 * İZLEME GRUPLARI — TAKIM + İZLEME TÜRÜ bazlı merkezi registry. Grup adları (team, type, name) üçlüsünde case-insensitive
 * benzersiz: aynı ad ("deneme") DNS ve Ping türlerinde AYRI gruplardır. Kullanıcı yalnız KENDİ takım(lar)ının gruplarını
 * görür/yeniden adlandırır. Monitör + envanter create/update {@link #getOrCreate} ile grubu (türüyle) kaydeder (kanonik ad);
 * {@link #rename} registry'yi + YALNIZ o türün tablosunu + o türün alarm geçmişini tek transaction'da günceller.
 * Grup-taşıyan türler: cert (envanter), http, ping, port, dns, keyword, domain.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MonitoringGroupService {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    /** İzleme türü → o türe ait AlertEvent.alertType kümesi (alert_events type-scope rename için). */
    private static final Map<String, Set<String>> TYPE_ALERTS = Map.of(
            "cert",    Set.of("EXPIRY", "CHAIN_BROKEN", "REVOKED", "MISMATCH"),
            "http",    Set.of("ACCESSIBILITY", "HTTP_DOWN", "HTTP_SSL", "DOMAIN_EXPIRY"),
            "port",    Set.of("PORT_DOWN", "PORT_SLOW"),
            "dns",     Set.of("DNS_FAILURE", "DNS_CHANGED", "DNS_SLOW", "DNS_UNEXPECTED", "DNS_INCONSISTENT"),
            "keyword", Set.of("KEYWORD", "KEYWORD_SLOW", "KEYWORD_SSL", "KEYWORD_DOMAIN_EXPIRY"),
            "ping",    Set.of("PING_DOWN"),
            "domain",  Set.of("DOMAINMON_EXPIRY", "DOMAINMON_UNKNOWN", "DOMAINMON_STATUS", "DOMAINMON_CHANGED"));

    private final MonitoringGroupRepository groupRepo;
    private final CertificateInventoryRepository certRepo;
    private final HttpMonitorRepository httpRepo;
    private final PingMonitorRepository pingRepo;
    private final PortMonitorRepository portRepo;
    private final DnsMonitorRepository dnsRepo;
    private final KeywordMonitorRepository keywordRepo;
    private final DomainMonitorRepository domainRepo;
    private final AlertEventRepository alertEventRepo;
    private final TeamRepository teamRepo;

    public record GroupInfo(Long id, Long teamId, String teamName, String type, String name, int count) {}

    /** Grup adını (takım, tür) için registry'de get-or-create eder (case-insensitive). Boş ad → null (grup yok).
     *  Dönen KANONİK adı monitör/envanterin group_name'ine yaz (casing tekilleşir). */
    @Transactional
    public String getOrCreate(Long teamId, String type, String rawName, String actor) {
        if (rawName == null) return null;
        String name = rawName.trim();
        if (name.isEmpty()) return null;
        if (teamId == null) return name;   // takım zorunlu kuralı çağıran uçta; registry'ye takımsız yazma
        String lower = name.toLowerCase(Locale.ROOT);
        Optional<MonitoringGroup> existing = groupRepo.findByTeamIdAndTypeAndNameLower(teamId, type, lower);
        if (existing.isPresent()) return existing.get().getName();
        MonitoringGroup g = new MonitoringGroup();
        g.setTeamId(teamId);
        g.setType(type);
        g.setName(name);
        g.setNameLower(lower);
        g.setCreatedBy(actor);
        g.setCreatedAt(ISO.format(Instant.now()));
        try {
            return groupRepo.save(g).getName();
        } catch (DataIntegrityViolationException race) {   // eşzamanlı oluşturma → mevcut kanonik ad
            return groupRepo.findByTeamIdAndTypeAndNameLower(teamId, type, lower).map(MonitoringGroup::getName).orElse(name);
        }
    }

    /** Monitör nesnesinden İZLEME TÜRÜNÜ çıkararak get-or-create (controller'larda tek-satır, tür-agnostik kullanım). */
    @Transactional
    public String getOrCreateFor(Object monitor, Long teamId, String rawName, String actor) {
        return getOrCreate(teamId, typeOf(monitor), rawName, actor);
    }

    private static String typeOf(Object m) {
        if (m == null) return "";
        return switch (m.getClass().getSimpleName()) {
            case "HttpMonitor"    -> "http";
            case "PingMonitor"    -> "ping";
            case "PortMonitor"    -> "port";
            case "DnsMonitor"     -> "dns";
            case "KeywordMonitor" -> "keyword";
            case "DomainMonitor"  -> "domain";
            default               -> "";
        };
    }

    /** Kapsam-filtreli grup listesi. {@code viewTeamIds} null (global admin) → tüm; değilse yalnız o takımlar.
     *  {@code teamFilter}/{@code typeFilter} verilirse daraltır (çağıran teamFilter'ın kapsamda olduğunu doğrular). */
    @Transactional(readOnly = true)
    public List<GroupInfo> listForScope(List<Long> viewTeamIds, Long teamFilter, String typeFilter) {
        List<MonitoringGroup> groups;
        if (teamFilter != null) {
            groups = (typeFilter != null)
                    ? groupRepo.findByTeamIdAndTypeOrderByNameAsc(teamFilter, typeFilter)
                    : groupRepo.findByTeamIdOrderByTypeAscNameAsc(teamFilter);
        } else if (viewTeamIds == null) {
            groups = groupRepo.findAllByOrderByTeamIdAscTypeAscNameAsc();
        } else if (viewTeamIds.isEmpty()) {
            return List.of();
        } else {
            groups = groupRepo.findByTeamIdInOrderByTeamIdAscTypeAscNameAsc(viewTeamIds);
        }
        if (typeFilter != null) groups = groups.stream().filter(g -> typeFilter.equals(g.getType())).toList();

        Map<String, Integer> counts = new HashMap<>();   // "type#teamId#name" → count
        countInto(counts, "cert",    certRepo.groupCountsByTeam());
        countInto(counts, "http",    httpRepo.groupCountsByTeam());
        countInto(counts, "ping",    pingRepo.groupCountsByTeam());
        countInto(counts, "port",    portRepo.groupCountsByTeam());
        countInto(counts, "dns",     dnsRepo.groupCountsByTeam());
        countInto(counts, "keyword", keywordRepo.groupCountsByTeam());
        countInto(counts, "domain",  domainRepo.groupCountsByTeam());

        Map<Long, String> teamNames = new HashMap<>();
        for (Team tm : teamRepo.findAll()) teamNames.put(tm.getId(), tm.getName());

        List<GroupInfo> out = new ArrayList<>();
        for (MonitoringGroup g : groups) {
            int c = counts.getOrDefault(g.getType() + "#" + g.getTeamId() + "#" + g.getName(), 0);
            out.add(new GroupInfo(g.getId(), g.getTeamId(), teamNames.get(g.getTeamId()), g.getType(), g.getName(), c));
        }
        return out;
    }

    private static void countInto(Map<String, Integer> acc, String type, List<Object[]> rows) {
        for (Object[] r : rows) {
            if (r[0] == null || r[1] == null) continue;
            Long teamId = r[0] instanceof Number n ? n.longValue() : null;
            if (teamId == null) continue;
            String name = r[1].toString();
            if (name.isBlank()) continue;
            int count = r[2] instanceof Number n ? n.intValue() : 0;
            acc.merge(type + "#" + teamId + "#" + name, count, Integer::sum);
        }
    }

    /** Bir grubu (registry id) yeniden adlandırır — YALNIZ o türün monitör tablosu + o türün alarm geçmişi, tek
     *  transaction. Yetki: caller grubun takımını yönetebilmeli/üyesi olmalı → SecurityException(403); aynı (takım,tür)'de
     *  çakışma → IllegalStateException(409); boş ad → IllegalArgumentException(400). Döndürür: etkilenen kayıt sayısı. */
    @Transactional
    public int rename(Long groupId, String newNameRaw, HttpSession session) {
        MonitoringGroup g = groupRepo.findById(groupId)
                .orElseThrow(() -> new NoSuchElementException("Grup bulunamadı: " + groupId));
        Long teamId = g.getTeamId();
        String type = g.getType();
        if (!SessionScope.canManage(session, teamId) && !ownTeam(session, teamId))
            throw new SecurityException("Bu grubu yeniden adlandırma yetkiniz yok");
        String newName = newNameRaw == null ? "" : newNameRaw.trim();
        if (newName.isEmpty()) throw new IllegalArgumentException("Yeni grup adı boş olamaz.");
        String oldName = g.getName();
        if (newName.equals(oldName)) return 0;
        String lower = newName.toLowerCase(Locale.ROOT);
        Optional<MonitoringGroup> clash = groupRepo.findByTeamIdAndTypeAndNameLower(teamId, type, lower);
        if (clash.isPresent() && !clash.get().getId().equals(groupId))
            throw new IllegalStateException("Bu takım + izleme türünde '" + newName + "' adlı grup zaten var.");

        g.setName(newName);
        g.setNameLower(lower);
        groupRepo.save(g);
        int affected = cascadeRename(type, teamId, oldName, newName);
        Set<String> alertTypes = TYPE_ALERTS.getOrDefault(type, Set.of());
        if (!alertTypes.isEmpty()) alertEventRepo.renameGroupForTeamAndTypes(teamId, oldName, newName, alertTypes);
        log.info("Monitoring group renamed: team={} type={} id={} '{}' → '{}' (records={})", teamId, type, groupId, oldName, newName, affected);
        return affected;
    }

    /** YALNIZ verilen türün monitör/envanter tablosunda grup adını değiştirir. */
    private int cascadeRename(String type, Long teamId, String oldName, String newName) {
        return switch (type) {
            case "cert"    -> certRepo.renameGroupForTeam(teamId, oldName, newName);
            case "http"    -> httpRepo.renameGroupForTeam(teamId, oldName, newName);
            case "ping"    -> pingRepo.renameGroupForTeam(teamId, oldName, newName);
            case "port"    -> portRepo.renameGroupForTeam(teamId, oldName, newName);
            case "dns"     -> dnsRepo.renameGroupForTeam(teamId, oldName, newName);
            case "keyword" -> keywordRepo.renameGroupForTeam(teamId, oldName, newName);
            case "domain"  -> domainRepo.renameGroupForTeam(teamId, oldName, newName);
            default        -> 0;
        };
    }

    private static boolean ownTeam(HttpSession session, Long teamId) {
        Object raw = session.getAttribute("teamId");
        Long own = raw instanceof Number n ? n.longValue() : (raw != null ? Long.valueOf(raw.toString()) : null);
        return own != null && own.equals(teamId);
    }
}
