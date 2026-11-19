package com.sitemonitor.controller;

import com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException;
import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.DbQueryMonitor;
import com.sitemonitor.model.MonitorAlertPrefs;
import com.sitemonitor.model.NotificationGroup;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.DbQueryMonitorRepository;
import com.sitemonitor.repository.DnsMonitorRepository;
import com.sitemonitor.repository.DomainMonitorRepository;
import com.sitemonitor.repository.HttpMonitorRepository;
import com.sitemonitor.repository.KeywordMonitorRepository;
import com.sitemonitor.repository.NotificationGroupRepository;
import com.sitemonitor.repository.PageMonitorRepository;
import com.sitemonitor.repository.PageSpeedMonitorRepository;
import com.sitemonitor.repository.PingMonitorRepository;
import com.sitemonitor.repository.PortMonitorRepository;
import com.sitemonitor.repository.ScriptedMonitorRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.service.AuditDetail;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.monitorbulk.MonitorBulkEdit;
import com.sitemonitor.util.ErrorTexts;
import com.sitemonitor.util.Msg;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.LongSupplier;

/**
 * İzleme sayfalarında GENİŞLETİLMİŞ toplu düzenleme (2026-11-19) — {@code POST /api/monitoring/bulk-edit}.
 *
 * <p>Kurallar {@link MonitorBulkEdit}'te. Her satır YENİDEN okunur, kapsam ve "değişmiyor" kararı burada verilir; değişen
 * satır TEKİL güncelleme ucundan ({@code MonitoringController.update*}, yalnız değişen anahtarla) geçer — yetki
 * ({@code canOperateTeam}), paylaşım yönlendirme koruması, değişiklik geçmişi, {@code MONITOR_UPDATE} denetimi, zamanlama
 * yeniden planı ve alarm kuralları tek tek düzenlemeyle BİREBİR aynı işler. Satırın hatası diğerlerini durdurmaz; sonuç
 * satır satır ({@code done | skipped + reason | failed + message}). Bütün istek için bir özet denetim satırı
 * {@code MONITOR_BULK_EDIT} (yalnız sayılar ve parametre — ad / adres değil). Sınırlar: ≤ {@link MonitorBulkEdit#MAX_ITEMS}
 * satır; her satır tek bir eşzamanlı güncelleme (döngü satır sayısıyla sınırlı, bekleme yok).
 *
 * <p>Yeni denetleyici (ContactBulkController deseni) — MonitoringController'ın kurucusu (dilim testleri) değişmez; o bean
 * alanla bağlanır. Bean yoksa (dilim / el yapımı test) uç 503 döner.
 */
@Slf4j
@RestController
@RequestMapping("/api/monitoring")
public class MonitorBulkEditController {

    private final PermissionService permissionService;
    private final AuditService auditService;

    @Autowired(required = false) MonitoringController monitoring;
    @Autowired(required = false) HttpMonitorRepository httpRepo;
    @Autowired(required = false) KeywordMonitorRepository keywordRepo;
    @Autowired(required = false) PageMonitorRepository pageRepo;
    @Autowired(required = false) PageSpeedMonitorRepository pageSpeedRepo;
    @Autowired(required = false) PingMonitorRepository pingRepo;
    @Autowired(required = false) PortMonitorRepository portRepo;
    @Autowired(required = false) DnsMonitorRepository dnsRepo;
    @Autowired(required = false) DomainMonitorRepository domainRepo;
    @Autowired(required = false) ScriptedMonitorRepository scriptedRepo;
    @Autowired(required = false) DbQueryMonitorRepository dbQueryRepo;
    @Autowired(required = false) CertificateInventoryRepository inventoryRepo;
    @Autowired(required = false) NotificationGroupRepository groupRepo;
    @Autowired(required = false) TeamRepository teamRepo;

    /**
     * İstek süre bütçesi (ms): 200 satırın her biri tekil güncelleme + zenginleştirmeden geçer; bütçe bitince kalan satırlar
     * denenmez ({@code time_budget}) — yanıt istemcinin 120 sn yazma süresinden ÖNCE döner (sonuç belirsiz kalmaz).
     */
    static final long TIME_BUDGET_MS = 90_000;
    /** Saat (testte el ile ilerletilir). */
    LongSupplier clock = System::currentTimeMillis;

    public MonitorBulkEditController(PermissionService permissionService, AuditService auditService) {
        this.permissionService = permissionService;
        this.auditService = auditService;
    }

    /** Satırın toplu düzenlemede okunan değerleri ({@code teamId} = ETKİN takım; envanter türevi Port / DNS'te envanterinki). */
    record Row(Long id, String name, Long teamId, Integer interval, String level, Boolean notifyEmail, Boolean notifyWebhook,
               Long groupId, String tags, boolean cron) {}

    @PostMapping("/bulk-edit")
    public ResponseEntity<Map<String, Object>> bulkEdit(@RequestBody Map<String, Object> body, HttpSession session,
                                                        HttpServletRequest request) {
        // Sentetik (k6) izlemenin tekil güncellemesi `monitoring.scripted` ister (crud değil) — toplu yol aynı izni sorar
        Object rawType = body == null ? null : body.get("type");
        boolean scripted = rawType != null && "scripted".equalsIgnoreCase(rawType.toString().trim());
        permissionService.require(session, scripted ? "monitoring.scripted" : "monitoring.crud", "edit");
        MonitorBulkEdit.Request req = MonitorBulkEdit.parse(body);
        if (monitoring == null) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("success", false);
            err.put("code", "UNAVAILABLE");
            err.put("error", Msg.t("Toplu düzenleme şu an kullanılamıyor — birazdan yeniden deneyin.",
                    "Bulk edit is unavailable right now — try again in a moment."));
            return ResponseEntity.status(503).body(err);
        }
        NotificationGroup group = null;
        if ("set".equals(req.groupMode())) {
            group = groupRepo == null ? null : groupRepo.findById(req.groupId()).orElse(null);
            if (group == null || !Boolean.TRUE.equals(group.getActive()))
                throw new FieldValidationException("notification_group_id", Msg.t(
                        "Seçilen bildirim grubu bulunamadı ya da silinmiş — listeyi yenileyip yeniden seçin.",
                        "The selected notification group was not found or was deleted — refresh the list and choose again."));
        }
        Map<Long, String> teamNames = new HashMap<>();
        List<Map<String, Object>> results = new ArrayList<>();
        int done = 0, skipped = 0, failed = 0;
        final long deadline = clock.getAsLong() + TIME_BUDGET_MS;
        for (Long id : req.ids()) {
            Map<String, Object> r = clock.getAsLong() >= deadline
                    ? result(id, null, null, teamNames, "skipped", "time_budget", null)
                    : applyRow(req, id, group, session, teamNames);
            switch (String.valueOf(r.get("status"))) {
                case "done" -> done++;
                case "failed" -> failed++;
                default -> skipped++;
            }
            results.add(r);
        }
        auditService.recordAction("MONITOR_BULK_EDIT", session, request, "MONITOR", req.type(),
                AuditDetail.of("type", req.type(), "action", req.action(), "value", valueSummary(req),
                        "requested", req.ids().size(), "done", done, "skipped", skipped, "failed", failed));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", req.type());
        data.put("action", req.action());
        data.put("done", done);
        data.put("skipped", skipped);
        data.put("failed", failed);
        data.put("results", results);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("data", data);
        return ResponseEntity.ok(out);
    }

    private Map<String, Object> applyRow(MonitorBulkEdit.Request req, Long id, NotificationGroup group, HttpSession session,
                                         Map<Long, String> teamNames) {
        Row row;
        try {
            row = read(req.type(), id).orElse(null);
        } catch (RuntimeException e) {
            log.warn("Toplu düzenleme: {} #{} okunamadı: {}", req.type(), id, e.getClass().getSimpleName());
            return failed(id, null, null, teamNames, Msg.t("Bu izleme okunamadı — birazdan yeniden deneyin.",
                    "This monitor could not be read — try again in a moment."));
        }
        if (row == null || !SessionScope.canViewMonitoring(session, row.teamId())) return result(id, null, null, teamNames, "skipped", "not_found", null);
        if (!SessionScope.canOperateTeam(session, row.teamId())) return result(id, row.name(), row.teamId(), teamNames, "skipped", "out_of_scope", null);
        // Yönlendirme (seviye / kanal / grup) paylaşımla yazana kapalı — tekil düzenlemenin kuralı (guardSharedNotificationFields)
        if (req.routingAction() && !SessionScope.canOperateTeamOwn(session, row.teamId()))
            return result(id, row.name(), row.teamId(), teamNames, "skipped", "routing_locked", null);

        Map<String, Object> patch = new LinkedHashMap<>();
        String reason = plan(req, row, group, patch);
        if (reason != null) return result(id, row.name(), row.teamId(), teamNames, "skipped", reason, null);

        try {
            ResponseEntity<Map<String, Object>> res = update(req.type()).apply(id, wire(req.type(), patch), session);
            int st = res == null ? 500 : res.getStatusCode().value();
            if (st >= 200 && st < 300) return result(id, row.name(), row.teamId(), teamNames, "done", "done", null);
            if (st == 404) return result(id, null, null, teamNames, "skipped", "not_found", null);
            if (st == 403) return result(id, row.name(), row.teamId(), teamNames, "skipped", "out_of_scope", null);
            return st >= 400 && st < 500
                    ? rejected(id, row.name(), row.teamId(), teamNames, errorOf(res))
                    : failed(id, row.name(), row.teamId(), teamNames, errorOf(res));
        } catch (SecurityException e) {
            return result(id, row.name(), row.teamId(), teamNames, "skipped", "out_of_scope", null);
        } catch (NoSuchElementException e) {
            return result(id, null, null, teamNames, "skipped", "not_found", null);
        } catch (DbQueryApiException e) {
            ResponseEntity<Map<String, Object>> er = e.toResponse();
            int est = er == null ? 500 : er.getStatusCode().value();
            return est >= 400 && est < 500
                    ? rejected(id, row.name(), row.teamId(), teamNames, errorOf(er))
                    : failed(id, row.name(), row.teamId(), teamNames, errorOf(er));
        } catch (IllegalArgumentException e) {
            return rejected(id, row.name(), row.teamId(), teamNames, ErrorTexts.safeMessage(e,
                    "Bu izleme güncellenemedi — değer kabul edilmedi.", "This monitor could not be updated — the value was not accepted."));
        } catch (RuntimeException e) {
            log.warn("Toplu düzenleme: {} #{} güncellenemedi: {}", req.type(), id, e.getClass().getSimpleName());
            return failed(id, row.name(), row.teamId(), teamNames, Msg.t(
                    "Bu izleme güncellenemedi — beklenmeyen bir sunucu hatası oldu; birazdan yeniden deneyin.",
                    "This monitor could not be updated — an unexpected server error occurred; try again in a moment."));
        }
    }

    /** Değişecek anahtarları {@code patch}'e yazar; değişmiyorsa / uygulanamıyorsa atlama nedeni döner. */
    static String plan(MonitorBulkEdit.Request req, Row row, NotificationGroup group, Map<String, Object> patch) {
        switch (req.action()) {
            case "interval" -> {
                if (row.cron()) return "schedule_cron";
                if (Objects.equals(row.interval(), req.intervalSeconds())) return "unchanged";
                patch.put("intervalSeconds", req.intervalSeconds());
            }
            case "alert_level" -> {
                if (MonitorAlertPrefs.effectiveLevel(row.level()).equals(req.level())) return "unchanged";
                patch.put("alertLevel", req.level());
            }
            case "channels" -> {
                if ("set".equals(req.groupMode()) && (group == null || !Objects.equals(group.getTeamId(), row.teamId())))
                    return "group_other_team";
                if (req.notifyEmail() != null && !req.notifyEmail().equals(row.notifyEmail() == null || row.notifyEmail()))
                    patch.put("notifyEmail", req.notifyEmail());
                if (req.notifyWebhook() != null && !req.notifyWebhook().equals(row.notifyWebhook() == null || row.notifyWebhook()))
                    patch.put("notifyWebhook", req.notifyWebhook());
                if ("default".equals(req.groupMode()) && row.groupId() != null) patch.put("notificationGroupId", null);
                if ("set".equals(req.groupMode()) && !Objects.equals(row.groupId(), req.groupId()))
                    patch.put("notificationGroupId", req.groupId());
                if (patch.isEmpty()) return "unchanged";
            }
            case "tags" -> {
                List<String> current = MonitorBulkEdit.tagsOf(row.tags());
                List<String> next = MonitorBulkEdit.mergeTags(current, req.tagsAdd(), req.tagsRemove());
                if (MonitorBulkEdit.sameTags(current, next)) return "unchanged";
                if (next.isEmpty()) return "last_tag";
                patch.put("tags", MonitorBulkEdit.joinTags(next));
            }
            default -> throw new IllegalStateException(req.action());
        }
        return null;
    }

    /** Veritabanı sorgusu uçları snake_case okur (dbqBody ortak anahtarları eşler); diğerleri camelCase. */
    static Map<String, Object> wire(String type, Map<String, Object> patch) {
        if (!"dbquery".equals(type)) return patch;
        Map<String, Object> out = new LinkedHashMap<>();
        patch.forEach((k, v) -> out.put(switch (k) {
            case "intervalSeconds" -> "interval_seconds";
            case "alertLevel" -> "alert_level";
            case "notifyEmail" -> "notify_email";
            case "notifyWebhook" -> "notify_webhook";
            case "notificationGroupId" -> "notification_group_id";
            default -> k;
        }, v));
        return out;
    }

    private interface Updater {
        ResponseEntity<Map<String, Object>> apply(Long id, Map<String, Object> body, HttpSession session);
    }

    private Updater update(String type) {
        return switch (type) {
            case "http" -> monitoring::updateHttp;
            case "keyword" -> monitoring::updateKeyword;
            case "page" -> monitoring::updatePage;
            case "pagespeed" -> monitoring::updatePageSpeed;
            case "ping" -> monitoring::updatePing;
            case "port" -> monitoring::updatePort;
            case "dns" -> monitoring::updateDns;
            case "domain" -> monitoring::updateDomain;
            case "scripted" -> monitoring::updateScripted;
            case "dbquery" -> monitoring::updateDbQuery;
            default -> throw new IllegalStateException(type);
        };
    }

    Optional<Row> read(String type, Long id) {
        return switch (type) {
            case "http" -> find(httpRepo, id, (m, x) -> new Row(m.getId(), nameOr(m.getName(), m.getUrl()), m.getTeamId(),
                    m.getIntervalSeconds(), m.getAlertLevel(), m.getNotifyEmail(), m.getNotifyWebhook(), m.getNotificationGroupId(), m.getTags(), false));
            case "keyword" -> find(keywordRepo, id, (m, x) -> new Row(m.getId(), nameOr(m.getName(), m.getUrl()), m.getTeamId(),
                    m.getIntervalSeconds(), m.getAlertLevel(), m.getNotifyEmail(), m.getNotifyWebhook(), m.getNotificationGroupId(), m.getTags(), false));
            case "page" -> find(pageRepo, id, (m, x) -> new Row(m.getId(), nameOr(m.getName(), m.getUrl()), m.getTeamId(),
                    m.getIntervalSeconds(), m.getAlertLevel(), m.getNotifyEmail(), m.getNotifyWebhook(), m.getNotificationGroupId(), m.getTags(), false));
            case "pagespeed" -> find(pageSpeedRepo, id, (m, x) -> new Row(m.getId(), nameOr(m.getName(), m.getUrl()), m.getTeamId(),
                    m.getIntervalSeconds(), m.getAlertLevel(), m.getNotifyEmail(), m.getNotifyWebhook(), m.getNotificationGroupId(), m.getTags(),
                    cron(m.getScheduleMode())));
            case "ping" -> find(pingRepo, id, (m, x) -> new Row(m.getId(), nameOr(m.getName(), m.getHost()), m.getTeamId(),
                    m.getIntervalSeconds(), m.getAlertLevel(), m.getNotifyEmail(), m.getNotifyWebhook(), m.getNotificationGroupId(), m.getTags(), false));
            case "port" -> find(portRepo, id, (m, x) -> new Row(m.getId(),
                    nameOr(m.getName(), m.getHost() + (m.getPort() == null ? "" : ":" + m.getPort())),
                    effectiveTeam(m.getHost(), m.getStandalone(), m.getTeamId()),
                    m.getIntervalSeconds(), m.getAlertLevel(), m.getNotifyEmail(), m.getNotifyWebhook(), m.getNotificationGroupId(), m.getTags(), false));
            case "dns" -> find(dnsRepo, id, (m, x) -> new Row(m.getId(), nameOr(m.getName(), m.getDomain()),
                    effectiveTeam(m.getDomain(), m.getStandalone(), m.getTeamId()),
                    m.getIntervalSeconds(), m.getAlertLevel(), m.getNotifyEmail(), m.getNotifyWebhook(), m.getNotificationGroupId(), m.getTags(), false));
            case "domain" -> find(domainRepo, id, (m, x) -> new Row(m.getId(), nameOr(m.getName(), m.getDomain()), m.getTeamId(),
                    m.getIntervalSeconds(), m.getAlertLevel(), m.getNotifyEmail(), m.getNotifyWebhook(), m.getNotificationGroupId(), m.getTags(), false));
            case "scripted" -> find(scriptedRepo, id, (m, x) -> new Row(m.getId(), m.getName(), m.getTeamId(),
                    m.getIntervalSeconds(), m.getAlertLevel(), m.getNotifyEmail(), m.getNotifyWebhook(), m.getNotificationGroupId(), m.getTags(),
                    cron(m.getScheduleMode())));
            case "dbquery" -> find(dbQueryRepo, id, (m, x) -> new Row(m.getId(), m.getName(), m.getTeamId(),
                    m.getIntervalSeconds(), m.getAlertLevel(), m.getNotifyEmail(), m.getNotifyWebhook(), m.getNotificationGroupId(), m.getTags(),
                    cron(m.getScheduleMode())));
            default -> Optional.empty();
        };
    }

    private static <T> Optional<Row> find(org.springframework.data.repository.CrudRepository<T, Long> repo, Long id,
                                          BiFunction<T, Long, Row> map) {
        if (repo == null) return Optional.empty();
        return repo.findById(id).map(m -> map.apply(m, id));
    }

    private static boolean cron(String mode) {
        return DbQueryMonitor.MODE_CRON.equalsIgnoreCase(mode == null ? "" : mode.trim());
    }

    private Long effectiveTeam(String domain, Boolean standalone, Long stored) {
        if (Boolean.TRUE.equals(standalone) || domain == null || inventoryRepo == null) return stored;
        CertificateInventory inv = inventoryRepo.findByDomain(domain).orElse(null);
        return MonitoringController.effectiveTeamFrom(domain, standalone, stored, inv);
    }

    private static String nameOr(String name, String fallback) {
        return name == null || name.isBlank() ? fallback : name;
    }

    private static String valueSummary(MonitorBulkEdit.Request req) {
        return switch (req.action()) {
            case "interval" -> String.valueOf(req.intervalSeconds());
            case "alert_level" -> req.level();
            case "channels" -> "email=" + req.notifyEmail() + ",webhook=" + req.notifyWebhook() + ",group=" + req.groupMode()
                    + (req.groupId() == null ? "" : ":" + req.groupId());
            case "tags" -> "+" + req.tagsAdd().size() + "/-" + req.tagsRemove().size();
            default -> req.action();
        };
    }

    private static String errorOf(ResponseEntity<Map<String, Object>> res) {
        Object e = res == null || res.getBody() == null ? null : res.getBody().get("error");
        String s = e == null ? null : e.toString();
        return s == null || s.isBlank()
                ? Msg.t("Bu izleme güncellenemedi — değer kabul edilmedi.", "This monitor could not be updated — the value was not accepted.")
                : s;
    }

    /** Tekil ucun değeri REDDETTİĞİ satır (4xx / doğrulama): başarısız, ama "beklenmeyen hata" değil — ayrıntı iletide. */
    private Map<String, Object> rejected(Long id, String name, Long teamId, Map<Long, String> teamNames, String message) {
        return result(id, name, teamId, teamNames, "failed", "rejected", message);
    }

    private Map<String, Object> failed(Long id, String name, Long teamId, Map<Long, String> teamNames, String message) {
        return result(id, name, teamId, teamNames, "failed", "error", message);
    }

    private Map<String, Object> result(Long id, String name, Long teamId, Map<Long, String> teamNames, String status,
                                       String reason, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("team_id", teamId);
        m.put("team_name", teamId == null ? null : teamName(teamId, teamNames));
        m.put("status", status);
        m.put("reason", reason);
        if (message != null) m.put("message", message);
        return m;
    }

    private String teamName(Long teamId, Map<Long, String> cache) {
        if (teamRepo == null) return null;
        return cache.computeIfAbsent(teamId, k -> {
            try {
                return teamRepo.findById(k).map(Team::getName).orElse(null);
            } catch (RuntimeException e) {
                return null;
            }
        });
    }
}
