package com.sitemonitor.controller;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.service.AlertOwnership;
import com.sitemonitor.service.EscalationService;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.dbquery.DbQueryAlertRowsService;
import com.sitemonitor.util.Msg;
import jakarta.servlet.http.HttpSession;
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
 * eklenmiyor, eski alarm) {@code data: null}.
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
        Optional<DbQueryAlertRowsService.Rows> rows = EscalationService.isDbQuery(ev.getAlertType())
                ? alertRows.stored(alertId) : Optional.empty();
        body.put("data", rows.map(r -> view(r, !fullRows(session, owners))).orElse(null));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
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
