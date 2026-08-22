package com.sitemonitor.service;

import com.sitemonitor.controller.SessionScope;
import com.sitemonitor.model.MonitoringGroup;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.DnsMonitorRepository;
import com.sitemonitor.repository.DomainMonitorRepository;
import com.sitemonitor.repository.HttpMonitorRepository;
import com.sitemonitor.repository.KeywordMonitorRepository;
import com.sitemonitor.repository.MonitoringGroupRepository;
import com.sitemonitor.repository.PageMonitorRepository;
import com.sitemonitor.repository.PingMonitorRepository;
import com.sitemonitor.repository.PortMonitorRepository;
import com.sitemonitor.repository.ScriptedMonitorRepository;
import com.sitemonitor.repository.TeamRepository;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

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
 * Grup-taşıyan türler: cert (envanter), http, ping, port, dns, keyword, domain, page, scripted.
 * YENİ TÜR EKLERKEN: typeOf + TYPE_ALERTS + sayım (listForScope/groupCountRows) + cascadeRename + MonitoringGroupBackfill
 * (seed & renameRows) + frontend TYPE_LABEL — hepsi bağlanmalı; biri eksikse grup sessizce kopar.
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
            "domain",  Set.of("DOMAINMON_EXPIRY", "DOMAINMON_UNKNOWN", "DOMAINMON_STATUS", "DOMAINMON_CHANGED"),
            // İki tür SONRADAN grup taşımaya başladı (typeOf zaten "page"/"scripted" üretiyor) ama bu haritaya
            // hiç girmemişti: grup yeniden adlandırıldığında o türün alarm geçmişindeki group_name ESKİ kalıyordu.
            "page",     Set.of("PAGE_DOWN", "PAGE_INTEGRITY"),
            "scripted", Set.of("SCRIPTED_FAIL", "SCRIPTED_SLOW"));

    private final MonitoringGroupRepository groupRepo;
    private final CertificateInventoryRepository certRepo;
    private final HttpMonitorRepository httpRepo;
    private final PingMonitorRepository pingRepo;
    private final PortMonitorRepository portRepo;
    private final DnsMonitorRepository dnsRepo;
    private final KeywordMonitorRepository keywordRepo;
    private final DomainMonitorRepository domainRepo;
    private final PageMonitorRepository pageRepo;
    private final ScriptedMonitorRepository scriptedRepo;
    private final AlertEventRepository alertEventRepo;
    private final TeamRepository teamRepo;
    private final AuditService auditService;   // rename → audit_log (MONITOR_GROUP_RENAME, eski→yeni)
    private final MonitorHistoryService monitorHistory;   // rename → ürün-görünür geçmiş (grup başına tek satır)
    private final PlatformTransactionManager txManager;   // getOrCreate insert'i REQUIRES_NEW ile izole eder

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
        // Insert REQUIRES_NEW'da: yarış kaybeden tarafta DIV yalnız iç transaction'ı bozar; dış transaction
        // rollback-only olmaz, refetch temiz döner (aksi halde commit'te UnexpectedRollbackException → 500).
        TransactionTemplate reqNew = new TransactionTemplate(txManager);
        reqNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        try {
            return reqNew.execute(st -> groupRepo.saveAndFlush(g).getName());
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
            case "HttpMonitor"     -> "http";
            case "PingMonitor"     -> "ping";
            case "PortMonitor"     -> "port";
            case "DnsMonitor"      -> "dns";
            case "KeywordMonitor"  -> "keyword";
            case "DomainMonitor"   -> "domain";
            // 2026-08: iki tür eksikti → grupları "" türüyle kaydediyordu ve form grup önerileri
            // (listGroups(teamId, type)) boş dönüyordu. Eski ""-türlü satırlar applySchemaPatches'te backfill edilir.
            case "PageMonitor"     -> "page";
            case "ScriptedMonitor" -> "scripted";
            default                -> "";
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

        Map<String, Integer> counts = new HashMap<>();   // "type#teamId#nameLower" → count
        if (typeFilter != null) {
            countInto(counts, typeFilter, groupCountRows(typeFilter));   // sıcak yol (form autocomplete): yalnız o türün tablosu
        } else {
            countInto(counts, "cert",    certRepo.groupCountsByTeam());
            countInto(counts, "http",    httpRepo.groupCountsByTeam());
            countInto(counts, "ping",    pingRepo.groupCountsByTeam());
            countInto(counts, "port",    portRepo.groupCountsByTeam());
            countInto(counts, "dns",     dnsRepo.groupCountsByTeam());
            countInto(counts, "keyword", keywordRepo.groupCountsByTeam());
            countInto(counts, "domain",  domainRepo.groupCountsByTeam());
            // Sorgu ZATEN yaziliydi (ScriptedMonitorRepository.groupCountsByTeam) ama hic
            // cagrilmiyordu: Monitor Gruplari ekrani sentetik gruplari 0 gosteriyordu.
            countInto(counts, "scripted", scriptedRepo.groupCountsByTeam());
            countInto(counts, "page",     pageRepo.groupCountsByTeam());   // aynı eksik "page"de de vardı
        }

        Map<Long, String> teamNames = new HashMap<>();
        if (teamFilter != null) {
            teamRepo.findById(teamFilter).ifPresent(tm -> teamNames.put(tm.getId(), tm.getName()));
        } else {
            for (Team tm : teamRepo.findAll()) teamNames.put(tm.getId(), tm.getName());
        }

        List<GroupInfo> out = new ArrayList<>();
        for (MonitoringGroup g : groups) {
            int c = counts.getOrDefault(g.getType() + "#" + g.getTeamId() + "#" + g.getNameLower(), 0);
            out.add(new GroupInfo(g.getId(), g.getTeamId(), teamNames.get(g.getTeamId()), g.getType(), g.getName(), c));
        }
        return out;
    }

    private List<Object[]> groupCountRows(String type) {
        return switch (type) {
            case "cert"    -> certRepo.groupCountsByTeam();
            case "http"    -> httpRepo.groupCountsByTeam();
            case "ping"    -> pingRepo.groupCountsByTeam();
            case "port"    -> portRepo.groupCountsByTeam();
            case "dns"     -> dnsRepo.groupCountsByTeam();
            case "keyword" -> keywordRepo.groupCountsByTeam();
            case "domain"  -> domainRepo.groupCountsByTeam();
            case "scripted"-> scriptedRepo.groupCountsByTeam();
            case "page"    -> pageRepo.groupCountsByTeam();
            default        -> List.of();
        };
    }

    /** Monitör tablolarındaki farklı casing'ler tek grupta toplansın diye nameLower ile anahtarlar (registry case-insensitive). */
    private static void countInto(Map<String, Integer> acc, String type, List<Object[]> rows) {
        for (Object[] r : rows) {
            if (r[0] == null || r[1] == null) continue;
            Long teamId = r[0] instanceof Number n ? n.longValue() : null;
            if (teamId == null) continue;
            String name = r[1].toString();
            if (name.isBlank()) continue;
            int count = r[2] instanceof Number n ? n.intValue() : 0;
            acc.merge(type + "#" + teamId + "#" + name.toLowerCase(Locale.ROOT), count, Integer::sum);
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
        try {
            groupRepo.saveAndFlush(g);   // unique index'e eşzamanlı rename çarparsa 500 değil 409 dönsün
        } catch (DataIntegrityViolationException dup) {
            throw new IllegalStateException("Bu takım + izleme türünde '" + newName + "' adlı grup zaten var.");
        }
        int affected = cascadeRename(type, teamId, oldName, newName);
        Set<String> alertTypes = TYPE_ALERTS.getOrDefault(type, Set.of());
        if (!alertTypes.isEmpty()) alertEventRepo.renameGroupForTeamAndTypes(teamId, oldName, newName, alertTypes);
        log.info("Monitoring group renamed: team={} type={} id={} '{}' → '{}' (records={})", teamId, type, groupId, oldName, newName, affected);
        // Denetim izi: kim, hangi grubu, eski→yeni (audit_log). IP/UA yok (servis katmanı, request bağımsız).
        auditService.recordAction(
                "MONITOR_GROUP_RENAME",
                strAttr(session, "username"), longAttr(session, "userId"), longAttr(session, "teamId"),
                strAttr(session, "systemRole"), "MONITOR_GROUP", String.valueOf(groupId),
                auditDetail(teamId, type, oldName, newName, affected),
                null, null, session.getId());
        // Ürün geçmişi: grup başına TEK satır (etkilenen her monitör için değil) — grup adı bir
        // GRUBUN özelliğidir; monitör başına satır yazmak zaman çizelgesini gürültüye boğardı.
        monitorHistory.record(MonitorHistoryService.GROUP, groupId, newName, teamId,
                MonitorHistoryService.GROUP_RENAME,
                java.util.Map.of("name", oldName), java.util.Map.of("name", newName, "type", type,
                        "affectedMonitors", affected),
                null, session);
        return affected;
    }

    private static String strAttr(HttpSession session, String key) {
        Object raw = session.getAttribute(key);
        return raw != null ? raw.toString() : null;
    }
    private static Long longAttr(HttpSession session, String key) {
        // Tek ternary'de long/Long karışımı sonucu long'a unbox'lıyordu → attribute yokken NPE (2026-08).
        Object raw = session.getAttribute(key);
        if (raw instanceof Number n) return n.longValue();
        return raw != null ? Long.valueOf(raw.toString()) : null;
    }
    private static String auditDetail(Long teamId, String type, String oldName, String newName, int affected) {
        return "{\"team_id\":" + teamId + ",\"type\":\"" + esc(type) + "\",\"old\":\"" + esc(oldName)
                + "\",\"new\":\"" + esc(newName) + "\",\"affected\":" + affected + "}";
    }
    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
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
            // page/scripted burada YOKTU → registry adı değişiyor, monitör satırları ESKİ grup adında kalıyordu:
            // monitörler sessizce gruptan düşüyor (sayaç 0, form önerisinde eski ad yeniden beliriyor). İki repoda
            // da sorgu zaten yazılıydı, yalnız çağrılmıyordu.
            case "page"    -> pageRepo.renameGroupForTeam(teamId, oldName, newName);
            case "scripted"-> scriptedRepo.renameGroupForTeam(teamId, oldName, newName);
            default        -> 0;
        };
    }

    private static boolean ownTeam(HttpSession session, Long teamId) {
        Object raw = session.getAttribute("teamId");
        Long own = raw instanceof Number n ? n.longValue() : (raw != null ? Long.valueOf(raw.toString()) : null);
        return own != null && own.equals(teamId);
    }
}
