package com.sitemonitor.service.activity;

import com.sitemonitor.controller.MonitoringController;
import com.sitemonitor.controller.SessionScope;
import com.sitemonitor.model.ActivityLog;
import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.MonitorChangeLog;
import com.sitemonitor.model.ScriptedMonitor;
import com.sitemonitor.repository.ActivityLogRepository;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.service.ActivityLogService;
import com.sitemonitor.service.AlertOwnership;
import com.sitemonitor.service.CertificateService;
import com.sitemonitor.service.EscalationService;
import com.sitemonitor.service.MonitorTypeCatalog;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.ScriptedCheckerService;
import com.sitemonitor.service.SecretMask;
import com.sitemonitor.service.noc.NocCallLogService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Aktivite ayrıntı sayfasının verisi ({@code GET /api/activity/{id}/detail}, 2026-11-05, aktivite zenginleştirmesi A–C).
 *
 * <p>Satırın kendisine ek: türün kontrol satırı ({@code check} — {@code check_id} varsa TAM, yoksa aynı izlemenin ± 120 sn
 * içindeki en yakın kontrolü YAKLAŞIK), aynı izlemenin bir önceki / sonraki kontrolü, ± 24 saatlik kontrol serisi (aktivite
 * satırlarından), ilgili alarm (satırdaki {@code alert_event_id} TAM; hata / zaman aşımı kontrolünde aynı izlemenin alarmı
 * YAKLAŞIK), değişiklik kaydı bağı ({@code change_seq}; eski satırda ± 120 sn yaklaşık) ve derin bağlantılar.
 *
 * <p><b>Biçim tek kaynaktan:</b> kontrol satırı her türün VAR OLAN geçmiş ucundaki satır biçimidir (uygulamanın JSON eşleyicisi;
 * sertifikada {@link CertificateService#historyRow}, sentetikte {@link MonitoringController#scriptedHistoryRow} + gizli
 * değer maskesi, sorgu izlemesinde {@link MonitoringController#dbqCheckRow}) — ön yüz {@code checkFailureModel.failureOf}
 * ile okur. Kişi verisi DÖNMEZ: alarm zinciri (bildirimler, push, fırtına) var olan alarm uçlarından okunur (maskeleme orada).
 *
 * <p><b>Maliyet sabit:</b> satır + takım adı + kontrol (kendisi, önceki, sonraki) + seri (1) + alarm (≤ 2) + değişiklik (≤ 1);
 * {@code activity_log}'da tam tarama yok (seri {@code idx_act_type_mon_time} ya da zaman dizini üzerinde ± 24 saat).
 */
@Slf4j
@Service
public class ActivityDetailService {

    /** Kontrol eşlemesinde yaklaşık pencere (sn). */
    static final int APPROX_WINDOW_S = 120;
    /** Hata kontrolünden sonra açılan alarmın sayıldığı pencere (sn). */
    static final int ALARM_AFTER_S = 600;
    /** Seri penceresi (± saat). */
    static final int SERIES_HOURS = 24;
    /** Seride en çok nokta (aşarsa eşit zaman kovaları). */
    static final int SERIES_MAX_POINTS = 300;
    /** Seri için okunan en çok aktivite satırı (aşarsa {@code truncated}). */
    static final int SERIES_MAX_ROWS = 6000;   // 30 sn aralıklı izlemenin 48 saati (5760) sığar
    /** Yaklaşık eşlemede okunan en çok aday. */
    private static final int APPROX_CANDIDATES = 20;
    /** Önceki / sonraki kontrolün arandığı pencere (her iki yönde, gün) — duraklatılmış izlemede tablonun sonuna yürünmez. */
    static final int NEIGHBOUR_WINDOW_DAYS = 7;
    /** Komşu aramasında okunan aday (aynı saniyedeki satırlar Java'da ayıklanır; inceleme Y1). */
    static final int NEIGHBOUR_FETCH = 16;
    /** Kontrol tablosu okumalarının sorgu zaman aşımı (ms) — uzun tarama havuzu tutmasın (inceleme Y1). */
    static final int QUERY_TIMEOUT_MS = 5000;
    static final String TIMEOUT_HINT = "jakarta.persistence.query.timeout";

    static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    /** Yaklaşık alarm aranan kontrol durumları (WARNING: alarm açan uyarı sınıfı kontrol hataları dahil). */
    static final Set<String> FAILING_STATUSES = Set.of("ERROR", "TIMEOUT", "WARNING");
    /** Alarm olayı eylemleri ({@code alert_info} taşır). */
    static final Set<String> ALERT_ACTIONS = Set.of(ActivityLogService.ACTION_ALERT_TRIGGERED,
            ActivityLogService.ACTION_ALERT_ESCALATED, ActivityLogService.ACTION_ALERT_ACKNOWLEDGED,
            ActivityLogService.ACTION_ALERT_RESOLVED);

    /** Kontrol satırı taşıyan eylemler. */
    static final Set<String> CHECK_ACTIONS =
            Set.of(ActivityLogService.ACTION_SCHEDULED_CHECK, ActivityLogService.ACTION_MANUAL_CHECK);
    /** Değişiklik kaydı taşıyabilen eylemler (eski satırda yaklaşık eşleme). */
    static final Set<String> CHANGE_ACTIONS = Set.of("CREATED", ActivityLogService.ACTION_CONFIG_CHANGED, "DELETED",
            ActivityLogService.ACTION_PAUSED, ActivityLogService.ACTION_RESUMED);
    /** Uçtan uca tanılaması olan türler (dbquery'de yok). */
    static final Set<String> DIAGNOSABLE = Set.of(ActivityLogService.HTTP, ActivityLogService.KEYWORD, ActivityLogService.PING,
            ActivityLogService.PORT, ActivityLogService.DNS, ActivityLogService.PAGE, ActivityLogService.PAGESPEED,
            ActivityLogService.SCRIPTED, ActivityLogService.DOMAIN, ActivityLogService.CERT, ActivityLogService.UPTIME);
    /** Tür → izleme sekmesi (ön yüz {@code utils/activityTarget.js} MONITOR_TABS ile aynı). */
    static final Map<String, String> MONITOR_TABS = Map.ofEntries(
            Map.entry(ActivityLogService.DOMAIN, "domain"), Map.entry(ActivityLogService.HTTP, "http"),
            Map.entry(ActivityLogService.PORT, "port"), Map.entry(ActivityLogService.PING, "ping"),
            Map.entry(ActivityLogService.DNS, "dns"), Map.entry(ActivityLogService.KEYWORD, "keyword"),
            Map.entry(ActivityLogService.PAGE, "page"), Map.entry(ActivityLogService.PAGESPEED, "pagespeed"),
            Map.entry(ActivityLogService.SCRIPTED, "scripted"), Map.entry(ActivityLogService.DBQUERY, "dbquery"));

    /**
     * Tür → kontrol varlığı (JPQL adı) + anahtar biçimi. Sabit metinler — kullanıcı girdisi sorguya HİÇ girmez (yalnız
     * bağlanan parametre).
     */
    enum CheckTable {
        PORT("PortCheck", com.sitemonitor.model.PortCheck.class, "port_checks", Key.MONITOR, "port"),
        PING("PingCheck", com.sitemonitor.model.PingCheck.class, "ping_checks", Key.MONITOR, "ping"),
        DNS("DnsRecord", com.sitemonitor.model.DnsRecord.class, "dns_records", Key.MONITOR, "dns"),
        HTTP("HttpCheck", com.sitemonitor.model.HttpCheck.class, "http_checks", Key.MONITOR, "http"),
        KEYWORD("KeywordResult", com.sitemonitor.model.KeywordResult.class, "keyword_results", Key.MONITOR, "keyword"),
        PAGE("PageCheck", com.sitemonitor.model.PageCheck.class, "page_checks", Key.MONITOR, "page"),
        PAGESPEED("PageSpeedCheck", com.sitemonitor.model.PageSpeedCheck.class, "pagespeed_checks", Key.MONITOR, "pagespeed"),
        SCRIPTED("ScriptedCheck", com.sitemonitor.model.ScriptedCheck.class, "scripted_checks", Key.MONITOR, "scripted"),
        DBQUERY("DbQueryCheck", com.sitemonitor.model.DbQueryCheck.class, "dbquery_checks", Key.MONITOR, "dbquery"),
        DOMAIN("DomainCheck", com.sitemonitor.model.DomainCheck.class, "domain_checks", Key.MONITOR, "domain"),
        CERT("CertificateCheck", com.sitemonitor.model.CertificateCheck.class, "certificate_checks", Key.DOMAIN, "cert"),
        UPTIME("UptimeCheck", com.sitemonitor.model.UptimeCheck.class, "uptime_checks", Key.DOMAIN_PORT, "uptime");

        final String entity;
        final Class<?> type;
        /** Fiziksel tablo (yerel komşu sorgusu; her birinde {@code (anahtar, checked_at)} bileşik dizini var). */
        final String table;
        final Key key;
        /** Ön yüzün {@code failureOf(type, row)} tür adı. */
        final String uiType;

        CheckTable(String entity, Class<?> type, String table, Key key, String uiType) {
            this.entity = entity;
            this.type = type;
            this.table = table;
            this.key = key;
            this.uiType = uiType;
        }

        static CheckTable of(String activityType) {
            if (activityType == null) return null;
            try {
                return valueOf(activityType.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }

        String keyClause() {
            return switch (key) {
                case MONITOR -> "c.monitorId = :k1";
                case DOMAIN -> "c.domain = :k1";
                case DOMAIN_PORT -> "c.domain = :k1 AND c.port = :k2";
            };
        }

        /** Yerel SQL anahtar koşulu. */
        String sqlKeyClause() {
            return switch (key) {
                case MONITOR -> "monitor_id = :k1";
                case DOMAIN -> "domain = :k1";
                case DOMAIN_PORT -> "domain = :k1 AND port = :k2";
            };
        }
    }

    /**
     * Önceki / sonraki kontrolün yerel SQL'i (inceleme Y1): koşul DİZİN DOSTU — {@code checked_at >= :at} (ya da
     * {@code <=}) + İKİ YÖNDE sınır ({@code :bound}, ± {@value #NEIGHBOUR_WINDOW_DAYS} gün) ve yalnız {@code checked_at}
     * sıralaması: {@code (anahtar, checked_at)} bileşik dizini koşulu da sırayı da taşır. Eski
     * {@code checkedAt > :at OR (checkedAt = :at AND id > :id)} dizin koşuluna dönüşmüyordu (tablonun en eski / en yeni
     * ucundan çapaya kadar filtreyle okuma). Aynı saniyedeki satırlar Java'da ayıklanır. {@code strict}: aynı saniyedeki
     * adaylar tükendiğinde tek satırlık katı arama. EXPLAIN kapısı {@code ActivityDetailPostgresTest}.
     */
    static String neighbourSql(CheckTable t, boolean before, boolean strict) {
        String op = before ? (strict ? "<" : "<=") : (strict ? ">" : ">=");
        String boundOp = before ? ">=" : "<=";
        String order = before ? "DESC" : "ASC";
        return "SELECT id, checked_at FROM " + t.table + " WHERE " + t.sqlKeyClause()
                + " AND checked_at " + op + " :at AND checked_at " + boundOp + " :bound"
                + " ORDER BY checked_at " + order + " LIMIT " + (strict ? 1 : NEIGHBOUR_FETCH);
    }

    enum Key { MONITOR, DOMAIN, DOMAIN_PORT }

    /** Kontrol satırının anahtarı (izleme kimliği ya da alan adı [+ port]). */
    record CheckKey(Object k1, Integer k2) {}

    private final ActivityLogRepository activityRepo;
    private final AlertEventRepository alertRepo;
    private final CertificateInventoryRepository inventoryRepo;
    private final TeamRepository teamRepo;
    private final PermissionService permissionService;
    private final ObjectProvider<CertificateService> certificateService;
    private final ObjectProvider<ScriptedCheckerService> scriptedChecker;
    private final ObjectProvider<NocCallLogService> nocCallLog;
    private final ObjectProvider<tools.jackson.databind.ObjectMapper> objectMapper;

    @PersistenceContext
    private EntityManager em;

    /** Uygulamanın eşleyicisi yoksa (dar test bağlamı) aynı adlandırmayla yerel eşleyici. */
    private static final tools.jackson.databind.ObjectMapper FALLBACK_MAPPER = tools.jackson.databind.json.JsonMapper.builder()
            .propertyNamingStrategy(tools.jackson.databind.PropertyNamingStrategies.SNAKE_CASE).build();

    public ActivityDetailService(ActivityLogRepository activityRepo, AlertEventRepository alertRepo,
                                 CertificateInventoryRepository inventoryRepo, TeamRepository teamRepo,
                                 PermissionService permissionService,
                                 ObjectProvider<CertificateService> certificateService,
                                 ObjectProvider<ScriptedCheckerService> scriptedChecker,
                                 ObjectProvider<NocCallLogService> nocCallLog,
                                 ObjectProvider<tools.jackson.databind.ObjectMapper> objectMapper) {
        this.activityRepo = activityRepo;
        this.alertRepo = alertRepo;
        this.inventoryRepo = inventoryRepo;
        this.teamRepo = teamRepo;
        this.permissionService = permissionService;
        this.certificateService = certificateService;
        this.scriptedChecker = scriptedChecker;
        this.nocCallLog = nocCallLog;
        this.objectMapper = objectMapper;
    }

    /** Test dikişi (elle kurulan servis). */
    void setEntityManager(EntityManager em) {
        this.em = em;
    }

    /** Saat (serinin üst sınırı "şimdi"yi aşmaz) — test dikişi. */
    private java.time.Clock clock = java.time.Clock.systemUTC();

    void setClock(java.time.Clock clock) {
        this.clock = clock;
    }

    /**
     * İzlemenin GÜNCEL takımını çözen zaman çizelgesi servisi ({@code resolve} kuralı — tek kaynak; inceleme O1). İsteğe
     * bağlı: yoksa (dar test bağlamı) güncel takım bilinmez → satırın takımı geçerli (bugünkü davranış).
     */
    private ActivityTimelineService timelines;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setTimelines(ActivityTimelineService timelines) {
        this.timelines = timelines;
    }

    /** Oturumun satır kapsamı (liste ucuyla aynı kural): {@code scoped=false} → tüm takımlar. */
    record ViewScope(boolean scoped, List<Long> list) {
        static ViewScope of(HttpSession session) {
            List<Long> view = SessionScope.seesAllMonitoring(session) ? null : SessionScope.viewTeamIdsWithGrants(session);
            if (view == null) return new ViewScope(false, List.of(-1L));
            List<Long> clean = view.stream().filter(java.util.Objects::nonNull).toList();
            return new ViewScope(true, clean.isEmpty() ? List.of(-1L) : clean);
        }
    }

    /**
     * İzlemenin güncel takımı görülebiliyor mu (inceleme O1 — izleme başka takıma taşındıysa eski takımın üyesi ayrıntıdan
     * yeni takımın kontrol satırlarını okumasın; zaman çizelgesiyle aynı kapı). {@code TRUE} görülebilir ya da izleme
     * bulunamadı (silinmiş — satırın kapısı geçerli), {@code FALSE} görülemez, {@code null} okunamadı.
     */
    record CurrentTeam(Boolean visible, Long teamId) {}

    CurrentTeam currentTeam(ActivityLog a, HttpSession session) {
        String type = a.getMonitorType() == null ? "" : a.getMonitorType().toUpperCase(Locale.ROOT);
        try {
            Long teamId;
            boolean known;
            if (a.getMonitorId() != null && timelines != null) {
                ActivityTimelineService.MonitorRef ref = timelines.resolve(type, a.getMonitorId());
                known = ref != null;
                teamId = ref == null ? null : ref.teamId();
            } else if (a.getMonitorId() == null && (ActivityLogService.CERT.equals(type) || ActivityLogService.UPTIME.equals(type))
                    && hostOf(a.getTarget()) != null) {
                Optional<CertificateInventory> inv = inventoryRepo.findByDomain(hostOf(a.getTarget()));
                known = inv.isPresent();
                teamId = inv.map(CertificateInventory::getTeamId).orElse(null);
            } else {
                known = false;
                teamId = null;
            }
            if (!known) return new CurrentTeam(Boolean.TRUE, a.getTeamId());
            return new CurrentTeam(SessionScope.canViewMonitoring(session, teamId), teamId);
        } catch (Exception e) {
            log.debug("Aktivite ayrıntısı: izlemenin güncel takımı okunamadı (#{}): {}", a.getId(), e.toString());
            return new CurrentTeam(null, a.getTeamId());
        }
    }

    /**
     * Ayrıntı. Kapı çağıranın işidir (satır görüş kapsamında değilse 404) — bu yöntem yalnız GÖRÜLEBİLEN satır için çağrılır.
     * Kontrol satırları (önceki / sonraki dahil) {@code monitoring.read/view} ister (geçmiş uçlarıyla aynı izin); yoksa
     * {@code check_hidden: true} ve kontrol alanları null. Bölümlerden biri okunamazsa o bölüm null + {@code <bölüm>_error}
     * (asla "yok" diye susmaz).
     */
    public Map<String, Object> detail(ActivityLog a, HttpSession session) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> data = dto(a);
        data.put("team_name", teamName(a.getTeamId()));
        out.put("data", data);

        ViewScope scope = ViewScope.of(session);
        CurrentTeam current = currentTeam(a, session);

        // ── Kontrol satırı + önceki / sonraki ──
        // Kapı: monitoring.read/view (geçmiş uçlarıyla aynı izin) VE izlemenin GÜNCEL takımı görülebilir (inceleme O1).
        CheckTable table = CHECK_ACTIONS.contains(a.getAction()) ? CheckTable.of(a.getMonitorType()) : null;
        boolean canReadChecks = checksReadable(permissionService, session);
        Map<String, Object> check = null;
        Map<String, Object> prev = null;
        Map<String, Object> next = null;
        if (table != null && !canReadChecks) {
            out.put("check_hidden", true);
            out.put("check_hidden_reason", "permission");
        } else if (table != null && Boolean.FALSE.equals(current.visible())) {
            out.put("check_hidden", true);
            out.put("check_hidden_reason", "team");
        } else if (table != null && current.visible() == null) {
            out.put("check_error", true);
        } else if (table != null) {
            try {
                CheckKey key = keyOf(table, a);
                Match m = key == null ? null : findCheck(table, key, a);
                if (m != null) {
                    check = new LinkedHashMap<>();
                    check.put("type", table.uiType);
                    check.put("match", m.exact ? "exact" : "approximate");
                    check.put("row", row(table, m.entity, a));
                    String at = stringField(m.entity, "checkedAt");
                    Long id = longField(m.entity, "id");
                    Object pe = neighbour(table, key, at, id, true);
                    Object ne = neighbour(table, key, at, id, false);
                    prev = pe == null ? null : withActivityId(row(table, pe, a), a, pe, scope);
                    next = ne == null ? null : withActivityId(row(table, ne, a), a, ne, scope);
                }
            } catch (Exception e) {
                log.debug("Aktivite ayrıntısı: kontrol satırı okunamadı (#{}): {}", a.getId(), e.toString());
                out.put("check_error", true);
            }
        }
        // Kontrol ayrıntısı gizliyse satırın KENDİ kontrol ayrıntısı da gizlenir (inceleme D3: aynı maskeli JSON — çözülen
        // IP'ler, vekil, gövde doğrulama iletisi — satır alanlarında görünmesin).
        if (Boolean.TRUE.equals(out.get("check_hidden"))) data.put("failure_detail", null);
        out.put("check", check);
        out.put("previous_check", prev);
        out.put("next_check", next);

        // ── ± 24 saatlik seri (aynı izlemenin kontrol satırları; oturum kapsamıyla süzülür — inceleme O1) ──
        try {
            out.put("series", series(a, scope));
        } catch (Exception e) {
            log.debug("Aktivite ayrıntısı: seri okunamadı (#{}): {}", a.getId(), e.toString());
            out.put("series", null);
            out.put("series_error", true);
        }

        // ── Alarm ── (alerts.read/view yoksa meta veri de dönmez — inceleme D4; Alarm Geçmişi'yle aynı izin)
        boolean canReadAlerts = permissionService == null || permissionService.allows(session, "alerts.read", "view");
        if (!canReadAlerts) {
            out.put("alarm", null);
            out.put("alarm_hidden", true);
        } else {
            try {
                out.put("alarm", alarm(a, session));
            } catch (Exception e) {
                log.debug("Aktivite ayrıntısı: alarm okunamadı (#{}): {}", a.getId(), e.toString());
                out.put("alarm", null);
                out.put("alarm_error", true);
            }
        }

        // ── Değişiklik bağı ──
        Map<String, Object> change = null;
        try {
            change = change(a);
        } catch (Exception e) {
            log.debug("Aktivite ayrıntısı: değişiklik kaydı okunamadı (#{}): {}", a.getId(), e.toString());
            out.put("change_error", true);
        }
        out.put("change", change);
        // Değişiklik ayrıntısı ucu monitoring.read ister (hata avı D7): izin yoksa bağ (yalnız işaretçi) kalır ama ön yüz istek
        // atmaz — "okunamadı" değil, nötr "izniniz yok"
        if (change != null && !canReadChecks) out.put("change_hidden", true);

        Map<String, Object> alarm = castMap(out.get("alarm"));
        out.put("links", links(a, alarm, change, session, current, canReadChecks));

        Map<String, Object> actor = new LinkedHashMap<>();
        actor.put("name", a.getActor());
        actor.put("request_id", a.getRequestId());
        out.put("actor", actor);
        return out;
    }

    // ── DTO ────────────────────────────────────────────────────────────────────────────────────────────────────────

    /** Liste / {@code /{id}} satırının alanları + bağlantı alanları ({@code failure_detail} nesne). */
    public static Map<String, Object> dto(ActivityLog a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("activity_time", a.getActivityTime());
        m.put("monitor_type", a.getMonitorType());
        m.put("monitor_id", a.getMonitorId());
        m.put("monitor_name", a.getMonitorName());
        m.put("target", a.getTarget());
        m.put("action", a.getAction());
        m.put("result_status", a.getResultStatus());
        m.put("result_summary", a.getResultSummary());
        m.put("result_detail", a.getResultDetail());
        m.put("error_message", a.getErrorMessage());
        m.put("error_class", a.getErrorClass());
        m.put("actor", a.getActor());
        m.put("team_id", a.getTeamId());
        m.put("response_ms", a.getResponseMs());
        m.put("days_remaining", a.getDaysRemaining());
        putRefs(m, a);
        return m;
    }

    /**
     * Bağlantı alanları (2026-11-05): {@code check_id, http_status, failure_reason, failure_detail (nesne), alert_event_id,
     * storm_id, change_seq, request_id}. Liste satırları da bu yardımcıyla genişletilebilir (eklemeli).
     */
    public static void putRefs(Map<String, Object> m, ActivityLog a) {
        m.put("check_id", a.getCheckId());
        m.put("http_status", a.getHttpStatus());
        m.put("failure_reason", a.getFailureReason());
        m.put("failure_detail", failureDetailObject(a.getFailureDetail()));
        m.put("alert_event_id", a.getAlertEventId());
        m.put("storm_id", a.getStormId());
        m.put("change_seq", a.getChangeSeq());
        m.put("request_id", a.getRequestId());
        // Alarm olayı satırının makinece okunur ayrıntısı (ön yüz özeti kendi dilinde kurar; FR D5) — yalnız ALERT_*
        if (ALERT_ACTIONS.contains(a.getAction())) m.put("alert_info", alertInfoObject(a.getResultDetail()));
    }

    /** Alarm satırının {@code result_detail} JSON'u → nesne; okunamıyor / yok → null. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> alertInfoObject(String raw) {
        if (raw == null || raw.isBlank() || raw.length() > 4000 || !raw.trim().startsWith("{")) return null;
        try {
            Object v = FALLBACK_MAPPER.readValue(raw.trim(), Map.class);
            return v instanceof Map<?, ?> map && map.get("alert_type") != null ? (Map<String, Object>) map : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * {@code failure_detail} kolonunu nesneye çevirir: JSON nesnesi → harita; başka metin (keyword'ün düz metin ayrıntısı) →
     * {@code {"text": …}}; boş → null.
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> failureDetailObject(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String t = raw.trim();
        if (t.startsWith("{")) {
            try {
                Object v = FALLBACK_MAPPER.readValue(t, Map.class);
                if (v instanceof Map<?, ?> map) return (Map<String, Object>) map;
            } catch (Exception ignore) { /* düz metin olarak döner */ }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("text", raw);
        return m;
    }

    private String teamName(Long teamId) {
        if (teamId == null || teamRepo == null) return null;
        try {
            return teamRepo.findById(teamId).map(com.sitemonitor.model.Team::getName).orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    // ── Kontrol eşlemesi ───────────────────────────────────────────────────────────────────────────────────────────

    private record Match(Object entity, boolean exact) {}

    /** Satırın kontrol anahtarı; çıkarılamıyorsa null (kontrol yok). */
    static CheckKey keyOf(CheckTable table, ActivityLog a) {
        return switch (table.key) {
            case MONITOR -> a.getMonitorId() == null ? null : new CheckKey(a.getMonitorId(), null);
            case DOMAIN -> blank(a.getTarget()) ? null : new CheckKey(a.getTarget().trim(), null);
            case DOMAIN_PORT -> {
                String t = a.getTarget();
                if (blank(t)) yield null;
                int i = t.lastIndexOf(':');
                if (i <= 0 || i == t.length() - 1) yield new CheckKey(t.trim(), 443);
                try {
                    yield new CheckKey(t.substring(0, i).trim(), Integer.parseInt(t.substring(i + 1).trim()));
                } catch (NumberFormatException e) {
                    yield new CheckKey(t.trim(), 443);
                }
            }
        };
    }

    private Match findCheck(CheckTable table, CheckKey key, ActivityLog a) {
        if (a.getCheckId() != null) {
            // Kimlik + izleme anahtarı birlikte: satırın kendi izlemesinin kontrolü değilse (olmamalı) tam eşleşme sayılmaz
            Object exact = query(table, key, "c.id = :id", "c.id ASC").setParameter("id", a.getCheckId())
                    .setMaxResults(1).getResultList().stream().findFirst().orElse(null);
            if (exact != null) return new Match(exact, true);
        }
        Instant at = parse(a.getActivityTime());
        if (at == null) return null;
        String from = ISO.format(at.minusSeconds(APPROX_WINDOW_S));
        String to = ISO.format(at.plusSeconds(APPROX_WINDOW_S)) + "~";   // "~" > "Z" / "." — saniye sonu ekleri de aralıkta
        List<?> cands = query(table, key, "c.checkedAt >= :from AND c.checkedAt <= :to", "c.checkedAt ASC, c.id ASC")
                .setParameter("from", from).setParameter("to", to)
                .setMaxResults(APPROX_CANDIDATES).getResultList();
        Object best = null;
        long bestDiff = Long.MAX_VALUE;
        for (Object c : cands) {
            Instant ct = parse(stringField(c, "checkedAt"));
            if (ct == null) continue;
            long diff = Math.abs(ct.getEpochSecond() - at.getEpochSecond());
            if (diff <= APPROX_WINDOW_S && diff < bestDiff) {
                best = c;
                bestDiff = diff;
            }
        }
        return best == null ? null : new Match(best, false);
    }

    /**
     * Aynı izlemenin önceki / sonraki kontrolü (± {@value #NEIGHBOUR_WINDOW_DAYS} gün içinde; yoksa null) — dizin dostu yerel
     * SQL ({@link #neighbourSql}) + kimlikle varlık okuma. Sıra (zaman, kimlik); aynı saniyedeki satırlar Java'da ayıklanır.
     */
    Object neighbour(CheckTable table, CheckKey key, String at, Long id, boolean before) {
        if (at == null || id == null) return null;
        Instant ai = parse(at);
        if (ai == null) return null;
        String bound = before ? ISO.format(ai.minusSeconds(NEIGHBOUR_WINDOW_DAYS * 86_400L))
                : ISO.format(ai.plusSeconds(NEIGHBOUR_WINDOW_DAYS * 86_400L)) + "~";   // "~" > saniye sonu ekleri
        List<Object[]> rows = nativeRows(table, key, neighbourSql(table, before, false), at, bound);
        List<Object[]> sorted = new ArrayList<>(rows);
        java.util.Comparator<Object[]> cmp = java.util.Comparator
                .comparing((Object[] r) -> r[1] == null ? "" : r[1].toString())
                .thenComparing(r -> r[0] instanceof Number n ? n.longValue() : 0L);
        sorted.sort(before ? cmp.reversed() : cmp);
        for (Object[] r : sorted) {
            Long rid = r[0] instanceof Number n ? n.longValue() : null;
            String rt = r[1] == null ? null : r[1].toString();
            if (rid == null || rt == null) continue;
            if (rt.equals(at) && (before ? rid >= id : rid <= id)) continue;   // çapa ve aynı saniyede öbür yandakiler
            return em.find(table.type, rid);
        }
        if (rows.size() < NEIGHBOUR_FETCH) return null;
        // Okunan adayların hepsi çapayla aynı saniyede ve öbür yanda: katı arama (tek satır)
        List<Object[]> strict = nativeRows(table, key, neighbourSql(table, before, true), at, bound);
        if (strict.isEmpty() || !(strict.get(0)[0] instanceof Number n)) return null;
        return em.find(table.type, n.longValue());
    }

    @SuppressWarnings("unchecked")
    private List<Object[]> nativeRows(CheckTable table, CheckKey key, String sql, String at, String bound) {
        var q = em.createNativeQuery(sql);
        q.setHint(TIMEOUT_HINT, QUERY_TIMEOUT_MS);
        q.setParameter("k1", key.k1());
        if (table.key == Key.DOMAIN_PORT) q.setParameter("k2", key.k2());
        q.setParameter("at", at);
        q.setParameter("bound", bound);
        List<?> raw = q.getResultList();
        List<Object[]> out = new ArrayList<>(raw.size());
        for (Object o : raw) if (o instanceof Object[] r && r.length >= 2) out.add(r);
        return out;
    }

    private TypedQueryWrapper query(CheckTable table, CheckKey key, String cond, String order) {
        var q = em.createQuery("SELECT c FROM " + table.entity + " c WHERE " + table.keyClause() + " AND " + cond
                + " ORDER BY " + order);
        q.setHint(TIMEOUT_HINT, QUERY_TIMEOUT_MS);
        q.setParameter("k1", key.k1());
        if (table.key == Key.DOMAIN_PORT) q.setParameter("k2", key.k2());
        return new TypedQueryWrapper(q);
    }

    /** Zincirleme parametre bağlamak için ince sarmalayıcı (yalnız bu sınıfın sorguları). */
    private record TypedQueryWrapper(jakarta.persistence.Query q) {
        TypedQueryWrapper setParameter(String name, Object value) {
            q.setParameter(name, value);
            return this;
        }

        TypedQueryWrapper setMaxResults(int n) {
            q.setMaxResults(n);
            return this;
        }

        List<?> getResultList() {
            return q.getResultList();
        }
    }

    /**
     * Önceki / sonraki kontrol satırına o kontrolün AKTİVİTE satırının kimliği ({@code activity_id}; ön yüzün "Bu aktiviteyi
     * aç" düğmesi): aynı izlemenin kontrol satırları kontrol anının ± 120 sn'sinde — {@code check_id} eşitse o, yoksa
     * bağsız (eski) satırlardan zamanca en yakını; bulunamazsa null. Tek okuma (dizinli, dar pencere).
     */
    private Map<String, Object> withActivityId(Map<String, Object> row, ActivityLog anchor, Object entity, ViewScope scope) {
        if (row == null) return null;
        Long id = null;
        try {
            id = activityIdFor(anchor, longField(entity, "id"), stringField(entity, "checkedAt"), scope);
        } catch (Exception e) {
            log.debug("Aktivite ayrıntısı: komşu kontrolün aktivite satırı okunamadı (#{}): {}", anchor.getId(), e.toString());
        }
        row.put("activity_id", id);
        return row;
    }

    Long activityIdFor(ActivityLog anchor, Long checkId, String checkedAt, ViewScope scope) {
        Instant at = parse(checkedAt);
        if (at == null) return null;
        String from = ISO.format(at.minusSeconds(APPROX_WINDOW_S));
        String to = ISO.format(at.plusSeconds(APPROX_WINDOW_S));
        PageRequest page = PageRequest.of(0, APPROX_CANDIDATES * 2);
        List<Object[]> rows;
        if (anchor.getMonitorId() != null) {
            rows = activityRepo.checkRowsNearForMonitor(anchor.getMonitorType(), anchor.getMonitorId(), from, to,
                    scope.scoped(), scope.list(), page);
        } else if (!blank(anchor.getTarget())) {
            rows = activityRepo.checkRowsNearForTarget(anchor.getMonitorType(), anchor.getTarget(), from, to,
                    scope.scoped(), scope.list(), page);
        } else {
            return null;
        }
        Long nearest = null;
        long best = Long.MAX_VALUE;
        for (Object[] r : rows == null ? List.<Object[]>of() : rows) {
            Long id = r[0] instanceof Number n ? n.longValue() : null;
            Long cid = r[1] instanceof Number n ? n.longValue() : null;
            if (id == null) continue;
            if (checkId != null && checkId.equals(cid)) return id;   // tam bağ
            if (cid != null) continue;                               // başka kontrolün satırı
            Instant t = parse(r[2] == null ? null : r[2].toString());
            if (t == null) continue;
            long diff = Math.abs(t.getEpochSecond() - at.getEpochSecond());
            if (diff < best) {
                best = diff;
                nearest = id;
            }
        }
        return nearest;
    }

    /** Kontrol varlığı → türün geçmiş ucundaki satır biçimi. */
    @SuppressWarnings("unchecked")
    Map<String, Object> row(CheckTable table, Object entity, ActivityLog a) {
        if (entity == null) return null;
        return switch (table) {
            case CERT -> {
                com.sitemonitor.model.CertificateCheck cc = (com.sitemonitor.model.CertificateCheck) entity;
                CertificateService cs = certificateService == null ? null : certificateService.getIfAvailable();
                Object dto = cs != null ? cs.historyRow(cc) : entity;
                Map<String, Object> r = mapper().convertValue(dto, LinkedHashMap.class);
                // Geçmiş satırı (CertificateDto) bunları taşımıyor — teşhis paneli eski kayıt sanıp neden metinden tahmin
                // ediyordu (sözleşme incelemesi O1). Varlıktan, eklemeli.
                r.putIfAbsent("error_class", cc.getErrorClass());
                r.putIfAbsent("error_stage", cc.getErrorStage());
                r.putIfAbsent("resolved_ips", cc.getResolvedIps());
                r.putIfAbsent("response_ms", cc.getResponseMs());
                yield r;
            }
            case DBQUERY -> {
                // Geçmiş ucunun biçimi + ayrıntı sayfasının okuduğu eş ad (eklemeli): error_message = error
                Map<String, Object> r = MonitoringController.dbqCheckRow((com.sitemonitor.model.DbQueryCheck) entity);
                r.putIfAbsent("error_message", r.get("error"));
                yield r;
            }
            case SCRIPTED -> scriptedRow((com.sitemonitor.model.ScriptedCheck) entity, a);
            default -> mapper().convertValue(entity, LinkedHashMap.class);
        };
    }

    /** Sentetik satır: geçmiş ucuyla aynı eşleyici + {@code checks_json}'daki gizli değerler maskeli (geçmiş ucu kuralı). */
    private Map<String, Object> scriptedRow(com.sitemonitor.model.ScriptedCheck c, ActivityLog a) {
        com.sitemonitor.model.ScriptedCheck copy = new com.sitemonitor.model.ScriptedCheck();
        org.springframework.beans.BeanUtils.copyProperties(c, copy);   // yönetilen varlık değiştirilmez
        if (copy.getChecksJson() != null && !copy.getChecksJson().isBlank()) {
            ScriptedCheckerService sc = scriptedChecker == null ? null : scriptedChecker.getIfAvailable();
            ScriptedMonitor mon = a.getMonitorId() == null ? null : em.find(ScriptedMonitor.class, a.getMonitorId());
            List<String> secrets = sc != null && mon != null ? sc.secretValues(mon) : List.of();
            if (!secrets.isEmpty()) copy.setChecksJson(SecretMask.maskValues(copy.getChecksJson(), secrets));
            else if (sc == null || mon == null) copy.setChecksJson(null);   // maskelenemiyorsa gösterilmez (güvenli taraf)
        }
        return MonitoringController.scriptedHistoryRow(copy);
    }

    private tools.jackson.databind.ObjectMapper mapper() {
        tools.jackson.databind.ObjectMapper m = objectMapper == null ? null : objectMapper.getIfAvailable();
        return m != null ? m : FALLBACK_MAPPER;
    }

    // ── Seri ───────────────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * ± 24 saat, aynı izlemenin kontrol satırları (aktivite akışından; oturum kapsamıyla süzülür — inceleme O1); ≤ 300
     * nokta (aşarsa eşit zaman kovaları). Çapanın İKİ YANI ayrı sınırlanır (her biri ≤ {@code SERIES_MAX_ROWS / 2}): tavan
     * aşılırsa pencerenin uçları kesilir, olay anı değil (inceleme D9).
     */
    Map<String, Object> series(ActivityLog a, ViewScope scope) {
        Instant at = parse(a.getActivityTime());
        if (at == null) return null;
        Instant fromI = at.minusSeconds(SERIES_HOURS * 3600L);
        Instant toI = at.plusSeconds(SERIES_HOURS * 3600L);
        Instant now = clock.instant();
        if (toI.isAfter(now)) toI = now.isAfter(at) ? now : at;   // geleceğe uzanmaz (sözleşme incelemesi D6)
        String from = ISO.format(fromI), to = ISO.format(toI), mid = ISO.format(at);
        int half = SERIES_MAX_ROWS / 2;
        PageRequest page = PageRequest.of(0, half + 1);
        List<Object[]> before, after;
        if (a.getMonitorId() != null) {
            before = activityRepo.seriesForMonitorDesc(a.getMonitorType(), a.getMonitorId(), from, mid,
                    scope.scoped(), scope.list(), page);
            after = activityRepo.seriesForMonitorAsc(a.getMonitorType(), a.getMonitorId(), mid, to,
                    scope.scoped(), scope.list(), page);
        } else if (!blank(a.getTarget())) {
            before = activityRepo.seriesForTargetDesc(a.getMonitorType(), a.getTarget(), from, mid,
                    scope.scoped(), scope.list(), page);
            after = activityRepo.seriesForTargetAsc(a.getMonitorType(), a.getTarget(), mid, to,
                    scope.scoped(), scope.list(), page);
        } else {
            before = List.of();
            after = List.of();
        }
        boolean truncated = before.size() > half || after.size() > half;
        List<Object[]> rows = new ArrayList<>(Math.min(before.size(), half) + Math.min(after.size(), half));
        List<Object[]> b = new ArrayList<>(before.subList(0, Math.min(before.size(), half)));
        java.util.Collections.reverse(b);
        rows.addAll(b);
        rows.addAll(after.subList(0, Math.min(after.size(), half)));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", from);
        out.put("to", to);
        out.put("points", points(rows, fromI, toI));
        out.put("truncated", truncated);
        return out;
    }

    /** Ham noktalar ya da (≤ 300'ü aşınca) eşit zaman kovaları: ortalama ms, en kötü durum; boş kova ms/status null. */
    static List<Map<String, Object>> points(List<Object[]> rows, Instant from, Instant to) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (rows.size() <= SERIES_MAX_POINTS) {
            for (Object[] r : rows) {
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("t", r[0]);
                p.put("ms", r[1] instanceof Number n ? n.longValue() : null);
                p.put("status", r[2]);
                out.add(p);
            }
            return out;
        }
        long span = Math.max(1, to.getEpochSecond() - from.getEpochSecond());
        double width = span / (double) SERIES_MAX_POINTS;
        long[] sum = new long[SERIES_MAX_POINTS];
        int[] cnt = new int[SERIES_MAX_POINTS];
        String[] worst = new String[SERIES_MAX_POINTS];
        for (Object[] r : rows) {
            Instant t = parse(r[0] == null ? null : r[0].toString());
            if (t == null) continue;
            int b = (int) Math.min(SERIES_MAX_POINTS - 1, Math.max(0, (t.getEpochSecond() - from.getEpochSecond()) / width));
            if (r[1] instanceof Number n) {
                sum[b] += n.longValue();
                cnt[b]++;
            }
            String st = r[2] == null ? "UNKNOWN" : r[2].toString();
            if (worst[b] == null || severity(st) > severity(worst[b])) worst[b] = st;
        }
        for (int b = 0; b < SERIES_MAX_POINTS; b++) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("t", ISO.format(from.plusSeconds((long) (b * width))));
            p.put("ms", cnt[b] > 0 ? Long.valueOf(Math.round(sum[b] / (double) cnt[b])) : null);
            p.put("status", worst[b]);
            out.add(p);
        }
        return out;
    }

    /** Kova durumunun önceliği: hata > zaman aşımı > uyarı > bilinmiyor > başarılı. */
    static int severity(String status) {
        return switch (status == null ? "" : status.toUpperCase(Locale.ROOT)) {
            case "ERROR" -> 4;
            case "TIMEOUT" -> 3;
            case "WARNING" -> 2;
            case "SUCCESS" -> 0;
            default -> 1;
        };
    }

    // ── Alarm ──────────────────────────────────────────────────────────────────────────────────────────────────────

    Map<String, Object> alarm(ActivityLog a, HttpSession session) {
        AlertEvent ev = null;
        boolean exact = false;
        if (a.getAlertEventId() != null) {
            ev = alertRepo.findById(a.getAlertEventId()).orElse(null);
            exact = ev != null;
        } else if (CHECK_ACTIONS.contains(a.getAction()) && FAILING_STATUSES.contains(a.getResultStatus())) {
            // WARNING da: HTTP / KEYWORD durum uyuşmazlığı, sentetik FAIL, sorgu ihlali uyarı yazılır ama alarm açar
            // (sözleşme incelemesi Y1 — çakışan kritik alarm bağlanmıyordu)
            ev = approximateAlarm(a);
        }
        if (ev == null) return null;
        boolean canOpen = canOpenAlarm(ev, session);
        // Yaklaşık eşleme yalnız AÇILABİLEN alarmı gösterir (inceleme D2): aynı URL / host / sentetik adı izleyen başka bir
        // takımın alarmının kimliği, seviyesi ve zamanları dönmesin.
        if (!exact && !canOpen) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", ev.getId());
        m.put("alert_type", ev.getAlertType());
        m.put("level", ev.getAlertLevel());
        m.put("opened_at", ev.getCreatedAt());
        m.put("resolved_at", Boolean.TRUE.equals(ev.getResolved()) ? ev.getResolvedAt() : null);
        m.put("resolved_by_kind", resolvedByKind(ev));
        m.put("acknowledged", Boolean.TRUE.equals(ev.getAcknowledged()));
        m.put("storm_id", ev.getStormId());
        m.put("match", exact ? "exact" : "approximate");
        m.put("can_open", canOpen);
        return m;
    }

    /** Hata / zaman aşımı kontrolünün alarmı: aynı alarm anahtarı + türün alarm tipleri; açık aralığı kontrol anını kapsayan
     *  (en son açılan) ya da kontrolden sonraki 10 dk içinde açılan (en erken). */
    private AlertEvent approximateAlarm(ActivityLog a) {
        String key = alarmKey(a);
        Set<String> types = alertTypesOf(a.getMonitorType());
        Instant at = parse(a.getActivityTime());
        if (key == null || types.isEmpty() || at == null) return null;
        List<AlertEvent> list = alertRepo.findOverlappingForHistory(key, types, ISO.format(at),
                ISO.format(at.plusSeconds(ALARM_AFTER_S)));
        String atS = ISO.format(at);
        AlertEvent covering = null, after = null;
        for (AlertEvent e : list == null ? List.<AlertEvent>of() : list) {
            String created = e.getCreatedAt();
            if (created == null) continue;
            if (created.compareTo(atS) <= 0) {
                if (covering == null || created.compareTo(covering.getCreatedAt()) > 0) covering = e;
            } else if (after == null || created.compareTo(after.getCreatedAt()) < 0) {
                after = e;
            }
        }
        return covering != null ? covering : after;
    }

    /** Alarm anahtarı (alert_events.domain) — yazıcıların hedef biçiminden (sorgu yok). */
    static String alarmKey(ActivityLog a) {
        String type = a.getMonitorType() == null ? "" : a.getMonitorType().toUpperCase(Locale.ROOT);
        String t = a.getTarget();
        return switch (type) {
            case ActivityLogService.PORT, ActivityLogService.UPTIME -> {
                if (blank(t)) yield null;
                int i = t.lastIndexOf(':');
                yield i > 0 ? t.substring(0, i) : t;
            }
            case ActivityLogService.DNS -> {
                if (blank(t)) yield null;
                int i = t.indexOf(' ');
                yield i > 0 ? t.substring(0, i) : t;
            }
            case ActivityLogService.SCRIPTED, ActivityLogService.DBQUERY -> blank(a.getMonitorName()) ? null : a.getMonitorName();
            default -> blank(t) ? null : t;
        };
    }

    /** Aktivite türünün alarm tipleri (katalog); UPTIME = erişilebilirlik, HTTP'den erişilebilirlik çıkar. */
    static Set<String> alertTypesOf(String type) {
        String t = type == null ? "" : type.toUpperCase(Locale.ROOT);
        if (ActivityLogService.UPTIME.equals(t)) return Set.of(EscalationService.TYPE_ACCESSIBILITY);
        Set<String> s = MonitorTypeCatalog.ALERT_TYPES.get(t.toLowerCase(Locale.ROOT));
        if (s == null) return Set.of();
        Set<String> out = new LinkedHashSet<>(s);
        if (ActivityLogService.HTTP.equals(t)) out.remove(EscalationService.TYPE_ACCESSIBILITY);
        return out;
    }

    static String resolvedByKind(AlertEvent ev) {
        if (!Boolean.TRUE.equals(ev.getResolved())) return null;
        if (Boolean.TRUE.equals(ev.getResolvedSilently())) return "silent";
        String by = ev.getResolvedBy();
        if (by == null || by.isBlank() || "system".equals(by) || by.startsWith("Sistem")) return "auto";
        return "manual";
    }

    /** {@code GET /api/admin/alerts/{id}} kapısının aynası: {@code alerts.read/view} + okuma kapsamı (7/24, global, takım). */
    boolean canOpenAlarm(AlertEvent ev, HttpSession session) {
        if (permissionService != null && !permissionService.allows(session, "alerts.read", "view")) return false;
        if (SessionScope.isGlobalViewer(session)) return true;
        NocCallLogService noc = nocCallLog == null ? null : nocCallLog.getIfAvailable();
        if (noc != null && noc.seesAllAlerts(session)) return true;
        Set<Long> owners = new HashSet<>();
        if (ev.getTeamId() != null) owners.add(ev.getTeamId());
        if (ev.getDomain() != null) {
            Optional<CertificateInventory> inv = inventoryRepo.findByDomain(ev.getDomain());
            inv.ifPresent(i -> {
                if (i.getTeamId() != null) owners.add(i.getTeamId());
                if (i.getUgTeamId() != null) owners.add(i.getUgTeamId());
            });
        }
        List<Long> view = SessionScope.viewTeamIdsWithGrants(session);
        return view != null && AlertOwnership.ownedByAny(owners, view);
    }

    // ── Değişiklik ─────────────────────────────────────────────────────────────────────────────────────────────────

    /** {@code {kind, id, seq, match}} — satırdaki seq TAM; eski satırda aynı kaynağın ± 120 sn içindeki kaydı YAKLAŞIK. */
    Map<String, Object> change(ActivityLog a) {
        if (a.getMonitorId() == null || a.getMonitorType() == null) return null;
        String kind = changeKind(a.getMonitorType());
        if (kind == null) return null;
        Integer seq = a.getChangeSeq();
        boolean exact = seq != null;
        if (seq == null) {
            if (!CHANGE_ACTIONS.contains(a.getAction())) return null;
            Instant at = parse(a.getActivityTime());
            if (at == null) return null;
            List<?> rows = em.createQuery("SELECT c FROM MonitorChangeLog c WHERE c.resourceKind = :kind AND c.resourceId = :id "
                            + "AND c.createdAt >= :from AND c.createdAt <= :to ORDER BY c.createdAt ASC, c.id ASC")
                    .setParameter("kind", kind.toUpperCase(Locale.ROOT))
                    .setParameter("id", a.getMonitorId())
                    .setParameter("from", ISO.format(at.minusSeconds(APPROX_WINDOW_S)))
                    .setParameter("to", ISO.format(at.plusSeconds(APPROX_WINDOW_S)) + "~")
                    .setMaxResults(APPROX_CANDIDATES).getResultList();
            String wanted = changeEventOf(a.getAction());
            long best = Long.MAX_VALUE;
            for (Object o : rows) {
                if (!(o instanceof MonitorChangeLog c) || c.getSeq() == null) continue;
                if (wanted != null && !wanted.equals(c.getEventType())) continue;
                Instant ct = parse(c.getCreatedAt());
                if (ct == null) continue;
                long diff = Math.abs(ct.getEpochSecond() - at.getEpochSecond());
                if (diff < best) {
                    best = diff;
                    seq = c.getSeq();
                }
            }
            if (seq == null) return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", kind);
        m.put("id", a.getMonitorId());
        m.put("seq", seq);
        m.put("match", exact ? "exact" : "approximate");
        return m;
    }

    /** Değişiklik ucunun {@code kind} yol parametresi (izleme türleri; sertifika / erişilebilirlik yok). */
    static String changeKind(String activityType) {
        String t = activityType.toUpperCase(Locale.ROOT);
        return MONITOR_TABS.containsKey(t) ? t.toLowerCase(Locale.ROOT) : null;
    }

    private static String changeEventOf(String action) {
        if (action == null) return null;
        return switch (action) {
            case "CREATED" -> "CREATE";
            case "DELETED" -> "DELETE";
            default -> null;   // CONFIG_CHANGED / PAUSED / RESUMED: güncelleme ya da geri yükleme
        };
    }

    // ── Bağlantılar ────────────────────────────────────────────────────────────────────────────────────────────────

    Map<String, Object> links(ActivityLog a, Map<String, Object> alarm, Map<String, Object> change, HttpSession session,
                              CurrentTeam current, boolean canReadChecks) {
        String type = a.getMonitorType() == null ? "" : a.getMonitorType().toUpperCase(Locale.ROOT);
        Map<String, Object> out = new LinkedHashMap<>();
        // İzleme başka (görülemeyen) takıma taşındıysa ya da güncel takımı okunamadıysa izlemeye derin bağlantı yok (O1)
        boolean teamVisible = current == null || Boolean.TRUE.equals(current.visible());
        // monitoring.read yoksa izleme sekmeleri (izleme / geçmiş / değişiklikler / tanılama) 403 verir → bağlantı yok
        // (hata avı D2; kontrol kapısının belgelenen sözleşmesi)
        boolean monitorVisible = teamVisible && canReadChecks;
        Map<String, Object> monitor = monitorVisible ? monitorLink(a, type, null) : null;
        out.put("monitor", monitor);
        out.put("history", monitorVisible ? monitorLink(a, type, "control") : null);
        out.put("changes", change == null || !monitorVisible ? null : monitorLink(a, type, "changes"));
        Map<String, Object> alert = null;
        if (alarm != null && Boolean.TRUE.equals(alarm.get("can_open"))) {
            alert = link("alerthistory", Map.of("alert", alarm.get("id")));
        }
        out.put("alert", alert);
        boolean diag = DIAGNOSABLE.contains(type) && monitor != null
                && (MONITOR_TABS.containsKey(type) ? a.getMonitorId() != null : !blank(a.getTarget()))
                && (permissionService == null || permissionService.allows(session, "diagnostics.run", "execute"))
                && SessionScope.canOperateTeam(session, current == null ? a.getTeamId() : current.teamId());
        out.put("can_diagnose", diag);
        // Zaman çizelgesi ucunun kapısı izlemenin GÜNCEL takımı (izin istemez) — düğme yalnız uç kabul edecekse (hata avı D8)
        out.put("can_timeline", a.getMonitorId() != null && teamVisible);
        return out;
    }

    /**
     * Kontrol ayrıntısını (kontrol satırı, komşular, satırın kendi {@code failure_detail}'i, izleme derin bağlantıları) görme
     * izni — {@code monitoring.read/view} (geçmiş uçlarıyla aynı). TEK yardımcı: ayrıntı ucu, liste ve kök neden satırları
     * aynı kuralla gizler (hata avı D1). Servis yoksa (dar test bağlamı) izinli.
     */
    public static boolean checksReadable(PermissionService permissionService, HttpSession session) {
        return permissionService == null || permissionService.allows(session, "monitoring.read", "view");
    }

    /** Sekme bağlantısı — {@code utils/activityTarget.js} kuralı; {@code mtab} verilirse izlemenin o sekmesi. */
    static Map<String, Object> monitorLink(ActivityLog a, String type, String mtab) {
        String host = hostOf(a.getTarget());
        if (ActivityLogService.CERT.equals(type)) {
            if (host == null) return null;
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("domain", host);
            p.put("open", "cert");
            return mtab == null ? link("dashboard", p) : null;   // geçmiş / değişiklik sekmesi yok (izleme bağlantısıyla aynı yer)
        }
        if (ActivityLogService.UPTIME.equals(type)) {
            if (host == null) return null;
            return mtab == null ? link("uptime", Map.of("q", host)) : null;
        }
        String tab = MONITOR_TABS.get(type);
        if (tab == null) return null;
        Map<String, Object> p = new LinkedHashMap<>();
        if (a.getMonitorId() != null) {
            p.put("monitor", a.getMonitorId());
            if (mtab != null) p.put("mtab", mtab);
        } else if (mtab != null) {
            return null;   // kimliksiz satır: sekmeye derin bağlantı kurulamaz
        }
        return link(tab, p.isEmpty() ? null : p);
    }

    private static Map<String, Object> link(String tab, Map<String, Object> params) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tab", tab);
        m.put("params", params);
        return m;
    }

    /** {@code https://x/yol} / {@code host:port} → ham host (ön yüzdeki aynı kural). */
    static String hostOf(String target) {
        if (blank(target)) return null;
        String t = target.trim().replaceFirst("(?i)^https?://", "");
        int slash = t.indexOf('/');
        if (slash >= 0) t = t.substring(0, slash);
        int colon = t.indexOf(':');
        if (colon >= 0) t = t.substring(0, colon);
        int space = t.indexOf(' ');
        if (space >= 0) t = t.substring(0, space);
        return t.isEmpty() ? null : t;
    }

    // ── yardımcılar ────────────────────────────────────────────────────────────────────────────────────────────────

    static Instant parse(String iso) {
        if (iso == null || iso.length() < 19) return null;
        try {
            return LocalDateTime.parse(iso.substring(0, 19)).toInstant(ZoneOffset.UTC);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    private static String stringField(Object entity, String name) {
        Object v = field(entity, name);
        return v == null ? null : v.toString();
    }

    private static Long longField(Object entity, String name) {
        Object v = field(entity, name);
        return v instanceof Number n ? n.longValue() : null;
    }

    /** Varlığın getter'ı ({@code getX}) — kontrol varlıkları Lombok {@code @Data}. */
    private static Object field(Object entity, String name) {
        if (entity == null) return null;
        try {
            var m = entity.getClass().getMethod("get" + Character.toUpperCase(name.charAt(0)) + name.substring(1));
            return m.invoke(entity);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }
}
