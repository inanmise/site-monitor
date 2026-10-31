package com.sitemonitor.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.DbQueryMonitor;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.DbQueryMonitorRepository;
import com.sitemonitor.service.AlertOwnership;
import com.sitemonitor.service.EscalationService;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.dbquery.DbQueryAlertRowsService;
import com.sitemonitor.util.Msg;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

/**
 * Veritabanı sorgu alarmının ALARM ANINDAKİ sonuç satırları (2026-10-31, sözleşme §7) — alarm ayrıntısındaki "Alarm
 * anındaki sorgu sonucu" bölümü.
 *
 * <p><b>Erişim kuralı = alarmı okuyabilme kuralı</b> ({@code AdminController.getAlert} ile aynı): {@code alerts.read/view}
 * izni + alarmın takımı oturumun görüş kapsamında (erişim paylaşımı dahil); global görüntüleyici ve 7/24 operatörü alarmı
 * okur. <b>Satırların kendisi</b> (iş verisi, kişisel veri olabilir) yalnız izlemenin takımını KENDİ kapsamıyla görebilene
 * ({@link SessionScope#canView} — üyelik / yönetim / görüş) ya da global görüntüleyiciye açılır; 7/24 operatörü ve paylaşımla
 * okuyan yalnız satır sayısını alır ({@code hidden: true}) — inceleme 2026-10-31, K4 (eskiden 7/24 operatörü her takımın
 * alarm anı satırlarını tam görüyordu). Kural alarm maili dışındaki her görünümde aynıdır.
 *
 * <p>Yanıt {@code no-store}; satırlar asla günlüğe / denetime yazılmaz. Kayıt yoksa (sorgu hatası alarmı, satırlar maile
 * eklenmiyor, eski alarm) {@code data: null} + NEDEN ({@code reason}, ek alan — inceleme ikinci tur D-4: "okunamadı" ile
 * "satır yok" aynı görünüyordu): {@value #REASON_NONE} = saklı satır yok (alarm satırsız açılmış ya da saklama süresi dolmuş),
 * {@value #REASON_ROWS_DISABLED} = izlemenin "satırları maile ekle" ayarı kapalı, {@value #REASON_UNAVAILABLE} = izleme ayarı
 * okunamadı, izleme silinmiş ya da satır kaydı okunamadı (bilinmiyor — "yok" DEĞİL). Neden, servisin boş dönüşünden sonra
 * izleme kaydından çıkarılır (izleme kimliği alarm bağlamındaki {@code monitor_id}, yoksa alarm anahtarı = izleme adı).
 */
@RestController
@RequestMapping("/api/monitoring/dbquery/alerts")
public class DbQueryAlertRowsController {

    private final PermissionService permissionService;
    private final AlertEventRepository alertEventRepo;
    private final DbQueryAlertRowsService alertRows;

    /** 7/24 operatörü kurum geneli okur — isteğe bağlı (dilimli bağlamda yoksa yalnız global görüntüleyici). */
    @Autowired(required = false)
    private com.sitemonitor.service.noc.NocCallLogService nocCallLog;

    /** Boş yanıtın nedeni için izleme kaydı (D-4) — isteğe bağlı; yokken neden {@value #REASON_UNAVAILABLE} (bilinmiyor). */
    @Autowired(required = false)
    private DbQueryMonitorRepository monitorRepo;

    /** Satır yokken nedenler — tel değerleri KALICI (ön yüz bunlara göre yazar). */
    public static final String REASON_NONE = "none", REASON_ROWS_DISABLED = "rows_disabled", REASON_UNAVAILABLE = "unavailable";

    private static final Logger log = LoggerFactory.getLogger(DbQueryAlertRowsController.class);
    private static final ObjectMapper CTX_JSON = new ObjectMapper();
    /** Bağlam JSON'u sınırı (ayrıştırma bütçesi). */
    static final int MAX_CTX_CHARS = 64 * 1024;

    void setMonitorRepo(DbQueryMonitorRepository repo) { this.monitorRepo = repo; }

    public DbQueryAlertRowsController(PermissionService permissionService, AlertEventRepository alertEventRepo,
                                      DbQueryAlertRowsService alertRows) {
        this.permissionService = permissionService;
        this.alertEventRepo = alertEventRepo;
        this.alertRows = alertRows;
    }

    @GetMapping("/{alertId}/rows")
    public ResponseEntity<Map<String, Object>> rows(@PathVariable Long alertId, HttpSession session) {
        permissionService.require(session, "alerts.read", "view");
        AlertEvent ev = alertEventRepo.findById(alertId)
                .orElseThrow(() -> new NoSuchElementException(Msg.t("Alarm bulunamadı", "Alert not found")));
        boolean seesAll = SessionScope.isGlobalViewer(session) || (nocCallLog != null && nocCallLog.seesAllAlerts(session));
        Set<Long> owners = readTeams(ev);
        if (!seesAll && !AlertOwnership.ownedByAny(owners, SessionScope.viewTeamIdsWithGrants(session))) {
            throw new SecurityException(Msg.t("Bu alarm sizin takım(lar)ınıza ait değil",
                    "This alert does not belong to your team(s)"));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        boolean dbq = EscalationService.isDbQuery(ev.getAlertType());
        Optional<DbQueryAlertRowsService.Rows> rows = Optional.empty();
        boolean readFailed = false;
        if (dbq) {
            try {
                rows = alertRows.stored(alertId);
            } catch (RuntimeException e) {
                // Satır kaydı okunamadı: "satır yok" DEĞİL — neden bilinmiyor (D-4)
                log.warn("Sorgu alarmı satırları okunamadı (olay {}): {}", alertId, e.getClass().getSimpleName());
                readFailed = true;
            }
        }
        body.put("data", rows.map(r -> view(r, !fullRows(session, owners))).orElse(null));
        if (rows.isEmpty()) body.put("reason", !dbq ? REASON_NONE : readFailed ? REASON_UNAVAILABLE : emptyReason(ev));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    /**
     * Servis satır döndürmediğinde nedeni izleme kaydından çıkarır (D-4): izleme okunamadı / silinmiş / kimliği çözülemedi →
     * {@value #REASON_UNAVAILABLE}; "satırları maile ekle" kapalı → {@value #REASON_ROWS_DISABLED}; aksi → {@value #REASON_NONE}.
     */
    String emptyReason(AlertEvent ev) {
        DbQueryMonitorRepository repo = monitorRepo;
        if (repo == null) return REASON_UNAVAILABLE;
        try {
            Long id = monitorIdOf(ev.getContextJson());
            Optional<DbQueryMonitor> m = id != null ? repo.findById(id)
                    : ev.getDomain() == null ? Optional.empty() : repo.findFirstByNameOrderByIdAsc(ev.getDomain());
            if (m.isEmpty()) return REASON_UNAVAILABLE;
            return Boolean.FALSE.equals(m.get().getMailIncludeRows()) ? REASON_ROWS_DISABLED : REASON_NONE;
        } catch (RuntimeException e) {
            log.warn("Sorgu alarmının izleme ayarı okunamadı (olay {}): {}", ev.getId(), e.getClass().getSimpleName());
            return REASON_UNAVAILABLE;
        }
    }

    /** Alarm bağlamındaki {@code monitor_id} (sayı ya da sayısal metin); yok / bozuk / aşırı uzun → null. */
    static Long monitorIdOf(String contextJson) {
        if (contextJson == null || contextJson.isBlank() || contextJson.length() > MAX_CTX_CHARS) return null;
        try {
            JsonNode n = CTX_JSON.readTree(contextJson).get("monitor_id");
            if (n == null || n.isNull()) return null;
            if (n.isNumber()) return n.longValue();
            String t = n.asText().trim();
            return t.isEmpty() ? null : Long.valueOf(t);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Satırların TAMAMI görülebilir mi (K4): global görüntüleyici ya da alarmın takımını KENDİ görüş kapsamıyla gören
     * ({@link SessionScope#canView}). 7/24 operatörlüğü ({@code canViewMonitoringOwn}'un 7/24 genişlemesi) ve erişim paylaşımı
     * satırları AÇMAZ; takımı olmayan (sahipsiz) alarmın satırları yalnız global görüntüleyiciye.
     */
    static boolean fullRows(HttpSession session, Set<Long> owners) {
        if (SessionScope.isGlobalViewer(session)) return true;
        for (Long t : owners) if (SessionScope.canView(session, t)) return true;
        return false;
    }

    /** Alarmın okuma takımları — bağımsız izleme: damgalı takım (sorgu izlemesi envanterden yönlenmez). */
    private static Set<Long> readTeams(AlertEvent ev) {
        Set<Long> ids = new HashSet<>();
        if (ev.getTeamId() != null) ids.add(ev.getTeamId());
        return ids;
    }

    static Map<String, Object> view(DbQueryAlertRowsService.Rows r, boolean hidden) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (hidden) {
            m.put("hidden", true);
            m.put("row_count", r.rowCount());
            m.put("row_count_capped", r.rowCountCapped());
            return m;
        }
        m.put("captured_at", r.capturedAt());
        m.put("columns", r.columns());
        m.put("rows", r.rows());
        m.put("masked", r.masked());
        m.put("row_count", r.rowCount());
        m.put("row_count_capped", r.rowCountCapped());
        m.put("truncated", r.truncated());
        m.put("hidden", false);
        return m;
    }
}
