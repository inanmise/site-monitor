package com.sitemonitor.service;

import com.sitemonitor.config.CorrelationIdFilter;
import com.sitemonitor.model.ActivityLog;
import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.repository.ActivityLogRepository;
import com.sitemonitor.service.failure.CheckFailure;
import com.sitemonitor.service.failure.CheckFailureClassifier;
import com.sitemonitor.service.failure.CheckFailureReason;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Birleşik aktivite akışına (activity_log) kayıt üreten TEK yazım noktası. Her izleme türünün
 * mevcut persist noktasından ve monitör CRUD'undan çağrılır.
 *
 * <b>Best-effort:</b> tüm gövde try/catch ile sarılır — aktivite yazımı ana kontrol akışını
 * ASLA yavaşlatmaz/kırmaz; hata yutulur (debug log) ve kontrol devam eder. teamId çağıran
 * bağlamda zaten yüklü olan monitör/envanterden geçirilir (per-check EK sorgu yapılmaz).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ActivityLogService {

    public static final String CERT = "CERT", UPTIME = "UPTIME", HTTP = "HTTP", PORT = "PORT",
            PING = "PING", DNS = "DNS", KEYWORD = "KEYWORD", DOMAIN = "DOMAIN", PAGE = "PAGE", SCRIPTED = "SCRIPTED",
            PAGESPEED = "PAGESPEED", DBQUERY = "DBQUERY";

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private final ActivityLogRepository repo;

    /**
     * İşlem yöneticisi (2026-11-05, aktivite inceleme D1) — isteğe bağlı: yapıcıyla / {@code @InjectMocks} ile kurulan
     * birim testlerinde yok → bugünkü yazım (deponun {@code save}'i). İlk yazımda tembel çözülür.
     */
    @Autowired(required = false)
    private ObjectProvider<PlatformTransactionManager> txManagerProvider;

    private volatile TransactionTemplate writeTx;

    /** Test dikişi: işlem yöneticisini elle bağlar (yazım {@code REQUIRES_NEW} / COMMIT sonrası yoluna geçer). */
    void setTransactionManager(PlatformTransactionManager tx) {
        this.writeTx = tx == null ? null : newWriteTx(tx);
    }

    /** {@code result_summary} kolon boyu. */
    static final int SUMMARY_MAX = 300;

    // ── Eylem kodları (KALICI — ön yüz act.action.<KOD> etiketleri) ──────────────────────────────────────────────
    public static final String ACTION_SCHEDULED_CHECK = "SCHEDULED_CHECK";
    public static final String ACTION_MANUAL_CHECK = "MANUAL_CHECK";
    public static final String ACTION_CONFIG_CHANGED = "CONFIG_CHANGED";
    public static final String ACTION_PAUSED = "PAUSED";
    public static final String ACTION_RESUMED = "RESUMED";
    public static final String ACTION_ALERT_TRIGGERED = "ALERT_TRIGGERED";
    public static final String ACTION_ALERT_ESCALATED = "ALERT_ESCALATED";
    public static final String ACTION_ALERT_ACKNOWLEDGED = "ALERT_ACKNOWLEDGED";
    public static final String ACTION_ALERT_RESOLVED = "ALERT_RESOLVED";

    /** Satırın aktörü bilinemediğinde (zamanlanmış iş, alarm motoru). */
    public static final String SYSTEM_ACTOR = "system";

    /** {@code failure_detail} tavanı — kontrol satırlarındaki {@link CheckFailure#DETAIL_MAX} ile aynı. */
    static final int FAILURE_DETAIL_MAX = CheckFailure.DETAIL_MAX;
    /** {@code failure_reason} kolon boyu (kontrol tablolarıyla aynı). */
    static final int FAILURE_REASON_MAX = 48;
    /** {@code request_id} kolon boyu. */
    static final int REQUEST_ID_MAX = 64;

    /**
     * Aktivite satırının ayrıntı bağlantıları (2026-11-05, aktivite zenginleştirmesi A–C). Her alan isteğe bağlı; null
     * yazılmaz. Kontrol satırında {@code checkId} + kontrol satırının kendi {@code failure_reason / failure_detail}'i
     * (çağıran, kalıcılaştırdığı varlıktan verir — "kontrol satırındaki değerin aynısı"); alarm / 7/24 satırında
     * {@code alertEventId} (+ fırtınada {@code stormId}); yapılandırma satırında {@code changeSeq}.
     */
    public record Refs(Long checkId, Integer httpStatus, String failureReason, String failureDetail,
                       Long alertEventId, Long stormId, Integer changeSeq) {
        public static final Refs NONE = new Refs(null, null, null, null, null, null, null);

        /** Kontrol satırı bağı. */
        public static Refs check(Long checkId, Integer httpStatus, String failureReason, String failureDetail) {
            return new Refs(checkId, httpStatus, failureReason, failureDetail, null, null, null);
        }

        /** Alarm bağı. */
        public static Refs alert(Long alertEventId) {
            return new Refs(null, null, null, null, alertEventId, null, null);
        }

        /** Fırtına bağı (fırtınanın tetiklediği üyenin alarmıyla). */
        public static Refs storm(Long alertEventId, Long stormId) {
            return new Refs(null, null, null, null, alertEventId, stormId, null);
        }

        /** Değişiklik geçmişi bağı ({@code monitor_change_log.seq}). */
        public static Refs change(Integer changeSeq) {
            return new Refs(null, null, null, null, null, null, changeSeq);
        }
    }

    // ── Kontrol satırı → bağ (kalıcılaştırılan varlıktan: kimlik + satırın KENDİ neden kodu / ayrıntısı) ──────────────
    // Varlık null (yazım düştü) → null (bağsız satır). Sahte / kaydedilmemiş varlıkta kimlik null kalır.

    public static Refs refs(com.sitemonitor.model.PortCheck c) {
        return c == null ? null : Refs.check(c.getId(), null, c.getFailureReason(), c.getFailureDetail());
    }

    public static Refs refs(com.sitemonitor.model.PingCheck c) {
        return c == null ? null : Refs.check(c.getId(), null, c.getFailureReason(), c.getFailureDetail());
    }

    public static Refs refs(com.sitemonitor.model.DnsRecord c) {
        return c == null ? null : Refs.check(c.getId(), null, c.getFailureReason(), c.getFailureDetail());
    }

    /**
     * HTTP: tabloda neden kodu yok. Önce kontrolün kendi tanısı ({@code error_detail.kind}: STATUS_MISMATCH →
     * {@code HTTP_STATUS}, BODY_ASSERTION → {@code BODY_ASSERTION}); sonra hata metni (ön yüzün eski satır kodu,
     * {@link #httpFailureReason}); hata metni olmayan başarısız kontrol + HTTP kodu → {@code HTTP_STATUS} (sözleşme incelemesi
     * Y1: checker durum uyuşmazlığında {@code error} YAZMAZ — kod {@code UNKNOWN} kalıyor, rozet "Kontrol tamamlanamadı",
     * {@code reason=HTTP_STATUS} süzgeci bulamıyordu). Ayrıntı = {@code error_detail}.
     */
    public static Refs refs(com.sitemonitor.model.HttpCheck c) {
        return c == null ? null : Refs.check(c.getId(), c.getHttpStatus(),
                httpReason(Boolean.TRUE.equals(c.getOk()), c.getError(), c.getHttpStatus(), c.getErrorDetail()),
                c.getErrorDetail());
    }

    private static final Pattern DETAIL_KIND = Pattern.compile("\"kind\"[ ]*:[ ]*\"([A-Z_]{1,40})\"");

    /** HTTP satırının neden kodu (bkz. {@link #refs(com.sitemonitor.model.HttpCheck)}); sağlıklı satırda null. */
    static String httpReason(boolean ok, String error, Integer httpStatus, String errorDetail) {
        if (ok) return null;
        String kind = null;
        if (errorDetail != null && errorDetail.length() <= CTX_SCAN_MAX) {
            Matcher m = DETAIL_KIND.matcher(errorDetail);
            if (m.find()) kind = m.group(1);
        }
        if ("BODY_ASSERTION".equals(kind)) return CheckFailureReason.BODY_ASSERTION.name();
        if ("STATUS_MISMATCH".equals(kind)) return CheckFailureReason.HTTP_STATUS.name();
        if ((error == null || error.isBlank()) && httpStatus != null) return CheckFailureReason.HTTP_STATUS.name();
        return httpFailureReason(false, error);
    }

    public static Refs refs(com.sitemonitor.model.KeywordResult c) {
        return c == null ? null : Refs.check(c.getId(), c.getHttpStatus(), c.getFailureReason(), c.getFailureDetail());
    }

    public static Refs refs(com.sitemonitor.model.PageCheck c) {
        return c == null ? null : Refs.check(c.getId(), c.getHttpStatus(), c.getFailureReason(), c.getFailureDetail());
    }

    public static Refs refs(com.sitemonitor.model.PageSpeedCheck c) {
        return c == null ? null : Refs.check(c.getId(), c.getStatusCode(), c.getFailureReason(), c.getFailureDetail());
    }

    public static Refs refs(com.sitemonitor.model.ScriptedCheck c) {
        return c == null ? null : Refs.check(c.getId(), null, c.getFailureReason(), c.getFailureDetail());
    }

    public static Refs refs(com.sitemonitor.model.DbQueryCheck c) {
        return c == null ? null : Refs.check(c.getId(), null, c.getFailureReason(), c.getFailureDetail());
    }

    public static Refs refs(com.sitemonitor.model.DomainCheck c) {
        return c == null ? null : Refs.check(c.getId(), null, c.getFailureReason(), c.getFailureDetail());
    }

    public static Refs refs(com.sitemonitor.model.UptimeCheck c) {
        return c == null ? null : Refs.check(c.getId(), null, c.getFailureReason(), c.getFailureDetail());
    }

    /** Sertifika: tabloda neden kodu yok — ön yüzün {@code certCode} aynası ({@link #certFailureReason}). */
    public static Refs refs(com.sitemonitor.model.CertificateCheck c) {
        return c == null ? null : Refs.check(c.getId(), null,
                c.getError() == null && c.getErrorClass() == null ? null
                        : certFailureReason(c.getErrorClass(), c.getError(), c.getErrorStage()), null);
    }

    /** Kontrol sonucunu (checker Map'i) aktiviteye çevirip kaydeder. manual=true → MANUAL_CHECK. Bağlantısız (eski imza). */
    public void recordCheck(String type, Long monitorId, String monitorName, String target,
                            Long teamId, boolean manual, String actor, Map<String, Object> result) {
        recordCheck(type, monitorId, monitorName, target, teamId, manual, actor, result, null);
    }

    /**
     * Kontrol sonucunu kontrol satırı bağıyla kaydeder (2026-11-05). {@code refs} null → bugünkü satır (bağsız).
     * HTTP kodu bağda yoksa sonuç haritasından okunur; neden kodu / ayrıntı yalnız bağdan (kontrol satırının değeri) —
     * bağ vermeyen çağıran bugünkü satırı yazar.
     *
     * <p>Elle kontrolde ({@code manual=true}) aktör verilmemişse ya da yer tutucu "manual" ise oturumdaki kullanıcı adı
     * yazılır (istek iş parçacığı ya da {@link #captureContext()} ile taşınan bağlam) — yaşam döngüsü satırlarının
     * aktör kuralıyla aynı; bulunamazsa "manual".
     */
    public void recordCheck(String type, Long monitorId, String monitorName, String target,
                            Long teamId, boolean manual, String actor, Map<String, Object> result, Refs refs) {
        try {
            ActivityLog a = base(type, monitorId, monitorName, target, teamId,
                    manual ? ACTION_MANUAL_CHECK : ACTION_SCHEDULED_CHECK, manual ? manualActor(actor) : actor);
            summarize(type, result, a);
            applyCheckRefs(a, result, refs);
            save(a);
        } catch (Exception e) {
            log.debug("activity-log kayıt atlandı (type={} target={}): {}", type, target, e.toString());
        }
    }

    /** İzleme yaşam döngüsü olayı (CREATED/UPDATED/DELETED/PAUSED/RESUMED). */
    public void recordLifecycle(String type, Long monitorId, String monitorName, String target,
                                Long teamId, String action, String actor) {
        recordLifecycle(type, monitorId, monitorName, target, teamId, action, actor, null);
    }

    /**
     * Özetli yaşam döngüsü olayı — {@code CONFIG_CHANGED} için "hangi alanlar değişti" bilgisini
     * taşır. Akışta olayı görenin ayrıntı için değişiklik geçmişine gitmesi gerekmesin diye
     * özet satırın kendisinde durur; TAM eski→yeni farkı yine {@code monitor_change_log}'da.
     */
    public void recordLifecycle(String type, Long monitorId, String monitorName, String target,
                                Long teamId, String action, String actor, String summary) {
        recordLifecycle(type, monitorId, monitorName, target, teamId, action, actor, summary, null);
    }

    /** Bağlantılı yaşam döngüsü / 7/24 olayı (2026-11-05): değişiklik sırası, alarm, fırtına. {@code refs} null = bağsız. */
    public void recordLifecycle(String type, Long monitorId, String monitorName, String target,
                                Long teamId, String action, String actor, String summary, Refs refs) {
        try {
            ActivityLog a = base(type, monitorId, monitorName, target, teamId, action, actor);
            a.setResultStatus("SUCCESS");
            if (summary != null && !summary.isBlank()) a.setResultSummary(fitWords(summary, SUMMARY_MAX));
            applyRefs(a, refs);
            save(a);
        } catch (Exception e) {
            log.debug("activity-log lifecycle atlandı (type={} action={}): {}", type, action, e.toString());
        }
    }

    /**
     * Yapılandırma değişikliği satırı ({@code CONFIG_CHANGED}, değişiklik kaydının {@code seq}'i ile) ve kullanıcı
     * {@code active} alanını değiştirdiyse EK olarak {@code PAUSED} / {@code RESUMED} satırı (2026-11-05). Satırların ikisi
     * de aynı değişiklik kaydına bağlıdır. {@code changesJson} = {@code monitor_change_log.changes} (AuditDiff biçimi).
     */
    public void recordConfigChange(String type, Long monitorId, String monitorName, String target, Long teamId,
                                   String actor, String summary, Integer changeSeq, String changesJson) {
        Refs refs = changeSeq == null ? null : Refs.change(changeSeq);
        recordLifecycle(type, monitorId, monitorName, target, teamId, ACTION_CONFIG_CHANGED, actor, summary, refs);
        Boolean activeTo = activeChangedTo(changesJson);
        if (activeTo != null) {
            recordLifecycle(type, monitorId, monitorName, target, teamId,
                    activeTo ? ACTION_RESUMED : ACTION_PAUSED, actor, null, refs);
        }
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();
    /** Değişiklik farkı bu boyu aşarsa {@code active} aranmaz (değişiklik satırı yine yazılır). */
    private static final int CHANGES_SCAN_MAX = 200_000;

    /**
     * Değişiklik farkında {@code active} gerçekten değiştiyse YENİ değeri ({@code true} = sürdürüldü, {@code false} =
     * duraklatıldı); değişmediyse / okunamıyorsa null. Asla fırlatmaz.
     */
    public static Boolean activeChangedTo(String changesJson) {
        if (changesJson == null || changesJson.length() < 3 || changesJson.length() > CHANGES_SCAN_MAX
                || !changesJson.contains("\"active\"")) return null;
        try {
            var root = JSON.readTree(changesJson);
            var active = root == null ? null : root.get("active");
            if (active == null || !active.isObject()) return null;
            var from = active.get("from");
            var to = active.get("to");
            if (to == null || !to.isBoolean()) return null;
            if (from != null && from.isBoolean() && from.booleanValue() == to.booleanValue()) return null;
            return to.booleanValue();
        } catch (Exception e) {
            return null;
        }
    }

    // ── Alarm olayları (2026-11-05, aktivite zenginleştirmesi B) ─────────────────────────────────────────────────

    /**
     * Alarm olayı satırı: {@code ALERT_TRIGGERED} (ilk açılış), {@code ALERT_ESCALATED} (seviye yükseltme talebini kazanan
     * pod), {@code ALERT_ACKNOWLEDGED} (onay yazıldı), {@code ALERT_RESOLVED} (kapanışı GERÇEKTEN yapan çağıran). Çağıran
     * tam bir kez çağırır (talep / CAS kazananı). Alarm yoluna SORGU EKLENMEZ: izleme kimliği çağıranın elindeki değerden
     * ({@code monitorId}) ya da alarmın bağlam anlık görüntüsünden ({@code context_json.monitor_id}) okunur; eşlenemezse
     * null + hedef = alarm anahtarı. Yazım alarm yolunu ASLA bozmaz (her şey try/catch).
     *
     * @param event     alarm (kimliği dolu olmalı; değilse satır yazılmaz)
     * @param action    {@code ALERT_*}
     * @param level     satıra yazılacak seviye (yükseltmede YENİ seviye); null → olayın seviyesi
     * @param monitorId izleme kimliği (sertifika / erişilebilirlikte envanter kimliği); null → bağlamdan
     * @param actor     null → oturumdaki kullanıcı (istek iş parçacığı) ya da {@value #SYSTEM_ACTOR}
     * @param note      özetin sonuna eklenecek kısa açıklama (kapatan / neden); null olabilir
     */
    public void recordAlert(AlertEvent event, String action, String level, Long monitorId, String actor, String note) {
        recordAlert(event, action, level, monitorId, actor, note, null);
    }

    /**
     * Alarm satırının makinece okunur ayrıntısı (sözleşme / ön yüz incelemesi FR D5): özet ({@code result_summary}) Türkçe
     * düz metin kalır (CSV, eski istemci); ön yüz satırın KENDİ dilindeki metni bu alanlardan kurar — satırın
     * {@code result_detail} kolonuna JSON yazılır, API {@code alert_info} nesnesi olarak döner. Alanlar isteğe bağlı.
     *
     * @param fromLevel yükseltmede önceki seviye
     * @param closedBy  kapanışta {@code auto} | {@code manual} | {@code silent}
     * @param closer    elle kapatan kişinin adı ya da sessiz / otomatik kapanışın sistem nedeni (ham metin)
     * @param code      ek durum kodu: {@code maintenance_open} (bakım penceresinde açıldı)
     */
    public record AlertInfo(String fromLevel, String closedBy, String closer, String code) {
        public static AlertInfo escalated(String fromLevel) {
            return new AlertInfo(fromLevel, null, null, null);
        }

        public static AlertInfo closed(String kind, String closer) {
            return new AlertInfo(null, kind, closer, null);
        }

        public static AlertInfo code(String code) {
            return new AlertInfo(null, null, null, code);
        }
    }

    public static final String CLOSED_AUTO = "auto", CLOSED_MANUAL = "manual", CLOSED_SILENT = "silent";
    public static final String CODE_MAINTENANCE_OPEN = "maintenance_open";

    /** {@link #recordAlert(AlertEvent, String, String, Long, String, String)} + makinece okunur ayrıntı (FR D5). */
    public void recordAlert(AlertEvent event, String action, String level, Long monitorId, String actor, String note,
                            AlertInfo info) {
        try {
            if (event == null || event.getId() == null || action == null) return;
            String lvl = level != null ? level : event.getAlertLevel();
            String type = alertActivityType(event.getAlertType());
            Long mid = monitorId != null ? monitorId : monitorIdFromContext(event.getContextJson());
            String domain = event.getDomain();
            String resolvedActor = actor != null && !actor.isBlank() ? actor : userOrSystem();
            ActivityLog a = base(type, mid, monitorNameFromContext(event.getContextJson(), domain),
                    alertTarget(type, domain, event.getContextJson()), event.getTeamId(), action, resolvedActor);
            a.setResultStatus(alertStatus(action, lvl));
            StringBuilder sum = new StringBuilder(EscalationService.resolvedTypeLabel(event.getAlertType()));
            if (lvl != null && !lvl.isBlank()) sum.append(" · ").append(lvl);
            if (note != null && !note.isBlank()) sum.append(" · ").append(note.trim());
            a.setResultSummary(fitWords(sum.toString(), SUMMARY_MAX));
            a.setResultDetail(alertInfoJson(event.getAlertType(), lvl, info));
            a.setAlertEventId(event.getId());
            if (event.getStormId() != null && ACTION_ALERT_TRIGGERED.equals(action)) a.setStormId(event.getStormId());
            a.setDaysRemaining(event.getDaysRemaining());
            save(a);
        } catch (Exception e) {
            log.debug("activity-log alarm olayı atlandı (action={} alarm={}): {}", action,
                    event == null ? null : event.getId(), e.toString());
        }
    }

    /** Alarm satırının {@code result_detail} JSON'u: {@code alert_type, level, from_level?, closed_by?, closer?, code?}. */
    static String alertInfoJson(String alertType, String level, AlertInfo info) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("alert_type", alertType);
        m.put("level", level);
        if (info != null) {
            if (info.fromLevel() != null) m.put("from_level", info.fromLevel());
            if (info.closedBy() != null) m.put("closed_by", info.closedBy());
            if (info.closer() != null && !info.closer().isBlank()) m.put("closer", cut(info.closer().trim(), 200));
            if (info.code() != null) m.put("code", info.code());
        }
        try {
            return JSON.writeValueAsString(m);
        } catch (Exception e) {
            return null;
        }
    }

    /** Alarm olayının renk durumu: açılış / yükseltme seviyeye göre (CRITICAL / HIGH → ERROR, diğer → WARNING); onay / kapanış SUCCESS. */
    static String alertStatus(String action, String level) {
        if (ACTION_ALERT_ACKNOWLEDGED.equals(action) || ACTION_ALERT_RESOLVED.equals(action)) return "SUCCESS";
        String l = level == null ? "" : level.toUpperCase(Locale.ROOT);
        return "CRITICAL".equals(l) || "HIGH".equals(l) ? "ERROR" : "WARNING";
    }

    /**
     * Alarm tipi → aktivite türü. Erişilebilirlik (ACCESSIBILITY) envanterin Durum yoklamasıdır → UPTIME (katalogda http
     * altında görünür ama aktivite akışında UPTIME satırlarıyla aynı tür olmalı); sertifika tipleri CERT; kalanı katalog.
     * Bilinmeyen tip: ham değer (≤ 20 karakter, kolon boyu).
     */
    public static String alertActivityType(String alertType) {
        if (EscalationService.TYPE_ACCESSIBILITY.equals(alertType)) return UPTIME;
        String t = MonitorTypeCatalog.typeOfAlert(alertType);
        if (t != null) return t.toUpperCase(Locale.ROOT);
        String raw = alertType == null ? CERT : alertType.toUpperCase(Locale.ROOT);
        return raw.length() > 20 ? raw.substring(0, 20) : raw;
    }

    private static final Pattern CTX_MONITOR_ID = Pattern.compile("\"monitor_id\"[ ]*:[ ]*\"?([0-9]{1,18})");
    private static final Pattern CTX_MONITOR_NAME = Pattern.compile("\"monitor_name\"[ ]*:[ ]*\"((?:[^\"\\\\]|\\\\.){1,300})\"");
    /** Bağlam anlık görüntüsü bu boyu aşıyorsa izleme kimliği / adı aranmaz (düzenli ifade sınırlı girdide koşar). */
    private static final int CTX_SCAN_MAX = 16_000;

    /** Alarmın bağlam anlık görüntüsündeki {@code monitor_id} (sorgu yok) — yoksa null. */
    public static Long monitorIdFromContext(String contextJson) {
        if (contextJson == null || contextJson.isBlank() || contextJson.length() > CTX_SCAN_MAX) return null;
        try {
            Matcher m = CTX_MONITOR_ID.matcher(contextJson);
            return m.find() ? Long.parseLong(m.group(1)) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String monitorNameFromContext(String contextJson, String fallback) {
        if (contextJson == null || contextJson.isBlank() || contextJson.length() > CTX_SCAN_MAX) return fallback;
        try {
            Matcher m = CTX_MONITOR_NAME.matcher(contextJson);
            if (m.find()) {
                String v = m.group(1).replace("\\\"", "\"").replace("\\\\", "\\");
                if (!v.isBlank()) return v;
            }
        } catch (Exception ignore) { /* yedek ad */ }
        return fallback;
    }

    // ── Aktör / istek bağlamı ───────────────────────────────────────────────────────────────────────────────────

    /** İstek dışı iş parçacığına taşınan bağlam (ör. sentetik elle koşumun sanal iş parçacığı). */
    public record Context(String actor, String requestId) {
        /** Gövdeyi bu bağlamla koşar (iş parçacığı yerel değeri, sonunda eski hâline döner). */
        public <T> T call(Callable<T> body) throws Exception {
            Context outer = CONTEXT.get();
            CONTEXT.set(this);
            try {
                return body.call();
            } finally {
                if (outer == null) CONTEXT.remove();
                else CONTEXT.set(outer);
            }
        }
    }

    private static final ThreadLocal<Context> CONTEXT = new ThreadLocal<>();

    /**
     * İstek iş parçacığında çağrılır: oturumdaki kullanıcı adı + istek kimliği. İş başka iş parçacığında koşacaksa
     * ({@link Context#call}) satır yine kullanıcıyı ve istek kimliğini taşır.
     */
    public static Context captureContext() {
        Context outer = CONTEXT.get();
        String user = currentSessionUser();
        String rid = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (user == null && outer != null) user = outer.actor();
        if (rid == null && outer != null) rid = outer.requestId();
        return new Context(user, rid);
    }

    /** Oturumdaki kullanıcı adı — yalnız istek iş parçacığında (ya da taşınan bağlamda) bilinir; yoksa null. */
    public static String currentUser() {
        String u = currentSessionUser();
        if (u != null) return u;
        Context c = CONTEXT.get();
        return c != null ? c.actor() : null;
    }

    private static String currentSessionUser() {
        try {
            RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
            if (!(attrs instanceof ServletRequestAttributes sra)) return null;
            HttpServletRequest req = sra.getRequest();
            HttpSession s = req.getSession(false);
            Object u = s == null ? null : s.getAttribute("username");
            String v = u == null ? null : u.toString().trim();
            return v == null || v.isEmpty() ? null : v;
        } catch (Exception e) {   // istek bitmiş / oturum geçersiz
            return null;
        }
    }

    private static String currentRequestId() {
        String rid = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (rid == null) {
            Context c = CONTEXT.get();
            rid = c != null ? c.requestId() : null;
        }
        return rid == null || rid.isBlank() ? null : trim(rid, REQUEST_ID_MAX);
    }

    /** Elle kontrolün aktörü: verilen gerçek ad; "manual" / boş → oturumdaki kullanıcı, yoksa "manual". */
    private static String manualActor(String actor) {
        if (actor != null && !actor.isBlank() && !"manual".equals(actor)) return actor;
        String u = currentUser();
        return u != null ? u : "manual";
    }

    private static String userOrSystem() {
        String u = currentUser();
        return u != null ? u : SYSTEM_ACTOR;
    }

    // ── Yazım (2026-11-05, aktivite inceleme D1 / D5 / O2) ─────────────────────────────────────────────────────

    /**
     * Satırı yazar — çağıranın işlemine ASLA katılmaz: aktivite INSERT'i düşerse (kolon taşması, kısıt, havuz) çağıranın
     * işlemi rollback-only İŞARETLENMEZ (envanter silme / toplu işlem / içe aktarma {@code UnexpectedRollbackException} ile
     * geri alınmaz). AuditService (MP-38) deseni:
     * <ul>
     *   <li>işlem yok → hemen (deponun kendi kısa işlemi);</li>
     *   <li>açık bir işlemin içinde → COMMIT SONRASINA ertelenir ve kendi ({@code REQUIRES_NEW}) işleminde yazılır; dış işlem
     *       geri alınırsa satır HİÇ yazılmaz (olay olmadı — denetim kaydının kuralı);</li>
     *   <li>tamamlanmakta olan işlem (COMMIT sonrası geri çağrı) ya da senkronizasyon kapalı → hemen, kendi işleminde.</li>
     * </ul>
     * İşlem altyapısı bağlı değilse (elle kurulan birim testleri) bugünkü yol. Bilinen sınır (denetim kaydıyla aynı): dış
     * işlemin bağlantısı COMMIT sonrası geri çağrıda hâlâ tutulur — yazım ikinci bir havuz bağlantısı ister.
     */
    void save(ActivityLog a) {
        fitColumns(a);
        TransactionTemplate tx = writeTx();
        if (tx == null || !TransactionSynchronizationManager.isActualTransactionActive()) {
            writeNow(a);
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive() && !transactionCompleting()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    writeOwn(tx, a);
                }
            });
            return;
        }
        writeOwn(tx, a);
    }

    /** Kendi ({@code REQUIRES_NEW}) işleminde yazar; hata yutulur (COMMIT sonrası geri çağrıdan çağırana yayılmasın). */
    private void writeOwn(TransactionTemplate tx, ActivityLog a) {
        try {
            tx.executeWithoutResult(st -> writeNow(a));
        } catch (Throwable t) {
            log.debug("activity-log kendi işleminde yazılamadı (type={} action={}): {}", a.getMonitorType(), a.getAction(),
                    t.toString());
        }
    }

    private void writeNow(ActivityLog a) {
        resolveAlertMonitorId(a);
        repo.save(a);
    }

    private TransactionTemplate writeTx() {
        TransactionTemplate t = writeTx;
        if (t != null) return t;
        PlatformTransactionManager m = txManagerProvider == null ? null : txManagerProvider.getIfUnique();
        if (m == null) return null;
        t = newWriteTx(m);
        writeTx = t;
        return t;
    }

    private static TransactionTemplate newWriteTx(PlatformTransactionManager m) {
        TransactionTemplate t = new TransactionTemplate(m);
        t.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        t.setTimeout(10);
        return t;
    }

    /**
     * Dış işlem açık görünüyor ama ZATEN COMMIT / ROLLBACK oldu mu (COMMIT sonrası geri çağrı — orada kaydedilen
     * senkronizasyon bir daha çağrılmaz). JPA: bağlı {@code EntityManager}'ın işlemi artık etkin değil. Okunamıyorsa false.
     */
    private static boolean transactionCompleting() {
        try {
            for (Object res : TransactionSynchronizationManager.getResourceMap().values()) {
                if (res instanceof EntityManagerHolder h && h.getEntityManager() != null) {
                    return !h.getEntityManager().getTransaction().isActive();
                }
            }
        } catch (RuntimeException ignored) {
            // JTA EntityManager'da getTransaction() desteklenmez — işlem açık sayılır
        }
        return false;
    }

    /**
     * Sertifika / erişilebilirlik alarmının onay / kapanış / 7/24 satırına izleme kimliği (envanter kimliği; aktivite
     * inceleme O2): bu alarmlarda bağlam anlık görüntüsü kimlik taşımaz (sertifika alarmında bağlam yok), açılış satırı ise
     * kimliği yazdı → aynı alarmın {@code ALERT_TRIGGERED} satırından okunur ({@code idx_act_alert}, tek dizinli okuma,
     * 2 sn zaman aşımı). Açılış satırı yoksa (sürümden önce açılan alarm) null kalır. Asla fırlatmaz.
     */
    private void resolveAlertMonitorId(ActivityLog a) {
        if (a.getMonitorId() != null || a.getAlertEventId() == null || ACTION_ALERT_TRIGGERED.equals(a.getAction())) return;
        if (!CERT.equals(a.getMonitorType()) && !UPTIME.equals(a.getMonitorType())) return;
        try {
            List<Long> ids = repo.triggeredMonitorId(a.getAlertEventId(), PageRequest.of(0, 1));
            if (ids != null && !ids.isEmpty() && ids.get(0) != null) a.setMonitorId(ids.get(0));
        } catch (Exception e) {
            log.debug("Alarm satırının izleme kimliği okunamadı (alarm={}): {}", a.getAlertEventId(), e.toString());
        }
    }

    /** Alarm satırının hedefi: erişilebilirlikte kontrol satırlarıyla aynı {@code alan:port} (bağlamdaki port); diğerleri anahtar. */
    static String alertTarget(String type, String domain, String contextJson) {
        if (!UPTIME.equals(type) || domain == null || domain.isBlank()) return domain;
        Integer port = portFromContext(contextJson);
        return port == null ? domain : domain + ":" + port;
    }

    private static final Pattern CTX_PORT = Pattern.compile("\"port\"[ ]*:[ ]*\"?([0-9]{1,5})\\b");

    static Integer portFromContext(String contextJson) {
        if (contextJson == null || contextJson.isBlank() || contextJson.length() > CTX_SCAN_MAX) return null;
        try {
            Matcher m = CTX_PORT.matcher(contextJson);
            if (!m.find()) return null;
            int p = Integer.parseInt(m.group(1));
            return p > 0 && p <= 65535 ? p : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Kolonlara sığdırma — satır kolon taşmasıyla SESSİZCE kaybolmasın (aktivite inceleme D5). */
    static void fitColumns(ActivityLog a) {
        a.setActivityTime(cut(a.getActivityTime(), 30));
        a.setMonitorType(cut(a.getMonitorType(), 20));
        a.setMonitorName(cut(a.getMonitorName(), 300));
        a.setTarget(cut(a.getTarget(), 500));
        a.setAction(cut(a.getAction(), 30));
        a.setResultStatus(cut(a.getResultStatus(), 20));
        a.setResultSummary(fitWords(a.getResultSummary(), SUMMARY_MAX));
        a.setErrorMessage(cut(a.getErrorMessage(), 1000));
        a.setErrorClass(cut(a.getErrorClass(), 40));
        a.setActor(cut(a.getActor(), 100));
        a.setRequestId(cut(a.getRequestId(), REQUEST_ID_MAX));
        if (a.getFailureReason() != null && a.getFailureReason().length() > FAILURE_REASON_MAX) a.setFailureReason(null);
    }

    /**
     * Metni {@code max} karaktere sığdırır: sığmıyorsa son kelime sınırında (son 60 karakter içinde varsa) kesip "…" ekler;
     * vekil çifti bölünmez. Sonuç her zaman ≤ max.
     */
    static String fitWords(String s, int max) {
        if (s == null || s.length() <= max) return s;
        int cut = max - 1;
        if (Character.isHighSurrogate(s.charAt(cut - 1))) cut--;
        int floor = Math.max(cut - 60, max / 2);
        for (int i = cut; i > floor; i--) {
            if (Character.isWhitespace(s.charAt(i))) {
                cut = i;
                break;
            }
        }
        return s.substring(0, cut).stripTrailing() + "\u2026";
    }

    /** Kesme (vekil çifti bölünmez). */
    private static String cut(String s, int max) {
        if (s == null || s.length() <= max) return s;
        int end = Character.isHighSurrogate(s.charAt(max - 1)) ? max - 1 : max;
        return s.substring(0, end);
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private static ActivityLog base(String type, Long monitorId, String monitorName, String target,
                                    Long teamId, String action, String actor) {
        ActivityLog a = new ActivityLog();
        a.setActivityTime(ISO.format(Instant.now()));
        a.setMonitorType(type);
        a.setMonitorId(monitorId);
        a.setMonitorName(monitorName != null ? trim(monitorName, 300) : (target != null ? trim(target, 300) : type));
        a.setTarget(trim(target, 500));
        a.setAction(action);
        a.setActor(actor != null ? trim(actor, 100) : "scheduler");
        a.setTeamId(teamId);
        a.setRequestId(currentRequestId());
        return a;
    }

    /** Kontrol bağı: kontrol kimliği, HTTP kodu (bağ ya da sonuç), neden kodu + ayrıntı (yalnız bağdan). */
    private static void applyCheckRefs(ActivityLog a, Map<String, Object> result, Refs refs) {
        Integer http = refs != null && refs.httpStatus() != null ? refs.httpStatus()
                : result == null ? null : asInt(firstNonNull(result.get("http_status"), result.get("status_code")));
        if (http != null && http >= 100 && http <= 999) a.setHttpStatus(http);
        applyRefs(a, refs);
    }

    private static void applyRefs(ActivityLog a, Refs refs) {
        if (refs == null) return;
        if (refs.checkId() != null) a.setCheckId(refs.checkId());
        if (refs.httpStatus() != null && refs.httpStatus() >= 100 && refs.httpStatus() <= 999) a.setHttpStatus(refs.httpStatus());
        String reason = refs.failureReason();
        if (reason != null && !reason.isBlank() && reason.length() <= FAILURE_REASON_MAX) a.setFailureReason(reason);
        String detail = refs.failureDetail();
        // Kırpılmaz: yarım JSON okunamaz — tavanı aşan ayrıntı (kontrol satırı da aynı tavanı kullanır) yazılmaz.
        if (detail != null && !detail.isBlank() && detail.length() <= FAILURE_DETAIL_MAX) a.setFailureDetail(detail);
        if (refs.alertEventId() != null) a.setAlertEventId(refs.alertEventId());
        if (refs.stormId() != null) a.setStormId(refs.stormId());
        if (refs.changeSeq() != null) a.setChangeSeq(refs.changeSeq());
    }

    /**
     * Neden kodu kolonu OLMAYAN kontrol tabloları için (HTTP, sertifika) ön yüzün eski satır sınıflandırıcısıyla AYNI
     * kod — aktivite süzgeci ve ilgili satırlar paneli kodsuz kalmasın. HTTP: hata metninden
     * ({@link CheckFailureClassifier#fromMessage}, ön yüzdeki {@code codeFromText}'in aynası); sağlıklı satırda null.
     */
    public static String httpFailureReason(boolean ok, String error) {
        if (ok) return null;
        CheckFailureReason r = CheckFailureClassifier.fromMessage(error);
        return r == null ? null : r.name();
    }

    /**
     * Sertifika kontrolü: ön yüz {@code checkFailureModel.certCode}'un aynası (sunucunun {@code error_class} sınıfı +
     * ileti + aşama). Hatasız satırda null.
     */
    public static String certFailureReason(String errorClass, String error, String stage) {
        String cls = errorClass == null ? "" : errorClass.trim().toUpperCase(Locale.ROOT);
        String msg = error == null ? "" : error.trim();
        if (cls.isEmpty() && msg.isEmpty()) return null;
        String lower = msg.toLowerCase(Locale.ROOT);
        switch (cls) {
            case "DNS" -> { return "DNS_RESOLVE"; }
            case "BLOCKED" -> { return lower.startsWith("çözümlenemeyen host") ? "DNS_RESOLVE" : "SSRF_BLOCKED"; }
            case "SSL" -> {
                if (lower.contains("no name matching") || lower.contains("subject alternative")) return "TLS_HOSTNAME";
                if (lower.contains("pkix") || lower.contains("certification path") || lower.contains("self signed")
                        || lower.contains("self-signed") || lower.contains("certificate_unknown")) return "TLS_TRUST";
                return "TLS_HANDSHAKE";
            }
            case "NETWORK" -> {
                if ("proxy-connect".equals(stage) || lower.contains("vekil") || lower.contains("proxy") || lower.contains("tunnel"))
                    return "PROXY_ERROR";
                if (lower.startsWith("connection timeout")) return "tls-handshake".equals(stage) ? "TLS_HANDSHAKE" : "CONNECT_TIMEOUT";
                if (lower.contains("refused")) return "CONNECT_REFUSED";
                if (lower.contains("no route") || lower.contains("unreachable")) return "HOST_UNREACHABLE";
                if (lower.contains("reset") || lower.contains("broken pipe")) return "CONNECTION_RESET";
                CheckFailureReason r = CheckFailureClassifier.fromMessage(msg);
                return r == null || r == CheckFailureReason.UNKNOWN ? "CONNECT_TIMEOUT" : r.name();
            }
            default -> {
                if (msg.isEmpty()) return null;
                CheckFailureReason r = CheckFailureClassifier.fromMessage(msg);
                return r == null ? CheckFailureReason.UNKNOWN.name() : r.name();
            }
        }
    }

    /** Checker Map'ini result_status + result_summary + tipli alanlara eşler. Türe özgü. */
    private static void summarize(String type, Map<String, Object> r, ActivityLog a) {
        if (r == null) { a.setResultStatus("UNKNOWN"); return; }
        String error = str(r.get("error"));
        String errorClass = str(r.get("error_class"));
        a.setErrorMessage(trim(error, 1000));
        a.setErrorClass(trim(errorClass, 40));
        // elapsed_ms: sertifika checker'ının süre anahtarı. Listede olmadığı için CERT satırlarında
        // response_ms HEP NULL kalıyordu (süre ölçülüp atılıyordu) — tek satırlık isim uyuşmazlığı.
        Long ms = asLong(firstNonNull(r.get("response_ms"), r.get("rtt_ms"), r.get("elapsed_ms")));
        a.setResponseMs(ms);

        switch (type) {
            case CERT, DOMAIN -> {
                Integer days = asInt(r.get("days_remaining"));
                a.setDaysRemaining(days);
                String status = str(r.get("status"));
                a.setResultStatus(mapCertDomainStatus(type, status, error));
                a.setResultSummary(days != null ? days + "d" : (error != null ? "error" : safe(status)));
            }
            case HTTP, KEYWORD -> {
                Integer code = asInt(r.get("http_status"));
                boolean ok = truthy(r.get("ok")) || (type.equals(KEYWORD) && truthy(r.get("found")) && error == null);
                a.setResultStatus(error != null ? errStatus(errorClass) : (ok ? "SUCCESS" : (code != null ? "WARNING" : "ERROR")));
                a.setResultSummary((code != null ? code : "-") + (ms != null ? " · " + ms + "ms" : ""));
            }
            case PORT -> {
                boolean open = truthy(r.get("open"));
                a.setResultStatus(error != null ? errStatus(errorClass) : (open ? "SUCCESS" : "ERROR"));
                a.setResultSummary((open ? "open" : "closed") + (ms != null ? " · " + ms + "ms" : ""));
            }
            case PING -> {
                boolean up = truthy(r.get("up"));
                boolean na = truthy(r.get("na"));
                a.setResultStatus(na ? "UNKNOWN" : (up ? "SUCCESS" : (error != null ? errStatus(errorClass) : "ERROR")));
                Object loss = r.get("packet_loss");
                a.setResultSummary((ms != null ? ms + "ms" : (up ? "up" : "down")) + (loss != null ? " · %" + loss : ""));
            }
            case UPTIME -> {
                String status = str(r.get("status"));
                boolean up = "up".equalsIgnoreCase(status);
                a.setResultStatus(up ? "SUCCESS" : (error != null ? errStatus(errorClass) : "ERROR"));
                a.setResultSummary((up ? "up" : "down") + (ms != null ? " · " + ms + "ms" : ""));
            }
            case DNS -> {
                boolean success = truthy(r.get("success"));
                a.setResultStatus(success ? "SUCCESS" : (error != null ? errStatus(errorClass) : "ERROR"));
                a.setResultSummary((success ? "ok" : "fail") + (ms != null ? " · " + ms + "ms" : ""));
            }
            case PAGE -> {
                String status = str(r.get("status"));   // OK | DEGRADED | DOWN | CONFIG_ERROR
                Integer broken = asInt(r.get("broken_resources"));
                Integer timeouts = asInt(r.get("timeout_count"));   // 2026-08-04: kırıktan ayrı sayaç
                Integer mixed = asInt(r.get("mixed_content_count"));
                // CONFIG_ERROR (şemasız/host'suz URL) da hata sayılır — aksi halde "diğer" dalına düşüp
                // sessizce SUCCESS görünürdü; sözlük sabit (SUCCESS|WARNING|ERROR|TIMEOUT|UNKNOWN).
                a.setResultStatus("DOWN".equalsIgnoreCase(status) ? (error != null ? errStatus(errorClass) : "ERROR")
                        : ("CONFIG_ERROR".equalsIgnoreCase(status) ? "ERROR"
                        : ("DEGRADED".equalsIgnoreCase(status) ? "WARNING" : "SUCCESS")));
                String detail = "CONFIG_ERROR".equalsIgnoreCase(status) ? "yapılandırma hatası"
                        : ("DOWN".equalsIgnoreCase(status))
                        ? "yüklenemedi"
                        : ((broken != null ? broken : 0) + " kırık"
                           + (timeouts != null && timeouts > 0 ? " · " + timeouts + " zaman aşımı" : "")
                           + (mixed != null && mixed > 0 ? " · " + mixed + " mixed" : ""));
                a.setResultSummary(detail + (ms != null ? " · " + ms + "ms" : ""));
            }
            case PAGESPEED -> {
                String status = str(r.get("status"));   // OK | SLOW | DOWN | CONFIG_ERROR
                // SLOW bir KESİNTİ DEĞİL, performans olayıdır → WARNING. ERROR'a çekilirse aktivite
                // akışında yavaş sayfa çökmüş sayfayla aynı görünür ve sinyal boğulur.
                a.setResultStatus(switch (status == null ? "" : status.toUpperCase()) {
                    case "OK" -> "SUCCESS";
                    case "SLOW" -> "WARNING";
                    case "DOWN" -> error != null ? errStatus(errorClass) : "ERROR";
                    default -> "ERROR";   // CONFIG_ERROR dahil
                });
                Long bytes = asLong(r.get("total_bytes"));
                Integer reqs = asInt(r.get("request_count"));
                String detail = "CONFIG_ERROR".equalsIgnoreCase(status) ? "yapılandırma hatası"
                        : "DOWN".equalsIgnoreCase(status) ? "yüklenemedi"
                        : (bytes != null ? (bytes / 1024) + " KB" : "-")
                          + (reqs != null ? " · " + reqs + " istek" : "");
                a.setResultSummary(detail + (ms != null ? " · " + ms + "ms" : ""));
            }
            case SCRIPTED -> {
                String status = str(r.get("status"));   // PASS | FAIL | ERROR | TIMEOUT | NO_CHECKS
                a.setResultStatus(switch (status == null ? "" : status.toUpperCase()) {
                    case "PASS" -> "SUCCESS";
                    // NO_CHECKS arıza değil yapılandırma kusuru — "diğer" dalına düşüp ERROR
                    // görünmemeli (sözlük sabit: SUCCESS|WARNING|ERROR|TIMEOUT|UNKNOWN).
                    case "FAIL", "NO_CHECKS" -> "WARNING";
                    case "TIMEOUT" -> "TIMEOUT";
                    default -> "ERROR";
                });
                Integer cp = asInt(r.get("checks_passed"));
                Integer cf = asInt(r.get("checks_failed"));
                String detail = safe(status) + (cp != null || cf != null ? " · " + (cp != null ? cp : 0) + "✓/" + (cf != null ? cf : 0) + "✗" : "");
                a.setResultSummary(detail + (ms != null ? " · " + ms + "ms" : ""));
            }
            case DBQUERY -> {
                String status = str(r.get("status"));   // OK | BREACH | FAIL
                // Kural ihlali bir KESİNTİ DEĞİL (iş alarmı) → WARNING; sorgu çalışmadıysa ERROR / TIMEOUT.
                String reason = str(r.get("failure_reason"));
                a.setResultStatus(switch (status == null ? "" : status.toUpperCase()) {
                    case "OK" -> "SUCCESS";
                    case "BREACH" -> "WARNING";
                    case "FAIL" -> "DB_TIMEOUT".equals(reason) ? "TIMEOUT" : "ERROR";
                    default -> error != null ? "ERROR" : "UNKNOWN";
                });
                Integer rows = asInt(r.get("row_count"));
                Object metric = r.get("metric_value");
                String detail = "FAIL".equalsIgnoreCase(status) ? "çalıştırılamadı"
                        : (rows != null ? (truthy(r.get("row_count_capped")) ? "≥ " : "") + rows + " satır" : "-")
                          + (metric instanceof Number n && n.doubleValue() != (rows == null ? Double.NaN : rows.doubleValue())
                             ? " · değer " + metricText(n) : "");
                a.setResultSummary(detail + (ms != null ? " · " + ms + "ms" : ""));
            }
            default -> a.setResultStatus(error != null ? "ERROR" : "SUCCESS");
        }
    }

    /** Ölçülen değer: tam sayıda ".0" yazılmaz. */
    private static String metricText(Number n) {
        double d = n.doubleValue();
        if (d == Math.rint(d) && Math.abs(d) < 1e15) return String.valueOf((long) d);
        return java.math.BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
    }

    private static String mapCertDomainStatus(String type, String status, String error) {
        if (status == null) return error != null ? "ERROR" : "UNKNOWN";
        String s = status.toLowerCase();
        return switch (s) {
            case "valid", "ok" -> "SUCCESS";
            case "warning" -> "WARNING";
            case "critical" -> "ERROR";
            case "error" -> "ERROR";
            case "unknown" -> "UNKNOWN";
            default -> error != null ? "ERROR" : "SUCCESS";
        };
    }

    /** Ağ/timeout hatalarını TIMEOUT'a, diğerlerini ERROR'a eşler. */
    private static String errStatus(String errorClass) {
        return errorClass != null && errorClass.toUpperCase().contains("TIMEOUT") ? "TIMEOUT" : "ERROR";
    }

    private static Object firstNonNull(Object... values) {
        for (Object v : values) if (v != null) return v;
        return null;
    }
    private static boolean truthy(Object o) { return Boolean.TRUE.equals(o) || "true".equalsIgnoreCase(String.valueOf(o)); }
    private static String safe(String s) { return s != null ? s : ""; }

    private static String str(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }

    private static Integer asInt(Object o) {
        if (o instanceof Number n) return n.intValue();
        try { return o != null ? Integer.valueOf(String.valueOf(o)) : null; } catch (Exception e) { return null; }
    }

    private static Long asLong(Object o) {
        if (o instanceof Number n) return n.longValue();
        try { return o != null ? Long.valueOf(String.valueOf(o)) : null; } catch (Exception e) { return null; }
    }

    private static String trim(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }
}
