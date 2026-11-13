package com.sitemonitor.controller;

import com.sitemonitor.model.AlertComment;
import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.repository.*;
import com.sitemonitor.service.AlertOwnership;
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
    /** Veritabanı sorgu izlemesi (11. tür, 2026-10-31) — alan enjeksiyonu, isteğe bağlı (WebMvc dilimi / elle kurulan test). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.sitemonitor.repository.DbQueryMonitorRepository dbQueryMonitorRepo;
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
    /** "7/24'e gitti" bilgisi (2026-11-13) — isteğe bağlı; dilimli testte yokken alan yazılmaz. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.sitemonitor.service.noc.NocAlertFacts nocAlertFacts;
    /** Takım duyarlı dar izleme çözümlemesi (2026-11-13) — isteğe bağlı; yokken eski tam liste yolu (takım duyarlı seçimle). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.sitemonitor.service.MonitorRefResolver monitorRefs;

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
            @RequestParam(required = false) String q,           // monitör adı/host araması (LIKE domain, kaçışlı)
            @RequestParam(required = false) String since,       // ISO ya da yyyy-MM-dd (İstanbul günü başı)
            @RequestParam(required = false) String until,       // ISO ya da yyyy-MM-dd (o İstanbul günü DAHİL)
            @RequestParam(defaultValue = "default") String sort,
            @RequestParam(defaultValue = "desc") String dir,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = SCOPE_MINE) String scope,   // mine | others | all (2026-09-28)
            // Sunucu süzgeçleri (2026-11-13) — eskiden yalnız yüklenen sayfaya uygulanıyordu
            @RequestParam(required = false) String level,          // CSV: CRITICAL,HIGH,WARNING
            @RequestParam(required = false) Long teamId,           // Alarm Geçmişi ile aynı sahiplik kuralı
            @RequestParam(required = false) Boolean acknowledged,
            @RequestParam(defaultValue = "false") boolean teamless,
            @RequestParam(defaultValue = "false") boolean mine,    // oturumun birincil takımı
            @RequestParam(required = false) String resolvedSince,  // Çözüldü (24 sa) kartı: çözülme anına göre
            @RequestParam(defaultValue = "false") boolean recurring, // son 7 günde aynı imza en az iki kez
            HttpSession session) {
        permissionService.require(session, "alerts.read", "view");
        int sz = Math.max(1, Math.min(size, 200));
        Filters f = filters(status, rootCause, q, since, until, level, teamId, acknowledged, teamless, mine,
                resolvedSince, recurring, session);

        // Takım kapsamı (IDOR): "kendi" kapsam = görüş kapsamı (global görüntüleyicide null = tümü). Org geneli okuma
        // ayrı bir karar: yalnız anahtar açıkken others/all uygulanır.
        List<Long> own = ownScope(session);
        boolean orgWide = orgWideReader(session);
        String eff = orgWide ? normalizeScope(scope) : SCOPE_MINE;
        ScopeQuery sq = scopeQuery(eff, own);

        Page<AlertEvent> result = sq == null
                ? Page.empty(PageRequest.of(0, sz))   // kapsamsız kullanıcının "Takımımın olayları": hiçbir olay
                : page(f, sq, PageRequest.of(Math.max(0, page), sz, sortFor(sort, dir)));
        List<Map<String, Object>> data = rowsFor(result.getContent(), own, session);

        Map<String, Long> typeCounts = new LinkedHashMap<>();
        if (sq != null)
            for (Object[] row : byType(f, sq))
                typeCounts.put(String.valueOf(row[0]), (Long) row[1]);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data",        data);
        body.put("total",       result.getTotalElements());
        body.put("page",        result.getNumber());
        body.put("size",        result.getSize());
        body.put("type_counts", typeCounts);
        body.put("scope",       eff);
        body.put("visible_to_all", visibleToAll() || nocCommenter(session));   // 7/24 operatörü: ayardan bağımsız
        // 2026-11-13: önem / takım / onaylı / takımsız / takımım / tekrarlayan SUNUCUDA uygulandı — arayüz yüklenen
        // sayfaya ikinci kez süzgeç uygulamaz (sayfaya uygulandı notu yok). Alan yoksa (eski sunucu) eski istemci yolu.
        body.put("server_facets", true);
        if (orgWide) body.put("scope_counts", scopeCounts(eff, result.getTotalElements(), own, f));
        return ok(body);
    }

    /**
     * Sayfa satırları — liste, tekil olay ve dışa aktarım AYNI yoldan geçer (kişi verisi maskesi tek yerde): izleme
     * çözümlemesi (takım duyarlı, dar okuma), yorum sayıları, envanter (takım sütunu + sahiplik), teslim özeti (yalnız
     * kendi / 7/24 satırları), 7 günlük tekrar sayısı. Sayfa başına sabit sorgu sayısı; satır başına sorgu YOK.
     */
    private List<Map<String, Object>> rowsFor(List<AlertEvent> events, List<Long> own, HttpSession session) {
        if (events.isEmpty()) return List.of();
        Map<String, List<com.sitemonitor.model.CertificateInventory>> inv = inventoryFor(events, own);
        Map<Long, Long> monitorTeams = new HashMap<>();
        Map<Long, Map<String, Object>> monitors = resolveMonitors(events, inv, monitorTeams);
        Map<Long, Long> commentCounts = commentCounts(events);
        TeamInfo teams = resolveTeams(events, inv);
        Set<Long> owned = ownedIds(events, actScope(session), inv);   // eylem sahipliği ≠ okuma (paylaşım VIEW, 2026-10-12)
        // Yeniden bildirim kapsamı (2026-11-13): AdminController.requireAlertNotifyScope ile aynı — erişim paylaşımı kolu YOK
        Set<Long> notifyOwned = ownedIds(events,
                SessionScope.isGlobalViewer(session) ? null : nullToEmpty(SessionScope.viewTeamIds(session)), inv);
        Access access = accessFor(session);
        // 7/24 operatörü (2026-10-04) başka ekibin olayını da TAM okur (teslim özeti, izleme bağlantısı, çözen kişi) —
        // eylem bayrakları (can_manage/can_act/can_delete) yine KENDİ kapsamından.
        Delivery delivery = deliveryFor(events, e -> access.noc() || owned.contains(e.getId()));
        Map<String, Long> recurrence = recurrenceFor(events);
        List<Long> checkScope = checkRecordScope(session);
        boolean seesAllAlerts = seesAllAlerts(session);
        List<Long> alertRead = SessionScope.viewTeamIdsWithGrants(session);
        return events.stream()
                .map(e -> toDto(e, monitors, commentCounts, teams,
                        access.forRow(owned.contains(e.getId()), notifyOwned.contains(e.getId())), delivery, inv, recurrence,
                        new RowLinks(checkRecordsVisible(monitorTeams.get(e.getId()), checkScope),
                                seesAllAlerts || alertReadable(e, inv, alertRead))))
                .toList();
    }

    /**
     * Satırın bağlantı kararları (2026-11-13 hata taraması): {@code checkRecordsVisible} — olayın izlemesinin Aktivite
     * kontrol kayıtları izleyiciye görünür mü ({@link #checkRecordsVisible}); {@code canOpenAlert} — Alarm Geçmişi tekil
     * okuması ({@code AdminController.requireAlertReadScope}) bu olayı açar mı.
     */
    private record RowLinks(boolean checkRecordsVisible, boolean canOpenAlert) {}

    /**
     * Aktivite LİSTESİNİN kapsamı — {@code ActivityController.scope} ile BİREBİR: 7/24 operatörü / global görüntüleyici
     * {@code null} (tümü), diğerleri görüş kapsamı + erişim paylaşımı (oturumda görüş kapsamı yoksa null = tümü, aynen).
     */
    private static List<Long> checkRecordScope(HttpSession session) {
        return SessionScope.seesAllMonitoring(session) ? null : SessionScope.viewTeamIdsWithGrants(session);
    }

    /**
     * Kontrol kayıtları görünür mü (2026-11-13 hata taraması): her kontrol satırı Aktivite kaydına izleme SATIRININ
     * {@code teamId}'siyle yazılır (envanter türevi Port / DNS'te envanterin SY takımı) ve Aktivite listesi
     * {@code team_id IN kapsam} ile süzer. UG takımı üyesi olayı envanter kuralıyla "kendi" görür ama o kayıtları göremez —
     * kanıt bölümü "başarısız kontrol yok" diyordu. {@code monitorTeam} null (izleme takımsız) yalnız tüm kapsamda görünür.
     */
    static boolean checkRecordsVisible(Long monitorTeam, List<Long> scope) {
        return scope == null || (monitorTeam != null && scope.contains(monitorTeam));
    }

    /** {@code AdminController.seesAllAlerts} ile aynı: global görüntüleyici ya da 7/24 operatörü. */
    private boolean seesAllAlerts(HttpSession session) {
        return SessionScope.isGlobalViewer(session) || nocCommenter(session);
    }

    /**
     * {@code AdminController.requireAlertReadScope}'un takım kolu (2026-11-13 hata taraması): damga + alan adının envanter
     * SY / UG takımı (bağımsız olayda da — Alarm Geçmişi okuması envanter kolunu taşır) görüş + paylaşım kapsamında mı.
     * Envanter satırı çağırandan ({@link #inventoryFor} damgası kapsam dışı olayın alan adını yükler); {@code read} null →
     * hayır (Alarm Geçmişi kuralı).
     */
    static boolean alertReadable(AlertEvent e, Map<String, List<com.sitemonitor.model.CertificateInventory>> inv,
                                 List<Long> read) {
        if (read == null || read.isEmpty()) return false;
        if (e.getTeamId() != null && read.contains(e.getTeamId())) return true;
        if (e.getDomain() == null || inv == null) return false;
        for (com.sitemonitor.model.CertificateInventory i : inv.getOrDefault(e.getDomain(), List.of())) {
            if ((i.getTeamId() != null && read.contains(i.getTeamId()))
                    || (i.getUgTeamId() != null && read.contains(i.getUgTeamId()))) return true;
        }
        return false;
    }

    private static List<Long> nullToEmpty(List<Long> v) { return v == null ? List.of() : v; }

    /**
     * "Takımımın olayları / Diğer ekiplerin olayları / Tümü" çip sayıları — sayfa süzgeçleriyle (durum, kök neden, arama,
     * tarih, önem, takım …) aynı. İki küme ayrık ve birleşimleri tümü olduğundan sayfanın toplamına TEK sayım eklemek yeter.
     */
    private Map<String, Long> scopeCounts(String eff, long pageTotal, List<Long> own, Filters f) {
        long mineN;
        long all;
        switch (eff) {
            case SCOPE_MINE -> {
                mineN = pageTotal;
                all = count(f, new ScopeQuery(false, false, NO_SCOPE));
            }
            case SCOPE_ALL -> {
                all = pageTotal;
                mineN = own.isEmpty() ? 0L : count(f, new ScopeQuery(true, false, own));
            }
            default -> {   // others
                mineN = own.isEmpty() ? 0L : count(f, new ScopeQuery(true, false, own));
                all = mineN + pageTotal;
            }
        }
        Map<String, Long> m = new LinkedHashMap<>();
        m.put(SCOPE_MINE, mineN);
        m.put(SCOPE_OTHERS, Math.max(0L, all - mineN));
        m.put(SCOPE_ALL, all);
        return m;
    }

    // ── Süzgeç gövdesi (2026-11-13) — liste, özet ve dışa aktarım AYNI kayıttan sorgular ──────────────────────────
    /** İstanbul — tarih süzgecinin gün sınırı (depolama UTC). */
    static final java.time.ZoneId TZ = java.time.ZoneId.of("Europe/Istanbul");
    private static final java.util.regex.Pattern DAY = java.util.regex.Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    private static final java.util.regex.Pattern STAMP = java.util.regex.Pattern.compile("^[0-9TZ:.+\\-]{10,40}$");
    /** Önem süzgecinin kabul ettiği değerler (Alarm Geçmişi ile aynı üçlü); bilinmeyen değer düşer, hiçbiri kalmazsa boş sonuç. */
    static final Set<String> LEVELS = Set.of("CRITICAL", "HIGH", "WARNING");
    /** Hiçbir alarmın taşımadığı önem değeri: geçersiz (ya da boş kesişimli) önem süzgeci boş sonuç verir. */
    static final String NO_LEVEL = "-";
    /** "Tekrarlayan" ve satır rozetinin penceresi (gün). */
    static final int RECUR_DAYS = 7;
    /** Hiçbir olayla eşleşmeyen takım kimliği ("Takımım" süzgeci birincil takımı olmayan oturumda). */
    static final Long NO_TEAM = -1L;

    /**
     * Olay süzgeçleri. {@code q} kaçışlanmış LIKE kalıbıdır; {@code levels} boş = süzgeç yok; {@code teamId} Alarm Geçmişi
     * listesinin sahiplik kuralı (damga ya da envanter SY/UG); {@code teamless} takım sütunu boş; {@code recurSince}
     * imza en az iki kez.
     */
    record Filters(Boolean resolved, String since, String until, String resolvedSince, String alertType, String q,
                   List<String> levels, Boolean acknowledged, Long teamId, boolean teamless, String recurSince) {
        static Filters none() {
            return new Filters(null, null, null, null, null, null, List.of(), null, null, false, null);
        }
        boolean levelScoped() { return !levels.isEmpty(); }
        java.util.Collection<String> levelParam() { return levels.isEmpty() ? AlertEventRepository.NO_LEVELS : levels; }
        Filters resolved(Boolean v) { return new Filters(v, since, until, resolvedSince, alertType, q, levels, acknowledged, teamId, teamless, recurSince); }
        Filters createdSince(String v) { return new Filters(resolved, v, until, resolvedSince, alertType, q, levels, acknowledged, teamId, teamless, recurSince); }
        Filters resolvedFrom(String v) { return new Filters(resolved, since, until, v, alertType, q, levels, acknowledged, teamId, teamless, recurSince); }
        Filters team(Long v) { return new Filters(resolved, since, until, resolvedSince, alertType, q, levels, acknowledged, v, teamless, recurSince); }
        Filters onlyTeamless() { return new Filters(resolved, since, until, resolvedSince, alertType, q, levels, acknowledged, teamId, true, recurSince); }
    }

    private Filters filters(String status, String rootCause, String q, String since, String until, String level, Long teamId,
                            Boolean acknowledged, boolean teamless, boolean mine, String resolvedSince, boolean recurring,
                            HttpSession session) {
        Boolean resolved = "ongoing".equalsIgnoreCase(status) ? Boolean.FALSE
                : "resolved".equalsIgnoreCase(status) ? Boolean.TRUE : null;
        String type = (rootCause != null && !rootCause.isBlank()) ? rootCause.trim() : null;
        List<String> levels = new ArrayList<>();
        if (level != null && !level.isBlank() && level.length() <= 200) {
            for (String part : level.split(",")) {
                String v = part.trim().toUpperCase(Locale.ROOT);
                if (LEVELS.contains(v) && !levels.contains(v)) levels.add(v);
            }
            // Dolu ama geçerli değer taşımayan süzgeç (istemcinin "Kritik" kartı ∩ uyarı süzgeci → "-") HİÇBİR olayla
            // eşleşmez — süzgeci yok saymak her önemi listelerdi.
            if (levels.isEmpty()) levels.add(NO_LEVEL);
        }
        Long effTeam = teamId;
        if (mine) {
            Long primary = SessionScope.primaryTeamId(session);
            // Birincil takımı olmayan oturumun "Takımım"ı boş küme; açık takım süzgeci farklı bir takımsa da kesişim boş.
            effTeam = primary == null || (teamId != null && !teamId.equals(primary)) ? NO_TEAM : primary;
        }
        String recurSince = recurring ? ISO.format(Instant.now().minus(java.time.Duration.ofDays(RECUR_DAYS))) : null;
        return new Filters(resolved, sinceBound(since, "since"), untilBound(until, "until"), stampOrNull(resolvedSince, "resolvedSince"),
                type, AlertEventRepository.incidentLike(q), List.copyOf(levels), acknowledged, effTeam, teamless, recurSince);
    }

    /**
     * Tarih süzgecinin ALT sınırı (2026-11-13 düzeltmesi): {@code yyyy-MM-dd} → o İSTANBUL gününün başı (UTC ISO);
     * tam damga (eski istemci / derin bağlantı) aynen. Önceden gün metni UTC sözlüksel karşılaştırılıyordu: başlangıç 3 saat
     * kayıyordu. Geçersiz gün → 400 alan hatası.
     */
    static String sinceBound(String raw, String field) {
        if (raw == null || raw.isBlank()) return null;
        String v = raw.trim();
        if (!DAY.matcher(v).matches()) return stampOrNull(v, field);
        return ISO.format(parseDay(v, field).atStartOfDay(TZ).toInstant());
    }

    /**
     * Tarih süzgecinin ÜST sınırı: {@code yyyy-MM-dd} → o İstanbul gününün SONU dahil (ertesi günün başından bir saniye
     * önce — damgalar saniye hassasiyetinde, sorgu {@code <=}). Önceden "2026-11-10" metni "2026-11-10T…" damgalarından
     * küçük kaldığı için bitiş günü TÜMÜYLE dışarıda kalıyordu.
     */
    static String untilBound(String raw, String field) {
        if (raw == null || raw.isBlank()) return null;
        String v = raw.trim();
        if (!DAY.matcher(v).matches()) return stampOrNull(v, field);
        return ISO.format(parseDay(v, field).plusDays(1).atStartOfDay(TZ).toInstant().minusSeconds(1));
    }

    private static java.time.LocalDate parseDay(String v, String field) {
        try {
            return java.time.LocalDate.parse(v);
        } catch (java.time.format.DateTimeParseException e) {
            throw new com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException(field, com.sitemonitor.util.Msg.t(
                    "Tarih geçersiz: " + v + " — yyyy-AA-gg biçiminde geçerli bir gün girin.",
                    "Invalid date: " + v + " — enter a valid day as yyyy-MM-dd."));
        }
    }

    /**
     * Tam damga → depolamayla karşılaştırılabilir UTC metni ({@code yyyy-MM-ddTHH:mm:ss}); boş → null. 2026-11-13 hata
     * taraması: değer sözlük sırasıyla karşılaştırıldığı için {@code …+03:00} / {@code …Z} taşıyan damga dilim YOK
     * SAYILARAK işleniyordu (İstanbul gece yarısı UTC 00:00 gibi). Artık ayrıştırılır: dilimli değer UTC'ye çevrilir,
     * dilimsiz değer UTC sayılır (bugünkü gibi), yalnız gün ({@code resolvedSince}) UTC gün başıdır (bugünkü sözlük
     * davranışı). Ayrıştırılamayan → 400 {@code VALIDATION_FAILED} + alan. Karakter / uzunluk ön denetimi düz (doğrusal).
     */
    static String stampOrNull(String raw, String field) {
        if (raw == null || raw.isBlank()) return null;
        String v = raw.trim();
        if (!STAMP.matcher(v).matches()) throw invalidStamp(field);
        try {
            if (DAY.matcher(v).matches()) return ISO.format(java.time.LocalDate.parse(v).atStartOfDay(ZoneOffset.UTC).toInstant());
            int t = v.indexOf('T');
            boolean zoned = v.endsWith("Z") || (t > 0 && (v.indexOf('+', t) > 0 || v.indexOf('-', t) > 0));
            Instant at = zoned ? java.time.OffsetDateTime.parse(v).toInstant()
                    : java.time.LocalDateTime.parse(v).toInstant(ZoneOffset.UTC);
            return ISO.format(at);
        } catch (java.time.DateTimeException e) {
            throw invalidStamp(field);
        }
    }

    private static com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException invalidStamp(String field) {
        return new com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException(field, com.sitemonitor.util.Msg.t(
                "Zaman değeri geçersiz: yyyy-AA-gg, yyyy-AA-ggTSS:dd:ss ya da saat dilimli ISO biçiminde (…Z, …+03:00) girin.",
                "Invalid time value: use yyyy-MM-dd, yyyy-MM-ddTHH:mm:ss or an ISO time with a zone (…Z, …+03:00)."));
    }

    private Page<AlertEvent> page(Filters f, ScopeQuery sq, org.springframework.data.domain.Pageable p) {
        return alertEventRepo.findIncidentsFiltered(f.resolved(), f.since(), f.until(), f.resolvedSince(), f.alertType(), f.q(),
                f.levelScoped(), f.levelParam(), f.acknowledged(), f.teamId(), f.teamless(), f.recurSince(),
                sq.scoped(), sq.outside(), sq.ids(), p);
    }

    private long count(Filters f, ScopeQuery sq) {
        return alertEventRepo.countIncidentsFiltered(f.resolved(), f.since(), f.until(), f.resolvedSince(), f.alertType(), f.q(),
                f.levelScoped(), f.levelParam(), f.acknowledged(), f.teamId(), f.teamless(), f.recurSince(),
                sq.scoped(), sq.outside(), sq.ids());
    }

    private List<Object[]> byType(Filters f, ScopeQuery sq) {
        return alertEventRepo.countIncidentsByTypeFiltered(f.resolved(), f.since(), f.until(), f.resolvedSince(), f.q(),
                f.levelScoped(), f.levelParam(), f.acknowledged(), f.teamId(), f.teamless(), f.recurSince(),
                sq.scoped(), sq.outside(), sq.ids());
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
        List<Long> v = SessionScope.viewTeamIdsWithGrants(session);   // + erişim paylaşımı (2026-10-12) — OKUMA
        return v == null ? List.of() : v;
    }

    /**
     * EYLEM sahipliği kapsamı (satır bayrakları {@code can_manage / can_act}, yorum): görüş kapsamı + erişim paylaşımında
     * EXECUTE ve üstü (2026-10-12). Yalnız görüntüleme paylaşımı olayı OKUTUR, eylem açmaz. Global görüntüleyicide null.
     */
    private static List<Long> actScope(HttpSession session) {
        if (SessionScope.isGlobalViewer(session)) return null;
        List<Long> v = SessionScope.viewTeamIds(session);
        List<Long> exec = SessionScope.grantExecuteTeamIds(session);
        if (exec.isEmpty()) return v == null ? List.of() : v;
        java.util.LinkedHashSet<Long> out = new java.util.LinkedHashSet<>(v == null ? List.of() : v);
        out.addAll(exec);
        return List.copyOf(out);
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
            return forRow(owned, false);
        }
        RowAccess forRow(boolean owned, boolean notifyOwned) {
            return new RowAccess(owned, owned && actions, owned && actions && globalAdmin,
                    owned || noc, (owned && actions) || noc, notifyOwned && actions);
        }
    }

    /** {@code can_manage} = olay kendi kapsamında (yazma kapılarının sorduğu kapsam); {@code can_act} = + alerts.actions;
     *  {@code can_delete} = + global yönetici (silme ucunun kuralı); {@code can_notify} (2026-11-13) = yeniden bildirim
     *  ({@code AdminController.requireAlertNotifyScope}: kendi görüş kapsamı, erişim paylaşımı kolu YOK) + alerts.actions.
     *  Arayüz yalnız bunlara göre eylem çizer; sunucu her yazma ucunda yine kendi kapısını uygular. */
    private record RowAccess(boolean owned, boolean canAct, boolean canDelete, boolean full, boolean canComment,
                             boolean canNotify) {}

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
    public static Sort sortFor(String sort, String dir) {
        Sort.Direction d = "asc".equalsIgnoreCase(dir) ? Sort.Direction.ASC : Sort.Direction.DESC;
        // Kimlik eşitlik bozucu (2026-10-22, DATA-7): aynı saniyede açılan olaylarda sayfa sınırı kararsızdı (satır iki
        // sayfada birden ya da hiç). Önem METİN saklanır (DATA-8): alfabetik sırada WARNING > HIGH > CRITICAL çıkıyordu —
        // ilk tık (desc) en ağır olayları sona atıyordu; artık Alarm Geçmişi'yle aynı CASE sırası.
        Sort tie = Sort.by(Sort.Direction.DESC, "id");
        Sort newest = Sort.by(Sort.Direction.DESC, "createdAt");
        return switch (sort == null ? "started" : sort) {
            case "status"   -> Sort.by(d, "resolved").and(newest).and(tie);
            case "severity" -> org.springframework.data.jpa.domain.JpaSort.unsafe(d, AdminController.AlertSort.LEVEL_RANK).and(newest).and(tie);
            case "type"     -> Sort.by(d, "alertType").and(newest).and(tie);
            case "started"  -> Sort.by(d, "createdAt").and(Sort.by(d, "id"));
            // varsayılan (istek dışı): ongoing-first + en yeni
            default         -> Sort.by(Sort.Direction.ASC, "resolved").and(newest).and(tie);
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
            // Envanter gibi yönlenen olayın sahip takımları (owner_team_ids → nöbetçi) SY/UG'yi de ister (2026-11-13)
            if (teamless || ownershipOpen || AlertOwnership.routesLikeInventory(e)) need.add(e.getDomain());
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
     * Sayfadaki hangi olaylar çağıranın KENDİ (sahip olduğu) kapsamında — {@link #incidentOwnedBy}'ın toplu hâli (satır
     * başına sorgu yok). {@code own == null} (global görüntüleyici) → hepsi. Bağımsız izleme olayında envanter kolu
     * YOKTUR (2026-10-09, {@link AlertOwnership}): host'un envanterini tutan takım başka takımın Ping / HTTP … olayının
     * sahibi sayılmaz — satır "Takımımın olayları"nda görünse de (liste sorgusu envanter kuralını taşır) salt okunurdur.
     */
    private static Set<Long> ownedIds(List<AlertEvent> events, List<Long> own,
                                      Map<String, List<com.sitemonitor.model.CertificateInventory>> inv) {
        Set<Long> out = new HashSet<>();
        if (own == null) { for (AlertEvent e : events) out.add(e.getId()); return out; }
        Set<Long> scope = new HashSet<>(own);
        for (AlertEvent e : events) {
            if (e.getTeamId() != null && scope.contains(e.getTeamId())) { out.add(e.getId()); continue; }
            if (e.getDomain() == null || !AlertOwnership.routesLikeInventory(e)) continue;
            for (com.sitemonitor.model.CertificateInventory i : inv.getOrDefault(e.getDomain(), List.of())) {
                if ((i.getTeamId() != null && scope.contains(i.getTeamId()))
                        || (i.getUgTeamId() != null && scope.contains(i.getUgTeamId()))) { out.add(e.getId()); break; }
            }
        }
        return out;
    }

    private Map<String, Object> toDto(AlertEvent e, Map<Long, Map<String, Object>> monitors, Map<Long, Long> counts,
                                      TeamInfo teams, RowAccess access, Delivery delivery,
                                      Map<String, List<com.sitemonitor.model.CertificateInventory>> inv,
                                      Map<String, Long> recurrence, RowLinks links) {
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
        // Yeniden bildir (2026-11-13): yalnız kendi görüş kapsamı + alerts.actions (paylaşım / 7/24 / org geneli okuma DEĞİL)
        dto.put("can_notify",    access.canNotify());
        // Zengin bağlam (2026-11-13) — kişi verisi DEĞİL, her okunabilir satırda: sessiz kapanış (izleme silindi / duraklatıldı
        // / bildirimler kapatıldı — "kurtulmadı, susturuldu"), bakım penceresinde algılanan değişiklik, 7 günlük tekrar.
        dto.put("resolved_silently", Boolean.TRUE.equals(e.getResolvedSilently()));
        dto.put("in_maintenance", ctx != null && ctx.get(com.sitemonitor.service.EscalationService.CTX_DETECTED_IN_MAINTENANCE) != null);
        Long rec = recurrence == null ? null : recurrence.get(recurrenceKey(e.getDomain(), e.getAlertType()));
        if (rec != null) dto.put("recurrence_7d", rec);
        // Alarm Geçmişi'nde açılabilir mi (2026-11-13 hata taraması): sunucunun tekil alarm okuma kuralı — görüntüleme
        // paylaşımıyla ya da envanter host'u üzerinden okunan olay da açılır; arayüz bağlantıyı yalnız buna göre çizer.
        dto.put("can_open_alert", links != null && links.canOpenAlert());
        if (!access.full() && monitor.containsKey("monitor_id")) {
            Map<String, Object> slim = new LinkedHashMap<>(monitor);
            slim.put("monitor_id", null);
            dto.put("monitor", slim);
        } else if (monitor.get("monitor_id") != null && links != null) {
            // Kontrol kanıtı sorulabilir mi (2026-11-13 hata taraması): izlemenin Aktivite kayıtları izleyicinin
            // kapsamında mı — değilse arayüz "görme yetkiniz yok" der, boş listeyi "başarısız kontrol yok" saymaz.
            dto.put("check_records_visible", links.checkRecordsVisible());
        }
        if (access.full()) {
            ownerFacts(dto, e, delivery);
            // Sahip takımlar (nöbetçi bölümü — AlertOwnership; bağımsız alarmda yalnız damga) + fırtına bağı
            com.sitemonitor.model.CertificateInventory row = firstInventory(inv, e.getDomain());
            dto.put("owner_team_ids", new ArrayList<>(AlertOwnership.ownerTeamIds(e,
                    row == null ? null : row.getTeamId(), row == null ? null : row.getUgTeamId())));
            dto.put("storm_id", e.getStormId());
        }
        return dto;
    }

    private static com.sitemonitor.model.CertificateInventory firstInventory(
            Map<String, List<com.sitemonitor.model.CertificateInventory>> inv, String domain) {
        if (inv == null || domain == null) return null;
        List<com.sitemonitor.model.CertificateInventory> rows = inv.get(domain);
        return rows == null || rows.isEmpty() ? null : rows.get(0);
    }

    /** Tekrar sayımının anahtarı (alan adı + tür) — ayırıcı alan adında görünmeyen bir karakter. */
    private static String recurrenceKey(String domain, String type) {
        return String.valueOf(domain) + '\u0001' + String.valueOf(type);
    }

    /**
     * "7 günde N kez" (2026-11-13): sayfadaki (alan adı, tür) imzalarının son {@value #RECUR_DAYS} günde açılma sayısı — TEK
     * gruplu sorgu (Alarm Geçmişi'nin tekrar sayımıyla aynı). Kapsam sorusu yoktur: yalnız ekrandaki olayların imzası sayılır.
     * Sorgu düşerse boş (rozet çizilmez — uydurma sayı yok).
     */
    private Map<String, Long> recurrenceFor(List<AlertEvent> events) {
        Set<String> domains = new HashSet<>();
        for (AlertEvent e : events) if (e.getDomain() != null) domains.add(e.getDomain());
        Map<String, Long> out = new HashMap<>();
        if (domains.isEmpty()) return out;
        try {
            String since = ISO.format(Instant.now().minus(java.time.Duration.ofDays(RECUR_DAYS)));
            List<Object[]> rows = alertEventRepo.countRecentByDomainAndType(domains, since);
            if (rows != null) for (Object[] r : rows)
                out.put(recurrenceKey(r[0] == null ? null : r[0].toString(), r[1] == null ? null : r[1].toString()),
                        ((Number) r[2]).longValue());
        } catch (Exception ex) {
            log.debug("Olay tekrar sayıları alınamadı: {}", ex.toString());
        }
        return out;
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
        // Gerekçeler + tekrar hatırlatma (2026-11-13) — sahiplenen gibi takım içi bilgi: yalnız kendi / 7/24 satırında
        dto.put("acknowledged_note", e.getAcknowledgedNote());
        dto.put("resolved_note", e.getResolvedNote());
        dto.put("realert_count", e.getRealertCount());
        dto.put("last_re_alert_at", e.getLastReAlertAt());
        if (e.getNocSentAt() != null) {
            dto.put("noc_sent_at", e.getNocSentAt());
            dto.put("noc_via_storm", Boolean.TRUE.equals(e.getNocViaStorm()));
        }
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
        if (nocAlertFacts != null) nocAlertFacts.decorate(mine);   // noc_sent_at / noc_via_storm — tek sorgu (2026-11-13)
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
        // İçerik değişimi (kw 2026-11-05): sayfa erişilebilir, içerik temelden farklı — kesinti değil, içerik olayı.
        else if ("KEYWORD_CHANGED".equals(type))  { code = "CHANGED"; cat = "content"; }
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
        // Veritabanı sorgusu (2026-10-31): sorgu çalıştırılamadı = kesinti; kural ihlali = içerik / koşul sorunu (iş
        // alarmı). DBQUERY_SLOW yukarıdaki `_SLOW` kuralına takılır.
        else if ("DBQUERY_FAIL".equals(type))     { code = "QUERY";     cat = "down"; }
        else if ("DBQUERY_THRESHOLD".equals(type)) { code = "THRESHOLD"; cat = "content"; }
        else { code = type; cat = "unknown"; }
        return Map.of("code", code, "category", cat);
    }

    // ── Monitör çözümleme (AlertEvent.domain → ad + tip + tab + monitor_id) — N+1'siz ──
    /**
     * İzleme çözümlemesi (2026-11-13, takım duyarlı): aynı URL / host / ad'ı birden çok takım izleyebilir — olay DAMGALI
     * takımın izlemesine bağlanır, başka takımın izlemesi ASLA seçilmez (damgalı takımın izlemesi yoksa bağlantı yok, ad =
     * alan adı). Damgasız olay (envanter türevi Port/DNS, sertifika) envanterin SY/UG takımının izlemesini, o da yoksa ilk
     * adayı (kimlik sırası — eski davranış) alır. Okuma DAR: {@link com.sitemonitor.service.MonitorRefResolver#candidates}
     * yalnız sayfadaki anahtarları {@code IN} ile okur (eskiden her istekte aile başına {@code findAll()} — k6 betikleri,
     * dbquery SQL'i dahil tam varlıklar). Çözümleyici yokken (dilimli test) eski tam liste yolu, aynı seçim kuralıyla.
     */
    private Map<Long, Map<String, Object>> resolveMonitors(List<AlertEvent> events,
                                                           Map<String, List<com.sitemonitor.model.CertificateInventory>> inv) {
        return resolveMonitors(events, inv, null);
    }

    /** {@code teamsOut} (null olabilir): seçilen izleme SATIRININ takımı, olay kimliğine göre — kontrol kaydı kapsamı için. */
    private Map<Long, Map<String, Object>> resolveMonitors(List<AlertEvent> events,
                                                           Map<String, List<com.sitemonitor.model.CertificateInventory>> inv,
                                                           Map<Long, Long> teamsOut) {
        Map<Long, Map<String, Object>> byEvent = new HashMap<>();
        if (events.isEmpty()) return byEvent;
        Map<String, Set<String>> keys = new HashMap<>();
        for (AlertEvent e : events) {
            String fam = family(e.getAlertType());
            if (!"cert".equals(fam) && e.getDomain() != null) keys.computeIfAbsent(fam, k -> new HashSet<>()).add(e.getDomain());
        }
        Map<String, Map<String, List<Object[]>>> idx = monitorRefs != null ? monitorRefs.candidates(keys) : null;
        if (idx == null) idx = legacyCandidates(keys.keySet());
        for (AlertEvent e : events) {
            String fam = family(e.getAlertType());
            Map<String, List<Object[]>> byKey = idx.get(fam);
            Object[] ref = byKey == null ? null
                    : pickMonitor(byKey.get(e.getDomain()), contextMonitorId(e), e.getTeamId(), inventoryTeams(inv, e.getDomain()));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", ref != null ? ref[0] : e.getDomain());
            m.put("type", fam);
            m.put("tab",  tabFor(fam));
            m.put("monitor_id", ref != null ? ref[1] : null);
            byEvent.put(e.getId(), m);
            if (teamsOut != null && ref != null && ref.length > 2 && ref[2] instanceof Number n) teamsOut.put(e.getId(), n.longValue());
        }
        return byEvent;
    }

    /**
     * Aday seçimi — paket görünür (birim testi). {@code cands}: kimlik sıralı {@code [ad, id, teamId]}. Damgalı olay yalnız
     * kendi takımının izlemesini alır; damgasız olay envanter takımının izlemesini, o yoksa ilk adayı.
     */
    static Object[] pickMonitor(List<Object[]> cands, Long stampedTeam, Set<Long> inventoryTeams) {
        return pickMonitor(cands, null, stampedTeam, inventoryTeams);
    }

    /**
     * Önce alarm bağlamının {@code monitor_id}'si (aynı anahtarlı aday arasında KESİN eşleşme — izleme takım değiştirse de
     * olayı açan izleme odur), sonra takım kuralı (2026-11-13 inceleme: damgalı takımın izlemesi taşınmışsa bağ kayboluyordu).
     */
    static Object[] pickMonitor(List<Object[]> cands, Long contextMonitorId, Long stampedTeam, Set<Long> inventoryTeams) {
        if (cands == null || cands.isEmpty()) return null;
        if (contextMonitorId != null) {
            for (Object[] c : cands) if (contextMonitorId.equals(c[1] instanceof Number n ? Long.valueOf(n.longValue()) : null)) return c;
        }
        if (stampedTeam != null) {
            for (Object[] c : cands) if (stampedTeam.equals(c[2])) return c;
            return null;
        }
        if (inventoryTeams != null && !inventoryTeams.isEmpty()) {
            for (Object[] c : cands) if (c[2] != null && inventoryTeams.contains(c[2])) return c;
        }
        return cands.get(0);
    }

    /** Alarm bağlamındaki izleme kimliği (sayı ya da sayısal metin); yoksa / okunamazsa null. */
    private Long contextMonitorId(AlertEvent e) {
        try {
            Map<String, Object> ctx = deserialize(e.getContextJson());
            if (ctx == null) return null;
            Object v = ctx.get("monitor_id");
            if (v instanceof Number n) return Long.valueOf(n.longValue());
            if (v instanceof String s && s.matches("\\d{1,18}")) return Long.valueOf(s);
        } catch (Exception ignored) { /* bağlam yok / bozuk */ }
        return null;
    }

    private static Set<Long> inventoryTeams(Map<String, List<com.sitemonitor.model.CertificateInventory>> inv, String domain) {
        if (inv == null || domain == null) return Set.of();
        Set<Long> out = new HashSet<>();
        for (com.sitemonitor.model.CertificateInventory i : inv.getOrDefault(domain, List.of())) {
            if (i.getTeamId() != null) out.add(i.getTeamId());
            if (i.getUgTeamId() != null) out.add(i.getUgTeamId());
        }
        return out;
    }

    /** Çözümleyici yokken (dilimli test) eski tam liste okuması — aynı aday biçimi; silinmiş Port/DNS satırı girmez. */
    private Map<String, Map<String, List<Object[]>>> legacyCandidates(Set<String> fams) {
        Map<String, Map<String, List<Object[]>>> idx = new HashMap<>();
        if (fams.contains("http"))    idx.put("http",    index(httpMonitorRepo.findAll(),    m -> m.getUrl(),    m -> m.getName(), m -> m.getId(), m -> m.getTeamId()));
        if (fams.contains("port"))    idx.put("port",    index(portMonitorRepo.findAll().stream().filter(m -> m.getDeletedAt() == null).toList(),    m -> m.getHost(),   m -> m.getName(), m -> m.getId(), m -> m.getTeamId()));
        if (fams.contains("keyword")) idx.put("keyword", index(keywordMonitorRepo.findAll(), m -> m.getUrl(),    m -> m.getName(), m -> m.getId(), m -> m.getTeamId()));
        if (fams.contains("ping"))    idx.put("ping",    index(pingMonitorRepo.findAll(),    m -> m.getHost(),   m -> m.getName(), m -> m.getId(), m -> m.getTeamId()));
        if (fams.contains("dns"))     idx.put("dns",     index(dnsMonitorRepo.findAll().stream().filter(m -> m.getDeletedAt() == null).toList(),     m -> m.getDomain(), m -> m.getName(), m -> m.getId(), m -> m.getTeamId()));
        if (fams.contains("domain"))  idx.put("domain",  index(domainMonitorRepo.findAll(),  m -> m.getDomain(), m -> m.getName(), m -> m.getId(), m -> m.getTeamId()));
        if (fams.contains("page"))     idx.put("page",     index(pageMonitorRepo.findAll(),     m -> m.getUrl(),  m -> m.getName(), m -> m.getId(), m -> m.getTeamId()));
        if (fams.contains("scripted")) idx.put("scripted", index(scriptedMonitorRepo.findAll(), m -> m.getName(), m -> m.getName(), m -> m.getId(), m -> m.getTeamId()));
        // PAGESPEED alarmlari AlertEvent.domain'e m.getUrl() yaziyor (SchedulerService.addPageSpeedSweepItems)
        if (fams.contains("pagespeed")) idx.put("pagespeed", index(pageSpeedMonitorRepo.findAll(), m -> m.getUrl(), m -> m.getName(), m -> m.getId(), m -> m.getTeamId()));
        // Veritabanı sorgusu: alarm anahtarı izleme ADI (k6 emsali).
        if (fams.contains("dbquery") && dbQueryMonitorRepo != null)
            idx.put("dbquery", index(dbQueryMonitorRepo.findAll(), m -> m.getName(), m -> m.getName(), m -> m.getId(), m -> m.getTeamId()));
        return idx;
    }

    private static <T> Map<String, List<Object[]>> index(List<T> rows, Function<T, String> key, Function<T, String> name,
                                                         Function<T, Long> id, Function<T, Long> team) {
        Map<String, List<Object[]>> m = new HashMap<>();
        List<T> sorted = new ArrayList<>(rows);
        sorted.sort(java.util.Comparator.comparing(id, java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())));
        for (T r : sorted) {
            String k = key.apply(r);
            if (k != null) m.computeIfAbsent(k, x -> new ArrayList<>()).add(new Object[]{ name.apply(r), id.apply(r), team.apply(r) });
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
        if (type.startsWith("DBQUERY_"))  return "dbquery";
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
            case "dbquery" -> "dbquery";
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
        // Kapsam listesiyle (listeyle aynı): damgası kapsam dışı olayın envanteri de okunur — Alarm Geçmişi bağlantı kararı
        // envanter SY / UG kolunu ister. Diğer alanlar değişmez (damgalı / bağımsız olayda envanter kullanılmaz).
        Map<String, List<com.sitemonitor.model.CertificateInventory>> inv = inventoryFor(one, ownScope(session));
        TeamInfo teams = resolveTeams(one, inv);
        boolean notify = SessionScope.isGlobalViewer(session) || incidentOwnedBy(ev, SessionScope.viewTeamIds(session));
        Map<Long, Long> monitorTeams = new HashMap<>();
        Map<Long, Map<String, Object>> monitors = resolveMonitors(one, inv, monitorTeams);
        RowLinks links = new RowLinks(checkRecordsVisible(monitorTeams.get(ev.getId()), checkRecordScope(session)),
                seesAllAlerts(session) || alertReadable(ev, inv, SessionScope.viewTeamIdsWithGrants(session)));
        return ok(Map.of("data", toDto(ev, monitors, commentCounts(one), teams,
                accessFor(session).forRow(owned, notify), deliveryFor(one, e -> owned || nocCommenter(session)), inv,
                recurrenceFor(one), links)));
    }

    // ── Özet (2026-11-13) ─────────────────────────────────────────────────────────
    /** Özet belleği — dakikalık yoklama aynı kapsam için DB'ye her seferinde binmesin; {@code fresh=1} atlar (en sık 5 sn). */
    private final com.sitemonitor.util.TtlMemo<Map<String, Object>> summaryMemo = new com.sitemonitor.util.TtlMemo<>(500);
    @org.springframework.beans.factory.annotation.Value("${site.monitor.incidents.summary-cache-ms:15000}")
    long summaryCacheMs = 15_000L;
    /** MTTA / MTTR örneklem tavanı (satır) — dar izdüşüm, en yeni önce. */
    static final int TIMING_CAP = 20_000;

    /**
     * Olaylar özeti (2026-11-13, kullanıcı onaylı zenginleştirme): AÇIK / ONAYLI / KRİTİK / TAKIMSIZ / TAKIMIM sayıları KESİN
     * (eskiden ilk 200 açık satırdan türetiliyordu), son 24 saatte ÇÖZÜLEN (çözülme anına göre — eskiden açılışa bakıyordu),
     * MTTA / MTTR (24 sa ve 7 gün; sessiz kapanışlar MTTR'a girmez — Alarm Gürültüsü ile aynı kural), en uzun süren açık
     * olay ve takım süzgeci seçenekleri (kapsamdaki olayların takımları, adlarıyla). Kapsam listeyle aynı ({@code scope}).
     * Gruplu sorgular, satır başına sorgu yok; kapsam anahtarı başına {@code site.monitor.incidents.summary-cache-ms}
     * (15 sn) bellek. Bir bölüm okunamazsa o alan {@code null} (bilinmiyor — uydurma sıfır yok).
     */
    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> summary(
            @RequestParam(defaultValue = SCOPE_MINE) String scope,
            @RequestParam(defaultValue = "false") boolean fresh,
            HttpSession session) {
        permissionService.require(session, "alerts.read", "view");
        List<Long> own = ownScope(session);
        boolean orgWide = orgWideReader(session);
        String eff = orgWide ? normalizeScope(scope) : SCOPE_MINE;
        ScopeQuery sq = scopeQuery(eff, own);
        Long primary = SessionScope.primaryTeamId(session);
        String key = eff + '|' + com.sitemonitor.util.TtlMemo.scopeKey(own == null, own) + "|p" + primary;
        Map<String, Object> data = summaryMemo.get(key, summaryCacheMs, allowFresh(key, fresh), () -> computeSummary(sq, primary));
        Map<String, Object> out = new LinkedHashMap<>(data);
        out.put("scope", eff);
        return ok(Map.of("data", out));
    }

    /** `fresh` en çok bu aralıkta bir hesaplatır (anahtar başına; İzleme Panosu deseni) — Yenile düğmesi DB'yi dövemez. */
    static final long SUMMARY_FRESH_MIN_MS = 5_000L;
    private final Map<String, Long> summaryFreshAt = new java.util.concurrent.ConcurrentHashMap<>();

    /** Taze hesap izni: son taze hesaptan {@link #SUMMARY_FRESH_MIN_MS} geçmediyse önbellek döner. */
    boolean allowFresh(String key, boolean fresh) {
        if (!fresh) return false;
        long now = System.currentTimeMillis();
        if (summaryFreshAt.size() > 1000) summaryFreshAt.clear();
        Long last = summaryFreshAt.get(key);
        if (last != null && now - last < SUMMARY_FRESH_MIN_MS) return false;
        summaryFreshAt.put(key, now);
        return true;
    }

    private Map<String, Object> computeSummary(ScopeQuery sq, Long primary) {
        Map<String, Object> d = new LinkedHashMap<>();
        Instant now = Instant.now();
        String since24 = ISO.format(now.minus(java.time.Duration.ofHours(24)));
        String since7 = ISO.format(now.minus(java.time.Duration.ofDays(7)));
        if (sq == null) {   // kapsamsız "Takımımın olayları": bilinen BOŞ küme (bilinmiyor değil)
            d.put("open", 0L); d.put("acknowledged", 0L); d.put("critical", 0L); d.put("teamless", 0L);
            d.put("mine", primary == null ? null : 0L); d.put("resolved_24h", 0L);
            for (String k : List.of("mtta_24h_minutes", "mtta_7d_minutes", "mttr_24h_minutes", "mttr_7d_minutes")) d.put(k, null);
            d.put("mtta_sample_7d", 0); d.put("mttr_sample_7d", 0); d.put("timing_capped", false);
            d.put("longest_open", null); d.put("team_options", List.of());
            return d;
        }
        Filters open = Filters.none().resolved(Boolean.FALSE);
        long openN = 0, ack = 0, critical = 0;
        for (Object[] row : alertEventRepo.countIncidentFacets(open.resolved(), null, null, null, null, null, false,
                AlertEventRepository.NO_LEVELS, null, null, false, null, sq.scoped(), sq.outside(), sq.ids())) {
            long n = ((Number) row[2]).longValue();
            openN += n;
            if (Boolean.TRUE.equals(row[1])) ack += n;
            if ("CRITICAL".equalsIgnoreCase(String.valueOf(row[0]))) critical += n;
        }
        d.put("open", openN);
        d.put("acknowledged", ack);
        d.put("critical", critical);
        d.put("teamless", count(open.onlyTeamless(), sq));
        d.put("mine", primary == null ? null : count(open.team(primary), sq));
        d.put("resolved_24h", count(Filters.none().resolved(Boolean.TRUE).resolvedFrom(since24), sq));
        timing(d, sq, since24, since7);
        d.put("longest_open", longestOpen(sq));
        d.put("team_options", teamOptions(sq));
        return d;
    }

    /** MTTA / MTTR — iki dar sorgu (açılışı 7 günde olanlar; çözülmesi 7 günde olanlar). Düşerse alanlar null. */
    private void timing(Map<String, Object> d, ScopeQuery sq, String since24, String since7) {
        try {
            org.springframework.data.domain.Pageable cap = PageRequest.of(0, TIMING_CAP);
            List<Object[]> resolvedRows = alertEventRepo.findIncidentTimingRows(Boolean.TRUE, null, null, since7, null, null, false,
                    AlertEventRepository.NO_LEVELS, null, null, false, null, sq.scoped(), sq.outside(), sq.ids(), cap);
            List<Object[]> openedRows = alertEventRepo.findIncidentTimingRows(null, since7, null, null, null, null, false,
                    AlertEventRepository.NO_LEVELS, null, null, false, null, sq.scoped(), sq.outside(), sq.ids(), cap);
            Mean mttr24 = new Mean(), mttr7 = new Mean(), mtta24 = new Mean(), mtta7 = new Mean();
            for (Object[] r : resolvedRows) {
                if (Boolean.TRUE.equals(r[4])) continue;   // sessiz kapanış: kurtarma değil (AlertNoiseService ile aynı)
                Instant c = parseStamp(r[0]), res = parseStamp(r[2]);
                if (c == null || res == null || res.isBefore(c)) continue;
                double min = (res.toEpochMilli() - c.toEpochMilli()) / 60_000.0;
                mttr7.add(min);
                if (String.valueOf(r[2]).compareTo(since24) >= 0) mttr24.add(min);
            }
            for (Object[] r : openedRows) {
                Instant c = parseStamp(r[0]), a = parseStamp(r[1]);
                if (c == null || a == null || a.isBefore(c)) continue;
                double min = (a.toEpochMilli() - c.toEpochMilli()) / 60_000.0;
                mtta7.add(min);
                if (String.valueOf(r[0]).compareTo(since24) >= 0) mtta24.add(min);
            }
            d.put("mtta_24h_minutes", mtta24.value());
            d.put("mtta_7d_minutes", mtta7.value());
            d.put("mttr_24h_minutes", mttr24.value());
            d.put("mttr_7d_minutes", mttr7.value());
            d.put("mtta_sample_7d", mtta7.n);
            d.put("mttr_sample_7d", mttr7.n);
            d.put("timing_capped", resolvedRows.size() >= TIMING_CAP || openedRows.size() >= TIMING_CAP);
        } catch (Exception ex) {
            log.debug("Olay özeti MTTA/MTTR okunamadı: {}", ex.toString());
            for (String k : List.of("mtta_24h_minutes", "mtta_7d_minutes", "mttr_24h_minutes", "mttr_7d_minutes",
                    "mtta_sample_7d", "mttr_sample_7d", "timing_capped")) d.put(k, null);
        }
    }

    /** Ortalama (dakika, bir ondalık); örnek yoksa null. */
    private static final class Mean {
        double sum; int n;
        void add(double v) { sum += v; n++; }
        Double value() { return n == 0 ? null : Math.round(sum / n * 10) / 10.0; }
    }

    /** Saklanan UTC damgası ({@code yyyy-MM-ddTHH:mm:ss}, isteğe bağlı kesir / Z) → an; okunamazsa null. */
    static Instant parseStamp(Object raw) {
        if (raw == null) return null;
        String v = raw.toString().trim();
        if (v.isEmpty()) return null;
        if (v.endsWith("Z")) v = v.substring(0, v.length() - 1);
        int dot = v.indexOf('.');
        if (dot > 0) v = v.substring(0, dot);
        try {
            return java.time.LocalDateTime.parse(v).toInstant(ZoneOffset.UTC);
        } catch (Exception e) {
            return null;
        }
    }

    /** En uzun süren AÇIK olay (en eski açılış) — kimlik + izleme adı + açılış. Düşerse null. */
    private Map<String, Object> longestOpen(ScopeQuery sq) {
        try {
            Page<AlertEvent> p = page(Filters.none().resolved(Boolean.FALSE), sq,
                    PageRequest.of(0, 1, Sort.by(Sort.Direction.ASC, "createdAt").and(Sort.by(Sort.Direction.ASC, "id"))));
            if (p.isEmpty()) return null;
            AlertEvent e = p.getContent().get(0);
            Map<String, Object> m = resolveMonitors(List.of(e), inventoryFor(List.of(e), null)).get(e.getId());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", e.getId());
            out.put("name", m == null ? e.getDomain() : m.get("name"));
            out.put("started_at", e.getCreatedAt());
            out.put("alert_level", e.getAlertLevel());
            return out;
        } catch (Exception ex) {
            log.debug("Olay özeti en uzun açık olay okunamadı: {}", ex.toString());
            return null;
        }
    }

    /**
     * Envanter satırının ({@code [damga, SY, UG, sayı]}) bu olaylara EK katkısı — liste süzgecinin kuralıyla: olay T takımında
     * damga ya da envanter SY / UG T ise listelenir; damga zaten damgalı sayımda, SY = UG tek sayılır. Açık: sorgu testi
     * (repository paketi) aynı kuralı kullanır.
     */
    public static java.util.Set<Long> inventoryOnlyTeams(Object[] r) {
        Long stamp = r[0] instanceof Number n ? Long.valueOf(n.longValue()) : null;
        java.util.Set<Long> out = new java.util.LinkedHashSet<>();
        for (int i = 1; i <= 2; i++) {
            if (r[i] instanceof Number n && !Long.valueOf(n.longValue()).equals(stamp)) out.add(n.longValue());
        }
        return out;
    }

    /** Takım seçeneklerinin geriye bakışı (gün) — açık olaylar her zaman sayılır. */
    static final int TEAM_OPTION_DAYS = 90;

    /**
     * Takım süzgeci seçenekleri — kapsamdaki olayların takımları (damga + alan adının envanter SY/UG takımı; liste süzgeciyle aynı kural),
     * adlarıyla, Türkçe abece sırasında. Ham kimlik çipi yok: adı okunamayan takım listeye girmez. Düşerse null.
     */
    private List<Map<String, Object>> teamOptions(ScopeQuery sq) {
        try {
            Map<Long, Long> counts = new HashMap<>();
            // Tüm geçmiş TARANMAZ (2026-11-13 inceleme): açık olaylar + son TEAM_OPTION_DAYS günde açılıp kapanmışlar
            // (iki ayrık dilim — açılış zamanı indeksiyle sınırlı).
            String recent = ISO.format(Instant.now().minus(java.time.Duration.ofDays(TEAM_OPTION_DAYS)));
            for (Object[] slice : new Object[][] { { Boolean.FALSE, null }, { Boolean.TRUE, recent } }) {
                Boolean res = (Boolean) slice[0];
                String since = (String) slice[1];
                for (Object[] r : alertEventRepo.countIncidentStampedTeams(res, since, null, null, null, false,
                        AlertEventRepository.NO_LEVELS, null, null, false, null, sq.scoped(), sq.outside(), sq.ids())) {
                    if (r[0] != null) counts.merge(((Number) r[0]).longValue(), ((Number) r[1]).longValue(), Long::sum);
                }
                for (Object[] r : alertEventRepo.countIncidentInventoryOwners(res, since, null, null, null, false,
                        AlertEventRepository.NO_LEVELS, null, null, false, null, sq.scoped(), sq.outside(), sq.ids())) {
                    for (Long team : inventoryOnlyTeams(r)) counts.merge(team, ((Number) r[3]).longValue(), Long::sum);
                }
            }
            if (counts.isEmpty()) return List.of();
            java.text.Collator col = java.text.Collator.getInstance(Locale.forLanguageTag("tr"));
            List<Map<String, Object>> out = new ArrayList<>();
            for (com.sitemonitor.model.Team tm : teamRepo.findAllById(counts.keySet())) {
                if (tm.getId() == null || tm.getName() == null) continue;
                Map<String, Object> o = new LinkedHashMap<>();
                o.put("id", tm.getId());
                o.put("name", tm.getName());
                o.put("count", counts.getOrDefault(tm.getId(), 0L));
                out.add(o);
            }
            out.sort((a, b) -> col.compare(String.valueOf(a.get("name")), String.valueOf(b.get("name"))));
            return out;
        } catch (Exception ex) {
            log.debug("Olay takım seçenekleri okunamadı: {}", ex.toString());
            return null;
        }
    }

    // ── Dışa aktarım (2026-11-13) ─────────────────────────────────────────────────
    /** Dışa aktarım satır tavanı. */
    static final int EXPORT_MAX_ROWS = 10_000;
    /** Okuma parçası — parça başına sabit sorgu sayısı. */
    static final int EXPORT_CHUNK = 1_000;
    /** Kişi başına dakikada dışa aktarım (pod belleği — yük sınırı). */
    static final int EXPORT_PER_MINUTE = 6;
    private final Map<String, java.util.Deque<Long>> exportCalls = new HashMap<>();
    private final Set<String> exportRunning = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** CSV sütunları — sıra sabit (Excel'i istemci JSON'dan aynı sırayla kurar). */
    static final List<String> EXPORT_COLUMNS = List.of("id", "status", "alert_level", "monitor", "monitor_type", "domain",
            "alert_type", "root_cause", "team", "started_at", "resolved_at", "duration_minutes", "acknowledged",
            "acknowledged_by", "resolved_by", "comment_count", "recurrence_7d", "message");

    /**
     * Olaylar dışa aktarımı — LİSTEYLE AYNI süzgeç + kapsam (ekranda ne varsa o), en çok {@value #EXPORT_MAX_ROWS} satır
     * ({@code X-Export-Truncated}). Kişi verisi maskesi listeyle AYNI ({@link #rowsFor}): sahiplenen / çözen kişi yalnız
     * kendi ya da 7/24 satırında. {@code format=csv} (varsayılan; BOM, formül koruması, İstanbul saati) | {@code json}
     * (istemci Excel'i kurar). Kişi başına aynı anda tek + dakikada {@value #EXPORT_PER_MINUTE} (429). Denetim
     * {@code INCIDENTS_EXPORT} (süzgeç + satır sayısı).
     */
    @GetMapping("/export")
    public ResponseEntity<?> export(
            @RequestParam(required = false) String format,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String rootCause,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String since,
            @RequestParam(required = false) String until,
            @RequestParam(defaultValue = "default") String sort,
            @RequestParam(defaultValue = "desc") String dir,
            @RequestParam(defaultValue = SCOPE_MINE) String scope,
            @RequestParam(required = false) String level,
            @RequestParam(required = false) Long teamId,
            @RequestParam(required = false) Boolean acknowledged,
            @RequestParam(defaultValue = "false") boolean teamless,
            @RequestParam(defaultValue = "false") boolean mine,
            @RequestParam(required = false) String resolvedSince,
            @RequestParam(defaultValue = "false") boolean recurring,
            HttpSession session, HttpServletRequest request) {
        permissionService.require(session, "alerts.read", "view");
        String fmt = format == null || format.isBlank() ? "csv" : format.trim().toLowerCase(Locale.ROOT);
        if (!"csv".equals(fmt) && !"json".equals(fmt))
            throw new com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException("format",
                    com.sitemonitor.util.Msg.t("Biçim csv ya da json olmalı.", "The format must be csv or json."));
        Filters f = filters(status, rootCause, q, since, until, level, teamId, acknowledged, teamless, mine,
                resolvedSince, recurring, session);
        String who = exportKey(session);
        if (!exportRunning.add(who)) {
            return tooMany("EXPORT_BUSY", 5L, com.sitemonitor.util.Msg.t(
                    "Önceki dışa aktarımınız hâlâ hazırlanıyor; kişi başına aynı anda tek dışa aktarım yapılabilir. Bitince yeniden deneyin.",
                    "Your previous export is still being prepared; one export per person can run at a time. Try again when it has finished."));
        }
        try {
            Long retry = reserveExport(who);
            if (retry != null) {
                return tooMany("RATE_LIMITED", retry, com.sitemonitor.util.Msg.t(
                        "Dışa aktarım sınırına ulaşıldı: kişi başına dakikada en çok " + EXPORT_PER_MINUTE + " dışa aktarım. "
                                + retry + " saniye sonra yeniden deneyin.",
                        "Export limit reached: at most " + EXPORT_PER_MINUTE + " exports per person per minute. Try again in "
                                + retry + " seconds."));
            }
            List<Long> own = ownScope(session);
            boolean orgWide = orgWideReader(session);
            String eff = orgWide ? normalizeScope(scope) : SCOPE_MINE;
            ScopeQuery sq = scopeQuery(eff, own);
            List<Map<String, Object>> rows = new ArrayList<>();
            long total = 0;
            if (sq != null) {
                Sort order = sortFor(sort, dir);
                for (int pg = 0; rows.size() < EXPORT_MAX_ROWS; pg++) {
                    Page<AlertEvent> p = page(f, sq, PageRequest.of(pg, EXPORT_CHUNK, order));
                    if (pg == 0) total = p.getTotalElements();
                    rows.addAll(rowsFor(p.getContent(), own, session));
                    if (!p.hasNext() || p.getContent().isEmpty()) break;
                }
                if (rows.size() > EXPORT_MAX_ROWS) rows = new ArrayList<>(rows.subList(0, EXPORT_MAX_ROWS));
            }
            boolean truncated = total > rows.size();
            try {
                auditService.recordAction("INCIDENTS_EXPORT", session, request, "ALERT_EVENT", "export",
                        com.sitemonitor.service.AuditDetail.of("format", fmt, "rows", rows.size(), "total", total,
                                "truncated", truncated, "scope", eff, "status", status, "root_cause", rootCause,
                                "level", level, "team_id", teamId, "q", q, "since", since, "until", until));
            } catch (Exception e) {
                log.debug("INCIDENTS_EXPORT denetimi yazılamadı: {}", e.toString());
            }
            org.springframework.http.HttpHeaders h = new org.springframework.http.HttpHeaders();
            h.setCacheControl("no-store");
            h.set("X-Export-Truncated", String.valueOf(truncated));
            h.set("X-Export-Max-Rows", String.valueOf(EXPORT_MAX_ROWS));
            if ("json".equals(fmt)) {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("rows", rows);
                data.put("row_count", rows.size());
                data.put("total", total);
                data.put("max_rows", EXPORT_MAX_ROWS);
                data.put("truncated", truncated);
                data.put("scope", eff);
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("success", true);
                body.put("data", data);
                return ResponseEntity.ok().headers(h).body(body);
            }
            StringBuilder sb = new StringBuilder();
            sb.append('﻿').append(com.sitemonitor.util.Csv.row(EXPORT_COLUMNS.toArray()));
            for (Map<String, Object> r : rows) sb.append(csvRow(r));
            h.setContentType(org.springframework.http.MediaType.parseMediaType("text/csv;charset=UTF-8"));
            h.setContentDisposition(org.springframework.http.ContentDisposition.attachment()
                    .filename("olaylar-" + java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmm").withZone(TZ).format(Instant.now()) + ".csv")
                    .build());
            return ResponseEntity.ok().headers(h).body(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } finally {
            exportRunning.remove(who);
        }
    }

    /** CSV satırı — {@link #EXPORT_COLUMNS} sırasıyla; zamanlar İstanbul yerel, süre dakika. */
    @SuppressWarnings("unchecked")
    private static String csvRow(Map<String, Object> r) {
        Map<String, Object> mon = r.get("monitor") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        Map<String, Object> rc = r.get("root_cause") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        Instant start = parseStamp(r.get("started_at"));
        Instant end = parseStamp(r.get("resolved_at"));
        Long minutes = start == null ? null
                : Math.max(0L, ((end != null ? end : Instant.now()).toEpochMilli() - start.toEpochMilli()) / 60_000L);
        return com.sitemonitor.util.Csv.row(r.get("id"), r.get("status"), r.get("alert_level"), mon.get("name"), mon.get("type"),
                r.get("domain"), r.get("alert_type"), rc.get("code"), r.get("team_name"), local(start), local(end), minutes,
                r.get("acknowledged"), r.get("acknowledged_by"), r.get("resolved_by"), r.get("comment_count"),
                r.get("recurrence_7d"), r.get("message"));
    }

    private static String local(Instant i) {
        return i == null ? null : java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(TZ).format(i);
    }

    private static String exportKey(HttpSession session) {
        Object u = session == null ? null : session.getAttribute("username");
        return u != null ? "u:" + u : "s:" + (session == null ? "-" : session.getId());
    }

    /** Dakikalık hak — dolu ise kalan saniye, yoksa null (hak düşülür). */
    private Long reserveExport(String who) {
        long now = System.currentTimeMillis();
        synchronized (exportCalls) {
            if (exportCalls.size() > 1000) exportCalls.clear();
            java.util.Deque<Long> q = exportCalls.computeIfAbsent(who, k -> new java.util.ArrayDeque<>());
            while (!q.isEmpty() && now - q.peekFirst() >= 60_000L) q.pollFirst();
            if (q.size() >= EXPORT_PER_MINUTE) return Math.max(1L, (60_000L - (now - q.peekFirst()) + 999) / 1000);
            q.addLast(now);
            return null;
        }
    }

    private static ResponseEntity<Map<String, Object>> tooMany(String code, long retry, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("code", code);
        body.put("error", message);
        body.put("retry_after_seconds", retry);
        String rid = org.slf4j.MDC.get("requestId");
        if (rid != null) body.put("request_id", rid);
        return ResponseEntity.status(429).header(org.springframework.http.HttpHeaders.RETRY_AFTER, String.valueOf(retry)).body(body);
    }

    // ── Yorumlar ─────────────────────────────────────────────────────────────────
    /**
     * Yorum dizisi — tekil olayla aynı okuma kapısı (başka ekibin olayında da okunur; yazma kapıları ayrı). Her yorum
     * {@code can_delete} taşır (2026-11-13): silme ucunun kuralıyla AYNI — yazar ya da olayın yönetim kapsamı, ve çağıran
     * silme ucunun kapısından geçer (7/24 operatörü okuyabildiği olayda; diğerleri alerts.actions + kendi kapsamı).
     */
    @GetMapping("/{id}/comments")
    public ResponseEntity<Map<String, Object>> listComments(@PathVariable Long id, HttpSession session) {
        permissionService.require(session, "alerts.read", "view");
        AlertEvent ev = requireAlert(id);
        requireIncidentReadable(session, ev);
        boolean noc = nocCommenter(session);
        boolean gate = noc || (permissionService.allows(session, "alerts.actions", "execute") && ownsIncident(session, ev));
        boolean manage = gate && canManageIncident(session, ev);
        String user = session == null ? null : (String) session.getAttribute("username");
        List<Map<String, Object>> out = new ArrayList<>();
        for (AlertComment c : commentRepo.findByAlertEventIdAndDeletedAtIsNullOrderByCreatedAtAsc(id)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.getId());
            m.put("alert_event_id", c.getAlertEventId());
            m.put("body", c.getBody());
            m.put("team_id", c.getTeamId());
            m.put("author_username", c.getAuthorUsername());
            m.put("author_name", c.getAuthorName());
            m.put("created_at", c.getCreatedAt());
            m.put("updated_at", c.getUpdatedAt());
            boolean own = user != null && user.equals(c.getAuthorUsername());
            m.put("can_delete", gate && (own || manage));
            out.add(m);
        }
        return ok(Map.of("data", out));
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
     * YAZMA kapısının takım kapsamı (IDOR): global viewer serbest; aksi halde olayın SAHİBİ takımı (teamId; envanter gibi
     * yönlenen olayda domain→envanter SY/UG — {@link AlertOwnership}) görüş kapsamında olmalı. Org geneli okuma ayarını
     * BİLEREK sormaz — başka ekibin olayını okuyabilmek ona yorum yazma/silme hakkı vermez (2026-09-28). Bağımsız izleme
     * olayında envanter kolu yok (2026-10-09): host'un sertifikasını tutan takım başka takımın Ping olayına yazamaz.
     */
    private void requireIncidentScope(HttpSession session, AlertEvent ev) {
        if (!ownsIncident(session, ev))
            throw new SecurityException("Bu incident üzerinde yetkiniz yok");
    }

    /** Olay çağıranın KENDİ (sahip olduğu) kapsamında mı (global görüntüleyici → her olay). Liste bayrağı {@code can_manage} ile aynı kural. */
    private boolean ownsIncident(HttpSession session, AlertEvent ev) {
        if (SessionScope.isGlobalViewer(session)) return true;
        return incidentOwnedBy(ev, SessionScope.viewTeamIds(session))
                || incidentOwnedBy(ev, SessionScope.grantExecuteTeamIds(session));   // erişim paylaşımı EXECUTE+ (2026-10-12)
    }

    /**
     * OKUMA kapısı (tekil olay + yorum dizisi): kendi kapsamı, liste sorgusunun "Takımımın olayları" kümesi
     * ({@link #incidentTeamInScope} — envanter kolu bağımsız olayda da) YA DA org geneli salt okunur okuma (ayar açık).
     * Aksi halde bugünkü gibi 403. Dönüş: olay çağıranın SAHİP olduğu kapsamda mı (satır bayrakları için) — listede
     * görünen başka takımın bağımsız olayı açılır ama salt okunurdur.
     */
    private boolean requireIncidentReadable(HttpSession session, AlertEvent ev) {
        if (ownsIncident(session, ev)) return true;
        if (incidentTeamInScope(ev, SessionScope.viewTeamIdsWithGrants(session)) || orgWideReader(session)) return false;
        throw new SecurityException("Bu incident üzerinde yetkiniz yok");
    }

    /**
     * Olay verilen kapsamdaki bir takıma AİT mi (YAZMA / yönetim, 2026-10-09) — {@link AlertOwnership}: damgalı
     * {@code teamId}; envanter gibi yönlenen olayda (sertifika, ACCESSIBILITY, envanter türevi Port/DNS) ayrıca
     * domain→envanter SY/UG. Bağımsız izleme olayında envanter OKUNMAZ.
     */
    private boolean incidentOwnedBy(AlertEvent ev, List<Long> scope) {
        if (scope == null || scope.isEmpty()) return false;
        if (ev.getTeamId() != null && scope.contains(ev.getTeamId())) return true;
        if (!AlertOwnership.routesLikeInventory(ev)) return false;
        return incidentTeamInScope(ev, scope);
    }

    /**
     * Incident'ın takimi verilen kapsamda mi — OKUMA kuralı (liste sorgusu {@code INCIDENTS_FILTER} ile aynı). Damgalanmis
     * {@code teamId} önce; yoksa envanter kolu (envanter-türevi izlemelerde alarm takimsiz açılabiliyor). Yazma kapıları
     * {@link #incidentOwnedBy}'ı kullanır.
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
                || incidentOwnedBy(ev, SessionScope.manageTeamIds(session));
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
