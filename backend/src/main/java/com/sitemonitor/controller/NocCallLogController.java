package com.sitemonitor.controller;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.NocCallLog;
import com.sitemonitor.service.AuditDetail;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.noc.NocCallLogService;
import com.sitemonitor.util.Msg;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 7/24 ARAMA KAYDI uçları (2026-09-27; sözleşme {@code .migration/noc/CONTRACT.md} "Arama kaydı"):
 * <ul>
 *   <li>{@code GET    /api/alerts/{alertId}/noc-calls} — uyarıyı görebilen herkes; en yeni önce</li>
 *   <li>{@code POST   /api/alerts/{alertId}/noc-calls} — {@code noc_calls.write} (global yönetici her zaman)</li>
 *   <li>{@code DELETE /api/alerts/{alertId}/noc-calls/{id}} — kaydı giren 15 dk içinde ya da global yönetici</li>
 *   <li>{@code GET    /api/alerts/{alertId}/noc-contacts} — arama seçicisi (yazabilene); telefon YOK</li>
 * </ul>
 * Kural ve gerekçeler {@link NocCallLogService}'te. Hatalar proje eşlemesiyle: 400 doğrulama, 403 yetki, 404 yok.
 * Yazma uçları denetim kaydı ({@code NOC_CALL_LOG_ADD/DELETE}) + etkinlik akışı ({@code NOC_CALL_LOGGED/DELETED}) yazar.
 */
@RestController
@RequestMapping("/api/alerts/{alertId}")
@RequiredArgsConstructor
public class NocCallLogController {

    /** Opak kullanıcı kimliği (2026-10-08) — bean'siz dilim testinde null → eski sayısal davranış. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.sitemonitor.service.userref.UserPublicIds userPublicIds;

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private final NocCallLogService calls;
    private final AuditService auditService;

    private static ResponseEntity<Map<String, Object>> ok(Object data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("data", data);
        body.put("timestamp", ISO.format(Instant.now()));
        return ResponseEntity.ok(body);
    }

    private AlertEvent readable(Long alertId, HttpSession session) {
        AlertEvent ev = calls.loadAlert(alertId);
        if (!calls.canRead(session, ev)) throw new SecurityException(Msg.t(
                "Bu uyarı sizin takım(lar)ınıza ait değil", "This alert doesn't belong to your team(s)"));
        return ev;
    }

    private void requireWriter(HttpSession session) {
        if (!calls.canWrite(session)) throw new SecurityException(Msg.t(
                "Arama kaydı yalnız 7/24 izleme ekibine açık (noc_calls.write)",
                "Call logging is only open to the 24/7 monitoring team (noc_calls.write)"));
    }

    @GetMapping("/noc-calls")
    public ResponseEntity<Map<String, Object>> list(@PathVariable Long alertId, HttpSession session) {
        AlertEvent ev = readable(alertId, session);
        return ok(calls.list(ev, session));
    }

    @PostMapping("/noc-calls")
    public ResponseEntity<Map<String, Object>> create(@PathVariable Long alertId,
                                                      @RequestBody(required = false) Map<String, Object> body,
                                                      HttpSession session) {
        requireWriter(session);
        AlertEvent ev = calls.loadAlert(alertId);
        Map<String, Object> row = calls.create(ev, resolveContacted(body, session), session);
        auditService.recordAction("NOC_CALL_LOG_ADD", session, "ALERT_EVENT", String.valueOf(alertId),
                AuditDetail.of("domain", ev.getDomain(), "call_id", row.get("id"),
                        "contacted_name", row.get("contacted_name"), "outcome", row.get("outcome"),
                        "channel", row.get("channel"), "contacted_at", row.get("contacted_at")), null);
        return ok(row);
    }

    /**
     * Opak kimlik (2026-10-08): arama listesi kişileri global olmayan operatöre opak kimlikle gelir; servis sayısal id ile
     * eşleştirdiği için kişi kimliği burada çözülür. Çözülemeyen (bilinmeyen / izinsiz sayısal) kimlik 400 olur.
     */
    private Map<String, Object> resolveContacted(Map<String, Object> body, HttpSession session) {
        if (body == null || userPublicIds == null) return body;
        Object raw = body.get("contactedUserId");
        if (raw == null || (raw instanceof String rs && rs.isBlank())) return body;
        Long id = com.sitemonitor.service.userref.UserPublicIds.resolve(userPublicIds, raw, session);
        if (id == null) {
            throw new IllegalArgumentException(com.sitemonitor.util.Msg.t("Geçersiz kişi kimliği", "Invalid person id"));
        }
        Map<String, Object> copy = new java.util.LinkedHashMap<>(body);
        copy.put("contactedUserId", id);
        return copy;
    }

    @DeleteMapping("/noc-calls/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable Long alertId, @PathVariable Long id,
                                                      HttpSession session) {
        AlertEvent ev = calls.loadAlert(alertId);
        NocCallLog removed = calls.delete(ev, id, session);
        auditService.recordAction("NOC_CALL_LOG_DELETE", session, "ALERT_EVENT", String.valueOf(alertId),
                AuditDetail.of("domain", ev.getDomain(), "call_id", id,
                        "contacted_name", removed.getContactedName(), "outcome", removed.getOutcome(),
                        "contacted_at", removed.getContactedAt(), "created_by", removed.getCreatedBy()), null);
        return ok(Map.of("deleted", id));
    }

    @GetMapping("/noc-contacts")
    public ResponseEntity<Map<String, Object>> contacts(@PathVariable Long alertId, HttpSession session) {
        requireWriter(session);
        AlertEvent ev = calls.loadAlert(alertId);
        return ok(calls.contacts(ev));
    }
}
