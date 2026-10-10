package com.sitemonitor.controller;

import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.quality.DataQualityService;
import com.sitemonitor.util.Msg;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.Predicate;

/**
 * Takım veri kalitesi puanı (2026-10-10) — {@code ?tab=dataquality}.
 *
 * <ul>
 *   <li>{@code GET /api/data-quality} — kurum puanı (kıyas) + görünür takımların puanı, bandı, en çok puan kaybettiren
 *       sorunları, 30 günlük eğilim; tüm izlemeyi görenlere ayrıca Sahipsiz kovası özeti.</li>
 *   <li>{@code GET /api/data-quality/teams/{id}} — takımın düzeltme listesi (kural başına kalemler + "düzeltebilir mi");
 *       {@code id = unassigned} Sahipsiz kovası (yalnız tüm izlemeyi görenler).</li>
 * </ul>
 *
 * <p><b>Kapı:</b> {@code data_quality.view/view} (PermissionCatalog; ADMIN/TEAM_ADMIN/USER/AUDIT varsayılan açık).
 * <b>Kapsam:</b> izleme OKUMA kuralı — {@link SessionScope#canViewMonitoring} (global görüntüleyici ve 7/24 operatörü
 * tüm takımlar, diğerleri görüş kapsamı). Görmediği takımın ayrıntısı 404 (var olduğu bile söylenmez). Satır
 * bayrakları kaydın KENDİ yazma kapısından: envanter {@link SessionScope#inventoryWriteTest}, izleme
 * {@link SessionScope#canOperateTeam}, takım {@link SessionScope#canManage} — 7/24 operatör bayrağı yazma kapılarına
 * girmez. Yanıtta kullanıcı kimliği yok. {@code ?fresh=1} paylaşılan belleği en çok 5 sn'de bir atlar.
 */
@RestController
@RequestMapping("/api/data-quality")
@RequiredArgsConstructor
public class DataQualityController {

    static final String PERMISSION = "data_quality.view";

    private final DataQualityService service;
    private final PermissionService permissionService;

    @GetMapping
    public ResponseEntity<Map<String, Object>> summary(@RequestParam(name = "fresh", required = false, defaultValue = "false") boolean fresh,
                                                       HttpSession session) {
        permissionService.require(session, PERMISSION, "view");
        return ok(service.summary(viewer(session), fresh));
    }

    @GetMapping("/teams/{key}")
    public ResponseEntity<Map<String, Object>> team(@PathVariable String key,
                                                    @RequestParam(name = "fresh", required = false, defaultValue = "false") boolean fresh,
                                                    HttpSession session) {
        permissionService.require(session, PERMISSION, "view");
        Map<String, Object> data = service.teamDetail(viewer(session), key, fresh);
        if (data == null) {
            throw new NoSuchElementException(Msg.t(
                    "Bu takımın veri kalitesi bulunamadı: takım silinmiş, pasife alınmış ya da görüş alanınızın dışında olabilir. Listeyi yenileyip tekrar deneyin.",
                    "This team’s data quality wasn’t found: the team may have been deleted, deactivated or be outside your view. Refresh the list and try again."));
        }
        return ok(data);
    }

    /** Oturumdan görüntüleyen — kapsam ve satır bayrakları istek başına bir kez kurulur. */
    static DataQualityService.Viewer viewer(HttpSession session) {
        Predicate<Long> invWrite = SessionScope.inventoryWriteTest(session);
        return new DataQualityService.Viewer(
                SessionScope.seesAllMonitoring(session),
                SessionScope.isGlobalAdmin(session),
                id -> SessionScope.canViewMonitoring(session, id),
                invWrite,
                id -> SessionScope.canOperateTeam(session, id),
                id -> SessionScope.canManage(session, id));
    }

    private static ResponseEntity<Map<String, Object>> ok(Map<String, Object> data) {
        return ResponseEntity.ok(Map.of("success", true, "data", data));
    }
}
