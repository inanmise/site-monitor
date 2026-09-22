package com.sitemonitor.service;

import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.TeamRepository;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Predicate;

/**
 * Envanter toplu içe aktarma (2026-09-12, envanter #6) — CSV istemcide ayrıştırılır, sunucu satır
 * listesi alır; önce KURU KOŞU (ne olacak: yeni / güncellenecek / atlanacak + sebep), sonra aynı
 * gövdeyle işleme.
 *
 * <p>Kurallar:
 * <ul>
 *   <li>Eşleşme {@code domain} ile (benzersiz); URL yapıştırılmışsa host'a indirgenir.</li>
 *   <li>Yeni kayıt için takım ZORUNLU (id ya da ad); mevcut kayıtta yalnız DOLU gelen alanlar
 *       yazılır — boş hücre "dokunma" demektir (toplu sorumlu atamayla aynı sözleşme).</li>
 *   <li>Kapsam SATIR BAŞINA: hedef takım (yeni kayıtta gelen, mevcutta kayıttaki ve varsa yeni)
 *       çağıranın yönetim kapsamında değilse satır {@code skip:scope}; batch durmaz.</li>
 *   <li>Yumuşak silinmiş kayıt: {@code skip:deleted} — sessizce diriltilmez, kullanıcı "Geri yükle" der.</li>
 *   <li>Aynı domain dosyada iki kez: ikinci {@code skip:duplicate_row}.</li>
 * </ul>
 * Denetim: tek {@code DOMAIN_IMPORT} kaydı (sayımlar + domainler); geçmiş: satır başına
 * CREATE/UPDATE (MonitorHistoryService). Yeni kayıtlar için anında tekil kontrol tetiklenir.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryImportService {

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    /** Kabul edilen sütun anahtarları (snake_case; istemci yerelleştirilmiş başlıkları bunlara çevirir). */
    public static final List<String> COLUMNS = List.of(
            "domain", "port", "team", "ug_team", "tier", "active", "group", "description", "owner", "tags",
            "purchased_by", "svc_mgmt_contact", "app_dev_contact", "iis_admin_contact", "waf_admin_contact",
            "netscaler", "waf_enabled", "openshift", "ssl_pinning", "internal_cert", "jks_keystore",
            "ev_certificate", "external_vendor", "in_use", "use_proxy", "action_required", "server_update",
            "transferred_to_sy", "change_description", "platform", "platform_detail");

    private static final String[] HISTORY_FIELDS = {
        "domain", "port", "active", "tier", "description", "owner", "tags", "externalVendor",
        "actionRequired", "openshift", "sslPinning", "internalCert", "jksKeystore", "serverUpdate",
        "netscaler", "wafEnabled", "inUse", "evCertificate", "transferredToSy", "useProxy",
        "purchasedBy", "platform", "platformDetail", "changeDescription", "teamId", "ugTeamId", "groupName",
        "svcMgmtContact", "appDevContact", "iisAdminContact", "wafAdminContact"
    };

    private final CertificateInventoryRepository inventoryRepo;
    private final TeamRepository teamRepo;
    private final MonitorHistoryService monitorHistory;
    private final MonitoringGroupService monitoringGroupService;
    /** Platform kataloğu (2026-09-22) — testte mock verilmezse null; o zaman değer olduğu gibi (üst-harf) alınır. */
    private final PlatformService platformService;
    private final SchedulerService schedulerService;   // döngü yok: SchedulerService bu servisi bilmez

    /** Satır sonucu: {@code action} = create | update | skip | error; {@code reason} makine kodu. */
    public record RowResult(int line, String domain, String action, String reason, List<String> changes) {}

    public record Result(boolean dryRun, int created, int updated, int skipped, int errors, List<RowResult> rows) {}

    /**
     * @param rows      istemciden gelen satırlar (anahtarlar {@link #COLUMNS})
     * @param dryRun    true → hiçbir şey yazılmaz, yalnız plan döner
     * @param canManage takım kapsam yüklemi (global admin: her şey)
     * @param actor     denetim/geçmiş aktörü
     * @param session   MonitorHistoryService damgaları için (null olabilir — test)
     */
    /**
     * KURU KOŞU — bilinçli olarak işlem (transaction) DIŞINDA: open-in-view kapalı olduğundan
     * {@code findByDomain} kopuk (detached) varlık döner ve {@link #apply} üzerinde yapılan deneme
     * değişiklikleri hiçbir zaman flush edilmez. Aynı gövde {@code @Transactional} olsaydı kirli-kontrol
     * "kuru" koşuyu sessizce yazardı.
     */
    public Result plan(List<Map<String, Object>> rows, Predicate<Long> canManage, String actor, HttpSession session) {
        return execute(rows, true, canManage, actor, session);
    }

    @Transactional
    public Result commit(List<Map<String, Object>> rows, Predicate<Long> canManage, String actor, HttpSession session) {
        return execute(rows, false, canManage, actor, session);
    }

    private Result execute(List<Map<String, Object>> rows, boolean dryRun, Predicate<Long> canManage,
                           String actor, HttpSession session) {
        Map<String, Long> teamByName = new HashMap<>();
        Set<Long> teamIds = new HashSet<>();
        for (Team tm : teamRepo.findAll()) {
            if (tm.getId() == null) continue;
            teamIds.add(tm.getId());
            if (tm.getName() != null) teamByName.put(tm.getName().trim().toLowerCase(Locale.ROOT), tm.getId());
        }
        Set<String> seen = new HashSet<>();
        List<RowResult> out = new ArrayList<>();
        List<String> createdDomains = new ArrayList<>(), updatedDomains = new ArrayList<>();
        int created = 0, updated = 0, skipped = 0, errors = 0;
        String now = ISO.format(Instant.now());

        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> r = rows.get(i) == null ? Map.of() : rows.get(i);
            int line = i + 2;   // başlık satırı 1
            String domain;
            try { domain = DomainNames.validate(str(r.get("domain"))); }
            catch (IllegalArgumentException e) { out.add(new RowResult(line, str(r.get("domain")), "error", "invalid_domain", List.of())); errors++; continue; }
            if (!seen.add(domain)) { out.add(new RowResult(line, domain, "skip", "duplicate_row", List.of())); skipped++; continue; }

            Long teamId = resolveTeam(r.get("team"), teamByName, teamIds);
            if (r.containsKey("team") && !isBlank(r.get("team")) && teamId == null) {
                out.add(new RowResult(line, domain, "error", "unknown_team", List.of())); errors++; continue;
            }
            Long ugTeamId = resolveTeam(r.get("ug_team"), teamByName, teamIds);
            Integer port = intOrNull(r.get("port"));
            if (port != null && (port < 1 || port > 65535)) { out.add(new RowResult(line, domain, "error", "invalid_port", List.of())); errors++; continue; }
            Integer tier = intOrNull(r.get("tier"));
            if (tier != null && (tier < 1 || tier > 4)) { out.add(new RowResult(line, domain, "error", "invalid_tier", List.of())); errors++; continue; }

            Optional<CertificateInventory> existingOpt = inventoryRepo.findByDomain(domain);
            if (existingOpt.isPresent()) {
                CertificateInventory ex = existingOpt.get();
                if (ex.getDeletedAt() != null) { out.add(new RowResult(line, domain, "skip", "deleted", List.of())); skipped++; continue; }
                if (!canManage.test(ex.getTeamId()) || (teamId != null && !canManage.test(teamId))) {
                    out.add(new RowResult(line, domain, "skip", "scope", List.of())); skipped++; continue;
                }
                Map<String, Object> before = AuditDiff.snapshot(ex, HISTORY_FIELDS);
                List<String> changes = apply(ex, r, teamId, ugTeamId, port, tier, actor, false);
                if (changes.isEmpty()) { out.add(new RowResult(line, domain, "skip", "no_change", List.of())); skipped++; continue; }
                if (!dryRun) {
                    ex.setUpdatedAt(now);
                    inventoryRepo.save(ex);
                    monitorHistory.record(MonitorHistoryService.INVENTORY, ex.getId(), ex.getDomain(), ex.getTeamId(),
                            MonitorHistoryService.UPDATE, before, AuditDiff.snapshot(ex, HISTORY_FIELDS), "import", session);
                    updatedDomains.add(domain);
                }
                out.add(new RowResult(line, domain, "update", null, changes)); updated++;
            } else {
                if (teamId == null) { out.add(new RowResult(line, domain, "error", "team_required", List.of())); errors++; continue; }
                if (!canManage.test(teamId)) { out.add(new RowResult(line, domain, "skip", "scope", List.of())); skipped++; continue; }
                CertificateInventory it = new CertificateInventory();
                it.setDomain(domain);
                it.setPort(port != null ? port : 443);
                it.setActive(true);
                it.setTeamId(teamId);
                List<String> changes = apply(it, r, teamId, ugTeamId, port, tier, actor, true);
                if (!dryRun) {
                    it.setCreatedAt(now); it.setUpdatedAt(now);
                    if (session != null) monitorHistory.stampCreated(it, session);
                    CertificateInventory saved = inventoryRepo.save(it);
                    monitorHistory.record(MonitorHistoryService.INVENTORY, saved.getId(), saved.getDomain(), saved.getTeamId(),
                            MonitorHistoryService.CREATE, null, AuditDiff.snapshot(saved, HISTORY_FIELDS), "import", session);
                    try { schedulerService.checkSingleDomainAsync(saved.getDomain(), saved.getPort(), Boolean.TRUE.equals(saved.getUseProxy()), saved.getTlsMode()); }
                    catch (Exception e) { log.debug("import: anlık kontrol tetiklenemedi {}: {}", domain, e.toString()); }
                    createdDomains.add(domain);
                }
                out.add(new RowResult(line, domain, "create", null, changes)); created++;
            }
        }
        return new Result(dryRun, created, updated, skipped, errors, out);
    }

    /** Dolu gelen alanları uygular; değişen alan adlarını döner. {@code isNew}: port/team her zaman yazılır. */
    private List<String> apply(CertificateInventory it, Map<String, Object> r, Long teamId, Long ugTeamId,
                               Integer port, Integer tier, String actor, boolean isNew) {
        List<String> ch = new ArrayList<>();
        if (port != null && !isNew && !Objects.equals(it.getPort(), port)) { it.setPort(port); ch.add("port"); }
        if (teamId != null && !isNew && !Objects.equals(it.getTeamId(), teamId)) { it.setTeamId(teamId); ch.add("team"); }
        if (ugTeamId != null && !Objects.equals(it.getUgTeamId(), ugTeamId)) { it.setUgTeamId(ugTeamId); ch.add("ug_team"); }
        if (tier != null && !Objects.equals(it.getTier(), tier)) { it.setTier(tier); ch.add("tier"); }
        Boolean active = boolOrNull(r.get("active"));
        if (active != null && !Objects.equals(it.getActive(), active)) { it.setActive(active); ch.add("active"); }
        String group = str(r.get("group"));
        if (!group.isBlank()) {
            String g = monitoringGroupService.getOrCreate(it.getTeamId(), "cert", group, actor);
            if (!Objects.equals(it.getGroupName(), g)) { it.setGroupName(g); ch.add("group"); }
        }
        text(r, "description", it.getDescription(), v -> it.setDescription(v), ch);
        text(r, "owner", it.getOwner(), v -> it.setOwner(v), ch);
        text(r, "tags", it.getTags(), v -> it.setTags(v), ch);
        text(r, "purchased_by", it.getPurchasedBy(), v -> it.setPurchasedBy(v), ch);
        text(r, "platform", it.getPlatform(), v -> it.setPlatform(platformService != null ? platformService.normalize(v) : (v == null || v.isBlank() ? null : v.trim().toUpperCase(java.util.Locale.ROOT))), ch);   // IIS/OPENSHIFT/… (2026-09-22)
        text(r, "platform_detail", it.getPlatformDetail(), v -> it.setPlatformDetail(v), ch);
        text(r, "change_description", it.getChangeDescription(), v -> it.setChangeDescription(v), ch);
        text(r, "svc_mgmt_contact", it.getSvcMgmtContact(), v -> it.setSvcMgmtContact(v), ch);
        text(r, "app_dev_contact", it.getAppDevContact(), v -> it.setAppDevContact(v), ch);
        text(r, "iis_admin_contact", it.getIisAdminContact(), v -> it.setIisAdminContact(v), ch);
        text(r, "waf_admin_contact", it.getWafAdminContact(), v -> it.setWafAdminContact(v), ch);
        flag(r, "netscaler", it.getNetscaler(), v -> it.setNetscaler(v), ch);
        flag(r, "waf_enabled", it.getWafEnabled(), v -> it.setWafEnabled(v), ch);
        flag(r, "openshift", it.getOpenshift(), v -> it.setOpenshift(v), ch);
        flag(r, "ssl_pinning", it.getSslPinning(), v -> it.setSslPinning(v), ch);
        flag(r, "internal_cert", it.getInternalCert(), v -> it.setInternalCert(v), ch);
        flag(r, "jks_keystore", it.getJksKeystore(), v -> it.setJksKeystore(v), ch);
        flag(r, "ev_certificate", it.getEvCertificate(), v -> it.setEvCertificate(v), ch);
        flag(r, "external_vendor", it.getExternalVendor(), v -> it.setExternalVendor(v), ch);
        flag(r, "in_use", it.getInUse(), v -> it.setInUse(v), ch);
        flag(r, "use_proxy", it.getUseProxy(), v -> it.setUseProxy(v), ch);
        flag(r, "action_required", it.getActionRequired(), v -> it.setActionRequired(v), ch);
        flag(r, "server_update", it.getServerUpdate(), v -> it.setServerUpdate(v), ch);
        flag(r, "transferred_to_sy", it.getTransferredToSy(), v -> it.setTransferredToSy(v), ch);
        // Yeni satır: takım (zorunlu) ve varsayılan-dışı port da yazılır — önizleme yazılacak her alanı saysın (ISSUE-004)
        if (isNew) { if (port != null && port != 443) ch.add(0, "port"); ch.add(0, "team"); ch.add(0, "domain"); }
        return ch;
    }

    private static void text(Map<String, Object> r, String key, String cur, java.util.function.Consumer<String> set, List<String> ch) {
        String v = str(r.get(key)).trim();
        if (v.isEmpty()) return;
        if (v.length() > 2000) v = v.substring(0, 2000);
        if (!Objects.equals(cur, v)) { set.accept(v); ch.add(key); }
    }

    private static void flag(Map<String, Object> r, String key, Boolean cur, java.util.function.Consumer<Boolean> set, List<String> ch) {
        Boolean v = boolOrNull(r.get(key));
        if (v == null) return;
        if (!Objects.equals(Boolean.TRUE.equals(cur), v)) { set.accept(v); ch.add(key); }
    }

    private static Long resolveTeam(Object raw, Map<String, Long> byName, Set<Long> ids) {
        String v = str(raw).trim();
        if (v.isEmpty()) return null;
        if (v.matches("\\d+")) { long id = Long.parseLong(v); return ids.contains(id) ? id : null; }
        return byName.get(v.toLowerCase(Locale.ROOT));
    }

    static Integer intOrNull(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.intValue();
        String v = o.toString().trim();
        if (v.isEmpty()) return null;
        v = v.replaceFirst("^[tT]", "");   // "T1" → 1
        try { return Integer.parseInt(v); } catch (NumberFormatException e) { return null; }
    }

    /** evet/yes/true/1/x/✓ → true; hayır/no/false/0 → false; boş/bilinmeyen → null (dokunma). */
    static Boolean boolOrNull(Object o) {
        if (o == null) return null;
        if (o instanceof Boolean b) return b;
        String v = o.toString().trim().toLowerCase(Locale.ROOT);
        if (v.isEmpty()) return null;
        return switch (v) {
            case "1", "true", "yes", "y", "evet", "e", "x", "✓", "var", "on" -> Boolean.TRUE;
            case "0", "false", "no", "n", "hayır", "hayir", "h", "-", "yok", "off" -> Boolean.FALSE;
            default -> null;
        };
    }

    private static boolean isBlank(Object o) { return o == null || o.toString().isBlank(); }
    private static String str(Object o) { return o == null ? "" : o.toString(); }
}
