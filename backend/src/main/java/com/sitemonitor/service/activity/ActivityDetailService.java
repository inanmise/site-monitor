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

    static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

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
        PORT("PortCheck", Key.MONITOR, "port"), PING("PingCheck", Key.MONITOR, "ping"), DNS("DnsRecord", Key.MONITOR, "dns"),
        HTTP("HttpCheck", Key.MONITOR, "http"), KEYWORD("KeywordResult", Key.MONITOR, "keyword"),
        PAGE("PageCheck", Key.MONITOR, "page"), PAGESPEED("PageSpeedCheck", Key.MONITOR, "pagespeed"),
        SCRIPTED("ScriptedCheck", Key.MONITOR, "scripted"), DBQUERY("DbQueryCheck", Key.MONITOR, "dbquery"),
        DOMAIN("DomainCheck", Key.MONITOR, "domain"), CERT("CertificateCheck", Key.DOMAIN, "cert"),
        UPTIME("UptimeCheck", Key.DOMAIN_PORT, "uptime");

        final String entity;
        final Key key;
        /** Ön yüzün {@code failureOf(type, row)} tür adı. */
        final String uiType;

        CheckTable(String entity, Key key, String uiType) {
            this.entity = entity;
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

        // ── Kontrol satırı + önceki / sonraki ──
        CheckTable table = CHECK_ACTIONS.contains(a.getAction()) ? CheckTable.of(a.getMonitorType()) : null;
        boolean canReadChecks = permissionService == null || permissionService.allows(session, "monitoring.read", "view");
        Map<String, Object> check = null;
        Map<String, Object> prev = null;
        Map<String, Object> next = null;
        if (table != null && !canReadChecks) {
            out.put("check_hidden", true);
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
                    prev = pe == null ? null : row(table, pe, a);
                    next = ne == null ? null : row(table, ne, a);
                }
            } catch (Exception e) {
                log.debug("Aktivite ayrıntısı: kontrol satırı okunamadı (#{}): {}", a.getId(), e.toString());
                out.put("check_error", true);
            }
        }
        out.put("check", check);
        out.put("previous_check", prev);
        out.put("next_check", next);

        // ── ± 24 saatlik seri (aynı izlemenin kontrol satırları) ──
        try {
            out.put("series", series(a));
        } catch (Exception e) {
            log.debug("Aktivite ayrıntısı: seri okunamadı (#{}): {}", a.getId(), e.toString());
            out.put("series", null);
            out.put("series_error", true);
        }

        // ── Alarm ──
        try {
            out.put("alarm", alarm(a, session));
        } catch (Exception e) {
            log.debug("Aktivite ayrıntısı: alarm okunamadı (#{}): {}", a.getId(), e.toString());
            out.put("alarm", null);
            out.put("alarm_error", true);
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

        Map<String, Object> alarm = castMap(out.get("alarm"));
        out.put("links", links(a, alarm, change, session));

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

    private Object neighbour(CheckTable table, CheckKey key, String at, Long id, boolean before) {
        if (at == null || id == null) return null;
        String cond = before
                ? "(c.checkedAt < :at OR (c.checkedAt = :at AND c.id < :id))"
                : "(c.checkedAt > :at OR (c.checkedAt = :at AND c.id > :id))";
        String order = before ? "c.checkedAt DESC, c.id DESC" : "c.checkedAt ASC, c.id ASC";
        return query(table, key, cond, order).setParameter("at", at).setParameter("id", id)
                .setMaxResults(1).getResultList().stream().findFirst().orElse(null);
    }

    private TypedQueryWrapper query(CheckTable table, CheckKey key, String cond, String order) {
        var q = em.createQuery("SELECT c FROM " + table.entity + " c WHERE " + table.keyClause() + " AND " + cond
                + " ORDER BY " + order);
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

    /** Kontrol varlığı → türün geçmiş ucundaki satır biçimi. */
    @SuppressWarnings("unchecked")
    Map<String, Object> row(CheckTable table, Object entity, ActivityLog a) {
        if (entity == null) return null;
        return switch (table) {
            case CERT -> {
                CertificateService cs = certificateService == null ? null : certificateService.getIfAvailable();
                Object dto = cs != null ? cs.historyRow((com.sitemonitor.model.CertificateCheck) entity) : entity;
                yield mapper().convertValue(dto, LinkedHashMap.class);
            }
            case DBQUERY -> MonitoringController.dbqCheckRow((com.sitemonitor.model.DbQueryCheck) entity);
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

    /** ± 24 saat, aynı izlemenin kontrol satırları (aktivite akışından); ≤ 300 nokta (aşarsa eşit zaman kovaları). */
    Map<String, Object> series(ActivityLog a) {
        Instant at = parse(a.getActivityTime());
        if (at == null) return null;
        Instant fromI = at.minusSeconds(SERIES_HOURS * 3600L);
        Instant toI = at.plusSeconds(SERIES_HOURS * 3600L);
        String from = ISO.format(fromI), to = ISO.format(toI);
        List<Object[]> rows;
        if (a.getMonitorId() != null) {
            rows = activityRepo.seriesForMonitor(a.getMonitorType(), a.getMonitorId(), from, to,
                    PageRequest.of(0, SERIES_MAX_ROWS + 1));
        } else if (!blank(a.getTarget())) {
            rows = activityRepo.seriesForTarget(a.getMonitorType(), a.getTarget(), from, to,
                    PageRequest.of(0, SERIES_MAX_ROWS + 1));
        } else {
            rows = List.of();
        }
        boolean truncated = rows.size() > SERIES_MAX_ROWS;
        if (truncated) rows = rows.subList(0, SERIES_MAX_ROWS);
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
        } else if (CHECK_ACTIONS.contains(a.getAction())
                && ("ERROR".equals(a.getResultStatus()) || "TIMEOUT".equals(a.getResultStatus()))) {
            ev = approximateAlarm(a);
        }
        if (ev == null) return null;
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
        m.put("can_open", canOpenAlarm(ev, session));
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

    Map<String, Object> links(ActivityLog a, Map<String, Object> alarm, Map<String, Object> change, HttpSession session) {
        String type = a.getMonitorType() == null ? "" : a.getMonitorType().toUpperCase(Locale.ROOT);
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> monitor = monitorLink(a, type, null);
        out.put("monitor", monitor);
        out.put("history", monitorLink(a, type, "control"));
        out.put("changes", change == null ? null : monitorLink(a, type, "changes"));
        Map<String, Object> alert = null;
        if (alarm != null && Boolean.TRUE.equals(alarm.get("can_open"))) {
            alert = link("alerthistory", Map.of("alert", alarm.get("id")));
        }
        out.put("alert", alert);
        boolean diag = DIAGNOSABLE.contains(type) && monitor != null
                && (MONITOR_TABS.containsKey(type) ? a.getMonitorId() != null : !blank(a.getTarget()))
                && (permissionService == null || permissionService.allows(session, "diagnostics.run", "execute"))
                && SessionScope.canOperateTeam(session, a.getTeamId());
        out.put("can_diagnose", diag);
        return out;
    }

    /** Sekme bağlantısı — {@code utils/activityTarget.js} kuralı; {@code mtab} verilirse izlemenin o sekmesi. */
    static Map<String, Object> monitorLink(ActivityLog a, String type, String mtab) {
        String host = hostOf(a.getTarget());
        if (ActivityLogService.CERT.equals(type)) {
            if (host == null) return null;
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("domain", host);
            p.put("open", "cert");
            return mtab == null || "control".equals(mtab) ? link("dashboard", p) : null;
        }
        if (ActivityLogService.UPTIME.equals(type)) {
            if (host == null) return null;
            return mtab == null || "control".equals(mtab) ? link("uptime", Map.of("q", host)) : null;
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
