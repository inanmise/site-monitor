package com.sitemonitor.controller;

import com.sitemonitor.model.AlertComment;
import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.repository.*;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.PermissionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Incidents Overview — tüm monitörlerin makine-üretimi olaylarını (AlertEvent) tek tabloda toplar.
 * Olay = teyitli hatada AÇILAN / recovery'de KAPANAN alarm; yaşam döngüsü EscalationService+MonitoringOutageService'te.
 * Bu controller yalnız SUNUM + yorum dizisi + (admin) silme sağlar; alarm üretimi/çözümü buraya AİT DEĞİLDİR.
 * Yetki: görüntüleme alerts.read, yorum alerts.actions, incident silme yalnız global ADMIN. Takım-kapsamı (IDOR) uygulanır.
 *
 * <p><b>Org geneli salt okunur görünürlük (2026-09-28, kullanıcı kararı):</b> "Olaylar sayfası tüm kullanıcılara açık;
 * bir kullanıcı başka takımlara açılan olayları görebilir ama müdahale edemez." Yalnız OKUMA genişler — liste
 * ({@code scope=mine|others|all}), tekil olay ve yorum dizisi. Ayar {@value #VISIBLE_TO_ALL_KEY} (varsayılan AÇIK,
 * yalnız global yönetici değiştirir) kapalıyken her şey bugünkü gibi takım kapsamlıdır. YAZMA kapıları
 * ({@link #requireIncidentScope}, {@link #canManageIncident}, global-admin silme) bu ayarı HİÇ okumaz; alarm eylemleri
 * (onay/çözüm/yeniden bildirim) AdminController'da kendi takım kapısıyla kalır. Bildirim alıcıları, teslimat günlüğü
 * ve 7/24 arama kayıtları ayrı uçlardadır ve takım kapsamlı kalır (başka ekibin olayında sunulmaz); listede yalnız
 * KENDİ satır özet sayıları + mevcut sahibi taşır ({@link #ownerFacts}).
 */
@Slf4j
@RestController
@RequestMapping("/api/monitoring/incidents")
@RequiredArgsConstructor
public class IncidentsController {

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final int MAX_COMMENT = 5000;

    private final AlertEventRepository alertEventRepo;
    private final AlertCommentRepository commentRepo;
    private final HttpMonitorRepository httpMonitorRepo;
    private final PortMonitorRepository portMonitorRepo;
    private final KeywordMonitorRepository keywordMonitorRepo;
    private final PingMonitorRepository pingMonitorRepo;
    private final DnsMonitorRepository dnsMonitorRepo;
    private final DomainMonitorRepository domainMonitorRepo;
    private final PageMonitorRepository pageMonitorRepo;
    private final ScriptedMonitorRepository scriptedMonitorRepo;
    private final PageSpeedMonitorRepository pageSpeedMonitorRepo;
    private final CertificateInventoryRepository inventoryRepo;
    private final com.sitemonitor.repository.TeamRepository teamRepo;   // takım sütunu (2026-09-18)
    private final PermissionService permissionService;
    private final AuditService auditService;
    private final com.sitemonitor.service.AppSettingsService appSettings;   // org geneli okuma anahtarı (2026-09-28)

    /**
     * Sahiplen/Çöz penceresinin bağlam kartı (2026-09-28): e-posta/push teslim sayıları + 7/24 arama sayısı — Alarm
     * Geçmişi'nin ({@code AdminController.listAlerts}) AYNI toplu sorguları, yalnız KENDİ satırlar için
     * ({@link #deliveryFor}). İsteğe bağlı ({@code AdminController.nocCallLog} deseni): dilimli test bağlamında yokken
     * sayı alanları yazılmaz, liste yine döner.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private NotificationLogRepository notificationLogRepo;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private UserPushDeliveryRepository userPushDeliveryRepo;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.sitemonitor.service.noc.NocCallLogService nocCallLog;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Org geneli salt okunur Olaylar anahtarı — {@code AppSettingsCatalog} + i18n etiket/yardım metinleriyle aynı. */
    public static final String VISIBLE_TO_ALL_KEY = "site.monitor.incidents.visible-to-all";
    /** Liste {@code scope} değerleri — bilinmeyen/boş değer {@link #SCOPE_MINE} sayılır. */
    public static final String SCOPE_MINE = "mine";
    public static final String SCOPE_OTHERS = "others";
    public static final String SCOPE_ALL = "all";
    /** Kapsamsız sorguda IN listesi boş olamaz — scoped=false kısa devre yaptığı için değeri önemsiz. */
    private static final List<Long> NO_SCOPE = List.of(-1L);

    // ── Liste ──────────────────────────────────────────────────────────────────
    /**
     * Olay listesi. {@code scope}: {@code mine} (varsayılan — BUGÜNKÜ görünüm: görüş kapsamındaki takımların olayları;
     * global görüntüleyici için tümü) | {@code others} (kapsam dışı olaylar, salt okunur) | {@code all}. {@code others}
     * ve {@code all} yalnız ayar açıkken ve çağıran global görüntüleyici DEĞİLKEN uygulanır; aksi halde sessizce
     * {@code mine}'a düşer (yanıttaki {@code scope} gerçekte uygulananı söyler). Satır başına {@code can_manage}
     * (olay çağıranın kendi kapsamında mı), {@code can_act} (+ {@code alerts.actions}) ve {@code can_delete} (global
     * yönetici). {@code scope_counts} yalnız anahtar anlamlıyken döner ve sayfa sorgusuna TEK sayım sorgusu ekler.
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(required = false) String status,      // all | ongoing | resolved
            @RequestParam(required = false) String rootCause,   // alertType pill
            @RequestParam(required = false) String q,           // monitör adı/host araması (LIKE domain)
            @RequestParam(required = false) String since,
            @RequestParam(required = false) String until,
            @RequestParam(defaultValue = "default") String sort,
            @RequestParam(defaultValue = "desc") String dir,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = SCOPE_MINE) String scope,   // mine | others | all (2026-09-28)
            HttpSession session) {
        permissionService.require(session, "alerts.read", "view");
        int sz = Math.max(1, Math.min(size, 200));
        Boolean resolved = "ongoing".equalsIgnoreCase(status) ? Boolean.FALSE
                : "resolved".equalsIgnoreCase(status) ? Boolean.TRUE : null;
        String type = (rootCause != null && !rootCause.isBlank()) ? rootCause.trim() : null;
        String qEff = (q != null && !q.isBlank()) ? q.trim() : null;

        // Takım kapsamı (IDOR): "kendi" kapsam = görüş kapsamı (global görüntüleyicide null = tümü). Org geneli okuma
        // ayrı bir karar: yalnız anahtar açıkken others/all uygulanır.
        List<Long> own = ownScope(session);
        boolean orgWide = orgWideReader(session);
        String eff = orgWide ? normalizeScope(scope) : SCOPE_MINE;
        ScopeQuery sq = scopeQuery(eff, own);

        Page<AlertEvent> result = sq == null
                ? Page.empty(PageRequest.of(0, sz))   // kapsamsız kullanıcının "Takımımın olayları": hiçbir olay
                : alertEventRepo.findIncidents(resolved, since, until, type, qEff, sq.scoped(), sq.outside(), sq.ids(),
                        PageRequest.of(Math.max(0, page), sz, sortFor(sort, dir)));
        List<AlertEvent> events = result.getContent();

        Map<Long, Map<String, Object>> monitors = resolveMonitors(events);
        Map<Long, Long> commentCounts = commentCounts(events);
        Map<String, List<com.sitemonitor.model.CertificateInventory>> inv = inventoryFor(events, own);
        TeamInfo teams = resolveTeams(events, inv);
        Set<Long> owned = ownedIds(events, own, inv);
        Access access = accessFor(session);
        // 7/24 operatörü (2026-10-04) başka ekibin olayını da TAM okur (teslim özeti, izleme bağlantısı, çözen kişi) —
        // eylem bayrakları (can_manage/can_act/can_delete) yine KENDİ kapsamından.
        Delivery delivery = deliveryFor(events, e -> access.noc() || owned.contains(e.getId()));
        List<Map<String, Object>> data = events.stream()
                .map(e -> toDto(e, monitors, commentCounts, teams, access.forRow(owned.contains(e.getId())), delivery)).toList();

        Map<String, Long> typeCounts = new LinkedHashMap<>();
        if (sq != null)
            for (Object[] row : alertEventRepo.countIncidentsByType(resolved, since, until, qEff, sq.scoped(), sq.outside(), sq.ids()))
                typeCounts.put(String.valueOf(row[0]), (Long) row[1]);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data",        data);
        body.put("total",       result.getTotalElements());
        body.put("page",        result.getNumber());
        body.put("size",        result.getSize());
        body.put("type_counts", typeCounts);
        body.put("scope",       eff);
        body.put("visible_to_all", visibleToAll() || nocCommenter(session));   // 7/24 operatörü: ayardan bağımsız
        if (orgWide) body.put("scope_counts",
                scopeCounts(eff, result.getTotalElements(), own, resolved, since, until, type, qEff));
        return ok(body);
    }

    /**
     * "Takımımın olayları / Diğer ekiplerin olayları / Tümü" çip sayıları — sayfa süzgeçleriyle (durum, kök neden, arama,
     * tarih) aynı. İki küme ayrık ve birleşimleri tümü olduğundan sayfanın toplamına TEK sayım eklemek yeter.
     */
    private Map<String, Long> scopeCounts(String eff, long pageTotal, List<Long> own, Boolean resolved,
                                          String since, String until, String type, String q) {
        long mine;
        long all;
        switch (eff) {
            case SCOPE_MINE -> {
                mine = pageTotal;
                all = alertEventRepo.countIncidents(resolved, since, until, type, q, false, false, NO_SCOPE);
            }
            case SCOPE_ALL -> {
                all = pageTotal;
                mine = own.isEmpty() ? 0L : alertEventRepo.countIncidents(resolved, since, until, type, q, true, false, own);
            }
            default -> {   // others
                mine = own.isEmpty() ? 0L : alertEventRepo.countIncidents(resolved, since, until, type, q, true, false, own);
                all = mine + pageTotal;
            }
        }
        Map<String, Long> m = new LinkedHashMap<>();
        m.put(SCOPE_MINE, mine);
        m.put(SCOPE_OTHERS, Math.max(0L, all - mine));
        m.put(SCOPE_ALL, all);
        return m;
    }

    /** Sorgu kapsamı: {@code null} = boş küme (görüş kapsamı boş kullanıcının "Takımımın olayları"). */
    private record ScopeQuery(boolean scoped, boolean outside, List<Long> ids) {}

    private static ScopeQuery scopeQuery(String eff, List<Long> own) {
        if (own == null || SCOPE_ALL.equals(eff)) return new ScopeQuery(false, false, NO_SCOPE);   // global görüntüleyici / tümü
        if (own.isEmpty()) return SCOPE_OTHERS.equals(eff) ? new ScopeQuery(false, false, NO_SCOPE) : null;
        return new ScopeQuery(true, SCOPE_OTHERS.equals(eff), own);
    }

    private static String normalizeScope(String scope) {
        String s = scope == null ? "" : scope.trim().toLowerCase(Locale.ROOT);
        return SCOPE_OTHERS.equals(s) || SCOPE_ALL.equals(s) ? s : SCOPE_MINE;
    }

    /**
     * Çağıranın KENDİ kapsamı: global görüntüleyici (admin/AUDIT) → {@code null} (her olay "kendi"); diğerleri görüş
     * kapsamı. Oturumda görüş kapsamı yoksa BOŞ liste — kapalı düşer (eski kod null'ı "kapsamsız sorgu" sayıyordu).
     */
    private static List<Long> ownScope(HttpSession session) {
        if (SessionScope.isGlobalViewer(session)) return null;
        List<Long> v = SessionScope.viewTeamIds(session);
        return v == null ? List.of() : v;
    }

    /** Ayar açık mı — her çağrıda canlı okunur (kaydedince yeniden başlatma gerekmez). Varsayılan AÇIK. */
    private boolean visibleToAll() {
        return appSettings.getBoolean("site.monitor.incidents.visible-to-all", true);
    }

    /**
     * Bu oturum kendi kapsamı DIŞINDAKİ olayları (salt okunur) okuyabilir mi: ayar açık ve çağıran global görüntüleyici
     * değil (o zaten her şeyi kendi kapsamında görür — anahtar ona anlamsız). {@code alerts.read} her okuma ucunda
     * ayrıca istenir. YAZMA kapılarında KULLANILMAZ.
     */
    private boolean orgWideReader(HttpSession session) {
        if (session == null || SessionScope.isGlobalViewer(session)) return false;
        // 7/24 operatörü (2026-10-04) ayardan BAĞIMSIZ tüm takımların olaylarını okur (yazma kapıları bunu okumaz).
        return visibleToAll() || nocCommenter(session);
    }

    /** İstek başına bir kez kurulan eylem hakları (satır başına oturum/izin okunmaz). */
    private record Access(boolean actions, boolean globalAdmin, boolean noc) {
        RowAccess forRow(boolean owned) {
            return new RowAccess(owned, owned && actions, owned && actions && globalAdmin,
                    owned || noc, (owned && actions) || noc);
        }
    }

    /** {@code can_manage} = olay kendi kapsamında (yazma kapılarının sorduğu kapsam); {@code can_act} = + alerts.actions;
     *  {@code can_delete} = + global yönetici (silme ucunun kuralı). Arayüz yalnız bunlara göre eylem çizer; sunucu her
     *  yazma ucunda yine kendi kapısını uygular. */
    private record RowAccess(boolean owned, boolean canAct, boolean canDelete, boolean full, boolean canComment) {}

    private Access accessFor(HttpSession session) {
        return new Access(permissionService.allows(session, "alerts.actions", "execute"), SessionScope.isGlobalAdmin(session),
                nocCommenter(session));
    }

    /**
     * 7/24 operatörü (2026-10-04, kullanıcı isteği "alarma bildirime uyarıya notlar düşebilsinler"): 7/24 izleme ekibi
     * takımının üyesi ya da {@code noc_calls.write} sahibi (eski AUDIT düzeni). OKUYABİLDİĞİ her olaya YORUM yazar ve
     * kendi yorumunu siler; başka hiçbir alarm eylemi (sahiplen/çöz/yeniden bildir/olay silme) açılmaz. Olayı tam okur
     * (teslim özeti, izleme bağlantısı). {@code nocCallLog} isteğe bağlı (dilimli test) — yoksa yalnız takım bayrağı.
     */
    private boolean nocCommenter(HttpSession session) {
        if (SessionScope.isNocOperator(session)) return true;
        return nocCallLog != null && nocCallLog.canWrite(session) && !SessionScope.isGlobalAdmin(session);
    }

    /** Sıralama: varsayılan ongoing-first + en yeni; kolon seçilirse o alan + createdAt tie-breaker. */
    private Sort sortFor(String sort, String dir) {
        Sort.Direction d = "asc".equalsIgnoreCase(dir) ? Sort.Direction.ASC : Sort.Direction.DESC;
        return switch (sort == null ? "started" : sort) {
            case "status"   -> Sort.by(d, "resolved").and(Sort.by(Sort.Direction.DESC, "createdAt"));
            case "severity" -> Sort.by(d, "alertLevel").and(Sort.by(Sort.Direction.DESC, "createdAt"));
            case "type"     -> Sort.by(d, "alertType").and(Sort.by(Sort.Direction.DESC, "createdAt"));
            case "started"  -> Sort.by(d, "createdAt");
            // varsayılan (istek dışı): ongoing-first + en yeni
            default         -> Sort.by(Sort.Direction.ASC, "resolved").and(Sort.by(Sort.Direction.DESC, "createdAt"));
        };
    }

    /** Olay → takım (2026-09-18): damgalı teamId önce; yoksa domain → envanter SY takımı (UG yedeği). Tek toplu sorgu. */
    private record TeamInfo(Map<Long, Long> teamByEvent, Map<Long, String> names) {}

    /**
     * Sayfanın ihtiyaç duyduğu envanter satırları — TEK toplu sorgu, iki soruya birden: takım sütunu (damgasız olay →
     * SY/UG) ve sahiplik (damgalı takımı kapsam dışı olan olay, envanterin SY/UG takımı üzerinden yine "kendi" olabilir —
     * {@link #incidentTeamInScope} ile aynı kural). Global görüntüleyicide sahiplik sorusu yoktur.
     */
    private Map<String, List<com.sitemonitor.model.CertificateInventory>> inventoryFor(List<AlertEvent> events, List<Long> own) {
        Set<String> need = new HashSet<>();
        for (AlertEvent e : events) {
            if (e.getDomain() == null) continue;
            boolean teamless = e.getTeamId() == null;
            boolean ownershipOpen = own != null && !own.isEmpty() && (teamless || !own.contains(e.getTeamId()));
            if (teamless || ownershipOpen) need.add(e.getDomain());
        }
        Map<String, List<com.sitemonitor.model.CertificateInventory>> byDomain = new HashMap<>();
        if (need.isEmpty()) return byDomain;
        for (com.sitemonitor.model.CertificateInventory inv : inventoryRepo.findByDomainIn(need))
            if (inv.getDomain() != null) byDomain.computeIfAbsent(inv.getDomain(), k -> new ArrayList<>()).add(inv);
        return byDomain;
    }

    private TeamInfo resolveTeams(List<AlertEvent> events, Map<String, List<com.sitemonitor.model.CertificateInventory>> inv) {
        Map<Long, Long> byEvent = new HashMap<>();
        for (AlertEvent e : events) {
            if (e.getTeamId() != null) { byEvent.put(e.getId(), e.getTeamId()); continue; }
            if (e.getDomain() == null) continue;
            for (com.sitemonitor.model.CertificateInventory i : inv.getOrDefault(e.getDomain(), List.of())) {
                Long tid = i.getTeamId() != null ? i.getTeamId() : i.getUgTeamId();
                if (tid != null) { byEvent.put(e.getId(), tid); break; }
            }
        }
        Set<Long> ids = new HashSet<>(byEvent.values());
        Map<Long, String> names = ids.isEmpty() ? Map.of() : teamRepo.findAllById(ids).stream()
                .filter(tm -> tm.getId() != null && tm.getName() != null)
                .collect(Collectors.toMap(com.sitemonitor.model.Team::getId, com.sitemonitor.model.Team::getName, (a, b) -> a));
        return new TeamInfo(byEvent, names);
    }

    /**
     * Sayfadaki hangi olaylar çağıranın KENDİ kapsamında — {@link #incidentTeamInScope}'un toplu hâli (satır başına
     * sorgu yok). {@code own == null} (global görüntüleyici) → hepsi.
     */
    private static Set<Long> ownedIds(List<AlertEvent> events, List<Long> own,
                                      Map<String, List<com.sitemonitor.model.CertificateInventory>> inv) {
        Set<Long> out = new HashSet<>();
        if (own == null) { for (AlertEvent e : events) out.add(e.getId()); return out; }
        Set<Long> scope = new HashSet<>(own);
        for (AlertEvent e : events) {
            if (e.getTeamId() != null && scope.contains(e.getTeamId())) { out.add(e.getId()); continue; }
            if (e.getDomain() == null) continue;
            for (com.sitemonitor.model.CertificateInventory i : inv.getOrDefault(e.getDomain(), List.of())) {
                if ((i.getTeamId() != null && scope.contains(i.getTeamId()))
                        || (i.getUgTeamId() != null && scope.contains(i.getUgTeamId()))) { out.add(e.getId()); break; }
            }
        }
        return out;
    }

    private Map<String, Object> toDto(AlertEvent e, Map<Long, Map<String, Object>> monitors, Map<Long, Long> counts,
                                      TeamInfo teams, RowAccess access, Delivery delivery) {
        Map<String, Object> ctx = deserialize(e.getContextJson());
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id",            e.getId());
        dto.put("status",        Boolean.TRUE.equals(e.getResolved()) ? "resolved" : "ongoing");
        Map<String, Object> monitor = monitors.get(e.getId());
        if (monitor == null) {
            // Map.of null değer kabul etmez → domain null olan olaylarda NPE olmasın diye null-güvenli varsayılan.
            monitor = new LinkedHashMap<>();
            monitor.put("name", e.getDomain());
            monitor.put("type", "cert");
            monitor.put("tab", "dashboard");
        }
        dto.put("monitor",       monitor);
        Long teamId = teams == null ? null : teams.teamByEvent().get(e.getId());
        dto.put("team_id",       teamId);
        dto.put("team_name",     teamId == null ? null : teams.names().get(teamId));
        dto.put("root_cause",    rootCause(e.getAlertType(), ctx));
        dto.put("comment_count", counts.getOrDefault(e.getId(), 0L));
        dto.put("alert_type",    e.getAlertType());
        dto.put("alert_level",   e.getAlertLevel());
        dto.put("started_at",    e.getCreatedAt());
        dto.put("resolved_at",   e.getResolvedAt());
        // "Neden kapandı?" ekrandan cevaplanabilmeli. Envanterden bir sertifika silinince
        // alarm otomatik çözülüyor ({@code inventory_delete}) ama bu ekran sebebi HİÇ
        // döndürmüyordu: kullanıcı kaydı "Çözüldü" görüyor, kimin/neyin kapattığını
        // bilmiyordu. Değer bir SİCİL ya da SİSTEM JETONU olabilir — ayrımı arayüz yapar.
        // Başka ekibin olayında çözen KİŞİ verilmez (sahiplenen gibi o ekibin iç bilgisi — regresyon taraması
        // 2026-09-28); sistem jetonu / "Sistem (…)" kalır ki "neden kapandı?" yine cevaplanabilsin.
        dto.put("resolved_by",   resolvedByFor(e.getResolvedBy(), access.full()));
        dto.put("acknowledged",  e.getAcknowledged());
        dto.put("domain",        e.getDomain());
        dto.put("message",       e.getMessage());
        // Org geneli görünürlük (2026-09-28): satırın sahipliği + eylem hakları. Başka ekibin olayında izlemenin iç
        // kimliği verilmez (o izleme sayfası takım kapsamlı — bağlantı çıkmaz sokak olurdu); bildirim alıcıları,
        // teslimat günlüğü ve arama kayıtları zaten ayrı, takım kapsamlı uçlarda.
        dto.put("can_manage",    access.owned());
        dto.put("can_act",       access.canAct());
        dto.put("can_delete",    access.canDelete());
        // Yorum yazabilir mi (2026-10-04): kendi kapsamında alerts.actions ya da 7/24 operatörü (her okunabilir olay).
        dto.put("can_comment",   access.canComment());
        if (!access.full() && monitor.containsKey("monitor_id")) {
            Map<String, Object> slim = new LinkedHashMap<>(monitor);
            slim.put("monitor_id", null);
            dto.put("monitor", slim);
        }
        if (access.full()) ownerFacts(dto, e, delivery);
        return dto;
    }

    /**
     * Sahiplen/Çöz penceresinin bağlam kartı (2026-09-28): mevcut sahip + e-posta/push teslim + 7/24 arama sayısı —
     * Alarm Geçmişi satırıyla AYNI snake_case adlar ({@code acknowledged_by/_at}, {@code email_sent_count},
     * {@code email_failed_count}, {@code noc_call_count}; push Alarm Geçmişi'nde sayfanın {@code push_summary[id]}'si,
     * burada satırın {@code push_summary}'si). YALNIZ kendi satırda: başka ekibin olayında kimin sahiplendiği, kaç
     * kişiye bildirim gittiği ve 7/24 aramaları o ekibin iç bilgisidir (teslimat günlüğü / arama kaydı uçları gibi).
     * Sayı yalnız sorgu döndüyse yazılır — sorgu düşerse alan YOK (uydurma sıfır yok; pencere o satırı çizmez).
     */
    /** Sahiplenmediği satırda yalnız sistem kapanışı görünür (system / inventory_* / "Sistem (…)"); kişi adı ya da sicil null. */
    static String resolvedByFor(String by, boolean owned) {
        if (owned || by == null) return by;
        String t = by.trim();
        return "system".equals(t) || t.startsWith("inventory_") || t.startsWith("Sistem") ? by : null;
    }

    private static void ownerFacts(Map<String, Object> dto, AlertEvent e, Delivery d) {
        dto.put("acknowledged_by", e.getAcknowledgedBy());
        dto.put("acknowledged_at", e.getAcknowledgedAt());
        if (d == null) return;
        if (d.mail() != null) {
            long[] m = d.mail().getOrDefault(e.getId(), new long[]{ 0, 0, 0 });
            dto.put("email_sent_count",   m[0]);
            dto.put("email_failed_count", m[1]);
            dto.put("webhook_sent_count", m.length > 2 ? m[2] : 0L);   // O-A3-6
        }
        if (d.push() != null && d.push().containsKey(e.getId())) dto.put("push_summary", d.push().get(e.getId()));
        if (e.getNocCallCount() != null) dto.put("noc_call_count", e.getNocCallCount());
    }

    /** Kendi satırların teslim özeti: e-posta {@code [sent, failed]} ve push {@code {sent, failed, skipped, other}}; null = sorgu yok/düştü. */
    private record Delivery(Map<Long, long[]> mail, Map<Long, Map<String, Long>> push) {}

    /**
     * Teslim + 7/24 özeti — Alarm Geçmişi'yle AYNI toplu sorgular ({@code NotificationLogRepository.countByAlertIds},
     * {@code UserPushDeliveryRepository.countByAlertEventIdInGroupByStatus}, {@code NocCallLogService.decorate}):
     * sayfa başına kaynak başına TEK sorgu (en çok 3), satır başına sorgu YOK. Yalnız KENDİ satırlar sorguya girer —
     * başka ekibin olayı sorgulanmaz bile; sayfada kendi satır yoksa hiç sorgu atılmaz ({@code null}). Özet sorgusu
     * düşerse liste yine döner (o alan yazılmaz).
     */
    private Delivery deliveryFor(List<AlertEvent> events, java.util.function.Predicate<AlertEvent> owned) {
        List<AlertEvent> mine = events.stream().filter(e -> e.getId() != null && owned.test(e)).toList();
        if (mine.isEmpty()) return null;
        List<Long> ids = mine.stream().map(AlertEvent::getId).toList();
        Map<Long, long[]> mail = null;
        if (notificationLogRepo != null) {
            try {
                Map<Long, long[]> m = new HashMap<>();
                for (Object[] row : notificationLogRepo.countByAlertIds(ids)) {
                    long sent   = row[1] == null ? 0 : ((Number) row[1]).longValue();
                    long failed = row[2] == null ? 0 : ((Number) row[2]).longValue();
                    long webhook = row.length > 3 && row[3] != null ? ((Number) row[3]).longValue() : 0;   // O-A3-6
                    m.put(((Number) row[0]).longValue(), new long[]{ sent, failed, webhook });
                }
                mail = m;
            } catch (Exception ex) {
                log.debug("Olay listesi e-posta özeti alınamadı: {}", ex.toString());
            }
        }
        Map<Long, Map<String, Long>> push = null;
        if (userPushDeliveryRepo != null) {
            try {
                Map<Long, Map<String, Long>> p = new HashMap<>();
                for (Object[] row : userPushDeliveryRepo.countByAlertEventIdInGroupByStatus(ids)) {
                    String st = String.valueOf(row[1]);
                    String key = "SENT".equals(st) ? "sent" : "FAILED".equals(st) ? "failed" : st.startsWith("SKIPPED") ? "skipped" : "other";
                    p.computeIfAbsent(((Number) row[0]).longValue(),
                                    k -> new LinkedHashMap<>(Map.of("sent", 0L, "failed", 0L, "skipped", 0L, "other", 0L)))
                            .merge(key, ((Number) row[2]).longValue(), Long::sum);
                }
                push = p;
            } catch (Exception ex) {
                log.debug("Olay listesi push özeti alınamadı: {}", ex.toString());
            }
        }
        if (nocCallLog != null) nocCallLog.decorate(mine);   // noc_call_count — tek sorgu; düşerse alan boş kalır
        return new Delivery(mail, push);
    }

    // ── Root-cause türetimi (kod + kategori; etiket/renk frontend'de i18n'lenir) ──
    // Paket gorunur: MonitorTypeCatalog'daki HER alarm tipinin bir kategoriye dustugunu
    // dogrulayan sozlesme testi bunu dogrudan cagirir (bkz. IncidentsControllerTest).
    Map<String, String> rootCause(String type, Map<String, Object> ctx) {
        Integer http = ctx != null && ctx.get("http_status") instanceof Number n ? n.intValue() : null;
        String err = ctx != null && ctx.get("last_error") != null ? ctx.get("last_error").toString().toLowerCase(Locale.ROOT) : null;
        String code, cat;
        if (type == null) { code = "?"; cat = "unknown"; }
        else if (type.endsWith("_SLOW"))              { code = "SLOW"; cat = "slow"; }
        else if (type.endsWith("_SSL"))               { code = "SSL";  cat = "ssl"; }
        else if (type.endsWith("DOMAIN_EXPIRY") || "DOMAINMON_EXPIRY".equals(type) || "EXPIRY".equals(type)) { code = "EXPIRY"; cat = "expiry"; }
        else if ("HTTP_DOWN".equals(type)) {
            if (http != null) { code = String.valueOf(http); cat = http >= 500 ? "server_error" : http == 404 ? "not_found" : http >= 400 ? "client_error" : "down"; }
            else { code = "DOWN"; cat = "down"; }
        }
        else if ("PORT_DOWN".equals(type)) {
            if (err != null && err.contains("refused"))                              { code = "REFUSED";     cat = "refused"; }
            else if (err != null && (err.contains("timed out") || err.contains("timeout"))) { code = "TIMEOUT"; cat = "timeout"; }
            else if (err != null && err.contains("unreachable"))                     { code = "UNREACHABLE"; cat = "unreachable"; }
            else { code = "DOWN"; cat = "down"; }
        }
        else if ("PING_DOWN".equals(type) || "ACCESSIBILITY".equals(type)) { code = "DOWN"; cat = "down"; }
        else if ("KEYWORD".equals(type))          { code = "CONTENT"; cat = "content"; }
        else if ("DNS_FAILURE".equals(type))      { code = "DNS";  cat = "dns_failure"; }
        else if (type.startsWith("DNS_"))         { code = "DNS";  cat = "dns"; }
        else if (type.startsWith("DOMAINMON_"))   { code = "DOMAIN"; cat = "domain"; }
        else if ("REVOKED".equals(type) || "MISMATCH".equals(type) || "CHAIN_BROKEN".equals(type)
                 || com.sitemonitor.service.EscalationService.TYPE_HOSTNAME_MISMATCH.equals(type)
                 || com.sitemonitor.service.EscalationService.TYPE_UNTRUSTED_CA.equals(type)) { code = type; cat = "cert"; }
        else if ("SCRIPTED_FAIL".equals(type))    { code = "SYNTHETIC"; cat = "down"; }
        else if ("PAGE_DOWN".equals(type))        { code = "DOWN";      cat = "down"; }
        else if ("PAGE_INTEGRITY".equals(type))   { code = "INTEGRITY"; cat = "content"; }
        // PAGESPEED_SLOW yukaridaki `_SLOW` kuralina takilir; DOWN'un burada acikca yeri olmali,
        // yoksa Sayfa Hizi KESINTILERI olay ekraninda "bilinmeyen" kok neden olarak gorunur.
        else if ("PAGESPEED_DOWN".equals(type))   { code = "DOWN";      cat = "down"; }
        else { code = type; cat = "unknown"; }
        return Map.of("code", code, "category", cat);
    }

    // ── Monitör çözümleme (AlertEvent.domain → ad + tip + tab + monitor_id) — N+1'siz ──
    private Map<Long, Map<String, Object>> resolveMonitors(List<AlertEvent> events) {
        Map<Long, Map<String, Object>> byEvent = new HashMap<>();
        if (events.isEmpty()) return byEvent;
        Set<String> fams = events.stream().map(e -> family(e.getAlertType())).collect(Collectors.toSet());
        Map<String, Map<String, Object[]>> idx = new HashMap<>();   // family → (key → [name, id])
        // Port/DNS: SİLİNMİŞ standalone satır indekse girmez (2026-09-27; bkz. MonitorRefResolver aynı kural).
        if (fams.contains("http"))    idx.put("http",    index(httpMonitorRepo.findAll(),    m -> m.getUrl(),    m -> m.getName(), m -> m.getId()));
        if (fams.contains("port"))    idx.put("port",    index(portMonitorRepo.findAll().stream().filter(m -> m.getDeletedAt() == null).toList(),    m -> m.getHost(),   m -> m.getName(), m -> m.getId()));
        if (fams.contains("keyword")) idx.put("keyword", index(keywordMonitorRepo.findAll(), m -> m.getUrl(),    m -> m.getName(), m -> m.getId()));
        if (fams.contains("ping"))    idx.put("ping",    index(pingMonitorRepo.findAll(),    m -> m.getHost(),   m -> m.getName(), m -> m.getId()));
        if (fams.contains("dns"))     idx.put("dns",     index(dnsMonitorRepo.findAll().stream().filter(m -> m.getDeletedAt() == null).toList(),     m -> m.getDomain(), m -> m.getName(), m -> m.getId()));
        if (fams.contains("domain"))  idx.put("domain",  index(domainMonitorRepo.findAll(),  m -> m.getDomain(), m -> m.getName(), m -> m.getId()));
        if (fams.contains("page"))     idx.put("page",     index(pageMonitorRepo.findAll(),     m -> m.getUrl(),  m -> m.getName(), m -> m.getId()));
        if (fams.contains("scripted")) idx.put("scripted", index(scriptedMonitorRepo.findAll(), m -> m.getName(), m -> m.getName(), m -> m.getId()));
        // PAGESPEED alarmlari AlertEvent.domain'e m.getUrl() yaziyor (SchedulerService.addPageSpeedSweepItems)
        // — page/http ile ayni anahtarlama.
        if (fams.contains("pagespeed")) idx.put("pagespeed", index(pageSpeedMonitorRepo.findAll(), m -> m.getUrl(), m -> m.getName(), m -> m.getId()));
        for (AlertEvent e : events) {
            String fam = family(e.getAlertType());
            Object[] ref = idx.containsKey(fam) ? idx.get(fam).get(e.getDomain()) : null;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", ref != null ? ref[0] : e.getDomain());
            m.put("type", fam);
            m.put("tab",  tabFor(fam));
            m.put("monitor_id", ref != null ? ref[1] : null);
            byEvent.put(e.getId(), m);
        }
        return byEvent;
    }

    private static <T> Map<String, Object[]> index(List<T> rows, Function<T, String> key, Function<T, String> name, Function<T, Long> id) {
        Map<String, Object[]> m = new HashMap<>();
        for (T r : rows) {
            String k = key.apply(r);
            if (k != null) m.putIfAbsent(k, new Object[]{ name.apply(r), id.apply(r) });
        }
        return m;
    }

    /** Paket-ozel: {@code rootCause} gibi, kapi testi (IncidentsControllerTest) dogrudan cagirir. */
    static String family(String type) {
        if (type == null) return "cert";
        if (type.startsWith("KEYWORD"))   return "keyword";
        if (type.startsWith("PORT_"))     return "port";
        if (type.startsWith("PING"))      return "ping";
        if (type.startsWith("DNS_"))      return "dns";
        if (type.startsWith("DOMAINMON_"))return "domain";
        // PAGESPEED once gelmeli DEGIL ama okunurluk icin burada: "PAGESPEED_DOWN".startsWith("PAGE_")
        // FALSE'tur (5. karakter 'S', '_' degil) — tam da bu yakin-kacirma yuzunden PAGESPEED
        // alarmlari asagidaki "cert" fallback'ine dusuyordu: olay ekraninda tip "cert", sekme
        // "dashboard", monitor_id null ve ad yerine ham URL. rootCause() ayni hatayi bir kez
        // yasayip duzeltmis (bkz. :183-185), bu uclu atlanmisti.
        if (type.startsWith("PAGESPEED_")) return "pagespeed";
        if (type.startsWith("PAGE_"))     return "page";
        if (type.startsWith("SCRIPTED_")) return "scripted";
        if ("HTTP_DOWN".equals(type) || "HTTP_SSL".equals(type) || "HTTP_SLOW".equals(type) || "DOMAIN_EXPIRY".equals(type)) return "http";
        return "cert";   // EXPIRY / CHAIN_BROKEN / REVOKED / MISMATCH / ACCESSIBILITY
    }

    /** Paket-ozel: kapi testi dogrudan cagirir. */
    static String tabFor(String fam) {
        return switch (fam) {
            case "http" -> "http"; case "port" -> "port"; case "keyword" -> "keyword";
            case "ping" -> "ping"; case "dns" -> "dns";  case "domain" -> "domain";
            case "page" -> "page"; case "scripted" -> "scripted";
            case "pagespeed" -> "pagespeed";
            default -> "dashboard";
        };
    }

    private Map<Long, Long> commentCounts(List<AlertEvent> events) {
        List<Long> ids = events.stream().map(AlertEvent::getId).filter(Objects::nonNull).toList();
        Map<Long, Long> out = new HashMap<>();
        if (ids.isEmpty()) return out;
        for (Object[] row : commentRepo.countByAlertIds(ids))
            out.put(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
        return out;
    }

    /**
     * Tek olay — e-postadaki "Olay detayını görüntüle / Olaya yorum yap" derin linkleri için.
     * Sayfalı listede olay 1. sayfada olmayabilir; bu uç doğrudan getirir. Yetki + takım
     * izolasyonu listeyle aynı ({@code alerts.read} + {@link #requireIncidentReadable}: kendi kapsamı ya da org geneli
     * salt okunur okuma). Satır bayrakları listeyle aynı.
     */
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable Long id, HttpSession session) {
        permissionService.require(session, "alerts.read", "view");
        AlertEvent ev = requireAlert(id);
        boolean owned = requireIncidentReadable(session, ev);
        List<AlertEvent> one = List.of(ev);
        TeamInfo teams = resolveTeams(one, inventoryFor(one, null));
        return ok(Map.of("data", toDto(ev, resolveMonitors(one), commentCounts(one), teams,
                accessFor(session).forRow(owned), deliveryFor(one, e -> owned || nocCommenter(session)))));
    }

    // ── Yorumlar ─────────────────────────────────────────────────────────────────
    /** Yorum dizisi — tekil olayla aynı okuma kapısı (başka ekibin olayında da okunur; yazma kapıları ayrı). */
    @GetMapping("/{id}/comments")
    public ResponseEntity<Map<String, Object>> listComments(@PathVariable Long id, HttpSession session) {
        permissionService.require(session, "alerts.read", "view");
        requireIncidentReadable(session, requireAlert(id));
        return ok(Map.of("data", commentRepo.findByAlertEventIdAndDeletedAtIsNullOrderByCreatedAtAsc(id)));
    }

    @PostMapping("/{id}/comments")
    public ResponseEntity<Map<String, Object>> addComment(
            @PathVariable Long id, @RequestBody Map<String, Object> body,
            HttpSession session, HttpServletRequest request) {
        // 7/24 operatörü (2026-10-04): okuyabildiği HER olaya yorum yazar (alerts.actions gerekmez, takım kapsamı
        // okuma kapısıdır). Diğerleri bugünkü gibi: alerts.actions + KENDİ kapsamı.
        if (nocCommenter(session)) {
            permissionService.require(session, "alerts.read", "view");
            requireIncidentReadable(session, requireAlert(id));
        } else {
            permissionService.require(session, "alerts.actions", "execute");
            requireIncidentScope(session, requireAlert(id));
        }
        String text = body.get("body") != null ? body.get("body").toString().trim() : "";
        if (text.isEmpty()) throw new IllegalArgumentException("Yorum boş olamaz");
        if (text.length() > MAX_COMMENT) throw new IllegalArgumentException("Yorum " + MAX_COMMENT + " karakteri aşamaz");
        String user = (String) session.getAttribute("username");
        String name = (String) session.getAttribute("displayName");
        if (name == null || name.isBlank()) name = user;
        AlertComment c = new AlertComment();
        c.setAlertEventId(id);
        c.setBody(text);
        c.setTeamId(sessionTeamId(session));
        c.setAuthorUsername(user);
        c.setAuthorName(name);
        c.setCreatedAt(now());
        AlertComment saved = commentRepo.save(c);
        // Yorum GOVDESI yazilmaz (kendi tablosunda duruyor); denetimin sorusu "kim ne zaman
        // yorum yapti" — hangi yoruma ve ne kadar uzunlukta oldugu izi yeterli.
        auditService.recordAction("INCIDENT_COMMENT_ADD", session, request, "ALERT_EVENT", String.valueOf(id),
                com.sitemonitor.service.AuditDetail.of("comment_id", saved.getId(),
                        "length", text.length()));
        return ok(Map.of("data", saved, "message", "Comment added"));
    }

    @DeleteMapping("/comments/{commentId}")
    public ResponseEntity<Map<String, Object>> deleteComment(
            @PathVariable Long commentId, HttpSession session, HttpServletRequest request) {
        // 7/24 operatörü (2026-10-04) okuyabildiği olayda KENDİ yorumunu siler; başkasınınkini yalnız yönetim kapsamıyla.
        boolean noc = nocCommenter(session);
        permissionService.require(session, noc ? "alerts.read" : "alerts.actions", noc ? "view" : "execute");
        AlertComment c = commentRepo.findById(commentId)
                .orElseThrow(() -> new NoSuchElementException("Yorum bulunamadı: " + commentId));
        // TAKIM KAPSAMI: kardes uclar (get/listComments/addComment) requireIncidentScope tasiyor,
        // silme tasimiyordu. "elevated" yalniz ROL dizesine bakiyor, HANGI takimin yoneticisi
        // olduguna bakmiyordu -> A takiminin TEAM_ADMIN'i id artirarak B takiminin yorumlarini
        // silebiliyordu (geri alma arayuzu de yok). Ayrica yorumun varligi istisna ile
        // numaralandirilabiliyordu; kapsam kontrolu once gelince o da kapanir.
        AlertEvent ev = requireAlert(c.getAlertEventId());
        if (noc) requireIncidentReadable(session, ev); else requireIncidentScope(session, ev);
        if (c.getDeletedAt() != null) return ok(Map.of("message", "Zaten silinmiş"));
        String user = (String) session.getAttribute("username");
        boolean own = user != null && user.equals(c.getAuthorUsername());
        if (!own && !canManageIncident(session, ev))
            throw new SecurityException("Yalnız yorumu ekleyen veya bu incident'ın takımının yöneticisi silebilir");
        c.setDeletedAt(now());
        c.setDeletedBy(user != null ? user : "anonymous");
        commentRepo.save(c);
        // SILMEDE alinti GEREKLI: yok olan sey tam da govdenin kendisi. Ilk 120 karakter,
        // "hangi yorum silindi" sorusunu cevaplamaya yeter, tam metni kopyalamadan.
        auditService.recordAction("INCIDENT_COMMENT_DELETE", session, request, "ALERT_EVENT",
                String.valueOf(c.getAlertEventId()),
                com.sitemonitor.service.AuditDetail.of("comment_id", c.getId(),
                        "author", c.getAuthorUsername(), "created_at", c.getCreatedAt(),
                        "excerpt", c.getBody() == null ? null
                                : c.getBody().substring(0, Math.min(120, c.getBody().length()))));
        return ok(Map.of("message", "Comment deleted"));
    }

    // ── Incident silme (yalnız global ADMIN) — yıkıcı: alarm kaydı + yorumları gider ──
    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<Map<String, Object>> deleteIncident(
            @PathVariable Long id, HttpSession session, HttpServletRequest request) {
        permissionService.require(session, "alerts.actions", "execute");
        if (!SessionScope.isGlobalAdmin(session))
            throw new SecurityException("Incident silme yalnız yöneticiye açıktır");
        AlertEvent ev = requireAlert(id);
        for (AlertComment c : commentRepo.findByAlertEventIdAndDeletedAtIsNullOrderByCreatedAtAsc(id))
            commentRepo.deleteById(c.getId());
        alertEventRepo.deleteById(id);
        auditService.recordAction("ALERT_DELETE", session, request, "ALERT_EVENT", String.valueOf(id),
                "{\"domain\":\"" + (ev.getDomain() == null ? "" : ev.getDomain().replace("\"", "\\\"")) + "\"}");
        return ok(Map.of("message", "Incident deleted"));
    }

    // ── helpers ──────────────────────────────────────────────────────────────────
    private AlertEvent requireAlert(Long id) {
        return alertEventRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Incident bulunamadı: " + id));
    }

    /**
     * YAZMA kapısının takım kapsamı (IDOR): global viewer serbest; aksi halde alarmın takımı (teamId veya domain→envanter
     * SY/UG) görüş kapsamında olmalı. Org geneli okuma ayarını BİLEREK sormaz — başka ekibin olayını okuyabilmek ona
     * yorum yazma/silme hakkı vermez (2026-09-28).
     */
    private void requireIncidentScope(HttpSession session, AlertEvent ev) {
        if (!inOwnScope(session, ev))
            throw new SecurityException("Bu incident üzerinde yetkiniz yok");
    }

    /** Olay çağıranın KENDİ kapsamında mı (global görüntüleyici → her olay). Liste bayrağı {@code can_manage} ile aynı kural. */
    private boolean inOwnScope(HttpSession session, AlertEvent ev) {
        if (SessionScope.isGlobalViewer(session)) return true;
        return incidentTeamInScope(ev, SessionScope.viewTeamIds(session));
    }

    /**
     * OKUMA kapısı (tekil olay + yorum dizisi): kendi kapsamı YA DA org geneli salt okunur okuma (ayar açık). Aksi halde
     * bugünkü gibi 403. Dönüş: olay KENDİ kapsamında mı (satır bayrakları için).
     */
    private boolean requireIncidentReadable(HttpSession session, AlertEvent ev) {
        if (inOwnScope(session, ev)) return true;
        if (orgWideReader(session)) return false;
        throw new SecurityException("Bu incident üzerinde yetkiniz yok");
    }

    /**
     * Incident'ın takimi verilen kapsamda mi. Damgalanmis {@code teamId} önce; yoksa envanter
     * kolu (envanter-türevi izlemelerde alarm takimsiz açılabiliyor).
     *
     * <p>Kapsam parametreli: OKUMA {@code viewTeamIds}, YAZMA {@code manageTeamIds} ile çağırır.
     * İkisi aynı şey değildir — müdürün görüş alanı astlarının takimlarını kapsar, yönetim
     * yetkisi kapsamaz.
     */
    private boolean incidentTeamInScope(AlertEvent ev, List<Long> scope) {
        if (scope == null || scope.isEmpty()) return false;
        if (ev.getTeamId() != null && scope.contains(ev.getTeamId())) return true;
        return ev.getDomain() != null && inventoryRepo.findByDomain(ev.getDomain())
                .map(inv -> (inv.getTeamId() != null && scope.contains(inv.getTeamId()))
                         || (inv.getUgTeamId() != null && scope.contains(inv.getUgTeamId())))
                .orElse(false);
    }

    /**
     * Bu incident'ın kayitlarini DÜZENLEYEBİLİR Mİ — <b>yönetim</b> kapsamıyla sorar.
     *
     * <p>Eski hâl yalnızca ROL DİZESİNE bakıyordu ({@code "TEAM_ADMIN".equals(role)}) ve tek öncesi
     * {@code requireIncidentScope} idi — o da OKUMA kapsamı. Sonuç: A takımının yöneticisi,
     * görüş alanına giren B takımının yorumlarını silebiliyordu — okuma yetkisiyle
     * yetkilendirilmiş bir YAZMA. Silme yumuşak ve geri alma arayüzü yok.
     */
    private boolean canManageIncident(HttpSession session, AlertEvent ev) {
        return SessionScope.isGlobalAdmin(session)
                || incidentTeamInScope(ev, SessionScope.manageTeamIds(session));
    }

    private Map<String, Object> deserialize(String json) {
        if (json == null || json.isBlank()) return null;
        try { return MAPPER.readValue(json, Map.class); } catch (Exception e) { return null; }
    }

    private Long sessionTeamId(HttpSession session) {
        Object v = session.getAttribute("teamId");
        return v instanceof Number num ? num.longValue() : null;
    }

    private static String now() { return ISO.format(Instant.now()); }

    private ResponseEntity<Map<String, Object>> ok(Map<String, Object> body) {
        Map<String, Object> r = new LinkedHashMap<>(body);
        r.put("success", true);
        r.put("timestamp", Instant.now().toString());
        return ResponseEntity.ok(r);
    }
}
